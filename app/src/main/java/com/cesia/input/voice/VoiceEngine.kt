package com.cesia.input.voice

import android.content.Context
import android.util.Log
import com.cesia.input.engine.ai.WhisperEngine
import com.cesia.input.model.ModelManager
import kotlinx.coroutines.Dispatchers
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
 * 语音识别引擎 — 统一本地 Whisper / 云端 Groq API 两种后端
 *
 * 使用方式:
 * 1. 创建实例: VoiceEngine(context)
 * 2. 设置后端: setBackend(Backend.LOCAL) 或 setBackend(Backend.CLOUD)
 * 3. 录音: recorder.record(durationMs) → FloatArray
 * 4. 识别: transcribe(floatArray) → String
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }

    enum class Backend {
        LOCAL_WHISPER,
        CLOUD_GROQ
    }

    private val modelManager = ModelManager(context)
    private val whisperEngine = WhisperEngine()
    private val recorder = VoiceRecorder()

    private var currentBackend = Backend.LOCAL_WHISPER
    private var whisperLoaded = false

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

    // ==================== 本地模型 ====================

    /** 加载本地 whisper 模型 */
    suspend fun loadLocalModel(): Boolean = withContext(Dispatchers.IO) {
        val modelFile = modelManager.getInstalledVoiceModelFile()
        if (modelFile == null) {
            Log.w(TAG, "No local voice model installed")
            return@withContext false
        }

        try {
            whisperLoaded = whisperEngine.nativeInit(modelFile.absolutePath, modelManager.useGpu)
            if (whisperLoaded) {
                Log.i(TAG, "Whisper model loaded: ${modelFile.name}")
            }
            whisperLoaded
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load whisper model", e)
            false
        }
    }

    // ==================== 核心 API ====================

    /**
     * 识别音频数据
     * @param audioData 16kHz 单声道 PCM float[-1.0, 1.0]
     * @return 识别文本（失败返回空字符串）
     */
    suspend fun transcribe(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (audioData.isEmpty()) return@withContext ""

        when (currentBackend) {
            Backend.LOCAL_WHISPER -> transcribeLocal(audioData)
            Backend.CLOUD_GROQ -> {
                // Groq 需要文件，先保存临时 WAV
                val tempFile = saveTempWav(audioData)
                try {
                    transcribeGroq(tempFile)
                } finally {
                    tempFile.delete()
                }
            }
        }
    }

    /**
     * 录音并识别（一站式）
     * @param durationMs 录音时长
     * @return 识别文本
     */
    suspend fun recordAndTranscribe(durationMs: Int): String {
        val audioData = withContext(Dispatchers.IO) {
            recorder.record(durationMs)
        }
        return if (audioData != null) {
            transcribe(audioData)
        } else ""
    }

    // ==================== 本地 Whisper ====================

    private suspend fun transcribeLocal(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (!whisperLoaded) {
            loadLocalModel()
        }
        if (!whisperLoaded) {
            Log.e(TAG, "Whisper model not loaded")
            return@withContext ""
        }

        try {
            whisperEngine.nativeTranscribe(audioData)
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcribe error", e)
            ""
        }
    }

    // ==================== 云端 Groq ====================

    private fun transcribeGroq(audioFile: File): String {
        val apiKey = getGroqApiKey()
        if (apiKey.isEmpty()) {
            Log.w(TAG, "Groq API key not set")
            return ""
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "zh")
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(GROQ_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = groqClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Groq API error: ${response.code}")
                return ""
            }
            val body = response.body?.string() ?: ""
            JSONObject(body).optString("text", "")
        } catch (e: Exception) {
            Log.e(TAG, "Groq request failed", e)
            ""
        }
    }

    // ==================== 辅助方法 ====================

    /** 临时保存 WAV 文件（用于上传到 Groq） */
    private fun saveTempWav(audioData: FloatArray): File {
        val tempFile = File(context.cacheDir, "voice_temp.wav")

        // 简化 WAV 头 + PCM16 数据
        val pcmData = ByteArray(audioData.size * 2)
        for (i in audioData.indices) {
            val sample = (audioData[i] * 32767).toInt().coerceIn(-32768, 32767)
            pcmData[i * 2] = (sample and 0xFF).toByte()
            pcmData[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        // WAV header
        val header = ByteArray(44)
        val byteRate = 16000 * 2  // 16kHz * 16bit mono
        val dataSize = pcmData.size

        // "RIFF"
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // file size - 8
        val totalSize = dataSize + 36
        writeInt(header, 4, totalSize)
        // "WAVE"
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // "fmt "
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)       // fmt chunk size
        writeShort(header, 20, 1)       // PCM format
        writeShort(header, 22, 1)       // mono
        writeInt(header, 24, 16000)     // sample rate
        writeInt(header, 28, byteRate)   // byte rate
        writeShort(header, 32, 2)       // block align
        writeShort(header, 34, 16)      // bits per sample
        // "data"
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, dataSize)

        tempFile.writeBytes(header + pcmData)
        return tempFile
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    /** 读取 Groq API Key */
    private fun getGroqApiKey(): String {
        return context.getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
            .getString("groq_api_key", "") ?: ""
    }

    // ==================== 状态查询 ====================

    fun hasLocalModel(): Boolean = modelManager.hasVoiceModel()
    fun hasCloudApiKey(): Boolean = getGroqApiKey().isNotEmpty()

    /**
     * 当前设置是否可用
     * 本地 → 需要模型已安装且已加载
     * 云端 → 需要 API Key 已设置
     */
    fun isAvailable(): Boolean = when (currentBackend) {
        Backend.LOCAL_WHISPER -> whisperLoaded
        Backend.CLOUD_GROQ -> hasCloudApiKey()
    }

    /** 检查 Whisper 模型是否已加载到内存 */
    fun isModelLoaded(): Boolean = whisperLoaded

    fun release() {
        whisperEngine.nativeFree()
        whisperLoaded = false
        recorder.release()
    }
}
