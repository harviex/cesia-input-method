#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "CesiaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
    
    JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
        JNIEnv* env;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            return JNI_ERR;
        }
        LOGI("Cesia JNI loaded successfully");
        return JNI_VERSION_1_6;
    }
    
    // Native code support will be added in future versions
    // Currently the app uses pure Kotlin/Java for all functionality
}
