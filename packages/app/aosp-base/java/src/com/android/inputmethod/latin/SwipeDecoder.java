/*
 * gti.dev: Java facade for the OpenSwipe neural gesture decoder (MIT).
 * The model is copied from assets on first run; if the native library or model
 * is missing, isReady() returns false and the IME keeps the old behaviour.
 */
package com.android.inputmethod.latin;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Decodes a swipe trace into ranked words using the local neural model. */
public final class SwipeDecoder {
    private static final String TAG = "SwipeDecoder";
    private static final String MODEL_ASSET = "swipe_model.bin";
    private static final String MODEL_FILE = "swipe_model_v1.bin";

    static { System.loadLibrary("swipe"); }

    private static native boolean nativeCreate(String modelPath, String layoutPath);
    private static native void nativeSetLayout(int[] labels, float[] geom);

    private static final String LAYOUT_TSV =
        "letter\tcx\tcy\thalf_w\thalf_h\n"
        + "q\t0.05\t0.1667\t0.05\t0.1667\n"
        + "w\t0.15\t0.1667\t0.05\t0.1667\n"
        + "e\t0.25\t0.1667\t0.05\t0.1667\n"
        + "r\t0.35\t0.1667\t0.05\t0.1667\n"
        + "t\t0.45\t0.1667\t0.05\t0.1667\n"
        + "y\t0.55\t0.1667\t0.05\t0.1667\n"
        + "u\t0.65\t0.1667\t0.05\t0.1667\n"
        + "i\t0.75\t0.1667\t0.05\t0.1667\n"
        + "o\t0.85\t0.1667\t0.05\t0.1667\n"
        + "p\t0.95\t0.1667\t0.05\t0.1667\n"
        + "a\t0.10\t0.5\t0.05\t0.1667\n"
        + "s\t0.20\t0.5\t0.05\t0.1667\n"
        + "d\t0.30\t0.5\t0.05\t0.1667\n"
        + "f\t0.40\t0.5\t0.05\t0.1667\n"
        + "g\t0.50\t0.5\t0.05\t0.1667\n"
        + "h\t0.60\t0.5\t0.05\t0.1667\n"
        + "j\t0.70\t0.5\t0.05\t0.1667\n"
        + "k\t0.80\t0.5\t0.05\t0.1667\n"
        + "l\t0.90\t0.5\t0.05\t0.1667\n"
        + "z\t0.20\t0.8333\t0.05\t0.1667\n"
        + "x\t0.30\t0.8333\t0.05\t0.1667\n"
        + "c\t0.40\t0.8333\t0.05\t0.1667\n"
        + "v\t0.50\t0.8333\t0.05\t0.1667\n"
        + "b\t0.60\t0.8333\t0.05\t0.1667\n"
        + "n\t0.70\t0.8333\t0.05\t0.1667\n"
        + "m\t0.80\t0.8333\t0.05\t0.1667\n";
    private static native int nativeSetWords(String[] words, int[] freqs);
    private static native String[] nativeDecode(float[] xs, float[] ys, long[] ts,
            float aspect, int topN);
    private static native boolean nativeReady();

    private static boolean sInitAttempted = false;

    private static Context sContext;

    /** gti.dev: records a decoded gesture for opt-in diagnostics. */
    public static void recordDiagnostics(int[] xs, int[] ys, long[] ts, String[] decoded) {
        if (sContext == null) return;
        SwipeDiagnostics.record(sContext, xs, ys, ts, decoded, sKeyboardWidth, sKeyboardHeight);
        CollectorHooks.onSwipe(xs, ys, ts, decoded, sKeyboardWidth, sKeyboardHeight);
    }

