package com.blauser.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * 悬浮球菜单里那些「对当前网页做点什么」的动作：收藏 / 复制 / 分享 / 加桌面快捷方式 / 收藏夹 / 审查元素。
 *
 * 从 MainActivity 抽出来，一方面它不再膨胀，另一方面这些动作都是无状态的
 * —— 只依赖「当前网址 + 当前标题」，不需要持有 Activity 的任何字段。
 */
object PageActions {

    /** 收藏 / 取消收藏。返回 true 表示操作后处于「已收藏」状态 */
    fun toggleBookmark(activity: AppCompatActivity, url: String?, title: String): Boolean {
        val target = url?.takeIf { it.isNotBlank() } ?: run {
            toast(activity, activity.getString(R.string.toast_no_page_to_bookmark))
            return false
        }
        val label = title.takeIf { it.isNotBlank() } ?: target
        val starred = BookmarkManager.toggle(activity, Bookmark(label, target))
        toast(
            activity,
            if (starred) activity.getString(R.string.toast_bookmarked, label)
            else activity.getString(R.string.toast_unbookmarked)
        )
        return starred
    }

    fun copyUrl(activity: AppCompatActivity, url: String?) {
        val target = url?.takeIf { it.isNotBlank() } ?: run {
            toast(activity, activity.getString(R.string.toast_no_url_to_copy))
            return
        }
        clipboard(activity).setPrimaryClip(ClipData.newPlainText("url", target))
        // Android 13 起系统自己会弹复制提示，别重复弹
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(activity, activity.getString(R.string.toast_copied))
        }
    }

    fun share(activity: AppCompatActivity, url: String?, title: String) {
        val target = url?.takeIf { it.isNotBlank() } ?: run {
            toast(activity, activity.getString(R.string.toast_no_url_to_share))
            return
        }
        val label = title.takeIf { it.isNotBlank() } ?: target
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, label)
            putExtra(Intent.EXTRA_TEXT, "$label\n$target")
        }
        activity.startActivity(
            Intent.createChooser(send, activity.getString(R.string.share_chooser))
        )
    }

    /**
     * 把当前网页加到桌面。
     *
     * Intent 的 data 用**新生成的标签 key**、网址走 extra —— 和 `openNewTab()` 一致。
     * 不能让 data 直接放网址：那样每个快捷方式打开的标签 key 就是网址本身，
     * 同一个网址开两次会撞成同一个标签（状态互相覆盖、关一个关俩）。
     */
    fun addShortcut(activity: AppCompatActivity, url: String?, title: String) {
        val target = url?.takeIf { it.isNotBlank() } ?: run {
            toast(activity, activity.getString(R.string.toast_no_page_for_shortcut))
            return
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            toast(activity, activity.getString(R.string.toast_shortcut_unsupported))
            return
        }
        val label = title.takeIf { it.isNotBlank() } ?: target

        val shortcutIntent = Intent(activity, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(TabRegistry.newTabKey())
            putExtra(MainActivity.EXTRA_URL, target)
            // 与开新标签一致：点快捷方式也应当是一个独立任务
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        val shortcut = ShortcutInfoCompat.Builder(activity, "url-" + target.hashCode())
            .setShortLabel(label.take(10))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(activity, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .build()

        // 系统会弹确认框，用户点确认才会真正加到桌面
        ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
    }

    /** 收藏夹列表。onOpen 由调用方给（要复用已打开的标签页），onChanged 用于刷新新标签页 */
    fun showBookmarkList(
        activity: AppCompatActivity,
        onOpen: (String) -> Unit,
        onChanged: () -> Unit
    ) {
        val bookmarks = BookmarkManager.getAll(activity)
        if (bookmarks.isEmpty()) {
            toast(activity, activity.getString(R.string.toast_bookmarks_empty))
            return
        }
        UrlListDialog(
            context = activity,
            title = activity.getString(R.string.bookmarks_title),
            emptyText = activity.getString(R.string.bookmarks_empty),
            rows = bookmarks.map { UrlListDialog.Row(it.title, it.url) },
            onSelect = onOpen,
            onDelete = { url ->
                BookmarkManager.remove(activity, url)
                toast(activity, activity.getString(R.string.toast_deleted))
                onChanged()
            }
        ).show()
    }

    /**
     * 历史记录。
     *
     * 清空是破坏性的（而且用户往往是在别处找不着东西时才来翻历史），
     * 所以先确认一次，不做「一点就没了」。
     */
    fun showHistory(activity: AppCompatActivity, onOpen: (String) -> Unit) {
        val entries = HistoryManager.getAll(activity)
        if (entries.isEmpty()) {
            toast(activity, activity.getString(R.string.toast_history_empty))
            return
        }
        UrlListDialog(
            context = activity,
            title = activity.getString(R.string.history_title),
            emptyText = activity.getString(R.string.history_empty),
            rows = entries.map { UrlListDialog.Row(it.title, it.url) },
            onSelect = onOpen,
            onClearAll = {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.history_clear_title)
                    .setMessage(R.string.history_clear_message)
                    .setPositiveButton(R.string.action_clear_all) { _, _ ->
                        HistoryManager.clear(activity)
                        toast(activity, activity.getString(R.string.history_cleared))
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        ).show()
    }

    fun showDevTools(activity: AppCompatActivity, url: String) {
        val shown = url.ifBlank { activity.getString(R.string.devtools_no_page) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.devtools_title)
            .setMessage(activity.getString(R.string.devtools_message, shown))
            .setPositiveButton(R.string.devtools_copy) { _, _ ->
                clipboard(activity).setPrimaryClip(
                    ClipData.newPlainText("DevTools", "chrome://inspect/#devices")
                )
                toast(activity, activity.getString(R.string.toast_copied_clipboard))
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun clipboard(activity: AppCompatActivity): ClipboardManager =
        activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun toast(activity: AppCompatActivity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
