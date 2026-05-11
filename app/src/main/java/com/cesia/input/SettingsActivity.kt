package com.cesia.input

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * Cesia 输入法设置页面
 * 配置润色 API 地址、测试连接、查看日志
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiUrl: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTest: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val prefs by lazy {
        getSharedPreferences("cesia_settings", MODE_PRIVATE)
    }

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_API_URL = "https://typeless-ai-service.vercel.app/api/polish"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        setTitle("Cesia 输入法设置")

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        etApiUrl = findViewById(R.id.et_api_url)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnTest = findViewById(R.id.btn_test_api) ?: run {
            // 如果布局里没有测试按钮，动态创建
            MaterialButton(this).apply {
                text = "测试 API 连接"
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16
                }
            }
        }
        tvStatus = findViewById(R.id.tv_api_status) ?: TextView(this).apply {
            id = View.generateViewId()
        }
        tvLog = findViewById(R.id.tv_log) ?: TextView(this).apply {
            id = View.generateViewId()
        }
    }

    private fun loadSettings() {
        val savedUrl = prefs.getString("api_url", DEFAULT_API_URL)
        etApiUrl.setText(savedUrl)
        appendLog("已加载保存的 API 地址")
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            val url = etApiUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                etApiUrl.error = "请输入 API 地址"
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                etApiUrl.error = "URL 必须以 http:// 或 https:// 开头"
                return@setOnClickListener
            }

            // 保存到 SharedPreferences
            prefs.edit().putString("api_url", url).apply()

            // 保存到输入法服务可以读取的位置
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString("api_url", url)
                .apply()

            tvStatus.text = "✓ 已保存: ${url.take(50)}..."
            appendLog("API 地址已保存: $url")
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            etApiUrl.setText(DEFAULT_API_URL)
            prefs.edit().putString("api_url", DEFAULT_API_URL).apply()
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString("api_url", DEFAULT_API_URL)
                .apply()
            tvStatus.text = "已重置为默认地址"
            appendLog("API 地址已重置为默认值")
            Toast.makeText(this, "已重置默认设置", Toast.LENGTH_SHORT).show()
        }

        // 如果有测试按钮，绑定点击事件
        try {
            val testBtn = findViewById<MaterialButton>(R.id.btn_test_api)
            testBtn?.setOnClickListener {
                testApiConnection()
            }
        } catch (_: Exception) {}
    }

    private fun testApiConnection() {
        btnTest.isEnabled = false
        btnTest.text = "测试中..."
        tvStatus.text = "🔄 正在测试连接..."

        Thread {
            try {
                val url = etApiUrl.text?.toString()?.trim() ?: DEFAULT_API_URL

                // 发送测试请求（用 "你好" 作为测试文本）
                val json = JSONObject().apply {
                    put("text", "你好世界")
                    put("language", "zh")
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = testClient.newCall(request).execute()
                val body = response.body?.string() ?: "空响应"

                runOnUiThread {
                    if (response.isSuccessful) {
                        tvStatus.text = "✅ API 连接成功 (${response.code})"
                        appendLog("API 测试成功: $body")
                        Toast.makeText(this, "API 连接正常 ✓", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "❌ API 返回错误: ${response.code}"
                        appendLog("API 测试失败: HTTP ${response.code} - $body")
                        Toast.makeText(this, "API 返回错误: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    btnTest.isEnabled = true
                    btnTest.text = "测试 API 连接"
                }
            } catch (e: IOException) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络连接失败"
                    appendLog("API 测试失败: ${e.message}")
                    Toast.makeText(this, "无法连接 API: ${e.message}", Toast.LENGTH_SHORT).show()
                    btnTest.isEnabled = true
                    btnTest.text = "测试 API 连接"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 测试出错: ${e.message}"
                    appendLog("API 测试异常: ${e.message}")
                    Toast.makeText(this, "测试出错: ${e.message}", Toast.LENGTH_SHORT).show()
                    btnTest.isEnabled = true
                    btnTest.text = "测试 API 连接"
                }
            }
        }.start()
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = tvLog.text?.toString() ?: ""
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(System.currentTimeMillis())
            val newLog = "$current\n[$timestamp] $msg"
            tvLog.text = newLog
        }
    }
}