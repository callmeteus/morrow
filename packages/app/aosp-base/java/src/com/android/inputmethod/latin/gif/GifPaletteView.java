/*
 * gti.dev: Gboard-style GIF search panel backed by the keyless GifJif API.
 */

package com.android.inputmethod.latin.gif;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.inputmethod.keyboard.KeyboardActionListener;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.common.Constants;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GifPaletteView extends LinearLayout implements View.OnClickListener {
    private KeyboardActionListener mKeyboardActionListener =
            KeyboardActionListener.EMPTY_LISTENER;
    private GifCommitListener mGifCommitListener = null;

    /** Callback invoked when the user selects a GIF. */
    public interface GifCommitListener {
        void onGifSelected(String url, String title);
    }

    private final List<GifJifClient.Gif> mGifs = new ArrayList<>();
    private final GifAdapter mAdapter = new GifAdapter();
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(6);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    // gti.dev: cache decoded thumbnails so scrolling does not refetch every GIF.
    private final LruCache<String, Drawable> mCache =
            new LruCache<String, Drawable>(64) {
                @Override protected int sizeOf(final String key, final Drawable value) {
                    return 1;
                }
            };
    private final ColorDrawable mPlaceholder = new ColorDrawable(Color.parseColor("#FF3C4043"));

    private EditText mSearch;
    private TextView mStatus;
    private Runnable mSearchRequestListener = null;
    // gti.dev: infinite scroll state.
    private RecyclerView mGrid;
    private GridLayoutManager mGridLayoutManager;
    private String mCurrentQuery = "happy";
    private String mCursor = null;
    private boolean mHasMore = false;
    private boolean mIsLoading = false;

    public GifPaletteView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSearch = findViewById(R.id.gif_search);
        mStatus = findViewById(R.id.gif_status);
        mGrid = findViewById(R.id.gif_grid);
        mGridLayoutManager = new GridLayoutManager(getContext(), 3);
        mGrid.setLayoutManager(mGridLayoutManager);
        mGrid.setAdapter(mAdapter);
        // gti.dev: load the next page when the user scrolls near the end.
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(final RecyclerView rv, final int dx, final int dy) {
                if (dy <= 0 || mIsLoading || !mHasMore) return;
                final int last = mGridLayoutManager.findLastVisibleItemPosition();
                final int total = mAdapter.getItemCount();
                if (last >= total - 4) loadMore();
            }
        });
        findViewById(R.id.gif_back_key).setOnClickListener(this);
        setupActionBar();
        // gti.dev: an EditText inside an IME cannot raise the software keyboard
        // on its own. Ask LatinIME to show the normal keyboard and route typed
        // text back here, the way Gboard handles GIF search.
        mSearch.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    && mSearchRequestListener != null) {
                mSearchRequestListener.run();
            }
            // gti.dev: consume the touch so it never falls through to the app
            // window behind the IME. Text is routed in via appendSearchText().
            return true;
        });
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { load(s.toString()); }
        });
        mMainHandler.postDelayed(() -> load("happy"), 250);
    }

    /** New search: reset pagination and load the first page. */
    private void load(final String query) {
        final String q = query.trim().isEmpty() ? "happy" : query.trim();
        mCurrentQuery = q;
        mCursor = null;
        mHasMore = false;
        mStatus.setText(R.string.gif_searching);
        mIsLoading = true;
        mExecutor.execute(() -> {
            final GifJifClient.Result page = GifJifClient.searchPage(q, null);
            mMainHandler.post(() -> {
                mIsLoading = false;
                if (!q.equals(mCurrentQuery)) return; // a newer search superseded this one
                mGifs.clear();
                mGifs.addAll(page.gifs);
                mCursor = page.cursor;
                mHasMore = page.hasMore;
                mAdapter.notifyDataSetChanged();
                mStatus.setText(page.gifs.isEmpty()
                        ? getResources().getString(R.string.gif_no_results) : "");
            });
        });
    }

    /** Infinite scroll: append the next page for the current query. */
    private void loadMore() {
        if (mIsLoading || !mHasMore) return;
        mIsLoading = true;
        final String query = mCurrentQuery;
        final String cursor = mCursor;
        mStatus.setText(R.string.gif_searching);
        mExecutor.execute(() -> {
            final GifJifClient.Result page = GifJifClient.searchPage(query, cursor);
            mMainHandler.post(() -> {
                mIsLoading = false;
                if (!query.equals(mCurrentQuery)) return;
                final int start = mGifs.size();
                // de-duplicate by full URL, repeated cursor pages can overlap
                for (final GifJifClient.Gif gif : page.gifs) {
                    boolean dup = false;
                    for (final GifJifClient.Gif existing : mGifs) {
                        if (existing.fullUrl.equals(gif.fullUrl)) { dup = true; break; }
                    }
                    if (!dup) mGifs.add(gif);
                }
                mCursor = page.cursor;
                mHasMore = page.hasMore && page.gifs.size() > 0;
                mAdapter.notifyItemRangeInserted(start, mGifs.size() - start);
                mStatus.setText("");
            });
        });
    }

    /** gti.dev: wire the shared bottom action bar so users can switch panels. */
    private void setupActionBar() {
        final View bar = findViewById(R.id.emoji_action_bar);
        if (bar == null) return;
        com.android.inputmethod.keyboard.emoji.ActionBarHighlight.setActive(
                bar, R.id.emoji_keyboard_gif);
        final TextView abc = bar.findViewById(R.id.emoji_keyboard_alphabet_left);
        if (abc != null) {
            abc.setText("ABC");
            abc.setOnClickListener(v -> mKeyboardActionListener.onCodeInput(
                    Constants.CODE_ALPHA_FROM_EMOJI, Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE, false));
        }
        final View gifTab = bar.findViewById(R.id.emoji_keyboard_gif);
        if (gifTab != null) {
            gifTab.setOnClickListener(v -> mKeyboardActionListener.onCodeInput(
                    Constants.CODE_GIF, Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE, false));
        }
        // gti.dev: the smiley button switches back to the emoji palette.
        final View emojiButton = bar.findViewById(R.id.emoji_keyboard_emoji);
        if (emojiButton != null) {
            emojiButton.setOnClickListener(v -> mKeyboardActionListener.onCodeInput(
                    Constants.CODE_EMOJI, Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE, false));
        }
        final View kaomojiTab = bar.findViewById(R.id.emoji_keyboard_kaomoji);
        if (kaomojiTab != null) {
            kaomojiTab.setOnClickListener(v -> mKeyboardActionListener.onCodeInput(
                    Constants.CODE_KAOMOJI, Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE, false));
        }
        final ImageButton delete = bar.findViewById(R.id.emoji_keyboard_delete);
        if (delete != null) {
            delete.setOnClickListener(v -> mKeyboardActionListener.onCodeInput(
                    Constants.CODE_DELETE, Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE, false));
        }
    }

    /** gti.dev: apply the keyboard's delete icon to the shared action bar. */
    public void setActionBarIcons(final com.android.inputmethod.keyboard.internal.KeyboardIconsSet iconSet) {
        final View bar = findViewById(R.id.emoji_action_bar);
        if (bar == null) return;
        final ImageButton delete = bar.findViewById(R.id.emoji_keyboard_delete);
        if (delete != null) {
            final int res = iconSet.getIconResourceId(
                    com.android.inputmethod.keyboard.internal.KeyboardIconsSet.NAME_DELETE_KEY);
            if (res != 0) delete.setImageResource(res);
        }
    }

    public void setSearchRequestListener(final Runnable listener) {
        mSearchRequestListener = listener;
    }

    /** gti.dev: while searching, show only the search header; the alphabet
     *  keyboard is displayed underneath by KeyboardSwitcher. */
    public void setSearchEditing(final boolean editing) {
        final View grid = findViewById(R.id.gif_grid);
        if (grid != null) grid.setVisibility(editing ? View.GONE : View.VISIBLE);
        if (mStatus != null) mStatus.setVisibility(editing ? View.GONE : View.VISIBLE);
        // Adjust our height so the panel shrinks to the header while searching.
        // The parent is a FrameLayout, so use generic ViewGroup.LayoutParams.
        final android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        lp.height = editing ? android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        setLayoutParams(lp);
    }

    /** gti.dev: called by LatinIME while the normal keyboard types into the search field. */
    public void appendSearchText(final String text) {
        mSearch.append(text);
    }

    public void deleteSearchText() {
        final Editable e = mSearch.getText();
        if (e != null && e.length() > 0) e.delete(e.length() - 1, e.length());
    }

    public String getSearchText() {
        return mSearch.getText().toString();
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    public void setGifCommitListener(final GifCommitListener listener) {
        mGifCommitListener = listener;
    }

    @Override
    public void onClick(final View v) {
        if (v.getId() == R.id.gif_back_key) {
            mKeyboardActionListener.onCodeInput(Constants.CODE_ALPHA_FROM_EMOJI,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        }
    }

    private final class GifAdapter extends RecyclerView.Adapter<GifAdapter.Vh> {
        final class Vh extends RecyclerView.ViewHolder {
            final ImageView image;
            Vh(final View view) {
                super(view);
                image = (ImageView) view.findViewById(R.id.gif_item_image);
            }
        }

        @Override public Vh onCreateViewHolder(final ViewGroup parent, final int type) {
            final View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.gif_palette_item, parent, false);
            return new Vh(v);
        }

        @Override public void onBindViewHolder(final Vh holder, final int position) {
            final GifJifClient.Gif gif = mGifs.get(position);
            holder.itemView.setOnClickListener(v -> {
                if (mGifCommitListener != null) {
                    mGifCommitListener.onGifSelected(gif.fullUrl, gif.title);
                } else {
                    mKeyboardActionListener.onTextInput(gif.fullUrl + " ");
                }
                mKeyboardActionListener.onCodeInput(Constants.CODE_ALPHA_FROM_EMOJI,
                        Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
            });
            // gti.dev: prefer the small purpose-built thumbnail; fall back to the
            // full source only when no preview is available. Full URLs can be MP4
            // or multi-megabyte GIFs that fail to decode in the grid.
            final String url = gif.previewUrl != null && !gif.previewUrl.isEmpty()
                    ? gif.previewUrl : gif.fullUrl;
            holder.image.setTag(url);
            loadBitmap(holder.image, url, gif.fullUrl);
        }

        @Override public int getItemCount() { return mGifs.size(); }

        private void loadBitmap(final ImageView view, final String url, final String fallbackUrl) {
            final Drawable cached = mCache.get(url);
            if (cached != null) {
                showDrawable(view, url, cached);
                return;
            }
            view.setImageDrawable(mPlaceholder);
            mExecutor.execute(() -> {
                Drawable drawable = download(url);
                if (drawable == null && fallbackUrl != null && !fallbackUrl.equals(url)) {
                    drawable = download(fallbackUrl);
                }
                final Drawable result = drawable;
                if (result != null) mCache.put(url, result);
                mMainHandler.post(() -> showDrawable(view, url,
                        result != null ? result : mPlaceholder));
            });
        }

        private Drawable download(final String url) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "MorrowKeyboard/1.0");
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                final byte[] bytes;
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    bytes = out.toByteArray();
                }
                if (bytes.length == 0) return null;
                return ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
            } catch (Exception | OutOfMemoryError e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private void showDrawable(final ImageView view, final String url, final Drawable drawable) {
            if (!url.equals(view.getTag())) return; // view was recycled, skip stale result
            view.setImageDrawable(drawable);
            if (drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).start();
            }
        }
    }
}
