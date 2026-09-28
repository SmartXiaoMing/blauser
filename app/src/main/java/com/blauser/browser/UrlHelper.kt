package com.blauser.browser

import android.net.Uri
import java.net.URLEncoder

/**
 * 地址栏输入的解析。
 *
 * 刻意做成不依赖 Context 的纯函数 —— 这样可以直接单测（见 app/src/test）。
 */
object UrlHelper {

    /** 默认搜索模板。实际用的是全局设置里选的那个（见 SettingsManager.SEARCH_ENGINES） */
    const val DEFAULT_SEARCH_TEMPLATE = "https://www.baidu.com/s?wd=%s"

    /**
     * 唯一放行的本地路径前缀。
     *
     * 放开整个 file scheme 的话，API 30 以下 setAllowFileAccess 默认为 true，
     * 输入 file:///data/data/... 就能读到私有目录。WebView 那边还另有关闭
     * allowFileAccess 的一道防线（见 WebViewFactory），这里是第一道。
     */
    private const val ASSET_PREFIX = "file:///android_asset/"

    /**
     * 本地 / 内网地址：这些几乎不会是 https，直接按 http 补全，
     * 免得在开发机上加了一层没配证书的 https 反复超时。
     */
    private val LOCAL_HOST = Regex(
        "^(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0" +
            "|10\\.\\d+\\.\\d+\\.\\d+" +
            "|192\\.168\\.\\d+\\.\\d+" +
            "|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)" +
            "(:\\d{1,5})?(/.*)?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 带显式端口的地址，多半也是本地服务。
     *
     * 开头的否定前瞻是为了排除「12:30」这类时间写法 —— 否则纯数字会被当成主机名，
     * 搜个时间结果去访问 http://12:30。
     */
    private val HOST_WITH_PORT = Regex("^(?![0-9]+:)[^\\s/:]+:\\d{1,5}(/.*)?$")

    fun isHttpUrl(uri: Uri?): Boolean = uri != null && isHttpScheme(uri.scheme)

    fun isHttpScheme(scheme: String?): Boolean = scheme == "http" || scheme == "https"

    fun isWebUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return u.startsWith("http://", true) || u.startsWith("https://", true)
    }

    /**
     * 用 java.net.URLEncoder 而不是 android.net.Uri.encode：前者是纯 JVM 实现，
     * 这样整个 [smartUrl] 都能在单测里跑（见 app/src/test）。
     * 两者对中文等非 ASCII 的编码结果一致，只有空格一个是 %20 一个是 +，
     * 放在查询串里都合法。
     */
    fun searchUrl(query: String, template: String = DEFAULT_SEARCH_TEMPLATE): String =
        template.format(URLEncoder.encode(query, "UTF-8"))

    /**
     * 智能 URL 处理：自动补全协议，不像网址就当搜索词。
     *
     * 除了「含点且不含空格」这种粗判，另外单独认两类：
     * 本地/内网地址和带端口的地址（`localhost:3000`、`192.168.1.5:8080`）。
     * 它们没有点或者有端口，按原来的规则会被当成搜索词 —— 对开发机来说很难用。
     */
    fun smartUrl(input: String, searchTemplate: String = DEFAULT_SEARCH_TEMPLATE): String = when {
        input.startsWith("http://", true) || input.startsWith("https://", true) -> input
        input.startsWith(ASSET_PREFIX, true) -> input
        input.startsWith("about:", true) -> input
        // 除 android_asset 外的 file: 一律拒掉，当作搜索词处理。
        // 不这么做的话 "file:///data/data/com.x/y.xml" 会走到下面「含点即网址」那条，
        // 变成 https://file:///... —— 虽然也读不到本地文件，但会给出一个莫名其妙的网址。
        input.startsWith("file:", true) -> searchUrl(input, searchTemplate)
        LOCAL_HOST.matches(input) -> "http://$input"
        HOST_WITH_PORT.matches(input) -> "http://$input"
        input.contains(".") && !input.contains(" ") -> "https://$input"
        else -> searchUrl(input, searchTemplate)
    }
}
