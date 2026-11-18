package dev.gti.gboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, unit-testable suggestion ranking for the strip. Given the word prefix
 * being typed (and the previous committed word), returns the most likely next
 * words. Prefix matches from a common-word list rank first, then dictionary
 * matches. No Android dependency.
 */
public final class SuggestionEngine {
    private final List<String> commonWords;
    private final List<String> dictionary;

    public SuggestionEngine(List<String> commonWords, List<String> dictionary) {
        this.commonWords = commonWords == null ? new ArrayList<>() : commonWords;
        this.dictionary = dictionary == null ? new ArrayList<>() : dictionary;
    }

    /** Returns up to limit suggestions for the given prefix. */
    public List<String> suggest(String prefix, int limit) {
        List<String> out = new ArrayList<>();
        if (prefix == null) return out;
        String p = fold(prefix);
        // Common words first (everyday usage order = list order).
        if (p.isEmpty()) {
            for (int i = 0; i < commonWords.size() && out.size() < limit; i++) {
                addUnique(out, commonWords.get(i));
            }
            return out;
        }
        for (String w : commonWords) {
            if (fold(w).startsWith(p)) addUnique(out, w);
            if (out.size() >= limit) return out;
        }
        for (String w : dictionary) {
            if (fold(w).startsWith(p)) addUnique(out, w);
            if (out.size() >= limit) return out;
        }
        return out;
    }

    private static void addUnique(List<String> l, String s) {
        if (s != null && !s.isEmpty() && !l.contains(s)) l.add(s);
    }

    private static String fold(String s) {
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
