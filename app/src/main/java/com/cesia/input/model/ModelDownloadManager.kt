package com.cesia.input.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 模型下载管理器
 * 支持: Zipformer 语音模型、Qwen3.5-0.8B-MNN AI 模型
 */
class ModelDownloadManager(private val context: Context) {

    companion object Formatter {
        private const val TAG = "ModelDownload"
        private const val BUFFER_SIZE = 8192

        fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
                bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }

    sealed class DownloadState {
        data class Progress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
        data class Completed(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val modelManager = ModelManager(context)
    private val modelsDir get() = modelManager.modelsDir

    /**
     * 检查模型是否已安装
     */
    fun isDownloaded(modelId: String): Boolean = modelManager.isModelInstalled(modelId)

    /**
     * 删除已下载模型
     */
    fun deleteModel(modelId: String): Boolean {
        val info = ModelRegistry.getById(modelId) ?: return false
        val file = File(modelsDir, info.fileName)
        return if (file.exists()) {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } else false
    }

    // ==================== 多镜像下载 ====================

    /**
     * 镜像源列表（按优先级排序）
     * - hf-mirror.com：国内镜像，大多数情况可用
     * - huggingface.co：官方源，可能被墙但部分网络可用
     */
    private val zipformerMirrors = listOf(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main",
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"
    )

    private val mnnMirrors = listOf(
        "https://hf-mirror.com/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main",
        "https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main"
    )

    /**
     * 带镜像自动切换的下载
     * @param mirrors 镜像 URL 列表
     * @param file 文件名
     * @param destFile 目标文件
     * @return 成功返回使用的 URL，失败返回 null
     */
    private suspend fun downloadWithMirrorFallback(
        mirrors: List<String>,
        file: String,
        destFile: File
    ): String? {
        val tempFile = File(destFile.parent, "${destFile.name}.tmp")

        for ((index, mirror) in mirrors.withIndex()) {
            val url = "$mirror/$file"
            try {
                Log.i(TAG, "尝试镜像 ${index + 1}/${mirrors.size}: $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "镜像 ${index + 1} 返回 HTTP ${response.code}，切换下一个")
                    continue
                }

                val body = response.body ?: continue

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!coroutineContext.isActive) {
                                tempFile.delete()
                                return null
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.delete()
                    continue
                }

                Log.i(TAG, "下载成功 (镜像 ${index + 1}): $file (${destFile.length()} bytes)")
                return url

            } catch (e: Exception) {
                Log.w(TAG, "镜像 ${index + 1} 失败: ${e.message}，切换下一个")
                tempFile.delete()
            }
        }

        return null
    }

    // ==================== Zipformer 语音模型下载 ====================

