package com.blauser.browser

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 通用网址列表对话框：收藏夹与历史记录共用。
 *
 * 两者的差别只有「删除按钮」还是「清空按钮」，为此各写一份几乎一样的类和布局
 * 不划算，所以做成配置驱动 —— 传 null 就等于关掉那一项。
 */
class UrlListDialog(
    context: Context,
    private val title: String,
    private val emptyText: String,
    private val rows: List<Row>,
    private val onSelect: (String) -> Unit,
    /** 逐条删除的按钮；传 null 则不显示（历史记录用「清空」） */
    private val onDelete: ((String) -> Unit)? = null,
    /** 右上角「清空」；传 null 则不显示（收藏夹用逐条删除） */
    private val onClearAll: (() -> Unit)? = null
) : Dialog(context) {

    /** [badge] 为 null 时不显示角标 */
    data class Row(val title: String, val url: String, val badge: String? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_url_list)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        findViewById<TextView>(R.id.tvListTitle).text = title
        findViewById<TextView>(R.id.tvListEmpty).apply {
            text = emptyText
            isVisible = rows.isEmpty()
        }

        findViewById<TextView>(R.id.btnListClearAll).apply {
            isVisible = onClearAll != null
            onClearAll?.let { clear ->
                setOnClickListener {
                    clear()
                    dismiss()
                }
            }
        }

        val rv = findViewById<RecyclerView>(R.id.rvList)
        rv.isVisible = rows.isNotEmpty()
        if (rows.isEmpty()) return

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = Adapter()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvUrlTitle)
            val tvSubtitle: TextView = view.findViewById(R.id.tvUrlSubtitle)
            val btnDelete: ImageButton = view.findViewById(R.id.btnUrlDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_url_row, parent, false)
            )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.tvTitle.text = row.title.ifBlank { row.url }
            holder.tvSubtitle.text = row.badge?.let { "$it · ${row.url}" } ?: row.url

            holder.itemView.setOnClickListener {
                onSelect(row.url)
                dismiss()
            }

            holder.btnDelete.isVisible = onDelete != null
            onDelete?.let { delete ->
                holder.btnDelete.setOnClickListener {
                    delete(row.url)
                    dismiss()
                }
            }
        }

        override fun getItemCount() = rows.size
    }
}
