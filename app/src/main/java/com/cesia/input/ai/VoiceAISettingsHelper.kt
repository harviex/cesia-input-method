package com.cesia.input.ai

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.cesia.input.R
import com.cesia.input.model.ModelDownloadManager
import com.cesia.input.model.ModelInfo
import com.cesia.input.model.ModelManager
import com.cesia.input.model.ModelRegistry
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.io.File

/**
 * 语音与 AI 本地化设置辅助类
 *
 * 封装 SettingsActivity 中所有本地化相关的 UI 逻辑
 */
class VoiceAISettingsHelper(
    private val activity: Activity,
    private val prefs: SharedPreferences
) {

    // 管理器
    val localModeManager = LocalModeManager(activity)
    val modelManager = ModelManager(activity)
    val downloadManager = ModelDownloadManager(activity)

    // 视图引用
    var etGroqKey: TextInputEditText? = null
    var tvModeLabel: TextView? = null
    var btnToggleMode: Button? = null
    var tvHardwareInfo: TextView? = null
    var tvVoiceModelStatus: TextView? = null
    var tvAiModelStatus: TextView? = null
    var btnDownloadWhisperSmall: Button? = null
    var btnDownloadWhisperLarge: Button? = null
    var btnDownloadQwen08b: Button? = null
    var btnDownloadQwen2b: Button? = null
    var tvDownloadProgress: TextView? = null
    var pbDownload: ProgressBar? = null
    var switchGpu: SwitchCompat? = null

    private var isDownloading = false

    /** 初始化所有视图引用 */
    fun bindViews(
        etGroqKey: TextInputEditText?,
        tvModeLabel: TextView?,
        btnToggleMode: Button?,
        tvHardwareInfo: TextView?,
        tvVoiceModelStatus: TextView?,
        tvAiModelStatus: TextView?,
        btnDownloadWhisperSmall: Button?,
        btnDownloadWhisperLarge: Button?,
        btnDownloadQwen08b: Button?,
        btnDownloadQwen2b: Button?,
        tvDownloadProgress: TextView?,
        pbDownload: ProgressBar?,
        switchGpu: SwitchCompat?
    ) {
        this.etGroqKey = etGroqKey
        this.tvModeLabel = tvModeLabel
        this.btnToggleMode = btnToggleMode
        this.tvHardwareInfo = tvHardwareInfo
        this.tvVoiceModelStatus = tvVoiceModelStatus
        this.tvAiModelStatus = tvAiModelStatus
        this.btnDownloadWhisperSmall = btnDownloadWhisperSmall
        this.btnDownloadWhisperLarge = btnDownloadWhisperLarge
        this.btnDownloadQwen08b = btnDownloadQwen08b
        this.btnDownloadQwen2b = btnDownloadQwen2b
        this.tvDownloadProgress = tvDownloadProgress
        this.pbDownload = pbDownload
        this.switchGpu = switchGpu
    }

    /** 加载已保存的设置 */
    fun loadSettings() {
        // Groq Key
        etGroqKey?.setText(prefs.getString("groq_api_key", "") ?: "")
        // GPU 开关
        switchGpu?.isChecked = modelManager.useGpu
        // 刷新 UI
        refreshModeUI()
        refreshModelStatus()
        detectHardware()
    }

    /** 保存设置 */
    fun saveSettings() {
        etGroqKey?.text?.toString()?.let { key ->
            prefs.edit().putString("groq_api_key", key).apply()
        }
        modelManager.useGpu = switchGpu?.isChecked ?: true
    }

    /** 设置按钮监听 */
    fun setupListeners() {
        // 模式切换
        btnToggleMode?.setOnClickListener {
            localModeManager.toggle()
            refreshModeUI()
        }

        // GPU 开关
        switchGpu?.setOnCheckedChangeListener { _, checked ->
            modelManager.useGpu = checked
        }

        // Whisper Small
        btnDownloadWhisperSmall?.setOnClickListener {
            downloadModel(ModelRegistry.getById("whisper-small")!!)
        }
        // Whisper Large
        btnDownloadWhisperLarge?.setOnClickListener {
            downloadModel(ModelRegistry.getById("whisper-large-turbo")!!)
        }
        // Qwen 0.8B
        btnDownloadQwen08b?.setOnClickListener {
            downloadModel(ModelRegistry.getById("qwen-0.8b")!!)
        }
        // Qwen 2B
        btnDownloadQwen2b?.setOnClickListener {
            downloadModel(ModelRegistry.getById("qwen-2b")!!)
        }
    }

    /** 刷新模式显示 */
    private fun refreshModeUI() {
        val mode = localModeManager.mode
        val modeText = when (mode) {
            LocalModeManager.RunMode.LOCAL -> "\uD83D\uDCF1 当前模式：本地模式"
            LocalModeManager.RunMode.CLOUD_FREE -> "\uD83C\uDF10 当前模式：云端免费"
            LocalModeManager.RunMode.CLOUD_PAID -> "\uD83D\uDCB0 当前模式：云端付费"
        }
        tvModeLabel?.text = modeText

        val toggleText = when (mode) {
            LocalModeManager.RunMode.LOCAL -> "\uD83D\uDD04 切换到云端模式"
            LocalModeManager.RunMode.CLOUD_FREE,
            LocalModeManager.RunMode.CLOUD_PAID -> "\uD83D\uDD04 切换到本地模式"
        }
        btnToggleMode?.text = toggleText
    }

    /** 刷新模型安装状态 */
    private fun refreshModelStatus() {
        // 语音模型
        val voiceInstalled = modelManager.getInstalledVoiceModelFile()
        tvVoiceModelStatus?.text = if (voiceInstalled != null) {
            "\u2705 已安装: ${voiceInstalled.name} (${ModelDownloadManager.Formatter.formatSize(voiceInstalled.length())})"
        } else {
            "\u274C 未安装"
        }

        // AI 模型
        val aiInstalled = modelManager.getInstalledAiModelFile()
        tvAiModelStatus?.text = if (aiInstalled != null) {
            "\u2705 已安装: ${aiInstalled.name} (${ModelDownloadManager.Formatter.formatSize(aiInstalled.length())})"
        } else {
            "\u274C 未安装"
        }

        // 更新按钮文字
        updateDownloadButton(btnDownloadWhisperSmall, "whisper-small")
        updateDownloadButton(btnDownloadWhisperLarge, "whisper-large-turbo")
        updateDownloadButton(btnDownloadQwen08b, "qwen-0.8b")
        updateDownloadButton(btnDownloadQwen2b, "qwen-2b")
    }

    private fun updateDownloadButton(btn: Button?, modelId: String) {
        val installed = modelManager.isModelInstalled(modelId)
        btn?.text = if (installed) "\u2611\uFE0F 已安装" else "\uD83D\uDCE5 下载"
        btn?.isEnabled = !installed && !isDownloading
    }

    /** 硬件检测 + 推荐 */
    private fun detectHardware() {
        val ram = getTotalRamGB()
        val soc = Build.HARDWARE ?: "unknown"
        val recommendation = when {
            ram >= 8 -> "高端旗舰（${ram}GB RAM）— 推荐高级安装：Whisper Large Turbo + Qwen 2B"
            ram >= 5 -> "中端机型（${ram}GB RAM）— 推荐简单安装：Whisper Small + Qwen 0.8B"
            else -> "入门机型（${ram}GB RAM）— 建议使用云端模式"
        }
        tvHardwareInfo?.text = "\uD83D\uDCBB $soc | RAM: ${ram}GB | $recommendation"
    }

    private fun getTotalRamGB(): Long {
        return try {
            val reader = File("/proc/meminfo").bufferedReader()
            val memInfo = reader.readLine()
            reader.close()
            val kb = memInfo.split("\\s+".toRegex())[1].toLongOrNull() ?: 0L
            kb / (1024 * 1024)
        } catch (_: Exception) {
            0L
        }
    }

    /** 下载模型 */
    private fun downloadModel(model: ModelInfo) {
        if (isDownloading) {
            Toast.makeText(activity, "正在下载其他模型，请稍候", Toast.LENGTH_SHORT).show()
            return
        }

        isDownloading = true
        tvDownloadProgress?.visibility = View.VISIBLE
        pbDownload?.visibility = View.VISIBLE
        tvDownloadProgress?.text = "正在下载 ${model.name}..."

        val modelFile = File(modelManager.modelsDir, model.fileName)

        activity.lifecycleScope.launch {
            val result = downloadManager.download(model) { progress ->
                activity.runOnUiThread {
                    pbDownload?.progress = progress
                    val downloadedMB = (model.sizeBytes * progress / 100) / (1024 * 1024)
                    val totalMB = model.sizeBytes / (1024 * 1024)
                    tvDownloadProgress?.text =
                        "下载 ${model.name}: ${progress}% (${downloadedMB}MB / ${totalMB}MB)"
                }
            }

            isDownloading = false

            activity.runOnUiThread {
                pbDownload?.visibility = View.GONE
                if (result.isSuccess) {
                    tvDownloadProgress?.text = "\u2705 ${model.name} 下载完成"
                    // 标记安装
                    modelManager.markInstalled(model.id, model.type)
                    refreshModelStatus()
                    Toast.makeText(activity, "${model.name} 安装成功", Toast.LENGTH_SHORT).show()
                } else {
                    tvDownloadProgress?.text =
                        "\u274C ${model.name} 下载失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                    Toast.makeText(
                        activity,
                        "下载失败: ${result.exceptionOrNull()?.message ?: "请检查网络"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ModelRegistry 在 com.cesia.input.model 包中
}
