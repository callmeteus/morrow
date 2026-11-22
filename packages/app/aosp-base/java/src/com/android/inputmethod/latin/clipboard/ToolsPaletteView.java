/*
 * gti.dev: Gboard-style tools menu, opened by the grid button in the toolbar.
 * Renders rounded two-column pills for emoji, one-handed, translate, clipboard,
 * settings and floating. Shortcuts open their matching panel.
 */

package com.android.inputmethod.latin.clipboard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
        // gti.dev: items and order match the Gboard reference (ref_65).
        addTool(grid, R.string.tools_item_emoji, R.drawable.ic_emoji_smiley,
                Constants.CODE_EMOJI, true);
        addTool(grid, R.string.tools_item_one_handed, R.drawable.ic_floating_switch_ime,
                Constants.CODE_ONE_HANDED, false);
        addTool(grid, R.string.tools_item_text_mode, R.drawable.ic_tools_edit,
                Constants.CODE_SETTINGS, true);
        addTool(grid, R.string.tools_item_share, R.drawable.ic_share,
                Constants.CODE_SHARE, false);
        addTool(grid, R.string.tools_item_resize, R.drawable.ic_floating_resize,
                Constants.CODE_RESIZE, false);
        addTool(grid, R.string.tools_item_floating, R.drawable.ic_floating_keyboard,
                Constants.CODE_ONE_HANDED, false);
        addTool(grid, R.string.tools_item_keep, R.drawable.ic_keep,
                Constants.CODE_KEEP, false);
        findViewById(R.id.tools_back_key).setOnClickListener(this);
        findViewById(R.id.tools_edit_key).setOnClickListener(this);
    }

    private void addTool(final GridLayout grid, final int labelRes, final int iconRes,
            final int code, final boolean showChevron) {
        final LinearLayout pill = new LinearLayout(getContext());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClickable(true);
        pill.setOnClickListener(this);
        pill.setTag(code);
        pill.setBackgroundResource(R.drawable.tools_pill_bg);
        pill.setPadding(dpToPx(12), dpToPx(10), dpToPx(10), dpToPx(10));

        final int index = grid.getChildCount();
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(index / 2, 1f),
                GridLayout.spec(index % 2, 1f));
        params.width = 0;
        params.height = dpToPx(56);
        params.setMargins(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6));
        pill.setLayoutParams(params);

        final ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconRes);
        icon.setClickable(false);
        icon.setFocusable(false);
        final LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
        icon.setLayoutParams(iconParams);
        pill.addView(icon);

        final TextView label = new TextView(getContext());
        label.setText(labelRes);
        label.setTextColor(0xFFE8EAED);
        label.setTextSize(13f);
        label.setSingleLine(true);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMargins(dpToPx(8), 0, dpToPx(4), 0);
        label.setLayoutParams(labelParams);
        pill.addView(label);

        if (showChevron) {
            final ImageView chevron = new ImageView(getContext());
            chevron.setImageResource(R.drawable.ic_tools_chevron);
            chevron.setClickable(false);
            chevron.setFocusable(false);
            final LinearLayout.LayoutParams chevronParams =
                    new LinearLayout.LayoutParams(dpToPx(20), dpToPx(20));
            chevron.setLayoutParams(chevronParams);
            pill.addView(chevron);
        }
        grid.addView(pill);
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
        } else if (v.getId() == R.id.tools_edit_key) {
            mKeyboardActionListener.onCodeInput(Constants.CODE_SETTINGS,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        } else if (v.getTag() instanceof Integer) {
            mKeyboardActionListener.onCodeInput((Integer) v.getTag(),
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        }
    }
}
