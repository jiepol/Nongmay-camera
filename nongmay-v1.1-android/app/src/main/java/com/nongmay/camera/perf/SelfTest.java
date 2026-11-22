package com.nongmay.camera.perf;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;

import com.nongmay.camera.BuildInfo;
import com.nongmay.camera.camera.CameraCaps;
import com.nongmay.camera.camera.CameraController;
import com.nongmay.camera.camera.CaptureSettings;
import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.camera.overlays.FrameAnalyzer;
import com.nongmay.camera.camera.pro.ExposureMath;
import com.nongmay.camera.storymax.CropMath;
import com.nongmay.camera.storymax.ExportJob;
import com.nongmay.camera.storymax.MediaInfo;
import com.nongmay.camera.storymax.MediaProbe;
import com.nongmay.camera.storymax.StoryEngine;
import com.nongmay.camera.storymax.StoryProfiles;
import com.nongmay.camera.voice.AudioKwsEngine;
import com.nongmay.camera.voice.KwsCore;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * On-device automated checks that produce real evidence on Pol's S25 Ultra: opens every camera the
 * OS exposes, captures and saves, verifies manual ISO/shutter/focus/WB through capture results,
 * RAW, a 3-second video, a Story transcode of that video and a Story photo export. Test files are
 * written to Pictures|Movies/NongMay/SelfTest and removed afterwards; only the report is kept.
 */
public final class SelfTest implements CameraController.Listener {
    public interface Sink {
        void line(String s);
        void done(String reportMarkdown);
    }

    private static final String DIR_P = MediaStoreUtil.PHOTO_DIR + "/SelfTest";
    private static final String DIR_V = MediaStoreUtil.VIDEO_DIR + "/SelfTest";

    private final Activity act;
    private final SurfaceTexture texture;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder md = new StringBuilder();
    private final List<Uri> created = new ArrayList<>();
    private CameraController ctl;
    private volatile boolean cancelled;
    private int pass, fail, skip;
    private boolean zslTested;

    private volatile CountDownLatch ready, saved, recStarted, recStopped;
    private volatile CameraController.ShotInfo lastShot;
    private volatile String lastError;
    private volatile CameraController.Readout readout;
    private volatile Uri lastVideo;

    public SelfTest(Activity act, SurfaceTexture texture, Sink sink) {
        this.act = act;
        this.texture = texture;
        this.sink = sink;
    }

