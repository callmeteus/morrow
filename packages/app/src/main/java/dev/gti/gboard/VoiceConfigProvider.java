package dev.gti.gboard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

/** Exposes the voice backend config to the IME (different package) via a read-only provider. */
public class VoiceConfigProvider extends ContentProvider {
    private static final String TAG = "VoiceConfigProvider";
    public static final String AUTHORITY = "dev.gti.gboard.voiceconfig";
    public static final Uri URI_CONFIG = Uri.parse("content://" + AUTHORITY + "/config");

    public static final String[] COLUMNS = {
            "gti_voice_use_external", "gti_voice_base_url", "gti_voice_api_key",
            "gti_voice_model", "gti_voice_language", "gti_swipe_diagnostics",
            "gti_collect_base_url", "gti_collect_token", "gti_collect_enabled"
    };

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static { MATCHER.addURI(AUTHORITY, "config", 1); }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (MATCHER.match(uri) != 1) return null;
        String caller = getCallingPackage();
        if (!ConfigAccess.isAllowed(caller)) {
            Log.w(TAG, "denied config query from " + caller);
            return null;
        }
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
        Object[] values = new Object[] {
                p.getBoolean("gti_voice_use_external", false) ? 1 : 0,
                p.getString("gti_voice_base_url", "http://100.84.51.1:8000/v1"),
                p.getString("gti_voice_api_key", ""),
                p.getString("gti_voice_model", "whisper-1"),
                p.getString("gti_voice_language", "pt"),
                p.getBoolean("gti_swipe_diagnostics", false) ? 1 : 0,
                p.getString("gti_collect_base_url", "https://morrow.gti.dev"),
                p.getString("gti_collect_token", ""),
                p.getBoolean("gti_collect_enabled", true) ? 1 : 0
        };
        // Build the cursor with only the columns the caller may read. The IME
        // never receives the collector token, even if it asks for all columns.
        java.util.List<String> cols = new java.util.ArrayList<>();
        java.util.List<Object> vals = new java.util.ArrayList<>();
        String[] wanted = projection != null && projection.length > 0 ? projection : COLUMNS;
        for (String col : wanted) {
            if (!ConfigAccess.mayRead(caller, col)) continue;
            for (int i = 0; i < COLUMNS.length; i++) {
                if (COLUMNS[i].equals(col)) {
                    cols.add(col);
                    vals.add(values[i]);
                    break;
                }
            }
        }
        MatrixCursor c = new MatrixCursor(cols.toArray(new String[0]));
        c.addRow(vals.toArray());
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/voiceconfig"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
