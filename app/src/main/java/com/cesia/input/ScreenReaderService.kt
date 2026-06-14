package com.cesia.input

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍屏幕内容读取服务
 *
 * 功能：监听窗口变化事件，遍历当前前台 App 的 UI 树，提取主要内容文本
 * 用途：为星星按钮的 AI 回复提供屏幕上下文
 *
 * 过滤策略：
 * - 跳过已知系统 UI 类名（状态栏、导航栏、弹窗等）
 * - 优先读取 EditText/TextView 等文本控件
 * - 限制最大读取字符数，避免 prompt 过长
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "CesiaScreenReader"
        private const val MAX_TEXT_LENGTH = 2000

        /** 最近一次读取的屏幕文本（缓存） */
        @Volatile
        var cachedScreenText: String = ""
            private set

        /** 最近一次读取时间戳 */
        @Volatile
        var lastReadTime: Long = 0L
            private set

        private const val CACHE_TIMEOUT_MS = 5000L

        /**
         * 需要跳过的系统 UI 类名（状态栏、导航栏、弹窗、按钮栏等）
         */
        private val SKIP_CLASSES = setOf(
            "android.widget.Button",
            "android.widget.ImageButton",
            "android.widget.ImageView",
            "android.widget.FrameLayout",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
            "android.widget.Toolbar",
            "android.widget.ActionMenuView",
            "android.inputmethodservice.InputMethodWindow",
            "com.android.systemui",
            "android.app.Dialog",
            "android.widget.PopupWindow",
            "android.widget.Toast"
        )

        /**
         * 只关注包含有意义文本的控件类型
         */
        private val TEXT_CLASSES = setOf(
            "android.widget.TextView",
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.webkit.WebView"
        )

        fun getScreenText(): String {
            val now = System.currentTimeMillis()
            return if (now - lastReadTime < CACHE_TIMEOUT_MS && cachedScreenText.isNotEmpty()) {
                cachedScreenText
            } else {
                ""
            }
        }

        fun clearCache() {
            cachedScreenText = ""
            lastReadTime = 0L
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍屏幕读取服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val rootNode = rootInActiveWindow ?: return
                try {
                    val text = extractTextFromNode(rootNode)
                    if (text.isNotEmpty()) {
                        cachedScreenText = text.take(MAX_TEXT_LENGTH)
                        lastReadTime = System.currentTimeMillis()
                        Log.d(TAG, "屏幕内容已更新: ${cachedScreenText.length} 字符 → ${text.take(100)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取屏幕内容失败", e)
                }
            }
        }
    }

    /**
     * 递归遍历 UI 节点树，提取有意义的文本内容
     * 跳过系统 UI 控件，只读取文本类控件
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val className = node.className?.toString() ?: ""

        // 跳过系统 UI 类
        if (shouldSkipClass(className)) {
            return ""
        }

        val sb = StringBuilder()

        // 只提取文本类控件的内容
        if (isTextClass(className)) {
            node.text?.let { text ->
                val trimmed = text.toString().trim()
                if (trimmed.length >= 2) {  // 过滤单字符/emoji噪音
                    sb.append(trimmed).append("\n")
                }
            }
        }

        // contentDescription 只在非文本类控件中提取（如图标的描述文字，但跳过已知噪音）
        node.contentDescription?.let { desc ->
            val trimmed = desc.toString().trim()
            if (trimmed.length >= 2 && trimmed.length <= 100
                && !isNoisyDesc(trimmed)
                && trimmed != node.text?.toString()
            ) {
                sb.append(trimmed).append("\n")
            }
        }

        // 递归遍历子节点
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    val childText = extractTextFromNode(child)
                    if (childText.isNotBlank()) {
                        sb.append(childText)
                    }
                } finally {
                    child.recycle()
                }
            }
        }

        return sb.toString().trim()
    }

    /**
     * 判断是否应该跳过该控件类
     */
    private fun shouldSkipClass(className: String): Boolean {
        // 跳过已知系统类
        if (SKIP_CLASSES.any { className.contains(it) }) return true
        // 跳过包名包含 systemui 的
        if (className.contains("systemui", ignoreCase = true)) return true
        // 跳过输入法自身的窗口
        if (className.contains("inputmethod", ignoreCase = true)) return true
        return false
    }

    /**
     * 判断是否为文本类控件
     */
    private fun isTextClass(className: String): Boolean {
        return TEXT_CLASSES.any { className.contains(it) }
    }

    /**
     * 判断 contentDescription 是否为噪音（系统标准描述文字）
     */
    private fun isNoisyDesc(desc: String): Boolean {
        val noisy = listOf(
            "查看全部", "已取消", "已连接", "滑动即可", "继续滑动",
            "去开启", "稍后再说", "确定", "取消", "关闭", "返回",
            "更多选项", "搜索", "菜单", "通知", "状态栏",
            "next", "back", "close", "menu", "search",
            "Bluetooth", "Wi-Fi", "Battery", "Signal"
        )
        return noisy.any { desc.contains(it, ignoreCase = true) }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍屏幕读取服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        clearCache()
        Log.i(TAG, "无障碍屏幕读取服务已销毁")
    }
}
