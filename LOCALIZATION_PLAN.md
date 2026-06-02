# Cesia 输入法 — 本地化引擎实施计划

## 目标

在现有 Cesia 输入法基础上，新增本地语音识别（whisper.cpp）和本地 AI 润色（llama.cpp + Qwen 3.5），
与现有的云端 Groq/OpenRouter 并存，用户可通过键盘按钮切换本地/云端模式。

## 现状

### 已有代码
- `app/src/main/cpp/CMakeLists.txt` — 空壳 JNI 编译入口
- `app/src/main/cpp/whisper-jni.cpp` — 空壳 JNI（仅 JNI_OnLoad）
- `PolishService.kt` — OpenRouter AI 润色（云端）
- `CesiaInputMethod.kt` — 输入法主逻辑，已有语音录制框架
- `PinyinDictManager.kt` — 词库管理（刚修复了 BUNDLE_EXT 编译错误）
- `build.gradle` — NDK arm64-v8a，compileSdk 34

### 现有语音流程
```
Voice Key → AudioRecord(16kHz mono) → Groq API → 文本 → AI 润色 → 上屏
```

### 改造后语音流程
```
Voice Key → AudioRecord(16kHz mono) → [本地模式] whisper.cpp → 文本 → AI 润色 → 上屏
                                       [云端模式] Groq API  → 文本 → AI 润色 → 上屏
```

## 实施阶段

### P1: NDK 编译框架（当前阶段）

#### 1.1 新建 ai-engine library 模块
```
settings.gradle 新增: include ':ai-engine'

ai-engine/
├── build.gradle.kts          # Android library + NDK + CMake
├── src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt    # 主编译入口（whisper.cpp + llama.cpp + Vulkan）
│   │   ├── whisper-jni.cpp   # whisper JNI 封装
│   │   ├── llama-jni.cpp     # llama JNI 封装
│   │   └── common.h          # 工具函数
│   └── java/com/cesia/input/engine/ai/
│       (空 - 逻辑在 app 模块)
└── CMakeLists.txt 内容...

ai-engine/build.gradle.kts:
  - com.android.library
  - compileSdk 34, minSdk 24
  - ndk { abiFilters "arm64-v8a" }
  - externalNativeBuild cmake
  - 依赖: core-ktx, coroutines
```

#### 1.2 whisper.cpp 集成方案

**方案选择：源码级编译 vs 预编译库**

鉴于 whisper.cpp + llama.cpp 源码量巨大（数万个文件），推荐以下方案：

**方案 A（推荐）：独立 CMake 子项目**
- whisper.cpp 和 llama.cpp 作为 CMake `add_subdirectory` 引入
- 共享 ggml 后端（Vulkan GPU 加速）
- 通过 GitHub Actions 预编译上传 .so，CI 产物集成
- 本地开发时 submodule 完整源码

**方案 B：纯预编译**
- 仅提供预编译 .so
- app 通过 jniLibs 加载
- 最简单但灵活性低

**选择方案 A**，既能在 CI 中自动构建，也支持本地开发。

#### 1.3 CMakeLists.txt 结构（核心）

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(ai-engine VERSION 1.0)
set(CMAKE_CXX_STANDARD 17)

# === Vulkan GPU 加速 ===
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_FP16 ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)

# === whisper.cpp ===
# 作为子项目引入，共享 ggml
set(WHISPER_BUILD_EXAMPLES OFF)
set(WHISPER_BUILD_TESTS OFF)
add_subdirectory(whisper.cpp whisper EXCLUDE_FROM_ALL)

# === llama.cpp ===
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_TESTS OFF)
add_subdirectory(llama.cpp llama EXCLUDE_FROM_ALL)

# === JNI Bridge ===
add_library(native-bridge SHARED
    whisper-jni.cpp
    llama-jni.cpp
)

target_include_directories(native-bridge PRIVATE
    whisper.cpp/include
    whisper.cpp/ggml/include
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(native-bridge
    whisper          # libwhisper
    llama            # libllama
    android
    log
    vulkan
)
```

#### 1.4 Whisper JNI 接口

```cpp
// whisper-jni.cpp
#include "whisper.h"

struct WhisperHandle {
    whisper_context* ctx;
};

extern "C" {

// 初始化：传入模型路径，返回 handle
JNIEXPORT jlong JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeInit(
    JNIEnv* env, jobject, jstring modelPath, jboolean useGpu) {
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu;
    
    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (!ctx) return 0;
    
    return reinterpret_cast<jlong>(new WhisperHandle{ctx});
}

// 识别：传入 16kHz float 音频数据，返回 UTF-8 文本
JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeTranscribe(
    JNIEnv* env, jobject, jlong handle, jfloatArray audio) {
    
    auto* h = reinterpret_cast<WhisperHandle*>(handle);
    if (!h || !h->ctx) return env->NewStringUTF("");
    
    jsize len = env->GetArrayLength(audio);
    jfloat* samples = env->GetFloatArrayElements(audio, nullptr);
    
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "zh";
    params.n_threads = 4;
    params.print_realtime = false;
    params.print_progress = false;
    
    whisper_full(h->ctx, params, samples, len);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    
    std::string result;
    int n = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < n; i++) {
        result += whisper_full_get_segment_text(h->ctx, i);
    }
    
    return env->NewStringUTF(result.c_str());
}

// 释放
JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_WhisperEngine_nativeFree(
    JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<WhisperHandle*>(handle);
    if (h) {
        whisper_free(h->ctx);
        delete h;
    }
}

} // extern "C"
```

#### 1.5 Llama JNI 接口

```cpp
// llama-jni.cpp
#include "llama.h"