    /**
     * 下载 Zipformer 语音识别模型（多文件）
     * 下载 encoder.onnx + decoder.onnx + joiner.onnx + tokens.txt 到 local_models/zipformer/ 目录
     * onProgress 回调: (当前文件名, 整体进度0-100)
     */
    suspend fun downloadZipformer(
        onProgress: ((fileName: String, percent: Double, downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val zipformerDir = File(modelsDir, "zipformer")
            zipformerDir.mkdirs()

            val files = ModelRegistry.ZIPFORMER_FILES

            // === 第一阶段：HEAD 获取真实文件大小 ===
            data class FileSpec(val name: String, val localName: String, val destFile: File, val url: String, val totalSize: Long)
            val fileSpecs = mutableListOf<FileSpec>()
            var grandTotal = 0L

            for (file in files) {
                val localName = ModelRegistry.getZipformerLocalName(file)
                val destFile = File(zipformerDir, localName)

                if (destFile.exists() && destFile.length() > 0) {
                    grandTotal += destFile.length()
                    // 已缓存文件也上报进度，避免进度条/状态栏无反馈
                    onProgress?.invoke(destFile.name, 100.0, destFile.length(), destFile.length())
                    continue
                }

                // 尝试 HEAD 获取大小
                var fileSize = 0L
                for (mirror in zipformerMirrors) {
                    try {
                        val headReq = Request.Builder().url("$mirror/$file").head().build()
                        val headResp = client.newCall(headReq).execute()
                        if (headResp.isSuccessful) {
                            fileSize = headResp.header("Content-Length")?.toLongOrNull() ?: 0L
                            if (fileSize > 0) break
                        }
                    } catch (_: Exception) { }
                }
                // HEAD 失败时用估算值（int8 量化版体积）
                if (fileSize == 0L) {
                    fileSize = when (file) {
                        "encoder-epoch-99-avg-1.int8.onnx" -> 174L * 1024 * 1024
                        "decoder-epoch-99-avg-1.int8.onnx" -> 13L * 1024 * 1024
                        "joiner-epoch-99-avg-1.int8.onnx" -> 3L * 1024 * 1024
                        "tokens.txt" -> 500L * 1024
                        else -> 1024L
                    }
                }
                val url = "${zipformerMirrors[0]}/$file"
                fileSpecs.add(FileSpec(file, localName, destFile, url, fileSize))
                grandTotal += fileSize
            }

            var downloadedBytes = grandTotal - fileSpecs.sumOf { it.totalSize }
            var lastCallbackTime = 0L

            // === 第二阶段：逐文件流式下载 ===
            for (spec in fileSpecs) {
                var success = false
                val mirrorUrls = zipformerMirrors.map { "$it/${spec.name}" }

                for (mirrorUrl in mirrorUrls) {
                    try {
                        val request = Request.Builder().url(mirrorUrl).build()
                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) continue
                        val body = response.body ?: continue

                        val tempFile = File(spec.destFile.parentFile, "${spec.destFile.name}.tmp")
                        var fileDownloaded = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (!coroutineContext.isActive) {
                                        tempFile.delete()
                                        return@withContext Result.failure(Exception("下载已取消"))
                                    }
                                    output.write(buffer, 0, bytesRead)
                                    fileDownloaded += bytesRead
                                    val overallDownloaded = downloadedBytes + fileDownloaded
                                    val pct = if (grandTotal > 0) (overallDownloaded.toDouble() / grandTotal * 100).coerceAtMost(99.9) else 0.0
                                    // 节流：每 200ms 或每 1% 更新一次 UI
                                    val now = System.currentTimeMillis()
                                    if (now - lastCallbackTime > 200) {
                                        lastCallbackTime = now
                                        onProgress?.invoke(spec.localName, Math.round(pct * 10.0) / 10.0, overallDownloaded, grandTotal)
                                    }
                                }
                            }
                        }

                        if (spec.destFile.exists()) spec.destFile.delete()
                        tempFile.renameTo(spec.destFile)
                        downloadedBytes += spec.destFile.length()
                        val filePct = if (grandTotal > 0) (downloadedBytes.toDouble() / grandTotal * 100).coerceAtMost(100.0) else 100.0
                        onProgress?.invoke(spec.localName, Math.round(filePct * 10.0) / 10.0, downloadedBytes, grandTotal)
                        success = true
                        Log.i(TAG, "Zipformer file downloaded: ${spec.localName} (${spec.destFile.length()} bytes)")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "镜像下载失败 ($mirrorUrl): ${spec.name}: ${e.message}")
                    }
                }

