package com.cesia.input.t9

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * T9 字母组合选择器适配器：横向显示合法拼音组合 chip。
 * 点击某组合 = 锁定该拼音前缀（回调 onSelect）。
 */
class T9ComboAdapter(
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<T9ComboAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()
    var textColor: Int = Color.parseColor("#4488FF")
    var pinnedPrefix: String = ""

    inner class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.WRAP_CONTENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val typedValue = TypedValue()
            val resolved = ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, typedValue, true
            )
            if (resolved) {
                background = ContextCompat.getDrawable(ctx, typedValue.resourceId)
            }
            isSingleLine = true
            isClickable = true
            isFocusable = true
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = items[position]
        holder.textView.text = text
        holder.textView.setTextColor(textColor)
        // 已锁定前缀高亮（加粗）
        holder.textView.paint.isFakeBoldText = text == pinnedPrefix
        holder.textView.setOnClickListener { onSelect(text) }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
