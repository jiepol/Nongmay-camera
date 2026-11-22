package com.nongmay.camera.camera.overlays;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Preview-stream analysis (YUV luma only): histogram, clipping/crush percentages, zebra stripes
 * and focus-peaking edges. Runs on its own thread at a throttled rate; values approximate the
 * preview pipeline, not the final JPEG/RAW (the UI says so).
 */
public final class FrameAnalyzer {

    public static final class Config {
        public volatile boolean histogram = true;
        public volatile boolean zebra;
        public volatile int zebraLevel = 95;     // percent of full scale (on 16..235 video range)
        public volatile boolean peaking;
        public volatile int peakColor = 0xFFFF3B30;
    }

    public static final class Result {
        public final int[] histogram = new int[64];
        public int maxBin;
        public double clippedPct, crushedPct, meanLuma;
        public Bitmap overlay;      // sensor-oriented (same as the analysis frame), may be null
        public int width, height;
    }

    private byte[] luma;
    private int[] pixels;
    private final Bitmap[] buffers = new Bitmap[3];
    private int next;

    public Result process(Image img, Config cfg) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride(), pixStride = plane.getPixelStride();
        if (luma == null || luma.length != w * h) luma = new byte[w * h];
        if (pixStride == 1 && rowStride == w) {
            buf.get(luma, 0, Math.min(luma.length, buf.remaining()));
        } else {
            byte[] row = new byte[rowStride];
            for (int y = 0; y < h; y++) {
                buf.position(y * rowStride);
                int len = Math.min(rowStride, buf.remaining());
                buf.get(row, 0, len);
                for (int x = 0; x < w; x++) luma[y * w + x] = row[Math.min(x * pixStride, len - 1)];
            }
        }
        Result r = new Result();
        r.width = w;
        r.height = h;
        long sum = 0;
        int clipped = 0, crushed = 0, n = 0;
        for (int y = 0; y < h; y += 2) {
            int base = y * w;
            for (int x = 0; x < w; x += 2) {
                int v = luma[base + x] & 0xFF;
                r.histogram[v >> 2]++;
                sum += v;
                if (v >= 250) clipped++;
                if (v <= 5) crushed++;
                n++;
            }
        }
        for (int b : r.histogram) r.maxBin = Math.max(r.maxBin, b);
        r.meanLuma = n == 0 ? 0 : (double) sum / n;
        r.clippedPct = n == 0 ? 0 : 100.0 * clipped / n;
        r.crushedPct = n == 0 ? 0 : 100.0 * crushed / n;
        if (cfg.zebra || cfg.peaking) {
            if (pixels == null || pixels.length != w * h) pixels = new int[w * h];
            java.util.Arrays.fill(pixels, 0);
            if (cfg.zebra) {
                int thr = 16 + (int) Math.round((235 - 16) * cfg.zebraLevel / 100.0);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int v = luma[y * w + x] & 0xFF;
                        if (v >= thr && ((x + y) / 6) % 2 == 0) pixels[y * w + x] = 0xC0FFFFFF;
                    }
                }
            }
            if (cfg.peaking) peaking(w, h, cfg.peakColor);
            Bitmap bmp = buffers[next];
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                buffers[next] = bmp;
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            next = (next + 1) % buffers.length;
            r.overlay = bmp;
        }
        return r;
    }

    /** Sobel magnitude; marks the strongest ~3% of edges so the colour tracks what is in focus. */
    private int[] mag;
    private final int[] hist = new int[256];

    private void peaking(int w, int h, int color) {
        java.util.Arrays.fill(hist, 0);
        if (mag == null || mag.length != w * h) mag = new int[w * h];
        java.util.Arrays.fill(mag, 0);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int a = luma[i - w - 1] & 0xFF, b = luma[i - w] & 0xFF, c = luma[i - w + 1] & 0xFF;
                int d = luma[i - 1] & 0xFF, f = luma[i + 1] & 0xFF;
                int g = luma[i + w - 1] & 0xFF, hh = luma[i + w] & 0xFF, k = luma[i + w + 1] & 0xFF;
                int gx = (c + 2 * f + k) - (a + 2 * d + g);
                int gy = (g + 2 * hh + k) - (a + 2 * b + c);
                int m = Math.min(255, (Math.abs(gx) + Math.abs(gy)) >> 2);
                mag[i] = m;
                hist[m]++;
            }
        }
        int total = (w - 2) * (h - 2), target = total * 3 / 100, acc = 0, thr = 255;
        for (int v = 255; v > 0; v--) {
            acc += hist[v];
            if (acc >= target) { thr = v; break; }
        }
        thr = Math.max(thr, 24);
        for (int i = 0; i < mag.length; i++) if (mag[i] >= thr) pixels[i] = color;
    }
}
