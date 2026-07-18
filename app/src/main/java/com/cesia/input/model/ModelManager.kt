package com.cesia.input.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * 本地模型管理器
 * 管理已下载模型的安装状态、路径、启用/禁用
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS_NAME = "cesia_models"
        private const val KEY_INSTALLED_VOICE_MODEL = "installed_voice_model"
        private const val KEY_INSTALLED_AI_MODEL = "installed_ai_model"
        private const val KEY_USE_GPU = "use_gpu"

        /** 本地模型存储目录 */
        const val MODELS_DIR = "local_models"

        /** 模型版本号 — 变更时清除旧模型强制重新下载 */
        const val KEY_MODEL_VERSION = "model_version"
        const val CURRENT_MODEL_VERSION = 4  // 1=双语zipformer, 2=中文zipformer, 3=修复下载URL, 4=回退双语+修复增量逻辑
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 模型存储根目录 */
    val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    /**
     * 扫描 modelsDir 中已存在的模型文件，自动注册到 SharedPreferences
     * 用于兼容手动放入模型文件的情况
     * @return 新发现的模型 ID 列表
     */
    fun scanExistingModels(): List<String> {
        // 模型版本检查：版本不匹配时仅清除安装记录，不删除文件
        val savedVersion = prefs.getInt(KEY_MODEL_VERSION, 0)
        if (savedVersion != CURRENT_MODEL_VERSION) {
            Log.i(TAG, "模型版本变更: $savedVersion -> $CURRENT_MODEL_VERSION, 清除安装记录")
            installedVoiceModelId = null
            installedAiModelId = null
            prefs.edit().putInt(KEY_MODEL_VERSION, CURRENT_MODEL_VERSION).apply()
        }
        val found = mutableListOf<String>()
        if (!modelsDir.exists()) return found
        val files = modelsDir.listFiles() ?: return found
        for (file in files) {
            // 跳过临时文件
            if (file.name.endsWith(".tmp")) continue
            // 在 ModelRegistry 中查找匹配的文件名（支持单文件和多文件目录）
            val matched = ModelRegistry.ALL_MODELS.find { it.fileName == file.name }
            if (matched != null && file.exists()) {
                val alreadyRegistered = when (matched.type) {
                    ModelInfo.ModelType.VOICE -> installedVoiceModelId == matched.id
                    ModelInfo.ModelType.AI -> installedAiModelId == matched.id
                    else -> false
                }
                if (!alreadyRegistered) {
                    markInstalled(matched.id, matched.type)
                    found.add(matched.id)
                    val size = if (file.isDirectory) {
                        file.listFiles()?.sumOf { it.length() } ?: 0
                    } else {
                        file.length()
                    }
                    Log.i(TAG, "扫描发现模型: ${matched.id} (${file.name}, $size bytes)")
                }
            }
        }
        return found
    }

    // ==================== 已安装模型 ====================

    /** 当前安装的语音模型 ID */
    var installedVoiceModelId: String?
        get() = prefs.getString(KEY_INSTALLED_VOICE_MODEL, null)
        set(value) = prefs.edit().putString(KEY_INSTALLED_VOICE_MODEL, value).apply()

    /** 当前安装的 AI 模型 ID */
    var installedAiModelId: String?
        get() = prefs.getString(KEY_INSTALLED_AI_MODEL, null)
        set(value) = prefs.edit().putString(KEY_INSTALLED_AI_MODEL, value).apply()

    /** 是否使用 GPU 加速（默认 false，大多数 Android 设备不支持 Vulkan） */
    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_GPU, value).apply()

    // ==================== 模型状态查询 ====================

    /** 某个模型是否已安装 */
    fun isModelInstalled(modelId: String): Boolean {
        val info = ModelRegistry.getById(modelId) ?: return false
        return File(modelsDir, info.fileName).exists()
    }

    /** 获取已安装语音模型的本地文件/目录，null 表示未安装 */
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

    /** 语音模型是否已安装 */
    fun hasVoiceModel(): Boolean = installedVoiceModelId != null && isModelInstalled(installedVoiceModelId!!)

    /** AI 模型是否已安装 */
    fun hasAiModel(): Boolean = installedAiModelId != null && isModelInstalled(installedAiModelId!!)

    /** 已安装模型列表 */
    fun getInstalledModelIds(): List<String> {
        return ModelRegistry.ALL_MODELS.filter { isModelInstalled(it.id) }.map { it.id }
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
            else -> { /* no-op */ }
        }
    }

    /** Groq API Key */
    fun getGroqApiKey(): String? {
        val prefs = context.getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
        return prefs.getString("groq_api_key", null)
    }
    fun getMissingModels(type: ModelInfo.ModelType? = null): List<ModelInfo> {
        val models = if (type != null) {
            ModelRegistry.ALL_MODELS.filter { it.type == type }
        } else {
            ModelRegistry.ALL_MODELS
        }
        return models.filter { !isModelInstalled(it.id) }
    }

    /** 仅清除手机AI模型文件与记录（不影响语音模型） */
    fun clearAiModel() {
        getInstalledAiModelFile()?.delete()
        installedAiModelId = null
        Log.i(TAG, "已清除手机AI模型文件")
    }

    /**
     * 清除所有已下载的模型文件
     */
    private fun clearAllModels() {
        if (modelsDir.exists() && modelsDir.isDirectory) {
            modelsDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    file.listFiles()?.forEach { it.delete() }
                }
                file.delete()
            }
            Log.i(TAG, "已清除所有模型文件: ${modelsDir.absolutePath}")
        }
        // 清除已安装记录
        installedVoiceModelId = null
        installedAiModelId = null
    }
}
