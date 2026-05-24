cmake_minimum_required(VERSION 3.22.1)

project(rime_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

aux_source_directory(. RIME_JNI_SOURCES)
add_library(rime_jni SHARED ${RIME_JNI_SOURCES})

find_library(log-lib log)
target_link_libraries(rime_jni ${log-lib})

# 链接 librime（如果存在）
set(LIBRIME_STATIC "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librime.a")
set(LIBRIME_INCLUDE "${CMAKE_SOURCE_DIR}/include")

if (EXISTS ${LIBRIME_STATIC})
    message(STATUS "Found librime.a, linking...")
    target_link_libraries(rime_jni ${LIBRIME_STATIC})
else()
    message(WARNING "librime.a not found at ${LIBRIME_STATIC}, JNI will compile but librime calls will fail at runtime")
endif()

if (EXISTS ${LIBRIME_INCLUDE}/rime_api.h)
    target_include_directories(rime_jni PRIVATE ${LIBRIME_INCLUDE})
else()
    message(WARNING "rime_api.h not found, JNI will compile but without librime headers")
endif()