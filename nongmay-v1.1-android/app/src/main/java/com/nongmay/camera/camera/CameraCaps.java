package com.nongmay.camera.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;

import com.nongmay.camera.camera.pro.ExposureMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Snapshot of what ONE camera id actually exposes to third-party apps. Every Pro control is gated
 * on these values; nothing is assumed from Samsung's marketing specs.
 */
public final class CameraCaps {
    public final String id;
    public final CameraCharacteristics chars;
    public final int facing, sensorOrientation, hwLevel;
    public final int[] capabilities;
    public final boolean manualSensor, manualPost, raw, logicalMulti, burst, readSettings, tenBit, ultraHighRes;
    public final boolean zslKey, aeLockAvailable, awbLockAvailable, flash;
    public final Range<Integer> isoRange;
    public final Range<Long> expRange;
    public final long maxFrameNs;
    public final float minFocusDiopters;
    public final int focusCalibration;
    public final float[] apertures, focals;
    public final SizeF sensorSize;
    public final Rect active;
    public final int[] afModes, aeModes, awbModes, effects, edgeModes, nrModes, oisModes, stabModes;
    public final Range<Integer> evRange;
    public final Rational evStep;
    public final int maxAe, maxAf, maxAwb;
    public final Range<Float> zoomRange;
    public final float maxDigitalZoom;
    public final Range<Integer>[] fpsRanges;
    public final StreamConfigurationMap map;
    public final String[] physicalIds;
    public final double[] physicalEqFocal;
    public final int timestampSource;
    public final double eqFocal;
    public final double[] cm1, cm2, cc1, cc2, fm1, fm2;
    public final int illum1, illum2;

    public static final class VideoMode {
        public final Size size;
        public final int fps;
        public final boolean avc, hevc;

        VideoMode(Size size, int fps, boolean avc, boolean hevc) {
            this.size = size; this.fps = fps; this.avc = avc; this.hevc = hevc;
        }

        public String resLabel() {
            return resLabel(size);
        }

        public static String resLabel(Size s) {
            int h = Math.min(s.getWidth(), s.getHeight());
            if (h >= 4320) return "8K";
            if (h >= 2160) return "4K";
            return h + "p";
        }
    }

