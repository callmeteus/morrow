package dev.gti.gboard;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for the pure, on-device post-ASR cleanup logic. */
public class TranscriptCleanerTest {

    @Test
    public void removesPortugueseMusicHallucination() {
        assertEquals("O teclado funciona",
                TranscriptCleaner.clean("[MÚSICA DE FUNDO] O teclado funciona"));
    }

    @Test
    public void removesEnglishMusicHallucination() {
        assertEquals("Olá mundo",
                TranscriptCleaner.clean("[Music] Olá mundo"));
    }

    @Test
    public void removesMusicNotesAndCollapsesWhitespace() {
        assertEquals("Bom dia", TranscriptCleaner.clean("♪ ♫  Bom   dia  ♪"));
    }

    @Test
    public void keepsLegitimateBracketedText() {
        assertEquals("Ver [versão 2] agora",
                TranscriptCleaner.clean("Ver [versão 2] agora"));
    }

    @Test
    public void onlyMusicTagBecomesEmptySpeech() {
        assertTrue(TranscriptCleaner.isEmptySpeech(
                TranscriptCleaner.clean("[MÚSICA DE FUNDO]")));
        assertFalse(TranscriptCleaner.isEmptySpeech(
                TranscriptCleaner.clean("olá")));
    }

    @Test
    public void silenceDetectionThreshold() {
        assertFalse(TranscriptCleaner.hasSpeech(0.001f));
        assertFalse(TranscriptCleaner.hasSpeech(0.011f));
        assertTrue(TranscriptCleaner.hasSpeech(0.02f));
        assertTrue(TranscriptCleaner.hasSpeech(0.3f));
    }

    @Test
    public void handlesNullAndEmpty() {
        assertEquals("", TranscriptCleaner.clean(null));
        assertEquals("", TranscriptCleaner.clean(""));
        assertTrue(TranscriptCleaner.isEmptySpeech(null));
    }
}
