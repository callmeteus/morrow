package dev.gti.gboard;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for the strict-prefix glide decoding (anti-hallucination). */
public class LocalDecoderTest {

    @Test
    public void exactTrailMatches() {
        assertTrue(LocalDecoder.isPrefixMatch("bom", "bom"));
        assertTrue(LocalDecoder.isPrefixMatch("voz", "voz"));
    }

    @Test
    public void wordLongerThanTrailMatches() {
        // finger traced "ca", word "casa" starts with it
        assertTrue(LocalDecoder.isPrefixMatch("ca", "casa"));
        assertTrue(LocalDecoder.isPrefixMatch("bom", "bomdia"));
    }

    @Test
    public void oneKeyOvershootAllowed() {
        // trail one key longer than word (finger slipped one extra key)
        assertTrue(LocalDecoder.isPrefixMatch("casa", "cas"));
    }

    @Test
    public void unrelatedWordRejected() {
        // This is the hallucination we fixed: cvhjo must NOT produce "dos"
        assertFalse(LocalDecoder.isPrefixMatch("cvhjo", "dos"));
        assertFalse(LocalDecoder.isPrefixMatch("cvhjo", "das"));
        assertFalse(LocalDecoder.isPrefixMatch("xyz", "casa"));
    }

    @Test
    public void tooMuchOvershootRejected() {
        assertFalse(LocalDecoder.isPrefixMatch("casaX", "cas"));
    }

    @Test
    public void keyGeometryHasQwertyLetters() {
        assertTrue(LocalDecoder.KEY_POS.containsKey('q'));
        assertTrue(LocalDecoder.KEY_POS.containsKey('m'));
        assertFalse(LocalDecoder.KEY_POS.containsKey('1'));
    }
}
