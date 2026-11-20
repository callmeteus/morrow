/*
 * gti.dev: Gboard-style "Área de transferência" (clipboard history) panel.
 * Renders locally persisted clipboard items as cards; tapping a card commits
 * its text to the focused editor. All data stays on the device.
 */

package com.android.inputmethod.latin.clipboard;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.inputmethod.keyboard.KeyboardActionListener;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.common.Constants;

import java.util.List;

public final class ClipboardPaletteView extends LinearLayout implements View.OnClickListener {
    private final ClipboardHistory mHistory;
    private LinearLayout mItemsContainer;
    private KeyboardActionListener mKeyboardActionListener =
            KeyboardActionListener.EMPTY_LISTENER;

    public ClipboardPaletteView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mHistory = new ClipboardHistory(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // gti.dev: this view is the root of clipboard_palette_view.xml, so its children
        // are already inflated here. Inflating the layout in the constructor would recurse.
        mItemsContainer = findViewById(R.id.clipboard_items_container);
        findViewById(R.id.clipboard_back_key).setOnClickListener(this);
        findViewById(R.id.clipboard_clear_key).setOnClickListener(this);
        findViewById(R.id.clipboard_alphabet_key).setOnClickListener(this);
        findViewById(R.id.clipboard_delete_key).setOnClickListener(this);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    /** gti.dev: rebuild the card list from the persisted history. */
    public void showClipboardHistory() {
        mItemsContainer.removeAllViews();
        final List<String> items = mHistory.getItems();
        if (items.isEmpty()) {
            final TextView empty = new TextView(getContext());
            empty.setText(R.string.clipboard_panel_empty);
            empty.setTextSize(15f);
            empty.setPadding(8, 24, 8, 24);
            mItemsContainer.addView(empty);
            return;
        }
        for (final String item : items) {
            final TextView card = new TextView(getContext());
            card.setText(item);
            card.setTextSize(15f);
            card.setTypeface(Typeface.DEFAULT);
            card.setMaxLines(3);
            card.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.setPadding(20, 20, 20, 20);
            card.setBackgroundResource(R.drawable.clipboard_history_card);
            card.setTag(item);
            card.setOnClickListener(this);
            mItemsContainer.addView(card);
        }
    }

    @Override
    public void onClick(final View v) {
        final int id = v.getId();
        if (id == R.id.clipboard_back_key || id == R.id.clipboard_alphabet_key) {
            mKeyboardActionListener.onCodeInput(Constants.CODE_ALPHA_FROM_EMOJI,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        } else if (id == R.id.clipboard_delete_key) {
            mKeyboardActionListener.onCodeInput(Constants.CODE_DELETE,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        } else if (id == R.id.clipboard_clear_key) {
            mHistory.clear();
            showClipboardHistory();
        } else if (v.getTag() instanceof String) {
            final String text = (String) v.getTag();
            mKeyboardActionListener.onTextInput(text);
            // gti.dev: Gboard returns to the alphabet keyboard after pasting.
            mKeyboardActionListener.onCodeInput(Constants.CODE_ALPHA_FROM_EMOJI,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        }
    }
}
