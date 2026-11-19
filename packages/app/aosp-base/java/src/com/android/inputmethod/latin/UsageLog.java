/*
 * gti.dev: local usage/training-data collector.
 *
 * Appends one JSON line per event to usage/usage_events.jsonl in the IME cache
 * dir. It records every source of text (typed keys, swipe decoding, dictation),
 * the final committed text, and the user's corrections (backspace, suggestion
 * pick, selection), linking each event to the preceding commit so a training
 * pipeline can match "what the model proposed" with "what the user kept".
 *
 * Nothing is sent anywhere. Data stays in this app's private cache until a
 * future release uploads it. Disable with the "gti_usage_log" setting in the
 * setup app (or the local IME preference of the same name).
 */
package com.android.inputmethod.latin;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class UsageLog {
    private static final String TAG = "UsageLog";
    private static final String FILE_NAME = "usage_events.jsonl";
    private static final String DIR_NAME = "usage";
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final int MAX_GESTURE_POINTS = 48;
    private static final int MAX_CONTEXT = 80;

    private static volatile UsageLog sInstance;

    private final Context mContext;
    private final Object mLock = new Object();
    private final ExecutorService mWriter = Executors.newSingleThreadExecutor();
    private final long mSessionId = System.currentTimeMillis();

    private int mSeq;
    private String mLastCommitId;
    private String mLastCommitText;
    private String mPendingSwipeId;
    private String mPendingVoiceId;
    private long mLastDecodeTs;

    private UsageLog(Context c) {
        mContext = c.getApplicationContext();
    }

    public static void init(Context c) {
        if (c != null && sInstance == null) {
            synchronized (UsageLog.class) {
                if (sInstance == null) sInstance = new UsageLog(c);
            }
        }
    }

    public static UsageLog get() {
        return sInstance;
    }

    /** Opt-out is honoured from the setup app's config provider, with a local fallback. */
    private boolean enabled() {
        Context c = mContext;
        if (c == null) return false;
        try {
            Cursor cur = c.getContentResolver().query(
                    Uri.parse("content://dev.gti.gboard.voiceconfig/config"),
                    null, null, null, null);
            if (cur != null) {
                boolean on = true;
                if (cur.moveToFirst() && cur.getColumnCount() > 6) {
                    on = cur.getInt(6) != 0;
                }
                cur.close();
                return on;
            }
        } catch (Exception ignored) {
            // Provider not installed yet. Fall back to the IME's own preference.
        }
        try {
            return android.preference.PreferenceManager.getDefaultSharedPreferences(c)
                    .getBoolean("gti_usage_log", true);
        } catch (Exception ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Event API. Each method is safe to call from any thread.
    // ------------------------------------------------------------------

    /** A physical/software key press (letter, punctuation, modifier handled by callers). */
    public void key(int codePoint, String label) {
        String text = codePoint > 0
                ? new String(Character.toChars(codePoint)) : "";
        append(event("key")
                .add("code", codePoint)
                .add("label", label == null ? text : label)
                .build());
    }

    /**
     * Raw swipe decode. Stores a downsampled gesture plus the model's candidate
     * list. Returns the id used to link the eventual committed word back here.
     */
    public String swipeDecode(int[] xs, int[] ys, long[] times, String[] decoded,
            int width, int height) {
        // The decoder runs on every MOVE event. Keep only meaningful changes so the
        // log has one or two entries per gesture instead of a dozen identical ones.
        final String sig = array(decoded);
        final long now = System.currentTimeMillis();
        if (mPendingSwipeId != null && now - mLastDecodeTs < 300) {
            return mPendingSwipeId;
        }
        mLastDecodeTs = now;

        final String id = newId("sw");
        int w = Math.max(1, width), h = Math.max(1, height);
        StringBuilder pts = new StringBuilder("[");
        if (xs != null && ys != null) {
            // InputPointers uses a fixed-size buffer padded with zeros. Find the
            // real end of the trace before sampling.
            int n = Math.min(xs.length, ys.length);
            while (n > 0 && xs[n - 1] == 0 && ys[n - 1] == 0) n--;
            int maxX = 1, maxY = 1;
            for (int i = 0; i < n; i++) {
                if (xs[i] > maxX) maxX = xs[i];
                if (ys[i] > maxY) maxY = ys[i];
            }
            if (width <= 0) w = maxX;
            if (height <= 0) h = maxY;
            final int step = Math.max(1, n / MAX_GESTURE_POINTS);
            boolean first = true;
            for (int i = 0; i < n; i += step) {
                if (!first) pts.append(',');
                first = false;
                int x = (int) (1000L * xs[i] / w);
                int y = (int) (1000L * ys[i] / h);
                long t = times != null && i < times.length ? times[i] : 0L;
                pts.append('[').append(x).append(',').append(y).append(',').append(t).append(']');
            }
        }
        pts.append(']');
        append(event("swipe_decode")
                .add("id", id)
                .add("kbd", w).add("kbdh", h)
                .add("pts", true, pts.toString())
                .add("cands", true, sig)
                .build());
        mPendingSwipeId = id;
        return id;
    }

    /** Final suggestion list produced for a gesture, before the user commits it. */
    public void swipeTop(String top, String[] candidates) {
        append(event("swipe_top")
                .add("top", top)
                .add("swipe_id", mPendingSwipeId)
                .add("cands", true, array(candidates))
                .build());
    }

    /** Dictation result. Returns the id used to link the committed transcript. */
    public String voice(String raw, String backend, long inferMs) {
        final String id = newId("vc");
        append(event("voice")
                .add("id", id)
                .add("backend", backend == null ? "local" : backend)
                .add("infer_ms", inferMs)
                .add("raw", raw)
                .build());
        mPendingVoiceId = id;
        return id;
    }

    /**
     * Final text committed to the editor. Links back to the pending swipe or
     * dictation id when present, and to the previous commit so corrections can
     * be paired with the exact text they replaced.
     */
    public String commit(String text, String origin) {
        final String id = newId("cm");
        final String prev = mLastCommitId;
        String swipeId = null;
        String voiceId = null;
        if ("swipe".equals(origin)) {
            swipeId = mPendingSwipeId;
            mPendingSwipeId = null;
        } else if ("voice".equals(origin)) {
            voiceId = mPendingVoiceId;
            mPendingVoiceId = null;
        } else {
            mPendingSwipeId = null;
            mPendingVoiceId = null;
        }
        append(event("commit")
                .add("id", id)
                .add("origin", origin)
                .add("text", text)
                .add("swipe_id", swipeId)
                .add("voice_id", voiceId)
                .add("prev_id", prev)
                .build());
        mLastCommitId = id;
        mLastCommitText = text;
        return id;
    }

    /** User deleted characters. Links to the commit being corrected. */
    public void backspace(int count, CharSequence textBefore) {
        append(event("backspace")
                .add("n", count)
                .add("target_id", mLastCommitId)
                .add("target_text", mLastCommitText)
                .add("before", clip(textBefore))
                .build());
    }

    /** User picked a suggestion instead of what the IME produced. */
    public void suggestionPicked(String chosen, String original) {
        append(event("suggestion_pick")
                .add("chosen", chosen)
                .add("original", original)
                .add("target_id", mLastCommitId)
                .add("target_text", mLastCommitText)
                .build());
    }

    /** Cursor moved / selection changed (registered only when text is selected). */
    public void selection(CharSequence selected) {
        if (selected == null || selected.length() == 0) return;
        append(event("selection")
                .add("text", selected.toString())
                .add("target_id", mLastCommitId)
                .build());
    }

    public void flush() {
        try {
            mWriter.shutdown();
            mWriter.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String getPath(Context c) {
        File dir = new File(c.getCacheDir(), DIR_NAME);
        return new File(dir, FILE_NAME).getAbsolutePath();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private String newId(String prefix) {
        return prefix + "_" + Long.toHexString(System.currentTimeMillis())
                + "_" + Integer.toHexString(mSeq & 0xffff)
                + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private Builder event(String type) {
        synchronized (mLock) {
            mSeq++;
            return new Builder(type, mSessionId, mSeq);
        }
    }

    private void append(final String line) {
        if (!enabled()) return;
        mWriter.execute(() -> write(line));
    }

    private void write(String line) {
        File dir = new File(mContext.getCacheDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return;
        File f = new File(dir, FILE_NAME);
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                File old = new File(dir, FILE_NAME + ".1");
                if (old.exists() && !old.delete()) Log.w(TAG, "could not rotate old log");
                if (!f.renameTo(old)) Log.w(TAG, "could not rotate log");
            }
            BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
            w.write(line);
            w.newLine();
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "write failed: " + e.getMessage());
        }
    }

    private static String clip(CharSequence s) {
        if (s == null) return "";
        String t = s.toString();
        return t.length() > MAX_CONTEXT ? t.substring(t.length() - MAX_CONTEXT) : t;
    }

    private static String array(String[] a) {
        if (a == null || a.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(a[i])).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class Builder {
        private final StringBuilder sb = new StringBuilder(256);

        Builder(String type, long session, int seq) {
            sb.append("{\"type\":\"").append(type).append('"')
              .append(",\"ts\":").append(System.currentTimeMillis())
              .append(",\"s\":").append(session)
              .append(",\"n\":").append(seq);
        }

        Builder add(String key, String value) {
            sb.append(",\"").append(key).append("\":")
              .append(value == null ? "null" : "\"" + escape(value) + "\"");
            return this;
        }

        Builder add(String key, int value) {
            sb.append(",\"").append(key).append("\":").append(value);
            return this;
        }

        Builder add(String key, long value) {
            sb.append(",\"").append(key).append("\":").append(value);
            return this;
        }

        /** Inserts an already-serialised JSON fragment (array/object). */
        Builder add(String key, boolean raw, String value) {
            if (raw) sb.append(",\"").append(key).append("\":").append(value);
            else add(key, value);
            return this;
        }

        String build() {
            return sb.append('}').toString();
        }
    }
}
