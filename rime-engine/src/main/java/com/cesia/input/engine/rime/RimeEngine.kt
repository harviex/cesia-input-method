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
        /** 最小权重阈值：只保留 weight >= 50 的词，过滤低频噪音 */
        private const val MIN_WEIGHT_THRESHOLD = 50
        /** 每个首字桶最多保留的词条数：只保留权重最高的 300 个 */
        private const val MAX_ENTRIES_PER_BUCKET = 300
        /** 候选词最多返回前 3000 个（词组+单字按权重自然混排；翻页上限防卡顿） */
        private const val MAX_CANDIDATE_COUNT = 3000
        /** getAllCandidates 最多翻页步数（pageSize=5 → 20页最多扫100候选，足够候选栏/面板显示，避免长码枚举卡顿） */
        private const val MAX_PAGE_WALK = 20
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
        get() = (session?.candidates ?: emptyList()).take(MAX_CANDIDATE_COUNT)
    /** 候选词拼音列表（与 candidates 一一对应），用于 T9 逐键选音按首字母过滤 */
    val candidatePinyins: List<String>
        get() = session?.candidatePinyins ?: emptyList()
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
        } else {
            // 后台预构建联想索引，避免首次查询时卡顿
            Thread {
                try {
                    dictIndexBuildTime = System.currentTimeMillis()
                    dictIndex = buildDictIndex()
                    dictIndexBuilt = true
                    Log.d(TAG, "联想索引后台构建完成")
                } catch (_: Exception) {
                    Log.e(TAG, "联想索引构建失败")
                }
            }.start()
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
        synchronized(this) {
            RimeJni.shutdown()
            session = null
            isInitialized = false
        }
    }

    fun reload(): Boolean {
        shutdown()
        return initialize()
    }

    /** 词库更新后触发重新部署（比 reload 轻量） */
    @Synchronized
    fun redeploy() {
        session = null
        // 重新部署：退出再启动
        RimeJni.shutdown()
        RimeJni.initialize(context)
    }

    @Synchronized
    override fun createSession(): RimeSession {
        val s = RimeJni.createSession()
        session = s
        return s
    }

    @Synchronized
    override fun destroySession(session: RimeSession) {
        RimeJni.destroySession(session)
        if (this.session?.id == session.id) this.session = null
    }

    @Synchronized
    override fun processKey(key: String): Boolean {
        val s = session ?: createSession()
        return s.processKey(key)
    }

    @Synchronized
    override fun processKey(c: Char): Boolean = processKey(c.toString())

    @Synchronized
    override fun processKeyCode(keyCode: Int): Boolean {
        val s = session ?: createSession()
        return s.processKeyCode(keyCode)
    }

    @Synchronized
    override fun selectCandidate(index: Int): String {
        val s = session ?: return ""
        return s.selectCandidate(index)
    }

    @Synchronized
    override fun commit(): String {
        val s = session ?: return ""
        return s.commit()
    }

    @Synchronized
    override fun clear() {
        session?.clear()
    }

    @Synchronized
    override fun nextPage(): List<String> {
        session?.nextPage()
        return candidates
    }

    @Synchronized
    override fun prevPage(): List<String> {
        session?.prevPage()
        return candidates
    }

    /** 获取所有页的候选词（合并）：线性取前 MAX_CANDIDATE_COUNT 个（含词组与单字，按需自然混排） */
    @Synchronized
    fun getAllCandidates(): List<String> {
        val s = session ?: return emptyList()
        if (s.pageCount <= 1) return s.candidates.take(MAX_CANDIDATE_COUNT)
        val all = mutableListOf<String>()
        val startPage = s.currentPage
        // 先回到第0页
        while (s.currentPage > 0) s.prevPage()
        // 从第0页开始往后收集，但最多收集 MAX_CANDIDATE_COUNT 个（避免翻遍数千页导致卡顿）
        all.addAll(s.candidates)
        var pagesWalked = 0
        while (s.currentPage < s.pageCount - 1 && all.size < MAX_CANDIDATE_COUNT && pagesWalked < MAX_PAGE_WALK) {
            if (!s.nextPage()) break
            all.addAll(s.candidates)
            pagesWalked++
        }
        // 回到起始页（同样限制回翻步数，避免起始页在极远处时卡顿）
        var back = 0
        while (s.currentPage < startPage && back < MAX_PAGE_WALK) { s.nextPage(); back++ }
        while (s.currentPage > startPage && back < MAX_PAGE_WALK * 2) { s.prevPage(); back++ }
        return all.take(MAX_CANDIDATE_COUNT)
    }

    /** 与 getAllCandidates 对应的拼音列表（按相同页遍历顺序） */
    @Synchronized
    fun getAllCandidatePinyins(): List<String> {
        val s = session ?: return emptyList()
        if (s.pageCount <= 1) return s.candidatePinyins.take(MAX_CANDIDATE_COUNT)
        val all = mutableListOf<String>()
        val startPage = s.currentPage
        while (s.currentPage > 0) s.prevPage()
        all.addAll(s.candidatePinyins)
        var pagesWalked = 0
        while (s.currentPage < s.pageCount - 1 && all.size < MAX_CANDIDATE_COUNT && pagesWalked < MAX_PAGE_WALK) {
            if (!s.nextPage()) break
            all.addAll(s.candidatePinyins)
            pagesWalked++
        }
        var back = 0
        while (s.currentPage < startPage && back < MAX_PAGE_WALK) { s.nextPage(); back++ }
        while (s.currentPage > startPage && back < MAX_PAGE_WALK * 2) { s.prevPage(); back++ }
        return all.take(MAX_CANDIDATE_COUNT)
    }

    // 兼容方法
    @Synchronized
    fun inputLetter(c: Char): String {
        processKey(c)
        return composingText
    }

    @Synchronized
    fun backspace(): String {
        processKey("BackSpace")
        return composingText
    }

    fun getCurrentPinyin(): String = composingText

    // ======================== 模式切换 ========================

    @Synchronized
    fun setAsciiMode(ascii: Boolean) {
        RimeJni.setAsciiMode(ascii)
    }

    /** 简繁切换：通过 Rime setOption 切换（需要 schema 中配置 traditional 开关） */
    @Synchronized
    fun setTraditional(trad: Boolean) {
        RimeJni.setOption("traditional", trad)
    }

    /** 切换 Rime schema */
    @Synchronized
    fun selectSchema(schemaId: String): Boolean {
        val ok = Rime.selectRimeSchemas(arrayOf(schemaId))
        if (ok) clearSession()
        return ok
    }

    /** 清除当前 session（切换 schema 后调用，下次按键自动用新 schema 创建新 session） */
    @Synchronized
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

    /** 构建词库索引：按首字分桶，桶内按权重降序，只保留高频词（在后台线程执行） */
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
                            if (parts.size >= 3) {
                                val word = parts[0]
                                if (word.length < 2) return@forEach // 跳过单字词
                                val weight = parts[2].toIntOrNull() ?: 0
                                if (weight < MIN_WEIGHT_THRESHOLD) return@forEach // 过滤低频词
                                val bucket = word.substring(0, 1)
                                val entry = AssociationEntry(word, "", weight)
                                index.getOrPut(bucket) { mutableListOf() }.add(entry)
                                entryCount++
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

        // 每个桶只保留权重最高的 MAX_ENTRIES_PER_BUCKET 个词
        val limitedIndex = mutableMapOf<String, List<AssociationEntry>>()
        var limitedCount = 0
        index.forEach { (bucket, list) ->
            list.sortByDescending { it.weight }
            if (list.size > MAX_ENTRIES_PER_BUCKET) {
                limitedIndex[bucket] = list.take(MAX_ENTRIES_PER_BUCKET)
            } else {
                limitedIndex[bucket] = list
            }
            limitedCount += limitedIndex[bucket]!!.size
        }
        Log.d(TAG, "联想索引: ${limitedIndex.size} 桶, $limitedCount 词条 (原 $entryCount 词条, 过滤 weight<$MIN_WEIGHT_THRESHOLD + 每桶限制 $MAX_ENTRIES_PER_BUCKET), 耗时 ${System.currentTimeMillis() - dictIndexBuildTime}ms")
        return limitedIndex
    }

    /**
     * 词语联想：查询以 prefix 为前缀的词语
     * 如果索引未构建完成，直接返回空列表（不阻塞主线程），后台会自动构建
     * @return 去掉前缀后的显示词列表，按权重降序，去重
     */
    fun getAssociations(prefix: String, limit: Int = 20, timeoutMs: Long = 500): List<String> {
        if (prefix.length < 2) return emptyList() // 至少两个字才联想

        if (!dictIndexBuilt) {
            // 索引未完成：触发后台构建，直接返回空避免卡顿
            if (dictIndex == null) {
                Thread {
                    try {
                        dictIndexBuildTime = System.currentTimeMillis()
                        dictIndex = buildDictIndex()
                        dictIndexBuilt = true
                        Log.d(TAG, "联想索引后台构建完成")
                    } catch (_: Exception) {
                        Log.e(TAG, "联想索引后台构建失败")
                    }
                }.start()
            }
            return emptyList()
        }

        val index = dictIndex ?: return emptyList()
        val bucket = prefix.substring(0, 1)
        val candidates = index[bucket] ?: return emptyList()

        val seen = mutableSetOf<String>()
        val singleChar = mutableListOf<String>()
        val multiChar = mutableListOf<Pair<String, Int>>()
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
        val sortedMulti = multiChar.sortedByDescending { it.second }.map { it.first }
        return (singleChar.sortedByDescending { w ->
            candidates.firstOrNull { it.fullWord == prefix + w && it.fullWord.length == prefix.length + 1 }?.weight ?: 0
        } + sortedMulti).take(limit)
    }

    /** 清除索引（词库更新后调用） */
    fun clearAssociationIndex() {
        dictIndex = null
        dictIndexBuilt = false
    }
}
