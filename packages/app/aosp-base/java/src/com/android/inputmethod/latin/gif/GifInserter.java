/*
 * gti.dev: downloads a GIF to the app cache and commits it to the focused editor
 * as rich content (image/gif) through InputConnection.commitContent.
 */

package com.android.inputmethod.latin.gif;

import android.content.Context;
import android.content.res.Resources;
import android.content.ClipDescription;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import android.view.inputmethod.InputConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class GifInserter {
    private static final String TAG = "GifInserter";

    private GifInserter() {}

    /** Result callback: true if rich GIF was inserted, false if caller should fall back to URL. */
    public interface Callback {
        void onResult(boolean inserted);
    }

    public static void insert(final Context context, final InputConnection ic,
            final android.view.inputmethod.EditorInfo editorInfo,
            final String gifUrl, final Callback callback) {
        new Thread(() -> {
            File gifFile = null;
            try {
                final File dir = new File(context.getCacheDir(), "gifs");
                if (!dir.exists()) dir.mkdirs();
                gifFile = new File(dir, "gif_" + System.currentTimeMillis() + ".gif");
                final HttpURLConnection conn = (HttpURLConnection) new URL(gifUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "MorrowKeyboard/1.0");
                boolean ok = false;
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(gifFile)) {
                        final byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        ok = gifFile.length() > 0;
                    }
                }
                conn.disconnect();
                if (!ok) throw new java.io.IOException("download failed");

                final Uri contentUri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".giffileprovider", gifFile);
                final ClipDescription desc = new ClipDescription("GIF",
                        new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN});
                // Android's commitContent uses InputContentInfo with image/gif MIME;
                // InputContentInfoCompat derives supported formats from ClipDescription.
                final InputContentInfoCompat info = new InputContentInfoCompat(
                        contentUri, new ClipDescription("GIF", new String[]{"image/gif"}),
                        null /* linkUri */);

                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    boolean inserted = false;
                    try {
                        inserted = InputConnectionCompat.commitContent(ic, editorInfo, info,
                                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                                Bundle.EMPTY);
                    } catch (Exception e) {
                        Log.w(TAG, "commitContent failed: " + e.getMessage());
                    }
                    callback.onResult(inserted);
                });
            } catch (Exception e) {
                Log.w(TAG, "GIF download failed: " + e.getMessage());
                if (gifFile != null) gifFile.delete();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.onResult(false));
            }
        }, "gif-insert").start();
    }
}
