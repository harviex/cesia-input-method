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
    private lateinit var etModelId: TextInputEditText
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
    private lateinit var statsManager: PolishStatsManager
    private lateinit var dictManager: PinyinDictManager

    // === 语音与 AI 本地化 ===
    private lateinit var localModeManager: LocalModeManager
    private lateinit var modelManager: ModelManager
    private lateinit var downloadManager: ModelDownloadManager
    private var etGroqKey: EditText? = null
    private var tvHardwareInfo: TextView? = null
    private var tvVoiceModelStatus: TextView? = null
    private var tvAiModelStatus: TextView? = null
    private var tvDownloadProgress: TextView? = null
    private var pbDownload: android.widget.ProgressBar? = null
    private var btnDownloadVoice: Button? = null
    private var btnDownloadAi: Button? = null
    private var btnUninstall: Button? = null
    private var isDownloading = false

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
            btnUninstall,
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
        setupListeners()
        aiSettingsHelper.setupListeners()
        showVersion()
        refreshStats()
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
        etModelId = findViewById(R.id.et_model_id)
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
            tvHardwareInfo = findViewById(R.id.tv_hardware_info)
            tvVoiceModelStatus = findViewById(R.id.tv_voice_model_status)
            tvAiModelStatus = findViewById(R.id.tv_ai_model_status)
            tvDownloadProgress = findViewById(R.id.tv_download_progress)
            pbDownload = findViewById(R.id.pb_download)
            btnDownloadVoice = findViewById(R.id.btn_download_voice)
            btnDownloadAi = findViewById(R.id.btn_download_ai)
            btnUninstall = findViewById(R.id.btn_uninstall)
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
                .putString(PREF_OPENROUTER_KEY, apiKey)
                .putString(PREF_MODEL_ID, modelId)
                .apply()
            aiSettingsHelper.saveSettings()
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
        btnTestLocalAi?.setOnClickListener { testLocalAiConnection() }
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
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
        // TTS 使用系统自带语音引擎，无需下载
        btnUninstall?.setOnClickListener { uninstallModels() }
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
                    dm.downloadZipformer { fileName, percent ->
                        runOnUiThread {
                            tvStatus.text = "🔄 下载 $fileName ($percent%)"
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
        val modelInfo = ModelRegistry.getById("qwen25-1.5b-mnn") ?: return
        btnDownloadAi?.isEnabled = false
        btnDownloadAi?.text = "下载中..."
        tvStatus.text = "🔄 下载 AI 模型中..."
        appendLog("⬇ 开始下载 AI 模型: ${modelInfo.name} (${modelInfo.sizeBytes / 1024 / 1024}MB)")

        Thread {
            try {
                val dm = ModelDownloadManager(this@SettingsActivity)
                val result = kotlinx.coroutines.runBlocking {
                    dm.downloadAiModel(modelInfo) { name, percent ->
                        runOnUiThread {
                            tvStatus.text = "🔄 下载 $name ($percent%)"
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

    private fun uninstallModels() {
        var count = 0
        // 删除 AI 模型
        val aiFile = modelManager.getInstalledAiModelFile()
        appendLog("DEBUG: aiFile=$aiFile, exists=${aiFile?.exists()}, installedAiModelId=${modelManager.installedAiModelId}")
        if (aiFile != null && aiFile.exists()) {
            val deleted = aiFile.delete()
            if (deleted) {
                modelManager.installedAiModelId = null
                count++
                appendLog("🗑 已删除 AI 模型: ${aiFile.name}")
            } else {
                appendLog("❌ 删除失败: ${aiFile.absolutePath}")
            }
        } else {
            appendLog("⚠️ AI 模型文件不存在: ${aiFile?.absolutePath ?: "null"}")
        }
        // 删除语音模型
        val voiceDir = java.io.File(filesDir, "local_models/zipformer")
        if (voiceDir.exists()) {
            val deleted = voiceDir.deleteRecursively()
            if (deleted) {
                modelManager.installedVoiceModelId = null
                count++
                appendLog("🗑 已删除语音模型")
            }
        }
        tvStatus.text = "✅ 已卸载 $count 个模型"
        appendLog("卸载完成: $count 个模型")
        Toast.makeText(this, "已卸载 $count 个模型", Toast.LENGTH_SHORT).show()
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
                    // OpenRouter 格式
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "你是一个中文文本润色助手。请将用户输入的口语化文字润色为通顺、简洁的书面语。只输出润色后的文字，不要解释。")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", inputText)
                        })
                    }
                    val json = JSONObject().apply {
                        put("model", etModelId.text?.toString()?.trim() ?: DEFAULT_MODEL_ID)
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
                        tvStatus.text = "❌ 模型加载失败"
                        appendLog("本地 AI 失败: 模型加载失败 (${loadTime}ms)")
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
}
