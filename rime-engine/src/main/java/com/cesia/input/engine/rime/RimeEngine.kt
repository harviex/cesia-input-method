package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Rime 输入引擎实现
 * 当前为 stub 实现（纯 Kotlin），后续替换为真实 librime JNI 调用
 */
class RimeEngine(private val context: Context) : InputEngine {

    companion object {
        private const val TAG = "RimeEngine"
    }

    private var session: RimeSession? = null
    private val prefs = context.getSharedPreferences("cesia_rime", Context.MODE_PRIVATE)

    override val name: String = "Rime"

    override var isInitialized: Boolean = false
        private set

    override val isAvailable: Boolean
        get() = isInitialized && RimeJni.isAvailable()

    // --- 当前会话代理 ---

    override val isComposing: Boolean
        get() = session?.hasComposing() ?: false

    override val composingText: String
        get() = session?.composingText ?: ""

    override val candidates: List<String>
        get() = session?.candidates ?: emptyList()

    override val hasCandidates: Boolean
        get() = session?.hasCandidates() ?: false

    override val pageCount: Int
        get() = session?.pageCount ?: 0

    override val currentPage: Int
        get() = session?.currentPage ?: 0

    // --- 生命周期 ---

    override fun initialize(): Boolean {
        if (isInitialized) return true
        // 先将 APK assets 中的 rime 配置文件解压到 filesDir/rime/（仅第一次）
        copyRimeAssetsIfNeeded()
        val success = RimeJni.initialize(context)
        isInitialized = success
        if (success) {
            Log.i(TAG, "Rime 引擎初始化成功")
        } else {
            Log.w(TAG, "Rime 引擎初始化失败: ${RimeJni.unavailableMessage()} — Rime 引擎将不可用")
        }
        return success
    }

    /**
     * 将 assets/rime/ 下的配置文件解压到 filesDir/rime/
     * 仅在首次（或文件缺失）时执行
     */
    private fun copyRimeAssetsIfNeeded() {
        val rimeDir = File(context.filesDir, "rime")
        if (rimeDir.exists() && rimeDir.listFiles()?.isNotEmpty() == true) return
        rimeDir.mkdirs()
        try {
            context.assets.list("rime")?.forEach { fileName ->
                val outFile = File(rimeDir, fileName)
                context.assets.open("rime/$fileName").use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "解压 Rime 资产: $fileName -> ${outFile.absolutePath} (${outFile.length()} bytes)")
            }
            prefs.edit().putBoolean("rime_assets_copied", true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "解压 Rime 资产失败", e)
        }
    }

    override fun shutdown() {
        RimeJni.shutdown()
        session?.let {
            try {
                RimeJni.destroySession(it)
            } catch (_: Exception) {}
        }
        session = null
        isInitialized = false
    }

    // --- 会话管理 ---

    override fun createSession(): RimeSession {
        val s = RimeJni.createSession()
        session = s
        return s
    }

    override fun destroySession(session: RimeSession) {
        RimeJni.destroySession(session)
        if (this.session?.id == session.id) {
            this.session = null
        }
    }

    // --- 输入处理 ---

    override fun processKey(key: String): Boolean {
        val s = session ?: createSession()
        return s.processKey(key)
    }

    override fun processKey(c: Char): Boolean {
        return processKey(c.toString())
    }

    override fun processKeyCode(keyCode: Int): Boolean {
        val s = session ?: createSession()
        return s.processKeyCode(keyCode)
    }

    override fun selectCandidate(index: Int): String {
        val s = session ?: return ""
        return s.selectCandidate(index)
    }

    override fun commit(): String {
        val s = session ?: return ""
        return s.commit()
    }

    override fun clear() {
        session?.clear()
    }

    override fun nextPage(): List<String> {
        session?.nextPage()
        return candidates
    }

    override fun prevPage(): List<String> {
        session?.prevPage()
        return candidates
    }

    // --- PinyinEngine 兼容方法 ---

    fun inputLetter(c: Char): String {
        processKey(c)
        return composingText
    }

    fun backspace(): String {
        processKey("BackSpace")
        return composingText
    }

    fun getCurrentPinyin(): String = composingText
}
