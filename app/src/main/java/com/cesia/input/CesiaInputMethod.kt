package com.cesia.input

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import com.cesia.input.CesiaKeyboardView
import com.cesia.input.model.ModelInfo
import com.cesia.input.model.ModelRegistry
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.ScaleAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.text.TextUtils
import android.graphics.Typeface
import java.io.File
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesia.input.ai.AIEngine
import com.cesia.input.ai.LocalModeManager
import com.cesia.input.engine.TypelessEngine
import com.cesia.input.engine.rime.RimeEngine
import com.cesia.input.model.ModelManager
import com.cesia.input.stats.PolishStatsManager
import com.cesia.input.stats.MagicHistoryManager
import com.cesia.input.voice.VoiceEngine
import com.cesia.input.voice.SimulTranslateManager
import com.cesia.input.engine.ai.SherpaOnnxEngine
import com.cesia.input.engine.PinyinDictManager
import com.cesia.input.model.ModelDownloadManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

/**
 * Cesia 输入法 — Rime 内核版
 *
 * 架构：
 * - 键盘 UI：标准 QWERTY 布局（qwerty.xml + symbols_cn.xml + symbols.xml）
 * - 输入引擎：Rime（librime JNI）处理拼音→汉字
 * - 底部功能栏：智能写作（星星/四角星）、智能修改（魔法书/笔）、语音、清空、发送
 * - 语音润色：TypelessEngine（OpenRouter API）
 */

// region 视图与UI
class CesiaInputMethod : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    // 单线程 Executor，用于串行执行 Rime 引擎操作（防止多线程并发崩溃）
    private val rimeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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
    private lateinit var tvMicZh: TextView          // 语音键右上角“中”副字符（仅纯中文模式显示）
    private lateinit var micWrapper: FrameLayout     // 包裹麦克风键，承载“中”标记；分列时需隐藏以恢复原始双按钮布局
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnClipboard: ImageButton // 智能修改按钮（魔法书/笔）
    private lateinit var btnMagic: ImageButton // 智能写作按钮（星星/五角星）
    private lateinit var btnSend: ImageButton
    private lateinit var statusDot: View
    private var statusDotState: String = "idle"
    private lateinit var statusText: TextView
    private lateinit var voiceWave: View
    private lateinit var btnTheme: TextView
    private lateinit var btnCloud: TextView

    // ---- 主题色动态可调（三维） ----
    private var themeAccent: Int = 0xFF81D8D0.toInt()     // 主色（蒂芙尼蓝），色相调节
    private var themeBgGrayBase: Int = 0xFF                // 背景灰度基础值（0-255），默认最右=255
    private var themeKeyGrayBase: Int = 0xFF               // 按键灰度基础值（0-255），默认最右=255
    private var themePopup: PopupWindow? = null
    private val defaultAccentHsl = hslOf(0xFF81D8D0.toInt())
    private var accentHue: Float = defaultAccentHsl[0]     // 当前色相 0-360
    private var textThemeSize: Int = 1                     // 0=小, 1=中(default), 2=大, 3=超大
    var textGrayScale: Float = 0.5f                        // 0=纯黑, 0.5=基准灰(默认), 1=纯白
    // 随手机时间自动变化主题色（勾选后按当前小时动态计算 hue 并定时刷新）
    private var autoTimeTheme: Boolean = false
    private val autoThemeHandler = Handler(Looper.getMainLooper())
    private var autoThemeRunnable: Runnable? = null

    private fun loadThemeColors() {
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        themeAccent = prefs.getInt("theme_accent", 0xFF81D8D0.toInt())
        themeBgGrayBase = prefs.getInt("theme_bg_gray", 0xFF)
        themeKeyGrayBase = prefs.getInt("theme_key_gray", 0xFF)
        accentHue = prefs.getFloat("theme_accent_hue", defaultAccentHsl[0])
        textThemeSize = prefs.getInt("theme_text_size", 1)
        textGrayScale = prefs.getFloat("text_gray_scale", 0.5f)
        autoTimeTheme = prefs.getBoolean("auto_time_theme", false)
        t9FenCiLock = prefs.getBoolean("t9_fenci_lock", false)
    }

    /**
     * 遍历 view 树，将蒂芙尼蓝替换为当前主题色
     * 覆盖所有 XML 中硬编码的 #81D8D0
     */
    private fun applyAccentToViewTree(view: View, accent: Int) {
        val tintList = android.content.res.ColorStateList.valueOf(accent)
        val tiffany = 0xFF81D8D0.toInt()
        when (view) {
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyAccentToViewTree(view.getChildAt(i), accent)
                }
            }
        }
        val defaultColor = (view as? android.widget.TextView)?.textColors?.defaultColor ?: 0
        if (defaultColor == tiffany) (view as? android.widget.TextView)?.setTextColor(accent)
        val bgTint = try { view.backgroundTintList?.defaultColor ?: 0 } catch (_: Exception) { 0 }
        if (bgTint == tiffany) view.backgroundTintList = tintList
        // Handle TextInputLayout boxStrokeColor and hintTextColor
        if (view is com.google.android.material.textfield.TextInputLayout) {
            try {
                if (view.boxStrokeColor == tiffany) {
                    view.boxStrokeColor = accent
                }
            } catch (_: Exception) {}
            try {
                val hintColor = view.hintTextColor?.defaultColor ?: 0
                if (hintColor == tiffany) {
                    view.hintTextColor = android.content.res.ColorStateList.valueOf(accent)
                }
            } catch (_: Exception) {}
        }
    }
    private fun saveThemeColors() {
        getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
            .putInt("theme_accent", themeAccent)
            .putInt("theme_bg_gray", themeBgGrayBase)
            .putInt("theme_key_gray", themeKeyGrayBase)
            .putFloat("theme_accent_hue", accentHue)
            .putInt("theme_text_size", textThemeSize)
            .putFloat("text_gray_scale", textGrayScale)
            .putBoolean("auto_time_theme", autoTimeTheme)
            .apply()
    }

    // 云按钮状态
    enum class CloudMode {
        LOCAL,       // 本地模式（本字，不高亮）
        CLOUD,       // 云端模式（云字，高亮）
        LOCAL_LOCKED // 本地锁定（本字，高亮）
    }
    private var cloudMode: CloudMode = CloudMode.LOCAL

    // 个性化设置（从 SharedPreferences 读取）
    private var statusIdleText: String = "Cesia 已就绪"
    private var smartWritingLabel: String = "智能写作"
    private var magicBookTitle: String = "芙莉莲的魔法书"

    // 语音锁定模式
    private var isVoiceLocked: Boolean = false

    // 语音键长按检测（参考智能修改按钮模式）
    private var micLongPressTriggered = false
    private var micHandler = Handler(Looper.getMainLooper())
    private var lastMicTapTime = 0L          // 双击检测：上一次松开时间
    private var micDoubleTapPending = false  // 双击窗口内等待第二次点击
    private var micLongPressRunnable: Runnable? = null

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

    // ---- HSL 工具函数 ----
    private fun hslOf(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        var h = 0f; var s = 0f
        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6f
                g -> ((b - r) / d + 2) / 6f
                else -> ((r - g) / d + 4) / 6f
            }
        }
        return floatArrayOf(h * 360f, s, l)
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Int = ColorUtils.hslToColor(h, s, l)

// endregion 视图与UI

// region 核心组件与引擎
    // ======================== 核心组件 ========================
    private var typelessEngine: TypelessEngine? = null
    private lateinit var statsManager: PolishStatsManager
    private lateinit var rimeEngine: RimeEngine
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var modelManager: ModelManager
    private lateinit var downloadManager: ModelDownloadManager
    private lateinit var dictManager: PinyinDictManager
    private lateinit var aiEngine: AIEngine
    private var simulTranslateManager: SimulTranslateManager? = null

    // ======================== 语音/润色选择 ========================
    enum class VoiceChoice { LOCAL_SHERPA, GOOGLE }
    enum class PolishChoice { LOCAL_AI, CLOUD_OPENROUTER, OFF }

    // ======================== 本地/云端模式 ========================
    // true = 本地模式, false = 云端模式（默认）
    private var localModeEnabled = false

    // ======================== 同传模式 ========================
    private var simulTranslateEnabled = false  // 同传模式开关

    /** 长按语音键：切换本地/云端模式 */
    private fun toggleLocalCloudMode() {
        if (!localModeEnabled) {
            val bridgeLoaded = SherpaOnnxEngine.isLibraryLoaded()
            val hasVoiceModel = voiceEngine.hasSherpaModel()
            val hasAiModel = modelManager.hasAiModel()

            if (!bridgeLoaded) {
                updateStatus("⚠️ 无法切换到本地模式：Sherpa 库未加载")
                return
            }
            if (!hasVoiceModel) {
                updateStatus("⚠️ 无法切换到本地模式：语音模型未安装")
                return
            }
            if (!hasAiModel) {
                updateStatus("⚠️ 无法切换到本地模式：Qwen 模型未安装")
                return
            }
        }

        localModeEnabled = !localModeEnabled

        // 同步写入 SharedPreferences，确保 polishRecognizedText() 读到正确模式
        val modePrefs = getSharedPreferences("cesia_local_mode", Context.MODE_PRIVATE)
        val newMode = if (localModeEnabled) LocalModeManager.RunMode.LOCAL.name
                      else LocalModeManager.RunMode.CLOUD_FREE.name
        modePrefs.edit().putString("run_mode", newMode).apply()
        Log.i("Cesia", "toggleLocalCloudMode: localModeEnabled=$localModeEnabled, run_mode=$newMode")

        updateVoiceBackend()
        localModeEnabled = !localModeEnabled

        // 更新云按钮和麦克风按钮外观
        updateMicButtonAppearance()
    }

    /** 根据当前模式更新语音键图标 */
    private fun updateMicButtonAppearance() {
        if (localModeEnabled) {
            micButton?.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            micButton?.text = "🎤"
        } else {
            micButton?.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            micButton?.text = "🎤☁️"
        }
    }

// endregion 核心组件与引擎

// region 状态变量
    // ======================== 状态 ========================
    private var isRecording = false
    // 语音输入后候选栏保持可见（不自动隐藏），直到按退格键才隐藏
    private var candidateBarKeep = false
    private var keyboardMode = KeyboardMode.NUMBER  // 默认 T9 数字键盘
    private var defaultKeyboardMode = KeyboardMode.NUMBER  // 用户长按切换键设定的默认键盘（打开输入法即用）
    private var prevKeyboardMode = KeyboardMode.NUMBER  // 进入符号键盘前的键盘模式（用于返回）
    private var isProcessingResult = false
    private var isWaitingForChoice = false
    private var voiceStartTime = 0L
    private var pendingAiMode: Boolean? = null
    private var recognizedText: String = ""        // 当前组合态显示文本（流式每轮会更新它，仅用于展示）
    private var isContinuingSession: Boolean = false // 撤销/清空后处于“继续识别”态，下次识别结果需拼到前缀之后
    // 下划线（组合态）唯一真相源：仅由“追加说话 / 撤销 / 清空”三类操作修改，
    // 流式 onSegmentResult 永不重写它，仅读取它来拼接显示。这样跨段保留内容不会被新一轮识别覆盖吃掉。
    private var voiceKeptText: String = ""
    // 撤销/清空的回收站：存最近一次撤销/清空前的完整内容，供“恢复”命令词还原。
    private var voiceUndoBackup: String = ""
    // 标记“刚在锁定态执行发送”：发送后输入框 finish 触发 onFinishInputView 时不解除锁定（由后续恢复监听接管）。
    private var justSentWhileLocked: Boolean = false
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var isAsciiMode = false  // 与 Rime ascii_mode 对应
    private var shortPressHandled = false  // 当前按键是否已处理短按（防止长按重复触发）
    // === 词语联想 ===
    private var associationPrefix = ""      // 当前联想前缀（如 "这个"）
    private var associationCandidates = emptyList<String>()  // 当前联想候选词列表
    private var isAssociationMode = false   // 是否处于联想模式
    private var selectedCandidateIndex = 0   // 当前长按选中的候选词 index（用于菜单定位）
    // 候选栏显示列表快照：经 CandidatePrefs.reorder(置顶/降频) + T9选音过滤后实际显示的顺序。
    // 点击时先按显示位置反查用户点的是哪个词。
    private var lastDisplayedCands: List<String> = emptyList()
    // 未过滤的 Rime 原始合并候选序（getAllCandidates，Rime 真实全局序）。
    // 用于点击时把「显示词」映射回 Rime 真实全局索引，再翻页选中（pageCount 在选音后不可靠，不能靠它翻页查找）。
    private var lastAllCands: List<String> = emptyList()
    // 候选栏去重签名：输入状态未变时跳过整轮重建(避免每次按键无谓的 notifyDataSetChanged 重排)
    private var lastCandSig: Int = 0
    // === 按钮提示计数（最多2次） ===
    private val buttonHintCount = mutableMapOf<String, Int>()

    /** 按钮按下时提示（最多2次），提示文字来源个性化设置 */
    private fun maybeShowButtonHint(buttonName: String, hintText: String) {
        val count = buttonHintCount[buttonName] ?: 0
        if (count < 2) {
            updateStatus(hintText)
            buttonHintCount[buttonName] = count + 1
        }
    }


    // 语音引擎协程作用域
    private val voiceEngineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // === 三个键盘的 shift 状态完全独立 ===
    private var qwertyShiftLocked = false   // 全键盘 shift 锁定
    private var qwertyShiftTemp = false     // 全键盘临时 shift（单字符后自动退出）
    private var t9ShiftLocked = false       // T9 shift 锁定
    private var t9ShiftTemp = false         // T9 临时 shift（单字符后自动退出）
    private var symbolShiftLocked = false   // 符号键盘 shift 锁定
    private var t9InputBuffer = StringBuilder()  // T9 数字输入缓冲
    // 本次组合已上屏的文本累积（逐字组词时用于去除最后一步整串返回的重复前缀）
    private var t9ComposedSoFar = StringBuilder()
    // 逐键选音：已按的数字队列 + 已选字母前缀（合计即原始数字串长度）
    private var t9DigitQueue = StringBuilder()    // 已按数字顺序，如 "97"
    // 接龙组词：已消费的数字位数（从 t9DigitQueue 头部）。待匹配 = substring(t9ConsumedLen)。
    // 选词后消费对应长度，剩余字符继续组词；耗尽自动上屏；无匹配停留等退格。
    private var t9ConsumedLen = 0
    // 用户自建词组库：词 → 全拼数字串（如 "大家不好"→"3254228426"），接龙上屏时写入，下次匹配注入候选
    private val userPhrases = LinkedHashMap<String, String>()
    // 候选懒加载：candPageWalk=已扫页数(10页=50候选)，滚到底+10页(50)；上限 MAX_PAGE_WALK(50页=250)
    private var candPageWalk = 10
    private var candTotalLoaded = 0
    private var lastPagerInputSig = ""
    // 连续按键数上限（单击数字键计数，非中文字数）：到达后提示上限、不再累积新键。
    // 配合 schema max_code_length=8，Rime 只解码末 ≤8 位，25 键内不卡（含退格）。
    private val MAX_T9_KEYS = 25
    private var t9SpellPrefix = StringBuilder()   // 已选字母，如 "ws"
    private var t9FenCiOn = false                 // 分词开关：默认关=全拼（数字直连）；开=简拼（数字间加分词符）
    private var t9FenCiLock = false               // 全拼/简拼按钮双击锁定（防误触），持久化，默认不锁
    private var t9FenCiLastClick = 0L             // 1键上次单击时间戳（双击检测）
    private var t9FenCiPendingSingle: Runnable? = null  // 单击待执行的切换任务（延迟以等待可能的双击）
    private var t9FenCiMerged: List<String> = emptyList()  // 简拼模式合并后的候选（分词符串 + 字母组合交叉），供 UI/点击使用
    // 单键单字：枚举该键所有字母(a/b/c)取单字候选合并，供 UI/点击使用（跟随选音锁定字母变化）
    private var t9SingleKeyCands: List<String> = emptyList()
    // 上次喂给 Rime 会话的 feed 串（增量喂判断依据）：新 feed 以其为前缀→只增量喂新增部分，
    // 否则（退格/切简拼全拼/提交后队列变化）整串重放。避免每键重放“整个数字队列”导致长码 O(n²) 卡顿。
    private var lastT9Feed: String? = null
    private var pendingEnglish = ""               // 英文模式下已直接上屏的连续英文字母缓冲（按数字时连同数字一起上屏）

    // = 号计算器（复刻 Rime 原生 =expr 求值）：calcExpr 非空即处于计算模式，缓存 = 开头的算式
    private var calcExpr = StringBuilder()
    private fun isCalcActive() = calcExpr.isNotEmpty()
    private var llT9Spell: android.widget.LinearLayout? = null          // 候选栏最左 4 字母点选区
    private var t9SpellTVs: List<android.widget.TextView>? = null        // 4 个字母 TextView
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

    // 长按检测
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    // 每个按键码独立记录长按 runnable，避免快速连续输入时“取消共享字段”误伤/泄漏导致首个按键功能被触发
    private val functionalLongPressRunnables = mutableMapOf<Int, Runnable>()
    private var currentLongPressKey: Keyboard.Key? = null
    private var longPressTriggered = false
    private var longPressConsumed = false
    private var backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null

    // 发送键长按检测
    private var sendKeyLongPressTriggered = false
    private var sendKeyHandler = Handler(Looper.getMainLooper())
    private var sendKeyRunnable: Runnable? = null
    private var sendButtonGlowing = false

    // hjkl 方向键长按重复触发
    private var directionalRepeatRunnable: Runnable? = null
    private var directionalRepeatKeyCode: Int = 0
    private var directionalRepeatActive: Boolean = false
    private val directionalRepeatHandler = Handler(Looper.getMainLooper())
    private val DIRECTIONAL_REPEAT_INTERVAL = 80L  // 80ms 重复间隔

    // 智能修改按钮（魔法书）长按检测
    private var magicBookLongPressTriggered = false
    private var magicBookHandler = Handler(Looper.getMainLooper())
    private var magicBookRunnable: Runnable? = null
    // 全拼/简拼按钮双击检测定时器
    private val fenciHandler = Handler(Looper.getMainLooper())
    // 智能修改按钮（魔法书/笔）长按发光状态
    private var magicBookGlowing = false

    // 智能写作按钮（星星/四角星）发光状态
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

    // -100 键长按检测（符号键盘切换）
    private var symbolKeyLongPressRunnable: Runnable? = null
    private var defaultKeyboardLongPressRunnable: Runnable? = null
    // 当前正在计时长按的按键码（-999 表示无）。runnable 触发前校验，防止跨键/滑动泄漏误触发
    private var longPressOwnerCode = -999
    // 长按符号键弹出的分类符号面板
    private var symbolPanel: SymbolPanel? = null
    private var symbolPanelLongPressTriggered = false

    /** 长按符号切换键 → 弹出分类符号面板（PopupWindow） */
    private fun showSymbolPanel() {
        if (symbolPanel?.isShowing() == true) {
            symbolPanel?.dismiss()
            symbolPanel = null
            return
        }
        symbolPanel = SymbolPanel(
            this,
            keyboardView,
            themeAccent,
            onCommit = { sym ->
                currentInputConnection?.commitText(sym, 1)
            }
        )
        symbolPanel?.show()
    }

    private fun dismissSymbolPanel() {
        symbolPanel?.dismiss()
        symbolPanel = null
    }

    // 魔法模式
    private var magicMode = false
    private var magicOriginalText = ""
    private var magicIsWaitingForVoice = false
    private var lastMagicRecognizedText = ""  // 魔法模式最后一次识别的文本（用于停止时触发AI）
    private var magicStopRequested = false    // 用户主动停止魔法录音标志（防止重复触发AI）

    // 撤销历史
    private val undoHistory = mutableListOf<Pair<String, String>>()
    private val undoMaxSteps = 3

    // AI自动回复
    private var aiReplyStyle = "自然"
    private var isAiProcessing = false

    // 智能修改历史（魔法书）
    private var magicHistoryManager: MagicHistoryManager? = null
    private var currentMagicPrompt: String? = null

    // 发送消息历史
    private val sentMessages = mutableListOf<String>()
    private val maxSentMessages = 10

    // 剪贴板管理器：收藏/锁定条目 (text -> isLocked)
    private val clipboardFavorites = mutableMapOf<String, Boolean>()
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
            clipboardFilteredItems.addAll(clipboardItems.filter { matchesClipboardFilter(it.text, clipboardSearchFilter) })
        }
        clipboardAdapter?.notifyDataSetChanged()
        clipboardPopupView?.findViewById<TextView>(R.id.tv_clipboard_empty)?.visibility =
            if (clipboardFilteredItems.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 剪贴板搜索匹配：支持中文直接匹配、拼音首字母匹配、全拼匹配 */
    private fun matchesClipboardFilter(text: String, filter: String): Boolean {
        val f = filter.trim().lowercase()
        if (f.isEmpty()) return true
        // 1. 直接包含匹配（中文、英文、数字）
        if (text.contains(f, ignoreCase = true)) return true
        // 2. 拼音匹配：将文本转为拼音首字母和全拼进行匹配
        val pinyinFirst = toPinyinFirstLetters(text)
        val pinyinFull = toPinyinFull(text)
        return pinyinFirst.contains(f, ignoreCase = true) || pinyinFull.contains(f, ignoreCase = true)
    }

    /** 将中文转为拼音首字母（如：你好 -> nh） */
    private fun toPinyinFirstLetters(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') {
                sb.append(c.lowercase())
            } else if (c.toInt() >= 0x4E00 && c.toInt() <= 0x9FFF) { // 基本汉字范围
                sb.append(getPinyinFirstLetter(c))
            }
        }
        return sb.toString()
    }

    /** 将中文转为全拼（如：你好 -> nihao） */
    private fun toPinyinFull(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') {
                sb.append(c.lowercase())
            } else if (c.toInt() >= 0x4E00 && c.toInt() <= 0x9FFF) {
                sb.append(getPinyinFull(c))
            }
        }
        return sb.toString()
    }

    /** 获取单个汉字的拼音首字母 */
    private fun getPinyinFirstLetter(c: Char): String {
        // 简单的汉字拼音首字母映射（常用字覆盖）
        return when (c.toInt()) {
            in 0x4E00..0x4EFF -> "a" // 一丁七... (简化)
            in 0x4F00..0x4FFF -> "b"
            in 0x5000..0x50FF -> "c"
            in 0x5100..0x51FF -> "d"
            in 0x5200..0x52FF -> "e"
            in 0x5300..0x53FF -> "f"
            in 0x5400..0x54FF -> "g"
            in 0x5500..0x55FF -> "h"
            in 0x5600..0x56FF -> "j"
            in 0x5700..0x57FF -> "k"
            in 0x5800..0x58FF -> "l"
            in 0x5900..0x59FF -> "m"
            in 0x5A00..0x5AFF -> "n"
            in 0x5B00..0x5BFF -> "o"
            in 0x5C00..0x5CFF -> "p"
            in 0x5D00..0x5DFF -> "q"
            in 0x5E00..0x5EFF -> "r"
            in 0x5F00..0x5FFF -> "s"
            in 0x6000..0x60FF -> "t"
            in 0x6100..0x61FF -> "w"
            in 0x6200..0x62FF -> "x"
            in 0x6300..0x63FF -> "y"
            in 0x6400..0x64FF -> "z"
            in 0x6500..0x65FF -> "a"
            in 0x6600..0x66FF -> "b"
            in 0x6700..0x67FF -> "c"
            in 0x6800..0x68FF -> "d"
            in 0x6900..0x69FF -> "e"
            in 0x6A00..0x6AFF -> "f"
            in 0x6B00..0x6BFF -> "g"
            in 0x6C00..0x6CFF -> "h"
            in 0x6D00..0x6DFF -> "j"
            in 0x6E00..0x6EFF -> "k"
            in 0x6F00..0x6FFF -> "l"
            in 0x7000..0x70FF -> "m"
            in 0x7100..0x71FF -> "n"
            in 0x7200..0x72FF -> "o"
            in 0x7300..0x73FF -> "p"
            in 0x7400..0x74FF -> "q"
            in 0x7500..0x75FF -> "r"
            in 0x7600..0x76FF -> "s"
            in 0x7700..0x77FF -> "t"
            in 0x7800..0x78FF -> "w"
            in 0x7900..0x79FF -> "x"
            in 0x7A00..0x7AFF -> "y"
            in 0x7B00..0x7BFF -> "z"
            in 0x7C00..0x7CFF -> "a"
            in 0x7D00..0x7DFF -> "b"
            in 0x7E00..0x7EFF -> "c"
            in 0x7F00..0x7FFF -> "d"
            in 0x8000..0x80FF -> "e"
            in 0x8100..0x81FF -> "f"
            in 0x8200..0x82FF -> "g"
            in 0x8300..0x83FF -> "h"
            in 0x8400..0x84FF -> "j"
            in 0x8500..0x85FF -> "k"
            in 0x8600..0x86FF -> "l"
            in 0x8700..0x87FF -> "m"
            in 0x8800..0x88FF -> "n"
            in 0x8900..0x89FF -> "o"
            in 0x8A00..0x8AFF -> "p"
            in 0x8B00..0x8BFF -> "q"
            in 0x8C00..0x8CFF -> "r"
            in 0x8D00..0x8DFF -> "s"
            in 0x8E00..0x8EFF -> "t"
            in 0x8F00..0x8FFF -> "w"
            in 0x9000..0x90FF -> "x"
            in 0x9100..0x91FF -> "y"
            in 0x9200..0x92FF -> "z"
            in 0x9300..0x93FF -> "a"
            in 0x9400..0x94FF -> "b"
            in 0x9500..0x95FF -> "c"
            in 0x9600..0x96FF -> "d"
            in 0x9700..0x97FF -> "e"
            in 0x9800..0x98FF -> "f"
            in 0x9900..0x99FF -> "g"
            in 0x9A00..0x9AFF -> "h"
            in 0x9B00..0x9BFF -> "j"
            in 0x9C00..0x9CFF -> "k"
            in 0x9D00..0x9DFF -> "l"
            in 0x9E00..0x9EFF -> "m"
            0x9FFF -> "n"
            else -> ""
        }
    }

    /** 获取单个汉字的全拼（简化版，返回首字母） */
    private fun getPinyinFull(c: Char): String {
        // 简化：返回首字母，实际可接入完整拼音库
        return getPinyinFirstLetter(c)
    }

    // 初始化标志
    private var isViewInitialized = false

    // 清屏键长按标志
    private var deleteLongPressTriggered = false

    // 清空按钮发光状态
    private var deleteButtonGlowing = false
    private var deleteButtonGlowRunnable: Runnable? = null
    private var deleteGlowHandler = Handler(Looper.getMainLooper())

    // 语音按钮发光状态（锁定模式）
    private var micButtonGlowing = false

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

// endregion 状态变量

// region 简繁切换
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
            104 -> { { startDirectionalRepeat(KeyEvent.KEYCODE_DPAD_LEFT) } }  // h=左（长按重复）
            106 -> { { startDirectionalRepeat(KeyEvent.KEYCODE_DPAD_DOWN) } }  // j=下（长按重复）
            107 -> { { startDirectionalRepeat(KeyEvent.KEYCODE_DPAD_UP) } }  // k=上（长按重复）
            108 -> { { startDirectionalRepeat(KeyEvent.KEYCODE_DPAD_RIGHT) } }  // l=右（长按重复）
            // ZXCV行：编辑功能
            120 -> { { currentInputConnection?.performContextMenuAction(android.R.id.cut) } }  // x=剪切
            99  -> { { currentInputConnection?.performContextMenuAction(android.R.id.copy) } }  // c=复制
            118 -> { { currentInputConnection?.performContextMenuAction(android.R.id.paste) } }  // v=粘贴
            98  -> { { toggleUpperCase() } }  // b=大写转换
            122 -> { { sendCtrlKey(KeyEvent.KEYCODE_Z) } }  // z=撤销
            110 -> { { sendCtrlKey(KeyEvent.KEYCODE_Y) } }  // n=Redo（前进）
            109 -> { { startForwardDeleteRepeat() } }  // m=Delete（长按连续删除）
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

    // hjkl 方向键长按重复触发
    private fun startDirectionalRepeat(keyCode: Int) {
        // 如果已经在重复同一个键，不重复启动
        if (directionalRepeatRunnable != null && directionalRepeatKeyCode == keyCode) return

        // 停止之前的重复
        stopDirectionalRepeat()

        directionalRepeatKeyCode = keyCode
        directionalRepeatActive = true
        directionalRepeatRunnable = object : Runnable {
            override fun run() {
                if (!directionalRepeatActive) return
                sendControlKey(directionalRepeatKeyCode)
                directionalRepeatHandler.postDelayed(this, DIRECTIONAL_REPEAT_INTERVAL)
            }
        }
        // 先发送一次，然后开始重复
        sendControlKey(keyCode)
        directionalRepeatHandler.postDelayed(directionalRepeatRunnable!!, DIRECTIONAL_REPEAT_INTERVAL)
    }

    private fun stopDirectionalRepeat() {
        directionalRepeatActive = false
        directionalRepeatRunnable?.let { directionalRepeatHandler.removeCallbacks(it) }
        directionalRepeatRunnable = null
        directionalRepeatKeyCode = 0
    }

    // 全键盘 M 键长按：连续 forward delete（删除光标后的字符）
    private val forwardDeleteHandler = Handler(Looper.getMainLooper())
    private var forwardDeleteRunnable: Runnable? = null
    private var forwardDeleteActive = false
    private val FORWARD_DELETE_INTERVAL = 80L

    private fun startForwardDeleteRepeat() {
        if (forwardDeleteActive) return
        stopForwardDeleteRepeat()
        forwardDeleteActive = true
        forwardDeleteRunnable = object : Runnable {
            override fun run() {
                if (!forwardDeleteActive) return
                sendControlKey(KeyEvent.KEYCODE_FORWARD_DEL)
                forwardDeleteHandler.postDelayed(this, FORWARD_DELETE_INTERVAL)
            }
        }
        // 先删除一次，然后开始连续删除
        sendControlKey(KeyEvent.KEYCODE_FORWARD_DEL)
        forwardDeleteHandler.postDelayed(forwardDeleteRunnable!!, FORWARD_DELETE_INTERVAL)
    }

    private fun stopForwardDeleteRepeat() {
        forwardDeleteActive = false
        forwardDeleteRunnable?.let { forwardDeleteHandler.removeCallbacks(it) }
        forwardDeleteRunnable = null
    }

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
        updateStatus(" 已转换 ${selectedText.length} 字")
    }

    /** 大小写/数字切换：英文小写↔大写，阿拉伯数字↔中文小写数字（一二三四五六七八九零） */
    private fun toUpperCaseText(text: String): String {
        val chineseNumbers = charArrayOf('零','一','二','三','四','五','六','七','八','九')
        // 中文数字→阿拉伯数字的反向映射
        val chineseToArabic = mapOf(
            '零' to '0', '一' to '1', '二' to '2', '三' to '3', '四' to '4',
            '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9'
        )
        return text.map { ch ->
            when {
                ch in 'a'..'z' -> ch.uppercaseChar()
                ch in 'A'..'Z' -> ch.lowercaseChar()
                ch in '0'..'9' -> chineseNumbers[ch - '0']
                ch in chineseToArabic -> chineseToArabic[ch]!!
                else -> ch
            }
        }.joinToString("")
    }

    /** 如果有选中文本则删除选区，否则删除光标前一个字符 */
    /** 发送 Tab 键 */

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

    // ======================== OpenCC 简繁转换（委托到 utils/OpenCCConverter）========================
    /** 懒加载 OpenCC 映射表（委托到单例，只加载一次） */

    /** 简→繁转换（委托到 utils/OpenCCConverter） */
    private fun toTraditional(text: String): String = OpenCCConverter.toTraditional(text)

    /** 繁→简转换（委托到 utils/OpenCCConverter） */
    private fun toSimplified(text: String): String = OpenCCConverter.toSimplified(text)


// endregion 简繁切换

