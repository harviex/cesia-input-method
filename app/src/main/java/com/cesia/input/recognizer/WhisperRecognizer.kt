package com.cesia.input.recognizer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cesia.input.audio.AudioRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Whisper 语音识别器 — 支持本地模型和远程 API 两种模式
 *
 * 模式1：远程 API（默认）
 *   - 发送 WAV 音频到远程 Whisper API 服务器
 *   - 支持自建服务器（faster-whisper、whisper.cpp server 等）
 *   - 也支持 OpenRouter / OpenAI 官方 API
 *
 * 模式2：本地模型（需集成 whisper.cpp JNI）
 *   - 通过 JNI 调用 whisper.cpp 在设备上推理
 *   - 需要预编译 libwhisper.so 和模型文件
 *   - 当前版本：本地模式为预留接口，需额外集成
 */
class WhisperRecognizer(
    private val context: Context,
    private var language: String = "zh"
) {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val PREFS_NAME = "cesia_settings"

        // ── 远程 API 配置 Key ──
        const val PREF_WHISPER_MODE = "whisper_mode"           // "api" / "local"
        const val PREF_WHISPER_API_URL = "whisper_api_url"     // Whisper API 端点
        const val PREF_WHISPER_API_KEY = "whisper_api_key"     // API Key（可选）
        const val PREF_WHISPER_MODEL = "whisper_model"         // 模型名

        // 默认值 — Groq API（免费，whisper-large-v3-turbo）
        const val DEFAULT_WHISPER_MODE = "api"
        const val DEFAULT_WHISPER_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val DEFAULT_WHISPER_MODEL = "whisper-large-v3-turbo"

        // 本地模型路径（预留）
        const val LOCAL_MODEL_DIR = "whisper_models"
    }

    // ─── OkHttp 客户端 ──────────────────────────────────
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ─── 识别结果流 ─────────────────────────────────────
    private val _results = MutableSharedFlow<Result>(replay = 0, extraBufferCapacity = 1)
    val results = _results.asSharedFlow()

    sealed class Result {
        data class Success(val text: String, val confidence: Float = 1.0f) : Result()
        data class Partial(val text: String) : Result()
        data class Error(val message: String) : Result()
        data class Recognizing(val text: String) : Result()
        object NoMatch : Result()
    }

    // ─── 状态 ──────────────────────────────────────────
    private var _isListening = false
    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var restartJob: Job? = null
    var continuousMode: Boolean = false
    var suppressNoMatchError: Boolean = true

    private val accumulatedText = StringBuilder()
    private var lastPartialText: String = ""

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─── SharedPreferences ─────────────────────────────
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── 配置读取 ──────────────────────────────────────

    /** 当前模式：api / local */
    val mode: String
        get() = prefs.getString(PREF_WHISPER_MODE, DEFAULT_WHISPER_MODE) ?: DEFAULT_WHISPER_MODE

    /** Whisper API 端点 URL */
    val apiUrl: String
        get() = prefs.getString(PREF_WHISPER_API_URL, DEFAULT_WHISPER_API_URL) ?: DEFAULT_WHISPER_API_URL

    /** Whisper API Key（可选，自建服务器可能不需要） */
    val apiKey: String
        get() = prefs.getString(PREF_WHISPER_API_KEY, "") ?: ""

    /** Whisper 模型名 */
    val modelName: String
        get() = prefs.getString(PREF_WHISPER_MODEL, DEFAULT_WHISPER_MODEL) ?: DEFAULT_WHISPER_MODEL

    // ─── 初始化 ────────────────────────────────────────
    fun init(): Boolean {
        val mode = mode
        val url = apiUrl
        Log.d(TAG, "初始化 mode=$mode, url=$url, language=$language")
        return true
    }

    // ─── 开始监听 ──────────────────────────────────────
    fun startListening(): Boolean {
        if (mode == "api" && apiUrl.isEmpty()) {
            Log.w(TAG, "API URL 未配置")
            scope.launch { _results.emit(Result.Error("Whisper API 地址未配置，请在设置中填写")) }
            return false
        }
        if (mode == "local" && !isLocalModelAvailable()) {
            Log.w(TAG, "本地模型不可用")
            scope.launch { _results.emit(Result.Error("本地 Whisper 模型未找到，请下载模型或切换到 API 模式")) }
            return false
        }

        if (continuousMode) accumulatedText.clear()
        _isListening = true
        restartJob?.cancel()

        return try {
            startRecording()
            Log.d(TAG, "startListening OK, mode=$mode, continuous=$continuousMode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startListening 异常", e)
            scope.launch { _results.emit(Result.Error("录音启动失败: ${e.message}")) }
            false
        }
    }

    // ─── 停止监听 ──────────────────────────────────────
    fun stopListening() {
        restartJob?.cancel()
        restartJob = null
        _isListening = false
        stopRecording()

        if (continuousMode) {
            val text = if (accumulatedText.isNotEmpty()) accumulatedText.toString() else lastPartialText
            accumulatedText.clear()
            lastPartialText = ""
            scope.launch {
                if (text.isNotEmpty()) _results.emit(Result.Success(text))
                else _results.emit(Result.NoMatch)
            }
        }
    }

    // ─── 销毁 ──────────────────────────────────────────
    fun destroy() {
        restartJob?.cancel()
        _isListening = false
        stopRecording()
        audioRecorder = null
        accumulatedText.clear()
        continuousMode = false
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        Log.d(TAG, "WhisperRecognizer 已销毁")
    }

    // ─── 可用性检查 ────────────────────────────────────
    fun isAvailable(): Boolean {
        return when (mode) {
            "local" -> isLocalModelAvailable()
            else -> apiUrl.isNotEmpty()
        }
    }

    // ─── 语言设置 ──────────────────────────────────────
    fun setLanguage(lang: String) {
        language = lang
        Log.d(TAG, "语言切换为: $language")
    }

    // ═══════════════════════════════════════════════════
    //  录音逻辑
    // ═══════════════════════════════════════════════════

    private fun startRecording() {
        audioRecorder?.stop()
        audioRecorder = AudioRecorder(scope)

        recordingJob = scope.launch {
            try {
                val recorder = audioRecorder ?: return@launch
                val pcmChunks = mutableListOf<ByteArray>()

                recorder.startRecordingWithVad().collect { chunks ->
                    pcmChunks.addAll(chunks)
                }

                if (pcmChunks.isNotEmpty()) {
                    val wavData = recorder.pcmToWav(pcmChunks)
                    Log.d(TAG, "录音完成, WAV: ${wavData.size} bytes")

                    _results.emit(Result.Recognizing("正在识别..."))
                    val text = when (mode) {
                        "local" -> recognizeLocal(wavData)
                        else -> recognizeRemote(wavData)
                    }

                    if (text.isNotEmpty()) {
                        if (continuousMode) {
                            if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                            accumulatedText.append(text)
                            _results.emit(Result.Partial(accumulatedText.toString()))
                            if (_isListening) scheduleRestart()
                        } else {
                            _results.emit(Result.Success(text))
                        }
                    } else {
                        if (continuousMode && _isListening) scheduleRestart()
                        else if (!continuousMode) _results.emit(Result.NoMatch)
                    }
                } else {
                    if (!suppressNoMatchError) _results.emit(Result.Error("未检测到语音"))
                    else if (continuousMode && _isListening) scheduleRestart()
                    else _results.emit(Result.NoMatch)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "录音协程已取消")
            } catch (e: Exception) {
                Log.e(TAG, "录音异常", e)
                if (_isListening) _results.emit(Result.Error("录音失败: ${e.message}"))
            }
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecorder?.stop()
        audioRecorder = null
    }

    // ═══════════════════════════════════════════════════
    //  远程 API 识别
    // ═══════════════════════════════════════════════════

    private suspend fun recognizeRemote(wavData: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            // 构建 multipart 请求
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", modelName)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "json")
                .build()

            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .post(multipartBody)

            // 如果有 API Key 则添加
            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            Log.d(TAG, "Whisper API 请求: url=$apiUrl, model=$modelName, lang=$language, size=${wavData.size}")

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "未知错误"
                    Log.e(TAG, "Whisper API 错误: ${response.code} - $errorBody")
                    return@withContext ""
                }

                val respBody = response.body?.string() ?: return@withContext ""
                Log.d(TAG, "Whisper API 响应: ${respBody.take(200)}")

                val respJson = JSONObject(respBody)
                val text = respJson.optString("text", "").trim()
                if (text.isNotEmpty()) return@withContext text

                Log.w(TAG, "Whisper API 返回空文本")
                return@withContext ""
            }
        } catch (e: IOException) {
            Log.e(TAG, "Whisper API 网络异常", e)
            scope.launch { _results.emit(Result.Error("网络连接失败: ${e.message ?: "未知"}")) }
            return@withContext ""
        } catch (e: Exception) {
            Log.e(TAG, "Whisper API 异常", e)
            return@withContext ""
        }
    }

    // ═══════════════════════════════════════════════════
    //  本地模型识别（预留接口）
    // ═══════════════════════════════════════════════════

    /**
     * 本地 Whisper 识别 — 通过 JNI 调用 whisper.cpp
     *
     * 需要：
     * 1. 将 libwhisper.so 放入 app/src/main/jniLibs/arm64-v8a/
     * 2. 将模型文件（ggml-*.bin）放入 filesDir/whisper_models/
     * 3. 实现 native 方法
     */
    private suspend fun recognizeLocal(wavData: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            // TODO: 集成 whisper.cpp JNI 后取消注释
            // val modelPath = getLocalModelPath()
            // if (modelPath == null) {
            //     _results.emit(Result.Error("本地模型未找到"))
            //     return@withContext ""
            // }
            // return@withContext nativeRecognize(modelPath, wavData, language)

            Log.w(TAG, "本地 Whisper 模式尚未集成，请切换到 API 模式")
            scope.launch { _results.emit(Result.Error("本地 Whisper 模式尚未集成，请切换到 API 模式或使用远程 API")) }
            return@withContext ""
        } catch (e: Exception) {
            Log.e(TAG, "本地识别异常", e)
            return@withContext ""
        }
    }

    /** 检查本地模型是否可用 */
    private fun isLocalModelAvailable(): Boolean {
        val modelDir = java.io.File(context.filesDir, LOCAL_MODEL_DIR)
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        // 检查是否有 .bin 模型文件
        return modelDir.listFiles()?.any { it.name.endsWith(".bin") } == true
    }

    /** 获取本地模型路径 */
    private fun getLocalModelPath(): String? {
        val modelDir = java.io.File(context.filesDir, LOCAL_MODEL_DIR)
        val modelFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".bin") }
        return modelFile?.absolutePath
    }

    // ─── JNI 本地方法（预留） ──────────────────────────
    // private external fun nativeRecognize(modelPath: String, wavData: ByteArray, language: String): String
    //
    // companion object {
    //     init {
    //         System.loadLibrary("whisper")
    //     }
    // }

    // ═══════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════

    private fun scheduleRestart() {
        if (!_isListening) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(700)
            if (_isListening) {
                try { startRecording() } catch (e: Exception) { Log.e(TAG, "restart failed", e) }
            }
        }
    }
}
