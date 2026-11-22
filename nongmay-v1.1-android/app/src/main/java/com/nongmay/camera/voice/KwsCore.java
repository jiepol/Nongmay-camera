package com.nongmay.camera.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline, speaker-dependent keyword spotter used by Voice Shutter.
 *
 * Pipeline (all on-device, no network, no stored audio):
 *   16 kHz PCM -> 10 ms energy VAD (adaptive floor) + background mel-spectrum tracker
 *   -> isolated speech segment -> mel power -> noise compensation against each recorded take
 *      ({@link Matcher}) -> MFCC (+CMVN, deltas) -> DTW against the owner's 2–5 takes
 *   -> accept if best distance <= calibrated threshold, the median agrees, duration matches
 *      the takes, and the cooldown has passed.
 *
 * Works for any language or phrase because it matches sound, not text. It is NOT a general
 * Thai speech recogniser: it needs the owner to record the phrase first, and other voices or
 * a very different intonation may not match. Pure Java (no Android imports) so the JVM
 * evaluation harness runs exactly this code.
 */
public final class KwsCore {

    public static final class Config {
        public int hangoverMs = 200;
        public int preRollMs = 120;
        public int postRollMs = 60;
        public int maxSegmentMs = 2500;
        public double minDurRatio = 0.55;
        public double maxDurRatio = 1.8;
        public int cooldownMs = 1500;
        public double band = 0.25;
        public double medianSlack = 1.25;
        public double minSnrDb = 6.0;
        /** Noise-compensated matching (see {@link Matcher}); false = plain MFCC/DTW (v1.1 prototype). */
        public boolean compensate = true;
        /** Optional extra floor relative to the segment's loudest band, dB (0 = off). */
        public double relFloorDb = 0;
        /** A 10 ms frame updates the background estimate when it is within this many dB of the VAD floor. */
        public double noiseGateDb = 3.0;
        public double noiseRate = 0.05;
        /** Both sides are clamped at this multiple of the shared background (removes noise flicker). */
        public double noiseClamp = 1.25;
        /** 0 = off, 1 = also try each take through a virtual room (min of dry/room), 2 = same room on both sides. */
        public int room = 1;
        public double roomRt60 = 0.3;
        public double roomDrrDb = 6.0;
        /** VAD: a pause is also anything this far below the segment's peak (99 = off). */
        public double endRelDb = 14.0;
    }

    public static final class Event {
        public static final int SEGMENT = 1;
        public static final int TRIGGER = 2;
        public int type;
        public double score = Double.NaN;
        public double median = Double.NaN;
        public double threshold = Double.NaN;
        public int durMs;
        public double snrDb;
        public String reject;
        public long speechEndSample;
        public long decisionSample;
        public float[][] features;
        /** Mel power of the segment and the background estimate just before it (what a take stores). */
        public float[][] mel;
        public float[] noise;

        public boolean accepted() {
            return reject == null;
        }
    }

    private static final int HOP = Mfcc.HOP;
    private final Config cfg;
    private final EnergyVad vad = new EnergyVad();
    private final Mfcc mfcc = new Mfcc();
    private final short[] ring = new short[Mfcc.SAMPLE_RATE * 4];
    private long written;
    private int hopFill;
    private double hopSumSq;
    private VoiceTemplate template;
    private boolean enrollMode;
    private long cooldownUntil = Long.MIN_VALUE;
    private long suppressUntil = Long.MIN_VALUE;
    private final double[] noiseMel = new double[Mfcc.NMEL];
    private final double[] melTmp = new double[Mfcc.NMEL];
    private final short[] frameBuf = new short[Mfcc.FRAME + 1];
    private int noiseFrames;

    public KwsCore(Config cfg) {
        this.cfg = cfg;
        applyConfig();
    }

    private void applyConfig() {
        vad.hangoverFrames = Math.max(5, cfg.hangoverMs / 10);
        vad.maxFrames = Math.max(50, cfg.maxSegmentMs / 10);
        vad.endRelDb = cfg.endRelDb;
    }

    public Config config() {
        return cfg;
    }

    public synchronized void setTemplate(VoiceTemplate t) {
        template = t;
    }

    public synchronized VoiceTemplate template() {
        return template;
    }

    public synchronized void setEnrollMode(boolean on) {
        enrollMode = on;
    }

    public synchronized void reset() {
        vad.reset();
        written = 0;
        hopFill = 0;
        hopSumSq = 0;
        cooldownUntil = Long.MIN_VALUE;
        suppressUntil = Long.MIN_VALUE;
        noiseFrames = 0;
        java.util.Arrays.fill(noiseMel, 0);
    }

    /** Current background mel-power estimate (copy). */
    public synchronized float[] noiseSnapshot() {
        float[] r = new float[Mfcc.NMEL];
        for (int m = 0; m < r.length; m++) r[m] = (float) noiseMel[m];
        return r;
    }

    private void updateNoise() {
        long start = written - Mfcc.FRAME - 1;
        for (int i = 0; i <= Mfcc.FRAME; i++) frameBuf[i] = ring[(int) ((start + i) % ring.length)];
        mfcc.melFrame(frameBuf, 1, frameBuf[0], melTmp);
        double a = noiseFrames < 10 ? 1.0 / (noiseFrames + 1) : cfg.noiseRate;
        for (int m = 0; m < Mfcc.NMEL; m++) noiseMel[m] += a * (melTmp[m] - noiseMel[m]);
        noiseFrames++;
    }

    public synchronized long samplesSeen() {
        return written;
    }

    public synchronized double noiseFloorDb() {
        return vad.floorDb();
    }

