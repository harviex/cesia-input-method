package com.cesia.input

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesia.input.stats.PolishHistoryManager
import java.text.SimpleDateFormat
import java.util.*

class PolishHistoryActivity : AppCompatActivity() {

    private lateinit var historyManager: PolishHistoryManager
    private lateinit var adapter: PolishHistoryAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyManager = PolishHistoryManager(this)

        val themeAccent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())

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
        val tvTitle = TextView(this).apply {
            text = "语音润色历史"
            textSize = 18f
            setPadding(24, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        // 模式提示
        val modeText = when (historyManager.getMode()) {
            PolishHistoryManager.MODE_LOCAL -> "当前：仅本地记录"
            PolishHistoryManager.MODE_AI -> "当前：本地记录 + 允许 AI 分析"
            else -> "当前：未开启记录"
        }
        val tvMode = TextView(this).apply {
            text = modeText
            textSize = 13f
            setPadding(36, 0, 36, 12)
        }
        root.addView(tvMode)

        tvEmpty = TextView(this).apply {
            text = "暂无润色记录"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        root.addView(tvEmpty)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PolishHistoryActivity)
            setPadding(24, 8, 24, 24)
        }
        root.addView(recyclerView)

        // 清空按钮
        val btnClear = Button(this).apply {
            text = "清空全部记录"
            setOnClickListener {
                historyManager.clearAll()
                refresh()
                Toast.makeText(this@PolishHistoryActivity, "已清空", Toast.LENGTH_SHORT).show()
            }
        }
        val clearWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        clearWrap.addView(btnClear)
        root.addView(clearWrap)

        setContentView(root)
        adapter = PolishHistoryAdapter(emptyList(), themeAccent)
        recyclerView.adapter = adapter
        refresh()
    }

    private fun refresh() {
        val records = historyManager.getRecords()
        tvEmpty.visibility = if (records.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        recyclerView.visibility = if (records.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        adapter.update(records)
    }

    class PolishHistoryAdapter(
        private var items: List<PolishHistoryManager.PolishRecord>,
        private val accent: Int
    ) : RecyclerView.Adapter<PolishHistoryAdapter.VH>() {

        class VH(
            val tvOriginal: TextView,
            val tvPolished: TextView,
            val tvMeta: TextView
        ) : RecyclerView.ViewHolder(
            LinearLayout(tvOriginal.context).apply { orientation = LinearLayout.VERTICAL }
        )

        fun update(list: List<PolishHistoryManager.PolishRecord>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
            }
            val t1 = TextView(ctx).apply { textSize = 15f; setTextColor(0xFF333333.toInt()) }
            val t2 = TextView(ctx).apply {
                textSize = 15f; setTextColor(accent); setPadding(0, 4, 0, 0)
            }
            val t3 = TextView(ctx).apply {
                textSize = 11f; setTextColor(0xFF999999.toInt()); setPadding(0, 4, 0, 0)
            }
            container.addView(t1)
            container.addView(t2)
            container.addView(t3)
            val divider = android.view.View(ctx).apply {
                setBackgroundColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            }
            val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            outer.addView(container)
            outer.addView(divider)
            return VH(t1, t2, t3).also { (outer as android.view.View) }
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.tvOriginal.text = "原文：${r.original.take(200)}"
            holder.tvPolished.text = "润色：${r.polished.take(200)}"
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val time = if (r.timestamp > 0) sdf.format(Date(r.timestamp)) else ""
            holder.tvMeta.text = "$time  ·  ${if (r.aiUsed) "AI 润色" else "本地润色"}"
        }

        override fun getItemCount() = items.size
    }
}
