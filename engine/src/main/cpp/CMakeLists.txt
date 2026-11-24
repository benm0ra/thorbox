# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(thorbox_engine LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(thorbox_engine SHARED
    AudioEngine.cpp
    AudioRender.cpp
    AudioExport.cpp
    Analysis.cpp
    Decoder.cpp
    jni_bridge.cpp
)

# Warnings + hardening. The NDK already enables -fstack-protector-strong and _FORTIFY_SOURCE.
# Hidden visibility: only the JNIEXPORT entry points are exported from the .so.
target_compile_options(thorbox_engine PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wformat -Wformat-security -Werror=format-security
    -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections)
# -ffast-math only for DSP translation units: it must never affect parsing/validation code.
set_source_files_properties(AudioEngine.cpp AudioRender.cpp AudioExport.cpp Analysis.cpp PROPERTIES COMPILE_OPTIONS "-ffast-math")
target_link_options(thorbox_engine PRIVATE
    -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack
    -Wl,--exclude-libs,ALL -Wl,--gc-sections
    -Wl,-z,max-page-size=16384)   # 16 KB page-size devices (Android 15+)
target_link_libraries(thorbox_engine PRIVATE oboe::oboe mediandk log android)

# Speed: every Thor variant (Snapdragon 865 = Cortex-A77/A55, 8 Gen 2 = Armv9) implements Armv8.2-A,
# so target it directly (fp16/dot-product instructions, better scheduling than baseline armv8-a).
# -O3 and ThinLTO for optimized builds; debug builds already use RelWithDebInfo (see engine/build.gradle.kts).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(thorbox_engine PRIVATE -march=armv8.2-a)
endif()
target_compile_options(thorbox_engine PRIVATE "$<$<CONFIG:Release,RelWithDebInfo>:-O3;-flto=thin>")
target_link_options(thorbox_engine PRIVATE "$<$<CONFIG:Release,RelWithDebInfo>:-flto=thin;-O3>")
