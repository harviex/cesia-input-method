package com.cesia.input

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.view.View
import android.widget.Button
import android.widget.Toast
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
        
        // 初始化WeNet（会自动检查模型）
        wenetManager = WenetManager(this)
        
        // 检查模型是否已下载
        if (!wenetManager.isModelDownloaded()) {
            Toast.makeText(this, "首次使用将下载WeNet模型(约90MB)...", Toast.LENGTH_LONG).show()
        }
        
        // 初始化API客户端
        apiClient = ApiClient(this)
        apiClient.setApiUrl(
            getSharedPreferences("cesia", MODE_PRIVATE)
                .getString("api_url", "https://typeless-ai-service.vercel.app/api/polish") ?: ""
        )
        
        // 麦克风按钮
        val micButton = view.findViewById<Button>(R.id.btn_mic)
        micButton.setOnClickListener {
            if (isRecording) {
                stopRecordingAndPolish()
            } else {
                // 检查模型
                if (!wenetManager.isModelDownloaded()) {
                    Toast.makeText(this, "正在下载模型，请稍候...", Toast.LENGTH_SHORT).show()
                    wenetManager.downloadModel(object : WenetManager.DownloadCallback {
                        override fun onProgress(progress: Int) {
                            // 可以更新通知栏进度
                        }
                        override fun onComplete() {
                            Toast.makeText(this@CesiaInputMethod, "模型下载完成！", Toast.LENGTH_SHORT).show()
                        }
                        override fun onError(error: String) {
                            Toast.makeText(this@CesiaInputMethod, "下载失败: $error", Toast.LENGTH_SHORT).show()
                        }
                    })
                    return@setOnClickListener
                }
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
        
        if (recognizedText.isNullOrEmpty()) {
            Toast.makeText(this, "识别失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 2. 调用API润色
        apiClient.polish(recognizedText) { polishedText ->
            // 3. 自动上屏 ✅
            currentInputConnection?.commitText(polishedText, 1)
            Toast.makeText(this, "润色完成", Toast.LENGTH_SHORT).show()
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
