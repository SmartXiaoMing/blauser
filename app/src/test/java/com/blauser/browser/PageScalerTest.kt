package com.blauser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩放脚本的生成。
 *
 * 只测「生成出来的脚本长什么样」，不测它在浏览器里的效果 ——
 * 后者要在真机上看（见 README 的调试技巧）。
 */
class PageScalerTest {

    @Test
    fun `跟随手机且没设语言时什么都不注入`() {
        assertNull(PageScaler.scriptFor(SettingsManager.WIDTH_FOLLOW_DEVICE, null))
        assertNull(PageScaler.scriptFor(SettingsManager.WIDTH_FOLLOW_DEVICE, ""))
        assertNull(PageScaler.scriptFor(SettingsManager.WIDTH_FOLLOW_DEVICE, "   "))
    }

    @Test
    fun `跟随手机但设了语言时只注入语言伪装`() {
        val script = PageScaler.scriptFor(SettingsManager.WIDTH_FOLLOW_DEVICE, "en-US")
        assertNotNull(script)
        assertTrue(script!!.contains("navigator"))
        // 不该碰 viewport —— 这个档位的语义就是完全不干预宽度
        assertFalse(script.contains("viewport"))
    }

    @Test
    fun `自动模式挂在 DOMContentLoaded 上`() {
        val script = PageScaler.scriptFor(SettingsManager.WIDTH_AUTO, null)
        assertNotNull(script)
        // 挂在 DOMContentLoaded 而不是 onPageFinished，是为了减少重排闪烁
        assertTrue(script!!.contains("DOMContentLoaded"))
        assertTrue(script.contains(SettingsManager.AUTO_PC_WIDTH.toString()))
        // 页面自己声明了 device-width 就要尊重它
        assertTrue(script.contains("device-width"))
    }

    @Test
    fun `固定宽度模式把宽度写进脚本`() {
        val script = PageScaler.scriptFor(800, null)
        assertNotNull(script)
        assertTrue(script!!.contains("var W = 800"))
        assertTrue(script.contains("viewport"))
    }

    @Test
    fun `固定宽度模式同时伪装 screen`() {
        val script = PageScaler.scriptFor(1024, null)!!
        assertTrue(script.contains("screen"))
        assertTrue(script.contains("availWidth"))
    }

    @Test
    fun `固定宽度加语言时两段都在`() {
        val script = PageScaler.scriptFor(800, "ja-JP")!!
        assertTrue(script.contains("var W = 800"))
        assertTrue(script.contains("ja-JP"))
    }

    @Test
    fun `每次注入都重新读取真实屏宽`() {
        // onPageStarted 和 onPageFinished 会各注入一次，第二次时 screen.width 已经是
        // 伪装值了，必须靠缓存取真实值，否则 scale 会算成 1 把页面放大
        val script = PageScaler.scriptFor(1280, null)!!
        assertTrue(script.contains("__blauserRealW"))
    }

    @Test
    fun `语言里的引号被转义不会破坏脚本`() {
        // 语言是用户手填的，直接拼进 JS 字符串会让脚本语法错误甚至注入
        val script = PageScaler.scriptFor(SettingsManager.WIDTH_FOLLOW_DEVICE, "a\"b\\c")
        assertNotNull(script)
        assertTrue(script!!.contains("""a\"b\\c"""))
    }

    @Test
    fun `自动模式只挂钩一次`() {
        // 两次注入（onPageStarted / onPageFinished）不能让 decide 跑两遍
        val script = PageScaler.scriptFor(SettingsManager.WIDTH_AUTO, null)!!
        assertTrue(script.contains("__blauserAutoHooked"))
    }
}
