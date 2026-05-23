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
            if (!key.popupCharacters.isNullOrEmpty()) {
                val symbol = key.popupCharacters[0].toString()
                // 调大符号：0.45f，最大28f
                val textSize = (key.height * 0.45f).coerceIn(20f, 28f)
                subsidiaryPaint.textSize = textSize

                // 右上角：右边留6px，顶部留4px+文字高度偏移
                val x = key.x + key.width - 6f
                val y = key.y + textSize + 4f

                canvas.drawText(symbol, x, y, subsidiaryPaint)
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
    private fun swipeLeft() {
        (context as? com.cesia.input.CesiaInputMethod)?.swipeLeft()
    }
    
    private fun swipeRight() {
        (context as? com.cesia.input.CesiaInputMethod)?.swipeRight()
    }
}
