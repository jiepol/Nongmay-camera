package com.nongmay.camera.storymax;

/**
 * HDR (PQ / HLG, BT.2020) -> SDR (BT.709) conversion used by the GL fallback path of the
 * Story video exporter. The Java functions mirror the GLSL below so they can be unit-tested.
 * Curve: linear below 0.75 × HDR reference white (203 nits, ITU-R BT.2408), smooth rational
 * shoulder above it up to the peak, gamut BT.2020 -> BT.709 with clipping, BT.1886 inverse EOTF.
 */
public final class ToneMap {
    public static final int TRANSFER_PQ = 6;   // MediaFormat.COLOR_TRANSFER_ST2084
    public static final int TRANSFER_HLG = 7;  // MediaFormat.COLOR_TRANSFER_HLG
    public static final double REF_WHITE_NITS = 203.0;
    public static final double KNEE = 0.75;

    private ToneMap() {}

    public static double pqEotfNits(double e) {
        double m1 = 0.1593017578125, m2 = 78.84375, c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
        double p = Math.pow(Math.max(e, 0), 1 / m2);
        return 10000.0 * Math.pow(Math.max(p - c1, 0) / (c2 - c3 * p), 1 / m1);
    }

    public static double pqOetf(double nits) {
        double m1 = 0.1593017578125, m2 = 78.84375, c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
        double y = Math.pow(Math.max(nits, 0) / 10000.0, m1);
        return Math.pow((c1 + c2 * y) / (1 + c3 * y), m2);
    }

    /** HLG inverse OETF: signal -> normalised scene light [0,1]. */
    public static double hlgInvOetf(double e) {
        double a = 0.17883277, b = 0.28466892, c = 0.55991073;
        if (e <= 0.5) return e * e / 3.0;
        return (Math.exp((e - c) / a) + b) / 12.0;
    }

    /** Peak used for the shoulder (nits). */
    public static double peakNits(int transfer, double maxCllNits) {
        if (transfer == TRANSFER_HLG) return 1000.0;
        return maxCllNits > 0 ? Math.min(Math.max(maxCllNits, 400), 10000) : 1000.0;
    }

    /** Luminance tone curve on x = nits / 203 → SDR relative [0,1]. */
    public static double curve(double x, double peakRel) {
        if (x <= KNEE) return x;
        double w = Math.max(peakRel, 1.0001);
        double s = (w - KNEE) / (1 - KNEE);
        if (s <= 1.0001) return Math.min(x, 1.0);
        double b = 1.0 / (s - 1.0);
        double t = Math.min((x - KNEE) / (w - KNEE), 1.0);
        double f = t * (1 + b) / (t + b);
        return KNEE + (1 - KNEE) * f;
    }

    /** BT.2020 -> BT.709 linear RGB (ITU-R BT.2087). */
    public static final double[] BT2020_TO_709 = {
            1.6605, -0.5876, -0.0728,
            -0.1246, 1.1329, -0.0083,
            -0.0182, -0.1006, 1.1187};

