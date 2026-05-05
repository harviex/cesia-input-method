package com.cesia.input

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.view.View
import android.widget.Button
import com.cesia.input.wenet.WenetManager
import com.cesia.input.api.ApiClient

class CesiaInputMethod: InputMethodService(), KeyboardView.OnKeyboardActionListener {
    
    private lateinit var keyboardView: KeyboardView
    private lateinit var keyboard: Keyboard
    private lateinit var wenetManager: WenetManager
    private lateinit var apiClient: ApiClient
    private var isRecording = false
    
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)
        
        keyboardView = view.findViewById(R.id.keyboard_view)
        keyboard = Keyboard(this, R.xml.qwerty)
        keyboardView.keyboard = keyboard
        keyboardView.setOnKeyboardActionListener(this)
        
        // 初始化WeNet
        wenetManager = WenetManager(this)
        
        // 初始化API客户端
        apiClient = ApiClient(getSharedPreferences("cesia", MODE_PRIVATE)
            .getString("api_url", "https://typeless-ai-service.vercel.app/api/polish") ?: "")
        
        // 麦克风按钮
        val micButton = view.findViewById<Button>(R.id.btn_mic)
        micButton.setOnClickListener {
            if (isRecording) {
                stopRecordingAndPolish()
            } else {
                startRecording()
            }
        }
        
        // 设置按钮
        val settingsButton = view.findViewById<Button>(R.id.btn_settings)
        settingsButton.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        
        return view
    }
    
    private fun startRecording() {
        isRecording = true
        wenetManager.startRecording()
        updateMicButton(true)
    }
    
    private fun stopRecordingAndPolish() {
        isRecording = false
        updateMicButton(false)
        
        // 1. WeNet识别
        val recognizedText = wenetManager.stopRecording()
        
        if (recognizedText.isNullOrEmpty()) return
        
        // 2. 调用API润色
        apiClient.polish(recognizedText) { polishedText ->
            // 3. 自动上屏 ✅
            currentInputConnection?.commitText(polishedText, 1)
        }
    }
    
    private fun updateMicButton(recording: Boolean) {
        val micButton = keyboardView.findViewById<Button>(R.id.btn_mic)
        micButton?.text = if (recording) "⏹ 停止" else "🎤 说话"
    }
    
    // Keyboard回调
    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val inputConnection = currentInputConnection ?: return
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                inputConnection.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_DONE -> {
                inputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            }
            else -> {
                val char = primaryCode.toChar()
                inputConnection.commitText(char.toString(), 1)
            }
        }
    }
    
    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
