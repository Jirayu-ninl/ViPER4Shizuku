cmake_minimum_required(VERSION 3.22.1)
project(viper4shizuku_dsp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT VIPERDSP_DIR)
    message(FATAL_ERROR "VIPERDSP_DIR is not set (expected third_party/ViPERDSP)")
endif ()
if (NOT VIPER4SHIZUKU_ENGINE_VERSION)
    set(VIPER4SHIZUKU_ENGINE_VERSION "0.0.0")
endif ()

# CMake's IPO switch adds -fuse-ld=gold on Android, which current NDKs no longer ship,
# so LTO is enabled by hand (ViPERDSP's own target already compiles with -flto).
add_compile_options(
    -O3 -DNDEBUG -flto
    -ffunction-sections -fdata-sections
    -fvisibility=hidden -fvisibility-inlines-hidden)

# ViPERDSP is consumed unmodified as a static library.
add_subdirectory(${VIPERDSP_DIR} viperdsp)

add_library(viper4shizuku_dsp SHARED viper4shizuku_jni.cpp)

target_compile_definitions(viper4shizuku_dsp PRIVATE
    VIPER4SHIZUKU_ENGINE_VERSION="${VIPER4SHIZUKU_ENGINE_VERSION}")

target_compile_options(viper4shizuku_dsp PRIVATE
    -Wall -Wno-unused-parameter)

target_link_libraries(viper4shizuku_dsp ViPERDSP log)

target_link_options(viper4shizuku_dsp PRIVATE
    -flto
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
