package com.cesia.input.ai

import android.content.Context
import android.util.Log
import com.cesia.input.engine.ai.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 AI 润色引擎 — 基于 llama.cpp + Qwen 3.5
 *
 * 与 PolishService（云端 OpenRouter）互补:
 * - 本地模式: 无需网络，隐私安全，使用 Qwen 3.5 GGUF 模型
 * - 云端模式: 使用 OpenRouter API（由 PolishService 处理）
 *
 * 使用方式:
 * 1. AIEngine(context)
 * 2. loadLocalModel() — 加载已安装的 GGUF 模型
 * 3. polish(text) — 润色文本
 * 4. release() — 释放资源
 */
class AIEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIEngine"
        private const val DEFAULT_MAX_TOKENS = 512
    }

    private val llamaEngine = LlamaEngine()
    private var modelLoaded = false
    private var currentModelPath: String? = null

    // ==================== 模型加载 ====================

    /**
     * 加载本地 GGUF 模型
     * @param modelPath GGUF 文件绝对路径
     * @param nGpuLayers GPU offload 层数（99=全部，0=纯CPU）
     * @return 是否成功
     */
    suspend fun loadLocalModel(modelPath: String, nGpuLayers: Int = 99): Boolean =
        withContext(Dispatchers.IO) {
            try {
                modelLoaded = llamaEngine.nativeInit(modelPath, nGpuLayers)
                if (modelLoaded) {
                    currentModelPath = modelPath
                    Log.i(TAG, "Llama model loaded: $modelPath (gpu_layers=$nGpuLayers)")
                } else {
                    Log.e(TAG, "Failed to load llama model: $modelPath")
                }
                modelLoaded
            } catch (e: Exception) {
                Log.e(TAG, "Error loading llama model", e)
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
    suspend fun polish(text: String, instruction: String = "润色"): String? =
        withContext(Dispatchers.IO) {
            if (!modelLoaded) {
                Log.w(TAG, "Model not loaded")
                return@withContext null
            }

            if (text.isBlank()) return@withContext ""

            try {
                val prompt = buildPolishPrompt(text, instruction)
                val result = llamaEngine.nativeGenerate(prompt, DEFAULT_MAX_TOKENS)
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
        return "<|im_start|>system\n" +
            "你是一个专业的文本润色助手。请根据用户的要求处理文本，只输出处理后的结果，不要解释。\n" +
            "<|im_end|>\n" +
            "<|im_start|>user\n" +
            "请对以下文本进行${instruction}：\n\n${text}\n" +
            "<|im_end|>\n" +
            "<|im_start|>assistant\n"
    }

    // ==================== 状态查询 ====================

    fun isModelLoaded(): Boolean = modelLoaded

    fun getCurrentModelPath(): String? = currentModelPath

    fun release() {
        llamaEngine.nativeFree()
        modelLoaded = false
        currentModelPath = null
    }
}
