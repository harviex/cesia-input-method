package com.cesia.input

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.PopupWindow
import android.content.Context
import android.util.TypedValue
import android.view.inputmethod.InputConnection
import android.view.Gravity as AndroidGravity
import com.cesia.input.R

/**
 * 长按符号切换键弹出的分类符号面板（PopupWindow）。
 * 符号分类借鉴雾凇拼音 symbols_v.yaml 的实用分组，数据内联，不依赖 Rime。
 * 点击符号 → commitText 上屏；面板保持打开可连点，点击外部关闭。
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
            "【", "】", "〔", "〕", "「", "」", "『", "』", "…", "—",
            "·", "～", "ˉ", "ˇ", "¨", "‘", "’", "「", "」", "￥", "＄"
        ),
        "标点" to listOf(
            "，", "。", "！", "？", "、", "；", "：", "“", "”", "‘", "’",
            "（", "）", "《", "》", "〈", "〉", "「", "」", "『", "』",
            "【", "】", "〔", "〕", "…", "—", "·", "～", "ˉ", "ˇ", "¨",
            "々", "〆", "〇", "「", "」", "『", "』", "｛", "｝", "［", "］"
        ),
        "数学" to listOf(
            "＋", "－", "×", "÷", "＝", "≠", "≈", "≤", "≥", "±", "√", "∞",
            "％", "‰", "°", "∠", "⊥", "∥", "∈", "∉", "∩", "∪", "⊆", "⊇",
            "∑", "∏", "∫", "∬", "∂", "∇", "∀", "∃", "∵", "∴", "≡", "≌",
            "≅", "∝", "∮", "∝", "∧", "∨", "⊕", "⊗", "⊙", "⊥", "⌈", "⌉",
            "⌊", "⌋", "∣", "∤", "≪", "≫", "⋮", "⋯", "∷", "≜", "≝", "∆"
        ),
        "箭头" to listOf(
            "→", "←", "↑", "↓", "↕", "↔", "↖", "↗", "↙", "↘",
            "⇒", "⇐", "⇑", "⇓", "↩", "↪", "➜", "➤", "➥", "➦",
            "↞", "↟", "↠", "↡", "↢", "↣", "↤", "↥", "↧", "↨",
            "⇄", "⇅", "⇆", "⇇", "⇈", "⇉", "⇊", "⇋", "⇌", "⇍", "⇎", "⇏"
        ),
        "货币" to listOf(
            "￥", "＄", "€", "£", "¥", "₩", "₽", "₺", "₪", "₫",
            "₴", "₦", "₡", "₢", "₣", "₤", "₥", "₦", "₧", "₨",
            "₩", "₪", "₫", "₭", "₮", "₯", "₰", "₱", "₲", "₳", "₴", "₵"
        ),
        "特殊" to listOf(
            "★", "☆", "●", "○", "■", "□", "◆", "◇", "♠", "♥", "♣", "♦",
            "♪", "♫", "✓", "✗", "✘", "§", "¶", "†", "‡", "¦", "⁄", "¨",
            "●", "◐", "◑", "◒", "◓", "◯", "⬟", "⬠", "⬡", "⬢", "⬣", "⬤",
            "▣", "▤", "▥", "▦", "▧", "▨", "▩", "◈", "◉", "◊", "❂", "❖"
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

    fun show() {
        if (popup?.isShowing == true) {
            popup?.dismiss()
            popup = null
            return
        }
        val view = LayoutInflater.from(context).inflate(R.layout.symbol_panel, null)
        val tabStrip = view.findViewById<HorizontalScrollView>(R.id.symbol_tabs)
        val tabContainer = tabStrip.findViewById<ViewGroup>(R.id.symbol_tab_container)
        val grid = view.findViewById<GridLayout>(R.id.symbol_grid)

        tabContainer.removeAllViews()
        categories.forEachIndexed { idx, (name, _) ->
            val tab = TextView(context).apply {
                text = name
                setPadding(dp(12), dp(8), dp(12), dp(8))
                textSize = 14f
                gravity = AndroidGravity.CENTER
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
                    fillGrid(grid, idx)
                }
            }
            tabContainer.addView(tab)
        }

        fillGrid(grid, currentCatIndex)

        popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            showAtLocation(anchor, AndroidGravity.BOTTOM or AndroidGravity.START, 0, 0)
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    fun isShowing(): Boolean = popup?.isShowing == true

    private fun fillGrid(grid: GridLayout, catIndex: Int) {
        grid.removeAllViews()
        grid.columnCount = 8
        val symbols = categories[catIndex].second
        symbols.forEach { sym ->
            val btn = TextView(context).apply {
                text = sym
                textSize = 20f
                gravity = AndroidGravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(12))
                background = makeKeyBg(0xFFF2F2F2.toInt())
                setOnClickListener { onCommit(sym) }
            }
            // 关键：显式 LayoutParams + columnSpec weight=1f，让 8 列均分父宽，
            // 避免窄字符集中在左侧（默认 WRAP_CONTENT 导致的布局异常）。
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            btn.layoutParams = lp
            grid.addView(btn)
        }
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
