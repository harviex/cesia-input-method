package com.cesia.input

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.cesia.input.engine.TypelessEngine

/**
 * Cesia 输入法 —— 语音自动润色上屏
 * 点麦克风 → 说话 → 再点停止 → API 润色 → 自动上屏
 */
class CesiaInputMethod : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    // 视图
    private lateinit var keyboardView: KeyboardView
    private lateinit var keyboard: Keyboard
    private lateinit var micButton: com.google.android.material.button.MaterialButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnSwitchIme: ImageButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    // 核心组件
    private var typelessEngine: TypelessEngine? = null

    // 录音状态
    private var isRecording = false

    // 设置
    private var apiUrl = "https://typeless-ai-service.vercel.app/api/polish"

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://typeless-ai-service.vercel.app/api/polish"
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
        statusDot = view.findViewById(R.id.v_status_dot)
        statusText = view.findViewById(R.id.tv_status)

        // 初始化键盘
        keyboard = Keyboard(this, R.xml.qwerty)
        keyboardView.keyboard = keyboard
        keyboardView.setOnKeyboardActionListener(this)

        // 初始化引擎
        typelessEngine = TypelessEngine(this, this).also { engine ->
            engine.onLogMessage = { msg ->
                Handler(Looper.getMainLooper()).post { updateStatus(msg) }
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

        // 删除按钮 = 清空全部
        btnDelete.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(Integer.MAX_VALUE, 0)
        }

        btnSwitchIme.setOnClickListener { switchToNextInputMethod() }
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput && isRecording) stopRecording()
    }

    override fun onDestroy() {
        typelessEngine?.destroy()
        typelessEngine = null
        super.onDestroy()
    }

    // ========================
    // 键盘回调
    // ========================

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        when (primaryCode) {
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
                keyboard.isShifted = !keyboard.isShifted
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_MODE_CHANGE -> showSettings()
            Keyboard.KEYCODE_DONE -> {
                currentInputConnection?.apply {
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            else -> {
                try {
                    var char = primaryCode.toChar()
                    if (keyboard.isShifted && char.isLowerCase()) {
                        char = char.uppercaseChar()
                        keyboard.isShifted = false
                        keyboardView.invalidateAllKeys()
                    }
                    currentInputConnection?.commitText(char.toString(), 1)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) { currentInputConnection?.commitText(text, 1) }
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
