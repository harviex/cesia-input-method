package com.cesia.input

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class CandidateAdapter(
    private val onItemClick: (Int, String) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()
    var textScaleFactor: Float = 1f
    var textColor: Int = Color.parseColor("#333333")

    inner class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.WRAP_CONTENT,
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
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = items[position]
        holder.textView.text = text
        holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * textScaleFactor)
        holder.textView.setTextColor(textColor)
        holder.textView.setOnClickListener { onItemClick(position, text) }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
