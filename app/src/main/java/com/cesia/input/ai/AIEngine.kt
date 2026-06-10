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
        private const val LOCAL_POLISH_TIMEOUT_MS = 30000L  // 30 秒超时
        // 默认润色 prompt（与云端 PolishService 共用同一套）
        const val DEFAULT_POLISH_PROMPT = """你是一个文本润色与输入排版高手。请将输入的口语文字处理为通顺的书面文字，并严格执行以下规则：\n严禁删减核心信息，严禁随意扩写。仅修正错别字、口语和语序，加入标点。只输出润色排版后的纯文本。禁止解释，禁止添加任何前缀（如"润色后："）或后缀。如果用户输入的内容包含多个观点、步骤或长篇大论，请自动通过"换行分段"或使用"* "进行分点陈列。"""
    }

    private val mnnEngine = MNNEngine()
    private var modelLoaded = false
    private var currentModelPath: String? = null

    // ==================== Prompt 管理 ====================

    /** 用户自定义润色 prompt，null 使用默认 */
    var customPolishPrompt: String? = null

    private fun getPolishSystemPrompt(): String {
        return customPolishPrompt ?: DEFAULT_POLISH_PROMPT
    }

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
    suspend fun polish(text: String, instruction: String = "润色"): String? =
        withContext(Dispatchers.IO) {
            if (!modelLoaded) {
                Log.w(TAG, "Model not loaded")
                return@withContext null
            }

            if (text.isBlank()) return@withContext ""

            try {
                // 重置对话历史，避免上下文污染导致重复
                mnnEngine.nativeReset()

                // 动态计算 maxTokens：原文长度 * 1.2（留 20% 余量给标点和修正），最少 32，最多 256
                val maxTokens = (text.length * 1.2).toInt().coerceIn(32, 256)
                Log.d(TAG, "Polish: textLen=${text.length}, maxTokens=$maxTokens")

                val prompt = buildPolishPrompt(text, instruction)
                Log.d(TAG, "Polish prompt: ${prompt.take(200)}")

                // 推理前主动 GC，释放 Java 堆内存，防止 JNI 返回字符串时 OOM
                System.gc()
                val result = mnnEngine.nativeGenerate(prompt, maxTokens)
                Log.d(TAG, "Polish raw result: ${result.take(200)}")

                val cleaned = postProcessPolishResult(text, result)
                Log.d(TAG, "Polish cleaned: ${cleaned.take(200)}")
                cleaned.ifBlank { null }
            } catch (e: Exception) {
                Log.e(TAG, "Polish error", e)
                null
            }
        }

    /**
     * 润色结果后处理：检测并去除续写内容
     */
    private fun postProcessPolishResult(original: String, raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw.trim()

        // 续写检测
        val continuationPatterns = listOf(
            "这是一个问题", "所以请", "请注意", "需要说明", "我来解释",
            "我来回答", "这个问题的", "关于这个问题", "简单来说",
            "首先，", "其次，", "最后，", "总之，", "综上，"
        )
        for (pattern in continuationPatterns) {
            val idx = text.indexOf(pattern)
            if (idx > 0) {
                Log.d(TAG, "postProcess: 检测到续写标志 '$pattern'，截断到位置 $idx")
                text = text.substring(0, idx).trim()
                break
            }
        }

        return text
    }

    /**
     * 构建润色 prompt（本地 MNN 格式）
     * C++ 层已使用 ChatMessages（system+user 分离），这里只返回原文
     */
    private fun buildPolishPrompt(text: String, instruction: String): String {
        return text
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
