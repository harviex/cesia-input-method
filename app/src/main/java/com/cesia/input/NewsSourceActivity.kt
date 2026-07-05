package com.cesia.input

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

/**
 * RSS 源选择页面（轻量化版）
 * - 按分类展示所有可用源（预置 + 自定义）
 * - 点击条目切换选中/取消选中（复选框直接响应）
 * - 支持添加自定义 RSS 源
 * - 颜色跟随主题色
 */
class NewsSourceActivity : AppCompatActivity() {

    private lateinit var adapter: SourceAdapter
    private val allSources = mutableListOf<RssFetchManager.RssSource>()
    private var selectedUrl: String = ""
    private var themeAccent: Int = 0xFF81D8D0.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_source)

        // 读取主题色
        themeAccent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())

        // 标题栏背景色用主题色
        val titleBar = findViewById<LinearLayout>(R.id.title_bar)
        titleBar?.setBackgroundColor(themeAccent)

        // 关闭按钮
        findViewById<TextView>(R.id.btn_close).setOnClickListener { finish() }

        // 添加自定义源按钮
        findViewById<TextView>(R.id.btn_add_custom).setOnClickListener { showAddCustomDialog() }

        // 应用动态主题色到全树
        applyAccentToViewTree(window.decorView, themeAccent)

        // 获取所有源（预置 + 自定义）
        allSources.clear()
        allSources.addAll(RssFetchManager.getAllSources(this))

        // 当前已选中的源
        val current = RssFetchManager.getSelectedSource(this)
        selectedUrl = current?.url ?: ""

        // RecyclerView
        val rv = findViewById<RecyclerView>(R.id.rv_sources)
        adapter = SourceAdapter(allSources, selectedUrl, themeAccent) { source, isNowChecked ->
            if (isNowChecked) {
                // 选中：抓取并缓存
                Toast.makeText(this, "正在抓取：${source.name}...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.IO).launch {
                    val success = RssFetchManager.fetchAndCache(this@NewsSourceActivity, source)
                    runOnUiThread {
                        if (success) {
                            RssFetchManager.saveSelectedSource(this@NewsSourceActivity, source)
                            selectedUrl = source.url
                            adapter.notifyDataSetChanged()
                            Toast.makeText(
                                this@NewsSourceActivity,
                                "✓ ${source.name} 已选中并抓取成功",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@NewsSourceActivity,
                                "❌ ${source.name} 抓取失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } else {
                // 取消选中：清除选择
                RssFetchManager.clearSelectedSource(this@NewsSourceActivity)
                selectedUrl = ""
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "已取消：${source.name}", Toast.LENGTH_SHORT).show()
            }
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    /** 添加自定义 RSS 对话框 */
    private fun showAddCustomDialog() {
        val nameInput = EditText(this).apply {
            hint = "名称（如：我的博客）"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(24, 24, 24, 24)
        }
        val urlInput = EditText(this).apply {
            hint = "RSS 链接（如：https://example.com/feed.xml）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(24, 16, 24, 24)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameInput)
            addView(urlInput)
        }

        AlertDialog.Builder(this)
            .setTitle("➕ 添加自定义 RSS 源")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (RssFetchManager.addCustomSource(this, name, url)) {
                    // 刷新列表
                    allSources.clear()
                    allSources.addAll(RssFetchManager.getAllSources(this))
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "已添加：$name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "添加失败：名称或链接已存在 / 为空", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class SourceAdapter(
        private val items: List<RssFetchManager.RssSource>,
        private var selectedUrl: String,
        private val themeAccent: Int,
        private val onToggle: (RssFetchManager.RssSource, Boolean) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvCategory: TextView = view.findViewById(R.id.tv_category)
            val tvName: TextView = view.findViewById(R.id.tv_source_name)
            val tvUrl: TextView = view.findViewById(R.id.tv_source_url)
            val cb: CheckBox = view.findViewById(R.id.cb_source)
        }

        private var lastCategory = ""

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_news_source, parent, false)
            // CheckBox 按钮着色
            val cb = view.findViewById<CheckBox>(R.id.cb_source)
            cb.buttonTintList = android.content.res.ColorStateList.valueOf(themeAccent)
            // 分类标题文字色
            val tvCat = view.findViewById<TextView>(R.id.tv_category)
            tvCat.setTextColor(themeAccent)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            // 分类标题（只在分类变化时显示）
            if (item.category != lastCategory) {
                lastCategory = item.category
                holder.tvCategory.text = item.category
                holder.tvCategory.visibility = View.VISIBLE
            } else {
                holder.tvCategory.visibility = View.GONE
            }
            holder.tvName.text = item.name
            holder.tvUrl.text = item.url

            val isChecked = item.url == selectedUrl
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = isChecked
            holder.cb.setOnCheckedChangeListener { _, checked ->
                onToggle(item, checked)
            }
            holder.itemView.setOnClickListener {
                holder.cb.isChecked = !isChecked
            }
        }

        override fun getItemCount() = items.size
    }

    /** 递归应用主题色到 View 树（替换硬编码的 Tiffany 蓝） */
    private fun applyAccentToViewTree(view: View, accent: Int) {
        val tintList = android.content.res.ColorStateList.valueOf(accent)
        val tiffany = 0xFF81D8D0.toInt()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyAccentToViewTree(view.getChildAt(i), accent)
            }
        }
        // TextView 文字色
        val defaultColor = (view as? TextView)?.textColors?.defaultColor ?: 0
        if (defaultColor == tiffany) (view as? TextView)?.setTextColor(accent)
        // 背景着色
        val bgTint = try { view.backgroundTintList?.defaultColor ?: 0 } catch (_: Exception) { 0 }
        if (bgTint == tiffany) view.backgroundTintList = tintList
        // 实心背景色
        try {
            val bg = view.background
            if (bg is android.graphics.drawable.ColorDrawable && bg.color == tiffany) {
                view.setBackgroundColor(accent)
            }
        } catch (_: Exception) {}
        // MaterialButton
        if (view is com.google.android.material.button.MaterialButton) {
            try {
                if (view.strokeColor?.defaultColor == tiffany) {
                    view.strokeColor = tintList
                }
            } catch (_: Exception) {}
        }
        // CheckBox / RadioButton (via CompoundButton)
        if (view is android.widget.CompoundButton) {
            try {
                if (view.buttonTintList?.defaultColor == tiffany) {
                    view.buttonTintList = tintList
                }
            } catch (_: Exception) {}
        }
    }
}