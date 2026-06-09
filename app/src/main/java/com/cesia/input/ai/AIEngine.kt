package com.cesia.input.ai

import android.content.Context
import android.util.Log
import com.cesia.input.engine.ai.MNNEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 AI 润色引擎 — 基于 MNN + Qwen 3.5
 *
 * 与 PolishService（云端 OpenRouter）互补:
 * - 本地模式: 无需网络，隐私安全，使用 Qwen 3.5 MNN 模型
 * - 云端模式: 使用 OpenRouter API（由 PolishService 处理）
 *
 * 使用方式:
 * 1. AIEngine(context)
 * 2. loadLocalModel() — 加载已安装的 MNN 模型
 * 3. polish(text) — 润色文本
 * 4. release() — 释放资源
 */
class AIEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIEngine"
        private const val DEFAULT_MAX_TOKENS = 32  // 润色任务简短输出，32足够
        private const val LOCAL_POLISH_TIMEOUT_MS = 30000L  // 30 秒超时
    }

    private val mnnEngine = MNNEngine()
    private var modelLoaded = false
    private var currentModelPath: String? = null

    // ==================== 模型加载 ====================

    /**
     * 加载本地 MNN 模型
     * @param configPath config.json 的绝对路径（MNN 模型目录下的 config.json）
     * @return 是否成功
     */
    suspend fun loadLocalModel(configPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                modelLoaded = mnnEngine.nativeInit(configPath)
                if (modelLoaded) {
                    currentModelPath = configPath
                    Log.i(TAG, "MNN model loaded: $configPath")
                } else {
                    Log.e(TAG, "Failed to load MNN model: $configPath")
                    val log = mnnEngine.nativeGetLog()
                    if (log.isNotEmpty()) {
                        Log.e(TAG, "MNN log: $log")
                    }
                }
                modelLoaded
            } catch (e: Exception) {
                Log.e(TAG, "Error loading MNN model", e)
                false
            }
        }

    // ==================== 润色 API ====================

    /**
     * 润色文本（本地 LLM）
     * @param text 原始文本
     * @param instruction 润色指令（如"扩写"、"缩句"、"转英文"等）
     * @return 润色后的文本，失败返回 null
     */
    suspend fun polish(text: String, instruction: String = "润色", customSystemPrompt: String? = null): String? =
        withContext(Dispatchers.IO) {
            if (!modelLoaded) {
                Log.w(TAG, "Model not loaded")
                return@withContext null
            }

            if (text.isBlank()) return@withContext ""

            try {
                val prompt = if (customSystemPrompt != null) {
                    "${customSystemPrompt}\n\n用户输入：${text}\n"
                } else {
                    buildPolishPrompt(text, instruction)
                }
                val result = mnnEngine.nativeGenerate(prompt, DEFAULT_MAX_TOKENS)
                result.ifBlank { null }
            } catch (e: Exception) {
                Log.e(TAG, "Polish error", e)
                null
            }
        }

    /**
     * 构建润色 prompt（Qwen 3.5 Instruct 格式）
     */
    private fun buildPolishPrompt(text: String, instruction: String): String {
        // 0.8B 模型指令遵循力弱，prompt 极简化为一句
        return "只输出${instruction}结果，不解释不重复：${text}\n"
    }

    // ==================== 通用生成 API ====================

    /**
     * 同步生成文本（阻塞直到完成）
     * 用于翻译等同传任务
     * @param prompt 输入 prompt
     * @param maxTokens 最大生成 token 数
     * @return 生成的文本，失败返回 null
     */
    fun syncGenerate(prompt: String, maxTokens: Int = 256): String? {
        if (!modelLoaded) {
            Log.w(TAG, "syncGenerate: Model not loaded")
            return null
        }
        return try {
            val result = mnnEngine.nativeGenerate(prompt, maxTokens)
            result.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "syncGenerate error", e)
            null
        }
    }

    // ==================== 状态查询 ====================

    fun isModelLoaded(): Boolean = modelLoaded

    fun getCurrentModelPath(): String? = currentModelPath

    fun release() {
        mnnEngine.nativeFree()
        modelLoaded = false
        currentModelPath = null
    }
}
