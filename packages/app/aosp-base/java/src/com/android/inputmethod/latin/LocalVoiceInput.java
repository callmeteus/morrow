/*
 * Copyright (C) 2026 gti.dev
 * Licensed under the Apache License, Version 2.0.
 *
 * On-device speech recognition for AOSP LatinIME, backed by the vendored
 * whisper.cpp engine (ggml-base, pt-BR). No network and no Google STT service.
 */
package com.android.inputmethod.latin;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local Whisper STT plugged into AOSP LatinIME's voice key. */
public final class LocalVoiceInput {
    private static final String TAG = "LocalVoiceInput";
    // gti.dev: versioned name so a model change forces re-copy from assets.
    // Contents are the faster ggml-tiny pt model (bundled as R.raw.ggml_base).
    private static final String MODEL_FILE = "ggml-tiny-f32-v3.bin";
    private static final int SAMPLE_RATE = 16000;

    static {
        System.loadLibrary("whisper");
    }

    private static long ctx = 0;
    private static Context sContext;
    private static AudioRecord recorder;
    private static Thread recordThread;
    private static volatile boolean recording = false;
    private static volatile boolean stopRequested = false;
    private static short[] pendingBuffer;
    private static int pendingLen;

    // gti.dev: voice/silence detection (Gboard-like behaviour).
    private static final float VOICE_RMS_THRESHOLD = 0.012f; // above this = speech
    private static final long SILENCE_TIMEOUT_MS = 1000;     // stop after ~1s of silence

    private static native long nativeInit(String modelPath, int threads);
    private static native void nativeFree(long ctx);
    private static native String nativeTranscribe(long ctx, float[] audio, String lang, int threads);

    public interface Listener {
        void onPartial(String text);
        void onFinal(String text);
        void onState(String state); // "listening" / "transcribing"
    }

    /** Copies the bundled model to internal storage on first run. */
    private static String ensureModel(Context c) throws Exception {
        File out = new File(c.getFilesDir(), MODEL_FILE);
        if (out.exists() && out.length() > 10_000_000) return out.getAbsolutePath();
        try (InputStream in = c.getResources().openRawResource(R.raw.ggml_base);
             FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        }
        return out.getAbsolutePath();
    }

    public static synchronized void init(Context c) {
        if (ctx != 0) return;
        try {
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
            ctx = nativeInit(ensureModel(c), threads);
            Log.i(TAG, "whisper ready ctx=" + ctx);
        } catch (Exception e) {
            Log.e(TAG, "init failed: " + e.getMessage());
        }
    }

