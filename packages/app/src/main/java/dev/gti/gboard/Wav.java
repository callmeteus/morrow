package dev.gti.gboard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Minimal 16-bit PCM WAV reader: accepts any sample rate / channel count and
 * returns 16 kHz mono floats (Whisper's native input). Used by the file-based
 * self-test so the on-device pipeline can be verified without a microphone.
 */
public final class Wav {
    private Wav() {}

    public static float[] to16kMono(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] hdr = new byte[12];
            raf.readFully(hdr);
            if (new String(hdr, 0, 4).compareTo("RIFF") != 0) {
                throw new IOException("not a RIFF file");
            }
            int pos = 12;
            int rate = 0, channels = 0, bits = 0;
            long dataPos = -1, dataLen = -1;
            while (pos + 8 <= raf.length()) {
                raf.seek(pos);
                byte[] ch = new byte[8];
                raf.readFully(ch);
                String id = new String(ch, 0, 4);
                int len = (ch[4] & 0xff) | (ch[5] & 0xff) << 8
                        | (ch[6] & 0xff) << 16 | (ch[7] & 0xff) << 24;
                if (id.equals("fmt ") && len >= 16) {
                    raf.seek(pos + 8);
                    byte[] fm = new byte[len];
                    raf.readFully(fm);
                    channels = (fm[2] & 0xff) | (fm[3] & 0xff) << 8;
                    rate = (fm[4] & 0xff) | (fm[5] & 0xff) << 8
                            | (fm[6] & 0xff) << 16 | (fm[7] & 0xff) << 24;
                    bits = (fm[14] & 0xff) | (fm[15] & 0xff) << 8;
                } else if (id.equals("data")) {
                    dataPos = pos + 8;
                    dataLen = len;
                }
                pos += 8 + len + (len & 1);
            }
            if (rate <= 0 || dataPos < 0 || bits != 16 || channels <= 0) {
                throw new IOException("unsupported WAV (need 16-bit PCM)");
            }
            int n = (int) (Math.min(dataLen, raf.length() - dataPos) / 2 / channels);
            short[] samples = new short[(int) n];
            raf.seek(dataPos);
            byte[] buf = new byte[(int) (n * channels * 2)];
            raf.readFully(buf);
            for (int i = 0; i < n; i++) {
                int s = 0;
                for (int c = 0; c < channels; c++) {
                    int off = (int) (i * channels + c) * 2;
                    s += (short) ((buf[off] & 0xff) | (buf[off + 1] & 0xff) << 8);
                }
                samples[i] = (short) (s / channels);
            }
            if (rate == 16000) {
                float[] out = new float[n];
                for (int i = 0; i < n; i++) out[i] = samples[i] / 32768f;
                return out;
            }
            double ratio = rate / 16000.0;
            int outLen = Math.max(1, (int) (n / ratio));
            float[] out = new float[outLen];
            for (int i = 0; i < outLen; i++) {
                double p = i * ratio;
                int i0 = (int) p;
                int i1 = Math.min(i0 + 1, n - 1);
                float fr = (float) (p - i0);
                out[i] = (samples[i0] * (1f - fr) + samples[i1] * fr) / 32768f;
            }
            return out;
        }
    }

    /** Writes 16 kHz mono 16-bit PCM samples as a canonical 44-byte-header WAV. */
    public static void write16kMono(short[] pcm, File out) throws IOException {
        int sampleCount = pcm == null ? 0 : pcm.length;
        int dataBytes = sampleCount * 2;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write("RIFF".getBytes("US-ASCII"));
            writeLe32(fos, 36 + dataBytes);          // RIFF size
            fos.write("WAVE".getBytes("US-ASCII"));
            fos.write("fmt ".getBytes("US-ASCII"));
            writeLe32(fos, 16);                       // fmt chunk size
            writeLe16(fos, 1);                        // PCM
            writeLe16(fos, 1);                        // mono
            writeLe32(fos, 16000);                    // sample rate
            writeLe32(fos, 16000 * 2);               // byte rate
            writeLe16(fos, 2);                        // block align
            writeLe16(fos, 16);                       // bits per sample
            fos.write("data".getBytes("US-ASCII"));
            writeLe32(fos, dataBytes);
            for (int i = 0; i < sampleCount; i++) {
                writeLe16(fos, pcm[i]);
            }
        }
    }

    private static void writeLe16(FileOutputStream fos, int v) throws IOException {
        fos.write(v & 0xff);
        fos.write((v >> 8) & 0xff);
    }

    private static void writeLe32(FileOutputStream fos, int v) throws IOException {
        fos.write(v & 0xff);
        fos.write((v >> 8) & 0xff);
        fos.write((v >> 16) & 0xff);
        fos.write((v >> 24) & 0xff);
    }
}
