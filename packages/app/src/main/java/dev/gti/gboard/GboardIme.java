package dev.gti.gboard;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A Gboard-style keyboard whose speech dictation is served entirely by a local
 * Whisper model. Reproduces the Gboard UI structure from docs/REA_GBOARD_UI.md:
 * a suggestion strip above a Material keyboard, light/dark themes, accent
 * popups on long press, space-bar cursor swipe and glide typing. The mic key
 * is press-and-hold; release commits the locally recognized text.
 */
public class GboardIme extends InputMethodService {

    private static final String TAG = "GboardIme";
    /** Fixed key row height in dp. Gboard keys measure ~50dp after padding. */
    private static final int KEY_ROW_H = 57;

    private enum KeyType { CHAR, SPACE, BACK, ENTER, SHIFT, SYMBOLS, SYMBOLS2, MIC, COMMA, PERIOD }

    // Theme palette (from REA_GBOARD_UI.md section 4.2)
    private int cKeyboardBg, cKeyBg, cKeyText, cSpecialBg, cAccent, cStripBg, cStripText;
    private boolean darkTheme = false;

    private LinearLayout root;
    private LinearLayout dictationBar;
    private WaveformView waveform;
    private TextView transcript;
    private TextView cancelMic;
    private LinearLayout suggestionStrip;
    private TextView[] suggestions = new TextView[3];
    private TextView micKey;
    private LinearLayout lettersPage, symbolsPage, symbols2Page, emojiPage;
    private TextView shiftKey;
    private GestureOverlayView gestureOverlay;
    private TextView glidePreview;

    private boolean shift = false;
    private boolean capsLock = false;
    /** 0 = letters, 1 = ?123 symbols, 2 = =\< more symbols. */
    private int symbolPage = 0;
    private boolean engineReady = false;
    private boolean micPermissionGranted = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DictationController dictation = new DictationController();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // Debug dataset collection (no-op in release via CollectorClient gate).
    private CorrectionTracker tracker;
    private String sessionId = "";
    private String pendingSwipeId = null;
    private String pendingSwipeWord = "";
    private java.util.List<float[]> pendingSwipePoints = new java.util.ArrayList<>();

    private static final Map<Character, String[]> ACCENTS = new HashMap<>();
    static {
        ACCENTS.put('a', new String[]{"á", "à", "â", "ã", "ä"});
        ACCENTS.put('e', new String[]{"é", "ê", "ë"});
        ACCENTS.put('i', new String[]{"í", "ì", "î", "ï"});
        ACCENTS.put('o', new String[]{"ó", "ò", "ô", "õ", "ö"});
        ACCENTS.put('u', new String[]{"ú", "ù", "û", "ü"});
        ACCENTS.put('c', new String[]{"ç", "¢"});
        ACCENTS.put('n', new String[]{"ñ"});
        ACCENTS.put('s', new String[]{"ß", "§"});
        // Gboard long-press alternates for consonants (digits/symbols).
        ACCENTS.put('m', new String[]{"?", "µ"});
        ACCENTS.put('q', new String[]{"?", "1"});
        ACCENTS.put('w', new String[]{"2", "ŵ"});
        ACCENTS.put('e', new String[]{"é", "ê", "è", "ë", "3", "€"});
        ACCENTS.put('r', new String[]{"4", "®"});
        ACCENTS.put('t', new String[]{"5", "†"});
        ACCENTS.put('y', new String[]{"ý", "ÿ", "6", "¥"});
        ACCENTS.put('u', new String[]{"ú", "ù", "û", "ü", "7"});
        ACCENTS.put('i', new String[]{"í", "ì", "î", "ï", "8", "¡"});
        ACCENTS.put('o', new String[]{"ó", "ò", "ô", "õ", "ö", "9", "ø"});
        ACCENTS.put('p', new String[]{"0", "¶"});
        ACCENTS.put('a', new String[]{"á", "à", "â", "ã", "ä", "æ"});
        ACCENTS.put('d', new String[]{"ð"});
        ACCENTS.put('f', new String[]{"ƒ"});
        ACCENTS.put('g', new String[]{"ĝ"});
        ACCENTS.put('h', new String[]{"ĥ"});
        ACCENTS.put('j', new String[]{"ĵ"});
        ACCENTS.put('k', new String[]{"ķ"});
        ACCENTS.put('l', new String[]{"ĺ", "ł"});
        ACCENTS.put('z', new String[]{"ž", "ź"});
        ACCENTS.put('x', new String[]{"×"});
        ACCENTS.put('v', new String[]{"√"});
        ACCENTS.put('b', new String[]{"✓"});
        // Numbers row long-press alternates.
        ACCENTS.put('1', new String[]{"!"});
        ACCENTS.put('2', new String[]{"@"});
        ACCENTS.put('3', new String[]{"#"});
        ACCENTS.put('4', new String[]{"$"});
        ACCENTS.put('5', new String[]{"%"});
        ACCENTS.put('6', new String[]{"^"});
        ACCENTS.put('7', new String[]{"&"});
        ACCENTS.put('8', new String[]{"*"});
        ACCENTS.put('9', new String[]{"("});
        ACCENTS.put('0', new String[]{")"});
    }

    /** Emoji palette used by the in-keyboard picker (Gboard emoji keyboard). */
    private static final String[] EMOJI = {
        "😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😍","🥰","😘",
        "😋","😛","😜","🤪","😝","🤗","🤔","🤭","🤫","😐","😑","😶","😏","😒","😞","😔",
        "😟","😕","🙁","😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡","🤬","🤯","😳",
        "👍","👎","👌","✌️","🤞","🤟","🤘","👏","🙌","🤝","🙏","💪","👊","✊","👋","🖐️",
        "❤️","🧡","💛","💚","💙","💜","🖤","💔","❣️","💕","💞","💗","💖","💘","💝","✨",
        "🔥","⭐","🌟","💫","☀️","🌈","🌸","🌺","🌻","🍀","🎉","🎊","🎂","🎁","⚽","🏀",
        "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🦄",
        "🍎","🍊","🍋","🍉","🍇","🍓","🍒","🍑","🥭","🍍","🥝","🍅","🥑","🌽","🍕","🍔"
    };

