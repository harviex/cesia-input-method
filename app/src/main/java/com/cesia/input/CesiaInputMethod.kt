package com.cesia.input

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
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
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnClipboard: ImageButton // 智能修改按钮（魔法书/笔）
    private lateinit var btnMagic: ImageButton // 智能写作按钮（星星/五角星）
    private lateinit var btnSend: ImageButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var voiceWave: View
    private lateinit var btnTheme: TextView
    private lateinit var btnCloud: TextView

    // ---- 主题色动态可调（三维） ----
    private var themeAccent: Int = 0xFF81D8D0.toInt()     // 主色（蒂芙尼蓝），色相调节
    private var themeBgGrayBase: Int = 0xE0                // 背景灰度基础值（0-255）
    private var themeKeyGrayBase: Int = 0xF0               // 按键灰度基础值（0-255）
    private var themePopup: PopupWindow? = null
    private val defaultAccentHsl = hslOf(0xFF81D8D0.toInt())
    private var accentHue: Float = defaultAccentHsl[0]     // 当前色相 0-360
    private var textThemeSize: Int = 1                     // 0=小, 1=中(default), 2=大, 3=超大
    var textGrayScale: Float = 0.5f                        // 0=纯黑, 0.5=基准灰(默认), 1=纯白

    private fun loadThemeColors() {
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        themeAccent = prefs.getInt("theme_accent", 0xFF81D8D0.toInt())
        themeBgGrayBase = prefs.getInt("theme_bg_gray", 0xE0)
        themeKeyGrayBase = prefs.getInt("theme_key_gray", 0xF0)
        accentHue = prefs.getFloat("theme_accent_hue", defaultAccentHsl[0])
        textThemeSize = prefs.getInt("theme_text_size", 1)
        textGrayScale = prefs.getFloat("text_gray_scale", 0.5f)
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

    private fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (ri shl 16) or (gi shl 8) or bi
    }

// endregion 视图与UI

// region 核心组件与引擎
    // ======================== 核心组件 ========================
    private var typelessEngine: TypelessEngine? = null
    private lateinit var statsManager: PolishStatsManager
    private lateinit var rimeEngine: RimeEngine
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var modelManager: ModelManager
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
    private var longPressActive = false

// endregion 核心组件与引擎

// region 状态变量
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
    // === 词语联想 ===
    private var associationPrefix = ""      // 当前联想前缀（如 "这个"）
    private var associationCandidates = emptyList<String>()  // 当前联想候选词列表
    private var isAssociationMode = false   // 是否处于联想模式



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

    // 智能修改按钮（魔法书）长按检测
    private var magicBookLongPressTriggered = false
    private var magicBookHandler = Handler(Looper.getMainLooper())
    private var magicBookRunnable: Runnable? = null
    private var magicBookGlowRunnable: Runnable? = null
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
    private var micButtonGlowRunnable: Runnable? = null
    private var micGlowHandler = Handler(Looper.getMainLooper())

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
            110 -> { { sendCtrlKey(KeyEvent.KEYCODE_Y) } }  // n=Redo（前进）
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

// endregion 常量配置

