package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log
import com.cesia.input.engine.rime.trime.*
import com.osfans.trime.core.Rime as TrimeRime

/**
 * Rime JNI 桥接层（Cesia 封装）
 * 通过 com.osfans.trime.core.Rime 包下的 native 方法调用 librime_jni.so
 */
object RimeJni {

    private const val TAG = "RimeJni"

    @Volatile
    private var initialized = false

    fun isAvailable(): Boolean = initialized

    @Volatile
    private var errorMessage: String? = null

    fun unavailableMessage(): String? = errorMessage

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        errorMessage = null
        try {
            // 加载 librime_jni.so（Trime 的 JNI 库）
            System.loadLibrary("rime_jni")
            Log.i(TAG, "STEP1: librime_jni.so 加载成功")

            val sharedDir = context.filesDir.absolutePath + "/rime"
            val userDir = context.filesDir.absolutePath + "/rime"
            java.io.File(sharedDir).mkdirs()
            java.io.File(userDir).mkdirs()

            val dictFile = java.io.File(sharedDir, "pinyin.dict.yaml")
            val schemaFile = java.io.File(sharedDir, "pinyin.schema.yaml")
            val defaultFile = java.io.File(sharedDir, "default.yaml")
            Log.i(TAG, "STEP3: dict=${dictFile.exists()}(${dictFile.length()}) schema=${schemaFile.exists()} default=${defaultFile.exists()}")

            // startupRime(sharedDir, userDir, versionName, fullCheck)
            TrimeRime.startupRime(sharedDir, userDir, "1.0.0", false)
            Log.i(TAG, "STEP4: startupRime 完成")

            // 检查是否真正启动成功
            val started = isRimeStarted()
            if (!started) {
                errorMessage = "startupRime 后 isRimeStarted=false (共享目录: $sharedDir)"
                Log.e(TAG, errorMessage!!)
            }
            initialized = started
            return started
        } catch (e: Throwable) {
            errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Rime native 引擎初始化失败", e)
            initialized = false
            return false
        }
    }

    fun shutdown() {
        if (initialized) {
            try { TrimeRime.exitRime() } catch (_: Exception) {}
            initialized = false
        }
    }

    fun createSession(): RimeSession = RimeSession(1L)
    fun destroySession(session: RimeSession) {}

    fun processKey(sessionId: Long, key: String): Boolean {
        if (!initialized) return false
        return try {
            val keycode = keyToRimeKeyCode(key)
            TrimeRime.processRimeKey(keycode, 0)
        } catch (e: Throwable) {
            Log.e(TAG, "processKey failed: $key", e)
            false
        }
    }

    fun getComposingText(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            val context = TrimeRime.getRimeContext()
            context.composition.preedit ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "getComposingText failed", e)
            ""
        }
    }

    fun getCandidates(sessionId: Long): List<String> {
        if (!initialized) return emptyList()
        return try {
            val ctx = TrimeRime.getRimeContext()
            ctx.menu.candidates.map { it.text }
        } catch (e: Throwable) {
            Log.e(TAG, "getCandidates failed", e)
            emptyList()
        }
    }

    fun commitComposition(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            TrimeRime.commitRimeComposition()
            TrimeRime.getRimeCommit().text ?: ""
        } catch (e: Throwable) { "" }
    }

    fun selectCandidate(sessionId: Long, index: Int): String {
        if (!initialized) return ""
        return try {
            TrimeRime.selectRimeCandidate(index, false)
            val ctx = TrimeRime.getRimeContext()
            ctx.menu.candidates.getOrNull(index)?.text ?: ""
        } catch (e: Throwable) { "" }
    }

    fun clearComposition(sessionId: Long) {
        if (!initialized) return
        try { TrimeRime.clearRimeComposition() } catch (_: Throwable) {}
    }

    fun changePage(sessionId: Long, backward: Boolean): Boolean {
        if (!initialized) return false
        return try { TrimeRime.changeRimeCandidatePage(backward) } catch (e: Throwable) { false }
    }

    fun getPageCount(sessionId: Long): Int {
        if (!initialized) return 0
        return try {
            val ctx = TrimeRime.getRimeContext()
            if (ctx.menu.pageSize <= 0) 0
            else (ctx.menu.candidates.size + ctx.menu.pageSize - 1) / ctx.menu.pageSize
        } catch (e: Throwable) { 0 }
    }

    fun getCurrentPage(sessionId: Long): Int {
        if (!initialized) return 0
        return try {
            TrimeRime.getRimeContext().menu.pageNumber
        } catch (e: Throwable) { 0 }
    }

    private fun keyToRimeKeyCode(key: String): Int {
        if (key.length == 1) {
            return key[0].code
        }
        return when (key) {
            "BackSpace", "Back" -> 8
            "Enter", "Return" -> 10
            "Tab" -> 9
            "Escape" -> 27
            "Space" -> 32
            "Delete", "Del" -> 127
            "Up" -> 0x26 + 0x10000
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

    private fun isRimeStarted(): Boolean {
        return try {
            val schemas = TrimeRime.getRimeSchemaList()
            schemas.isNotEmpty()
        } catch (e: Throwable) {
            Log.e(TAG, "isRimeStarted check failed", e)
            false
        }
    }
}
