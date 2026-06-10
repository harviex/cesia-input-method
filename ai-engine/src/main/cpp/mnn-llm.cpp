//
//  mnn-llm.cpp — MNN LLM JNI bridge for Cesia
//  JNI 对应: com.cesia.input.engine.ai.MNNEngine
//

#include <jni.h>
#include <string>
#include <memory>
#include <sstream>
#include <android/log.h>

#include "MNN/llm/llm.hpp"

#define LOG_TAG "MNN-LLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace MNN::Transformer;

static Llm* g_llm = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_MNNEngine_nativeInit(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring configPath
) {
    if (g_llm != nullptr) {
        LOGI("LLM already loaded, destroying old instance first");
        Llm::destroy(g_llm);
        g_llm = nullptr;
    }

    const char* path = env->GetStringUTFChars(configPath, nullptr);
    std::string configStr(path);
    env->ReleaseStringUTFChars(configPath, path);

    LOGI("Loading MNN LLM from: %s", configStr.c_str());

    try {
        g_llm = Llm::createLLM(configStr);
        if (g_llm == nullptr) {
            LOGE("Llm::createLLM returned null");
            return JNI_FALSE;
        }

        // 配置：纯 CPU 异步模式（兼容性最好）
        g_llm->set_config(R"({"async":true})");
        // 关闭 thinking（Qwen2.5 默认开启，润色任务不需要推理链）
        g_llm->set_config(R"({"jinja":{"context":{"enable_thinking":false}}})");

        bool loaded = g_llm->load();
        if (!loaded) {
            LOGE("llm->load() failed");
            Llm::destroy(g_llm);
            g_llm = nullptr;
            return JNI_FALSE;
        }

        LOGI("MNN LLM loaded successfully");
        return JNI_TRUE;

    } catch (const std::exception& e) {
        LOGE("Exception during init: %s", e.what());
        if (g_llm) { Llm::destroy(g_llm); g_llm = nullptr; }
        return JNI_FALSE;
    }
}

/**
 * 同步生成文本（阻塞直到完成）
 * 与 a468509 版本保持一致，仅做最小修改：
 * - end_with 设为 "\n" 防止无限生成（原版本 nullptr 会生成到 maxTokens 上限）
 * - 输出截断到 500 字符（防止 OOM）
 */
JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_MNNEngine_nativeGenerate(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring prompt,
    jint maxTokens
) {
    if (g_llm == nullptr) {
        LOGE("nativeGenerate: LLM not initialized");
        return env->NewStringUTF("");
    }

    const char* promptC = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptC);
    env->ReleaseStringUTFChars(prompt, promptC);

    try {
        // 使用 ChatMessages 格式，让 MNN 自动应用模型的 chat template
        // system 指令必须非常强硬——1.5B 模型面对问句会条件反射地回答
        std::vector<MNN::Transformer::ChatMessage> messages;
        messages.push_back({"system",
            "你是一个中文文本编辑工具。你的唯一职责是：将输入的口语文字修改为通顺规范的书面表达。\n"
            "规则（必须严格执行，无一例外）：\n"
            "1. 只修改文字表达：修正错别字、修正口语化用词、调整语序、添加或修正标点符号\n"
            "2. 保持原文原意不变：不增加任何新信息、不删除任何原文内容\n"
            "3. 严禁回答原文中的任何问题：如果原文包含问句，保留问句原样只改文字，绝对不要给出答案\n"
            "4. 严禁续写：不要在原文之后添加任何新句子\n"
            "5. 严禁解释或评论：不要添加任何说明文字\n"
            "只输出修改后的文本，不要任何其他内容。"
        });

        // 在用户消息中明确告诉模型这段文字需要润色，强调不要回答问题
        std::string userPrompt = "以下是需要润色的口语文字。\n"
                                 "要求：只修改文字表达（错别字、口语、语序、标点），不改变原意，不回答文中的任何问题。\n"
                                 "原文：\n" + promptStr + "\n修改后：";
        messages.push_back({"user", userPrompt});

        std::ostringstream outputStream;

        // 安全包装：如果 response 抛出异常（如 OOM），捕获并返回空字符串
        // end_with 用换行而非句号，避免输入中的句号被误判为结束
        try {
            g_llm->response(messages, &outputStream, "\n", maxTokens);
        } catch (const std::bad_alloc& e) {
            LOGE("nativeGenerate: OOM during response - %s", e.what());
            return env->NewStringUTF("");
        } catch (const std::exception& e) {
            LOGE("nativeGenerate: exception during response - %s", e.what());
            return env->NewStringUTF("");
        }

        auto context = g_llm->getContext();
        if (context->status == LlmStatus::INTERNAL_ERROR) {
            LOGE("nativeGenerate: LLM internal error");
            return env->NewStringUTF("");
        }

        std::string result = outputStream.str();
        LOGI("Generate complete: %d chars, %d tokens",
             (int)result.size(), (int)context->gen_seq_len);

        // 续写检测：如果结果中出现以下模式，截断到该位置之前
        const char* continuationMarkers[] = {
            "这是一个问题", "所以请", "请注意", "需要说明", "我来解释",
            "我来回答", "这个问题的", "关于这个问题", "简单来说",
            "首先", "其次", "最后", "总之", "综上",
            "如果你想", "如果你想了解", "以下是", "当然",
            "我会推荐", "我建议", "你可以试试", "你可以考虑", "不如试试",
            "比如", "例如", "举例来说", "打个比方"
        };
        for (const char* marker : continuationMarkers) {
            std::string::size_type pos = result.find(marker);
            if (pos != std::string::npos && pos > 5) {
                result = result.substr(0, pos);
                while (!result.empty() && (result.back() == ' ' || result.back() == '\n' || result.back() == '\t')) {
                    result.pop_back();
                }
                LOGI("Truncation: removed continuation at '%s', result now %d chars", marker, (int)result.size());
                break;
            }
        }

        return env->NewStringUTF(result.c_str());

    } catch (const std::bad_alloc& e) {
        LOGE("nativeGenerate: OOM - %s", e.what());
        return env->NewStringUTF("");
    } catch (const std::exception& e) {
        LOGE("nativeGenerate: exception - %s", e.what());
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_MNNEngine_nativeGenerateStreaming(
    JNIEnv* env,
    jobject thiz,
    jstring prompt,
    jint maxTokens,
    jobject callback
) {
    if (g_llm == nullptr || callback == nullptr) {
        LOGE("nativeGenerateStreaming: LLM or callback is null");
        return;
    }

    const char* promptC = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptC);
    env->ReleaseStringUTFChars(prompt, promptC);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");
    jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");

    if (!onToken || !onComplete || !onError) {
        LOGE("nativeGenerateStreaming: callback methods not found");
        return;
    }

    try {
        std::ostringstream outputStream;
        // end_with="。"：遇到句号就停止，让润色结果在句子边界处结束
        g_llm->response(promptStr, &outputStream, "。", maxTokens);

        auto context = g_llm->getContext();
        if (context->status == LlmStatus::INTERNAL_ERROR) {
            std::string errMsg = "LLM internal error";
            env->CallVoidMethod(callback, onError, env->NewStringUTF(errMsg.c_str()));
        } else {
            std::string result = outputStream.str();
            if (!result.empty()) {
                jstring jResult = env->NewStringUTF(result.c_str());
                env->CallVoidMethod(callback, onToken, jResult);
                env->DeleteLocalRef(jResult);
            }
            env->CallVoidMethod(callback, onComplete);
        }

    } catch (const std::exception& e) {
        std::string errMsg = std::string("Exception: ") + e.what();
        env->CallVoidMethod(callback, onError, env->NewStringUTF(errMsg.c_str()));
    }
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_MNNEngine_nativeFree(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    if (g_llm != nullptr) {
        LOGI("Destroying MNN LLM");
        Llm::destroy(g_llm);
        g_llm = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_MNNEngine_nativeIsLoaded(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    return (g_llm != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_MNNEngine_nativeGetLog(
    JNIEnv* env,
    jobject /*thiz*/
) {
    if (g_llm == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(g_llm->getLog().c_str());
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_MNNEngine_nativeReset(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    if (g_llm != nullptr) {
        LOGI("Resetting LLM context");
        g_llm->reset();
    }
}

} // extern "C"
