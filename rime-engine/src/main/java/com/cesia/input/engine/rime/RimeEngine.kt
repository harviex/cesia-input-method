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
    }

    // ===== 后台线程：librime 要求 initialize + 所有 native 调用在同一线程 =====
    // 主线程只负责 UI，native 计算搬到 worker，避免按键冻屏
    private val workerThread = android.os.HandlerThread("rime-worker").apply { start() }
    private val workerHandler = android.os.Handler(workerThread.looper)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 候选更新监听器（由上层设为 updateCandidateBar），worker 算完后主线程回调 */
    var candidateListener: (() -> Unit)? = null
    /** 组合文本/提交变化监听器 */
    var commitListener: ((text: String) -> Unit)? = null

    // 主线程可见快照（worker 算完后由主线程赋值，避免跨线程读 session 不一致）
    @Volatile private var latestCandidates: List<String> = emptyList()
    @Volatile private var latestComposingText: String = ""
    @Volatile private var latestHasCandidates: Boolean = false
    @Volatile private var latestIsComposing: Boolean = false

    private var session: RimeSession? = null
    private val prefs = context.getSharedPreferences("cesia_rime", Context.MODE_PRIVATE)

    override val name: String = "Rime"
    override var isInitialized: Boolean = false
        private set
    override val isAvailable: Boolean
        get() = isInitialized && RimeJni.isAvailable()

    override val isComposing: Boolean
        get() = latestIsComposing
    override val composingText: String
        get() = latestComposingText
    override val candidates: List<String>
        get() = latestCandidates
    override val hasCandidates: Boolean
        get() = latestHasCandidates
    override val pageCount: Int
        get() = session?.pageCount ?: 0
    override val currentPage: Int
        get() = session?.currentPage ?: 0

    override fun initialize(): Boolean {
        // 后台线程执行 native 初始化（librime 要求 initialize + 调用同一线程）
        workerHandler.post {
            if (isInitialized) {
                mainHandler.post { initCallback?.invoke(true) }
                return@post
            }
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
            mainHandler.post { initCallback?.invoke(success) }
        }
        return true // 已提交初始化
    }

    /** 初始化完成回调（主线程） */
    var initCallback: ((Boolean) -> Unit)? = null

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
        workerHandler.post {
            RimeJni.shutdown()
            session = null
            isInitialized = false
        }
    }

    fun reload(): Boolean {
        workerHandler.post {
            RimeJni.shutdown()
            session = null
            // 重新初始化（在 worker 串行执行）
            copyRimeAssetsIfNeeded()
            val success = RimeJni.initialize(context)
            isInitialized = success
            mainHandler.post { initCallback?.invoke(success) }
        }
        return true
    }

    /** 词库更新后触发重新部署（比 reload 轻量） */
    fun redeploy() {
        workerHandler.post {
            session = null
            RimeJni.shutdown()
            RimeJni.initialize(context)
        }
    }

    override fun createSession(): RimeSession {
        // 主线程调用方走 worker；worker 内部直接调 RimeJni.createSession
        var result: RimeSession? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        workerHandler.post {
            val s = RimeJni.createSession()
            session = s
            result = s
            latch.countDown()
        }
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return result ?: RimeSession(1L)
    }

    override fun destroySession(session: RimeSession) {
        workerHandler.post {
            RimeJni.destroySession(session)
            if (this.session?.id == session.id) this.session = null
        }
    }

    override fun processKey(key: String): Boolean {
        // 异步：native 计算在 worker 线程，算完主线程触发 candidateListener 刷新候选栏
        workerHandler.post {
            val s = session ?: RimeJni.createSession().also { session = it }
            s.processKey(key)
            val cands = s.candidates
            val comp = s.composingText
            val composing = s.hasComposing()
            mainHandler.post {
                latestCandidates = cands
                latestComposingText = comp
                latestHasCandidates = cands.isNotEmpty()
                latestIsComposing = composing
                candidateListener?.invoke()
            }
        }
        return true
    }

    override fun processKey(c: Char): Boolean = processKey(c.toString())

    override fun processKeyCode(keyCode: Int): Boolean {
        workerHandler.post {
            val s = session ?: RimeJni.createSession().also { session = it }
            s.processKeyCode(keyCode)
            val cands = s.candidates
            mainHandler.post {
                latestCandidates = cands
                candidateListener?.invoke()
            }
        }
        return true
    }

    override fun selectCandidate(index: Int): String {
        // 异步：选中在 worker 执行，结果经 commitListener 回主线程上屏
        workerHandler.post {
            val s = session ?: run { mainHandler.post { commitListener?.invoke("") }; return@post }
            val text = s.selectCandidate(index)
            mainHandler.post {
                commitListener?.invoke(text)
            }
        }
        return "" // 结果经 commitListener 异步返回
    }

    override fun commit(): String {
        workerHandler.post {
            val s = session ?: return@post
            val text = s.commit()
            mainHandler.post { commitListener?.invoke(text) }
        }
        return ""
    }

    override fun clear() {
        workerHandler.post {
            session?.clear()
            mainHandler.post {
                latestCandidates = emptyList()
                latestComposingText = ""
                latestHasCandidates = false
                latestIsComposing = false
                candidateListener?.invoke()
            }
        }
    }

    override fun nextPage(): List<String> {
        workerHandler.post {
            session?.nextPage()
            val cands = session?.candidates ?: emptyList()
            mainHandler.post {
                latestCandidates = cands
                candidateListener?.invoke()
            }
        }
        return emptyList()
    }

    override fun prevPage(): List<String> {
        workerHandler.post {
            session?.prevPage()
            val cands = session?.candidates ?: emptyList()
            mainHandler.post {
                latestCandidates = cands
                candidateListener?.invoke()
            }
        }
        return emptyList()
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

    // ======================== 模式切换 =======================

    fun setAsciiMode(ascii: Boolean) {
        workerHandler.post { RimeJni.setAsciiMode(ascii) }
    }

    /** 简繁切换：通过 Rime setOption 切换（需要 schema 中配置 traditional 开关） */
    fun setTraditional(trad: Boolean) {
        workerHandler.post { RimeJni.setOption("traditional", trad) }
    }

    /** 切换 Rime schema */
    fun selectSchema(schemaId: String): Boolean {
        workerHandler.post {
            Rime.selectRimeSchemas(arrayOf(schemaId))
            clearSession()
        }
        return true // 已在 worker 异步执行
    }

    /** 清除当前 session（切换 schema 后调用，下次按键自动用新 schema 创建新 session） */
    fun clearSession() {
        workerHandler.post { session = null }
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
