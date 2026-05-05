package com.cesia.input

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.view.View
import android.widget.Toast

class CesiaInputMethod: InputMethodService(), KeyboardView.OnKeyboardActionListener {
    
    private lateinit var keyboardView: KeyboardView
    private lateinit var keyboard: Keyboard
    
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)
        
        keyboardView = view.findViewById(R.id.keyboard_view)
        keyboard = Keyboard(this, R.xml.qwerty)
        keyboardView.keyboard = keyboard
        keyboardView.setOnKeyboardActionListener(this)
        
        // 麦克风按钮（暂时不实现功能）
        val micButton = view.findViewById<View>(R.id.btn_mic)
        micButton?.setOnClickListener {
            Toast.makeText(this, "语音功能开发中...", Toast.LENGTH_SHORT).show()
        }
        
        return view
    }
    
    // Keyboard回调
    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val inputConnection = currentInputConnection ?: return
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                inputConnection.deleteSurroundingText(1, 0)
            }
            Keyboard.KEYCODE_ENTER -> {
                inputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                inputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
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
