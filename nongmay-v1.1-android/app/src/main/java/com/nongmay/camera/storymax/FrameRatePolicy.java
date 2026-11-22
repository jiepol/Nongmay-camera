package com.nongmay.camera.storymax;

/**
 * Timestamp-based frame selection for "≤ N fps" exports. Frames are only dropped, never
 * duplicated or interpolated: 60→30 keeps every other frame, 24/25/30 keep their native cadence,
 * variable-frame-rate input keeps its own timing. Pure Java.
 */
public final class FrameRatePolicy {
    private final long minGapUs;
    private long lastUs = Long.MIN_VALUE;
    private int accepted, dropped;

    public FrameRatePolicy(int maxFps) {
        if (maxFps <= 0) {
            minGapUs = 0;
        } else {
            long interval = 1_000_000L / maxFps;
            minGapUs = interval - interval / 4; // 25% tolerance absorbs timestamp jitter
        }
    }

    public boolean accept(long ptsUs) {
        if (minGapUs == 0 || lastUs == Long.MIN_VALUE || ptsUs - lastUs >= minGapUs) {
            lastUs = ptsUs;
            accepted++;
            return true;
        }
        dropped++;
        return false;
    }

    public int accepted() {
        return accepted;
    }

    public int dropped() {
        return dropped;
    }
}
