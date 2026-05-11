package com.cesia.input

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.cesia.input.audio.AudioRecorder
import com.cesia.input.engine.TypelessEngine
import com.google.android.material.button.MaterialButton

/**
 * Cesia Typeless 输入法 —— 语音自动润色上屏
 *
 * 3 步完成输入: 点麦克风 → 说话 → 自动上屏
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

    // API URL
    private var apiUrl = "https://typeless-ai-service.vercel.app/api/polish"

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://typeless-ai-service.vercel.app/api/polish"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Cesia)
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
                runOnUiThread { updateStatus(msg) }
            }
            engine.initialize()
        }

        // 加载设置
        loadSettings()

        // 绑定按钮事件
        setupButtonListeners()

        updateStatus("就绪 - 点击麦克风开始")
        setStatusDot("idle")

        return view
    }

    private fun setupButtonListeners() {
        // 麦克风按钮 —— Typeless 核心触发
        micButton.setOnClickListener {
            toggleRecording()
        }

        // 长按麦克风 = 停止（安全机制）
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

        // 删除按钮
        btnDelete.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            ic.deleteSurroundingText(1, 0)
        }

        // 切换输入法按钮
        btnSwitchIme.setOnClickListener {
            switchToNextInputMethod()
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
        micButton.text = "⏺ 录音中..."
        setStatusDot("recording")
        updateStatus("🎤 正在录音，请说话...")

        typelessEngine?.startListening()
    }

    private fun stopRecording() {
        isRecording = false
        micButton.isActivated = false
        micButton.text = "🎤 说话"
        setStatusDot("idle")

        typelessEngine?.stopListening()
        updateStatus("⏹️ 录音结束，处理中...")
    }

    private fun setStatusDot(state: String) {
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
        val inputConnection = currentInputConnection ?: return

        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                val ic = currentInputConnection ?: return
                ic.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_SHIFT -> {
                keyboard.isShifted = !keyboard.isShifted
            }
            Keyboard.KEYCODE_MODE_CHANGE -> {
                // 可以切换到中文语音模式
                showSettings()
            }
            Keyboard.KEYCODE_DONE, Keyboard.KEYCODE_ENTER -> {
                val ic = currentInputConnection ?: return
                ic.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_ENTER
                    )
                )
                ic.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_ENTER
                    )
                )
            }
            // 中文切换键
            -1 -> {
                // 触发语音输入
                toggleRecording()
            }
            else -> {
                try {
                    val code = primaryCode.toChar()
                    currentInputConnection?.commitText(code.toString(), 1)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        // 按下时的视觉反馈
    }

    override fun onRelease(primaryCode: Int) {
        // 释放时的处理
    }

    override fun onText(text: CharSequence?) {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(text, 1)
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}