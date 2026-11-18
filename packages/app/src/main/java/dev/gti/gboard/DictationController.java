package dev.gti.gboard;

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Holds the dictation state machine:
 *   IDLE -> (press mic) LISTENING -> (release mic) TRANSCRIBING -> IDLE
 *
 * While LISTENING it accumulates 16 kHz PCM and periodically runs a *partial*
 * transcription so the UI can show a live transcript (the Gboard "talk and see
 * the words appear" feel). On release it runs a final pass over the whole
 * buffer and emits the committed text.
 */
public final class DictationController {
    public interface Listener {
        void onPartial(String text);
        void onTranscribing();
        void onFinal(String text);
        void onRms(float rms);
        void onListening(boolean listening);
    }

    private static final int PARTIAL_EVERY_MS = 2500;
    private static final int MAX_SECONDS = 30;

    private final Recorder recorder = new Recorder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService transcribeExec = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();

    private short[] pcmBuf = new short[Recorder.SAMPLE_RATE * MAX_SECONDS];
    private int pcmLen = 0;
    private volatile boolean listening;
    private Listener listener;
    private long lastPartialAt = 0;
    private volatile float peakRms = 0f;

    public boolean isListening() { return listening; }

    public void start(Listener l) {
        if (listening) return;
        this.listener = l;
        synchronized (lock) { pcmLen = 0; }
        peakRms = 0f;
        listening = true;
        lastPartialAt = System.currentTimeMillis();
        main.post(() -> { if (listener != null) listener.onListening(true); });
        recorder.start(new Recorder.OnData() {
            @Override public void onData(short[] pcm, int count, float rms) {
                appendPcm(pcm, count);
                if (rms > peakRms) peakRms = rms;
                main.post(() -> { if (listener != null) listener.onRms(rms); });
                maybePartial();
            }
            @Override public void onStopped() { /* handled by stop() */ }
        });
    }

    public void stop() {
        if (!listening) return;
        listening = false;
        recorder.stop();
        main.post(() -> { if (listener != null) listener.onListening(false); });
        final short[] pcm = snapshotPcm();
        final float rms = peakRms;
        main.post(() -> { if (listener != null) listener.onTranscribing(); });
        transcribeExec.submit(() -> {
            // Silence / background noise produces the "[MÚSICA DE FUNDO]"
            // hallucination in Whisper base: skip inference entirely.
            if (pcm.length == 0 || !TranscriptCleaner.hasSpeech(rms)) {
                main.post(() -> { if (listener != null) listener.onFinal(""); });
                return;
            }
            String text = VoiceEngine.transcribe(toFloat(pcm), "pt");
            String t = TranscriptCleaner.clean(text);
            // Post-ASR pass: local dictionary correction (offline, no Google).
            final String corrected = DictionaryCorrector.correct(t);
            final String finalText = TranscriptCleaner.isEmptySpeech(corrected) ? "" : corrected;
            main.post(() -> { if (listener != null) listener.onFinal(finalText); });
        });
    }

    private void appendPcm(short[] chunk, int count) {
        synchronized (lock) {
            if (pcmLen + count > pcmBuf.length) {
                pcmBuf = Arrays.copyOf(pcmBuf, (pcmLen + count) * 2);
            }
            System.arraycopy(chunk, 0, pcmBuf, pcmLen, count);
            pcmLen += count;
        }
    }

    private short[] snapshotPcm() {
        synchronized (lock) { return Arrays.copyOf(pcmBuf, pcmLen); }
    }

    private void maybePartial() {
        long now = System.currentTimeMillis();
        if (now - lastPartialAt < PARTIAL_EVERY_MS) return;
        if (!TranscriptCleaner.hasSpeech(peakRms)) return;
        lastPartialAt = now;
        final short[] pcm = snapshotPcm();
        transcribeExec.submit(() -> {
            String text = VoiceEngine.transcribe(toFloat(pcm), "pt");
            final String t = TranscriptCleaner.clean(text);
            main.post(() -> { if (listener != null) listener.onPartial(t); });
        });
    }

    private static float[] toFloat(short[] pcm) {
        float[] f = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) f[i] = pcm[i] / 32768f;
        return f;
    }

    public void release() {
        stop();
        transcribeExec.shutdown();
    }
}
