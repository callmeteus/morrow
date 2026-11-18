/*
 * gti.dev: opt-in voice diagnostics. Appends one JSON line per dictation to
 * voice_diagnostics.jsonl in the IME files dir, with: background RMS, speech
 * peak, adaptive threshold used, whether speech was detected, timing and the
 * final transcript (or empty). Off by default; reads the same opt-in toggle as
 * swipe diagnostics. Nothing leaves the device.
 */
package com.android.inputmethod.latin;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

public final class VoiceDiagnostics {
    private static final String TAG = "VoiceDiag";
    private static final String FILE_NAME = "voice_diagnostics.jsonl";

    private VoiceDiagnostics() {}

    static boolean enabled(Context c) {
        try {
            Cursor cur = c.getContentResolver().query(
                    Uri.parse("content://dev.gti.gboard.voiceconfig/config"),
                    null, null, null, null);
            if (cur != null && cur.moveToFirst() && cur.getColumnCount() > 5) {
                boolean on = cur.getInt(5) == 1;
                cur.close();
                return on;
            }
            if (cur != null) cur.close();
        } catch (Exception ignored) {}
        return false;
    }

    public static void record(final Context c, final double noiseRms, final double peakRms,
            final double threshold, final boolean speechDetected, final long durationMs,
            final int samples, final String transcript, final String reason) {
        if (c == null || !enabled(c)) return;
        try {
            File f = new File(c.getFilesDir(), FILE_NAME);
            FileWriter w = new FileWriter(f, true);
            String t = transcript == null ? "" : transcript.replace("\\", "\\\\").replace("\"", "\\\"");
            w.write(String.format(Locale.US,
                    "{\"ts\":%d,\"noise\":%.5f,\"peak\":%.5f,\"thr\":%.5f,\"speech\":%d,"
                            + "\"dur\":%d,\"samples\":%d,\"reason\":\"%s\",\"text\":\"%s\"}\n",
                    System.currentTimeMillis(), noiseRms, peakRms, threshold,
                    speechDetected ? 1 : 0, durationMs, samples, reason, t));
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "record failed: " + e.getMessage());
        }
    }

    /** Records the final transcript of the last dictation (the "Transcrevendo..." outcome). */
    public static void recordResult(final Context c, final String transcript, final long inferMs) {
        if (c == null || !enabled(c)) return;
        try {
            File f = new File(c.getFilesDir(), FILE_NAME);
            FileWriter w = new FileWriter(f, true);
            String t = transcript == null ? "" : transcript.replace("\\", "\\\\").replace("\"", "\\\"");
            w.write(String.format(Locale.US,
                    "{\"type\":\"result\",\"ts\":%d,\"infer\":%d,\"empty\":%d,\"text\":\"%s\"}\n",
                    System.currentTimeMillis(), inferMs, t.isEmpty() ? 1 : 0, t));
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "recordResult failed: " + e.getMessage());
        }
    }

    public static String getPath(Context c) {
        return new File(c.getFilesDir(), FILE_NAME).getAbsolutePath();
    }
}
