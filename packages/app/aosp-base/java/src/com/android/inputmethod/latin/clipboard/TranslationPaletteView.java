/*
 * gti.dev: Gboard-style "Traduzir" (translation) panel.
 * Shows the source/target language pills and a local input field. Translation
 * uses a keyless public endpoint; failures show a short message in the result.
 */

package com.android.inputmethod.latin.clipboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;

import com.android.inputmethod.keyboard.KeyboardActionListener;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.common.Constants;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TranslationPaletteView extends LinearLayout implements View.OnClickListener {
    private KeyboardActionListener mKeyboardActionListener =
            KeyboardActionListener.EMPTY_LISTENER;
    private EditText mInput;
    private TextView mResult;
    private TextView mSourceLang;
    private TextView mTargetLang;
    private boolean mSourceIsDetect = true;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public TranslationPaletteView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mInput = findViewById(R.id.translation_input);
        mResult = findViewById(R.id.translation_result);
        mSourceLang = findViewById(R.id.translation_source_key);
        mTargetLang = findViewById(R.id.translation_target_key);
        findViewById(R.id.translation_back_key).setOnClickListener(this);
        findViewById(R.id.translation_swap_key).setOnClickListener(this);
        findViewById(R.id.translation_go_key).setOnClickListener(this);
        mSourceLang.setOnClickListener(this);
        mTargetLang.setOnClickListener(this);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    public void show() {
        mInput.setText("");
        if (mResult != null) mResult.setText("");
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
        } else if (id == R.id.translation_go_key) {
            doTranslate();
        }
    }

    private void doTranslate() {
        final String text = mInput.getText().toString();
        if (text.trim().isEmpty()) return;
        final String from = mSourceIsDetect ? "pt" : "pt";
        final String to = "en";
        mResult.setText(R.string.gif_searching);
        mExecutor.execute(() -> {
            final String translated = TranslationClient.translate(text, from, to);
            mMain.post(() -> mResult.setText(translated != null ? translated
                    : getResources().getString(R.string.translation_error)));
        });
    }
}