    public synchronized boolean inSpeech() {
        return vad.inSpeech();
    }

    /** Ignore anything that ends within the next {@code ms} (e.g. our own shutter sound). */
    public synchronized void suppressFor(int ms) {
        suppressUntil = written + (long) ms * Mfcc.SAMPLE_RATE / 1000;
    }

    public synchronized List<Event> feed(short[] pcm, int off, int len) {
        List<Event> events = null;
        for (int i = 0; i < len; i++) {
            short s = pcm[off + i];
            ring[(int) (written % ring.length)] = s;
            written++;
            hopSumSq += (double) s * s;
            if (++hopFill == HOP) {
                double rms = Math.sqrt(hopSumSq / HOP);
                double db = 20.0 * Math.log10(rms / 32768.0 + 1e-9);
                hopFill = 0;
                hopSumSq = 0;
                if (vad.process(db) == EnergyVad.END) {
                    Event e = onSegmentEnd();
                    if (e != null) {
                        if (events == null) events = new ArrayList<>(2);
                        events.add(e);
                    }
                }
                // background spectrum: only frames the VAD treats as quiet, after the segment was scored
                if (!vad.inSpeech() && written > Mfcc.FRAME + 1 && db < vad.floorDb() + cfg.noiseGateDb) updateNoise();
            }
        }
        return events == null ? java.util.Collections.<Event>emptyList() : events;
    }

    private Event onSegmentEnd() {
        Event ev = new Event();
        ev.type = Event.SEGMENT;
        ev.durMs = (int) ((vad.segEnd - vad.segStart + 1) * 10);
        ev.snrDb = vad.segPeakDb - vad.floorDb();
        ev.speechEndSample = (vad.segEnd + 1) * HOP;
        ev.decisionSample = written;
        if (vad.tooLong) {
            ev.reject = "too_long";
            return ev;
        }
        long pre = (long) cfg.preRollMs * Mfcc.SAMPLE_RATE / 1000;
        long post = (long) cfg.postRollMs * Mfcc.SAMPLE_RATE / 1000;
        long start = Math.max(0, Math.max(vad.segStart * HOP - pre, written - ring.length + 1));
        long end = Math.min(written, (vad.segEnd + 1) * HOP + post);
        int n = (int) (end - start);
        if (n < Mfcc.FRAME * 3) {
            ev.reject = "too_short";
            return ev;
        }
        short[] seg = new short[n];
        for (int i = 0; i < n; i++) seg[i] = ring[(int) ((start + i) % ring.length)];
        ev.mel = mfcc.mel(seg, 0, n);
        ev.noise = noiseSnapshot();
        ev.features = mfcc.featuresFromMel(Mfcc.toDouble(ev.mel));
        if (ev.snrDb < cfg.minSnrDb) {
            ev.reject = "too_quiet";
            return ev;
        }
        if (enrollMode || template == null || !template.isReady()) {
            if (!enrollMode) ev.reject = "no_template";
            return ev;
        }
        double ratio = ev.durMs / Math.max(1.0, template.avgDurMs());
        int k = template.size();
        double[] d = new double[k];
        for (int i = 0; i < k; i++) d[i] = Matcher.distance(mfcc, ev.mel, ev.noise, template.mel.get(i), template.noise.get(i), cfg);
        Arrays.sort(d);
        ev.score = d[0];
        ev.median = d[k / 2];
        if (cfg.room == 1) {
            // second hypothesis: the owner's takes heard through a virtual furnished room
            double[] r = new double[k];
            for (int i = 0; i < k; i++) r[i] = Matcher.distance(mfcc, ev.mel, ev.noise, template.mel.get(i), template.noise.get(i), cfg, true);
            Arrays.sort(r);
            if (r[0] < ev.score) {
                ev.score = r[0];
                ev.median = r[k / 2];
            }
        }
        ev.threshold = template.threshold();
        if (ratio < cfg.minDurRatio) ev.reject = "shorter_than_samples";
        else if (ratio > cfg.maxDurRatio) ev.reject = "longer_than_samples";
        else if (!(ev.score <= ev.threshold)) ev.reject = "no_match";
        else if (!(ev.median <= ev.threshold * cfg.medianSlack)) ev.reject = "weak_match";
        else if (ev.speechEndSample <= suppressUntil) ev.reject = "suppressed";
        else if (written < cooldownUntil) ev.reject = "cooldown";
        if (ev.reject == null) {
            ev.type = Event.TRIGGER;
            cooldownUntil = written + (long) cfg.cooldownMs * Mfcc.SAMPLE_RATE / 1000;
        }
        return ev;
    }

    /** Thai text for a reject code, shown in the test screen. */
    public static String rejectLabel(String code) {
        if (code == null) return "ตรงกับเสียงที่สอนไว้";
        switch (code) {
            case "too_long": return "พูดยาวเกินไป (ระบบไม่ถ่ายตอนคุยกันเป็นประโยค)";
            case "too_short": return "สั้นเกินไป";
            case "too_quiet": return "เบาเกินไปเมื่อเทียบกับเสียงรอบข้าง";
            case "no_template": return "ยังไม่ได้สอนเสียง";
            case "shorter_than_samples": return "สั้นกว่าตอนสอนมาก";
            case "longer_than_samples": return "ยาวกว่าตอนสอนมาก";
            case "no_match": return "ไม่เหมือนเสียงที่สอนไว้";
            case "weak_match": return "เหมือนแค่บางตัวอย่าง";
            case "suppressed": return "ข้ามเสียงชัตเตอร์ของแอปเอง";
            case "cooldown": return "เพิ่งถ่ายไป รอสักครู่";
            default: return code;
        }
    }
}
