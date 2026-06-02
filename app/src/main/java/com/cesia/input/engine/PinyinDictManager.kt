package com.cesia.input.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Rime 词库管理器
 * 支持多词库下载、切换、中英文词库
 *
 * 词库来源：
 * - rime-ice: 雾凇拼音 (iDvel/rime-ice)，GPL-3.0，~14MB，精校~14万词
 * - rime-fast-xhup: 快速词库 (boomker/rime-fast-xhup)，Mixed，~60MB，~150万词
 * - rime-dict: 增强合集 (Iorest/rime-dict)，Mixed，~50MB，~180万精品词（无 Release，需手动下载）
 * - terra-pinyin: 地球拼音，LGPL-3.0，~8MB
 * - pinyin-simp: 简拼基础，LGPL-3.0，~4MB
 * - en-basic/en-full: 内置英文词库
 *
 * 用户需在设置页点击下载，不随 APK 分发
 */
class PinyinDictManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cesia_dict", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ─── 词库源定义（放在 class 内部、companion 外部，方便外部访问） ───

    data class DictSource(
        val id: String,
        val name: String,
        val nameZh: String,
        val url: String,
        val language: String,
        val size: String,
        val license: String,
        val description: String
    )

    companion object {
        private const val TAG = "RimeDictManager"

        const val PREF_ACTIVE_DICT = "active_dict"
        const val PREF_VOICE_LANGUAGE = "voice_language"

        // 可用词库列表
        val AVAILABLE_DICTS = listOf(
            // ── 中文词库 ──
            DictSource(
                id = "rime-ice",
                name = "Rime Ice",
                nameZh = "雾凇拼音（推荐）",
                url = "https://github.com/iDvel/rime-ice/releases/download/nightly/cn_dicts.zip",
                language = "zh",
                size = "~14MB",
                license = "GPL-3.0",
                description = "社区维护的简体中文词库，词条丰富，全拼/双拼兼容"
            ),
            DictSource(
                id = "rime-fast-xhup",
                name = "Rime Fast XHUP",
                nameZh = "快速词库（推荐，150万词）",
                url = "https://github.com/boomker/rime-fast-xhup/releases/latest/download/cn_dicts.zip",
                language = "zh",
                size = "~60MB",
                license = "Mixed",
                description = "雾凇拼音增强版，精简优化，~150 万词条。官方 Release 可自动下载"
            ),
            DictSource(
                id = "rime-dict",
                name = "Rime Dict Extended",
                nameZh = "增强词库合集（180万词）",
                url = "",
                language = "zh",
                size = "~50MB",
                license = "Mixed",
                description = "高度优化扩展词库，~180万精品词条，全局去重。无 Release，需手动下载或填写自定义 URL"
            ),
            DictSource(
                id = "terra-pinyin",
                name = "Terra Pinyin",
                nameZh = "地球拼音词库",
                url = "https://github.com/rime/rime-terra-pinyin/releases/download/terra-pinyin-0.2/terra_pinyin.dict.yaml",
                language = "zh",
                size = "~8MB",
                license = "LGPL-3.0",
                description = "地球拼音方案词库，双拼/全拼兼容"
            ),
            DictSource(
                id = "pinyin-simp",
                name = "Pinyin Simplified",
                nameZh = "简拼基础词库",
                url = "https://github.com/rime/rime-pinyin-simp/releases/download/0.2/pinyin_simp.dict.yaml",
                language = "zh",
                size = "~4MB",
                license = "LGPL-3.0",
                description = "Rime 官方简体中文基础词库"
            ),
            // ── 英文词库 ──
            DictSource(
                id = "en-basic",
                name = "English Basic",
                nameZh = "英文基础（内置）",
                url = "",
                language = "en",
                size = "~200KB",
                license = "Built-in",
                description = "英文基础词库（~5000常用词），无需下载"
            ),
            DictSource(
                id = "en-full",
                name = "English Full",
                nameZh = "英文完整词库",
                url = "",
                language = "en",
                size = "~0KB",
                license = "Built-in",
                description = "英文完整词库（~50000词），包含专业词汇"
            )
        )

        const val PREF_DICT_VERSION = "dict_version"
        const val PREF_DICT_SOURCE = "dict_source"
        const val PREF_LAST_SYNC = "last_sync"
        const val PREF_DICT_DOWNLOADED = "dict_downloaded"

        const val LOCAL_DICT_FILE = "pinyin.dict.yaml"
        const val LOCAL_BASE_FILE = "base.dict.yaml"
        const val LOCAL_8105_FILE = "8105.dict.yaml"
        const val LOCAL_PHRASES_FILE = "pinyin_phrases.json"
    }

    // ─── 词库信息 ───

    fun getActiveDictSource(): DictSource {
        val activeId = prefs.getString(PREF_ACTIVE_DICT, "rime-ice") ?: "rime-ice"
        return AVAILABLE_DICTS.find { it.id == activeId } ?: AVAILABLE_DICTS.first()
    }

    fun setActiveDict(dictId: String) {
        prefs.edit().putString(PREF_ACTIVE_DICT, dictId).apply()
        Log.i(TAG, "词库切换为: $dictId")
    }

    fun getVoiceLanguage(): String = settingsPrefs.getString(PREF_VOICE_LANGUAGE, "auto") ?: "auto"

    fun setVoiceLanguage(lang: String) {
        settingsPrefs.edit().putString(PREF_VOICE_LANGUAGE, lang).apply()
        Log.i(TAG, "语音语言切换为: $lang")
    }

    fun getDictInfo(): DictInfo {
        val rimeDir = File(context.filesDir, "rime")
        val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
        val baseFile = File(rimeDir, LOCAL_BASE_FILE)
        val dict8105File = File(rimeDir, LOCAL_8105_FILE)
        val active = getActiveDictSource()

        val dictCount = countDictEntries(mergedFile)
        val word8105Count = countDictEntries(dict8105File)
        val totalSize = mergedFile.length() + baseFile.length() + dict8105File.length()

        return DictInfo(
            dictCount = dictCount,
            word8105Count = word8105Count,
            dictSize = totalSize,
            version = prefs.getString(PREF_DICT_VERSION, "内置") ?: "内置",
            source = prefs.getString(PREF_DICT_SOURCE, "内置") ?: "内置",
            lastSync = prefs.getLong(PREF_LAST_SYNC, 0),
            downloaded = prefs.getBoolean(PREF_DICT_DOWNLOADED, false),
            activeDictId = active.id,
            activeDictName = active.nameZh,
            voiceLanguage = getVoiceLanguage()
        )
    }

    fun isDictDownloaded(dictId: String): Boolean {
        if (dictId == "en-basic") return true
        return prefs.getBoolean("dict_downloaded_$dictId", false)
    }

    fun hasDownloadedDict(): Boolean = AVAILABLE_DICTS.any { isDictDownloaded(it.id) }

    // ─── 词库下载 ───

    fun downloadDict(
        dictId: String = getActiveDictSource().id,
        customUrl: String? = null,
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        var source = AVAILABLE_DICTS.find { it.id == dictId }
        if (source == null) {
            onComplete(false, "未知词库: $dictId")
            return
        }

        val downloadUrl = if (!customUrl.isNullOrEmpty()) customUrl else source.url
        if (downloadUrl.isEmpty()) {
            onComplete(true, "${source.nameZh} 为内置词库，无需下载")
            return
        }

        // 如果有自定义 URL，创建一个临时 source 用自定义 URL
        if (!customUrl.isNullOrEmpty()) {
            source = source.copy(url = customUrl)
        }

        Thread {
            try {
                val rimeDir = File(context.filesDir, "rime")
                rimeDir.mkdirs()

                onProgress("正在下载 ${source.nameZh} (${source.size})...")
                Log.i(TAG, "开始下载: $downloadUrl")

                val dlClient = OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(downloadUrl)
                    .get()
                    .header("User-Agent", "CesiaIME/1.0")
                    .build()

                val response = dlClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    onComplete(false, "下载失败: HTTP ${response.code}")
                    return@Thread
                }

                val body = response.body?.bytes() ?: byteArrayOf()
                if (body.isEmpty()) {
                    onComplete(false, "下载数据为空")
                    return@Thread
                }

                if (downloadUrl.endsWith(".zip")) {
                    extractZipToDict(rimeDir, body, source, onProgress, onComplete)
                } else {
                    val outFile = File(rimeDir, "${source.id}.dict.yaml")
                    outFile.writeBytes(body)
                    markDictDownloaded(source)
                    onComplete(true, "${source.nameZh} 下载完成 (${outFile.length() / 1024}KB)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "下载词库失败", e)
                onComplete(false, "下载失败: ${e.message}")
            }
        }.start()
    }

    private fun extractZipToDict(
        rimeDir: File,
        zipBytes: ByteArray,
        source: DictSource,
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        try {
            onProgress("正在解压词库...")
            val tempZip = File(rimeDir, "download_${source.id}.zip")
            tempZip.writeBytes(zipBytes)

            val zipFile = java.util.zip.ZipFile(tempZip)
            val entries = zipFile.entries()
            var extractedCount = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast("/")
                if (name == LOCAL_BASE_FILE || name == LOCAL_8105_FILE ||
                    name == "ext.dict.yaml" || name.endsWith(".dict.yaml")) {
                    val outFile = File(rimeDir, name)
                    zipFile.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "解压: $name (${outFile.length() / 1024}KB)")
                    extractedCount++
                }
            }
            zipFile.close()
            tempZip.delete()

            if (extractedCount > 0) {
                onProgress("正在合并词库...")
                mergeDicts(rimeDir)
                val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
                val entryCount = countDictEntries(mergedFile)
                markDictDownloaded(source, dictCount = entryCount)
                onComplete(true, "词库下载完成！共 $entryCount 条词条")
            } else {
                // 没有标准文件，尝试其他文件名
                onProgress("未找到标准词库文件，尝试其他格式...")
                extractAllDictFiles(rimeDir, zipBytes, source, onProgress, onComplete)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压词库失败", e)
            onComplete(false, "解压失败: ${e.message}")
        }
    }

    private fun extractAllDictFiles(
        rimeDir: File,
        zipBytes: ByteArray,
        source: DictSource,
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        // 对于超大词库（如 rime-list），可能包含很多 .dict.yaml 文件
        try {
            val tempZip = File(rimeDir, "download_${source.id}.zip")
            tempZip.writeBytes(zipBytes)

            val zipFile = java.util.zip.ZipFile(tempZip)
            val entries = zipFile.entries()
            var extractedCount = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast("/")
                if (name.endsWith(".dict.yaml")) {
                    val outFile = File(rimeDir, name)
                    zipFile.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    extractedCount++
                }
            }
            zipFile.close()
            tempZip.delete()

            if (extractedCount > 0) {
                onProgress("正在合并 $extractedCount 个词库文件...")
                mergeDicts(rimeDir)
                val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
                val entryCount = countDictEntries(mergedFile)
                markDictDownloaded(source, dictCount = entryCount)
                onComplete(true, "词库下载完成！共 $entryCount 条词条")
            } else {
                markDictDownloaded(source)
                onComplete(true, "词库下载完成（已保存原始文件）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            onComplete(false, "解压失败: ${e.message}")
        }
    }

    private fun markDictDownloaded(source: DictSource, dictCount: Int = 0) {
        prefs.edit()
            .putBoolean("dict_downloaded_${source.id}", true)
            .putBoolean(PREF_DICT_DOWNLOADED, true)
            .putString(PREF_DICT_VERSION,
                if (dictCount > 0) "${source.id}-v${dictCount}w" else "${source.id}-${System.currentTimeMillis()}")
            .putString(PREF_DICT_SOURCE, "${source.nameZh} (${source.license})")
            .putLong(PREF_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }

    // ─── 词库合并 ───

    private fun mergeDicts(rimeDir: File) {
        val merged = LinkedHashMap<String, String>()

        // 1. 加载 base.dict.yaml (最高优先级)
        val baseFile = File(rimeDir, LOCAL_BASE_FILE)
        if (baseFile.exists()) {
            loadDictFile(baseFile, merged, preferExisting = true)
        }

        // 2. 加载 8105.dict.yaml (字频)
        val dict8105File = File(rimeDir, LOCAL_8105_FILE)
        if (dict8105File.exists()) {
            loadDictFile(dict8105File, merged, preferExisting = true)
        }

        // 3. 加载所有其他 .dict.yaml 文件
        rimeDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".dict.yaml") &&
                file.name != LOCAL_DICT_FILE &&
                file.name != LOCAL_BASE_FILE &&
                file.name != LOCAL_8105_FILE) {
                loadDictFile(file, merged, preferExisting = false)
                Log.i(TAG, "合并附加词库: ${file.name}")
            }
        }

        // 4. 写入合并文件
        val outFile = File(rimeDir, LOCAL_DICT_FILE)
        val sb = StringBuilder()
        sb.appendLine("# Rime dictionary - Cesia输入法")
        sb.appendLine("---")
        sb.appendLine("name: pinyin")
        sb.appendLine("version: \"1.1.4\"")
        sb.appendLine("sort: by_weight")
        sb.appendLine("...")
        sb.appendLine()
        for ((hanzi, value) in merged) {
            sb.appendLine("$hanzi\t$value")
        }
        outFile.writeText(sb.toString())
        Log.i(TAG, "合并词库: ${merged.size} 条 -> ${outFile.absolutePath}")
    }

    private fun loadDictFile(file: File, merged: LinkedHashMap<String, String>, preferExisting: Boolean) {
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                    !trimmed.startsWith("---") && !trimmed.startsWith("...") &&
                    !trimmed.startsWith("name:") && !trimmed.startsWith("version:") &&
                    !trimmed.startsWith("sort:") && trimmed.contains("\t")) {
                    val parts = trimmed.split("\t")
                    if (parts.size >= 2) {
                        val hanzi = parts[0]
                        val pinyin = parts[1]
                        val weight = if (parts.size >= 3) parts[2] else "50"
                        if (preferExisting && merged.containsKey(hanzi)) return@forEach
                        merged[hanzi] = "$pinyin\t$weight"
                    }
                }
            }
        }
    }

    private fun countDictEntries(file: File): Int {
        if (!file.exists()) return 0
        var count = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                    !trimmed.startsWith("---") && !trimmed.startsWith("...") &&
                    !trimmed.startsWith("name:") && !trimmed.startsWith("version:") &&
                    !trimmed.startsWith("sort:") && trimmed.contains("\t")) {
                    count++
                }
            }
        }
        return count
    }

    // ─── 兼容旧版 ───

    fun getDictFilePath(): String? = null
    fun getPhrasesFilePath(): String? = null

    // ─── 数据类 ───

    data class DictInfo(
        val dictCount: Int,
        val word8105Count: Int,
        val dictSize: Long,
        val version: String,
        val source: String,
        val lastSync: Long,
        val downloaded: Boolean = false,
        val activeDictId: String = "rime-ice",
        val activeDictName: String = "雾凇拼音",
        val voiceLanguage: String = "zh"
    )
}
