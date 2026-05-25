package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Rime 输入引擎
 * 直接代理给 RimeJni → native librime
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

    override fun initialize(): Boolean {
        if (isInitialized) return true
        copyRimeAssetsIfNeeded()
        val success = RimeJni.initialize(context)
        isInitialized = success
        if (!success) {
            Log.e(TAG, "Rime 引擎初始化失败: ${RimeJni.unavailableMessage()}")
        }
        return success
    }

    fun lastError(): String? = RimeJni.unavailableMessage()

    private fun copyRimeAssetsIfNeeded() {
        val rimeDir = File(context.filesDir, "rime")
        rimeDir.mkdirs()
        try {
            context.assets.list("rime")?.forEach { fileName ->
                val outFile = File(rimeDir, fileName)
                if (!outFile.exists()) {
                    context.assets.open("rime/$fileName").use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.d(TAG, "解压: $fileName (${outFile.length()} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压 Rime 资产失败", e)
        }
    }

    override fun shutdown() {
        RimeJni.shutdown()
        session = null
        isInitialized = false
    }

    fun reload(): Boolean {
        shutdown()
        return initialize()
    }

    /** 词库更新后触发重新部署（比 reload 轻量） */
    fun redeploy() {
        session = null
        // 重新部署：退出再启动
        RimeJni.shutdown()
        RimeJni.initialize(context)
    }

    override fun createSession(): RimeSession {
        val s = RimeJni.createSession()
        session = s
        return s
    }

    override fun destroySession(session: RimeSession) {
        RimeJni.destroySession(session)
        if (this.session?.id == session.id) this.session = null
    }

    override fun processKey(key: String): Boolean {
        val s = session ?: createSession()
        return s.processKey(key)
    }

    override fun processKey(c: Char): Boolean = processKey(c.toString())

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

    // 兼容方法
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
