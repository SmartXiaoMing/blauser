package com.blauser.browser

import android.content.Context
import androidx.annotation.StringRes

/**
 * 浏览器设置持久化。
 *
 * 选项列表里存的是**字符串资源 id** 而不是字面量：加语言时只要多一份 strings.xml，
 * 不用回来改这里的代码。
 */
object SettingsManager {

    private const val PREF = "browser_settings"

    private const val KEY_PAGE_WIDTH = "page_width"

    /** 虚拟屏幕高度。0 表示不限（只按宽度缩放，纵向滚动） */
    private const val KEY_PAGE_HEIGHT = "page_height"
    private const val KEY_UA_KEY = "ua_key"
    private const val KEY_UA_CUSTOM = "ua_custom"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_BALL_X = "ball_x"
    private const val KEY_BALL_Y = "ball_y"

    /** 是否允许用户缩放（捏合 / 双击）。默认关：刷网页时误触缩放很烦 */
    private const val KEY_ZOOM_ENABLED = "zoom_enabled"

    /** 带文案的整数选项，页面宽度与屏幕方向共用一套结构 */
    data class IntOption(val value: Int, @StringRes val labelRes: Int)

    /** 屏幕方向候选。值用 ActivityInfo 的方向常量 */
    val ORIENTATION_OPTIONS = listOf(
        IntOption(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            R.string.orientation_system
        ),
        IntOption(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            R.string.orientation_portrait
        ),
        IntOption(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            R.string.orientation_landscape
        ),
    )

    /**
     * 自动：页面自己声明了 `width=device-width`（即移动端适配）就不动它，
     * 否则按 [AUTO_PC_WIDTH] 当 PC 页面缩放。默认值，兼顾「PC 页适配」和「移动页原生」。
     */
    const val WIDTH_AUTO = -1

    /** 页面虚拟宽度取该值时，表示跟随手机屏，不做任何缩放 */
    const val WIDTH_FOLLOW_DEVICE = 0

    /** 自动模式下，判定为 PC 页面时使用的宽度 */
    const val AUTO_PC_WIDTH = 1280

    const val DEFAULT_PAGE_WIDTH = WIDTH_AUTO

    // ===== UserAgent 预设 =====

    const val UA_ANDROID = "android"
    const val UA_DESKTOP = "desktop"
    const val UA_IPHONE = "iphone"
    const val UA_CUSTOM = "custom"

    data class UaPreset(val key: String, @StringRes val labelRes: Int, val value: String)

