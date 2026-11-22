package com.nongmay.camera.voice;

/**
 * MFCC front end for the on-device keyword matcher (16 kHz mono PCM16).
 * 25 ms Hamming frames, 10 ms hop, 512-point FFT, 26 mel bands (64–7600 Hz), DCT-II -> c0..c12.
 * Matching features: c1..c12 with utterance-level CMVN plus weighted deltas (24 dims).
 * Pure Java so the exact same code runs in the JVM evaluation harness and on the phone.
 */
public final class Mfcc {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME = 400;
    public static final int HOP = 160;
    public static final int NFFT = 512;
    public static final int NMEL = 26;
    public static final int NCEPS = 13;
    public static final int DIM = 2 * (NCEPS - 1);
    public static final double DELTA_WEIGHT = 0.6;
    private static final double PRE_EMPHASIS = 0.97;
    private static final double LOW_HZ = 64, HIGH_HZ = 7600;

    private final Fft fft = new Fft(NFFT);
    private final double[] window = new double[FRAME];
    private final double[][] melW = new double[NMEL][];
    private final int[] melLo = new int[NMEL];
    private final double[][] dct = new double[NCEPS][NMEL];
    private final double[] frame = new double[FRAME];
    private final double[] pow = new double[NFFT / 2 + 1];
    private final double[] melE = new double[NMEL];

    public Mfcc() {
        for (int i = 0; i < FRAME; i++) window[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1));
        double mLo = hzToMel(LOW_HZ), mHi = hzToMel(HIGH_HZ);
        double[] edges = new double[NMEL + 2];
        for (int i = 0; i < edges.length; i++) edges[i] = melToHz(mLo + (mHi - mLo) * i / (NMEL + 1));
        double binHz = (double) SAMPLE_RATE / NFFT;
        for (int m = 0; m < NMEL; m++) {
            double l = edges[m], c = edges[m + 1], r = edges[m + 2];
            int kLo = (int) Math.floor(l / binHz), kHi = (int) Math.ceil(r / binHz);
            kHi = Math.min(kHi, NFFT / 2);
            double[] w = new double[kHi - kLo + 1];
            for (int k = kLo; k <= kHi; k++) {
                double f = k * binHz;
                double v = 0;
                if (f > l && f <= c) v = (f - l) / (c - l);
                else if (f > c && f < r) v = (r - f) / (r - c);
                w[k - kLo] = v;
            }
            melW[m] = w;
            melLo[m] = kLo;
        }
        for (int c = 0; c < NCEPS; c++) {
            double scale = c == 0 ? Math.sqrt(1.0 / NMEL) : Math.sqrt(2.0 / NMEL);
            for (int m = 0; m < NMEL; m++) dct[c][m] = scale * Math.cos(Math.PI * c * (m + 0.5) / NMEL);
        }
    }

    public static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    public static double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    public static int frameCount(int samples) {
        return samples < FRAME ? 0 : 1 + (samples - FRAME) / HOP;
    }

    /**
     * Mel power of one 25 ms frame starting at pcm[s]; {@code prev} is the sample before it
     * (pre-emphasis). Linear power, 26 bands.
     */
    public void melFrame(short[] pcm, int s, double prev, double[] out) {
        for (int i = 0; i < FRAME; i++) {
            double x = pcm[s + i];
            frame[i] = (x - PRE_EMPHASIS * prev) * window[i] / 32768.0;
            prev = x;
        }
        fft.power(frame, FRAME, pow);
        for (int m = 0; m < NMEL; m++) {
            double[] w = melW[m];
            int lo = melLo[m];
            double e = 0;
            for (int k = 0; k < w.length; k++) e += w[k] * pow[lo + k];
            out[m] = e;
        }
    }

    /** Linear mel power for every 10 ms frame. This (not audio) is what a voice template keeps. */
    public float[][] mel(short[] pcm, int off, int len) {
        int nf = frameCount(len);
        float[][] out = new float[nf][NMEL];
        for (int f = 0; f < nf; f++) {
            int s = off + f * HOP;
            melFrame(pcm, s, s > off ? pcm[s - 1] : pcm[s], melE);
            for (int m = 0; m < NMEL; m++) out[f][m] = (float) melE[m];
        }
        return out;
    }

    /** log -> DCT-II -> c0..c12 for every frame of a (possibly noise-compensated) mel sequence. */
    public double[][] cepstraFromMel(double[][] mel) {
        double[][] out = new double[mel.length][NCEPS];
        for (int f = 0; f < mel.length; f++) {
            for (int m = 0; m < NMEL; m++) melE[m] = Math.log(Math.max(mel[f][m], 1e-12));
            for (int c = 0; c < NCEPS; c++) {
                double v = 0;
                double[] row = dct[c];
                for (int m = 0; m < NMEL; m++) v += row[m] * melE[m];
                out[f][c] = v;
            }
        }
        return out;
    }

    public static double[][] toDouble(float[][] a) {
        double[][] r = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            r[i] = new double[a[i].length];
            for (int j = 0; j < a[i].length; j++) r[i][j] = a[i][j];
        }
        return r;
    }

    /** Static cepstra c0..c12 for every 10 ms frame. */
    public double[][] cepstra(short[] pcm, int off, int len) {
        return cepstraFromMel(toDouble(mel(pcm, off, len)));
    }

    public float[][] features(short[] pcm, int off, int len) {
        return toFeatures(cepstra(pcm, off, len));
    }

    public float[][] featuresFromMel(double[][] mel) {
        return toFeatures(cepstraFromMel(mel));
    }

    /** c1..c12 with per-utterance mean/variance normalisation, then DELTA_WEIGHT * deltas (N = 2). */
    public static float[][] toFeatures(double[][] ceps) {
        int n = ceps.length;
        int d = NCEPS - 1;
        float[][] out = new float[n][2 * d];
        if (n == 0) return out;
        double[] mean = new double[d];
        double[] sd = new double[d];
        for (double[] row : ceps) for (int j = 0; j < d; j++) mean[j] += row[j + 1];
        for (int j = 0; j < d; j++) mean[j] /= n;
        for (double[] row : ceps) for (int j = 0; j < d; j++) { double t = row[j + 1] - mean[j]; sd[j] += t * t; }
        for (int j = 0; j < d; j++) sd[j] = Math.max(Math.sqrt(sd[j] / n), 1e-3);
        double[][] st = new double[n][d];
        for (int t = 0; t < n; t++) for (int j = 0; j < d; j++) st[t][j] = (ceps[t][j + 1] - mean[j]) / sd[j];
        for (int t = 0; t < n; t++) {
            for (int j = 0; j < d; j++) {
                double num = 0;
                for (int k = 1; k <= 2; k++) {
                    num += k * (st[Math.min(t + k, n - 1)][j] - st[Math.max(t - k, 0)][j]);
                }
                out[t][j] = (float) st[t][j];
                out[t][d + j] = (float) (DELTA_WEIGHT * num / 10.0);
            }
        }
        return out;
    }
}
