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
typedef struct VkPhysicalDevice_T* VkPhysicalDevice;
typedef void* VkAllocationCallbacks;
typedef void* (*PFN_vkGetInstanceProcAddr)(VkInstance, const char*);
typedef VkResult (*PFN_vkCreateInstance)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);
typedef void (*PFN_vkDestroyInstance)(VkInstance, const VkAllocationCallbacks*);
typedef VkResult (*PFN_vkEnumeratePhysicalDevices)(VkInstance, uint32_t*, void*);
typedef void (*PFN_vkGetPhysicalDeviceQueueFamilyProperties)(void*, uint32_t*, void*);
typedef void (*PFN_vkGetPhysicalDeviceProperties)(void*, void*);

#ifdef HAS_LLAMA
#include "llama.h"
#include "ggml-backend.h"

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
    const char * sig_name = "unknown";
    switch(sig) {
        case SIGSEGV: sig_name = "SIGSEGV"; break;
        case SIGABRT: sig_name = "SIGABRT"; break;
        case SIGFPE:  sig_name = "SIGFPE";  break;
        case SIGILL:  sig_name = "SIGILL";  break;
    }
    LOGE("Caught signal %d (%s), jumping back to safe point", sig, sig_name);
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

// 完整 Vulkan 探测（在信号保护下执行）
// 模拟 ggml-vulkan 的初始化流程，提前发现驱动兼容性问题
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

    void* sym_gipa = dlsym(vulkan_lib, "vkGetInstanceProcAddr");
    if (!sym_gipa) {
        LOGE("probe_vulkan_safe: dlsym vkGetInstanceProcAddr failed");
        dlclose(vulkan_lib);
        return false;
    }
    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
    memcpy(&vkGetInstanceProcAddr, &sym_gipa, sizeof(sym_gipa));

    // Step 1: vkCreateInstance
    void* sym_vci = vkGetInstanceProcAddr(nullptr, "vkCreateInstance");
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
    if (result != VK_SUCCESS || instance == nullptr) {
        LOGE("probe_vulkan_safe: vkCreateInstance failed, result=%d", (int)result);
        dlclose(vulkan_lib);
        return false;
    }
    LOG_STEP("probe_vulkan_safe: instance created");

    // Step 2: vkEnumeratePhysicalDevices
    void* sym_vepd = vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices");
    if (!sym_vepd) {
        LOGE("probe_vulkan_safe: vkEnumeratePhysicalDevices not found");
        void* s = vkGetInstanceProcAddr(instance, "vkDestroyInstance");
        PFN_vkDestroyInstance vdi;
        memcpy(&vdi, &s, sizeof(s));
        if (vdi) vdi(instance, nullptr);
        dlclose(vulkan_lib);
        return false;
    }
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices;
    memcpy(&vkEnumeratePhysicalDevices, &sym_vepd, sizeof(sym_vepd));

    uint32_t dev_count = 0;
    result = vkEnumeratePhysicalDevices(instance, &dev_count, nullptr);
    if (result != VK_SUCCESS || dev_count == 0) {
        LOGE("probe_vulkan_safe: vkEnumeratePhysicalDevices count failed, result=%d, count=%u", (int)result, dev_count);
        void* s = vkGetInstanceProcAddr(instance, "vkDestroyInstance");
        PFN_vkDestroyInstance vdi;
        memcpy(&vdi, &s, sizeof(s));
        if (vdi) vdi(instance, nullptr);
        dlclose(vulkan_lib);
        return false;
    }
    LOG_STEP("probe_vulkan_safe: found devices");

    // Step 3: vkGetPhysicalDeviceQueueFamilyProperties
    void* sym_vgdqfp = vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    if (!sym_vgdqfp) {
        LOGE("probe_vulkan_safe: vkGetPhysicalDeviceQueueFamilyProperties not found");
        void* s = vkGetInstanceProcAddr(instance, "vkDestroyInstance");
        PFN_vkDestroyInstance vdi;
        memcpy(&vdi, &s, sizeof(s));
        if (vdi) vdi(instance, nullptr);
        dlclose(vulkan_lib);
        return false;
    }
    PFN_vkGetPhysicalDeviceQueueFamilyProperties vkGetPhysicalDeviceQueueFamilyProperties;
    memcpy(&vkGetPhysicalDeviceQueueFamilyProperties, &sym_vgdqfp, sizeof(sym_vgdqfp));

    // Need a physical device first
    VkPhysicalDevice devices[4] = {};
    uint32_t count = 4;
    result = vkEnumeratePhysicalDevices(instance, &count, devices);
    if (result != VK_SUCCESS || count == 0) {
        LOGE("probe_vulkan_safe: vkEnumeratePhysicalDevices list failed");
        void* s = vkGetInstanceProcAddr(instance, "vkDestroyInstance");
        PFN_vkDestroyInstance vdi;
        memcpy(&vdi, &s, sizeof(s));
        if (vdi) vdi(instance, nullptr);
        dlclose(vulkan_lib);
        return false;
    }

    // Test queue family enumeration on device 0
    uint32_t qf_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(devices[0], &qf_count, nullptr);
    LOG_STEP("probe_vulkan_safe: queue family count retrieved");

    if (qf_count > 0) {
        typedef struct {
            uint32_t queueFlags;
            uint32_t queueCount;
            uint32_t timestampValidBits;
            struct { uint32_t width, height, depth; } minImageTransferGranularity;
        } QFProps;
        QFProps qf[16] = {};
        uint32_t qf_count2 = 16;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[0], &qf_count2, (void*)qf);
        LOGI("probe_vulkan_safe: queue families = %u", qf_count2);
    }

    // Step 4: vkGetPhysicalDeviceProperties
    void* sym_vgpdp = vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties");
    if (sym_vgpdp) {
        LOG_STEP("probe_vulkan_safe: getPhysicalDeviceProperties available");
    }

    // Cleanup
    void* sym_vdi = vkGetInstanceProcAddr(instance, "vkDestroyInstance");
    if (sym_vdi) {
        PFN_vkDestroyInstance vdi;
        memcpy(&vdi, &sym_vdi, sizeof(sym_vdi));
        vdi(instance, nullptr);
    }
    dlclose(vulkan_lib);

    LOG_STEP("probe_vulkan_safe: Vulkan is fully available!");
    return true;
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
        cparams.n_ctx = 512;
        cparams.n_batch = 256;
        cparams.n_ubatch = 256;
        cparams.n_threads = 6;
        cparams.n_threads_batch = 6;

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

        // 列出所有 ggml backend device，确认 Vulkan 实际使用情况
        size_t dev_count = ggml_backend_dev_count();
        LOGI("ggml backend devices: %zu total", dev_count);
        bool vulkan_backend_found = false;
        for (size_t di = 0; di < dev_count; di++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(di);
            const char * dev_name = ggml_backend_dev_name(dev);
            const char * dev_desc = ggml_backend_dev_description(dev);
            enum ggml_backend_dev_type dev_type = ggml_backend_dev_type(dev);
            const char * type_str = (dev_type == GGML_BACKEND_DEVICE_TYPE_GPU) ? "GPU" :
                                    (dev_type == GGML_BACKEND_DEVICE_TYPE_CPU) ? "CPU" : "OTHER";
            size_t mem_free = 0, mem_total = 0;
            ggml_backend_dev_memory(dev, &mem_free, &mem_total);
            LOGI("  dev[%zu]: name=%s, desc=%s, type=%s, mem_free=%zuMB, mem_total=%zuMB",
                 di, dev_name, dev_desc, type_str, mem_free/(1024*1024), mem_total/(1024*1024));
            if (strstr(dev_name, "vulkan") || strstr(dev_name, "Vulkan") ||
                strstr(dev_desc, "vulkan") || strstr(dev_desc, "Vulkan")) {
                vulkan_backend_found = true;
            }
        }
        if (vulkan_backend_found) {
            LOGI(">>> Vulkan backend device detected! <<<");
        } else {
            LOGI(">>> No Vulkan backend device found - running on CPU only <<<");
        }

        LOGI("Llama model loaded: gpu_layers=%d, vulkan_probe=%s, n_ctx=%d, n_vocab=%d",
             nGpuLayers, vulkan_available ? "PASS" : "FAIL", g_handle.n_ctx, n_vocab);
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
    std::string result_text;
    int32_t n_generated = 0;
    llama_token eos_token = llama_vocab_eos(g_handle.vocab);

    for (; n_generated < maxTokens; n_generated++) {
        llama_token token = llama_sampler_sample(g_handle.sampler, g_handle.ctx, -1);

        // EOS 立即停止
        if (token == eos_token) {
            LOGI("EOS reached at token %d", n_generated);
            break;
        }

        llama_sampler_accept(g_handle.sampler, token);

        // token → 文本，过滤特殊标记
        char buf[256];
        int32_t n_chars = llama_token_to_piece(g_handle.vocab, token, buf, sizeof(buf), 0, false);

        if (n_chars > 0) {
            std::string piece(buf, (size_t) n_chars);

            // 过滤 Qwen 特殊标记（如 <|im_end|>, <|im_start|> 等）
            if (piece.find("<|") != std::string::npos || piece.find("|>") != std::string::npos) {
                LOGI("Filtered special token: %s", piece.c_str());
                // 遇到 <|im_end|> 也停止生成
                if (piece.find("im_end") != std::string::npos) {
                    LOGI("im_end detected, stopping generation");
                    break;
                }
                continue;
            }

            result_text.append(piece);
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

    // 去重：如果结果中存在重复段落，只保留第一段
    // 检测方式：找到文本中间是否有完整重复
    if (result_text.size() > 20) {
        size_t half = result_text.size() / 2;
        // 在后半部分查找是否有与前半部分匹配的重复
        for (size_t len = half; len >= 10; len--) {
            std::string first_part = result_text.substr(0, len);
            size_t pos = result_text.find(first_part, len);
            if (pos != std::string::npos && pos == len) {
                // 发现紧邻的重复，截断
                LOGI("Duplicate detected at pos %zu, truncating", pos);
                result_text = result_text.substr(0, pos);
                break;
            }
        }
    }

    // 去除首尾空白
    {
        size_t start = result_text.find_first_not_of(" \t\n\r");
        if (start == std::string::npos) result_text.clear();
        else {
            size_t end = result_text.find_last_not_of(" \t\n\r");
            result_text = result_text.substr(start, end - start + 1);
        }
    }

    LOGI("Generated %d tokens, result length=%zu, result='%s'", n_generated, result_text.size(), result_text.c_str());
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
