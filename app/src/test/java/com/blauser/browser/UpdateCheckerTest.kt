package com.blauser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 版本比较与 GitHub Release JSON 解析。
 *
 * 这些函数决定了「要不要弹升级提示」：判错一次要么漏掉更新，要么把旧版本当新版，
 * 所以边界都钉在测试里。纯 JVM 逻辑，不依赖 Android。
 */
class UpdateCheckerTest {

    // ==================== 版本规范化 / 比较 ====================

    @Test
    fun `去掉可选 v 前缀`() {
        assertEquals("1.0.8", UpdateChecker.normalizeVersion("v1.0.8"))
        assertEquals("1.0.8", UpdateChecker.normalizeVersion("V1.0.8"))
        assertEquals("1.0.8", UpdateChecker.normalizeVersion(" 1.0.8 "))
    }

    @Test
    fun `每段前导零归一`() {
        assertEquals("1.0.8", UpdateChecker.normalizeVersion("01.000.008"))
        assertEquals("0", UpdateChecker.normalizeVersion("000"))
    }

    @Test
    fun `不是纯数字段的版本无法识别`() {
        // 预发布后缀 / 日期版本 / 空段都不拿来比较，宁可当作没有更新
        listOf(
            null, "", "   ", "v", "1.0.8-beta", "1.0.8+build", "nightly", "1..2", "1.a.3"
        ).forEach { assertNull(it, UpdateChecker.normalizeVersion(it)) }
    }

