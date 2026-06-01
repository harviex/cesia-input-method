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
 * 基于 Whisper API 的语音识别器
 *
 * 通过 AudioRecorder 录制音频，将 WAV 数据通过 HTTP POST 发送到 Whisper API，
 * 解析响应返回识别文本。
 *
 * 支持两种 API 端点：
 * 1. OpenAI 兼容端点（如 OpenRouter /api/v1/audio/transcriptions）
 * 2. 其他兼容 Whisper API 的服务
 *
 * 连续监听模式：点击按钮后持续听，直到用户再次点击才停止。
 * 积累所有识别结果，停止时一次性发送。
 */
class WhisperRecognizer(
    private val context: Context,
    private var language: String = "zh"
) {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val PREFS_NAME = "cesia_settings"
        private const val PREF_API_URL = "api_url"
        private const val PREF_OPENROUTER_KEY = "openrouter_api_key"
        private const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"

        // 默认 Whisper 模型
        private const val DEFAULT_WHISPER_MODEL = "openai/whisper-large-v3"
        private const val FALLBACK_WHISPER_MODEL = "openai/whisper-1"
    }

    // ─── OkHttp 客户端 ──────────────────────────────────
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // Whisper 识别可能耗时较长
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

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─── SharedPreferences ─────────────────────────────
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val apiKey: String
        get() = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""

    private val apiUrl: String
        get() = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL

    // ─── 初始化 ────────────────────────────────────────
    fun init(): Boolean {
        val keyPrefix = if (apiKey.isNotEmpty()) apiKey.take(8) + "..." else "(空)"
        Log.d(TAG, "WhisperRecognizer 初始化, language=$language, apiKey=$keyPrefix")
        return true
    }

    // ─── 开始监听 ──────────────────────────────────────
    fun startListening(): Boolean {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API Key 未配置")
            scope.launch { _results.emit(Result.Error("API Key 未配置，请在设置中配置")) }
            return false
        }

        if (continuousMode) accumulatedText.clear()
        _isListening = true
        restartJob?.cancel()

        return try {
            startRecording()
            Log.d(TAG, "startListening OK, continuous=$continuousMode")
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

        // 连续模式：发送已积累的文本
        if (continuousMode) {
            val text = if (accumulatedText.isNotEmpty()) accumulatedText.toString() else lastPartialText
            accumulatedText.clear()
            lastPartialText = ""
            Log.d(TAG, "stopListening → emit '${text.length}' chars")
            scope.launch {
                if (text.isNotEmpty()) {
                    _results.emit(Result.Success(text))
                } else {
                    _results.emit(Result.NoMatch)
                }
            }
        }
    }

    // ─── 销毁 ──────────────────────────────────────────
    fun destroy() {
        restartJob?.cancel()
        restartJob = null
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
        return apiKey.isNotEmpty()
    }

    // ─── 更新语言 ──────────────────────────────────────
    fun setLanguage(lang: String) {
        language = lang
        Log.d(TAG, "语言已切换为: $language")
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
                    Log.d(TAG, "录音完成, WAV 大小: ${wavData.size} bytes")

                    _results.emit(Result.Recognizing("正在识别..."))
                    val text = recognizeAudio(wavData)

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
                        if (continuousMode && _isListening) {
                            scheduleRestart()
                        } else if (!continuousMode) {
                            _results.emit(Result.NoMatch)
                        }
                    }
                } else {
                    Log.d(TAG, "录音数据为空")
                    if (!suppressNoMatchError) {
                        _results.emit(Result.Error("未检测到语音"))
                    } else if (continuousMode && _isListening) {
                        scheduleRestart()
                    } else {
                        _results.emit(Result.NoMatch)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "录音协程已取消")
            } catch (e: Exception) {
                Log.e(TAG, "录音异常", e)
                if (_isListening) {
                    _results.emit(Result.Error("录音失败: ${e.message}"))
                }
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
    //  Whisper API 调用
    // ═══════════════════════════════════════════════════

    /**
     * 将 WAV 音频数据发送到 Whisper API 进行识别
     * 使用 multipart/form-data 上传音频文件
     */
    private suspend fun recognizeAudio(wavData: ByteArray): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API Key 为空，跳过识别")
            return@withContext ""
        }

        // 尝试主模型和备用模型
        val models = listOf(DEFAULT_WHISPER_MODEL, FALLBACK_WHISPER_MODEL)
        var lastError = ""

        for (model in models.distinct()) {
            val result = tryWhisperApi(wavData, model)
            if (result.isNotEmpty()) return@withContext result
        }

        if (lastError.isNotEmpty()) {
            scope.launch { _results.emit(Result.Error("识别失败: $lastError")) }
        }
        return@withContext ""
    }

    /**
     * 通过 multipart/form-data 上传音频到 Whisper API
     * 兼容 OpenAI Whisper API 和 OpenRouter 的音频端点
     */
    private fun tryWhisperApi(wavData: ByteArray, model: String): String {
        return try {
            // 构建 multipart 请求
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", model)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "json")
                .build()

            // 确定 Whisper API 端点
            val whisperEndpoint = getWhisperEndpoint()

            val request = Request.Builder()
                .url(whisperEndpoint)
                .post(multipartBody)
                .addHeader("Authorization", "Bearer $apiKey")
                .apply {
                    // OpenRouter 特有 Header
                    if (whisperEndpoint.contains("openrouter.ai")) {
                        addHeader("HTTP-Referer", "https://github.com/harviex/cesia-input-method")
                        addHeader("X-Title", "Cesia Input Method")
                    }
                }
                .build()

            Log.d(TAG, "Whisper API 请求 [$model], 音频: ${wavData.size} bytes, 端点: $whisperEndpoint")

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "未知错误"
                    Log.e(TAG, "Whisper API 错误 [$model]: ${response.code} - $errorBody")
                    return ""
                }

                val respBody = response.body?.string() ?: return ""
                Log.d(TAG, "Whisper API 响应 [$model]: ${respBody.take(200)}")

                val respJson = JSONObject(respBody)
                val text = respJson.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    return text
                }

                Log.w(TAG, "Whisper API 返回空文本")
                return ""
            }
        } catch (e: IOException) {
            Log.e(TAG, "Whisper API 网络异常 [$model]", e)
            scope.launch { _results.emit(Result.Error("网络连接失败: ${e.message ?: "未知"}")) }
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "Whisper API 异常 [$model]", e)
            return ""
        }
    }

    /**
     * 获取 Whisper API 端点
     * 根据当前配置的 API URL 推导出正确的音频转录端点
     */
    private fun getWhisperEndpoint(): String {
        // 如果已经是音频端点，直接返回
        if (apiUrl.contains("/audio/")) return apiUrl

        // OpenRouter: chat/completions → audio/transcriptions
        if (apiUrl.contains("openrouter.ai")) {
            return apiUrl.replace("/chat/completions", "/audio/transcriptions")
        }

        // 通用：从 base URL 构建
        val baseUrl = apiUrl.substringBefore("/v1/")
        return "${baseUrl}/v1/audio/transcriptions"
    }

    // ═══════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════

    private fun isOpenRouterUrl(url: String): Boolean {
        return url.contains("openrouter.ai") || url.contains("api.cesia.cc")
    }

    private fun scheduleRestart() {
        if (!_isListening) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(500)
            if (!_isListening) return@launch
            delay(200)
            if (_isListening) {
                try {
                    startRecording()
                } catch (e: Exception) {
                    Log.e(TAG, "restart startRecording failed", e)
                }
            }
        }
    }
}
