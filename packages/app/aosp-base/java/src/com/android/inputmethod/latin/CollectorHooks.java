package com.android.inputmethod.latin;

import com.android.inputmethod.latin.common.Constants;

import java.util.List;

/**
 * Single entry point for the AOSP IME to report voice, swipe and corrector
 * signals to the remote dataset collector. Holds the session identity and the
 * pending glide word so callers only pass the data they own.
 *
 * Debug-only: every method is a no-op in release builds.
 */
public final class CollectorHooks {
    private static CorrectionTracker tracker;
    private static String sessionId;
    private static String pendingSwipeId;
    private static String pendingSwipeWord;
    private static final StringBuilder sTypedWord = new StringBuilder();
    private static String sLastSwipeKey;
    private static long sLastSwipeTime;
    // Set only from LatinIME's batch-input callbacks, which AOSP drives from
    // BatchInputArbiter.isStartOfAGesture(). Plain key events never set it.
    private static volatile boolean sGestureActive;

    private CollectorHooks() {}

    /** Called once from LatinIME.onCreate(). */
    public static void init() {
        sessionId = CollectorClient.newId();
        tracker = new CorrectionTracker(sessionId);
    }

    public static String sessionId() {
        return sessionId == null ? "" : sessionId;
    }

    /** AOSP detected the start of a real gesture. Only then is a swipe reported.
     *  The gesture's trail keys arrive as onCodeInput events; keeping them out
     *  of the typed-word buffer prevents trail letters from being mistaken for
     *  a committed word (the source of keysmash in suggestion events). */
    public static void onGestureStarted() {
        sGestureActive = true;
        sTypedWord.setLength(0);
    }

    /** The gesture ended or was canceled; stop accepting swipe reports. */
    public static void onGestureEnded() {
        sGestureActive = false;
        sTypedWord.setLength(0);
    }

    /** When a glide is decoded, remember its id and word for failure correlation. */
    public static void onSwipe(int[] xs, int[] ys, long[] ts, String[] decoded,
                               int kbWidth, int kbHeight) {
        // recordDiagnostics runs for every suggestion pass, including plain
        // typing. Only traces collected inside a real AOSP gesture are data.
        if (!sGestureActive) return;
        if (tracker == null || decoded == null || decoded.length == 0) return;
        // The native decoder calls recordDiagnostics several times for one
        // gesture. Drop repeated traces of the same shape within a short window.
        int count = Math.min(xs.length, Math.min(ys.length, ts.length));
        StringBuilder key = new StringBuilder();
        if (count > 0) {
            key.append(xs[0]).append(',').append(ys[0]).append(';')
               .append(xs[count - 1]).append(',').append(ys[count - 1]).append(';')
               .append(count);
        }
        for (String d : decoded) if (d != null) key.append('|').append(d);
        String fingerprint = key.toString();
        long now = System.currentTimeMillis();
        if (fingerprint.equals(sLastSwipeKey) && now - sLastSwipeTime < 1500) return;
        sLastSwipeKey = fingerprint;
        sLastSwipeTime = now;
        String trail = "";
        String word = decoded[0] == null ? "" : decoded[0];
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String d : decoded) if (d != null) candidates.add(d);
        java.util.List<float[]> points = new java.util.ArrayList<>();
        int n = Math.min(xs.length, Math.min(ys.length, ts.length));
        if (kbWidth > 0 && kbHeight > 0) {
            for (int i = 0; i < n; i++) {
                points.add(new float[] { xs[i] / (float) kbWidth, ys[i] / (float) kbHeight, (float) ts[i] });
            }
        }
        String kb = kbWidth + "x" + kbHeight;
        pendingSwipeId = CollectorClient.postSwipe(sessionId, trail, word, candidates, kb, points);
        pendingSwipeWord = word;
    }

    /** Feed a key code point from LatinIME.onCodeInput. Letters/digits are
     *  accumulated; a separator flushes the pending word as a commit event. */
    public static void onKey(int codePoint) {
        if (tracker == null) return;
        // During a glide the finger crosses keys that fire onCodeInput, but
        // those are trail points, not typed characters. The real committed word
        // arrives separately; counting trail letters here produced keysmash.
        if (sGestureActive) return;
        if (codePoint == Constants.CODE_DELETE) {
            if (sTypedWord.length() > 0) sTypedWord.deleteCharAt(sTypedWord.length() - 1);
            return;
        }
        if (codePoint < 0) return; // functional / toolbar codes
        if (Character.isLetterOrDigit(codePoint)) {
            sTypedWord.appendCodePoint(codePoint);
        } else if (codePoint == ' ' || codePoint == '\n'
                || Character.getType(codePoint) == Character.OTHER_PUNCTUATION
                || Character.getType(codePoint) == Character.END_PUNCTUATION) {
            if (sTypedWord.length() > 0) {
                onWordCommitted(sTypedWord.toString());
                sTypedWord.setLength(0);
            }
        }
    }

    public static void onWordCommitted(String typed) {
        if (tracker == null || typed == null || typed.isEmpty()) return;
        CorrectionTracker.WordCommit commit = tracker.onWordCommitted(
                typed, pendingSwipeWord, pendingSwipeId, System.currentTimeMillis());
        if (commit != null) {
            for (CorrectionTracker.Event e : commit.events) CollectorClient.postCorrection(e);
        }
        clearPendingSwipe();
    }

    public static void onBackspace(String deleted) {
        if (tracker == null) return;
        CorrectionTracker.Event e = tracker.onBackspace(deleted, System.currentTimeMillis());
        if (e != null) CollectorClient.postCorrection(e);
    }

    public static void onSuggestionsShown(String prefix, List<String> shown,
                                          boolean fallback) {
        if (tracker == null) return;
        tracker.onSuggestionsShown(prefix, shown, System.currentTimeMillis());
        if (fallback) CollectorClient.postCorrection(tracker.onFallbackGeneric(System.currentTimeMillis()));
    }

    public static void onSuggestionSelected(int index) {
        if (tracker == null) return;
        CollectorClient.postCorrection(tracker.onSuggestionSelected(index, System.currentTimeMillis()));
        clearPendingSwipe();
    }

    public static void onAsrWord(String raw, String replacement, int distance,
                                 int frequency, String voiceId) {
        if (tracker == null) return;
        CollectorClient.postCorrection(tracker.onAsrCorrection(
                raw, replacement, distance, frequency, voiceId, System.currentTimeMillis()));
    }

    private static void clearPendingSwipe() {
        pendingSwipeId = null;
        pendingSwipeWord = "";
    }
}
