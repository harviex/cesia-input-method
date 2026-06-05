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
     * 检查本地 Paraformer 模型是否可用
     * 模型文件路径：models/sherpa/model.onnx
     */
    suspend fun loadLocalModel(modelType: ModelType? = null): Boolean = withContext(Dispatchers.IO) {
        // 检查库是否可用
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            val err = SherpaOnnxEngine.getLibraryLoadError() ?: "未知错误"
            lastErrorMessage = "Sherpa-onnx 库未加载: $err"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        // 检查模型文件
        val modelFile = findParaformerModel()
        if (modelFile == null) {
            lastErrorMessage = "未找到模型文件 models/sherpa/model.onnx"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        recognizerLoaded = true
        lastErrorMessage = null
        Log.i(TAG, "Paraformer model available: ${modelFile.absolutePath}")
        true
    }

    /**
     * 查找 Paraformer 模型文件
     * 支持路径：models/sherpa/model.onnx
     */
    private fun findParaformerModel(): File? {
        val sherpaDir = File(context.filesDir, "models/sherpa")
        val modelFile = File(sherpaDir, "model.onnx")
        return if (modelFile.exists() && modelFile.isFile) modelFile else null
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
     * 检查是否有可用的 Paraformer 模型
     */
    fun hasSherpaModel(): Boolean = findParaformerModel() != null

    /**
     * 获取模型名称
     */
    fun getSherpaModelName(): String {
        val modelFile = findParaformerModel() ?: return "无"
        return "Paraformer (${modelFile.length() / 1024 / 1024}MB)"
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
        val modelFile = findParaformerModel()
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

            val modelFile = findParaformerModel()
            if (modelFile == null) {
                Log.e(TAG, "transcribeLocal: model file not found")
                return@withContext ""
            }

            // 每次识别创建新的离线识别器（因为单文件模型）
            val offlineRec = sherpaEngine.createOfflineRecognizer(
                assetManager = null,
                modelDir = modelFile.parentFile.absolutePath,
                modelType = SherpaOnnxEngine.ModelType.PARAFORMER,
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
