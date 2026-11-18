package dev.gti.gboard;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Unit tests for the suggestion strip ranking. */
public class SuggestionEngineTest {

    private SuggestionEngine engine() {
        return new SuggestionEngine(
            Arrays.asList("de","que","não","bom","boa","casa","carro","tempo"),
            Arrays.asList("bomdia","bonito","cachorro","casa1")
        );
    }

    @Test
    public void emptyPrefixReturnsCommonWords() {
        assertEquals(Arrays.asList("de","que","não"), engine().suggest("", 3));
    }

    @Test
    public void prefixFiltersCommonWordsFirst() {
        assertTrue(engine().suggest("bo", 3).contains("bom"));
        assertTrue(engine().suggest("bo", 3).contains("boa"));
    }

    @Test
    public void dictionaryUsedAfterCommonWords() {
        // "ca" -> casa/carro (common) come before cachorro (dictionary)
        assertTrue(engine().suggest("ca", 3).contains("casa"));
    }

    @Test
    public void accentInsensitiveMatching() {
        assertEquals(Arrays.asList("não"), engine().suggest("nao", 3));
    }

    @Test
    public void noMatchReturnsEmpty() {
        assertTrue(engine().suggest("xyz", 3).isEmpty());
    }
}
