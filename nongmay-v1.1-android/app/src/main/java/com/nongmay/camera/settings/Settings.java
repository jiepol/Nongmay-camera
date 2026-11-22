package com.nongmay.camera.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Versioned settings (SharedPreferences, app-private). Every default here matches the table in
 * the v1.1 spec: Auto on the last usable rear 1× lens, voice OFF until enabled, Story 1080×1920 SDR.
 */
public final class Settings {
    public static final int SCHEMA = 1;
    private final SharedPreferences p;

    public Settings(Context ctx) {
        p = ctx.getSharedPreferences("nongmay_settings", Context.MODE_PRIVATE);
        migrate();
    }

    private void migrate() {
        int v = p.getInt("schema", 0);
        if (v == SCHEMA) return;
        SharedPreferences.Editor e = p.edit();
        // v0 -> v1: nothing to convert yet (fresh install); keep the hook for future versions.
        e.putInt("schema", SCHEMA).apply();
    }

    public SharedPreferences prefs() {
        return p;
    }

    public boolean getBool(String k, boolean def) { return p.getBoolean(k, def); }
    public int getInt(String k, int def) { return p.getInt(k, def); }
    public String getString(String k, String def) { return p.getString(k, def); }
    public void put(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }
    public void put(String k, int v) { p.edit().putInt(k, v).apply(); }
    public void put(String k, String v) { p.edit().putString(k, v).apply(); }

    // ---- keys and defaults -------------------------------------------------------------
    public static final String START_MODE = "start_mode";          // "last" | "auto"
    public static final String LAST_MODE = "last_mode";            // int camera mode
    public static final String LAST_CAMERA = "last_camera";
    public static final String LEFT_HANDED = "left_handed";
    public static final String GRID = "grid";                      // 0 off, 1 thirds, 2 golden, 3 crosshair
    public static final String LEVEL = "level";
    public static final String HISTOGRAM = "histogram";
    public static final String ZEBRA = "zebra";
    public static final String ZEBRA_LEVEL = "zebra_level";        // percent of full scale
    public static final String PEAKING = "peaking";
    public static final String SHUTTER_SOUND = "shutter_sound";
    public static final String VOLUME_SHUTTER = "volume_shutter";
    public static final String HAPTICS = "haptics";
    public static final String ZSL = "zsl";
    public static final String FOCUS_BEFORE_CAPTURE = "focus_before_capture";
    public static final String JPEG_QUALITY = "jpeg_quality";
    public static final String PHOTO_ASPECT = "photo_aspect";      // "4:3" "16:9" "1:1" "3:2"
    public static final String RAW = "raw";
    public static final String MIRROR_SELFIE = "mirror_selfie";
    public static final String TIMER = "timer_s";
    public static final String FLASH = "flash";
    public static final String VOICE_ENABLED = "voice_enabled";
    public static final String VOICE_PROFILE = "voice_profile";
    public static final String VOICE_COUNTDOWN = "voice_countdown_s";
    public static final String VIDEO_RES = "video_res";            // "720p" "1080p" "2160p" "4320p"
    public static final String VIDEO_FPS = "video_fps";
    public static final String VIDEO_CODEC = "video_codec";        // "avc" | "hevc"
    public static final String VIDEO_BITRATE = "video_bitrate_mbps"; // 0 = auto
    public static final String VIDEO_AUDIO = "video_audio";
    public static final String VIDEO_STAB = "video_stab";          // 0 off, 1 on, 2 preview stabilisation
    public static final String STORY_AUTO_OPEN = "story_auto_open";
    public static final String STORY_PHOTO_PROFILE = "story_photo_profile";
    public static final String STORY_VIDEO_PROFILE = "story_video_profile";
    public static final String STORY_SAFE_ZONES = "story_safe_zones";
    public static final String EXPORT_KEEP_EXIF = "export_keep_exif";
    public static final String TOUCH_DOWN_SHUTTER = "touch_down_shutter";

    public boolean leftHanded() { return getBool(LEFT_HANDED, false); }
    public boolean shutterSound() { return getBool(SHUTTER_SOUND, true); }
    public boolean volumeShutter() { return getBool(VOLUME_SHUTTER, true); }
    public boolean haptics() { return getBool(HAPTICS, true); }
    public boolean zsl() { return getBool(ZSL, true); }
    public int jpegQuality() { return getInt(JPEG_QUALITY, 95); }
    public boolean voiceEnabled() { return getBool(VOICE_ENABLED, false); }
    public int voiceCountdown() { return getInt(VOICE_COUNTDOWN, 0); }
    public boolean touchDownShutter() { return getBool(TOUCH_DOWN_SHUTTER, true); }
}
