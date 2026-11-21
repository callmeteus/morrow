/*
 * gti.dev: keyless GIF search with provider fallback.
 * Tries GifJif, then GifSnap, then urGIF so a single provider outage
 * does not leave the picker empty. Failures return an empty list.
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
        final String q = URLEncoder.encode(query == null ? "" : query,
                java.nio.charset.StandardCharsets.UTF_8);
        if (q.isEmpty()) return new ArrayList<>();

        List<Gif> result = searchGifJif(q);
        if (!result.isEmpty()) return result;

        Log.w(TAG, "GifJif returned no results, trying GifSnap");
        result = searchGifSnap(q);
        if (!result.isEmpty()) return result;

        Log.w(TAG, "GifSnap returned no results, trying urGIF");
        result = searchUrGif(q);
        return result;
    }

    private static String fetch(final String urlString) {
        HttpURLConnection conn = null;
        try {
            final URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "MorrowKeyboard/1.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + conn.getResponseCode() + " for " + urlString);
                return null;
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
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<Gif> searchGifJif(final String q) {
        final List<Gif> result = new ArrayList<>();
        final String body = fetch("https://gifjif.com/api/search?q=" + q + "&limit=16");
        if (body == null) return result;
        try {
            final JSONObject root = new JSONObject(body);
            final JSONArray items = root.optJSONArray("items");
            if (items == null) return result;
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final JSONObject media = item.optJSONObject("media");
                if (media == null) continue;
                String preview = firstNonEmpty(media, "tinygif", "tinywebp", "webp", "poster");
                String full = firstNonEmpty(media, "gif", "mp4", "webp");
                if (preview.isEmpty() || full.isEmpty()) continue;
                result.add(new Gif(preview, full, item.optString("title", "")));
            }
        } catch (Exception e) {
            Log.w(TAG, "GifJif parse failed: " + e.getMessage());
        }
        return result;
    }

    private static List<Gif> searchGifSnap(final String q) {
        final List<Gif> result = new ArrayList<>();
        final String body = fetch("https://gifsnap.com/api/v1/gifs/search?q=" + q
                + "&page=1&limit=16");
        if (body == null) return result;
        try {
            final JSONObject root = new JSONObject(body);
            final JSONArray items = root.optJSONArray("data");
            if (items == null) return result;
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final String preview = item.optString("preview_url", "");
                final String full = item.optString("url", "");
                if (preview.isEmpty() || full.isEmpty()) continue;
                result.add(new Gif(preview, full, item.optString("title", "")));
            }
        } catch (Exception e) {
            Log.w(TAG, "GifSnap parse failed: " + e.getMessage());
        }
        return result;
    }

    private static List<Gif> searchUrGif(final String q) {
        final List<Gif> result = new ArrayList<>();
        final String body = fetch("https://api.urgif.com/v1/search?q=" + q + "&limit=16");
        if (body == null) return result;
        try {
            final JSONObject root = new JSONObject(body);
            final JSONArray items = root.optJSONArray("data");
            if (items == null) return result;
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final String full = item.optString("url", "");
                if (full.isEmpty()) continue;
                // urGIF preview is an inline base64 data URI; use it directly.
                String preview = item.optString("preview", "");
                if (preview.isEmpty()) preview = full;
                result.add(new Gif(preview, full, ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "urGIF parse failed: " + e.getMessage());
        }
        return result;
    }

    private static String firstNonEmpty(final JSONObject o, final String... keys) {
        for (final String key : keys) {
            final String v = o.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