// region 常量配置
    companion object {
        const val PREF_API_URL = "api_url"
        const val PREF_MODEL_ID = "model_id"
        const val PREF_THEME_MODE = "theme_mode"
        const val PREF_AI_STYLE = "ai_reply_style"
        const val PREF_OPENROUTER_KEY = "openrouter_api_key"
        const val PREF_POLISH_PROMPT = "polish_prompt"
        const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_MODEL_ID = ""  // 默认空：需用户在设置页选择模型来源与模型
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

// endregion 常量配置

// region 生命周期
    // ======================== 生命周期 ========================

    override fun onCreate() {
        installCrashHandler()
        val themeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt(PREF_THEME_MODE, THEME_LIGHT)
        isDarkTheme = themeMode == THEME_DARK
        setTheme(if (isDarkTheme) R.style.Theme_Cesia_Dark else R.style.Theme_Cesia)
        super.onCreate()
        // 预加载 OpenCC 简繁映射：命令词检测(正体模式)与候选显示都依赖它，
        // 提前加载避免在语音命令路径里首次调用才懒加载导致的竞态/空映射。
        OpenCCConverter.load(assets)
    }

    /** 封测期：未捕获异常写入本地文件（不联网），便于真机崩溃后回收日志 */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val logFile = java.io.File(dir, "crash.log")
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val sb = StringBuilder()
                sb.append("=== CRASH $ts (thread=${thread.name}) ===\n")
                sb.append(android.util.Log.getStackTraceString(ex))
                sb.append("\n\n")
                logFile.appendText(sb.toString())
            } catch (_: Exception) { /* 写日志失败不影响默认处理 */ }
            defaultHandler?.uncaughtException(thread, ex)
        }
    }

    /**
     * 防闪烁：禁用全屏提取模式。
     * 部分 ROM（非三星机型）在输入框较小时会默认进入全屏提取视图，
     * 导致普通键盘窗口与全屏窗口反复切换 → 输入法整体闪烁。
     * 强制返回 false 让 IME 始终以常规窗口显示，根治此闪烁。
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        try {
            return createInputViewSafe()
        } catch (e: Throwable) {
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
        // 优先加载保存的主题色（必须在 inflate 之前）
        loadThemeColors()
        // 加载用户设定的默认键盘（长按切换键设定，打开输入法即用）
        try {
            val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            val savedDefault = prefs.getString("default_keyboard_mode", "NUMBER") ?: "NUMBER"
            defaultKeyboardMode = try { KeyboardMode.valueOf(savedDefault) } catch (_: Exception) { KeyboardMode.NUMBER }
        } catch (_: Exception) { defaultKeyboardMode = KeyboardMode.NUMBER }
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.input_view, null)

        keyboardView = view.findViewById(R.id.keyboard_view)
        // 同步持久化的全拼/简拼锁定态到键盘视图（keyboardView 此刻已初始化）
        keyboardView.t9FenCiLock = t9FenCiLock
        btnTraditional = view.findViewById(R.id.btn_traditional)
        btnCloud = view.findViewById(R.id.btn_cloud)
        micButton = view.findViewById(R.id.btn_mic)
        micButtonContainer = view.findViewById(R.id.mic_button_container)
        micWrapper = view.findViewById(R.id.mic_wrapper)
        tvMicZh = view.findViewById(R.id.tv_mic_zh)
        btnMicAi = view.findViewById(R.id.btn_mic_ai)
        btnMicNoAi = view.findViewById(R.id.btn_mic_noai)
        btnSettings = view.findViewById(R.id.btn_settings)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnClipboard = view.findViewById(R.id.btn_magic_book)
        btnMagic = view.findViewById(R.id.btn_magic)
        btnSend = view.findViewById(R.id.btn_send)
        statusDot = view.findViewById(R.id.v_status_dot)
        statusDotState = "idle"
        statusText = view.findViewById(R.id.tv_status)
        voiceWave = view.findViewById(R.id.v_voice_wave)
        btnTheme = view.findViewById(R.id.btn_theme)

        // 本地/云端模式切换已移除，统一使用长按语音键切换

        // 候选词栏
        candidateBar = view.findViewById(R.id.candidate_bar)
        btnCandidateExpand = view.findViewById(R.id.btn_candidate_expand)
        // tvT9Letters/dividerT9 已移除

        // RecyclerView 候选词列表
        rvCandidates = view.findViewById(R.id.rv_candidates)
        // 逐键选音：候选栏最左 4 字母点选区（点击锁定当前位字母）
        llT9Spell = view.findViewById(R.id.ll_t9_spell)
        t9SpellTVs = listOf(
            view.findViewById(R.id.tv_t9_spell0),
            view.findViewById(R.id.tv_t9_spell1),
            view.findViewById(R.id.tv_t9_spell2),
            view.findViewById(R.id.tv_t9_spell3)
        )
        t9SpellTVs?.forEachIndexed { idx, tv ->
            tv.isClickable = true
            tv.isEnabled = true
            tv.setOnClickListener { onT9SpellLetterClick(idx) }
        }
        candidateAdapter = CandidateAdapter(
            onItemClick = { index, _ ->
                if (rimeEngine.hasCandidates || isAssociationMode) {
                    selectCandidateByGlobalIndex(index)
                }
            },
            onItemLongClick = { view, index, word ->
                showCandidateLongPressMenu(word, view, index)
                true
            }
        )
        rvCandidates?.adapter = candidateAdapter
        rvCandidates?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
        )
        // 候选懒加载：横向滚到右端附近(距底3个)时，拉下一批 50 候选
        rvCandidates?.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dx <= 0) return  // 只向右滚时触发
                val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                val total = rv.adapter?.itemCount ?: 0
                if (lm.findLastVisibleItemPosition() >= total - 3) {
                    loadMoreCandidates()
                }
            }
        })

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
        currentKeyboard = if (defaultKeyboardMode == KeyboardMode.QWERTY) qwertyKeyboard else numberKeyboard
        keyboardMode = defaultKeyboardMode

        keyboardView.keyboard = currentKeyboard
        keyboardView.isT9Mode = (defaultKeyboardMode == KeyboardMode.NUMBER)
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
            98 to "大小",  // b
            122 to "撤销", // z
            110 to "前进",  // n
            109 to "Del",  // m
            // T9 底行功能键副字符（灰色，右上角）
            -108 to "粘贴",  // 粘贴键：副字符
            -109 to "剪切",  // 复制键：副字符
            10 to "撤销",    // 回车键：副字符
            -102 to "默认",  // 全键盘/T9 切换键：长按设该键盘为默认
            -999 to "默认"   // T9 全键盘切换键(⌨)：长按设全键盘为默认
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
        downloadManager = ModelDownloadManager(this)
        dictManager = PinyinDictManager(this)
        voiceEngine = VoiceEngine(this)
        aiEngine = AIEngine(this)

        // 从 SharedPreferences 加载自定义命令词（跨进程同步：设置页面保存后，IME 启动时读取）
        runCatching {
            val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
            val exit = cmdPrefs.getString("cmd_exit", null)
            val polish = cmdPrefs.getString("cmd_polish", null)
            val finish = cmdPrefs.getString("cmd_finish", null)
            val send = cmdPrefs.getString("cmd_send", null)
            val command = cmdPrefs.getString("cmd_command", null)
            val writing = cmdPrefs.getString("cmd_writing", null)
            val undo = cmdPrefs.getString("cmd_undo", "撤销") ?: "撤销"
            val clear = cmdPrefs.getString("cmd_clear", "清空") ?: "清空"
            val restore = cmdPrefs.getString("cmd_restore", "恢复") ?: "恢复"
            if (exit != null && polish != null && finish != null && send != null && command != null && writing != null) {
                VoiceEngine.updateCommandWords(exit, polish, finish, send, command, writing, undo, clear, restore)
                Log.i("Cesia", "初始化: 已加载自定义命令词 exit=$exit, polish=$polish, finish=$finish, send=$send, command=$command, writing=$writing, undo=$undo, clear=$clear, restore=$restore")
            }
        }

        // 从 SharedPreferences 恢复本地/云端模式（确保与 polishRecognizedText 读取同一数据源）
        val modePrefs = getSharedPreferences("cesia_local_mode", Context.MODE_PRIVATE)
        val savedMode = modePrefs.getString("run_mode", LocalModeManager.RunMode.CLOUD_FREE.name)
            ?: LocalModeManager.RunMode.CLOUD_FREE.name
        localModeEnabled = (savedMode == LocalModeManager.RunMode.LOCAL.name)
        Log.i("Cesia", "初始化: 从 SharedPreferences 恢复 localModeEnabled=$localModeEnabled (run_mode=$savedMode)")

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
                // 仅当历史记录模式开启时写入（与语音路径一致）
                val historyMode = getSharedPreferences("cesia_polish_history", MODE_PRIVATE)
                    .getString("history_mode", "off") ?: "off"
                if (historyMode != "off") {
                    statsManager.addRecord(
                        inputText = inputText,
                        outputText = outputText,
                        voiceDurationMs = duration,
                        voiceRawText = inputText,
                        type = "voice"
                    )
                }
                // 每5条记录自动更新语法大纲
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        val guideMgr = com.cesia.input.stats.GrammarGuideManager(this@CesiaInputMethod)
                        val recordCount = statsManager.getRecords().size
                        if (guideMgr.needsUpdate(recordCount)) {
                            Log.d("Cesia", "语法大纲自动更新: 当前记录数=$recordCount, 上次更新=${guideMgr.lastRecordCount}")
                            val records = statsManager.getRecords()
                            val newGuide = guideMgr.generateGuide(records) { text, instruction ->
                                typelessEngine?.getPolishService()?.polishWithPrompt(text)
                            }
                            if (!newGuide.isNullOrEmpty()) {
                                guideMgr.saveGuide(newGuide)
                                guideMgr.updateRecordCount(recordCount)
                                Log.d("Cesia", "语法大纲自动更新成功: 版本=${guideMgr.version}, 长度=${newGuide.length}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Cesia", "语法大纲自动更新失败", e)
                    }
                }
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
                    updateStatus(" 已完成")
                }
            }
            engine.onRecognitionComplete = { text ->
                Handler(Looper.getMainLooper()).post {
                    // 语音结果统一转阿拉伯数字（Google 路径兜底，与本地 sherpa 路径一致）
                    val text = voiceEngine.convertChineseDigitsToArabic(text)
                    // 魔法模式停止时，直接用 Google 识别结果触发 AI
                    if (magicStopRequested) {
                        Log.d("Cesia", "onRecognitionComplete: 魔法模式停止中，直接触发 AI")
                        magicStopRequested = false
                        if (text.isNotEmpty()) {
                            handleMagicResult(text)
                        }
                        return@post
                    }
                    // 命令词检测（Google 识别结果走这里）；复用 VoiceEngine 统一实现
                    val commandResult = voiceEngine.checkCommandWord(text)
                    if (commandResult != null) {
                        val (textBefore, command) = commandResult
                        Log.i("Cesia", "命令词检测(Google): command='$command', text='${textBefore.take(50)}'")
                        recognizedText = textBefore
                        isRecording = false
                        stopVoiceWave()
                        setStatusDot("idle")
                        isWaitingForChoice = false
                        hideAiChoiceButtons()

                        if (textBefore.isEmpty()) {
                            updateStatus("⚠️ 未识别到文字")
                            resetToIdle()
                            return@post
                        }

                        if (command == "ai") {
                            updateStatus("✨ 语音润色中...")
                            setStatusDot("processing")
                            isProcessingResult = true
                            polishRecognizedText(textBefore)
                        } else if (command == "writing") {
                            // 写作命令：延迟1秒执行智能写作
                            updateStatus("✨ 语音写作中...")
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1000)
                                // 删除完整语音识别文本（含命令词）
                                val ic = currentInputConnection
                                if (ic != null) {
                                    ic.deleteSurroundingText(text.trimEnd().length, 0)
                                }
                                executeSmartCommand(textBefore)
                                // 退出语音输入模式（除非锁定）
                                if (isVoiceLocked) {
                                    startRecordingLocked()
                                } else {
                                    isVoiceLocked = false
                                    updateMicButtonLockedState()
                                    resetToIdle()
                                }
                            }
                        } else {
                            currentInputConnection?.commitText(textBefore, 1)
                            updateStatus(" 已上屏")
                            if (isVoiceLocked) {
                                startRecordingLocked()
                            } else {
                                resetToIdle()
                            }
                        }
                        return@post
                    }

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
        // 加载云按钮状态
        loadCloudMode()
        updateCloudButtonState()
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        typelessEngine?.updateModelId(prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID)
        // 加载用户自定义润色 prompt 并同步到云端和本地引擎
        val polishPrompt = prefs.getString(PREF_POLISH_PROMPT, null)
        if (!polishPrompt.isNullOrEmpty()) {
            typelessEngine?.getPolishService()?.updatePolishPrompt(polishPrompt)
            aiEngine.customPolishPrompt = polishPrompt
        }
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

        // 应用动态主题色到主输入视图树
        applyAccentToViewTree(view, themeAccent)
        applyThemeColors()

        // 启动输入法后自动检测并下载语音文字套件（三件套），不挑网络、后台进行
        ensureVoiceSuite()

        return view
    }

    /**
     * 启动输入法后自动检测语音文字套件（三件套）是否已安装：
     * 中英双语模型 + 纯中文模型 + 雾凇词库。任一缺失即后台自动下载（不挑网络）。
     * 进度复用状态栏（updateStatus），关键节点写入运行日志。
     */
    private fun ensureVoiceSuite() {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val voiceInstalled = modelManager.getInstalledVoiceModelFile() != null
                val dictInstalled = dictManager.hasDownloadedDict()
                val zhInstalled = java.io.File(filesDir, "local_models/zipformer-zh-2025/encoder.onnx").exists()
                if (voiceInstalled && dictInstalled && zhInstalled) {
                    Log.i("Cesia", "语音文字套件已完整，跳过自动下载")
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    updateStatus("开始下载语音文字输入套件（Zipformer中英双语 / Zipformer纯中文2025 / 雾凇词库）")
                }
                appendSuiteLog("开始下载语音文字输入套件（Zipformer中英双语 / Zipformer纯中文2025 / 雾凇词库）")

                // 1) 中英双语模型
                if (!voiceInstalled) {
                    withContext(Dispatchers.Main) { updateStatus("下载中：Zipformer中英双语模型...") }
                    var lastPct1 = -1.0
                    downloadManager.downloadZipformer { _: String, percent: Double, _: Long, _: Long ->
                        if (kotlin.math.abs(percent - lastPct1) >= 0.3) {
                            lastPct1 = percent
                            val txt = "下载中：中英双语模型 ${String.format("%.1f", percent)}%"
                            updateStatus(txt)
                            DownloadProgressBus.emit("中英双语模型", percent)
                        }
                    }
                }
                // 2) 纯中文模型
                if (!zhInstalled) {
                    withContext(Dispatchers.Main) { updateStatus("下载中：Zipformer纯中文2025模型...") }
                    var lastPct2 = -1.0
                    downloadManager.downloadArchive("zipformer-zh-2025") { _: String, percent: Double, _: Long, _: Long ->
                        if (kotlin.math.abs(percent - lastPct2) >= 0.3) {
                            lastPct2 = percent
                            val txt = "下载中：纯中文模型 ${String.format("%.1f", percent)}%"
                            updateStatus(txt)
                            DownloadProgressBus.emit("纯中文模型", percent)
                        }
                    }
                }
                // 3) 雾凇词库
                if (!dictInstalled) {
                    withContext(Dispatchers.Main) { updateStatus("下载中：雾凇词库...") }
                    val deferred = CompletableDeferred<Boolean>()
                    dictManager.downloadFullDict(
                        onProgress = { percent: Int, _: Long, _: Long, _: String ->
                            val pct = percent.toDouble()
                            val txt = "下载中：雾凇词库 ${String.format("%.1f", pct)}%"
                            updateStatus(txt)
                            DownloadProgressBus.emit("雾凇词库", pct)
                        },
                        onComplete = { ok: Boolean, _: String ->
                            deferred.complete(ok)
                        }
                    )
                    deferred.await()
                }
                withContext(Dispatchers.Main) {
                    updateStatus("语音文字输入套件已下载完成，可以正常使用")
                }
                appendSuiteLog("语音文字输入套件已下载完成，可以正常使用")
            } catch (e: Exception) {
                Log.e("Cesia", "ensureVoiceSuite 失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateStatus("语音套件下载失败：${e.message ?: "未知错误"}（可到设置页重试）")
                }
                appendSuiteLog("❌ 语音套件自动下载失败: ${e.message}")
            }
        }
    }

    /** 写入设置页运行日志（与 SettingsActivity 共享 cesia_settings 中的 run_log） */
    private fun appendSuiteLog(msg: String) {
        try {
            val sp = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(System.currentTimeMillis())
            val line = "[$ts] $msg\n"
            val existing = sp.getString("run_log", "") ?: ""
            sp.edit().putString("run_log", line + existing).apply()
        } catch (_: Exception) {}
    }

// endregion 生命周期

