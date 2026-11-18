package dev.gti.gboard;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device decoder reimplementing the CONTRACT of Gboard's Delight5 Decoder
 * (see docs/REA_GBOARD_UI.md), not its proprietary code. Gboard's decoder is a
 * native FST + serialized language model reached through decodeNative(),
 * onKeyPressNative() and a spatial keyboard layout. Here the equivalent is a
 * compact pt-BR unigram + bigram model (asset) plus a spatial keyboard layout,
 * used to rank candidates from a glide trail and from key presses.
 *
 * All inference is local. No network, no Google services.
 */
public final class LocalDecoder {
    private static final String TAG = "LocalDecoder";
    private static final String LM_ASSET = "dict/pt-br-lm.txt";

    /** folded word -> log frequency */
    private static final Map<String, Float> UNI = new HashMap<>();
    /** "foldedA foldedB" -> log count */
    private static final Map<String, Float> BI = new HashMap<>();
    /** display form keyed by folded form */
    private static final Map<String, String> DISPLAY = new HashMap<>();
    private static volatile boolean loaded = false;

    /** Spatial keyboard geometry: center (x,y) in key units for QWERTY rows. */
    static final Map<Character, float[]> KEY_POS = new HashMap<>();
    static {
        String row1 = "qwertyuiop";
        String row2 = "asdfghjkl";
        String row3 = "zxcvbnm";
        for (int i = 0; i < row1.length(); i++) KEY_POS.put(row1.charAt(i), new float[]{i, 0});
        for (int i = 0; i < row2.length(); i++) KEY_POS.put(row2.charAt(i), new float[]{i + 0.5f, 1});
        for (int i = 0; i < row3.length(); i++) KEY_POS.put(row3.charAt(i), new float[]{i + 1.5f, 2});
    }

    private LocalDecoder() {}

