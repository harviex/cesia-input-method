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
import android.util.TypedValue
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesia.input.R

/**
 * 长按符号切换键弹出的分类符号面板（PopupWindow）。
 * 符号分类借鉴雾凇拼音 symbols_v.yaml 的实用分组，数据内联，不依赖 Rime。
 * 点击符号 → commitText 上屏；面板保持打开可连点，点击外部关闭。
 * 符号网格用 RecyclerView + GridLayoutManager(10) 均分占满整行，同类多可滚动。
 */
class SymbolPanel(
    private val context: Context,
    private val anchor: View,
    private val accentColor: Int,
    private val onCommit: (String) -> Unit
) {
    // 分类 → 符号列表（借鉴 rime-ice symbols_v.yaml 的实用分组，已扩充）
    private val categories: List<Pair<String, List<String>>> = listOf(
        "常用" to listOf(
            "，", "。", "！", "？", "、", "；", "：", "“", "”", "‘", "’",
            "（", "）", "《", "》", "〈", "〉", "「", "」", "『", "』",
            "【", "】", "〔", "〕", "…", "—", "·", "～", "ˉ", "ˇ", "¨",
            "々", "〆", "〇", "｛", "｝", "［", "］", "￥", "＄", "＃", "＆", "＊"
        ),
        "标点" to listOf(
            "，", "。", "！", "？", "、", "；", "：", "“", "”", "‘", "’",
            "（", "）", "《", "》", "〈", "〉", "「", "」", "『", "』",
            "【", "】", "〔", "〕", "…", "—", "·", "～", "ˉ", "ˇ", "¨",
            "々", "〆", "〇", "｛", "｝", "［", "］", "｟", "｠", "«", "»", "‹", "›"
        ),
        "数学" to listOf(
            "＋", "－", "×", "÷", "＝", "≠", "≈", "≤", "≥", "±", "√", "∞",
            "％", "‰", "°", "∠", "⊥", "∥", "∈", "∉", "∩", "∪", "⊆", "⊇",
            "∑", "∏", "∫", "∬", "∂", "∇", "∀", "∃", "∵", "∴", "≡", "≌",
            "≅", "∝", "∮", "∧", "∨", "⊕", "⊗", "⊙", "⌈", "⌉", "⌊", "⌋",
            "∣", "∤", "≪", "≫", "⋮", "⋯", "∷", "≜", "≝", "∆", "∱", "∲"
        ),
        "箭头" to listOf(
            "→", "←", "↑", "↓", "↕", "↔", "↖", "↗", "↙", "↘",
            "⇒", "⇐", "⇑", "⇓", "↩", "↪", "➜", "➤", "➥", "➦",
            "↞", "↟", "↠", "↡", "↢", "↣", "↤", "↥", "↧", "↨",
            "⇄", "⇅", "⇆", "⇇", "⇈", "⇉", "⇊", "⇋", "⇌", "⇍", "⇎", "⇏"
        ),
        "货币" to listOf(
            "￥", "＄", "€", "£", "¥", "₩", "₽", "₺", "₪", "₫",
            "₴", "₦", "₡", "₢", "₣", "₤", "₥", "₧", "₨", "₩",
            "₪", "₫", "₭", "₮", "₯", "₰", "₱", "₲", "₳", "₴", "₵", "₶"
        ),
        "特殊" to listOf(
            "★", "☆", "●", "○", "■", "□", "◆", "◇", "♠", "♥", "♣", "♦",
            "♪", "♫", "✓", "✗", "✘", "§", "¶", "†", "‡", "¦", "⁄", "¨",
            "◐", "◑", "◒", "◓", "◯", "⬟", "⬠", "⬡", "⬢", "⬣", "⬤", "◈",
            "▣", "▤", "▥", "▦", "▧", "▨", "▩", "◉", "◊", "❂", "❖", "❣"
        ),
        "希腊" to listOf(
            "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ",
            "ν", "ξ", "ο", "π", "ρ", "σ", "τ", "υ", "φ", "χ", "ψ", "ω",
            "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ", "Μ",
            "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω"
        ),
        "罗马" to listOf(
            "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ",
            "Ⅺ", "Ⅻ", "Ⅼ", "Ⅽ", "Ⅾ", "Ⅿ", "ⅰ", "ⅱ", "ⅲ", "ⅳ",
            "ⅴ", "ⅵ", "ⅶ", "ⅷ", "ⅸ", "ⅹ", "ⅺ", "ⅻ", "ⅼ", "ⅽ", "ⅾ", "ⅿ"
        ),
        "Emoji" to listOf(
            "😀", "😁", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤔",
            "😅", "😭", "😡", "👍", "👎", "👏", "🙏", "💪", "🔥", "❤️",
            "✨", "🌟", "🌹", "🌈", "☀️", "🌙", "⭐", "💡", "⚡", "🔔",
            "🎉", "🎊", "💯", "✅", "❌", "⭕", "❗", "❓", "💤", "🌸",
            "🌿", "🍎", "🍊", "🍉", "🍓", "🍔", "🍜", "☕", "🍻", "🚀"
        )
    )

    private var popup: PopupWindow? = null
    private var currentCatIndex = 0
    private var grid: RecyclerView? = null

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

        tabContainer.removeAllViews()
        categories.forEachIndexed { idx, (name, _) ->
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
                    fillGrid(idx)
                }
            }
            tabContainer.addView(tab)
        }

        fillGrid(currentCatIndex)

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

    private fun fillGrid(catIndex: Int) {
        val symbols = categories[catIndex].second
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
            holder.tv.setOnClickListener { onCommit(sym) }
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
