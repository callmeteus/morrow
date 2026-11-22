package com.android.inputmethod.latin;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the debug dataset to the remote collector. Every entry point is a
 * no-op in release builds (no INTERNET permission there) and when collection
 * is disabled in the setup dialog. Failures are logged and dropped: uploads
 * are fire-and-forget so the keyboard never blocks on the network.
 */
public final class CollectorClient {
    private static final String TAG = "CollectorClient";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dataset-upload");
        t.setDaemon(true);
        return t;
    });

    private static volatile Context appContext;

    private CollectorClient() {}

    /** Call once from the IME/application onCreate with the application context. */
    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /** The collector endpoint. Fixed by design: debug-only dataset intake. */
    private static final String BASE_URL = "https://morrow.gti.dev";

    private static final class Config {
        final String baseUrl;
        final String token;

        Config(String baseUrl, String token) {
            this.baseUrl = baseUrl;
            this.token = token;
        }
    }

    private static boolean enabled() {
        // Debug-only collection; release builds must not upload keyboard data.
        return BuildConfig.DEBUG && appContext != null;
    }

    private static Config enabledConfig() {
        return enabled() ? new Config(BASE_URL, "") : null;
    }

    /** Upload the dictation WAV plus the three text stages and timing metadata. */
    public static void uploadVoice(final String voiceId, final short[] pcm, final String raw,
                                   final String clean, final String corrected, final long inferMs,
                                   final float rms, final long durationMs, final String model,
                                   final String locale, final String sessionId) {
        final Config cfg = enabledConfig();
        if (cfg == null) return;
        EXEC.submit(() -> {
            File wav = null;
            try {
                String id = (voiceId == null || voiceId.isEmpty())
                        ? UUID.randomUUID().toString() : voiceId;
                wav = File.createTempFile("dictation-" + id + "-", ".wav",
                        appContext.getCacheDir());
                VoiceBackend.writeWav(wav, pcm, pcm.length);

                JSONObject meta = new JSONObject();
                meta.put("id", id);
                meta.put("sessionId", sessionId == null ? "" : sessionId);
                meta.put("ts", System.currentTimeMillis());
                meta.put("transcriptRaw", nullToEmpty(raw));
                meta.put("transcriptCleaned", nullToEmpty(clean));
                meta.put("transcriptCorrected", nullToEmpty(corrected));
                meta.put("inferMs", inferMs);
                meta.put("rms", rms);
                meta.put("durationMs", durationMs);
                meta.put("model", model);
                meta.put("locale", locale);

                postMultipart(cfg, "/v1/voice", "meta", meta.toString(), "audio", wav);
            } catch (Exception e) {
                Log.w(TAG, "voice upload failed: " + e.getMessage());
            } finally {
                if (wav != null) {
                    //noinspection ResultOfMethodCallIgnored
                    wav.delete();
                }
            }
        });
    }

    /** Post a JSON body to a signal endpoint (for example /v1/swipe). */
    public static void postJson(final String path, final JSONObject payload) {
        final Config cfg = enabledConfig();
        if (cfg == null) return;
        EXEC.submit(() -> {
            try {
                postJsonBlocking(cfg, path, payload);
            } catch (Exception e) {
                Log.w(TAG, "post " + path + " failed: " + e.getMessage());
            }
        });
    }

    /** Post a swipe gesture. The id, session and timestamp are built here so
     *  the caller only supplies the gesture data. Returns the generated id so
     *  the IME can correlate a later rewrite of the word. */
    public static String postSwipe(final String sessionId, final String trail,
                                   final String decoded, final java.util.List<String> candidates,
                                   final String kb, final java.util.List<float[]> points) {
        final Config cfg = enabledConfig();
        if (cfg == null) return null;
        final String id = newId();
        EXEC.submit(() -> {
            try {
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("id", id);
                payload.put("sessionId", sessionId == null ? "" : sessionId);
                payload.put("ts", System.currentTimeMillis());
                payload.put("trail", trail == null ? "" : trail);
                payload.put("decoded", decoded == null ? "" : decoded);
                payload.put("candidates", toArray(candidates));
                payload.put("kb", kb == null ? "0x0" : kb);
                org.json.JSONArray pts = new org.json.JSONArray();
                if (points != null) {
                    for (float[] p : points) {
                        org.json.JSONArray one = new org.json.JSONArray();
                        one.put(p[0]); one.put(p[1]); one.put(p[2]);
                        pts.put(one);
                    }
                }
                payload.put("points", pts);
                postJsonBlocking(cfg, "/v1/swipe", payload);
            } catch (Exception e) {
                Log.w(TAG, "swipe upload failed: " + e.getMessage());
            }
        });
        return id;
    }

    /** Post a corrector event assembled by CorrectionTracker. */
    public static void postCorrection(final CorrectionTracker.Event event) {
        final Config cfg = enabledConfig();
        if (cfg == null || event == null) return;
        EXEC.submit(() -> {
            try {
                postJsonBlocking(cfg, "/v1/correction", toJson(event.toFields()));
            } catch (Exception e) {
                Log.w(TAG, "correction upload failed: " + e.getMessage());
            }
        });
    }

    private static JSONObject toJson(java.util.Map<String, Object> fields) {
        JSONObject obj = new JSONObject();
        for (java.util.Map.Entry<String, Object> e : fields.entrySet()) {
            try {
                obj.put(e.getKey(), e.getValue());
            } catch (JSONException ignored) {
                // Unsupported value type: skip rather than lose the whole event.
            }
        }
        return obj;
    }

    // --- blocking implementation, always on the upload executor -------------

    private static void postJsonBlocking(Config cfg, String path, JSONObject payload) throws IOException {
        HttpsURLConnection conn = open(cfg, path);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            logStatus(path, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    private static void postMultipart(Config cfg, String path, String jsonField, String jsonValue,
                                      String fileField, File file) throws IOException {
        String boundary = "----gboard" + UUID.randomUUID().toString().replace("-", "");
        HttpsURLConnection conn = open(cfg, path);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"" + jsonField + "\"\r\n");
            out.writeBytes("Content-Type: application/json; charset=utf-8\r\n\r\n");
            out.write(jsonValue.getBytes(StandardCharsets.UTF_8));
            out.writeBytes("\r\n");
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + file.getName() + "\"\r\n");
            out.writeBytes("Content-Type: audio/wav\r\n\r\n");
            java.nio.file.Files.copy(file.toPath(), out);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();

            byte[] body = buffer.toByteArray();
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            logStatus(path, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    private static HttpsURLConnection open(Config cfg, String path) throws IOException {
        String base = cfg.baseUrl.endsWith("/")
                ? cfg.baseUrl.substring(0, cfg.baseUrl.length() - 1) : cfg.baseUrl;
        URL url = new URL(base + path);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        if (cfg.token != null && !cfg.token.isEmpty()) {
            conn.setRequestProperty("X-Collector-Token", cfg.token);
        }
        return conn;
    }

    private static void logStatus(String path, int code) {
        if (code >= 200 && code < 300) {
            Log.i(TAG, "uploaded " + path + " -> " + code);
        } else {
            Log.w(TAG, "upload " + path + " returned " + code);
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** Build the id for an outgoing record. */
    public static String newId() { return UUID.randomUUID().toString(); }

    /** Serialize a string list to a JSON array. */
    private static org.json.JSONArray toArray(List<String> values) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (values != null) {
            for (String v : values) arr.put(v);
        }
        return arr;
    }
}
