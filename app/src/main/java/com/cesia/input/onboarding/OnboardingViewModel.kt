package com.cesia.input.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cesia.input.engine.PinyinDictManager
import com.cesia.input.model.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    /** 下载项枚举 */
    enum class DownloadItem { DICT, VOICE, AI }

    /** 下载进度数据类 */
    data class DownloadProgress(
        val item: DownloadItem,
        val percent: Int,
        val status: String
    )

    // 用户选择
    var downloadVoice = true
    var downloadAi = true

    // 云端配置
    var openRouterKey = ""
    var tavilyKey = ""
    var newsSourceId = 0

    // 进度 LiveData
    val dictProgress = MutableLiveData<DownloadProgress>()
    val voiceProgress = MutableLiveData<DownloadProgress>()
    val aiProgress = MutableLiveData<DownloadProgress>()

    // 完成状态 LiveData
    val dictCompleted = MutableLiveData<Boolean>()
    val voiceCompleted = MutableLiveData<Boolean>()
    val aiCompleted = MutableLiveData<Boolean>()

    // Manager 实例
    private val dictManager = PinyinDictManager(getApplication<Application>())
    private val downloadManager = ModelDownloadManager(getApplication<Application>())

    /** 开始下载流程 */
    fun startDownloads() {
        // 重置进度
        dictProgress.postValue(DownloadProgress(DownloadItem.DICT, 0, "准备中..."))
        voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 0, "等待中..."))
        aiProgress.postValue(DownloadProgress(DownloadItem.AI, 0, "等待中..."))
        dictCompleted.postValue(false)
        voiceCompleted.postValue(false)
        aiCompleted.postValue(false)

        // 先下载词库（必选）
        downloadDict()
    }

    private fun downloadDict() {
        viewModelScope.launch(Dispatchers.IO) {
            dictProgress.postValue(DownloadProgress(DownloadItem.DICT, 10, "下载中..."))
            
            // PinyinDictManager.downloadFullDict 使用 Thread 内部回调，需要桥接
            dictManager.downloadFullDict(
                onProgress = { percent, downloadedBytes, totalBytes, message ->
                    dictProgress.postValue(DownloadProgress(DownloadItem.DICT, percent, message))
                },
                onComplete = { success, message ->
                    dictCompleted.postValue(success)
                    if (success) {
                        dictProgress.postValue(DownloadProgress(DownloadItem.DICT, 100, "完成"))
                        // 词库完成后，下载语音模型（如果勾选）
                        if (downloadVoice) {
                            downloadVoiceModel()
                        } else {
                            voiceCompleted.postValue(true)
                            voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 100, "已跳过"))
                            checkAiDownload()
                        }
                    } else {
                        dictProgress.postValue(DownloadProgress(DownloadItem.DICT, 0, "下载失败: $message"))
                    }
                }
            )
        }
    }

    private fun downloadVoiceModel() {
        viewModelScope.launch(Dispatchers.IO) {
            voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 10, "下载中..."))
            
            // downloadZipformer 是 suspend 函数，在 coroutine 中直接调用
            val result = withContext(Dispatchers.IO) {
                downloadManager.downloadZipformer { fileName, percent, downloadedBytes, totalBytes ->
                    voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, percent.toInt(), "下载中... ${percent.toInt()}%"))
                }
            }
            
            val success = result.isSuccess
            voiceCompleted.postValue(success)
            if (success) {
                voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 100, "完成"))
            } else {
                voiceProgress.postValue(DownloadProgress(DownloadItem.VOICE, 0, "下载失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"))
            }
            checkAiDownload()
        }
    }

    private fun checkAiDownload() {
        if (downloadAi) {
            downloadAiModel()
        } else {
            aiCompleted.postValue(true)
            aiProgress.postValue(DownloadProgress(DownloadItem.AI, 100, "已跳过"))
        }
    }

    private fun downloadAiModel() {
        viewModelScope.launch(Dispatchers.IO) {
            aiProgress.postValue(DownloadProgress(DownloadItem.AI, 10, "下载中..."))
            
            val result = withContext(Dispatchers.IO) {
                downloadManager.downloadMnnModel("qwen35-2b-mnn") { fileName, percent, downloadedBytes, totalBytes ->
                    aiProgress.postValue(DownloadProgress(DownloadItem.AI, percent.toInt(), "下载中... ${percent.toInt()}%"))
                }
            }
            
            val success = result.isSuccess
            aiCompleted.postValue(success)
            if (success) {
                aiProgress.postValue(DownloadProgress(DownloadItem.AI, 100, "完成"))
            } else {
                aiProgress.postValue(DownloadProgress(DownloadItem.AI, 0, "下载失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"))
            }
        }
    }
}