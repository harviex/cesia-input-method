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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.TimeUnit
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
import com.cesia.input.ai.AIEngine
import com.cesia.input.ai.LocalModeManager
import com.cesia.input.ai.LocalModeToggleHelper
import com.cesia.input.engine.TypelessEngine
import com.cesia.input.engine.rime.RimeEngine
import com.cesia.input.model.ModelManager
import com.cesia.input.stats.PolishStatsManager
import com.cesia.input.stats.MagicHistoryManager
import com.cesia.input.voice.VoiceEngine
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

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
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var modelManager: ModelManager
    private lateinit var aiEngine: AIEngine

    // ======================== 语音/润色用户选择 ========================
    // 识别后端（用户选择或自动默认）
    enum class VoiceChoice { CLOUD_GROQ, LOCAL_WHISPER, GOOGLE }
    enum class PolishChoice { CLOUD_OPENROUTER, LOCAL_AI, OFF }

    private var currentVoiceChoice: VoiceChoice? = null  // null = 未通过长按面板选择过
    private var currentPolishChoice: PolishChoice = PolishChoice.OFF
    private var longPressActive = false

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
    private var shortPressHandled = false  // 当前按键是否已处理短按（防止长按重复触发）

    // 语音引擎协程作用域
    private val voiceEngineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // === 三个键盘的 shift 状态完全独立 ===
    private var qwertyShiftLocked = false   // 全键盘 shift 锁定
    private var qwertyShiftTemp = false     // 全键盘临时 shift（单字符后自动退出）
    private var t9ShiftLocked = false       // T9 shift 锁定
    private var t9ShiftTemp = false         // T9 临时 shift（单字符后自动退出）
    private var symbolShiftLocked = false   // 符号键盘 shift 锁定
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
    private var sendButtonGlowRunnable: Runnable? = null
    private var sendButtonGlowing = false

    // 魔法书键长按检测
    private var magicBookLongPressTriggered = false
    private var magicBookHandler = Handler(Looper.getMainLooper())
    private var magicBookRunnable: Runnable? = null
    private var magicBookGlowRunnable: Runnable? = null
    private var magicBookGlowing = false

    // 魔法修改按键发光状态
    private var magicModeGlowing = false

    // 正体字按键发光状态
    private var traditionalGlowing = false

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

    // 本地化切换按钮
    private lateinit var localModeToggleHelper: LocalModeToggleHelper

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

    // ======================== OpenCC 简繁转换（从 assets 加载）=======================
    private var SIMP_TO_TRAD: Map<Char, Char>? = null
    private var SIMP_TO_TRAD_PHRASES: Map<String, String>? = null

    /** 从 assets 加载 OpenCC 简繁映射表（懒加载） */
    private fun ensureOpenCCLoaded() {
        if (SIMP_TO_TRAD != null) return
        try {
            val json = assets.open("opencc_s2t.json").bufferedReader().use { it.readText() }
            val obj = org.json.JSONObject(json)
            val charObj = obj.getJSONObject("char_map")
            val phraseObj = obj.getJSONObject("phrase_map")
            val charMap = mutableMapOf<Char, Char>()
            val phraseMap = mutableMapOf<String, String>()
            for (key in charObj.keys()) {
                if (key.length == 1) {
                    charMap[key[0]] = charObj.getString(key)[0]
                }
            }
            for (key in phraseObj.keys()) {
                phraseMap[key] = phraseObj.getString(key)
            }
            SIMP_TO_TRAD = charMap
            SIMP_TO_TRAD_PHRASES = phraseMap
        } catch (e: Exception) {
            SIMP_TO_TRAD = emptyMap()
            SIMP_TO_TRAD_PHRASES = emptyMap()
        }
    }

    /** 简→繁转换：先匹配词组（最长4字），再逐字替换 */
    private fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        ensureOpenCCLoaded()
        val charMap = SIMP_TO_TRAD ?: emptyMap()
        val phraseMap = SIMP_TO_TRAD_PHRASES ?: emptyMap()
        val sb = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            var matched = false
            for (len in minOf(4, text.length - i) downTo 2) {
                val sub = text.substring(i, i + len)
                val trad = phraseMap[sub]
                if (trad != null) {
                    sb.append(trad)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                val ch = text[i]
                sb.append(charMap[ch] ?: ch)
                i++
            }
        }
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

        // 本地化切换按钮
        val btnLocalModeView = view.findViewById<TextView>(R.id.btn_local_mode)
        localModeToggleHelper = LocalModeToggleHelper(
            this, LocalModeManager(this), ModelManager(this)
        )
        localModeToggleHelper.bind(btnLocalModeView)

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
        // 滑动早期趋势通知：提前取消长按 runnable，防止副字符功能误触发
        keyboardView.onSwipeEarly = { cancelAllLongPressActions() }

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

        // 初始化语音引擎和模型管理器
        modelManager = ModelManager(this)
        voiceEngine = VoiceEngine(this)
        aiEngine = AIEngine(this)
        // 根据模式和模型可用性设置默认语音后端
        updateVoiceBackend()

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

        // 简繁转换：繁体模式下候选词显示繁体
        val displayCands = if (isTraditional) allCands.map { toTraditional(it) } else allCands

        // 更新候选词列表
        candidateAdapter?.updateData(displayCands)
        btnCandidateExpand.visibility = if (allCands.size > 4) View.VISIBLE else View.GONE

        // 更新展开面板
        if (isPanelExpanded) {
            tvPanelComposing.text = pinyin
            val allCandsPanel = rimeEngine.getAllCandidates()
            val displayPanel = if (isTraditional) allCandsPanel.map { toTraditional(it) } else allCandsPanel
            panelAdapter?.clear()
            panelAdapter?.addAll(displayPanel)
            panelAdapter?.notifyDataSetChanged()
        }
    }

    // ======================== 识别后端可用性检测 ========================

    /**
     * 检测单个后端的真实可用性
     * 返回 Triple(是否可用, 错误信息, 详细信息)
     */

    /** 检测 Whisper：模型存在 + 能加载 */
    private fun checkWhisperAvailable(): Pair<Boolean, String?> {
        if (!modelManager.hasVoiceModel()) {
            return false to "模型未安装"
        }
        return try {
            val modelFile = modelManager.getInstalledAiModelFile()
            if (modelFile?.exists() == true) {
                true to "模型已就绪"
            } else {
                false to "模型文件不存在"
            }
        } catch (e: Exception) {
            false to "模型检查失败: ${e.message}"
        }
    }

    /** 检测 Google：框架存在 + 能实际录音并返回结果 */
    private fun checkGoogleAsync(callback: (Boolean, String?) -> Unit) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            callback(false, "Google 语音框架不可用")
            return
        }
        try {
            val testRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            var finished = false
            testRecognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (finished) return
                    finished = true
                    try { testRecognizer.destroy() } catch (_: Exception) {}
                    callback(false, "Google 测试录音失败 (错误码: $error)")
                }
                override fun onResults(bundle: Bundle?) {
                    if (finished) return
                    finished = true
                    try { testRecognizer.destroy() } catch (_: Exception) {}
                    val matches = bundle?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        callback(true, "Google 服务正常")
                    } else {
                        // 框架通了但没收到结果（可能是静音），也算连通
                        callback(true, "Google 连通（静音无输入）")
                    }
                }
                override fun onPartialResults(bundle: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // 只录 2 秒做测试
                putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_MILLIS", 2000L)
                putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_MILLIS", 2000L)
                putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1000L)
            }
            testRecognizer.startListening(intent)

            // 最多等 3 秒
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!finished) {
                    finished = true
                    try { testRecognizer.destroy() } catch (_: Exception) {}
                    callback(false, "Google 测试超时（无响应）")
                }
            }, 3000)
        } catch (e: Exception) {
            callback(false, "Google 测试启动失败: ${e.message}")
        }
    }

    /** 检测 Groq：发轻量 HTTP 请求测试连通性 */
    private fun checkGroqAsync(callback: (Boolean, String?) -> Unit) {
        val key = getGroqApiKey()
        if (key.isEmpty()) {
            callback(false, "未配置 API Key")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                // 用 models 端点做轻量检测
                val request = okhttp3.Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $key")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    when (response.code) {
                        200 -> {
                            // 验证响应里有 data 字段
                            val body = response.body?.string() ?: ""
                            if (body.contains("\"data\"")) {
                                callback(true, "Groq 连通")
                            } else {
                                callback(false, "Groq API 返回异常")
                            }
                        }
                        401 -> callback(false, "API Key 无效")
                        else -> callback(false, "Groq 返回 ${response.code}")
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                withContext(Dispatchers.Main) { callback(false, "无法连接 Groq（网络问题）") }
            } catch (e: java.net.SocketTimeoutException) {
                withContext(Dispatchers.Main) { callback(false, "Groq 连接超时") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(false, "Groq 检测失败: ${e.message}") }
            }
        }
    }

    /**
     * 综合检测并返回可用后端列表（按优先级：Whisper > Google > Groq）
     * 用于单击时的快速判断
     */
    private fun detectAvailableBackends(): List<VoiceChoice> {
        val available = mutableListOf<VoiceChoice>()
        val (whisperOk, _) = checkWhisperAvailable()
        if (whisperOk) available.add(VoiceChoice.LOCAL_WHISPER)
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            available.add(VoiceChoice.GOOGLE)
        }
        if (getGroqApiKey().isNotEmpty()) {
            available.add(VoiceChoice.CLOUD_GROQ)
        }
        return available
    }

    // ======================== 按钮监听 ========================

    private fun setupButtonListeners() {
        // 语音按钮：短按开始录音，长按弹出选择面板
        longPressActive = false
        micButton.setOnClickListener {
            if (longPressActive) {
                longPressActive = false
                return@setOnClickListener
            }
            if (!isRecording && !isWaitingForChoice) {
                // 长按面板设置过识别方式，直接用选择的方式
                if (currentVoiceChoice != null) {
                    startRecordingWithChoice(currentVoiceChoice!!, currentPolishChoice)
                } else {
                    // 检测可用后端
                    val available = detectAvailableBackends()
                    if (available.isEmpty()) {
                        // 全部不可用，弹出选择面板
                        updateStatus("⚠️ 无可用识别后端，请选择")
                        showVoicePolishSelector()
                    } else {
                        // 有可用后端，按优先级选最优
                        val best = available.first()
                        startRecordingWithChoice(best, currentPolishChoice)
                    }
                }
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
            } else {
                longPressActive = true
                showVoicePolishSelector()
                true
            }
        }

        btnMicAi.setOnClickListener { onAiPlusSelected() }
        btnMicNoAi.setOnClickListener { onAiCrossSelected() }
        btnSettings.setOnClickListener { showSettings() }
        localModeToggleHelper.setupListener()
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
        btnClipboard.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    magicBookLongPressTriggered = false
                    startMagicBookLongPress()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    cancelMagicBookLongPress()
                    if (!magicBookLongPressTriggered) {
                        v.performClick()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelMagicBookLongPress()
                    true
                }
                else -> false
            }
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
        btnSend.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sendKeyLongPressTriggered = false
                    startSendKeyLongPress()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    cancelSendKeyLongPress()
                    if (!sendKeyLongPressTriggered) {
                        v.performClick()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelSendKeyLongPress()
                    true
                }
                else -> false
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

        // 高亮按钮表示正在录音 + 脉冲发光动画
        magicIsWaitingForVoice = true
        magicModeGlowing = true
        btnMagic.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF81D8D0.toInt())
        btnMagic.setTextColor(0xFFFFFFFF.toInt())
        btnMagic.elevation = 6f
        startMagicButtonGlow()

        updateStatus("🎤 请说出修改指令...（再次点击✨停止）")
        setStatusDot("recording")
        startVoiceWave()
        isRecording = true
        micButton.text = "🎤 说话"
        typelessEngine?.startListening(continuous = true)
    }

    private fun resetMagicHighlight() {
        magicIsWaitingForVoice = false
        magicModeGlowing = false
        stopMagicButtonGlow()
        try {
            btnMagic.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE0E0E0.toInt())
            btnMagic.setTextColor(0xFF888888.toInt())
            btnMagic.elevation = 0f
        } catch (_: Exception) {}
    }

    private fun startMagicButtonGlow() {
        val pulse = android.view.animation.ScaleAnimation(
            1.0f, 1.15f, 1.0f, 1.15f,
            android.view.animation.ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 600
            repeatMode = android.view.animation.ScaleAnimation.REVERSE
            repeatCount = android.view.animation.ScaleAnimation.INFINITE
        }
        btnMagic.startAnimation(pulse)
    }

    private fun stopMagicButtonGlow() {
        btnMagic.clearAnimation()
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

        val btnAdd = popupView.findViewById<TextView>(R.id.btn_add_magic)
        val btnPin = popupView.findViewById<TextView>(R.id.btn_pin_manage)
        val btnDelete = popupView.findViewById<TextView>(R.id.btn_delete_manage)
        val btnClose = popupView.findViewById<TextView>(R.id.btn_close_magic)

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
                    val prefix = if (isActive) "✓ " else if (record.isPinned) "⤒ " else ""
                    val displayText = "${prefix}${record.instruction}"
                    if (record.isPinned && !isActive) {
                        // 置顶但未激活：置顶标志加粗
                        val spannable = android.text.SpannableString(displayText)
                        spannable.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            0, prefix.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        tv.text = spannable
                        tv.setTextColor(0xFF333333.toInt())
                        tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    } else {
                        tv.text = displayText
                        tv.setTextColor(if (isActive) 0xFF81D8D0.toInt() else 0xFF333333.toInt())
                        tv.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    }
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
                val title = "${if (r.isPinned) "⤒ " else "○ "}${r.instruction.take(18)}"
                popupMenu.menu.add(0, r.id.toInt(), 0, title)
            }
            popupMenu.setOnMenuItemClickListener { item ->
                val record = realItems.find { it.id.toInt() == item.itemId }
                if (record != null) {
                    mgr.togglePin(record.id)
                    rebuildItems()
                    notifyChanged()
                    updateStatus(if (!record.isPinned) "⤒ 已置顶" else "取消置顶")
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
            // 全部删除置顶（order=0）
            popupMenu.menu.add(0, -1, 0, "⊗ 删除全部（${realItems.size}条）")
            for (r in realItems) {
                popupMenu.menu.add(0, r.id.toInt(), 1, "⊗ ${r.instruction.take(18)}")
            }
            popupMenu.setOnMenuItemClickListener { item ->
                if (item.itemId == -1) {
                    val pinned = realItems.filter { it.isPinned }
                    mgr.clearAll()
                    // 重新添加置顶项（不改变顺序）
                    for (r in pinned) {
                        mgr.addRecord(r.instruction)
                    }
                    currentMagicPrompt = null
                    rebuildItems()
                    notifyChanged()
                    updateStatus("⊗ 已删除全部（保留置顶）")
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

        popup.setOnDismissListener {
            cancelSendKeyLongPress()
            cancelMagicBookLongPress()
        }
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
        updateStatus(if (isTraditional) "✅ 已切换为繁体输出" else "✅ 已切换为简体输出")
        updateTraditionalButton()
        // 切换后重新触发候选（Rime stub 不支持 setOption，用本地 OpenCC 转换）
        updateCandidateBar()
    }

    /** 候选词选中上屏：根据当前简繁状态做转换 */
    private fun commitCandidateText(text: String) {
        val output = if (isTraditional) toTraditional(text) else text
        currentInputConnection?.commitText(output, 1)
    }

    private fun updateTraditionalButton() {
        // 更新按钮视觉状态
        if (::btnTraditional.isInitialized) {
            btnTraditional.setTextColor(if (isTraditional) 0xFF81D8D0.toInt() else 0xFF888888.toInt())
            btnTraditional.setBackgroundColor(if (isTraditional) 0x2281D8D0.toInt() else 0x00000000)
            if (isTraditional && !traditionalGlowing) {
                traditionalGlowing = true
                val pulse = android.view.animation.ScaleAnimation(
                    1.0f, 1.12f, 1.0f, 1.12f,
                    android.view.animation.ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.ScaleAnimation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 800
                    repeatMode = android.view.animation.ScaleAnimation.REVERSE
                    repeatCount = android.view.animation.ScaleAnimation.INFINITE
                }
                btnTraditional.startAnimation(pulse)
            } else if (!isTraditional && traditionalGlowing) {
                traditionalGlowing = false
                btnTraditional.clearAnimation()
            }
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

    // ======================== 语音后端自动切换 ========================

    /**
     * 根据 LocalModeManager 模式和模型可用性自动选择语音后端
     *
     * 规则：
     * - 云端模式（CLOUD_FREE / CLOUD_PAID）→ Groq API（需要 Groq Key）
     * - 本地模式 + 已下载 Whisper 模型 → 本地 Whisper
     * - 本地模式 + 未下载模型 → 回退到 Google 语音识别（系统自带）
     */
    private fun updateVoiceBackend() {
        val modePrefs = getSharedPreferences("cesia_local_mode", Context.MODE_PRIVATE)
        val modeName = modePrefs.getString("run_mode", LocalModeManager.RunMode.LOCAL.name)
            ?: LocalModeManager.RunMode.LOCAL.name
        val mode = try { LocalModeManager.RunMode.valueOf(modeName) }
            catch (_: Exception) { LocalModeManager.RunMode.LOCAL }
        val hasGroqKey = getGroqApiKey().isNotEmpty()
        val hasLocalModel = modelManager.hasVoiceModel()

        when (mode) {
            LocalModeManager.RunMode.CLOUD_FREE, LocalModeManager.RunMode.CLOUD_PAID -> {
                if (hasGroqKey) {
                    voiceEngine.setBackend(VoiceEngine.Backend.CLOUD_GROQ)
                    Log.i("Cesia", "语音后端: Groq 云端")
                } else {
                    // 云端模式但没有 Groq Key，回退到 Google
                    voiceEngine.setBackend(VoiceEngine.Backend.LOCAL_WHISPER)
                    Log.i("Cesia", "语音后端: Google (Groq Key 未设置，回退)")
                }
            }
            LocalModeManager.RunMode.LOCAL -> {
                if (hasLocalModel) {
                    voiceEngine.setBackend(VoiceEngine.Backend.LOCAL_WHISPER)
                    Log.i("Cesia", "语音后端: 本地 Whisper")
                } else {
                    // 本地模式但没有模型，回退到 Google
                    voiceEngine.setBackend(VoiceEngine.Backend.LOCAL_WHISPER)
                    Log.i("Cesia", "语音后端: Google (本地模型未下载，回退)")
                }
            }
        }
    }

    /** 读取 Groq API Key */
    private fun getGroqApiKey(): String {
        val prefs = getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
        return prefs.getString("groq_api_key", "") ?: ""
    }

    private fun getOpenRouterApiKey(): String {
        val prefs = getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
    }

    /**
     * 获取当前语音后端的显示名称
     */
    fun getVoiceBackendName(): String {
        val modePrefs = getSharedPreferences("cesia_local_mode", Context.MODE_PRIVATE)
        val modeName = modePrefs.getString("run_mode", LocalModeManager.RunMode.LOCAL.name)
            ?: LocalModeManager.RunMode.LOCAL.name
        val mode = try { LocalModeManager.RunMode.valueOf(modeName) }
            catch (_: Exception) { LocalModeManager.RunMode.LOCAL }
        val hasGroqKey = getGroqApiKey().isNotEmpty()
        val hasLocalModel = modelManager.hasVoiceModel()

        return when (mode) {
            LocalModeManager.RunMode.CLOUD_FREE, LocalModeManager.RunMode.CLOUD_PAID -> {
                if (hasGroqKey) "Groq 云端" else "Google (回退)"
            }
            LocalModeManager.RunMode.LOCAL -> {
                if (hasLocalModel) "本地 Whisper" else "Google (回退)"
            }
        }
    }

    // ======================== 长按选择面板 ========================

    /**
     * 长按语音键弹出的选择面板
     * 用户分别选择识别后端和润色后端
     */
    /**
     * 长按语音键弹出的选择面板
     * 用户选择识别后端和润色后端，点确认后立即开始录音
     */
    private fun showVoicePolishSelector() {
        try {
            val windowToken = micButton?.windowToken
            if (windowToken == null) {
                updateStatus("⚠️ 输入法视图未就绪，请重试")
                Log.e("Cesia", "showVoicePolishSelector: windowToken is null")
                return
            }

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_polish_selector, null)

            // Loading 视图
            val loadingContainer = dialogView.findViewById<LinearLayout>(R.id.loading_container)
            val tvLoadingText = dialogView.findViewById<TextView>(R.id.tv_loading_text)

            // 识别选项
            val btnGroq = dialogView.findViewById<TextView>(R.id.btn_voice_groq)
            val btnWhisper = dialogView.findViewById<TextView>(R.id.btn_voice_whisper)
            val btnGoogle = dialogView.findViewById<TextView>(R.id.btn_voice_google)

            // 润色选项
            val btnPolishCloud = dialogView.findViewById<TextView>(R.id.btn_polish_cloud)
            val btnPolishLocal = dialogView.findViewById<TextView>(R.id.btn_polish_local)
            val btnPolishOff = dialogView.findViewById<TextView>(R.id.btn_polish_off)

            // 提示文字
            val tvHint = dialogView.findViewById<TextView>(R.id.tv_voice_hint)

            // 确认/取消按钮
            val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_voice_confirm)
            val btnCancel = dialogView.findViewById<TextView>(R.id.btn_voice_cancel)

            // ====== 初始状态：静态检测 + 显示 loading ======
            val groqKey = getGroqApiKey()
            val hasGroqKey = groqKey.isNotEmpty()
            val hasWhisperModel = modelManager.hasVoiceModel()
            val googleFrameworkOk = try {
                android.speech.SpeechRecognizer.isRecognitionAvailable(this)
            } catch (_: Exception) { false }
            val orKey = getOpenRouterApiKey()
            val hasOrKey = orKey.isNotEmpty()
            val hasAiModel = modelManager.hasAiModel()

            // 初始状态：先按静态检测设置可用性
            setOptionState(btnGroq, hasGroqKey, "☁️ Groq 云端", "☁️ Groq (需设置 API Key)")
            setOptionState(btnWhisper, hasWhisperModel, "📱 本地 Whisper", "📱 Whisper (需下载模型)")
            setOptionState(btnGoogle, googleFrameworkOk, "🌍 Google", "🌍 Google (不可用)")
            setOptionState(btnPolishCloud, hasOrKey, "☁️ 云端润色", "☁️ 云端 (需设置 API Key)")
            setOptionState(btnPolishLocal, hasAiModel, "📱 本地润色", "📱 本地 (需下载模型)")
            setOptionState(btnPolishOff, true, "❌ 关闭润色", null)

            // 当前选中高亮
            highlightSelected(btnGroq, currentVoiceChoice == VoiceChoice.CLOUD_GROQ)
            highlightSelected(btnWhisper, currentVoiceChoice == VoiceChoice.LOCAL_WHISPER)
            highlightSelected(btnGoogle, currentVoiceChoice == VoiceChoice.GOOGLE)
            highlightSelected(btnPolishCloud, currentPolishChoice == PolishChoice.CLOUD_OPENROUTER)
            highlightSelected(btnPolishLocal, currentPolishChoice == PolishChoice.LOCAL_AI)
            highlightSelected(btnPolishOff, currentPolishChoice == PolishChoice.OFF)

            // 延迟提示
            fun updateHint() {
                if (currentVoiceChoice == VoiceChoice.CLOUD_GROQ &&
                    (currentPolishChoice == PolishChoice.CLOUD_OPENROUTER || currentPolishChoice == PolishChoice.LOCAL_AI)) {
                    tvHint.text = "⚠️ 云端识别+润色预计 4~7 秒"
                    tvHint.setTextColor(0xFFFF8800.toInt())
                } else {
                    tvHint.text = ""
                }
            }
            updateHint()

            // ====== 创建 dialog ======
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create()
            dialog.window?.let { window ->
                window.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                window.attributes = window.attributes?.apply { token = windowToken }
            }
            dialog.setOnDismissListener { longPressActive = false }

            // ====== 按钮点击 ======
            btnGroq.setOnClickListener {
                if (hasGroqKey) {
                    currentVoiceChoice = VoiceChoice.CLOUD_GROQ
                    highlightSelected(btnGroq, true); highlightSelected(btnWhisper, false); highlightSelected(btnGoogle, false)
                    updateHint()
                } else {
                    showApiKeyPrompt("Groq", "https://console.groq.com")
                }
            }
            btnWhisper.setOnClickListener {
                if (hasWhisperModel) {
                    currentVoiceChoice = VoiceChoice.LOCAL_WHISPER
                    highlightSelected(btnGroq, false); highlightSelected(btnWhisper, true); highlightSelected(btnGoogle, false)
                    updateHint()
                } else {
                    showModelDownloadPrompt("语音", "whisper-small")
                }
            }
            btnGoogle.setOnClickListener {
                if (googleFrameworkOk) {
                    currentVoiceChoice = VoiceChoice.GOOGLE
                    highlightSelected(btnGroq, false); highlightSelected(btnWhisper, false); highlightSelected(btnGoogle, true)
                    updateHint()
                }
            }
            btnPolishCloud.setOnClickListener {
                if (hasOrKey) {
                    currentPolishChoice = PolishChoice.CLOUD_OPENROUTER
                    highlightSelected(btnPolishCloud, true); highlightSelected(btnPolishLocal, false); highlightSelected(btnPolishOff, false)
                    updateHint()
                } else {
                    showApiKeyPrompt("OpenRouter", "https://openrouter.ai/keys")
                }
            }
            btnPolishLocal.setOnClickListener {
                if (hasAiModel) {
                    currentPolishChoice = PolishChoice.LOCAL_AI
                    highlightSelected(btnPolishCloud, false); highlightSelected(btnPolishLocal, true); highlightSelected(btnPolishOff, false)
                    updateHint()
                } else {
                    showModelDownloadPrompt("AI 润色", "qwen-0.8b")
                }
            }
            btnPolishOff.setOnClickListener {
                currentPolishChoice = PolishChoice.OFF
                highlightSelected(btnPolishCloud, false); highlightSelected(btnPolishLocal, false); highlightSelected(btnPolishOff, true)
                updateHint()
            }

            btnConfirm.setOnClickListener {
                if (currentVoiceChoice == null) {
                    updateStatus("请先选择一种识别方式")
                    return@setOnClickListener
                }
                dialog.dismiss()
                startRecordingWithChoice(currentVoiceChoice!!, currentPolishChoice)
            }
            btnCancel.setOnClickListener { dialog.dismiss() }

            // ====== 显示 dialog ======
            dialog.show()

            // ====== 后台异步真实检测 ======
            // 记录哪些后端需要重新检测
            var pendingChecks = 0
            var whisperRealOk = hasWhisperModel
            var googleRealOk = googleFrameworkOk
            var groqRealOk = hasGroqKey

            fun onCheckComplete() {
                pendingChecks--
                if (pendingChecks > 0) return  // 还有检测在进行中

                // 所有检测完成，更新按钮状态
                loadingContainer.visibility = View.GONE

                // 更新识别按钮
                if (hasGroqKey && !groqRealOk) {
                    setOptionState(btnGroq, false, "☁️ Groq 云端", "☁️ Groq (连接失败)")
                }
                if (hasWhisperModel && !whisperRealOk) {
                    setOptionState(btnWhisper, false, "📱 本地 Whisper", "📱 Whisper (加载失败)")
                }
                if (googleFrameworkOk && !googleRealOk) {
                    setOptionState(btnGoogle, false, "🌍 Google", "🌍 Google (连接失败)")
                }

                // 如果当前选中的后端实际不可用，提示用户
                when (currentVoiceChoice) {
                    VoiceChoice.CLOUD_GROQ -> if (!groqRealOk) updateStatus("⚠️ Groq 连接失败，请选择其他识别方式")
                    VoiceChoice.LOCAL_WHISPER -> if (!whisperRealOk) updateStatus("⚠️ Whisper 加载失败，请选择其他识别方式")
                    VoiceChoice.GOOGLE -> if (!googleRealOk) updateStatus("⚠️ Google 连接失败，请选择其他识别方式")
                    null -> {}
                }
            }

            // 1. 检测 Whisper（同步，很快）
            if (hasWhisperModel) {
                pendingChecks++
                CoroutineScope(Dispatchers.IO).launch {
                    val (ok, err) = checkWhisperAvailable()
                    whisperRealOk = ok
                    withContext(Dispatchers.Main) {
                        if (!ok) {
                            setOptionState(btnWhisper, false, "📱 本地 Whisper", "📱 Whisper ($err)")
                        }
                        onCheckComplete()
                    }
                }
            }

            // 2. 检测 Google（异步，需要录音测试）
            if (googleFrameworkOk) {
                pendingChecks++
                loadingContainer.visibility = View.VISIBLE
                tvLoadingText.text = "正在检测 Google 语音识别..."
                checkGoogleAsync { ok, err ->
                    googleRealOk = ok
                    Handler(Looper.getMainLooper()).post {
                        if (ok) {
                            // 检测通过，确保按钮亮起
                            setOptionState(btnGoogle, true, "🌍 Google", "🌍 Google (不可用)")
                        } else {
                            setOptionState(btnGoogle, false, "🌍 Google", "🌍 Google ($err)")
                        }
                        onCheckComplete()
                    }
                }
            }

            // 3. 检测 Groq（异步 HTTP）
            if (hasGroqKey) {
                pendingChecks++
                if (pendingChecks > 0) {
                    loadingContainer.visibility = View.VISIBLE
                    tvLoadingText.text = "正在检测 Groq 连通性..."
                }
                checkGroqAsync { ok, err ->
                    groqRealOk = ok
                    Handler(Looper.getMainLooper()).post {
                        if (!ok) {
                            setOptionState(btnGroq, false, "☁️ Groq 云端", "☁️ Groq ($err)")
                        }
                        onCheckComplete()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("Cesia", "选择面板异常", e)
            updateStatus("⚠️ 选择面板异常: ${e.message}")
        }
    }

    /** 设置选项可用/不可用状态 */
    private fun setOptionState(btn: TextView, available: Boolean, enabledText: String, disabledText: String?) {
        btn.isEnabled = available
        btn.text = if (available) enabledText else (disabledText ?: enabledText)
        btn.alpha = if (available) 1.0f else 0.4f
    }

    /** 高亮当前选中 */
    private fun highlightSelected(btn: TextView, selected: Boolean) {
        if (!btn.isEnabled) return
        if (selected) {
            btn.setBackgroundColor(0xFF81D8D8.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
        } else {
            btn.setBackgroundColor(0xFFF0F0F0.toInt())
            btn.setTextColor(0xFF333333.toInt())
        }
    }

    /** 显示 API Key 设置引导 */
    private fun showApiKeyPrompt(name: String, url: String) {
        val windowToken = micButton?.windowToken ?: return
        try {
        AlertDialog.Builder(this)
            .setTitle("需要 $name API Key")
            .setMessage("请先在设置中配置 $name API Key，或前往 $url 获取。")
            .setPositiveButton("前往设置") { _, _ ->
                try {
                    val intent = Intent(this, com.cesia.input.SettingsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton("取消", null)
            .create()
            .also { d ->
                d.window?.let { w ->
                    w.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                    w.attributes = w.attributes?.apply { token = windowToken }
                }
            }
            .show()
        } catch (e: Exception) {
            Log.e("Cesia", "showApiKeyPrompt 异常", e)
        }
    }

    /** 显示模型下载引导 */
    private fun showModelDownloadPrompt(type: String, modelId: String) {
        val windowToken = micButton?.windowToken ?: return
        try {
        AlertDialog.Builder(this)
            .setTitle("需要下载 $type 模型")
            .setMessage("本地 $type 模型未安装。是否前往设置下载？")
            .setPositiveButton("前往下载") { _, _ ->
                try {
                    val intent = Intent(this, com.cesia.input.SettingsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra("scroll_to_models", true)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton("取消", null)
            .create()
            .also { d ->
                d.window?.let { w ->
                    w.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                    w.attributes = w.attributes?.apply { token = windowToken }
                }
            }
            .show()
        } catch (e: Exception) {
            Log.e("Cesia", "showModelDownloadPrompt 异常", e)
        }
    }

    // ======================== 录音（根据选择的后端） ========================

    /**
     * 根据用户选择的识别和润色后端开始录音
     */
    private fun startRecordingWithChoice(voiceChoice: VoiceChoice, polishChoice: PolishChoice) {
        isRecording = true
        isWaitingForChoice = false
        recognizedText = ""
        pendingAiMode = null
        setStatusDot("recording")
        startVoiceWave()
        keyboardView.visibility = View.GONE
        candidateBar.visibility = View.GONE
        voiceStartTime = System.currentTimeMillis()

        when (voiceChoice) {
            VoiceChoice.CLOUD_GROQ -> {
                updateStatus("🎤 正在收听 (Groq 云端)...")
                startGroqRecordingAsync()
            }
            VoiceChoice.LOCAL_WHISPER -> {
                updateStatus("🎤 正在收听 (本地 Whisper)...")
                startWhisperRecordingAsync()
            }
            VoiceChoice.GOOGLE -> {
                updateStatus("🎤 正在收听 (Google)...")
                startGoogleRecording(polishChoice)
            }
        }

        // 立即显示 AI+ / AI× 按钮
        showAiChoiceButtons()
    }

    /** Google 语音识别（流式，通过 FallbackRecognizer） */
    private fun startGoogleRecording(polishChoice: PolishChoice) {
        typelessEngine?.startListening(continuous = true)
    }

    /** Groq 云端录音+识别 */
    private fun startGroqRecordingAsync() {
        voiceEngineScope.launch {
            try {
                val text = voiceEngine.recordAndTranscribe(30000)
                withContext(Dispatchers.Main) {
                    handleCloudVoiceResult(text)
                }
            } catch (e: Exception) {
                Log.e("Cesia", "Groq 录音失败", e)
                withContext(Dispatchers.Main) {
                    updateStatus("❌ 语音识别失败: ${e.message}")
                    resetToIdle()
                }
            }
        }
    }

    /** 本地 Whisper 录音+识别 */
    private fun startWhisperRecordingAsync() {
        voiceEngineScope.launch {
            try {
                if (!voiceEngine.hasLocalModel()) {
                    voiceEngine.loadLocalModel()
                }
                val text = voiceEngine.recordAndTranscribe(30000)
                withContext(Dispatchers.Main) {
                    handleCloudVoiceResult(text)
                }
            } catch (e: Exception) {
                Log.e("Cesia", "Whisper 录音失败", e)
                withContext(Dispatchers.Main) {
                    updateStatus("❌ 语音识别失败: ${e.message}")
                    resetToIdle()
                }
            }
        }
    }

    /** 处理 Groq/Whisper 识别结果 → 显示 AI+/AI× 按钮 */
    private fun handleCloudVoiceResult(text: String) {
        if (!isRecording && recognizedText.isEmpty()) return
        isRecording = false
        stopVoiceWave()
        setStatusDot("idle")
        recognizedText = text

        if (text.isEmpty()) {
            updateStatus("⚠️ 未识别到文字，请重试")
            resetToIdle()
            return
        }

        // 显示 AI+/AI× 选择按钮
        isWaitingForChoice = true
        updateStatus("📝 「$text」→ 选择 AI+ 润色 或 AI× 直接上屏")
        micButton.visibility = View.GONE
        btnMicAi.visibility = View.VISIBLE
        btnMicNoAi.visibility = View.VISIBLE
    }

    // ======================== 录音（根据选择的后端） ========================

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

        // 每次录音前更新语音后端（模式可能已切换）
        updateVoiceBackend()

        when (voiceEngine.getBackend()) {
            VoiceEngine.Backend.CLOUD_GROQ -> {
                updateStatus("🎤 正在收听 (Groq 云端)...")
                startGroqRecordingAsync()
            }
            VoiceEngine.Backend.LOCAL_WHISPER -> {
                if (modelManager.hasVoiceModel()) {
                    updateStatus("🎤 正在收听 (本地 Whisper)...")
                    startWhisperRecordingAsync()
                } else {
                    updateStatus("🎤 正在收听 (Google)...")
                    typelessEngine?.startListening(continuous = true)
                }
            }
        }

        // 立即显示 AI+ / AI× 按钮（原逻辑）
        showAiChoiceButtons()
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

    private fun showAiChoiceButtons() {
        animateMicSplit()
    }

    private fun hideAiChoiceButtons() {
        animateMicMerge()
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
        // 停止所有语音后端
        typelessEngine?.stopListening()
        voiceEngineScope.coroutineContext.cancelChildren()
        setStatusDot("processing")
    }

    private fun polishRecognizedText(text: String) {
        isProcessingResult = true
        when (currentPolishChoice) {
            PolishChoice.CLOUD_OPENROUTER -> {
                // 云端润色（OpenRouter）
                typelessEngine?.polishTextAsync(text) { finalText ->
                    isProcessingResult = false
                    currentInputConnection?.commitText(finalText, 1)
                    val duration = if (voiceStartTime > 0) System.currentTimeMillis() - voiceStartTime else 0
                    statsManager.addRecord(text, finalText, duration)
                    resetToIdle()
                }
            }
            PolishChoice.LOCAL_AI -> {
                // 本地润色（AIEngine / llama.cpp）
                polishWithLocalAi(text)
            }
            PolishChoice.OFF -> {
                // 不润色，直接上屏（不会走到这里，AI+ 不会在 OFF 时触发）
                isProcessingResult = false
                currentInputConnection?.commitText(text, 1)
                resetToIdle()
            }
        }
    }

    /** 本地 AI 润色 */
    private fun polishWithLocalAi(text: String) {
        voiceEngineScope.launch {
            try {
                val modelFile = modelManager.getInstalledAiModelFile()
                if (modelFile == null) {
                    withContext(Dispatchers.Main) {
                        updateStatus("⚠️ AI 模型未安装，使用原文")
                        isProcessingResult = false
                        currentInputConnection?.commitText(text, 1)
                        resetToIdle()
                    }
                    return@launch
                }
                if (!aiEngine.isModelLoaded()) {
                    aiEngine.loadLocalModel(modelFile.absolutePath, if (modelManager.useGpu) 99 else 0)
                }
                val result = aiEngine.polish(text, "润色")
                withContext(Dispatchers.Main) {
                    isProcessingResult = false
                    val finalText = result ?: text
                    currentInputConnection?.commitText(finalText, 1)
                    val duration = if (voiceStartTime > 0) System.currentTimeMillis() - voiceStartTime else 0
                    statsManager.addRecord(text, finalText, duration)
                    resetToIdle()
                }
            } catch (e: Exception) {
                Log.e("Cesia", "本地润色失败", e)
                withContext(Dispatchers.Main) {
                    isProcessingResult = false
                    currentInputConnection?.commitText(text, 1)
                    resetToIdle()
                }
            }
        }
    }

    private fun resetToIdle() {
        isRecording = false
        isWaitingForChoice = false
        isProcessingResult = false
        recognizedText = ""
        pendingAiMode = null
        stopVoiceWave()
        // 取消所有语音识别协程
        voiceEngineScope.coroutineContext.cancelChildren()
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
        // 取消所有可能正在进行的长按检测（防止滑动起点按键触发副字符/功能）
        cancelAllLongPressActions()
        // 结束 composing 状态，清除输入框中的高亮/下划线残留
        try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
        // 清除输入状态，防止切换后残留
        rimeEngine.clear()
        t9InputBuffer.clear()
        candidateBar.visibility = View.GONE
        updateStatus("Cesia 已就绪")
        // UI 立即切换，schema 切换放后台（轻量 reload，保留 build 缓存）
        if (keyboardMode == KeyboardMode.NUMBER) {
            switchToKeyboard(KeyboardMode.QWERTY)
            Thread {
                rimeEngine.selectSchema("pinyin")
                rimeEngine.reload()
            }.start()
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
        // 切换键盘时，各键盘的 shift 状态完全独立，互不影响
        if (mode == KeyboardMode.NUMBER) {
            // 进入 T9：只操作 T9 相关状态
            isAsciiMode = false
            rimeEngine.setAsciiMode(false)
            t9ShiftTemp = false  // 每次进入 T9 重置临时 shift
            // t9ShiftLocked 保留（T9 锁定状态不因切换而改变）
        } else if (mode == KeyboardMode.QWERTY) {
            // 进入全键盘：恢复 QWERTY shift 状态
            isAsciiMode = qwertyShiftLocked || qwertyShiftTemp
            rimeEngine.setAsciiMode(isAsciiMode)
            rimeEngine.clear()
        } else {
            // 进入符号键盘：只操作符号相关状态
            isAsciiMode = false
            rimeEngine.setAsciiMode(false)
            rimeEngine.clear()
            t9InputBuffer.clear()
            // symbolShiftLocked 保留
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
            // T9 → QWERTY：切换 schema 到 pinyin
            switchToKeyboard(KeyboardMode.QWERTY)
            rimeEngine.selectSchema("pinyin")
            rimeEngine.reload()
            if (qwertyShiftLocked) {
                isAsciiMode = true
                rimeEngine.setAsciiMode(true)
            }
        } else {
            // QWERTY → T9：切换 schema 到 t9_pinyin
            switchToKeyboard(KeyboardMode.NUMBER)
            rimeEngine.selectSchema("t9_pinyin")
            rimeEngine.reload()
            resetNumberKeyboardState()
        }
    }

    // ======================== 数字键盘核心逻辑 ========================

    private fun resetT9State() {
        t9InputBuffer.clear()
        rimeEngine.clear()
        t9ShiftTemp = false
        // qwertyShiftLocked 不在此处清除，各键盘状态独立
        updateCandidateBar()
        updateStatus("Cesia 已就绪")
    }

    // 数字键盘长按通过 popupCharacters 走 startLongPressDetection

    private fun resetNumberKeyboardState() {
        t9ShiftTemp = false
        // qwertyShiftLocked 和 t9ShiftLocked 不在此处清除，各键盘状态独立
        t9InputBuffer.clear()
        updateShiftIndicator()
    }

    private fun updateShiftIndicator() {
        // 同步 shift 状态到 KeyboardView（三个键盘完全独立）
        when (keyboardMode) {
            KeyboardMode.NUMBER -> {
                keyboardView.isShiftMode = t9ShiftTemp
                keyboardView.isShiftLocked = t9ShiftLocked
            }
            KeyboardMode.QWERTY -> {
                // 全键盘：临时shift 和 锁定 都显示为大写
                keyboardView.isShiftMode = qwertyShiftTemp
                keyboardView.isShiftLocked = qwertyShiftLocked
            }
            KeyboardMode.SYMBOL_CN, KeyboardMode.SYMBOL_EN -> {
                keyboardView.isShiftMode = false
                keyboardView.isShiftLocked = symbolShiftLocked
            }
        }
        keyboardView.invalidateAllKeys()
    }

    private fun handleShiftKey() {
        if (keyboardMode == KeyboardMode.NUMBER) {
            // T9：操作 t9ShiftLocked / t9ShiftTemp
            if (t9ShiftLocked) {
                t9ShiftLocked = false
                t9ShiftTemp = false
                commitT9AndClear()
            } else if (t9ShiftTemp) {
                t9ShiftTemp = false
                commitT9AndClear()
            } else {
                t9ShiftTemp = true
            }
        } else if (keyboardMode == KeyboardMode.QWERTY) {
            // 全键盘：操作 qwertyShiftLocked / qwertyShiftTemp
            if (qwertyShiftLocked) {
                // 锁定状态 → 单击解除锁定
                qwertyShiftLocked = false
                qwertyShiftTemp = false
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
                rimeEngine.clear()
            } else if (qwertyShiftTemp) {
                // 临时shift → 退回正常
                qwertyShiftTemp = false
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
                rimeEngine.clear()
            } else {
                // 正常 → 单击临时shift
                qwertyShiftTemp = true
                isAsciiMode = true
                rimeEngine.setAsciiMode(true)
                rimeEngine.clear()
            }
        } else {
            // 符号键盘：操作 symbolShiftLocked
            symbolShiftLocked = !symbolShiftLocked
        }
        updateShiftIndicator()
        keyboardView.invalidateAllKeys()
        updateCandidateBar()
    }

    private fun handleShiftLongPress() {
        // 长按 shift：三个键盘完全独立
        if (keyboardMode == KeyboardMode.NUMBER) {
            // T9：锁定 T9 shift
            t9ShiftLocked = true
            t9ShiftTemp = true
        } else if (keyboardMode == KeyboardMode.QWERTY) {
            // 全键盘：锁定大写
            qwertyShiftLocked = true
            qwertyShiftTemp = false
            isAsciiMode = true
            rimeEngine.setAsciiMode(true)
            rimeEngine.clear()
        } else {
            // 符号键盘：锁定符号 shift
            symbolShiftLocked = true
        }
        longPressTriggered = true
        longPressConsumed = false
        updateShiftIndicator()
        keyboardView.invalidateAllKeys()
        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** 当前是否处于 shift 激活状态（T9模式下=t9ShiftTemp，QWERTY模式下=isAsciiMode） */
    private fun isShiftActive(): Boolean {
        return if (keyboardMode == KeyboardMode.NUMBER) t9ShiftTemp else isAsciiMode
    }

    /** 临时shift输入一个字符后自动退回 */
    private fun autoExitShift() {
        val currentShiftLocked = if (keyboardMode == KeyboardMode.NUMBER) t9ShiftLocked else qwertyShiftLocked
        if (!currentShiftLocked && isShiftActive()) {
            if (keyboardMode == KeyboardMode.NUMBER) {
                t9ShiftTemp = false
            } else if (keyboardMode == KeyboardMode.QWERTY) {
                qwertyShiftTemp = false
            }
            isAsciiMode = false
            rimeEngine.setAsciiMode(false)
            updateShiftIndicator()
            keyboardView.invalidateAllKeys()
        }
    }

    private fun handleNumberKeyboardKey(primaryCode: Int) {
        if (t9ShiftTemp || t9ShiftLocked) {
            // Shift模式（临时或锁定）：直接输入数字
            val digit = mainToSub[primaryCode]
            if (digit != null) {
                currentInputConnection?.commitText(digit.toString(), 1)
            } else if (primaryCode == 32) {
                // 空格键在 T9 shift 模式下输出 0
                currentInputConnection?.commitText("0", 1)
            } else {
                currentInputConnection?.commitText(primaryCode.toChar().toString(), 1)
            }
            // 临时shift：输入一个数字后自动退回；锁定shift：保持
            if (!t9ShiftLocked) {
                t9ShiftTemp = false
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
            when (targetMode) {
                KeyboardMode.NUMBER -> {
                    // schema 本来就是 t9_pinyin，只需清状态
                    resetNumberKeyboardState()
                }
                KeyboardMode.QWERTY -> {
                    // schema 本来就是 pinyin，保留 shift 状态
                    rimeEngine.clear()
                    updateCandidateBar()
                }
                else -> updateCandidateBar()
            }
        } else {
            // 非符号键盘场景：默认回QWERTY
            val wasT9 = keyboardMode == KeyboardMode.NUMBER
            switchToKeyboard(KeyboardMode.QWERTY)
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
        if (qwertyShiftLocked) {
            // 锁定状态 → 解除
            qwertyShiftLocked = false
            qwertyShiftTemp = false
            isAsciiMode = false
        } else if (qwertyShiftTemp) {
            // 临时 shift 状态 → 解除
            qwertyShiftTemp = false
            isAsciiMode = false
        } else {
            // 正常切换中英文模式
            isAsciiMode = !isAsciiMode
        }
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
        keyboardView.currentPopupKey = key
        keyboardView.invalidateKey(key)
        longPressRunnable = Runnable {
            val popup = key.popupCharacters
            if (!popup.isNullOrEmpty()) {
                val symbol = popup[0].toString()
                currentInputConnection?.commitText(symbol, 1)
                keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                longPressTriggered = true
                longPressConsumed = false
            }
            keyboardView.currentPopupKey = null
            keyboardView.invalidateKey(key)
            currentLongPressKey = null
        }.also {
            longPressHandler.postDelayed(it, 500)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        val prevKey = currentLongPressKey
        currentLongPressKey = null
        longPressTriggered = false
        longPressConsumed = false
        keyboardView.currentPopupKey = null
        if (prevKey != null) keyboardView.invalidateKey(prevKey)
    }

    /** 取消所有长按相关的 runnable（滑动切换时调用，彻底防止误触发） */
    private fun cancelAllLongPressActions() {
        cancelLongPress()
        // 取消功能键长按
        functionalLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        functionalLongPressRunnable = null
        // 取消剪贴板粘贴长按
        clipboardPasteRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        clipboardPasteRunnable = null
        // 取消剪贴板剪切长按
        clipboardCutRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        clipboardCutRunnable = null
        // 取消 Shift 长按
        shiftLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        shiftLongPressRunnable = null
        // 取消回车长按
        enterLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        enterLongPressRunnable = null
        // 取消退格长按
        backspaceRunnable?.let { backspaceHandler.removeCallbacks(it) }
        backspaceRunnable = null
        // 取消发送键长按
        cancelSendKeyLongPress()
        // 重置短按标志，防止 runnable 中的 !shortPressHandled 判断泄漏
        shortPressHandled = true
    }

    private fun startSendKeyLongPress() {
        cancelSendKeyLongPress()
        // 立即高亮发送按钮
        btnSend.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF81D8D0.toInt())
        btnSend.elevation = 6f
        startSendButtonGlow()
        sendKeyRunnable = Runnable {
            sendKeyLongPressTriggered = true
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showClipboardManagerPopup()
        }.also {
            sendKeyHandler.postDelayed(it, 500)
        }
    }

    private fun startSendButtonGlow() {
        sendButtonGlowing = true
        val pulse = ScaleAnimation(
            1.0f, 1.15f, 1.0f, 1.15f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 600
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = ScaleAnimation.INFINITE
        }
        btnSend.startAnimation(pulse)
    }

    private fun stopSendButtonGlow() {
        sendButtonGlowing = false
        btnSend.clearAnimation()
        btnSend.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE0E0E0.toInt())
        btnSend.elevation = 0f
    }

    private fun startMagicBookLongPress() {
        cancelMagicBookLongPress()
        // 立即高亮魔法书按钮
        btnClipboard.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF81D8D0.toInt())
        btnClipboard.elevation = 6f
        startMagicBookGlow()
        magicBookRunnable = Runnable {
            magicBookLongPressTriggered = true
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showMagicHistoryPopup()
        }.also {
            magicBookHandler.postDelayed(it, 500)
        }
    }

    private fun startMagicBookGlow() {
        magicBookGlowing = true
        val pulse = ScaleAnimation(
            1.0f, 1.15f, 1.0f, 1.15f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 600
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = ScaleAnimation.INFINITE
        }
        btnClipboard.startAnimation(pulse)
    }

    private fun stopMagicBookGlow() {
        magicBookGlowing = false
        btnClipboard.clearAnimation()
        btnClipboard.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE0E0E0.toInt())
        btnClipboard.elevation = 0f
    }

    private fun cancelSendKeyLongPress() {
        sendKeyRunnable?.let { sendKeyHandler.removeCallbacks(it) }
        sendKeyRunnable = null
        stopSendButtonGlow()
    }

    private fun cancelMagicBookLongPress() {
        magicBookRunnable?.let { magicBookHandler.removeCallbacks(it) }
        magicBookRunnable = null
        stopMagicBookGlow()
    }

    // ====== 剪贴板搜索状态 =======
    private var clipboardSearchEditMode = false
    private var etSearch: android.widget.EditText? = null

    /**
     * 剪贴板管理器弹窗 — 两列风格，支持置顶/删除/搜索/关闭/长按操作
     */
    private fun showClipboardManagerPopup() {
        try {
            val inflater2 = android.view.LayoutInflater.from(this)
            clipboardPopupView = inflater2.inflate(R.layout.popup_clipboard_manager, null)
            val popupView = clipboardPopupView!!
            val gvClipboard = popupView.findViewById<GridView>(R.id.gv_clipboard_items)
            val etSearch = popupView.findViewById<android.widget.EditText>(R.id.btn_clipboard_search)
            this.etSearch = etSearch
            val tvSearchHint = popupView.findViewById<TextView>(R.id.tv_search_edit_hint)
            val btnDone = popupView.findViewById<TextView>(R.id.btn_clipboard_done)
            val btnPin = popupView.findViewById<TextView>(R.id.btn_clipboard_pin)
            val btnDelete = popupView.findViewById<TextView>(R.id.btn_clipboard_delete)
            val tvEmpty = popupView.findViewById<TextView>(R.id.tv_clipboard_empty)

            // 搜索框：点击获得焦点弹出软键盘，输入内容实时过滤
            etSearch.setOnFocusChangeListener { _, hasFocus ->
                clipboardSearchEditMode = hasFocus
                if (hasFocus) {
                    tvSearchHint.visibility = View.VISIBLE
                    tvSearchHint.text = "输入搜索关键词..."
                    etSearch.hint = ""
                } else {
                    tvSearchHint.visibility = View.GONE
                    etSearch.hint = "🔍 点击搜索..."
                }
            }
            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // 搜索编辑模式下，TextWatcher 不做任何事（由 onKey 拦截处理过滤）
                    // 非搜索编辑模式下（如直接粘贴），才由 TextWatcher 触发过滤
                    if (!clipboardSearchEditMode) {
                        clipboardSearchFilter = s?.toString()?.trim() ?: ""
                        applyClipboardFilter()
                    }
                }
            })
            etSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    // 搜索动作：清除焦点，隐藏软键盘
                    etSearch.clearFocus()
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
                    true
                } else false
            }

            // 加载剪贴板历史（持久化 + 系统剪贴板 + 收藏）
            val clipboardMgr = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            loadClipboardHistoryToClassMembers(clipboardMgr)

            // 初始化过滤
            clipboardSearchFilter = ""
            applyClipboardFilter()

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
                val item = clipboardFilteredItems.getOrNull(position) ?: return@setOnItemClickListener
                if (item.isEmpty) return@setOnItemClickListener
                currentInputConnection?.commitText(item.text, 1)
                popup.dismiss()
            }

            // 长按：操作菜单（置顶/删除/编辑/分词）
            gvClipboard.setOnItemLongClickListener { _, _, position, _ ->
                val item = clipboardFilteredItems.getOrNull(position) ?: return@setOnItemLongClickListener true
                if (item.isEmpty) return@setOnItemLongClickListener true
                showClipboardItemActions(item, clipboardItems) { loadClipboardHistoryToClassMembers(clipboardMgr); applyClipboardFilter() }
                true
            }


            btnDone.setOnClickListener { popup.dismiss() }

            // 置顶按钮
            btnPin.setOnClickListener {
                val realItems = clipboardItems.filter { !it.isEmpty }
                if (realItems.isEmpty()) return@setOnClickListener
                val popupMenu = android.widget.PopupMenu(this, btnPin)
                for (r in realItems) {
                    val title = "${if (r.isPinned) "⤒ " else "○ "}${r.text.take(18)}"
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
                // 全部删除置顶（order=0）
                popupMenu.menu.add(0, -1, 0, "⊗ 删除全部（${realItems.size}条）")
                for (r in realItems) {
                    popupMenu.menu.add(0, r.text.hashCode(), 1, "⊗ ${r.text.take(18)}")
                }
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.itemId == -1) {
                        // 全部删除：保留置顶项
                        clipboardItems.removeAll { !it.isPinned && !it.isEmpty }
                        saveClipboardHistoryFromClassMembers()
                        applyClipboardFilter()
                        updateStatus("⊗ 已删除全部（保留置顶）")
                    } else {
                        val target = realItems.find { it.text.hashCode() == menuItem.itemId }
                        if (target != null) {
                            clipboardItems.removeAll { it.text == target.text }
                            saveClipboardHistoryFromClassMembers()
                            applyClipboardFilter()
                        }
                    }
                    true
                }
                popupMenu.show()
            }

            popup.showAtLocation(keyboardView, android.view.Gravity.TOP or android.view.Gravity.START, 0, -totalHeight)

            popup.setOnDismissListener {
                cancelSendKeyLongPress()
                cancelMagicBookLongPress()
            }

            // 持久化保存
            saveClipboardHistoryFromClassMembers()

        } catch (e: Exception) {
            updateStatus("❌ 剪贴板管理器异常: ${e.message}")
        }
    }

    private fun updateClipboardSearchBtn(btnSearch: TextView) {
        if (clipboardSearchFilter.isNotEmpty()) {
            btnSearch.text = "🔍 $clipboardSearchFilter"
            btnSearch.setTextColor(0xFF81D8D0.toInt())
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
            actions.add(if (item.isPinned) "⤒ 取消置顶" else "⤒ 置顶收藏")
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
                tvPin.setTypeface(null, if (item.isPinned) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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

        // ======================== 剪贴板搜索编辑模式：手动写入 EditText ========================
        if (clipboardSearchEditMode) {
            val searchEt = this.etSearch
            if (searchEt != null) {
                when (primaryCode) {
                    // 发送键/回车键：确认搜索，退出编辑模式
                    -200, 10 -> {
                        clipboardSearchFilter = searchEt.text.toString().trim()
                        applyClipboardFilter()
                        searchEt.clearFocus()
                        clipboardSearchEditMode = false
                        return
                    }
                    // 返回键/ESC：取消搜索，清空并退出编辑模式
                    KeyEvent.KEYCODE_BACK, 27 -> {
                        searchEt.setText("")
                        clipboardSearchFilter = ""
                        applyClipboardFilter()
                        searchEt.clearFocus()
                        clipboardSearchEditMode = false
                        return
                    }
                    // 退格键
                    -5, Keyboard.KEYCODE_DELETE -> {
                        val buf = searchEt.text.toString()
                        if (buf.isNotEmpty()) {
                            val newBuf = buf.dropLast(1)
                            searchEt.setText(newBuf)
                            searchEt.setSelection(newBuf.length)
                        }
                        clipboardSearchFilter = searchEt.text.toString().trim()
                        applyClipboardFilter()
                        return
                    }
                    // 空格（直接追加空格）
                    32 -> {
                        val buf = searchEt.text.toString()
                        searchEt.setText(buf + " ")
                        searchEt.setSelection(searchEt.text.length)
                        clipboardSearchFilter = searchEt.text.toString().trim()
                        applyClipboardFilter()
                        return
                    }
                    // 字母键 a-z：追加字符
                    in 97..122 -> {
                        val buf = searchEt.text.toString()
                        searchEt.setText(buf + primaryCode.toChar().toString())
                        searchEt.setSelection(searchEt.text.length)
                        clipboardSearchFilter = searchEt.text.toString().trim()
                        applyClipboardFilter()
                        return
                    }
                    // 大写字母 A-Z
                    in 65..90 -> {
                        val buf = searchEt.text.toString()
                        searchEt.setText(buf + primaryCode.toChar().lowercase())
                        searchEt.setSelection(searchEt.text.length)
                        clipboardSearchFilter = searchEt.text.toString().trim()
                        applyClipboardFilter()
                        return
                    }
                    // 数字键 0-9：直接追加数字
                    in 48..57 -> {
                        val buf = searchEt.text.toString()
                        searchEt.setText(buf + primaryCode.toChar().toString())
                        searchEt.setSelection(searchEt.text.length)
                        clipboardSearchFilter = searchEt.text.toString().trim()
                        applyClipboardFilter()
                        return
                    }
                    // 其他可打印符号直接追加
                    in 33..47, in 58..64, in 91..96, in 123..126 -> {
                        val buf = searchEt.text.toString()
                        searchEt.setText(buf + primaryCode.toChar().toString())
                        searchEt.setSelection(searchEt.text.length)
                        clipboardSearchFilter = searchEt.text.toString().trim()
                        applyClipboardFilter()
                        return
                    }
                    // 其他按键（shift/ctrl等）忽略
                    else -> return
                }
            }
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
                    val shiftActive = if (keyboardMode == KeyboardMode.NUMBER) {
                        t9ShiftTemp || t9ShiftLocked
                    } else if (keyboardMode == KeyboardMode.QWERTY) {
                        isAsciiMode  // QWERTY 临时 shift 或锁定都是 isAsciiMode=true
                    } else {
                        symbolShiftLocked
                    }
                    val out = if (shiftActive) {
                        primaryCode.toChar().uppercaseChar().toString()
                    } else {
                        primaryCode.toChar().toString()
                    }
                    ic?.commitText(out, 1)
                    // QWERTY临时shift：输入一个字母后自动退回中文（锁定不退出）
                    if (!qwertyShiftLocked && keyboardMode == KeyboardMode.QWERTY && qwertyShiftTemp) {
                        qwertyShiftTemp = false
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
                    // 数字键盘空格：shift模式下输出0，否则正常空格
                    if (t9ShiftTemp || t9ShiftLocked) {
                        // Shift模式：输出 0
                        ic?.commitText("0", 1)
                        // 临时shift：自动退回；锁定shift：保持
                        if (!t9ShiftLocked) {
                            t9ShiftTemp = false
                            updateShiftIndicator()
                        }
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
                    if (!t9ShiftTemp && t9InputBuffer.isNotEmpty()) {
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
                    val pinyinText = rimeEngine.composingText?.replace(" ", "")
                    if (!pinyinText.isNullOrEmpty()) {
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
            KEYCODE_SHIFT -> {
                if (keyboardMode == KeyboardMode.QWERTY || keyboardMode == KeyboardMode.NUMBER) {
                    shortPressHandled = true; handleShiftKey()
                } else {
                    // 符号键盘：普通符号输出
                    shortPressHandled = true
                    currentInputConnection?.commitText("⇧", 1)
                }
            }

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
        // Shift 键长按检测（仅 QWERTY 和 T9）
        if ((primaryCode == KEYCODE_SHIFT || primaryCode == -1) &&
            (keyboardMode == KeyboardMode.QWERTY || keyboardMode == KeyboardMode.NUMBER)) {
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
        voiceEngine.release()
        aiEngine.release()
        voiceEngineScope.cancel()
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
