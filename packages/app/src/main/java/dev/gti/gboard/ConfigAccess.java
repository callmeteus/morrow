package dev.gti.gboard;

import java.util.Arrays;
import java.util.List;

/**
 * Pure rule for who may read the config provider. No Android dependency so the
 * rule can be unit tested on the JVM.
 */
public final class ConfigAccess {
    /** The setup app itself and the IME package that consumes the config. */
    private static final List<String> ALLOWED_CALLERS = Arrays.asList(
            "dev.gti.gboard",
            "com.android.inputmethod.latin"
    );

    /** Columns the IME needs; the setup app may read all of them. */
    private static final List<String> IME_COLUMNS = Arrays.asList(
            "gti_voice_use_external", "gti_voice_base_url", "gti_voice_api_key",
            "gti_voice_model", "gti_voice_language", "gti_swipe_diagnostics"
    );

    private ConfigAccess() {}

    /** Only the setup app and the IME package may query the provider. */
    public static boolean isAllowed(String callingPackage) {
        return callingPackage != null && ALLOWED_CALLERS.contains(callingPackage);
    }

    /** The IME never needs the collector credentials; it gets only voice config. */
    public static boolean mayRead(String callingPackage, String column) {
        if (!isAllowed(callingPackage)) return false;
        if ("dev.gti.gboard".equals(callingPackage)) return true;
        return IME_COLUMNS.contains(column);
    }
}
