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
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
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
    private var etPolishPrompt: TextInputEditText? = null
    private lateinit var etTestText: TextInputEditText
    private lateinit var tilTestText: com.google.android.material.textfield.TextInputLayout
    private var btnTestApi: MaterialButton? = null
    private var btnTestLocalAi: MaterialButton? = null
    private lateinit var tvLog: TextView
    private var isLogExpanded = true
    private lateinit var tvVersion: TextView
    private lateinit var statsManager: PolishStatsManager
    private lateinit var dictManager: PinyinDictManager

    // 用户词组库导出/导入（SAF 文件选择器）
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        exportUserPhrases(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        importUserPhrases(uri)
    }

    // === 语音与 AI 本地化 ===
    private lateinit var localModeManager: LocalModeManager
    private lateinit var modelManager: ModelManager
    private lateinit var downloadManager: ModelDownloadManager
    private var btnInstallVoice: Button? = null
    private var btnInstallAi: Button? = null
    private var isDownloading = false

    // === 语音命令词 (新顺序：智能写作、智能修改、智能润色、结束语音识别、立即发送、退出语音模式、撤销识别、清空识别) ===
    private var etCmdWriting: TextInputEditText? = null    // 智能写作
    private var etCmdCommand: TextInputEditText? = null   // 智能修改
    private var etCmdPolish: TextInputEditText? = null    // 智能润色
    private var etCmdFinish: TextInputEditText? = null    // 结束语音识别
    private var etCmdSend: TextInputEditText? = null      // 立即发送
    private var etCmdExit: TextInputEditText? = null      // 退出语音模式
    private var etCmdUndo: TextInputEditText? = null      // 撤销识别
    private var etCmdClear: TextInputEditText? = null     // 清空识别
    private var etCmdRestore: TextInputEditText? = null   // 恢复识别
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
    private lateinit var etNewsQuery: EditText
    private lateinit var tvTestStatus: TextView

    // === 标题可编辑 ===
    private var etSettingsTitle: TextInputEditText? = null
    private var tilSettingsTitle: TextInputLayout? = null

    // === 标题可编辑 ===
    private lateinit var aiSettingsHelper: VoiceAISettingsHelper

    // 词库信息（已并入运行日志开头）
    private val prefs by lazy { getSharedPreferences("cesia_settings", MODE_PRIVATE) }
    private var accentColor: Int = 0xFF81D8D0.toInt()
    // 连续点击"检查更新"且已是最新版的次数（用于"不要再拉了"提示）
    private var upToDateCheckCount = 0
    private var themeMode: Int = THEME_LIGHT

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val PREF_MODEL_ID = "model_id"
        const val DEFAULT_MODEL_ID = ""  // 默认空：需用户在设置页选择模型来源与模型
        const val PERMISSION_REQUEST_CODE = 1001
        const val TYPE_HEADER = 0
        const val TYPE_SOURCE = 1
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val PREF_GROQ_KEY = "groq_api_key"
        const val PREF_OPENROUTER_KEY = "openrouter_api_key"
        const val PREF_BRAVE_KEY = "tavily_api_key"
        const val PREF_POLISH_PROMPT = "polish_prompt"
        const val PREF_TEST_TEXT = "test_text"
        const val PREF_MODE = "run_mode"
        const val PREF_THEME_MODE = "theme_mode"
        const val PREF_SETTINGS_TITLE = "settings_title"
        const val DEFAULT_POLISH_PROMPT = "你是一个文本润色与输入排版高手。请将输入的口语文字处理为通顺的书面文字，并严格执行以下规则：\n严禁删减核心信息，严禁随意扩写。仅修正错别字、口语和语序，加入标点。只输出润色排版后的纯文本。禁止解释，禁止添加任何前缀（如\"润色后：\"）或后缀。如果用户输入的内容包含多个观点、步骤或长篇大论，请自动通过\"换行分段\"或使用\"— \"进行分点陈列。"
        const val DEFAULT_TEST_TEXT = "嗯那个键盘最下面5个按钮是亮点哦纸飞机短按发送文本长按打开剪贴板垃圾桶短按清空前面长按清空后面星星和笔分别是写作和修改短按发送命令长按打开菜单语音输入长按锁定只要结尾说命令词就有惊喜哦。"  // 初始文本，重装后恢复；测试只闪烁提示，不覆盖此内容
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
        // 设置页版本号进度条订阅下载总线，与键盘状态栏同步显示下载进度
        initDownloadProgressSync()
        // 初始化 VoiceEngine 命令词
        try {
            val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
            // 清理历史上误设为 "ok" 的命令词（恢复中文默认），避免识别 ok 后内容被清空
            val defaults = mapOf(
                "cmd_exit" to "退出", "cmd_polish" to "润色", "cmd_finish" to "结束",
                "cmd_send" to "发送", "cmd_command" to "修改",
                "cmd_undo" to "撤销", "cmd_clear" to "清空", "cmd_restore" to "恢复", "cmd_writing" to "写作"
            )
            val edit = cmdPrefs.edit()
            for ((k, def) in defaults) {
                val v = cmdPrefs.getString(k, def) ?: def
                if (v.equals("ok", ignoreCase = true)) edit.putString(k, def)
            }
            edit.apply()
            com.cesia.input.voice.VoiceEngine.updateCommandWords(
                cmdPrefs.getString("cmd_exit", "退出") ?: "退出",
                cmdPrefs.getString("cmd_polish", "润色") ?: "润色",
                cmdPrefs.getString("cmd_finish", "结束") ?: "结束",
                cmdPrefs.getString("cmd_send", "发送") ?: "发送",
                cmdPrefs.getString("cmd_command", "修改") ?: "修改"
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
        tvLog = findViewById(R.id.tv_log)
        tvVersion = findViewById(R.id.tv_version)
        etNewsQuery = findViewById(R.id.et_news_query)
        tvTestStatus = findViewById(R.id.tv_test_status)

        // 语音与 AI 本地化视图（安装按钮已移除，改由版本菜单/启动自动下载驱动）
        // 语音命令词设置 (新顺序：智能写作、智能修改、智能润色、结束语音识别、立即发送、退出语音模式)
        try {
            etCmdWriting = findViewById(R.id.et_cmd_writing)      // 智能写作
            etCmdCommand = findViewById(R.id.et_cmd_command)      // 智能修改
            etCmdPolish = findViewById(R.id.et_cmd_polish)        // 智能润色
            etCmdFinish = findViewById(R.id.et_cmd_finish)        // 结束语音识别
            etCmdSend = findViewById(R.id.et_cmd_send)            // 立即发送
            etCmdExit = findViewById(R.id.et_cmd_exit)        // 退出语音模式
            etCmdUndo = findViewById(R.id.et_cmd_undo)        // 撤销识别
            etCmdClear = findViewById(R.id.et_cmd_clear)      // 清空识别
            etCmdRestore = findViewById(R.id.et_cmd_restore)  // 恢复识别
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

    // 设置页版本号进度条订阅下载总线：键盘状态栏触发的下载（语音/词库/手机AI）也同步显示在这里
    private fun initDownloadProgressSync() {
        DownloadProgressBus.subscribe { p ->
            runOnUiThread {
                if (p.task.isNotEmpty()) {
                    llVersionContainer?.visibility = View.VISIBLE
                    pbVersionDownload?.visibility = View.VISIBLE
                    pbVersionDownload?.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
                    pbVersionDownload?.progress = p.percent.toInt()
                    tvVersion?.text = "⏳ 下载${p.task} ${String.format("%.1f", p.percent)}%"
                }
                if (p.done || p.failed) {
                    pbVersionDownload?.visibility = View.GONE
                }
            }
        }
    }

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

        etPolishPrompt?.setText(prefs.getString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT))

        // 测试文本：每次启动都强制回填默认初始样本（不持久化用户输入的润色结果，
        // 保证重装/重新进入始终回到初始状态，测试时在框内直接显示润色结果）
        etTestText.setText(DEFAULT_TEST_TEXT)

        // 加载语音命令词（新键名，兼容旧键名）
        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
        etCmdWriting?.setText(cmdPrefs.getString("cmd_writing", "写作"))
        etCmdCommand?.setText(cmdPrefs.getString("cmd_command", "修改"))
        etCmdPolish?.setText(cmdPrefs.getString("cmd_polish", "润色"))
        etCmdFinish?.setText(cmdPrefs.getString("cmd_finish", "结束"))
        etCmdSend?.setText(cmdPrefs.getString("cmd_send", "发送"))
        etCmdExit?.setText(cmdPrefs.getString("cmd_exit", "退出"))
        etCmdUndo?.setText(cmdPrefs.getString("cmd_undo", "撤销"))
        etCmdClear?.setText(cmdPrefs.getString("cmd_clear", "清空"))
        etCmdRestore?.setText(cmdPrefs.getString("cmd_restore", "恢复"))

        // 加载个性化设置
        etStatusIdle?.setText(prefs.getString("status_idle", "人工智能已就绪"))
        etSmartWritingLabel?.setText(prefs.getString("smart_writing_label", "智能写作菜单"))
        etMagicBookTitle?.setText(prefs.getString("magic_book_title", "智能修改菜单"))

        // 加载设置标题
        etSettingsTitle?.setText(prefs.getString(PREF_SETTINGS_TITLE, "Cesia AI智能输入法"))

        // 初始化云端模型显示
        refreshCloudModelText()
        // 根据当前 API URL 自动拉取模型列表（OpenRouter 远程 / Ollama 本地）
        loadModelsForCurrentUrl()

        // 更新 VoiceEngine 命令词
        VoiceEngine.updateCommandWords(
            cmdPrefs.getString("cmd_exit", "退出") ?: "退出",
            cmdPrefs.getString("cmd_polish", "润色") ?: "润色",
            cmdPrefs.getString("cmd_finish", "结束") ?: "结束",
            cmdPrefs.getString("cmd_send", "发送") ?: "发送",
            cmdPrefs.getString("cmd_command", "修改") ?: "修改",
            cmdPrefs.getString("cmd_writing", "写作") ?: "写作",
            cmdPrefs.getString("cmd_undo", "撤销") ?: "撤销",
            cmdPrefs.getString("cmd_clear", "清空") ?: "清空",
            cmdPrefs.getString("cmd_restore", "恢复") ?: "恢复"
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

        // AI模型源（旧下拉：手机AI模型 / Ollama本地模型 / OpenRouter.ai）
        tvApiUrl.setOnClickListener {
            showCustomValueDialog(
                title = "AI模型源",
                presets = listOf(
                    SRC_PHONE_AI to "手机AI模型（本地1.2G，无需联网Key）",
                    SRC_OLlama to "Ollama本地模型（192.168.123.33）",
                    SRC_OPENROUTER to "OpenRouter.ai（云端）"
                ),
                historyKey = "model_source_history",
                prefKey = "model_source",
                valueView = tvApiUrl,
                isSecret = false,
                freeUrl = "",
                onSaved = { selectModelSource(prefs.getString("model_source", "") ?: "") }
            )
        }
        tvApiKey.setOnClickListener {
            showCustomValueDialog(
                title = "AI API Key",
                presets = emptyList(),
                historyKey = "api_key_history",
                prefKey = PREF_OPENROUTER_KEY,
                valueView = tvApiKey,
                isSecret = true,
                freeUrl = "https://openrouter.ai/",
                onSaved = { testApiConnection() }
            )
        }
        tvTavilyKey.setOnClickListener {
            showCustomValueDialog(
                title = "Tavily API Key",
                presets = emptyList(),
                historyKey = "tavily_key_history",
                prefKey = PREF_BRAVE_KEY,
                valueView = tvTavilyKey,
                isSecret = true,
                freeUrl = "https://www.tavily.com/",
                onSaved = { runNewsSearch() }
            )
        }

        // 润色 Prompt - 焦点离开时自动保存
        etPolishPrompt?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) savePolishPrompt()
        }
        etTestText.setOnFocusChangeListener { _, hasFocus ->
            // 测试文本不持久化：离开焦点不保存，保持每次启动回到初始样本
        }

        // 语音命令词 - 焦点离开时自动保存（含校验）
        val cmdFields = listOf(
            etCmdWriting to "cmd_writing",
            etCmdCommand to "cmd_command",
            etCmdPolish to "cmd_polish",
            etCmdFinish to "cmd_finish",
            etCmdSend to "cmd_send",
            etCmdExit to "cmd_exit",
            etCmdUndo to "cmd_undo",
            etCmdClear to "cmd_clear",
            etCmdRestore to "cmd_restore"
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
        btnTestApi?.setOnClickListener { testApiConnection() }
        // 本地AI测试已在下载完成后自动执行，手动按钮已移除（保留引用以兼容布局）
        btnTestLocalAi?.visibility = View.GONE

        // 词库管理 - 只有下载/重新下载按钮
        // 云端模型选择 - 点击 TextView 打开选择对话框
        tvCloudModel?.setOnClickListener { openModelSelector() }

        // 版本号点击 → 弹出菜单（下载新版本 / 安装或卸载语音套件 / 卸载手机AI模型）
        tvVersion?.setOnClickListener { showVersionMenu() }
        // 版本号容器（有更新时显示）也可点击
        findViewById<LinearLayout>(R.id.ll_version_container)?.setOnClickListener { showVersionMenu() }
        findViewById<TextView>(R.id.tv_version_with_dot)?.setOnClickListener { showVersionMenu() }

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

        // 历史记录：点击直接开启本地历史记录并打开历史记录页（无菜单）
        btnHistory?.setOnClickListener {
            val historyPrefs = getSharedPreferences("cesia_polish_history", MODE_PRIVATE)
            historyPrefs.edit().putString("history_mode", "local").apply()
            appendLog("已开启本地历史记录")
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnNewsSources?.setOnClickListener { showNewsSourcePicker() }

        // 语音与 AI 本地化
        btnInstallVoice?.setOnClickListener { downloadVoiceModel() }
        btnInstallAi?.setOnClickListener { downloadAiModel() }
        btnInstallVoice?.setOnLongClickListener { showUninstallMenu(true); true }
        // 手机AI模型的长按卸载入口已移除（卸载请到版本号菜单）。重新下载是卸载后的事。

        // 用户词组库：导出/导入（接龙组词备份）
        findViewById<Button>(R.id.btn_export_phrases)?.setOnClickListener {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            exportLauncher.launch("CesiaUserPhrases_$time.json")
        }
        findViewById<Button>(R.id.btn_import_phrases)?.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    // ======================== 用户词组库导出/导入 ========================
    private fun exportUserPhrases(uri: Uri) {
        try {
            val json = getSharedPreferences("cesia_dict", MODE_PRIVATE).getString("user_phrases_json", "") ?: ""
            if (json.isEmpty()) {
                Toast.makeText(this, "词库为空，无可导出", Toast.LENGTH_SHORT).show()
                appendLog("导出词库：为空")
                return
            }
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "词库已导出", Toast.LENGTH_SHORT).show()
            appendLog("导出词库成功: $uri")
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
            appendLog("导出词库失败: ${e.message}")
        }
    }

    private fun importUserPhrases(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.use { iss ->
                iss.bufferedReader().readText()
            } ?: ""
            if (json.isEmpty()) {
                Toast.makeText(this, "文件为空", Toast.LENGTH_SHORT).show()
                return
            }
            val obj = JSONObject(json)
            val merge = JSONObject(getSharedPreferences("cesia_dict", MODE_PRIVATE).getString("user_phrases_json", "") ?: "")
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                merge.put(k, obj.getString(k))
            }
            getSharedPreferences("cesia_dict", MODE_PRIVATE).edit().putString("user_phrases_json", merge.toString()).apply()
            Toast.makeText(this, "词库已导入（合并 ${obj.length()} 条）", Toast.LENGTH_SHORT).show()
            appendLog("导入词库成功: ${obj.length()} 条")
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            appendLog("导入词库失败: ${e.message}")
        }
    }

    // ======================== API URL/Key 下拉自定义 ========================

    // 通用下拉选择对话框：预选项 + 历史记忆 + 自定义输入
    // 选中或保存后写入 prefs(prefKey) 并更新显示(valueView)，同时把值记入 historyKey 列表供下次直接选择
    private fun showCustomValueDialog(
        title: String,
        presets: List<Pair<String, String>>, // (value, displayName)
        historyKey: String,
        prefKey: String,
        valueView: TextView,
        isSecret: Boolean,
        freeUrl: String = "",
        onSaved: (() -> Unit)? = null
    ) {
        // 真实当前值（直接读 prefs，不使用 TextView 上的脱敏/友好名显示）
        val realCurrent = prefs.getString(prefKey, "") ?: ""
        // 已被用户删除的预选项不再出现
        val deletedPresets = getDeletedPresets(historyKey)
        val activePresets = presets.filter { it.first !in deletedPresets }
        val history = getHistory(historyKey)
        // 列表项 = 预选 + 历史 + 当前(若不在前两者中)；不重复
        val allItems = LinkedHashSet<String>().apply {
            addAll(activePresets.map { it.first })
            addAll(history)
            if (realCurrent.isNotEmpty()) add(realCurrent)
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
        val bannerBar = dialogView.findViewById<LinearLayout>(R.id.banner_bar)

        // banner 主题色
        val themeAccent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())
        bannerBar?.setBackgroundColor(themeAccent)

        tvTitle.text = title
        etCustom.hint = "自定义：输入后点“保存并使用”"

        val adapter = object : ArrayAdapter<String>(
            this, R.layout.item_custom_value, R.id.tv_item_text, allItems
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_custom_value, parent, false)
                val tv = view.findViewById<TextView>(R.id.tv_item_text)
                val btnEdit = view.findViewById<TextView>(R.id.tv_item_edit)
                val btnDel = view.findViewById<TextView>(R.id.tv_item_delete)
                val v = allItems[position]
                tv.text = displayOf(v)
                // 当前选中项高亮（主题色文字）
                tv.setTextColor(if (v == realCurrent) accentColor else 0xFF333333.toInt())
                btnEdit.setOnClickListener {
                    // 修改：把当前值预填到编辑框，方便改 IP 等
                    etCustom.setText(v)
                    etCustom.setSelection(v.length)
                    etCustom.requestFocus()
                }
                btnDel.setOnClickListener {
                    removeHistory(historyKey, v)
                    if (presets.any { it.first == v }) addDeletedPreset(historyKey, v)
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
            if (ok) { dialog.dismiss(); onSaved?.invoke() }
        }

        btnSave.setOnClickListener {
            val value = etCustom.text?.toString()?.trim() ?: ""
            if (value.isNotEmpty()) {
                val ok = applyValueWithCheck(prefKey, historyKey, value, valueView, isSecret, title)
                if (ok) { dialog.dismiss(); onSaved?.invoke() }
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

        dialogView.findViewById<TextView>(R.id.btn_close).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // 记录被删除的预选项（避免重开对话框时重新出现）
    private fun getDeletedPresets(historyKey: String): Set<String> {
        val raw = prefs.getString("${historyKey}_deleted", "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split("||").filter { it.isNotEmpty() }.toSet()
    }
    private fun addDeletedPreset(historyKey: String, value: String) {
        val set = getDeletedPresets(historyKey).toMutableSet()
        set.add(value)
        prefs.edit().putString("${historyKey}_deleted", set.joinToString("||")).apply()
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
            // 切换前，把“上一个用过的 API”存为 fallback（仅网络层失败时才回退）
            val oldUrl = prefs.getString(PREF_API_URL, "") ?: ""
            val oldKey = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
            val oldModel = prefs.getString(PREF_MODEL_ID, "") ?: ""
            prefs.edit().putString(prefKey, value).apply()
            addHistory(historyKey, value)
            prefs.edit().putBoolean("api_url_configured", true).apply()
            updateTestButtonStates()
            // 旧 API（若存在且与新值不同）记为备用 API
            if (oldUrl.isNotEmpty() && oldUrl != value) {
                prefs.edit().putString("fallback_api_url", oldUrl).apply()
                prefs.edit().putString("fallback_api_key", oldKey).apply()
                prefs.edit().putString("fallback_model_id", oldModel).apply()
            }
            // API 切换后，自动选中该 API 此前用过的模型
            applyModelForApi(value)
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

    private fun getActivePolishPrompt(): String {
        val saved = prefs.getString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT) ?: DEFAULT_POLISH_PROMPT
        return saved.replace("*", "—")
    }

    private fun savePolishPrompt() {
        val prompt = etPolishPrompt?.text?.toString()?.trim() ?: ""
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
            cmdPrefs.getString("cmd_writing", "写作") ?: "写作",
            cmdPrefs.getString("cmd_undo", "撤销") ?: "撤销",
            cmdPrefs.getString("cmd_clear", "清空") ?: "清空",
            cmdPrefs.getString("cmd_restore", "恢复") ?: "恢复"
        )
    }

    private fun resetPolishPrompt() {
        etPolishPrompt?.setText(DEFAULT_POLISH_PROMPT)
        prefs.edit().putString(PREF_POLISH_PROMPT, DEFAULT_POLISH_PROMPT).apply()
        etTestText.setText(DEFAULT_TEST_TEXT)
        prefs.edit().putString(PREF_TEST_TEXT, DEFAULT_TEST_TEXT).apply()
        appendLog("🔄 已重置为默认 Prompt 与测试文本")
        Toast.makeText(this, "已重置为默认", Toast.LENGTH_SHORT).show()
    }

    // ======================== 模型下载 ========================
    // 按钮即进度条：圆角，主题色为进度填充，主题色减淡 50% 为背景
    // foreground=true 时用前景覆盖层渲染进度（按钮自身背景/tint 不变 → 高度绝不会被撑大），
    // 用于测试按钮；下载按钮用 background 方式（与套件下载一致）。
    private fun applyButtonProgress(
        button: android.widget.Button?,
        percent: Int,
        text: String,
        foreground: Boolean = false
    ) {
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
        // 锁死高度：minHeight 会覆盖 layout_height，必须同时清零；并强制 layoutParams 高度固定 44dp，
        // 确保自定义背景 Drawable 不会被重新测量把按钮撑大（不论主题 minHeight 多大）
        btn.minimumHeight = 0
        val fixedPx = (44f * resources.displayMetrics.density).toInt()
        btn.layoutParams.height = fixedPx

        if (foreground) {
            // 前景覆盖：按钮自身背景/描边完全不动，仅在上层叠加进度填充 → 高度绝不变
            btn.foreground = layer
        } else {
            // 清除 backgroundTint，否则 MaterialButton 会用 tint 覆盖进度层
            if (btn is com.google.android.material.button.MaterialButton) {
                btn.backgroundTintList = null
            }
            btn.background = layer
        }
        btn.requestLayout()
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.text = text
    }

    // 润色/推理进度动画：根据输入字数估算总时长，平滑递增到 100%，
    // 到达 100% 后停留 END_HOLD_MS 再复位（让进度条看起来真实而非瞬间跳变）
    private fun startPolishProgress(
        button: android.widget.Button?,
        text: String,
        inputLen: Int,
        onFinished: () -> Unit
    ) {
        val btn = button ?: return
        val totalMs = (1500 + inputLen * 120).coerceIn(2000, 12000)
        val tickMs = 100L
        val steps = (totalMs / tickMs).toInt().coerceAtLeast(1)
        val handler = android.os.Handler(mainLooper)
        var step = 0
        btn.isEnabled = false
        val runnable = object : Runnable {
            override fun run() {
                step++
                val percent = if (step >= steps) 100 else ((step * 100 / steps) * 0.92).toInt()
                applyButtonProgress(btn, percent, text, foreground = true)
                if (step < steps) {
                    handler.postDelayed(this, tickMs)
                } else {
                    // 停在 100% 多等一会
                    handler.postDelayed({ onFinished() }, 900)
                }
            }
        }
        handler.post(runnable)
    }

    // 平滑过渡到目标进度（用于本地 AI 的真实阶段：加载->推理->完成）
    private fun animateProgressTo(button: android.widget.Button?, target: Int, text: String) {
        val btn = button ?: return
        val current = (btn.tag as? Int) ?: 0
        if (current >= target) {
            applyButtonProgress(btn, target, text, foreground = true)
            btn.tag = target
            return
        }
        val handler = android.os.Handler(mainLooper)
        var p = current
        val runnable = object : Runnable {
            override fun run() {
                p += ((target - p) / 4).coerceAtLeast(1)
                if (p >= target) p = target
                applyButtonProgress(btn, p, text, foreground = true)
                btn.tag = p
                if (p < target) handler.postDelayed(this, 60)
            }
        }
        handler.post(runnable)
    }

    // 润色完成：进度拉满到 100%，多停留一会再复位并恢复可用
    private fun finishPolishButton(button: android.widget.Button?, text: String) {
        val btn = button ?: return
        animateProgressTo(btn, 100, text)
        android.os.Handler(mainLooper).postDelayed({
            btn.tag = 0
            btn.isEnabled = true
            resetButtonBg(btn, text)
        }, 900)
    }

    // 简单的文字闪烁（替代进度条）：按钮文字在「原文字」与「提示文字」之间慢速闪烁，
    // 润色/加载完成后停止并恢复原文字。按钮高度固定不变、不会撑大。
    // 文字闪烁用的 runnable：自带 stopped 标志，
    // 即使 removeCallbacks 命中“正在执行中”的窗口（didn't cancel enqueued），
    // 下一次 run 也会因 stopped=true 而停止重投并恢复原文字，避免闪烁永不停止。
    private class FlashRunnable(
        private val btn: android.widget.Button,
        private val base: String,
        private val flashingText: String,
        private val handler: android.os.Handler
    ) : Runnable {
        @Volatile var stopped = false
        private var showFlash = false
        override fun run() {
            if (stopped) {
                btn.text = base
                return
            }
            btn.text = if (showFlash) flashingText else base
            showFlash = !showFlash
            handler.postDelayed(this, 600)
        }
    }

    private val buttonFlashRunnables = mutableMapOf<android.widget.Button, FlashRunnable>()
    private fun startButtonFlash(button: android.widget.Button?, flashingText: String) {
        val btn = button ?: return
        btn.isEnabled = false
        btn.foreground = null
        btn.minimumHeight = 0
        val fixedPx = (44f * resources.displayMetrics.density).toInt()
        btn.layoutParams.height = fixedPx
        val base = btn.text?.toString() ?: ""
        val handler = android.os.Handler(mainLooper)
        // 若已在闪烁，先停掉旧的（置 stopped 并取消挂起回调），避免多个 runnable 叠加
        buttonFlashRunnables[btn]?.let { old ->
            old.stopped = true
            handler.removeCallbacks(old)
        }
        val runnable = FlashRunnable(btn, base, flashingText, handler)
        buttonFlashRunnables[btn] = runnable
        handler.post(runnable)
    }
    private fun stopButtonFlash(button: android.widget.Button?, restoreText: String) {
        val btn = button ?: return
        buttonFlashRunnables[btn]?.let { r ->
            r.stopped = true
            android.os.Handler(mainLooper).removeCallbacks(r)
        }
        buttonFlashRunnables.remove(btn)
        btn.isEnabled = true
        btn.minimumHeight = 0
        val fixedPx = (44f * resources.displayMetrics.density).toInt()
        btn.layoutParams.height = fixedPx
        btn.text = restoreText
    }

    // 润色测试状态：文字闪烁提示“正在测试…”，完成后停止
    private var testStatusAnim: android.view.animation.AlphaAnimation? = null
    private fun startTestStatusFlash() {
        runOnUiThread {
            tvTestStatus.text = "正在测试…"
            tvTestStatus.visibility = View.VISIBLE
            testStatusAnim = android.view.animation.AlphaAnimation(1.0f, 0.2f).apply {
                duration = 600
                repeatCount = android.view.animation.Animation.INFINITE
                repeatMode = android.view.animation.Animation.REVERSE
            }
            tvTestStatus.startAnimation(testStatusAnim)
        }
    }
    private fun stopTestStatusFlash() {
        runOnUiThread {
            testStatusAnim?.let { tvTestStatus.clearAnimation() }
            testStatusAnim = null
            tvTestStatus.visibility = View.GONE
        }
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
        btn.foreground = null
        btn.minimumHeight = 0
        val fixedPx = (44f * resources.displayMetrics.density).toInt()
        btn.layoutParams.height = fixedPx
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
        val anchor = (if (isVoice) btnInstallVoice else btnInstallAi) ?: tvVersion ?: return
        val popup = android.widget.PopupMenu(this, anchor)
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
            // 删除语音模型（中英双语）
            downloadManager.deleteModel("sherpa-zipformer")
            // 删除纯中文模型（独立目录）
            downloadManager.deleteModel("zipformer-zh-2025")
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
        val zhInstalled = java.io.File(filesDir, "local_models/zipformer-zh-2025/encoder.onnx").exists()
        btnInstallVoice?.setOnClickListener { downloadVoiceModel() }
        if (voiceInstalled && dictInstalled) {
            if (zhInstalled) {
                installedButtonBg(btnInstallVoice, "语音文字套件已安装")
            } else {
                // 中英混已装，但纯中文未下：提示可单独补下
                installedButtonBg(btnInstallVoice, "语音套件已安装")
                btnInstallVoice?.setOnClickListener { downloadChineseModelOnly() }
            }
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
        refreshModelSourceSymbol()
    }

    // ======================== 模型可用性标记（与键盘状态栏本/云字联动） ========================
    private fun modelStatusPrefs() = getSharedPreferences("cesia_model_status", Context.MODE_PRIVATE)
    private fun isLocalAiReady() = modelStatusPrefs().getBoolean("local_ai_ready", false)
    private fun isCloudReady() = modelStatusPrefs().getBoolean("cloud_ready", false)
    private fun markLocalAiReady(v: Boolean) {
        modelStatusPrefs().edit().putBoolean("local_ai_ready", v).apply()
        refreshModelSourceSymbol()
    }
    private fun markCloudReady(v: Boolean) {
        modelStatusPrefs().edit().putBoolean("cloud_ready", v).apply()
        refreshModelSourceSymbol()
    }
    /** 下拉项旁显示通过符号（✓/○）：本地下载自测通过 / 云端已填凭据且测试通过 */
    private fun refreshModelSourceSymbol() {
        val src = prefs.getString("model_source", "") ?: ""
        val openrouterKey = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
        val ollamaUrl = prefs.getString("ollama_url", "") ?: ""
        val sym = when (src) {
            SRC_PHONE_AI -> if (isLocalAiReady()) "✓" else "○"
            SRC_OPENROUTER -> if (isCloudReady() && openrouterKey.isNotEmpty()) "✓" else "○"
            SRC_OLlama -> if (isCloudReady() && ollamaUrl.isNotEmpty()) "✓" else "○"
            else -> ""
        }
        tvApiUrl?.text = when (src) {
            SRC_PHONE_AI -> "手机AI模型 $sym"
            SRC_OLlama -> "Ollama本地模型 $sym"
            SRC_OPENROUTER -> "OpenRouter.ai $sym"
            else -> "（未选择模型来源）"
        }
    }

    private fun downloadVoiceModel() {
        val voiceInstalled = modelManager.getInstalledVoiceModelFile() != null
        val dictInstalled = dictManager.hasDownloadedDict()
        // 已安装时再次点击 → 先弹确认框，取消不下载，确定才重新下载
        if (voiceInstalled && dictInstalled) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("重新下载语音套件")
                .setMessage("语音文字输入套件已安装，确定要重新下载吗？")
                .setNegativeButton("取消") { _, _ -> }
                .setPositiveButton("确定") { _, _ -> doDownloadVoiceModel() }
                .show()
            return
        }
        doDownloadVoiceModel()
    }

    private fun doDownloadVoiceModel() {
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
                                // 阶段三：下载纯中文模型（独立目录，不影响中英双语套件）
                                runOnUiThread { setBothVoiceProgress(98, "正在下载纯中文模型") }
                                try {
                                    val dm = ModelDownloadManager(this@SettingsActivity)
                                    val zhResult = kotlinx.coroutines.runBlocking {
                                        dm.downloadArchive("zipformer-zh-2025") { _, percent, _, _ ->
                                            runOnUiThread {
                                                val overall = 98 + (percent * 0.02).toInt()
                                                setBothVoiceProgress(overall, "下载纯中文模型")
                                            }
                                        }
                                    }
                                    if (zhResult.isSuccess) {
                                        appendLog("✅ 纯中文模型下载完成（双击语音键可切换）")
                                        runOnUiThread { appendLog("🎉 语音套件 + 纯中文模型已全部安装完成") }
                                    } else {
                                        val msg = zhResult.exceptionOrNull()?.message ?: "未知错误"
                                        appendLog("⚠️ 纯中文模型下载失败（中英混仍可用）: $msg")
                                        runOnUiThread {
                                            Toast.makeText(this, "纯中文模型下载失败，可单独重试", Toast.LENGTH_LONG).show()
                                            // 主按钮提示可重试纯中文模型
                                            btnInstallVoice?.text = "纯中文模型未下载(点此重试)"
                                            btnInstallVoice?.setOnClickListener { downloadChineseModelOnly() }
                                        }
                                    }
                                } catch (ze: Exception) {
                                    appendLog("⚠️ 纯中文模型下载异常（中英混仍可用）: ${ze.message}")
                                    runOnUiThread {
                                        Toast.makeText(this, "纯中文模型下载异常，可单独重试", Toast.LENGTH_LONG).show()
                                        btnInstallVoice?.text = "纯中文模型未下载(点此重试)"
                                        btnInstallVoice?.setOnClickListener { downloadChineseModelOnly() }
                                    }
                                }
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

    /**
     * 单独下载纯中文模型（双击语音键切换用，独立目录，不影响已装的中英双语套件）
     * 用于阶段三失败后的重试入口
     */
    private fun downloadChineseModelOnly() {
        val dm = ModelDownloadManager(this)
        btnInstallVoice?.isEnabled = false
        btnInstallVoice?.text = "正在下载纯中文模型..."
        appendLog("⬇ 单独下载纯中文模型（双击语音键切换用）")
        Thread {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    dm.downloadArchive("zipformer-zh-2025") { _, percent, _, _ ->
                        runOnUiThread { setBothVoiceProgress(98 + (percent * 0.02).toInt(), "下载纯中文模型") }
                    }
                }
                runOnUiThread {
                    btnInstallVoice?.isEnabled = true
                    if (result.isSuccess) {
                        appendLog("✅ 纯中文模型下载完成（双击语音键可切换）")
                        Toast.makeText(this, "纯中文模型已安装", Toast.LENGTH_SHORT).show()
                        refreshVoiceInstallState()
                    } else {
                        appendLog("⚠️ 纯中文模型下载失败: ${result.exceptionOrNull()?.message}")
                        Toast.makeText(this, "纯中文模型下载失败，可再次点击重试", Toast.LENGTH_LONG).show()
                        btnInstallVoice?.text = "纯中文模型未下载(点此重试)"
                        btnInstallVoice?.setOnClickListener { downloadChineseModelOnly() }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnInstallVoice?.isEnabled = true
                    appendLog("⚠️ 纯中文模型下载异常: ${e.message}")
                    btnInstallVoice?.text = "纯中文模型未下载(点此重试)"
                    btnInstallVoice?.setOnClickListener { downloadChineseModelOnly() }
                }
            }
        }.start()
    }

    private fun downloadAiModel() {
        // 已安装时点击 → 直接跑自测点亮，不重新下载（重新下载是卸载后的事）
        if (modelManager.hasAiModel()) {
            testLocalAiConnection()
            return
        }
        doDownloadAiModel()
    }

    private fun doDownloadAiModel() {
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
                            // 复用版本号进度条显示手机AI模型下载进度，并与键盘状态栏同步
                            llVersionContainer?.visibility = View.VISIBLE
                            pbVersionDownload?.visibility = View.VISIBLE
                            pbVersionDownload?.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
                            pbVersionDownload?.progress = percent.toInt()
                            tvVersion?.text = "⏳ 下载手机AI模型 ${String.format("%.1f", percent)}%"
                            DownloadProgressBus.emit("手机AI模型", percent)
                        }
                    }
                }
                runOnUiThread {
                    btnInstallAi?.isEnabled = true
                    getSharedPreferences("cesia_download", Context.MODE_PRIVATE).edit()
                        .putBoolean("ai_downloading", false).commit()
                    pbVersionDownload?.visibility = View.GONE
                    if (result.isSuccess) {
                        appendLog("✅ AI 模型下载完成: ${result.getOrNull()?.absolutePath}")
                        Toast.makeText(this, "AI 模型下载完成，正在自动测试...", Toast.LENGTH_SHORT).show()
                        refreshAiInstallState()
                        // 下载完成自动跑自测（无需手动按钮）
                        testLocalAiConnection()
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
            // 闪烁结束后恢复为原本的字体颜色（深色，清晰可读），而非定格主题色
            val originalColor = (ed.textColors?.defaultColor ?: 0xFF222222.toInt())
            // 闪烁：主题色 <-> 白色 交替几次后恢复原本颜色
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
                        ed.setTextColor(originalColor)
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

        btnTestApi?.isEnabled = false
        // 互斥：开始云端润色 → 本地按钮变灰不可点，直到润色完成恢复
        btnTestLocalAi?.isEnabled = false
        btnTestLocalAi?.alpha = 0.4f
        appendLog("🔄 正在润色...")
        startTestStatusFlash()
        Thread {
            try {
                val apiUrl = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
                val isOr = apiUrl.contains("openrouter.ai") || apiUrl.contains("api.cesia.cc")
                val isOpenAi = apiUrl.contains("/v1/chat/completions") || apiUrl.contains("/v1/")

                val request = if (isOr) {
                    // OpenRouter 格式：使用用户自定义 prompt
                    val customPrompt = getActivePolishPrompt()
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
                } else if (isOpenAi) {
                    // OpenAI 兼容端点（Ollama /v1/chat/completions、vLLM、LM Studio 等）
                    val customPrompt = getActivePolishPrompt()
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
                    val reqBuilder = Request.Builder()
                        .url(apiUrl)
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Content-Type", "application/json")
                    val apiKey = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
                    if (apiKey.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                    reqBuilder.build()
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
                            val polished = if (isOr || isOpenAi) {
                                // OpenRouter / OpenAI 兼容响应格式
                                val choices = respJson.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val msg = choices.getJSONObject(0).optJSONObject("message")
                                    val c = msg?.optString("content", "")?.trim() ?: ""
                                    if (c.isNotEmpty()) c
                                    else (msg?.optString("reasoning", "")?.trim()
                                        ?: msg?.optString("reasoning_content", "")?.trim() ?: "")
                                } else ""
                            } else {
                                // 自定义 API 响应格式
                                respJson.optString("polished_text", "")
                            }
                            if (polished.isNotEmpty() && polished != inputText) {
                                // 测试时把润色结果显示到文本框，便于验证 API 是否工作
                                etTestText.setText(polished)
                                showTestResult(true, "润色成功")
                                appendLog("润色成功: $polished")
                                // 云端测试通过 → 标记云端可用（键盘云字点亮）
                                markCloudReady(true)
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
                    btnTestApi?.isEnabled = true
                    stopButtonFlash(btnTestApi, "云端AI润色")
                    // 润色完成 → 恢复对方按钮状态
                    updateTestButtonStates()
                    stopTestStatusFlash()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showTestResult(false, "网络错误: ${e.message ?: "未知"}")
                    appendLog("API 测试异常: ${e.message}")
                    btnTestApi?.isEnabled = true
                    stopButtonFlash(btnTestApi, "云端AI润色")
                    // 润色完成 → 恢复对方按钮状态
                    updateTestButtonStates()
                    stopTestStatusFlash()
                }
            }
        }.start()
    }

    // ======================== 本地 AI 测试 ========================

    private fun testLocalAiConnection() {
        // 文本框为空时填入默认测试文本，确保选中手机AI模型后能自动跑润色测试
        if (etTestText.text?.toString()?.trim().isNullOrEmpty()) {
            etTestText.setText("今天天气真好，我们一起去公园散步吧。")
        }
        val inputText = etTestText.text?.toString()?.trim() ?: ""

        btnTestLocalAi?.isEnabled = false
        // 互斥：开始本地润色 → 云端按钮变灰不可点，直到润色完成恢复
        btnTestApi?.isEnabled = false
        btnTestApi?.alpha = 0.4f
        startButtonFlash(btnTestLocalAi, "加载中")
        appendLog("🔄 正在加载模型并润色...")
        startTestStatusFlash()

        Thread {
            try {
                val modelManager = com.cesia.input.model.ModelManager(this@SettingsActivity)
                val modelFile = modelManager.getInstalledAiModelFile()

                if (modelFile == null || !modelFile.exists()) {
                    runOnUiThread {
                        showTestResult(false, "未安装 AI 模型")
                        appendLog("本地 AI 失败: 模型未安装")
                        stopButtonFlash(btnTestLocalAi, "本地AI润色")
                        stopTestStatusFlash()
                        // 润色完成 → 恢复对方按钮状态
                        updateTestButtonStates()
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
                        stopButtonFlash(btnTestLocalAi, "本地AI润色")
                        stopTestStatusFlash()
                        // 润色完成 → 恢复对方按钮状态
                        updateTestButtonStates()
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
                        showTestResult(false, "推理超时（60s）")
                        appendLog("推理超时（${inferTime}ms），请尝试更短的文本或更小的模型")
                    } else if (result.isNotEmpty() && result != inputText) {
                        // 测试时把润色结果显示到文本框，便于验证本地 AI 是否工作
                        etTestText.setText(result)
                        showTestResult(true, "本地 AI 润色成功 (${inferTime}ms)")
                        appendLog("润色成功 (${inferTime}ms): ${result.take(50)}...")
                        // 下载后自动自测通过 → 标记本地AI可用（键盘本字点亮）
                        markLocalAiReady(true)
                    } else {
                        showTestResult(true, "润色结果为空")
                        appendLog("润色结果为空 (${inferTime}ms)")
                    }
                    stopButtonFlash(btnTestLocalAi, "本地AI润色")
                    stopTestStatusFlash()
                    // 润色完成 → 恢复对方按钮状态
                    updateTestButtonStates()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "本地 AI 测试异常", e)
                runOnUiThread {
                    showTestResult(false, "异常: ${e.message ?: "未知"}")
                    appendLog("本地 AI 异常: ${e.message}")
                    stopButtonFlash(btnTestLocalAi, "本地AI润色")
                    stopTestStatusFlash()
                    // 润色完成 → 恢复对方按钮状态
                    updateTestButtonStates()
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
        // 本地AI测试改为下载后自动自测，手动按钮隐藏
        btnTestLocalAi?.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        refreshDictInfo()
        // 检测上次下载是否被系统终止（Activity 后台时被 kill）
        checkInterruptedDownloads()
        // 新手引导横幅保持常驻显示（由 checkFirstLaunchOnboarding 统一控制），不在 onResume 隐藏
    }

    /**
     * 远程模型列表拉取失败时的兜底：读取内置的 openrouter_models.json（assets）
     */
    private fun loadBuiltinOpenRouterModels() {
        try {
            val jsonStr = assets.open("openrouter_models.json").bufferedReader().use { it.readText() }
            val json = org.json.JSONObject(jsonStr)
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
            refreshCloudModelText()
        } catch (e: Exception) {
            runOnUiThread { tvCloudModel?.text = "⚠️ 内置模型列表读取失败" }
        }
    }

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
        // 首次/每次点击：弹出“如何使用历史记录”选择菜单（默认不记录）
        val historyPrefs = getSharedPreferences("cesia_polish_history", MODE_PRIVATE)
        val currentMode = historyPrefs.getString("history_mode", "off") ?: "off"

        // AI 选项分析条件：已装千问(MNN)模型 或 云端 API 已配置
        val aiAvailable = modelManager.hasAiModel() ||
                prefs.getBoolean("api_url_configured", false)

        val MODE_LOCAL = "local"
        val MODE_AI = "ai"
        val MODE_OFF = "off"
        val modes = arrayOf(MODE_LOCAL, MODE_AI, MODE_OFF)
        val labels = arrayOf("仅本地开启历史记录功能", "允许 AI 分析历史记录（需 AI 测试通过）", "关闭历史记录功能（所有记录将清空）")
        val checkedIndex = when (currentMode) {
            MODE_LOCAL -> 0
            MODE_AI -> 1
            else -> 2
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("输入历史记录")
            .setSingleChoiceItems(labels, checkedIndex) { _, which ->
                // 禁用项（AI 未就绪）不可选
                if (which == 1 && !aiAvailable) return@setSingleChoiceItems
            }
            .setPositiveButton("确定") { d, _ ->
                val lv = (d as AlertDialog).listView
                val which = lv.checkedItemPosition
                if (which < 0) return@setPositiveButton
                val mode = modes[which]
                if (mode == MODE_AI && !aiAvailable) {
                    Toast.makeText(this, "AI 模型未就绪，无法选择此项", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                historyPrefs.edit().putString("history_mode", mode).apply()
                if (mode == MODE_OFF) {
                    // 关闭即清空所有润色记录
                    com.cesia.input.stats.PolishStatsManager(this).clearRecords()
                    Toast.makeText(this, "历史记录已关闭，记录已清空", Toast.LENGTH_SHORT).show()
                } else {
                    // 复用现有润色历史页面
                    startActivity(Intent(this, HistoryActivity::class.java))
                }
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        // 禁用 AI 选项（未就绪时灰显）
        if (!aiAvailable) {
            val lv = dialog.listView
            val item = lv.getChildAt(1)
            item?.isEnabled = false
            (item as? TextView)?.isEnabled = false
            (item as? TextView)?.alpha = 0.4f
        }
    }

    private fun showNewsSourcePicker() {
        val intent = Intent(this, NewsSourceActivity::class.java)
        startActivity(intent)
    }

    /**
     * 智能写作来源：用已填的 Tavily Key 自动搜索新闻框中的查询词（“今天的中国新闻”）。
     * 失败 → 文本框提示“API KEY无效”；成功 → 文本框显示搜索结果。
     */
    private fun runNewsSearch() {
        val rawQuery = etNewsQuery.text?.toString()?.trim()
            .takeIf { !it.isNullOrEmpty() } ?: "今天的中国新闻"
        // 偏置中文结果：要求 Tavily 用中文返回摘要
        val query = "$rawQuery（请使用中文回答，返回中文新闻摘要）"
        val key = prefs.getString(PREF_BRAVE_KEY, "") ?: ""
        if (key.isEmpty()) {
            etNewsQuery.setText("请先填写 Tavily API KEY")
            return
        }
        etNewsQuery.setText("正在搜索：$rawQuery ...")
        appendLog("🔍 智能写作来源新闻搜索: $rawQuery")
        Thread {
            try {
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
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .addHeader("Authorization", "Bearer $key")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                runOnUiThread {
                    if (response.code == 200) {
                        val json = response.body?.string() ?: ""
                        val parsed = parseNewsResult(json)
                        if (parsed.isNotEmpty()) {
                            appendLog("✅ 新闻搜索成功")
                            etNewsQuery.setText(parsed)
                        } else {
                            etNewsQuery.setText("搜索结果为空，请检查 API KEY 或网络")
                        }
                    } else {
                        etNewsQuery.setText("API KEY无效（HTTP ${response.code}）")
                        appendLog("❌ 新闻搜索失败 HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    etNewsQuery.setText("搜索失败：${e.message ?: "未知错误"}")
                    appendLog("❌ 新闻搜索异常: ${e.message}")
                }
            }
        }.start()
    }

    private fun parseNewsResult(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            val answer = obj.optString("answer", "").trim()
            val results = obj.optJSONArray("results")
            val sb = StringBuilder()
            // 优先中文摘要（answer 跟随查询语言，已偏置中文）
            if (answer.isNotEmpty()) sb.append("【摘要】$answer\n\n")
            if (results != null) {
                var added = 0
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val title = r.optString("title", "").trim()
                    val content = r.optString("content", "").trim()
                    // 只保留正文以中文为主的新闻，过滤英文正文，保证框内全中文
                    if (content.isNotEmpty() && isMostlyChinese(content)) {
                        sb.append("• $title\n$content\n\n")
                        added++
                    }
                    if (added >= 5) break
                }
                if (added == 0 && answer.isEmpty()) {
                    return "未找到中文新闻源，请尝试更换关键词（如“国内科技新闻”）"
                }
            } else if (answer.isEmpty()) {
                return "搜索结果为空，请检查 API KEY 或网络"
            }
            sb.toString().trim()
        } catch (_: Exception) { "" }
    }

    // 判断文本是否以中文为主（中文字符占比 > 50%）
    private fun isMostlyChinese(text: String): Boolean {
        if (text.isEmpty()) return false
        val cn = text.count { c -> c.code in 0x4E00..0x9FFF }
        return cn * 2 > text.length
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

    /**
     * 版本号点击弹出的菜单：
     * - 下载最新版本输入法（始终显示）
     * - 安装/卸载语音文字套件（按当前是否已装动态显示文案）
     * - 卸载手机AI模型（仅已装时显示）
     */
    private fun showVersionMenu() {
        val voiceInstalled = modelManager.getInstalledVoiceModelFile() != null
                && dictManager.hasDownloadedDict()
        val aiInstalled = modelManager.hasAiModel()

        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add("下载最新版本输入法" to { checkForUpdates() })
        if (voiceInstalled) {
            items.add("卸载语音文字套件" to { uninstallVoiceSuite() })
        } else {
            items.add("安装语音文字输入套件" to { downloadVoiceModel() })
        }
        if (aiInstalled) {
            items.add("卸载手机AI模型" to { uninstallAiModel() })
        }

        // 自定义 view：主题色 banner + 标题 + X 关闭，下列表项
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val accent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())

        val banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(accent)
            setPadding((16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (48 * resources.displayMetrics.density).toInt())
        }
        val titleTv = TextView(ctx).apply {
            text = "版本 v${BuildConfig.VERSION_NAME}"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeTv = TextView(ctx).apply {
            text = "⨯"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            val sz = (48 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        closeTv.isClickable = true
        closeTv.isFocusable = true
        val out = android.util.TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            closeTv.setBackgroundResource(out.resourceId)
        }
        banner.addView(titleTv)
        banner.addView(closeTv)

        val listView = ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
        }
        val labels = items.map { it.first }
        listView.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, labels)

        root.addView(banner)
        root.addView(listView)

        val dialog = AlertDialog.Builder(ctx).setView(root).setCancelable(true).create()
        closeTv.setOnClickListener { dialog.dismiss() }
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            items[position].second.invoke()
        }
        dialog.show()
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
                // 远程拉取失败（如国内访问 github.io 超时）→ 用内置模型列表兜底
                loadBuiltinOpenRouterModels()
                runOnUiThread {
                    tvCloudModel?.text = "⚠️ 远程列表加载失败，已用内置列表"
                    updateModelSelectorContent()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    // HTTP 错误 → 用内置列表兜底
                    loadBuiltinOpenRouterModels()
                    runOnUiThread {
                        tvCloudModel?.text = "⚠️ 加载失败：HTTP ${response.code}，已用内置列表"
                        updateModelSelectorContent()
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
                    refreshCloudModelText()
                } catch (e: Exception) {
                    runOnUiThread {
                        tvCloudModel?.text = "⚠️ 加载失败"
                    }
                }
            }
        })
    }

    /** 根据当前 API URL 自动拉取模型列表：OpenRouter 走远程，Ollama 走本地 /api/tags */
    // 模型来源联动：选来源 → 自动弹模型 → 选模型后自动弹 Key
    private var autoOpenKeyAfterModel = false
    private val SRC_PHONE_AI = "phone_ai"
    private val SRC_OLlama = "ollama_local"
    private val SRC_OPENROUTER = "openrouter_ai"
    private val OLLAMA_URL = "http://192.168.123.33:11434/v1/chat/completions"

    private fun showModelSourcePicker() {
        val sources = arrayOf(
            "手机AI模型（本地1.2G，无需联网Key）",
            "Ollama本地模型（192.168.123.33）",
            "OpenRouter.ai（云端）"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择模型来源")
            .setItems(sources) { _, which ->
                when (which) {
                    0 -> selectModelSource(SRC_PHONE_AI)
                    1 -> selectModelSource(SRC_OLlama)
                    2 -> selectModelSource(SRC_OPENROUTER)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Ollama 地址可配置（不再写死 IP，避免局域网 IP 变化后连不上） */
    private fun promptOllamaAddress() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ollama_address, null)
        val et = dialogView.findViewById<android.widget.EditText>(R.id.et_ollama_url)
        et.setText(prefs.getString("ollama_url", OLLAMA_URL))
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialogView.findViewById<android.view.View>(R.id.btn_ollama_dialog_close)
            .setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<android.view.View>(R.id.btn_ollama_cancel)
            .setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<android.view.View>(R.id.btn_ollama_ok)
            .setOnClickListener {
                val addr = et.text.toString().trim()
                if (addr.isNotEmpty()) {
                    prefs.edit().putString("ollama_url", addr).apply()
                    prefs.edit().putString(PREF_API_URL, addr).apply()
                    tvApiUrl.text = "ollama本地模型"
                    appendLog("模型来源：Ollama本地模型($addr)")
                    autoOpenKeyAfterModel = true
                    dialog.dismiss()
                    loadModelsForCurrentUrl()
                    tvCloudModel?.performClick()
                }
            }
        dialog.show()
    }

    private fun selectModelSource(src: String) {
        prefs.edit().putBoolean("api_url_configured", true).apply()
        when (src) {
            SRC_PHONE_AI -> {
                tvApiUrl.text = "手机AI模型"
                prefs.edit().putString(PREF_API_URL, "phone_ai_local").apply()
                appendLog("模型来源：手机AI模型（自动下载并测试）")
                // 手机AI：唯一模型，无需 Key，直接下载并自动测试
                downloadAiModel()
            }
            SRC_OLlama -> {
                promptOllamaAddress()
            }
            SRC_OPENROUTER -> {
                tvApiUrl.text = "openrouter.ai"
                prefs.edit().putString(PREF_API_URL, DEFAULT_API_URL).apply()
                appendLog("模型来源：OpenRouter.ai")
                autoOpenKeyAfterModel = true
                loadModelsForCurrentUrl()
                tvCloudModel?.performClick()
            }
        }
    }

    private fun loadModelsForCurrentUrl() {
        val apiUrl = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        val isOr = apiUrl.contains("openrouter.ai") || apiUrl.contains("api.cesia.cc")
        if (isOr) {
            loadOpenRouterFreeModels()
        } else {
            loadOllamaModels(apiUrl)
        }
    }

    /** 从 Ollama 的 /api/tags 拉取本地模型列表 */
    private fun loadOllamaModels(apiUrl: String) {
        // 提取 host:port（支持 http://host:port 或 http://ip:port）
        val regex = Regex("""https?://([^/]+)""")
        val host = regex.find(apiUrl)?.groupValues?.get(1) ?: run {
            runOnUiThread { tvCloudModel?.text = "⚠️ 无法解析 Ollama 地址" }
            return
        }
        val tagsUrl = "http://$host/api/tags"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(tagsUrl).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    tvCloudModel?.text = "⚠️ Ollama 连接失败：${e.message}"
                    updateModelSelectorContent()
                }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        tvCloudModel?.text = "⚠️ Ollama HTTP ${response.code}"
                        updateModelSelectorContent()
                    }
                    return
                }
                val body = response.body?.string() ?: return
                try {
                    val json = org.json.JSONObject(body)
                    val models = json.getJSONArray("models")
                    val list = mutableListOf<CloudModel>()
                    for (i in 0 until models.length()) {
                        val name = models.getJSONObject(i).optString("name", "")
                        if (name.isNotEmpty()) {
                            list.add(CloudModel(id = name, name = name, provider = "Ollama", contextLength = 0, hasTools = false, hasVision = false))
                        }
                    }
                    cloudModelList = list
                    refreshCloudModelText()
                } catch (e: Exception) {
                    runOnUiThread {
                        tvCloudModel?.text = "⚠️ Ollama 解析失败"
                        updateModelSelectorContent()
                    }
                }
            }
        })
    }

    /** 刷新主界面模型名显示：优先远程列表，回退自定义列表，再回退直接显示 id */
    private fun refreshCloudModelText() {
        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        val fromList = cloudModelList?.find { it.id == savedModelId }
        val name = fromList?.name
            ?: getCustomModels().find { it == savedModelId }
            ?: savedModelId
        runOnUiThread { tvCloudModel?.text = name }
        updateModelSelectorContent()
    }

    /** 点击模型菜单：先清空旧列表并展示“正在读取”，异步加载完成后再刷新 */
    private fun openModelSelector() {
        cloudModelList = null
        tvCloudModel?.text = "⏳ 读取中..."
        showModelSelectorDialog(emptyList(), loading = true)
        loadModelsForCurrentUrl()
    }

    /** 刷新模型选择对话框内容（加载完成或失败时调用） */
    private fun updateModelSelectorContent() {
        val list = cloudModelList
        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        runOnUiThread {
            if (list.isNullOrEmpty()) {
                modelSelectorAdapter?.updateModels(mutableListOf(
                    CloudModel("__loading__", "⏳ 暂无可读模型，请检查网络或地址", "提示", 0, false, false)))
                modelSelectorRv?.let { resizeModelList(it, 1) }
                modelSelectorTitle?.text = "⏳ 正在读取模型列表..."
            } else {
                modelSelectorAdapter?.updateModels(list)
                modelSelectorRv?.let { resizeModelList(it, list.size) }
                val cur = list.find { it.id == savedModelId }
                modelSelectorTitle?.text = if (cur != null) "当前: ${cur.name}" else "模型列表（${list.size} 个）"
            }
        }
    }

    /** 根据 item 数自适应列表高度（封顶 400dp，至少 1 项） */
    private fun resizeModelList(rv: androidx.recyclerview.widget.RecyclerView, count: Int) {
        val itemH = (56 * resources.displayMetrics.density).toInt()
        val desired = maxOf(count, 1) * itemH
        val maxH = (400 * resources.displayMetrics.density).toInt()
        rv.layoutParams.height = minOf(desired, maxH)
        rv.requestLayout()
    }

    private var modelSelectorAdapter: ModelSelectorAdapter? = null
    private var modelSelectorDialog: AlertDialog? = null
    private var modelSelectorRv: androidx.recyclerview.widget.RecyclerView? = null
    private var modelSelectorTitle: android.widget.TextView? = null

    private fun showModelSelectorDialog(models: List<CloudModel>, loading: Boolean = false) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_selector, null)
        val rvModels = dialogView.findViewById<RecyclerView>(R.id.rv_model_list)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_model_dialog_title)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_model_dialog_close)
        val etCustomModel = dialogView.findViewById<EditText>(R.id.et_custom_model)
        val btnSaveCustom = dialogView.findViewById<Button>(R.id.btn_save_custom_model)
        val bannerBar = dialogView.findViewById<LinearLayout>(R.id.banner_bar)
        val themeAccent = getSharedPreferences("cesia_settings", MODE_PRIVATE)
            .getInt("theme_accent", 0xFF81D8D0.toInt())
        bannerBar?.setBackgroundColor(themeAccent)

        // 合并自定义模型（pref: id 以 || 分隔，id 即名称）
        val customIds = getCustomModels()
        val allModels = models.toMutableList().apply {
            addAll(customIds.map { CloudModel(it, it, "自定义", 0, false, false) })
        }

        // Set current model in title
        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        val currentModel = allModels.find { it.id == savedModelId }
        if (loading) {
            tvTitle.text = "⏳ 正在读取模型列表..."
        } else {
            currentModel?.let {
                tvTitle.text = "当前: ${it.name}"
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        rvModels.layoutManager = LinearLayoutManager(this)
        val adapter = ModelSelectorAdapter(allModels, savedModelId,
            onModelClick = { model ->
                if (model.id != "__loading__") {
                    tvCloudModel?.text = model.name
                    prefs.edit().putString(PREF_MODEL_ID, model.id).apply()
                    // 记录 当前API -> 该模型 的联动关系
                    val curApi = prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
                    saveApiModelMapping(curApi, model.id)
                    appendLog("模型已保存: ${model.id}")
                    dialog.dismiss()
                    // 模型来源联动：选模型后自动弹 Key
                    if (autoOpenKeyAfterModel) {
                        autoOpenKeyAfterModel = false
                        tvApiKey?.performClick()
                    }
                }
            },
            onDeleteClick = { model ->
                removeCustomModel(model.id)
                allModels.remove(model)
                (rvModels.adapter as ModelSelectorAdapter).notifyDataSetChanged()
                appendLog("已删除自定义模型: ${model.id}")
                if (model.id == savedModelId) {
                    tvTitle.text = "当前: (未选择)"
                }
            }
        )
        rvModels.adapter = adapter
        modelSelectorAdapter = adapter
        modelSelectorDialog = dialog
        modelSelectorRv = rvModels
        modelSelectorTitle = tvTitle

        // 自适应高度：按 item 数估算，封顶 400dp，至少 1 项高度避免空白
        rvModels.post {
            resizeModelList(rvModels, allModels.size)
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

    /** API↔模型 联动记忆：记录每个 API URL 上次选的模型。格式 url<<<modelId 以 || 分隔 */
    private fun saveApiModelMapping(apiUrl: String, modelId: String) {
        val map = getApiModelMap().toMutableMap()
        map[apiUrl] = modelId
        prefs.edit().putString("api_model_map", map.entries.joinToString("||") { "${it.key}<<<${it.value}" }).apply()
    }

    private fun getApiModelMap(): Map<String, String> {
        val raw = prefs.getString("api_model_map", "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        return raw.split("||").mapNotNull { entry ->
            val parts = entry.split("<<<", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    /** 切换 API 时，自动选中该 API 此前用过的模型（若有） */
    private fun applyModelForApi(apiUrl: String) {
        val modelId = getApiModelMap()[apiUrl] ?: return
        prefs.edit().putString(PREF_MODEL_ID, modelId).apply()
        refreshCloudModelText()
        // 输入法会在下次唤起时通过 loadSettings 读取 PREF_MODEL_ID 自动生效
    }

    private fun removeCustomModel(id: String) {
        val list = getCustomModels().toMutableList()
        list.remove(id)
        prefs.edit().putString("custom_models", list.joinToString("||")).apply()
        // 若删除的是当前选中模型，清空选择
        val saved = prefs.getString(PREF_MODEL_ID, "") ?: ""
        if (saved == id) {
            prefs.edit().remove(PREF_MODEL_ID).apply()
            refreshCloudModelText()
        }
    }

    private inner class ModelSelectorAdapter(
        private val models: MutableList<CloudModel>,
        private val selectedModelId: String,
        private val onModelClick: (CloudModel) -> Unit,
        private val onDeleteClick: (CloudModel) -> Unit
    ) : RecyclerView.Adapter<ModelSelectorAdapter.ModelViewHolder>() {

        inner class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_model_name)
            val tvProvider: TextView = view.findViewById(R.id.tv_model_provider)
            val btnDelete: TextView = view.findViewById(R.id.btn_delete_custom)
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
            // 仅自定义模型显示删除按钮
            if (model.provider == "自定义") {
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnDelete.setOnClickListener {
                    onDeleteClick(model)
                }
            } else {
                holder.btnDelete.visibility = View.GONE
            }
        }

        override fun getItemCount() = models.size

        fun updateModels(newList: List<CloudModel>) {
            models.clear()
            models.addAll(newList)
            notifyDataSetChanged()
        }
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
        if (view is com.google.android.material.button.MaterialButton) {
            try {
                if (view.background !is android.graphics.drawable.LayerDrawable) {
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
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
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            drawable.setPadding(dpToPx(12), dpToPx(8), dpToPx(32), dpToPx(8)) // setPadding 仅 API 29+
        }
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