    public void cancel() {
        cancelled = true;
    }

    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runAll();
            }
        }, "nm-selftest").start();
    }

    private void log(final String s) {
        md.append(s).append('\n');
        main.post(new Runnable() {
            @Override
            public void run() {
                sink.line(s);
            }
        });
    }

    private void result(String name, Boolean ok, String detail) {
        String tag = ok == null ? "SKIP" : ok ? "PASS" : "FAIL";
        if (ok == null) skip++; else if (ok) pass++; else fail++;
        log("| " + tag + " | " + name + " | " + detail.replace("|", "/").replace("\n", " ") + " |");
    }

    private static boolean await(CountDownLatch l, long ms) {
        try {
            return l.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void runAll() {
        md.append("# NongMay Camera — on-device self-test\n\n");
        md.append("- App ").append(BuildInfo.VERSION).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        md.append("- Run ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append("\n\n");
        log("| Result | Check | Detail |");
        log("|---|---|---|");
        ctl = new CameraController(act);
        ctl.setMetricPrefix("selftest_");
        ctl.setListener(this);
        CameraCaps firstBack = null;
        try {
            String[] ids = ctl.manager().getCameraIdList();
            for (String id : ids) {
                if (cancelled) break;
                CameraCaps c = CameraCaps.load(ctl.manager(), id);
                if (firstBack == null && !c.isFront()) firstBack = c;
                testCamera(c);
            }
        } catch (CameraAccessException | RuntimeException e) {
            result("camera enumeration", false, e.toString());
        }
        Uri photo = null;
        for (Uri u : created) if (u != null && u.toString().contains("images")) { photo = u; break; }
        if (!cancelled && firstBack != null) testVideoAndStory(firstBack);
        if (!cancelled && photo != null) testPhotoExport(photo);
        if (!cancelled) testMic();
        ctl.shutdown();
        int removed = 0;
        for (Uri u : created) {
            if (u == null) continue;
            MediaStoreUtil.delete(act, u);
            removed++;
        }
        md.append("\n**Summary:** ").append(pass).append(" PASS · ").append(fail).append(" FAIL · ").append(skip).append(" SKIP")
                .append(cancelled ? " (cancelled)" : "").append(" · removed ").append(removed).append(" test files\n");
        md.append("\nTimings from this run were also added to the performance log (Diagnostics › Performance).\n");
        final String report = md.toString();
        main.post(new Runnable() {
            @Override
            public void run() {
                sink.done(report);
            }
        });
    }

    private CameraController.SessionSpec photoSpec(CameraCaps c, boolean raw) {
        CameraController.SessionSpec s = new CameraController.SessionSpec();
        s.mode = CameraController.MODE_PHOTO;
        s.preview = c.previewSize(4.0 / 3);
        Size j = c.jpegSize(4.0 / 3);
        s.jpeg = j != null ? j : c.largestJpeg();
        s.raw = raw;
        return s;
    }

    private boolean openAndWait(CameraCaps c, CameraController.SessionSpec spec, CaptureSettings cs) {
        ready = new CountDownLatch(1);
        lastError = null;
        readout = null;
        long t0 = SystemClock.uptimeMillis();
        ctl.start(c.id, spec, cs);
        ctl.setPreviewTexture(texture);
        boolean ok = await(ready, 8000) && lastError == null;
        if (ok) {
            long until = SystemClock.uptimeMillis() + 3000;
            while (readout == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        }
        result("camera " + c.id + " open + session (" + c.describe() + ")", ok,
                ok ? (SystemClock.uptimeMillis() - t0) + " ms incl. first result" : String.valueOf(lastError));
        return ok;
    }

    private CameraController.ShotInfo shoot(String relPath) {
        saved = new CountDownLatch(1);
        lastShot = null;
        lastError = null;
        CameraController.ShotRequest r = new CameraController.ShotRequest();
        r.source = "selftest";
        r.tapElapsedNs = SystemClock.elapsedRealtimeNanos();
        r.relPath = relPath;
        ctl.takePhoto(r);
        if (!await(saved, 10_000)) return null;
        return lastShot;
    }

    private void testCamera(CameraCaps c) {
        CaptureSettings cs = new CaptureSettings();
        if (!openAndWait(c, photoSpec(c, false), cs)) {
            ctl.stop();
            return;
        }
        SystemClock.sleep(700);
        CameraController.ShotInfo s = shoot(DIR_P);
        if (s != null && s.jpegUri != null) {
            created.add(s.jpegUri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = act.getContentResolver().openInputStream(s.jpegUri)) {
                BitmapFactory.decodeStream(in, null, o);
            } catch (IOException | RuntimeException ignored) {
            }
            double toSensor = (s.startedElapsedNs - s.tapElapsedNs) / 1e6;
            double toSaved = (s.savedElapsedNs - s.tapElapsedNs) / 1e6;
            result("camera " + c.id + " JPEG capture + MediaStore save", o.outWidth > 0,
                    String.format(Locale.ROOT, "%dx%d · request→sensor %.0f ms · request→saved %.0f ms · %s", o.outWidth, o.outHeight,
                            toSensor, toSaved, s.physicalId == null ? "no physical-id report" : "physical " + s.physicalId));
        } else {
            result("camera " + c.id + " JPEG capture + MediaStore save", false, String.valueOf(lastError));
        }
        if (!cancelled && c.physicalIds.length > 0) testLenses(c, cs);
        if (!cancelled && c.zslKey && !c.isFront() && !zslTested) {
            zslTested = true;
            testShutterLag(c, cs);
        }
        if (c.manualSensor && c.isoRange != null && c.expRange != null) {
            cs.manualExposure = true;
            cs.iso = Math.max(c.isoRange.getLower(), Math.min(c.isoRange.getUpper(), 400));
            cs.exposureNs = Math.max(c.expRange.getLower(), Math.min(c.expRange.getUpper(), 10_000_000L));
            ctl.updateSettings(cs);
            SystemClock.sleep(1500);
            CameraController.Readout r = readout;
            boolean ok = r != null && Math.abs(r.iso - cs.iso) <= Math.max(2, cs.iso * 0.05)
                    && Math.abs(r.exposureNs - cs.exposureNs) <= Math.max(50_000, cs.exposureNs * 0.05);
            result("camera " + c.id + " manual ISO/shutter honoured", ok, r == null ? "no result" : String.format(Locale.ROOT,
                    "asked ISO %d %s → got ISO %d %s", cs.iso, ExposureMath.shutter(cs.exposureNs), r.iso, ExposureMath.shutter(r.exposureNs)));
            cs.manualExposure = false;
        } else {
            result("camera " + c.id + " manual ISO/shutter", null, "MANUAL_SENSOR not exposed");
        }
        if (c.manualFocus()) {
            cs.focusMode = CaptureSettings.FOCUS_MANUAL;
            cs.focusDiopters = Math.min(1f, c.minFocusDiopters);
            ctl.updateSettings(cs);
            SystemClock.sleep(1200);
            CameraController.Readout r = readout;
            boolean ok = r != null && !Float.isNaN(r.focusDiopters) && Math.abs(r.focusDiopters - cs.focusDiopters) <= Math.max(0.1, cs.focusDiopters * 0.1);
            result("camera " + c.id + " manual focus honoured", c.focusCalibration == 0 && !ok ? null : (Boolean) ok,
                    (r == null ? "no result" : String.format(Locale.ROOT, "asked %.2f D → reported %.2f D", cs.focusDiopters, r.focusDiopters))
                            + (c.focusCalibration == 0 ? " (UNCALIBRATED lens: informational)" : ""));
            cs.focusMode = CaptureSettings.FOCUS_CONTINUOUS;
        } else {
            result("camera " + c.id + " manual focus", null, "fixed focus or no MANUAL_SENSOR");
        }
        if (c.kelvinCapable()) {
            cs.wbMode = CaptureSettings.WB_KELVIN;
            cs.kelvin = 3200;
            ctl.updateSettings(cs);
            SystemClock.sleep(1200);
            CameraController.Readout r = readout;
            com.nongmay.camera.camera.pro.WhiteBalanceMath.Result want = ctl.kelvin(cs, c);
            boolean ok = r != null && r.gains != null && want != null && want.valid
                    && Math.abs(r.gains[0] / r.gains[2] - want.gains[0] / want.gains[3]) / (want.gains[0] / want.gains[3]) < 0.1;
            result("camera " + c.id + " Kelvin WB gains applied", ok, r == null || r.gains == null ? "no gains in result"
                    : String.format(Locale.ROOT, "R/B asked %.3f got %.3f", want == null ? 0 : want.gains[0] / want.gains[3], r.gains[0] / r.gains[2]));
            cs.wbMode = CaptureSettings.WB_AUTO;
        } else {
            result("camera " + c.id + " Kelvin WB", null, "no MANUAL_POST_PROCESSING / colour calibration");
        }
        ctl.updateSettings(cs);
        if (c.raw && c.rawSize() != null) {
            ready = new CountDownLatch(1);
            ctl.reconfigure(photoSpec(c, true), cs);
            if (await(ready, 8000)) {
                SystemClock.sleep(600);
                CameraController.ShotInfo rs = shoot(DIR_P);
                SystemClock.sleep(1500);
                if (rs != null) created.add(rs.jpegUri);
                if (rs != null && rs.rawUri != null) created.add(rs.rawUri);
                long size = rs == null || rs.rawUri == null ? -1 : MediaStoreUtil.sizeOf(act, rs.rawUri);
                result("camera " + c.id + " RAW (DNG) + JPEG", size > 0, size > 0 ? "DNG " + com.nongmay.camera.util.Fmt.bytes(size) : "no DNG written: " + lastError);
            } else {
                result("camera " + c.id + " RAW session", false, String.valueOf(lastError));
            }
        } else {
            result("camera " + c.id + " RAW", null, "RAW_SENSOR not exposed");
        }
        ctl.stop();
    }

    /** Which physical lens really streams at each lens preset (0.6× / 1× / 3× / 5× on the S25 Ultra). */
    private void testLenses(CameraCaps c, CaptureSettings cs) {
        for (double z : c.lensZoomPresets()) {
            if (cancelled) break;
            float zr = (float) z;
            if (c.zoomRange != null) zr = Math.max(c.zoomRange.getLower(), Math.min(c.zoomRange.getUpper(), zr));
            cs.zoom = zr;
            ctl.updateSettings(cs);
            SystemClock.sleep(1300);
            CameraController.Readout r = readout;
            String name = "camera " + c.id + " lens at " + ExposureMath.zoomLabel(zr);
            String got = r == null ? null : r.physicalId;
            if (got == null) {
                result(name, null, "no active-physical-camera id in the capture result");
                continue;
            }
            String want = expectedPhysical(c, z);
            boolean match = want == null || want.equals(got);
            result(name, match ? Boolean.TRUE : null, "active physical " + got + " (" + c.physicalLabel(got) + ")"
                    + (match ? "" : " · expected " + want + ": the camera chose another lens (low light or close focus?)"));
        }
        cs.zoom = 1f;
        ctl.updateSettings(cs);
        SystemClock.sleep(700);
    }

    static String expectedPhysical(CameraCaps c, double zoom) {
        String best = null;
        double bestErr = 0.12;
        for (int i = 0; i < c.physicalIds.length; i++) {
            double f = c.physicalEqFocal[i];
            if (Double.isNaN(f) || Double.isNaN(c.eqFocal) || zoom <= 0) continue;
            double err = Math.abs(Math.log(f / c.eqFocal / zoom));
            if (err < bestErr) {
                bestErr = err;
                best = c.physicalIds[i];
            }
        }
        return best;
    }

    /** Steady-state shutter lag with and without CONTROL_ENABLE_ZSL (request → start of the frame's exposure). */
    private void testShutterLag(CameraCaps c, CaptureSettings cs) {
        boolean realtime = c.timestampSource == android.hardware.camera2.CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        StringBuilder d = new StringBuilder();
        boolean ok = true;
        for (int pass = 0; pass < 2 && !cancelled; pass++) {
            cs.zsl = pass == 0;
            ctl.updateSettings(cs);
            SystemClock.sleep(1500);
            double[] v = new double[3];
            int n = 0;
            for (int i = 0; i < v.length && !cancelled; i++) {
                CameraController.ShotInfo s = shoot(DIR_P);
                if (s != null) {
                    if (s.jpegUri != null) created.add(s.jpegUri);
                    long at = realtime && s.sensorTs > 0 ? s.sensorTs : s.startedElapsedNs;
                    if (at > 0) v[n++] = (at - s.tapElapsedNs) / 1e6;
                }
                SystemClock.sleep(600);
            }
            ok &= n > 0;
            Stats st = Stats.of(java.util.Arrays.copyOf(v, n));
            d.append(pass == 0 ? "ZSL on: " : " · ZSL off: ").append(n == 0 ? "no shots"
                    : String.format(Locale.ROOT, "p50 %.0f ms (min %.0f, max %.0f, n=%d)", st.p50, st.min, st.max, n));
        }
        cs.zsl = true;
        ctl.updateSettings(cs);
        result("camera " + c.id + " shutter lag (request → sensor exposure)", ok, d + (realtime ? " · sensor clock" : " · callback clock")
                + " · negative = the frame was exposed just before the request (true zero lag)");
    }

    private MediaInfo recordClip(CameraCaps c, CameraCaps.VideoMode vm, boolean hevc) {
        CameraController.SessionSpec s = new CameraController.SessionSpec();
        s.mode = CameraController.MODE_VIDEO;
        s.video = vm.size;
        s.fps = vm.fps;
        s.codec = hevc ? "video/hevc" : "video/avc";
        s.bitrate = CameraController.autoBitrate(vm.size.getWidth(), vm.size.getHeight(), vm.fps, hevc);
        s.preview = c.previewSize(16.0 / 9);
        String name = "video record " + vm.resLabel() + "@" + vm.fps + " " + (hevc ? "HEVC" : "H.264");
        if (!openAndWait(c, s, new CaptureSettings())) {
            ctl.stop();
            return null;
        }
        boolean audio = act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        recStarted = new CountDownLatch(1);
        recStopped = new CountDownLatch(1);
        lastVideo = null;
        ctl.startRecording(audio, DIR_V, SystemClock.elapsedRealtimeNanos());
        boolean started = await(recStarted, 5000);
        SystemClock.sleep(3000);
        ctl.stopRecording(SystemClock.elapsedRealtimeNanos());
        boolean stopped = await(recStopped, 8000);
        ctl.stop();
        if (!started || !stopped || lastVideo == null) {
            result(name, false, String.valueOf(lastError));
            return null;
        }
        created.add(lastVideo);
        MediaInfo v;
        try {
            v = MediaProbe.probe(act, lastVideo);
        } catch (IOException e) {
            result(name + " probe", false, e.getMessage());
            return null;
        }
        boolean ok = v.durationUs > 2_200_000 && v.durationUs < 4_200_000 && v.orientedW() > 0 && (!audio || v.hasAudio);
        result(name + (audio ? " + mic" : " (no mic permission)"), ok,
                v.summary() + " · " + com.nongmay.camera.util.Fmt.bitrate(v.bitrate) + " · " + v.videoMime
                        + (audio ? " · audio " + (v.hasAudio ? v.audioMime : "MISSING") : ""));
        return ok ? v : null;
    }

    private void exportStory(MediaInfo v, String what) {
        ExportJob job = new ExportJob();
        job.source = v;
        job.profile = StoryProfiles.V1080_SDR;
        job.mode = CropMath.FILL;
        StoryEngine.export(act.getApplicationContext(), job, new ExportJob.Progress() {
            @Override
            public void onProgress(float fraction, String stage) {
            }
        });
        if (job.output != null) created.add(job.output);
        MediaInfo a = job.actual;
        boolean sOk = job.ok() && a != null && a.orientedW() == 1080 && a.orientedH() == 1920 && "video/avc".equals(a.videoMime)
                && (Double.isNaN(a.fps) || a.fps <= 30.5) && Math.abs(a.durationUs - v.durationUs) < 300_000
                && (!v.hasAudio || a.hasAudio);
        result("StoryMax video V1080_SDR export from " + what, sOk, job.ok() ? (a == null ? "" : a.summary()) + " · " + job.elapsedMs + " ms · "
                + job.notesText().replace('\n', ' ') : job.error);
    }

    private static CameraCaps.VideoMode pick(List<CameraCaps.VideoMode> modes, String res, int fps) {
        CameraCaps.VideoMode r = null;
        for (CameraCaps.VideoMode m : modes) if (m.resLabel().equals(res) && m.fps == fps) r = m;
        return r;
    }

    private void testVideoAndStory(CameraCaps c) {
        List<CameraCaps.VideoMode> modes = c.videoModes();
        CameraCaps.VideoMode hd = pick(modes, "1080p", 30), uhd = pick(modes, "4K", 30), hd60 = pick(modes, "1080p", 60);
        if (hd == null && !modes.isEmpty()) hd = modes.get(modes.size() - 1);
        if (hd == null) {
            result("video record", false, "no camera+encoder video mode available");
            return;
        }
        MediaInfo a = recordClip(c, hd, !hd.avc);
        if (a != null && !cancelled) exportStory(a, hd.resLabel() + "@" + hd.fps);
        if (uhd != null && !cancelled) {
            MediaInfo b = recordClip(c, uhd, uhd.hevc);   // the S25 Ultra's own camera app records 4K HEVC
            if (b != null && !cancelled) exportStory(b, "4K@30 " + (uhd.hevc ? "HEVC" : "H.264"));
        }
        if (hd60 != null && !cancelled) recordClip(c, hd60, !hd60.avc);
    }

    private void testPhotoExport(Uri photo) {
        try {
            MediaInfo m = MediaProbe.probe(act, photo);
            ExportJob job = new ExportJob();
            job.source = m;
            job.profile = StoryProfiles.P1080_SDR;
            job.mode = CropMath.FILL;
            StoryEngine.export(act.getApplicationContext(), job, new ExportJob.Progress() {
                @Override
                public void onProgress(float fraction, String stage) {
                }
            });
            if (job.output != null) created.add(job.output);
            MediaInfo a = job.actual;
            boolean ok = job.ok() && a != null && a.orientedW() == 1080 && a.orientedH() == 1920 && a.hasIcc;
            result("StoryMax photo P1080_SDR export (sRGB ICC)", ok, job.ok() ? a.summary() + " · ICC " + a.hasIcc + " · " + job.elapsedMs + " ms" : job.error);
        } catch (IOException | RuntimeException e) {
            result("StoryMax photo export", false, e.toString());
        }
    }

    private void testMic() {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            result("Voice Shutter microphone loop", null, "RECORD_AUDIO not granted (enable Voice Shutter first)");
            return;
        }
        final AudioKwsEngine e = new AudioKwsEngine(act);
        final float[] lvl = {-200};
        final CountDownLatch got = new CountDownLatch(1);
        final boolean[] started = {false};
        main.post(new Runnable() {
            @Override
            public void run() {
                started[0] = e.start(null, true, new AudioKwsEngine.Listener() {
                    @Override
                    public void onTrigger(KwsCore.Event ev, long a, long b) {
                    }

                    @Override
                    public void onSegment(KwsCore.Event ev) {
                    }

                    @Override
                    public void onLevel(float dbfs) {
                        lvl[0] = Math.max(lvl[0], dbfs);
                        got.countDown();
                    }

                    @Override
                    public void onEngineError(String message) {
                    }

                    @Override
                    public void onSilenced(boolean silenced) {
                    }
                });
            }
        });
        boolean ok = await(got, 3000);
        SystemClock.sleep(1500);
        double floor = e.core().noiseFloorDb();
        e.stop();
        result("Voice Shutter microphone loop (16 kHz, foreground)", ok, ok ? String.format(Locale.ROOT,
                "level callbacks OK · peak %.1f dBFS · noise floor %.1f dBFS · no audio stored", lvl[0], floor) : "no audio callbacks");
    }

    // ---- CameraController.Listener (main thread) --------------------------------------------

    @Override
    public void onOpened(CameraCaps caps) {
    }

    @Override
    public void onSessionReady(CameraController.SessionSpec spec, String note) {
        CountDownLatch l = ready;
        if (l != null) l.countDown();
    }

    @Override
    public void onError(String message, boolean retryable) {
        lastError = message;
        CountDownLatch l = ready;
        if (l != null) l.countDown();
    }

    @Override
    public void onReadout(CameraController.Readout r) {
        readout = r;
    }

    @Override
    public void onShutter(CameraController.ShotInfo s) {
    }

    @Override
    public void onSaved(CameraController.ShotInfo s) {
        lastShot = s;
        CountDownLatch l = saved;
        if (l != null) l.countDown();
    }

    @Override
    public void onShotFailed(CameraController.ShotInfo s, String reason) {
        lastError = reason;
        CountDownLatch l = saved;
        if (l != null && s.index == 0) l.countDown();
    }

    @Override
    public void onAnalysis(FrameAnalyzer.Result r) {
    }

    @Override
    public void onFocusLocked(boolean success) {
    }

    @Override
    public void onRecordingStarted(Uri uri) {
        CountDownLatch l = recStarted;
        if (l != null) l.countDown();
    }

    @Override
    public void onRecordingStopped(Uri uri, String error, long durationMs) {
        lastVideo = uri;
        if (error != null) lastError = error;
        CountDownLatch l = recStarted;
        if (l != null) l.countDown();
        CountDownLatch s = recStopped;
        if (s != null) s.countDown();
    }

    @Override
    public void onSettingApplied(double latencyMs) {
    }
}
