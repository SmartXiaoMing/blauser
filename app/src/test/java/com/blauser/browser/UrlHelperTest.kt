package com.blauser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏输入解析。这是「用户输入什么 → 加载什么」的分流逻辑，
 * 判错一次要么把搜索词当域名，要么反过来，所以边界都钉死在测试里。
 */
class UrlHelperTest {

    // ==================== smartUrl：已经是网址 ====================

    @Test
    fun `完整网址原样保留`() {
        assertEquals("https://a.com/x", UrlHelper.smartUrl("https://a.com/x"))
        assertEquals("http://a.com/y?z=1", UrlHelper.smartUrl("http://a.com/y?z=1"))
    }

    @Test
    fun `协议大小写不敏感`() {
        assertEquals("HTTPS://A.com", UrlHelper.smartUrl("HTTPS://A.com"))
        assertEquals("Http://a.com", UrlHelper.smartUrl("Http://a.com"))
    }

    // ==================== smartUrl：补全 ====================

    @Test
    fun `像域名的补上 https`() {
        assertEquals("https://a.com", UrlHelper.smartUrl("a.com"))
        assertEquals("https://www.a.com/x/y", UrlHelper.smartUrl("www.a.com/x/y"))
    }

    // ==================== smartUrl：本地 / 内网地址 ====================

    @Test
    fun `localhost 和端口补 http 而不是 https`() {
        // 开发机上几乎不会有 https，补成 https 会一直超时
        assertEquals("http://localhost:3000", UrlHelper.smartUrl("localhost:3000"))
        assertEquals("http://localhost", UrlHelper.smartUrl("localhost"))
        assertEquals("http://127.0.0.1:8080/x", UrlHelper.smartUrl("127.0.0.1:8080/x"))
    }

    @Test
    fun `内网网段补 http`() {
        assertEquals("http://192.168.1.5", UrlHelper.smartUrl("192.168.1.5"))
        assertEquals("http://192.168.1.5:8080", UrlHelper.smartUrl("192.168.1.5:8080"))
        assertEquals("http://10.0.0.2:9000", UrlHelper.smartUrl("10.0.0.2:9000"))
        assertEquals("http://172.16.3.4", UrlHelper.smartUrl("172.16.3.4"))
    }

    @Test
    fun `公网地址加端口的也算地址`() {
        assertEquals("http://example.com:8080", UrlHelper.smartUrl("example.com:8080"))
    }

    @Test
    fun `不像地址的端口写法仍然当搜索词`() {
        // "12:30" 这种会被当成时间，不该变成网址
        assertTrue(UrlHelper.smartUrl("12:30").startsWith("https://www.baidu.com/s?wd="))
    }

    // ==================== smartUrl：当搜索词 ====================

    @Test
    fun `带空格的一律当搜索词`() {
        val result = UrlHelper.smartUrl("hello world")
        assertTrue(result, result.startsWith("https://www.baidu.com/s?wd="))
    }

    @Test
    fun `不含点的当搜索词`() {
        assertTrue(UrlHelper.smartUrl("hello").startsWith("https://www.baidu.com/s?wd="))
        assertTrue(UrlHelper.smartUrl("浏览器").startsWith("https://www.baidu.com/s?wd="))
    }

    @Test
    fun `搜索词会被 URL 编码`() {
        val result = UrlHelper.smartUrl("中文 词")
        assertFalse(result, result.contains(" "))
        assertFalse(result, result.contains("中文"))
    }

    // ==================== smartUrl：本地路径白名单 ====================

    @Test
    fun `android_asset 放行`() {
        val url = "file:///android_asset/demo.html"
        assertEquals(url, UrlHelper.smartUrl(url))
    }

    @Test
    fun `其它 file 路径不放行`() {
        // 放开整个 file scheme 的话，API 30 以下默认 allowFileAccess=true，
        // 输入 file:///data/data/... 就能读私有目录。这里钉住这个约束：
        // 一律转成搜索，既不会加载本地文件，也不会拼出一个荒唐的 https://file:///...
        listOf(
            "file:///data/data/com.blauser.browser/x.xml",
            "file:///sdcard/secret.txt",
            "FILE:///etc/hosts"
        ).forEach { input ->
            val result = UrlHelper.smartUrl(input)
            assertFalse(input, result.startsWith("file:", true))
            assertFalse(input, result.startsWith("https://file:"))
            assertTrue(input, result.startsWith("https://www.baidu.com/s?wd="))
        }
    }

    @Test
    fun `about 放行`() {
        assertEquals("about:blank", UrlHelper.smartUrl("about:blank"))
    }

    // ==================== 其它 ====================

    @Test
    fun `searchUrl 用加号表示空格`() {
        assertEquals("https://www.baidu.com/s?wd=hello+world", UrlHelper.searchUrl("hello world"))
    }

    /**
     * isWebUrl 是**安全边界**，不只是格式校验：intent:// 的 browser_fallback_url
     * 完全由网页控制，放行 javascript: 会让它在本页上下文里执行脚本，
     * 放行 file: 能读本地文件。这里把拒绝面钉死。
     */
    @Test
    fun `isWebUrl 只认 http 和 https`() {
        assertTrue(UrlHelper.isWebUrl("https://a.com"))
        assertTrue(UrlHelper.isWebUrl("  http://a.com  "))

        listOf(
            "javascript:alert(document.cookie)",
            "JavaScript:alert(1)",
            "file:///data/data/com.blauser.browser/x.xml",
            "data:text/html,<script>alert(1)</script>",
            "blauser://tab/main",
            "intent://x#Intent;end",
            "content://com.android.contacts/data",
            ""
        ).forEach { assertFalse(it, UrlHelper.isWebUrl(it)) }

        assertFalse(UrlHelper.isWebUrl(null))
    }

    @Test
    fun `isHttpScheme 只认 http 和 https`() {
        assertTrue(UrlHelper.isHttpScheme("http"))
        assertTrue(UrlHelper.isHttpScheme("https"))
        assertFalse(UrlHelper.isHttpScheme("HTTP"))  // 方法本身不做大小写归一
        assertFalse(UrlHelper.isHttpScheme("intent"))
        assertFalse(UrlHelper.isHttpScheme(null))
    }
}
