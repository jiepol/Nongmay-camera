package com.nongmay.camera.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings.Secure;
import android.util.Size;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nongmay.camera.App;
import com.nongmay.camera.camera.CameraCaps;
import com.nongmay.camera.camera.CameraController;
import com.nongmay.camera.camera.CaptureSettings;
import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.camera.overlays.FrameAnalyzer;
import com.nongmay.camera.camera.overlays.LevelSensor;
import com.nongmay.camera.camera.overlays.OverlayView;
import com.nongmay.camera.camera.pro.ExposureMath;
import com.nongmay.camera.perf.PerfLog;
import com.nongmay.camera.settings.Settings;
import com.nongmay.camera.settings.SettingsActivity;
import com.nongmay.camera.storymax.StoryActivity;
import com.nongmay.camera.util.Fmt;
import com.nongmay.camera.voice.AudioKwsEngine;
import com.nongmay.camera.voice.KwsCore;
import com.nongmay.camera.voice.ThaiText;
import com.nongmay.camera.voice.VoiceActivity;
import com.nongmay.camera.voice.VoiceProfileStore;
import com.nongmay.camera.voice.VoiceTemplate;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/** The camera screen: AUTO · PRO · VIDEO · STORY, with optional "น้องเม" voice shutter. */
public final class CameraActivity extends Activity implements CameraController.Listener, ProPanel.Listener {
    static final int MODE_AUTO = 0, MODE_PRO = 1, MODE_VIDEO = 2, MODE_STORY = 3;
    private static final String[] MODE_LABELS = {"AUTO", "PRO", "VIDEO", "STORY"};
    private static final int REQ_CAMERA = 11, REQ_MIC_VOICE = 12, REQ_MIC_VIDEO = 13;
    private static final int[] TIMERS = {0, 2, 5, 10};
    private static final String[] ASPECTS = {"4:3", "16:9", "1:1", "3:2"};
    private static final String[] DRIVE = {"Single", "Burst 5", "Burst 10", "AEB ±1 ×3", "AEB ±2 ×5", "Interval 3s×20", "Interval 10s×30"};

    private Settings prefs;
    private CameraController ctl;
    private CaptureSettings cs = new CaptureSettings();
    private CameraCaps caps;
    private String cameraId;
    private final List<String> backIds = new ArrayList<>();
    private final List<String> frontIds = new ArrayList<>();
    private int mode;
    private boolean storyVideo;
    private CameraController.SessionSpec activeSpec;
    private boolean sessionReady;
    private int drive;

    private FrameLayout root, previewBox;
    private TextureView texture;
    private OverlayView overlay;
    private LinearLayout topBar, bottom, zoomRow, modeRow, storyRow;
    private ImageView flashBtn, timerBtn, gridBtn, levelBtn, micBtn, settingsBtn, switchBtn, pauseBtn, thumb;
    private TextView aspectBtn, timerLabel, statusText, readoutText, recText, lensText;
    private View micMeter;
    private ProPanel pro;
    private ShutterButton shutter;
    private LinearLayout messagePanel;
    private TextView messageText, messageBtn1, messageBtn2;
    private final List<View> rotatables = new ArrayList<>();
    private int insetTop, insetBottom;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean awaitFirstFrame;
    private long warmStartNs;
    private long startCameraNs;
    private long switchStartNs;
    private String switchMetric;
    private Uri lastUri;
    // "ไม่ใช่คำนี้": the last voice trigger, so the owner can mark it false (and drop the photo it took)
    private KwsCore.Event lastVoiceTrigger;
    private Uri lastVoiceShotUri;
    private boolean dropNextVoiceShot;
    private boolean voiceUndoShowing;
    private boolean lastIsVideo;
    private Runnable countdownTask, intervalTask, recTicker, loupeReset, statusReset;
    private int intervalDone;
    private long recStartUptime;
    private boolean recPaused;
    private boolean pendingRecord;

    private AudioKwsEngine voice;
    private VoiceProfileStore store;
    private VoiceTemplate template;
    private boolean micSilenced;

    private OrientationEventListener orientation;
    private int deviceOrientation;
    private LevelSensor level;
    private PowerManager pm;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private int thermal;
    private MediaActionSound sound;
    private CameraManager.AvailabilityCallback availability;
    private boolean errorRetryable;
    private ScaleGestureDetector scaleDet;
    private GestureDetector tapDet;

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = App.get().settings();
        ctl = new CameraController(this);
        ctl.setListener(this);
        store = new VoiceProfileStore(this);
        voice = new AudioKwsEngine(this);
        level = new LevelSensor(this);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        mode = "auto".equals(prefs.getString(Settings.START_MODE, "last")) ? MODE_AUTO : prefs.getInt(Settings.LAST_MODE, MODE_AUTO);
        if (mode < 0 || mode > MODE_STORY) mode = MODE_AUTO;
        cs.zsl = prefs.zsl();
        cs.jpegQuality = prefs.jpegQuality();
        cs.flash = prefs.getInt(Settings.FLASH, CaptureSettings.FLASH_OFF);
        buildUi();
        enumerateCameras();
        String last = prefs.getString(Settings.LAST_CAMERA, null);
        cameraId = (last != null && (backIds.contains(last) || frontIds.contains(last))
                && !"auto".equals(prefs.getString(Settings.START_MODE, "last"))) ? last
                : (backIds.isEmpty() ? (frontIds.isEmpty() ? null : frontIds.get(0)) : backIds.get(0));
        setupOrientation();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        warmStartNs = SystemClock.elapsedRealtimeNanos();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (orientation.canDetectOrientation()) orientation.enable();
        if (prefs.getBool(Settings.LEVEL, false)) level.start(levelListener);
        overlay.setLevelEnabled(prefs.getBool(Settings.LEVEL, false));
        overlay.setGrid(prefs.getInt(Settings.GRID, 0));
        registerThermal();
        registerAvailability();
        template = loadTemplate();
        cs.zsl = prefs.zsl();
        cs.jpegQuality = prefs.jpegQuality();
        refreshTopBar();
        if (cameraId == null) {
            showMessage("ไม่พบกล้องที่แอปภายนอกใช้ได้บนเครื่องนี้", null, null);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        startCamera();
    }

