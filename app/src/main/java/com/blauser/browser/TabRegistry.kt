package com.blauser.browser

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build

/**
 * 标签页 = Android 任务（task）。
 *
 * ## 为什么是 task 而不是进程
 *
 * 每个标签页是一个独立的 `MainActivity` 实例，跑在**独立的 task** 里 ——
 * 于是它会在系统的「最近任务」里占一张卡片，用系统的方式就能切换，不必再回 App 内找列表。
 *
 * 注意这些 task 仍在**同一个进程**里，所以 cookie / localStorage 照常共享；
 * 这不是多进程，多进程反而会因为每个进程要独立的 WebView 数据目录而丢掉共享登录态。
 *
 * ## 标签身份
 *
 * 每个 tab 的启动 Intent 带一个唯一的 data URI（`blauser://tab/<uuid>`）。
 * 实测 task 的 `baseIntent` 会保留这个 URI，因此它就是标签的稳定标识：
 * 存 URL 用它做 key，找 task 也用它匹配。
 *
 * ## 代价
 *
 * 后台 task 的 Activity 处于 stopped 状态，内存紧张时系统**可以销毁它** ——
 * 再切回来就是重建 + 重新加载页面。这一点与「切回来绝不重载」不可兼得，
 * 是本设计明确接受的取舍。
 */
object TabRegistry {

    private const val PREF = "tab_registry"
    private const val SCHEME = "blauser"
    private const val HOST = "tab"
    private const val KEY_URL = "url"
    private const val KEY_TITLE = "title"
    private const val KEY_INCOGNITO = "incognito"

    /** 由 Launcher 图标启动的主 task 没有 data URI，用这个常量兜底 */
    const val MAIN_TAB_KEY = "blauser://tab/main"

    private const val KEY_PREFIX = "$SCHEME://$HOST/"

    data class OpenTab(
        val key: String,
        val url: String,
        val title: String,
        val taskId: Int,
        val isIncognito: Boolean
    ) {
        /** blankLabel 由调用方传入，这样本类不必依赖 Context 取字符串资源 */
        fun displayTitle(blankLabel: String): String =
            title.ifBlank { url.ifBlank { blankLabel } }

        fun isBlank(): Boolean = url.isBlank()
    }

    fun newTabKey(): String = KEY_PREFIX + java.util.UUID.randomUUID()

    /**
     * 从 task 的 baseIntent 里取出标签标识，用于**匹配 task**。
     *
     * 绝大多数情况是本 App 生成且唯一的 `blauser://tab/<uuid>`。但有两个来源拿不到它：
     *  - 桌面图标启动：没有 data，归到 [MAIN_TAB_KEY]
     *  - 外部 App 用 ACTION_VIEW 调起：data 就是那个网址，于是 key 退化成网址本身
     *
     * 第二种情况**不唯一** —— 同一个网址被外部打开两次会撞成同一个 key，
     * 导致状态互相覆盖、关一个关俩。所以 MainActivity 收到这类 Intent 时不会就地加载，
     * 而是另开一个带 uuid 的正经标签页再结束自己（见 `MainActivity.onCreate` 开头）。
     */
    fun keyFrom(uri: Uri?): String = uri?.toString() ?: MAIN_TAB_KEY

    // ===== 每个标签自己的状态，由该标签的 Activity 持续更新 =====

    fun saveState(context: Context, key: String, url: String, title: String, incognito: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("$key#$KEY_URL", url)
            .putString("$key#$KEY_TITLE", title)
            .putBoolean("$key#$KEY_INCOGNITO", incognito)
            .apply()
    }

    fun remove(context: Context, key: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("$key#$KEY_URL")
            .remove("$key#$KEY_TITLE")
            .remove("$key#$KEY_INCOGNITO")
            .apply()
    }

    private fun urlOf(context: Context, key: String): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("$key#$KEY_URL", "").orEmpty()

    private fun titleOf(context: Context, key: String): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("$key#$KEY_TITLE", "").orEmpty()

    private fun incognitoOf(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("$key#$KEY_INCOGNITO", false)

    // ===== 枚举 / 切换 / 关闭 =====

    private fun appTasks(context: Context): List<ActivityManager.AppTask> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching { am.appTasks }.getOrDefault(emptyList())
    }

    /**
     * 列出当前所有已打开的标签页（= 本 App 的所有 task）。
     *
     * 按 taskId 倒序：taskId 单调递增，越大越新，这样列表是「最近打开的在前」。
     */
    fun listOpenTabs(context: Context): List<OpenTab> =
        appTasks(context)
            .mapNotNull { task ->
                val key = keyFrom(task.taskInfo?.baseIntent?.data)
                OpenTab(
                    key = key,
                    url = urlOf(context, key),
                    title = titleOf(context, key),
                    taskId = taskIdOf(task),
                    isIncognito = incognitoOf(context, key)
                )
            }
            .sortedByDescending { it.taskId }
            .distinctBy { it.key }

    /** 把某个标签的 task 提到前台。返回 false 表示它已经不在了 */
    fun switchTo(context: Context, key: String): Boolean {
        val target = appTasks(context).firstOrNull { keyOf(it) == key } ?: return false
        return runCatching { target.moveToFront(); true }.getOrDefault(false)
    }

    /** 关闭某个标签（连同它的 task 一起移除） */
    fun close(context: Context, key: String) {
        appTasks(context)
            .filter { keyOf(it) == key }
            .forEach { runCatching { it.finishAndRemoveTask() } }
        remove(context, key)
    }

    private fun keyOf(task: ActivityManager.AppTask): String =
        keyFrom(task.taskInfo?.baseIntent?.data)

    /** TaskInfo.id 在 API 29 起被 taskId 取代 */
    @Suppress("DEPRECATION")
    private fun taskIdOf(task: ActivityManager.AppTask): Int {
        val info = task.taskInfo ?: return Int.MAX_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.id
    }

    /** 当前打开了几个标签 */
    fun count(context: Context): Int = appTasks(context).size

    /**
     * 除了 [exceptKey] 之外还有几个无痕标签。
     *
     * 用来判断「关掉这一个之后，无痕 profile 是不是就没人用了」——
     * profile 还被 WebView 占用时 deleteProfile 会抛异常。
     */
    fun countIncognitoExcept(context: Context, exceptKey: String): Int =
        listOpenTabs(context).count { it.isIncognito && it.key != exceptKey }
}
