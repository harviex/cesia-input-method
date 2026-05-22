package com.cesia.input

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.cesia.input.engine.PinyinEngine
import com.cesia.input.engine.TypelessEngine
import com.cesia.input.stats.PolishStatsManager
import com.google.android.material.button.MaterialButton
import android.widget.Toast

/**
 * Cesia 输入法 — 语音自动润色上屏 + 中文拼音输入
 *
 * 键盘布局：
 * - 第一行：数字 1-0
 * - 第二至四行：QWERTY 字母键，每个键有小符号标注
 * - 第五行：符号切换 + 中/英切换 + 标点 + 空格 + 回车 + 发送
 *
 * 功能：
 * - 点击符号键 → 切换到符号键盘
 * - 长按字母键 → Fn 效果，输出对应符号
 * - 退格键长按 → 快速连续删除
 * - 中/英切换 → 中文拼音输入模式
 * - 左下角魔法按钮 → 语音指令修改文字
 */
class CesiaInputMethod : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    // 视图
    private lateinit var keyboardView: com.cesia.input.ui.CustomKeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolKeyboard: Keyboard
    private var currentKeyboard: Keyboard? = null

    private lateinit var micButton: MaterialButton
    private lateinit var micButtonContainer: LinearLayout
    private lateinit var btnMicAi: MaterialButton
    private lateinit var btnMicNoAi: MaterialButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnClipboard: ImageButton
    private lateinit var btnMagic: com.google.android.material.button.MaterialButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    // 候选词栏
    private lateinit var candidateBar: LinearLayout
    private lateinit var tvComposing: TextView
    private lateinit var tvCandidates: Array<TextView>
    private lateinit var btnCandidatePrev: ImageButton
    private lateinit var btnCandidateNext: ImageButton

    // 核心组件
    private var typelessEngine: TypelessEngine? = null
    private lateinit var statsManager: PolishStatsManager
    private lateinit var pinyinEngine: PinyinEngine

    // 状态
    private var isRecording = false
    private var isSymbolMode = false
    private var isCapsLock = false
    private var isChineseMode = false
    private var isProcessingResult = false  // 正在处理识别结果（润色中）
    private var isWaitingForChoice = false  // 识别完成，等待用户选择 AI+/AI×
    private var lastMicClickTime = 0L  // 防抖
    private var voiceStartTime = 0L  // 语音开始时间
    private var pendingAiMode: Boolean? = null  // null=未选择, true=使用AI, false=不使用AI
    private var recognizedText: String = ""  // 识别完成的文字（等待用户选择）

    // 长按检测
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var currentLongPressKey: Keyboard.Key? = null
    private var longPressTriggered = false
    private var longPressConsumed = false  // 长按是否已被消费（防止 onKey 重复处理）

    // 退格键长按连续删除
    private var backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null

    // 魔法模式
    private var magicMode = false
    private var magicOriginalText = ""

    // AI自动回复
    private var aiReplyStyle = "自然" // 自然, 幽默, 圆滑, 官方, 简洁
    private var isAiProcessing = false

    // 主题
    private var isDarkTheme = false

    // 设置
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
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
    }

    override fun onCreate() {
        // 读取主题设置
        val themeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt(PREF_THEME_MODE, THEME_LIGHT)
        isDarkTheme = themeMode == THEME_DARK
        setTheme(if (isDarkTheme) R.style.Theme_Cesia_Dark else R.style.Theme_Cesia)
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)

        keyboardView = view.findViewById(R.id.keyboard_view)
        micButton = view.findViewById(R.id.btn_mic)
        micButtonContainer = view.findViewById(R.id.mic_button_container)
        btnMicAi = view.findViewById(R.id.btn_mic_ai)
        btnMicNoAi = view.findViewById(R.id.btn_mic_noai)
        btnSettings = view.findViewById(R.id.btn_settings)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnClipboard = view.findViewById(R.id.btn_clipboard)
        btnMagic = view.findViewById(R.id.btn_magic)
        statusDot = view.findViewById(R.id.v_status_dot)
        statusText = view.findViewById(R.id.tv_status)

        // 候选词栏
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
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolKeyboard = Keyboard(this, R.xml.symbols)
        currentKeyboard = qwertyKeyboard

        keyboardView.keyboard = currentKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.isPreviewEnabled = true

        // 初始化引擎
        statsManager = PolishStatsManager(this)
        pinyinEngine = PinyinEngine(this)
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
            // 识别结果返回时标记为处理中
            engine.onResultProcessing = {
                Handler(Looper.getMainLooper()).post {
                    isProcessingResult = true
                    setStatusDot("processing")
                }
            }
            // 润色完成时取消处理中标记
            engine.onResultCommitted = {
                Handler(Looper.getMainLooper()).post {
                    isProcessingResult = false
                    isRecording = false
                    micButton.isActivated = false
                    micButton.text = "🎙️ 点击开始说话"
                    // 恢复按钮状态（隐藏AI/非AI按钮）
                    micButton.visibility = View.VISIBLE
                    btnMicAi.visibility = View.GONE
                    btnMicNoAi.visibility = View.GONE
                    // 恢复键盘区域
                    keyboardView.visibility = View.VISIBLE
                    setStatusDot("idle")
                    updateStatus("✅ 已完成")
                }
            }
            // 识别完成回调（新流程：等待用户选择 AI+/AI×）
            engine.onRecognitionComplete = { text ->
                Handler(Looper.getMainLooper()).post {
                    recognizedText = text
                    isRecording = false
                    setStatusDot("idle")

                    // 如果用户已经选择了模式（录音中按了 AI+ 或 AI×），直接执行
                    if (pendingAiMode == true) {
                        // AI+ 模式：直接润色
                        isWaitingForChoice = false
                        hideAiChoiceButtons()
                        if (text.isEmpty()) {
                            // 空文本无法润色，提示并重置
                            updateStatus("⚠️ 未识别到文字")
                            resetToIdle()
                        } else {
                            updateStatus("🔄 正在润色...")
                            setStatusDot("processing")
                            isProcessingResult = true
                            polishRecognizedText(text)
                        }
                    } else if (pendingAiMode == false) {
                        // AI× 模式：直接上屏
                        isWaitingForChoice = false
                        hideAiChoiceButtons()
                        if (text.isNotEmpty()) {
                            currentInputConnection?.commitText(text, 1)
                            updateStatus("✅ 已上屏（未润色）")
                        } else {
                            updateStatus("⚠️ 未识别到文字")
                        }
                        resetToIdle()
                    } else {
                        // 未选择模式：等待用户选择
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
        // 把用户配置的模型ID传给引擎
        val prefs = getSharedPreferences("cesia_settings", MODE_PRIVATE)
        typelessEngine?.updateModelId(prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID)
        // 加载AI回复风格
        aiReplyStyle = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getString(PREF_AI_STYLE, "自然") ?: "自然"
        setupButtonListeners()
        setupCandidateBar()

        // 应用主题到键盘视图
        applyKeyboardTheme()

        updateStatus("Cesia 已就绪")
        setStatusDot("idle")

        return view
    }

    private fun applyKeyboardTheme() {
        if (isDarkTheme) {
            keyboardView.setBackgroundColor(0xFF0F0F23.toInt())
            // 状态栏
            (statusText.parent as? View)?.setBackgroundColor(0xFF1A1A2E.toInt())
            statusText.setTextColor(0xFFE0E0E0.toInt())
            // 候选词栏
            candidateBar.setBackgroundColor(0xFF16213E.toInt())
            tvComposing.setTextColor(0xFF4488FF.toInt())
            for (tv in tvCandidates) {
                tv.setTextColor(0xFFE0E0E0.toInt())
            }
            // 底部按钮栏
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

    private fun setupCandidateBar() {
        // 候选词点击
        for (i in tvCandidates.indices) {
            val index = i
            tvCandidates[i].setOnClickListener {
                if (isChineseMode && pinyinEngine.hasCandidates()) {
                    val selected = pinyinEngine.selectCandidate(
                        pinyinEngine.getCurrentPage() * 5 + index
                    )
                    if (selected.isNotEmpty()) {
                        currentInputConnection?.commitText(selected, 1)
                        updateCandidateBar()
                    }
                }
            }
        }

        // 翻页按钮
        btnCandidatePrev.setOnClickListener {
            if (isChineseMode && pinyinEngine.hasCandidates()) {
                pinyinEngine.prevPage()
                updateCandidateBar()
            }
        }

        btnCandidateNext.setOnClickListener {
            if (isChineseMode && pinyinEngine.hasCandidates()) {
                pinyinEngine.nextPage()
                updateCandidateBar()
            }
        }
    }

    private fun updateCandidateBar() {
        if (!isChineseMode || !pinyinEngine.isComposing()) {
            candidateBar.visibility = View.GONE
            return
        }

        val candidates = pinyinEngine.getCandidates()
        if (candidates.isEmpty()) {
            candidateBar.visibility = View.GONE
            return
        }

        candidateBar.visibility = View.VISIBLE
        tvComposing.text = pinyinEngine.getCurrentPinyin()

        for (i in tvCandidates.indices) {
            if (i < candidates.size) {
                tvCandidates[i].text = candidates[i]
                tvCandidates[i].visibility = View.VISIBLE
            } else {
                tvCandidates[i].visibility = View.INVISIBLE
            }
        }

        // 更新翻页按钮状态
        btnCandidatePrev.isEnabled = pinyinEngine.getCurrentPage() > 0
        btnCandidateNext.isEnabled = pinyinEngine.getCurrentPage() < pinyinEngine.getPageCount() - 1
    }

    private fun setupButtonListeners() {
        // 主麦克风按钮：点击立即开始录音
        micButton.setOnClickListener {
            if (!isRecording && !isWaitingForChoice) {
                startRecordingImmediately()
            } else if (isWaitingForChoice) {
                updateStatus("请点击 AI+ 或 AI× 选择处理方式")
            } else if (isRecording) {
                if (magicMode) {
                    // 魔法模式：停止监听，保持 isRecording 等 handleMagicResult 回调重置
                    typelessEngine?.stopListening()
                    setStatusDot("processing")
                    updateStatus("⏳ 正在识别指令...")
                } else {
                    // 普通模式：停止录音
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

        // AI+ 按钮：使用 AI 润色
        btnMicAi.setOnClickListener { onAiPlusSelected() }

        // AI× 按钮：不使用 AI，直接上屏
        btnMicNoAi.setOnClickListener { onAiCrossSelected() }

        btnSettings.setOnClickListener { showSettings() }

        btnDelete.setOnClickListener {
            if (isChineseMode && pinyinEngine.isComposing()) {
                handleChineseBackspace()
            } else {
                currentInputConnection?.deleteSurroundingText(Integer.MAX_VALUE, 0)
            }
        }

        // AI自动回复按钮：短按=读取上下文并生成回复，长按=切换语言风格
        btnClipboard.setOnClickListener { triggerAiReply() }
        btnClipboard.setOnLongClickListener { showAiStylePicker(); true }

        btnMagic.setOnClickListener { startMagicMode() }
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
            // 创建自定义弹窗布局
            val inflater = android.view.LayoutInflater.from(this)
            val dialogView = inflater.inflate(R.layout.dialog_ai_style_picker, null)

            val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setView(dialogView)
                .setTitle("🎭 选择写作风格")
                .setNegativeButton("取消", null)
                .create()

            // 动态生成风格选项
            val container = dialogView.findViewById<LinearLayout>(R.id.style_container)
            for ((name, icon, desc) in styles) {
                val item = inflater.inflate(R.layout.item_ai_style, container, false)
                item.findViewById<TextView>(R.id.tv_style_icon).text = icon
                item.findViewById<TextView>(R.id.tv_style_name).text = name
                item.findViewById<TextView>(R.id.tv_style_desc).text = desc

                // 高亮当前选中的风格
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
            // 降级：如果弹窗失败，直接切换到下一个风格
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
            updateStatus("⏳ AI正在处理中，请稍候...")
            return
        }

        val ic = currentInputConnection ?: run {
            updateStatus("❌ 无输入框连接")
            return
        }

        // 第一步：读取输入框中的文字作为上下文
        val textBefore = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
        val inputText = textBefore + textAfter

        // 第二步：清空输入框（安全方式，避免performContextMenuAction崩溃）
        if (inputText.isNotEmpty()) {
            try {
                // 先尝试全选
                ic.performContextMenuAction(android.R.id.selectAll)
                // 再删除选中的内容
                ic.deleteSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE)
            } catch (e: Exception) {
                // 降级方案：直接删除前后文字
                try {
                    ic.deleteSurroundingText(textBefore.length, textAfter.length)
                } catch (e2: Exception) {
                    // 最后手段：直接提交空文本替换
                    ic.commitText("", 1)
                }
            }
        }

        // 第三步：根据上下文生成AI回复
        if (inputText.isEmpty()) {
            // 输入框为空，尝试从EditorInfo获取应用信息
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
            // 用输入框原文作为上下文，生成回复
            val context = "【原文】\n$inputText\n\n请根据以上内容的语气和主题，生成一条合适的回复。"
            generateAiReply(context, ic)
        }
    }

    private fun generateAiReply(context: String, ic: android.view.inputmethod.InputConnection) {
        isAiProcessing = true
        updateStatus("🤖 AI正在生成回复（$aiReplyStyle 风格）...")
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

    private fun buildContext(before: String, after: String): String {
        val sb = StringBuilder()
        if (before.isNotEmpty()) {
            sb.append("【前面已有的内容】\n$before\n\n")
        }
        if (after.isNotEmpty()) {
            sb.append("【后面已有的内容】\n$after\n\n")
        }
        return sb.toString()
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

    // ======================== 中/英切换 ========================

    private fun toggleChineseMode() {
        isChineseMode = !isChineseMode
        if (isChineseMode) {
            // 切换到中文模式
            isSymbolMode = false
            currentKeyboard = qwertyKeyboard
            keyboardView.keyboard = qwertyKeyboard
            keyboardView.invalidateAllKeys()
            pinyinEngine.clear()
            candidateBar.visibility = View.GONE
            updateStatus("中文拼音模式")
            // 更新中/英切换按钮标签：中文模式下显示"英"（点击切英文）
            updateLangSwitchKeyLabel("英")
        } else {
            // 切换到英文模式
            pinyinEngine.clear()
            candidateBar.visibility = View.GONE
            updateStatus("英文模式")
            // 更新中/英切换按钮标签：英文模式下显示"中"（点击切中文）
            updateLangSwitchKeyLabel("中")
        }
    }

    /**
     * 更新中/英切换键的显示文字
     */
    private fun updateLangSwitchKeyLabel(label: String) {
        val keyboard = currentKeyboard ?: return
        for (key in keyboard.keys) {
            if (key.codes.isNotEmpty() && key.codes[0] == KEYCODE_SWITCH_LANG) {
                key.label = label
                break
            }
        }
        keyboardView.invalidateAllKeys()
    }

    // ======================== 中文拼音输入处理 ========================

    private fun handleChineseInput(primaryCode: Int) {
        val c = primaryCode.toChar()

        if (c in 'a'..'z') {
            // 输入拼音字母
            val pinyin = pinyinEngine.inputLetter(c)
            updateCandidateBar()
            // 显示拼音串在状态栏
            updateStatus("拼音: $pinyin")
        } else if (c == ' ') {
            // 空格：选择第一个候选词上屏
            if (pinyinEngine.isComposing()) {
                if (pinyinEngine.hasCandidates()) {
                    // 有候选词：选择第一个候选词上屏
                    val selected = pinyinEngine.selectCandidate(0)
                    currentInputConnection?.commitText(selected, 1)
                } else {
                    // 没有候选词：将当前拼音串作为普通文字上屏
                    val pinyin = pinyinEngine.getCurrentPinyin()
                    currentInputConnection?.commitText(pinyin, 1)
                }
                pinyinEngine.clear()
                updateCandidateBar()
            } else {
                // 没有输入拼音，直接输入空格
                currentInputConnection?.commitText(" ", 1)
            }
        } else {
            // 其他字符（数字、标点等）：如果有候选词先上屏，再输出字符
            if (pinyinEngine.isComposing()) {
                val selected = if (pinyinEngine.hasCandidates()) {
                    pinyinEngine.selectCandidate(0)
                } else {
                    pinyinEngine.getCurrentPinyin()
                }
                currentInputConnection?.commitText(selected, 1)
                pinyinEngine.clear()
                updateCandidateBar()
            }
            // 输出字符（中文模式下自动转换标点）
            val charStr: String = if (isChineseMode) {
                when (c) {
                    ',' -> "\uFF0C"
                    '.' -> "\u3002"
                    '!' -> "\uFF01"
                    '?' -> "\uFF1F"
                    ';' -> "\uFF1B"
                    ':' -> "\uFF1A"
                    '(' -> "\uFF08"
                    ')' -> "\uFF09"
                    '[' -> "\u3010"
                    ']' -> "\u3011"
                    '{' -> "\uFF5B"
                    '}' -> "\uFF5D"
                    '<' -> "\u300A"
                    '>' -> "\u300B"
                    '"' -> "\u300C"
                    '\u0027' -> "\u300E"
                    '\\' -> "\u3001"
                    '|' -> "\uFF5C"
                    '~' -> "\uFF5E"
                    '`' -> "\u00B7"
                    else -> c.toString()
                }
            } else {
                c.toString()
            }
            currentInputConnection?.commitText(charStr, 1)
        }
    }

    private fun handleChineseBackspace() {
        if (pinyinEngine.isComposing()) {
            val pinyin = pinyinEngine.backspace()
            if (pinyin.isEmpty()) {
                pinyinEngine.clear()
                candidateBar.visibility = View.GONE
                updateStatus("中文拼音模式")
            } else {
                updateCandidateBar()
                updateStatus("拼音: $pinyin")
            }
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    // ======================== 录音 ========================

    /**
     * 点击麦克风按钮：立即开始录音，同时按钮变成 AI+/AI× 选择
     */
    private fun startRecordingImmediately() {
        isRecording = true
        isWaitingForChoice = false
        recognizedText = ""
        pendingAiMode = null
        setStatusDot("recording")
        // 录音时隐藏键盘区域
        keyboardView.visibility = View.GONE
        candidateBar.visibility = View.GONE
        voiceStartTime = System.currentTimeMillis()
        updateStatus("🎤 正在收听，请说话...")
        typelessEngine?.startListening(continuous = true)
        // 录音开始后，按钮变成 AI+/AI× 选择
        showAiChoiceButtons()
    }

    /**
     * 显示 AI+/AI× 选择按钮（隐藏主麦克风按钮）
     */
    private fun showAiChoiceButtons() {
        micButton.visibility = View.GONE
        btnMicAi.visibility = View.VISIBLE
        btnMicNoAi.visibility = View.VISIBLE
    }

    /**
     * 恢复主麦克风按钮（隐藏 AI+/AI× 选择按钮）
     */
    private fun hideAiChoiceButtons() {
        micButton.visibility = View.VISIBLE
        btnMicAi.visibility = View.GONE
        btnMicNoAi.visibility = View.GONE
    }

    /**
     * AI+ 按钮点击：使用 AI 润色
     */
    private fun onAiPlusSelected() {
        if (isWaitingForChoice && recognizedText.isNotEmpty()) {
            // 识别完成，用户选择 AI+ → 润色
            isWaitingForChoice = false
            pendingAiMode = true
            hideAiChoiceButtons()
            updateStatus("🔄 正在润色...")
            setStatusDot("processing")
            isProcessingResult = true
            // 调用润色
            polishRecognizedText(recognizedText)
        } else if (isRecording) {
            // 录音中点击 AI+ → 停止录音，等待识别结果
            stopRecordingAndWait()
            pendingAiMode = true
            updateStatus("⏳ 识别完成，正在润色...")
        }
    }

    /**
     * AI× 按钮点击：不使用 AI，直接上屏
     */
    private fun onAiCrossSelected() {
        if (isWaitingForChoice && recognizedText.isNotEmpty()) {
            // 识别完成，用户选择 AI× → 直接上屏
            isWaitingForChoice = false
            pendingAiMode = false
            hideAiChoiceButtons()
            // 直接上屏
            currentInputConnection?.commitText(recognizedText, 1)
            updateStatus("✅ 已上屏（未润色）")
            resetToIdle()
        } else if (isRecording) {
            // 录音中点击 AI× → 停止录音，等待识别结果后直接上屏
            stopRecordingAndWait()
            pendingAiMode = false
            updateStatus("⏳ 识别完成，直接上屏...")
        }
    }

    /**
     * 停止录音，等待识别结果
     */
    private fun stopRecordingAndWait() {
        isRecording = false
        typelessEngine?.stopListening()
        setStatusDot("processing")
        updateStatus("⏳ 正在识别...")
    }

    /**
     * 润色识别完成的文字
     */
    private fun polishRecognizedText(text: String) {
        isProcessingResult = true
        typelessEngine?.polishTextAsync(text) { finalText ->
            isProcessingResult = false
            // 上屏
            currentInputConnection?.commitText(finalText, 1)
            // 统计
            val duration = if (voiceStartTime > 0) System.currentTimeMillis() - voiceStartTime else 0
            statsManager.addRecord(text, finalText, duration)
            updateStatus("✅ 润色完成")
            resetToIdle()
        }
    }

    /**
     * 重置到空闲状态
     */
    private fun resetToIdle() {
        isRecording = false
        isWaitingForChoice = false
        isProcessingResult = false
        recognizedText = ""
        pendingAiMode = null
        setStatusDot("idle")
        hideAiChoiceButtons()
        keyboardView.visibility = View.VISIBLE
        updateStatus("Cesia 已就绪")
    }

    // 保留旧函数以兼容魔法模式
    private fun toggleRecording() {
        val now = System.currentTimeMillis()
        if (now - lastMicClickTime < 300) return
        lastMicClickTime = now
        if (isRecording || isWaitingForChoice) {
            if (isProcessingResult) {
                updateStatus("⏳ 正在处理中，请稍候...")
                return
            }
            // 录音中或等待选择时，点击主按钮无作用（应该点 AI+/AI×）
            if (isWaitingForChoice) {
                updateStatus("请点击 AI+ 或 AI× 选择处理方式")
            }
        } else {
            startRecordingImmediately()
        }
    }

    private fun startRecording() {
        // 兼容魔法模式
        startRecordingImmediately()
    }

    private fun stopRecording() {
        stopRecordingAndWait()
    }

    // ======================== 魔法模式 ========================

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
        isRecording = true
        micButton.isActivated = true
        micButton.text = "⏹️ 再次点击完成"

        typelessEngine?.startListening(continuous = true)
    }

    private fun handleMagicResult(recognizedText: String) {
        magicMode = false
        typelessEngine?.magicMode = false
        isRecording = false
        micButton.isActivated = false
        micButton.text = "🎤 点击开始说话"
        setStatusDot("idle")

        val instruction = recognizedText.trim()
        if (instruction.isEmpty()) {
            updateStatus("⚠️ 未识别到指令")
            return
        }

        Log.d("CesiaMagic", "原文: $magicOriginalText")
        Log.d("CesiaMagic", "指令: $instruction")
        updateStatus("🔄 正在处理修改指令...")

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
                            replaceInputText(result)
                            updateStatus("✅ 修改完成")
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

    private fun replaceInputText(newText: String) {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(newText, 1)
    }

    // ======================== 键盘切换 ========================

    private fun switchToSymbolKeyboard() {
        if (!isSymbolMode) {
            isSymbolMode = true
            isChineseMode = false
            pinyinEngine.clear()
            candidateBar.visibility = View.GONE
            currentKeyboard = symbolKeyboard
            keyboardView.keyboard = symbolKeyboard
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

    // ======================== 长按 Fn 效果 ========================

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
                longPressConsumed = false  // 重置消费标志
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
        // 注意：不清除 longPressTriggered，让 onKey 有机会检查
    }

    // ======================== KeyboardView 回调 ========================

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        // 先检查是否长按触发过
        val wasLongPressed = longPressTriggered && !longPressConsumed
        if (wasLongPressed) {
            // 长按已触发，标记为已消费，跳过此次按键
            longPressConsumed = true
            cancelLongPress()
            return
        }
        cancelLongPress()

        when (primaryCode) {
            KEYCODE_SWITCH_SYMBOL -> {
                toggleKeyboard()
            }
            KEYCODE_SWITCH_LANG -> {
                toggleChineseMode()
            }
            -1 -> { // Shift
                isCapsLock = !isCapsLock
                qwertyKeyboard.isShifted = isCapsLock
                keyboardView.invalidateAllKeys()
            }
            -5 -> { // 删除
                if (isChineseMode) {
                    handleChineseBackspace()
                } else {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
            }
            -200 -> { // 发送（纸飞机）
                // 如果有未上屏的拼音，先上屏
                if (isChineseMode && pinyinEngine.isComposing()) {
                    val text = if (pinyinEngine.hasCandidates()) {
                        pinyinEngine.selectCandidate(0)
                    } else {
                        pinyinEngine.getCurrentPinyin()
                    }
                    currentInputConnection?.commitText(text, 1)
                    pinyinEngine.clear()
                    updateCandidateBar()
                }
                // 发送：先尝试IME_ACTION_SEND，如果当前App不支持则发送Enter键
                val ic = currentInputConnection ?: return@onKey
                val editorInfo = currentInputEditorInfo
                val imeOptions = editorInfo?.imeOptions ?: 0
                val action = imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                val hasSendAction = action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                        || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                if (hasSendAction) {
                    ic.performEditorAction(action)
                } else {
                    // 回退：发送Enter键事件（换行）
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            Keyboard.KEYCODE_DELETE -> {
                if (isChineseMode) {
                    handleChineseBackspace()
                } else {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
            }
            Keyboard.KEYCODE_SHIFT -> {
                isCapsLock = !isCapsLock
                qwertyKeyboard.isShifted = isCapsLock
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_DONE -> {
                currentInputConnection?.apply {
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            Keyboard.KEYCODE_MODE_CHANGE -> {
                toggleKeyboard()
            }
            else -> {
                if (isChineseMode) {
                    // 中文模式：所有字符都走拼音输入处理
                    handleChineseInput(primaryCode)
                } else {
                    // 普通字符键
                    var char = primaryCode.toChar()
                    if (isCapsLock && char.isLowerCase()) {
                        char = char.uppercaseChar()
                    }
                    currentInputConnection?.commitText(char.toString(), 1)
                }
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        // 检测长按 Fn 效果（英文模式和中文模式都支持）
        if (primaryCode > 0 && !isSymbolMode) {
            val key = currentKeyboard?.keys?.find { it.codes?.contains(primaryCode) == true }
            if (key != null && !key.popupCharacters.isNullOrEmpty()) {
                startLongPressDetection(key)
            }
        }
        // 退格键长按连续删除
        if (primaryCode == -5 || primaryCode == Keyboard.KEYCODE_DELETE) {
            backspaceRunnable = object : Runnable {
                override fun run() {
                    if (isChineseMode) {
                        handleChineseBackspace()
                    } else {
                        currentInputConnection?.deleteSurroundingText(1, 0)
                    }
                    backspaceHandler.postDelayed(this, 80)
                }
            }
            backspaceHandler.postDelayed(backspaceRunnable!!, 400)
        }
    }

    override fun onRelease(primaryCode: Int) {
        cancelLongPress()
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
        loadSettings()
        // 重新加载主题（可能在设置中切换了）
        val themeMode = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt(PREF_THEME_MODE, THEME_LIGHT)
        isDarkTheme = themeMode == THEME_DARK
        applyKeyboardTheme()
        // 重新加载AI风格
        aiReplyStyle = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getString(PREF_AI_STYLE, "自然") ?: "自然"
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput && isRecording) stopRecording()
    }

    override fun onDestroy() {
        cancelLongPress()
        typelessEngine?.destroy()
        typelessEngine = null
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
                // 录音时追加文字
                if (msg.startsWith("🎤") || msg.startsWith("⏳") || msg.startsWith("🔄") || msg.startsWith("✅")) {
                    // 状态消息，替换最后一行或添加
                    if (statusLines.isNotEmpty() && !statusLines.last().startsWith("📝")) {
                        statusLines[statusLines.size - 1] = msg
                    } else {
                        statusLines.add(msg)
                    }
                } else if (msg.startsWith("📝") || msg.startsWith("🎤")) {
                    // 识别结果，追加
                    statusLines.add(msg)
                } else {
                    // 其他消息，替换最后一行
                    if (statusLines.isNotEmpty()) {
                        statusLines[statusLines.size - 1] = msg
                    } else {
                        statusLines.add(msg)
                    }
                }
                // 限制行数
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