                if (!success) {
                    return@withContext Result.failure(
                        Exception("所有镜像均下载失败: ${spec.name}")
                    )
                }
            }

            modelManager.markInstalled("sherpa-zipformer", ModelInfo.ModelType.VOICE)
            Log.i(TAG, "Zipformer model download complete: ${zipformerDir.absolutePath}")
            Result.success(zipformerDir)

        } catch (e: Exception) {
            Log.e(TAG, "Zipformer download failed", e)
            Result.failure(e)
        }
    }

    // ==================== 整包 .tar.bz2 下载 + 解压 ====================

    /**
     * 下载 .tar.bz2 整包并解压到 modelsDir/<localDir>
     * 用于纯中文 zipformer 2025（GitHub release 整包分发）
     * 解压后将 int8 权重文件重命名为标准名（encoder.onnx / decoder.onnx / joiner.onnx），
     * 以复用现有 isZipformerModel() 校验逻辑。
     */
    suspend fun downloadArchive(
        modelId: String,
        onProgress: ((fileName: String, percent: Double, downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val info = ModelRegistry.getById(modelId) ?: return@withContext Result.failure(Exception("未知模型: $modelId"))
            val destDir = File(modelsDir, info.fileName)
            destDir.mkdirs()
            val archiveFile = File(modelsDir, "${info.fileName}.tar.bz2")

            // 1. 下载整包
            val request = Request.Builder().url(info.downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("下载失败 HTTP ${response.code}"))
            }
            val body = response.body ?: return@withContext Result.failure(Exception("空响应"))
            val totalBytes = response.body?.contentLength() ?: info.sizeBytes
            var downloaded = 0L
            val temp = File(modelsDir, "${info.fileName}.tar.bz2.tmp")
            body.byteStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        if (!coroutineContext.isActive) { temp.delete(); return@withContext Result.failure(Exception("下载已取消")) }
                        output.write(buf, 0, read)
                        downloaded += read
                        val pct = if (totalBytes > 0) (downloaded.toDouble() / totalBytes * 100).coerceAtMost(100.0) else 0.0
                        onProgress?.invoke("${info.fileName}.tar.bz2", pct, downloaded, totalBytes)
                    }
                }
            }
            if (archiveFile.exists()) archiveFile.delete()
            if (!temp.renameTo(archiveFile)) { temp.delete(); return@withContext Result.failure(Exception("归档重命名失败")) }

            // 2. 解压（依赖系统 tar，Android 自带 /system/bin/tar）
            val tarBin = if (File("/system/bin/tar").exists()) "/system/bin/tar"
                         else if (File("/bin/tar").exists()) "/bin/tar" else "tar"
            val proc = ProcessBuilder(tarBin, "xjf", archiveFile.absolutePath, "-C", destDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exit = proc.waitFor()
            val out = proc.inputStream.bufferedReader().readText()
            if (exit != 0) {
                Log.e(TAG, "tar 解压失败 (exit=$exit): $out")
                return@withContext Result.failure(Exception("解压失败: $out".take(200)))
            }
            archiveFile.delete()

            // 3. 将 int8 权重重命名为标准名（如 encoder-epoch-99-avg-1.int8.onnx -> encoder.onnx）
            val renameMap = mapOf(
                "encoder-epoch-99-avg-1.int8.onnx" to "encoder.onnx",
                "decoder-epoch-99-avg-1.int8.onnx" to "decoder.onnx",
                "joiner-epoch-99-avg-1.int8.onnx" to "joiner.onnx"
            )
            destDir.listFiles()?.forEach { f ->
                renameMap[f.name]?.let { newName ->
                    val target = File(destDir, newName)
                    if (!target.exists()) f.renameTo(target)
                }
                // 包内可能嵌套一层同名目录，把其内容上提
                if (f.isDirectory) {
                    f.listFiles()?.forEach { inner -> inner.renameTo(File(destDir, inner.name)) }
                    if (f.listFiles().isNullOrEmpty()) f.delete()
                }
            }

            modelManager.markInstalled(modelId, ModelInfo.ModelType.VOICE)
            Log.i(TAG, "Archive model extracted: ${destDir.absolutePath}")
            Result.success(destDir)
        } catch (e: Exception) {
            Log.e(TAG, "Archive download failed", e)
            Result.failure(e)
        }
    }

    // ==================== MNN AI 模型下载 ====================

    /**
     * 下载 Qwen3.5-0.8B-MNN 模型（多文件）
     * 下载到 local_models/qwen35-0.8b-mnn/ 目录
     *
     * @param onProgress 进度回调 (文件名, 整体进度0-100)
     * @return 成功返回目录 File，失败返回异常
     */
    suspend fun downloadMnnModel(
        modelId: String = "qwen35-2b-mnn",
        onProgress: ((fileName: String, percent: Double, downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelInfo = ModelRegistry.getById(modelId)
                ?: return@withContext Result.failure(Exception("未知模型: $modelId"))

            val modelDir = File(modelsDir, modelInfo.fileName)
            modelDir.mkdirs()

            val files = ModelRegistry.MNN_MODEL_FILES

            // === 第一阶段：HEAD 请求获取每个文件的真实大小 ===
            data class FileSpec(val name: String, val destFile: File, val url: String, val totalSize: Long)
            val fileSpecs = mutableListOf<FileSpec>()
            var grandTotal = 0L

            for (file in files) {
                val destFile = File(modelDir, file)
                val url = ModelRegistry.getMnnFileUrl(file, modelId)

                // 已存在的文件直接计入进度
                if (destFile.exists() && destFile.length() > 0) {
                    grandTotal += destFile.length()
                    // 已缓存文件也上报进度，避免进度条/状态栏无反馈
                    onProgress?.invoke(destFile.name, 100.0, destFile.length(), destFile.length())
                    continue
                }

                // HEAD 请求获取真实文件大小
                var fileSize = 0L
                try {
                    val headReq = Request.Builder().url(url).head().build()
                    val headResp = client.newCall(headReq).execute()
                    if (headResp.isSuccessful) {
                        fileSize = headResp.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                } catch (_: Exception) { }

                // HEAD 失败时用估算值
                if (fileSize == 0L) {
                    fileSize = when {
                        file == "visual.mnn.weight" -> 196L * 1024 * 1024
                        file == "llm.mnn.weight" -> 1000L * 1024 * 1024
                        file == "visual.mnn" -> 500L * 1024
                        file == "llm.mnn" -> 50L * 1024 * 1024
                        file.endsWith(".json") -> 10L * 1024
                        file == "tokenizer.txt" -> 2L * 1024 * 1024
                        else -> 1024L
                    }
                }
                fileSpecs.add(FileSpec(file, destFile, url, fileSize))
                grandTotal += fileSize
            }

            var downloadedBytes = grandTotal - fileSpecs.sumOf { it.totalSize }
            var lastCallbackTime = 0L

            // === 第二阶段：逐文件下载，流式进度 ===
            for (spec in fileSpecs) {
                // 下载（使用 modelId 对应的仓库 URL）
                var success = false
                val mirrors = listOf(
                    spec.url,
                    spec.url.replace("hf-mirror.com", "huggingface.co"),
                    spec.url.replace("huggingface.co", "hf-mirror.com")
                ).distinct()

                for (mirrorUrl in mirrors) {
                    try {
                        val request = Request.Builder().url(mirrorUrl).build()
                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) continue
                        val body = response.body ?: continue

                        val tempFile = File(spec.destFile.parentFile, "${spec.destFile.name}.tmp")
                        var fileDownloaded = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (!coroutineContext.isActive) {
                                        tempFile.delete()
                                        return@withContext Result.failure(Exception("下载已取消"))
                                    }
                                    output.write(buffer, 0, bytesRead)
                                    fileDownloaded += bytesRead
                                    val overallDownloaded = downloadedBytes + fileDownloaded
                                    val pct = if (grandTotal > 0) (overallDownloaded.toDouble() / grandTotal * 100).coerceAtMost(99.9) else 0.0
                                    // 节流：每 200ms 更新一次 UI，避免频繁刷新
                                    val now = System.currentTimeMillis()
                                    if (now - lastCallbackTime > 200) {
                                        lastCallbackTime = now
                                        onProgress?.invoke(spec.name, Math.round(pct * 10.0) / 10.0, overallDownloaded, grandTotal)
                                    }
                                }
                            }
                        }

                        if (spec.destFile.exists()) spec.destFile.delete()
                        tempFile.renameTo(spec.destFile)
                        downloadedBytes += spec.destFile.length()
                        // 文件完成时强制回调一次
                        val filePct = if (grandTotal > 0) (downloadedBytes.toDouble() / grandTotal * 100).coerceAtMost(100.0) else 100.0
                        onProgress?.invoke(spec.name, Math.round(filePct * 10.0) / 10.0, downloadedBytes, grandTotal)
                        success = true
                        Log.i(TAG, "MNN file downloaded: ${spec.name} (${spec.destFile.length()} bytes)")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "镜像下载失败 ($mirrorUrl): ${spec.name}: ${e.message}")
                    }
                }

                if (!success) {
                    return@withContext Result.failure(
                        Exception("所有镜像均下载失败: ${spec.name}")
                    )
                }
            }

            // 下载完成后，用 assets 中的 config.json 覆盖（确保 hidden_size 等参数正确）
            try {
                val configFile = File(modelDir, "config.json")
                context.assets.open("qwen35-2b-mnn/config.json").use { input ->
                    FileOutputStream(configFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "MNN model: config.json replaced from assets")
            } catch (e: Exception) {
                Log.w(TAG, "MNN model: failed to copy config.json from assets", e)
            }

            modelManager.markInstalled(modelId, ModelInfo.ModelType.AI)
            Log.i(TAG, "MNN model download complete: ${modelDir.absolutePath}")
            Result.success(modelDir)

        } catch (e: Exception) {
            Log.e(TAG, "MNN model download failed", e)
            Result.failure(e)
        }
    }

    /**
     * 下载指定的 AI 润色模型（兼容旧接口）
     */
    suspend fun downloadAiModel(
        model: ModelInfo,
        onProgress: ((modelName: String, percent: Double, downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> {
        return downloadMnnModel(modelId = model.id, onProgress = onProgress)
    }

    /**
     * 获取模型存储目录总大小
     */
    fun getTotalModelSize(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
