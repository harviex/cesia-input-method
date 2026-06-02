package com.cesia.input.model

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 本地模型管理器
 * 管理已下载模型的安装状态、路径、启用/禁用
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "cesia_models"
        private const val KEY_INSTALLED_VOICE_MODEL = "installed_voice_model"
        private const val KEY_INSTALLED_AI_MODEL = "installed_ai_model"
        private const val KEY_USE_GPU = "use_gpu"

        /** 本地模型存储目录 */
        const val MODELS_DIR = "local_models"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 模型存储根目录 */
    val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    // ==================== 已安装模型 ====================

    /** 当前安装的语音模型 ID */
    var installedVoiceModelId: String?
        get() = prefs.getString(KEY_INSTALLED_VOICE_MODEL, null)
        set(value) = prefs.edit().putString(KEY_INSTALLED_VOICE_MODEL, value).apply()

    /** 当前安装的 AI 模型 ID */
    var installedAiModelId: String?
        get() = prefs.getString(KEY_INSTALLED_AI_MODEL, null)
        set(value) = prefs.edit().putString(KEY_INSTALLED_AI_MODEL, value).apply()

    /** 是否使用 GPU 加速 */
    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_GPU, value).apply()

    // ==================== 模型状态查询 ====================

    /** 获取已安装语音模型的本地文件，null 表示未安装 */
    fun getInstalledVoiceModelFile(): File? {
        val modelId = installedVoiceModelId ?: return null
        val info = ModelRegistry.getById(modelId) ?: return null
        val file = File(modelsDir, info.fileName)
        return if (file.exists()) file else null
    }

    /** 获取已安装 AI 模型的本地文件，null 表示未安装 */
    fun getInstalledAiModelFile(): File? {
        val modelId = installedAiModelId ?: return null
        val info = ModelRegistry.getById(modelId) ?: return null
        val file = File(modelsDir, info.fileName)
        return if (file.exists()) file else null
    }

    /** 某个模型是否已安装 */
    fun isModelInstalled(modelId: String): Boolean {
        val info = ModelRegistry.getById(modelId) ?: return false
        return File(modelsDir, info.fileName).exists()
    }

    /** 已安装模型列表 */
    fun getInstalledModelIds(): List<String> {
        return ModelRegistry.ALL_MODELS.filter { isModelInstalled(it.id) }.map { it.id }
    }

    /** 语音模型是否已安装（任意一个） */
    fun hasVoiceModel(): Boolean {
        return ModelRegistry.ALL_MODELS
            .filter { it.type == ModelInfo.ModelType.VOICE }
            .any { isModelInstalled(it.id) }
    }

    /** AI 模型是否已安装（任意一个） */
    fun hasAiModel(): Boolean {
        return ModelRegistry.ALL_MODELS
            .filter { it.type == ModelInfo.ModelType.AI }
            .any { isModelInstalled(it.id) }
    }

    /** 全部本地模型已安装（语音 + AI 各至少一个） */
    fun isLocalFullySetup(): Boolean = hasVoiceModel() && hasAiModel()

    /** 获取模型本地文件路径 */
    fun getModelFile(modelId: String): File? {
        val info = ModelRegistry.getById(modelId) ?: return null
        val file = File(modelsDir, info.fileName)
        return if (file.exists()) file else null
    }

    /** 标记模型已安装 */
    fun markInstalled(modelId: String, type: ModelInfo.ModelType) {
        when (type) {
            ModelInfo.ModelType.VOICE -> installedVoiceModelId = modelId
            ModelInfo.ModelType.AI -> installedAiModelId = modelId
        }
    }

    /** 获取缺额（已选 tier 但未安装的模型列表） */
    fun getMissingModels(tier: ModelInfo.Tier): List<ModelInfo> {
        return ModelRegistry.ALL_MODELS
            .filter { it.tier == tier }
            .filter { !isModelInstalled(it.id) }
    }

    /** 某个 tier 下所有模型是否都已安装 */
    fun isTierInstalled(tier: ModelInfo.Tier): Boolean {
        return ModelRegistry.ALL_MODELS
            .filter { it.tier == tier }
            .all { isModelInstalled(it.id) }
    }
}
