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
        color = 0xFF999999.toInt()
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val keys = keyboard?.keys ?: return
        for (key in keys) {
            if (!key.popupCharacters.isNullOrEmpty()) {
                val symbol = key.popupCharacters[0].toString()
                val textSize = (key.height * 0.22f).coerceIn(8f, 14f)
                subsidiaryPaint.textSize = textSize

                val x = key.x + key.width - 4f
                val y = key.y + textSize + 2f

                canvas.drawText(symbol, x, y, subsidiaryPaint)
            }
        }
    }
}
