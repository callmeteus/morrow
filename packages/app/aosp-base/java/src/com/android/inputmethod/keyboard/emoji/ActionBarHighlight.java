/*
 * gti.dev: highlights the active tab in the shared emoji/GIF action bar.
 */

package com.android.inputmethod.keyboard.emoji;

import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import com.android.inputmethod.latin.R;

public final class ActionBarHighlight {
    private ActionBarHighlight() {}

    public static void setActive(final View bar, final int activeTabId) {
        if (bar == null) return;
        final TextView gif = bar.findViewById(R.id.emoji_keyboard_gif);
        final TextView kaomoji = bar.findViewById(R.id.emoji_keyboard_kaomoji);
        if (gif != null) {
            final boolean active = activeTabId == R.id.emoji_keyboard_gif;
            gif.setBackgroundResource(active ? R.drawable.tools_pill_active_bg
                    : R.drawable.tools_pill_bg);
            gif.setTextColor(active ? Color.parseColor("#FF202124")
                    : Color.parseColor("#FFE8EAED"));
        }
        if (kaomoji != null) {
            final boolean active = activeTabId == R.id.emoji_keyboard_kaomoji;
            kaomoji.setBackgroundResource(active ? R.drawable.tools_pill_active_bg
                    : R.drawable.tools_pill_bg);
            kaomoji.setTextColor(active ? Color.parseColor("#FF202124")
                    : Color.parseColor("#FFE8EAED"));
        }
    }
}
