#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "CesiaLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeInit(
        JNIEnv * env, jobject /* this */, jstring modelPath, jint nGpuLayers) {

    const char * path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load llama model");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        LOGE("Failed to create llama context");
        return JNI_FALSE;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) {
        llama_free(ctx);
        llama_model_free(model);
        LOGE("Failed to get vocab from model");
        return JNI_FALSE;
    }

    // Create sampler chain: temp(0.3) -> top_k(40) -> top_p(0.9) -> dist
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g_handle.model = model;
    g_handle.ctx = ctx;
    g_handle.vocab = vocab;
    g_handle.sampler = sampler;
    g_handle.n_ctx = llama_n_ctx(ctx);

    int32_t n_vocab = llama_vocab_n_tokens(vocab);
    LOGI("Llama model loaded: gpu_layers=%d, n_ctx=%d, n_vocab=%d",
         nGpuLayers, g_handle.n_ctx, n_vocab);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject /* this */, jstring prompt, jint maxTokens) {

    if (!g_handle.ctx || !g_handle.model || !g_handle.vocab || !g_handle.sampler) {
        LOGE("llama not initialized");
        return env->NewStringUTF("");
    }

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Tokenize prompt
    int32_t n_vocab = llama_vocab_n_tokens(g_handle.vocab);
    std::vector<llama_token> tokens(prompt_text.size() + 16);

    int32_t n_tokens = llama_tokenize(
        g_handle.vocab,
        prompt_text.c_str(),
        (int32_t) prompt_text.size(),
        tokens.data(),
        (int32_t) tokens.size(),
        true,   // add_special (BOS)
        false   // parse_special
    );

    if (n_tokens < 0) {
        LOGE("tokenize failed, n_tokens=%d", n_tokens);
        return env->NewStringUTF("");
    }

    tokens.resize((size_t) n_tokens);

    // Check if prompt fits in context
    if ((int32_t) tokens.size() + maxTokens > g_handle.n_ctx) {
        LOGE("prompt too long: %d tokens + %d max > %d ctx",
             (int32_t) tokens.size(), maxTokens, g_handle.n_ctx);
        return env->NewStringUTF("");
    }

    // Reset sampler for new generation
    llama_sampler_reset(g_handle.sampler);

    // Feed prompt tokens in batches
    int32_t n_batch = llama_n_batch(g_handle.ctx);
    std::string result_text;

    int32_t i = 0;
    for (; i < (int32_t) tokens.size(); ) {
        int32_t batch_end = std::min(i + n_batch, (int32_t) tokens.size());
        int32_t batch_size = batch_end - i;

        llama_batch batch = llama_batch_get_one(
            tokens.data() + i,
            batch_size
        );

        int32_t ret = llama_decode(g_handle.ctx, batch);
        if (ret != 0) {
            LOGE("llama_decode failed at prompt pos %d: ret=%d", i, ret);
            return env->NewStringUTF("");
        }

        i = batch_end;
    }

    // Generate tokens one by one
    int32_t n_generated = 0;
    for (; n_generated < maxTokens; n_generated++) {
        // Sample next token
        llama_token token = llama_sampler_sample(
            g_handle.sampler,
            g_handle.ctx,
            -1  // use last token's logits
        );

        // Check for EOS
        {
            llama_token eos = llama_vocab_eos(g_handle.vocab);
            if (token == eos) {
                LOGI("EOS reached at token %d", n_generated);
                break;
            }
        }

        // Accept token into sampler
        llama_sampler_accept(g_handle.sampler, token);

        // Convert token to text
        char buf[256];
        int32_t n_chars = llama_token_to_piece(
            g_handle.vocab,
            token,
            buf,
            sizeof(buf),
            0,      // lstrip
            false   // special
        );

        if (n_chars > 0) {
            result_text.append(buf, (size_t) n_chars);
        }

        // Feed generated token back
        llama_batch batch = llama_batch_get_one(&token, 1);
        int32_t ret = llama_decode(g_handle.ctx, batch);
        if (ret != 0) {
            LOGE("llama_decode failed at gen pos %d: ret=%d", n_generated, ret);
            break;
        }
    }

    LOGI("Generated %d tokens, result length=%zu", n_generated, result_text.size());
    return env->NewStringUTF(result_text.c_str());
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeFree(
        JNIEnv * /* env */, jobject /* this */) {

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
