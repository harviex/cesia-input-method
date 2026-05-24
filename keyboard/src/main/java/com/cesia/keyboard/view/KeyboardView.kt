package com.cesia.keyboard.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.cesia.keyboard.data.Key
import com.cesia.keyboard.data.KeyType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 自定义键盘渲染 View
 * 完全自写，不继承 Android 的 KeyboardView
 * 负责绘制所有按键、处理触摸事件、显示长按弹窗
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ===== 绘制状态 =====
    private var keyboard: com.cesia.keyboard.data.Keyboard? = null
    private val keyRects = mutableMapOf<Int, RectF>()  // keyIndex -> screen rect
    private var pressedKeyIndex: Int = -1
    private var popupKeyIndex: Int = -1

    // ===== 绘制工具 =====
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val functionKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    // ===== 颜色配置 =====
    var keyNormalColor: Int = 0xFFF0F0F0.toInt()
        set(value) { field = value; invalidate() }
    var keyPressedColor: Int = 0xFFD0D0D0.toInt()
        set(value) { field = value; invalidate() }
    var keyTextColor: Int = 0xFF333333.toInt()
        set(value) { field = value; invalidate() }
    var keyTextSize: Float = 18f
        set(value) { field = value; invalidate() }
    var functionKeyColor: Int = 0xFFE0E0E0.toInt()
        set(value) { field = value; invalidate() }
    var functionKeyTextColor: Int = 0xFF555555.toInt()
        set(value) { field = value; invalidate() }
    var keyboardBgColor: Int = 0xFFE8E8E8.toInt()
        set(value) { field = value; invalidate() }
    var popupBgColor: Int = 0xFFFFFFFF.toInt()
        set(value) { field = value; invalidate() }
    var popupTextColor: Int = 0xFF333333.toInt()
        set(value) { field = value; invalidate() }
    var cornerRadius: Float = 8f
        set(value) { field = value; invalidate() }

    // ===== 弹窗 =====
    private var popupRect = RectF()
    private var popupLabel: String = ""
    private var showPopup = false
    private var popupAnimator: ValueAnimator? = null
    private var popupScale = 0f

    // ===== 回调 =====
    var onKeyClickListener: ((Key) -> Unit)? = null
    var onKeyLongPressListener: ((Key) -> Unit)? = null
    var onKeyRepeatListener: ((Key) -> Unit)? = null

    // ===== 长按检测 =====
    private var longPressRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null
    private val longPressDelay = 500L
    private val repeatDelay = 80L

    // ===== 滑动检测 =====
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val swipeThreshold = 100f

    // ===== Caps 模式 =====
    var capsMode: Boolean = false

    // ===== 设置键盘 =====
    fun setKeyboard(kb: com.cesia.keyboard.data.Keyboard) {
        keyboard = kb
        keyRects.clear()
        pressedKeyIndex = -1
        showPopup = false
        requestLayout()
        invalidate()
    }

    // ===== 测量 =====
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val kb = keyboard

        val height = if (kb != null) {
            val keyHeight = dpToPx(kb.keyHeightDp)
            val vGap = dpToPx(kb.verticalGapDp)
            val totalHeight = (kb.rows.size * keyHeight) + ((kb.rows.size - 1) * vGap) + dpToPx(8f)
            totalHeight
        } else {
            dpToPx(200f)
        }

        setMeasuredDimension(width, height)
    }

    // ===== 绘制 =====
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val kb = keyboard ?: return

        // 绘制背景
        canvas.drawColor(keyboardBgColor)

        // 计算布局
        val width = width.toFloat()
        val keyHeightPx = dpToPx(kb.keyHeightDp)
        val hGapPx = dpToPx(kb.horizontalGapDp)
        val vGapPx = dpToPx(kb.verticalGapDp)
        val padding = dpToPx(4f)

        keyRects.clear()
        var keyIndex = 0
        var y = padding

        for (row in kb.rows) {
            // 计算这一行的总宽度百分比
            val totalWidthPercent = row.keys.sumOf {
                (it.widthPercent ?: kb.keyWidthPercent).toDouble()
            }.toFloat()

            val availableWidth = width - (2 * padding) - ((row.keys.size - 1) * hGapPx)
            val unitWidth = availableWidth / totalWidthPercent

            var x = padding

            for (key in row.keys) {
                val keyWidth = unitWidth * (key.widthPercent ?: kb.keyWidthPercent)
                val keyH = key.heightDp?.let { dpToPx(it) } ?: keyHeightPx
                val keyGap = key.horizontalGapDp?.let { dpToPx(it) } ?: hGapPx

                val rect = RectF(x, y, x + keyWidth, y + keyH)
                keyRects[keyIndex] = rect

                drawKey(canvas, key, rect, keyIndex == pressedKeyIndex)

                x += keyWidth + keyGap
                keyIndex++
            }

            y += keyHeightPx + vGapPx
        }

        // 绘制长按弹窗
        if (showPopup && popupScale > 0f) {
            drawPopup(canvas)
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF, isPressed: Boolean) {
        val bgColor = when {
            isPressed -> keyPressedColor
            key.keyType == KeyType.FUNCTION -> functionKeyColor
            key.keyType == KeyType.SPACE -> keyNormalColor
            key.keyType == KeyType.ENTER -> 0xFF4CAF50.toInt()
            key.keyType == KeyType.BACKSPACE -> functionKeyColor
            else -> keyNormalColor
        }

        // 绘制按键背景
        val bgDrawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = this@KeyboardView.cornerRadius
        }
        bgDrawable.setBounds(
            rect.left.roundToInt(),
            rect.top.roundToInt(),
            rect.right.roundToInt(),
            rect.bottom.roundToInt()
        )
        bgDrawable.draw(canvas)

        // 绘制按键标签
        val displayLabel = key.getDisplayLabel(capsMode && key.keyType == KeyType.CHARACTER)
        val textColor = when (key.keyType) {
            KeyType.ENTER -> 0xFFFFFFFF.toInt()
            KeyType.FUNCTION, KeyType.BACKSPACE -> functionKeyTextColor
            else -> keyTextColor
        }

        val textSize = when (key.keyType) {
            KeyType.FUNCTION -> keyTextSize * 0.8f
            KeyType.SPACE -> keyTextSize * 0.75f
            else -> keyTextSize
        }

        labelPaint.textSize = dpToPx(textSize)
        labelPaint.color = textColor

        val centerX = rect.centerX()
        val centerY = rect.centerY() + dpToPx(4f)

        canvas.drawText(displayLabel, centerX, centerY, labelPaint)

        // 绘制副字符（popupCharacters）— 右上角小字
        if (!key.popupCharacters.isNullOrEmpty()) {
            val subText = key.popupCharacters
            val subSize = dpToPx(keyTextSize * 0.45f).coerceIn(dpToPx(10f), dpToPx(14f))
            keyPaint.textSize = subSize
            keyPaint.color = 0xFF999999.toInt()
            keyPaint.textAlign = Paint.Align.RIGHT

            val subX = rect.right - dpToPx(4f)
            val subY = rect.top + subSize + dpToPx(2f)

            canvas.drawText(subText, subX, subY, keyPaint)
            keyPaint.textAlign = Paint.Align.CENTER  // 恢复
        }
    }

    private fun drawPopup(canvas: Canvas) {
        val scale = popupScale
        val popupWidth = popupRect.width() * scale
        val popupHeight = popupRect.height() * scale
        val cx = popupRect.centerX()
        val cy = popupRect.centerY()

        val scaledRect = RectF(
            cx - popupWidth / 2,
            cy - popupHeight / 2,
            cx + popupWidth / 2,
            cy + popupHeight / 2
        )

        // 弹窗背景
        popupBgPaint.color = popupBgColor
        val radius = cornerRadius * 1.5f
        canvas.drawRoundRect(scaledRect, radius, radius, popupBgPaint)

        // 弹窗边框
        popupBgPaint.style = Paint.Style.STROKE
        popupBgPaint.strokeWidth = dpToPx(1f)
        popupBgPaint.color = 0xFFCCCCCC.toInt()
        canvas.drawRoundRect(scaledRect, radius, radius, popupBgPaint)
        popupBgPaint.style = Paint.Style.FILL

        // 弹窗文字
        if (scale > 0.5f) {
            popupPaint.textSize = dpToPx(keyTextSize * 1.5f)
            popupPaint.color = popupTextColor
            canvas.drawText(
                popupLabel,
                scaledRect.centerX(),
                scaledRect.centerY() + dpToPx(6f),
                popupPaint
            )
        }
    }

    // ===== 触摸事件 =====
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()

                val keyIndex = findKeyAt(event.x, event.y)
                if (keyIndex >= 0) {
                    pressedKeyIndex = keyIndex
                    invalidate()

                    // 启动长按检测
                    val key = getKeyAtIndex(keyIndex)
                    if (key != null) {
                        startLongPressDetection(key, keyIndex)
                        if (key.repeatable) {
                            startRepeatDetection(key)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val keyIndex = findKeyAt(event.x, event.y)
                if (keyIndex != pressedKeyIndex) {
                    // 按键切换
                    cancelLongPress()
                    cancelRepeat()
                    pressedKeyIndex = keyIndex
                    invalidate()

                    if (keyIndex >= 0) {
                        val key = getKeyAtIndex(keyIndex)
                        if (key != null) {
                            startLongPressDetection(key, keyIndex)
                            if (key.repeatable) {
                                startRepeatDetection(key)
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                cancelRepeat()

                val deltaX = abs(event.x - downX)
                val deltaY = abs(event.y - downY)
                val elapsed = System.currentTimeMillis() - downTime

                // 如果是快速滑动，不触发按键
                if (deltaX > swipeThreshold && deltaX > deltaY * 1.5f && elapsed < 300) {
                    pressedKeyIndex = -1
                    showPopup = false
                    invalidate()
                    return true
                }

                if (pressedKeyIndex >= 0) {
                    val key = getKeyAtIndex(pressedKeyIndex)
                    if (key != null) {
                        onKeyClickListener?.invoke(key)
                    }
                }

                pressedKeyIndex = -1
                showPopup = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                cancelRepeat()
                pressedKeyIndex = -1
                showPopup = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ===== 长按检测 =====
    private fun startLongPressDetection(key: Key, keyIndex: Int) {
        cancelLongPress()
        longPressRunnable = Runnable {
            if (!key.popupCharacters.isNullOrEmpty()) {
                // 显示弹窗
                popupKeyIndex = keyIndex
                popupLabel = key.popupCharacters
                showPopupAnimation(keyIndex)
            }
            onKeyLongPressListener?.invoke(key)
        }.also {
            postDelayed(it, longPressDelay)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun startRepeatDetection(key: Key) {
        cancelRepeat()
        repeatRunnable = object : Runnable {
            override fun run() {
                onKeyRepeatListener?.invoke(key)
                postDelayed(this, repeatDelay)
            }
        }.also {
            postDelayed(it, longPressDelay)
        }
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { removeCallbacks(it) }
        repeatRunnable = null
    }

    // ===== 弹窗动画 =====
    private fun showPopupAnimation(keyIndex: Int) {
        val rect = keyRects[keyIndex] ?: return

        // 弹窗在按键上方
        val popupHeight = rect.height() * 1.3f
        val popupWidth = rect.width() * 1.3f
        popupRect = RectF(
            rect.centerX() - popupWidth / 2,
            rect.top - popupHeight - dpToPx(4f),
            rect.centerX() + popupWidth / 2,
            rect.top - dpToPx(4f)
        )

        showPopup = true
        popupScale = 0f

        popupAnimator?.cancel()
        popupAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                popupScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ===== 辅助方法 =====
    private fun findKeyAt(x: Float, y: Float): Int {
        for ((index, rect) in keyRects) {
            if (rect.contains(x, y)) {
                return index
            }
        }
        return -1
    }

    private fun getKeyAtIndex(index: Int): Key? {
        val kb = keyboard ?: return null
        var currentIndex = 0
        for (row in kb.rows) {
            for (key in row.keys) {
                if (currentIndex == index) return key
                currentIndex++
            }
        }
        return null
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelLongPress()
        cancelRepeat()
        popupAnimator?.cancel()
    }
}
