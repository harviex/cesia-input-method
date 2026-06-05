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
            "sherpa-sensevoice", "qwen-2b" -> Tier.PREMIUM
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
        // === 语音识别模型 (Sherpa-onnx Zipformer) ===
        // Zipformer 支持流式识别（OnlineRecognizer），完全离线运行，边说边出字
        ModelInfo(
            id = "sherpa-zipformer",
            name = "Zipformer",
            description = "中英双语, 流式识别, 完全离线 (~206MB)",
            downloadUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-2023-09-14",
            fileName = "zipformer",  // 目录名，实际包含多个文件
            sizeBytes = 206L * MB,
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

    // Zipformer 流式模型文件列表（INT8 量化版本）
    // 下载时使用原始文件名，下载后重命名为标准文件名
    val ZIPFORMER_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",   // 下载后重命名为 encoder.onnx
        "decoder-epoch-99-avg-1.int8.onnx",   // 下载后重命名为 decoder.onnx
        "joiner-epoch-99-avg-1.int8.onnx",    // 下载后重命名为 joiner.onnx
        "tokens.txt"
    )

    // Zipformer 各文件下载路径
    fun getZipformerFileUrl(file: String): String {
        return "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/$file"
    }

    // Zipformer 文件下载后的标准文件名映射
    fun getZipformerLocalName(downloadedFile: String): String {
        return when (downloadedFile) {
            "encoder-epoch-99-avg-1.int8.onnx" -> "encoder.onnx"
            "decoder-epoch-99-avg-1.int8.onnx" -> "decoder.onnx"
            "joiner-epoch-99-avg-1.int8.onnx" -> "joiner.onnx"
            else -> downloadedFile
        }
    }

    fun getById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
}