    @SuppressWarnings("unchecked")
    private CameraCaps(CameraManager mgr, String id, CameraCharacteristics c) {
        this.id = id;
        this.chars = c;
        facing = nz(c.get(CameraCharacteristics.LENS_FACING), CameraMetadata.LENS_FACING_BACK);
        sensorOrientation = nz(c.get(CameraCharacteristics.SENSOR_ORIENTATION), 90);
        hwLevel = nz(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL), CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        capabilities = caps == null ? new int[0] : caps;
        manualSensor = has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        manualPost = has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING);
        raw = has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW);
        logicalMulti = has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
        burst = has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE);
        readSettings = has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS);
        tenBit = Build.VERSION.SDK_INT >= 33 && has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT);
        ultraHighRes = Build.VERSION.SDK_INT >= 31 && has(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR);
        List<CaptureRequest.Key<?>> reqKeys = c.getAvailableCaptureRequestKeys();
        zslKey = reqKeys != null && reqKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL);
        aeLockAvailable = Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
        awbLockAvailable = Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
        flash = Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Long mfd = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        maxFrameNs = mfd == null ? 0 : mfd;
        Float mf = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        minFocusDiopters = mf == null ? 0 : mf;
        focusCalibration = nz(c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION), 0);
        float[] ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        apertures = ap == null ? new float[0] : ap;
        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        focals = fl == null ? new float[0] : fl;
        sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Rect a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        active = a == null ? new Rect(0, 0, 4000, 3000) : a;
        afModes = arr(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
        aeModes = arr(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES));
        awbModes = arr(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES));
        effects = arr(c.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS));
        edgeModes = arr(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES));
        nrModes = arr(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES));
        oisModes = arr(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
        stabModes = arr(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES));
        evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        maxAe = nz(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE), 0);
        maxAf = nz(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF), 0);
        maxAwb = nz(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB), 0);
        zoomRange = Build.VERSION.SDK_INT >= 30 ? c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
        Float mdz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        maxDigitalZoom = mdz == null ? 1f : mdz;
        Range<Integer>[] fr = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        fpsRanges = fr == null ? (Range<Integer>[]) new Range[0] : fr;
        map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        timestampSource = nz(c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE), 0);
        eqFocal = focals.length > 0 && sensorSize != null
                ? ExposureMath.equivalentFocal(focals[0], sensorSize.getWidth(), sensorSize.getHeight()) : Double.NaN;

        Set<String> phys = c.getPhysicalCameraIds();
        List<String> ids = new ArrayList<>(phys);
        Collections.sort(ids);
        physicalIds = ids.toArray(new String[0]);
        physicalEqFocal = new double[physicalIds.length];
        for (int i = 0; i < physicalIds.length; i++) {
            physicalEqFocal[i] = Double.NaN;
            try {
                CameraCharacteristics pc = mgr.getCameraCharacteristics(physicalIds[i]);
                float[] pf = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF ps = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (pf != null && pf.length > 0 && ps != null) {
                    physicalEqFocal[i] = ExposureMath.equivalentFocal(pf[0], ps.getWidth(), ps.getHeight());
                }
            } catch (CameraAccessException | IllegalArgumentException ignored) {
            }
        }
        cm1 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        cm2 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        cc1 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        cc2 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
        fm1 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        fm2 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        illum1 = nz(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1), 0);
        Byte i2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        illum2 = i2 == null ? 0 : i2;
    }

    public static CameraCaps load(CameraManager mgr, String id) throws CameraAccessException {
        return new CameraCaps(mgr, id, mgr.getCameraCharacteristics(id));
    }

    // ---- helpers ------------------------------------------------------------------------
    static int nz(Integer v, int def) {
        return v == null ? def : v;
    }

    static int[] arr(int[] a) {
        return a == null ? new int[0] : a;
    }

    public static boolean has(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    static double[] matrix(ColorSpaceTransform t) {
        if (t == null) return null;
        Rational[] r = new Rational[9];
        t.copyElements(r, 0);
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = r[i].doubleValue();
        return m;
    }

    public boolean isFront() {
        return facing == CameraMetadata.LENS_FACING_FRONT;
    }

    public boolean manualFocus() {
        return manualSensor && minFocusDiopters > 0;
    }

    public boolean kelvinCapable() {
        return manualPost && cm1 != null && has(awbModes, CameraMetadata.CONTROL_AWB_MODE_OFF);
    }

    public double evStepSize() {
        return evStep == null ? 0 : evStep.doubleValue();
    }

    public String levelName() {
        switch (hwLevel) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return "UNKNOWN(" + hwLevel + ")";
        }
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(isFront() ? "หน้า" : "หลัง");
        if (!Double.isNaN(eqFocal)) sb.append(" · ").append(Math.round(eqFocal)).append("mm");
        if (apertures.length > 0) sb.append(String.format(Locale.ROOT, " · f/%.1f", apertures[0]));
        return sb.toString();
    }

    static boolean aspectMatch(Size s, double aspect) {
        double a = (double) Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight());
        return Math.abs(a - aspect) / aspect < 0.015;
    }

    static final Comparator<Size> BY_AREA = new Comparator<Size>() {
        @Override
        public int compare(Size a, Size b) {
            return Long.compare((long) a.getWidth() * a.getHeight(), (long) b.getWidth() * b.getHeight());
        }
    };

    /** Largest JPEG size with the given long:short aspect (e.g. 4/3), or null if none. */
    public Size jpegSize(double aspect) {
        if (map == null) return null;
        Size best = null;
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null) return null;
        for (Size s : sizes) if (aspectMatch(s, aspect) && (best == null || BY_AREA.compare(s, best) > 0)) best = s;
        return best;
    }

    public Size largestJpeg() {
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return null;
        return Collections.max(Arrays.asList(sizes), BY_AREA);
    }

    public Size previewSize(double aspect) {
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null) return new Size(1920, 1080);
        Size best = null;
        for (Size s : sizes) {
            if (!aspectMatch(s, aspect)) continue;
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            if (best == null || BY_AREA.compare(s, best) > 0) best = s;
        }
        if (best == null) {
            for (Size s : sizes) if (s.getWidth() <= 1920 && s.getHeight() <= 1440 && (best == null || BY_AREA.compare(s, best) > 0)) best = s;
        }
        return best == null ? sizes[0] : best;
    }

    /** Small YUV stream (~640 px wide) for histogram / zebra / peaking. */
    public Size analysisSize(double aspect) {
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null) return null;
        Size best = null;
        for (Size s : sizes) {
            if (!aspectMatch(s, aspect) || s.getWidth() < 480 || s.getWidth() > 960) continue;
            if (best == null || BY_AREA.compare(s, best) < 0) best = s;
        }
        return best;
    }

    public Size rawSize() {
        if (!raw || map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if (sizes == null || sizes.length == 0) return null;
        return Collections.max(Arrays.asList(sizes), BY_AREA);
    }

    public Range<Integer> fixedFps(int fps) {
        for (Range<Integer> r : fpsRanges) if (r.getLower() == fps && r.getUpper() == fps) return r;
        return null;
    }

    /** Best AE range for preview/stills: highest upper bound, widest range (lets AE slow down in the dark). */
    public Range<Integer> photoFpsRange() {
        Range<Integer> best = null;
        for (Range<Integer> r : fpsRanges) {
            if (r.getUpper() > 30) continue;
            if (best == null || r.getUpper() > best.getUpper()
                    || (r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower())) best = r;
        }
        return best;
    }

    /** Capability-checked (size, fps) combinations for MediaRecorder + an available encoder. */
    public List<VideoMode> videoModes() {
        List<VideoMode> out = new ArrayList<>();
        if (map == null) return out;
        Size[] rec = map.getOutputSizes(MediaRecorder.class);
        if (rec == null) return out;
        int[][] std = {{7680, 4320}, {3840, 2160}, {1920, 1080}, {1280, 720}};
        int[] fpsList = {24, 25, 30, 50, 60};
        for (int[] wh : std) {
            Size size = null;
            for (Size s : rec) if (s.getWidth() == wh[0] && s.getHeight() == wh[1]) size = s;
            if (size == null) continue;
            long minFrame = map.getOutputMinFrameDuration(MediaRecorder.class, size);
            for (int fps : fpsList) {
                if (minFrame > 0 && minFrame > 1e9 / fps * 1.01) continue;
                if (fixedFps(fps) == null) continue;
                boolean avc = EncoderCaps.supports("video/avc", size.getWidth(), size.getHeight(), fps);
                boolean hevc = EncoderCaps.supports("video/hevc", size.getWidth(), size.getHeight(), fps);
                if (avc || hevc) out.add(new VideoMode(size, fps, avc, hevc));
            }
        }
        return out;
    }

    /** Zoom presets that correspond to real physical lenses under this logical camera (ratio vs this id's own focal). */
    public List<Double> lensZoomPresets() {
        List<Double> out = new ArrayList<>();
        out.add(1.0);
        if (zoomRange == null || Double.isNaN(eqFocal)) return out;
        for (double f : physicalEqFocal) {
            if (Double.isNaN(f)) continue;
            double r = f / eqFocal;
            if (r < zoomRange.getLower() * 0.97 || r > zoomRange.getUpper() * 1.03) continue;
            boolean dup = false;
            for (double o : out) if (Math.abs(o - r) / r < 0.12) dup = true;
            if (!dup) out.add(Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), r)));
        }
        Collections.sort(out);
        return out;
    }

    public String physicalLabel(String physicalId) {
        for (int i = 0; i < physicalIds.length; i++) {
            if (physicalIds[i].equals(physicalId)) {
                double f = physicalEqFocal[i];
                return "ID " + physicalId + (Double.isNaN(f) ? "" : " ≈" + Math.round(f) + "mm");
            }
        }
        return "ID " + physicalId;
    }
}
