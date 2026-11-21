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
                    status.append("\n• Modelo de voz carregado (Whisper base, local).");
                    status.append("\n• Dicionário pt-BR local: " + DictionaryCorrector.size() + " palavras.");
                });
                if (autoWav) {
                    runOnUiThread(this::runWavSelfTest);
                }
            } catch (Exception e) {
                Log.e(TAG, "WAV-SELFTEST: engine init failed: " + e);
                runOnUiThread(() -> status.append("\n• Falha ao carregar modelo: " + e.getMessage()));
            }
        });
    }

    private TextView buildUi() {
        TextView title = new TextView(this);
        title.setText("Gboard · Voz local (offline)");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setPadding(dp(24), dp(24), dp(24), dp(8));

        TextView desc = new TextView(this);
        desc.setText(
            "Teclado no estilo Gboard com ditado por voz 100% local (Whisper embarcado).\n\n" +
            "1. Habilite o teclado nas configurações de entrada.\n" +
            "2. Conceda a permissão de microfone.\n" +
            "3. Use o botão de teste abaixo para validar o STT local.\n\n" +
            "Dica: toque DUAS VEZES rápido na tecla Shift (seta) para ligar o " +
            "CAPS LOCK (tudo em maiúsculas). A seta fica com um traço embaixo " +
            "quando está ligado; toque duas vezes de novo para desligar.\n\n" +
            "Nenhuma chamada é feita ao serviço de voz do Google.");
        desc.setTextColor(Color.parseColor("#DADCE0"));
        desc.setPadding(dp(24), 0, dp(24), dp(16));

        Button enable = new Button(this);
        enable.setText("1) Habilitar teclado");
        enable.setOnClickListener(v -> {
            startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS));
        });

        Button perm = new Button(this);
        perm.setText("2) Conceder microfone");
        perm.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2);
            } else {
                Toast.makeText(this, "Microfone já permitido", Toast.LENGTH_SHORT).show();
            }
            refreshPermission();
        });

        Button test = new Button(this);
        test.setText("3) Testar ditado local (fale ~4 s)");
        test.setOnClickListener(v -> runSelfTest());

        Button wav = new Button(this);
        wav.setText("4) Testar WAV (sem microfone)");
        wav.setOnClickListener(v -> runWavSelfTest());

        EditText campo = new EditText(this);
        campo.setHint("5) Toque aqui para testar o teclado");
        campo.setTextSize(16);
        campo.setPadding(dp(16), dp(12), dp(16), dp(12));

        Button voiceCfg = new Button(this);
        voiceCfg.setText("6) Configurar voz (local / servidor OpenAI)");
        voiceCfg.setOnClickListener(v -> showVoiceConfigDialog());

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#8AB4F8"));
        status.setTextSize(13);
        status.setPadding(dp(24), dp(16), dp(24), dp(24));
        status.setText("Inicializando…");

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
        useExternal.setText("Usar servidor externo (desmarcado = 100% local offline)");
        useExternal.setChecked(prefs.getBoolean(K_USE, false));
        box.addView(useExternal);

        final EditText url = new EditText(this);
        url.setHint("URL base (ex: http://100.84.51.1:8000/v1)");
        url.setText(prefs.getString(K_URL, "http://100.84.51.1:8000/v1"));
        box.addView(url);

        final EditText key = new EditText(this);
        key.setHint("API key (deixe vazio se nao precisar)");
        key.setText(prefs.getString(K_KEY, ""));
        box.addView(key);

        final EditText model = new EditText(this);
        model.setHint("Modelo (ex: whisper-1)");
        model.setText(prefs.getString(K_MODEL, "whisper-1"));
        box.addView(model);

        final EditText lang = new EditText(this);
        lang.setHint("Idioma (pt)");
        lang.setText(prefs.getString(K_LANG, "pt"));
        box.addView(lang);

        final CheckBox diagnostics = new CheckBox(this);
        diagnostics.setText("Gravar diagnostico de swipe (gesto + resultado + palavra que eu digitar)");
        diagnostics.setChecked(prefs.getBoolean("gti_swipe_diagnostics", false));
        box.addView(diagnostics);

        final CheckBox collect = new CheckBox(this);
        collect.setText("Enviar dataset ao coletor (somente build debug)");
        collect.setChecked(prefs.getBoolean("gti_collect_enabled", true));
        box.addView(collect);

        final EditText collectUrl = new EditText(this);
        collectUrl.setHint("URL do coletor (ex: https://morrow.gti.dev)");
        collectUrl.setText(prefs.getString("gti_collect_base_url", "https://morrow.gti.dev"));
        box.addView(collectUrl);

        final EditText collectToken = new EditText(this);
        collectToken.setHint("Token do coletor (X-Collector-Token)");
        collectToken.setText(prefs.getString("gti_collect_token", ""));
        box.addView(collectToken);

        TextView hint = new TextView(this);
        hint.setText("Compativel com OpenAI / localai / faster-whisper-server / Speaches. "
                + "O teclado tenta o servidor e, se falhar, volta para o Whisper local.\n\n"
                + "Diagnostico: salvo apenas no aparelho em "
                + "/data/data/com.android.inputmethod.latin/files/swipe_diagnostics.jsonl\n\n"
                + "Coleta de dataset: somente em build debug, sem consentimento. "
                + "Em release nada e enviado (sem permissao INTERNET).");
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("Voz: backend de transcricao")
                .setView(box)
                .setPositiveButton("Salvar", (d, which) -> {
                    prefs.edit()
                            .putBoolean(K_USE, useExternal.isChecked())
                            .putString(K_URL, url.getText().toString().trim())
                            .putString(K_KEY, key.getText().toString().trim())
                            .putString(K_MODEL, model.getText().toString().trim())
                            .putString(K_LANG, lang.getText().toString().trim())
                            .putBoolean("gti_swipe_diagnostics", diagnostics.isChecked())
                            .putBoolean("gti_collect_enabled", collect.isChecked())
                            .putString("gti_collect_base_url", collectUrl.getText().toString().trim())
                            .putString("gti_collect_token", collectToken.getText().toString().trim())
                            .apply();
                    status.append("\nDiagnostico de swipe: "
                            + (diagnostics.isChecked() ? "ATIVADO" : "desativado") + ".");
                    status.append("\nColeta de dataset (debug): "
                            + (collect.isChecked() ? "ATIVADA" : "desativada") + ".");
                    status.append("\nConfiguracao de voz salva. "
                            + (useExternal.isChecked() ? "Servidor externo ativado (com fallback local)."
                                                       : "Modo local offline ativado."));
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void runSelfTest() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2);
            status.append("\n• Conceda o microfone e tente de novo.");
            return;
        }
        status.append("\n• Gravando 4 s… (fale em português)");
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
                status.append("\n• Reconhecimento local: “" + (r.isEmpty() ? "(vazio)" : r) + "”");
                if (fixed != null && !fixed.equals(r)) {
                    status.append("\n• Com dicionário local: “" + fixed + "”");
                }
                status.append("\n• (Executado no dispositivo, sem Google STT.)");
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
        status.append("\n• Procurando .wav no storage externo do app…");
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
                    runOnUiThread(() -> status.append(
                            "\n• Nenhum .wav. Envie com:\n  adb push x.wav /sdcard/Android/data/dev.gti.gboard/files/"));
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
                    status.append("\n• WAV " + wavFile.getName() + " ("
                            + String.format("%.1f", secs) + " s): inferência "
                            + infer + " ms (RTF " + String.format("%.2f", rtf) + ")");
                    status.append("\n• Reconhecimento local: “" + (r.isEmpty() ? "(vazio)" : r) + "”");
                    if (fixed != null && !fixed.equals(r)) {
                        status.append("\n• Com dicionário local: “" + fixed + "”");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.append("\n• WAV test error: " + e.getMessage()));
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
                status.append("\n• Microfone: " + (ok ? "permitido" : "negado"));
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
