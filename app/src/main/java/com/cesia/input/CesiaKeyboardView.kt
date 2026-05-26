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
 * 自定义 KeyboardView — 在功能键右上角显示长按副功能文字（灰色小字）
 */
class CesiaKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : KeyboardView(context, attrs, defStyleAttr) {

    // 副功能文字映射：primaryCode -> 显示文字
    private val functionalLabels = mutableMapOf<Int, String>()

    // 副功能文字画笔
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
        color = 0xFF999999.toInt() // 灰色
    }

    private var labelTextSize = 9f // sp

    fun setFunctionalLabels(labels: Map<Int, String>) {
        functionalLabels.clear()
        functionalLabels.putAll(labels)
        invalidateAllKeys()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val spSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, labelTextSize, resources.displayMetrics
        )
        labelPaint.textSize = spSize

        val keyboard = keyboard ?: return
        for (key in keyboard.keys) {
            val label = functionalLabels[key.codes?.firstOrNull() ?: continue] ?: continue
            if (key.label != null) {
                // 只对有主 label 的键显示副功能
                val x = key.x + key.width - 4f
                val y = key.y + spSize + 2f
                canvas.drawText(label, x, y, labelPaint)
            }
        }
    }
}
