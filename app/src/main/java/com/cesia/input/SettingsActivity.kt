package com.cesia.input

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.cesia.input.stats.PolishStatsManager
import com.cesia.input.engine.PinyinDictManager
import com.cesia.input.recognizer.WhisperRecognizer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Cesia 输入法设置页面
 * 配置润色 API、测试连接、OTA 更新、词库管理、主题切换、语音设置
 */
class SettingsActivity : AppCompatActivity() {

    // ── API 设置 ──
    private lateinit var etApiUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etModelId: TextInputEditText
    private lateinit var etTestText: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTestApi: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    // ── 版本 ──
    private lateinit var tvVersion: TextView

    // ── 统计 ──
    private var tvStatVoiceTime: TextView? = null
    private var tvStatSavedTime: TextView? = null
    private var tvStatVoiceSpeed: TextView? = null
    private lateinit var tvStatInputChars: TextView
    private lateinit var tvStatOutputChars: TextView
    private lateinit var tvStatCount: TextView
    private lateinit var btnHistory: Button
    private lateinit var statsManager: PolishStatsManager

    // ── 主题 ──
    private lateinit var btnThemeToggle: Button
    private lateinit var tvThemeLabel: TextView

    // ── 词库管理 ──
    private lateinit var btnDownloadDict: Button
    private lateinit var btnImportDict: Button
    private lateinit var btnExportDict: Button
    private lateinit var btnCloudBackup: Button
    private lateinit var tvDictInfo: TextView
    private lateinit var spinnerDictSource: Spinner
    private lateinit var tvDictDesc: TextView
    private var etCustomDictUrl: TextInputEditText? = null
    private lateinit var dictManager: PinyinDictManager

    // ── 语音设置 ──
    private lateinit var spinnerVoiceLanguage: Spinner
    private lateinit var spinnerVoiceEngine: Spinner
    private lateinit var tvVoiceEngineDesc: TextView
    private var etWhisperApiKey: TextInputEditText? = null
    private var etWhisperModel: TextInputEditText? = null
    private var spinnerWhisperMode: Spinner? = null
    private var tvWhisperStatus: TextView? = null
    private var btnTestWhisper: Button? = null

    // ── 检查更新 ──
    private lateinit var btnCheckUpdate: Button
    private lateinit var vUpdateDot: View

    private val prefs by lazy { getSharedPreferences("cesia_settings", MODE_PRIVATE) }

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val PREF_MODEL_ID = "model_id"
        const val DEFAULT_MODEL_ID = "minimax/minimax-m2.5:free"
        const val PERMISSION_REQUEST_CODE = 1001
        const val PREF_THEME_MODE = "theme_mode"
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val IMPORT_DICT_REQUEST = 2001
        const val IMPORT_PHRASES_REQUEST = 2002

        // 语音引擎选项
        const val VOICE_ENGINE_WHISPER = "whisper"
        const val VOICE_ENGINE_GOOGLE = "google"
        const val VOICE_ENGINE_AUTO = "auto"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        setTitle("Cesia 输入法设置")

        initViews()
        statsManager = PolishStatsManager(this)
        dictManager = PinyinDictManager(this)
        loadSettings()
        setupListeners()
        showVersion()
        refreshStats()
        refreshDictInfo()
        updateThemeUI()
        setupDictSpinner()
        setupVoiceSpinners()

        checkAndRequestPermission()
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
        // API 设置
        etApiUrl = findViewById(R.id.et_api_url)
        etApiKey = findViewById(R.id.et_openrouter_key)
        etModelId = findViewById(R.id.et_model_id)
        etTestText = findViewById(R.id.et_test_text)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnTestApi = findViewById(R.id.btn_test_api)
        tvStatus = findViewById(R.id.tv_api_status)
        tvLog = findViewById(R.id.tv_log)
        tvVersion = findViewById(R.id.tv_version)

