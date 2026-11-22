package com.nongmay.camera.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.nongmay.camera.camera.overlays.FrameAnalyzer;
import com.nongmay.camera.camera.pro.WhiteBalanceMath;
import com.nongmay.camera.perf.PerfLog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Camera2 engine. Everything that touches the camera runs on one background thread ("nm-camera");
 * JPEG/RAW buffers arrive on "nm-image", preview analysis on "nm-analysis", file writes on an IO
 * executor. Listener callbacks are always delivered on the main thread.
 *
 * Slider changes (ISO, shutter, WB, focus, zoom, EV) only rebuild the repeating request; the
 * session is re-created only for things that change streams (mode, aspect, RAW, camera).
 */
public final class CameraController {
    public static final int MODE_PHOTO = 0, MODE_VIDEO = 1;

    public static final class SessionSpec {
        public int mode = MODE_PHOTO;
        public Size preview;
        public Size jpeg;
        public boolean raw;
        public Size analysis;
        public Size video;
        public int fps = 30;
        public String codec = "video/avc";
        public int bitrate = 20_000_000;

        public SessionSpec copy() {
            SessionSpec s = new SessionSpec();
            s.mode = mode; s.preview = preview; s.jpeg = jpeg; s.raw = raw; s.analysis = analysis;
            s.video = video; s.fps = fps; s.codec = codec; s.bitrate = bitrate;
            return s;
        }
    }

    public static final class ShotRequest {
        public int count = 1;
        public double[] bracketEv;
        public String source = "button";
        public long tapElapsedNs;
        public long voiceEndElapsedNs;  // utterance end (voice shutter), for the voice latency metric
        public int cropAspect;          // 0 = native, 1 = 1:1, 2 = 3:2 (centre crop, re-encoded)
        public boolean mirror;
        public String relPath = MediaStoreUtil.PHOTO_DIR;
    }

    public static final class ShotInfo {
        public final int id;
        public String source;
        public int index, count;
        public long tapElapsedNs, requestElapsedNs, startedElapsedNs, sensorTs, jpegElapsedNs, savedElapsedNs;
        public Uri jpegUri, rawUri;
        public String fileName;
        public String error;
        public int requestedIso;
        public long requestedExposureNs;
        public int iso;
        public long exposureNs;
        public float focusDiopters = Float.NaN;
        public String physicalId;
        int jpegOrientation;
        int cropAspect;
        boolean mirror;
        String relPath;
        String baseName;
        boolean wantRaw, rawWriting, rawDone, jpegDone;
        TotalCaptureResult result;
        Image rawImage;

        ShotInfo(int id) {
            this.id = id;
        }
    }

    public static final class Readout {
        public int iso;
        public long exposureNs, frameDurationNs;
        public float focusDiopters = Float.NaN, aperture = Float.NaN, focal = Float.NaN;
        public int afState = -1, aeState = -1, awbState = -1;
        public String physicalId;
        public float zoomRatio = Float.NaN;
        public float[] gains;
        public boolean oisOn;
    }

    public interface Listener {
        void onOpened(CameraCaps caps);
        void onSessionReady(SessionSpec spec, String note);
        void onError(String message, boolean retryable);
        void onReadout(Readout r);
        void onShutter(ShotInfo s);
        void onSaved(ShotInfo s);
        void onShotFailed(ShotInfo s, String reason);
        void onAnalysis(FrameAnalyzer.Result r);
        void onFocusLocked(boolean success);
        void onRecordingStarted(Uri uri);
        void onRecordingStopped(Uri uri, String error, long durationMs);
        void onSettingApplied(double latencyMs);
    }

    private static final int CS_IDLE = 0, CS_WAIT_PRECAPTURE = 1, CS_WAIT_NON_PRECAPTURE = 2, CS_WAIT_AF = 3;

    private final Context ctx;
    private final CameraManager mgr;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread camThread = new HandlerThread("nm-camera");
    private final HandlerThread imgThread = new HandlerThread("nm-image");
    private final HandlerThread anaThread = new HandlerThread("nm-analysis");
    private final Handler camH, imgH, anaH;
    private final Executor camExec;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Listener listener;

    // ---- camera-thread state ------------------------------------------------------------
    private String cameraId;
    private volatile CameraCaps caps;
    private CameraDevice device;
    private CameraCaptureSession session;
    private SurfaceTexture texture;
    private Surface previewSurface;
    private ImageReader jpegReader, rawReader, analysisReader;
    private Surface recorderSurface;
    private volatile SessionSpec spec = new SessionSpec();
    private CaptureSettings settings = new CaptureSettings();
    private boolean configuring, dropAnalysis, dropRaw;
    private int generation;
    private int sessionGen;
    private long openRequestNs;
    private volatile Readout lastReadout;
    private long lastReadoutPostMs;
    private final FrameAnalyzer analyzer = new FrameAnalyzer();
    public final FrameAnalyzer.Config analysisConfig = new FrameAnalyzer.Config();
    private volatile boolean analysisWanted;
    private volatile int analysisIntervalMs = 125;
    private long lastAnalysisMs;
    private volatile int deviceOrientation;
    private WhiteBalanceMath.Result kelvinResult;
    private int kelvinFor = -1, tintFor = Integer.MIN_VALUE;
    private int captureState = CS_IDLE;
    private ShotRequest pendingShot;
    private long captureDeadlineMs;
    private boolean awaitingAf;
    private long settingsChangeNs;
    private CaptureSettings settingsExpect;
    private Runnable resumeContinuousAf;
    // touch state (tap-to-focus/meter, long-press locks) is owned here, not by the UI's settings copy
    private boolean tHasPoint, tAfLock, tAeLock;
    private float tX = 0.5f, tY = 0.5f;
    private volatile boolean focusBeforeCapture;

    private final Object shotLock = new Object();
    private final Map<Integer, ShotInfo> shotsById = new HashMap<>();
    private final Map<Long, ShotInfo> shotsByTs = new HashMap<>();
    private final Map<Long, byte[]> orphanJpeg = new HashMap<>();
    private final Map<Long, Image> orphanRaw = new HashMap<>();
    private int nextShotId = 1;
    private int pendingRawWrites;

    private MediaRecorder recorder;
    private ParcelFileDescriptor recPfd;
    private Uri recUri;
    private volatile boolean recording;
    private boolean recPaused;
    private long recStartMs;

