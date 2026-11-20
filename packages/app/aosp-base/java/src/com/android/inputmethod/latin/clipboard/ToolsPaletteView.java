/*
 * gti.dev: Gboard-style tools grid, opened by the grid button in the toolbar.
 * Shows shortcuts to the translation panel, clipboard, emoji and settings.
 */

package com.android.inputmethod.latin.clipboard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.inputmethod.keyboard.KeyboardActionListener;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.common.Constants;

public final class ToolsPaletteView extends LinearLayout implements View.OnClickListener {
    private KeyboardActionListener mKeyboardActionListener =
            KeyboardActionListener.EMPTY_LISTENER;

    public ToolsPaletteView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        final GridLayout grid = findViewById(R.id.tools_grid);
        addTool(grid, R.string.tools_item_translate, R.drawable.ic_translate,
                Constants.CODE_TRANSLATE);
        addTool(grid, R.string.tools_item_clipboard, R.drawable.ic_clipboard,
                Constants.CODE_CLIPBOARD_PASTE);
        addTool(grid, R.string.tools_item_emoji, R.drawable.ic_emoji_smiley, Constants.CODE_EMOJI);
        addTool(grid, R.string.tools_item_settings, R.drawable.ic_settings,
                Constants.CODE_SETTINGS);
        findViewById(R.id.tools_back_key).setOnClickListener(this);
    }

    private void addTool(final GridLayout grid, final int labelRes, final int iconRes,
            final int code) {
        final LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setClickable(true);
        cell.setOnClickListener(this);
        cell.setTag(code);
        final int size = dpToPx(72);
        final int index = grid.getChildCount();
        cell.setLayoutParams(new GridLayout.LayoutParams(
                GridLayout.spec(index / 3),
                GridLayout.spec(index % 3)));
        cell.setMinimumWidth(size);

        final ImageButton icon = new ImageButton(getContext());
        icon.setImageResource(iconRes);
        icon.setBackground(null);
        icon.setClickable(false);
        icon.setFocusable(false);
        final LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);

        final TextView label = new TextView(getContext());
        label.setText(labelRes);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        label.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.gravity = Gravity.CENTER;
        label.setLayoutParams(labelParams);

        cell.addView(icon);
        cell.addView(label);
        grid.addView(cell);
    }

    private int dpToPx(final int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    @Override
    public void onClick(final View v) {
        if (v.getId() == R.id.tools_back_key) {
            mKeyboardActionListener.onCodeInput(Constants.CODE_ALPHA_FROM_EMOJI,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        } else if (v.getTag() instanceof Integer) {
            mKeyboardActionListener.onCodeInput((Integer) v.getTag(),
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        }
    }
}
