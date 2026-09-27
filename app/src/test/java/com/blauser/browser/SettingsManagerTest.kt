package com.blauser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsManagerTest {

    // ==================== UserAgent 解析 ====================

    @Test
    fun `预设 key 取到对应 UA`() {
        assertTrue(SettingsManager.uaValueOf(SettingsManager.UA_ANDROID).contains("Android"))
        assertTrue(SettingsManager.uaValueOf(SettingsManager.UA_DESKTOP).contains("Macintosh"))
        assertTrue(SettingsManager.uaValueOf(SettingsManager.UA_IPHONE).contains("iPhone"))
    }

    @Test
    fun `自定义 UA 原样返回`() {
        assertEquals("my-ua/1.0", SettingsManager.uaValueOf(SettingsManager.UA_CUSTOM, "my-ua/1.0"))
    }

    @Test
    fun `自定义为空时回落而不是把 UA 设成空串`() {
        val fallback = SettingsManager.uaValueOf(SettingsManager.UA_ANDROID)
        assertEquals(fallback, SettingsManager.uaValueOf(SettingsManager.UA_CUSTOM, ""))
        assertEquals(fallback, SettingsManager.uaValueOf(SettingsManager.UA_CUSTOM, "   "))
        assertEquals(fallback, SettingsManager.uaValueOf(SettingsManager.UA_CUSTOM))
    }

    @Test
    fun `未知 key 回落到安卓默认`() {
        assertEquals(
            SettingsManager.uaValueOf(SettingsManager.UA_ANDROID),
            SettingsManager.uaValueOf("不存在的 key")
        )
    }

    @Test
    fun `任何情况下都不会返回空 UA`() {
        // UA 设成空串会让很多站点直接拒绝服务，这里钉住兜底
        val keys = SettingsManager.UA_PRESETS.map { it.key } + listOf("", "???")
        keys.forEach { key ->
            assertNotEquals("key=$key", "", SettingsManager.uaValueOf(key, ""))
        }
    }

    // ==================== 选项表 ====================

    @Test
    fun `UA 预设的 key 不重复`() {
        val keys = SettingsManager.UA_PRESETS.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `宽度选项的值不重复`() {
        val values = SettingsManager.WIDTH_OPTIONS.map { it.value }
        assertEquals(values.size, values.distinct().size)
    }

    @Test
    fun `宽度候选里包含默认值和跟随手机`() {
        val values = SettingsManager.WIDTH_OPTIONS.map { it.value }
        assertTrue(values.contains(SettingsManager.DEFAULT_PAGE_WIDTH))
        assertTrue(values.contains(SettingsManager.WIDTH_FOLLOW_DEVICE))
    }

    @Test
    fun `方向选项的值不重复`() {
        val values = SettingsManager.ORIENTATION_OPTIONS.map { it.value }
        assertEquals(values.size, values.distinct().size)
    }

    @Test
    fun `哨兵值与真实档位不会撞车`() {
        // WIDTH_AUTO(-1) 和 WIDTH_FOLLOW_DEVICE(0) 是「不按固定宽度渲染」的哨兵值，
        // PageScaler 的 when 分支靠它们区分处理方式。真实档位必须是正数、
        // 且不能等于任何一个哨兵，否则那个档位会被当成「自动」或「跟随手机」。
        val real = SettingsManager.WIDTH_OPTIONS.filter { it.value > 0 }.map { it.value }
        assertTrue("至少得有一个真实档位", real.isNotEmpty())
        assertEquals("真实档位不能重复", real.size, real.distinct().size)
        assertTrue(real.none { it == SettingsManager.WIDTH_AUTO })
        assertTrue(real.none { it == SettingsManager.WIDTH_FOLLOW_DEVICE })

        // 两个哨兵本身也得能区分，且各自只出现一次
        assertNotEquals(SettingsManager.WIDTH_AUTO, SettingsManager.WIDTH_FOLLOW_DEVICE)
        assertEquals(
            1,
            SettingsManager.WIDTH_OPTIONS.count { it.value == SettingsManager.WIDTH_AUTO }
        )
        assertEquals(
            1,
            SettingsManager.WIDTH_OPTIONS.count { it.value == SettingsManager.WIDTH_FOLLOW_DEVICE }
        )
    }
}