struct LlamaHandle {
    llama_model* model;
    llama_context* ctx;
};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeInit(
    JNIEnv* env, jobject, jstring modelPath, jint nGpuLayers) {
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    
    llama_model_params mparams = llama_model_default_params();
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    
    llama_model* model = llama_load_model_from_file(path, mparams);
    env->ReleaseUTFChars(modelPath, path);
    
    if (!model) return JNI_FALSE;
    
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        llama_free_model(model);
        return JNI_FALSE;
    }
    
    auto* h = new LlamaHandle{model, ctx};
    return JNI_TRUE;
}

// 流式生成（回调到 Kotlin）
JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeGenerate(
    JNIEnv* env, jobject, jlong handle, jstring prompt, jint maxTokens) {
    
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return env->NewStringUTF("");
    
    // 分词 → 推理 → 解码（简化版）
    // 实际需要完整的 tokenization + sampling 循环
    // ...
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_ai_LlamaEngine_nativeFree(
    JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (h) {
        llama_free(h->ctx);
        llama_free_model(h->model);
        delete h;
    }
}

} // extern "C"
```

### P2: Kotlin 引擎层

#### 2.1 新建文件清单

```
app/src/main/java/com/cesia/input/
├── voice/
│   ├── VoiceEngine.kt          # 语音识别统一接口
│   └── VoiceRecorder.kt        # AudioRecord 封装
├── ai/
│   ├── AIEngine.kt             # AI 润色统一接口（本地+云端）
│   └── LlamaCpp.kt             # llama.cpp JNI 绑定
├── model/
│   ├── ModelInfo.kt            # 模型信息数据类
│   ├── ModelManager.kt         # 已安装模型管理
│   └── ModelDownloadManager.kt # HuggingFace 下载器
└── settings/
    └── VoiceAISettings.kt      # 设置页面逻辑
```

#### 2.2 VoiceEngine.kt（语音识别统一接口）

```kotlin
class VoiceEngine(private val context: Context) {
    
    enum class Backend { LOCAL_WHISPER, CLOUD_GROQ }
    
    private var currentBackend = Backend.LOCAL_WHISPER
    private var whisperPtr: Long = 0
    private var currentModelPath: String? = null
    
    fun setBackend(backend: Backend) { ... }
    fun isLocalModelInstalled(): Boolean { ... }
    fun initLocalModel(modelPath: String): Boolean { ... }
    
    suspend fun transcribe(audioData: FloatArray): String {
        return when (currentBackend) {
            Backend.LOCAL_WHISPER -> transcribeLocal(audioData)
            Backend.CLOUD_GROQ -> transcribeCloud(audioData)
        }
    }
    
    private suspend fun transcribeLocal(audio: FloatArray): String {
        // whisper.cpp native call
    }
    
    private suspend fun transcribeCloud(audio: FloatArray): String {
        // Groq API call
    }
}
```

#### 2.3 AIEngine.kt（AI 润色统一接口）

```kotlin
class AIEngine(private val context: Context) {
    
    enum class Backend { LOCAL_LLAMA, CLOUD_OPENROUTER }
    enum class CloudMode { FREE, PAID }
    
    private var currentBackend = Backend.LOCAL_LLAMA
    private var cloudMode = CloudMode.FREE
    private var llamaPtr: Long = 0
    
    fun setBackend(backend: Backend) { ... }
    fun setCloudMode(mode: CloudMode) { ... }
    
    fun polishText(text: String, callback: (String) -> Unit) {
        when (currentBackend) {
            Backend.LOCAL_LLAMA -> polishLocal(text, callback)
            Backend.CLOUD_OPENROUTER -> polishCloud(text, callback)
        }
    }
    
    private fun polishLocal(text: String, callback: (String) -> Unit) {
        // llama.cpp native call，流式输出
    }
    
    private fun polishCloud(text: String, callback: (String) -> Unit) {
        // 复用 PolishService
    }
}
```

#### 2.4 ModelDownloadManager.kt

```kotlin
class ModelDownloadManager(private val context: Context) {
    