// region 主题
    // ======================== 主题 ========================

    private fun applyKeyboardTheme() {
        val keyBgColor: Int
        if (isDarkTheme) {
            keyBgColor = 0xFF0F0F23.toInt()
            keyboardView.setBackgroundColor(keyBgColor)
            (statusText.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
            candidateBar.setBackgroundColor(0xFF16213E.toInt())
            (btnClipboard.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
        } else {
            // 使用动态背景灰度
            val base = themeBgGrayBase
            keyBgColor = colorGray(base)
            keyboardView.setBackgroundColor(keyBgColor)
            (statusText.parent as? View)?.setBackgroundColor(colorGray((base - 8).coerceIn(0, 255)))
            candidateBar.setBackgroundColor(colorGray((base + 16).coerceIn(0, 255)))
            (btnClipboard.parent as? View)?.setBackgroundColor(colorGray(base))
            // root_layout
            (keyboardView.parent as? View)?.setBackgroundColor(colorGray((base + 23).coerceIn(0, 255)))
        }
    }

    private fun colorGray(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return 0xFF000000.toInt() or (c shl 16) or (c shl 8) or c
    }

    // 当前按键灰度背景色（供触摸回调恢复使用，确保暗黑/灰度状态下一致）
    private var currentKeyBg: Int = 0

    /** 生成与键盘按键同款的圆角灰底+描边背景 drawable */
    private fun makeKeyBgDrawable(keyBgColor: Int): android.graphics.drawable.GradientDrawable =
        ColorUtils.makeKeyBgDrawable(keyBgColor, resources.displayMetrics.density)

    /**
     * 随手机时间自动变化主题色：根据当前小时(0-23)计算色相。
     * 一天从早到晚：黎明暖橙(约40°)→上午明黄绿(约90°)→正午青蓝(蒂芙尼180°)→
     * 黄昏暖紫(约300°)→深夜冷蓝(约220°)，形成从早到晚的渐变循环。
     */
    private fun timeBasedHue(): Float = ColorUtils.timeBasedHue()

    /** 应用随手机时间主题：计算 hue → 更新 accentHue/themeAccent → 应用并保存 */
    private fun applyAutoTimeTheme() {
        if (!autoTimeTheme) return
        accentHue = timeBasedHue()
        themeAccent = hslToColor(accentHue, defaultAccentHsl[1], defaultAccentHsl[2])
        applyThemeColors()
    }

    /** 启动/停止随手机时间主题的定时刷新（每分钟检查一次） */
    private fun startAutoTimeTheme() {
        stopAutoTimeTheme()
        if (!autoTimeTheme) return
        autoThemeRunnable = object : Runnable {
            override fun run() {
                if (!autoTimeTheme) { stopAutoTimeTheme(); return }
                applyAutoTimeTheme()
                autoThemeHandler.postDelayed(this, 60_000L)
            }
        }
        autoThemeHandler.post(autoThemeRunnable!!)
    }

    private fun stopAutoTimeTheme() {
        autoThemeRunnable?.let { autoThemeHandler.removeCallbacks(it) }
        autoThemeRunnable = null
    }

    /** 实时应用主题色 + 背景灰度 + 按键灰度到所有UI元素 */
    private fun applyThemeColors() {
        // ① 背景灰度
        applyKeyboardTheme()
        updateMicZhLabel()   // 刷新语音键“中”副字符（随模式显示/隐藏）

        // ② 主题色 —— 所有高亮元素
        val accent = themeAccent
        val accentStateList = android.content.res.ColorStateList.valueOf(accent)

        // 简繁切换：仅在 traditionalGlowing（即正体模式）时随主题刷新；
        // 简体模式下跳过，避免主题漂移/长按功能键时简繁键背景闪动
        if (::btnTraditional.isInitialized && traditionalGlowing) {
            btnTraditional.setTextColor(accent)
            btnTraditional.setBackgroundColor((accent and 0x00FFFFFF) or 0x22000000)
        }

        // 功能按钮层级（智能写作、修改、清退、发送） - 移除阴影
        btnMagic?.elevation = 0f
        btnClipboard?.elevation = 0f
        btnDelete?.elevation = 0f
        btnSend?.elevation = 0f
        btnTraditional.elevation = 0f

        // 云/本地切换按钮
        if (::btnCloud.isInitialized) {
            val cloudActive = cloudMode == CloudMode.CLOUD || cloudMode == CloudMode.LOCAL_LOCKED
            val hasAi = modelManager.hasAiModel()
            // 云模式或本地锁定 → 高亮主题色；本地模式 → 灰色
            // 未安装手机AI模型时，本地模式不可用：按钮灰度 + 禁用
            if (!hasAi) {
                btnCloud.setTextColor(0xFF888888.toInt())
                btnCloud.isEnabled = false
                btnCloud.alpha = 0.4f
            } else {
                btnCloud.setTextColor(if (cloudActive) accent else 0xFF888888.toInt())
                btnCloud.isEnabled = true
                btnCloud.alpha = 1f
            }
        }

        // 语音键底色
        micButton?.backgroundTintList = accentStateList
        btnMicAi?.backgroundTintList = accentStateList
        btnMicAi?.setTextColor(0xFFFFFFFF.toInt())
        btnMicNoAi?.setTextColor(accent)
        // AI× 按键边框随主题色变化
        btnMicNoAi?.strokeColor = android.content.res.ColorStateList.valueOf(accent)
        btnMicNoAi?.strokeWidth = (1.5f * resources.displayMetrics.density).toInt()
        // 状态栏圆点：随主题色实时重绘（拖主题杆时直接变，无需重启）
        redrawStatusDot()
        // 键盘副字符色（T9 数字等）
        if (::keyboardView.isInitialized) {
            // 副字符颜色跟随主题色
        }

        // ③ 按键灰度 —— 底栏按钮、键盘按键背景
        val keyBgRaw = if (isDarkTheme) 0x2A else themeKeyGrayBase
        val keyBg = colorGray(keyBgRaw)
        currentKeyBg = keyBg
        val keyBgList = android.content.res.ColorStateList.valueOf(keyBg)
        // 底栏按钮默认背景（与键盘按键同款圆角灰底+描边，保持风格统一）
        val keyBgDrawable = makeKeyBgDrawable(keyBg)
        if (!magicIsWaitingForVoice && !isRecording) {
            btnMagic.background = keyBgDrawable.constantState?.newDrawable()?.mutate() ?: keyBgDrawable
        }
        if (!magicBookLongPressTriggered) {
            btnClipboard.background = keyBgDrawable.constantState?.newDrawable()?.mutate() ?: keyBgDrawable
        }
        if (!sendKeyLongPressTriggered) {
            btnSend.background = keyBgDrawable.constantState?.newDrawable()?.mutate() ?: keyBgDrawable
        }
        btnDelete.background = keyBgDrawable.constantState?.newDrawable()?.mutate() ?: keyBgDrawable
        // 键盘按键背景（动态替换 drawable）
        if (::keyboardView.isInitialized) {
            keyboardView.updateKeyBackground(keyBg)
            keyboardView.themeAccent = accent
            // 文字大小缩放
            keyboardView.textScaleFactor = when (textThemeSize) {
                0 -> 0.85f
                2 -> 1.2f
                3 -> 1.5f
                else -> 1f
            }
            keyboardView.invalidateAllKeys()
        }

        // ④ 自动对比文字颜色（根据背景灰度）
        applyAutoContrast()

        // ⑤ 文字灰阶缩放
        applyTextGrayScale()

        // 语音锁定高亮状态也用主题色
        if (simulTranslateEnabled) {
            micButton?.setBackgroundColor(accent)
        }

        // 候选音（候选栏最左字母点选区）跟随主题色实时变化（无需重开输入法）
        t9SpellTVs?.forEach { it.setTextColor(accent) }

        // 候选栏文字色（字/词）保持原固定色，不随主题变（仅候选音随主题变）

        // 持久化
        saveThemeColors()
    }

    // 统一的文字/图标基准颜色（深背景→亮色，浅背景→暗色）
    // textGrayScale: 0=纯黑, 0.5=基准灰(自动对比色), 1.0=纯白
    // 最终颜色 = lerp(黑, 基准灰, textGrayScale*2) when scale<=0.5
    //           lerp(基准灰, 白, (textGrayScale-0.5)*2) when scale>0.5
    val unifiedTextColor: Int
        get() {
            val bgGray = if (isDarkTheme) 20 else themeBgGrayBase
            return if (bgGray < 128) 0xFFE0E0E0.toInt() else 0xFF333333.toInt()
        }

    /** 根据背景灰度自动调整文字颜色（暗背景→亮字，亮背景→暗字） */
    private fun applyAutoContrast() {
        val textColor = unifiedTextColor

        // 状态栏文字
        statusText.setTextColor(textColor)

        // 候选栏文字（遍历子元素）
        if (::candidateBar.isInitialized) {
            for (i in 0 until candidateBar.childCount) {
                val child = candidateBar.getChildAt(i)
                if (child is android.widget.TextView) {
                    child.setTextColor(textColor)
                }
            }
        }

        // 底栏按钮图标颜色：无色描边，跟随背景灰度的自动对比色（中性外边框）
        val iconColor = unifiedTextColor
        btnMagic.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        btnClipboard.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        btnSend.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        btnDelete.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)

        // 键盘按键文字颜色（使用统一颜色）
        if (::keyboardView.isInitialized) {
            keyboardView.unifiedKeyColor = textColor
            keyboardView.updateTextColor(isDarkTheme)
        }
    }

    /** 将文字灰阶缩放应用到各 UI 组件（统一基准颜色） */
    private fun applyTextGrayScale() {
        val scale = textGrayScale
        val baseColor = unifiedTextColor

        // 状态栏文字
        statusText.setTextColor(scaleGray(baseColor, scale))

        // 候选栏文字
        if (::candidateBar.isInitialized) {
            for (i in 0 until candidateBar.childCount) {
                val child = candidateBar.getChildAt(i)
                if (child is android.widget.TextView) {
                    child.setTextColor(scaleGray(baseColor, scale))
                }
            }
        }

        // 候选栏 RecyclerView 文字大小和颜色
        if (rvCandidates != null && candidateAdapter != null) {
            val candScale = when (textThemeSize) {
                0 -> 0.85f
                2 -> 1.2f
                3 -> 1.5f
                else -> 1f
            }
            candidateAdapter!!.textScaleFactor = candScale
            candidateAdapter!!.textColor = scaleGray(baseColor, scale)
            candidateAdapter!!.notifyDataSetChanged()
        }

        // 键盘按键灰阶
        if (::keyboardView.isInitialized) {
            keyboardView.textGrayScale = scale
        }

        // 候选栏展开面板（GridView）刷新大小和颜色
        panelAdapter?.notifyDataSetChanged()

        // 底栏按钮图标颜色（深色模式用主题色，避免过亮）
        val iconColor = if (isDarkTheme) themeAccent else scaleGray(baseColor, scale)
        btnMagic.setColorFilter(if (!magicIsWaitingForVoice && !isRecording) themeAccent else iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        btnClipboard.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        btnSend.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        btnDelete.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
    }

    /** 对基准颜色应用灰阶缩放（在黑白之间插值） */
    private fun scaleGray(baseColor: Int, scale: Float): Int {
        val a = (baseColor ushr 24) and 0xFF
        val br = ((baseColor shr 16) and 0xFF)
        val bg = ((baseColor shr 8) and 0xFF)
        val bb = (baseColor and 0xFF)
        // scale 0→黑, 0.5→baseColor, 1→白
        val t = scale.coerceIn(0f, 1f)
        val r = if (t <= 0.5f) (br * (t * 2)).toInt() else (br + (255 - br) * ((t - 0.5f) * 2)).toInt()
        val g = if (t <= 0.5f) (bg * (t * 2)).toInt() else (bg + (255 - bg) * ((t - 0.5f) * 2)).toInt()
        val b = if (t <= 0.5f) (bb * (t * 2)).toInt() else (bb + (255 - bb) * ((t - 0.5f) * 2)).toInt()
        return (a shl 24) or (r.coerceIn(0,255) shl 16) or (g.coerceIn(0,255) shl 8) or b.coerceIn(0,255)
    }

    /** 主题菜单弹窗 */
    private fun showThemePopup() {
        dismissAllPopups()
        val view = LayoutInflater.from(this).inflate(R.layout.popup_theme, null)
        // 立刻应用当前主题色到弹窗内所有硬编码的蒂芙尼蓝元素
        applyAccentToViewTree(view, themeAccent)
        // banner 主题色
        view.findViewById<android.view.View>(R.id.banner_bar)?.setBackgroundColor(themeAccent)
        val popup = PopupWindow(
            view,
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        themePopup = popup
        view.findViewById<android.widget.TextView>(R.id.btn_theme_close)?.setOnClickListener {
            popup.dismiss()
        }

        val seekHue = view.findViewById<android.widget.SeekBar>(R.id.seek_hue)
        val seekGray = view.findViewById<android.widget.SeekBar>(R.id.seek_gray)
        val seekKey = view.findViewById<android.widget.SeekBar>(R.id.seek_key)
        val tvHue = view.findViewById<android.widget.TextView>(R.id.tv_hue_preview)
        val btnReset = view.findViewById<android.widget.TextView>(R.id.btn_reset_theme)

        // 明暗模式按钮（仅明亮/黑暗，去掉随系统）
        val btnThemeLight = view.findViewById<android.widget.TextView>(R.id.btn_theme_light)
        val btnThemeDark = view.findViewById<android.widget.TextView>(R.id.btn_theme_dark)

        // 文字大小按钮
        val btnTextSmall = view.findViewById<android.widget.TextView>(R.id.btn_text_small)
        val btnTextMedium = view.findViewById<android.widget.TextView>(R.id.btn_text_medium)
        val btnTextLarge = view.findViewById<android.widget.TextView>(R.id.btn_text_large)
        val btnTextXLarge = view.findViewById<android.widget.TextView>(R.id.btn_text_xlarge)

        // 文字灰度调节
        val seekTextGray = view.findViewById<android.widget.SeekBar>(R.id.seek_text_gray)
        val tvTextGrayPreview = view.findViewById<android.widget.TextView>(R.id.tv_text_gray_preview)

        // 初始化为当前值（不是默认值，解决重开不同步问题）
        seekHue.progress = accentHue.toInt()
        seekGray.progress = themeBgGrayBase
        seekKey.progress = themeKeyGrayBase

        // 初始化 SeekBar 色调和预览框背景（使用当前 themeAccent）
        val initialAccentList = android.content.res.ColorStateList.valueOf(themeAccent)
        seekHue.progressTintList = initialAccentList
        seekHue.thumbTintList = initialAccentList
        tvHue.background = makeKeyBgDrawable(themeAccent)

        // 初始化明暗模式按钮状态
        val currentThemeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt(PREF_THEME_MODE, THEME_LIGHT)
        updateThemeModeButtons(btnThemeLight, btnThemeDark, currentThemeMode)

        // 初始化文字大小按钮状态
        updateTextSizeButtons(btnTextSmall, btnTextMedium, btnTextLarge, btnTextXLarge, textThemeSize)

        // 初始化文字灰度滑块
        seekTextGray.progress = (textGrayScale * 100f).toInt().coerceIn(0, 100)
        tvTextGrayPreview.text = String.format("%.1f", textGrayScale)

        // 明暗模式切换（仅明亮/黑暗）
        btnThemeLight.setOnClickListener {
            isDarkTheme = false
            getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
                .putInt(PREF_THEME_MODE, THEME_LIGHT).apply()
            updateThemeModeButtons(btnThemeLight, btnThemeDark, THEME_LIGHT)
            applyThemeColors()
        }
        btnThemeDark.setOnClickListener {
            isDarkTheme = true
            getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
                .putInt(PREF_THEME_MODE, THEME_DARK).apply()
            updateThemeModeButtons(btnThemeLight, btnThemeDark, THEME_DARK)
            applyThemeColors()
        }

        // 文字大小切换
        btnTextSmall.setOnClickListener {
            textThemeSize = 0
            updateTextSizeButtons(btnTextSmall, btnTextMedium, btnTextLarge, btnTextXLarge, 0)
            applyThemeColors()
        }
        btnTextMedium.setOnClickListener {
            textThemeSize = 1
            updateTextSizeButtons(btnTextSmall, btnTextMedium, btnTextLarge, btnTextXLarge, 1)
            applyThemeColors()
        }
        btnTextLarge.setOnClickListener {
            textThemeSize = 2
            updateTextSizeButtons(btnTextSmall, btnTextMedium, btnTextLarge, btnTextXLarge, 2)
            applyThemeColors()
        }
        btnTextXLarge.setOnClickListener {
            textThemeSize = 3
            updateTextSizeButtons(btnTextSmall, btnTextMedium, btnTextLarge, btnTextXLarge, 3)
            applyThemeColors()
        }

        // 文字灰度调节
        seekTextGray.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                textGrayScale = progress / 100f
                tvTextGrayPreview.text = String.format("%.1f", textGrayScale)
                applyTextGrayScale()
                saveThemeColors()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        seekHue.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                accentHue = progress.toFloat()
                themeAccent = hslToColor(accentHue, defaultAccentHsl[1], defaultAccentHsl[2])
                tvHue.background = makeKeyBgDrawable(themeAccent)
                // SeekBar 自身的 tint 也跟主题色走
                val accentStateList = android.content.res.ColorStateList.valueOf(themeAccent)
                seekHue.progressTintList = accentStateList
                seekHue.thumbTintList = accentStateList
                applyThemeColors()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        seekGray.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                themeBgGrayBase = progress
                applyThemeColors()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        seekKey.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                themeKeyGrayBase = progress
                applyThemeColors()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        btnReset.setOnClickListener {
            // 默认值：主题色蒂芙尼蓝，背景/按键灰度1.0(255)，文字灰度0.7，文字大小"小"(0)，明暗模式"明亮"
            accentHue = defaultAccentHsl[0]
            themeAccent = hslToColor(defaultAccentHsl[0], defaultAccentHsl[1], defaultAccentHsl[2])
            themeBgGrayBase = 0xFF
            themeKeyGrayBase = 0xFF
            textThemeSize = 0
            textGrayScale = 0.5f
            seekHue.progress = accentHue.toInt()
            seekGray.progress = themeBgGrayBase
            seekKey.progress = themeKeyGrayBase
            updateTextSizeButtons(btnTextSmall, btnTextMedium, btnTextLarge, btnTextXLarge, 0)
            seekTextGray.progress = 50
            textGrayScale = 0.5f
            tvTextGrayPreview.text = "0.5"
            // 重置明暗模式为明亮
            isDarkTheme = false
            getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
                .putInt(PREF_THEME_MODE, THEME_LIGHT).apply()
            updateThemeModeButtons(btnThemeLight, btnThemeDark, THEME_LIGHT)
            applyThemeColors()
        }

        // 随手机时间自动变化主题色：共用主题色切换条旁的颜色框(tv_hue_preview)，不再单独显示框
        val checkAutoTime = view.findViewById<android.widget.CheckBox>(R.id.check_auto_time_theme)
        val tvAutoPreview = tvHue
        checkAutoTime.isChecked = autoTimeTheme
        val refreshAutoPreview = {
            tvAutoPreview.background = makeKeyBgDrawable(themeAccent)
        }
        refreshAutoPreview()
        checkAutoTime.setOnCheckedChangeListener { _, isChecked ->
            autoTimeTheme = isChecked
            saveThemeColors()
            if (isChecked) {
                applyAutoTimeTheme()
                startAutoTimeTheme()
            } else {
                stopAutoTimeTheme()
            }
            refreshAutoPreview()
        }

        popup.setOnDismissListener { themePopup = null }
        popup.showAtLocation(keyboardView, android.view.Gravity.CENTER, 0, 0)
    }

    private fun updateThemeModeButtons(btnLight: android.widget.TextView, btnDark: android.widget.TextView, mode: Int) {
        val accent = themeAccent
        val inactiveColor = 0xFF666666.toInt()
        btnLight.setTextColor(if (mode == THEME_LIGHT) accent else inactiveColor)
        btnLight.setTypeface(null, if (mode == THEME_LIGHT) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        btnDark.setTextColor(if (mode == THEME_DARK) accent else inactiveColor)
        btnDark.setTypeface(null, if (mode == THEME_DARK) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun updateTextSizeButtons(btnSmall: android.widget.TextView, btnMedium: android.widget.TextView, btnLarge: android.widget.TextView, btnXLarge: android.widget.TextView, size: Int) {
        val accent = themeAccent
        val inactiveColor = 0xFF666666.toInt()
        btnSmall.setTextColor(if (size == 0) accent else inactiveColor)
        btnSmall.setTypeface(null, if (size == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        btnMedium.setTextColor(if (size == 1) accent else inactiveColor)
        btnMedium.setTypeface(null, if (size == 1) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        btnLarge.setTextColor(if (size == 2) accent else inactiveColor)
        btnLarge.setTypeface(null, if (size == 2) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        btnXLarge.setTextColor(if (size == 3) accent else inactiveColor)
        btnXLarge.setTypeface(null, if (size == 3) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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

// endregion 主题

// region 候选栏
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

                // 基础字号由文字大小档位决定
                val baseSp = when (textThemeSize) {
                    0 -> 12f
                    2 -> 16f
                    3 -> 18f
                    else -> 14f
                }
                // 自动缩小字号：如果文字宽度超过列宽，按比例缩小
                val text = getItem(position) ?: ""
                var size = baseSp
                if (text.isNotEmpty() && columnWidthPx > 0) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                    val paint = tv.paint
                    while (size > minTextSp && paint.measureText(text) > columnWidthPx) {
                        size -= 0.5f
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                    }
                } else {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                }
                tv.setTextColor(scaleGray(unifiedTextColor, textGrayScale))

                return tv
            }
        }
        gvCandidates.adapter = panelAdapter

        // 候选面板纵向懒加载：滚到底部附近时拉下一批 50 候选
        gvCandidates.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScroll(view: android.widget.AbsListView?, firstVisible: Int, visible: Int, total: Int) {
                if (total <= 0) return
                if (firstVisible + visible >= total - 3) {
                    loadMoreCandidates()
                }
            }
            override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {}
        })

        // GridView 点击选候选词
        gvCandidates.setOnItemClickListener { _, _, position, _ ->
            selectCandidateByGlobalIndex(position)
            // 选中后候选面板滚动回顶部（高频词），避免停在当前滚动位置
            gvCandidates.post { gvCandidates.setSelection(0) }
        }

        // 收起按钮
        btnPanelClose.setOnClickListener {
            collapseCandidatePanel()
        }
    }

    private fun expandCandidatePanel() {
        isPanelExpanded = true
        candidatePanel.visibility = View.VISIBLE
        btnCandidateExpand.setImageResource(R.drawable.triangle_gray_up)
        updateCandidateBar()
    }

    private fun collapseCandidatePanel() {
        isPanelExpanded = false
        candidatePanel.visibility = View.GONE
        btnCandidateExpand.setImageResource(R.drawable.triangle_gray_down)
    }

    /** 通过全局索引选择候选词（自动翻页选中） */
    private fun selectCandidateByGlobalIndex(globalIndex: Int) {
        if (globalIndex < 0) return

        try {
        // 联想模式：点击的是联想候选词
        if (isAssociationMode && globalIndex < associationCandidates.size) {
            val selectedDisplay = associationCandidates[globalIndex]
            val newPrefix = associationPrefix + selectedDisplay
            val newAssociations = rimeEngine.getAssociations(newPrefix).take(20)

            // 上屏选中的词（追加到已有前缀后面）
            if (smartEditMode) {
                smartEditBuffer.append(selectedDisplay)
                updateSmartEditStatus()
            } else if (magicEditMode) {
                magicEditBuffer.append(selectedDisplay)
                updateMagicEditStatus()
            } else if (clipboardAddMode) {
                clipboardAddBuffer.append(selectedDisplay)
                updateClipboardAddStatus()
            } else {
                commitCandidateText(selectedDisplay)
            }
            lastT9Feed = null  // 联想选词上屏后重置增量喂标记，防止下个新拼音首键被吞

            if (newAssociations.isNotEmpty()) {
                // 继续联想模式
                associationPrefix = newPrefix
                associationCandidates = newAssociations
                showAssociationCandidates()
            } else {
                // 没有更多联想词，退出联想模式
                isAssociationMode = false
                associationPrefix = ""
                associationCandidates = emptyList()
                if (isPanelExpanded) collapseCandidatePanel()
                updateCandidateBar()
            }
            return
        }

        // 正常模式：点击的是 Rime 候选词
        // 关键修复：显示列表经 reorder(置顶/降频) 和 T9选音过滤重排，与 Rime 原始顺序不同。
        // 不再用「显示位置→Rime原始索引」的页码数学（过滤/重排下会对不齐），而是：
        //   1) 用显示位置反查用户实际点的是哪个词(lastDisplayedCands)
        //   2) 从 Rime 第 0 页起逐页在真实候选里找这个词，找到后用其当页 index 选中
        // 这样无论降频还是选音过滤，点到的词 = 上屏的词（点频上频、点管字上管字）。
        if (globalIndex >= lastDisplayedCands.size) return
        val clickedWord = lastDisplayedCands[globalIndex]
        // 用户自建词组：独立点击路径，直接整词上屏，不走 Rime 翻页（避免混入候选后位置错位/选不中）
        if (userPhrases.containsKey(clickedWord)) {
            if (keyboardMode == KeyboardMode.NUMBER && !t9FenCiOn && t9ConsumedLen > 0) {
                // 接龙中：已组词 + 用户词拼接上屏
                t9ComposedSoFar.append(clickedWord)
                commitCandidateText(t9ComposedSoFar.toString())
            } else {
                commitCandidateText(clickedWord)
            }
            t9ComposedSoFar.clear(); t9ConsumedLen = 0
            rimeEngine.clear(); t9DigitQueue.clear(); t9SpellPrefix.clear()
            lastT9Feed = null
            updateCandidateBar(); updateSpellBar(); updateStatus(statusIdleText)
            return
        }
        // 简拼模式（仅 T9）：候选来自合并列表(分词符串+字母组合)，不在主 Rime 会话中。
        // 入口判断用 lastDisplayedCands(用户实际点击的显示列表)而非 t9FenCiMerged，
        // 避免用户词组注入/简繁转换后显示列表与原始列表不一致导致 contains 失败、接龙被绕过。
        if (keyboardMode == KeyboardMode.NUMBER && t9FenCiOn && lastDisplayedCands.contains(clickedWord)) {
            val toCommit = stripDuplicatePrefix(clickedWord)
            t9ComposedSoFar.append(clickedWord)
            // 接龙选词后清空选音前缀（接龙只靠 t9ConsumedLen 消费剩余数字，不能带旧 t9SpellPrefix 走 buildT9SpellFeed 锁定字母分支）
            t9SpellPrefix.clear()
            // 简拼接龙：消费长度 = 词字数（简拼每位数字对应一个音节首字母），与全拼接龙对称
            if (t9ConsumedLen > 0 || t9DigitQueue.length > clickedWord.length) {
                t9ConsumedLen += clickedWord.length
                val remaining = t9DigitQueue.length - t9ConsumedLen
                if (remaining <= 0) {
                    commitCandidateText(t9ComposedSoFar.toString())
                    addUserPhrase(t9ComposedSoFar.toString(), t9DigitQueue.toString())
                    t9ComposedSoFar.clear(); t9ConsumedLen = 0
                    rimeEngine.clear(); t9DigitQueue.clear(); t9SpellPrefix.clear(); t9FenCiMerged = emptyList()
                    lastT9Feed = null
                    updateCandidateBar(); updateSpellBar(); updateStatus(statusIdleText)
                    return
                }
                feedRemaining()
                return
            }
            // 非接龙：立即上屏（保留原行为）
            when {
                smartEditMode -> { smartEditBuffer.append(toCommit); updateSmartEditStatus() }
                magicEditMode -> { magicEditBuffer.append(toCommit); updateMagicEditStatus() }
                clipboardAddMode -> { clipboardAddBuffer.append(toCommit); updateClipboardAddStatus() }
                else -> commitCandidateText(toCommit)
            }
            rimeEngine.clear()
            // 造词放宽：选词后查联想(词+词/词+字)，有联想进联想模式继续组词；无则结束
            val newAssoc = rimeEngine.getAssociations(clickedWord).take(20)
            if (newAssoc.isNotEmpty() && !smartEditMode && !magicEditMode && !clipboardAddMode) {
                // 清 T9 残留（数字队列/候选音区），避免点击上屏后状态栏和候选音不消失
                t9DigitQueue.clear(); t9SpellPrefix.clear(); t9FenCiMerged = emptyList()
                updateSpellBar()
                isAssociationMode = true
                associationPrefix = clickedWord
                associationCandidates = newAssoc
                if (isPanelExpanded) collapseCandidatePanel()
                showAssociationCandidates()
            } else {
                resetT9State()
            }
            updateCandidateBar()
            return
        }
        // 在「未过滤的 Rime 真实全局序(lastAllCands)」里定位该词，得到真实全局索引；
        // 再按 pageSize 算出页码/页内索引翻页选中。
        // 注意：不能用 pageCount 逐页查找（选音后 pageCount 不可靠，如 746+p 报 pageCount=2 但实有 63+ 候选）。
        val realGlobalIndex = lastAllCands.indexOf(clickedWord).let { idx ->
            // 正体模式下 clickedWord 是繁体，lastAllCands 是简体，先用繁体查，查不到再转简体回查
            if (idx >= 0) idx else lastAllCands.indexOf(toSimplified(clickedWord))
        }
        if (realGlobalIndex < 0) return
        // 选词前记录候选拼音（与 lastAllCands 全局序一一对应），用于接龙消费长度反推
        val prePinyins = rimeEngine.getAllCandidatePinyins()
        val pageSize = maxOf(1, rimeEngine.candidates.size)
        val targetPage = realGlobalIndex / pageSize
        val idxInPage = realGlobalIndex % pageSize
        // 翻到目标页（先从第0页开始，保证起点一致）
        while (rimeEngine.currentPage > 0) rimeEngine.prevPage()
        var curPage = 0
        while (curPage < targetPage) { rimeEngine.nextPage(); curPage++ }
        val selectedWord = rimeEngine.selectCandidate(idxInPage)
        if (selectedWord.isNotEmpty()) {
            lastT9Feed = null  // 选词上屏后重置增量喂标记，防止下次新拼音首键被误判为退格而吞字
            // 去重：逐字组词时最后一步会返回整串(六牛柳)，而前面(六/牛)已上屏，
            // 此处把前面已上屏的前缀去掉，只上屏新增的尾巴(柳)，避免重复。
            val toCommit = stripDuplicatePrefix(selectedWord)
            t9ComposedSoFar.append(selectedWord)
            if (smartEditMode) {
                // 智能写作编辑模式：写入 buffer 而不是上屏
                smartEditBuffer.append(toCommit)
                rimeEngine.clear()
                updateSmartEditStatus()
            } else if (magicEditMode) {
                // 魔法编辑模式：写入 buffer 而不是上屏
                magicEditBuffer.append(toCommit)
                rimeEngine.clear()
                updateMagicEditStatus()
            } else if (clipboardAddMode) {
                // 剪贴板新增模式：写入 buffer 而不是上屏
                clipboardAddBuffer.append(toCommit)
                rimeEngine.clear()
                updateClipboardAddStatus()
            } else {
                // 上屏分支：全拼接龙组词不立即上屏，其余(单字/简拼/其他)正常立即上屏
                if (keyboardMode == KeyboardMode.NUMBER && !t9FenCiOn) {
                    // 全拼接龙：用候选拼音反推消费的数字长度（区分全拼32542与缩写325），剩余继续组词
                    // 注意 t9ComposedSoFar 已在上方 2129 append(selectedWord)，此处不再重复 append
                    val py = prePinyins.getOrElse(realGlobalIndex) { "" }
                    val consumed = pinyinToDigitLen(py)
                    t9ConsumedLen += consumed
                    val remaining = t9DigitQueue.length - t9ConsumedLen
                    if (remaining <= 0) {
                        // 字符耗尽 → 自动上屏整串组词结果（仅此一次上屏）
                        commitCandidateText(t9ComposedSoFar.toString())
                        addUserPhrase(t9ComposedSoFar.toString(), t9DigitQueue.toString())  // 接龙组词写入用户词库
                        t9ComposedSoFar.clear(); t9ConsumedLen = 0
                        rimeEngine.clear(); t9DigitQueue.clear(); t9SpellPrefix.clear()
                        lastT9Feed = null
                        updateCandidateBar(); updateSpellBar(); updateStatus(statusIdleText)
                        return
                    }
                    // 还有剩余字符 → 接龙：不清 digitQueue，用剩余重匹配刷新候选栏
                    feedRemaining()
                    return
                }
                // 单字/简拼/其他：立即上屏（保留原行为，避免重复上屏）
                commitCandidateText(toCommit)
            }
            if (keyboardMode == KeyboardMode.NUMBER) {
                // T9 选词后：数字已转成字，清显示缓存
                t9InputBuffer.clear()
                // 选词组(>=2字)或 composing 已结束 → 强制清 Rime composing，确保选词完整并触发词组联想；
                // 仅选单字(length==1)且 composing 仍在继续时保留(逐字组词场景，如 624'624 选‘麦’后继续选下一音节)。
                // 注: T9 组词累积靠 t9ComposedSoFar(StringBuilder)，不依赖 Rime composing 跨次保留。
                if (selectedWord.length >= 2 || !rimeEngine.isComposing) {
                    rimeEngine.clear()
                    t9ComposedSoFar.clear()  // 组合结束，清空累积
                    t9DigitQueue.clear(); t9SpellPrefix.clear()
                }
            }
            // 查询联想词（限制最高频的 20 个，防止过多导致闪退）
            val associations = rimeEngine.getAssociations(selectedWord).take(20)
            if (associations.isNotEmpty()) {
                // 清 T9 残留（候选音区），避免全拼上屏后候选音不消失
                t9SpellPrefix.clear(); t9FenCiMerged = emptyList()
                if (keyboardMode == KeyboardMode.NUMBER) { t9DigitQueue.clear() }
                updateSpellBar()
                // 有联想词，进入联想模式
                isAssociationMode = true
                associationPrefix = selectedWord
                associationCandidates = associations
                if (isPanelExpanded) collapseCandidatePanel()
                showAssociationCandidates()
            } else {
                // 没有联想词
                isAssociationMode = false
                associationPrefix = ""
                associationCandidates = emptyList()
                // 清 T9 残留（候选音区），避免全拼单字上屏后候选音不消失
                t9SpellPrefix.clear(); t9FenCiMerged = emptyList()
                if (keyboardMode == KeyboardMode.NUMBER) { t9DigitQueue.clear() }
                updateSpellBar()
                // 不进联想：保持展开面板（逐字组词顺点，避免收起再展开旧index命中新内容重复上屏）
                updateCandidateBar()
                gvCandidates?.setSelection(0)
            }
        }
        } catch (e: Exception) {
            Log.e("Cesia", "selectCandidateByGlobalIndex crash: ${e.message}")
            // 安全恢复：退出联想模式
            isAssociationMode = false
            associationPrefix = ""
            associationCandidates = emptyList()
        }
    }

    /** 显示联想候选词 */
    private fun showAssociationCandidates() {
        candidateBar.visibility = View.VISIBLE
        updateStatus("💡$associationPrefix")
        val displayCands = if (isTraditional) associationCandidates.map { toTraditional(it) } else associationCandidates
        candidateAdapter?.updateData(displayCands)
        rvCandidates?.scrollToPosition(0)
        btnCandidateExpand.visibility = if (associationCandidates.size > 4) View.VISIBLE else View.GONE
    }

    /** 退出联想模式（用户输入新拼音时调用） */
    /** 退出联想模式：清除联想状态并同步更新候选栏 */
    private fun exitAssociationMode() {
        if (isAssociationMode) {
            isAssociationMode = false
            associationPrefix = ""
            associationCandidates = emptyList()
            // 立即清空候选栏适配器，防止显示旧联想词
            candidateAdapter?.updateData(emptyList())
            rvCandidates?.scrollToPosition(0)
            setCandidateBarVisible(false)
        }
    }

    /** 候选栏显隐：隐藏用 INVISIBLE（保留 36dp 占位，避免 GONE 导致键盘重排版闪烁）。
     *  语音输入保持模式(candidateBarKeep)下不隐藏，直到退格键清除该标志。 */
    private fun setCandidateBarVisible(show: Boolean) {
        if (!show && candidateBarKeep) return
        candidateBar.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    /**
     * 清除候选栏内容（词列表 + 状态文字）但整条栏保持可见（不收起），用于：
     * 词上屏(空格/标点/回车)、单击无联想、联想耗尽、退格删词等场景。
     * 同时结束语音保持模式(candidateBarKeep)，使“退格才清”的语音保持在此类提交后失效。
     */
    private fun clearCandidateContent() {
        candidateBarKeep = false
        candidateAdapter?.updateData(emptyList())
        rvCandidates?.scrollToPosition(0)
        if (isPanelExpanded) collapseCandidatePanel()
        candidateBar.visibility = View.VISIBLE
        updateStatus(statusIdleText)
    }

    private fun updateCandidateBar() {
        // 语音识别期间不更新候选栏（避免覆盖流式识别状态）
        if (isRecording) return
        // 候选懒加载：输入状态(数字队列/选音前缀/简拼开关/接龙消费)变化时，重置分页，首屏重新拉 50 候选
        val inputSig = "$t9DigitQueue|$t9SpellPrefix|$t9FenCiOn|$t9ConsumedLen"
        if (inputSig != lastPagerInputSig) {
            candPageWalk = 10
            candTotalLoaded = 0
            lastPagerInputSig = inputSig
        }
        val composing = rimeEngine.isComposing
        val pinyin = rimeEngine.composingText
        // 简拼模式：仅 T9 数字键盘下用合并候选（分词符串 + 字母组合交叉）；单键单字用枚举候选(跟随选音)；全键盘始终走自身 pinyin 候选
        val rimeAllCands = when {
            keyboardMode == KeyboardMode.NUMBER && t9FenCiOn && t9FenCiMerged.isNotEmpty() -> t9FenCiMerged
            keyboardMode == KeyboardMode.NUMBER && t9DigitQueue.length == 1 && t9SingleKeyCands.isNotEmpty() -> t9SingleKeyCands
            else -> rimeEngine.getAllCandidates(candPageWalk)
        }
        var allCands = rimeAllCands
        // 注入用户自建词组：当前待匹配数字串是某用户词数字串的前缀时，插到候选最前（接龙组词记忆）
        // 单键(length==1)只出单字，不注入用户词组，避免高频词组挤掉单字
        if (keyboardMode == KeyboardMode.NUMBER && !t9FenCiOn && t9DigitQueue.length != 1 && userPhrases.isNotEmpty()) {
            val curDigits = if (t9DigitQueue.length > t9ConsumedLen) t9DigitQueue.substring(t9ConsumedLen) else ""
            if (curDigits.isNotEmpty()) {
                val injected = userPhrases.filter { (_, dig) -> dig.startsWith(curDigits) }.map { it.key }
                if (injected.isNotEmpty()) {
                    allCands = (injected + allCands).distinct()
                }
            }
        }
        // 快照未过滤的原始 Rime 候选列表（供点击反查真实全局索引）；用户词组不纳入，避免位置错位
        lastAllCands = rimeAllCands
        // 懒加载：记录首屏已拉取的 Rime 候选数（纯 Rime，不含用户词组注入），供滚动到底 drop 取新增
        candTotalLoaded = rimeAllCands.size

        // 逐键选音：已选字母前缀非空时，按候选拼音首字母过滤（全拼模式用；简拼模式已由 buildT9SpellFeed 精确出候选，跳过）
        if (t9SpellPrefix.isNotEmpty() && !t9FenCiOn) {
            val pinyins = rimeEngine.getAllCandidatePinyins(candPageWalk)
            allCands = filterCandsBySpellPrefix(allCands, pinyins, t9SpellPrefix.toString())
        }

        // 去重：输入状态( composing/拼音/选音前缀/繁体/面板/联想/候选集 )未变则跳过整轮重建，
        // 避免每次按键无谓的 adapter 重排与 notifyDataSetChanged（影响跟手速度）。
        val sig = ((composing.hashCode() * 31 + pinyin.hashCode()) * 31 + allCands.hashCode()) xor
            ((t9SpellPrefix.hashCode() * 31 + isTraditional.hashCode() * 31
                + isPanelExpanded.hashCode() * 31 + isAssociationMode.hashCode() * 31
                + associationCandidates.hashCode()))
        if (sig == lastCandSig) return
        lastCandSig = sig

        // 没有输入时退出联想模式并恢复初始状态
        // 但联想模式下有联想词时不退出（联想词已上屏，Rime composing 已结束）
        // 智能写作/魔法编辑模式下不恢复初始状态（避免"已就绪"覆盖编辑中的命令）
        if (!composing && pinyin.isEmpty() && !isAssociationMode && !smartEditMode && !magicEditMode) {
            if (isAssociationMode) {
                isAssociationMode = false
                associationPrefix = ""
                associationCandidates = emptyList()
            }
            // 没有输入时：语音保持模式(candidateBarKeep)下整栏保持可见；否则清除内容但保持可见（不收起）
            if (!candidateBarKeep) {
                clearCandidateContent()
            }
            return
        }

        // 有输入时
        setCandidateBarVisible(true)

        // T9 模式：状态栏显示「已选字母 + 剩余未选数字」（逐键选音进度）；选满后只显示字母
        // 接龙态(t9ConsumedLen>0)下跳过已消费前缀，只显示未消费后缀的选音进度
        if (keyboardMode == KeyboardMode.NUMBER && (t9DigitQueue.isNotEmpty() || t9SpellPrefix.isNotEmpty())) {
            val skip = t9ConsumedLen.coerceAtMost(t9DigitQueue.length)
            val from = (skip + t9SpellPrefix.length).coerceAtMost(t9DigitQueue.length)
            val remaining = if (from < t9DigitQueue.length) t9DigitQueue.substring(from) else ""
            updateStatus(t9SpellPrefix.toString() + remaining)
        } else {
            updateStatus(pinyin)
        }

        // 联想模式：显示联想候选词
        if (isAssociationMode && associationCandidates.isNotEmpty()) {
            val displayCands = if (isTraditional) associationCandidates.map { toTraditional(it) } else associationCandidates
            candidateAdapter?.updateData(displayCands)
            rvCandidates?.scrollToPosition(0)
            btnCandidateExpand.visibility = if (associationCandidates.size > 4) View.VISIBLE else View.GONE
            if (isPanelExpanded) {
                tvPanelComposing.text = "💡$associationPrefix"
                val displayPanel = displayCands
                panelAdapter?.clear()
                panelAdapter?.addAll(displayPanel)
                panelAdapter?.notifyDataSetChanged()
            }
            return
        }

        // 简繁转换：繁体模式下候选词显示繁体
        val displayCands = if (isTraditional) allCands.map { toTraditional(it) } else allCands

        // 应用候选偏好（置顶/降频），全局持久化
        val reordered = CandidatePrefs.reorder(this, displayCands)
        // 快照：供点击时反查用户点的是哪个词（显示顺序）
        lastDisplayedCands = reordered

        // 更新候选词列表
        candidateAdapter?.updateData(reordered)
        rvCandidates?.scrollToPosition(0)
        btnCandidateExpand.visibility = if (reordered.size > 4) View.VISIBLE else View.GONE

        // 更新展开面板
        if (isPanelExpanded) {
            tvPanelComposing.text = pinyin
            // 展开面板直接复用已获取的 allCands（候选栏已按选音前缀过滤过），避免再调一次 getAllCandidates
            val filteredPanel = allCands
            val displayPanel = if (isTraditional) filteredPanel.map { toTraditional(it) } else filteredPanel
            val reorderedPanel = CandidatePrefs.reorder(this, displayPanel)
            panelAdapter?.clear()
            panelAdapter?.addAll(reorderedPanel)
            panelAdapter?.notifyDataSetChanged()
        }
    }

    /** 候选懒加载：候选栏横向滚到右端阈值时，从 Rime 会话拉取下一批候选（每批约20字词）追加到列表与点击反查源。
     *  不设置数量上限，纯靠滚动懒加载（每批 20 字词），滚到底继续加载更多。 */
    private fun loadMoreCandidates() {
        if (keyboardMode != KeyboardMode.NUMBER) return
        if (candTotalLoaded <= 0) return
        // 从 Rime 会话增量翻页（每批 +4页=20候选）
        candPageWalk += 4
        val fresh = rimeEngine.getAllCandidates(candPageWalk)
        val more = fresh.drop(candTotalLoaded)
        if (more.isEmpty()) { candPageWalk -= 4; return }
        candTotalLoaded = fresh.size
        lastAllCands = (lastAllCands + more).distinct()
        val merged = (lastDisplayedCands + more).distinct()
        lastDisplayedCands = merged
        candidateAdapter?.updateData(merged)
        if (isPanelExpanded) {
            val displayPanel = if (isTraditional) merged.map { toTraditional(it) } else merged
            val reorderedPanel = CandidatePrefs.reorder(this, displayPanel)
            panelAdapter?.clear()
            panelAdapter?.addAll(reorderedPanel)
            panelAdapter?.notifyDataSetChanged()
        }
    }

// endregion 候选栏

    // ======================== 候选词长按菜单（置顶/降频） ========================
    /**
     * 长按候选词弹出菜单：置顶 / 降频 / 恢复默认。
     * 用 PopupWindow（IME 环境不能用 AlertDialog）。
     */
    private fun showCandidateLongPressMenu(word: String, anchorView: android.view.View?, longPressIndex: Int) {
        if (word.isEmpty()) return
        val ctx = this
        // 保存被长按项 index，用于定位菜单
        selectedCandidateIndex = longPressIndex
        val pinned = CandidatePrefs.isPinned(ctx, word)
        val down = CandidatePrefs.isDowngraded(ctx, word)

        val items = mutableListOf<String>()
        if (pinned) items.add("取消置顶") else items.add("置顶")
        if (down) items.add("恢复候选") else items.add("降频")
        // 用户自建词组：额外提供「删除词组」从词库移除
        if (userPhrases.containsKey(word)) items.add("删除词组")
        items.add("关闭")

        val menuView = layoutInflater.inflate(R.layout.popup_candidate_menu, null)
        val tvTitle = menuView.findViewById<TextView>(R.id.tv_menu_title)
        val btnClose = menuView.findViewById<ImageButton>(R.id.btn_menu_close)
        val llItems = menuView.findViewById<LinearLayout>(R.id.ll_menu_items)
        tvTitle.text = "候选：$word"
        // 菜单文字随主题文字大小档位缩放
        val menuSp = when (textThemeSize) {
            0 -> 12f
            2 -> 16f
            3 -> 18f
            else -> 14f
        }
        tvTitle.textSize = menuSp

        val popup = PopupWindow(menuView,
            (200 * resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(
            ContextCompat.getDrawable(ctx, android.R.drawable.dialog_holo_light_frame)
                ?: GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    setStroke(1, 0xFFCCCCCC.toInt())
                }
        )
        popup.elevation = 8f

        fun doAction(action: String) {
            when (action) {
                "置顶" -> CandidatePrefs.pin(ctx, word)
                "取消置顶" -> CandidatePrefs.reset(ctx, word)
                "降频" -> CandidatePrefs.downgrade(ctx, word)
                "恢复候选" -> CandidatePrefs.reset(ctx, word)
                "删除词组" -> {
                    userPhrases.remove(word)
                    saveUserPhrases()
                    updateStatus("已删除词组：$word")
                }
            }
            popup.dismiss()
            updateCandidateBar()
        }

        for (item in items) {
            val row = TextView(ctx).apply {
                text = item
                textSize = menuSp
                setTextColor(0xFF333333.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * resources.displayMetrics.density).toInt()
                )
                isClickable = true
                isFocusable = true
                val typedValue = TypedValue()
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)) {
                    background = ContextCompat.getDrawable(ctx, typedValue.resourceId)
                }
                setOnClickListener { doAction(item) }
            }
            llItems.addView(row)
        }
        btnClose.setOnClickListener { popup.dismiss() }

        // 定位到候选栏底部（紧邻，不跳动）
        val rv = rvCandidates ?: return
        popup.showAtLocation(rv, android.view.Gravity.NO_GRAVITY, 0, 0)
        rv.post {
            val loc = IntArray(2)
            rv.getLocationOnScreen(loc)
            // 菜单显示在候选栏底部 +2px
            popup.update(loc[0], loc[1] + rv.height + 2, -1, -1)
        }
    }

// region 录音控制
    // ======================== 识别后端可用性检测 ========================

    /**
     * 检测单个后端的真实可用性
     * 返回 Triple(是否可用, 错误信息, 详细信息)
     */

    // ======================== 录音（根据当前模式） ========================

    private fun setupButtonListeners() {
        // 语音按钮：参考魔法书模式，OnTouchListener 统一处理单击和长按
        micButton.setOnClickListener {
            // 纯 OnTouchListener 处理点击/长按，这里仅作兜底（正常不会走到 performClick 路径）
        }
        micButton.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    micLongPressTriggered = false
                    dismissAllPopups() // 长按互斥：关闭其他弹窗
                    startMicLongPressDetection()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    cancelMicLongPressDetection()
                    if (!micLongPressTriggered) {
                        // 双击检测（350ms 窗口）：未录音时双击切换中英混/纯中文模型
                        val now = System.currentTimeMillis()
                        if (now - lastMicTapTime <= 350) {
                            micDoubleTapPending = false
                            lastMicTapTime = 0L
                            handleMicDoubleTap()
                        } else {
                            lastMicTapTime = now
                            micDoubleTapPending = true
                            micHandler.postDelayed({
                                if (micDoubleTapPending) {
                                    micDoubleTapPending = false
                                    // 超时未第二次点击 → 按单次点击处理
                                    micOnClickListener()
                                }
                            }, 350)
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelMicLongPressDetection()
                    true
                }
                else -> false
            }
        }

        btnMicAi.setOnClickListener { onAiPlusSelected() }
        btnMicNoAi.setOnClickListener { onAiCrossSelected() }
        btnSettings.setOnClickListener { showSettings() }
        btnTraditional.setOnClickListener { toggleTraditionalSimplified() }
        btnCloud.setOnClickListener { onCloudButtonClick() }
        btnCloud.setOnLongClickListener { onCloudButtonLongClick(); true }
        btnTheme.setOnClickListener { showThemePopup() }

        deleteLongPressTriggered = false

        btnDelete.setOnClickListener {
            maybeShowButtonHint("clear", "清空")
            // 清空键：清除候选栏内容（保持可见），并结束语音保持模式
            clearCandidateContent()
            if (rimeEngine.isComposing) {
                rimeEngine.processKey("BackSpace")
                updateCandidateBar()
            } else {
                try {
                    // Android IME 框架对单次 deleteSurroundingText 有限制，循环删除直到清空
                    val ic = currentInputConnection ?: return@setOnClickListener
                    // 先删除选中文字（如果有选区）
                    val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                    val selStart = extracted?.selectionStart ?: -1
                    val selEnd = extracted?.selectionEnd ?: -1
                    if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
                        ic.commitText("", 1)
                    } else {
                        // 删除光标前全部文字
                        while (true) {
                            val before = ic.getTextBeforeCursor(1000, 0)
                            if (before.isNullOrEmpty()) break
                            val len = before.length
                            ic.deleteSurroundingText(len, 0)
                            if (len < 1000) break // 已删完
                        }
                    }
                } catch (_: Exception) { /* 安全忽略 */ }
            }
        }
        // 清空键：长按高亮动态效果（无锁定，手指移开解除）
        btnDelete.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    deleteLongPressTriggered = false
                    dismissAllPopups() // 长按互斥：关闭其他弹窗
                    // 立即高亮清空按钮
                    btnDelete.background = makeKeyBgDrawable(themeAccent)
                    btnDelete.elevation = 6f
                    startDeleteButtonGlow()
                    deleteButtonGlowRunnable = Runnable {
                        deleteLongPressTriggered = true
                        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        try {
                            if (rimeEngine.isComposing) {
                                rimeEngine.processKey("BackSpace")
                                updateCandidateBar()
                            } else {
                                val ic = currentInputConnection ?: return@Runnable
                                // 先删除选中文字（如果有选区）
                                val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                                val selStart = extracted?.selectionStart ?: -1
                                val selEnd = extracted?.selectionEnd ?: -1
                                if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
                                    ic.commitText("", 1)
                                    maybeShowButtonHint("clear_long", "清空选中的文字")
                                } else {
                                    // 长按：删除光标后全部文字，循环删除避免字数限制
                                    maybeShowButtonHint("clear_long", "清空光标后的文字")
                                    while (true) {
                                        val after = ic.getTextAfterCursor(1000, 0)
                                        if (after.isNullOrEmpty()) break
                                        val len = after.length
                                        ic.deleteSurroundingText(0, len)
                                        if (len < 1000) break // 已删完
                                    }
                                }
                            }
                        } catch (_: Exception) { /* 安全忽略 */ }
                    }.also {
                        deleteGlowHandler.postDelayed(it, 800)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    deleteButtonGlowRunnable?.let { deleteGlowHandler.removeCallbacks(it) }
                    deleteButtonGlowRunnable = null
                    stopDeleteButtonGlow()
                    if (!deleteLongPressTriggered) {
                        v.performClick()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    deleteButtonGlowRunnable?.let { deleteGlowHandler.removeCallbacks(it) }
                    deleteButtonGlowRunnable = null
                    stopDeleteButtonGlow()
                    true
                }
                else -> false
            }
        }

        btnClipboard.setOnClickListener {
            maybeShowButtonHint("magic", "智能修改")
            executeMagicOrAiReply()
        }
        btnClipboard.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    magicBookLongPressTriggered = false
                    dismissAllPopups() // 长按互斥：关闭其他弹窗
                    startMagicBookLongPress()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    magicBookRunnable?.let { magicBookHandler.removeCallbacks(it) }
                    magicBookRunnable = null
                    if (!magicBookLongPressTriggered) {
                        // 单击：停止高光
                        stopMagicBookGlow()
                        v.performClick()
                    }
                    // 长按已触发：保持高光（持续到popup关闭）
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelMagicBookLongPress()
                    true
                }
                else -> false
            }
        }

        // 智能写作按钮（星星/五角星）：短按执行第一项命令，长按弹出设置弹窗
        // 复用魔法书按钮的触摸处理模式
        btnMagic.setOnClickListener {
            maybeShowButtonHint("smart_write", "智能写作")
            toggleMagicMode()
        }
        btnMagic.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    magicBookLongPressTriggered = false
                    dismissAllPopups() // 长按互斥：关闭其他弹窗
                    // 开始发光（与魔法书按钮一致：青色背景+白色图标）
                    btnMagic.background = makeKeyBgDrawable(themeAccent)
                    btnMagic.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_ATOP)
                    startMagicButtonGlow()
                    // 延迟触发长按弹窗
                    magicBookRunnable?.let { magicBookHandler.removeCallbacks(it) }
                    magicBookRunnable = Runnable {
                        magicBookLongPressTriggered = true
                        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        maybeShowButtonHint("smart_write_long", "智能写作 菜单")
                        showSmartWritingPopup()
                    }.also {
                        magicBookHandler.postDelayed(it, 600)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    magicBookRunnable?.let { magicBookHandler.removeCallbacks(it) }
                    magicBookRunnable = null
                    if (!magicBookLongPressTriggered) {
                        // 短按：停止发光，执行第一项命令
                        stopMagicButtonGlow()
                        btnMagic.background = makeKeyBgDrawable(currentKeyBg)
                        btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
                        v.performClick()
                    }
                    // 长按已触发：保持高光（持续到popup关闭）
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    magicBookRunnable?.let { magicBookHandler.removeCallbacks(it) }
                    magicBookRunnable = null
                    stopMagicButtonGlow()
                    btnMagic.background = makeKeyBgDrawable(currentKeyBg)
                    btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
                    true
                }
                else -> false
            }
        }

        // 发送按钮
        btnSend.setOnClickListener {
            maybeShowButtonHint("send", "发送")
            val ic = currentInputConnection ?: return@setOnClickListener
            if (!isAsciiMode && rimeEngine.isComposing) {
                val text = if (rimeEngine.hasCandidates) {
                    rimeEngine.selectCandidate(0).ifEmpty { rimeEngine.composingText }
                } else { rimeEngine.composingText }
                if (text.isNotEmpty()) { commitCandidateText(text) }
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
            updateStatus("已发送（长按显示剪贴板）")
        }
        // 发送键长按：剪贴板管理器
        btnSend.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sendKeyLongPressTriggered = false
                    dismissAllPopups() // 长按互斥：关闭其他弹窗
                    startSendKeyLongPress()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    sendKeyRunnable?.let { sendKeyHandler.removeCallbacks(it) }
                    sendKeyRunnable = null
                    if (!sendKeyLongPressTriggered) {
                        // 单击：停止高光
                        stopSendButtonGlow()
                        v.performClick()
                    }
                    // 长按已触发：保持高光（持续到popup关闭）
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

// endregion 录音控制

// region 语音键处理
    // ======================== 语音键单击/长按处理 ========================

    /**
     * 语音键单击处理
     * - 锁定模式：退出锁定
     * - 非录音状态：开始录音
     * - 录音状态：停止录音
     */
    private fun micOnClickListener() {
        if (isVoiceLocked) {
            // 锁定模式下单击 → 退出锁定
            isVoiceLocked = false
            updateMicButtonLockedState()
            maybeShowButtonHint("voice", "退出语音锁定模式")
            updateStatus("🔓 已退出语音锁定模式")
            resetToIdle()
            return
        }
        if (!isRecording && !isWaitingForChoice) {
            maybeShowButtonHint("voice", "正在收听...（双击语音键可切换纯中文/中英混模型）")
            try {
                val bridgeLoaded = SherpaOnnxEngine.isLibraryLoaded()
                val hasVoiceModel = modelManager.hasVoiceModel()
                Log.i("Cesia", "单击语音键: bridgeLoaded=$bridgeLoaded, hasVoiceModel=$hasVoiceModel, localMode=$localModeEnabled, simulTranslate=$simulTranslateEnabled")

                if (simulTranslateEnabled) {
                    if (!bridgeLoaded || !hasVoiceModel) {
                        updateStatus("⚠️ 同传模式需要语音识别模型，请先到设置中下载")
                        return
                    }
                    if (!modelManager.hasAiModel()) {
                        updateStatus("⚠️ 同传模式需要 Qwen 模型，请先到设置中下载")
                        return
                    }
                    startSimulTranslateRecording()
                } else if (localModeEnabled) {
                    if (!bridgeLoaded || !hasVoiceModel || !modelManager.hasAiModel()) {
                        updateStatus("⚠️ 本地模式需要语音识别 + Qwen 模型，请先到设置中下载")
                        return
                    }
                    startRecordingWithChoice(VoiceChoice.LOCAL_SHERPA, PolishChoice.LOCAL_AI)
                } else {
                    if (bridgeLoaded && hasVoiceModel) {
                        startRecordingWithChoice(VoiceChoice.LOCAL_SHERPA, PolishChoice.CLOUD_OPENROUTER)
                    } else {
                        Log.i("Cesia", "单击语音键: 使用 Google 语音识别")
                        startRecordingWithChoice(VoiceChoice.GOOGLE, PolishChoice.CLOUD_OPENROUTER)
                    }
                }
            } catch (e: Throwable) {
                Log.e("Cesia", "单击语音键异常", e)
                updateStatus("❌ 语音启动失败: ${e.javaClass.simpleName}")
            }
        } else if (isWaitingForChoice) {
            updateStatus("请点击 AI+ 或 AI× 选择处理方式")
        } else if (isRecording) {
            if (magicMode) {
                // 智能写作模式：停止录音并完整清理
                stopRecordingAndWait()
                resetMagicHighlight()
            } else {
                if (simulTranslateEnabled) {
                    stopSimulTranslateRecording()
                } else {
                    stopRecording()
                }
            }
        }
    }

    /** 开始语音键长按检测 */
    private fun startMicLongPressDetection() {
        cancelMicLongPressDetection()
        micLongPressRunnable = Runnable {
            micLongPressTriggered = true
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            toggleVoiceLockMode()
        }.also {
            micHandler.postDelayed(it, 800)
        }
    }

    /** 取消语音键长按检测 */
    private fun cancelMicLongPressDetection() {
        micLongPressRunnable?.let { micHandler.removeCallbacks(it) }
        micLongPressRunnable = null
    }

// endregion 语音键处理

    // 语音键双击：未录音时切换中英混 / 纯中文 识别模型（不影响中英混模型本身）
    private fun handleMicDoubleTap() {
        if (isRecording || isWaitingForChoice) {
            updateStatus("⚠️ 录音中，无法切换模型")
            return
        }
        if (!voiceEngine.hasChineseModel()) {
            updateStatus("⚠️ 纯中文模型未下载，请到设置下载「语音文字输入套件增强版」")
            return
        }
        val mode = voiceEngine.switchVoiceMode()
        updateMicZhLabel()
        // 切换后立即在后台预热新模型的识别器，使下次点击语音键无需在线重建（消除切换后首次识别的卡顿）
        voiceEngine.warmupRecognizer()
        if (mode == com.cesia.input.voice.VoiceEngine.VoiceMode.CHINESE) {
            updateStatus("🔤 已切换到纯中文识别模型（双击切回中英混）")
        } else {
            updateStatus("🔡 已切回中英混识别模型")
        }
    }

    // 语音键右上角“中”副字符：仅纯中文模式显示，字号随主题文字档，颜色固定白
    private fun updateMicZhLabel() {
        if (!::tvMicZh.isInitialized) return
        val isZh = voiceEngine.voiceMode == com.cesia.input.voice.VoiceEngine.VoiceMode.CHINESE
                && voiceEngine.hasChineseModel()
        tvMicZh.visibility = if (isZh) View.VISIBLE else View.GONE
        if (isZh) {
            tvMicZh.text = "中"
            tvMicZh.setTextColor(0xFFFFFFFF.toInt())
            tvMicZh.textSize = (10 + textThemeSize * 2).toFloat()
            tvMicZh.requestLayout()
        }
    }

// region 智能写作（星星按钮：短按语音写作，长按设置弹窗）
    // ======================== 智能写作（星星按钮） ========================

    private fun toggleMagicMode() {
        // 短按星星：直接执行第一条智能写作命令
        val smartRecords = mutableListOf<String>()
        loadSmartRecords(smartRecords)
        if (smartRecords.isNotEmpty()) {
            executeSmartCommand(smartRecords[0])
        } else {
            updateStatus("⚠️ 暂无写作命令，请长按星星添加")
        }
    }

    /**
     * 魔法模式 - 本地语音识别
     * 使用 Sherpa 本地模型，识别结果通过 handleMagicResult 处理
     */
    private fun startMagicLocalRecording() {
        magicStopRequested = false
        voiceEngineScope.launch {
            try {
                voiceEngine.warmupRecognizer()
                lastMagicRecognizedText = ""
                voiceEngine.recordInSegments(
                    onSegmentResult = { text, isFinal ->
                        if (text.isNotEmpty()) {
                            lastMagicRecognizedText = text
                            Handler(Looper.getMainLooper()).post {
                                updateStatus("🎤 $text")
                            }
                            if (isFinal) {
                                // 流式最终结果：直接触发 AI
                                Handler(Looper.getMainLooper()).post {
                                    handleMagicResult(text)
                                }
                            }
                        }
                    }
                )
                // recordInSegments 正常结束（超时）
                // 如果用户主动停止（magicStopRequested=true），则由 toggleMagicMode 触发 AI，这里不重复
                Handler(Looper.getMainLooper()).post {
                    if (!magicStopRequested) {
                        val text = lastMagicRecognizedText
                        if (text.isNotEmpty() && !isAiProcessing) {
                            handleMagicResult(text)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 协程被 cancel：不处理，由 toggleMagicMode 触发
                Log.d("Cesia", "魔法模式本地录音协程被取消")
            } catch (e: Exception) {
                Log.e("Cesia", "魔法模式本地识别失败", e)
                Handler(Looper.getMainLooper()).post {
                    updateStatus("❌ 本地识别失败: ${e.message}")
                    resetMagicHighlight()
                    magicMode = false
                    typelessEngine?.magicMode = false
                    isRecording = false
                }
            }
        }
    }

    /**
     * 魔法模式 - 云端语音识别
     * 使用 Google SpeechRecognizer，识别结果通过 onMagicResult 回调
     */
    private fun startMagicGoogleRecording() {
        try {
            typelessEngine?.startListening(continuous = true)
        } catch (e: Throwable) {
            Log.e("Cesia", "魔法模式 Google 识别失败", e)
            updateStatus("❌ Google 语音启动失败: ${e.javaClass.simpleName}")
            resetMagicHighlight()
            magicMode = false
            typelessEngine?.magicMode = false
            isRecording = false
        }
    }

    private fun resetMagicHighlight() {
        magicIsWaitingForVoice = false
        magicModeGlowing = false
        stopMagicButtonGlow()
        try {
            btnMagic.background = makeKeyBgDrawable(currentKeyBg)
            btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
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
        // 语音结果统一转阿拉伯数字（Google 魔法路径兜底，与本地 sherpa 路径一致）
        val recognizedText = voiceEngine.convertChineseDigitsToArabic(recognizedText)
        // 防重入：如果 AI 正在处理中，忽略重复触发
        if (isAiProcessing) {
            Log.d("Cesia", "handleMagicResult: AI 正在处理中，忽略重复触发")
            return
        }
        magicMode = false
        magicStopRequested = false
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

        // 读取剪贴板非置顶首条内容作为语境
        val clipboardContext = getClipboardFirstNonPinned()
        Log.d("Cesia", "handleMagicResult: instruction='$instruction', original='${magicOriginalText.take(50)}', clipboard='${clipboardContext.take(50)}'")

        // 异步执行 AI，避免阻塞主线程
        isAiProcessing = true
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val prompt = buildMagicPrompt(magicOriginalText, instruction, clipboardContext)
                Log.d("Cesia", "handleMagicResult: prompt长度=${prompt.length}")
                val polishService = typelessEngine?.getPolishService()
                Log.d("Cesia", "handleMagicResult: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(30) ?: "null"}")
                val result = polishService?.polishWithPrompt(prompt)
                Log.d("Cesia", "handleMagicResult: result=${result?.take(50) ?: "null"}, isNullOrEmpty=${result.isNullOrEmpty()}")
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    if (result != null && result.isNotEmpty()) {
                        magicHistoryManager?.addRecord(instruction)
                        saveUndoHistory(magicOriginalText, instruction)
                        try {
                            if (!isInputViewShown) {
                                updateStatus("⚠️ 键盘已收起，AI结果未上屏")
                                resetToIdle()
                            } else {
                            val ic2 = currentInputConnection
                            ic2?.performContextMenuAction(android.R.id.selectAll)
                            ic2?.commitText(result, 1)
                            resetToIdle()
                            }
                        } catch (e2: Exception) {
                            Log.e("Cesia", "handleMagicResult replaceInputText 异常", e2)
                            updateStatus("❌ 上屏失败: ${e2.message}")
                        }
                    } else {
                        updateStatus("⚠️ API返回为空，请检查网络或稍后重试")
                    }
                }
            } catch (e: Exception) {
                Log.e("Cesia", "智能写作失败", e)
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    updateStatus("❌ 修改失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 读取系统剪贴板第一条非空内容作为语境
     * 只读系统剪贴板，不读持久化历史
     * 如系统剪贴板为空或不可用，返回空字符串
     */
    private fun getClipboardFirstNonPinned(): String {
        return try {
            val clipboardMgr = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboardMgr?.hasPrimaryClip() == true) {
                val clip = clipboardMgr.primaryClip
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val text = clip.getItemAt(i).text?.toString()?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            Log.d("Cesia", "getClipboardFirstNonPinned: 读取到 ${text.length} 字符: ${text.take(50)}")
                            return text
                        }
                    }
                }
            }
            Log.d("Cesia", "getClipboardFirstNonPinned: 系统剪贴板为空")
            ""
        } catch (e: Exception) {
            Log.e("Cesia", "getClipboardFirstNonPinned: 读取剪贴板失败", e)
            ""
        }
    }

/**
 * 构建魔法模式 prompt
 * @param original 输入框原文
 * @param instruction 用户语音指令
 * @param clipboardContext 剪贴板语境（用户复制的参考内容）
 */
private fun buildMagicPrompt(original: String, instruction: String, clipboardContext: String): String {
    val originalSection = if (original.isNotEmpty()) {
        "\n【参考原文】\n$original\n"
    } else {
        ""
    }
    val contextSection = if (clipboardContext.isNotEmpty()) {
        "\n【参考内容】\n$clipboardContext\n"
    } else {
        ""
    }

    return "你是一位富有创意的文字助手。请根据以下信息，生成一段自然流畅的内容。\n" +
            originalSection +
            contextSection +
            "\n【用户的想法/指令】\n$instruction\n" +
            "\n请根据以上内容自由发挥，生成合适的回复或文字内容。直接输出内容本身，不要解释。"
}

// endregion 智能写作（星星按钮）

// region 魔法历史菜单
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
        // 使用统一润色入口（自动适配本地/云端）
        executePolish(fullText, instruction) { result, success ->
            isAiProcessing = false
            if (success && result.isNotEmpty() && result != fullText) {
                magicHistoryManager?.addRecord(instruction)
                saveUndoHistory(fullText, instruction)
                try {
                    if (!isInputViewShown) {
                        updateStatus("⚠️ 键盘已收起，AI结果未上屏")
                        resetToIdle()
                    } else {
                    val ic2 = currentInputConnection
                    ic2?.performContextMenuAction(android.R.id.selectAll)
                    ic2?.commitText(result, 1)
                    resetToIdle()
                    }
                } catch (e2: Exception) {
                    Log.e("Cesia", "replaceInputText 异常", e2)
                    updateStatus("❌ 上屏失败: ${e2.message}")
                }
            } else if (result == fullText) {
                updateStatus("⚠️ 修改结果与原文相同，可能指令不够明确")
            } else {
                updateStatus("⚠️ AI未返回有效结果，请重试")
            }
        }
    }

    private fun saveUndoHistory(originalText: String, instruction: String) {
        undoHistory.add(0, Pair(originalText, instruction))
        while (undoHistory.size > undoMaxSteps) {
            undoHistory.removeAt(undoHistory.size - 1)
        }
    }

    private fun showMagicHistoryPopup() {
        Log.d("Cesia", "showMagicHistoryPopup: called, mgr=$magicHistoryManager")
        val mgr = magicHistoryManager ?: run {
            Log.e("Cesia", "showMagicHistoryPopup: magicHistoryManager is null!")
            return
        }

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
        applyAccentToViewTree(popupView, themeAccent)
        val gridView = popupView.findViewById<GridView>(R.id.gv_magic_items)
        // 设置标题（使用个性化设置）
        val tvTitle = popupView.findViewById<android.widget.TextView>(R.id.tv_magic_title)
        tvTitle?.text = magicBookTitle

        val keyboardWidth = keyboardView.width
        val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

        // 测量标题栏实际高度
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val titleHeightPx = popupView.findViewById<android.widget.TextView>(R.id.tv_magic_title)?.measuredHeight
            ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt()

        val barHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics
        ).toInt()
        // 获取状态栏高度
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
            if (id > 0) resources.getDimensionPixelSize(id) else 88
        }
        // 高度 = 状态栏底部到键盘顶部的可用空间
        val keyboardLocation = IntArray(2)
        keyboardView.getLocationOnScreen(keyboardLocation)
        val keyboardTopScreenY = keyboardLocation[1]
        val totalHeight = (keyboardTopScreenY - statusBarHeight).coerceAtLeast(200)
        // Grid 高度 = 总高度 - 标题栏 - 按钮栏，填满剩余空间
        val gridHeightPx = (totalHeight - titleHeightPx - barHeightPx).coerceAtLeast(100)
        Log.d("Cesia", "MagicBookPopup: statusBar=$statusBarHeight keyboardTop=$keyboardTopScreenY total=$totalHeight grid=$gridHeightPx")

        val popup = PopupWindow(popupView, popupWidth, totalHeight, true)
        popup.isOutsideTouchable = false
        popup.elevation = 4f
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.setFocusable(false)

        // 动态设置 GridView 高度，填满标题栏和按钮栏之间的空间
        gridView.layoutParams = gridView.layoutParams.apply {
            height = gridHeightPx
        }

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
// endregion 魔法历史菜单

