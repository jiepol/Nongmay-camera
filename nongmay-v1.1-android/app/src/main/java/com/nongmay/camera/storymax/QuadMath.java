package com.nongmay.camera.storymax;

/**
 * Geometry for the export quad (pure Java, unit-tested): where the picture lands in the portrait
 * output, which part of the source it shows, the container rotation, and the optional
 * "encode landscape + rotate 90 in the container" fallback for encoders without portrait sizes.
 * Vertex order is a triangle strip: bottom-left, bottom-right, top-left, top-right.
 */
public final class QuadMath {
    private QuadMath() {}

    /**
     * @param outW,outH   portrait output size in pixels (e.g. 1080×1920)
     * @param dst         {l,t,r,b} destination rect in output pixels (y down)
     * @param srcNorm     {l,t,r,b} source window in oriented, normalised coordinates [0,1] (y down)
     * @param orientation EXIF-style orientation of the stored frames (1, 3, 6, 8 for video rotation)
     * @param landscape   true when the encoder runs at outH×outW and the MP4 carries a 90° hint
     * @return {ndc[8], st[8]}
     */
    public static float[][] quad(int outW, int outH, double[] dst, double[] srcNorm, int orientation, boolean landscape) {
        double[][] px = {{dst[0], dst[3]}, {dst[2], dst[3]}, {dst[0], dst[1]}, {dst[2], dst[1]}};
        double[][] uv = {{srcNorm[0], srcNorm[3]}, {srcNorm[2], srcNorm[3]}, {srcNorm[0], srcNorm[1]}, {srcNorm[2], srcNorm[1]}};
        float[] ndc = new float[8];
        float[] st = new float[8];
        int encW = landscape ? outH : outW, encH = landscape ? outW : outH;
        for (int i = 0; i < 4; i++) {
            double x = px[i][0], y = px[i][1];
            if (landscape) {
                double xe = y, ye = outW - x;
                x = xe;
                y = ye;
            }
            ndc[2 * i] = (float) (2.0 * x / encW - 1.0);
            ndc[2 * i + 1] = (float) (1.0 - 2.0 * y / encH);
            double[] raw = CropMath.orientedToRaw(uv[i][0], uv[i][1], orientation, 1, 1);
            st[2 * i] = (float) raw[0];
            st[2 * i + 1] = (float) (1.0 - raw[1]);
        }
        return new float[][]{ndc, st};
    }

    /** H.264 level that covers w×h@fps (macroblocks/frame and macroblocks/second), per ITU-T H.264 Table A-1. */
    public static int avcLevelFor(int w, int h, double fps) {
        long mbs = (long) Math.ceil(w / 16.0) * (long) Math.ceil(h / 16.0);
        double mbps = mbs * fps;
        long[][] table = {
                {0x100, 8192, 245760},    // 4.1  (MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                {0x200, 8704, 522240},    // 4.2
                {0x400, 22080, 589824},   // 5
                {0x800, 36864, 983040},   // 5.1
                {0x1000, 36864, 2073600}, // 5.2
        };
        for (long[] row : table) if (mbs <= row[1] && mbps <= row[2]) return (int) row[0];
        return 0x1000;
    }
}
