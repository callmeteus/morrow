/*
 * gti.dev: keyless translation client for the Gboard-style translation panel.
 * Uses MyMemory's public endpoint; failures return null so the caller can show
 * an error message instead of breaking the keyboard.
 */

package com.android.inputmethod.latin.clipboard;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class TranslationClient {
    private static final String TAG = "TranslationClient";

    private TranslationClient() {}

    /** Returns the translated text, or null on any failure. */
    public static String translate(final String text, final String from, final String to) {
        if (text == null || text.trim().isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            final String q = URLEncoder.encode(text.trim(), StandardCharsets.UTF_8);
            final String url = "https://api.mymemory.translated.net/get?q=" + q
                    + "&langpair=" + from + "|" + to;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MorrowKeyboard/1.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            final JSONObject root = new JSONObject(sb.toString());
            final JSONObject data = root.optJSONObject("responseData");
            if (data == null) return null;
            final String translated = data.optString("translatedText", "");
            if (translated.isEmpty() || translated.startsWith("MYMEMORY WARNING")) return null;
            return translated;
        } catch (Exception e) {
            Log.w(TAG, "translate failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
