cmake_minimum_required(VERSION 3.22.1)
project(ai-engine VERSION 1.0)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# llama.cpp 作为 CMake 子项目引入
# ============================================================

set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

set(LLAMA_EXISTS FALSE)
if (EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP ON CACHE BOOL "Enable OpenMP" FORCE)

    # Vulkan — DISABLED on Android (SIGSEGV in device init, try-catch can't catch signals)
    set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan" FORCE)

    # OpenCL GPU backend — works on Android via system libOpenCL.so
    set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Adreno optimized kernels" FORCE)
    set(GGML_OPENCL_PROFILING OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL_TARGET_VERSION "300" CACHE STRING "" FORCE)

    # Android cross-compile: SPIRV-Headers from local install
    if (ANDROID)
        set(SPIRV-Headers_DIR "/home/ca/cesia-input-method/ai-engine/src/main/cpp/SPIRV-Headers/install/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
    endif()

    add_subdirectory(${LLAMA_SRC} llama EXCLUDE_FROM_ALL)
    set(LLAMA_EXISTS TRUE)
    message(STATUS "llama.cpp: enabled (CPU+OpenMP+Vulkan)")
else()
    message(STATUS "llama.cpp: not found — stub mode")
endif()

# ----- JNI Bridge -----
add_library(native-bridge SHARED
    llama-jni.cpp
)

if (LLAMA_EXISTS)
    target_compile_definitions(native-bridge PRIVATE HAS_LLAMA=1)
endif()

target_include_directories(native-bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
if (LLAMA_EXISTS)
    target_include_directories(native-bridge PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
    )
    if (ANDROID)
        target_include_directories(native-bridge PRIVATE
            /home/ca/cesia-input-method/ai-engine/src/main/cpp/SPIRV-Headers/include
            /home/ca/cesia-input-method/ai-engine/src/main/cpp/OpenCL-Headers
        )
    endif()
endif()

# 链接库
set(BRIDGE_LIBS android log)
if (LLAMA_EXISTS)
    list(APPEND BRIDGE_LIBS llama ggml-opencl ggml-cpu ggml-base)
endif()
target_link_libraries(native-bridge ${BRIDGE_LIBS})

target_compile_options(native-bridge PRIVATE -O3 -ffast-math)
target_link_options(native-bridge PRIVATE "LINKER:--unresolved-symbols=ignore-all")
