package com.cesia.input.ai

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.widget.EditText
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
    var etGroqKey: EditText? = null
    var tvModeLabel: TextView? = null
    var btnToggleMode: Button? = null
    var tvHardwareInfo: TextView? = null
    var tvVoiceModelStatus: TextView? = null
    var tvAiModelStatus: TextView? = null
    var btnDownloadAuto: Button? = null
    var btnUninstall: Button? = null
    var tvDownloadProgress: TextView? = null
    var pbDownload: ProgressBar? = null
    var switchGpu: SwitchCompat? = null

    private var isDownloading = false

    /** 初始化所有视图引用 */
    fun bindViews(
        etGroqKey: EditText?,
        tvModeLabel: TextView?,
        btnToggleMode: Button?,
        tvHardwareInfo: TextView?,
        tvVoiceModelStatus: TextView?,
        tvAiModelStatus: TextView?,
        btnDownloadAuto: Button?,
        btnUninstall: Button?,
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
        this.btnDownloadAuto = btnDownloadAuto
        this.btnUninstall = btnUninstall
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

        // 自动下载（根据 RAM 选 tier）
        btnDownloadAuto?.setOnClickListener {
            val ram = getTotalRamGB()
            val tier = if (ram >= 6) ModelInfo.Tier.PREMIUM else ModelInfo.Tier.BASIC
            val tierName = if (tier == ModelInfo.Tier.PREMIUM) "Large" else "Small"
            val models = ModelRegistry.ALL_MODELS.filter { it.tier == tier }
            val totalSize = models.sumOf { it.sizeBytes }
            Toast.makeText(activity,
                "将下载 $tierName 版本（${ModelDownloadManager.Formatter.formatSize(totalSize)}）",
                Toast.LENGTH_SHORT).show()
            downloadTier(tier)
        }

        // 卸载本地模型
        btnUninstall?.setOnClickListener {
            val installedTier = downloadManager.getInstalledTier()
            if (installedTier == null) {
                Toast.makeText(activity, "没有已安装的本地模型", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val count = downloadManager.deleteTier(installedTier)
            refreshModelStatus()
            Toast.makeText(activity, "已卸载 $count 个模型文件", Toast.LENGTH_SHORT).show()
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
            "✅ 已安装: ${voiceInstalled.name} (${ModelDownloadManager.Formatter.formatSize(voiceInstalled.length())})"
        } else {
            "❌ 未安装"
        }

        // AI 模型
        val aiInstalled = modelManager.getInstalledAiModelFile()
        tvAiModelStatus?.text = if (aiInstalled != null) {
            "✅ 已安装: ${aiInstalled.name} (${ModelDownloadManager.Formatter.formatSize(aiInstalled.length())})"
        } else {
            "❌ 未安装"
        }

        // 更新下载/卸载按钮状态
        val installedTier = downloadManager.getInstalledTier()
        if (installedTier != null) {
            btnDownloadAuto?.text = "✅ 已安装 (${if (installedTier == ModelInfo.Tier.PREMIUM) "Large" else "Small"})"
            btnDownloadAuto?.isEnabled = false
            btnUninstall?.isEnabled = true
        } else {
            val ram = getTotalRamGB()
            val recommended = if (ram >= 6) "Large" else "Small"
            btnDownloadAuto?.text = "⬇ 下载 $recommended 版本"
            btnDownloadAuto?.isEnabled = !isDownloading
            btnUninstall?.isEnabled = false
        }
    }

    /** 硬件检测 + 推荐 */
    private fun detectHardware() {
        val ram = getTotalRamGB()
        val recommendation = when {
            ram >= 6 -> "RAM ${ram}GB — 推荐下载 Large 版本"
            ram >= 3 -> "RAM ${ram}GB — 推荐下载 Small 版本"
            else -> "RAM ${ram}GB — 建议使用云端模式"
        }
        tvHardwareInfo?.text = "📱 $recommendation"
    }

    private fun getTotalRamGB(): Long {
        return try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 0L
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024 * 1024)
        } catch (_: Exception) {
            0L
        }
    }

    /** 下载某个 tier 的所有模型 */
    private fun downloadTier(tier: ModelInfo.Tier) {
        if (isDownloading) {
            Toast.makeText(activity, "正在下载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        tvDownloadProgress?.visibility = View.VISIBLE
        pbDownload?.visibility = View.VISIBLE
        tvDownloadProgress?.text = "正在下载 ${tier.name} 版本..."

        val appCompat = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        appCompat.lifecycleScope.launch {
            val result = downloadManager.downloadTier(tier) { modelName, progress ->
                activity.runOnUiThread {
                    pbDownload?.progress = progress
                    tvDownloadProgress?.text = "下载 $modelName: $progress%"
                }
            }

            isDownloading = false

            activity.runOnUiThread {
                pbDownload?.visibility = View.GONE
                if (result.isSuccess) {
                    tvDownloadProgress?.text = "✅ ${tier.name} 版本下载完成"
                    refreshModelStatus()
                    Toast.makeText(activity, "安装成功", Toast.LENGTH_SHORT).show()
                } else {
                    tvDownloadProgress?.text =
                        "❌ 下载失败: ${result.exceptionOrNull()?.message ?: "请检查网络"}"
                    Toast.makeText(activity,
                        "下载失败: ${result.exceptionOrNull()?.message ?: "请检查网络"}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ModelRegistry 在 com.cesia.input.model 包中
}
