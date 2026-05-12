# CMakeLists.txt — placeholder
# WeNet/Whisper JNI 支持将在后续版本通过运行时动态加载方式添加
# 当前版本专注于基础输入法功能

cmake_minimum_required(VERSION 3.18.1)
project("cesia-jni" VERSION 1.0)
set(CMAKE_CXX_STANDARD 14)

add_library(cesia-jni SHARED whisper-jni.cpp)
find_library(log-lib log)
target_link_libraries(cesia-jni ${log-lib})
