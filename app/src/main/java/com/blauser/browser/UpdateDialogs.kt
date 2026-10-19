package com.blauser.browser

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.blauser.browser.databinding.DialogUpdateProgressBinding

/**
 * 检查更新相关的对话框。
 *
 * 检查等待与下载进度共用 [DialogUpdateProgressBinding]；更新说明用标准的
 * AlertDialog 文本区，release notes 是纯文本，不渲染 Markdown。
 */
object UpdateDialogs {

    /** release notes 显示上限。太长的正文会把对话框撑得没法看 */
    private const val NOTES_LIMIT = 2000

    fun showChecking(activity: AppCompatActivity, onCancel: () -> Unit): AlertDialog {
        val binding = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
        binding.tvUpdateStatus.setText(R.string.update_checking)
        binding.progressUpdate.isIndeterminate = true

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.menu_check_update)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        dialog.show()
        return dialog
    }

    fun showUpdate(
        activity: AppCompatActivity,
        release: UpdateChecker.ReleaseInfo,
        currentVersion: String,
        onDownload: () -> Unit,
        onLater: () -> Unit
    ): AlertDialog {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, release.version))
            .setMessage(
                activity.getString(
                    R.string.update_dialog_message,
                    currentVersion,
                    release.version,
                    notesForDisplay(activity, release.notes)
                )
            )
            .setPositiveButton(R.string.action_download_install) { _, _ -> onDownload() }
            .setNegativeButton(R.string.action_later) { _, _ -> onLater() }
            .setOnCancelListener { onLater() }
            .create()
        dialog.show()
        return dialog
    }

    fun showDownloadProgress(
        activity: AppCompatActivity,
        onCancel: () -> Unit
    ): DownloadProgressDialog {
        val binding = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
        binding.tvUpdateStatus.setText(R.string.update_download_unknown_progress)
        binding.progressUpdate.isIndeterminate = true

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_download_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        dialog.show()
        return DownloadProgressDialog(dialog, binding)
    }

    fun showInstallPermission(
        activity: AppCompatActivity,
        onOpenSettings: () -> Unit,
        onDecline: () -> Unit
    ): AlertDialog {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setPositiveButton(R.string.action_go_settings) { _, _ -> onOpenSettings() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onDecline() }
            .setOnCancelListener { onDecline() }
            .create()
        dialog.show()
        return dialog
    }

    private fun notesForDisplay(activity: AppCompatActivity, notes: String): String {
        val trimmed = notes.trim()
        if (trimmed.isEmpty()) return activity.getString(R.string.update_notes_empty)
        return if (trimmed.length > NOTES_LIMIT) {
            trimmed.substring(0, NOTES_LIMIT) + "…"
        } else {
            trimmed
        }
    }

    /** 下载进度对话框：主线程调用 update / dismiss */
    class DownloadProgressDialog(
        private val dialog: AlertDialog,
        private val binding: DialogUpdateProgressBinding
    ) {
        val isShowing: Boolean get() = dialog.isShowing

        fun update(received: Long, total: Long) {
            if (!dialog.isShowing) return
            val context = binding.root.context
            if (total > 0) {
                val percent = (received.coerceAtMost(total) * 100.0 / total).toInt()
                binding.progressUpdate.isIndeterminate = false
                binding.progressUpdate.max = 100
                binding.progressUpdate.progress = percent
                binding.tvUpdateStatus.text =
                    context.getString(R.string.update_download_progress, percent)
            } else {
                binding.progressUpdate.isIndeterminate = true
                binding.tvUpdateStatus.setText(R.string.update_download_unknown_progress)
            }
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }
}
