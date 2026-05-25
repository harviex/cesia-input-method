package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log

/**
 * Rime JNI 桥接层
 * 调用 native librime 实现真正的 Rime 输入法引擎
 *
 * keycode 说明：
 * - Rime native 使用内部 keycode，字母直接对应 ASCII 码（a=97, b=98...）
 * - 不是 Android 的 KeyEvent.KEYCODE_A (=29)
 * - 参考 rime/key_table.h：以字符的 Unicode code point 作为 keycode
 */
object RimeJni {

    private const val TAG = "RimeJni"

    @Volatile
    private var initialized = false

    fun isAvailable(): Boolean = initialized

    fun unavailableMessage(): String? = if (initialized) null else "Rime JNI 未初始化"

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        try {
            System.loadLibrary("rime_jni")
            Log.i(TAG, "librime_jni.so 加载成功")

            val sharedDir = context.filesDir.absolutePath + "/rime"
            val userDir = context.filesDir.absolutePath + "/rime"
            java.io.File(sharedDir).mkdirs()
            java.io.File(userDir).mkdirs()

            // 验证词库文件
            val dictFile = java.io.File(sharedDir, "pinyin.dict.yaml")
            val schemaFile = java.io.File(sharedDir, "pinyin.schema.yaml")
            val defaultFile = java.io.File(sharedDir, "default.yaml")
            Log.i(TAG, "dict=${dictFile.exists()}(${dictFile.length()}) schema=${schemaFile.exists()} default=${defaultFile.exists()}")

            nativeStartup(sharedDir, userDir)
            initialized = true
            Log.i(TAG, "Rime native 引擎初始化成功 shared=$sharedDir")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Rime native 引擎初始化失败", e)
            initialized = false
            return false
        }
    }

    fun shutdown() {
        if (initialized) {
            try { nativeExit() } catch (_: Exception) {}
            initialized = false
        }
    }

    fun createSession(): RimeSession = RimeSession(1L)
    fun destroySession(session: RimeSession) {}

    /**
     * 处理按键
     * key 格式：单个字符用 ASCII 码，功能键用标准 ASCII 控制码
     * a-z → 97-122 (ASCII)
     * BackSpace → 8, Space → 32, Enter → 10, Escape → 27
     * Delete → 127
     */
    fun processKey(sessionId: Long, key: String): Boolean {
        if (!initialized) return false
        return try {
            val keycode = keyToRimeKeyCode(key)
            nativeProcessKey(keycode, 0)
        } catch (e: Throwable) {
            Log.e(TAG, "processKey failed: $key", e)
            false
        }
    }

    fun getComposingText(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            nativeGetPreedit() ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "getComposingText failed", e)
            ""
        }
    }

    fun getCandidates(sessionId: Long): List<String> {
        if (!initialized) return emptyList()
        return try {
            val arr = nativeGetCandidates() ?: return emptyList()
            val count = nativeGetCandidateCount()
            (0 until count).map { i -> arr.get(i)?.toString() ?: "" }.filter { it.isNotEmpty() }
        } catch (e: Throwable) {
            Log.e(TAG, "getCandidates failed", e)
            emptyList()
        }
    }

    fun commitComposition(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            nativeCommitComposition()
            nativeGetCommit() ?: ""
        } catch (e: Throwable) { "" }
    }

    fun selectCandidate(sessionId: Long, index: Int): String {
        if (!initialized) return ""
        return try {
            nativeSelectCandidate(index) ?: ""
        } catch (e: Throwable) { "" }
    }

    fun clearComposition(sessionId: Long) {
        if (!initialized) return
        try { nativeClearComposition() } catch (_: Throwable) {}
    }

    fun changePage(sessionId: Long, backward: Boolean): Boolean {
        if (!initialized) return false
        return try { nativeChangePage(backward) } catch (e: Throwable) { false }
    }

    fun getPageCount(sessionId: Long): Int {
        if (!initialized) return 0
        return try {
            val ps = nativeGetPageSize()
            val total = nativeGetCandidateCount()
            if (ps <= 0) 0 else (total + ps - 1) / ps
        } catch (e: Throwable) { 0 }
    }

    fun getCurrentPage(sessionId: Long): Int = 0

    /**
     * 将按键字符转换为 Rime 内部 keycode
     * Rime 使用 Unicode code point 作为 keycode
     */
    private fun keyToRimeKeyCode(key: String): Int {
        if (key.length == 1) {
            val c = key[0]
            // 字母、数字、符号直接返回 ASCII/Unicode code point
            return c.code
        }
        // 功能键映射为 ASCII 控制码
        return when (key) {
            "BackSpace", "Back" -> 8       // ASCII BS
            "Enter", "Return" -> 10        // ASCII LF
            "Tab" -> 9                     // ASCII HT
            "Escape" -> 27                 // ASCII ESC
            "Space" -> 32                  // ASCII SP
            "Delete", "Del" -> 127         // ASCII DEL
            "Up" -> 0x26 + 0x10000         // 方向键需要特殊处理
            "Down" -> 0x28 + 0x10000
            "Left" -> 0x25 + 0x10000
            "Right" -> 0x27 + 0x10000
            "Home" -> 0x24 + 0x10000
            "End" -> 0x23 + 0x10000
            "PageUp" -> 0x21 + 0x10000
            "PageDown" -> 0x22 + 0x10000
            else -> {
                Log.w(TAG, "未知按键: $key, 使用原值")
                key.hashCode()
            }
        }
    }

    // ============ Native methods ============
    @JvmStatic external fun nativeStartup(sharedDir: String, userDir: String)
    @JvmStatic external fun nativeExit()
    @JvmStatic external fun nativeProcessKey(keycode: Int, mask: Int): Boolean
    @JvmStatic external fun nativeCommitComposition(): Boolean
    @JvmStatic external fun nativeClearComposition()
    @JvmStatic external fun nativeGetCommit(): String?
    @JvmStatic external fun nativeGetPreedit(): String?
    @JvmStatic external fun nativeGetCursorPos(): Int
    @JvmStatic external fun nativeGetCandidates(): Array<String>?
    @JvmStatic external fun nativeGetCandidateCount(): Int
    @JvmStatic external fun nativeGetPageSize(): Int
    @JvmStatic external fun nativeChangePage(backward: Boolean): Boolean
    @JvmStatic external fun nativeSelectCandidate(index: Int): String?
    @JvmStatic external fun nativeGetInput(): String?
    @JvmStatic external fun nativeGetOption(key: String): Boolean
    @JvmStatic external fun nativeSetOption(key: String, value: Boolean)
}
