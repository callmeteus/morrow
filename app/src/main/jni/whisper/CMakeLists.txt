cmake_minimum_required(VERSION 3.10)
project(gboard_whisper)

set(CMAKE_CXX_STANDARD 17)

# Location of the vendored whisper.cpp source tree.
# Gradle passes an absolute path via -DWHISPER_LIB_DIR=... ; fall back to a
# path relative to this file (app/src/main/jni/whisper -> repo root).
if(NOT DEFINED WHISPER_LIB_DIR OR WHISPER_LIB_DIR STREQUAL "")
    set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../../third_party/whisper.cpp)
endif()
message(STATUS "whisper.cpp source: ${WHISPER_LIB_DIR}")

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_SOURCE_DIR}/jni.cpp
)

find_library(LOG_LIB log)

include(FetchContent)

# Single shared library for every ABI. (Optional arm64 fp16 / armv7 vfpv4
# variants are intentionally omitted to keep the build small and fast.)
add_library(whisper SHARED ${SOURCE_FILES})

target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="bundled")

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3)
    target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(whisper PRIVATE -Wl,--gc-sections)
    target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)
    target_link_options(whisper PRIVATE -flto)
endif()

# Build the bundled ggml backend.
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

target_link_libraries(whisper ${LOG_LIB} android ggml)

target_include_directories(whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)
