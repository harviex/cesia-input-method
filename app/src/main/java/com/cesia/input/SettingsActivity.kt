package com.cesia.input

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.cesia.input.stats.PolishStatsManager
import com.cesia.input.polish.PolishService
import com.cesia.input.engine.PinyinDictManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.lifecycleScope
import com.cesia.input.ai.AIEngine
import com.cesia.input.ai.LocalModeManager
import com.cesia.input.ai.LocalModeToggleHelper
import com.cesia.input.ai.VoiceAISettingsHelper
import com.cesia.input.model.ModelDownloadManager
import com.cesia.input.model.ModelInfo
import com.cesia.input.model.ModelManager
import com.cesia.input.model.ModelRegistry
import com.cesia.input.voice.VoiceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Cesia 输入法设置页面
 * 配置云端功能、测试连接、OTA 更新、词库管理、语音命令词、个性化设置
 */
class SettingsActivity : AppCompatActivity() {

    // === 云端功能设置 ===
    private lateinit var etApiUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etTavilyKey: TextInputEditText
    private var tvCloudModel: TextView? = null
    private lateinit var etPolishPrompt: TextInputEditText
    private lateinit var etTestText: TextInputEditText
    private lateinit var btnTestApi: MaterialButton
    private var btnTestLocalAi: MaterialButton? = null
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private var isLogExpanded = true
    private lateinit var tvVersion: TextView
    private lateinit var statsManager: PolishStatsManager
    private lateinit var dictManager: PinyinDictManager

    // === 语音与 AI 本地化 ===
    private lateinit var localModeManager: LocalModeManager
    private lateinit var modelManager: ModelManager
    private lateinit var downloadManager: ModelDownloadManager
    private var btnDownloadVoice: Button? = null
    private var btnDownloadAi: Button? = null
    private var isDownloading = false

    // === 语音命令词 (新顺序：智能写作、智能修改、智能润色、结束语音识别、立即发送、退出语音模式) ===
    private var etCmdWriting: TextInputEditText? = null    // 智能写作
    private var etCmdCommand: TextInputEditText? = null   // 智能修改
    private var etCmdPolish: TextInputEditText? = null    // 智能润色
    private var etCmdFinish: TextInputEditText? = null    // 结束语音识别
    private var etCmdSend: TextInputEditText? = null      // 立即发送
    private var etCmdExit: TextInputEditText? = null      // 退出语音模式
    private var tvCommandStatus: TextView? = null

    // === 个性化设置 ===
    private var etStatusIdle: TextInputEditText? = null
    private var etSmartWritingLabel: TextInputEditText? = null
    private var etMagicBookTitle: TextInputEditText? = null

    // === 统计卡片 ===
    private lateinit var tvStatInputChars: TextView
    private lateinit var tvStatOutputChars: TextView
    private lateinit var tvStatCount: TextView
    private var tvStatVoiceTime: TextView? = null
    private var tvStatSavedTime: TextView? = null
    private var tvStatVoiceSpeed: TextView? = null

    // === 历史记录 + 新闻源管理 ===
    private lateinit var btnHistory: Button
    private var btnNewsSources: Button? = null

    // === 标题可编辑 ===
    private var etSettingsTitle: TextInputEditText? = null
    private var tilSettingsTitle: TextInputLayout? = null

    // === 测试区重置按钮 ===
    private var btnResetPrompt: MaterialButton? = null

    // 语音与 AI 本地化设置 helper
    private lateinit var aiSettingsHelper: VoiceAISettingsHelper

    // 词库管理
    private lateinit var btnDownloadDict: Button
    private lateinit var tvDictInfo: TextView

