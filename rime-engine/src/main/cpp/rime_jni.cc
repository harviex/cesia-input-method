#include <jni.h>
#include <string>
#include <vector>
#include <memory>

// librime API
#include <rime_api.h>

#define MAX_BUFFER_LENGTH 2048

// 声明 librime 模块依赖
extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();

static void declare_librime_module_dependencies() {
    rime_require_module_lua();
    rime_require_module_octagram();
    rime_require_module_predict();
}

// Rime 引擎单例
class RimeEngine {
public:
    RimeEngine() : api_(rime_get_api()) {}

    static RimeEngine& Instance() {
        static RimeEngine instance;
        return instance;
    }

    void startup(const char* sharedDir, const char* userDataDir, const char* versionName, bool fullCheck) {
        if (!api_) return;

        RIME_STRUCT(RimeTraits, traits);
        traits.shared_data_dir = sharedDir;
        traits.user_data_dir = userDataDir;
        traits.log_dir = "";
        traits.app_name = "rime.cesia";
        traits.distribution_name = "Cesia";
        traits.distribution_code_name = "cesia";
        traits.distribution_version = versionName;

        api_->setup(&traits);
        api_->initialize(&traits);
        api_->start_maintenance(fullCheck);
    }

    void exit() {
        session_id_ = 0;
        api_->finalize();
    }

    bool processKey(int keycode, int mask) {
        return api_->process_key(session_id_, keycode, mask);
    }

    bool commitComposition() {
        return api_->commit_composition(session_id_);
    }

    void clearComposition() {
        api_->clear_composition(session_id_);
    }

    std::string getCommitText() {
        RIME_STRUCT(RimeCommit, data);
        if (api_->get_commit(session_id_, &data)) {
            std::string result(data.text);
            api_->free_commit(&data);
            return result;
        }
        return "";
    }

    std::string getContextText() {
        RIME_STRUCT(RimeContext, data);
        if (api_->get_context(session_id_, &data)) {
            std::string result;
            if (data.composition.preedit) {
                result = data.composition.preedit;
            }
            api_->free_context(&data);
            return result;
        }
        return "";
    }

    std::vector<std::string> getCandidates(int startIndex, int limit) {
        std::vector<std::string> result;
        RimeCandidateListIterator iter{};
        if (api_->candidate_list_from_index(session_id_, &iter, startIndex)) {
            int count = 0;
            while (api_->candidate_list_next(&iter)) {
                if (count >= limit) break;
                if (iter.candidate.text) {
                    result.emplace_back(iter.candidate.text);
                }
                ++count;
            }
            api_->candidate_list_end(&iter);
        }
        return result;
    }

    std::string getCurrentSchema() {
        char result[MAX_BUFFER_LENGTH];
        return api_->get_current_schema(session_id_, result, MAX_BUFFER_LENGTH)
               ? std::string(result) : "";
    }

    std::vector<std::string> getSchemaList() {
        std::vector<std::string> result;
        RimeSchemaList list{};
        if (api_->get_schema_list(&list)) {
            for (size_t i = 0; i < list.size; ++i) {
                if (list.list[i].schema_id) {
                    result.emplace_back(list.list[i].schema_id);
                }
            }
            api_->free_schema_list(&list);
        }
        return result;
    }

    bool selectSchema(const char* schemaId) {
        return api_->select_schema(session_id_, schemaId);
    }

    bool selectCandidate(int index, bool global) {
        if (global) {
            return api_->select_candidate(session_id_, index);
        } else {
            return api_->select_candidate_on_current_page(session_id_, index);
        }
    }

    bool deleteCandidate(int index, bool global) {
        if (global) {
            return api_->delete_candidate(session_id_, index);
        } else {
            return api_->delete_candidate_on_current_page(session_id_, index);
        }
    }

    bool changePage(bool backward) {
        return api_->change_page(session_id_, backward);
    }

    std::string getRawInput() {
        auto cStr = api_->get_input(session_id_);
        return cStr ? std::string(cStr) : "";
    }

    int getCaretPos() {
        return static_cast<int>(api_->get_caret_pos(session_id_));
    }

    void setCaretPosition(int caretPos) {
        api_->set_caret_pos(session_id_, static_cast<size_t>(caretPos));
    }

    bool simulateKeySequence(const char* sequence) {
        return api_->simulate_key_sequence(session_id_, sequence);
    }

    void setOption(const char* key, bool value) {
        api_->set_option(session_id_, key, value);
    }

    bool getOption(const char* key) {
        return api_->get_option(session_id_, key);
    }

    bool deploySchema(const char* schemaFile) {
        return api_->deploy_schema(schemaFile);
    }

    bool deployConfigFile(const char* configFile, const char* versionKey) {
        return api_->deploy_config_file(configFile, versionKey);
    }

