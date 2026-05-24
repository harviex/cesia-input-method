#ifndef CESIA_RIME_JNI_H
#define CESIA_RIME_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeInitialize(JNIEnv *env, jobject thiz,
                                                          jstring data_dir, jstring shared_dir);

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeShutdown(JNIEnv *env, jobject thiz);

JNIEXPORT jlong JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeCreateSession(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeDestroySession(JNIEnv *env, jobject thiz,
                                                              jlong session_id);

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeProcessKey(JNIEnv *env, jobject thiz,
                                                          jlong session_id, jstring key);

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetComposingText(JNIEnv *env, jobject thiz,
                                                                 jlong session_id);

JNIEXPORT jobjectArray JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCandidates(JNIEnv *env, jobject thiz,
                                                             jlong session_id);

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeCommitComposition(JNIEnv *env, jobject thiz,
                                                                 jlong session_id);

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeSelectCandidate(JNIEnv *env, jobject thiz,
                                                               jlong session_id, jint index);

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeClearComposition(JNIEnv *env, jobject thiz,
                                                                jlong session_id);

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeChangePage(JNIEnv *env, jobject thiz,
                                                          jlong session_id, jboolean backward);

JNIEXPORT jint JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetPageCount(JNIEnv *env, jobject thiz,
                                                            jlong session_id);

JNIEXPORT jint JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCurrentPage(JNIEnv *env, jobject thiz,
                                                              jlong session_id);

#ifdef __cplusplus
}
#endif

#endif // CESIA_RIME_JNI_H
