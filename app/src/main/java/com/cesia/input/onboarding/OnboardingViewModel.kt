package com.cesia.input.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.cesia.input.engine.PinyinDictManager
import com.cesia.input.model.ModelDownloadManager
import com.cesia.input.model.ModelInfo
import com.cesia.input.model.ModelRegistry
import kotlinx.coroutines.*

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===== 步骤 2：下载聚合状态 =====
    enum class DownloadItem { DICT, VOICE, AI }

    data class DownloadProgress(
        val item: DownloadItem,
        val percent: Int,           // 0-100
        val downloaded: Long,
        val total: Long,
        val status: String,         // "下载中..." / "解压中..." / "完成" / "失败: ..."
        val isActive: Boolean       // 当前正在下载的项
    )

    val dictProgress = MutableLiveData<DownloadProgress>()
    val voiceProgress = MutableLiveData<DownloadProgress>()
    val aiProgress = MutableLiveData<DownloadProgress>()

    // 用户勾选状态（默认：词库必选、语音模型默认勾选、AI模型默认不选）
    var downloadDict = true
    var downloadVoice = true
    var downloadAi = false

    // 完成标记
    val dictCompleted = MutableLiveData<Boolean>()
    val voiceCompleted = MutableLiveData<Boolean>()
    val aiCompleted = MutableLiveData<Boolean>()

    // ===== 步骤 3：云端配置数据 =====
    var openRouterKey = ""
    var tavilyKey = ""
    var newsSourceId = 0

    private val dictManager = PinyinDictManager(getApplication())
    private val modelManager = ModelDownloadManager(getApplication())

    /** 开始下载：词库必下，模型按勾选 */
    fun startDownloads() {
        if (downloadDict) downloadDict()
        if (downloadVoice) downloadVoiceModel()
        if (downloadAi) downloadAiModel()
    }

    private fun downloadDict() {
        dictProgress.postValue(DownloadProgress(DownloadItem.DICT, 0, 0, 0, "正在连接...", true))
        scope.launch {
            dictManager.downloadFullDict(
                onProgress = { pct, dl, total, msg ->
                    dictProgress.postValue(DownloadProgress(DownloadItem.DICT, pct, dl, total, msg, true))
                },
                onComplete = { success, msg ->
                    dictProgress.postValue(DownloadProgress(DownloadItem.DICT,
                        if (success) 100 else 0, 0, 0, msg, false))
                    dictCompleted.postValue(success)
                    // 词库完成后，若有模型在队列，启动它们
                    if (success) {
                        if (downloadVoice) downloadVoiceModel()
                        if (downloadAi) downloadAiModel()
                    }
                }
            )
        }
    }

    private fun downloadVoiceModel() {
        val info = ModelRegistry.getById("sherpa-zipformer")!!
        voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 0, 0, info.sizeBytes, "正在连接...", true))
        scope.launch {
            val result = modelManager.downloadZipformer(
                onProgress = { fileName, pct, dl, total ->
                    voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, pct.toInt(), dl, total, "下载中: $fileName", true))
                }
            )
            if (result.isSuccess) {
                voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 100, info.sizeBytes, info.sizeBytes, "完成", false))
                voiceCompleted.postValue(true)
            } else {
                voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 0, 0, 0, "失败: ${result.exceptionOrNull()?.message ?: "未知错误"}", false))
                voiceCompleted.postValue(false)
            }
        }
    }

    private fun downloadAiModel() {
        // 默认下载 Qwen3.5-2B（更新、指令遵循更强）
        val info = ModelRegistry.getById("qwen35-2b-mnn")!!
        aiProgress.postValue(DownloadProgress(DownloadItem.AI, 0, 0, info.sizeBytes, "正在连接...", true))
        scope.launch {
            val result = modelManager.downloadMnnModel(
                modelId = "qwen35-2b-mnn",
                onProgress = { fileName, pct, dl, total ->
                    aiProgress.postValue(DownloadProgress(DownloadItem.AI, pct.toInt(), dl, total, "下载中: $fileName", true))
                }
            )
            if (result.isSuccess) {
                aiProgress.postValue(DownloadProgress(DownloadItem.AI, 100, info.sizeBytes, info.sizeBytes, "完成", false))
                aiCompleted.postValue(true)
            } else {
                aiProgress.postValue(DownloadProgress(DownloadItem.AI, 0, 0, 0, "失败: ${result.exceptionOrNull()?.message ?: "未知错误"}", false))
                aiCompleted.postValue(false)
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}