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
    var btnDownloadVoice: Button? = null
    var btnDownloadAi: Button? = null
    var btnUninstall: Button? = null
    var tvDownloadProgress: TextView? = null
    var pbDownload: ProgressBar? = null
    var switchGpu: SwitchCompat? = null

    // 桥梁插件视图
    var tvBridgeStatus: TextView? = null
    var btnDownloadBridge: Button? = null
    var tvBridgeError: TextView? = null

    private var isDownloading = false

    /** 初始化所有视图引用 */
    fun bindViews(
        etGroqKey: EditText?,
        tvModeLabel: TextView?,
        btnToggleMode: Button?,
        tvHardwareInfo: TextView?,
        tvVoiceModelStatus: TextView?,
        tvAiModelStatus: TextView?,
        btnDownloadVoice: Button?,
        btnDownloadAi: Button?,
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
        this.btnDownloadVoice = btnDownloadVoice
        this.btnDownloadAi = btnDownloadAi
        this.btnUninstall = btnUninstall
        this.tvDownloadProgress = tvDownloadProgress
        this.pbDownload = pbDownload
        this.switchGpu = switchGpu
    }

    /** 绑定桥梁插件视图（在 SettingsActivity.initViews 中单独调用） */
    fun bindBridgeViews(
        tvBridgeStatus: TextView?,
        btnDownloadBridge: Button?,
        tvBridgeError: TextView?
    ) {
        this.tvBridgeStatus = tvBridgeStatus
        this.btnDownloadBridge = btnDownloadBridge
        this.tvBridgeError = tvBridgeError
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
        refreshBridgeStatus()
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

        // 下载桥梁插件
        btnDownloadBridge?.setOnClickListener {
            downloadBridge()
        }

        // 下载语音识别模型（Whisper）
        btnDownloadVoice?.setOnClickListener {
            val ram = getTotalRamGB()
            val tier = if (ram >= 6.0) ModelInfo.Tier.PREMIUM else ModelInfo.Tier.BASIC
            val model = ModelRegistry.ALL_MODELS.find {
                it.type == ModelInfo.ModelType.VOICE && it.tier == tier
            }
            val sizeStr = model?.let { ModelDownloadManager.Formatter.formatSize(it.sizeBytes) } ?: ""
            Toast.makeText(activity,
                "将下载语音识别模型（$sizeStr）",
                Toast.LENGTH_SHORT).show()
            downloadVoiceModel(tier)
        }

        // 下载 AI 润色模型（Qwen）
        btnDownloadAi?.setOnClickListener {
            val ram = getTotalRamGB()
            val tier = if (ram >= 6.0) ModelInfo.Tier.PREMIUM else ModelInfo.Tier.BASIC
            val model = ModelRegistry.ALL_MODELS.find {
                it.type == ModelInfo.ModelType.AI && it.tier == tier
            }
            val sizeStr = model?.let { ModelDownloadManager.Formatter.formatSize(it.sizeBytes) } ?: ""
            Toast.makeText(activity,
                "将下载 AI 润色模型（$sizeStr）",
                Toast.LENGTH_SHORT).show()
            downloadAiModel(tier)
        }

        // 卸载本地模型
        btnUninstall?.setOnClickListener {
            val voiceInstalled = modelManager.getInstalledVoiceModelFile()
            val aiInstalled = modelManager.getInstalledAiModelFile()
            if (voiceInstalled == null && aiInstalled == null) {
                Toast.makeText(activity, "没有已安装的本地模型", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var count = 0
            val installedTier = downloadManager.getInstalledTier()
            if (installedTier != null) {
                count = downloadManager.deleteTier(installedTier)
            }
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
            "❌ 未安装（将使用 Google 语音识别）"
        }

        // AI 模型
        val aiInstalled = modelManager.getInstalledAiModelFile()
        tvAiModelStatus?.text = if (aiInstalled != null) {
            "✅ 已安装: ${aiInstalled.name} (${ModelDownloadManager.Formatter.formatSize(aiInstalled.length())})"
        } else {
            "❌ 未安装（将使用 OpenRouter 云端润色）"
        }

        // 更新语音识别下载按钮
        if (voiceInstalled != null) {
            btnDownloadVoice?.text = "✅ 语音识别已安装"
            btnDownloadVoice?.isEnabled = false
        } else {
            val ram = getTotalRamGB()
            val recommended = if (ram >= 6) "Large" else "Small"
            btnDownloadVoice?.text = "⬇ 下载语音识别 ($recommended)"
            btnDownloadVoice?.isEnabled = !isDownloading
        }

        // 更新 AI 润色下载按钮
        if (aiInstalled != null) {
            btnDownloadAi?.text = "✅ 语音润色已安装"
            btnDownloadAi?.isEnabled = false
        } else {
            val ram = getTotalRamGB()
            val recommended = if (ram >= 6) "2B" else "0.8B"
            btnDownloadAi?.text = "⬇ 下载语音润色 ($recommended)"
            btnDownloadAi?.isEnabled = !isDownloading
        }

        // 卸载按钮：任一已安装则可卸载
        btnUninstall?.isEnabled = voiceInstalled != null || aiInstalled != null
    }

    /** 硬件检测 + 推荐 */
    private fun detectHardware() {
        val ram = getTotalRamGB()
        val recommendation = when {
            ram >= 6 -> "RAM ${ram}GB — 推荐下载 Large / 2B 版本"
            ram >= 3 -> "RAM ${ram}GB — 推荐下载 Small / 0.8B 版本"
            else -> "RAM ${ram}GB — 建议使用云端模式"
        }
        tvHardwareInfo?.text = "📱 $recommendation"
    }

    private fun getTotalRamGB(): Double {
        return try {
            val reader = File("/proc/meminfo").bufferedReader()
            val firstLine = reader.readLine() ?: return 0.0
            reader.close()
            val kb = firstLine.trim().split("\\s+".toRegex())[1].toDoubleOrNull() ?: 0.0
            // 厂商按 1000 进制标称 GB，保留 1 位小数
            Math.round(kb / 1000.0 / 1000.0 * 10.0) / 10.0
        } catch (_: Exception) {
            0.0
        }
    }

    // ==================== 桥梁插件 ====================

    /** 刷新桥梁插件状态 */
    fun refreshBridgeStatus() {
        val bridgeLoaded = com.cesia.input.engine.ai.WhisperEngine.isBridgeLoaded()
        val bridgeError = com.cesia.input.engine.ai.WhisperEngine.getBridgeLoadError()
        val bridgeInstalled = downloadManager.isBridgeInstalled()

        if (bridgeLoaded) {
            // 桥梁已加载成功
            tvBridgeStatus?.text = "✅ 语音引擎已就绪（native-bridge.so 已加载）"
            tvBridgeStatus?.setTextColor(0xFF2E7D32.toInt()) // 绿色
            tvBridgeStatus?.setBackgroundColor(0xFFE8F5E9.toInt()) // 浅绿背景
            btnDownloadBridge?.text = "✅ 语音引擎已安装"
            btnDownloadBridge?.isEnabled = false
            tvBridgeError?.visibility = android.view.View.GONE
        } else if (bridgeInstalled) {
            // 文件存在但加载失败
            tvBridgeStatus?.text = "⚠️ 语音引擎文件存在但加载失败"
            tvBridgeStatus?.setTextColor(0xFFE65100.toInt()) // 橙色
            tvBridgeStatus?.setBackgroundColor(0xFFFFF3E0.toInt()) // 浅橙背景
            btnDownloadBridge?.text = "🔄 重新下载语音引擎"
            btnDownloadBridge?.isEnabled = true
            tvBridgeError?.text = "加载错误: ${bridgeError ?: "未知"}"
            tvBridgeError?.visibility = android.view.View.VISIBLE
        } else {
            // 桥梁未安装
            tvBridgeStatus?.text = "❌ 语音引擎未安装（本地语音识别不可用）"
            tvBridgeStatus?.setTextColor(0xFFC62828.toInt()) // 红色
            tvBridgeStatus?.setBackgroundColor(0xFFFFEBEE.toInt()) // 浅红背景
            btnDownloadBridge?.text = "⬇ 下载语音引擎（桥梁）"
            btnDownloadBridge?.isEnabled = !isDownloading
            tvBridgeError?.visibility = android.view.View.GONE
        }
    }

    /** 下载桥梁插件 */
    private fun downloadBridge() {
        if (isDownloading) {
            Toast.makeText(activity, "正在下载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查网络
        val url = com.cesia.input.model.ModelRegistry.Bridge.getDownloadUrl()
        if (url.isBlank() || !url.startsWith("http")) {
            Toast.makeText(activity,
                "桥梁下载 URL 未配置，请检查 ModelRegistry.Bridge",
                Toast.LENGTH_LONG).show()
            return
        }

        isDownloading = true
        tvDownloadProgress?.visibility = android.view.View.VISIBLE
        pbDownload?.visibility = android.view.View.VISIBLE
        tvDownloadProgress?.text = "正在下载语音引擎（桥梁）..."
        btnDownloadBridge?.isEnabled = false

        val appCompat = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        appCompat.lifecycleScope.launch {
            val result = downloadManager.downloadBridge(force = true) { progress ->
                activity.runOnUiThread {
                    pbDownload?.progress = progress
                    tvDownloadProgress?.text = "下载语音引擎: $progress%"
                }
            }

            isDownloading = false

            activity.runOnUiThread {
                pbDownload?.visibility = android.view.View.GONE
                if (result.isSuccess) {
                    tvDownloadProgress?.text = "✅ 语音引擎下载完成，请重启输入法生效"
                    Toast.makeText(activity,
                        "语音引擎下载成功！请重启输入法后生效",
                        Toast.LENGTH_LONG).show()
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "请检查网络"
                    tvDownloadProgress?.text = "❌ 下载失败: $errMsg"
                    Toast.makeText(activity, "下载失败: $errMsg", Toast.LENGTH_LONG).show()
                }
                refreshBridgeStatus()
                refreshModelStatus()
            }
        }
    }

    /** 下载语音识别模型 */
    private fun downloadVoiceModel(tier: ModelInfo.Tier) {
        if (isDownloading) {
            Toast.makeText(activity, "正在下载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        tvDownloadProgress?.visibility = View.VISIBLE
        pbDownload?.visibility = View.VISIBLE
        tvDownloadProgress?.text = "正在下载语音识别模型..."

        val appCompat = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        appCompat.lifecycleScope.launch {
            val result = downloadManager.downloadByType(
                ModelInfo.ModelType.VOICE, tier
            ) { modelName, progress ->
                activity.runOnUiThread {
                    pbDownload?.progress = progress
                    tvDownloadProgress?.text = "下载 $modelName: $progress%"
                }
            }

            isDownloading = false

            activity.runOnUiThread {
                pbDownload?.visibility = View.GONE
                if (result.isSuccess) {
                    tvDownloadProgress?.text = "✅ 语音识别模型下载完成"
                    refreshModeUI()
                    refreshModelStatus()
                    Toast.makeText(activity,
                        "语音识别安装成功，已替换 Google 语音", Toast.LENGTH_SHORT).show()
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

    /** 下载 AI 润色模型 */
    private fun downloadAiModel(tier: ModelInfo.Tier) {
        if (isDownloading) {
            Toast.makeText(activity, "正在下载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        tvDownloadProgress?.visibility = View.VISIBLE
        pbDownload?.visibility = View.VISIBLE
        tvDownloadProgress?.text = "正在下载 AI 润色模型..."

        val appCompat = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        appCompat.lifecycleScope.launch {
            val result = downloadManager.downloadByType(
                ModelInfo.ModelType.AI, tier
            ) { modelName, progress ->
                activity.runOnUiThread {
                    pbDownload?.progress = progress
                    tvDownloadProgress?.text = "下载 $modelName: $progress%"
                }
            }

            isDownloading = false

            activity.runOnUiThread {
                pbDownload?.visibility = View.GONE
                if (result.isSuccess) {
                    tvDownloadProgress?.text = "✅ AI 润色模型下载完成"
                    refreshModelStatus()
                    Toast.makeText(activity, "AI 润色模型安装成功", Toast.LENGTH_SHORT).show()
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
