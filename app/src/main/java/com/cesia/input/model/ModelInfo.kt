package com.cesia.input.model

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,      // 下载链接
    val fileName: String,         // 本地文件名或目录名
    val sizeBytes: Long,
    val sha256: String? = null,
    val type: ModelType
) {
    enum class ModelType { VOICE, AI, TTS }
}

/**
 * 模型注册表
 */
object ModelRegistry {

    const val KB = 1024L
    const val MB = KB * 1024
    const val GB = MB * 1024

    // === Qwen2.5-1.5B-Instruct-MNN 模型文件列表 ===
    val MNN_MODEL_FILES = listOf(
        "config.json",
        "llm.mnn",
        "llm.mnn.json",
        "llm.mnn.weight",
        "llm_config.json",
        "tokenizer.txt"
    )

    // === Zipformer 语音模型文件列表 ===
    val ZIPFORMER_FILES = listOf(
        "encoder-epoch-99-avg-1.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx",
        "tokens.txt"
    )

    val ALL_MODELS = listOf(
        // === 语音识别模型 (Sherpa-onnx Zipformer 中英双语) ===
        ModelInfo(
            id = "sherpa-zipformer",
            name = "Zipformer 中英双语",
            description = "中英双语, 流式识别, 完全离线 (~206MB)",
            downloadUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            fileName = "zipformer",
            sizeBytes = 206L * MB,
            type = ModelInfo.ModelType.VOICE
        ),

        // === AI 模型 (MNN 格式，本地推理) ===
        ModelInfo(
            id = "qwen25-1.5b-mnn",
            name = "Qwen2.5 1.5B",
            description = "AI 润色模型（~1.2GB），MNN 本地推理，无需网络",
            downloadUrl = "https://hf-mirror.com/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN",
            fileName = "qwen25-1.5b-mnn",
            sizeBytes = 1200L * MB,
            type = ModelInfo.ModelType.AI
        ),

        // === TTS 模型 (Sherpa-onnx Vits 中文语音合成) ===
        ModelInfo(
            id = "sherpa-tts-zh-hf-theresa",
            name = "中文语音合成 (Vits)",
            description = "中文 TTS 模型，用于同声传译语音输出 (~30MB)",
            downloadUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-tts-zh-hf-theresa-2023-12-28",
            fileName = "tts",
            sizeBytes = 30L * MB,
            type = ModelInfo.ModelType.TTS
        )
    )

    fun getById(id: String): ModelInfo? = ALL_MODELS.find { it.id == id }

    // === Zipformer 文件下载 URL ===
    fun getZipformerFileUrl(file: String): String {
        return "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/$file"
    }

    fun getZipformerLocalName(downloadedFile: String): String {
        return when (downloadedFile) {
            "encoder-epoch-99-avg-1.onnx" -> "encoder.onnx"
            "decoder-epoch-99-avg-1.onnx" -> "decoder.onnx"
            "joiner-epoch-99-avg-1.onnx" -> "joiner.onnx"
            else -> downloadedFile
        }
    }

    // === MNN 模型文件下载 URL ===
    fun getMnnFileUrl(file: String): String {
        return "https://hf-mirror.com/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main/$file"
    }
}
