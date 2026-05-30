package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log
import java.io.File
import com.osfans.trime.core.Rime

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
        // 统一使用 filesDir/rime/，与 PinyinDictManager 下载词库的路径一致
        val rimeDir = File(context.filesDir, "rime")
        if (!rimeDir.exists()) rimeDir.mkdirs()

        try {
            val assetFiles = context.assets.list("rime") ?: emptyArray()
            Log.i(TAG, "assets/rime 文件列表: ${assetFiles.joinToString()}")
            for (fileName in assetFiles) {
                // 跳过 .dict.yaml —— 词库由用户从设置页下载，不覆盖
                if (fileName.endsWith(".dict.yaml")) {
                    val outFile = File(rimeDir, fileName)
                    if (outFile.exists()) {
                        Log.i(TAG, "跳过词库(已存在): $fileName (${outFile.length()} bytes)")
                    } else {
                        // 首次安装也没有下载词库时，复制内置精简版作为 fallback
                        context.assets.open("rime/$fileName").use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        Log.i(TAG, "复制内置精简词库(fallback): $fileName (${outFile.length()} bytes)")
                    }
                    continue
                }

                // schema 和 default 配置总是覆盖（APK 更新时同步）
                val outFile = File(rimeDir, fileName)
                context.assets.open("rime/$fileName").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "复制配置: $fileName (${outFile.length()} bytes)")
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

    /** 获取所有页的候选词（合并） */
    fun getAllCandidates(): List<String> {
        val s = session ?: return emptyList()
        if (s.pageCount <= 1) return s.candidates
        val all = mutableListOf<String>()
        val startPage = s.currentPage
        // 先回到第0页
        while (s.currentPage > 0) s.prevPage()
        // 从第0页开始往后收集
        all.addAll(s.candidates)
        while (s.currentPage < s.pageCount - 1) {
            if (!s.nextPage()) break
            all.addAll(s.candidates)
        }
        // 回到起始页
        while (s.currentPage < startPage) s.nextPage()
        while (s.currentPage > startPage) s.prevPage()
        return all
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

    /** 简繁切换：通过 Rime setOption 切换（需要 schema 中配置 traditional 开关） */
    fun setTraditional(trad: Boolean) {
        RimeJni.setOption("traditional", trad)
    }

    /** 切换 Rime schema */
    fun selectSchema(schemaId: String): Boolean {
        return Rime.selectRimeSchemas(arrayOf(schemaId))
    }

}
