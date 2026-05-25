package com.cesia.input

import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.ScaleAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.cesia.input.engine.TypelessEngine
import com.cesia.input.engine.rime.RimeEngine
import com.cesia.input.stats.PolishStatsManager
import com.cesia.input.stats.MagicHistoryManager
import com.google.android.material.button.MaterialButton

/**
 * Cesia 输入法 — Rime 内核版
 *
 * 架构：
 * - 键盘 UI：标准 QWERTY 布局（qwerty.xml + symbols_cn.xml + symbols.xml）
 * - 输入引擎：Rime（librime JNI）处理拼音→汉字
 * - 底部功能栏：魔法修改、魔法书、语音、清空、发送
 * - 语音润色：TypelessEngine（OpenRouter API）
 */
class CesiaInputMethod : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    // ======================== 视图 ========================
    private lateinit var keyboardView: KeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolKeyboardEn: Keyboard
    private lateinit var symbolKeyboardCn: Keyboard
    private var currentKeyboard: Keyboard? = null

    private lateinit var micButton: MaterialButton
    private lateinit var micButtonContainer: LinearLayout
    private lateinit var btnMicAi: MaterialButton
    private lateinit var btnMicNoAi: MaterialButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnClipboard: MaterialButton
    private lateinit var btnMagic: MaterialButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var voiceWave: View

    // 候选词栏
    private lateinit var candidateBar: LinearLayout
    private lateinit var tvComposing: TextView
    private lateinit var tvCandidates: Array<TextView>
    private lateinit var btnCandidatePrev: ImageButton
    private lateinit var btnCandidateNext: ImageButton

    // ======================== 核心组件 ========================
    private var typelessEngine: TypelessEngine? = null
    private lateinit var statsManager: PolishStatsManager
    private lateinit var rimeEngine: RimeEngine

    // ======================== 状态 ========================
    private var isRecording = false
    private var isSymbolMode = false
    private var isCapsLock = false
    private var isProcessingResult = false
    private var isWaitingForChoice = false
    private var lastMicClickTime = 0L
    private var voiceStartTime = 0L
    private var pendingAiMode: Boolean? = null
    private var recognizedText: String = ""

    // 长按检测
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var currentLongPressKey: Keyboard.Key? = null
    private var longPressTriggered = false
    private var longPressConsumed = false
    private var backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null

    // 发送键长按检测
    private var sendKeyLongPressTriggered = false
    private var sendKeyHandler = Handler(Looper.getMainLooper())
    private var sendKeyRunnable: Runnable? = null

    // 魔法模式
    private var magicMode = false
    private var magicOriginalText = ""
    private var magicIsWaitingForVoice = false

    // 撤销历史
    private val undoHistory = mutableListOf<Pair<String, String>>()
    private val undoMaxSteps = 3

    // AI自动回复
    private var aiReplyStyle = "自然"
    private var isAiProcessing = false

    // 魔法修改历史
    private var magicHistoryManager: MagicHistoryManager? = null
    private var currentMagicPrompt: String? = null

    // 发送消息历史
    private val sentMessages = mutableListOf<String>()
    private val maxSentMessages = 10

    // 初始化标志
    private var isViewInitialized = false

    // 主题
    private var isDarkTheme = false
    private var apiUrl = "https://openrouter.ai/api/v1/chat/completions"

    companion object {
        const val PREF_API_URL = "api_url"
        const val PREF_MODEL_ID = "model_id"
        const val PREF_THEME_MODE = "theme_mode"
        const val PREF_AI_STYLE = "ai_reply_style"
        const val PREF_OPENROUTER_KEY = "openrouter_api_key"
        const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_MODEL_ID = "minimax/minimax-m2.5:free"
        const val KEYCODE_SWITCH_SYMBOL = -100
        const val KEYCODE_SWITCH_LANG = -101
        const val KEYCODE_BACK_KEY = -999
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
    }

    // ======================== 生命周期 ========================

    override fun onCreate() {
        val themeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt(PREF_THEME_MODE, THEME_LIGHT)
        isDarkTheme = themeMode == THEME_DARK
        setTheme(if (isDarkTheme) R.style.Theme_Cesia_Dark else R.style.Theme_Cesia)
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        try {
            return createInputViewSafe()
        } catch (e: Exception) {
            Log.e("Cesia", "onCreateInputView 严重崩溃", e)
            return android.widget.TextView(this).apply {
                text = "Cesia 加载失败\n${e.javaClass.simpleName}: ${e.message}\n请重启输入法"
                setTextColor(android.graphics.Color.RED)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
        }
    }

    private fun createInputViewSafe(): View {
        Log.d("Cesia", "createInputViewSafe: 开始加载布局")
        val inflater = LayoutInflater.from(this)
        Log.d("Cesia", "createInputViewSafe: inflater 获取成功")
        val view = inflater.inflate(R.layout.input_view, null)
        Log.d("Cesia", "createInputViewSafe: 布局加载成功")

        keyboardView = view.findViewById(R.id.keyboard_view)
        Log.d("Cesia", "createInputViewSafe: keyboardView 获取成功")
        micButton = view.findViewById(R.id.btn_mic)
        micButtonContainer = view.findViewById(R.id.mic_button_container)
        btnMicAi = view.findViewById(R.id.btn_mic_ai)
        btnMicNoAi = view.findViewById(R.id.btn_mic_noai)
        btnSettings = view.findViewById(R.id.btn_settings)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnClipboard = view.findViewById(R.id.btn_magic_book)
        btnMagic = view.findViewById(R.id.btn_magic)
        statusDot = view.findViewById(R.id.v_status_dot)
        statusText = view.findViewById(R.id.tv_status)
        voiceWave = view.findViewById(R.id.v_voice_wave)

        candidateBar = view.findViewById(R.id.candidate_bar)
        tvComposing = view.findViewById(R.id.tv_composing)
        tvCandidates = arrayOf(
            view.findViewById<TextView>(R.id.tv_candidate_1),
            view.findViewById<TextView>(R.id.tv_candidate_2),
            view.findViewById<TextView>(R.id.tv_candidate_3),
            view.findViewById<TextView>(R.id.tv_candidate_4),
            view.findViewById<TextView>(R.id.tv_candidate_5)
        )
        btnCandidatePrev = view.findViewById(R.id.btn_candidate_prev)
        btnCandidateNext = view.findViewById(R.id.btn_candidate_next)

        // 初始化键盘
        Log.d("Cesia", "createInputViewSafe: 开始初始化键盘")
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        Log.d("Cesia", "createInputViewSafe: qwerty 键盘加载成功")
        try {
            symbolKeyboardEn = Keyboard(this, R.xml.symbols)
            Log.d("Cesia", "createInputViewSafe: symbols 键盘加载成功")
        } catch (e: Exception) {
            Log.e("Cesia", "加载英文符号键盘失败", e)
            symbolKeyboardEn = qwertyKeyboard
        }
        try {
            symbolKeyboardCn = Keyboard(this, R.xml.symbols_cn)
            Log.d("Cesia", "createInputViewSafe: symbols_cn 键盘加载成功")
        } catch (e: Exception) {
            Log.e("Cesia", "加载中文符号键盘失败", e)
            symbolKeyboardCn = symbolKeyboardEn
        }
        currentKeyboard = qwertyKeyboard

        keyboardView.keyboard = currentKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.isPreviewEnabled = true
        Log.d("Cesia", "createInputViewSafe: 键盘设置完成")

        // 初始化引擎
        Log.d("Cesia", "createInputViewSafe: 开始初始化引擎")
        statsManager = PolishStatsManager(this)
        magicHistoryManager = MagicHistoryManager(this)
        currentMagicPrompt = magicHistoryManager?.getActiveInstruction()

        rimeEngine = RimeEngine(this)
        rimeEngine.initialize()
        Log.i("Cesia", "Rime 引擎初始化完成")

        typelessEngine = TypelessEngine(this, this).also { engine ->
            engine.onLogMessage = { msg ->
                Handler(Looper.getMainLooper()).post { updateStatus(msg) }
            }
            engine.onMagicResult = { recognizedText ->
                Handler(Looper.getMainLooper()).post {
                    handleMagicResult(recognizedText)
                }
            }
            engine.onPolishComplete = { inputText, outputText, _ ->
                val duration = if (voiceStartTime > 0) System.currentTimeMillis() - voiceStartTime else 0
                statsManager.addRecord(inputText, outputText, duration)
            }
            engine.onResultProcessing = {
                Handler(Looper.getMainLooper()).post {
                    isProcessingResult = true
                    setStatusDot("processing")
                }
            }
            engine.onResultCommitted = {
                Handler(Looper.getMainLooper()).post {
                    isProcessingResult = false
                    isRecording = false
                    micButton.isActivated = false
                    micButton.text = "🎤 说话"
                    stopVoiceWave()
                    micButton.visibility = View.VISIBLE
                    btnMicAi.visibility = View.GONE
                    btnMicNoAi.visibility = View.GONE
                    keyboardView.visibility = View.VISIBLE
                    setStatusDot("idle")
                    updateStatus("✅ 已完成")
                }
            }
            engine.onRecognitionComplete = { text ->
                Handler(Looper.getMainLooper()).post {
                    recognizedText = text
                    isRecording = false
                    stopVoiceWave()
                    setStatusDot("idle")

                    if (pendingAiMode == true) {
                        isWaitingForChoice = false
                        hideAiChoiceButtons()
                        if (text.isEmpty()) {
                            updateStatus("⚠️ 未识别到文字")
                            resetToIdle()
                        } else {
                            updateStatus("✨ 正在施展魔法...")
                            setStatusDot("processing")
                            isProcessingResult = true
                            polishRecognizedText(text)
                        }
                    } else if (pendingAiMode == false) {
                        isWaitingForChoice = false
                        hideAiChoiceButtons()
                        if (text.isNotEmpty()) {
                            currentInputConnection?.commitText(text, 1)
                        }
                        resetToIdle()
                    } else {
                        if (text.isEmpty()) {
                            updateStatus("⚠️ 未识别到文字，请重试")
                            resetToIdle()
                        } else {
                            isWaitingForChoice = true
                            updateStatus("📝 「$text」→ 选择 AI+ 润色 或 AI× 直接上屏")
                            micButton.visibility = View.GONE
                            btnMicAi.visibility = View.VISIBLE
                            btnMicNoAi.visibility = View.VISIBLE
                        }
                    }
                }
            }
            engine.initialize(getOpenRouterApiKey())
        }

        loadSettings()
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        typelessEngine?.updateModelId(prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID)
        aiReplyStyle = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getString(PREF_AI_STYLE, "自然") ?: "自然"

        setupButtonListeners()
        setupCandidateBar()
        applyKeyboardTheme()

        updateStatus("Cesia 已就绪")
        setStatusDot("idle")
        isViewInitialized = true

        return view
    }

    // ======================== 主题 ========================

    private fun applyKeyboardTheme() {
        if (isDarkTheme) {
            keyboardView.setBackgroundColor(0xFF0F0F23.toInt())
            (statusText.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
            statusText.setTextColor(0xFFE0E0E0.toInt())
            candidateBar.setBackgroundColor(0xFF16213E.toInt())
            tvComposing.setTextColor(0xFF4488FF.toInt())
            for (tv in tvCandidates) {
                tv.setTextColor(0xFFE0E0E0.toInt())
            }
            (btnClipboard.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
        } else {
            keyboardView.setBackgroundColor(0xFFE8E8E8.toInt())
            (statusText.parent as? View)?.setBackgroundColor(0xFFEEEEEE.toInt())
            statusText.setTextColor(0xFF555555.toInt())
            candidateBar.setBackgroundColor(0xFFF0F0F0.toInt())
            tvComposing.setTextColor(0xFF4488FF.toInt())
            for (tv in tvCandidates) {
                tv.setTextColor(0xFF333333.toInt())
            }
            (btnClipboard.parent as? View)?.setBackgroundColor(0xFFE0E0E0.toInt())
        }
    }

    // ======================== 候选栏 ========================

    private fun setupCandidateBar() {
        for (i in tvCandidates.indices) {
            val index = i
            tvCandidates[i].setOnClickListener {
                if (rimeEngine.hasCandidates) {
                    val selected = rimeEngine.selectCandidate(
                        rimeEngine.currentPage * 5 + index
                    )
                    if (selected.isNotEmpty()) {
                        currentInputConnection?.commitText(selected, 1)
                        updateCandidateBar()
                    }
                }
            }
        }
        btnCandidatePrev.setOnClickListener {
            if (rimeEngine.hasCandidates) {
                rimeEngine.prevPage()
                updateCandidateBar()
            }
        }
        btnCandidateNext.setOnClickListener {
            if (rimeEngine.hasCandidates) {
                rimeEngine.nextPage()
                updateCandidateBar()
            }
        }
    }

    private fun updateCandidateBar() {
        val composing = rimeEngine.isComposing
        val candidates = rimeEngine.candidates
        val pinyin = rimeEngine.composingText

        if (!composing && pinyin.isEmpty()) {
            candidateBar.visibility = View.GONE
            return
        }

        candidateBar.visibility = View.VISIBLE
        tvComposing.text = pinyin

        for (i in tvCandidates.indices) {
            if (i < candidates.size) {
                tvCandidates[i].text = candidates[i]
                tvCandidates[i].visibility = View.VISIBLE
            } else {
                tvCandidates[i].visibility = View.INVISIBLE
            }
        }

        btnCandidatePrev.isEnabled = rimeEngine.currentPage > 0
        btnCandidateNext.isEnabled = rimeEngine.currentPage < rimeEngine.pageCount - 1
    }

    // ======================== 按钮监听 ========================

    private fun setupButtonListeners() {
        micButton.setOnClickListener {
            if (!isRecording && !isWaitingForChoice) {
                startRecordingImmediately()
            } else if (isWaitingForChoice) {
                updateStatus("请点击 AI+ 或 AI× 选择处理方式")
            } else if (isRecording) {
                if (magicMode) {
                    typelessEngine?.stopListening()
                    setStatusDot("processing")
                    updateStatus("⏳ 正在识别指令...")
                } else {
                    stopRecording()
                }
            }
        }
        micButton.setOnLongClickListener {
            if (isRecording || isWaitingForChoice) {
                resetToIdle()
                true
            } else false
        }

        btnMicAi.setOnClickListener { onAiPlusSelected() }
        btnMicNoAi.setOnClickListener { onAiCrossSelected() }
        btnSettings.setOnClickListener { showSettings() }

        btnDelete.setOnClickListener {
            if (rimeEngine.isComposing) {
                rimeEngine.processKey("BackSpace")
                updateCandidateBar()
            } else {
                currentInputConnection?.deleteSurroundingText(Integer.MAX_VALUE, 0)
            }
        }
        btnDelete.setOnLongClickListener {
            if (rimeEngine.isComposing) {
                rimeEngine.processKey("BackSpace")
                updateCandidateBar()
            } else {
                currentInputConnection?.deleteSurroundingText(0, Integer.MAX_VALUE)
            }
            true
        }

        btnClipboard.setOnClickListener { executeMagicOrAiReply() }
        btnClipboard.setOnLongClickListener { showMagicHistoryPopup(); true }

        btnMagic.setOnClickListener { toggleMagicMode() }
        btnMagic.setOnLongClickListener { true }
    }

    // ======================== 魔法修改 ========================

    private fun toggleMagicMode() {
        if (!magicIsWaitingForVoice) {
            magicIsWaitingForVoice = true
            btnMagic.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
            btnMagic.setTextColor(0xFFFFFFFF.toInt())
            btnMagic.elevation = 6f
            updateStatus("✨ 点击✨按钮开始语音修改")
            btnMagic.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()
        } else {
            magicIsWaitingForVoice = false
            btnMagic.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE0E0E0.toInt())
            btnMagic.setTextColor(0xFF888888.toInt())
            btnMagic.elevation = 0f
            btnMagic.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            startMagicMode()
        }
    }

    private fun startMagicMode() {
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

        magicOriginalText = fullText
        magicMode = true
        typelessEngine?.magicMode = true

        updateStatus("🎤 请说出修改指令...")
        setStatusDot("recording")
        startVoiceWave()
        isRecording = true
        micButton.text = "🎤 说话"
        typelessEngine?.startListening(continuous = true)
    }

    private fun resetMagicHighlight() {
        magicIsWaitingForVoice = false
        try {
            btnMagic.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE0E0E0.toInt())
            btnMagic.setTextColor(0xFF888888.toInt())
            btnMagic.elevation = 0f
            btnMagic.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        } catch (_: Exception) {}
    }

    private fun handleMagicResult(recognizedText: String) {
        magicMode = false
        typelessEngine?.magicMode = false
        isRecording = false
        stopVoiceWave()
        setStatusDot("idle")

        val instruction = recognizedText.trim()
        if (instruction.isEmpty()) {
            updateStatus("⚠️ 未识别到指令")
            return
        }

        Log.d("CesiaMagic", "原文: $magicOriginalText")
        Log.d("CesiaMagic", "指令: $instruction")
        updateStatus("✨ 正在施展魔法...")

        val prompt = buildMagicPrompt(magicOriginalText, instruction)
        val polishService = typelessEngine?.getPolishService()

        Thread {
            try {
                val result = polishService?.polishWithPrompt(prompt)
                Handler(Looper.getMainLooper()).post {
                    if (result != null && result.isNotEmpty()) {
                        if (result == magicOriginalText) {
                            updateStatus("⚠️ 修改结果与原文相同，可能指令不够明确")
                        } else {
                            magicHistoryManager?.addRecord(instruction)
                            saveUndoHistory(magicOriginalText, instruction)
                            try {
                                val ic2 = currentInputConnection
                                ic2?.performContextMenuAction(android.R.id.selectAll)
                                ic2?.commitText(result, 1)
                                resetToIdle()
                            } catch (e2: Exception) {
                                Log.e("Cesia", "handleMagicResult replaceInputText 异常", e2)
                                updateStatus("❌ 上屏失败: ${e2.message}")
                            }
                        }
                    } else {
                        updateStatus("⚠️ API返回为空，请检查网络或稍后重试")
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

    // ======================== 魔法历史 & 菜单 ========================

    private fun executeMagicOrAiReply() {
        try {
            if (currentMagicPrompt != null) {
                executeSelectedMagic(currentMagicPrompt!!)
            } else {
                triggerAiReply()
            }
        } catch (e: Exception) {
            Log.e("Cesia", "executeMagicOrAiReply 异常", e)
            updateStatus("❌ 操作失败: ${e.message}")
        }
    }

    private fun executeSelectedMagic(instruction: String) {
        if (isAiProcessing) {
            updateStatus("⏳ AI正在处理中，请稍候...")
            return
        }
        val ic = currentInputConnection ?: run {
            updateStatus("❌ 无输入框连接")
            return
        }
        val textBefore = try { ic.getTextBeforeCursor(10000, 0)?.toString() ?: "" } catch (_: Exception) { "" }
        val textAfter = try { ic.getTextAfterCursor(10000, 0)?.toString() ?: "" } catch (_: Exception) { "" }
        val fullText = textBefore + textAfter

        if (fullText.isEmpty()) {
            updateStatus("⚠️ 输入框无文字，无法修改")
            return
        }

        isAiProcessing = true
        updateStatus("✨ 正在施展魔法...")
        setStatusDot("processing")
        val prompt = buildMagicPrompt(fullText, instruction)
        val polishService = typelessEngine?.getPolishService()

        Thread {
            try {
                val result = polishService?.polishWithPrompt(prompt)
                Handler(Looper.getMainLooper()).post {
                    isAiProcessing = false
                    if (result != null && result.isNotEmpty()) {
                        if (result == fullText) {
                            updateStatus("⚠️ 修改结果与原文相同，可能指令不够明确")
                        } else {
                            magicHistoryManager?.addRecord(instruction)
                            saveUndoHistory(fullText, instruction)
                            try {
                                val ic2 = currentInputConnection
                                ic2?.performContextMenuAction(android.R.id.selectAll)
                                ic2?.commitText(result, 1)
                                resetToIdle()
                            } catch (e2: Exception) {
                                Log.e("Cesia", "replaceInputText 异常", e2)
                                updateStatus("❌ 上屏失败: ${e2.message}")
                                return@post
                            }
                        }
                    } else {
                        updateStatus("⚠️ API返回为空，请检查网络或稍后重试")
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    isAiProcessing = false
                    updateStatus("❌ 修改失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun saveUndoHistory(originalText: String, instruction: String) {
        undoHistory.add(0, Pair(originalText, instruction))
        while (undoHistory.size > undoMaxSteps) {
            undoHistory.removeAt(undoHistory.size - 1)
        }
    }

    private fun performUndo() {
        if (undoHistory.isEmpty()) {
            updateStatus("↩️ 没有可撤销的记录")
            return
        }
        val (originalText, _) = undoHistory.removeAt(0)
        val ic = currentInputConnection ?: run {
            updateStatus("❌ 无输入框连接")
            return
        }
        try {
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText(originalText, 1)
            updateStatus("↩️ 已撤销到原文")
        } catch (e: Exception) {
            updateStatus("❌ 撤销失败: ${e.message}")
        }
    }

    private fun showMagicHistoryPopup() {
        val mgr = magicHistoryManager ?: return
        val records = mgr.getRecords()

        try {
            val inflater = android.view.LayoutInflater.from(this)
            val popupView = inflater.inflate(R.layout.popup_magic_menu, null)
            val gridView = popupView.findViewById<GridView>(R.id.gv_magic_items)

            val keyboardWidth = keyboardView.width
            val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

            val popup = PopupWindow(popupView, popupWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
            popup.isOutsideTouchable = true
            popup.elevation = 4f

            val items = mutableListOf<MagicHistoryManager.MagicRecord>()
            items.addAll(records)

            val btnPin = popupView.findViewById<TextView>(R.id.btn_pin_manage)
            val btnDelete = popupView.findViewById<TextView>(R.id.btn_delete_manage)
            val btnUndo = popupView.findViewById<TextView>(R.id.btn_undo_manage)

            gridView.adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(p: Int) = items[p]
                override fun getItemId(p: Int) = items[p].id
                override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val v = cv ?: inflater.inflate(R.layout.item_magic_grid, parent, false)
                    val record = items[p]
                    val tv = v.findViewById<TextView>(R.id.tv_magic_text)
                    val tvFull = v.findViewById<TextView>(R.id.tv_magic_full)

                    val prefix = if (record.isPinned) "📌 " else if (record.instruction == currentMagicPrompt) "✓ " else ""
                    tv.text = "${prefix}${record.instruction}"
                    tv.setTextColor(if (record.instruction == currentMagicPrompt) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                    tv.setTypeface(null, if (record.instruction == currentMagicPrompt) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

                    tvFull.text = record.instruction
                    tvFull.visibility = View.GONE

                    v.setOnLongClickListener {
                        tv.visibility = View.GONE
                        tvFull.visibility = View.VISIBLE
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                tvFull.visibility = View.GONE
                                tv.visibility = View.VISIBLE
                            } catch (_: Exception) {}
                        }, 3000)
                        true
                    }
                    return v
                }
            }

            gridView.setOnItemClickListener { _, _, position, _ ->
                val record = items[position]
                currentMagicPrompt = record.instruction
                updateStatus("✅ 已应用：${record.instruction.take(20)}…")
                popup.dismiss()
                executeSelectedMagic(record.instruction)
            }

            gridView.setOnItemLongClickListener { _, v, position, _ ->
                val record = items[position]
                val popupMenu = android.widget.PopupMenu(this, v)
                popupMenu.menu.add(0, 1, 0, if (record.isPinned) "取消置顶" else "📌 置顶")
                popupMenu.menu.add(0, 2, 1, "🗑️ 删除")
                popupMenu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            mgr.togglePin(record.id)
                            currentMagicPrompt = mgr.getActiveInstruction()
                            items.clear()
                            items.addAll(mgr.getRecords())
                            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                            updateStatus(if (!record.isPinned) "📌 已置顶" else "取消置顶")
                            true
                        }
                        2 -> {
                            mgr.removeRecord(record.id)
                            items.clear()
                            val updated = mgr.getRecords()
                            items.addAll(updated)
                            if (currentMagicPrompt != null && updated.none { it.instruction == currentMagicPrompt }) {
                                currentMagicPrompt = mgr.getActiveInstruction()
                            }
                            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                            if (items.isEmpty()) {
                                btnPin.visibility = View.GONE
                                btnDelete.visibility = View.GONE
                            }
                            updateStatus("🗑️ 已删除")
                            true
                        }
                        else -> false
                    }
                }
                popupMenu.show()
                true
            }

            btnPin.setOnClickListener {
                if (items.isEmpty()) return@setOnClickListener
                val popupMenu = android.widget.PopupMenu(this, btnPin)
                for (r in items) {
                    val title = "${if (r.isPinned) "📌 " else "○ "}${r.instruction.take(18)}"
                    popupMenu.menu.add(0, r.id.toInt(), 0, title)
                }
                popupMenu.setOnMenuItemClickListener { item ->
                    val record = items.find { it.id.toInt() == item.itemId }
                    if (record != null) {
                        mgr.togglePin(record.id)
                        currentMagicPrompt = mgr.getActiveInstruction()
                        items.clear()
                        items.addAll(mgr.getRecords())
                        (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                        updateStatus(if (!record.isPinned) "📌 已置顶" else "取消置顶")
                    }
                    true
                }
                popupMenu.show()
            }

            btnDelete.setOnClickListener {
                if (items.isEmpty()) return@setOnClickListener
                val popupMenu = android.widget.PopupMenu(this, btnDelete)
                for (r in items) {
                    popupMenu.menu.add(0, r.id.toInt(), 0, "🗑️ ${r.instruction.take(18)}")
                }
                popupMenu.menu.add(0, -1, items.size, "⚠️ 删除全部（${items.size}条）")
                popupMenu.setOnMenuItemClickListener { item ->
                    if (item.itemId == -1) {
                        mgr.clearAll()
                        items.clear()
                        currentMagicPrompt = null
                        (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                        btnPin.visibility = View.GONE
                        btnDelete.visibility = View.GONE
                        updateStatus("🗑️ 已删除全部记录")
                    } else {
                        mgr.removeRecord(item.itemId.toLong())
                        items.clear()
                        val updated = mgr.getRecords()
                        items.addAll(updated)
                        if (currentMagicPrompt != null && updated.none { it.instruction == currentMagicPrompt }) {
                            currentMagicPrompt = mgr.getActiveInstruction()
                        }
                        (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                    }
                    true
                }
                popupMenu.show()
            }

            btnUndo.setOnClickListener {
                popup.dismiss()
                performUndo()
            }

            if (items.isEmpty()) {
                btnPin.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnUndo.visibility = View.GONE
            }

            popup.showAtLocation(keyboardView, Gravity.TOP, 0, 0)
        } catch (e: Exception) {
            Log.e("Cesia", "showMagicHistoryPopup 异常", e)
            updateStatus("长按可管理魔法指令")
        }
    }

    // ======================== AI自动回复 ========================

    private fun showAiStylePicker() {
        val styles = listOf(
            Triple("自然", "🌿", "自然流畅的语气"),
            Triple("幽默", "😄", "幽默风趣的表达"),
            Triple("圆滑", "🎭", "圆滑得体的措辞"),
            Triple("官方", "📋", "官方正式的语气"),
            Triple("简洁", "✂️", "简洁明了不废话"),
            Triple("正式", "👔", "正式商务的风格"),
            Triple("亲切", "🤗", "亲切温暖的语气"),
            Triple("犀利", "🔥", "犀利直接的观点")
        )

        try {
            val inflater = android.view.LayoutInflater.from(this)
            val dialogView = inflater.inflate(R.layout.dialog_ai_style_picker, null)

            val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setView(dialogView)
                .setTitle("🎭 选择写作风格")
                .setNegativeButton("取消", null)
                .create()

            val container = dialogView.findViewById<LinearLayout>(R.id.style_container)
            for ((name, icon, desc) in styles) {
                val item = inflater.inflate(R.layout.item_ai_style, container, false)
                item.findViewById<TextView>(R.id.tv_style_icon).text = icon
                item.findViewById<TextView>(R.id.tv_style_name).text = name
                item.findViewById<TextView>(R.id.tv_style_desc).text = desc
                if (name == aiReplyStyle) {
                    item.setBackgroundColor(0xFFE0F7FA.toInt())
                }
                item.setOnClickListener {
                    aiReplyStyle = name
                    getSharedPreferences("cesia_settings", MODE_PRIVATE)
                        .edit().putString(PREF_AI_STYLE, aiReplyStyle).apply()
                    updateStatus("✅ 已切换为「$aiReplyStyle」风格")
                    dialog.dismiss()
                }
                container.addView(item)
            }
            dialog.show()
        } catch (e: Exception) {
            val styleNames = styles.map { it.first }
            val currentIdx = styleNames.indexOf(aiReplyStyle)
            aiReplyStyle = styleNames.getOrElse((currentIdx + 1) % styleNames.size) { "自然" }
            getSharedPreferences("cesia_settings", MODE_PRIVATE)
                .edit().putString(PREF_AI_STYLE, aiReplyStyle).apply()
            updateStatus("✅ 已切换为「$aiReplyStyle」风格")
        }
    }

    private fun triggerAiReply() {
        if (isAiProcessing) {
            updateStatus("✨ 正在施展魔法...")
            return
        }
        val ic = currentInputConnection ?: run {
            updateStatus("❌ 无输入框连接")
            return
        }
        val textBefore = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
        val inputText = textBefore + textAfter

        if (inputText.isNotEmpty()) {
            try {
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.deleteSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE)
            } catch (e: Exception) {
                try {
                    ic.deleteSurroundingText(textBefore.length, textAfter.length)
                } catch (e2: Exception) {
                    ic.commitText("", 1)
                }
            }
        }

        if (inputText.isEmpty()) {
            val editorInfo = currentInputEditorInfo
            val appName = editorInfo?.packageName?.let { pkg ->
                when {
                    pkg.contains("wechat") -> "微信"
                    pkg.contains("qq") -> "QQ"
                    pkg.contains("whatsapp") -> "WhatsApp"
                    pkg.contains("telegram") -> "Telegram"
                    pkg.contains("line") -> "LINE"
                    else -> null
                }
            }
            val context = if (appName != null) "【当前应用：$appName】\n输入框为空，请根据应用类型生成一条合适的开场白或问候语。"
                       else "输入框为空，请生成一条通用的问候或开场白。"
            generateAiReply(context, ic)
        } else {
            val context = "【原文】\n$inputText\n\n请根据以上内容的语气和主题，生成一条合适的回复。"
            generateAiReply(context, ic)
        }
    }

    private fun generateAiReply(context: String, ic: android.view.inputmethod.InputConnection) {
        isAiProcessing = true
        updateStatus("✨ 正在施展魔法...")
        setStatusDot("processing")
        val prompt = buildAiReplyPrompt(context, aiReplyStyle)
        executeAiPrompt(prompt, ic)
    }

    private fun executeAiPrompt(prompt: String, ic: android.view.inputmethod.InputConnection) {
        val polishService = typelessEngine?.getPolishService()
        Thread {
            try {
                val result = polishService?.polishWithPrompt(prompt)
                Handler(Looper.getMainLooper()).post {
                    isAiProcessing = false
                    setStatusDot("idle")
                    if (result != null && result.isNotEmpty()) {
                        ic.commitText(result, 1)
                        updateStatus("✅ AI已生成建议内容（$aiReplyStyle 风格）")
                    } else {
                        updateStatus("⚠️ AI未生成有效内容，请重试")
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    isAiProcessing = false
                    setStatusDot("idle")
                    updateStatus("❌ AI生成失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun buildAiReplyPrompt(context: String, style: String): String {
        val styleDesc = when (style) {
            "幽默" -> "用幽默风趣的方式回复，适当使用俏皮话和轻松的语气"
            "圆滑" -> "用圆滑得体的方式回复，措辞委婉，不得罪人"
            "官方" -> "用官方正式的语气回复，措辞严谨规范"
            "简洁" -> "用简洁明了的方式回复，言简意赅，不废话"
            "正式" -> "用正式商务的语气回复，专业得体"
            "亲切" -> "用亲切温暖的方式回复，语气温和友好"
            "犀利" -> "用犀利直接的方式回复，观点鲜明，一针见血"
            else -> "用自然流畅的方式回复，语气自然"
        }
        return "你是一个智能回复助手。请根据以下聊天上下文，生成一条合适的回复。\n\n" +
                "要求：$styleDesc\n" +
                "只输出回复内容本身，不要解释。\n\n" +
                "$context\n" +
                "请生成合适的回复："
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
            val apiKey = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
            typelessEngine?.getPolishService()?.updateApiKey(apiKey)
            val modelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
            typelessEngine?.updateModelId(modelId)
        } catch (_: Exception) {
            apiUrl = DEFAULT_API_URL
        }
    }

    private fun getOpenRouterApiKey(): String {
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        return prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
    }

    // ======================== 录音 ========================

    private fun startRecordingImmediately() {
        isRecording = true
        isWaitingForChoice = false
        recognizedText = ""
        pendingAiMode = null
        setStatusDot("recording")
        startVoiceWave()
        keyboardView.visibility = View.GONE
        candidateBar.visibility = View.GONE
        voiceStartTime = System.currentTimeMillis()
        updateStatus("🎤 正在收听，请说话...")
        typelessEngine?.startListening(continuous = true)
        showAiChoiceButtons()
    }

    private fun showAiChoiceButtons() {
        animateMicSplit()
    }

    private fun hideAiChoiceButtons() {
        animateMicMerge()
    }

    private fun onAiPlusSelected() {
        if (isWaitingForChoice && recognizedText.isNotEmpty()) {
            isWaitingForChoice = false
            pendingAiMode = true
            hideAiChoiceButtons()
            updateStatus("✨ 正在施展魔法...")
            setStatusDot("processing")
            isProcessingResult = true
            polishRecognizedText(recognizedText)
        } else if (isRecording) {
            stopRecordingAndWait()
            pendingAiMode = true
            updateStatus("⏳ 正在识别，识别后自动施展魔法")
        }
    }

    private fun onAiCrossSelected() {
        if (isWaitingForChoice && recognizedText.isNotEmpty()) {
            isWaitingForChoice = false
            pendingAiMode = false
            hideAiChoiceButtons()
            currentInputConnection?.commitText(recognizedText, 1)
            addSentMessage(recognizedText)
            resetToIdle()
        } else if (isRecording) {
            stopRecordingAndWait()
            pendingAiMode = false
            updateStatus("⏳ 正在识别，识别后自动上屏")
        }
    }

    private fun stopRecordingAndWait() {
        isRecording = false
        stopVoiceWave()
        typelessEngine?.stopListening()
        setStatusDot("processing")
    }

    private fun polishRecognizedText(text: String) {
        isProcessingResult = true
        typelessEngine?.polishTextAsync(text) { finalText ->
            isProcessingResult = false
            currentInputConnection?.commitText(finalText, 1)
            val duration = if (voiceStartTime > 0) System.currentTimeMillis() - voiceStartTime else 0
            statsManager.addRecord(text, finalText, duration)
            resetToIdle()
        }
    }

    private fun resetToIdle() {
        isRecording = false
        isWaitingForChoice = false
        isProcessingResult = false
        recognizedText = ""
        pendingAiMode = null
        stopVoiceWave()
        resetMagicHighlight()
        setStatusDot("idle")
        hideAiChoiceButtons()
        keyboardView.visibility = View.VISIBLE
        updateStatus("Cesia 已就绪")
    }

    private fun stopRecording() {
        stopRecordingAndWait()
    }

    private fun addSentMessage(text: String) {
        if (text.isBlank()) return
        sentMessages.add(text)
        if (sentMessages.size > maxSentMessages) {
            sentMessages.removeAt(0)
        }
    }

    // ======================== 声波动画 ========================

    private var waveAnim: AnimationDrawable? = null

    private fun startVoiceWave() {
        try {
            voiceWave.visibility = View.VISIBLE
            val bg = voiceWave.background
            if (bg is AnimationDrawable) {
                waveAnim = bg
                bg.start()
            }
            val pulse = ScaleAnimation(
                1.0f, 1.3f, 1.0f, 1.3f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 600
                repeatMode = ScaleAnimation.REVERSE
                repeatCount = ScaleAnimation.INFINITE
            }
            voiceWave.startAnimation(pulse)
        } catch (_: Exception) {}
    }

    private fun stopVoiceWave() {
        try {
            waveAnim?.stop()
            waveAnim = null
            voiceWave.clearAnimation()
            voiceWave.visibility = View.GONE
        } catch (_: Exception) {}
    }

    // ======================== 麦克风按钮动画 ========================

    private fun animateMicSplit() {
        try {
            micButton.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(200).withEndAction {
                micButton.visibility = View.GONE
                voiceWave.visibility = View.VISIBLE
                startVoiceWave()
                btnMicAi.visibility = View.VISIBLE
                btnMicAi.translationX = -80f
                btnMicAi.animate().translationX(0f).alpha(1f).setDuration(250).start()
                btnMicNoAi.visibility = View.VISIBLE
                btnMicNoAi.translationX = 80f
                btnMicNoAi.animate().translationX(0f).alpha(1f).setDuration(250).start()
            }.start()
        } catch (_: Exception) {}
    }

    private fun animateMicMerge() {
        try {
            stopVoiceWave()
            voiceWave.visibility = View.GONE
            btnMicAi.animate().translationX(-80f).alpha(0f).setDuration(200).withEndAction {
                btnMicAi.visibility = View.GONE
            }.start()
            btnMicNoAi.animate().translationX(80f).alpha(0f).setDuration(200).withEndAction {
                btnMicNoAi.visibility = View.GONE
                micButton.visibility = View.VISIBLE
                micButton.scaleX = 0.5f
                micButton.scaleY = 0.5f
                micButton.alpha = 0f
                micButton.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250).start()
            }.start()
        } catch (_: Exception) {}
    }

    // ======================== 键盘切换 ========================

    private fun switchToSymbolKeyboard() {
        if (!isSymbolMode) {
            isSymbolMode = true
            currentKeyboard = symbolKeyboardCn
            keyboardView.keyboard = symbolKeyboardCn
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

    // ======================== 长按检测 ========================

    private fun startLongPressDetection(key: Keyboard.Key) {
        cancelLongPress()
        currentLongPressKey = key
        longPressRunnable = Runnable {
            val popup = key.popupCharacters
            if (!popup.isNullOrEmpty()) {
                val symbol = popup[0].toString()
                currentInputConnection?.commitText(symbol, 1)
                Log.d("Cesia", "Fn 长按输出: $symbol")
                keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                longPressTriggered = true
                longPressConsumed = false
            }
            currentLongPressKey = null
        }.also {
            longPressHandler.postDelayed(it, 400)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        currentLongPressKey = null
    }

    private fun startSendKeyLongPress() {
        cancelSendKeyLongPress()
        sendKeyRunnable = Runnable {
            sendKeyLongPressTriggered = true
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }.also {
            sendKeyHandler.postDelayed(it, 500)
        }
    }

    private fun cancelSendKeyLongPress() {
        sendKeyRunnable?.let { sendKeyHandler.removeCallbacks(it) }
        sendKeyRunnable = null
    }

    // ======================== KeyboardView 回调 ========================

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val wasLongPressed = longPressTriggered && !longPressConsumed
        if (wasLongPressed) {
            longPressConsumed = true
            cancelLongPress()
            return
        }
        cancelLongPress()

        when (primaryCode) {
            // 字母键 a-z → Rime 处理
            in 97..122 -> {
                rimeEngine.processKey(primaryCode.toChar())
                updateCandidateBar()
            }

            // 数字键 0-9
            in 48..57 -> {
                if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                    val index = primaryCode - 48
                    if (index == 0) index + 1
                    if (index <= rimeEngine.candidates.size) {
                        val selected = rimeEngine.selectCandidate(index - 1)
                        currentInputConnection?.commitText(selected, 1)
                        rimeEngine.clear()
                        updateCandidateBar()
                    }
                } else {
                    currentInputConnection?.commitText(primaryCode.toChar().toString(), 1)
                }
            }

            // 空格键
            32 -> {
                if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                    val selected = rimeEngine.selectCandidate(0)
                    currentInputConnection?.commitText(selected, 1)
                    rimeEngine.clear()
                    updateCandidateBar()
                } else if (rimeEngine.isComposing) {
                    currentInputConnection?.commitText(rimeEngine.composingText, 1)
                    rimeEngine.clear()
                    updateCandidateBar()
                } else {
                    currentInputConnection?.commitText(" ", 1)
                }
            }

            // BackSpace
            -5, Keyboard.KEYCODE_DELETE -> {
                if (rimeEngine.isComposing) {
                    rimeEngine.processKey("BackSpace")
                    updateCandidateBar()
                } else {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
            }

            // 回车键
            10, Keyboard.KEYCODE_DONE -> {
                if (rimeEngine.isComposing) {
                    val text = if (rimeEngine.hasCandidates) {
                        rimeEngine.selectCandidate(0)
                    } else {
                        rimeEngine.composingText
                    }
                    currentInputConnection?.commitText(text, 1)
                    rimeEngine.clear()
                    updateCandidateBar()
                } else {
                    currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }

            // Shift
            -1 -> {
                isCapsLock = !isCapsLock
                qwertyKeyboard.isShifted = isCapsLock
                keyboardView.invalidateAllKeys()
            }

            // 符号切换 (?123/符/ABC)
            KEYCODE_SWITCH_SYMBOL -> toggleKeyboard()

            // 中英文切换 (🌐)
            KEYCODE_SWITCH_LANG -> toggleKeyboard()

            // 返回键
            KEYCODE_BACK_KEY -> switchToQwertyKeyboard()

            // 发送键（纸飞机）
            -200 -> {
                if (sendKeyLongPressTriggered) {
                    sendKeyLongPressTriggered = false
                    return
                }
                if (rimeEngine.isComposing) {
                    val text = if (rimeEngine.hasCandidates) rimeEngine.selectCandidate(0) else rimeEngine.composingText
                    currentInputConnection?.commitText(text, 1)
                    rimeEngine.clear()
                    updateCandidateBar()
                }
                val ic = currentInputConnection
                val editorInfo = currentInputEditorInfo
                val imeOptions = editorInfo?.imeOptions ?: 0
                val action = imeOptions and EditorInfo.IME_MASK_ACTION
                val hasSendAction = action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE
                if (hasSendAction) ic?.performEditorAction(action)
                else {
                    ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }

            // 其他按键（标点符号等）
            else -> {
                val c = primaryCode.toChar()
                if (rimeEngine.isComposing) {
                    val text = if (rimeEngine.hasCandidates) rimeEngine.selectCandidate(0) else rimeEngine.composingText
                    currentInputConnection?.commitText(text, 1)
                    rimeEngine.clear()
                    updateCandidateBar()
                }
                currentInputConnection?.commitText(c.toString(), 1)
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        if (primaryCode > 0 && !isSymbolMode) {
            val key = currentKeyboard?.keys?.find { it.codes?.contains(primaryCode) == true }
            if (key != null && !key.popupCharacters.isNullOrEmpty()) {
                startLongPressDetection(key)
            }
        }
        if (primaryCode == -5 || primaryCode == Keyboard.KEYCODE_DELETE) {
            backspaceRunnable = object : Runnable {
                override fun run() {
                    rimeEngine.processKey("BackSpace")
                    updateCandidateBar()
                    backspaceHandler.postDelayed(this, 80)
                }
            }
            backspaceHandler.postDelayed(backspaceRunnable!!, 400)
        }
        if (primaryCode == -200) {
            startSendKeyLongPress()
        }
    }

    override fun onRelease(primaryCode: Int) {
        cancelLongPress()
        cancelSendKeyLongPress()
        backspaceRunnable?.let { backspaceHandler.removeCallbacks(it) }
        backspaceRunnable = null
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
        try {
            if (!isViewInitialized) return
            loadSettings()
            val themeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
                .getInt(PREF_THEME_MODE, THEME_LIGHT)
            isDarkTheme = themeMode == THEME_DARK
            applyKeyboardTheme()
            aiReplyStyle = getSharedPreferences("cesia_settings", MODE_PRIVATE)
                .getString(PREF_AI_STYLE, "自然") ?: "自然"
        } catch (e: Exception) {
            Log.e("Cesia", "onStartInputView 异常(已忽略)", e)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput && isRecording) stopRecording()
    }

    override fun onDestroy() {
        cancelLongPress()
        typelessEngine?.destroy()
        typelessEngine = null
        rimeEngine?.shutdown()
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

    private var statusLines = mutableListOf<String>()

    private fun updateStatus(msg: String) {
        try {
            if (isRecording) {
                if (msg.startsWith("🎤") || msg.startsWith("⏳") || msg.startsWith("🔄") || msg.startsWith("✅")) {
                    if (statusLines.isNotEmpty() && !statusLines.last().startsWith("📝")) {
                        statusLines[statusLines.size - 1] = msg
                    } else {
                        statusLines.add(msg)
                    }
                } else if (msg.startsWith("📝") || msg.startsWith("🎤")) {
                    statusLines.add(msg)
                } else {
                    if (statusLines.isNotEmpty()) {
                        statusLines[statusLines.size - 1] = msg
                    } else {
                        statusLines.add(msg)
                    }
                }
                while (statusLines.size > 20) {
                    statusLines.removeAt(0)
                }
                statusText.text = statusLines.joinToString("\n")
            } else {
                statusLines.clear()
                statusLines.add(msg)
                statusText.text = msg
            }
        } catch (_: Exception) {}
    }
}
