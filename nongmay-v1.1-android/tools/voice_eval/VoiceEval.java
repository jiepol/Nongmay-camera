import com.nongmay.camera.perf.Stats;
import com.nongmay.camera.voice.KwsCore;
import com.nongmay.camera.voice.VoiceTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the app's real KwsCore / Matcher / VoiceTemplate (the same classes that are dexed into the
 * APK) over the synthetic streaming corpus from make_corpus.py and writes VOICE_EVAL.md.
 *
 * java -cp build/unit/classes:build/voice_eval VoiceEval build/voice_corpus VOICE_EVAL.md
 */
public final class VoiceEval {
    static final String[] SPK = {"S1", "S2", "S3", "S4"};
    static final String[] KW = {"nongme", "hong", "charlie", "thailoey", "cheese"};
    static final String[] KW_TEXT = {"น้องเม", "โฮ่ง ๆ", "ชาลี", "ถ่ายเลย", "cheese"};
    static final String[] COND = {"clean", "white20", "pink10", "babble15", "reverb"};
    static final String[] COND_TEXT = {"quiet", "white noise 20 dB", "street-like pink noise 10 dB", "other people talking 15 dB", "room reverb"};
    static final String[] SENS = {"STRICT", "NORMAL", "SENSITIVE"};
    static final int SR = 16000;

    static final class Stream {
        String group, spk, kw, cond, path;
        long[][] iv;
        String[] texts;
        short[] pcm;
    }

    static final Map<String, Stream> streams = new LinkedHashMap<>();
    static File root;

