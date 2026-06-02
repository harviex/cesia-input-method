#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "CesiaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAS_WHISPER
#include "whisper.h"

struct WhisperHandle {
    whisper_context* ctx = nullptr;
};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeInit(
        JNIEnv* env, jobject /* this */, jstring modelPath, jboolean useGpu) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu;

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to load whisper model");
        return JNI_FALSE;
    }

    // TODO: store ctx in a global map keyed by thread/instance
    LOGI("Whisper model loaded successfully (gpu=%d)", useGpu);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeTranscribe(
        JNIEnv* env, jobject /* this */, jfloatArray audioData) {

    // TODO: implement — get stored context, call whisper_full, return text
    LOGI("transcribe called — not yet implemented");
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeFree(
        JNIEnv* /* env */, jobject /* this */) {
    // TODO: free stored context
    LOGI("whisper free called");
}

} // extern "C"

#else // !HAS_WHISPER — 空壳占位

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeInit(
        JNIEnv* /* env */, jobject, jstring, jboolean) {
    LOGE("whisper.cpp not compiled in — stub");
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeTranscribe(
        JNIEnv* env, jobject, jfloatArray) {
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeFree(
        JNIEnv*, jobject) {}

} // extern "C"
#endif // HAS_WHISPER
