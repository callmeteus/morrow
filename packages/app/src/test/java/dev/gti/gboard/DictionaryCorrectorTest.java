package dev.gti.gboard;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for the pure string logic of the local dictionary layer. */
public class DictionaryCorrectorTest {

    @Test
    public void foldIsAccentInsensitiveAndLowercase() {
        assertEquals("dicionario", DictionaryCorrector.fold("Dicionário"));
        assertEquals("casa", DictionaryCorrector.fold("cása"));
        assertEquals("nao", DictionaryCorrector.fold("Não"));
    }

    @Test
    public void levenshteinBoundedExactAndEditDistance() {
        assertEquals(0, DictionaryCorrector.levenshteinBounded("casa", "casa", 2));
        assertEquals(1, DictionaryCorrector.levenshteinBounded("casa", "casaX", 2));
        assertEquals(2, DictionaryCorrector.levenshteinBounded("casa", "casXX", 2));
    }

    @Test
    public void levenshteinRespectsBound() {
        // "casa" vs "xxxxxx" is far beyond bound 2 -> sentinel (> bound)
        int d = DictionaryCorrector.levenshteinBounded("casa", "xxxxxx", 2);
        assertTrue(d > 2);
    }

    @Test
    public void levenshteinSymmetric() {
        assertEquals(
            DictionaryCorrector.levenshteinBounded("internet", "intenet", 2),
            DictionaryCorrector.levenshteinBounded("intenet", "internet", 2));
    }
}
