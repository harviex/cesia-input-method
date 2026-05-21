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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Cesia 输入法设置页面
 * 配置润色 API、测试连接、OTA 更新、词库管理、主题切换
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etTestText: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTestApi: MaterialButton
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
        const val PERMISSION_REQUEST_CODE = 1001
        const val PREF_THEME_MODE = "theme_mode"
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val IMPORT_DICT_REQUEST = 2001
        const val IMPORT_PHRASES_REQUEST = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用主题
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
        etTestText = findViewById(R.id.et_test_text)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnTestApi = findViewById(R.id.btn_test_api)
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
    }

    private fun showVersion() {
        // 优先显示本地编译版本号，如果本地版本号无效则显示缓存的GitHub版本号
        val localVersionName = try {
            val pInfo = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            pInfo.versionName
        } catch (_: Exception) { null }

        val cachedVersion = prefs.getString("github_version_name", null)

        val displayVersion = when {
            // 本地版本号有效且不是 "1.0.0"（构建失败默认值）
            !localVersionName.isNullOrEmpty() && localVersionName != "null" && localVersionName != "1.0.0" -> localVersionName
            // 尝试从缓存读取GitHub版本号
            !cachedVersion.isNullOrEmpty() -> cachedVersion
            else -> "开发版"
        }

        tvVersion.text = "版本: $displayVersion"
        Log.d("SettingsActivity", "显示版本: $displayVersion (本地: $localVersionName, 缓存: $cachedVersion)")

        // 如果没有缓存的版本号，异步从GitHub获取
        if (cachedVersion.isNullOrEmpty() && localVersionName.let { it.isNullOrEmpty() || it == "null" || it == "1.0.0" }) {
            fetchGitHubVersion()
        }
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
        etApiKey.setText(prefs.getString("openrouter_api_key", ""))
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
            prefs.edit()
                .putString(PREF_API_URL, url)
                .putString("openrouter_api_key", apiKey)
                .apply()
            tvStatus.text = "✓ 设置已保存"
            appendLog("💾 API: $url")
            appendLog("🔑 API Key: ${if (apiKey.isNotEmpty()) "已设置(${apiKey.take(8)}...)" else "未设置"}")
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
            "从未同步"
        }
        tvDictInfo?.text = "字典: ${info.dictCount} 条 | 词组: ${info.phraseCount} 条\n" +
                "大小: ${formatSize(info.dictSize + info.phrasesSize)} | 来源: ${info.source}\n" +
                "版本: ${info.version} | 同步: $syncTime"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / 1024 / 1024}MB"
        }
    }

    private fun downloadDict() {
        btnDownloadDict?.isEnabled = false
        btnDownloadDict?.text = "下载中..."
        tvStatus.text = "⏳ 正在下载词库..."

        dictManager.downloadDict(
            onProgress = { msg ->
                runOnUiThread {
                    tvStatus.text = msg
                    appendLog(msg)
                }
            },
            onComplete = { success, msg ->
                runOnUiThread {
                    btnDownloadDict?.isEnabled = true
                    btnDownloadDict?.text = "📥下载"
                    tvStatus.text = if (success) "✅ $msg" else "❌ $msg"
                    appendLog(msg)
                    refreshDictInfo()
                    if (success) {
                        Toast.makeText(this, "词库下载完成！重启输入法后生效", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun showImportDialog() {
        AlertDialog.Builder(this)
            .setTitle("导入词库")
            .setMessage("选择要导入的词库文件（JSON格式）")
            .setPositiveButton("导入字典") { _, _ ->
                openFilePicker(IMPORT_DICT_REQUEST, "选择拼音字典文件")
            }
            .setNegativeButton("导入词组") { _, _ ->
                openFilePicker(IMPORT_PHRASES_REQUEST, "选择词组文件")
            }
            .setNeutralButton("取消", null)
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
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            IMPORT_DICT_REQUEST -> {
                val uri = data?.data ?: return
                val path = getRealPathFromUri(uri)
                dictManager.importDict(path, null) { success, msg ->
                    runOnUiThread {
                        tvStatus.text = if (success) "✅ $msg" else "❌ $msg"
                        appendLog(msg)
                        refreshDictInfo()
                        if (success) Toast.makeText(this, "导入成功！重启输入法后生效", Toast.LENGTH_LONG).show()
                    }
                }
            }
            IMPORT_PHRASES_REQUEST -> {
                val uri = data?.data ?: return
                val path = getRealPathFromUri(uri)
                dictManager.importDict(null, path) { success, msg ->
                    runOnUiThread {
                        tvStatus.text = if (success) "✅ $msg" else "❌ $msg"
                        appendLog(msg)
                        refreshDictInfo()
                        if (success) Toast.makeText(this, "导入成功！重启输入法后生效", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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
        val exportDir = "${getExternalFilesDir(null)?.absolutePath}/dict_export"
        dictManager.exportDict(exportDir) { success, msg ->
            runOnUiThread {
                tvStatus.text = if (success) "✅ $msg" else "❌ $msg"
                appendLog(msg)
            }
        }
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
        // 使用 GitHub Gist 作为简易云端备份
        val dictFile = java.io.File(filesDir, PinyinDictManager.LOCAL_DICT_FILE)
        val phrasesFile = java.io.File(filesDir, PinyinDictManager.LOCAL_PHRASES_FILE)

        if (!dictFile.exists() && !phrasesFile.exists()) {
            Toast.makeText(this, "没有可备份的词库", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "⏳ 正在上传到云端..."
        appendLog("开始云端备份...")

        // 提示用户需要配置 GitHub Token
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
                json.put("description", "Cesia 输入法词库备份 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
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
                // 提取 Gist ID
                val gistId = if (gistInput.contains("gist.github.com")) {
                    gistInput.split("/").last()
                } else {
                    gistInput
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
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

                var imported = false

                if (files.has("pinyin_dict.json")) {
                    val content = files.getJSONObject("pinyin_dict.json").getString("content")
                    java.io.File(filesDir, PinyinDictManager.LOCAL_DICT_FILE).writeText(content)
                    imported = true
                }

                if (files.has("pinyin_phrases.json")) {
                    val content = files.getJSONObject("pinyin_phrases.json").getString("content")
                    java.io.File(filesDir, PinyinDictManager.LOCAL_PHRASES_FILE).writeText(content)
                    imported = true
                }

                runOnUiThread {
                    if (imported) {
                        tvStatus.text = "✅ 恢复成功！"
                        appendLog("云端恢复成功")
                        refreshDictInfo()
                        Toast.makeText(this, "恢复成功！重启输入法后生效", Toast.LENGTH_LONG).show()
                    } else {
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
                        put("model", "minimax/minimax-m2.5:free")
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

                // 用 versionCode 比较版本
                val currentVersionCode = try {
                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
                    else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                } catch (_: Exception) { 0L }

                // 从 tag_name 解析最新版本的 versionCode (格式: 1.0.X -> X)
                val latestVersionCode = try {
                    val parts = latestVersionName.split(".")
                    if (parts.size >= 3) parts[2].toLong() else 0L
                } catch (_: Exception) { 0L }

                val currentVersionName = try {
                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    pInfo.versionName ?: "开发版"
                } catch (_: Exception) { "开发版" }

                // 缓存GitHub版本号到prefs，供showVersion()显示
                if (latestVersionName.isNotEmpty()) {
                    prefs.edit().putString("github_version_name", latestVersionName).apply()
                }

                appendLog("当前版本: $currentVersionName($currentVersionCode), 最新版本: $latestVersionName($latestVersionCode)")

                runOnUiThread {
                    // 刷新版本号显示
                    showVersion()
                    if (latestVersionCode > 0 && latestVersionCode > currentVersionCode) {
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