    // 模型下载源
    object ModelSources {
        const val WHISPER_SMALL = 
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_0.bin"
        const val WHISPER_LARGE_TURBO = 
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin"
        const val QWEN_0_8B = 
            "https://huggingface.co/bartowski/Qwen3.5-0.8B-Instruct-GGUF/resolve/main/Qwen3.5-0.8B-Instruct-Q4_K_M.gguf"
        const val QWEN_2B = 
            "https://huggingface.co/bartowski/Qwen3.5-2B-Instruct-GGUF/resolve/main/Qwen3.5-2B-Instruct-Q4_K_M.gguf"
    }
    
    suspend fun download(modelUrl: String, destFile: File, 
                        onProgress: (Int) -> Unit): Result<File>
    
    fun getInstalledModels(): List<ModelInfo> { ... }
}
```

### P3: 设置页面

#### 3.1 SettingsActivity 新增选项

```
语音与 AI → 
  ├── 运行模式:
  │   ├── 🌐 云端（免费模型）
  │   ├── 🌐 云端（付费模型）              
  │   └── 📱 本地
  │
  ├── 语音模型 (本地模式):
  │   ├── Whisper Small (~400MB) [已安装/下载]
  │   └── Whisper Large V3 Turbo (~800MB) [已安装/下载]
  │
  ├── AI 模型 (本地模式):
  │   ├── Qwen 3.5 0.8B (~600MB) [已安装/下载]
  │   └── Qwen 3.5 2B (~1.4GB) [已安装/下载]
  │
  ├── Groq API Key (云端语音):
  │   └── [输入框]
  │
  └── OpenRouter API Key (云端 AI):
      └── [输入框]
```

### P4: 键盘切换按钮

#### 4.1 行为

```
点击按钮:
  ├── 当前本地 → 切换云端 → 检查 API Key
  │                              ├── 已设置 → 切到云端 ✅
  │                              └── 未设置 → Toast "请设置 API Key"
  └── 当前云端 → 切换本地 → 检查模型
                                 ├── 已安装 → 切到本地 ✅
                                 └── 未安装 → Toast "请先下载模型"
```

### P5: GitHub Actions CI

新增 `build-ai-engine.yml`：
- 编译 whisper.cpp + llama.cpp + Vulkan（arm64-v8a）
- 生成 native-bridge.so
- 上传为 workflow artifact
- 可选：上传 Releases

## 模型清单

| 用途 | 模型 | 量化 | HF 源 | 文件名 | 大小 |
|------|------|------|-------|--------|------|
| 语音识别 | Whisper Small | Q5_0 | ggerganov/whisper.cpp | ggml-small-q5_0.bin | ~400MB |
| 语音识别 | Whisper Large V3 Turbo | Q5_0 | ggerganov/whisper.cpp | ggml-large-v3-turbo-q5_0.bin | ~800MB |
| AI 润色 | Qwen 3.5 0.8B | Q4_K_M | bartowski/Qwen3.5-0.8B-Instruct-GGUF | Qwen3.5-0.8B-Instruct-Q4_K_M.gguf | ~600MB |
| AI 润色 | Qwen 3.5 2B | Q4_K_M | bartowski/Qwen3.5-2B-Instruct-GGUF | Qwen3.5-2B-Instruct-Q4_K_M.gguf | ~1.4GB |

## 文件修改汇总

### 新增文件
```
settings.gradle            → +include ':ai-engine'
ai-engine/
├── build.gradle.kts
└── src/main/
    ├── cpp/
    │   ├── CMakeLists.txt
    │   ├── whisper-jni.cpp
    │   └── llama-jni.cpp
    └── AndroidManifest.xml

app/src/main/java/com/cesia/input/
├── voice/VoiceEngine.kt
├── voice/VoiceRecorder.kt
├── ai/AIEngine.kt
├── ai/LlamaCpp.kt
├── model/ModelInfo.kt
├── model/ModelManager.kt
├── model/ModelDownloadManager.kt
└── settings/VoiceAISettings.kt

.github/workflows/build-ai-engine.yml
```

### 修改文件
```
app/build.gradle           → +implementation project(':ai-engine')
CesiaInputMethod.kt        → +VoiceEngine/AIEngine 集成, +切换按钮
SettingsActivity.kt        → +语音AI设置入口
input_view.xml             → +本地化切换按钮
strings.xml                → +新模式字符串
```

## 风险与注意事项

1. **编译时间**: whisper.cpp + llama.cpp 首次编译需要 15-30 分钟，CI 需缓存
2. **APK 体积**: .so 文件约 100-200MB（Vulkan 着色器），考虑动态交付或压缩
3. **发热**: Large V3 Turbo 持续推理会导致温热，需要监控
4. **HuggingFace 可用性**: 国内可能需要代理或镜像下载模型
5. **模型兼容性**: whisper.cpp 的 .bin 格式 vs GGUF 格式需要确认最新版本
