cmake_minimum_required(VERSION 3.22)
project(viddaw_engine C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CORE_SOURCES
    core/audio_file.cpp
    core/effects.cpp
    core/instruments.cpp
    core/engine.cpp)

if(ANDROID)
    find_package(oboe REQUIRED CONFIG)
    add_library(viddaw_engine SHARED ${CORE_SOURCES} android/jni.cpp)
    target_include_directories(viddaw_engine PRIVATE core)
    target_compile_options(viddaw_engine PRIVATE -O2 -ffast-math -Wall)
    target_link_libraries(viddaw_engine oboe::oboe log)
    target_link_options(viddaw_engine PRIVATE "-Wl,-z,max-page-size=16384")

    # Shine, an LGPL MP3 encoder, stays a separate shared library so it can be replaced (see AGENTS.md).
    file(GLOB SHINE_SOURCES ../third_party/shine/*.c)
    add_library(shine SHARED ${SHINE_SOURCES})
    target_include_directories(shine PUBLIC ../third_party/shine)
    target_compile_options(shine PRIVATE -O2 -w)
    target_link_options(shine PRIVATE "-Wl,-z,max-page-size=16384")
    add_library(viddaw_mp3 SHARED android/mp3_jni.cpp)
    target_link_libraries(viddaw_mp3 shine log)
    target_link_options(viddaw_mp3 PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Host build: the core has no platform dependencies, so its tests run on the dev machine.
    add_executable(engine_tests ${CORE_SOURCES} tests/tests.cpp)
    target_include_directories(engine_tests PRIVATE core)
    target_compile_options(engine_tests PRIVATE -O2 -Wall -Wextra)
    find_package(Threads REQUIRED)
    target_link_libraries(engine_tests Threads::Threads)
endif()
