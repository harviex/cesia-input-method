package com.cesia.input.voice

import android.content.Context
import android.util.Log
import com.cesia.input.OpenCCConverter
import com.cesia.input.engine.ai.SherpaOnnxEngine
import com.cesia.input.model.ModelManager
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 语音识别引擎
 * 支持：
 * - Sherpa-onnx Zipformer 流式识别（OnlineRecognizer，边说边出文字）
 * - Sherpa-onnx 离线识别（OfflineRecognizer，整段识别）
 * - Groq 云端识别（whisper-large-v3）
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

        // 动态命令词（可被 PersonalizationActivity 更新）
        @Volatile
        var cmdExit: String = "退出"

        @Volatile
        var cmdPolish: String = "魔法"

        @Volatile
        var cmdFinish: String = "结束"

        @Volatile
        var cmdSend: String = "发送"

        @Volatile
        var cmdCommand: String = "修改"

        @Volatile
        var cmdWriting: String = "写作"

        @Volatile
        var cmdUndo: String = "撤销"

        @Volatile
        var cmdClear: String = "清空"

        @Volatile
        var cmdRestore: String = "恢复"

        /**
         * 更新命令词（从设置页面调用）
         */
        fun updateCommandWords(exit: String, polish: String, finish: String, send: String, command: String, writing: String = "写作", undo: String = "撤销", clear: String = "清空", restore: String = "恢复") {
            cmdExit = exit
            cmdPolish = polish
            cmdFinish = finish
            cmdSend = send
            cmdCommand = command
            cmdWriting = writing
            cmdUndo = undo
            cmdClear = clear
            cmdRestore = restore
            Log.d(TAG, "命令词已更新: exit=$exit, polish=$polish, finish=$finish, send=$send, command=$command, writing=$writing, undo=$undo, clear=$clear, restore=$restore")
        }

        /** 获取语音命令词提示字符串 */
        fun getCommandHints(): String {
            return "退出 / $cmdPolish / $cmdFinish / $cmdSend / $cmdCommand / $cmdWriting / $cmdUndo / $cmdClear / $cmdRestore"
        }
    }

    enum class Backend {
        LOCAL_SHERPA,
        CLOUD_GROQ
    }

    enum class ModelType(val displayName: String) {
        SENSE_VOICE("SenseVoice"),
        ZIPFORMER("Zipformer")
    }

    // 语音识别模式：中英混（默认，原行为）/ 纯中文（zipformer-zh-2025）
    enum class VoiceMode { MIXED, CHINESE }

    data class RecognitionResult(
        val text: String,
        val isFinal: Boolean,
        val modelType: String = "",
        val backend: String = ""
    )

    private val modelManager = ModelManager(context)
    private val sherpaEngine = SherpaOnnxEngine()
    private val recorder = VoiceRecorder()
    private val prefs = context.getSharedPreferences("cesia_voice", Context.MODE_PRIVATE)

    // 语音识别模式（中英混 / 纯中文），持久化；默认中英混，不改动原有行为
    var voiceMode: VoiceMode
        get() = VoiceMode.valueOf(prefs.getString("voice_mode", VoiceMode.MIXED.name) ?: VoiceMode.MIXED.name)
        set(value) {
            prefs.edit().putString("voice_mode", value.name).apply()
            Log.i(TAG, "voiceMode -> ${value.name}")
        }

    private var currentBackend = Backend.LOCAL_SHERPA
    private var currentModelType = ModelType.ZIPFORMER
    private var recognizer: Any? = null
    private var recognizerLoaded = false
    private var lastErrorMessage: String? = null

    // 预热缓存：在后台预创建 OnlineRecognizer，避免首次点击语音键的延迟
    private var cachedOnlineRecognizer: com.k2fsa.sherpa.onnx.OnlineRecognizer? = null
    private var cachedModelPath: String? = null
    private val warmupScope = CoroutineScope(Dispatchers.IO)
    private var hasWarmedUp = false

    private val groqClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ==================== 后端配置 ====================

    fun setBackend(backend: Backend) {
        currentBackend = backend
        Log.i(TAG, "Voice backend set to: $backend")
    }

    fun getBackend(): Backend = currentBackend

    fun setModelType(type: ModelType) {
        currentModelType = type
        Log.i(TAG, "Model type set to: $type")
    }

    fun getModelType(): ModelType = currentModelType

    fun getLastErrorMessage(): String? = lastErrorMessage

    // ==================== 模型目录查找 ====================

    /**
     * 查找模型目录
     * 优先查找 Zipformer 多文件目录 local_models/zipformer/
     * 兼容旧路径 local_models/ 下的 .onnx 单文件
     */
    private fun findModelDir(): File? {
        // 纯中文模式：优先返回 zh-2025 目录（仅当文件完整），否则回退到中英混，绝不破坏原有模型
        if (voiceMode == VoiceMode.CHINESE) {
            val zhDir = File(context.filesDir, "local_models/zipformer-zh-2025")
            if (isZipformerModel(zhDir)) {
                Log.i(TAG, "findModelDir: 纯中文目录 ${zhDir.absolutePath}")
                return zhDir
            }
            Log.w(TAG, "findModelDir: 纯中文模式但 zh 模型不完整，回退中英混")
        }

        // 1. Zipformer 多文件目录
        val zipformerDir = File(context.filesDir, "local_models/zipformer")
        if (zipformerDir.exists() && zipformerDir.isDirectory) {
            val encoder = File(zipformerDir, "encoder.onnx")
            val decoder = File(zipformerDir, "decoder.onnx")
            val joiner = File(zipformerDir, "joiner.onnx")
            val tokens = File(zipformerDir, "tokens.txt")
            if (encoder.exists() && decoder.exists() && joiner.exists() && tokens.exists()) {
                Log.i(TAG, "findModelDir: Zipformer 目录完整 ${zipformerDir.absolutePath}")
                return zipformerDir
            }
        }

        // 2. 兼容旧版 Paraformer 目录（迁移期保留）
        val paraformerDir = File(context.filesDir, "local_models/paraformer")
        if (paraformerDir.exists() && paraformerDir.isDirectory) {
            val encoder = File(paraformerDir, "encoder.onnx")
            val decoder = File(paraformerDir, "decoder.onnx")
            val tokens = File(paraformerDir, "tokens.txt")
            if (encoder.exists() && decoder.exists() && tokens.exists()) {
                Log.i(TAG, "findModelDir: 旧版 Paraformer 目录可用 ${paraformerDir.absolutePath}")
                return paraformerDir
            }
        }

        // 3. 通过 ModelManager 查找单文件模型
        val installedFile = modelManager.getInstalledVoiceModelFile()
        if (installedFile != null) {
            Log.i(TAG, "findModelDir: ModelManager 找到 ${installedFile.absolutePath}")
            return installedFile.parentFile
        }

        // 3. 兼容旧路径
        val sherpaDir = File(context.filesDir, "models/sherpa")
        val legacyFile = File(sherpaDir, "model.onnx")
        if (legacyFile.exists()) {
            Log.i(TAG, "findModelDir: 旧路径 ${sherpaDir.absolutePath}")
            return sherpaDir
        }

        return null
    }

    /**
     * 判断是否为 Zipformer 多文件模型
     */
    private fun isZipformerModel(modelDir: File): Boolean {
        return File(modelDir, "encoder.onnx").exists() &&
               File(modelDir, "decoder.onnx").exists() &&
               File(modelDir, "joiner.onnx").exists() &&
               File(modelDir, "tokens.txt").exists()
    }

    /** 纯中文模型是否已下载且完整（独立目录 local_models/zipformer-zh-2025/） */
    fun hasChineseModel(): Boolean {
        val zhDir = File(context.filesDir, "local_models/zipformer-zh-2025")
        return isZipformerModel(zhDir)
    }

    /** 切换中英混 / 纯中文 模式；返回切换后的模式。仅在 hasChineseModel() 为真时切到中文有效。 */
    fun switchVoiceMode(): VoiceMode {
        voiceMode = if (voiceMode == VoiceMode.MIXED) VoiceMode.CHINESE else VoiceMode.MIXED
        // 切换后清掉识别器缓存，使下次 getOrCreateRecognizer 按新目录重建
        cachedOnlineRecognizer = null
        cachedModelPath = null
        return voiceMode
    }

    // ==================== 本地模型加载 ====================

    /**
     * 检查本地语音模型是否可用
     * 支持 Zipformer 多文件模型（local_models/zipformer/）
     * 兼容旧版 Paraformer 目录
     */
    suspend fun loadLocalModel(modelType: ModelType? = null): Boolean = withContext(Dispatchers.IO) {
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            val err = SherpaOnnxEngine.getLibraryLoadError() ?: "未知错误"
            lastErrorMessage = "Sherpa-onnx 库未加载: $err"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        val modelDir = findModelDir()
        if (modelDir == null) {
            lastErrorMessage = "未找到模型目录（local_models/zipformer/）"
            Log.e(TAG, lastErrorMessage!!)
            return@withContext false
        }

        recognizerLoaded = true
        lastErrorMessage = null
        Log.i(TAG, "本地模型可用: ${modelDir.absolutePath}")
        true
    }

    fun isLocalModelLoaded(): Boolean = recognizerLoaded

    /** 后台预热：预创建 OnlineRecognizer，避免首次点击语音键的 1.4s 延迟 */
    fun warmupRecognizer() {
        if (hasWarmedUp) return
        warmupScope.launch {
            val modelDir = findModelDir()
            if (modelDir == null) return@launch
            val modelPath = modelDir.absolutePath
            // 如果已缓存同路径，跳过
            if (cachedModelPath == modelPath && cachedOnlineRecognizer != null) return@launch

            try {
                val modelType = if (isZipformerModel(modelDir)) {
                    SherpaOnnxEngine.ModelType.ZIPFORMER
                } else {
                    SherpaOnnxEngine.ModelType.ZIPFORMER
                }
                val onlineRec = sherpaEngine.createStreamingRecognizer(
                    assetManager = null,
                    modelDir = modelPath,
                    modelType = modelType,
                    numThreads = 2
                )
                if (onlineRec != null) {
                    cachedOnlineRecognizer = onlineRec
                    cachedModelPath = modelPath
                    hasWarmedUp = true
                    Log.i(TAG, "预热完成: OnlineRecognizer 已缓存 ($modelPath)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "预热失败: ${e.message}", e)
            }
        }
    }

    /** 获取或创建 OnlineRecognizer（优先使用缓存） */
    private suspend fun getOrCreateRecognizer(modelDir: File): com.k2fsa.sherpa.onnx.OnlineRecognizer? {
        val modelPath = modelDir.absolutePath
        // 缓存命中
        if (cachedOnlineRecognizer != null && cachedModelPath == modelPath) {
            Log.i(TAG, "使用缓存的 OnlineRecognizer")
            return cachedOnlineRecognizer
        }
        // 否则创建新的
        val modelType = if (isZipformerModel(modelDir)) {
            SherpaOnnxEngine.ModelType.ZIPFORMER
        } else {
            SherpaOnnxEngine.ModelType.ZIPFORMER
        }
        val onlineRec = sherpaEngine.createStreamingRecognizer(
            assetManager = null,
            modelDir = modelPath,
            modelType = modelType,
            numThreads = 2
        )
        if (onlineRec != null) {
            cachedOnlineRecognizer = onlineRec
            cachedModelPath = modelPath
            hasWarmedUp = true
        }
        return onlineRec
    }

    /** 检查是否有可用的本地模型 */
    fun hasSherpaModel(): Boolean {
         val modelDir = findModelDir()
         if (modelDir == null) return false
         // 验证文件非空且完整
         return if (isZipformerModel(modelDir)) {
             File(modelDir, "encoder.onnx").length() > 1000 &&
             File(modelDir, "decoder.onnx").length() > 1000 &&
             File(modelDir, "joiner.onnx").length() > 1000 &&
             File(modelDir, "tokens.txt").length() > 100
         } else if (File(modelDir, "encoder.onnx").exists() && File(modelDir, "decoder.onnx").exists()) {
             File(modelDir, "encoder.onnx").length() > 1000 &&
             File(modelDir, "decoder.onnx").length() > 1000 &&
             File(modelDir, "tokens.txt").length() > 100
         } else {
             false
         }
     }

     /** 获取模型名称和路径信息 */
     fun getSherpaModelName(): String {
        val modelDir = findModelDir() ?: return "无模型"
        val modelId = modelManager.installedVoiceModelId ?: "未知"
        return if (isZipformerModel(modelDir)) {
            val totalBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val totalMB = totalBytes / 1024 / 1024
            "Zipformer 流式 (${totalMB}MB)"
        } else if (File(modelDir, "encoder.onnx").exists() && File(modelDir, "decoder.onnx").exists()) {
            // 旧版 Paraformer（无 joiner）
            val totalBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val totalMB = totalBytes / 1024 / 1024
            "Paraformer 流式 (${totalMB}MB)"
        } else {
            val onnxFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
            val sizeMB = (onnxFile?.length() ?: 0) / 1024 / 1024
            "$modelId (${sizeMB}MB)"
        }
    }

    /**
     * 获取模型详细信息（用于诊断显示）
     */
    fun getModelDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 语音识别诊断 ===")
        sb.appendLine("框架: Sherpa-onnx")
        sb.appendLine("库加载: ${if (SherpaOnnxEngine.isLibraryLoaded()) " 已加载" else "❌ 未加载"}")
        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            sb.appendLine("  错误: ${SherpaOnnxEngine.getLibraryLoadError() ?: "未知"}")
        }
        sb.appendLine("已注册模型ID: ${modelManager.installedVoiceModelId ?: "无"}")

        val installedFile = modelManager.getInstalledVoiceModelFile()
        sb.appendLine("ModelManager模型: ${installedFile?.let { " ${it.name} (${it.length()/1024/1024}MB)" } ?: "❌ 无"}")

        val foundDir = findModelDir()
        sb.appendLine("VoiceEngine查找: ${foundDir?.let { " ${it.absolutePath}" } ?: "❌ 未找到"}")

        // 列出 local_models/ 目录内容
        val modelsDir = File(context.filesDir, "local_models")
        if (modelsDir.exists() && modelsDir.isDirectory) {
            val files = modelsDir.listFiles()?.filter { it.isFile } ?: emptyList()
            sb.appendLine("local_models/ 文件: ${files.size} 个")
            files.forEach { sb.appendLine("  - ${it.name} (${it.length()/1024/1024}MB)") }
            // 列出子目录
            val dirs = modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            dirs.forEach { dir ->
                val dirFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
                sb.appendLine("  📁 ${dir.name}/ (${dirFiles.size} 个文件)")
                dirFiles.forEach { sb.appendLine("    - ${it.name} (${it.length()/1024/1024}MB)") }
            }
        } else {
            sb.appendLine("local_models/ 目录: 不存在")
        }

        return sb.toString()
    }

    // ==================== 流式/分段识别 ====================

    /**
     * 录音识别（边说边出文字）
     * - Zipformer 模型：使用 OnlineRecognizer 真正流式识别
     * - 其他模型：使用 OfflineRecognizer 分段识别
     */
    suspend fun recordInSegments(
        maxDurationMs: Int = 30000,
        segmentDurationMs: Int = 3000,
        onSegmentResult: suspend (text: String, isFinal: Boolean) -> Unit,
        onCommandWordDetected: suspend (text: String, command: String) -> Unit = { _, _ -> }
    ) {
        Log.i(TAG, "recordInSegments: max=${maxDurationMs}ms, segment=${segmentDurationMs}ms")

        if (!SherpaOnnxEngine.isLibraryLoaded()) {
            Log.e(TAG, "recordInSegments: library not loaded")
            return
        }

        val modelDir = findModelDir()
        if (modelDir == null) {
            Log.e(TAG, "recordInSegments: model dir not found")
            return
        }

        if (isZipformerModel(modelDir) || isParaformerModel(modelDir)) {
            recordStreaming(modelDir, maxDurationMs, onSegmentResult, onCommandWordDetected)
        } else {
            recordInSegmentsOffline(modelDir, maxDurationMs, segmentDurationMs, onSegmentResult)
        }
    }

    // 兼容旧版 Paraformer 目录检测
    private fun isParaformerModel(modelDir: File): Boolean {
        return File(modelDir, "encoder.onnx").exists() &&
               File(modelDir, "decoder.onnx").exists() &&
               File(modelDir, "tokens.txt").exists() &&
               !File(modelDir, "joiner.onnx").exists()
    }

    /** Zipformer/Paraformer 流式识别（OnlineRecognizer）
     * 真正边说边识别，不需要分段
     *
     * 关键逻辑：
     * - getResult(stream) 返回当前 stream 内累积的完整文本（非增量）
     * - 端点触发 reset(stream) 后，stream 重新从头累积
     * - 增量 = 同一次 stream 内前后两次 getResult 之差
     * - 端点后必须清空 lastResult，因为新 stream 从新开始
     */
    private suspend fun recordStreaming(
        modelDir: File,
        maxDurationMs: Int,
        onSegmentResult: suspend (text: String, isFinal: Boolean) -> Unit,
        onCommandWordDetected: suspend (text: String, command: String) -> Unit = { _, _ -> }
    ) {
        Log.i(TAG, "recordStreaming: 使用 OnlineRecognizer 流式识别, maxDuration=${maxDurationMs}ms")

        val onlineRec = getOrCreateRecognizer(modelDir)
        if (onlineRec == null) {
            Log.e(TAG, "recordStreaming: 创建/获取 OnlineRecognizer 失败")
            return
        }
        val isUsingCache = (cachedOnlineRecognizer != null && cachedModelPath == modelDir.absolutePath)

        val stream = onlineRec.createStream()
        if (stream == null) {
            Log.e(TAG, "recordStreaming: 创建 OnlineStream 失败")
            return
        }

        if (!recorder.init()) {
            Log.e(TAG, "recordStreaming: recorder init failed")
            return
        }
        recorder.start()

        val readBuffer = FloatArray(1024)
        val startTime = LongArray(1) { System.currentTimeMillis() }
        // lastResult: 上一次 getResult 返回的完整文本（用于计算增量）
        var lastResult = ""
        val accumulatedText = StringBuilder()

        // reset 后静默期：忽略接下来一小段时间内的结果，避免重复输出
        var lastResetTime = 0L
        val RESET_SETTLE_MS = 300L  // reset 后 300ms 内的结果忽略

        // 静音超时检测
        var consecutiveEmptyReads = 0
        var lastNonEmptyResultTime = startTime[0]
        val SILENCE_TIMEOUT_MS = 2000L
        val MAX_EMPTY_READS = 20

        // 命令词检测状态
        var pendingCommand: String? = null
        var pendingText: String = ""
        var commandDetectedTime: Long = 0L
        val COMMAND_CONFIRM_MS = 1000L  // 命令词后等待 1 秒静音确认
        var unlockDetected: Boolean = false  // 检测到"退出锁定"命令词

        try {
            var totalSamples = 0
            var nonEmptyResults = 0
            var endpointCount = 0
            while (coroutineContext.isActive && System.currentTimeMillis() - startTime[0] < maxDurationMs) {
                // 如果有待触发的命令词，超时后自动触发
                if (pendingCommand != null && System.currentTimeMillis() - commandDetectedTime >= COMMAND_CONFIRM_MS) {
                    Log.i(TAG, "recordStreaming: 命令词 '$pendingCommand' 超时确认, 文本='${pendingText.take(50)}'")
                    try { recorder.stop() } catch (_: Exception) {}
                    onCommandWordDetected(pendingText, pendingCommand)
                    return
                }

                val read = recorder.readChunk(readBuffer.size) ?: break
                if (read.isEmpty()) {
                    consecutiveEmptyReads++
                    if (consecutiveEmptyReads >= MAX_EMPTY_READS
                        && accumulatedText.isNotEmpty()
                        && System.currentTimeMillis() - lastNonEmptyResultTime > SILENCE_TIMEOUT_MS) {
                        Log.i(TAG, "recordStreaming: 静音超时触发伪端点, 累积='${accumulatedText}'")
                        endpointCount++
                        // 静音超时：把当前已确认的文本发出去
                        val converted = convertChineseDigitsToArabic(accumulatedText.toString())
                        onSegmentResult(converted, false)
                        sherpaEngine.resetStream(onlineRec, stream)
                        lastResult = ""
                        lastResetTime = System.currentTimeMillis()
                        consecutiveEmptyReads = 0
                        lastNonEmptyResultTime = System.currentTimeMillis()
                    }
                    Thread.sleep(50)
                    continue
                }
                consecutiveEmptyReads = 0
                totalSamples += read.size

                // 喂入音频
                sherpaEngine.acceptWaveform(onlineRec, stream, read)

                // 获取当前完整结果
                val rawResult = sherpaEngine.getStreamingResult(onlineRec, stream)
                if (rawResult.isEmpty()) continue

                // reset 后静默期内忽略结果（避免 stream 刚重置时的重复输出）
                if (System.currentTimeMillis() - lastResetTime < RESET_SETTLE_MS) {
                    Log.d(TAG, "recordStreaming: 静默期忽略 '$rawResult'")
                    continue
                }

                nonEmptyResults++
                lastNonEmptyResultTime = System.currentTimeMillis()

                // 英文转小写（模型输出常为大写）
                val currentResult = SherpaOnnxEngine.normalizeText(rawResult)
                if (rawResult != currentResult) {
                    Log.d(TAG, "recordStreaming: 英文转小写 '$rawResult' → '$currentResult'")
                }

                // 增量提取：currentResult 是完整文本，lastResult 是上次完整文本
                // 增量 = currentResult 去掉 lastResult 前缀后的部分
                val delta = if (lastResult.isNotEmpty() && currentResult.length > lastResult.length && currentResult.startsWith(lastResult)) {
                    currentResult.substring(lastResult.length).trim()
                } else {
                    // lastResult 为空（刚 reset）或结果回退，取完整文本
                    currentResult
                }

                // 如果有待触发的命令词，且用户继续说了新内容，取消触发
                if (pendingCommand != null && delta.isNotEmpty()) {
                    val newEnd = currentResult.takeLast(20).lowercase()
                    // 如果新内容不包含命令词，说明用户在继续说话，取消触发
                    if (!newEnd.contains("over") && !newEnd.contains("ai")) {
                        Log.i(TAG, "recordStreaming: 用户继续说话，取消命令词 '$pendingCommand' 触发")
                        pendingCommand = null
                        pendingText = ""
                    }
                }

                // 更新 lastResult 为当前完整文本（去重后）
                lastResult = currentResult

                // 命令词检测：检查当前完整文本末尾是否包含命令词
                // 支持 "aiover"、"ai over"、"over"（兼容空格）
                val commandResult = checkCommandWord(currentResult)
                if (commandResult != null) {
                    var (textBefore, command) = commandResult
                    // 端点重置后 currentResult 只含命令词，textBefore 为空
                    // 此时应使用 accumulatedText 作为原文
                    if (textBefore.isEmpty() && accumulatedText.isNotEmpty()) {
                        textBefore = accumulatedText.toString().trimEnd()
                        Log.i(TAG, "recordStreaming: 端点后命令词，使用累积文本='${textBefore.take(50)}'")
                    }
                    Log.i(TAG, "recordStreaming: 检测到命令词 '$command', 文本='${textBefore.take(50)}', 等待 ${COMMAND_CONFIRM_MS}ms 确认")
                    // 不立即触发，记录待触发状态，等 1 秒静音确认
                    pendingCommand = command
                    pendingText = textBefore
                    commandDetectedTime = System.currentTimeMillis()
                    // 继续录音，等待超时或新内容取消
                }

                if (delta.isNotEmpty()) {
                    Log.d(TAG, "recordStreaming: delta='$delta', full='$currentResult', samples=$totalSamples")
                    onSegmentResult(delta, false)
                }

                // 检查端点（说话结束）
                if (sherpaEngine.isEndpoint(onlineRec, stream)) {
                    endpointCount++
                    val endpointRaw = sherpaEngine.getStreamingResult(onlineRec, stream)
                    val endpointText = SherpaOnnxEngine.normalizeText(endpointRaw)
                    Log.i(TAG, "recordStreaming: 端点 #$endpointCount, text='$endpointText'")

                    // 过滤静音误识别：纯英文字母（如 "n"、"a"）是模型把静音段识别成了字母
                    val isNoiseLetter = endpointText.length <= 2 && endpointText.all { it in 'a'..'z' || it in 'A'..'Z' }
                    if (!isNoiseLetter && endpointText.isNotEmpty()) {
                        // 端点增量：endpointText 与 lastResult 的差
                        val endpointDelta = if (lastResult.isNotEmpty() && endpointText.length > lastResult.length && endpointText.startsWith(lastResult)) {
                            endpointText.substring(lastResult.length).trim()
                        } else {
                            endpointText
                        }
                        if (endpointDelta.isNotEmpty()) {
                            accumulatedText.append(if (accumulatedText.isNotEmpty()) " " else "").append(endpointDelta)
                        }
                    }

                    // 重置流，准备下一段
                    sherpaEngine.resetStream(onlineRec, stream)
                    lastResult = ""
                    lastResetTime = System.currentTimeMillis()  // 记录 reset 时间

                    if (accumulatedText.isNotEmpty()) {
                        val converted = convertChineseDigitsToArabic(accumulatedText.toString())
                        onSegmentResult(converted, false)
                    }
                }
            }
            Log.i(TAG, "recordStreaming: 结束 samples=$totalSamples, results=$nonEmptyResults, endpoints=$endpointCount, accumulated='${accumulatedText.toString().take(100)}'")

            // 录音结束：取最后一段结果
            val finalRaw = sherpaEngine.getStreamingResult(onlineRec, stream)
            val finalText = SherpaOnnxEngine.normalizeText(finalRaw)
            if (finalText.isNotEmpty()) {
                val finalDelta = if (lastResult.isNotEmpty() && finalText.length > lastResult.length && finalText.startsWith(lastResult)) {
                    finalText.substring(lastResult.length).trim()
                } else {
                    finalText
                }
                if (finalDelta.isNotEmpty()) {
                    accumulatedText.append(if (accumulatedText.isNotEmpty()) " " else "").append(finalDelta)
                }
            }

            val totalText = accumulatedText.toString()
            if (totalText.isNotEmpty()) {
                // 中文数字转阿拉伯数字（模型常把数字输出为汉字）
                val converted = convertChineseDigitsToArabic(totalText)
                onSegmentResult(converted, true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（用户停止录音）：处理最终识别结果
            // 注意：不在此处调用 onCommandWordDetected（suspend 函数，协程已取消无法挂起）
            // 而是通过 onSegmentResult(true) 让上层处理（显示 AI+/AI× 按钮或自动处理命令词）
            Log.i(TAG, "recordStreaming: 协程被取消，pendingCommand=$pendingCommand, accumulated='${accumulatedText.toString().take(50)}', lastResult='$lastResult'")
            // 优先用 accumulatedText（端点检测累积的完整文本），否则用 lastResult（最后流式结果）
            val totalText = if (accumulatedText.isNotEmpty()) {
                accumulatedText.toString()
            } else if (lastResult.isNotEmpty()) {
                lastResult
            } else {
                ""
            }
            if (totalText.isNotEmpty()) {
                val converted = convertChineseDigitsToArabic(totalText)
                onSegmentResult(converted, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordStreaming error: ${e.message}", e)
        } finally {
             try { recorder.stop() } catch (_: Exception) {}
             // 清理 OnlineStream（每次录音都要新建 stream）
             try {
                 if (stream != null) {
                     val deleteMethod = stream.javaClass.getDeclaredMethod("delete")
                     deleteMethod.isAccessible = true
                     deleteMethod.invoke(stream)
                     Log.i(TAG, "OnlineStream.delete() called via reflection")
                 }
             } catch (e: Exception) {
                 Log.w(TAG, "Failed to call OnlineStream.delete(): ${e.message}")
             }
             // 只有非缓存模式才释放 OnlineRecognizer；缓存模式保留供下次复用
             if (!isUsingCache) {
                 try {
                     if (onlineRec != null) {
                         val releaseMethod = onlineRec.javaClass.getDeclaredMethod("release")
                         releaseMethod.isAccessible = true
                         releaseMethod.invoke(onlineRec)
                         Log.i(TAG, "OnlineRecognizer.release() called via reflection (non-cached)")
                     }
                 } catch (e: Exception) {
                     Log.w(TAG, "Failed to call OnlineRecognizer.release(): ${e.message}")
                 }
             } else {
                 Log.i(TAG, "缓存模式: 保留 OnlineRecognizer 供下次复用")
             }
             System.gc()
         }
     }

    /**
     * 分段离线识别（OfflineRecognizer）
     * 用于非 Paraformer 模型
     */
    private suspend fun recordInSegmentsOffline(
        modelDir: File,
        maxDurationMs: Int,
        segmentDurationMs: Int,
        onSegmentResult: suspend (text: String, isFinal: Boolean) -> Unit
    ) {
        Log.i(TAG, "recordInSegmentsOffline: 使用 OfflineRecognizer 分段识别")

        val sampleRate = 16000
        val segmentSamples = sampleRate * segmentDurationMs / 1000
        val maxSamples = sampleRate * maxDurationMs / 1000

        if (!recorder.init()) {
            Log.e(TAG, "recordInSegmentsOffline: recorder init failed")
            return
        }
        recorder.start()

        val allAudio = mutableListOf<Float>()
        val readBuffer = FloatArray(1024)
        var totalSamples = 0
        val startTime = System.currentTimeMillis()

        try {
            while (totalSamples < maxSamples && (System.currentTimeMillis() - startTime) < maxDurationMs + 1000) {
                val read = recorder.readChunk(readBuffer.size) ?: break
                if (read.isEmpty()) {
                    Thread.sleep(50)
                    continue
                }
                for (sample in read) {
                    allAudio.add(sample)
                }
                totalSamples += read.size

                while (allAudio.size >= segmentSamples) {
                    val segment = allAudio.subList(0, segmentSamples).toFloatArray()
                    allAudio.subList(0, segmentSamples).clear()
                    val text = transcribeLocal(segment, modelDir)
                    Log.i(TAG, "recordInSegmentsOffline: segment result '$text'")
                    onSegmentResult(text, false)
                }
            }

            if (allAudio.isNotEmpty()) {
                val remaining = allAudio.toFloatArray()
                val text = transcribeLocal(remaining, modelDir)
                Log.i(TAG, "recordInSegmentsOffline: final result '$text'")
                onSegmentResult(text, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordInSegmentsOffline error: ${e.message}", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
        }
    }

    private fun MutableList<Float>.toFloatArray(): FloatArray = FloatArray(size) { this[it] }

    // ==================== 音频识别 ====================
    suspend fun transcribe(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (audioData.isEmpty()) return@withContext ""

        when (currentBackend) {
            Backend.LOCAL_SHERPA -> {
                val modelDir = findModelDir()
                if (modelDir == null) {
                    Log.e(TAG, "transcribe: model dir not found")
                    return@withContext ""
                }
                transcribeLocal(audioData, modelDir)
            }
            Backend.CLOUD_GROQ -> {
                val tempFile = saveTempWav(audioData)
                try {
                    transcribeGroq(tempFile)
                } finally {
                    tempFile.delete()
                }
            }
        }
    }

    private suspend fun transcribeLocal(
        audioData: FloatArray,
        modelDir: File
    ): String = withContext(Dispatchers.IO) {
        try {
            val modelType = if (isZipformerModel(modelDir)) {
                SherpaOnnxEngine.ModelType.ZIPFORMER
            } else if (File(modelDir, "encoder.onnx").exists() && File(modelDir, "decoder.onnx").exists()) {
                // 旧版 Paraformer 目录（无 joiner），尝试用 transducer 加载
                SherpaOnnxEngine.ModelType.ZIPFORMER
            } else {
                SherpaOnnxEngine.ModelType.SENSE_VOICE
            }
            Log.i(TAG, "transcribeLocal: modelDir=${modelDir.absolutePath}, type=$modelType")

            val offlineRec = sherpaEngine.createOfflineRecognizer(
                assetManager = null,
                modelDir = modelDir.absolutePath,
                modelType = modelType,
                numThreads = 2
            )

            if (offlineRec == null) {
                Log.e(TAG, "transcribeLocal: failed to create offline recognizer")
                return@withContext ""
            }

            val result = sherpaEngine.transcribeOffline(offlineRec, audioData)
            try { offlineRec.release() } catch (_: Exception) {}
            Log.i(TAG, "transcribeLocal result: \"$result\"")
            result
        } catch (e: Exception) {
            Log.e(TAG, "transcribeLocal error: ${e.message}", e)
            ""
        }
    }

    // ==================== 云端识别 ====================

    private suspend fun transcribeGroq(audioFile: File): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = modelManager.getGroqApiKey()
            if (apiKey.isNullOrBlank()) {
                Log.e(TAG, "Groq API key not configured")
                return@withContext ""
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("language", "zh")
                .build()

            val request = Request.Builder()
                .url(GROQ_API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = groqClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                json.optString("text", "").trim()
            } else {
                Log.e(TAG, "Groq API error: ${response.code} ${response.message}")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq transcribe error: ${e.message}", e)
            ""
        }
    }

    // ==================== WAV 写入工具 ====================

    private fun saveTempWav(audioData: FloatArray): File {
        val tempFile = File(context.cacheDir, "voice_temp.wav")
        val byteBuffer = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(byteBuffer)
        val dataSize = audioData.size * 2
        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(36 + dataSize))
        dos.writeBytes("WAVE")
        // fmt chunk
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16))
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt())
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt())
        dos.writeInt(Integer.reverseBytes(16000))
        dos.writeInt(Integer.reverseBytes(32000))
        dos.writeShort(java.lang.Short.reverseBytes(2).toInt())
        dos.writeShort(java.lang.Short.reverseBytes(16).toInt())
        // data chunk
        dos.writeBytes("data")
        dos.writeInt(Integer.reverseBytes(dataSize))
        for (sample in audioData) {
            val clamped = (sample.coerceIn(-1f, 1f) * 32767).toInt().toShort()
            dos.writeShort(clamped.toInt().ushr(8) or (clamped.toInt() and 0xFF).shl(8))
        }
        dos.flush()
        tempFile.writeBytes(byteBuffer.toByteArray())
        return tempFile
    }

    fun getRecorder(): VoiceRecorder = recorder

    /**
     * 紧急释放 AudioRecord（用于停止录音时立即中断 readChunk 阻塞）
     */
    fun releaseRecorder() {
        try {
            recorder.stop()
            recorder.release()
            Log.i(TAG, "releaseRecorder: AudioRecord released")
        } catch (e: Exception) {
            Log.w(TAG, "releaseRecorder: ${e.message}")
        }
    }

    /**
     * 中文数字转阿拉伯数字
     * 将识别结果中的中文数字（一二三四五六七八九十百千万亿）转换为阿拉伯数字
     * 例如："今天花了三百二十五元" → "今天花了325元"
     */
    /** 中文数字转阿拉伯数字（公开：供 CesiaInputMethod 在上屏/魔法路径兜底统一转换） */
    fun convertChineseDigitsToArabic(text: String): String {
        val chineseDigits: Map<Char, Long> = mapOf(
            '零' to 0L, '一' to 1L, '二' to 2L, '三' to 3L, '四' to 4L,
            '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L,
            '两' to 2L, '俩' to 2L, '仨' to 3L
        )
        val units: Map<Char, Long> = mapOf(
            '十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10000L, '亿' to 100000000L
        )
        // 序数前缀：后面紧跟的中文数字不当作数值转换（如“第一次”保持“第一次”）
        val ordinalPrefixes = setOf('第', '初')

        val result = StringBuilder()
        val chars = text.toCharArray()
        var i = 0

        while (i < chars.size) {
            val c = chars[i]
            if (c in chineseDigits || c in units) {
                // 收集连续的数字片段
                val numStart = i
                var numEnd = i
                while (numEnd < chars.size && (chars[numEnd] in chineseDigits || chars[numEnd] in units)) {
                    numEnd++
                }
                val chineseNumStr = text.substring(numStart, numEnd)
                // 序数：前面是“第/初”则不转换，原样保留
                val prev = if (numStart > 0) chars[numStart - 1] else null
                if (prev != null && prev in ordinalPrefixes) {
                    result.append(chineseNumStr)
                } else if (chineseNumStr.any { it in units }) {
                    // 含单位（十百千万亿）：按数值解析
                    val arabicValue = parseChineseNumber(chineseNumStr, chineseDigits, units)
                    if (arabicValue != null) {
                        result.append(arabicValue)
                    } else {
                        result.append(chineseNumStr)
                    }
                } else if (chineseNumStr.length == 1) {
                    // 单个中文数字：保持汉字（如“五”→“五”，不转“5”）
                    result.append(chineseNumStr)
                } else {
                    // 两个及以上连续中文数字：转阿拉伯，保留原顺序（如“四三五”→“435”）
                    for (ch in chineseNumStr) {
                        val d = chineseDigits[ch]
                        if (d != null) result.append(d) else result.append(ch)
                    }
                }
                i = numEnd
            } else {
                result.append(c)
                i++
            }
        }
        return cleanupOrdinalSpacing(result.toString())
    }

    /**
     * 清理 ASR 分段在序数词内部插入的空格和前缀重复：
     * - "第 一" / "第 七" → "第一" / "第七"（去掉 第/初 后紧跟的空格）
     * - "第 第一" / "第第一" → "第一"（折叠重复的序数前缀）
     * 注意：只处理序数前缀，语段之间的空格（如 "第一 第二"）必须保留，撤销分段依赖它。
     */
    private fun cleanupOrdinalSpacing(text: String): String {
        var s = text
        // 折叠重复序数前缀："第 第" / "第第" → "第"（初 同理）
        s = s.replace(Regex("第[\\s]*第"), "第")
        s = s.replace(Regex("初[\\s]*初"), "初")
        // 去掉序数前缀与其后数字之间的空格："第 一" → "第一"
        s = s.replace(Regex("第[\\s]+"), "第")
        s = s.replace(Regex("初[\\s]+"), "初")
        return s
    }

    /**
     * 解析含单位的中文数字字符串为 Long 值
     * 支持：零-九、十、百、千、万、亿（不含单位的多数字串不应传入此处）
     */
    private fun parseChineseNumber(s: String, digits: Map<Char, Long>, units: Map<Char, Long>): Long? {
        if (s.isEmpty()) return null
        // 单个数字
        if (s.length == 1) {
            val d = digits[s[0]]
            if (d != null) return d
            if (s[0] == '十') return 10L
            return null
        }
        try {
            var result = 0L
            var current = 0L
            var hasDigit = false
            for (c in s) {
                val d = digits[c]
                if (d != null) {
                    current = d
                    hasDigit = true
                } else {
                    val unit = units[c] ?: return null
                    if (unit == 10000L || unit == 100000000L) {
                        // 万/亿：前面的累加值乘以单位
                        if (current == 0L && !hasDigit) current = 1L
                        result = (result + current) * unit
                        current = 0L
                        hasDigit = false
                    } else {
                        // 十/百/千：当前数字乘以单位
                        if (current == 0L) current = 1L
                        result += current * unit
                        current = 0L
                        hasDigit = false
                    }
                }
            }
            result += current
            return result
        } catch (_: Exception) {
            return null

        }
    }

    /**
     * 命令词检测
     * 检查文本末尾是否包含命令词（支持正体：识别为繁体时，命令词存简体，
     * 先归一简体再匹配；剥离时保留原文繁体，仅去掉命令词本身）。
     * 返回 Pair(命令词前的文本, 命令词类型) 或 null
     */
    internal fun checkCommandWord(text: String): Pair<String, String>? {
        val trimmed = text.trimEnd()
        // 正体模式：识别结果可能是繁体(如"寫作")，命令词存简体("写作")，
        // 归一为简体再匹配，避免繁体识别导致命令词失效。
        val norm = OpenCCConverter.toSimplified(trimmed)
        return when {
            norm.endsWith(cmdExit) -> Pair(stripCommandWord(trimmed, cmdExit), "exit")
            norm.endsWith(cmdSend) -> Pair(stripCommandWord(trimmed, cmdSend), "send")
            norm.endsWith(cmdPolish) -> Pair(stripCommandWord(trimmed, cmdPolish), "ai")
            norm.endsWith(cmdFinish) -> Pair(stripCommandWord(trimmed, cmdFinish), "finish")
            norm.endsWith(cmdCommand) -> Pair(stripCommandWord(trimmed, cmdCommand), "cmd")
            norm.endsWith(cmdWriting) -> Pair(stripCommandWord(trimmed, cmdWriting), "writing")
            norm.endsWith(cmdUndo) -> Pair(stripCommandWord(trimmed, cmdUndo), "undo")
            norm.endsWith(cmdClear) -> Pair(stripCommandWord(trimmed, cmdClear), "clear")
            norm.endsWith(cmdRestore) -> Pair(stripCommandWord(trimmed, cmdRestore), "restore")
            else -> null
        }
    }

    /**
     * 从原文(可能繁体)末尾剥离命令词：优先按命令词的繁体形式剥离(保留原文繁体其余部分)，
     * 兜底按简体词长剥离(简繁通常等长)。返回剥离后的文本。
     */
    private fun stripCommandWord(originalTrimmed: String, cmdSimplified: String): String {
        val tradCmd = OpenCCConverter.toTraditional(cmdSimplified)
        val stripped = if (tradCmd.isNotEmpty() && originalTrimmed.endsWith(tradCmd)) {
            originalTrimmed.dropLast(tradCmd.length)
        } else {
            originalTrimmed.dropLast(cmdSimplified.length)
        }
        return stripped.trimEnd()
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            (recognizer as? com.k2fsa.sherpa.onnx.OfflineRecognizer)?.release()
            recognizer = null
            recognizerLoaded = false
            Log.i(TAG, "VoiceEngine released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
    }
}