    /**
     * Full Java reference of the shader for one BT.2020 non-linear RGB pixel (signal 0..1).
     * Returns BT.709 SDR signal (0..1) encoded with the BT.1886 inverse EOTF (gamma 1/2.4).
     */
    public static double[] toSdr(double r, double g, double b, int transfer, double peakNits) {
        double[] in = {r, g, b};
        double[] lin = new double[3];
        if (transfer == TRANSFER_HLG) {
            double[] scene = new double[3];
            for (int i = 0; i < 3; i++) scene[i] = hlgInvOetf(in[i]);
            double ys = 0.2627 * scene[0] + 0.6780 * scene[1] + 0.0593 * scene[2];
            double gain = 1000.0 * Math.pow(Math.max(ys, 1e-6), 0.2); // OOTF, gamma 1.2 @ 1000 nits
            for (int i = 0; i < 3; i++) lin[i] = scene[i] * gain;
        } else {
            for (int i = 0; i < 3; i++) lin[i] = pqEotfNits(in[i]);
        }
        double x = (0.2627 * lin[0] + 0.6780 * lin[1] + 0.0593 * lin[2]) / REF_WHITE_NITS;
        double[] rel = new double[3];
        for (int i = 0; i < 3; i++) rel[i] = lin[i] / REF_WHITE_NITS;
        if (x > 1e-9) {
            double s = curve(x, peakNits / REF_WHITE_NITS) / x; // luminance-preserving scale keeps hue
            for (int i = 0; i < 3; i++) rel[i] *= s;
        }
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = BT2020_TO_709[i * 3] * rel[0] + BT2020_TO_709[i * 3 + 1] * rel[1] + BT2020_TO_709[i * 3 + 2] * rel[2];
            v = Math.max(0, Math.min(1, v));
            o[i] = Math.pow(v, 1 / 2.4);
        }
        return o;
    }

    /**
     * Fragment shader (GLES 3.0 + GL_EXT_YUV_target) that samples raw Y'CbCr from the decoder's
     * external texture and performs the conversion above. Uniforms: uTex, uTransfer (6 = PQ, 7 = HLG),
     * uFullRange (0/1), uPeakRel (peak / 203).
     */
    public static final String FRAGMENT_YUV_HDR =
            "#version 300 es\n"
            + "#extension GL_EXT_YUV_target : require\n"
            + "precision highp float;\n"
            + "uniform __samplerExternal2DY2YEXT uTex;\n"
            + "uniform int uTransfer;\n"
            + "uniform int uFullRange;\n"
            + "uniform float uPeakRel;\n"
            + "in vec2 vTex;\n"
            + "out vec4 outColor;\n"
            + "float pqEotf(float e){ float m1=0.1593017578125; float m2=78.84375; float c1=0.8359375; float c2=18.8515625; float c3=18.6875;"
            + " float p=pow(max(e,0.0),1.0/m2); return 10000.0*pow(max(p-c1,0.0)/(c2-c3*p),1.0/m1);}\n"
            + "float hlgInv(float e){ float a=0.17883277; float b=0.28466892; float c=0.55991073;"
            + " return e<=0.5 ? e*e/3.0 : (exp((e-c)/a)+b)/12.0;}\n"
            + "float curve(float x, float w){ float k=0.75; if(x<=k) return x; float s=(w-k)/(1.0-k);"
            + " if(s<=1.0001) return min(x,1.0); float b=1.0/(s-1.0); float t=min((x-k)/(w-k),1.0); return k+(1.0-k)*t*(1.0+b)/(t+b);}\n"
            + "void main(){\n"
            + "  vec3 yuv = texture(uTex, vTex).xyz;\n"
            + "  float Y = yuv.x; float Cb = yuv.y - 0.5; float Cr = yuv.z - 0.5;\n"
            + "  if (uFullRange == 0) { Y = (Y*1023.0-64.0)/876.0; Cb = (yuv.y*1023.0-512.0)/896.0; Cr = (yuv.z*1023.0-512.0)/896.0; }\n"
            + "  vec3 rgb = clamp(vec3(Y + 1.4746*Cr, Y - 0.16455*Cb - 0.57135*Cr, Y + 1.8814*Cb), 0.0, 1.0);\n"
            + "  vec3 lin;\n"
            + "  if (uTransfer == 7) { vec3 sc = vec3(hlgInv(rgb.r), hlgInv(rgb.g), hlgInv(rgb.b));"
            + "    float ys = dot(sc, vec3(0.2627,0.6780,0.0593)); lin = sc * 1000.0 * pow(max(ys,1e-6), 0.2); }\n"
            + "  else { lin = vec3(pqEotf(rgb.r), pqEotf(rgb.g), pqEotf(rgb.b)); }\n"
            + "  float x = dot(lin, vec3(0.2627,0.6780,0.0593)) / 203.0;\n"
            + "  vec3 rel = lin / 203.0;\n"
            + "  if (x > 1e-9) rel *= curve(x, uPeakRel) / x;\n"
            + "  mat3 m = mat3(1.6605,-0.1246,-0.0182, -0.5876,1.1329,-0.1006, -0.0728,-0.0083,1.1187);\n"
            + "  vec3 o = clamp(m * rel, 0.0, 1.0);\n"
            + "  outColor = vec4(pow(o, vec3(1.0/2.4)), 1.0);\n"
            + "}\n";
}