// region 候选适配器
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
                        tv.setTextColor(if (isActive) themeAccent else 0xFF333333.toInt())
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

        // 显示在键盘View正上方，顶部对齐状态栏底部
        // 弹窗顶部 = keyboardTopScreenY + yOffset = keyboardTopScreenY - totalHeight = statusBarHeight
        val anchorLocation = IntArray(2)
        keyboardView.getLocationOnScreen(anchorLocation)
        Log.d("Cesia", "MagicBookPopup: anchorScreenY=${anchorLocation[1]} statusBar=$statusBarHeight total=$totalHeight yOffset=${-totalHeight}")
        popup.showAtLocation(keyboardView, Gravity.TOP or Gravity.START, 0, -totalHeight)
        magicHistoryPopup = popup

        popup.setOnDismissListener {
            cancelMagicBookLongPress()
            magicHistoryPopup = null
        }
    }

    // ======================== 智能写作选项弹窗 ========================

    /** 智能写作选项数据类 */
    private data class SmartOption(val label: String, val tag: String, var isChecked: Boolean = false)

    // 智能写作设置弹窗中的选项标签常量
    private val OPT_CLIPBOARD = "📋 剪贴板首条"
    private val OPT_RSS_SOURCE = "📰 RSS源"
    private val OPT_SEARCH = "🌐 网络搜索"
    private val OPT_LOCAL_LIB = "📚 本地文库"

    /** 显示智能写作设置弹窗 */
    private fun showSmartWritingPopup() {
        Log.d("Cesia", "showSmartWritingPopup: 弹窗被调用")
        try {
            val inflater = android.view.LayoutInflater.from(this)
            val popupView = inflater.inflate(R.layout.popup_smart_writing, null)
            applyAccentToViewTree(popupView, themeAccent)

            val tvTitle = popupView.findViewById<android.widget.TextView>(R.id.tv_smart_title)
            tvTitle.text = smartWritingLabel

            // 选项视图（4个数据源：剪贴板、RSS源、网络搜索、本地文库）
            val optClipboard = popupView.findViewById<TextView>(R.id.opt_clipboard)
            val optRssSource = popupView.findViewById<TextView>(R.id.opt_rss_news)
            val optSearch = popupView.findViewById<TextView>(R.id.opt_search)
            val optLocalLib = popupView.findViewById<TextView>(R.id.opt_local_lib)

            // 恢复上次选中状态（持久化）
            val smartPrefs = getSharedPreferences("cesia_smart_writing", MODE_PRIVATE)
            var savedOptions = smartPrefs.getStringSet("selected_options", null) ?: emptySet()

            fun refreshOption(tv: TextView, tag: String, label: String) {
                val checked = savedOptions.contains(tag)
                tv.text = if (checked) "✓ $label" else "○ $label"
                tv.setTextColor(if (checked) themeAccent else 0xFF333333.toInt())
                tv.setTypeface(null, if (checked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                tv.tag = tag
            }
            refreshOption(optClipboard, "clipboard", OPT_CLIPBOARD)
            refreshOption(optRssSource, "rss_cache", OPT_RSS_SOURCE)
            refreshOption(optSearch, "search", OPT_SEARCH)
            refreshOption(optLocalLib, "local_lib", OPT_LOCAL_LIB)

            // 点击切换（直接保存到 SharedPreferences 并刷新 UI）
            fun toggleOption(tv: TextView, tag: String, label: String) {
                val current = (smartPrefs.getStringSet("selected_options", null) ?: emptySet()).toMutableSet()
                if (current.contains(tag)) current.remove(tag) else current.add(tag)
                smartPrefs.edit().putStringSet("selected_options", current).apply()
                // 更新 savedOptions 闭包变量，使 refreshOption 读取最新值
                savedOptions = smartPrefs.getStringSet("selected_options", null) ?: emptySet()
                refreshOption(tv, tag, label)
            }
            optClipboard.setOnClickListener { toggleOption(it as TextView, "clipboard", OPT_CLIPBOARD) }
            optRssSource.setOnClickListener {
                // RSS源：直接切换选中/取消选中（不跳转页面）
                val currentOpts = (smartPrefs.getStringSet("selected_options", null) ?: emptySet()).toMutableSet()
                val rssPrefs = getSharedPreferences("cesia_rss_sources", MODE_PRIVATE)
                if (currentOpts.contains("rss_cache")) {
                    // 当前已选中 -> 取消选中
                    currentOpts.remove("rss_cache")
                    rssPrefs.edit()
                        .remove("selected_name")
                        .remove("selected_url")
                        .remove("selected_category")
                        .apply()
                } else {
                    // 当前未选中 -> 选中（若有上次选中的源则恢复，否则选第一个预置源）
                    currentOpts.add("rss_cache")
                    val selected = RssFetchManager.getSelectedSource(this@CesiaInputMethod)
                    if (selected == null) {
                        // 无历史选择，默认选第一个预置源
                        val firstSource = RssFetchManager.PRESET_SOURCES.firstOrNull()
                        if (firstSource != null) {
                            RssFetchManager.saveSelectedSource(this@CesiaInputMethod, firstSource)
                        }
                    }
                }
                smartPrefs.edit().putStringSet("selected_options", currentOpts).apply()
                savedOptions = smartPrefs.getStringSet("selected_options", null) ?: emptySet()
                refreshOption(it as TextView, "rss_cache", OPT_RSS_SOURCE)
            }
            optSearch.setOnClickListener { toggleOption(it as TextView, "search", OPT_SEARCH) }
            optLocalLib.setOnClickListener {
                // 如果已选中，再次点击取消；如果未选中，先选文件
                val current = (smartPrefs.getStringSet("selected_options", null) ?: emptySet()).toMutableSet()
                if (current.contains("local_lib")) {
                    current.remove("local_lib")
                    smartPrefs.edit().putStringSet("selected_options", current).apply()
                    savedOptions = smartPrefs.getStringSet("selected_options", null) ?: emptySet()
                    refreshOption(it as TextView, "local_lib", OPT_LOCAL_LIB)
                } else {
                    // 弹出文件选择器（通过透明辅助 Activity）
                    val intent = android.content.Intent(this, FilePickerActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(intent)
                        // 选完后通过 onResume 或 SharedPreferences 回调刷新选中状态
                        // 简单方案：选完文件后自动标记为已选中
                        current.add("local_lib")
                        smartPrefs.edit().putStringSet("selected_options", current).apply()
                    } catch (e: Exception) {
                        Log.w("Cesia", "Cannot open file picker: ${e.message}")
                    }
                }
            }

            // 智能写作命令列表（2列，可滚动，与魔法书一致）
            val gvRecords = popupView.findViewById<android.widget.GridView>(R.id.gv_smart_records)
            val smartRecords = mutableListOf<String>()
            loadSmartRecords(smartRecords)

            val recordAdapter = object : android.widget.BaseAdapter() {
                override fun getCount() = smartRecords.size
                override fun getItem(p: Int) = smartRecords[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val v = cv ?: android.view.LayoutInflater.from(this@CesiaInputMethod)
                        .inflate(R.layout.item_smart_command, parent, false)
                    val tvCommand = v.findViewById<android.widget.TextView>(R.id.tv_smart_command)
                    tvCommand.text = smartRecords[p]
                    return v
                }
            }
            gvRecords.adapter = recordAdapter

            // 追踪当前编辑状态
            var editingPosition = -1
            var hasFocusedEdit = false

            fun notifyChanged() {
                (gvRecords.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            }

            // 长按：置顶/删除（和魔法书一致）
            gvRecords.setOnItemLongClickListener { _, view, position, _ ->
                if (position < smartRecords.size) {
                    val item = smartRecords[position]
                    smartRecords.removeAt(position)
                    smartRecords.add(0, item)
                    saveSmartRecords(smartRecords)
                    notifyChanged()
                    updateStatus("⤒ 已置顶：${item.take(20)}")
                }
                true
            }

            // 单击：直接执行该命令（调用AI）
            gvRecords.setOnItemClickListener { _: android.widget.AdapterView<*>?, _: android.view.View?, position: Int, _: Long ->
                if (position < smartRecords.size) {
                    val command = smartRecords[position]
                    smartWritingPopup?.dismiss()
                    smartWritingPopup = null
                    executeSmartCommand(command)
                }
            }

            // 底部按钮
            val btnAdd = popupView.findViewById<TextView>(R.id.btn_smart_add)
            val btnPin = popupView.findViewById<TextView>(R.id.btn_smart_pin)
            val btnDelete = popupView.findViewById<TextView>(R.id.btn_smart_delete)
            val btnClose = popupView.findViewById<TextView>(R.id.btn_smart_close)

            // ===== ＋：进入编辑模式输入新命令 =====
            btnAdd.setOnClickListener {
                smartWritingPopup?.dismiss()
                smartWritingPopup = null
                enterSmartEditMode()
            }

            // ===== 置顶按钮：PopupMenu 选择置顶指定命令 =====
            btnPin.setOnClickListener {
                if (smartRecords.isEmpty()) return@setOnClickListener
                val popupMenu = android.widget.PopupMenu(this, btnPin)
                for ((idx, cmd) in smartRecords.withIndex()) {
                    val title = "${if (idx == 0) "⤒ " else "○ "}${cmd.take(20)}"
                    popupMenu.menu.add(0, idx, 0, title)
                }
                popupMenu.setOnMenuItemClickListener { item ->
                    val pos = item.itemId
                    if (pos >= 0 && pos < smartRecords.size) {
                        val moved = smartRecords.removeAt(pos)
                        smartRecords.add(0, moved)
                        saveSmartRecords(smartRecords)
                        notifyChanged()
                        updateStatus("⤒ 已置顶：${moved.take(18)}")
                    }
                    true
                }
                popupMenu.show()
            }

            // ===== 删除按钮：PopupMenu 选择删除 =====
            btnDelete.setOnClickListener {
                if (smartRecords.isEmpty()) return@setOnClickListener
                val popupMenu = android.widget.PopupMenu(this, btnDelete)
                // 全部删除置顶（order=0）
                popupMenu.menu.add(0, -1, 0, "⊗ 删除全部（${smartRecords.size}条）")
                for ((idx, cmd) in smartRecords.withIndex()) {
                    popupMenu.menu.add(0, idx + 1, 1, "⊗ ${cmd.take(18)}")
                }
                popupMenu.setOnMenuItemClickListener { item ->
                    if (item.itemId == -1) {
                        smartRecords.clear()
                        saveSmartRecords(smartRecords)
                        notifyChanged()
                        updateStatus("⊗ 已清空智能写作命令")
                    } else {
                        val pos = item.itemId - 1
                        if (pos >= 0 && pos < smartRecords.size) {
                            val removed = smartRecords.removeAt(pos)
                            saveSmartRecords(smartRecords)
                            notifyChanged()
                            updateStatus("⊗ 已删除：${removed.take(18)}")
                        }
                    }
                    true
                }
                popupMenu.show()
            }

            // ===== 关闭按钮 =====
            btnClose.setOnClickListener {
                smartWritingPopup?.dismiss()
                smartWritingPopup = null
            }

            // 弹窗尺寸和定位（与魔法书一致）
            val keyboardWidth = keyboardView.width
            val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

            // 测量标题栏实际高度
            popupView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(popupWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            )
            val titleHeightPx = popupView.findViewById<android.widget.TextView>(R.id.tv_smart_title)?.measuredHeight
                ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt()

            val barHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics
            ).toInt()

            // 选项区高度（2列×2行 × 40dp）
            val optionHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics
            ).toInt()
            val optionsHeightPx = optionHeightPx * 2 + TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
            ).toInt() // padding

            val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
                if (id > 0) resources.getDimensionPixelSize(id) else 88
            }
            val keyboardLocation = IntArray(2)
            keyboardView.getLocationOnScreen(keyboardLocation)
            val keyboardTopScreenY = keyboardLocation[1]
            val totalHeight = (keyboardTopScreenY - statusBarHeight).coerceAtLeast(200)

            // GridView 使用 weight=1 自动填满剩余空间，无需手动设置高度

            // 弹窗尺寸：固定高度 = 键盘顶部 - 状态栏底部，完全填满
            val popup = PopupWindow(popupView, popupWidth, totalHeight, true)
            popup.isOutsideTouchable = false
            popup.elevation = 4f
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.setFocusable(false)

            popup.setOnDismissListener {
                smartWritingPopup = null
                stopMagicButtonGlow()
                btnMagic.background = makeKeyBgDrawable(currentKeyBg)
                btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
            }

            popup.showAtLocation(keyboardView, android.view.Gravity.TOP or android.view.Gravity.START, 0, -totalHeight)
            smartWritingPopup = popup
        } catch (e: Exception) {
            Log.e("Cesia", "showSmartWritingPopup 异常", e)
        }
    }

    /** 进入智能写作命令编辑模式 */
    private fun enterSmartEditMode() {
        smartEditMode = true
        smartEditBuffer.clear()
        updateStatus("✏️ 输入智能写作命令...（按发送键保存）")
    }

    /** 执行智能写作命令（点击列表项直接调用AI） */
    private fun executeSmartCommand(command: String) {
        Log.d("Cesia", "executeSmartCommand: command=$command")
        val selectedOptions = getSmartWritingSelection()
        Log.d("Cesia", "executeSmartCommand: selectedOptions=$selectedOptions")

        // 立即显示状态，让用户知道正在处理
        updateStatus("⏳ 智能写作处理中...")

        // 获取剪贴板内容
        val clipboardText = if (selectedOptions.contains("clipboard")) {
            if (clipboardItems.isEmpty()) {
                val clipboardMgr = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                loadClipboardHistoryToClassMembers(clipboardMgr)
            }
            getClipboardFirstItemText()
        } else ""
        Log.d("Cesia", "executeSmartCommand: clipboardText=${clipboardText.length} chars")

        isAiProcessing = true
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // 构建结构化 prompt
                val promptParts = mutableListOf<String>()

                // 1. 用户命令
                promptParts.add("【指令】\n$command")

                // 2. 剪贴板内容
                if (clipboardText.isNotEmpty()) {
                    promptParts.add("【参考素材】\n$clipboardText")
                }

                // 3. 本地文本文件
                if (selectedOptions.contains("local_text")) {
                    val localTextContent = readLocalTextFile()
                    if (localTextContent.isNotEmpty()) {
                        promptParts.add("【本地文本】\n$localTextContent")
                    }
                }

                // 4. RSS 缓存
                if (selectedOptions.contains("rss_cache")) {
                    val rssCache = RssFetchManager.readCache(this@CesiaInputMethod)
                    if (rssCache.isNotBlank()) {
                        promptParts.add("【RSS 缓存】\n$rssCache")
                    }
                }

                // 5. 互联网搜索
                if (selectedOptions.contains("search")) {
                    val searchQuery = if (clipboardText.isNotEmpty()) {
                        clipboardText.take(80)
                    } else {
                        command.replace(COMMAND_STRIP_REGEX, "").trim()
                    }

                    val sdf = java.text.SimpleDateFormat("yyyy年MM月dd日", java.util.Locale.CHINA)
                    // val today = sdf.format(java.util.Date())
                    // 搜索时附加日期会导致部分时间点查询搜不到内容，先注释掉日期，仅用原始查询
                    val finalQuery = searchQuery // "$searchQuery $today"

                    Log.d("Cesia", "SearXNG query: $finalQuery")
                    withContext(Dispatchers.Main) { updateStatus("🔍 正在搜索：${finalQuery.take(20)}...") }
                    val tavilyResults = performSearXNGSearch(finalQuery)
                    Log.d("Cesia", "SearXNG results: ${tavilyResults.length} chars")
                    if (tavilyResults.isNotEmpty()) {
                        promptParts.add("【网络搜索】\n$tavilyResults")
                    }
                }

                val ic = currentInputConnection ?: run {
                    withContext(Dispatchers.Main) {
                        isAiProcessing = false
                        updateStatus("❌ 无法获取输入连接")
                    }
                    return@launch
                }

                // 本地文库：读取选中的 txt 文件内容
                if (selectedOptions.contains("local_lib")) {
                    val libPrefs = getSharedPreferences("cesia_smart_writing", MODE_PRIVATE)
                    val libContent = libPrefs.getString(FilePickerActivity.RESULT_KEY_FILE_CONTENT, "")
                    val libName = libPrefs.getString(FilePickerActivity.RESULT_KEY_FILE_NAME, "")
                    if (libContent != null && libContent.isNotEmpty()) {
                        promptParts.add("【本地文库：$libName】\n${libContent.take(3000)}")
                        Log.d("Cesia", "LocalLib: loaded $libName (${libContent.length} chars)")
                    }
                }

                val textBefore = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                if (textBefore.isNotEmpty()) {
                    promptParts.add("【当前文本】\n$textBefore")
                }

                val fullPrompt = promptParts.joinToString("\n\n") + "\n\n只输出结果："

                Log.d("Cesia", "SmartWriting prompt: ${fullPrompt.take(200)}...")

                withContext(Dispatchers.Main) { updateStatus("🤖 AI 正在生成...") }

                // 根据本地/云端模式选择执行路径
                val useLocal = isLocalPolishMode() && modelManager.hasAiModel()
                Log.d("Cesia", "executeSmartCommand: useLocal=$useLocal, cloudMode=$cloudMode, hasAiModel=${modelManager.hasAiModel()}")

                val result = if (useLocal) {
                    val modelFile = modelManager.getInstalledAiModelFile()
                    if (modelFile != null && modelFile.exists() && !aiEngine.isModelLoaded()) {
                        val configPath = if (modelFile.isDirectory) File(modelFile, "config.json").absolutePath else modelFile.absolutePath
                        aiEngine.loadLocalModel(configPath)
                    }
                    aiEngine.polishWithPrompt(fullPrompt)
                } else {
                    val polishService = typelessEngine?.getPolishService()
                    Log.d("Cesia", "executeSmartCommand: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(50) ?: "null"}")
                    polishService?.polishWithPrompt(fullPrompt)
                }

                Log.d("Cesia", "executeSmartCommand: result=${result?.take(100) ?: "NULL"}, resultIsNull=${result == null}")

                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    if (result != null && result.isNotEmpty() && result != "null") {
                        ic.commitText(result, 1)
                        updateStatus(" 智能写作已完成")
                        try {
                            val smartRecords = mutableListOf<String>()
                            loadSmartRecords(smartRecords)
                            smartRecords.remove(command)
                            smartRecords.add(0, command)
                            if (smartRecords.size > 50) {
                                smartRecords.subList(50, smartRecords.size).clear()
                            }
                            saveSmartRecords(smartRecords)
                        } catch (e: Exception) {
                            Log.e("Cesia", "保存智能写作记录失败", e)
                        }
                    } else {
                        updateStatus("⚠️ 无输出")
                    }
                }
            } catch (e: Exception) {
                Log.e("Cesia", "executeSmartCommand failed", e)
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    updateStatus("❌ 失败：${e.message}")
                }
            }
        }
    }

    /** Tavily Search API（互联网搜索）
     *  支持多个 API Key：优先用当前选中的 key，失败后依次尝试历史记录里的其他 key（避免“只能1条/新键顶旧键”）
     */
    private fun performSearXNGSearch(query: String): String {
        Log.d("Cesia", "TavilySearch: start, query=$query")
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        val activeKey = prefs.getString("tavily_api_key", "") ?: ""
        val historyKeys = prefs.getString("tavily_key_history", "")?.split("||")
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        // 去重后的 key 池：当前选中在前
        val keyPool = (listOf(activeKey) + historyKeys).filter { it.isNotEmpty() }.distinct()
        if (keyPool.isEmpty()) {
            Log.w("Cesia", "TavilySearch: API key not configured")
            return ""
        }

        // 构造请求体（与 key 无关，只构造一次）
        val jsonBody = org.json.JSONObject().apply {
            put("query", query)
            put("max_results", 5)
            put("include_answer", true)
            put("search_depth", "basic")
            put("topic", "news")
            put("days", 1)
        }.toString()
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        // 依次尝试每个 key，第一个成功的即用
        for ((idx, key) in keyPool.withIndex()) {
            try {
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .addHeader("Authorization", "Bearer $key")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                val code = response.code
                Log.d("Cesia", "TavilySearch[$idx]: HTTP $code")
                if (code == 200) {
                    val json = response.body?.string() ?: ""
                    val results = parseTavilyResults(json)
                    if (results.isNotEmpty()) {
                        if (idx != 0) {
                            Log.i("Cesia", "TavilySearch: 使用第 ${idx + 1} 个 key 成功")
                        }
                        return results
                    }
                    response.close()
                } else {
                    Log.w("Cesia", "TavilySearch[$idx] error HTTP $code: ${response.body?.string()?.take(200)}")
                    response.close()
                    // 401/429 等说明该 key 失效，继续尝试下一个
                    if (code == 401 || code == 429) continue
                }
            } catch (e: Exception) {
                Log.w("Cesia", "TavilySearch[$idx] failed: ${e.message}")
            }
        }
        Log.d("Cesia", "TavilySearch: all keys failed or empty")
        return ""
    }

    /** 检查文本中是否包含今天日期 */

    /** 解析 Tavily Search JSON 结果 */
    private fun parseTavilyResults(json: String): String {
        try {
            val obj = org.json.JSONObject(json)
            // 先取 answer（LLM 摘要答案）
            val answer = obj.optString("answer", "").trim()
            val results = obj.optJSONArray("results") ?: return ""
            if (results.length() == 0 && answer.isEmpty()) return ""
            val sb = StringBuilder()
            if (answer.isNotEmpty()) {
                sb.appendLine("【摘要】$answer")
                sb.appendLine()
            }
            for (i in 0 until minOf(results.length(), 5)) {
                val item = results.getJSONObject(i)
                val title = item.optString("title", "").trim()
                val content = item.optString("content", "").trim()
                val url = item.optString("url", "").trim()
                if (title.isNotEmpty()) sb.appendLine("• $title")
                if (content.isNotEmpty()) sb.appendLine("  ${content.take(200)}")
                if (url.isNotEmpty()) sb.appendLine("  $url")
                if (i < minOf(results.length(), 5) - 1) sb.appendLine()
            }
            return sb.toString().trim()
        } catch (e: Exception) {
            Log.e("Cesia", "Tavily parse error", e)
            return ""
        }
    }

    /** 加载智能写作命令记录 */
    private fun loadSmartRecords(list: MutableList<String>) {
        try {
            val prefs = getSharedPreferences("cesia_smart_records", MODE_PRIVATE)
            val records = prefs.getString("records", "") ?: ""
            if (records.isNotEmpty()) {
                list.clear()
                list.addAll(records.split("\n").filter { it.isNotEmpty() })
            } else {
                // 首次使用：注入生成类10条标准指令
                list.clear()
                list.addAll(com.cesia.input.instruction.InstructionSet.starInstructions.map { it.name })
                saveSmartRecords(list)
            }
        } catch (e: Exception) {
            Log.e("Cesia", "loadSmartRecords 异常", e)
        }
    }

    /** 保存智能写作命令记录 */
    private fun saveSmartRecords(list: List<String>) {
        try {
            val prefs = getSharedPreferences("cesia_smart_records", MODE_PRIVATE)
            prefs.edit().putString("records", list.joinToString("\n")).apply()
        } catch (e: Exception) {
            Log.e("Cesia", "saveSmartRecords 异常", e)
        }
    }


    /** 加载智能写作记录 */
    private fun loadMagicRecords(list: MutableList<String>) {
        try {
            val prefs = getSharedPreferences("cesia_magic_records", MODE_PRIVATE)
            val records = prefs.getString("records", "") ?: ""
            if (records.isNotEmpty()) {
                list.clear()
                list.addAll(records.split("\n").filter { it.isNotEmpty() })
            }
        } catch (e: Exception) {
            Log.e("Cesia", "loadMagicRecords 异常", e)
        }
    }

    /** 保存智能写作记录 */
    private fun saveMagicRecords(list: List<String>) {
        try {
            val prefs = getSharedPreferences("cesia_magic_records", MODE_PRIVATE)
            prefs.edit().putString("records", list.joinToString("\n")).apply()
        } catch (e: Exception) {
            Log.e("Cesia", "saveMagicRecords 异常", e)
        }
    }

    /** 获取当前智能写作选中状态（供短按时使用） */
    private fun getSmartWritingSelection(): Set<String> {
        val prefs = getSharedPreferences("cesia_smart_writing", MODE_PRIVATE)
        return prefs.getStringSet("selected_options", emptySet()) ?: emptySet()
    }

    /** 短按星星按钮：执行智能写作 */
    private fun executeSmartWriting() {
        val selectedOptions = getSmartWritingSelection()
        Log.d("Cesia", "executeSmartWriting: selected=${selectedOptions.size}")

        if (selectedOptions.isEmpty()) {
            updateStatus("⚠️ 请先长按星星按钮设置写作选项")
            return
        }

        // 构建语境
        val contextParts = mutableListOf<String>()

        if (selectedOptions.contains("clipboard")) {
            val clipboardText = getClipboardFirstNonPinned()
            Log.d("Cesia", "executeSmartWriting: clipboard=${clipboardText.length} chars")
            if (clipboardText.isNotEmpty()) {
                contextParts.add("参考内容：\n$clipboardText")
            }
        }
        if (selectedOptions.contains("local_text")) {
            val localTextContent = readLocalTextFile()
            Log.d("Cesia", "executeSmartWriting: local_text=${localTextContent.length} chars")
            if (localTextContent.isNotEmpty()) {
                contextParts.add("本地文本：\n$localTextContent")
            }
        }
        if (selectedOptions.contains("rss_cache")) {
            val rssCache = RssFetchManager.readCache(this@CesiaInputMethod)
            Log.d("Cesia", "executeSmartWriting: rss_cache=${rssCache.length} chars")
            if (rssCache.isNotBlank()) {
                contextParts.add("RSS缓存：\n$rssCache")
            }
        }
        if (selectedOptions.contains("search")) {
            contextParts.add("搜索模式：需要联网获取相关信息")
        }

        if (contextParts.isEmpty()) {
            updateStatus("⚠️ 未获取到有效语境内容")
            return
        }

        // 获取当前输入框文本
        val ic = currentInputConnection ?: run {
            Log.e("Cesia", "executeSmartWriting: currentInputConnection is null")
            return
        }
        val textBefore = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""

        val fullContext = contextParts.joinToString("\n\n")
        val prompt = "请基于以下语境进行智能写作：\n\n$fullContext\n\n当前文本：\n$textBefore\n\n请续写或优化："

        Log.d("Cesia", "executeSmartWriting: prompt length=${prompt.length}")

        isAiProcessing = true
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // 根据本地/云端模式选择执行路径
                val useLocal = isLocalPolishMode() && modelManager.hasAiModel()
                Log.d("Cesia", "executeSmartWriting: useLocal=$useLocal, cloudMode=$cloudMode, hasAiModel=${modelManager.hasAiModel()}")

                val result = if (useLocal) {
                    // 本地 MNN 推理
                    val modelFile = modelManager.getInstalledAiModelFile()
                    if (modelFile != null && modelFile.exists() && !aiEngine.isModelLoaded()) {
                        val configPath = if (modelFile.isDirectory) File(modelFile, "config.json").absolutePath else modelFile.absolutePath
                        aiEngine.loadLocalModel(configPath)
                    }
                    aiEngine.polishWithPrompt(prompt)
                } else {
                    // 云端 OpenRouter
                    val polishService = typelessEngine?.getPolishService()
                    Log.d("Cesia", "executeSmartWriting: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(50) ?: "null"}")
                    polishService?.polishWithPrompt(prompt)
                }

                Log.d("Cesia", "executeSmartWriting: result=${result?.take(80) ?: "null"}, isNullOrEmpty=${result.isNullOrEmpty()}")
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    if (result != null && result.isNotEmpty()) {
                        ic.commitText(result, 1)
                        updateStatus(" 智能写作已完成")
                    } else {
                        updateStatus("⚠️ 智能写作无输出")
                    }
                }
            } catch (e: Exception) {
                Log.e("Cesia", "executeSmartWriting failed", e)
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    updateStatus("❌ 智能写作失败：${e.message}")
                }
            }
        }
    }

    /** 构建语法指南（注入 AI 润色，供借鉴） */
    private fun buildGrammarGuide(): String {
        return try {
            val guideMgr = com.cesia.input.stats.GrammarGuideManager(this)
            val guideContent = guideMgr.content
            if (guideContent.isNotEmpty()) {
                guideContent
            } else {
                // AI 未生成时，回退本地大纲（基于全部历史记录），保证 AI 润色有借鉴素材
                val local = guideMgr.buildLocalOutline(statsManager.getRecords())
                if (local.isNotEmpty()) local else ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** 读取本地文本文件内容 */
    private fun readLocalTextFile(): String {
        return try {
            val prefs = getSharedPreferences("cesia_smart_writing", MODE_PRIVATE)
            val fileName = prefs.getString(FilePickerActivity.RESULT_KEY_FILE_NAME, "") ?: ""
            if (fileName.isNotEmpty()) {
                val file = java.io.File(fileName)
                if (file.exists()) file.readText() else ""
            } else ""
        } catch (e: Exception) { "" }
    }

    /** 弹窗引用（用于长按互斥关闭） */
    private var magicHistoryPopup: PopupWindow? = null
    private var smartWritingPopup: PopupWindow? = null
    private var clipboardPopup: PopupWindow? = null
    private var smartEditMode = false
    private var smartEditBuffer = StringBuilder()

    // ======================== 剪贴板新增模式 ========================
    private var clipboardAddMode = false
    private var clipboardAddBuffer = StringBuilder()

    /** 关闭所有弹窗（长按互斥） */
    private fun dismissAllPopups() {
        magicHistoryPopup?.dismiss()
        magicHistoryPopup = null
        smartWritingPopup?.dismiss()
        smartWritingPopup = null
        clipboardPopup?.dismiss()
        clipboardPopup = null
        themePopup?.dismiss()
        themePopup = null
        // 清除所有按钮高亮状态
        stopMagicButtonGlow()
        btnMagic.background = makeKeyBgDrawable(currentKeyBg)
        btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
        stopMagicBookGlow()
        // 退出剪贴板新增模式
        if (clipboardAddMode) exitClipboardAddMode(save = false)
    }

// endregion 候选适配器

// region 魔法编辑
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
        rvCandidates?.scrollToPosition(0)
        setCandidateBarVisible(rimeEngine.isComposing)
    }

    /** 退出剪贴板新增模式 */
    private fun exitClipboardAddMode(save: Boolean = false) {
        if (save && clipboardAddBuffer.isNotEmpty()) {
            val text = clipboardAddBuffer.toString().trim()
            if (text.isNotEmpty()) {
                clipboardItems.add(0, ClipboardItem(text = text, isPinned = false))
                saveClipboardHistoryFromClassMembers()
                updateStatus(" 已保存至剪贴板：${text.take(20)}")
            }
        } else {
            if (clipboardAddMode) updateStatus("❌ 已取消新增剪贴板")
        }
        clipboardAddMode = false
        clipboardAddBuffer.clear()
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
                updateStatus(" 已保存魔法：${text.take(20)}")
            }
        } else {
            if (magicEditMode) updateStatus("❌ 已取消新增魔法")
        }
        magicEditMode = false
        magicEditBuffer.clear()
        magicEditMgr = null
    }

    /** 更新智能写作命令编辑状态（同步候选栏） */
    private fun updateSmartEditStatus() {
        val comp = rimeEngine.composingText
        val display = smartEditBuffer.toString() + comp
        if (display.isEmpty()) {
            updateStatus("✏️ 输入智能写作命令...（按发送键保存）")
        } else {
            updateStatus("✏️ $display")
        }
        // 同步更新候选栏
        val allCands = rimeEngine.getAllCandidates()
        candidateAdapter?.updateData(allCands)
        rvCandidates?.scrollToPosition(0)
        setCandidateBarVisible(rimeEngine.isComposing)
    }

    /** 更新剪贴板新增模式状态（同步候选栏） */
    private fun updateClipboardAddStatus() {
        val comp = rimeEngine.composingText
        val display = clipboardAddBuffer.toString() + comp
        if (display.isEmpty()) {
            updateStatus("✏️ 输入剪贴板内容...（按发送键保存）")
        } else {
            updateStatus("✏️ $display")
        }
        // 同步更新候选栏
        val allCands = rimeEngine.getAllCandidates()
        candidateAdapter?.updateData(allCands)
        rvCandidates?.scrollToPosition(0)
        setCandidateBarVisible(rimeEngine.isComposing)
    }

    /** 退出智能写作命令编辑模式 */
    private fun exitSmartEditMode(save: Boolean = false, execute: Boolean = false) {
        if (save && smartEditBuffer.isNotEmpty()) {
            val text = smartEditBuffer.toString().trim()
            if (text.isNotEmpty()) {
                val prefs = getSharedPreferences("cesia_smart_records", MODE_PRIVATE)
                val records = prefs.getString("records", "") ?: ""
                val list = if (records.isNotEmpty()) records.split("\n").filter { it.isNotEmpty() }.toMutableList() else mutableListOf()
                list.add(0, text)
                if (list.size > 50) list.removeAt(list.size - 1)
                prefs.edit().putString("records", list.joinToString("\n")).apply()
                updateStatus(" 已保存并执行：${text.take(20)}")
                // 保存后直接执行
                if (execute) {
                    executeSmartCommand(text)
                    smartEditMode = false
                    smartEditBuffer.clear()
                    return
                }
            }
        } else {
            if (smartEditMode) updateStatus("❌ 已取消新增命令")
        }
        smartEditMode = false
        smartEditBuffer.clear()
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
                updateStatus(" 已新增魔法：${text.take(20)}")
            } else {
                // 编辑已有魔法
                if (text != record.instruction) {
                    mgr.removeRecord(record.id)
                    mgr.addRecord(text)
                    updateStatus(" 已修改魔法：${text.take(20)}")
                }
            }
        }
        onComplete()
    }

    // 在输入法服务中显示 dialog 的通用方法

