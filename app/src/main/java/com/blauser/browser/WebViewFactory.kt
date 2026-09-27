package com.blauser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

/**
 * 唯一构造 WebView 的地方：全套 WebSettings + 两个 client 的装配。
 *
 * 抽出来是因为「一个标签页一个 Activity」之后，MainActivity 不该再关心
 * WebView 怎么配、缩放脚本何时注入、各种系统回调怎么翻译。
 */
object WebViewFactory {

    /**
     * 按网址解析当前生效的设置。实现方会把「站点覆盖 + 全局默认」合起来。
     *
     * 注意 UserAgent 不在这里应用：它必须在发起请求**之前**设好，
     * 所以由 Activity 在 loadUrl 前处理；这里只用得到宽度和语言（页面开始加载时注入）。
     */
    interface SettingsResolver {
        fun pageWidthFor(url: String): Int
        fun languageFor(url: String): String?
    }

    interface Callbacks {
        fun onPageStarted(url: String)

        /** 页面排版完成，此时注入缩放脚本能拿到真实内容宽度 */
        fun onPageFinished(url: String)

        fun onProgress(progress: Int)

        /** 导航导致的地址变化（含站内跳转） */
        fun onUrlChanged(url: String)

        fun onTitle(title: String)

        /** 主文档加载失败（域名解析不了、连不上、超时等） */
        fun onPageError(error: PageError)

        /** SSL 证书校验失败。注意它**不会**走 onPageError */
        fun onSslError(error: SslError)

        /** window.open() / target="_blank" 探到的目标地址，交给 Activity 开新标签页 */
        fun onNewWindowRequested(url: String)

        /** 渲染进程被回收。返回 true 表示已处理（必须自行销毁旧实例并换新） */
        fun onRenderProcessGone(view: WebView): Boolean

        /**
         * 非 http/https 的 scheme（intent://、weixin:// 等），交回 Activity 拉起外部应用。
         *
         * [hasGesture] 表示这次跳转是不是用户点击触发的 —— **不能忽略它**：
         * 没有手势的自动跳转是网页静默拉起任意 App 的常用手段。
         *
         * 刻意不传 WebView 进来：这个回调可能源自 `window.open()` 的探针，
         * 而探针在回调前就被销毁了，实现方若往它上面 loadUrl 会静默丢页面。
         * 要加载 fallback 地址就用 Activity 自己的 WebView。
         */
        fun onExternalScheme(uri: Uri, hasGesture: Boolean): Boolean

        /** `<input type="file">`。返回 true 表示已接管，之后必须回调 callback */
        fun onShowFileChooser(
            callback: android.webkit.ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean

        /** 网页请求摄像头 / 麦克风等。实现方必须在某处 grant() 或 deny()，否则会一直挂着 */
        fun onPermissionRequest(request: PermissionRequest)

        /** 网页请求定位。同样必须回调 callback */
        fun onGeolocationPermission(
            origin: String,
            callback: GeolocationPermissions.Callback
        )

        /** 网页触发了下载 */
        fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?)
    }

