package com.cesia.input

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import com.cesia.input.ui.CustomKeyboardView
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import com.cesia.input.engine.TypelessEngine

/**
 * Cesia 输入法 — 语音自动润色上屏
 *
 * 键盘布局：
 * - 第一行：数字 1-0
 * - 第二至四行：QWERTY 字母键，每个键有小符号标注
 * - 第五行：符号切换键 + 标点 + 空格 + 回车 + 发送
 *
 * 功能：
 * - 点击符号键 → 切换到符号键盘
 * - 长按字母键 → Fn 效果，输出对应符号
 * - 左下角按钮 → 加载已输入文字 → 语音指令修改 → API 润色替换
 */
class CesiaInputMethod : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    // 视图
    private lateinit var keyboardView: CustomKeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolKeyboard: Keyboard
    private var currentKeyboard: Keyboard? = null

    private lateinit var micButton: com.google.android.material.button.MaterialButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnSwitchIme: ImageButton
    private lateinit var btnMagic: ImageButton  // 左下角魔法按钮（语音修改）
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    // 核心组件
    private var typelessEngine: TypelessEngine? = null

    // 状态
    private var isRecording = false
    private var isSymbolMode = false
    private var isCapsLock = false

    // 长按检测
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var currentLongPressKey: Keyboard.Key? = null
    private var longPressTriggered = false

    // 魔法模式
    private var magicMode = false
    private var magicOriginalText = ""

    // 设置
    private var apiUrl = "https://typeless-ai-service.vercel.app/api/polish"

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://typeless-ai-service.vercel.app/api/polish"
        const val KEYCODE_SWITCH_SYMBOL = -100
    }

    override fun onCreate() {
        setTheme(R.style.Theme_Cesia)
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)

        keyboardView = view.findViewById(R.id.keyboard_view)
        micButton = view.findViewById(R.id.btn_mic)
        btnSettings = view.findViewById(R.id.btn_settings)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnSwitchIme = view.findViewById(R.id.btn_switch_ime)
        btnMagic = view.findViewById(R.id.btn_magic)
        statusDot = view.findViewById(R.id.v_status_dot)
        statusText = view.findViewById(R.id.tv_status)

        // 初始化键盘
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolKeyboard = Keyboard(this, R.xml.symbols)
        currentKeyboard = qwertyKeyboard

        keyboardView.keyboard = currentKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.isPreviewEnabled = true

        // 初始化引擎
        typelessEngine = TypelessEngine(this, this).also { engine ->
            engine.onLogMessage = { msg ->
                Handler(Looper.getMainLooper()).post { updateStatus(msg) }
            }
            engine.onMagicResult = { recognizedText ->
                Handler(Looper.getMainLooper()).post {
                    handleMagicResult(recognizedText)
                }
            }
            engine.initialize()
        }

        loadSettings()
        setupButtonListeners()

        updateStatus("Cesia 已就绪")
        setStatusDot("idle")

        return view
    }

    private fun setupButtonListeners() {
        micButton.setOnClickListener { toggleRecording() }
        micButton.setOnLongClickListener {
            if (isRecording) { stopRecording(); true } else false
        }

        btnSettings.setOnClickListener { showSettings() }

        btnDelete.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(Integer.MAX_VALUE, 0)
        }

        btnSwitchIme.setOnClickListener { switchToNextInputMethod() }

        // 魔法按钮：加载已输入文字 → 语音修改指令
        btnMagic.setOnClickListener { startMagicMode() }
    }

    private fun switchToNextInputMethod() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        }
    }

    private fun showSettings() {
        Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(this)
        }
    }

    private fun loadSettings() {
        try {
            val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            apiUrl = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
            typelessEngine?.updateApiUrl(apiUrl)
        } catch (_: Exception) {
            apiUrl = DEFAULT_API_URL
        }
    }

    // ======================== 录音 ========================

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        isRecording = true
        micButton.isActivated = true
        micButton.text = "⏹️ 再次点击完成"
        setStatusDot("recording")
        updateStatus("🎤 正在收听，请说话...")
        typelessEngine?.startListening(continuous = true)
    }

    private fun stopRecording() {
        isRecording = false
        micButton.isActivated = false
        micButton.text = "🎤 点击开始说话"
        setStatusDot("idle")
        typelessEngine?.stopListening()
        updateStatus("Cesia 已就绪")
    }

    // ======================== 魔法模式 ========================

    private fun startMagicMode() {
        // 获取当前输入框的全部文字
        val ic = currentInputConnection ?: run {
            updateStatus("❌ 无输入框连接")
            return
        }
        val extracted = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
        val extractedAfter = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
        val fullText = extracted + extractedAfter

        if (fullText.isEmpty()) {
            updateStatus("⚠️ 输入框无文字，无法修改")
            return
        }

        // 进入魔法模式
        magicOriginalText = fullText
        magicMode = true
        typelessEngine?.magicMode = true

        updateStatus("🎤 请说出修改指令...")
        setStatusDot("recording")
        isRecording = true
        micButton.isActivated = true
        micButton.text = "⏹️ 再次点击完成"

        typelessEngine?.startListening(continuous = true)
    }

    private fun handleMagicResult(recognizedText: String) {
        // 退出魔法模式
        magicMode = false
        typelessEngine?.magicMode = false
        isRecording = false
        micButton.isActivated = false
        micButton.text = "🎤 点击开始说话"
        setStatusDot("idle")

        val instruction = recognizedText.trim()
        if (instruction.isEmpty()) {
            updateStatus("⚠️ 未识别到指令")
            return
        }

        Log.d("CesiaMagic", "原文: $magicOriginalText")
        Log.d("CesiaMagic", "指令: $instruction")
        updateStatus("🔄 正在处理修改指令...")

        // 构建魔法 prompt 并调用 API
        val prompt = buildMagicPrompt(magicOriginalText, instruction)
        val polishService = typelessEngine?.getPolishService()

        Thread {
            try {
                val result = polishService?.polishWithPrompt(prompt)
                Handler(Looper.getMainLooper()).post {
                    if (result != null && result.isNotEmpty() && result != magicOriginalText) {
                        replaceInputText(result)
                        updateStatus("✅ 修改完成")
                    } else {
                        updateStatus("⚠️ 修改结果为空或无变化")
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    updateStatus("❌ 修改失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun buildMagicPrompt(original: String, instruction: String): String {
        return "原文：$original\n\n用户指令：$instruction\n\n请根据用户指令修改原文，输出修改后的完整文本。只输出修改后的文本，不要输出任何解释。"
    }

    private fun replaceInputText(newText: String) {
        val ic = currentInputConnection ?: return
        // 选中全部文字然后替换
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(newText, 1)
    }

    // ======================== 键盘切换 ========================

    private fun switchToSymbolKeyboard() {
        if (!isSymbolMode) {
            isSymbolMode = true
            currentKeyboard = symbolKeyboard
            keyboardView.keyboard = symbolKeyboard
            keyboardView.invalidateAllKeys()
        }
    }

    private fun switchToQwertyKeyboard() {
        if (isSymbolMode) {
            isSymbolMode = false
            currentKeyboard = qwertyKeyboard
            keyboardView.keyboard = qwertyKeyboard
            keyboardView.invalidateAllKeys()
        }
    }

    private fun toggleKeyboard() {
        if (isSymbolMode) switchToQwertyKeyboard() else switchToSymbolKeyboard()
    }

    // ======================== 长按 Fn 效果 ========================

    private fun startLongPressDetection(key: Keyboard.Key) {
        cancelLongPress()
        currentLongPressKey = key
        longPressRunnable = Runnable {
            // 长按触发：输出 popupCharacters 中的符号
            val popup = key.popupCharacters
            if (!popup.isNullOrEmpty()) {
                val symbol = popup[0].toString()
                currentInputConnection?.commitText(symbol, 1)
                Log.d("Cesia", "Fn 长按输出: $symbol")
                // 震动反馈
                keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                longPressTriggered = true
            }
            currentLongPressKey = null
        }.also {
            longPressHandler.postDelayed(it, 400) // 400ms 长按阈值
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        currentLongPressKey = null
        longPressTriggered = false
    }

    // ======================== KeyboardView 回调 ========================

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        cancelLongPress()

        if (longPressTriggered) {
            longPressTriggered = false
            return
        }

        when (primaryCode) {
            KEYCODE_SWITCH_SYMBOL -> {
                toggleKeyboard()
            }
            -1 -> { // Shift
                isCapsLock = !isCapsLock
                qwertyKeyboard.isShifted = isCapsLock
                keyboardView.invalidateAllKeys()
            }
            -5 -> { // 删除
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            -200 -> { // 发送
                currentInputConnection?.apply {
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            Keyboard.KEYCODE_DELETE -> {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_SHIFT -> {
                isCapsLock = !isCapsLock
                qwertyKeyboard.isShifted = isCapsLock
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_DONE -> {
                currentInputConnection?.apply {
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            Keyboard.KEYCODE_MODE_CHANGE -> {
                toggleKeyboard()
            }
            else -> {
                // 普通字符键
                var char = primaryCode.toChar()
                if (isCapsLock && char.isLowerCase()) {
                    char = char.uppercaseChar()
                }
                currentInputConnection?.commitText(char.toString(), 1)
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        // 检测长按 Fn 效果
        if (primaryCode > 0 && !isSymbolMode) {
            val key = currentKeyboard?.keys?.find { it.codes?.contains(primaryCode) == true }
            if (key != null && !key.popupCharacters.isNullOrEmpty()) {
                startLongPressDetection(key)
            }
        }
    }

    override fun onRelease(primaryCode: Int) {
        cancelLongPress()
    }

    override fun onText(text: CharSequence?) {
        cancelLongPress()
        currentInputConnection?.commitText(text, 1)
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}

    // ======================== 生命周期 ========================

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput && isRecording) stopRecording()
    }

    override fun onDestroy() {
        cancelLongPress()
        typelessEngine?.destroy()
        typelessEngine = null
        super.onDestroy()
    }

    // ======================== UI 辅助 ========================

    private fun setStatusDot(state: String) {
        if (!::statusDot.isInitialized) return
        try {
            val drawableRes = when (state) {
                "recording" -> R.drawable.status_dot_recording
                "processing" -> R.drawable.status_dot_processing
                "error" -> R.drawable.status_dot_error
                else -> R.drawable.status_dot_idle
            }
            statusDot.setBackgroundResource(drawableRes)
        } catch (_: Exception) {}
    }

    private fun updateStatus(msg: String) {
        try { statusText.text = msg } catch (_: Exception) {}
    }
}
