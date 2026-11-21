package dev.gti.gboard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Launcher screen: enables the keyboard, grants the microphone permission, and
 * runs an in-app self-test of the local (on-device) speech recognizer so the
 * user can verify dictation works without Google's speech service.
 */
public class SetupActivity extends AppCompatActivity {

    private static final String TAG = "SetupActivity";
    private TextView status;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private boolean engineReady = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = buildUi();
        refreshPermission();
        final boolean autoWav = getIntent() != null
                && getIntent().getBooleanExtra("wavtest", false);
        if (autoWav) {
            Log.i(TAG, "WAV-SELFTEST: intent flag received, will run after engine init");
        }
        bg.submit(() -> {
            try {
                VoiceEngine.init(this);
                DictionaryCorrector.load(SetupActivity.this);
                engineReady = true;
                Log.i(TAG, "WAV-SELFTEST: engine ready (dict_size=" + DictionaryCorrector.size() + ")");
                runOnUiThread(() -> {
                    status.append(getString(R.string.status_model_loaded));
                    status.append(getString(R.string.status_dictionary_loaded, DictionaryCorrector.size()));
                });
                if (autoWav) {
                    runOnUiThread(this::runWavSelfTest);
                }
            } catch (Exception e) {
                Log.e(TAG, "WAV-SELFTEST: engine init failed: " + e);
                runOnUiThread(() -> status.append(getString(R.string.status_model_error, e.getMessage())));
            }
        });
    }

    private TextView buildUi() {
        TextView title = new TextView(this);
        title.setText(getString(R.string.setup_title));
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setPadding(dp(24), dp(24), dp(24), dp(8));

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.setup_description));
        desc.setTextColor(Color.parseColor("#DADCE0"));
        desc.setPadding(dp(24), 0, dp(24), dp(16));

        Button enable = new Button(this);
        enable.setText(getString(R.string.setup_enable_keyboard));
        enable.setOnClickListener(v -> {
            startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS));
        });

        Button perm = new Button(this);
        perm.setText(getString(R.string.setup_grant_mic));
        perm.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2);
            } else {
                Toast.makeText(this, getString(R.string.setup_mic_already_allowed), Toast.LENGTH_SHORT).show();
            }
            refreshPermission();
        });

        Button test = new Button(this);
        test.setText(getString(R.string.setup_test_dictation));
        test.setOnClickListener(v -> runSelfTest());

        Button wav = new Button(this);
        wav.setText(getString(R.string.setup_test_wav));
        wav.setOnClickListener(v -> runWavSelfTest());

        EditText campo = new EditText(this);
        campo.setHint(getString(R.string.setup_test_field_hint));
        campo.setTextSize(16);
        campo.setPadding(dp(16), dp(12), dp(16), dp(12));

        Button voiceCfg = new Button(this);
        voiceCfg.setText(getString(R.string.setup_configure_voice));
        voiceCfg.setOnClickListener(v -> showVoiceConfigDialog());

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#8AB4F8"));
        status.setTextSize(13);
        status.setPadding(dp(24), dp(16), dp(24), dp(24));
        status.setText(getString(R.string.setup_initializing));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.parseColor("#202124"));
        col.addView(title);
        col.addView(desc);
        col.addView(enable);
        col.addView(perm);
        col.addView(test);
        col.addView(wav);
        col.addView(campo);
        col.addView(voiceCfg);
        col.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setFillViewport(true);
        setContentView(scroll);
        return status;
    }

    /** Dialog to configure the optional OpenAI-compatible STT backend. */
    private void showVoiceConfigDialog() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String K_USE = "gti_voice_use_external";
        final String K_URL = "gti_voice_base_url";
        final String K_KEY = "gti_voice_api_key";
        final String K_MODEL = "gti_voice_model";
        final String K_LANG = "gti_voice_language";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);

        final CheckBox useExternal = new CheckBox(this);
        useExternal.setText(getString(R.string.voice_use_external));
        useExternal.setChecked(prefs.getBoolean(K_USE, false));
        box.addView(useExternal);

        final EditText url = new EditText(this);
        url.setHint(getString(R.string.voice_base_url_hint));
        url.setText(prefs.getString(K_URL, "http://100.84.51.1:8000/v1"));
        box.addView(url);

        final EditText key = new EditText(this);
        key.setHint(getString(R.string.voice_api_key_hint));
        key.setText(prefs.getString(K_KEY, ""));
        box.addView(key);

        final EditText model = new EditText(this);
        model.setHint(getString(R.string.voice_model_hint));
        model.setText(prefs.getString(K_MODEL, "whisper-1"));
        box.addView(model);

        final EditText lang = new EditText(this);
        lang.setHint(getString(R.string.voice_language_hint));
        lang.setText(prefs.getString(K_LANG, "pt"));
        box.addView(lang);

        final CheckBox diagnostics = new CheckBox(this);
        diagnostics.setText(getString(R.string.voice_swipe_diagnostics));
        diagnostics.setChecked(prefs.getBoolean("gti_swipe_diagnostics", false));
        box.addView(diagnostics);

        final CheckBox usageLog = new CheckBox(this);
        usageLog.setText(getString(R.string.voice_usage_log));
        usageLog.setChecked(prefs.getBoolean("gti_usage_log", true));
        box.addView(usageLog);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.voice_config_hint));
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.voice_config_title))
                .setView(box)
                .setPositiveButton(getString(R.string.save), (d, which) -> {
                    prefs.edit()
                            .putBoolean(K_USE, useExternal.isChecked())
                            .putString(K_URL, url.getText().toString().trim())
                            .putString(K_KEY, key.getText().toString().trim())
                            .putString(K_MODEL, model.getText().toString().trim())
                            .putString(K_LANG, lang.getText().toString().trim())
                            .putBoolean("gti_swipe_diagnostics", diagnostics.isChecked())
                            .putBoolean("gti_usage_log", usageLog.isChecked())
                            .apply();
                    status.append(getString(R.string.status_swipe_diagnostics,
                            getString(diagnostics.isChecked() ? R.string.enabled : R.string.disabled)));
                    status.append(getString(R.string.status_usage_log,
                            getString(usageLog.isChecked() ? R.string.enabled : R.string.disabled)));
                    status.append(getString(R.string.status_voice_config_saved,
                            getString(useExternal.isChecked() ? R.string.voice_external_enabled
                                                              : R.string.voice_local_enabled)));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void runSelfTest() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2);
            status.append(getString(R.string.status_grant_mic_retry));
            return;
        }
        status.append(getString(R.string.status_recording));
        final Recorder recorder = new Recorder();
        final short[] buf = new short[Recorder.SAMPLE_RATE * 4];
        final int[] len = {0};
        final CountDownLatch done = new CountDownLatch(1);
        recorder.start(new Recorder.OnData() {
            @Override public void onData(short[] pcm, int count, float rms) {
                int space = buf.length - len[0];
                int n = Math.min(space, count);
                System.arraycopy(pcm, 0, buf, len[0], n);
                len[0] += n;
            }
            @Override public void onStopped() { done.countDown(); }
        });
        bg.submit(() -> {
            try { TimeUnit.SECONDS.sleep(4); } catch (InterruptedException ignored) {}
            recorder.stop();
            try { done.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            final float[] f = new float[len[0]];
            for (int i = 0; i < len[0]; i++) f[i] = buf[i] / 32768f;
            String raw = VoiceEngine.transcribe(f, "pt");
            final String r = raw == null ? "" : raw.trim();
            final String fixed = DictionaryCorrector.correct(r);
            runOnUiThread(() -> {
                status.append(getString(R.string.status_local_recognition,
                        r.isEmpty() ? getString(R.string.empty_transcript) : r));
                if (fixed != null && !fixed.equals(r)) {
                    status.append(getString(R.string.status_corrected, fixed));
                }
                status.append(getString(R.string.status_on_device_note));
            });
        });
    }

    /**
     * File-based self-test: transcribes the newest .wav found in the app's
     * own external files dir (no permissions needed; push it with
     *   adb push x.wav /sdcard/Android/data/dev.gti.gboard/files/
     * ). Exercises the exact same on-device pipeline as dictation
     * (WAV -> 16 kHz mono -> libwhisper.so -> dictionary correction) and
     * reports inference time / RTF, so latency can be verified without a mic.
     */
    private void runWavSelfTest() {
        status.append(getString(R.string.status_searching_wav));
        bg.submit(() -> {
            try {
                File dir = getExternalFilesDir(null);
                File best = null;
                if (dir != null) {
                    File[] ws = dir.listFiles((d, name) ->
                            name.toLowerCase(Locale.ROOT).endsWith(".wav"));
                    if (ws != null) {
                        for (File w : ws) {
                            if (best == null || w.lastModified() > best.lastModified()) best = w;
                        }
                    }
                }
                if (best == null) {
                    runOnUiThread(() -> status.append(getString(R.string.status_no_wav)));
                    return;
                }
                final File wavFile = best;
                final float[] audio = Wav.to16kMono(wavFile);
                final float secs = audio.length / 16000f;
                long t0 = SystemClock.elapsedRealtime();
                String raw = VoiceEngine.transcribe(audio, "pt");
                long t1 = SystemClock.elapsedRealtime();
                String r = raw == null ? "" : raw.trim();
                String fixed = DictionaryCorrector.correct(r);
                long infer = t1 - t0;
                float rtf = secs > 0 ? infer / 1000f / secs : 0f;
                Log.i(TAG, "WAV self-test file=" + best.getName()
                        + " audio_s=" + String.format("%.2f", secs)
                        + " infer_ms=" + infer + " rtf=" + String.format("%.2f", rtf)
                        + " raw=\"" + r + "\" corrected=\"" + fixed + "\"");
                runOnUiThread(() -> {
                    status.append(getString(R.string.status_wav_result,
                            wavFile.getName(), String.format("%.1f", secs), infer,
                            String.format("%.2f", rtf)));
                    status.append(getString(R.string.status_local_recognition,
                        r.isEmpty() ? getString(R.string.empty_transcript) : r));
                    if (fixed != null && !fixed.equals(r)) {
                        status.append(getString(R.string.status_corrected, fixed));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.append(getString(R.string.status_wav_error, e.getMessage())));
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        refreshPermission();
    }

    private void refreshPermission() {
        boolean ok = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        runOnUiThread(() -> {
            if (status != null) {
                status.append(getString(R.string.status_microphone,
                        getString(ok ? R.string.mic_allowed : R.string.mic_denied)));
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        bg.shutdown();
        VoiceEngine.release();
        super.onDestroy();
    }
}
