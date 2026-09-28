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

    fun inject(
        view: WebView,
        pageWidth: Int,
        language: String? = null,
        pageHeight: Int = 0,
        scale: Float? = null,
        userScalable: Boolean = true
    ) {
        scriptFor(pageWidth, language, pageHeight, scale, userScalable)
            ?.let { view.evaluateJavascript(it, null) }
    }

    /**
     * viewport 里的 `user-scalable` 取值。
     *
     * 这只是「告诉页面别允许缩放」的声明，真正拦住手势的是 WebSettings 的
     * setSupportZoom / setBuiltInZoomControls（见 MainActivity.applyZoomSetting）。
     * 两边都设是为了让页面自己的 JS 判断（`window.visualViewport.scale` 之类）也一致。
     */
    private fun scalableLiteral(enabled: Boolean) = if (enabled) "yes" else "no"

    /**
     * 按配置生成要注入的脚本；返回 null 表示这个组合下什么都不用做。
     *
     * 和 [inject] 分开是为了可测：脚本生成是纯字符串拼接，
     * 不该为了测它去构造一个真 WebView。
     *
     * @param pageHeight 虚拟屏幕高度，0 表示不限高（只按宽度缩放）
     * @param scale 虚拟屏幕模式下由视图尺寸反算出的缩放比；null 表示按屏宽自动算
     */
    internal fun scriptFor(
        pageWidth: Int,
        language: String?,
        pageHeight: Int = 0,
        scale: Float? = null,
        userScalable: Boolean = true
    ): String? {
        val lang = language?.takeIf { it.isNotBlank() }
        val scalable = scalableLiteral(userScalable)

        // 虚拟屏幕模式：固定宽度 + 高度 + 明确的缩放比，三者缺一不可
        if (pageHeight > 0 && scale != null && scale > 0f && pageWidth > 0) {
            return virtualScreenScript(pageWidth, scale, lang, scalable)
        }

        return when (pageWidth) {
            // 跟随手机屏：只在设了语言时才需要注入（伪装 navigator.language）
            SettingsManager.WIDTH_FOLLOW_DEVICE -> lang?.let { languageScript(it) }
            SettingsManager.WIDTH_AUTO -> autoScript(lang, scalable)
            else -> forceScript(pageWidth, lang, scalable)
        }
    }

    /**
     * 虚拟屏幕模式：按 W:H 模拟一块屏幕，等比缩放、四周留白。
     *
     * 和 [forceScript] 的两点不同：
     *  - 宽度就是 W，**不再按文档实际宽度扩张**（`Math.max(docW, W)`）。
     *    这个模式的意义就是「屏幕就这么宽」，页面比它宽就该横向滚动。
     *  - `initial-scale` 用调用方算好的值（按视图实际尺寸反推），而不是 `realW / W` ——
     *    因为留白时视图宽度不等于屏宽。
     */
    private fun virtualScreenScript(w: Int, scale: Float, lang: String?, scalable: String): String {
        // 别让 Float.toString 在极小值上吐科学计数法，那会变成非法的 CSS 数值
        val scaleLiteral = java.lang.String.format(java.util.Locale.ROOT, "%.6f", scale)
        return """
            (function() {
                ${languagePrologue(lang)}
                var W = $w;
                if (!W || W <= 0) return;
                try {
                    Object.defineProperty(window.screen, 'width', {
                        get: function() { return W; }, configurable: true
                    });
                    Object.defineProperty(window.screen, 'availWidth', {
                        get: function() { return W; }, configurable: true
                    });
                } catch (e) {}

                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    (document.head || document.documentElement).appendChild(meta);
                }
                meta.setAttribute('content',
                    'width=' + W +
                    ', initial-scale=$scaleLiteral' +
                    ', maximum-scale=5.0, minimum-scale=0.1, user-scalable=$scalable');
            })();
        """.trimIndent()
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
    private fun forceScript(w: Int, lang: String?, scalable: String): String = """
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
                ', maximum-scale=5.0, minimum-scale=0.1, user-scalable=$scalable');
        })();
    """.trimIndent()

    /**
     * 自动模式：等 `<head>` 解析完（DOMContentLoaded）再判断页面类型。
     *
     * 之所以挂在 DOMContentLoaded 而不是 onPageStarted：onPageStarted 时 `<head>` 还没解析，
     * 读不到 viewport meta。挂在 DOMContentLoaded 比 onPageFinished 早得多，能明显减少重排闪烁。
     */
    private fun autoScript(lang: String?, scalable: String): String = """
        (function() {
            ${languagePrologue(lang)}

            // 判重用的是「本次的 user-scalable 值」，而不是一个布尔钩子。
            // 布尔钩子会把「切换缩放开关后重新应用」也一起挡掉 ——
            // 参数没变才跳过，变了就得重跑一次 decide()。
            if (window.__blauserScalable === "$scalable") return;
            window.__blauserScalable = "$scalable";

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
                    ', maximum-scale=5.0, minimum-scale=0.1, user-scalable=$scalable');
            }

            function decide() {
                var m = document.querySelector('meta[name="viewport"]');
                var content = (m && m.getAttribute('content')) || '';
                // 页面自己声明了 device-width → 移动端适配页，尊重它，不干预
                if (/width\s*=\s*device-width/i.test(content)) return;
                applyPcWidth(PC_WIDTH);
            }

            if (document.readyState === 'loading') {
                // DOMContentLoaded 监听只挂一次；但每次注入都要把 decide 跑一遍
                // 已经过了在这个时机的场景（切开关时页面早就加载完了）
                if (!window.__blauserAutoHooked) {
                    window.__blauserAutoHooked = true;
                    document.addEventListener('DOMContentLoaded', decide);
                }
            } else {
                decide();
            }
        })();
    """.trimIndent()
}