        // 统计
        try {
            tvStatVoiceTime = findViewById(R.id.tv_stat_voice_time)
            tvStatSavedTime = findViewById(R.id.tv_stat_saved_time)
            tvStatVoiceSpeed = findViewById(R.id.tv_stat_voice_speed)
        } catch (_: Exception) {}
        tvStatInputChars = findViewById(R.id.tv_stat_input_chars)
        tvStatOutputChars = findViewById(R.id.tv_stat_output_chars)
        tvStatCount = findViewById(R.id.tv_stat_count)
        btnHistory = findViewById(R.id.btn_history)

        // 主题
        try {
            btnThemeToggle = findViewById(R.id.btn_theme_toggle)
            tvThemeLabel = findViewById(R.id.tv_theme_label)
        } catch (_: Exception) {}

        // 词库管理
        try {
            btnDownloadDict = findViewById(R.id.btn_download_dict)
            btnImportDict = findViewById(R.id.btn_import_dict)
            btnExportDict = findViewById(R.id.btn_export_dict)
            btnCloudBackup = findViewById(R.id.btn_cloud_backup)
            tvDictInfo = findViewById(R.id.tv_dict_info)
            spinnerDictSource = findViewById(R.id.spinner_dict_source)
            tvDictDesc = findViewById(R.id.tv_dict_desc)
            etCustomDictUrl = findViewById(R.id.et_custom_dict_url)
        } catch (_: Exception) {}

        // 语音设置
        try {
            spinnerVoiceLanguage = findViewById(R.id.spinner_voice_language)
            spinnerVoiceEngine = findViewById(R.id.spinner_voice_engine)
            tvVoiceEngineDesc = findViewById(R.id.tv_voice_engine_desc)
            etWhisperApiKey = findViewById(R.id.et_whisper_api_key)
            etWhisperModel = findViewById(R.id.et_whisper_model)
            spinnerWhisperMode = findViewById(R.id.spinner_whisper_mode)
            tvWhisperStatus = findViewById(R.id.tv_whisper_status)
            btnTestWhisper = findViewById(R.id.btn_test_whisper)
        } catch (_: Exception) {}

