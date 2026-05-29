package com.cesia.input

import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import com.cesia.input.CesiaKeyboardView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.ScaleAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.text.TextUtils
import android.graphics.Typeface
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var keyboardView: CesiaKeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolKeyboardEn: Keyboard
    private lateinit var symbolKeyboardCn: Keyboard
    private lateinit var numberKeyboard: Keyboard
    private var currentKeyboard: Keyboard? = null

    private lateinit var micButton: MaterialButton
    private lateinit var micButtonContainer: LinearLayout
    private lateinit var btnMicAi: MaterialButton
    private lateinit var btnMicNoAi: MaterialButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnClipboard: ImageButton
    private lateinit var btnMagic: MaterialButton
    private lateinit var btnSend: ImageButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var voiceWave: View

    // 候选词栏
    private lateinit var candidateBar: LinearLayout
    private lateinit var tvComposing: TextView
    private lateinit var btnCandidateExpand: ImageButton
    private var rvCandidates: RecyclerView? = null
    private var candidateAdapter: CandidateAdapter? = null

    // 候选词展开面板
    private lateinit var candidatePanel: LinearLayout
    private lateinit var tvPanelComposing: TextView
    private lateinit var btnPanelClose: ImageButton
    private lateinit var gvCandidates: GridView
    private var panelAdapter: ArrayAdapter<String>? = null
    private var isPanelExpanded = false

    // ======================== 核心组件 ========================
    private var typelessEngine: TypelessEngine? = null
    private lateinit var statsManager: PolishStatsManager
    private lateinit var rimeEngine: RimeEngine

    // ======================== 状态 ========================
    private var isRecording = false
    private var keyboardMode = KeyboardMode.QWERTY
    private var isCapsLock = false
    private var isProcessingResult = false
    private var isWaitingForChoice = false
    private var lastMicClickTime = 0L
    private var voiceStartTime = 0L
    private var pendingAiMode: Boolean? = null
    private var recognizedText: String = ""
    private var isAsciiMode = false  // 与 Rime ascii_mode 对应
    private var isShiftMode = false  // 数字键盘 Shift 状态（临时切换）
    private var shortPressHandled = false  // 当前按键是否已处理短按（防止长按重复触发）
    private var isShiftLocked = false  // Shift 锁定状态
    private var t9InputBuffer = StringBuilder()  // T9 数字输入缓冲
    private val t9Map = mapOf(
        2 to "abc", 3 to "def", 4 to "ghi", 5 to "jkl",
        6 to "mno", 7 to "pqrs", 8 to "tuv", 9 to "wxyz", 0 to " "
    )
    // 主字符 → 副字符(T9数字) 映射
    private val mainToSub = mapOf(
        50 to 2, 51 to 3, 52 to 4, 53 to 5, 54 to 6,
        55 to 7, 56 to 8, 57 to 9, 48 to 0
    )
    // 副字符(T9数字) → 主字符 映射
    private val subToMain = mainToSub.entries.associate { (k, v) -> v to k }

    // 长按检测
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var functionalLongPressRunnable: Runnable? = null
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

    // 清屏键长按标志
    private var deleteLongPressTriggered = false

    // 主题
    private var isDarkTheme = false
    private var apiUrl = "https://openrouter.ai/api/v1/chat/completions"

    // ======================== 键盘模式枚举 ========================
    enum class KeyboardMode { QWERTY, SYMBOL_CN, SYMBOL_EN, NUMBER }

    // 功能键长按映射（参考 Trime preset_keys）
    private fun getFunctionalLongAction(primaryCode: Int): (() -> Unit)? {
        return when (primaryCode) {
            97  -> { { sendCtrlKey(KeyEvent.KEYCODE_A) } }  // a=全选
            115 -> { { sendControlKey(KeyEvent.KEYCODE_MOVE_HOME) } }  // s=Home
            100 -> { { sendControlKey(KeyEvent.KEYCODE_MOVE_END) } }  // d=End
            102 -> { { sendControlKey(KeyEvent.KEYCODE_PAGE_UP) } }  // f=PgUp
            103 -> { { sendControlKey(KeyEvent.KEYCODE_PAGE_DOWN) } }  // g=PgDn
            104 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_LEFT) } }  // h=左
            106 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_DOWN) } }  // j=下
            107 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_UP) } }  // k=上
            108 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_RIGHT) } }  // l=右
            120 -> { { currentInputConnection?.performContextMenuAction(android.R.id.cut) } }  // x=剪切
            99  -> { { currentInputConnection?.performContextMenuAction(android.R.id.copy) } }  // c=复制
            118 -> { { currentInputConnection?.performContextMenuAction(android.R.id.paste) } }  // v=粘贴
            98  -> { { toggleBold() } }  // b=粗体
            122 -> { { sendCtrlKey(KeyEvent.KEYCODE_Z) } }  // z=撤销
            110 -> { { sendControlKey(KeyEvent.KEYCODE_INSERT) } }  // n=Insert
            109 -> { { sendControlKey(KeyEvent.KEYCODE_FORWARD_DEL) } }  // m=Delete
            else -> null
        }
    }

    private fun sendControlKey(keyCode: Int, metaState: Int = 0) {
        val ic = currentInputConnection ?: return
        val time = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }

    private fun sendCtrlKey(keyCode: Int) = sendControlKey(keyCode, KeyEvent.META_CTRL_ON)

    /** 切换选中文字的粗体样式 */
    private fun toggleBold() {
        val ic = currentInputConnection ?: return
        try {
            // 如果有选中文字，给选中文字加粗
            val selectedText = ic.getSelectedText(0)
            if (selectedText != null && selectedText.isNotEmpty()) {
                // 有选中文字 → 用 Spannable 加粗
                val newSpan = android.text.SpannableString(selectedText)
                newSpan.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD),
                    0, newSpan.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ic.commitText(newSpan, 1)
                updateStatus("✅ 已加粗选中文字")
            } else {
                // 没有选中文字 → 进入粗体模式（后续输入的文字加粗）
                // 发送一个零宽空格标记，提示用户进入粗体模式
                updateStatus("⚠️ 请先选中文字，长按 b 可将其加粗")
            }
        } catch (e: Exception) {
            Log.e("Cesia", "toggleBold 异常", e)
            updateStatus("❌ 加粗失败: ${e.message}")
        }
    }

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
        const val KEYCODE_SWITCH_NUMBER = -102
        const val KEYCODE_SHIFT = -104
        const val KEYCODE_CONTROL = -103
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
        val view = inflater.inflate(R.layout.input_view, null)

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
        btnSend = view.findViewById(R.id.btn_send)
        statusDot = view.findViewById(R.id.v_status_dot)
        statusText = view.findViewById(R.id.tv_status)
        voiceWave = view.findViewById(R.id.v_voice_wave)

        // 候选词栏
        candidateBar = view.findViewById(R.id.candidate_bar)
        tvComposing = view.findViewById(R.id.tv_composing)
        btnCandidateExpand = view.findViewById(R.id.btn_candidate_expand)

        // RecyclerView 候选词列表
        rvCandidates = view.findViewById(R.id.rv_candidates)
        candidateAdapter = CandidateAdapter { index, _ ->
            if (rimeEngine.hasCandidates) {
                selectCandidateByGlobalIndex(index)
            }
        }
        rvCandidates?.adapter = candidateAdapter
        rvCandidates?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
        )

        // 候选面板视图
        candidatePanel = view.findViewById(R.id.candidate_panel)
        tvPanelComposing = view.findViewById(R.id.tv_panel_composing)
        btnPanelClose = view.findViewById(R.id.btn_panel_close)
        gvCandidates = view.findViewById(R.id.gv_candidates)

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
        try {
            numberKeyboard = Keyboard(this, R.xml.number)
            Log.d("Cesia", "number 键盘加载成功")
        } catch (e: Exception) {
            Log.e("Cesia", "加载数字键盘失败", e)
            numberKeyboard = qwertyKeyboard
        }
        currentKeyboard = qwertyKeyboard

        keyboardView.keyboard = currentKeyboard
        keyboardView.setOnKeyboardActionListener(this)

        // 设置功能键长按副功能提示文字
        keyboardView.setFunctionalLabels(mapOf(
            97 to "全选",   // a
            115 to "Home",  // s
            100 to "End",   // d
            102 to "PgUp",  // f
            103 to "PgDn",  // g
            104 to "←",     // h
            106 to "↓",     // j
            107 to "↑",     // k
            108 to "→",     // l
            120 to "剪切",  // x
            99 to "复制",   // c
            118 to "粘贴",  // v
            98 to "粗体",   // b=粗体
            122 to "撤销",  // z
            110 to "Ins",   // n
            109 to "Del"    // m
        ))
        // T9Labels 已清空（数字键不再显示灰色副字符）
        keyboardView.setT9Labels(mapOf())
        Log.d("Cesia", "createInputViewSafe: 键盘设置完成")

        // 初始化引擎
        Log.d("Cesia", "createInputViewSafe: 开始初始化引擎")
        statsManager = PolishStatsManager(this)
        magicHistoryManager = MagicHistoryManager(this)
        currentMagicPrompt = magicHistoryManager?.getActiveInstruction()

        rimeEngine = RimeEngine(this)
        val rimeOk = rimeEngine.initialize()
        Log.i("Cesia", "Rime 引擎初始化: ok=$rimeOk")
        val rimeErrorMsg = if (!rimeOk) rimeEngine.lastError() ?: "未知" else null

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
        setupCandidatePanel()
        applyKeyboardTheme()

        updateStatus("Cesia 已就绪 | Rime init=${rimeEngine.isInitialized}" +
            (rimeErrorMsg?.let { " | 错误: $it" } ?: ""))
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
            (btnClipboard.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
        } else {
            keyboardView.setBackgroundColor(0xFFE8E8E8.toInt())
            (statusText.parent as? View)?.setBackgroundColor(0xFFEEEEEE.toInt())
            statusText.setTextColor(0xFF555555.toInt())
            candidateBar.setBackgroundColor(0xFFF0F0F0.toInt())
            tvComposing.setTextColor(0xFF4488FF.toInt())
            (btnClipboard.parent as? View)?.setBackgroundColor(0xFFE0E0E0.toInt())
        }
    }

    // ======================== 候选栏 ========================

    private fun setupCandidateBar() {
        // 展开/收起候选面板
        btnCandidateExpand.setOnClickListener {
            if (isPanelExpanded) {
                collapseCandidatePanel()
            } else {
                expandCandidatePanel()
            }
        }
    }

    private fun setupCandidatePanel() {
        // GridView 适配器 — 文字自动缩小以适应格子宽度
        panelAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            // 缓存列宽，首次测量后固定
            private var columnWidthPx = 0
            private val minTextSp = 10f
            private val maxTextSp = 14f

            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.gravity = Gravity.CENTER
                tv.setPadding(2, 6, 2, 6)
                tv.maxLines = 1
                tv.ellipsize = android.text.TextUtils.TruncateAt.END

                // 测量列宽
                if (columnWidthPx == 0) {
                    val grid = parent as? GridView
                    columnWidthPx = if (grid != null && grid.numColumns > 0) {
                        (grid.width - grid.paddingLeft - grid.paddingRight -
                            (grid.numColumns - 1) * grid.horizontalSpacing) / grid.numColumns
                    } else {
                        // 默认按屏幕宽度/5估算
                        val dm = resources.displayMetrics
                        (dm.widthPixels * 0.9f / 5).toInt()
                    }
                }

                // 自动缩小字号：如果文字宽度超过列宽，按比例缩小
                val text = getItem(position) ?: ""
                if (text.isNotEmpty() && columnWidthPx > 0) {
                    var size = maxTextSp
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                    val paint = tv.paint
                    while (size > minTextSp && paint.measureText(text) > columnWidthPx) {
                        size -= 0.5f
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                    }
                }

                return tv
            }
        }
        gvCandidates.adapter = panelAdapter

        // GridView 点击选候选词
        gvCandidates.setOnItemClickListener { _, _, position, _ ->
            selectCandidateByGlobalIndex(position)
        }

        // 收起按钮
        btnPanelClose.setOnClickListener {
            collapseCandidatePanel()
        }
    }

    private fun expandCandidatePanel() {
        isPanelExpanded = true
        candidatePanel.visibility = View.VISIBLE
        btnCandidateExpand.setImageResource(android.R.drawable.arrow_up_float)
        updateCandidateBar()
    }

    private fun collapseCandidatePanel() {
        isPanelExpanded = false
        candidatePanel.visibility = View.GONE
        btnCandidateExpand.setImageResource(android.R.drawable.arrow_down_float)
    }

    /** 通过全局索引选择候选词（自动翻页选中） */
    private fun selectCandidateByGlobalIndex(globalIndex: Int) {
        if (globalIndex < 0) return
        val curSize = rimeEngine.candidates.size
        if (curSize <= 0) return
        val targetPage = globalIndex / curSize
        val idxInPage = globalIndex % curSize
        // 翻页到目标页
        var curPage = rimeEngine.currentPage
        while (curPage < targetPage) { rimeEngine.nextPage(); curPage++ }
        while (curPage > targetPage) { rimeEngine.prevPage() }
        val selected = rimeEngine.selectCandidate(idxInPage)
        if (selected.isNotEmpty()) {
            currentInputConnection?.commitText(selected, 1)
            if (isPanelExpanded) collapseCandidatePanel()
            updateCandidateBar()
        }
    }

    private fun updateCandidateBar() {
        val composing = rimeEngine.isComposing
        val pinyin = rimeEngine.composingText

        Log.d("Cesia", "updateCandidateBar: composing=$composing, pinyin='$pinyin'")

        // 没有输入时恢复初始状态
        if (!composing && pinyin.isEmpty()) {
            candidateBar.visibility = View.GONE
            Log.d("Cesia", "updateCandidateBar: HIDE candidateBar")
            if (isPanelExpanded) collapseCandidatePanel()
            // 恢复候选词栏拼音显示
            tvComposing.text = ""
            tvComposing.visibility = View.VISIBLE
            updateStatus("Cesia 已就绪")
            return
        }

        // 有输入时：状态栏显示拼音（T9模式显示首候选词的拼音）
        candidateBar.visibility = View.VISIBLE
        Log.d("Cesia", "updateCandidateBar: SHOW candidateBar")
        tvComposing.text = ""
        tvComposing.visibility = View.GONE

        // T9 模式：状态栏只显示数字序列，候选词在候选栏
        if (keyboardMode == KeyboardMode.NUMBER && t9InputBuffer.isNotEmpty()) {
            updateStatus(t9InputBuffer.toString())
        } else {
            updateStatus(pinyin)
        }

        // 更新候选词列表（全部）
        val allCandsForBar = rimeEngine.getAllCandidates()
        candidateAdapter?.updateData(allCandsForBar)

        // 候选词>4时显示展开按钮
        btnCandidateExpand.visibility = if (allCandsForBar.size > 4) View.VISIBLE else View.GONE

        // 更新展开面板 — 显示全部候选词 + 拼音
        if (isPanelExpanded) {
            tvPanelComposing.text = pinyin
            val allCands = rimeEngine.getAllCandidates()
            panelAdapter?.clear()
            panelAdapter?.addAll(allCands)
            panelAdapter?.notifyDataSetChanged()
        }
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

        deleteLongPressTriggered = false

        btnDelete.setOnClickListener {
            if (deleteLongPressTriggered) {
                deleteLongPressTriggered = false
                return@setOnClickListener
            }
            if (rimeEngine.isComposing) {
                rimeEngine.processKey("BackSpace")
                updateCandidateBar()
            } else {
                currentInputConnection?.deleteSurroundingText(Integer.MAX_VALUE, 0)
            }
        }
        btnDelete.setOnLongClickListener {
            deleteLongPressTriggered = true
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

        // 发送按钮
        btnSend.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            if (!isAsciiMode && rimeEngine.isComposing) {
                val text = if (rimeEngine.hasCandidates) {
                    rimeEngine.selectCandidate(0).ifEmpty { rimeEngine.composingText }
                } else { rimeEngine.composingText }
                if (text.isNotEmpty()) { ic.commitText(text, 1) }
                rimeEngine.clear()
                updateCandidateBar()
            }
            val editorInfo = currentInputEditorInfo
            val action = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE) {
                ic.performEditorAction(action)
            } else {
                sendDownUpEnter()
            }
        }
    }

    // ======================== 魔法修改 ========================

    private fun toggleMagicMode() {
        if (isRecording) {
            // 正在录音 → 结束录音，取消高亮，识别结果由 onRecognitionComplete → handleMagicResult 处理
            stopRecording()
            resetMagicHighlight()
        } else {
            // 未录音 → 读取输入框文字 + 高亮 + 开始录音
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

        // 高亮按钮表示正在录音
        magicIsWaitingForVoice = true
        btnMagic.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
        btnMagic.setTextColor(0xFFFFFFFF.toInt())
        btnMagic.elevation = 6f
        btnMagic.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()

        updateStatus("🎤 请说出修改指令...（再次点击✨停止）")
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
        resetMagicHighlight()

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

        // 后台加载记录，避免主线程 JSON 解析卡界面
        Thread {
            val records = mgr.getRecords()
            Handler(Looper.getMainLooper()).post {
                try {
                    showMagicHistoryPopupInternal(mgr, records)
                } catch (e: Exception) {
                    Log.e("Cesia", "showMagicHistoryPopup UI 异常", e)
                    updateStatus("长按可管理魔法指令")
                }
            }
        }.start()
    }

    private fun showMagicHistoryPopupInternal(mgr: MagicHistoryManager, records: List<MagicHistoryManager.MagicRecord>) {
        val inflater = android.view.LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_magic_menu, null)
        val gridView = popupView.findViewById<GridView>(R.id.gv_magic_items)

        val keyboardWidth = keyboardView.width
        val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

        val gridHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 300f, resources.displayMetrics
        ).toInt()
        val barHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics
        ).toInt()
        val totalHeight = (gridHeightPx + barHeightPx).coerceAtMost(
            (resources.displayMetrics.heightPixels * 0.6f).toInt()
        )

        val popup = PopupWindow(popupView, popupWidth, totalHeight, true)
        popup.isOutsideTouchable = true
        popup.elevation = 4f
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED

        // ===== 排序：置顶项在前（按置顶顺序），然后非置顶项按时间倒序 =====
        // 被点击的魔法通过 refreshItems 重排到首位
        val items = mutableListOf<MagicHistoryManager.MagicRecord>()
        val pinned = records.filter { it.isPinned }
        val unpinned = records.filter { !it.isPinned }
        items.addAll(pinned)
        items.addAll(unpinned)

        val btnPin = popupView.findViewById<TextView>(R.id.btn_pin_manage)
        val btnAdd = popupView.findViewById<TextView>(R.id.btn_add_manage)
        val btnEdit = popupView.findViewById<TextView>(R.id.btn_edit_manage)
        val btnDelete = popupView.findViewById<TextView>(R.id.btn_delete_manage)
        val btnUndo = popupView.findViewById<TextView>(R.id.btn_undo_manage)

        fun refreshItems() {
            val newRecords = mgr.getRecords()
            val newPinned = newRecords.filter { it.isPinned }
            val newUnpinned = newRecords.filter { !it.isPinned }
            items.clear()
            items.addAll(newPinned)
            items.addAll(newUnpinned)
            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        }

        gridView.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = items[p].id
            override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val v = cv ?: inflater.inflate(R.layout.item_magic_grid, parent, false)
                val record = items[p]
                val tv = v.findViewById<TextView>(R.id.tv_magic_text)
                val tvFull = v.findViewById<TextView>(R.id.tv_magic_full)

                val isActive = record.instruction == currentMagicPrompt
                val prefix = if (isActive) "✓ " else if (record.isPinned) "📌 " else ""
                tv.text = "${prefix}${record.instruction}"
                tv.setTextColor(if (isActive) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                tv.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                tv.textSize = 12f
                tv.maxLines = 1

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

        // ===== 点击=打钩+装载+释放+关闭弹窗 =====
        gridView.setOnItemClickListener { _, _, position, _ ->
            val record = items[position]
            currentMagicPrompt = record.instruction
            popup.dismiss()
            executeSelectedMagic(record.instruction)
        }

        // ===== 长按=置顶/删除 =====
        gridView.setOnItemLongClickListener { _, v, position, _ ->
            val record = items[position]
            val popupMenu = android.widget.PopupMenu(this, v)
            popupMenu.menu.add(0, 1, 0, if (record.isPinned) "取消置顶" else "📌 置顶")
            popupMenu.menu.add(0, 2, 1, "🗑️ 删除")
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        mgr.togglePin(record.id)
                        refreshItems()
                        updateStatus(if (!record.isPinned) "📌 已置顶" else "取消置顶")
                        true
                    }
                    2 -> {
                        mgr.removeRecord(record.id)
                        val updated = mgr.getRecords()
                        if (currentMagicPrompt != null && updated.none { it.instruction == currentMagicPrompt }) {
                            currentMagicPrompt = mgr.getActiveInstruction()
                        }
                        refreshItems()
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

        // ===== 置顶按钮 =====
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
                    refreshItems()
                    updateStatus(if (!record.isPinned) "📌 已置顶" else "取消置顶")
                }
                true
            }
            popupMenu.show()
        }

        // ===== 新增：按键上方弹输入框 =====
        var addEditText: android.widget.EditText? = null
        btnAdd.setOnClickListener {
            if (addEditText != null) {
                // 输入框已显示 → 点新增按钮 = 确认添加
                val text = addEditText?.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    mgr.addRecord(text)
                    currentMagicPrompt = text  // 装载新魔法
                    refreshItems()
                    updateStatus("✅ 已新增并装载：${text.take(20)}")
                    addEditText = null
                    // 更新按钮文字回"➕"
                    btnAdd.text = "➕"
                }
            } else {
                // 首次点击 → 在按钮上方弹出输入框
                addEditText = android.widget.EditText(applicationContext).apply {
                    hint = "输入魔法指令..."
                    setPadding(24, 12, 24, 12)
                    textSize = 14f
                    setSingleLine(true)
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                }

                // 用 PopupWindow 在 btnAdd 上方显示输入框
                val editPopupView = android.widget.LinearLayout(applicationContext).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(8, 8, 8, 8)
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    addView(addEditText, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }

                val editPopup = PopupWindow(editPopupView,
                    btnAdd.width,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
                editPopup.elevation = 8f
                editPopup.isOutsideTouchable = true
                editPopup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED

                // 显示在 btnAdd 上方
                val location = IntArray(2)
                btnAdd.getLocationOnScreen(location)
                editPopup.showAtLocation(btnAdd, Gravity.NO_GRAVITY,
                    location[0],
                    location[1] - (addEditText?.height?.plus(16) ?: 100)
                )

                // 聚焦并弹出软键盘
                addEditText?.requestFocus()

                // 按钮文字改为"确认"
                btnAdd.text = "✓"

                // 监听输入完成
                addEditText?.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        btnAdd.performClick()
                        editPopup.dismiss()
                        true
                    } else false
                }

                // 点击外部关闭
                editPopup.setOnDismissListener {
                    if (addEditText != null) {
                        // 还没确认就关闭了，恢复按钮
                        btnAdd.text = "➕"
                        addEditText = null
                    }
                }
            }
        }

        // ===== 修改 =====
        btnEdit.setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            val popupMenu = android.widget.PopupMenu(this, btnEdit)
            for (r in items) {
                val title = if (r.isPinned) "📌 ${r.instruction.take(25)}" else r.instruction.take(25)
                popupMenu.menu.add(0, r.id.toInt(), 0, title)
            }
            popupMenu.setOnMenuItemClickListener { item ->
                val record = items.find { it.id.toInt() == item.itemId }
                if (record != null) {
                    val editText = android.widget.EditText(applicationContext).apply {
                        setText(record.instruction)
                        setSelection(record.instruction.length)
                        setPadding(32, 16, 32, 16)
                    }
                    val dialog = AlertDialog.Builder(applicationContext, android.R.style.Theme_Material_Light_Dialog_Alert)
                        .setTitle("✏️ 修改魔法")
                        .setView(editText)
                        .setPositiveButton("保存") { _, _ ->
                            val newText = editText.text.toString().trim()
                            if (newText.isNotEmpty() && newText != record.instruction) {
                                mgr.removeRecord(record.id)
                                mgr.addRecord(newText)
                                refreshItems()
                                updateStatus("✅ 已修改：${newText.take(20)}")
                            }
                        }
                        .setNegativeButton("取消", null)
                        .create()
                    showImeDialog(dialog)
                    dialog.show()
                }
                true
            }
            popupMenu.show()
        }

        // ===== 删除 =====
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
                    val updated = mgr.getRecords()
                    if (currentMagicPrompt != null && updated.none { it.instruction == currentMagicPrompt }) {
                        currentMagicPrompt = mgr.getActiveInstruction()
                    }
                    refreshItems()
                }
                true
            }
            popupMenu.show()
        }

        // ===== 撤销 = 关闭弹窗 =====
        btnUndo.setOnClickListener {
            popup.dismiss()
        }

        if (items.isEmpty()) {
            btnPin.visibility = View.GONE
            btnEdit.visibility = View.GONE
            btnDelete.visibility = View.GONE
        }
        popup.showAtLocation(keyboardView, Gravity.TOP, 0, 0)
    }

    // 在输入法服务中显示 dialog 的通用方法
    private fun showImeDialog(dialog: androidx.appcompat.app.AlertDialog) {
        try {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        } catch (_: Exception) { /* fallback */ }
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
        // 恢复候选词栏拼音显示
        if (::tvComposing.isInitialized) {
            tvComposing.text = ""
            tvComposing.visibility = View.VISIBLE
        }
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

    // ======================== 键盘切换（Trime 风格）=======================

    private fun switchToKeyboard(mode: KeyboardMode) {
        keyboardMode = mode
        currentKeyboard = when (mode) {
            KeyboardMode.QWERTY -> qwertyKeyboard
            KeyboardMode.SYMBOL_CN -> symbolKeyboardCn
            KeyboardMode.SYMBOL_EN -> symbolKeyboardEn
            KeyboardMode.NUMBER -> numberKeyboard
        }
        keyboardView.keyboard = currentKeyboard
        // 只有 NUMBER 模式（T9 数字键盘）才绘制字母主字符
        keyboardView.isT9Mode = (mode == KeyboardMode.NUMBER)
        keyboardView.invalidateAllKeys()
    }

    private fun toggleSymbolKeyboard() {
        if (keyboardMode == KeyboardMode.SYMBOL_CN || keyboardMode == KeyboardMode.SYMBOL_EN) {
            switchToKeyboard(KeyboardMode.QWERTY)
        } else { switchToKeyboard(KeyboardMode.SYMBOL_CN) }
    }

    private fun toggleNumberKeyboard() {
        if (keyboardMode == KeyboardMode.NUMBER) {
            switchToKeyboard(KeyboardMode.QWERTY)
            rimeEngine.selectSchema("pinyin")
            // 切换 schema 后重建 session，使新 schema 生效
            rimeEngine.reload()
        } else {
            switchToKeyboard(KeyboardMode.NUMBER)
            rimeEngine.selectSchema("t9_pinyin")
            // 切换 schema 后重建 session，使新 schema 生效
            rimeEngine.reload()
            resetNumberKeyboardState()
        }
    }

    // ======================== 数字键盘核心逻辑 ========================

    private fun resetT9State() {
        t9InputBuffer.clear()
        rimeEngine.clear()
        isShiftMode = false
        isShiftLocked = false
        updateCandidateBar()
        updateStatus("Cesia 已就绪")
    }

    // ======================== 数字键盘长按逻辑 ========================

    private var numberLongPressKeyCode = 0
    private var numberLongPressRunnable: Runnable? = null

    private fun startNumberKeyboardLongPress(primaryCode: Int, isOneKey: Boolean) {
        numberLongPressKeyCode = primaryCode
        numberLongPressRunnable = Runnable {
            if (isOneKey) {
                // 1 键长按：弹出符号候选
                showSymbolPopup()
            } else {
                // T9 键长按：弹出字母候选 (a/b/c 等)
                val letters = t9Map[mainToSub[primaryCode]] ?: ""
                showLetterPopup(primaryCode, letters)
            }
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        Handler(Looper.getMainLooper()).postDelayed(numberLongPressRunnable!!, 400)
    }

    private fun cancelNumberLongPress() {
        numberLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        numberLongPressRunnable = null
        numberLongPressKeyCode = 0
    }

    /** 长按 T9 键弹出字母候选窗 */
    private fun showLetterPopup(keyCode: Int, letters: String) {
        if (letters.isEmpty()) return
        val items = letters.map { it.toString() }
        // 隐藏候选栏，显示展开面板作为字母选单
        candidateBar.visibility = View.VISIBLE
        candidateAdapter?.updateData(items)
        btnCandidateExpand.visibility = View.GONE
        // 临时切换到字母选择模式
        updateStatus("选择字母: ${items.joinToString("/")}")
    }

    /** 长按 1 键弹出符号候选窗 */
    private fun showSymbolPopup() {
        val symbols = listOf(
            ".", ",", "?", "!", ":", ";", "'", "\"", "(", ")",
            "-", "+", "=", "/", "\\", "@", "#", "$", "%", "^",
            "&", "*", "_", "~", "`", "|", "{", "}", "[", "]",
            "<", ">", "。", "，", "？", "！", "：", "；", "、",
            "…", "—", "·", "《", "》", "「", "」", "【", "】",
            "（", "）"
        )
        // 在状态栏显示提示，用展开面板显示符号网格
        updateStatus("1键副字符：选择符号后长按1输入")
        candidateBar.visibility = View.VISIBLE
        candidateAdapter?.updateData(symbols)
        btnCandidateExpand.visibility = View.GONE
    }

    private fun resetNumberKeyboardState() {
        isShiftMode = false
        isShiftLocked = false
        t9InputBuffer.clear()
        updateShiftIndicator()
    }

    private fun updateShiftIndicator() {
        // 通过 invalidateAllKeys 刷新按键外观
        keyboardView.invalidateAllKeys()
    }

    private fun handleShiftKey() {
        if (isShiftLocked) {
            // 锁定状态 → 解除
            isShiftLocked = false
            isShiftMode = false
            commitT9AndClear()
        } else if (isShiftMode) {
            // 临时切换状态 → 锁定
            isShiftLocked = true
            // isShiftMode 保持 true
        } else {
            // 正常状态 → 临时切换
            isShiftMode = true
        }
        updateShiftIndicator()
    }

    private fun handleNumberKeyboardKey(primaryCode: Int) {
        Log.d("CesiaT9", "handleNumberKeyboardKey: primaryCode=$primaryCode isShiftMode=$isShiftMode keyboardMode=$keyboardMode")
        if (isShiftMode) {
            // Shift模式：直接输入数字
            val digit = mainToSub[primaryCode]
            if (digit != null) {
                currentInputConnection?.commitText(digit.toString(), 1)
            } else {
                currentInputConnection?.commitText(primaryCode.toChar().toString(), 1)
            }
            if (!isShiftLocked) {
                isShiftMode = false
                updateShiftIndicator()
            }
        } else {
            // 主字符模式：T9拼音输入
            val t9Digit = mainToSub[primaryCode]
            Log.d("CesiaT9", "T9 path: t9Digit=$t9Digit")
            if (t9Digit != null) {
                t9InputBuffer.append(t9Digit)
                Log.d("CesiaT9", "t9InputBuffer=$t9InputBuffer")
                processT9Input()
            } else {
                when (primaryCode) {
                    49 -> currentInputConnection?.commitText("1", 1)
                    65292 -> currentInputConnection?.commitText("，", 1)
                    12290 -> currentInputConnection?.commitText("。", 1)
                    65311 -> currentInputConnection?.commitText("？", 1)
                    65281 -> currentInputConnection?.commitText("！", 1)
                    else -> currentInputConnection?.commitText(primaryCode.toChar().toString(), 1)
                }
            }
        }
    }

    private fun processT9Input() {
        val digits = t9InputBuffer.toString()
        Log.d("CesiaT9", "processT9Input: digits='$digits'")
        // 只传入最新的数字键（不是重输全部）
        if (digits.isNotEmpty()) {
            val lastDigit = digits.last().toString()
            val result = rimeEngine.processKey(lastDigit)
            Log.d("CesiaT9", "processKey('$lastDigit') result=$result composing=${rimeEngine.isComposing} hasCands=${rimeEngine.hasCandidates}")
        }
        updateCandidateBar()
    }

    private fun commitT9AndClear() {
        if (t9InputBuffer.isNotEmpty()) {
            if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                val selected = rimeEngine.selectCandidate(0)
                if (selected.isNotEmpty()) {
                    currentInputConnection?.commitText(selected, 1)
                }
            }
            t9InputBuffer.clear()
            rimeEngine.clear()
            updateCandidateBar()
        }
    }

    // 控制键—按键对调模式
    private var isSwapMode = false
    private var swapFirstKey: Keyboard.Key? = null

    private fun handleControlKey() {
        if (!isSwapMode) {
            isSwapMode = true
            swapFirstKey = null
            updateStatus("🔄 对调模式：先点第一个按键")
            keyboardView.invalidateAllKeys()
        } else {
            // 退出对调模式
            isSwapMode = false
            swapFirstKey = null
            updateStatus("Cesia 已就绪")
            keyboardView.invalidateAllKeys()
        }
    }

    private fun switchToDefaultKeyboard() {
        val wasT9 = keyboardMode == KeyboardMode.NUMBER
        switchToKeyboard(KeyboardMode.QWERTY)
        isAsciiMode = false
        rimeEngine.setAsciiMode(false)
        if (wasT9) rimeEngine.selectSchema("pinyin")
        rimeEngine.clear()
        updateCandidateBar()
    }

    private fun toggleLanguage() {
        isAsciiMode = !isAsciiMode
        rimeEngine.setAsciiMode(isAsciiMode)
        // 如果当前在数字键盘，保持在数字键盘（只是切换中英文模式）
        if (keyboardMode != KeyboardMode.NUMBER) {
            switchToKeyboard(KeyboardMode.QWERTY)
        }
        rimeEngine.clear()
        updateCandidateBar()
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
    // 参考 Trime 的 CommonKeyboardActionListener.onKey 逻辑：
    // 1. 按键后调用 processKey，不检查返回值
    // 2. 通过 getRimeContext/getRimeStatus 轮询状态更新 UI
    // 3. 退格/空格/回车等控制键优先交给 Rime 处理

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        Log.d("Cesia", "onKey: primaryCode=$primaryCode isAsciiMode=$isAsciiMode keyboardMode=$keyboardMode")
        val wasLongPressed = longPressTriggered && !longPressConsumed
        if (wasLongPressed) {
            Log.d("Cesia", "onKey: 被长按拦截 primaryCode=$primaryCode")
            longPressConsumed = true
            cancelLongPress()
            return
        }
        cancelLongPress()

        val ic = currentInputConnection
        val composing = rimeEngine.isComposing
        val hasCands = rimeEngine.hasCandidates
        val cands = rimeEngine.candidates

        Log.d("Cesia", "onKey: composing=$composing hasCands=$hasCands cands.size=${cands.size}")

        when (primaryCode) {

            // ======================== 字母键 a-z ========================
            in 97..122 -> {
                Log.d("CesisLongPress", "onKey: 字母键 primaryCode=$primaryCode")
                Log.d("CesiaLongPress", "onKey: 取消长按 primaryCode=$primaryCode")
                functionalLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                functionalLongPressRunnable = null
                shortPressHandled = true
                if (isAsciiMode) {
                    ic?.commitText(primaryCode.toChar().toString(), 1)
                } else {
                    rimeEngine.processKey(primaryCode.toChar())
                    updateCandidateBar()
                }
            }

            // ======================== 数字键区域 (0-9 及字母键 abc/def...) ========================
            // 主字符模式：abc=2, def=3, ghi=4... T9拼音输入
            // Shift模式：直接输入数字
            in 48..57 -> {
                if (keyboardMode == KeyboardMode.NUMBER) {
                    handleNumberKeyboardKey(primaryCode)
                } else {
                    // 全键盘模式的数字键原有逻辑
                    if (!isAsciiMode && composing && hasCands) {
                        val index = if (primaryCode == 48) 9 else (primaryCode - 49)
                        if (index < cands.size) {
                            val selected = rimeEngine.selectCandidate(index)
                            if (selected.isNotEmpty()) {
                                ic?.commitText(selected, 1)
                            } else { commitAndClear() }
                        } else {
                            ic?.commitText(primaryCode.toChar().toString(), 1)
                        }
                    } else {
                        ic?.commitText(primaryCode.toChar().toString(), 1)
                    }
                    updateCandidateBar()
                }
            }

            // ======================== 空格键 ========================
            32 -> {
                if (keyboardMode == KeyboardMode.NUMBER) {
                    // 数字键盘空格：T9模式下输入空格分隔拼音，否则直接上屏
                    if (isShiftMode) {
                        // Shift模式直接上屏空格
                        ic?.commitText(" ", 1)
                    } else if (t9InputBuffer.isNotEmpty()) {
                        // T9模式：空格 = 选择首候选上屏
                        val cands = rimeEngine.candidates
                        if (cands.isNotEmpty()) {
                            val selected = rimeEngine.selectCandidate(0)
                            if (selected.isNotEmpty()) {
                                ic?.commitText(selected, 1)
                            }
                        } else {
                            ic?.commitText(" ", 1)
                        }
                        resetT9State()
                    } else {
                        ic?.commitText(" ", 1)
                    }
                } else if (isAsciiMode) {
                    ic?.commitText(" ", 1)
                } else if (composing && hasCands) {
                    val selected = rimeEngine.selectCandidate(0)
                    if (selected.isNotEmpty()) {
                        ic?.commitText(selected, 1)
                    } else { commitAndClear(); ic?.commitText(" ", 1) }
                } else if (composing) {
                    commitAndClear(); ic?.commitText(" ", 1)
                } else {
                    ic?.commitText(" ", 1)
                }
                if (keyboardMode != KeyboardMode.NUMBER) updateCandidateBar()
            }

            // ======================== 退格键 ========================
            -5, Keyboard.KEYCODE_DELETE -> {
                if (keyboardMode == KeyboardMode.NUMBER) {
                    // 数字键盘退格
                    if (!isShiftMode && t9InputBuffer.isNotEmpty()) {
                        // 先告诉 Rime 删除一个按键
                        rimeEngine.processKey("BackSpace")
                        // 同步更新缓冲
                        t9InputBuffer.deleteCharAt(t9InputBuffer.length - 1)
                        if (t9InputBuffer.isEmpty()) {
                            rimeEngine.clear()
                            resetT9State()
                        } else {
                            updateCandidateBar()
                        }
                    } else {
                        ic?.deleteSurroundingText(1, 0)
                    }
                } else if (isAsciiMode) {
                    ic?.deleteSurroundingText(1, 0)
                } else {
                    val wasComposing = rimeEngine.isComposing
                    val handled = rimeEngine.processKey("BackSpace")
                    if (!handled) {
                        ic?.deleteSurroundingText(1, 0)
                    }
                    if (wasComposing && !rimeEngine.isComposing) {
                        resetToIdle()
                    } else {
                        updateCandidateBar()
                    }
                }
            }

            // ======================== 回车键 ========================
            10, Keyboard.KEYCODE_DONE -> {
                if (!isAsciiMode && composing) {
                    // 直接上屏当前拼音字母（不转换成汉字）
                    val pinyinText = rimeEngine.composingText
                    if (pinyinText.isNotEmpty()) {
                        ic?.commitText(pinyinText, 1)
                    } else if (hasCands) {
                        val selected = rimeEngine.selectCandidate(0)
                        if (selected.isNotEmpty()) {
                            ic?.commitText(selected, 1)
                        }
                    }
                    rimeEngine.clear()
                    updateCandidateBar()
                } else {
                    sendDownUpEnter()
                }
            }

            // ======================== Shift 键 ========================
            -1 -> {
                when {
                    // 大写锁定状态：切回中文
                    isCapsLock -> {
                        isCapsLock = false
                        isAsciiMode = false
                        rimeEngine.setAsciiMode(false)
                        qwertyKeyboard.isShifted = false
                        rimeEngine.clear()
                    }
                    // 英文模式：切换大写
                    isAsciiMode -> {
                        isCapsLock = true
                        qwertyKeyboard.isShifted = true
                    }
                    // 中文模式：切换英文
                    else -> {
                        isAsciiMode = true
                        rimeEngine.setAsciiMode(true)
                        rimeEngine.clear()
                        isCapsLock = false
                        qwertyKeyboard.isShifted = false
                    }
                }
                keyboardView.invalidateAllKeys()
                updateCandidateBar()
            }

            // ======================== 符号切换（符）=======================
            KEYCODE_SWITCH_SYMBOL -> toggleSymbolKeyboard()

            // ======================== 数字切换（123）=======================
            KEYCODE_SWITCH_NUMBER -> toggleNumberKeyboard()
            KEYCODE_CONTROL -> handleControlKey()
            KEYCODE_SHIFT -> handleShiftKey()

            // ======================== 中英文切换（🌐）=======================
            KEYCODE_SWITCH_LANG -> toggleLanguage()

            // ======================== 返回键 ========================
            KEYCODE_BACK_KEY -> switchToDefaultKeyboard()

            // ======================== 发送键（纸飞机）=======================
            -200 -> {
                if (sendKeyLongPressTriggered) {
                    sendKeyLongPressTriggered = false; return
                }
                if (!isAsciiMode && composing) {
                    val text = if (hasCands) {
                        rimeEngine.selectCandidate(0).ifEmpty { rimeEngine.composingText }
                    } else { rimeEngine.composingText }
                    if (text.isNotEmpty()) { ic?.commitText(text, 1) }
                    rimeEngine.clear()
                    updateCandidateBar()
                }
                val editorInfo = currentInputEditorInfo
                val action = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE) {
                    ic?.performEditorAction(action)
                } else sendDownUpEnter()
            }

            // ======================== 其他按键（标点等）=======================
            else -> {
                if (!isAsciiMode && composing) commitAndClear()
                // 中文模式下，逗号/句号映射为中文标点
                val adjustedCode = if (!isAsciiMode) {
                    when (primaryCode) {
                        44 -> 65292   // , → ，
                        46 -> 12290   // . → 。
                        47 -> 65311   // / → ？
                        else -> primaryCode
                    }
                } else primaryCode
                val c = adjustedCode.toChar()
                if (c != '\u0000') { ic?.commitText(c.toString(), 1) }
            }
        }
    }

    /**
     * 提交当前 composing 文本并清除状态
     */
    private fun commitAndClear() {
        val text = rimeEngine.commit()
        if (text.isNotEmpty()) {
            currentInputConnection?.commitText(text, 1)
        }
        rimeEngine.clear()
        if (isPanelExpanded) collapseCandidatePanel()
        updateCandidateBar()
    }

    /**
     * 发送回车键事件
     */
    private fun sendDownUpEnter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        ic.sendKeyEvent(KeyEvent(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    override fun onPress(primaryCode: Int) {
        shortPressHandled = false
        Log.d("Cesia", "onPress: primaryCode=$primaryCode")
        // 功能键长按检测（仅 QWERTY 中文模式，且 Rime 不在 composing 状态）
        // 注意：功能键长按(500ms)优先于 popupCharacters 长按(400ms)
        // 功能键长按注册后，跳过 popupCharacters 长按，避免冲突
        var skipPopupLongPress = false
        if (!isAsciiMode && primaryCode in 97..122 && keyboardMode == KeyboardMode.QWERTY && !rimeEngine.isComposing) {
            if (getFunctionalLongAction(primaryCode) != null) {
                Log.d("CesiaLongPress", "onPress: 注册功能长按 primaryCode=$primaryCode")
                skipPopupLongPress = true
                functionalLongPressRunnable = Runnable {
                    Log.d("CesiaLongPress", "功能长按触发! primaryCode=$primaryCode shortPressHandled=$shortPressHandled")
                    if (!shortPressHandled) {
                        getFunctionalLongAction(primaryCode)?.invoke()
                        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        longPressTriggered = true
                        longPressConsumed = false
                    }
                    currentLongPressKey = null
                }
                Handler(Looper.getMainLooper()).postDelayed(functionalLongPressRunnable!!, 500)
            }
        }
        // popupCharacters 长按检测（功能键不注册，避免与功能长按冲突）
        if (!skipPopupLongPress && primaryCode > 0) {
            val key = currentKeyboard?.keys?.find { it.codes?.contains(primaryCode) == true }
            if (key != null && !key.popupCharacters.isNullOrEmpty()) {
                startLongPressDetection(key)
            }
        }
        // 数字键盘按键长按检测（T9字母候选 / 符号候选）
        if (keyboardMode == KeyboardMode.NUMBER && primaryCode != -104 && primaryCode != -100 && primaryCode != -101 && primaryCode != -103 && primaryCode != -5 && primaryCode != 10) {
            val isT9Key = mainToSub.containsKey(primaryCode)
            val isOneKey = (primaryCode == 49)
            if (isT9Key || isOneKey) {
                startNumberKeyboardLongPress(primaryCode, isOneKey)
            }
        }
        if (primaryCode == -5 || primaryCode == Keyboard.KEYCODE_DELETE) {
            backspaceRunnable = object : Runnable {
                override fun run() {
                    val handled = rimeEngine.processKey("BackSpace")
                    if (!handled) {
                        currentInputConnection?.deleteSurroundingText(1, 0)
                    }
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
        cancelNumberLongPress()
        functionalLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        functionalLongPressRunnable = null
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
        Log.d("Cesia", "onStartInputView: restarting=$restarting package=${info?.packageName} fieldId=${info?.fieldId} inputType=${info?.inputType} hintText=${info?.hintText}")
        try {
            if (!isViewInitialized) {
                Log.w("Cesia", "onStartInputView: isViewInitialized=false, skipping")
                return
            }
            loadSettings()
            val themeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
                .getInt(PREF_THEME_MODE, THEME_LIGHT)
            isDarkTheme = themeMode == THEME_DARK
            applyKeyboardTheme()
            aiReplyStyle = getSharedPreferences("cesia_settings", MODE_PRIVATE)
                .getString(PREF_AI_STYLE, "自然") ?: "自然"
            // 外部词库下载后需要重新部署 Rime
            val dictPrefs = getSharedPreferences("cesia_dict", MODE_PRIVATE)
            val settingsPrefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            if (dictPrefs.getBoolean("dict_downloaded", false) && rimeEngine.isInitialized) {
                val lastReload = settingsPrefs.getLong("last_dict_reload", 0)
                val lastSync = dictPrefs.getLong("last_sync", 0)
                if (lastSync > lastReload) {
                    Log.i("Cesia", "检测到词库更新，重新部署 Rime")
                    rimeEngine.reload()
                    settingsPrefs.edit().putLong("last_dict_reload", System.currentTimeMillis()).apply()
                }
            }
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
