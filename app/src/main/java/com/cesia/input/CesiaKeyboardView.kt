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

    // 动态主题色（由 CesiaInputMethod 设置）
    var themeAccent: Int = 0xFF81D8D0.toInt()
    var textGrayScale: Float = 0.5f
        set(value) {
            field = value
            if (lastKeyGrayVal > 0) {
                updateKeyTextColor(lastKeyGrayVal)
                applyKeyTextPaintColor()
            }
            invalidateAllKeys()
        }

    // Store last key gray value for re-applying text gray scale
    private var lastKeyGrayVal: Int = 0

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

    // T9 标点主字符映射（复用 t9MainPaint，自动跟随灰度）
    private val t9PunctLabels = mapOf(
        65292 to "，",   // 全角逗号
        12290 to "。",   // 全角句号
        65281 to "！",   // 全角感叹号
        65311 to "？"    // 全角问号
    )

    // T9 功能键主字符映射（复用 t9MainPaint，统一灰度，居中大字）
        private val t9FuncLabels = mapOf(
            -5 to "\u232B",       // 退格 ⌫
            -104 to "\u21E7",     // Shift ⇧
            -100 to "符",          // 符号切换
            -999 to "\u2328",     // 全键盘切换 ⌨
            32 to "空格",         // 空格键
            10 to "\u21B5",       // 回车 ↵
            49 to "Tab"           // 1键：Tab
        )

    // 副字符画笔（灰色）
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
        color = 0xFF999999.toInt()
    }

    // popupCharacters 副字符画笔（主题色，加粗）
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        color = themeAccent
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

    var textScaleFactor: Float = 1f
        set(value) {
            field = value
            applyKeyTextPaintSize()
            invalidateAllKeys()
        }

    // 缓存 AOSP KeyboardView 原始 mLabelTextSize（px），避免多次缩放失真
    private var cachedBaseLabelSize = -1f

    // 统一基准颜色（由 CesiaInputMethod 设置）
    var unifiedKeyColor: Int = 0xFF333333.toInt()

    var keyTextColor: Int = 0xFF333333.toInt()
    private var labelTextColor: Int = 0xFF999999.toInt()
    private var t9MainTextColor: Int = 0xFF555555.toInt()

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

    // 长按高亮画笔（主题色半透明）
    private val longPressHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (themeAccent and 0x00FFFFFF) or 0x40000000  // 主题色 + 25% alpha
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
            // 副字符使用主题色 + 亮度缩放（保持色相，调节明暗）
            popupPaint.color = scaleBrightness(themeAccent, textGrayScale)
            longPressHighlightPaint.color = (themeAccent and 0x00FFFFFF) or 0x40000000

            // 确保 mKeyTextPaint 颜色正确（super.onDraw 可能重置它）
            applyKeyTextPaintColor()

            val kb = this.keyboard
            val keys = kb?.keys ?: emptyList()

            // ===== 全键盘模式：清空 label 让 AOSP 缓存空白按键，改由我们用 t9MainPaint 绘制可变色主字符 =====
            val originalLabels = mutableMapOf<Int, CharSequence>()
            if (!isT9Mode && kb != null) {
                for (key in keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    if (key.label != null && key.label.length > 0) {
                        originalLabels[code] = key.label
                        key.label = ""
                    }
                }
            }

            // Shift模式：临时将字母键label改为大写，让super.onDraw绘制大写（仅 T9 模式不处理）
            if ((isShiftMode || isShiftLocked) && kb != null && !isT9Mode) {
                for (key in kb.keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    if (code in 97..122 && key.label != null && key.label.length == 1) {
                        key.label = key.label.toString().uppercase()
                    }
                }
            }

            super.onDraw(canvas)

            // 恢复原始小写label（Shift 模式）
            if ((isShiftMode || isShiftLocked) && kb != null && !isT9Mode) {
                for (key in kb.keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    if (code in 97..122 && key.label != null && key.label.length == 1) {
                        key.label = key.label.toString().lowercase()
                    }
                }
            }

            // 恢复全键盘原始 label（供下一帧 buffer 重建时使用，虽然 buffer 已缓存空白）
            if (!isT9Mode) {
                for (key in keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    originalLabels[code]?.let { key.label = it }
                }
            }

            val spSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, labelTextSize * textScaleFactor, resources.displayMetrics
            )
            labelPaint.textSize = spSize
            labelPaint.color = labelTextColor

            val popupSpSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, popupTextSize * textScaleFactor, resources.displayMetrics
            )
            popupPaint.textSize = popupSpSize

            val t9MainSpSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, t9MainTextSize * textScaleFactor, resources.displayMetrics
            )
            t9MainPaint.textSize = t9MainSpSize
            t9MainPaint.color = t9MainTextColor

            // 长按高亮：在 super.onDraw 之后绘制高亮背景（覆盖在按键上方）
            val popupKey = currentPopupKey
            if (popupKey != null) {
                canvas.drawRect(
                    popupKey.x.toFloat(), popupKey.y.toFloat(),
                    (popupKey.x + popupKey.width).toFloat(), (popupKey.y + popupKey.height).toFloat(),
                    longPressHighlightPaint
                )
            }

            // ===== 全键盘主字符：用 t9MainPaint 绘制（居中、同字号、同灰度、可变色）=====
            if (!isT9Mode) {
                for (key in keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    val label = originalLabels[code] ?: key.label ?: continue
                    if (label.isEmpty()) continue

                    // Shift 模式下字母键显示大写
                    val displayLabel = if ((isShiftMode || isShiftLocked) && code in 97..122 && label.length == 1)
                        label.toString().uppercase() else label.toString()

                    val cx = key.x + key.width / 2f
                    val cy = key.y + key.height / 2f + t9MainSpSize * 0.35f
                    canvas.drawText(displayLabel, cx, cy, t9MainPaint)
                }
            }

            // ===== T9 功能键主字符（独立循环，无 label 检查，复用 t9MainPaint 跟随灰度/缩放）=====
            if (isT9Mode) {
                // 1. 普通功能键主字符（t9MainPaint）
                for (key in keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    val t9Func = t9FuncLabels[code]
                    if (t9Func != null && code != -100 && code != -108 && code != -109) {
                        val cx = key.x + key.width / 2f
                        val cy = key.y + key.height / 2f + t9MainSpSize * 0.35f
                        canvas.drawText(t9Func, cx, cy, t9MainPaint)
                    }
                }

                // 2. 符号键 - "符" 字（使用 t9MainPaint，跟随字体缩放/灰度）
                for (key in keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    if (code == -100) {
                        val cx = key.x + key.width / 2f
                        val cy = key.y + key.height / 2f + t9MainSpSize * 0.35f
                        canvas.drawText("符", cx, cy, t9MainPaint)
                    }
                }
                // 3. 粘贴/复制键主字符 - "全选"/"复制"
                for (key in keys) {
                    val code = key.codes?.firstOrNull() ?: continue
                    val label = when (code) {
                        -108 -> "全选"
                        -109 -> "复制"
                        else -> null
                    }
                    if (label != null) {
                        val centerPaint = Paint(t9MainPaint).apply {
                            textSize = t9MainSpSize * 0.85f
                        }
                        val cx = key.x + key.width / 2f
                        val cy = key.y + key.height / 2f + centerPaint.textSize * 0.35f
                        canvas.drawText(label, cx, cy, centerPaint)
                    }
                }
            }

            for (key in keys) {
                val code = key.codes?.firstOrNull() ?: continue
                if (key.label == null) continue

                // ===== T9 主字符（大号字母，按键中央）=====
                if (isT9Mode) {
                    val t9Main = t9MainLabels[code]
                    if (t9Main != null) {
                        val cx = key.x + key.width / 2f
                        val cy = key.y + key.height / 2f + t9MainSpSize * 0.35f
                        canvas.drawText(t9Main, cx, cy, t9MainPaint)
                    }
                    // ===== T9 标点主字符（复用 t9MainPaint，自动跟随灰度）=====
                    val t9Punct = t9PunctLabels[code]
                    if (t9Punct != null) {
                        val cx = key.x + key.width / 2f
                        val cy = key.y + key.height / 2f + t9MainSpSize * 0.35f
                        canvas.drawText(t9Punct, cx, cy, t9MainPaint)
                    }
                // T9 功能键主字符主字符由第 3 部分统一绘制（主题感知 + 缩放），此处不再重复绘制
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

                    // ===== T9 副字符（灰色 functionalLabels / 红色 popupCharacters）独立循环，无 label 检查 =====
                    if (isT9Mode) {
                        for (key in keys) {
                            val code = key.codes?.firstOrNull() ?: continue
                            val x = key.x + key.width - 10f

                            // functionalLabels 灰色副字符
                            val fnLabel = functionalLabels[code]
                            if (fnLabel != null) {
                                val y = key.y + 10f + spSize
                                canvas.drawText(fnLabel, x, y, labelPaint)
                            }

                            // popupCharacters 红色副字符（如空格键的 0）
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

                    // ===== 4. Shift 锁定圆点（脉冲发光效果） =====
            // T9 shift=-104，QWERTY shift=-1，共用 isShiftLocked 状态
            if ((code == -104 || code == -1) && isShiftLocked) {
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = themeAccent
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

    /** 动态更新按键背景色 */
    fun updateKeyBackground(keyBgColor: Int) {
        val keyGrayVal = (keyBgColor and 0xFF)
        val strokeGray = (keyGrayVal - 16).coerceIn(0, 255)
        val strokeColor = 0xFF000000.toInt() or (strokeGray shl 16) or (strokeGray shl 8) or strokeGray
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(keyBgColor)
            cornerRadius = 6f * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), strokeColor)
        }
        // KeyboardView 内部用 mKeyBackground 字段存储按键背景 Drawable
        // AOSP KeyboardView 实际字段名可能不同，尝试多个可能的字段名
        val fieldNames = arrayOf("mKeyBackground", "mKeyBackgroundDrawable", "mBackground")
        var success = false
        for (fieldName in fieldNames) {
            try {
                val field = KeyboardView::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(this, drawable.constantState?.newDrawable()?.mutate() ?: drawable)
                android.util.Log.d("CesiaKeyboardView", "updateKeyBackground: set field $fieldName success")
                success = true
                break
            } catch (e: Exception) {
                android.util.Log.w("CesiaKeyboardView", "updateKeyBackground: field $fieldName not found: ${e.message}")
            }
        }
        if (!success) {
            android.util.Log.e("CesiaKeyboardView", "updateKeyBackground: ALL reflection attempts failed, keyBgColor=$keyBgColor")
        }
        // Auto-contrast: if key background is dark, use light text; if light, use dark text
        updateKeyTextColor(keyGrayVal)
        invalidateAllKeys()
    }

    fun updateKeyTextColor(keyGrayVal: Int) {
        lastKeyGrayVal = keyGrayVal
        // 所有文字颜色基于统一的基准颜色（由 CesiaInputMethod 设置）
        keyTextColor = unifiedKeyColor
        labelTextColor = darken(unifiedKeyColor, 0.3f)
        t9MainTextColor = darken(unifiedKeyColor, 0.15f)
        // 应用统一的文字灰度
        keyTextColor = scaleGrayColor(keyTextColor, textGrayScale)
        labelTextColor = scaleGrayColor(labelTextColor, textGrayScale)
        t9MainTextColor = scaleGrayColor(t9MainTextColor, textGrayScale)
        // Apply key text paint via reflection
        applyKeyTextPaintColor()
    }

    /** 同步 AOSP mLabelTextSize / mKeyTextPaint，使按键主字符跟随 textScaleFactor */
    private fun applyKeyTextPaintSize() {
        if (cachedBaseLabelSize < 0) {
            try {
                val field = android.inputmethodservice.KeyboardView::class.java.getDeclaredField("mLabelTextSize")
                field.isAccessible = true
                cachedBaseLabelSize = field.getFloat(this)
            } catch (_: Exception) {
                cachedBaseLabelSize = 14f * resources.displayMetrics.scaledDensity
            }
        }
        val scaled = cachedBaseLabelSize * textScaleFactor
        try {
            val field = android.inputmethodservice.KeyboardView::class.java.getDeclaredField("mLabelTextSize")
            field.isAccessible = true
            field.setFloat(this, scaled)
        } catch (_: Exception) {}
        try {
            val ktp = android.inputmethodservice.KeyboardView::class.java.getDeclaredField("mKeyTextPaint")
            ktp.isAccessible = true
            val paint = ktp.get(this) as? android.graphics.Paint
            paint?.textSize = scaled
        } catch (_: Exception) {}
    }

    /** Scale color by interpolating between black and white through the base color */
    private fun scaleGrayColor(baseColor: Int, scale: Float): Int {
        val a = (baseColor ushr 24) and 0xFF
        val br = ((baseColor shr 16) and 0xFF)
        val bg = ((baseColor shr 8) and 0xFF)
        val bb = (baseColor and 0xFF)
        // scale 0→黑, 0.5→baseColor, 1→白
        val t = scale.coerceIn(0f, 1f)
        val r = if (t <= 0.5f) (br * (t * 2)).toInt() else (br + (255 - br) * ((t - 0.5f) * 2)).toInt()
        val g = if (t <= 0.5f) (bg * (t * 2)).toInt() else (bg + (255 - bg) * ((t - 0.5f) * 2)).toInt()
        val b = if (t <= 0.5f) (bb * (t * 2)).toInt() else (bb + (255 - bb) * ((t - 0.5f) * 2)).toInt()
        return (a shl 24) or (r.coerceIn(0,255) shl 16) or (g.coerceIn(0,255) shl 8) or b.coerceIn(0,255)
    }

    /** Lighten a color by mixing with white (0-1, where 1 = white) */
    private fun lighten(argb: Int, amount: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF).let { (it + (255 - it) * amount).toInt() }
        val g = ((argb shr 8) and 0xFF).let { (it + (255 - it) * amount).toInt() }
        val b = (argb and 0xFF).let { (it + (255 - it) * amount).toInt() }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Darken a color by multiplying channels (0-1, where 0 = black) */
    private fun darken(argb: Int, amount: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF).let { (it * (1f - amount)).toInt() }
        val g = ((argb shr 8) and 0xFF).let { (it * (1f - amount)).toInt() }
        val b = (argb and 0xFF).let { (it * (1f - amount)).toInt() }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Scale brightness of a color (0=暗, 0.5=原色, 1=亮) while preserving hue */
    private fun scaleBrightness(argb: Int, scale: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF)
        val g = ((argb shr 8) and 0xFF)
        val b = (argb and 0xFF)
        val t = scale.coerceIn(0f, 1f)
        // 0→暗(乘0.3), 0.5→原色(乘1.0), 1→亮(乘1.8+白mix)
        val factor = if (t <= 0.5f) 0.3f + t * 1.4f else 1.0f + (t - 0.5f) * 1.6f
        val sr = (r * factor).toInt().coerceIn(0, 255)
        val sg = (g * factor).toInt().coerceIn(0, 255)
        val sb = (b * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (sr shl 16) or (sg shl 8) or sb
    }

    /**
     * Public method to dynamically update text colors when dark mode changes.
     * Can be called directly from CesiaInputMethod when theme toggles.
     */
    fun updateTextColor(isDark: Boolean) {
        val keyGrayVal = if (isDark) 0 else 200  // 0 = pure dark, 200 = light
        updateKeyTextColor(keyGrayVal)
        invalidateAllKeys()
    }

    /** Apply keyTextColor to KeyboardView's internal mPaint and mKeyTextColor via reflection */
    private fun applyKeyTextPaintColor() {
        try {
            // AOSP KeyboardView 只用 mPaint 绘制 key label（没有 mKeyTextPaint 字段）
            val paintField = android.inputmethodservice.KeyboardView::class.java.getDeclaredField("mPaint")
            paintField.isAccessible = true
            val paint = paintField.get(this) as? android.graphics.Paint
            paint?.apply {
                color = t9MainTextColor
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, t9MainTextSize * textScaleFactor, resources.displayMetrics
                )
            }
            // 同步设置 mKeyTextColor（onBufferDraw 中 paint.setColor(mKeyTextColor) 会调用）
            val colorField = android.inputmethodservice.KeyboardView::class.java.getDeclaredField("mKeyTextColor")
            colorField.isAccessible = true
            colorField.setInt(this, t9MainTextColor)
        } catch (_: Exception) {}
        // 强制 buffer 重绘，使颜色/字号/对齐变更生效
        forceBufferRedraw()
    }

    /** 强制重绘 KeyboardView 内部缓存 buffer（解决反射修改 paint 颜色后不生效的问题） */
    private fun forceBufferRedraw() {
        try {
            val bufferField = android.inputmethodservice.KeyboardView::class.java.getDeclaredField("mBuffer")
            bufferField.isAccessible = true
            bufferField.set(this, null)
        } catch (_: Exception) {}
        invalidateAllKeys()
    }
}
