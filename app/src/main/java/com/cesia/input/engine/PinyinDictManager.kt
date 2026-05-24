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
 * 从 GitHub 下载 rime-ice 词库，保存到 filesDir/rime/
 * 
 * 词库来源：rime-ice (iDvel/rime-ice)，GPL-3.0 许可证
 * 用户需自行在设置页点击下载，不随 APK 分发
 */
class PinyinDictManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("cesia_dict", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "RimeDictManager"
        const val PREF_DICT_VERSION = "dict_version"
        const val PREF_DICT_SOURCE = "dict_source"
        const val PREF_LAST_SYNC = "last_sync"
        const val PREF_DICT_DOWNLOADED = "dict_downloaded"

        // rime-ice 词库下载源 (GPL-3.0)
        // base.dict.yaml: ~16MB, 55万词条，基础词库
        const val DICT_BASE_URL = "https://raw.githubusercontent.com/iDvel/rime-ice/master/cn_dicts/base.dict.yaml"
        // 8105.dict.yaml: ~0.1MB, 8000+常用字
        const val DICT_8105_URL = "https://raw.githubusercontent.com/iDvel/rime-ice/master/cn_dicts/8105.dict.yaml"

        // 本地保存路径：filesDir/rime/
        const val LOCAL_DICT_FILE = "pinyin.dict.yaml"
        const val LOCAL_BASE_FILE = "base.dict.yaml"
        const val LOCAL_8105_FILE = "8105.dict.yaml"
    }

    /**
     * 下载 rime-ice 词库到 filesDir/rime/
     * 合并 base + 8105 为 pinyin.dict.yaml
     */
    fun downloadRimeDict(
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                val rimeDir = File(context.filesDir, "rime")
                rimeDir.mkdirs()

                // Step 1: 下载 8105 字表
                onProgress("正在下载字表 (8105)...")
                val url8105 = DICT_8105_URL
                val req8105 = Request.Builder().url(url8105).get().build()
                val resp8105 = client.newCall(req8105).execute()
                if (!resp8105.isSuccessful) {
                    onComplete(false, "字表下载失败: HTTP ${resp8105.code}")
                    return@Thread
                }
                val body8105 = resp8105.body?.bytes() ?: byteArrayOf()
                if (body8105.isEmpty()) {
                    onComplete(false, "字表数据为空")
                    return@Thread
                }
                File(rimeDir, LOCAL_8105_FILE).writeBytes(body8105)
                Log.i(TAG, "8105 下载完成: ${body8105.size / 1024}KB")

                // Step 2: 下载 base 词库
                onProgress("正在下载基础词库 (~16MB)...")
                val reqBase = Request.Builder().url(DICT_BASE_URL).get().build()
                val respBase = client.newCall(reqBase).execute()
                if (!respBase.isSuccessful) {
                    onComplete(false, "词库下载失败: HTTP ${respBase.code}")
                    return@Thread
                }
                val bodyBase = respBase.body?.bytes() ?: byteArrayOf()
                if (bodyBase.isEmpty()) {
                    onComplete(false, "词库数据为空")
                    return@Thread
                }
                File(rimeDir, LOCAL_BASE_FILE).writeBytes(bodyBase)
                Log.i(TAG, "base 下载完成: ${bodyBase.size / 1024 / 1024}MB")

                // Step 3: 合并为 pinyin.dict.yaml（base + 8105 去重）
                onProgress("正在合并词库...")
                mergeDicts(rimeDir)

                // 更新状态
                prefs.edit()
                    .putBoolean(PREF_DICT_DOWNLOADED, true)
                    .putString(PREF_DICT_VERSION, "rime-ice-${System.currentTimeMillis()}")
                    .putString(PREF_DICT_SOURCE, "rime-ice (GPL-3.0)")
                    .putLong(PREF_LAST_SYNC, System.currentTimeMillis())
                    .apply()

                val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
                val entryCount = countDictEntries(mergedFile)
                onComplete(true, "词库下载完成！共 $entryCount 条词条")

            } catch (e: Exception) {
                Log.e(TAG, "下载 rime 词库失败", e)
                onComplete(false, "下载失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 合并 base.dict.yaml + 8105.dict.yaml → pinyin.dict.yaml
     * 格式：汉字\t拼音\t词频（Rime 标准格式）
     */
    private fun mergeDicts(rimeDir: File) {
        val merged = LinkedHashMap<String, String>() // 汉字 → "拼音\t词频"

        // 先读 base（权重大，优先）
        val baseFile = File(rimeDir, LOCAL_BASE_FILE)
        if (baseFile.exists()) {
            baseFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---") && !trimmed.startsWith("...") && !trimmed.startsWith("name:") && !trimmed.startsWith("version:") && !trimmed.startsWith("sort:") && trimmed.contains("\t")) {
                        val parts = trimmed.split("\t")
                        if (parts.size >= 3) {
                            val hanzi = parts[0]
                            val pinyin = parts[1]
                            val weight = parts[2]
                            merged[hanzi] = "$pinyin\t$weight"
                        } else if (parts.size == 2) {
                            val hanzi = parts[0]
                            val pinyin = parts[1]
                            merged[hanzi] = "$pinyin\t100"
                        }
                    }
                }
            }
        }

        // 再读 8105（补充 base 没有的常用字）
        val dict8105File = File(rimeDir, LOCAL_8105_FILE)
        if (dict8105File.exists()) {
            dict8105File.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---") && !trimmed.startsWith("...") && !trimmed.startsWith("name:") && !trimmed.startsWith("version:") && !trimmed.startsWith("sort:") && trimmed.contains("\t")) {
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

        // 写入合并后的 pinyin.dict.yaml
        val outFile = File(rimeDir, LOCAL_DICT_FILE)
        val sb = StringBuilder()
        sb.appendLine("# Rime dictionary — Cesia输入法")
        sb.appendLine("# 词库来源：rime-ice (iDvel/rime-ice)，GPL-3.0")
        sb.appendLine("# 合并：8105 字表 + base 基础词库")
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
        Log.i(TAG, "合并词库: ${merged.size} 条 → ${outFile.absolutePath} (${outFile.length() / 1024 / 1024}MB)")
    }

    private fun countDictEntries(file: File): Int {
        if (!file.exists()) return 0
        var count = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---") && !trimmed.startsWith("...") && !trimmed.startsWith("name:") && !trimmed.startsWith("version:") && !trimmed.startsWith("sort:") && trimmed.contains("\t")) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * 获取词库统计信息
     */
    fun getDictInfo(): DictInfo {
        val rimeDir = File(context.filesDir, "rime")
        val mergedFile = File(rimeDir, LOCAL_DICT_FILE)
        val baseFile = File(rimeDir, LOCAL_BASE_FILE)
        val dict8105File = File(rimeDir, LOCAL_8105_FILE)

        val dictCount = countDictEntries(mergedFile)
        val baseCount = countDictEntries(baseFile)
        val phraseCount = countDictEntries(dict8105File)
        val totalSize = mergedFile.length() + baseFile.length() + dict8105File.length()

        return DictInfo(
            dictCount = dictCount,
            phraseCount = phraseCount,
            dictSize = totalSize,
            phrasesSize = 0,
            version = prefs.getString(PREF_DICT_VERSION, "内置") ?: "内置",
            source = prefs.getString(PREF_DICT_SOURCE, "内置") ?: "内置",
            lastSync = prefs.getLong(PREF_LAST_SYNC, 0),
            downloaded = prefs.getBoolean(PREF_DICT_DOWNLOADED, false)
        )
    }

    /**
     * 检查是否已下载外部词库
     */
    fun hasDownloadedDict(): Boolean {
        return prefs.getBoolean(PREF_DICT_DOWNLOADED, false)
    }

    data class DictInfo(
        val dictCount: Int,
        val phraseCount: Int,
        val dictSize: Long,
        val phrasesSize: Long,
        val version: String,
        val source: String,
        val lastSync: Long,
        val downloaded: Boolean = false
    )
}
