package com.blauser.browser

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 两个设置对话框：全局设置、本站设置。
 *
 * 它们只负责「读当前值 → 渲染表单 → 存回去」，存完调 onApplied 让 MainActivity
 * 去决定要不要重载页面 —— 重载策略属于标签页的事，不属于对话框。
 */
object SettingsDialogs {

    // ==================== 全局设置 ====================

    fun showGlobal(activity: AppCompatActivity, onApplied: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_settings, null)
        val spWidth = view.findViewById<Spinner>(R.id.spWidth)
        val spUa = view.findViewById<Spinner>(R.id.spUa)
        val etUaCustom = view.findViewById<EditText>(R.id.etUaCustom)
        val spOrientation = view.findViewById<Spinner>(R.id.spOrientation)
        val etLanguage = view.findViewById<EditText>(R.id.etLanguage)

        // --- 页面宽度 ---
        spWidth.adapter = spinnerAdapter(activity, SettingsManager.WIDTH_OPTIONS.map {
            activity.getString(it.labelRes)
        })
        val curWidth = SettingsManager.getPageWidth(activity)
        spWidth.setSelection(
            SettingsManager.WIDTH_OPTIONS.indexOfFirst { it.value == curWidth }.coerceAtLeast(0)
        )

        // --- UserAgent ---
        spUa.adapter = spinnerAdapter(activity, SettingsManager.UA_PRESETS.map {
            activity.getString(it.labelRes)
        })
        val curUaKey = SettingsManager.getUaKey(activity)
        spUa.setSelection(
            SettingsManager.UA_PRESETS.indexOfFirst { it.key == curUaKey }.coerceAtLeast(0)
        )
        etUaCustom.setText(SettingsManager.getUaForEditing(activity))
        etUaCustom.visibility =
            if (curUaKey == SettingsManager.UA_CUSTOM) View.VISIBLE else View.GONE
        spUa.onItemSelectedListener = SimpleItemSelected { pos ->
            val key = SettingsManager.UA_PRESETS[pos].key
            etUaCustom.visibility =
                if (key == SettingsManager.UA_CUSTOM) View.VISIBLE else View.GONE
        }

        // --- 屏幕方向 ---
        spOrientation.adapter = spinnerAdapter(activity, SettingsManager.ORIENTATION_OPTIONS.map {
            activity.getString(it.labelRes)
        })
        val curOrientation = SettingsManager.getOrientation(activity)
        spOrientation.setSelection(
            SettingsManager.ORIENTATION_OPTIONS
                .indexOfFirst { it.value == curOrientation }.coerceAtLeast(0)
        )

        // --- 语言 ---
        etLanguage.setText(SettingsManager.getLanguage(activity))

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                SettingsManager.setPageWidth(
                    activity, SettingsManager.WIDTH_OPTIONS[spWidth.selectedItemPosition].value
                )
                SettingsManager.setUa(
                    activity,
                    SettingsManager.UA_PRESETS[spUa.selectedItemPosition].key,
                    etUaCustom.text.toString().trim()
                )
                SettingsManager.setOrientation(
                    activity,
                    SettingsManager.ORIENTATION_OPTIONS[spOrientation.selectedItemPosition].value
                )
                SettingsManager.setLanguage(activity, etLanguage.text.toString().trim())
                onApplied()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ==================== 本站设置 ====================

