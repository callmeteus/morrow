/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin.suggestions;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.core.view.ViewCompat;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.inputmethod.accessibility.AccessibilityUtils;
import com.android.inputmethod.keyboard.Keyboard;
import com.android.inputmethod.keyboard.MainKeyboardView;
import com.android.inputmethod.keyboard.MoreKeysPanel;
import com.android.inputmethod.latin.AudioAndHapticFeedbackManager;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.SuggestedWords;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.android.inputmethod.latin.clipboard.ClipboardSuggestion;
import com.android.inputmethod.latin.common.Constants;
import com.android.inputmethod.latin.define.DebugFlags;
import com.android.inputmethod.latin.settings.Settings;
import com.android.inputmethod.latin.settings.SettingsValues;
import com.android.inputmethod.latin.suggestions.MoreSuggestionsView.MoreSuggestionsListener;
import com.android.inputmethod.latin.utils.ImportantNoticeUtils;
import com.android.inputmethod.latin.utils.SplitKeyboardUtils;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SuggestionStripView extends RelativeLayout implements OnClickListener,
        OnLongClickListener {
    public interface Listener {
        public void showImportantNoticeContents();
        public void pickSuggestionManually(SuggestedWordInfo word);
        public void onCodeInput(int primaryCode, int x, int y, boolean isKeyRepeat);
        public void toggleSplitKeyboard();
        public void toggleFloatingKeyboard();
        public void pickClipboardSuggestion(ClipboardSuggestion suggestion,
                ClipboardSuggestion.Match match);
    }

    static final boolean DBG = DebugFlags.DEBUG_ENABLED;
    private static final float DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.0f;
    private static final int TOOL_KEY_ALPHA_ON = 255;
    // gti.dev: mic icon swap for on-device dictation feedback.
    private Drawable mVoiceIcon;
    private Drawable mRecordingIcon;
    private static final int TOOL_KEY_ALPHA_OFF = 128;
    private static final String SENSITIVE_CLIPBOARD_LABEL = "\u2022\u2022\u2022\u2022\u2022\u2022";

    private final ViewGroup mSuggestionsStrip;
    // gti.dev: the icon-only toolbar shown when no word is being composed (Gboard style).
    private final View mToolbar;
    private boolean mShowWordSuggestions = false;
    private final ImageButton mVoiceKey;
    // gti.dev: a visible emoji button instead of the hidden comma long-press.
    private final ImageButton mEmojiKey;
    // gti.dev: Gboard-style toolbar buttons.
    private final ImageButton mClipboardKey;
    private final ImageButton mGifKey;
    private final ImageButton mThemeKey;
    private final ImageButton mSettingsKey;
    private final int mDockedStripMargin;
    private final View mImportantNoticeStrip;
    private final InlineSuggestionsStrip mInlineSuggestionsStrip;
    private final ViewGroup mClipboardSuggestionStrip;
    @Nullable
    private ClipboardSuggestion mClipboardSuggestion;
    private boolean mShouldBeVisible = true;
    private boolean mIsFullscreenMode;
    MainKeyboardView mMainKeyboardView;

    private final View mMoreSuggestionsContainer;
    private final MoreSuggestionsView mMoreSuggestionsView;
    private final MoreSuggestions.Builder mMoreSuggestionsBuilder;

    private final ArrayList<TextView> mWordViews = new ArrayList<>();
    private final ArrayList<TextView> mDebugInfoViews = new ArrayList<>();
    private final ArrayList<View> mDividerViews = new ArrayList<>();

    Listener mListener;
    private SuggestedWords mSuggestedWords = SuggestedWords.getEmptyInstance();
    private int mStartIndexOfMoreSuggestions;

    private final SuggestionStripLayoutHelper mLayoutHelper;
    private final StripVisibilityGroup mStripVisibilityGroup;

    private static class StripVisibilityGroup {
        private final View mSuggestionStripView;
        private final View mToolbar;
        private final View mSuggestionsStrip;
        private final View mImportantNoticeStrip;
        private final InlineSuggestionsStrip mInlineSuggestionsStrip;
        private final View mClipboardSuggestionStrip;

        public StripVisibilityGroup(final View suggestionStripView, final View toolbar,
                final ViewGroup suggestionsStrip, final View importantNoticeStrip,
                final InlineSuggestionsStrip inlineSuggestionsStrip,
                final View clipboardSuggestionStrip) {
            mSuggestionStripView = suggestionStripView;
            mToolbar = toolbar;
            mSuggestionsStrip = suggestionsStrip;
            mImportantNoticeStrip = importantNoticeStrip;
            mInlineSuggestionsStrip = inlineSuggestionsStrip;
            mClipboardSuggestionStrip = clipboardSuggestionStrip;
            showToolbar();
        }

        public void setLayoutDirection(final boolean isRtlLanguage) {
            final int layoutDirection = isRtlLanguage ? ViewCompat.LAYOUT_DIRECTION_RTL
                    : ViewCompat.LAYOUT_DIRECTION_LTR;
            ViewCompat.setLayoutDirection(mSuggestionStripView, layoutDirection);
            ViewCompat.setLayoutDirection(mSuggestionsStrip, layoutDirection);
            ViewCompat.setLayoutDirection(mImportantNoticeStrip, layoutDirection);
        }

        // gti.dev: idle state - only the icon toolbar (Gboard has no suggestion
        // row until you actually start typing).
        public void showToolbar() {
            mToolbar.setVisibility(VISIBLE);
            mSuggestionsStrip.setVisibility(INVISIBLE);
            mImportantNoticeStrip.setVisibility(INVISIBLE);
            mInlineSuggestionsStrip.setVisibility(INVISIBLE);
            mClipboardSuggestionStrip.setVisibility(INVISIBLE);
        }

        public void showSuggestionsStrip(final boolean showsClipboardSuggestion) {
            final boolean showsInline = mInlineSuggestionsStrip.hasSuggestions();
            final boolean showsClipboard = !showsInline && showsClipboardSuggestion;
            mToolbar.setVisibility(INVISIBLE);
            mSuggestionsStrip.setVisibility(showsInline || showsClipboard ? INVISIBLE : VISIBLE);
            mImportantNoticeStrip.setVisibility(INVISIBLE);
            mInlineSuggestionsStrip.setVisibility(showsInline ? VISIBLE : INVISIBLE);
            mClipboardSuggestionStrip.setVisibility(showsClipboard ? VISIBLE : INVISIBLE);
        }

        public void showImportantNoticeStrip() {
            mToolbar.setVisibility(INVISIBLE);
            mSuggestionsStrip.setVisibility(INVISIBLE);
            mImportantNoticeStrip.setVisibility(VISIBLE);
            mInlineSuggestionsStrip.setVisibility(INVISIBLE);
            mClipboardSuggestionStrip.setVisibility(INVISIBLE);
        }

        public boolean isShowingImportantNoticeStrip() {
            return mImportantNoticeStrip.getVisibility() == VISIBLE;
        }
    }

    /**
     * Construct a {@link SuggestionStripView} for showing suggestions to be picked by the user.
     * @param context
     * @param attrs
     */
    public SuggestionStripView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.suggestionStripViewStyle);
    }

    public SuggestionStripView(final Context context, final AttributeSet attrs,
            final int defStyle) {
        super(context, attrs, defStyle);

        final LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(R.layout.suggestions_strip, this);

        mSuggestionsStrip = (ViewGroup)findViewById(R.id.suggestions_strip);
        mToolbar = findViewById(R.id.suggestions_strip_toolbar);
        mVoiceKey = (ImageButton)findViewById(R.id.suggestions_strip_voice_key);
        mEmojiKey = (ImageButton)findViewById(R.id.suggestions_strip_emoji_key);
        mClipboardKey = (ImageButton)findViewById(R.id.suggestions_strip_clipboard_key);
        mGifKey = (ImageButton)findViewById(R.id.suggestions_strip_gif_key);
        mThemeKey = (ImageButton)findViewById(R.id.suggestions_strip_theme_key);
        mSettingsKey = (ImageButton)findViewById(R.id.suggestions_strip_settings_key);
        mDockedStripMargin =
                ((MarginLayoutParams) mSuggestionsStrip.getLayoutParams()).getMarginStart();
        mImportantNoticeStrip = findViewById(R.id.important_notice_strip);
        mInlineSuggestionsStrip = findViewById(R.id.inline_suggestions_strip);
        mClipboardSuggestionStrip = findViewById(R.id.clipboard_suggestion_strip);
        mStripVisibilityGroup = new StripVisibilityGroup(this, mToolbar, mSuggestionsStrip,
                mImportantNoticeStrip, mInlineSuggestionsStrip, mClipboardSuggestionStrip);

        for (int pos = 0; pos < SuggestedWords.MAX_SUGGESTIONS; pos++) {
            final TextView word = new TextView(context, null, R.attr.suggestionWordStyle);
            word.setContentDescription(getResources().getString(R.string.spoken_empty_suggestion));
            word.setOnClickListener(this);
            word.setOnLongClickListener(this);
            mWordViews.add(word);
            final View divider = inflater.inflate(R.layout.suggestion_divider, null);
            mDividerViews.add(divider);
            final TextView info = new TextView(context, null, R.attr.suggestionWordStyle);
            info.setTextColor(Color.WHITE);
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP);
            mDebugInfoViews.add(info);
        }

        mLayoutHelper = new SuggestionStripLayoutHelper(
                context, attrs, defStyle, mWordViews, mDividerViews, mDebugInfoViews);

        mMoreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null);
        mMoreSuggestionsView = (MoreSuggestionsView)mMoreSuggestionsContainer
                .findViewById(R.id.more_suggestions_view);
        mMoreSuggestionsBuilder = new MoreSuggestions.Builder(context, mMoreSuggestionsView);

        final Resources res = context.getResources();
        mMoreSuggestionsModalTolerance = res.getDimensionPixelOffset(
                R.dimen.config_more_suggestions_modal_tolerance);
        mMoreSuggestionsSlidingDetector = new GestureDetector(
                context, mMoreSuggestionsSlidingListener);

        final TypedArray keyboardAttr = context.obtainStyledAttributes(attrs,
                R.styleable.Keyboard, defStyle, R.style.SuggestionStripView);
        final Drawable iconVoice = keyboardAttr.getDrawable(R.styleable.Keyboard_iconShortcutKey);
        keyboardAttr.recycle();
        // gti.dev: keep both icons so the mic can switch to a "recording" icon.
        mVoiceIcon = iconVoice;
        mRecordingIcon = androidx.core.content.ContextCompat.getDrawable(
                context, R.drawable.sym_keyboard_voice_recording);
        mVoiceKey.setImageDrawable(iconVoice);
        mVoiceKey.setOnClickListener(this);
        mEmojiKey.setOnClickListener(this);
        for (final ImageButton key : new ImageButton[] {
                mClipboardKey, mGifKey, mThemeKey, mSettingsKey }) {
            key.setImageTintList(getToolKeyTintList());
            key.setOnClickListener(this);
        }
    }

    /**
     * A connection back to the input method.
     * @param listener
     */
    public void setListener(final Listener listener, final View inputView) {
        mListener = listener;
        mMainKeyboardView = (MainKeyboardView)inputView.findViewById(R.id.keyboard_view);
    }

    public void updateVisibility(final boolean shouldBeVisible, final boolean isFullscreenMode) {
        mShouldBeVisible = shouldBeVisible;
        mIsFullscreenMode = isFullscreenMode;
        final boolean isVisible = shouldBeVisible || mInlineSuggestionsStrip.hasSuggestions();
        final int visibility = isVisible ? VISIBLE : (isFullscreenMode ? GONE : INVISIBLE);
        setVisibility(visibility);
        final SettingsValues currentSettingsValues = Settings.getInstance().getCurrent();
        mVoiceKey.setVisibility(currentSettingsValues.mShowsVoiceInputKey ? VISIBLE : INVISIBLE);
        updateSplitKey();
        updateFloatKey();
    }

    /** gti.dev: visual feedback for on-device dictation (active = recording). */
    public void setVoiceInputActive(final boolean active) {
        if (mVoiceKey != null) {
            // gti.dev: change the mic icon while recording, like Gboard.
            mVoiceKey.setImageDrawable(active ? mRecordingIcon : mVoiceIcon);
            mVoiceKey.setActivated(active);
            mVoiceKey.setContentDescription(getContext().getString(active
                    ? com.android.inputmethod.latin.R.string.spoken_description_stop_dictation
                    : com.android.inputmethod.latin.R.string.spoken_description_mic));
        }
    }

    /** gti.dev: show Gboard-style centered status ("Fale agora") during dictation. */
    public void setVoiceInputStatus(final String status) {
        if (status == null || status.isEmpty()) {
            // Restore the normal suggestion strip / toolbar.
            refreshStripVisibility();
        } else {
            if (isShowingMoreSuggestionPanel()) dismissMoreSuggestionsPanel();
            mLayoutHelper.layoutImportantNotice(mImportantNoticeStrip, status);
            mStripVisibilityGroup.showImportantNoticeStrip();
            mImportantNoticeStrip.setOnClickListener(this);
        }
    }

    public ColorStateList getToolKeyTintList() {
        return mWordViews.get(0).getTextColors();
    }

    private void updateFloatKey() {
        // gti.dev: the Gboard-style toolbar owns the left edge now; keep the
        // standard margin for the suggestion strip.
        setHorizontalMargins(mSuggestionsStrip, mDockedStripMargin);
        setHorizontalMargins(mImportantNoticeStrip, mDockedStripMargin);
        setHorizontalMargins(mInlineSuggestionsStrip, mDockedStripMargin);
        setHorizontalMargins(mClipboardSuggestionStrip, mDockedStripMargin);
    }

    private static void setHorizontalMargins(final View view, final int margin) {
        final MarginLayoutParams params = (MarginLayoutParams) view.getLayoutParams();
        if (params.getMarginStart() == margin && params.getMarginEnd() == margin) {
            return;
        }
        params.setMargins(margin, params.topMargin, margin, params.bottomMargin);
        params.setMarginStart(margin);
        params.setMarginEnd(margin);
        view.setLayoutParams(params);
    }

    private void updateSplitKey() {
        // gti.dev: split/floating toggles are no longer shown in the strip.
    }

    /**
     * gti.dev: Gboard shows the icon toolbar while the editor is idle and only
     * swaps in word suggestions once text is being composed. LatinIME calls
     * this with the real composing/editor state before pushing suggestions.
     */
    public void setShowWordSuggestions(final boolean showWordSuggestions) {
        mShowWordSuggestions = showWordSuggestions;
        refreshStripVisibility();
    }

    private void refreshStripVisibility() {
        // The dictation status bar owns the strip while it is on screen.
        if (mStripVisibilityGroup.isShowingImportantNoticeStrip()) {
            return;
        }
        if (mInlineSuggestionsStrip.hasSuggestions() || shouldShowClipboardSuggestion()) {
            mStripVisibilityGroup.showSuggestionsStrip(shouldShowClipboardSuggestion());
        } else if (mShowWordSuggestions) {
            // Typing: word suggestions replace the toolbar.
            mStripVisibilityGroup.showSuggestionsStrip(false /* showsClipboardSuggestion */);
        } else {
            // Idle: Gboard-style toolbar only, no suggestion row.
            mStripVisibilityGroup.showToolbar();
        }
    }

    public void setSuggestions(final SuggestedWords suggestedWords, final boolean isRtlLanguage) {
        clear();
        mStripVisibilityGroup.setLayoutDirection(isRtlLanguage);
        mSuggestedWords = suggestedWords;
        mStartIndexOfMoreSuggestions = mLayoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
                getContext(), mSuggestedWords, mSuggestionsStrip, this);
        refreshStripVisibility();
    }

    @Nullable
    public InlineSuggestionsRequest createInlineSuggestionsRequest(@Nonnull final Bundle uiExtras) {
        return mInlineSuggestionsStrip.createRequest(uiExtras,
                getToolKeyTintList().getDefaultColor());
    }

    public void setInlineSuggestions(@Nonnull final List<InlineSuggestion> suggestions) {
        mInlineSuggestionsStrip.setSuggestions(suggestions);
        if (isShowingMoreSuggestionPanel()) {
            dismissMoreSuggestionsPanel();
        }
        refreshStripVisibility();
        updateVisibility(mShouldBeVisible, mIsFullscreenMode);
    }

    public void setClipboardSuggestion(@Nullable final ClipboardSuggestion suggestion) {
        mClipboardSuggestion = suggestion;
        mClipboardSuggestionStrip.removeAllViews();
        if (suggestion != null) {
            for (final ClipboardSuggestion.Match match : suggestion.getMatches()) {
                mClipboardSuggestionStrip.addView(
                        createClipboardSuggestionChip(match, suggestion.isSensitive()));
            }
        }
        refreshStripVisibility();
    }

    @Nonnull
    private TextView createClipboardSuggestionChip(@Nonnull final ClipboardSuggestion.Match match,
            final boolean isSensitive) {
        final TextView chip = (TextView)LayoutInflater.from(getContext())
                .inflate(R.layout.clipboard_suggestion_chip, mClipboardSuggestionStrip, false);
        final ColorStateList tint = getToolKeyTintList();
        chip.setTextColor(tint);
        chip.setCompoundDrawableTintList(tint);
        chip.setBackgroundTintList(ColorStateList.valueOf(
                InlineSuggestionsStrip.getChipBackgroundColor(tint.getDefaultColor())));
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                match.getType() == ClipboardSuggestion.Type.CODE
                        ? R.drawable.ic_suggestion_code : R.drawable.ic_suggestion_clipboard,
                0, 0, 0);
        final Resources res = getResources();
        chip.setText(isSensitive ? SENSITIVE_CLIPBOARD_LABEL : match.getText());
        chip.setContentDescription(isSensitive
                ? res.getString(R.string.clipboard_suggestion_sensitive_description)
                : res.getString(R.string.clipboard_suggestion_description, match.getText()));
        chip.setTag(match);
        chip.setOnClickListener(this);
        return chip;
    }

    private boolean shouldShowClipboardSuggestion() {
        return mClipboardSuggestion != null
                && (mSuggestedWords.isEmpty() || mSuggestedWords.isPunctuationSuggestions()
                        || mSuggestedWords.mInputStyle
                                == SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION);
    }

    public void setMoreSuggestionsHeight(final int remainingHeight) {
        mLayoutHelper.setMoreSuggestionsHeight(remainingHeight);
    }

    // This method checks if we should show the important notice (checks on permanent storage if
    // it has been shown once already or not, and if in the setup wizard). If applicable, it shows
    // the notice. In all cases, it returns true if it was shown, false otherwise.
    public boolean maybeShowImportantNoticeTitle() {
        final SettingsValues currentSettingsValues = Settings.getInstance().getCurrent();
        if (!ImportantNoticeUtils.shouldShowImportantNotice(getContext(), currentSettingsValues)
                || mInlineSuggestionsStrip.hasSuggestions() || shouldShowClipboardSuggestion()) {
            return false;
        }
        if (getWidth() <= 0) {
            return false;
        }
        final String importantNoticeTitle = ImportantNoticeUtils.getSuggestContactsNoticeTitle(
                getContext());
        if (TextUtils.isEmpty(importantNoticeTitle)) {
            return false;
        }
        if (isShowingMoreSuggestionPanel()) {
            dismissMoreSuggestionsPanel();
        }
        mLayoutHelper.layoutImportantNotice(mImportantNoticeStrip, importantNoticeTitle);
        mStripVisibilityGroup.showImportantNoticeStrip();
        mImportantNoticeStrip.setOnClickListener(this);
        return true;
    }

    public void clear() {
        mSuggestionsStrip.removeAllViews();
        removeAllDebugInfoViews();
        refreshStripVisibility();
        dismissMoreSuggestionsPanel();
    }

    private void removeAllDebugInfoViews() {
        // The debug info views may be placed as children views of this {@link SuggestionStripView}.
        for (final View debugInfoView : mDebugInfoViews) {
            final ViewParent parent = debugInfoView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup)parent).removeView(debugInfoView);
            }
        }
    }

    private final MoreSuggestionsListener mMoreSuggestionsListener = new MoreSuggestionsListener() {
        @Override
        public void onSuggestionSelected(final SuggestedWordInfo wordInfo) {
            mListener.pickSuggestionManually(wordInfo);
            dismissMoreSuggestionsPanel();
        }

        @Override
        public void onCancelInput() {
            dismissMoreSuggestionsPanel();
        }
    };

    private final MoreKeysPanel.Controller mMoreSuggestionsController =
            new MoreKeysPanel.Controller() {
        @Override
        public void onDismissMoreKeysPanel() {
            mMainKeyboardView.onDismissMoreKeysPanel();
        }

        @Override
        public void onShowMoreKeysPanel(final MoreKeysPanel panel) {
            mMainKeyboardView.onShowMoreKeysPanel(panel);
        }

        @Override
        public void onCancelMoreKeysPanel() {
            dismissMoreSuggestionsPanel();
        }
    };

    public boolean isShowingMoreSuggestionPanel() {
        return mMoreSuggestionsView.isShowingInParent();
    }

    public void dismissMoreSuggestionsPanel() {
        mMoreSuggestionsView.dismissMoreKeysPanel();
    }

    @Override
    public boolean onLongClick(final View view) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                Constants.NOT_A_CODE, this);
        return showMoreSuggestions();
    }

    boolean showMoreSuggestions() {
        final Keyboard parentKeyboard = mMainKeyboardView.getKeyboard();
        if (parentKeyboard == null) {
            return false;
        }
        final SuggestionStripLayoutHelper layoutHelper = mLayoutHelper;
        if (mSuggestedWords.size() <= mStartIndexOfMoreSuggestions) {
            return false;
        }
        final int stripWidth = getWidth();
        final View container = mMoreSuggestionsContainer;
        final int maxWidth = stripWidth - container.getPaddingLeft() - container.getPaddingRight();
        final MoreSuggestions.Builder builder = mMoreSuggestionsBuilder;
        builder.layout(mSuggestedWords, mStartIndexOfMoreSuggestions, maxWidth,
                (int)(maxWidth * layoutHelper.mMinMoreSuggestionsWidth),
                layoutHelper.getMaxMoreSuggestionsRow(), parentKeyboard);
        mMoreSuggestionsView.setKeyboard(builder.build());
        container.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        final MoreKeysPanel moreKeysPanel = mMoreSuggestionsView;
        final int pointX = stripWidth / 2;
        final int pointY = -layoutHelper.mMoreSuggestionsBottomGap;
        moreKeysPanel.showMoreKeysPanel(this, mMoreSuggestionsController, pointX, pointY,
                mMoreSuggestionsListener);
        mOriginX = mLastX;
        mOriginY = mLastY;
        for (int i = 0; i < mStartIndexOfMoreSuggestions; i++) {
            mWordViews.get(i).setPressed(false);
        }
        return true;
    }

    // Working variables for {@link onInterceptTouchEvent(MotionEvent)} and
    // {@link onTouchEvent(MotionEvent)}.
    private int mLastX;
    private int mLastY;
    private int mOriginX;
    private int mOriginY;
    private final int mMoreSuggestionsModalTolerance;
    private boolean mNeedsToTransformTouchEventToHoverEvent;
    private boolean mIsDispatchingHoverEventToMoreSuggestions;
    private final GestureDetector mMoreSuggestionsSlidingDetector;
    private final GestureDetector.OnGestureListener mMoreSuggestionsSlidingListener =
            new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onScroll(MotionEvent down, MotionEvent me, float deltaX, float deltaY) {
            if (down == null) {
                return false;
            }
            final float dy = me.getY() - down.getY();
            if (deltaY > 0 && dy < 0) {
                return showMoreSuggestions();
            }
            return false;
        }
    };

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent me) {
        if (mStripVisibilityGroup.isShowingImportantNoticeStrip()) {
            return false;
        }
        // Detecting sliding up finger to show {@link MoreSuggestionsView}.
        if (!mMoreSuggestionsView.isShowingInParent()) {
            mLastX = (int)me.getX();
            mLastY = (int)me.getY();
            return mMoreSuggestionsSlidingDetector.onTouchEvent(me);
        }
        if (mMoreSuggestionsView.isInModalMode()) {
            return false;
        }

        final int action = me.getAction();
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index);
        final int y = (int)me.getY(index);
        if (Math.abs(x - mOriginX) >= mMoreSuggestionsModalTolerance
                || mOriginY - y >= mMoreSuggestionsModalTolerance) {
            // Decided to be in the sliding suggestion mode only when the touch point has been moved
            // upward. Further {@link MotionEvent}s will be delivered to
            // {@link #onTouchEvent(MotionEvent)}.
            mNeedsToTransformTouchEventToHoverEvent =
                    AccessibilityUtils.getInstance().isTouchExplorationEnabled();
            mIsDispatchingHoverEventToMoreSuggestions = false;
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            // Decided to be in the modal input mode.
            mMoreSuggestionsView.setModalMode();
        }
        return false;
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(final AccessibilityEvent event) {
        // Don't populate accessibility event with suggested words and voice key.
        return true;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent me) {
        if (!mMoreSuggestionsView.isShowingInParent()) {
            // Ignore any touch event while more suggestions panel hasn't been shown.
            // Detecting sliding up is done at {@link #onInterceptTouchEvent}.
            return true;
        }
        // In the sliding input mode. {@link MotionEvent} should be forwarded to
        // {@link MoreSuggestionsView}.
        final int index = me.getActionIndex();
        final int x = mMoreSuggestionsView.translateX((int)me.getX(index));
        final int y = mMoreSuggestionsView.translateY((int)me.getY(index));
        me.setLocation(x, y);
        if (!mNeedsToTransformTouchEventToHoverEvent) {
            mMoreSuggestionsView.onTouchEvent(me);
            return true;
        }
        // In sliding suggestion mode with accessibility mode on, a touch event should be
        // transformed to a hover event.
        final int width = mMoreSuggestionsView.getWidth();
        final int height = mMoreSuggestionsView.getHeight();
        final boolean onMoreSuggestions = (x >= 0 && x < width && y >= 0 && y < height);
        if (!onMoreSuggestions && !mIsDispatchingHoverEventToMoreSuggestions) {
            // Just drop this touch event because dispatching hover event isn't started yet and
            // the touch event isn't on {@link MoreSuggestionsView}.
            return true;
        }
        final int hoverAction;
        if (onMoreSuggestions && !mIsDispatchingHoverEventToMoreSuggestions) {
            // Transform this touch event to a hover enter event and start dispatching a hover
            // event to {@link MoreSuggestionsView}.
            mIsDispatchingHoverEventToMoreSuggestions = true;
            hoverAction = MotionEvent.ACTION_HOVER_ENTER;
        } else if (me.getActionMasked() == MotionEvent.ACTION_UP) {
            // Transform this touch event to a hover exit event and stop dispatching a hover event
            // after this.
            mIsDispatchingHoverEventToMoreSuggestions = false;
            mNeedsToTransformTouchEventToHoverEvent = false;
            hoverAction = MotionEvent.ACTION_HOVER_EXIT;
        } else {
            // Transform this touch event to a hover move event.
            hoverAction = MotionEvent.ACTION_HOVER_MOVE;
        }
        me.setAction(hoverAction);
        mMoreSuggestionsView.onHoverEvent(me);
        return true;
    }

    @Override
    public void onClick(final View view) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                Constants.CODE_UNSPECIFIED, this);
        if (view == mImportantNoticeStrip) {
            mListener.showImportantNoticeContents();
            return;
        }
        if (view.getTag() instanceof ClipboardSuggestion.Match && mClipboardSuggestion != null) {
            mListener.pickClipboardSuggestion(mClipboardSuggestion,
                    (ClipboardSuggestion.Match) view.getTag());
            return;
        }
        if (view == mEmojiKey) {
            mListener.onCodeInput(Constants.CODE_EMOJI,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE,
                    false /* isKeyRepeat */);
            return;
        }
        if (view == mClipboardKey) {
            mListener.onCodeInput(Constants.CODE_CLIPBOARD_PASTE,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE,
                    false /* isKeyRepeat */);
            return;
        }
        if (view == mGifKey) {
            // gti.dev: GIF button; no dedicated action yet, open the emoji panel.
            mListener.onCodeInput(Constants.CODE_EMOJI,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE,
                    false /* isKeyRepeat */);
            return;
        }
        if (view == mThemeKey) {
            mListener.onCodeInput(Constants.CODE_SETTINGS,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE,
                    false /* isKeyRepeat */);
            return;
        }
        if (view == mSettingsKey) {
            mListener.onCodeInput(Constants.CODE_SETTINGS,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE,
                    false /* isKeyRepeat */);
            return;
        }
        if (view == mVoiceKey || view == mImportantNoticeStrip) {
            // gti.dev: tapping the mic or the "Fale agora" label toggles dictation.
            mListener.onCodeInput(Constants.CODE_SHORTCUT,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE,
                    false /* isKeyRepeat */);
            return;
        }

        final Object tag = view.getTag();
        // {@link Integer} tag is set at
        // {@link SuggestionStripLayoutHelper#setupWordViewsTextAndColor(SuggestedWords,int)} and
        // {@link SuggestionStripLayoutHelper#layoutPunctuationSuggestions(SuggestedWords,ViewGroup}
        if (tag instanceof Integer) {
            final int index = (Integer) tag;
            if (index >= mSuggestedWords.size()) {
                return;
            }
            final SuggestedWordInfo wordInfo = mSuggestedWords.getInfo(index);
            mListener.pickSuggestionManually(wordInfo);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        dismissMoreSuggestionsPanel();
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overriden by showing suggestions later, if applicable.
        if (oldw <= 0 && w > 0) {
            maybeShowImportantNoticeTitle();
        }
    }
}
