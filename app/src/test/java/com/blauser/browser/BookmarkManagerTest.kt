package com.blauser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏夹的 JSON 读写。
 *
 * 重点测「坏数据不能让整个收藏夹消失」：这份数据是从旧版本迁移过来的，
 * 历史格式里没有 time 字段、也可能混进空条目。
 */
class BookmarkManagerTest {

    @Test
    fun `空与非法输入都返回空列表而不是抛异常`() {
        assertTrue(BookmarkManager.parse("[]").isEmpty())
        assertTrue(BookmarkManager.parse("").isEmpty())
        assertTrue(BookmarkManager.parse("not json at all").isEmpty())
        assertTrue(BookmarkManager.parse("""{"a":1}""").isEmpty())  // 对象而不是数组
    }

    @Test
    fun `正常条目字段完整`() {
        val list = BookmarkManager.parse(
            """[{"title":"标题","url":"https://a.com","time":123}]"""
        )
        assertEquals(1, list.size)
        assertEquals("标题", list[0].title)
        assertEquals("https://a.com", list[0].url)
        assertEquals(123L, list[0].time)
    }

    @Test
    fun `缺 time 字段回落到 0 而不是抛异常`() {
        // 旧版本的收藏没有 time 字段，用 getLong 而非 optLong 会直接抛 JSONException
        val list = BookmarkManager.parse("""[{"title":"t","url":"https://a.com"}]""")
        assertEquals(1, list.size)
        assertEquals(0L, list[0].time)
    }

    @Test
    fun `缺标题时用网址兜底`() {
        val list = BookmarkManager.parse("""[{"url":"https://a.com"}]""")
        assertEquals("https://a.com", list[0].title)
    }

    @Test
    fun `空标题也用网址兜底`() {
        val list = BookmarkManager.parse("""[{"title":"","url":"https://a.com"}]""")
        assertEquals("https://a.com", list[0].title)
    }

    @Test
    fun `没有网址的条目被跳过其余保留`() {
        val list = BookmarkManager.parse(
            """[{"title":"坏条目"},{"title":"好条目","url":"https://ok.com"}]"""
        )
        assertEquals(1, list.size)
        assertEquals("https://ok.com", list[0].url)
    }

    @Test
    fun `序列化再解析能还原`() {
        val original = listOf(
            Bookmark("一号", "https://a.com", 1L),
            Bookmark("二号", "https://b.com/x?y=1", 2L)
        )
        val restored = BookmarkManager.parse(BookmarkManager.serialize(original))
        assertEquals(original, restored)
    }

    @Test
    fun `序列化结果能被解析为空列表`() {
        assertTrue(BookmarkManager.parse(BookmarkManager.serialize(emptyList())).isEmpty())
    }
}
