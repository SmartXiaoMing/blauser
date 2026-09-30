package com.blauser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 域名归一化。
 *
 * 它是「接受一次之后不再问」能成立的前提：同一个站点存成两条 key，用户就会遇到
 * 「明明接受过了，怎么又问一次」—— 而且翻设置面板还看不出问题（两条长得一样）。
 *
 * 实现刻意不依赖 android.net.Uri（单测跑在 JVM 上，那是返回默认值的桩），
 * 所以这些用例能直接跑。
 */
class SslExceptionStoreTest {

    // ==================== 归一化的三件事 ====================

    @Test
    fun `大小写归一`() {
        assertEquals("example.com", SslExceptionStore.normalizeHost("Example.COM"))
        assertEquals("example.com", SslExceptionStore.normalizeHost("EXAMPLE.com"))
    }

    @Test
    fun `末尾的点去掉`() {
        // "example.com." 是同一个域名的根域写法，DNS 上完全等价
        assertEquals("example.com", SslExceptionStore.normalizeHost("example.com."))
        assertEquals("example.com", SslExceptionStore.normalizeHost("Example.com.."))
    }

    @Test
    fun `前后空白去掉`() {
        assertEquals("example.com", SslExceptionStore.normalizeHost("  example.com\n"))
    }

    // ==================== 不做的事 ====================

    @Test
    fun `只归一化不校验，少见但合法的主机名要留住`() {
        // 内网单标签主机名、IPv6 字面量都不是「有点的域名」，但完全合法。
        // 校验是 SiteSettingsManager.hostOf() 的职责，这里越权只会误伤它们
        assertEquals("nas", SslExceptionStore.normalizeHost("NAS"))
        assertEquals("[::1]", SslExceptionStore.normalizeHost("[::1]"))
    }

    @Test
    fun `归一化幂等`() {
        val once = SslExceptionStore.normalizeHost("  Example.COM.  ")
        assertEquals(once, SslExceptionStore.normalizeHost(once))
    }

    // ==================== 空值 ====================

    @Test
    fun `空值一律返回 null 而不是空串`() {
        // 空串会变成一个「所有取不到域名的请求都命中」的 key —— 那等于无条件放行
        assertNull(SslExceptionStore.normalizeHost(null))
        assertNull(SslExceptionStore.normalizeHost(""))
        assertNull(SslExceptionStore.normalizeHost("   "))
        assertNull(SslExceptionStore.normalizeHost("."))
        assertNull(SslExceptionStore.normalizeHost("..."))
    }
}