    /** Starts recording from the mic and emits a partial transcript every 2.5s. */
    public static void start(final Context c, final Listener l) {
        Log.i(TAG, "start() ctx=" + ctx);
        sContext = c.getApplicationContext();
        init(c);
        if (ctx == 0) { Log.e(TAG, "ctx==0, abort"); l.onFinal(""); return; }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final int bufSize = minBuf <= 0 ? SAMPLE_RATE : minBuf;
        Log.i(TAG, "minBuf=" + minBuf + " bufSize=" + bufSize);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        Log.i(TAG, "recorder state=" + recorder.getState());
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "recorder not initialized, abort");
            recording = false;
            recorder.release();
            recorder = null;
            l.onFinal("");
            return;
        }
        final short[] buf = new short[SAMPLE_RATE * 30];
        final int[] len = {0};
        pendingBuffer = buf;
        try {
            recorder.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed: " + e.getMessage());
            recording = false;
            recorder.release();
            recorder = null;
            l.onState("error");
            l.onFinal("");
            return;
        }
        recording = true;
        stopRequested = false;
        l.onState("ready");
        recordThread = new Thread(() -> {
            short[] chunk = new short[bufSize / 2];
            long startMs = System.currentTimeMillis();
            long lastVoiceMs = -1;      // set the first time speech is detected
            boolean voiceHeard = false;
            // gti.dev: adaptive noise floor. Sample the first ~400ms as background.
            double noiseSum = 0.0;
            int noiseCount = 0;
            double peakRms = 0.0;
            double threshold = VOICE_RMS_THRESHOLD;
            boolean calibrating = true;
            String reason = "manual";
            while (recording && !stopRequested) {
                int n = recorder.read(chunk, 0, chunk.length);
                if (n <= 0) continue;
                if (len[0] + n < buf.length) {
                    System.arraycopy(chunk, 0, buf, len[0], n);
                    len[0] += n;
                    pendingLen = len[0];
                }
                // RMS over this chunk to detect speech vs silence.
                double sumSq = 0.0;
                for (int i = 0; i < n; ++i) {
                    final double s = chunk[i] / 32768.0;
                    sumSq += s * s;
                }
                final double rms = Math.sqrt(sumSq / n);
                final long now = System.currentTimeMillis();
                if (rms > peakRms) peakRms = rms;
                if (calibrating) {
                    noiseSum += rms;
                    noiseCount++;
                    if (now - startMs >= 400 || noiseCount >= 12) {
                        final double noiseRms = noiseSum / noiseCount;
                        // adaptive: 3x the noise floor, with an absolute floor so a
                        // silent room still needs real speech. Works with a fan on.
                        threshold = Math.max(noiseRms * 3.0, noiseRms + 0.012);
                        calibrating = false;
                        Log.i(TAG, String.format("noise=%.5f thr=%.5f", noiseRms, threshold));
                    }
                    continue; // do not treat calibration samples as speech
                }
                if (rms > threshold) {
                    if (!voiceHeard) {
                        voiceHeard = true;
                        l.onState("listening"); // "Ouvindo..." as soon as the user speaks
                    }
                    lastVoiceMs = now;
                } else if (voiceHeard && lastVoiceMs > 0
                        && now - lastVoiceMs > SILENCE_TIMEOUT_MS) {
                    // Gboard-style automatic stop after ~1s of silence.
                    Log.i(TAG, "auto-stop after silence");
                    reason = "silence";
                    stopRequested = true;
                    break;
                }
            }
            final long durationMs = System.currentTimeMillis() - startMs;
            Log.i(TAG, "record thread exiting, len=" + len[0] + " voice=" + voiceHeard);
            // gti.dev: diagnostics for the "Transcrevendo..." investigations.
            VoiceDiagnostics.record(sContext, noiseCount > 0 ? noiseSum / noiseCount : 0,
                    peakRms, threshold, voiceHeard, durationMs, len[0], "", reason);
            // Finalize on this thread so we never fight the synchronous stop().
            if (voiceHeard) {
                finishRecording(l);
            } else {
                l.onFinal("");
            }
        }, "local-voice-record");
        recordThread.start();
    }

    /** Stops recording (manual tap). Signals the record thread, which finalizes. */
    public static void stop(final Listener l) {
        Log.i(TAG, "stop() requested");
        if (recording && !stopRequested) {
            // Signal the thread; it is blocked in recorder.read() so set the flag
            // and stop the recorder to unblock it. finalize happens in the thread.
            stopRequested = true;
            try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        }
    }

    /** Release recorder and run the final transcription. Called once from the record thread. */
    private static void finishRecording(final Listener l) {
        recording = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            recorder = null;
        }
        final short[] pcm = pendingBuffer;
        final int plen = pendingLen;
        pendingBuffer = null;
        pendingLen = 0;
        final long[] inferMs = {0};
        new AsyncTask<Void, Void, String>() {
            @Override protected void onPreExecute() { l.onState("transcribing"); }
            @Override protected String doInBackground(Void... v) {
                long t0 = System.currentTimeMillis();
                String res = doTranscribe(pcm, plen);
                inferMs[0] = System.currentTimeMillis() - t0;
                return res;
            }
            @Override protected void onPostExecute(String s) {
                VoiceDiagnostics.recordResult(sContext, s, inferMs[0]);
                l.onFinal(s);
            }
        }.execute();
    }

    /** Runs transcribe with a hard timeout so a native hang cannot freeze the UI. */
    private static String doTranscribe(final short[] pcm, final int plen) {
                if (pcm == null) return "";
                // gti.dev: hard timeout so a native whisper_full hang can never
                // leave the UI stuck in "Transcrevendo..." forever.
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override public String call() { return transcribe(pcm, plen); }
                    });
                    try {
                        return future.get(35, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        Log.e(TAG, "transcribe TIMEOUT after 35s");
                        future.cancel(true);
                        return "";
                    }
                } catch (Exception e) {
                    Log.e(TAG, "transcribe error: " + e.getMessage());
                    return "";
                } finally {
                    executor.shutdownNow();
                }
    }

    private static String transcribe(short[] pcm, int len) {
        if (len == 0) return "";
        // gti.dev: offline-first. If the user configured an OpenAI-compatible
        // server, try it first; on any failure fall back to the local engine.
        if (sContext != null && VoiceBackend.isExternalEnabled(sContext)) {
            try {
                String t = VoiceBackend.transcribe(sContext, pcm, len);
                Log.i(TAG, "external transcribe result=\"" + t + "\"");
                if (t != null && !t.trim().isEmpty()) return t.trim();
            } catch (Exception e) {
                Log.e(TAG, "external backend failed, falling back to local: " + e.getMessage());
            }
        }
        if (ctx == 0) return "";
        // gti.dev: trim leading/trailing silence and cap length. With a noisy
        // background the recording can run 18s; sending all of it made
        // whisper_full exceed the 18s timeout and return nothing. Keep only the
        // loudest 8s window and transcribe that.
        final int maxSamples = SAMPLE_RATE * 8;
        int start = 0;
        if (len > maxSamples) {
            // find the 8s window with the highest total energy
            double bestEnergy = -1;
            final int window = 1600; // 100ms
            for (int s = 0; s + maxSamples <= len; s += window) {
                double e = 0;
                for (int i = s; i < s + maxSamples; i += 40) {
                    final double v = pcm[i] / 32768.0;
                    e += v * v;
                }
                if (e > bestEnergy) { bestEnergy = e; start = s; }
            }
        }
        final int useLen = Math.min(len - start, maxSamples);
        // Measure RMS and apply gentle automatic gain (Samsung MIC captures low).
        double sumSq = 0.0;
        for (int i = 0; i < useLen; i++) {
            final double s = pcm[start + i] / 32768.0;
            sumSq += s * s;
        }
        final double rms = Math.sqrt(sumSq / useLen);
        float gain = 1.0f;
        if (rms > 1e-5 && rms < 0.05) {
            gain = (float) Math.min(6.0, 0.10 / rms); // capped at 6x to avoid distortion
        }
        final float[] f = new float[useLen];
        for (int i = 0; i < useLen; i++) {
            f[i] = (float) Math.max(-1.0, Math.min(1.0, (pcm[start + i] / 32768f) * gain));
        }
        Log.i(TAG, "transcribe len=" + useLen + " rms=" + String.format("%.5f", rms)
                + " gain=" + String.format("%.2f", gain));
        try {
            String t = nativeTranscribe(ctx, f, "pt", 4);
            Log.i(TAG, "transcribe result=\"" + t + "\"");
            return t == null ? "" : t.trim();
        } catch (Exception e) {
            Log.e(TAG, "transcribe failed: " + e.getMessage());
            return "";
        }
    }

    public static boolean isRecording() { return recording; }

    public static void release() {
        recording = false;
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} recorder.release(); recorder = null; }
        if (ctx != 0) { nativeFree(ctx); ctx = 0; }
    }
}
