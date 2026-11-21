package dev.gti.gboard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WordFoldTest {
    @Test
    public void stripsAccentsAndLowercases() {
        assertEquals("nao", WordFold.fold("não"));
        assertEquals("nao", WordFold.fold("NAO"));
        assertEquals("nao", WordFold.fold("NÃO"));
        assertEquals("coracao", WordFold.fold("coração"));
    }

    @Test
    public void keepsPlainWords() {
        assertEquals("casa", WordFold.fold("casa"));
        assertEquals("", WordFold.fold(""));
    }
}
