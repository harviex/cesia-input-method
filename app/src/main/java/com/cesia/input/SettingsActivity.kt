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
import com.cesia.input.onboarding.OnboardingActivity
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
    private lateinit var tvApiUrl: TextView
    private lateinit var tvApiKey: TextView
    private lateinit var tvTavilyKey: TextView
    private var tvCloudModel: TextView? = null
    private lateinit var etPolishPrompt: TextInputEditText
    private lateinit var etTestText: TextInputEditText
    private lateinit var tilTestText: com.google.android.material.textfield.TextInputLayout
    private lateinit var btnTestApi: MaterialButton
    private var btnTestLocalAi: MaterialButton? = null
    private lateinit var tvLog: TextView
    private var isLogExpanded = true
    private lateinit var tvVersion: TextView
    private lateinit var statsManager: PolishStatsManager
    private lateinit var dictManager: PinyinDictManager

    // === 语音与 AI 本地化 ===
    private lateinit var localModeManager: LocalModeManager
    private lateinit var modelManager: ModelManager
    private lateinit var downloadManager: ModelDownloadManager
    private var btnInstallVoice: Button? = null
    private var btnInstallAi: Button? = null
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

    // 词库信息（已并入运行日志开头）
    private val prefs by lazy { getSharedPreferences("cesia_settings", MODE_PRIVATE) }
    private var accentColor: Int = 0xFF81D8D0.toInt()
    // 连续点击"检查更新"且已是最新版的次数（用于"不要再拉了"提示）
    private var upToDateCheckCount = 0
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
        const val DEFAULT_POLISH_PROMPT = "你是一个文本润色与输入排版高手。请将输入的口语文字处理为通顺的书面文字，并严格执行以下规则：\n严禁删减核心信息，严禁随意扩写。仅修正错别字、口语和语序，加入标点。只输出润色排版后的纯文本。禁止解释，禁止添加任何前缀（如\"润色后：\"）或后缀。如果用户输入的内容包含多个观点、步骤或长篇大论，请自动通过\"换行分段\"或使用\"* \"进行分点陈列。"
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

        // 三个自定义下拉（API URL / Key / Tavily）边框 + 箭头跟随主题色
        listOf(tvApiUrl, tvApiKey, tvTavilyKey).forEach { tv ->
            tv.background = createSpinnerBackground(accentColor)
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_expand_more)?.mutate()
            drawable?.setTint(accentColor)
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null)
        }

        // 语音与 AI 本地化设置 helper（仅用于模型扫描/状态，按钮绑定已移到本 Activity）
        aiSettingsHelper = VoiceAISettingsHelper(this, prefs)
        aiSettingsHelper.bindViews(null, null)
        modelManager = com.cesia.input.model.ModelManager(this)
        downloadManager = ModelDownloadManager(this)

        statsManager = PolishStatsManager(this)
        dictManager = PinyinDictManager(this)
        loadSettings()
        aiSettingsHelper.loadSettings()
        // 根据已安装状态刷新下载按钮文字/底色
        refreshVoiceInstallState()
        refreshAiInstallState()
        // 根据已选供应方/已装模型刷新测试按钮可用状态
        updateTestButtonStates()
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

        // 检查是否首次启动，显示新手引导横幅
        checkFirstLaunchOnboarding()

        checkAndRequestPermission()

        // 每天自动检查一次更新（后台静默，受 24h 限制）
        checkUpdateDaily()
        // 打开设置页即检查更新：若有新版本自动弹出下载菜单
        checkForUpdates()
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
        tvApiUrl = findViewById(R.id.tv_api_url)
        tvApiKey = findViewById(R.id.tv_api_key)
        tvTavilyKey = findViewById(R.id.tv_tavily_key)
        tvCloudModel = findViewById(R.id.tv_cloud_model)
        etPolishPrompt = findViewById(R.id.et_polish_prompt)
        etTestText = findViewById(R.id.et_test_text)
        tilTestText = findViewById(R.id.til_test_text)
        btnTestApi = findViewById(R.id.btn_test_api)
        btnTestLocalAi = findViewById(R.id.btn_test_local_ai)
        tvLog = findViewById(R.id.tv_log)
        tvVersion = findViewById(R.id.tv_version)

        // 语音与 AI 本地化视图
        try {
            btnInstallVoice = findViewById(R.id.btn_install_voice)
            btnInstallAi = findViewById(R.id.btn_install_ai)
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
            pbVersionDownload = findViewById(R.id.pb_version_download)
        } catch (_: Exception) {}

        // 新手引导横幅
        try {
            viewOnboardingBanner = findViewById(R.id.view_onboarding_banner)
        } catch (_: Exception) {}
    }

    private var llVersionContainer: View? = null
    private var versionDot: View? = null
    private var tvVersionWithDot: TextView? = null
    private var pbVersionDownload: ProgressBar? = null
    private var viewOnboardingBanner: View? = null
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
        val savedUrl = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        tvApiUrl.text = apiUrlDisplay(savedUrl)
        val savedKey = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
        tvApiKey.text = if (savedKey.isEmpty()) "点击选择或输入" else if (savedKey.length > 20) maskSecret(savedKey) else savedKey
        val savedTavily = prefs.getString(PREF_BRAVE_KEY, "") ?: ""
        tvTavilyKey.text = if (savedTavily.isEmpty()) "点击选择或输入" else if (savedTavily.length > 20) maskSecret(savedTavily) else savedTavily

        etPolishPrompt.setText(prefs.getString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT))

        // 加载语音命令词（新键名，兼容旧键名）
        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
        etCmdWriting?.setText(cmdPrefs.getString("cmd_writing", "写作"))
        etCmdCommand?.setText(cmdPrefs.getString("cmd_command", "修改"))
        etCmdPolish?.setText(cmdPrefs.getString("cmd_polish", "润色"))
        etCmdFinish?.setText(cmdPrefs.getString("cmd_finish", "结束"))
        etCmdSend?.setText(cmdPrefs.getString("cmd_send", "发送"))
        etCmdExit?.setText(cmdPrefs.getString("cmd_exit", "退出"))

        // 加载个性化设置
        etStatusIdle?.setText(prefs.getString("status_idle", "人工智能已就绪"))
        etSmartWritingLabel?.setText(prefs.getString("smart_writing_label", "智能写作菜单"))
        etMagicBookTitle?.setText(prefs.getString("magic_book_title", "智能修改菜单"))

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
            cmdPrefs.getString("cmd_exit", "退出") ?: "退出",
            cmdPrefs.getString("cmd_polish", "润色") ?: "润色",
            cmdPrefs.getString("cmd_finish", "结束") ?: "结束",
            cmdPrefs.getString("cmd_send", "发送") ?: "发送",
            cmdPrefs.getString("cmd_command", "修改") ?: "修改",
            cmdPrefs.getString("cmd_writing", "写作") ?: "写作"
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

        // API URL / Key / Tavily - 点击打开下拉菜单（含自定义输入 + 历史记忆 + 删除）
        tvApiUrl.setOnClickListener {
            showCustomValueDialog(
                title = "AI API URL",
                presets = listOf(
                    "https://openrouter.ai/api/v1/chat/completions" to "openrouter.ai"
                ),
                historyKey = "api_url_history",
                prefKey = PREF_API_URL,
                currentValue = if (tvApiUrl.text.toString() == "请选择模型供应方") "" else tvApiUrl.text.toString(),
                valueView = tvApiUrl,
                isSecret = false
            )
        }
        tvApiKey.setOnClickListener {
            showCustomValueDialog(
                title = "AI API Key",
                presets = emptyList(),
                historyKey = "api_key_history",
                prefKey = PREF_OPENROUTER_KEY,
                currentValue = if (tvApiKey.text.toString() == "点击选择或输入") "" else tvApiKey.text.toString(),
                valueView = tvApiKey,
                isSecret = true,
                freeUrl = "https://openrouter.ai/"
            )
        }
        tvTavilyKey.setOnClickListener {
            showCustomValueDialog(
                title = "Tavily API Key",
                presets = emptyList(),
                historyKey = "tavily_key_history",
                prefKey = PREF_BRAVE_KEY,
                currentValue = if (tvTavilyKey.text.toString() == "点击选择或输入") "" else tvTavilyKey.text.toString(),
                valueView = tvTavilyKey,
                isSecret = true,
                freeUrl = "https://www.tavily.com/"
            )
        }

        // 润色 Prompt - 焦点离开时自动保存
        etPolishPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) savePolishPrompt()
        }

        // 语音命令词 - 焦点离开时自动保存（含校验）
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
                    val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
                    val old = cmdPrefs.getString(key, "") ?: ""
                    if (value.isEmpty()) {
                        // 清空时若无变动不处理；有变动才恢复默认
                        if (old.isNotEmpty()) {
                            cmdPrefs.edit().putString(key, "").apply()
                            updateVoiceEngineCommands()
                            appendLog("命令词已清空: $key")
                        }
                        return@setOnFocusChangeListener
                    }
                    if (value == old) return@setOnFocusChangeListener // 无变动不保存
                    // 校验：仅中文/英文，不含空格/符号/数字，且不超过 4 字
                    if (value.contains(" ")) {
                        appendLog("❌ 命令词「$value」不允许空格，已忽略")
                        Toast.makeText(this, "命令词不允许空格", Toast.LENGTH_SHORT).show()
                        field.setText(old) // 还原
                        return@setOnFocusChangeListener
                    }
                    if (value.length > 4) {
                        appendLog("❌ 命令词「$value」不能超过4个字，已忽略")
                        Toast.makeText(this, "命令词不能超过4个字", Toast.LENGTH_SHORT).show()
                        field.setText(old) // 还原
                        return@setOnFocusChangeListener
                    }
                    if (!value.matches(Regex("^[\\u4e00-\\u9fa5a-zA-Z]+\$"))) {
                        appendLog("❌ 命令词「$value」仅允许中英文，已忽略")
                        Toast.makeText(this, "命令词仅允许中英文", Toast.LENGTH_SHORT).show()
                        field.setText(old) // 还原
                        return@setOnFocusChangeListener
                    }
                    // 校验：6 个命令词不可重复
                    val others = cmdFields.mapNotNull { (f, k) ->
                        if (k == key) null else (f?.text?.toString()?.trim() ?: cmdPrefs.getString(k, "") ?: "")
                    }
                    if (others.contains(value)) {
                        appendLog("❌ 命令词「$value」与其他命令词重复，已忽略")
                        Toast.makeText(this, "命令词不可重复", Toast.LENGTH_SHORT).show()
                        field.setText(old) // 还原
                        return@setOnFocusChangeListener
                    }
                    cmdPrefs.edit().putString(key, value).apply()
                    updateVoiceEngineCommands()
                    appendLog("命令词已保存: $key=$value")
                }
            }
        }

        // 个性化设置 - 焦点离开时自动保存
        etStatusIdle?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = etStatusIdle?.text?.toString()?.trim() ?: ""
                val old = prefs.getString("status_idle", "") ?: ""
                if (value != old) {
                    prefs.edit().putString("status_idle", value).apply()
                    appendLog("个性化已保存: status_idle=$value")
                }
            }
        }
        etSmartWritingLabel?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = etSmartWritingLabel?.text?.toString()?.trim() ?: ""
                val old = prefs.getString("smart_writing_label", "") ?: ""
                if (value != old) {
                    prefs.edit().putString("smart_writing_label", value).apply()
                    appendLog("个性化已保存: smart_writing_label=$value")
                }
            }
        }
        etMagicBookTitle?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = etMagicBookTitle?.text?.toString()?.trim() ?: ""
                val old = prefs.getString("magic_book_title", "") ?: ""
                if (value != old) {
                    prefs.edit().putString("magic_book_title", value).apply()
                    appendLog("个性化已保存: magic_book_title=$value")
                }
            }
        }

        // 测试按钮
        btnTestApi.setOnClickListener { testApiConnection() }
        btnTestLocalAi?.setOnClickListener { testLocalAiConnection() }

        // 词库管理 - 只有下载/重新下载按钮
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
        btnInstallVoice?.setOnClickListener { downloadVoiceModel() }
        btnInstallAi?.setOnClickListener { downloadAiModel() }
        btnInstallVoice?.setOnLongClickListener { showUninstallMenu(true); true }
        btnInstallAi?.setOnLongClickListener { showUninstallMenu(false); true }
    }

    // ======================== API URL/Key 下拉自定义 ========================

    // 通用下拉选择对话框：预选项 + 历史记忆 + 自定义输入
    // 选中或保存后写入 prefs(prefKey) 并更新显示(valueView)，同时把值记入 historyKey 列表供下次直接选择
    private fun showCustomValueDialog(
        title: String,
        presets: List<Pair<String, String>>, // (value, displayName)
        historyKey: String,
        prefKey: String,
        currentValue: String,
        valueView: TextView,
        isSecret: Boolean,
        freeUrl: String = ""
    ) {
        val allItems = LinkedHashSet<String>().apply {
            if (currentValue.isNotEmpty()) add(currentValue)
            addAll(presets.map { it.first })
            addAll(getHistory(historyKey))
        }.toList().toMutableList()

        // value -> 显示文案（API URL 等需要把地址映射成友好名）
        fun displayOf(v: String): String {
            val preset = presets.find { it.first == v }
            if (preset != null && preset.second.isNotEmpty()) return preset.second
            return if (isSecret) maskSecret(v) else v
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_value, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_title)
        val lvValues = dialogView.findViewById<ListView>(R.id.lv_values)
        val etCustom = dialogView.findViewById<EditText>(R.id.et_custom)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save)
        val btnApplyFree = dialogView.findViewById<Button>(R.id.btn_apply_free)

        tvTitle.text = title
        etCustom.hint = if (isSecret) "自定义：输入后点“保存并使用”" else "自定义：输入后点“保存并使用”"

        val adapter = object : ArrayAdapter<String>(
            this, R.layout.item_custom_value, R.id.tv_item_text, allItems
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_custom_value, parent, false)
                val tv = view.findViewById<TextView>(R.id.tv_item_text)
                val btnDel = view.findViewById<TextView>(R.id.tv_item_delete)
                val v = allItems[position]
                tv.text = displayOf(v)
                btnDel.setOnClickListener {
                    removeHistory(historyKey, v)
                    allItems.remove(v)
                    remove(v)
                    notifyDataSetChanged()
                    appendLog("已删除: ${if (isSecret) maskSecret(v) else v}")
                }
                return view
            }
        }
        lvValues.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        lvValues.setOnItemClickListener { _, _, position, _ ->
            val value = allItems[position]
            val ok = applyValueWithCheck(prefKey, historyKey, value, valueView, isSecret, title)
            if (ok) dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val value = etCustom.text?.toString()?.trim() ?: ""
            if (value.isNotEmpty()) {
                val ok = applyValueWithCheck(prefKey, historyKey, value, valueView, isSecret, title)
                if (ok) dialog.dismiss()
            } else {
                Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            }
        }

        if (freeUrl.isNotEmpty()) {
            btnApplyFree.visibility = View.VISIBLE
            btnApplyFree.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(freeUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    // 应用值并做针对性审查（openrouter key / tavily key）
    // 返回 true 表示通过审查可关闭对话框，false 表示被拦截
    private fun applyValueWithCheck(
        prefKey: String,
        historyKey: String,
        value: String,
        valueView: TextView,
        isSecret: Boolean,
        title: String
    ): Boolean {
        // 针对 API Key 的审查
        if (prefKey == PREF_OPENROUTER_KEY) {
            // 仅当当前 URL 为 openrouter 时才审查 sk-or 开头
            val url = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
            if (url.contains("openrouter.ai", ignoreCase = true) && !value.startsWith("sk-or", ignoreCase = true)) {
                promptKeyMismatch(title, "https://openrouter.ai/")
                return false
            }
        }
        if (prefKey == PREF_BRAVE_KEY) {
            if (!value.startsWith("tvly", ignoreCase = true)) {
                Toast.makeText(this, "密钥不匹配：Tavily Key 需以 tvly 开头", Toast.LENGTH_LONG).show()
                return false
            }
        }
        applyValue(prefKey, historyKey, value, valueView, isSecret, title)
        return true
    }

    // key 不匹配时提示去填入或注册
    private fun promptKeyMismatch(title: String, registerUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("未检测到匹配的 Key")
            .setMessage("当前为 OpenRouter，需要以 sk-or 开头的 Key。是否前往注册或返回填入？")
            .setPositiveButton("去注册") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(registerUrl)))
                } catch (_: Exception) {}
            }
            .setNegativeButton("返回填入") { _, _ -> }
            .show()
    }

    private fun applyValue(
        prefKey: String,
        historyKey: String,
        value: String,
        valueView: TextView,
        isSecret: Boolean,
        title: String
    ) {
        prefs.edit().putString(prefKey, value).apply()
        addHistory(historyKey, value)
        if (prefKey == PREF_API_URL) {
            prefs.edit().putBoolean("api_url_configured", true).apply()
            updateTestButtonStates()
        }
        // 显示：密钥脱敏（前15 + 后5）；API URL 显示友好名
        valueView.text = when {
            isSecret && value.length > 20 -> maskSecret(value)
            prefKey == PREF_API_URL -> apiUrlDisplay(value)
            else -> value
        }
        val disp = if (isSecret) "已设置(${if (value.length > 20) maskSecret(value) else value})" else value
        appendLog("$title: $disp")
    }

    private fun getHistory(key: String): List<String> {
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("||").filter { it.isNotEmpty() }
    }

    private fun addHistory(key: String, value: String) {
        val list = getHistory(key).toMutableList()
        list.remove(value)
        list.add(0, value)
        val trimmed = list.take(10)
        prefs.edit().putString(key, trimmed.joinToString("||")).apply()
    }

    private fun removeHistory(key: String, value: String) {
        val list = getHistory(key).toMutableList()
        list.remove(value)
        prefs.edit().putString(key, list.joinToString("||")).apply()
    }

    // API URL 显示映射：openrouter 地址显示友好名，自定义地址显示原始地址
    // 初始未配置时显示“请选择模型供应方”
    private fun apiUrlDisplay(value: String): String {
        val configured = prefs.getBoolean("api_url_configured", false)
        return when {
            value == DEFAULT_API_URL && !configured -> "请选择模型供应方"
            value == DEFAULT_API_URL -> "openrouter.ai"
            else -> value
        }
    }

    private fun maskSecret(s: String): String {
        return if (s.length <= 20) s else "${s.take(15)}****${s.takeLast(5)}"
    }

    private fun savePolishPrompt() {
        val prompt = etPolishPrompt.text?.toString()?.trim() ?: ""
        val old = prefs.getString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT) ?: DEFAULT_POLISH_PROMPT
        if (prompt == old) return // 无变动不保存、不记日志
        prefs.edit().putString(PREF_POLISH_PROMPT, prompt).apply()
        if (prompt.isNotEmpty()) {
            appendLog("润色 Prompt 已保存: ${prompt.take(50)}...")
        }
    }

    private fun updateVoiceEngineCommands() {
        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
        VoiceEngine.updateCommandWords(
            cmdPrefs.getString("cmd_exit", "退出") ?: "退出",
            cmdPrefs.getString("cmd_polish", "润色") ?: "润色",
            cmdPrefs.getString("cmd_finish", "结束") ?: "结束",
            cmdPrefs.getString("cmd_send", "发送") ?: "发送",
            cmdPrefs.getString("cmd_command", "修改") ?: "修改",
            cmdPrefs.getString("cmd_writing", "写作") ?: "写作"
        )
    }

    private fun resetPolishPrompt() {
        etPolishPrompt.setText(DEFAULT_POLISH_PROMPT)
        prefs.edit().putString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT).apply()
        appendLog("🔄 润色 Prompt 已重置为默认值")
        Toast.makeText(this, "已重置为默认 Prompt", Toast.LENGTH_SHORT).show()
    }

    // ======================== 模型下载 ========================
    // 按钮即进度条：圆角，主题色为进度填充，主题色减淡 50% 为背景
    private fun applyButtonProgress(button: android.widget.Button?, percent: Int, text: String) {
        val btn = button ?: return
        val accent = accentColor
        val bg = lighten(accent, 0.5f)
        val radius = 10f * resources.displayMetrics.density
        val base = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = radius
        }
        val fillLayer = android.graphics.drawable.GradientDrawable().apply {
            setColor(accent); cornerRadius = radius
        }
        val clip = android.graphics.drawable.ClipDrawable(
            fillLayer, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        clip.level = (percent.coerceIn(0, 100) * 100).coerceIn(0, 10000)
        val layer = android.graphics.drawable.LayerDrawable(arrayOf(base, clip))
        // 清除 backgroundTint，否则 MaterialButton 会用 tint 覆盖进度层
        if (btn is com.google.android.material.button.MaterialButton) {
            btn.backgroundTintList = null
        }
        btn.background = layer
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.text = text
    }

    // 初始/重置态：跟随主题色底 + 白字
    private fun resetButtonBg(button: android.widget.Button?, text: String) {
        val btn = button ?: return
        val accent = accentColor
        if (btn is com.google.android.material.button.MaterialButton) {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        } else {
            btn.setBackgroundColor(accent)
        }
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.text = text
    }

    // 已安装态：同样跟随主题色 + 白字（不转黑）
    private fun installedButtonBg(button: android.widget.Button?, text: String) {
        resetButtonBg(button, text)
    }

    // 将颜色按因子减淡（factor=0.5 → 与原色各占一半，即减淡50%）
    private fun lighten(color: Int, factor: Float): Int {
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        val nr = (r + (255 - r) * factor).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * factor).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * factor).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(255, nr, ng, nb)
    }

    private fun setBothVoiceProgress(percent: Int, text: String) {
        applyButtonProgress(btnInstallVoice, percent, text)
    }
    private fun setBothAiProgress(percent: Int, text: String) {
        applyButtonProgress(btnInstallAi, percent, text)
    }
    private fun resetBothVoice(text: String) {
        resetButtonBg(btnInstallVoice, text)
    }
    private fun resetBothAi(text: String) {
        resetButtonBg(btnInstallAi, text)
    }

    // 长按弹出卸载菜单
    private fun showUninstallMenu(isVoice: Boolean) {
        val popup = android.widget.PopupMenu(this, if (isVoice) btnInstallVoice else btnInstallAi)
        popup.menu.add(0, 1, 0, if (isVoice) "卸载语音文字输入套件" else "卸载本地AI模型")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (isVoice) uninstallVoiceSuite() else uninstallAiModel()
                }
            }
            true
        }
        popup.show()
    }

    // 卸载语音文字输入套件（语音模型 + 雾凇词库）
    private fun uninstallVoiceSuite() {
        Thread {
            // 删除语音模型
            downloadManager.deleteModel("sherpa-zipformer")
            // 删除雾凇词库
            val rimeDir = dictManager.getRimeDir()
            if (rimeDir.exists()) rimeDir.deleteRecursively()
            dictManager.clearDownloadedState()
            runOnUiThread {
                refreshVoiceInstallState()
                Toast.makeText(this, "语音文字输入套件已卸载", Toast.LENGTH_SHORT).show()
                appendLog("🗑 已卸载语音文字输入套件（语音模型+雾凇词库）")
            }
        }.start()
    }

    // 卸载本地 AI 模型
    private fun uninstallAiModel() {
        Thread {
            val aiId = modelManager.installedAiModelId ?: "qwen35-2b-mnn"
            downloadManager.deleteModel(aiId)
            runOnUiThread {
                refreshAiInstallState()
                Toast.makeText(this, "本地AI模型已卸载", Toast.LENGTH_SHORT).show()
                appendLog("🗑 已卸载本地AI模型: $aiId")
            }
        }.start()
    }

    // 根据已安装状态刷新按钮文字/底色
    private fun refreshVoiceInstallState() {
        val voiceInstalled = modelManager.getInstalledVoiceModelFile() != null
        val dictInstalled = dictManager.hasDownloadedDict()
        if (voiceInstalled && dictInstalled) {
            installedButtonBg(btnInstallVoice, "语音文字输入套件已安装")
        } else {
            resetButtonBg(btnInstallVoice, "安装语音文字输入套件")
        }
    }
    private fun refreshAiInstallState() {
        if (modelManager.hasAiModel()) {
            installedButtonBg(btnInstallAi, "本地AI模型已安装")
        } else {
            resetButtonBg(btnInstallAi, "选装本地AI模型(1.2G)")
        }
        updateTestButtonStates()
    }

    private fun downloadVoiceModel() {
        val modelInfo = ModelRegistry.getById("sherpa-zipformer") ?: return
        btnInstallVoice?.isEnabled = false
        setBothVoiceProgress(0, "准备下载...")
        getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
            .putBoolean("voice_downloading", true).commit()
        appendLog("⬇ 开始下载语音文字输入套件（语音模型 + 雾凇词库）")

        // 阶段一：下载语音模型（0%~50%）
        Thread {
            try {
                val dm = ModelDownloadManager(this@SettingsActivity)
                val result = kotlinx.coroutines.runBlocking {
                    dm.downloadZipformer { fileName, percent, downloadedBytes, totalBytes ->
                        runOnUiThread {
                            val pctStr = String.format("%.1f%%", percent)
                            val dlStr = ModelDownloadManager.Formatter.formatSize(downloadedBytes)
                            val totalStr = ModelDownloadManager.Formatter.formatSize(totalBytes)
                            val overall = (percent * 0.5).toInt()
                            setBothVoiceProgress(overall, "下载语音模型")
                        }
                    }
                }
                if (!result.isSuccess) {
                    runOnUiThread {
                        btnInstallVoice?.isEnabled = true
                        getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                            .putBoolean("voice_downloading", false).commit()
                        appendLog("❌ 语音模型下载失败: ${result.exceptionOrNull()?.message}")
                        resetBothVoice("安装语音文字输入套件")
                    }
                    return@Thread
                }
                // 阶段二：下载雾凇词库（50%~100%）
                runOnUiThread {
                    setBothVoiceProgress(50, "正在下载雾凇词库")
                }
                var dictOk = false
                var dictMsg = ""
                dictManager.downloadFullDict(
                    onProgress = { percent, _, _, msg ->
                        runOnUiThread {
                            val overall = 50 + (percent * 0.5).toInt()
                            setBothVoiceProgress(overall, "正在下载雾凇词库")
                        }
                    },
                    onComplete = { success, msg ->
                        dictOk = success
                        dictMsg = msg
                        runOnUiThread {
                            btnInstallVoice?.isEnabled = true
                            getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                                .putBoolean("voice_downloading", false).commit()
                            if (dictOk) {
                                appendLog("✅ 语音文字输入套件安装完成")
                                Toast.makeText(this, "语音文字输入套件安装完成", Toast.LENGTH_SHORT).show()
                                refreshVoiceInstallState()
                                refreshDictInfo()
                                // 触发 Rime 重新部署
                                try {
                                    val rimeEngine = com.cesia.input.engine.rime.RimeEngine(this)
                                    rimeEngine.redeploy()
                                    Toast.makeText(this, "词库已部署！请重启输入法后生效", Toast.LENGTH_LONG).show()
                                } catch (_: Exception) {}
                            } else {
                                appendLog("❌ 词库下载失败: $dictMsg")
                                resetBothVoice("安装语音文字输入套件")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                    .putBoolean("voice_downloading", false).commit()
                runOnUiThread {
                    btnInstallVoice?.isEnabled = true
                    resetBothVoice("安装语音文字输入套件")
                    appendLog("❌ 套件下载异常: ${e.message}")
                }
            }
        }.start()
    }

    private fun downloadAiModel() {
        val modelInfo = ModelRegistry.getById("qwen35-2b-mnn")
            ?: ModelRegistry.ALL_MODELS.find { it.type == ModelInfo.ModelType.AI } ?: return
        btnInstallAi?.isEnabled = false
        setBothAiProgress(0, "0%")
        appendLog("⬇ 开始下载 AI 模型: ${modelInfo.name} (${modelInfo.sizeBytes / 1024 / 1024}MB)")
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
                            setBothAiProgress(percent.toInt(), "下载AI模型")
                        }
                    }
                }
                runOnUiThread {
                    btnInstallAi?.isEnabled = true
                    getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                        .putBoolean("ai_downloading", false).commit()
                    if (result.isSuccess) {
                        appendLog("✅ AI 模型下载完成: ${result.getOrNull()?.absolutePath}")
                        Toast.makeText(this, "AI 模型下载完成", Toast.LENGTH_SHORT).show()
                        refreshAiInstallState()
                    } else {
                        appendLog("❌ AI 模型下载失败: ${result.exceptionOrNull()?.message}")
                        resetBothAi("选装本地AI模型(1.2G)")
                    }
                }
            } catch (e: Exception) {
                getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                    .putBoolean("ai_downloading", false).commit()
                runOnUiThread {
                    btnInstallAi?.isEnabled = true
                    resetBothAi("选装本地AI模型(1.2G)")
                    appendLog("❌ AI 模型下载异常: ${e.message}")
                }
            }
        }.start()
    }

    // ======================== 词库信息（并入运行日志开头） ========================

    private fun refreshDictInfo() {
        Thread {
            // 词库信息统计涉及遍历整个 rime 目录 + 逐文件统计词条，较重，放到后台线程避免返回设置页卡顿
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
                "【词库】 $sizeStr | 词条: ${info.dictCount} 条 | 文件: ${info.fileCount} 个 | 同步: $syncTime | 来源: rime-ice"
            } else {
                "【词库】 使用内置精简版（点击「安装语音文字输入套件」获取完整词库~50MB）"
            }
            runOnUiThread {
                // 词库信息作为运行日志开头第一行
                val current = tvLog?.text?.toString() ?: ""
                if (current.startsWith("【词库】")) {
                    tvLog?.text = statusText + "\n" + current.substringAfter("\n")
                } else {
                    tvLog?.text = statusText + "\n" + current
                }
            }
        }.start()
    }

    // ======================== API 测试 ========================

    // 在“测试要润色的文本”框显示测试结果：
    // 成功 → 文本框变主题色并闪烁几下后停止；失败 → 文本框变红并显示错误代码
    private fun showTestResult(success: Boolean, message: String) {
        val accent = accentColor
        val red = 0xFFE53935.toInt()
        val ed = etTestText
        val til = tilTestText
        if (success) {
            til.error = null
            til.boxStrokeColor = accent
            // 闪烁：主题色 <-> 白色 交替几次后定格主题色
            val white = 0xFFFFFFFF.toInt()
            val handler = android.os.Handler(mainLooper)
            var count = 0
            val total = 6
            val runnable = object : Runnable {
                override fun run() {
                    ed.setTextColor(if (count % 2 == 0) accent else white)
                    count++
                    if (count <= total) {
                        handler.postDelayed(this, 160)
                    } else {
                        ed.setTextColor(accent)
                    }
                }
            }
            handler.post(runnable)
        } else {
            ed.setTextColor(red)
            til.boxStrokeColor = red
            til.error = message
        }
    }

    private fun testApiConnection() {
        val inputText = etTestText.text?.toString()?.trim() ?: ""
        if (inputText.isEmpty()) {
            Toast.makeText(this, "请先在文本框中输入要润色的文字", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestApi.isEnabled = false
        applyButtonProgress(btnTestApi, 30, "润色中...")
        appendLog("🔄 正在润色...")

        Thread {
            try {
                val apiUrl = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
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
                    val apiKey = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
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
                                showTestResult(true, "润色成功")
                                appendLog("润色成功: $polished")
                            } else {
                                showTestResult(true, "API 返回但内容无变化")
                                appendLog("API 返回无变化: ${body.take(200)}")
                            }
                        } catch (e: Exception) {
                            showTestResult(true, "API 成功 (原始响应)")
                            appendLog("API 响应: ${body.take(200)}")
                        }
                    } else {
                        showTestResult(false, "API 错误 $respCode")
                        appendLog("API 失败 ($respCode): ${body.take(200)}")
                    }
                    btnTestApi.isEnabled = true
                    resetButtonBg(btnTestApi, "API 润色")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showTestResult(false, "网络错误: ${e.message ?: "未知"}")
                    appendLog("API 测试异常: ${e.message}")
                    btnTestApi.isEnabled = true
                    resetButtonBg(btnTestApi, "API 润色")
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
        applyButtonProgress(btnTestLocalAi, 20, "推理中...")
        appendLog("🔄 正在加载模型并润色...")

        Thread {
            try {
                val modelManager = com.cesia.input.model.ModelManager(this@SettingsActivity)
                val modelFile = modelManager.getInstalledAiModelFile()

                if (modelFile == null || !modelFile.exists()) {
                    runOnUiThread {
                        showTestResult(false, "未安装 AI 模型")
                        appendLog("本地 AI 失败: 模型未安装")
                        btnTestLocalAi?.isEnabled = true
                        resetButtonBg(btnTestLocalAi, "本地 AI")
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
                        showTestResult(false, "模型加载失败")
                        appendLog("本地 AI 失败: 模型加载失败 (${loadTime}ms)")
                        if (mnnLog.isNotEmpty()) {
                            appendLog("MNN log: $mnnLog")
                        }
                        btnTestLocalAi?.isEnabled = true
                        resetButtonBg(btnTestLocalAi, "本地 AI")
                    }
                    return@Thread
                }
                appendLog("模型加载成功 (${loadTime}ms)")
                applyButtonProgress(btnTestLocalAi, 60, "推理中...")

                // 推理（60 秒超时）
                appendLog("开始推理（最多 60 秒）...")
                val inferStart = System.currentTimeMillis()
                val result = kotlinx.coroutines.runBlocking {
                    withTimeoutOrNull(300000L) {
                        aiEngine.polish(inputText, "润色")
                    }
                }
                applyButtonProgress(btnTestLocalAi, 85, "推理中...")
                val inferTime = System.currentTimeMillis() - inferStart

                aiEngine.release()

                runOnUiThread {
                    if (result == null) {
                        showTestResult(false, "推理超时（60s）")
                        appendLog("推理超时（${inferTime}ms），请尝试更短的文本或更小的模型")
                    } else if (result.isNotEmpty() && result != inputText) {
                        etTestText.setText(result)
                        showTestResult(true, "本地 AI 润色成功 (${inferTime}ms)")
                        appendLog("润色成功 (${inferTime}ms): ${result.take(50)}...")
                    } else {
                        showTestResult(true, "润色结果为空")
                        appendLog("润色结果为空 (${inferTime}ms)")
                    }
                    btnTestLocalAi?.isEnabled = true
                    resetButtonBg(btnTestLocalAi, "本地 AI")
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "本地 AI 测试异常", e)
                runOnUiThread {
                    showTestResult(false, "异常: ${e.message ?: "未知"}")
                    appendLog("本地 AI 异常: ${e.message}")
                    btnTestLocalAi?.isEnabled = true
                    resetButtonBg(btnTestLocalAi, "本地 AI")
                }
            }
        }.start()
    }

    // ======================== 统计 & 日志 ========================

    // 根据是否已选择供应方 / 是否已安装本地 AI 模型，更新测试按钮可用状态
    private fun updateTestButtonStates() {
        val urlConfigured = prefs.getBoolean("api_url_configured", false)
        btnTestApi?.isEnabled = urlConfigured
        btnTestApi?.alpha = if (urlConfigured) 1f else 0.4f

        val aiModelFile = try {
            com.cesia.input.model.ModelManager(this).getInstalledAiModelFile()
        } catch (_: Exception) { null }
        val aiInstalled = aiModelFile != null && aiModelFile.exists()
        btnTestLocalAi?.isEnabled = aiInstalled
        btnTestLocalAi?.alpha = if (aiInstalled) 1f else 0.4f
    }

    override fun onResume() {
        super.onResume()
        refreshDictInfo()
        // 检测上次下载是否被系统终止（Activity 后台时被 kill）
        checkInterruptedDownloads()
        // 新手引导横幅保持常驻显示（由 checkFirstLaunchOnboarding 统一控制），不在 onResume 隐藏
    }

    /**
     * 检查是否有被中断的下载（Activity 进入后台导致 Thread 被 kill）
     */
    private fun checkInterruptedDownloads() {
        val prefs = getSharedPreferences("cesia_download", Context.MODE_PRIVATE)
        if (prefs.getBoolean("voice_downloading", false)) {
            Log.w("SettingsActivity", "检测到语音下载在上次会话中被中断")
            prefs.edit().putBoolean("voice_downloading", false).apply()
            appendLog("⚠️ 语音文字输入套件下载在上次会话中被系统终止，请保持前台重新下载")
            btnInstallVoice?.isEnabled = true
            resetButtonBg(btnInstallVoice, "安装语音文字输入套件")
        }
        if (prefs.getBoolean("ai_downloading", false)) {
            Log.w("SettingsActivity", "检测到 AI 模型下载在上次会话中被中断")
            prefs.edit().putBoolean("ai_downloading", false).apply()
            appendLog("⚠️ 本地AI模型下载在上次会话中被系统终止，请保持前台重新下载")
            btnInstallAi?.isEnabled = true
            resetButtonBg(btnInstallAi, "选装本地AI模型(1.2G)")
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
            upToDateCheckCount = 0 // 新的一天，重置"已是最新版"提示计数
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

                // 版本比较：使用 compareVersions 正确比较三段版本号
                // （之前错误地把 latestVersionCode 解析为仅 patch 段，导致 1020687 >= 687 永远成立，更新框永不弹出）
                val currentVersionName = BuildConfig.VERSION_NAME
                val currentVersionCode = BuildConfig.VERSION_CODE

                val cmp = compareVersions(latestVersionName, currentVersionName)
                val isUpToDate = cmp <= 0

                appendLog("本地: $currentVersionName($currentVersionCode), 最新: $latestVersionName, cmp=$cmp, isUpToDate=$isUpToDate")

                runOnUiThread {
                    showVersion()
                    if (cmp > 0) {
                        upToDateCheckCount = 0
                        showUpdateDialog(latestVersionName, releaseUrl, releaseNotes, apkUrl)
                    } else {
                        upToDateCheckCount++
                        val vtext = " $latestVersionName"
                        if (upToDateCheckCount >= 2) {
                            appendLog("不要再拉了，已是最新版$vtext")
                            Toast.makeText(this, "不要再拉了，已是最新版$vtext", Toast.LENGTH_SHORT).show()
                        } else {
                            appendLog("已是最新版$vtext")
                            Toast.makeText(this, "已是最新版$vtext", Toast.LENGTH_SHORT).show()
                        }
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
        pbVersionDownload?.visibility = View.VISIBLE
        pbVersionDownload?.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
        pbVersionDownload?.progress = 0
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
                        pbVersionDownload?.visibility = View.GONE
                        llVersionContainer?.visibility = View.VISIBLE
                        appendLog("下载失败: ${response.code}")
                    }
                    return@Thread
                }

                // 获取文件总大小
                val contentLength = response.body?.contentLength() ?: -1L
                var downloaded = 0L

                // 保存到缓存目录
                val apkFile = java.io.File(cacheDir, "cesia-update.apk")
                response.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            output.write(buffer, 0, len)
                            downloaded += len
                            if (contentLength > 0) {
                                val progress = (downloaded * 100 / contentLength).toInt()
                                runOnUiThread {
                                    pbVersionDownload?.progress = progress
                                    tvVersion?.text = "⏳ 下载中 ${progress}%..."
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    tvVersion?.text = "✅ 下载完成，正在安装..."
                    tvVersionWithDot?.text = "v$version"
                    versionDot?.visibility = View.GONE
                    pbVersionDownload?.progress = 100
                    pbVersionDownload?.visibility = View.GONE
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
                        pbVersionDownload?.visibility = View.GONE
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
                    pbVersionDownload?.visibility = View.GONE
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
                                name = m.optString("name", m.optString("id", "")).replace(Regex("\\s*\\(free\\)"), ""),
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
        val etCustomModel = dialogView.findViewById<EditText>(R.id.et_custom_model)
        val btnSaveCustom = dialogView.findViewById<Button>(R.id.btn_save_custom_model)

        // 合并自定义模型（pref: id 以 || 分隔，id 即名称）
        val customIds = getCustomModels()
        val allModels = models.toMutableList().apply {
            addAll(customIds.map { CloudModel(it, it, "自定义", 0, false, false) })
        }

        // Set current model in title
        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        val currentModel = allModels.find { it.id == savedModelId }
        currentModel?.let {
            tvTitle.text = "当前: ${it.name}"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        rvModels.layoutManager = LinearLayoutManager(this)
        rvModels.adapter = ModelSelectorAdapter(allModels, savedModelId) { model ->
            tvCloudModel?.text = model.name
            prefs.edit().putString(PREF_MODEL_ID, model.id).apply()
            appendLog("模型已保存: ${model.id}")
            dialog.dismiss()
        }

        btnSaveCustom.setOnClickListener {
            val id = etCustomModel.text?.toString()?.trim() ?: ""
            if (id.isNotEmpty()) {
                addCustomModel(id)
                appendLog("自定义模型已保存: $id")
                // 立即应用并关闭
                tvCloudModel?.text = id
                prefs.edit().putString(PREF_MODEL_ID, id).apply()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "请输入模型 ID", Toast.LENGTH_SHORT).show()
            }
        }

        // 关闭按钮点击
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun getCustomModels(): List<String> {
        val raw = prefs.getString("custom_models", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("||").filter { it.isNotEmpty() }
    }

    private fun addCustomModel(id: String) {
        val list = getCustomModels().toMutableList()
        list.remove(id)
        list.add(0, id)
        prefs.edit().putString("custom_models", list.take(20).joinToString("||")).apply()
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
            holder.itemView.setBackgroundColor(if (isSelected) accentColor else 0xFFFFFFFF.toInt())
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
        // 状态圆点：动态圆形drawable染色（匹配原尺寸和形状）
        if (view.id == R.id.v_status_dot) {
            try {
                val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(accent)
                    setSize((6 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt())
                }
                view.background = dotDrawable
            } catch (_: Exception) {}
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
        // Handle ImageView drawable tint
        if (view is android.widget.ImageView) {
            try {
                val drawable = view.drawable
                if (drawable != null && (drawable.constantState?.toString()?.contains("81D8D0") == true)) {
                    drawable.setTint(accent)
                    view.setImageDrawable(drawable)
                }
            } catch (_: Exception) {}
        }
        // 下载按钮：跟随主题色（backgroundTint 用 accent 而非硬编码 tiffany）
        if (view.id == R.id.btn_install_voice || view.id == R.id.btn_install_ai) {
            try {
                if (view is com.google.android.material.button.MaterialButton) {
                    if (view.background !is android.graphics.drawable.LayerDrawable) {
                        view.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
                    }
                }
            } catch (_: Exception) {}
        }
        // Handle simple View background drawable tint (non-tintable drawables)
        if (view !is android.widget.ProgressBar && view !is android.widget.TextView && view !is android.widget.RadioButton && view !is com.google.android.material.button.MaterialButton && view !is com.google.android.material.textfield.TextInputLayout) {
            try {
                val bg = view.background
                if (bg != null && bg.constantState?.toString()?.contains("81D8D0") == true) {
                    bg.setTint(accent)
                    view.background = bg
                }
            } catch (_: Exception) {}
        }
        // Handle ProgressBar progressTint
        if (view is android.widget.ProgressBar) {
            try {
                if (view.progressTintList?.defaultColor == tiffany) {
                    view.progressTintList = tintList
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

    // ======================== 新手引导横幅 ========================

    private fun checkFirstLaunchOnboarding() {
        // 新手引导已移除，隐藏横幅
        viewOnboardingBanner?.visibility = View.GONE
    }
}