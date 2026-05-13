package com.cesia.input.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.widget.Toast
import com.cesia.input.polish.PolishService
import com.cesia.input.recognizer.FallbackRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.util.Log

/**
 * Typeless 引擎 —— 核心编排层
 *
 * 工作流程（纯手动按钮模式）:
 * 1. 用户按麦克风按钮 → SpeechRecognizer.startListening()
 * 2. 系统 VAD 检测静音结束 → onResults 回调
 * 3. 文本发送到润色 API
 * 4. 润色结果自动上屏 (commitText)
 */
class TypelessEngine(
    private val context: Context,
    private val service: InputMethodService
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var polishService: PolishService? = null
    private var fallbackRecognizer: FallbackRecognizer? = null

    // 引擎状态
    private var _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    enum class State {
        IDLE,          // 空闲
        LISTENING,     // 正在录音/识别
        PROCESSING,    // 正在润色
        ERROR          // 出错
    }

    // 日志回调
    var onLogMessage: ((String) -> Unit)? = null

    init {
        // 启动识别结果监听协程
        engineScope.launch {
            while (fallbackRecognizer == null) delay(50)

            fallbackRecognizer?.results?.collect { result ->
                when (result) {
                    is FallbackRecognizer.Result.Success -> {
                        log("📝 识别成功：${result.text.take(50)}")
                        polishAndCommit(result.text)
                    }
                    is FallbackRecognizer.Result.Partial -> {
                        // 实时部分结果，显示在状态栏
                        log("🎤 ${result.text}")
                    }
                    is FallbackRecognizer.Result.Recognizing -> {
                        log("🔄 ${result.text}")
                    }
                    is FallbackRecognizer.Result.Error -> {
                        log("❌ ${result.message}")
                        _state.value = State.ERROR
                    }
                    is FallbackRecognizer.Result.NoMatch -> {
                        // 无匹配时不报错
                    }
                }
            }
        }
    }

    /** 初始化引擎 */
    fun initialize() {
        polishService = PolishService(engineScope)
        fallbackRecognizer = FallbackRecognizer(context)

        if (fallbackRecognizer?.init() == true) {
            log("✅ Cesia 已就绪")
        } else {
            log("❌ Cesia 初始化失败")
        }
    }

    /** 开始监听 */
    fun startListening(continuous: Boolean = false) {
        fallbackRecognizer?.continuousMode = continuous
        if (fallbackRecognizer?.startListening() == true) {
            _state.value = State.LISTENING
        } else {
            _state.value = State.ERROR
            showError("录音启动失败")
        }
    }

    /** 停止监听 */
    fun stopListening() {
        fallbackRecognizer?.stopListening()
        _state.value = State.IDLE
    }

    /** 发送文本润色并提交 */
    private fun polishAndCommit(text: String) {
        engineScope.launch {
            _state.value = State.PROCESSING
            log("🔄 正在润色... (${text.length}字)")

            try {
                val polishResult = polishService?.polishText(text)

                val finalText = when (polishResult) {
                    is PolishService.PolishResult.Success -> {
                        val cleaned = cleanPolishedText(polishResult.polishedText)
                        if (isPlaceholder(cleaned)) {
                            log("⚠️ API 返回占位符，使用原文")
                            text
                        } else {
                            log("✨ 润色完成: ${cleaned.take(50)}")
                            cleaned
                        }
                    }
                    is PolishService.PolishResult.Error -> {
                        log("⚠️ 润色失败，使用原文")
                        text
                    }
                    is PolishService.PolishResult.EmptyInput -> {
                        log("🗑️ 输入为空，跳过")
                        ""
                    }
                    null -> {
                        log("❌ 服务不可用，使用原文")
                        text
                    }
                }

                if (finalText.isNotEmpty()) {
                    commitText(finalText)
                }
                withContext(Dispatchers.Main) { _state.value = State.IDLE }
            } catch (e: Exception) {
                log("❌ 润色异常: ${e.message}")
                commitText(text)
                withContext(Dispatchers.Main) { _state.value = State.ERROR }
            }
        }
    }

    private fun cleanPolishedText(raw: String): String {
        return raw.trim().removePrefix("```").removeSuffix("```").trim()
    }

    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "polished_text" || t == "(polished_text)" || t == "<polished_text>" || t.isEmpty()
    }

    /** 提交文本到输入框 */
    private fun commitText(text: String) {
        val ic = service.currentInputConnection ?: run {
            log("❌ 无输入框连接")
            return
        }
        // 切换到主线程提交
        runBlocking {
            withContext(Dispatchers.Main) {
                ic.commitText(text, 1)
                log("✅ Cesia 已就绪")
                _state.value = State.IDLE
            }
        }
    }

    private fun showToast(msg: String) {
        runBlocking {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
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

    /** 更新 API URL */
    fun updateApiUrl(url: String) { polishService?.updateApiUrl(url) }

    /** 销毁引擎 */
    fun destroy() {
        fallbackRecognizer?.destroy()
        fallbackRecognizer = null
        polishService?.shutdown()
        polishService = null
        engineScope.cancel()
        log("🔻 引擎已销毁")
    }
}