    public static synchronized void load(Context c) {
        if (loaded) return;
        try (InputStream in = c.getAssets().open(LM_ASSET);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int tab = line.lastIndexOf('\t');
                if (tab < 0) continue;
                String key = line.substring(0, tab);
                float score = (float) Math.log(1 + parseCount(line.substring(tab + 1)));
                if (key.indexOf(' ') > 0) {
                    BI.put(fold(key), score);
                } else {
                    String f = fold(key);
                    UNI.put(f, score);
                    DISPLAY.putIfAbsent(f, key);
                }
            }
            loaded = true;
            Log.i(TAG, "LM loaded: " + UNI.size() + " unigrams, " + BI.size() + " bigrams");
        } catch (IOException e) {
            Log.w(TAG, "LM not loaded: " + e.getMessage());
        }
    }

    public static boolean isLoaded() { return loaded; }

    private static int parseCount(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 1; }
    }

    private static String fold(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * Equivalent of Gboard's Decoder.decode(): ranks candidate words for a
     * glide trail. A glide word must START with the letters the finger traced.
     * Only the last traced key may be an overshoot (allowing 0 or 1 trailing
     * extra key). This strict-prefix rule is what keeps glide predictions sane
     * instead of "hallucinating" unrelated frequent words.
     */
    public static List<String> decode(String trail) {
        List<String> out = new ArrayList<>();
        if (!loaded || TextUtils.isEmpty(trail)) return out;
        String t = fold(trail);
        if (t.length() < 2) return out;

        List<Map.Entry<String, Float>> scored = new ArrayList<>();
        for (Map.Entry<String, Float> e : UNI.entrySet()) {
            String w = e.getKey();            // folded dictionary word
            if (!isPrefixMatch(t, w)) continue;
            scored.add(new java.util.AbstractMap.SimpleEntry<>(w, e.getValue()));
        }
        scored.sort(Comparator.comparingDouble((Map.Entry<String, Float> e) -> -e.getValue()));
        for (int i = 0; i < scored.size() && out.size() < 5; i++) {
            String d = DISPLAY.get(scored.get(i).getKey());
            if (d != null) addUnique(out, d);
        }
        // If nothing matched (e.g. the trail includes junk keys), fall back to
        // the literal trail so at least the exact letters typed are committed.
        if (out.isEmpty()) addUnique(out, trail.toLowerCase());
        return out;
    }

    /**
     * True when a glide trail can produce a word. The traced letters must match
     * the start of the word; the word may overrun the trail by 0..1 letters, or
     * the trail may overrun the word by 0..1 keys (finger overshoot). Anything
     * more is rejected, which prevents unrelated-word hallucinations.
     */
    static boolean isPrefixMatch(String trail, String word) {
        if (trail == null || word == null) return false;
        if (word.length() >= trail.length()) {
            return word.startsWith(trail);
        }
        return trail.startsWith(word) && (trail.length() - word.length()) <= 1;
    }

    /** Equivalent of Decoder.onKeyPress(): suggest the next word after context. */
    public static List<String> nextWords(String previousWord, int limit) {
        List<String> out = new ArrayList<>();
        if (!loaded) return out;
        final String prev = previousWord == null ? null : fold(previousWord);
        List<Map.Entry<String, Float>> scored = new ArrayList<>();
        for (Map.Entry<String, Float> e : UNI.entrySet()) {
            String w = e.getKey();
            if (w.equals(prev)) continue;
            scored.add(new java.util.AbstractMap.SimpleEntry<>(
                    w, e.getValue() + bigramScore(prev, w)));
        }
        scored.sort(Comparator.comparingDouble((Map.Entry<String, Float> e) -> -e.getValue()));
        for (int i = 0; i < scored.size() && out.size() < limit; i++) {
            String d = DISPLAY.get(scored.get(i).getKey());
            if (d != null) addUnique(out, d);
        }
        return out;
    }

    private static float bigramScore(String prev, String word) {
        if (prev == null || prev.isEmpty()) return 0f;
        Float v = BI.get(fold(prev + " " + word));
        return v == null ? -2f : v;
    }

    /** Cost of a trail key the word does not use (finger overshoot). */
    private static final double INSERT_COST = 0.45;
    /** Cost of a word letter the trail did not cover (undershoot). */
    private static final double DELETE_COST = 1.2;

    /**
     * Spatial score for a glide trail, mirroring the spatial model of Gboard's
     * decoder. The finger sweeps across intermediate keys and can miss others,
     * so a strict subsequence fails. Instead we align the trail's key positions
     * to the word's letter positions with dynamic programming, allowing skipped
     * trail keys (overshoot) and uncovered word letters (undershoot). The match
     * cost is the physical distance between the two keys on the QWERTY layout.
     */
    static double spatialScore(String trail, String word) {
        List<float[]> tps = new ArrayList<>();
        for (int i = 0; i < trail.length(); i++) {
            float[] p = KEY_POS.get(trail.charAt(i));
            if (p != null) tps.add(p);
        }
        List<float[]> wps = new ArrayList<>();
        for (int i = 0; i < word.length(); i++) {
            float[] p = KEY_POS.get(word.charAt(i));
            if (p != null) wps.add(p);
        }
        int n = tps.size(), m = wps.size();
        if (n == 0 || m == 0) return -1;

        double[][] dp = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) for (int j = 0; j <= m; j++) dp[i][j] = Double.MAX_VALUE / 4;
        dp[0][0] = 0;
        for (int i = 1; i <= n; i++) dp[i][0] = dp[i - 1][0] + INSERT_COST;
        for (int j = 1; j <= m; j++) dp[0][j] = dp[0][j - 1] + DELETE_COST;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                float[] tp = tps.get(i - 1), wp = wps.get(j - 1);
                double d = Math.abs(tp[0] - wp[0]) + Math.abs(tp[1] - wp[1]);
                double match = dp[i - 1][j - 1] + (d <= 3.0 ? d : 3.0);
                dp[i][j] = Math.min(dp[i - 1][j] + INSERT_COST,
                        Math.min(dp[i][j - 1] + DELETE_COST, match));
            }
        }
        return 8.0 - dp[n][m] / m;
    }

    private static void addUnique(List<String> l, String s) {
        if (!TextUtils.isEmpty(s) && !l.contains(s)) l.add(s);
    }
}
