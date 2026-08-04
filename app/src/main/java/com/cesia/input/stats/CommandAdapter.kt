package com.cesia.input.stats

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cesia.input.R

/**
 * 命令库 RecyclerView 适配器
 * 支持：2列 GridLayout、置顶/常用标记、长按菜单、点击执行
 */
class CommandAdapter(
    private val context: Context,
    private val onItemClick: (CommandLibrary.CommandItem) -> Unit,
    private val onItemLongClick: (View, CommandLibrary.CommandItem) -> Boolean,
    private val themeAccent: Int,
    private val textScaleFactor: Float = 1f
) : RecyclerView.Adapter<CommandAdapter.ViewHolder>() {

    private var items = mutableListOf<CommandLibrary.CommandItem>()
    private var frequentIds = mutableSetOf<String>()

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tv_command_text)
        val indicatorPinned: View = view.findViewById(R.id.indicator_pinned)
        val indicatorFrequent: View = view.findViewById(R.id.indicator_frequent)
        val etEdit: TextView = view.findViewById(R.id.et_command_edit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_command_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvText.text = item.name
        holder.tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * textScaleFactor)
        
        // 置顶标记
        holder.indicatorPinned.visibility = if (item.isPinned) View.VISIBLE else View.GONE
        // 常用标记
        holder.indicatorFrequent.visibility = if (frequentIds.contains(item.id)) View.VISIBLE else View.GONE
        
        // 点击执行
        holder.view.setOnClickListener { onItemClick(item) }
        
        // 长按菜单
        holder.view.setOnLongClickListener { onItemLongClick(holder.view, item) }
        
        // 隐藏编辑框
        holder.etEdit.visibility = View.GONE
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<CommandLibrary.CommandItem>, newFrequentIds: Set<String>) {
        items.clear()
        items.addAll(newItems)
        frequentIds.clear()
        frequentIds.addAll(newFrequentIds)
        notifyDataSetChanged()
    }
}