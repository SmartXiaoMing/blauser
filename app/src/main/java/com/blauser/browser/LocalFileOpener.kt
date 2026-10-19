package com.blauser.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 打开文件管理器 / 其他 App 送来的本地 HTML。
 *
 * 两条来源，处理方式不同：
 *  - `file://`：WebView 自己能读，直接交给它（加载前由 MainActivity 打开 allowFileAccess）
 *  - `content://`：WebView 不认这个 scheme，先复制到应用缓存目录，再按 `file://` 加载
 *
 * 复制而不是直接把 `content://` 交给 WebView，还顺带解决了授权问题：
 * 对方给的读取授权只发给收到 Intent 的那个 Activity，而本 App 会把页面转交给
 * 新标签页的另一个 Activity —— 转交时授权已经失效。复制正好发生在授权还有效的实例里。
 */
object LocalFileOpener {

    private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml", "xht")
    private val HTML_MIME_TYPES =
        setOf("text/html", "application/xhtml+xml", "text/xhtml")

    /** 缓存副本目录。放 cacheDir 下，系统空间紧张时可以自行清理 */
    private const val CACHE_DIR = "local_html"

    /**
     * 这个 URI 是不是 HTML 文件。
     *
     * MIME 优先，其次看扩展名 —— 不少文件管理器给 `.html` 填的是
     * `application/octet-stream`，只看 MIME 会把它们漏掉。
     */
    fun isHtmlFile(context: Context, uri: Uri, intentType: String?): Boolean {
        val declared = intentType?.takeIf { it.isNotBlank() && it != "*/*" }
        val mime = (declared ?: runCatching { context.contentResolver.getType(uri) }.getOrNull())
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (mime != null && mime in HTML_MIME_TYPES) return true
        return isHtmlName(displayName(context, uri) ?: uri.lastPathSegment)
    }

    /** 转成 WebView 能加载的地址；不是本地 HTML 或读不出来时返回 null */
    fun prepare(context: Context, uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.toString()
        "content" -> copyToCache(context, uri)
        else -> null
    }

    private fun copyToCache(context: Context, uri: Uri): String? {
        val input = runCatching { context.contentResolver.openInputStream(uri) }
            .getOrNull() ?: return null

        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, cacheName(displayName(context, uri)))

        return try {
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            Uri.fromFile(target).toString()
        } catch (e: Exception) {
            // 读一半失败留下的残片不能留着 —— 下次打开会拿到一个截断的页面
            target.delete()
            null
        }
    }

    /**
     * 缓存文件名保留原扩展名：WebView 加载 `file://` 时靠它判断内容类型。
     * 前面加短随机串，避免同名文件互相覆盖。
     */
    private fun cacheName(displayName: String?): String {
        val base = displayName.orEmpty()
            .substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .take(80)
            .ifBlank { "page" }
        val withExtension = if (isHtmlName(base)) base else "$base.html"
        return "${UUID.randomUUID().toString().take(8)}-$withExtension"
    }

    private fun isHtmlName(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return false
        return extension in HTML_EXTENSIONS
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}
