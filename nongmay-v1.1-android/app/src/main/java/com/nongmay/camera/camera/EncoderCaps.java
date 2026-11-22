package com.nongmay.camera.camera;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Range;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Queries the real encoders on the phone (MediaCodecList), cached once per process. */
public final class EncoderCaps {
    private static MediaCodecInfo[] infos;

    private EncoderCaps() {}

    private static synchronized MediaCodecInfo[] all() {
        if (infos == null) infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        return infos;
    }

    public static boolean supports(String mime, int w, int h, double fps) {
        return find(mime, w, h, fps) != null;
    }

    /** Encoder for mime that accepts w×h@fps in either orientation, hardware preferred. */
    public static MediaCodecInfo find(String mime, int w, int h, double fps) {
        MediaCodecInfo soft = null;
        for (MediaCodecInfo info : all()) {
            if (!info.isEncoder()) continue;
            boolean match = false;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) match = true;
            if (!match) continue;
            try {
                MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(mime).getVideoCapabilities();
                if (vc == null) continue;
                boolean ok = vc.areSizeAndRateSupported(w, h, fps) || vc.areSizeAndRateSupported(h, w, fps);
                if (!ok) continue;
                if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated()) {
                    if (soft == null) soft = info;
                    continue;
                }
                return info;
            } catch (RuntimeException ignored) {
            }
        }
        return soft;
    }

    /** True if this encoder accepts exactly w×h@fps (no orientation swap). */
    public static boolean exactSize(MediaCodecInfo info, String mime, int w, int h, double fps) {
        try {
            MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(mime).getVideoCapabilities();
            return vc != null && vc.areSizeAndRateSupported(w, h, fps);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean vbrSupported(MediaCodecInfo info, String mime) {
        try {
            MediaCodecInfo.EncoderCapabilities ec = info.getCapabilitiesForType(mime).getEncoderCapabilities();
            return ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean profileSupported(MediaCodecInfo info, String mime, int profile) {
        try {
            for (MediaCodecInfo.CodecProfileLevel pl : info.getCapabilitiesForType(mime).profileLevels) {
                if (pl.profile == profile) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Report rows for DEVICE_CAPABILITIES. */
    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaCodecInfo info : all()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) {
                if (!t.startsWith("video/") && !t.equals("audio/mp4a-latm")) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", info.getName());
                m.put("mime", t);
                if (Build.VERSION.SDK_INT >= 29) {
                    m.put("hardware", info.isHardwareAccelerated());
                    m.put("software_only", info.isSoftwareOnly());
                }
                try {
                    MediaCodecInfo.CodecCapabilities cc = info.getCapabilitiesForType(t);
                    MediaCodecInfo.VideoCapabilities vc = cc.getVideoCapabilities();
                    if (vc != null) {
                        m.put("max_width", vc.getSupportedWidths().getUpper());
                        m.put("max_height", vc.getSupportedHeights().getUpper());
                        Range<Integer> br = vc.getBitrateRange();
                        m.put("bitrate_max_bps", br.getUpper());
                        m.put("1080x1920@30", vc.areSizeAndRateSupported(1080, 1920, 30));
                        m.put("1440x2560@30", vc.areSizeAndRateSupported(1440, 2560, 30));
                        m.put("3840x2160@60", vc.areSizeAndRateSupported(3840, 2160, 60));
                        m.put("7680x4320@30", vc.areSizeAndRateSupported(7680, 4320, 30));
                    }
                    List<String> profiles = new ArrayList<>();
                    for (MediaCodecInfo.CodecProfileLevel pl : cc.profileLevels) profiles.add(pl.profile + "/" + pl.level);
                    m.put("profile_levels", profiles.size() > 24 ? profiles.subList(0, 24) : profiles);
                } catch (RuntimeException e) {
                    m.put("error", e.getClass().getSimpleName());
                }
                out.add(m);
            }
        }
        return out;
    }
}
