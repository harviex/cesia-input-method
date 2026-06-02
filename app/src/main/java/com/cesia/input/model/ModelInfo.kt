package com.cesia.input.model

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,                  // 唯一标识，如 "whisper-small", "qwen-2b"
    val name: String,                // 显示名，如 "Whisper Small"
    val description: String,         // 描述
    val downloadUrl: String,         // HuggingFace 下载链接
    val fileName: String,            // 本地文件名
    val sizeBytes: Long,             // 文件大小（字节）
    val sha256: String? = null,      // 校验和（可选）
    val type: ModelType              // 语音 or AI
) {
    enum class ModelType { VOICE, AI }

    /** 简单安装 vs 高级安装 */
    enum class Tier { BASIC, PREMIUM }

    val tier: Tier
        get() = when (id) {
            "whisper-small", "qwen-0.8b" -> Tier.BASIC
            "whisper-large-turbo", "qwen-2b" -> Tier.PREMIUM
            else -> Tier.BASIC
        }
}

/**
 * 所有可用模型定义
 */
object ModelRegistry {
    
    const val KB = 1024L
    const val MB = KB * 1024
    const val GB = MB * 1024

    val ALL_MODELS = listOf(
        // === 语音识别模型 ===
        ModelInfo(
            id = "whisper-small",
            name = "Whisper Small",
            description = "语音识别基础模型（~400MB），准确率高，适合日常使用",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_0.bin",
            fileName = "ggml-small-q5_0.bin",
            sizeBytes = 466L * MB,
            type = ModelInfo.ModelType.VOICE
        ),
        ModelInfo(
            id = "whisper-large-turbo",
            name = "Whisper Large V3 Turbo",
            description = "语音识别旗舰模型（~800MB），最高精度，适合专业场景",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            sizeBytes = 809L * MB,
            type = ModelInfo.ModelType.VOICE
        ),

        // === AI 模型 ===
        ModelInfo(
            id = "qwen-0.8b",
            name = "Qwen 3.5 0.8B",
            description = "AI 润色轻量模型（~560MB），极速响应，省资源",
            downloadUrl = "https://huggingface.co/bartowski/Qwen3.5-0.8B-Instruct-GGUF/resolve/main/Qwen3.5-0.8B-Instruct-Q4_K_M.gguf",
            fileName = "Qwen3.5-0.8B-Instruct-Q4_K_M.gguf",
            sizeBytes = 560L * MB,
            type = ModelInfo.ModelType.AI
        ),
        ModelInfo(
            id = "qwen-2b",
            name = "Qwen 3.5 2B",
            description = "AI 润色标准模型（~1.4GB），更好的润色质量",
            downloadUrl = "https://huggingface.co/bartowski/Qwen3.5-2B-Instruct-GGUF/resolve/main/Qwen3.5-2B-Instruct-Q4_K_M.gguf",
            fileName = "Qwen3.5-2B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1400L * MB,
            type = ModelInfo.ModelType.AI
        )
    )

    fun getById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
}
