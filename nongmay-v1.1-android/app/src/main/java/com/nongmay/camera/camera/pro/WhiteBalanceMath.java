package com.nongmay.camera.camera.pro;

/**
 * Manual white balance in Kelvin/tint, computed the way DNG converters do it, from the sensor's
 * own calibration (SENSOR_COLOR_TRANSFORM1/2, SENSOR_CALIBRATION_TRANSFORM1/2, SENSOR_FORWARD_MATRIX1/2,
 * SENSOR_REFERENCE_ILLUMINANT1/2). Output = COLOR_CORRECTION_GAINS + COLOR_CORRECTION_TRANSFORM.
 * When forward matrices are missing the colour matrix path is used and flagged as an approximation.
 * All matrices are 3x3 row-major double[9]. Pure Java.
 */
public final class WhiteBalanceMath {
    private WhiteBalanceMath() {}

    /** XYZ(D50) -> linear sRGB(D65), Bradford adapted. */
    static final double[] XYZD50_TO_SRGB = {
            3.1338561, -1.6168667, -0.4906146,
            -0.9787684, 1.9161415, 0.0334540,
            0.0719453, -0.2289914, 1.4052427};
    static final double[] BRADFORD = {
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296};
    static final double[] D50_XYZ = {0.9642, 1.0, 0.8249};

    public static final class Result {
        public double[] gains;      // R, G_even, G_odd, B (green = 1)
        public double[] ccm;        // camera (after gains) -> linear sRGB
        public double[] whiteXy;
        public boolean forwardMatrix;
        public boolean valid;
        public String note;
    }

    /** CIE 1931 xy of a Planckian radiator (Kim et al. 2002), valid 1667–25000 K. */
    public static double[] planckianXy(double kelvin) {
        double t = Math.max(1667, Math.min(25000, kelvin));
        double x;
        if (t <= 4000) x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
        else x = -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        double y;
        if (t <= 2222) y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
        else if (t <= 4000) y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        else y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        return new double[]{x, y};
    }

    static double[] xyToUv(double[] xy) {
        double d = -2 * xy[0] + 12 * xy[1] + 3;
        return new double[]{4 * xy[0] / d, 6 * xy[1] / d};
    }

    static double[] uvToXy(double[] uv) {
        double d = 2 * uv[0] - 8 * uv[1] + 4;
        return new double[]{3 * uv[0] / d, 2 * uv[1] / d};
    }

    /** White point for Kelvin plus tint (+ = magenta, − = green), offset normal to the locus in CIE 1960 uv. */
    public static double[] whiteXy(double kelvin, double tint) {
        double[] xy = planckianXy(kelvin);
        if (tint == 0) return xy;
        double[] uv = xyToUv(xy);
        double[] uv2 = xyToUv(planckianXy(kelvin * 1.01));
        double du = uv2[0] - uv[0], dv = uv2[1] - uv[1];
        double len = Math.hypot(du, dv);
        if (len < 1e-12) return xy;
        double nx = -dv / len, ny = du / len;
        if (ny > 0) { nx = -nx; ny = -ny; } // point toward lower v (magenta side)
        double s = 0.0001 * tint;
        return uvToXy(new double[]{uv[0] + nx * s, uv[1] + ny * s});
    }

    /** McCamy's approximation, used only for the readout of auto white balance. */
    public static double cctFromXy(double x, double y) {
        double n = (x - 0.3320) / (0.1858 - y);
        return 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33;
    }

    /** CCT for an EXIF LightSource value (CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_*). */
    public static double illuminantKelvin(int exifLightSource) {
        switch (exifLightSource) {
            case 1: return 5500;   // daylight
            case 2: return 4200;   // fluorescent
            case 3: return 2856;   // tungsten
            case 4: return 5500;   // flash
            case 9: return 5500;   // fine weather
            case 10: return 6500;  // cloudy
            case 11: return 7500;  // shade
            case 12: return 6430;  // daylight fluorescent
            case 13: return 5000;  // day white fluorescent
            case 14: return 4150;  // cool white fluorescent
            case 15: return 3450;  // white fluorescent
            case 17: return 2856;  // standard A
            case 18: return 4874;  // standard B
            case 19: return 6774;  // standard C
            case 20: return 5503;  // D55
            case 21: return 6504;  // D65
            case 22: return 7504;  // D75
            case 23: return 5003;  // D50
            case 24: return 3200;  // ISO studio tungsten
            default: return 0;
        }
    }

