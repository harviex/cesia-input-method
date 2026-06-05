package com.cesia.input.voice

import android.content.Context
import android.util.Log
import com.cesia.input.engine.ai.SherpaOnnxEngine
import com.cesia.input.model.ModelManager
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 语音识别引擎
 * 支持：
 * - Sherpa-onnx Paraformer 流式识别（OnlineRecognizer，边说边出文字）
 * - Sherpa-onnx 离线识别（OfflineRecognizer，整段识别）
 * - Groq 云端识别（whisper-large-v3）
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }

    enum class Backend {
        LOCAL_SHERPA,
        CLOUD_GROQ
    }

    enum class ModelType(val displayName: String) {
        SENSE_VOICE("SenseVoice"),
        PARAFORMER("Paraformer"),
        ZIPFORMER("Zipformer")
    }

    data class RecognitionResult(
        val text: String,
        val isFinal: Boolean,
        val modelType: String = "",
        val backend: String = ""
    )

    private val modelManager = ModelManager(context)
    private val sherpaEngine = SherpaOnnxEngine()
    private val recorder = VoiceRecorder()

    private var currentBackend = Backend.LOCAL_SHERPA
    private var currentModelType = ModelType.PARAFORMER
    private var recognizer: Any? = null
    private var recognizerLoaded = false
    private var lastErrorMessage: String? = null

    private val groqClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ==================== 后端配置 ====================

    fun setBackend(backend: Backend) {
        currentBackend = backend
        Log.i(TAG, "Voice backend set to: $backend")
    }

    fun getBackend(): Backend = currentBackend

    fun setModelType(type: ModelType) {
        currentModelType = type
        Log.i(TAG, "Model type set to: $type")
    }

    fun getModelType(): ModelType = currentModelType

    fun getLastErrorMessage(): String? = lastErrorMessage

    // ==================== 模型目录查找 ====================

    /**
     * 查找模型目录
     * 优先查找 Paraformer 多文件目录 local_models/paraformer/
     * 兼容旧路径 local_models/ 下的 .onnx 单文件
     */
    private fun findModelDir(): File? {
        // 1. Paraformer 多文件目录
        val paraformerDir = File(context.filesDir, "local_models/paraformer")
        if (paraformerDir.exists() && paraformerDir.isDirectory) {
            val encoder = File(paraformerDir, "encoder.onnx")
            val decoder = File(paraformerDir, "decoder.onnx")
            val tokens = File(paraformerDir, "tokens.txt")
            if (encoder.exists() && decoder.exists() && tokens.exists()) {
                Log.i(TAG, "findModelDir: Paraformer 目录完整 ${paraformerDir.absolutePath}")
                return paraformerDir
            }
        }

        // 2. 通过 ModelManager 查找单文件模型
        val installedFile = modelManager.getInstalledVoiceModelFile()
        if (installedFile != null) {
            Log.i(TAG, "findModelDir: ModelManager 找到 ${installedFile.absolutePath}")
            return installedFile.parentFile
        }

        // 3. 兼容旧路径
        val sherpaDir = File(context.filesDir, "models/sherpa")
        val legacyFile = File(sherpaDir, "model.onnx")
        if (legacyFile.exists()) {
            Log.i(TAG, "findModelDir: 旧路径 ${sherpaDir.absolutePath}")
            return sherpaDir
        }

        return null
    }

    /**
     * 判断是否为 Paraformer 多文件模型
     */
    private fun isParaformerModel(modelDir: File): Boolean {
        return File(modelDir, "encoder.onnx").exists() &&
               File(modelDir, "decoder.onnx").exists() &&
               File(modelDir, "tokens.txt").exists()
    }

    // ==================== 本地模型加载 ====================

    /**
     * 检查本地语音模型是否可用
     * 支持 Paraformer 多文件模型（local_models/paraformer/）
     */
    suspend fun loadLocalModel(modelType: ModelType? = null): Boolean = withContext(Dispatchers.IO) {
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            val err = SherpaOnnxEngine.getLibraryLoadError() ?: "未知错误"
            lastErrorMessage = "Sherpa-onnx 库未加载: $err"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        val modelDir = findModelDir()
        if (modelDir == null) {
            lastErrorMessage = "未找到模型目录（local_models/paraformer/）"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        recognizerLoaded = true
        lastErrorMessage = null
        Log.i(TAG, "本地模型可用: ${modelDir.absolutePath}")
        true
    }

    fun isLocalModelLoaded(): Boolean = recognizerLoaded

    /**
     * 检查是否有可用的本地模型
     */
    fun hasSherpaModel(): Boolean = findModelDir() != null

    /**
     * 获取模型名称和路径信息
     */
    fun getSherpaModelName(): String {
        val modelDir = findModelDir() ?: return "无模型"
        val modelId = modelManager.installedVoiceModelId ?: "未知"
        return if (isParaformerModel(modelDir)) {
            val encSize = File(modelDir, "encoder.onnx").length() / 1024 / 1024
            "Paraformer 流式 (${encSize}MB)"
        } else {
            val onnxFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
            val sizeMB = (onnxFile?.length() ?: 0) / 1024 / 1024
            "$modelId (${sizeMB}MB)"
        }
    }

    /**
     * 获取模型详细信息（用于诊断显示）
     */
    fun getModelDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 语音识别诊断 ===")
        sb.appendLine("框架: Sherpa-onnx")
        sb.appendLine("库加载: ${if (SherpaOnnxEngine.isLibraryLoaded()) "✅ 已加载" else "❌ 未加载"}")
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            sb.appendLine("  错误: ${SherpaOnnxEngine.getLibraryLoadError() ?: "未知"}")
        }
        sb.appendLine("已注册模型ID: ${modelManager.installedVoiceModelId ?: "无"}")

        val installedFile = modelManager.getInstalledVoiceModelFile()
        sb.appendLine("ModelManager模型: ${installedFile?.let { "✅ ${it.name} (${it.length()/1024/1024}MB)" } ?: "❌ 无"}")

        val foundDir = findModelDir()
        sb.appendLine("VoiceEngine查找: ${foundDir?.let { "✅ ${it.absolutePath}" } ?: "❌ 未找到"}")

        // 列出 local_models/ 目录内容
        val modelsDir = File(context.filesDir, "local_models")
        if (modelsDir.exists() && modelsDir.isDirectory) {
            val files = modelsDir.listFiles()?.filter { it.isFile } ?: emptyList()
            sb.appendLine("local_models/ 文件: ${files.size} 个")
            files.forEach { sb.appendLine("  - ${it.name} (${it.length()/1024/1024}MB)") }
            // 列出子目录
            val dirs = modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            dirs.forEach { dir ->
                val dirFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
                sb.appendLine("  📁 ${dir.name}/ (${dirFiles.size} 个文件)")
                dirFiles.forEach { sb.appendLine("    - ${it.name} (${it.length()/1024/1024}MB)") }
            }
        } else {
            sb.appendLine("local_models/ 目录: 不存在")
        }

        return sb.toString()
    }

    // ==================== 流式/分段识别 ====================

    /**
     * 录音识别（边说边出文字）
     * - Paraformer 模型：使用 OnlineRecognizer 真正流式识别
     * - 其他模型：使用 OfflineRecognizer 分段识别
     */
    suspend fun recordInSegments(
        maxDurationMs: Int = 30000,
        segmentDurationMs: Int = 3000,
        onSegmentResult: suspend (text: String, isFinal: Boolean) -> Unit
    ) {
        Log.i(TAG, "recordInSegments: max=${maxDurationMs}ms, segment=${segmentDurationMs}ms")

        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            Log.e(TAG, "recordInSegments: library not loaded")
            return
        }

        val modelDir = findModelDir()
        if (modelDir == null) {
            Log.e(TAG, "recordInSegments: model dir not found")
            return
        }

        if (isParaformerModel(modelDir)) {
            recordStreaming(modelDir, maxDurationMs, onSegmentResult)
        } else {
            recordInSegmentsOffline(modelDir, maxDurationMs, segmentDurationMs, onSegmentResult)
        }
    }

    /**
     * Paraformer 流式识别（OnlineRecognizer）
     * 真正边说边识别，不需要分段
     */
    private suspend fun recordStreaming(
        modelDir: File,
        maxDurationMs: Int,
        onSegmentResult: suspend (text: String, isFinal: Boolean) -> Unit
    ) {
        Log.i(TAG, "recordStreaming: 使用 OnlineRecognizer 流式识别")

        val onlineRec = sherpaEngine.createStreamingRecognizer(
            assetManager = null,
            modelDir = modelDir.absolutePath,
            modelType = SherpaOnnxEngine.ModelType.PARAFORMER,
            numThreads = 2
        )
        if (onlineRec == null) {
            Log.e(TAG, "recordStreaming: 创建 OnlineRecognizer 失败")
            return
        }

        val stream = onlineRec.createStream()
        if (stream == null) {
            Log.e(TAG, "recordStreaming: 创建 OnlineStream 失败")
            return
        }

        if (!recorder.init()) {
            Log.e(TAG, "recordStreaming: recorder init failed")
            return
        }
        recorder.start()

        val readBuffer = FloatArray(1024)
        val startTime = System.currentTimeMillis()
        var lastResult = ""

        try {
            while (System.currentTimeMillis() - startTime < maxDurationMs) {
                val read = recorder.readChunk(readBuffer.size) ?: break
                if (read.isEmpty()) {
                    Thread.sleep(50)
                    continue
                }

                // 喂入音频
                sherpaEngine.acceptWaveform(onlineRec, stream, read)

                // 获取当前结果
                val currentResult = sherpaEngine.getStreamingResult(onlineRec, stream)
                if (currentResult.isNotEmpty() && currentResult != lastResult) {
                    Log.i(TAG, "recordStreaming: 中间结果 '$currentResult'")
                    onSegmentResult(currentResult, false)
                    lastResult = currentResult
                }

                // 检查端点（说话结束）
                if (sherpaEngine.isEndpoint(onlineRec, stream)) {
                    val finalText = sherpaEngine.getStreamingResult(onlineRec, stream)
                    if (finalText.isNotEmpty()) {
                        onSegmentResult(finalText, true)
                    }
                    // 重置继续识别
                    sherpaEngine.resetStream(onlineRec, stream)
                    lastResult = ""
                }
            }

            // 最终结果
            val finalText = sherpaEngine.getStreamingResult(onlineRec, stream)
            if (finalText.isNotEmpty() && finalText != lastResult) {
                onSegmentResult(finalText, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordStreaming error: ${e.message}", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            // OnlineStream.delete() and OnlineRecognizer.release() are private;
            // rely on GC/finalize for native resource cleanup
        }
    }

    /**
     * 分段离线识别（OfflineRecognizer）
     * 用于非 Paraformer 模型
     */
    private suspend fun recordInSegmentsOffline(
        modelDir: File,
        maxDurationMs: Int,
        segmentDurationMs: Int,
        onSegmentResult: suspend (text: String, isFinal: Boolean) -> Unit
    ) {
        Log.i(TAG, "recordInSegmentsOffline: 使用 OfflineRecognizer 分段识别")

        val sampleRate = 16000
        val segmentSamples = sampleRate * segmentDurationMs / 1000
        val maxSamples = sampleRate * maxDurationMs / 1000

        if (!recorder.init()) {
            Log.e(TAG, "recordInSegmentsOffline: recorder init failed")
            return
        }
        recorder.start()

        val allAudio = mutableListOf<Float>()
        val readBuffer = FloatArray(1024)
        var totalSamples = 0
        val startTime = System.currentTimeMillis()

        try {
            while (totalSamples < maxSamples && (System.currentTimeMillis() - startTime) < maxDurationMs + 1000) {
                val read = recorder.readChunk(readBuffer.size) ?: break
                if (read.isEmpty()) {
                    Thread.sleep(50)
                    continue
                }
                for (sample in read) {
                    allAudio.add(sample)
                }
                totalSamples += read.size

                while (allAudio.size >= segmentSamples) {
                    val segment = allAudio.subList(0, segmentSamples).toFloatArray()
                    allAudio.subList(0, segmentSamples).clear()
                    val text = transcribeLocal(segment, modelDir)
                    Log.i(TAG, "recordInSegmentsOffline: segment result '$text'")
                    onSegmentResult(text, false)
                }
            }

            if (allAudio.isNotEmpty()) {
                val remaining = allAudio.toFloatArray()
                val text = transcribeLocal(remaining, modelDir)
                Log.i(TAG, "recordInSegmentsOffline: final result '$text'")
                onSegmentResult(text, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordInSegmentsOffline error: ${e.message}", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
        }
    }

    private fun MutableList<Float>.toFloatArray(): FloatArray = FloatArray(size) { this[it] }

    // ==================== 音频识别 ====================
    suspend fun transcribe(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (audioData.isEmpty()) return@withContext ""

        when (currentBackend) {
            Backend.LOCAL_SHERPA -> {
                val modelDir = findModelDir()
                if (modelDir == null) {
                    Log.e(TAG, "transcribe: model dir not found")
                    return@withContext ""
                }
                transcribeLocal(audioData, modelDir)
            }
            Backend.CLOUD_GROQ -> {
                val tempFile = saveTempWav(audioData)
                try {
                    transcribeGroq(tempFile)
                } finally {
                    tempFile.delete()
                }
            }
        }
    }

    private suspend fun transcribeLocal(
        audioData: FloatArray,
        modelDir: File
    ): String = withContext(Dispatchers.IO) {
        try {
            val modelType = if (isParaformerModel(modelDir)) {
                SherpaOnnxEngine.ModelType.PARAFORMER
            } else {
                SherpaOnnxEngine.ModelType.SENSE_VOICE
            }
            Log.i(TAG, "transcribeLocal: modelDir=${modelDir.absolutePath}, type=$modelType")

            val offlineRec = sherpaEngine.createOfflineRecognizer(
                assetManager = null,
                modelDir = modelDir.absolutePath,
                modelType = modelType,
                numThreads = 2
            )

            if (offlineRec == null) {
                Log.e(TAG, "transcribeLocal: failed to create offline recognizer")
                return@withContext ""
            }

            val result = sherpaEngine.transcribeOffline(offlineRec, audioData)
            try { offlineRec.release() } catch (_: Exception) {}
            Log.i(TAG, "transcribeLocal result: \"$result\"")
            result
        } catch (e: Exception) {
            Log.e(TAG, "transcribeLocal error: ${e.message}", e)
            ""
        }
    }

    // ==================== 云端识别 ====================

    private suspend fun transcribeGroq(audioFile: File): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = modelManager.getGroqApiKey()
            if (apiKey.isNullOrBlank()) {
                Log.e(TAG, "Groq API key not configured")
                return@withContext ""
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("language", "zh")
                .build()

            val request = Request.Builder()
                .url(GROQ_API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = groqClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                json.optString("text", "").trim()
            } else {
                Log.e(TAG, "Groq API error: ${response.code} ${response.message}")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq transcribe error: ${e.message}", e)
            ""
        }
    }

    // ==================== WAV 写入工具 ====================

    private fun saveTempWav(audioData: FloatArray): File {
        val tempFile = File(context.cacheDir, "voice_temp.wav")
        val byteBuffer = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(byteBuffer)
        val dataSize = audioData.size * 2
        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(36 + dataSize))
        dos.writeBytes("WAVE")
        // fmt chunk
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16))
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt())
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt())
        dos.writeInt(Integer.reverseBytes(16000))
        dos.writeInt(Integer.reverseBytes(32000))
        dos.writeShort(java.lang.Short.reverseBytes(2).toInt())
        dos.writeShort(java.lang.Short.reverseBytes(16).toInt())
        // data chunk
        dos.writeBytes("data")
        dos.writeInt(Integer.reverseBytes(dataSize))
        for (sample in audioData) {
            val clamped = (sample.coerceIn(-1f, 1f) * 32767).toInt().toShort()
            dos.writeShort(clamped.toInt().ushr(8) or (clamped.toInt() and 0xFF).shl(8))
        }
        dos.flush()
        tempFile.writeBytes(byteBuffer.toByteArray())
        return tempFile
    }

    fun getRecorder(): VoiceRecorder = recorder

    /**
     * 释放资源
     */
    fun release() {
        try {
            (recognizer as? com.k2fsa.sherpa.onnx.OfflineRecognizer)?.release()
            recognizer = null
            recognizerLoaded = false
            Log.i(TAG, "VoiceEngine released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
    }
}
