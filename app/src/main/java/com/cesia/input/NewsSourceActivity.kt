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

/** 新闻源列表行：分类标题 或 具体源 */
sealed class Row {
    data class Category(val name: String) : Row()
    data class Source(val src: RssFetchManager.RssSource) : Row()
}

/**
 * RSS 源管理页面（参照智能写作菜单）
 * - 按分类分组展示（分类标题 + 该分类下的 RSS），每个分类标题右侧「+」可在该分类下添加
 * - 普通模式：列表项右侧 RadioButton 显示选中态（点条目 = 选中该源作为新闻源）；URL 默认隐藏
 * - 「显示 URL」按钮：切换整页 URL 显隐
 * - 「批量管理」：复选框 + 全选/取消/批量置顶(自定义源)/批量删除(自定义源)/已选数量
 * - 底部三个按钮并列：+ 添加 RSS / 显示 URL / 批量管理
 */
class NewsSourceActivity : AppCompatActivity() {

    private lateinit var adapter: SourceAdapter
    private val rows = mutableListOf<Row>()
    private var selectedUrl: String = ""
    private var themeAccent: Int = 0xFF81D8D0.toInt()
    private var showUrl: Boolean = false

    private var batchMode = false
    private val selectedBatch = mutableSetOf<String>()  // 以 url 为 key

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDarkLaunch = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_mode", 0) == 1
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (isDarkLaunch) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        setContentView(R.layout.activity_news_source)

