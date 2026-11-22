package com.nongmay.camera.voice;

/**
 * Distance between a spoken segment and one recorded take of the owner's phrase.
 *
 * Noise-compensated matching (parallel-model-combination style, all in the mel power domain):
 * both sides are rebuilt as "clean speech estimate + the same background noise" before MFCC,
 * so a phrase taught in a quiet room can still match on a street, and vice versa:
 *
 *   gain      = speech level of the segment / speech level of the take
 *   target[m] = max(noise_segment[m], gain * noise_take[m])
 *   segment'  = max(segment - noise_segment, 0) + target
 *   take'     = max(gain * (take - noise_take), 0) + target
 *
 * then log -> DCT -> CMVN -> deltas -> DTW. Pure Java; the JVM evaluation runs this exact code.
 */
public final class Matcher {
    private Matcher() {}

    public static double distance(Mfcc mfcc, float[][] segMel, float[] segNoise, float[][] takeMel, float[] takeNoise,
                                  KwsCore.Config cfg) {
        return distance(mfcc, segMel, segNoise, takeMel, takeNoise, cfg, cfg.room == 2);
    }

    /** @param roomTake pass the take's clean part through a virtual room (late reverb in the mel power domain). */
    public static double distance(Mfcc mfcc, float[][] segMel, float[] segNoise, float[][] takeMel, float[] takeNoise,
                                  KwsCore.Config cfg, boolean roomTake) {
        if (segMel == null || takeMel == null || segMel.length == 0 || takeMel.length == 0) return Double.POSITIVE_INFINITY;
        int nb = Mfcc.NMEL;
        double[][] a, b;
        if (cfg.compensate) {
            double ls = speechLevel(segMel, segNoise), lt = speechLevel(takeMel, takeNoise);
            double gain = lt > 0 && ls > 0 ? ls / lt : 1.0;
            double[] target = new double[nb];
            for (int m = 0; m < nb; m++) target[m] = Math.max(at(segNoise, m), gain * at(takeNoise, m));
            boolean roomSeg = cfg.room == 2;
            a = rebuild(segMel, segNoise, 1.0, target, roomSeg ? cfg : null);
            b = rebuild(takeMel, takeNoise, gain, target, roomTake ? cfg : null);
            if (cfg.noiseClamp > 1.0) {
                for (int m = 0; m < nb; m++) target[m] *= cfg.noiseClamp;
                clampBands(a, target);
                clampBands(b, target);
            }
        } else {
            a = Mfcc.toDouble(segMel);
            b = Mfcc.toDouble(takeMel);
            if (roomTake) b = room(b, cfg.roomRt60, cfg.roomDrrDb);
            if (cfg.room == 2) a = room(a, cfg.roomRt60, cfg.roomDrrDb);
        }
        if (cfg.relFloorDb > 0) {
            double max = 0;
            for (double[] r : a) for (double v : r) max = Math.max(max, v);
            double fl = max * Math.pow(10.0, -cfg.relFloorDb / 10.0);
            floor(a, fl);
            floor(b, fl);
        }
        return Dtw.distance(mfcc.featuresFromMel(a), mfcc.featuresFromMel(b), cfg.band);
    }

    static double at(float[] v, int i) {
        return v == null ? 0.0 : v[i];
    }

    static double[][] rebuild(float[][] mel, float[] noise, double gain, double[] target, KwsCore.Config roomCfg) {
        double[][] out = new double[mel.length][Mfcc.NMEL];
        for (int f = 0; f < mel.length; f++) {
            for (int m = 0; m < Mfcc.NMEL; m++) {
                double clean = mel[f][m] - at(noise, m);
                out[f][m] = clean > 0 ? gain * clean : 0.0;
            }
        }
        if (roomCfg != null) out = room(out, roomCfg.roomRt60, roomCfg.roomDrrDb);
        for (double[] r : out) for (int m = 0; m < Mfcc.NMEL; m++) r[m] += target[m];
        return out;
    }

    static void clampBands(double[][] x, double[] fl) {
        for (double[] r : x) for (int m = 0; m < r.length; m++) if (r[m] < fl[m]) r[m] = fl[m];
    }

    /**
     * Late reverberation in the 10 ms mel power domain: direct sound plus an exponentially decaying
     * diffuse tail (power falls 60 dB in rt60, tail/direct energy = -drrDb).
     */
    public static double[][] room(double[][] x, double rt60, double drrDb) {
        double decay = Math.exp(-13.8155 * (Mfcc.HOP / (double) Mfcc.SAMPLE_RATE) / rt60);
        double c = Math.pow(10.0, -drrDb / 10.0) * (1.0 - decay) / decay;
        double[][] y = new double[x.length][];
        double[] tail = new double[x.length == 0 ? 0 : x[0].length];
        for (int f = 0; f < x.length; f++) {
            y[f] = new double[x[f].length];
            for (int m = 0; m < x[f].length; m++) {
                if (f > 0) tail[m] = decay * (tail[m] + x[f - 1][m]);
                y[f][m] = x[f][m] + c * tail[m];
            }
        }
        return y;
    }

    static void floor(double[][] x, double fl) {
        for (double[] r : x) for (int m = 0; m < r.length; m++) if (r[m] < fl) r[m] = fl;
    }

    /** Mean noise-free mel power of the louder half of the frames (robust "how loud was the speech"). */
    public static double speechLevel(float[][] mel, float[] noise) {
        int n = mel.length;
        if (n == 0) return 0;
        double[] e = new double[n];
        for (int f = 0; f < n; f++) {
            double s = 0;
            for (int m = 0; m < Mfcc.NMEL; m++) {
                double c = mel[f][m] - at(noise, m);
                if (c > 0) s += c;
            }
            e[f] = s;
        }
        java.util.Arrays.sort(e);
        double sum = 0;
        int c = 0;
        for (int f = n / 2; f < n; f++) {
            sum += e[f];
            c++;
        }
        return c == 0 ? 0 : sum / c;
    }
}
