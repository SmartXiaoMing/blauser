package com.blauser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装前的 APK 校验。
 *
 * 这里挡的是「下错包 / 版本不对 / 降级」；签名是否一致由系统安装器负责。
 */
class UpdateInstallerTest {

    @Test
    fun `包名和版本匹配且版本码更高时通过`() {
        assertTrue(
            UpdateInstaller.isExpectedApk(
                packageName = "com.blauser.browser",
                versionName = "1.0.8",
                versionCode = 9,
                expectedPackage = "com.blauser.browser",
                expectedVersion = "1.0.8",
                installedVersionCode = 8
            )
        )
    }

    @Test
    fun `版本名带 v 前缀也能匹配`() {
        assertTrue(
            UpdateInstaller.isExpectedApk(
                packageName = "com.blauser.browser",
                versionName = "1.0.8",
                versionCode = 9,
                expectedPackage = "com.blauser.browser",
                expectedVersion = "v1.0.8",
                installedVersionCode = 8
            )
        )
    }

    @Test
    fun `包名不同时拒绝`() {
        assertFalse(
            UpdateInstaller.isExpectedApk(
                packageName = "com.example.fake",
                versionName = "1.0.8",
                versionCode = 99,
                expectedPackage = "com.blauser.browser",
                expectedVersion = "1.0.8",
                installedVersionCode = 8
            )
        )
    }

    @Test
    fun `版本号不一致时拒绝`() {
        assertFalse(
            UpdateInstaller.isExpectedApk(
                packageName = "com.blauser.browser",
                versionName = "1.0.7",
                versionCode = 9,
                expectedPackage = "com.blauser.browser",
                expectedVersion = "1.0.8",
                installedVersionCode = 8
            )
        )
    }

    @Test
    fun `versionCode 不高于已安装版本时拒绝`() {
        assertFalse(
            UpdateInstaller.isExpectedApk(
                packageName = "com.blauser.browser",
                versionName = "1.0.8",
                versionCode = 8,
                expectedPackage = "com.blauser.browser",
                expectedVersion = "1.0.8",
                installedVersionCode = 8
            )
        )
    }

    @Test
    fun `无法识别 APK 版本时拒绝`() {
        assertFalse(
            UpdateInstaller.isExpectedApk(
                packageName = "com.blauser.browser",
                versionName = "nightly",
                versionCode = 9,
                expectedPackage = "com.blauser.browser",
                expectedVersion = "1.0.8",
                installedVersionCode = 8
            )
        )
    }

    @Test
    fun `FileProvider authority 固定由包名派生`() {
        assertEquals(
            "com.blauser.browser.fileprovider",
            UpdateInstaller.authorityFor("com.blauser.browser")
        )
    }
}
