#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <signal.h>
#include <setjmp.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>

#define LOG_TAG "CesiaLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define LOG_STEP(step) LOGI("STEP: %s (line %d)", step, __LINE__)

// Vulkan 最小类型定义（避免引入 vulkan.h 的复杂性）
#define VK_SUCCESS 0
#define VK_STRUCTURE_TYPE_APPLICATION_INFO 0
#define VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO 1
#define VK_API_VERSION_1_0 0x00400000

typedef struct {
    int sType;
    const void* pNext;
    const char* pApplicationName;
    uint32_t applicationVersion;
    const char* pEngineName;
    uint32_t engineVersion;
    uint32_t apiVersion;
} VkApplicationInfo;

typedef struct {
    int sType;
    const void* pNext;
    uint32_t flags;
    const VkApplicationInfo* pApplicationInfo;
    uint32_t enabledLayerCount;
    const char* const* ppEnabledLayerNames;
    uint32_t enabledExtensionCount;
    const char* const* ppEnabledExtensionNames;
} VkInstanceCreateInfo;

typedef uint32_t VkResult;
typedef struct VkInstance_T* VkInstance;
typedef void* VkAllocationCallbacks;
typedef void* (*PFN_vkGetInstanceProcAddr)(VkInstance, const char*);
typedef VkResult (*PFN_vkCreateInstance)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);
typedef void (*PFN_vkDestroyInstance)(VkInstance, const VkAllocationCallbacks*);

#ifdef HAS_LLAMA
#include "llama.h"

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    int32_t n_ctx = 0;
};

static LlamaHandle g_handle = {};

// SIGSEGV 捕获
static sigjmp_buf g_jump_buf;
static volatile sig_atomic_t g_signal_received = 0;

static void signal_handler(int sig) {
    g_signal_received = sig;
    LOGE("Caught signal %d, jumping back to safe point", sig);
    siglongjmp(g_jump_buf, 1);
}

static void install_signal_handlers() {
    struct sigaction sa;
    sa.sa_handler = signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_ONSTACK;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
}

static void restore_signal_handlers() {
    signal(SIGSEGV, SIG_DFL);
    signal(SIGABRT, SIG_DFL);
    signal(SIGFPE, SIG_DFL);
    signal(SIGILL, SIG_DFL);
}

