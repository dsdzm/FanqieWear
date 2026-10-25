cmake_minimum_required(VERSION 3.22.1)
project(espeakng_jni C)

# espeak-ng 本体用 NDK 单独交叉编译好，产物放在 app/src/main/jniLibs/<abi>/
# 这里只编 JNI 桥，然后链接过去，避免把整个 espeak 源码塞进 Gradle 的 CMake 工程。
set(ESPEAK_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libespeak-ng.so)

add_library(espeakng SHARED IMPORTED)
set_target_properties(espeakng PROPERTIES IMPORTED_LOCATION ${ESPEAK_LIB})

add_library(espeakng-jni SHARED espeak_jni.c)
target_include_directories(espeakng-jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng/include)

find_library(log-lib log)
target_link_libraries(espeakng-jni espeakng ${log-lib})