    @Override
    protected void onPause() {
        resumed = false;
        cancelCountdown();
        stopInterval();
        stopVoice();
        orientation.disable();
        level.stop();
        unregisterThermal();
        unregisterAvailability();
        stopRecTicker();
        ctl.stop();
        sessionReady = false;
        savePreset();
        prefs.put(Settings.LAST_MODE, mode);
        if (cameraId != null) prefs.put(Settings.LAST_CAMERA, cameraId);
        prefs.put(Settings.FLASH, cs.flash);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ctl.shutdown();
        sound.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA) {
            if (granted) {
                hideMessage();
                startCamera();
            } else {
                showMessage("NongMay ต้องใช้สิทธิ์กล้องเพื่อแสดงภาพและถ่ายรูป\n(ไม่มีสิทธิ์อินเทอร์เน็ต รูปไม่ออกจากเครื่อง)",
                        "ขอสิทธิ์อีกครั้ง", new Runnable() {
                            @Override
                            public void run() {
                                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                            }
                        });
                messageBtn2.setText("เปิดตั้งค่าแอป");
                messageBtn2.setVisibility(View.VISIBLE);
                messageBtn2.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                });
            }
        } else if (requestCode == REQ_MIC_VOICE) {
            if (granted) {
                prefs.put(Settings.VOICE_ENABLED, true);
                if (template == null || !template.isReady()) openVoiceSetup();
                else startVoiceIfAllowed();
            } else {
                prefs.put(Settings.VOICE_ENABLED, false);
                setStatus("ไม่ได้อนุญาตไมโครโฟน จึงใช้ Voice Shutter ไม่ได้ (ปุ่มชัตเตอร์ยังใช้ได้ตามปกติ)", 4000);
            }
            refreshTopBar();
        } else if (requestCode == REQ_MIC_VIDEO) {
            if (!granted) setStatus("ไม่ได้อนุญาตไมค์ — อัดวิดีโอแบบไม่มีเสียง", 3000);
            if (pendingRecord) {
                pendingRecord = false;
                startRecording(0);
            }
        }
    }

    // =====================================================================================
    // Cameras / sessions
    // =====================================================================================

    private void enumerateCameras() {
        backIds.clear();
        frontIds.clear();
        CameraManager m = ctl.manager();
        try {
            for (String id : m.getCameraIdList()) {
                CameraCharacteristics c = m.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraMetadata.LENS_FACING_FRONT) frontIds.add(id);
                else backIds.add(id);
            }
        } catch (CameraAccessException | RuntimeException ignored) {
        }
    }

    private void startCamera() {
        if (!resumed || cameraId == null) return;
        try {
            caps = CameraCaps.load(ctl.manager(), cameraId);
        } catch (CameraAccessException | RuntimeException e) {
            showMessage("อ่านข้อมูลกล้องไม่ได้: " + e.getMessage(), "ลองอีกครั้ง", retryCamera);
            return;
        }
        hideMessage();
        overlay.setSensor(caps.sensorOrientation, caps.isFront());
        loadPreset();
        activeSpec = buildSpec();
        if (activeSpec == null) return;
        layoutPreview();
        rebuildControls();
        awaitFirstFrame = true;
        startCameraNs = SystemClock.elapsedRealtimeNanos();
        sessionReady = false;
        overlay.setBanner(null);
        ctl.setFocusBeforeCapture(prefs.getBool(Settings.FOCUS_BEFORE_CAPTURE, false));
        ctl.start(cameraId, activeSpec, cs);
        if (texture.isAvailable()) ctl.setPreviewTexture(texture.getSurfaceTexture());
    }

    private final Runnable retryCamera = new Runnable() {
        @Override
        public void run() {
            hideMessage();
            startCamera();
        }
    };

    private void reconfigure(String metric) {
        if (caps == null) return;
        CameraController.SessionSpec s = buildSpec();
        if (s == null) return;
        activeSpec = s;
        layoutPreview();
        rebuildControls();
        switchStartNs = SystemClock.elapsedRealtimeNanos();
        switchMetric = metric;
        awaitFirstFrame = true;
        sessionReady = false;
        stopVoice();
        overlay.clearAnalysis();
        overlay.setBanner("กำลังตั้งค่ากล้อง…");
        ctl.reconfigure(activeSpec, cs);
    }

    private void switchCamera(String id) {
        if (id == null || id.equals(cameraId) || ctl.isRecording()) return;
        savePreset();
        cameraId = id;
        switchStartNs = SystemClock.elapsedRealtimeNanos();
        switchMetric = PerfLog.CAMERA_SWITCH;
        cs.zoom = 1f;
        cs.aeLock = false;
        overlay.clearFocus();
        overlay.setBanner("กำลังสลับกล้อง…");
        stopVoice();
        startCamera();
    }

    private boolean isVideoSession() {
        return mode == MODE_VIDEO || (mode == MODE_STORY && storyVideo);
    }

    private double photoSensorAspect() {
        if (mode == MODE_STORY) return 16.0 / 9;
        return "16:9".equals(prefs.getString(Settings.PHOTO_ASPECT, "4:3")) ? 16.0 / 9 : 4.0 / 3;
    }

    private int cropAspect() {
        if (mode == MODE_STORY || isVideoSession()) return 0;
        String a = prefs.getString(Settings.PHOTO_ASPECT, "4:3");
        return "1:1".equals(a) ? 1 : "3:2".equals(a) ? 2 : 0;
    }

    private boolean overlaysWanted() {
        return prefs.getBool(Settings.HISTOGRAM, true) || prefs.getBool(Settings.ZEBRA, false) || prefs.getBool(Settings.PEAKING, false);
    }

    private CameraController.SessionSpec buildSpec() {
        CameraCaps c = caps;
        CameraController.SessionSpec s = new CameraController.SessionSpec();
        if (isVideoSession()) {
            s.mode = CameraController.MODE_VIDEO;
            CameraCaps.VideoMode vm = chooseVideoMode(c);
            if (vm == null) {
                showMessage("กล้องนี้ไม่มีโหมดวิดีโอที่ทั้งกล้องและ encoder รองรับร่วมกัน", "กลับไปถ่ายรูป", new Runnable() {
                    @Override
                    public void run() {
                        setMode(MODE_AUTO);
                    }
                });
                return null;
            }
            boolean hevc = "hevc".equals(prefs.getString(Settings.VIDEO_CODEC, "avc")) && vm.hevc && mode == MODE_VIDEO;
            if (!vm.avc) hevc = true;
            s.video = vm.size;
            s.fps = vm.fps;
            s.codec = hevc ? "video/hevc" : "video/avc";
            int mbps = mode == MODE_VIDEO ? prefs.getInt(Settings.VIDEO_BITRATE, 0) : 0;
            s.bitrate = mbps > 0 ? mbps * 1_000_000 : CameraController.autoBitrate(vm.size.getWidth(), vm.size.getHeight(), vm.fps, hevc);
            s.preview = c.previewSize(16.0 / 9);
            s.analysis = (mode == MODE_VIDEO && overlaysWanted()) ? c.analysisSize(16.0 / 9) : null;
        } else {
            s.mode = CameraController.MODE_PHOTO;
            double a = photoSensorAspect();
            s.jpeg = c.jpegSize(a);
            if (s.jpeg == null) s.jpeg = c.largestJpeg();
            s.preview = c.previewSize(a);
            s.raw = mode == MODE_PRO && prefs.getBool(Settings.RAW, false) && c.raw;
            s.analysis = (mode == MODE_PRO && overlaysWanted()) ? c.analysisSize(a) : null;
        }
        return s;
    }

    private CameraCaps.VideoMode chooseVideoMode(CameraCaps c) {
        List<CameraCaps.VideoMode> modes = c.videoModes();
        if (modes.isEmpty()) return null;
        String res = mode == MODE_STORY ? "1080p" : prefs.getString(Settings.VIDEO_RES, "1080p");
        int fps = mode == MODE_STORY ? 30 : prefs.getInt(Settings.VIDEO_FPS, 30);
        CameraCaps.VideoMode best = null;
        for (CameraCaps.VideoMode m : modes) if (m.resLabel().equals(res) && m.fps == fps) return m;
        for (CameraCaps.VideoMode m : modes) if (m.resLabel().equals(res) && (best == null || Math.abs(m.fps - fps) < Math.abs(best.fps - fps))) best = m;
        if (best != null) return best;
        for (CameraCaps.VideoMode m : modes) if (m.resLabel().equals("1080p") && m.fps == 30) return m;
        return modes.get(modes.size() - 1);
    }

    // =====================================================================================
    // CameraController.Listener
    // =====================================================================================

    @Override
    public void onOpened(CameraCaps c) {
        caps = c;
        overlay.setSensor(c.sensorOrientation, c.isFront());
    }

    @Override
    public void onSessionReady(CameraController.SessionSpec spec, String note) {
        sessionReady = true;
        activeSpec = spec;
        overlay.setBanner(null);
        if (note != null && !note.isEmpty()) setStatus(note, 4000);
        boolean wantAnalysis = spec.analysis != null && overlaysWanted() && thermal < PowerManager.THERMAL_STATUS_SEVERE;
        ctl.analysisConfig.histogram = prefs.getBool(Settings.HISTOGRAM, true);
        ctl.analysisConfig.zebra = prefs.getBool(Settings.ZEBRA, false);
        ctl.analysisConfig.zebraLevel = prefs.getInt(Settings.ZEBRA_LEVEL, 95);
        ctl.analysisConfig.peaking = prefs.getBool(Settings.PEAKING, false);
        ctl.setAnalysisWanted(wantAnalysis, thermal >= PowerManager.THERMAL_STATUS_MODERATE ? 250 : 125);
        overlay.setHistogram(wantAnalysis && prefs.getBool(Settings.HISTOGRAM, true));
        if (!wantAnalysis) overlay.clearAnalysis();
        refreshTopBar();
        startVoiceIfAllowed();
    }

    @Override
    public void onError(String message, boolean retryable) {
        sessionReady = false;
        errorRetryable = retryable;
        overlay.setBanner(null);
        showMessage(message, retryable ? "ลองอีกครั้ง" : null, retryable ? retryCamera : null);
    }

    @Override
    public void onReadout(CameraController.Readout r) {
        CameraCaps c = caps;
        if (c == null) return;
        StringBuilder sb = new StringBuilder();
        if (mode == MODE_PRO || mode == MODE_VIDEO) {
            sb.append("ISO ").append(r.iso).append(" · ").append(ExposureMath.shutter(r.exposureNs));
            if (mode == MODE_VIDEO && activeSpec != null) {
                sb.append(String.format(Locale.ROOT, " (%.0f°)", ExposureMath.shutterAngle(r.exposureNs, activeSpec.fps)));
            }
            if (!Float.isNaN(r.aperture)) sb.append(String.format(Locale.ROOT, " · f/%.1f", r.aperture));
            if (!Float.isNaN(r.focusDiopters)) sb.append(" · ").append(ExposureMath.focusDistance(r.focusDiopters));
            if (cs.wbMode == CaptureSettings.WB_KELVIN) sb.append(" · ").append(cs.kelvin).append("K");
            else if (cs.wbMode == CaptureSettings.WB_AUTO) sb.append(" · AWB");
            if (r.afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || r.afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED) sb.append(" · AF✓");
            if (r.oisOn) sb.append(" · OIS");
        }
        readoutText.setText(sb.toString());
        readoutText.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        StringBuilder lens = new StringBuilder();
        float z = Float.isNaN(r.zoomRatio) ? cs.zoom : r.zoomRatio;
        lens.append(ExposureMath.zoomLabel(z));
        if (!Double.isNaN(c.eqFocal)) lens.append(" · ≈").append(Math.round(c.eqFocal * z)).append("mm eq");
        if (r.physicalId != null) lens.append(" · เลนส์จริง ").append(c.physicalLabel(r.physicalId));
        else if (c.logicalMulti) lens.append(" · HAL ไม่ระบุเลนส์จริง");
        lensText.setText(lens.toString());
    }

    @Override
    public void onShutter(CameraController.ShotInfo s) {
        boolean must = Build.VERSION.SDK_INT >= 33 && MediaActionSound.mustPlayShutterSound();
        if (prefs.shutterSound() || must) {
            voice.suppressFor(900);
            sound.play(MediaActionSound.SHUTTER_CLICK);
        }
        shutter.setBusy(true);
    }

    @Override
    public void onSaved(CameraController.ShotInfo s) {
        if ("voice".equals(s.source) && s.index == 0 && s.jpegUri != null) {
            if (dropNextVoiceShot) {
                dropNextVoiceShot = false;
                lastUri = s.jpegUri;
                deleteVoiceShot(s.jpegUri);
                if (s.index == s.count - 1) shutter.setBusy(false);
                return;
            }
            lastVoiceShotUri = s.jpegUri;
        }
        lastUri = s.jpegUri;
        lastIsVideo = false;
        loadThumb(s.jpegUri, false);
        if (s.index == s.count - 1) shutter.setBusy(false);
        StringBuilder msg = new StringBuilder("บันทึกแล้ว ").append(s.fileName);
        if (s.count > 1) msg.append(String.format(Locale.ROOT, " (%d/%d)", s.index + 1, s.count));
        if (s.requestedExposureNs > 0) {
            boolean ok = Math.abs(s.exposureNs - s.requestedExposureNs) <= Math.max(50_000, s.requestedExposureNs * 0.05)
                    && Math.abs(s.iso - s.requestedIso) <= Math.max(2, s.requestedIso * 0.05);
            msg.append(String.format(Locale.ROOT, "\nขอ ISO %d · %s → ได้ ISO %d · %s %s", s.requestedIso,
                    ExposureMath.shutter(s.requestedExposureNs), s.iso, ExposureMath.shutter(s.exposureNs), ok ? "✓" : "≠"));
        }
        if (s.physicalId != null && caps != null) msg.append("\nเลนส์จริง: ").append(caps.physicalLabel(s.physicalId));
        setStatus(msg.toString(), 3500);
        if (mode == MODE_STORY && prefs.getBool(Settings.STORY_AUTO_OPEN, true) && s.index == s.count - 1) {
            openStory(s.jpegUri);
        }
    }

    @Override
    public void onShotFailed(CameraController.ShotInfo s, String reason) {
        shutter.setBusy(false);
        setStatus("ถ่ายไม่สำเร็จ: " + reason, 4000);
    }

    @Override
    public void onAnalysis(FrameAnalyzer.Result r) {
        overlay.setAnalysis(r);
    }

    @Override
    public void onFocusLocked(boolean success) {
        overlay.focusResult(success);
    }

    @Override
    public void onRecordingStarted(Uri uri) {
        shutter.setRecording(true);
        recStartUptime = SystemClock.uptimeMillis();
        recPaused = false;
        pauseBtn.setVisibility(View.VISIBLE);
        switchBtn.setVisibility(View.GONE);
        recText.setVisibility(View.VISIBLE);
        if (prefs.shutterSound()) sound.play(MediaActionSound.START_VIDEO_RECORDING);
        startRecTicker();
    }

    @Override
    public void onRecordingStopped(Uri uri, String error, long durationMs) {
        shutter.setRecording(false);
        pauseBtn.setVisibility(View.GONE);
        switchBtn.setVisibility(View.VISIBLE);
        recText.setVisibility(View.GONE);
        stopRecTicker();
        if (prefs.shutterSound()) sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        if (error != null) setStatus(error, 4000);
        if (uri != null) {
            lastUri = uri;
            lastIsVideo = true;
            loadThumb(uri, true);
            setStatus("บันทึกวิดีโอแล้ว (" + Fmt.clock(durationMs) + ")", 3000);
            if (mode == MODE_STORY && prefs.getBool(Settings.STORY_AUTO_OPEN, true)) openStory(uri);
        }
        startVoiceIfAllowed();
    }

    @Override
    public void onSettingApplied(double latencyMs) {
    }

    // =====================================================================================
    // Shutter, timer, drive modes
    // =====================================================================================

    private static long eventElapsedNs(MotionEvent ev) {
        long offset = SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * 1_000_000L;
        return ev.getEventTime() * 1_000_000L + offset;
    }

    private boolean photoMode() {
        return !isVideoSession();
    }

    private void onShutterPressed(long tapNs, long touchUptimeMs, String source) {
        if (countdownTask != null || intervalTask != null) {
            cancelCountdown();
            stopInterval();
            setStatus("ยกเลิกแล้ว", 1500);
            return;
        }
        if (!sessionReady) {
            setStatus("กล้องยังไม่พร้อม", 1500);
            return;
        }
        if (!photoMode()) {
            toggleRecording(tapNs);
            return;
        }
        if (drive >= 5 && !"interval".equals(source)) {
            startInterval(drive == 5 ? 3 : 10, drive == 5 ? 20 : 30);
            return;
        }
        int t = prefs.getInt(Settings.TIMER, 0);
        if (t > 0) {
            startCountdown(t, tapNs, source);
            return;
        }
        fire(tapNs, 0, source, touchUptimeMs);
    }

    private void fire(long tapNs, long voiceEndNs, String source, final long touchUptimeMs) {
        CameraController.ShotRequest r = new CameraController.ShotRequest();
        r.source = source;
        r.tapElapsedNs = tapNs;
        r.voiceEndElapsedNs = voiceEndNs;
        if (mode == MODE_PRO) {
            if (drive == 1) r.count = 5;
            else if (drive == 2) r.count = 10;
            else if (drive == 3) r.bracketEv = new double[]{-1, 0, 1};
            else if (drive == 4) r.bracketEv = new double[]{-2, -1, 0, 1, 2};
        }
        r.cropAspect = cropAspect();
        r.mirror = prefs.getBool(Settings.MIRROR_SELFIE, false);
        r.relPath = MediaStoreUtil.PHOTO_DIR;
        ctl.takePhoto(r);
        overlay.blink();
        if (prefs.haptics()) shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (touchUptimeMs > 0) {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    PerfLog.add(PerfLog.TAP_TO_ACK, (frameTimeNanos - touchUptimeMs * 1_000_000L) / 1e6);
                }
            });
        }
    }

    private void startCountdown(final int seconds, final long tapNs, final String source) {
        cancelCountdown();
        final long end = SystemClock.uptimeMillis() + seconds * 1000L;
        countdownTask = new Runnable() {
            @Override
            public void run() {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    overlay.setCountdown(null);
                    shutter.setProgress(-1);
                    countdownTask = null;
                    fire(0, 0, source, 0);
                    return;
                }
                overlay.setCountdown(String.valueOf((int) Math.ceil(left / 1000.0)));
                shutter.setProgress(1f - left / (seconds * 1000f));
                h.postDelayed(this, 50);
            }
        };
        h.post(countdownTask);
    }

    private void cancelCountdown() {
        if (countdownTask != null) h.removeCallbacks(countdownTask);
        countdownTask = null;
        overlay.setCountdown(null);
        shutter.setProgress(-1);
    }

    private void startInterval(final int everySec, final int total) {
        intervalDone = 0;
        intervalTask = new Runnable() {
            @Override
            public void run() {
                if (intervalTask != this) return;
                fire(0, 0, "interval", 0);
                intervalDone++;
                shutter.setProgress(intervalDone / (float) total);
                setStatus(String.format(Locale.ROOT, "Interval %d/%d ทุก %d วินาที · แตะชัตเตอร์เพื่อหยุด", intervalDone, total, everySec), everySec * 1000);
                if (intervalDone >= total) {
                    stopInterval();
                    return;
                }
                h.postDelayed(this, everySec * 1000L);
            }
        };
        h.post(intervalTask);
    }

    private void stopInterval() {
        if (intervalTask != null) h.removeCallbacks(intervalTask);
        intervalTask = null;
        shutter.setProgress(-1);
    }

    private void toggleRecording(long tapNs) {
        if (ctl.isRecording()) {
            ctl.stopRecording(tapNs);
            return;
        }
        startRecording(tapNs);
    }

    private void startRecording(long tapNs) {
        boolean wantAudio = mode == MODE_STORY || prefs.getBool(Settings.VIDEO_AUDIO, true);
        boolean hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (wantAudio && !hasMic && !pendingRecord && !shouldSkipMicAsk()) {
            pendingRecord = true;
            prefs.put("asked_mic_video", true);
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_VIDEO);
            return;
        }
        stopVoice(); // never share the microphone between Voice Shutter and the soundtrack
        ctl.startRecording(wantAudio && hasMic, MediaStoreUtil.VIDEO_DIR, tapNs);
    }

    private boolean shouldSkipMicAsk() {
        return prefs.getBool("asked_mic_video", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
    }

    private void startRecTicker() {
        stopRecTicker();
        recTicker = new Runnable() {
            @Override
            public void run() {
                if (recTicker != this) return;
                long ms = SystemClock.uptimeMillis() - recStartUptime;
                final long bytes = activeSpec == null ? 0 : (long) activeSpec.bitrate / 8 * ms / 1000;
                final String base = (recPaused ? "❚❚ " : "● REC ") + Fmt.clock(ms) + "  ≈" + Fmt.bytes(bytes);
                ctl.pollAmplitude(new CameraController.AmplitudeCallback() {
                    @Override
                    public void onAmplitude(int a) {
                        int bars = a <= 0 ? 0 : (int) Math.min(10, Math.round(10 * Math.log10(1 + 9.0 * a / 32767.0)));
                        StringBuilder meter = new StringBuilder("  🎙");
                        for (int i = 0; i < 10; i++) meter.append(i < bars ? '▮' : '▯');
                        recText.setText(base + (prefs.getBool(Settings.VIDEO_AUDIO, true) ? meter.toString() : "  (ไม่มีเสียง)"));
                    }
                });
                if (thermal >= PowerManager.THERMAL_STATUS_CRITICAL) {
                    ctl.stopRecording(0);
                    setStatus("เครื่องร้อนมาก หยุดอัดเพื่อป้องกันไฟล์เสีย", 5000);
                }
                h.postDelayed(this, 250);
            }
        };
        h.post(recTicker);
    }

    private void stopRecTicker() {
        if (recTicker != null) h.removeCallbacks(recTicker);
        recTicker = null;
    }

    // =====================================================================================
    // Voice shutter
    // =====================================================================================

    private VoiceTemplate loadTemplate() {
        String id = prefs.getString(Settings.VOICE_PROFILE, VoiceProfileStore.DEFAULT_ID);
        VoiceTemplate t = store.get(id);
        if (t == null) t = store.get(VoiceProfileStore.DEFAULT_ID);
        return t;
    }

    private String phraseLabel() {
        return template != null ? ThaiText.display(template.text) : ThaiText.DEFAULT_PHRASE;
    }

    private boolean voiceAllowedNow() {
        return resumed && sessionReady && prefs.voiceEnabled() && photoMode() && !ctl.isRecording()
                && template != null && template.isReady() && voice.hasPermission();
    }

    private void startVoiceIfAllowed() {
        if (!voiceAllowedNow()) {
            stopVoice();
            refreshVoiceHint();
            return;
        }
        if (voice.isRunning()) {
            voice.setTemplate(template);
            return;
        }
        voice.start(template, false, voiceListener);
        refreshVoiceHint();
    }

    private void stopVoice() {
        voice.stop();
        refreshVoiceHint();
    }

    private final AudioKwsEngine.Listener voiceListener = new AudioKwsEngine.Listener() {
        @Override
        public void onTrigger(KwsCore.Event e, long speechEndNs, long decisionNs) {
            PerfLog.voiceOutcome("trigger", e.score / e.threshold);
            if (!voiceAllowedNow() || countdownTask != null || intervalTask != null) return;
            lastVoiceTrigger = e;
            lastVoiceShotUri = null;
            dropNextVoiceShot = false;
            PerfLog.add(PerfLog.VOICE_END_TO_TRIGGER, (decisionNs - speechEndNs) / 1e6);
            setStatus("ได้ยิน “" + phraseLabel() + "” แล้ว!", 1500);
            int cd = prefs.voiceCountdown();
            if (cd > 0) {
                startCountdown(cd, 0, "voice");
            } else {
                fire(0, speechEndNs, "voice", 0);
                offerVoiceUndo();
            }
        }

        @Override
        public void onSegment(KwsCore.Event e) {
            // real-use accuracy evidence: outcome + score÷threshold only (never audio)
            if (e.type != KwsCore.Event.TRIGGER) PerfLog.voiceOutcome(e.reject == null ? "accepted" : e.reject, e.score / e.threshold);
            if (prefs.getBool("voice_debug", false) && !Double.isNaN(e.score)) {
                setStatus(String.format(Locale.ROOT, "คะแนน %.2f / เกณฑ์ %.2f · %s", e.score, e.threshold, KwsCore.rejectLabel(e.reject)), 2000);
            }
        }

        @Override
        public void onLevel(float dbfs) {
            float v = Math.max(0, Math.min(1, (dbfs + 70) / 60f));
            micMeter.setScaleX(Math.max(0.03f, v));
        }

        @Override
        public void onEngineError(String message) {
            setStatus("Voice Shutter: " + message, 4000);
            refreshVoiceHint();
        }

        @Override
        public void onSilenced(boolean silenced) {
            micSilenced = silenced;
            refreshVoiceHint();
        }
    };

    /** After a voice photo: one tap says “that wasn't my phrase” → remember the sound, delete that photo. */
    private void offerVoiceUndo() {
        showMessage("ถ่ายด้วยเสียงแล้ว · ไม่ได้พูด “" + phraseLabel() + "”?", "ไม่ใช่คำนี้ (ลบรูป + จำเสียงนี้)", new Runnable() {
            @Override
            public void run() {
                markLastVoiceFalse();
            }
        });
        voiceUndoShowing = true;   // set after showMessage(), which clears it for every other message
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (voiceUndoShowing) {
                    voiceUndoShowing = false;
                    hideMessage();
                }
            }
        }, 6000);
    }

    private void markLastVoiceFalse() {
        voiceUndoShowing = false;
        hideMessage();
        KwsCore.Event e = lastVoiceTrigger;
        lastVoiceTrigger = null;
        if (e != null && template != null) {
            template.addAnti(e.mel, e.noise);
            try {
                store.save(template);
            } catch (IOException ignored) {
            }
            PerfLog.voiceOutcome("marked_false", e.score / e.threshold);
        }
        if (lastVoiceShotUri != null) deleteVoiceShot(lastVoiceShotUri);
        else dropNextVoiceShot = true;   // still being saved: delete it when it lands
        setStatus("จำเสียงนี้แล้ว จะไม่ถ่ายเพราะเสียงนี้อีก", 2500);
    }

    private void deleteVoiceShot(Uri uri) {
        try {
            getContentResolver().delete(uri, null, null);   // the app's own MediaStore item, deleted on the owner's tap
        } catch (RuntimeException ignored) {
            setStatus("ลบรูปไม่ได้ ลบเองในแกลเลอรีได้", 2500);
        }
        if (uri.equals(lastUri)) {
            lastUri = null;
            thumb.setImageDrawable(null);
        }
        lastVoiceShotUri = null;
    }

    private void refreshVoiceHint() {
        boolean on = prefs.voiceEnabled();
        boolean listening = voice.isRunning();
        micBtn.setImageDrawable(new Icons(on ? Icons.MIC : Icons.MIC_OFF, on ? UiKit.VOICE : UiKit.TEXT));
        micBtn.setContentDescription(on ? "ปิด Voice Shutter (ไมค์ทำงาน)" : "เปิด Voice Shutter");
        micMeter.setVisibility(listening ? View.VISIBLE : View.GONE);
        if (statusReset != null) return; // a transient message is showing
        if (!on) {
            statusText.setText("");
        } else if (!photoMode()) {
            statusText.setText("Voice Shutter พักไว้ในโหมดวิดีโอ (ไม่แย่งไมค์กับเสียงวิดีโอ)");
        } else if (template == null || !template.isReady()) {
            statusText.setText("แตะไอคอนไมค์เพื่อสอนเสียง “" + ThaiText.DEFAULT_PHRASE + "” ก่อนใช้");
        } else if (micSilenced) {
            statusText.setText("ไมค์ถูกระบบปิดเสียงอยู่ (สายโทรเข้า / ปิดไมค์ในแผงด่วน)");
        } else if (listening) {
            statusText.setText("● ฟังอยู่ · พูดว่า “" + phraseLabel() + "” เพื่อถ่ายรูป");
        } else {
            statusText.setText("");
        }
        statusText.setTextColor(on ? UiKit.VOICE : UiKit.TEXT);
    }

    private void onMicButton() {
        if (prefs.voiceEnabled()) {
            prefs.put(Settings.VOICE_ENABLED, false);
            stopVoice();
            setStatus("ปิด Voice Shutter แล้ว ไมค์หยุดทำงาน", 2000);
            return;
        }
        if (!voice.hasPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_VOICE);
            return;
        }
        prefs.put(Settings.VOICE_ENABLED, true);
        if (template == null || !template.isReady()) {
            openVoiceSetup();
            return;
        }
        startVoiceIfAllowed();
    }

    private void openVoiceSetup() {
        startActivity(new Intent(this, VoiceActivity.class));
    }

    // =====================================================================================
    // Pro deck
    // =====================================================================================

    private void rebuildControls() {
        CameraCaps c = caps;
        List<ProPanel.Control> list = new ArrayList<>();
        if (c == null) {
            pro.setControls(list);
            return;
        }
        if (mode == MODE_AUTO) {
            list.add(evControl(c));
        } else if (mode == MODE_PRO || mode == MODE_VIDEO) {
            boolean video = mode == MODE_VIDEO;
            if (video) list.addAll(videoControls(c));
            list.add(isoControl(c));
            list.add(shutterControl(c, video));
            list.add(evControl(c));
            list.add(wbControl(c));
            if (cs.wbMode == CaptureSettings.WB_KELVIN) {
                list.add(kelvinControl());
                list.add(tintControl());
            }
            list.add(afControl(c));
            if (cs.focusMode == CaptureSettings.FOCUS_MANUAL) list.add(mfControl(c));
            list.add(meterControl(c));
            if (!video) {
                list.add(driveControl(c));
                list.add(rawControl(c));
            }
            list.add(lookControl(c));
            list.add(oisControl(c));
            list.add(lockControl(c));
            if (backIds.size() > 1 && !c.isFront()) list.add(cameraControl());
            list.add(presetControl());
        }
        pro.setControls(list);
        pro.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int[] isoValues;
    private long[] shutterValues;

    private ProPanel.Control isoControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("ISO", "ISO");
        k.hasAuto = true;
        k.auto = !cs.manualExposure;
        if (!c.manualSensor || c.isoRange == null) {
            k.disabledReason = "กล้องนี้ไม่เปิด MANUAL_SENSOR ให้แอปภายนอก จึงตั้ง ISO เองไม่ได้";
            return k;
        }
        isoValues = ExposureMath.isoStops(c.isoRange.getLower(), c.isoRange.getUpper());
        k.options = new String[isoValues.length];
        for (int i = 0; i < isoValues.length; i++) k.options[i] = String.valueOf(isoValues[i]);
        k.index = ExposureMath.nearestIndex(isoValues, cs.iso);
        k.hint = "ช่วงจริงของเซนเซอร์ " + c.isoRange.getLower() + "–" + c.isoRange.getUpper()
                + " · ตั้ง ISO หรือ S เองจะล็อกทั้งคู่ (Camera2 บน Android 15 ไม่มีโหมด ISO-priority)";
        return k;
    }

    private ProPanel.Control shutterControl(CameraCaps c, boolean video) {
        ProPanel.Control k = new ProPanel.Control("S", "S");
        k.hasAuto = true;
        k.auto = !cs.manualExposure;
        if (!c.manualSensor || c.expRange == null) {
            k.disabledReason = "กล้องนี้ไม่เปิด MANUAL_SENSOR ให้แอปภายนอก จึงตั้งความเร็วชัตเตอร์เองไม่ได้";
            return k;
        }
        long max = c.expRange.getUpper();
        if (video && activeSpec != null) max = Math.min(max, (long) (1e9 / activeSpec.fps));
        shutterValues = ExposureMath.shutterStops(c.expRange.getLower(), max);
        k.options = new String[shutterValues.length];
        for (int i = 0; i < shutterValues.length; i++) k.options[i] = ExposureMath.shutter(shutterValues[i]);
        k.index = ExposureMath.nearestIndex(shutterValues, cs.exposureNs);
        k.hint = video ? "วิดีโอ: ชัตเตอร์ช้าสุดได้ไม่เกิน 1/fps · มุมชัตเตอร์แสดงในแถบค่าด้านบน"
                : "ช่วงจริง " + ExposureMath.shutter(c.expRange.getLower()) + " – " + ExposureMath.shutter(c.expRange.getUpper());
        return k;
    }

    private ProPanel.Control evControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("EV", "EV");
        if (c.evRange == null || c.evStepSize() == 0 || (c.evRange.getLower() == 0 && c.evRange.getUpper() == 0)) {
            k.disabledReason = "กล้องนี้ไม่รองรับการชดเชยแสง";
            return k;
        }
        if (cs.manualExposure) {
            k.disabledReason = "EV ใช้ได้เมื่อ ISO/S เป็น AUTO";
            return k;
        }
        int lo = c.evRange.getLower(), hi = c.evRange.getUpper();
        k.options = new String[hi - lo + 1];
        for (int i = lo; i <= hi; i++) k.options[i - lo] = ExposureMath.evLabel(i, c.evStepSize());
        k.index = cs.evSteps - lo;
        k.slider = true;
        return k;
    }

    private static final int[] WB_PRESETS = {CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_SHADE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT};
    private static final String[] WB_NAMES = {"แดด", "เมฆ", "ร่ม", "หลอดไส้", "ฟลูออเรสเซนต์", "ฟลูออฯ อุ่น", "พลบค่ำ"};
    private final List<Integer> wbChoices = new ArrayList<>();

    private ProPanel.Control wbControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("WB", "WB");
        k.hasAuto = true;
        k.auto = cs.wbMode == CaptureSettings.WB_AUTO;
        k.slider = false;
        wbChoices.clear();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < WB_PRESETS.length; i++) {
            if (CameraCaps.has(c.awbModes, WB_PRESETS[i])) {
                wbChoices.add(WB_PRESETS[i]);
                names.add(WB_NAMES[i]);
            }
        }
        if (c.kelvinCapable()) {
            wbChoices.add(-1);
            names.add("Kelvin");
        }
        k.options = names.toArray(new String[0]);
        k.index = 0;
        for (int i = 0; i < wbChoices.size(); i++) {
            if (cs.wbMode == CaptureSettings.WB_KELVIN && wbChoices.get(i) == -1) k.index = i;
            if (cs.wbMode == CaptureSettings.WB_PRESET && wbChoices.get(i) == cs.awbPreset) k.index = i;
        }
        k.hint = c.kelvinCapable() ? (c.fm1 != null ? "Kelvin คำนวณจากเมทริกซ์สีของเซนเซอร์ (DNG forward matrix)"
                : "Kelvin เป็นค่าประมาณ (เซนเซอร์ไม่มี forward matrix)")
                : "กล้องนี้ไม่เปิด MANUAL_POST_PROCESSING/ข้อมูลสีเซนเซอร์ จึงมีแค่พรีเซ็ต WB";
        return k;
    }

    private ProPanel.Control kelvinControl() {
        ProPanel.Control k = new ProPanel.Control("K", "K");
        k.options = new String[78];
        for (int i = 0; i < k.options.length; i++) k.options[i] = (2300 + i * 100) + "K";
        k.index = Math.max(0, Math.min(77, (cs.kelvin - 2300) / 100));
        if (caps != null) {
            com.nongmay.camera.camera.pro.WhiteBalanceMath.Result r = ctl.kelvin(cs, caps);
            if (r != null && !r.valid) k.hint = "คำนวณไม่ได้: " + r.note;
            else if (r != null && r.note != null) k.hint = r.note;
        }
        return k;
    }

    private ProPanel.Control tintControl() {
        ProPanel.Control k = new ProPanel.Control("TINT", "Tint");
        k.options = new String[21];
        for (int i = 0; i < 21; i++) k.options[i] = String.format(Locale.ROOT, "%+d", -50 + i * 5);
        k.index = Math.max(0, Math.min(20, (cs.tint + 50) / 5));
        k.hint = "+ = ม่วงแดง (magenta) · − = เขียว";
        return k;
    }

    private ProPanel.Control afControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("AF", "AF");
        k.slider = false;
        boolean mf = c.manualFocus();
        k.options = mf ? new String[]{"AF-C", "AF-S", "MF"} : new String[]{"AF-C", "AF-S"};
        k.index = Math.min(cs.focusMode, k.options.length - 1);
        if (c.afModes.length <= 1 && !mf) k.disabledReason = "เลนส์นี้เป็นโฟกัสคงที่";
        if (!mf) k.hint = "MF ไม่มี: เลนส์นี้ไม่รายงานระยะโฟกัสใกล้สุด หรือไม่เปิด MANUAL_SENSOR";
        return k;
    }

    private ProPanel.Control mfControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("MF", "MF");
        k.options = new String[101];
        for (int i = 0; i <= 100; i++) k.options[i] = ExposureMath.focusDistance(c.minFocusDiopters * i / 100.0);
        k.index = Math.round(cs.focusDiopters / Math.max(1e-6f, c.minFocusDiopters) * 100);
        k.index = Math.max(0, Math.min(100, k.index));
        k.hint = c.focusCalibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
                ? "หน่วยระยะเป็นค่าประมาณ (HAL รายงานว่า UNCALIBRATED) · ภาพขยายช่วยโฟกัสระหว่างหมุน"
                : "หน่วยจาก HAL (ไดออปเตอร์) · ภาพขยายช่วยโฟกัสระหว่างหมุน · เปิด Peaking ได้ในตั้งค่า";
        return k;
    }

    private ProPanel.Control meterControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("MTR", "วัดแสง");
        k.slider = false;
        k.options = new String[]{"Matrix", "Center", "Spot"};
        k.index = cs.metering;
        if (c.maxAe == 0) k.disabledReason = "กล้องนี้ไม่รับ AE regions จึงวัดแสงได้แบบเดียว";
        else k.hint = "Center/Spot ทำผ่าน AE region ของ Camera2 (ใกล้เคียง ไม่ใช่โหมดวัดแสงของกล้องจริง) · แตะจอเพื่อเลือกจุด";
        return k;
    }

    private ProPanel.Control driveControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("DRIVE", "Drive");
        k.slider = false;
        k.options = DRIVE;
        k.index = drive;
        k.hint = (!c.manualSensor ? "AEB ต้องใช้ MANUAL_SENSOR (ไม่มีในกล้องนี้) · " : "")
                + (prefs.getBool(Settings.RAW, false) ? "เปิด RAW อยู่: ถ่ายต่อเนื่องได้สูงสุด 3 ภาพ · " : "")
                + "ตั้งเวลา (Timer) อยู่ที่แถบบน";
        return k;
    }

    private ProPanel.Control rawControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("RAW", "RAW");
        k.slider = false;
        k.options = new String[]{"ปิด", "RAW+JPEG"};
        k.index = prefs.getBool(Settings.RAW, false) ? 1 : 0;
        if (!c.raw) k.disabledReason = "กล้องนี้ไม่ให้ RAW_SENSOR กับแอปภายนอก (ไม่ปลอม RAW จาก JPEG)";
        else if (c.rawSize() != null) k.hint = "DNG " + c.rawSize().getWidth() + "×" + c.rawSize().getHeight() + " จากเซนเซอร์จริง";
        return k;
    }

    private ProPanel.Control lookControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("LOOK", "Look");
        k.slider = false;
        boolean mono = CameraCaps.has(c.effects, CameraMetadata.CONTROL_EFFECT_MODE_MONO);
        k.options = mono ? new String[]{"Standard", "Natural", "Mono"} : new String[]{"Standard", "Natural"};
        k.index = Math.min(cs.look, k.options.length - 1);
        k.hint = "Natural = ปิดการเพิ่มความคม + ลดนอยส์น้อยสุด (ถ้า HAL รองรับ) มีผลทั้งพรีวิวและไฟล์ ไม่มีผลกับ RAW";
        return k;
    }

    private ProPanel.Control oisControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("OIS", "OIS");
        k.slider = false;
        k.options = new String[]{"เปิด", "ปิด"};
        k.index = cs.ois ? 0 : 1;
        if (!(CameraCaps.has(c.oisModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                && CameraCaps.has(c.oisModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF))) {
            k.disabledReason = "เลนส์นี้ไม่ให้แอปเปิด/ปิด OIS เอง";
        }
        return k;
    }

    private ProPanel.Control lockControl(CameraCaps c) {
        ProPanel.Control k = new ProPanel.Control("LOCK", "Lock");
        k.slider = false;
        k.options = new String[]{"ปลดทั้งหมด", "AE", "AWB", "AE+AWB"};
        k.index = (cs.aeLock ? 1 : 0) + (cs.awbLock ? 2 : 0);
        k.hint = "กดค้างที่จอ = ล็อก AE/AF ที่จุดนั้น" + (c.aeLockAvailable ? "" : " · กล้องนี้ไม่มี AE lock")
                + (c.awbLockAvailable ? "" : " · ไม่มี AWB lock");
        return k;
    }

    private ProPanel.Control cameraControl() {
        ProPanel.Control k = new ProPanel.Control("CAM", "กล้อง");
        k.slider = false;
        k.options = new String[backIds.size()];
        for (int i = 0; i < backIds.size(); i++) {
            String label = "ID " + backIds.get(i);
            try {
                CameraCaps cc = CameraCaps.load(ctl.manager(), backIds.get(i));
                label = "ID " + cc.id + (Double.isNaN(cc.eqFocal) ? "" : " ≈" + Math.round(cc.eqFocal) + "mm");
            } catch (CameraAccessException | RuntimeException ignored) {
            }
            k.options[i] = label;
            if (backIds.get(i).equals(cameraId)) k.index = i;
        }
        k.hint = "เฉพาะกล้องที่ระบบเปิดให้แอปภายนอก (ไม่เดาจากสเปกเครื่อง) · สลับแล้วต้องเปิดกล้องใหม่";
        return k;
    }

    private ProPanel.Control presetControl() {
        ProPanel.Control k = new ProPanel.Control("PRESET", "★");
        k.slider = false;
        k.actions = true;
        k.options = new String[]{"โหลด P1", "โหลด P2", "โหลด P3", "บันทึก P1", "บันทึก P2", "บันทึก P3", "รีเซ็ต AUTO"};
        k.hint = "พรีเซ็ตแยกตามเลนส์และโหมด · ค่าล่าสุดจำให้อัตโนมัติ";
        return k;
    }

    private List<ProPanel.Control> videoControls(CameraCaps c) {
        List<ProPanel.Control> out = new ArrayList<>();
        List<CameraCaps.VideoMode> modes = c.videoModes();
        Set<String> resSet = new LinkedHashSet<>();
        for (CameraCaps.VideoMode m : modes) resSet.add(m.resLabel());
        ProPanel.Control res = new ProPanel.Control("RES", "ขนาด");
        res.slider = false;
        res.options = resSet.toArray(new String[0]);
        String curRes = activeSpec != null && activeSpec.video != null ? CameraCaps.VideoMode.resLabel(activeSpec.video) : "1080p";
        for (int i = 0; i < res.options.length; i++) if (res.options[i].equals(curRes)) res.index = i;
        res.hint = "แสดงเฉพาะที่กล้อง + encoder ของเครื่องนี้รับได้จริง";
        out.add(res);
        ProPanel.Control fps = new ProPanel.Control("FPS", "fps");
        fps.slider = false;
        List<String> f = new ArrayList<>();
        for (CameraCaps.VideoMode m : modes) if (m.resLabel().equals(curRes)) f.add(String.valueOf(m.fps));
        fps.options = f.toArray(new String[0]);
        int curFps = activeSpec != null ? activeSpec.fps : 30;
        for (int i = 0; i < fps.options.length; i++) if (fps.options[i].equals(String.valueOf(curFps))) fps.index = i;
        out.add(fps);
        ProPanel.Control codec = new ProPanel.Control("CODEC", "Codec");
        codec.slider = false;
        codec.options = new String[]{"H.264", "HEVC"};
        codec.index = activeSpec != null && "video/hevc".equals(activeSpec.codec) ? 1 : 0;
        out.add(codec);
        ProPanel.Control br = new ProPanel.Control("BR", "Bitrate");
        br.slider = false;
        br.options = new String[]{"Auto", "10M", "20M", "40M", "60M", "100M"};
        int mb = prefs.getInt(Settings.VIDEO_BITRATE, 0);
        int[] vals = {0, 10, 20, 40, 60, 100};
        for (int i = 0; i < vals.length; i++) if (vals[i] == mb) br.index = i;
        if (activeSpec != null) br.hint = "ตอนนี้ " + Fmt.bitrate(activeSpec.bitrate);
        out.add(br);
        ProPanel.Control stab = new ProPanel.Control("STAB", "กันสั่น");
        stab.slider = false;
        boolean eis = CameraCaps.has(c.stabModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        boolean pstab = Build.VERSION.SDK_INT >= 33 && CameraCaps.has(c.stabModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
        stab.options = pstab ? new String[]{"ปิด", "EIS", "EIS+พรีวิว"} : new String[]{"ปิด", "EIS"};
        stab.index = Math.min(cs.videoStab, stab.options.length - 1);
        if (!eis) stab.disabledReason = "กล้องนี้ไม่มี video stabilization ให้แอปภายนอก";
        else stab.hint = "EIS ครอปภาพเล็กน้อย · OIS ตั้งแยกที่ชิป OIS";
        out.add(stab);
        ProPanel.Control audio = new ProPanel.Control("AUDIO", "เสียง");
        audio.slider = false;
        audio.options = new String[]{"เปิด", "ปิด"};
        audio.index = prefs.getBool(Settings.VIDEO_AUDIO, true) ? 0 : 1;
        audio.hint = "AAC 48 kHz · ระหว่างอัด Voice Shutter จะพักเพื่อไม่แย่งไมค์";
        out.add(audio);
        return out;
    }

    @Override
    public void onControlChanged(ProPanel.Control k, boolean fromUser) {
        CameraCaps c = caps;
        if (c == null) return;
        CameraController.Readout r = ctl.lastReadout();
        boolean rebuild = false;
        switch (k.key) {
            case "ISO":
                if (k.auto) {
                    cs.manualExposure = false;
                } else {
                    int iso = isoValues[k.index];
                    if (!cs.manualExposure && r != null && r.iso > 0) {
                        cs.exposureNs = ExposureMath.equivalentExposure(r.exposureNs, r.iso, iso);
                        if (c.expRange != null) cs.exposureNs = Math.max(c.expRange.getLower(), Math.min(c.expRange.getUpper(), cs.exposureNs));
                    }
                    cs.iso = iso;
                    cs.manualExposure = true;
                }
                rebuild = true;
                break;
            case "S":
                if (k.auto) {
                    cs.manualExposure = false;
                } else {
                    long e = shutterValues[k.index];
                    if (!cs.manualExposure && r != null && r.exposureNs > 0) {
                        int iso = (int) Math.round((double) r.iso * r.exposureNs / e);
                        if (c.isoRange != null) iso = Math.max(c.isoRange.getLower(), Math.min(c.isoRange.getUpper(), iso));
                        cs.iso = iso;
                    }
                    cs.exposureNs = e;
                    cs.manualExposure = true;
                }
                rebuild = true;
                break;
            case "EV":
                if (c.evRange != null) cs.evSteps = c.evRange.getLower() + k.index;
                break;
            case "WB":
                if (k.auto || wbChoices.isEmpty()) {
                    cs.wbMode = CaptureSettings.WB_AUTO;
                } else if (wbChoices.get(k.index) == -1) {
                    cs.wbMode = CaptureSettings.WB_KELVIN;
                } else {
                    cs.wbMode = CaptureSettings.WB_PRESET;
                    cs.awbPreset = wbChoices.get(k.index);
                }
                rebuild = true;
                break;
            case "K":
                cs.kelvin = 2300 + k.index * 100;
                break;
            case "TINT":
                cs.tint = -50 + k.index * 5;
                break;
            case "AF":
                cs.focusMode = k.index;
                overlay.clearFocus();
                ctl.clearLocks();
                if (cs.focusMode == CaptureSettings.FOCUS_MANUAL && r != null && !Float.isNaN(r.focusDiopters)) {
                    cs.focusDiopters = r.focusDiopters;
                }
                rebuild = true;
                break;
            case "MF":
                cs.focusDiopters = c.minFocusDiopters * k.index / 100f;
                showLoupe();
                break;
            case "MTR":
                cs.metering = k.index;
                break;
            case "DRIVE":
                drive = k.index;
                if (drive >= 3 && drive <= 4 && !c.manualSensor) {
                    drive = 0;
                    setStatus("AEB ต้องใช้ MANUAL_SENSOR ซึ่งกล้องนี้ไม่มี", 3000);
                    rebuild = true;
                }
                break;
            case "RAW":
                prefs.put(Settings.RAW, k.index == 1);
                reconfigure(PerfLog.MODE_SWITCH);
                return;
            case "LOOK":
                cs.look = k.index;
                break;
            case "OIS":
                cs.ois = k.index == 0;
                break;
            case "LOCK":
                cs.aeLock = (k.index & 1) != 0 && c.aeLockAvailable && !cs.manualExposure;
                cs.awbLock = (k.index & 2) != 0 && c.awbLockAvailable && cs.wbMode == CaptureSettings.WB_AUTO;
                break;
            case "CAM":
                switchCamera(backIds.get(k.index));
                return;
            case "PRESET":
                handlePreset(k.index);
                rebuild = true;
                break;
            case "RES": {
                prefs.put(Settings.VIDEO_RES, k.options[k.index]);
                reconfigure(PerfLog.MODE_SWITCH);
                return;
            }
            case "FPS":
                if (k.options.length > 0) prefs.put(Settings.VIDEO_FPS, Integer.parseInt(k.options[k.index]));
                reconfigure(PerfLog.MODE_SWITCH);
                return;
            case "CODEC":
                prefs.put(Settings.VIDEO_CODEC, k.index == 1 ? "hevc" : "avc");
                reconfigure(PerfLog.MODE_SWITCH);
                return;
            case "BR": {
                int[] vals = {0, 10, 20, 40, 60, 100};
                prefs.put(Settings.VIDEO_BITRATE, vals[k.index]);
                reconfigure(PerfLog.MODE_SWITCH);
                return;
            }
            case "STAB":
                cs.videoStab = k.index;
                break;
            case "AUDIO":
                prefs.put(Settings.VIDEO_AUDIO, k.index == 0);
                break;
            default:
                break;
        }
        ctl.updateSettings(cs);
        savePreset();
        if (rebuild) rebuildControls();
    }

    private void handlePreset(int action) {
        String base = "fav_" + cameraId + "_" + mode + "_P";
        if (action <= 2) {
            String s = prefs.getString(base + (action + 1), null);
            if (s == null) {
                setStatus("ยังไม่ได้บันทึก P" + (action + 1), 2000);
                return;
            }
            try {
                cs.applyPreset(new JSONObject(s));
                setStatus("โหลด P" + (action + 1) + " แล้ว", 1500);
            } catch (JSONException e) {
                setStatus("พรีเซ็ตเสีย ลบแล้ว", 2000);
                prefs.put(base + (action + 1), (String) null);
            }
        } else if (action <= 5) {
            prefs.put(base + (action - 2), cs.toPreset().toString());
            setStatus("บันทึก P" + (action - 2) + " แล้ว", 1500);
        } else {
            cs.resetToAuto();
            overlay.clearFocus();
            ctl.clearLocks();
            setStatus("กลับเป็นอัตโนมัติทั้งหมด", 1500);
        }
    }

    private void savePreset() {
        if (cameraId == null || (mode != MODE_PRO && mode != MODE_VIDEO)) return;
        prefs.put("preset_" + cameraId + "_" + mode, cs.toPreset().toString());
    }

    private void loadPreset() {
        if (mode == MODE_AUTO || mode == MODE_STORY) {
            cs.resetToAuto();
            cs.look = CaptureSettings.LOOK_STANDARD;
            return;
        }
        String s = prefs.getString("preset_" + cameraId + "_" + mode, null);
        cs.resetToAuto();
        if (s != null) {
            try {
                cs.applyPreset(new JSONObject(s));
            } catch (JSONException ignored) {
            }
        }
    }

    private void showLoupe() {
        previewBox.setScaleX(2.5f);
        previewBox.setScaleY(2.5f);
        if (loupeReset != null) h.removeCallbacks(loupeReset);
        loupeReset = new Runnable() {
            @Override
            public void run() {
                previewBox.setScaleX(1f);
                previewBox.setScaleY(1f);
            }
        };
        h.postDelayed(loupeReset, 1200);
    }

    // =====================================================================================
    // Modes and UI building
    // =====================================================================================

    private void setMode(int m) {
        if (m == mode || ctl.isRecording()) return;
        cancelCountdown();
        stopInterval();
        savePreset();
        boolean wasVideo = isVideoSession();
        double oldAspect = wasVideo ? 16.0 / 9 : photoSensorAspect();
        boolean oldRaw = activeSpec != null && activeSpec.raw;
        boolean oldAnalysis = activeSpec != null && activeSpec.analysis != null;
        mode = m;
        loadPreset();
        drive = 0;
        refreshModeRow();
        refreshTopBar();
        overlay.setStoryZones(mode == MODE_STORY && prefs.getBool(Settings.STORY_SAFE_ZONES, true));
        shutter.setKind(isVideoSession() ? ShutterButton.VIDEO : ShutterButton.PHOTO);
        CameraController.SessionSpec next = caps == null ? null : buildSpec();
        boolean streamsChange = next == null || next.mode != (activeSpec == null ? -1 : activeSpec.mode)
                || Math.abs((isVideoSession() ? 16.0 / 9 : photoSensorAspect()) - oldAspect) > 0.01
                || next.raw != oldRaw || (next.analysis != null) != oldAnalysis
                || (next.mode == CameraController.MODE_VIDEO && activeSpec != null
                && (!next.video.equals(activeSpec.video) || next.fps != activeSpec.fps || next.bitrate != activeSpec.bitrate));
        if (streamsChange) {
            reconfigure(PerfLog.MODE_SWITCH);
        } else {
            rebuildControls();
            ctl.updateSettings(cs);
            startVoiceIfAllowed();
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(UiKit.BG);
        previewBox = new FrameLayout(this);
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(textureListener);
        previewBox.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new OverlayView(this);
        previewBox.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(previewBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupGestures();

        topBar = UiKit.row(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = UiKit.dp(this, 6);
        topBar.setPadding(pad, 0, pad, 0);
        flashBtn = iconButton(Icons.FLASH_OFF, "แฟลช");
        aspectBtn = UiKit.text(this, "4:3", 13, UiKit.TEXT, true);
        aspectBtn.setGravity(Gravity.CENTER);
        aspectBtn.setMinWidth(UiKit.dp(this, 48));
        aspectBtn.setMinHeight(UiKit.dp(this, 48));
        aspectBtn.setContentDescription("อัตราส่วนภาพ (aspect ratio)");
        timerBtn = iconButton(Icons.TIMER, "ตั้งเวลาถ่าย");
        timerLabel = UiKit.text(this, "", 10, UiKit.ACCENT, true);
        gridBtn = iconButton(Icons.GRID, "เส้นตาราง");
        levelBtn = iconButton(Icons.LEVEL, "ระดับน้ำ");
        micBtn = iconButton(Icons.MIC_OFF, "Voice Shutter");
        settingsBtn = iconButton(Icons.SETTINGS, "ตั้งค่า");
        int b = UiKit.dp(this, 48);
        topBar.addView(flashBtn, UiKit.lp(b, b));
        topBar.addView(aspectBtn, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, b));
        FrameLayout timerBox = new FrameLayout(this);
        timerBox.addView(timerBtn, new FrameLayout.LayoutParams(b, b));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        timerBox.addView(timerLabel, tl);
        topBar.addView(timerBox, UiKit.lp(b, b));
        topBar.addView(gridBtn, UiKit.lp(b, b));
        topBar.addView(levelBtn, UiKit.lp(b, b));
        topBar.addView(new View(this), UiKit.weight(1));
        topBar.addView(micBtn, UiKit.lp(b, b));
        topBar.addView(settingsBtn, UiKit.lp(b, b));
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        rotatables.add(flashBtn);
        rotatables.add(aspectBtn);
        rotatables.add(timerBox);
        rotatables.add(gridBtn);
        rotatables.add(levelBtn);
        rotatables.add(micBtn);
        rotatables.add(settingsBtn);

        bottom = UiKit.col(this);
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF0000000, 0xB0000000, 0x00000000});
        bottom.setBackground(grad);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        statusText = UiKit.text(this, "", 13, UiKit.TEXT, false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(pad * 2, pad, pad * 2, 0);
        micMeter = new View(this);
        micMeter.setBackground(UiKit.round(UiKit.VOICE, UiKit.dp(this, 2)));
        micMeter.setPivotX(0);
        micMeter.setVisibility(View.GONE);
        readoutText = UiKit.text(this, "", 12, UiKit.ACCENT, true);
        readoutText.setGravity(Gravity.CENTER);
        recText = UiKit.text(this, "", 14, UiKit.DANGER, true);
        recText.setGravity(Gravity.CENTER);
        recText.setVisibility(View.GONE);
        lensText = UiKit.text(this, "", 11, UiKit.MUTED, false);
        lensText.setGravity(Gravity.CENTER);
        bottom.addView(statusText, UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(micMeter, UiKit.lpm(UiKit.dp(this, 120), UiKit.dp(this, 3), 0, UiKit.dp(this, 4), 0, UiKit.dp(this, 2)));
        bottom.addView(recText);
        bottom.addView(readoutText);
        pro = new ProPanel(this);
        pro.setListener(this);
        bottom.addView(pro, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, pad, 0, 0));
        HorizontalScrollView zs = new HorizontalScrollView(this);
        zs.setHorizontalScrollBarEnabled(false);
        zoomRow = UiKit.row(this);
        zs.addView(zoomRow);
        bottom.addView(zs, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, pad, 0, 0));
        bottom.addView(lensText);
        storyRow = UiKit.row(this);
        storyRow.setGravity(Gravity.CENTER);
        bottom.addView(storyRow, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, pad, 0, 0));
        modeRow = UiKit.row(this);
        modeRow.setGravity(Gravity.CENTER);
        bottom.addView(modeRow, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44), 0, pad, 0, 0));
        LinearLayout controls = UiKit.row(this);
        controls.setGravity(Gravity.CENTER);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(UiKit.roundStroke(0xFF222222, 0xFFFFFFFF, UiKit.dp(this, 2), UiKit.dp(this, 12)));
        thumb.setClipToOutline(true);
        thumb.setContentDescription("ดูรูปล่าสุด (gallery)");
        shutter = new ShutterButton(this);
        switchBtn = iconButton(Icons.SWITCH, "สลับกล้องหน้า/หลัง");
        switchBtn.setBackground(UiKit.round(0x33FFFFFF, UiKit.dp(this, 26)));
        pauseBtn = iconButton(Icons.PAUSE, "หยุดชั่วคราว");
        pauseBtn.setBackground(UiKit.round(0x33FFFFFF, UiKit.dp(this, 26)));
        pauseBtn.setVisibility(View.GONE);
        int side = UiKit.dp(this, 56), shut = UiKit.dp(this, 82);
        boolean left = prefs.leftHanded();
        View a = left ? switchBtn : thumb, z = left ? thumb : switchBtn;
        controls.addView(a, UiKit.lp(side, side));
        controls.addView(new View(this), UiKit.weight(1));
        controls.addView(shutter, UiKit.lp(shut, shut));
        controls.addView(new View(this), UiKit.weight(1));
        FrameLayout rightBox = new FrameLayout(this);
        rightBox.addView(z, new FrameLayout.LayoutParams(side, side));
        rightBox.addView(pauseBtn, new FrameLayout.LayoutParams(side, side));
        controls.addView(rightBox, UiKit.lp(side, side));
        controls.setPadding(UiKit.dp(this, 32), pad, UiKit.dp(this, 32), UiKit.dp(this, 10));
        bottom.addView(controls, UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        rotatables.add(thumb);
        rotatables.add(switchBtn);

        messagePanel = UiKit.col(this);
        messagePanel.setGravity(Gravity.CENTER);
        messagePanel.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 24), UiKit.dp(this, 24), UiKit.dp(this, 24));
        messagePanel.setBackground(UiKit.round(UiKit.CARD, UiKit.dp(this, 18)));
        messageText = UiKit.text(this, "", 16, UiKit.TEXT, false);
        messageText.setGravity(Gravity.CENTER);
        messageBtn1 = UiKit.button(this, "", true);
        messageBtn2 = UiKit.button(this, "", false);
        messagePanel.addView(messageText);
        messagePanel.addView(messageBtn1, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 16), 0, 0));
        messagePanel.addView(messageBtn2, UiKit.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, UiKit.dp(this, 8), 0, 0));
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mp.setMargins(UiKit.dp(this, 24), 0, UiKit.dp(this, 24), 0);
        root.addView(messagePanel, mp);
        messagePanel.setVisibility(View.GONE);

        setContentView(root);
        wireButtons();
        refreshModeRow();
        overlay.setStoryZones(mode == MODE_STORY && prefs.getBool(Settings.STORY_SAFE_ZONES, true));
        shutter.setKind(isVideoSession() ? ShutterButton.VIDEO : ShutterButton.PHOTO);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    insetTop = in.top;
                    insetBottom = in.bottom;
                } else {
                    //noinspection deprecation
                    insetTop = insets.getSystemWindowInsetTop();
                    //noinspection deprecation
                    insetBottom = insets.getSystemWindowInsetBottom();
                }
                topBar.setPadding(topBar.getPaddingLeft(), insetTop, topBar.getPaddingRight(), 0);
                bottom.setPadding(0, 0, 0, insetBottom);
                layoutPreview();
                return insets;
            }
        });
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b2, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b2 - t != ob - ot) layoutPreview();
            }
        });
    }

    private ImageView iconButton(int icon, String desc) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new Icons(icon, UiKit.TEXT));
        int p = UiKit.dp(this, 12);
        v.setPadding(p, p, p, p);
        v.setContentDescription(desc);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void layoutPreview() {
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        double aspect = isVideoSession() ? 16.0 / 9 : photoSensorAspect();
        int vw = W, vh = (int) Math.round(W * aspect);
        int top = aspect < 1.5 ? insetTop + UiKit.dp(this, 48) : insetTop;
        if (top + vh > H) {
            vh = H - top;
            vw = (int) Math.round(vh / aspect);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(vw, vh, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = top;
        previewBox.setLayoutParams(lp);
        previewBox.setPivotX(vw / 2f);
        previewBox.setPivotY(vh / 2f);
        overlay.setCropMask(cropAspect());
    }

    private void wireButtons() {
        flashBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (caps == null || !caps.flash) {
                    setStatus("กล้องนี้ไม่มีแฟลช", 1500);
                    return;
                }
                cs.flash = (cs.flash + 1) % 4;
                if (isVideoSession() && (cs.flash == CaptureSettings.FLASH_AUTO || cs.flash == CaptureSettings.FLASH_ON)) {
                    cs.flash = CaptureSettings.FLASH_TORCH;
                }
                ctl.updateSettings(cs);
                refreshTopBar();
                String[] names = {"แฟลช: ปิด", "แฟลช: อัตโนมัติ (ZSL ปิดชั่วคราว)", "แฟลช: เปิด", "ไฟฉาย: เปิดค้าง"};
                setStatus(names[cs.flash], 1500);
            }
        });
        aspectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mode == MODE_STORY || isVideoSession()) return;
                String cur = prefs.getString(Settings.PHOTO_ASPECT, "4:3");
                int i = 0;
                for (int k = 0; k < ASPECTS.length; k++) if (ASPECTS[k].equals(cur)) i = k;
                String next = ASPECTS[(i + 1) % ASPECTS.length];
                if ("16:9".equals(next) && caps != null && caps.jpegSize(16.0 / 9) == null) next = "1:1";
                prefs.put(Settings.PHOTO_ASPECT, next);
                refreshTopBar();
                describeAspect(next);
                reconfigure(PerfLog.MODE_SWITCH);
            }
        });
        timerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = prefs.getInt(Settings.TIMER, 0);
                int i = 0;
                for (int k = 0; k < TIMERS.length; k++) if (TIMERS[k] == cur) i = k;
                prefs.put(Settings.TIMER, TIMERS[(i + 1) % TIMERS.length]);
                refreshTopBar();
            }
        });
        gridBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int g = (overlay.grid() + 1) % 4;
                overlay.setGrid(g);
                prefs.put(Settings.GRID, g);
                String[] names = {"ปิดเส้นตาราง", "เส้นแบ่ง 3 ส่วน", "สัดส่วนทอง", "จุดกึ่งกลาง"};
                setStatus(names[g], 1200);
                refreshTopBar();
            }
        });
        levelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !prefs.getBool(Settings.LEVEL, false);
                prefs.put(Settings.LEVEL, on);
                overlay.setLevelEnabled(on);
                if (on) level.start(levelListener); else level.stop();
                if (on && !level.available()) setStatus("เครื่องนี้ไม่มีเซนเซอร์แรงโน้มถ่วง", 2000);
                refreshTopBar();
            }
        });
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onMicButton();
            }
        });
        micBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openVoiceSetup();
                return true;
            }
        });
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(CameraActivity.this, SettingsActivity.class));
            }
        });
        switchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (caps == null) return;
                if (caps.isFront()) {
                    if (!backIds.isEmpty()) switchCamera(backIds.get(0));
                } else if (!frontIds.isEmpty()) {
                    switchCamera(frontIds.get(0));
                }
            }
        });
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recPaused = !recPaused;
                ctl.pauseRecording(recPaused);
                pauseBtn.setImageDrawable(new Icons(recPaused ? Icons.PLAY : Icons.PAUSE, UiKit.TEXT));
            }
        });
        thumb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastUri == null) {
                    setStatus("ยังไม่มีรูปในรอบนี้ · รูปอยู่ที่อัลบั้ม Pictures/NongMay", 2000);
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(lastUri, lastIsVideo ? "video/*" : "image/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(i);
                } catch (RuntimeException e) {
                    setStatus("ไม่มีแอปแกลเลอรีเปิดไฟล์นี้", 2000);
                }
            }
        });
        thumb.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (lastUri != null) openStory(lastUri);
                return true;
            }
        });
        shutter.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if (photoMode() && prefs.touchDownShutter()) onShutterPressed(eventElapsedNs(ev), ev.getEventTime(), "button");
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (!photoMode() || !prefs.touchDownShutter()) onShutterPressed(eventElapsedNs(ev), ev.getEventTime(), "button");
                        v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void describeAspect(String a) {
        CameraCaps c = caps;
        if (c == null) return;
        Size s43 = c.jpegSize(4.0 / 3), s169 = c.jpegSize(16.0 / 9);
        String msg;
        if ("4:3".equals(a)) msg = "4:3 · " + (s43 == null ? "" : Fmt.mp(s43.getWidth(), s43.getHeight())) + " (เต็มเซนเซอร์ตามที่แอปได้)";
        else if ("16:9".equals(a)) msg = "16:9 · " + (s169 == null ? "" : Fmt.mp(s169.getWidth(), s169.getHeight())) + " (ครอปเซนเซอร์)";
        else if ("1:1".equals(a)) msg = "1:1 · ครอปหลังถ่ายจาก 4:3 (เข้ารหัส JPEG ใหม่, คัดลอก EXIF)";
        else msg = "3:2 · ครอปหลังถ่ายจาก 4:3 (เข้ารหัส JPEG ใหม่, คัดลอก EXIF)";
        setStatus(msg, 2500);
    }

    private void refreshTopBar() {
        int[] flashIcons = {Icons.FLASH_OFF, Icons.FLASH_AUTO, Icons.FLASH_ON, Icons.TORCH};
        flashBtn.setImageDrawable(new Icons(flashIcons[Math.max(0, Math.min(3, cs.flash))], cs.flash == 0 ? UiKit.TEXT : UiKit.ACCENT));
        boolean photoAspect = mode != MODE_STORY && !isVideoSession();
        aspectBtn.setVisibility(photoAspect ? View.VISIBLE : View.INVISIBLE);
        aspectBtn.setText(prefs.getString(Settings.PHOTO_ASPECT, "4:3"));
        int t = prefs.getInt(Settings.TIMER, 0);
        timerBtn.setImageDrawable(new Icons(Icons.TIMER, t > 0 ? UiKit.ACCENT : UiKit.TEXT));
        timerLabel.setText(t > 0 ? t + "s" : "");
        gridBtn.setImageDrawable(new Icons(Icons.GRID, prefs.getInt(Settings.GRID, 0) > 0 ? UiKit.ACCENT : UiKit.TEXT));
        levelBtn.setImageDrawable(new Icons(Icons.LEVEL, prefs.getBool(Settings.LEVEL, false) ? UiKit.ACCENT : UiKit.TEXT));
        refreshVoiceHint();
        refreshZoomRow();
        refreshStoryRow();
    }

    private void refreshModeRow() {
        modeRow.removeAllViews();
        for (int i = 0; i < MODE_LABELS.length; i++) {
            final int m = i;
            TextView t = UiKit.text(this, MODE_LABELS[i], 14, m == mode ? UiKit.ACCENT : UiKit.TEXT, m == mode);
            t.setGravity(Gravity.CENTER);
            t.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 12), 0);
            t.setContentDescription("โหมด " + MODE_LABELS[i]);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setMode(m);
                }
            });
            modeRow.addView(t, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        TextView lab = UiKit.text(this, "StoryMax ›", 14, UiKit.VOICE, true);
        lab.setGravity(Gravity.CENTER);
        lab.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 4), 0);
        lab.setContentDescription("เปิด StoryMax Lab (นำเข้า/ส่งออก Story)");
        lab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(CameraActivity.this, StoryActivity.class));
            }
        });
        modeRow.addView(lab, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void refreshZoomRow() {
        zoomRow.removeAllViews();
        CameraCaps c = caps;
        if (c == null) return;
        List<Double> presets = c.lensChipRatios();
        if (presets.size() <= 1 && c.zoomRange == null && c.maxDigitalZoom <= 1.01f) {
            lensText.setVisibility(View.GONE);
            return;
        }
        lensText.setVisibility(View.VISIBLE);
        for (final Double z : presets) {
            TextView chip = UiKit.chip(this, ExposureMath.zoomLabel(z));
            UiKit.chipState(chip, Math.abs(cs.zoom - z) / z < 0.06);
            chip.setContentDescription("ซูม " + ExposureMath.zoomLabel(z));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cs.zoom = z.floatValue();
                    ctl.setZoom(cs.zoom);
                    refreshZoomRow();
                }
            });
            zoomRow.addView(chip, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36), UiKit.dp(this, 3), 0, UiKit.dp(this, 3), 0));
        }
    }

    private void refreshStoryRow() {
        storyRow.removeAllViews();
        if (mode != MODE_STORY) {
            storyRow.setVisibility(View.GONE);
            return;
        }
        storyRow.setVisibility(View.VISIBLE);
        TextView photo = UiKit.chip(this, "รูป Story");
        TextView video = UiKit.chip(this, "วิดีโอ Story");
        UiKit.chipState(photo, !storyVideo);
        UiKit.chipState(video, storyVideo);
        photo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!storyVideo || ctl.isRecording()) return;
                storyVideo = false;
                shutter.setKind(ShutterButton.PHOTO);
                refreshStoryRow();
                reconfigure(PerfLog.MODE_SWITCH);
            }
        });
        video.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (storyVideo) return;
                storyVideo = true;
                shutter.setKind(ShutterButton.VIDEO);
                refreshStoryRow();
                reconfigure(PerfLog.MODE_SWITCH);
            }
        });
        storyRow.addView(photo, UiKit.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36), 0, 0, UiKit.dp(this, 8), 0));
        storyRow.addView(video, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36)));
    }

    private void setupGestures() {
        scaleDet = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                CameraCaps c = caps;
                if (c == null) return false;
                float min = c.zoomRange != null ? c.zoomRange.getLower() : 1f;
                float max = c.zoomRange != null ? c.zoomRange.getUpper() : c.maxDigitalZoom;
                cs.zoom = Math.max(min, Math.min(max, cs.zoom * d.getScaleFactor()));
                ctl.setZoom(cs.zoom);
                overlay.setBanner(ExposureMath.zoomLabel(cs.zoom));
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                overlay.setBanner(null);
                refreshZoomRow();
            }
        });
        tapDet = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float nx = e.getX() / previewBox.getWidth(), ny = e.getY() / previewBox.getHeight();
                if (pro.getVisibility() == View.VISIBLE) pro.collapse();
                if (overlay.isLocked()) {
                    overlay.clearFocus();
                    ctl.clearLocks();
                    setStatus("ปลดล็อก AE/AF", 1200);
                    return true;
                }
                overlay.showFocus(nx, ny, false);
                ctl.focusAt(nx, ny, false);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                float nx = e.getX() / previewBox.getWidth(), ny = e.getY() / previewBox.getHeight();
                overlay.showFocus(nx, ny, true);
                ctl.focusAt(nx, ny, true);
                if (prefs.haptics()) previewBox.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        });
        previewBox.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                scaleDet.onTouchEvent(ev);
                if (!scaleDet.isInProgress()) tapDet.onTouchEvent(ev);
                return true;
            }
        });
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            ctl.setPreviewTexture(st);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            ctl.setPreviewTexture(null);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            if (!awaitFirstFrame) return;
            // When the app comes back, the TextureView can still show the last buffer of the previous
            // session (seen on the S25 Ultra: a "30 ms warm start" and a 36.8 s open→frame sample).
            // Only a frame exposed after this start/switch request counts. Camera timestamps share the
            // elapsedRealtime clock when the source is REALTIME; otherwise wait for the new session.
            long since = switchMetric != null ? switchStartNs : startCameraNs;
            boolean realtime = caps != null && caps.timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
            if (realtime ? st.getTimestamp() < since : !sessionReady) return;
            awaitFirstFrame = false;
            long now = SystemClock.elapsedRealtimeNanos();
            double sinceProcess = now / 1e6 - PerfLog.processStartElapsed();
            if (PerfLog.consumeColdStart() && sinceProcess < 10_000) {
                PerfLog.add(PerfLog.COLD_START, sinceProcess);
            } else if (warmStartNs > 0) {
                PerfLog.add(PerfLog.WARM_START, (now - warmStartNs) / 1e6);
            }
            warmStartNs = 0;
            if (switchMetric != null) {
                PerfLog.add(switchMetric, (now - switchStartNs) / 1e6);
                switchMetric = null;
            } else if (startCameraNs > 0) {
                PerfLog.add(PerfLog.OPEN_TO_FRAME, (now - startCameraNs) / 1e6);
            }
            startCameraNs = 0;
            overlay.setBanner(null);
        }
    };

    // =====================================================================================
    // Orientation, level, thermal, availability
    // =====================================================================================

    private final LevelSensor.Listener levelListener = new LevelSensor.Listener() {
        @Override
        public void onLevel(float rollDeg, boolean flat) {
            overlay.setLevel(rollDeg, flat);
        }
    };

    private void setupOrientation() {
        orientation = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int snapped = ((o + 45) / 90 * 90) % 360;
                int diff = Math.abs(o - deviceOrientation);
                diff = Math.min(diff, 360 - diff);
                if (snapped == deviceOrientation || diff < 55) return;
                deviceOrientation = snapped;
                ctl.setDeviceOrientation(snapped);
                for (View v : rotatables) {
                    float target = -snapped;
                    float cur = v.getRotation();
                    float delta = ((target - cur) % 360 + 540) % 360 - 180;
                    v.animate().rotation(cur + delta).setDuration(200).start();
                }
            }
        };
    }

    private void registerThermal() {
        if (pm == null) return;
        thermal = pm.getCurrentThermalStatus();
        thermalListener = new PowerManager.OnThermalStatusChangedListener() {
            @Override
            public void onThermalStatusChanged(int status) {
                thermal = status;
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    ctl.setAnalysisWanted(false, 500);
                    overlay.clearAnalysis();
                    setStatus("เครื่องร้อน: ปิดฮิสโตแกรม/พีคกิงชั่วคราวเพื่อรักษาความลื่นของกล้อง", 5000);
                } else if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                    ctl.setAnalysisWanted(activeSpec != null && activeSpec.analysis != null && overlaysWanted(), 250);
                    setStatus("เครื่องเริ่มอุ่น ลดความถี่การวิเคราะห์ภาพ", 3000);
                }
            }
        };
        pm.addThermalStatusListener(new Executor() {
            @Override
            public void execute(Runnable r) {
                h.post(r);
            }
        }, thermalListener);
    }

    private void unregisterThermal() {
        if (pm != null && thermalListener != null) pm.removeThermalStatusListener(thermalListener);
        thermalListener = null;
    }

    private void registerAvailability() {
        availability = new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(String id) {
                if (resumed && errorRetryable && messagePanel.getVisibility() == View.VISIBLE && id.equals(cameraId)) {
                    hideMessage();
                    startCamera();
                }
            }
        };
        ctl.manager().registerAvailabilityCallback(availability, h);
    }

    private void unregisterAvailability() {
        if (availability != null) ctl.manager().unregisterAvailabilityCallback(availability);
        availability = null;
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA)
                && prefs.volumeShutter()) {
            if (event.getRepeatCount() == 0) {
                long offset = SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * 1_000_000L;
                onShutterPressed(event.getEventTime() * 1_000_000L + offset, event.getEventTime(), "volume");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && prefs.volumeShutter()) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void openStory(Uri uri) {
        Intent i = new Intent(this, StoryActivity.class);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private void loadThumb(final Uri uri, final boolean video) {
        if (uri == null) return;
        App.get().background().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap b = getContentResolver().loadThumbnail(uri, new Size(192, 192), null);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            thumb.setImageBitmap(b);
                        }
                    });
                } catch (IOException | RuntimeException ignored) {
                }
            }
        });
    }

    private void setStatus(String s, int ms) {
        statusText.setText(s);
        statusText.setTextColor(UiKit.TEXT);
        if (statusReset != null) h.removeCallbacks(statusReset);
        statusReset = new Runnable() {
            @Override
            public void run() {
                statusReset = null;
                refreshVoiceHint();
            }
        };
        h.postDelayed(statusReset, ms);
    }

    private void showMessage(String text, String action, final Runnable run) {
        voiceUndoShowing = false;
        messageText.setText(text);
        messageBtn2.setVisibility(View.GONE);
        if (action == null) {
            messageBtn1.setVisibility(View.GONE);
        } else {
            messageBtn1.setVisibility(View.VISIBLE);
            messageBtn1.setText(action);
            messageBtn1.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (run != null) run.run();
                }
            });
        }
        messagePanel.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        messagePanel.setVisibility(View.GONE);
    }

    @SuppressWarnings("unused")
    private boolean accessibilityOn() {
        return Secure.getInt(getContentResolver(), Secure.ACCESSIBILITY_ENABLED, 0) == 1;
    }
}
