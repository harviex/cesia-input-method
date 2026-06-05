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
    private var recognizer: com.k2fsa.sherpa.onnx.OnlineRecognizer? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
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
     * 加载本地 Sherpa-onnx 模型
     * 自动扫描 models/sherpa/ 目录，根据文件存在情况判断模型类型
     *
     * 支持的目录结构：
     *   models/sherpa/sensevoice/  → model.onnx + tokens.txt (离线识别)
     *   models/sherpa/paraformer/  → encoder.onnx + decoder.onnx + tokens.txt (流式)
     *   models/sherpa/zipformer/   → encoder.onnx + decoder.onnx + joiner.onnx + tokens.txt (流式)
     *
     * 也支持在 models/sherpa/ 根目录放置文件（自动识别类型）
     */
    suspend fun loadLocalModel(modelType: ModelType? = null): Boolean = withContext(Dispatchers.IO) {
        // 检查库是否可用
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            val err = SherpaOnnxEngine.getLibraryLoadError() ?: "未知错误"
            lastErrorMessage = "Sherpa-onnx 库未加载: $err"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        // 确定模型类型和目录
        val (type, modelDir) = if (modelType != null) {
            val dir = findModelDir(modelType)
            if (dir == null) {
                lastErrorMessage = "模型目录不存在: models/sherpa/${modelType.name.lowercase()}/"
                Log.e(TAG, lastErrorMessage!!)
                return@withContext false
            }
            modelType to dir
        } else {
            // 自动扫描所有支持的模型目录
            val found = findAnyModelDir()
            if (found == null) {
                lastErrorMessage = "未找到任何语音模型。请下载模型并放入 models/sherpa/{sensevoice|paraformer|zipformer}/ 目录"
                Log.e(TAG, lastErrorMessage!!)
                return@withContext false
            }
            found
        }

        currentModelType = type
        Log.i(TAG, "Loading Sherpa-onnx model: type=$type, dir=${modelDir.absolutePath}")

        try {
            val sherpaType = when (type) {
                ModelType.SENSE_VOICE -> SherpaOnnxEngine.ModelType.SENSE_VOICE
                ModelType.PARAFORMER -> SherpaOnnxEngine.ModelType.PARAFORMER
                ModelType.ZIPFORMER -> SherpaOnnxEngine.ModelType.ZIPFORMER
            }

            // 释放旧的识别器
            stream?.let { try { it.release() } catch (_: Exception) {} }
            recognizer?.let { try { it.release() } catch (_: Exception) {} }

            // 创建新的流式识别器
            val newRecognizer = sherpaEngine.createStreamingRecognizer(
                assetManager = null,
                modelDir = modelDir.absolutePath,
                modelType = sherpaType,
                numThreads = 2,
                provider = "cpu"
            )

            if (newRecognizer != null) {
                recognizer = newRecognizer
                recognizerLoaded = true
                lastErrorMessage = null
                Log.i(TAG, "Sherpa-onnx model loaded: $type")
                true
            } else {
                lastErrorMessage = "创建识别器失败: $type（模型文件可能损坏或不完整）"
                Log.e(TAG, lastErrorMessage!!)
                false
            }
        } catch (e: Throwable) {
            lastErrorMessage = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Failed to load Sherpa-onnx model", e)
            false
        }
    }

    fun isLocalModelLoaded(): Boolean = recognizerLoaded

    /**
     * 扫描 models/sherpa/ 目录，找到第一个可用的模型
     * @return (模型类型, 模型目录) 或 null
     */
    private fun findAnyModelDir(): Pair<ModelType, File>? {
        val sherpaDir = File(context.filesDir, "models/sherpa")
        if (!sherpaDir.exists() || !sherpaDir.isDirectory) return null

        // 检查各子目录
        val subdirs = mapOf(
            ModelType.SENSE_VOICE to "sensevoice",
            ModelType.PARAFORMER to "paraformer",
            ModelType.ZIPFORMER to "zipformer"
        )

        for ((type, dirname) in subdirs) {
            val dir = File(sherpaDir, dirname)
            if (dir.exists() && dir.isDirectory && hasRequiredFiles(dir, type)) {
                Log.i(TAG, "Found model: type=$type, dir=${dir.absolutePath}")
                return type to dir
            }
        }

        // 也检查根目录
        for (type in ModelType.entries) {
            if (hasRequiredFiles(sherpaDir, type)) {
                Log.i(TAG, "Found model in root: type=$type, dir=${sherpaDir.absolutePath}")
                return type to sherpaDir
            }
        }

        return null
    }

    /**
     * 查找指定类型的模型目录
     */
    private fun findModelDir(type: ModelType): File? {
        val dir = File(context.filesDir, "models/sherpa/${type.name.lowercase()}")
        return if (dir.exists() && dir.isDirectory && hasRequiredFiles(dir, type)) dir else null
    }

    /**
     * 检查目录是否包含指定类型模型的必需文件
     */
    private fun hasRequiredFiles(dir: File, type: ModelType): Boolean {
        val tokensFile = File(dir, "tokens.txt")
        return when (type) {
            ModelType.SENSE_VOICE -> {
                // SenseVoice: model.onnx + tokens.txt
                val modelFile = File(dir, "model.onnx")
                modelFile.exists() && tokensFile.exists()
            }
            ModelType.PARAFORMER -> {
                // Paraformer: encoder.onnx + decoder.onnx + tokens.txt
                val encoder = File(dir, "encoder.onnx")
                val decoder = File(dir, "decoder.onnx")
                encoder.exists() && decoder.exists() && tokensFile.exists()
            }
            ModelType.ZIPFORMER -> {
                // Zipformer: encoder.onnx + decoder.onnx + joiner.onnx + tokens.txt
                val encoder = File(dir, "encoder.onnx")
                val decoder = File(dir, "decoder.onnx")
                val joiner = File(dir, "joiner.onnx")
                encoder.exists() && decoder.exists() && joiner.exists() && tokensFile.exists()
            }
        }
    }

    /**
     * 获取模型目录（兼容旧接口）
     */
    private fun getSherpaModelDir(type: ModelType): File? = findModelDir(type)

    /**
     * 检查是否有可用的 Sherpa 模型（供外部调用）
     */
    fun hasSherpaModel(): Boolean = findAnyModelDir() != null

    /**
     * 获取当前检测到的模型名称（供外部显示）
     */
    fun getSherpaModelName(): String {
        val found = findAnyModelDir() ?: return "无"
        return found.first.displayName
    }

    // ==================== 流式识别 ====================

    /**
     * 开始流式识别
     * @return 是否成功启动
     */
    fun startStreamingRecognition(): Boolean {
        val rec = recognizer
        if (rec == null) {
            Log.e(TAG, "startStreamingRecognition: recognizer is null")
            return false
        }
        return try {
            stream?.let {
                try { it.release() } catch (_: Exception) {}
            }
            val newStream = rec.createStream()
            stream = newStream
            Log.i(TAG, "Streaming recognition started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming: ${e.message}")
            false
        }
    }

    /**
     * 喂入音频数据（流式识别）
     * @param audioData 16kHz 单声道 PCM float[-1.0, 1.0]
     * @return 当前识别结果
     */
    fun feedAudio(audioData: FloatArray): RecognitionResult {
        val rec = recognizer ?: return RecognitionResult("", false, backend = "none")
        val str = stream ?: return RecognitionResult("", false, backend = "none")

        return try {
            sherpaEngine.acceptWaveform(rec, str, audioData)
            val text = sherpaEngine.getStreamingResult(rec, str)
            val isEndpoint = sherpaEngine.isEndpoint(rec, str)
            RecognitionResult(
                text = text,
                isFinal = isEndpoint,
                modelType = currentModelType.displayName,
                backend = "local"
            )
        } catch (e: Exception) {
            Log.e(TAG, "feedAudio error: ${e.message}")
            RecognitionResult("", false, backend = "local")
        }
    }

    /**
     * 停止流式识别，返回最终结果
     */
    fun stopStreamingRecognition(): RecognitionResult {
        val rec = recognizer ?: return RecognitionResult("", true, backend = "none")
        val str = stream ?: return RecognitionResult("", true, backend = "none")

        return try {
            // 标记输入结束
            str.inputFinished()
            // 解码剩余数据
            while (rec.isReady(str)) {
                rec.decode(str)
            }
            val text = rec.getResult(str).text.trim()
            Log.i(TAG, "Streaming final result: \"$text\"")
            RecognitionResult(
                text = text,
                isFinal = true,
                modelType = currentModelType.displayName,
                backend = "local"
            )
        } catch (e: Exception) {
            Log.e(TAG, "stopStreaming error: ${e.message}")
            RecognitionResult("", true, backend = "local")
        } finally {
            try { str.release() } catch (_: Exception) {}
            stream = null
        }
    }

    // ==================== 离线识别（录音后识别） ====================

    /**
     * 录音并识别（一站式，非流式）
     * 适用于短语音输入
     */
    suspend fun recordAndTranscribe(durationMs: Int): String {
        Log.i(TAG, "recordAndTranscribe: starting record, duration=${durationMs}ms")
        val audioData = withContext(Dispatchers.IO) {
            recorder.record(durationMs)
        }
        Log.i(TAG, "recordAndTranscribe: record done, audioData=${if (audioData != null) "size=${audioData.size}" else "null"}")
        return if (audioData != null) {
            transcribe(audioData)
        } else {
            Log.e(TAG, "recordAndTranscribe: audioData is null")
            ""
        }
    }

    /**
     * 识别音频数据
     */
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
            val type = when (currentModelType) {
                ModelType.SENSE_VOICE -> SherpaOnnxEngine.ModelType.SENSE_VOICE
                ModelType.PARAFORMER -> SherpaOnnxEngine.ModelType.PARAFORMER
                ModelType.ZIPFORMER -> SherpaOnnxEngine.ModelType.ZIPFORMER
            }

            val modelDir = getSherpaModelDir(currentModelType)
            if (modelDir == null || !modelDir.exists()) {
                Log.e(TAG, "Model dir not found for $currentModelType")
                return@withContext ""
            }

            val offlineRec = sherpaEngine.createOfflineRecognizer(
                assetManager = null,
                modelDir = modelDir.absolutePath,
                modelType = type,
                numThreads = 2
            )

            if (offlineRec == null) {
                Log.e(TAG, "Failed to create offline recognizer")
                return@withContext ""
            }

            val result = sherpaEngine.transcribeOffline(offlineRec, audioData)
            try { offlineRec.release() } catch (_: Exception) {}
            result
        } catch (e: Exception) {
            Log.e(TAG, "Local transcribe error: ${e.message}", e)
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
            stream?.let { try { it.release() } catch (_: Exception) {} }
            recognizer?.let { try { it.release() } catch (_: Exception) {} }
            stream = null
            recognizer = null
            recognizerLoaded = false
            Log.i(TAG, "VoiceEngine released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
    }
}
