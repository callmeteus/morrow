package dev.gti.gboard;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigAccessTest {
    @Test
    public void onlySetupAppAndImeAreAllowed() {
        assertTrue(ConfigAccess.isAllowed("dev.gti.gboard"));
        assertTrue(ConfigAccess.isAllowed("com.android.inputmethod.latin"));
        assertFalse(ConfigAccess.isAllowed("com.evil.app"));
        assertFalse(ConfigAccess.isAllowed(null));
        assertFalse(ConfigAccess.isAllowed(""));
    }

    @Test
    public void imeCannotReadCollectorCredentials() {
        String ime = "com.android.inputmethod.latin";
        assertTrue(ConfigAccess.mayRead(ime, "gti_voice_base_url"));
        assertTrue(ConfigAccess.mayRead(ime, "gti_voice_api_key"));
        assertFalse(ConfigAccess.mayRead(ime, "gti_collect_token"));
        assertFalse(ConfigAccess.mayRead(ime, "gti_collect_base_url"));
        assertFalse(ConfigAccess.mayRead(ime, "gti_collect_enabled"));
    }

    @Test
    public void setupAppReadsAllColumns() {
        String self = "dev.gti.gboard";
        assertTrue(ConfigAccess.mayRead(self, "gti_collect_token"));
        assertTrue(ConfigAccess.mayRead(self, "gti_voice_api_key"));
        assertTrue(ConfigAccess.mayRead(self, "gti_collect_base_url"));
    }

    @Test
    public void strangerReadsNothing() {
        assertFalse(ConfigAccess.mayRead("com.evil.app", "gti_collect_token"));
        assertFalse(ConfigAccess.mayRead("com.evil.app", "gti_voice_api_key"));
    }
}
