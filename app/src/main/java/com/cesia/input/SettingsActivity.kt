package com.cesia.input

import android.Manifest
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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cesia 输入法设置页面
 * 配置润色 API、测试连接、OTA 更新
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiUrl: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTestApi: MaterialButton
    private lateinit var btnUpdate: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvVersion: TextView

    private val prefs by lazy { getSharedPreferences("cesia_settings", MODE_PRIVATE) }

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val PREF_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://typeless-ai-service.vercel.app/api/polish"
        const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        setTitle("Cesia 输入法设置")

        initViews()
        loadSettings()
        setupListeners()
        showVersion()

        checkAndRequestPermission()
    }

    private fun initViews() {
        etApiUrl = findViewById(R.id.et_api_url)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnTestApi = findViewById(R.id.btn_test_api)
        btnUpdate = findViewById(R.id.btn_update)
        tvStatus = findViewById(R.id.tv_api_status)
        tvLog = findViewById(R.id.tv_log)
        tvVersion = findViewById(R.id.tv_version)
    }

    private fun showVersion() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "版本: ${pInfo.versionName} (${pInfo.versionCode})"
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
    }

    private fun testApiConnection() {
        btnTestApi.isEnabled = false
        btnTestApi.text = "测试中..."
        tvStatus.text = "🔄 正在测试..."

        Thread {
            try {
                val json = JSONObject().apply {
                    put("text", "你好世界")
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
                        tvStatus.text = "✅ API 成功"
                        appendLog("API 测试成功: $body")
                    } else {
                        tvStatus.text = "❌ API 错误 ${response.code}"
                        appendLog("API 失败: $body")
                    }
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "📡 测试 API"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络错误"
                    appendLog("API 测试异常: ${e.message}")
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "📡 测试 API"
                }
            }
        }.start()
    }

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
                        // 按 .apk 后缀找下载链接
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
        // API 24+ 使用 FileProvider
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
            // fallback: try without FileProvider
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

    private fun appendLog(msg: String) {
        runOnUiThread {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(System.currentTimeMillis())
            val current = tvLog.text?.toString() ?: ""
            val newLog = (current + "\n[$timestamp] $msg").lines().takeLast(50).joinToString("\n")
            tvLog.text = newLog
        }
    }
}
