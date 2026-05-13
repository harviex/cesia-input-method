package com.cesia.input

import android.content.Intent
import android.content.SharedPreferences
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
import com.cesia.input.audio.AudioRecorder
import com.cesia.input.engine.TypelessEngine
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Cesia Typeless 输入法 —— 语音自动润色上屏
 *
 * 🔥 语音激活模式: 说 "Hey Typeless" → 说话 → 说 "Typeless Over" → 自动润色上屏
 * 🔘 手动按钮模式: 点麦克风按钮 → 说话 → 静音自动结束 → 自动润色上屏
 *
 * 无需"选中文字"等多余步骤，commitText 直接上屏
 */
class CesiaInputMethod : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    // 视图
    private lateinit var keyboardView: KeyboardView
    private lateinit var keyboard: Keyboard
    private lateinit var micButton: MaterialButton
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
        const val PREF_VOICE_ACTIVATION = "voice_activation"
        const val PREF_WAKE_WORD = "wake_word"
        const val PREF_END_WORD = "end_word"
        const val DEFAULT_API_URL = "https://typeless-ai-service.vercel.app/api/polish"
        const val DEFAULT_WAKE_WORD = "Hey Typeless"
        const val DEFAULT_END_WORD = "Typeless Over"
    }

    override fun onCreate() {
        setTheme(R.style.Theme_Cesia)
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)

        // 绑定视图
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

        // 初始化 Typeless 引擎
        typelessEngine = TypelessEngine(this, this).also { engine ->
            engine.onLogMessage = { msg ->
                Handler(Looper.getMainLooper()).post { updateStatus(msg) }
            }
            engine.initialize()
        }

        // 加载设置
        loadSettings()

        // 绑定按钮事件
        setupButtonListeners()

        updateStatus("Cesia 已就绪")
        setStatusDot("idle")

        return view
    }

    private fun setupButtonListeners() {
        // 麦克风按钮 —— 手动模式触发 / 语音激活下停止录音
        micButton.setOnClickListener {
            if (isVoiceActivationEnabled()) {
                // 语音激活模式下，点击麦克风 = 停止录音
                stopRecording()
            } else {
                toggleRecording()
            }
        }

        // 长按麦克风 = 强制停止（安全机制）
        micButton.setOnLongClickListener {
            if (isRecording) {
                stopRecording()
                true
            } else {
                false
            }
        }

        // 设置按钮
        btnSettings.setOnClickListener {
            showSettings()
        }

        // 删除按钮 —— 清空全部文本
        btnDelete.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            ic.deleteSurroundingText(Integer.MAX_VALUE, 0)
        }

        // 切换输入法按钮
        btnSwitchIme.setOnClickListener {
            switchToNextInputMethod()
        }
    }

    /**
     * 切换到下一个输入法
     */
    private fun switchToNextInputMethod() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                imm.switchToNextInputMethod(null, false)
            }
        }
    }

    private fun showSettings() {
        val settingsIntent = Intent(this, SettingsActivity::class.java)
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(settingsIntent)
    }

    private fun loadSettings() {
        try {
            val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            apiUrl = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
            typelessEngine?.updateApiUrl(apiUrl)

            // 语音激活
            val voiceActivation = prefs.getBoolean(PREF_VOICE_ACTIVATION, false)
            typelessEngine?.voiceActivationEnabled = voiceActivation

            // 唤醒词 / 结束词
            val wakeWord = prefs.getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
            val endWord = prefs.getString(PREF_END_WORD, DEFAULT_END_WORD) ?: DEFAULT_END_WORD
            typelessEngine?.setWakeWord(wakeWord)
            typelessEngine?.setEndWord(endWord)
        } catch (e: Exception) {
            apiUrl = DEFAULT_API_URL
        }
    }

    // ========================
    // 录音控制
    // ========================

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        micButton.isActivated = true
        micButton.text = "⏹️ 再次点击完成"
        setStatusDot("recording")
        updateStatus("🎤 正在收听，请说话...")

        // 使用连续模式：积累所有语音，停止时一次性发送
        typelessEngine?.startListening(continuous = true)
    }

    private fun stopRecording() {
        isRecording = false
        micButton.isActivated = false
        micButton.text = "🎤 点击开始说话"
        setStatusDot("idle")

        typelessEngine?.stopListening()
        updateStatus("正在识别...")
    }

    private fun isVoiceActivationEnabled(): Boolean {
        return try {
            val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            prefs.getBoolean(PREF_VOICE_ACTIVATION, false)
        } catch (_: Exception) {
            false
        }
    }

    private fun setStatusDot(state: String) {
        if (!::statusDot.isInitialized) return
        try {
            val drawableRes = when (state) {
                "monitoring" -> android.R.drawable.presence_online
                "recording" -> R.drawable.status_dot_recording
                "processing" -> R.drawable.status_dot_processing
                "error" -> R.drawable.status_dot_error
                else -> R.drawable.status_dot_idle
            }
            statusDot.setBackgroundResource(drawableRes)
        } catch (_: Exception) {}
    }

    private fun updateStatus(msg: String) {
        try {
            statusText.text = msg
        } catch (_: Exception) {}
    }

    // ========================
    // 生命周期
    // ========================

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput && isRecording) {
            stopRecording()
        }
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
            // 纸飞机发送 —— 发送回车键
            -200 -> {
                val ic = currentInputConnection ?: return
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            Keyboard.KEYCODE_DELETE -> {
                val ic = currentInputConnection ?: return
                ic.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_SHIFT -> {
                keyboard.isShifted = !keyboard.isShifted
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_MODE_CHANGE -> {
                showSettings()
            }
            Keyboard.KEYCODE_DONE -> {
                val ic = currentInputConnection ?: return
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            else -> {
                try {
                    var char = primaryCode.toChar()
                    if (keyboard.isShifted && char.isLowerCase()) {
                        char = char.uppercaseChar()
                        // Shift 自动关闭（标准键盘行为）
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
    override fun onText(text: CharSequence?) {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(text, 1)
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
