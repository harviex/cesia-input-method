package com.cesia.input.recognizer

import android.content.Context
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 语音识别器（Android SpeechRecognizer）
 * 
 * 支持两种模式：
 * - 单次模式 (continuous = false): 识别一次后立即发送结果
 * - 连续模式 (continuous = true): 持续积累识别结果，直到 stopListening 时一次性发送
 */
class FallbackRecognizer(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    /**
     * 是否启用连续积累模式。
     * 当连续模式开启时，onResults 会积累文本而不是立即发送，
     * 直到 stopListening() 被调用时才将所有积累的文本一次性发送。
     */
    var continuousMode: Boolean = false

    /** 连续模式下积累的文本 */
    private val accumulatedText = StringBuilder()

    sealed class Result {
        data class Success(val text: String, val confidence: Float = 1.0f) : Result()
        data class Partial(val text: String) : Result()
        data class Error(val message: String) : Result()
        data class Recognizing(val text: String) : Result()
        object NoMatch : Result()
    }

    private val _results = MutableSharedFlow<Result>(replay = 0, extraBufferCapacity = 1)
    val results = _results.asSharedFlow()

    /**
     * 是否在安静（无语音）时保持沉默，而不是报错
     */
    var suppressNoMatchError: Boolean = true

    fun init(): Boolean {
        return try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
                recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        // 准备就绪
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        // 说话结束，系统正在处理中
                        CoroutineScope(Dispatchers.Main).launch {
                            _results.emit(Result.Recognizing("正在识别..."))
                        }
                    }

                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                // 安静/无语音时不报错，保持静默
                                if (!suppressNoMatchError) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        _results.emit(Result.NoMatch)
                                    }
                                }
                                // 连续模式下自动重启
                                if (isListening) {
                                    restartListening()
                                }
                            }
                            SpeechRecognizer.ERROR_CLIENT -> {
                                // 客户端错误（通常在处理中），不报错
                                if (!suppressNoMatchError) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        _results.emit(Result.Recognizing("正在识别..."))
                                    }
                                }
                                // 延迟后自动重启
                                if (isListening) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(500)
                                        restartListening()
                                    }
                                }
                            }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                // 识别器忙 — 销毁重建期间被调用 startListening，加延迟重试
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(2000)
                                    if (isListening) restartListening()
                                }
                            }
                            SpeechRecognizer.ERROR_NETWORK -> {
                                CoroutineScope(Dispatchers.Main).launch {
                                    _results.emit(Result.Error("网络错误，请检查网络"))
                                }
                                if (isListening) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(1000)
                                        restartListening()
                                    }
                                }
                            }
                            SpeechRecognizer.ERROR_AUDIO,
                            SpeechRecognizer.ERROR_SERVER,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT_LIMIT_REACHED -> {
                                // 音频/服务器/无语音错误，不报错静默重启
                                if (isListening) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(500)
                                        restartListening()
                                    }
                                }
                            }
                            else -> {
                                // 其他错误
                                val msg = "识别错误"
                                CoroutineScope(Dispatchers.Main).launch {
                                    _results.emit(Result.Error(msg))
                                }
                                if (isListening) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(2000)
                                        restartListening()
                                    }
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val best = matches[0]
                            val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                            val confidence = scores?.firstOrNull() ?: 1.0f
                            
                            if (continuousMode) {
                                // 连续模式：积累文本，不立即发送
                                if (accumulatedText.isNotEmpty()) {
                                    accumulatedText.append(" ")
                                }
                                accumulatedText.append(best)
                                CoroutineScope(Dispatchers.Main).launch {
                                    _results.emit(Result.Partial(accumulatedText.toString()))
                                }
                                // 自动重启以保持持续监听
                                if (isListening) {
                                    restartListening()
                                }
                            } else {
                                // 单次模式：立即发送结果
                                CoroutineScope(Dispatchers.Main).launch {
                                    _results.emit(Result.Success(best, confidence))
                                }
                            }
                        } else {
                            if (!suppressNoMatchError) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    _results.emit(Result.NoMatch)
                                }
                            }
                            if (isListening) {
                                restartListening()
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            CoroutineScope(Dispatchers.Main).launch {
                                _results.emit(Result.Partial(matches[0]))
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            true
        } catch (e: Exception) {
            Log.e("FallbackRecognizer", "初始化失败", e)
            false
        }
    }

    fun startListening(): Boolean {
        val recognizer = speechRecognizer ?: return false
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false

        // 连续模式下重置积累缓冲区
        if (continuousMode) {
            accumulatedText.clear()
        }

        isListening = true
        return try {
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer.startListening(intent)
            true
        } catch (e: Exception) {
            Log.e("FallbackRecognizer", "启动识别失败", e)
            false
        }
    }

    /**
     * 重新启动监听（保持 isListening 状态）
     * SpeechRecognizer 需要销毁重建才能重新开始，加短延迟避免 ERROR_CLIENT
     */
    private fun restartListening() {
        speechRecognizer?.destroy()
        speechRecognizer = null

        CoroutineScope(Dispatchers.Main).launch {
            delay(200)  // 给系统释放识别资源的时间，避免 ERROR_CLIENT
            if (isListening && init()) {
                startListening()
            }
        }
    }

    fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()

        // 连续模式下：如果有积累的文本，作为成功结果发送
        if (continuousMode && accumulatedText.isNotEmpty()) {
            val text = accumulatedText.toString()
            accumulatedText.clear()
            CoroutineScope(Dispatchers.Main).launch {
                _results.emit(Result.Success(text))
            }
        }
    }

    fun destroy() {
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        accumulatedText.clear()
        continuousMode = false
    }

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
}
