package com.cesia.input.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.cesia.input.audio.AudioRecorder
import com.cesia.input.polish.PolishService
import com.cesia.input.recognizer.FallbackRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.util.Log

/**
 * Typeless 引擎 —— 核心编排层
 *
 * 工作流程:
 * 1. 用户按麦克风 → AudioRecorder 开始录音 + VAD
 * 2. VAD 检测到语音结束 → 收集 PCM
 * 3. (可选) WeNet 本地识别 → 中文文本
 * 4. 回退使用 Android SpeechRecognizer
 * 5. 文本发送到润色 API
 * 6. 润色结果自动上屏 (commitText)
 *
 * 设计目标: 3 步完成输入 —— 按麦克风 → 说话 → 自动上屏
 */
class TypelessEngine(
    private val context: Context,
    private val service: InputMethodService
) {
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val audioRecorder = AudioRecorder(engineScope)
    private var polishService: PolishService? = null
    private var fallbackRecognizer: FallbackRecognizer? = null

    // WeNet 是否可用
    private var wenetAvailable = false

    // 引擎状态
    private var _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    enum class State {
        IDLE,          // 空闲，等待用户操作
        LISTENING,     // 正在录音
        PROCESSING,    // 正在识别 + 润色
        COMMITTING,    // 正在上屏
        ERROR          // 出错
    }

    // 日志回调
    var onLogMessage: ((String) -> Unit)? = null

    init {
        // 监听录音器事件
        engineScope.launch {
            audioRecorder.events.collect { event ->
                when (event) {
                    is AudioRecorder.RecorderEvent.RmsChanged -> {
                        // 可以在这里更新 UI 音量指示
                    }
                    is AudioRecorder.RecorderEvent.Started -> {
                        _state.value = State.LISTENING
                        log("🎤 开始录音...")
                    }
                    is AudioRecorder.RecorderEvent.VadSilence -> {
                        log("🔇 检测到静音，结束录音")
                        processAudio()
                    }
                    is AudioRecorder.RecorderEvent.Stopped -> {
                        _state.value = State.IDLE
                    }
                    is AudioRecorder.RecorderEvent.Error -> {
                        _state.value = State.ERROR
                        log("❌ 录音错误: ${event.message}")
                    }
                    is AudioRecorder.RecorderEvent.AudioData -> {
                        // 实时音频数据，暂不处理
                    }
                }
            }
        }
    }

    /**
     * 初始化引擎
     */
    fun initialize() {
        // 初始化润色服务
        polishService = PolishService(engineScope).also { service ->
            // TODO: 从 SharedPreferences 加载 API URL
            // service.updateApiUrl(savedUrl)
        }

        // 初始化 Android 备用识别
        fallbackRecognizer = FallbackRecognizer(context).also { recognizer ->
            wenetAvailable = recognizer.init()
            if (wenetAvailable) {
                log("✅ Android 语音识别已就绪")
            } else {
                log("⚠️ Android 语音识别不可用")
            }

            // 监听识别结果
            engineScope.launch {
                recognizer.results.collect { result ->
                    when (result) {
                        is FallbackRecognizer.Result.Success -> {
                            log("📝 识别成功: ${result.text.take(50)}...")
                            polishAndCommit(result.text, result.originalText)
                        }
                        is FallbackRecognizer.Result.Partial -> {
                            log("📝 部分识别: ${result.text}")
                        }
                        is FallbackRecognizer.Result.Error -> {
                            log("❌ 识别错误: ${result.message}")
                            _state.value = State.ERROR
                        }
                        is FallbackRecognizer.Result.NoMatch -> {
                            log("❓ 未识别到语音")
                            _state.value = State.IDLE
                        }
                    }
                }
            }
        }
    }

    /**
     * 开始录音 (由麦克风按钮触发)
     */
    fun startListening() {
        if (_state.value == State.LISTENING) return

        log("🎙️ Typeless 引擎启动")
        engineScope.launch {
            // 开始录音并收集 VAD 分片
            audioRecorder.startRecordingWithVad().collect { chunks ->
                log("📦 采集到 ${chunks.size} 个音频分片")

                // 合并 PCM 数据
                val totalSize = chunks.sumOf { it.size }
                val mergedPcm = ByteArray(totalSize)
                var offset = 0
                chunks.forEach { chunk ->
                    System.arraycopy(chunk, 0, mergedPcm, offset, chunk.size)
                    offset += chunk.size
                }

                // 使用 Android 语音识别
                fallbackRecognizer?.startListening()
            }
        }
    }

    /**
     * 处理录音完成后的音频
     */
    private fun processAudio() {
        engineScope.launch {
            _state.value = State.PROCESSING
            log("⚙️ 正在处理音频...")

            // 停止识别（FallbackRecognizer 是事件驱动的，这里只需要停止录音器）
            audioRecorder.stop()

            // FallbackRecognizer 会在 onResults 回调中自动触发 polishAndCommit
            // 所以这里不需要额外操作
        }
    }

    /**
     * 润色文本并上屏 —— Typeless 核心流程
     */
    private fun polishAndCommit(rawText: String, originalText: String = "") {
        engineScope.launch {
            _state.value = State.PROCESSING
            log("🔄 正在润色文本...")

            val polishService = polishService
            if (polishService == null) {
                // 没有润色服务，直接上屏原始文本
                log("⚠️ 润色服务未初始化，直接上屏")
                commitTextToEditor(rawText)
                return@launch
            }

            val result = polishService.polishText(rawText)
            when (result) {
                is PolishService.PolishResult.Success -> {
                    _state.value = State.COMMITTING
                    val polished = if (result.polishedText.isBlank()) result.originalText else result.polishedText
                    log("✅ 润色完成: $polished")
                    commitTextToEditor(polished)
                }
                is PolishService.PolishResult.Error -> {
                    log("❌ 润色失败: ${result.message}")
                    // 润色失败，回退到原始文本
                    commitTextToEditor(rawText)
                }
                PolishService.PolishResult.EmptyInput -> {
                    log("⚠️ 空输入，跳过")
                }
            }
        }
    }

    /**
     * 将文本提交到输入框 —— 真正的 commitText
     * 使用 InputMethodService 的 currentInputConnection
     */
    private fun commitTextToEditor(text: String) {
        engineScope.launch {
            _state.value = State.COMMITTING

            // 清除可能存在的 composing text
            val conn = service.currentInputConnection ?: run {
                log("❌ 无法获取 InputConnection")
                _state.value = State.IDLE
                return@launch
            }

            // 清除当前的 composing
            conn.finishComposingText()

            // 分块提交以避免单次过长
            val maxChunk = 200
            if (text.length <= maxChunk) {
                conn.commitText(text, 1)
            } else {
                // 分块提交
                var remaining = text
                while (remaining.isNotEmpty()) {
                    val chunk = remaining.substring(0, kotlin.math.min(maxChunk, remaining.length))
                    conn.commitText(chunk, 1)
                    remaining = remaining.substring(chunk.length)
                    delay(50) // 小延迟避免太快
                }
            }

            log("✅ 已上屏: ${text.take(50)}...")
            _state.value = State.IDLE

            // 显示 Toast 反馈
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "✓ ${text.take(30)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 停止录音
     */
    fun stopListening() {
        audioRecorder.stop()
        fallbackRecognizer?.stopListening()
        log("⏹️ 录音已停止")
    }

    /**
     * 销毁引擎
     */
    fun destroy() {
        engineScope.cancel()
        fallbackRecognizer?.destroy()
        polishService?.shutdown()
        audioRecorder.stop()
    }

    /**
     * 更新 API URL
     */
    fun updateApiUrl(url: String) {
        polishService?.updateApiUrl(url)
    }

    private fun log(msg: String) {
        Log.d("TypelessEngine", msg)
        onLogMessage?.invoke(msg)
    }
}