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
        get() = try {
            RimeJni.isComposing()
        } catch (_: Throwable) {
            session?.hasComposing() ?: false
        }
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
        // 使用外部存储目录，避免卸载时丢失
        val rimeDir = File(context.getExternalFilesDir(null), "rime")
        if (!rimeDir.exists()) rimeDir.mkdirs()

        try {
            val assetFiles = context.assets.list("rime") ?: emptyArray()
            Log.i(TAG, "assets/rime 文件列表: ${assetFiles.joinToString()}")
            for (fileName in assetFiles) {
                val outFile = File(rimeDir, fileName)
                // 首次安装或文件缺失时复制
                // 词库文件（.dict.yaml）只在不存在时复制，避免覆盖用户数据
                // schema 和 default 总是覆盖，确保更新生效
                val shouldCopy = if (fileName.endsWith(".dict.yaml")) {
                    !outFile.exists()
                } else {
                    true // schema 和 default 总是覆盖
                }
                if (shouldCopy) {
                    context.assets.open("rime/$fileName").use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.i(TAG, "复制: $fileName (${outFile.length()} bytes)")
                } else {
                    Log.i(TAG, "跳过(已存在): $fileName (${outFile.length()} bytes)")
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

    // ======================== 模式切换 ========================

    fun setAsciiMode(ascii: Boolean) {
        RimeJni.setAsciiMode(ascii)
    }
