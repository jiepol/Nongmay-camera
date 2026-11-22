package com.nongmay.camera.perf;

import java.util.Arrays;
import java.util.Locale;

/** Percentile summaries for latency reports (pure Java, linear interpolation like numpy default). */
public final class Stats {
    public final int n;
    public final double min, max, mean, p50, p95;

    private Stats(int n, double min, double max, double mean, double p50, double p95) {
        this.n = n; this.min = min; this.max = max; this.mean = mean; this.p50 = p50; this.p95 = p95;
    }

    public static Stats of(double[] values) {
        if (values == null || values.length == 0) return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        double[] v = values.clone();
        Arrays.sort(v);
        double sum = 0;
        for (double d : v) sum += d;
        return new Stats(v.length, v[0], v[v.length - 1], sum / v.length, percentile(v, 50), percentile(v, 95));
    }

    /** values must be sorted ascending. */
    public static double percentile(double[] sorted, double pct) {
        if (sorted.length == 0) return Double.NaN;
        if (sorted.length == 1) return sorted[0];
        double rank = pct / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        double frac = rank - lo;
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
    }

    public String row(String unit) {
        if (n == 0) return "n=0 (NOT MEASURED)";
        return String.format(Locale.ROOT, "n=%d  p50=%.1f%s  p95=%.1f%s  min=%.1f  max=%.1f", n, p50, unit, p95, unit, min, max);
    }
}
