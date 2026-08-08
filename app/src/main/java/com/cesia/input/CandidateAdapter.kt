package com.cesia.input

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.text.TextUtils.TruncateAt
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class CandidateAdapter(
    private val onItemClick: (Int, String) -> Unit,
    private val onItemLongClick: ((view: android.view.View, index: Int, word: String) -> Boolean)? = null
) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()
    var textScaleFactor: Float = 1f
    var textColor: Int = Color.parseColor("#333333")
    var newsMode: Boolean = false   // 新闻态：顶栏标题过长时跑马灯滚动
    // 当前绑定到的 TextView 引用，供 marquee 直接改文本（不重绑，保留 longPressed 状态）
    private var lastBoundView: TextView? = null
    // 当前展示文本（marquee 滚动中可能被截短），供点击回调取真实文字
    private var currentText: String = ""

    inner class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                if (newsMode) RecyclerView.LayoutParams.MATCH_PARENT
                else RecyclerView.LayoutParams.WRAP_CONTENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * textScaleFactor)
            setTextColor(textColor)
            // 使用系统 selectableItemBackground，通过 theme resolve 避免 Resources$NotFoundException
            val typedValue = TypedValue()
            val resolved = ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, typedValue, true
            )
            if (resolved) {
                background = ContextCompat.getDrawable(ctx, typedValue.resourceId)
            } else {
                setBackgroundColor(Color.TRANSPARENT)
            }
            isSingleLine = true
            isClickable = true
            isFocusable = true
            if (newsMode) {
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1   // 无限循环
                isSelected = true
            }
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = items[position]
        currentText = text
        lastBoundView = holder.textView
        holder.textView.text = text
        holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * textScaleFactor)
        holder.textView.setTextColor(textColor)
        var longPressed = false
        holder.textView.setOnClickListener {
            if (longPressed) { longPressed = false; return@setOnClickListener }
            onInteract?.invoke()   // 交互即停止 marquee 滚动
            onItemClick(position, currentText)
        }
        if (onItemLongClick != null) {
            holder.textView.setOnLongClickListener {
                onInteract?.invoke()   // 交互即停止 marquee 滚动
                val consumed = onItemLongClick.invoke(holder.textView, position, currentText)
                if (consumed) longPressed = true
                consumed
            }
        } else {
            holder.textView.setOnLongClickListener(null)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** marquee 滚动时直接改文本（不 notifyDataSetChanged，避免重绑重置 longPressed 状态） */
    fun setCurrentText(text: String) {
        currentText = text
        lastBoundView?.text = text
    }

    /** 点击/长按触发时回调，用于停止 marquee 滚动（由宿主设置） */
    var onInteract: (() -> Unit)? = null
}
