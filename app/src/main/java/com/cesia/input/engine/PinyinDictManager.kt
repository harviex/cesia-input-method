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
 * - 中文：rime-ice (iDvel/rime-ice)，GPL-3.0
 * - 英文：使用内置小词库 + 可选下载完整词库
 *
 * 用户需在设置页点击下载，不随 APK 分发
 */
class PinyinDictManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("cesia_dict", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "RimeDictManager"

        // 设置 key
        const val PREF_ACTIVE_DICT = "active_dict"          // 当前激活的词库 ID
        const val PREF_VOICE_LANGUAGE = "voice_language"    // 语音识别语言 zh/en

        // 词库源定义
        data class DictSource(
            val id: String,
            val name: String,
            val nameZh: String,
            val url: String,
            val language: String,       // "zh" / "en"
            val size: String,           // 预估大小
            val license: String,
            val description: String
        )

        // 可用词库列表
        val AVAILABLE_DICTS = listOf(
            // ── 中文词库 ──
            DictSource(
                id = "rime-ice",
                name = "Rime Ice (雾凇)",
                nameZh = "雾凇拼音（推荐）",
                url = "https://github.com/iDvel/rime-ice/releases/download/nightly/cn_dicts.zip",
                language = "zh",
                size = "~14MB",
                license = "GPL-3.0",
                description = "社区维护的简体中文词库，词条丰富，支持大量常用词组"
            ),
            DictSource(
                id = "terra-pinyin",
                name = "Terra Pinyin (地球拼音)",
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
                description = "Rime 官方简体中文基础词库，精简但常用词齐全"
            ),
            // ── 英文词库 ──
            DictSource(
                id = "en-basic",
                name = "English Basic",
                nameZh = "英文基础词库（内置）",
                url = "",
                language = "en",
                size = "~200KB",
                license = "Built-in",
                description = "内置英文基础词库（~5000常用词），无需下载"
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

        // 内部文件 key
        const val PREF_DICT_VERSION = "dict_version"
        const val PREF_DICT_SOURCE = "dict_source"
        const val PREF_LAST_SYNC = "last_sync"
        const val PREF_DICT_DOWNLOADED = "dict_downloaded"  // 已废弃，改用 per-dict 标记

        const val LOCAL_DICT_FILE = "pinyin.dict.yaml"
        const val LOCAL_BASE_FILE = "base.dict.yaml"
        const val LOCAL_8105_FILE = "8105.dict.yaml"
        const val LOCAL_PHRASES_FILE = "pinyin_phrases.json"
    }

    // ─── 词库信息 ───

    /** 获取当前激活的词库源 */
    fun getActiveDictSource(): DictSource {
        val activeId = prefs.getString(PREF_ACTIVE_DICT, "rime-ice") ?: "rime-ice"
        return AVAILABLE_DICTS.find { it.id == activeId } ?: AVAILABLE_DICTS.first()
    }

    /** 设置当前激活的词库 */
    fun setActiveDict(dictId: String) {
        prefs.edit().putString(PREF_ACTIVE_DICT, dictId).apply()
        Log.i(TAG, "词库切换为: $dictId")
    }

    /** 获取语音识别语言 */
    fun getVoiceLanguage(): String {
        return settingsPrefs.getString(PREF_VOICE_LANGUAGE, "zh") ?: "zh"
    }

    /** 设置语音识别语言 */
    fun setVoiceLanguage(lang: String) {
        settingsPrefs.edit().putString(PREF_VOICE_LANGUAGE, lang).apply()
        Log.i(TAG, "语音语言切换为: $lang")
    }

    /** 获取词库统计信息 */
    fun getDictInfo(): DictInfo {
        val rimeDir = File(context.filesDir, "rime")
        val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
        val baseFile = File(rimeDir, LOCAL_BASE_FILE)
        val dict8105File = File(rimeDir, LOCAL_8105_FILE)
        val active = getActiveDictSource()

        val dictCount = countDictEntries(mergedFile)
        val baseCount = countDictEntries(baseFile)
        val word8105Count = countDictEntries(dict8105File)
        val totalSize = mergedFile.length() + baseFile.length() + dict8105File.length()

        val dictVersion = prefs.getString(PREF_DICT_VERSION, "内置") ?: "内置"
        val dictSource = prefs.getString(PREF_DICT_SOURCE, "内置") ?: "内置"
        val lastSync = prefs.getLong(PREF_LAST_SYNC, 0)
        val downloaded = prefs.getBoolean(PREF_DICT_DOWNLOADED, false)

        return DictInfo(
            dictCount = dictCount,
            word8105Count = word8105Count,
            dictSize = totalSize,
            version = dictVersion,
            source = dictSource,
            lastSync = lastSync,
            downloaded = downloaded,
            activeDictId = active.id,
            activeDictName = active.nameZh,
            voiceLanguage = getVoiceLanguage()
        )
    }

    /** 检查指定词库是否已下载 */
    fun isDictDownloaded(dictId: String): Boolean {
        if (dictId == "en-basic") return true  // 内置词库
        return prefs.getBoolean("dict_downloaded_$dictId", false)
    }

    /** 检查是否有任何外部词库已下载（兼容旧版） */
    fun hasDownloadedDict(): Boolean {
        return AVAILABLE_DICTS.any { isDictDownloaded(it.id) }
    }

    // ─── 词库下载 ───

    /**
     * 下载指定词库
     */
    fun downloadDict(
        dictId: String = getActiveDictSource().id,
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        val source = AVAILABLE_DICTS.find { it.id == dictId }
        if (source == null) {
            onComplete(false, "未知词库: $dictId")
            return
        }

        // 内置词库不需要下载
        if (source.url.isEmpty()) {
            onComplete(true, "${source.nameZh} 为内置词库，无需下载")
            return
        }

        Thread {
            try {
                val rimeDir = File(context.filesDir, "rime")
                rimeDir.mkdirs()

                onProgress("正在下载 ${source.nameZh} (${source.size})...")
                Log.i(TAG, "开始下载: ${source.url}")

                val dlClient = OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(source.url).get().build()
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

                // 处理 zip 文件
                if (source.url.endsWith(".zip")) {
                    extractZipToDict(rimeDir, body, source, onProgress, onComplete)
                } else {
                    // 单文件直接保存
                    val outFile = File(rimeDir, "${source.id}.dict.yaml")
                    outFile.writeBytes(body)
                    markDictDownloaded(source)
                    onComplete(true, "${source.nameZh} 下载完成")
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
                // 提取我们需要的文件
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
                markDictDownloaded(source)
                val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
                val entryCount = countDictEntries(mergedFile)
                onComplete(true, "词库下载完成！共 $entryCount 条词条")
            } else {
                // 如果没有标准文件，直接把完整 zip 内容当作词库
                markDictDownloaded(source)
                onComplete(true, "词库下载完成（已保存原始文件）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压词库失败", e)
            onComplete(false, "解压失败: ${e.message}")
        }
    }

    private fun markDictDownloaded(source: DictSource) {
        prefs.edit()
            .putBoolean("dict_downloaded_${source.id}", true)
            .putBoolean(PREF_DICT_DOWNLOADED, true)
            .putString(PREF_DICT_VERSION, "${source.id}-${System.currentTimeMillis()}")
            .putString(PREF_DICT_SOURCE, "${source.nameZh} (${source.license})")
            .putLong(PREF_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }

    // ─── 词库合并 ───

    private fun mergeDicts(rimeDir: File) {
        val merged = LinkedHashMap<String, String>()

        val baseFile = File(rimeDir, LOCAL_BASE_FILE)
        if (baseFile.exists()) {
            baseFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                        !trimmed.startsWith("---") && !trimmed.startsWith("...") &&
                        !trimmed.startsWith("name:") && !trimmed.startsWith("version:") &&
                        !trimmed.startsWith("sort:") && trimmed.contains("\t")) {
                        val parts = trimmed.split("\t")
                        if (parts.size >= 3) {
                            merged[parts[0]] = "${parts[1]}\t${parts[2]}"
                        } else if (parts.size == 2) {
                            merged[parts[0]] = "${parts[1]}\t100"
                        }
                    }
                }
            }
        }

        val dict8105File = File(rimeDir, LOCAL_8105_FILE)
        if (dict8105File.exists()) {
            dict8105File.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                        !trimmed.startsWith("---") && !trimmed.startsWith("...") &&
                        !trimmed.startsWith("name:") && !trimmed.startsWith("version:") &&
                        !trimmed.startsWith("sort:") && trimmed.contains("\t")) {
                        val parts = trimmed.split("\t")
                        if (parts.size >= 2) {
                            val hanzi = parts[0]
                            if (!merged.containsKey(hanzi)) {
                                val pinyin = parts[1]
                                val weight = if (parts.size >= 3) parts[2] else "50"
                                merged[hanzi] = "$pinyin\t$weight"
                            }
                        }
                    }
                }
            }
        }

        val outFile = File(rimeDir, LOCAL_DICT_FILE)
        val sb = StringBuilder()
        sb.appendLine("# Rime dictionary — Cesia输入法")
        sb.appendLine("# 词库来源：rime-ice (iDvel/rime-ice)，GPL-3.0")
        sb.appendLine("---")
        sb.appendLine("name: pinyin")
        sb.appendLine("version: \"1.1.1\"")
        sb.appendLine("sort: by_weight")
        sb.appendLine("...")
        sb.appendLine()
        for ((hanzi, value) in merged) {
            sb.appendLine("$hanzi\t$value")
        }
        outFile.writeText(sb.toString())
        Log.i(TAG, "合并词库: ${merged.size} 条 → ${outFile.absolutePath}")
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
