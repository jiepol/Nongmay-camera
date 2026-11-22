/**
 * Parameter sweep used to choose the shipped Voice Shutter settings (see VOICE_EVAL.md).
 * Usage: java -cp build/unit/classes:build/voice_eval VoiceSweep build/voice_corpus clamp,room,endRelDb[,relFloorDb] ...
 * e.g.  ... VoiceSweep build/voice_corpus 1,0,99 1.25,1,14   (prints hits per condition and false accepts per threshold)
 */
import com.nongmay.camera.voice.*;
import java.util.*;
import java.util.concurrent.*;
public class VoiceSweep {
  static final class Rec { String grp, cond; int utt; boolean hard; double ratio, s, md; }
  public static void main(String[] a) throws Exception {
    VoiceEval.root = new java.io.File(a[0]);
    VoiceEval.load();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    for (int ai = 1; ai < a.length; ai++) {
      String[] p = a[ai].split(",");
      final KwsCore.Config cfg = new KwsCore.Config();
      cfg.noiseClamp = Double.parseDouble(p[0]); cfg.room = Integer.parseInt(p[1]); cfg.endRelDb = Double.parseDouble(p[2]);
      if (p.length > 3) cfg.relFloorDb = Double.parseDouble(p[3]);
      final List<Rec> recs = Collections.synchronizedList(new ArrayList<Rec>());
      final int[] tpl = {0};
      List<Future<?>> fs = new ArrayList<>();
      for (final String s : VoiceEval.SPK) for (final String k : VoiceEval.KW) fs.add(pool.submit(new Runnable() { public void run() {
        VoiceTemplate t = VoiceEval.enroll(cfg, s, k);
        if (t.isReady()) synchronized (tpl) { tpl[0]++; }
        List<Object[]> jobs = new ArrayList<>();
        for (String c : VoiceEval.COND) { jobs.add(new Object[]{"pos", VoiceEval.get("pos", s, k, c), c}); jobs.add(new Object[]{"neg", VoiceEval.get("neg", s, "-", c), c}); }
        for (String o : VoiceEval.SPK) if (!o.equals(s)) jobs.add(new Object[]{"cross", VoiceEval.get("pos", o, k, "clean"), "clean"});
        jobs.add(new Object[]{"idle", VoiceEval.get("idle", "-", "-", "quiet"), "quiet"});
        jobs.add(new Object[]{"idle", VoiceEval.get("idle", "-", "-", "cafe"), "cafe"});
        for (Object[] j : jobs) {
          VoiceEval.Stream st = (VoiceEval.Stream) j[1];
          KwsCore core = new KwsCore(cfg); core.setTemplate(t);
          for (KwsCore.Event e : VoiceEval.run(core, st.pcm, null)) {
            Rec r = new Rec(); r.grp = (String) j[0]; r.cond = (String) j[2];
            r.utt = r.grp.equals("idle") ? -1 : VoiceEval.which(st, e.speechEndSample);
            if ((r.grp.equals("pos") || r.grp.equals("cross")) && r.utt < 0) r.grp = r.grp + "-extra";
            r.utt = r.utt + 1000 * (s.hashCode() % 97) + 100000 * k.hashCode() % 1000003 + st.path.hashCode() * 7;
            r.hard = Double.isNaN(e.score);
            r.ratio = e.durMs / Math.max(1.0, t.avgDurMs());
            r.s = e.score / t.base; r.md = e.median / t.base;
            recs.add(r);
          }
        }
      }}));
      for (Future<?> f : fs) f.get();
      System.out.println("== clamp=" + cfg.noiseClamp + " room=" + cfg.room + " endRel=" + cfg.endRelDb + " relFloor=" + cfg.relFloorDb + " templates=" + tpl[0]);
      System.out.println("  T    minDur | quiet white20 pink10 babble15 reverb | negFA idleQ idleC cross extra");
      for (double minDur : new double[]{0.55, 0.45}) for (double T : new double[]{1.1, 1.2, 1.3, 1.4, 1.5, 1.6}) {
        Map<String, Set<Integer>> hits = new HashMap<>();
        int neg = 0, iq = 0, ic = 0, extra = 0; Set<Integer> cross = new HashSet<>();
        synchronized (recs) { for (Rec r : recs) {
          boolean trig = !r.hard && r.ratio >= minDur && r.ratio <= 1.8 && r.s <= T && r.md <= T * 1.25;
          if (!trig) continue;
          switch (r.grp) {
            case "pos": hits.computeIfAbsent(r.cond, x -> new HashSet<>()).add(r.utt); break;
            case "neg": neg++; break;
            case "idle": if (r.cond.equals("quiet")) iq++; else ic++; break;
            case "cross": cross.add(r.utt); break;
            default: extra++;
          }
        } }
        StringBuilder sb = new StringBuilder(String.format("  %.1f  %.2f  |", T, minDur));
        for (String c : VoiceEval.COND) sb.append(String.format(" %6d", hits.containsKey(c) ? hits.get(c).size() : 0));
        sb.append(String.format("  | %5d %5d %5d %5d %5d", neg, iq, ic, cross.size(), extra));
        System.out.println(sb);
      }
    }
    pool.shutdown();
  }
}
