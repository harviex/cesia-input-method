package com.cesia.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import android.util.TypedValue

/**
 * 自定义 KeyboardView — 在功能键右上角显示副功能文字
 * - 功能键长按副字符（functionalLabels，灰色）
 * - T9 数字键盘副字符（t9Labels，灰色）
 * - 字母/数字键 popupCharacters 副字符（红色小字）
 */
class CesiaKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : KeyboardView(context, attrs, defStyleAttr) {

    // 功能键长按副字符
    private var functionalLabels = mapOf<Int, String>()

    // T9 数字键盘副字符
    private var t9Labels = mapOf<Int, String>()

    // 副字符画笔（灰色）
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
        color = 0xFF999999.toInt()
    }

    // popupCharacters 副字符画笔（红色小字）
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
        color = 0xFFCC4444.toInt()
    }

    private var labelTextSize = 9f
    private var popupTextSize = 8f

    fun setFunctionalLabels(labels: Map<Int, String>) {
        functionalLabels = labels
        invalidateAllKeys()
    }

    fun setT9Labels(labels: Map<Int, String>) {
        t9Labels = labels
        invalidateAllKeys()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val spSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, labelTextSize, resources.displayMetrics
        )
        labelPaint.textSize = spSize

        val popupSpSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, popupTextSize, resources.displayMetrics
        )
        popupPaint.textSize = popupSpSize

        val keyboard = keyboard ?: return
        for (key in keyboard.keys) {
            val code = key.codes?.firstOrNull() ?: continue
            if (key.label == null) continue

            val x = key.x + key.width - 3f

            // 1. functionalLabels / t9Labels（灰色，功能键编辑功能提示）
            val fnLabel = functionalLabels[code] ?: t9Labels[code]
            if (fnLabel != null) {
                val y = key.y + spSize + 1f
                canvas.drawText(fnLabel, x, y, labelPaint)
            }

            // 2. popupCharacters（红色小字，长按上屏字符）
            val popup = key.popupCharacters
            if (!popup.isNullOrEmpty()) {
                val symbol = popup[0].toString()
                // 如果有 functionalLabels，popup 画在下方；否则画在上方
                val y = if (fnLabel != null) {
                    key.y + spSize + popupSpSize + 2f
                } else {
                    key.y + popupSpSize + 1f
                }
                canvas.drawText(symbol, x, y, popupPaint)
            }
        }
    }
}
