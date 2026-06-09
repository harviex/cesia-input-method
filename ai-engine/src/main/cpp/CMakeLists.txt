cmake_minimum_required(VERSION 3.22.1)
project(ai-engine VERSION 1.0)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# MNN LLM 引擎
# ============================================================
set(MNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mnn")
set(MNN_LIBS_DIR "${MNN_DIR}/libs")
set(MNN_INCLUDE_DIR "${MNN_DIR}/include")

# 如果 .a 文件不存在，从 GitHub Release 下载
set(MNN_RELEASE_BASE "https://github.com/harviex/cesia-input-method/releases/download/mnn-libs-v1.0")

if (NOT EXISTS "${MNN_LIBS_DIR}/libMNN.a" OR NOT EXISTS "${MNN_LIBS_DIR}/libllm.a")
    message(STATUS "MNN libraries not found, downloading from GitHub Release...")
    file(DOWNLOAD "${MNN_RELEASE_BASE}/libMNN.a" "${MNN_LIBS_DIR}/libMNN.a"
         SHOW_PROGRESS STATUS MNN_DL)
    list(GET MNN_DL 0 MNN_DL_CODE)
    if (NOT MNN_DL_CODE EQUAL 0)
        list(GET MNN_DL 1 MNN_DL_ERR)
        message(FATAL_ERROR "Failed to download libMNN.a: ${MNN_DL_ERR}")
    endif()

    file(DOWNLOAD "${MNN_RELEASE_BASE}/libllm.a" "${MNN_LIBS_DIR}/libllm.a"
         SHOW_PROGRESS STATUS LLM_DL)
    list(GET LLM_DL 0 LLM_DL_CODE)
    if (NOT LLM_DL_CODE EQUAL 0)
        list(GET LLM_DL 1 LLM_DL_ERR)
        message(FATAL_ERROR "Failed to download libllm.a: ${LLM_DL_ERR}")
    endif()
    message(STATUS "MNN libraries downloaded successfully")
endif()

# MNN 核心库
add_library(mnn STATIC IMPORTED)
set_target_properties(mnn PROPERTIES
    IMPORTED_LOCATION "${MNN_LIBS_DIR}/libMNN.a")

# MNN LLM 引擎
add_library(mnn_llm STATIC IMPORTED)
set_target_properties(mnn_llm PROPERTIES
    IMPORTED_LOCATION "${MNN_LIBS_DIR}/libllm.a")

# MNN LLM JNI 桥接
add_library(mnn-llm-bridge SHARED mnn-llm.cpp)
target_include_directories(mnn-llm-bridge PRIVATE
    ${MNN_INCLUDE_DIR}
    ${MNN_INCLUDE_DIR}/MNN)
target_link_libraries(mnn-llm-bridge mnn_llm mnn android log)
target_compile_options(mnn-llm-bridge PRIVATE -O3 -ffast-math)
target_link_options(mnn-llm-bridge PRIVATE "LINKER:--unresolved-symbols=ignore-all")
