package com.cesia.input

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

/**
 * 长按符号切换键弹出的分类符号面板（PopupWindow）。
 * 分类借鉴雾凇拼音 symbols_v.yaml 的实用分组（已剔除数百个生僻字母变体），
 * 另增「网络」类（@ / 斜线等常用网络符号）。
 * 点击符号 → commitText 上屏；面板保持打开可连点，点击外部关闭。
 * 符号网格用 RecyclerView + GridLayoutManager(10) 均分占满整行，同类多可滚动。
 * 「常用」类按点击频率动态排序（SharedPreferences 记录）。
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

    // 各类符号（源自 rime-ice symbols_v.yaml 实用部分；网络类为新增）
    private val allCategories: List<Pair<String, List<String>>> = listOf(
        "网络" to listOf(
            "@", "#", "&", "*", "/", "\\", "~", "_", "-", "+", "=",
            "|", "<", ">", "`", "^", "%", "(", ")", "[", "]", "{", "}",
            ";", ":", ",", ".", "!", "?", "\"", "'", "￥", "＄", "§",
            "¶", "°", "•", "·", "…", "—", "©", "®", "™", "#"
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
            "℃", "℉", "‰", "‱"
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
            "❈", "❇", "❆", "❅", "❄", "❃", "❂", "❁", "❀", "✿",
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
        "单位" to listOf(
            "℃", "℉", "°", "％", "‰", "‱", "Å", "㎜", "㎝", "㎞",
            "㎏", "㎡", "㏄", "㎎", "㎐", "㎑", "㎒", "㎓", "㎖", "㎗",
            "㎘", "㏔", "㏕", "㎢", "㎦", "㎪", "㎫", "㎰", "㎴", "㎺",
            "㎭", "㎮", "㎯", "㏛", "㎩", "㎉"
        ),
        "天气" to listOf("☀", "☁", "⛅", "⛈", "☂", "☔", "☃", "⛄", "⛇"),
        "星座" to listOf(
            "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑",
            "♒", "♓"
        ),
        "八卦" to listOf("☰", "☱", "☲", "☳", "☴", "☵", "☶", "☷"),
        "俄文" to listOf(
            "а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и",
            "й", "к", "л", "м", "н", "о", "п", "р", "с", "т",
            "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь",
            "э", "ю", "я", "А", "Б", "В", "Г", "Д", "Е", "Ё",
            "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О", "П",
            "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Э", "Ю", "Я"
        ),
        "Emoji" to listOf(
            "😀", "😁", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤔",
            "😅", "😭", "😡", "👍", "👎", "👏", "🙏", "💪", "🔥", "❤️",
            "✨", "🌟", "🌹", "🌈", "☀️", "🌙", "⭐", "💡", "⚡", "🔔",
            "🎉", "🎊", "💯", "✅", "❌", "⭕", "❗", "❓", "💤", "🌸",
            "🌿", "🍎", "🍊", "🍉", "🍓", "🍔", "🍜", "☕", "🍻", "🚀",
            "👌", "😱", "😮", "😰", "💩", "⚽", "🐱", "🐶", "🌍", "📱"
        )
    )

    // 「常用」类基础候选（频率为空时兜底）
    private val commonBase = listOf(
        "，", "。", "！", "？", "、", "；", "：", "“", "”", "（",
        "）", "《", "》", "…", "—", "·", "～", "@", "#", "&",
        "*", "→", "←", "↑", "↓", "√", "×", "÷", "＝", "≠",
        "≈", "≤", "≥", "★", "☆", "●", "○", "■", "□", "€", "£", "¥", "✓", "✗"
    )

    // 动态分类：第一个是「常用」（按频率），其余为固定分类
    private val categories: List<Pair<String, List<String>>>
        get() = listOf("常用" to commonSymbols()) + allCategories

    private var popup: PopupWindow? = null
    private var currentCatIndex = 0
    private var grid: RecyclerView? = null

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
        // 按点击次数降序取前 50，不足则用 base 补齐
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

    fun show() {
        if (popup?.isShowing == true) {
            popup?.dismiss()
            popup = null
            return
        }
        val view = LayoutInflater.from(context).inflate(R.layout.symbol_panel, null)
        val tabStrip = view.findViewById<HorizontalScrollView>(R.id.symbol_tabs)
        val tabContainer = tabStrip.findViewById<ViewGroup>(R.id.symbol_tab_container)
        grid = view.findViewById(R.id.symbol_grid)
        grid?.layoutManager = GridLayoutManager(context, 10)

        val cats = categories
        tabContainer.removeAllViews()
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
                setOnClickListener {
                    currentCatIndex = idx
                    for (i in 0 until tabContainer.childCount) {
                        val t = tabContainer.getChildAt(i) as TextView
                        if (i == idx) {
                            t.setTextColor(Color.WHITE)
                            t.background = makeTabBg(accentColor)
                        } else {
                            t.setTextColor(0xFF666666.toInt())
                            t.background = null
                        }
                    }
                    fillGrid(cats[idx].second)
                }
            }
            tabContainer.addView(tab)
        }

        fillGrid(cats[currentCatIndex].second)

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
                bumpSymbol(sym) // 记录点击频率
            }
        }

        override fun getItemCount(): Int = symbols.size
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
