package com.cesia.input

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.cesia.input.R
import kotlin.math.abs

/**
 * 长按符号切换键(-100，副字符"符库")弹出的分类符号面板（PopupWindow）。
 * 分类借鉴雾凇拼音 symbols_v.yaml 的实用分组，另增「网络」类。
 * 点击符号 → commitText 上屏；面板保持打开可连点，点击外部关闭。
 * 「常用」类按点击频率动态排序；标签按使用频率自动调整顺序；
 * 「常用」标签长按清空频率；符号长按弹出菜单（取消/设为常用）。
 */
class SymbolPanel(
    private val context: Context,
    private val anchor: View,
    private val accentColor: Int,
    private val onCommit: (String) -> Unit
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cesia_symbol_freq", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 各类符号（源自 rime-ice symbols_v.yaml 实用部分；网络/序号/制表/线条为新增/整合）
    private val allCategories: List<Pair<String, List<String>>> = listOf(
        "网络" to listOf(
            "@", "#", "&", "*", "/", "\\", "~", "_", "-", "+", "=",
            "|", "<", ">", "`", "^", "%", "(", ")", "[", "]", "{", "}",
            ";", ":", ",", ".", "!", "?", "\"", "'", "￥", "＄", "§",
            "¶", "°", "•", "·", "…", "—", "©", "®", "™"
        ),
        "标点" to listOf(
            "。", "．", "，", "、", "：", "；", "！", "‼", "？", "⁇",
            "「", "」", "『", "』", "“", "”", "‘", "’", "（", "）",
            "《", "》", "〈", "〉", "【", "】", "〖", "〗", "〔", "〕",
            "［", "］", "｛", "｝", "—", "……", "～", "·", "・", "‐",
            "‑", "–", "—", "‥", "′", "″", "‵", "‹", "›", "«", "»",
            "※", "†", "‡", "•", "‣", "⁄", "⸺"
        ),
        "数学" to listOf(
            "±", "÷", "×", "∈", "∏", "∑", "－", "＋", "＜", "≮",
            "＝", "≠", "＞", "≯", "∕", "√", "∝", "∞", "∟", "∠",
            "∥", "¬", "⊕", "∧", "∨", "∩", "∪", "∫", "∮", "∴",
            "∵", "∷", "∽", "≈", "≌", "≒", "≡", "≤", "≥", "≦",
            "≧", "⊖", "⊗", "⊙", "⊥", "⊿", "㏑", "㏒", "π", "°",
            "℃", "℉", "‰", "‱", "☰", "☱", "☲", "☳", "☴", "☵", "☶", "☷"
        ),
        "箭头" to listOf(
            "↑", "↓", "←", "→", "↕", "↔", "↖", "↗", "↙", "↘",
            "↚", "↛", "↮", "↜", "↝", "↞", "↟", "↠", "↡", "↢",
            "↣", "↤", "↥", "↧", "↨", "↩", "↪", "↫", "↬", "⇄",
            "⇅", "⇆", "⇇", "⇈", "⇉", "⇊", "⇋", "⇌", "⇐", "⇑",
            "⇒", "⇓", "⇔", "➔", "➙", "➚", "➛", "➜", "➝", "➞",
            "➟", "➠", "➡", "➢", "➣", "➤", "➥", "➦", "➧", "➨"
        ),
        "货币" to listOf(
            "￥", "¥", "¤", "￠", "¢", "＄", "$", "￡", "£", "฿",
            "₠", "₡", "₢", "₣", "₤", "₥", "₦", "₧", "₩", "₪",
            "₫", "€", "₭", "₮", "₯", "₰", "₱", "₲", "₳", "₴",
            "₵", "₶", "₷", "₸", "₹", "₺", "₨", "﷼"
        ),
        "特殊" to listOf(
            "★", "☆", "⛤", "⛥", "⛦", "⛧", "✡", "❋", "❊", "❉",
            "❈", "❇", "❆", "❅", "❄", "❃", "❁", "❀", "✿",
            "✾", "✽", "✼", "✻", "✺", "✹", "✸", "✷", "✶", "✵",
            "✴", "✳", "✲", "✱", "✰", "✯", "✮", "✭", "✬", "✫",
            "✪", "✩", "✧", "✦", "█", "▓", "▒", "░", "▚", "▜"
        ),
        "几何" to listOf(
            "■", "□", "▢", "▣", "▤", "▥", "▦", "▧", "▨", "▩",
            "▪", "▫", "▬", "▭", "▮", "▯", "▰", "▱", "▲", "△",
            "▶", "▷", "▸", "▹", "►", "▻", "▼", "▽", "◀", "◁",
            "◆", "◇", "◈", "◉", "◊", "○", "◎", "●", "◐", "◑",
            "◒", "◓", "◗", "◘", "◚", "◜", "◝", "◞", "◟", "◢"
        ),
        "希腊" to listOf(
            "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ",
            "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "σ", "τ", "υ",
            "φ", "χ", "ψ", "ω", "Α", "Β", "Γ", "Δ", "Ε", "Ζ",
            "Η", "Θ", "Ι", "Κ", "Λ", "Μ", "Ν", "Ξ", "Ο", "Π",
            "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω"
        ),
        "罗马" to listOf(
            "ⅰ", "ⅱ", "ⅲ", "ⅳ", "ⅴ", "ⅵ", "ⅶ", "ⅷ", "ⅸ", "ⅹ",
            "ⅺ", "ⅻ", "ⅼ", "ⅽ", "ⅾ", "ⅿ", "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ",
            "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "Ⅺ", "Ⅻ", "Ⅼ", "Ⅽ",
            "Ⅾ", "Ⅿ"
        ),
        "数字" to listOf(
            "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
            "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
            "㉑", "㉒", "㉓", "㉔", "㉕", "㉖", "㉗", "㉘", "㉙", "㉚",
            "⓪", "⓿", "❶", "❷", "❸", "❹", "❺", "❻", "❼", "❽",
            "❾", "❿", "⑴", "⑵", "⑶", "⑷", "⑸", "⑹", "⑺", "⑻",
            "⑼", "⑽", "⒈", "⒉", "⒊", "⒋", "⒌", "⒍", "⒎", "⒏", "⒐", "⒑"
        ),
        "序号" to listOf(
            "㊀", "㊁", "㊂", "㊃", "㊄", "㊅", "㊆", "㊇", "㊈", "㊉",
            "㈠", "㈡", "㈢", "㈣", "㈤", "㈥", "㈦", "㈧", "㈨", "㈩",
            "㈪", "㈫", "㈬", "㈭", "㈮", "㈯", "㈰", "㈱", "㈲", "㈳",
            "🅰", "🅱", "🅲", "🅳", "🅴", "🅵", "🅶", "🅷", "🅸", "🅹", "🅺", "🅻", "🅼", "🅽", "🅾", "🅿",
            "ⓐ", "ⓑ", "ⓒ", "ⓓ", "ⓔ", "ⓕ", "ⓖ", "ⓗ", "ⓘ", "ⓙ", "ⓚ", "ⓛ", "ⓜ", "ⓝ", "ⓞ", "ⓟ", "ⓠ", "ⓡ", "ⓢ", "ⓣ", "ⓤ", "ⓥ", "ⓦ", "ⓧ", "ⓨ", "ⓩ"
        ),
        "单位" to listOf(
            "℃", "℉", "°", "％", "‰", "‱", "Å", "㎜", "㎝", "㎞",
            "㎏", "㎡", "㏄", "㎎", "㎐", "㎑", "㎒", "㎓", "㎖", "㎗",
            "㎘", "㏔", "㏕", "㎢", "㎦", "㎪", "㎫", "㎰", "㎴", "㎺",
            "㎭", "㎮", "㎯", "㏛", "㎩", "㎉"
        ),
        "线条" to listOf(
            "─", "│", "┌", "┐", "└", "┘", "├", "┤", "┬", "┴",
            "┼", "═", "║", "╔", "╗", "╚", "╝", "╠", "╣", "╦",
            "╩", "╬", "▏", "▎", "▍", "▌", "▋", "▊", "▉", "▔",
            "▕", "▖", "▗", "▘", "▝", "▞", "▟"
        ),
        "Emoji" to listOf(
            "😀", "😁", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤔",
            "😅", "😭", "😡", "👍", "👎", "👏", "🙏", "💪", "🔥", "❤️",
            "✨", "🌟", "🌹", "🌈", "☀️", "🌙", "⭐", "💡", "⚡", "🔔",
            "🎉", "🎊", "💯", "✅", "❌", "⭕", "❗", "❓", "💤", "🌸",
            "🌿", "🍎", "🍊", "🍉", "🍓", "🍔", "🍜", "☕", "🍻", "🚀",
            "👌", "😱", "😮", "😰", "💩", "⚽", "🐱", "🐶", "🌍", "📱",
            "☀", "☁", "⛅", "⛈", "☂", "☔", "☃", "⛄", "⛇",
            "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓"
        )
    )

    private val commonBase = listOf(
        "，", "。", "！", "？", "、", "；", "：", "“", "”", "（",
        "）", "《", "》", "…", "—", "·", "～", "@", "#", "&",
        "*", "→", "←", "↑", "↓", "√", "×", "÷", "＝", "≠",
        "≈", "≤", "≥", "★", "☆", "●", "○", "■", "□", "€", "£", "¥", "✓", "✗"
    )

    private var popup: PopupWindow? = null
    private var currentCatIndex = 0
    private var grid: RecyclerView? = null
    private var tabContainer: ViewGroup? = null

    private fun loadFreq(): MutableMap<String, Int> {
        val json = prefs.getString("freq", null) ?: return mutableMapOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableMap<String, Int>>() {}.type)
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveFreq(map: MutableMap<String, Int>) {
        prefs.edit().putString("freq", gson.toJson(map)).apply()
    }

    private fun commonSymbols(): List<String> {
        val freq = loadFreq()
        if (freq.isEmpty()) return commonBase
        val top = freq.entries.sortedByDescending { it.value }.take(50).map { it.key }
        val result = top.toMutableList()
        for (s in commonBase) {
            if (result.size >= 60) break
            if (!result.contains(s)) result.add(s)
        }
        return result
    }

    private fun bumpSymbol(symbol: String) {
        val freq = loadFreq()
        freq[symbol] = (freq[symbol] ?: 0) + 1
        saveFreq(freq)
    }

    // 标签顺序：常用固定首位，其余按该类符号累计点击频率降序
    private fun orderedCategories(): List<Pair<String, List<String>>> {
        val freq = loadFreq()
        val rest = allCategories.sortedByDescending { (_, syms) ->
            syms.sumOf { freq[it] ?: 0 }
        }
        return listOf("常用" to commonSymbols()) + rest
    }

    fun show() {
        if (popup?.isShowing == true) {
            popup?.dismiss()
            popup = null
            return
        }
        val content = LayoutInflater.from(context).inflate(R.layout.symbol_panel, null)
        // 用 SwipeFrameLayout 包裹，拦截横向滑动切类别（不被内部 RecyclerView 吞手势）
        val view = SwipeFrameLayout(context).apply {
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val tabStrip = content.findViewById<HorizontalScrollView>(R.id.symbol_tabs)
        tabContainer = tabStrip.findViewById(R.id.symbol_tab_container)
        grid = content.findViewById(R.id.symbol_grid)
        grid?.layoutManager = GridLayoutManager(context, 10)

        buildTabs()
        val cats = orderedCategories()
        // 保持 currentCatIndex 指向同名分类（顺序变化后索引会变）
        val safeIndex = currentCatIndex.coerceIn(0, cats.lastIndex)
        selectCategory(safeIndex, cats)

        popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            showAtLocation(anchor, Gravity.BOTTOM or Gravity.START, 0, 0)
        }

        // 左右滑动切换符号类别（同全键盘/T9 切换的滑动方案：水平>100px 且垂直<80px）
        (view as SwipeFrameLayout).onSwipe = { dir ->
            // dir>0 右滑(手指右拖)→看左侧相邻类别(currentCatIndex-1)；dir<0 左滑→右侧(+1)
            val c = orderedCategories()
            val next = if (dir > 0) currentCatIndex - 1 else currentCatIndex + 1
            val ni = next.coerceIn(0, c.lastIndex)
            if (ni != currentCatIndex) {
                buildTabs()
                selectCategory(ni, c)
            }
        }
    }

    private fun buildTabs() {
        val cats = orderedCategories()
        tabContainer?.removeAllViews()
        cats.forEachIndexed { idx, (name, _) ->
            val tab = TextView(context).apply {
                text = name
                setPadding(dp(12), dp(8), dp(12), dp(8))
                textSize = 14f
                gravity = Gravity.CENTER
                if (idx == currentCatIndex) {
                    setTextColor(Color.WHITE)
                    background = makeTabBg(accentColor)
                } else {
                    setTextColor(0xFF666666.toInt())
                    background = null
                }
                setOnClickListener { selectCategory(idx, cats) }
                // 「常用」标签长按清空频率
                if (name == "常用") {
                    setOnLongClickListener {
                        clearFreq()
                        true
                    }
                }
            }
            tabContainer?.addView(tab)
        }
    }

    private fun clearFreq() {
        prefs.edit().remove("freq").apply()
        // 重建标签并回到常用类
        currentCatIndex = 0
        buildTabs()
        val cats = orderedCategories()
        fillGrid(cats[0].second)
    }

    private fun selectCategory(idx: Int, cats: List<Pair<String, List<String>>>) {
        currentCatIndex = idx
        // 刷新标签高亮
        tabContainer?.let { container ->
            for (i in 0 until container.childCount) {
                val t = container.getChildAt(i) as TextView
                if (i == idx) {
                    t.setTextColor(Color.WHITE)
                    t.background = makeTabBg(accentColor)
                } else {
                    t.setTextColor(0xFF666666.toInt())
                    t.background = null
                }
            }
        }
        fillGrid(cats[idx].second)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    fun isShowing(): Boolean = popup?.isShowing == true

    private fun fillGrid(symbols: List<String>) {
        grid?.adapter = SymbolAdapter(symbols)
    }

    private inner class SymbolAdapter(private val symbols: List<String>) :
        RecyclerView.Adapter<SymbolAdapter.VH>() {

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(context).apply {
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(12))
                background = makeKeyBg(0xFFF2F2F2.toInt())
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val sym = symbols[position]
            holder.tv.text = sym
            holder.tv.setOnClickListener {
                onCommit(sym)
                bumpSymbol(sym)
            }
            holder.tv.setOnLongClickListener {
                showSymbolContextMenu(holder.tv, sym)
                true
            }
        }

        override fun getItemCount(): Int = symbols.size
    }

    // 符号长按菜单：常用类显示「取消常用」，其他类显示「设为常用」
    private fun showSymbolContextMenu(anchorView: View, symbol: String) {
        val isCommon = (currentCatIndex == 0)
        val menuView = LayoutInflater.from(context)
            .inflate(R.layout.symbol_context_menu, null)
        val title = menuView.findViewById<TextView>(R.id.menu_title)
        val action = menuView.findViewById<TextView>(R.id.menu_action)
        title.text = symbol
        action.text = if (isCommon) "取消常用" else "设为常用"

        val menu = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        }
        action.setOnClickListener {
            val freq = loadFreq()
            if (isCommon) {
                freq.remove(symbol) // 取消常用
            } else {
                freq[symbol] = (freq[symbol] ?: 0) + 1000 // 设为常用（推到最前）
            }
            saveFreq(freq)
            if (isCommon) {
                // 刷新当前常用类
                fillGrid(commonSymbols())
            }
            menu.dismiss()
        }
        menu.showAsDropDown(anchorView)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private fun makeTabBg(color: Int): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun makeKeyBg(color: Int): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }
    }
}

/**
 * 包裹符号面板内容，拦截横向滑动切类别（同 CesiaKeyboardView 的滑动方案）。
 * 横向滑动(dx>100 且 dy<80)触发 onSwipe(dir<0 左滑 / >0 右滑)，纵向滑动放行给子 view。
 */
private class SwipeFrameLayout(context: Context) : FrameLayout(context) {
    var onSwipe: ((dir: Int) -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var swiped = false
    private val threshold = 100f
    private val maxYDrift = 80f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x; startY = ev.y; swiped = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiped) {
                    val dx = ev.x - startX
                    val adx = abs(dx)
                    val ady = abs(ev.y - startY)
                    if (adx > threshold && ady < maxYDrift) {
                        swiped = true
                        onSwipe?.invoke(if (dx < 0) -1 else 1)
                        return true  // 消费本次滑动，防止误触子 view
                    }
                }
            }
        }
        return false
    }
}
