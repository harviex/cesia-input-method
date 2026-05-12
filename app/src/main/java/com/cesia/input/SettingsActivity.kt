package com.cesia.input

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cesia 输入法设置页面
 * 配置润色 API、唤醒词/结束词、测试连接、查看日志
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiUrl: TextInputEditText
    private lateinit var etWakeWord: TextInputEditText
    private lateinit var etEndWord: TextInputEditText
    private lateinit var switchVoiceActivation: SwitchMaterial
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTestApi: MaterialButton
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
        const val DEFAULT_WAKE_WORD = "Hey Typeless"
        const val DEFAULT_END_WORD = "Typeless Over"
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
        etWakeWord = findViewById(R.id.et_wake_word)
        etEndWord = findViewById(R.id.et_end_word)
        switchVoiceActivation = findViewById(R.id.switch_voice_activation)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnTestApi = findViewById(R.id.btn_test_api)
        tvStatus = findViewById(R.id.tv_api_status)
        tvLog = findViewById(R.id.tv_log)
    }

    private fun loadSettings() {
        val apiUrl = prefs.getString(CesiaInputMethod.PREF_API_URL, DEFAULT_API_URL)
        val wakeWord = prefs.getString(CesiaInputMethod.PREF_WAKE_WORD, DEFAULT_WAKE_WORD)
        val endWord = prefs.getString(CesiaInputMethod.PREF_END_WORD, DEFAULT_END_WORD)
        val voiceActivation = prefs.getBoolean(CesiaInputMethod.PREF_VOICE_ACTIVATION, false)

        etApiUrl.setText(apiUrl)
        etWakeWord.setText(wakeWord)
        etEndWord.setText(endWord)
        switchVoiceActivation.isChecked = voiceActivation

        appendLog("已加载上次设置")
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            val url = etApiUrl.text?.toString()?.trim() ?: ""
            val wakeWord = etWakeWord.text?.toString()?.trim() ?: DEFAULT_WAKE_WORD
            val endWord = etEndWord.text?.toString()?.trim() ?: DEFAULT_END_WORD
            val voiceActivation = switchVoiceActivation.isChecked

            if (url.isEmpty()) {
                etApiUrl.error = "请输入 API 地址"
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                etApiUrl.error = "URL 必须以 http:// 或 https:// 开头"
                return@setOnClickListener
            }

            if (wakeWord == endWord) {
                Toast.makeText(this, "唤醒词和结束词不能相同", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存所有设置
            prefs.edit()
                .putString(CesiaInputMethod.PREF_API_URL, url)
                .putString(CesiaInputMethod.PREF_WAKE_WORD, wakeWord)
                .putString(CesiaInputMethod.PREF_END_WORD, endWord)
                .putBoolean(CesiaInputMethod.PREF_VOICE_ACTIVATION, voiceActivation)
                .apply()

            tvStatus.text = "✓ 设置已保存"
            appendLog("""
                💾 设置已保存:
                  API: $url
                  唤醒词: "$wakeWord"
                  结束词: "$endWord"
                  语音激活: $voiceActivation
            """.trimIndent())

            Toast.makeText(this, "设置已保存 ✓", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            etApiUrl.setText(DEFAULT_API_URL)
            etWakeWord.setText(DEFAULT_WAKE_WORD)
            etEndWord.setText(DEFAULT_END_WORD)
            switchVoiceActivation.isChecked = false

            prefs.edit()
                .putString(CesiaInputMethod.PREF_API_URL, DEFAULT_API_URL)
                .putString(CesiaInputMethod.PREF_WAKE_WORD, DEFAULT_WAKE_WORD)
                .putString(CesiaInputMethod.PREF_END_WORD, DEFAULT_END_WORD)
                .putBoolean(CesiaInputMethod.PREF_VOICE_ACTIVATION, false)
                .apply()

            tvStatus.text = "已重置为默认设置"
            appendLog("🔄 设置已重置为默认值")
            Toast.makeText(this, "已重置默认设置", Toast.LENGTH_SHORT).show()
        }

        btnTestApi.setOnClickListener {
            testApiConnection()
        }
    }

    private fun testApiConnection() {
        btnTestApi.isEnabled = false
        btnTestApi.text = "测试中..."
        tvStatus.text = "🔄 正在测试连接..."

        Thread {
            try {
                val url = etApiUrl.text?.toString()?.trim() ?: DEFAULT_API_URL

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
                        appendLog("API 测试成功 (${response.code}): $body")
                        Toast.makeText(this, "API 连接正常 ✓", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "❌ API 返回错误: ${response.code}"
                        appendLog("API 测试失败: HTTP ${response.code} - $body")
                        Toast.makeText(this, "API 返回错误: ${response.code}", Toast.LENGTH_SHORT)
                            .show()
                    }
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "📡 测试 API"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ 网络连接失败"
                    appendLog("API 测试失败: ${e.message}")
                    Toast.makeText(
                        this,
                        "连接失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    btnTestApi.isEnabled = true
                    btnTestApi.text = "📡 测试 API"
                }
            }
        }.start()
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = tvLog.text?.toString() ?: ""
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(System.currentTimeMillis())
            val newLog = "$current\n[$timestamp] $msg"
            tvLog.text = newLog
        }
    }
}
