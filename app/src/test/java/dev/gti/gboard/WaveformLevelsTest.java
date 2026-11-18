package dev.gti.gboard;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for the RMS-driven waveform level model. */
public class WaveformLevelsTest {

    @Test
    public void pushShiftsAndKeepsFixedSize() {
        WaveformLevels w = new WaveformLevels(4, 0f);
        assertEquals(4, w.size());
        w.push(0.1f);
        w.push(0.2f);
        // smoothing 0 => newest bar takes the full normalized value
        assertTrue(w.get(3) > w.get(2));
        assertEquals(0.1f * 8f, w.get(2), 0.001f);
    }

    @Test
    public void loudAudioClampsToOne() {
        WaveformLevels w = new WaveformLevels(2, 0f);
        w.push(1.0f);
        assertEquals(1f, w.get(1), 0.001f);
    }

    @Test
    public void silentAudioStaysAtZero() {
        WaveformLevels w = new WaveformLevels(2, 0f);
        w.push(0f);
        assertEquals(0f, w.get(1), 0.001f);
    }

    @Test
    public void resetFlattensAllBars() {
        WaveformLevels w = new WaveformLevels(3, 0f);
        w.push(0.5f);
        w.reset();
        assertEquals(0f, w.peak(), 0.001f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveBarCount() {
        new WaveformLevels(0);
    }
}
