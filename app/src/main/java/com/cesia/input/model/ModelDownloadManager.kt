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

    // ==================== Zipformer 语音模型下载 ====================

    /**
     * 下载 Zipformer 语音识别模型（多文件）
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
                    onProgress?.invoke(localName, (downloadedBytes * 100 / totalBytes.coerceAtLeast(1L)).toInt())
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
                        }
                    }
                }

                downloadedBytes += fileDownloaded

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.delete()
                    return@withContext Result.failure(Exception("Failed to rename $localName"))
                }

                completedFiles++
                Log.i(TAG, "Zipformer file downloaded: $localName (${destFile.length()} bytes)")
                val percent = if (totalBytes > 0) {
                    (downloadedBytes * 100 / totalBytes).toInt()
                } else {
                    (completedFiles * 100 / totalFiles)
                }
                onProgress?.invoke(localName, percent.coerceIn(0, 100))
            }

            modelManager.markInstalled("sherpa-zipformer", ModelInfo.ModelType.VOICE)
            Log.i(TAG, "Zipformer model download complete: ${zipformerDir.absolutePath}")
            Result.success(zipformerDir)

        } catch (e: Exception) {
            Log.e(TAG, "Zipformer download failed", e)
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
        onProgress: ((fileName: String, percent: Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelId = "qwen25-1.5b-mnn"
            val modelInfo = ModelRegistry.getById(modelId)
                ?: return@withContext Result.failure(Exception("未知模型: $modelId"))

            val modelDir = File(modelsDir, modelInfo.fileName)
            modelDir.mkdirs()

            val files = ModelRegistry.MNN_MODEL_FILES
            val totalFiles = files.size
            var completedFiles = 0

            for (file in files) {
                val destFile = File(modelDir, file)

                // 文件已存在且大小合理则跳过
                if (destFile.exists() && destFile.length() > 100) {
                    Log.i(TAG, "MNN file already exists: $file (${destFile.length()} bytes)")
                    completedFiles++
                    onProgress?.invoke(file, (completedFiles * 100 / totalFiles).toInt())
                    continue
                }

                val url = ModelRegistry.getMnnFileUrl(file)
                Log.i(TAG, "Downloading MNN file: $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code} for $file")
                    )
                }

                val body = response.body
                    ?: return@withContext Result.failure(Exception("Empty response for $file"))

                val tempFile = File(modelDir, "$file.tmp")
                var fileDownloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) {
                                tempFile.delete()
                                return@withContext Result.failure(Exception("下载已取消"))
                            }
                            output.write(buffer, 0, bytesRead)
                            fileDownloaded += bytesRead
                        }
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.delete()
                    return@withContext Result.failure(Exception("重命名失败: $file"))
                }

                completedFiles++
                val percent = (completedFiles * 100 / totalFiles).toInt()
                onProgress?.invoke(file, percent.coerceIn(0, 100))
                Log.i(TAG, "MNN file downloaded: $file (${destFile.length()} bytes)")
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
        onProgress: ((modelName: String, percent: Int) -> Unit)? = null
    ): Result<File> {
        return downloadMnnModel(onProgress)
    }

    /**
     * 下载 TTS 语音合成模型（Vits 中文）
     * 下载 model.onnx + tokens.txt 到 local_models/tts/ 目录
     */
    suspend fun downloadTts(
        onProgress: ((fileName: String, percent: Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val ttsDir = File(modelsDir, "tts")
            ttsDir.mkdirs()

            val files = listOf("model.onnx", "tokens.txt")
            val totalFiles = files.size
            var completedFiles = 0

            for (file in files) {
                val url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-tts-zh-hf-theresa-2023-12-28/resolve/main/$file"

                repeat(3) { attempt ->
                    try {
                        val request = Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()

                        if (!response.isSuccessful) {
                            if (attempt == 2) {
                                return@withContext Result.failure(
                                    Exception("HTTP ${response.code} for $file")
                                )
                            }
                            return@repeat
                        }

                        val body = response.body
                            ?: return@withContext Result.failure(Exception("Empty response for $file"))

                        val destFile = File(ttsDir, file)
                        val tempFile = File(ttsDir, "$file.tmp")

                        body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (!isActive) {
                                        tempFile.delete()
                                        return@withContext Result.failure(Exception("下载已取消"))
                                    }
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                        }

                        if (destFile.exists()) destFile.delete()
                        if (!tempFile.renameTo(destFile)) {
                            tempFile.delete()
                            return@withContext Result.failure(Exception("重命名失败: $file"))
                        }

                        completedFiles++
                        val percent = (completedFiles * 100 / totalFiles).toInt()
                        onProgress?.invoke(file, percent.coerceIn(0, 100))
                        Log.i(TAG, "TTS file downloaded: $file (${destFile.length()} bytes)")
                        return@repeat
                    } catch (e: Exception) {
                        if (attempt == 2) throw e
                        Log.w(TAG, "TTS download attempt ${attempt + 1} failed for $file: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "TTS model download complete: ${ttsDir.absolutePath}")
            Result.success(ttsDir)

        } catch (e: Exception) {
            Log.e(TAG, "TTS model download failed", e)
            Result.failure(e)
        }
    }

    /**
     * 获取模型存储目录总大小
     */
    fun getTotalModelSize(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