    /** Copies the model from assets and initialises the native engine once. */
    public static synchronized boolean init(Context c) {
        sContext = c.getApplicationContext();
        if (sInitAttempted) return nativeReady();
        sInitAttempted = true;
        try {
            File out = new File(c.getFilesDir(), MODEL_FILE);
            if (!out.exists() || out.length() < 100000) {
                try (InputStream in = c.getResources().openRawResource(R.raw.swipe_model);
                     FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
            }
            File layout = new File(c.getFilesDir(), "swipe_layout.tsv");
            try (FileOutputStream lfo = new FileOutputStream(layout)) {
                lfo.write(LAYOUT_TSV.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean ok = nativeCreate(out.getAbsolutePath(), layout.getAbsolutePath());
            Log.i(TAG, "init model=" + ok + " ready=" + nativeReady());
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "init failed: " + e.getMessage());
            return false;
        }
    }

    private static int idx(int label, int[] labels) {
        for (int i = 0; i < labels.length; i++) if (labels[i] == label) return i;
        return 0;
    }

    private static int sKeyboardWidth = 1, sKeyboardHeight = 1;

    public static void setKeyboardSize(int width, int height) {
        if (width > 0 && height > 0) { sKeyboardWidth = width; sKeyboardHeight = height; }
    }

    /**
     * Feeds the REAL key geometry from the AOSP keyboard into the engine, so
     * swipe points and key centres share the same coordinate frame.
     */
    public static void setLayoutFromKeyboard(com.android.inputmethod.keyboard.Keyboard keyboard) {
        if (!sInitAttempted || keyboard == null) return;
        final float w = keyboard.mId.mWidth;
        final float h = keyboard.mId.mHeight;
        if (w <= 0 || h <= 0) return;
        java.util.ArrayList<Integer> labels = new java.util.ArrayList<>();
        java.util.ArrayList<Float> geom = new java.util.ArrayList<>();
        for (com.android.inputmethod.keyboard.Key key : keyboard.getSortedKeys()) {
            final int code = key.getCode();
            if (code < 'a' || code > 'z') continue;
            final float cx = (key.getX() + key.getWidth() / 2f) / w;
            final float cy = (key.getY() + key.getHeight() / 2f) / h;
            final float hw = (key.getWidth() / 2f) / w;
            final float hh = (key.getHeight() / 2f) / h;
            labels.add(code);
            geom.add(cx); geom.add(cy); geom.add(hw); geom.add(hh);
        }
        if (labels.isEmpty()) return;
        int[] l = new int[labels.size()];
        for (int i = 0; i < l.length; i++) l[i] = labels.get(i);
        float[] g = new float[geom.size()];
        for (int i = 0; i < g.length; i++) g[i] = geom.get(i);
        android.util.Log.i(TAG, String.format("setLayout real: t=(%.2f,%.2f) e=(%.2f,%.2f) p=(%.2f,%.2f) a=(%.2f,%.2f)",
                g[4*idx('t',l)], g[4*idx('t',l)+1], g[4*idx('e',l)], g[4*idx('e',l)+1],
                g[4*idx('p',l)], g[4*idx('p',l)+1], g[4*idx('a',l)], g[4*idx('a',l)+1]));
        nativeSetLayout(l, g);
    }

    public static boolean isReady() { return sInitAttempted && nativeReady(); }

    /** Decodes gesture points given in keyboard pixel coordinates. */
    public static String[] decodeGesture(int[] xPx, int[] yPx, long[] times, int count) {
        if (!isReady() || count < 2) return new String[0];
        final float[] xs = new float[count];
        final float[] ys = new float[count];
        final long[] ts = new long[count];
        for (int i = 0; i < count; ++i) {
            xs[i] = xPx[i] / (float) sKeyboardWidth;
            ys[i] = yPx[i] / (float) sKeyboardHeight;
            ts[i] = times[i];
        }
        final float aspect = (float) sKeyboardHeight / (float) sKeyboardWidth;
        StringBuilder dbg = new StringBuilder("pts=");
        for (int i = 0; i < count; ++i) dbg.append(String.format("(%.2f,%.2f)", xs[i], ys[i]));
        Log.i(TAG, "decode n=" + count + " kb=" + sKeyboardWidth + "x" + sKeyboardHeight + " " + dbg);
        return decode(xs, ys, ts, aspect, 3);
    }

    /** Loads the bundled word<TAB>freq list into the decoder lexicon. */
    public static synchronized int initWords(Context c) {
        Log.i(TAG, "initWords called, attempted=" + sInitAttempted);
        if (!sInitAttempted) return 0;
        try {
            java.util.ArrayList<String> words = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> freqs = new java.util.ArrayList<>();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getResources().openRawResource(R.raw.swipe_words),
                            java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                words.add(line.substring(0, tab));
                try { freqs.add(Integer.parseInt(line.substring(tab + 1).trim())); }
                catch (Exception e) { freqs.add(128); }
            }
            r.close();
            int[] f = new int[freqs.size()];
            for (int i = 0; i < f.length; i++) f[i] = freqs.get(i);
            int n = setWords(words.toArray(new String[0]), f);
            Log.i(TAG, "initWords=" + n);
            return n;
        } catch (Exception e) {
            Log.e(TAG, "initWords failed: " + e.getMessage());
            return 0;
        }
    }

    /** Supplies the dictionary word list (word + 0..255 frequency). */
    public static int setWords(String[] words, int[] freqs) {
        return nativeSetWords(words, freqs);
    }

    /** Decodes a swipe. Coordinates are 0..1 of the keyboard, times in ms. */
    public static String[] decode(float[] xs, float[] ys, long[] ts, float aspect, int topN) {
        return nativeDecode(xs, ys, ts, aspect, topN);
    }
}