// 轻量 Vulkan 探测（在信号保护下执行）
// 返回 true = Vulkan 可用，false = 不可用
static bool probe_vulkan_safe() {
    LOG_STEP("probe_vulkan_safe start");

    if (sigsetjmp(g_jump_buf, 1) != 0) {
        LOGE("probe_vulkan_safe: interrupted by signal %d", g_signal_received);
        return false;
    }

    void * vulkan_lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!vulkan_lib) {
        LOGE("probe_vulkan_safe: dlopen failed: %s", dlerror());
        return false;
    }

    // dlsym returns void*, safely cast to function pointer via memcpy
    void * sym_gipa = dlsym(vulkan_lib, "vkGetInstanceProcAddr");
    if (!sym_gipa) {
        LOGE("probe_vulkan_safe: dlsym vkGetInstanceProcAddr failed");
        dlclose(vulkan_lib);
        return false;
    }
    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
    memcpy(&vkGetInstanceProcAddr, &sym_gipa, sizeof(sym_gipa));

    void * sym_vci = vkGetInstanceProcAddr(nullptr, "vkCreateInstance");
    if (!sym_vci) {
        LOGE("probe_vulkan_safe: vkCreateInstance not found");
        dlclose(vulkan_lib);
        return false;
    }
    PFN_vkCreateInstance vkCreateInstance;
    memcpy(&vkCreateInstance, &sym_vci, sizeof(sym_vci));

    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "cesia-probe";
    app_info.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo create_info = {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance instance = nullptr;
    VkResult result = vkCreateInstance(&create_info, nullptr, &instance);

    if (result == VK_SUCCESS && instance != nullptr) {
        void * sym_vdi = vkGetInstanceProcAddr(instance, "vkDestroyInstance");
        if (sym_vdi) {
            PFN_vkDestroyInstance vkDestroyInstance;
            memcpy(&vkDestroyInstance, &sym_vdi, sizeof(sym_vdi));
            vkDestroyInstance(instance, nullptr);
        }
        LOG_STEP("probe_vulkan_safe: Vulkan is available!");
        dlclose(vulkan_lib);
        return true;
    }

    LOGE("probe_vulkan_safe: vkCreateInstance failed, result=%d", (int)result);
    dlclose(vulkan_lib);
    return false;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeInit(
        JNIEnv * env, jobject /* this */, jstring modelPath, jint nGpuLayers) {

    LOG_STEP("nativeInit start");

    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s (gpu_layers=%d)", path, nGpuLayers);

    // 安装信号处理器
    install_signal_handlers();

    bool result = false;
    bool vulkan_available = false;

    // 第一步：探测 Vulkan 是否可用
    if (nGpuLayers > 0) {
        LOG_STEP("Probing Vulkan availability...");
        vulkan_available = probe_vulkan_safe();
        if (!vulkan_available) {
            LOGE("Vulkan not available, falling back to CPU (nGpuLayers=0)");
            nGpuLayers = 0; // 回退到纯 CPU
        }
    }

    // 第二步：加载模型（在信号保护下）
    if (sigsetjmp(g_jump_buf, 1) == 0) {
        LOG_STEP("calling llama_model_load_from_file");

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = nGpuLayers;

        llama_model * model = llama_model_load_from_file(path, mparams);
        if (!model) {
            LOGE("Failed to load llama model");
            goto cleanup;
        }
        LOG_STEP("model loaded successfully");

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 1024;
        cparams.n_batch = 256;
        cparams.n_ubatch = 256;
        cparams.n_threads = 8;
        cparams.n_threads_batch = 8;

        LOG_STEP("calling llama_init_from_model");
        llama_context * ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            llama_model_free(model);
            LOGE("Failed to create llama context");
            goto cleanup;
        }
        LOG_STEP("context created");

        const llama_vocab * vocab = llama_model_get_vocab(model);
        if (!vocab) {
            llama_free(ctx);
            llama_model_free(model);
            LOGE("Failed to get vocab from model");
            goto cleanup;
        }
        LOG_STEP("vocab obtained");

        auto sparams = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        LOG_STEP("sampler created");

        g_handle.model = model;
        g_handle.ctx = ctx;
        g_handle.vocab = vocab;
        g_handle.sampler = sampler;
        g_handle.n_ctx = llama_n_ctx(ctx);

        int32_t n_vocab = llama_vocab_n_tokens(vocab);
        LOGI("Llama model loaded: gpu_layers=%d, n_ctx=%d, n_vocab=%d",
             nGpuLayers, g_handle.n_ctx, n_vocab);
        result = true;
    } else {
        LOGE("Model loading interrupted by signal %d", g_signal_received);
        result = false;
    }

cleanup:
    restore_signal_handlers();
    env->ReleaseStringUTFChars(modelPath, path);

    if (result) {
        LOGI("nativeInit: SUCCESS (vulkan=%s)", vulkan_available ? "yes" : "no");
    } else {
        LOGI("nativeInit: FAILED");
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject /* this */, jstring prompt, jint maxTokens) {

    LOG_STEP("nativeGenerate start");

    if (!g_handle.ctx || !g_handle.model || !g_handle.vocab || !g_handle.sampler) {
        LOGE("llama not initialized");
        return env->NewStringUTF("");
    }

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    LOGI("Prompt length: %zu chars", prompt_text.size());

    std::vector<llama_token> tokens(prompt_text.size() + 16);

    LOG_STEP("calling llama_tokenize");
    int32_t n_tokens = llama_tokenize(
        g_handle.vocab,
        prompt_text.c_str(),
        (int32_t) prompt_text.size(),
        tokens.data(),
        (int32_t) tokens.size(),
        true,
        false
    );

    if (n_tokens < 0) {
        LOGE("tokenize failed, n_tokens=%d", n_tokens);
        return env->NewStringUTF("");
    }

    LOGI("Tokenized: %d tokens", n_tokens);
    tokens.resize((size_t) n_tokens);

    if ((int32_t) tokens.size() + maxTokens > g_handle.n_ctx) {
        LOGE("prompt too long: %d tokens + %d max > %d ctx",
             (int32_t) tokens.size(), maxTokens, g_handle.n_ctx);
        return env->NewStringUTF("");
    }

    llama_sampler_reset(g_handle.sampler);
    LOG_STEP("sampler reset");

    int32_t n_batch = llama_n_batch(g_handle.ctx);
    LOGI("n_batch=%d, processing %d prompt tokens", n_batch, (int32_t) tokens.size());

    std::string result_text;
    int32_t i = 0;
    for (; i < (int32_t) tokens.size(); ) {
        int32_t batch_end = std::min(i + n_batch, (int32_t) tokens.size());
        int32_t batch_size = batch_end - i;

        llama_batch batch = llama_batch_get_one(tokens.data() + i, batch_size);

        int32_t ret = llama_decode(g_handle.ctx, batch);
        if (ret != 0) {
            LOGE("llama_decode failed at prompt pos %d: ret=%d", i, ret);
            return env->NewStringUTF("");
        }

        i = batch_end;
    }

    LOG_STEP("prompt decode complete, starting generation");

    int32_t n_generated = 0;
    for (; n_generated < maxTokens; n_generated++) {
        llama_token token = llama_sampler_sample(g_handle.sampler, g_handle.ctx, -1);

        {
            llama_token eos = llama_vocab_eos(g_handle.vocab);
            if (token == eos) {
                LOGI("EOS reached at token %d", n_generated);
                break;
            }
        }

        llama_sampler_accept(g_handle.sampler, token);

        char buf[256];
        int32_t n_chars = llama_token_to_piece(g_handle.vocab, token, buf, sizeof(buf), 0, false);

        if (n_chars > 0) {
            result_text.append(buf, (size_t) n_chars);
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        int32_t ret = llama_decode(g_handle.ctx, batch);
        if (ret != 0) {
            LOGE("llama_decode failed at gen pos %d: ret=%d", n_generated, ret);
            break;
        }

        if (n_generated % 10 == 0) {
            LOGI("Generated %d tokens so far...", n_generated);
        }
    }

    LOGI("Generated %d tokens, result length=%zu", n_generated, result_text.size());
    return env->NewStringUTF(result_text.c_str());
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeFree(
        JNIEnv * /* env */, jobject /* this */) {

    LOG_STEP("nativeFree start");

    if (g_handle.sampler) {
        llama_sampler_free(g_handle.sampler);
        g_handle.sampler = nullptr;
    }
    if (g_handle.ctx) {
        llama_free(g_handle.ctx);
        g_handle.ctx = nullptr;
    }
    if (g_handle.model) {
        llama_model_free(g_handle.model);
        g_handle.model = nullptr;
    }
    g_handle.vocab = nullptr;
    g_handle.n_ctx = 0;
    LOGI("Llama freed");
}

} // extern "C"

#else // !HAS_LLAMA — stub

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeInit(
        JNIEnv * /* env */, jobject, jstring, jint) {
    LOGE("llama.cpp not compiled in — stub");
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject, jstring, jint) {
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeFree(
        JNIEnv *, jobject) {}

} // extern "C"
#endif // HAS_LLAMA
