cmake_minimum_required(VERSION 3.10)
project(aosp_latinime_with_whisper)
set(CMAKE_CXX_STANDARD 17)

# Directory of this file
set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR})
# AOSP native root: aosp-base/native/jni (one level up from whisper-jni)
set(AOSP_NATIVE_DIR ${JNI_DIR}/../native/jni)
# whisper.cpp source: gboard-local-voice/third_party/whisper.cpp (two levels up)
if(NOT DEFINED WHISPER_LIB_DIR OR WHISPER_LIB_DIR STREQUAL "")
    set(WHISPER_LIB_DIR ${JNI_DIR}/../../../ai-model/whisper.cpp)
endif()
message(STATUS "AOSP native: ${AOSP_NATIVE_DIR}")
message(STATUS "whisper.cpp: ${WHISPER_LIB_DIR}")

find_library(LOG_LIB log)

# ---- libjni_latinime: all AOSP native .cpp except tests and host tools ----
# The JNI entry points (com_android_inputmethod_*.cpp, jni_common.cpp) live in
# the root of native/jni, while the core sources live in native/jni/src.
file(GLOB LATINIME_JNI_SRCS ${AOSP_NATIVE_DIR}/*.cpp)
# gti.dev: GLOB_RECURSE includes the open gesture policy implementation.
# gesture_traversal.cpp, gesture_weighting.cpp and gesture_scoring.cpp are new
# sources; touching this file forces CMake to re-run the GLOB on the next build.
file(GLOB_RECURSE LATINIME_CORE_SRCS ${AOSP_NATIVE_DIR}/src/*.cpp)
set(LATINIME_SRCS ${LATINIME_JNI_SRCS} ${LATINIME_CORE_SRCS})
# Exclude unit tests and host-only tool sources
list(FILTER LATINIME_SRCS EXCLUDE REGEX ".*_test\\.cpp$")
list(FILTER LATINIME_SRCS EXCLUDE REGEX ".*/tests/.*")
list(FILTER LATINIME_SRCS EXCLUDE REGEX ".*/dicttoolkit/.*")

add_library(jni_latinime SHARED ${LATINIME_SRCS})
target_include_directories(jni_latinime PRIVATE ${AOSP_NATIVE_DIR}/src)
target_compile_options(jni_latinime PRIVATE -Wno-everything -O2)
target_link_libraries(jni_latinime ${LOG_LIB} android dl)

# ---- libwhisper: on-device STT ----
set(WHISPER_SRCS
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp
    ${JNI_DIR}/jni.cpp)
add_library(whisper SHARED ${WHISPER_SRCS})
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="bundled")
target_compile_options(whisper PRIVATE -O3)
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)
# gti.dev: the Android Gradle build forces CMAKE_BUILD_TYPE=Debug, and the
# bundled ggml CMake only adds -O3 in Release. Add it explicitly to every ggml
# target so the ARM kernels are optimised (otherwise inference is ~3x slower).
foreach(_t ggml ggml-cpu ggml-base ggml-blas)
  if(TARGET ${_t})
    target_compile_options(${_t} PRIVATE -O3 -fomit-frame-pointer)
  endif()
endforeach()
target_link_libraries(whisper ${LOG_LIB} android ggml)
target_include_directories(whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

# ---- libswipe: OpenSwipe neural gesture decoder (MIT) + JNI ----
set(SWIPE_ENGINE_DIR ${JNI_DIR}/../../../ai-model/openswipe-engine)
file(GLOB SWIPE_CORE_SRCS ${SWIPE_ENGINE_DIR}/src/*.cpp)
add_library(swipe SHARED ${SWIPE_CORE_SRCS} ${JNI_DIR}/SwipeJni.cpp)
target_include_directories(swipe PRIVATE ${SWIPE_ENGINE_DIR}/include)
target_compile_options(swipe PRIVATE -O3 -std=c++17)
target_link_libraries(swipe ${LOG_LIB} android)
