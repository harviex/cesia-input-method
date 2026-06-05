package com.cesia.input.voice

import android.content.Context
import android.util.Log
import com.cesia.input.engine.ai.SherpaOnnxEngine
import com.cesia.input.model.ModelManager
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
 * 语音识别引擎 — 统一本地 Sherpa-onnx / 云端 Groq API 两种后端
 *
 * 本地模式使用流式识别：边录音边识别，实时返回中间结果
 * 云端模式使用 Groq API：录音完成后上传识别
 *
 * 使用方式:
 * 1. 创建实例: VoiceEngine(context)
 * 2. 设置后端: setBackend(Backend.LOCAL) 或 setBackend(Backend.CLOUD)
 * 3. 流式识别: startStreamingRecognition() → 循环 feedAudio() → stopStreamingRecognition()
 * 4. 离线识别: recordAndTranscribe(durationMs) → String
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val FEED_INTERVAL_MS = 100L  // 每100ms喂一次音频
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

    // ==================== 本地模型加载 ====================

    /**
     * 检查本地语音模型是否可用
     * 统一使用 ModelManager 的路径：local_models/
     */
    suspend fun loadLocalModel(modelType: ModelType? = null): Boolean = withContext(Dispatchers.IO) {
        // 检查库是否可用
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            val err = SherpaOnnxEngine.getLibraryLoadError() ?: "未知错误"
            lastErrorMessage = "Sherpa-onnx 库未加载: $err"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        // 检查模型文件（通过 ModelManager）
        val modelFile = findModelFile()
        if (modelFile == null) {
            lastErrorMessage = "未找到模型文件（local_models/ 目录下无 .onnx 模型）"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        recognizerLoaded = true
        lastErrorMessage = null
        Log.i(TAG, "本地模型可用: ${modelFile.absolutePath}")
        true
    }

    /**
     * 查找模型文件 — 统一路径
     * 优先通过 ModelManager 查找已安装的模型
     * 兼容旧路径 models/sherpa/model.onnx
     */
    private fun findModelFile(): File? {
        // 1. 通过 ModelManager 查找（local_models/ 目录）
        val installedFile = modelManager.getInstalledVoiceModelFile()
        if (installedFile != null) {
            Log.i(TAG, "findModelFile: 通过 ModelManager 找到 ${installedFile.absolutePath}")
            return installedFile
        }

        // 2. 兼容旧路径 models/sherpa/model.onnx
        val sherpaDir = File(context.filesDir, "models/sherpa")
        val legacyFile = File(sherpaDir, "model.onnx")
        if (legacyFile.exists() && legacyFile.isFile) {
            Log.i(TAG, "findModelFile: 通过旧路径找到 ${legacyFile.absolutePath}")
            return legacyFile
        }

        // 3. 扫描 local_models/ 目录下的任意 .onnx 文件
        val modelsDir = File(context.filesDir, "local_models")
        if (modelsDir.exists() && modelsDir.isDirectory) {
            val onnxFile = modelsDir.listFiles()?.firstOrNull {
                it.isFile && it.name.endsWith(".onnx") && !it.name.endsWith(".tmp")
            }
            if (onnxFile != null) {
                Log.i(TAG, "findModelFile: 扫描 local_models/ 找到 ${onnxFile.absolutePath}")
                return onnxFile
            }
        }

        return null
    }

    fun isLocalModelLoaded(): Boolean = recognizerLoaded

    /**
     * 检查是否有可用的本地模型
     */
    fun hasSherpaModel(): Boolean = findModelFile() != null

    /**
     * 获取模型名称和路径信息
     */
    fun getSherpaModelName(): String {
        val modelFile = findModelFile() ?: return "无模型"
        val sizeMB = modelFile.length() / 1024 / 1024
        val modelId = modelManager.installedVoiceModelId ?: "未知"
        return "$modelId (${sizeMB}MB)"
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

        val foundFile = findModelFile()
        sb.appendLine("VoiceEngine查找: ${foundFile?.let { "✅ ${it.absolutePath}" } ?: "❌ 未找到"}")

        // 列出 local_models/ 目录内容
        val modelsDir = File(context.filesDir, "local_models")
        if (modelsDir.exists() && modelsDir.isDirectory) {
            val files = modelsDir.listFiles()?.filter { it.isFile } ?: emptyList()
            sb.appendLine("local_models/ 文件: ${files.size} 个")
            files.forEach { sb.appendLine("  - ${it.name} (${it.length()/1024/1024}MB)") }
        } else {
            sb.appendLine("local_models/ 目录: 不存在")
        }

        // 列出 models/sherpa/ 目录内容
        val sherpaDir = File(context.filesDir, "models/sherpa")
        if (sherpaDir.exists() && sherpaDir.isDirectory) {
            val files = sherpaDir.listFiles()?.filter { it.isFile } ?: emptyList()
            sb.appendLine("models/sherpa/ 文件: ${files.size} 个")
            files.forEach { sb.appendLine("  - ${it.name} (${it.length()/1024/1024}MB)") }
        } else {
            sb.appendLine("models/sherpa/ 目录: 不存在")
        }

        return sb.toString()
    }

    // ==================== 离线识别（录音后识别） ====================

    /**
     * 分段录音识别（边说边出文字）
     * 每 segmentDurationMs 秒识别一次，通过 onSegmentResult 回调返回
     * @param maxDurationMs 最长录音时间
     * @param segmentDurationMs 每段识别的时长（秒）
     * @param onSegmentResult 分段结果回调 (text, isFinal)
     */
    suspend fun recordInSegments(
        maxDurationMs: Int = 30000,
        segmentDurationMs: Int = 3000,
        onSegmentResult: suspend (text: String, isFinal: Boolean) -> Unit
    ) {
        Log.i(TAG, "recordInSegments: max=${maxDurationMs}ms, segment=${segmentDurationMs}ms")

        // 预检查
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            Log.e(TAG, "recordInSegments: library not loaded")
            return
        }
        val modelFile = findModelFile()
        if (modelFile == null) {
            Log.e(TAG, "recordInSegments: model file not found")
            return
        }

        val sampleRate = 16000
        val segmentSamples = sampleRate * segmentDurationMs / 1000
        val maxSamples = sampleRate * maxDurationMs / 1000

        // 初始化录音
        if (!recorder.init()) {
            Log.e(TAG, "recordInSegments: recorder init failed")
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
                // 追加到缓冲区
                for (sample in read) {
                    allAudio.add(sample)
                }
                totalSamples += read.size

                // 检查是否够一段了
                while (allAudio.size >= segmentSamples) {
                    val segment = allAudio.subList(0, segmentSamples).toFloatArray()
                    allAudio.subList(0, segmentSamples).clear()

                    // 识别这一段
                    val text = transcribeLocal(segment)
                    Log.i(TAG, "recordInSegments: segment result '$text'")
                    onSegmentResult(text, false)
                }
            }

            // 处理剩余音频
            if (allAudio.isNotEmpty()) {
                val remaining = allAudio.toFloatArray()
                val text = transcribeLocal(remaining)
                Log.i(TAG, "recordInSegments: final result '$text'")
                onSegmentResult(text, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordInSegments error: ${e.message}", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
        }
    }

    private fun MutableList<Float>.toFloatArray(): FloatArray = FloatArray(size) { this[it] }

    // ==================== 音频识别 ====================
    suspend fun transcribe(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (audioData.isEmpty()) return@withContext ""

        when (currentBackend) {
            Backend.LOCAL_SHERPA -> transcribeLocal(audioData)
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

    private suspend fun transcribeLocal(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        try {
            val rec = recognizer
            if (rec == null) {
                Log.e(TAG, "transcribeLocal: recognizer is null, trying to load...")
                if (!loadLocalModel()) {
                    return@withContext ""
                }
            }

            val modelFile = findModelFile()
            if (modelFile == null) {
                Log.e(TAG, "transcribeLocal: model file not found")
                return@withContext ""
            }

            // 根据模型 ID 自动选择 ModelType
            val modelType = detectModelType(modelFile)
            Log.i(TAG, "transcribeLocal: modelFile=${modelFile.name}, detectedType=$modelType")

            // 每次识别创建新的离线识别器
            val offlineRec = sherpaEngine.createOfflineRecognizer(
                assetManager = null,
                modelDir = modelFile.parentFile.absolutePath,
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

    /**
     * 根据模型文件检测 ModelType
     */
    private fun detectModelType(modelFile: File): SherpaOnnxEngine.ModelType {
        val modelId = modelManager.installedVoiceModelId ?: ""
        return when {
            modelId.contains("sensevoice", ignoreCase = true) -> SherpaOnnxEngine.ModelType.SENSE_VOICE
            modelId.contains("paraformer", ignoreCase = true) -> SherpaOnnxEngine.ModelType.PARAFORMER
            modelId.contains("zipformer", ignoreCase = true) -> SherpaOnnxEngine.ModelType.ZIPFORMER
            // 根据文件名推断
            modelFile.name.contains("sensevoice", ignoreCase = true) -> SherpaOnnxEngine.ModelType.SENSE_VOICE
            modelFile.name.contains("paraformer", ignoreCase = true) -> SherpaOnnxEngine.ModelType.PARAFORMER
            modelFile.name.contains("zipformer", ignoreCase = true) -> SherpaOnnxEngine.ModelType.ZIPFORMER
            // 默认 SenseVoice（单文件 model.onnx）
            else -> SherpaOnnxEngine.ModelType.SENSE_VOICE
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
        // 简单 WAV 写入：16kHz 单声道 16-bit PCM
        val byteBuffer = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(byteBuffer)
        val dataSize = audioData.size * 2
        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(36 + dataSize))
        dos.writeBytes("WAVE")
        // fmt chunk
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16)) // chunk size
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt()) // PCM
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt()) // mono
        dos.writeInt(Integer.reverseBytes(16000)) // sample rate
        dos.writeInt(Integer.reverseBytes(32000)) // byte rate
        dos.writeShort(java.lang.Short.reverseBytes(2).toInt()) // block align
        dos.writeShort(java.lang.Short.reverseBytes(16).toInt()) // bits per sample
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
