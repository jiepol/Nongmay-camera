package com.nongmay.camera.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;

/**
 * Foreground-only microphone loop for Voice Shutter. Started in onResume of the camera screen and
 * stopped in onPause / when video recording starts, so the microphone is never used in the
 * background. Audio is processed in 20 ms chunks and immediately discarded.
 */
public final class AudioKwsEngine {
    public interface Listener {
        void onTrigger(KwsCore.Event e, long speechEndElapsedNs, long decisionElapsedNs);
        void onSegment(KwsCore.Event e);
        void onLevel(float dbfs);
        void onEngineError(String message);
        void onSilenced(boolean silenced);
    }

    private static final int SR = Mfcc.SAMPLE_RATE;
    private final Context ctx;
    private final KwsCore core = new KwsCore(new KwsCore.Config());
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread thread;
    private volatile Listener listener;

    public AudioKwsEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public KwsCore core() {
        return core;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean hasPermission() {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public void setTemplate(VoiceTemplate t) {
        core.setTemplate(t);
    }

    public void suppressFor(int ms) {
        core.suppressFor(ms);
    }

    /** @return false if the microphone could not be opened (permission, used by a call, …). */
    public synchronized boolean start(VoiceTemplate template, boolean enrollMode, Listener l) {
        stop();
        listener = l;
        if (!hasPermission()) {
            postError("ยังไม่ได้อนุญาตไมโครโฟน");
            return false;
        }
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, SR / 5 * 2) * 2;
        final AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bytes);
        } catch (SecurityException | IllegalArgumentException e) {
            postError("เปิดไมโครโฟนไม่ได้: " + e.getMessage());
            return false;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            postError("ไมโครโฟนไม่พร้อม (อาจมีแอปอื่นหรือสายโทรศัพท์ใช้อยู่)");
            return false;
        }
        core.reset();
        core.setTemplate(template);
        core.setEnrollMode(enrollMode);
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            rec.release();
            postError("เริ่มฟังไม่ได้: " + e.getMessage());
            return false;
        }
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release();
            postError("ไมโครโฟนถูกใช้โดยแอปอื่นอยู่");
            return false;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(rec);
            }
        }, "nm-voice");
        thread.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop(AudioRecord rec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buf = new short[SR / 50];
        AudioTimestamp ts = new AudioTimestamp();
        long lastLevel = 0;
        long zeroSamples = 0;
        boolean silenced = false;
        try {
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) {
                    postError("อ่านเสียงจากไมโครโฟนไม่ได้ (รหัส " + n + ")");
                    break;
                }
                if (n == 0) continue;
                long now = SystemClock.elapsedRealtimeNanos();
                double sum = 0;
                boolean allZero = true;
                for (int i = 0; i < n; i++) {
                    sum += (double) buf[i] * buf[i];
                    if (buf[i] != 0) allZero = false;
                }
                zeroSamples = allZero ? zeroSamples + n : 0;
                boolean nowSilenced = zeroSamples > SR * 2L;
                if (nowSilenced != silenced) {
                    silenced = nowSilenced;
                    final boolean s = silenced;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l != null) l.onSilenced(s);
                        }
                    });
                }
                List<KwsCore.Event> events = core.feed(buf, 0, n);
                long ms = SystemClock.uptimeMillis();
                if (ms - lastLevel > 100) {
                    lastLevel = ms;
                    final float db = (float) (10 * Math.log10(sum / n / (32768.0 * 32768.0) + 1e-12));
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l != null) l.onLevel(db);
                        }
                    });
                }
                if (events.isEmpty()) continue;
                boolean haveTs = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS;
                long seen = core.samplesSeen();
                for (final KwsCore.Event e : events) {
                    final long endNs = haveTs
                            ? ts.nanoTime + (e.speechEndSample - ts.framePosition) * 1_000_000_000L / SR
                            : now - (seen - e.speechEndSample) * 1_000_000_000L / SR;
                    final long decNs = now;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l == null) return;
                            l.onSegment(e);
                            if (e.type == KwsCore.Event.TRIGGER) l.onTrigger(e, endNs, decNs);
                        }
                    });
                }
            }
        } finally {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
        }
    }

    private void postError(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onEngineError(msg);
            }
        });
    }
}
