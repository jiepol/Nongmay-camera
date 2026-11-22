package com.nongmay.camera.util;

import java.util.Locale;

/** Small formatting helpers shared by UI and reports (pure Java). */
public final class Fmt {
    private Fmt() {}

    public static String bytes(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    public static String bitrate(long bps) {
        if (bps <= 0) return "?";
        if (bps >= 1_000_000) return String.format(Locale.ROOT, "%.1f Mbps", bps / 1e6);
        return String.format(Locale.ROOT, "%d kbps", Math.round(bps / 1e3));
    }

    public static String durationUs(long us) {
        if (us < 0) return "?";
        long totalMs = us / 1000;
        long m = totalMs / 60000;
        double s = (totalMs % 60000) / 1000.0;
        if (m > 0) return String.format(Locale.ROOT, "%d:%04.1f", m, s);
        return String.format(Locale.ROOT, "%.2f s", s);
    }

    public static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    public static String ms(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 100) return String.format(Locale.ROOT, "%.0f ms", v);
        return String.format(Locale.ROOT, "%.1f ms", v);
    }

    public static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    public static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    public static String mp(long w, long h) {
        return String.format(Locale.ROOT, "%.1fMP", w * h / 1e6);
    }
}
