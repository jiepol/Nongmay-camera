package com.nongmay.camera.voice;

/**
 * Streaming energy voice-activity detector on 10 ms frames with an adaptive noise floor.
 * Emits START when speech begins and END after {@link #hangoverFrames} quiet frames.
 * Segments longer than {@link #maxFrames} end with {@link #tooLong} = true and the detector then
 * waits for real silence before arming again, so continuous talk is never chopped into pieces
 * (that behaviour is what keeps the shutter from firing during conversation).
 */
public final class EnergyVad {
    public static final int NONE = 0, START = 1, END = 2;

    public double startMarginDb = 10.0;
    public double endMarginDb = 6.0;
    public double absMinDb = -58.0;
    public int startFrames = 3;
    public int hangoverFrames = 20;
    public int maxFrames = 250;
    /** First frames only learn the background (minimum energy) so a noisy start is not taken as speech. */
    public int warmupFrames = 30;
    /** Inside a segment, frames more than this many dB below the segment's peak count as pauses. */
    public double endRelDb = 99.0;

    private double floorDb = -75.0;
    private boolean initialised;
    private boolean inSpeech;
    private boolean waitingForSilence;
    private int above, below, quiet;
    private long frame;
    private long startFrame, lastSpeechFrame;
    private double peakDb = -120;

    /** Valid after END: first and last speech frame indices (inclusive). */
    public long segStart, segEnd;
    public boolean tooLong;
    public double segPeakDb;

    public double floorDb() {
        return floorDb;
    }

    public boolean inSpeech() {
        return inSpeech;
    }

    public long frameIndex() {
        return frame;
    }

    public void reset() {
        frame = 0;
        initialised = false;
        inSpeech = false;
        waitingForSilence = false;
        above = below = quiet = 0;
        floorDb = -75.0;
    }

    public int process(double eDb) {
        long idx = frame++;
        if (!initialised) {
            floorDb = eDb;
            initialised = true;
        }
        if (idx < warmupFrames) {
            if (eDb < floorDb) floorDb = eDb;
            return NONE;
        }
        if (waitingForSilence) {
            if (eDb < floorDb + endMarginDb) quiet++; else quiet = 0;
            // keep adapting so a new constant noise level (fan, AC, music) cannot lock us out forever
            floorDb += (eDb - floorDb) * (eDb < floorDb ? 0.3 : 0.05);
            if (quiet >= hangoverFrames) {
                waitingForSilence = false;
                above = 0;
            }
            return NONE;
        }
        if (!inSpeech) {
            boolean loud = eDb > floorDb + startMarginDb && eDb > absMinDb;
            if (loud) {
                above++;
                if (above >= startFrames) {
                    inSpeech = true;
                    startFrame = idx - above + 1;
                    lastSpeechFrame = idx;
                    below = 0;
                    peakDb = eDb;
                    return START;
                }
            } else {
                above = 0;
                floorDb += (eDb - floorDb) * (eDb < floorDb ? 0.3 : 0.02);
            }
            return NONE;
        }
        boolean speech = eDb > Math.max(floorDb + endMarginDb, peakDb - endRelDb) && eDb > absMinDb - 6.0;
        if (speech) {
            lastSpeechFrame = idx;
            below = 0;
            if (eDb > peakDb) peakDb = eDb;
        } else {
            below++;
        }
        if (below >= hangoverFrames) {
            inSpeech = false;
            above = 0;
            segStart = startFrame;
            segEnd = lastSpeechFrame;
            segPeakDb = peakDb;
            tooLong = false;
            return END;
        }
        if (idx - startFrame >= maxFrames) {
            inSpeech = false;
            waitingForSilence = true;
            quiet = 0;
            segStart = startFrame;
            segEnd = idx;
            segPeakDb = peakDb;
            tooLong = true;
            return END;
        }
        return NONE;
    }
}
