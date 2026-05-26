package com.cesia.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.PopupWindow
import android.widget.TextView
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.ColorDrawable

/**
 * 自定义 KeyboardView — 在功能键右上角显示长按副功能文字（灰色小字）
 */
class CesiaKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : KeyboardView(context, attrs, defStyleAttr) {

    // 副功能文字映射：primaryCode -> 显示文字
    private var functionalLabels = mapOf<Int, String>()

    // 副功能文字画笔
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
        color = 0xFF999999.toInt() // 灰色
    }

    private var labelTextSize = 9f // sp

    // 禁用父类的预览弹窗（我们自己不画），避免 showKey NPE
    private var previewEnabled = false

    fun setFunctionalLabels(labels: Map<Int, String>) {
        functionalLabels = labels
        invalidateAllKeys()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (functionalLabels.isEmpty()) return

        val spSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, labelTextSize, resources.displayMetrics
        )
        labelPaint.textSize = spSize

        val keyboard = keyboard ?: return
        for (key in keyboard.keys) {
            val code = key.codes?.firstOrNull() ?: continue
            val label = functionalLabels[code] ?: continue
            if (key.label != null) {
                val x = key.x + key.width - 4f
                val y = key.y + spSize + 2f
                canvas.drawText(label, x, y, labelPaint)
            }
        }
    }

    // 禁用预览弹窗，避免 showKey 内部 NPE
    override fun setPreviewEnabled(previewEnabled: Boolean) {
        // 强制禁用，父类的预览机制有 NPE 问题
        super.setPreviewEnabled(false)
    }

    override fun isPreviewEnabled(): Boolean = false
}
