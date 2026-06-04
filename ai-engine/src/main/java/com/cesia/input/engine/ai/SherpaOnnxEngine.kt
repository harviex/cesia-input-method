package com.cesia.input.engine.ai

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*

/**
 * Sherpa-onnx 本地语音识别引擎
 *
 * 支持三种模型类型（根据硬件自动选择）：
 * - SenseVoice: ~228MB, 多语言(中/英/日/韩/粤), 内置标点, 离线识别
 * - Paraformer: ~80MB, 中文专精, 高准确率, 流式识别
 * - Zipformer: ~30MB, 最轻量, 中英双语, 流式识别
 *
 * 使用前需:
 * 1. 下载模型到本地文件
 * 2. 调用 createRecognizer() 创建识别器
 * 3. 流式识别: createStream() → acceptWaveform() → getResult()
 * 4. 释放: stream.delete(), recognizer.delete()
 */
class SherpaOnnxEngine {

    companion object {
        private const val TAG = "SherpaOnnxEngine"
        private var libraryLoaded = false
        private var libraryLoadError: String? = null

        init {
            try {
                libraryLoaded = true
                libraryLoadError = null
                Log.i(TAG, "Sherpa-onnx library loaded (via AAR)")
            } catch (e: Throwable) {
                libraryLoaded = false
                libraryLoadError = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Failed to load Sherpa-onnx: ${e.message}")
            }
        }

        fun isLibraryLoaded(): Boolean = libraryLoaded
        fun getLibraryLoadError(): String? = libraryLoadError
    }

    // ==================== 模型类型 ====================

    enum class ModelType(
        val displayName: String,
        val description: String,
        val sizeMB: Int,
        val supportsStreaming: Boolean
    ) {
        SENSE_VOICE("SenseVoice", "多语言(中/英/日/韩/粤), 内置标点, 离线", 228, false),
        PARAFORMER("Paraformer", "中文专精, 高准确率, 流式", 80, true),
        ZIPFORMER("Zipformer", "最轻量, 中英双语, 流式", 30, true)
    }

    /**
     * 根据设备硬件能力推荐模型类型
     * - 高端设备 (RAM >= 6GB): SenseVoice（离线高精度）
     * - 中端设备 (RAM 4-6GB): Paraformer（流式+中文专精）
     * - 低端设备 (RAM < 4GB): Zipformer（流式+轻量）
     */
    fun recommendModelType(): ModelType {
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = (runtime.maxMemory() / 1024 / 1024).toInt()
        Log.i(TAG, "Device max memory: ${maxMemoryMB}MB")

        return when {
            maxMemoryMB >= 6144 -> ModelType.SENSE_VOICE
            maxMemoryMB >= 4096 -> ModelType.PARAFORMER
            else -> ModelType.ZIPFORMER
        }
    }

    // ==================== 离线识别器 ====================

