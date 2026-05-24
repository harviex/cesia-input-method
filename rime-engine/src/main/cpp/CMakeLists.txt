cmake_minimum_required(VERSION 3.22.1)

project(rime_jni)

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 收集源文件
aux_source_directory(. RIME_JNI_SOURCES)

# 创建 JNI 动态库
add_library(rime_jni SHARED ${RIME_JNI_SOURCES})

# Android 日志库
find_library(log-lib log)

# ====== librime 配置 ======
# librime 作为预编译静态库引入
# 需要手动将以下内容放入对应目录：
#   src/main/jniLibs/arm64-v8a/librime.a
#   src/main/jniLibs/armeabi-v7a/librime.a
# 并将 rime_api.h 等头文件放入 src/main/cpp/include/

# 添加头文件搜索路径
target_include_directories(rime_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)

# 链接库
target_link_libraries(rime_jni
    ${log-lib}
    # librime 静态链接（需要预编译）
    # ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librime.a
)