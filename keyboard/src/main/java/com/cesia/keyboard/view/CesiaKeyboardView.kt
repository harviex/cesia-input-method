package com.cesia.keyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cesia.keyboard.model.Key
import com.cesia.keyboard.model.KeyType
import kotlin.math.abs

/**
 * Cesia 自定义键盘视图
 * 完全自写，不依赖 Android 的 KeyboardView
 */
class CesiaKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ========== 数据 ==========
    var keyboard: com.cesia.keyboard.model.Keyboard? = null
        set(value) {
            field = value
            invalidate()
        }

    // ========== 回调 ==========
    var onKeyClickListener: ((Key) -> Unit)? = null
    var onKeyLongPressListener: ((Key) -> Boolean)? = null
    var onSwipeLeftListener: (() -> Unit)? = null
    var onSwipeRightListener: (() -> Unit)? = null

    // ========== 绘制工具 ==========
    private val keyBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val mainLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT
    }

    private val activeKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D0E8FF")
        style = Paint.Style.FILL
    }

    // ========== 按键布局 ==========
    private val keyRects = mutableListOf<Pair<Key, RectF>>()
    private var pressedKey: Key? = null

    // ========== 主题 ==========
    var darkMode: Boolean = false
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    private fun applyTheme() {
        if (darkMode) {
            keyBackgroundPaint.color = Color.parseColor("#2A2A3E")
            keyBorderPaint.color = Color.parseColor("#3A3A4E")
            mainLabelPaint.color = Color.parseColor("#E0E0E0")
            subLabelPaint.color = Color.parseColor("#888888")
            activeKeyPaint.color = Color.parseColor("#3A4A6E")
        } else {
            keyBackgroundPaint.color = Color.WHITE
            keyBorderPaint.color = Color.parseColor("#CCCCCC")
            mainLabelPaint.color = Color.parseColor("#333333")
            subLabelPaint.color = Color.parseColor("#888888")
            activeKeyPaint.color = Color.parseColor("#D0E8FF")
        }
        invalidate()
    }

    // ========== 绘制 ==========
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val kb = keyboard ?: return

        keyRects.clear()

        val density = resources.displayMetrics.density
        val keyWidth = kb.keyWidth * density
        val keyHeight = kb.keyHeight * density
        val hGap = kb.horizontalGap * density
        val vGap = kb.verticalGap * density
        val paddingLeft = paddingLeft.toFloat()
        val paddingTop = paddingTop.toFloat()

        // 计算每行的偏移量（居中）
        var y = paddingTop
        for (row in kb.rows) {
            val rowWidth = row.sumOf { (it.width * density).toInt() } + (row.size - 1) * hGap
            val xOffset = paddingLeft + ((width - paddingLeft - paddingRight - rowWidth) / 2f).coerceAtLeast(0f)

            var x = xOffset
            for (key in row) {
                val kw = if (key.width > 0) key.width * density else keyWidth
                val kh = if (key.height > 0) key.height * density else keyHeight
                val left = x + key.marginLeft * density
                val top = y + key.marginTop * density
                val right = left + kw
                val bottom = top + kh - key.marginBottom * density

                val rect = RectF(left, top, right, bottom)
                keyRects.add(key to rect)

                // 绘制按键背景
                val isPressed = key == pressedKey
                val bgPaint = if (isPressed) activeKeyPaint else keyBackgroundPaint

                val cornerRadius = 6f * density
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyBorderPaint)

                // 绘制副字符（右上角）
                if (!key.popupCharacters.isNullOrEmpty()) {
                    val subTextSize = (kh * 0.28f).coerceIn(10f, 16f)
                    subLabelPaint.textSize = subTextSize
                    val subX = right - 4f * density
                    val subY = top + subTextSize + 2f * density
                    canvas.drawText(key.popupCharacters[0].toString(), subX, subY, subLabelPaint)
                }

                // 绘制主字符（中央）
                if (key.label.isNotEmpty()) {
                    val mainTextSize = when {
                        key.label.length <= 2 -> kh * 0.42f
                        key.label.length <= 4 -> kh * 0.32f
                        else -> kh * 0.24f
                    }.coerceIn(12f, 24f)
                    mainLabelPaint.textSize = mainTextSize
                    val centerX = rect.centerX()
                    val centerY = rect.centerY() + mainTextSize / 3f
                    canvas.drawText(key.label, centerX, centerY, mainLabelPaint)
                }

                x += kw + hGap
            }

            y += keyHeight + vGap
        }
    }

    // ========== 触摸处理 ==========
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        val key = findKeyAt(downX, downY)
        if (key != null) {
            longPressTriggered = true
            pressedKey = null
            invalidate()
            onKeyLongPressListener?.invoke(key)
        }
    }

    companion object {
        private const val LONG_PRESS_TIMEOUT = 500L
        private const val MIN_SWIPE_DISTANCE = 100f
        private const val MIN_SWIPE_VELOCITY = 800f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                longPressTriggered = false

                val key = findKeyAt(event.x, event.y)
                pressedKey = key
                invalidate()

                if (key != null && key.type != KeyType.SPACE) {
                    postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressTriggered) return true

                val dx = event.x - downX
                val dy = event.y - downY
                val elapsed = System.currentTimeMillis() - downTime

                // 快速滑动检测
                if (elapsed < 300 && abs(dx) > MIN_SWIPE_DISTANCE && abs(dx) > abs(dy) * 1.5f) {
                    removeCallbacks(longPressRunnable)
                    pressedKey = null
                    invalidate()

                    if (dx > 0) {
                        onSwipeRightListener?.invoke()
                    } else {
                        onSwipeLeftListener?.invoke()
                    }
                    return true
                }

                // 更新按下的按键
                val key = findKeyAt(event.x, event.y)
                if (key != pressedKey) {
                    pressedKey = key
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)

                if (longPressTriggered) {
                    pressedKey = null
                    invalidate()
                    return true
                }

                val key = findKeyAt(event.x, event.y)
                if (key != null) {
                    onKeyClickListener?.invoke(key)
                }

                pressedKey = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pressedKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findKeyAt(x: Float, y: Float): Key? {
        for ((key, rect) in keyRects) {
            if (rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    // ========== 测量 ==========
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val kb = keyboard ?: super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val density = resources.displayMetrics.density
        val keyHeight = kb?.keyHeight ?: 48
        val vGap = kb?.verticalGap ?: 4
        val rows = kb?.rows?.size ?: 4

        val totalHeight = (rows * keyHeight + (rows - 1) * vGap) * density + paddingTop + paddingBottom

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = totalHeight.toInt()

        setMeasuredDimension(
            resolveSize(widthSize, widthMeasureSpec),
            resolveSize(heightSize, heightMeasureSpec)
        )
    }
}