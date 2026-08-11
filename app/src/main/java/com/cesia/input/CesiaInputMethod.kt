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
import com.cesia.input.command.CategorizedCommandMenu
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
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ScrollView
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
import com.cesia.input.engine.PinyinMap
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

    /**
     * 调试日志开关（release 关闭）。
     * 原来按键热路径上的 Log.d 会强制求值 rimeEngine.composingText / candidates 等
     * 跨 JNI 属性并做字符串拼接，即使日志不输出也照样耗时——用 inline + 常量条件后，
     * release 编译期直接消除整个调用（含参数求值）。
     */
    internal inline fun dlog(msg: () -> String) {
        if (DEBUG_LOG) Log.d("Cesia", msg())
    }

    // 单线程 Executor，用于串行执行 Rime 引擎操作（防止多线程并发崩溃）
    private val rimeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ======================== 视图 ========================
    private lateinit var keyboardView: CesiaKeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolKeyboardCn: Keyboard
    private lateinit var numberKeyboard: Keyboard
    private var currentKeyboard: Keyboard? = null

    private lateinit var micButton: MaterialButton
    private lateinit var micButtonContainer: LinearLayout
    private lateinit var btnMicAi: MaterialButton
    private lateinit var btnMicNoAi: MaterialButton
    private lateinit var tvMicZh: TextView          // 语音键右上角“中”副字符（仅纯中文模式显示）
    private lateinit var tvMicModeBi: TextView        // 语音键左上角「中英」
    private lateinit var tvMicModeZh: TextView        // 语音键左上角「纯中」
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
        // 替换 backgroundTint
        val bgTint = try { view.backgroundTintList?.defaultColor ?: 0 } catch (_: Exception) { 0 }
        if (bgTint == tiffany) view.backgroundTintList = tintList
        // 替换 background（如果是 ColorDrawable 且颜色是 tiffany）
        if (view.background is android.graphics.drawable.ColorDrawable) {
            val colorDrawable = view.background as android.graphics.drawable.ColorDrawable
            val bgColor = colorDrawable.color
            if (bgColor == tiffany) {
                view.background = android.graphics.drawable.ColorDrawable(accent)
            }
        }
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

    /**
     * 统一的弹窗暗色化：暗色模式下，把弹窗整棵 view 树里的浅色表面换成深色、
     * 文字按「实际背景明暗」自动取对比色（深底→浅字，浅底→深字），确保文字永远可见。
     * 覆盖所有弹窗（主题/智能写作/智能修改/剪贴板/候选长按/符号面板/字符库等）。
     * 仅在 isDarkTheme 时生效。
     */
    private fun applyDarkThemeToViewTree(root: android.view.View) {
        if (!isDarkTheme) return
        val darkSurface = 0xFF1E1E2E.toInt()
        val darkSurfaceAlt = 0xFF2A2A3E.toInt()
        val darkDivider = 0xFF3A3A4E.toInt()
        val darkText = 0xFFE0E0E0.toInt()
        val lightText = 0xFF333333.toInt()
        fun gray(v: Int) = ((v shr 16) and 0xFF)
        fun isGrayish(v: Int) = run { val r=(v shr 16) and 0xFF; val g=(v shr 8) and 0xFF; val b=v and 0xFF; r==g && g==b }
        // 取一个 Drawable 的实色（ColorDrawable / 纯色 GradientDrawable），否则返回 null
        fun solidColor(d: android.graphics.drawable.Drawable?): Int? {
            return when (d) {
                is android.graphics.drawable.ColorDrawable -> d.color
                is android.graphics.drawable.GradientDrawable -> try { d.color?.defaultColor } catch (_: Exception) { null }
                else -> null
            }
        }
        fun walk(v: android.view.View) {
            // 背景：浅灰→深；灰色分割线→暗描边
            val sc = solidColor(v.background)
            if (sc != null && isGrayish(sc)) {
                val g = gray(sc)
                when {
                    g > 235 -> v.setBackgroundColor(darkSurface)      // 近白
                    g in 215..235 -> v.setBackgroundColor(darkSurfaceAlt) // 浅灰
                    g in 195..215 -> v.setBackgroundColor(darkDivider)   // 灰分割线
                }
            }
            // 文字：按「实际背景明暗」选对比色（避免浅字落在浅底上不可见）
            if (v is android.widget.TextView) {
                val def = try { v.textColors?.defaultColor ?: 0 } catch (_: Exception) { 0 }
                val bg = solidColor(v.background) ?: (v.parent as? android.view.View)?.let { solidColor(it.background) }
                val eff = bg ?: (if (isDarkTheme) darkSurface else 0xFFFFFFFF.toInt())
                v.setTextColor(if (gray(eff) < 128) darkText else lightText)
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
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
    private lateinit var btnPanelRefresh: ImageButton
    private lateinit var flCandidates: CesiaFlowLayout
    private lateinit var scrollCandidates: ScrollView
    private var panelChips: MutableList<TextView> = mutableListOf()
    private var isPanelExpanded = false

    // ===== 候选面板新闻模式（空输入时把下拉面板变成 RSS 阅读器）=====
    // 设计原则：默认关闭；仅在「面板展开 且 完全无输入」时显示；一有拼音输入立刻切回候选词；
    // 按需刷新（展开时缓存过期才抓），绝不后台定时轮询，零耗电零流量。
    /** 当前面板是否处于新闻模式（决定点击行为与列数） */
    private var isNewsMode = false
    /** 新闻列表：标题 + 链接 */
    private var newsItems: List<RssFetchManager.NewsItem> = emptyList()
    /** 顶栏是否正处于新闻首条展示态（区别于拼音候选，决定点击行为） */
    private var newsBarActive = false
    /** 顶栏新闻滚动位置（每次重新进入新闻态推进一条，循环浏览） */
    private var newsBarIndex = 0
    /** 上一次 updateCandidateBar 是否处于新闻态（用于判定「切回新闻态」以推进滚动） */
    private var wasNewsMode = false
    /** 顶栏新闻「逐字左删」跑马灯：从左侧逐字删，删空后回到完整标题循环 */
    private val newsMarqueeHandler = Handler(Looper.getMainLooper())
    private var newsMarqueeRunnable: Runnable? = null
    private var newsMarqueeTitle = ""      // 当前新闻完整标题
    private var newsMarqueeOffset = 0      // 已从左删除的字符数
    /** 正在抓取，避免重复请求 */
    private var isNewsFetching = false
    /** 缓存有效期：30 分钟内不重复抓取 */
    private val newsCacheTtlMs = 30 * 60 * 1000L

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
                updateStatus("无法切换到本地模式")
                return
            }
            if (!hasVoiceModel) {
                updateStatus("无法切换到本地模式")
                return
            }
            if (!hasAiModel) {
                updateStatus("无法切换到本地模式")
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
    // === 英文拼写模式（状态栏中/英按钮切换，持久化）===
    private var isEnglishMode = false
    private var englishDict: com.cesia.input.engine.EnglishDictLoader? = null
    private var enBuffer = StringBuilder()          // 当前英文输入串（QWERTY 字母 / T9 数字）
    private var enCandidates = emptyList<String>() // 当前英文候选词
    private var enMode: KeyboardMode? = null        // 进入英文时的键盘模式（区分 QWERTY/T9 匹配方式）
    private var shortPressHandled = false  // 当前按键是否已处理短按（防止长按重复触发）
    // === 词语联想 ===
    private var associationPrefix = ""      // 当前联想前缀（如 "这个"）
    private var associationCandidates = emptyList<String>()  // 当前联想候选词列表（当前已加载的）
    private var isAssociationMode = false   // 是否处于联想模式
    private var selectedCandidateIndex = 0   // 当前长按选中的候选词 index（用于菜单定位）
    // 联想懒加载状态
    private var assocPageWalk = 10
    private var assocTotalLoaded = 0
    private var lastAssocPrefix = ""
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
    // 逐词组词时累积的「整串短语」（跨选词不清），仅用于数字全部用完时整体写入用户词库；
    // 不参与任何显示/候选逻辑，故不影响拼音对应。
    private var t9FullPhrase = StringBuilder()
    // 用户词组召回：当前数字码前缀匹配到的存词（仅显示用，点击走直接上屏语义）
    private var t9RecalledPhrase: String? = null
    // 逐键选音：已按的数字队列 + 已选字母前缀（合计即原始数字串长度）
    private var t9DigitQueue = StringBuilder()    // 已按数字顺序，如 "97"
    // 注：自研「接龙消费计数(t9ConsumedLen)」已删除。多音节切分/续写全部交给 Rime 原生
    // (schema enable_sentence)，选词后是否还有剩余音节一律看 rimeEngine.isComposing。
    // 用户自建词组库：词 → (该词所有有效 T9 数字码集合, 频次)。
    // 数字码包括：组词时按下的原始数字串、全拼反推码、简拼首字母反推码。
    // 匹配时任一码以当前输入为前缀即注入候选，从而全拼/简拼/任意前缀都能命中该词。
    private data class UserPhraseEntry(val codes: MutableSet<String>, var freq: Int)
    private val userPhrases = LinkedHashMap<String, UserPhraseEntry>()
    // 候选懒加载：candPageWalk=已扫页数(10页=50候选)，滚到底+10页(50)；上限 MAX_PAGE_WALK(50页=250)
    private var candPageWalk = 10
    private var candTotalLoaded = 0
    private var lastPagerInputSig = ""
    // 连续按键数上限（单击数字键计数，非中文字数）：到达后提示上限、不再累积新键。
    // 配合 schema max_code_length=8，Rime 只解码末 ≤8 位，25 键内不卡（含退格）。
    private val MAX_T9_KEYS = 25
    private var t9SpellPrefix = StringBuilder()   // 已选字母，如 "ws"
    // 候选音区(逐键选音)当前指向的数字串位置 = 已组词对应的数字位数 + 当前音节已选字母数。
    // 已组词位数由 t9ComposedSoFar 的真实读音反推（PinyinMap→T9 数字码），替代已删除的 t9ConsumedLen 消费语义，
    // 使选完一个音节(如 946→辛)后候选音区仍能推进到下一音节的数字键。PinyinMap 未加载时回退 0（候选音区从头，可接受）。
    private val t9SpellCursor: Int get() {
        val composed = t9ComposedSoFar.toString()
        val composedDigitLen = if (composed.isEmpty()) 0 else {
            val py = try { PinyinMap.toFull(composed) } catch (_: Exception) { "" }
            if (py.isEmpty()) 0 else pinyinToDigits(py).length
        }
        return (composedDigitLen + t9SpellPrefix.length).coerceIn(0, t9DigitQueue.length)
    }
    private var t9FenCiOn = false                 // 分词开关：默认关=全拼（数字直连）；开=简拼（数字间加分词符）
    private var t9FenCiLock = false               // 全拼/简拼按钮双击锁定（防误触），持久化，默认不锁
    private var t9FenCiLastClick = 0L             // 1键上次单击时间戳（双击检测）
    private var t9FenCiPendingSingle: Runnable? = null  // 单击待执行的切换任务（延迟以等待可能的双击）
    private var t9FenCiMerged: List<String> = emptyList()  // 简拼模式合并后的候选（分词符串 + 字母组合交叉），供 UI/点击使用
    // 单键单字：枚举该键所有字母(a/b/c)取单字候选合并，供 UI/点击使用（跟随选音锁定字母变化）
    private var t9SingleKeyCands: List<String> = emptyList()
    // ===== 逐音节接龙消费（数字段 → 单字/词）=====
    // 已消费的数字位数（从 t9DigitQueue 头部）：选中单字/词按「该词真实读音反推的数字码」消费对应位数，
    // 剩余 tail = substring(t9ConsumedLen) 才是还没确定的部分。只有 tail 为空才结束本次组合，
    // 从而「所有数字用过才结束」，不再出现「选第一个字就直接上屏结束」的早退 bug。
    private var t9ConsumedLen = 0
    // 当前待定段数字（如选 cai 后 tail=7474，Rime 首音节 qi 反推 74 → 待定段=74）。
    // 单字枚举就针对这一段：列出 74 能拼的全部合法拼音(pi/qi/ri/si)各自单字(皮起思日…)。
    private var t9PendingSeg: String = ""
    // 当前待定段已枚举出的单字（注入主候选流、可懒加载翻页），跟随选音锁定(t9SpellPrefix)收窄。
    private var t9PendingChars: MutableList<String> = mutableListOf()
    private var t9PendingPageWalk = 4
    private var t9PendingBusy = false
    // 待定段+选音锁定签名：变化时整段重拉单字，避免旧段单字(皮日思)残留。
    private var lastPendingSig: String = ""
    // 单字回归主候选流：词在上、待定段单字在下，同处一个流、拖拽懒加载走 loadMoreCandidates()。
    // 上次喂给 Rime 会话的 feed 串（增量喂判断依据）：新 feed 以其为前缀→只增量喂新增部分，
    // 否则（退格/切简拼全拼/提交后队列变化）整串重放。避免每键重放“整个数字队列”导致长码 O(n²) 卡顿。
    private var lastT9Feed: String? = null
    private var pendingEnglish = ""               // 英文模式下已直接上屏的连续英文字母缓冲（按数字时连同数字一起上屏）

    private var calcExpr = StringBuilder()
    // 用户词表重新部署的防抖 Runnable（接龙整词写入 cesia_user.dict.yaml 后触发）
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

    // 智能写作当前激活命令（最近使用/点击执行的命令，排在列表第 1 项，带 ✓）
    private var currentSmartPrompt: String? = null

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
    private var clipboardSearchActive = false  // 搜索编辑进行中（复用 smartEditMode 输入法）
    private var clipboardSearchResuming = false  // 搜索回车后重新弹出菜单时，保留已输入过滤词
    // 已删除（防复活）的文本集合：持久化到 SharedPreferences，load 时跳过这些文本（含系统剪贴板里残留的）
    private var clipboardDeleted = mutableSetOf<String>()
    private fun loadClipboardDeleted() {
        val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
        val s = prefs.getString("deleted", "") ?: ""
        clipboardDeleted = if (s.isNotEmpty()) s.split("\n").toSet().toMutableSet() else mutableSetOf()
    }
    private fun saveClipboardDeleted() {
        val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
        prefs.edit().putString("deleted", clipboardDeleted.joinToString("\n")).apply()
    }
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
        // 2. 拼音匹配：将文本转为拼音首字母和全拼进行匹配（宽松模式：生僻字跳过不影响整体匹配）
        val pinyinFirst = PinyinMap.toFirstLettersLoose(text)
        val pinyinFull = PinyinMap.toFullLoose(text)
        return pinyinFirst.contains(f, ignoreCase = true) || pinyinFull.contains(f, ignoreCase = true)
    }

    /** 将中文转为拼音首字母（如：你好 -> nh）。造词登记用严格模式：有生僻字查不到即返回空串，
     *  避免登记半截错码污染用户词库。 */
    private fun toPinyinFirstLetters(text: String): String = PinyinMap.toFirstLetters(text)

    /** 将中文转为全拼（如：孙珺 -> sunjun）。严格模式，理由同上。 */
    private fun toPinyinFull(text: String): String = PinyinMap.toFull(text)

    /** 获取单个汉字的拼音首字母（真实读音，来自 assets/pinyin_dict.json） */
    private fun getPinyinFirstLetter(c: Char): String = PinyinMap.firstLetter(c)

    /** 获取单个汉字的全拼（真实读音，来自 assets/pinyin_dict.json） */
    private fun getPinyinFull(c: Char): String = PinyinMap.full(c)

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
    enum class KeyboardMode { QWERTY, SYMBOL_CN, NUMBER }

// endregion 状态变量

// region 简繁切换
    // ======================== 简繁切换 ========================
    private var isTraditional = false
    private lateinit var btnTraditional: TextView
    private lateinit var btnEnMode: TextView       // 状态栏中/英切换按钮（双击空格等价）

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
        updateStatus("已转换 ${selectedText.length} 字")
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
        /** 调试日志总开关：release 置 false，编译期消除热路径日志与其参数求值 */
        internal const val DEBUG_LOG = false

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
        // 预加载真实汉字拼音表（assets/pinyin_dict.json，16472 字）：
        // 用户造词登记全拼码/简拼码依赖它。后台加载，不阻塞输入法启动。
        PinyinMap.preload(this)
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
        tvMicModeBi = view.findViewById(R.id.tv_mic_mode_bi)
        tvMicModeZh = view.findViewById(R.id.tv_mic_mode_zh)
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
        btnEnMode = view.findViewById(R.id.btn_en_mode)
        btnEnMode.setOnClickListener { toggleEnglishMode() }

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
                // 新闻态：顶栏当前新闻（随切换滚动）点击 → 直接打开浏览器
                if (newsBarActive) { openNewsLink(newsBarIndex % maxOf(newsItems.size, 1)); return@CandidateAdapter }
                if (rimeEngine.hasCandidates || isAssociationMode || isEnglishMode) {
                    selectCandidateByGlobalIndex(index)
                    // 全键盘模式：点击候选词上屏后必须清除 Rime composing 状态
                    if (keyboardMode == KeyboardMode.QWERTY && !isEnglishMode) {
                        rimeEngine.clear()
                    }
                }
            },
            onItemLongClick = { view, index, word ->
                if (newsBarActive) { showNewsItemMenu(view, newsBarIndex % maxOf(newsItems.size, 1)); true }
                else { showCandidateLongPressMenu(word, view, index); true }
            }
        )
        candidateAdapter?.onInteract = { stopNewsMarquee() }   // 顶栏点击/长按即停止新闻滚动
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
                    if (isAssociationMode) {
                        loadMoreAssociations()
                    } else {
                        loadMoreCandidates()
                    }
                }
            }
        })

        // T9 字母区已移除（不再显示英文字母和分隔线）

        // 候选面板视图
        candidatePanel = view.findViewById(R.id.candidate_panel)
        tvPanelComposing = view.findViewById(R.id.tv_panel_composing)
        btnPanelClose = view.findViewById(R.id.btn_panel_close)
        btnPanelRefresh = view.findViewById(R.id.btn_panel_refresh)
        flCandidates = view.findViewById(R.id.fl_candidates)
        scrollCandidates = view.findViewById(R.id.scroll_candidates)
        // 面板滚动到底部附近：主候选流懒加载（单字与词同源，拖动即继续翻页出更多单字/词）。
        // 不再有独立「单字专区」段——单字就在主候选流里，由 loadMoreCandidates() 统一增量翻页。
        scrollCandidates.viewTreeObserver.addOnScrollChangedListener {
            val sv = scrollCandidates
            val child = sv.getChildAt(0) ?: return@addOnScrollChangedListener
            val diff = child.bottom - (sv.height + sv.scrollY)
            if (diff <= sv.height / 2 && !isAssociationMode) {
                if (scrollCandidates.tag == "busy") return@addOnScrollChangedListener
                scrollCandidates.tag = "busy"
                loadMoreCandidates()
                scrollCandidates.tag = null
            }
        }

        // 初始化键盘
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        try {
            symbolKeyboardCn = Keyboard(this, R.xml.symbols)
        } catch (e: Exception) {
            Log.e("Cesia", "加载符号键盘失败", e)
            symbolKeyboardCn = qwertyKeyboard
        }
        // 读取符号键盘主/副翻转偏好
        symbolFlipped = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getBoolean(SYMBOL_FLIP_PREF, false)
        try {
            numberKeyboard = Keyboard(this, R.xml.number)
            dlog { "number 键盘加载成功" }
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
        currentSmartPrompt = getSharedPreferences("cesia_smart_records", MODE_PRIVATE)
            .getString("active_instruction", null)

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
                            dlog { "语法大纲自动更新: 当前记录数=$recordCount, 上次更新=${guideMgr.lastRecordCount}" }
                            val records = statsManager.getRecords()
                            val newGuide = guideMgr.generateGuide(records) { text, instruction ->
                                typelessEngine?.getPolishService()?.polishWithPrompt(text)
                            }
                            if (!newGuide.isNullOrEmpty()) {
                                guideMgr.saveGuide(newGuide)
                                guideMgr.updateRecordCount(recordCount)
                                dlog { "语法大纲自动更新成功: 版本=${guideMgr.version}, 长度=${newGuide.length}" }
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
                    updateStatus("已完成")
                }
            }
            engine.onRecognitionComplete = { text ->
                Handler(Looper.getMainLooper()).post {
                    // 语音结果统一转阿拉伯数字（Google 路径兜底，与本地 sherpa 路径一致）
                    val text = voiceEngine.convertChineseDigitsToArabic(text)
                    // 魔法模式停止时，直接用 Google 识别结果触发 AI
                    if (magicStopRequested) {
                        dlog { "onRecognitionComplete: 魔法模式停止中，直接触发 AI" }
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
                            updateStatus("未识别到文字")
                            resetToIdle()
                            return@post
                        }

                        if (command == "ai") {
                            updateStatus("AI正在处理中")
                            setStatusDot("processing")
                            isProcessingResult = true
                            polishRecognizedText(textBefore)
                        } else if (command == "writing") {
                            // 写作命令：延迟1秒执行智能写作
                            updateStatus("AI正在处理中")
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
                            // 语音识别文字上屏：繁体模式转繁体
                            val outText = if (isTraditional) toTraditional(textBefore) else textBefore
                            currentInputConnection?.commitText(outText, 1)
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
                            updateStatus("未识别到文字")
                            resetToIdle()
                        } else {
                            updateStatus("AI正在处理中")
                            setStatusDot("processing")
                            isProcessingResult = true
                            polishRecognizedText(text)
                        }
                    } else if (pendingAiMode == false) {
                        isWaitingForChoice = false
                        hideAiChoiceButtons()
                        if (text.isNotEmpty()) {
                            // 语音识别文字上屏：繁体模式转繁体
                            val outText = if (isTraditional) toTraditional(text) else text
                            currentInputConnection?.commitText(outText, 1)
                        }
                        resetToIdle()
                    } else {
                        if (text.isEmpty()) {
                            updateStatus("未识别到文字")
                            resetToIdle()
                        } else {
                            isWaitingForChoice = true
                            updateStatus("「$text」→ 选择 润色 或 直接上屏")
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

        updateStatus(statusIdleText)
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
     * 启动输入法后自动检测语音文字套件（主件套：中英双语模型 + 雾凇词库）是否已安装。
     * 纯中文模型作为增强包，不在此自动下载（避免与设置页手动安装竞态），由设置页显式触发。
     * 下载进度仅在设置页版本号区域显示（通过 DownloadProgressBus 同步），键盘状态栏不显示进度，仅写入运行日志。
     * 支持前后台下载（不依赖 Activity 生命周期）。
     */
    private fun ensureVoiceSuite() {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val voiceInstalled = modelManager.getInstalledVoiceModelFile() != null
                val dictInstalled = dictManager.hasDownloadedDict()
                // 纯中文模型不在此自动下载，避免与设置页阶段三竞态
                val zhInstalled = java.io.File(filesDir, "local_models/zipformer-zh-2025/encoder.onnx").exists()
                if (voiceInstalled && dictInstalled) {
                    Log.i("Cesia", "语音文字主套件已完整（纯中文模型：${if (zhInstalled) "已装" else "未装"}），跳过自动下载")
                    return@launch
                }
                appendSuiteLog("开始下载语音文字输入套件（Zipformer中英双语 / 雾凇词库）")

                // 模型大小比例：双语 206MB + 词库 50MB = 256MB
                val bilingualSize = 206.0
                val dictSize = 50.0
                val totalSize = bilingualSize + dictSize
                val bilingualWeight = bilingualSize / totalSize  // ~0.805
                val dictWeight = dictSize / totalSize            // ~0.195

                var bilingualPercent = 0.0  // 0-100
                var dictPercent = 0.0       // 0-100

                DownloadProgressBus.emit("语音套件", 0.0)

                // 1) 中英双语模型
                if (!voiceInstalled) {
                    var lastPct1 = -1.0
                    downloadManager.downloadZipformer { _: String, percent: Double, _: Long, _: Long ->
                        if (kotlin.math.abs(percent - lastPct1) >= 0.3) {
                            lastPct1 = percent
                            bilingualPercent = percent  // percent 已是 0-100
                            val totalProgress = bilingualPercent * bilingualWeight + dictPercent * dictWeight
                            DownloadProgressBus.emit("语音模型", totalProgress)
                        }
                    }
                } else {
                    bilingualPercent = 100.0
                }

                // 2) 雾凇词库
                if (!dictInstalled) {
                    val deferred = CompletableDeferred<Boolean>()
                    dictManager.downloadFullDict(
                        onProgress = { percent: Int, _: Long, _: Long, _: String ->
                            dictPercent = percent.toDouble()  // percent 是 0-100
                            val totalProgress = bilingualPercent * bilingualWeight + dictPercent * dictWeight
                            DownloadProgressBus.emit("雾凇词库", totalProgress)
                        },
                        onComplete = { ok: Boolean, _: String ->
                            deferred.complete(ok)
                        }
                    )
                    deferred.await()
                } else {
                    dictPercent = 100.0
                }

                appendSuiteLog("语音文字输入套件已下载完成，可以正常使用")
                DownloadProgressBus.emit("语音套件", 100.0, done = true)
            } catch (e: Exception) {
                Log.e("Cesia", "ensureVoiceSuite 失败: ${e.message}", e)
                appendSuiteLog("❌ 语音套件自动下载失败: ${e.message}")
                DownloadProgressBus.emit("语音套件", 0.0, failed = true)
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

        // 简繁切换：仅在 traditionalGlowing（即正体模式）时随主题刷新文字色；
        // 不 setBackgroundColor，保留圆形脉冲(ripple)效果，与云端/主题按钮统一
        if (::btnTraditional.isInitialized && traditionalGlowing) {
            btnTraditional.setTextColor(accent)
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
        // 注意：主题切换必须无条件刷新所有按钮底色（覆盖语音等待/长按等临时态），
        // 否则黑白切换时这些按钮底色不跟随变化。
        val keyBgDrawable = makeKeyBgDrawable(keyBg)
        btnMagic.background = keyBgDrawable.constantState?.newDrawable()?.mutate() ?: keyBgDrawable
        btnClipboard.background = keyBgDrawable.constantState?.newDrawable()?.mutate() ?: keyBgDrawable
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

        // 若候选下拉菜单正展开，立即按最新主题/灰度重建（背景灰度、按键灰度、文字灰度、字号、主题色同步生效）
        if (::candidatePanel.isInitialized && candidatePanel.visibility == View.VISIBLE) {
            if (isNewsMode) renderNewsList() else updateCandidateBar()
        }

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

        // 候选面板（下拉菜单）背景 + 滚动容器 + 拼音标题色，分别跟随背景灰度 / 主题色
        val panelBg = if (isDarkTheme) 0xFF1A1A2E.toInt() else colorGray(themeBgGrayBase)
        candidatePanel.setBackgroundColor(panelBg)
        scrollCandidates.setBackgroundColor(panelBg)
        tvPanelComposing.setTextColor(themeAccent)
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

        // 候选栏展开面板：直接遍历现有 chip 实时改文字色与背景填充（不依赖重建，做到像背景灰度一样实时）
        if (::flCandidates.isInitialized) {
            val chipTextColor = scaleGray(baseColor, scale)
            val chipFill = if (isDarkTheme) 0xFF2A2C30.toInt() else currentKeyBg
            for (chip in panelChips) {
                chip.setTextColor(chipTextColor)
                val bg = chip.background
                if (bg is GradientDrawable) {
                    bg.setColor(chipFill)
                    bg.setStroke((1 * resources.displayMetrics.density).toInt(), themeAccent)
                }
            }
            flCandidates.invalidate()
            scrollCandidates.invalidate()
        }
        // 主题/暗色切换后重建 chip：让边框描边色(主题色)与填充(暗/亮)即时刷新（兜底）
        if (candidatePanel.visibility == View.VISIBLE) {
            if (isNewsMode) renderNewsList() else updateCandidateBar()
        }

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
        // 暗色模式：整棵弹窗树换深色
        applyDarkThemeToViewTree(view)
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
        // 复选框勾选色跟随当前主题色：原先未设 tint，回退到 theme 的 colorAccent（写死 Tiffany），
        // 导致换了主题色后这里仍是 Tiffany 蓝。文字色也跟随暗色模式。
        checkAutoTime.buttonTintList = android.content.res.ColorStateList.valueOf(themeAccent)
        checkAutoTime.setTextColor(if (isDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt())
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
        // 候选面板用流式布局渲染 chip：每个候选词一个 chip，宽随词长自适应（优先格子适配字词），
        // 长词占更宽格子、短词占窄格子，一行能放几个放几个；文字字号与顶部候选条一致。

        // 收起按钮
        btnPanelClose.setOnClickListener {
            collapseCandidatePanel()
        }
        // 新闻刷新按钮：手动强制重新抓取（绕过缓存 TTL）
        btnPanelRefresh.setOnClickListener {
            if (isNewsMode) forceRefreshNews()
        }
    }

    /** 强制刷新新闻：清空缓存时间戳后重新抓取（手动刷新按钮用） */
    private fun forceRefreshNews() {
        val prefs = getSharedPreferences("cesia_rss_sources", MODE_PRIVATE)
        prefs.edit().putLong("cached_time", 0L).apply()   // 使 TTL 判定为过期
        newsItems = emptyList()
        // 先给个加载提示
        flCandidates.removeAllViews()
        panelChips.clear()
        val chip = makeChip("正在刷新新闻…", fullWidth = true, position = 0, isNews = true)
        flCandidates.addView(chip)
        panelChips.add(chip)
        if (!isNewsFetching) fetchNewsAsync()
    }

    /** 字号档位 → 候选面板/顶栏统一基础字号（与 CandidateAdapter 的 15f*scale 对齐；用户要求再大一些） */
    private fun panelBaseSp(): Float {
        return when (textThemeSize) {
            0 -> 15f
            2 -> 19f
            3 -> 21f
            else -> 17f
        }
    }

    /** 新建一个候选 chip TextView（流式布局里的一个格子） */
    private fun makeChip(text: String, fullWidth: Boolean, position: Int, isNews: Boolean): TextView {
        val dp = resources.displayMetrics.density
        // 动态生成 chip 背景：填充跟随按键灰度，描边跟随主题色
        val chipFill = if (isDarkTheme) 0xFF2A2C30.toInt() else currentKeyBg
        val chipBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * dp   // 圆角缩小（之前 14dp 太圆）
            // 填充：跟随按键灰度（与键盘按键同款灰），让候选格背景受按键灰度控制
            setColor(chipFill)
            // 描边：跟随主题色（Tiffany 等），让边框随主体色变换
            setStroke((1 * dp).toInt(), themeAccent)
        }
        val tv = TextView(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                if (fullWidth) ViewGroup.LayoutParams.MATCH_PARENT
                else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // 横向间距收窄：单行字能一行排下最多约 7 个；纵向留一点呼吸
                setMargins((2 * dp).toInt(), (3 * dp).toInt(), (2 * dp).toInt(), (3 * dp).toInt())
            }
            gravity = if (isNews || fullWidth) (Gravity.CENTER_VERTICAL or Gravity.START)
                      else Gravity.CENTER
            // 内边距：横向收窄让单字更紧凑，纵向保留舒展
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            maxLines = if (isNews || fullWidth) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, panelBaseSp())
            setTextColor(scaleGray(unifiedTextColor, textGrayScale))
            background = chipBg
            this.text = text
            isClickable = true
            isFocusable = true
            val ctx = this
            var longPressed = false
            setOnClickListener {
                if (longPressed) { longPressed = false; return@setOnClickListener }  // 长按已处理，吞掉紧随的单击
                if (isNews) openNewsLink(position)
                else {
                    selectCandidateByGlobalIndex(position)
                    if (keyboardMode == KeyboardMode.QWERTY) rimeEngine.clear()
                }
            }
            setOnLongClickListener {
                if (isNews) { showNewsItemMenu(ctx, position); longPressed = true }
                else { showCandidateLongPressMenu(text.toString(), ctx, position); longPressed = true }
                true   // 消费长按，阻止后续单击触发打开链接
            }
        }
        return tv
    }

    /** 把候选词列表渲染进面板（流式 chip，宽随词长）。单字与词同处主候选流，拖拽懒加载走 loadMoreCandidates()。 */
    private fun renderCandidatesToPanel(words: List<String>) {
        flCandidates.removeAllViews()
        panelChips.clear()
        val ctx = this
        words.forEachIndexed { idx, w ->
            val chip = makeChip(w, fullWidth = false, position = idx, isNews = false)
            flCandidates.addView(chip)
            panelChips.add(chip)
        }
        // 单条太宽放不进一行时自动缩字（仅在整行只放得下它本身时触发，优先格子适配）
        postShrinkChipsIfNeeded()
    }

    /** 当某个 chip 自身宽度就超过一行可容纳宽度时，按比例缩小该 chip 文字（最后手段） */
    private fun postShrinkChipsIfNeeded() {
        flCandidates.post {
            val avail = flCandidates.width - flCandidates.paddingLeft - flCandidates.paddingRight
            if (avail <= 0) return@post
            for (chip in panelChips) {
                val w = chip.measuredWidth
                if (w > avail && w > 0) {
                    val ratio = avail.toFloat() / w
                    val cur = chip.textSize
                    val target = cur * ratio * 0.96f
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, target.coerceAtLeast(10f * resources.displayMetrics.density))
                    chip.maxLines = 1
                }
            }
        }
    }

    /** 新闻列表渲染进面板（每条独占一行，字号与候选条一致） */
    private fun renderNewsList() {
        if (newsItems.isEmpty()) return
        flCandidates.removeAllViews()
        panelChips.clear()
        newsItems.forEachIndexed { idx, item ->
            val chip = makeChip("${idx + 1}. ${item.title}", fullWidth = true, position = idx, isNews = true)
            flCandidates.addView(chip)
            panelChips.add(chip)
        }
    }

    // ===================== 候选面板新闻模式 =====================

    /** 新闻模式总开关（设置页控制，默认关闭 —— 输入法联网属敏感操作，须用户主动开启） */
    private fun isNewsPanelEnabled(): Boolean =
        getSharedPreferences("cesia_settings", MODE_PRIVATE).getBoolean("news_panel_enabled", false)

    /** 读取 RSS 缓存中的新闻条目 */
    private fun readCachedNews(): List<RssFetchManager.NewsItem> {
        return try {
            val prefs = getSharedPreferences("cesia_rss_sources", MODE_PRIVATE)
            val json = prefs.getString("cached_items", "") ?: ""
            if (json.isEmpty()) return emptyList()
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("title", "")
                val l = o.optString("link", "")
                if (t.isEmpty()) null else RssFetchManager.NewsItem(t, l)
            }
        } catch (e: Exception) {
            Log.w("CesiaNews", "读取新闻缓存失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 进入新闻模式并展示。按需刷新：缓存超过 TTL 才联网抓取，否则直接用缓存。
     * 绝不后台定时轮询 —— 只有用户展开面板且无输入时才可能触发一次网络请求。
     */
    private fun showNewsInPanel() {
        isNewsMode = true
        newsItems = readCachedNews()
        renderNewsList()
        btnPanelRefresh.visibility = View.VISIBLE   // 新闻模式显示刷新按钮

        // 缓存过期（或为空）才抓取
        val prefs = getSharedPreferences("cesia_rss_sources", MODE_PRIVATE)
        val cachedTime = prefs.getLong("cached_time", 0L)
        val expired = System.currentTimeMillis() - cachedTime > newsCacheTtlMs
        if ((newsItems.isEmpty() || expired) && !isNewsFetching) {
            fetchNewsAsync()
        }
    }

    /** 后台抓取 RSS，完成后回主线程刷新（仍在新闻模式才刷，避免用户已开始打字被打断） */
    private fun fetchNewsAsync() {
        val source = RssFetchManager.getSelectedSource(this)
        if (source == null) {
            if (newsItems.isEmpty()) {
                flCandidates.removeAllViews()
                panelChips.clear()
                val chip = makeChip("未选择新闻源，请到设置 → 新闻源管理选择", fullWidth = true, position = 0, isNews = true)
                flCandidates.addView(chip)
                panelChips.add(chip)
            }
            return
        }
        isNewsFetching = true
        if (newsItems.isEmpty()) {
            flCandidates.removeAllViews()
            panelChips.clear()
            val chip = makeChip("正在加载 ${source.name} …", fullWidth = true, position = 0, isNews = true)
            flCandidates.addView(chip)
            panelChips.add(chip)
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val ok = try {
                RssFetchManager.fetchAndCache(this@CesiaInputMethod, source)
            } catch (e: Exception) {
                Log.w("CesiaNews", "抓取失败: ${e.message}"); false
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                isNewsFetching = false
                // 用户可能已经开始打字（isNewsMode=false），此时不要抢占候选栏
                if (!isNewsMode) return@withContext
                if (ok) {
                    newsItems = readCachedNews()
                    renderNewsList()
                    // 抓到后同步刷新顶栏首条（若当前仍处于无输入新闻态）
                    if (!rimeEngine.isComposing && rimeEngine.composingText.isEmpty()) renderNewsToBar()
                } else if (newsItems.isEmpty()) {
                    flCandidates.removeAllViews()
                    panelChips.clear()
                    val chip = makeChip("加载失败，请检查网络后重新展开", fullWidth = true, position = 0, isNews = true)
                    flCandidates.addView(chip)
                    panelChips.add(chip)
                }
            }
        }
    }

    /** 退出新闻模式，恢复候选词的流式布局 */
    private fun exitNewsMode() {
        if (!isNewsMode) return
        isNewsMode = false
        wasNewsMode = false   // 标记「现在处于非新闻态」：下次切回新闻态时才会推进滚动索引
        stopNewsMarquee()
        btnPanelRefresh.visibility = View.GONE   // 退出新闻模式隐藏刷新按钮
        // 退出时清掉顶栏可能残留的新闻首条，交回候选词渲染
        if (rvCandidates != null && newsBarActive) {
            candidateAdapter?.newsMode = false
            candidateAdapter?.updateData(emptyList())
            newsBarActive = false
        }
    }

    /** 把新闻渲染到顶部候选条（横向条），点击即打开浏览器。
     *  滚动展示：每次「从无输入新闻态之外切回新闻态」时推进一条，循环浏览全部新闻。 */
    private fun renderNewsToBar() {
        if (rvCandidates == null || candidateAdapter == null) return
        candidateAdapter?.newsMode = true   // 顶栏新闻：逐字左删跑马灯
        // 延迟读缓存（首次进可能缓存还在抓，fetchNewsAsync 完成后会再次 updateCandidateBar → 这里也会再被调用）
        if (newsItems.isEmpty()) newsItems = readCachedNews()
        val first = newsItems.firstOrNull()
        if (first == null) {
            // 还没抓到：先显示「正在加载」，抓到后 fetch 回调会再次刷新
            stopNewsMarquee()
            if (!newsBarActive) {
                candidateAdapter?.updateData(listOf("正在加载新闻…"))
                newsBarActive = true
            }
            return
        }
        val item = newsItems[newsBarIndex % newsItems.size]
        candidateAdapter?.updateData(listOf(item.title))
        newsBarActive = true
        startNewsMarquee(item.title)   // 进入即开始逐字左删滚动
    }

    /** 启动顶栏新闻「逐字左删」滚动：从完整标题开始，每隔一步删左侧一个字，
     *  删空后恢复到完整标题并停止（不再循环），方便此时复制 / 标题上屏。 */
    private fun startNewsMarquee(title: String) {
        stopNewsMarquee()
        newsMarqueeTitle = title
        newsMarqueeOffset = 0
        newsMarqueeRunnable = object : Runnable {
            override fun run() {
                if (!newsBarActive) return
                // 已删到空（offset == 长度）→ 恢复到完整标题后停止，不再滚动
                if (newsMarqueeOffset >= newsMarqueeTitle.length) {
                    candidateAdapter?.updateData(listOf(newsMarqueeTitle))
                    newsMarqueeRunnable?.let { newsMarqueeHandler.removeCallbacks(it) }
                    newsMarqueeRunnable = null
                    return
                }
                val shown = newsMarqueeTitle.substring(newsMarqueeOffset)
                candidateAdapter?.setCurrentText(shown)   // 直接改文本，不重绑，保留长按状态
                newsMarqueeOffset++
                newsMarqueeHandler.postDelayed(this, 220)
            }
        }
        newsMarqueeHandler.postDelayed(newsMarqueeRunnable!!, 220)
    }

    /** 停止跑马灯（打字 / 退出新闻 / 离开顶栏时） */
    private fun stopNewsMarquee() {
        newsMarqueeRunnable?.let { newsMarqueeHandler.removeCallbacks(it) }
        newsMarqueeRunnable = null
    }

    /** 推进顶栏新闻滚动位置（循环），供每次重新进入新闻态时调用 */
    private fun advanceNewsBarIndex() {
        if (newsItems.isNotEmpty()) newsBarIndex = (newsBarIndex + 1) % newsItems.size
    }

    /** 顶部候选条点击（新闻态）：打开第一条新闻链接 */
    private fun openNewsLink(position: Int) {
        val item = newsItems.getOrNull(position) ?: return
        if (item.link.isEmpty()) { updateStatus("该条目无链接"); return }
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(item.link)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)   // IME 无 Activity 栈，必须加
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("CesiaNews", "打开链接失败: ${e.message}")
            updateStatus("无法打开链接")
        }
    }

    /** 新闻条目长按：复制标题 / 复制链接 / 标题上屏 */
    private fun showNewsItemMenu(anchor: android.view.View, position: Int) {
        val item = newsItems.getOrNull(position) ?: return
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, "复制标题")
        menu.menu.add(0, 1, 1, "复制链接")
        menu.menu.add(0, 2, 2, "标题上屏")
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                0 -> { copyToClipboard(item.title); updateStatus("已复制标题") }
                1 -> {
                    if (item.link.isEmpty()) updateStatus("该条目无链接")
                    else { copyToClipboard(item.link); updateStatus("已复制链接") }
                }
                2 -> currentInputConnection?.commitText(item.title, 1)
            }
            true
        }
        menu.show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("cesia", text))
        } catch (e: Exception) {
            Log.w("CesiaNews", "复制失败: ${e.message}")
        }
    }

    private fun expandCandidatePanel() {
        isPanelExpanded = true
        candidatePanel.visibility = View.VISIBLE
        btnCandidateExpand.setImageResource(R.drawable.triangle_gray_up)
        // 新闻模式显示刷新按钮，否则隐藏
        btnPanelRefresh.visibility = if (isNewsMode) View.VISIBLE else View.GONE
        updateCandidateBar()
        // 每次展开都回到顶部：滚动到面板顶部
        scrollCandidates.scrollTo(0, 0)
    }

    // ======================== 英文拼写模式（Cesia 自主词库，独立于 Rime） ========================
    // 初始化：加载英文词库（仅一次）+ 恢复持久化的中/英文状态
    private fun initEnglishMode() {
        if (englishDict == null) {
            try {
                val enPath = java.io.File(dictManager.getRimeDir(), "en_dicts/en.dict.yaml").absolutePath
                englishDict = com.cesia.input.engine.EnglishDictLoader().loadFromFile(enPath)
                Log.i("Cesia", "英文词库加载: size=${englishDict?.size()}")
            } catch (e: Throwable) {
                Log.e("Cesia", "英文词库加载失败: ${e.message}")
            }
        }
        isEnglishMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getBoolean("english_mode", false)
    }

    // 状态栏中/英按钮动效（参考简繁）：当前模式文字高亮（主题色），另一字灰色
    private fun updateEnModeButton() {
        if (!::btnEnMode.isInitialized) return
        val accent = themeAccent
        val gray = 0xFF888888.toInt()
        btnEnMode.text = if (isEnglishMode) "英" else "中"
        btnEnMode.setTextColor(if (isEnglishMode) accent else gray)
    }

    // 中/英切换（双击空格 / 状态栏按钮，等价）
    private fun toggleEnglishMode() {
        isEnglishMode = !isEnglishMode
        getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
            .putBoolean("english_mode", isEnglishMode).apply()
        enMode = if (isEnglishMode) keyboardMode else null
        enBuffer.clear()
        enCandidates = emptyList()
        if (isEnglishMode) {
            // 进入英文：清空 Rime 中文 composing + 联想态，避免候选栏残留中文词/联想词（像 T9 那样）
            try { rimeEngine.clear() } catch (_: Throwable) {}
            isAssociationMode = false
            associationPrefix = ""
            associationCandidates = emptyList()
        } else {
            // 切回中文：重置 Rime 到当前键盘对应 schema，避免候选栏只显示一个词
            try {
                rimeEngine.selectSchema(if (keyboardMode == KeyboardMode.NUMBER) "t9_pinyin" else "pinyin")
                rimeEngine.reload()
                rimeEngine.clear()
            } catch (_: Throwable) {}
        }
        updateEnModeButton()
        updateCandidateBar()
        updateStatus(if (isEnglishMode) "已切换到英文输入" else "已切换到中文输入")
    }

    // 英文输入：处理单个按键（完全不经过 Rime）
    private fun handleEnglishKey(primaryCode: Int) {
        val dict = englishDict ?: run { isEnglishMode = false; updateEnModeButton(); return }
        val lower = if (primaryCode in 65..90) primaryCode + 32 else primaryCode
        // 符号面板负码 / 全键盘问号 / T9主键盘中文标点码点 → 对应英文标点（先上屏英文词再上屏符号+空格）
        val punctMap = mapOf(
            -310 to ',', -320 to '.', -329 to '!', -330 to '?',
            65311 to '?', 33 to '!', 63 to '?', 44 to ',', 46 to '.',
            65292 to ',', 12290 to '.', 65281 to '!'
        )
        val punct = punctMap[primaryCode]
        when {
            punct != null -> {  // 标点符号：先上屏英文词，再上屏英文标点+空格
                commitEnglishTop()
                currentInputConnection?.commitText("$punct ", 1)
                enBuffer.clear()
                enCandidates = emptyList()
                updateCandidateBar()
            }
            primaryCode == -5 -> {  // 退格
                if (enBuffer.isNotEmpty()) {
                    enBuffer.deleteAt(enBuffer.length - 1)
                    refreshEnglishCandidates()
                } else {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
            }
            primaryCode == 10 -> commitEnglishTop()  // 回车：上屏英文词（无则忽略）
            primaryCode == 32 -> {  // 空格：有英文上屏词，无则上屏空格
                val had = enBuffer.isNotEmpty() || enCandidates.isNotEmpty()
                commitEnglishTop()
                if (!had) currentInputConnection?.commitText(" ", 1)
            }
            lower in 97..122 -> {  // 字母：累积
                enBuffer.append(lower.toChar())
                refreshEnglishCandidates()
            }
            enMode == KeyboardMode.NUMBER && primaryCode in 50..57 -> {  // T9 数字键：作为 t9 数字序列
                enBuffer.append(primaryCode.toChar())
                refreshEnglishCandidates()
            }
            else -> {  // 其余键（标点/数字/符号）：先上屏英文词，再上屏该键字符（标点后补空格）
                commitEnglishTop()
                if (primaryCode in 32..126) {
                    val ch = primaryCode.toChar()
                    val suffix = if (ch.isLetterOrDigit()) "" else " "
                    currentInputConnection?.commitText("$ch$suffix", 1)
                }
            }
        }
    }

    private fun commitEnglishTop() {
        val commit = enCandidates.firstOrNull() ?: enBuffer.toString()
        if (commit.isNotEmpty()) {
            currentInputConnection?.commitText(commit, 1)
        }
        enBuffer.clear()
        enCandidates = emptyList()
        updateCandidateBar()
    }

    private fun refreshEnglishCandidates() {
        val dict = englishDict ?: return
        enCandidates = if (enMode == KeyboardMode.NUMBER) {
            dict.t9Match(enBuffer.toString())
        } else {
            dict.prefixMatch(enBuffer.toString())
        }
        updateCandidateBar()
    }

    private fun collapseCandidatePanel() {
        isPanelExpanded = false
        candidatePanel.visibility = View.GONE
        btnCandidateExpand.setImageResource(R.drawable.triangle_gray_down)
        exitNewsMode()   // 收起面板即退出新闻模式，避免下次展开时列数/点击行为串台
    }

    /** 通过全局索引选择候选词（自动翻页选中） */
    private fun selectCandidateByGlobalIndex(globalIndex: Int) {
        if (globalIndex < 0) return
        // 英文自主词库通道：点击候选直接上屏对应英文词，不走 Rime
        if (isEnglishMode && enCandidates.isNotEmpty()) {
            val word = enCandidates.getOrNull(globalIndex) ?: enCandidates.firstOrNull() ?: return
            currentInputConnection?.commitText(word, 1)
            enBuffer.clear()
            enCandidates = emptyList()
            updateCandidateBar()
            return
        }
        // 选了整词/词组/单字 → 交给 Rime 原生续写或结束；单字已并入主候选流，无独立「单字专区」进度需重置。

        try {
        // 联想模式：点击的是联想候选词
        if (isAssociationMode && globalIndex < associationCandidates.size) {
            val selectedDisplay = associationCandidates[globalIndex]
            val newPrefix = associationPrefix + selectedDisplay
            val newAssociations = rimeEngine.getAssociations(newPrefix, 100, 500, 10)

            // 上屏选中的词（追加到已有前缀后面）
            if (smartEditMode) {
                smartEditBuffer.append(selectedDisplay)
                updateSmartEditStatus()
            } else if (magicEditMode) {
                magicEditBuffer.append(selectedDisplay)
                updateMagicEditStatus()
            } else {
                commitCandidateText(selectedDisplay)
            }
            lastT9Feed = null  // 联想选词上屏后重置增量喂标记，防止下个新拼音首键被吞

            // 每步都把当前累积词（≥2字）写入用户词库：无论用户是继续联想还是就此停手，
            // 组出的词都能被记住。registerUserPhraseCodes 内部去重+累加频次，重复调用安全。
            addUserPhraseByText(newPrefix)

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
        // 调频修复（精准版）：若该词既在 userPhrases（被联想态误登记）又在 Rime 候选(lastAllCands，说明是 Rime 原生词)，
        // 且处于「NUMBER 多键、非简拼」场景（此时 Rime 会话与 lastAllCands 对齐，selectCandidate 安全），
        // 则放行到下方 selectCandidate 原生调频路径，让词频前移。
        // 注意：单键/简拼分支保持原样（无条件直出），绝不 fall through，避免会话错位导致选词与显示不一致。
        val inRime = lastAllCands.contains(clickedWord) || lastAllCands.contains(toSimplified(clickedWord))
        val shouldUseRimeFreq = userPhrases.containsKey(clickedWord) && inRime
            && keyboardMode == KeyboardMode.NUMBER && !t9FenCiOn && t9DigitQueue.length > 1
        // 用户自建词组：独立点击路径，支持接龙组词（有剩余数字则继续，无剩余则上屏）
        if (userPhrases.containsKey(clickedWord)) {
            if (shouldUseRimeFreq) {
                // 好啦类：Rime 原生词被误登记，不在此直出，落到下方 selectCandidate 调频（多键场景会话有效）。
                // 空处理，继续往下走统一路径。
            } else {
                if (keyboardMode == KeyboardMode.NUMBER && !t9FenCiOn) {
                    // Rime 原生主导：用户词组直接上屏，不再自研消费位数/重喂剩余数字。
                    // 该词整串即用户当次输入的目标，上屏后一律结束本次 T9 组合。
                    t9ComposedSoFar.append(clickedWord)
                    commitCandidateText(clickedWord)
                    addUserPhrase(t9ComposedSoFar.toString(), t9DigitQueue.toString())
                    t9ComposedSoFar.clear()
                    rimeEngine.clear(); t9DigitQueue.clear(); t9SpellPrefix.clear()
                    lastT9Feed = null
                    updateCandidateBar(); updateSpellBar(); updateStatus(statusIdleText)
                } else {
                    // 非接龙场景：直接上屏该词
                    commitCandidateText(clickedWord)
                }
                // 用户词组直出也查联想：修复组过的词/部分码选词因提前 return 失联想
                // （如反复全码输「问题」后被登记进 userPhrases，部分码 9368 选「问题」走此分支直接 return）
                val upAssoc = rimeEngine.getAssociations(clickedWord, 100, 500, 10)
                if (upAssoc.isNotEmpty()) {
                    isAssociationMode = true
                    associationPrefix = clickedWord
                    associationCandidates = upAssoc
                    showAssociationCandidates()
                }
                return
            }
        }
        // 单键枚举候选（t9SingleKeyCands）：显示列表来自「逐字母枚举合并」，与 Rime 当前 t9 模糊态会话
        // 的候选顺序/集合不对齐（如输入3显示「的」、Rime会话第0是「饿」）。若在此走下方 Rime 翻页选中，
        // 会选到会话第0而非显示第0，导致显示与上屏不一致。故单键直接按显示词上屏，不翻页。
        if (keyboardMode == KeyboardMode.NUMBER && t9DigitQueue.length == 1) {
            commitCandidateText(clickedWord)
            resetT9State()
            return
        }
        // 简拼模式（仅 T9）：候选来自合并列表(分词符串+字母组合)，不在主 Rime 会话中。
        // 入口判断用 lastDisplayedCands(用户实际点击的显示列表)而非 t9FenCiMerged，
        // 避免用户词组注入/简繁转换后显示列表与原始列表不一致导致 contains 失败、接龙被绕过。
        if (keyboardMode == KeyboardMode.NUMBER && t9FenCiOn && lastDisplayedCands.contains(clickedWord)) {
            val toCommit = stripDuplicatePrefix(clickedWord)
            t9ComposedSoFar.append(clickedWord)
            // 选词后清空选音前缀（下一音节的逐键选音从头开始）
            t9SpellPrefix.clear()
            // Rime 原生主导：简拼选词即上屏并结束本次组合，不再自研消费位数/重喂剩余数字
            when {
                smartEditMode -> { smartEditBuffer.append(toCommit); updateSmartEditStatus() }
                magicEditMode -> { magicEditBuffer.append(toCommit); updateMagicEditStatus() }
                else -> commitCandidateText(toCommit)
            }
            addUserPhrase(t9ComposedSoFar.toString(), t9DigitQueue.toString())
            t9ComposedSoFar.clear()
            rimeEngine.clear()
            // 造词放宽：选词后查联想(词+词/词+字)，有联想进联想模式继续组词；无则结束
            val newAssoc = rimeEngine.getAssociations(clickedWord, 100, 500, 10)
            dlog { "T9联想查询[简拼/非接龙]: prefix='$clickedWord', mode=T9, 结果数=${newAssoc.size}, associations=${newAssoc.take(5)}" }
            if (newAssoc.isNotEmpty() && !smartEditMode && !magicEditMode) {
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
            t9FullPhrase.append(selectedWord)   // 累积整串短语，末尾整体存 Rime 词表（不参与显示）
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
            } else {
                // Rime 原生主导：直接上屏该候选。是否还有后续音节由 Rime 的 composing 状态决定，
                // 不再自研消费位数/feedRemaining 重喂。
                commitCandidateText(toCommit)
            }
            // QWERTY 全键盘模式：选词上屏后必须清除 Rime composing 状态，否则下次输入会残留
            if (keyboardMode == KeyboardMode.QWERTY) {
                rimeEngine.clear()
            }
            if (keyboardMode == KeyboardMode.NUMBER && !smartEditMode && !magicEditMode) {
                // 回归 Rime 原生续写：selectCandidate 已吃掉已选字的音并推进 composition，
                // 不再自研「选词后重喂完整队列 + t9SpellCursor 跳过」。b9962c9 在 selectCandidate 之后
                // 又 processT9InputLight 重喂完整 96894，等于把已选字那部分重新拉回 → 已选的字对应的音没被吃掉；
                // 且重喂前 t9ComposedSoFar.clear() 让 t9SpellCursor 回退到 0，已消费位彻底失效。
                t9InputBuffer.clear()
                if (rimeEngine.isComposing) {
                    // Rime 仍在 composing（拼音未输完整）：先查该词联想。
                    // 有联想则进入联想模式（清 Rime composing，显示续词），否则交还 Rime 续写下一音节。
                    // 修复：原逻辑直接 return 跳过 getAssociations，导致部分码选词（如 936 选"问题"）无联想，
                    // 而全码（93684）因 isComposing=false 能联想——同一词行为不一致。
                    val earlyAssoc = rimeEngine.getAssociations(selectedWord, 100, 500, 10)
                    if (earlyAssoc.isNotEmpty()) {
                        rimeEngine.clear()
                        t9ComposedSoFar.clear()
                        t9DigitQueue.clear(); t9SpellPrefix.clear(); t9FenCiMerged = emptyList()
                        t9PendingSeg = ""; t9PendingChars.clear(); lastT9Feed = null
                        updateSpellBar(); updateStatus(statusIdleText)
                        isAssociationMode = true
                        associationPrefix = selectedWord
                        associationCandidates = earlyAssoc
                        showAssociationCandidates()
                        return
                    }
                    t9SpellPrefix.clear()
                    updateSpellBar()
                    updateCandidateBar()
                    scrollCandidates.scrollTo(0, 0)
                    return
                }
                // Rime 整串已消费完：整串写入 Rime 用户词表（cesia_user.dict.yaml），清 T9 状态，随后统一走词后联想
                if (t9FullPhrase.isNotEmpty()) {
                    addUserPhrase(t9FullPhrase.toString(), "")
                    t9FullPhrase.clear()   // 存完即清，避免下次组合继续 append 产生「蔡祈琪蔡祈琪」垃圾词条
                }
                rimeEngine.clear()
                t9ComposedSoFar.clear()
                t9DigitQueue.clear(); t9SpellPrefix.clear(); t9FenCiMerged = emptyList()
                t9PendingSeg = ""; t9PendingChars.clear()
                lastT9Feed = null
                updateSpellBar(); updateStatus(statusIdleText)
            }
            val associations = rimeEngine.getAssociations(selectedWord, 100, 500, 10)
            dlog { "T9联想查询: prefix='$selectedWord', mode=${if (keyboardMode == KeyboardMode.NUMBER) "T9" else "QWERTY"}, 结果数=${associations.size}" }
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
                // 没有联想词：可能因联想索引尚未构建完成（新装首查）返回的“假阴性”。
                // 改用 getAssociationsWhenReady：索引就绪后自动重查并回调，彻底消除「早期选词永久失联」。
                if (!rimeEngine.isAssociationIndexReady() && selectedWord.isNotEmpty() && keyboardMode == KeyboardMode.NUMBER) {
                    // 索引未就绪：先彻底清空选音态（避免残留 yan 拼回 feed），登记就绪后自动补查联想
                    t9SpellPrefix.clear(); t9FenCiMerged = emptyList(); t9DigitQueue.clear()
                    t9ComposedSoFar.clear()
                    val pendingPrefix = selectedWord
                    rimeEngine.getAssociationsWhenReady(pendingPrefix, 100, 10) {
                        // 索引已就绪，主线程回调：重查并进入联想模式（仅当用户尚未进入其他联想态）
                        if (isAssociationMode) return@getAssociationsWhenReady
                        val retry = rimeEngine.getAssociations(pendingPrefix, 100, 500, 10)
                        if (retry.isNotEmpty()) {
                            t9SpellPrefix.clear(); t9DigitQueue.clear()
                            isAssociationMode = true
                            associationPrefix = pendingPrefix
                            associationCandidates = retry
                            if (isPanelExpanded) collapseCandidatePanel()
                            showAssociationCandidates()
                            updateSpellBar()
                        }
                    }
                    updateSpellBar(); updateStatus(statusIdleText)
                } else {
                    isAssociationMode = false
                    associationPrefix = ""
                    associationCandidates = emptyList()
                    // 修复：选中单字且有已选音后，无联想词时应完全重置到空闲状态（清除状态栏、候选栏、候选音区）
                    if (keyboardMode == KeyboardMode.NUMBER && t9SpellPrefix.isNotEmpty()) {
                        // 这种情况在前面的 else if 分支已处理清除 spellPrefix，这里确保彻底重置
                        resetT9State()
                    } else {
                        // 清 T9 残留（候选音区），避免全拼单字上屏后候选音不消失
                        t9SpellPrefix.clear(); t9FenCiMerged = emptyList()
                        if (keyboardMode == KeyboardMode.NUMBER) { t9DigitQueue.clear() }
                        updateSpellBar()
                        // 不进联想：保持展开面板（逐字组词顺点，避免收起再展开旧index命中新内容重复上屏）
                        updateCandidateBar()
                        scrollCandidates.scrollTo(0, 0)
                    }
                }
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
        updateStatus("$associationPrefix")
        // 重置联想懒加载状态
        assocPageWalk = 10
        assocTotalLoaded = associationCandidates.size
        lastAssocPrefix = associationPrefix
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
            // 防御：退出联想态时一并清空选音态，避免残留拼音(如 yan)拼回新输入 feed 显示 yan43
            t9SpellPrefix.clear(); t9FenCiMerged = emptyList(); t9DigitQueue.clear()
            t9ComposedSoFar.clear()
            // 立即清空候选栏适配器，防止显示旧联想词
            candidateAdapter?.updateData(emptyList())
            rvCandidates?.scrollToPosition(0)
            setCandidateBarVisible(false)
            // 退格删掉已上屏文字后，状态栏仍残留联想前缀（=已上屏文字），这里一并清空
            if (!smartEditMode && !magicEditMode) updateStatus(statusIdleText)
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
        // 新闻开关打开且面板已展开：保留面板（新闻模式），不要收起。
        // 否则每次 updateCandidateBar() 都会走到无输入分支并调 clearCandidateContent()，
        // 它又强制 collapse → 刚展开的面板被立刻收起，showNewsInPanel() 永远拿不到 isPanelExpanded=true。
        if (isPanelExpanded && !isNewsPanelEnabled()) collapseCandidatePanel()
        candidateBar.visibility = View.VISIBLE
        updateStatus(statusIdleText)
    }

    private fun updateCandidateBar() {
        // 语音识别期间不更新候选栏（避免覆盖流式识别状态）
        if (isRecording) return
        // 候选懒加载：输入状态(数字队列/选音前缀/简拼开关/接龙消费)变化时，重置分页，首屏重新拉 50 候选
        val inputSig = "$t9DigitQueue|$t9SpellPrefix|$t9FenCiOn|$t9ComposedSoFar"
        if (inputSig != lastPagerInputSig) {
            candPageWalk = 10
            candTotalLoaded = 0
            lastPagerInputSig = inputSig
        }
        val composing = rimeEngine.isComposing
        val pinyin = rimeEngine.composingText

        // 候选音锁定(t9SpellPrefix)由下方 filterCandsByFullPinyinPrefix 收窄；
        // 待定段单字不再自研伪造列表注入（曾导致点击错位/锁音失效），完全信任 Rime 主候选流——
        // 未锁定时 Rime 整串已含全合法拼音单字（pi/qi/ri/si）随主流懒加载，锁定后 Rime 自然只剩该音系。
        t9ConsumedLen = 0; t9PendingSeg = ""; t9PendingChars.clear()

        // 简拼模式：仅 T9 数字键盘下用合并候选（分词字符串 + 字母组合交叉）；单键单字用枚举候选(跟随选音)；全键盘始终走自身 pinyin 候选
        val rimeAllCands = when {
            keyboardMode == KeyboardMode.NUMBER && t9FenCiOn && t9FenCiMerged.isNotEmpty() -> t9FenCiMerged
            keyboardMode == KeyboardMode.NUMBER && t9DigitQueue.length == 1 && t9SingleKeyCands.isNotEmpty() -> t9SingleKeyCands
            else -> rimeEngine.getAllCandidates(candPageWalk)
        }
        var allCands: List<String>
        if (isEnglishMode) {
            // 英文自主词库通道：候选直接来自 enCandidates，不经 Rime
            allCands = if (enBuffer.isEmpty()) emptyList() else enCandidates
            lastAllCands = allCands
            candTotalLoaded = allCands.size
        } else {
        var allCandsZh = rimeAllCands
        // 用户词组召回：若当前数字码前缀匹配某存词（如 2247474→蔡祈琪），把该词插入候选前列。
        // 仅显示用（不进 lastAllCands），点击时走「直接上屏语义」(见 selectCandidateByGlobalIndex 特判)，
        // 不走 Rime 索引反查 → 不会错位（区别于早期 mergeByFrequency 注入 bug）。
        val curDigits = t9DigitQueue.toString()
        val recalled = if (keyboardMode == KeyboardMode.NUMBER && curDigits.length >= 2 && userPhrases.isNotEmpty()) {
            userPhrases.filter { (_, e) -> e.codes.any { it == curDigits || it.startsWith(curDigits) } }
                .maxByOrNull { it.value.freq }?.key
        } else null
        t9RecalledPhrase = recalled
        if (recalled != null && recalled !in allCandsZh) {
            allCandsZh = listOf(recalled) + allCandsZh
        }
        allCands = allCandsZh
        }
        // 快照未过滤的原始 Rime 候选列表（供点击反查真实全局索引）。召回词不纳入，避免位置错位
        lastAllCands = rimeAllCands
        // 懒加载：记录首屏已拉取的 Rime 候选数（纯 Rime），供滚动到底 drop 取新增
        candTotalLoaded = rimeAllCands.size

        // 逐键选音：已选字母前缀非空时，按候选拼音完整前缀过滤（全拼模式用；简拼模式已由 buildT9SpellFeed 精确出候选，跳过）
        if (t9SpellPrefix.isNotEmpty() && !t9FenCiOn) {
            val pinyins = rimeEngine.getAllCandidatePinyins(candPageWalk)
            allCands = filterCandsByFullPinyinPrefix(allCands, pinyins, t9SpellPrefix.toString())
        }

        // 去重：输入状态( composing/拼音/选音前缀/繁体/面板/联想/候选集 )未变则跳过整轮重建，
        // 避免每次按键无谓的 adapter 重排与 notifyDataSetChanged（影响跟手速度）。
        val sig = ((composing.hashCode() * 31 + pinyin.hashCode()) * 31 + allCands.hashCode()) xor
            ((t9SpellPrefix.hashCode() * 31 + isTraditional.hashCode() * 31
                + isPanelExpanded.hashCode() * 31 + isAssociationMode.hashCode() * 31
                + associationCandidates.hashCode() + isNewsMode.hashCode()))
        if (sig == lastCandSig) return
        lastCandSig = sig

        // 没有输入时退出联想模式并恢复初始状态
        // 但联想模式下有联想词时不退出（联想词已上屏，Rime composing 已结束）
        // 智能写作/魔法编辑模式下不恢复初始状态（避免"已就绪"覆盖编辑中的命令）
        if (!composing && pinyin.isEmpty() && !isAssociationMode && !smartEditMode && !magicEditMode && !isEnglishMode) {
            if (isAssociationMode) {
                isAssociationMode = false
                associationPrefix = ""
                associationCandidates = emptyList()
            }
            // 没有输入时：语音保持模式(candidateBarKeep)下整栏保持可见；否则清除内容但保持可见（不收起）
            if (!candidateBarKeep) {
                clearCandidateContent()
            }
            if (isNewsPanelEnabled()) {
                // 新闻模式：面板展开则面板显示全部新闻（单列）；顶栏始终显示一条（可点击开浏览器）
                // 显式把候选条置为可见：绕过 candidateBarKeep（否则此前"默认不显示候选栏"的偏好
                // 会让 clearCandidateContent 跳过、条一直是 GONE，新闻首条永远不显示）。
                // 仅当「上一次不是新闻态」时推进滚动索引：打字→清空、联想结束等每一次切回都换一条，循环浏览。
                if (!wasNewsMode) advanceNewsBarIndex()
                wasNewsMode = true
                isNewsMode = true
                candidateBar.visibility = View.VISIBLE
                if (isPanelExpanded) showNewsInPanel()
                renderNewsToBar()
                btnCandidateExpand?.visibility = View.VISIBLE
            } else {
                wasNewsMode = false
                exitNewsMode()
                btnCandidateExpand?.visibility = View.GONE
            }
            return
        }

        // 有输入 → 立刻退出新闻模式，候选词优先（输入法本职不能被新闻干扰）
        exitNewsMode()
        // 打字时展开按钮交给候选数逻辑（下方 count 分支），这里先隐藏避免残留新闻态按钮
        if (!isPanelExpanded) btnCandidateExpand?.visibility = View.GONE

        // 有输入时
        setCandidateBarVisible(true)

        // T9 模式：状态栏显示「已选字母 + 剩余未选数字」（逐键选音进度）；选满后只显示字母
        // 编辑模式(smartEditMode/magicEditMode)已自行设置状态栏，跳过覆盖
        if (!(smartEditMode || magicEditMode)) {
            if (keyboardMode == KeyboardMode.NUMBER && (t9DigitQueue.isNotEmpty() || t9SpellPrefix.isNotEmpty())) {
                val from = t9SpellCursor.coerceAtMost(t9DigitQueue.length)
                val remaining = if (from < t9DigitQueue.length) t9DigitQueue.substring(from) else ""
                updateStatus(t9SpellPrefix.toString() + remaining)
            } else {
                updateStatus(pinyin)
            }
        }

        // 联想模式：显示联想候选词
        if (isAssociationMode && associationCandidates.isNotEmpty()) {
            val displayCands = if (isTraditional) associationCandidates.map { toTraditional(it) } else associationCandidates
            candidateAdapter?.updateData(displayCands)
            rvCandidates?.scrollToPosition(0)
            btnCandidateExpand.visibility = if (associationCandidates.size > 4) View.VISIBLE else View.GONE
            if (isPanelExpanded) {
                tvPanelComposing.text = "💡$associationPrefix"
                renderCandidatesToPanel(displayCands)
            }
            return
        }

        // 简繁转换：显示层转繁体，但点击定位/选中用简体原词，
        // 否则繁体词(如「簡單」)匹配不到 Rime 简体候选(lastAllCands)导致点击不上屏。
        val base = allCands  // 简体原词（Rime 候选），用于置顶 key、点击反查、Rime 翻页选中

        // 应用候选偏好（置顶/降频），全局持久化（用简体词做 key，与点击反查一致）
        var reordered = CandidatePrefs.reorder(this, base)
        // BUG修复（多位数字只出词组、看不到单字）：全拼多键时 Rime 优先给长词，单字被排到很后面
        // （常常在第 2 页之后，首屏根本看不到），用户无法选单字逐字组词。
        // 这里把前若干个单字提升到首屏可见区（保持相对顺序，不改动其它候选的相对次序）。
        if (keyboardMode == KeyboardMode.NUMBER && !t9FenCiOn && t9DigitQueue.length > 1 && reordered.size > 4) {
            val visibleHead = 6
            val headSingles = reordered.take(visibleHead).count { it.length == 1 }
            // 只要首屏可见区单字不足 2 个，就把单字提升到首屏（Rime 多音节优先给长词，单字常被挤到第 2 页之后，
            // 导致逐字组词时选不到对应拼音的单字，如 caiqiqi 选蔡后只剩词组）。最多提 3 个，保持 Rime 首选词在第 1 位。
            if (headSingles < 2) {
                val singles = reordered.filter { it.length == 1 }.take(3)
                if (singles.isNotEmpty()) {
                    val rest = reordered.filterNot { it in singles }
                    reordered = rest.take(1) + singles + rest.drop(1)
                }
            }
        }
        // 快照：供点击时反查用户点的是哪个词（简体原词，与 Rime 候选/翻页选中一致）
        lastDisplayedCands = reordered

        // 更新候选词列表（显示繁体）
        // 必须由 reordered 派生显示列表：否则显示顺序(base)与点击反查源(reordered)错位，点甲上乙。
        val displayReordered = if (isTraditional) reordered.map { toTraditional(it) } else reordered
        candidateAdapter?.updateData(displayReordered)
        rvCandidates?.scrollToPosition(0)
        btnCandidateExpand.visibility = if (reordered.size > 4) View.VISIBLE else View.GONE

        // 更新展开面板：直接复用顶栏已重排好的 lastDisplayedCands（与横向候选栏完全同一份列表），
        // 严禁再独立 reorder（旧代码用 allCands 重新 reorder 得到 reorderedPanel，与 lastDisplayedCands 不等价，
        // 导致面板点击经 lastAllCands.indexOf(词) 反查时位置错配 → 点甲上乙）。
        // 现在面板与顶栏共用同一列表 + 同一点击路径（显示序号 → lastDisplayedCands[序号] → lastAllCands.indexOf），
        // 彻底消除错位，且不影响横向候选栏。
        if (isPanelExpanded) {
            tvPanelComposing.text = pinyin
            val displayPanel = if (isTraditional) lastDisplayedCands.map { toTraditional(it) } else lastDisplayedCands
            renderCandidatesToPanel(displayPanel)
        }
    }

    /** 候选懒加载：候选栏横向滚到右端阈值时，从 Rime 会话拉取下一批候选（每批约20字词）追加到列表与点击反查源。
     *  不设置数量上限，纯靠滚动懒加载（每批 20 字词），滚到底继续加载更多。 */
    private fun loadMoreCandidates() {
        if (keyboardMode != KeyboardMode.NUMBER) return
        if (candTotalLoaded <= 0) return
        // 单键枚举候选不走 Rime 会话分页（列表来自逐字母枚举），分页会取到不相干候选
        if (t9DigitQueue.length == 1) return
        // 从 Rime 会话增量翻页（每批 +10 页≈50 候选；原来每批 +4 页在首屏已扫 10 页的情况下
        // 常常一条新词都取不到 → 表现为“懒加载失效”）
        val step = 10
        candPageWalk += step
        val fresh = rimeEngine.getAllCandidates(candPageWalk)
        // 用「已在列表里的词」做差集，而不是按数量 drop：
        // fenci/用户词注入等路径下 candTotalLoaded 与 fresh 的下标并不对齐，
        // 按数量 drop 会漏掉或错位，导致 more 恒为空、懒加载看起来失效。
        val known = lastDisplayedCands.toHashSet()
        val more = fresh.filter { it !in known }
        if (more.isEmpty()) { candPageWalk -= step; return }
        candTotalLoaded = maxOf(candTotalLoaded, fresh.size)
        lastAllCands = (lastAllCands + fresh).distinct()
        val merged = (lastDisplayedCands + more).distinct()  // 简体原词，供点击反查/Rime选中
        lastDisplayedCands = merged
        val displayMerged = if (isTraditional) merged.map { toTraditional(it) } else merged
        candidateAdapter?.updateData(displayMerged)
        if (isPanelExpanded) {
            // 面板严格复用顶栏同一份 lastDisplayedCands（含懒加载新词），不再独立 reorder，
            // 与 updateCandidateBar 面板逻辑一致，消除错位。
            val displayPanel = if (isTraditional) lastDisplayedCands.map { toTraditional(it) } else lastDisplayedCands
            renderCandidatesToPanel(displayPanel)
        }
    }

    /** 联想懒加载：候选栏横向滚到右端阈值时，追加加载更多联想词 */
    private fun loadMoreAssociations() {
        if (!isAssociationMode) return
        if (associationPrefix != lastAssocPrefix) return // 前缀变了，不加载
        // 增加 pageWalk 加载下一页
        assocPageWalk += 10
        val more = rimeEngine.getAssociations(associationPrefix, 100, 500, assocPageWalk)
        dlog { "联想懒加载[loadMoreAssociations]: prefix='$associationPrefix', pageWalk=$assocPageWalk, 结果数=${more.size}, 总数=${associationCandidates.size}" }
        if (more.isEmpty()) {
            assocPageWalk -= 10
            return
        }
        // 追加到现有列表（去重）
        val combined = (associationCandidates + more).distinct()
        associationCandidates = combined
        assocTotalLoaded = combined.size
        lastAssocPrefix = associationPrefix
        val displayCands = if (isTraditional) combined.map { toTraditional(it) } else combined
        candidateAdapter?.updateData(displayCands)
        if (isPanelExpanded) {
            tvPanelComposing.text = "💡$associationPrefix"
            renderCandidatesToPanel(displayCands)
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
        applyDarkThemeToViewTree(menuView)
        if (isDarkTheme) menuView.setBackgroundResource(R.drawable.popup_candidate_menu_bg_dark)
        val tvTitle = menuView.findViewById<TextView>(R.id.tv_menu_title)
        val btnClose = menuView.findViewById<ImageButton>(R.id.btn_menu_close)
        val llItems = menuView.findViewById<LinearLayout>(R.id.ll_menu_items)
        tvTitle.text = "候选：$word"
        tvTitle.setTextColor(if (isDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt())
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
            if (isDarkTheme)
                GradientDrawable().apply { setColor(0xFF1E1E2E.toInt()); setStroke(1, 0xFF3A3A4E.toInt()) }
            else
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
                setTextColor(if (isDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt())
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

        // 定位到被长按的词附近（anchorView 即长按的候选 TextView），而非固定在候选栏底部
        val anchor = anchorView
        if (anchor != null) {
            popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, 0, 0)
            anchor.post {
                val loc = IntArray(2)
                anchor.getLocationOnScreen(loc)
                val menuW = popup.contentView?.measuredWidth ?: 0
                val menuH = popup.contentView?.measuredHeight ?: 0
                // 优先显示在词正下方；若超出屏幕底部则翻到词上方
                val screenH = resources.displayMetrics.heightPixels
                val y = if (loc[1] + anchor.height + menuH + 2 <= screenH)
                    loc[1] + anchor.height + 2
                else
                    (loc[1] - menuH - 2).coerceAtLeast(0)
                // 水平居中于该词，避免超出屏幕右缘
                val x = (loc[0] + anchor.width / 2 - menuW / 2).coerceIn(0, (resources.displayMetrics.widthPixels - menuW).coerceAtLeast(0))
                popup.update(x, y, -1, -1)
            }
        } else {
            // 无 anchor 兜底：贴候选栏底部
            val rv = rvCandidates ?: return
            popup.showAtLocation(rv, android.view.Gravity.NO_GRAVITY, 0, 0)
            rv.post {
                val loc = IntArray(2)
                rv.getLocationOnScreen(loc)
                popup.update(loc[0], loc[1] + rv.height + 2, -1, -1)
            }
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
            // 仅在候选栏已显示（有输入内容）时清空并保留候选栏；否则不弹出候选栏
            val candBarWasVisible = candidateBar.visibility == View.VISIBLE
            if (candBarWasVisible) {
                // 清空键：清除候选栏内容（保持可见），并结束语音保持模式
                clearCandidateContent()
            }
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
            updateStatus("已退出语音锁定")
            resetToIdle()
            return
        }
        if (!isRecording && !isWaitingForChoice) {
            maybeShowButtonHint("voice", "正在收听（双击切换模型）")
            try {
                val bridgeLoaded = SherpaOnnxEngine.isLibraryLoaded()
                val hasVoiceModel = modelManager.hasVoiceModel()
                Log.i("Cesia", "单击语音键: bridgeLoaded=$bridgeLoaded, hasVoiceModel=$hasVoiceModel, localMode=$localModeEnabled, simulTranslate=$simulTranslateEnabled")

                if (simulTranslateEnabled) {
                    if (!bridgeLoaded || !hasVoiceModel) {
                        updateStatus("需下载语音模型")
                        return
                    }
                    if (!modelManager.hasAiModel()) {
                        updateStatus("需下载 AI 模型")
                        return
                    }
                    startSimulTranslateRecording()
                } else if (localModeEnabled) {
                    if (!bridgeLoaded || !hasVoiceModel || !modelManager.hasAiModel()) {
                        updateStatus("需下载语音与 AI 模型")
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
                updateStatus("语音启动失败")
            }
        } else if (isWaitingForChoice) {
            updateStatus("请选择 处理方式")
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
            updateStatus("录音中不可切换")
            return
        }
        if (!voiceEngine.hasChineseModel()) {
            updateStatus("需下载中文模型")
            return
        }
        val mode = voiceEngine.switchVoiceMode()
        updateMicZhLabel()
        // 切换后立即在后台预热新模型的识别器，使下次点击语音键无需在线重建（消除切换后首次识别的卡顿）
        voiceEngine.warmupRecognizer()
        if (mode == com.cesia.input.voice.VoiceEngine.VoiceMode.CHINESE) {
            updateStatus("已切换到中文精准模型")
        } else {
            updateStatus("已切换到中英语音模型")
        }
    }

    // 语音键标记：右上角“中”（仅纯中文模式）+ 左上角「中英/纯中」选中高亮
    private fun updateMicZhLabel() {
        val isZh = voiceEngine.voiceMode == com.cesia.input.voice.VoiceEngine.VoiceMode.CHINESE
                && voiceEngine.hasChineseModel()
        if (::tvMicZh.isInitialized) {
            tvMicZh.visibility = if (isZh) View.VISIBLE else View.GONE
            if (isZh) {
                tvMicZh.text = "中"
                tvMicZh.setTextColor(0xFFFFFFFF.toInt())
                tvMicZh.textSize = (10 + textThemeSize * 2).toFloat()
                tvMicZh.requestLayout()
            }
        }
        // 左上角「中英/纯中」：纯中模式高亮"纯中"，否则高亮"中英"
        if (::tvMicModeBi.isInitialized && ::tvMicModeZh.isInitialized) {
            val pureZh = voiceEngine.voiceMode == com.cesia.input.voice.VoiceEngine.VoiceMode.CHINESE
            val white = 0xFFFFFFFF.toInt()
            val gray = 0xFF888888.toInt()
            tvMicModeBi.setTextColor(if (pureZh) gray else white)
            tvMicModeZh.setTextColor(if (pureZh) white else gray)
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
            updateStatus("暂无写作命令")
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
                dlog { "魔法模式本地录音协程被取消" }
            } catch (e: Exception) {
                Log.e("Cesia", "魔法模式本地识别失败", e)
                Handler(Looper.getMainLooper()).post {
                    updateStatus("本地识别失败")
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
            updateStatus("语音启动失败")
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
            dlog { "handleMagicResult: AI 正在处理中，忽略重复触发" }
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
            updateStatus("未识别到指令")
            return
        }

        updateStatus("正在施展魔法")

        // 读取剪贴板非置顶首条内容作为语境
        val clipboardContext = getClipboardFirstNonPinned()
        dlog { "handleMagicResult: instruction='$instruction', original='${magicOriginalText.take(50)}', clipboard='${clipboardContext.take(50)}'" }
        // 异步执行 AI，避免阻塞主线程
        isAiProcessing = true
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val prompt = buildMagicPrompt(magicOriginalText, instruction, clipboardContext)
                dlog { "handleMagicResult: prompt长度=${prompt.length}" }
                val polishService = typelessEngine?.getPolishService()
                dlog { "handleMagicResult: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(30) ?: "null"}" }
                val result = polishService?.polishWithPrompt(prompt)
                dlog { "handleMagicResult: result=${result?.take(50) ?: "null"}, isNullOrEmpty=${result.isNullOrEmpty()}" }
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    if (result != null && result.isNotEmpty()) {
                        magicHistoryManager?.addRecord(instruction)
                        saveUndoHistory(magicOriginalText, instruction)
                        try {
                            if (!isInputViewShown) {
                                updateStatus("键盘已收起，结果未上屏")
                                resetToIdle()
                            } else {
                            val ic2 = currentInputConnection
                            ic2?.performContextMenuAction(android.R.id.selectAll)
                            ic2?.commitText(result, 1)
                            resetToIdle()
                            }
                        } catch (e2: Exception) {
                            Log.e("Cesia", "handleMagicResult replaceInputText 异常", e2)
                            updateStatus("上屏失败")
                        }
                    } else {
                        updateStatus("网络异常，请重试")
                    }
                }
            } catch (e: Exception) {
                Log.e("Cesia", "智能写作失败", e)
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    updateStatus("操作失败")
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
                            dlog { "getClipboardFirstNonPinned: 读取到 ${text.length} 字符: ${text.take(50)}" }
                            return text
                        }
                    }
                }
            }
            dlog { "getClipboardFirstNonPinned: 系统剪贴板为空" }
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
            updateStatus("操作失败")
        }
    }

    private fun executeSelectedMagic(instruction: String) {
        if (isAiProcessing) {
            updateStatus("AI正在处理中，请稍候")
            return
        }
        val ic = currentInputConnection ?: run {
            updateStatus("无输入框连接")
            return
        }
        // ===== 选区感知：有选中文字则只改选中部分，无选区则改全文 =====
        // 实测（2026-08-07 logcat 探针）：popup.setFocusable(false) 下编辑器焦点不丢，
        // 从按下魔法书到点击命令间隔 9 秒，getSelectedText 与 selStart/selEnd 全程稳定，
        // 因此无需提前缓存选区，执行时实时读取即可。
        val selText = try { ic.getSelectedText(0)?.toString() } catch (_: Exception) { null }
        val ex0 = try { ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) } catch (_: Exception) { null }
        val selStart = ex0?.selectionStart ?: -1
        val selEnd = ex0?.selectionEnd ?: -1
        // 选区有效：非空、起止合法且不相等
        val hasSelection = !selText.isNullOrEmpty() && selStart >= 0 && selEnd > selStart

        val textBefore = try { ic.getTextBeforeCursor(10000, 0)?.toString() ?: "" } catch (_: Exception) { "" }
        val textAfter = try { ic.getTextAfterCursor(10000, 0)?.toString() ?: "" } catch (_: Exception) { "" }
        // 注意：有选区时 before/after 都不含选中内容，全文需把选中部分拼回中间
        val fullText = if (hasSelection) textBefore + (selText ?: "") + textAfter else textBefore + textAfter
        // 送 AI 的文本：有选区只送选中部分
        val targetText = if (hasSelection) (selText ?: "") else fullText

        Log.i("CesiaSel", "智能修改: hasSelection=$hasSelection sel=[$selStart,$selEnd] " +
            "targetLen=${targetText.length} fullLen=${fullText.length}")

        // 生成类魔法允许空文本，修改类魔法要求有文本
        if (targetText.isEmpty() && !isGenerationMagic(instruction)) {
            updateStatus(if (hasSelection) "选中内容为空" else "输入框无文字")
            return
        }

        isAiProcessing = true
        updateStatus(if (hasSelection) "AI正在修改选中的${targetText.length}个字" else "AI正在处理中")
        setStatusDot("processing")
        // 使用统一润色入口（自动适配本地/云端）
        executePolish(targetText, instruction) { result, success ->
            isAiProcessing = false
            if (success && result.isNotEmpty() && result != targetText) {
                magicHistoryManager?.addRecord(instruction)
                saveUndoHistory(fullText, instruction)
                try {
                    if (!isInputViewShown) {
                        updateStatus("键盘已收起，结果未上屏")
                        resetToIdle()
                    } else if (hasSelection) {
                        // ===== 局部替换：只覆盖选区，未选中的文字原样保留 =====
                        val ic2 = currentInputConnection
                        if (ic2 == null) {
                            updateStatus("上屏失败")
                        } else {
                            // ⚠️ AI 是异步的，这几秒内用户可能改动文本，导致 selStart/selEnd 失效。
                            // 回写前必须校验该区间文字仍是当初选中的那段，否则会替换到错误位置
                            // （宁可不改，也绝不能改错地方 / 吃掉别的文字）。
                            val nowText = try {
                                ic2.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()
                            } catch (_: Exception) { null }
                            val stillValid = nowText != null &&
                                selEnd <= nowText.length &&
                                nowText.substring(selStart, selEnd) == selText
                            if (stillValid) {
                                ic2.beginBatchEdit()
                                ic2.setSelection(selStart, selEnd)   // 重新选中原区间
                                ic2.commitText(result, 1)            // 有选区时 commitText 直接替换选区
                                ic2.endBatchEdit()
                                resetToIdle()
                            } else {
                                Log.w("CesiaSel", "选区已失效，放弃替换: sel=[$selStart,$selEnd] nowLen=${nowText?.length}")
                                updateStatus("原文已变化，未修改")
                                resetToIdle()
                            }
                        }
                    } else {
                        // ===== 无选区：保持原有全文替换 =====
                        val ic2 = currentInputConnection
                        ic2?.performContextMenuAction(android.R.id.selectAll)
                        ic2?.commitText(result, 1)
                        resetToIdle()
                    }
                } catch (e2: Exception) {
                    Log.e("Cesia", "replaceInputText 异常", e2)
                    updateStatus("上屏失败")
                }
            } else if (result == targetText) {
                updateStatus("修改结果与原文相同")
            } else {
                updateStatus("AI未返回有效结果，请重试")
            }
        }
    }

    private fun saveUndoHistory(originalText: String, instruction: String) {
        undoHistory.add(0, Pair(originalText, instruction))
        while (undoHistory.size > undoMaxSteps) {
            undoHistory.removeAt(undoHistory.size - 1)
        }
    }

    /**
     * 三大面板（智能写作 / 智能修改 / 剪贴板）左右滑动切换。
     *
     * 顺序：智能写作(0) → 智能修改(1) → 剪贴板(2) → 智能写作，左滑前进、右滑后退、循环。
     *
     * 实现方式：用 GestureDetector 绑在面板根 View 上，只识别「横向 fling」
     * （且水平速度明显大于垂直速度），命中后切换面板；其余所有手势（列表纵向滚动、
     * 手柄上下拖动改高度、下滑关闭）一律不拦截，照常传递。这样不会和已有逻辑抢事件。
     *
     * @param root 面板根 View（手势监听绑这里）
     * @param currentIndex 当前面板序号
     */
    private fun attachPanelSwipe(popup: PopupWindow, currentIndex: Int, dismiss: () -> Unit) {
        val detector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onFling(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.rawX - e1.rawX
                    val dy = e2.rawY - e1.rawY
                    // 横向为主、且甩动够快才切换；纵向甩动交给列表/关闭逻辑
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
                        kotlin.math.abs(velocityX) > 600f &&
                        kotlin.math.abs(dx) > 40f * resources.displayMetrics.density) {
                        val next = if (dx < 0) (currentIndex + 1) % 3 else (currentIndex + 2) % 3
                        dismissAndOpen(dismiss) {
                            when (next) {
                                0 -> showSmartWritingPopup()
                                1 -> showMagicHistoryPopup()
                                2 -> showClipboardManagerPopup()
                            }
                        }
                        return true
                    }
                    return false
                }
            })
        // 用 PopupWindow 的 touch 拦截器：它能拿到派发给整个弹窗的全部触摸事件，
        // 而 root.setOnTouchListener 只有在没有子 View 消费时才会触发（列表/按钮会消费掉）。
        // 这里始终返回 false —— 只观察手势，不拦截，列表滚动与点击照常。
        popup.setTouchInterceptor { _, e ->
            detector.onTouchEvent(e)
            false
        }
    }

    /** 关闭当前面板并打开目标面板（延后一帧避免 PopupWindow 残留闪烁） */
    private fun dismissAndOpen(dismiss: () -> Unit, open: () -> Unit) {
        dismiss()
        Handler(Looper.getMainLooper()).post(open)
    }

    private fun showMagicHistoryPopup() {
        dlog { "showMagicHistoryPopup: called, mgr=$magicHistoryManager" }
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
        applyDarkThemeToViewTree(popupView)
        if (isDarkTheme) popupView.setBackgroundResource(R.drawable.sheet_top_rounded_dark)
        val gridView = popupView.findViewById<GridView>(R.id.gv_magic_items)
        // 设置标题（使用个性化设置）
        val bannerBar = popupView.findViewById<android.widget.LinearLayout>(R.id.banner_bar)
        // banner_bar 的第一个子 View 是标题 TextView，第二个是关闭按钮
        val titleTv = bannerBar?.getChildAt(0) as? android.widget.TextView
        titleTv?.text = magicBookTitle

        val keyboardWidth = keyboardView.width
        val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

        // 获取状态栏高度
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
            if (id > 0) resources.getDimensionPixelSize(id) else 88
        }
        // 高度上限放开到整屏（状态栏之下），允许拖到屏幕顶端、覆盖键盘区
        val density = resources.displayMetrics.density
        val minSheetHeight = (density * 160f).toInt()
        val screenH = resources.displayMetrics.heightPixels
        val maxSheetHeight = (screenH - statusBarHeight).coerceAtLeast(minSheetHeight)

        // 记忆上次高度
        val sheetPrefs = getSharedPreferences("cesia_magic_sheet", MODE_PRIVATE)
        val savedH = sheetPrefs.getInt("height", -1)
        val totalHeight = if (savedH > 0) savedH.coerceIn(minSheetHeight, maxSheetHeight) else maxSheetHeight

        val popup = PopupWindow(popupView, popupWidth, totalHeight, true)
        popup.isOutsideTouchable = false
        popup.elevation = 8f
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.setFocusable(false)
        // 左右滑动切换面板（智能写作↔智能修改↔剪贴板），绑在根 View 上
        attachPanelSwipe(popup, 1) { popup.dismiss(); magicHistoryPopup = null }

        // 顶部手柄拖动改高度 + 快速下滑关闭
        val dragHandle = popupView.findViewById<android.view.View>(R.id.drag_handle)
        var dragStartY = 0f
        var dragStartH = 0
        var lastMoveY = 0f
        var lastMoveT = 0L
        var velY = 0f
        var totalDy = 0f
        dragHandle.setOnTouchListener { _: android.view.View, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragStartY = ev.rawY
                    dragStartH = popup.height
                    lastMoveY = ev.rawY
                    lastMoveT = System.currentTimeMillis()
                    velY = 0f
                    totalDy = 0f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - dragStartY
                    totalDy = dy
                    val newH = (dragStartH - dy).toInt().coerceIn(minSheetHeight, maxSheetHeight)
                    popup.update(popupWidth, newH)
                    val now = System.currentTimeMillis()
                    val dt = (now - lastMoveT).coerceAtLeast(1)
                    velY = (ev.rawY - lastMoveY) / dt * 1000f
                    lastMoveY = ev.rawY
                    lastMoveT = now
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    sheetPrefs.edit().putInt("height", popup.height).apply()
                    val downward = totalDy.coerceAtLeast(0f)
                    val closeByDistance = downward > (popup.height * 0.35f) || downward > density * 80f
                    val closeByFling = velY > density * 1500f && downward > density * 40f
                    if (closeByDistance || closeByFling) {
                        popup.dismiss()
                        magicHistoryPopup = null
                    }
                    true
                }
                else -> false
            }
        }

        // ===== 数据列表：置顶项在前，非置顶项按时间倒序（常用标签）=====
        val items = mutableListOf<MagicHistoryManager.MagicRecord>()
        fun rebuildItems() {
            val all = mgr.getRecords()
            items.clear()
            items.addAll(all.filter { it.isPinned })
            items.addAll(all.filter { !it.isPinned })
            // 当前激活命令永远排在最前（第 1 项），置顶从第二项开始
            val active = currentMagicPrompt
            if (active != null) {
                val idx = items.indexOfFirst { it.instruction == active }
                if (idx > 0) {
                    val r = items.removeAt(idx)
                    items.add(0, r)
                }
            }
        }
        rebuildItems()

        // ===== 批量模式状态 =====
        var magicBatchMode = false
        val selectedMagic = mutableSetOf<String>()  // 以 instruction 为 key
        val actionBarNormal = popupView.findViewById<android.widget.LinearLayout>(R.id.action_bar_normal)
        val actionBarBatch = popupView.findViewById<android.widget.LinearLayout>(R.id.action_bar_batch)
        val tvBatchCount = popupView.findViewById<TextView>(R.id.tv_magic_batch_count)
        fun updateMagicBatchCount() { tvBatchCount.text = "已选 ${selectedMagic.size}" }
        fun enterMagicBatch() {
            magicBatchMode = true
            actionBarNormal.visibility = android.view.View.GONE
            actionBarBatch.visibility = android.view.View.VISIBLE
            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        }
        fun exitMagicBatch() {
            magicBatchMode = false
            selectedMagic.clear()
            actionBarNormal.visibility = android.view.View.VISIBLE
            actionBarBatch.visibility = android.view.View.GONE
            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            updateMagicBatchCount()
        }
        // 分类标签：常用(用户记录) + 翻译/语气/长度/格式/内容/特殊/润色(默认指令)
        var currentMagicTab = "常用"
        // 默认分类指令条目（点击直接执行 instruction）
        data class DefEntry(val id: String, val name: String, val instruction: String)
        var defEntries: List<DefEntry> = emptyList()
        // 分类标签内置指令的置顶/删除覆盖（与智能写作的 pinnedSet/deletedBuiltin 对齐）
        val magicModifyPrefs = getSharedPreferences("cesia_magic_modify", MODE_PRIVATE)
        var magicModifyPinned = magicModifyPrefs.getStringSet("pinned_set", emptySet())?.toMutableSet() ?: mutableSetOf()
        var magicModifyDeleted = magicModifyPrefs.getStringSet("deleted_set", emptySet())?.toMutableSet() ?: mutableSetOf()
        fun saveMagicModifyPrefs() {
            magicModifyPrefs.edit()
                .putStringSet("pinned_set", magicModifyPinned)
                .putStringSet("deleted_set", magicModifyDeleted)
                .apply()
        }
        fun isMagicPinned(instruction: String): Boolean = magicModifyPinned.contains(instruction)
        fun rebuildDefEntries() {
            val ids = if (currentMagicTab == "常用") emptyList()
            else CategorizedCommandMenu.getCommandIdsForTab(this@CesiaInputMethod, false, currentMagicTab)
            defEntries = ids.mapNotNull { id ->
                CategorizedCommandMenu.getInstruction(id)?.let { DefEntry(id, it.name, it.instruction) }
            }.filter { !magicModifyDeleted.contains(it.instruction) }
        }

        val btnAdd = popupView.findViewById<TextView>(R.id.btn_magic_add)
        val btnClose = popupView.findViewById<TextView>(R.id.btn_magic_close)
        val btnSelectAll = popupView.findViewById<TextView>(R.id.btn_magic_select_all)
        val btnBatchCancel = popupView.findViewById<TextView>(R.id.btn_magic_batch_cancel)
        val btnBatchAll = popupView.findViewById<TextView>(R.id.btn_magic_batch_all)
        val btnBatchPin = popupView.findViewById<TextView>(R.id.btn_magic_batch_pin)
        val btnBatchDelete = popupView.findViewById<TextView>(R.id.btn_magic_batch_delete)
        val btnBatchDeleteAll = popupView.findViewById<TextView>(R.id.btn_magic_batch_delete_all)
        val tabContainer = popupView.findViewById<android.widget.LinearLayout>(R.id.category_tab_container)

        // 批量选择（□）
        btnSelectAll.setOnClickListener { enterMagicBatch() }
        btnBatchCancel.setOnClickListener { exitMagicBatch() }
        btnBatchAll.setOnClickListener {
            if (currentMagicTab == "常用") for (r in items) selectedMagic.add(r.instruction)
            else for (e in defEntries) selectedMagic.add(e.instruction)
            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            updateMagicBatchCount()
        }
        btnBatchPin.setOnClickListener {
            val sel = selectedMagic
            if (sel.isEmpty()) { updateStatus("请先选择"); return@setOnClickListener }
            if (currentMagicTab == "常用") {
                val ids = items.filter { sel.contains(it.instruction) }.map { it.id }.toSet()
                mgr.setPinned(ids, true)
            } else {
                magicModifyPinned.addAll(sel)
                saveMagicModifyPrefs()
            }
            selectedMagic.clear()
            rebuildItems(); rebuildDefEntries(); (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            updateStatus("⤒ 已批量置顶 ${sel.size} 条")
            exitMagicBatch()
        }
        btnBatchDelete.setOnClickListener {
            val sel = selectedMagic
            if (sel.isEmpty()) { updateStatus("请先选择"); return@setOnClickListener }
            if (currentMagicTab == "常用") {
                val toDel = items.filter { sel.contains(it.instruction) }
                mgr.removeRecords(toDel.map { it.id })
                val updated = mgr.getRecords()
                if (currentMagicPrompt != null && updated.none { it.instruction == currentMagicPrompt }) {
                    currentMagicPrompt = mgr.getActiveInstruction()
                }
            } else {
                magicModifyDeleted.addAll(sel)
                saveMagicModifyPrefs()
            }
            selectedMagic.clear()
            rebuildItems(); rebuildDefEntries(); (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            updateStatus("⊗ 已批量删除 ${sel.size} 条")
            exitMagicBatch()
        }
        btnBatchDeleteAll.setOnClickListener {
            if (currentMagicTab == "常用") {
                mgr.clearAll()
                currentMagicPrompt = null
            } else {
                for (e in defEntries) magicModifyDeleted.add(e.instruction)
                saveMagicModifyPrefs()
            }
            selectedMagic.clear()
            rebuildItems(); rebuildDefEntries(); (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            updateStatus("⊗ 已清空${if (currentMagicTab == "常用") "全部魔法" else "当前分类"}")
            exitMagicBatch()
        }

        // 新增魔法
        btnAdd.setOnClickListener {
            popup.dismiss()
            magicHistoryPopup = null
            enterMagicEditMode(mgr)
        }

        // 右上角 X 关闭
        btnClose.setOnClickListener {
            popup.dismiss()
            magicHistoryPopup = null
        }

        // 追踪当前编辑状态
        var editingPosition = -1
        var hasFocusedEdit = false

        fun notifyChanged() {
            if (currentMagicTab == "常用") rebuildItems() else rebuildDefEntries()
            (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        }

        // ===== 底部按钮：新增魔法 =====
        btnAdd.setOnClickListener {
            popup.dismiss()
            enterMagicEditMode(mgr)
        }

        // 构建标签栏
        val magicTabs = CategorizedCommandMenu.getTabOrder(false)
        fun selectMagicTab(tab: String) {
            currentMagicTab = tab
            for (i in 0 until tabContainer.childCount) {
                val tv = tabContainer.getChildAt(i) as? android.widget.TextView
                val active = tv?.tag == tab
                // 未选中标签：亮色下深灰、暗色下浅灰（原先硬编码 0xFF666666，暗背景上几乎看不见）
                tv?.setTextColor(if (active) themeAccent else if (isDarkTheme) 0xFFAAAAAA.toInt() else 0xFF666666.toInt())
                tv?.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            notifyChanged()
        }
        tabContainer.removeAllViews()
        magicTabs.forEach { tab ->
            val tv = android.widget.TextView(this@CesiaInputMethod).apply {
                text = tab
                textSize = 14f
                setPadding(20, 0, 20, 0)
                tag = tab
                gravity = android.view.Gravity.CENTER
                setOnClickListener { selectMagicTab(tab) }
            }
            tabContainer.addView(tv)
        }
        selectMagicTab("常用")

        gridView.adapter = object : android.widget.BaseAdapter() {
// endregion 魔法历史菜单

// region 候选适配器
            override fun getCount() = if (currentMagicTab == "常用") items.size else defEntries.size
            override fun getItem(p: Int) = if (currentMagicTab == "常用") items[p] else defEntries[p]
            override fun getItemId(p: Int) = if (currentMagicTab == "常用") items[p].id.hashCode().toLong() else p.toLong()

            override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val v = cv ?: inflater.inflate(R.layout.item_magic_grid, parent, false)
                val tv = v.findViewById<TextView>(R.id.tv_magic_text)
                val cardBg = if (isDarkTheme) R.drawable.magic_item_bg_dark else R.drawable.magic_item_bg
                // 暗色下的正文色：下面各分支（置顶/未激活）原先硬编码 0xFF333333 深灰，
                // 在深色卡片上几乎不可见 —— 这是「智能修改菜单黑暗模式仍显示白底深字」的根因。
                val magicTextColor = if (isDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt()
                tv.setBackgroundResource(cardBg)
                tv.setTextColor(magicTextColor)
                val et = v.findViewById<android.widget.EditText>(R.id.et_magic_edit)
                // 行内编辑框同样跟随暗色，否则编辑时深色卡片上是深色字
                et.setTextColor(magicTextColor)
                et.setHintTextColor(if (isDarkTheme) 0xFF888888.toInt() else 0xFF999999.toInt())
                val cb = v.findViewById<android.widget.CheckBox>(R.id.cb_magic_select)
                val showCb = magicBatchMode
                cb.visibility = if (showCb) android.view.View.VISIBLE else android.view.View.GONE
                if (showCb) {
                    val inst = if (currentMagicTab == "常用") items[p].instruction else defEntries[p].instruction
                    // 先挂监听（引用当前 instruction），再设 isChecked，避免复用旧视图误删已选项
                    cb.setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedMagic.add(inst) else selectedMagic.remove(inst)
                        updateMagicBatchCount()
                    }
                    cb.buttonTintList = android.content.res.ColorStateList.valueOf(themeAccent)
                    cb.isChecked = selectedMagic.contains(inst)
                }
                if (currentMagicTab == "常用") {
                    val record = items[p]
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
                        val isPin = record.isPinned
                        tv.text = record.instruction
                        tv.textSize = 13f
                        tv.maxLines = 2
                        if (isActive) {
                            // 当前激活命令：主题色描边高亮 + 前缀 ✓ 勾选标志（排第 1 项）
                            val d = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = (resources.displayMetrics.density * 8f)
                                setStroke((resources.displayMetrics.density * 1.5f).toInt(), themeAccent)
                                val fill = (themeAccent and 0x00FFFFFF) or 0x1A000000
                                setColor(fill)
                            }
                            tv.background = d
                            tv.text = "✓ ${record.instruction}"
                            tv.setTextColor(themeAccent)
                            tv.setTypeface(null, android.graphics.Typeface.BOLD)
                        } else if (isPin) {
                            // 置顶：主题色描边高亮（与智能写作一致）
                            val d = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = (resources.displayMetrics.density * 8f)
                                setStroke((resources.displayMetrics.density * 1.5f).toInt(), themeAccent)
                                val fill = (themeAccent and 0x00FFFFFF) or 0x1A000000
                                setColor(fill)
                            }
                            tv.background = d
                            tv.setTextColor(magicTextColor)
                            tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                        } else {
                            tv.setBackgroundResource(cardBg)
                            tv.setTextColor(magicTextColor)
                            tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                        }
                    }
                } else {
                    // 默认分类指令：只读，点击执行；置顶项用主题色描边高亮（与智能写作一致）
                    et.visibility = View.GONE
                    tv.visibility = View.VISIBLE
                    et.setOnEditorActionListener(null)
                    val entry = defEntries[p]
                    tv.text = entry.name
                    tv.textSize = 13f
                    tv.maxLines = 2
                    val isActive = entry.instruction == currentMagicPrompt
                    if (isActive) {
                        val d = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = (resources.displayMetrics.density * 8f)
                            setStroke((resources.displayMetrics.density * 1.5f).toInt(), themeAccent)
                            val fill = (themeAccent and 0x00FFFFFF) or 0x1A000000
                            setColor(fill)
                        }
                        tv.background = d
                        tv.text = "✓ ${entry.name}"
                        tv.setTextColor(themeAccent)
                        tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    } else if (isMagicPinned(entry.instruction)) {
                        val d = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = (resources.displayMetrics.density * 8f)
                            setStroke((resources.displayMetrics.density * 1.5f).toInt(), themeAccent)
                            val fill = (themeAccent and 0x00FFFFFF) or 0x1A000000
                            setColor(fill)
                        }
                        tv.background = d
                        tv.setTextColor(magicTextColor)
                        tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    } else {
                        tv.setBackgroundResource(cardBg)
                        tv.setTextColor(magicTextColor)
                        tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                }
                return v
            }
        }

        // ===== 单击：打钩+装载+执行+关闭 =====
        gridView.setOnItemClickListener { _, _, position, _ ->
            if (magicBatchMode && currentMagicTab == "常用") {
                // 批量模式：点 item 切换勾选，不执行命令
                val inst = items[position].instruction
                if (selectedMagic.contains(inst)) selectedMagic.remove(inst) else selectedMagic.add(inst)
                (gridView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                updateMagicBatchCount()
                return@setOnItemClickListener
            }
            if (currentMagicTab == "常用") {
                val record = items[position]
                currentMagicPrompt = record.instruction
                popup.dismiss()
                executeSelectedMagic(record.instruction)
            } else {
                val entry = defEntries[position]
                CategorizedCommandMenu.recordUsage(this@CesiaInputMethod, entry.id)
                currentMagicPrompt = entry.instruction
                popup.dismiss()
                executeSelectedMagic(entry.instruction)
            }
        }

        // ===== 长按：弹出操作菜单（置顶 / 取消置顶 / 删除 / 修改）=====
        gridView.setOnItemLongClickListener { _, view, position, _ ->
            val isCommon = currentMagicTab == "常用"
            if (isCommon && position >= items.size) return@setOnItemLongClickListener true
            if (!isCommon && position >= defEntries.size) return@setOnItemLongClickListener true
            val inst = if (isCommon) items[position].instruction else defEntries[position].instruction
            val pinned = if (isCommon) items[position].isPinned else isMagicPinned(inst)
            val menu = android.widget.PopupMenu(this@CesiaInputMethod, view ?: gridView)
            val pinItem = menu.menu.add(0, 1, 0, if (pinned) "↻ 取消置顶" else "⤒ 置顶")
            pinItem.isEnabled = true
            val delItem = menu.menu.add(0, 2, 1, "⊗ 删除")
            delItem.isEnabled = true
            // 修改仅对常用标签的用户/历史记录可用（分类内置指令只读）
            if (isCommon) {
                val modItem = menu.menu.add(0, 3, 2, "✎ 修改")
                modItem.isEnabled = true
            }
            menu.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> {
                        if (isCommon) {
                            mgr.togglePin(items[position].id)
                            rebuildItems()
                        } else {
                            if (pinned) magicModifyPinned.remove(inst) else magicModifyPinned.add(inst)
                            saveMagicModifyPrefs()
                            rebuildDefEntries()
                        }
                        notifyChanged()
                        updateStatus(if (pinned) "↻ 已取消置顶" else "⤒ 已置顶：${inst.take(18)}")
                    }
                    2 -> {
                        if (isCommon) {
                            mgr.removeRecord(items[position].id)
                            rebuildItems()
                        } else {
                            magicModifyDeleted.add(inst)
                            saveMagicModifyPrefs()
                            rebuildDefEntries()
                        }
                        notifyChanged()
                        updateStatus("⊗ 已删除：${inst.take(18)}")
                    }
                    3 -> {
                        // 修改：进入内联编辑（仅常用）
                        editingPosition = position
                        hasFocusedEdit = true
                        notifyChanged()
                        gridView.post {
                            val child = gridView.getChildAt(position - gridView.firstVisiblePosition)
                            val et = child?.findViewById<android.widget.EditText?>(R.id.et_magic_edit)
                            et?.requestFocus()
                        }
                    }
                }
                true
            }
            menu.show()
            true
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

        // 显示在顶部状态栏下方，允许拖到整屏高度（覆盖键盘区）
        popup.showAtLocation(keyboardView, android.view.Gravity.TOP or android.view.Gravity.START, 0, statusBarHeight)
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
    private val OPT_RSS_SOURCE = "◉ RSS源"
    private val OPT_SEARCH = "⌕ 网络搜索"
    private val OPT_LOCAL_LIB = "▤ 本地文库"
    // NOTE: 剪贴板首条开关已按需求移除（2026-08）：UI 不再显示，以下功能保留但不可达
    // private val OPT_CLIPBOARD = "📋 剪贴板首条"

    /** 显示智能写作设置弹窗 */
    private fun showSmartWritingPopup() {
        dlog { "showSmartWritingPopup: 弹窗被调用" }
        try {
            val inflater = android.view.LayoutInflater.from(this)
            val popupView = inflater.inflate(R.layout.popup_smart_writing, null)
            applyAccentToViewTree(popupView, themeAccent)
            applyDarkThemeToViewTree(popupView)
            if (isDarkTheme) popupView.setBackgroundResource(R.drawable.sheet_top_rounded_dark)

            // 标题
            val titleTv = popupView.findViewById<android.widget.TextView>(R.id.tv_smart_title)
            titleTv?.text = smartWritingLabel

            // 选项视图（3个数据源：RSS源、网络搜索、本地文库；剪贴板首条已移除）
            // val optClipboard = popupView.findViewById<TextView>(R.id.opt_clipboard)  // 已移除
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
            // optClipboard.setOnClickListener { toggleOption(it as TextView, "clipboard", OPT_CLIPBOARD) }  // 剪贴板首条已移除
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

            // ===== 分类命令菜单（标签栏 + 网格，纯代码映射，不依赖外部 JSON）=====
            val gvRecords = popupView.findViewById<android.widget.GridView>(R.id.gv_smart_records)
            val tabStrip = popupView.findViewById<HorizontalScrollView>(R.id.category_tab_strip)
            val tabContainer = popupView.findViewById<android.widget.LinearLayout>(R.id.category_tab_container)

            // 用户自定义命令（instruction 文本集合），归入各分类
            val userRecords = mutableListOf<String>()
            loadSmartRecords(userRecords)
            val userRecordSet = userRecords.toSet()
            // 元数据覆盖层（内置删除/修改、用户命令归属分类）
            val meta = loadSmartMeta()
            // 置顶集合（按 instruction 文本，内置/用户通用）
            val pinnedSet = getSharedPreferences("cesia_smart_records", MODE_PRIVATE)
                .getStringSet("pinned_set", emptySet())?.toMutableSet() ?: mutableSetOf<String>()

            // 当前选中的分类标签
            var currentTab = "常用"

            // 根据当前标签计算要显示的「指令 id + 显示名 + instruction」列表
            data class CmdEntry(val id: String, val name: String, val instruction: String, val isUser: Boolean)

            // 取某内置指令当前的 instruction（被修改则用覆盖值）
            fun builtinInstruction(id: String, ins: String): String = meta.overriddenBuiltin[id] ?: ins

            fun buildEntries(): List<CmdEntry> {
                val result = mutableListOf<CmdEntry>()
                // 内置命令（过滤已删除）
                for (ins in com.cesia.input.instruction.InstructionSet.starInstructions) {
                    if (meta.deletedBuiltin.contains(ins.id)) continue
                    val showIns = builtinInstruction(ins.id, ins.instruction)
                    val belongTabs = com.cesia.input.command.CategorizedCommandMenu.getInstructionTabs(ins.id)
                    val shown = if (currentTab == "常用") true
                                else belongTabs.contains(currentTab)
                    if (shown) result.add(CmdEntry(ins.id, ins.name, showIns, false))
                }
                // 用户自定义命令（按 cmdTab 归属）
                for (rec in userRecords) {
                    val tab = meta.cmdTab[rec] ?: "常用"
                    val shown = if (currentTab == "常用") true else tab == currentTab
                    if (shown && result.none { it.instruction == rec }) {
                        result.add(CmdEntry("user_${rec.hashCode()}", rec.take(20), rec, true))
                    }
                }
                // pinned 排前（按 instruction 命中 pinnedSet），当前激活命令置顶第 1 项
                result.sortBy { !pinnedSet.contains(it.instruction) }
                // 激活命令永远排在最前（第 1 项），置顶从第二项开始
                val active = currentSmartPrompt
                if (active != null) {
                    val idx = result.indexOfFirst { it.instruction == active }
                    if (idx > 0) {
                        val e = result.removeAt(idx)
                        result.add(0, e)
                    }
                }
                return result
            }

            // ===== 批量模式状态 =====
            var batchMode = false
            val selectedSet = mutableSetOf<String>()
            val actionBarNormal = popupView.findViewById<android.widget.LinearLayout>(R.id.action_bar_normal)
            val actionBarBatch = popupView.findViewById<android.widget.LinearLayout>(R.id.action_bar_batch)
            val tvBatchCount = popupView.findViewById<android.widget.TextView>(R.id.tv_smart_batch_count)

            fun updateBatchCount() {
                tvBatchCount.text = "已选 ${selectedSet.size}"
            }

            var entries = buildEntries()

            // ===== 列表适配器 =====
            val recordAdapter = object : android.widget.BaseAdapter() {
                override fun getCount() = entries.size
                override fun getItem(p: Int) = entries[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val v = cv ?: android.view.LayoutInflater.from(this@CesiaInputMethod)
                        .inflate(R.layout.item_smart_command, parent, false)
                    // 卡片外框只由内部 tvCommand 绘制（与智能修改/剪贴板一致）。
                    // 原先容器 v 也设了一层同款背景，形成双层描边，观感与另两个菜单不同。
                    v.setBackgroundResource(0)
                    val tvCommand = v.findViewById<android.widget.TextView>(R.id.tv_smart_command)
                    val cb = v.findViewById<android.widget.CheckBox>(R.id.cb_smart_select)
                    val entry = entries[p]
                    tvCommand.text = entry.name
                    val isActive = entry.instruction == currentSmartPrompt
                    val cardBg = if (isDarkTheme) R.drawable.magic_item_bg_dark else R.drawable.magic_item_bg
                    if (isActive) {
                        // 当前激活命令：主题色描边高亮 + 前缀 ✓ 勾选标志（排第 1 项）
                        val d = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = (resources.displayMetrics.density * 8f)
                            setStroke((resources.displayMetrics.density * 1.5f).toInt(), themeAccent)
                            val fill = (themeAccent and 0x00FFFFFF) or 0x1A000000
                            setColor(fill)
                        }
                        tvCommand.background = d
                        tvCommand.text = "✓ ${entry.name}"
                        tvCommand.setTextColor(themeAccent)
                        tvCommand.setTypeface(null, android.graphics.Typeface.BOLD)
                    } else if (pinnedSet.contains(entry.instruction)) {
                        val d = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = (resources.displayMetrics.density * 8f)
                            setStroke((resources.displayMetrics.density * 1.5f).toInt(), themeAccent)
                            val fill = (themeAccent and 0x00FFFFFF) or 0x1A000000
                            setColor(fill)
                        }
                        tvCommand.background = d
                        tvCommand.setTextColor(if (isDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt())
                        tvCommand.setTypeface(null, android.graphics.Typeface.NORMAL)
                    } else {
                        tvCommand.setBackgroundResource(cardBg)
                        tvCommand.setTextColor(if (isDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt())
                        tvCommand.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                    cb.visibility = if (batchMode) android.view.View.VISIBLE else android.view.View.GONE
                    // 先挂监听（引用当前 entry），再设置 isChecked，避免复用旧视图时监听器仍指向旧 entry 误删已选项
                    cb.setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedSet.add(entry.instruction) else selectedSet.remove(entry.instruction)
                        updateBatchCount()
                    }
                    cb.buttonTintList = android.content.res.ColorStateList.valueOf(themeAccent)
                    cb.isChecked = selectedSet.contains(entry.instruction)
                    return v
                }
            }
            gvRecords.adapter = recordAdapter

            fun notifyChanged() {
                entries = buildEntries()
                recordAdapter.notifyDataSetChanged()
            }

            fun isPinnedUser(instruction: String): Boolean = pinnedSet.contains(instruction)

            fun persistPinned() {
                getSharedPreferences("cesia_smart_records", MODE_PRIVATE).edit()
                    .putStringSet("pinned_set", pinnedSet).apply()
            }

            fun togglePin(entry: CmdEntry) {
                if (pinnedSet.contains(entry.instruction)) pinnedSet.remove(entry.instruction)
                else pinnedSet.add(entry.instruction)
                persistPinned()
                notifyChanged()
            }

            fun deleteCmd(entry: CmdEntry) {
                if (entry.isUser) {
                    userRecords.remove(entry.instruction)
                    meta.cmdTab.remove(entry.instruction)
                    saveSmartRecords(userRecords)
                } else {
                    meta.deletedBuiltin.add(entry.id)
                    saveSmartMeta(meta)
                }
                notifyChanged()
            }

            fun modifyCmd(entry: CmdEntry, newText: String) {
                if (entry.isUser) {
                    userRecords.remove(entry.instruction)
                    if (!userRecords.contains(newText)) userRecords.add(newText)
                    saveSmartRecords(userRecords)
                } else {
                    meta.overriddenBuiltin[entry.id] = newText
                    saveSmartMeta(meta)
                }
                notifyChanged()
            }

            fun enterBatchMode() {
                batchMode = true
                actionBarNormal.visibility = android.view.View.GONE
                actionBarBatch.visibility = android.view.View.VISIBLE
                recordAdapter.notifyDataSetChanged()
            }
            fun exitBatchMode() {
                batchMode = false
                selectedSet.clear()
                actionBarNormal.visibility = android.view.View.VISIBLE
                actionBarBatch.visibility = android.view.View.GONE
                recordAdapter.notifyDataSetChanged()
                updateBatchCount()
            }

            // 构建标签栏
            val tabs = CategorizedCommandMenu.getTabOrder(true)
            fun selectTab(tab: String) {
                currentTab = tab
                // 高亮
                for (i in 0 until tabContainer.childCount) {
                    val tv = tabContainer.getChildAt(i) as? android.widget.TextView
                    val active = tv?.tag == tab
                    tv?.setTextColor(if (active) themeAccent else 0xFF666666.toInt())
                    tv?.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
                notifyChanged()
            }
            tabContainer.removeAllViews()
            tabs.forEach { tab ->
                val tv = android.widget.TextView(this@CesiaInputMethod).apply {
                    text = tab
                    textSize = 14f
                    setPadding(20, 0, 20, 0)
                    tag = tab
                    gravity = android.view.Gravity.CENTER
                    setOnClickListener { selectTab(tab) }
                }
                tabContainer.addView(tv)
            }
            selectTab("常用")

            // 拖动识别：在 GridView 上滑动（哪怕小幅）不应触发 item click（避免复选框状态被误翻转）
            var gvDownY = 0f
            var gvMoved = false
            gvRecords.setOnTouchListener { _: android.view.View, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { gvDownY = ev.y; gvMoved = false }
                    android.view.MotionEvent.ACTION_MOVE -> { if (Math.abs(ev.y - gvDownY) > 8f) gvMoved = true }
                }
                false  // 不消费事件，交给 GridView 正常处理
            }

            // 单击：批量模式下切换勾选；普通模式执行该命令（调用AI）
            gvRecords.setOnItemClickListener { _: android.widget.AdapterView<*>?, _: android.view.View?, position: Int, _: Long ->
                if (gvMoved) return@setOnItemClickListener  // 拖动过则不视为点击
                if (position < entries.size) {
                    val entry = entries[position]
                    if (batchMode) {
                        // 批量模式：点 item 切换勾选，不触发命令
                        if (selectedSet.contains(entry.instruction)) selectedSet.remove(entry.instruction)
                        else selectedSet.add(entry.instruction)
                        recordAdapter.notifyDataSetChanged()
                        updateBatchCount()
                        return@setOnItemClickListener
                    }
                    if (!entry.isUser) CategorizedCommandMenu.recordUsage(this@CesiaInputMethod, entry.id)
                    currentSmartPrompt = entry.instruction
                    getSharedPreferences("cesia_smart_records", MODE_PRIVATE).edit()
                        .putString("active_instruction", currentSmartPrompt).apply()
                    smartWritingPopup?.dismiss()
                    smartWritingPopup = null
                    executeSmartCommand(entry.instruction)
                }
            }

            // 长按：弹出操作菜单（置顶 / 删除 / 修改）—— 仅自定义命令
            gvRecords.setOnItemLongClickListener { _: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, _: Long ->
                if (position < entries.size) {
                    val entry = entries[position]
                    val menu = android.widget.PopupMenu(this@CesiaInputMethod, view ?: gvRecords)
                    val pinned = isPinnedUser(entry.instruction)
                    // 置顶项显示「取消置顶」并可点击；非置顶显示「置顶」
                    val pinItem = menu.menu.add(0, 1, 0, if (pinned) "↻ 取消置顶" else "⤒ 置顶")
                    pinItem.isEnabled = true
                    val delItem = menu.menu.add(0, 2, 1, "⊗ 删除")
                    delItem.isEnabled = true
                    val modItem = menu.menu.add(0, 3, 2, "✎ 修改")
                    modItem.isEnabled = true
                    menu.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> { togglePin(entry); updateStatus(if (isPinnedUser(entry.instruction)) "⤒ 已置顶" else "取消置顶：${entry.instruction.take(18)}") }
                            2 -> { deleteCmd(entry); updateStatus("⊗ 已删除：${entry.instruction.take(18)}") }
                            3 -> { smartWritingPopup?.dismiss(); smartWritingPopup = null; smartEditBuffer.clear(); smartEditBuffer.append(entry.instruction); smartEditMode = true; updateSmartEditStatus() }
                        }
                        true
                    }
                    menu.show()
                }
                true
            }

            // 底部按钮
            val btnAdd = popupView.findViewById<TextView>(R.id.btn_smart_add)
            val btnSelectAll = popupView.findViewById<TextView>(R.id.btn_smart_select_all)
            val btnBatchCancel = popupView.findViewById<android.widget.TextView>(R.id.btn_smart_batch_cancel)
            val btnBatchAll = popupView.findViewById<android.widget.TextView>(R.id.btn_smart_batch_all)
            val btnBatchPin = popupView.findViewById<android.widget.TextView>(R.id.btn_smart_batch_pin)
            val btnBatchDelete = popupView.findViewById<android.widget.TextView>(R.id.btn_smart_batch_delete)
            val btnBatchDeleteAll = popupView.findViewById<android.widget.TextView>(R.id.btn_smart_batch_delete_all)

            // ===== 右上角 X：关闭弹窗 =====
            val btnClose = popupView.findViewById<android.widget.TextView>(R.id.btn_smart_close)
            btnClose.setOnClickListener {
                smartWritingPopup?.dismiss()
                smartWritingPopup = null
            }

            // ===== 批量栏：取消 / 批量置顶 / 批量删除 =====

            // ===== ＋：进入编辑模式输入新命令（记录当前分类）=====
            btnAdd.setOnClickListener {
                pendingSmartAddTab = currentTab
                smartWritingPopup?.dismiss()
                smartWritingPopup = null
                enterSmartEditMode()
            }

            // ===== 批量选择按钮：进入批量模式 =====
            btnSelectAll.setOnClickListener { enterBatchMode() }

            // ===== 批量栏：取消 / 批量置顶 / 批量删除 =====
            btnBatchCancel.setOnClickListener { exitBatchMode() }
            btnBatchPin.setOnClickListener {
                for (cmd in selectedSet) pinnedSet.add(cmd)
                persistPinned()
                notifyChanged()
                updateStatus("⤒ 已批量置顶 ${selectedSet.size} 条")
                exitBatchMode()
            }
            btnBatchDelete.setOnClickListener {
                // selectedSet 存的是 instruction 文本，匹配 entries 删除（内置/用户通用）
                val toDelete = entries.filter { selectedSet.contains(it.instruction) }.toList()
                for (e in toDelete) deleteCmd(e)
                updateStatus("⊗ 已批量删除 ${toDelete.size} 条")
                exitBatchMode()
            }
            btnBatchAll.setOnClickListener {
                // 全选：勾选当前所有可见命令（含内置）
                for (e in entries) selectedSet.add(e.instruction)
                recordAdapter.notifyDataSetChanged()
                updateBatchCount()
            }
            btnBatchDeleteAll.setOnClickListener {
                val all = entries.toList()
                if (all.isEmpty()) { updateStatus("暂无命令"); return@setOnClickListener }
                for (e in all) deleteCmd(e)
                updateStatus("⊗ 已清空当前分类命令")
                exitBatchMode()
            }

            // 弹窗尺寸和定位（底部上滑 sheet 风格）
            val keyboardWidth = keyboardView.width
            val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

            val barHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics
            ).toInt()

            // 选项区高度（1行 × 约40dp + padding）
            val optionHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics
            ).toInt()
            val optionsHeightPx = optionHeightPx + TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
            ).toInt() // padding

            val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
                if (id > 0) resources.getDimensionPixelSize(id) else 88
            }
            val keyboardLocation = IntArray(2)
            keyboardView.getLocationOnScreen(keyboardLocation)
            val keyboardTopScreenY = keyboardLocation[1]
            val totalHeight = (keyboardTopScreenY - statusBarHeight).coerceAtLeast(200)

            // 高度上限放开到整屏（状态栏之下），允许菜单拖到屏幕顶端、覆盖键盘区
            val minSheetHeight = (resources.displayMetrics.density * 160f).toInt()
            val screenH = resources.displayMetrics.heightPixels
            val maxSheetHeight = (screenH - statusBarHeight).coerceAtLeast(minSheetHeight)

            // 记忆上次高度
            val sheetPrefs = getSharedPreferences("cesia_smart_sheet", MODE_PRIVATE)
            val savedH = sheetPrefs.getInt("height", -1)
            val sheetHeight = if (savedH > 0) savedH.coerceIn(minSheetHeight, maxSheetHeight) else maxSheetHeight

            // GridView 使用 weight=1 自动填满剩余空间，无需手动设置高度

            val popup = PopupWindow(popupView, popupWidth, sheetHeight, true)
            popup.isOutsideTouchable = false
            popup.elevation = 8f
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.setFocusable(false)

            // ===== 顶部手柄拖动改高度 + 快速下滑关闭 =====
            val density = resources.displayMetrics.density
            attachPanelSwipe(popup, 0) { smartWritingPopup?.dismiss(); smartWritingPopup = null }  // 智能写作

            // 上限放开到整屏（状态栏之下），允许拖到屏幕顶端
            val maxSheetHeightLocal = maxSheetHeight
            val dragHandle = popupView.findViewById<android.view.View>(R.id.drag_handle)
            var dragStartY = 0f
            var dragStartH = 0
            var lastMoveY = 0f
            var lastMoveT = 0L
            var velY = 0f
            var totalDy = 0f  // 从按下到抬起的累计位移（向下为正）
            dragHandle.setOnTouchListener { _: android.view.View, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dragStartY = ev.rawY
                        dragStartH = popup.height
                        lastMoveY = ev.rawY
                        lastMoveT = System.currentTimeMillis()
                        velY = 0f
                        totalDy = 0f
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - dragStartY
                        totalDy = dy
                        val newH = (dragStartH - dy).toInt().coerceIn(minSheetHeight, maxSheetHeightLocal)
                        popup.update(popupWidth, newH)
                        val now = System.currentTimeMillis()
                        val dt = (now - lastMoveT).coerceAtLeast(1)
                        velY = (ev.rawY - lastMoveY) / dt * 1000f
                        lastMoveY = ev.rawY
                        lastMoveT = now
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        sheetPrefs.edit().putInt("height", popup.height).apply()
                        // 关闭判定（二选一即可，避免误关也保证可关）：
                        // 1) 累计向下位移足够大（> 当前高度 35% 或 > 80dp）→ 视为下拉关闭
                        // 2) 快速向下甩（瞬时速度 > 1500px/s 且向下位移 > 40dp）
                        val downward = totalDy.coerceAtLeast(0f)
                        val closeByDistance = downward > (popup.height * 0.35f) || downward > density * 80f
                        val closeByFling = velY > density * 1500f && downward > density * 40f
                        if (closeByDistance || closeByFling) {
                            smartWritingPopup?.dismiss()
                            smartWritingPopup = null
                        }
                        true
                    }
                    else -> false
                }
            }

            popup.setOnDismissListener {
                smartWritingPopup = null
                stopMagicButtonGlow()
                btnMagic.background = makeKeyBgDrawable(currentKeyBg)
                btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
            }

            // 从顶部状态栏下方开始，允许拖到整屏高度（覆盖键盘区）
            popup.showAtLocation(keyboardView, android.view.Gravity.TOP or android.view.Gravity.START, 0, statusBarHeight)
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
        dlog { "executeSmartCommand: command=$command" }
        val selectedOptions = getSmartWritingSelection()
        dlog { "executeSmartCommand: selectedOptions=$selectedOptions" }
        // 立即显示状态，让用户知道正在处理
        updateStatus("智能写作处理中")

        // 获取剪贴板内容（剪贴板首条开关已移除 2026-08：不再作为默认上下文来源）
        val clipboardText = ""
        // 旧逻辑（已禁用）：
        // val clipboardText = if (selectedOptions.contains("clipboard")) { ... } else ""
        dlog { "executeSmartCommand: clipboardText=${clipboardText.length} chars" }
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

                    dlog { "SearXNG query: $finalQuery" }
                    withContext(Dispatchers.Main) { updateStatus("🔍 正在搜索：${finalQuery.take(20)}...") }
                    val tavilyResults = performSearXNGSearch(finalQuery)
                    dlog { "SearXNG results: ${tavilyResults.length} chars" }
                    if (tavilyResults.isNotEmpty()) {
                        promptParts.add("【网络搜索】\n$tavilyResults")
                    }
                }

                val ic = currentInputConnection ?: run {
                    withContext(Dispatchers.Main) {
                        isAiProcessing = false
                        updateStatus("无法获取输入连接")
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
                        dlog { "LocalLib: loaded $libName (${libContent.length} chars)" }
                    }
                }

                val textBefore = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                if (textBefore.isNotEmpty()) {
                    promptParts.add("【当前文本】\n$textBefore")
                }

                val fullPrompt = promptParts.joinToString("\n\n") + "\n\n只输出结果："

                dlog { "SmartWriting prompt: ${fullPrompt.take(200)}..." }
                withContext(Dispatchers.Main) { updateStatus("🤖 AI 正在生成...") }

                // 根据本地/云端模式选择执行路径
                val useLocal = isLocalPolishMode() && modelManager.hasAiModel()
                dlog { "executeSmartCommand: useLocal=$useLocal, cloudMode=$cloudMode, hasAiModel=${modelManager.hasAiModel()}" }
                val result = if (useLocal) {
                    val modelFile = modelManager.getInstalledAiModelFile()
                    if (modelFile != null && modelFile.exists() && !aiEngine.isModelLoaded()) {
                        val configPath = if (modelFile.isDirectory) File(modelFile, "config.json").absolutePath else modelFile.absolutePath
                        aiEngine.loadLocalModel(configPath)
                    }
                    aiEngine.polishWithPrompt(fullPrompt)
                } else {
                    val polishService = typelessEngine?.getPolishService()
                    dlog { "executeSmartCommand: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(50) ?: "null"}" }
                    polishService?.polishWithPrompt(fullPrompt)
                }

                dlog { "executeSmartCommand: result=${result?.take(100) ?: "NULL"}, resultIsNull=${result == null}" }
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
                        updateStatus("无输出")
                    }
                }
            } catch (e: Exception) {
                Log.e("Cesia", "executeSmartCommand failed", e)
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    updateStatus("操作失败")
                }
            }
        }
    }

    /** Tavily Search API（互联网搜索）
     *  支持多个 API Key：优先用当前选中的 key，失败后依次尝试历史记录里的其他 key（避免“只能1条/新键顶旧键”）
     */
    private fun performSearXNGSearch(query: String): String {
        dlog { "TavilySearch: start, query=$query" }
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
                dlog { "TavilySearch[$idx]: HTTP $code" }
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
        dlog { "TavilySearch: all keys failed or empty" }
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

    /** 加载智能写作命令记录 - 带置顶状态 */
    private fun loadSmartRecords(list: MutableList<String>) {
        try {
            val prefs = getSharedPreferences("cesia_smart_records", MODE_PRIVATE)
            val recordsJson = prefs.getString("records_json", "") ?: ""
            val pinnedSet = prefs.getStringSet("pinned_set", emptySet())?.toMutableSet() ?: mutableSetOf<String>()
            if (recordsJson.isNotEmpty()) {
                list.clear()
                val arr = org.json.JSONArray(recordsJson)
                val pinnedItems = mutableListOf<String>()
                val normalItems = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val instruction = obj.getString("instruction")
                    if (pinnedSet.contains(instruction)) {
                        pinnedItems.add(instruction)
                    } else {
                        normalItems.add(instruction)
                    }
                }
                list.addAll(pinnedItems)
                list.addAll(normalItems)
            } else {
                // 首次使用：注入生成类标准指令
                list.clear()
                list.addAll(com.cesia.input.instruction.InstructionSet.starInstructions.map { it.name })
                saveSmartRecords(list)
            }
        } catch (e: Exception) {
            Log.e("Cesia", "loadSmartRecords 异常", e)
        }
    }

    /** 保存智能写作命令记录 - 带置顶状态 */
    private fun saveSmartRecords(list: List<String>) {
        try {
            val prefs = getSharedPreferences("cesia_smart_records", MODE_PRIVATE)
            val arr = org.json.JSONArray()
            // 提取置顶项（通过 pinned_set 记录）
            val pinnedSet = prefs.getStringSet("pinned_set", emptySet())?.toMutableSet() ?: mutableSetOf<String>()
            for (item in list) {
                arr.put(org.json.JSONObject().apply {
                    put("instruction", item)
                })
            }
            prefs.edit()
                .putString("records_json", arr.toString())
                .putStringSet("pinned_set", pinnedSet)
                .apply()
        } catch (e: Exception) {
            Log.e("Cesia", "saveSmartRecords 异常", e)
        }
    }

    /**
     * 智能写作命令元数据（覆盖层）：
     *  - deletedBuiltin: 被删除的内置命令 id 集合
     *  - overriddenBuiltin: 被修改的内置命令 id -> 新 instruction 文本
     *  - cmdTab: 用户自定义命令 instruction 文本 -> 归属分类 tab
     * 持久化到 cesia_smart_meta（JSON）
     */
    private data class SmartMeta(
        val deletedBuiltin: MutableSet<String> = mutableSetOf(),
        val overriddenBuiltin: MutableMap<String, String> = mutableMapOf(),
        val cmdTab: MutableMap<String, String> = mutableMapOf()
    )

    private fun loadSmartMeta(): SmartMeta {
        val meta = SmartMeta()
        try {
            val prefs = getSharedPreferences("cesia_smart_meta", MODE_PRIVATE)
            val json = prefs.getString("meta", "") ?: ""
            if (json.isNotEmpty()) {
                val obj = org.json.JSONObject(json)
                val del = obj.optJSONArray("deleted")
                if (del != null) for (i in 0 until del.length()) meta.deletedBuiltin.add(del.getString(i))
                val ov = obj.optJSONObject("overridden")
                if (ov != null) ov.keys().forEach { meta.overriddenBuiltin[it] = ov.getString(it) }
                val tabs = obj.optJSONObject("tabs")
                if (tabs != null) tabs.keys().forEach { meta.cmdTab[it] = tabs.getString(it) }
            }
        } catch (e: Exception) {
            Log.e("Cesia", "loadSmartMeta 异常", e)
        }
        return meta
    }

    private fun saveSmartMeta(meta: SmartMeta) {
        try {
            val obj = org.json.JSONObject()
            val del = org.json.JSONArray()
            for (id in meta.deletedBuiltin) del.put(id)
            obj.put("deleted", del)
            val ov = org.json.JSONObject()
            for ((k, v) in meta.overriddenBuiltin) ov.put(k, v)
            obj.put("overridden", ov)
            val tabs = org.json.JSONObject()
            for ((k, v) in meta.cmdTab) tabs.put(k, v)
            obj.put("tabs", tabs)
            getSharedPreferences("cesia_smart_meta", MODE_PRIVATE).edit().putString("meta", obj.toString()).apply()
        } catch (e: Exception) {
            Log.e("Cesia", "saveSmartMeta 异常", e)
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
        dlog { "executeSmartWriting: selected=${selectedOptions.size}" }
        if (selectedOptions.isEmpty()) {
            updateStatus("请先设置写作选项")
            return
        }

        // 构建语境
        val contextParts = mutableListOf<String>()

        if (selectedOptions.contains("clipboard")) {
            val clipboardText = getClipboardFirstNonPinned()
            dlog { "executeSmartWriting: clipboard=${clipboardText.length} chars" }
            if (clipboardText.isNotEmpty()) {
                contextParts.add("参考内容：\n$clipboardText")
            }
        }
        if (selectedOptions.contains("local_text")) {
            val localTextContent = readLocalTextFile()
            dlog { "executeSmartWriting: local_text=${localTextContent.length} chars" }
            if (localTextContent.isNotEmpty()) {
                contextParts.add("本地文本：\n$localTextContent")
            }
        }
        if (selectedOptions.contains("rss_cache")) {
            val rssCache = RssFetchManager.readCache(this@CesiaInputMethod)
            dlog { "executeSmartWriting: rss_cache=${rssCache.length} chars" }
            if (rssCache.isNotBlank()) {
                contextParts.add("RSS缓存：\n$rssCache")
            }
        }
        if (selectedOptions.contains("search")) {
            contextParts.add("搜索模式：需要联网获取相关信息")
        }

        if (contextParts.isEmpty()) {
            updateStatus("未获取到语境内容")
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

        dlog { "executeSmartWriting: prompt length=${prompt.length}" }
        isAiProcessing = true
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // 根据本地/云端模式选择执行路径
                val useLocal = isLocalPolishMode() && modelManager.hasAiModel()
                dlog { "executeSmartWriting: useLocal=$useLocal, cloudMode=$cloudMode, hasAiModel=${modelManager.hasAiModel()}" }
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
                    dlog { "executeSmartWriting: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(50) ?: "null"}" }
                    polishService?.polishWithPrompt(prompt)
                }

                dlog { "executeSmartWriting: result=${result?.take(80) ?: "null"}, isNullOrEmpty=${result.isNullOrEmpty()}" }
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    if (result != null && result.isNotEmpty()) {
                        ic.commitText(result, 1)
                        updateStatus(" 智能写作已完成")
                    } else {
                        updateStatus("智能写作无输出")
                    }
                }
            } catch (e: Exception) {
                Log.e("Cesia", "executeSmartWriting failed", e)
                withContext(Dispatchers.Main) {
                    isAiProcessing = false
                    updateStatus("操作失败")
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
    // 新增智能写作命令时记录的归属分类（由弹窗 btnAdd 设置）
    private var pendingSmartAddTab: String = "常用"

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
    }

// endregion 候选适配器

// region 魔法编辑
    // ======================== 魔法编辑模式 ========================

    /** 更新魔法编辑模式状态栏：显示已输入内容 + Rime 当前拼音 */
    private fun updateMagicEditStatus() {
        val comp = rimeEngine.composingText
        val display = magicEditBuffer.toString() + comp
        if (display.isEmpty()) {
            updateStatus("✏️ 输入智能修改指令...（按发送键保存）")
        } else {
            updateStatus("✏️ $display")
        }
        // 复用正常的候选栏更新流水线，保证 T9过滤/用户词组/简繁/置顶降频 完整处理
        updateCandidateBar()
    }

    /** 进入魔法编辑模式：关闭弹窗，清空缓冲区，等待键盘输入 */
    private fun enterMagicEditMode(mgr: MagicHistoryManager) {
        magicEditMode = true
        magicEditBuffer.clear()
        magicEditMgr = mgr
        updateStatus("✏️ 输入智能修改指令...（按发送键保存）")
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
            if (magicEditMode) updateStatus("已取消新增智能修改命令")
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
        // 复用正常的候选栏更新流水线，保证 T9过滤/用户词组/简繁/置顶降频 完整处理
        updateCandidateBar()
    }

    /** 退出智能写作命令编辑模式 */
    private fun exitSmartEditMode(save: Boolean = false, execute: Boolean = false) {
        if (save && smartEditBuffer.isNotEmpty()) {
            val text = smartEditBuffer.toString().trim()
            if (text.isNotEmpty()) {
                // 写入用户自定义命令（records_json），并记录归属分类
                val userList = mutableListOf<String>()
                loadSmartRecords(userList)
                if (!userList.contains(text)) userList.add(0, text)
                saveSmartRecords(userList)
                // 记录该命令归属当前新增分类
                try {
                    val metaPrefs = getSharedPreferences("cesia_smart_meta", MODE_PRIVATE)
                    val obj = org.json.JSONObject(metaPrefs.getString("meta", "{}"))
                    val tabs = obj.optJSONObject("tabs") ?: org.json.JSONObject()
                    tabs.put(text, pendingSmartAddTab)
                    obj.put("tabs", tabs)
                    metaPrefs.edit().putString("meta", obj.toString()).apply()
                } catch (_: Exception) {}
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
            if (smartEditMode) updateStatus("已取消新增命令")
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
            updateStatus("正在施展魔法")
            return
        }
        val ic = currentInputConnection ?: run {
            updateStatus("无输入框连接")
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
        updateStatus("AI正在处理中")
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
                updateStatus("AI未生成有效内容，请重试")
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
        // 仅在候选栏已显示（有输入内容）时才重新触发候选，避免无候选内容时弹出候选栏
        if (candidateBar.visibility == View.VISIBLE) {
            // 切换后重新触发候选（Rime stub 不支持 setOption，用本地 OpenCC 转换）
            updateCandidateBar()
        }
    }

    /** 逐字组词去重：若 Rime 最后一步返回整串(如"六牛柳")，而前面已上屏"六牛"，
     *  则只返回新增尾巴("柳")。无前缀累积或不是前缀时原样返回。 */
    private fun stripDuplicatePrefix(selected: String): String {
        val soFar = t9ComposedSoFar.toString()
        // 仅当「选中的词比已组词更长」时才截取前缀（Rime 续写返回整段累加词，如 六/牛 后返回 六牛柳 → 只上屏新增的 柳）。
        // 关键修复：单字或等长（如 耗耗 的第二个 耗、豪豪 的第二个 豪）绝不能截取，否则返回空串导致「重复字不上屏、只进状态栏」。
        if (soFar.isNotEmpty() && selected.startsWith(soFar) && selected.length > soFar.length) {
            return selected.substring(soFar.length)
        }
        return selected
    }

    /** 候选词选中上屏：根据当前简繁状态做转换 */
    private fun commitCandidateText(text: String) {
        // 剪贴板搜索编辑模式：把中文候选词写入搜索框而非上屏到编辑器
        if (clipboardSearchActive) {
            // 搜索编辑进行中（smartEditMode 已为真）：中文候选词直接进 smartEditBuffer
            smartEditBuffer.append(if (isTraditional) toTraditional(text) else text)
            rimeEngine.clear()
            updateSmartEditStatus()
            return
        }
        try {
            val output = if (isTraditional) toTraditional(text) else text
            currentInputConnection?.commitText(output, 1)
        } catch (e: Exception) {
            Log.e("Cesia", "commitCandidateText failed: ${e.message}")
        }
    }

    // 把文本直接追加进剪贴板搜索框并实时过滤（弹窗可见时，如粘贴）
    private fun appendToClipboardSearch(text: String) {
        val et = this.etSearch ?: return
        val buf = et.text.toString() + text
        et.setText(buf)
        et.setSelection(buf.length)
        clipboardSearchFilter = buf.trim()
        applyClipboardFilter()
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
                    val v = obj.getString(k)
                    // 格式兼容：
                    //   新版 "码1|码2|码3,频次"
                    //   旧版 "主码,频次"
                    //   更旧 "主码"
                    if (v.contains(",")) {
                        val idx = v.lastIndexOf(',')
                        val codesPart = v.substring(0, idx)
                        val freq = v.substring(idx + 1).toIntOrNull() ?: 1
                        val codeSet = codesPart.split("|").filter { c -> c.isNotEmpty() }.toMutableSet()
                        userPhrases[k] = UserPhraseEntry(codeSet, freq)
                    } else {
                        userPhrases[k] = UserPhraseEntry(mutableSetOf(v), 1)
                    }
                }
            }
        } catch (_: Exception) {}
        // 持久化只存主码（见 saveUserPhrases），全拼/简拼码必须在加载后用真实拼音表补回，
        // 否则重启后「孙珺」只剩原始数字码，输入 sj / sunjun 都召不回。
        // 拼音表是后台异步加载的，这里等它就绪后再补。
        rebuildUserPhraseCodesWhenReady()
    }

    /** 拼音表就绪后，用真实读音为所有用户词补齐全拼码/简拼码（幂等，可重复调用） */
    private fun rebuildUserPhraseCodesWhenReady() {
        if (userPhrases.isEmpty()) return
        if (PinyinMap.isReady) {
            rebuildUserPhraseCodes()
            return
        }
        // 拼音表尚未加载完：后台轮询等待（不阻塞主线程），就绪后回主线程补码
        Thread {
            var waited = 0
            while (!PinyinMap.isReady && waited < 10000) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread }
                waited += 200
            }
            if (PinyinMap.isReady) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { rebuildUserPhraseCodes() }
            }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /**
     * 用真实拼音表重算所有用户词的全拼码/简拼码。
     * 同时清洗历史脏数据：旧版 getPinyinFull() 是 Unicode 区间假映射（孙珺→"op"→67），
     * 登记的全拼/简拼码全是错的，这里按真实读音重算后覆盖。
     * 原始数字码（用户当初实际按出来的那串）始终保留。
     */
    private fun rebuildUserPhraseCodes() {
        var changed = 0
        for ((phrase, entry) in userPhrases) {
            val simplex = toSimplified(phrase)
            val fullCode = toPinyinFull(simplex).let { if (it.isNotEmpty()) pinyinToDigits(it) else "" }
            val simpCode = toPinyinFirstLetters(simplex).let { if (it.isNotEmpty()) pinyinToDigits(it) else "" }
            val before = entry.codes.size
            if (fullCode.isNotEmpty()) entry.codes.add(fullCode)
            if (simpCode.isNotEmpty()) entry.codes.add(simpCode)
            if (entry.codes.size != before) changed++
        }
        if (changed > 0) {
            saveUserPhrases()
            dlog { "用户词库补码完成: $changed/${userPhrases.size} 条新增全拼/简拼码" }
        }
    }

    private fun saveUserPhrases() {
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in userPhrases) {
                // 存储格式：码1|码2|码3,频次
                // 原先只存主码一个，而 load 时又没有反推其余码，导致重启后全拼/简拼码永久丢失。
                val allCodes = v.codes.filter { it.isNotEmpty() }.joinToString("|")
                obj.put(k, "$allCodes,${v.freq}")
            }
            getSharedPreferences("cesia_dict", MODE_PRIVATE).edit()
                .putString("user_phrases_json", obj.toString()).apply()
        } catch (_: Exception) {}
    }

    /** 把一组数字码登记进某词（合并去重），并累加频次 */
    private fun registerUserPhraseCodes(phrase: String, codes: Set<String>, freqDelta: Int = 1) {
        if (phrase.length < 2) return
        val entry = userPhrases[phrase]
        if (entry != null) {
            entry.codes.addAll(codes.filter { it.isNotEmpty() })
            entry.freq += freqDelta
        } else {
            userPhrases[phrase] = UserPhraseEntry(codes.filter { it.isNotEmpty() }.toMutableSet(), freqDelta)
        }
        // 限制规模，避免无限增长：保留频次最高的 500 条
        if (userPhrases.size > 500) {
            val minFreq = userPhrases.values.map { it.freq }.minOrNull() ?: 1
            val keysToRemove = userPhrases.filter { it.value.freq == minFreq }.keys.take(userPhrases.size - 500)
            keysToRemove.forEach { userPhrases.remove(it) }
        }
        saveUserPhrases()
    }
    /** 写入用户词组（接龙整词上屏时调用）：
     *  把「整词 + 数字码」登记进 Cesia 自研词库 userPhrases（持久化到 cesia_dict）。
     *  召回方式：updateCandidateBar 把存词插入候选前列，点击时走「直接上屏语义」(commitCandidateText)，
     *  不走 Rime 索引反查，故不会错位（区别于早期注入 bug）。 */
    private fun addUserPhrase(phrase: String, digits: String) {
        if (phrase.length < 2) return
        // 数字码优先用上层传入的「当次输入数字串」；为空时（联想组词路径）用真实读音反推
        val code = if (digits.isNotEmpty()) digits else {
            val simplex = toSimplified(phrase)
            val fullPy = toPinyinFull(simplex)
            if (fullPy.isNotEmpty()) pinyinToDigits(fullPy) else ""
        }
        if (code.isEmpty()) return
        dlog { "addUserPhrase: '$phrase' code='$code'" }
        registerUserPhraseCodes(phrase, setOf(code), 1)
        if (!PinyinMap.isReady) rebuildUserPhraseCodesWhenReady()
    }

    /** 联想组词路径按纯文本登记（无原始数字串，码由真实拼音表反推），复用 registerUserPhraseCodes。 */
    private fun addUserPhraseByText(phrase: String) {
        if (phrase.length < 2) return
        val simplex = toSimplified(phrase)
        val fullCode = toPinyinFull(simplex).let { if (it.isNotEmpty()) pinyinToDigits(it) else "" }
        val simpCode = toPinyinFirstLetters(simplex).let { if (it.isNotEmpty()) pinyinToDigits(it) else "" }
        val codes = mutableSetOf(fullCode, simpCode).filter { it.isNotEmpty() }.toSet()
        dlog { "addUserPhraseByText: '$phrase' full->'$fullCode' simp->'$simpCode' codes=$codes" }
        if (codes.isNotEmpty()) {
            registerUserPhraseCodes(phrase, codes, 1)
        } else {
            // 拼音表未就绪：先占位（freq=1，无码），就绪后补码
            if (!userPhrases.containsKey(phrase)) {
                userPhrases[phrase] = UserPhraseEntry(mutableSetOf(), 1)
            }
            rebuildUserPhraseCodesWhenReady()
        }
    }

    private fun updateTraditionalButton() {
        // 更新按钮视觉状态：移除高亮方框，保留 selectableItemBackgroundBorderless 的圆形脉冲效果，与云端/主题按钮统一
        if (::btnTraditional.isInitialized) {
            // 简体模式显示"简"字，正体模式显示"正"字
            btnTraditional.text = if (isTraditional) "正" else "简"
            btnTraditional.setTextColor(if (isTraditional) themeAccent else 0xFF888888.toInt())
            // 不再 setBackgroundColor，保留圆形脉冲(ripple)效果
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
        // 每次激活键盘时，根据模型就绪情况重新评估默认云/本地模式
        // 如果用户在设置页测试通过云端模型，云字会自动亮起
        loadCloudMode()
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
                updateStatus("语音: Google（Sherpa 库未加载: $reason）")
            } else if (!hasLocalModel) {
                Log.w("Cesia", "语音后端: Google（本地模式但 Sherpa 模型未安装）")
                updateStatus("语音: Google（Sherpa 模型未安装）")
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
            updateStatus("同传管理器未初始化")
            return
        }
        if (!mgr.isInitialized()) {
            updateStatus("同传未就绪，请长按语音键切换到同传模式")
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
            voiceEngineScope.launch(Dispatchers.Main) { updateStatus(text) }
        }
        mgr.onTranslated = { text ->
            voiceEngineScope.launch(Dispatchers.Main) { updateStatus("🌐 $text") }
        }
        mgr.onError = { error ->
            voiceEngineScope.launch(Dispatchers.Main) { updateStatus("同传错误：$error") }
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
                    updateStatus("语音启动失败")
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
                updateStatus("正在收听")
                startWhisperRecordingAsync()
            }
            VoiceChoice.GOOGLE -> {
                updateStatus("正在收听")
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
        updateStatus("正在收听·锁定")
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
            updateStatus("正在收听·锁定")
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
            updateStatus("云端语音启动失败")
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
                        updateStatus("本地语音不可用，已切换云端")
                        startGoogleRecording(PolishChoice.CLOUD_OPENROUTER)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    updateStatus("正在收听")
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
                                    updateStatus(text)
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
                                    updateStatus("已退出语音锁定")
                                    resetToIdle()
                                }
                                "send" -> {
                                    // 发送（中等级）：确认文本（含前缀）+ 发送，然后——若处于锁定态则恢复监听继续识别
                                    val editorInfo = currentInputEditorInfo
                                    val canSend = editorInfo != null &&
                                        (editorInfo.imeOptions and EditorInfo.IME_ACTION_SEND) != 0
                                    if (canSend) {
                                        // 标记“刚在锁定态发送”，避免发送后输入框 finish 触发 onFinishInputView
                                        // → forceExitVoiceMode 把锁定解除。发送后由 finishCommandResumeIfLocked 重新进入监听。
                                        justSentWhileLocked = isVoiceLocked
                                        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
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
                                        updateStatus("请输入内容")
                                        finishCommandResumeIfLocked()
                                    } else {
                                        updateStatus("AI正在处理中")
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
                                        updateStatus("请输入内容")
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
                                        updateStatus("请输入内容")
                                        finishCommandResumeIfLocked()
                                    } else {
                                        Log.i("Cesia", "语音写作命令: '$fullText'")
                                        updateStatus("AI正在处理中")
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
                                        updateStatus("已撤销：$remaining")
                                    } else {
                                        // 已无上一句：清空组合态（用 setComposingText 而非 finish，避免“撤销”二字被提交上屏）
                                        ic.setComposingText("", 1)
                                        recognizedText = ""
                                        updateStatus("已撤销全部")
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
                                    updateStatus("已清空")
                                    isContinuingSession = true
                                    resumeRecordingKeepText()
                                }
                                "restore" -> {
                                    // 恢复（低等级）：把最近一次撤销/清空删掉的内容还原到下划线，继续识别
                                    if (voiceUndoBackup.isNotEmpty()) {
                                        voiceKeptText = voiceUndoBackup
                                        recognizedText = voiceUndoBackup
                                        ic.setComposingText(voiceUndoBackup, 1)
                                        updateStatus("已恢复：$voiceUndoBackup")
                                    } else {
                                        updateStatus("无可恢复内容")
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
                    updateStatus("语音启动失败")
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
        val text = if (isTraditional) toTraditional(voiceEngine.convertChineseDigitsToArabic(rawText))
                   else voiceEngine.convertChineseDigitsToArabic(rawText)
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
            updateStatus("未识别到文字")
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
                updateStatus("语音润色中")
                setStatusDot("processing")
                isProcessingResult = true
                polishRecognizedText(text)
            } else if (isCloudPolishAvailable()) {
                // 云端润色模式
                updateStatus("云端润色中")
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
                updateStatus("未识别到文字")
                resetToIdle()
                return
            }
            if (mode) {
                updateStatus("正在润色")
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
        updateStatus("「$text」→ 选择 润色 或 直接上屏")
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
            updateStatus("正在润色")
            setStatusDot("processing")
            isProcessingResult = true
            polishRecognizedText(recognizedText)
        } else if (isRecording) {
            // 说话过程中点击 AI+：立即停止录音，用当前识别文本润色
            val currentText = recognizedText
            stopRecordingAndWait()
            if (currentText.isNotEmpty()) {
                updateStatus("正在润色")
                setStatusDot("processing")
                isProcessingResult = true
                polishRecognizedText(currentText)
            } else {
                // 没有识别到文字，直接退出语音状态
                updateStatus("未识别到文字")
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
            // 语音识别文字上屏（AI× 直接上屏）：繁体模式转繁体
            val outText = if (isTraditional) toTraditional(recognizedText) else recognizedText
            currentInputConnection?.commitText(outText, 1)
            addSentMessage(recognizedText)
            resetToIdle()
        } else if (isRecording) {
            // 说话过程中点击 AI×：立即停止录音，用当前识别文本上屏
            val currentText = recognizedText
            stopRecordingAndWait()
            if (currentText.isNotEmpty()) {
                // 语音识别文字上屏（说话中AI×）：繁体模式转繁体
                val outText = if (isTraditional) toTraditional(currentText) else currentText
                currentInputConnection?.commitText(outText, 1)
                addSentMessage(currentText)
                resetToIdle()
            } else {
                // 没有识别到文字，直接退出语音状态
                updateStatus("未识别到文字")
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
        dlog { "polishRecognizedText: text='${text.take(50)}', useLocalPolish=$useLocalPolish, cloudMode=$cloudMode, isVoiceLocked=$isVoiceLocked" }
        if (useLocalPolish) {
            dlog { "polishRecognizedText: 走本地润色 (MNN)" }
            polishWithLocalAi(text)
        } else if (cloudMode == CloudMode.CLOUD) {
            // 云端润色（OpenRouter）
            dlog { "polishRecognizedText: 走云端润色 (OpenRouter)" }
            polishWithCloud(text)
        } else {
            // LOCAL 模式但没有安装 MNN 模型 → 尝试 fallback 云端
            if (!modelManager.hasAiModel()) {
                dlog { "polishRecognizedText: 本地模型未安装，尝试云端 fallback" }
                polishWithCloud(text)
            } else {
                dlog { "polishRecognizedText: 走本地润色 (MNN)" }
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
            dlog { "polishRecognizedText: 云端润色回调 finalText='${finalText.take(50)}'" }
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
            // 插入润色结果（繁体模式转繁体）
            ic.commitText(if (isTraditional) toTraditional(polishedText) else polishedText, 1)
        } catch (e: Exception) {
            Log.e("Cesia", "replaceTextWithPolish 失败，fallback commitText", e)
            try {
                val ic2 = currentInputConnection ?: return
                ic2.finishComposingText()
                ic2.commitText(if (isTraditional) toTraditional(polishedText) else polishedText, 1)
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
                        updateStatus("AI 模型未安装，使用原文")
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
                            updateStatus("AI 模型加载失败（${loadTime}ms），使用原文")
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
            updateStatus("请输入内容")
            resetToIdle()
            return
        }

        updateStatus("执行指令中")
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
                dlog { "polishWithCommandPrompt: useLocal=$useLocal, cloudMode=$cloudMode, hasAiModel=${modelManager.hasAiModel()}" }
                val result = if (useLocal) {
                    val modelFile = modelManager.getInstalledAiModelFile()
                    if (modelFile != null && modelFile.exists() && !aiEngine.isModelLoaded()) {
                        val configPath = if (modelFile.isDirectory) File(modelFile, "config.json").absolutePath else modelFile.absolutePath
                        aiEngine.loadLocalModel(configPath)
                    }
                    aiEngine.polishWithPrompt(prompt)
                } else {
                    val polishService = typelessEngine?.getPolishService()
                    dlog { "polishWithCommandPrompt: polishService=${polishService != null}, apiUrl=${polishService?.getApiUrl()?.take(50) ?: "null"}" }
                    polishService?.polishWithPrompt(prompt)
                }
                dlog { "polishWithCommandPrompt: result=${result?.take(80) ?: "NULL"}" }
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
                        updateStatus("执行失败，已保留原文")
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
                        // 有选区：恢复选区文字（繁体模式转繁体）
                        ic.commitText(if (isTraditional) toTraditional(text) else text, 1)
                    } else {
                        // 无选区：恢复全文
                        val before = ic.getTextBeforeCursor(64, 0)?.length ?: 0
                        val after = ic.getTextAfterCursor(64, 0)?.length ?: 0
                        if (before > 0 || after > 0) {
                            ic.deleteSurroundingText(before, after)
                        }
                        ic.commitText(if (isTraditional) toTraditional(text) else text, 1)
                    }
                    updateStatus("执行失败，已恢复原文")
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
        // 记录进入符号键盘前的模式，用于返回
        // 只在从非符号键盘进入符号键盘时记录，符号↔符号切换不更新
        if (mode == KeyboardMode.SYMBOL_CN
            && keyboardMode != KeyboardMode.SYMBOL_CN) {
            prevKeyboardMode = keyboardMode
        }
        keyboardMode = mode
        currentKeyboard = when (mode) {
            KeyboardMode.QWERTY -> qwertyKeyboard
            KeyboardMode.SYMBOL_CN -> symbolKeyboardCn
            KeyboardMode.NUMBER -> numberKeyboard
        }
        keyboardView.keyboard = currentKeyboard
        if (mode == KeyboardMode.SYMBOL_CN) applySymbolFlip()
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
        val paddingPx = if (mode == KeyboardMode.SYMBOL_CN) {
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

    // ===== 符号键盘：主/副语言翻转（⇄ 键） =====
    // 合并键盘：主字符=CJ符号，副字符=EN变体。常打英文者点 ⇄ 翻转，
    // 翻后主=EN变体、副=CJ符号，并持久化到 SharedPreferences。
    private var symbolFlipped = false
    private val SYMBOL_FLIP_PREF = "cesia_symbol_flip"

    // 每个符号键的码 → (主字符, 副字符)。翻转时两者互换。
    private val symbolPairMap: Map<Int, Pair<String, String>> by lazy {
        val list = listOf(
            -301 to ("（）" to "()"), -302 to ("《》" to "⟨⟩"), -303 to ("〔〕" to "{}"),
            -304 to ("[]" to "<>"), -305 to ("『』" to "「」"), -306 to ("【】" to "⟦ ⟧"),
            -307 to ("“”" to "‘’"), -308 to ("、" to "`"), -309 to (";" to "；"),
            -310 to ("," to ","), -311 to ("+" to "-"), -312 to ("×" to "÷"),
            -313 to ("√" to "✕"), -314 to ("=" to "≈"), -315 to ("<" to "≤"),
            -316 to (">" to "≥"), -317 to ("%" to "‰"), -318 to ("～" to "~"),
            -319 to ("·" to "∙"), -320 to ("。" to "."), -321 to ("*" to "#"),
            -322 to ("@" to "&"), -323 to ("/" to "\\"), -324 to ("|" to "¦"),
            -325 to ("°" to "′"), -326 to ("∞" to "∾"), -327 to ("■" to "□"),
            -328 to ("★" to "☆"), -329 to ("！" to "!"), -330 to ("?" to "？"),
            -331 to ("①" to "②"), -332 to ("③" to "④"), -333 to ("⑤" to "⑥"),
            -334 to ("⑦" to "⑧"), -335 to ("⑨" to "⑩"), -336 to ("α" to "β"),
            -337 to ("℃" to "℉"), -338 to ("€" to "₿"), -339 to ("¥" to "$"),
            -340 to ("—" to "——"), -341 to ("……" to "…")
        )
        list.toMap()
    }

    // 切换符号键盘时按当前翻转状态刷新每个键的主/副字符，并立即重绘
    private fun applySymbolFlip() {
        val kb = symbolKeyboardCn
        for (key in kb.keys) {
            val primary = key.codes?.firstOrNull() ?: continue
            val pair = symbolPairMap[primary] ?: continue
            val (main, sub) = if (symbolFlipped) (pair.second to pair.first) else (pair.first to pair.second)
            key.label = main
            key.popupCharacters = sub
        }
        keyboardView.invalidateAllKeys()
    }

    private fun toggleSymbolFlip() {
        symbolFlipped = !symbolFlipped
        getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .edit().putBoolean(SYMBOL_FLIP_PREF, symbolFlipped).apply()
        applySymbolFlip()
        updateStatus("主/副切换")
    }

    private fun toggleSymbolKeyboard() {
        if (keyboardMode == KeyboardMode.SYMBOL_CN) {
            // 从符号键盘返回：回到进入符号键盘前的键盘（T9 或全键盘），而非固定回全键盘
            switchToKeyboard(prevKeyboardMode)
            if (prevKeyboardMode == KeyboardMode.NUMBER) resetNumberKeyboardState()
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
        t9ComposedSoFar.clear()       // 重置已组词累积
        t9FullPhrase.clear()          // 重置整串短语累积
        t9ConsumedLen = 0
        t9PendingSeg = ""; t9PendingChars.clear(); t9PendingPageWalk = 4
        lastPendingSig = ""
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
        updateStatus(t9SpellPrefix.toString() + t9DigitQueue.substring(t9SpellCursor))
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
            KeyboardMode.SYMBOL_CN -> {
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
                // 联想态下按数字：先退出联想模式，让新拼音从干净状态开始（避免旧联想接在新输入后面）
                if (isAssociationMode) exitAssociationMode()
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
        // Rime 原生主导：新数字键入时不再维护自研消费计数。仍在 Rime composing 中（原生续写）
        // 才保留已组词累积，否则清空（新组合开始）。
        if (!rimeEngine.isComposing) t9ComposedSoFar.clear()
        // 分词开关：开=简拼（数字间加 ' 分词符），关=全拼（数字直连）
        if (t9DigitQueue.isNotEmpty()) {
            if (t9DigitQueue.length == 1) {
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
                val digits = t9DigitQueue.toString().map { it.digitToInt() }
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
                // 全拼：未锁定字母时数字直连增量喂（Rime 反解 T9 模糊态，首屏给同键候选供选音）；
                // 已锁定字母(t9SpellPrefix 非空)时改用「锁定字母+剩余数字首字母」的精确 feed（同简拼 buildT9SpellFeed），
                // 让 Rime 直接精确出该音候选（如 9426 锁 zhao → 喂 "zhao"），不再依赖客户端拼音注释过滤
                // （T9 schema 候选拼音注释是数字态派生串，字母前缀匹配会全失败→回退成全部同键候选，旧版因此出 xian/xiao）。
                val feed = if (t9SpellPrefix.isNotEmpty()) buildT9SpellFeed() else t9DigitQueue.toString()
                val tFeed = System.currentTimeMillis()
                feedRimeIncrementally(feed)
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

    /** 拼音反推 T9 数字串（ziji → 9454），遇未知字母返回空串 */
    private fun pinyinToDigits(py: String): String {
        val sb = StringBuilder()
        for (ch in py.lowercase()) {
            if (ch == ' ' || ch == '\'' || ch == '·') continue
            val key = t9Map.entries.firstOrNull { it.value.contains(ch) }?.key ?: return ""
            sb.append(key)
        }
        return sb.toString()
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

    /** 轻量重喂：只恢复 Rime 主会话到当前数字队列态，不递归触发 updateCandidateBar（避免刷新死循环）。 */
    private fun processT9InputLight() {
        if (t9DigitQueue.isEmpty()) { rimeEngine.clear(); rimeEngine.createSession(); lastT9Feed = null; return }
        val feed = t9DigitQueue.toString()
        rimeEngine.clear(); rimeEngine.createSession()
        for (ch in feed) rimeEngine.processKey(ch.toString())
        lastT9Feed = feed
    }

      /** 拼纯字母串喂 Rime：已选字母前缀 + 剩余位数字各取首字母占位（如 prefix=ws, queue=97 剩7→取p → wsp） */
    private fun buildT9SpellFeed(): String {
        if (t9DigitQueue.isEmpty()) return ""
        val sb = StringBuilder(t9SpellPrefix)
        val remaining = t9DigitQueue.drop(t9SpellCursor)
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

    /** 全拼逐键选音：按完整拼音前缀匹配（如 wang 锁定 w/a/n/g 时，候选拼音以 "wang" 前缀匹配）。
     *  与 filterCandsBySpellPrefix（首字母匹配，仅适用简拼）不同：全拼每锁定一位字母都要用完整拼音前缀缩窄，
     *  否则锁定到非首字母位（如 wan/g）时首字母匹配为空、回退 cands 导致不再缩窄。 */
    private fun filterCandsByFullPinyinPrefix(cands: List<String>, pinyins: List<String>, prefix: String): List<String> {
        if (prefix.isEmpty()) return cands
        val filtered = cands.mapIndexedNotNull { i, cand ->
            val py = pinyins.getOrElse(i) { "" }
            // 完整拼音（去掉分词符/空格）按前缀匹配；多音节词用完整拼音串拼接匹配（如 "da jia" → "dajia"）
            val full = py.split(SPELL_SPLIT_REGEX).filter { it.isNotEmpty() }.joinToString("")
            if (full.startsWith(prefix)) cand else null
        }
        return if (filtered.isNotEmpty()) filtered else cands
    }

    /** 将用户高频词按词频融入 Rime 候选列表（不破坏整体词频序）。
     *  策略：双指针归并——Rime 候选按原序遍历，用户词按频次降序遍历，
     *  每当用户词频次 >= 当前 Rime 候选的隐含词频（用位置近似）时插入。 */
    private fun mergeByFrequency(rimeCands: List<String>, userPhrasesByFreq: List<String>): List<String> {
        if (userPhrasesByFreq.isEmpty()) return rimeCands
        val merged = mutableListOf<String>()
        var rimeIdx = 0
        var userIdx = 0
        // Rime 候选隐含词频：位置越前词频越高。简单近似：每前进 10 个 Rime 候选，词频档次下降 1 档
        // 用户词频：按实际频次降序，档次 = 100 - userIdx * 10（确保高频用户词能插入前段）
        while (rimeIdx < rimeCands.size && userIdx < userPhrasesByFreq.size) {
            val rimeFreqTier = 100 - (rimeIdx / 10) * 10
            val userFreqTier = 100 - userIdx * 10
            if (userFreqTier >= rimeFreqTier) {
                val up = userPhrasesByFreq[userIdx]
                if (up !in merged) merged.add(up)
                userIdx++
            } else {
                val rc = rimeCands[rimeIdx]
                if (rc !in merged) merged.add(rc)
                rimeIdx++
            }
        }
        while (rimeIdx < rimeCands.size) {
            val rc = rimeCands[rimeIdx]
            if (rc !in merged) merged.add(rc)
            rimeIdx++
        }
        while (userIdx < userPhrasesByFreq.size) {
            val up = userPhrasesByFreq[userIdx]
            if (up !in merged) merged.add(up)
            userIdx++
        }
        return merged
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
        dlog { "spellClick in: letter=$letterIndex pre='$t9SpellPrefix' queue='$t9DigitQueue' cursor=$t9SpellCursor" }
        if (t9SpellCursor >= t9DigitQueue.length) return
        val curDigit = t9DigitQueue[t9SpellCursor]
        val letters = t9Map[curDigit.digitToInt()] ?: return
        if (letterIndex >= letters.length) return
        t9SpellPrefix.append(letters[letterIndex])  // 锁定该位字母
        dlog { "spellClick out: pre='$t9SpellPrefix'" }
        // 选音锁定变化：清空旧的待定段单字缓存，让 refreshPendingChars 按新锁定拼音(如 qi)重拉，
        // 避免残留未锁定时的皮/脾气等无关单字。
        t9PendingChars.clear(); t9PendingPageWalk = 4; lastPendingSig = ""
        processT9Input()                             // 重算（实时收窄候选）
        // 强制刷新状态栏选音进度（复用正常显示逻辑）：避免 sig 去重跳过导致 236 等组合仍显示数字
        if (keyboardMode == KeyboardMode.NUMBER) {
            val remaining = if (t9SpellCursor < t9DigitQueue.length) t9DigitQueue.substring(t9SpellCursor) else ""
            updateStatus(t9SpellPrefix.toString() + remaining)
        }
    }

    /** 刷新候选音：驱动候选栏最左 4 字母点选区。
     *  全选完 / 候选区空 / 非T9模式 时同步隐藏。 */
    private fun updateSpellBar() {
        val showSpell = keyboardMode == KeyboardMode.NUMBER
                && t9SpellCursor < t9DigitQueue.length
                && rimeEngine.hasCandidates
        val spellZone = llT9Spell
        val spellTVs = t9SpellTVs
        if (spellZone == null || spellTVs == null || !showSpell) {
            spellZone?.visibility = android.view.View.GONE
            return
        }
        spellZone.visibility = android.view.View.VISIBLE
        val curDigit = t9DigitQueue[t9SpellCursor]
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
        val wasSymbols = keyboardMode == KeyboardMode.SYMBOL_CN
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
                // 长按符号上屏整串副字符（如 () 、<> ），而非仅第一个字符
                val symbol = popup
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
    private var etSearch: android.widget.EditText? = null

    /**
     * 剪贴板管理器弹窗 — 两列风格，支持置顶/删除/搜索/关闭/长按操作
     */
    private fun showClipboardManagerPopup() {
        try {
            val inflater2 = android.view.LayoutInflater.from(this)
            clipboardPopupView = inflater2.inflate(R.layout.popup_clipboard_manager, null)
            applyAccentToViewTree(clipboardPopupView!!, themeAccent)
            applyDarkThemeToViewTree(clipboardPopupView!!)
            if (isDarkTheme) clipboardPopupView!!.setBackgroundResource(R.drawable.sheet_top_rounded_dark)
            val popupView = clipboardPopupView!!
            val gvClipboard = popupView.findViewById<GridView>(R.id.gv_clipboard_items)
            val etSearch = popupView.findViewById<android.widget.EditText>(R.id.et_clipboard_search)
            this.etSearch = etSearch
            val tvSearchHint = popupView.findViewById<TextView>(R.id.tv_search_edit_hint)
            val tvEmpty = popupView.findViewById<TextView>(R.id.tv_clipboard_empty)

            // 搜索框：点击后隐藏剪贴板菜单，复用智能写作新增的输入法（正常拼音，T9/全键盘皆可）输入中文
            // 弹窗 setFocusable(false)：EditText 默认 focusableInTouchMode=true 时，首次点击会被当作“获取焦点”而吞掉、
            // 第二次点击才触发 onClick，表现为“点两下才隐藏菜单”。设为 false，第一次点击即直接触发 onClick 进入搜索。
            etSearch.isFocusableInTouchMode = false
            etSearch.setOnClickListener {
                if (clipboardSearchActive) return@setOnClickListener
                clipboardSearchActive = true
                smartEditMode = true
                smartEditBuffer.clear()
                smartEditBuffer.append(etSearch.text.toString())
                rimeEngine.clear()
                updateSmartEditStatus()
                updateStatus("搜索剪贴板…（按回车保存）")
                // 隐藏菜单，让出空间给键盘/候选栏输入
                clipboardPopup?.dismiss()
                clipboardPopup = null
            }
            etSearch.setOnFocusChangeListener { _, hasFocus ->
                // 保留焦点变化仅用于外观提示，不再用它驱动编辑模式（避免软键盘弹出冲突）
                tvSearchHint.visibility = if (hasFocus) View.VISIBLE else View.GONE
                tvSearchHint.text = if (hasFocus) "输入搜索关键词…" else ""
                etSearch.hint = if (hasFocus) "" else "🔍 点击搜索…"
            }
            etSearch.addTextChangedListener(object : android.text.TextWatcher {
// endregion 长按检测

// region 剪贴板搜索
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // 搜索框文本变化（如直接粘贴、或回车提交后恢复）即触发过滤
                    clipboardSearchFilter = s?.toString()?.trim() ?: ""
                    applyClipboardFilter()
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

            // 加载剪贴板历史：仅在内存列表为空时（首次/初始化）从持久化+系统剪贴板加载一次，
            // 之后依赖后台 addPrimaryClipChangedListener 实时追加，不再每次打开弹窗重建（避免快速复制丢失/排序混乱）。
            if (clipboardItems.isEmpty()) {
                val clipboardMgr = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                loadClipboardHistoryToClassMembers(clipboardMgr)
                registerClipboardListener()
            }
            dlog { "showClipboardManagerPopup: clipboardItems.size=${clipboardItems.size}, items=${clipboardItems.take(3).map { it.text.take(20) }}" }
            // 初始化过滤（搜索回车后重新弹出时保留已输入过滤词）
            if (!clipboardSearchResuming) {
                clipboardSearchFilter = ""
            }
            applyClipboardFilter()
            if (clipboardSearchResuming) {
                etSearch.setText(clipboardSearchFilter)
                etSearch.setSelection(clipboardSearchFilter.length)
                applyClipboardFilter()
                clipboardSearchResuming = false
            }

            clipboardAdapter = ClipboardAdapter(inflater2, clipboardFilteredItems, this)
            gvClipboard.adapter = clipboardAdapter

            val keyboardWidth = keyboardView.width
            val popupWidth = if (keyboardWidth > 0) keyboardWidth else resources.displayMetrics.widthPixels

            // 获取状态栏高度
            val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
                if (id > 0) resources.getDimensionPixelSize(id) else 88
            }
            // 高度上限放开到整屏（状态栏之下），允许拖到屏幕顶端、覆盖键盘区
            val density = resources.displayMetrics.density
            val minSheetHeight = (density * 160f).toInt()
            val screenH = resources.displayMetrics.heightPixels
            val maxSheetHeight = (screenH - statusBarHeight).coerceAtLeast(minSheetHeight)

            // 记忆上次高度
            val sheetPrefs = getSharedPreferences("cesia_clipboard_sheet", MODE_PRIVATE)
            val savedH = sheetPrefs.getInt("height", -1)
            val totalHeight = if (savedH > 0) savedH.coerceIn(minSheetHeight, maxSheetHeight) else maxSheetHeight

            val popup = PopupWindow(popupView, popupWidth, totalHeight, true)
            popup.isOutsideTouchable = false
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            popup.elevation = 8f
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.setFocusable(false)   // IME 弹窗内保持 false，避免焦点冲突导致 popup dismiss；搜索输入由 Cesia 手写 onKey 分支处理
            clipboardPopup = popup
            // 左右滑动切换面板（智能写作↔智能修改↔剪贴板），绑在根 View 上
            attachPanelSwipe(popup, 2) { popup.dismiss(); clipboardPopup = null }

            // 顶部手柄拖动改高度 + 快速下滑关闭
            val dragHandle = popupView.findViewById<android.view.View>(R.id.drag_handle)
            var dragStartY = 0f
            var dragStartH = 0
            var lastMoveY = 0f
            var lastMoveT = 0L
            var velY = 0f
            var totalDy = 0f
            dragHandle.setOnTouchListener { _: android.view.View, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dragStartY = ev.rawY
                        dragStartH = popup.height
                        lastMoveY = ev.rawY
                        lastMoveT = System.currentTimeMillis()
                        velY = 0f
                        totalDy = 0f
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - dragStartY
                        totalDy = dy
                        val newH = (dragStartH - dy).toInt().coerceIn(minSheetHeight, maxSheetHeight)
                        popup.update(popupWidth, newH)
                        val now = System.currentTimeMillis()
                        val dt = (now - lastMoveT).coerceAtLeast(1)
                        velY = (ev.rawY - lastMoveY) / dt * 1000f
                        lastMoveY = ev.rawY
                        lastMoveT = now
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        sheetPrefs.edit().putInt("height", popup.height).apply()
                        val downward = totalDy.coerceAtLeast(0f)
                        val closeByDistance = downward > (popup.height * 0.35f) || downward > density * 80f
                        val closeByFling = velY > density * 1500f && downward > density * 40f
                        if (closeByDistance || closeByFling) {
                            popup.dismiss()
                            clipboardPopup = null
                        }
                        true
                    }
                    else -> false
                }
            }

            // ===== 批量栏 =====
            val ca = clipboardAdapter as ClipboardAdapter
            val clipActionBarNormal = popupView.findViewById<android.widget.LinearLayout>(R.id.action_bar_normal)
            val clipActionBarBatch = popupView.findViewById<android.widget.LinearLayout>(R.id.action_bar_batch)
            val tvClipBatchCount = popupView.findViewById<TextView>(R.id.tv_clipboard_batch_count)
            val btnClipSelectAll = popupView.findViewById<TextView>(R.id.btn_clipboard_select_all)
            val btnClipBatchCancel = popupView.findViewById<TextView>(R.id.btn_clipboard_batch_cancel)
            val btnClipBatchAll = popupView.findViewById<TextView>(R.id.btn_clipboard_batch_all)
            val btnClipBatchPin = popupView.findViewById<TextView>(R.id.btn_clipboard_batch_pin)
            val btnClipBatchDelete = popupView.findViewById<TextView>(R.id.btn_clipboard_batch_delete)
            val btnClipBatchDeleteAll = popupView.findViewById<TextView>(R.id.btn_clipboard_batch_delete_all)
            fun updateClipBatchCount() { tvClipBatchCount.text = "已选 ${ca.selectedClip.size}" }
            fun enterClipBatch() {
                ca.batchMode = true
                clipActionBarNormal.visibility = android.view.View.GONE
                clipActionBarBatch.visibility = android.view.View.VISIBLE
                ca.notifyDataSetChanged()
            }
            fun exitClipBatch() {
                ca.batchMode = false
                ca.selectedClip.clear()
                clipActionBarNormal.visibility = android.view.View.VISIBLE
                clipActionBarBatch.visibility = android.view.View.GONE
                ca.notifyDataSetChanged()
                updateClipBatchCount()
            }
            btnClipSelectAll.setOnClickListener { enterClipBatch() }
            btnClipBatchCancel.setOnClickListener { exitClipBatch() }
            btnClipBatchAll.setOnClickListener {
                for (it in clipboardFilteredItems) if (!it.isEmpty) ca.selectedClip.add(it.text)
                ca.notifyDataSetChanged()
                updateClipBatchCount()
            }
            btnClipBatchPin.setOnClickListener {
                val sel = ca.selectedClip
                if (sel.isEmpty()) { updateStatus("请先选择"); return@setOnClickListener }
                for (i in clipboardItems.indices) {
                    val it = clipboardItems[i]
                    if (sel.contains(it.text)) clipboardItems[i] = it.copy(isPinned = true)
                }
                ca.selectedClip.clear()
                updateClipboardFavorites(); saveClipboardHistoryFromClassMembers(); applyClipboardFilter()
                updateStatus("⤒ 已批量置顶 ${sel.size} 条")
                exitClipBatch()
            }
            btnClipBatchDelete.setOnClickListener {
                val sel = ca.selectedClip
                if (sel.isEmpty()) { updateStatus("请先选择"); return@setOnClickListener }
                clipboardDeleted.addAll(sel)
                clipboardItems.removeAll { sel.contains(it.text) }
                ca.selectedClip.clear()
                saveClipboardDeleted(); updateClipboardFavorites(); saveClipboardHistoryFromClassMembers(); applyClipboardFilter()
                updateStatus("⊗ 已批量删除 ${sel.size} 条")
                exitClipBatch()
            }
            btnClipBatchDeleteAll.setOnClickListener {
                val removed = clipboardItems.filter { !it.isPinned && !it.isEmpty }.map { it.text }
                clipboardDeleted.addAll(removed)
                clipboardItems.removeAll { !it.isPinned && !it.isEmpty }
                ca.selectedClip.clear()
                saveClipboardDeleted(); updateClipboardFavorites(); saveClipboardHistoryFromClassMembers(); applyClipboardFilter()
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                } catch (_: Exception) {}
                updateStatus("⊗ 已清空（保留置顶）")
                exitClipBatch()
            }

            // 单击：批量模式下切换勾选；普通模式插入文本
            gvClipboard.setOnItemClickListener { _, _, position, _ ->
                val item = clipboardFilteredItems.getOrNull(position) ?: return@setOnItemClickListener
                if (item.isEmpty) return@setOnItemClickListener
                if (ca.batchMode) {
                    if (ca.selectedClip.contains(item.text)) ca.selectedClip.remove(item.text)
                    else ca.selectedClip.add(item.text)
                    ca.notifyDataSetChanged()
                    updateClipBatchCount()
                    return@setOnItemClickListener
                }
                currentInputConnection?.commitText(item.text, 1)

                popup.dismiss()
            }

            // 长按：弹出操作菜单（置顶 / 取消置顶 / 删除 / 修改）
            gvClipboard.setOnItemLongClickListener { _, view, position, _ ->
                val item = clipboardFilteredItems.getOrNull(position) ?: return@setOnItemLongClickListener true
                if (item.isEmpty) return@setOnItemLongClickListener true
                val menu = android.widget.PopupMenu(this@CesiaInputMethod, view ?: gvClipboard)
                val pinned = item.isPinned
                val pinItem = menu.menu.add(0, 1, 0, if (pinned) "↻ 取消置顶" else "⤒ 置顶")
                pinItem.isEnabled = true
                val delItem = menu.menu.add(0, 2, 1, "⊗ 删除")
                delItem.isEnabled = true
                val modItem = menu.menu.add(0, 3, 2, "✎ 修改")
                modItem.isEnabled = true
                menu.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> {
                            clipboardItems.removeAll { it.text == item.text }
                            val toggled = item.copy(isPinned = !item.isPinned, timestamp = System.currentTimeMillis())
                            clipboardItems.add(toggled)
                            resortClipboard()  // 置顶→最前；取消置顶→按时间归位（不再压到末尾）
                            updateClipboardFavorites(); saveClipboardHistoryFromClassMembers(); applyClipboardFilter()
                            updateStatus(if (item.isPinned) "↻ 已取消置顶" else "⤒ 已置顶：${item.text.take(18)}")
                        }
                        2 -> {
                            clipboardItems.removeAll { it.text == item.text }
                            clipboardDeleted.add(item.text); saveClipboardDeleted()
                            saveClipboardHistoryFromClassMembers(); applyClipboardFilter()
                            updateStatus("⊗ 已删除")
                        }
                        3 -> {
                            showClipboardEditDialog(item.text) { newText ->
                                if (newText != item.text) {
                                    clipboardItems.removeAll { it.text == item.text }
                                    clipboardItems.add(0, item.copy(text = newText))
                                    updateClipboardFavorites(); saveClipboardHistoryFromClassMembers(); applyClipboardFilter()
                                }
                            }
                        }
                    }
                    true
                }
                menu.show()
                true
            }

            // 显示在顶部状态栏下方，允许拖到整屏高度（覆盖键盘区）
            popup.showAtLocation(keyboardView, android.view.Gravity.TOP or android.view.Gravity.START, 0, statusBarHeight)
            clipboardPopup = popup

            // 右上角 X 关闭
            val btnClose = popupView.findViewById<TextView>(R.id.btn_clipboard_close)
            btnClose.setOnClickListener {
                clipboardPopup?.dismiss()
                clipboardPopup = null
            }

            popup.setOnDismissListener {
                cancelSendKeyLongPress()
                clipboardPopup = null
                dlog { "clipboardPopup dismissed, clipboardItems.size=${clipboardItems.size}" }
            }

            // 持久化保存（弹窗显示后立即保存当前加载状态）
            saveClipboardHistoryFromClassMembers()
            dlog { "showClipboardManagerPopup: saved to prefs, clipboardItems.size=${clipboardItems.size}" }
        } catch (e: Exception) {
            updateStatus("操作失败")
        }
    }

    private fun loadClipboardHistoryToClassMembers(clipboardMgr: android.content.ClipboardManager?) {
        clipboardItems.clear()
        loadClipboardDeleted()
        try {
            // 1. 从 SharedPreferences 读取持久化历史
            val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
            val historyStr = prefs.getString("history", "") ?: ""
            val favStr = prefs.getString("favorites", "") ?: ""
            dlog { "loadClipboard: historyStr='${historyStr.take(100)}', favStr='${favStr.take(50)}'" }
            val favSet = if (favStr.isNotEmpty()) favStr.split("\n").toSet() else emptySet()
            val historyTexts = if (historyStr.isNotEmpty()) historyStr.split("\n").filter { it.isNotEmpty() }.toSet() else emptySet()

            // 2. 获取系统剪贴板内容
            val sysClipTexts = mutableListOf<String>()
            if (clipboardMgr?.hasPrimaryClip() == true) {
                val clip = clipboardMgr.primaryClip
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val text = clip.getItemAt(i).text?.toString()?.trim() ?: ""
                        if (text.isNotEmpty() && text.length <= 20000) {
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
                    clipboardItems.add(ClipboardItem(text = text, isPinned = false, timestamp = System.currentTimeMillis()))
                } else {
                    sysInHistory.add(text)
                }
            }

            // 4. 加载持久化历史
            // 先加载 sysInHistory 中的条目（系统剪贴板中已在持久化历史的），保持 sysClipTexts 顺序
            // 再加载其余条目（跳过已在第0位处理过的）
            if (historyStr.isNotEmpty()) {
                val historyList = historyStr.split("\n").filter { it.isNotEmpty() }
                // 按历史列表顺序赋递增 timestamp（列表越靠后=越新），保证排序稳定
                historyList.forEachIndexed { idx, text ->
                    val ts = 1000L + idx
                    if (text in sysInHistory) {
                        clipboardItems.add(ClipboardItem(text = text, isPinned = favSet.contains(text), timestamp = ts))
                    } else if (text !in sysClipTexts && text !in clipboardDeleted) {
                        clipboardItems.add(ClipboardItem(text = text, isPinned = favSet.contains(text), timestamp = ts))
                    }
                }
            }

            // 统一排序：置顶项最前，其余按 timestamp 倒序（最新在前）
            resortClipboard()
            // 顺序稳定：sysClipTexts（不在历史的）→ sysInHistory → 其余历史
            // 每次加载顺序一致，不会因系统剪贴板变化而产生循环闪烁
        } catch (_: Exception) {}
        if (clipboardItems.isEmpty()) {
            clipboardItems.add(ClipboardItem(text = "(剪贴板为空)", isPinned = true, isEmpty = true))
        }
        dlog { "loadClipboard: result size=${clipboardItems.size}, first3=${clipboardItems.take(3).map { it.text.take(20) }}" }
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
        // IME 环境不能用 AlertDialog（非 Activity context 会闪退），改用 PopupMenu（与置顶/删除按钮同源，IME 安全）
        val anchor = clipboardPopupView ?: return
        val menu = android.widget.PopupMenu(this, anchor)
        actions.forEachIndexed { i, label -> menu.menu.add(0, i, i, label) }
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
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
                        updateStatus("无法启动搜索")
                    }
                }
                6 -> { // 删除
                    if (clipboardFavorites[item.text] == false) {
                        allItems.remove(item)
                        clipboardDeleted.add(item.text); saveClipboardDeleted()
                        updateClipboardFavorites(); onUpdate()
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
                        updateStatus("已锁定，无法删除")
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
                        updateStatus("无法启动分享")
                    }
                }
            }
            true
        }
        menu.show()
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
                else updateStatus("文本为空，未保存")
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun updateClipboardFavorites() {
        // 原实现从已废弃的 clipboardHistory(恒为空)读取导致 favorites 被清空；
        // 直接复用 saveClipboardHistoryFromClassMembers 统一持久化(历史+收藏)，避免覆盖正确数据。
        saveClipboardHistoryFromClassMembers()
    }

// endregion 剪贴板搜索

// region 剪贴板适配器
    data class ClipboardItem(val text: String, val isPinned: Boolean = false, val isEmpty: Boolean = false, val timestamp: Long = 0L)

    // 剪贴板后台监听是否已注册（避免重复注册）
    private var clipboardListenerRegistered = false

    /** 统一入口：将文本加入剪贴板列表（去重 + 置顶优先 + 按时间倒序），并持久化 */
    private fun addClipboardText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || t.length > 20000) return
        // 去重：若已存在相同文本，更新其时间戳并保留置顶状态
        val existing = clipboardItems.find { it.text == t }
        if (existing != null) {
            clipboardItems.remove(existing)
            clipboardItems.add(existing.copy(timestamp = System.currentTimeMillis()))
        } else {
            clipboardItems.add(ClipboardItem(text = t, isPinned = clipboardFavorites[t] == true, timestamp = System.currentTimeMillis()))
        }
        resortClipboard()
        saveClipboardHistoryFromClassMembers()
        applyClipboardFilter()
    }

    /** 排序规则：置顶项永远最前（保持原有置顶顺序），其余按复制时间倒序（最新在前） */
    private fun resortClipboard() {
        val pinned = clipboardItems.filter { it.isPinned && !it.isEmpty }.toMutableList()
        val normal = clipboardItems.filter { !it.isPinned && !it.isEmpty }
            .sortedByDescending { it.timestamp }
            .toMutableList()
        clipboardItems.clear()
        clipboardItems.addAll(pinned)
        clipboardItems.addAll(normal)
    }

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
            cm.addPrimaryClipChangedListener {
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                    if (text.isNotEmpty()) addClipboardText(text)
                }
            }
            clipboardListenerRegistered = true
        } catch (_: Exception) {}
    }

    private class ClipboardAdapter(
        private val inflater: android.view.LayoutInflater,
        private val items: List<ClipboardItem>,
        private val context: CesiaInputMethod
    ) : android.widget.BaseAdapter() {
        var batchMode = false
        val selectedClip = mutableSetOf<String>()  // 以 text 为 key
        private val accentColor = context.themeAccent
        private val isDarkTheme = context.isDarkTheme
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].text.hashCode().toLong()
        override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
            val v = cv ?: inflater.inflate(R.layout.item_clipboard_grid, parent, false)
            val item = items[p]
            val tv = v.findViewById<TextView>(R.id.tv_clipboard_text)
            val cardBg = if (isDarkTheme) R.drawable.magic_item_bg_dark else R.drawable.magic_item_bg
            // 暗色正文色：下面「置顶/普通」分支原先硬编码 0xFF333333 深灰，会把这里的浅色覆盖掉，
            // 导致黑暗模式下剪贴板文字在深底上几乎看不见。
            val clipTextColor = if (isDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt()
            tv.setBackgroundResource(cardBg)
            tv.setTextColor(clipTextColor)
            val cb = v.findViewById<android.widget.CheckBox>(R.id.cb_clipboard_select)
            cb.visibility = if (batchMode && !item.isEmpty) android.view.View.VISIBLE else android.view.View.GONE
            if (batchMode && !item.isEmpty) {
                cb.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedClip.add(item.text) else selectedClip.remove(item.text)
                }
                cb.buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
                cb.isChecked = selectedClip.contains(item.text)
            }
            if (item.isEmpty) {
                tv.text = item.text
                tv.setTextColor(if (isDarkTheme) 0xFF888888.toInt() else 0xFF999999.toInt())
                tv.textSize = 13f
            } else {
                // 仅置顶项用主题色描边高亮（与智能写作一致），其余用默认背景
                val display = if (item.text.length > 82) item.text.take(82) + "…" else item.text
                tv.text = display
                tv.textSize = 13f
                if (item.isPinned) {
                    val d = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = (context.resources.displayMetrics.density * 8f)
                        setStroke((context.resources.displayMetrics.density * 1.5f).toInt(), accentColor)
                        val fill = (accentColor and 0x00FFFFFF) or 0x1A000000
                        setColor(fill)
                    }
                    tv.background = d
                } else {
                    // 与智能写作/智能修改菜单保持一致：普通项也用卡片背景（含描边）。
                    // 原先亮/暗色都置 null，剪贴板条目没有外框，三个菜单观感不统一。
                    tv.setBackgroundResource(cardBg)
                }
                tv.setTextColor(clipTextColor)
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
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

        // ======================== 英文输入模式拦截（Cesia 自主词库，独立于 Rime） ========================
        if (isEnglishMode) {
            handleEnglishKey(primaryCode)
            return
        }

        // ======================== 剪贴板搜索编辑：复用 smartEditMode 输入法（见下方智能写作编辑模式拦截） ========================

        // ======================== 智能写作命令编辑模式拦截 ========================
        if (smartEditMode) {
            when (primaryCode) {
                // 发送键/回车键：若处于剪贴板搜索编辑，则提交到搜索框并重现菜单；否则保存命令并执行
                -200, 10 -> {
                    val comp = rimeEngine.composingText
                    if (comp.isNotEmpty()) {
                        smartEditBuffer.append(comp)
                        rimeEngine.clear()
                    }
                    if (clipboardSearchActive) {
                        clipboardSearchFilter = smartEditBuffer.toString().trim()
                        clipboardSearchActive = false
                        smartEditMode = false
                        smartEditBuffer.clear()
                        updateStatus("搜索剪贴板：${clipboardSearchFilter}")
                        clipboardSearchResuming = true
                        statusText.text = ""
                        showClipboardManagerPopup()
                        return
                    }
                    exitSmartEditMode(save = true, execute = true)
                    return
                }
                // 返回键：取消并退出编辑模式
                KeyEvent.KEYCODE_BACK -> {
                    rimeEngine.clear()
                    if (clipboardSearchActive) {
                        clipboardSearchActive = false
                        smartEditMode = false
                        smartEditBuffer.clear()
                        clipboardSearchResuming = true
                        statusText.text = ""
                        showClipboardManagerPopup()
                        return
                    }
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
                    if (isAssociationMode) exitAssociationMode()
                    rimeEngine.processKey(primaryCode.toChar())
                    updateSmartEditStatus()
                    return
                }
                // 数字键 0-9：T9模式走T9拼音引擎，全键盘模式选词或追加
                in 48..57 -> {
                    if (isAssociationMode) exitAssociationMode()
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
                    if (isAssociationMode) exitAssociationMode()
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
                    if (isAssociationMode) exitAssociationMode()
                    rimeEngine.processKey(primaryCode.toChar())
                    updateMagicEditStatus()
                    return
                }
                // 数字键 0-9：T9模式走T9拼音引擎，全键盘模式选词或追加
                in 48..57 -> {
                    if (isAssociationMode) exitAssociationMode()
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
                    if (isAssociationMode) exitAssociationMode()
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

        // 空格键调试日志
        if (primaryCode == 32 && keyboardMode != KeyboardMode.NUMBER && !isAsciiMode) {
            dlog { "空格键: composing=$composing hasCands=$hasCands cands=${cands.size} isAscii=$isAsciiMode mode=$keyboardMode" }
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
                    dlog { "中英混输调试: key='${primaryCode.toChar()}' hadComposing=$hadComposing accepted=$accepted nowComposing=${rimeEngine.isComposing} composingText='${rimeEngine.composingText}'" }
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
            32 -> { handleSpaceKey() }

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
                        // 退格：Rime 原生主导下不再维护消费计数，仅在队列被删空时清已组词缓冲
                        if (t9DigitQueue.isEmpty()) t9ComposedSoFar.clear()
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
                                    t9DigitQueue.substring(t9SpellCursor)  // 锁定字母 + 剩余未消费数字(如 t+9=t9)
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

            // ======================== 符号主/副翻转（⇄）========================
            KEYCODE_SWITCH_SYMBOL_LANG -> toggleSymbolFlip()

            // ======================== 数字切换（123）========================
            KEYCODE_SWITCH_NUMBER -> {
                defaultKeyboardLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                defaultKeyboardLongPressRunnable = null
                toggleNumberKeyboard()
            }

            // ======================== 符号库（〰️）========================
            KEYCODE_CONTROL -> showSymbolPanel()
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

            // ======================== 符号键盘按键（-301~-341）=======================
            // 由 symbolPairMap 决定主/副字符（受 ⇄ 翻转影响），点一下上屏整段
            in -341..-301 -> {
                shortPressHandled = true
                val pair = symbolPairMap[primaryCode] ?: return
                val out = if (symbolFlipped) pair.second else pair.first
                // 配对类（长度>1且非「——」「……」等重复符号）上屏后光标停在中间
                val caret = if (out.length > 1) 1 else out.length
                currentInputConnection?.commitText(out, caret)
                // 符号上屏后自动退回进入符号键盘前的键盘（T9/QWERTY），符合「点符号即返回」的交互预期
                switchToDefaultKeyboard()
            }

            // ======================== 其他按键（标点等）=======================
            else -> {
                // T9 模式：按标点符号时，先把候选栏第 0 个（自研顺序显示的那个词/字）上屏，再上屏标点。
                // 避免走 rimeEngine.commit() 取「原生第 0」导致与自研重排后的显示第 0 错位（点甲上乙类回归，
                // 表现为「看到的是拼音、上屏的却是奇」）。
                if (keyboardMode == KeyboardMode.NUMBER && t9DigitQueue.isNotEmpty()) {
                    val word = lastDisplayedCands.firstOrNull()
                    if (!word.isNullOrEmpty()) {
                        commitCandidateText(if (isTraditional) toTraditional(word) else word)
                    }
                    rimeEngine.clear()
                    val c = when (primaryCode) {
                        44 -> '，'; 46 -> '。'; 47 -> '？'
                        65292 -> '，'; 12290 -> '。'; 65307 -> '；'; 65281 -> '！'; 65311 -> '？'
                        65288 -> '（'; 65289 -> '）'
                        else -> primaryCode.toChar()
                    }
                    if (c != '\u0000') ic?.commitText(c.toString(), 1)
                    t9DigitQueue.clear(); t9SpellPrefix.clear(); t9InputBuffer.clear()
                    lastT9Feed = null  // 重置增量喂标记，下次输入从头喂（防首个音被吃）
                    updateStatus(statusIdleText); updateCandidateBar()
                    return
                }
                // 如果当前 composing 是纯英文（如输入llama后按.），直接上屏英文原文 + 标点
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
        // 注意：符号键盘按键码为负值（-301~-341），故需同时放行 primaryCode > 0 与负值符号码
        if (!skipPopupLongPress && (primaryCode > 0 || primaryCode in -341..-301)) {
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

    // 空格键逻辑（单击直接执行）
    private fun handleSpaceKey() {
        val ic = currentInputConnection
        if (keyboardMode == KeyboardMode.NUMBER) {
            if (t9ShiftTemp || t9ShiftLocked) {
                ic?.commitText("0", 1)
                if (!t9ShiftLocked) {
                    t9ShiftTemp = false
                    updateShiftIndicator()
                }
            } else if (t9DigitQueue.isNotEmpty()) {
                val cands = rimeEngine.candidates
                if (cands.isNotEmpty()) {
                    selectCandidateByGlobalIndex(0)
                    if (!isAssociationMode) resetT9State()
                } else {
                    ic?.commitText(" ", 1)
                    resetT9State()
                }
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
            if (isAssociationMode && associationCandidates.isNotEmpty()) {
                val selectedWord = associationCandidates[0]
                val newPrefix = associationPrefix + selectedWord
                val newAssociations = rimeEngine.getAssociations(newPrefix, 100, 500, 10)
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
            } else if (rimeEngine.isComposing || rimeEngine.candidates.isNotEmpty()) {
                selectCandidateByGlobalIndex(0)
                if (keyboardMode == KeyboardMode.QWERTY) rimeEngine.clear()
            } else {
                ic?.commitText(" ", 1)
            }
        }
        if (keyboardMode != KeyboardMode.NUMBER) clearCandidateContent()
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
        // 英文模式：符号键（如问号）经 onText 发送，先上屏当前英文词再上屏符号并补空格
        if (isEnglishMode && text != null) {
            commitEnglishTop()
            currentInputConnection?.commitText("$text ", 1)
            enBuffer.clear()
            enCandidates = emptyList()
            updateCandidateBar()
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
                rimeEngine.selectSchema("pinyin")
                rimeEngine.reload()
            }
            // 英文拼写模式：加载自主词库 + 恢复持久化的中/英文状态
            initEnglishMode()
            updateEnModeButton()
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
            dlog { "onStartInputView: step1 updateVoiceBackend" }
            modelManager.scanExistingModels()
            updateVoiceBackend()
            dlog { "onStartInputView: step2 preloadModels" }
            preloadWhisperModel()
            preloadAiModel()

            // 注册来电监听（来电时自动退出语音模式，避免卡死）
            registerPhoneStateListener()

            // RSS 自动抓取：如果缓存不存在或过期（>1h），后台自动刷新
            dlog { "onStartInputView: step3 autoRefreshRssCache" }
            autoRefreshRssCache()
            dlog { "onStartInputView: step4 done" }
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
            dlog { "autoRefreshRssCache: cache ${if (cacheFile.exists()) "expired" else "missing"}, fetching ${source.name}" }
            voiceEngineScope.launch {
                try {
                    val success = RssFetchManager.fetchAndCache(this@CesiaInputMethod, source)
                    dlog { "autoRefreshRssCache: ${if (success) "success" else "failed"}" }
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
     * 默认模式：哪个模型先就绪（local_ai_ready 或 cloud_ready），就默认哪个模式
     * 如果两个都未就绪，默认本地模式（等待下载）
     */
    private fun loadCloudMode() {
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        val savedMode = prefs.getString("cloud_mode", null)
        
        // 如果有保存的模式，直接恢复
        if (savedMode != null) {
            cloudMode = try {
                CloudMode.valueOf(savedMode)
            } catch (e: Exception) {
                CloudMode.LOCAL
            }
            return
        }
        
        // 首次运行：根据模型就绪情况决定默认模式
        val modelStatus = getSharedPreferences("cesia_model_status", MODE_PRIVATE)
        val localReady = modelStatus.getBoolean("local_ai_ready", false)
        val cloudReady = modelStatus.getBoolean("cloud_ready", false)
        
        cloudMode = when {
            localReady && !cloudReady -> CloudMode.LOCAL      // 只有本地就绪
            cloudReady && !localReady -> CloudMode.CLOUD      // 只有云端就绪
            localReady && cloudReady -> CloudMode.LOCAL       // 都就绪，默认本地
            else -> CloudMode.LOCAL                            // 都未就绪，默认本地（等待下载）
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
                updateStatus("已切换到云端润色模式")
            } else {
                updateStatus("云端模型未通过测试，请到设置页测试 Ollama / OpenRouter")
            }
        } else {
            // 云字 → 切本字
            if (localReady) {
                cloudMode = CloudMode.LOCAL
                updateStatus("已切换到本地润色模式")
            } else {
                // 本字灰度：已下载则直接点亮，未下载则触发下载（键盘状态栏显示进度）
                if (modelManager.hasAiModel()) {
                    markLocalAiReadyInIme()
                } else {
                    updateStatus("手机AI模型未下载，正在开始下载")
                    promptDownloadPhoneAi()
                }
            }
        }
        updateCloudButtonState()
    }

    /** 本字灰度时点击：触发手机AI模型下载（键盘状态栏显示进度，与设置页同步） */
    private fun promptDownloadPhoneAi() {
        val modelInfo = ModelRegistry.getById("qwen35-2b-mnn")
            ?: ModelRegistry.ALL_MODELS.find { it.type == ModelInfo.ModelType.AI } ?: run {
                updateStatus("无可用手机AI模型定义")
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
                    updateStatus("手机AI模型下载失败：${result.exceptionOrNull()?.message}")
                    DownloadProgressBus.emit("手机AI模型", 0.0, failed = true)
                }
            } catch (e: Exception) {
                updateStatus("手机AI模型下载异常：${e.message}")
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
            updateStatus("需要 MNN 和 API 都可用才能锁定")
            return
        }

        if (cloudMode == CloudMode.LOCAL_LOCKED) {
            // 已锁定 → 解锁
            cloudMode = CloudMode.LOCAL
            updateStatus("已解锁，恢复默认本地模式")
        } else {
            // 锁定本地
            cloudMode = CloudMode.LOCAL_LOCKED
            updateStatus("已锁定本地模式（MNN + Zipformer）")
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
            updateStatus("已退出语音锁定")
        } else {
            // 未锁定 → 进入锁定，直接录音（不分裂按钮）
            val recognitionAvailable = isVoiceRecognitionAvailable()
            if (!recognitionAvailable) {
                updateStatus("语音识别不可用，无法进入锁定模式")
                return
            }
            isVoiceLocked = true
            updateMicButtonLockedState()
            // 显示语音锁定模式提示（命令词）
            showVoiceLockHints()
            updateStatus("已进入语音锁定")
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
            updateStatus("$hints")
        }
    }

    /** 显示语音锁定模式提示 */
    private fun showVoiceLockHints() {
        val hints = VoiceEngine.getCommandHints()
        if (hints.isNotEmpty() && ::statusText.isInitialized) {
            updateStatus("语音锁定命令：$hints")
        }
    }

    private var statusLines = mutableListOf<String>()

    private fun updateStatus(msg: String) {
        dlog { "updateStatus: msg='$msg', isRecording=$isRecording, lines=${statusLines.size}" }
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
