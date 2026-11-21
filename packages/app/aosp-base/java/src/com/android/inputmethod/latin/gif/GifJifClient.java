/*
 * gti.dev: minimal keyless client for the GifJif public GIF search API.
 * Offline-first friendly: failures simply return an empty list.
 */

package com.android.inputmethod.latin.gif;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public final class GifJifClient {
    private static final String TAG = "GifJifClient";
    private static final String BASE = "https://gifjif.com/api/search";

    public static final class Gif {
        public final String previewUrl;
        public final String fullUrl;
        public final String title;

        Gif(final String preview, final String full, final String title) {
            previewUrl = preview;
            fullUrl = full;
            this.title = title;
        }
    }

    private GifJifClient() {}

    public static List<Gif> search(final String query) {
        final List<Gif> result = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            final String q = URLEncoder.encode(query == null ? "" : query, "UTF-8");
            final URL url = new URL(BASE + "?q=" + q + "&limit=16");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "MorrowKeyboard/1.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + conn.getResponseCode());
                return result;
            }
            final StringBuilder sb = new StringBuilder();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            } finally {
                if (reader != null) reader.close();
            }
            final JSONObject root = new JSONObject(sb.toString());
            final JSONArray items = root.optJSONArray("items");
            if (items == null) return result;
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final JSONObject media = item.optJSONObject("media");
                if (media == null) continue;
                String preview = media.optString("tinygif", "");
                if (preview.isEmpty()) preview = media.optString("tinywebp", "");
                if (preview.isEmpty()) preview = media.optString("webp", "");
                String full = media.optString("gif", "");
                if (full.isEmpty()) full = media.optString("mp4", "");
                if (preview.isEmpty() || full.isEmpty()) continue;
                result.add(new Gif(preview, full, item.optString("title", "")));
            }
        } catch (Exception e) {
            Log.w(TAG, "search failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }
}