    /** Inverse-CCT interpolation weight for matrix 1 (DNG spec 6.3.1). */
    public static double weight(double kelvin, double t1, double t2) {
        if (t1 <= 0 || t2 <= 0 || Math.abs(t1 - t2) < 1) return 1.0;
        double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
        double k = Math.max(lo, Math.min(hi, kelvin));
        double g = (1.0 / k - 1.0 / t2) / (1.0 / t1 - 1.0 / t2);
        return Math.max(0, Math.min(1, g));
    }

    public static Result compute(double kelvin, double tint,
                                 double[] cm1, double[] cm2, double[] cc1, double[] cc2,
                                 double[] fm1, double[] fm2, double t1, double t2) {
        Result r = new Result();
        if (cm1 == null) {
            r.note = "sensor has no colour calibration";
            return r;
        }
        double[] xy = whiteXy(kelvin, tint);
        r.whiteXy = xy;
        double[] white = {xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1]};
        double g = cm2 == null ? 1.0 : weight(kelvin, t1, t2);
        double[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        double[] cc = cc1 == null ? identity : (cc2 == null ? cc1 : lerp(cc1, cc2, g));
        double[] cmRef = cm2 == null ? cm1 : lerp(cm1, cm2, g);
        double[] cm = mul(cc, cmRef); // XYZ -> this unit's camera space
        double[] neutral = mulVec(cm, white);
        if (neutral[0] <= 0 || neutral[1] <= 0 || neutral[2] <= 0) {
            r.note = "white point outside sensor gamut";
            return r;
        }
        double ng = neutral[1];
        for (int i = 0; i < 3; i++) neutral[i] /= ng;
        r.gains = new double[]{1 / neutral[0], 1, 1, 1 / neutral[2]};
        double[] ccm;
        if (fm1 != null) {
            // DNG: XYZ_D50 = FM * diag(1/refNeutral) * inv(CC) * camera, Android applies gains first.
            double[] fm = fm2 == null ? fm1 : lerp(fm1, fm2, g);
            double[] invCc = inv(cc);
            if (invCc == null) invCc = identity;
            double[] refN = mulVec(invCc, neutral);
            if (refN[0] <= 0 || refN[1] <= 0 || refN[2] <= 0) refN = neutral;
            double[] dInv = diag(new double[]{1 / refN[0], 1 / refN[1], 1 / refN[2]});
            ccm = mul(XYZD50_TO_SRGB, mul(fm, mul(dInv, mul(invCc, diag(neutral)))));
            r.forwardMatrix = true;
        } else {
            double[] camToXyz = inv(cm);
            if (camToXyz == null) {
                r.note = "colour matrix not invertible";
                return r;
            }
            double[] ca = bradford(white, D50_XYZ);
            ccm = mul(XYZD50_TO_SRGB, mul(ca, mul(camToXyz, diag(neutral))));
            r.note = "approximation (no forward matrix)";
        }
        for (int row = 0; row < 3; row++) {
            double s = ccm[row * 3] + ccm[row * 3 + 1] + ccm[row * 3 + 2];
            if (Math.abs(s) > 1e-6) for (int c = 0; c < 3; c++) ccm[row * 3 + c] /= s;
        }
        r.ccm = ccm;
        r.valid = true;
        return r;
    }

    static double[] bradford(double[] srcXyz, double[] dstXyz) {
        double[] s = mulVec(BRADFORD, srcXyz);
        double[] d = mulVec(BRADFORD, dstXyz);
        double[] scale = diag(new double[]{d[0] / s[0], d[1] / s[1], d[2] / s[2]});
        return mul(inv(BRADFORD), mul(scale, BRADFORD));
    }

    public static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return r;
    }

    public static double[] mulVec(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    static double[] diag(double[] v) {
        return new double[]{v[0], 0, 0, 0, v[1], 0, 0, 0, v[2]};
    }

    static double[] lerp(double[] a, double[] b, double g) {
        double[] r = new double[9];
        for (int i = 0; i < 9; i++) r[i] = g * a[i] + (1 - g) * b[i];
        return r;
    }

    public static double[] inv(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12) return null;
        return new double[]{
                (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
                (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det};
    }
}