// region 生命周期
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
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.input_view, null)

        keyboardView = view.findViewById(R.id.keyboard_view)
        btnTraditional = view.findViewById(R.id.btn_traditional)
        btnCloud = view.findViewById(R.id.btn_cloud)
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
        btnTheme = view.findViewById(R.id.btn_theme)

        // 本地/云端模式切换已移除，统一使用长按语音键切换

        // 候选词栏
        candidateBar = view.findViewById(R.id.candidate_bar)
        btnCandidateExpand = view.findViewById(R.id.btn_candidate_expand)
        // tvT9Letters/dividerT9 已移除

        // RecyclerView 候选词列表
        rvCandidates = view.findViewById(R.id.rv_candidates)
        candidateAdapter = CandidateAdapter { index, _ ->
            if (rimeEngine.hasCandidates || isAssociationMode) {
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
            98 to "大小",  // b
            122 to "撤销", // z
            110 to "前进",  // n
            109 to "Del",  // m
            // T9 底行功能键副字符（灰色，右上角）
            -108 to "粘贴",  // 粘贴键：副字符
            -109 to "剪切",  // 复制键：副字符
            10 to "撤销"     // 回车键：副字符
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

        // 从 SharedPreferences 加载自定义命令词（跨进程同步：设置页面保存后，IME 启动时读取）
        runCatching {
            val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
            val exit = cmdPrefs.getString("cmd_exit", null)
            val polish = cmdPrefs.getString("cmd_polish", null)
            val finish = cmdPrefs.getString("cmd_finish", null)
            val send = cmdPrefs.getString("cmd_send", null)
            val command = cmdPrefs.getString("cmd_command", null)
            val writing = cmdPrefs.getString("cmd_writing", null)
            if (exit != null && polish != null && finish != null && send != null && command != null && writing != null) {
                VoiceEngine.updateCommandWords(exit, polish, finish, send, command, writing)
                Log.i("Cesia", "初始化: 已加载自定义命令词 exit=$exit, polish=$polish, finish=$finish, send=$send, command=$command, writing=$writing")
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
                statsManager.addRecord(inputText, outputText, duration)
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
                    // 魔法模式停止时，直接用 Google 识别结果触发 AI
                    if (magicStopRequested) {
                        Log.d("Cesia", "onRecognitionComplete: 魔法模式停止中，直接触发 AI")
                        magicStopRequested = false
                        if (text.isNotEmpty()) {
                            handleMagicResult(text)
                        }
                        return@post
                    }
                    // 命令词检测（Google 识别结果走这里）
                    val commandResult = checkVoiceCommandWord(text)
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

        return view
    }

// endregion 生命周期

// region 主题
    // ======================== 主题 ========================

    private fun applyKeyboardTheme() {
        if (isDarkTheme) {
            keyboardView.setBackgroundColor(0xFF0F0F23.toInt())
            (statusText.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
            candidateBar.setBackgroundColor(0xFF16213E.toInt())
            (btnClipboard.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
        } else {
            // 使用动态背景灰度
            val base = themeBgGrayBase
            keyboardView.setBackgroundColor(colorGray(base))
            (statusText.parent as? View)?.setBackgroundColor(colorGray((base - 8).coerceIn(0, 255)))
            candidateBar.setBackgroundColor(colorGray((base + 16).coerceIn(0, 255)))
            (btnClipboard.parent as? View)?.setBackgroundColor(colorGray(base))
            // root_layout
            (keyboardView.parent as? View)?.setBackgroundColor(colorGray((base + 23).coerceIn(0, 255)))
        }
    }

    private fun isSystemDark(): Boolean {
        return (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun colorGray(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return 0xFF000000.toInt() or (c shl 16) or (c shl 8) or c
    }

    /** 实时应用主题色 + 背景灰度 + 按键灰度到所有UI元素 */
    private fun applyThemeColors() {
        // ① 背景灰度
        applyKeyboardTheme()

        // ② 主题色 —— 所有高亮元素
        val accent = themeAccent
        val accentStateList = android.content.res.ColorStateList.valueOf(accent)

        // 简繁切换
        if (::btnTraditional.isInitialized) {
            if (isTraditional) {
                btnTraditional.setTextColor(accent)
                btnTraditional.setBackgroundColor((accent and 0x00FFFFFF) or 0x22000000)
            } else {
                btnTraditional.setTextColor(0xFF888888.toInt())
                btnTraditional.setBackgroundColor(0x00000000)
            }
        }

        // 云/本地切换按钮
        if (::btnCloud.isInitialized) {
            val cloudActive = cloudMode == CloudMode.CLOUD || cloudMode == CloudMode.LOCAL_LOCKED
            // 云模式或本地锁定 → 高亮主题色；本地模式 → 灰色
            btnCloud.setTextColor(if (cloudActive) accent else 0xFF888888.toInt())
        }

        // 语音键底色
        micButton?.backgroundTintList = accentStateList
        btnMicAi?.backgroundTintList = accentStateList
        btnMicAi?.setTextColor(0xFFFFFFFF.toInt())
        btnMicNoAi?.setTextColor(accent)
        // 键盘副字符色（T9 数字等）
        if (::keyboardView.isInitialized) {
            // 副字符颜色跟随主题色
        }

        // ③ 按键灰度 —— 底栏按钮、键盘按键背景
        val keyBgRaw = if (isDarkTheme) 0x2A else themeKeyGrayBase
        val keyBg = colorGray(keyBgRaw)
        val keyBgList = android.content.res.ColorStateList.valueOf(keyBg)
        // 底栏按钮默认背景（仅非高亮状态才设按键灰度）
        if (!magicIsWaitingForVoice && !isRecording) {
            btnMagic.setBackgroundColor(keyBg)
        }
        if (!magicBookLongPressTriggered) {
            btnClipboard.setBackgroundColor(keyBg)
        }
        if (!sendKeyLongPressTriggered) {
            btnSend.setBackgroundColor(keyBg)
        }
        btnDelete.setBackgroundColor(keyBg)
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

        // 底栏按钮图标颜色（深色模式用主题色，避免过亮）
        val iconColor = if (isDarkTheme) themeAccent else textColor
        btnMagic.setColorFilter(if (!magicIsWaitingForVoice && !isRecording) themeAccent else iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
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
        val popup = PopupWindow(
            view,
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        themePopup = popup

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
        tvHue.setBackgroundColor(themeAccent)

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
                tvHue.setBackgroundColor(themeAccent)
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
            textGrayScale = 0.7f
            seekHue.progress = accentHue.toInt()
            seekGray.progress = themeBgGrayBase
            seekKey.progress = themeKeyGrayBase
            updateTextSizeButtons(btnTextSmall, btnTextMedium, btnTextLarge, btnTextXLarge, 0)
            seekTextGray.progress = 70
            textGrayScale = 0.7f
            tvTextGrayPreview.text = "0.7"
            // 重置明暗模式为明亮
            isDarkTheme = false
            getSharedPreferences("cesia_settings", MODE_PRIVATE).edit()
                .putInt(PREF_THEME_MODE, THEME_LIGHT).apply()
            updateThemeModeButtons(btnThemeLight, btnThemeDark, THEME_LIGHT)
            applyThemeColors()
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
            if (smartEditMode) {
                // 智能写作编辑模式：写入 buffer 而不是上屏
                smartEditBuffer.append(selected)
                rimeEngine.clear()
                updateSmartEditStatus()
            } else if (magicEditMode) {
                // 魔法编辑模式：写入 buffer 而不是上屏
                magicEditBuffer.append(selected)
                rimeEngine.clear()
                updateMagicEditStatus()
            } else if (clipboardAddMode) {
                // 剪贴板新增模式：写入 buffer 而不是上屏
                clipboardAddBuffer.append(selected)
                rimeEngine.clear()
                updateClipboardAddStatus()
            } else {
                // 上屏选中的词
                commitCandidateText(selected)
            }
            if (keyboardMode == KeyboardMode.NUMBER && t9InputBuffer.isNotEmpty()) {
                t9InputBuffer.clear()
                rimeEngine.clear()
                rimeEngine.createSession()
            }
            // 查询联想词（限制最高频的 20 个，防止过多导致闪退）
            val associations = rimeEngine.getAssociations(selected).take(20)
            if (associations.isNotEmpty()) {
                // 有联想词，进入联想模式
                isAssociationMode = true
                associationPrefix = selected
                associationCandidates = associations
                if (isPanelExpanded) collapseCandidatePanel()
                showAssociationCandidates()
            } else {
                // 没有联想词
                isAssociationMode = false
                associationPrefix = ""
                associationCandidates = emptyList()
                if (isPanelExpanded) collapseCandidatePanel()
                updateCandidateBar()
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
            candidateBar?.visibility = View.GONE
        }
    }

    private fun updateCandidateBar() {
        // 语音识别期间不更新候选栏（避免覆盖流式识别状态）
        if (isRecording) return
        
        val composing = rimeEngine.isComposing
        val pinyin = rimeEngine.composingText
        val allCands = rimeEngine.getAllCandidates()

        // 没有输入时退出联想模式并恢复初始状态
        // 但联想模式下有联想词时不退出（联想词已上屏，Rime composing 已结束）
        // 智能写作/魔法编辑模式下不恢复初始状态（避免"已就绪"覆盖编辑中的命令）
        if (!composing && pinyin.isEmpty() && !isAssociationMode && !smartEditMode && !magicEditMode) {
            if (isAssociationMode) {
                isAssociationMode = false
                associationPrefix = ""
                associationCandidates = emptyList()
            }
            candidateBar.visibility = View.GONE
            if (isPanelExpanded) collapseCandidatePanel()
            updateStatus(statusIdleText)
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

        // 更新候选词列表
        candidateAdapter?.updateData(displayCands)
        rvCandidates?.scrollToPosition(0)
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

// endregion 候选栏

// region 录音控制
    // ======================== 识别后端可用性检测 ========================

    /**
     * 检测单个后端的真实可用性
     * 返回 Triple(是否可用, 错误信息, 详细信息)
     */

    // ======================== 录音（根据当前模式） ========================

    private fun setupButtonListeners() {
        // 语音按钮：参考魔法书模式，OnTouchListener 统一处理单击和长按
        micButton.setOnClickListener { micOnClickListener() }
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
                        v.performClick()
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
                    btnDelete.setBackgroundColor(themeAccent)
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
                                } else {
                                    // 长按：删除光标后全部文字，循环删除避免字数限制
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

        btnClipboard.setOnClickListener { executeMagicOrAiReply() }
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
        btnMagic.setOnClickListener { toggleMagicMode() }
        btnMagic.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    magicBookLongPressTriggered = false
                    dismissAllPopups() // 长按互斥：关闭其他弹窗
                    // 开始发光（与魔法书按钮一致：青色背景+白色图标）
                    btnMagic.setBackgroundColor(themeAccent)
                    btnMagic.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_ATOP)
                    startMagicButtonGlow()
                    // 延迟触发长按弹窗
                    magicBookRunnable?.let { magicBookHandler.removeCallbacks(it) }
                    magicBookRunnable = Runnable {
                        magicBookLongPressTriggered = true
                        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
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
                        btnMagic.setBackgroundColor(colorGray(themeKeyGrayBase))
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
                    btnMagic.setBackgroundColor(colorGray(themeKeyGrayBase))
                    btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
                    true
                }
                else -> false
            }
        }

        // 发送按钮
        btnSend.setOnClickListener {
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
            updateStatus("🔓 已退出语音锁定模式")
            resetToIdle()
            return
        }
        if (!isRecording && !isWaitingForChoice) {
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

    private fun startMagicMode() {
        val ic = currentInputConnection ?: run {
            updateStatus("❌ 无输入框连接")
            return
        }
        val extracted = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
        val extractedAfter = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
        val fullText = extracted + extractedAfter

        // 空文本也允许启动——AI 可以生成新内容
        magicOriginalText = fullText
        magicMode = true
        typelessEngine?.magicMode = true

        // 高亮按钮表示正在录音 + 脉冲发光动画
        magicIsWaitingForVoice = true
        magicModeGlowing = true
        btnMagic.setBackgroundColor(themeAccent)
        btnMagic.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_ATOP)
        startMagicButtonGlow()

        updateStatus("🎤 请说出你的想法...（再次点击✨停止）")
        setStatusDot("recording")
        startVoiceWave()
        isRecording = true
        micButton.text = "🎤 说话"
        // 显示语音命令词提示
        showVoiceCommandHints()

        // 根据本地/云端模式选择语音识别后端
        when (cloudMode) {
            CloudMode.LOCAL, CloudMode.LOCAL_LOCKED -> {
                // 本地模式：使用 Sherpa 本地识别
                if (SherpaOnnxEngine.isLibraryLoaded() && voiceEngine.hasSherpaModel()) {
                    startMagicLocalRecording()
                } else {
                    updateStatus("⚠️ 本地语音不可用，回退到 Google...")
                    startMagicGoogleRecording()
                }
            }
            CloudMode.CLOUD -> {
                // 云端模式：使用 Google 语音识别
                startMagicGoogleRecording()
            }
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
            btnMagic.setBackgroundColor(colorGray(themeKeyGrayBase))
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
                            val ic2 = currentInputConnection
                            ic2?.performContextMenuAction(android.R.id.selectAll)
                            ic2?.commitText(result, 1)
                            resetToIdle()
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
                    val ic2 = currentInputConnection
                    ic2?.performContextMenuAction(android.R.id.selectAll)
                    ic2?.commitText(result, 1)
                    resetToIdle()
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
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
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
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.setFocusable(false)

            popup.setOnDismissListener {
                smartWritingPopup = null
                stopMagicButtonGlow()
                btnMagic.setBackgroundColor(colorGray(themeKeyGrayBase))
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
                        command.replace(Regex("(续写|扩写|改写|润色|翻译|写作|修改|帮我写|帮我改|帮我润色)"), "").trim()
                    }

                    val sdf = java.text.SimpleDateFormat("yyyy年MM月dd日", java.util.Locale.CHINA)
                    val today = sdf.format(java.util.Date())
                    val finalQuery = "$searchQuery $today"

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

    /** Tavily Search API（互联网搜索） */
    private fun performSearXNGSearch(query: String): String {
        Log.d("Cesia", "TavilySearch: start, query=$query")
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        val tavilyApiKey = prefs.getString("tavily_api_key", "") ?: ""
        if (tavilyApiKey.isEmpty()) {
            Log.w("Cesia", "TavilySearch: API key not configured")
            return ""
        }
        try {
            val url = "https://api.tavily.com/search"
            // 判断是否为新闻类查询，使用新闻搜索通道
            val newsKeywords = listOf("新闻", "最新", "今天", "日报", "时报", "早报", "晚报", "时事", "热点", "头条", "战况", "比分")
            val isNewsQuery = newsKeywords.any { query.contains(it) }
            val jsonBody = org.json.JSONObject().apply {
                put("query", query)
                put("max_results", 5)
                put("include_answer", true)
                put("search_depth", "basic")
                // 强制使用新闻通道 + 最近1天，确保时效性
                put("topic", "news")
                put("days", 1)
            }.toString()
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $tavilyApiKey")
                .post(body)
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            Log.d("Cesia", "TavilySearch: HTTP $code")
            if (code == 200) {
                val json = response.body?.string() ?: ""
                Log.d("Cesia", "TavilySearch: response length=${json.length}")
                val results = parseTavilyResults(json)
                Log.d("Cesia", "TavilySearch: results=${results.length} chars")
                if (results.isNotEmpty()) {
                    return results
                }
            } else {
                Log.w("Cesia", "TavilySearch error HTTP $code: ${response.body?.string()?.take(200)}")
            }
        } catch (e: Exception) {
            Log.w("Cesia", "TavilySearch failed: ${e.message}")
        }
        Log.d("Cesia", "TavilySearch: returning empty")
        return ""
    }

    /** 检查文本中是否包含今天日期 */
    private fun containsTodayDate(text: String): Boolean {
        val sdf1 = java.text.SimpleDateFormat("yyyy年MM月dd日", java.util.Locale.CHINA)
        val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        val sdf3 = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA)
        val today1 = sdf1.format(java.util.Date())
        val today2 = sdf2.format(java.util.Date())
        val today3 = sdf3.format(java.util.Date())
        return text.contains(today1) || text.contains(today2) || text.contains(today3)
    }

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

    /** 构建语法指南 */
    private fun buildGrammarGuide(): String {
        return try {
            val guideMgr = com.cesia.input.stats.GrammarGuideManager(this)
            val guideContent = guideMgr.content
            if (guideContent.isNotEmpty()) {
                guideContent
            } else {
                ""
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
        btnMagic.setBackgroundColor(colorGray(themeKeyGrayBase))
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
        candidateBar.visibility = if (rimeEngine.isComposing) View.VISIBLE else View.GONE
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
        candidateBar.visibility = if (rimeEngine.isComposing) View.VISIBLE else View.GONE
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
        candidateBar.visibility = if (rimeEngine.isComposing) View.VISIBLE else View.GONE
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
    private fun showImeDialog(dialog: androidx.appcompat.app.AlertDialog) {
        // 不设置 window type，让系统自动处理（IME 服务有权限创建 dialog）
        dialog.show()
    }

// endregion 魔法编辑

// region AI自动回复
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
            applyAccentToViewTree(dialogView, themeAccent)

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
                    btnMagic.setBackgroundColor(colorGray(themeKeyGrayBase))
                    btnMagic.setColorFilter(themeAccent, android.graphics.PorterDuff.Mode.SRC_ATOP)
                }
                item.setOnClickListener {
                    aiReplyStyle = name
                    getSharedPreferences("cesia_settings", MODE_PRIVATE)
                        .edit().putString(PREF_AI_STYLE, aiReplyStyle).apply()
                    updateStatus(" 已切换为「$aiReplyStyle」风格")
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
            updateStatus(" 已切换为「$aiReplyStyle」风格")
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
        updateStatus(if (isTraditional) " 已切换为繁体输出" else " 已切换为简体输出")
        updateTraditionalButton()
        // 切换后重新触发候选（Rime stub 不支持 setOption，用本地 OpenCC 转换）
        updateCandidateBar()
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

    private fun updateTraditionalButton() {
        // 更新按钮视觉状态
        if (::btnTraditional.isInitialized) {
            // 简体模式显示"简"字，正体模式显示"正"字
            btnTraditional.text = if (isTraditional) "正" else "简"
            btnTraditional.setTextColor(if (isTraditional) themeAccent else 0xFF888888.toInt())
            btnTraditional.setBackgroundColor(if (isTraditional) (themeAccent and 0x00FFFFFF) or 0x22000000 else 0x00000000)
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
                btnTraditional.backgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000.toInt())
                btnTraditional.elevation = 0f
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
    private fun startLocalRecording() {
        startRecordingWithChoice(VoiceChoice.LOCAL_SHERPA, PolishChoice.LOCAL_AI)
    }

    /** 云端模式录音 */
    private fun startCloudRecording() {
        startRecordingWithChoice(VoiceChoice.GOOGLE, PolishChoice.CLOUD_OPENROUTER)
    }

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
        candidateBar.visibility = View.GONE

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
    private fun onSimulTranslateButtonClick() {
        // 同声传译功能正在开发中，仅提示用户
        // 不初始化引擎，避免内存占用导致输入法卡死
        android.widget.Toast.makeText(
            this,
            "🎧 同声传译功能正在开发中，敬请期待",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

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
        pendingAiMode = null
        setStatusDot("recording")
        startVoiceWave()
        keyboardView.visibility = View.GONE
        candidateBar.visibility = View.GONE
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
        pendingAiMode = null
        setStatusDot("recording")
        // 锁定模式不显示绿色圆点，避免按钮偏移
        // startVoiceWave() 已禁用
        keyboardView.visibility = View.GONE
        candidateBar.visibility = View.GONE
        voiceStartTime = System.currentTimeMillis()
        updateStatus("🎤 正在收听 (锁定模式)...")
        startWhisperRecordingAsync()
        // 不调用 showAiChoiceButtons()，保持语音键不分列
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
                // 兜底预热（若 updateVoiceBackend 还没触发）
                voiceEngine.warmupRecognizer()
                voiceEngine.recordInSegments(
                    maxDurationMs = 300000,  // 5分钟，避免长语音被截断
                    segmentDurationMs = 3000,
                    onSegmentResult = { text, isFinal ->
                        segmentCount++
                        Log.i("Cesia", "onSegmentResult #$segmentCount: text='${text.take(50)}', isFinal=$isFinal")
                        if (text.isNotEmpty() && text != lastStreamingText) {
                            lastStreamingText = text
                            recognizedText = text
                            withContext(Dispatchers.Main) {
                                // 检测到识别文本，隐藏语音命令提示
                                if (statusLines.isNotEmpty() && statusLines.last().startsWith("💡")) {
                                    statusLines.removeAt(statusLines.size - 1)
                                }
                                // 流式显示：直接在光标位置显示识别文本（组合态）
                                val ic = currentInputConnection ?: return@withContext
                                ic.setComposingText(text, 1)
                                updateStatus("🎤 $text")
                            }
                        }
                        if (isFinal) {
                            withContext(Dispatchers.Main) {
                                Log.i("Cesia", "onSegmentResult: isFinal, text='${text.take(50)}', recognizedText='${recognizedText.take(50)}'")
                                if (text.isNotEmpty()) {
                                    // 最终结果：确认组合文本
                                    val ic = currentInputConnection ?: return@withContext
                                    ic.finishComposingText()
                                    handleCloudVoiceResult(text)
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

                            // 1. 先 finishComposingText 确认当前组合文本
                            ic.finishComposingText()

                            // 2. 删除末尾的命令词（长度 = 完整识别文本 - 去掉命令词后的文本）
                            val cmdWordLength = recognizedText.length - text.length
                            if (cmdWordLength > 0) {
                                ic.deleteSurroundingText(cmdWordLength, 0)
                            }

                            // 3. 更新 recognizedText（去掉命令词后的文本）
                            recognizedText = text

                            when (command) {
                                "exit" -> {
                                    // 退出：结束识别 + 退出语音输入状态
                                    isVoiceLocked = false
                                    updateMicButtonLockedState()
                                    updateStatus("🔓 已退出语音输入")
                                    resetToIdle()
                                }
                                "send" -> {
                                    // 发送：确认文本 + 发送
                                    updateStatus("📤 已发送")
                                    // 检查当前输入框是否支持发送动作
                                    val editorInfo = currentInputEditorInfo
                                    val canSend = editorInfo != null &&
                                        (editorInfo.imeOptions and EditorInfo.IME_ACTION_SEND) != 0
                                    if (canSend) {
                                        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
                                    } else {
                                        Log.w("Cesia", "当前输入框不支持 IME_ACTION_SEND，imeOptions=${editorInfo?.imeOptions}")
                                        updateStatus(" 已上屏（当前输入框不支持自动发送）")
                                    }
                                    // 锁定模式：发送后继续录音；非锁定模式：退出语音输入
                                    if (isVoiceLocked) {
                                        startRecordingLocked()
                                    } else {
                                        resetToIdle()
                                    }
                                }
                                "ai" -> {
                                    // 润色：对删除命令词后的文本润色
                                    if (text.isEmpty()) {
                                        updateStatus("⚠️ 没有需要润色的文字")
                                        startRecordingLocked()
                                    } else {
                                        updateStatus("✨ 语音润色中...")
                                        setStatusDot("processing")
                                        isProcessingResult = true
                                        isWaitingForChoice = false
                                        hideAiChoiceButtons()
                                        polishRecognizedText(text)
                                    }
                                }
                                "cmd" -> {
                                    // 命令模式：text 是指令内容（如"翻译成英文"）
                                    if (text.isEmpty()) {
                                        updateStatus("⚠️ 请输入指令")
                                        startRecordingLocked()
                                    } else {
                                        Log.i("Cesia", "命令模式: 指令='$text'")
                                        executeVoiceCommand(text)
                                    }
                                }
                                "finish" -> {
                                    // 结束：原文已上屏，直接结束识别
                                    updateStatus(" 已上屏")
                                    // 锁定模式下继续录音，非锁定模式退出
                                    if (isVoiceLocked) {
                                        startRecordingLocked()
                                    } else {
                                        resetToIdle()
                                    }
                                }
                                "writing" -> {
                                    // 写作：text 是写作指令（如"帮我写篇文章"）
                                    if (text.isEmpty()) {
                                        updateStatus("⚠️ 请输入写作内容")
                                        if (isVoiceLocked) startRecordingLocked() else resetToIdle()
                                    } else {
                                        Log.i("Cesia", "语音写作命令: '$text'")
                                        updateStatus("✨ 语音写作中...")
                                        // 延迟1秒执行，让用户看到状态提示
                                        CoroutineScope(Dispatchers.Main).launch {
                                            delay(1000)
                                            // 删除输入框中剩余的写作指令文字
                                            val ic2 = currentInputConnection
                                            if (ic2 != null && text.isNotEmpty()) {
                                                ic2.deleteSurroundingText(text.length, 0)
                                            }
                                            executeSmartCommand(text)
                                            // 锁定模式下继续录音，非锁定模式退出
                                            if (isVoiceLocked) startRecordingLocked() else resetToIdle()
                                        }
                                    }
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
    private fun handleCloudVoiceResult(text: String) {
        Log.i("Cesia", "handleCloudVoiceResult: text='${text.take(50)}', isRecording=$isRecording, recognizedText='${recognizedText.take(50)}', pendingAiMode=$pendingAiMode, isProcessingResult=$isProcessingResult, isVoiceLocked=$isVoiceLocked")
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

        // 每次录音前更新语音后端（模型状态可能已变化）
        updateVoiceBackend()

        when (voiceEngine.getBackend()) {
            VoiceEngine.Backend.LOCAL_SHERPA -> {
                if (modelManager.hasVoiceModel()) {
                    startWhisperRecordingAsync()
                } else {
                    // 模型被删除了，回退 Google
                    updateStatus("🎤 正在收听 (Google)...")
                    typelessEngine?.startListening(continuous = true)
                }
            }
            VoiceEngine.Backend.CLOUD_GROQ -> {
                // Groq 已移除，回退到 Google
                updateStatus("🎤 正在收听 (Google)...")
                typelessEngine?.startListening(continuous = true)
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
            replaceTextWithPolish(text, finalText)
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
    private fun replaceTextWithPolish(originalText: String, polishedText: String) {
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
        statsManager.addRecord(originalText, polishedText, duration)
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
                    replaceTextWithPolish(text, finalText)
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
                val hasLocalModel = modelManager.hasAiModel()
                val result = if (hasLocalModel) {
                    aiEngine.polishWithPrompt(prompt)
                } else {
                    // 无本地模型 → 尝试云端
                    // TODO: 云端润色
                    null
                }

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
                            val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
                            val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
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
                        val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
                        val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
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
            val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
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
            val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
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

// endregion 声波动画

// region 麦克风动画
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
        rimeEngine.clear()
        t9ShiftTemp = false
        // qwertyShiftLocked 不在此处清除，各键盘状态独立
        updateCandidateBar()
        updateStatus(statusIdleText)
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
                currentInputConnection?.commitText(symbol, 1)
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
        // 取消 -100 键长按
        symbolKeyLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        symbolKeyLongPressRunnable = null
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
        btnSend.setBackgroundColor(themeAccent)
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
        btnSend.setBackgroundColor(colorGray(themeKeyGrayBase))
    }

    private fun startMagicBookLongPress() {
        cancelMagicBookLongPress()
        // 立即高亮魔法书按钮
        btnClipboard.setBackgroundColor(themeAccent)
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
        btnClipboard.setBackgroundColor(colorGray(themeKeyGrayBase))
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
        btnDelete.setBackgroundColor(colorGray(themeKeyGrayBase))
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
            popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
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

    private fun updateClipboardSearchBtn(btnSearch: TextView) {
        if (clipboardSearchFilter.isNotEmpty()) {
            btnSearch.text = "🔍 $clipboardSearchFilter"
            btnSearch.setTextColor(themeAccent)
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
        val prefs = getSharedPreferences("cesia_clipboard", MODE_PRIVATE)
        // 持久化：收藏+锁定条目
        val favItems = clipboardHistory.filter { clipboardFavorites[it] == true }
        prefs.edit().putString("favorites", favItems.joinToString("\n")).apply()
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

        when (primaryCode) {

            // ======================== 字母键 a-z ========================
            in 97..122 -> {
                // 输入新拼音时退出联想模式
                exitAssociationMode()
                functionalLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                functionalLongPressRunnable = null
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
                            } else {
                                ic?.commitText(keyChar.toString(), 1)
                            }
                        }
                    } else {
                        // Rime 不接受，直接上屏
                        ic?.commitText(keyChar.toString(), 1)
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
                        }
                        updateCandidateBar()
                    } else {
                        // Rime 不接受该按键，直接上屏
                        ic?.commitText(primaryCode.toChar().toString(), 1)
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
                    // 如果当前 composing 是纯英文（如输入t后按9），直接上屏英文+数字
                    val composingText = rimeEngine.composingText
                    val isPureEnglish = composing && composingText.isNotEmpty() &&
                        composingText.all { it in 'a'..'z' }
                    if (isPureEnglish) {
                        // 英文输入中按数字：上屏英文原文 + 数字，无空格
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
                    } else if (t9InputBuffer.isNotEmpty()) {
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
                            commitCandidateText(selected)
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
                        // 标点上屏后清空候选栏和状态栏
                        rimeEngine.clear()
                        if (keyboardMode == KeyboardMode.NUMBER) t9InputBuffer.clear()
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

// endregion 键盘回调

// region 按键事件
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
                Handler(Looper.getMainLooper()).postDelayed(functionalLongPressRunnable!!, 800)
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
                Handler(Looper.getMainLooper()).postDelayed(it, 800)
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
                Handler(Looper.getMainLooper()).postDelayed(it, 600)
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
        symbolKeyLongPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        symbolKeyLongPressRunnable = null
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
            // 每次输入法激活时更新语音后端并预加载模型
            Log.d("Cesia", "onStartInputView: step1 updateVoiceBackend")
            modelManager.scanExistingModels()
            updateVoiceBackend()
            Log.d("Cesia", "onStartInputView: step2 preloadModels")
            preloadWhisperModel()
            preloadAiModel()

            // RSS 自动抓取：如果缓存不存在或过期（>1h），后台自动刷新
            Log.d("Cesia", "onStartInputView: step3 autoRefreshRssCache")
            autoRefreshRssCache()
            Log.d("Cesia", "onStartInputView: step4 done")

            // 同传按钮：AI 模型文件已下载时显示（TTS 使用系统自带）
            btnTheme?.visibility = if (modelManager.hasAiModel()) View.VISIBLE else View.GONE

            // 重置星星按钮状态（防止重启后残留高亮）
            btnMagic.setBackgroundColor(colorGray(themeKeyGrayBase))
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
    private fun onLocalModeChanged() {
        updateVoiceBackend()
        preloadWhisperModel()
        preloadAiModel()
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
     * 检查本地润色是否可用（MNN）
     */
    private fun isLocalPolishAvailable(): Boolean {
        return modelManager.hasAiModel()
    }

    /**
     * 检查云端润色是否可用（API Key）
     */
    private fun isCloudPolishAvailable(): Boolean {
        return !getOpenRouterApiKey().isNullOrEmpty()
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

        // 云按钮
        btnCloud?.let { btn ->
            when {
                !recognitionAvailable -> {
                    // 识别不可用 → 灰色
                    btn.isEnabled = false
                    btn.alpha = 0.4f
                    btn.text = "云"
                    btn.setTextColor(0xFF888888.toInt())
                }
                !polishAvailable -> {
                    // 润色不可用 → 灰色
                    btn.isEnabled = false
                    btn.alpha = 0.4f
                    btn.text = "云"
                    btn.setTextColor(0xFF888888.toInt())
                }
                usingGoogle -> {
                    // Google 识别 → 强制云端模式，云字高亮
                    cloudMode = CloudMode.CLOUD
                    btn.isEnabled = false
                    btn.alpha = 1.0f
                    btn.text = "云"
                    btn.setTextColor(themeAccent) // 青色高亮
                }
                !localPolish && cloudPolish -> {
                    // 只有云端润色 → 强制云端
                    cloudMode = CloudMode.CLOUD
                    btn.isEnabled = false
                    btn.alpha = 1.0f
                    btn.text = "云"
                    btn.setTextColor(themeAccent)
                }
                localPolish && !cloudPolish -> {
                    // 只有本地润色 → 强制本地
                    cloudMode = CloudMode.LOCAL
                    btn.isEnabled = false
                    btn.alpha = 1.0f
                    btn.text = "本"
                    btn.setTextColor(0xFF888888.toInt())
                }
                localPolish && cloudPolish -> {
                    // 都有 → 可切换，根据当前模式显示
                    btn.isEnabled = true
                    btn.alpha = 1.0f
                    when (cloudMode) {
                        CloudMode.LOCAL -> {
                            btn.text = "本"
                            btn.setTextColor(0xFF888888.toInt())
                        }
                        CloudMode.CLOUD -> {
                            btn.text = "云"
                            btn.setTextColor(themeAccent)
                        }
                        CloudMode.LOCAL_LOCKED -> {
                            btn.text = "本"
                            btn.setTextColor(themeAccent)
                        }
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

        when (cloudMode) {
            CloudMode.LOCAL -> {
                cloudMode = CloudMode.CLOUD
                updateStatus("☁️ 已切换到云端润色模式")
            }
            CloudMode.CLOUD -> {
                cloudMode = CloudMode.LOCAL
                updateStatus("🏠 已切换到本地润色模式")
            }
            CloudMode.LOCAL_LOCKED -> {
                // 锁定模式下点击解锁
                cloudMode = CloudMode.LOCAL
                updateStatus("🔓 已解锁本地模式")
            }
        }
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
                btn.setBackgroundColor(themeAccent) // 青色背景
                btn.setTextColor(0xFFFFFFFF.toInt()) // 白色文字
                btn.elevation = 6f
                startMicButtonGlow()
            } else {
                // 正常状态
                btn.setBackgroundColor(0x00000000.toInt()) // 透明背景
                btn.setTextColor(0xFF555555.toInt()) // 灰色文字
                btn.elevation = 0f
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
    private fun checkVoiceCommandWord(text: String): Pair<String, String>? {
        val trimmed = text.trimEnd()
        // 使用动态命令词（与 VoiceEngine 一致）
        return when {
            trimmed.endsWith(VoiceEngine.cmdExit) -> {
                Pair(trimmed.dropLast(VoiceEngine.cmdExit.length).trimEnd(), "exit")
            }
            trimmed.endsWith(VoiceEngine.cmdSend) -> {
                Pair(trimmed.dropLast(VoiceEngine.cmdSend.length).trimEnd(), "send")
            }
            trimmed.endsWith(VoiceEngine.cmdPolish) -> {
                Pair(trimmed.dropLast(VoiceEngine.cmdPolish.length).trimEnd(), "ai")
            }
            trimmed.endsWith(VoiceEngine.cmdFinish) -> {
                Pair(trimmed.dropLast(VoiceEngine.cmdFinish.length).trimEnd(), "finish")
            }
            trimmed.endsWith(VoiceEngine.cmdWriting) -> {
                val beforeWriting = trimmed.dropLast(VoiceEngine.cmdWriting.length).trimEnd()
                Pair(beforeWriting, "writing")
            }
            else -> null
        }
    }

// endregion 语音锁定

// region UI辅助
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
