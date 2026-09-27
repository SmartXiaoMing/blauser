package com.blauser.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 无痕标签。
 *
 * ## 为什么非得用 profile
 *
 * WebView 的 cookie / localStorage / cache 是**整个进程共享**的，没有「给这一个
 * WebView 单独一套存储」的开关 —— `WebSettings.setIncognito()` 早在 API 18 就废弃
 * 且不再生效。所以真正的隔离只能靠 androidx.webkit 的 [ProfileStore]：
 * 每个 profile 有独立的 cookie jar 和存储目录，WebView 之间互不可见。
 *
 * ## 什么时候不支持
 *
 * ProfileStore 需要 **Android 9（API 28）及以上**，而且还得 WebView 实现本身
 * 支持多 profile —— 只查 API 等级不够：系统版本够了但 WebView 是老版本的机型
 * 一样会抛异常。所以判断统一走 [WebViewFeature.isFeatureSupported]，
 * 它把两个条件都包含了。
 *
 * 不支持时硬做只能做成「不记历史，但 cookie 照旧共享」—— 那不是无痕，
 * 只是让用户误以为自己在无痕浏览。所以干脆不提供这个入口（见 [isSupported]）。
 *
 * ## 关闭时机
 *
 * [wipe] 必须在**最后一个**无痕标签销毁后调用：profile 还被 WebView 使用时
 * deleteProfile 会抛 IllegalStateException。
 */
object Incognito {

    private const val PROFILE_NAME = "incognito"

    /**
     * 这台设备能不能做真正的无痕。
     *
     * 包一层 runCatching：WebView 没装 / 被禁用时 isFeatureSupported 会抛
     * RuntimeException，那种情况下当然是「不支持」。
     */
    val isSupported: Boolean
        get() = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)

    /**
     * 把这个 WebView 挂到无痕 profile 上。
     *
     * 必须在加载任何内容**之前**调用（[WebViewFactory.create] 里紧跟构造之后）。
     * 返回 false 表示这个系统版本做不到真隔离。
     */
    // 下面两处 @SuppressLint("RequiresFeature") 是误报：守卫在 [isSupported] 里，
    // 但 lint 不做跨函数的分析，看不到那个属性。判断逻辑集中在一处比在每个调用点
    // 重复写一遍 isFeatureSupported 更不容易漏。
    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView): Boolean {
        if (!isSupported) return false
        return runCatching {
            // 注意这里传的是 profile **名字**而不是 Profile 对象
            // （WebViewCompat.setProfile(WebView, String)）；
            // 名字对应的 profile 不存在时会自动创建。
            ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME)
            WebViewCompat.setProfile(webView, PROFILE_NAME)
            true
        }.getOrDefault(false)
    }

    /** 抹掉无痕 profile 的全部数据（cookie / 存储 / 缓存） */
    @SuppressLint("RequiresFeature")
    fun wipe() {
        if (!isSupported) return
        runCatching { ProfileStore.getInstance().deleteProfile(PROFILE_NAME) }
    }
}
