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

    // ==================== 虚拟屏幕模式（自定义分辨率填了高）====================

    @Test
    fun `虚拟屏幕模式把宽度锁死为设定值`() {
        val script = PageScaler.scriptFor(1280, null, pageHeight = 720, scale = 0.32f)!!
        assertTrue(script.contains("var W = 1280"))
        assertTrue(script.contains("initial-scale=0.320000"))
        // 不该按文档实际宽度扩张 —— 那是 forceScript 的行为，虚拟屏模式的意义就是定宽
        assertFalse(script.contains("scrollWidth"))
    }

    @Test
    fun `虚拟屏幕模式缺高度或缺缩放比就退回普通模式`() {
        // 有高度但没缩放比（视图还没量出来）
        val a = PageScaler.scriptFor(1280, null, pageHeight = 720, scale = null)!!
        assertTrue("应有文档宽度扩张逻辑", a.contains("scrollWidth"))

        // 有缩放比但没高度（预设档就是这个组合）
        val b = PageScaler.scriptFor(1280, null, pageHeight = 0, scale = 0.3f)!!
        assertTrue("应有文档宽度扩张逻辑", b.contains("scrollWidth"))
    }

    @Test
    fun `缩放比不会写成科学计数法`() {
        // Float.toString 对极小值会吐 "1.2E-4"，那是非法的 CSS 数值
        val script = PageScaler.scriptFor(1280, null, pageHeight = 720, scale = 0.000123456f)!!
        assertFalse(script, script.contains("E-"))
        assertFalse(script, script.contains("e-"))
        assertTrue(script.contains("initial-scale=0.000123"))
    }

    @Test
    fun `虚拟屏幕模式同样伪装 screen width`() {
        val script = PageScaler.scriptFor(1280, null, pageHeight = 720, scale = 0.32f)!!
        assertTrue(script.contains("screen"))
        assertTrue(script.contains("availWidth"))
    }

    @Test
    fun `虚拟屏幕模式下语言伪装也生效`() {
        val script = PageScaler.scriptFor(1280, "ja-JP", pageHeight = 720, scale = 0.32f)!!
        assertTrue(script.contains("ja-JP"))
        assertTrue(script.contains("initial-scale=0.320000"))
    }

    // ==================== 缩放开关 ====================

    @Test
    fun `禁止缩放时三种模式都写 user-scalable=no`() {
        // 固定宽度模式
        val forced = PageScaler.scriptFor(1280, null, userScalable = false)!!
        assertTrue("固定宽度模式", forced.contains("user-scalable=no"))

        // 自动模式
        val auto = PageScaler.scriptFor(SettingsManager.WIDTH_AUTO, null, userScalable = false)!!
        assertTrue("自动模式", auto.contains("user-scalable=no"))

        // 虚拟屏幕模式
        val virtual = PageScaler.scriptFor(
            1280, null, pageHeight = 720, scale = 0.3f, userScalable = false
        )!!
        assertTrue("虚拟屏幕模式", virtual.contains("user-scalable=no"))
    }

    @Test
    fun `允许缩放时写 user-scalable=yes`() {
        val script = PageScaler.scriptFor(1280, null, userScalable = true)!!
        assertTrue(script.contains("user-scalable=yes"))
    }

    @Test
    fun `缩放开关不影响 initial-scale`() {
        // 关掉用户缩放不能连我们自己的整体缩放一起关掉 ——
        // maximum-scale 保持 5.0，initial-scale 照常写
        val script = PageScaler.scriptFor(
            1280, null, pageHeight = 720, scale = 0.32f, userScalable = false
        )!!
        assertTrue(script.contains("initial-scale=0.320000"))
        assertTrue(script.contains("maximum-scale=5.0"))
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
