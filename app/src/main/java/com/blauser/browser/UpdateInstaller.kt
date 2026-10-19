package com.blauser.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 校验与系统安装。
 *
 * 签名是否与当前应用一致不在这里判断 —— 系统安装器覆盖安装时会强制校验，
 * 签名不符会直接拒绝。这里只挡「下错包 / 包损坏 / 降级」这类能在提交给
 * 安装器之前发现的问题。
 */
object UpdateInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * 下载完成后、调起安装器之前校验：
     * 包名是当前应用、versionName 与 release 一致、versionCode 高于已安装版本。
     */
    fun validateApk(context: Context, file: File, expectedVersion: String): Boolean {
        if (!file.isFile || file.length() <= 0) return false

        val archive = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return false

        val installed = runCatching {
            packageInfo(context, context.packageName)
        }.getOrNull() ?: return false

        return isExpectedApk(
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = versionCodeOf(archive),
            expectedPackage = context.packageName,
            expectedVersion = expectedVersion,
            installedVersionCode = versionCodeOf(installed)
        )
    }

    /** 纯函数，便于单测 */
    internal fun isExpectedApk(
        packageName: String?,
        versionName: String?,
        versionCode: Long,
        expectedPackage: String,
        expectedVersion: String,
        installedVersionCode: Long
    ): Boolean {
        if (packageName != expectedPackage) return false

        val apkVersion = UpdateChecker.normalizeVersion(versionName) ?: return false
        val wanted = UpdateChecker.normalizeVersion(expectedVersion) ?: return false
        if (apkVersion != wanted) return false

        return versionCode > installedVersionCode
    }

    internal fun authorityFor(packageName: String): String = "$packageName.fileprovider"

    /** API 26+ 的「安装未知应用」是按应用授权的；更低版本没有这个开关 */
    fun needsUnknownSourcesPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    /**
     * 调用方只在 [needsUnknownSourcesPermission] 为 true（API 26+）时才会用到它。
     * lint 只看到常量被内联进来，会误以为低版本也会执行；这里显式说明。
     */
    @SuppressLint("InlinedApi")
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * 调起系统安装器。API 24+ 禁止把 file:// 放进 Intent，必须走 FileProvider 的
     * content://，否则会抛 FileUriExposedException。
     */
    fun install(activity: Activity, file: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(activity, authorityFor(activity.packageName), file)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 部分 OEM 安装器只认 ClipData 里的 URI 授权
            clipData = ClipData.newRawUri("apk", uri)
        }

        return try {
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun packageInfo(context: Context, packageName: String): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
