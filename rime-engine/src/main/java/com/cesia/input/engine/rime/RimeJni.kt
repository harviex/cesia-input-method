package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log

/**
 * Rime JNI 桥接层
 * 调用 native librime 实现真正的 Rime 输入法引擎
 */
object RimeJni {

    private const val TAG = "RimeJni"

    @Volatile
    private var initialized = false

    fun isAvailable(): Boolean = true

    fun unavailableMessage(): String? = null

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        try {
            System.loadLibrary("rime_jni")
            val sharedDir = context.filesDir.absolutePath + "/rime"
            val userDir = context.filesDir.absolutePath + "/rime"
            // 确保目录存在
            java.io.File(sharedDir).mkdirs()
            java.io.File(userDir).mkdirs()
            nativeStartup(sharedDir, userDir)
            initialized = true
            Log.i(TAG, "Rime native 引擎初始化成功, shared=$sharedDir")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Rime native 引擎初始化失败", e)
            return false
        }
    }

    fun shutdown() {
        if (initialized) {
            try {
                nativeExit()
            } catch (_: Exception) {}
            initialized = false
        }
    }

    fun createSession(): RimeSession {
        return RimeSession(1L) // native 层使用全局 session
    }

    fun destroySession(session: RimeSession) {
        // native 层使用全局 session，无需单独销毁
    }

    fun processKey(sessionId: Long, key: String): Boolean {
        if (!initialized) return false
        return try {
            // 将字符转换为 keycode
            val keycode = keyToKeyCode(key)
            nativeProcessKey(keycode, 0)
        } catch (e: Exception) {
            Log.e(TAG, "processKey failed: $key", e)
            false
        }
    }

    fun getComposingText(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            val preedit = nativeGetPreedit()
            if (preedit.isNullOrEmpty()) "" else preedit
        } catch (e: Exception) {
            ""
        }
    }

    fun getCandidates(sessionId: Long): List<String> {
        if (!initialized) return emptyList()
        return try {
            val arr = nativeGetCandidates()
            val count = nativeGetCandidateCount()
            (0 until count).map { i ->
                arr?.get(i)?.toString() ?: ""
            }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun commitComposition(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            nativeCommitComposition()
            nativeGetCommit() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun selectCandidate(sessionId: Long, index: Int): String {
        if (!initialized) return ""
        return try {
            nativeSelectCandidate(index) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun clearComposition(sessionId: Long) {
        if (!initialized) return
        try {
            nativeClearComposition()
        } catch (_: Exception) {}
    }

    fun changePage(sessionId: Long, backward: Boolean): Boolean {
        if (!initialized) return false
        return try {
            nativeChangePage(backward)
        } catch (e: Exception) {
            false
        }
    }

    fun getPageCount(sessionId: Long): Int {
        if (!initialized) return 0
        return try {
            val ps = nativeGetPageSize()
            val total = nativeGetCandidateCount()
            if (ps <= 0) 0 else (total + ps - 1) / ps
        } catch (e: Exception) {
            0
        }
    }

    fun getCurrentPage(sessionId: Long): Int {
        // 简化：返回 0
        return 0
    }

    // 将字符转换为 Android keycode
    private fun keyToKeyCode(key: String): Int {
        if (key.length == 1) {
            val c = key[0]
            if (c in 'a'..'z') return c - 'a' + 29 // KEYCODE_A = 29
            if (c in 'A'..'Z') return c - 'A' + 29
            if (c in '0'..'9') return c - '0' + 7  // KEYCODE_0 = 7
            return when (c) {
                ' ' -> 62  // KEYCODE_SPACE
                ',' -> 55  // KEYCODE_COMMA
                '.' -> 56  // KEYCODE_PERIOD
                ';' -> 74  // KEYCODE_SEMICOLON
                '\'' -> 75 // KEYCODE_APOSTROPHE
                '/' -> 76  // KEYCODE_SLASH
                '\\' -> 73 // KEYCODE_BACKSLASH
                '[' -> 71  // KEYCODE_LEFT_BRACKET
                ']' -> 72  // KEYCODE_RIGHT_BRACKET
                '=' -> 69  // KEYCODE_EQUALS
                '-' -> 69  // KEYCODE_MINUS (same as equals in some layouts)
                '`' -> 68  // KEYCODE_GRAVE
                else -> c.code
            }
        }
        return when (key) {
            "BackSpace", "Back" -> 67  // KEYCODE_DEL
            "Enter", "Return" -> 66    // KEYCODE_ENTER
            "Tab" -> 61                // KEYCODE_TAB
            "Escape" -> 111            // KEYCODE_ESCAPE
            "Space" -> 62              // KEYCODE_SPACE
            "Up" -> 19                 // KEYCODE_DPAD_UP
            "Down" -> 20               // KEYCODE_DPAD_DOWN
            "Left" -> 21               // KEYCODE_DPAD_LEFT
            "Right" -> 22              // KEYCODE_DPAD_RIGHT
            else -> 0
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