    @Test
    fun `按数字段比较而不是按字符串`() {
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9")!! > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.9", "1.0.10")!! < 0)
        assertEquals(0, UpdateChecker.compareVersions("v1.0.8", "1.0.8"))
        assertEquals(0, UpdateChecker.compareVersions("1.00.8", "1.0.8"))
    }

    @Test
    fun `缺失段按零处理`() {
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
        assertTrue(UpdateChecker.compareVersions("2", "1.9.9")!! > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0.1", "1.0")!! > 0)
    }

    @Test
    fun `任一版本无法识别时比较返回 null`() {
        assertNull(UpdateChecker.compareVersions("1.0-beta", "1.0"))
        assertNull(UpdateChecker.compareVersions("1.0", null))
    }

    // ==================== Release JSON ====================

    @Test
    fun `解析正常的 release`() {
        val parsed = UpdateChecker.parseRelease(
            releaseJson(
                tag = "v1.0.8",
                body = "修复了若干问题",
                assets = """
                    [
                      {
                        "name": "blauser-1.0.8.apk",
                        "browser_download_url": "https://github.com/SmartXiaoMing/blauser/releases/download/v1.0.8/blauser-1.0.8.apk",
                        "state": "uploaded",
                        "content_type": "application/vnd.android.package-archive",
                        "size": 1911292,
                        "digest": "sha256:${"a".repeat(64)}"
                      }
                    ]
                """
            )
        )

        assertTrue(parsed is UpdateChecker.ParsedRelease.Ok)
        val release = (parsed as UpdateChecker.ParsedRelease.Ok).release
        assertEquals("1.0.8", release.version)
        assertEquals("v1.0.8", release.tagName)
        assertEquals("修复了若干问题", release.notes)
        assertEquals(1911292L, release.apkSize)
        assertEquals("a".repeat(64), release.sha256)
        assertTrue(release.apkUrl.startsWith("https://"))
    }

    @Test
    fun `优先选择约定的 APK 文件名`() {
        val parsed = UpdateChecker.parseRelease(
            releaseJson(
                assets = """
                    [
                      {
                        "name": "other.apk",
                        "browser_download_url": "https://example.com/other.apk",
                        "content_type": "application/vnd.android.package-archive",
                        "size": 10
                      },
                      {
                        "name": "blauser-1.0.8.apk",
                        "browser_download_url": "https://github.com/SmartXiaoMing/blauser/releases/download/v1.0.8/blauser-1.0.8.apk",
                        "content_type": "application/octet-stream",
                        "size": 20
                      }
                    ]
                """
            )
        )

        val release = (parsed as UpdateChecker.ParsedRelease.Ok).release
        assertEquals(20L, release.apkSize)
        assertTrue(release.apkUrl.endsWith("blauser-1.0.8.apk"))
    }

    @Test
    fun `没有 APK asset 时单独返回 NoApk`() {
        val parsed = UpdateChecker.parseRelease(
            releaseJson(assets = """[{"name": "source.zip", "browser_download_url": "https://example.com/x.zip"}]""")
        )
        assertTrue(parsed is UpdateChecker.ParsedRelease.NoApk)
    }

    @Test
    fun `非 https 的下载地址不被接受`() {
        val parsed = UpdateChecker.parseRelease(
            releaseJson(
                assets = """
                    [{
                      "name": "blauser-1.0.8.apk",
                      "browser_download_url": "http://example.com/blauser-1.0.8.apk",
                      "size": 10
                    }]
                """
            )
        )
        assertTrue(parsed is UpdateChecker.ParsedRelease.NoApk)
    }

    @Test
    fun `draft 和 prerelease 一律拒绝`() {
        assertTrue(UpdateChecker.parseRelease(releaseJson(draft = true)) is UpdateChecker.ParsedRelease.Invalid)
        assertTrue(UpdateChecker.parseRelease(releaseJson(prerelease = true)) is UpdateChecker.ParsedRelease.Invalid)
    }

    @Test
    fun `非法 tag 或缺字段时解析失败`() {
        assertTrue(UpdateChecker.parseRelease(releaseJson(tag = "1.0.8-beta")) is UpdateChecker.ParsedRelease.Invalid)
        assertTrue(UpdateChecker.parseRelease("""{"body":"x"}""") is UpdateChecker.ParsedRelease.Invalid)
        assertTrue(UpdateChecker.parseRelease("not json") is UpdateChecker.ParsedRelease.Invalid)
    }

    @Test
    fun `body 为空时 notes 是空串`() {
        val parsed = UpdateChecker.parseRelease(releaseJson(body = null))
        val release = (parsed as UpdateChecker.ParsedRelease.Ok).release
        assertEquals("", release.notes)
    }

    // ==================== 自动检查节流 / 跳过版本 ====================

    @Test
    fun `同一天不重复自动检查`() {
        assertFalse(UpdateChecker.isAutoCheckDue("2026-10-19", "2026-10-19"))
        assertTrue(UpdateChecker.isAutoCheckDue("2026-10-18", "2026-10-19"))
        assertTrue(UpdateChecker.isAutoCheckDue(null, "2026-10-19"))
        assertTrue(UpdateChecker.isAutoCheckDue("", "2026-10-19"))
    }

    @Test
    fun `localDay 按指定时区计算`() {
        // epoch 0 在 UTC 是 1 月 1 日，在洛杉矶还是 1969-12-31
        assertEquals("1970-01-01", UpdateChecker.localDay(0, TimeZone.getTimeZone("UTC")))
        assertEquals(
            "1969-12-31",
            UpdateChecker.localDay(0, TimeZone.getTimeZone("America/Los_Angeles"))
        )
    }

    @Test
    fun `跳过版本只挡住同一版本`() {
        assertFalse(UpdateChecker.shouldAutoPrompt("1.0.8", "v1.0.8"))
        assertTrue(UpdateChecker.shouldAutoPrompt("1.0.9", "1.0.8"))
        assertTrue(UpdateChecker.shouldAutoPrompt("1.0.8", null))
    }

    @Test
    fun `APK 文件名由版本拼出`() {
        assertEquals("blauser-1.0.8.apk", UpdateChecker.apkFileName("1.0.8"))
    }

    // ==================== 测试数据 ====================

    private fun releaseJson(
        tag: String = "v1.0.8",
        body: String? = "notes",
        assets: String = """
            [{
              "name": "blauser-1.0.8.apk",
              "browser_download_url": "https://github.com/SmartXiaoMing/blauser/releases/download/v1.0.8/blauser-1.0.8.apk",
              "state": "uploaded",
              "content_type": "application/vnd.android.package-archive",
              "size": 100
            }]
        """,
        draft: Boolean = false,
        prerelease: Boolean = false
    ): String {
        val bodyJson = if (body == null) "null" else "\"$body\""
        return """
            {
              "tag_name": "$tag",
              "body": $bodyJson,
              "html_url": "https://github.com/SmartXiaoMing/blauser/releases/tag/$tag",
              "draft": $draft,
              "prerelease": $prerelease,
              "assets": $assets
            }
        """
    }
}
