package com.cesia.input

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Cesia 输入法设置页面
 * 配置润色 API、测试连接、OTA 更新、词库管理、主题切换
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiUrl: TextInputEditText
    private lateinit var etTestText: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTestApi: MaterialButton
    private lateinit var btnUpdate: MaterialButton
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

    private val prefs by lazy { getSharedPreferences("cesia_settings", MODE_PRIVATE) }

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://typeless-ai-service.vercel.app/api/polish"
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
        etTestText = findViewById(R.id.et_test_text)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnTestApi = findViewById(R.id.btn_test_api)
        btnUpdate = findViewById(R.id.btn_update)
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
    }

    private fun showVersion() {
        try {
            // 兼容所有Android版本读取版本号
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
            val versionName = pInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            tvVersion.text = if (versionName != null) "版本: $versionName ($versionCode)" else "版本: $versionCode"
        } catch (_: Exception) {
            tvVersion.text = "版本: 未知"
        }
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
        appendLog("已加载设置")
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            val url = etApiUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) { etApiUrl.error = "请输入 API 地址"; return@setOnClickListener }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                etApiUrl.error = "URL 必须以 http:// 或 https:// 开头"; return@setOnClickListener
            }
            prefs.edit().putString(PREF_API_URL, url).apply()
            tvStatus.text = "✓ 设置已保存"
            appendLog("💾 API: $url")
            Toast.makeText(this, "设置已保存 ✓", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            etApiUrl.setText(DEFAULT_API_URL)
            prefs.edit().putString(PREF_API_URL, DEFAULT_API_URL).apply()
            tvStatus.text = "已重置"
            appendLog("🔄 已重置")
        }

        btnTestApi.setOnClickListener { testApiConnection() }
        btnUpdate.setOnClickListener { checkForUpdates() }

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
                    btnDownloadDict?.text = "📥 下载最新词库"
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
                val json = JSONObject().apply {
                    put("text", inputText)
                    put("language", "zh")
                }
                val request = Request.Builder()
                    .url(etApiUrl.text?.toString()?.trim() ?: DEFAULT_API_URL)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = testClient.newCall(request).execute()
                val body = response.body?.string() ?: "空响应"

                runOnUiThread {
                    if (response.isSuccessful) {
                        try {
                            val respJson = JSONObject(body)
                            val polished = respJson.optString("polished_text", "")
                            if (polished.isNotEmpty() && polished != inputText) {
                                etTestText.setText(polished)
                                tvStatus.text = "✅ API 润色成功"
                                appendLog("润色成功: $polished")
                            } else {
                                tvStatus.text = "⚠️ API 返回但内容无变化"
                                appendLog("API 返回无变化: $body")
                            }
                        } catch (e: Exception) {
                            tvStatus.text = "✅ API 成功 (原始响应)"
                            appendLog("API 响应: $body")
                        }
                    } else {
                        tvStatus.text = "❌ API 错误 ${response.code}"
                        appendLog("API 失败 (${response.code}): $body")
                    }
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "📡 测试 API 润色"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误"
                    appendLog("API 测试异常: ${e.message}")
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "📡 测试 API 润色"
                }
            }
        }.start()
    }

    // ======================== OTA 更新 ========================

    private fun checkForUpdates() {
        btnUpdate.isEnabled = false
        btnUpdate.text = "检查中..."
        tvStatus.text = "🔄 检查更新..."
        appendLog("正在检查更新...")

        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://api.github.com/repos/harviex/cesia-input-method/releases/latest")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                runOnUiThread {
                    try {
                        val json = JSONObject(body)
                        val remoteVersion = json.getString("tag_name")
                        val assets = json.getJSONArray("assets")
                        var downloadUrl = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                        if (downloadUrl.isEmpty()) {
                            tvStatus.text = "❌ Release 中未找到 APK 文件"
                            btnUpdate.isEnabled = true
                            btnUpdate.text = "🔄 检查更新"
                            return@runOnUiThread
                        }

                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        val currentVersion = pInfo.versionName

                        if (remoteVersion != currentVersion) {
                            tvStatus.text = "🎉 发现新版本: $remoteVersion（当前: $currentVersion）"
                            appendLog("发现新版本: $remoteVersion")
                            showUpdateDialog(remoteVersion, downloadUrl)
                        } else {
                            tvStatus.text = "✅ 已是最新版本 ($currentVersion)"
                            appendLog("已是最新版本")
                        }
                    } catch (e: Exception) {
                        tvStatus.text = "❌ 解析失败"
                        appendLog("解析更新失败: ${e.message}")
                    }
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "🔄 检查更新"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误"
                    appendLog("检查更新失败: ${e.message}")
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "🔄 检查更新"
                }
            }
        }.start()
    }

    private fun showUpdateDialog(version: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本 $version")
            .setMessage("是否下载并安装新版本？")
            .setPositiveButton("下载更新") { _, _ ->
                downloadAndInstall(downloadUrl)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadAndInstall(url: String) {
        tvStatus.text = "⏳ 正在下载..."
        appendLog("开始下载更新...")
        btnUpdate.isEnabled = false
        btnUpdate.text = "下载中..."

        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    runOnUiThread {
                        tvStatus.text = "❌ 下载失败"
                        appendLog("下载失败: HTTP ${response.code}")
                        btnUpdate.isEnabled = true
                        btnUpdate.text = "🔄 检查更新"
                    }
                    return@Thread
                }

                val apkBytes = response.body?.bytes()
                    ?: throw RuntimeException("Empty response")

                val apkFile = java.io.File(cacheDir, "cesia-update.apk")
                apkFile.writeBytes(apkBytes)
                appendLog("下载完成: ${apkFile.length() / 1024 / 1024}MB")

                runOnUiThread {
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 下载异常"
                    appendLog("下载异常: ${e.message}")
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "🔄 检查更新"
                }
            }
        }.start()
    }

    private fun installApk(apkFile: java.io.File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", apkFile)
        } else {
            android.net.Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
            tvStatus.text = "✅ 已启动安装程序"
            appendLog("已启动 APK 安装")
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(fallback)
                tvStatus.text = "✅ 已启动安装程序"
                appendLog("已启动 APK 安装")
            } catch (e2: Exception) {
                tvStatus.text = "❌ 安装失败: ${e2.message}"
                appendLog("安装失败: ${e2.message}")
            }
        }

        btnUpdate.isEnabled = true
        btnUpdate.text = "🔄 检查更新"
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
}
