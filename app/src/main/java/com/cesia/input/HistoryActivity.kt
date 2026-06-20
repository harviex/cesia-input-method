package com.cesia.input

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesia.input.stats.PolishStatsManager
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var statsManager: PolishStatsManager
    private lateinit var adapter: HistoryAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statsManager = PolishStatsManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶部栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 32, 32, 16)
        }

        val btnBack = Button(this).apply {
            text = "← 返回"
            setOnClickListener { finish() }
        }

        val btnClear = Button(this).apply {
            text = "📖 大纲"
            setOnClickListener {
                showGrammarGuideDialog()
            }
        }

        val btnClearAll = Button(this).apply {
            text = "🗑️ 清空"
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("清空历史记录")
                    .setMessage("确定要清空所有润色历史记录吗？")
                    .setPositiveButton("清空") { _, _ ->
                        statsManager.clearRecords()
                        refreshData()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        topBar.addView(btnBack)
        topBar.addView(btnClear)
        topBar.addView(btnClearAll)
        root.addView(topBar)

        tvEmpty = TextView(this).apply {
            text = "暂无润色记录"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 64, 32, 32)
            visibility = View.GONE
        }
        root.addView(tvEmpty)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
        }
        root.addView(recyclerView)

        setContentView(root)
        setTitle("润色历史记录")

        refreshData()
    }

    private fun refreshData() {
        val records = statsManager.getRecords()
        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter = HistoryAdapter(records) { index ->
                AlertDialog.Builder(this)
                    .setTitle("删除记录")
                    .setMessage("删除这条记录？")
                    .setPositiveButton("删除") { _, _ ->
                        statsManager.deleteRecord(index)
                        refreshData()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            recyclerView.adapter = adapter
        }
    }

    class HistoryAdapter(
        private val records: List<com.cesia.input.stats.PolishRecord>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        // 记录每条是否展开
        private val expandedPositions = mutableSetOf<Int>()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tv_record_time)
            val tvInput: TextView = view.findViewById(R.id.tv_record_input)
            val tvOutput: TextView = view.findViewById(R.id.tv_record_output)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_record)
            val tvExpandHint: TextView = view.findViewById(R.id.tv_expand_hint)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val record = records[position]
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            holder.tvTime.text = sdf.format(Date(record.timestamp))

            val isExpanded = expandedPositions.contains(position)

            // 原文
            holder.tvInput.text = record.inputText
            holder.tvInput.maxLines = if (isExpanded) Int.MAX_VALUE else 2

            // 润色结果
            holder.tvOutput.text = record.outputText
            holder.tvOutput.maxLines = if (isExpanded) Int.MAX_VALUE else 3

            // 判断是否需要显示"展开"提示
            val inputTooLong = record.inputText.length > 60
            val outputTooLong = record.outputText.length > 80
            val canExpand = inputTooLong || outputTooLong

            if (canExpand && !isExpanded) {
                holder.tvExpandHint.visibility = View.VISIBLE
                holder.tvExpandHint.text = "▼ 点击展开全文"
            } else if (isExpanded) {
                holder.tvExpandHint.visibility = View.VISIBLE
                holder.tvExpandHint.text = "▲ 点击收起"
            } else {
                holder.tvExpandHint.visibility = View.GONE
            }

            // 点击展开/收起
            holder.itemView.setOnClickListener {
                if (expandedPositions.contains(position)) {
                    expandedPositions.remove(position)
                } else {
                    expandedPositions.add(position)
                }
                notifyItemChanged(position)
            }

            holder.btnDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = records.size
    }

    private fun showGrammarGuideDialog() {
        val guideMgr = com.cesia.input.stats.GrammarGuideManager(this)
        val content = guideMgr.content
        val message = if (content.isNotEmpty()) {
            "版本 ${guideMgr.version} | 基于最近润色记录自动生成\n\n$content"
        } else {
            "暂无语法大纲\n\n请先使用几次润色功能，系统每5条记录会自动生成个人语法纲要。"
        }
        AlertDialog.Builder(this)
            .setTitle("📖 个人语法纲要")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
    }
}