    /**
     * 创建离线识别器（用于整段音频识别）
     * 适用于 SenseVoice 模型
     */
    fun createOfflineRecognizer(
        assetManager: AssetManager?,
        modelDir: String,
        modelType: ModelType = ModelType.SENSE_VOICE,
        numThreads: Int = 2,
        provider: String = "cpu"
    ): OfflineRecognizer? {
        return try {
            val tokensPath = "$modelDir/tokens.txt"

            val modelConfig = when (modelType) {
                ModelType.SENSE_VOICE -> {
                    OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "$modelDir/model.onnx",
                            language = "zh",
                            useInverseTextNormalization = true
                        ),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        debug = false,
                        provider = provider
                    )
                }
                ModelType.PARAFORMER -> {
                    OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(
                            model = "$modelDir/model.onnx"
                        ),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        debug = false,
                        provider = provider
                    )
                }
                ModelType.ZIPFORMER -> {
                    OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$modelDir/encoder.onnx",
                            decoder = "$modelDir/decoder.onnx",
                            joiner = "$modelDir/joiner.onnx"
                        ),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        debug = false,
                        provider = provider
                    )
                }
            }

            val featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            )

            val config = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
                maxActivePaths = 4
            )

            val recognizer = OfflineRecognizer(assetManager, config)
            Log.i(TAG, "OfflineRecognizer created: type=$modelType, provider=$provider")
            recognizer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create OfflineRecognizer: ${e.message}", e)
            null
        }
    }

    // ==================== 流式识别器 ====================

    /**
     * 创建流式识别器（用于实时识别）
     * 适用于 Paraformer 和 Zipformer 模型
     */
    fun createStreamingRecognizer(
        assetManager: AssetManager?,
        modelDir: String,
        modelType: ModelType = ModelType.PARAFORMER,
        numThreads: Int = 2,
        provider: String = "cpu"
    ): OnlineRecognizer? {
        return try {
            val tokensPath = "$modelDir/tokens.txt"

            val modelConfig = when (modelType) {
                ModelType.SENSE_VOICE -> {
                    // SenseVoice 不支持流式，回退到 Paraformer 配置
                    Log.w(TAG, "SenseVoice does not support streaming, using Paraformer config")
                    OnlineModelConfig(
                        paraformer = OnlineParaformerModelConfig(
                            encoder = "$modelDir/encoder.onnx",
                            decoder = "$modelDir/decoder.onnx"
                        ),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        debug = false,
                        provider = provider
                    )
                }
                ModelType.PARAFORMER -> {
                    OnlineModelConfig(
                        paraformer = OnlineParaformerModelConfig(
                            encoder = "$modelDir/encoder.onnx",
                            decoder = "$modelDir/decoder.onnx"
                        ),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        debug = false,
                        provider = provider
                    )
                }
                ModelType.ZIPFORMER -> {
                    OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$modelDir/encoder.onnx",
                            decoder = "$modelDir/decoder.onnx",
                            joiner = "$modelDir/joiner.onnx"
                        ),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        debug = false,
                        provider = provider
                    )
                }
            }

            val featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            )

            val endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.0f, minUtteranceLength = 0.0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.8f, minUtteranceLength = 0.0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 0.0f)
            )

            val config = OnlineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                endpointConfig = endpointConfig,
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4
            )

            val recognizer = OnlineRecognizer(assetManager, config)
            Log.i(TAG, "OnlineRecognizer created: type=$modelType, provider=$provider")
            recognizer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create OnlineRecognizer: ${e.message}", e)
            null
        }
    }

    // ==================== 音频识别 ====================

    /**
     * 离线识别整段音频
     */
    fun transcribeOffline(
        recognizer: OfflineRecognizer,
        audioData: FloatArray
    ): String {
        return try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(audioData, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            // OfflineStream.delete() is private; rely on GC/finalize
            Log.i(TAG, "Offline transcribe result: \"$text\"")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Offline transcribe error: ${e.message}", e)
            ""
        }
    }

    /**
     * 流式识别 — 喂入音频数据
     */
    fun acceptWaveform(
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
        audioData: FloatArray
    ) {
        try {
            stream.acceptWaveform(audioData, 16000)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Accept waveform error: ${e.message}", e)
        }
    }

    /**
     * 流式识别 — 获取当前识别结果
     */
    fun getStreamingResult(
        recognizer: OnlineRecognizer,
        stream: OnlineStream
    ): String {
        return try {
            val result = recognizer.getResult(stream)
            result.text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Get streaming result error: ${e.message}", e)
            ""
        }
    }

    /**
     * 流式识别 — 检查是否检测到端点（说话结束）
     */
    fun isEndpoint(
        recognizer: OnlineRecognizer,
        stream: OnlineStream
    ): Boolean {
        return try {
            recognizer.isEndpoint(stream)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 流式识别 — 重置流（继续识别下一段）
     */
    fun resetStream(
        recognizer: OnlineRecognizer,
        stream: OnlineStream
    ) {
        try {
            recognizer.reset(stream)
        } catch (e: Exception) {
            Log.e(TAG, "Reset stream error: ${e.message}", e)
        }
    }
}
