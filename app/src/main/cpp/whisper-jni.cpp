#include <jni.h>
#include <string>
#include <android/log.h>
#include "wenet_model.h"

#define LOG_TAG "WenetJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
    
    JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
        JNIEnv* env;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            return JNI_ERR;
        }
        return JNI_VERSION_1_6;
    }
    
    JNIEXPORT void JNICALL
    Java_com_cesia_input_wenet_WenetManager_initWenet(
        JNIEnv* env, jobject /* this */, jstring modelPath, jstring unitPath) {
        
        const char* model_cstr = env->GetStringUTFChars(modelPath, nullptr);
        const char* unit_cstr = env->GetStringUTFChars(unitPath, nullptr);
        
        std::string model_path(model_cstr);
        std::string unit_path(unit_cstr);
        
        LOGI("Initializing Wenet with model: %s", model_cstr);
        
        // TODO: 初始化WeNet模型
        // WenetModel::Init(model_path, unit_path);
        
        env->ReleaseStringUTFChars(modelPath, model_cstr);
        env->ReleaseStringUTFChars(unitPath, unit_cstr);
    }
    
    JNIEXPORT jstring JNICALL
    Java_com_cesia_input_wenet_WenetManager_recognizeAudio(
        JNIEnv* env, jobject /* this */, jstring pcmPath) {
        
        const char* pcm_cstr = env->GetStringUTFChars(pcmPath, nullptr);
        std::string pcm_path(pcm_cstr);
        
        LOGI("Recognizing audio: %s", pcm_cstr);
        
        // TODO: 调用WeNet识别
        std::string result = "识别结果示例"; // WenetModel::Recognize(pcm_path);
        
        env->ReleaseStringUTFChars(pcmPath, pcm_cstr);
        
        return env->NewStringUTF(result.c_str());
    }
}
