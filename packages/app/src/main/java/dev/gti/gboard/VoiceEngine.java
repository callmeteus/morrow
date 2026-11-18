package dev.gti.gboard;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Thin Java wrapper around the vendored whisper.cpp engine (loaded as libwhisper.so).
 *
 * The model is the same Whisper family used by the Voice Cursor sidecar
 * (faster-whisper "base", int8) but runs fully on-device via whisper.cpp.
 * Nothing here touches the network or Google's speech service.
 */
public final class VoiceEngine {
    private static final String TAG = "VoiceEngine";
    // gti.dev: use the faster quantized tiny model (42 MB) so dictation is usable.
    private static final String MODEL_ASSET = "models/ggml-tiny-q8_0.bin";
    private static final String MODEL_FILE = "ggml-tiny-q8_0.bin";

    private static long ctx = 0;
    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    static {
        System.loadLibrary("whisper");
    }

    private VoiceEngine() {}

    // --- JNI (implemented in app/src/main/jni/whisper/jni.c) ---
    private static native long nativeInit(String modelPath, int threads);
    private static native void nativeFree(long ctx);
    private static native String nativeTranscribe(long ctx, float[] audio, String language, int threads);
    private static native String nativeSystemInfo();

    public static boolean isReady() { return ctx != 0; }

    /** Copy the bundled model out of assets into internal storage (first run only). */
    public static String ensureModel(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create models dir");
        File out = new File(dir, MODEL_FILE);
        if (out.exists() && out.length() > 10_000_000) {
            return out.getAbsolutePath();
        }
        try (InputStream in = context.getAssets().open(MODEL_ASSET);
             FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
            }
            Log.i(TAG, "Model extracted: " + out + " (" + total + " bytes)");
        }
        return out.getAbsolutePath();
    }

    /** Load the model into a whisper context. Must be called once, off the UI thread. */
    public static synchronized void init(Context context) throws IOException {
        if (ctx != 0) return;
        String model = ensureModel(context);
        long c = nativeInit(model, THREADS);
        if (c == 0) throw new IOException("whisper_init failed for " + model);
        ctx = c;
        Log.i(TAG, "VoiceEngine ready (threads=" + THREADS + ")");
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whisper-transcribe");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    /**
     * Transcribe 16 kHz mono float audio. Must only be called from the single
     * transcribe executor (whisper contexts are not thread-safe).
     */
    public static String transcribe(float[] audio, String language) {
        if (ctx == 0 || audio == null || audio.length == 0) return "";
        return nativeTranscribe(ctx, audio, language, THREADS);
    }

    public static ExecutorService executor() { return EXEC; }

    public static String systemInfo() {
        try { return nativeSystemInfo(); } catch (Throwable t) { return "n/a"; }
    }

    public static synchronized void release() {
        if (ctx != 0) {
            nativeFree(ctx);
            ctx = 0;
        }
    }
}
