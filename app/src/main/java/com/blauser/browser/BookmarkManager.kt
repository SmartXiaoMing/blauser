package com.blauser.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(
    val title: String,
    val url: String,
    val time: Long = System.currentTimeMillis()
)

/**
 * 收藏夹持久化。
 *
 * 历史上存在两套并存且互不相通的实现：早期 MainActivity 内联的那套写在
 * `bookmarks` / `list`，本类写在 `bookmarks` / `bookmark_list`。现在统一到本类，
 * 由 [migrateIfNeeded] 把旧数据搬过来，避免用户已有收藏凭空消失。
 */
object BookmarkManager {
    private const val PREF_NAME = "bookmarks"
    private const val KEY_LIST = "bookmark_list"

    /** 旧版 MainActivity 内联实现用的 key */
    private const val LEGACY_KEY_LIST = "list"
    private const val KEY_MIGRATED = "migrated_v2"

    fun getAll(context: Context): MutableList<Bookmark> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return parse(prefs.getString(KEY_LIST, "[]") ?: "[]")
    }

    /**
     * 解析收藏列表。宽容优先 —— 解析失败、条目缺字段都不该让整个收藏夹消失。
     *
     * internal 而非 private：它是纯函数，可单测（见 app/src/test）。
     */
    internal fun parse(json: String): MutableList<Bookmark> {
        val arr = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Bookmark(
                title = obj.optString("title").ifBlank { url },
                url = url,
                // optLong 而非 getLong：旧数据没有 time 字段，getLong 会抛 JSONException
                time = obj.optLong("time", 0L)
            )
        }.toMutableList()
    }

    fun add(context: Context, bookmark: Bookmark) {
        val list = getAll(context)
        // 去重
        if (list.any { it.url == bookmark.url }) return
        list.add(0, bookmark)
        save(context, list)
    }

    /** 已存在则删除，不存在则添加。返回 true 表示操作后处于「已收藏」状态 */
    fun toggle(context: Context, bookmark: Bookmark): Boolean {
        val list = getAll(context)
        val existing = list.indexOfFirst { it.url == bookmark.url }
        return if (existing >= 0) {
            list.removeAt(existing)
            save(context, list)
            false
        } else {
            list.add(0, bookmark)
            save(context, list)
            true
        }
    }

    fun remove(context: Context, url: String) {
        val list = getAll(context).filter { it.url != url }.toMutableList()
        save(context, list)
    }

    fun contains(context: Context, url: String): Boolean =
        getAll(context).any { it.url == url }

    /**
     * 把旧 key 里的收藏搬到新 key，然后删掉旧 key。幂等，可以每次启动都调。
     *
     * 只有当新 key 为空时才搬——避免把用户在新版本里新加的收藏覆盖掉。
     */
    fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacyRaw = prefs.getString(LEGACY_KEY_LIST, null) ?: return
        val hasNew = (prefs.getString(KEY_LIST, null) ?: "[]") != "[]"

        if (!hasNew) {
            val legacyArr = runCatching { JSONArray(legacyRaw) }.getOrNull()
            if (legacyArr != null && legacyArr.length() > 0) {
                val out = JSONArray()
                for (i in 0 until legacyArr.length()) {
                    val obj = legacyArr.optJSONObject(i) ?: continue
                    val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val title = obj.optString("title").ifBlank { url }
                    out.put(JSONObject().apply {
                        put("title", title)
                        put("url", url)
                        put("time", 0L)
                    })
                }
                if (out.length() > 0) {
                    prefs.edit().putString(KEY_LIST, out.toString()).apply()
                }
            }
        }
        // 刻意为旧 key 留个标记而不是删掉它：万一日后发现迁移有偏差，用户原数据还在，
        // 能人工捞回来。占几 KB 存储换一个「永远可回滚」，划算。
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    /** 序列化为 JSON。同样是纯函数，便于单测 */
    internal fun serialize(list: List<Bookmark>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("title", it.title)
                put("url", it.url)
                put("time", it.time)
            })
        }
        return arr.toString()
    }

    private fun save(context: Context, list: List<Bookmark>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, serialize(list)).apply()
    }
}