// endregion 魔法编辑

// region AI自动回复
    // ======================== AI自动回复 ========================

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
        // 使用统一润色入口（自动适配本地/云端），prompt 已包含完整上下文
        executePolish(prompt, "AI回复") { result, success ->
            isAiProcessing = false
            setStatusDot("idle")
            if (success && result.isNotEmpty()) {
                ic.commitText(result, 1)
                updateStatus(" AI已生成建议内容")
            } else {
                updateStatus("⚠️ AI未生成有效内容，请重试")
            }
        }
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
        traditionalGlowing = isTraditional  // 正体模式才允许简繁键高亮随主题刷新
        maybeShowButtonHint("traditional", if (isTraditional) "正体输入模式" else "简体输入模式")
        updateTraditionalButton()
        // 切换后重新触发候选（Rime stub 不支持 setOption，用本地 OpenCC 转换）
        updateCandidateBar()
    }

    /** 逐字组词去重：若 Rime 最后一步返回整串(如"六牛柳")，而前面已上屏"六牛"，
     *  则只返回新增尾巴("柳")。无前缀累积或不是前缀时原样返回。 */
    private fun stripDuplicatePrefix(selected: String): String {
        val soFar = t9ComposedSoFar.toString()
        if (soFar.isNotEmpty() && selected.startsWith(soFar)) {
            return selected.substring(soFar.length)
        }
        return selected
    }

    /** 候选词选中上屏：根据当前简繁状态做转换 */
    private fun commitCandidateText(text: String) {
        try {
            val output = if (isTraditional) toTraditional(text) else text
            currentInputConnection?.commitText(output, 1)
        } catch (e: Exception) {
            Log.e("Cesia", "commitCandidateText failed: ${e.message}")
        }
    }

    // ===== 用户自建词组库：接龙组词上屏写入，下次匹配注入候选，持久化到 cesia_dict =====
    private fun loadUserPhrases() {
        try {
            val json = getSharedPreferences("cesia_dict", MODE_PRIVATE).getString("user_phrases_json", "") ?: ""
            if (json.isNotEmpty()) {
                val obj = org.json.JSONObject(json)
                userPhrases.clear()
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    userPhrases[k] = obj.getString(k)
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveUserPhrases() {
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in userPhrases) obj.put(k, v)
            getSharedPreferences("cesia_dict", MODE_PRIVATE).edit()
                .putString("user_phrases_json", obj.toString()).apply()
        } catch (_: Exception) {}
    }

    /** 写入用户词组（词→数字串），去重并保持最近优先 */
    private fun addUserPhrase(phrase: String, digits: String) {
        if (phrase.length < 2 || digits.isEmpty()) return
        userPhrases.remove(phrase)
        userPhrases[phrase] = digits
        // 限制规模，避免无限增长
        while (userPhrases.size > 500) {
            val firstKey = userPhrases.keys.firstOrNull() ?: break
            userPhrases.remove(firstKey)
        }
        saveUserPhrases()
    }

    private fun updateTraditionalButton() {
        // 更新按钮视觉状态（不再弹出脉冲动画，仅颜色变化）
        if (::btnTraditional.isInitialized) {
            // 简体模式显示"简"字，正体模式显示"正"字
            btnTraditional.text = if (isTraditional) "正" else "简"
            btnTraditional.setTextColor(if (isTraditional) themeAccent else 0xFF888888.toInt())
            btnTraditional.setBackgroundColor(if (isTraditional) (themeAccent and 0x00FFFFFF) or 0x22000000 else 0x00000000)
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
            // 备用 API（上一个用过的）：仅默认 API 网络层失败时自动回退
            val fbUrl = prefs.getString("fallback_api_url", "") ?: ""
            val fbKey = prefs.getString("fallback_api_key", "") ?: ""
            val fbModel = prefs.getString("fallback_model_id", "") ?: ""
            if (fbUrl.isNotEmpty()) {
                typelessEngine?.getPolishService()?.setFallbackApi(fbUrl, fbKey, fbModel)
            } else {
                typelessEngine?.getPolishService()?.setFallbackApi(null, null, null)
            }
            // 用户设定的默认键盘（长按切换键设定，打开输入法即用）
            val savedDefault = prefs.getString("default_keyboard_mode", "NUMBER") ?: "NUMBER"
            defaultKeyboardMode = try { KeyboardMode.valueOf(savedDefault) } catch (_: Exception) { KeyboardMode.NUMBER }
        } catch (_: Exception) {
            apiUrl = DEFAULT_API_URL
        }
    }

// endregion AI自动回复

// region 语音后端
    // ======================== 语音后端自动切换 ========================

    /**
     * 根据本地模式开关和模型安装情况确定语音后端
     * 规则：
     * 1. 云端模式（localModeEnabled=false）→ 始终使用 Google
     * 2. 本地模式（localModeEnabled=true）+ bridge + 模型 → 本地 Whisper
     * 3. 本地模式但 bridge 或模型缺失 → 回退 Google + 状态栏提示
     */
    private fun updateVoiceBackend() {
        val bridgeLoaded = SherpaOnnxEngine.isLibraryLoaded()
        val hasLocalModel = voiceEngine.hasSherpaModel()
        val modelName = voiceEngine.getSherpaModelName()

        // 诊断信息
        val bridgeError = SherpaOnnxEngine.getLibraryLoadError()
        Log.i("Cesia", "updateVoiceBackend: localMode=$localModeEnabled, bridgeLoaded=$bridgeLoaded, bridgeError=$bridgeError, hasLocalModel=$hasLocalModel, modelName=$modelName")

        // 库 + 模型都可用 → Sherpa-onnx
        if (bridgeLoaded && hasLocalModel) {
            voiceEngine.setBackend(VoiceEngine.Backend.LOCAL_SHERPA)
            val modeLabel = if (localModeEnabled) "本地模式" else "云端模式+本地加速"
            Log.i("Cesia", "语音后端: 本地 Sherpa-onnx ($modeLabel, $modelName)")
            // 异步预热 OnlineRecognizer，避免首次点击语音键的延迟
            voiceEngine.warmupRecognizer()
            return
        }

        // 本地模式但缺少依赖 → 回退 Google + 提示具体原因
        if (localModeEnabled) {
            if (!bridgeLoaded) {
                val reason = bridgeError ?: "未知错误"
                Log.w("Cesia", "语音后端: Google（本地模式但 Sherpa 库未加载: $reason）")
                updateStatus("🎤 语音: Google（⚠️ Sherpa 库未加载: $reason）")
            } else if (!hasLocalModel) {
                Log.w("Cesia", "语音后端: Google（本地模式但 Sherpa 模型未安装）")
                updateStatus("🎤 语音: Google（⚠️ Sherpa 模型未安装）")
            }
            return
        }

        // 云端模式 + 无本地模型 → 静默使用 Google（不提示）
        Log.i("Cesia", "语音后端: Google（云端模式，无本地模型）")
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
        val modeName = modePrefs.getString("run_mode", LocalModeManager.RunMode.CLOUD_FREE.name)
            ?: LocalModeManager.RunMode.CLOUD_FREE.name
        val mode = try { LocalModeManager.RunMode.valueOf(modeName) }
            catch (_: Exception) { LocalModeManager.RunMode.CLOUD_FREE }
        val hasLocalModel = modelManager.hasVoiceModel()

        return when (mode) {
            LocalModeManager.RunMode.CLOUD_FREE, LocalModeManager.RunMode.CLOUD_PAID -> "Google 云端"
            LocalModeManager.RunMode.LOCAL -> {
                if (hasLocalModel) "本地 Whisper" else "Google (回退)"
            }
        }
    }

// endregion 语音后端

// region 长按选择
    // ======================== 长按选择面板 ========================

    /**
     * 长按语音键弹出的选择面板
     * 用户分别选择识别后端和润色后端
     */
    /**
     * 长按语音键弹出的选择面板
     * 用户分别选择识别后端和润色后端，点确认后保存配置
     */
    /** 本地模式录音 */

    /** 云端模式录音 */

// endregion 长按选择

// region 同声传译
    // ======================== 同声传译 ========================

    /** 开始同传录音 */
    private fun startSimulTranslateRecording() {
        val mgr = simulTranslateManager ?: run {
            updateStatus("⚠️ 同传管理器未初始化")
            return
        }
        if (!mgr.isInitialized()) {
            updateStatus("⚠️ 同传未就绪，请长按语音键切换到同传模式")
            return
        }

        isRecording = true
        recognizedText = ""
        setStatusDot("recording")
        startVoiceWave()
        keyboardView.visibility = View.GONE
        // 语音输入期间隐藏候选栏（不再常驻），避免状态栏下方出现无关的候选词栏目
        candidateBar.visibility = View.GONE
        candidateBarKeep = false

        // 设置同传回调
        mgr.onStatusUpdate = { status ->
            voiceEngineScope.launch(Dispatchers.Main) { updateStatus(status) }
        }
        mgr.onRecognized = { text ->
            voiceEngineScope.launch(Dispatchers.Main) { updateStatus("🎤 $text") }
        }
        mgr.onTranslated = { text ->
            voiceEngineScope.launch(Dispatchers.Main) { updateStatus("🌐 $text") }
        }
        mgr.onError = { error ->
            voiceEngineScope.launch(Dispatchers.Main) { updateStatus("❌ $error") }
        }

        // 启动同传
        mgr.start()
        updateSimulTranslateButton(true)

        // 开始语音识别，结果传给同传管理器
        voiceEngineScope.launch {
            try {
                voiceEngine.recordInSegments(
                    maxDurationMs = 300000,
                    segmentDurationMs = 3000,
                    onSegmentResult = { text, isFinal ->
                        if (text.isNotEmpty()) {
                            mgr.onRecognitionResult(text, isFinal)
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.e("Cesia", "同传录音失败", e)
                withContext(Dispatchers.Main) {
                    updateStatus("❌ 同传录音失败: ${e.javaClass.simpleName}")
                }
            } finally {
                mgr.stop()
                withContext(Dispatchers.Main) {
                    isRecording = false
                    updateSimulTranslateButton(false)
                    resetToIdle()
                }
            }
        }
    }

    /** 停止同传录音 */
    private fun stopSimulTranslateRecording() {
        simulTranslateManager?.stop()
        isRecording = false
        stopVoiceWave()
        updateSimulTranslateButton(false)
        resetToIdle()
    }

    /** 同传按钮点击 */

    /** 更新同传按钮外观 */
    private fun updateSimulTranslateButton(active: Boolean) {
        simulTranslateEnabled = active
        btnTheme?.text = if (active) "🔴" else "🎨"
        btnTheme?.alpha = if (active) 1.0f else 0.6f
    }

    /**
     * 根据用户选择的识别和润色后端开始录音
     */
    private fun startRecordingWithChoice(voiceChoice: VoiceChoice, polishChoice: PolishChoice) {
        isRecording = true
        isWaitingForChoice = false
        recognizedText = ""
        voiceKeptText = ""
        pendingAiMode = null
        setStatusDot("recording")
        startVoiceWave()
        keyboardView.visibility = View.GONE
        // 语音输入期间隐藏候选栏（不再常驻），避免状态栏下方出现无关的候选词栏目
        candidateBar.visibility = View.GONE
        candidateBarKeep = false
        voiceStartTime = System.currentTimeMillis()

        when (voiceChoice) {
            VoiceChoice.LOCAL_SHERPA -> {
                updateStatus("🎤 正在收听 (本地 Sherpa)...")
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

    /**
     * 锁定模式开始录音（不分裂按钮，不显示 AI+/AI×）
     */
    private fun startRecordingLocked() {
        isRecording = true
        isWaitingForChoice = false
        recognizedText = ""
        voiceKeptText = ""
        isContinuingSession = false
        pendingAiMode = null
        setStatusDot("recording")
        // 锁定模式不显示绿色圆点，避免按钮偏移
        // startVoiceWave() 已禁用
        keyboardView.visibility = View.GONE
        // 语音输入期间隐藏候选栏（不再常驻），避免状态栏下方出现无关的候选词栏目
        candidateBar.visibility = View.GONE
        candidateBarKeep = false
        voiceStartTime = System.currentTimeMillis()
        updateStatus("🎤 正在收听 (锁定模式)...")
        startWhisperRecordingAsync()
        // 不调用 showAiChoiceButtons()，保持语音键不分列
    }

    /**
     * 撤销/清空命令后继续录音：保持下划线（组合态）续识别。
     * 注意：voiceKeptText（真相源）已由调用方在撤销/清空分支里改好，这里【不】再改它。
     */
    private fun resumeRecordingKeepText() {
        isVoiceLocked = true
        isRecording = true
        isWaitingForChoice = false
        pendingAiMode = null
        isProcessingResult = false
        isContinuingSession = true
        setStatusDot("recording")
        keyboardView.visibility = View.GONE
        // 语音输入期间隐藏候选栏（不再常驻），避免状态栏下方出现无关的候选词栏目
        candidateBar.visibility = View.GONE
        candidateBarKeep = false
        voiceStartTime = System.currentTimeMillis()
        // 重新启动本地流式识别循环（resetStream 由 VoiceEngine 内部处理）
        startWhisperRecordingAsync()
    }

    /**
     * 中等级命令（发送/结束/润色/命令/写作）执行完后的收尾：
     * - 若处于语音锁定状态：清空上一轮文本，作为【全新一段】恢复监听+启动语音识别（不保留旧内容、不续接前缀），
     *   保证锁定期间说完命令词后仍在录音，不会出现“按钮闪烁但不识别”。
     * - 若未锁定：正常 resetToIdle 退出。
     */
    private fun finishCommandResumeIfLocked() {
        recognizedText = ""
        voiceKeptText = ""
        isContinuingSession = false
        pendingAiMode = null
        isProcessingResult = false
        if (isVoiceLocked) {
            isVoiceLocked = true
            isRecording = true
            isWaitingForChoice = false
            setStatusDot("recording")
            keyboardView.visibility = View.GONE
            candidateBar.visibility = View.GONE
            voiceStartTime = System.currentTimeMillis()
            updateStatus("🎤 正在收听 (锁定模式)...")
            startWhisperRecordingAsync()
        } else {
            resetToIdle()
        }
    }

    /** Google 语音识别（流式，通过 FallbackRecognizer） */
    private fun startGoogleRecording(polishChoice: PolishChoice) {
        try {
            Log.i("Cesia", "startGoogleRecording: typelessEngine=${typelessEngine != null}")
            typelessEngine?.startListening(continuous = true)
        } catch (e: Throwable) {
            Log.e("Cesia", "startGoogleRecording 异常", e)
            updateStatus("❌ Google 语音启动失败: ${e.javaClass.simpleName}")
        }
    }

    /** 本地 Zipformer 流式录音+识别（边说边出字） */
    private fun startWhisperRecordingAsync() {
        voiceEngineScope.launch {
            try {
                val bridgeLoaded = SherpaOnnxEngine.isLibraryLoaded()
                val hasLocalModel = voiceEngine.hasSherpaModel()
                val modelName = voiceEngine.getSherpaModelName()
                val modelId = modelManager.installedVoiceModelId
                Log.i("Cesia", "startWhisperRecordingAsync: bridgeLoaded=$bridgeLoaded, hasLocalModel=$hasLocalModel, modelName=$modelName, modelId=$modelId")
                if (!bridgeLoaded || !hasLocalModel) {
                    withContext(Dispatchers.Main) {
                        val reason = when {
                            !bridgeLoaded -> "Sherpa 库未加载"
                            !hasLocalModel -> "模型文件未找到"
                            else -> "未知原因"
                        }
                        updateStatus("⚠️ 本地语音不可用（$reason），回退到 Google...")
                        startGoogleRecording(PolishChoice.CLOUD_OPENROUTER)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    updateStatus("🎤 正在收听...")
                }

                var lastStreamingText = ""
                var segmentCount = 0
                // streamedAll：截至当前已识别的完整累积文本（VoiceEngine 已传全量），直接整体显示
                var streamedAll = ""
                // 兜底预热（若 updateVoiceBackend 还没触发）
                voiceEngine.warmupRecognizer()
                voiceEngine.recordInSegments(
                    maxDurationMs = 300000,  // 5分钟，避免长语音被截断
                    segmentDurationMs = 3000,
                    onSegmentResult = { text, isFinal ->
                        segmentCount++
                        Log.i("Cesia", "onSegmentResult #$segmentCount: text='${text.take(50)}', isFinal=$isFinal")
                        if (text.isNotEmpty()) {
                            // text 已是截至当前的完整累积文本，直接整体显示（边说边累计、不隐藏）
                            streamedAll = text
                            if (text != lastStreamingText) {
                                lastStreamingText = text
                                recognizedText = text
                                withContext(Dispatchers.Main) {
                                    // 检测到识别文本，隐藏语音命令提示
                                    if (statusLines.isNotEmpty() && statusLines.last().startsWith("💡")) {
                                        statusLines.removeAt(statusLines.size - 1)
                                    }
                                    // 流式显示：直接在光标位置显示已累计的全部识别文本（组合态）
                                    val ic = currentInputConnection ?: return@withContext
                                    ic.setComposingText(text, 1)
                                    updateStatus("🎤 $text")
                                }
                            }
                        }
                        if (isFinal) {
                            withContext(Dispatchers.Main) {
                                if (isContinuingSession) {
                                    // 续识别态：把本轮完整内容并入真相源 voiceKeptText（空格分隔追加前缀），
                                    // 然后保持组合态继续监听后续命令/内容。
                                    voiceKeptText = if (voiceKeptText.isNotEmpty()) "${voiceKeptText.trimEnd()} $streamedAll" else streamedAll
                                    Log.i("Cesia", "onSegmentResult: 续识别态 isFinal，voiceKeptText='${voiceKeptText.take(50)}'")
                                    resumeRecordingKeepText()
                                } else if (streamedAll.isNotEmpty()) {
                                    // 最终结果：确认组合文本（已是全部已识别内容）
                                    voiceKeptText = streamedAll
                                    val ic = currentInputConnection ?: return@withContext
                                    Log.i("Cesia", "onSegmentResult final: setComposing='${streamedAll.take(50)}' beforeFinish")
                                    ic.finishComposingText()
                                    Log.i("Cesia", "onSegmentResult final: afterFinish, calling handleCloudVoiceResult")
                                    handleCloudVoiceResult(streamedAll)
                                } else {
                                    Log.w("Cesia", "onSegmentResult: isFinal but text is empty!")
                                    handleCloudVoiceResult("")
                                }
                            }
                        }
                    },
                    onCommandWordDetected = { text: String, command: String ->
                        Log.i("Cesia", "命令词检测: command='$command', text='${text.take(50)}'")
                        withContext(Dispatchers.Main) {
                            isRecording = false
                            stopVoiceWave()
                            setStatusDot("idle")

                            // 注意：此时组合文本（setComposingText）显示的是不含命令词的原文
                            // 不要 finishComposingText + deleteSurroundingText，否则会删错字符
                            // 直接交给 replaceTextWithPolish / commitText 统一处理

                            val ic = currentInputConnection ?: run {
                                resetToIdle()
                                return@withContext
                            }

                            // 命令词语上屏：统一把中文数字转阿拉伯数字（与本地 sherpa/Google 路径一致），
                            // 避免“识别数字转阿拉伯数字失效又恢复汉字数字上屏”。
                            val textConv = voiceEngine.convertChineseDigitsToArabic(text)
                            val keptConv = voiceEngine.convertChineseDigitsToArabic(voiceKeptText)
                            // 去掉命令词本身：本地流式路径 onCommandWordDetected 传入的 text 仍含命令词
                            //（如“写作今天天气好”），与 Google 路径 checkCommandWord 返回已剥离文本保持一致，
                            // 避免“写作/润色/结束/退出”等命令词被上屏或带入处理。
                            val textNoCmd = voiceEngine.checkCommandWord(textConv)?.first ?: textConv

                            // 低等级命令（撤销/清空）不结束下划线：不 finish、不删命令词，
                            // 直接由下方分支用 setComposingText 整体替换 composing 区域。
                            if (command != "undo" && command != "clear" && command != "restore") {
                                // 把“已保留内容 + 本轮说的（去命令词）”拼成整体，作为要上屏/处理的真相源，
                                // 这样“结束/退出/发送/润色/命令/写作”执行时不会把命令词本身带上屏。
                                val combined = when {
                                    keptConv.isNotEmpty() && textNoCmd.isNotEmpty() -> "$keptConv $textNoCmd"
                                    textNoCmd.isNotEmpty() -> textNoCmd
                                    else -> keptConv
                                }
                                // 先 setComposingText(combined) 把组合区整体替换成“去掉命令词后的原文”，
                                // 再 finishComposingText 提交（避免把“结束/退出”等命令词提交上屏）。
                                ic.setComposingText(combined, 1)
                                ic.finishComposingText()
                                voiceKeptText = combined
                                recognizedText = combined
                            }

                            when (command) {
                                "exit" -> {
                                    // 退出（最高等级）：结束识别 + 结束语音锁定（含已确认前缀一并提交上屏）
                                    ic.finishComposingText()
                                    isVoiceLocked = false
                                    isContinuingSession = false
                                    voiceKeptText = ""
                                    updateMicButtonLockedState()
                                    updateStatus("🔓 已退出语音输入")
                                    resetToIdle()
                                }
                                "send" -> {
                                    // 发送（中等级）：确认文本（含前缀）+ 发送，然后——若处于锁定态则恢复监听继续识别
                                    updateStatus("已发送（长按显示剪贴板）")
                                    val editorInfo = currentInputEditorInfo
                                    val canSend = editorInfo != null &&
                                        (editorInfo.imeOptions and EditorInfo.IME_ACTION_SEND) != 0
                                    if (canSend) {
                                        // 标记“刚在锁定态发送”，避免发送后输入框 finish 触发 onFinishInputView
                                        // → forceExitVoiceMode 把锁定解除。发送后由 finishCommandResumeIfLocked 重新进入监听。
                                        justSentWhileLocked = isVoiceLocked
                                        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
                                    } else {
                                        Log.w("Cesia", "当前输入框不支持 IME_ACTION_SEND，imeOptions=${editorInfo?.imeOptions}")
                                        updateStatus(" 已上屏（当前输入框不支持自动发送）")
                                    }
                                    // 锁定态：作为全新一段恢复监听+启动识别；未锁定：退出
                                    finishCommandResumeIfLocked()
                                }
                                "ai" -> {
                                    // 润色（中等级）：对删除命令词后的完整文本（含前缀）润色，完成后——锁定态恢复监听
                                    val fullText = if (isContinuingSession && keptConv.isNotEmpty()) {
                                        "$keptConv $textNoCmd".trim()
                                    } else {
                                        textNoCmd
                                    }
                                    if (fullText.isEmpty()) {
                                        updateStatus("⚠️ 没有需要润色的文字")
                                        finishCommandResumeIfLocked()
                                    } else {
                                        updateStatus("✨ 语音润色中...")
                                        setStatusDot("processing")
                                        isProcessingResult = true
                                        isWaitingForChoice = false
                                        hideAiChoiceButtons()
                                        // 不置 isVoiceLocked=false：polishRecognizedText 内部收尾会
                                        // `if (isVoiceLocked) startRecordingLocked() else resetToIdle()`，
                                        // 锁定态自动恢复录音继续识别。
                                        isContinuingSession = false
                                        voiceKeptText = ""
                                        polishRecognizedText(fullText)
                                    }
                                }
                                "cmd" -> {
                                    // 命令模式（中等级）：执行指令（含前缀），完成后——锁定态恢复监听
                                    val fullText = if (isContinuingSession && keptConv.isNotEmpty()) {
                                        "$keptConv $textNoCmd".trim()
                                    } else {
                                        textNoCmd
                                    }
                                    if (fullText.isEmpty()) {
                                        updateStatus("⚠️ 请输入指令")
                                        finishCommandResumeIfLocked()
                                    } else {
                                        Log.i("Cesia", "命令模式: 指令='$fullText'")
                                        // 不置 isVoiceLocked=false：executeVoiceCommand 内部收尾锁定态自动恢复录音。
                                        isContinuingSession = false
                                        voiceKeptText = ""
                                        executeVoiceCommand(fullText)
                                    }
                                }
                                "finish" -> {
                                    // 结束（中等级）：把组合态文本（含前缀，已去命令词）提交上屏，并【始终】退出语音模式。
                                    // 注意：即使此前说过撤销/清空/恢复（会置 isVoiceLocked=true），
                                    // “结束”按其命令词本意应结束输入，不能因为之前的状态而变成继续录音。
                                    ic.finishComposingText()
                                    updateStatus(" 已上屏")
                                    isVoiceLocked = false
                                    resetToIdle()
                                }
                                "writing" -> {
                                    // 写作（中等级）：执行写作指令（含前缀），完成后——锁定态恢复监听
                                    val fullText = if (isContinuingSession && keptConv.isNotEmpty()) {
                                        "$keptConv $textNoCmd".trim()
                                    } else {
                                        textNoCmd
                                    }
                                    if (fullText.isEmpty()) {
                                        updateStatus("⚠️ 请输入写作内容")
                                        finishCommandResumeIfLocked()
                                    } else {
                                        Log.i("Cesia", "语音写作命令: '$fullText'")
                                        updateStatus("✨ 语音写作中...")
                                        val keptSnapshot = voiceKeptText
                                        // 延迟1秒执行，让用户看到状态提示
                                        CoroutineScope(Dispatchers.Main).launch {
                                            delay(1000)
                                            // 删除输入框中剩余的写作指令文字（本次会话新识别部分）
                                            val ic2 = currentInputConnection
                                            val newPart = if (keptSnapshot.isNotEmpty()) fullText.removePrefix(keptSnapshot).trimStart() else fullText
                                            if (ic2 != null && newPart.isNotEmpty()) {
                                                ic2.deleteSurroundingText(newPart.length, 0)
                                            }
                                            executeSmartCommand(fullText)
                                            // 写作完成后：锁定态恢复监听，否则退出
                                            finishCommandResumeIfLocked()
                                        }
                                    }
                                }
                                "undo" -> {
                                    // 撤销（低等级）：不结束下划线、不提交。
                                    // 把“已保留内容 + 本轮说的（去命令词）”拼成整体 combined，再删最后一句（空格断句）。
                                    // 关键：必须拼起来，单说“撤销”时 text 为空不能丢 voiceKeptText，
                                    // 同句说“第四 撤销”时 text 是“第四”不能丢 voiceKeptText 里已保留的“第一”，否则会把“第一”当整句删光。
                                    val combined = when {
                                        keptConv.isNotEmpty() && textConv.isNotEmpty() -> "$keptConv $textConv"
                                        textConv.isNotEmpty() -> textConv
                                        else -> keptConv
                                    }
                                    val base = combined.trimEnd()
                                    // 撤销前先把完整内容存入回收站，供“恢复”命令词还原
                                    if (base.isNotEmpty()) voiceUndoBackup = base
                                    // 从后往前遍历，遇到空格（=上一句起点）或到达顶端，删掉起点之后的所有内容（不含空格）。
                                    val idx = base.lastIndexOf(' ')   // -1 表示到达顶端（整段都是一句）
                                    val remaining = if (idx < 0) "" else base.substring(0, idx)  // 不含空格
                                    voiceKeptText = remaining
                                    if (remaining.isNotEmpty()) {
                                        // 还有上一句：保留为组合态（不提交）
                                        ic.setComposingText(remaining, 1)
                                        recognizedText = remaining
                                        updateStatus("↩️ 已撤销最近语段：$remaining")
                                    } else {
                                        // 已无上一句：清空组合态（用 setComposingText 而非 finish，避免“撤销”二字被提交上屏）
                                        ic.setComposingText("", 1)
                                        recognizedText = ""
                                        updateStatus("↩️ 已撤销全部")
                                    }
                                    isContinuingSession = true
                                    resumeRecordingKeepText()
                                }
                                "clear" -> {
                                    // 清空（低等级）：不结束下划线、不提交，直接清空组合区域（真相源一并清空）
                                    // 清空前把完整内容存入回收站，供“恢复”还原
                                    val combined = when {
                                        keptConv.isNotEmpty() && textConv.isNotEmpty() -> "$keptConv $textConv"
                                        textConv.isNotEmpty() -> textConv
                                        else -> keptConv
                                    }
                                    if (combined.isNotEmpty()) voiceUndoBackup = combined
                                    ic.setComposingText("", 1)
                                    voiceKeptText = ""
                                    recognizedText = ""
                                    updateStatus("🧹 已清空，继续识别")
                                    isContinuingSession = true
                                    resumeRecordingKeepText()
                                }
                                "restore" -> {
                                    // 恢复（低等级）：把最近一次撤销/清空删掉的内容还原到下划线，继续识别
                                    if (voiceUndoBackup.isNotEmpty()) {
                                        voiceKeptText = voiceUndoBackup
                                        recognizedText = voiceUndoBackup
                                        ic.setComposingText(voiceUndoBackup, 1)
                                        updateStatus("♻️ 已恢复：$voiceUndoBackup")
                                    } else {
                                        updateStatus("⚠️ 没有可恢复的内容")
                                    }
                                    isContinuingSession = true
                                    resumeRecordingKeepText()
                                }
                            }
                        }
                    }
                )
                Log.i("Cesia", "startWhisperRecordingAsync: recordInSegments returned, lastStreamingText='${lastStreamingText.take(50)}'")
            } catch (e: Throwable) {
                Log.e("Cesia", "Zipformer 录音失败", e)
                withContext(Dispatchers.Main) {
                    updateStatus("❌ 语音识别失败: ${e.javaClass.simpleName}: ${e.message}")
                    resetToIdle()
                }
            }
        }
    }

    /** 处理云端/本地识别结果 → 显示 AI+/AI× 按钮（锁定模式自动处理） */
    private fun handleCloudVoiceResult(rawText: String) {
        Log.i("Cesia", "handleCloudVoiceResult: text='${rawText.take(50)}', isRecording=$isRecording, recognizedText='${recognizedText.take(50)}', pendingAiMode=$pendingAiMode, isProcessingResult=$isProcessingResult, isVoiceLocked=$isVoiceLocked")
        // 语音识别结果统一转阿拉伯数字：本地 sherpa 路径已在 VoiceEngine 转过，
        // 但 Google 语音路径（FallbackRecognizer）不经过转换，这里兜底，
        // 保证“识别到什么、上屏就是什么”，不出现“先阿拉伯后又变回汉字”。
        val text = voiceEngine.convertChineseDigitsToArabic(rawText)
        // 已在点击 AI+/AI× 时处理过，跳过
        if (isProcessingResult) {
            Log.i("Cesia", "handleCloudVoiceResult: already processed, skipping")
            return
        }
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

        // 锁定模式：自动根据云按钮状态处理，不显示 AI+/AI× 按钮
        if (isVoiceLocked) {
            Log.i("Cesia", "handleCloudVoiceResult: 锁定模式，自动处理")
            isWaitingForChoice = false
            hideAiChoiceButtons()
            // 锁定模式下，根据云按钮状态决定润色或直接上屏
            if (isLocalPolishMode() && modelManager.hasAiModel()) {
                // 本地润色模式
                updateStatus("✨ 语音润色中...")
                setStatusDot("processing")
                isProcessingResult = true
                polishRecognizedText(text)
            } else if (isCloudPolishAvailable()) {
                // 云端润色模式
                updateStatus("☁️ 云端润色中...")
                setStatusDot("processing")
                isProcessingResult = true
                polishRecognizedText(text)
            } else {
                // 无润色服务 → 原文已上屏（finishComposingText），直接结束
                updateStatus(" 已上屏")
            }
            // 锁定模式下自动重新开始录音
            if (isVoiceLocked) {
                startRecordingLocked()
            }
            return
        }

        // 如果用户在录音过程中已点击 AI+/AI×，直接执行对应逻辑
        if (pendingAiMode != null) {
            val mode = pendingAiMode!!
            pendingAiMode = null
            isWaitingForChoice = false
            hideAiChoiceButtons()
            if (text.isEmpty()) {
                // 没有识别到文字，直接退出
                updateStatus("⚠️ 未识别到文字")
                resetToIdle()
                return
            }
            if (mode) {
                updateStatus("✨ 正在润色...")
                setStatusDot("processing")
                isProcessingResult = true
                polishRecognizedText(text)
            } else {
                // AI×：原文已上屏（finishComposingText），直接结束
                addSentMessage(text)
                resetToIdle()
            }
            return
        }

        // 否则显示 AI+/AI× 选择按钮等待用户点击
        isWaitingForChoice = true
        updateStatus("📝 「$text」→ 选择 AI+ 润色 或 AI× 直接上屏")
        micButton.visibility = View.GONE
        btnMicAi.visibility = View.VISIBLE
        btnMicNoAi.visibility = View.VISIBLE
    }

// endregion 同声传译

// region 录音选择后端
    // ======================== 录音（根据选择的后端） ========================

    private fun onAiPlusSelected() {
        if (isWaitingForChoice && recognizedText.isNotEmpty()) {
            isWaitingForChoice = false
            pendingAiMode = true
            hideAiChoiceButtons()
            updateStatus("✨ 正在润色...")
            setStatusDot("processing")
            isProcessingResult = true
            polishRecognizedText(recognizedText)
        } else if (isRecording) {
            // 说话过程中点击 AI+：立即停止录音，用当前识别文本润色
            val currentText = recognizedText
            stopRecordingAndWait()
            if (currentText.isNotEmpty()) {
                updateStatus("✨ 正在润色...")
                setStatusDot("processing")
                isProcessingResult = true
                polishRecognizedText(currentText)
            } else {
                // 没有识别到文字，直接退出语音状态
                updateStatus("⚠️ 未识别到文字")
                resetToIdle()
            }
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
            // 说话过程中点击 AI×：立即停止录音，用当前识别文本上屏
            val currentText = recognizedText
            stopRecordingAndWait()
            if (currentText.isNotEmpty()) {
                currentInputConnection?.commitText(currentText, 1)
                addSentMessage(currentText)
                resetToIdle()
            } else {
                // 没有识别到文字，直接退出语音状态
                updateStatus("⚠️ 未识别到文字")
                resetToIdle()
            }
        }
    }

    private fun stopRecordingAndWait() {
        isRecording = false
        stopVoiceWave()
        // 停止所有语音后端
        typelessEngine?.stopListening()
        // 释放 AudioRecord 让 readChunk 立即返回 null，退出 recordStreaming 循环
        voiceEngine.releaseRecorder()
        // 取消语音识别协程（确保协程立即结束）
        voiceEngineScope.coroutineContext.cancelChildren()
        setStatusDot("processing")
    }

    private fun polishRecognizedText(text: String) {
        isProcessingResult = true
        val useLocalPolish = isLocalPolishMode() && modelManager.hasAiModel()
        Log.d("Cesia", "polishRecognizedText: text='${text.take(50)}', useLocalPolish=$useLocalPolish, cloudMode=$cloudMode, isVoiceLocked=$isVoiceLocked")

        if (useLocalPolish) {
            Log.d("Cesia", "polishRecognizedText: 走本地润色 (MNN)")
            polishWithLocalAi(text)
        } else if (cloudMode == CloudMode.CLOUD) {
            // 云端润色（OpenRouter）
            Log.d("Cesia", "polishRecognizedText: 走云端润色 (OpenRouter)")
            polishWithCloud(text)
        } else {
            // LOCAL 模式但没有安装 MNN 模型 → 尝试 fallback 云端
            if (!modelManager.hasAiModel()) {
                Log.d("Cesia", "polishRecognizedText: 本地模型未安装，尝试云端 fallback")
                polishWithCloud(text)
            } else {
                Log.d("Cesia", "polishRecognizedText: 走本地润色 (MNN)")
                polishWithLocalAi(text)
            }
        }
    }

    /** 云端润色封装 */
    private fun polishWithCloud(text: String) {
        val grammarGuide = buildGrammarGuide()
        val enhancedText = if (grammarGuide.isNotEmpty()) {
            "$text\n\n[语法纲要]\n$grammarGuide"
        } else text
        typelessEngine?.polishTextAsync(enhancedText) { finalText ->
            Log.d("Cesia", "polishRecognizedText: 云端润色回调 finalText='${finalText.take(50)}'")
            isProcessingResult = false
            replaceTextWithPolish(text, finalText, aiUsed = true)
        } ?: run {
            Log.w("Cesia", "polishRecognizedText: typelessEngine 为 null，无法云端润色")
            isProcessingResult = false
            // 原文已上屏，直接结束
            if (isVoiceLocked) startRecordingLocked() else resetToIdle()
        }
    }

    /**
     * 替换光标处的原文为润色结果
     * 删除前面的原文，插入润色后的文本
     */
    private fun replaceTextWithPolish(originalText: String, polishedText: String, aiUsed: Boolean = false) {
        try {
            val ic = currentInputConnection ?: return
            // 删除原文（光标前面的 originalText.length 个字符）
            val deleteLen = originalText.length
            if (deleteLen > 0) {
                ic.deleteSurroundingText(deleteLen, 0)
            }
            // 插入润色结果
            ic.commitText(polishedText, 1)
        } catch (e: Exception) {
            Log.e("Cesia", "replaceTextWithPolish 失败，fallback commitText", e)
            try {
                val ic2 = currentInputConnection ?: return
                ic2.finishComposingText()
                ic2.commitText(polishedText, 1)
            } catch (_: Exception) {}
        }
        val duration = if (voiceStartTime > 0) System.currentTimeMillis() - voiceStartTime else 0
        // 语音历史：仅当历史记录模式开启（非 off）时写入；记录语音原文↔最终发出文字对比
        val historyMode = getSharedPreferences("cesia_polish_history", MODE_PRIVATE)
            .getString("history_mode", "off") ?: "off"
        if (historyMode != "off") {
            statsManager.addRecord(
                inputText = originalText,
                outputText = polishedText,
                voiceDurationMs = duration,
                voiceRawText = originalText,
                type = "voice"
            )
        }
        // 锁定模式下润色完成后自动重新开始录音
        if (isVoiceLocked) {
            startRecordingLocked()
        } else {
            resetToIdle()
        }
    }

    /** 本地 AI 润色 */
    private fun polishWithLocalAi(text: String) {
        // 使用独立 scope，防止被 voiceEngineScope.cancelChildren() 取消
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val modelFile = modelManager.getInstalledAiModelFile()
                Log.i("Cesia", "polishWithLocalAi: modelFile=$modelFile, exists=${modelFile?.exists()}, isDir=${modelFile?.isDirectory}")
                if (modelFile == null || !modelFile.exists()) {
                    withContext(Dispatchers.Main) {
                        updateStatus("⚠️ AI 模型未安装，使用原文")
                        isProcessingResult = false
                        if (isVoiceLocked) startRecordingLocked() else resetToIdle()
                    }
                    return@launch
                }
                if (!aiEngine.isModelLoaded()) {
                    val configPath = if (modelFile.isDirectory) {
                        File(modelFile, "config.json").absolutePath
                    } else {
                        modelFile.absolutePath
                    }
                    Log.i("Cesia", "polishWithLocalAi: loading model from $configPath")
                    val loadStart = System.currentTimeMillis()
                    val loaded = aiEngine.loadLocalModel(configPath)
                    val loadTime = System.currentTimeMillis() - loadStart
                    Log.i("Cesia", "polishWithLocalAi: loadLocalModel returned $loaded in ${loadTime}ms")
                    if (!loaded) {
                        val mnnLog = aiEngine.getMnnLog()
                        Log.e("Cesia", "polishWithLocalAi: MNN log: $mnnLog")
                        withContext(Dispatchers.Main) {
                            updateStatus("⚠️ AI 模型加载失败（${loadTime}ms），使用原文")
                            isProcessingResult = false
                            if (isVoiceLocked) startRecordingLocked() else resetToIdle()
                        }
                        return@launch
                    }
                }
                val result = aiEngine.polish(text, "润色")
                withContext(Dispatchers.Main) {
                    isProcessingResult = false
                    val finalText = result ?: text
                    replaceTextWithPolish(text, finalText, aiUsed = true)
                }
            } catch (e: Exception) {
                Log.e("Cesia", "本地润色失败", e)
                withContext(Dispatchers.Main) {
                    isProcessingResult = false
                    replaceTextWithPolish(text, text)
                }
            }
        }
    }

    private fun resetToIdle() {
        isRecording = false
        isWaitingForChoice = false
        isProcessingResult = false
        recognizedText = ""
        voiceKeptText = ""
        pendingAiMode = null
        stopVoiceWave()
        // 取消所有语音识别协程
        voiceEngineScope.coroutineContext.cancelChildren()
        resetMagicHighlight()
        // 清理清空按钮发光
        deleteButtonGlowRunnable?.let { deleteGlowHandler.removeCallbacks(it) }
        deleteButtonGlowRunnable = null
        stopDeleteButtonGlow()
        setStatusDot("idle")
        hideAiChoiceButtons()
        keyboardView.visibility = View.VISIBLE
        updateStatus(statusIdleText)
        pendingEnglish = ""
    }

    /**
     * 强制退出语音输入模式（用于：输入法切后台/来电）。
     * 与 resetToIdle 的区别：先把当前组合态内容 commit 上屏（避免切后台时系统丢弃 composing text 导致内容全失），
     * 再彻底清理语音状态。
     */
    private fun forceExitVoiceMode() {
        // 锁定态刚执行“发送”后，输入框 finish 会触发这里；此时不应解除锁定，
        // 由 send 分支的 finishCommandResumeIfLocked 重新进入监听接管。
        if (justSentWhileLocked) {
            justSentWhileLocked = false
            Log.i("Cesia", "forceExitVoiceMode: 跳过（刚在锁定态发送，由恢复监听接管）")
            return
        }
        if (!isRecording && recognizedText.isEmpty() && !isVoiceLocked) return
        Log.i("Cesia", "forceExitVoiceMode: 切后台/来电，退出语音模式，保留内容='${recognizedText.take(50)}'")
        // 1. 停录音、取消协程
        try {
            typelessEngine?.stopListening()
            voiceEngine.releaseRecorder()
            voiceEngineScope.coroutineContext.cancelChildren()
        } catch (_: Exception) {}
        // 2. 把保留内容落定上屏（组合态 → 已提交，切换窗口后不丢）
        try {
            val ic = currentInputConnection
            if (ic != null && voiceKeptText.isNotEmpty()) {
                // 先用 setComposingText 把组合区整体替换成真相源内容，再 finish 提交，避免重复上屏
                ic.setComposingText(voiceKeptText, 1)
                ic.finishComposingText()
            } else {
                ic?.finishComposingText()
            }
        } catch (_: Exception) {}
        // 3. 彻底重置状态
        isRecording = false
        isWaitingForChoice = false
        isProcessingResult = false
        isVoiceLocked = false
        isContinuingSession = false
        voiceKeptText = ""
        recognizedText = ""
        pendingAiMode = null
        stopVoiceWave()
        stopMicButtonGlow()
        resetMagicHighlight()
        setStatusDot("idle")
        hideAiChoiceButtons()
        keyboardView.visibility = View.VISIBLE
        updateMicButtonLockedState()
        updateStatus(statusIdleText)
    }

    /** 注册来电监听：来电时自动退出语音模式，避免录音卡死 */
    private fun registerPhoneStateListener() {
        if (phoneStateListener != null) return
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val tm = telephonyManager ?: return
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    super.onCallStateChanged(state, phoneNumber)
                    if (state == TelephonyManager.CALL_STATE_RINGING ||
                        state == TelephonyManager.CALL_STATE_OFFHOOK) {
                        Log.i("Cesia", "PhoneStateListener: 来电/接通，强制退出语音模式")
                        forceExitVoiceMode()
                    }
                }
            }
            @Suppress("DEPRECATION")
            tm.listen(phoneStateListener!!, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e("Cesia", "registerPhoneStateListener 失败", e)
        }
    }

    /**
     * 语音指令执行器
     * 用户说"XXX指令"后，XXX 作为自然语言指令传给 AI 理解执行
     * 不预设任何关键词，AI 自己理解用户意图
     */
    private fun executeVoiceCommand(commandText: String) {
        Log.i("Cesia", "executeVoiceCommand: commandText='$commandText'")

        val cmdLower = commandText.trim()

        // === 发送指令单独处理 ===
        if (cmdLower == "发送" || cmdLower == "发送指令" || cmdLower == "发送文字" || cmdLower == "发出") {
            updateStatus("📤 已发送")
            val editorInfo = currentInputEditorInfo
            val canSend = editorInfo != null &&
                (editorInfo.imeOptions and EditorInfo.IME_ACTION_SEND) != 0
            if (canSend) {
                currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            } else {
                Log.w("Cesia", "当前输入框不支持 IME_ACTION_SEND")
                updateStatus(" 已上屏（当前输入框不支持自动发送）")
            }
            // 锁定模式：发送后继续录音
            startRecordingLocked()
            return
        }

        // === 直接以魔法书 prompt 格式传给 AI ===
        val currentText = getInputText()
        Log.i("Cesia", "executeVoiceCommand: currentText='${currentText.take(80)}', length=${currentText.length}")

        if (currentText.isEmpty() && !isGenerationMagic(cmdLower)) {
            updateStatus("⚠️ 输入框没有文字，无法执行修改类指令")
            resetToIdle()
            return
        }

        updateStatus("✨ 执行指令中...")
        setStatusDot("processing")
        isProcessingResult = true

        // 尝试从 InstructionSet 匹配标准指令
        val matchedInstruction = com.cesia.input.instruction.InstructionSet.findByKeywords(cmdLower)
        val (prompt, recordName) = if (matchedInstruction != null) {
            // 匹配到标准指令：用标准化 prompt，记录指令名称
            val p = com.cesia.input.instruction.InstructionSet.buildPrompt(matchedInstruction, currentText)
            Log.i("Cesia", "executeVoiceCommand: 匹配到标准指令 '${matchedInstruction.name}'")
            Pair(p, matchedInstruction.name)
        } else {
            // 未匹配到：回退到简单 prompt
            val p = "$cmdLower：\n\n$currentText\n\n只输出结果："
            Pair(p, cmdLower)
        }
        Log.i("Cesia", "executeVoiceCommand: prompt='${prompt.take(80)}'")

        polishWithCommandPrompt(currentText, prompt, recordName)
    }

    /**
     * 用自定义 prompt 执行语音命令润色
     */
    private fun polishWithCommandPrompt(text: String, prompt: String, cmdLabel: String) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // 与智能写作(executeSmartCommand)一致的路由：本地模式且装了 MNN 模型才走本地，
                // 否则走云端 PolishService(OpenRouter)。修复：以前只看 hasAiModel()，
                // 而 Zipformer 语音识别模型也让 hasAiModel()=true，导致误走本地 aiEngine 卡死。
                val useLocal = isLocalPolishMode() && modelManager.hasAiModel()
                Log.d("Cesia", "polishWithCommandPrompt: useLocal=$useLocal, cloudMode=$cloudMode, hasAiModel=${modelManager.hasAiModel()}")
                val result = if (useLocal) {
                    val modelFile = modelManager.getInstalledAiModelFile()
                    if (modelFile != null && modelFile.exists() && !aiEngine.isModelLoaded()) {
                        val configPath = if (modelFile.isDirectory) File(modelFile, "config.json").absolutePath else modelFile.absolutePath
                        aiEngine.loadLocalModel(configPath)
                    }
                    aiEngine.polishWithPrompt(prompt)
                } else {
                    val polishService = typelessEngine?.getPolishService()
                    Log.d("Cesia", "polishWithCommandPrompt: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(50) ?: "null"}")
                    polishService?.polishWithPrompt(prompt)
                }
                Log.d("Cesia", "polishWithCommandPrompt: result=${result?.take(80) ?: "NULL"}")

                withContext(Dispatchers.Main) {
                    isProcessingResult = false
                    if (result != null) {
                        val cleaned = cleanCommandResult(result)
                        val ic = currentInputConnection ?: return@withContext
                        // 检查是否有选区
                        val selectedText = ic.getSelectedText(0)
                        if (selectedText != null && selectedText.isNotEmpty()) {
                            // 有选区：直接替换选区文字
                            ic.commitText(cleaned, 1)
                        } else {
                            // 无选区：清空全文后写入
                            val before = ic.getTextBeforeCursor(64, 0)?.length ?: 0
                            val after = ic.getTextAfterCursor(64, 0)?.length ?: 0
                            if (before > 0 || after > 0) {
                                ic.deleteSurroundingText(before, after)
                            }
                            ic.commitText(cleaned, 1)
                        }
                        updateStatus(" 已执行：$cmdLabel")
                        // 将指令加入魔法书历史第1位
                        magicHistoryManager?.addRecord(cmdLabel)
                    } else {
                        updateStatus("⚠️ 执行失败，已保留原文")
                    }
                    // 锁定模式下恢复录音，否则重置
                    if (isVoiceLocked) {
                        startRecordingLocked()
                    } else {
                        resetToIdle()
                    }
                }
            } catch (e: Exception) {
                Log.e("Cesia", "语音命令执行失败", e)
                withContext(Dispatchers.Main) {
                    isProcessingResult = false
                    // 异常时恢复原文
                    val ic = currentInputConnection ?: return@withContext
                    val selectedText = ic.getSelectedText(0)
                    if (selectedText != null && selectedText.isNotEmpty()) {
                        // 有选区：恢复选区文字
                        ic.commitText(text, 1)
                    } else {
                        // 无选区：恢复全文
                        val before = ic.getTextBeforeCursor(64, 0)?.length ?: 0
                        val after = ic.getTextAfterCursor(64, 0)?.length ?: 0
                        if (before > 0 || after > 0) {
                            ic.deleteSurroundingText(before, after)
                        }
                        ic.commitText(text, 1)
                    }
                    updateStatus("⚠️ 执行失败，已恢复原文")
                    if (isVoiceLocked) {
                        startRecordingLocked()
                    } else {
                        resetToIdle()
                    }
                }
            }
        }
    }

    /**
     * 语音命令结果后处理
     * 去掉 AI 可能输出的前缀标签和多余内容
     */
    private fun cleanCommandResult(raw: String): String {
        var text = raw.trim()

        // 1. 去掉常见前缀
        val prefixes = listOf(
            "输出：", "输出:", "结果：", "结果:",
            "处理后：", "处理后:", "处理结果：", "处理结果:",
            "翻译：", "翻译:", "译文：", "译文:",
            "润色后：", "润色后:", "润色结果：",
            "简化后：", "简化后:", "摘要：", "摘要:",
            "以下是处理后的文本：", "处理后的文本：",
            "根据您的要求，"
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }

        // 2. 如果 AI 重复了"输出："之后的内容，取第一段有意义的文本
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size > 1) {
            // 找到第一个非空且不是标签的行
            for (line in lines) {
                val trimmed = line.trim()
                if (!trimmed.endsWith("：") && !trimmed.endsWith(":") &&
                    !trimmed.startsWith("任务") && !trimmed.startsWith("规则") &&
                    !trimmed.startsWith("输入文本")) {
                    return trimmed
                }
            }
        }

        return text
    }

    private fun stopRecording() {
        stopRecordingAndWait()
    }

    /** 获取当前输入框中的全部文字 */
    private fun getInputText(): String {
        return try {
            val ic = currentInputConnection ?: return ""
            val before = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
            val after = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
            before + after
        } catch (e: Exception) {
            Log.e("Cesia", "getInputText 失败", e)
            ""
        }
    }

    /** 清空输入框中的全部文字 */
    private fun clearInputText() {
        try {
            val ic = currentInputConnection ?: return
            val before = ic.getTextBeforeCursor(64, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(64, 0)?.length ?: 0
            if (before > 0 || after > 0) {
                ic.deleteSurroundingText(before, after)
            }
        } catch (e: Exception) {
            Log.e("Cesia", "clearInputText 失败", e)
        }
    }

    /** 替换输入框中的全部文字 */
    private fun replaceInputText(oldText: String, newText: String) {
        try {
            val ic = currentInputConnection ?: return
            // 先选中全部文字
            val before = ic.getTextBeforeCursor(64, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(64, 0)?.length ?: 0
            if (before > 0 || after > 0) {
                ic.setSelection(0, 0)  // 移到开头
                // 选中到末尾
                ic.setSelection(0, 0)
            }
            ic.deleteSurroundingText(before, after)
            ic.commitText(newText, 1)
        } catch (e: Exception) {
            Log.e("Cesia", "replaceInputText 失败", e)
        }
    }

    private fun addSentMessage(text: String) {
        Log.i("Cesia", "addSentMessage: text='${text.take(40)}', voiceKeptText='${voiceKeptText.take(40)}', isRecording=$isRecording")
        if (text.isBlank()) return
        sentMessages.add(text)
        if (sentMessages.size > maxSentMessages) {
            sentMessages.removeAt(0)
        }
    }

// endregion 录音选择后端

// region 声波动画
    // ======================== 声波动画 ========================

    private var waveAnim: AnimationDrawable? = null

    private fun startVoiceWave() {
        try {
            voiceWave.visibility = View.VISIBLE
            // 创建跟随主题色的声波动画
            val color = themeAccent
            val frames = arrayOf(
                createWaveFrame(color, 0.25f, 48),
                createWaveFrame(color, 0.4f, 56),
                createWaveFrame(color, 0.55f, 64),
                createWaveFrame(color, 0.4f, 56)
            )
            val anim = AnimationDrawable().apply {
                frames.forEach { frame ->
                    addFrame(frame, 250)
                }
                isOneShot = false
            }
            voiceWave.background = anim
            anim.start()
            waveAnim = anim

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

    private fun createWaveFrame(color: Int, alpha: Float, size: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((color and 0xFFFFFF) or ((color * alpha).toInt() shl 24))
            setSize(size, size)
        }
    }

    private fun stopVoiceWave() {
        try {
            waveAnim?.stop()
            waveAnim = null
            voiceWave.clearAnimation()
            voiceWave.visibility = View.GONE
        } catch (_: Exception) {}
    }

// endregion 声波动画

// region 麦克风动画
    // ======================== 麦克风按钮动画 ========================

    private fun animateMicSplit() {
        try {
            micButton.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(200).withEndAction {
                micButton.visibility = View.GONE
                // 隐藏包裹层（含“中”标记），恢复原始双按钮布局：[AI+][AI×] 各占 1/2
                micWrapper.visibility = View.GONE
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
                micWrapper.visibility = View.VISIBLE
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
        t9DigitQueue.clear(); t9SpellPrefix.clear()
        candidateBar.visibility = View.GONE
        updateStatus(statusIdleText)
        // UI 立即切换，schema 切换放后台（轻量 reload，保留 build 缓存）
        // 使用单线程 Executor 串行执行，防止多线程并发操作 Rime 引擎导致崩溃
        if (keyboardMode == KeyboardMode.NUMBER) {
            switchToKeyboard(KeyboardMode.QWERTY)
            rimeExecutor.execute {
                rimeEngine.selectSchema("pinyin")
                rimeEngine.reload()
            }
        } else {
            switchToKeyboard(KeyboardMode.NUMBER)
            rimeExecutor.execute {
                rimeEngine.selectSchema("t9_pinyin")
                rimeEngine.reload()
                Handler(Looper.getMainLooper()).post { resetNumberKeyboardState() }
            }
        }
    }

    private fun switchToKeyboard(mode: KeyboardMode) {
        // 切换键盘时退出魔法编辑模式和智能写作编辑模式
        if (magicEditMode) exitMagicEditMode(save = false)
        if (smartEditMode) exitSmartEditMode(save = false)
        if (clipboardAddMode) exitClipboardAddMode(save = false)
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
            // 恢复 1 键分词开关文字（保留用户切全拼前的状态）
            keyboardView.t9FenCiLabel = if (t9FenCiOn) "简拼" else "全拼"
        } else if (mode == KeyboardMode.QWERTY) {
            // 进入全键盘：恢复 QWERTY shift 状态
            isAsciiMode = qwertyShiftLocked || qwertyShiftTemp
            rimeEngine.setAsciiMode(isAsciiMode)
            // 根据 shift 状态恢复对应方案
            if (qwertyShiftLocked || qwertyShiftTemp) {
                rimeEngine.selectSchema("en")
            } else {
                rimeEngine.selectSchema("pinyin")
            }
            rimeEngine.clear()
            // 清理 T9 状态，避免泄漏到全键盘（残留候选/数字队列/简拼合并列表）
            t9DigitQueue.clear()
            t9SpellPrefix.clear()
            t9FenCiMerged = emptyList()
            t9ComposedSoFar.clear()
            keyboardView.t9FenCiLabel = "全拼"  // 复位（仅 T9 模式绘制，全键盘不显示）
            candidateBar.visibility = View.GONE
            updateStatus(statusIdleText)
        } else {
            // 进入符号键盘：只操作符号相关状态
            isAsciiMode = false
            rimeEngine.setAsciiMode(false)
            rimeEngine.clear()
            t9InputBuffer.clear()
            // symbolShiftLocked 保留
            candidateBar.visibility = View.GONE
            updateStatus(statusIdleText)
        }
        updateShiftIndicator()
        keyboardView.invalidateAllKeys()
        // 符号键盘增加左右3px边距
        val paddingPx = if (mode == KeyboardMode.SYMBOL_CN || mode == KeyboardMode.SYMBOL_EN) {
            (1.5f * resources.displayMetrics.density).toInt() // ≈3px on xhdpi
        } else 0
        keyboardView.setPadding(paddingPx, 0, paddingPx, 0)
        // 切换到非 T9 模式（全键盘/符号）时，候选栏字母点选区同步消失
        if (mode != KeyboardMode.NUMBER) {
            llT9Spell?.visibility = android.view.View.GONE
        } else {
            updateSpellBar()
        }
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
                rimeEngine.selectSchema("en")
            }
        } else {
            // QWERTY → T9：切换 schema 到 t9_pinyin
            switchToKeyboard(KeyboardMode.NUMBER)
            rimeEngine.selectSchema("t9_pinyin")
            rimeEngine.reload()
            resetNumberKeyboardState()
        }
    }

// endregion 麦克风动画

// region 数字键盘
    // ======================== 数字键盘核心逻辑 ========================

    private fun resetT9State() {
        t9InputBuffer.clear()
        t9DigitQueue.clear(); t9SpellPrefix.clear()
        t9FenCiMerged = emptyList()   // 清空简拼合并候选，避免上屏/退格后残留
        rimeEngine.clear()
        lastT9Feed = null             // 重置增量喂标记：上屏/清状态后下次输入从头喂
        t9ConsumedLen = 0             // 重置接龙消费位置
        t9ShiftTemp = false
        // qwertyShiftLocked 不在此处清除，各键盘状态独立
        updateCandidateBar()
        updateSpellBar()               // 同步隐藏候选音区
        updateStatus(statusIdleText)
    }

    // 数字键盘长按通过 popupCharacters 走 startLongPressDetection

    private fun resetNumberKeyboardState() {
        t9ShiftTemp = false
        // qwertyShiftLocked 和 t9ShiftLocked 不在此处清除，各键盘状态独立
        t9InputBuffer.clear()
        t9DigitQueue.clear(); t9SpellPrefix.clear()
        updateShiftIndicator()
    }

    /** 连续按键达上限(25)提示：用 PopupWindow（IME 环境不能用 AlertDialog），居中显示 2 秒后自动消失 */
    private fun showT9KeyLimitPopup() {
        val ctx = this
        val tv = android.widget.TextView(ctx).apply {
            text = "已达到连续输入上限（25 键）\n退格删除后可继续输入"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(40, 28, 40, 28)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6000000.toInt())
                cornerRadius = 16f
            }
        }
        val w = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        val popup = PopupWindow(tv, w, w, false).apply {
            isOutsideTouchable = true
            isFocusable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        val root = keyboardView ?: return
        tv.setOnClickListener { popup.dismiss() }
        try {
            popup.showAtLocation(root, android.view.Gravity.CENTER, 0, 0)
            tv.postDelayed({ if (popup.isShowing) popup.dismiss() }, 2000)
        } catch (_: Exception) { }
    }

    /** 退格轻量重放：clear+重喂当前队列，同步 Rime composingText（修 Bug2 状态栏残留 1 字），
     *  但只更新状态栏、不刷新候选栏 RecyclerView，避免连续退格主线程堆积卡顿 */
    private fun replayT9Quiet() {
        if (t9DigitQueue.isEmpty() && t9SpellPrefix.isEmpty()) {
            rimeEngine.clear()
            resetT9State()
            return
        }
        rimeEngine.clear(); rimeEngine.createSession()
        val feed = if (t9FenCiOn) buildT9SpellFeed() else t9DigitQueue.toString()
        for (ch in feed) rimeEngine.processKey(ch.toString())
        lastT9Feed = feed
        // 同步状态栏 + 候选栏（退格后候选词随数字队列变化而刷新，修复状态栏退但候选栏不变）
        updateStatus(t9SpellPrefix.toString() + t9DigitQueue.substring(t9SpellPrefix.length))
        updateCandidateBar()
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
                // 锁定状态 → 解除锁定，切回中文方案
                qwertyShiftLocked = false
                qwertyShiftTemp = false
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
                rimeEngine.selectSchema("pinyin")
                rimeEngine.clear()
            } else if (qwertyShiftTemp) {
                // 临时shift → 退回正常，切回中文方案
                qwertyShiftTemp = false
                isAsciiMode = false
                rimeEngine.setAsciiMode(false)
                rimeEngine.selectSchema("pinyin")
                rimeEngine.clear()
            } else {
                // 正常 → 单击临时shift，切换到英文方案
                qwertyShiftTemp = true
                isAsciiMode = true
                rimeEngine.setAsciiMode(true)
                rimeEngine.selectSchema("en")
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
            // 全键盘：锁定大写，切换到英文方案
            qwertyShiftLocked = true
            qwertyShiftTemp = false
            isAsciiMode = true
            rimeEngine.setAsciiMode(true)
            // 切换到英文方案
            rimeEngine.selectSchema("en")
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
            // QWERTY 模式下切回中文方案
            if (keyboardMode == KeyboardMode.QWERTY) {
                rimeEngine.selectSchema("pinyin")
            }
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
                // 连续按键数上限：到达 25 弹出提示（PopupWindow，IME 环境不能用 AlertDialog），阻止继续累积（退格删键后可继续）
                if (t9DigitQueue.length >= MAX_T9_KEYS) {
                    showT9KeyLimitPopup()
                    return
                }
                t9DigitQueue.append(t9Digit)  // 逐键选音：数字进队列，字母通过覆盖层锁定
                processT9Input()
            } else {
                when (primaryCode) {
                    49 -> {
                        onFenciKeyClick()
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
        val t0 = System.currentTimeMillis()
        // 接龙态(t9ConsumedLen>0)不清已组词缓冲；非接龙才清（新组合开始）
        if (t9ConsumedLen == 0) t9ComposedSoFar.clear()
        // 分词开关：开=简拼（数字间加 ' 分词符），关=全拼（数字直连）
        if (t9DigitQueue.isNotEmpty()) {
            if (t9DigitQueue.length == 1 && t9ConsumedLen == 0) {
                // 单键单字：枚举该键所有字母(a/b/c)取单字候选合并，覆盖全部开头单字。
                // 无论简拼/全拼模式，单键只出单字(不出词组)；跟随选音锁定字母变化(锁定某字母则只该字母)。
                val d = t9DigitQueue[0].digitToInt()
                t9SingleKeyCands = enumSingleKeyCands(d)
                t9FenCiMerged = emptyList()
                // 单键选音(t9SpellPrefix)只对单键有效；进入多键时由下面 else 分支统一处理，这里不写 lastT9Feed
                // 关键：单键枚举不碰 Rime 会话状态，避免污染多键路径(问题2：双数字首屏空白)
            } else {
                if (t9FenCiOn) {
                // 简拼：已锁定字母(t9SpellPrefix) + 剩余位数字各取首字母 组成简拼码喂 Rime
                // 接龙态(t9ConsumedLen>0)下只取「未消费后缀」算简拼码，否则候选仍是整串、接龙失败
                val digits = t9DigitQueue.substring(t9ConsumedLen).map { it.digitToInt() }
                val feed = if (t9SpellPrefix.isEmpty()) {
                    // 未锁定时：每数字取首字母组成一个代表简拼码(如 77777→ppppp)喂 Rime，出一组合适候选
                    val firstLetters = digits.map { (t9Map[it] ?: "").filter { c -> c != ' ' }.firstOrNull() ?: ' ' }
                    firstLetters.joinToString("")
                } else {
                    // 已锁定时：用锁定字母+剩余首位拼简拼码(如 bf)，精确出该范围候选
                    buildT9SpellFeed()
                }
                val tFeed = System.currentTimeMillis()
                feedRimeIncrementally(feed)
                val tGet = System.currentTimeMillis()
                t9FenCiMerged = rimeEngine.getAllCandidates(candPageWalk)
                val tEnd = System.currentTimeMillis()
                Log.i("CesiaPerf", "processT9Input 简拼 qlen=${t9DigitQueue.length} feed=$feed | feedRime=${tGet-tFeed}ms getAll=${tEnd-tGet}ms total=${tEnd-t0}ms")
            } else {
                // 全拼：数字直连增量喂（feedRimeIncrementally），O(1) 不卡。
                // 单键枚举已把会话恢复成「数字 digit 的 t9 模糊态」并同步 lastT9Feed=数字串，
                // 故增量时只喂新增数字，长码不再整串重放；会话态与数字串一致 → 23 出 ce（非 a3）。
                // 接龙态只取未消费后缀喂入（t9ConsumedLen）。
                val remaining = t9DigitQueue.substring(t9ConsumedLen)
                val tFeed = System.currentTimeMillis()
                feedRimeIncrementally(remaining)
                val tGet = System.currentTimeMillis()
                rimeEngine.getAllCandidates(candPageWalk)
                val tEnd = System.currentTimeMillis()
                Log.i("CesiaPerf", "processT9Input 全拼 qlen=${t9DigitQueue.length} | feedRime=${tGet-tFeed}ms getAll=${tEnd-tGet}ms total=${tEnd-t0}ms")
            }
        }   // 关闭多键(else)分支
        } else {   // 队列空：确保 Rime 会话清空，下次从头喂
            // 队列空：确保 Rime 会话清空，下次从头喂
            rimeEngine.clear(); rimeEngine.createSession()
            lastT9Feed = null
        }
        val tUpd = System.currentTimeMillis()
        updateSpellBar()
        updateCandidateBar()
        val tEnd = System.currentTimeMillis()
        Log.i("CesiaPerf", "processT9Input 后续update=${tEnd-tUpd}ms | qlen=${t9DigitQueue.length}")
    }

    /**
     * 增量喂 Rime：若新 feed 以上次喂入的 lastT9Feed 为前缀（即仅追加、未退格/切模式/提交），
     * 则只把新增部分 processKey 进去；否则清空会话整串重放。
     * 关键修复：不再每键 clear+重放“整个数字队列”，长码（如连续拼接多词组）下每键开销从 O(队列长) 降到 O(1)，
     * 累计从 O(n²) 降到 O(n)，根治“连续输入中文超 4 字后卡顿甚至卡死”。
     */
    private fun feedRimeIncrementally(feed: String) {
        val prev = lastT9Feed
        if (prev != null && feed.length > prev.length && feed.startsWith(prev)) {
            // 增量：仅喂新增的尾部字符。逐字符检查 processKey 返回值——
            // Rime 对超长码会偶尔拒收/重置会话，若某字符喂入失败立即放弃增量、整串重放，
            // 此时 feed 仅比 prev 多1位，重放开销极小，避免「会话态与 lastT9Feed 漂移」
            // 累积到长码（qlen17/20）才爆发整串重放导致卡顿。
            var ok = true
            for (i in prev.length until feed.length) {
                if (!rimeEngine.processKey(feed[i].toString())) { ok = false; break }
            }
            if (ok) {
                lastT9Feed = feed
            } else {
                rimeEngine.clear(); rimeEngine.createSession()
                for (ch in feed) {
                    rimeEngine.processKey(ch.toString())
                }
                lastT9Feed = feed
            }
        } else if (prev != null && feed.length < prev.length && prev.startsWith(feed)) {
            // 退格：feed 是上次的严格前缀 → 用 BackSpace 增量回退 Rime 组合（避免整串重喂，退格卡顿根因）
            val removed = prev.length - feed.length
            repeat(removed) {
                if (!rimeEngine.processKey("BackSpace")) return@repeat
            }
            lastT9Feed = feed
        } else {
            // 重放：清空会话，从头喂完整 feed
            rimeEngine.clear(); rimeEngine.createSession()
            for (ch in feed) {
                rimeEngine.processKey(ch.toString())
            }
            lastT9Feed = feed
        }
    }

    /** 接龙组词：将候选拼音(如 "dajia")反推成 T9 数字长度(3+2+5+4+2=5)。
     *  逐字母查 t9Map 反映射（d→3,a→2,j→5,i→4,a→2），跳过空格/分词符。仅全拼用。 */
    private fun pinyinToDigitLen(py: String): Int {
        if (py.isEmpty()) return 0
        var n = 0
        for (ch in py.lowercase()) {
            if (ch == ' ' || ch == '\'' || ch == '·') continue
            val key = t9Map.entries.firstOrNull { it.value.contains(ch) }?.key
            if (key != null) n++
        }
        return n
    }

    /** 接龙组词：用「未消费后缀」重新喂 Rime，刷新候选栏为剩余部分候选 */
    private fun feedRemaining() {
        // 清空 Rime 会话，让 processT9Input 按 t9FenCiOn 重新走全拼/简拼剩余分支
        // （简拼需 substring(t9ConsumedLen) 算剩余首字母 feed，不能直喂数字串）
        rimeEngine.clear(); rimeEngine.createSession()
        lastT9Feed = null
        processT9Input()
    }

    /** 1 键：单击=切换全拼/简拼（并提示）；双击=锁定/解锁当前模式（防误触，持久化） */
    private fun onFenciKeyClick() {
        val now = System.currentTimeMillis()
        val isDouble = now - t9FenCiLastClick < 350
        t9FenCiLastClick = now
        if (isDouble) {
            // 双击：锁定/解锁。取消待执行的单击切换，改为切换锁状态
            t9FenCiPendingSingle?.let { fenciHandler.removeCallbacks(it) }
            t9FenCiPendingSingle = null
            t9FenCiLock = !t9FenCiLock
            keyboardView.t9FenCiLock = t9FenCiLock
            getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
                .putBoolean("t9_fenci_lock", t9FenCiLock).apply()
            updateStatus(if (t9FenCiLock) "已锁定（${if (t9FenCiOn) "简拼" else "全拼"}）·双击解锁" else "已解锁·双击锁定")
            keyboardView.invalidateAllKeys()
            return
        }
        // 锁定状态下：单击不切换全拼/简拼（防误触），仅提示
        if (t9FenCiLock) {
            updateStatus("已锁定·双击解锁")
            return
        }
        // 单击：延迟 350ms 执行切换，给双击留出判定窗口
        val pending = Runnable { doFenciToggle() }
        t9FenCiPendingSingle = pending
        fenciHandler.postDelayed(pending, 350)
    }

    /** 真正执行全拼/简拼切换（单击触发） */
    private fun doFenciToggle() {
        t9FenCiPendingSingle = null
        toggleT9FenCi()
        updateStatus("双击锁定/解锁，单击切换")
    }

    /** 1 键：分词开关切换（默认开=简拼，点一下关=全拼，自由切换） */
    private fun toggleT9FenCi() {
        t9FenCiOn = !t9FenCiOn
        // 同步 1 键文字（参考全选键样式，由 CesiaKeyboardView 绘制）
        keyboardView.t9FenCiLabel = if (t9FenCiOn) "简拼" else "全拼"
        // 切换模式时清选音锁定（两种模式锁定含义不同，避免串扰）
        t9SpellPrefix.clear()
        // 切换时重算候选（保留已输入数字串，只改分词符有无）
        if (t9DigitQueue.isNotEmpty()) {
            processT9Input()
        }
        updateStatus(if (t9FenCiOn) "简拼模式（分词开）" else "全拼模式（分词关）")
    }

    // 单键单字：枚举该数字键所有字母(a/b/c)，复用当前会话逐个 clear+喂取单字(length==1)合并去重
    // 覆盖该键全部开头单字，而非只首字母一个；且只出单字不出词组。
    // 已选音锁定某字母(t9SpellPrefix 单字母)时只取该字母单字，候选随选音变化。
    // 重要：枚举结束后把 Rime 会话恢复成「单键应有的首字母状态」并同步 lastT9Feed，
    // 避免污染后续多键路径(问题2：双数字首屏空白)。
    private fun enumSingleKeyCands(digit: Int): List<String> {
        val letters = (t9Map[digit] ?: "").filter { it != ' ' }
        if (letters.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        if (t9SpellPrefix.length == 1) {
            // 已锁定某字母：只出该字母单字
            rimeEngine.clear()
            rimeEngine.processKey(t9SpellPrefix.toString())
            out.addAll(rimeEngine.getAllCandidates(candPageWalk).filter { it.length == 1 })
        } else {
            // 未锁定：枚举该键每个字母，逐个取单字合并
            for (ch in letters) {
                rimeEngine.clear()
                rimeEngine.processKey(ch.toString())
                out.addAll(rimeEngine.getAllCandidates(candPageWalk).filter { it.length == 1 })
            }
        }
        // 恢复会话到「单键数字 digit 的 t9 模糊态」：喂数字字符（而非字母），
        // 保持与全拼增量 feedRimeIncrementally 的 lastT9Feed(数字串)一致，
        // 后续按第2个数字走全拼增量时，会话态=“2”的模糊拼音、增量喂“3”得“23”整体态（出 ce 而非 a3）。
        rimeEngine.clear()
        rimeEngine.processKey(digit.toString())
        lastT9Feed = t9DigitQueue.toString()
        return out.toList()
    }

     /** 拼纯字母串喂 Rime：已选字母前缀 + 剩余位数字各取首字母占位（如 prefix=ws, queue=97 剩7→取p → wsp） */
    private fun buildT9SpellFeed(): String {
        if (t9DigitQueue.isEmpty()) return ""
        val sb = StringBuilder(t9SpellPrefix)
        val remaining = t9DigitQueue.drop(t9SpellPrefix.length)
        for (d in remaining) {
            val letters = t9Map[d.digitToInt()] ?: " "
            sb.append(letters.firstOrNull() ?: ' ')
        }
        return sb.toString()
    }

    /** 按已选字母前缀(拼音首字母)过滤候选词列表，返回过滤后的子集（候选拼音首字母以 prefix 开头）。
     *  pinyins 与 cands 顺序一一对应。过滤结果为空时返回原列表（避免误清空）。 */
    // 拼音首字母分词正则（常量，只编译一次，避免 filterCandsBySpellPrefix 每次按键重复编译）
    private val SPELL_SPLIT_REGEX = Regex("[\\s'·]")
    // 语音命令词剥离正则（常量，避免每次语音结果重复编译）
    private val COMMAND_STRIP_REGEX = Regex("(续写|扩写|改写|润色|翻译|写作|修改|帮我写|帮我改|帮我润色)")
    // 剪贴板分词正则（常量，避免每条剪贴板项渲染时重复编译）
    private val CLIPBOARD_SPLIT_REGEX = Regex("""[\s,，。；;:：！!？?、]+""")

    private fun filterCandsBySpellPrefix(cands: List<String>, pinyins: List<String>, prefix: String): List<String> {
        if (prefix.isEmpty()) return cands
        val filtered = cands.mapIndexedNotNull { i, cand ->
            val py = pinyins.getOrElse(i) { "" }
            val initials = py.split(SPELL_SPLIT_REGEX).filter { it.isNotEmpty() }
                .joinToString("") { it.first().toString() }
            if (initials.startsWith(prefix)) cand else null
        }
        return if (filtered.isNotEmpty()) filtered else cands
    }

    /** 简易四则运算求值（复刻 Rime =expr）：支持 + - * / % ^ 及括号、小数、负数。
     *  返回格式化结果字符串；解析失败返回 null。 */
    private fun evalMath(expr: String): String? {
        val e = expr.trim()
        if (e.isEmpty()) return null
        // 允许的字符
        if (!e.all { it.isDigit() || it in "+-*/%^(). " }) return null
        try {
            calcSrc = e.replace(" ", "")
            calcPos = 0
            val result = evalExpr()
            if (calcPos != calcSrc.length) return null  // 有未解析尾巴
            if (result.isNaN() || result.isInfinite()) return null
            // 去掉多余小数 0：整数显示整数，否则最多 6 位
            val s = if (result == result.toLong().toDouble()) result.toLong().toString()
            else String.format("%.6f", result).trimEnd('0').trimEnd('.')
            return s
        } catch (_: Exception) {
            return null
        }
    }

    // 递归下降求值（类级私有函数，避免局部函数前向引用限制）
    private var calcPos = 0
    private var calcSrc = ""
    private fun evalExpr(): Double {
        var v = parseExpr()
        return v
    }
    private fun parseExpr(): Double {
        var v = parseTerm()
        while (calcPos < calcSrc.length && (calcSrc[calcPos] == '+' || calcSrc[calcPos] == '-')) {
            val op = calcSrc[calcPos++]; val r = parseTerm()
            v = if (op == '+') v + r else v - r
        }
        return v
    }
    private fun parseTerm(): Double {
        var v = parseFactor()
        while (calcPos < calcSrc.length && (calcSrc[calcPos] == '*' || calcSrc[calcPos] == '/' || calcSrc[calcPos] == '%')) {
            val op = calcSrc[calcPos++]; val r = parseFactor()
            v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
        }
        return v
    }
    private fun parseFactor(): Double {
        var v = parseBase()
        while (calcPos < calcSrc.length && calcSrc[calcPos] == '^') { calcPos++; v = Math.pow(v, parseFactor()) }
        return v
    }
    private fun parseBase(): Double {
        if (calcPos < calcSrc.length && calcSrc[calcPos] == '-') { calcPos++; return -parseBase() }
        if (calcPos < calcSrc.length && calcSrc[calcPos] == '+') { calcPos++; return parseBase() }
        if (calcPos < calcSrc.length && calcSrc[calcPos] == '(') {
            calcPos++; val v = parseExpr(); if (calcPos < calcSrc.length && calcSrc[calcPos] == ')') calcPos++; return v
        }
        val start = calcPos
        while (calcPos < calcSrc.length && (calcSrc[calcPos].isDigit() || calcSrc[calcPos] == '.')) calcPos++
        if (start == calcPos) throw RuntimeException("bad expr")
        return calcSrc.substring(start, calcPos).toDouble()
    }

    /** 逐键选音：点击候选栏字母区第 letterIndex 个字母（如 9→wxyz 的第0个 w） */
    private fun onT9SpellLetterClick(letterIndex: Int) {
        Log.d("Cesia", "spellClick in: letter=$letterIndex pre='$t9SpellPrefix' queue='$t9DigitQueue' consumed=$t9ConsumedLen")
        if (t9SpellPrefix.length >= t9DigitQueue.length) return
        val curDigit = t9DigitQueue[t9SpellPrefix.length]
        val letters = t9Map[curDigit.digitToInt()] ?: return
        if (letterIndex >= letters.length) return
        t9SpellPrefix.append(letters[letterIndex])  // 锁定该位字母
        Log.d("Cesia", "spellClick out: pre='$t9SpellPrefix'")
        processT9Input()                             // 重算（实时收窄候选）
        // 强制刷新状态栏选音进度（复用正常显示逻辑）：避免 sig 去重跳过导致 236 等组合仍显示数字
        if (keyboardMode == KeyboardMode.NUMBER) {
            val skip = t9ConsumedLen.coerceAtMost(t9DigitQueue.length)
            val from = (skip + t9SpellPrefix.length).coerceAtMost(t9DigitQueue.length)
            val remaining = if (from < t9DigitQueue.length) t9DigitQueue.substring(from) else ""
            updateStatus(t9SpellPrefix.toString() + remaining)
        }
    }

    /** 刷新候选音：驱动候选栏最左 4 字母点选区。
     *  全选完 / 候选区空 / 非T9模式 时同步隐藏。 */
    private fun updateSpellBar() {
        val showSpell = keyboardMode == KeyboardMode.NUMBER
                && t9SpellPrefix.length < t9DigitQueue.length
                && rimeEngine.hasCandidates
        val spellZone = llT9Spell
        val spellTVs = t9SpellTVs
        if (spellZone == null || spellTVs == null || !showSpell) {
            spellZone?.visibility = android.view.View.GONE
            return
        }
        spellZone.visibility = android.view.View.VISIBLE
        val curDigit = t9DigitQueue[t9SpellPrefix.length]
        val letters = t9Map[curDigit.digitToInt()] ?: ""
        for (i in 0..3) {
            val tv = spellTVs.getOrNull(i) ?: continue
            if (i < letters.length) {
                tv.visibility = android.view.View.VISIBLE
                tv.text = letters[i].toString()
            } else {
                tv.visibility = android.view.View.GONE
            }
        }
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
            t9DigitQueue.clear(); t9SpellPrefix.clear()
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
            updateStatus(statusIdleText)
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

// endregion 数字键盘

// region 长按检测
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
                // 长按符号上屏（，。！？等）：参照空格/点选上屏——先把当前 T9 首候选上屏，再上屏符号，最后清状态栏+候选栏
                if (t9DigitQueue.isNotEmpty() || t9SpellPrefix.isNotEmpty()) {
                    val cands = rimeEngine.candidates
                    if (cands.isNotEmpty()) {
                        val sel = rimeEngine.selectCandidate(0)
                        if (sel.isNotEmpty()) commitCandidateText(sel)
                    }
                }
                currentInputConnection?.commitText(symbol, 1)
                resetT9State()
                keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                longPressTriggered = true
                longPressConsumed = false
            }
            keyboardView.currentPopupKey = null
            keyboardView.invalidateKey(key)
            currentLongPressKey = null
        }.also {
            longPressHandler.postDelayed(it, 600)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        val prevKey = currentLongPressKey
        currentLongPressKey = null
        longPressTriggered = false
        longPressConsumed = false
        longPressOwnerCode = -999
        keyboardView.currentPopupKey = null
        if (prevKey != null) keyboardView.invalidateKey(prevKey)
    }

    /** 取消所有长按相关的 runnable（滑动切换时调用，彻底防止误触发） */
    private fun cancelAllLongPressActions() {
        cancelLongPress()
        // 取消功能键长按（每个按键码独立）
        functionalLongPressRunnables.values.forEach { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        functionalLongPressRunnables.clear()
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
        // 取消 -100 键长按
        symbolKeyLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        symbolKeyLongPressRunnable = null
        // 取消切换键(-102)长按设默认
        defaultKeyboardLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        defaultKeyboardLongPressRunnable = null
        // 取消退格长按
        backspaceRunnable?.let { backspaceHandler.removeCallbacks(it) }
        backspaceRunnable = null
        // 取消发送键长按
        cancelSendKeyLongPress()
        // 重置短按标志，防止 runnable 中的 !shortPressHandled 判断泄漏
        shortPressHandled = true
        // 守卫失效：任何遗留的长按 runnable 触发时都会因码不匹配而跳过
        longPressOwnerCode = -999
    }

    private fun startSendKeyLongPress() {
        cancelSendKeyLongPress()
        // 立即高亮发送按钮
        btnSend.background = makeKeyBgDrawable(themeAccent)
        startSendButtonGlow()
        sendKeyRunnable = Runnable {
            sendKeyLongPressTriggered = true
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // 如果星星菜单打开着，先关闭它
            if (smartWritingPopup != null && smartWritingPopup?.isShowing == true) {
                smartWritingPopup?.dismiss()
                smartWritingPopup = null
            }
            showClipboardManagerPopup()
        }.also {
            sendKeyHandler.postDelayed(it, 800)
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
        btnSend.background = makeKeyBgDrawable(currentKeyBg)
    }

    private fun startMagicBookLongPress() {
        cancelMagicBookLongPress()
        // 立即高亮魔法书按钮
        btnClipboard.background = makeKeyBgDrawable(themeAccent)
        startMagicBookGlow()
        magicBookRunnable = Runnable {
            magicBookLongPressTriggered = true
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showMagicHistoryPopup()
        }.also {
            magicBookHandler.postDelayed(it, 600)
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
        btnClipboard.background = makeKeyBgDrawable(currentKeyBg)
    }

    // ====== 语音按钮发光（锁定模式） ======
    private fun startMicButtonGlow() {
        micButtonGlowing = true
        val pulse = ScaleAnimation(
            1.0f, 1.15f, 1.0f, 1.15f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 600
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = ScaleAnimation.INFINITE
        }
        micButton.startAnimation(pulse)
    }

    private fun stopMicButtonGlow() {
        micButtonGlowing = false
        micButton.clearAnimation()
    }

    // ====== 清空按钮发光（长按） ======
    private fun startDeleteButtonGlow() {
        deleteButtonGlowing = true
        val pulse = ScaleAnimation(
            1.0f, 1.15f, 1.0f, 1.15f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 600
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = ScaleAnimation.INFINITE
        }
        btnDelete.startAnimation(pulse)
    }

    private fun stopDeleteButtonGlow() {
        deleteButtonGlowing = false
        btnDelete.clearAnimation()
        btnDelete.background = makeKeyBgDrawable(currentKeyBg)
        btnDelete.elevation = 0f
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
            applyAccentToViewTree(clipboardPopupView!!, themeAccent)
            val popupView = clipboardPopupView!!
            val gvClipboard = popupView.findViewById<GridView>(R.id.gv_clipboard_items)
            val etSearch = popupView.findViewById<android.widget.EditText>(R.id.et_clipboard_search)
            this.etSearch = etSearch
            val tvSearchHint = popupView.findViewById<TextView>(R.id.tv_search_edit_hint)
            val btnAdd = popupView.findViewById<TextView>(R.id.btn_clipboard_add)
            val btnPin = popupView.findViewById<TextView>(R.id.btn_clipboard_pin)
            val btnDelete = popupView.findViewById<TextView>(R.id.btn_clipboard_delete)
            val btnClose = popupView.findViewById<TextView>(R.id.btn_clipboard_close)
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
// endregion 长按检测

// region 剪贴板搜索
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
            Log.d("Cesia", "showClipboardManagerPopup: clipboardItems.size=${clipboardItems.size}, items=${clipboardItems.take(3).map { it.text.take(20) }}")

            // 初始化过滤
            clipboardSearchFilter = ""
            applyClipboardFilter()

            clipboardAdapter = ClipboardAdapter(inflater2, clipboardFilteredItems, this)
            gvClipboard.adapter = clipboardAdapter

            val keyboardWidth = keyboardView.width
            val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

            // 获取状态栏高度
            val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
                if (id > 0) resources.getDimensionPixelSize(id) else 88
            }
            // 高度 = 状态栏底部到键盘顶部的可用空间
            val keyboardLocation = IntArray(2)
            keyboardView.getLocationOnScreen(keyboardLocation)
            val keyboardTopScreenY = keyboardLocation[1]
            val totalHeight = (keyboardTopScreenY - statusBarHeight).coerceAtLeast(200)

            val popup = PopupWindow(popupView, popupWidth, totalHeight, true)
            popup.isOutsideTouchable = false
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            popup.elevation = 8f
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.setFocusable(false)
            clipboardPopup = popup

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
                showClipboardItemActions(item, clipboardItems) {
                    // 删除后直接保存到 SharedPreferences，不要重新加载（否则被删除的条目会重新出现）
                    saveClipboardHistoryFromClassMembers()
                    applyClipboardFilter()
                }
                true
            }


            btnAdd.setOnClickListener {
                // 新增：打开编辑弹窗（PopupWindow 内的 EditText 无法接收 IME，需手动拦截输入）
                showClipboardAddPopup()
            }

            btnClose.setOnClickListener { popup.dismiss() }

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
                        // 清除系统剪贴板，防止重新加载时再次出现
                        try {
                            val clipboardMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboardMgr?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                        } catch (_: Exception) {}
                        updateStatus("⊗ 已删除全部（保留置顶）")
                    } else {
                        val target = realItems.find { it.text.hashCode() == menuItem.itemId }
                        if (target != null) {
                            clipboardItems.removeAll { it.text == target.text }
                            saveClipboardHistoryFromClassMembers()
                            applyClipboardFilter()
                            // 同时清除系统剪贴板中匹配的内容，防止重新加载时再次出现
                            try {
                                val clipboardMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                if (clipboardMgr?.hasPrimaryClip() == true) {
                                    val clipText = clipboardMgr.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clipText == target.text) {
                                        clipboardMgr.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }
                popupMenu.show()
            }

            popup.showAtLocation(keyboardView, android.view.Gravity.TOP or android.view.Gravity.START, 0, -totalHeight)

            popup.setOnDismissListener {
                cancelSendKeyLongPress()
                clipboardPopup = null
                Log.d("Cesia", "clipboardPopup dismissed, clipboardItems.size=${clipboardItems.size}")
            }

            // 持久化保存（弹窗显示后立即保存当前加载状态）
            saveClipboardHistoryFromClassMembers()
            Log.d("Cesia", "showClipboardManagerPopup: saved to prefs, clipboardItems.size=${clipboardItems.size}")

        } catch (e: Exception) {
            updateStatus("❌ 剪贴板管理器异常: ${e.message}")
        }
    }

    private fun loadClipboardHistoryToClassMembers(clipboardMgr: android.content.ClipboardManager?) {
        clipboardItems.clear()
        try {
            // 1. 从 SharedPreferences 读取持久化历史
            val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
            val historyStr = prefs.getString("history", "") ?: ""
            val favStr = prefs.getString("favorites", "") ?: ""
            Log.d("Cesia", "loadClipboard: historyStr='${historyStr.take(100)}', favStr='${favStr.take(50)}'")
            val favSet = if (favStr.isNotEmpty()) favStr.split("\n").toSet() else emptySet()
            val historyTexts = if (historyStr.isNotEmpty()) historyStr.split("\n").filter { it.isNotEmpty() }.toSet() else emptySet()

            // 2. 获取系统剪贴板内容
            val sysClipTexts = mutableListOf<String>()
            if (clipboardMgr?.hasPrimaryClip() == true) {
                val clip = clipboardMgr.primaryClip
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val text = clip.getItemAt(i).text?.toString()?.trim() ?: ""
                        if (text.isNotEmpty() && text.length <= 500) {
                            sysClipTexts.add(text)
                        }
                    }
                }
            }

            // 3. 系统剪贴板内容始终放第0位
            // 分类：不在持久化历史的直接添加，在持久化历史的记录下来稍后处理
            val sysInHistory = mutableListOf<String>()
            for (text in sysClipTexts) {
                if (text !in historyTexts) {
                    clipboardItems.add(ClipboardItem(text = text, isPinned = false))
                } else {
                    sysInHistory.add(text)
                }
            }

            // 4. 加载持久化历史
            // 先加载 sysInHistory 中的条目（系统剪贴板中已在持久化历史的），保持 sysClipTexts 顺序
            // 再加载其余条目（跳过已在第0位处理过的）
            if (historyStr.isNotEmpty()) {
                val historyList = historyStr.split("\n").filter { it.isNotEmpty() }
                for (text in sysInHistory) {
                    clipboardItems.add(ClipboardItem(text = text, isPinned = favSet.contains(text)))
                }
                for (text in historyList) {
                    if (text !in sysClipTexts) {
                        clipboardItems.add(ClipboardItem(text = text, isPinned = favSet.contains(text)))
                    }
                }
            }

            // 顺序稳定：sysClipTexts（不在历史的）→ sysInHistory → 其余历史
            // 每次加载顺序一致，不会因系统剪贴板变化而产生循环闪烁
        } catch (_: Exception) {}
        if (clipboardItems.isEmpty()) {
            clipboardItems.add(ClipboardItem(text = "(剪贴板为空)", isPinned = true, isEmpty = true))
        }
        Log.d("Cesia", "loadClipboard: result size=${clipboardItems.size}, first3=${clipboardItems.take(3).map { it.text.take(20) }}")
    }

    /** 获取输入法剪贴板第一条内容（系统剪贴板优先），智能写作调用此方法替代 getClipboardFirstNonPinned */
    private fun getClipboardFirstItemText(): String {
        for (item in clipboardItems) {
            if (!item.isEmpty && item.text.isNotEmpty()) {
                return item.text
            }
        }
        // fallback：如果弹窗没打开过（clipboardItems 为空），直接读系统剪贴板
        return getClipboardFirstNonPinned()
    }

    /** 保存剪贴板历史到 SharedPreferences（全部历史 + 收藏标记） */
    private fun saveClipboardHistoryFromClassMembers() {
        val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
        // 限制历史条数：置顶/收藏项始终保留，其余按最近顺序保留最多 maxClipboardHistory 条
        val pinned = clipboardItems.filter { it.isPinned && !it.isEmpty }
        val normal = clipboardItems.filter { !it.isPinned && !it.isEmpty }
        val cappedNormal = if (normal.size > maxClipboardHistory - pinned.size) {
            normal.takeLast(maxClipboardHistory - pinned.size)
        } else normal
        val capped = (pinned + cappedNormal).distinctBy { it.text }
        val allTexts = capped.map { it.text }
        val favTexts = capped.filter { it.isPinned }.map { it.text }
        prefs.edit()
            .putString("history", allTexts.joinToString("\n"))
            .putString("favorites", favTexts.joinToString("\n"))
            .apply()
        // 同步裁剪内存列表，避免无限增长
        clipboardItems.removeAll { item -> !item.isEmpty && capped.none { it.text == item.text } }
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
                        val words = item.text.split(CLIPBOARD_SPLIT_REGEX)
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
                            // 同时清除系统剪贴板中匹配的内容
                            try {
                                val clipboardMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                if (clipboardMgr?.hasPrimaryClip() == true) {
                                    val clipText = clipboardMgr.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clipText == item.text) {
                                        clipboardMgr.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                                    }
                                }
                            } catch (_: Exception) {}
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
        dialog.show()
    }

    /** 显示剪贴板新增弹窗（PopupWindow 内的 EditText 无法接收 IME，需手动拦截输入） */
    private fun showClipboardAddPopup() {
        clipboardAddMode = true
        clipboardAddBuffer.clear()
        dismissAllPopups()
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.popup_clipboard_manager, null)
        val popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isFocusable = true
            setBackgroundDrawable(ContextCompat.getDrawable(this@CesiaInputMethod, R.drawable.popup_bg))
            elevation = 8f
            setOutsideTouchable(true)
        }
        clipboardPopup = popup
        clipboardPopupView = view

        // 更新标题为"新增"
        view.findViewById<TextView>(R.id.tv_clipboard_title)?.text = "➕ 新增剪贴板"

        // 状态栏提示
        updateStatus("✏️ 输入剪贴板内容...（按发送键保存）")

        // 关闭按钮
        view.findViewById<ImageView>(R.id.btn_clipboard_close)?.setOnClickListener {
            exitClipboardAddMode(save = false)
            popup.dismiss()
        }

        // 新增按钮 -> 保存
        view.findViewById<ImageView>(R.id.btn_clipboard_add)?.setOnClickListener {
            exitClipboardAddMode(save = true)
            popup.dismiss()
        }

        // 显示弹窗
        val parentView = keyboardView
        popup.showAtLocation(parentView, Gravity.CENTER, 0, 0)
    }

    private fun updateClipboardFavorites() {
        // 原实现从已废弃的 clipboardHistory(恒为空)读取导致 favorites 被清空；
        // 直接复用 saveClipboardHistoryFromClassMembers 统一持久化(历史+收藏)，避免覆盖正确数据。
        saveClipboardHistoryFromClassMembers()
    }

