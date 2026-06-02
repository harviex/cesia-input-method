#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "CesiaLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAS_LLAMA
#include "llama.h"

struct LlamaHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeInit(
        JNIEnv* env, jobject /* this */, jstring modelPath, jint nGpuLayers) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;

    llama_model* model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load llama model");
        return JNI_FALSE;
    }

    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        llama_free_model(model);
        LOGE("Failed to create llama context");
        return JNI_FALSE;
    }

    LOGI("Llama model loaded (gpu_layers=%d)", nGpuLayers);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject /* this */, jstring prompt, jint maxTokens) {

    // TODO: implement — tokenize, run inference, decode, return text
    LOGI("generate called — not yet implemented");
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeFree(
        JNIEnv* /* env */, jobject /* this */) {
    // TODO: free model + context
    LOGI("llama free called");
}

} // extern "C"

#else // !HAS_LLAMA — 空壳占位

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeInit(
        JNIEnv* /* env */, jobject, jstring, jint) {
    LOGE("llama.cpp not compiled in — stub");
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject, jstring, jint) {
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeFree(
        JNIEnv*, jobject) {}

} // extern "C"
#endif // HAS_LLAMA
