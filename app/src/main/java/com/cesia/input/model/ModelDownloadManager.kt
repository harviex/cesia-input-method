package com.cesia.input.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 模型下载管理器
 * 支持: 后台下载、进度回调、断点续传、错误处理
 * 使用 OkHttp（复用项目已有的 OkHttpClient 模式）
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
     * 下载模型
     * @param model 模型信息
     * @param force 是否强制重新下载（即使文件已存在）
     * @param onProgress 进度回调（0-100）
     * @return Result<File>
     */
    suspend fun download(
        model: ModelInfo,
        force: Boolean = false,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val destFile = File(modelsDir, model.fileName)

            // 文件已存在且不强制重下
            if (destFile.exists() && !force) {
                Log.i(TAG, "Model already exists: ${model.fileName}")
                onProgress?.invoke(100)
                return@withContext Result.success(destFile)
            }

            Log.i(TAG, "Downloading ${model.name} from ${model.downloadUrl}")

            val request = Request.Builder()
                .url(model.downloadUrl)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code} for ${model.downloadUrl}")
                )
            }

            val body = response.body
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val totalBytes = body.contentLength().takeIf { it > 0 } ?: model.sizeBytes

            // 使用临时文件，下载完成后重命名（防止残缺文件）
            val tempFile = File(modelsDir, "${model.fileName}.tmp")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!isActive) {
                            tempFile.delete()
                            return@withContext Result.failure(
                                Exception("Download cancelled")
                            )
                        }
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val percent = ((downloaded * 100) / totalBytes).toInt()
                            .coerceIn(0, 100)
                        onProgress?.invoke(percent)
                    }
                }
            }

            // 移动到最终位置
            if (destFile.exists()) destFile.delete()
            if (tempFile.renameTo(destFile)) {
                // 标记模型已安装
                modelManager.markInstalled(model.id, model.type)
                Log.i(TAG, "Download complete: ${model.fileName} (${destFile.length()} bytes)")
                Result.success(destFile)
            } else {
                tempFile.delete()
                Result.failure(Exception("Failed to rename temp file"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.id}", e)
            Result.failure(e)
        }
    }

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
            file.delete()
        } else false
    }

    /** 下载 Zipformer 语音识别模型（多文件）
     * 下载 encoder.onnx + decoder.onnx + joiner.onnx + tokens.txt 到 local_models/zipformer/ 目录
     * onProgress 回调: (当前文件名, 整体进度0-100)
     */
    suspend fun downloadZipformer(
        onProgress: ((fileName: String, percent: Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val zipformerDir = File(modelsDir, "zipformer")
            zipformerDir.mkdirs()

            val files = ModelRegistry.ZIPFORMER_FILES
            val totalFiles = files.size
            var completedFiles = 0

            // 预计算总大小（用于字节级进度）
            var totalBytes = 0L
            var downloadedBytes = 0L

            for (file in files) {
                val localName = ModelRegistry.getZipformerLocalName(file)
                val destFile = File(zipformerDir, localName)

                // 文件已存在则跳过
                if (destFile.exists()) {
                    Log.i(TAG, "Zipformer file already exists: $localName")
                    totalBytes += destFile.length()
                    downloadedBytes += destFile.length()
                    completedFiles++
                    onProgress?.invoke(localName, (downloadedBytes * 100 / totalBytes.coerceAtLeast(1)).toInt())
                    continue
                }

                val url = ModelRegistry.getZipformerFileUrl(file)
                Log.i(TAG, "Downloading Zipformer file: $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code} for $file")
                    )
                }

                val body = response.body
                    ?: return@withContext Result.failure(Exception("Empty response for $file"))

                val fileSize = body.contentLength().takeIf { it > 0 } ?: 0L
                totalBytes += fileSize

                val tempFile = File(zipformerDir, "$localName.tmp")
                var fileDownloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) {
                                tempFile.delete()
                                return@withContext Result.failure(Exception("Download cancelled"))
                            }
                            output.write(buffer, 0, bytesRead)
                            fileDownloaded += bytesRead
                            downloadedBytes += bytesRead

                            // 字节级整体进度
                            val overallPercent = if (totalBytes > 0) {
                                (downloadedBytes * 100 / totalBytes).toInt()
                            } else {
                                (completedFiles * 100 / totalFiles)
                            }
                            onProgress?.invoke(localName, overallPercent.coerceIn(0, 100))
                        }
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.delete()
                    return@withContext Result.failure(Exception("Failed to rename $localName"))
                }

                completedFiles++
                Log.i(TAG, "Zipformer file downloaded: $localName (${destFile.length()} bytes)")
            }

            // 标记模型已安装
            modelManager.markInstalled("sherpa-zipformer", ModelInfo.ModelType.VOICE)
            Log.i(TAG, "Zipformer model download complete: ${zipformerDir.absolutePath}")
            Result.success(zipformerDir)
        } catch (e: Exception) {
            Log.e(TAG, "Zipformer download failed", e)
            Result.failure(e)
        }
    }

    /**
     * 下载指定类型的 AI 模型（用于 AI 润色模型下载）
     */
    suspend fun downloadByType(
        type: ModelInfo.ModelType,
        tier: ModelInfo.Tier,
        onProgress: ((modelName: String, percent: Int) -> Unit)? = null
    ): Result<File> {
        val models = ModelRegistry.ALL_MODELS.filter { it.type == type && it.tier == tier }
        if (models.isEmpty()) {
            return Result.failure(Exception("No models for type=$type tier=$tier"))
        }
        var lastResult: Result<File>? = null
        for (model in models) {
            if (isDownloaded(model.id)) continue
            lastResult = download(model) { p ->
                onProgress?.invoke(model.name, p)
            }
            if (lastResult.isFailure) break
        }
        return lastResult ?: Result.success(models.firstNotNullOfOrNull {
            File(modelsDir, it.fileName).takeIf { f -> f.exists() }
        } ?: modelsDir)
    }

    /** 下载指定的 AI 润色模型 */
    suspend fun downloadAiModel(
        model: ModelInfo,
        onProgress: ((modelName: String, percent: Int) -> Unit)? = null
    ): Result<File> {
        if (isDownloaded(model.id)) {
            val existing = File(modelsDir, model.fileName).takeIf { it.exists() }
            return if (existing != null) Result.success(existing)
            else Result.failure(Exception("模型文件不存在: ${model.fileName}"))
        }
        return download(model) { p ->
            onProgress?.invoke(model.name, p)
        }
    }

    /**
     * 下载某个 tier 的所有模型（识别 + 润色）
     * 返回最后一个下载结果
     */
    suspend fun downloadTier(
        tier: ModelInfo.Tier,
        onProgress: ((modelName: String, percent: Int) -> Unit)? = null
    ): Result<File> {
        val models = ModelRegistry.ALL_MODELS.filter { it.tier == tier }
        if (models.isEmpty()) {
            return Result.failure(Exception("No models for tier $tier"))
        }
        var lastResult: Result<File>? = null
        for (model in models) {
            if (isDownloaded(model.id)) continue  // 已安装跳过
            lastResult = download(model) { p ->
                onProgress?.invoke(model.name, p)
            }
            if (lastResult.isFailure) break
        }
        return lastResult ?: Result.success(models.firstNotNullOfOrNull {
            File(modelsDir, it.fileName).takeIf { f -> f.exists() }
        } ?: modelsDir)
    }

    /**
     * 卸载某个 tier 的所有模型
     */
    fun deleteTier(tier: ModelInfo.Tier): Int {
        val models = ModelRegistry.ALL_MODELS.filter { it.tier == tier }
        var count = 0
        for (model in models) {
            if (deleteModel(model.id)) count++
        }
        return count
    }

    /**
     * 获取当前已安装的 tier（如果有）
     */
    fun getInstalledTier(): ModelInfo.Tier? {
        return ModelInfo.Tier.entries.firstOrNull { tier ->
            ModelRegistry.ALL_MODELS.filter { it.tier == tier }
                .any { isDownloaded(it.id) }
        }
    }

    /**
     * 获取模型存储目录总大小
     */
    fun getTotalModelSize(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
