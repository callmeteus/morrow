/*
 * gti.dev: Gboard-style "Traduzir" (translation) panel.
 * Shows the source/target language pills and a local input field. Translation
 * is intentionally a local UI shell matching Gboard's layout; no network call.
 */

package com.android.inputmethod.latin.clipboard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.inputmethod.keyboard.KeyboardActionListener;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.common.Constants;

public final class TranslationPaletteView extends LinearLayout implements View.OnClickListener {
    private KeyboardActionListener mKeyboardActionListener =
            KeyboardActionListener.EMPTY_LISTENER;
    private EditText mInput;
    private TextView mSourceLang;
    private TextView mTargetLang;
    private boolean mSourceIsDetect = true;

    public TranslationPaletteView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mInput = findViewById(R.id.translation_input);
        mSourceLang = findViewById(R.id.translation_source_key);
        mTargetLang = findViewById(R.id.translation_target_key);
        findViewById(R.id.translation_back_key).setOnClickListener(this);
        findViewById(R.id.translation_swap_key).setOnClickListener(this);
        mSourceLang.setOnClickListener(this);
        mTargetLang.setOnClickListener(this);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    public void show() {
        mInput.setText("");
        mInput.requestFocus();
    }

    @Override
    public void onClick(final View v) {
        final int id = v.getId();
        if (id == R.id.translation_back_key) {
            mKeyboardActionListener.onCodeInput(Constants.CODE_ALPHA_FROM_EMOJI,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        } else if (id == R.id.translation_swap_key) {
            if (mSourceIsDetect) {
                mSourceLang.setText(R.string.translation_lang_portuguese);
                mTargetLang.setText(R.string.translate_english);
                mSourceIsDetect = false;
            } else {
                mSourceLang.setText(R.string.translate_detect_language);
                mTargetLang.setText(R.string.translate_english);
                mSourceIsDetect = true;
            }
        } else if (id == R.id.translation_source_key || id == R.id.translation_target_key) {
            // gti.dev: language selection is a no-op shell for now.
        }
    }
}
