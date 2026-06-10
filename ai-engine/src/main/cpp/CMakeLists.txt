cmake_minimum_required(VERSION 3.22.1)
project(ai-engine VERSION 1.0)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# MNN LLM 引擎 - 使用 MNN 3.5.0 官方 .so 动态库
# ============================================================
set(MNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mnn")
set(MNN_LIBS_DIR "${MNN_DIR}/libs")
set(MNN_INCLUDE_DIR "${MNN_DIR}/include")

# MNN 3.5.0 官方 Android 包下载 URL
set(MNN_350_URL "https://github.com/alibaba/MNN/releases/download/3.5.0/mnn_3.5.0_android_armv7_armv8_cpu_opencl_vulkan.zip")
set(MNN_350_ZIP "${MNN_DIR}/mnn_3.5.0_android.zip")
set(MNN_350_EXTRACTED "${MNN_DIR}/mnn_3.5.0_extracted")

# 如果 .so 文件不存在，下载并解压 MNN 3.5.0 官方包
if (NOT EXISTS "${MNN_LIBS_DIR}/libMNN.so" OR NOT EXISTS "${MNN_LIBS_DIR}/libllm.so")
    message(STATUS "MNN 3.5.0 .so libraries not found, downloading from official release...")

    # 下载 zip
    if (NOT EXISTS "${MNN_350_ZIP}")
        file(DOWNLOAD "${MNN_350_URL}" "${MNN_350_ZIP}"
             SHOW_PROGRESS STATUS MNN_DL)
        list(GET MNN_DL 0 MNN_DL_CODE)
        if (NOT MNN_DL_CODE EQUAL 0)
            list(GET MNN_DL 1 MNN_DL_ERR)
            message(FATAL_ERROR "Failed to download MNN 3.5.0: ${MNN_DL_ERR}")
        endif()
    endif()

    # 解压
    file(MAKE_DIRECTORY "${MNN_350_EXTRACTED}")
    execute_process(
        COMMAND ${CMAKE_COMMAND} -E tar xf "${MNN_350_ZIP}"
        WORKING_DIRECTORY "${MNN_350_EXTRACTED}"
        RESULT_VARIABLE EXTRACT_RESULT
    )
    if (NOT EXTRACT_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to extract MNN 3.5.0 zip")
    endif()

    # 复制 .so 文件到 libs 目录（按 ABI 子目录组织，供 Android jniLibs 使用）
    set(MNN_SO_DIR "${MNN_350_EXTRACTED}/mnn_3.5.0_android_armv7_armv8_cpu_opencl_vulkan/arm64-v8a")
    # CMake 链接用的 .so（放在 libs 根目录）
    file(COPY "${MNN_SO_DIR}/libMNN.so" DESTINATION "${MNN_LIBS_DIR}")
    file(COPY "${MNN_SO_DIR}/libllm.so" DESTINATION "${MNN_LIBS_DIR}")
    # Android jniLibs 用的 .so（放在 arm64-v8a 子目录）
    set(MNN_JNI_DIR "${MNN_DIR}/../src/main/jniLibs/arm64-v8a")
    file(MAKE_DIRECTORY "${MNN_JNI_DIR}")
    file(COPY "${MNN_SO_DIR}/libMNN.so" DESTINATION "${MNN_JNI_DIR}")
    file(COPY "${MNN_SO_DIR}/libllm.so" DESTINATION "${MNN_JNI_DIR}")
    file(COPY "${MNN_SO_DIR}/libmnncore.so" DESTINATION "${MNN_JNI_DIR}")
    file(COPY "${MNN_SO_DIR}/libc++_shared.so" DESTINATION "${MNN_JNI_DIR}")

    message(STATUS "MNN 3.5.0 .so libraries extracted successfully")
endif()

# MNN 核心库（动态链接）
add_library(mnn SHARED IMPORTED)
set_target_properties(mnn PROPERTIES
    IMPORTED_LOCATION "${MNN_LIBS_DIR}/libMNN.so")

# MNN LLM 引擎（动态链接）
add_library(mnn_llm SHARED IMPORTED)
set_target_properties(mnn_llm PROPERTIES
    IMPORTED_LOCATION "${MNN_LIBS_DIR}/libllm.so")

# MNN LLM JNI 桥接
add_library(mnn-llm-bridge SHARED mnn-llm.cpp)
target_include_directories(mnn-llm-bridge PRIVATE
    ${MNN_INCLUDE_DIR}
    ${MNN_INCLUDE_DIR}/MNN)
target_link_libraries(mnn-llm-bridge mnn_llm mnn android log)
target_compile_options(mnn-llm-bridge PRIVATE -O3 -ffast-math)