// endregion 剪贴板搜索

// region 剪贴板适配器
    data class ClipboardItem(val text: String, val isPinned: Boolean = false, val isEmpty: Boolean = false)

    private class ClipboardAdapter(
        private val inflater: android.view.LayoutInflater,
        private val items: List<ClipboardItem>,
        private val context: CesiaInputMethod
    ) : android.widget.BaseAdapter() {
        private val accentColor = context.themeAccent
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
                tvPin.setTextColor(accentColor)
            }
            return v
        }
    }

// endregion 剪贴板适配器

// region 键盘回调
    // ======================== KeyboardView 回调 ========================
    // 参考 Trime 的 CommonKeyboardActionListener.onKey 逻辑：
    // 1. 按键后调用 processKey，不检查返回值
    // 2. 通过 getRimeContext/getRimeStatus 轮询状态更新 UI
    // 3. 退格/空格/回车等控制键优先交给 Rime 处理

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        // 任意按键都停止方向键重复，防止长按后光标卡住持续移动
        stopDirectionalRepeat()
        // 统一长按状态机：每次 onKey 先重置标志，防止跨键泄漏
        val wasLongPressed = longPressTriggered && !longPressConsumed
        longPressTriggered = false
        longPressConsumed = false
        // 取消所有长按 runnable（含功能键长按 functionalLongPressRunnable），
        // 防止快速输入下一个键时上一个字母的功能键长按被误触发
        cancelAllLongPressActions()
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

        // ======================== 智能写作命令编辑模式拦截 ========================
        if (smartEditMode) {
            when (primaryCode) {
                // 发送键/回车键：保存命令、退出编辑模式、直接执行
                -200, 10 -> {
                    val comp = rimeEngine.composingText
                    if (comp.isNotEmpty()) {
                        smartEditBuffer.append(comp)
                        rimeEngine.clear()
                    }
                    exitSmartEditMode(save = true, execute = true)
                    return
                }
                // 返回键：取消并退出编辑模式
                KeyEvent.KEYCODE_BACK -> {
                    rimeEngine.clear()
                    exitSmartEditMode(save = false)
                    return
                }
                // 退格键：优先删除 Rime composition，其次删除缓冲区
                -5, Keyboard.KEYCODE_DELETE -> {
                    if (rimeEngine.isComposing) {
                        rimeEngine.processKey("BackSpace")
                        updateSmartEditStatus()
                    } else if (smartEditBuffer.isNotEmpty()) {
                        smartEditBuffer.deleteCharAt(smartEditBuffer.length - 1)
                        updateSmartEditStatus()
                    }
                    return
                }
                // 字母键 a-z：走 Rime 引擎，让候选栏正常显示
                in 97..122 -> {
                    rimeEngine.processKey(primaryCode.toChar())
                    updateSmartEditStatus()
                    return
                }
                // 数字键 0-9：T9模式走T9拼音引擎，全键盘模式选词或追加
                in 48..57 -> {
                    if (keyboardMode == KeyboardMode.NUMBER) {
                        rimeEngine.processKey(primaryCode.toChar())
                        updateSmartEditStatus()
                    } else if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val index = if (primaryCode == 48) 9 else (primaryCode - 49)
                        val cands = rimeEngine.candidates
                        if (index < cands.size) {
                            val selected = rimeEngine.selectCandidate(index)
                            if (selected.isNotEmpty()) {
                                smartEditBuffer.append(selected)
                                rimeEngine.clear()
                            }
                        }
                    } else {
                        smartEditBuffer.append(primaryCode.toChar())
                    }
                    updateSmartEditStatus()
                    return
                }
                // 空格：如果有候选词则选第一个词，否则追加空格
                32 -> {
                    if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val selected = rimeEngine.selectCandidate(0)
                        if (selected.isNotEmpty()) {
                            smartEditBuffer.append(selected)
                            rimeEngine.clear()
                        }
                    } else {
                        smartEditBuffer.append(' ')
                    }
                    updateSmartEditStatus()
                    return
                }
                // 标点符号直接追加
                44, 46, 59, 33, 63, 45, 95, 43, 61, 40, 41, 123, 125, 91, 93, 47, 92, 58, 34, 39, 60, 62, 42, 38, 37, 35, 64, 36, 94, 126, 96, 124 -> {
                    rimeEngine.clear()
                    smartEditBuffer.append(primaryCode.toChar())
                    updateSmartEditStatus()
                    return
                }
                // 中文标点（Unicode）
                65292, 12290, 65307, 65281, 65311, 12289, 65288, 65289, 8220, 8221, 8216, 8217 -> {
                    rimeEngine.clear()
                    smartEditBuffer.append(primaryCode.toChar())
                    updateSmartEditStatus()
                    return
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

        // ======================== 剪贴板新增模式拦截 ========================
        if (clipboardAddMode) {
            when (primaryCode) {
                // 发送键/回车键：保存剪贴板并退出编辑模式
                -200, 10 -> {
                    val comp = rimeEngine.composingText
                    if (comp.isNotEmpty()) {
                        clipboardAddBuffer.append(comp)
                        rimeEngine.clear()
                    }
                    exitClipboardAddMode(save = true)
                    return
                }
                // 返回键：取消并退出编辑模式
                KeyEvent.KEYCODE_BACK -> {
                    rimeEngine.clear()
                    exitClipboardAddMode(save = false)
                    return
                }
                // 退格键：优先删除 Rime composition，其次删除缓冲区
                -5, Keyboard.KEYCODE_DELETE -> {
                    if (rimeEngine.isComposing) {
                        rimeEngine.processKey("BackSpace")
                        updateClipboardAddStatus()
                    } else if (clipboardAddBuffer.isNotEmpty()) {
                        clipboardAddBuffer.deleteCharAt(clipboardAddBuffer.length - 1)
                        updateClipboardAddStatus()
                    }
                    return
                }
                // 字母键 a-z：走 Rime 引擎，让候选栏正常显示
                in 97..122 -> {
                    rimeEngine.processKey(primaryCode.toChar())
                    updateClipboardAddStatus()
                    return
                }
                // 数字键 0-9
                in 48..57 -> {
                    if (keyboardMode == KeyboardMode.NUMBER) {
                        rimeEngine.processKey(primaryCode.toChar())
                        updateClipboardAddStatus()
                    } else if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val index = if (primaryCode == 48) 9 else (primaryCode - 49)
                        val cands = rimeEngine.candidates
                        if (index < cands.size) {
                            val selected = rimeEngine.selectCandidate(index)
                            if (selected.isNotEmpty()) {
                                clipboardAddBuffer.append(selected)
                                rimeEngine.clear()
                            }
                        }
                    } else {
                        clipboardAddBuffer.append(primaryCode.toChar())
                    }
                    updateClipboardAddStatus()
                    return
                }
                // 空格：如果有候选词则选第一个词，否则追加空格
                32 -> {
                    if (rimeEngine.isComposing && rimeEngine.hasCandidates) {
                        val selected = rimeEngine.selectCandidate(0)
                        if (selected.isNotEmpty()) {
                            clipboardAddBuffer.append(selected)
                            rimeEngine.clear()
                        }
                    } else {
                        clipboardAddBuffer.append(' ')
                    }
                    updateClipboardAddStatus()
                    return
                }
                // 标点符号直接追加
                44, 46, 59, 33, 63, 45, 95, 43, 61, 40, 41, 123, 125, 91, 93, 47, 92, 58, 34, 39, 60, 62, 42, 38, 37, 35, 64, 36, 94, 126, 96, 124 -> {
                    rimeEngine.clear()
                    clipboardAddBuffer.append(primaryCode.toChar())
                    updateClipboardAddStatus()
                    return
                }
                // 中文标点（Unicode）
                65292, 12290, 65307, 65281, 65311, 12289, 65288, 65289, 8220, 8221, 8216, 8217 -> {
                    rimeEngine.clear()
                    clipboardAddBuffer.append(primaryCode.toChar())
                    updateClipboardAddStatus()
                    return
                }
            }
        }

        val ic = currentInputConnection
        val composing = rimeEngine.isComposing
        val hasCands = rimeEngine.hasCandidates
        val cands = rimeEngine.candidates

        // 空格键调试日志
        if (primaryCode == 32 && keyboardMode != KeyboardMode.NUMBER && !isAsciiMode) {
            Log.d("Cesia", "空格键: composing=$composing hasCands=$hasCands cands=${cands.size} isAscii=$isAsciiMode mode=$keyboardMode")
        }

        // 任何新按键（除空格键外）清除联想状态，确保旧联想词不会残留
        if (primaryCode != 32) {
            exitAssociationMode()
        }

        // ======================== = 号计算器（复刻 Rime =expr）============================
        // 计算模式激活(calcExpr 以 '=' 开头非空)：数字/运算符/括号/小数点追加到算式；
        // 空格/回车/再次按 = 触求值并上屏；遇到非算式字符则先求值上屏再 fallthrough 走原逻辑。
        if (isCalcActive()) {
            val isDigit = primaryCode in 48..57
            val isOp = primaryCode in listOf(42, 43, 45, 47, 37, 94) // * + - / % ^
            val isParen = primaryCode == 40 || primaryCode == 41
            val isDot = primaryCode == 46
            if (isDigit || isOp || isParen || isDot) {
                calcExpr.append(primaryCode.toChar())
                updateStatus("= ${calcExpr.substring(1)}")
                shortPressHandled = true
                return
            }
            // 触求值：空格/回车/再次按 =
            if (primaryCode == 32 || primaryCode == 10 || primaryCode == 61) {
                val res = evalMath(calcExpr.substring(1))
                calcExpr.clear()
                if (res != null) commitCandidateText(res) else updateStatus(statusIdleText)
                shortPressHandled = true
                return
            }
            // 非算式字符：先求值上屏（若有结果），清缓冲后 fallthrough 走原逻辑
            val res = evalMath(calcExpr.substring(1))
            calcExpr.clear()
            if (res != null) commitCandidateText(res)
        }
        // 首次按 = 且未激活：进入计算模式
        if (primaryCode == 61 && !isCalcActive()) {
            calcExpr.append('=')
            updateStatus("= ")
            shortPressHandled = true
            return
        }

        when (primaryCode) {

            // ======================== 字母键 a-z ========================
            in 97..122 -> {
                // 输入新拼音时退出联想模式
                exitAssociationMode()
                functionalLongPressRunnables[primaryCode]?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                functionalLongPressRunnables.remove(primaryCode)
                shortPressHandled = true
                if (isAsciiMode) {
                    // Shift模式：走英文词典联想（en schema 的 table_translator）
                    val shiftActive = if (keyboardMode == KeyboardMode.NUMBER) {
                        t9ShiftTemp || t9ShiftLocked
                    } else if (keyboardMode == KeyboardMode.QWERTY) {
                        isAsciiMode
                    } else {
                        symbolShiftLocked
                    }
                    val keyChar = if (shiftActive) {
                        primaryCode.toChar().uppercaseChar()
                    } else {
                        primaryCode.toChar()
                    }
                    // 走 Rime 英文方案，获取联想候选词
                    val accepted = rimeEngine.processKey(keyChar)
                    if (accepted) {
                        updateCandidateBar()
                        // 如果 Rime 没有 composing（直接上屏了），commit 结果
                        if (!rimeEngine.isComposing) {
                            val result = rimeEngine.commit()
                            if (result.isNotEmpty()) {
                                commitCandidateText(result)
                                pendingEnglish = result
                            } else {
                                ic?.commitText(keyChar.toString(), 1)
                                pendingEnglish += keyChar
                            }
                        }
                    } else {
                        // Rime 不接受，直接上屏
                        ic?.commitText(keyChar.toString(), 1)
                        pendingEnglish += keyChar
                    }
                    // QWERTY临时shift：输入一个字母后自动退回中文（锁定不退出）
                    if (!qwertyShiftLocked && keyboardMode == KeyboardMode.QWERTY && qwertyShiftTemp) {
                        qwertyShiftTemp = false
                        isAsciiMode = false
                        rimeEngine.setAsciiMode(false)
                        rimeEngine.selectSchema("pinyin")
                        updateShiftIndicator()
                        keyboardView.invalidateAllKeys()
                    }
                } else {
                    // 中文模式：先走 Rime 引擎
                    val hadComposing = rimeEngine.isComposing
                    exitAssociationMode()
                    val accepted = rimeEngine.processKey(primaryCode.toChar())
                    Log.d("Cesia", "中英混输调试: key='${primaryCode.toChar()}' hadComposing=$hadComposing accepted=$accepted nowComposing=${rimeEngine.isComposing} composingText='${rimeEngine.composingText}'")
                    if (accepted) {
                        // 如果之前没有 composing，且输入后 Rime 产生了 composing，说明是拼音输入
                        // 如果之前没有 composing，且输入后也没有 composing，说明是英文输入
                        if (!hadComposing && !rimeEngine.isComposing) {
                            // Rime 没有进入 composing 状态，直接上屏英文
                            ic?.commitText(primaryCode.toChar().toString(), 1)
                            pendingEnglish += primaryCode.toChar()
                        } else if (rimeEngine.isComposing) {
                            // 进入拼音 composing，英文缓冲失效
                            pendingEnglish = ""
                        }
                        updateCandidateBar()
                    } else {
                        // Rime 不接受该按键，直接上屏
                        ic?.commitText(primaryCode.toChar().toString(), 1)
                        pendingEnglish += primaryCode.toChar()
                        updateCandidateBar()
                    }
                }
            }

            // ======================== 数字键区域 (0-9) ========================
            in 48..57 -> {
                if (keyboardMode == KeyboardMode.NUMBER) {
                    handleNumberKeyboardKey(primaryCode)
                } else {
                    // 全键盘模式的数字键逻辑
                    // 英文缓冲：多字母直接上屏后按数字，英文+数字一起上屏（如 abcd9）
                    if (pendingEnglish.isNotEmpty() && !rimeEngine.isComposing) {
                        rimeEngine.clear()
                        ic?.commitText(pendingEnglish + primaryCode.toChar().toString(), 1)
                        pendingEnglish = ""
                        autoExitShift()
                        updateCandidateBar()
                        return@onKey
                    }
                    // 如果当前 composing 是纯英文（如输入t后按9），直接上屏英文+数字
                    val composingText = rimeEngine.composingText
                    val isPureEnglish = composing && composingText.isNotEmpty() &&
                        composingText.all { it in 'a'..'z' }
                    if (isPureEnglish) {
                        // 英文输入中按数字：无论多少英文，英文+数字一起直接上屏（如 t9 / abcd9）
                        rimeEngine.clear()
                        ic?.commitText(composingText + primaryCode.toChar().toString(), 1)
                        autoExitShift()
                    } else if (!isAsciiMode && composing && hasCands) {
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
                        // 英文模式下输入数字保持英文模式，不自动退出
                        if (isAsciiMode) {
                            // 保持 isAsciiMode=true，不调用 autoExitShift
                        } else {
                            autoExitShift()
                        }
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
                    } else if (t9DigitQueue.isNotEmpty()) {
                        // T9模式：空格 = 选择首候选上屏
                        val cands = rimeEngine.candidates
                        if (cands.isNotEmpty()) {
                            val selected = rimeEngine.selectCandidate(0)
                            if (selected.isNotEmpty()) {
                                commitCandidateText(selected)
                            }
                        } else {
                            ic?.commitText(" ", 1)
                        }
                        resetT9State()
                    } else {
                        ic?.commitText(" ", 1)
                    }
                } else if (isAsciiMode) {
                    if (pendingEnglish.isNotEmpty()) {
                        ic?.commitText(pendingEnglish, 1)
                        pendingEnglish = ""
                    }
                    ic?.commitText(" ", 1)
                } else {
                    // 全键盘中文模式：参照 T9 空格键逻辑，直接检查 candidates
                    if (isAssociationMode && associationCandidates.isNotEmpty()) {
                        // 联想模式：选择第一个联想词继续联想
                        val selectedWord = associationCandidates[0]
                        val newPrefix = associationPrefix + selectedWord
                        val newAssociations = rimeEngine.getAssociations(newPrefix)
                        if (newAssociations.isNotEmpty()) {
                            associationPrefix = newPrefix
                            associationCandidates = newAssociations
                            commitCandidateText(selectedWord)
                            showAssociationCandidates()
                        } else {
                            isAssociationMode = false
                            associationPrefix = ""
                            associationCandidates = emptyList()
                            commitCandidateText(selectedWord)
                            updateCandidateBar()
                        }
                    } else {
                        val cands = rimeEngine.candidates
                        if (cands.isNotEmpty()) {
                            val selected = rimeEngine.selectCandidate(0)
                            if (selected.isNotEmpty()) {
                                commitCandidateText(selected)
                            } else {
                                ic?.commitText(" ", 1)
                            }
                        } else if (composing) {
                            commitAndClear(); ic?.commitText(" ", 1)
                        } else {
                            ic?.commitText(" ", 1)
                        }
                    }
                }
                if (keyboardMode != KeyboardMode.NUMBER) clearCandidateContent()
            }

            // ======================== 退格键 ========================
            -5, Keyboard.KEYCODE_DELETE -> {
                // 优先检查是否有选中文本
                val sel = ic?.getSelectedText(0)
                if (sel != null && sel.isNotEmpty()) {
                    deleteSelectionOrChar()
                    return
                }
                // 退格：结束语音保持模式（语音②“退格才清”），并准备清除候选栏内容
                candidateBarKeep = false
                if (keyboardMode == KeyboardMode.NUMBER) {
                    // 数字键盘退格（逐键选音：优先撤销已选字母；撤销字母不会删除原数字，仅缩短前缀）
                    if (!t9ShiftTemp && (t9SpellPrefix.isNotEmpty() || t9DigitQueue.isNotEmpty())) {
                        if (t9DigitQueue.length == 1 && t9SpellPrefix.isNotEmpty()) {
                            // 单键已选音：选音即定型，退格直接整删（数字+选音一起清），回到空状态
                            t9DigitQueue.clear()
                            t9SpellPrefix.clear()
                        } else if (t9SpellPrefix.isNotEmpty()) {
                            t9SpellPrefix.deleteCharAt(t9SpellPrefix.length - 1)
                        } else {
                            t9DigitQueue.deleteCharAt(t9DigitQueue.length - 1)
                        }
                        // 退格后若已超过已消费范围（接龙态下删到了已组词字符）→ 整段取消接龙，重置缓冲
                        if (t9ConsumedLen > t9DigitQueue.length) {
                            t9ConsumedLen = 0
                            t9ComposedSoFar.clear()
                        }
                        // 退格：重算候选（processT9Input 增量喂+刷新候选栏，简拼退格也同步 t9FenCiMerged）
                        processT9Input()
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
                        clearCandidateContent()
                    } else {
                        updateCandidateBar()
                    }
                }
            }

            // ======================== 回车键（只换行，不发送）=======================
            10, Keyboard.KEYCODE_DONE -> {
                shortPressHandled = true  // 阻止长按撤销与短按换行同时触发
                if (composing) {
                    if (!isAsciiMode) {
                        // T9 简拼模式：回车上屏（锁定字母 + 剩余数字），并清空候选栏
                        if (keyboardMode == KeyboardMode.NUMBER && t9DigitQueue.isNotEmpty()) {
                            val toCommit = t9SpellPrefix.toString() +
                                    t9DigitQueue.substring(t9SpellPrefix.length)  // 锁定字母 + 剩余数字(如 t+9=t9)
                            t9ComposedSoFar.append(toCommit)
                            commitCandidateText(toCommit)
                            rimeEngine.clear()
                            resetT9State()  // 清空队列/候选栏
                        } else {
                            // 直接上屏当前拼音字母（不转换成汉字），去掉分词符 ' 和空格
                            val pinyinText = rimeEngine.composingText?.replace(" ", "")?.replace("'", "")
                            if (!pinyinText.isNullOrEmpty()) {
                                ic?.commitText(pinyinText, 1)
                            } else if (hasCands) {
                                val selected = rimeEngine.selectCandidate(0)
                                if (selected.isNotEmpty()) {
                                    commitCandidateText(selected)
                                }
                            }
                            rimeEngine.clear()
                            clearCandidateContent()
                        }
                    } else {
                        // 英文模式：先上屏英文（去掉分词符 '），再换行
                        val enText = rimeEngine.composingText?.replace("'", "")?.replace(" ", "")
                        if (!enText.isNullOrEmpty()) {
                            ic?.commitText(enText, 1)
                        }
                        rimeEngine.clear()
                        clearCandidateContent()
                        ic?.commitText("\n", 1)
                    }
                } else {
                    // 只发送换行，不触发发送动作
                    ic?.commitText("\n", 1)
                }
            }

            // ======================== Shift 键（QWERTY -1 / T9 -104 统一行为）=======================
            -1 -> { shortPressHandled = true; handleShiftKey() }

            // ======================== 符号切换（符）=======================
            KEYCODE_SWITCH_SYMBOL -> {
                if (symbolPanelLongPressTriggered) {
                    symbolPanelLongPressTriggered = false
                } else {
                    toggleSymbolKeyboard()
                }
            }

            // ======================== 符号语言切换（中英符号）========================
            KEYCODE_SWITCH_SYMBOL_LANG -> toggleSymbolLanguage()

            // ======================== 数字切换（123）========================
            KEYCODE_SWITCH_NUMBER -> {
                defaultKeyboardLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                defaultKeyboardLongPressRunnable = null
                toggleNumberKeyboard()
            }
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
            KEYCODE_BACK_KEY -> {
                defaultKeyboardLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                defaultKeyboardLongPressRunnable = null
                switchToDefaultKeyboard()
            }

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
                // 如果当前 composing 是纯英文（如输入llama后按.），直接上屏英文+标点
                val composingText = rimeEngine.composingText
                val isPureEnglish = !isAsciiMode && composing && composingText.isNotEmpty() &&
                    composingText.all { it in 'a'..'z' }
                if (isPureEnglish) {
                    // 英文输入中按标点：上屏英文原文 + 标点，无空格
                    val punct = primaryCode.toChar().toString()
                    rimeEngine.clear()
                    ic?.commitText(composingText + punct, 1)
                } else {
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
                    // 英文模式下符号直接上屏，不清空 Rime 状态
                    if (isAsciiMode) {
                        // 保持英文模式，不清空任何状态
                    } else {
                        // 标点上屏后清空候选栏和状态栏（全键盘 + T9 统一逻辑）
                        rimeEngine.clear()
                        if (keyboardMode == KeyboardMode.NUMBER) {
                            // T9 模式：彻底清空数字队列/前缀，状态栏退回已就绪
                            t9DigitQueue.clear(); t9SpellPrefix.clear()
                            t9InputBuffer.clear()
                            lastT9Feed = null  // 重置增量喂标记，下次输入从头喂（防首个音被吃）
                            updateStatus(statusIdleText)
                        }
                    }
                }
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
            commitCandidateText(text)
        }
        rimeEngine.clear()
        pendingEnglish = ""
        if (isPanelExpanded) collapseCandidatePanel()
        clearCandidateContent()
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

