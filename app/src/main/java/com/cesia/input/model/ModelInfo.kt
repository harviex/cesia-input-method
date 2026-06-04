package com.cesia.input.model

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,                  // 唯一标识，如 "sherpa-sensevoice", "qwen-2b"
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
            "sherpa-zipformer", "qwen-0.8b" -> Tier.BASIC
            "sherpa-sensevoice", "sherpa-paraformer", "qwen-2b" -> Tier.PREMIUM
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
        // === 语音识别模型 (Sherpa-onnx) ===
        ModelInfo(
            id = "sherpa-sensevoice",
            name = "SenseVoice",
            description = "多语言语音识别(中/英/日/韩/粤), 内置标点, 离线识别 (~228MB)",
            downloadUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.onnx",
            fileName = "sherpa-sensevoice-model.onnx",
            sizeBytes = 228L * MB,
            type = ModelInfo.ModelType.VOICE
        ),
        ModelInfo(
            id = "sherpa-paraformer",
            name = "Paraformer",
            description = "中文专精语音识别, 高准确率, 流式识别 (~80MB)",
            downloadUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/model.onnx",
            fileName = "sherpa-paraformer-model.onnx",
            sizeBytes = 80L * MB,
            type = ModelInfo.ModelType.VOICE
        ),
        ModelInfo(
            id = "sherpa-zipformer",
            name = "Zipformer",
            description = "最轻量语音识别, 中英双语, 流式识别 (~30MB)",
            downloadUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-zipformer-zh-2023-09-14/resolve/main/model.onnx",
            fileName = "sherpa-zipformer-model.onnx",
            sizeBytes = 30L * MB,
            type = ModelInfo.ModelType.VOICE
        ),

        // === AI 模型 ===
        ModelInfo(
            id = "qwen-0.8b",
            name = "Qwen 3.5 0.8B",
            description = "AI 润色轻量模型（~560MB），极速响应，省资源",
            downloadUrl = "https://hf-mirror.com/bartowski/Qwen3.5-0.8B-Instruct-GGUF/resolve/main/Qwen3.5-0.8B-Instruct-Q4_K_M.gguf",
            fileName = "Qwen3.5-0.8B-Instruct-Q4_K_M.gguf",
            sizeBytes = 560L * MB,
            type = ModelInfo.ModelType.AI
        ),
        ModelInfo(
            id = "qwen-2b",
            name = "Qwen 3.5 2B",
            description = "AI 润色标准模型（~1.4GB），更好的润色质量",
            downloadUrl = "https://hf-mirror.com/bartowski/Qwen3.5-2B-Instruct-GGUF/resolve/main/Qwen3.5-2B-Instruct-Q4_K_M.gguf",
            fileName = "Qwen3.5-2B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1400L * MB,
            type = ModelInfo.ModelType.AI
        )
    )

    fun getById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
}