    /** 把 WebView 的错误码翻译成用户能看懂的原因 */
    fun describeError(context: Context, errorCode: Int): String = context.getString(
        when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> R.string.err_host_lookup
            WebViewClient.ERROR_CONNECT -> R.string.err_connect
            WebViewClient.ERROR_TIMEOUT -> R.string.err_timeout
            WebViewClient.ERROR_BAD_URL -> R.string.err_bad_url
            WebViewClient.ERROR_UNSUPPORTED_SCHEME -> R.string.err_unsupported_scheme
            WebViewClient.ERROR_TOO_MANY_REQUESTS -> R.string.err_too_many_requests
            WebViewClient.ERROR_REDIRECT_LOOP -> R.string.err_redirect_loop
            WebViewClient.ERROR_FILE_NOT_FOUND -> R.string.err_file_not_found
            WebViewClient.ERROR_FILE -> R.string.err_file
            WebViewClient.ERROR_IO -> R.string.err_io
            WebViewClient.ERROR_PROXY_AUTHENTICATION -> R.string.err_proxy_auth
            WebViewClient.ERROR_AUTHENTICATION -> R.string.err_auth
            WebViewClient.ERROR_UNSAFE_RESOURCE -> R.string.err_unsafe_resource
            else -> R.string.err_unknown
        }
    )

    /** 主文档加载失败的信息，用于渲染自定义错误页 */
    data class PageError(
        val errorCode: Int,
        val description: String,
        val url: String
    )

    /** 进程级静态开关，只设一次。release 包必须关掉，否则等于把用户登录态敞开给 adb。 */
    fun configureDebugging(activity: AppCompatActivity) {
        val debuggable =
            (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        activity: AppCompatActivity,
        cb: Callbacks,
        resolver: SettingsResolver,
        incognito: Boolean = false
    ): WebView {
        val wv = WebView(activity)

        // 无痕：必须赶在加载任何内容之前把 WebView 挂到独立 profile 上。
        // 晚一步（比如放在 loadUrl 之后）cookie 就已经写进默认 profile 了。
        if (incognito) Incognito.attach(wv)

        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true          // 启用宿主视口
            loadWithOverviewMode = true     // 缩放适屏
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false     // 隐藏缩放控制按钮
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = SettingsManager.resolveUserAgent(activity)

            // HTTPS 页面里的 http 子资源：兼容模式会放行图片等被动资源，
            // 但拦下脚本 —— 比 ALWAYS_ALLOW 安全得多，又不像 BLOCK 那样把大量
            // 老站点直接搞坏（一个 http 图片就能让整页资源加载失败）。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // 纵深防御：地址栏已经拦了 file://，但 WebView 这层也别开着。
            // API 30 以下 setAllowFileAccess 默认是 true，恶意页面若能把顶层
            // 导航到 file:///data/data/<包名>/... 就能读到私有目录。
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            // 定位默认是关的，必须显式打开，否则 onGeolocationPermissionsShowPrompt 不会被调用。
            // API 30 起这个方法废弃（改为始终可用），保留是为了兼容低版本。
            @Suppress("DEPRECATION")
            setGeolocationEnabled(true)

            // window.open() 是否允许「无用户手势」自动弹窗。默认 false，正是想要的：
            // 广告页最爱用自动弹窗。onCreateWindow 里还会再查一次 isUserGesture 兜底。
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
        }

        // WebView 默认**拒绝**三方 cookie（API 21+）。很多站点的 SSO / 内嵌登录
        // 依赖它，不开的话表现为「登录页刷新一下就退出了」。
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // 没有 DownloadListener 的话，点击下载链接会停在空白页
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            cb.onDownload(url, userAgent, contentDisposition, mimeType)
        }

        wv.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // 尽早注入，争取赶在页面 <head> 内脚本之前
                PageScaler.inject(view, resolver.pageWidthFor(url), resolver.languageFor(url))
                cb.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 再注入一次：此时页面已排版，能拿到真实内容宽度做修正
                PageScaler.inject(view, resolver.pageWidthFor(url), resolver.languageFor(url))
                cb.onPageFinished(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                cb.onUrlChanged(url)
            }

            // 兼容 API < 24
            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                // 这个重载拿不到手势信息，只能当作有手势放行
                handleUrl(Uri.parse(url), hasGesture = true)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = handleUrl(request.url, request.hasGesture())

            private fun handleUrl(uri: Uri, hasGesture: Boolean): Boolean {
                val scheme = uri.scheme ?: return false
                // http/https 就地导航
                if (UrlHelper.isHttpScheme(scheme)) return false
                return cb.onExternalScheme(uri, hasGesture)
            }

            override fun onRenderProcessGone(
                view: WebView, detail: RenderProcessGoneDetail
            ): Boolean = cb.onRenderProcessGone(view)

            /**
             * API 23+ 的主文档错误回调。
             *
             * @RequiresApi 是给 lint 看的：这个重载只会在 API 23+ 被系统调用，
             * 但 WebResourceError 的 getErrorCode/getDescription 本身是 API 23 的，
             * 没有它就报 NewApi 错误（minSdk 是 21）。
             */
            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // 只处理主文档：图片 / CSS / 埋点之类的子资源失败不该把整页换掉
                if (!request.isForMainFrame) return
                cb.onPageError(
                    PageError(
                        errorCode = error.errorCode,
                        description = error.description?.toString().orEmpty(),
                        url = request.url.toString()
                    )
                )
            }

            /**
             * API < 23 的老签名。它拿不到「是不是主文档」的信息，
             * 用失败地址与当前地址比对来兜底过滤子资源。
             */
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView, errorCode: Int, description: String, failingUrl: String
            ) {
                if (failingUrl != view.url) return
                cb.onPageError(PageError(errorCode, description, failingUrl))
            }

            /**
             * 证书校验失败走的不是 onReceivedError。默认实现直接取消加载，
             * 页面会停在空白 —— 所以必须接管，交给自定义错误页说明原因。
             *
             * 刻意**不提供「继续访问」**：接受无效证书等于放弃中间人攻击防护。
             */
            override fun onReceivedSslError(
                view: WebView, handler: SslErrorHandler, error: SslError
            ) {
                handler.cancel()
                cb.onSslError(error)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) =
                cb.onProgress(newProgress)

            override fun onReceivedTitle(view: WebView, title: String) = cb.onTitle(title)

            /** `<input type="file">`：不接管的话点上传按钮毫无反应 */
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean = cb.onShowFileChooser(filePathCallback, fileChooserParams)

            /** 摄像头 / 麦克风。默认实现是直接拒绝，得自己接管才能问用户 */
            override fun onPermissionRequest(request: PermissionRequest) {
                cb.onPermissionRequest(request)
            }

            /** 定位。默认实现是静默拒绝 */
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                cb.onGeolocationPermission(origin, callback)
            }

            /**
             * window.open() / target="_blank" → 开一个新标签页。
             *
             * 麻烦之处：标签页现在是**独立的 task**，没法把一个 WebView 从当前 Activity
             * 交给另一个 task，所以系统那套 WebViewTransport 在这里用不了。
             *
             * 变通办法：给 transport 塞一个一次性的探针 WebView，只为截获目标地址，
             * 拿到就立刻销毁，再用这个地址新开一个标签。
             */
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // 只放行用户点出来的弹窗。WebSettings 那边已经关了自动弹窗，
                // 这里再挡一次：两条路径都封死，广告页才没法刷出一堆标签。
                if (!isUserGesture) return false

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val probe = WebView(view.context).apply {
                    // 只用来截获地址，不需要执行页面脚本 —— 弹窗里跑的往往是广告，
                    // 关掉 JS 既省资源，也少给它一个在后台偷偷干活的机会
                    settings.javaScriptEnabled = false
                }

                // 用主线程 Handler 而不是 probe.postDelayed：未 attach 到窗口的 View，
                // postDelayed 会进 HandlerActionQueue 一直等到 attach 才执行，
                // 而探针永远不会被 attach —— 那样这个超时兜底就形同虚设。
                val handler = Handler(Looper.getMainLooper())
                var handled = false

                /** 收尾：停掉超时、销毁探针。幂等，保证只执行一次 */
                fun dispose() {
                    if (handled) return
                    handled = true
                    handler.removeCallbacksAndMessages(null)
                    runCatching { probe.destroy() }
                }

                fun finish(url: String?) {
                    if (handled) return
                    dispose()
                    if (!url.isNullOrBlank()) cb.onNewWindowRequested(url)
                }

                val timeout = Runnable { finish(null) }

                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView, request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url
                        if (UrlHelper.isHttpScheme(uri.scheme)) {
                            finish(uri.toString())
                        } else if (!handled) {
                            // 弹窗里点的是 deeplink（weixin:// / intent:// 之类），
                            // 要交给外部 App，不能当成网址开新标签。
                            // 能走到这里说明是用户点出来的，hasGesture 记 true。
                            //
                            // 顺序很重要：先销毁探针再回调。回调里若要加载
                            // browser_fallback_url，用的是主 WebView，不是这个探针。
                            dispose()
                            cb.onExternalScheme(uri, hasGesture = true)
                        }
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        // 地址栏型弹窗不会走 shouldOverrideUrlLoading，用这里兜底
                        if (url != "about:blank") finish(url)
                    }
                }

                // 有些弹窗是先开空白窗口再 document.write，地址迟迟不出现；给个上限
                handler.postDelayed(timeout, WINDOW_PROBE_TIMEOUT_MS)

                transport.webView = probe
                resultMsg.sendToTarget()
                return true
            }
        }

        return wv
    }

    /** 探针 WebView 等地址的上限，超时就放弃（弹窗多半是空窗口） */
    private const val WINDOW_PROBE_TIMEOUT_MS = 1200L
}
