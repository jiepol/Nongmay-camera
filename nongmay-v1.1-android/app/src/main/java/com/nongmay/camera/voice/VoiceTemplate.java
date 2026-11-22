package com.nongmay.camera.voice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One trigger phrase ("น้องเม", "โฮ่ง ๆ", "cheese", ...) learned from the owner's own voice.
 * Holds 2–5 takes as 26-band mel power envelopes plus the background level at that moment
 * (never raw audio), and a threshold calibrated from how similar the owner's own repetitions are.
 */
public final class VoiceTemplate {
    public static final int STRICT = 0, NORMAL = 1, SENSITIVE = 2;
    /** Multipliers on the calibrated base distance; tuned with tools/voice_eval (see VOICE_EVAL.md). */
    public static final double[] SENSITIVITY_MULT = {1.10, 1.20, 1.35};   // 1.1.4: tighter after a real false trigger
    static final double DEFAULT_BASE = 6.5;
    static final double MIN_BASE = 1.0;
    private static final int MAGIC = 0x4E4D5654; // "NMVT"
    private static final int VERSION = 3;   // 3 = adds "not this" samples
    /** Sounds the owner marked as false triggers (e.g. a loud “เฮ้ย”); newest kept. */
    public static final int MAX_ANTI = 12;

    public String id;
    public String text;
    public int sensitivity = NORMAL;
    public long createdAt;
    public double base = DEFAULT_BASE;
    public double pairMean = Double.NaN, pairMax = Double.NaN;
    public final List<float[][]> mel = new ArrayList<>();
    public final List<float[]> noise = new ArrayList<>();
    public final List<Integer> durMs = new ArrayList<>();
    private final List<float[][]> antiMel = new ArrayList<>();
    private final List<float[]> antiNoise = new ArrayList<>();

    public VoiceTemplate(String id, String text) {
        this.id = id;
        this.text = text;
        this.createdAt = System.currentTimeMillis();
    }

    public int size() {
        return mel.size();
    }

    public boolean isReady() {
        return mel.size() >= 2;
    }

    /** Adds one accepted enrollment segment (mel envelope + background + duration). */
    public void add(KwsCore.Event e) {
        add(e.mel, e.noise, e.durMs);
    }

    public void add(float[][] m, float[] n, int dur) {
        mel.add(m);
        noise.add(n == null ? new float[Mfcc.NMEL] : n);
        durMs.add(dur);
    }

    /** Remembers a sound that wrongly triggered (mel envelope + background only, never audio). */
    public synchronized void addAnti(float[][] m, float[] n) {
        if (m == null || m.length == 0) return;
        antiMel.add(m);
        antiNoise.add(n == null ? new float[Mfcc.NMEL] : n);
        while (antiMel.size() > MAX_ANTI) {
            antiMel.remove(0);
            antiNoise.remove(0);
        }
    }

    public synchronized void clearAnti() {
        antiMel.clear();
        antiNoise.clear();
    }

    public synchronized int antiCount() {
        return antiMel.size();
    }

    /** Snapshot for the audio thread: [0] = mel list, [1] = noise list. */
    @SuppressWarnings("unchecked")
    public synchronized List<?>[] antiSnapshot() {
        return new List<?>[]{new ArrayList<>(antiMel), new ArrayList<>(antiNoise)};
    }

    public void removeLast() {
        if (mel.isEmpty()) return;
        mel.remove(mel.size() - 1);
        noise.remove(noise.size() - 1);
        durMs.remove(durMs.size() - 1);
    }

    public double avgDurMs() {
        if (durMs.isEmpty()) return 0;
        double s = 0;
        for (int d : durMs) s += d;
        return s / durMs.size();
    }

    public double threshold() {
        int s = Math.max(0, Math.min(SENSITIVITY_MULT.length - 1, sensitivity));
        return base * SENSITIVITY_MULT[s];
    }

