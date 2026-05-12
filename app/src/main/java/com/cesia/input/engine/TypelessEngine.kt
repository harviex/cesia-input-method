package com.cesia.input.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.cesia.input.polish.PolishService
import com.cesia.input.recognizer.FallbackRecognizer
import com.cesia.input.wakeword.WakeWordDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.util.Log

/**
 * Typeless 引擎 —— 核心编排层
 *
 * 工作流程 (纯 SpeechRecognizer 方案):
 *
 * === 语音激活模式 (WakeWord) ===
 * 1. 后台循环监听 "Hey Typeless" (WakeWordDetector)
 * 2. 检测到唤醒词 → 切换到 ACTIVE 录音模式
 * 3. 用户说话 → 系统自带 VAD 检测静音结束
 * 4. 检测到结束词 "Typeless Over" → 去除结束词 → 提交文本
 * 5. 文本发送到润色 API
 * 6. 润色结果自动上屏 (commitText)
 * 7. 回到步骤1继续监听
 *
 * === 手动按钮模式 ===
 * 1. 用户按麦克风按钮 → SpeechRecognizer.startListening()
 * 2. 系统 VAD 检测静音结束 → onResults 回调
 * 3. 文本发送到润色 API
 * 4. 润色结果自动上屏 (commitText)
 *
 * 设计目标: 3 步完成输入 —— 说"Hey Typeless" → 说话 → 说"Typeless Over"→ 自动上屏
 */
class TypelessEngine(
    private val context: Context,
    private val service: InputMethodService
) {
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var polishService: PolishService? = null
    private var fallbackRecognizer: FallbackRecognizer? = null
    private var wakeWordDetector: WakeWordDetector? = null

    // 识别器是否可用
    private var recognizerAvailable = false

    // 引擎状态
    private var _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    enum class State {
        IDLE,          // 空闲
        MONITORING,    // 后台监听唤醒词
        LISTENING,     // 正在录音/识别
        PROCESSING,    // 正在润色
        COMMITTING,    // 正在上屏
        ERROR          // 出错
    }

    // 日志回调
    var onLogMessage: ((String) -> Unit)? = null

    // 语音激活开关
    var voiceActivationEnabled: Boolean = false
        set(value) {
            field = value
            if (value) {
                startWakeWordMonitoring()
            } else {
                wakeWordDetector?.stop()
                if (_state.value == State.MONITORING) {
                    _state.value = State.IDLE
                }
            }
        }

    init {
        // 启动识别结果监听协程
        engineScope.launch {
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
                    }
                    is FallbackRecognizer.Result.Error -> {
                        log("❌ 识别错误: ${result.message}")
                        _state.value = State.ERROR
                        engineScope.launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "语音识别失败: ${result.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    is FallbackRecognizer.Result.NoMatch -> {
                        log("🔇 未识别到语音")
                        _state.value = State.ERROR
                    }
                }
            }
        }
    }

    /**
     * 初始化引擎（创建子服务）
     */
    fun initialize() {
        polishService = PolishService(engineScope)
        fallbackRecognizer = FallbackRecognizer(context)
        wakeWordDetector = WakeWordDetector(context, engineScope)

        // 初始化识别器
        if (fallbackRecognizer?.init() == true) {
            recognizerAvailable = true
            log("✅ FallbackRecognizer 已就绪")
        } else {
            log("❌ FallbackRecognizer 初始化失败")
        }

        // 绑定唤醒词事件
        wakeWordDetector?.onWakeWord = { word ->
            log("🔥 检测到唤醒词: \"$word\"")
            _state.value = State.LISTENING
            service.commitText(word, 1)
        }
        wakeWordDetector?.onEndWord = { word ->
            log("🏁 检测到结束词: \"$word\"")
            // 结束词触发后应自动处理
        }
    }

    /**
     * 开始监听（手动/自动模式通用入口）
     */
    fun startListening() {
        if (voiceActivationEnabled) {
            startWakeWordMonitoring()
        } else {
            if (fallbackRecognizer?.startListening() == true) {
                _state.value = State.LISTENING
                log("🎤 开始录音（手动模式）")
            } else {
                _state.value = State.ERROR
                log("❌ 无法启动录音")
                showError("录音启动失败")
            }
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        if (voiceActivationEnabled) {
            wakeWordDetector?.stopActiveSession()
        } else {
            fallbackRecognizer?.stopListening()
        }
        _state.value = State.IDLE
        log("⏹ 已停止")
    }

    /**
     * 开启唤醒词监听
     */
    private fun startWakeWordMonitoring() {
        if (!voiceActivationEnabled) return
        wakeWordDetector?.startMonitoring()
        _state.value = State.MONITORING
        log("📡 开启唤醒词监听")
    }

    /**
     * 发送文本润色并提交
     */
    private fun polishAndCommit(text: String) {
        engineScope.launch {
            _state.value = State.PROCESSING
            log("🔄 正在润色... (${text.length}字)")

            val result = polishService?.polishText(text) ?: run {
                log("❌ 润色服务不可用，直接上屏原始文本")
                commitTextDirect(text)
                return@launch
            }

            when (result) {
                is PolishService.PolishResult.Success -> {
                    log("✨ 润色完成: ${result.polishedText.take(50)}")
                    commitTextDirect(result.polishedText)
                    _state.value = State.IDLE
                }
                is PolishService.PolishResult.Error -> {
                    log("❌ 润色失败: ${result.message}")
                    commitTextDirect(text)
                    _state.value = State.ERROR
                    showToast("润色失败，已上屏原文")
                }
                is PolishService.PolishResult.EmptyInput -> {
                    log("📭 输入为空，跳过")
                    _state.value = State.IDLE
                }
            }
        }
    }

    /**
     * 直接提交文本到当前输入框
     */
    private fun commitTextDirect(text: String) {
        val ic = service.currentInputConnection ?: run {
            log("❌ 无输入框连接")
            return
        }
        _state.value = State.COMMITTING
        ic.commitText(text, 1)
        log("📝 已上屏: $text")

        engineScope.launch {
            delay(200)
            _state.value = State.IDLE
        }
    }

    private fun showToast(msg: String) {
        engineScope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun showError(msg: String) {
        log("❌ $msg")
        showToast(msg)
    }

    private fun log(msg: String) {
        Log.d("TypelessEngine", msg)
        onLogMessage?.invoke(msg)
    }

    /**
     * 更新 API URL
     */
    fun updateApiUrl(url: String) {
        polishService?.updateApiUrl(url)
    }

    /**
     * 设置唤醒词
     */
    fun setWakeWord(word: String) {
        wakeWordDetector?.wakeWord = word
    }

    /**
     * 设置结束词
     */
    fun setEndWord(word: String) {
        wakeWordDetector?.endWord = word
    }

    /**
     * 销毁引擎
     */
    fun destroy() {
        wakeWordDetector?.stop()
        wakeWordDetector = null
        fallbackRecognizer?.destroy()
        fallbackRecognizer = null
        polishService?.shutdown()
        polishService = null
        engineScope.cancel()
        log("🔻 引擎已销毁")
    }
}
