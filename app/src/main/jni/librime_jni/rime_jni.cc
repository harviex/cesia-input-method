// SPDX-FileCopyrightText: 2015 - 2024 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later

// Simplified Rime JNI - returns primitive types only

#include <jni.h>
#include <memory>
#include <string>
#include <vector>

#include <rime_api.h>

static void rime_require_module_lua();
static void rime_require_module_octagram();
static void rime_require_module_predict();

static void declare_librime_module_dependencies() {
  rime_require_module_lua();
  rime_require_module_octagram();
  rime_require_module_predict();
}

class Rime {
 public:
  Rime() : api_(rime_get_api()), session_id_(0) {}
  Rime(Rime const &) = delete;
  void operator=(Rime const &) = delete;

  static Rime &Instance() {
    static Rime instance;
    return instance;
  }

  void startup(const char *shared_dir, const char *user_dir) {
    if (!api_) return;
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared_dir;
    traits.user_data_dir = user_dir;
    traits.log_dir = "";
    traits.app_name = "com.cesia.input";
    traits.distribution_name = "Cesia";
    traits.distribution_code_name = "cesia";
    traits.distribution_version = "1.1.1";
    api_->setup(&traits);
    api_->initialize(&traits);
  }

  void ensureSession() {
    if (!session_id_) {
      session_id_ = api_->create_session();
    }
  }

  RimeSessionId session() {
    ensureSession();
    return session_id_;
  }

  bool processKey(int keycode, int mask) {
    return api_->process_key(session(), keycode, mask);
  }

  bool commitComposition() { return api_->commit_composition(session()); }
  void clearComposition() { api_->clear_composition(session()); }

  jstring getCommit(JNIEnv *env) {
    RIME_STRUCT(RimeCommit, data);
    if (api_->get_commit(session(), &data)) {
      jstring result = env->NewStringUTF(data.text ? data.text : "");
      api_->free_commit(&data);
      return result;
    }
    return env->NewStringUTF("");
  }

  // Returns preedit string via output parameter
  bool getPreedit(JNIEnv *env, std::string &preedit, int &cursor_pos) {
    RIME_STRUCT(RimeContext, ctx);
    if (!api_->get_context(session(), &ctx)) return false;
    preedit = ctx.composition.preedit ? ctx.composition.preedit : "";
    cursor_pos = ctx.composition.cursor_pos;
    api_->free_context(&ctx);
    return true;
  }

  // Returns input string
  jstring getInput(JNIEnv *env) {
    auto input = api_->get_input(session());
    return env->NewStringUTF(input ? input : "");
  }

  int getInputLength() {
    auto input = api_->get_input(session());
    if (!input) return 0;
    int len = 0;
    while (input[len]) len++;
    return len;
  }

  // Get candidates as string array
  jobjectArray getCandidates(JNIEnv *env, int *highlighted) {
    RIME_STRUCT(RimeContext, ctx);
    if (!api_->get_context(session(), &ctx)) {
      *highlighted = -1;
      return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    *highlighted = ctx.menu.highlighted_candidate_index;
    int count = ctx.menu.num_candidates;
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(count, strClass, nullptr);
    for (int i = 0; i < count; i++) {
      env->SetObjectArrayElement(arr, i,
          env->NewStringUTF(ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : ""));
    }
    api_->free_context(&ctx);
    return arr;
  }

  int getCandidateCount() {
    RIME_STRUCT(RimeContext, ctx);
    if (!api_->get_context(session(), &ctx)) return 0;
    int count = ctx.menu.num_candidates;
    api_->free_context(&ctx);
    return count;
  }

  int getPageSize() {
    RIME_STRUCT(RimeContext, ctx);
    if (!api_->get_context(session(), &ctx)) return 0;
    int size = ctx.menu.page_size;
    api_->free_context(&ctx);
    return size;
  }

  bool changePage(bool backward) {
    return api_->change_page(session(), backward);
  }

  bool selectCandidate(int index) {
    return api_->select_candidate_on_current_page(session(), index);
  }

  bool getOption(const char *key) {
    return api_->get_option(session(), key);
  }

  void setOption(const char *key, bool value) {
    api_->set_option(session(), key, value);
  }

  void exit() {
    if (session_id_) {
      // api_->destroy_session(session_id_);
      session_id_ = 0;
    }
    api_->finalize();
  }

 private:
  RimeApi *api_;
  RimeSessionId session_id_;
};

static jstring toJString(JNIEnv *env, const char *s) {
  return env->NewStringUTF(s ? s : "");
}

static std::string fromJString(JNIEnv *env, jstring s) {
  if (!s) return "";
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string result(c);
  env->ReleaseStringUTFChars(s, c);
  return result;
}

// ============ JNI methods ============

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
  declare_librime_module_dependencies();
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeStartup(
    JNIEnv *env, jclass clazz, jstring shared_dir, jstring user_dir) {
  Rime::Instance().startup(
      fromJString(env, shared_dir).c_str(),
      fromJString(env, user_dir).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeExit(JNIEnv *env, jclass clazz) {
  Rime::Instance().exit();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeProcessKey(
    JNIEnv *env, jclass clazz, jint keycode, jint mask) {
  return Rime::Instance().processKey(keycode, mask);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeCommitComposition(
    JNIEnv *env, jclass clazz) {
  return Rime::Instance().commitComposition();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeClearComposition(
    JNIEnv *env, jclass clazz) {
  Rime::Instance().clearComposition();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCommit(
    JNIEnv *env, jclass clazz) {
  return Rime::Instance().getCommit(env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetPreedit(
    JNIEnv *env, jclass clazz) {
  std::string preedit;
  int cursor;
  if (Rime::Instance().getPreedit(env, preedit, cursor)) {
    return env->NewStringUTF(preedit.c_str());
  }
  return env->NewStringUTF("");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCursorPos(
    JNIEnv *env, jclass clazz) {
  std::string preedit;
  int cursor;
  if (Rime::Instance().getPreedit(env, preedit, cursor)) {
    return cursor;
  }
  return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCandidates(
    JNIEnv *env, jclass clazz) {
  int highlighted;
  return Rime::Instance().getCandidates(env, &highlighted);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCandidateCount(
    JNIEnv *env, jclass clazz) {
  return Rime::Instance().getCandidateCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetPageSize(
    JNIEnv *env, jclass clazz) {
  return Rime::Instance().getPageSize();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeChangePage(
    JNIEnv *env, jclass clazz, jboolean backward) {
  return Rime::Instance().changePage(backward);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeSelectCandidate(
    JNIEnv *env, jclass clazz, jint index) {
  return Rime::Instance().selectCandidate(index);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetInput(
    JNIEnv *env, jclass clazz) {
  return Rime::Instance().getInput(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetOption(
    JNIEnv *env, jclass clazz, jstring key) {
  return Rime::Instance().getOption(fromJString(env, key).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeSetOption(
    JNIEnv *env, jclass clazz, jstring key, jboolean value) {
  Rime::Instance().setOption(fromJString(env, key).c_str(), value);
}
