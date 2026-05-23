package com.cesia.input.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet

class CustomKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : KeyboardView(context, attrs, defStyleAttr) {

    private val subsidiaryPaint = Paint().apply {
        color = 0xFF888888.toInt()
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }

    // 副字符语言模式: "cn"=中文, "en"=英文, "jp"=日文
    var subsidiaryLangMode: String = "cn"

    // 是否显示副字符（所有按键都显示）
    var showSubsidiaryChars: Boolean = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!showSubsidiaryChars) return

        val keys = keyboard?.keys ?: return
        for (key in keys) {
            // 优先使用 XML 中定义的 popupCharacters
            if (!key.popupCharacters.isNullOrEmpty()) {
                val symbol = key.popupCharacters[0].toString()
                drawSubsidiary(canvas, key, symbol)
            } else if (key.codes.isNotEmpty()) {
                // 对于没有 popupCharacters 的按键，根据语言模式显示副字符
                val code = key.codes[0]
                val subsidiary = getSubsidiaryForCode(code)
                if (subsidiary != null) {
                    drawSubsidiary(canvas, key, subsidiary)
                }
            }
        }
    }

    private fun drawSubsidiary(canvas: Canvas, key: Keyboard.Key, symbol: String) {
        val textSize = (key.height * 0.35f).coerceIn(16f, 24f)
        subsidiaryPaint.textSize = textSize
        subsidiaryPaint.color = if (subsidiaryLangMode == "cn") 0xFF4488FF.toInt() else 0xFF888888.toInt()

        val x = key.x + key.width - 4f
        val y = key.y + textSize + 2f

        canvas.drawText(symbol, x, y, subsidiaryPaint)
    }

    private fun getSubsidiaryForCode(code: Int): String? {
        return when (subsidiaryLangMode) {
            "cn" -> when (code) {
                // 数字键 → 中文数字
                49 to "①"; 50 to "②"; 51 to "③"; 52 to "④"; 53 to "⑤"
                54 to "⑥"; 55 to "⑦"; 56 to "⑧"; 57 to "⑨"; 48 to "⓪"
                // 字母键 → 拼音声调
                113 to "ā"; 119 to "ē"; 101 to "ī"; 114 to "ō"; 116 to "ū"
                121 to "ǖ"; 117 to "ú"; 105 to "ì"; 111 to "ǒ"; 112 to "ǜ"
                97 to "ɑ"; 115 to "σ"; 100 to "δ"; 102 to "φ"; 103 to "γ"
                104 to "η"; 106 to "ξ"; 107 to "κ"; 108 to "λ"
                122 to "ζ"; 120 to "χ"; 99 to "ψ"; 118 to "ω"; 98 to "β"
                110 to "ν"; 109 to "μ"
                else -> null
            }
            "jp" -> when (code) {
                // 数字键 → 日文假名
                49 to "ぬ"; 50 to "ふ"; 51 to "あ"; 52 to "う"; 53 to "え"
                54 to "お"; 55 to "や"; 56 to "ゆ"; 57 to "よ"; 48 to "わ"
                // 字母键 → 假名
                113 to "た"; 119 to "て"; 101 to "い"; 114 to "す"; 116 to "か"
                121 to "ん"; 117 to "な"; 105 to "に"; 111 to "ら"; 112 to "せ"
                97 to "ち"; 115 to "と"; 100 to "し"; 102 to "は"; 103 to "き"
                104 to "く"; 106 to "ま"; 107 to "の"; 108 to "れ"
                122 to "つ"; 120 to "さ"; 99 to "そ"; 118 to "ひ"; 98 to "こ"
                110 to "み"; 109 to "も"
                else -> null
            }
            else -> when (code) {
                // 英文模式：数字键 → 符号
                49 to "!"; 50 to "@"; 51 to "#"; 52 to "$"; 53 to "%"
                54 to "^"; 55 to "&"; 56 to "*"; 57 to "("; 48 to ")"
                else -> null
            }
        }
    }
}
