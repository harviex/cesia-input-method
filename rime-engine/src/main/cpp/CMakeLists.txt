cmake_minimum_required(VERSION 3.22.1)
project("cesia-rime-engine" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(cesia-rime-engine SHARED
    rime_jni.cpp
)

find_library(log-lib log)

target_link_options(cesia-rime-engine PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(cesia-rime-engine
    ${log-lib}
    # 链接真实 librime 时取消注释:
    # ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librime.so
)

# target_include_directories(cesia-rime-engine PRIVATE ${RIME_ROOT}/include)
