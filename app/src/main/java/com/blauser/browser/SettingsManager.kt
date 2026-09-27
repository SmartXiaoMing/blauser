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
    private const val KEY_UA_KEY = "ua_key"
    private const val KEY_UA_CUSTOM = "ua_custom"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_BALL_X = "ball_x"
    private const val KEY_BALL_Y = "ball_y"

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

    /** 页面宽度候选 */
    val WIDTH_OPTIONS = listOf(
        IntOption(WIDTH_AUTO, R.string.width_auto),
        IntOption(WIDTH_FOLLOW_DEVICE, R.string.width_follow_device),
        IntOption(1280, R.string.width_1280),
        IntOption(1024, R.string.width_1024),
        IntOption(800, R.string.width_800),
        IntOption(768, R.string.width_768)
    )

    /** 没匹配到预设时统一回落到安卓默认 UA */
    private val fallbackUa: String get() = UA_PRESETS.first { it.key == UA_ANDROID }.value

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ===== 页面宽度 =====

    fun getPageWidth(c: Context): Int =
        prefs(c).getInt(KEY_PAGE_WIDTH, DEFAULT_PAGE_WIDTH)

    fun setPageWidth(c: Context, w: Int) =
        prefs(c).edit().putInt(KEY_PAGE_WIDTH, w).apply()

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