    // ---- lifecycle ---------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme();
        glideTracker = new GlideTracker(dp(6));
        glideTrail = glideTracker.path();
        glideResolver = new GlideKeyResolver();

        CollectorClient.init(getApplicationContext());
        sessionId = java.util.UUID.randomUUID().toString();
        tracker = new CorrectionTracker(sessionId);
        dictation.setCollector(tracker, sessionId);
    }

    @Override
    public View onCreateInputView() {
        root = buildKeyboard();
        Log.i(TAG, "UI built: letterRows=" + lettersPage.getChildCount()
                + " symbolPages=3 symbolRows=" + symbolsPage.getChildCount()
                + " overlay=" + (gestureOverlay != null)
                + " preview=" + (glidePreview != null)
                + " suggestionNodes=" + suggestionStrip.getChildCount());
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        refreshPermissionState();
        if (!engineReady) warmUpEngine();
        main.post(this::updateSuggestions);
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        if (dictation.isListening()) dictation.stop();
    }

    @Override
    public void onDestroy() {
        dictation.release();
        bg.shutdown();
        VoiceEngine.release();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean nowDark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (nowDark != darkTheme) {
            darkTheme = nowDark;
            applyTheme();
            if (root != null) {
                root = buildKeyboard();
                setInputView(root);
            }
        }
    }

    // ---- theme -------------------------------------------------------------

    private void applyTheme() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        darkTheme = night == Configuration.UI_MODE_NIGHT_YES;
        if (darkTheme) {
            // Gboard green theme (dark): mint accent, charcoal keys.
            cKeyboardBg = Color.parseColor("#1B1D1E");
            cKeyBg = Color.parseColor("#2D3031");
            cKeyText = Color.parseColor("#E8EAED");
            cSpecialBg = Color.parseColor("#3B3F41");
            cAccent = Color.parseColor("#A8E6C5"); // mint green
            cStripBg = Color.parseColor("#1B1D1E");
            cStripText = Color.parseColor("#E8EAED");
        } else {
            cKeyboardBg = Color.parseColor("#F1F3F4");
            cKeyBg = Color.parseColor("#FFFFFF");
            cKeyText = Color.parseColor("#202124");
            cSpecialBg = Color.parseColor("#DADCE0");
            cAccent = Color.parseColor("#1A73E8");
            cStripBg = Color.parseColor("#F1F3F4");
            cStripText = Color.parseColor("#202124");
        }
    }

    // ---- keyboard construction ---------------------------------------------

    private LinearLayout buildKeyboard() {
        int pad = dp(4);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundColor(cKeyboardBg);
        r.setPadding(pad, dp(6), pad, dp(6));
        // Explicit WRAP_CONTENT height. Without it the IME window inherits
        // MATCH_PARENT under adjustResize and the keyboard fills the screen.
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Dictation bar, hidden until the mic is pressed.
        dictationBar = new LinearLayout(this);
        dictationBar.setOrientation(LinearLayout.HORIZONTAL);
        dictationBar.setGravity(Gravity.CENTER_VERTICAL);
        dictationBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        dictationBar.setBackground(rounded(cKeyBg, dp(12)));
        dictationBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        waveform = new WaveformView(this, null);
        dictationBar.addView(waveform, new LinearLayout.LayoutParams(dp(56), dp(26)));
        transcript = new TextView(this);
        transcript.setSingleLine(true);
        transcript.setEllipsize(TextUtils.TruncateAt.END);
        transcript.setTextSize(14);
        transcript.setTextColor(cStripText);
        transcript.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        transcript.setPadding(dp(12), 0, 0, 0);
        dictationBar.addView(transcript);
        // Cancel button so the user can abort dictation instead of being stuck
        // with an empty "…" bar and no way out.
        cancelMic = new TextView(this);
        cancelMic.setText("✕");
        cancelMic.setTextSize(20);
        cancelMic.setTextColor(cStripText);
        cancelMic.setGravity(Gravity.CENTER);
        cancelMic.setPadding(dp(10), dp(4), dp(6), dp(4));
        cancelMic.setOnClickListener(v -> cancelDictation());
        dictationBar.addView(cancelMic, new LinearLayout.LayoutParams(
                dp(40), dp(40)));
        dictationBar.setVisibility(View.GONE);
        r.addView(dictationBar);

        // Suggestion strip (Gboard: toolbar + 3 suggestions + mic).
        suggestionStrip = new LinearLayout(this);
        suggestionStrip.setOrientation(LinearLayout.HORIZONTAL);
        suggestionStrip.setGravity(Gravity.CENTER_VERTICAL);
        suggestionStrip.setBackgroundColor(cStripBg);
        suggestionStrip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        // Gboard toolbar (REA 4.8): More features, Sticker, GIF, Clipboard,
        // Settings, Theme. Mic sits at the right end of the strip.
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        // Green circular arrow at the left, like Gboard's green theme.
        TextView back = new TextView(this);
        back.setText("›");
        back.setTextSize(22);
        back.setGravity(Gravity.CENTER);
        back.setTextColor(Color.parseColor("#1B1D1E"));
        back.setBackground(rounded(cAccent, dp(16)));
        back.setPadding(dp(2), dp(2), dp(2), dp(4));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)) {{ leftMargin = dp(4); }});
        back.setOnClickListener(v -> toast("Voltar"));
        toolbar.addView(back);
        toolbar.addView(iconRes(R.drawable.ic_more, "More features", v -> showMore()));
        toolbar.addView(iconRes(R.drawable.ic_sticker, "Sticker Keyboard",
                v -> showEmojiPicker(v)));
        toolbar.addView(iconRes(R.drawable.ic_gif, "GIF Keyboard", v -> toast("GIF")));
        toolbar.addView(iconRes(R.drawable.ic_clipboard, "Clipboard",
                v -> toast("Área de transferência")));
        suggestionStrip.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sugWrap = new LinearLayout(this);
        sugWrap.setOrientation(LinearLayout.HORIZONTAL);
        sugWrap.setGravity(Gravity.CENTER_VERTICAL);
        sugWrap.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        for (int i = 0; i < 3; i++) {
            // Thin vertical separators between suggestions, as in Gboard.
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(darkTheme ? Color.parseColor("#3C4043")
                        : Color.parseColor("#DADCE0"));
                sep.setLayoutParams(new LinearLayout.LayoutParams(1, dp(24)));
                sugWrap.addView(sep);
            }
            TextView s = new TextView(this);
            s.setGravity(Gravity.CENTER);
            s.setTextSize(15);
            s.setTextColor(i == 1 ? cAccent : cStripText);
            s.setTypeface(null, i == 1 ? Typeface.BOLD : Typeface.NORMAL);
            // Default pt-BR words so the strip is never empty on focus; the
            // local LM replaces these as soon as updateSuggestions() runs.
            s.setText(new String[]{"de", "que", "não"}[i]);
            s.setPadding(dp(6), dp(4), dp(6), dp(4));
            s.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            final int idx = i;
            s.setOnClickListener(v -> onSuggestionClick(idx));
            suggestions[i] = s;
            sugWrap.addView(s);
        }
        suggestionStrip.addView(sugWrap);

        micKey = new TextView(this);
        // Material microphone icon instead of a platform-dependent emoji.
        micKey.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(this, R.drawable.ic_mic), null, null, null);
        micKey.setContentDescription("Voice input");
        micKey.setGravity(Gravity.CENTER);
        micKey.setBackground(rounded(cAccent, dp(20)));
        micKey.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        attachMicTouch(micKey);
        suggestionStrip.addView(micKey, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            leftMargin = dp(8); rightMargin = dp(4);
        }});
        r.addView(suggestionStrip);

        View divider = new View(this);
        divider.setBackgroundColor(darkTheme ? Color.parseColor("#3C4043")
                : Color.parseColor("#DADCE0"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        r.addView(divider);

        // FrameLayout holds the key pages plus the gesture overlay drawn on top.
        FrameLayout keyboardFrame = new FrameLayout(this);
        keyboardFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(KEY_ROW_H * 4)));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lettersPage = buildLettersPage();
        symbolsPage = buildSymbolsPage();
        symbols2Page = buildSymbols2Page();
        emojiPage = buildEmojiPage();
        symbolsPage.setVisibility(View.GONE);
        symbols2Page.setVisibility(View.GONE);
        emojiPage.setVisibility(View.GONE);
        container.addView(lettersPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(symbolsPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(symbols2Page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(emojiPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keyboardFrame.addView(container);

        // Live gesture trail overlay (Gboard GestureOverlayView contract).
        gestureOverlay = new GestureOverlayView(this);
        gestureOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keyboardFrame.addView(gestureOverlay);

        // Floating preview of the decoded word, shown above the finger.
        glidePreview = new TextView(this);
        glidePreview.setTextSize(20);
        glidePreview.setTextColor(Color.WHITE);
        glidePreview.setBackgroundColor(Color.parseColor("#CC202124"));
        glidePreview.setPadding(dp(10), dp(4), dp(10), dp(4));
        glidePreview.setVisibility(View.GONE);
        glidePreview.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        keyboardFrame.addView(glidePreview);

        r.addView(keyboardFrame);

        // Gboard HeaderMotionEventHandler: gestures on the suggestion strip.
        // Horizontal swipe selects a suggestion; downward swipe delegates the
        // event to the keyboard body to start a glide (suggestion_kc_dist_threshold).
        attachSuggestionGestures(suggestionStrip);
        return r;
    }

    private void attachSuggestionGestures(final View strip) {
        final float[] start = new float[2];
        final boolean[] horizontal = {false};
        final boolean[] downward = {false};
        strip.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = ev.getX();
                    start[1] = ev.getY();
                    horizontal[0] = false;
                    downward[0] = false;
                    return false; // let children (buttons) receive the touch too
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - start[0];
                    float dy = ev.getY() - start[1];
                    if (!horizontal[0] && Math.abs(dx) > dp(28) && Math.abs(dx) > Math.abs(dy)) {
                        horizontal[0] = true;
                        int idx = dx > 0 ? 1 : 0; // swipe right = middle, left = first
                        onSuggestionClick(idx);
                    } else if (!downward[0] && dy > dp(40) && Math.abs(dy) > Math.abs(dx)) {
                        // Downward swipe from the strip starts a glide on the
                        // keyboard body, mirroring HeaderMotionEventHandler.
                        downward[0] = true;
                        startGlideFromStrip(ev);
                    }
                    return horizontal[0] || downward[0];
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return horizontal[0] || downward[0];
                default:
                    return false;
            }
        });
    }

    /** Begins a glide using the key currently under the finger after a strip swipe. */
    private void startGlideFromStrip(MotionEvent ev) {
        refreshGlideKeys();
        glideResolver.reset();
        glideResolver.move(ev.getRawX(), ev.getRawY());
        glideTrail = glideResolver.path();
        gliding = true;
        Log.i(TAG, "strip glide start trail=" + glideResolver.trail());
    }

    private LinearLayout buildLettersPage() {
        LinearLayout page = newPage();
        // Gboard real measures (REA 4.8/4.10): every row sums to 10 units.
        // Row1: 10 x 1. Row2: a(1.5) + 7x1 + l(1.5) = 10.
        // Row3: Shift(1.5) + 7x1 + Delete(1.5) = 10.
        page.addView(row(k('q'), k('w'), k('e'), k('r'), k('t'), k('y'), k('u'), k('i'), k('o'), k('p')));
        page.addView(row(keyWeighted("a", 1.5f), k('s'), k('d'), k('f'), k('g'), k('h'), k('j'), k('k'),
                keyWeighted("l", 1.5f)));
        shiftKey = special("⇪", 1.5f, KeyType.SHIFT);
        page.addView(row(shiftKey, k('z'), k('x'), k('c'), k('v'), k('b'), k('n'), k('m'),
                special("⌫", 1.5f, KeyType.BACK)));
        // Bottom row like Gboard: ?123 (green circle), emoji+comma, globe,
        // language-labeled space, period, Enter (green circle).
        TextView globe = icon("🌐", v -> toast("Idioma: Português (Brasil)"));
        globe.setTextColor(cKeyText); // Gboard globe is light, not blue
        // Gboard row4 weights: ?123(1.5) + ,(1) + emoji(1) + space(4) + .(1) + Enter(1.5) = 10
        page.addView(row(
                accentKey("?123", 1.5f, KeyType.SYMBOLS),
                emojiCommaKey(),
                globe,
                spaceLabeled("PT-BR"),
                keyChar(".", KeyType.PERIOD),
                accentKey("⏎", 1.5f, KeyType.ENTER)));
        return page;
    }

    /** First symbol page (numbers), matching Gboard's ?123 layout. */
    private LinearLayout buildSymbolsPage() {
        LinearLayout page = newPage();
        page.addView(row(sym("1"), sym("2"), sym("3"), sym("4"), sym("5"),
                sym("6"), sym("7"), sym("8"), sym("9"), sym("0")));
        page.addView(row(sym("@"), sym("#"), sym("$"), sym("_"), sym("&"),
                sym("-"), sym("+"), sym("("), sym(")"), sym("/")));
        page.addView(row(special("=\\<", 1.5f, KeyType.SYMBOLS2),
                sym("*"), sym("\""), sym("'"), sym(":"), sym(";"), sym("!"),
                sym("?"), special("⌫", 1.5f, KeyType.BACK)));
        page.addView(row(
                special("ABC", 1.5f, KeyType.SHIFT),
                keyChar(",", KeyType.COMMA),
                spaceKey(),
                keyChar(".", KeyType.PERIOD),
                special("⏎", 1.5f, KeyType.ENTER)));
        return page;
    }

    /** Second symbol page (more symbols), reached via =\< on the first. */
    private LinearLayout buildSymbols2Page() {
        LinearLayout page = newPage();
        page.addView(row(sym("~"), sym("`"), sym("|"), sym("•"), sym("√"),
                sym("π"), sym("÷"), sym("×"), sym("¶"), sym("Δ")));
        page.addView(row(sym("£"), sym("¢"), sym("€"), sym("¥"), sym("^"),
                sym("°"), sym("="), sym("{\\"), sym("}"), sym("©")));
        page.addView(row(special("?123", 1.5f, KeyType.SYMBOLS),
                sym("«"), sym("»"), sym("‹"), sym("›"), sym("[]"), sym("{}"),
                sym("½"), special("⌫", 1.5f, KeyType.BACK)));
        page.addView(row(
                special("ABC", 1.5f, KeyType.SHIFT),
                keyChar(",", KeyType.COMMA),
                spaceKey(),
                keyChar(".", KeyType.PERIOD),
                special("⏎", 1.5f, KeyType.ENTER)));
        return page;
    }

    private LinearLayout newPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(KEY_ROW_H * 4)));
        return page;
    }

    private LinearLayout row(View... keys) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setWeightSum(10f);
        for (View v : keys) r.addView(v);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(KEY_ROW_H)));
        return r;
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight));
        return v;
    }

    private View k(char c) { return keyChar(String.valueOf(c), KeyType.CHAR); }

    private View keyWeighted(String label, float weight) {
        TextView t = (TextView) keyChar(label, KeyType.CHAR);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = weight;
        return t;
    }

    private View sym(String s) { return keyChar(s, KeyType.CHAR); }

    /** Material key: rounded background, pressed state, long-press accents, glide. */
    private View keyChar(final String label, final KeyType type) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(20);
        t.setTextColor(cKeyText);
        t.setBackground(rounded(cKeyBg, dp(8)));
        t.setElevation(dp(1)); // Gboard's subtle key shadow
        t.setPadding(dp(2), dp(6), dp(2), dp(6));
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f) {{
            leftMargin = dp(0); rightMargin = dp(0); topMargin = dp(3); bottomMargin = dp(3);
        }});
        attachKeyTouch(t, label, type);
        return t;
    }

    private TextView special(String label, float weight, KeyType type) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(type == KeyType.SHIFT ? 20 : 18);
        t.setTextColor(cKeyText);
        t.setBackground(rounded(cSpecialBg, dp(8)));
        t.setElevation(dp(1)); // Gboard's subtle key shadow
        t.setPadding(dp(2), dp(6), dp(2), dp(6));
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight) {{
            leftMargin = dp(0); rightMargin = dp(0); topMargin = dp(3); bottomMargin = dp(3);
        }});
        if (type == KeyType.BACK) {
            attachRepeatDelete(t);
        } else {
            t.setOnClickListener(v -> onKey(type, label));
        }
        return t;
    }

    /** Accent-colored key (green on Gboard's green theme) for ?123 / Enter. */
    private TextView accentKey(String label, float weight, KeyType type) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(16);
        int darkText = Color.parseColor("#1B1D1E");
        t.setTextColor(darkTheme ? darkText : Color.WHITE);
        t.setBackground(rounded(cAccent, dp(8)));
        t.setElevation(dp(1));
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight) {{
            topMargin = dp(3); bottomMargin = dp(3);
        }});
        t.setOnClickListener(v -> onKey(type, label));
        return t;
    }

    /** Sends a single DEL key event to the focused field. */
    private void deleteOne() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
        CorrectionTracker tr = tracker;
        if (tr != null) {
            CorrectionTracker.Event burst = tr.onBackspace(System.currentTimeMillis());
            if (burst != null) CollectorClient.postCorrection(burst);
        }
        updateSuggestions();
    }

    /** Holding backspace repeats deletion, like Gboard. */
    private void attachRepeatDelete(final View v) {
        final Runnable repeat = new Runnable() {
            @Override public void run() {
                deleteOne();
                main.postDelayed(this, 80);
            }
        };
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Delete immediately on the tap (this was broken when the
                    // touch listener returned true and swallowed the click).
                    deleteOne();
                    main.postDelayed(repeat, 400); // initial delay before repeat
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(repeat);
                    return true;
                default:
                    return false;
            }
        });
    }

    private View spaceKey() {
        TextView t = new TextView(this);
        t.setText(" ");
        t.setGravity(Gravity.CENTER);
        t.setTextSize(16);
        t.setTextColor(cStripText);
        t.setBackground(rounded(cSpecialBg, dp(8)));
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 4f) {{
            leftMargin = dp(0); rightMargin = dp(0); topMargin = dp(3); bottomMargin = dp(3);
        }});
        attachSpaceTouch(t);
        return t;
    }

    /** Emoji+comma key: tap inserts comma, long-press opens the emoji page. */
    private View emojiCommaKey() {
        TextView t = new TextView(this);
        t.setText("☺,");
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(cKeyText);
        t.setBackground(rounded(cKeyBg, dp(8)));
        t.setElevation(dp(1));
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f) {{
            topMargin = dp(3); bottomMargin = dp(3);
        }});
        final long[] downAt = {0};
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downAt[0] = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (System.currentTimeMillis() - downAt[0] >= 400) {
                        showEmojiPicker(v); // long press -> emoji page
                    } else {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) ic.commitText(",", 1); // tap -> comma
                    }
                    return true;
                default:
                    return false;
            }
        });
        return t;
    }

    /** Space bar labeled with the current language, like Gboard. */
    private View spaceLabeled(String label) {
        TextView t = (TextView) spaceKey();
        t.setText(label);
        return t; // spaceKey is 4f; row4 sums to 10
    }

    private TextView icon(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(17);
        t.setTextColor(cStripText);
        t.setPadding(dp(10), dp(4), dp(6), dp(4));
        t.setOnClickListener(l);
        return t;
    }

    /** Toolbar icon backed by a Material-style VectorDrawable (like Gboard). */
    private TextView iconRes(int drawableRes, String desc, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(this, drawableRes), null, null, null);
        t.setContentDescription(desc);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(20), dp(6), dp(20));
        t.setOnClickListener(l);
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    // ---- touch handling: pressed state, accents, glide ---------------------

    private SuggestionEngine suggestionEngine;
    private GlideTracker glideTracker;
    private GlideKeyResolver glideResolver;
    private List<String> glideTrail;
    private boolean gliding = false;

    private void attachKeyTouch(final TextView t, final String label, final KeyType type) {
        final GradientDrawable bg = (GradientDrawable) t.getBackground();
        final int base = cKeyBg;
        final long[] downAt = {0};
        final TapDetector tap = new TapDetector(dp(12));
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bg.setColor(darkTheme ? Color.parseColor("#414249")
                            : Color.parseColor("#E8EAED"));
                    glideTracker.clear();
                    refreshGlideKeys();
                    glideResolver.reset();
                    glideResolver.move(ev.getRawX(), ev.getRawY());
                    glideTrail = glideResolver.path();
                    downAt[0] = System.currentTimeMillis();
                    tap.down(ev.getRawX(), ev.getRawY());
                    gliding = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!gliding) return true;
                    float x = ev.getRawX(), y = ev.getRawY();
                    if (!tap.move(x, y)) {
                        // Gboard UX: a plain tap must never trigger a swipe/glide.
                        return true;
                    }
                    addGesturePoint(x, y);
                    String hit = glideResolver.move(x, y);
                    if (hit != null) {
                        updateGlidePreview(x, y);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    bg.setColor(base);
                    boolean wasGlide = gliding && tap.isGlide();
                    gliding = false;
                    tap.up();
                    // Snapshot the traced points before the overlay is cleared.
                    pendingSwipePoints = (gestureOverlay != null && gestureOverlay.getWidth() > 0
                            && gestureOverlay.getHeight() > 0)
                            ? gestureOverlay.snapshotNormalized(
                                    gestureOverlay.getWidth(), gestureOverlay.getHeight())
                            : new java.util.ArrayList<>();
                    clearGlideVisuals();
                    long held = System.currentTimeMillis() - downAt[0];
                    if (wasGlide && glideTrail.size() > 1) {
                        commitGlide();
                    } else if (type == KeyType.CHAR && label.length() == 1) {
                        if (held >= 450) {
                            showAccentPopup(t, label.charAt(0));
                        } else {
                            onKey(type, label); // simple tap = single character
                        }
                    } else {
                        onKey(type, label);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    bg.setColor(base);
                    gliding = false;
                    tap.up();
                    clearGlideVisuals();
                    return true;
                default:
                    return false;
            }
        });
    }

    /**
     * Finds the nearest key TextView to a raw screen coordinate. Gboard uses a
     * distance threshold (suggestion_kc_dist_threshold) rather than strict
     * rectangle containment, so a glide slightly off a key still registers it.
     */
    /** Refreshes the glide resolver with the current page's key rectangles. */
    private void refreshGlideKeys() {
        if (glideResolver == null) return;
        ViewGroup page = symbolPage == 1 ? symbolsPage
                : symbolPage == 2 ? symbols2Page : lettersPage;
        List<GlideKeyResolver.KeyBox> boxes = new ArrayList<>();
        if (page != null) {
            for (int i = 0; i < page.getChildCount(); i++) {
                View row = page.getChildAt(i);
                if (!(row instanceof ViewGroup)) continue;
                ViewGroup rg = (ViewGroup) row;
                for (int j = 0; j < rg.getChildCount(); j++) {
                    View key = rg.getChildAt(j);
                    if (!(key instanceof TextView) || key.getWidth() == 0) continue;
                    int[] loc = new int[2];
                    key.getLocationOnScreen(loc);
                    boxes.add(new GlideKeyResolver.KeyBox(
                            ((TextView) key).getText().toString(),
                            loc[0], loc[1], loc[0] + key.getWidth(), loc[1] + key.getHeight()));
                }
            }
        }
        glideResolver.setKeys(boxes);
    }

    private View findKeyAt(float x, float y) {
        ViewGroup page = symbolPage == 1 ? symbolsPage
                : symbolPage == 2 ? symbols2Page : lettersPage;
        if (page == null) return null;
        View best = null;
        float bestDist = Float.MAX_VALUE;
        float radius = dp(38); // tolerant proximity, ~half a key
        for (int i = 0; i < page.getChildCount(); i++) {
            View row = page.getChildAt(i);
            if (!(row instanceof ViewGroup)) continue;
            ViewGroup rg = (ViewGroup) row;
            for (int j = 0; j < rg.getChildCount(); j++) {
                View key = rg.getChildAt(j);
                if (!(key instanceof TextView) || key.getWidth() == 0) continue;
                int[] loc = new int[2];
                key.getLocationOnScreen(loc);
                float cx = loc[0] + key.getWidth() / 2f;
                float cy = loc[1] + key.getHeight() / 2f;
                float d = (float) Math.hypot(x - cx, y - cy);
                if (d < bestDist && d <= radius) { bestDist = d; best = key; }
            }
        }
        return best;
    }

    /**
     * Gboard-equivalent free cursor: swipe horizontally on the space key to
     * move the cursor continuously via setSelection, with velocity based on
     * the accumulated displacement (FreeCursorKeyboard contract).
     */
    private void attachSpaceTouch(final TextView t) {
        final GradientDrawable bg = (GradientDrawable) t.getBackground();
        final int base = cKeyBg;
        final float[] start = new float[2];
        final int[] cursor = {-1};
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = ev.getX();
                    start[1] = ev.getY();
                    cursor[0] = -1;
                    bg.setColor(darkTheme ? Color.parseColor("#414249")
                            : Color.parseColor("#E8EAED"));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - start[0];
                    if (Math.abs(dx) > dp(8)) {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            if (cursor[0] < 0) {
                                CharSequence before = ic.getTextBeforeCursor(2000, 0);
                                cursor[0] = before == null ? 0 : before.length();
                            }
                            int steps = Math.max(1, Math.round(Math.abs(dx) / dp(12)));
                            cursor[0] += (dx > 0 ? steps : -steps);
                            if (cursor[0] < 0) cursor[0] = 0;
                            ic.setSelection(cursor[0], cursor[0]);
                            start[0] = ev.getX();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    bg.setColor(base);
                    if (cursor[0] < 0 && Math.abs(ev.getX() - start[0]) < dp(20)) {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            confirmWord(currentWordPrefix());
                            ic.commitText(" ", 1);
                        }
                        updateSuggestions();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /** Converts a raw screen coordinate into the overlay's local space. */
    private void addGesturePoint(float rawX, float rawY) {
        if (gestureOverlay == null) return;
        int[] loc = new int[2];
        gestureOverlay.getLocationOnScreen(loc);
        gestureOverlay.addPoint(rawX - loc[0], rawY - loc[1]);
    }

    /** Shows the live decoded word above the finger during a glide. */
    private void updateGlidePreview(float rawX, float rawY) {
        if (glidePreview == null || glideTrail.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String s : glideTrail) if (s.length() == 1) sb.append(s);
        List<String> cands = LocalDecoder.decode(sb.toString());
        String preview = cands.isEmpty() ? sb.toString() : cands.get(0);
        glidePreview.setText(preview);
        glidePreview.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) glidePreview.getLayoutParams();
        int[] loc = new int[2];
        if (glidePreview.getParent() instanceof View) {
            ((View) glidePreview.getParent()).getLocationOnScreen(loc);
        }
        lp.leftMargin = Math.max(0, (int) (rawX - loc[0] - dp(30)));
        lp.topMargin = Math.max(0, (int) (rawY - loc[1] - dp(56)));
        glidePreview.setLayoutParams(lp);
    }

    private void clearGlideVisuals() {
        if (gestureOverlay != null) gestureOverlay.clear();
        if (glidePreview != null) glidePreview.setVisibility(View.GONE);
    }

    /**
     * Gboard-equivalent glide commit: hands the raw trail to LocalDecoder,
     * which ranks candidates using the spatial layout + pt-BR language model,
     * and commits the most probable word. Falls back to the literal trail.
     */
    private void commitGlide() {
        StringBuilder sb = new StringBuilder();
        for (String s : glideTrail) {
            if (s.length() == 1) sb.append(s);
        }
        String trail = sb.toString();
        glideTrail.clear();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || trail.length() == 0) return;
        List<String> candidates = LocalDecoder.decode(trail);
        // Prefer a dictionary candidate: the raw trail (first entry) is only a
        // fallback when no real word matches, mirroring Gboard's decoder which
        // commits the top ranked dictionary word, not the literal key sweep.
        String word = trail;
        for (String c : candidates) {
            if (!c.equals(trail)) { word = c; break; }
        }
        Log.i(TAG, "glide trail=\"" + trail + "\" -> \"" + word
                + "\" candidates=" + candidates);

        // Debug dataset: record the gesture so failed glides can be studied.
        pendingSwipeWord = word;
        if (tracker != null) {
            String kb = (gestureOverlay != null
                    ? gestureOverlay.getWidth() + "x" + gestureOverlay.getHeight() : "0x0");
            pendingSwipeId = CollectorClient.postSwipe(
                    sessionId, trail, word, candidates, kb, pendingSwipePoints);
        }

        // Gboard commits the decoded word plus a trailing space so the next
        // word starts cleanly.
        ic.commitText(word + " ", 1);
        updateSuggestions();
    }

    private void showAccentPopup(final TextView anchor, char c) {
        String[] opts = ACCENTS.get(Character.toLowerCase(c));
        if (opts == null || opts.length == 0) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackgroundColor(cKeyBg);
        for (final String a : opts) {
            TextView t = new TextView(this);
            t.setText(a);
            t.setTextSize(22);
            t.setTextColor(cKeyText);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(a, 1);
                popup.dismiss();
                updateSuggestions();
            });
            box.addView(t);
        }
        popup = new PopupWindow(box, FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, false);
        popup.setOutsideTouchable(true);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START,
                Math.max(0, loc[0] - dp(20)), loc[1] - dp(52));
    }

    private PopupWindow popup;

    private void attachMicTouch(final View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onMicDown();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    onMicUp();
                    return true;
                default:
                    return false;
            }
        });
    }

    // ---- key handling ------------------------------------------------------

    private void onKey(KeyType type, String label) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (type) {
            case CHAR:
                String c = label;
                if (shift || capsLock) c = c.toUpperCase();
                ic.commitText(c, 1);
                if (shift && !capsLock) { shift = false; refreshShiftVisual(); }
                updateSuggestions();
                break;
            case COMMA: ic.commitText(",", 1); updateSuggestions(); break;
            case PERIOD: ic.commitText(".", 1); updateSuggestions(); break;
            case BACK:
                deleteOne(); // already refreshes suggestions
                break;
            case ENTER:
                confirmWord(currentWordPrefix());
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                break;
            case SHIFT:
                if (symbolPage != 0) { showPage(0); break; }
                if (capsLock) { capsLock = false; shift = false; }
                else if (shift) { capsLock = true; }
                else { shift = true; }
                refreshShiftVisual();
                break;
            case SYMBOLS: showPage(1); break;
            case SYMBOLS2: showPage(2); break;
            default: break;
        }
    }

    private void refreshShiftVisual() {
        if (shiftKey == null) return;
        shiftKey.setText(capsLock ? "🔒" : "⇪");
        shiftKey.setTextColor(capsLock ? cAccent : cKeyText);
    }

    private void showPage(int page) {
        symbolPage = page;
        Log.i(TAG, "showPage=" + page);
        if (lettersPage != null) lettersPage.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        if (symbolsPage != null) symbolsPage.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        if (symbols2Page != null) symbols2Page.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        if (emojiPage != null) emojiPage.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
    }

    private void switchSymbols() { showPage(symbolPage == 0 ? 1 : 0); }

    private void showMore() {
        Toast.makeText(this, "Mais recursos: dicionário local + STT offline",
                Toast.LENGTH_SHORT).show();
    }

    /** Alternates between light and dark keyboard themes. */
    private void toggleTheme() {
        darkTheme = !darkTheme;
        applyThemeForced(darkTheme);
        root = buildKeyboard();
        setInputView(root);
    }

    /** Like applyTheme() but forces a specific mode instead of reading the system. */
    private void applyThemeForced(boolean dark) {
        if (dark) {
            cKeyboardBg = Color.parseColor("#1B1D1E");
            cKeyBg = Color.parseColor("#2D3031");
            cKeyText = Color.parseColor("#E8EAED");
            cSpecialBg = Color.parseColor("#3B3F41");
            cAccent = Color.parseColor("#A8E6C5");
            cStripBg = Color.parseColor("#1B1D1E");
            cStripText = Color.parseColor("#E8EAED");
        } else {
            cKeyboardBg = Color.parseColor("#F1F3F4");
            cKeyBg = Color.parseColor("#FFFFFF");
            cKeyText = Color.parseColor("#202124");
            cSpecialBg = Color.parseColor("#DADCE0");
            cAccent = Color.parseColor("#1A73E8");
            cStripBg = Color.parseColor("#F1F3F4");
            cStripText = Color.parseColor("#202124");
        }
    }

    /** Emoji keyboard as a full InputView page (like Gboard's emoji keyboard). */
    private LinearLayout buildEmojiPage() {
        LinearLayout page = newPage();
        // Header row with a "ABC" button to return to the letters page.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(cStripBg);
        TextView abc = new TextView(this);
        abc.setText("ABC");
        abc.setTextSize(16);
        abc.setTextColor(cAccent);
        abc.setPadding(dp(16), dp(6), dp(16), dp(6));
        abc.setOnClickListener(v -> showPage(0));
        header.addView(abc);
        TextView title = new TextView(this);
        title.setText("Emoji");
        title.setTextColor(cStripText);
        title.setTextSize(14);
        header.addView(title);
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(8);
        grid.setRowCount((EMOJI.length + 7) / 8);
        grid.setBackgroundColor(cKeyboardBg);
        for (final String e : EMOJI) {
            TextView cell = new TextView(this);
            cell.setText(e);
            cell.setTextSize(24);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(2), dp(4), dp(2), dp(4));
            cell.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(e, 1);
            });
            grid.addView(cell);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cKeyboardBg);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    /** Switches the InputView to the emoji keyboard page (not a popup). */
    private void showEmojiPicker(View anchor) {
        Log.i(TAG, "showEmojiPicker: switching to emoji page");
        showPage(3); // 3 = emoji page
    }

    // ---- suggestions -------------------------------------------------------

    private void updateSuggestions() {
        bg.submit(() -> {
            String prefix = currentWordPrefix();
            // Gboard decoder contract: onKeyPress returns ranked next words.
            // Use the bigram LM when we have a completed previous word, else
            // fall back to prefix ranking from the dictionary.
            final List<String> words;
            if (suggestionEngine != null) {
                words = suggestionEngine.suggest(prefix, 3);
            } else {
                words = DictionaryCorrector.suggest(prefix, 3);
            }
            Log.i(TAG, "suggest prefix=\"" + prefix + "\" -> " + words);
            final boolean fallback = words.isEmpty();
            final List<String> shown = fallback
                    ? java.util.Arrays.asList("de", "que", "não") : words;
            main.post(() -> {
                for (int i = 0; i < 3; i++) {
                    suggestions[i].setText(i < shown.size() ? shown.get(i) : "");
                }
                CorrectionTracker tr = tracker;
                if (tr != null) {
                    long now = System.currentTimeMillis();
                    tr.onSuggestionsShown(prefix, shown, now);
                    if (fallback) {
                        CollectorClient.postCorrection(tr.onFallbackGeneric(now));
                    }
                }
            });
        });
    }

    /** Word immediately before the current token (LM context for nextWords). */
    private String previousWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        CharSequence before = ic.getTextBeforeCursor(60, 0);
        if (before == null) return null;
        String s = before.toString().trim();
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\s+");
        return parts.length >= 2 ? parts[parts.length - 2] : parts[0];
    }

    /** Extracts the token immediately before the cursor (accent-insensitive handled inside). */
    private String currentWordPrefix() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null) return "";
        String s = before.toString();
        int i = s.length();
        while (i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) i--;
        return s.substring(i);
    }

    private void onSuggestionClick(int idx) {
        String w = suggestions[idx].getText().toString();
        if (w.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String prefix = currentWordPrefix();
        if (!prefix.isEmpty()) {
            ic.deleteSurroundingText(prefix.length(), 0);
        }
        ic.commitText(w + " ", 1);
        CorrectionTracker tr = tracker;
        if (tr != null) {
            CollectorClient.postCorrection(tr.onSuggestionSelected(idx, System.currentTimeMillis()));
        }
        // A swipe was superseded by a suggestion tap: no failure to attribute.
        clearPendingSwipe();
        updateSuggestions();
    }

    /** One place for the word-confirm decision shared by space and enter. */
    private void confirmWord(String typed) {
        CorrectionTracker tr = tracker;
        if (tr == null) {
            clearPendingSwipe();
            return;
        }
        CorrectionTracker.WordCommit commit = tr.onWordCommitted(
                typed, pendingSwipeWord, pendingSwipeId, System.currentTimeMillis());
        if (commit != null) {
            for (CorrectionTracker.Event event : commit.events) {
                CollectorClient.postCorrection(event);
            }
            if (commit.clearPendingSwipe) clearPendingSwipe();
        }
    }

    private void clearPendingSwipe() {
        pendingSwipeId = null;
        pendingSwipeWord = "";
    }

    // ---- microphone / dictation -------------------------------------------

    private void onMicDown() {
        refreshPermissionState();
        if (!micPermissionGranted) {
            requestMicPermission();
            toast("Permita o microfone para ditado por voz");
            return;
        }
        if (!engineReady) {
            toast("Preparando modelo de voz…");
            return;
        }
        dictationBar.setVisibility(View.VISIBLE);
        suggestionStrip.setVisibility(View.GONE);
        dictation.start(dictationListener);
    }

    private void onMicUp() {
        if (dictation.isListening()) dictation.stop();
    }

    /** Aborts dictation without committing partial text, and restores the strip. */
    private void cancelDictation() {
        if (dictation.isListening()) dictation.stop();
        transcribing = false;
        main.removeCallbacks(transcribingAnim);
        transcript.setText("");
        dictationBar.setVisibility(View.GONE);
        suggestionStrip.setVisibility(View.VISIBLE);
        waveform.setActive(false);
        updateSuggestions();
    }

    /** Animated ellipsis for the "Transcrevendo…" state. */
    private final Runnable transcribingAnim = new Runnable() {
        private int dots = 0;
        @Override public void run() {
            if (!transcribing) return;
            dots = (dots + 1) % 4;
            StringBuilder sb = new StringBuilder("Transcrevendo");
            for (int i = 0; i < dots; i++) sb.append('.');
            transcript.setText(sb.toString());
            main.postDelayed(this, 350);
        }
    };
    private volatile boolean transcribing = false;

    private final DictationController.Listener dictationListener = new DictationController.Listener() {
        @Override public void onPartial(String text) {
            if (transcribing) return;
            transcript.setText(text.isEmpty() ? "Ouvindo… solte o 🎤 ou toque ✕"
                    : text);
        }
        @Override public void onTranscribing() {
            transcribing = true;
            waveform.setActive(false);
            main.removeCallbacks(transcribingAnim);
            main.post(transcribingAnim);
        }
        @Override public void onFinal(String text) {
            transcribing = false;
            main.removeCallbacks(transcribingAnim);
            if (text.isEmpty()) {
                transcript.setText("Nenhuma fala detectada");
            } else {
                transcript.setText(text);
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(text + " ", 1);
            }
            main.postDelayed(() -> {
                if (!dictation.isListening()) {
                    dictationBar.setVisibility(View.GONE);
                    suggestionStrip.setVisibility(View.VISIBLE);
                    waveform.setActive(false);
                    updateSuggestions();
                }
            }, 1100);
        }
        @Override public void onRms(float rms) {
            waveform.pushLevel(rms);
        }
        @Override public void onListening(boolean listening) {
            if (listening) {
                transcribing = false;
                waveform.setActive(true);
                transcript.setText("Ouvindo… solte o 🎤 ou toque ✕");
            } else {
                waveform.setActive(false);
            }
        }
    };

    private void requestMicPermission() {
        startActivity(new Intent(this, PermissionActivity.class));
    }

    private void refreshPermissionState() {
        micPermissionGranted =
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Loads the curated everyday pt-BR word list from assets. */
    private List<String> loadCommonWords() {
        List<String> words = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(getAssets().open("dict/common-words.txt"),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) words.add(line.trim().toLowerCase());
            }
        } catch (Exception e) {
            Log.w(TAG, "common words not loaded: " + e.getMessage());
        }
        return words;
    }

    private void warmUpEngine() {
        bg.submit(() -> {
            try {
                VoiceEngine.init(GboardIme.this);
                DictionaryCorrector.load(GboardIme.this);
                LocalDecoder.load(GboardIme.this);
                suggestionEngine = new SuggestionEngine(
                        loadCommonWords(), DictionaryCorrector.getWords());
                engineReady = true;
                Log.i(TAG, "Gboard IME ready (local STT + decoder + suggestions)");
            } catch (Exception e) {
                Log.e(TAG, "engine init failed: " + e.getMessage());
                main.post(() -> toast("Falha ao carregar modelo: " + e.getMessage()));
            }
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
