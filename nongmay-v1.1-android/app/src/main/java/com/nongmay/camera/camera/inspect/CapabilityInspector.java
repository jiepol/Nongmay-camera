package com.nongmay.camera.camera.inspect;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.PowerManager;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Range;
import android.util.Size;

import com.nongmay.camera.BuildInfo;
import com.nongmay.camera.camera.CameraCaps;
import com.nongmay.camera.camera.EncoderCaps;
import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.util.Json;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Generates DEVICE_CAPABILITIES (JSON + Markdown) from what THIS phone actually exposes to a
 * third-party app. Contains no photos, audio, location, accounts or serial numbers.
 */
public final class CapabilityInspector {
    private CapabilityInspector() {}

    static final String[] CAPS = {"BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW", "PRIVATE_REPROCESSING",
            "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT", "CONSTRAINED_HIGH_SPEED_VIDEO",
            "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA", "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING",
            "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT", "STREAM_USE_CASE", "COLOR_SPACE_PROFILES"};
    static final String[] AF = {"OFF", "AUTO", "MACRO", "CONTINUOUS_VIDEO", "CONTINUOUS_PICTURE", "EDOF"};
    static final String[] AE = {"OFF", "ON", "ON_AUTO_FLASH", "ON_ALWAYS_FLASH", "ON_AUTO_FLASH_REDEYE", "ON_EXTERNAL_FLASH", "ON_LOW_LIGHT_BOOST"};
    static final String[] AWB = {"OFF", "AUTO", "INCANDESCENT", "FLUORESCENT", "WARM_FLUORESCENT", "DAYLIGHT", "CLOUDY_DAYLIGHT", "TWILIGHT", "SHADE"};
    static final String[] STAB = {"OFF", "ON", "PREVIEW_STABILIZATION"};
    static final String[] EXT = {"AUTOMATIC", "FACE_RETOUCH", "BOKEH", "HDR", "NIGHT"};

