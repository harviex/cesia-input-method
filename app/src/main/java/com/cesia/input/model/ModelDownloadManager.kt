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

                // 多镜像下载
                val result = downloadWithMirrorFallback(zipformerMirrors, file, destFile)
                if (result == null) {
                    return@withContext Result.failure(
                        Exception("所有镜像均下载失败: $file")
                    )
                }

                downloadedBytes += destFile.length()
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
        modelId: String = "qwen25-1.5b-mnn",
        onProgress: ((fileName: String, percent: Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
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

                // 下载（使用 modelId 对应的仓库 URL）
                val url = ModelRegistry.getMnnFileUrl(file, modelId)
                try {
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("下载失败 HTTP ${response.code}: $file")
                        )
                    }
                    val body = response.body ?: return@withContext Result.failure(
                        Exception("下载失败: 空响应 $file")
                    )
                    // 先写到临时文件，成功后再重命名
                    val tempFile = File(destFile.parent, "${destFile.name}.tmp")
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destFile.exists()) destFile.delete()
                    tempFile.renameTo(destFile)
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("下载失败 $file: ${e.message}")
                    )
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
        return downloadMnnModel(modelId = model.id, onProgress = onProgress)
    }

    /**
     * 获取模型存储目录总大小
     */
    fun getTotalModelSize(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
