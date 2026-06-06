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
            "sherpa-zipformer", "qwen-0.6b" -> Tier.BASIC
            "sherpa-sensevoice", "qwen-4b", "qwen-8b" -> Tier.PREMIUM
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
            name = "Zipformer 中英双语",
            description = "中英双语, 流式识别, 完全离线 (~206MB)",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            fileName = "zipformer",  // 目录名，实际包含多个文件
            sizeBytes = 206L * MB,
            type = ModelInfo.ModelType.VOICE
        ),

        // === AI 模型 (GGUF 格式，用于 llama.cpp 本地推理) ===
        // 使用 ModelScope 国内镜像，确保国内可下载
        // 0.6B 模型手机端最快：610MB 下载 + 推理 5-15 秒
        ModelInfo(
            id = "qwen-0.6b",
            name = "Qwen 3 0.6B",
            description = "AI 润色模型（~610MB），手机端最快",
            downloadUrl = "https://modelscope.cn/models/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            fileName = "Qwen3-0.6B-Q8_0.gguf",
            sizeBytes = 610L * MB,
            type = ModelInfo.ModelType.AI
        ),
        // 4B 模型手机端最佳平衡：2.7GB 下载 + 推理快 + 质量好
        ModelInfo(
            id = "qwen-4b",
            name = "Qwen 3 4B",
            description = "AI 润色模型（~2.7GB），手机端最佳平衡",
            downloadUrl = "https://modelscope.cn/models/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q6_K.gguf",
            fileName = "Qwen3-4B-Q6_K.gguf",
            sizeBytes = 2700L * MB,
            type = ModelInfo.ModelType.AI
        )
    )

    // Zipformer 流式模型文件列表（双语 zh-en）
    val ZIPFORMER_FILES = listOf(
        "encoder-epoch-99-avg-1.onnx",   // 下载后重命名为 encoder.onnx
        "decoder-epoch-99-avg-1.onnx",   // 下载后重命名为 decoder.onnx
        "joiner-epoch-99-avg-1.onnx",    // 下载后重命名为 joiner.onnx
        "tokens.txt"
    )

    // Zipformer 各文件下载路径
    fun getZipformerFileUrl(file: String): String {
        return "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/$file"
    }

    // Zipformer 文件下载后的标准文件名映射
    fun getZipformerLocalName(downloadedFile: String): String {
        return when (downloadedFile) {
            "encoder-epoch-99-avg-1.onnx" -> "encoder.onnx"
            "decoder-epoch-99-avg-1.onnx" -> "decoder.onnx"
            "joiner-epoch-99-avg-1.onnx" -> "joiner.onnx"
            else -> downloadedFile
        }
    }

    fun getById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }
}
