package com.blauser.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast

/**
 * 网页触发的下载，交给系统的 DownloadManager。
 *
 * 为什么不自己写 IO：DownloadManager 会接管通知栏进度、断点续传、失败重试，
 * 而且下载完能直接在系统「下载」应用里看到 —— 自己写这些等于重造一遍。
 *
 * ## 存储权限
 *
 * API 29 起 DownloadManager 写公共「下载」目录不需要任何权限（它自己走 MediaStore）。
 * API 28 及以下需要 WRITE_EXTERNAL_STORAGE，由调用方（MainActivity）先申请再进来。
 */
object DownloadHandler {

    /**
     * 提交一个下载任务。
     *
     * @param userAgent 原样透传，有些站点靠它决定给什么文件
     * @param contentDisposition 用来猜文件名，可能为空
     */
    fun start(
        activity: Activity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || !UrlHelper.isHttpUrl(uri)) {
            // blob: / data: 之类的地址 DownloadManager 拿不到（内容还在渲染进程里），
            // 只能明确告诉用户不支持，别让他对着没反应的按钮发呆
            toast(activity, activity.getString(R.string.download_unsupported, uri?.scheme ?: url))
            return
        }

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

        // Request 的**构造**也必须在 try 里：setDestinationInExternalPublicDir 在外部存储
        // 不可用时（比如未挂载）会抛 IllegalStateException。放在 try 外面的话，
        // 异常会一路穿过 WebView 的 DownloadListener 回调，直接把 App 崩掉。
        try {
            val request = DownloadManager.Request(uri).apply {
                setTitle(fileName)
                setMimeType(mimeType)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                // 关键：DownloadManager 不共享 WebView 的 cookie jar，
                // 需要登录才能下的东西不带上 cookie 会下到一个登录页
                CookieManager.getInstance().getCookie(url)?.let {
                    addRequestHeader("Cookie", it)
                }
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            }

            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toast(activity, activity.getString(R.string.download_started, fileName))
        } catch (e: Exception) {
            // enqueue 还可能抛 IllegalArgumentException（文件名非法）或 SecurityException
            toast(activity, activity.getString(R.string.download_failed))
        }
    }

    private fun toast(activity: Activity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
