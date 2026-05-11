package com.cesia.input.recognizer

import android.content.Context
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.cesia.input.audio.AudioRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Android 系统语音识别的备用方案
 * 不依赖 WeNet，在 WeNet 不可用时自动降级
 */
class FallbackRecognizer(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isReady = false

    sealed class Result {
        data class Success(val text: String, val confidence: Float = 1.0f) : Result()
        data class Partial(val text: String) : Result()
        data class Error(val message: String) : Result()
        object NoMatch : Result()
    }

    private val _results = MutableSharedFlow<Result>(replay = 0)
    val results = _results.asSharedFlow()

    fun init(): Boolean {
        return try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
                recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isReady = true
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                            else -> "未知错误: $error"
                        }
                        CoroutineScope(Dispatchers.Main).launch {
                            _results.emit(Result.Error(msg))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val best = matches[0]
                            val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                            val confidence = scores?.firstOrNull() ?: 1.0f
                            CoroutineScope(Dispatchers.Main).launch {
                                _results.emit(Result.Success(best, confidence))
                            }
                        } else {
                            CoroutineScope(Dispatchers.Main).launch {
                                _results.emit(Result.NoMatch)
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

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isReady = false
    }

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
}