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
        for (k in keys) {
            // 优先使用 XML 中定义的 popupCharacters
            if (!k.popupCharacters.isNullOrEmpty()) {
                val symbol = k.popupCharacters[0].toString()
                drawSubsidiary(canvas, k, symbol)
            } else if (k.codes.isNotEmpty()) {
                // 对于没有 popupCharacters 的按键，根据语言模式显示副字符
                val code = k.codes[0]
                val subsidiary = getSubsidiaryForCode(code)
                if (subsidiary != null) {
                    drawSubsidiary(canvas, k, subsidiary)
                }
            }
        }
    }

    private fun drawSubsidiary(canvas: Canvas, k: Keyboard.Key, symbol: String) {
        val textSize = (k.height * 0.35f).coerceIn(16f, 24f)
        subsidiaryPaint.textSize = textSize
        subsidiaryPaint.color = if (subsidiaryLangMode == "cn") 0xFF4488FF.toInt() else 0xFF888888.toInt()

        val x = k.x + k.width - 4f
        val y = k.y + textSize + 2f

        canvas.drawText(symbol, x, y, subsidiaryPaint)
    }

    private fun getSubsidiaryForCode(code: Int): String? {
        return when (subsidiaryLangMode) {
            "cn" -> getChineseSubsidiary(code)
            "jp" -> getJapaneseSubsidiary(code)
            else -> getEnglishSubsidiary(code)
        }
    }

    private fun getChineseSubsidiary(code: Int): String? {
        return when (code) {
            49 -> "①"; 50 -> "②"; 51 -> "③"; 52 -> "④"; 53 -> "⑤"
            54 -> "⑥"; 55 -> "⑦"; 56 -> "⑧"; 57 -> "⑨"; 48 -> "⓪"
            113 -> "ā"; 119 -> "ē"; 101 -> "ī"; 114 -> "ō"; 116 -> "ū"
            121 -> "ǖ"; 117 -> "ú"; 105 -> "ì"; 111 -> "ǒ"; 112 -> "ǜ"
            97 -> "ɑ"; 115 -> "σ"; 100 -> "δ"; 102 -> "φ"; 103 -> "γ"
            104 -> "η"; 106 -> "ξ"; 107 -> "κ"; 108 -> "λ"
            122 -> "ζ"; 120 -> "χ"; 99 -> "ψ"; 118 -> "ω"; 98 -> "β"
            110 -> "ν"; 109 -> "μ"
            else -> null
        }
    }

    private fun getJapaneseSubsidiary(code: Int): String? {
        return when (code) {
            49 -> "ぬ"; 50 -> "ふ"; 51 -> "あ"; 52 -> "う"; 53 -> "え"
            54 -> "お"; 55 -> "や"; 56 -> "ゆ"; 57 -> "よ"; 48 -> "わ"
            113 -> "た"; 119 -> "て"; 101 -> "い"; 114 -> "す"; 116 -> "か"
            121 -> "ん"; 117 -> "な"; 105 -> "に"; 111 -> "ら"; 112 -> "せ"
            97 -> "ち"; 115 -> "と"; 100 -> "し"; 102 -> "は"; 103 -> "き"
            104 -> "く"; 106 -> "ま"; 107 -> "の"; 108 -> "れ"
            122 -> "つ"; 120 -> "さ"; 99 -> "そ"; 118 -> "ひ"; 98 -> "こ"
            110 -> "み"; 109 -> "も"
            else -> null
        }
    }

    private fun getEnglishSubsidiary(code: Int): String? {
        return when (code) {
            49 -> "!"; 50 -> "@"; 51 -> "#"; 52 -> "$"; 53 -> "%"
            54 -> "^"; 55 -> "&"; 56 -> "*"; 57 -> "("; 48 -> ")"
            else -> null
        }
    }
}
