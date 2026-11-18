/*
 * gti.dev: opt-in swipe diagnostics. When the user enables it in the setup
 * screen, each gesture is appended to a JSONL file in the IME's files dir:
 * the raw trace (x,y,time), the words the decoder proposed, and the word the
 * user finally typed (the "intended" word) captured via onTextInput.
 *
 * Disabled by default. No network, no Google, nothing leaves the device until
 * the user manually pulls the file via adb (or a future explicit export).
 */
package com.android.inputmethod.latin;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public final class SwipeDiagnostics {
    private static final String TAG = "SwipeDiag";
    private static final String PREF_ENABLED = "gti_swipe_diagnostics";
    private static final String FILE_NAME = "swipe_diagnostics.jsonl";

    private SwipeDiagnostics() {}

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    public static boolean isEnabled(Context c) {
        // Read the opt-in toggle from the setup app via its content provider.
        try {
            android.database.Cursor cur = c.getContentResolver().query(
                    android.net.Uri.parse("content://dev.gti.gboard.voiceconfig/config"),
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

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_ENABLED, on).apply();
    }

    /** Appends one gesture record. orderedWords are the decoder's ranked guesses. */
    public static void record(Context c, int[] xsPx, int[] ysPx, long[] times,
            String[] decoded, int kbWidth, int kbHeight) {
        if (!isEnabled(c)) return;
        try {
            File f = new File(c.getFilesDir(), FILE_NAME);
            FileWriter w = new FileWriter(f, true);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"gesture\",\"ts\":").append(System.currentTimeMillis());
            sb.append(",\"kb\":").append(kbWidth).append('x').append(kbHeight);
            // InputPointers arrays are pre-allocated (48) while times has only
            // the real point count. Iterate the shortest to avoid AIOOBE.
            final int count = Math.min(xsPx.length, Math.min(ysPx.length, times.length));
            sb.append(",\"n\":").append(count);
            sb.append(",\"pts\":[");
            for (int i = 0; i < count; ++i) {
                if (i > 0) sb.append(',');
                sb.append(String.format(java.util.Locale.US, "[%.4f,%.4f,%d]",
                        xsPx[i] / (float) kbWidth, ysPx[i] / (float) kbHeight, times[i]));
            }
            sb.append("],\"decoded\":[");
            if (decoded != null) for (int i = 0; i < decoded.length; ++i) {
                if (i > 0) sb.append(',');
                sb.append('"').append(decoded[i] == null ? "" : decoded[i]).append('"');
            }
            sb.append("]}\n");
            w.write(sb.toString());
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "record failed: " + e.getMessage());
        }
    }

    /** Records the word the user actually typed (the intended word after a swipe). */
    public static void recordTyped(Context c, String word) {
        if (!isEnabled(c) || word == null || word.isEmpty()) return;
        try {
            File f = new File(c.getFilesDir(), FILE_NAME);
            FileWriter w = new FileWriter(f, true);
            w.write("{\"type\":\"typed\",\"ts\":" + System.currentTimeMillis()
                    + ",\"word\":\"" + word + "\"}\n");
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "recordTyped failed: " + e.getMessage());
        }
    }

    public static String getPath(Context c) {
        return new File(c.getFilesDir(), FILE_NAME).getAbsolutePath();
    }
}
