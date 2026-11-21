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

    /** A page of results plus the cursor needed to fetch the next one. */
    public static final class Result {
        public final List<Gif> gifs;
        public final String cursor;
        public final boolean hasMore;
        Result(final List<Gif> gifs, final String cursor, final boolean hasMore) {
            this.gifs = gifs;
            this.cursor = cursor;
            this.hasMore = hasMore;
        }
    }

    private GifJifClient() {}

    public static List<Gif> search(final String query) {
        return searchPage(query, null).gifs;
    }

    /**
     * gti.dev: fetch one page of GIFs. Pass the cursor from a previous result to
     * continue where it left off (infinite scroll). page is a fallback for the
     * providers that only support numeric paging.
     */
    public static Result searchPage(final String query, final String cursor) {
        final String q = URLEncoder.encode(query == null ? "" : query,
                java.nio.charset.StandardCharsets.UTF_8);
        if (q.isEmpty()) return new Result(new ArrayList<Gif>(), null, false);

        Result result = searchGifJifPage(q, cursor);
        if (!result.gifs.isEmpty()) return result;

        Log.w(TAG, "GifJif returned no results, trying GifSnap");
        final int page = (cursor == null || cursor.isEmpty()) ? 1
                : safeParseInt(cursor);
        result = searchGifSnapPage(q, page);
        if (!result.gifs.isEmpty()) return result;

        Log.w(TAG, "GifSnap returned no results, trying urGIF");
        return searchUrGifPage(q, page);
    }

    private static int safeParseInt(final String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 1; }
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

    private static Result searchGifJifPage(final String q, final String cursor) {
        final List<Gif> result = new ArrayList<>();
        String url = "https://gifjif.com/api/search?q=" + q + "&limit=16";
        if (cursor != null && !cursor.isEmpty()) {
            url += "&cursor=" + URLEncoder.encode(cursor,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        final String body = fetch(url);
        if (body == null) return new Result(result, null, false);
        try {
            final JSONObject root = new JSONObject(body);
            final JSONArray items = root.optJSONArray("items");
            if (items == null) return new Result(result, null, false);
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final JSONObject media = item.optJSONObject("media");
                if (media == null) continue;
                String preview = firstNonEmpty(media, "tinygif", "tinywebp", "webp", "poster");
                String full = firstNonEmpty(media, "gif", "mp4", "webp");
                if (preview.isEmpty() || full.isEmpty()) continue;
                result.add(new Gif(preview, full, item.optString("title", "")));
            }
            final String nextCursor = root.optString("cursor", null);
            final boolean hasMore = root.optBoolean("hasMore", false);
            return new Result(result, nextCursor, hasMore);
        } catch (Exception e) {
            Log.w(TAG, "GifJif parse failed: " + e.getMessage());
        }
        return new Result(result, null, false);
    }

    private static Result searchGifSnapPage(final String q, final int page) {
        final List<Gif> result = new ArrayList<>();
        final String body = fetch("https://gifsnap.com/api/v1/gifs/search?q=" + q
                + "&page=" + page + "&limit=16");
        if (body == null) return new Result(result, null, false);
        try {
            final JSONObject root = new JSONObject(body);
            final JSONArray items = root.optJSONArray("data");
            if (items == null) return new Result(result, null, false);
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final String preview = item.optString("preview_url", "");
                final String full = item.optString("url", "");
                if (preview.isEmpty() || full.isEmpty()) continue;
                result.add(new Gif(preview, full, item.optString("title", "")));
            }
            // GifSnap uses numeric pages; encode the next page as the cursor.
            final boolean hasMore = items.length() >= 16;
            return new Result(result, hasMore ? String.valueOf(page + 1) : null, hasMore);
        } catch (Exception e) {
            Log.w(TAG, "GifSnap parse failed: " + e.getMessage());
        }
        return new Result(result, null, false);
    }

    private static Result searchUrGifPage(final String q, final int page) {
        final List<Gif> result = new ArrayList<>();
        final int offset = (page - 1) * 16;
        final String body = fetch("https://api.urgif.com/v1/search?q=" + q
                + "&limit=16&offset=" + offset);
        if (body == null) return new Result(result, null, false);
        try {
            final JSONObject root = new JSONObject(body);
            final JSONArray items = root.optJSONArray("data");
            if (items == null) return new Result(result, null, false);
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final String full = item.optString("url", "");
                if (full.isEmpty()) continue;
                String preview = item.optString("preview", "");
                if (preview.isEmpty()) preview = full;
                result.add(new Gif(preview, full, ""));
            }
            final String nextOffset = root.optString("next_offset", "");
            final boolean hasMore = !nextOffset.isEmpty() && !"null".equals(nextOffset)
                    || items.length() >= 16;
            return new Result(result, hasMore ? String.valueOf(page + 1) : null, hasMore);
        } catch (Exception e) {
            Log.w(TAG, "urGIF parse failed: " + e.getMessage());
        }
        return new Result(result, null, false);
    }

    private static String firstNonEmpty(final JSONObject o, final String... keys) {
        for (final String key : keys) {
            final String v = o.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
