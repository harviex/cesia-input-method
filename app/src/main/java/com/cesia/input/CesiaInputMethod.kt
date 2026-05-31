package com.cesia.input

import android.content.ClipboardManager
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
    private var keyboardMode = KeyboardMode.NUMBER  // 默认 T9 数字键盘
    private var prevKeyboardMode = KeyboardMode.NUMBER  // 进入符号键盘前的键盘模式（用于返回）
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
    private var qwertyShiftLock = false  // 全键盘shift锁定（跨键盘切换保持）
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

    // 剪贴板键长按
    private var clipboardPasteRunnable: Runnable? = null
    private var clipboardCutRunnable: Runnable? = null

    // Shift 键长按检测
    private var shiftLongPressRunnable: Runnable? = null

    // 回车键长按检测
    private var enterLongPressRunnable: Runnable? = null

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

    // 剪贴板管理器：收藏/锁定条目 (text -> isLocked)
    private val clipboardFavorites = mutableMapOf<String, Boolean>()
    private val clipboardHistory = mutableListOf<String>()
    private val maxClipboardHistory = 50
    // 剪贴板弹窗引用（搜索编辑模式需要刷新 adapter）
    private var clipboardPopupView: android.view.View? = null
    private var clipboardAdapter: android.widget.BaseAdapter? = null
    private var clipboardItems = mutableListOf<ClipboardItem>()
    private var clipboardFilteredItems = mutableListOf<ClipboardItem>()
    private var clipboardSearchFilter = ""
    private fun applyClipboardFilter() {
        clipboardFilteredItems.clear()
        if (clipboardSearchFilter.isEmpty()) {
            clipboardFilteredItems.addAll(clipboardItems)
        } else {
            clipboardFilteredItems.addAll(clipboardItems.filter { it.text.contains(clipboardSearchFilter, ignoreCase = true) })
        }
        clipboardAdapter?.notifyDataSetChanged()
        clipboardPopupView?.findViewById<TextView>(R.id.tv_clipboard_empty)?.visibility =
            if (clipboardFilteredItems.isEmpty()) View.VISIBLE else View.GONE
    }

    // 初始化标志
    private var isViewInitialized = false

    // 清屏键长按标志
    private var deleteLongPressTriggered = false

    // ======================== 魔法编辑模式 ========================
    // 当用户点击"➕ 新增"后进入此模式，键盘输入直接写入魔法指令缓冲区
    private var magicEditMode = false
    private var magicEditBuffer = StringBuilder()
    private var magicEditMgr: MagicHistoryManager? = null  // 新增完成后保存用

    // 主题
    private var isDarkTheme = false
    private var apiUrl = "https://openrouter.ai/api/v1/chat/completions"

    // ======================== 键盘模式枚举 ========================
    enum class KeyboardMode { QWERTY, SYMBOL_CN, SYMBOL_EN, NUMBER }

    // ======================== 简繁切换 ========================
    private var isTraditional = false
    private lateinit var btnTraditional: TextView

    // 功能键长按映射（参考 Trime preset_keys）
    private fun getFunctionalLongAction(primaryCode: Int): (() -> Unit)? {
        return when (primaryCode) {
            // QWERTY上排(qwertyuiop)：无功能长按（popupCharacters显示数学符号，不需要长按输出）
            // ASDF行：恢复编辑功能
            97  -> { { sendCtrlKey(KeyEvent.KEYCODE_A) } }  // a=全选
            115 -> { { sendControlKey(KeyEvent.KEYCODE_MOVE_HOME) } }  // s=Home
            100 -> { { sendControlKey(KeyEvent.KEYCODE_MOVE_END) } }  // d=End
            102 -> { { sendControlKey(KeyEvent.KEYCODE_PAGE_UP) } }  // f=PgUp
            103 -> { { sendControlKey(KeyEvent.KEYCODE_PAGE_DOWN) } }  // g=PgDn
            104 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_LEFT) } }  // h=左
            106 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_DOWN) } }  // j=下
            107 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_UP) } }  // k=上
            108 -> { { sendControlKey(KeyEvent.KEYCODE_DPAD_RIGHT) } }  // l=右
            // ZXCV行：编辑功能
            120 -> { { currentInputConnection?.performContextMenuAction(android.R.id.cut) } }  // x=剪切
            99  -> { { currentInputConnection?.performContextMenuAction(android.R.id.copy) } }  // c=复制
            118 -> { { currentInputConnection?.performContextMenuAction(android.R.id.paste) } }  // v=粘贴
            98  -> { { toggleUpperCase() } }  // b=大写转换
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

    /** 大写转换：选中的英文→大写，数字→中文大写数字 */
    /** 判断魔法指令是否为生成类（允许空文本）还是修改类（需要文本） */
    private fun isGenerationMagic(instruction: String): Boolean {
        val genKeywords = listOf("帮我想", "帮我写", "生成", "创作", "编写", "写一个", "写一段", "给我一个")
        return genKeywords.any { instruction.contains(it) }
    }

    private fun toggleUpperCase() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)?.toString()
        if (selectedText.isNullOrEmpty()) {
            updateStatus("请先选中要转换的文字")
            return
        }
        val result = toUpperCaseText(selectedText)
        ic.commitText(result, 1)
        updateStatus("✅ 已转换 ${selectedText.length} 字")
    }

    /** 英文转大写，数字转中文大写 */
    private fun toUpperCaseText(text: String): String {
        val chineseNumbers = charArrayOf('零','壹','贰','叁','肆','伍','陆','柒','捌','玖')
        return text.map { ch ->
            when {
                ch in 'a'..'z' -> ch.uppercaseChar()
                ch in 'A'..'Z' -> ch
                ch in '0'..'9' -> chineseNumbers[ch - '0']
                else -> ch
            }
        }.joinToString("")
    }

    /** 如果有选中文本则删除选区，否则删除光标前一个字符 */
    /** 发送 Tab 键 */
    private fun sendTabKey() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0))
        ic.sendKeyEvent(KeyEvent(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0))
    }

    private fun deleteSelectionOrChar() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)?.toString()
        if (!selectedText.isNullOrEmpty()) {
            ic.deleteSurroundingText(0, 0)  // 发送 delete 键事件清除选区
            ic.sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
            ic.sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    // ======================== 简繁转换映射表（候选词上屏时转换）=======================
    private val SIMP_TRAD: Map<Char, Char> = mapOf(
        '国' to '國', '会' to '會', '来' to '來', '时' to '時', '个' to '個',
        '们' to '們', '说' to '說', '这' to '這', '为' to '為', '过' to '過',
        '对' to '對', '还' to '還', '发' to '發', '经' to '經', '长' to '長',
        '问' to '問', '开' to '開', '学' to '學', '动' to '動', '进' to '進',
        '种' to '種', '应' to '應', '头' to '頭', '现' to '現', '实' to '實',
        '点' to '點', '业' to '業', '关' to '關', '机' to '機', '认' to '認',
        '让' to '讓', '东' to '東', '当' to '當', '没' to '沒', '产' to '產',
        '车' to '車', '见' to '見', '电' to '電', '里' to '裡', '两' to '兩',
        '场' to '場', '从' to '從', '无' to '無', '万' to '萬', '亚' to '亞',
        '着' to '著', '处' to '處', '将' to '將', '书' to '書', '许' to '許',
        '总' to '總', '听' to '聽', '员' to '員', '难' to '難', '结' to '結',
        '极' to '極', '义' to '義', '记' to '記', '务' to '務', '战' to '戰',
        '图' to '圖', '报' to '報', '类' to '類', '条' to '條', '统' to '統',
        '办' to '辦', '华' to '華', '变' to '變', '运' to '運', '达' to '達',
        '传' to '傳', '该' to '該', '众' to '眾', '写' to '寫', '军' to '軍',
        '门' to '門', '语' to '語', '选' to '選', '区' to '區', '级' to '級',
        '转' to '轉', '杀' to '殺', '范' to '範', '风' to '風', '虽' to '雖',
        '举' to '舉', '销' to '銷', '独' to '獨', '资' to '資', '养' to '養',
        '节' to '節', '价' to '價', '权' to '權', '苏' to '蘇', '刘' to '劉',
        '孙' to '孫', '陈' to '陳', '杨' to '楊', '赵' to '趙', '张' to '張',
        '罗' to '羅', '郑' to '鄭', '韩' to '韓', '钱' to '錢', '给' to '給',
        '纳' to '納', '龙' to '龍', '刚' to '剛', '过' to '過', '边' to '邊',
        '网' to '網', '飞' to '飛', '还' to '還', '系' to '係', '计' to '計',
        '让' to '讓'
    )

    /** 简→繁转换（逐字替换）*/
    private fun toTraditional(text: String): String {
        val sb = StringBuilder(text.length * 2)
        for (ch in text) { sb.append(SIMP_TRAD[ch] ?: ch) }
        return sb.toString()
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
        const val KEYCODE_SWITCH_SYMBOL_LANG = -105
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
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.input_view, null)

        keyboardView = view.findViewById(R.id.keyboard_view)
        btnTraditional = view.findViewById(R.id.btn_traditional)
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
        btnCandidateExpand = view.findViewById(R.id.btn_candidate_expand)
        // tvT9Letters/dividerT9 已移除

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

        // T9 字母区已移除（不再显示英文字母和分隔线）

        // 候选面板视图
        candidatePanel = view.findViewById(R.id.candidate_panel)
        tvPanelComposing = view.findViewById(R.id.tv_panel_composing)
        btnPanelClose = view.findViewById(R.id.btn_panel_close)
        gvCandidates = view.findViewById(R.id.gv_candidates)

        // 初始化键盘
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        try {
            symbolKeyboardEn = Keyboard(this, R.xml.symbols)
        } catch (e: Exception) {
            Log.e("Cesia", "加载英文符号键盘失败", e)
            symbolKeyboardEn = qwertyKeyboard
        }
        try {
            symbolKeyboardCn = Keyboard(this, R.xml.symbols_cn)
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
        currentKeyboard = numberKeyboard

        keyboardView.keyboard = currentKeyboard
        keyboardView.isT9Mode = true
        keyboardView.setOnKeyboardActionListener(this)

        // 左右滑动循环切换全键盘/T9
        keyboardView.onSwipeLeft = { toggleBySwipe() }
        keyboardView.onSwipeRight = { toggleBySwipe() }

        // 设置功能键长按副功能提示文字
        keyboardView.setFunctionalLabels(mapOf(
            // ASDF行：编辑功能
            97 to "全选",  // a
            115 to "Home", // s
            100 to "End",  // d
            102 to "PgUp", // f
            103 to "PgDn", // g
            104 to "←",    // h
            106 to "↓",    // j
            107 to "↑",    // k
            108 to "→",    // l
            // ZXCV行：编辑功能
            120 to "剪切", // x
            99 to "复制",  // c
            118 to "粘贴", // v
            98 to "大写",  // b
            122 to "撤销", // z
            110 to "Ins",  // n
            109 to "Del",  // m
            -108 to "粘贴", // 剪贴板键：副字符
            -109 to "剪切", // 复制键：副字符
            10 to "撤销"   // 回车键：副字符
        ))
        // T9Labels 已清空（数字键不再显示灰色副字符）
        keyboardView.setT9Labels(mapOf())

        // 初始化引擎
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

        // 初始化为 T9 模式
        rimeEngine.selectSchema("t9_pinyin")
        rimeEngine.reload()

        return view
    }

    // ======================== 主题 ========================

    private fun applyKeyboardTheme() {
        if (isDarkTheme) {
            keyboardView.setBackgroundColor(0xFF0F0F23.toInt())
            (statusText.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
            statusText.setTextColor(0xFFE0E0E0.toInt())
            candidateBar.setBackgroundColor(0xFF16213E.toInt())
            (btnClipboard.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
        } else {
            keyboardView.setBackgroundColor(0xFFE8E8E8.toInt())
            (statusText.parent as? View)?.setBackgroundColor(0xFFEEEEEE.toInt())
            statusText.setTextColor(0xFF555555.toInt())
            candidateBar.setBackgroundColor(0xFFF0F0F0.toInt())
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
            commitCandidateText(selected)
            // T9模式：点选上屏后清除数字缓冲，与空格上屏一致
            if (keyboardMode == KeyboardMode.NUMBER && t9InputBuffer.isNotEmpty()) {
                t9InputBuffer.clear()
                rimeEngine.clear()
                rimeEngine.createSession()
            }
            if (isPanelExpanded) collapseCandidatePanel()
            updateCandidateBar()
        }
    }

    private fun updateCandidateBar() {
        val composing = rimeEngine.isComposing
        val pinyin = rimeEngine.composingText
        val allCands = rimeEngine.getAllCandidates()

        // 没有输入时恢复初始状态
        if (!composing && pinyin.isEmpty()) {
            candidateBar.visibility = View.GONE
            if (isPanelExpanded) collapseCandidatePanel()
            updateStatus("Cesia 已就绪")
            return
        }

        // 有输入时
        candidateBar.visibility = View.VISIBLE

        // T9 模式：状态栏显示数字序列（不再显示英文字母区和分隔线）
        if (keyboardMode == KeyboardMode.NUMBER && t9InputBuffer.isNotEmpty()) {
            updateStatus(t9InputBuffer.toString())
        } else {
            updateStatus(pinyin)
        }
        // 更新候选词列表
        // 更新候选词列表
        candidateAdapter?.updateData(allCands)
        btnCandidateExpand.visibility = if (allCands.size > 4) View.VISIBLE else View.GONE

        // 更新展开面板
        if (isPanelExpanded) {
            tvPanelComposing.text = pinyin
            val allCandsPanel = rimeEngine.getAllCandidates()
            panelAdapter?.clear()
            panelAdapter?.addAll(allCandsPanel)
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
        btnTraditional.setOnClickListener { toggleTraditionalSimplified() }

        deleteLongPressTriggered = false

        btnDelete.setOnClickListener {
            if (rimeEngine.isComposing) {
                rimeEngine.processKey("BackSpace")
                updateCandidateBar()
            } else {
                try {
                    currentInputConnection?.deleteSurroundingText(Integer.MAX_VALUE, 0)
                } catch (_: Exception) { /* 安全忽略 */ }
            }
        }
        btnDelete.setOnLongClickListener {
            try {
                if (rimeEngine.isComposing) {
                    rimeEngine.processKey("BackSpace")
                    updateCandidateBar()
                } else {
                    // 安全删除：分段删除光标后文本，避免某些App崩溃
                    val ic = currentInputConnection ?: return@setOnLongClickListener true
                    val afterCursor = ic.getTextAfterCursor(10000, 0)
                    if (afterCursor != null && afterCursor.isNotEmpty()) {
                        val deleteLen = minOf(afterCursor.length, 1000)
                        ic.deleteSurroundingText(0, deleteLen)
                    }
                }
            } catch (_: Exception) { /* 安全忽略 */ }
            true
        }

        btnClipboard.setOnClickListener { executeMagicOrAiReply() }
        btnClipboard.setOnLongClickListener {
            // 高亮动画
            btnClipboard.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).withEndAction {
                btnClipboard.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
            showMagicHistoryPopup(); true
        }

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
        // 发送键长按：剪贴板管理器
        btnSend.setOnLongClickListener {
            showClipboardManagerPopup()
            true
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
        btnMagic.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF81D8D0.toInt())
        btnMagic.setTextColor(0xFFFFFFFF.toInt())
        btnMagic.elevation = 6f
        btnMagic.pivotX = btnMagic.width / 2f
        btnMagic.pivotY = btnMagic.height / 2f
        btnMagic.animate().scaleX(1.08f).scaleY(1.08f).setDuration(200).start()

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

        // 生成类魔法允许空文本，修改类魔法要求有文本
        if (fullText.isEmpty() && !isGenerationMagic(instruction)) {
            updateStatus("⚠️ 输入框无文字，无法执行修改类魔法")
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
        popup.isOutsideTouchable = false
        popup.elevation = 4f
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.setFocusable(false)

        // ===== 数据列表：置顶项在前，非置顶项按时间倒序 =====
        val items = mutableListOf<MagicHistoryManager.MagicRecord>()
        fun rebuildItems() {
            val all = mgr.getRecords()
            items.clear()
            items.addAll(all.filter { it.isPinned })
            items.addAll(all.filter { !it.isPinned })
        }
        rebuildItems()

        val btnAdd = popupView.findViewById<ImageButton>(R.id.btn_add_magic)
        val btnPin = popupView.findViewById<TextView>(R.id.btn_pin_manage)
        val btnDelete = popupView.findViewById<ImageButton>(R.id.btn_delete_manage)
        val btnClose = popupView.findViewById<ImageButton>(R.id.btn_close_magic)

        // 追踪当前编辑状态
        var editingPosition = -1
        var hasFocusedEdit = false

        fun notifyChanged() {
            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        }

        // ===== 底部按钮：新增魔法 =====
        btnAdd.setOnClickListener {
            popup.dismiss()
            enterMagicEditMode(mgr)
        }

        gridView.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = items[p].id

            override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val v = cv ?: inflater.inflate(R.layout.item_magic_grid, parent, false)
                val record = items[p]
                val tv = v.findViewById<TextView>(R.id.tv_magic_text)
                val et = v.findViewById<android.widget.EditText>(R.id.et_magic_edit)
                val isEditing = (p == editingPosition)

                if (isEditing) {
                    tv.visibility = View.GONE
                    et.visibility = View.VISIBLE
                    if (et.text.toString() != record.instruction) {
                        et.setText(record.instruction)
                        et.setSelection(et.text.length)
                    }
                    et.hint = "✏️ 修改魔法指令..."
                    et.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                            saveEditing(p, gridView, mgr) { rebuildItems(); notifyChanged() }
                            editingPosition = -1
                            hasFocusedEdit = false
                            true
                        } else false
                    }
                } else {
                    et.visibility = View.GONE
                    tv.visibility = View.VISIBLE
                    et.setOnEditorActionListener(null)

                    val isActive = record.instruction == currentMagicPrompt
                    val prefix = if (isActive) "✓ " else if (record.isPinned) "📌 " else ""
                    tv.text = "${prefix}${record.instruction}"
                    tv.setTextColor(if (isActive) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                    tv.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    tv.textSize = 13f
                    tv.maxLines = 2
                }
                return v
            }
        }

        // ===== 单击：打钩+装载+执行+关闭 =====
        gridView.setOnItemClickListener { _, _, position, _ ->
            val record = items[position]
            currentMagicPrompt = record.instruction
            popup.dismiss()
            executeSelectedMagic(record.instruction)
        }

        // ===== 长按：进入编辑模式 =====
        gridView.setOnItemLongClickListener { _, _, position, _ ->
            if (editingPosition != position) {
                editingPosition = position
                hasFocusedEdit = false
                notifyChanged()
                // 延迟 requestFocus，等.getView执行完后再聚焦
                gridView.post {
                    val child = gridView.getChildAt(position - gridView.firstVisiblePosition)
                    val et = child?.findViewById<android.widget.EditText>(R.id.et_magic_edit)
                    et?.requestFocus()
                    // 弹出软键盘
                    et?.postDelayed({
                        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }, 100)
                }
            }
            true
        }

        // ===== 关闭按钮 =====
        btnClose.setOnClickListener {
            popup.dismiss()
        }

        // ===== 滚动时退出编辑模式但不保存 =====
        gridView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {
                if (scrollState != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE && editingPosition >= 0) {
                    editingPosition = -1
                    hasFocusedEdit = false
                    notifyChanged()
                }
            }
            override fun onScroll(view: android.widget.AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
        })

        popup.setOnDismissListener {
            if (editingPosition >= 0) {
                saveEditing(editingPosition, gridView, mgr) { rebuildItems(); notifyChanged() }
                editingPosition = -1
                hasFocusedEdit = false
            }
        }

        // ===== 置顶按钮 =====
        btnPin.setOnClickListener {
            val realItems = items
            if (realItems.isEmpty()) return@setOnClickListener
            val popupMenu = android.widget.PopupMenu(this, btnPin)
            for (r in realItems) {
                val title = "${if (r.isPinned) "📌 " else "○ "}${r.instruction.take(18)}"
                popupMenu.menu.add(0, r.id.toInt(), 0, title)
            }
            popupMenu.setOnMenuItemClickListener { item ->
                val record = realItems.find { it.id.toInt() == item.itemId }
                if (record != null) {
                    mgr.togglePin(record.id)
                    rebuildItems()
                    notifyChanged()
                    updateStatus(if (!record.isPinned) "📌 已置顶" else "取消置顶")
                }
                true
            }
            popupMenu.show()
        }

        // ===== 删除按钮 =====
        btnDelete.setOnClickListener {
            val realItems = items
            if (realItems.isEmpty()) return@setOnClickListener
            val popupMenu = android.widget.PopupMenu(this, btnDelete)
            for (r in realItems) {
                popupMenu.menu.add(0, r.id.toInt(), 0, "🗑️ ${r.instruction.take(18)}")
            }
            popupMenu.menu.add(0, -1, realItems.size, "⚠️ 删除全部（${realItems.size}条）")
            popupMenu.setOnMenuItemClickListener { item ->
                if (item.itemId == -1) {
                    mgr.clearAll()
                    currentMagicPrompt = null
                    rebuildItems()
                    notifyChanged()
                    updateStatus("🗑️ 已删除全部记录")
                } else {
                    mgr.removeRecord(item.itemId.toLong())
                    val updated = mgr.getRecords()
                    if (currentMagicPrompt != null && updated.none { it.instruction == currentMagicPrompt }) {
                        currentMagicPrompt = mgr.getActiveInstruction()
                    }
                    rebuildItems()
                    notifyChanged()
                }
                true
            }
            popupMenu.show()
        }

        // 显示在键盘View正上方
        popup.showAtLocation(keyboardView, Gravity.TOP or Gravity.START, 0, -totalHeight)
    }

    // ======================== 魔法编辑模式 ========================

    /** 更新魔法编辑模式状态栏：显示已输入内容 + Rime 当前拼音 */
    private fun updateMagicEditStatus() {
        val comp = rimeEngine.composingText
        val display = magicEditBuffer.toString() + comp
        if (display.isEmpty()) {
            updateStatus("✏️ 输入魔法指令...（按发送键保存）")
        } else {
            updateStatus("✏️ $display")
        }
        // 同步更新候选栏
        val allCands = rimeEngine.getAllCandidates()
        candidateAdapter?.updateData(allCands)
        candidateBar.visibility = if (rimeEngine.isComposing) View.VISIBLE else View.GONE
    }

    /** 进入魔法编辑模式：关闭弹窗，清空缓冲区，等待键盘输入 */
    private fun enterMagicEditMode(mgr: MagicHistoryManager) {
        magicEditMode = true
        magicEditBuffer.clear()
        magicEditMgr = mgr
        updateStatus("✏️ 输入魔法指令...（按发送键保存）")
    }

    /** 退出魔法编辑模式 */
    private fun exitMagicEditMode(save: Boolean = false) {
        if (save && magicEditBuffer.isNotEmpty() && magicEditMgr != null) {
            val text = magicEditBuffer.toString().trim()
            if (text.isNotEmpty()) {
                magicEditMgr!!.addRecord(text)
                currentMagicPrompt = text
                updateStatus("✅ 已保存魔法：${text.take(20)}")
            }
        } else {
            if (magicEditMode) updateStatus("❌ 已取消新增魔法")
        }
        magicEditMode = false
        magicEditBuffer.clear()
        magicEditMgr = null
    }


    /** 保存编辑中的魔法 */
    private fun saveEditing(
        position: Int,
        gridView: GridView,
        mgr: MagicHistoryManager,
        onComplete: () -> Unit
    ) {
        val v = gridView.getChildAt(position - gridView.firstVisiblePosition) ?: return
        val et = v.findViewById<android.widget.EditText?>(R.id.et_magic_edit) ?: return
        val text = et.text.toString().trim()
        val record = try { (gridView.adapter as android.widget.BaseAdapter).getItem(position) as MagicHistoryManager.MagicRecord } catch (_: Exception) { null } ?: return
        val isEmptySlot = (record.id == -999L)

        if (text.isNotEmpty()) {
            if (isEmptySlot) {
                // 空槽输入了新内容 → 新增魔法 + 自动追加空槽（由 rebuildItems 完成）
                mgr.addRecord(text)
                currentMagicPrompt = text
                updateStatus("✅ 已新增魔法：${text.take(20)}")
            } else {
                // 编辑已有魔法
                if (text != record.instruction) {
                    mgr.removeRecord(record.id)
                    mgr.addRecord(text)
                    updateStatus("✅ 已修改魔法：${text.take(20)}")
                }
            }
        }
        onComplete()
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

    /** 简繁切换：通过 Rime 原生 OpenCC 转换（候选词和输出均自动转换） */
    private fun toggleTraditionalSimplified() {
        isTraditional = !isTraditional
        rimeEngine.setTraditional(isTraditional)
        updateStatus(if (isTraditional) "✅ 已切换为繁体输出" else "✅ 已切换为简体输出")
        updateTraditionalButton()
        // 切换后清空当前输入，重新触发候选
        rimeEngine.clear()
        updateCandidateBar()
    }

    /** 候选词选中上屏（Rime 原生已处理简繁转换，直接上屏） */
    private fun commitCandidateText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun updateTraditionalButton() {
        // 更新按钮视觉状态
        if (::btnTraditional.isInitialized) {
            btnTraditional.setTextColor(if (isTraditional) 0xFF81D8D0.toInt() else 0xFF888888.toInt())
            btnTraditional.setBackgroundColor(if (isTraditional) 0x2281D8D0.toInt() else 0x00000000)
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

    // ======================== 键盘切换（Trime 风格）=======================

    /** 左右滑动循环切换全键盘 ↔ T9 */
    private fun toggleBySwipe() {
        // 清除输入状态，防止切换后残留
        rimeEngine.clear()
        t9InputBuffer.clear()
        candidateBar.visibility = View.GONE
        updateStatus("Cesia 已就绪")
        // UI 立即切换，schema reload 放后台
        if (keyboardMode == KeyboardMode.NUMBER) {
            switchToKeyboard(KeyboardMode.QWERTY)
            Thread { rimeEngine.selectSchema("pinyin"); rimeEngine.reload() }.start()
        } else {
            switchToKeyboard(KeyboardMode.NUMBER)
            Thread {
                rimeEngine.selectSchema("t9_pinyin")
                rimeEngine.reload()
                Handler(Looper.getMainLooper()).post { resetNumberKeyboardState() }
            }.start()
        }
    }

    private fun switchToKeyboard(mode: KeyboardMode) {
        // 切换键盘时退出魔法编辑模式
        if (magicEditMode) exitMagicEditMode(save = false)
        // 记录进入符号键盘前的模式，用于返回
        // 只在从非符号键盘进入符号键盘时记录，符号↔符号切换不更新
        if ((mode == KeyboardMode.SYMBOL_CN || mode == KeyboardMode.SYMBOL_EN)
            && keyboardMode != KeyboardMode.SYMBOL_CN && keyboardMode != KeyboardMode.SYMBOL_EN) {
            prevKeyboardMode = keyboardMode
        }
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
        // 切换键盘时清除 T9 相关状态（避免 T9 锁定圆点出现在其他键盘上）
        if (mode == KeyboardMode.NUMBER) {
            // 进入 T9：清除所有非T9输入状态
            isAsciiMode = false
            rimeEngine.setAsciiMode(false)
            isShiftMode = false
            isShiftLocked = false
        } else if (mode == KeyboardMode.QWERTY) {
            // 进入全键盘：如有持久shift锁，恢复 ascii 模式
            if (qwertyShiftLock) {
                isShiftLocked = true
                isAsciiMode = true
                rimeEngine.setAsciiMode(true)
            }
            rimeEngine.clear()
            // 从T9回到QWERTY：T9的shift状态已在进入NUMBER时清除（switchToKeyboard NUMBER分支）
        } else {
            // 进入符号键盘：清除所有输入状态，避免卡住
            rimeEngine.clear()
            t9InputBuffer.clear()
            if (mode == KeyboardMode.NUMBER) {
                // 从T9进入符号：清除T9状态
                isShiftLocked = false
                isShiftMode = false
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
            }
            if (mode == KeyboardMode.QWERTY) {
                // 从全键盘进入符号：清除ascii模式
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
            }
            // 切换到符号键盘时隐藏候选栏
            candidateBar.visibility = View.GONE
            updateStatus("Cesia 已就绪")
        }
        updateShiftIndicator()
        keyboardView.invalidateAllKeys()
        // 符号键盘增加左右3px边距
        val paddingPx = if (mode == KeyboardMode.SYMBOL_CN || mode == KeyboardMode.SYMBOL_EN) {
            (1.5f * resources.displayMetrics.density).toInt() // ≈3px on xhdpi
        } else 0
        keyboardView.setPadding(paddingPx, 0, paddingPx, 0)
    }

    private fun toggleSymbolLanguage() {
        // 在中文符号键盘和英文符号键盘之间切换（不更新 prevKeyboardMode，保持返回原键盘）
        val wasPrev = prevKeyboardMode
        if (keyboardMode == KeyboardMode.SYMBOL_CN) {
            switchToKeyboard(KeyboardMode.SYMBOL_EN)
        } else if (keyboardMode == KeyboardMode.SYMBOL_EN) {
            switchToKeyboard(KeyboardMode.SYMBOL_CN)
        }
        prevKeyboardMode = wasPrev  // 恢复，确保返回键回到符号前原键盘
    }

    private fun toggleSymbolKeyboard() {
        if (keyboardMode == KeyboardMode.SYMBOL_CN || keyboardMode == KeyboardMode.SYMBOL_EN) {
            switchToKeyboard(KeyboardMode.QWERTY)
        } else { switchToKeyboard(KeyboardMode.SYMBOL_CN) }
    }

    private fun toggleNumberKeyboard() {
        if (keyboardMode == KeyboardMode.NUMBER) {
            // T9 → QWERTY：切换 schema 到 pinyin（需要 reload 使新 schema 生效）
            switchToKeyboard(KeyboardMode.QWERTY)
            rimeEngine.selectSchema("pinyin")
            rimeEngine.reload()
            // reload() 会重置 Rime 内部状态，需重新应用 shift 锁定
            if (qwertyShiftLock) {
                isAsciiMode = true
                rimeEngine.setAsciiMode(true)
            }
        } else {
            // QWERTY → T9：切换 schema 到 t9_pinyin（需要 reload 使新 schema 生效）
            switchToKeyboard(KeyboardMode.NUMBER)
            rimeEngine.selectSchema("t9_pinyin")
            rimeEngine.reload()
            resetNumberKeyboardState()  // 清除 T9 shift 状态（已在 switchToKeyboard NUMBER 分支清除）
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

    // 数字键盘长按通过 popupCharacters 走 startLongPressDetection

    private fun resetNumberKeyboardState() {
        isShiftMode = false
        isShiftLocked = false
        t9InputBuffer.clear()
        updateShiftIndicator()
    }

    private fun updateShiftIndicator() {
        // 同步 shift 状态到 KeyboardView
        keyboardView.isShiftLocked = isShiftLocked
        keyboardView.isShiftMode = isShiftMode
        keyboardView.invalidateAllKeys()
    }

    private fun handleShiftKey() {
        if (isShiftLocked) {
            // 锁定状态 → 单击解除锁定，退回正常
            isShiftLocked = false
            if (keyboardMode == KeyboardMode.NUMBER) {
                isShiftMode = false
                commitT9AndClear()
            } else {
                // 全键盘：解除持久锁
                qwertyShiftLock = false
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
                rimeEngine.clear()
            }
        } else if (isShiftActive()) {
            // 临时shift状态 → 单击退回正常
            if (keyboardMode == KeyboardMode.NUMBER) {
                isShiftMode = false
                commitT9AndClear()
            } else {
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
                rimeEngine.clear()
            }
        } else {
            // 正常状态 → 单击临时shift（输入一个字符后自动退回）
            if (keyboardMode == KeyboardMode.NUMBER) {
                isShiftMode = true
            } else {
                isAsciiMode = true
                rimeEngine.setAsciiMode(true)
                rimeEngine.clear()
            }
            isShiftLocked = false
        }
        updateShiftIndicator()
        keyboardView.invalidateAllKeys()
        updateCandidateBar()
    }

    private fun handleShiftLongPress() {
        // 长按 shift：锁定shift
        isShiftLocked = true
        if (keyboardMode == KeyboardMode.NUMBER) {
            isShiftMode = true
        } else {
            // 全键盘长按锁定 = 持久锁（跨键盘切换保持）
            qwertyShiftLock = true
            isAsciiMode = true
            rimeEngine.setAsciiMode(true)
            rimeEngine.clear()
        }
        longPressTriggered = true
        longPressConsumed = false
        updateShiftIndicator()
        keyboardView.invalidateAllKeys()
        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** 当前是否处于 shift 激活状态（T9模式下=isShiftMode，QWERTY模式下=isAsciiMode） */
    private fun isShiftActive(): Boolean {
        return if (keyboardMode == KeyboardMode.NUMBER) isShiftMode else isAsciiMode
    }

    /** 临时shift输入一个字符后自动退回 */
    private fun autoExitShift() {
        if (!isShiftLocked && isShiftActive()) {
            if (keyboardMode == KeyboardMode.NUMBER) {
                isShiftMode = false
            } else {
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
            }
            updateShiftIndicator()
            keyboardView.invalidateAllKeys()
        }
    }

    private fun handleNumberKeyboardKey(primaryCode: Int) {
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
            if (t9Digit != null) {
                t9InputBuffer.append(t9Digit)
                processT9Input()
            } else {
                when (primaryCode) {
                    49 -> {
                        // 1键：Tab
                        sendTabKey()
                    }
                    65292 -> {
                        currentInputConnection?.commitText("，", 1)
                    }
                    12290 -> {
                        currentInputConnection?.commitText("。", 1)
                    }
                    65311 -> {
                        currentInputConnection?.commitText("？", 1)
                    }
                    65281 -> {
                        currentInputConnection?.commitText("！", 1)
                    }
                    else -> {
                        currentInputConnection?.commitText(primaryCode.toChar().toString(), 1)
                    }
                }
            }
        }
        // 短按已完成
    }

    private fun processT9Input() {
        val digits = t9InputBuffer.toString()
        if (digits.isNotEmpty()) {
            // 重建session，输入完整数字串
            rimeEngine.clear()
            rimeEngine.createSession()
            for (d in digits) {
                rimeEngine.processKey(d.toString())
            }
        }
        updateCandidateBar()
    }

    private fun commitT9AndClear() {
        if (t9InputBuffer.isNotEmpty()) {
            if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                val selected = rimeEngine.selectCandidate(0)
                if (selected.isNotEmpty()) {
                    commitCandidateText(selected)
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
        // 返回进入符号键盘前的键盘模式
        val targetMode = prevKeyboardMode
        val wasSymbols = keyboardMode == KeyboardMode.SYMBOL_CN || keyboardMode == KeyboardMode.SYMBOL_EN
        if (wasSymbols) {
            // 进入符号键盘时未曾切换 schema，直接切回即可
            switchToKeyboard(targetMode)
            isAsciiMode = false
            rimeEngine.setAsciiMode(false)
            when (targetMode) {
                KeyboardMode.NUMBER -> {
                    // schema 本来就是 t9_pinyin，只需清状态
                    resetNumberKeyboardState()
                }
                KeyboardMode.QWERTY -> {
                    // schema 本来就是 pinyin，只需清状态
                    rimeEngine.clear()
                    updateCandidateBar()
                }
                else -> updateCandidateBar()
            }
        } else {
            // 非符号键盘场景：默认回QWERTY
            val wasT9 = keyboardMode == KeyboardMode.NUMBER
            switchToKeyboard(KeyboardMode.QWERTY)
            isAsciiMode = false
            rimeEngine.setAsciiMode(false)
            if (wasT9) {
                rimeEngine.selectSchema("pinyin")
                rimeEngine.reload()
            } else {
                rimeEngine.clear()
            }
            updateCandidateBar()
        }
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
                keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                longPressTriggered = true
                longPressConsumed = false
            }
            currentLongPressKey = null
        }.also {
            longPressHandler.postDelayed(it, 500)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        currentLongPressKey = null
        longPressTriggered = false
        longPressConsumed = false
    }

    private fun startSendKeyLongPress() {
        cancelSendKeyLongPress()
        sendKeyRunnable = Runnable {
            sendKeyLongPressTriggered = true
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showClipboardManagerPopup()
        }.also {
            sendKeyHandler.postDelayed(it, 500)
        }
    }

    private fun cancelSendKeyLongPress() {
        sendKeyRunnable?.let { sendKeyHandler.removeCallbacks(it) }
        sendKeyRunnable = null
    }

    private var clipboardSearchEditMode = false
    private var clipboardSearchBuffer = StringBuilder()

    /**
     * 剪贴板管理器弹窗 — 两列风格，支持置顶/删除/搜索/关闭/长按操作
     */
    private fun showClipboardManagerPopup() {
        try {
            val inflater2 = android.view.LayoutInflater.from(this)
            clipboardPopupView = inflater2.inflate(R.layout.popup_clipboard_manager, null)
            val popupView = clipboardPopupView!!
            val gvClipboard = popupView.findViewById<GridView>(R.id.gv_clipboard_items)
            val btnSearch = popupView.findViewById<TextView>(R.id.btn_clipboard_search)
            val tvSearchHint = popupView.findViewById<TextView>(R.id.tv_search_edit_hint)
            val btnClose = popupView.findViewById<TextView>(R.id.btn_clipboard_close)
            val btnDone = popupView.findViewById<TextView>(R.id.btn_clipboard_done)
            val btnPin = popupView.findViewById<TextView>(R.id.btn_clipboard_pin)
            val btnDelete = popupView.findViewById<TextView>(R.id.btn_clipboard_delete)
            val tvEmpty = popupView.findViewById<TextView>(R.id.tv_clipboard_empty)

            // 加载剪贴板历史（持久化 + 系统剪贴板 + 收藏）
            val clipboardMgr = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            loadClipboardHistoryToClassMembers(clipboardMgr)

            // 初始化过滤
            clipboardSearchFilter = ""
            applyClipboardFilter()

            // 搜索按钮：进入搜索编辑模式
            btnSearch.setOnClickListener {
                clipboardSearchEditMode = true
                clipboardSearchBuffer.clear()
                tvSearchHint.text = "✏️ 输入搜索关键词...（按发送键确认）"
                tvSearchHint.visibility = View.VISIBLE
                btnSearch.text = "🔍 点击搜索..."
                btnSearch.setTextColor(0xFF999999.toInt())
            }

            clipboardAdapter = ClipboardAdapter(inflater2, clipboardFilteredItems, this)
            gvClipboard.adapter = clipboardAdapter

            val keyboardWidth = keyboardView.width
            val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels
            val totalHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()

            val popup = PopupWindow(popupView, popupWidth, totalHeight, true)
            popup.isOutsideTouchable = false
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            popup.elevation = 8f
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.setFocusable(false)

            // 单击：插入文本（非空条目）
            gvClipboard.setOnItemClickListener { _, _, position, _ ->
                if (clipboardSearchEditMode) return@setOnItemClickListener
                val item = clipboardFilteredItems.getOrNull(position) ?: return@setOnItemClickListener
                if (item.isEmpty) return@setOnItemClickListener
                currentInputConnection?.commitText(item.text, 1)
                popup.dismiss()
            }

            // 长按：操作菜单（置顶/删除/编辑/分词）
            gvClipboard.setOnItemLongClickListener { _, _, position, _ ->
                if (clipboardSearchEditMode) return@setOnItemLongClickListener true
                val item = clipboardFilteredItems.getOrNull(position) ?: return@setOnItemLongClickListener true
                if (item.isEmpty) return@setOnItemLongClickListener true
                showClipboardItemActions(item, clipboardItems) { loadClipboardHistoryToClassMembers(clipboardMgr); applyClipboardFilter() }
                true
            }

            // 关闭按钮
            btnClose.setOnClickListener {
                if (clipboardSearchEditMode) {
                    clipboardSearchEditMode = false
                    clipboardSearchBuffer.clear()
                    clipboardSearchFilter = ""
                    tvSearchHint.visibility = View.GONE
                    updateClipboardSearchBtn(btnSearch)
                    applyClipboardFilter()
                } else {
                    popup.dismiss()
                }
            }

            // 完成按钮
            btnDone.setOnClickListener { popup.dismiss() }

            // 置顶按钮
            btnPin.setOnClickListener {
                val realItems = clipboardItems.filter { !it.isEmpty }
                if (realItems.isEmpty()) return@setOnClickListener
                val popupMenu = android.widget.PopupMenu(this, btnPin)
                for (r in realItems) {
                    val title = "${if (r.isPinned) "📌 " else "○ "}${r.text.take(18)}"
                    popupMenu.menu.add(0, r.text.hashCode(), 0, title)
                }
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    val target = realItems.find { it.text.hashCode() == menuItem.itemId }
                    if (target != null) {
                        clipboardItems.removeAll { it.text == target.text }
                        clipboardItems.add(0, target.copy(isPinned = !target.isPinned))
                        saveClipboardHistoryFromClassMembers()
                        applyClipboardFilter()
                    }
                    true
                }
                popupMenu.show()
            }

            // 删除按钮
            btnDelete.setOnClickListener {
                val realItems = clipboardItems.filter { !it.isEmpty }
                if (realItems.isEmpty()) return@setOnClickListener
                val popupMenu = android.widget.PopupMenu(this, btnDelete)
                for (r in realItems) {
                    popupMenu.menu.add(0, r.text.hashCode(), 0, "🗑️ ${r.text.take(18)}")
                }
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    val target = realItems.find { it.text.hashCode() == menuItem.itemId }
                    if (target != null) {
                        clipboardItems.removeAll { it.text == target.text }
                        saveClipboardHistoryFromClassMembers()
                        applyClipboardFilter()
                    }
                    true
                }
                popupMenu.show()
            }

            popup.showAtLocation(keyboardView, android.view.Gravity.TOP or android.view.Gravity.START, 0, -totalHeight)

            // 持久化保存
            saveClipboardHistoryFromClassMembers()

        } catch (e: Exception) {
            updateStatus("❌ 剪贴板管理器异常: ${e.message}")
        }
    }

    private fun updateClipboardSearchBtn(btnSearch: TextView) {
        if (clipboardSearchFilter.isNotEmpty()) {
            btnSearch.text = "🔍 $clipboardSearchFilter"
            btnSearch.setTextColor(0xFF1565C0.toInt())
        } else {
            btnSearch.text = "🔍 点击搜索..."
            btnSearch.setTextColor(0xFF999999.toInt())
        }
    }

    private fun loadClipboardHistoryToClassMembers(clipboardMgr: android.content.ClipboardManager?) {
        clipboardItems.clear()
        try {
            // 1. 从 SharedPreferences 读取持久化历史
            val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
            val historyStr = prefs.getString("history", "") ?: ""
            val favStr = prefs.getString("favorites", "") ?: ""
            val favSet = if (favStr.isNotEmpty()) favStr.split("\n").toSet() else emptySet()
            
            // 2. 先加载持久化历史（置顶在前）
            if (historyStr.isNotEmpty()) {
                for (text in historyStr.split("\n")) {
                    if (text.isNotEmpty()) {
                        clipboardItems.add(ClipboardItem(text = text, isPinned = favSet.contains(text)))
                    }
                }
            }
            
            // 3. 再加载系统剪贴板（去重追加）
            if (clipboardMgr?.hasPrimaryClip() == true) {
                val clip = clipboardMgr.primaryClip ?: return
                for (i in 0 until clip.itemCount) {
                    val text = clip.getItemAt(i).text?.toString()?.trim() ?: ""
                    if (text.isNotEmpty() && text.length <= 500 && clipboardItems.none { it.text == text }) {
                        clipboardItems.add(0, ClipboardItem(text = text, isPinned = favSet.contains(text)))
                    }
                }
            }
        } catch (_: Exception) {}
        if (clipboardItems.isEmpty()) {
            clipboardItems.add(ClipboardItem(text = "(剪贴板为空)", isPinned = true, isEmpty = true))
        }
    }

    /** 保存剪贴板历史到 SharedPreferences（全部历史 + 收藏标记） */
    private fun saveClipboardHistoryFromClassMembers() {
        val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
        val allTexts = clipboardItems.filter { !it.isEmpty }.map { it.text }
        val favTexts = clipboardItems.filter { it.isPinned && !it.isEmpty }.map { it.text }
        prefs.edit()
            .putString("history", allTexts.joinToString("\n"))
            .putString("favorites", favTexts.joinToString("\n"))
            .apply()
    }

    private fun showClipboardItemActions(
        item: ClipboardItem,
        allItems: MutableList<ClipboardItem>,
        onUpdate: () -> Unit
    ) {
        val actions = mutableListOf<String>()
        if (!item.isEmpty) {
            actions.add("📋 插入文本")
            actions.add(if (item.isPinned) "📌 取消置顶" else "📌 置顶收藏")
            actions.add(if (clipboardFavorites[item.text] == true) "🔓 解锁删除" else "🔒 锁定防删")
            actions.add("✂️ 分词处理")
            actions.add("✏️ 编辑文本")
            actions.add("🔍 搜索文本")
            actions.add("🗑️ 删除条目")
            actions.add("📤 分享文本")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(item.text.take(30) + if (item.text.length > 30) "…" else "")
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> currentInputConnection?.commitText(item.text, 1) // 插入
                    1 -> { // 置顶
                        allItems.remove(item)
                        val toggled = item.copy(isPinned = !item.isPinned)
                        if (toggled.isPinned) allItems.add(0, toggled) else allItems.add(toggled)
                        updateClipboardFavorites(); onUpdate()
                    }
                    2 -> { // 锁定
                        val key = item.text
                        if (clipboardFavorites[key] == true) clipboardFavorites.remove(key)
                        else clipboardFavorites[key] = true
                        updateClipboardFavorites(); onUpdate()
                    }
                    3 -> { // 分词 — 用空格分词后逐段插入
                        val words = item.text.split(Regex("""[\s,，。；;:：！!？?、]+"""))
                            .filter { it.isNotEmpty() }
                        if (words.size > 1) {
                            currentInputConnection?.commitText(words.joinToString(" "), 1)
                        } else {
                            updateStatus("✂️ 已单段插入")
                            currentInputConnection?.commitText(item.text, 1)
                        }
                    }
                    4 -> { // 编辑
                        showClipboardEditDialog(item.text) { newText ->
                            allItems.remove(item)
                            allItems.add(0, ClipboardItem(text = newText, isPinned = item.isPinned))
                            updateClipboardFavorites(); onUpdate()
                        }
                    }
                    5 -> { // 搜索
                        try {
                            Intent(Intent.ACTION_WEB_SEARCH).apply {
                                putExtra("query", item.text)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(this)
                            }
                        } catch (_: Exception) {
                            updateStatus("❌ 无法启动搜索")
                        }
                    }
                    6 -> { // 删除
                        if (clipboardFavorites[item.text] == false) {
                            allItems.remove(item)
                            updateClipboardFavorites(); onUpdate()
                        } else {
                            updateStatus("⚠️ 已锁定，无法删除")
                        }
                    }
                    7 -> { // 分享
                        try {
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, item.text)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(Intent.createChooser(this, "分享"))
                            }
                        } catch (_: Exception) {
                            updateStatus("❌ 无法启动分享")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()
        try { dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) } catch (_: Exception) {}
        dialog.show()
    }

    private fun showClipboardEditDialog(original: String, onSave: (String) -> Unit) {
        val editText = android.widget.EditText(this).apply {
            setText(original)
            setSelection(original.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("✏️ 编辑文本")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isNotEmpty()) onSave(newText)
                else updateStatus("⚠️ 文本为空，未保存")
            }
            .setNegativeButton("取消", null)
            .create()
        try { dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) } catch (_: Exception) {}
        dialog.show()
    }

    private fun updateClipboardFavorites() {
        val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
        // 持久化：收藏+锁定条目
        val favItems = clipboardHistory.filter { clipboardFavorites[it] == true }
        prefs.edit().putString("favorites", favItems.joinToString("\n")).apply()
    }

    data class ClipboardItem(val text: String, val isPinned: Boolean = false, val isEmpty: Boolean = false)

    private class ClipboardAdapter(
        private val inflater: android.view.LayoutInflater,
        private val items: List<ClipboardItem>,
        private val context: CesiaInputMethod
    ) : android.widget.BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].text.hashCode().toLong()
        override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
            val v = cv ?: inflater.inflate(R.layout.item_clipboard_grid, parent, false)
            val item = items[p]
            val tv = v.findViewById<TextView>(R.id.tv_clipboard_text)
            val tvPin = v.findViewById<TextView>(R.id.tv_clipboard_pin)
            if (item.isEmpty) {
                tv.text = item.text
                tv.setTextColor(0xFF999999.toInt())
                tv.textSize = 13f
                tvPin.visibility = View.GONE
            } else {
                tv.text = if (item.text.length > 80) item.text.take(80) + "…" else item.text
                tv.setTextColor(0xFF333333.toInt())
                tv.textSize = 13f
                tvPin.visibility = if (item.isPinned) View.VISIBLE else View.GONE
            }
            return v
        }
    }

    // ======================== KeyboardView 回调 ========================
    // 参考 Trime 的 CommonKeyboardActionListener.onKey 逻辑：
    // 1. 按键后调用 processKey，不检查返回值
    // 2. 通过 getRimeContext/getRimeStatus 轮询状态更新 UI
    // 3. 退格/空格/回车等控制键优先交给 Rime 处理

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        // 统一长按状态机：每次 onKey 先重置标志，防止跨键泄漏
        val wasLongPressed = longPressTriggered && !longPressConsumed
        longPressTriggered = false
        longPressConsumed = false
        cancelLongPress()
        if (wasLongPressed) {
            return  // 上一次按键的长按被消耗，跳过本次短按
        }

        // ======================== 魔法编辑模式拦截 ========================
        if (magicEditMode) {
            when (primaryCode) {
                // 发送键/回车键：保存魔法并退出编辑模式
                -200, 10 -> {
                    // 先把 Rime 当前 composition 的文字追加到缓冲区
                    val comp = rimeEngine.composingText
                    if (comp.isNotEmpty()) {
                        magicEditBuffer.append(comp)
                        rimeEngine.clear()
                    }
                    exitMagicEditMode(save = true)
                    return
                }
                // 返回键：取消并退出编辑模式
                KeyEvent.KEYCODE_BACK -> {
                    rimeEngine.clear()
                    exitMagicEditMode(save = false)
                    return
                }
                // 退格键：优先删除 Rime composition，其次删除缓冲区
                -5, Keyboard.KEYCODE_DELETE -> {
                    if (rimeEngine.isComposing) {
                        rimeEngine.processKey("BackSpace")
                        updateMagicEditStatus()
                    } else if (magicEditBuffer.isNotEmpty()) {
                        magicEditBuffer.deleteCharAt(magicEditBuffer.length - 1)
                        updateMagicEditStatus()
                    }
                    return
                }
                // 字母键 a-z：走 Rime 引擎，让候选栏正常显示
                in 97..122 -> {
                    rimeEngine.processKey(primaryCode.toChar())
                    updateMagicEditStatus()
                    return
                }
                // 数字键 0-9：T9模式走T9拼音引擎，全键盘模式选词或追加
                in 48..57 -> {
                    if (keyboardMode == KeyboardMode.NUMBER) {
                        // T9模式：数字键直接走Rime引擎（字母输入模式），不走T9 buffer
                        rimeEngine.processKey(primaryCode.toChar())
                        updateMagicEditStatus()
                    } else if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val index = if (primaryCode == 48) 9 else (primaryCode - 49)
                        val cands = rimeEngine.candidates
                        if (index < cands.size) {
                            val selected = rimeEngine.selectCandidate(index)
                            if (selected.isNotEmpty()) {
                                magicEditBuffer.append(selected)
                                rimeEngine.clear()
                            }
                        }
                    } else {
                        magicEditBuffer.append(primaryCode.toChar())
                    }
                    updateMagicEditStatus()
                    return
                }
                // 空格：如果有候选词则选第一个词，否则追加空格
                32 -> {
                    if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val selected = rimeEngine.selectCandidate(0)
                        if (selected.isNotEmpty()) {
                            magicEditBuffer.append(selected)
                            rimeEngine.clear()
                        }
                    } else {
                        magicEditBuffer.append(' ')
                    }
                    updateMagicEditStatus()
                    return
                }
                // 标点符号直接追加
                44, 46, 59, 33, 63, 45, 95, 43, 61, 40, 41, 123, 125, 91, 93, 47, 92, 58, 34, 39, 60, 62, 42, 38, 37, 35, 64, 36, 94, 126, 96, 124 -> {
                    rimeEngine.clear()
                    magicEditBuffer.append(primaryCode.toChar())
                    updateMagicEditStatus()
                    return
                }
                // 中文标点（Unicode）
                65292, 12290, 65307, 65281, 65311, 12289, 65288, 65289, 8220, 8221, 8216, 8217 -> {
                    rimeEngine.clear()
                    magicEditBuffer.append(primaryCode.toChar())
                    updateMagicEditStatus()
                    return
                }
            }
        }

        // ======================== 剪贴板搜索编辑模式拦截 ========================
        if (clipboardSearchEditMode) {
            when (primaryCode) {
                // 发送键/回车键：确认搜索并退出搜索模式
                -200, 10 -> {
                    clipboardSearchFilter = clipboardSearchBuffer.toString()
                    clipboardSearchEditMode = false
                    clipboardPopupView?.findViewById<TextView>(R.id.tv_search_edit_hint)?.visibility = View.GONE
                    updateClipboardSearchBtn(clipboardPopupView?.findViewById<TextView>(R.id.btn_clipboard_search) ?: return)
                    applyClipboardFilter()
                    return
                }
                // 返回键/关闭：取消搜索模式
                KeyEvent.KEYCODE_BACK -> {
                    clipboardSearchEditMode = false
                    clipboardSearchBuffer.clear()
                    clipboardSearchFilter = ""
                    applyClipboardFilter()
                    return
                }
                // 退格：删除搜索缓冲区最后一个字符
                -5, Keyboard.KEYCODE_DELETE -> {
                    if (clipboardSearchBuffer.isNotEmpty()) {
                        clipboardSearchBuffer.deleteCharAt(clipboardSearchBuffer.length - 1)
                    }
                    val display = clipboardSearchBuffer.toString()
                    updateStatus("✏️ ${if (display.isEmpty()) "输入搜索关键词..." else display}")
                    return
                }
                // 字母键 a-z：走 Rime 引擎
                in 97..122 -> {
                    rimeEngine.processKey(primaryCode.toChar())
                    val comp = rimeEngine.composingText
                    val display = clipboardSearchBuffer.toString() + comp
                    updateStatus("✏️ $display")
                    return
                }
                // 数字键 0-9：选词或追加
                in 48..57 -> {
                    if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val index = if (primaryCode == 48) 9 else (primaryCode - 49)
                        val cands = rimeEngine.candidates
                        if (index < cands.size) {
                            val selected = rimeEngine.selectCandidate(index)
                            if (selected.isNotEmpty()) {
                                clipboardSearchBuffer.append(selected)
                                rimeEngine.clear()
                            }
                        }
                    } else {
                        clipboardSearchBuffer.append(primaryCode.toChar())
                    }
                    val display = clipboardSearchBuffer.toString()
                    updateStatus("✏️ $display")
                    return
                }
                // 空格：选首词或追加空格
                32 -> {
                    if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val selected = rimeEngine.selectCandidate(0)
                        if (selected.isNotEmpty()) {
                            clipboardSearchBuffer.append(selected)
                            rimeEngine.clear()
                        }
                    } else {
                        clipboardSearchBuffer.append(' ')
                    }
                    val display = clipboardSearchBuffer.toString()
                    updateStatus("✏️ $display")
                    return
                }
                // 标点追加
                44, 46, 59, 33, 63 -> {
                    rimeEngine.clear()
                    clipboardSearchBuffer.append(primaryCode.toChar())
                    updateStatus("✏️ ${clipboardSearchBuffer}")
                    return
                }
            }
        }

        val ic = currentInputConnection
        val composing = rimeEngine.isComposing
        val hasCands = rimeEngine.hasCandidates
        val cands = rimeEngine.candidates


        when (primaryCode) {

            // ======================== 字母键 a-z ========================
            in 97..122 -> {
                functionalLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                functionalLongPressRunnable = null
                shortPressHandled = true
                if (isAsciiMode) {
                    // Shift模式：短按输出大写字母
                    val out = if (isShiftMode || isShiftLocked) {
                        primaryCode.toChar().uppercaseChar().toString()
                    } else {
                        primaryCode.toChar().toString()
                    }
                    ic?.commitText(out, 1)
                    // QWERTY临时shift：输入一个字母后自动退回中文
                    if (!isShiftLocked && keyboardMode != KeyboardMode.NUMBER) {
                        isAsciiMode = false
                        rimeEngine.setAsciiMode(false)
                        updateShiftIndicator()
                        keyboardView.invalidateAllKeys()
                    }
                } else {
                    rimeEngine.processKey(primaryCode.toChar())
                    updateCandidateBar()
                }
            }

            // ======================== 数字键区域 (0-9) ========================
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
                                commitCandidateText(selected)
                            } else { commitAndClear() }
                        } else {
                            ic?.commitText(primaryCode.toChar().toString(), 1)
                            autoExitShift()
                        }
                    } else {
                        ic?.commitText(primaryCode.toChar().toString(), 1)
                        autoExitShift()
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
                // 优先检查是否有选中文本
                val sel = ic?.getSelectedText(0)
                if (sel != null && sel.isNotEmpty()) {
                    deleteSelectionOrChar()
                    return
                }
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
                        deleteSelectionOrChar()
                    }
                } else if (isAsciiMode) {
                    deleteSelectionOrChar()
                } else {
                    val wasComposing = rimeEngine.isComposing
                    val handled = rimeEngine.processKey("BackSpace")
                    if (!handled) {
                        deleteSelectionOrChar()
                    }
                    if (wasComposing && !rimeEngine.isComposing) {
                        resetToIdle()
                    } else {
                        updateCandidateBar()
                    }
                }
            }

            // ======================== 回车键（只换行，不发送）=======================
            10, Keyboard.KEYCODE_DONE -> {
                shortPressHandled = true  // 阻止长按撤销与短按换行同时触发
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
                    // 只发送换行，不触发发送动作
                    ic?.commitText("\n", 1)
                }
            }

            // ======================== Shift 键（QWERTY -1 / T9 -104 统一行为）=======================
            -1 -> { shortPressHandled = true; handleShiftKey() }

            // ======================== 符号切换（符）=======================
            KEYCODE_SWITCH_SYMBOL -> toggleSymbolKeyboard()

            // ======================== 符号语言切换（中英符号）=======================
            KEYCODE_SWITCH_SYMBOL_LANG -> toggleSymbolLanguage()

            // ======================== 数字切换（123）=======================
            KEYCODE_SWITCH_NUMBER -> toggleNumberKeyboard()
            KEYCODE_CONTROL -> handleControlKey()
            KEYCODE_SHIFT -> { shortPressHandled = true; handleShiftKey() }

            // ======================== 剪贴板功能键 ========================
            -108 -> { // 全选（短按），长按=粘贴
                clipboardPasteRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                clipboardPasteRunnable = null
                shortPressHandled = true
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }
            -109 -> { // 复制（短按），长按=剪切
                clipboardCutRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                clipboardCutRunnable = null
                shortPressHandled = true
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
            }

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
                // 标点上屏后清空候选栏和状态栏
                rimeEngine.clear()
                if (keyboardMode == KeyboardMode.NUMBER) t9InputBuffer.clear()
                updateCandidateBar()
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
        // 功能键长按检测（仅 QWERTY 中文模式，且 Rime 不在 composing 状态）
        // 注意：功能键长按(500ms)优先于 popupCharacters 长按(400ms)
        // 功能键长按注册后，跳过 popupCharacters 长按，避免冲突
        var skipPopupLongPress = false
        if (!isAsciiMode && primaryCode in 97..122 && keyboardMode == KeyboardMode.QWERTY && !rimeEngine.isComposing) {
            if (getFunctionalLongAction(primaryCode) != null) {
                skipPopupLongPress = true
                functionalLongPressRunnable = Runnable {
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
        // 2-9 键长按已通过 popupCharacters → startLongPressDetection 统一处理
        // Shift 键长按检测（锁定shift）
        // Shift 键长按检测（T9=-104 / QWERTY=-1 统一）
        if (primaryCode == KEYCODE_SHIFT || primaryCode == -1) {
            shiftLongPressRunnable = Runnable {
                if (!shortPressHandled) {
                    handleShiftLongPress()
                }
            }.also {
                Handler(Looper.getMainLooper()).postDelayed(it, 400)
            }
        }
        // 剪贴板键长按：-108=粘贴，-109=剪切
        if (primaryCode == -108) {
            clipboardPasteRunnable = Runnable {
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    currentInputConnection?.performContextMenuAction(android.R.id.paste)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(clipboardPasteRunnable!!, 400)
        }
        if (primaryCode == -109) {
            clipboardCutRunnable = Runnable {
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    currentInputConnection?.performContextMenuAction(android.R.id.cut)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(clipboardCutRunnable!!, 400)
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
        // 回车键长按：撤销 Ctrl+Z
        if (primaryCode == 10 || primaryCode == Keyboard.KEYCODE_DONE) {
            enterLongPressRunnable = Runnable {
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    sendCtrlKey(KeyEvent.KEYCODE_Z)
                }
            }.also {
                Handler(Looper.getMainLooper()).postDelayed(it, 400)
            }
        }
        if (primaryCode == -200) {
            startSendKeyLongPress()
        }
    }

    override fun onRelease(primaryCode: Int) {
        cancelLongPress()
        functionalLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        functionalLongPressRunnable = null
        clipboardPasteRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        clipboardPasteRunnable = null
        clipboardCutRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        clipboardCutRunnable = null
        shiftLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        shiftLongPressRunnable = null
        enterLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        enterLongPressRunnable = null
        cancelSendKeyLongPress()
        backspaceRunnable?.let { backspaceHandler.removeCallbacks(it) }
        backspaceRunnable = null
    }

    override fun onText(text: CharSequence?) {
        cancelLongPress()
        if (clipboardSearchEditMode && text != null) {
            // 剪贴板搜索编辑模式：追加选词到搜索缓冲区
            clipboardSearchBuffer.append(text)
            rimeEngine.clear()
            updateStatus("✏️ ${clipboardSearchBuffer}")
            return
        }
        if (magicEditMode && text != null) {
            // 魔法编辑模式：如果 Rime 正在 composing，追加选词到缓冲区并清空 Rime
            if (rimeEngine.isComposing) {
                magicEditBuffer.append(text)
                rimeEngine.clear()
                updateMagicEditStatus()
            } else {
                // 非 composing 状态直接追加
                magicEditBuffer.append(text)
                updateMagicEditStatus()
            }
            return
        }
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
