package com.nongmay.camera.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Unit tests of the offline keyword spotter using synthetic "words" (sequences of harmonic
 * tones with formant-like spectra). Real-speech numbers come from tools/voice_eval, not from here.
 */
public class VoiceCoreTest {
    static final int SR = Mfcc.SAMPLE_RATE;

    /** A synthetic word: piecewise pitch/formant pattern, stretched by speed, shifted by pitch. */
    static short[] word(double[] f0s, double[] formants, double speed, double pitch, double amp, Random rnd, double noise) {
        int segMs = (int) (110 / speed);
        int n = f0s.length * segMs * SR / 1000;
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            int seg = Math.min(f0s.length - 1, i / (segMs * SR / 1000));
            double f0 = f0s[seg] * pitch;
            double fm = formants[seg];
            ph += 2 * Math.PI * f0 / SR;
            double s = 0;
            for (int h = 1; h <= 12; h++) {
                double fh = h * f0;
                double g = Math.exp(-Math.pow((fh - fm) / 350.0, 2)) + 0.15 / h;
                s += g * Math.sin(h * ph);
            }
            double env = Math.min(1, Math.min(i, n - i) / (0.02 * SR));
            out[i] = (short) Math.max(-32767, Math.min(32767, amp * env * s * 4000 + noise * rnd.nextGaussian()));
        }
        return out;
    }

    static short[] silence(int ms, double noise, Random rnd) {
        short[] s = new short[ms * SR / 1000];
        for (int i = 0; i < s.length; i++) s[i] = (short) (noise * rnd.nextGaussian());
        return s;
    }

    static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        short[] out = new short[n];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    static final double[] KEY_F0 = {180, 200, 210, 170, 160};
    static final double[] KEY_FM = {700, 1200, 2300, 900, 500};
    static final double[] OTHER_F0 = {150, 150, 240, 240, 140};
    static final double[] OTHER_FM = {2500, 400, 600, 2000, 1500};

    static List<KwsCore.Event> run(KwsCore core, short[] audio) {
        List<KwsCore.Event> all = new ArrayList<>();
        for (int i = 0; i < audio.length; i += 320) {
            all.addAll(core.feed(audio, i, Math.min(320, audio.length - i)));
        }
        return all;
    }

    static VoiceTemplate enroll(Random rnd) {
        KwsCore core = new KwsCore(new KwsCore.Config());
        core.setEnrollMode(true);
        VoiceTemplate t = new VoiceTemplate("t1", "น้องเม");
        double[][] var = {{1.0, 1.0}, {0.93, 1.03}, {1.07, 0.97}};
        for (double[] v : var) {
            short[] audio = concat(silence(400, 30, rnd), word(KEY_F0, KEY_FM, v[0], v[1], 1.0, rnd, 30), silence(500, 30, rnd));
            List<KwsCore.Event> ev = run(core, audio);
            assertEquals(1, ev.size());
            t.add(ev.get(0));
        }
        t.calibrate(core.config());
        return t;
    }

    @Test
    public void fftFindsTheToneBin() {
        Fft fft = new Fft(512);
        double[] frame = new double[512];
        for (int i = 0; i < 512; i++) frame[i] = Math.sin(2 * Math.PI * 32 * i / 512.0);
        double[] p = new double[257];
        fft.power(frame, 512, p);
        int best = 0;
        for (int k = 1; k < p.length; k++) if (p[k] > p[best]) best = k;
        assertEquals(32, best);
        assertEquals(512 * 512 / 4.0, p[32], 1e-6);
    }

    @Test
    public void mfccShapesAndNormalisation() {
        Random rnd = new Random(1);
        short[] w = word(KEY_F0, KEY_FM, 1, 1, 1, rnd, 20);
        float[][] f = new Mfcc().features(w, 0, w.length);
        assertEquals(Mfcc.frameCount(w.length), f.length);
        assertEquals(Mfcc.DIM, f[0].length);
        double mean = 0;
        for (float[] row : f) mean += row[0];
        assertEquals(0, mean / f.length, 1e-4);
    }

    @Test
    public void dtwPrefersSameWordEvenWhenStretched() {
        Random rnd = new Random(2);
        Mfcc m = new Mfcc();
        short[] a = word(KEY_F0, KEY_FM, 1.0, 1.0, 1, rnd, 20);
        short[] b = word(KEY_F0, KEY_FM, 0.85, 1.04, 1, rnd, 20);
        short[] c = word(OTHER_F0, OTHER_FM, 1.0, 1.0, 1, rnd, 20);
        float[][] fa = m.features(a, 0, a.length), fb = m.features(b, 0, b.length), fc = m.features(c, 0, c.length);
        assertEquals(0, Dtw.distance(fa, fa, 0.25), 1e-9);
        double same = Dtw.distance(fa, fb, 0.25), diff = Dtw.distance(fa, fc, 0.25);
        assertTrue("same=" + same + " diff=" + diff, same * 1.5 < diff);
        assertTrue(Double.isInfinite(Dtw.distance(fa, java.util.Arrays.copyOf(fa, fa.length / 4), 0.25)));
    }

    @Test
    public void vadFindsSegmentAndRejectsLongTalk() {
        EnergyVad v = new EnergyVad();
        int starts = 0, ends = 0;
        long s = -1, e = -1;
        for (int i = 0; i < 300; i++) {
            double db = (i >= 80 && i < 140) ? -20 : -62;
            int r = v.process(db);
            if (r == EnergyVad.START) starts++;
            if (r == EnergyVad.END) { ends++; s = v.segStart; e = v.segEnd; assertFalse(v.tooLong); }
        }
        assertEquals(1, starts);
        assertEquals(1, ends);
        assertEquals(80, s);
        assertEquals(139, e);
        EnergyVad v2 = new EnergyVad();
        int longEnds = 0, restarts = 0;
        for (int i = 0; i < 700; i++) {
            int r = v2.process(i >= 50 && i < 600 ? -20 : -62);
            if (r == EnergyVad.END) { longEnds++; assertTrue(v2.tooLong); }
            if (r == EnergyVad.START && i > 60) restarts++;
        }
        assertEquals(1, longEnds);
        assertEquals(0, restarts);
    }

    @Test
    public void spotterTriggersOnKeywordOnly() {
        Random rnd = new Random(3);
        VoiceTemplate t = enroll(rnd);
        assertTrue(t.isReady());
        assertTrue(t.consistent());
        KwsCore core = new KwsCore(new KwsCore.Config());
        core.setTemplate(t);
        short[] audio = concat(
                silence(600, 30, rnd),
                word(KEY_F0, KEY_FM, 0.97, 1.02, 0.8, rnd, 30), silence(2000, 30, rnd),
                word(OTHER_F0, OTHER_FM, 1.0, 1.0, 1.0, rnd, 30), silence(800, 30, rnd));
        List<KwsCore.Event> ev = run(core, audio);
        int triggers = 0;
        for (KwsCore.Event e : ev) if (e.type == KwsCore.Event.TRIGGER) triggers++;
        assertEquals("events=" + ev.size(), 1, triggers);
        assertEquals(2, ev.size());
        assertEquals(KwsCore.Event.TRIGGER, ev.get(0).type);
        assertFalse(ev.get(1).accepted());
        long latencyMs = (ev.get(0).decisionSample - ev.get(0).speechEndSample) * 1000 / SR;
        assertTrue("latency " + latencyMs, latencyMs <= 260);
    }

    @Test
    public void spotterIgnoresLongSentencesAndRespectsCooldown() {
        Random rnd = new Random(4);
        VoiceTemplate t = enroll(rnd);
        KwsCore core = new KwsCore(new KwsCore.Config());
        core.setTemplate(t);
        short[] sentence = concat(word(OTHER_F0, OTHER_FM, 1, 1, 1, rnd, 30), word(KEY_F0, KEY_FM, 1, 1, 1, rnd, 30),
                word(OTHER_F0, OTHER_FM, 0.9, 1.1, 1, rnd, 30), word(OTHER_F0, OTHER_FM, 0.8, 1, 1, rnd, 30),
                word(OTHER_F0, OTHER_FM, 0.8, 0.9, 1, rnd, 30));
        List<KwsCore.Event> ev = run(core, concat(silence(500, 30, rnd), sentence, silence(800, 30, rnd)));
        for (KwsCore.Event e : ev) assertNotEquals(KwsCore.Event.TRIGGER, e.type);
        core = new KwsCore(new KwsCore.Config());
        core.setTemplate(t);
        short[] w = word(KEY_F0, KEY_FM, 1, 1, 1, rnd, 30);
        ev = run(core, concat(silence(500, 30, rnd), w, silence(300, 30, rnd), w, silence(600, 30, rnd)));
        int triggers = 0;
        String lastReject = null;
        for (KwsCore.Event e : ev) {
            if (e.type == KwsCore.Event.TRIGGER) triggers++; else lastReject = e.reject;
        }
        assertEquals(1, triggers);
        assertEquals("cooldown", lastReject);
    }

    @Test
    public void compensationKeepsTheKeywordInSteadyNoise() {
        Random rnd = new Random(11);
        VoiceTemplate t = enroll(rnd);
        // 2 s of steady background first (the engine listens continuously), then the phrase, ~12 dB SNR
        KwsCore core = new KwsCore(new KwsCore.Config());
        core.setTemplate(t);
        short[] key = word(KEY_F0, KEY_FM, 1.02, 0.99, 1.0, rnd, 30);
        short[] audio = concat(silence(2000, 30, rnd), key, silence(600, 30, rnd));
        addNoise(audio, rms(key) / 4.0, new Random(3));
        int trig = 0;
        for (KwsCore.Event e : run(core, audio)) if (e.type == KwsCore.Event.TRIGGER) trig++;
        assertEquals(1, trig);
        KwsCore.Config plain = new KwsCore.Config();
        plain.compensate = false;
        KwsCore old = new KwsCore(plain);
        VoiceTemplate t2 = enroll(new Random(11));
        t2.calibrate(plain);
        old.setTemplate(t2);
        int trigOld = 0;
        for (KwsCore.Event e : run(old, audio)) if (e.type == KwsCore.Event.TRIGGER) trigOld++;
        assertTrue("compensated matcher should be at least as good as the plain one", trig >= trigOld);
    }

    static double rms(short[] a) {
        double s = 0;
        for (short v : a) s += (double) v * v;
        return Math.sqrt(s / Math.max(1, a.length));
    }

    static void addNoise(short[] a, double sd, Random r) {
        for (int i = 0; i < a.length; i++) a[i] = (short) Math.max(-32767, Math.min(32767, Math.round(a[i] + r.nextGaussian() * sd)));
    }

    @Test
    public void exactZerosFromMicWarmUpAreIgnored() {
        // S25 Ultra self-test 1.1.1: the first AudioRecord buffers were exact zeros and the VAD floor read -180 dB
        Random rnd = new Random(5);
        VoiceTemplate t = enroll(rnd);
        KwsCore core = new KwsCore(new KwsCore.Config());
        core.setTemplate(t);
        short[] zeros = new short[Mfcc.SAMPLE_RATE / 2];
        short[] key = word(KEY_F0, KEY_FM, 1.0, 1.0, 1.0, rnd, 30);
        short[] audio = concat(zeros, silence(400, 30, rnd), key, silence(600, 30, rnd));
        int trig = 0;
        for (KwsCore.Event e : run(core, audio)) if (e.type == KwsCore.Event.TRIGGER) trig++;
        assertEquals(1, trig);
        assertEquals(500, core.digitalSilenceMs());
        assertTrue("floor must never sit at digital silence", core.noiseFloorDb() >= -100.0);
        EnergyVad vad = new EnergyVad();
        for (int i = 0; i < 40; i++) vad.process(-180.0);
        assertTrue(vad.floorDb() >= -100.0);
    }

    @Test
    public void notThisSampleBlocksAMarkedSound() throws Exception {
        // the owner taps “ไม่ใช่คำนี้” after a false trigger; the same sound must not fire again (also after save/load)
        Random rnd = new Random(21);
        VoiceTemplate t = enroll(rnd);
        short[] key = word(KEY_F0, KEY_FM, 1.0, 1.0, 1.0, rnd, 30);
        short[] audio = concat(silence(800, 30, rnd), key, silence(600, 30, rnd));
        KwsCore core = new KwsCore(new KwsCore.Config());
        core.setTemplate(t);
        KwsCore.Event fired = null;
        for (KwsCore.Event e : run(core, audio)) if (e.type == KwsCore.Event.TRIGGER) fired = e;
        assertTrue(fired != null);
        t.addAnti(fired.mel, fired.noise);
        VoiceTemplate back = VoiceTemplate.fromBytes(t.toBytes());
        assertEquals(1, back.antiCount());
        KwsCore again = new KwsCore(new KwsCore.Config());
        again.setTemplate(back);
        int trig = 0;
        String reason = null;
        for (KwsCore.Event e : run(again, audio)) {
            if (e.type == KwsCore.Event.TRIGGER) trig++;
            else if (!Double.isNaN(e.score)) reason = e.reject;
        }
        assertEquals(0, trig);
        assertEquals("not_this", reason);
        back.clearAnti();
        assertEquals(0, back.antiCount());
    }

    @Test
    public void templateRoundTripAndSensitivityOrder() throws Exception {
        VoiceTemplate t = enroll(new Random(5));
        t.sensitivity = VoiceTemplate.SENSITIVE;
        VoiceTemplate back = VoiceTemplate.fromBytes(t.toBytes());
        assertEquals(t.text, back.text);
        assertEquals(t.size(), back.size());
        assertEquals(t.base, back.base, 1e-12);
        assertEquals(t.mel.get(1)[3][7], back.mel.get(1)[3][7], 0f);
        assertEquals(t.noise.get(2)[5], back.noise.get(2)[5], 0f);
        double strict = t.base * VoiceTemplate.SENSITIVITY_MULT[0];
        double normal = t.base * VoiceTemplate.SENSITIVITY_MULT[1];
        assertTrue(strict < normal && normal < back.threshold());
    }

    @Test
    public void thaiPhraseNormalisation() {
        assertEquals(ThaiText.key("โฮ่ง ๆ"), ThaiText.key("โฮ่งๆ"));
        assertEquals(ThaiText.key("โฮ่ง ๆ"), ThaiText.key(" โฮ่ง  ๆ "));
        assertEquals("โฮ่ง ๆ", ThaiText.display("โฮ่งๆ"));
        assertTrue(ThaiText.isDefault("“น้องเม”"));
        assertTrue(ThaiText.isDefault("น้อง​เม"));
        assertFalse(ThaiText.sameKey("น้องเม", "น้องแม่"));
        assertFalse(ThaiText.sameKey("น้องเม", "น้องเมย์"));
        assertEquals(ThaiText.key("ทำ"), ThaiText.key("ทํา"));
        assertEquals(ThaiText.key("cheese"), ThaiText.key("Cheese!"));
        assertTrue(ThaiText.likelyTooShort("ไป", 300));
        assertFalse(ThaiText.likelyTooShort("น้องเม", 520));
    }
}