    val UA_PRESETS = listOf(
        UaPreset(
            UA_ANDROID, R.string.ua_android,
            "Mozilla/5.0 (Linux; Android 13; Xiaomi) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        ),
        UaPreset(
            UA_DESKTOP, R.string.ua_desktop,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        ),
        UaPreset(
            UA_IPHONE, R.string.ua_iphone,
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        ),
        // 自定义项没有固定 UA，取用户填写的字符串
        UaPreset(UA_CUSTOM, R.string.ua_custom, "")
    )

    /**
     * 页面分辨率候选。
     *
     * 这些档位**只定宽度**：网页按该宽度排版，再整体缩放贴合手机屏宽，纵向照常滚动。
     * 档位标签里的高度（如 1280 × 720）只是这个宽度「相当于什么屏幕」的说明，
     * 不参与渲染 —— 否则手机屏又高又窄，保 16:9 就得上下留一大块白，日常浏览很难受。
     *
     * 想按真实比例模拟一块屏幕（等比缩放 + 四周留白）用「自定义」，那边可以填宽和高。
     */
    val WIDTH_OPTIONS = listOf(
        IntOption(WIDTH_AUTO, R.string.width_auto),
        IntOption(WIDTH_FOLLOW_DEVICE, R.string.width_follow_device),
        IntOption(1280, R.string.width_1280),
        IntOption(1366, R.string.width_1366),
        IntOption(1024, R.string.width_1024),
        IntOption(800, R.string.width_800)
    )

    /** 仅用于下拉框：用户选了「自定义…」，此时读输入框里的宽高 */
    const val WIDTH_CUSTOM = -2

    /** 自定义分辨率的兜底值 */
    const val DEFAULT_CUSTOM_WIDTH = 1280
    const val DEFAULT_CUSTOM_HEIGHT = 720

    private const val MIN_SIZE = 120
    private const val MAX_SIZE = 10000

    /** 没匹配到预设时统一回落到安卓默认 UA */
    private val fallbackUa: String get() = UA_PRESETS.first { it.key == UA_ANDROID }.value

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ===== 页面宽度 =====

    fun getPageWidth(c: Context): Int =
        prefs(c).getInt(KEY_PAGE_WIDTH, DEFAULT_PAGE_WIDTH)

    /**
     * 虚拟屏幕高度（CSS px）。0 表示不限高 —— 网页铺满屏宽、纵向滚动。
     * 只有「自定义」分辨率会填它，填了就按 W:H 等比缩放并四周留白。
     */
    fun getPageHeight(c: Context): Int =
        prefs(c).getInt(KEY_PAGE_HEIGHT, 0)

    fun setResolution(c: Context, width: Int, height: Int) =
        prefs(c).edit()
            .putInt(KEY_PAGE_WIDTH, width)
            .putInt(KEY_PAGE_HEIGHT, height.coerceIn(0, MAX_SIZE))
            .apply()

    fun setPageWidth(c: Context, w: Int) = setResolution(c, w, 0)

    /** 自定义分辨率回显：当前值不是预设档时就当作自定义 */
    fun customWidthForEditing(c: Context): Int =
        getPageWidth(c).takeIf { it > 0 } ?: DEFAULT_CUSTOM_WIDTH

    fun customHeightForEditing(c: Context): Int =
        getPageHeight(c).takeIf { it > 0 } ?: DEFAULT_CUSTOM_HEIGHT

    /** 把用户填的尺寸夹到合理范围，防止填 0 或天文数字把页面搞崩 */
    fun sanitizeSize(value: Int, fallback: Int): Int =
        value.takeIf { it in MIN_SIZE..MAX_SIZE } ?: fallback

    // ===== UserAgent =====

    fun getUaKey(c: Context): String =
        prefs(c).getString(KEY_UA_KEY, UA_ANDROID) ?: UA_ANDROID

    fun getUaCustom(c: Context): String =
        prefs(c).getString(KEY_UA_CUSTOM, "") ?: ""

    fun setUa(c: Context, key: String, custom: String) =
        prefs(c).edit()
            .putString(KEY_UA_KEY, key)
            .putString(KEY_UA_CUSTOM, custom)
            .apply()

    /** 按预设 key 取 UA 字符串；自定义项需要另外传值 */
    fun uaValueOf(key: String, custom: String = ""): String {
        if (key == UA_CUSTOM) {
            // 自定义为空时回落到安卓默认，避免把 UA 设成空串
            return custom.takeIf { it.isNotBlank() } ?: fallbackUa
        }
        return UA_PRESETS.firstOrNull { it.key == key }?.value ?: fallbackUa
    }

    /** 解析出最终要写进 WebSettings 的 UA 字符串 */
    fun resolveUserAgent(c: Context): String = uaValueOf(getUaKey(c), getUaCustom(c))

    // ===== 屏幕方向 =====

    fun getOrientation(c: Context): Int = prefs(c).getInt(
        KEY_ORIENTATION, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    )

    fun setOrientation(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_ORIENTATION, value).apply()

    // ===== 语言（Accept-Language / navigator.language）=====

    /** 空串表示跟随系统 */
    fun getLanguage(c: Context): String =
        prefs(c).getString(KEY_LANGUAGE, "").orEmpty()

    fun setLanguage(c: Context, value: String) =
        prefs(c).edit().putString(KEY_LANGUAGE, value.trim()).apply()

    /** 供设置页回显：当前自定义 UA（选中自定义项但还没填时，预填安卓默认值方便修改） */
    fun getUaForEditing(c: Context): String {
        if (getUaKey(c) == UA_CUSTOM) {
            val custom = getUaCustom(c)
            if (custom.isNotBlank()) return custom
        }
        return resolveUserAgent(c)
    }

    // ===== 缩放开关 =====

    /**
     * 允许用户缩放（双指捏合 / 双击）。
     *
     * **默认关闭**：这个 App 的定位是把 PC 页面整体缩放贴合屏幕，页面本身就是
     * 按屏幕适配过的，再叠加手势缩放大多是误触。需要看清细节时再从菜单里打开。
     */
    fun isZoomEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_ZOOM_ENABLED, false)

    fun setZoomEnabled(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean(KEY_ZOOM_ENABLED, enabled).apply()

    // ===== 悬浮球位置 =====

    /** 悬浮球位置；返回 null 表示还没存过，由调用方给默认位置 */
    fun getBallPosition(c: Context): Pair<Float, Float>? {
        val p = prefs(c)
        if (!p.contains(KEY_BALL_X) || !p.contains(KEY_BALL_Y)) return null
        return p.getFloat(KEY_BALL_X, 0f) to p.getFloat(KEY_BALL_Y, 0f)
    }

    fun setBallPosition(c: Context, x: Float, y: Float) =
        prefs(c).edit().putFloat(KEY_BALL_X, x).putFloat(KEY_BALL_Y, y).apply()
}
