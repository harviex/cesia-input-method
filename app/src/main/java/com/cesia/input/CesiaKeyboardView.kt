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
 * 自定义 KeyboardView
 * - 功能键长按副字符（functionalLabels，灰色）
 * - T9 数字键盘主字符（大号字母，灰色）+ 副字符（红色数字）
 * - popupCharacters 副字符（红色小字，距右侧10px距上方10px）
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

    // T9 模式标志 — 只有 T9 数字键盘才绘制字母主字符
    var isT9Mode = false

    // T9 主字符映射（数字码 → 字母标签）
    private val t9MainLabels = mapOf(
        50 to "abc", 51 to "def", 52 to "ghi", 53 to "jkl",
        54 to "mno", 55 to "pqrs", 56 to "tuv", 57 to "wxyz"
    )

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

    // T9 主字符画笔（大号字母，居中）
    private val t9MainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
        color = 0xFF555555.toInt()
    }

    private var labelTextSize = 9f
    private var popupTextSize = 12f
    private var t9MainTextSize = 14f

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

        val t9MainSpSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, t9MainTextSize, resources.displayMetrics
        )
        t9MainPaint.textSize = t9MainSpSize

        val keyboard = keyboard ?: return
        for (key in keyboard.keys) {
            val code = key.codes?.firstOrNull() ?: continue
            if (key.label == null) continue

            // ===== T9 主字符（大号字母，按键中央） =====
            if (isT9Mode) {
                val t9Main = t9MainLabels[code]
                if (t9Main != null) {
                    val cx = key.x + key.width / 2f
                    val cy = key.y + key.height / 2f + t9MainSpSize * 0.35f
                    canvas.drawText(t9Main, cx, cy, t9MainPaint)
                }
            }

            val x = key.x + key.width - 10f

            // ===== 1. functionalLabels / t9Labels（灰色，右上角） =====
            val fnLabel = functionalLabels[code] ?: t9Labels[code]
            if (fnLabel != null) {
                val y = key.y + 10f + spSize
                canvas.drawText(fnLabel, x, y, labelPaint)
            }

            // ===== 2. popupCharacters / T9 副字符数字（红色，右上角） =====
            val popup = key.popupCharacters
            if (!popup.isNullOrEmpty()) {
                val symbol = popup[0].toString()
                val y = if (fnLabel != null) {
                    key.y + 10f + spSize + popupSpSize + 2f
                } else {
                    key.y + 10f + popupSpSize
                }
                canvas.drawText(symbol, x, y, popupPaint)
            }
        }
    }
}
