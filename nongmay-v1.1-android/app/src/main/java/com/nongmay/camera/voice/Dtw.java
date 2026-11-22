package com.nongmay.camera.voice;

import java.util.Arrays;

/**
 * Symmetric dynamic time warping (Sakoe–Chiba weights 1-2-1) with a slope-adjusted band.
 * Returns accumulated Euclidean frame cost divided by (n + m), i.e. an average per-frame distance.
 */
public final class Dtw {
    private Dtw() {}

    public static double distance(float[][] a, float[][] b, double bandRatio) {
        int n = a.length, m = b.length;
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY;
        if (n > 3 * m || m > 3 * n) return Double.POSITIVE_INFINITY;
        int w = Math.max(2, (int) Math.ceil(bandRatio * Math.max(n, m)));
        final double inf = Double.POSITIVE_INFINITY;
        double[] prev = new double[m];
        double[] cur = new double[m];
        Arrays.fill(prev, inf);
        for (int i = 0; i < n; i++) {
            Arrays.fill(cur, inf);
            int center = n == 1 ? 0 : (int) Math.round((double) i * (m - 1) / (n - 1));
            int lo = Math.max(0, center - w), hi = Math.min(m - 1, center + w);
            for (int j = lo; j <= hi; j++) {
                double d = frameDist(a[i], b[j]);
                double best;
                if (i == 0 && j == 0) {
                    best = 2 * d;
                } else {
                    double up = i > 0 ? prev[j] + d : inf;
                    double left = j > 0 ? cur[j - 1] + d : inf;
                    double diag = (i > 0 && j > 0) ? prev[j - 1] + 2 * d : inf;
                    best = Math.min(diag, Math.min(up, left));
                }
                cur[j] = best;
            }
            double[] t = prev; prev = cur; cur = t;
        }
        double total = prev[m - 1];
        return total == inf ? inf : total / (n + m);
    }

    static double frameDist(float[] x, float[] y) {
        double s = 0;
        for (int k = 0; k < x.length; k++) {
            double t = x[k] - y[k];
            s += t * t;
        }
        return Math.sqrt(s);
    }
}
