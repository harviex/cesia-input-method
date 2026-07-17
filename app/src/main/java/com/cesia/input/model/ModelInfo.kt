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
    val type: ModelType,
    val isArchive: Boolean = false,   // true = .tar.bz2 整包下载后解压
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

    // === MNN AI 模型文件列表 ===
    // Qwen3.5-2B 模型文件（Qwen2.5-1.5B 不含 visual 文件）
    val MNN_MODEL_FILES_COMMON = listOf(
        "config.json",
        "llm.mnn",
        "llm.mnn.json",
        "llm.mnn.weight",
        "llm_config.json",
        "tokenizer.txt"
    )
    // Qwen3.5 额外需要 visual 文件
    val MNN_MODEL_FILES_QWEN35 = MNN_MODEL_FILES_COMMON + listOf("visual.mnn", "visual.mnn.weight")
    // 向后兼容：默认使用完整列表
    val MNN_MODEL_FILES = MNN_MODEL_FILES_QWEN35

    // === Zipformer 语音模型文件列表 ===
    val ZIPFORMER_FILES = listOf(
        "encoder-epoch-99-avg-1.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx",
        "tokens.txt"
    )

    // === Zipformer 纯中文模型文件列表（zh-int8-2025-06-30，文件名已带 int8）===
    val ZIPFORMER_ZH_FILES = listOf(
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "joiner.int8.onnx",
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

        // === 语音识别模型 (Sherpa-onnx Zipformer 纯中文 2025 int8) ===
        // 双击语音键切换；独立目录 local_models/zipformer-zh-2025/，不影响中英双语模型
        ModelInfo(
            id = "zipformer-zh-2025",
            name = "Zipformer 纯中文 2025",
            description = "纯中文高精度, 流式 int8 (~160MB), 双击语音键切换",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            fileName = "zipformer-zh-2025",
            sizeBytes = 160L * MB,
            type = ModelInfo.ModelType.VOICE,
            isArchive = true
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
        ModelInfo(
            id = "qwen35-2b-mnn",
            name = "Qwen3.5 2B",
            description = "AI 润色模型（~1.3GB），MNN 本地推理，Qwen3.5 指令遵循更强",
            downloadUrl = "https://hf-mirror.com/taobao-mnn/Qwen3.5-2B-MNN",
            fileName = "qwen35-2b-mnn",
            sizeBytes = 1300L * MB,
            type = ModelInfo.ModelType.AI
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

    // 纯中文模型（zh-int8-2025-06-30）：文件名已带 int8，重命名为标准名
    fun getZipformerZhLocalName(downloadedFile: String): String {
        return when (downloadedFile) {
            "encoder.int8.onnx" -> "encoder.onnx"
            "decoder.int8.onnx" -> "decoder.onnx"
            "joiner.int8.onnx" -> "joiner.onnx"
            else -> downloadedFile
        }
    }

    // === MNN 模型文件下载 URL ===
    // 根据模型 ID 动态选择仓库
    fun getMnnFileUrl(file: String, modelId: String = "qwen25-1.5b-mnn"): String {
        val repo = when (modelId) {
            "qwen35-2b-mnn" -> "taobao-mnn/Qwen3.5-2B-MNN"
            else -> "taobao-mnn/Qwen2.5-1.5B-Instruct-MNN"
        }
        return "https://hf-mirror.com/$repo/resolve/main/$file"
    }
}
