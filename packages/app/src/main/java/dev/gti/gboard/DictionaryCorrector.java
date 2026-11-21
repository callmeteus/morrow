package dev.gti.gboard;

import android.content.Context;
import android.util.Log;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Local pt-BR dictionary + correction for the ASR output.
 *
 * Loads a word-frequency list (word + count per line) bundled in the APK
 * assets and, for every transcription token that is not a known word, looks
 * for a dictionary word within a small edit distance and swaps it in,
 * preferring the more frequent candidate. This is the on-device analog of
 * the post-ASR dictionary/correction layer Gboard ships (SmartDictation,
 * see docs/REA_GBOARD.md) and runs 100% offline.
 *
 * Comparison is accent-insensitive (both the dictionary and the tokens are
 * folded to a canonical form), which matters because Whisper sometimes
 * emits accented and unaccented spellings interchangeably.
 */
public final class DictionaryCorrector {
    private static final String TAG = "DictionaryCorrector";
    private static final String DICT_ASSET = "dict/pt-br-dictionary.txt";
    /** A distance-2 replacement must target a word at least this frequent. */
    private static final int MIN_FREQ_AT_DIST2 = 100;

    /** folded word -> frequency */
    private static final Map<String, Integer> FREQ = new HashMap<>();
    /** folded word -> display form (accented, lowercase) */
    private static final Map<String, String> DISPLAY = new HashMap<>();
    private static volatile boolean loaded = false;

    /** Debug-only hook: notified whenever a token is actually replaced. */
    public interface CorrectionListener {
        void onCorrection(String raw, String replacement, int distance, int frequency);
    }
    private static volatile CorrectionListener correctionListener;

    private DictionaryCorrector() {}

    /** Install (or clear with null) the dataset correction listener. */
    public static void setCorrectionListener(CorrectionListener listener) {
        correctionListener = listener;
    }

    public static synchronized void load(Context context) {
        if (loaded) return;
        try (InputStream in = context.getAssets().open(DICT_ASSET);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int tab = line.lastIndexOf('\t');
                String word = tab > 0 ? line.substring(0, tab) : line;
                int count = tab > 0 ? parseCount(line, tab + 1) : 1;
                if (word.length() < 2) continue;
                String key = fold(word);
                if (key.isEmpty()) continue;
                String display = word.toLowerCase();
                DISPLAY.putIfAbsent(key, display);
                Integer old = FREQ.get(key);
                FREQ.put(key, old == null ? count : Math.max(old, count));
            }
        } catch (IOException e) {
            Log.w(TAG, "dictionary not loaded: " + e.getMessage());
            return;
        }
        loaded = true;
        Log.i(TAG, "dictionary loaded: " + FREQ.size() + " words");
    }

    public static boolean isLoaded() { return loaded; }
    public static int size() { return FREQ.size(); }

    /** All display words, for ranking suggestions outside this class. */
    public static java.util.List<String> getWords() {
        return new java.util.ArrayList<>(DISPLAY.values());
    }

    /**
     * Returns up to {@code limit} of the most frequent dictionary words that
     * start with {@code prefix}. Feeds the Gboard-style suggestion strip; the
     * ranking by frequency mirrors a local language model, entirely offline.
     */
    public static java.util.List<String> suggest(String prefix, int limit) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!loaded || prefix == null) return out;
        String key = fold(prefix);
        if (key.isEmpty()) return out;
        java.util.List<Map.Entry<String, Integer>> cands = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : FREQ.entrySet()) {
            if (e.getKey().startsWith(key)) cands.add(e);
        }
        cands.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int n = Math.min(limit, cands.size());
        for (int i = 0; i < n; i++) {
            String d = DISPLAY.get(cands.get(i).getKey());
            if (d != null) out.add(d);
        }
        return out;
    }

    private static int parseCount(String line, int from) {
        try { return Integer.parseInt(line.substring(from).trim()); } catch (Exception e) { return 1; }
    }

    /** Corrects a whole transcription; unknown short tokens are left alone. */
    public static String correct(String text) {
        return correct(text, null);
    }

    /**
     * Corrects a transcription and reports each replacement to {@code listener}
     * passed in by the caller, instead of relying on a global listener that
     * depends on call order. The listener argument is optional.
     */
    public static String correct(String text, CorrectionListener listener) {
        if (!loaded || TextUtils.isEmpty(text)) return text;
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < n && Character.isLetterOrDigit(text.charAt(i))) i++;
                out.append(fixToken(text.substring(start, i), listener));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String fixToken(String raw) {
        return fixToken(raw, null);
    }

    private static String fixToken(String raw, CorrectionListener listener) {
        String key = fold(raw);
        if (key.length() < 4) return raw;          // too short: correction risk > benefit
        if (FREQ.containsKey(key)) return raw;     // known word: keep as-is

        int maxDist = key.length() >= 8 ? 2 : 1;
        String best = null;
        int bestDist = maxDist + 1;
        int bestFreq = -1;
        for (Map.Entry<String, Integer> e : FREQ.entrySet()) {
            String d = e.getKey();
            if (Math.abs(d.length() - key.length()) > maxDist) continue;
            int dist = levenshteinBounded(key, d, maxDist);
            // levenshteinBounded returns maxDist+1 as an "over bound" sentinel;
            // reject it before it can win the comparison.
            if (dist <= 0 || dist > maxDist) continue;
            int f = e.getValue();
            // A distance-2 swap only lands on very common words, so a rare
            // (but real) word is not replaced by a common one at distance 2.
            if (dist >= 2 && f < MIN_FREQ_AT_DIST2) continue;
            if (dist < bestDist || (dist == bestDist && f > bestFreq)) {
                bestDist = dist;
                bestFreq = f;
                best = d;
            }
        }
        if (best == null) return raw;
        String display = DISPLAY.get(best);
        if (display == null) return raw;
        boolean upper = Character.isUpperCase(raw.charAt(0));
        String result = upper
                ? Character.toUpperCase(display.charAt(0)) + display.substring(1)
                : display;
        CorrectionListener target = listener != null ? listener : correctionListener;
        if (target != null) {
            target.onCorrection(raw, result, bestDist, bestFreq);
        }
        return result;
    }

    /** Edit distance with early exit once the row minimum exceeds the bound. */
    static int levenshteinBounded(String a, String b, int bound) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > bound) return bound + 1;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int rowMin = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            // Safe early exit: the row minimum can drop by at most one per
            // remaining row, so this proves the final distance > bound.
            if (rowMin > bound + (la - i)) return bound + 1;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[lb];
    }

    /** Lowercase + strip diacritics (accent-insensitive key). */
    static String fold(String s) {
        return WordFold.fold(s);
    }
}
