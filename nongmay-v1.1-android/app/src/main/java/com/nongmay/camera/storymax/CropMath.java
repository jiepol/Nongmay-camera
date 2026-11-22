package com.nongmay.camera.storymax;

/**
 * 9:16 framing math for StoryMax (pure Java, unit-tested).
 * "Oriented" coordinates = how the photo/video is meant to be viewed (EXIF / rotation applied).
 * "Raw" coordinates = how pixels are stored in the file (what BitmapRegionDecoder and decoders see).
 */
public final class CropMath {
    public static final int FILL = 0;
    public static final int FIT = 1;
    public static final int REFRAME = 2;
    public static final double STORY_ASPECT = 9.0 / 16.0;

    private CropMath() {}

    /**
     * Source window (oriented pixels, {l,t,r,b}) that fills the output in FILL/REFRAME mode.
     * zoom ≥ 1 (1 = largest window that covers the canvas), (cx, cy) = wanted centre in [0,1].
     * The window always stays inside the image, so nothing is stretched and no empty bars appear.
     */
    public static double[] coverRect(int srcW, int srcH, double outAspect, double zoom, double cx, double cy) {
        double cw, ch;
        if ((double) srcW / srcH > outAspect) {
            ch = srcH;
            cw = ch * outAspect;
        } else {
            cw = srcW;
            ch = cw / outAspect;
        }
        double z = Math.max(1.0, zoom);
        cw /= z;
        ch /= z;
        double x = clamp(cx * srcW - cw / 2, 0, srcW - cw);
        double y = clamp(cy * srcH - ch / 2, 0, srcH - ch);
        return new double[]{x, y, x + cw, y + ch};
    }

    /** Where the whole image lands inside the output in FIT mode (letterbox/pillarbox). */
    public static double[] fitRect(int srcW, int srcH, int outW, int outH) {
        double s = Math.min((double) outW / srcW, (double) outH / srcH);
        double w = srcW * s, h = srcH * s;
        double x = (outW - w) / 2, y = (outH - h) / 2;
        return new double[]{x, y, x + w, y + h};
    }

    /** Fraction of the original image area kept by a FILL window (for the "cropped X%" hint). */
    public static double keptFraction(double[] rect, int srcW, int srcH) {
        return (rect[2] - rect[0]) * (rect[3] - rect[1]) / ((double) srcW * srcH);
    }

    public static boolean swapsAxes(int exifOrientation) {
        return exifOrientation >= 5 && exifOrientation <= 8;
    }

    public static int[] orientedSize(int rawW, int rawH, int exifOrientation) {
        return swapsAxes(exifOrientation) ? new int[]{rawH, rawW} : new int[]{rawW, rawH};
    }

    /** EXIF orientation (1..8) for a container rotation in degrees (video rotation metadata). */
    public static int orientationForRotation(int degrees) {
        switch (((degrees % 360) + 360) % 360) {
            case 90: return 6;
            case 180: return 3;
            case 270: return 8;
            default: return 1;
        }
    }

    /** Raw -> oriented point. */
    public static double[] rawToOriented(double rx, double ry, int o, int rawW, int rawH) {
        switch (o) {
            case 2: return new double[]{rawW - rx, ry};
            case 3: return new double[]{rawW - rx, rawH - ry};
            case 4: return new double[]{rx, rawH - ry};
            case 5: return new double[]{ry, rx};
            case 6: return new double[]{rawH - ry, rx};
            case 7: return new double[]{rawH - ry, rawW - rx};
            case 8: return new double[]{ry, rawW - rx};
            default: return new double[]{rx, ry};
        }
    }

    /** Oriented -> raw point (inverse of {@link #rawToOriented}). */
    public static double[] orientedToRaw(double x, double y, int o, int rawW, int rawH) {
        switch (o) {
            case 2: return new double[]{rawW - x, y};
            case 3: return new double[]{rawW - x, rawH - y};
            case 4: return new double[]{x, rawH - y};
            case 5: return new double[]{y, x};
            case 6: return new double[]{y, rawH - x};
            case 7: return new double[]{rawW - y, rawH - x};
            case 8: return new double[]{rawW - y, x};
            default: return new double[]{x, y};
        }
    }

    /** Oriented rect -> raw rect (axis aligned, integer-safe bounds). */
    public static double[] rectToRaw(double[] r, int o, int rawW, int rawH) {
        double[] a = orientedToRaw(r[0], r[1], o, rawW, rawH);
        double[] b = orientedToRaw(r[2], r[3], o, rawW, rawH);
        return new double[]{
                clamp(Math.min(a[0], b[0]), 0, rawW), clamp(Math.min(a[1], b[1]), 0, rawH),
                clamp(Math.max(a[0], b[0]), 0, rawW), clamp(Math.max(a[1], b[1]), 0, rawH)};
    }

    /** Largest power-of-two subsample that keeps the decoded region at least outW x outH. */
    public static int sampleSize(double regionW, double regionH, int outW, int outH) {
        int s = 1;
        while (regionW / (s * 2) >= outW && regionH / (s * 2) >= outH && s < 64) s *= 2;
        return s;
    }

    public static double clamp(double v, double lo, double hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
