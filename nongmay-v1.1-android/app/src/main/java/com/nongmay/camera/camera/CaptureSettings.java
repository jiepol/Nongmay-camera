package com.nongmay.camera.camera;

import android.hardware.camera2.CameraMetadata;

import org.json.JSONException;
import org.json.JSONObject;

/** User-facing capture state. The controller maps it onto Camera2 requests, gated by CameraCaps. */
public final class CaptureSettings {
    public static final int FOCUS_CONTINUOUS = 0, FOCUS_SINGLE = 1, FOCUS_MANUAL = 2;
    public static final int WB_AUTO = 0, WB_PRESET = 1, WB_KELVIN = 2;
    public static final int METER_MATRIX = 0, METER_CENTER = 1, METER_SPOT = 2;
    public static final int LOOK_STANDARD = 0, LOOK_NATURAL = 1, LOOK_MONO = 2;
    public static final int FLASH_OFF = 0, FLASH_AUTO = 1, FLASH_ON = 2, FLASH_TORCH = 3;

    public boolean manualExposure;
    public int iso = 100;
    public long exposureNs = 10_000_000L;
    public int evSteps;
    public boolean aeLock;

    public int focusMode = FOCUS_CONTINUOUS;
    public float focusDiopters;
    public boolean afLocked;

    public int wbMode = WB_AUTO;
    public int awbPreset = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT;
    public int kelvin = 5200;
    public int tint;
    public boolean awbLock;

    public int metering = METER_MATRIX;
    public float pointX = 0.5f, pointY = 0.5f; // normalised preview (portrait display) coordinates
    public boolean hasPoint;

    public int look = LOOK_STANDARD;
    public boolean ois = true;
    public int videoStab = 1;
    public float aperture;
    public int flash = FLASH_OFF;
    public float zoom = 1f;
    public boolean zsl = true;
    public int jpegQuality = 95;

    public CaptureSettings copy() {
        CaptureSettings c = new CaptureSettings();
        c.manualExposure = manualExposure; c.iso = iso; c.exposureNs = exposureNs; c.evSteps = evSteps; c.aeLock = aeLock;
        c.focusMode = focusMode; c.focusDiopters = focusDiopters; c.afLocked = afLocked;
        c.wbMode = wbMode; c.awbPreset = awbPreset; c.kelvin = kelvin; c.tint = tint; c.awbLock = awbLock;
        c.metering = metering; c.pointX = pointX; c.pointY = pointY; c.hasPoint = hasPoint;
        c.look = look; c.ois = ois; c.videoStab = videoStab; c.aperture = aperture; c.flash = flash; c.zoom = zoom;
        c.zsl = zsl; c.jpegQuality = jpegQuality;
        return c;
    }

    /** Pro values worth restoring per lens and mode (not transient things like tap points or locks). */
    public JSONObject toPreset() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1);
            o.put("manualExposure", manualExposure);
            o.put("iso", iso);
            o.put("exposureNs", exposureNs);
            o.put("evSteps", evSteps);
            o.put("focusMode", focusMode);
            o.put("focusDiopters", (double) focusDiopters);
            o.put("wbMode", wbMode);
            o.put("awbPreset", awbPreset);
            o.put("kelvin", kelvin);
            o.put("tint", tint);
            o.put("metering", metering);
            o.put("look", look);
            o.put("ois", ois);
            o.put("videoStab", videoStab);
            o.put("aperture", (double) aperture);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public void applyPreset(JSONObject o) {
        if (o == null) return;
        manualExposure = o.optBoolean("manualExposure", manualExposure);
        iso = o.optInt("iso", iso);
        exposureNs = o.optLong("exposureNs", exposureNs);
        evSteps = o.optInt("evSteps", evSteps);
        focusMode = o.optInt("focusMode", focusMode);
        focusDiopters = (float) o.optDouble("focusDiopters", focusDiopters);
        wbMode = o.optInt("wbMode", wbMode);
        awbPreset = o.optInt("awbPreset", awbPreset);
        kelvin = o.optInt("kelvin", kelvin);
        tint = o.optInt("tint", tint);
        metering = o.optInt("metering", metering);
        look = o.optInt("look", look);
        ois = o.optBoolean("ois", ois);
        videoStab = o.optInt("videoStab", videoStab);
        aperture = (float) o.optDouble("aperture", aperture);
    }

    /** Back to full auto (keeps zoom, flash and look). */
    public void resetToAuto() {
        manualExposure = false;
        evSteps = 0;
        aeLock = false;
        focusMode = FOCUS_CONTINUOUS;
        afLocked = false;
        wbMode = WB_AUTO;
        awbLock = false;
        metering = METER_MATRIX;
        hasPoint = false;
    }
}
