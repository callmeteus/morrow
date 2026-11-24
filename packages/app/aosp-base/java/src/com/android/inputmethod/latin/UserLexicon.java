/*
 * gti.dev: persistent, on-device user lexicon.
 *
 * Two things live here:
 *  - corrections: when the user replaces a suggested/typed word with another,
 *    the rejected word is remembered and never offered again, and the chosen
 *    word is remembered as its replacement.
 *  - frequency: every committed word gets a count, so words the user writes
 *    often gradually outrank equally-frequent dictionary words.
 *
 * Stored in SharedPreferences (private to the IME). Empty by default, so the
 * keyboard behaves like stock AOSP until the user actually corrects something.
 */
package com.android.inputmethod.latin;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class UserLexicon {
    private static final String TAG = "UserLexicon";
    private static final String PREFS = "morrow_user_lexicon";
    private static final String KEY_CORRECTIONS = "corrections";
    private static final String KEY_COUNTS = "counts";
    private static final int MAX_BOOST = 64;

    private static volatile UserLexicon sInstance;

    private final SharedPreferences mPrefs;
    private Map<String, String> mCorrections;
    private Map<String, Integer> mCounts;

    private UserLexicon(Context c) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
        mCorrections = loadCorrections();
        mCounts = loadCounts();
    }

    public static void init(Context c) {
        if (c != null && sInstance == null) {
            synchronized (UserLexicon.class) {
                if (sInstance == null) sInstance = new UserLexicon(c);
            }
        }
    }

    public static UserLexicon get() {
        return sInstance;
    }

    /** Remember that the user replaced "wrong" with "right". */
    public void noteCorrection(String wrong, String right) {
        if (wrong == null || right == null) return;
        final String w = fold(wrong);
        final String r = fold(right);
        if (w.isEmpty() || r.isEmpty() || w.equals(r)) return;
        mCorrections.put(w, r);
        mPrefs.edit().putString(KEY_CORRECTIONS, serialiseCorrections()).apply();
    }

    /** Minimum repetitions before a word joins the personal dictionary. */
    public static final int FREQUENT_THRESHOLD = 2;

    private static Runnable sOnWordBecameFrequent;

    public static void setOnWordBecameFrequent(final Runnable r) {
        sOnWordBecameFrequent = r;
    }

    /** Remember a committed word so frequent words gain weight over time. */
    public void noteWord(String word) {
        if (word == null) return;
        final String w = fold(word);
        if (w.isEmpty() || w.length() < 2) return;
        final int old = mCounts.containsKey(w) ? mCounts.get(w) : 0;
        final int count = old + 1;
        mCounts.put(w, count);
        mPrefs.edit().putString(KEY_COUNTS, serialiseCounts()).apply();
        // gti.dev: reload the swipe lexicon when a word first becomes frequent.
        if (old < FREQUENT_THRESHOLD && count >= FREQUENT_THRESHOLD
                && sOnWordBecameFrequent != null) {
            sOnWordBecameFrequent.run();
        }
    }

    /** True when the user previously rejected this word. */
    public boolean isSuppressed(String word) {
        return word != null && mCorrections.containsKey(fold(word));
    }

    /** The word the user prefers instead, or null. */
    public String replacementFor(String word) {
        return word == null ? null : mCorrections.get(fold(word));
    }

    /** gti.dev: words the user repeated often enough to join the personal dictionary. */
    public Map<String, Integer> frequentWords(final int minCount) {
        final Map<String, Integer> out = new HashMap<>();
        for (final Map.Entry<String, Integer> e : mCounts.entrySet()) {
            if (e.getValue() != null && e.getValue() >= minCount) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Extra ranking weight for frequently written words (0..MAX_BOOST). */
    public int boost(String word) {
        if (word == null) return 0;
        final Integer c = mCounts.get(fold(word));
        if (c == null) return 0;
        return Math.min(MAX_BOOST, (int) (8 * Math.log(1 + c) / Math.log(2)));
    }

    private static String fold(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, String> loadCorrections() {
        final Map<String, String> m = new HashMap<>();
        final String raw = mPrefs.getString(KEY_CORRECTIONS, "");
        for (String pair : raw.split(",")) {
            final String[] kv = pair.split("=");
            if (kv.length == 2 && !kv[0].isEmpty() && !kv[1].isEmpty()) {
                m.put(kv[0], kv[1]);
            }
        }
        return m;
    }

    private Map<String, Integer> loadCounts() {
        final Map<String, Integer> m = new HashMap<>();
        final String raw = mPrefs.getString(KEY_COUNTS, "");
        for (String pair : raw.split(",")) {
            final String[] kv = pair.split("=");
            if (kv.length == 2) {
                try { m.put(kv[0], Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {}
            }
        }
        return m;
    }

    private String serialiseCorrections() {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : mCorrections.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private String serialiseCounts() {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : mCounts.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