    private val prefs by lazy { getSharedPreferences("cesia_settings", MODE_PRIVATE) }
    private var accentColor: Int = 0xFF81D8D0.toInt()
    private var themeMode: Int = THEME_LIGHT

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val PREF_MODEL_ID = "model_id"
        const val DEFAULT_MODEL_ID = "minimax/minimax-m2.5:free"
        const val PERMISSION_REQUEST_CODE = 1001
        const val TYPE_HEADER = 0
        const val TYPE_SOURCE = 1
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val PREF_GROQ_KEY = "groq_api_key"
        const val PREF_OPENROUTER_KEY = "openrouter_api_key"
        const val PREF_BRAVE_KEY = "tavily_api_key"
        const val PREF_POLISH_PROMPT = "polish_prompt"
        const val PREF_MODE = "run_mode"
        const val PREF_THEME_MODE = "theme_mode"
        const val PREF_SETTINGS_TITLE = "settings_title"
        const val DEFAULT_POLISH_PROMPT = "你是专业的中文润色助手。请润色用户输入的文本，使其更加流畅、自然、符合中文表达习惯，保持原意不变。只输出润色后的文本，不要包含任何解释或额外内容。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用主题
        applyTheme()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)

        // 先加载主题模式
        themeMode = prefs.getInt(PREF_THEME_MODE, THEME_LIGHT)
        // 应用深色/浅色主题的文本和背景色
        applyThemeColorsToViewTree(window.decorView)

        // 应用动态主题色到所有硬编码的蒂芙尼蓝元素
        accentColor = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())
        applyAccentToViewTree(window.decorView, accentColor)

        initViews()

        // 设置下拉刷新检查版本更新
        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        swipeRefresh?.setOnRefreshListener {
            checkForUpdates()
            swipeRefresh.isRefreshing = false
        }
        swipeRefresh?.setColorSchemeColors(accentColor)

        // 设置模型下拉框背景（边框颜色跟随主题色）
        tvCloudModel?.background = createSpinnerBackground(accentColor)
        // 云端模型下拉箭头颜色跟随主题色
        tvCloudModel?.let {
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_expand_more)?.mutate()
            drawable?.setTint(accentColor)
            it.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null)
        }

        // 语音与 AI 本地化设置 helper
        aiSettingsHelper = VoiceAISettingsHelper(this, prefs)
        aiSettingsHelper.bindViews(
            btnDownloadVoice, btnDownloadAi
        )

        statsManager = PolishStatsManager(this)
        dictManager = PinyinDictManager(this)
        loadSettings()
        aiSettingsHelper.loadSettings()
        // 初始化 VoiceEngine 命令词
        try {
            val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
            com.cesia.input.voice.VoiceEngine.updateCommandWords(
                cmdPrefs.getString("cmd_exit", "退出语音模式") ?: "退出语音模式",
                cmdPrefs.getString("cmd_polish", "智能润色") ?: "智能润色",
                cmdPrefs.getString("cmd_finish", "结束语音识别") ?: "结束语音识别",
                cmdPrefs.getString("cmd_send", "立即发送") ?: "立即发送",
                cmdPrefs.getString("cmd_command", "智能修改") ?: "智能修改"
            )
        } catch (_: Exception) {}
        setupListeners()
        aiSettingsHelper.setupListeners()
        refreshStats()
        showVersion()
        loadOpenRouterFreeModels()
        refreshDictInfo()

        checkAndRequestPermission()

        // 每天自动检查一次更新
        checkUpdateDaily()
    }

    private fun applyTheme() {
        val themeMode = prefs.getInt(PREF_THEME_MODE, THEME_LIGHT)
        if (themeMode == THEME_DARK) {
            setTheme(R.style.Theme_Cesia_Dark)
        } else {
            setTheme(R.style.Theme_Cesia)
        }
    }

    private fun initViews() {
        etApiUrl = findViewById(R.id.et_api_url)
        etApiKey = findViewById(R.id.et_openrouter_key)
        etTavilyKey = findViewById(R.id.et_brave_api_key)
        tvCloudModel = findViewById(R.id.tv_cloud_model)
        etPolishPrompt = findViewById(R.id.et_polish_prompt)
        etTestText = findViewById(R.id.et_test_text)
        btnTestApi = findViewById(R.id.btn_test_api)
        btnTestLocalAi = findViewById(R.id.btn_test_local_ai)
        tvStatus = findViewById(R.id.tv_api_status)
        tvLog = findViewById(R.id.tv_log)
        tvVersion = findViewById(R.id.tv_version)

        // 词库管理 - 只保留下载按钮
        try {
            btnDownloadDict = findViewById(R.id.btn_download_dict)
            tvDictInfo = findViewById(R.id.tv_dict_info)
        } catch (_: Exception) {}

        // 语音与 AI 本地化视图
        try {
            btnDownloadVoice = findViewById(R.id.btn_download_voice)
            btnDownloadAi = findViewById(R.id.btn_download_ai)
        } catch (_: Exception) {}

        // 语音命令词设置 (新顺序：智能写作、智能修改、智能润色、结束语音识别、立即发送、退出语音模式)
        try {
            etCmdWriting = findViewById(R.id.et_cmd_writing)      // 智能写作
            etCmdCommand = findViewById(R.id.et_cmd_command)      // 智能修改
            etCmdPolish = findViewById(R.id.et_cmd_polish)        // 智能润色
            etCmdFinish = findViewById(R.id.et_cmd_finish)        // 结束语音识别
            etCmdSend = findViewById(R.id.et_cmd_send)            // 立即发送
            etCmdExit = findViewById(R.id.et_cmd_exit)            // 退出语音模式
            tvCommandStatus = findViewById(R.id.tv_command_status)
        } catch (_: Exception) {}

        // 个性化设置
        try {
            etStatusIdle = findViewById(R.id.et_status_idle)
            etSmartWritingLabel = findViewById(R.id.et_smart_writing_label)
            etMagicBookTitle = findViewById(R.id.et_magic_book_title)
        } catch (_: Exception) {}

        // 统计卡片
        try {
            tvStatInputChars = findViewById(R.id.tv_stat_input_chars)
            tvStatOutputChars = findViewById(R.id.tv_stat_output_chars)
            tvStatCount = findViewById(R.id.tv_stat_count)
            tvStatVoiceTime = findViewById(R.id.tv_stat_voice_time)
            tvStatSavedTime = findViewById(R.id.tv_stat_saved_time)
            tvStatVoiceSpeed = findViewById(R.id.tv_stat_voice_speed)
        } catch (_: Exception) {}

        // 历史记录 + 新闻源管理
        try {
            btnHistory = findViewById(R.id.btn_history)
            btnNewsSources = findViewById(R.id.btn_news_sources)
        } catch (_: Exception) {}

        // 可编辑标题
        try {
            etSettingsTitle = findViewById(R.id.et_settings_title)
            tilSettingsTitle = findViewById(R.id.til_settings_title)
        } catch (_: Exception) {}

        // 测试区重置 prompt 按钮
        try {
            btnResetPrompt = findViewById(R.id.btn_reset_prompt)
        } catch (_: Exception) {}

        // 版本号容器和圆点
        try {
            llVersionContainer = findViewById(R.id.ll_version_container)
            versionDot = findViewById(R.id.version_dot)
            tvVersionWithDot = findViewById(R.id.tv_version_with_dot)
        } catch (_: Exception) {}
    }

    private var llVersionContainer: View? = null
    private var versionDot: View? = null
    private var tvVersionWithDot: TextView? = null
    private var versionCheckHandler: Handler? = null
    private var pulseAnimator: Animator? = null

    private fun showVersion() {
        val displayVersion = try {
            BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            Log.w("SettingsActivity", "读取 BuildConfig 版本失败: ${e.javaClass.simpleName} ${e.message}", e)
            "开发版"
        }

        val versionText = if (!displayVersion.isNullOrEmpty() && displayVersion != "null") {
            "● v$displayVersion"
        } else {
            "● 开发版"
        }
        tvVersion.text = versionText
        tvVersionWithDot?.text = "v$displayVersion"
        Log.d("SettingsActivity", "显示版本: $displayVersion")

        // 检查是否有缓存的远端版本
        checkCachedRemoteVersion()

        // 启动版本检查
        fetchGitHubVersion()
    }

    private fun checkCachedRemoteVersion() {
        val remoteVersion = prefs.getString("github_version_name", "") ?: ""
        if (remoteVersion.isNotEmpty()) {
            val localVersion = try {
                BuildConfig.VERSION_NAME
            } catch (_: Exception) {
                ""
            }
            if (compareVersions(remoteVersion, localVersion) > 0) {
                showUpdateIndicator(true)
            }
        }
    }

    private fun showUpdateIndicator(hasUpdate: Boolean) {
        runOnUiThread {
            if (hasUpdate) {
                tvVersion.visibility = View.GONE
                llVersionContainer?.visibility = View.VISIBLE
                // 设置版本号圆点颜色为主题色
                versionDot?.setBackgroundColor(accentColor)
                startPulseAnimation()
            } else {
                tvVersion.visibility = View.VISIBLE
                llVersionContainer?.visibility = View.GONE
                stopPulseAnimation()
            }
        }
    }

    private fun startPulseAnimation() {
        versionDot?.let { dot ->
            stopPulseAnimation()
            val animator = ObjectAnimator.ofPropertyValuesHolder(
                dot,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.5f, 1f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.5f, 1f),
                PropertyValuesHolder.ofFloat("alpha", 1f, 0.3f, 1f)
            ).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                start()
            }
            pulseAnimator = animator
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = if (i < parts1.size) parts1[i] else 0
            val p2 = if (i < parts2.size) parts2[i] else 0
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    private fun fetchGitHubVersion() {
            Thread {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/harviex/cesia-input-method/releases/latest")
                        .addHeader("User-Agent", "CesiaIME/1.0")
                        .get()
                        .build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val json = JSONObject(body)
                        val tagName = json.optString("tag_name", "").removePrefix("v")
                        if (tagName.isNotEmpty()) {
                            prefs.edit().putString("github_version_name", tagName).apply()
                            // 检查是否有更新
                            val localVersion = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "0" }
                            runOnUiThread { checkAndShowUpdate(tagName, localVersion) }
                            Log.d("SettingsActivity", "远端版本已缓存: $tagName")
                        }
                    }
                } catch (_: Exception) {}
            }.start()
        }

        private fun checkAndShowUpdate(remoteVersion: String, localVersion: String) {
            val cmp = compareVersions(remoteVersion, localVersion)
            val llVersionContainer = findViewById<LinearLayout>(R.id.ll_version_container)
            val versionDot = findViewById<View>(R.id.version_dot)
            val tvVersionWithDot = findViewById<TextView>(R.id.tv_version_with_dot)
            val tvVersion = findViewById<TextView>(R.id.tv_version)

            if (cmp > 0) {
                // 有更新：显示带圆点的版本，启动脉冲动画
                tvVersion.visibility = View.GONE
                llVersionContainer.visibility = View.VISIBLE
                tvVersionWithDot.text = "v$remoteVersion 可用"
                startPulseAnimation(versionDot)
            } else {
                // 无更新：显示原版本号
                tvVersion.visibility = View.VISIBLE
                llVersionContainer.visibility = View.GONE
                tvVersion.text = "v$localVersion"
            }
        }

        private fun startPulseAnimation(view: View) {
            val animator = ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.5f, 1f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.5f, 1f),
                PropertyValuesHolder.ofFloat("alpha", 1f, 0.3f, 1f)
            )
            animator.duration = 1500
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.RESTART
            animator.start()
        }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
            appendLog("🔐 请求录音权限...")
        } else {
            appendLog("✅ 录音权限已授予")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("✅ 录音权限已授予")
            } else {
                appendLog("❌ 录音权限被拒绝")
            }
        }
    }

    private fun loadSettings() {
        etApiUrl.setText(prefs.getString(PREF_API_URL, DEFAULT_API_URL))
        // OpenRouter API Key - 直接显示真实 key（恢复原有行为）
        etApiKey.setText(prefs.getString(PREF_OPENROUTER_KEY, ""))
        // Tavily API Key - 直接显示真实 key（与 OpenRouter 一致）
        etTavilyKey.setText(prefs.getString(PREF_BRAVE_KEY, ""))

        etPolishPrompt.setText(prefs.getString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT))

        // 加载语音命令词（新键名，兼容旧键名）
        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
        etCmdWriting?.setText(cmdPrefs.getString("cmd_writing", "智能写作"))
        etCmdCommand?.setText(cmdPrefs.getString("cmd_command", "智能修改"))
        etCmdPolish?.setText(cmdPrefs.getString("cmd_polish", "智能润色"))
        etCmdFinish?.setText(cmdPrefs.getString("cmd_finish", "结束语音识别"))
        etCmdSend?.setText(cmdPrefs.getString("cmd_send", "立即发送"))
        etCmdExit?.setText(cmdPrefs.getString("cmd_exit", "退出语音模式"))

        // 加载个性化设置
        etStatusIdle?.setText(prefs.getString("status_idle", ""))
        etSmartWritingLabel?.setText(prefs.getString("smart_writing_label", ""))
        etMagicBookTitle?.setText(prefs.getString("magic_book_title", "芙莉莲的魔法书"))

        // 加载设置标题
        etSettingsTitle?.setText(prefs.getString(PREF_SETTINGS_TITLE, "Cesia AI智能输入法"))

        // 初始化云端模型显示
        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID)
        tvCloudModel?.let { textView ->
            // 先显示默认文本，fetchModels 完成后会更新
            textView.text = "请选择模型"
        }

        // 更新 VoiceEngine 命令词
        VoiceEngine.updateCommandWords(
            cmdPrefs.getString("cmd_exit", "退出语音模式") ?: "退出语音模式",
            cmdPrefs.getString("cmd_polish", "智能润色") ?: "智能润色",
            cmdPrefs.getString("cmd_finish", "结束语音识别") ?: "结束语音识别",
            cmdPrefs.getString("cmd_send", "立即发送") ?: "立即发送",
            cmdPrefs.getString("cmd_command", "智能修改") ?: "智能修改",
            cmdPrefs.getString("cmd_writing", "智能写作") ?: "智能写作"
        )

        appendLog("已加载设置")
    }

    private fun setupListeners() {

        // 标题可编辑 - 焦点离开时自动保存
        etSettingsTitle?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val title = etSettingsTitle?.text?.toString()?.trim()
                if (title.isNullOrEmpty().not()) {
                    prefs.edit().putString(PREF_SETTINGS_TITLE, title!!).apply()
                    appendLog("标题已保存: $title")
                }
            }
        }

        // API URL - 焦点离开时自动保存
        etApiUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveApiUrl()
        }
        etApiUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })

        // API Key - 焦点离开时自动保存（直接显示真实 key，无需脱敏）
        etApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveApiKey()
        }

        // Tavily API Key - 焦点离开时自动保存（与 OpenRouter 一致）
        etTavilyKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTavilyKey()
        }

        // 润色 Prompt - 焦点离开时自动保存
        etPolishPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) savePolishPrompt()
        }

        // 语音命令词 - 焦点离开时自动保存
        val cmdFields = listOf(
            etCmdWriting to "cmd_writing",
            etCmdCommand to "cmd_command",
            etCmdPolish to "cmd_polish",
            etCmdFinish to "cmd_finish",
            etCmdSend to "cmd_send",
            etCmdExit to "cmd_exit"
        )
        cmdFields.forEach { (field, key) ->
            field?.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val value = field.text?.toString()?.trim() ?: ""
                    if (value.isNotEmpty()) {
                        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
                        cmdPrefs.edit().putString(key, value).apply()
                        updateVoiceEngineCommands()
                        appendLog("命令词已保存: $key=$value")
                    }
                }
            }
        }

        // 个性化设置 - 焦点离开时自动保存
        etStatusIdle?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = etStatusIdle?.text?.toString()?.trim() ?: ""
                prefs.edit().putString("status_idle", value).apply()
                appendLog("个性化已保存: status_idle=$value")
            }
        }
        etSmartWritingLabel?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = etSmartWritingLabel?.text?.toString()?.trim() ?: ""
                prefs.edit().putString("smart_writing_label", value).apply()
                appendLog("个性化已保存: smart_writing_label=$value")
            }
        }
        etMagicBookTitle?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = etMagicBookTitle?.text?.toString()?.trim() ?: ""
                prefs.edit().putString("magic_book_title", value).apply()
                appendLog("个性化已保存: magic_book_title=$value")
            }
        }

        // 测试按钮
        btnTestApi.setOnClickListener { testApiConnection() }
        btnTestLocalAi?.setOnClickListener { testLocalAiConnection() }

        // 词库管理 - 只有下载/重新下载按钮
        btnDownloadDict?.setOnClickListener { downloadDict() }

        // 测试区重置 prompt 按钮
        btnResetPrompt?.setOnClickListener { resetPolishPrompt() }

        // 云端模型选择 - 点击 TextView 打开选择对话框
        tvCloudModel?.setOnClickListener { showModelSelectorDialog(cloudModelList ?: emptyList()) }

        // 版本号点击检查更新
        tvVersion?.setOnClickListener { checkForUpdates() }
        // 版本号容器（有更新时显示）也可点击检查更新
        findViewById<LinearLayout>(R.id.ll_version_container)?.setOnClickListener { checkForUpdates() }
        findViewById<TextView>(R.id.tv_version_with_dot)?.setOnClickListener { checkForUpdates() }

        // 运行日志点击展开/折叠
        tvLog.setOnClickListener {
            isLogExpanded = !isLogExpanded
            if (isLogExpanded) {
                tvLog.maxLines = Int.MAX_VALUE
                tvLog.text = tvLog.text // 触发重新测量
                appendLog("📖 日志已展开")
            } else {
                tvLog.maxLines = 5
                appendLog("📕 日志已折叠 (点击展开)")
            }
        }

        // 历史记录 + 新闻源管理
        btnHistory?.setOnClickListener { showHistory() }
        btnNewsSources?.setOnClickListener { showNewsSourcePicker() }

        // 语音与 AI 本地化
        btnDownloadVoice?.setOnClickListener { downloadVoiceModel() }
        btnDownloadAi?.setOnClickListener { downloadAiModel() }
        // 卸载按钮已在 VoiceAISettingsHelper 中绑定（长按）
    }

    // ======================== 自动保存辅助方法 ========================

    private fun saveApiUrl() {
        val url = etApiUrl.text?.toString()?.trim() ?: ""
        if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
            prefs.edit().putString(PREF_API_URL, url).apply()
            appendLog("API URL 已保存: $url")
        }
    }

    private fun saveApiKey() {
        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        prefs.edit().putString(PREF_OPENROUTER_KEY, apiKey).apply()
        appendLog("OpenRouter API Key: ${if (apiKey.isNotEmpty()) "已设置(${apiKey.take(8)}...)" else "已清除"}")
    }

    private fun saveTavilyKey() {
        val apiKey = etTavilyKey.text?.toString()?.trim() ?: ""
        prefs.edit().putString(PREF_BRAVE_KEY, apiKey).apply()
        appendLog("Tavily API Key: ${if (apiKey.isNotEmpty()) "已设置(${apiKey.take(8)}...)" else "已清除"}")
    }

    private fun savePolishPrompt() {
        val prompt = etPolishPrompt.text?.toString()?.trim() ?: ""
        prefs.edit().putString(PREF_POLISH_PROMPT, prompt).apply()
        if (prompt.isNotEmpty()) {
            appendLog("润色 Prompt 已保存: ${prompt.take(50)}...")
        }
    }

    private fun updateVoiceEngineCommands() {
        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
        VoiceEngine.updateCommandWords(
            cmdPrefs.getString("cmd_exit", "退出语音模式") ?: "退出语音模式",
            cmdPrefs.getString("cmd_polish", "智能润色") ?: "智能润色",
            cmdPrefs.getString("cmd_finish", "结束语音识别") ?: "结束语音识别",
            cmdPrefs.getString("cmd_send", "立即发送") ?: "立即发送",
            cmdPrefs.getString("cmd_command", "智能修改") ?: "智能修改",
            cmdPrefs.getString("cmd_writing", "智能写作") ?: "智能写作"
        )
    }

    private fun resetPolishPrompt() {
        etPolishPrompt.setText(DEFAULT_POLISH_PROMPT)
        prefs.edit().putString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT).apply()
        tvStatus.text = "✅ 润色 Prompt 已重置为默认值"
        appendLog("🔄 润色 Prompt 已重置为默认值")
        Toast.makeText(this, "已重置为默认 Prompt", Toast.LENGTH_SHORT).show()
    }

    // ======================== 模型下载 ========================

    private fun downloadVoiceModel() {
        val modelInfo = ModelRegistry.getById("sherpa-zipformer") ?: return
        btnDownloadVoice?.isEnabled = false
        btnDownloadVoice?.text = "0%"
        tvStatus.text = "🔄 下载语音模型中..."
        appendLog("⬇ 开始下载语音模型: ${modelInfo.name}")
        // 记录下载状态，用于 Activity 恢复时检测
        getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
            .putBoolean("voice_downloading", true).commit()

        Thread {
            try {
                val dm = ModelDownloadManager(this@SettingsActivity)
                val result = kotlinx.coroutines.runBlocking {
                    dm.downloadZipformer { fileName, percent, downloadedBytes, totalBytes ->
                        runOnUiThread {
                            val pctStr = String.format("%.1f%%", percent)
                            val dlStr = ModelDownloadManager.Formatter.formatSize(downloadedBytes)
                            val totalStr = ModelDownloadManager.Formatter.formatSize(totalBytes)
                            tvStatus.text = "🔄 下载 $fileName ($pctStr)"
                            btnDownloadVoice?.text = pctStr
                            appendLog("⬇ $fileName: $pctStr ($dlStr / $totalStr)")
                        }
                    }
                }
                runOnUiThread {
                    btnDownloadVoice?.isEnabled = true
                    getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                        .putBoolean("voice_downloading", false).commit()
                    if (result.isSuccess) {
                        tvStatus.text = "✅ 语音模型下载完成"
                        appendLog("✅ 语音模型下载完成: ${result.getOrNull()?.absolutePath}")
                        Toast.makeText(this, "语音模型下载完成", Toast.LENGTH_SHORT).show()
                        btnDownloadVoice?.text = "✅ 已完成"
                    } else {
                        tvStatus.text = "❌ 下载失败: ${result.exceptionOrNull()?.message}"
                        appendLog("❌ 语音模型下载失败: ${result.exceptionOrNull()?.message}")
                        btnDownloadVoice?.text = "📥 语音识别"
                    }
                }
            } catch (e: Exception) {
                getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                    .putBoolean("voice_downloading", false).commit()
                runOnUiThread {
                    btnDownloadVoice?.isEnabled = true
                    btnDownloadVoice?.text = "📥 语音识别"
                    tvStatus.text = "❌ 下载异常: ${e.message}"
                    appendLog("❌ 语音模型下载异常: ${e.message}")
                }
            }
        }.start()
    }

    private fun downloadAiModel() {
        val modelInfo = ModelRegistry.getById("qwen35-2b-mnn")
            ?: ModelRegistry.ALL_MODELS.find { it.type == ModelInfo.ModelType.AI } ?: return
        btnDownloadAi?.isEnabled = false
        btnDownloadAi?.text = "0%"
        tvStatus.text = "🔄 下载 AI 模型中..."
        appendLog("⬇ 开始下载 AI 模型: ${modelInfo.name} (${modelInfo.sizeBytes / 1024 / 1024}MB)")
        // 记录下载状态，用于 Activity 恢复时检测
        getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
            .putBoolean("ai_downloading", true).commit()

        Thread {
            try {
                val dm = ModelDownloadManager(this@SettingsActivity)
                val result = kotlinx.coroutines.runBlocking {
                    dm.downloadAiModel(modelInfo) { name, percent, downloadedBytes, totalBytes ->
                        runOnUiThread {
                            val pctStr = String.format("%.1f%%", percent)
                            val dlStr = ModelDownloadManager.Formatter.formatSize(downloadedBytes)
                            val totalStr = ModelDownloadManager.Formatter.formatSize(totalBytes)
                            tvStatus.text = "🔄 下载 $name ($pctStr)"
                            btnDownloadAi?.text = pctStr
                            appendLog("⬇ $name: $pctStr ($dlStr / $totalStr)")
                        }
                    }
                }
                runOnUiThread {
                    btnDownloadAi?.isEnabled = true
                    getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                        .putBoolean("ai_downloading", false).commit()
                    if (result.isSuccess) {
                        tvStatus.text = "✅ AI 模型下载完成"
                        appendLog("✅ AI 模型下载完成: ${result.getOrNull()?.absolutePath}")
                        Toast.makeText(this, "AI 模型下载完成", Toast.LENGTH_SHORT).show()
                        btnDownloadAi?.text = "✅ 已完成"
                    } else {
                        tvStatus.text = "❌ 下载失败: ${result.exceptionOrNull()?.message}"
                        appendLog("❌ AI 模型下载失败: ${result.exceptionOrNull()?.message}")
                        btnDownloadAi?.text = "📥 语音润色"
                    }
                }
            } catch (e: Exception) {
                getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                    .putBoolean("ai_downloading", false).commit()
                runOnUiThread {
                    btnDownloadAi?.isEnabled = true
                    btnDownloadAi?.text = "📥 语音润色"
                    tvStatus.text = "❌ 下载异常: ${e.message}"
                    appendLog("❌ AI 模型下载异常: ${e.message}")
                }
            }
        }.start()
    }

    // ======================== 词库管理 ========================

    private fun refreshDictInfo() {
        val info = dictManager.getDictInfo()
        val syncTime = if (info.lastSync > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(info.lastSync))
        } else {
            "从未下载"
        }
        val sizeStr = when {
            info.dictSize < 1024 -> "${info.dictSize}B"
            info.dictSize < 1024 * 1024 -> "${info.dictSize / 1024}KB"
            else -> "${info.dictSize / 1024 / 1024}MB"
        }
        val statusText = if (info.downloaded) {
            "词库: $sizeStr | 词条: ${info.dictCount} 条 | 文件: ${info.fileCount} 个\n同步: $syncTime\n来源: rime-ice (iDvel/rime-ice)"
        } else {
            "词库: 使用内置精简版\n提示: 点击下载词库按钮获取完整词库（~50MB）\n来源: 内置"
        }
        tvDictInfo?.text = statusText
        btnDownloadDict?.text = if (info.downloaded) "重新下载" else "下载词库"
    }

    private fun downloadDict() {
        val alreadyDownloaded = dictManager.hasDownloadedDict()
        val title = if (alreadyDownloaded) "重新下载词库" else "下载完整词库"
        val message = if (alreadyDownloaded) {
            "将重新下载 rime-ice 完整词库（覆盖现有文件）。\n\n当前词库将被覆盖，操作不可撤销。"
        } else {
            "将下载 rime-ice 完整词库（full.zip，约 16MB 压缩包，解压后约 50MB）。\n\n词库包含：\n• 基础词库（~55万词条）\n• 扩展词库（41448字表）\n• 腾讯词库（~100万词条）\n• 英文词库\n• OpenCC 转换表\n• Lua 脚本\n\n下载完成后需重启输入法生效。\n\n来源：iDvel/rime-ice，GPL-3.0"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("下载") { _, _ ->
                startFullDictDownload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startFullDictDownload() {
        btnDownloadDict?.isEnabled = false
        btnDownloadDict?.text = "下载中..."
        tvStatus.text = "⏳ 正在下载词库..."
        appendLog("📥 开始下载完整词库（rime-ice full.zip）")
        appendLog("📋 词库来源：rime-ice (iDvel/rime-ice)")
        appendLog("⚖️  许可证：GPL-3.0（词库数据，不影响本应用）")

        // 显示进度条
        val pbDict = findViewById<android.widget.ProgressBar>(R.id.pb_dict_download)
        val tvDictProgress = findViewById<android.widget.TextView>(R.id.tv_dict_download_progress)
        pbDict?.visibility = android.view.View.VISIBLE
        tvDictProgress?.visibility = android.view.View.VISIBLE
        pbDict?.progress = 0

        dictManager.downloadFullDict(
            onProgress = { percent, downloadedBytes, totalBytes, msg ->
                runOnUiThread {
                    pbDict?.progress = percent
                    tvDictProgress?.text = "$msg ($percent%)"
                    tvStatus.text = msg
                    appendLog(msg)
                }
            },
            onComplete = { success, msg ->
                runOnUiThread {
                    btnDownloadDict?.isEnabled = true
                    pbDict?.visibility = android.view.View.GONE
                    tvDictProgress?.visibility = android.view.View.GONE
                    refreshDictInfo()
                    if (success) {
                        tvStatus.text = "✅ $msg"
                        appendLog("✅ $msg")
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        // 触发 Rime 重新部署
                        try {
                            val rimeEngine = com.cesia.input.engine.rime.RimeEngine(this)
                            rimeEngine.redeploy()
                            Toast.makeText(this, "词库已部署！请重启输入法（切换一下输入法）后生效", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "词库下载完成！重启输入法后生效", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        tvStatus.text = "❌ $msg"
                        appendLog("❌ $msg")
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // ======================== API 测试 ========================

    private fun testApiConnection() {
        val inputText = etTestText.text?.toString()?.trim() ?: ""
        if (inputText.isEmpty()) {
            Toast.makeText(this, "请先在文本框中输入要润色的文字", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestApi.isEnabled = false
        btnTestApi.text = "测试中..."
        tvStatus.text = "🔄 正在润色..."

        Thread {
            try {
                val apiUrl = etApiUrl.text?.toString()?.trim() ?: DEFAULT_API_URL
                val isOr = apiUrl.contains("openrouter.ai")

                val request = if (isOr) {
                    // OpenRouter 格式：使用用户自定义 prompt
                    val customPrompt = etPolishPrompt.text?.toString()?.trim() ?: DEFAULT_POLISH_PROMPT
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", customPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", inputText)
                        })
                    }
                    val selectedModel = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID)
                    val json = JSONObject().apply {
                        put("model", selectedModel)
                        put("messages", messages)
                        put("temperature", 0.3)
                        put("max_tokens", 512)
                    }
                    val apiKey = etApiKey.text?.toString()?.trim() ?: ""
                    Request.Builder()
                        .url(apiUrl)
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("HTTP-Referer", "https://github.com/harviex/cesia-input-method")
                        .build()
                } else {
                    // 自定义 API 格式
                    val json = JSONObject().apply {
                        put("text", inputText)
                        put("language", "zh")
                    }
                    Request.Builder()
                        .url(apiUrl)
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Content-Type", "application/json")
                        .addHeader("User-Agent", "CesiaIME/1.0")
                        .build()
                }

                val response = testClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val respCode = response.code

                runOnUiThread {
                    if (respCode in 200..299) {
                        try {
                            val respJson = JSONObject(body)
                            val polished = if (isOr) {
                                // OpenRouter 响应格式
                                val choices = respJson.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                                } else ""
                            } else {
                                // 自定义 API 响应格式
                                respJson.optString("polished_text", "")
                            }
                            if (polished.isNotEmpty() && polished != inputText) {
                                etTestText.setText(polished)
                                tvStatus.text = "✅ API 润色成功"
                                appendLog("润色成功: $polished")
                            } else {
                                tvStatus.text = "⚠️ API 返回但内容无变化"
                                appendLog("API 返回无变化: ${body.take(200)}")
                            }
                        } catch (e: Exception) {
                            tvStatus.text = "✅ API 成功 (原始响应)"
                            appendLog("API 响应: ${body.take(200)}")
                        }
                    } else {
                        tvStatus.text = "❌ API 错误 $respCode"
                        appendLog("API 失败 ($respCode): ${body.take(200)}")
                    }
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "API 润色"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误: ${e.message ?: "未知"}"
                    appendLog("API 测试异常: ${e.message}")
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "API 润色"
                }
            }
        }.start()
    }

    // ======================== 本地 AI 测试 ========================

    private fun testLocalAiConnection() {
        val inputText = etTestText.text?.toString()?.trim() ?: ""
        if (inputText.isEmpty()) {
            Toast.makeText(this, "请先在文本框中输入要润色的文字", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestLocalAi?.isEnabled = false
        btnTestLocalAi?.text = "推理中..."
        tvStatus.text = "🔄 正在加载模型并润色..."
        appendLog("🤖 本地 AI 测试开始: ${inputText.take(30)}...")

        Thread {
            try {
                val modelManager = com.cesia.input.model.ModelManager(this@SettingsActivity)
                val modelFile = modelManager.getInstalledAiModelFile()

                if (modelFile == null || !modelFile.exists()) {
                    runOnUiThread {
                        tvStatus.text = "❌ 未安装 AI 模型"
                        appendLog("本地 AI 失败: 模型未安装")
                        btnTestLocalAi?.isEnabled = true
                        btnTestLocalAi?.text = "本地 AI"
                    }
                    return@Thread
                }

                appendLog("模型文件: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")

                val aiEngine = com.cesia.input.ai.AIEngine(this@SettingsActivity)

                // 加载模型（在 IO 线程中运行 suspend 函数）
                appendLog("正在加载模型...")
                val loadStart = System.currentTimeMillis()
                val loaded = kotlinx.coroutines.runBlocking {
                    // MNN 模型：传 config.json 路径
                    val configPath = if (modelFile.isDirectory) {
                        File(modelFile, "config.json").absolutePath
                    } else {
                        modelFile.absolutePath
                    }
                    aiEngine.loadLocalModel(configPath)
                }
                val loadTime = System.currentTimeMillis() - loadStart

                if (!loaded) {
                    runOnUiThread {
                        val mnnLog = aiEngine.getMnnLog()
                        tvStatus.text = "❌ 模型加载失败"
                        appendLog("本地 AI 失败: 模型加载失败 (${loadTime}ms)")
                        if (mnnLog.isNotEmpty()) {
                            appendLog("MNN log: $mnnLog")
                        }
                        btnTestLocalAi?.isEnabled = true
                        btnTestLocalAi?.text = "本地 AI"
                    }
                    return@Thread
                }
                appendLog("模型加载成功 (${loadTime}ms)")

                // 推理（60 秒超时）
                appendLog("开始推理（最多 60 秒）...")
                val inferStart = System.currentTimeMillis()
                val result = kotlinx.coroutines.runBlocking {
                    withTimeoutOrNull(300000L) {
                        aiEngine.polish(inputText, "润色")
                    }
                }
                val inferTime = System.currentTimeMillis() - inferStart

                aiEngine.release()

                runOnUiThread {
                    if (result == null) {
                        tvStatus.text = "⏰ 推理超时（60s），模型可能太慢"
                        appendLog("推理超时（${inferTime}ms），请尝试更短的文本或更小的模型")
                    } else if (result.isNotEmpty() && result != inputText) {
                        etTestText.setText(result)
                        tvStatus.text = "✅ 本地 AI 润色成功 (${inferTime}ms)"
                        appendLog("润色成功 (${inferTime}ms): ${result.take(50)}...")
                    } else {
                        tvStatus.text = "⚠️ 润色结果为空"
                        appendLog("润色结果为空 (${inferTime}ms)")
                    }
                    btnTestLocalAi?.isEnabled = true
                    btnTestLocalAi?.text = "本地 AI"
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "本地 AI 测试异常", e)
                runOnUiThread {
                    tvStatus.text = "❌ 异常: ${e.message ?: "未知"}"
                    appendLog("本地 AI 异常: ${e.message}")
                    btnTestLocalAi?.isEnabled = true
                    btnTestLocalAi?.text = "本地 AI"
                }
            }
        }.start()
    }

    // ======================== 统计 & 日志 ========================

    override fun onResume() {
        super.onResume()
        refreshDictInfo()
        // 检测上次下载是否被系统终止（Activity 后台时被 kill）
        checkInterruptedDownloads()
    }

    /**
     * 检查是否有被中断的下载（Activity 进入后台导致 Thread 被 kill）
     */
    private fun checkInterruptedDownloads() {
        val prefs = getSharedPreferences("cesia_download", Context.MODE_PRIVATE)
        if (prefs.getBoolean("voice_downloading", false)) {
            Log.w("SettingsActivity", "检测到语音下载在上次会话中被中断")
            prefs.edit().putBoolean("voice_downloading", false).apply()
            tvStatus.text = "❌ 语音模型下载被中断（Activity 进入后台）"
            appendLog("⚠️ 语音模型下载在上次会话中被系统终止，请保持前台重新下载")
            btnDownloadVoice?.isEnabled = true
            btnDownloadVoice?.text = "📥 下载语音模型"
        }
        if (prefs.getBoolean("ai_downloading", false)) {
            Log.w("SettingsActivity", "检测到 AI 模型下载在上次会话中被中断")
            prefs.edit().putBoolean("ai_downloading", false).apply()
            tvStatus.text = "❌ AI 模型下载被中断（Activity 进入后台）"
            appendLog("⚠️ AI 模型下载在上次会话中被系统终止，请保持前台重新下载")
            btnDownloadAi?.isEnabled = true
            btnDownloadAi?.text = "📥 下载 AI 模型"
        }
    }

    // ======================== 统计刷新 ========================

    private fun refreshStats() {
        // 文本统计
        tvStatInputChars.text = statsManager.totalInputChars.toString()
        tvStatOutputChars.text = statsManager.totalOutputChars.toString()
        tvStatCount.text = statsManager.totalPolishCount.toString()

        // 语音统计
        val voiceTotalMs = statsManager.totalVoiceDurationMs
        val voiceTimeMin = voiceTotalMs / 60000
        tvStatVoiceTime?.text = "${voiceTimeMin}分钟"

        val savedTimeMin = statsManager.savedTimeSeconds / 60
        tvStatSavedTime?.text = "${savedTimeMin}分钟"

        val speed = statsManager.voiceSpeedPerMinute
        tvStatVoiceSpeed?.text = "${speed}字/分"
    }

    // ======================== 历史记录 & 新闻源 ========================

    private fun showHistory() {
        val intent = Intent(this, HistoryActivity::class.java)
        startActivity(intent)
    }

    private fun showNewsSourcePicker() {
        val intent = Intent(this, NewsSourceActivity::class.java)
        startActivity(intent)
    }

    // ======================== 日志工具 ========================

    private fun appendLog(msg: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(System.currentTimeMillis())
            val current = tvLog.text?.toString() ?: ""
            val newLog = (current + "\n[$timestamp] $msg").lines().takeLast(50).joinToString("\n")
            tvLog.text = newLog
        }
    }

    // ======================== 检查更新 ========================

    private fun checkUpdateDaily() {
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        if (now - lastCheck > 24 * 60 * 60 * 1000L) {
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        tvVersion?.isEnabled = false
        tvVersion?.text = "版本: 检查中..."
        appendLog("🔍 检查更新...")

        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/harviex/cesia-input-method/releases/latest")
                    .addHeader("User-Agent", "CesiaIME/1.0")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                runOnUiThread {
                    tvVersion?.isEnabled = true
                    tvVersion?.text = "版本: ${BuildConfig.VERSION_NAME}"
                }

                if (!response.isSuccessful) {
                    appendLog("❌ 检查更新失败: ${response.code}")
                    if (response.code == 403) {
                        appendLog("GitHub API 速率限制，请稍后重试")
                    }
                    return@Thread
                }

                val json = JSONObject(body)
                val latestVersionName = json.optString("tag_name", "").removePrefix("v")
                val releaseUrl = json.optString("html_url", "")
                val releaseNotes = json.optString("body", "")
                // 从 release body 或 assets 中获取 APK 下载链接
                val apkUrl = try {
                    val assets = json.optJSONArray("assets")
                    if (assets != null && assets.length() > 0) {
                        assets.getJSONObject(0).optString("browser_download_url", "")
                    } else ""
                } catch (_: Exception) { "" }

                // 版本比较：直接读 BuildConfig，不依赖 packageManager
                val currentVersionName = BuildConfig.VERSION_NAME
                val currentVersionCode = BuildConfig.VERSION_CODE

                // 从 tag_name 解析最新版本的 versionCode (格式: 1.1.X -> X)
                val latestVersionCode = try {
                    val parts = latestVersionName.split(".")
                    if (parts.size >= 3) parts[2].toLong() else 0L
                } catch (e: Exception) {
                    Log.w("SettingsActivity", "解析远端版本失败: ${e.javaClass.simpleName} ${e.message}", e)
                    0L
                }

                // 本地versionCode >= 最新版本versionCode 表示已是最新
                val isUpToDate = currentVersionCode > 0 && currentVersionCode >= latestVersionCode

                appendLog("本地: $currentVersionName($currentVersionCode), 最新: $latestVersionName($latestVersionCode), isUpToDate=$isUpToDate")

                runOnUiThread {
                    showVersion()
                    if (!isUpToDate && latestVersionCode > 0) {
                        showUpdateDialog(latestVersionName, releaseUrl, releaseNotes, apkUrl)
                    } else {
                        tvStatus.text = "已是最新版本 ($latestVersionName)"
                        appendLog("已是最新版本")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvVersion?.isEnabled = true
                    tvVersion?.text = "版本: ${BuildConfig.VERSION_NAME}"
                    appendLog("❌ 检查更新异常: ${e.message}")
                }
            }
        }.start()
    }

    private fun showUpdateDialog(version: String, url: String, notes: String, apkUrl: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle("🎉 发现新版本 $version")
                .setMessage("当前版本已不是最新。\n\n更新内容:\n${notes.take(500)}")
                .setPositiveButton("立即更新") { _, _ ->
                    downloadAndInstallApk(apkUrl, version)
                }
                .setNegativeButton("稍后", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun downloadAndInstallApk(apkUrl: String, version: String) {
        if (apkUrl.isEmpty()) {
            // 没有直接的APK链接，尝试构造
            Toast.makeText(this, "无法获取下载链接，请手动到GitHub下载", Toast.LENGTH_LONG).show()
            return
        }

        // 在版本号区域显示下载进度
        llVersionContainer?.visibility = View.VISIBLE
        tvVersion?.text = "⏳ 正在下载 v$version..."
        tvVersionWithDot?.text = "v$version"
        versionDot?.visibility = View.GONE
        appendLog("开始下载: $apkUrl")

        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(apkUrl).get().build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                                    runOnUiThread {
                                        tvVersion?.text = "❌ 下载失败: ${response.code}"
                                        tvVersionWithDot?.text = "v$version"
                                        versionDot?.visibility = View.GONE
                                        llVersionContainer?.visibility = View.VISIBLE
                                        appendLog("下载失败: ${response.code}")
                                    }
                                    return@Thread
                                }

                // 保存到缓存目录
                val apkFile = java.io.File(cacheDir, "cesia-update.apk")
                response.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                runOnUiThread {
                    tvVersion?.text = "✅ 下载完成，正在安装..."
                    tvVersionWithDot?.text = "v$version"
                    versionDot?.visibility = View.GONE
                    llVersionContainer?.visibility = View.VISIBLE
                    appendLog("APK下载完成: ${apkFile.absolutePath}")
                }

                // 触发安装
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            // Android 7+ 使用 FileProvider
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this@SettingsActivity,
                                "${packageName}.fileprovider",
                                apkFile
                            )
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } else {
                            setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    appendLog("已触发安装")
                } catch (e: Exception) {
                    runOnUiThread {
                        tvVersion?.text = "❌ 安装失败: ${e.message}"
                        tvVersionWithDot?.text = "v$version"
                        versionDot?.visibility = View.GONE
                        llVersionContainer?.visibility = View.VISIBLE
                        appendLog("安装失败: ${e.message}")
                        Toast.makeText(this, "安装失败，请手动安装: ${apkFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvVersion?.text = "❌ 下载异常: ${e.message}"
                    tvVersionWithDot?.text = "v$version"
                    versionDot?.visibility = View.GONE
                    llVersionContainer?.visibility = View.VISIBLE
                    appendLog("下载异常: ${e.message}")
                }
            }
        }.start()
    }

    // ======================== 云端模型加载 ========================

    data class CloudModel(
        val id: String,
        val name: String,
        val provider: String,
        val contextLength: Long,
        val hasTools: Boolean,
        val hasVision: Boolean
    )

    private var cloudModelList: List<CloudModel>? = null

    private fun loadOpenRouterFreeModels() {
        val url = "https://harviex.github.io/openrouter-free-dashboard/data/models.json"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    tvCloudModel?.text = "⚠️ 无法加载模型列表：${e.message}"
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        tvCloudModel?.text = "⚠️ 加载失败：HTTP ${response.code}"
                    }
                    return
                }
                val body = response.body?.string() ?: return
                try {
                    val json = org.json.JSONObject(body)
                    val models = json.getJSONArray("models")
                    val list = mutableListOf<CloudModel>()
                    for (i in 0 until models.length()) {
                        val m = models.getJSONObject(i)
                        list.add(
                            CloudModel(
                                id = m.optString("id", ""),
                                name = m.optString("name", m.optString("id", "")),
                                provider = m.optString("provider", ""),
                                contextLength = m.optLong("context_length", 0),
                                hasTools = m.optBoolean("has_tools", false),
                                hasVision = m.optBoolean("has_vision", false)
                            )
                        )
                    }
                    cloudModelList = list
                    runOnUiThread {
                        // 更新 TextView 显示当前选中的模型名称
                        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID)
                        val currentModel = list.find { it.id == savedModelId }
                        currentModel?.let {
                            tvCloudModel?.text = it.name
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvCloudModel?.text = "⚠️ 加载失败"
                    }
                }
            }
        })
    }

    private fun showModelSelectorDialog(models: List<CloudModel>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_selector, null)
        val rvModels = dialogView.findViewById<RecyclerView>(R.id.rv_model_list)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_model_dialog_title)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_model_dialog_close)

        // Set current model in title
        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        val currentModel = models.find { it.id == savedModelId }
        currentModel?.let {
            tvTitle.text = "当前: ${it.name}"
        }

        rvModels.layoutManager = LinearLayoutManager(this)
        rvModels.adapter = ModelSelectorAdapter(models, savedModelId) { model ->
            // Update the TextView
            tvCloudModel?.text = model.name
            // Save the model ID
            prefs.edit().putString(PREF_MODEL_ID, model.id).apply()
            appendLog("模型已保存: ${model.id}")
            // Dismiss dialog
            (dialogView.parent as? android.app.Dialog)?.dismiss()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // 关闭按钮点击
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private inner class ModelSelectorAdapter(
        private val models: List<CloudModel>,
        private val selectedModelId: String,
        private val onModelClick: (CloudModel) -> Unit
    ) : RecyclerView.Adapter<ModelSelectorAdapter.ModelViewHolder>() {

        inner class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_model_name)
            val tvProvider: TextView = view.findViewById(R.id.tv_model_provider)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_model_selector, parent, false)
            return ModelViewHolder(view)
        }

        override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
            val model = models[position]
            val ctxStr = if (model.contextLength >= 1000000L)
                "${model.contextLength / 1000000}M"
            else if (model.contextLength >= 1000L)
                "${model.contextLength / 1000}K"
            else "${model.contextLength}"
            holder.tvName.text = "${model.name} ($ctxStr)"
            holder.tvProvider.text = model.provider
            val isSelected = model.id == selectedModelId
            holder.itemView.setBackgroundColor(if (isSelected) 0xFFE0F7FA.toInt() else 0xFFFFFFFF.toInt())
            holder.itemView.setOnClickListener { onModelClick(model) }
        }

        override fun getItemCount() = models.size
    }

    // ======================== 新闻源选择器的 RecyclerView Adapter ========================

    // ===== 折叠分类选择器 =====

    /** 列表项类型：分类头 或 源条目 */
    private sealed class CategoryAdapterItem {
        data class Header(val category: String, val count: Int) : CategoryAdapterItem()
        data class SourceItem(val source: RssFetchManager.RssSource) : CategoryAdapterItem()
    }

    /** 简单的可变引用包装（让 adapter 和 Activity 共享同步共享同一变量） */
    private class MutableRef<T>(var value: T)

    /** 折叠分类适配器 */
    private inner class CategorySourceAdapter(
        private val items: MutableList<CategoryAdapterItem>,
        private val expandedCategories: MutableSet<String>,
        private val categories: List<String>,
        private val sourcesByCategory: Map<String, List<RssFetchManager.RssSource>>,
        private val etCustomUrl: android.widget.EditText,
        private val selectedSourceRef: MutableRef<RssFetchManager.RssSource?>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is CategoryAdapterItem.Header -> TYPE_HEADER
                is CategoryAdapterItem.SourceItem -> TYPE_SOURCE
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = layoutInflater.inflate(R.layout.item_rss_category, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = layoutInflater.inflate(R.layout.item_rss_source, parent, false)
                SourceViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is CategoryAdapterItem.Header -> {
                    val h = holder as HeaderViewHolder
                    h.tvCategoryName.text = "📂 ${item.category}"
                    h.tvCategoryCount.text = "${item.count} 个源"
                    val arrow = if (expandedCategories.contains(item.category)) "▼" else "▶"
                    h.tvCategoryArrow.text = arrow
                    h.itemView.setOnClickListener { toggleCategory(item.category) }
                }
                is CategoryAdapterItem.SourceItem -> {
                    val h = holder as SourceViewHolder
                    h.tvName.text = item.source.name
                    h.tvCategory.text = item.source.category
                    val currentSelected = selectedSourceRef.value
                    val isSelected = currentSelected?.name == item.source.name && currentSelected?.url == item.source.url
                    h.rb.isChecked = isSelected
                    h.itemView.setOnClickListener { selectSource(item.source) }
                    h.rb.setOnClickListener { selectSource(item.source) }
                }
            }
        }

        fun toggleCategory(category: String) {
            if (expandedCategories.contains(category)) {
                expandedCategories.remove(category)
            } else {
                expandedCategories.add(category)
            }
            rebuildItems()
            notifyDataSetChanged()
        }

        fun selectSource(source: RssFetchManager.RssSource) {
            selectedSourceRef.value = source
            etCustomUrl.setText("")
            notifyDataSetChanged()
        }

        private fun rebuildItems() {
            items.clear()
            for (category in categories) {
                items.add(CategoryAdapterItem.Header(category, sourcesByCategory[category]?.size ?: 0))
                if (expandedCategories.contains(category)) {
                    sourcesByCategory[category]?.forEach { source ->
                        items.add(CategoryAdapterItem.SourceItem(source))
                    }
                }
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvCategoryArrow: TextView = view.findViewById(R.id.tv_category_arrow)
            val tvCategoryName: TextView = view.findViewById(R.id.tv_category_name)
            val tvCategoryCount: TextView = view.findViewById(R.id.tv_category_count)
        }

        inner class SourceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val rb: RadioButton = view.findViewById(R.id.rb_source)
            val tvName: TextView = view.findViewById(R.id.tv_source_name)
            val tvCategory: TextView = view.findViewById(R.id.tv_source_category)
        }
    }

    /** 遍历 view 树，应用深色/浅色主题的文本颜色和背景色 */
    private fun applyThemeColorsToViewTree(view: android.view.View) {
        val isDark = themeMode == THEME_DARK
        val textPrimary = if (isDark) 0xFFE0E0E0.toInt() else 0xFF333333.toInt()
        val textSecondary = if (isDark) 0xFFB0B0B0.toInt() else 0xFF666666.toInt()
        val textTertiary = if (isDark) 0xFF888888.toInt() else 0xFF999999.toInt()
        val textHint = if (isDark) 0xFF888888.toInt() else 0xFF999999.toInt()
        val bgPrimary = if (isDark) 0xFF1A1A2E.toInt() else 0xFFFAFAFA.toInt()
        val bgSecondary = if (isDark) 0xFF16213E.toInt() else 0xFFFFFFFF.toInt()
        val bgCard = if (isDark) 0xFF1A1A2E.toInt() else 0xFFFFFFFF.toInt()
        val dividerColor = if (isDark) 0xFF333333.toInt() else 0xFFE0E0E0.toInt()
        val cardAccentBg = if (isDark) 0xFF1A1A2E.toInt() else 0xFFE8F5F5.toInt() // Tiffany tint for cards

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyThemeColorsToViewTree(view.getChildAt(i))
            }
        }

        // TextView text colors
        if (view is android.widget.TextView) {
            val currentColor = view.currentTextColor
            // Replace hardcoded light theme colors with theme-aware colors
            when (currentColor) {
                0xFF333333.toInt(), 0xFF444444.toInt(), 0xFF555555.toInt() -> view.setTextColor(textPrimary)
                0xFF666666.toInt(), 0xFF888888.toInt(), 0xFF999999.toInt(), 0xFFAAAAAA.toInt() -> view.setTextColor(textSecondary)
                0xFFCCCCCC.toInt(), 0xFFDDDDDD.toInt(), 0xFFEEEEEE.toInt() -> view.setTextColor(textTertiary)
                0xFFFFFFFF.toInt() -> view.setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF333333.toInt())
                else -> {
                    // Check hint color
                    val hintColor = view.hintTextColors?.defaultColor ?: 0
                    if (hintColor == 0xFF999999.toInt() || hintColor == 0xFF888888.toInt() || hintColor == 0xFFAAAAAA.toInt()) {
                        view.setHintTextColor(textHint)
                    }
                }
            }
        }

        // Background colors
        try {
            val bg = view.background
            if (bg is android.graphics.drawable.ColorDrawable) {
                val bgColor = bg.color
                when (bgColor) {
                    0xFFFAFAFA.toInt(), 0xFFF5F5F5.toInt(), 0xFFF0F0F0.toInt(), 0xFFF8F8F8.toInt(), 0xFFEEEEEE.toInt() -> {
                        // Light backgrounds -> use bgPrimary for root, bgSecondary for cards
                        view.setBackgroundColor(if (view is android.view.ViewGroup && view.childCount > 0) bgSecondary else bgPrimary)
                    }
                    0xFF0F0F23.toInt(), 0xFF1A1A2E.toInt(), 0xFF16213E.toInt() -> {
                        // Dark backgrounds already dark, keep or update
                        view.setBackgroundColor(if (view is android.view.ViewGroup && view.childCount > 0) bgCard else bgPrimary)
                    }
                    0xFFE0E0E0.toInt(), 0xFFCCCCCC.toInt() -> {
                        // Divider-like backgrounds
                        view.setBackgroundColor(dividerColor)
                    }
                    0xFFE8F5F5.toInt() -> {
                        // Tiffany tinted card background
                        view.setBackgroundColor(cardAccentBg)
                    }
                }
            }
        } catch (_: Exception) {}

        // View backgrounds (solid colors set via setBackgroundColor)
        try {
            val bg = view.background
            if (bg is android.graphics.drawable.ColorDrawable) {
                val bgColor = bg.color
                when (bgColor) {
                    0xFFFAFAFA.toInt(), 0xFFF5F5F5.toInt(), 0xFFF0F0F0.toInt(), 0xFFF8F8F8.toInt(), 0xFFEEEEEE.toInt() -> {
                        view.setBackgroundColor(if (view is android.view.ViewGroup && view.childCount > 0) bgSecondary else bgPrimary)
                    }
                    0xFF0F0F23.toInt(), 0xFF1A1A2E.toInt(), 0xFF16213E.toInt() -> {
                        view.setBackgroundColor(if (view is android.view.ViewGroup && view.childCount > 0) bgCard else bgPrimary)
                    }
                    0xFFE0E0E0.toInt(), 0xFFCCCCCC.toInt() -> {
                        view.setBackgroundColor(dividerColor)
                    }
                    0xFFE8F5F5.toInt() -> {
                        view.setBackgroundColor(cardAccentBg)
                    }
                }
            }
        } catch (_: Exception) {}

        // Dividers (View with small height/width)
        if (view is android.view.View) {
            val lp = view.layoutParams
            if ((lp is android.view.ViewGroup.LayoutParams && lp.height == 1 && lp.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) ||
                (lp is android.view.ViewGroup.LayoutParams && lp.width == 1 && lp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT)) {
                view.setBackgroundColor(dividerColor)
            }
        }

        // MaterialButton text color
        if (view is com.google.android.material.button.MaterialButton) {
            val currentTextColor = view.currentTextColor
            if (currentTextColor == 0xFF333333.toInt() || currentTextColor == 0xFFFFFFFF.toInt()) {
                view.setTextColor(textPrimary)
            }
        }

        // TextInputLayout hint/text colors
        if (view is com.google.android.material.textfield.TextInputLayout) {
            view.defaultHintTextColor = android.content.res.ColorStateList.valueOf(textHint)
            // boxStrokeColor handled by applyAccentToViewTree
        }

        // EditText text color
        if (view is android.widget.EditText) {
            val currentColor = view.currentTextColor
            if (currentColor == 0xFF333333.toInt() || currentColor == 0xFFFFFFFF.toInt()) {
                view.setTextColor(textPrimary)
            }
            val hintColor = view.hintTextColors?.defaultColor ?: 0
            if (hintColor == 0xFF999999.toInt() || hintColor == 0xFF888888.toInt()) {
                view.setHintTextColor(textHint)
            }
        }
    }

    /** 遍历 view 树，将蒂芙尼蓝替换为主题色 */
    private fun applyAccentToViewTree(view: android.view.View, accent: Int) {
        val tintList = android.content.res.ColorStateList.valueOf(accent)
        val tiffany = 0xFF81D8D0.toInt()
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyAccentToViewTree(view.getChildAt(i), accent)
            }
        }
        val defaultColor = (view as? android.widget.TextView)?.textColors?.defaultColor ?: 0
        if (defaultColor == tiffany) (view as? android.widget.TextView)?.setTextColor(accent)
        val bgTint = try { view.backgroundTintList?.defaultColor ?: 0 } catch (_: Exception) { 0 }
        if (bgTint == tiffany) view.backgroundTintList = tintList
        // Handle solid background color
        try {
            val bg = view.background
            if (bg is android.graphics.drawable.ColorDrawable && bg.color == tiffany) {
                view.setBackgroundColor(accent)
            }
        } catch (_: Exception) {}
        // Handle MaterialButton strokeColor
        if (view is com.google.android.material.button.MaterialButton) {
            try {
                if (view.strokeColor?.defaultColor == tiffany) {
                    view.strokeColor = tintList
                }
            } catch (_: Exception) {}
        }
        // Handle RadioButton buttonTint
        if (view is android.widget.RadioButton) {
            try {
                if (view.buttonTintList?.defaultColor == tiffany) {
                    view.buttonTintList = tintList
                }
            } catch (_: Exception) {}
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

    /** 创建模型下拉框背景，边框颜色跟随主题色 */
    private fun createSpinnerBackground(accent: Int): android.graphics.drawable.GradientDrawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        drawable.setColor(0xFFFFFFFF.toInt()) // 白色背景
        drawable.setStroke(dpToPx(1), accent) // 1dp 边框，主题色
        drawable.cornerRadius = dpToPx(8).toFloat() // 8dp 圆角
        drawable.setPadding(dpToPx(12), dpToPx(8), dpToPx(32), dpToPx(8))
        return drawable
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}