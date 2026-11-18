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

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_gti_gboard_VoiceEngine_nativeInit(JNIEnv* env, jclass /*clazz*/,
                                           jstring modelPath, jint nThreads) {
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
Java_dev_gti_gboard_VoiceEngine_nativeFree(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    if (ptr != 0) {
        whisper_free((struct whisper_context*) ptr);
    }
}

JNIEXPORT jstring JNICALL
Java_dev_gti_gboard_VoiceEngine_nativeTranscribe(JNIEnv* env, jclass /*clazz*/,
                                                 jlong ptr, jfloatArray audio,
                                                 jstring language, jint nThreads) {
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
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    // Anchor the decoder to Portuguese speech. Whisper otherwise hallucinates
    // "[MUSIC]"/"[MÚSICA DE FUNDO]" tags on silence and synthetic audio.
    // initial_prompt biases the first window toward real pt-BR dictation text.
    static const char* kPrompt =
        "Ditado em português do Brasil: o teclado local reconhece a minha voz, "
        "o ditado por voz funciona sem internet, obrigado, bom dia.";
    params.initial_prompt = kPrompt;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, samples, len);
    std::string text = (rc == 0) ? collect_text(ctx) : std::string();
    if (rc != 0) LOGE("whisper_full failed rc=%d", rc);

    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (language != nullptr) env->ReleaseStringUTFChars(language, lang);

    jstring result = env->NewStringUTF(text.c_str());
    whisper_print_timings(ctx);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_gti_gboard_VoiceEngine_nativeSystemInfo(JNIEnv* env, jclass /*clazz*/) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "");
}

} // extern "C"
