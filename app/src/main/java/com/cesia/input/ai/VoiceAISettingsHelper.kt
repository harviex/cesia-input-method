package com.cesia.input.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.cesia.input.R
import com.cesia.input.model.ModelDownloadManager
import com.cesia.input.model.ModelInfo
import com.cesia.input.model.ModelManager
import com.cesia.input.model.ModelRegistry
import com.cesia.input.engine.ai.SherpaOnnxEngine
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

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
    val modelManager = ModelManager(activity)
    val downloadManager = ModelDownloadManager(activity)

    // 视图引用
    var etGroqKey: EditText? = null
    var btnDownloadVoice: Button? = null
    var btnDownloadAi: Button? = null

    private var isDownloading = false

    /** 初始化所有视图引用 */
    fun bindViews(
        etGroqKey: EditText?,
        btnDownloadVoice: Button?,
        btnDownloadAi: Button?
    ) {
        this.etGroqKey = etGroqKey
        this.btnDownloadVoice = btnDownloadVoice
        this.btnDownloadAi = btnDownloadAi
    }

    /** 加载已保存的设置 */
    fun loadSettings() {
        // 扫描已有的模型文件（兼容手动放置）
        val found = modelManager.scanExistingModels()
        if (found.isNotEmpty()) {
            Log.i("VoiceAISettings", "扫描发现已有模型: $found")
        }
        // Groq Key
        etGroqKey?.setText(prefs.getString("groq_api_key", "") ?: "")
        // 刷新 UI
        refreshModelStatus()
    }

    /** 保存设置 */
    fun saveSettings() {
        etGroqKey?.text?.toString()?.let { key ->
            prefs.edit().putString("groq_api_key", key).apply()
        }
    }

    /** 设置按钮监听 */
    fun setupListeners() {        // 下载语音识别模型（Zipformer 多文件）
        btnDownloadVoice?.setOnClickListener {
            val installedFile = modelManager.getInstalledVoiceModelFile()
            if (installedFile != null && installedFile.exists()) {
                // 已安装 → 显示实际大小
                val actualSize = if (installedFile.isDirectory) {
                    installedFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else {
                    installedFile.length()
                }
                Toast.makeText(activity,
                    "Zipformer 已安装（${ModelDownloadManager.Formatter.formatSize(actualSize)}）",
                    Toast.LENGTH_SHORT).show()
            } else {
                // 未安装 → 显示预估大小
                val totalBytes = 206L * 1024 * 1024  // 206MB 预估
                Toast.makeText(activity,
                    "将下载 Zipformer 语音识别模型（约 ${ModelDownloadManager.Formatter.formatSize(totalBytes)}，4个文件）",
                    Toast.LENGTH_SHORT).show()
                downloadVoiceModel()
            }
        }

        // 下载 AI 润色模型（默认 Qwen3.5-2B）
        btnDownloadAi?.setOnClickListener {
            val model = ModelRegistry.ALL_MODELS.find { it.id == "qwen35-2b-mnn" }
                ?: ModelRegistry.ALL_MODELS.find { it.type == ModelInfo.ModelType.AI }
            if (model == null) {
                Toast.makeText(activity, "没有可用的 AI 模型", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sizeStr = ModelDownloadManager.Formatter.formatSize(model.sizeBytes)
            Toast.makeText(activity,
                "将下载 AI 润色模型（$sizeStr）",
                Toast.LENGTH_SHORT).show()
            downloadAiModel(model)
        }

        // 语音识别下载按钮：已安装时长按卸载
        btnDownloadVoice?.setOnLongClickListener {
            val voiceInstalled = modelManager.getInstalledVoiceModelFile()
            if (voiceInstalled != null) {
                showUninstallDialog(
                    title = "卸载语音识别模型",
                    modelType = ModelInfo.ModelType.VOICE,
                    modelId = "sherpa-zipformer",
                    fallbackNote = "卸载后将回退到 Google 语音识别"
                )
                true
            } else {
                false
            }
        }

        // AI 润色下载按钮：已安装时长按卸载
        btnDownloadAi?.setOnLongClickListener {
            val aiInstalled = modelManager.getInstalledAiModelFile()
            if (aiInstalled != null) {
                val installedAiId = modelManager.installedAiModelId ?: "qwen35-2b-mnn"
                showUninstallDialog(
                    title = "卸载 AI 润色模型",
                    modelType = ModelInfo.ModelType.AI,
                    modelId = installedAiId,
                    fallbackNote = "卸载后将回退到云端润色"
                )
                true
            } else {
                false
            }
        }
    }

    /**
     * 显示详细的卸载确认弹窗（含模型名、路径、大小、文件列表）
     */
    private fun showUninstallDialog(
        title: String,
        modelType: ModelInfo.ModelType,
        modelId: String,
        fallbackNote: String
    ) {
        val modelInfo = ModelRegistry.getById(modelId)
        val installedFile = if (modelType == ModelInfo.ModelType.VOICE) {
            modelManager.getInstalledVoiceModelFile()
        } else {
            modelManager.getInstalledAiModelFile()
        } ?: return

        // 构建文件列表
        val fileItems = if (installedFile.isDirectory) {
            installedFile.listFiles()?.filter { it.isFile }?.sortedByDescending { it.length() } ?: emptyList()
        } else {
            listOf(installedFile)
        }
        val totalBytes = fileItems.sumOf { it.length() }
        val fileCount = fileItems.size

        // 模型名称
        val modelName = modelInfo?.name ?: modelId
        val bridgeStatus = if (modelType == ModelInfo.ModelType.VOICE) {
            val loaded = SherpaOnnxEngine.isLibraryLoaded()
            "Sherpa-onnx 库：${if (loaded) "已加载 ✅" else "未加载 ❌"}"
        } else {
            ""
        }

        // 构建显示文本
        val message = buildString {
            appendLine("模型：$modelName")
            appendLine("路径：${installedFile.absolutePath}")
            appendLine("大小：${ModelDownloadManager.Formatter.formatSize(totalBytes)}")
            appendLine("文件：$fileCount/${fileCount} 已下载")
            appendLine()
            for (file in fileItems) {
                val sizeStr = ModelDownloadManager.Formatter.formatSize(file.length())
                appendLine("├ ${file.name}  $sizeStr ✅")
            }
            if (bridgeStatus.isNotEmpty()) {
                appendLine()
                append(bridgeStatus)
            }
            appendLine()
            appendLine("确定要卸载${if (modelType == ModelInfo.ModelType.VOICE) "语音识别" else "AI 润色"}模型吗？")
            append(fallbackNote)
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("卸载") { _, _ ->
                val deleted = downloadManager.deleteModel(modelId)
                refreshModelStatus()
                Toast.makeText(
                    activity,
                    if (deleted) "已卸载${if (modelType == ModelInfo.ModelType.VOICE) "语音识别" else "AI 润色"}模型" else "卸载失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 刷新模型安装状态（仅更新按钮文字） */
    private fun refreshModelStatus() {
        // 更新语音识别下载按钮
        val voiceInstalled = modelManager.getInstalledVoiceModelFile()
        if (voiceInstalled != null) {
            btnDownloadVoice?.text = "✅ 语音识别已安装"
            btnDownloadVoice?.isEnabled = true
            btnDownloadVoice?.setTextColor(0xFF888888.toInt())
        } else {
            btnDownloadVoice?.text = "⬇ 下载语音识别"
            btnDownloadVoice?.isEnabled = !isDownloading
            btnDownloadVoice?.setTextColor(0xFF4488FF.toInt())
        }

        // 更新 AI 润色下载按钮
        val aiInstalled = modelManager.getInstalledAiModelFile()
        if (aiInstalled != null) {
            btnDownloadAi?.text = "✅ 语音润色已安装"
            btnDownloadAi?.isEnabled = true
            btnDownloadAi?.setTextColor(0xFF888888.toInt())
        } else {
            btnDownloadAi?.text = "⬇ 下载语音润色"
            btnDownloadAi?.isEnabled = !isDownloading
            btnDownloadAi?.setTextColor(0xFF4488FF.toInt())
        }
    }



    /** 下载 Zipformer 语音识别模型（多文件） */
    private fun downloadVoiceModel() {
        if (isDownloading) {
            Toast.makeText(activity, "正在下载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        btnDownloadVoice?.text = "下载中..."
        btnDownloadVoice?.isEnabled = false
        Log.i("VoiceAISettings", "downloadVoiceModel: starting")

        val appCompat = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        appCompat.lifecycleScope.launch {
            Log.i("VoiceAISettings", "downloadZipformer: starting download")
            val result = downloadManager.downloadZipformer { fileName, overallPercent, downloadedBytes, totalBytes ->
                Log.i("VoiceAISettings", "downloadZipformer: onProgress $fileName $overallPercent%")
                activity.runOnUiThread {
                    val pctStr = String.format("%.1f%%", overallPercent)
                    btnDownloadVoice?.text = "下载中... $fileName $pctStr"
                }
            }

            isDownloading = false

            activity.runOnUiThread {
                if (result.isSuccess) {
                    refreshModelStatus()
                    Toast.makeText(activity,
                        "Zipformer 安装成功，支持流式语音识别（边说边出字）", Toast.LENGTH_SHORT).show()
                } else {
                    refreshModelStatus()
                    Toast.makeText(activity,
                        "下载失败: ${result.exceptionOrNull()?.message ?: "请检查网络"}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 下载 AI 润色模型 */
    private fun downloadAiModel(model: ModelInfo) {
        if (isDownloading) {
            Toast.makeText(activity, "正在下载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        btnDownloadAi?.text = "下载中..."
        btnDownloadAi?.isEnabled = false
        Log.i("VoiceAISettings", "downloadAiModel: starting AI model download")

        val appCompat = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        appCompat.lifecycleScope.launch {
            Log.i("VoiceAISettings", "downloadAiModel: launching coroutine")
            val result = downloadManager.downloadAiModel(model) { modelName, percent, downloadedBytes, totalBytes ->
                Log.i("VoiceAISettings", "downloadAiModel: onProgress $modelName $percent% ($downloadedBytes/$totalBytes)")
                activity.runOnUiThread {
                    val pctStr = String.format("%.1f%%", percent)
                    btnDownloadAi?.text = "下载中... $modelName $pctStr"
                }
            }

            isDownloading = false

            activity.runOnUiThread {
                if (result.isSuccess) {
                    refreshModelStatus()
                    Toast.makeText(activity, "AI 润色模型安装成功", Toast.LENGTH_SHORT).show()
                } else {
                    refreshModelStatus()
                    Toast.makeText(activity,
                        "下载失败: ${result.exceptionOrNull()?.message ?: "请检查网络"}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
