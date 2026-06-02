package com.cesia.input.engine.ai

import android.util.Log

/**
 * whisper.cpp 本地语音识别引擎
 *
 * 使用前需:
 * 1. 下载 GGUF 模型到本地文件
 * 2. 调用 init() 加载模型
 * 3. 调用 transcribe() 识别音频
 * 4. 调用 release() 释放资源
 *
 * JNI 对应: ai-engine/src/main/cpp/whisper-jni.cpp
 */
class WhisperEngine {

    companion object {
        private const val TAG = "WhisperEngine"

        init {
            try {
                System.loadLibrary("native-bridge")
                Log.i(TAG, "native-bridge loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native-bridge", e)
            }
        }
    }

    /**
     * 初始化 whisper 模型
     * @param modelPath 模型文件的绝对路径（.bin 格式）
     * @param useGpu 是否使用 Vulkan GPU 加速
     * @return 是否成功
     */
    external fun nativeInit(modelPath: String, useGpu: Boolean): Boolean

    /**
     * 识别音频
     * @param audioData 16kHz 单声道 PCM float[-1.0, 1.0]
     * @return 识别出的文本
     */
    external fun nativeTranscribe(audioData: FloatArray): String

    /**
     * 释放模型资源
     */
    external fun nativeFree()
}
