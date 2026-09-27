package com.blauser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览历史的核心逻辑：去重置顶 + 上限截断 + 坏数据容错。
 *
 * 去重和截断是最容易写错的两件事 —— 顺序搞反会让刚访问的页面反而是第一个被丢掉的。
 */
class HistoryManagerTest {

    private fun entry(url: String, title: String = url, time: Long = 0L) =
        HistoryManager.Entry(title, url, time)

    // ==================== merge：去重置顶 ====================

    @Test
    fun `新记录插到最前面`() {
        val existing = listOf(entry("https://a.com"), entry("https://b.com"))
        val merged = HistoryManager.merge(existing, "C", "https://c.com", 3L)
        assertEquals(
            listOf("https://c.com", "https://a.com", "https://b.com"),
            merged.map { it.url }
        )
    }

    @Test
    fun `重复访问只保留一条并置顶`() {
        val existing = listOf(
            entry("https://a.com", time = 1L),
            entry("https://b.com", time = 2L),
            entry("https://c.com", time = 3L)
        )
        val merged = HistoryManager.merge(existing, "A 新标题", "https://a.com", 9L)

        assertEquals(3, merged.size)
        assertEquals("https://a.com", merged[0].url)
        assertEquals("A 新标题", merged[0].title)
        assertEquals(9L, merged[0].time)
        // 其余顺序不变
        assertEquals(listOf("https://b.com", "https://c.com"), merged.drop(1).map { it.url })
    }

    @Test
    fun `空标题用网址兜底`() {
        val merged = HistoryManager.merge(emptyList(), "", "https://a.com", 1L)
        assertEquals("https://a.com", merged[0].title)
        val blank = HistoryManager.merge(emptyList(), "   ", "https://a.com", 1L)
        assertEquals("https://a.com", blank[0].title)
    }

    @Test
    fun `超出上限时丢掉最旧的`() {
        // 列表约定是「新的在前」，所以末尾那条才是最旧的。
        // 这里让 old500 最新（排最前）、old1 最旧（排最后），读起来才和真实数据一致
        val existing = (1..HistoryManager.MAX_ENTRIES)
            .sortedDescending()
            .map { entry("https://old$it.com", time = it.toLong()) }
        val merged = HistoryManager.merge(existing, "新", "https://new.com", 999L)

        assertEquals(HistoryManager.MAX_ENTRIES, merged.size)
        assertEquals("https://new.com", merged.first().url)
        // 被挤掉的是最旧的 old1，而不是刚插进来的那条
        assertTrue(merged.none { it.url == "https://old1.com" })
        assertTrue(merged.any { it.url == "https://old${HistoryManager.MAX_ENTRIES}.com" })
    }

    @Test
    fun `对已满列表重复访问老条目也不会丢新内容`() {
        val existing = (1..HistoryManager.MAX_ENTRIES)
            .sortedDescending()
            .map { entry("https://old$it.com", time = it.toLong()) }
        // 访问一个已经在列表里的老网址：应该把它挪到最前，总数不变
        val merged = HistoryManager.merge(existing, "复访", "https://old5.com", 999L)

        assertEquals(HistoryManager.MAX_ENTRIES, merged.size)
        assertEquals("https://old5.com", merged.first().url)
        assertEquals("复访", merged.first().title)
    }

    // ==================== parse ====================

    @Test
    fun `空与非法输入返回空列表`() {
        assertTrue(HistoryManager.parse("[]").isEmpty())
        assertTrue(HistoryManager.parse("").isEmpty())
        assertTrue(HistoryManager.parse("not json").isEmpty())
        assertTrue(HistoryManager.parse("""{"a":1}""").isEmpty())
    }

    @Test
    fun `缺 time 字段回落到 0`() {
        val list = HistoryManager.parse("""[{"title":"t","url":"https://a.com"}]""")
        assertEquals(1, list.size)
        assertEquals(0L, list[0].time)
    }

    @Test
    fun `没有网址的条目被跳过其余保留`() {
        val list = HistoryManager.parse(
            """[{"title":"坏"},{"title":"好","url":"https://ok.com"}]"""
        )
        assertEquals(1, list.size)
        assertEquals("https://ok.com", list[0].url)
    }

    @Test
    fun `序列化再解析能还原`() {
        val original = listOf(
            entry("https://a.com", "一号", 1L),
            entry("https://b.com/x?y=1", "二号", 2L)
        )
        assertEquals(original, HistoryManager.parse(HistoryManager.serialize(original)))
    }
}