    static List<String> names(int[] v, String[] table) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        for (int x : v) out.add(x >= 0 && x < table.length ? table[x] : "#" + x);
        return out;
    }

    static List<String> sizes(Size[] s, int max) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        Size[] c = s.clone();
        Arrays.sort(c, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight());
            }
        });
        for (int i = 0; i < c.length && i < max; i++) out.add(c[i].getWidth() + "x" + c[i].getHeight());
        return out;
    }

    public static Map<String, Object> collect(Context ctx) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("report", "NongMay Camera DEVICE_CAPABILITIES");
        root.put("schema", 1);
        root.put("app_version", BuildInfo.VERSION);
        root.put("generated_at", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()));
        Map<String, Object> dev = new LinkedHashMap<>();
        dev.put("manufacturer", Build.MANUFACTURER);
        dev.put("model", Build.MODEL);
        dev.put("device", Build.DEVICE);
        dev.put("android", Build.VERSION.RELEASE);
        dev.put("sdk", Build.VERSION.SDK_INT);
        dev.put("security_patch", Build.VERSION.SECURITY_PATCH);
        dev.put("build_display", Build.DISPLAY);
        if (Build.VERSION.SDK_INT >= 31) {
            dev.put("soc", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
            dev.put("media_performance_class", Build.VERSION.MEDIA_PERFORMANCE_CLASS);
        }
        root.put("device", dev);
        CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        List<Object> cams = new ArrayList<>();
        try {
            for (String id : mgr.getCameraIdList()) cams.add(camera(mgr, id));
            if (Build.VERSION.SDK_INT >= 30) {
                List<String> combos = new ArrayList<>();
                for (Set<String> s : mgr.getConcurrentCameraIds()) combos.add(s.toString());
                root.put("concurrent_camera_id_sets", combos);
            }
        } catch (CameraAccessException | RuntimeException e) {
            root.put("camera_error", e.toString());
        }
        root.put("cameras", cams);
        root.put("encoders", EncoderCaps.describe());
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null) root.put("thermal_status_now", pm.getCurrentThermalStatus());
        root.put("free_storage_bytes", MediaStoreUtil.freeBytes());
        root.put("privacy", "No photos, audio, location, accounts or hardware serials are included.");
        return root;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> camera(CameraManager mgr, String id) throws CameraAccessException {
        CameraCaps c = CameraCaps.load(mgr, id);
        CameraCharacteristics ch = c.chars;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("facing", c.isFront() ? "FRONT" : "BACK/EXTERNAL");
        m.put("hardware_level", c.levelName());
        m.put("capabilities", names(c.capabilities, CAPS));
        m.put("sensor_orientation", c.sensorOrientation);
        m.put("active_array", c.active.width() + "x" + c.active.height());
        if (c.sensorSize != null) m.put("sensor_physical_mm", c.sensorSize.getWidth() + "x" + c.sensorSize.getHeight());
        m.put("focal_lengths_mm", c.focals);
        m.put("equivalent_focal_35mm", Double.isNaN(c.eqFocal) ? null : Math.round(c.eqFocal * 10) / 10.0);
        m.put("apertures", c.apertures);
        m.put("aperture_adjustable", c.apertures.length > 1);
        m.put("ois_modes", names(c.oisModes, new String[]{"OFF", "ON"}));
        m.put("min_focus_diopters", c.minFocusDiopters);
        m.put("focus_calibration", new String[]{"UNCALIBRATED", "APPROXIMATE", "CALIBRATED"}[Math.max(0, Math.min(2, c.focusCalibration))]);
        m.put("iso_range", c.isoRange == null ? null : c.isoRange.toString());
        m.put("exposure_ns_range", c.expRange == null ? null : c.expRange.toString());
        m.put("max_frame_duration_ns", c.maxFrameNs);
        m.put("af_modes", names(c.afModes, AF));
        m.put("ae_modes", names(c.aeModes, AE));
        m.put("awb_modes", names(c.awbModes, AWB));
        m.put("max_regions_ae_af_awb", c.maxAe + "/" + c.maxAf + "/" + c.maxAwb);
        m.put("ev_range_steps", c.evRange == null ? null : c.evRange.toString());
        m.put("ev_step", c.evStep == null ? null : c.evStep.toString());
        m.put("ae_lock", c.aeLockAvailable);
        m.put("awb_lock", c.awbLockAvailable);
        m.put("zoom_ratio_range", c.zoomRange == null ? null : c.zoomRange.toString());
        m.put("max_digital_zoom", c.maxDigitalZoom);
        List<String> fps = new ArrayList<>();
        for (Range<Integer> r : c.fpsRanges) fps.add(r.toString());
        m.put("ae_fps_ranges", fps);
        m.put("flash", c.flash);
        m.put("timestamp_source", c.timestampSource == 1 ? "REALTIME" : "UNKNOWN");
        m.put("video_stabilization_modes", names(c.stabModes, STAB));
        m.put("zsl_request_key", c.zslKey);
        m.put("colour_calibration", c.cm1 != null ? (c.fm1 != null ? "colour+forward matrices" : "colour matrix only") : "none");
        m.put("kelvin_wb_possible", c.kelvinCapable());
        int[] faces = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        m.put("face_detect_modes", faces == null ? new int[0] : faces);
        StreamConfigurationMap map = c.map;
        if (map != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("jpeg", sizes(map.getOutputSizes(ImageFormat.JPEG), 8));
            s.put("yuv_420_888", sizes(map.getOutputSizes(ImageFormat.YUV_420_888), 8));
            s.put("raw_sensor", sizes(map.getOutputSizes(ImageFormat.RAW_SENSOR), 4));
            s.put("private_preview", sizes(map.getOutputSizes(SurfaceTexture.class), 6));
            s.put("media_recorder", sizes(map.getOutputSizes(MediaRecorder.class), 8));
            List<String> hs = new ArrayList<>();
            Size[] hsSizes = map.getHighSpeedVideoSizes();
            if (hsSizes != null) {
                for (Size z : hsSizes) {
                    List<String> fr = new ArrayList<>();
                    for (Range<Integer> r : map.getHighSpeedVideoFpsRangesFor(z)) fr.add(r.toString());
                    hs.add(z.getWidth() + "x" + z.getHeight() + " " + fr);
                }
            }
            s.put("high_speed_video", hs);
            m.put("stream_sizes_top", s);
        }
        if (Build.VERSION.SDK_INT >= 31 && c.ultraHighRes) {
            StreamConfigurationMap max = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            if (max != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("jpeg", sizes(max.getOutputSizes(ImageFormat.JPEG), 3));
                s.put("raw_sensor", sizes(max.getOutputSizes(ImageFormat.RAW_SENSOR), 3));
                s.put("yuv_420_888", sizes(max.getOutputSizes(ImageFormat.YUV_420_888), 3));
                m.put("max_resolution_mode_sizes", s);
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            DynamicRangeProfiles drp = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
            if (drp != null) {
                List<String> p = new ArrayList<>();
                for (Long v : drp.getSupportedProfiles()) p.add(profileName(v));
                Collections.sort(p);
                m.put("dynamic_range_profiles", p);
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                CameraExtensionCharacteristics ec = mgr.getCameraExtensionCharacteristics(id);
                List<Integer> ext = ec.getSupportedExtensions();
                List<String> en = new ArrayList<>();
                for (int e : ext) en.add(e >= 0 && e < EXT.length ? EXT[e] : "#" + e);
                m.put("camera_extensions", en);
            } catch (RuntimeException e) {
                m.put("camera_extensions", "error: " + e.getClass().getSimpleName());
            }
        }
        List<Object> phys = new ArrayList<>();
        for (int i = 0; i < c.physicalIds.length; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", c.physicalIds[i]);
            p.put("equivalent_focal_35mm", Double.isNaN(c.physicalEqFocal[i]) ? null : Math.round(c.physicalEqFocal[i] * 10) / 10.0);
            phys.add(p);
        }
        m.put("physical_cameras", phys);
        m.put("zoom_presets_backed_by_physical_lenses", c.lensZoomPresets());
        List<String> vm = new ArrayList<>();
        for (CameraCaps.VideoMode v : c.videoModes()) vm.add(v.resLabel() + "@" + v.fps + (v.avc ? " avc" : "") + (v.hevc ? " hevc" : ""));
        m.put("video_modes_usable_by_this_app", vm);
        return m;
    }

    static String profileName(long v) {
        if (v == DynamicRangeProfiles.STANDARD) return "STANDARD";
        if (v == DynamicRangeProfiles.HLG10) return "HLG10";
        if (v == DynamicRangeProfiles.HDR10) return "HDR10";
        if (v == DynamicRangeProfiles.HDR10_PLUS) return "HDR10_PLUS";
        if (v == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF) return "DOLBY_VISION_10B_HDR_REF";
        if (v == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM) return "DOLBY_VISION_10B_HDR_OEM";
        if (v == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF) return "DOLBY_VISION_8B_HDR_REF";
        if (v == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM) return "DOLBY_VISION_8B_HDR_OEM";
        return "0x" + Long.toHexString(v);
    }

    public interface SpeechCallback {
        void onResult(Map<String, Object> result);
    }

    /** Probes on-device speech recognition and Thai support (for future engines). Main thread only. */
    public static void checkSpeech(Context ctx, final SpeechCallback cb) {
        final Map<String, Object> r = new LinkedHashMap<>();
        boolean available = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
        r.put("on_device_recognition_available", available);
        r.put("used_by_voice_shutter", false);
        r.put("note", "Voice Shutter uses its own offline MFCC+DTW matcher; this only records what the OS offers.");
        if (!available || Build.VERSION.SDK_INT < 33) {
            cb.onResult(r);
            return;
        }
        try {
            final SpeechRecognizer sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH");
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            sr.checkRecognitionSupport(intent, new Executor() {
                @Override
                public void execute(Runnable command) {
                    main.post(command);
                }
            }, new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport s) {
                    r.put("installed_on_device", s.getInstalledOnDeviceLanguages());
                    r.put("supported_on_device", s.getSupportedOnDeviceLanguages());
                    r.put("pending_on_device", s.getPendingOnDeviceLanguages());
                    boolean th = false;
                    for (String l : s.getInstalledOnDeviceLanguages()) if (l.toLowerCase(Locale.ROOT).startsWith("th")) th = true;
                    r.put("thai_installed_on_device", th);
                    sr.destroy();
                    cb.onResult(r);
                }

                @Override
                public void onError(int error) {
                    r.put("check_error", error);
                    sr.destroy();
                    cb.onResult(r);
                }
            });
        } catch (RuntimeException e) {
            r.put("check_error", e.getClass().getSimpleName());
            cb.onResult(r);
        }
    }

    @SuppressWarnings("unchecked")
    public static String markdown(Map<String, Object> d) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> dev = (Map<String, Object>) d.get("device");
        sb.append("# DEVICE_CAPABILITIES — ").append(dev.get("manufacturer")).append(' ').append(dev.get("model")).append("\n\n");
        sb.append("- Android ").append(dev.get("android")).append(" (API ").append(dev.get("sdk")).append("), build ").append(dev.get("build_display")).append('\n');
        sb.append("- App ").append(d.get("app_version")).append(" · generated ").append(d.get("generated_at")).append('\n');
        sb.append("- Source: live Camera2 / MediaCodecList queries on this phone (what a third-party app can use, not Samsung's stock-camera specs)\n\n");
        List<Object> cams = (List<Object>) d.get("cameras");
        if (cams != null) {
            sb.append("| id | facing | level | eq mm | f/ | ISO | shutter ns | manual | RAW | 10-bit | zoom | video modes |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (Object o : cams) {
                Map<String, Object> m = (Map<String, Object>) o;
                List<String> caps = (List<String>) m.get("capabilities");
                sb.append("| ").append(m.get("id")).append(" | ").append(m.get("facing")).append(" | ").append(m.get("hardware_level"))
                        .append(" | ").append(m.get("equivalent_focal_35mm")).append(" | ").append(Json.write(m.get("apertures")))
                        .append(" | ").append(m.get("iso_range")).append(" | ").append(m.get("exposure_ns_range"))
                        .append(" | ").append(caps.contains("MANUAL_SENSOR") ? "yes" : "no")
                        .append(" | ").append(caps.contains("RAW") ? "yes" : "no")
                        .append(" | ").append(caps.contains("DYNAMIC_RANGE_TEN_BIT") ? "yes" : "no")
                        .append(" | ").append(m.get("zoom_ratio_range"))
                        .append(" | ").append(Json.write(m.get("video_modes_usable_by_this_app")).replace("|", "/")).append(" |\n");
            }
        }
        sb.append("\nFull machine-readable data:\n\n```json\n").append(Json.pretty(d)).append("```\n");
        return sb.toString();
    }
}