    /** Sets {@link #base} from pairwise distances between the owner's own takes (same matcher as live). */
    public void calibrate(KwsCore.Config cfg) {
        Mfcc mfcc = new Mfcc();
        int n = mel.size();
        if (n < 2) {
            base = DEFAULT_BASE;
            pairMean = pairMax = Double.NaN;
            return;
        }
        double sum = 0, max = 0;
        int c = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Matcher.distance(mfcc, mel.get(i), noise.get(i), mel.get(j), noise.get(j), cfg);
                if (Double.isInfinite(d)) continue;
                sum += d;
                max = Math.max(max, d);
                c++;
            }
        }
        if (c == 0) {
            base = DEFAULT_BASE;
            return;
        }
        pairMean = sum / c;
        pairMax = max;
        base = Math.max(MIN_BASE, pairMean + 0.5 * (pairMax - pairMean));
    }

    /** Pairwise consistency check used by the enrollment UI (true = samples agree well enough). */
    public boolean consistent() {
        return Double.isNaN(pairMax) || pairMax <= 2.2 * Math.max(pairMean, 1e-6);
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(id);
            out.writeUTF(text);
            out.writeInt(sensitivity);
            out.writeLong(createdAt);
            out.writeDouble(base);
            out.writeDouble(pairMean);
            out.writeDouble(pairMax);
            out.writeInt(mel.size());
            for (int i = 0; i < mel.size(); i++) {
                float[][] f = mel.get(i);
                out.writeInt(durMs.get(i));
                out.writeInt(f.length);
                out.writeInt(f.length == 0 ? Mfcc.NMEL : f[0].length);
                for (float[] row : f) for (float v : row) out.writeFloat(v);
                float[] nz = noise.get(i);
                for (int m = 0; m < Mfcc.NMEL; m++) out.writeFloat(nz[m]);
            }
            synchronized (this) {
                out.writeInt(antiMel.size());
                for (int i = 0; i < antiMel.size(); i++) {
                    float[][] f = antiMel.get(i);
                    out.writeInt(f.length);
                    for (float[] row : f) for (float v : row) out.writeFloat(v);
                    for (int m = 0; m < Mfcc.NMEL; m++) out.writeFloat(antiNoise.get(i)[m]);
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static VoiceTemplate fromBytes(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != MAGIC) throw new IOException("not a voice template");
        int ver = in.readInt();
        if (ver != 2 && ver != VERSION) throw new IOException("unsupported template version " + ver);
        VoiceTemplate t = new VoiceTemplate(in.readUTF(), in.readUTF());
        t.sensitivity = in.readInt();
        t.createdAt = in.readLong();
        t.base = in.readDouble();
        t.pairMean = in.readDouble();
        t.pairMax = in.readDouble();
        int n = in.readInt();
        if (n < 0 || n > 16) throw new IOException("bad sample count");
        for (int i = 0; i < n; i++) {
            int dur = in.readInt();
            int frames = in.readInt();
            int dim = in.readInt();
            if (frames < 0 || frames > 2000 || dim != Mfcc.NMEL) throw new IOException("bad sample shape");
            float[][] f = new float[frames][dim];
            for (int r = 0; r < frames; r++) for (int c = 0; c < dim; c++) f[r][c] = in.readFloat();
            float[] nz = new float[Mfcc.NMEL];
            for (int m = 0; m < nz.length; m++) nz[m] = in.readFloat();
            t.add(f, nz, dur);
        }
        if (ver >= 3) {
            int a = in.readInt();
            if (a < 0 || a > MAX_ANTI) throw new IOException("bad anti count");
            for (int i = 0; i < a; i++) {
                int frames = in.readInt();
                if (frames < 0 || frames > 2000) throw new IOException("bad anti shape");
                float[][] f = new float[frames][Mfcc.NMEL];
                for (int r = 0; r < frames; r++) for (int c = 0; c < Mfcc.NMEL; c++) f[r][c] = in.readFloat();
                float[] nz = new float[Mfcc.NMEL];
                for (int m = 0; m < nz.length; m++) nz[m] = in.readFloat();
                t.addAnti(f, nz);
            }
        }
        return t;
    }

    @Override
    public String toString() {
        return "VoiceTemplate{" + text + ", n=" + mel.size() + ", base=" + base + ", dur=" + Arrays.toString(durMs.toArray()) + "}";
    }
}
