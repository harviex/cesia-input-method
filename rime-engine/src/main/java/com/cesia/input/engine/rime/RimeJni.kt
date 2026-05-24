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

    fun isAvailable(): Boolean = initialized

    fun unavailableMessage(): String? = if (initialized) null else "Rime JNI 未初始化"

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        try {
            // 加载 native 库
            System.loadLibrary("rime_jni")
            Log.i(TAG, "librime_jni.so 加载成功")

            val sharedDir = context.filesDir.absolutePath + "/rime"
            val userDir = context.filesDir.absolutePath + "/rime"
            // 确保目录存在
            java.io.File(sharedDir).mkdirs()
            java.io.File(userDir).mkdirs()
            
            // 验证词库文件是否存在
            val dictFile = java.io.File(sharedDir, "pinyin.dict.yaml")
            val schemaFile = java.io.File(sharedDir, "pinyin.schema.yaml")
            val defaultFile = java.io.File(sharedDir, "default.yaml")
            Log.i(TAG, "dict存在=${dictFile.exists()} size=${dictFile.length()}")
            Log.i(TAG, "schema存在=${schemaFile.exists()} size=${schemaFile.length()}")
            Log.i(TAG, "default存在=${defaultFile.exists()} size=${defaultFile.length()}")
            
            nativeStartup(sharedDir, userDir)
            initialized = true
            Log.i(TAG, "Rime native 引擎初始化成功, shared=$sharedDir")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Rime native 引擎初始化失败", e)
            initialized = false
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
        } catch (e: Throwable) {
            Log.e(TAG, "processKey failed: $key", e)
            false
        }
    }

    fun getComposingText(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            val preedit = nativeGetPreedit()
            if (preedit.isNullOrEmpty()) "" else preedit
        } catch (e: Throwable) {
            Log.e(TAG, "getComposingText failed", e)
            ""
        }
    }

    fun getCandidates(sessionId: Long): List<String> {
        if (!initialized) return emptyList()
        return try {
            val arr = nativeGetCandidates()
            val count = nativeGetCandidateCount()
            Log.d(TAG, "getCandidates: count=$count, initialized=$initialized")
            (0 until count).map { i ->
                arr?.get(i)?.toString() ?: ""
            }.filter { it.isNotEmpty() }
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
        } catch (e: Throwable) {
            ""
        }
    }

    fun selectCandidate(sessionId: Long, index: Int): String {
        if (!initialized) return ""
        return try {
            nativeSelectCandidate(index) ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun clearComposition(sessionId: Long) {
        if (!initialized) return
        try {
            nativeClearComposition()
        } catch (_: Throwable) {}
    }

    fun changePage(sessionId: Long, backward: Boolean): Boolean {
        if (!initialized) return false
        return try {
            nativeChangePage(backward)
        } catch (e: Throwable) {
            false
        }
    }

    fun getPageCount(sessionId: Long): Int {
        if (!initialized) return 0
        return try {
            val ps = nativeGetPageSize()
            val total = nativeGetCandidateCount()
            if (ps <= 0) 0 else (total + ps - 1) / ps
        } catch (e: Throwable) {
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
                '-' -> 69  // KEYCODE_MINUS
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
