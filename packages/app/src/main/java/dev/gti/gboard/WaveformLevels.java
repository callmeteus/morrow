package dev.gti.gboard;

/**
 * Pure, unit-testable waveform level model. Maps a stream of real RMS samples
 * from the microphone into a fixed set of smoothed bar heights, so the
 * waveform reflects actual voice energy instead of a random animation.
 * No Android dependencies.
 */
public final class WaveformLevels {
    private final float[] levels;
    private final float smoothing;

    public WaveformLevels(int bars) {
        this(bars, 0.5f);
    }

    public WaveformLevels(int bars, float smoothing) {
        if (bars <= 0) throw new IllegalArgumentException("bars must be > 0");
        this.levels = new float[bars];
        this.smoothing = Math.max(0f, Math.min(1f, smoothing));
    }

    public int size() { return levels.length; }

    public float get(int i) { return levels[i]; }

    /**
     * Pushes one real RMS sample. The newest bar takes the value; older bars
     * shift left, producing a scrolling waveform driven by actual audio energy.
     *
     * @param rms root-mean-square amplitude of the current audio frame
     */
    public void push(float rms) {
        // Normalize a plausible voice RMS range into 0..1.
        float norm = Math.max(0f, Math.min(1f, rms * 8f));
        for (int i = 0; i < levels.length - 1; i++) {
            levels[i] = levels[i + 1];
        }
        float last = levels.length > 0 ? levels[levels.length - 1] : 0f;
        levels[levels.length - 1] = smoothing * last + (1f - smoothing) * norm;
    }

    /** Flattens all bars to the idle height. */
    public void reset() {
        for (int i = 0; i < levels.length; i++) levels[i] = 0f;
    }

    /** Highest bar, useful for tests and UI scaling. */
    public float peak() {
        float p = 0f;
        for (float l : levels) if (l > p) p = l;
        return p;
    }
}
