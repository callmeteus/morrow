/*
 * gti.dev: Gboard-style GIF search panel backed by the keyless GifJif API.
 */

package com.android.inputmethod.latin.gif;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private EditText mSearch;
    private TextView mStatus;

    public GifPaletteView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSearch = findViewById(R.id.gif_search);
        mStatus = findViewById(R.id.gif_status);
        final RecyclerView grid = findViewById(R.id.gif_grid);
        grid.setLayoutManager(new GridLayoutManager(getContext(), 3));
        grid.setAdapter(mAdapter);
        findViewById(R.id.gif_back_key).setOnClickListener(this);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { load(s.toString()); }
        });
        mMainHandler.postDelayed(() -> load("happy"), 250);
    }

    private void load(final String query) {
        final String q = query.trim().isEmpty() ? "happy" : query.trim();
        mStatus.setText(R.string.gif_searching);
        mExecutor.execute(() -> {
            final List<GifJifClient.Gif> found = GifJifClient.search(q);
            mMainHandler.post(() -> {
                mGifs.clear();
                mGifs.addAll(found);
                mAdapter.notifyDataSetChanged();
                mStatus.setText(found.isEmpty()
                        ? getResources().getString(R.string.gif_no_results) : "");
            });
        });
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
            loadBitmap(holder.image, gif.previewUrl);
        }

        @Override public int getItemCount() { return mGifs.size(); }

        private void loadBitmap(final ImageView view, final String url) {
            view.setImageBitmap(null);
            mExecutor.execute(() -> {
                Bitmap bmp = null;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("User-Agent", "MorrowKeyboard/1.0");
                    if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        try (InputStream in = conn.getInputStream()) {
                            bmp = BitmapFactory.decodeStream(in);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final Bitmap result = bmp;
                mMainHandler.post(() -> view.setImageBitmap(result));
            });
        }
    }
}