    fun showSite(activity: AppCompatActivity, currentUrl: String, onApplied: () -> Unit) {
        val host = SiteSettingsManager.hostOf(currentUrl) ?: run {
            toast(activity, activity.getString(R.string.toast_site_settings_no_host))
            return
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_site_settings, null)
        val spOrientation = view.findViewById<Spinner>(R.id.spSiteOrientation)
        val spWidth = view.findViewById<Spinner>(R.id.spSiteWidth)
        val spUa = view.findViewById<Spinner>(R.id.spSiteUa)
        val etLang = view.findViewById<EditText>(R.id.etSiteLanguage)

        val current = SiteSettingsManager.get(activity, host)

        // 每一项都以「跟随全局」打头
        val inherit = activity.getString(R.string.site_inherit)

        val orientValues = listOf(SiteSettingsManager.INHERIT) +
            SettingsManager.ORIENTATION_OPTIONS.map { it.value }
        spOrientation.adapter = spinnerAdapter(
            activity,
            listOf(inherit) + SettingsManager.ORIENTATION_OPTIONS.map {
                activity.getString(it.labelRes)
            }
        )
        spOrientation.setSelection(orientValues.indexOf(current.orientation).coerceAtLeast(0))

        val widthValues = listOf(SiteSettingsManager.INHERIT) +
            SettingsManager.WIDTH_OPTIONS.map { it.value }
        spWidth.adapter = spinnerAdapter(
            activity,
            listOf(inherit) + SettingsManager.WIDTH_OPTIONS.map {
                activity.getString(it.labelRes)
            }
        )
        spWidth.setSelection(widthValues.indexOf(current.pageWidth).coerceAtLeast(0))

        // uaKey 是 String?，null 表示跟随全局，所以这里不能直接用 Int 的 indexOf
        val uaKeys = listOf<String?>(null) + SettingsManager.UA_PRESETS.map { it.key }
        spUa.adapter = spinnerAdapter(
            activity,
            listOf(inherit) + SettingsManager.UA_PRESETS.map { activity.getString(it.labelRes) }
        )
        spUa.setSelection(uaKeys.indexOf(current.uaKey).coerceAtLeast(0))

        etLang.setText(current.language.orEmpty())

        // 一键预设：直接改下面两个下拉的选中项，用户还能再微调
        view.findViewById<View>(R.id.btnPresetDesktop).setOnClickListener {
            spWidth.setSelection(
                widthValues.indexOf(SiteSettingsManager.DESKTOP_PRESET.pageWidth)
                    .coerceAtLeast(0)
            )
            spUa.setSelection(
                uaKeys.indexOf(SiteSettingsManager.DESKTOP_PRESET.uaKey).coerceAtLeast(0)
            )
        }
        view.findViewById<View>(R.id.btnPresetMobile).setOnClickListener {
            spWidth.setSelection(
                widthValues.indexOf(SiteSettingsManager.MOBILE_PRESET.pageWidth)
                    .coerceAtLeast(0)
            )
            spUa.setSelection(
                uaKeys.indexOf(SiteSettingsManager.MOBILE_PRESET.uaKey).coerceAtLeast(0)
            )
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.site_settings_title, host))
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                SiteSettingsManager.save(
                    activity, host,
                    SiteSettingsManager.Overrides(
                        orientation = orientValues[spOrientation.selectedItemPosition],
                        pageWidth = widthValues[spWidth.selectedItemPosition],
                        uaKey = uaKeys[spUa.selectedItemPosition],
                        language = etLang.text.toString().trim().ifBlank { null }
                    )
                )
                onApplied()
            }
            .setNeutralButton(R.string.action_clear) { _, _ ->
                SiteSettingsManager.clear(activity, host)
                onApplied()
                toast(activity, activity.getString(R.string.toast_site_settings_cleared, host))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ==================== 重载确认 ====================

    /**
     * 宽度 / UA / 语言只对之后新发起的请求有效，当前页必须重载才生效。
     * 重载会丢滚动位置和表单内容，所以不静默执行，交给用户决定。
     */
    fun confirmReload(
        activity: AppCompatActivity,
        @StringRes messageRes: Int,
        onReload: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.reload_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.action_reload_now) { _, _ -> onReload() }
            .setNegativeButton(R.string.action_later) { _, _ ->
                toast(activity, activity.getString(R.string.reload_deferred))
            }
            .show()
    }

    // ==================== 小工具 ====================

    private fun spinnerAdapter(activity: AppCompatActivity, labels: List<String>) =
        ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    /** 只关心 onItemSelected 的 Spinner 监听器 */
    private class SimpleItemSelected(val onSelected: (Int) -> Unit) :
        AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: AdapterView<*>?, view: View?, position: Int, id: Long
        ) = onSelected(position)

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun toast(activity: AppCompatActivity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
