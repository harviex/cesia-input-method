package com.cesia.input.engine.ai

import android.util.Log

/**
 * llama.cpp 本地 AI 推理引擎
 *
 * 使用前需:
 * 1. 下载 GGUF 模型到本地文件
 * 2. 调用 init() 加载模型
 * 3. 调用 generate() 生成文本
 * 4. 调用 release() 释放资源
 *
 * JNI 对应: ai-engine/src/main/cpp/llama-jni.cpp
 */
class LlamaEngine {

    companion object {
        private const val TAG = "LlamaEngine"

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
     * 初始化 llama 模型
     * @param modelPath GGUF 模型文件的绝对路径
     * @param nGpuLayers 多少层 offload 到 GPU（99=全部，0=纯CPU）
     * @return 是否成功
     */
    external fun nativeInit(modelPath: String, nGpuLayers: Int): Boolean

    /**
     * 生成文本（同步 — 会阻塞直到完成）
     * @param prompt 输入 prompt
     * @param maxTokens 最大生成 token 数
     * @return 生成的文本
     */
    external fun nativeGenerate(prompt: String, maxTokens: Int): String

    /**
     * 释放模型资源
     */
    external fun nativeFree()
}
