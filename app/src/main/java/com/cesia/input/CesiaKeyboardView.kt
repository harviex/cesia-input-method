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
 * - 左右滑动切换全键盘/T9（防误触）
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

    // Shift 模式标志 — 全键盘字母键主字符绘制为大写
    var isShiftMode = false

    // Shift 锁定状态（用于绘制不同图标）
    var isShiftLocked = false

    // 当前长按中的按键（用于高亮绘制）
    var currentPopupKey: Keyboard.Key? = null

    // 手势检测：左右滑动切换全键盘/T9，防止误触
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var isSwipeDetected = false
    private var swipeLockUntil = 0L   // 滑动后锁定按键输入的时间戳
    private val swipeThreshold = 100f       // 最小水平滑动距离（px），增大防误触
    private val swipeMaxYDrift = 80f       // 最大垂直偏移（防止上下滑动误触）
    private val swipeLockDuration = 300L   // 滑动后锁定按键输入的冷却时间（ms）

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    /** 滑动趋势早期通知（到达阈值前调用，用于提前取消长按 runnable） */
    var onSwipeEarly: (() -> Unit)? = null
    private var swipeEarlyNotified = false  // 防止重复通知

    // 追踪第一根手指的 ID，用于忽略多指触控时的滑动检测
    private var activePointerId = -1
    private var multiTouch = false

    override fun onTouchEvent(me: android.view.MotionEvent): Boolean {
        when (me.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // 滑动冷却期内禁止按键输入
                if (System.currentTimeMillis() < swipeLockUntil) {
                    return true
                }
                activePointerId = me.getPointerId(0)
                multiTouch = false
                gestureStartX = me.x
                gestureStartY = me.y
                isSwipeDetected = false
                swipeEarlyNotified = false
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指按下：标记多指状态，忽略后续滑动检测
                multiTouch = true
                isSwipeDetected = false
                swipeEarlyNotified = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // 多指状态下不检测滑动（防止双键同时按下被判为滑动手势）
                if (multiTouch) return super.onTouchEvent(me)
                if (!isSwipeDetected) {
                    // 只追踪第一根手指的位移
                    val pointerIndex = me.findPointerIndex(activePointerId)
                    if (pointerIndex < 0) return super.onTouchEvent(me)
                    val dx = me.getX(pointerIndex) - gestureStartX
                    val dy = kotlin.math.abs(me.getY(pointerIndex) - gestureStartY)
                    val adx = kotlin.math.abs(dx)
                    // 早期滑动趋势检测：移动超过 20px 且方向水平，提前取消长按
                    // 此时可能还没到 100px 滑动阈值，但长按 runnable 可能已经注册了
                    if (!swipeEarlyNotified && adx > 20f && dy < swipeMaxYDrift) {
                        swipeEarlyNotified = true
                        clearAllKeysPressed()
                        onSwipeEarly?.invoke()
                    }
                    if (adx > swipeThreshold && dy < swipeMaxYDrift) {
                        isSwipeDetected = true
                        swipeLockUntil = System.currentTimeMillis() + swipeLockDuration
                        if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                        return true
                    }
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                // 非主手指抬起：如果还有手指在屏幕上，继续正常处理
                if (multiTouch) {
                    // 检查是否所有手指都抬起了
                    if (me.pointerCount <= 1) {
                        multiTouch = false
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                // 如果之前检测到滑动，吞掉 ACTION_UP，防止起点按键触发 onKey/onRelease
                if (isSwipeDetected) {
                    isSwipeDetected = false
                    return true
                }
                activePointerId = -1
                multiTouch = false
            }
        }
        return super.onTouchEvent(me)
    }

    /** 清除所有按键按下状态（滑动切换时调用，防止起点按键误触发） */
    private fun clearAllKeysPressed() {
        val kb = keyboard ?: return
        for (key in kb.keys) {
            key.pressed = false
        }
        // 清除长按高亮 key（消除蓝色方形区域）
        currentPopupKey = null
        // 取消 KeyboardView 内部的长按检测（防止滑动起点按键弹出副字符）
        cancelKeyboardViewLongPress()
        invalidateAllKeys()
    }

    /** 取消 KeyboardView 内部的长按 runnable（通过反射） */
    private fun cancelKeyboardViewLongPress() {
        try {
            // KeyboardView 内部用 Handler 延迟触发长按，取消它
            val handlerField = android.inputmethodservice.KeyboardView::class.java.getDeclaredField("mHandler")
            handlerField.isAccessible = true
            val handler = handlerField.get(this) as? android.os.Handler
            // 移除所有 pending 的 runnable（包括长按检测）
            handler?.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
            // 反射失败不影响核心功能
        }
    }

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

    // popupCharacters 副字符画笔（tiffany蓝，加粗）
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        color = 0xFF81D8D0.toInt()
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

    /** 重绘单个按键（用于长按高亮） */
    fun invalidateKey(key: Keyboard.Key) {
        // 标记当前长按的按键，触发重绘
        currentPopupKey = key
        // 只重绘该按键区域
        invalidate(key.x, key.y, key.x + key.width, key.y + key.height)
    }

    // 长按高亮画笔
    private val longPressHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4081D8D0.toInt()
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        // Shift模式：临时将字母键label改为大写，让super.onDraw绘制大写
        val kb = this.keyboard
        if ((isShiftMode || isShiftLocked) && kb != null && !isT9Mode) {
            for (key in kb.keys) {
                val code = key.codes?.firstOrNull() ?: continue
                if (code in 97..122 && key.label != null && key.label.length == 1) {
                    key.label = key.label.toString().uppercase()
                }
            }
        }

        super.onDraw(canvas)

        // 恢复原始小写label
        if ((isShiftMode || isShiftLocked) && kb != null && !isT9Mode) {
            for (key in kb.keys) {
                val code = key.codes?.firstOrNull() ?: continue
                if (code in 97..122 && key.label != null && key.label.length == 1) {
                    key.label = key.label.toString().lowercase()
                }
            }
        }

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

        val keys = this.keyboard?.keys ?: return
        // 长按高亮：在 super.onDraw 之前绘制高亮背景
        val popupKey = currentPopupKey
        if (popupKey != null) {
            canvas.drawRect(
                popupKey.x.toFloat(), popupKey.y.toFloat(),
                (popupKey.x + popupKey.width).toFloat(), (popupKey.y + popupKey.height).toFloat(),
                longPressHighlightPaint
            )
        }
        for (key in keys) {
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

            // ===== 3. T9 1键/剪贴板键 表面文字（统一灰色12px） =====
            if (isT9Mode && (code == 49 || code == -108 || code == -109)) {
                val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
                    color = 0xFF888888.toInt()
                }
                val cx = key.x + key.width / 2f
                val cy = key.y + key.height / 2f + grayPaint.textSize * 0.35f
                val label = when (code) {
                    49 -> "Tab"
                    -108 -> "全选"
                    -109 -> "复制"
                    else -> ""
                }
                canvas.drawText(label, cx, cy, grayPaint)
            }

            // ===== 4. Shift 锁定圆点（脉冲发光效果） =====
            // T9 shift=-104，QWERTY shift=-1，共用 isShiftLocked 状态
            if ((code == -104 || code == -1) && isShiftLocked) {
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF81D8D0.toInt()
                    style = Paint.Style.FILL
                }
                val dotX = key.x + key.width - 30f
                val dotY = key.y + 10f + 8f
                // 脉冲半径：8f ~ 12f，基于系统时间
                val pulse = (System.currentTimeMillis() % 800) / 800f
                val radius = 8f + 4f * kotlin.math.sin(pulse * 2 * kotlin.math.PI).toFloat()
                canvas.drawCircle(dotX, dotY, radius, dotPaint)
                // 触发持续重绘以实现动画
                if (isShiftLocked) {
                    postInvalidateDelayed(50)
                }
            }

            // ===== 5. (剪贴板字符已合并到 section 3) =====
        }
    }
}
