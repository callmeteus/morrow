package dev.gti.gboard;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Minimal microphone capture: 16 kHz mono 16-bit PCM, the native input format
 * for Whisper. Data callbacks fire on the capture thread.
 */
public final class Recorder {
    public static final int SAMPLE_RATE = 16000;

    public interface OnData {
        void onData(short[] pcm, int count, float rms);
        void onStopped();
    }

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private OnData listener;

    public synchronized void start(OnData l) {
        if (running) return;
        this.listener = l;
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 16000;
        int bufSize = minBuf * 4; // ~250 ms of headroom
        record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            throw new IllegalStateException("AudioRecord failed to initialize");
        }
        running = true;
        record.startRecording();
        thread = new Thread(() -> {
            short[] chunk = new short[bufSize / 2];
            while (running) {
                int read;
                try {
                    read = record.read(chunk, 0, chunk.length);
                } catch (Exception e) {
                    break;
                }
                if (read <= 0) break;
                double sum = 0;
                for (int i = 0; i < read; i++) {
                    double v = chunk[i] / 32768.0;
                    sum += v * v;
                }
                float rms = (float) Math.sqrt(sum / read);
                OnData l2 = listener;
                if (l2 != null) l2.onData(chunk, read, rms);
            }
            OnData l3 = listener;
            if (l3 != null) l3.onStopped();
        }, "mic-capture");
        thread.start();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        Thread t = thread;
        if (t != null) {
            try { t.join(600); } catch (InterruptedException ignored) {}
        }
        AudioRecord r = record;
        if (r != null) {
            try { r.stop(); } catch (IllegalStateException ignored) {}
            r.release();
        }
        record = null;
        thread = null;
    }

    public boolean isRunning() { return running; }
}
