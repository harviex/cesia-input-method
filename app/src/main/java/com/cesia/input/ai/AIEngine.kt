package com.cesia.input.ai

import android.content.Context
import android.util.Log
import com.cesia.input.engine.ai.MNNEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        const val DEFAULT_POLISH_PROMPT = """你是一个文本润色与输入排版高手。请将输入的口语文字处理为通顺的书面文字，并严格执行以下规则：
1. 去掉所有语气词（嗯、啊、呃、哈、呀、哇、哎、唉、哼、嘿、呵、哦、噢、喔、呦、吁、喂、嗯啊、那个、这个、就是、然后呢、所以说、反正、其实、你知道吧之类的口语填充词）
2. 去掉重复和冗余的词
3. 严禁删减核心信息，严禁随意扩写
4. 仅修正错别字、口语和语序，加入适当的标点
5. 使语句通顺自然，保持原意不变
6. 只输出润色排版后的纯文本，禁止解释，禁止添加任何前缀（如"润色后："）或后缀
7. 如果内容包含多个观点、步骤或长篇大论，请自动通过换行分段或使用"* "进行分点陈列"""
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
                // 0. 用 APK assets 中的 config.json 覆盖本地版本（确保参数如 temperature 等是最新的）
                try {
                    val configFile = File(configPath)
                    val modelDir = configFile.parentFile
                    // 确定 assets 中的 config 路径：模型目录名即为 assets 子目录名
                    val assetsConfigName = if (modelDir != null) "${modelDir.name}/config.json" else "qwen35-2b-mnn/config.json"
                    context.assets.open(assetsConfigName).use { input ->
                        val content = input.readBytes().toString(Charsets.UTF_8)
                        configFile.writeText(content)
                        Log.i(TAG, "loadLocalModel: config.json replaced from assets ($assetsConfigName)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "loadLocalModel: failed to copy config.json from assets, using existing file", e)
                }

                // 修改 config.json：修复格式问题并添加必要参数
                try {
                    val configFile = File(configPath)
                    if (configFile.exists()) {
                        var json = configFile.readText()

                        // 1. 移除 mllm 配置（Qwen3.5 纯文本推理不需要）
                        val mllmMatch = Regex("\"mllm\"\\s*:\\s*\\{[^}]*\\}").find(json)
                        if (mllmMatch != null) {
                            json = json.removeRange(mllmMatch.range)
                            Log.i(TAG, "loadLocalModel: removed mllm config")
                        }

                        // 2. 修复多余逗号（如 "min_p": 0, 后面的逗号）
                        // 先修复 ",," 模式
                        json = json.replace(",,", ",")
                        // 再修复逗号后跟 } 的模式
                        json = json.replace(Regex(",\\s*\\}"), "}")
                        // 最后修复逗号后跟 { 的模式（不应该有）
                        json = json.replace(Regex(",\\s*\\{"), "{")

                        // 3. 添加 hidden_size=2048（Qwen3.5-2B 实际架构参数）
                        if (!json.contains("\"hidden_size\"")) {
                            json = json.replaceFirst("\\{", "{\n    \"hidden_size\": 2048,")
                            Log.i(TAG, "loadLocalModel: added hidden_size=2048")
                        } else {
                            // 如果已有 hidden_size，修正为正确的 2048
                            json = json.replace(Regex("\"hidden_size\"\\s*:\\s*\\d+"), "\"hidden_size\": 2048")
                            Log.i(TAG, "loadLocalModel: corrected hidden_size to 2048")
                        }

                        // 4. 关闭 thinking（减少推理开销）
                        json = json.replace(Regex("\"enable_thinking\"\\s*:\\s*true"), "\"enable_thinking\": false")

                        // 5. 对齐云端采样参数：temperature 1.0 -> 0.3，penalty 1.1 -> 1.2
                        json = json.replace(Regex("\"temperature\"\\s*:\\s*[\\d.]+"), "\"temperature\": 0.3")
                        json = json.replace(Regex("\"penalty\"\\s*:\\s*[\\d.]+"), "\"penalty\": 1.2")
                        Log.i(TAG, "loadLocalModel: sampler params aligned (temp=0.3, penalty=1.2)")

                        // 6. 使用 CPU 后端（Vulkan 后端对 Qwen3.5 算子支持不完整，推理时 fallback 到 CPU）
                        json = json.replace(Regex("\"backend_type\"\\s*:\\s*\"[^\"]+\""), "\"backend_type\": \"cpu\"")
                        Log.i(TAG, "loadLocalModel: backend_type set to cpu")

                        // 7. 固定 4 线程（实测 8 线程反而更慢，4 线程最优）
                        json = json.replace(Regex("\"thread_num\"\\s*:\\s*\\d+"), "\"thread_num\": 4")
                        Log.i(TAG, "loadLocalModel: thread_num fixed to 4")

                        configFile.writeText(json)
                        Log.i(TAG, "loadLocalModel: config.json patched successfully")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "loadLocalModel: failed to modify config.json", e)
                }
                Log.i(TAG, "loadLocalModel: calling nativeInit with $configPath")
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
     * 用自定义 prompt 执行润色（语音命令专用）
     * @param prompt 完整的 prompt（包含指令和原文）
     * @return 润色后的文本，失败返回 null
     */
    suspend fun polishWithPrompt(prompt: String): String? =
        withContext(Dispatchers.IO) {
            if (!modelLoaded) {
                Log.w(TAG, "polishWithPrompt: Model not loaded")
                return@withContext null
            }
            try {
                mnnEngine.nativeReset()
                // prompt 已包含原文，按 prompt 长度动态计算 maxTokens
                val maxTokens = (prompt.length * 2.0).toInt().coerceIn(64, 4096)
                Log.d(TAG, "polishWithPrompt: promptLen=${prompt.length}, maxTokens=$maxTokens")
                System.gc()
                val result = mnnEngine.nativeGenerate(prompt, maxTokens)
                Log.d(TAG, "polishWithPrompt raw result: ${result.take(200)}")
                result.ifBlank { null }
            } catch (e: Exception) {
                Log.e(TAG, "polishWithPrompt failed", e)
                null
            }
        }

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

            // 长文本分块润色：本地 LLM 在长 prompt 下易生成失败/超时返回空 → 导致返回 null
            // 按 ~400 字切分（优先在句末标点处断），逐块润色后拼接，避免单次过长
            val CHUNK_THRESHOLD = 400
            if (text.length > CHUNK_THRESHOLD) {
                val chunks = splitIntoChunks(text, CHUNK_THRESHOLD)
                Log.d(TAG, "Polish: 长文本分 ${chunks.size} 块 (总长 ${text.length})")
                val sb = StringBuilder()
                for (chunk in chunks) {
                    val r = polishSingle(chunk, instruction) ?: chunk  // 某块失败则保留原文该块
                    sb.append(r)
                }
                val joined = sb.toString().trim()
                return@withContext joined.ifBlank { null }
            }

            return@withContext polishSingle(text, instruction)
        }

    /** 单块润色（含重置、动态 maxTokens、后处理） */
    private fun polishSingle(text: String, instruction: String): String? {
        try {
            mnnEngine.nativeReset()
            val maxTokens = (text.length * 2.5).toInt().coerceIn(64, 1536)
            Log.d(TAG, "Polish: textLen=${text.length}, maxTokens=$maxTokens")
            val prompt = buildPolishPrompt(text, instruction)
            System.gc()
            val result = mnnEngine.nativeGenerate(prompt, maxTokens)
            Log.d(TAG, "Polish raw result: ${result.take(200)}")
            val cleaned = postProcessPolishResult(text, result)
            return cleaned.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Polish error", e)
            return null
        }
    }

    /** 尽量在句末标点处把长文本切成 ≤ maxLen 的块 */
    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxLen, text.length)
            if (end < text.length) {
                // 在 [start+maxLen/2, end] 范围内向前找最后一个句末标点
                val mid = start + maxLen / 2
                var cut = -1
                for (j in end downTo mid) {
                    val ch = text[j]
                    if (ch == '。' || ch == '？' || ch == '！' || ch == '\n') { cut = j + 1; break }
                }
                if (cut > start) end = cut
            }
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    /**
     * 润色结果后处理：
     * 1. 截断续写内容（黑名单匹配）
     * 2. 截断重复句子
     * 3. 截断到原文长度的 120%
     */
    private fun postProcessPolishResult(original: String, raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw.trim()

        // 0. 去除模型可能输出的前缀
        val prefixPatterns = listOf("改写的文字：", "改写后：", "润色后：", "润色：", "修改后：", "处理后：", "输出：", "结果：", "？", "?")
        for (prefix in prefixPatterns) {
            if (text.startsWith(prefix)) {
                Log.d(TAG, "postProcess: 去除前缀 '$prefix'")
                text = text.substring(prefix.length).trim()
            }
        }

        // 1. 续写检测——黑名单截断
        // 注意：只匹配明显的续写/解释/评价开头，不匹配可能在正常润色结果中出现的词
        val continuationPatterns = listOf(
            // 解释/评论类（模型在解释而非润色）
            "这是一个问题", "所以请", "请注意", "需要说明", "我来解释",
            "我来回答", "这个问题的", "关于这个问题", "简单来说",
            "首先，", "其次，", "最后，", "总之，", "综上，",
            "如果你想了解", "以下是", "当然，",
            // 评价原文类（模型在评价原文质量）
            "这个句子", "已经很", "不需要做", "不需要修改",
            "已经很好", "已经很通顺", "已经很流畅", "已经很规范",
            "这段话", "这段文字", "这段文本",
            "修改后", "润色后", "改写后", "处理后",
            // 续写开头类（模型在补充额外内容）
            "接下来", "另外，", "此外，", "除此之外", "补充一下",
            "如果你想学习", "如果你想尝试",
            // 建议类（模型在给出建议而非润色）
            "我会推荐", "我建议", "你可以试试", "你可以考虑", "不如试试",
            "你可以尝试"
        )
        for (pattern in continuationPatterns) {
            val idx = text.indexOf(pattern)
            if (idx > 0) {
                Log.d(TAG, "postProcess: 截断续写 '$pattern' at $idx")
                text = text.substring(0, idx).trim()
                break
            }
        }

        // 2. 重复句子检测（Java 层兜底）
        val sentences = text.split(Regex("[。！？\\n]+")).filter { it.trim().isNotEmpty() }
        if (sentences.size > 1) {
            val seen = mutableSetOf<String>()
            val unique = mutableListOf<String>()
            for (s in sentences) {
                val trimmed = s.trim()
                if (trimmed !in seen) {
                    seen.add(trimmed)
                    unique.add(trimmed)
                } else {
                    Log.d(TAG, "postProcess: 去除重复句子 '$trimmed'")
                }
            }
            if (unique.size < sentences.size) {
                text = unique.joinToString("。") + "。"
            }
        }

        // 3. 长度截断：不超过原文 200%（润色后可能比原文长）
        val maxLen = (original.length * 2.0).toInt().coerceAtLeast(original.length + 10)
        if (text.length > maxLen) {
            Log.d(TAG, "postProcess: 长度截断 ${text.length} -> $maxLen")
            val truncated = text.substring(0, maxLen)
            // 在截断点找最后一个句末标点
            val lastPunct = maxOf(
                truncated.lastIndexOf('。'),
                truncated.lastIndexOf('？'),
                truncated.lastIndexOf('！'),
                truncated.lastIndexOf('\n')
            )
            if (lastPunct > maxLen * 0.3) {
                text = truncated.substring(0, lastPunct + 1).trim()
            } else {
                // 找不到合适的句末标点，回退到逗号
                val lastComma = maxOf(
                    truncated.lastIndexOf('，'),
                    truncated.lastIndexOf(',')
                )
                text = if (lastComma > maxLen * 0.3) {
                    truncated.substring(0, lastComma).trim()
                } else {
                    truncated.trim()
                }
            }
        }

        return text
    }

    /**
     * 构建润色 prompt（本地 MNN 格式）
     * C++ 层已使用 ChatMessages（system+user 分离），这里只返回原文
     */
    private fun buildPolishPrompt(text: String, instruction: String): String {
        // C++ 层已使用 ChatMessages（system+user 分离）
        // system prompt 在 C++ 层硬编码（与 DEFAULT_POLISH_PROMPT 一致）
        // 这里只返回原文，不需要额外指令
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

    fun getMnnLog(): String = mnnEngine.nativeGetLog()

    fun release() {
        mnnEngine.nativeFree()
        modelLoaded = false
        currentModelPath = null
    }
}
