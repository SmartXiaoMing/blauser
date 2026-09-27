package com.blauser.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.blauser.browser.databinding.ActivityMainBinding

/**
 * 一个 Activity 实例 = 一个标签页 = 一个 Android task。
 *
 * ## 为什么这样分
 *
 * 每个标签页跑在**独立的 task** 里，于是会在系统「最近任务」中各占一张卡片，
 * 用系统的方式就能切换，不必回 App 内找列表。这些 task 仍在**同一个进程**，
 * 所以 cookie / localStorage 照常共享 —— 这和多进程是两回事。
 *
 * ## 代价（本设计明确接受的取舍）
 *
 * 后台 task 的 Activity 处于 stopped 状态，内存紧张时系统可以销毁它，再切回来
 * 就是重建 + 重新加载页面。相比之下「所有标签的 WebView 都塞在一个前台 Activity 里」
 * 能 100% 保证不重载，但那样就没法在最近任务里分卡片了。二者不可兼得。
 *
 * ## 这个类负责什么
 *
 * 只管「本标签页」的生命周期与状态：WebView 的创建销毁、地址与标题、两个覆盖层
 * （新标签页 / 错误页）、悬浮球接线、各种系统回调的分发。
 * 具体动作都拆出去了：[PageActions]（收藏/分享/快捷方式）、[SettingsDialogs]（设置面板）、
 * [DownloadHandler]（下载）、[UrlHelper]（地址解析）、[WebViewFactory]（WebView 装配）。
 */
