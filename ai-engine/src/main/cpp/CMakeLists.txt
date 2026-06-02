cmake_minimum_required(VERSION 3.22.1)
project(ai-engine VERSION 1.0)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# whisper.cpp + llama.cpp 作为 CMake 子项目引入
#
# 使用前需要:
#   git submodule add https://github.com/ggerganov/whisper.cpp whisper.cpp
#   git submodule add https://github.com/ggerganov/llama.cpp    llama.cpp
#   git submodule update --init --recursive
#
# CI 构建时由 build script clone 到对应位置
# ============================================================

set(WHISPER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")
set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# ----- whisper.cpp -----
set(WHISPER_EXISTS FALSE)
if (EXISTS "${WHISPER_SRC}/CMakeLists.txt")
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory(${WHISPER_SRC} whisper EXCLUDE_FROM_ALL)
    set(WHISPER_EXISTS TRUE)
    message(STATUS "whisper.cpp: enabled")
else()
    message(WARNING "whisper.cpp not found at ${WHISPER_SRC} — stub only")
endif()

# ----- llama.cpp -----
set(LLAMA_EXISTS FALSE)
if (EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan GPU backend" FORCE)
    set(GGML_VULKAN_FP16 ON CACHE BOOL "Enable FP16 Vulkan" FORCE)
    set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_SRC} llama EXCLUDE_FROM_ALL)
    set(LLAMA_EXISTS TRUE)
    message(STATUS "llama.cpp: enabled (Vulkan ON)")
else()
    message(WARNING "llama.cpp not found at ${LLAMA_SRC} — stub only")
endif()

# ----- JNI Bridge -----
add_library(native-bridge SHARED
    whisper-jni.cpp
    llama-jni.cpp
)

# 条件编译宏 — 让 C++ 知道子模块是否存在
if (WHISPER_EXISTS)
    target_compile_definitions(native-bridge PRIVATE HAS_WHISPER=1)
endif()
if (LLAMA_EXISTS)
    target_compile_definitions(native-bridge PRIVATE HAS_LLAMA=1)
endif()

# 头文件路径
target_include_directories(native-bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
if (WHISPER_EXISTS)
    target_include_directories(native-bridge PRIVATE
        ${WHISPER_SRC}/include
        ${WHISPER_SRC}/ggml/include
    )
endif()
if (LLAMA_EXISTS)
    target_include_directories(native-bridge PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
    )
endif()

# 链接库
set(BRIDGE_LIBS android log)
if (WHISPER_EXISTS)
    list(APPEND BRIDGE_LIBS whisper)
endif()
if (LLAMA_EXISTS)
    list(APPEND BRIDGE_LIBS llama)
endif()
target_link_libraries(native-bridge ${BRIDGE_LIBS})

target_compile_options(native-bridge PRIVATE -O3 -ffast-math)
