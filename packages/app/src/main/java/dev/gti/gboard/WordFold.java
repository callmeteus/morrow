package dev.gti.gboard;

import java.text.Normalizer;

/** Lowercase and strip diacritics for accent-insensitive word comparison. */
public final class WordFold {
    private WordFold() {}

    public static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