class MainActivity : AppCompatActivity(),
    FloatingBallView.MenuListener,
    FloatingBallView.StateProvider,
    WebViewFactory.Callbacks,
    WebViewFactory.SettingsResolver {

    private lateinit var binding: ActivityMainBinding

    /** 本标签的唯一标识，取自启动 Intent 的 data；进程重建后仍是同一个值 */
    private lateinit var tabKey: String

    /** 空白标签在首次导航前不创建 WebView，省内存 */
    private var webView: WebView? = null

    private var ball: FloatingBallView? = null

    private var currentUrl: String = ""
    private var currentTitle: String = ""

    /** 最近一次加载失败的地址，供错误页的「重试」用 */
    private var lastErrorUrl: String = ""

    /**
     * 本标签是不是无痕标签。跟启动 Intent 走，所以配置变化重建后依然正确。
     *
     * 无痕的含义：用独立的 WebView profile（cookie / 存储与普通标签隔离），
     * 并且不写浏览历史。
     */
    private var isIncognito = false

    /** 待恢复的滚动位置；< 0 表示没有。恢复一次后清掉 */
    private var pendingScrollY = -1

    private val isBlankTab: Boolean get() = webView == null || currentUrl.isBlank()

    // ==================== 需要用户授权的几件事 ====================
    //
    // 这些 launcher 必须在 Activity 进入 STARTED 之前注册，所以放成属性初始化器。

    /** 网页里 `<input type="file">` 弹出的选择器 */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingFileCallback?.onReceiveValue(fileChooserResult(result.resultCode, result.data))
        pendingFileCallback = null
    }

    /** 网页要摄像头 / 麦克风 */
    private var pendingPermissionRequest: PermissionRequest? = null
    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request == null) return@registerForActivityResult
        // 全部拿到才授权：只给一半会导致 getUserMedia 直接失败，
        // 不如明确拒绝，页面也好走降级分支
        if (grants.values.all { it }) {
            request.grant(request.resources)
        } else {
            request.deny()
            toast(getString(R.string.permission_denied))
        }
    }

    /** 网页要定位 */
    private var pendingGeolocation: Pair<String, GeolocationPermissions.Callback>? = null
    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val pending = pendingGeolocation
        pendingGeolocation = null
        // 定位用 any 而不是 all：Android 12 起用户可以只给「近似位置」，
        // 那时只有 COARSE 被授予，要求 FINE 也拿到就会误判成拒绝，定位永远不可用
        val granted = grants.values.any { it }
        pending?.second?.invoke(pending.first, granted, false)
        if (pending != null && !granted) toast(getString(R.string.permission_denied))
    }

    /** API 28 及以下下载到公共目录需要存储权限 */
    private var pendingDownload: Download? = null
    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingDownload
        pendingDownload = null
        if (download == null) return@registerForActivityResult
        if (granted) {
            DownloadHandler.start(
                this, download.url, download.userAgent, download.contentDisposition, download.mimeType
            )
        } else {
            toast(getString(R.string.download_permission_needed))
        }
    }

    private data class Download(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 把旧版本内联实现里的收藏搬到统一存储，避免用户已收藏的条目消失
        BookmarkManager.migrateIfNeeded(this)
        WebViewFactory.configureDebugging(this)

        isIncognito = intent?.getBooleanExtra(EXTRA_INCOGNITO, false) == true

        // 外部 App 用 ACTION_VIEW 调起时，Intent 的 data 是**网址本身**，不能拿来当标签标识：
        // 同一个网址被打开两次会撞成同一个 key，两个标签的状态互相覆盖、关一个还关俩。
        // 所以这里不就地加载，而是开一个带 uuid 的正经标签页，自己立刻退场。
        val handoffUrl = if (savedInstanceState == null) externalUrlFrom(intent) else null
        if (handoffUrl != null && openNewTab(handoffUrl)) {
            // 本实例不承载任何页面。给 tabKey 一个没人引用的值，
            // 免得 onDestroy 清理时误删了别的标签的状态。
            tabKey = TabRegistry.newTabKey()
            finish()
            return
        }

        tabKey = TabRegistry.keyFrom(intent?.data)

        enterFullscreen()
        setupBackHandling()
        setupFloatingBall()
        setupNewTabPage()
        setupErrorPage()

        restoreOrLoad(savedInstanceState)
        persistState()
    }

    /**
     * 三种恢复来源，按优先级：
     *  1. 进程/配置变化重建：WebView 历史在 bundle 里，优先恢复它（保住前进后退）
     *  2. 本 App 开的新标签、桌面快捷方式：网址在 extra，data 是标签标识
     *  3. 外部 App 调起的链接：data 本身就是网址
     *
     * 第 3 种正常走不到（onCreate 已经转交出去了），只有标签数达上限、转交失败时才落到这里。
     * 兜底也不能把链接吞掉 —— 否则用户点开链接只得到一张空白首页。
     */
    private fun restoreOrLoad(savedInstanceState: Bundle?) {
        val savedUrl = savedInstanceState?.getString(KEY_URL).orEmpty()
        val savedWebState = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)
        pendingScrollY = savedInstanceState?.getInt(KEY_SCROLL_Y, -1) ?: -1

        if (savedWebState != null && savedUrl.isNotBlank()) {
            val wv = ensureWebView()
            // UA 和方向必须在发起请求之前设好，restoreState 会立刻重新请求当前页
            applyRequestSettings(savedUrl, wv)
            currentUrl = savedUrl
            // 返回 null 表示 bundle 里没有可用历史，那就退回普通加载
            if (wv.restoreState(savedWebState) == null) wv.loadUrl(savedUrl)
            updateNewTabPage()
            return
        }

        val target = savedUrl.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra(EXTRA_URL)
            ?: externalUrlFrom(intent)
        if (!target.isNullOrBlank()) loadUrl(target) else updateNewTabPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 只存 URL：标题由 onReceivedTitle 在页面加载后自然恢复
        outState.putString(KEY_URL, currentUrl)

        val wv = webView
        if (wv != null && currentUrl.isNotBlank()) {
            // 保存前进/后退历史。切深色模式会重建 Activity，没有它历史会清零 ——
            // 页面本身仍然会重新请求（WebView 恢复不了 DOM），至少历史留得住。
            val history = Bundle()
            wv.saveState(history)
            outState.putBundle(KEY_WEBVIEW_STATE, history)

            // 滚动位置。WebView 的 saveState 不含这个，页面重载后位置会归零。
            // getScrollY 是同步的，正好能在 onSaveInstanceState 里取到；
            // 换用 JS 读 window.scrollY 就不行了 —— 那是异步的，赶不上这次保存。
            outState.putInt(KEY_SCROLL_Y, wv.scrollY)
        }
    }

    /**
     * 全屏沉浸。
     *
     * 只 hide 状态栏不够：窗口默认不延伸到系统栏区域，状态栏一藏就留一条黑边。
     * 必须同时 setDecorFitsSystemWindows(false) 让内容铺满，再用 inset 给底部系统栏
     * 让出空间。有挖孔的机型还要开 shortEdges，否则窗口会被 letterbox。
     */
    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 顶部刻意留 0：状态栏已隐藏，内容要一直铺到屏幕顶端
            v.setPadding(bars.left, 0, bars.right, bars.bottom)

            // 键盘高度垫给新标签页的内容。
            // edge-to-edge 下 windowSoftInputMode=adjustResize 已经不起作用了
            // （窗口不再为 IME 缩小），不自己垫的话，列表最后几项永远滚不到键盘上方。
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            binding.newTabPage.newTabScroll.setPadding(0, 0, 0, ime)

            insets
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                // 能后退就后退；否则关闭当前标签（结束本 task）
                if (wv != null && wv.canGoBack()) wv.goBack() else finish()
            }
        })
    }

    /**
     * 刻意**不调 setIntent()**：那会把这个 Activity 的 data 换成外部网址，
     * 之后一旦被系统重建，tabKey 就跟着变了，标签在列表里会对不上号。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 本 Activity 已经开着一个页面，又收到外部链接 → 另开一个标签，别覆盖当前页面
        externalUrlFrom(intent)?.let { openNewTab(it) }
    }

    override fun onDestroy() {
        ball?.dismissMenu()
        webView?.let {
            binding.webContainer.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
        // 只有用户主动关闭才清登记；被系统回收时保留，恢复后还能拿到地址
        if (isFinishing) {
            TabRegistry.remove(this, tabKey)
            // 最后一个无痕标签关闭时，把整个 profile 抹掉（cookie / 存储 / 缓存）。
            // 必须在 WebView 销毁之后做 —— profile 还被占用时 deleteProfile 会抛异常。
            if (isIncognito && TabRegistry.countIncognitoExcept(this, tabKey) == 0) {
                Incognito.wipe()
            }
        }
        super.onDestroy()
    }

    // ==================== 页面 ====================

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebViewFactory.create(this, this, this, incognito = isIncognito)
        binding.webContainer.addView(wv)
        webView = wv
        return wv
    }

    private fun resolveFor(url: String): SiteSettingsManager.Resolved =
        SiteSettingsManager.resolve(this, SiteSettingsManager.hostOf(url))

    /** UA 与屏幕方向必须在发起请求**之前**设好 —— 请求已经发出去再改就晚了 */
    private fun applyRequestSettings(url: String, wv: WebView) {
        val resolved = resolveFor(url)
        wv.settings.userAgentString = resolved.userAgent
        applyOrientation(resolved.orientation)
    }

    /** 加载网址 */
    private fun loadUrl(url: String) {
        val wv = ensureWebView()
        val resolved = resolveFor(url)

        wv.settings.userAgentString = resolved.userAgent
        applyOrientation(resolved.orientation)

        currentUrl = url
        loadWithHeaders(wv, url, resolved.language)

        updateNewTabPage()
        persistState()
    }

    /**
     * 带 Accept-Language 的加载。
     *
     * 附加请求头只在 loadUrl 这一次生效 —— 必须走 loadUrl 而不是 reload()，
     * 后者不会重发这些头。见 [reloadCurrentPage]。
     */
    private fun loadWithHeaders(wv: WebView, url: String, language: String?) {
        val headers = language?.let { mapOf("Accept-Language" to it) }.orEmpty()
        if (headers.isEmpty()) wv.loadUrl(url) else wv.loadUrl(url, headers)
    }

    /**
     * 用**当前生效的设置**重新加载当前页。
     *
     * 不能用 `WebView.reload()`：附加请求头（Accept-Language）只在 loadUrl 那一次带上，
     * reload 不会重发。于是「改了语言 → 立即重载」看起来像没生效 —— 这正是
     * 重载确认弹窗向用户承诺过的事，不能做不到。
     */
    private fun reloadCurrentPage() {
        val url = currentUrl
        val wv = webView
        if (url.isBlank() || wv == null) return
        loadWithHeaders(wv, url, resolveFor(url).language)
    }

    /** 只在真的需要变时才设，避免每次导航都触发一次方向重排 */
    private fun applyOrientation(orientation: Int) {
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }

    // ==================== WebViewFactory.SettingsResolver ====================

    override fun pageWidthFor(url: String): Int = resolveFor(url).pageWidth

    override fun languageFor(url: String): String? = resolveFor(url).language

    private fun persistState() {
        TabRegistry.saveState(this, tabKey, currentUrl, currentTitle, isIncognito)
    }

    // ==================== WebViewFactory.Callbacks ====================

    override fun onPageStarted(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        currentUrl = url
        // 重新开始加载 → 收起错误页
        hideErrorPage()
        // 从空白页导航走了 → 收起新标签页覆盖层
        updateNewTabPage()
        persistState()
    }

    override fun onPageError(error: WebViewFactory.PageError) {
        showErrorPage(
            title = getString(R.string.error_title_load),
            reason = WebViewFactory.describeError(this, error.errorCode),
            url = error.url,
            detail = if (error.description.isNotBlank()) {
                getString(R.string.error_detail_with_desc, error.errorCode, error.description)
            } else {
                getString(R.string.error_detail, error.errorCode)
            }
        )
    }

    override fun onSslError(error: android.net.http.SslError) {
        showErrorPage(
            title = getString(R.string.error_title_ssl),
            reason = getString(R.string.error_ssl_reason),
            url = error.url.orEmpty(),
            detail = getString(R.string.error_ssl_detail, error.primaryError)
        )
    }

    private fun setupErrorPage() {
        binding.errorPage.btnErrorRetry.setOnClickListener {
            val url = lastErrorUrl.ifBlank { currentUrl }
            if (url.isBlank()) return@setOnClickListener
            hideErrorPage()
            loadUrl(url)
        }
    }

    private fun showErrorPage(title: String, reason: String, url: String, detail: String) {
        lastErrorUrl = url
        with(binding.errorPage) {
            tvErrorTitle.text = title
            tvErrorReason.text = reason
            tvErrorUrl.text = url
            tvErrorUrl.visibility = if (url.isBlank()) View.GONE else View.VISIBLE
            tvErrorDetail.text = detail
            root.visibility = View.VISIBLE
            // 空白页/新标签页覆盖层让位给错误页
            binding.newTabPage.root.visibility = View.GONE
        }
        binding.progressBar.visibility = View.GONE
    }

    private fun hideErrorPage() {
        if (binding.errorPage.root.visibility != View.GONE) {
            binding.errorPage.root.visibility = View.GONE
        }
    }

    override fun onPageFinished(url: String) {
        binding.progressBar.visibility = View.GONE
        currentUrl = url
        // 无痕标签不留任何痕迹 —— 这正是它存在的意义。
        // 非 http/https 的地址（about:blank 之类）也不值得记。
        if (!isIncognito && UrlHelper.isWebUrl(url)) {
            HistoryManager.record(this, currentTitle, url)
        }
        persistState()
        restoreScrollIfPending()
    }

    /**
     * 恢复上次离开时的滚动位置。
     *
     * 只在「被系统回收后重建」和「切深色模式重建」这两条路径上生效
     * （见 onSaveInstanceState）。页面 DOM 本身恢复不了，但位置能回来，
     * 用户至少不用重新翻一遍长页面。
     */
    private fun restoreScrollIfPending() {
        val y = pendingScrollY
        if (y <= 0) return
        pendingScrollY = -1
        // 不能立刻 scrollTo：onPageFinished 之后页面往往还在继续排版
        // （图片撑开高度、懒加载补内容），滚过去会被随后的布局吃掉。
        // 延后一点是经验值 —— 更稳的做法是等高度稳定，但那要轮询，不划算。
        webView?.postDelayed({ webView?.scrollTo(0, y) }, SCROLL_RESTORE_DELAY_MS)
    }

    override fun onProgress(progress: Int) {
        binding.progressBar.progress = progress
        if (progress >= 100) binding.progressBar.visibility = View.GONE
    }

    override fun onUrlChanged(url: String) {
        currentUrl = url
        persistState()
    }

    override fun onTitle(title: String) {
        currentTitle = title
        persistState()
    }

    override fun onNewWindowRequested(url: String) {
        openNewTab(url)
    }

    /**
     * 渲染进程被系统回收。默认实现返回 false 会导致 **App 被直接杀掉**，必须接管。
     * 旧实例不可复用，销毁后换一个新的顶上；这一次页面状态无法保留。
     */
    override fun onRenderProcessGone(view: WebView): Boolean {
        val url = currentUrl
        binding.webContainer.removeView(view)
        view.destroy()
        webView = null
        Toast.makeText(this, R.string.toast_render_gone, Toast.LENGTH_LONG).show()
        if (url.isNotBlank()) loadUrl(url) else updateNewTabPage()
        return true
    }

    /**
     * 非 http/https 的 scheme：拉起外部 App。
     *
     * 两道关卡：
     *  - **必须有用户手势**。没有手势的自动跳转是网页静默拉起任意 App 的惯用手段
     *    （Chrome 也是这个策略），直接拦掉并提示。
     *  - 隐式 Intent 的 categories 必须被目标 filter 全部包含，所以先不带
     *    BROWSABLE 试一次，失败了再带上重试 —— 无条件带会把那些没声明
     *    BROWSABLE 的 activity 全部排除掉。
     */
    override fun onExternalScheme(uri: Uri, hasGesture: Boolean): Boolean {
        val scheme = uri.scheme ?: return false
        if (UrlHelper.isHttpScheme(scheme)) return false

        if (!hasGesture) {
            toast(getString(R.string.toast_external_needs_gesture, "$scheme://"))
            return true
        }

        if (scheme == "intent") return launchIntentScheme(uri)

        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (launchExternal(intent)) return true
        toast(getString(R.string.toast_no_app_for_scheme, scheme))
        return true
    }

    /**
     * 处理 `intent://` 形式的跳转。
     *
     * 三处加固，都是浏览器处理 intent URI 的标准动作：
     *  - **清掉 component / selector**。`intent://x#Intent;component=com.别的应用/.内部组件;end`
     *    这种写法能把任意组件塞进 Intent；不清掉的话，网页就能借本 App 的身份去拉起
     *    别的应用里**没有导出**的组件（Google 称之为 Intent Redirection）。
     *  - **fallback 地址必须是 http/https**。它完全由网页控制，直接丢给 loadUrl 的话，
     *    `browser_fallback_url=javascript:...` 会在当前页面上下文里执行脚本
     *    （WebView.loadUrl 对 javascript: 是当代码跑的），`file://` 则能读本地文件。
     *  - 加载 fallback 用**本标签自己的 WebView**：这个回调也可能来自 window.open()
     *    的探针，而探针在回调前就已经销毁了。
     */
    private fun launchIntentScheme(uri: Uri): Boolean {
        val intent = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return false
        }
        intent.component = null
        intent.selector = null

        val fallbackUrl = intent.getStringExtra("browser_fallback_url")

        if (launchExternal(intent)) return true

        if (!fallbackUrl.isNullOrEmpty() && UrlHelper.isWebUrl(fallbackUrl)) {
            webView?.loadUrl(fallbackUrl)
        } else {
            toast(getString(R.string.toast_app_not_installed, intent.`package` ?: "intent"))
        }
        return true
    }

    /**
     * 拉起外部应用，失败时带 BROWSABLE 重试一次。
     *
     * 加 BROWSABLE 是有代价的：隐式 Intent 的 categories 必须被目标 filter **全部**包含，
     * 附加它会排除掉那些没声明 BROWSABLE 的 activity。所以放在重试位，而不是一上来就带。
     *
     * 捕获 Exception 而不是只捕 ActivityNotFoundException：intent:// 可以指定
     * component / package，若目标是未导出的组件、或声明了 android:permission，
     * startActivity 抛的是 **SecurityException** —— 只捕 ActivityNotFound 的话，
     * 一个恶意网页就能让 App 崩掉。
     */
    private fun launchExternal(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            if (intent.categories?.contains(Intent.CATEGORY_BROWSABLE) == true) return false
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                startActivity(intent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    // ==================== 文件上传 / 权限 / 下载 ====================

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        // 上一次的还没收尾就再来一次：先把旧的放掉，否则 WebView 那边会一直挂着
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            fileChooserLauncher.launch(params.createIntent())
            true
        } catch (e: Exception) {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    private fun fileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        // 多选走 clipData，单选走 data
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val needed = request.resources
            .flatMap { androidPermissionsFor(it) }
            .distinct()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            request.grant(request.resources)
            return
        }

        // 上一个请求还挂着就先拒掉，避免页面永远等不到回调
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        webPermissionLauncher.launch(needed.toTypedArray())
    }

    /** WebView 的权限资源 → 安卓运行时权限。空列表表示不需要申请 */
    private fun androidPermissionsFor(resource: String): List<String> = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        // RESOURCE_PROTECTED_MEDIA_ID / MIDI 不需要运行时权限
        else -> emptyList()
    }

    override fun onGeolocationPermission(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        val locationPerms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        // any 而非 all，理由同上面 launcher 里的注释
        if (locationPerms.any {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            callback.invoke(origin, true, false)
            return
        }
        pendingGeolocation?.second?.invoke(pendingGeolocation!!.first, false, false)
        pendingGeolocation = origin to callback
        locationLauncher.launch(locationPerms)
    }

    override fun onDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val download = Download(url, userAgent, contentDisposition, mimeType)
        // API 29 起 DownloadManager 写公共下载目录不需要权限，只有更老的版本要
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = download
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        DownloadHandler.start(this, url, userAgent, contentDisposition, mimeType)
    }

    // ==================== 标签页 ====================

    /**
     * 开一个新标签页 = 启动一个属于新 task 的 MainActivity 实例。
     * 返回 false 表示没开成（到上限了）。
     */
    private fun openNewTab(url: String?, incognito: Boolean = false): Boolean {
        if (incognito && !Incognito.isSupported) {
            toast(getString(R.string.toast_incognito_unsupported))
            return false
        }
        if (TabRegistry.count(this) >= MAX_TABS) {
            toast(resources.getQuantityString(R.plurals.toast_max_tabs, MAX_TABS, MAX_TABS))
            return false
        }
        val i = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse(TabRegistry.newTabKey())
            // NEW_DOCUMENT 让它成为最近任务里的独立卡片，MULTIPLE_TASK 保证不复用已有 task
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
            if (incognito) putExtra(EXTRA_INCOGNITO, true)
        }
        startActivity(i)
        return true
    }

    /**
     * 回到「新标签页」—— 它同时承担首页、标签切换、地址输入的职责。
     * 已经有空白标签就切过去，避免越开越多。
     */
    private fun goToNewTabPage() {
        if (isBlankTab) return
        val blank = TabRegistry.listOpenTabs(this).firstOrNull { it.isBlank() && it.key != tabKey }
        if (blank != null) TabRegistry.switchTo(this, blank.key) else openNewTab(null)
    }

    /**
     * 打开网址时优先复用已打开的标签页：同一个网址已经在别的标签里开着就切过去，
     * 不重复开一个。这也是点收藏条目时的行为。
     */
    private fun openUrlReusingTab(url: String) {
        val existing = TabRegistry.listOpenTabs(this)
            .firstOrNull { it.url == url && it.key != tabKey }
        if (existing != null) {
            TabRegistry.switchTo(this, existing.key)
            return
        }
        if (isBlankTab) loadUrl(url) else openNewTab(url)
    }

    // ==================== 悬浮球 ====================

    private fun setupFloatingBall() {
        if (ball != null) return
        // 顶部栏和底部栏都已移除，悬浮球是界面上唯一的常驻控件，所以不提供隐藏开关
        ball = FloatingBallView(this, binding.rootLayout, this, this).also {
            // 无痕时换个颜色 —— 悬浮球是唯一能承载这个信息的地方
            it.setIncognito(isIncognito)
            it.attach()
        }
    }

    override fun onMenuAction(action: FloatingBallView.MenuAction) {
        when (action) {
            FloatingBallView.MenuAction.BACK -> webView?.takeIf { it.canGoBack() }?.goBack()
            FloatingBallView.MenuAction.FORWARD -> webView?.takeIf { it.canGoForward() }?.goForward()
            FloatingBallView.MenuAction.REFRESH -> webView?.reload()
            FloatingBallView.MenuAction.HOME -> goToNewTabPage()
            FloatingBallView.MenuAction.NEW_INCOGNITO -> openNewTab(null, incognito = true)
            FloatingBallView.MenuAction.HISTORY ->
                PageActions.showHistory(this) { openUrlReusingTab(it) }
            FloatingBallView.MenuAction.TOGGLE_BOOKMARK -> {
                PageActions.toggleBookmark(this, currentUrl, currentTitle)
                refreshNewTabPageIfVisible()
            }
            FloatingBallView.MenuAction.BOOKMARKS -> PageActions.showBookmarkList(
                this,
                onOpen = { openUrlReusingTab(it) },
                onChanged = { refreshNewTabPageIfVisible() }
            )
            FloatingBallView.MenuAction.COPY_URL -> PageActions.copyUrl(this, currentUrl)
            FloatingBallView.MenuAction.SHARE ->
                PageActions.share(this, currentUrl, currentTitle)
            FloatingBallView.MenuAction.ADD_SHORTCUT ->
                PageActions.addShortcut(this, currentUrl, currentTitle)
            FloatingBallView.MenuAction.SITE_SETTINGS -> {
                val before = currentSignature()
                SettingsDialogs.showSite(this, currentUrl) {
                    applyResolved(before, R.string.reload_message_site)
                }
            }
            FloatingBallView.MenuAction.SETTINGS -> {
                val before = currentSignature()
                SettingsDialogs.showGlobal(this) {
                    applyResolved(before, R.string.reload_message_global)
                }
            }
            FloatingBallView.MenuAction.DEVTOOLS -> PageActions.showDevTools(this, currentUrl)
        }
    }

    // ==================== 设置的应用与重载 ====================

    private fun currentSignature(): String = signatureOf(resolveFor(currentUrl))

    /** 用来判断「这次改动是否需要重载」——方向不参与，它立即生效 */
    private fun signatureOf(r: SiteSettingsManager.Resolved): String =
        "${r.pageWidth}|${r.language}|${r.userAgent}"

    /**
     * 应用当前生效的设置。
     *
     * 屏幕方向立即生效（不需要重载）；宽度 / UA / 语言只对之后新发起的请求有效，
     * 必须重载页面，所以提示用户而不是静默执行。
     *
     * @param before 改动**之前**的生效签名。注意要比对解析后的值而不是全局值 ——
     *   当前站点可能有自己的覆盖，拿全局值比会在「改了全局但被站点覆盖挡住」时误报。
     */
    private fun applyResolved(before: String, messageRes: Int) {
        val resolved = resolveFor(currentUrl)
        applyOrientation(resolved.orientation)

        val wv = webView
        if (wv != null) {
            wv.settings.userAgentString = resolved.userAgent
            if (signatureOf(resolved) != before) {
                SettingsDialogs.confirmReload(this, messageRes) { reloadCurrentPage() }
            }
        }
        // webView 为 null（空白标签）时无需重载，新设置会在首次加载时生效
    }

    // ==================== FloatingBallView.StateProvider ====================

    override fun currentUrl(): String? = currentUrl.takeIf { it.isNotBlank() }

    override fun canGoBack(): Boolean = webView?.canGoBack() == true

    override fun canGoForward(): Boolean = webView?.canGoForward() == true

    override fun isCurrentBookmarked(): Boolean =
        currentUrl.isNotBlank() && BookmarkManager.contains(this, currentUrl)

    // ==================== 新标签页 ====================

    private fun setupNewTabPage() {
        binding.newTabPage.btnNewTabGo.setOnClickListener { submitNewTabUrl() }
        binding.newTabPage.etNewTabUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN)
            ) {
                submitNewTabUrl()
                true
            } else false
        }
    }

    /** 本标签是空白页时，把定制的新标签页盖在 WebView 之上 */
    private fun updateNewTabPage() {
        val root = binding.newTabPage.root
        if (!isBlankTab) {
            if (root.visibility != View.GONE) root.visibility = View.GONE
            return
        }
        if (root.visibility != View.VISIBLE) root.visibility = View.VISIBLE
        populateNewTabPage()
    }

    private fun refreshNewTabPageIfVisible() {
        if (binding.newTabPage.root.visibility == View.VISIBLE) populateNewTabPage()
    }

    private fun submitNewTabUrl() {
        val input = binding.newTabPage.etNewTabUrl.text.toString().trim()
        if (input.isEmpty()) return
        binding.newTabPage.etNewTabUrl.setText("")
        hideKeyboard(binding.newTabPage.etNewTabUrl)
        openUrlReusingTab(UrlHelper.smartUrl(input))
    }

    private fun populateNewTabPage() {
        val page = binding.newTabPage

        // --- 打开的标签页（= 本 App 的所有 task）---
        page.openTabsContainer.removeAllViews()
        val open = TabRegistry.listOpenTabs(this)
        page.tvOpenTabsHeader.visibility = if (open.isEmpty()) View.GONE else View.VISIBLE
        open.forEach { tab ->
            val row = layoutInflater.inflate(
                R.layout.item_newtab_row, page.openTabsContainer, false
            )
            val isCurrent = tab.key == tabKey
            row.findViewById<TextView>(R.id.tvRowTitle).text =
                tab.displayTitle(getString(R.string.row_new_tab))
            row.findViewById<TextView>(R.id.tvRowUrl).text =
                if (tab.isBlank()) getString(R.string.row_blank_page) else tab.url
            row.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(
                when {
                    tab.isIncognito -> R.drawable.ic_incognito
                    tab.isBlank() -> R.drawable.ic_add
                    else -> R.drawable.ic_globe_gray
                }
            )
            // 无痕这个信息比「当前」更重要，两个都成立时一起显示
            val badge = when {
                isCurrent && tab.isIncognito -> getString(R.string.badge_current_incognito)
                isCurrent -> getString(R.string.badge_current)
                tab.isIncognito -> getString(R.string.badge_incognito)
                else -> null
            }
            row.findViewById<TextView>(R.id.tvRowBadge).apply {
                text = badge
                visibility = if (badge == null) View.GONE else View.VISIBLE
            }

            row.findViewById<ImageButton>(R.id.btnRowClose).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    TabRegistry.close(this@MainActivity, tab.key)
                    // 关掉当前标签等于关闭自己，交给系统走 onDestroy
                    if (!isCurrent) page.openTabsContainer.post { populateNewTabPage() }
                }
            }
            row.setOnClickListener {
                if (!isCurrent) TabRegistry.switchTo(this, tab.key)
            }
            page.openTabsContainer.addView(row)
        }

        // --- 收藏的网址 ---
        page.bookmarksContainer.removeAllViews()
        val bookmarks = BookmarkManager.getAll(this)
        page.tvBookmarksHeader.visibility = if (bookmarks.isEmpty()) View.GONE else View.VISIBLE
        page.tvNewTabEmpty.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
        bookmarks.forEach { bm ->
            val row = layoutInflater.inflate(
                R.layout.item_newtab_row, page.bookmarksContainer, false
            )
            row.findViewById<TextView>(R.id.tvRowTitle).text = bm.title
            row.findViewById<TextView>(R.id.tvRowUrl).text = bm.url
            row.findViewById<ImageView>(R.id.ivRowIcon)
                .setImageResource(R.drawable.ic_bookmark)
            if (open.any { it.url == bm.url }) {
                row.findViewById<TextView>(R.id.tvRowBadge).apply {
                    text = getString(R.string.badge_opened)
                    visibility = View.VISIBLE
                }
            }
            row.setOnClickListener { openUrlReusingTab(bm.url) }
            page.bookmarksContainer.addView(row)
        }
    }

    // ==================== 杂项 ====================

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** 仅当 Intent 带的是真正的网址（外部 App 调起）时才返回它 */
    private fun externalUrlFrom(i: Intent?): String? {
        val data = i?.data ?: return null
        return if (UrlHelper.isHttpUrl(data)) data.toString() else null
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_URL = "tab_url"
        private const val KEY_WEBVIEW_STATE = "tab_webview_state"
        private const val KEY_SCROLL_Y = "tab_scroll_y"

        /** 开新标签时用来传真正要加载的网址（data 留给了标签标识） */
        const val EXTRA_URL = "extra_url"

        /** 标记这个标签是无痕的 */
        const val EXTRA_INCOGNITO = "extra_incognito"

        /** 等页面排版稳定再滚回去，见 restoreScrollIfPending() */
        private const val SCROLL_RESTORE_DELAY_MS = 300L

        /**
         * 标签上限。每个标签是一个 Activity + WebView，但后台 task 会被系统按需回收，
         * 所以上限放宽到 32；保留一个上限只是防止某网站疯狂 window.open 把 App 拖死。
         */
        const val MAX_TABS = 32
    }
}
