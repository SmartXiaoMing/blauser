package com.blauser.browser

import android.content.Context
import android.net.Uri

/**
 * 按**站点**（域名）覆盖全局设置。
 *
 * 存在的理由：有些网站只在特定设置下才正常 —— 只认桌面 UA、必须横屏才有完整布局、
 * 或者要特定语言才给对应内容。每次都去改全局设置再改回来很烦，所以允许按域名单独记一份覆盖，
 * 离开这个站点自动失效。没被覆盖的项一律跟随全局。
 */
object SiteSettingsManager {

    /** 表示「跟随全局」，不覆盖 */
    const val INHERIT = Int.MIN_VALUE

    private const val PREF = "site_settings"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_WIDTH = "width"
    private const val KEY_UA = "ua"
    private const val KEY_LANG = "lang"

    /** 某个站点的覆盖项；INHERIT / null 表示跟随全局 */
    data class Overrides(
        val orientation: Int = INHERIT,
        val pageWidth: Int = INHERIT,
        val uaKey: String? = null,
        val language: String? = null
    )

    /** 全局设置叠加站点覆盖之后，最终生效的值 */
    data class Resolved(
        val orientation: Int,
        val pageWidth: Int,
        val userAgent: String,
        val language: String?
    )

    fun hostOf(url: String?): String? =
        url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(context: Context, host: String): Overrides {
        val p = prefs(context)
        return Overrides(
            orientation = p.getInt("$host#$KEY_ORIENTATION", INHERIT),
            pageWidth = p.getInt("$host#$KEY_WIDTH", INHERIT),
            uaKey = p.getString("$host#$KEY_UA", null),
            language = p.getString("$host#$KEY_LANG", null)
        )
    }

    fun save(context: Context, host: String, o: Overrides) {
        prefs(context).edit().apply {
            if (o.orientation == INHERIT) remove("$host#$KEY_ORIENTATION")
            else putInt("$host#$KEY_ORIENTATION", o.orientation)

            if (o.pageWidth == INHERIT) remove("$host#$KEY_WIDTH")
            else putInt("$host#$KEY_WIDTH", o.pageWidth)

            if (o.uaKey == null) remove("$host#$KEY_UA")
            else putString("$host#$KEY_UA", o.uaKey)

            if (o.language.isNullOrEmpty()) remove("$host#$KEY_LANG")
            else putString("$host#$KEY_LANG", o.language)
        }.apply()
    }

    fun clear(context: Context, host: String) {
        prefs(context).edit().apply {
            remove("$host#$KEY_ORIENTATION")
            remove("$host#$KEY_WIDTH")
            remove("$host#$KEY_UA")
            remove("$host#$KEY_LANG")
        }.apply()
    }

    /** 把全局设置与站点覆盖合起来，得到这个域名下真正生效的配置 */
    fun resolve(context: Context, host: String?): Resolved {
        val o = host?.let { get(context, it) } ?: Overrides()
        return Resolved(
            orientation = if (o.orientation != INHERIT) o.orientation
            else SettingsManager.getOrientation(context),
            pageWidth = if (o.pageWidth != INHERIT) o.pageWidth
            else SettingsManager.getPageWidth(context),
            // 站点覆盖选了「自定义…」时，取全局那份自定义 UA —— 本站设置面板里
            // 没有填 UA 的输入框，不这么接的话 uaValueOf(UA_CUSTOM) 会因为没有 custom
            // 而回落到安卓默认 UA，用户以为覆盖生效了，其实拿到的是另一串
            userAgent = if (o.uaKey != null) {
                SettingsManager.uaValueOf(o.uaKey, SettingsManager.getUaCustom(context))
            } else {
                SettingsManager.resolveUserAgent(context)
            },
            language = o.language?.takeIf { it.isNotBlank() }
                ?: SettingsManager.getLanguage(context).takeIf { it.isNotBlank() }
        )
    }

    /** 一键桌面模式的预设：桌面 UA + 固定 PC 宽度 */
    val DESKTOP_PRESET = Overrides(
        pageWidth = SettingsManager.AUTO_PC_WIDTH,
        uaKey = SettingsManager.UA_DESKTOP
    )

    /** 一键移动模式的预设：手机 UA + 跟随手机屏 */
    val MOBILE_PRESET = Overrides(
        pageWidth = SettingsManager.WIDTH_FOLLOW_DEVICE,
        uaKey = SettingsManager.UA_ANDROID
    )
}