        themeAccent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())
        val isDark = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_mode", 0) == 1

        findViewById<LinearLayout>(R.id.title_bar)?.setBackgroundColor(themeAccent)
        findViewById<TextView>(R.id.btn_close).setOnClickListener { finish() }

        // 底部三个并列按钮
        findViewById<TextView>(R.id.btn_add_custom).apply {
            setBackgroundColor(themeAccent)
            setOnClickListener { showAddCustomDialog(category = "自定义") }
        }
        findViewById<TextView>(R.id.btn_show_url).apply {
            setBackgroundColor(themeAccent)
            setOnClickListener { toggleUrl() }
        }
        findViewById<TextView>(R.id.btn_batch_manage).apply {
            setBackgroundColor(themeAccent)
            setOnClickListener { enterBatchMode() }
        }

        applyAccentToViewTree(window.decorView, themeAccent)
        if (isDark) applyDarkToView(window.decorView)

        selectedUrl = RssFetchManager.getSelectedSource(this)?.url ?: ""
        buildRows()

        // 批量栏按钮
        findViewById<TextView>(R.id.btn_batch_cancel).setOnClickListener { exitBatchMode() }
        findViewById<TextView>(R.id.btn_batch_all).setOnClickListener {
            selectedBatch.clear()
            for (r in rows) if (r is Row.Source) selectedBatch.add(r.src.url)
            adapter.notifyDataSetChanged()
            updateBatchCount()
        }
        findViewById<TextView>(R.id.btn_batch_pin).setOnClickListener {
            val customSel = selectedBatch.filter { url -> rows.any { it is Row.Source && (it as Row.Source).src.url == url && (it as Row.Source).src.category == "自定义" } }
            if (customSel.isEmpty()) {
                Toast.makeText(this, "置顶仅对自定义源生效", Toast.LENGTH_SHORT).show()
            } else {
                RssFetchManager.pinCustomSources(this, customSel)
                buildRows(); adapter.setData(rows); adapter.notifyDataSetChanged()
                Toast.makeText(this, "⤒ 已置顶 ${customSel.size} 个源", Toast.LENGTH_SHORT).show()
            }
            exitBatchMode()
        }
        findViewById<TextView>(R.id.btn_batch_delete).setOnClickListener {
            val customSel = selectedBatch.filter { url -> rows.any { it is Row.Source && (it as Row.Source).src.url == url && (it as Row.Source).src.category == "自定义" } }
            if (customSel.isEmpty()) {
                Toast.makeText(this, "仅自定义源可删除（预置源不可删）", Toast.LENGTH_SHORT).show()
            } else {
                var removed = 0
                for (url in customSel) {
                    val s = (rows.find { it is Row.Source && (it as Row.Source).src.url == url } as? Row.Source)?.src ?: continue
                    RssFetchManager.removeCustomSource(this, s.name, url)
                    removed++
                }
                buildRows(); selectedBatch.clear(); adapter.setData(rows); adapter.notifyDataSetChanged()
                Toast.makeText(this, "⊗ 已删除 $removed 个源", Toast.LENGTH_SHORT).show()
            }
            exitBatchMode()
        }

        val rv = findViewById<RecyclerView>(R.id.rv_sources)
        adapter = SourceAdapter(themeAccent, isDark,
            batchModeRef = { batchMode },
            showUrlRef = { showUrl },
            selectedUrlRef = { selectedUrl },
            selectedBatchRef = { selectedBatch },
            onSourceClick = { src ->
                if (batchMode) {
                    if (selectedBatch.contains(src.url)) selectedBatch.remove(src.url) else selectedBatch.add(src.url)
                    adapter.notifyDataSetChanged(); updateBatchCount()
                } else {
                    Toast.makeText(this, "正在抓取：${src.name}...", Toast.LENGTH_SHORT).show()
                    CoroutineScope(Dispatchers.IO).launch {
                        val success = RssFetchManager.fetchAndCache(this@NewsSourceActivity, src)
                        runOnUiThread {
                            if (success) {
                                RssFetchManager.saveSelectedSource(this@NewsSourceActivity, src)
                                selectedUrl = src.url
                                adapter.notifyDataSetChanged()
                                Toast.makeText(this@NewsSourceActivity, "✓ ${src.name} 已选中并抓取成功", Toast.LENGTH_SHORT).show()
                            } else {
                                adapter.notifyDataSetChanged()
                                Toast.makeText(this@NewsSourceActivity, "❌ ${src.name} 抓取失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            onCatAddClick = { category -> showAddCustomDialog(category = category) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        adapter.setData(rows)
    }

    /** 按分类展开为列表行（分类标题 + 源），顺序与 getAllSources 一致 */
    private fun buildRows() {
        rows.clear()
        val sources = RssFetchManager.getAllSources(this)
        var lastCat = ""
        for (s in sources) {
            if (s.category != lastCat) {
                rows.add(Row.Category(s.category))
                lastCat = s.category
            }
            rows.add(Row.Source(s))
        }
    }

    private fun updateBatchCount() {
        findViewById<TextView>(R.id.tv_batch_count).text = "已选 ${selectedBatch.size}"
    }

    private fun toggleUrl() {
        showUrl = !showUrl
        findViewById<TextView>(R.id.btn_show_url).text = if (showUrl) "隐藏 URL" else "显示 URL"
        adapter.notifyDataSetChanged()
    }

    private fun enterBatchMode() {
        batchMode = true
        selectedBatch.clear()
        findViewById<LinearLayout>(R.id.action_bar_batch).visibility = View.VISIBLE
        findViewById<TextView>(R.id.btn_batch_manage).visibility = View.GONE
        adapter.batchMode = true
        adapter.notifyDataSetChanged()
        updateBatchCount()
    }

    private fun exitBatchMode() {
        batchMode = false
        selectedBatch.clear()
        findViewById<LinearLayout>(R.id.action_bar_batch).visibility = View.GONE
        findViewById<TextView>(R.id.btn_batch_manage).visibility = View.VISIBLE
        adapter.batchMode = false
        adapter.notifyDataSetChanged()
        updateBatchCount()
    }

    /** 添加自定义 RSS 对话框（默认归类到指定分类，可改） */
    private fun showAddCustomDialog(category: String) {
        val catInput = EditText(this).apply {
            hint = "分类（如：自定义 / 科技）"
            setText(category)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(24, 16, 24, 16)
        }
        val nameInput = EditText(this).apply {
            hint = "名称（如：我的博客）"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(24, 24, 24, 24)
        }
        val urlInput = EditText(this).apply {
            hint = "RSS 链接（如：https://example.com/feed.xml）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(24, 16, 24, 16)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(catInput); addView(nameInput); addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle("➕ 添加自定义 RSS 源")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val cat = catInput.text.toString().trim().ifEmpty { "自定义" }
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (RssFetchManager.addCustomSource(this, name, url, cat)) {
                    buildRows(); adapter.setData(rows); adapter.notifyDataSetChanged()
                    Toast.makeText(this, "已添加：$name（分类：$cat）", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "添加失败：名称或链接已存在 / 为空", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class SourceAdapter(
        private val themeAccent: Int,
        private val isDark: Boolean,
        private val batchModeRef: () -> Boolean,
        private val showUrlRef: () -> Boolean,
        private val selectedUrlRef: () -> String,
        private val selectedBatchRef: () -> MutableSet<String>,
        private val onSourceClick: (RssFetchManager.RssSource) -> Unit,
        private val onCatAddClick: (String) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

        var batchMode: Boolean = false

        companion object {
            private const val TYPE_CATEGORY = 0
            private const val TYPE_SOURCE = 1
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val layoutCategory: View = view.findViewById(R.id.layout_category)
            val layoutSource: View = view.findViewById(R.id.layout_source)
            val tvCategory: TextView = view.findViewById(R.id.tv_category)
            val btnCatAdd: TextView = view.findViewById(R.id.btn_cat_add)
            val tvName: TextView = view.findViewById(R.id.tv_source_name)
            val tvUrl: TextView = view.findViewById(R.id.tv_source_url)
            val cb: CompoundButton = view.findViewById(R.id.cb_source)
        }

        override fun getItemViewType(position: Int): Int {
            return if (rowsSafe()[position] is Row.Category) TYPE_CATEGORY else TYPE_SOURCE
        }

        // rows 由外部持有，这里通过 Activity 引用不便；改为接收 list
        private var data: List<Row> = emptyList()
        fun setData(list: List<Row>) { data = list }
        private fun rowsSafe(): List<Row> = data

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_news_source, parent, false)
            view.findViewById<CompoundButton>(R.id.cb_source).buttonTintList =
                android.content.res.ColorStateList.valueOf(themeAccent)
            view.findViewById<TextView>(R.id.tv_category).setTextColor(themeAccent)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = data[position]
            when (item) {
                is Row.Category -> {
                    holder.layoutCategory.visibility = View.VISIBLE
                    holder.layoutSource.visibility = View.GONE
                    holder.tvCategory.text = item.name
                    holder.btnCatAdd.setTextColor(themeAccent)
                    holder.btnCatAdd.setOnClickListener { onCatAddClick(item.name) }
                }
                is Row.Source -> {
                    holder.layoutCategory.visibility = View.GONE
                    holder.layoutSource.visibility = View.VISIBLE
                    val src = item.src
                    holder.tvName.text = src.name
                    holder.tvName.setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF333333.toInt())
                    holder.tvUrl.text = src.url
                    holder.tvUrl.setTextColor(if (isDark) 0xFF888888.toInt() else 0xFF999999.toInt())
                    holder.tvUrl.visibility = if (showUrlRef()) View.VISIBLE else View.GONE

                    val isSelected = src.url == selectedUrlRef()
                    val inBatch = selectedBatchRef().contains(src.url)

                    holder.cb.visibility = if (batchMode) View.VISIBLE else View.GONE
                    if (batchMode) {
                        holder.itemView.setBackgroundColor(0)
                        holder.cb.setOnCheckedChangeListener(null)
                        holder.cb.isChecked = inBatch
                        holder.cb.setOnCheckedChangeListener { _, checked ->
                            val sel = selectedBatchRef()
                            if (checked) sel.add(src.url) else sel.remove(src.url)
                        }
                    } else {
                        // 普通模式：RadioButton 仅作选中态指示（不响应自身点击，点击由 itemView 统一处理选中新闻源）
                        holder.cb.setOnCheckedChangeListener(null)
                        holder.cb.isEnabled = false
                        holder.cb.isChecked = isSelected
                        if (isSelected) {
                            val fill = (themeAccent and 0x00FFFFFF) or 0x1A000000
                            holder.itemView.setBackgroundColor(fill)
                        } else {
                            holder.itemView.setBackgroundColor(0)
                        }
                    }
                    holder.itemView.setOnClickListener { onSourceClick(src) }
                }
            }
        }

        override fun getItemCount() = data.size
    }

    /** 递归应用主题色到 View 树 */
    private fun applyAccentToViewTree(view: View, accent: Int) {
        val tintList = android.content.res.ColorStateList.valueOf(accent)
        val tiffany = 0xFF81D8D0.toInt()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyAccentToViewTree(view.getChildAt(i), accent)
        }
        val defaultColor = (view as? TextView)?.textColors?.defaultColor ?: 0
        if (defaultColor == tiffany) (view as? TextView)?.setTextColor(accent)
        val bgTint = try { view.backgroundTintList?.defaultColor ?: 0 } catch (_: Exception) { 0 }
        if (bgTint == tiffany) view.backgroundTintList = tintList
        try {
            val bg = view.background
            if (bg is android.graphics.drawable.ColorDrawable && bg.color == tiffany) view.setBackgroundColor(accent)
        } catch (_: Exception) {}
        if (view is com.google.android.material.button.MaterialButton) {
            try {
                if (view.strokeColor?.defaultColor == tiffany) view.strokeColor = tintList
            } catch (_: Exception) {}
        }
        if (view is android.widget.CompoundButton) {
            try {
                if (view.buttonTintList?.defaultColor == tiffany) view.buttonTintList = tintList
            } catch (_: Exception) {}
        }
    }

    /** 暗色模式 */
    private fun applyDarkToView(root: android.view.View) {
        val darkSurface = 0xFF1E1E2E.toInt()
        val darkSurfaceAlt = 0xFF2A2A3E.toInt()
        val darkDivider = 0xFF3A3A4E.toInt()
        val darkText = 0xFFE0E0E0.toInt()
        val lightText = 0xFF333333.toInt()
        fun gray(v: Int) = (v shr 16) and 0xFF
        fun isGrayish(v: Int) = run { val r=(v shr 16) and 0xFF; val g=(v shr 8) and 0xFF; val b=v and 0xFF; r==g && g==b }
        fun solidColor(d: android.graphics.drawable.Drawable?): Int? {
            return when (d) {
                is android.graphics.drawable.ColorDrawable -> d.color
                is android.graphics.drawable.GradientDrawable -> try { d.color?.defaultColor } catch (_: Exception) { null }
                else -> null
            }
        }
        fun walk(v: android.view.View) {
            val sc = solidColor(v.background)
            if (sc != null && isGrayish(sc)) {
                val g = gray(sc)
                when {
                    g > 235 -> v.setBackgroundColor(darkSurface)
                    g in 215..235 -> v.setBackgroundColor(darkSurfaceAlt)
                    g in 195..215 -> v.setBackgroundColor(darkDivider)
                }
            }
            if (v is android.widget.TextView) {
                val bg = solidColor(v.background) ?: (v.parent as? android.view.View)?.let { solidColor(it.background) }
                val eff = bg ?: darkSurface
                v.setTextColor(if (gray(eff) < 128) darkText else lightText)
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }
}