// endregion 键盘回调

// region 按键事件
    override fun onPress(primaryCode: Int) {
        shortPressHandled = false
        // 长按归 owner：本键开始计时，任何其它键/滑动/切换都会先 cancelAllLongPressActions 使旧 owner 失效
        longPressOwnerCode = primaryCode
        // 功能键长按检测（仅 QWERTY 中文模式，且 Rime 不在 composing 状态）
        // 注意：功能键长按(500ms)优先于 popupCharacters 长按(400ms)
        // 功能键长按注册后，跳过 popupCharacters 长按，避免冲突
        var skipPopupLongPress = false
        if (!isAsciiMode && primaryCode in 97..122 && keyboardMode == KeyboardMode.QWERTY && !rimeEngine.isComposing) {
            if (getFunctionalLongAction(primaryCode) != null) {
                skipPopupLongPress = true
                // 快速/多指连续输入时，上一个按键（仍按住未释放）的功能长按 runnable 可能残留，
                // 在此按码清除“其它按键”的残留 runnable，避免首个按键功能被误触发；
                // 当前按键自身的 runnable 保留（注册在下方），单指长按功能不受影响。
                functionalLongPressRunnables.keys.filter { it != primaryCode }.forEach { code ->
                    functionalLongPressRunnables[code]?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                    functionalLongPressRunnables.remove(code)
                }
                val runnable = Runnable {
                    if (longPressOwnerCode != primaryCode) return@Runnable
                    if (!shortPressHandled) {
                        getFunctionalLongAction(primaryCode)?.invoke()
                        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        longPressTriggered = true
                        longPressConsumed = false
                    }
                    currentLongPressKey = null
                    functionalLongPressRunnables.remove(primaryCode)
                }
                functionalLongPressRunnables[primaryCode] = runnable
                Handler(Looper.getMainLooper()).postDelayed(runnable, 700)
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
                if (longPressOwnerCode != primaryCode) return@Runnable
                if (!shortPressHandled) {
                    handleShiftLongPress()
                }
            }.also {
                Handler(Looper.getMainLooper()).postDelayed(it, 700)
            }
        }
        // 剪贴板键长按：-108=粘贴，-109=剪切
        if (primaryCode == -108) {
            clipboardPasteRunnable = Runnable {
                if (longPressOwnerCode != -108) return@Runnable
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    currentInputConnection?.performContextMenuAction(android.R.id.paste)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(clipboardPasteRunnable!!, 700)
        }
        if (primaryCode == -109) {
            clipboardCutRunnable = Runnable {
                if (longPressOwnerCode != -109) return@Runnable
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    currentInputConnection?.performContextMenuAction(android.R.id.cut)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(clipboardCutRunnable!!, 700)
        }
        // 符号切换键(-100)长按：弹出分类符号面板
        if (primaryCode == KEYCODE_SWITCH_SYMBOL) {
            symbolPanelLongPressTriggered = false
            symbolKeyLongPressRunnable = Runnable {
                if (longPressOwnerCode != KEYCODE_SWITCH_SYMBOL) return@Runnable
                if (!shortPressHandled) {
                    symbolPanelLongPressTriggered = true
                    longPressTriggered = true
                    longPressConsumed = true
                    keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    showSymbolPanel()
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(symbolKeyLongPressRunnable!!, 700)
        }
        // 全键盘/T9 切换键(-102)长按：将 T9 设为默认键盘并切换到 T9（打开输入法即用）
        if (primaryCode == KEYCODE_SWITCH_NUMBER) {
            defaultKeyboardLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            defaultKeyboardLongPressRunnable = Runnable {
                if (longPressOwnerCode != KEYCODE_SWITCH_NUMBER) return@Runnable
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    defaultKeyboardMode = KeyboardMode.NUMBER
                    try {
                        getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
                            .putString("default_keyboard_mode", KeyboardMode.NUMBER.name).apply()
                    } catch (_: Exception) {}
                    switchToKeyboard(KeyboardMode.NUMBER)
                    rimeEngine.selectSchema("t9_pinyin")
                    rimeEngine.reload()
                    resetNumberKeyboardState()
                    keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    updateStatus("已将 T9 设为默认键盘（下次打开即用）")
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(defaultKeyboardLongPressRunnable!!, 700)
        }
        // T9 全键盘切换键(-999/⌨)长按：将全键盘设为默认并切换到全键盘（打开输入法即用）
        if (primaryCode == KEYCODE_BACK_KEY) {
            defaultKeyboardLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            defaultKeyboardLongPressRunnable = Runnable {
                if (longPressOwnerCode != KEYCODE_BACK_KEY) return@Runnable
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    defaultKeyboardMode = KeyboardMode.QWERTY
                    try {
                        getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
                            .putString("default_keyboard_mode", KeyboardMode.QWERTY.name).apply()
                    } catch (_: Exception) {}
                    switchToKeyboard(KeyboardMode.QWERTY)
                    rimeEngine.selectSchema("pinyin")
                    rimeEngine.reload()
                    if (qwertyShiftLocked) {
                        isAsciiMode = true
                        rimeEngine.setAsciiMode(true)
                        rimeEngine.selectSchema("en")
                    }
                    keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    updateStatus("已将 全键盘 设为默认键盘（下次打开即用）")
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(defaultKeyboardLongPressRunnable!!, 700)
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
                // owner 守卫：若已切键/滑动，旧回车长按不再触发（防误撤销大段文字）
                if (longPressOwnerCode != 10 && longPressOwnerCode != Keyboard.KEYCODE_DONE) return@Runnable
                if (!shortPressHandled) {
                    longPressTriggered = true
                    longPressConsumed = false
                    sendCtrlKey(KeyEvent.KEYCODE_Z)
                }
            }.also {
                Handler(Looper.getMainLooper()).postDelayed(it, 700)
            }
        }

        if (primaryCode == -200) {
            startSendKeyLongPress()
        }
    }

    override fun onRelease(primaryCode: Int) {
        cancelLongPress()
        functionalLongPressRunnables[primaryCode]?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        functionalLongPressRunnables.remove(primaryCode)
        clipboardPasteRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        clipboardPasteRunnable = null
        clipboardCutRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        clipboardCutRunnable = null
        shiftLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        shiftLongPressRunnable = null
        enterLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        enterLongPressRunnable = null
        symbolKeyLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        symbolKeyLongPressRunnable = null
        defaultKeyboardLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        defaultKeyboardLongPressRunnable = null
        cancelSendKeyLongPress()
        // 停止 hjkl 方向键重复
        stopDirectionalRepeat()
        // 停止 M 键连续删除
        stopForwardDeleteRepeat()
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

// endregion 按键事件

// region 生命周期续
    // ======================== 生命周期 ========================

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        try {
            if (!isViewInitialized) {
                Log.w("Cesia", "onStartInputView: isViewInitialized=false, skipping")
                return
            }
            loadSettings()
            loadThemeColors()

            // 读取个性化设置
            val sPrefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            val idleText = sPrefs.getString("status_idle", "") ?: ""
            if (idleText.isNotEmpty()) statusIdleText = idleText
            val swLabel = sPrefs.getString("smart_writing_label", "") ?: ""
            if (swLabel.isNotEmpty()) smartWritingLabel = swLabel
            val mbTitle = sPrefs.getString("magic_book_title", "") ?: ""
            if (mbTitle.isNotEmpty()) magicBookTitle = mbTitle

            val themeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
                .getInt(PREF_THEME_MODE, THEME_LIGHT)
            isDarkTheme = themeMode == THEME_DARK
            // 随手机时间自动变化主题色：每次激活键盘时同步当前小时色相
            if (autoTimeTheme) {
                applyAutoTimeTheme()
                startAutoTimeTheme()
            } else {
                stopAutoTimeTheme()
            }
            applyKeyboardTheme()
            // 恢复 Rime schema：灭屏/重连后 onFinishInputView 可能 clear composing，
            // 亮屏激活时若 schema 停留在非 T9 方案，T9 数字串无法转拼音 → 退化成纯数字。
            // 按当前键盘模式重新 selectSchema + reload，确保 T9 必为 t9_pinyin。
            if (keyboardMode == KeyboardMode.NUMBER) {
                rimeEngine.selectSchema("t9_pinyin")
                rimeEngine.reload()
            } else if (keyboardMode == KeyboardMode.QWERTY) {
                rimeEngine.selectSchema(if (qwertyShiftLocked || qwertyShiftTemp) "en" else "pinyin")
                rimeEngine.reload()
            }
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
            loadUserPhrases()  // 加载用户自建词组库
            // 每次输入法激活时更新语音后端并预加载模型
            Log.d("Cesia", "onStartInputView: step1 updateVoiceBackend")
            modelManager.scanExistingModels()
            updateVoiceBackend()
            Log.d("Cesia", "onStartInputView: step2 preloadModels")
            preloadWhisperModel()
            preloadAiModel()

            // 注册来电监听（来电时自动退出语音模式，避免卡死）
            registerPhoneStateListener()

            // RSS 自动抓取：如果缓存不存在或过期（>1h），后台自动刷新
            Log.d("Cesia", "onStartInputView: step3 autoRefreshRssCache")
            autoRefreshRssCache()
            Log.d("Cesia", "onStartInputView: step4 done")

            // 🎨 主题按钮：始终显示，不依赖 AI 模型是否下载
            btnTheme?.visibility = View.VISIBLE

            // 重置星星按钮状态（防止重启后残留高亮）
            btnMagic.background = makeKeyBgDrawable(currentKeyBg)
            btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
            btnMagic.clearAnimation()
        } catch (e: Throwable) {
            Log.e("Cesia", "onStartInputView 异常(已忽略)", e)
        }
    }

    /** 预加载 Sherpa 模型到内存（如果已安装） */
    private fun preloadWhisperModel() {
        if (voiceEngine.getBackend() != VoiceEngine.Backend.LOCAL_SHERPA) return
        if (!voiceEngine.hasSherpaModel()) return
        voiceEngineScope.launch {
            try {
                val loaded = voiceEngine.loadLocalModel()
                Log.i("Cesia", "Sherpa 预加载: ${if (loaded) "成功" else "失败"}")
            } catch (e: Throwable) {
                Log.e("Cesia", "Sherpa 预加载失败", e)
            }
        }
    }

    /** 预加载 AI 模型到内存（如果已安装） */
    private fun preloadAiModel() {
        if (aiEngine.isModelLoaded()) return  // 已加载则跳过
        val modelFile = modelManager.getInstalledAiModelFile() ?: return
        val configPath = if (modelFile.isDirectory) {
            File(modelFile, "config.json").absolutePath
        } else {
            modelFile.absolutePath
        }
        voiceEngineScope.launch {
            try {
                val loaded = aiEngine.loadLocalModel(configPath)
                Log.i("Cesia", "AI 模型预加载: ${if (loaded) "成功" else "失败"}")
            } catch (e: Throwable) {
                Log.e("Cesia", "AI 模型预加载失败", e)
            }
        }
    }

    /** 自动刷新 RSS 缓存（缓存不存在或超过1小时则后台抓取） */
    private fun autoRefreshRssCache() {
        try {
            val cacheFile = java.io.File(filesDir, "rss_cache.txt")
            val cacheExpired = if (cacheFile.exists()) {
                System.currentTimeMillis() - cacheFile.lastModified() > 60 * 60 * 1000L
            } else true
            if (!cacheExpired) return
            val source = RssFetchManager.getSelectedSource(this) ?: return
            Log.d("Cesia", "autoRefreshRssCache: cache ${if (cacheFile.exists()) "expired" else "missing"}, fetching ${source.name}")
            voiceEngineScope.launch {
                try {
                    val success = RssFetchManager.fetchAndCache(this@CesiaInputMethod, source)
                    Log.d("Cesia", "autoRefreshRssCache: ${if (success) "success" else "failed"}")
                } catch (e: Throwable) {
                    Log.w("Cesia", "autoRefreshRssCache error: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.w("Cesia", "autoRefreshRssCache exception: ${e.message}")
        }
    }

    /** 本地/云端模式切换后的回调 */

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 关闭符号面板（避免切出输入法后残留）
        dismissSymbolPanel()
        // 停止所有可能的重复（方向键/退格），防止切出输入法后光标卡住
        directionalRepeatActive = false
        directionalRepeatRunnable?.let { directionalRepeatHandler.removeCallbacks(it) }
        directionalRepeatRunnable = null
        backspaceRunnable?.let { backspaceHandler.removeCallbacks(it) }
        backspaceRunnable = null
        // 语音输入中切后台（窗口隐藏）→ 自动退出语音模式，并把保留内容落定上屏（避免系统丢弃 composing text）
        if (isRecording || isVoiceLocked || recognizedText.isNotEmpty()) {
            forceExitVoiceMode()
        }
        // 清除联想模式和候选，恢复初始状态
        if (isAssociationMode || rimeEngine.isComposing) {
            isAssociationMode = false
            associationCandidates = emptyList()
            rimeEngine.clear()
            updateCandidateBar()
            updateStatus(statusIdleText)
        }
        if (finishingInput && isRecording) stopRecording()
        // 切到后台/收起输入法时，候选栏字母点选区同步消失
        llT9Spell?.visibility = android.view.View.GONE
    }

    override fun onDestroy() {
        cancelAllLongPressActions()
        cancelLongPress()
        typelessEngine?.destroy()
        typelessEngine = null
        rimeEngine?.shutdown()
        voiceEngine.release()
        aiEngine.release()
        voiceEngineScope.cancel()
        // 注销来电监听
        try {
            phoneStateListener?.let { listener ->
                telephonyManager?.let { tm ->
                    @Suppress("DEPRECATION")
                    tm.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
            }
            phoneStateListener = null
            telephonyManager = null
        } catch (_: Exception) {}
        super.onDestroy()
    }

// endregion 生命周期续

// region 云按钮
    // ======================== 云按钮逻辑 ========================

    /**
     * 检查语音识别是否可用（Zipformer 或 Google）
     */
    private fun isVoiceRecognitionAvailable(): Boolean {
        val bridgeLoaded = SherpaOnnxEngine.isLibraryLoaded()
        val hasVoiceModel = modelManager.hasVoiceModel()
        return bridgeLoaded && hasVoiceModel
    }

    /**
     * 检查是否使用 Google 识别（没有本地模型时用 Google）
     */
    private fun isUsingGoogleRecognition(): Boolean {
        val bridgeLoaded = SherpaOnnxEngine.isLibraryLoaded()
        val hasVoiceModel = modelManager.hasVoiceModel()
        // 没有本地语音模型 → 用 Google
        return !bridgeLoaded || !hasVoiceModel
    }

    /**
     * 检查语音润色是否可用（MNN 本地 或 API 云端）
     */
    private fun isVoicePolishAvailable(): Boolean {
        val mnnAvailable = modelManager.hasAiModel()
        val apiAvailable = !getOpenRouterApiKey().isNullOrEmpty()
        return mnnAvailable || apiAvailable
    }

    /**
     * 检查本地润色是否可用：手机AI模型下载完成且自动自测通过（local_ai_ready）
     */
    private fun isLocalPolishAvailable(): Boolean {
        return getSharedPreferences("cesia_model_status", MODE_PRIVATE)
            .getBoolean("local_ai_ready", false)
    }

    /**
     * 检查云端润色是否可用：任一云端来源（Ollama/OpenRouter）测试通过过（cloud_ready）
     */
    private fun isCloudPolishAvailable(): Boolean {
        return getSharedPreferences("cesia_model_status", MODE_PRIVATE)
            .getBoolean("cloud_ready", false)
    }

    /**
     * 更新云按钮和语音按钮的状态
     */
    private fun updateCloudButtonState() {
        val recognitionAvailable = isVoiceRecognitionAvailable()
        val polishAvailable = isVoicePolishAvailable()
        val usingGoogle = isUsingGoogleRecognition()
        val localPolish = isLocalPolishAvailable()
        val cloudPolish = isCloudPolishAvailable()

        // 语音输入按钮
        micButton?.let { btn ->
            btn.isEnabled = recognitionAvailable
            btn.alpha = if (recognitionAvailable) 1.0f else 0.4f
        }

        // 云按钮（本地/云端切换：本字=本地模式，云字=云端模式）
        btnCloud?.let { btn ->
            val localReady = isLocalPolishAvailable()
            val cloudReady = isCloudPolishAvailable()
            // 默认本字（本地模式）；本字高亮=local_ai_ready，云字高亮=cloud_ready
            val isLocal = (cloudMode == CloudMode.LOCAL || cloudMode == CloudMode.LOCAL_LOCKED)
            when {
                !recognitionAvailable -> {
                    btn.isEnabled = false; btn.alpha = 0.4f
                    btn.text = if (isLocal) "本" else "云"
                    btn.setTextColor(0xFF888888.toInt())
                }
                !polishAvailable -> {
                    btn.isEnabled = false; btn.alpha = 0.4f
                    btn.text = if (isLocal) "本" else "云"
                    btn.setTextColor(0xFF888888.toInt())
                }
                else -> {
                    // 本字 / 云字 都可点，但灰度取决于对应模型是否 ready
                    btn.isEnabled = true
                    btn.alpha = 1.0f
                    if (isLocal) {
                        btn.text = "本"
                        btn.setTextColor(if (localReady) themeAccent else 0xFF888888.toInt())
                    } else {
                        btn.text = "云"
                        btn.setTextColor(if (cloudReady) themeAccent else 0xFF888888.toInt())
                    }
                }
            }
        }

        // 保存状态到 SharedPreferences
        saveCloudMode()
    }

    /**
     * 保存云按钮状态
     */
    private fun saveCloudMode() {
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        prefs.edit().putString("cloud_mode", cloudMode.name).apply()
    }

    /**
     * 加载云按钮状态
     */
    private fun loadCloudMode() {
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        val savedMode = prefs.getString("cloud_mode", CloudMode.LOCAL.name)
        cloudMode = try {
            CloudMode.valueOf(savedMode ?: CloudMode.LOCAL.name)
        } catch (e: Exception) {
            CloudMode.LOCAL
        }
    }

    /**
     * 云按钮点击：切换本地/云端
     */
    private fun onCloudButtonClick() {
        if (!btnCloud.isEnabled) return
        val localReady = isLocalPolishAvailable()
        val cloudReady = isCloudPolishAvailable()
        val isLocal = (cloudMode == CloudMode.LOCAL || cloudMode == CloudMode.LOCAL_LOCKED)
        if (isLocal) {
            // 本字 → 切云字
            if (cloudReady) {
                cloudMode = CloudMode.CLOUD
                updateStatus("☁️ 已切换到云端润色模式")
            } else {
                updateStatus("⚠️ 云端模型未通过测试，请到设置页测试 Ollama / OpenRouter")
            }
        } else {
            // 云字 → 切本字
            if (localReady) {
                cloudMode = CloudMode.LOCAL
                updateStatus("🏠 已切换到本地润色模式")
            } else {
                // 本字灰度：已下载则直接点亮，未下载则触发下载（键盘状态栏显示进度）
                if (modelManager.hasAiModel()) {
                    markLocalAiReadyInIme()
                } else {
                    updateStatus("⚠️ 手机AI模型未下载，正在开始下载...")
                    promptDownloadPhoneAi()
                }
            }
        }
        updateCloudButtonState()
    }

    /** 本字灰度时点击：直接触发手机AI模型下载（键盘状态栏显示进度，与设置页同步） */
    private fun promptDownloadPhoneAi() {
        val modelInfo = ModelRegistry.getById("qwen35-2b-mnn")
            ?: ModelRegistry.ALL_MODELS.find { it.type == ModelInfo.ModelType.AI } ?: run {
                updateStatus("⚠️ 无可用手机AI模型定义")
                return
            }
        DownloadProgressBus.emit("手机AI模型", 0.0)
        Thread {
            try {
                val dm = ModelDownloadManager(this@CesiaInputMethod)
                val result = kotlinx.coroutines.runBlocking {
                    dm.downloadAiModel(modelInfo) { _: String, percent: Double, _: Long, _: Long ->
                        val txt = "下载中：手机AI模型 ${String.format("%.1f", percent)}%"
                        updateStatus(txt)
                        DownloadProgressBus.emit("手机AI模型", percent)
                    }
                }
                if (result.isSuccess) {
                    updateStatus("✅ 手机AI模型下载完成，本字已点亮（可到设置页测试）")
                    markLocalAiReadyInIme()
                } else {
                    updateStatus("❌ 手机AI模型下载失败：${result.exceptionOrNull()?.message}")
                    DownloadProgressBus.emit("手机AI模型", 0.0, failed = true)
                }
            } catch (e: Exception) {
                updateStatus("❌ 手机AI模型下载异常：${e.message}")
                DownloadProgressBus.emit("手机AI模型", 0.0, failed = true)
            }
        }.start()
    }

    /** 手机AI模型已就绪：标记点亮本字（真正的推理自测在设置页做，避免 IME 内加载大模型导致卡死） */
    private fun markLocalAiReadyInIme() {
        getSharedPreferences("cesia_model_status", MODE_PRIVATE).edit().putBoolean("local_ai_ready", true).apply()
        cloudMode = CloudMode.LOCAL
        updateStatus("✅ 手机AI模型已就绪，本字已点亮")
        updateCloudButtonState()
    }

    /**
     * 云按钮长按：锁定本地模式
     */
    private fun onCloudButtonLongClick() {
        val localPolish = isLocalPolishAvailable()
        val cloudPolish = isCloudPolishAvailable()

        if (!localPolish || !cloudPolish) {
            updateStatus("⚠️ 需要 MNN 和 API 都可用才能锁定")
            return
        }

        if (cloudMode == CloudMode.LOCAL_LOCKED) {
            // 已锁定 → 解锁
            cloudMode = CloudMode.LOCAL
            updateStatus("🔓 已解锁，恢复默认本地模式")
        } else {
            // 锁定本地
            cloudMode = CloudMode.LOCAL_LOCKED
            updateStatus("🔒 已锁定本地模式（MNN + Zipformer）")
        }
        updateCloudButtonState()
    }

    /**
     * 获取当前润色模式是否为本地
     * 供语音润色时判断使用 MNN 还是 OpenRouter
     */
    fun isLocalPolishMode(): Boolean {
        return cloudMode == CloudMode.LOCAL || cloudMode == CloudMode.LOCAL_LOCKED
    }

    /**
     * 统一 AI 润色入口（语音命令词、魔法书、AI回复共用）
     * 根据当前模式自动选择本地 MNN 或云端 OpenRouter
     * @param text 原文
     * @param instruction 润色指令（如"润色"、"改成正式语气"等）
     * @param callback 回调 (润色结果, 是否成功)
     */
    fun executePolish(text: String, instruction: String, callback: (String, Boolean) -> Unit) {
        if (text.isBlank()) {
            callback("", false)
            return
        }
        val useLocal = isLocalPolishMode() && modelManager.hasAiModel()
        Log.i("Cesia", "executePolish: text='${text.take(50)}', instruction='$instruction', useLocal=$useLocal")
        if (useLocal) {
            // 本地 MNN 润色
            voiceEngineScope.launch {
                try {
                    val modelFile = modelManager.getInstalledAiModelFile()
                    if (modelFile == null || !modelFile.exists()) {
                        withContext(Dispatchers.Main) { callback(text, false) }
                        return@launch
                    }
                    if (!aiEngine.isModelLoaded()) {
                        val configPath = if (modelFile.isDirectory) {
                            File(modelFile, "config.json").absolutePath
                        } else {
                            modelFile.absolutePath
                        }
                        val loaded = aiEngine.loadLocalModel(configPath)
                        if (!loaded) {
                            withContext(Dispatchers.Main) { callback(text, false) }
                            return@launch
                        }
                    }
                    val prompt = buildPolishPrompt(text, instruction)
                    val result = aiEngine.polish(prompt, instruction)
                    withContext(Dispatchers.Main) {
                        callback(result ?: text, result != null)
                    }
                } catch (e: Exception) {
                    Log.e("Cesia", "本地润色失败", e)
                    withContext(Dispatchers.Main) { callback(text, false) }
                }
            }
        } else {
            // 云端 OpenRouter 润色（同步 API，需在后台线程调用）
            val prompt = buildPolishPrompt(text, instruction)
            voiceEngineScope.launch(Dispatchers.IO) {
                try {
                    val result = typelessEngine?.getPolishService()?.polishWithPrompt(prompt)
                    withContext(Dispatchers.Main) {
                        callback(result ?: text, !result.isNullOrEmpty())
                    }
                } catch (e: Exception) {
                    Log.e("Cesia", "云端润色失败", e)
                    withContext(Dispatchers.Main) { callback(text, false) }
                }
            }
        }
    }

    /** 构建润色 prompt（本地和云端统一） */
    private fun buildPolishPrompt(text: String, instruction: String): String {
        // 匹配到标准指令时用标准化 prompt
        val std = com.cesia.input.instruction.InstructionSet.findByKeywords(instruction)
        if (std != null) {
            return com.cesia.input.instruction.InstructionSet.buildPrompt(std, text)
        }
        return "原文：$text\n\n指令：$instruction\n\n请根据指令处理原文，只输出处理后的文本，不要输出任何解释。"
    }

// endregion 云按钮

// region 语音锁定
    // ======================== 语音锁定模式 ========================

    /**
     * 切换语音锁定模式（长按语音键）
     */
    private fun toggleVoiceLockMode() {
        if (isVoiceLocked) {
            // 已锁定 → 退出锁定
            isVoiceLocked = false
            updateMicButtonLockedState()
            updateStatus("🔓 已退出语音锁定模式")
        } else {
            // 未锁定 → 进入锁定，直接录音（不分裂按钮）
            val recognitionAvailable = isVoiceRecognitionAvailable()
            if (!recognitionAvailable) {
                updateStatus("⚠️ 语音识别不可用，无法进入锁定模式")
                return
            }
            isVoiceLocked = true
            updateMicButtonLockedState()
            // 显示语音锁定模式提示（命令词）
            showVoiceLockHints()
            updateStatus("🔒 已进入语音锁定模式，说话后自动处理")
            // 锁定模式直接录音，不分裂按钮
            startRecordingLocked()
        }
    }

    /**
     * 更新语音键的锁定状态显示
     */
    private fun updateMicButtonLockedState() {
        micButton?.let { btn ->
            if (isVoiceLocked) {
                // 锁定状态：高亮显示 + 脉冲发光动画
                btn.background = makeKeyBgDrawable(themeAccent)
                btn.setTextColor(0xFFFFFFFF.toInt())
                btn.elevation = 6f
                btn.translationZ = 12f // 置于所有层之上
                startMicButtonGlow()
            } else {
                // 正常状态：恢复主题背景 + 最高层级
                btn.background = makeKeyBgDrawable(currentKeyBg)
                btn.setTextColor(unifiedTextColor)
                btn.elevation = 4f
                btn.translationZ = 8f // 在功能键层之上
                stopMicButtonGlow()
            }
        }
    }

    /**
     * 语音命令词检测
     * 检查文本末尾是否包含 "aiover"、"ai over" 或 "over"
     * 返回 Pair(命令词前的文本, 命令词类型) 或 null
     * 命令词类型: "ai" 表示 aiover/ai over, "plain" 表示 over
     */

// endregion 语音锁定

// region UI辅助
    // ======================== UI 辅助 ========================

    private fun setStatusDot(state: String) {
        statusDotState = state
        redrawStatusDot()
    }

    /** 按当前 statusDotState 重绘圆点（颜色随主题色实时变化，无需重启） */
    private fun redrawStatusDot() {
        if (!::statusDot.isInitialized) return
        try {
            val color = when (statusDotState) {
                "recording" -> themeAccent
                "processing" -> 0xFFFF9800.toInt() // orange
                "error" -> 0xFFF44336.toInt()    // red
                else -> themeAccent               // 空闲：主题色圆点
            }
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setSize(12, 12)
            }
            statusDot.background = drawable
        } catch (_: Exception) {}
    }

    /** 显示语音命令词提示 */
    private fun showVoiceCommandHints() {
        val hints = VoiceEngine.getCommandHints()
        if (hints.isNotEmpty() && ::statusText.isInitialized) {
            updateStatus("💡 $hints")
        }
    }

    /** 显示语音锁定模式提示 */
    private fun showVoiceLockHints() {
        val hints = VoiceEngine.getCommandHints()
        if (hints.isNotEmpty() && ::statusText.isInitialized) {
            updateStatus("🔒 语音锁定命令：$hints")
        }
    }

    private var statusLines = mutableListOf<String>()

    private fun updateStatus(msg: String) {
        Log.d("Cesia", "updateStatus: msg='$msg', isRecording=$isRecording, lines=${statusLines.size}")
        try {
            if (isRecording) {
                if (msg.startsWith("🎤") || msg.startsWith("⏳") || msg.startsWith("🔄") || msg.startsWith("")) {
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