    public CameraController(Context context) {
        ctx = context.getApplicationContext();
        mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        camThread.start();
        imgThread.start();
        anaThread.start();
        camH = new Handler(camThread.getLooper());
        imgH = new Handler(imgThread.getLooper());
        anaH = new Handler(anaThread.getLooper());
        camExec = new Executor() {
            @Override
            public void execute(Runnable r) {
                camH.post(r);
            }
        };
    }

    public static int autoBitrate(int w, int h, int fps, boolean hevc) {
        return VideoRecorder.autoBitrate(w, h, fps, hevc);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public CameraManager manager() {
        return mgr;
    }

    public CameraCaps caps() {
        return caps;
    }

    public SessionSpec spec() {
        return spec;
    }

    public boolean isRecording() {
        return recording;
    }

    public Readout lastReadout() {
        return lastReadout;
    }

    public long openRequestNs() {
        return openRequestNs;
    }

    public void setDeviceOrientation(int degrees) {
        deviceOrientation = degrees;
    }

    public void setAnalysisWanted(boolean on, int intervalMs) {
        analysisWanted = on;
        analysisIntervalMs = intervalMs;
    }

    public void setFocusBeforeCapture(boolean on) {
        focusBeforeCapture = on;
    }

    public void shutdown() {
        stop();
        camThread.quitSafely();
        imgThread.quitSafely();
        anaThread.quitSafely();
        io.shutdown();
    }

    // ===================================================================================
    // Open / close / reconfigure
    // ===================================================================================

    /** Opens the camera right away (in parallel with view inflation); the session waits for the texture. */
    public void start(final String id, final SessionSpec s, final CaptureSettings cs) {
        final SessionSpec sp = s.copy();
        final CaptureSettings set = cs.copy();
        camH.post(new Runnable() {
            @Override
            public void run() {
                closeInternal();
                cameraId = id;
                spec = sp;
                settings = set;
                tHasPoint = tAfLock = tAeLock = false;
                dropAnalysis = false;
                dropRaw = false;
                try {
                    caps = CameraCaps.load(mgr, id);
                } catch (CameraAccessException | RuntimeException e) {
                    postError("อ่านข้อมูลกล้องไม่ได้: " + e.getMessage(), true);
                    return;
                }
                openDevice();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void openDevice() {
        openRequestNs = SystemClock.elapsedRealtimeNanos();
        final int gen = ++generation;
        try {
            mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (gen != generation) {
                        d.close();
                        return;
                    }
                    device = d;
                    PerfLog.add(PerfLog.CAMERA_OPEN, PerfLog.msSince(openRequestNs));
                    final CameraCaps c = caps;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l != null) l.onOpened(c);
                        }
                    });
                    maybeCreateSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (device == d) {
                        device = null;
                        session = null;
                        postError("กล้องถูกแอปอื่นหรือระบบดึงไปใช้ แตะเพื่อลองใหม่", true);
                    }
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    if (device == d || device == null) {
                        device = null;
                        session = null;
                        postError(errorText(error), error != ERROR_CAMERA_DISABLED);
                    }
                }
            }, camH);
        } catch (CameraAccessException e) {
            postError(errorText(e.getReason()), true);
        } catch (SecurityException e) {
            postError("ยังไม่ได้รับสิทธิ์ใช้กล้อง", true);
        } catch (RuntimeException e) {
            postError("เปิดกล้องไม่ได้: " + e.getMessage(), true);
        }
    }

    static String errorText(int code) {
        switch (code) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "กล้องถูกแอปอื่นใช้อยู่ ปิดแอปนั้นแล้วแตะเพื่อลองใหม่";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "มีการเปิดกล้องพร้อมกันมากเกินไป";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "กล้องถูกปิดโดยนโยบายของเครื่อง";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "กล้องขัดข้อง แตะเพื่อเปิดใหม่";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "บริการกล้องของระบบขัดข้อง ลองปิดแล้วเปิดแอปใหม่";
            default: return "กล้องขัดข้อง (รหัส " + code + ")";
        }
    }

    public void setPreviewTexture(final SurfaceTexture t) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                texture = t;
                if (t == null) {
                    closeInternal();
                } else {
                    maybeCreateSession();
                }
            }
        });
    }

    /** Re-create streams (mode / aspect / RAW / analysis / video format). Shows a transient state in the UI. */
    public void reconfigure(final SessionSpec s, final CaptureSettings cs) {
        final SessionSpec sp = s.copy();
        final CaptureSettings set = cs.copy();
        camH.post(new Runnable() {
            @Override
            public void run() {
                if (recording) stopRecordingInternal();
                spec = sp;
                settings = set;
                tHasPoint = tAfLock = tAeLock = false;
                dropAnalysis = false;
                dropRaw = false;
                closeSession();
                maybeCreateSession();
            }
        });
    }

    /** Blocking close (≤ 2.5 s) so other apps can open the camera as soon as we pause. */
    public void stop() {
        final CountDownLatch latch = new CountDownLatch(1);
        camH.post(new Runnable() {
            @Override
            public void run() {
                generation++;
                closeInternal();
                latch.countDown();
            }
        });
        try {
            latch.await(2500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSession() {
        sessionGen++; // invalidates any session still being configured
        captureState = CS_IDLE;
        pendingShot = null;
        if (resumeContinuousAf != null) {
            camH.removeCallbacks(resumeContinuousAf);
            resumeContinuousAf = null;
        }
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            session = null;
        }
        waitForRawWrites();
        releaseStreams();
        configuring = false;
    }

    private void closeInternal() {
        if (recording) stopRecordingInternal();
        closeSession();
        if (device != null) {
            try {
                device.close();
            } catch (RuntimeException ignored) {
            }
            device = null;
        }
    }

    private void waitForRawWrites() {
        long deadline = SystemClock.uptimeMillis() + 2000;
        while (SystemClock.uptimeMillis() < deadline) {
            synchronized (shotLock) {
                if (pendingRawWrites == 0) return;
            }
            SystemClock.sleep(20);
        }
    }

    private void releaseStreams() {
        if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
        if (rawReader != null) { rawReader.close(); rawReader = null; }
        if (analysisReader != null) { analysisReader.close(); analysisReader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (recorderSurface != null) { recorderSurface.release(); recorderSurface = null; }
        synchronized (shotLock) {
            for (Image i : orphanRaw.values()) i.close();
            orphanRaw.clear();
            orphanJpeg.clear();
        }
    }

    private void maybeCreateSession() {
        if (device == null || texture == null || session != null || configuring) return;
        createSession();
    }

    private void createSession() {
        configuring = true;
        releaseStreams();
        final SessionSpec s = spec;
        final CameraCaps c = caps;
        List<OutputConfiguration> outs = new ArrayList<>();
        StringBuilder note = new StringBuilder();
        try {
            texture.setDefaultBufferSize(s.preview.getWidth(), s.preview.getHeight());
            previewSurface = new Surface(texture);
            outs.add(new OutputConfiguration(previewSurface));
            if (s.mode == MODE_PHOTO) {
                Size j = s.jpeg != null ? s.jpeg : c.largestJpeg();
                jpegReader = ImageReader.newInstance(j.getWidth(), j.getHeight(), ImageFormat.JPEG, 6);
                jpegReader.setOnImageAvailableListener(jpegListener, imgH);
                outs.add(new OutputConfiguration(jpegReader.getSurface()));
                Size r = c.rawSize();
                if (s.raw && r != null && !dropRaw) {
                    rawReader = ImageReader.newInstance(r.getWidth(), r.getHeight(), ImageFormat.RAW_SENSOR, 3);
                    rawReader.setOnImageAvailableListener(rawListener, imgH);
                    outs.add(new OutputConfiguration(rawReader.getSurface()));
                } else if (s.raw) {
                    note.append(dropRaw ? "RAW ใช้ร่วมกับชุดสตรีมนี้ไม่ได้ จึงปิด RAW ชั่วคราว. " : "กล้องนี้ไม่ให้ RAW กับแอปภายนอก. ");
                }
            } else {
                recorderSurface = VideoRecorder.createPersistentSurface(ctx, s);
                outs.add(new OutputConfiguration(recorderSurface));
            }
            if (s.analysis != null && !dropAnalysis) {
                analysisReader = ImageReader.newInstance(s.analysis.getWidth(), s.analysis.getHeight(), ImageFormat.YUV_420_888, 3);
                analysisReader.setOnImageAvailableListener(analysisListener, anaH);
                outs.add(new OutputConfiguration(analysisReader.getSurface()));
            } else if (s.analysis != null) {
                note.append("ฮิสโตแกรม/ซีบร้า/พีคกิงปิดชั่วคราว (ชุดสตรีมนี้รับเพิ่มไม่ได้). ");
            }
        } catch (IOException | RuntimeException e) {
            configuring = false;
            releaseStreams();
            postError("เตรียมสตรีมกล้องไม่ได้: " + e.getMessage(), true);
            return;
        }
        final String noteText = note.toString().trim();
        final long cfgStart = SystemClock.elapsedRealtimeNanos();
        final int gen = generation;
        final int sg = ++sessionGen;
        SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, camExec,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession cs) {
                        if (sg != sessionGen || gen != generation || device == null) {
                            cs.close();
                            return;
                        }
                        configuring = false;
                        session = cs;
                        PerfLog.add(PerfLog.SESSION_READY, PerfLog.msSince(cfgStart));
                        startRepeating();
                        final SessionSpec ready = s.copy();
                        if (rawReader == null) ready.raw = false;
                        if (analysisReader == null) ready.analysis = null;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                Listener l = listener;
                                if (l != null) l.onSessionReady(ready, noteText);
                            }
                        });
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession cs) {
                        if (sg != sessionGen || gen != generation) return;
                        configuring = false;
                        if (analysisReader != null && !dropAnalysis) {
                            dropAnalysis = true;
                            createSession();
                        } else if (rawReader != null && !dropRaw) {
                            dropRaw = true;
                            createSession();
                        } else {
                            postError("ตั้งค่าสตรีมกล้องชุดนี้ไม่ได้ (ลองเปลี่ยนความละเอียด/fps)", true);
                        }
                    }
                });
        if (s.mode == MODE_VIDEO) {
            try {
                CaptureRequest.Builder p = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                Range<Integer> fr = c.fixedFps(s.fps);
                if (fr != null) p.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fr);
                sc.setSessionParameters(p.build());
            } catch (CameraAccessException | RuntimeException ignored) {
            }
        }
        try {
            device.createCaptureSession(sc);
        } catch (CameraAccessException | RuntimeException e) {
            configuring = false;
            postError("สร้างเซสชันกล้องไม่ได้: " + e.getMessage(), true);
        }
    }

    // ===================================================================================
    // Requests
    // ===================================================================================

    private CaptureRequest.Builder newRepeatingBuilder() throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(
                spec.mode == MODE_VIDEO ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewSurface);
        if (analysisReader != null) b.addTarget(analysisReader.getSurface());
        if (recording && recorderSurface != null) b.addTarget(recorderSurface);
        apply(b, false);
        return b;
    }

    private void startRepeating() {
        if (session == null || device == null) return;
        try {
            session.setRepeatingRequest(newRepeatingBuilder().build(), previewCallback, camH);
        } catch (CameraAccessException | RuntimeException e) {
            postError("ส่งคำสั่งพรีวิวไม่ได้: " + e.getMessage(), true);
        }
    }

    /** Push new user settings without touching streams. */
    public void updateSettings(final CaptureSettings cs) {
        final CaptureSettings set = cs.copy();
        camH.post(new Runnable() {
            @Override
            public void run() {
                boolean measurable = set.manualExposure && (set.iso != settings.iso || set.exposureNs != settings.exposureNs
                        || !settings.manualExposure);
                settings = set;
                if (measurable) {
                    settingsChangeNs = SystemClock.elapsedRealtimeNanos();
                    settingsExpect = set;
                }
                startRepeating();
            }
        });
    }

    private static int clampI(int v, Range<Integer> r) {
        return r == null ? v : Math.max(r.getLower(), Math.min(r.getUpper(), v));
    }

    private static long clampL(long v, Range<Long> r) {
        return r == null ? v : Math.max(r.getLower(), Math.min(r.getUpper(), v));
    }

    private void apply(CaptureRequest.Builder b, boolean still) {
        CameraCaps c = caps;
        CaptureSettings s = settings;
        boolean video = spec.mode == MODE_VIDEO;
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        boolean manual = s.manualExposure && c.manualSensor && c.isoRange != null && c.expRange != null;
        boolean torch = c.flash && s.flash == CaptureSettings.FLASH_TORCH;
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            long exp = clampL(s.exposureNs, c.expRange);
            long frame = 0;
            if (video) {
                frame = (long) (1e9 / spec.fps);
                exp = Math.min(exp, frame);
            } else {
                frame = exp;
                if (c.maxFrameNs > 0) frame = Math.min(frame, c.maxFrameNs);
            }
            b.set(CaptureRequest.SENSOR_SENSITIVITY, clampI(s.iso, c.isoRange));
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame);
            int flashMode = CameraMetadata.FLASH_MODE_OFF;
            if (torch) flashMode = CameraMetadata.FLASH_MODE_TORCH;
            else if (still && c.flash && s.flash == CaptureSettings.FLASH_ON) flashMode = CameraMetadata.FLASH_MODE_SINGLE;
            b.set(CaptureRequest.FLASH_MODE, flashMode);
        } else {
            int ae = CameraMetadata.CONTROL_AE_MODE_ON;
            if (c.flash && !video) {
                if (s.flash == CaptureSettings.FLASH_AUTO && CameraCaps.has(c.aeModes, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
                    ae = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
                } else if (s.flash == CaptureSettings.FLASH_ON && CameraCaps.has(c.aeModes, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)) {
                    ae = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
                }
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, ae);
            b.set(CaptureRequest.FLASH_MODE, torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
            if (c.evRange != null) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampI(s.evSteps, c.evRange));
            if (c.aeLockAvailable) b.set(CaptureRequest.CONTROL_AE_LOCK, s.aeLock || tAeLock);
        }
        Range<Integer> fps = video ? c.fixedFps(spec.fps) : c.photoFpsRange();
        if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);

        if (s.focusMode == CaptureSettings.FOCUS_MANUAL && c.manualFocus()) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0f, Math.min(c.minFocusDiopters, s.focusDiopters)));
        } else if ((s.focusMode == CaptureSettings.FOCUS_SINGLE || tHasPoint || tAfLock)
                && CameraCaps.has(c.afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        } else {
            int cont = video ? CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            if (CameraCaps.has(c.afModes, cont)) b.set(CaptureRequest.CONTROL_AF_MODE, cont);
            else if (CameraCaps.has(c.afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        }
        applyWb(b, s, c);
        MeteringRectangle[] regions = regions(s, c);
        if (regions != null) {
            if (c.maxAe > 0 && !manual) b.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
            if (c.maxAf > 0 && s.focusMode != CaptureSettings.FOCUS_MANUAL) b.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
        }
        applyZoom(b, s.zoom, c);
        applyLook(b, s.look, c);
        if (CameraCaps.has(c.oisModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                && CameraCaps.has(c.oisModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, s.ois
                    ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON : CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if (video) {
            int mode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
            if (s.videoStab == 2 && Build.VERSION.SDK_INT >= 33
                    && CameraCaps.has(c.stabModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
                mode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
            } else if (s.videoStab >= 1 && CameraCaps.has(c.stabModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                mode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            }
            if (c.stabModes.length > 0) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode);
        }
        if (c.apertures.length > 1 && s.aperture > 0) b.set(CaptureRequest.LENS_APERTURE, s.aperture);
    }

    private void applyWb(CaptureRequest.Builder b, CaptureSettings s, CameraCaps c) {
        if (s.wbMode == CaptureSettings.WB_PRESET && CameraCaps.has(c.awbModes, s.awbPreset)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbPreset);
            return;
        }
        if (s.wbMode == CaptureSettings.WB_KELVIN) {
            WhiteBalanceMath.Result r = kelvin(s, c);
            if (r != null && r.valid && c.kelvinCapable()) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF);
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector((float) r.gains[0], (float) r.gains[1],
                        (float) r.gains[2], (float) r.gains[3]));
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform(r.ccm));
                return;
            }
            int preset = closestPreset(s.kelvin, c);
            if (preset >= 0) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, preset);
                return;
            }
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        if (c.awbLockAvailable) b.set(CaptureRequest.CONTROL_AWB_LOCK, s.awbLock);
    }

    public synchronized WhiteBalanceMath.Result kelvin(CaptureSettings s, CameraCaps c) {
        if (kelvinResult != null && kelvinFor == s.kelvin && tintFor == s.tint) return kelvinResult;
        kelvinResult = WhiteBalanceMath.compute(s.kelvin, s.tint, c.cm1, c.cm2, c.cc1, c.cc2, c.fm1, c.fm2,
                WhiteBalanceMath.illuminantKelvin(c.illum1), WhiteBalanceMath.illuminantKelvin(c.illum2));
        kelvinFor = s.kelvin;
        tintFor = s.tint;
        return kelvinResult;
    }

    static int closestPreset(int kelvin, CameraCaps c) {
        int[][] presets = {
                {CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, 2700}, {CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT, 3000},
                {CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, 4200}, {CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, 5500},
                {CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, 6500}, {CameraMetadata.CONTROL_AWB_MODE_SHADE, 7500}};
        int best = -1;
        int bd = Integer.MAX_VALUE;
        for (int[] p : presets) {
            if (!CameraCaps.has(c.awbModes, p[0])) continue;
            int d = Math.abs(p[1] - kelvin);
            if (d < bd) { bd = d; best = p[0]; }
        }
        return best;
    }

    static ColorSpaceTransform transform(double[] m) {
        int[] e = new int[18];
        for (int i = 0; i < 9; i++) {
            e[2 * i] = (int) Math.round(m[i] * 10000);
            e[2 * i + 1] = 10000;
        }
        return new ColorSpaceTransform(e);
    }

    private void applyZoom(CaptureRequest.Builder b, float zoom, CameraCaps c) {
        if (c.zoomRange != null && Build.VERSION.SDK_INT >= 30) {
            float z = Math.max(c.zoomRange.getLower(), Math.min(c.zoomRange.getUpper(), zoom));
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
            return;
        }
        Rect crop = cropRegion(zoom, c);
        if (crop != null) b.set(CaptureRequest.SCALER_CROP_REGION, crop);
    }

    private static Rect cropRegion(float zoom, CameraCaps c) {
        float z = Math.max(1f, Math.min(c.maxDigitalZoom, zoom));
        if (z <= 1.001f) return null;
        int w = c.active.width(), h = c.active.height();
        int cw = Math.round(w / z), ch = Math.round(h / z);
        return new Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
    }

    private static void applyLook(CaptureRequest.Builder b, int look, CameraCaps c) {
        if (look == CaptureSettings.LOOK_NATURAL) {
            if (CameraCaps.has(c.edgeModes, CameraMetadata.EDGE_MODE_OFF)) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF);
            if (CameraCaps.has(c.nrModes, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL);
            } else if (CameraCaps.has(c.nrModes, CameraMetadata.NOISE_REDUCTION_MODE_FAST)) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST);
            }
        } else if (look == CaptureSettings.LOOK_MONO && CameraCaps.has(c.effects, CameraMetadata.CONTROL_EFFECT_MODE_MONO)) {
            b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO);
        }
    }

    /** Normalised portrait-display point -> normalised sensor point (handles sensor rotation and selfie mirror). */
    public static float[] displayToSensor(float nx, float ny, int sensorOrientation, boolean front) {
        if (front) nx = 1f - nx;
        switch (sensorOrientation) {
            case 90: return new float[]{ny, 1f - nx};
            case 180: return new float[]{1f - nx, 1f - ny};
            case 270: return new float[]{1f - ny, nx};
            default: return new float[]{nx, ny};
        }
    }

    private MeteringRectangle[] regions(CaptureSettings s, CameraCaps c) {
        if (!tHasPoint && s.metering == CaptureSettings.METER_MATRIX) return null;
        float px = tHasPoint ? tX : 0.5f, py = tHasPoint ? tY : 0.5f;
        float frac;
        if (s.metering == CaptureSettings.METER_SPOT) frac = 0.07f;
        else if (s.metering == CaptureSettings.METER_CENTER && !tHasPoint) frac = 0.6f;
        else frac = 0.2f;
        float[] n = displayToSensor(px, py, c.sensorOrientation, c.isFront());
        Rect base;
        if (c.zoomRange != null && Build.VERSION.SDK_INT >= 30) {
            base = new Rect(0, 0, c.active.width(), c.active.height()); // post-zoom coordinate system
        } else {
            Rect crop = cropRegion(settings.zoom, c);
            base = crop != null ? crop : new Rect(0, 0, c.active.width(), c.active.height());
        }
        int side = Math.max(16, Math.round(Math.min(base.width(), base.height()) * frac));
        int cx = base.left + Math.round(n[0] * base.width());
        int cy = base.top + Math.round(n[1] * base.height());
        int l = Math.max(base.left, cx - side / 2), t = Math.max(base.top, cy - side / 2);
        int r = Math.min(base.right - 1, l + side), btm = Math.min(base.bottom - 1, t + side);
        if (r - l < 8 || btm - t < 8) return null;
        return new MeteringRectangle[]{new MeteringRectangle(l, t, r - l, btm - t, MeteringRectangle.METERING_WEIGHT_MAX - 1)};
    }

    // ===================================================================================
    // Focus / metering at a point
    // ===================================================================================

    /** Tap = focus+meter at point (returns to continuous AF after 4 s); lock = AE/AF lock (long press). */
    public void focusAt(final float nx, final float ny, final boolean lock) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                if (session == null) return;
                tHasPoint = true;
                tX = nx;
                tY = ny;
                tAfLock = lock;
                tAeLock = false; // let AE re-meter at the new point first; lock follows below for long-press
                if (resumeContinuousAf != null) camH.removeCallbacks(resumeContinuousAf);
                try {
                    CaptureRequest.Builder cancel = newRepeatingBuilder();
                    if (settings.focusMode != CaptureSettings.FOCUS_MANUAL) {
                        cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                        session.capture(cancel.build(), previewCallback, camH);
                        CaptureRequest.Builder start = newRepeatingBuilder();
                        start.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                        awaitingAf = true;
                        session.capture(start.build(), previewCallback, camH);
                    }
                    session.setRepeatingRequest(newRepeatingBuilder().build(), previewCallback, camH);
                } catch (CameraAccessException | RuntimeException e) {
                    return;
                }
                if (lock) {
                    camH.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!tAfLock) return;
                            if (caps.aeLockAvailable && !settings.manualExposure) tAeLock = true;
                            startRepeating();
                        }
                    }, 600);
                } else if (settings.focusMode == CaptureSettings.FOCUS_CONTINUOUS) {
                    resumeContinuousAf = new Runnable() {
                        @Override
                        public void run() {
                            resumeContinuousAf = null;
                            if (tAfLock) return;
                            tHasPoint = false;
                            startRepeating();
                        }
                    };
                    camH.postDelayed(resumeContinuousAf, 4000);
                }
            }
        });
    }

    public void clearLocks() {
        camH.post(new Runnable() {
            @Override
            public void run() {
                tHasPoint = false;
                tAfLock = false;
                tAeLock = false;
                if (resumeContinuousAf != null) {
                    camH.removeCallbacks(resumeContinuousAf);
                    resumeContinuousAf = null;
                }
                startRepeating();
            }
        });
    }

    public void setZoom(final float z) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                settings.zoom = z;
                startRepeating();
            }
        });
    }

    // ===================================================================================
    // Still capture
    // ===================================================================================

    private int jpegOrientation() {
        CameraCaps c = caps;
        int sign = c.isFront() ? -1 : 1;
        return (c.sensorOrientation + sign * deviceOrientation + 360) % 360;
    }

    public void takePhoto(final ShotRequest req) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                if (session == null || device == null || jpegReader == null || spec.mode != MODE_PHOTO) {
                    failNow(req, "กล้องยังไม่พร้อม");
                    return;
                }
                if (captureState != CS_IDLE) {
                    failNow(req, "กำลังถ่ายภาพก่อนหน้า");
                    return;
                }
                CaptureSettings s = settings;
                CameraCaps c = caps;
                boolean needsPrecapture = c.flash && !s.manualExposure
                        && (s.flash == CaptureSettings.FLASH_AUTO || s.flash == CaptureSettings.FLASH_ON);
                Readout ro = lastReadout;
                boolean scanning = ro != null && (ro.afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN
                        || ro.afState == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN);
                try {
                    if (needsPrecapture) {
                        pendingShot = req;
                        captureState = CS_WAIT_PRECAPTURE;
                        captureDeadlineMs = SystemClock.uptimeMillis() + 1500;
                        CaptureRequest.Builder b = newRepeatingBuilder();
                        b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                        session.capture(b.build(), previewCallback, camH);
                    } else if (focusBeforeCapture && scanning) {
                        pendingShot = req;
                        captureState = CS_WAIT_AF;
                        captureDeadlineMs = SystemClock.uptimeMillis() + 900;
                    } else {
                        captureStill(req);
                    }
                } catch (CameraAccessException | RuntimeException e) {
                    captureState = CS_IDLE;
                    failNow(req, "ถ่ายไม่สำเร็จ: " + e.getMessage());
                }
            }
        });
    }

    private void failNow(ShotRequest req, final String why) {
        final ShotInfo s = new ShotInfo(0);
        s.source = req.source;
        s.error = why;
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onShotFailed(s, why);
            }
        });
    }

    private void captureStill(ShotRequest req) throws CameraAccessException {
        captureState = CS_IDLE;
        pendingShot = null;
        CameraCaps c = caps;
        CaptureSettings s = settings;
        boolean wantRaw = rawReader != null;
        int n = req.bracketEv != null ? req.bracketEv.length : Math.max(1, req.count);
        if (wantRaw) n = Math.min(n, 3);
        int orientation = jpegOrientation();
        String base = MediaStoreUtil.timestampName("NM_");
        Readout ro = lastReadout;
        List<CaptureRequest> list = new ArrayList<>(n);
        List<ShotInfo> infos = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jpegReader.getSurface());
            if (wantRaw) b.addTarget(rawReader.getSurface());
            apply(b, true);
            b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
            b.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) Math.max(50, Math.min(100, s.jpegQuality)));
            if (c.zslKey) {
                boolean zsl = s.zsl && req.bracketEv == null && n == 1 && !s.manualExposure
                        && s.flash != CaptureSettings.FLASH_ON && s.flash != CaptureSettings.FLASH_AUTO;
                b.set(CaptureRequest.CONTROL_ENABLE_ZSL, zsl);
            }
            ShotInfo info;
            synchronized (shotLock) {
                info = new ShotInfo(nextShotId++);
            }
            info.source = req.source;
            info.index = i;
            info.count = n;
            info.tapElapsedNs = req.tapElapsedNs;
            info.jpegOrientation = orientation;
            info.cropAspect = req.cropAspect;
            info.mirror = req.mirror && c.isFront();
            info.relPath = req.relPath;
            info.wantRaw = wantRaw;
            info.baseName = n > 1 ? String.format(java.util.Locale.ROOT, "%s_%02d", base, i + 1) : base;
            if (req.bracketEv != null && c.manualSensor && c.isoRange != null && c.expRange != null) {
                int iso = s.manualExposure ? s.iso : (ro != null && ro.iso > 0 ? ro.iso : 100);
                long exp = s.manualExposure ? s.exposureNs : (ro != null && ro.exposureNs > 0 ? ro.exposureNs : 10_000_000L);
                double want = exp * Math.pow(2, req.bracketEv[i]);
                long e2 = clampL(Math.round(want), c.expRange);
                int iso2 = clampI((int) Math.round(iso * want / e2), c.isoRange);
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e2);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso2);
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, e2);
                info.requestedIso = iso2;
                info.requestedExposureNs = e2;
            } else if (s.manualExposure && c.manualSensor) {
                info.requestedIso = clampI(s.iso, c.isoRange);
                info.requestedExposureNs = clampL(s.exposureNs, c.expRange);
            }
            b.setTag(info.id);
            synchronized (shotLock) {
                shotsById.put(info.id, info);
            }
            infos.add(info);
            list.add(b.build());
        }
        long t = SystemClock.elapsedRealtimeNanos();
        for (ShotInfo info : infos) info.requestElapsedNs = t;
        if (req.tapElapsedNs > 0) PerfLog.add(PerfLog.TAP_TO_REQUEST, (t - req.tapElapsedNs) / 1e6);
        if (req.voiceEndElapsedNs > 0) PerfLog.add(PerfLog.VOICE_END_TO_REQUEST, (t - req.voiceEndElapsedNs) / 1e6);
        try {
            if (list.size() == 1) session.capture(list.get(0), stillCallback, camH);
            else session.captureBurst(list, stillCallback, camH);
        } catch (CameraAccessException | RuntimeException e) {
            for (ShotInfo info : infos) finishFailed(info, "ส่งคำสั่งถ่ายไม่ได้: " + e.getMessage());
        }
    }

    private ShotInfo shotForRequest(CaptureRequest request) {
        Object tag = request.getTag();
        if (!(tag instanceof Integer)) return null;
        synchronized (shotLock) {
            return shotsById.get(tag);
        }
    }

    private final CameraCaptureSession.CaptureCallback stillCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession s, CaptureRequest request, long timestamp, long frameNumber) {
            final ShotInfo info = shotForRequest(request);
            if (info == null) return;
            info.startedElapsedNs = SystemClock.elapsedRealtimeNanos();
            info.sensorTs = timestamp;
            byte[] jpeg;
            Image raw;
            synchronized (shotLock) {
                shotsByTs.put(timestamp, info);
                jpeg = orphanJpeg.remove(timestamp);
                raw = orphanRaw.remove(timestamp);
            }
            if (info.index == 0) {
                if (info.tapElapsedNs > 0) {
                    boolean realtime = caps.timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
                    long at = realtime ? timestamp : info.startedElapsedNs;
                    PerfLog.add(PerfLog.TAP_TO_SENSOR, (at - info.tapElapsedNs) / 1e6);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Listener l = listener;
                        if (l != null) l.onShutter(info);
                    }
                });
            }
            if (jpeg != null) onJpeg(info, jpeg);
            if (raw != null) onRaw(info, raw);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            ShotInfo info = shotForRequest(request);
            if (info == null) return;
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            info.iso = iso == null ? 0 : iso;
            info.exposureNs = exp == null ? 0 : exp;
            info.focusDiopters = fd == null ? Float.NaN : fd;
            if (Build.VERSION.SDK_INT >= 29) info.physicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
            synchronized (shotLock) {
                info.result = result;
            }
            maybeWriteRaw(info);
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
            ShotInfo info = shotForRequest(request);
            if (info == null) return;
            finishFailed(info, failure.getReason() == CaptureFailure.REASON_FLUSHED
                    ? "การถ่ายถูกยกเลิก (กล้องถูกปิดระหว่างถ่าย)" : "เซนเซอร์ถ่ายเฟรมนี้ไม่สำเร็จ");
        }
    };

    private final ImageReader.OnImageAvailableListener jpegListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img;
            try {
                img = reader.acquireNextImage();
            } catch (RuntimeException e) {
                return;
            }
            if (img == null) return;
            long ts = img.getTimestamp();
            byte[] bytes;
            try {
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                bytes = new byte[buf.remaining()];
                buf.get(bytes);
            } finally {
                img.close();
            }
            ShotInfo info;
            synchronized (shotLock) {
                info = shotsByTs.get(ts);
                if (info == null) {
                    orphanJpeg.put(ts, bytes);
                    return;
                }
            }
            onJpeg(info, bytes);
        }
    };

    private final ImageReader.OnImageAvailableListener rawListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img;
            try {
                img = reader.acquireNextImage();
            } catch (RuntimeException e) {
                return;
            }
            if (img == null) return;
            ShotInfo info;
            synchronized (shotLock) {
                info = shotsByTs.get(img.getTimestamp());
                if (info == null) {
                    orphanRaw.put(img.getTimestamp(), img);
                    return;
                }
            }
            onRaw(info, img);
        }
    };

    private void onJpeg(final ShotInfo info, final byte[] bytes) {
        info.jpegElapsedNs = SystemClock.elapsedRealtimeNanos();
        if (info.index == 0 && info.tapElapsedNs > 0) PerfLog.add(PerfLog.TAP_TO_JPEG, (info.jpegElapsedNs - info.tapElapsedNs) / 1e6);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (MediaStoreUtil.freeBytes() >= 0 && MediaStoreUtil.freeBytes() < MediaStoreUtil.MIN_FREE_BYTES) {
                        throw new IOException("พื้นที่เครื่องเหลือน้อยเกินไป");
                    }
                    info.fileName = info.baseName + ".jpg";
                    info.jpegUri = PhotoSaver.saveJpeg(ctx, bytes, info.fileName, info.relPath, info.cropAspect, info.mirror);
                    info.savedElapsedNs = SystemClock.elapsedRealtimeNanos();
                    if (info.index == 0 && info.tapElapsedNs > 0) {
                        PerfLog.add(PerfLog.TAP_TO_SAVED, (info.savedElapsedNs - info.tapElapsedNs) / 1e6);
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l != null) l.onSaved(info);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    finishFailed(info, "บันทึกรูปไม่สำเร็จ: " + e.getMessage());
                    return;
                }
                synchronized (shotLock) {
                    info.jpegDone = true;
                    cleanupShot(info);
                }
            }
        });
    }

    private void onRaw(ShotInfo info, Image img) {
        synchronized (shotLock) {
            info.rawImage = img;
        }
        maybeWriteRaw(info);
    }

    private void maybeWriteRaw(final ShotInfo info) {
        synchronized (shotLock) {
            if (!info.wantRaw || info.rawWriting || info.rawImage == null || info.result == null) return;
            info.rawWriting = true;
            pendingRawWrites++;
        }
        final CameraCharacteristics chars = caps.chars;
        io.execute(new Runnable() {
            @Override
            public void run() {
                Uri uri = null;
                try (DngCreator dng = new DngCreator(chars, info.result)) {
                    dng.setOrientation(exifOrientation(info.jpegOrientation));
                    uri = MediaStoreUtil.insertPending(ctx, info.baseName + ".dng", "image/x-adobe-dng", info.relPath);
                    try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "w")) {
                        if (os == null) throw new IOException("cannot open DNG output");
                        dng.writeImage(os, info.rawImage);
                    }
                    MediaStoreUtil.publish(ctx, uri);
                    info.rawUri = uri;
                } catch (IOException | RuntimeException e) {
                    MediaStoreUtil.delete(ctx, uri);
                    final String msg = "บันทึก RAW ไม่สำเร็จ: " + e.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l != null) l.onShotFailed(info, msg);
                        }
                    });
                } finally {
                    synchronized (shotLock) {
                        if (info.rawImage != null) {
                            info.rawImage.close();
                            info.rawImage = null;
                        }
                        info.rawDone = true;
                        pendingRawWrites--;
                        cleanupShot(info);
                    }
                }
            }
        });
    }

    static int exifOrientation(int jpegDegrees) {
        switch (jpegDegrees) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private void cleanupShot(ShotInfo info) {
        if (info.jpegDone && (!info.wantRaw || info.rawDone)) {
            shotsById.remove(info.id);
            shotsByTs.remove(info.sensorTs);
        }
    }

    private void finishFailed(final ShotInfo info, final String why) {
        info.error = why;
        synchronized (shotLock) {
            shotsById.remove(info.id);
            shotsByTs.remove(info.sensorTs);
            if (info.rawImage != null && !info.rawWriting) {
                info.rawImage.close();
                info.rawImage = null;
            }
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onShotFailed(info, why);
            }
        });
    }

    // ===================================================================================
    // Repeating-request results: readout, precapture/AF state machine, settings latency
    // ===================================================================================

    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            handleResult(result);
        }
    };

    private void handleResult(TotalCaptureResult result) {
        Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
        if (awaitingAf && af != null && (af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED
                || af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
            awaitingAf = false;
            final boolean ok = af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED;
            main.post(new Runnable() {
                @Override
                public void run() {
                    Listener l = listener;
                    if (l != null) l.onFocusLocked(ok);
                }
            });
        }
        try {
            switch (captureState) {
                case CS_WAIT_PRECAPTURE:
                    if (ae == null || ae == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE
                            || ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        captureState = CS_WAIT_NON_PRECAPTURE;
                    } else if (SystemClock.uptimeMillis() > captureDeadlineMs) {
                        captureStill(pendingShot);
                    }
                    break;
                case CS_WAIT_NON_PRECAPTURE:
                    if (ae == null || ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE
                            || SystemClock.uptimeMillis() > captureDeadlineMs) {
                        captureStill(pendingShot);
                    }
                    break;
                case CS_WAIT_AF:
                    if (af == null || (af != CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN
                            && af != CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN)
                            || SystemClock.uptimeMillis() > captureDeadlineMs) {
                        captureStill(pendingShot);
                    }
                    break;
                default:
                    break;
            }
        } catch (CameraAccessException | RuntimeException e) {
            captureState = CS_IDLE;
            pendingShot = null;
        }
        Readout r = new Readout();
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long fd = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        Float ap = result.get(CaptureResult.LENS_APERTURE);
        Float fl = result.get(CaptureResult.LENS_FOCAL_LENGTH);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
        Integer ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        RggbChannelVector g = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        r.iso = iso == null ? 0 : iso;
        r.exposureNs = exp == null ? 0 : exp;
        r.frameDurationNs = fd == null ? 0 : fd;
        r.focusDiopters = focus == null ? Float.NaN : focus;
        r.aperture = ap == null ? Float.NaN : ap;
        r.focal = fl == null ? Float.NaN : fl;
        r.afState = af == null ? -1 : af;
        r.aeState = ae == null ? -1 : ae;
        r.awbState = awb == null ? -1 : awb;
        r.oisOn = ois != null && ois == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON;
        if (g != null) r.gains = new float[]{g.getRed(), (g.getGreenEven() + g.getGreenOdd()) / 2f, g.getBlue()};
        if (Build.VERSION.SDK_INT >= 29) r.physicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        if (Build.VERSION.SDK_INT >= 30) {
            Float z = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
            r.zoomRatio = z == null ? Float.NaN : z;
        }
        lastReadout = r;
        CaptureSettings expect = settingsExpect;
        if (expect != null) {
            boolean isoOk = Math.abs(r.iso - expect.iso) <= Math.max(2, expect.iso * 0.05);
            boolean expOk = Math.abs(r.exposureNs - expect.exposureNs) <= Math.max(50_000, expect.exposureNs * 0.05);
            boolean timeout = PerfLog.msSince(settingsChangeNs) > 3000;
            if ((isoOk && expOk) || timeout) {
                settingsExpect = null;
                if (!timeout) {
                    final double ms = PerfLog.msSince(settingsChangeNs);
                    PerfLog.add(PerfLog.SETTING_APPLY, ms);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l != null) l.onSettingApplied(ms);
                        }
                    });
                }
            }
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastReadoutPostMs >= 150) {
            lastReadoutPostMs = now;
            final Readout post = r;
            main.post(new Runnable() {
                @Override
                public void run() {
                    Listener l = listener;
                    if (l != null) l.onReadout(post);
                }
            });
        }
    }

    // ===================================================================================
    // Analysis stream
    // ===================================================================================

    private final ImageReader.OnImageAvailableListener analysisListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img;
            try {
                img = reader.acquireLatestImage();
            } catch (RuntimeException e) {
                return;
            }
            if (img == null) return;
            try {
                long now = SystemClock.uptimeMillis();
                if (!analysisWanted || now - lastAnalysisMs < analysisIntervalMs) return;
                lastAnalysisMs = now;
                final FrameAnalyzer.Result r = analyzer.process(img, analysisConfig);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Listener l = listener;
                        if (l != null) l.onAnalysis(r);
                    }
                });
            } catch (RuntimeException ignored) {
            } finally {
                img.close();
            }
        }
    };

    // ===================================================================================
    // Video
    // ===================================================================================

    public void startRecording(final boolean audio, final String relPath, final long tapElapsedNs) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                if (spec.mode != MODE_VIDEO || session == null || recorderSurface == null || recording) return;
                long free = MediaStoreUtil.freeBytes();
                if (free >= 0 && free < MediaStoreUtil.MIN_FREE_BYTES * 3) {
                    postRecordingStopped(null, "พื้นที่เครื่องเหลือน้อยเกินไปสำหรับวิดีโอ", 0);
                    return;
                }
                String name = MediaStoreUtil.timestampName("NM_VID_") + ".mp4";
                try {
                    recUri = MediaStoreUtil.insertPending(ctx, name, "video/mp4", relPath);
                    recPfd = ctx.getContentResolver().openFileDescriptor(recUri, "rw");
                    if (recPfd == null) throw new IOException("cannot open output");
                    long maxBytes = free > 0 ? Math.max(0, free - 300L * 1024 * 1024) : 0;
                    recorder = VideoRecorder.start(ctx, recorderSurface, spec, recPfd.getFileDescriptor(), audio,
                            jpegOrientation(), maxBytes);
                    recorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                        @Override
                        public void onInfo(MediaRecorder mr, int what, int extra) {
                            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                                camH.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        stopRecordingInternal();
                                    }
                                });
                            }
                        }
                    });
                    recording = true;
                    recPaused = false;
                    session.setRepeatingRequest(newRepeatingBuilder().build(), previewCallback, camH);
                    recorder.start();
                    recStartMs = SystemClock.uptimeMillis();
                    if (tapElapsedNs > 0) PerfLog.add(PerfLog.VIDEO_START, PerfLog.msSince(tapElapsedNs));
                    final Uri u = recUri;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Listener l = listener;
                            if (l != null) l.onRecordingStarted(u);
                        }
                    });
                } catch (IOException | CameraAccessException | RuntimeException e) {
                    recording = false;
                    if (recorder != null) { recorder.release(); recorder = null; }
                    closePfd();
                    MediaStoreUtil.delete(ctx, recUri);
                    recUri = null;
                    startRepeating();
                    postRecordingStopped(null, "เริ่มอัดวิดีโอไม่ได้: " + e.getMessage(), 0);
                }
            }
        });
    }

    public void stopRecording(final long tapElapsedNs) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                if (!recording) return;
                stopRecordingInternal();
                if (tapElapsedNs > 0) PerfLog.add(PerfLog.VIDEO_STOP, PerfLog.msSince(tapElapsedNs));
            }
        });
    }

    public void pauseRecording(final boolean pause) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                if (!recording || recorder == null) return;
                try {
                    if (pause && !recPaused) { recorder.pause(); recPaused = true; }
                    else if (!pause && recPaused) { recorder.resume(); recPaused = false; }
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    /** Polled by the UI for the audio meter (0..32767). */
    public void pollAmplitude(final AmplitudeCallback cb) {
        camH.post(new Runnable() {
            @Override
            public void run() {
                int a = 0;
                try {
                    if (recording && recorder != null) a = recorder.getMaxAmplitude();
                } catch (RuntimeException ignored) {
                }
                final int amp = a;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onAmplitude(amp);
                    }
                });
            }
        });
    }

    public interface AmplitudeCallback {
        void onAmplitude(int amplitude);
    }

    private void stopRecordingInternal() {
        recording = false;
        String error = null;
        long dur = SystemClock.uptimeMillis() - recStartMs;
        try {
            if (session != null) session.setRepeatingRequest(newRepeatingBuilder().build(), previewCallback, camH);
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                error = "วิดีโอสั้นเกินไปหรือไม่มีเฟรมเข้ามา จึงไม่ได้บันทึก";
            }
            try {
                recorder.reset();
            } catch (RuntimeException ignored) {
            }
            recorder.release();
            recorder = null;
        }
        closePfd();
        Uri u = recUri;
        recUri = null;
        if (u != null) {
            if (error == null) MediaStoreUtil.publish(ctx, u);
            else { MediaStoreUtil.delete(ctx, u); u = null; }
        }
        postRecordingStopped(u, error, dur);
    }

    private void closePfd() {
        if (recPfd != null) {
            try {
                recPfd.close();
            } catch (IOException ignored) {
            }
            recPfd = null;
        }
    }

    private void postRecordingStopped(final Uri u, final String error, final long dur) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onRecordingStopped(u, error, dur);
            }
        });
    }

    private void postError(final String msg, final boolean retry) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onError(msg, retry);
            }
        });
    }
}
