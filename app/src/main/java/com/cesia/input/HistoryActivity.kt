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

        // 顶部栏：返回 / 大纲 / 清空 / 关闭（4按钮，风格与设置页历史记录按钮一致：白底+主题色文字+主题色描边）
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 32, 32, 16)
        }

        val accent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())  // 边框/文字随主题色变化

        fun styledButton(text: String): Button {
            val btn = Button(this)
            btn.text = text
            btn.textSize = 13f
            btn.maxLines = 1
            btn.ellipsize = android.text.TextUtils.TruncateAt.END
            val d = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setStroke((1 * resources.displayMetrics.density).toInt(), accent)
                cornerRadius = 10 * resources.displayMetrics.density
            }
            btn.background = d
            btn.setTextColor(accent)
            val px = (6 * resources.displayMetrics.density).toInt()
            btn.setPadding(px, px, px, px)
            val h = (34 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(0, h, 1f).apply {
                if (topBar.childCount > 0) leftMargin = (8 * resources.displayMetrics.density).toInt()
            }
            btn.layoutParams = lp
            return btn
        }

        val btnBack = styledButton("返回").apply {
            setOnClickListener { finish() }
        }
        val btnOutline = styledButton("大纲").apply {
            setOnClickListener { showGrammarGuideDialog() }
        }
        val btnClearAll = styledButton("清空").apply {
            setOnClickListener {
                // 清空菜单：两项选择
                val items = arrayOf("只清空历史记录（保留历史记录功能）", "清空历史记录并关闭历史记录功能")
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("清空历史记录")
                    .setItems(items) { _, which ->
                        when (which) {
                            0 -> {
                                statsManager.clearRecords()
                                refreshData()
                                Toast.makeText(this@HistoryActivity, "已清空历史记录", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                statsManager.clearRecords()
                                getSharedPreferences("cesia_polish_history", MODE_PRIVATE)
                                    .edit().putString("history_mode", "off").apply()
                                refreshData()
                                Toast.makeText(this@HistoryActivity, "已清空并关闭历史记录功能", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        topBar.addView(btnBack)
        topBar.addView(btnOutline)
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
        // AI 大纲为空时，用本地大纲兜底（直接基于历史记录生成，无需 AI）
        val content = if (guideMgr.content.isNotEmpty()) {
            guideMgr.content
        } else {
            val local = guideMgr.buildLocalOutline(statsManager.getRecords())
            if (local.isNotEmpty()) {
                guideMgr.saveGuide(local)  // 缓存，避免重复生成
                local
            } else ""
        }
        val message = if (content.isNotEmpty()) {
            "版本 ${guideMgr.version} | 基于最近润色记录自动生成\n\n$content"
        } else {
            "暂无语法大纲\n\n请先使用几次润色功能，系统会自动生成个人语法纲要。"
        }
        AlertDialog.Builder(this)
            .setTitle("📖 个人语法纲要")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
    }
}
