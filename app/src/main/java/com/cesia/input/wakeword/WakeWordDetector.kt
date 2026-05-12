package com.cesia.input.wakeword

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 离线唤醒词检测器
 *
 * 使用 Android 内置 SpeechRecognizer 实现：
 * 1. 后台循环监听唤醒词 "Hey Typeless"
 * 2. 录音过程中检测结束词 "Typeless Over"
 *
 * ⚠️ 注意: SpeechRecognizer 激活时会显示系统识别图标（无法避免）。
 * 如需完全隐形的唤醒体验，未来可替换为 Porcupine 离线引擎。
 *
 * 工作流程:
 * MONITORING → 检测到唤醒词 → ACTIVE（录音中）
 * ACTIVE → 检测到结束词 / 静音超时 → PROCESSING → 回到 MONITORING
 */
class WakeWordDetector(
    private val context: Context,
    private val scope: CoroutineScope
) {
    sealed class Event {
        /** 唤醒词检测到 */
        data class WakeWordDetected(val text: String = "") : Event()

        /** 结束词检测到，携带去除结束词后的文本 */
        data class EndWordDetected(val text: String = "") : Event()

        /** 实时中间结果 */
        data class PartialText(val text: String) : Event()

        /** 错误 */
        data class Error(val message: String) : Event()

        /** 检测器就绪 */
        object Ready : Event()
    }

    enum class Mode { IDLE, MONITORING, ACTIVE }

    @Volatile
    var mode = Mode.IDLE
        private set

    /** 唤醒词 - 默认为 "Hey Typeless" */
    var wakeWord: String = "Hey Typeless"

    /** 结束词 - 默认为 "Typeless Over" */
    var endWord: String = "Typeless Over"

    /** 语音激活功能是否启用 */
    var voiceActivationEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                stop()
            }
        }

    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    /**
     * 启动唤醒词监控
     * 在语音激活模式下，循环监听 "Hey Typeless"
     */
    fun start() {
        if (isRunning || !voiceActivationEnabled) return

        isRunning = true
        scope.launch {
            _events.emit(Event.Ready)
            startMonitoringLoop()
        }
    }

    /**
     * 停止所有检测
     */
    fun stop() {
        isRunning = false
        recognizer?.let { rec ->
            try {
                rec.stopListening()
            } catch (_: Exception) {}
            rec.destroy()
        }
        recognizer = null
        mode = Mode.IDLE
    }

    // =====================
    // 监控循环 (MONITORING 模式)
    // =====================

    private fun startMonitoringLoop() {
        if (!isRunning) return
        mode = Mode.MONITORING
        Log.d("WakeWordDetector", "监控启动，等待唤醒词: $wakeWord")

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            scope.launch { _events.emit(Event.Error("语音识别不可用")) }
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            this@WakeWordDetector.recognizer = it
            it.setRecognitionListener(monitoringListener)
        }

        recognizer.startListening(createMonitoringIntent())
    }

    /**
     * 唤醒词检测成功 → 切换到 ACTIVE 模式
     */
    private fun onWakeWordCaught() {
        if (!isRunning) return
        Log.d("WakeWordDetector", "✅ 唤醒词检测到！切换为录音模式")
        mode = Mode.ACTIVE

        // 销毁监控用的 recognizer，创建新的 recognizer 用于主动录音
        recognizer?.let { rec ->
            try { rec.stopListening() } catch (_: Exception) {}
            rec.destroy()
        }

        val activeRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            this@WakeWordDetector.recognizer = it
            it.setRecognitionListener(activeListener)
        }

        scope.launch { _events.emit(Event.WakeWordDetected()) }

        activeRecognizer.startListening(createActiveIntent())
    }

    // =====================
    // 录音模式 (ACTIVE 模式)
    // =====================

    /** 录音过程中累积的文本 */
    private val accumulatedText = StringBuilder()

    private fun onEndWordCaught(currentText: String) {
        if (!isRunning) return
        Log.d("WakeWordDetector", "✅ 结束词检测到！")
        mode = Mode.IDLE

        accumulators.clear()

        // 从文本中去除结束词
        val cleanText = currentText
            .replace(Regex(endWord, RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        recognizer?.let { rec ->
            try { rec.stopListening() } catch (_: Exception) {}
            rec.destroy()
        }

        scope.launch {
            _events.emit(Event.EndWordDetected(cleanText))
        }

        // 回到监控模式
        if (voiceActivationEnabled) {
            startMonitoringLoop()
        }
    }

    private fun onActiveSessionEnd(text: String) {
        if (!isRunning) return

        val clean = text
            .replace(Regex(endWord, RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        mode = Mode.IDLE

        recognizer?.let { rec ->
            try { rec.stopListening() } catch (_: Exception) {}
            rec.destroy()
        }

        scope.launch {
            if (clean.isNotBlank()) {
                _events.emit(Event.EndWordDetected(clean))
            }
            // 回到监控模式
            if (voiceActivationEnabled) {
                delay(500)
                startMonitoringLoop()
            }
        }
    }

    // =====================
    // 事件监听器
    // =====================

    private val monitoringListener = object : android.speech.RecognitionListener {
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onPartialResults(results: Bundle?) {
            val text = extractText(results) ?: return
            // 在监控模式下检查唤醒词（区分大小写更宽容）
            if (text.containsMatchIn(wakeWord)) {
                recognizer?.stopListening()
                onWakeWordCaught()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = extractText(results) ?: ""
            if (text.containsMatchIn(wakeWord)) {
                // 最终结果中也检测到唤醒词
                onWakeWordCaught()
            } else if (isRunning) {
                // 没有检测到唤醒词，重新开始监听
                startMonitoringLoop()
            }
        }

        override fun onError(error: Int) {
            val isTimeout = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_AUDIO

            if (isRunning && isTimeout) {
                // 预期内的超时，重新开始
                startMonitoringLoop()
            } else if (isRunning) {
                scope.launch { _events.emit(Event.Error("识别错误: $error")) }
                if (voiceActivationEnabled) startMonitoringLoop()
            }
        }

        override fun onEndOfSpeech() {
            // 监控模式下语音结束，重新开始
            if (isRunning && mode == Mode.MONITORING) {
                startMonitoringLoop()
            }
        }
    }

    private val activeListener = object : android.speech.RecognitionListener {
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onPartialResults(results: Bundle?) {
            val text = extractText(results) ?: return
            accumulators.add(text)

            scope.launch { _events.emit(Event.PartialText(text)) }

            // 检查结束词
            if (text.containsMatchIn(endWord)) {
                recognizer?.stopListening()
                onEndWordCaught(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = extractText(results) ?: ""
            accumulators.add(text)

            onActiveSessionEnd(text)
        }

        override fun onError(error: Int) {
            val isTimeout = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            if (isTimeout) {
                // 静音超时，结束本次录音
                val allText = accumulators.joinToString(" ")
                onActiveSessionEnd(allText)
            } else {
                scope.launch { _events.emit(Event.Error("识别错误: $error")) }
                if (voiceActivationEnabled) startMonitoringLoop()
            }
        }

        override fun onEndOfSpeech() {
            // ACTIVE 模式下语音结束
            // recognizer 会在此时自动调用 onResults
        }
    }

    // =====================
    // 工具方法
    // =====================

    private val accumulators = mutableListOf<String>()

    private fun createMonitoringIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 较短超时以便快速循环检测
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    private fun createActiveIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 更长的超时以覆盖完整对话
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    private fun String.containsMatchIn(keyword: String): Boolean {
        return this.contains(keyword, ignoreCase = true)
    }

    private fun extractText(results: Bundle?): String? {
        return try {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}