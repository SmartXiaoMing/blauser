package com.blauser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * 更新包下载器。
 *
 * 刻意不用系统的 DownloadManager：它拿不到「下载完成 → 继续安装」的可靠回调，
 * 也不方便把文件锁在应用私有目录里交给 FileProvider。更新包只有 ~2 MB，
 * 普通线程 + HttpURLConnection 足够，且行为完全可控。
 *
 * 单例单任务：切换标签页不会打断下载。回调全部切回主线程；
 * Activity 销毁时 detach 监听即可，下载可以继续，文件留给下次检查直接安装。
 */
object UpdateDownloader {

    sealed class Event {
        /** total <= 0 表示长度未知 */
        data class Progress(val received: Long, val total: Long) : Event()
        data class Done(val file: File) : Event()
        data class Failed(val reason: Reason) : Event()
        object Cancelled : Event()
    }

    enum class Reason { NETWORK, TOO_LARGE, SIZE_MISMATCH, HASH_MISMATCH }

    fun interface Listener {
        fun onEvent(event: Event)
    }

    private const val TAG = "UpdateDownloader"
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROGRESS_INTERVAL_MS = 200L
    private const val BUFFER_SIZE = 64 * 1024

    /** 100MB 上限：更新包只有 2MB 左右，这个值只防异常响应把手机塞满 */
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var runningVersion: String? = null

    fun isRunning(): Boolean = running

    /** 正在下载的版本；没有任务时为 null */
    fun currentVersion(): String? = runningVersion.takeIf { running }

    fun isHttps(url: String?): Boolean = UpdateChecker.isHttpsUrl(url)

    fun apkFile(context: Context, version: String): File =
        File(downloadDir(context), UpdateChecker.apkFileName(version))

    /** 已有且非空的安装包可以直接走安装，不必重复下载 */
    fun existingApk(context: Context, version: String): File? =
        apkFile(context, version).takeIf { it.isFile && it.length() > 0 }

    /**
     * 开始下载。返回 false 表示已有任务在跑（或 URL 不是 https）。
     * 回调都在主线程；同一任务最多只有一个监听者。
     */
    fun start(context: Context, release: UpdateChecker.ReleaseInfo, listener: Listener): Boolean {
        if (!isHttps(release.apkUrl)) return false

        synchronized(this) {
            if (running) return false
            running = true
            runningVersion = release.version
            cancelRequested = false
            this.listener = listener
        }

        val appContext = context.applicationContext
        Thread({
            run(appContext, release)
        }, "update-download").start()
        return true
    }

    fun cancel() {
        cancelRequested = true
        connection?.disconnect()
    }

    fun detach(listener: Listener) {
        synchronized(this) {
            if (this.listener === listener) this.listener = null
        }
    }

    // ==================== 下载线程 ====================

    private fun run(context: Context, release: UpdateChecker.ReleaseInfo) {
        val dir = downloadDir(context).apply { mkdirs() }
        val fileName = UpdateChecker.apkFileName(release.version)
        cleanupOldFiles(dir, keep = fileName)

        val target = File(dir, fileName)
        val part = File(dir, "$fileName.part")
        part.delete()

        try {
            val conn = openConnection(release.apkUrl, context)
            connection = conn

            // 更新包上限 100MB，int 足够；contentLengthLong 要 API 24+
            val contentLength = conn.contentLength.toLong().takeIf { it > 0 }
            val total = contentLength ?: release.apkSize
            if (total > MAX_APK_BYTES) throw TooLargeException()

            val expectedSha256 = release.sha256
            val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
            var received = 0L
            var lastProgressAt = 0L

            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelRequested) throw CancelledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_APK_BYTES) throw TooLargeException()
                        output.write(buffer, 0, count)
                        digest?.update(buffer, 0, count)

                        val now = SystemClock.uptimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                            lastProgressAt = now
                            deliver(Event.Progress(received, total))
                        }
                    }
                }
            }

            if (cancelRequested) throw CancelledException()
            if (contentLength != null && received != contentLength) throw SizeMismatchException()
            if (release.apkSize > 0 && received != release.apkSize) throw SizeMismatchException()

            if (digest != null) {
                val actual = digest.digest().joinToString("") {
                    String.format(Locale.US, "%02x", it.toInt() and 0xff)
                }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw HashMismatchException()
                }
            }

            if (!part.renameTo(target)) {
                // 同一目录下 rename 正常不会失败；极端文件系统上退化成复制
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            deliver(Event.Progress(received, received))
            deliver(Event.Done(target))
        } catch (e: CancelledException) {
            part.delete()
            deliver(Event.Cancelled)
        } catch (e: TooLargeException) {
            part.delete()
            deliver(Event.Failed(Reason.TOO_LARGE))
        } catch (e: SizeMismatchException) {
            part.delete()
            deliver(Event.Failed(Reason.SIZE_MISMATCH))
        } catch (e: HashMismatchException) {
            part.delete()
            deliver(Event.Failed(Reason.HASH_MISMATCH))
        } catch (e: Exception) {
            part.delete()
            Log.w(TAG, "download failed: ${e.message}")
            deliver(if (cancelRequested) Event.Cancelled else Event.Failed(Reason.NETWORK))
        } finally {
            connection?.disconnect()
            connection = null
            runningVersion = null
            running = false
        }
    }

    /**
     * GitHub 的 release asset 会 302 到 objects.githubusercontent.com。
     * 这里手动跟随，并且每一跳都要求 https —— 不允许被重定向到明文地址。
     */
    private fun openConnection(url: String, context: Context): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) { index ->
            if (cancelRequested) throw CancelledException()
            if (!isHttps(current)) throw IOException("not https")

            val conn = URL(current).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", UpdateChecker.userAgent(context))
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("Accept-Encoding", "identity")

            val code = conn.responseCode
            if (code in 300..399) {
                if (index == MAX_REDIRECTS) {
                    conn.disconnect()
                    throw IOException("too many redirects")
                }
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without location")
                // Location 可能是相对地址
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            return conn
        }
        throw IOException("too many redirects")
    }

    private fun cleanupOldFiles(dir: File, keep: String) {
        dir.listFiles()?.forEach { file ->
            if (file.name != keep && file.name != "$keep.part") file.delete()
        }
    }

    private fun downloadDir(context: Context): File = File(context.filesDir, "updates")

    private fun deliver(event: Event) {
        val target = listener ?: return
        mainHandler.post {
            // 同一个监听者才投递：旧任务的事件不能跑进新 Activity
            if (listener === target) target.onEvent(event)
        }
    }

    private class CancelledException : IOException()
    private class TooLargeException : IOException()
    private class SizeMismatchException : IOException()
    private class HashMismatchException : IOException()
}