        // 检查更新
        try {
            btnCheckUpdate = findViewById(R.id.btn_check_update)
            vUpdateDot = findViewById(R.id.v_update_dot)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════
    //  词库选择
    // ═══════════════════════════════════════════════════

    private fun setupDictSpinner() {
        val dictSources = PinyinDictManager.AVAILABLE_DICTS
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            dictSources.map { "${it.nameZh} [${it.language.uppercase()}]" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDictSource?.adapter = adapter

        // 设置当前选中
        val activeId = dictManager.getActiveDictSource().id
        val activeIndex = dictSources.indexOfFirst { it.id == activeId }
        if (activeIndex >= 0) spinnerDictSource?.setSelection(activeIndex)

        spinnerDictSource?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val source = dictSources[position]
                tvDictDesc?.text = "${source.description}\n大小: ${source.size} | 许可: ${source.license}"
                // 如果切换了词库源，更新激活词库
                if (source.id != dictManager.getActiveDictSource().id) {
                    dictManager.setActiveDict(source.id)
                    refreshDictInfo()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 初始化描述
        if (dictSources.isNotEmpty()) {
            val source = dictManager.getActiveDictSource()
            tvDictDesc?.text = "${source.description}\n大小: ${source.size} | 许可: ${source.license}"
        }
    }

    // ═══════════════════════════════════════════════════
    //  语音设置
    // ═══════════════════════════════════════════════════

    private fun setupVoiceSpinners() {
        // 语音语言
        val languages = listOf("中文 (zh)", "English (en)")
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoiceLanguage?.adapter = langAdapter

        val currentLang = dictManager.getVoiceLanguage()
        spinnerVoiceLanguage?.setSelection(if (currentLang == "en") 1 else 0)

        spinnerVoiceLanguage?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lang = if (position == 1) "en" else "zh"
                dictManager.setVoiceLanguage(lang)
                appendLog("🎤 语音语言切换为: ${if (lang == "zh") "中文" else "English"}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 语音引擎
        val engines = listOf("Whisper API (推荐)", "Google 语音识别", "自动选择")
        val engineAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engines)
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoiceEngine?.adapter = engineAdapter

        val currentEngine = prefs.getString("voice_engine", VOICE_ENGINE_AUTO) ?: VOICE_ENGINE_AUTO
        spinnerVoiceEngine?.setSelection(when (currentEngine) {
            VOICE_ENGINE_WHISPER -> 0
            VOICE_ENGINE_GOOGLE -> 1
            else -> 2
        })

        spinnerVoiceEngine?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val engine = when (position) {
                    0 -> VOICE_ENGINE_WHISPER
                    1 -> VOICE_ENGINE_GOOGLE
                    else -> VOICE_ENGINE_AUTO
                }
                prefs.edit().putString("voice_engine", engine).apply()
                updateVoiceEngineDesc(engine)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateVoiceEngineDesc(currentEngine)

        // ── Whisper 配置 ──
        setupWhisperConfig()
    }

    private fun setupWhisperConfig() {
        // Whisper 模式选择（隐藏，固定为 API 模式）
        spinnerWhisperMode?.visibility = View.GONE

        // 固定使用 Groq API 地址
        val groqUrl = WhisperRecognizer.DEFAULT_WHISPER_API_URL
        prefs.edit().putString(WhisperRecognizer.PREF_WHISPER_API_URL, groqUrl).apply()

        // 加载已保存的 API Key 和模型
        etWhisperApiKey?.setText(prefs.getString(WhisperRecognizer.PREF_WHISPER_API_KEY, ""))
        etWhisperModel?.setText(prefs.getString(WhisperRecognizer.PREF_WHISPER_MODEL, WhisperRecognizer.DEFAULT_WHISPER_MODEL))

        // 测试按钮
        btnTestWhisper?.setOnClickListener { testWhisperConnection() }
    }

    private fun testWhisperConnection() {
        val url = WhisperRecognizer.DEFAULT_WHISPER_API_URL
        val apiKey = etWhisperApiKey?.text?.toString()?.trim() ?: ""

        if (apiKey.isEmpty()) {
            tvWhisperStatus?.text = "❌ 请输入 Groq API Key"
            return
        }

        tvWhisperStatus?.text = "⏳ 测试中..."
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                // 用 GET /models 端点测试（Groq audio 端点只支持 POST，HEAD/GET 会 404）
                val testUrl = url.replace("/audio/transcriptions", "/models")
                val request = Request.Builder()
                    .url(testUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                runOnUiThread {
                    tvWhisperStatus?.text = when (response.code) {
                        200 -> "✅ 连接成功，API Key 有效"
                        401 -> "⚠️ API 可达，但 Key 无效（请检查 Key）"
                        403 -> "⚠️ API 可达，权限不足"
                        else -> "⚠️ 连接异常: HTTP ${response.code}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvWhisperStatus?.text = "❌ 连接失败: ${e.message}"
                }
            }
        }.start()
    }

    private fun updateVoiceEngineDesc(engine: String) {
        val desc = when (engine) {
            VOICE_ENGINE_WHISPER -> "使用 Whisper API 进行语音识别，不依赖 Google 服务。需要配置 API Key。"
            VOICE_ENGINE_GOOGLE -> "使用 Android 系统内置的 Google 语音识别。需要 Google Play 服务且可能需要科学上网。"
            else -> "优先使用 Whisper API，如果不可用则回退到 Google 语音识别。"
        }
        tvVoiceEngineDesc?.text = desc
    }

    // ═══════════════════════════════════════════════════
    //  版本信息
    // ═══════════════════════════════════════════════════

    private fun showVersion() {
        val displayVersion = try {
            val pInfo = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            pInfo.versionName ?: "开发版"
        } catch (_: Exception) { "开发版" }

        val versionText = if (displayVersion.isNotEmpty() && displayVersion != "null") {
            "版本: $displayVersion"
        } else {
            "版本: 开发版"
        }
        tvVersion.text = versionText
        fetchGitHubVersion()
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
                        runOnUiThread {
                            tvVersion.text = "版本: $tagName"
                            Log.d("SettingsActivity", "从GitHub获取版本号: $tagName")
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ═══════════════════════════════════════════════════
    //  权限
    // ═══════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════
    //  设置加载/保存
    // ═══════════════════════════════════════════════════

    private fun loadSettings() {
        etApiUrl.setText(prefs.getString(PREF_API_URL, DEFAULT_API_URL))
        etApiKey.setText(prefs.getString("openrouter_api_key", ""))
        etModelId.setText(prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID))
        appendLog("已加载设置")
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            val url = etApiUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) { etApiUrl.error = "请输入 API 地址"; return@setOnClickListener }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                etApiUrl.error = "URL 必须以 http:// 或 https:// 开头"; return@setOnClickListener
            }
            val apiKey = etApiKey.text?.toString()?.trim() ?: ""
            val modelId = etModelId.text?.toString()?.trim() ?: DEFAULT_MODEL_ID
            prefs.edit()
                .putString(PREF_API_URL, url)
                .putString("openrouter_api_key", apiKey)
                .putString(PREF_MODEL_ID, modelId)
                .putString(WhisperRecognizer.PREF_WHISPER_API_URL, WhisperRecognizer.DEFAULT_WHISPER_API_URL)
                .putString(WhisperRecognizer.PREF_WHISPER_API_KEY, etWhisperApiKey?.text?.toString()?.trim() ?: "")
                .putString(WhisperRecognizer.PREF_WHISPER_MODEL, etWhisperModel?.text?.toString()?.trim() ?: WhisperRecognizer.DEFAULT_WHISPER_MODEL)
                .apply()
            tvStatus.text = "✓ 设置已保存"
            appendLog("💾 API: $url")
            appendLog("🔑 API Key: ${if (apiKey.isNotEmpty()) "已设置(${apiKey.take(8)}...)" else "未设置"}")
            appendLog("🤖 模型: $modelId")
            Toast.makeText(this, "设置已保存 ✓", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            etApiUrl.setText(DEFAULT_API_URL)
            prefs.edit().putString(PREF_API_URL, DEFAULT_API_URL).apply()
            tvStatus.text = "已重置"
            appendLog("🔄 已重置")
        }

        btnTestApi.setOnClickListener { testApiConnection() }
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        btnThemeToggle?.setOnClickListener { toggleTheme() }

        // 词库管理
        btnDownloadDict?.setOnClickListener { downloadDict() }
        btnImportDict?.setOnClickListener {
            Toast.makeText(this, "请使用词库选择器切换词库", Toast.LENGTH_SHORT).show()
        }
        btnExportDict?.setOnClickListener {
            Toast.makeText(this, "词库导出功能暂未开放", Toast.LENGTH_SHORT).show()
        }
        btnCloudBackup?.setOnClickListener { showCloudBackupDialog() }

        // 检查更新
        btnCheckUpdate?.setOnClickListener { checkForUpdates() }
    }

    // ═══════════════════════════════════════════════════
    //  主题切换
    // ═══════════════════════════════════════════════════

    private fun toggleTheme() {
        val currentTheme = prefs.getInt(PREF_THEME_MODE, THEME_LIGHT)
        val newTheme = if (currentTheme == THEME_LIGHT) THEME_DARK else THEME_LIGHT
        prefs.edit().putInt(PREF_THEME_MODE, newTheme).apply()
        updateThemeUI()
        recreate()
    }

    private fun updateThemeUI() {
        val currentTheme = prefs.getInt(PREF_THEME_MODE, THEME_LIGHT)
        if (currentTheme == THEME_DARK) {
            tvThemeLabel?.text = "🌙 当前：黑暗模式"
            btnThemeToggle?.text = "☀️ 切换到明亮模式"
        } else {
            tvThemeLabel?.text = "☀️ 当前：明亮模式"
            btnThemeToggle?.text = "🌙 切换到黑暗模式"
        }
    }

    // ═══════════════════════════════════════════════════
    //  词库管理
    // ═══════════════════════════════════════════════════

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
        val langLabel = if (info.voiceLanguage == "en") "English" else "中文"
        val statusText = buildString {
            appendLine("当前词库: ${info.activeDictName}")
            appendLine("词条: ${info.dictCount} 条 | 大小: $sizeStr")
            if (info.downloaded) {
                appendLine("来源: ${info.source}")
                appendLine("同步: $syncTime")
            }
            appendLine("语音语言: $langLabel")
        }
        tvDictInfo?.text = statusText.trim()

        // 更新下载按钮文字
        val activeSource = dictManager.getActiveDictSource()
        if (activeSource.url.isEmpty()) {
            btnDownloadDict?.text = "✅ 内置词库"
            btnDownloadDict?.isEnabled = false
        } else if (dictManager.isDictDownloaded(activeSource.id)) {
            btnDownloadDict?.text = "🔄 更新词库"
            btnDownloadDict?.isEnabled = true
        } else {
            btnDownloadDict?.text = "📥 下载词库"
            btnDownloadDict?.isEnabled = true
        }
    }

    private fun downloadDict() {
        val source = dictManager.getActiveDictSource()

        // 检查是否有自定义 URL
        val customUrl = etCustomDictUrl?.text?.toString()?.trim() ?: ""
        if (customUrl.isNotEmpty() && source.url.isNotEmpty()) {
            // 使用自定义 URL 下载
            downloadCustomDict(customUrl, source)
            return
        }

        if (source.url.isEmpty()) {
            Toast.makeText(this, "内置词库无需下载", Toast.LENGTH_SHORT).show()
            return
        }

        btnDownloadDict?.isEnabled = false
        btnDownloadDict?.text = "下载中..."
        tvStatus.text = "⏳ 正在下载..."
        appendLog("📥 开始下载 ${source.nameZh} (${source.size})...")

        dictManager.downloadDict(
            dictId = source.id,
            onProgress = { msg ->
                runOnUiThread {
                    tvStatus.text = msg
                    appendLog(msg)
                }
            },
            onComplete = { success, msg ->
                runOnUiThread {
                    btnDownloadDict?.isEnabled = true
                    btnDownloadDict?.text = if (success) "🔄 更新词库" else "📥 重新下载"
                    tvStatus.text = if (success) "✅ $msg" else "❌ $msg"
                    appendLog(msg)
                    refreshDictInfo()
                    if (success) {
                        try {
                            val rimeEngine = com.cesia.input.engine.rime.RimeEngine(this)
                            rimeEngine.redeploy()
                            Toast.makeText(this, "词库下载完成！已自动部署", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "词库下载完成！重启输入法后生效", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    private fun downloadCustomDict(customUrl: String, source: PinyinDictManager.DictSource) {
        if (!customUrl.startsWith("http://") && !customUrl.startsWith("https://")) {
            Toast.makeText(this, "URL 必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
            return
        }

        btnDownloadDict?.isEnabled = false
        btnDownloadDict?.text = "下载中..."
        tvStatus.text = "⏳ 从自定义地址下载..."
        appendLog("📥 自定义下载: $customUrl")

        dictManager.downloadDict(
            dictId = source.id,
            customUrl = customUrl,
            onProgress = { msg ->
                runOnUiThread {
                    tvStatus.text = msg
                    appendLog(msg)
                }
            },
            onComplete = { success, msg ->
                runOnUiThread {
                    btnDownloadDict?.isEnabled = true
                    btnDownloadDict?.text = if (success) "🔄 更新词库" else "📥 重新下载"
                    tvStatus.text = if (success) "✅ $msg" else "❌ $msg"
                    appendLog(msg)
                    refreshDictInfo()
                    if (success) {
                        try {
                            val rimeEngine = com.cesia.input.engine.rime.RimeEngine(this)
                            rimeEngine.redeploy()
                            Toast.makeText(this, "词库下载完成！已自动部署", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "词库下载完成！重启输入法后生效", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    // ═══════════════════════════════════════════════════
    //  云端备份
    // ═══════════════════════════════════════════════════

    private fun showCloudBackupDialog() {
        val options = arrayOf("上传到云端备份", "从云端恢复")
        AlertDialog.Builder(this)
            .setTitle("云端备份")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> cloudUpload()
                    1 -> cloudDownload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun cloudUpload() {
        val dictFile = java.io.File(filesDir, PinyinDictManager.LOCAL_DICT_FILE)
        val phrasesFile = java.io.File(filesDir, PinyinDictManager.LOCAL_PHRASES_FILE)
        if (!dictFile.exists() && !phrasesFile.exists()) {
            Toast.makeText(this, "没有可备份的词库", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "⏳ 正在上传到云端..."
        appendLog("开始云端备份...")
        val editText = EditText(this).apply {
            hint = "请输入 GitHub Personal Access Token"
            setText(prefs.getString("github_token", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("云端备份到 GitHub Gist")
            .setMessage("需要 GitHub Token 才能上传。Token 只会保存在本地。")
            .setView(editText)
            .setPositiveButton("上传") { _, _ ->
                val token = editText.text.toString().trim()
                if (token.isEmpty()) {
                    Toast.makeText(this, "Token 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("github_token", token).apply()
                performCloudUpload(token, dictFile, phrasesFile)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performCloudUpload(token: String, dictFile: java.io.File, phrasesFile: java.io.File) {
        Thread {
            try {
                val json = JSONObject()
                val files = JSONObject()
                if (dictFile.exists()) {
                    val content = JSONObject()
                    content.put("content", dictFile.readText())
                    files.put("pinyin_dict.json", content)
                }
                if (phrasesFile.exists()) {
                    val content = JSONObject()
                    content.put("content", phrasesFile.readText())
                    files.put("pinyin_phrases.json", content)
                }
                json.put("files", files)
                json.put("description", "Cesia IME dict backup ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}")

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.github.com/gists")
                    .post(body)
                    .addHeader("Authorization", "token $token")
                    .addHeader("User-Agent", "CesiaIME/1.0")
                    .build()

                val response = testClient.newCall(request).execute()
                runOnUiThread {
                    if (response.isSuccessful) {
                        tvStatus.text = "✅ 云端备份成功"
                        appendLog("✅ 云端备份成功")
                        Toast.makeText(this, "备份成功！", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "❌ 备份失败: ${response.code}"
                        appendLog("❌ 备份失败: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 备份失败: ${e.message}"
                    appendLog("❌ 备份失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun cloudDownload() {
        val editText = EditText(this).apply {
            hint = "请输入 Gist ID"
        }
        AlertDialog.Builder(this)
            .setTitle("从云端恢复")
            .setMessage("请输入备份时获得的 Gist ID")
            .setView(editText)
            .setPositiveButton("恢复") { _, _ ->
                val gistId = editText.text.toString().trim()
                if (gistId.isEmpty()) {
                    Toast.makeText(this, "Gist ID 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performCloudDownload(gistId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performCloudDownload(gistId: String) {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/gists/$gistId")
                    .addHeader("User-Agent", "CesiaIME/1.0")
                    .get()
                    .build()

                val response = testClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val files = json.optJSONObject("files") ?: JSONObject()
                    val rimeDir = java.io.File(filesDir, "rime")
                    rimeDir.mkdirs()

                    val dictJson = files.optJSONObject("pinyin_dict.json")
                    if (dictJson != null) {
                        java.io.File(rimeDir, PinyinDictManager.LOCAL_DICT_FILE)
                            .writeText(dictJson.optString("content", ""))
                    }
                    val phrasesJson = files.optJSONObject("pinyin_phrases.json")
                    if (phrasesJson != null) {
                        java.io.File(rimeDir, PinyinDictManager.LOCAL_PHRASES_FILE)
                            .writeText(phrasesJson.optString("content", ""))
                    }

                    runOnUiThread {
                        tvStatus.text = "✅ 云端恢复成功"
                        appendLog("✅ 云端恢复成功")
                        refreshDictInfo()
                        Toast.makeText(this, "恢复成功！重启输入法后生效", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "❌ 恢复失败: ${response.code}"
                        appendLog("❌ 恢复失败: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 恢复失败: ${e.message}"
                    appendLog("❌ 恢复失败: ${e.message}")
                }
            }
        }.start()
    }

    // ═══════════════════════════════════════════════════
    //  API 测试
    // ═══════════════════════════════════════════════════

    private fun testApiConnection() {
        val url = etApiUrl.text?.toString()?.trim() ?: DEFAULT_API_URL
        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        val modelId = etModelId.text?.toString()?.trim() ?: DEFAULT_MODEL_ID
        val testText = etTestText.text?.toString()?.trim() ?: "你好"

        if (apiKey.isEmpty()) {
            tvStatus.text = "❌ 请先设置 API Key"
            appendLog("❌ API Key 为空")
            return
        }

        tvStatus.text = "⏳ 正在测试..."
        appendLog("🧪 测试 API 连接...")

        Thread {
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "你是输入法助手，直接回复用户输入的内容，不要解释。")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", testText)
                    })
                }
                val json = JSONObject().apply {
                    put("model", modelId)
                    put("messages", messages)
                    put("max_tokens", 128)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "https://github.com/harviex/cesia-input-method")
                    .addHeader("X-Title", "Cesia Input Method")
                    .build()

                val response = testClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""

                runOnUiThread {
                    if (response.isSuccessful) {
                        try {
                            val respJson = JSONObject(respBody)
                            val choices = respJson.optJSONArray("choices")
                            val reply = if (choices != null && choices.length() > 0) {
                                choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
                            } else ""
                            tvStatus.text = "✅ 连接成功"
                            appendLog("✅ 测试成功！回复: ${reply.take(50)}")
                        } catch (e: Exception) {
                            tvStatus.text = "✅ 连接成功（响应解析异常）"
                            appendLog("✅ 连接成功")
                        }
                    } else {
                        tvStatus.text = "❌ 连接失败: ${response.code}"
                        appendLog("❌ HTTP ${response.code}: ${respBody.take(100)}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误"
                    appendLog("❌ ${e.message}")
                }
            }
        }.start()
    }

    // ═══════════════════════════════════════════════════
    //  统计
    // ═══════════════════════════════════════════════════

    private fun refreshStats() {
        val records = statsManager.getRecords()
        val totalInput = records.sumOf { it.inputChars.toLong() }
        val totalOutput = records.sumOf { it.outputChars.toLong() }
        val totalVoiceTime = records.sumOf { it.voiceDurationMs }
        tvStatInputChars?.text = "输入: ${totalInput} 字"
        tvStatOutputChars?.text = "输出: ${totalOutput} 字"
        tvStatCount?.text = "次数: ${records.size} 次"
        tvStatVoiceTime?.text = "语音: ${totalVoiceTime / 1000}s"
        tvStatSavedTime?.text = "节省: ${totalVoiceTime / 2000}s"
        tvStatVoiceSpeed?.text = "提速: ${if (totalVoiceTime > 0) (totalInput * 1000 / totalVoiceTime) else 0} 字/分"
    }

    // ═══════════════════════════════════════════════════
    //  检查更新
    // ═══════════════════════════════════════════════════

    private fun checkUpdateDaily() {
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        if (now - lastCheck > 24 * 60 * 60 * 1000) {
            prefs.edit().putLong("last_update_check", now).apply()
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/harviex/cesia-input-method/releases/latest")
                    .addHeader("User-Agent", "CesiaIME/1.0")
                    .get()
                    .build()
                val response = testClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "").removePrefix("v")
                    val htmlUrl = json.optString("html_url", "")
                    val bodyText = json.optString("body", "")

                    val currentVersion = try {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        pInfo.versionName ?: ""
                    } catch (_: Exception) { "" }

                    runOnUiThread {
                        if (tagName.isNotEmpty() && tagName != currentVersion) {
                            vUpdateDot?.visibility = View.VISIBLE
                            AlertDialog.Builder(this)
                                .setTitle("发现新版本: $tagName")
                                .setMessage(bodyText.take(500))
                                .setPositiveButton("前往下载") { _, _ ->
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                                }
                                .setNegativeButton("稍后", null)
                                .show()
                        } else {
                            vUpdateDot?.visibility = View.GONE
                            Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ═══════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLog.text = "[$time] $msg\n${tvLog.text}"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }
}