    bool sync() {
        session_id_ = 0;
        return api_->sync_user_data();
    }

private:
    RimeApi* api_;
    RimeSessionId session_id_ = 0;
};

// JNI 辅助函数
static std::string JStringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

static jstring StringToJString(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

static jobjectArray StringVectorToJArray(JNIEnv* env, const std::vector<std::string>& vec) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(vec.size(), stringClass, nullptr);
    for (size_t i = 0; i < vec.size(); ++i) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(vec[i].c_str()));
    }
    return result;
}

// ========== JNI 方法实现 ==========

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void* reserved) {
    declare_librime_module_dependencies();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_rime_jni_RimeJni_startup(
    JNIEnv* env, jclass clazz,
    jstring sharedDir, jstring userDataDir,
    jstring versionName, jboolean fullCheck) {
    RimeEngine::Instance().startup(
        JStringToString(env, sharedDir).c_str(),
        JStringToString(env, userDataDir).c_str(),
        JStringToString(env, versionName).c_str(),
        fullCheck
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_rime_jni_RimeJni_exit(JNIEnv* env, jclass clazz) {
    RimeEngine::Instance().exit();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_deploySchema(
    JNIEnv* env, jclass clazz, jstring schemaFile) {
    return RimeEngine::Instance().deploySchema(JStringToString(env, schemaFile).c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_deployConfigFile(
    JNIEnv* env, jclass clazz, jstring fileName, jstring versionKey) {
    return RimeEngine::Instance().deployConfigFile(
        JStringToString(env, fileName).c_str(),
        JStringToString(env, versionKey).c_str()
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_syncUserData(JNIEnv* env, jclass clazz) {
    return RimeEngine::Instance().sync();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_processKey(
    JNIEnv* env, jclass clazz, jint keyCode, jint mask) {
    return RimeEngine::Instance().processKey(keyCode, mask);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_commitComposition(JNIEnv* env, jclass clazz) {
    return RimeEngine::Instance().commitComposition();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_rime_jni_RimeJni_clearComposition(JNIEnv* env, jclass clazz) {
    RimeEngine::Instance().clearComposition();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_rime_jni_RimeJni_getCommitText(JNIEnv* env, jclass clazz) {
    return StringToJString(env, RimeEngine::Instance().getCommitText());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_rime_jni_RimeJni_getContextText(JNIEnv* env, jclass clazz) {
    return StringToJString(env, RimeEngine::Instance().getContextText());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_cesia_rime_jni_RimeJni_getCandidates(
    JNIEnv* env, jclass clazz, jint startIndex, jint limit) {
    return StringVectorToJArray(env, RimeEngine::Instance().getCandidates(startIndex, limit));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_rime_jni_RimeJni_getStatusSchemaId(JNIEnv* env, jclass clazz) {
    return StringToJString(env, RimeEngine::Instance().getCurrentSchema());
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_rime_jni_RimeJni_setOption(
    JNIEnv* env, jclass clazz, jstring option, jboolean value) {
    RimeEngine::Instance().setOption(JStringToString(env, option).c_str(), value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_getOption(
    JNIEnv* env, jclass clazz, jstring option) {
    return RimeEngine::Instance().getOption(JStringToString(env, option).c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_cesia_rime_jni_RimeJni_getSchemaList(JNIEnv* env, jclass clazz) {
    return StringVectorToJArray(env, RimeEngine::Instance().getSchemaList());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_rime_jni_RimeJni_getCurrentSchema(JNIEnv* env, jclass clazz) {
    return StringToJString(env, RimeEngine::Instance().getCurrentSchema());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_selectSchema(
    JNIEnv* env, jclass clazz, jstring schemaId) {
    return RimeEngine::Instance().selectSchema(JStringToString(env, schemaId).c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_selectCandidate(
    JNIEnv* env, jclass clazz, jint index, jboolean global) {
    return RimeEngine::Instance().selectCandidate(index, global);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_deleteCandidate(
    JNIEnv* env, jclass clazz, jint index, jboolean global) {
    return RimeEngine::Instance().deleteCandidate(index, global);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_changePage(
    JNIEnv* env, jclass clazz, jboolean backward) {
    return RimeEngine::Instance().changePage(backward);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_rime_jni_RimeJni_getRawInput(JNIEnv* env, jclass clazz) {
    return StringToJString(env, RimeEngine::Instance().getRawInput());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cesia_rime_jni_RimeJni_getCaretPos(JNIEnv* env, jclass clazz) {
    return RimeEngine::Instance().getCaretPos();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_rime_jni_RimeJni_setCaretPosition(
    JNIEnv* env, jclass clazz, jint caretPos) {
    RimeEngine::Instance().setCaretPosition(caretPos);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_rime_jni_RimeJni_simulateKeySequence(
    JNIEnv* env, jclass clazz, jstring keySequence) {
    return RimeEngine::Instance().simulateKeySequence(JStringToString(env, keySequence).c_str());
}