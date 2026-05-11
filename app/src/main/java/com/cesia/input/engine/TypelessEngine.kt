package com.cesia.input.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.cesia.input.polish.PolishService
import com.cesia.input.recognizer.FallbackRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.util.Log

/**
 * Typeless 引擎 —— 核心编排层
 *
 * 工作流程 (纯 SpeechRecognizer 方案):
 * 1. 用户按麦克风 → SpeechRecognizer.startListening()
 * 2. Android 系统自带 VAD，自动检测静音结束
 * 3. onResults 回调 → 获取识别文本
 * 4. 文本发送到润色 API
 * 5. 润色结果自动上屏 (commitText)
 *
 * 设计目标: 3 步完成输入 —— 按麦克风 → 说话 → 自动上屏
 *
 * 未来扩展: 集成 WeNet 离线识别后，AudioRecorder 将接管录音和 VAD，
 *           替代 SpeechRecognizer，实现完全离线的 Typeless 输入。
 */
class TypelessEngine(
    private val context: Context,
    private val service: InputMethodService
) {
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var polishService: PolishService? = null
    private var fallbackRecognizer: FallbackRecognizer? = null

    // 识别器是否可用
    private var recognizerAvailable = false

    // 引擎状态
    private var _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    enum class State {
        IDLE,          // 空闲，等待用户操作
        LISTENING,     // 正在录音/识别
        PROCESSING,    // 正在润色
        COMMITTING,    // 正在上屏
        ERROR          // 出错
    }

    // 日志回调
    var onLogMessage: ((String) -> Unit)? = null

    init {
        // 启动识别结果监听协程（长生命周期，随引擎销毁而取消）
        engineScope.launch {
            // 等待 fallbackRecognizer 初始化完成
            while (fallbackRecognizer == null) {
                delay(50)
            }

            fallbackRecognizer?.results?.collect { result ->
                when (result) {
                    is FallbackRecognizer.Result.Success -> {
                        log("📝 识别成功: ${result.text.take(50)}...")
                        polishAndCommit(result.text)
                    }
                    is FallbackRecognizer.Result.Partial -> {
                        log("📝 部分识别: ${result.text}")
                        // 可以在这里显示部分结果给预览
                    }
                    is FallbackRecognizer.Result.Error -> {
                        log("❌ 识别错误: ${result.message}")
                        _state.value = State.ERROR
                        // 识别失败时给用户反馈
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "语音识别失败: ${result.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    is FallbackRecognizer.Result.NoMatch -> {
                        log("❓ 未识别到语音")
                        _state.value = State.IDLE
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "未识别到语音，请重试", Toast.LENGTH_SHORT)
                                .show()
                        }
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
        polishService = PolishService(engineScope)

        // 初始化 Android 系统语音识别
        fallbackRecognizer = FallbackRecognizer(context).also { recognizer ->
            recognizerAvailable = recognizer.init()
            if (recognizerAvailable) {
                log("✅ 系统语音识别已就绪")
            } else {
                log("⚠️ 系统语音识别不可用，将无法使用语音输入")
            }
        }
    }

    /**
     * 开始语音识别 (由麦克风按钮触发)
     *
     * SpeechRecognizer.startListening() 会自动:
     * - 打开麦克风
     * - 进行 VAD (语音活动检测)
     * - 检测到静音后自动停止并返回结果
     */
    fun startListening() {
        if (_state.value == State.LISTENING) return

        val recognizer = fallbackRecognizer
        if (recognizer == null || !recognizerAvailable) {
            log("❌ 语音识别未初始化或不可用")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "语音识别不可用，请检查设备是否支持",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        _state.value = State.LISTENING
        log("🎤 开始语音识别...")

        // SpeechRecognizer 内部自带 VAD，调用 startListening 即开始录音+识别
        // 识别完成（检测到静音）后会自动通过 results Flow 发射结果
        recognizer.startListening()
    }

    /**
     * 润色文本并上屏 —— Typeless 核心流程
     */
    private fun polishAndCommit(rawText: String) {
        engineScope.launch {
            _state.value = State.PROCESSING
            log("🔄 正在润色: ${rawText.take(30)}...")

            val polishService = polishService
            if (polishService == null) {
                log("⚠️ 润色服务未初始化，直接上屏原始文本")
                commitTextToEditor(rawText)
                return@launch
            }

            val result = polishService.polishText(rawText)
            when (result) {
                is PolishService.PolishResult.Success -> {
                    _state.value = State.COMMITTING
                    val polished =
                        if (result.polishedText.isBlank()) result.originalText else result.polishedText
                    log("✅ 润色完成: $polished")
                    commitTextToEditor(polished)
                }
                is PolishService.PolishResult.Error -> {
                    log("❌ 润色失败: ${result.message}")
                    // 润色失败，回退到原始文本
                    commitTextToEditor(rawText)
                }
                PolishService.PolishResult.EmptyInput -> {
                    log("⚠️ 空识别结果，跳过")
                    _state.value = State.IDLE
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
                var remaining = text
                while (remaining.isNotEmpty()) {
                    val chunk = remaining.substring(0, kotlin.math.min(maxChunk, remaining.length))
                    conn.commitText(chunk, 1)
                    remaining = remaining.substring(chunk.length)
                    delay(50)
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
     * 停止录音/识别
     */
    fun stopListening() {
        fallbackRecognizer?.stopListening()
        log("⏹️ 语音识别已停止")
    }

    /**
     * 销毁引擎
     */
    fun destroy() {
        engineScope.cancel()
        fallbackRecognizer?.destroy()
        polishService?.shutdown()
        log("引擎已销毁")
    }

    /**
     * 更新 API URL
     */
    fun updateApiUrl(url: String) {
        polishService?.updateApiUrl(url)
        log("API URL 已更新")
    }

    /**
     * 获取引擎状态信息
     */
    fun getStateInfo(): String {
        return when (_state.value) {
            State.IDLE -> "就绪"
            State.LISTENING -> "🎤 识别中..."
            State.PROCESSING -> "🔄 润色中..."
            State.COMMITTING -> "⬆️ 上屏中..."
            State.ERROR -> "❌ 出错"
        }
    }

    private fun log(msg: String) {
        Log.d("TypelessEngine", msg)
        onLogMessage?.invoke(msg)
    }
}