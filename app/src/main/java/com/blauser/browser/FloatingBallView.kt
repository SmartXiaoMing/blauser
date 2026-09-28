package com.blauser.browser

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/**
 * 可拖动的悬浮球，点击弹出下拉菜单。
 *
 * ## 为什么是半球
 * 圆球在屏幕边缘会占掉一整个 48dp 的横向空间。改成贴边的半球后横向只占 32dp，
 * 且在视觉上像是从屏幕边缘"探出来"的一个把手，更省地方。
 * 贴左边用右侧圆弧的形状，贴右边用左侧圆弧的，由 [updateBallShape] 按位置切换。
 *
 * ## 拖动与点击的区分
 * 光靠 OnClickListener 不行 —— 拖完松手也会触发 click。这里自己处理 touch：
 * 按下时记锚点，移动超过 touchSlop 就标记为拖动；松手时只有「没拖动过 **且** 按下时间够短」
 * 才算点击，避免拖拽结束误弹菜单。长按则直达设置。
 *
 * 位置持久化在 SharedPreferences，重启后回到原处，并被钳制在父容器边界内。
 *
 * 两个 lint 抑制的理由：
 *  - ViewConstructor：这个 View 只由代码 new 出来（见 [attach]），永远不会出现在 XML 里，
 *    所以不需要 (Context, AttributeSet) 那套构造函数。
 *  - ClickableViewAccessibility：无障碍要求「点击」能触发 performClick，
 *    本类在 ACTION_UP 里已经显式调用了（见 [handleTouch]），lint 只是看不到那条路径。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FloatingBallView(
    private val host: AppCompatActivity,
    private val parent: FrameLayout,
    private val menuListener: MenuListener,
    private val stateProvider: StateProvider
) : AppCompatImageView(host) {

    /** 悬浮球菜单的动作。新增一项只需在这里加个枚举值，再到 [menuRows] 加一行。 */
    enum class MenuAction {
        BACK,
        FORWARD,
        REFRESH,
        HOME,
        NEW_INCOGNITO,
        TOGGLE_BOOKMARK,
        BOOKMARKS,
        HISTORY,
        COPY_URL,
        SHARE,
        ADD_SHORTCUT,
        SITE_SETTINGS,
        SETTINGS,
        DEVTOOLS
    }

    fun interface MenuListener {
        fun onMenuAction(action: MenuAction)
    }

    /** 菜单需要知道的当前页面状态 */
    interface StateProvider {
        fun currentUrl(): String?
        fun canGoBack(): Boolean
        fun canGoForward(): Boolean
        fun isCurrentBookmarked(): Boolean
    }

    private data class MenuRow(
        val action: MenuAction,
        val iconRes: Int,
        @StringRes val labelRes: Int,
        val dividerBefore: Boolean = false,
        /** 返回 false 时该项置灰且不可点，比如无历史时的「后退」 */
        val enabledWhen: ((StateProvider) -> Boolean)? = null,
        /**
         * 按状态覆盖图标与文字。用于「收藏本页 / 取消收藏」这类同一入口切换语义的项 ——
         * 没有它的话，用户看不出当前页到底收藏了没有。
         */
        val dynamic: ((StateProvider) -> Pair<Int, Int>)? = null,
        /**
         * 返回 false 时整项**不显示**（不是置灰）。
         * 用于这台设备根本不支持的功能：无痕标签要 Android 9+，
         * 摆一个永远点不动的灰色项只会让人困惑。
         */
        val visibleWhen: (() -> Boolean)? = null
    )

    /**
     * 这几项都要求「当前有一个真正的网页」。
     *
     * 空白标签（新标签页）上没有网址，点了只能得到一句 toast 说「当前没有可…的页面」——
     * 与其让人点进去才发现，不如和「后退 / 前进」一样直接置灰。
     */
    private fun needsPage(sp: StateProvider): Boolean = sp.currentUrl() != null

    private val menuRows = listOf(
        MenuRow(MenuAction.BACK, R.drawable.ic_back, R.string.menu_back,
            enabledWhen = { it.canGoBack() }),
        MenuRow(MenuAction.FORWARD, R.drawable.ic_forward, R.string.menu_forward,
            enabledWhen = { it.canGoForward() }),
        MenuRow(MenuAction.REFRESH, R.drawable.ic_refresh, R.string.menu_refresh),
        MenuRow(MenuAction.HOME, R.drawable.ic_home, R.string.menu_home, dividerBefore = true),
        MenuRow(MenuAction.NEW_INCOGNITO, R.drawable.ic_incognito, R.string.menu_incognito,
            visibleWhen = { Incognito.isSupported }),
        MenuRow(
            MenuAction.TOGGLE_BOOKMARK, R.drawable.ic_bookmark, R.string.menu_bookmark_add,
            enabledWhen = { needsPage(it) },
            dynamic = { sp ->
                if (sp.isCurrentBookmarked()) {
                    R.drawable.ic_bookmark_filled to R.string.menu_bookmark_remove
                } else {
                    R.drawable.ic_bookmark to R.string.menu_bookmark_add
                }
            }
        ),
        MenuRow(MenuAction.BOOKMARKS, R.drawable.ic_bookmarks, R.string.menu_bookmarks),
        MenuRow(MenuAction.HISTORY, R.drawable.ic_history, R.string.menu_history),
        MenuRow(MenuAction.COPY_URL, R.drawable.ic_copy, R.string.menu_copy_url,
            dividerBefore = true, enabledWhen = { needsPage(it) }),
        MenuRow(MenuAction.SHARE, R.drawable.ic_share, R.string.menu_share,
            enabledWhen = { needsPage(it) }),
        MenuRow(MenuAction.ADD_SHORTCUT, R.drawable.ic_add_to_home, R.string.menu_add_shortcut,
            enabledWhen = { needsPage(it) }),
        MenuRow(MenuAction.SITE_SETTINGS, R.drawable.ic_settings, R.string.menu_site_settings,
            dividerBefore = true, enabledWhen = { needsPage(it) }),
        MenuRow(MenuAction.SETTINGS, R.drawable.ic_settings, R.string.menu_settings),
        MenuRow(MenuAction.DEVTOOLS, R.drawable.ic_devtools, R.string.menu_devtools),
    )

    private val touchSlop = ViewConfiguration.get(host).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var downTime = 0L
    private var dragging = false

    private var menu: PopupWindow? = null

    /** 当前用的形状资源；用于避免拖动过程中反复换背景。0 表示还没设过 */
    private var shapeRes: Int = 0

    /** 上一次已知的方向；null 表示还没量过。用来在旋转后重新贴边 */
    private var wasLandscape: Boolean? = null

    /**
     * 无痕模式。悬浮球是整个界面上唯一的常驻控件，它也是唯一能承载
     * 「这个标签是无痕的」这个信息的地方 —— 换个颜色，一眼可见。
     */
    private var incognito = false

    /**
     * 顶部安全距离（刘海 / 挖孔的高度，px）。
     *
     * 窗口开了 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，内容会一直铺到屏幕顶端
     * —— 网页该这样，但悬浮球不行：贴到最上面就整颗画进刘海里，那里物理上没有像素，
     * 表现就是「球不见了，但盲拖还能拖出来」。所以球的 y 下限卡在这个值上。
     */
    private var safeTop = 0

    /** 空闲后淡出，避免一直挡着网页内容 */
    private val fadeOut = Runnable {
        animate().alpha(IDLE_ALPHA).setDuration(300).start()
    }

    init {
        setImageResource(R.drawable.ic_ball)
        scaleType = ScaleType.CENTER
        contentDescription = host.getString(R.string.ball_desc)
        elevation = dp(6f).toFloat()
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    /** 加到父容器并恢复上次的位置 */
    fun attach() {
        parent.addView(
            this,
            FrameLayout.LayoutParams(dp(ballWidthDp()), dp(ballHeightDp())).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        updateBallShape()
        // 键盘弹出（windowSoftInputMode=adjustResize 会让容器变矮）、旋转、分屏都会改变
        // 父容器尺寸，可能把球挤到可视区外，每次布局变化都重新钳制一次
        parent.addOnLayoutChangeListener(layoutClampListener)

        // 等父容器量完宽高才能算边界，否则 restore 会被钳到 0
        post {
            restorePosition()
            scheduleFadeOut()
        }
    }

    private val layoutClampListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        // 顺序有讲究：先换宽高，后面的 maxX/maxY 才是对的
        applyBallSizeIfNeeded()
        val nx = x.coerceIn(0f, maxX())
        val ny = y.coerceIn(minY(), maxY())
        // 只在真的越界时才赋值，避免自己触发自己的布局回调
        if (nx != x) x = nx
        if (ny != y) y = ny

        // 竖横屏切换后，原来贴的那条边已经不是长边了，重新贴一次；
        // 否则球会停在屏幕中间（旧坐标在两个方向上都不靠边）
        val landscape = isLandscape()
        if (wasLandscape != null && wasLandscape != landscape) {
            snapToEdge()
            savePosition()
        }
        wasLandscape = landscape

        updateBallShape()
    }

    /**
     * 横屏时把球横过来（56dp 宽 × 32dp 高），因为它要贴的是上下边。
     *
     * 用「宽 > 高」判断而不是读 Configuration.orientation：分屏下窗口本身可能是
     * 横的而系统仍是竖屏，按窗口实际形状判断才不会贴错边。
     */
    private fun isLandscape(): Boolean = parent.width > parent.height

    private fun ballWidthDp(): Float = if (isLandscape()) BALL_HEIGHT_DP else BALL_WIDTH_DP

    private fun ballHeightDp(): Float = if (isLandscape()) BALL_WIDTH_DP else BALL_HEIGHT_DP

    /** 旋转 / 分屏后窗口形状变了，球的宽高要跟着换 */
    private fun applyBallSizeIfNeeded() {
        val wantW = dp(ballWidthDp())
        val wantH = dp(ballHeightDp())
        val lp = layoutParams ?: return
        if (lp.width == wantW && lp.height == wantH) return
        lp.width = wantW
        lp.height = wantH
        layoutParams = lp
    }

    /** 刘海高度变化（旋转、换屏、进入分屏）时调用 */
    fun setSafeTop(px: Int) {
        if (safeTop == px) return
        safeTop = px
        // 已经在刘海里的球要拉回来，否则它会一直看不见
        val ny = y.coerceIn(minY(), maxY())
        if (ny != y) y = ny
    }

    /** 球能停的最上面。父容器太矮时退化成 0，别把 coerceIn 的区间搞反 */
    private fun minY(): Float = safeTop.toFloat().coerceAtMost(maxY())

    /**
     * 换球的形状，让它看起来是从所在的那条边「探出来」的。
     *
     * 半球贴在**长边**上：竖屏是左右，横屏是上下 —— 把手顺着长边伸出去，
     * 占的是短边方向的空间，才不会把内容挤扁。
     */
    private fun updateBallShape() {
        val res = if (isLandscape()) {
            // 横屏贴上下：在上半屏就贴上边（上平下圆），反之贴下边
            val onTop = y + ballH() / 2f < parent.height / 2f
            if (onTop) R.drawable.bg_ball_half_bottom else R.drawable.bg_ball_half_top
        } else {
            // 竖屏贴左右：在左半屏就贴左边（左平右圆），反之贴右边
            val onLeft = x + ballW() / 2f < parent.width / 2f
            if (onLeft) R.drawable.bg_ball_half_right else R.drawable.bg_ball_half_left
        }
        if (shapeRes == res) return
        shapeRes = res
        setBackgroundResource(res)
        // setBackgroundResource 会清掉 tint，换形状后必须重新染一次
        applyBallTint()
    }

    fun setIncognito(on: Boolean) {
        if (incognito == on) return
        incognito = on
        applyBallTint()
    }

    /**
     * 用 backgroundTint 染色，而不是再画一套 drawable：
     * 半球有「贴左」「贴右」两份形状，颜色再乘两份就是四个文件，
     * 而且以后加第三种模式还要继续乘下去。
     */
    private fun applyBallTint() {
        backgroundTintList = if (incognito) {
            ColorStateList.valueOf(ContextCompat.getColor(host, R.color.incognito))
        } else {
            null   // null = 用 drawable 自己的颜色（品牌橙）
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                anchorX = x
                anchorY = y
                downTime = SystemClock.uptimeMillis()
                dragging = false
                wakeUp()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) {
                    x = (anchorX + dx).coerceIn(0f, maxX())
                    y = (anchorY + dy).coerceIn(minY(), maxY())
                    updateBallShape()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val elapsed = SystemClock.uptimeMillis() - downTime
                when {
                    dragging -> {
                        snapToEdge()
                        savePosition()
                    }
                    // 短按：弹菜单
                    elapsed < CLICK_TIMEOUT_MS -> performClick()
                    // 长按：直达设置。底部导航栏去掉后，这是设置的另一条入口
                    elapsed >= LONG_PRESS_TIMEOUT_MS ->
                        menuListener.onMenuAction(MenuAction.SETTINGS)
                    // 中间这段什么都不做，避免手抖误触
                }
                scheduleFadeOut()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                scheduleFadeOut()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        showMenu()
        return super.performClick()
    }

    // ==================== 菜单 ====================

    @SuppressLint("InflateParams")
    private fun showMenu() {
        dismissMenu()

        // 这里传 null 作为 parent 是正确的：PopupWindow 的内容本来就没有父容器，
        // 传它进去反而会立刻被加进视图树。菜单尺寸随后由 measure() 自己量。
        // 先把内容拼好，再决定弹窗多大 —— 尺寸要靠量出来才知道
        val content = LayoutInflater.from(host)
            .inflate(R.layout.view_floating_menu, null, false) as LinearLayout

        // 顶部显示当前网址：固定宽度 + 中间省略，开头和结尾都看得到
        val url = stateProvider.currentUrl()?.takeIf { it.isNotBlank() }
        if (url != null) {
            val header = LayoutInflater.from(host)
                .inflate(R.layout.item_floating_menu_url, content, false)
            header.findViewById<TextView>(R.id.tvMenuUrl).text = url
            content.addView(header)
            content.addView(makeDivider(152f))
        }

        // 按 menuRows 生成菜单项
        menuRows.forEach { row ->
            // 设备不支持的功能整项不显示（比如 Android 9 以下的无痕标签）
            if (row.visibleWhen?.invoke() == false) return@forEach

            if (row.dividerBefore) content.addView(makeDivider(24f))

            val enabled = row.enabledWhen?.invoke(stateProvider) ?: true
            val (iconRes, labelRes) = row.dynamic?.invoke(stateProvider)
                ?: (row.iconRes to row.labelRes)
            val item = LayoutInflater.from(host)
                .inflate(R.layout.item_floating_menu, content, false)
            item.findViewById<ImageView>(R.id.ivMenuIcon).setImageResource(iconRes)
            item.findViewById<TextView>(R.id.tvMenuLabel).text = host.getString(labelRes)

            if (enabled) {
                item.setOnClickListener {
                    dismissMenu()
                    menuListener.onMenuAction(row.action)
                }
            } else {
                // 置灰且不可点：比如没有历史时的「后退」
                item.alpha = 0.35f
                item.isClickable = false
            }
            content.addView(item)
        }

        // 先量一次拿到菜单的自然尺寸，才能决定往上弹还是往下弹、以及横向怎么收进屏内
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val mw = content.measuredWidth
        val mh = content.measuredHeight

        // showAtLocation 用的是屏幕坐标，而球的 x/y 是相对父容器的，需要换算
        val loc = IntArray(2)
        parent.getLocationOnScreen(loc)
        val ballScreenX = loc[0] + x
        val ballScreenY = loc[1] + y
        val screenTop = loc[1] + dp(4f)
        val screenBottom = loc[1] + parent.height
        val gap = dp(GAP_DP)

        // 关键约束只有一个：**整块菜单要落在窗口内**。装在装不下时交给 ScrollView 滚。
        //
        // 之前没有滚动、也没有底部钳制，只保证顶部不越界 —— 横屏时窗口只有三百多 dp
        // 而菜单近六百 dp，最下面那几项（本站设置 / 全局设置 / 审查元素）被切在屏幕外，
        // 永远点不到。
        val popupH = minOf(mh, screenBottom - screenTop)

        val scroll = ScrollView(host).apply {
            addView(
                content,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val popup = PopupWindow(scroll, mw, popupH, true).apply {
            // 没有背景的话点击外部不会自动关闭
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8f).toFloat()
        }

        var px = ballScreenX.toInt()
        // 优先往下弹；下方放不下就翻到球上方；两边都不够就贴顶 ——
        // 这个是 coerceIn 而不是 coerceAtLeast，底部同样被钳住
        var py = (ballScreenY + height + gap).toInt()
        if (py + popupH > screenBottom) py = (ballScreenY - popupH - gap).toInt()
        val maxPy = (screenBottom - popupH).coerceAtLeast(screenTop)
        py = py.coerceIn(screenTop, maxPy)
        // 菜单比窗口还宽时（分屏、极窄窗口）coerceIn 的区间会反过来直接抛异常，
        // 这里退化成「贴着左边显示」，宁可超出也比崩掉强
        val maxPx = loc[0] + parent.width - mw - dp(8f)
        px = if (maxPx >= dp(8f)) px.coerceIn(dp(8f), maxPx) else dp(8f)

        popup.showAtLocation(parent, Gravity.NO_GRAVITY, px, py)
        menu = popup
    }

    /**
     * 菜单里的分隔线。
     * 必须给**固定宽度**：PopupWindow 以 AT_MOST 测量，用 match_parent 会撑满可用宽度，
     * 把整个菜单拖到屏幕那么宽。
     */
    private fun makeDivider(widthDp: Float): View = View(host).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(1f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(3f)
            bottomMargin = dp(3f)
        }
        // 走颜色资源而不是写死色值，深色模式下才跟着变
        setBackgroundColor(ContextCompat.getColor(host, R.color.divider))
    }

    fun dismissMenu() {
        menu?.takeIf { it.isShowing }?.dismiss()
        menu = null
    }

    // ==================== 位置 ====================

    /**
     * 球的宽高。
     *
     * **优先读 LayoutParams 而不是 view.width/height**：横竖屏切换时我们刚改完
     * LayoutParams，而 View 的 width/height 要等下一次布局才更新。在那之前用它算
     * maxX/maxY 会得到上一方向的旧值，贴边就会差一截（实测横屏转竖屏后停在
     * x=933 而不是 996，差的正好是两种宽度之差）。
     */
    private fun ballW(): Int = layoutParams?.width ?: width
    private fun ballH(): Int = layoutParams?.height ?: height

    private fun maxX() = (parent.width - ballW()).coerceAtLeast(0).toFloat()
    private fun maxY() = (parent.height - ballH()).coerceAtLeast(0).toFloat()

    /** 松手后贴边，别停在屏幕正中间挡内容 */
    private fun snapToEdge() {
        if (isLandscape()) {
            // 横屏贴上下。用 minY() 而不是 0：球贴在顶边时不能钻进刘海
            val target = if (y + ballH() / 2f < parent.height / 2f) minY() else maxY()
            animate().y(target).setDuration(150).start()
        } else {
            val target = if (x + ballW() / 2f < parent.width / 2f) 0f else maxX()
            animate().x(target).setDuration(150).start()
        }
        // 动画结束后朝向才最终确定
        postDelayed({ updateBallShape() }, 180)
    }

    private fun savePosition() {
        // 动画结束后 x 才到位，延后一点再存
        postDelayed({
            SettingsManager.setBallPosition(host, x, y)
        }, 200)
    }

    private fun restorePosition() {
        val saved = SettingsManager.getBallPosition(host)
        if (saved == null) {
            // 首次启动：停在屏幕下半 / 偏一侧，避开常见的内容区
            if (isLandscape()) {
                x = maxX() * 0.6f
                y = maxY()
            } else {
                x = maxX()
                y = maxY() * 0.6f
            }
        } else {
            x = saved.first.coerceIn(0f, maxX())
            // 上次保存的位置可能来自另一种方向（或没有刘海的横屏），重新钳一次
            y = saved.second.coerceIn(minY(), maxY())
        }
        updateBallShape()
    }

    // ==================== 淡出 ====================

    private fun wakeUp() {
        removeCallbacks(fadeOut)
        animate().cancel()
        alpha = 1f
    }

    private fun scheduleFadeOut() {
        removeCallbacks(fadeOut)
        postDelayed(fadeOut, IDLE_TIMEOUT_MS)
    }

    override fun onDetachedFromWindow() {
        dismissMenu()
        removeCallbacks(fadeOut)
        parent.removeOnLayoutChangeListener(layoutClampListener)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    companion object {
        /** 半球：横向只占 32dp（原来整圆是 48dp），高度给足以保证圆弧是半圆 */
        private const val BALL_WIDTH_DP = 32f
        private const val BALL_HEIGHT_DP = 56f
        private const val GAP_DP = 8f
        private const val CLICK_TIMEOUT_MS = 300L
        private const val LONG_PRESS_TIMEOUT_MS = 600L
        private const val IDLE_TIMEOUT_MS = 3000L
        private const val IDLE_ALPHA = 0.45f
    }
}
