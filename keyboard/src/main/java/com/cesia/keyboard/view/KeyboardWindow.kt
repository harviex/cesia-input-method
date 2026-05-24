package com.cesia.keyboard.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 键盘窗口容器
 * 管理多个键盘、切换、事件分发
 * 负责协调 KeyboardView 和页面切换
 */
class KeyboardWindow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ===== 子视图 =====
    private val keyboardView: KeyboardView

    // ===== 键盘管理 =====
    private val keyboards = mutableMapOf<String, com.cesia.keyboard.data.Keyboard>()
    private var currentKeyboardId: String = ""
    private var isSymbolMode = false
    private var isChineseMode = true

    // ===== 回调 =====
    var onKeyListener: OnKeyListener? = null
    var onSwipeListener: OnSwipeListener? = null

    // ===== 手势检测 =====
    private val gestureDetector: GestureDetector

    // ===== 接口定义 =====
    interface OnKeyListener {
        fun onKeyClick(key: com.cesia.keyboard.data.Key)
        fun onKeyLongPress(key: com.cesia.keyboard.data.Key)
        fun onKeyRepeat(key: com.cesia.keyboard.data.Key)
    }

    interface OnSwipeListener {
        fun onSwipeLeft()
        fun onSwipeRight()
    }

    // ===== 滑动追踪 =====
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private val SWIPE_THRESHOLD = 80f
    private val SWIPE_VELOCITY_THRESHOLD = 500f

    init {
        // 创建 KeyboardView
        keyboardView = KeyboardView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        addView(keyboardView)

        // 设置按键回调
        keyboardView.onKeyClickListener = { key ->
            handleKeyClick(key)
        }
        keyboardView.onKeyLongPressListener = { key ->
            onKeyListener?.onKeyLongPress(key)
        }
        keyboardView.onKeyRepeatListener = { key ->
            onKeyListener?.onKeyRepeat(key)
        }

        // 手势检测
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y

                // 水平滑动
                if (abs(deltaX) > abs(deltaY) * 1.5f &&
                    abs(deltaX) > SWIPE_THRESHOLD &&
                    abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (deltaX > 0) {
                        onSwipeListener?.onSwipeRight()
                    } else {
                        onSwipeListener?.onSwipeLeft()
                    }
                    return true
                }
                return false
            }
        })
    }

    // ===== 键盘管理 =====

    /**
     * 注册键盘
     */
    fun registerKeyboard(keyboard: com.cesia.keyboard.data.Keyboard) {
        keyboards[keyboard.id] = keyboard
    }

    /**
     * 切换到指定键盘
     */
    fun switchToKeyboard(keyboardId: String) {
        val kb = keyboards[keyboardId] ?: return
        currentKeyboardId = keyboardId
        keyboardView.setKeyboard(kb)
    }

    /**
     * 获取当前键盘
     */
    fun getCurrentKeyboard(): com.cesia.keyboard.data.Keyboard? {
        return keyboards[currentKeyboardId]
    }

    /**
     * 获取当前键盘 ID
     */
    fun getCurrentKeyboardId(): String = currentKeyboardId

    // ===== 状态管理 =====

    fun setSymbolMode(enabled: Boolean) {
        isSymbolMode = enabled
    }

    fun isSymbolMode(): Boolean = isSymbolMode

    fun setChineseMode(enabled: Boolean) {
        isChineseMode = enabled
    }

    fun isChineseMode(): Boolean = isChineseMode

    fun setCapsMode(enabled: Boolean) {
        keyboardView.capsMode = enabled
        keyboardView.invalidate()
    }

    fun isCapsMode(): Boolean = keyboardView.capsMode

    // ===== 主题 =====

    fun setTheme(
        keyNormal: Int? = null,
        keyPressed: Int? = null,
        keyText: Int? = null,
        keyBg: Int? = null,
        functionKey: Int? = null,
        functionText: Int? = null,
        popupBg: Int? = null,
        popupText: Int? = null
    ) {
        keyNormal?.let { keyboardView.keyNormalColor = it }
        keyPressed?.let { keyboardView.keyPressedColor = it }
        keyText?.let { keyboardView.keyTextColor = it }
        keyBg?.let { keyboardView.keyboardBgColor = it }
        functionKey?.let { keyboardView.functionKeyColor = it }
        functionText?.let { keyboardView.functionKeyTextColor = it }
        popupBg?.let { keyboardView.popupBgColor = it }
        popupText?.let { keyboardView.popupTextColor = it }
    }

    // ===== 按键处理 =====

    private fun handleKeyClick(key: com.cesia.keyboard.data.Key) {
        when (key.keyType) {
            com.cesia.keyboard.data.KeyType.SYMBOL_SWITCH -> {
                isSymbolMode = !isSymbolMode
                onKeyListener?.onKeyClick(key)
            }
            com.cesia.keyboard.data.KeyType.LANG_SWITCH -> {
                isChineseMode = !isChineseMode
                onKeyListener?.onKeyClick(key)
            }
            com.cesia.keyboard.data.KeyType.KEYBOARD_SWITCH -> {
                onKeyListener?.onKeyClick(key)
            }
            else -> {
                onKeyListener?.onKeyClick(key)
            }
        }
    }

    // ===== 触摸事件 =====

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 让 KeyboardView 处理所有触摸事件
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ===== 内部 KeyboardView 引用（供高级用法）=====
    fun getKeyboardView(): KeyboardView = keyboardView
}
