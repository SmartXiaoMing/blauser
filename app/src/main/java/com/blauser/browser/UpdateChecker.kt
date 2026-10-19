package com.blauser.browser

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 检查 GitHub Releases 是否有更新。
 *
 * 版本比较、JSON 解析、按天节流都做成纯函数，JVM 单测直接覆盖（见 UpdateCheckerTest）。
 * 网络请求用 HttpURLConnection，项目刻意不为此引入 OkHttp / 协程。
 *
 * 当前发布约定：tag 为 `v<versionName>`，APK asset 为 `blauser-<versionName>.apk`。
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/SmartXiaoMing/blauser/releases/latest"
    private const val TAG = "UpdateChecker"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 自动检查状态。它不是用户设置，单独存一个文件，别混进 browser_settings */
    private const val PREF = "update_state"
    private const val KEY_LAST_DAY = "last_auto_check_day"
    private const val KEY_SKIPPED = "skipped_version"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_JSON_CHARS = 1_000_000

    data class ReleaseInfo(
        /** 规范化后的版本号，如 "1.0.8" */
        val version: String,
        /** 原始 tag，如 "v1.0.8" */
        val tagName: String,
        val notes: String,
        val apkUrl: String,
        /** 字节数；未知为 -1 */
        val apkSize: Long,
        /** GitHub asset 的 sha256 digest；没有则为 null */
        val sha256: String?
    )

    sealed class CheckResult {
        data class Update(val release: ReleaseInfo, val currentVersion: String) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class Failed(val reason: FailureReason) : CheckResult()
    }

    enum class FailureReason { NETWORK, NO_APK, INVALID_RELEASE, INVALID_LOCAL_VERSION }

    internal sealed class ParsedRelease {
        data class Ok(val release: ReleaseInfo) : ParsedRelease()
        object NoApk : ParsedRelease()
        object Invalid : ParsedRelease()
    }

    /**
     * 进程级去重：本 App 一个标签页一个 MainActivity，多个标签同时启动时
     * 只允许其中一个发起当天第一次自动检查。
     */
    @Volatile
    private var autoAttemptedThisProcess = false

    // ==================== 纯函数（可单测） ====================

    /**
     * 规范化版本号：去掉可选的 v 前缀和每段的前导零。
     * 只接受「纯数字 + 点」的形式；`1.0.8-beta`、`nightly` 这类返回 null。
     */
    internal fun normalizeVersion(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        val withoutPrefix =
            if (trimmed.startsWith("v", ignoreCase = true)) trimmed.substring(1) else trimmed
        if (withoutPrefix.isEmpty()) return null

        val parts = withoutPrefix.split('.')
        if (parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) return null
        return parts.joinToString(".") { it.trimStart('0').ifEmpty { "0" } }
    }

    /**
     * 比较两个版本号。a > b 返回正数，相等返回 0，无法解析返回 null。
     * 缺失段按 0 处理（1.0 == 1.0.0），段内按数值而非字符串比较（1.0.10 > 1.0.9）。
     */
    internal fun compareVersions(a: String?, b: String?): Int? {
        val left = normalizeVersion(a)?.split('.') ?: return null
        val right = normalizeVersion(b)?.split('.') ?: return null

        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { "0" }
            val r = right.getOrElse(i) { "0" }
            if (l != r) {
                // 去掉前导零后，位数多的数值更大；位数相同再按字典序
                return if (l.length != r.length) l.length.compareTo(r.length) else l.compareTo(r)
            }
        }
        return 0
    }

    /** GitHub 下载地址必须是 https，否则下载层直接拒绝 */
    internal fun isHttpsUrl(url: String?): Boolean =
        url?.trim()?.startsWith("https://", ignoreCase = true) == true

    internal fun apkFileName(version: String): String = "blauser-$version.apk"

    internal fun isAutoCheckDue(lastDay: String?, today: String): Boolean =
        lastDay.isNullOrBlank() || lastDay != today

    internal fun shouldAutoPrompt(version: String, skippedVersion: String?): Boolean =
        normalizeVersion(version) != normalizeVersion(skippedVersion)

    /** 本地时区的日历日，不能用 UTC 毫秒数做除法（跨时区会错一天） */
    internal fun localDay(nowMillis: Long, timeZone: TimeZone): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = timeZone
        return format.format(Date(nowMillis))
    }

    /**
     * 解析 `/releases/latest` 的 JSON。
     *
     * 宽容但守住底线：MIME / 名称不符合、下载地址不是 https 的 asset 一律跳过；
     * 没有可用 APK 与 JSON 本身无效是两种不同结果，手动检查时提示也不一样。
     */
    internal fun parseRelease(json: String): ParsedRelease {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return ParsedRelease.Invalid

        // /latest 正常不会返回草稿或预发布；这里防御性拒绝，免得把测试版当成正式更新
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            return ParsedRelease.Invalid
        }

        val tag = if (root.isNull("tag_name")) null else root.optString("tag_name")
        val version = normalizeVersion(tag) ?: return ParsedRelease.Invalid
        val notes = if (root.isNull("body")) "" else root.optString("body").orEmpty()

        val assets = root.optJSONArray("assets") ?: JSONArray()
        val preferredName = apkFileName(version)
        var best: JSONObject? = null
        var bestScore = 0

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.isNull("state") && asset.optString("state") != "uploaded") continue

            val name = if (asset.isNull("name")) "" else asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue

            val url = if (asset.isNull("browser_download_url")) {
                ""
            } else {
                asset.optString("browser_download_url")
            }
            if (!isHttpsUrl(url)) continue

            // 优先与约定文件名完全一致的 asset，其次是 APK MIME，最后是所有 .apk
            val score = when {
                name.equals(preferredName, ignoreCase = true) -> 3
                asset.optString("content_type") == APK_MIME -> 2
                else -> 1
            }
            if (score > bestScore) {
                best = asset
                bestScore = score
            }
        }

        val asset = best ?: return ParsedRelease.NoApk
        val url = asset.optString("browser_download_url")
        val size = asset.optLong("size", -1L)
        val sha256 = parseSha256(if (asset.isNull("digest")) null else asset.optString("digest"))

        return ParsedRelease.Ok(
            ReleaseInfo(
                version = version,
                tagName = tag.orEmpty(),
                notes = notes,
                apkUrl = url,
                apkSize = size,
                sha256 = sha256
            )
        )
    }

    /** GitHub 的 digest 形如 "sha256:abc..."。只接受合法的 64 位十六进制 */
    private fun parseSha256(digest: String?): String? {
        val value = digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':') ?: return null
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return null
        }
        return value.lowercase(Locale.US)
    }

    // ==================== Android / 网络 ====================

    fun currentVersionName(context: Context): String? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    }.getOrNull()

    internal fun userAgent(context: Context): String =
        "blauser/${currentVersionName(context) ?: "unknown"}"

    /** 后台请求 + 回主线程。手动检查与自动检查共用 */
    fun check(context: Context, callback: (CheckResult) -> Unit) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = runCheck(appContext)
            main.post { callback(result) }
        }, "update-check").start()
    }

    private fun runCheck(context: Context): CheckResult {
        val currentRaw = currentVersionName(context)
        val current = normalizeVersion(currentRaw)
            ?: return CheckResult.Failed(FailureReason.INVALID_LOCAL_VERSION)

        val body = try {
            fetch(API_URL, context)
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            return CheckResult.Failed(FailureReason.NETWORK)
        }

        return when (val parsed = parseRelease(body)) {
            ParsedRelease.NoApk -> CheckResult.Failed(FailureReason.NO_APK)
            ParsedRelease.Invalid -> CheckResult.Failed(FailureReason.INVALID_RELEASE)
            is ParsedRelease.Ok -> {
                val cmp = compareVersions(parsed.release.version, current)
                    ?: return CheckResult.Failed(FailureReason.INVALID_RELEASE)
                if (cmp > 0) {
                    CheckResult.Update(parsed.release, currentRaw ?: current)
                } else {
                    CheckResult.UpToDate(currentRaw ?: current)
                }
            }
        }
    }

    private fun fetch(url: String, context: Context): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent(context))
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("Accept-Encoding", "identity")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return readLimited(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(connection: HttpURLConnection): String {
        // contentLength 是 int，API 21 可用；JSON 只有几十 KB，足够
        if (connection.contentLength > MAX_JSON_CHARS) throw IOException("response too large")
        val builder = StringBuilder()
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(8 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                builder.append(buffer, 0, count)
                if (builder.length > MAX_JSON_CHARS) throw IOException("response too large")
            }
        }
        return builder.toString()
    }

    // ==================== 自动检查 / 跳过版本 ====================

    /**
     * 每天最多静默检查一次。
     *
     * 「尝试即记录」：请求失败也算今天的自动检查用掉了，避免每次启动都重试；
     * 手动检查不受影响。无痕标签不写状态，也不占用当天的名额。
     */
    fun maybeAutoCheck(
        activity: Activity,
        freshLaunch: Boolean,
        incognito: Boolean,
        onUpdate: (ReleaseInfo, String) -> Unit
    ) {
        if (!freshLaunch || incognito) return
        val appContext = activity.applicationContext

        synchronized(this) {
            if (autoAttemptedThisProcess) return
            val today = localDay(System.currentTimeMillis(), TimeZone.getDefault())
            if (!isAutoCheckDue(prefs(appContext).getString(KEY_LAST_DAY, null), today)) return
            prefs(appContext).edit().putString(KEY_LAST_DAY, today).apply()
            autoAttemptedThisProcess = true
        }

        check(appContext) { result ->
            if (result !is CheckResult.Update) return@check
            if (activity.isFinishing || activity.isDestroyed) return@check
            val skipped = prefs(appContext).getString(KEY_SKIPPED, null)
            if (!shouldAutoPrompt(result.release.version, skipped)) return@check
            onUpdate(result.release, result.currentVersion)
        }
    }

    /** 用户点「稍后」/ 关闭弹窗后，自动检查不再为同一版本打扰 */
    fun markSkipped(context: Context, version: String) {
        val normalized = normalizeVersion(version) ?: return
        prefs(context.applicationContext).edit().putString(KEY_SKIPPED, normalized).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
