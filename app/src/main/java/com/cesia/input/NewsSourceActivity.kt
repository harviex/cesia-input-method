package com.cesia.input

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

/**
 * RSS 源选择页面（轻量化版）
 * 所有可用源按分类展示，点击选择一个源 → 立即抓取 → 写入缓存
 */
class NewsSourceActivity : AppCompatActivity() {

    private lateinit var adapter: SourceAdapter
    private val allSources = mutableListOf<RssFetchManager.RssSource>()
    private var selectedUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_source)

        // 应用动态主题色
        val accent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())
        applyAccentToViewTree(window.decorView, accent)

        // 获取所有源（预置 + 自定义）
        allSources.clear()
        allSources.addAll(RssFetchManager.getAllSources(this))

        // 当前已选中的源
        val current = RssFetchManager.getSelectedSource(this)
        selectedUrl = current?.url ?: ""

        // RecyclerView
        val rv = findViewById<RecyclerView>(R.id.rv_sources)
        adapter = SourceAdapter(allSources, selectedUrl) { source ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(this, "正在抓取：${source.name}...", Toast.LENGTH_SHORT).show()
            }
            CoroutineScope(Dispatchers.IO).launch {
                val success = RssFetchManager.fetchAndCache(this@NewsSourceActivity, source)
                runOnUiThread {
                    if (success) {
                        Toast.makeText(
                            this@NewsSourceActivity,
                            "✅ ${source.name} 抓取成功",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    } else {
                        Toast.makeText(
                            this@NewsSourceActivity,
                            "❌ ${source.name} 抓取失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    class SourceAdapter(
        private val items: List<RssFetchManager.RssSource>,
        private var selectedUrl: String,
        private val onSelect: (RssFetchManager.RssSource) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

        class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val tvCategory: TextView = view.findViewById(R.id.tv_category)
            val tvName: TextView = view.findViewById(R.id.tv_source_name)
            val tvUrl: TextView = view.findViewById(R.id.tv_source_url)
            val cb: CheckBox = view.findViewById(R.id.cb_source)
        }

        private var lastCategory = ""

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_news_source, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            if (item.category != lastCategory) {
                lastCategory = item.category
                holder.tvCategory.text = item.category
                holder.tvCategory.visibility = android.view.View.VISIBLE
            } else {
                holder.tvCategory.visibility = android.view.View.GONE
            }
            holder.tvName.text = item.name
            holder.tvUrl.text = item.url
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = item.url == selectedUrl
            holder.cb.setOnCheckedChangeListener { _, _ -> onSelect(item) }
            holder.itemView.setOnClickListener { holder.cb.isChecked = true }
        }

        override fun getItemCount() = items.size
    }

    private fun applyAccentToViewTree(view: android.view.View, accent: Int) {
        val tintList = android.content.res.ColorStateList.valueOf(accent)
        val tiffany = 0xFF81D8D0.toInt()
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyAccentToViewTree(view.getChildAt(i), accent)
            }
        }
        val defaultColor = (view as? android.widget.TextView)?.textColors?.defaultColor ?: 0
        if (defaultColor == tiffany) (view as? android.widget.TextView)?.setTextColor(accent)
        val bgTint = try { view.backgroundTintList?.defaultColor ?: 0 } catch (_: Exception) { 0 }
        if (bgTint == tiffany) view.backgroundTintList = tintList
        // Handle solid background color
        try {
            val bg = view.background
            if (bg is android.graphics.drawable.ColorDrawable && bg.color == tiffany) {
                view.setBackgroundColor(accent)
            }
        } catch (_: Exception) {}
        if (view is com.google.android.material.button.MaterialButton) {
            try {
                if (view.strokeColor?.defaultColor == tiffany) {
                    view.strokeColor = tintList
                }
            } catch (_: Exception) {}
        }
        if (view is android.widget.RadioButton) {
            try {
                if (view.buttonTintList?.defaultColor == tiffany) {
                    view.buttonTintList = tintList
                }
            } catch (_: Exception) {}
        }
    }
}
