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

    # Android: Vulkan is NOT compiled in — no glshaders-gen, no glslc, no Vulkan::Vulkan in NDK
    # Runtime Vulkan support is probed at app startup via probe_vulkan_safe() which
    # dlopens libvulkan.so directly. Keep GGML_VULKAN=OFF to avoid build issues.
    if (ANDROID)
        set(GGML_VULKAN OFF CACHE BOOL "Vulkan disabled on Android (no glslc in NDK)" FORCE)
        message(STATUS "Android build: Vulkan disabled at compile time (runtime probe only)")
    else()
        set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan" FORCE)
        set(GGML_VULKAN_DEBUG OFF CACHE BOOL "Disable Vulkan debug" FORCE)
    endif()

    message(STATUS "DEBUG: GGML_VULKAN=${GGML_VULKAN} before add_subdirectory")

    add_subdirectory(${LLAMA_SRC} llama EXCLUDE_FROM_ALL)

    message(STATUS "DEBUG: GGML_VULKAN=${GGML_VULKAN} after add_subdirectory")
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
    if (ANDROID AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/SPIRV-Headers/include")
        target_include_directories(native-bridge PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/SPIRV-Headers/include
        )
    endif()
endif()

# 链接库
set(BRIDGE_LIBS android log)
if (LLAMA_EXISTS)
    list(APPEND BRIDGE_LIBS llama ggml-cpu ggml-base)
    if (GGML_VULKAN)
        list(APPEND BRIDGE_LIBS ggml-vulkan)
    endif()
endif()
target_link_libraries(native-bridge ${BRIDGE_LIBS})

target_compile_options(native-bridge PRIVATE -O3 -ffast-math)
target_link_options(native-bridge PRIVATE "LINKER:--unresolved-symbols=ignore-all")
