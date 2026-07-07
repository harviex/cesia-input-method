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

        // === 下载源：full.zip 包含所有词库、schema、lua、opencc ===
        const val FULL_DICT_URL = "https://github.com/iDvel/rime-ice/releases/download/2026.06.03/full.zip"

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
                Log.i(TAG, "开始下载: $FULL_DICT_URL")

                val request = Request.Builder().url(FULL_DICT_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    onComplete(false, "下载失败: HTTP ${response.code}")
                    return@Thread
                }

                val body = response.body ?: run {
                    onComplete(false, "下载失败: 空响应")
                    return@Thread
                }

                val totalBytes = body.contentLength()
                onProgress(0, 0, totalBytes, "正在下载词库 (${formatSize(totalBytes)})...")

                // 流式下载，边写边更新进度
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

                // 解压全部文件，去掉 zip 内 "full/" 前缀，平铺到 rime 根目录
                // （否则词库会落到 rime/full/ 子目录，Rime 引擎读不到，下载的词库无法被 import）
                var extracted = 0
                val zis = ZipInputStream(tempZip.inputStream())
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 去掉可能的 "full/" 前缀，确保文件落在 rime 根目录
                        val relativeName = entry.name.removePrefix("full/")
                        val outFile = File(rimeDir, relativeName)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output ->
                            zis.copyTo(output)
                        }
                        extracted++
                        Log.d(TAG, "解压: ${relativeName} (${outFile.length() / 1024}KB)")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                zis.close()
                tempZip.delete()

                if (extracted == 0) {
                    onComplete(false, "解压失败: 未提取到任何文件")
                    return@Thread
                }

                Log.i(TAG, "解压完成: $extracted 个文件")

                // 更新状态
                prefs.edit()
                    .putBoolean(PREF_DICT_DOWNLOADED, true)
                    .putLong(PREF_LAST_SYNC, System.currentTimeMillis())
                    .apply()

                // 统计词库信息
                val info = getDictInfo()
                onProgress(100, totalBytes, totalBytes, "词库下载完成！")
                onComplete(true, "词库下载完成！共 $extracted 个文件，${info.dictCount} 条词条")

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
        val rimeDir = getRimeDir()
        // 递归统计所有子目录中的文件大小
        val totalSize = rimeDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val fileCount = rimeDir.walkTopDown().filter { it.isFile }.count()

        // 统计 .dict.yaml 文件中的词条数
        var dictCount = 0
        rimeDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".dict.yaml")) {
                dictCount += countDictEntries(file)
            }
        }

        return DictInfo(
            dictCount = dictCount,
            dictSize = totalSize,
            fileCount = fileCount,
            downloaded = prefs.getBoolean(PREF_DICT_DOWNLOADED, false),
            lastSync = prefs.getLong(PREF_LAST_SYNC, 0)
        )
    }

    fun hasDownloadedDict(): Boolean = prefs.getBoolean(PREF_DICT_DOWNLOADED, false)

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
