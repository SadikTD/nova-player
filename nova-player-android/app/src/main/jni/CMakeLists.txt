cmake_minimum_required(VERSION 3.22.1)
project(novaplayer-mpv LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Our JNI glue (adapted from mpv-android's app/src/main/jni, MIT) plus the
# webrtc-vad shim used by automatic subtitle sync. Linked against the prebuilt
# libmpv/libass/libav* produced by scripts/build-native.sh.

add_library(novamplayer SHARED
        nova_mpv.cpp)

find_library(log-lib log)

# libmpv lives in jniLibs/<abi> as a prebuilt shared library produced by
# scripts/build-native.sh. av_jni_set_java_vm / av_jni_set_android_app_ctx are
# resolved at link time from libavcodec.so (shipped alongside libmpv).
add_library(mpv SHARED IMPORTED)
set_target_properties(mpv PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so)
add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libavcodec.so)

# mpv headers are copied here by scripts/build-native.sh (ABI-independent).
target_include_directories(novamplayer PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include)

target_link_libraries(novamplayer
        mpv
        avcodec
        ${log-lib})

# Keep the symbol table small and strip unused sections: the APK is download-
# size sensitive and Play's per-device budget is 200 MB compressed.
target_compile_options(novamplayer PRIVATE -ffunction-sections -fdata-sections)
target_link_options(novamplayer PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)

file(GLOB FVAD_SOURCES third_party/fvad/src/*.c third_party/fvad/src/vad/*.c third_party/fvad/src/signal_processing/*.c)
target_sources(novamplayer PRIVATE ${FVAD_SOURCES})
target_include_directories(novamplayer PRIVATE third_party/fvad/include third_party/fvad/src)

