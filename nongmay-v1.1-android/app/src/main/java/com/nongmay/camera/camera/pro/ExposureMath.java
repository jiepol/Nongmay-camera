package com.nongmay.camera.camera.pro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exposure / focus / lens unit conversions used by the Pro deck and reports (pure Java). */
public final class ExposureMath {
    private ExposureMath() {}

    /** Standard 1/3-stop shutter values in seconds, plus the 180°-rule video speeds. */
    private static final double[] SHUTTERS = {
            1 / 16000.0, 1 / 12800.0, 1 / 10000.0, 1 / 8000.0, 1 / 6400.0, 1 / 5000.0, 1 / 4000.0, 1 / 3200.0,
            1 / 2500.0, 1 / 2000.0, 1 / 1600.0, 1 / 1250.0, 1 / 1000.0, 1 / 800.0, 1 / 640.0, 1 / 500.0,
            1 / 400.0, 1 / 320.0, 1 / 250.0, 1 / 240.0, 1 / 200.0, 1 / 160.0, 1 / 125.0, 1 / 120.0, 1 / 100.0,
            1 / 96.0, 1 / 80.0, 1 / 60.0, 1 / 50.0, 1 / 48.0, 1 / 40.0, 1 / 30.0, 1 / 25.0, 1 / 20.0,
            1 / 15.0, 1 / 13.0, 1 / 10.0, 1 / 8.0, 1 / 6.0, 1 / 5.0, 1 / 4.0, 0.3, 0.4, 0.5, 0.6, 0.8,
            1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30};

    private static final int[] ISOS = {25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000,
            40000, 51200, 64000, 102400};

    /** Shutter speeds (ns) available inside [minNs, maxNs]; always includes the range ends. */
    public static long[] shutterStops(long minNs, long maxNs) {
        List<Long> out = new ArrayList<>();
        if (minNs <= 0 || maxNs < minNs) return new long[0];
        out.add(minNs);
        for (double s : SHUTTERS) {
            long ns = Math.round(s * 1e9);
            if (ns > minNs * 1.02 && ns < maxNs * 0.98) out.add(ns);
        }
        if (maxNs > minNs) out.add(maxNs);
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** ISO stops inside [min, max] including the ends. */
    public static int[] isoStops(int min, int max) {
        List<Integer> out = new ArrayList<>();
        if (min <= 0 || max < min) return new int[0];
        out.add(min);
        for (int iso : ISOS) if (iso > min * 1.04 && iso < max * 0.96) out.add(iso);
        if (max > min) out.add(max);
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    public static int nearestIndex(long[] values, long v) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            double d = Math.abs(Math.log((double) values[i] / Math.max(1, v)));
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    public static int nearestIndex(int[] values, int v) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            double d = Math.abs(Math.log((double) values[i] / Math.max(1, v)));
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** "1/120", "1/8000", "0.5s", "2s", "1.3s". */
    public static String shutter(long ns) {
        if (ns <= 0) return "?";
        double s = ns / 1e9;
        if (s >= 0.29) {
            if (Math.abs(s - Math.round(s)) < 0.02) return Math.round(s) + "s";
            return String.format(Locale.ROOT, "%.1fs", s);
        }
        double inv = 1.0 / s;
        long d;
        if (inv < 20) d = Math.round(inv);
        else if (inv < 200) d = Math.round(inv);
        else d = Math.round(inv / 10.0) * 10;
        return "1/" + d;
    }

    /** Shutter angle in degrees for a given exposure and frame rate (180° = exposure 1/(2·fps)). */
    public static double shutterAngle(long exposureNs, double fps) {
        return 360.0 * exposureNs / 1e9 * fps;
    }

    /** Exposure (ns) that keeps the same image brightness when ISO changes from isoA to isoB. */
    public static long equivalentExposure(long exposureNs, int isoA, int isoB) {
        if (isoB <= 0) return exposureNs;
        return Math.round((double) exposureNs * isoA / isoB);
    }

    /** EV100 = log2(N²/t) − log2(ISO/100). */
    public static double ev100(long exposureNs, int iso, double fNumber) {
        double t = exposureNs / 1e9;
        return Math.log(fNumber * fNumber / t) / Math.log(2) - Math.log(iso / 100.0) / Math.log(2);
    }

    /** 35 mm-equivalent focal length from physical focal length and sensor size (diagonal ratio). */
    public static double equivalentFocal(double focalMm, double sensorWmm, double sensorHmm) {
        double diag = Math.hypot(sensorWmm, sensorHmm);
        if (diag <= 0) return Double.NaN;
        return focalMm * 43.2666 / diag;
    }

    /** "∞", "1.2 m", "35 cm" from diopters (1/m). */
    public static String focusDistance(double diopters) {
        if (diopters <= 0.001) return "∞";
        double m = 1.0 / diopters;
        if (m >= 10) return String.format(Locale.ROOT, "%.0f m", m);
        if (m >= 1) return String.format(Locale.ROOT, "%.1f m", m);
        return String.format(Locale.ROOT, "%.0f cm", m * 100);
    }

    public static String zoomLabel(double ratio) {
        if (Math.abs(ratio - Math.round(ratio)) < 0.05) return Math.round(ratio) + "×";
        return String.format(Locale.ROOT, "%.1f×", ratio);
    }

    public static String evLabel(int steps, double stepSize) {
        double ev = steps * stepSize;
        if (Math.abs(ev) < 1e-6) return "0";
        return String.format(Locale.ROOT, "%+.1f", ev);
    }

    /** Clamp an exposure to what fits in one frame at the given fps (video cannot exceed 1/fps). */
    public static long clampToFrame(long exposureNs, double fps) {
        if (fps <= 0) return exposureNs;
        long frame = (long) (1e9 / fps);
        return Math.min(exposureNs, frame);
    }
}
