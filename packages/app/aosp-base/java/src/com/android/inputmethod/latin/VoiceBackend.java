/*
 * Copyright (C) 2026 gti.dev
 * Licensed under the Apache License, Version 2.0.
 *
 * Optional OpenAI-compatible speech-to-text backend. The keyboard is
 * offline-first: by default every dictation is transcribed on-device by
 * LocalVoiceInput (whisper.cpp). If the user configures an external server,
 * audio is sent to POST {baseUrl}/audio/transcriptions using the standard
 * multipart form ("file", "model", "language") and the JSON "text" field is
 * returned. Works with OpenAI, localai, faster-whisper-server,
 * whisper.cpp server (with the OpenAI-compatible adapter), Speaches, etc.
 */
package com.android.inputmethod.latin;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Manages the optional external STT backend configuration and HTTP call. */
public final class VoiceBackend {
    private static final String TAG = "VoiceBackend";

    // Preference keys (kept in the IME's default SharedPreferences).
    public static final String PREF_USE_EXTERNAL = "gti_voice_use_external";
    public static final String PREF_BASE_URL = "gti_voice_base_url";
    public static final String PREF_API_KEY = "gti_voice_api_key";
    public static final String PREF_MODEL = "gti_voice_model";
    public static final String PREF_LANGUAGE = "gti_voice_language";

    public static final String DEFAULT_BASE_URL = "http://100.84.51.1:8000/v1";
    public static final String DEFAULT_MODEL = "whisper-1";
    public static final String DEFAULT_LANGUAGE = "pt";

    private VoiceBackend() {}

    // Read config from the setup app's content provider (different package).
    private static final Uri CONFIG_URI = Uri.parse("content://dev.gti.gboard.voiceconfig/config");

    private static Map<String, String> readConfig(Context c) {
        Map<String, String> m = new HashMap<>();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(CONFIG_URI, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String[] cols = cur.getColumnNames();
                for (String col : cols) {
                    int i = cur.getColumnIndex(col);
                    if (i >= 0) m.put(col, cur.getString(i));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "readConfig: " + e.getMessage());
        } finally {
            if (cur != null) cur.close();
        }
        return m;
    }

    public static boolean isExternalEnabled(Context c) {
        return "1".equals(readConfig(c).get(PREF_USE_EXTERNAL));
    }

    // configure() is unused by the IME; the setup app owns the preferences.

    private static String normalizedBase(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.isEmpty()) b = DEFAULT_BASE_URL;
        return b;
    }

    /**
     * Transcribes PCM16 16 kHz audio via an OpenAI-compatible endpoint.
     * Returns the transcript, or throws on network/protocol errors so the
     * caller can fall back to the local engine.
     */
    public static String transcribe(Context c, short[] pcm, int len) throws Exception {
        Map<String, String> p = readConfig(c);
        String base = normalizedBase(p.get(PREF_BASE_URL) == null ? DEFAULT_BASE_URL : p.get(PREF_BASE_URL));
        String apiKey = p.get(PREF_API_KEY) == null ? "" : p.get(PREF_API_KEY);
        String model = p.get(PREF_MODEL);
        String lang = p.get(PREF_LANGUAGE);
        if (model == null || model.isEmpty()) model = DEFAULT_MODEL;
        if (lang == null || lang.isEmpty()) lang = DEFAULT_LANGUAGE;
        String urlStr = base + "/audio/transcriptions";

        // Write a temporary 16 kHz mono WAV (multipart "file").
        File wav = File.createTempFile("gti_stt_" + UUID.randomUUID(), ".wav", c.getCacheDir());
        writeWav(wav, pcm, len);
        HttpURLConnection conn = null;
        try {
            String boundary = "----gti" + UUID.randomUUID().toString().replace("-", "");
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            addField(out, boundary, "model", model);
            addField(out, boundary, "language", lang);
            addField(out, boundary, "response_format", "json");
            addFile(out, boundary, "file", wav);
            out.writeBytes("--" + boundary + "--\r\n");
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + ": " + body.substring(0, Math.min(300, body.length())));
            }
            return extractJsonText(body);
        } finally {
            if (conn != null) conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            wav.delete();
        }
    }

    private static void addField(DataOutputStream out, String boundary, String name, String value)
            throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static void addFile(DataOutputStream out, String boundary, String name, File file)
            throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + file.getName() + "\"\r\n");
        out.writeBytes("Content-Type: audio/wav\r\n\r\n");
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.writeBytes("\r\n");
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    /** Minimal JSON "text" extractor (avoids a dependency on org.json here). */
    static String extractJsonText(String json) {
        int i = json.indexOf("\"text\"");
        if (i < 0) return "";
        i = json.indexOf(':', i + 6);
        if (i < 0) return "";
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char ch = json.charAt(i++);
            if (ch == '"') break;
            if (ch == '\\' && i < json.length()) {
                char e = json.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(e); break;
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString().trim();
    }

    /** Writes a minimal 16-bit PCM WAV header followed by the samples. */
    static void writeWav(File out, short[] pcm, int len) throws Exception {
        int dataLen = len * 2;
        FileOutputStream fo = new FileOutputStream(out);
        fo.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeIntLE(fo, 36 + dataLen);
        fo.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        fo.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeIntLE(fo, 16);            // PCM header size
        writeShortLE(fo, 1);           // PCM
        writeShortLE(fo, 1);           // mono
        writeIntLE(fo, 16000);         // sample rate
        writeIntLE(fo, 16000 * 2);     // byte rate
        writeShortLE(fo, 2);           // block align
        writeShortLE(fo, 16);          // bits per sample
        fo.write("data".getBytes(StandardCharsets.US_ASCII));
        writeIntLE(fo, dataLen);
        for (int i = 0; i < len; i++) writeShortLE(fo, pcm[i]);
        fo.close();
    }

    private static void writeIntLE(FileOutputStream fo, int v) throws Exception {
        fo.write(v & 0xFF);
        fo.write((v >> 8) & 0xFF);
        fo.write((v >> 16) & 0xFF);
        fo.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(FileOutputStream fo, int v) throws Exception {
        fo.write(v & 0xFF);
        fo.write((v >> 8) & 0xFF);
    }
}
