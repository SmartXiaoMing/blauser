package com.blauser.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 浏览历史。
 *
 * 存储方式和收藏夹保持一致（SharedPreferences 里存一份 JSON），理由也一样：
 * 条数有限、读写都在主线程但很快，不值得为它上数据库。
 *
 * 两个刻意的设计：
 *  - **同一个网址只留一条**，重复访问只是把它挪到最前并刷新时间。否则翻过的
 *    每个列表页都会各占一行，历史会迅速变成一团噪音。
 *  - **有上限**（[MAX_ENTRIES]）。整份 JSON 每次读写都要解析，不设上限的话
 *    历史越长越慢，而用户根本翻不到几百条以前。
 *
 * 无痕标签不写历史 —— 这是它存在的意义。调用方负责判断（见 MainActivity）。
 */
object HistoryManager {

    private const val PREF_NAME = "history"
    private const val KEY_LIST = "history_list"

    /** 最多保留多少条 */
    const val MAX_ENTRIES = 500

    data class Entry(val title: String, val url: String, val time: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Entry> =
        parse(prefs(context).getString(KEY_LIST, "[]") ?: "[]")

    fun isEmpty(context: Context): Boolean = getAll(context).isEmpty()

    /** 记一次访问。同一个网址只留最新一条，并置顶 */
    fun record(context: Context, title: String, url: String) {
        if (url.isBlank()) return
        val merged = merge(getAll(context), title, url, System.currentTimeMillis())
        prefs(context).edit().putString(KEY_LIST, serialize(merged)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LIST).apply()
    }

    /**
     * 把一条记录并进列表：同 URL 的旧记录删掉，新记录插到最前，再截断到上限。
     *
     * 拆成纯函数是为了可测（见 app/src/test）—— 去重和截断这两件事最容易写错。
     */
    internal fun merge(
        existing: List<Entry>,
        title: String,
        url: String,
        time: Long
    ): List<Entry> {
        val entry = Entry(title.ifBlank { url }, url, time)
        val withoutDuplicate = existing.filter { it.url != url }
        return (listOf(entry) + withoutDuplicate).take(MAX_ENTRIES)
    }

    /** 解析。宽容优先：坏数据不该让整份历史消失 */
    internal fun parse(json: String): List<Entry> {
        val arr = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(
                title = obj.optString("title").ifBlank { url },
                url = url,
                time = obj.optLong("time", 0L)
            )
        }
    }

    internal fun serialize(list: List<Entry>): String {
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
}
