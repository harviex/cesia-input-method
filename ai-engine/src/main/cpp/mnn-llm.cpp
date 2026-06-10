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
static std::string g_lastError;

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
    g_lastError.clear();

    try {
        // 检查 config 文件是否存在
        {
            FILE* f = fopen(configStr.c_str(), "r");
            if (!f) {
                g_lastError = "config.json not found: " + configStr;
                LOGE("%s", g_lastError.c_str());
                return JNI_FALSE;
            }
            fclose(f);
        }

        g_llm = Llm::createLLM(configStr);
        if (g_llm == nullptr) {
            g_lastError = "Llm::createLLM returned null";
            LOGE("%s", g_lastError.c_str());
            return JNI_FALSE;
        }

        // 配置：纯 CPU 异步模式（兼容性最好）
        g_llm->set_config(R"({"async":true})");
        // 关闭 thinking（Qwen3.5 默认可能开启）
        g_llm->set_config(R"({"jinja":{"context":{"enable_thinking":false}}})");

        LOGI("Calling llm->load()...");
        bool loaded = g_llm->load();
        if (!loaded) {
            // 收集 MNN 内部日志
            std::string mnnLog;
            try {
                mnnLog = g_llm->getLog();
            } catch (...) {}

            // 检查模型文件是否存在且非空
            std::string modelDir = configStr.substr(0, configStr.rfind('/') + 1);
            const char* checkFiles[] = {"llm.mnn", "llm.mnn.weight", "llm.mnn", "embedding.mnn", "lm.mnn"};
            std::string fileStatus;
            for (const char* fname : checkFiles) {
                std::string fpath = modelDir + fname;
                FILE* f = fopen(fpath.c_str(), "r");
                if (f) {
                    fseek(f, 0, SEEK_END);
                    long sz = ftell(f);
                    fclose(f);
                    fileStatus += fname;
                    fileStatus += sz > 0 ? "(OK " + std::to_string(sz/1024/1024) + "MB) " : "(EMPTY!) ";
                } else {
                    fileStatus += fname;
                    fileStatus += "(missing) ";
                }
            }

            g_lastError = "llm->load() failed.\nFiles: " + fileStatus;
            if (!mnnLog.empty()) {
                g_lastError += "\nMNN log: " + mnnLog;
            }
            LOGE("%s", g_lastError.c_str());

            Llm::destroy(g_llm);
            g_llm = nullptr;
            return JNI_FALSE;
        }

        LOGI("MNN LLM loaded successfully");
        return JNI_TRUE;

    } catch (const std::exception& e) {
        g_lastError = std::string("Exception: ") + e.what();
        LOGE("%s", g_lastError.c_str());
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
        // 使用与云端 OpenRouter 完全相同的 prompt 格式
        // system: 短指令，只说"做什么"不说"不要做什么"
        // user: 直接传原文
        // 关键差异：云端用 temperature=0.3 + stop tokens，本地用 end_with="\n"
        std::vector<MNN::Transformer::ChatMessage> messages;
        messages.push_back({"system",
            "你是一个文本编辑助手。根据用户指令修改原文。只输出修改后的文本，不要解释。"
        });
        messages.push_back({"user", promptStr});

        std::ostringstream outputStream;

        // end_with 用换行，避免输入中的句号被误判为结束
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

        // 0. 去除模型可能输出的前缀（如"改写的文字："、"润色后："、"？"等）
        {
            const char* prefixes[] = {
                "改写的文字：", "改写后：", "润色后：", "润色：",
                "修改后：", "处理后：", "输出：", "结果：", "？", "?"
            };
            for (const char* prefix : prefixes) {
                size_t len = strlen(prefix);
                if (result.size() >= len && result.compare(0, len, prefix) == 0) {
                    result = result.substr(len);
                    while (!result.empty() && (result.front() == ' ' || result.front() == '\n'))
                        result.erase(result.begin());
                    LOGI("Removed prefix '%s'", prefix);
                    break;
                }
            }
        }

        // 1. 续写检测：如果结果中出现以下模式，截断到该位置之前
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

        // 重复检测：查找是否有句子重复出现（如"XXX。XXX。XXX。"）
        // 按句号/问号/感叹号分句，如果有句子出现超过1次，只保留第一次
        // 注意：中文标点是 UTF-8 multibyte，用 memcmp 比较 3 字节
        {
            std::vector<std::string> sentences;
            std::string current;
            for (size_t i = 0; i < result.size(); ) {
                unsigned char c = (unsigned char)result[i];
                // UTF-8 中文标点：句(0xe38082) 问(0xe388b7) 叹(0xe38081) — 3字节
                bool isChinesePunct = false;
                if (i + 2 < result.size() && c == 0xe3) {
                    unsigned char c2 = (unsigned char)result[i+1];
                    unsigned char c3 = (unsigned char)result[i+2];
                    if ((c2 == 0x80 && c3 == 0x82) ||  // 。
                        (c2 == 0x80 && c3 == 0x81) ||  // ！
                        (c2 == 0x88 && c3 == 0xb7)) {   // ？
                        isChinesePunct = true;
                    }
                }
                if (isChinesePunct || c == '\n') {
                    current += result.substr(i, isChinesePunct ? 3 : 1);
                    // 去掉首尾空白
                    while (!current.empty() && (current.front() == ' ' || current.front() == '\n' || current.front() == '\t'))
                        current.erase(current.begin());
                    while (!current.empty() && (current.back() == ' ' || current.back() == '\n' || current.back() == '\t'))
                        current.pop_back();
                    if (!current.empty()) sentences.push_back(current);
                    current.clear();
                    i += isChinesePunct ? 3 : 1;
                } else {
                    current += result[i];
                    i++;
                }
            }
            if (!current.empty()) {
                while (!current.empty() && (current.front() == ' ' || current.front() == '\n' || current.front() == '\t'))
                    current.erase(current.begin());
                while (!current.empty() && (current.back() == ' ' || current.back() == '\n' || current.back() == '\t'))
                    current.pop_back();
                if (!current.empty()) sentences.push_back(current);
            }

            // 去重：只保留第一次出现的句子
            std::vector<std::string> uniqueSentences;
            for (const auto& s : sentences) {
                bool found = false;
                for (const auto& u : uniqueSentences) {
                    if (s == u) { found = true; break; }
                }
                if (!found) uniqueSentences.push_back(s);
            }

            if (uniqueSentences.size() < sentences.size()) {
                LOGI("Dedup: %d sentences -> %d unique", (int)sentences.size(), (int)uniqueSentences.size());
                result.clear();
                for (size_t i = 0; i < uniqueSentences.size(); i++) {
                    if (i > 0) result += "\xe3\x80\x82";  // 。
                    // 去掉原有句末标点（3 字节）
                    std::string s = uniqueSentences[i];
                    while (s.size() >= 3) {
                        unsigned char c1 = (unsigned char)s[s.size()-3];
                        unsigned char c2 = (unsigned char)s[s.size()-2];
                        unsigned char c3 = (unsigned char)s[s.size()-1];
                        if ((c1 == 0xe3 && c2 == 0x80 && c3 == 0x82) ||
                            (c1 == 0xe3 && c2 == 0x80 && c3 == 0x81) ||
                            (c1 == 0xe3 && c2 == 0x88 && c3 == 0xb7)) {
                            s = s.substr(0, s.size() - 3);
                        } else break;
                    }
                    result += s + "\xe3\x80\x82";  // 。
                }
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
        // end_with 用换行，避免输入中的句号被误判为结束
        g_llm->response(promptStr, &outputStream, "\n", maxTokens);

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
    if (g_llm != nullptr) {
        return env->NewStringUTF(g_llm->getLog().c_str());
    }
    // 如果 llm 已销毁（加载失败），返回保存的错误信息
    return env->NewStringUTF(g_lastError.c_str());
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
