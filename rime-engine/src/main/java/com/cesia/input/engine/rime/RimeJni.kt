package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log
import com.osfans.trime.core.Rime as TrimeRime
import com.osfans.trime.core.ContextProto
import com.osfans.trime.core.CommitProto

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

            TrimeRime.startupRime(sharedDir, userDir, "1.0.0", false)
            Log.i(TAG, "STEP4: startupRime 完成")

            // 清除旧的 build 目录，强制 Rime 重新编译
            val buildDir = java.io.File(sharedDir, "build")
            if (buildDir.exists()) {
                buildDir.deleteRecursively()
                Log.i(TAG, "STEP5: 清除旧 build 目录")
            }

            // 触发 Rime 部署（编译 schema 和词库）
            if (schemaFile.exists()) {
                val schemaOk = TrimeRime.deployRimeSchemaFile(schemaFile.absolutePath)
                Log.i(TAG, "STEP5: deployRimeSchemaFile=${schemaOk}")
            }
            // 注意：不调用 deployRimeConfigFile，避免覆盖 default.yaml 中的 schema_list

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
            val result = TrimeRime.processRimeKey(keycode, 0)
            Log.d(TAG, "processRimeKey key=$key keycode=$keycode result=$result")
            result
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
            // 等待 Rime 引擎完成初始化（最多 60 秒）
            // 通过检查 schema 列表是否非空来判断
            for (i in 0 until 600) {
                try {
                    val schemas = TrimeRime.getRimeSchemaList()
                    if (schemas.isNotEmpty()) {
                        Log.i(TAG, "isRimeStarted: schemaList=${schemas.size} after ${i * 100}ms")
                        return true
                    }
                } catch (_: Throwable) {}
                Thread.sleep(100)
            }
            Log.e(TAG, "isRimeStarted: timeout after 60s, schemaList still empty")
            false
        } catch (e: Throwable) {
            Log.e(TAG, "isRimeStarted check failed", e)
            false
        }
    }
}
