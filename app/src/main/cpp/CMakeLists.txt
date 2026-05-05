cmake_minimum_required(VERSION 3.18.1)

project("wenet-jni" VERSION 1.0)

# 设置C++标准
set(CMAKE_CXX_STANDARD 14)

# 包含目录
include_directories(${CMAKE_SOURCE_DIR}/wenet/include)
include_directories(${CMAKE_SOURCE_DIR}/wenet)

# 源文件
add_library(wenet-jni SHARED
    whisper-jni.cpp
    wenet/models/wenet_model.cc
    wenet/utils/wenet_utils.cc
)

# 链接库
find_library(log-lib log)
target_link_libraries(wenet-jni ${log-lib})
