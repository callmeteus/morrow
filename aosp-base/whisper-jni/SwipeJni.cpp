/*
 * gti.dev: JNI bridge for the OpenSwipe neural gesture decoder (MIT,
 * github.com/itsdestin/openswipe). Wraps swipekb::SwipeEngine so the Android
 * IME can decode a swipe trace into ranked dictionary words entirely offline.
 */
#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <cstdio>
#include <cstring>

#include "swipekb/swipe_engine.hpp"

namespace {
std::unique_ptr<swipekb::SwipeEngine> g_engine;

// Standard QWERTY layout (letter area, normalised 0..1). Same positions as the
// AOSP LatinIME qwerty keyboard; accented pt keys fold to their base letter.
static const char *kLayoutTsv =
    "letter\tcx\tcy\thalf_w\thalf_h\n"
    "q\t0.05\t0.1667\t0.05\t0.1667\n"
    "w\t0.15\t0.1667\t0.05\t0.1667\n"
    "e\t0.25\t0.1667\t0.05\t0.1667\n"
    "r\t0.35\t0.1667\t0.05\t0.1667\n"
    "t\t0.45\t0.1667\t0.05\t0.1667\n"
    "y\t0.55\t0.1667\t0.05\t0.1667\n"
    "u\t0.65\t0.1667\t0.05\t0.1667\n"
    "i\t0.75\t0.1667\t0.05\t0.1667\n"
    "o\t0.85\t0.1667\t0.05\t0.1667\n"
    "p\t0.95\t0.1667\t0.05\t0.1667\n"
    "a\t0.10\t0.5\t0.05\t0.1667\n"
    "s\t0.20\t0.5\t0.05\t0.1667\n"
    "d\t0.30\t0.5\t0.05\t0.1667\n"
    "f\t0.40\t0.5\t0.05\t0.1667\n"
    "g\t0.50\t0.5\t0.05\t0.1667\n"
    "h\t0.60\t0.5\t0.05\t0.1667\n"
    "j\t0.70\t0.5\t0.05\t0.1667\n"
    "k\t0.80\t0.5\t0.05\t0.1667\n"
    "l\t0.90\t0.5\t0.05\t0.1667\n"
    "z\t0.20\t0.8333\t0.05\t0.1667\n"
    "x\t0.30\t0.8333\t0.05\t0.1667\n"
    "c\t0.40\t0.8333\t0.05\t0.1667\n"
    "v\t0.50\t0.8333\t0.05\t0.1667\n"
    "b\t0.60\t0.8333\t0.05\t0.1667\n"
    "n\t0.70\t0.8333\t0.05\t0.1667\n"
    "m\t0.80\t0.8333\t0.05\t0.1667\n";
}

extern "C" {

#include <android/log.h>
#define SWIPE_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SwipeJni", __VA_ARGS__)

JNIEXPORT jboolean JNICALL
Java_com_android_inputmethod_latin_SwipeDecoder_nativeCreate(JNIEnv *env, jclass,
        jstring modelPath, jstring layoutPath) {
    g_engine.reset(new swipekb::SwipeEngine());
    const char *lp = layoutPath ? env->GetStringUTFChars(layoutPath, nullptr) : nullptr;
    bool layoutOk = lp ? g_engine->loadLayout(std::string(lp)) : false;
    SWIPE_LOG("layout=%d path=%s", layoutOk ? 1 : 0, lp ? lp : "(null)");
    if (lp) env->ReleaseStringUTFChars(layoutPath, lp);
    const char *p = modelPath ? env->GetStringUTFChars(modelPath, nullptr) : nullptr;
    bool ok = p ? g_engine->loadModel(std::string(p)) : false;
    SWIPE_LOG("model=%d", ok ? 1 : 0);
    if (p) env->ReleaseStringUTFChars(modelPath, p);
    return (ok && layoutOk) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_android_inputmethod_latin_SwipeDecoder_nativeSetLayout(JNIEnv *env, jclass,
        jintArray labels, jfloatArray geom) {
    if (!g_engine) return;
    jsize n = env->GetArrayLength(labels);
    jint *l = env->GetIntArrayElements(labels, nullptr);
    jfloat *g = env->GetFloatArrayElements(geom, nullptr);
    std::vector<swipekb::SwipeKey> keys;
    keys.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        keys.push_back({static_cast<char32_t>(l[i]), g[i*4], g[i*4+1], g[i*4+2], g[i*4+3]});
    }
    env->ReleaseIntArrayElements(labels, l, JNI_ABORT);
    env->ReleaseFloatArrayElements(geom, g, JNI_ABORT);
    swipekb::SwipeLayout layout(std::move(keys));
    // gti.dev: keep the same key pitch as the training layout (qwerty.tsv has
    // 0.1 spacing -> unit 10) so the network sees the same feature scale.
    layout.setUnit(10.f);
    g_engine->setLayout(std::move(layout));
    SWIPE_LOG("setLayout keys=%d", n);
}

JNIEXPORT jint JNICALL
Java_com_android_inputmethod_latin_SwipeDecoder_nativeSetWords(JNIEnv *env, jclass,
        jobjectArray words, jintArray freqs) {
    if (!g_engine) return 0;
    std::vector<std::pair<std::string, int>> ws;
    jsize n = env->GetArrayLength(words);
    jint *f = env->GetIntArrayElements(freqs, nullptr);
    for (jsize i = 0; i < n; ++i) {
        jstring js = (jstring) env->GetObjectArrayElement(words, i);
        if (!js) continue;
        const char *c = env->GetStringUTFChars(js, nullptr);
        ws.emplace_back(std::string(c), f ? f[i] : 128);
        env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    if (f) env->ReleaseIntArrayElements(freqs, f, JNI_ABORT);
    return (jint) g_engine->setWords(ws);
}

JNIEXPORT jobjectArray JNICALL
Java_com_android_inputmethod_latin_SwipeDecoder_nativeDecode(JNIEnv *env, jclass,
        jfloatArray xs, jfloatArray ys, jlongArray ts, jfloat aspect, jint topN) {
    jsize n = env->GetArrayLength(xs);
    jobjectArray empty = env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    if (!g_engine || n < 2) return empty;
    jfloat *x = env->GetFloatArrayElements(xs, nullptr);
    jfloat *y = env->GetFloatArrayElements(ys, nullptr);
    jlong *t = env->GetLongArrayElements(ts, nullptr);
    std::vector<swipekb::SwipePoint> trace;
    trace.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        trace.push_back({x[i], y[i], (double) t[i]});
    }
    env->ReleaseFloatArrayElements(xs, x, JNI_ABORT);
    env->ReleaseFloatArrayElements(ys, y, JNI_ABORT);
    env->ReleaseLongArrayElements(ts, t, JNI_ABORT);
    auto cands = g_engine->decode(trace, aspect, topN);
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray((jsize) cands.size(), strCls, nullptr);
    for (size_t i = 0; i < cands.size(); ++i) {
        const std::string &w = g_engine->lexicon().text(cands[i].word);
        jstring js = env->NewStringUTF(w.c_str());
        env->SetObjectArrayElement(out, (jsize) i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_android_inputmethod_latin_SwipeDecoder_nativeReady(JNIEnv *, jclass) {
    return g_engine && g_engine->ready() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
