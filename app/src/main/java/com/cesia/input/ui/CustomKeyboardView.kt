package com.cesia.input.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs

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

    // 滑动速度检测
    private var velocityTracker: VelocityTracker? = null
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var downTime = 0L
    
    // 最小滑动速度阈值（像素/秒），低于此值不触发键盘切换
    private val MIN_SWIPE_VELOCITY = 800f
    // 最小滑动距离（像素）
    private val MIN_SWIPE_DISTANCE = 100f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val keys = keyboard?.keys ?: return
        for (key in keys) {
            // 绘制副字符（popupCharacters）- 右上角小字
            if (!key.popupCharacters.isNullOrEmpty()) {
                val symbol = key.popupCharacters[0].toString()
                val textSize = (key.height * 0.38f).coerceIn(14f, 20f)
                subsidiaryPaint.textSize = textSize
                subsidiaryPaint.color = 0xFF666666.toInt()

                // 右上角：右边留4px，顶部留2px
                val x = key.x + key.width - 4f
                val y = key.y + textSize + 2f

                canvas.drawText(symbol, x, y, subsidiaryPaint)
            }
            
            // 绘制主字符（keyLabel中的字母）- 按键中央大字
            // T9 键盘：数字键显示字母作为主字符
            val label = key.label?.toString() ?: ""
            if (label.isNotEmpty() && label.matches(Regex("[a-z]+"))) {
                // 这是 T9 键盘的字母主字符，绘制在中央
                val mainPaint = android.graphics.Paint().apply {
                    color = 0xFF333333.toInt()
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    textSize = (key.height * 0.42f).coerceIn(18f, 26f)
                }
                
                val centerX = key.x + key.width / 2f
                val centerY = key.y + key.height / 2f + mainPaint.textSize / 3f
                
                canvas.drawText(label, centerX, centerY, mainPaint)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                lastDownX = ev.x
                lastDownY = ev.y
                downTime = System.currentTimeMillis()
                
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(ev)
                velocityTracker?.computeCurrentVelocity(1000) // 像素/秒
                
                val deltaX = ev.x - lastDownX
                val deltaY = ev.y - lastDownY
                val velocityX = velocityTracker?.xVelocity ?: 0f
                val elapsed = System.currentTimeMillis() - downTime
                
                val isHorizontalSwipe = abs(deltaX) > abs(deltaY) * 1.5f
                val distanceOk = abs(deltaX) > MIN_SWIPE_DISTANCE
                val velocityOk = abs(velocityX) > MIN_SWIPE_VELOCITY
                
                if (isHorizontalSwipe && distanceOk && velocityOk) {
                    // 快速水平滑动 → 触发键盘切换
                    if (deltaX > 0) {
                        swipeRight()
                    } else {
                        swipeLeft()
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true // 消费事件，不触发按键
                }
                
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        
        return super.onTouchEvent(ev)
    }

    // 这些方法由 CesiaInputMethod 实现
    override fun swipeLeft() {
        (context as? com.cesia.input.CesiaInputMethod)?.swipeLeft()
    }
    
    override fun swipeRight() {
        (context as? com.cesia.input.CesiaInputMethod)?.swipeRight()
    }
}
