// JNI bridge between the Android app and the vendored whisper.cpp engine.
//
// The public surface is intentionally tiny: create a context from a model file,
// run a full transcription over a 16 kHz float buffer, and free the context.
// All heavy lifting happens in whisper.cpp; this file only marshals data.
//
// Compiled as C++ (jni.cpp) so the <string> helper below is available.

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <time.h>
#include <mutex>
#include <chrono>

#include "whisper.h"

#define TAG "VoiceEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Build the full transcription text from the segments whisper_full produced.
static std::string collect_text(struct whisper_context* ctx) {
    std::string out;
    int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; ++i) {
        const char* t = whisper_full_get_segment_text(ctx, i);
        if (t != nullptr) {
            out += t;
        }
    }
    // Trim surrounding whitespace.
    while (!out.empty() && (out.front() == ' ' || out.front() == '\n' || out.front() == '\r')) out.erase(out.begin());
    while (!out.empty() && (out.back() == ' ' || out.back() == '\n' || out.back() == '\r')) out.pop_back();
    return out;
}

// gti.dev: the vendored ggml-cpu crashes (SIGSEGV in ggml_compute_forward_mul_mat)
// when multiple OpenMP workers touch the same context concurrently. Serialise
// every init/transcribe/free and keep the worker count at 1.
static std::mutex g_whisper_mutex;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_android_inputmethod_latin_LocalVoiceInput_nativeInit(JNIEnv* env, jclass /*clazz*/,
                                           jstring modelPath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context* ctx = whisper_init_from_file_with_params(
        path, whisper_context_default_params());
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("Failed to init whisper context from %s", path);
        return 0;
    }
    LOGI("whisper context ready (threads=%d)", nThreads);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_android_inputmethod_latin_LocalVoiceInput_nativeFree(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    if (ptr != 0) {
        whisper_free((struct whisper_context*) ptr);
    }
}

JNIEXPORT jstring JNICALL
Java_com_android_inputmethod_latin_LocalVoiceInput_nativeTranscribe(JNIEnv* env, jclass /*clazz*/,
                                                 jlong ptr, jfloatArray audio,
                                                 jstring language, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    struct whisper_context* ctx = (struct whisper_context*) ptr;
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize len = env->GetArrayLength(audio);
    if (len == 0) return env->NewStringUTF("");

    jfloat* samples = env->GetFloatArrayElements(audio, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    const char* lang = (language != nullptr) ? env->GetStringUTFChars(language, nullptr) : "pt";
    params.language = lang;
    // gti.dev: 4 threads. The earlier SIGSEGV was a race between concurrent
    // transcribes on the shared context; g_whisper_mutex now serialises all
    // calls, so parallel workers are safe. 1 thread took ~60s per inference.
    params.n_threads = 4;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    // gti.dev: NO initial_prompt. On long/noisy buffers it made whisper_full
    // stall for minutes (the "Transcrevendo..." forever bug). Language is set
    // explicitly via params.language, which is enough for pt-BR.
    params.initial_prompt = nullptr;
    // Suppress hallucinated tags on near-silence.
    params.no_speech_thold = 0.70f;

    LOGI("whisper_full start len=%d threads=%d", len, params.n_threads);
    auto t0 = std::chrono::steady_clock::now();
    int rc = whisper_full(ctx, params, samples, len);
    double secs = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - t0).count();
    LOGI("whisper_full done rc=%d wall=%.2fs", rc, secs);
    std::string text = (rc == 0) ? collect_text(ctx) : std::string();
    if (rc != 0) LOGE("whisper_full failed rc=%d", rc);

    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (language != nullptr) env->ReleaseStringUTFChars(language, lang);

    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_android_inputmethod_latin_LocalVoiceInput_nativeSystemInfo(JNIEnv* env, jclass /*clazz*/) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "");
}

} // extern "C"

