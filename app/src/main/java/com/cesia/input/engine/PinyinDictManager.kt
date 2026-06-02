package com.cesia.input.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Rime 词库管理器
 * 支持分词库下载：基础词库（GBK字表+base+英文+opencc）、扩展词库（41448+ext）、腾讯词库
 * 
 * 词库来源：rime-ice (iDvel/rime-ice)，GPL-3.0 许可证
 */
class PinyinDictManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("cesia_dict", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "RimeDictManager"
        const val PREF_DICT_DOWNLOADED = "dict_downloaded"
        const val PREF_BASE_DOWNLOADED = "base_downloaded"
        const val PREF_EXT_DOWNLOADED = "ext_downloaded"
        const val PREF_TENCENT_DOWNLOADED = "tencent_downloaded"
        const val PREF_EN_DOWNLOADED = "en_downloaded"
        const val PREF_OPENCC_DOWNLOADED = "opencc_downloaded"
        const val PREF_LAST_SYNC = "last_sync"

        // === 下载源 ===
        // rime-ice 中文词库（nightly）
        const val CN_DICTS_URL = "https://github.com/iDvel/rime-ice/releases/download/nightly/cn_dicts.zip"
        // rime-ice 英文词库
        const val EN_DICTS_URL = "https://github.com/iDvel/rime-ice/releases/download/nightly/en_dicts.zip"
        // rime-ice OpenCC 转换表
        const val OPENCC_URL = "https://github.com/iDvel/rime-ice/releases/download/nightly/opencc.zip"

        // === 本地目录 ===
        private const val RIME_DIR = "rime"
        const val LOCAL_DICT_FILE = "pinyin.dict.yaml"

        // GBK 字表（内置，从 assets 释放）
        const val LOCAL_GBK_FILE = "gbk.dict.yaml"
        const val LOCAL_8105_FILE = "8105.dict.yaml"  // fallback

        // === 词包定义 ===
        /**
         * 基础词包（必须下载）
         * 包含：GBK字表（或8105） + base基础词库 + 英文词库 + OpenCC
         */
        const val BUNDLE_BASE = "base"

        /**
         * 扩展词包（推荐下载）
         * 包含：41448扩展词库 + ext扩展词库
         */
        const val BUNDLE_EXT = "ext"

        /**
         * 腾讯词包（可选，体积大）
         * 包含：tencent词库（~100万词条）
         */
        const val BUNDLE_TENCENT = "tencent"
    }

    /**
     * 词库包信息
     */
    data class BundleInfo(
        val id: String,
        val name: String,
        val description: String,
        val estimatedSize: String,
        val required: Boolean,
        val recommended: Boolean,  // 是否默认勾选
        val url: String,
        val files: List<String>    // zip中包含的.dict.yaml文件名
    )

    /**
     * 获取所有可用的词库包
     */
    fun getAvailableBundles(): List<BundleInfo> = listOf(
        BundleInfo(
            id = BUNDLE_BASE,
            name = "基础词库",
            description = "GBK字表（~21000字）+ 基础词库（~55万词条）+ 英文词库 + OpenCC",
            estimatedSize = "~20MB",
            required = true,
            recommended = true,
            url = CN_DICTS_URL,  // 基础包含GBK+base，EN和OPENCC单独处理
            files = listOf("base.dict.yaml")
        ),
        BundleInfo(
            id = BUNDLE_EXT,
            name = "扩展词库",
            description = "41448扩展词库 + ext扩展词库（~40万词条）",
            estimatedSize = "~15MB",
            required = false,
            recommended = false,
            url = CN_DICTS_URL,
            files = listOf("41448.dict.yaml", "ext.dict.yaml")
        ),
        BundleInfo(
            id = BUNDLE_TENCENT,
            name = "腾讯词库",
            description = "腾讯词库（~100万词条）",
            estimatedSize = "~17MB",
            required = false,
            recommended = false,
            url = CN_DICTS_URL,
            files = listOf("tencent.dict.yaml")
        )
    )

    /**
     * 下载选中的词库包
     * @param bundles 选中的包ID列表，如 ["base", "ext"]
     * @param onProgress 进度回调
     * @param onComplete 完成回调
     */
    fun downloadBundles(
        bundles: List<String>,
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (bundles.isEmpty()) {
            onComplete(false, "请选择要下载的词库包")
            return
        }

        Thread {
            try {
                val rimeDir = File(context.filesDir, RIME_DIR)
                rimeDir.mkdirs()

                // 释放内置 GBK 字表（如果本地不存在）
                ensureGbkTable(rimeDir)

                var totalExtracted = 0

                // 下载中文词库（如果选中了任何中文包）
                if (bundles.contains(BUNDLE_BASE) || bundles.contains(BUNDLE_EXT) || bundles.contains(BUNDLE_TENCENT)) {
                    val cnFiles = mutableListOf<String>()
                    if (bundles.contains(BUNDLE_BASE)) {
                        cnFiles.add("base.dict.yaml")
                        cnFiles.add("8105.dict.yaml")
                    }
                    if (bundles.contains(BUNDLE_EXT)) {
                        cnFiles.add("41448.dict.yaml")
                        cnFiles.add("ext.dict.yaml")
                    }
                    if (bundles.contains(BUNDLE_TENCENT)) {
                        cnFiles.add("tencent.dict.yaml")
                    }

                    onProgress("正在下载中文词库...")
                    val extracted = downloadAndExtract(CN_DICTS_URL, rimeDir, cnFiles)
                    totalExtracted += extracted
                }

                // 下载英文词库（随基础包）
                if (bundles.contains(BUNDLE_BASE)) {
                    onProgress("正在下载英文词库...")
                    val enFiles = listOf("en.dict.yaml", "en_ext.dict.yaml", "en_aliases.dict.yaml")
                    totalExtracted += downloadAndExtract(EN_DICTS_URL, rimeDir, enFiles)
                }

                // 下载 OpenCC（随基础包）
                if (bundles.contains(BUNDLE_BASE)) {
                    onProgress("正在下载 OpenCC 转换表...")
                    val openccDir = File(rimeDir, "opencc")
                    openccDir.mkdirs()
                    val openccFiles = listOf("STPhrases.txt", "STCharacters.txt", "TSCharacters.txt",
                        "HKVariants.txt", "JPVariants.txt", "TWVariants.txt")
                    totalExtracted += downloadAndExtract(OPENCC_URL, openccDir, openccFiles, isTextFile = true)
                }

                if (totalExtracted == 0) {
                    onComplete(false, "未下载到任何词库文件")
                    return@Thread
                }

                // 合并选中的词库
                onProgress("正在合并词库...")
                val entryCount = mergeSelectedDicts(rimeDir, bundles)

                // 更新状态
                updateBundlePrefs(bundles)

                onComplete(true, "词库下载完成！共 ${entryCount} 条词条")

            } catch (e: Exception) {
                Log.e(TAG, "下载词库失败", e)
                onComplete(false, "下载失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 下载并解压指定文件
     */
    private fun downloadAndExtract(
        url: String,
        destDir: File,
        targetFiles: List<String>,
        isTextFile: Boolean = false
    ): Int {
        Log.i(TAG, "下载: $url, 目标文件: $targetFiles")

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.w(TAG, "下载失败: HTTP ${response.code} for $url")
            return 0
        }

        val body = response.body?.bytes() ?: return 0
        Log.i(TAG, "下载完成: ${body.size / 1024}KB")

        var extracted = 0
        val zis = ZipInputStream(body.inputStream())
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            val name = entry.name.substringAfterLast("/")
            if (!entry.isDirectory && targetFiles.any { name.contains(it) || name == it }) {
                val outFile = File(destDir, name)
                outFile.outputStream().use { output ->
                    zis.copyTo(output)
                }
                Log.i(TAG, "解压: $name (${outFile.length() / 1024}KB)")
                extracted++
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()

        return extracted
    }

    /**
     * 合并选中的词库到 pinyin.dict.yaml
     * 规则：先合并字表（8105或GBK），然后按 base → ext → tencent 顺序叠加词库
     */
    private fun mergeSelectedDicts(rimeDir: File, bundles: List<String>): Int {
        // 词条：字 → "拼音\t权重"
        // LinkedHashMap 保证顺序：先插入的（字表）优先级低，后插入的（大词库）优先级高
        val entries = LinkedHashMap<String, Pair<String, Long>>()

        // Step 1: 字表（优先级最低，只填充缺失的字）
        // 优先用 GBK（~21000字），fallback 到 8105（~8000字）
        val gbkFile = File(rimeDir, LOCAL_GBK_FILE)
        val charTableFile = if (gbkFile.exists()) gbkFile else File(rimeDir, LOCAL_8105_FILE)
        if (charTableFile.exists()) {
            loadDictEntries(charTableFile, entries, isCharTable = true)
            Log.i(TAG, "加载字表: ${charTableFile.name}, 当前总条目: ${entries.size}")
        }

        // Step 2: 基础词库（base）
        if (bundles.contains(BUNDLE_BASE)) {
            val baseFile = File(rimeDir, "base.dict.yaml")
            if (baseFile.exists()) {
                val before = entries.size
                loadDictEntries(baseFile, entries, isCharTable = false)
                Log.i(TAG, "加载 base: +${entries.size - before} 条, 当前总条目: ${entries.size}")
            }
        }

        // Step 3: 扩展词库（41448 + ext）
        if (bundles.contains(BUNDLE_EXT)) {
            val ext41448File = File(rimeDir, "41448.dict.yaml")
            if (ext41448File.exists()) {
                val before = entries.size
                loadDictEntries(ext41448File, entries, isCharTable = false)
                Log.i(TAG, "加载 41448: +${entries.size - before} 条")
            }
            val extFile = File(rimeDir, "ext.dict.yaml")
            if (extFile.exists()) {
                val before = entries.size
                loadDictEntries(extFile, entries, isCharTable = false)
                Log.i(TAG, "加载 ext: +${entries.size - before} 条")
            }
        }

        // Step 4: 腾讯词库（最大，最后加载，优先级最高）
        if (bundles.contains(BUNDLE_TENCENT)) {
            val tencentFile = File(rimeDir, "tencent.dict.yaml")
            if (tencentFile.exists()) {
                val before = entries.size
                loadDictEntries(tencentFile, entries, isCharTable = false)
                Log.i(TAG, "加载 tencent: +${entries.size - before} 条")
            }
        }

        // 写入合并后的 pinyin.dict.yaml
        val outFile = File(rimeDir, LOCAL_DICT_FILE)
        val sb = StringBuilder()
        sb.appendLine("# Rime dictionary — Cesia输入法")
        sb.appendLine("# 词库来源：rime-ice (iDvel/rime-ice)，GPL-3.0")
        sb.appendLine("# 合并：${bundles.joinToString(" + ")}")
        sb.appendLine("---")
        sb.appendLine("name: pinyin")
        sb.appendLine("version: \"1.1.1\"")
        sb.appendLine("sort: by_weight")
        sb.appendLine("...")
        sb.appendLine()
        for ((hanzi, pair) in entries) {
            sb.appendLine("$hanzi\t${pair.first}\t${pair.second}")
        }
        outFile.writeText(sb.toString())

        Log.i(TAG, "合并完成: ${entries.size} 条 → ${outFile.name} (${outFile.length() / 1024 / 1024}MB)")
        return entries.size
    }

    /**
     * 加载词条到 entries map
     * @param isCharTable 是否是字表（只填充缺失的字，不覆盖已有词条）
     */
    private fun loadDictEntries(file: File, entries: LinkedHashMap<String, Pair<String, Long>>, isCharTable: Boolean) {
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---") ||
                    trimmed.startsWith("...") || trimmed.startsWith("name:") ||
                    trimmed.startsWith("version:") || trimmed.startsWith("sort:")) {
                    return@forEach
                }
                val parts = trimmed.split("\t")
                if (parts.size >= 3) {
                    val hanzi = parts[0]
                    val pinyin = parts[1]
                    val weight = parts[2].toLongOrNull() ?: 1L
                    if (isCharTable) {
                        // 字表：只填充缺失的字
                        if (!entries.containsKey(hanzi)) {
                            entries[hanzi] = Pair(pinyin, weight)
                        }
                    } else {
                        // 词库：直接覆盖（后加载的优先级高）
                        entries[hanzi] = Pair(pinyin, weight)
                    }
                } else if (parts.size == 2) {
                    val hanzi = parts[0]
                    val pinyin = parts[1]
                    if (isCharTable) {
                        if (!entries.containsKey(hanzi)) {
                            entries[hanzi] = Pair(pinyin, 50L)
                        }
                    } else {
                        entries[hanzi] = Pair(pinyin, 100L)
                    }
                }
            }
        }
    }

    /**
     * 更新词库包下载状态
     */
    private fun updateBundlePrefs(bundles: List<String>) {
        val editor = prefs.edit()
        if (bundles.contains(BUNDLE_BASE)) {
            editor.putBoolean(PREF_BASE_DOWNLOADED, true)
            editor.putBoolean(PREF_EN_DOWNLOADED, true)
            editor.putBoolean(PREF_OPENCC_DOWNLOADED, true)
        }
        if (bundles.contains(BUNDLE_EXT)) {
            editor.putBoolean(PREF_EXT_DOWNLOADED, true)
        }
        if (bundles.contains(BUNDLE_TENCENT)) {
            editor.putBoolean(PREF_TENCENT_DOWNLOADED, true)
        }
        editor.putBoolean(PREF_DICT_DOWNLOADED, true)
        editor.putLong(PREF_LAST_SYNC, System.currentTimeMillis())
        editor.apply()
    }

    /**
     * 获取词库统计信息
     */
    fun getDictInfo(): DictInfo {
        val rimeDir = File(context.filesDir, RIME_DIR)
        val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
        val dictCount = if (mergedFile.exists()) countDictEntries(mergedFile) else 0
        val totalSize = rimeDir.listFiles()?.sumOf { it.length() } ?: 0

        val bundles = mutableListOf<String>()
        if (prefs.getBoolean(PREF_BASE_DOWNLOADED, false)) bundles.add("基础")
        if (prefs.getBoolean(PREF_EXT_DOWNLOADED, false)) bundles.add("扩展")
        if (prefs.getBoolean(PREF_TENCENT_DOWNLOADED, false)) bundles.add("腾讯")

        return DictInfo(
            dictCount = dictCount,
            dictSize = totalSize,
            downloaded = prefs.getBoolean(PREF_DICT_DOWNLOADED, false),
            bundles = bundles,
            lastSync = prefs.getLong(PREF_LAST_SYNC, 0)
        )
    }

    /**
     * 确保 GBK 字表存在（从 assets 释放到 rime 目录）
     */
    private fun ensureGbkTable(rimeDir: File) {
        val gbkFile = File(rimeDir, LOCAL_GBK_FILE)
        if (gbkFile.exists() && gbkFile.length() > 1000) return  // 已存在，跳过

        try {
            context.assets.open(LOCAL_GBK_FILE).use { input ->
                gbkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "GBK 字表已释放: ${gbkFile.absolutePath} (${gbkFile.length() / 1024}KB)")
        } catch (e: Exception) {
            Log.w(TAG, "释放 GBK 字表失败: ${e.message}")
        }
    }

    /**
     * 统计词条数
     */
    private fun countDictEntries(file: File): Int {
        if (!file.exists()) return 0
        var count = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---") &&
                    !trimmed.startsWith("...") && !trimmed.startsWith("name:") &&
                    !trimmed.startsWith("version:") && !trimmed.startsWith("sort:") &&
                    trimmed.contains("\t")) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * 检查是否已下载外部词库
     */
    fun hasDownloadedDict(): Boolean = prefs.getBoolean(PREF_DICT_DOWNLOADED, false)

    /**
     * 获取词库文件路径（供 RimeEngine 使用）
     */
    fun getMergedDictPath(): String? {
        val f = File(context.filesDir, "$RIME_DIR/$LOCAL_DICT_FILE")
        return if (f.exists()) f.absolutePath else null
    }

    /**
     * 兼容旧版 PinyinEngine：返回合并后的词库路径
     */
    fun getDictFilePath(): String? = getMergedDictPath()

    /**
     * 兼容旧版 PinyinEngine：词库已合并，不再有单独的 phrases 文件
     */
    fun getPhrasesFilePath(): String? = null

    data class DictInfo(
        val dictCount: Int,
        val dictSize: Long,
        val downloaded: Boolean,
        val bundles: List<String> = emptyList(),
        val lastSync: Long
    )
}
