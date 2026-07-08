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
 * 下载 rime-ice full.zip 到外部存储目录（getExternalFilesDir/rime/），解压全部文件不合并
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
        const val PREF_LAST_SYNC = "last_sync"
        // 词库统计信息（下载完成时一次性计算并持久化，避免每次返回设置页遍历整个 rime 目录）
        const val PREF_DICT_SIZE = "dict_size"
        const val PREF_DICT_FILE_COUNT = "dict_file_count"
        const val PREF_DICT_ENTRY_COUNT = "dict_entry_count"

        // === 下载源：full.zip 包含所有词库、schema、lua、opencc ===
        // 主源走 ghproxy.net 镜像（与模型下载一致，国内可访问），失败回退 GitHub 原链
        private val DICT_URLS = listOf(
            "https://ghproxy.net/https://github.com/iDvel/rime-ice/releases/download/2026.06.03/full.zip",
            "https://github.com/iDvel/rime-ice/releases/download/2026.06.03/full.zip"
        )

        // === 本地目录名（外部存储，无需权限，卸载时删除） ===
        const val RIME_DIR = "rime"
    }

    /**
     * 获取 Rime 数据目录（外部存储）
     * 路径：/sdcard/Android/data/com.cesia.input/files/rime/
     */
    fun getRimeDir(): File {
        val dir = File(context.getExternalFilesDir(null), RIME_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 下载完整词库（full.zip），流式下载带进度回调
     * @param onProgress 进度回调 (进度百分比 0-100, 已下载字节, 总字节, 状态文字)
     */
    fun downloadFullDict(
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, message: String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                val rimeDir = getRimeDir()
                onProgress(0, 0, 0, "正在连接服务器...")

                // 依次尝试所有下载源（镜像优先，原链兜底）
                var lastErr = ""
                for (tryUrl in DICT_URLS) {
                    var thisErr = ""
                    try {
                        Log.i(TAG, "开始下载: $tryUrl")
                        onProgress(0, 0, 0, "正在连接: ${tryUrl.substringBefore("github.com")}...")
                        val request = Request.Builder().url(tryUrl).build()
                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) {
                            thisErr = "HTTP ${response.code}"
                            response.close()
                        } else {
                            val body = response.body
                            if (body == null) {
                                thisErr = "空响应"
                            } else {
                                val totalBytes = body.contentLength()
                                onProgress(0, 0, totalBytes, "正在下载词库 (${formatSize(totalBytes)})...")

                                val tempZip = File(rimeDir, "full.zip.tmp")
                                var downloadedBytes = 0L
                                val buffer = ByteArray(8192)
                                body.byteStream().use { input ->
                                    tempZip.outputStream().use { output ->
                                        var bytesRead: Int
                                        var lastCallbackTime = 0L
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                            downloadedBytes += bytesRead
                                            val now = System.currentTimeMillis()
                                            if (now - lastCallbackTime > 200) {
                                                lastCallbackTime = now
                                                val pct = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceAtMost(99) else 0
                                                onProgress(pct, downloadedBytes, totalBytes, "下载中... ${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}")
                                            }
                                        }
                                    }
                                }
                                body.close()

                                onProgress(99, downloadedBytes, totalBytes, "正在解压词库文件...")

                                var extracted = 0
                                val zis = ZipInputStream(tempZip.inputStream())
                                var entry: ZipEntry? = zis.nextEntry
                                while (entry != null) {
                                    if (!entry.isDirectory) {
                                        val relativeName = entry.name.removePrefix("full/")
                                        val outFile = File(rimeDir, relativeName)
                                        outFile.parentFile?.mkdirs()
                                        outFile.outputStream().use { output -> zis.copyTo(output) }
                                        extracted++
                                        Log.d(TAG, "解压: ${relativeName} (${outFile.length() / 1024}KB)")
                                    }
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                }
                                zis.close()
                                tempZip.delete()

                                if (extracted == 0) {
                                    thisErr = "解压失败: 未提取到任何文件"
                                } else {
                                    Log.i(TAG, "解压完成: $extracted 个文件")
                                    prefs.edit()
                                        .putBoolean(PREF_DICT_DOWNLOADED, true)
                                        .putLong(PREF_LAST_SYNC, System.currentTimeMillis())
                                        .apply()
                                    val info = computeAndCacheDictInfo()
                                    onProgress(100, totalBytes, totalBytes, "词库下载完成！")
                                    onComplete(true, "词库下载完成！共 $extracted 个文件，${info.dictCount} 条词条")
                                    return@Thread
                                }
                            }
                        }
                    } catch (e: Exception) {
                        thisErr = e.message ?: "未知错误"
                        Log.w(TAG, "下载源失败: $tryUrl -> $thisErr")
                    }
                    lastErr = thisErr
                    // 尝试下一个源
                }
                onComplete(false, "所有下载源均失败，最后错误: $lastErr")
            } catch (e: Exception) {
                Log.e(TAG, "下载词库失败", e)
                onComplete(false, "下载失败: ${e.message}")
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun getDictInfo(): DictInfo {
        val downloaded = prefs.getBoolean(PREF_DICT_DOWNLOADED, false)
        val lastSync = prefs.getLong(PREF_LAST_SYNC, 0)
        var dictSize = prefs.getLong(PREF_DICT_SIZE, 0L)
        var fileCount = prefs.getInt(PREF_DICT_FILE_COUNT, 0)
        var dictCount = prefs.getInt(PREF_DICT_ENTRY_COUNT, 0)
        // 兼容旧版：已下载但统计信息未持久化时，计算一次并缓存（之后不再遍历）
        if (downloaded && dictSize == 0L && fileCount == 0 && dictCount == 0) {
            val info = computeAndCacheDictInfo()
            dictSize = info.dictSize
            fileCount = info.fileCount
            dictCount = info.dictCount
        }
        return DictInfo(
            dictCount = dictCount,
            dictSize = dictSize,
            fileCount = fileCount,
            downloaded = downloaded,
            lastSync = lastSync
        )
    }

    /**
     * 统计词库信息（仅下载完成时调用一次）。遍历整个 rime 目录，
     * 结果持久化到 SP，之后 getDictInfo() 不再遍历。
     */
    private fun computeAndCacheDictInfo(): DictInfo {
        val rimeDir = getRimeDir()
        var totalSize = 0L
        var fileCount = 0
        var dictCount = 0
        rimeDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalSize += file.length()
                fileCount++
                if (file.name.endsWith(".dict.yaml")) {
                    dictCount += countDictEntries(file)
                }
            }
        }
        prefs.edit()
            .putLong(PREF_DICT_SIZE, totalSize)
            .putInt(PREF_DICT_FILE_COUNT, fileCount)
            .putInt(PREF_DICT_ENTRY_COUNT, dictCount)
            .apply()
        return DictInfo(
            dictCount = dictCount,
            dictSize = totalSize,
            fileCount = fileCount,
            downloaded = prefs.getBoolean(PREF_DICT_DOWNLOADED, false),
            lastSync = prefs.getLong(PREF_LAST_SYNC, 0)
        )
    }

    fun hasDownloadedDict(): Boolean = prefs.getBoolean(PREF_DICT_DOWNLOADED, false)

    /** 清除已下载状态标记（卸载词库时调用） */
    fun clearDownloadedState() {
        prefs.edit()
            .putBoolean(PREF_DICT_DOWNLOADED, false)
            .putLong(PREF_LAST_SYNC, 0L)
            .putLong(PREF_DICT_SIZE, 0L)
            .putInt(PREF_DICT_FILE_COUNT, 0)
            .putInt(PREF_DICT_ENTRY_COUNT, 0)
            .apply()
    }

    fun getDictFilePath(): String? {
        val rimeDir = getRimeDir()
        return if (rimeDir.exists() && rimeDir.listFiles()?.isNotEmpty() == true) {
            rimeDir.absolutePath
        } else null
    }

    fun getPhrasesFilePath(): String? = null

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

    data class DictInfo(
        val dictCount: Int,
        val dictSize: Long,
        val fileCount: Int = 0,
        val downloaded: Boolean,
        val lastSync: Long
    )
}
