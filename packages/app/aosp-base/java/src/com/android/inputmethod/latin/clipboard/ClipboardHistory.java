/*
 * gti.dev: local clipboard history for the Gboard-style clipboard panel.
 * Items are stored as plain text in SharedPreferences, kept on the device only.
 */

package com.android.inputmethod.latin.clipboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nonnull;

/** gti.dev: persists a small, most-recent-first clipboard text history. */
public final class ClipboardHistory {
    private static final String PREF_NAME = "clipboard_history";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 20;
    private static final String SEPARATOR = "\u0000";

    private final SharedPreferences mPrefs;

    public ClipboardHistory(@Nonnull final Context context) {
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @Nonnull
    public List<String> getItems() {
        final String raw = mPrefs.getString(KEY_ITEMS, "");
        if (raw.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(raw.split(SEPARATOR, -1)));
    }

    /** gti.dev: add text to the front, de-duplicated and capped. */
    public void add(@Nonnull final String text) {
        if (text.isEmpty()) {
            return;
        }
        final List<String> items = getItems();
        items.remove(text);
        items.add(0, text);
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(SEPARATOR);
            }
            // gti.dev: strip the separator from user text so items stay intact.
            sb.append(items.get(i).replace(SEPARATOR, " "));
        }
        mPrefs.edit().putString(KEY_ITEMS, sb.toString()).apply();
    }

    public void clear() {
        mPrefs.edit().remove(KEY_ITEMS).apply();
    }
}
