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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Cesia 输入法设置页面
 * 配置润色 API、测试连接、OTA 更新、词库管理、主题切换
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private var spinnerCloudModel: Spinner? = null
    private var tvModelInfo: TextView? = null
    private lateinit var etPolishPrompt: TextInputEditText
    private lateinit var etTestText: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTestApi: MaterialButton
    private var btnTestLocalAi: MaterialButton? = null
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvVersion: TextView
    private var tvStatVoiceTime: TextView? = null
    private var tvStatSavedTime: TextView? = null
    private var tvStatVoiceSpeed: TextView? = null
    private lateinit var tvStatInputChars: TextView
    private lateinit var tvStatOutputChars: TextView
    private lateinit var tvStatCount: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnGrammarGuide: Button
    private lateinit var statsManager: PolishStatsManager
    private lateinit var dictManager: PinyinDictManager

    // === 语音与 AI 本地化 ===
    private lateinit var localModeManager: LocalModeManager
    private lateinit var modelManager: ModelManager
    private lateinit var downloadManager: ModelDownloadManager
    private var etGroqKey: EditText? = null
    private var etBraveApiKey: EditText? = null
    private var tvHardwareInfo: TextView? = null
    private var tvVoiceModelStatus: TextView? = null
    private var tvAiModelStatus: TextView? = null
    private var tvDownloadProgress: TextView? = null
    private var pbDownload: android.widget.ProgressBar? = null
    private var btnDownloadVoice: Button? = null
    private var btnDownloadAi: Button? = null
    private var isDownloading = false

    // 语音命令词
    private var etCmdExit: TextInputEditText? = null
    private var etCmdPolish: TextInputEditText? = null
    private var etCmdFinish: TextInputEditText? = null
    private var etCmdSend: TextInputEditText? = null
    private var etCmdCommand: TextInputEditText? = null
    private var btnSaveCommands: Button? = null
    private var btnResetCommands: Button? = null
    private var tvCommandStatus: TextView? = null

    // 语音与 AI 本地化设置 helper
    private lateinit var aiSettingsHelper: VoiceAISettingsHelper

    // 主题
    private lateinit var btnThemeToggle: Button
    private lateinit var tvThemeLabel: TextView

    // 词库管理
    private lateinit var btnDownloadDict: Button
    private lateinit var btnImportDict: Button
    private lateinit var btnExportDict: Button
    private lateinit var btnCloudBackup: Button
    private lateinit var tvDictInfo: TextView

    // 检查更新
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
        const val PREF_GROQ_KEY = "groq_api_key"
        const val PREF_OPENROUTER_KEY = "openrouter_api_key"
        const val PREF_BRAVE_KEY = "brave_search_api_key"
        const val PREF_POLISH_PROMPT = "polish_prompt"
        const val PREF_MODE = "run_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用主题
        applyTheme()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        setTitle("Cesia 输入法设置")

        initViews()

        // 语音与 AI 本地化设置 helper
        aiSettingsHelper = VoiceAISettingsHelper(this, prefs)
        aiSettingsHelper.bindViews(
            etGroqKey, tvHardwareInfo,
            tvVoiceModelStatus, tvAiModelStatus,
            btnDownloadVoice, btnDownloadAi,
            tvDownloadProgress, pbDownload
        )
        // 绑定桥梁状态视图（仅显示状态，不下载）
        aiSettingsHelper.bindBridgeViews(
            findViewById(R.id.tv_bridge_status)
        )

        statsManager = PolishStatsManager(this)
        dictManager = PinyinDictManager(this)
        loadSettings()
        aiSettingsHelper.loadSettings()
        // 初始化 VoiceEngine 命令词
        try {
            val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
            com.cesia.input.voice.VoiceEngine.updateCommandWords(
                cmdPrefs.getString("cmd_exit", "退出") ?: "退出",
                cmdPrefs.getString("cmd_polish", "魔法") ?: "魔法",
                cmdPrefs.getString("cmd_finish", "结束") ?: "结束",
                cmdPrefs.getString("cmd_send", "发送") ?: "发送",
                cmdPrefs.getString("cmd_command", "指令") ?: "指令"
            )
        } catch (_: Exception) {}
        setupListeners()
        aiSettingsHelper.setupListeners()
        showVersion()
        refreshStats()
        loadOpenRouterFreeModels()
        refreshDictInfo()
        updateThemeUI()

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
        spinnerCloudModel = findViewById(R.id.spinner_cloud_model)
        tvModelInfo = findViewById(R.id.tv_model_info)
        etPolishPrompt = findViewById(R.id.et_polish_prompt)
        etTestText = findViewById(R.id.et_test_text)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnTestApi = findViewById(R.id.btn_test_api)
        btnTestLocalAi = findViewById(R.id.btn_test_local_ai)
        tvStatus = findViewById(R.id.tv_api_status)
        tvLog = findViewById(R.id.tv_log)
        tvVersion = findViewById(R.id.tv_version)
        try {
            tvStatVoiceTime = findViewById(R.id.tv_stat_voice_time)
            tvStatSavedTime = findViewById(R.id.tv_stat_saved_time)
            tvStatVoiceSpeed = findViewById(R.id.tv_stat_voice_speed)
        } catch (_: Exception) {}
        tvStatInputChars = findViewById(R.id.tv_stat_input_chars)
        tvStatOutputChars = findViewById(R.id.tv_stat_output_chars)
        tvStatCount = findViewById(R.id.tv_stat_count)
        btnHistory = findViewById(R.id.btn_history)
        btnGrammarGuide = findViewById(R.id.btn_grammar_guide)

        // 主题切换
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
        } catch (_: Exception) {}

        // 检查更新
        try {
            btnCheckUpdate = findViewById(R.id.btn_check_update)
            vUpdateDot = findViewById(R.id.v_update_dot)
        } catch (_: Exception) {}

        // === 语音与 AI 本地化视图 ===
        try {
            etGroqKey = findViewById(R.id.et_groq_key)
            etBraveApiKey = findViewById(R.id.et_brave_api_key)
            tvHardwareInfo = findViewById(R.id.tv_hardware_info)
            tvVoiceModelStatus = findViewById(R.id.tv_voice_model_status)
            tvAiModelStatus = findViewById(R.id.tv_ai_model_status)
            tvDownloadProgress = findViewById(R.id.tv_download_progress)
            pbDownload = findViewById(R.id.pb_download)
            btnDownloadVoice = findViewById(R.id.btn_download_voice)
            btnDownloadAi = findViewById(R.id.btn_download_ai)
        } catch (_: Exception) {}

        // 语音命令词设置（原个性化设置内容）
        try {
            etCmdExit = findViewById(R.id.et_cmd_exit)
            etCmdPolish = findViewById(R.id.et_cmd_polish)
            etCmdFinish = findViewById(R.id.et_cmd_finish)
            etCmdSend = findViewById(R.id.et_cmd_send)
            etCmdCommand = findViewById(R.id.et_cmd_command)
            btnSaveCommands = findViewById(R.id.btn_save_commands)
            btnResetCommands = findViewById(R.id.btn_reset_commands)
            tvCommandStatus = findViewById(R.id.tv_command_status)
        } catch (_: Exception) {}
    }

    private fun showVersion() {
        val displayVersion = try {
            BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            Log.w("SettingsActivity", "读取 BuildConfig 版本失败: ${e.javaClass.simpleName} ${e.message}", e)
            "开发版"
        }

        val versionText = if (!displayVersion.isNullOrEmpty() && displayVersion != "null") {
            "版本: $displayVersion"
        } else {
            "版本: 开发版"
        }
        tvVersion.text = versionText
        Log.d("SettingsActivity", "显示版本(固定): $displayVersion")
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
                        // 只更新远端版本缓存，不覆盖本地版本显示
                        Log.d("SettingsActivity", "远端版本已缓存: $tagName")
                    }
                }
            } catch (_: Exception) {}
        }.start()
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
        etApiKey.setText(prefs.getString(PREF_OPENROUTER_KEY, ""))
        etBraveApiKey?.setText(prefs.getString(PREF_BRAVE_KEY, ""))
        etPolishPrompt.setText(prefs.getString(PREF_POLISH_PROMPT, PolishService.DEFAULT_POLISH_PROMPT))
        // 加载语音命令词
        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
        etCmdExit?.setText(cmdPrefs.getString("cmd_exit", "退出"))
        etCmdPolish?.setText(cmdPrefs.getString("cmd_polish", "魔法"))
        etCmdFinish?.setText(cmdPrefs.getString("cmd_finish", "结束"))
        etCmdSend?.setText(cmdPrefs.getString("cmd_send", "发送"))
        etCmdCommand?.setText(cmdPrefs.getString("cmd_command", "指令"))
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
            val braveApiKey = etBraveApiKey?.text?.toString()?.trim() ?: ""
            val selectedModel = cloudModelList?.get(spinnerCloudModel?.selectedItemPosition ?: 0)?.id
                ?: prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID)
            val polishPrompt = etPolishPrompt.text?.toString()?.trim() ?: ""
            prefs.edit()
                .putString(PREF_API_URL, url)
                .putString(PREF_OPENROUTER_KEY, apiKey)
                .putString(PREF_BRAVE_KEY, braveApiKey)
                .putString(PREF_MODEL_ID, selectedModel)
                .putString(PREF_POLISH_PROMPT, polishPrompt)
                .apply()
            aiSettingsHelper.saveSettings()
            tvStatus.text = "✓ 设置已保存"
            appendLog("💾 API: $url")
            appendLog("🔑 API Key: ${if (apiKey.isNotEmpty()) "已设置(${apiKey.take(8)}...)" else "未设置"}")
            appendLog("🤖 模型: $selectedModel")
            if (polishPrompt.isNotEmpty()) {
                appendLog("📝 Prompt: ${polishPrompt.take(50)}...")
            }
            Toast.makeText(this, "设置已保存 ✓", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            etApiUrl.setText(DEFAULT_API_URL)
            prefs.edit().putString(PREF_API_URL, DEFAULT_API_URL).apply()
            tvStatus.text = "已重置"
            appendLog("🔄 已重置")
        }

        btnTestApi.setOnClickListener { testApiConnection() }
        btnTestLocalAi?.setOnClickListener { testLocalAiConnection() }
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnGrammarGuide.setOnClickListener {
            showGrammarGuideDialog()
        }

        // 主题切换
        btnThemeToggle?.setOnClickListener {
            toggleTheme()
        }

        // 词库管理
        btnDownloadDict?.setOnClickListener { downloadDict() }
        btnImportDict?.setOnClickListener { showImportDialog() }
        btnExportDict?.setOnClickListener { exportDict() }
        btnCloudBackup?.setOnClickListener { showCloudBackupDialog() }

        // 检查更新
        btnCheckUpdate?.setOnClickListener { checkForUpdates() }

        // === 语音与 AI 本地化 ===
        btnDownloadVoice?.setOnClickListener { downloadVoiceModel() }
        btnDownloadAi?.setOnClickListener { downloadAiModel() }
        // 卸载按钮已在 VoiceAISettingsHelper 中绑定

        // === 语音命令词 ===
        btnSaveCommands?.setOnClickListener { saveCommandWords() }
        btnResetCommands?.setOnClickListener { resetCommandWords() }
    }

    // ======================== 模型下载 ========================

    private fun downloadVoiceModel() {
        val modelInfo = ModelRegistry.getById("sherpa-zipformer") ?: return
        btnDownloadVoice?.isEnabled = false
        btnDownloadVoice?.text = "下载中..."
        tvStatus.text = "🔄 下载语音模型中..."
        appendLog("⬇ 开始下载语音模型: ${modelInfo.name}")

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
                            appendLog("⬇ $fileName: $pctStr ($dlStr / $totalStr)")
                        }
                    }
                }
                runOnUiThread {
                    btnDownloadVoice?.isEnabled = true
                    if (result.isSuccess) {
                        tvStatus.text = "✅ 语音模型下载完成"
                        appendLog("✅ 语音模型下载完成: ${result.getOrNull()?.absolutePath}")
                        Toast.makeText(this, "语音模型下载完成", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "❌ 下载失败: ${result.exceptionOrNull()?.message}"
                        appendLog("❌ 语音模型下载失败: ${result.exceptionOrNull()?.message}")
                        btnDownloadVoice?.text = "📥 下载语音模型"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnDownloadVoice?.isEnabled = true
                    btnDownloadVoice?.text = "📥 下载语音模型"
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
        btnDownloadAi?.text = "下载中..."
        tvStatus.text = "🔄 下载 AI 模型中..."
        appendLog("⬇ 开始下载 AI 模型: ${modelInfo.name} (${modelInfo.sizeBytes / 1024 / 1024}MB)")

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
                            appendLog("⬇ $name: $pctStr ($dlStr / $totalStr)")
                        }
                    }
                }
                runOnUiThread {
                    btnDownloadAi?.isEnabled = true
                    if (result.isSuccess) {
                        tvStatus.text = "✅ AI 模型下载完成"
                        appendLog("✅ AI 模型下载完成: ${result.getOrNull()?.absolutePath}")
                        Toast.makeText(this, "AI 模型下载完成", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "❌ 下载失败: ${result.exceptionOrNull()?.message}"
                        appendLog("❌ AI 模型下载失败: ${result.exceptionOrNull()?.message}")
                        btnDownloadAi?.text = "📥 下载 AI 模型"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnDownloadAi?.isEnabled = true
                    btnDownloadAi?.text = "📥 下载 AI 模型"
                    tvStatus.text = "❌ 下载异常: ${e.message}"
                    appendLog("❌ AI 模型下载异常: ${e.message}")
                }
            }
        }.start()
    }

    // ======================== 主题切换 ========================

    private fun toggleTheme() {
        val currentTheme = prefs.getInt(PREF_THEME_MODE, THEME_LIGHT)
        val newTheme = if (currentTheme == THEME_LIGHT) THEME_DARK else THEME_LIGHT
        prefs.edit().putInt(PREF_THEME_MODE, newTheme).apply()
        updateThemeUI()
        // 重启Activity以应用新主题
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
        btnDownloadDict?.text = if (info.downloaded) "🔄 更新词库" else "📥 下载词库"
    }

    private fun downloadDict() {
        AlertDialog.Builder(this)
            .setTitle("下载完整词库")
            .setMessage("将下载 rime-ice 完整词库（full.zip，约 16MB 压缩包，解压后约 50MB）。\n\n词库包含：\n• 基础词库（~55万词条）\n• 扩展词库（41448字表）\n• 腾讯词库（~100万词条）\n• 英文词库\n• OpenCC 转换表\n• Lua 脚本\n\n下载完成后需重启输入法生效。\n\n来源：iDvel/rime-ice，GPL-3.0")
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

        dictManager.downloadFullDict(
            onProgress = { msg ->
                runOnUiThread {
                    tvStatus.text = msg
                    appendLog(msg)
                }
            },
            onComplete = { success, msg ->
                runOnUiThread {
                    btnDownloadDict?.isEnabled = true
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

    private fun showImportDialog() {
        AlertDialog.Builder(this)
            .setTitle("导入词库")
            .setMessage("当前版本已改用在线下载词库，不再支持本地导入。\n请使用「下载词库」按钮获取完整词库。")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun openFilePicker(requestCode: Int, title: String) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, title), requestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 文件导入已废弃，不再处理
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex("_data")
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
        // fallback: copy to cache
        try {
            val tempFile = java.io.File(cacheDir, "import_temp_${System.currentTimeMillis()}.json")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return tempFile.absolutePath
        } catch (e: Exception) {
            appendLog("文件读取失败: ${e.message}")
        }
        return null
    }

    private fun exportDict() {
        Toast.makeText(this, "当前版本已改用在线下载词库，不再支持本地导出", Toast.LENGTH_SHORT).show()
    }

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
        // 云端备份：打包整个 rime 目录为 zip 上传
        val rimeDir = java.io.File(getExternalFilesDir(null), "rime")
        if (!rimeDir.exists() || rimeDir.listFiles()?.isEmpty() != false) {
            Toast.makeText(this, "没有可备份的词库", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "⏳ 正在打包词库..."
        appendLog("开始云端备份...")

        val editText = EditText(this).apply {
            hint = "请输入 GitHub Personal Access Token"
            setText(prefs.getString("github_token", ""))
        }

        AlertDialog.Builder(this)
            .setTitle("云端备份到 GitHub Gist")
            .setMessage("将打包整个词库目录（~50MB）上传。\n需要 GitHub Token。Token 只保存在本地。")
            .setView(editText)
            .setPositiveButton("上传") { _, _ ->
                val token = editText.text.toString().trim()
                if (token.isEmpty()) {
                    Toast.makeText(this, "Token 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("github_token", token).apply()
                performCloudUpload(token, rimeDir)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performCloudUpload(token: String, rimeDir: java.io.File) {
        Thread {
            try {
                // 打包 rime 目录为 zip
                val zipFile = java.io.File(cacheDir, "rime_backup.zip")
                java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                    rimeDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val entryName = file.relativeTo(rimeDir).path
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                // 上传到 GitHub Gist
                val json = JSONObject()
                val files = JSONObject()
                val content = JSONObject()
                content.put("content", zipFile.readText())
                files.put("rime_backup.zip", content)
                json.put("files", files)
                json.put("description", "Cesia 输入法词库备份 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")

                val client = OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://api.github.com/gists")
                    .addHeader("Authorization", "token $token")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                runOnUiThread {
                    if (response.isSuccessful) {
                        val gistUrl = JSONObject(body).optString("html_url", "")
                        tvStatus.text = "✅ 备份成功！"
                        appendLog("云端备份成功: $gistUrl")
                        Toast.makeText(this, "备份成功！\nGist: $gistUrl", Toast.LENGTH_LONG).show()
                    } else {
                        tvStatus.text = "❌ 备份失败: ${response.code}"
                        appendLog("云端备份失败: ${response.code} $body")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 备份异常"
                    appendLog("云端备份异常: ${e.message}")
                }
            }
        }.start()
    }

    private fun cloudDownload() {
        val editText = EditText(this).apply {
            hint = "请输入 Gist URL 或 ID"
        }

        AlertDialog.Builder(this)
            .setTitle("从云端恢复")
            .setMessage("输入之前备份的 Gist URL 或 ID")
            .setView(editText)
            .setPositiveButton("恢复") { _, _ ->
                val gistInput = editText.text.toString().trim()
                if (gistInput.isEmpty()) {
                    Toast.makeText(this, "请输入 Gist URL 或 ID", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performCloudDownload(gistInput)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performCloudDownload(gistInput: String) {
        tvStatus.text = "⏳ 正在从云端恢复..."
        appendLog("开始云端恢复...")

        Thread {
            try {
                val gistId = if (gistInput.contains("gist.github.com")) {
                    gistInput.split("/").last()
                } else {
                    gistInput
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://api.github.com/gists/$gistId")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    runOnUiThread {
                        tvStatus.text = "❌ 恢复失败: ${response.code}"
                        appendLog("云端恢复失败: ${response.code}")
                    }
                    return@Thread
                }

                val json = JSONObject(body)
                val files = json.getJSONObject("files")

                // 查找 rime_backup.zip 或旧版 pinyin_dict.json
                if (files.has("rime_backup.zip")) {
                    val zipContent = files.getJSONObject("rime_backup.zip").getString("content")
                    val zipFile = java.io.File(cacheDir, "rime_restore.zip")
                    zipFile.writeText(zipContent)

                    // 解压到外部存储 rime 目录
                    val rimeDir = java.io.File(getExternalFilesDir(null), "rime")
                    rimeDir.mkdirs()
                    java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val outFile = java.io.File(rimeDir, entry.name)
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }

                    runOnUiThread {
                        tvStatus.text = "✅ 恢复成功！"
                        appendLog("云端恢复成功")
                        refreshDictInfo()
                        Toast.makeText(this, "恢复成功！重启输入法后生效", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "❌ Gist 中未找到词库文件"
                        appendLog("Gist 中未找到词库文件")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 恢复异常"
                    appendLog("云端恢复异常: ${e.message}")
                }
            }
        }.start()
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
                    val customPrompt = etPolishPrompt.text?.toString()?.trim() ?: PolishService.DEFAULT_POLISH_PROMPT
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
                    val selectedModel = cloudModelList?.get(spinnerCloudModel?.selectedItemPosition ?: 0)?.id
                        ?: prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID)
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
                    btnTestApi.text = "📡 测试 API 润色"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误: ${e.message ?: "未知"}"
                    appendLog("API 测试异常: ${e.message}")
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "📡 测试 API 润色"
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
                        btnTestLocalAi?.text = "🤖 本地 AI"
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
                        btnTestLocalAi?.text = "🤖 本地 AI"
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
                    btnTestLocalAi?.text = "🤖 本地 AI"
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "本地 AI 测试异常", e)
                runOnUiThread {
                    tvStatus.text = "❌ 异常: ${e.message ?: "未知"}"
                    appendLog("本地 AI 异常: ${e.message}")
                    btnTestLocalAi?.isEnabled = true
                    btnTestLocalAi?.text = "🤖 本地 AI"
                }
            }
        }.start()
    }
    // ======================== 统计 & 日志 ========================

    override fun onResume() {
        super.onResume()
        refreshStats()
        refreshDictInfo()
    }

    private fun refreshStats() {
        tvStatInputChars.text = statsManager.totalInputChars.toString()
        tvStatOutputChars.text = statsManager.totalOutputChars.toString()
        tvStatCount.text = statsManager.totalPolishCount.toString()
        refreshVoiceStats()
    }

    private fun refreshVoiceStats() {
        try {
            val voiceTimeMin = statsManager.totalVoiceDurationMs / 60000
            val savedTimeMin = statsManager.savedTimeSeconds / 60
            val speed = statsManager.voiceSpeedPerMinute

            tvStatVoiceTime?.text = "${voiceTimeMin}分钟"
            tvStatSavedTime?.text = "${savedTimeMin}分钟"
            tvStatVoiceSpeed?.text = "${speed}字/分"
        } catch (_: Exception) {}
    }

    /**
     * 显示语法大纲弹窗
     */
    private fun showGrammarGuideDialog() {
        val guideMgr = com.cesia.input.stats.GrammarGuideManager(this)
        val guideContent = guideMgr.content

        val scrollView = android.widget.ScrollView(this)
        val tv = android.widget.TextView(this).apply {
            textSize = 14f
            setPadding(32, 24, 32, 24)
            setTextColor(0xFF333333.toInt())
            text = if (guideContent.isEmpty()) {
                "暂无语法大纲\n\n请先使用几次润色功能，系统会自动根据历史记录生成个人语法纲要。"
            } else {
                "版本: ${guideMgr.version}\n\n$guideContent"
            }
        }
        scrollView.addView(tv)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📖 个人语法纲要")
            .setView(scrollView)
            .setPositiveButton("刷新") { dialog, _ ->
                val records = statsManager.getRecords()
                if (records.isNotEmpty()) {
                    dialog.dismiss()
                    // 后台线程生成
                    Thread {
                        try {
                            val guideMgr = com.cesia.input.stats.GrammarGuideManager(this@SettingsActivity)
                            guideMgr.clear()
                            guideMgr.updateRecordCount(0)

                            val sb = StringBuilder()
                            for ((i, record) in records.take(20).withIndex()) {
                                sb.appendLine("【${i + 1}】原文：${record.inputText}")
                                sb.appendLine("    润色：${record.outputText}")
                                sb.appendLine()
                            }
                            val inputText = sb.toString().trim()
                            if (inputText.isEmpty()) return@Thread

                            val prompt = "以下是用户的最近润色记录（原文→润色后），请分析并生成一份简洁的【用户个人语法纲要】。\n" +
                                    "包括：\n" +
                                    "1. 常用句式和表达习惯\n" +
                                    "2. 词汇偏好（喜欢用哪些词）\n" +
                                    "3. 语气风格（正式/口语/幽默/简洁等）\n" +
                                    "4. 标点使用习惯\n" +
                                    "5. 其他语言特点\n" +
                                    "\n" +
                                    "要求：简洁精炼，不超过500字，用要点列表形式。\n" +
                                    "\n" +
                                    "润色记录：\n" + inputText

                            val apiKey = prefs.getString("openrouter_api_key", "") ?: ""
                            if (apiKey.isEmpty()) {
                                runOnUiThread {
                                    Toast.makeText(this@SettingsActivity, "请先设置 OpenRouter API Key", Toast.LENGTH_LONG).show()
                                }
                                return@Thread
                            }

                            val client = OkHttpClient.Builder()
                                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                                .build()

                            val url = prefs.getString("api_url", DEFAULT_API_URL) ?: DEFAULT_API_URL
                            val model = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
                            Log.d("GrammarGuide", "请求: url=$url model=$model")

                            val json = org.json.JSONObject().apply {
                                put("model", model)
                                put("messages", org.json.JSONArray().apply {
                                    put(org.json.JSONObject().apply {
                                        put("role", "user")
                                        put("content", prompt)
                                    })
                                })
                                put("max_tokens", 1024)
                            }

                            val request = Request.Builder()
                                .url(url)
                                .addHeader("Authorization", "Bearer $apiKey")
                                .addHeader("Content-Type", "application/json")
                                .post(json.toString().toRequestBody("application/json".toMediaType()))
                                .build()

                            val response = client.newCall(request).execute()
                            val body = response.body?.string() ?: ""

                            if (response.isSuccessful) {
                                val result = org.json.JSONObject(body)
                                val content = result.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                if (content.isNotEmpty()) {
                                    guideMgr.saveGuide(content)
                                    guideMgr.updateRecordCount(records.size)
                                    runOnUiThread {
                                        Toast.makeText(this@SettingsActivity, "✅ 语法大纲已生成（版本${guideMgr.version}）", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this@SettingsActivity, "生成失败: ${response.code}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SettingsActivity", "语法大纲生成失败", e)
                            runOnUiThread {
                                Toast.makeText(this@SettingsActivity, "生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                } else {
                    Toast.makeText(this, "暂无润色记录，无法生成大纲", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

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
        btnCheckUpdate?.isEnabled = false
        btnCheckUpdate?.text = "检查中..."
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
                    btnCheckUpdate?.isEnabled = true
                    btnCheckUpdate?.text = "检查更新"
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
                        vUpdateDot?.visibility = View.VISIBLE
                        showUpdateDialog(latestVersionName, releaseUrl, releaseNotes, apkUrl)
                    } else {
                        vUpdateDot?.visibility = View.GONE
                        tvStatus.text = "✅ 已是最新版本 ($latestVersionName)"
                        appendLog("✅ 已是最新版本")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnCheckUpdate?.isEnabled = true
                    btnCheckUpdate?.text = "检查更新"
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

        tvStatus.text = "⏳ 正在下载 v$version..."
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
                        tvStatus.text = "❌ 下载失败: ${response.code}"
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
                    tvStatus.text = "✅ 下载完成，正在安装..."
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
                        tvStatus.text = "❌ 安装失败: ${e.message}"
                        appendLog("安装失败: ${e.message}")
                        Toast.makeText(this, "安装失败，请手动安装: ${apkFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 下载异常: ${e.message}"
                    appendLog("下载异常: ${e.message}")
                }
            }
        }.start()
    }

    // ======================== 语音命令词 ========================

    private fun saveCommandWords() {
        val exit = etCmdExit?.text?.toString()?.trim() ?: ""
        val polish = etCmdPolish?.text?.toString()?.trim() ?: ""
        val finish = etCmdFinish?.text?.toString()?.trim() ?: ""
        val send = etCmdSend?.text?.toString()?.trim() ?: ""
        val command = etCmdCommand?.text?.toString()?.trim() ?: ""

        val errors = mutableListOf<String>()
        if (exit.isEmpty()) errors.add("退出命令词不能为空")
        if (polish.isEmpty()) errors.add("润色命令词不能为空")
        if (finish.isEmpty()) errors.add("结束命令词不能为空")
        if (send.isEmpty()) errors.add("发送命令词不能为空")
        if (command.isEmpty()) errors.add("指令模式词不能为空")

        val words = listOf(exit, polish, finish, send, command)
        val duplicates = words.groupBy { it }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) errors.add("命令词不能重复：${duplicates.joinToString("、")}")

        if (errors.isNotEmpty()) {
            tvCommandStatus?.text = "❌ ${errors.joinToString("\n")}"
            tvCommandStatus?.setBackgroundColor(0xFFFFEBEE.toInt())
            return
        }

        val cmdPrefs = getSharedPreferences("cesia_commands", MODE_PRIVATE)
        cmdPrefs.edit()
            .putString("cmd_exit", exit)
            .putString("cmd_polish", polish)
            .putString("cmd_finish", finish)
            .putString("cmd_send", send)
            .putString("cmd_command", command)
            .apply()

        // 立即更新 VoiceEngine
        try {
            com.cesia.input.voice.VoiceEngine.updateCommandWords(exit, polish, finish, send, command)
        } catch (_: Exception) {}

        tvCommandStatus?.text = "✅ 已保存：退出=$exit, 润色=$polish, 结束=$finish, 发送=$send, 指令=$command"
        tvCommandStatus?.setBackgroundColor(0xFFE8F5E9.toInt())
        Toast.makeText(this, "命令词已保存", Toast.LENGTH_SHORT).show()
    }

    private fun resetCommandWords() {
        etCmdExit?.setText("退出")
        etCmdPolish?.setText("魔法")
        etCmdFinish?.setText("结束")
        etCmdSend?.setText("发送")
        etCmdCommand?.setText("指令")
        tvCommandStatus?.text = "已恢复默认值，请点击保存"
        tvCommandStatus?.setBackgroundColor(0xFFFFF3E0.toInt())
    }

    // ==================== 云端模型加载 ====================

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
                    tvModelInfo?.text = "⚠️ 无法加载模型列表：${e.message}"
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        tvModelInfo?.text = "⚠️ 加载失败：HTTP ${response.code}"
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
                        updateSpinner(list)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvModelInfo?.text = "⚠️ 解析失败：${e.message}"
                    }
                }
            }
        })
    }

    private fun updateSpinner(models: List<CloudModel>) {
        val spinner = spinnerCloudModel ?: return
        val displayNames = models.map { model ->
            val ctxStr = if (model.contextLength >= 1000000)
                "${model.contextLength / 1000000}M"
            else if (model.contextLength >= 1000)
                "${model.contextLength / 1000}K"
            else "${model.contextLength}"
            val features = buildString {
                if (model.hasTools) append("🔧")
                if (model.hasVision) append("👁")
            }
            "${model.name} ($ctxStr) $features"
        }
        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            displayNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // 恢复之前保存的选择
        val savedModelId = prefs.getString(PREF_MODEL_ID, DEFAULT_MODEL_ID)
        val savedIndex = models.indexOfFirst { it.id == savedModelId }
        if (savedIndex >= 0) {
            spinner.setSelection(savedIndex)
        }

        // 选中后更新信息栏
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val model = models[position]
                val ctxStr = if (model.contextLength >= 1000000)
                    "${model.contextLength / 1000000}M tokens"
                else if (model.contextLength >= 1000)
                    "${model.contextLength / 1000}K tokens"
                else "${model.contextLength} tokens"
                val features = mutableListOf<String>()
                if (model.hasTools) features.add("工具调用")
                if (model.hasVision) features.add("视觉")
                tvModelInfo?.text = "${model.provider} · $ctxStr · ${features.joinToString(", ")}"
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
}
