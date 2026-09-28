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

    // ==================== 自定义分辨率 ====================

    @Test
    fun `非法尺寸会被夹到合理范围`() {
        // 正常值原样保留
        assertEquals(1280, SettingsManager.sanitizeSize(1280, 1280))
        assertEquals(5000, SettingsManager.sanitizeSize(5000, 1280))
        // 太小 / 非正数 → 回落。0 或负数会让页面直接白屏
        assertEquals(1280, SettingsManager.sanitizeSize(0, 1280))
        assertEquals(1280, SettingsManager.sanitizeSize(-5, 1280))
        // 大得离谱（比如把高度当宽度填了）也回落
        assertEquals(1280, SettingsManager.sanitizeSize(999_999, 1280))
    }

    @Test
    fun `高度允许为 0 表示不限高`() {
        // 高度 0 是合法值（不限制高度），不能像宽度那样被当成非法值顶掉
        assertEquals(0, SettingsManager.sanitizeSize(0, 0))
        assertEquals(720, SettingsManager.sanitizeSize(720, 0))
    }

    @Test
    fun `预设档位都带高度说明但只按宽度渲染`() {
        // 标签里写了 W × H 只是说明性的；渲染只取宽度，
        // 所以这里核对的是「宽度全都不同」，避免两档实际效果相同
        val presetWidths = SettingsManager.WIDTH_OPTIONS
            .filter { it.value > 0 }
            .map { it.value }
        assertEquals(presetWidths.size, presetWidths.distinct().size)
    }

    @Test
    fun `自定义哨兵不和任何预设档撞车`() {
        assertTrue(SettingsManager.WIDTH_OPTIONS.none { it.value == SettingsManager.WIDTH_CUSTOM })
    }

    // ==================== 搜索引擎 ====================

    @Test
    fun `搜索引擎模板恰好一个占位符`() {
        SettingsManager.SEARCH_ENGINES.forEach { engine ->
            val placeholders = engine.template.split("%s").size - 1
            assertEquals("${engine.key} 的模板", 1, placeholders)
        }
    }

    @Test
    fun `默认搜索引擎是百度`() {
        // getSearchEngine 的兜底值取的是列表第一项，两者必须一致，
        // 否则用户没选过时下拉会显示 Google 而实际用百度
        assertEquals(SettingsManager.SEARCH_BAIDU, SettingsManager.SEARCH_ENGINES.first().key)
    }

    @Test
    fun `搜索引擎 key 不重复`() {
        val keys = SettingsManager.SEARCH_ENGINES.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
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
