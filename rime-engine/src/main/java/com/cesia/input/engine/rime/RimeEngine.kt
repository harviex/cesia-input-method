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
        // 使用外部存储目录：/sdcard/Android/data/com.cesia.input/files/rime/
        // 词库下载到此目录，schema 配置从 APK assets 复制（仅首次）
        val rimeDir = File(context.getExternalFilesDir(null), "rime")
        if (!rimeDir.exists()) rimeDir.mkdirs()

        try {
            val assetFiles = context.assets.list("rime") ?: emptyArray()
            Log.i(TAG, "assets/rime 文件列表: ${assetFiles.joinToString()}")
            for (fileName in assetFiles) {
                val outFile = File(rimeDir, fileName)

                // .dict.yaml 词库：如果外部目录已有（用户下载过），跳过；否则复制内置精简版作为 fallback
                if (fileName.endsWith(".dict.yaml")) {
                    if (outFile.exists()) {
                        Log.i(TAG, "跳过词库(已存在): $fileName (${outFile.length()} bytes)")
                    } else {
                        context.assets.open("rime/$fileName").use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        Log.i(TAG, "复制内置精简词库(fallback): $fileName (${outFile.length()} bytes)")
                    }
                    continue
                }

                // schema 配置（.yaml）：总是从 APK 复制（APK 更新时同步最新配置）
                // 但保留用户可能修改过的 schema 文件（如 default.yaml、installation.yaml）
                if (fileName == "default.yaml" || fileName == "installation.yaml") {
                    if (outFile.exists()) {
                        Log.i(TAG, "保留用户配置: $fileName")
                        continue
                    }
                }

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

    /** 清除当前 session（切换 schema 后调用，下次按键自动用新 schema 创建新 session） */
    fun clearSession() {
        session = null
    }

    /** 调试：获取 Rime 完整状态 */
    fun getDebugStatus(): String = RimeJni.getDebugStatus()

    /** 联想词条目 */
    private data class AssociationEntry(
        val fullWord: String,    // 完整词，如 "这个问题"
        val displayWord: String, // 显示词（去掉前缀后），如 "问题"
        val weight: Int
    )

    // ======================== 词库索引（懒加载，按首字分桶） ========================
    private var dictIndex: Map<String, List<AssociationEntry>>? = null
    private var dictIndexBuilt = false
    private var dictIndexBuildTime = 0L

    /** 构建词库索引：按首字（或前2字）分桶，桶内按权重降序 */
    private fun buildDictIndex(): Map<String, List<AssociationEntry>> {
        val index = mutableMapOf<String, MutableList<AssociationEntry>>()
        val rimeDir = java.io.File(context.getExternalFilesDir(null), "rime")
        if (!rimeDir.exists()) return index

        var entryCount = 0
        rimeDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dict.yaml") }
            .forEach { dictFile ->
                try {
                    dictFile.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---") || trimmed.startsWith("...")) return@forEach
                            if (trimmed.startsWith("name:") || trimmed.startsWith("version:") || trimmed.startsWith("sort:") || trimmed.startsWith("use_preset_")) return@forEach

                            val parts = trimmed.split("\t")
                            if (parts.size >= 2) {
                                val word = parts[0]
                                if (word.length < 2) return@forEach // 跳过单字词
                                val weight = if (parts.size >= 4) parts[3].toIntOrNull() ?: 0 else 0
                                // 按首字分桶
                                val bucket = word.substring(0, 1)
                                val entry = AssociationEntry(word, "", weight) // displayWord 在查询时计算
                                index.getOrPut(bucket) { mutableListOf() }.add(entry)
                                entryCount++
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 每个桶按权重降序
            index.forEach { (_, list) -> list.sortByDescending { it.weight } }
            Log.d(TAG, "联想索引: ${index.size} 桶, $entryCount 词条, 耗时 ${System.currentTimeMillis() - dictIndexBuildTime}ms")
            return index
    }

    /**
     * 词语联想：查询以 prefix 为前缀的词语
     * 首次调用构建索引，后续直接查（毫秒级）
     * @return 去掉前缀后的显示词列表，按权重降序，去重
     */
    fun getAssociations(prefix: String, limit: Int = 20): List<String> {
        if (prefix.isEmpty()) return emptyList()

        // 懒加载索引
        if (!dictIndexBuilt) {
            dictIndexBuildTime = System.currentTimeMillis()
            dictIndex = buildDictIndex()
            dictIndexBuilt = true
        }

        val index = dictIndex ?: return emptyList()
        val bucket = prefix.substring(0, 1)
        val candidates = index[bucket] ?: return emptyList()

        // 遍历桶内候选，找 prefix 前缀匹配的项
        val seen = mutableSetOf<String>()
        val singleChar = mutableListOf<String>()   // 单字优先
        val multiChar = mutableListOf<Pair<String, Int>>()  // 多字词按权重
        for (entry in candidates) {
            if (entry.fullWord.startsWith(prefix) && entry.fullWord.length > prefix.length) {
                val displayWord = entry.fullWord.substring(prefix.length)
                if (seen.add(displayWord)) {
                    if (displayWord.length == 1) {
                        singleChar.add(displayWord)
                    } else {
                        multiChar.add(displayWord to entry.weight)
                    }
                }
            }
        }
        // 单字在前（按权重降序），多字在后（按权重降序）
        val sortedMulti = multiChar.sortedByDescending { it.second }.map { it.first }
        return (singleChar.sortedByDescending { w ->
            // 单字也按权重排序：从桶里找对应权重
            candidates.firstOrNull { it.fullWord == prefix + w && it.fullWord.length == prefix.length + 1 }?.weight ?: 0
        } + sortedMulti).take(limit)
    }

    /** 清除索引（词库更新后调用） */
    fun clearAssociationIndex() {
        dictIndex = null
        dictIndexBuilt = false
    }
}
