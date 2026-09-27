package com.blauser.browser

import android.webkit.WebView

/**
 * 注入脚本：把 PC 页面缩放适配到手机屏、按需伪装 window.screen、按需伪装语言。
 *
 * 宽度模式（见 [SettingsManager] 的宽度常量）：
 * - `WIDTH_AUTO`：页面自己声明了 `width=device-width` 就不动它，否则当 PC 页面处理。
 *   **默认值** —— 移动端页面保持原生渲染，PC 页面才被撑宽缩放。
 * - `WIDTH_FOLLOW_DEVICE`：完全不干预宽度，交给页面自己的 viewport。
 * - `> 0`：无条件按该宽度当 PC 页面处理。
 *
 * 语言与宽度相互独立：只要设了语言，即使宽度是「跟随手机」也要注入。
 */
object PageScaler {

    fun inject(view: WebView, pageWidth: Int, language: String? = null) {
        scriptFor(pageWidth, language)?.let { view.evaluateJavascript(it, null) }
    }

    /**
     * 按配置生成要注入的脚本；返回 null 表示这个组合下什么都不用做。
     *
     * 和 [inject] 分开是为了可测：脚本生成是纯字符串拼接，
     * 不该为了测它去构造一个真 WebView。
     */
    internal fun scriptFor(pageWidth: Int, language: String?): String? {
        val lang = language?.takeIf { it.isNotBlank() }
        return when (pageWidth) {
            // 跟随手机屏：只在设了语言时才需要注入（伪装 navigator.language）
            SettingsManager.WIDTH_FOLLOW_DEVICE -> lang?.let { languageScript(it) }
            SettingsManager.WIDTH_AUTO -> autoScript(lang)
            else -> forceScript(pageWidth, lang)
        }
    }

    /** 伪装 navigator.language / languages，让页面按目标语言渲染 */
    private fun languagePrologue(lang: String?): String {
        if (lang == null) return ""
        val safe = lang.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            try {
                Object.defineProperty(navigator, 'language', {
                    get: function() { return "$safe"; }, configurable: true
                });
                Object.defineProperty(navigator, 'languages', {
                    get: function() { return ["$safe"]; }, configurable: true
                });
            } catch (e) {}
        """.trimIndent()
    }

    private fun languageScript(lang: String): String =
        "(function(){${languagePrologue(lang)}})();"

    /**
     * 强制模式：把页面按 W 宽排版，整体缩放贴合手机屏。
     *
     * realW 只取一次并缓存在 `window.__blauserRealW`：脚本会在 onPageStarted 和
     * onPageFinished 各注入一次，第二次注入时 `screen.width` 已经是伪装值了，
     * 必须用缓存值，否则 scale 会算成 1 导致页面被放大。
     */
    private fun forceScript(w: Int, lang: String?): String = """
        (function() {
            ${languagePrologue(lang)}
            var W = $w;
            if (!W || W <= 0) return;
            if (window.__blauserRealW === undefined) {
                window.__blauserRealW = window.screen.width;
            }
            var realW = window.__blauserRealW;
            if (!realW) return;

            // 1. 伪装 screen，让响应式站点按 PC 宽度渲染
            try {
                Object.defineProperty(window.screen, 'width', {
                    get: function() { return W; }, configurable: true
                });
                Object.defineProperty(window.screen, 'availWidth', {
                    get: function() { return W; }, configurable: true
                });
            } catch (e) {}

            // 2. 页面实际内容宽度 —— 固定宽度(非响应式)的 PC 页可能超过 W，不能硬压
            var docW = Math.max(
                document.documentElement ? document.documentElement.scrollWidth : 0,
                document.body ? document.body.scrollWidth : 0
            );
            var target = Math.max(docW || 0, W);

            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                (document.head || document.documentElement).appendChild(meta);
            }
            meta.setAttribute('content',
                'width=' + target +
                ', initial-scale=' + (realW / target) +
                ', maximum-scale=5.0, minimum-scale=0.1, user-scalable=yes');
        })();
    """.trimIndent()

    /**
     * 自动模式：等 `<head>` 解析完（DOMContentLoaded）再判断页面类型。
     *
     * 之所以挂在 DOMContentLoaded 而不是 onPageStarted：onPageStarted 时 `<head>` 还没解析，
     * 读不到 viewport meta。挂在 DOMContentLoaded 比 onPageFinished 早得多，能明显减少重排闪烁。
     */
    private fun autoScript(lang: String?): String = """
        (function() {
            ${languagePrologue(lang)}
            if (window.__blauserAutoHooked) return;
            window.__blauserAutoHooked = true;

            var PC_WIDTH = ${SettingsManager.AUTO_PC_WIDTH};

            function applyPcWidth(W) {
                if (window.__blauserRealW === undefined) {
                    window.__blauserRealW = window.screen.width;
                }
                var realW = window.__blauserRealW;
                if (!realW) return;
                try {
                    Object.defineProperty(window.screen, 'width', {
                        get: function() { return W; }, configurable: true
                    });
                    Object.defineProperty(window.screen, 'availWidth', {
                        get: function() { return W; }, configurable: true
                    });
                } catch (e) {}
                var docW = Math.max(
                    document.documentElement ? document.documentElement.scrollWidth : 0,
                    document.body ? document.body.scrollWidth : 0
                );
                var target = Math.max(docW || 0, W);
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    (document.head || document.documentElement).appendChild(meta);
                }
                meta.setAttribute('content',
                    'width=' + target +
                    ', initial-scale=' + (realW / target) +
                    ', maximum-scale=5.0, minimum-scale=0.1, user-scalable=yes');
            }

            function decide() {
                var m = document.querySelector('meta[name="viewport"]');
                var content = (m && m.getAttribute('content')) || '';
                // 页面自己声明了 device-width → 移动端适配页，尊重它，不干预
                if (/width\s*=\s*device-width/i.test(content)) return;
                applyPcWidth(PC_WIDTH);
            }

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', decide);
            } else {
                decide();
            }
        })();
    """.trimIndent()
}
