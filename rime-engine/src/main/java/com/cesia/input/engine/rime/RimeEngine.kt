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
        /** 最小权重阈值：只保留 weight >= 20 的词（原 50 太严，过滤掉大量可用词组联想） */
        private const val MIN_WEIGHT_THRESHOLD = 20
        /** 每个首字桶最多保留的词条数。
         *  注意：按首字分桶会把整部词库塞进内存，256MB heap 下每桶不能太大（1500 实测约 400-600MB 必 OOM）。
         *  500 是内存(约130MB)与词组联想覆盖率的折中；更大的覆盖率靠 indexByPrefix2（二字前缀桶）补足。 */
        private const val MAX_ENTRIES_PER_BUCKET = 500
        /** 前 2 字桶上限（词组联想）。桶极多但每桶极小（同一二字前缀的长词有限），
         *  故上限可放宽到 200，覆盖长尾词组。总内存主要由桶数量而非单桶上限决定。 */
        private const val PREFIX2_BUCKET_CAP = 200
        /** 候选词最多返回数（原 3000 → 800：候选栏+展开面板实际远用不到，翻页收集是主线程开销大头） */
        private const val MAX_CANDIDATE_COUNT = 800
        /** getAllCandidates 翻页步数上限（原 600 → 60：配合懒加载按需增长，避免单次按键上千次 JNI 往返） */
        private const val MAX_PAGE_WALK = 60
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

    @Synchronized
    override fun initialize(): Boolean {
        if (isInitialized) return true
        val t0 = System.currentTimeMillis()
        copyRimeAssetsIfNeeded()
        val success = RimeJni.initialize(context, false)
        isInitialized = success
        Log.i(TAG, "TIMING: RimeEngine.initialize total=${System.currentTimeMillis() - t0}ms (prewarm=false)")
        if (!success) {
            Log.e(TAG, "Rime 引擎初始化失败: ${RimeJni.unavailableMessage()}")
        } else {
            // 后台预构建联想索引，避免首次查询时卡顿
            startIndexBuildAsync()
        }
        return success
    }

    /** 预热入口：设置页/Application 提前调用，fromPrewarm 仅用于打点区分来源 */
    @Synchronized
    fun initialize(prewarm: Boolean): Boolean {
        if (isInitialized) return true
        val t0 = System.currentTimeMillis()
        copyRimeAssetsIfNeeded()
        val success = RimeJni.initialize(context, prewarm)
        isInitialized = success
        Log.i(TAG, "TIMING: RimeEngine.initialize total=${System.currentTimeMillis() - t0}ms (prewarm=$prewarm)")
        if (!success) {
            Log.e(TAG, "Rime 引擎初始化失败: ${RimeJni.unavailableMessage()}")
        } else {
            startIndexBuildAsync()
        }
        return success
    }

    fun lastError(): String? = RimeJni.unavailableMessage()

    /** 联想索引是否已构建完成（用于首查失败后延时补查） */
    fun isAssociationIndexReady(): Boolean = dictIndexBuilt

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

    /** 仅重新部署用户词表(cesia_user.dict.yaml)，不重编译主词典，开销小，使新增接龙词即时生效。 */
    fun deployUserDict() {
        try {
            Rime.deployRimeConfigFile("cesia_user.dict.yaml", "user_dict")
        } catch (e: Exception) {
            Log.w("RimeEngine", "deployUserDict failed: ${e.message}")
        }
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
        invalidateCandCache()
        return s.processKey(key)
    }

    @Synchronized
    override fun processKey(c: Char): Boolean = processKey(c.toString())

    @Synchronized
    override fun processKeyCode(keyCode: Int): Boolean {
        val s = session ?: createSession()
        invalidateCandCache()
        return s.processKeyCode(keyCode)
    }

    @Synchronized
    override fun selectCandidate(index: Int): String {
        val s = session ?: return ""
        invalidateCandCache()
        return s.selectCandidate(index)
    }

    @Synchronized
    override fun commit(): String {
        val s = session ?: return ""
        invalidateCandCache()
        return s.commit()
    }

    @Synchronized
    override fun clear() {
        invalidateCandCache()
        session?.clear()
    }

    @Synchronized
    override fun nextPage(): List<String> {
        invalidateCandCache()
        session?.nextPage()
        return candidates
    }

    @Synchronized
    override fun prevPage(): List<String> {
        invalidateCandCache()
        session?.prevPage()
        return candidates
    }

    // ==================== 候选翻页结果缓存 ====================
    // 每次按键 updateCandidateBar() 都会调 getAllCandidates()，而它内部要把 Rime 游标
    // 从当前页翻回第 0 页、逐页收集、再翻回来——最坏上千次 JNI 往返，是拼音输入卡顿的头号原因。
    // 这里按 (composingText + pageWalk) 做缓存：同一输入状态重复查询直接命中，0 次 JNI。
    private var candCacheKey: String? = null
    private var candCacheWalk = 0
    private var candCacheValue: List<String> = emptyList()
    private var pyCacheKey: String? = null
    private var pyCacheWalk = 0
    private var pyCacheValue: List<String> = emptyList()

    /** 输入状态变化后使候选缓存失效 */
    private fun invalidateCandCache() {
        candCacheKey = null
        pyCacheKey = null
    }

    /** 通用翻页收集：pull 提供每页数据，提前用 isLastPage 终止，避免无谓翻页 */
    private inline fun collectPages(s: RimeSession, pageWalk: Int, pull: (RimeSession) -> List<String>): List<String> {
        if (s.pageCount <= 1) return pull(s).take(MAX_CANDIDATE_COUNT)
        val cap = minOf(pageWalk, MAX_PAGE_WALK)
        val all = ArrayList<String>(cap * 9)
        val startPage = s.currentPage
        // 回到第 0 页（有界，防止极端情况死循环）
        var guard = 0
        while (s.currentPage > 0 && guard++ < MAX_PAGE_WALK) { if (!s.prevPage()) break }
        all.addAll(pull(s))
        var walked = 0
        // isLastPage 直接判定终点，比 currentPage < pageCount-1 更准且不会多翻
        while (!s.isLastPage && all.size < MAX_CANDIDATE_COUNT && walked < cap) {
            if (!s.nextPage()) break
            all.addAll(pull(s))
            walked++
        }
        // 回到起始页
        var back = 0
        while (s.currentPage < startPage && back++ < MAX_PAGE_WALK) { if (!s.nextPage()) break }
        while (s.currentPage > startPage && back++ < MAX_PAGE_WALK * 2) { if (!s.prevPage()) break }
        return if (all.size > MAX_CANDIDATE_COUNT) all.subList(0, MAX_CANDIDATE_COUNT).toList() else all
    }

    /** 获取所有页的候选词（合并）。同一 composing 状态下走缓存，避免重复翻页。 */
    @Synchronized
    fun getAllCandidates(pageWalk: Int = MAX_PAGE_WALK): List<String> {
        val s = session ?: return emptyList()
        val key = s.composingText
        if (candCacheKey == key && candCacheWalk >= pageWalk) return candCacheValue
        val result = collectPages(s, pageWalk) { it.candidates }
        candCacheKey = key
        candCacheWalk = pageWalk
        candCacheValue = result
        return result
    }

    /** 与 getAllCandidates 对应的拼音列表（按相同页遍历顺序），同样带缓存 */
    @Synchronized
    fun getAllCandidatePinyins(pageWalk: Int = MAX_PAGE_WALK): List<String> {
        val s = session ?: return emptyList()
        val key = s.composingText
        if (pyCacheKey == key && pyCacheWalk >= pageWalk) return pyCacheValue
        val result = collectPages(s, pageWalk) { it.candidatePinyins }
        pyCacheKey = key
        pyCacheWalk = pageWalk
        pyCacheValue = result
        return result
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
        invalidateCandCache()
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
    // @Volatile：索引在后台线程构建、主线程读取，无 volatile 会读到半构建状态导致偶发闪退。
    @Volatile
    private var dictIndex: Map<String, List<AssociationEntry>>? = null
    @Volatile
    private var dictIndexBuilt = false
    @Volatile
    private var dictIndexBuilding = false
    private var dictIndexBuildTime = 0L
    // 索引未就绪期间登记的「就绪后自动重查」回调：key=prefix, value=就绪后回调(在主线程执行)。
    // 解决早期选词(索引未构建)返回的假阴性永久失联问题——索引就绪后自动重查并刷新 UI。
    private val pendingAssocCallbacks = mutableMapOf<String, MutableList<() -> Unit>>()

    /** 查询联想：若索引未就绪，登记 onReady 回调，索引构建完成后自动重查前缀并触发回调（不依赖一次性延时重试）。 */
    fun getAssociationsWhenReady(prefix: String, limit: Int = 20, pageWalk: Int = 10, onReady: () -> Unit) {
        if (prefix.isEmpty()) return
        if (dictIndexBuilt) {
            onReady()
            return
        }
        synchronized(pendingAssocCallbacks) {
            pendingAssocCallbacks.getOrPut(prefix) { mutableListOf() }.add(onReady)
        }
        startIndexBuildAsync()
    }

    /** 启动后台索引构建（幂等，避免重复起线程导致内存翻倍 / OOM） */
    private fun startIndexBuildAsync() {
        synchronized(this) {
            if (dictIndexBuilt || dictIndexBuilding) return
            dictIndexBuilding = true
        }
        Thread {
            try {
                dictIndexBuildTime = System.currentTimeMillis()
                val built = buildDictIndex()
                dictIndex = built
                dictIndexBuilt = true
                Log.d(TAG, "联想索引后台构建完成")
                // 索引就绪：触发所有等待中的联想重查回调（主线程执行，安全刷新 UI）
                val pending = synchronized(pendingAssocCallbacks) {
                    val map = pendingAssocCallbacks.toMap()
                    pendingAssocCallbacks.clear()
                    map
                }
                if (pending.isNotEmpty()) {
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    for ((prefix, callbacks) in pending) {
                        val retry = getAssociations(prefix, 100, 500, 10)
                        if (retry.isNotEmpty()) {
                            for (cb in callbacks) mainHandler.post { cb() }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "联想索引构建失败: ${e.message}")
            } finally {
                dictIndexBuilding = false
            }
        }.apply {
            // 后台优先级：比 MIN_PRIORITY 快，缩短首查空窗（仍是后台线程，不抢 UI 渲染）
            priority = android.os.Process.THREAD_PRIORITY_BACKGROUND
            isDaemon = true
        }.start()
    }

    /** 构建词库索引。
     *  联想的两种场景对索引有不同要求：
     *   - 单字联想（prefix 1 字，如「谢」→予/谢/意…）：需要以该字开头的 2 字词，桶=首字。
     *   - 词组联想（prefix ≥2 字，如「谢谢」→你/大家…）：需要以该词开头的更长词，桶=前 2 字。
     *  原来只按首字分桶：高频 2 字词把桶占满（上限 500），低频的 3/4 字词组全被挤掉，
     *  于是「词组上屏后无联想、单字却有」。
     *  现在拆成两张表，各自的桶都很小、可完整保留，总内存反而低于单张大表。 */
    private fun buildDictIndex(): Map<String, List<AssociationEntry>> {
        val rimeDir = java.io.File(context.getExternalFilesDir(null), "rime")
        if (!rimeDir.exists()) return emptyMap()

        val dictFiles = rimeDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dict.yaml") }
            .toList()

        val byWeight = Comparator<AssociationEntry> { a, b -> a.weight.compareTo(b.weight) }
        // firstCharHeaps: 首字 → 2 字词 Top-K（供单字联想）
        val firstCharHeaps = HashMap<String, java.util.PriorityQueue<AssociationEntry>>()
        // prefix2Heaps: 前 2 字 → ≥3 字词 Top-K（供词组联想）
        val prefix2Heaps = HashMap<String, java.util.PriorityQueue<AssociationEntry>>()

        fun offer(heaps: HashMap<String, java.util.PriorityQueue<AssociationEntry>>, key: String, cap: Int, e: AssociationEntry) {
            val heap = heaps.getOrPut(key) { java.util.PriorityQueue(cap + 1, byWeight) }
            if (heap.size < cap) heap.add(e)
            else if (e.weight > (heap.peek()?.weight ?: 0)) { heap.poll(); heap.add(e) }
        }

        for (dictFile in dictFiles) {
            try {
                dictFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---") || trimmed.startsWith("...")) return@forEachLine
                        if (trimmed.startsWith("name:") || trimmed.startsWith("version:") || trimmed.startsWith("sort:") || trimmed.startsWith("use_preset_")) return@forEachLine

                        val parts = trimmed.split("\t")
                        if (parts.size >= 3) {
                            val word = parts[0]
                            // 联想续写用不到超长词，限制 2..6 字，控制内存
                            if (word.length < 2 || word.length > 6) return@forEachLine
                            val weight = if (parts.size >= 4) parts[3].toIntOrNull() ?: 0 else parts[2].toIntOrNull() ?: 0
                            if (weight < MIN_WEIGHT_THRESHOLD) return@forEachLine
                            val entry = AssociationEntry(word, "", weight)
                            if (word.length == 2) {
                                // 只进首字表（单字联想）
                                offer(firstCharHeaps, word.substring(0, 1), MAX_ENTRIES_PER_BUCKET, entry)
                            } else {
                                // ≥3 字：进前 2 字表（词组联想），桶小故上限可放宽
                                offer(prefix2Heaps, word.substring(0, 2), PREFIX2_BUCKET_CAP, entry)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val result = HashMap<String, List<AssociationEntry>>((firstCharHeaps.size + prefix2Heaps.size) * 2)
        var totalCount = 0
        firstCharHeaps.forEach { (k, heap) ->
            val sorted = heap.sortedByDescending { it.weight }
            result[k] = sorted
            totalCount += sorted.size
        }
        prefix2Heaps.forEach { (k, heap) ->
            val sorted = heap.sortedByDescending { it.weight }
            // 前 2 字 key 与首字 key 不冲突（长度不同），直接放同一 map
            result[k] = sorted
            totalCount += sorted.size
        }
        firstCharHeaps.clear(); prefix2Heaps.clear()
        Log.d(TAG, "联想索引: ${result.size} 桶, $totalCount 词条, 耗时 ${System.currentTimeMillis() - dictIndexBuildTime}ms")
        return result
    }

    /**
     * 词语联想：查询以 prefix 为前缀的词语（支持分页加载更多）
     * 索引未就绪时立即返回空并触发后台构建 —— 绝不在主线程 sleep 等待。
     * 桶选择：prefix 为 1 字用首字桶（2 字词），≥2 字用前 2 字桶（更长词组）。
     */
    fun getAssociations(prefix: String, limit: Int = 20, timeoutMs: Long = 0, pageWalk: Int = 10): List<String> {
        if (prefix.isEmpty()) return emptyList()

        val index = dictIndex
        if (!dictIndexBuilt || index == null) {
            startIndexBuildAsync()
            return emptyList()
        }

        val bucketKey = if (prefix.length == 1) prefix else prefix.substring(0, 2)
        val candidates = index[bucketKey] ?: return emptyList()

        // 分页：pageWalk 每 +10 翻一页
        val page = ((pageWalk - 10) / 10).coerceAtLeast(0)
        val need = (page + 1) * limit

        val seen = HashSet<String>(need * 2)
        val matches = ArrayList<Pair<String, Int>>()
        val pLen = prefix.length
        for (entry in candidates) {
            val fw = entry.fullWord
            if (fw.length > pLen && fw.startsWith(prefix)) {
                val displayWord = fw.substring(pLen)
                if (seen.add(displayWord)) matches.add(displayWord to entry.weight)
            }
        }
        val allMatches = matches.sortedByDescending { it.second }.map { it.first }
        val offset = page * limit
        if (offset >= allMatches.size) return emptyList()
        return allMatches.subList(offset, minOf(offset + limit, allMatches.size))
    }

    /** 清除索引（词库更新后调用） */
    fun clearAssociationIndex() {
        dictIndex = null
        dictIndexBuilt = false
        synchronized(pendingAssocCallbacks) { pendingAssocCallbacks.clear() }
    }
}