    static short[] wav(File f) throws IOException {
        byte[] b = Files.readAllBytes(f.toPath());
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int p = 12;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, StandardCharsets.US_ASCII);
            int len = bb.getInt(p + 4);
            if (id.equals("data")) {
                short[] s = new short[len / 2];
                for (int i = 0; i < s.length; i++) s[i] = bb.getShort(p + 8 + 2 * i);
                return s;
            }
            p += 8 + len + (len & 1);
        }
        throw new IOException("no data chunk: " + f);
    }

    static void load() throws IOException {
        for (String line : Files.readAllLines(new File(root, "manifest.tsv").toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            String[] c = line.split("\t", -1);
            Stream s = new Stream();
            s.group = c[0]; s.spk = c[1]; s.kw = c[2]; s.cond = c[3]; s.path = c[4];
            String[] iv = c[5].split(";");
            s.iv = new long[iv.length][2];
            for (int i = 0; i < iv.length; i++) {
                String[] ab = iv[i].split(":");
                s.iv[i][0] = Long.parseLong(ab[0]);
                s.iv[i][1] = Long.parseLong(ab[1]);
            }
            s.texts = c[6].isEmpty() ? new String[0] : c[6].split("\\|");
            s.pcm = wav(new File(root, s.path));
            streams.put(s.group + "/" + s.spk + "/" + s.kw + "/" + s.cond, s);
        }
    }

    static Stream get(String g, String spk, String kw, String cond) {
        Stream s = streams.get(g + "/" + spk + "/" + kw + "/" + cond);
        if (s == null) throw new IllegalStateException("missing " + g + "/" + spk + "/" + kw + "/" + cond);
        return s;
    }

    /** Feeds a whole stream in 20 ms chunks (as AudioRecord does); returns events, records compute time. */
    static List<KwsCore.Event> run(KwsCore core, short[] a, List<Double> computeMs) {
        List<KwsCore.Event> out = new ArrayList<>();
        for (int i = 0; i < a.length; i += 320) {
            long t = System.nanoTime();
            List<KwsCore.Event> ev = core.feed(a, i, Math.min(320, a.length - i));
            if (!ev.isEmpty()) {
                if (computeMs != null) computeMs.add((System.nanoTime() - t) / 1e6);
                out.addAll(ev);
            }
        }
        return out;
    }

    /** Same acceptance rules as VoiceActivity's enrollment screen; first 3 good takes. */
    static VoiceTemplate enroll(KwsCore.Config cfg, String spk, String kw) {
        KwsCore core = new KwsCore(cfg);
        core.setEnrollMode(true);
        VoiceTemplate t = new VoiceTemplate(spk + "_" + kw, kw);
        for (KwsCore.Event e : run(core, get("enroll", spk, kw, "clean").pcm, null)) {
            if (t.size() >= 3) break;
            if (e.reject != null || e.mel == null || e.durMs < 220 || e.durMs > 2200) continue;
            t.add(e);
        }
        t.calibrate(cfg);
        return t;
    }

    static int which(Stream s, long speechEnd) {
        for (int i = 0; i < s.iv.length; i++) if (speechEnd >= s.iv[i][0] && speechEnd <= s.iv[i][1] + SR / 2) return i;
        return -1;
    }

    static final class Result {
        int[][][] hit = new int[KW.length][COND.length][1];   // same voice, per kw x cond (out of 24)
        int[][] extra = new int[KW.length][COND.length];     // triggers outside any phrase window
        int[] cross = new int[KW.length];                    // other voices, clean (out of 72)
        int[] negFa = new int[KW.length];                    // other words, all conditions (out of 720)
        int[][] negFaCond = new int[KW.length][COND.length]; // (out of 144)
        Map<String, Integer> negWords = new LinkedHashMap<>();
        int idleQuiet, idleCafe;
        double idleHoursPerTemplate;
        Map<String, Integer> idleByKw = new LinkedHashMap<>();
        List<Double> latency = Collections.synchronizedList(new ArrayList<Double>());
        List<Double> compute = Collections.synchronizedList(new ArrayList<Double>());
        int templates;
    }

    static Result evaluate(final KwsCore.Config cfg, final int sens, ExecutorService pool) throws Exception {
        final Result r = new Result();
        final Map<String, VoiceTemplate> tpl = new LinkedHashMap<>();
        for (String s : SPK) for (String k : KW) {
            VoiceTemplate t = enroll(cfg, s, k);
            t.sensitivity = sens;
            if (t.isReady()) r.templates++;
            tpl.put(s + "_" + k, t);
        }
        List<Future<?>> fs = new ArrayList<>();
        for (int ki0 = 0; ki0 < KW.length; ki0++) {
            for (final String s : SPK) {
                final int ki = ki0;
                fs.add(pool.submit(new Runnable() {
                    public void run() {
                        VoiceTemplate t = tpl.get(s + "_" + KW[ki]);
                        for (int c = 0; c < COND.length; c++) {
                            Stream st = get("pos", s, KW[ki], COND[c]);
                            KwsCore core = new KwsCore(cfg);
                            core.setTemplate(t);
                            boolean[] got = new boolean[st.iv.length];
                            int h = 0, x = 0;
                            for (KwsCore.Event e : VoiceEval.run(core, st.pcm, r.compute)) {
                                if (e.type != KwsCore.Event.TRIGGER) continue;
                                int u = which(st, e.speechEndSample);
                                if (u < 0 || got[u]) { x++; continue; }
                                got[u] = true;
                                h++;
                                if (c == 0) r.latency.add((e.decisionSample - e.speechEndSample) * 1000.0 / SR);
                            }
                            synchronized (r) { r.hit[ki][c][0] += h; r.extra[ki][c] += x; }
                        }
                        for (String o : SPK) {
                            if (o.equals(s)) continue;
                            Stream st = get("pos", o, KW[ki], "clean");
                            KwsCore core = new KwsCore(cfg);
                            core.setTemplate(t);
                            int h = 0;
                            for (KwsCore.Event e : VoiceEval.run(core, st.pcm, null)) if (e.type == KwsCore.Event.TRIGGER && which(st, e.speechEndSample) >= 0) h++;
                            synchronized (r) { r.cross[ki] += h; }
                        }
                        for (int c = 0; c < COND.length; c++) {
                            Stream st = get("neg", s, "-", COND[c]);
                            KwsCore core = new KwsCore(cfg);
                            core.setTemplate(t);
                            for (KwsCore.Event e : VoiceEval.run(core, st.pcm, null)) {
                                if (e.type != KwsCore.Event.TRIGGER) continue;
                                int u = which(st, e.speechEndSample);
                                String w = u < 0 ? "(between words)" : st.texts[u];
                                synchronized (r) {
                                    r.negFa[ki]++;
                                    r.negFaCond[ki][c]++;
                                    String key = KW_TEXT[ki] + " ← " + w;
                                    Integer v = r.negWords.get(key);
                                    r.negWords.put(key, v == null ? 1 : v + 1);
                                }
                            }
                        }
                        for (String idle : new String[]{"quiet", "cafe"}) {
                            Stream st = get("idle", "-", "-", idle);
                            KwsCore core = new KwsCore(cfg);
                            core.setTemplate(t);
                            int n = 0;
                            for (KwsCore.Event e : VoiceEval.run(core, st.pcm, null)) if (e.type == KwsCore.Event.TRIGGER) n++;
                            synchronized (r) {
                                if (idle.equals("quiet")) r.idleQuiet += n; else r.idleCafe += n;
                                Integer v = r.idleByKw.get(KW_TEXT[ki] + " (" + idle + ")");
                                r.idleByKw.put(KW_TEXT[ki] + " (" + idle + ")", (v == null ? 0 : v) + n);
                                r.idleHoursPerTemplate = st.pcm.length / (double) SR / 3600.0;
                            }
                        }
                    }
                }));
            }
        }
        for (Future<?> f : fs) f.get();
        return r;
    }

    static String pct(int a, int b) {
        if (b == 0) return "—";
        return String.format(Locale.ROOT, "%d/%d (%.0f%%)", a, b, 100.0 * a / b);
    }

    static int sumHit(Result r, int c) {
        int s = 0;
        for (int k = 0; k < KW.length; k++) s += r.hit[k][c][0];
        return s;
    }

    static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    static double[] arr(List<Double> l) {
        synchronized (l) {
            double[] r = new double[l.size()];
            for (int i = 0; i < r.length; i++) r[i] = l.get(i);
            return r;
        }
    }

    public static void main(String[] args) throws Exception {
        root = new File(args[0]);
        long t0 = System.nanoTime();
        load();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        KwsCore.Config plain = new KwsCore.Config();
        plain.compensate = false;   // first prototype: plain MFCC/DTW, no room hypothesis, energy-only VAD end
        plain.room = 0;
        plain.noiseClamp = 1.0;
        plain.endRelDb = 99.0;
        KwsCore.Config comp = new KwsCore.Config();
        Result base = evaluate(plain, VoiceTemplate.NORMAL, pool);
        Result[] res = new Result[3];
        for (int s = 0; s < 3; s++) res[s] = evaluate(comp, s, pool);
        pool.shutdown();
        double tplHours = res[0].idleHoursPerTemplate * SPK.length * KW.length;

        StringBuilder md = new StringBuilder();
        md.append("# Voice Shutter — synthetic evaluation (espeak-ng TTS, NOT human speech)\n\n");
        md.append("Generated on the build machine by `tools/voice_eval/make_corpus.py` + `tools/voice_eval/VoiceEval.java`. ")
                .append("The detector is the exact `KwsCore` / `EnergyVad` / `Matcher` / `Mfcc` / `Dtw` / `VoiceTemplate` code that is dexed into the APK, ")
                .append("fed in 20 ms chunks like `AudioRecord`. Audio is continuous streams (the app listens continuously): 4 synthetic Thai voices, ")
                .append("±15% speed, ±8 pitch, level −14…−4 dBFS peak; each voice enrolls each phrase with 3 clean takes using the enrollment screen's rules, ")
                .append("then says it 6 more times per condition. **These numbers show that the pipeline works and how the settings trade off. ")
                .append("They do not predict accuracy with Pol's real voice on the S25 Ultra** — synthetic voices are far more repeatable than people.\n\n");
        md.append(String.format(Locale.ROOT, "Templates built: %d/%d. Run time %.0f s on 2 CPU cores.\n\n", res[1].templates, SPK.length * KW.length,
                (System.nanoTime() - t0) / 1e9));

        md.append("## 1. Shipped matcher vs. the first prototype (NORMAL sensitivity, same threshold)\n\n");
        md.append("Shipped = background tracker + noise-compensated mel matching (clamp 1.25×) + a second “virtual room” hypothesis ")
                .append("(RT60 0.3 s, DRR +6 dB — deliberately not the corpus room of 0.4 s / +4 dB) + VAD pause at 14 dB below the phrase peak. ")
                .append("Prototype = plain MFCC/DTW on the raw segment. The settings were chosen with a parameter sweep on this same corpus, ")
                .append("so treat the shipped numbers as optimistic. Decision gates in 1.1.5: duration ≥ 0.55 × the takes, the two best takes ")
                .append("must agree (2nd best ≤ 1.25 × threshold), sensitivity 1.15 / 1.25 / 1.40 × base. On Pol's phone 1.1.3 (1.30) let a loud ")
                .append("“เฮ้ย” through and 1.1.4 (1.20 + duration 0.65 + agreement 1.10) rejected his real “น้องเม” — real voices vary far more ")
                .append("than these synthetic ones. Owner corrections (“ไม่ใช่คำนี้” / “อันนี้ใช่”) are not part of this corpus.\n\n");
        md.append("| Condition | plain MFCC/DTW (first prototype) | noise-compensated (shipped) |\n|---|---|---|\n");
        for (int c = 0; c < COND.length; c++) {
            md.append("| ").append(COND_TEXT[c]).append(" | ").append(pct(sumHit(base, c), 24 * KW.length)).append(" | ")
                    .append(pct(sumHit(res[1], c), 24 * KW.length)).append(" |\n");
        }
        md.append("| other words accepted (all conditions) | ").append(pct(sum(base.negFa), 720 * KW.length)).append(" | ")
                .append(pct(sum(res[1].negFa), 720 * KW.length)).append(" |\n");
        md.append("| other synthetic voice accepted (quiet) | ").append(pct(sum(base.cross), 72 * KW.length)).append(" | ")
                .append(pct(sum(res[1].cross), 72 * KW.length)).append(" |\n");
        md.append(String.format(Locale.ROOT, "| false triggers in 12 min conversation, quiet / cafe (per template-hour) | %.1f / %.1f | %.1f / %.1f |\n\n",
                base.idleQuiet / tplHours, base.idleCafe / tplHours, res[1].idleQuiet / tplHours, res[1].idleCafe / tplHours));

        for (int s = 0; s < 3; s++) {
            Result r = res[s];
            md.append("## ").append(s + 2).append(". ").append(SENS[s]).append(String.format(Locale.ROOT, " (threshold = %.2f × calibrated base)\n\n",
                    VoiceTemplate.SENSITIVITY_MULT[s]));
            md.append("Same voice says the phrase (6 times × 4 voices per cell):\n\n| Phrase | all | ");
            for (String c : COND_TEXT) md.append(c).append(" | ");
            md.append("other voice (quiet) |\n|---|---|---|---|---|---|---|---|\n");
            for (int k = 0; k < KW.length; k++) {
                int all = 0;
                for (int c = 0; c < COND.length; c++) all += r.hit[k][c][0];
                md.append("| ").append(KW_TEXT[k]).append(" | ").append(pct(all, 24 * COND.length));
                for (int c = 0; c < COND.length; c++) md.append(" | ").append(pct(r.hit[k][c][0], 24));
                md.append(" | ").append(pct(r.cross[k], 72)).append(" |\n");
            }
            md.append("\nOther words by the same voice (18 words × 2 × 5 conditions × 4 voices = 720 per phrase):\n\n| Phrase template | accepted another word |\n|---|---|\n");
            for (int k = 0; k < KW.length; k++) md.append("| ").append(KW_TEXT[k]).append(" | ").append(pct(r.negFa[k], 720)).append(" |\n");
            if (!r.negWords.isEmpty()) {
                md.append("\nWhich words got through: ");
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Integer> e : r.negWords.entrySet()) parts.add(e.getKey() + " ×" + e.getValue());
                md.append(String.join(", ", parts)).append("\n");
            }
            int extra = 0;
            for (int[] row : r.extra) extra += sum(row);
            md.append(String.format(Locale.ROOT, "\nFalse triggers during 12 min of synthetic Thai conversation (20 templates, %.1f template-hours per room): "
                    + "**quiet room %d (%.1f/h), cafe %d (%.1f/h)**. Extra triggers outside a spoken phrase in the phrase streams: %d.\n",
                    tplHours, r.idleQuiet, r.idleQuiet / tplHours, r.idleCafe, r.idleCafe / tplHours, extra));
            List<String> idleParts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : r.idleByKw.entrySet()) if (e.getValue() > 0) idleParts.add(e.getKey() + " ×" + e.getValue());
            if (!idleParts.isEmpty()) md.append("Idle triggers by template: ").append(String.join(", ", idleParts)).append("\n");
            md.append("\n");
        }
        Stats lat = Stats.of(arr(res[1].latency));
        Stats cmp = Stats.of(arr(res[1].compute));
        md.append("## 5. Latency (NORMAL)\n\n");
        md.append(String.format(Locale.ROOT, "- End of speech → trigger decision on the audio clock: p50 %.0f ms, p95 %.0f ms (n=%d). This is the 200 ms "
                + "end-of-speech hangover by design (so “น้องเม…” isn't cut mid-word).\n", lat.p50, lat.p95, lat.n));
        md.append(String.format(Locale.ROOT, "- Compute for the 20 ms chunk that ends a segment (VAD + mel + 6 compensated DTW matches: 3 takes × dry/virtual room), build machine JVM: "
                + "p50 %.2f ms, p95 %.2f ms, max %.2f ms. Not measured on the phone.\n", cmp.p50, cmp.p95, cmp.max));
        md.append("- On the phone add AudioRecord buffering (~20–40 ms, NOT MEASURED) and the capture request; the app logs real "
                + "`voice_end_to_capture_ms` in Diagnostics.\n\n");
        md.append("## How to read this\n\n");
        md.append("- “same voice” = the voice that enrolled says the phrase again (the real use case).\n");
        md.append("- “other voice” = someone else says the same phrase. The matcher is speaker-dependent on purpose; it is not a lock, "
                + "a similar voice can pass.\n");
        md.append("- The conversation script deliberately contains sentences with “น้องเม”, “ชาลี”, “ถ่ายเลย” and “ชีสเค้ก” inside them; the app is meant "
                + "to fire only on the phrase said on its own.\n");
        md.append("- Next step is Pol's own voice on the S25 Ultra: Voice screen → test mode shows score vs. threshold for every attempt.\n");
        Files.write(new File(args[1]).toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(md);
    }
}
