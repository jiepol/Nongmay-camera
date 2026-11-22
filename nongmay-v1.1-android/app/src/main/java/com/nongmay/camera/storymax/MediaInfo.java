package com.nongmay.camera.storymax;

import android.net.Uri;

import com.nongmay.camera.util.Fmt;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** What StoryMax learned about one source file (probe) or one exported file (validation). */
public final class MediaInfo {
    public Uri uri;
    public String name = "media";
    public String mime = "";
    public long sizeBytes = -1;
    public boolean video;

    // image
    public int rawW, rawH;
    public int exifOrientation = 1;
    public String colorSpace = "?";
    public boolean wideGamut;
    public boolean hasGps;
    public boolean hasGainmap;
    public boolean hasIcc;
    public String camera;

    // video
    public long durationUs = -1;
    public int rotation;
    public String videoMime;
    public int videoW, videoH;
    public int profile = -1, level = -1;
    public double fps = Double.NaN, fpsMin = Double.NaN, fpsMax = Double.NaN;
    public boolean vfr;
    public long bitrate = -1;
    public int colorStandard = -1, colorTransfer = -1, colorRange = -1;
    public boolean hdr;
    public int videoTrack = -1;
    public int frames = -1;

    public boolean hasAudio;
    public int audioTrack = -1;
    public String audioMime;
    public int sampleRate, channels;
    public long audioBitrate = -1;

    public int orientedW() {
        if (video) return (rotation % 180 == 0) ? videoW : videoH;
        return CropMath.swapsAxes(exifOrientation) ? rawH : rawW;
    }

    public int orientedH() {
        if (video) return (rotation % 180 == 0) ? videoH : videoW;
        return CropMath.swapsAxes(exifOrientation) ? rawW : rawH;
    }

    public int orientation() {
        return video ? CropMath.orientationForRotation(rotation) : exifOrientation;
    }

    public int storedW() {
        return video ? videoW : rawW;
    }

    public int storedH() {
        return video ? videoH : rawH;
    }

    public static String standardName(int s) {
        switch (s) {
            case 1: return "BT.709";
            case 2: return "BT.601 PAL";
            case 4: return "BT.601 NTSC";
            case 6: return "BT.2020";
            default: return s < 0 ? "ไม่ระบุ" : "#" + s;
        }
    }

    public static String transferName(int t) {
        switch (t) {
            case 1: return "Linear";
            case 3: return "SDR (BT.709/601)";
            case 6: return "PQ / ST 2084 (HDR10)";
            case 7: return "HLG";
            default: return t < 0 ? "ไม่ระบุ" : "#" + t;
        }
    }

    public static String rangeName(int r) {
        return r == 1 ? "full" : r == 2 ? "limited" : "ไม่ระบุ";
    }

    /** One-line, non-technical summary. */
    public String summary() {
        if (video) {
            return String.format(Locale.ROOT, "วิดีโอ %d×%d · %s · %s%s%s", orientedW(), orientedH(),
                    Double.isNaN(fps) ? "? fps" : String.format(Locale.ROOT, "%.0f fps%s", fps, vfr ? " (VFR)" : ""),
                    Fmt.durationUs(durationUs), hdr ? " · HDR" : "", hasAudio ? " · มีเสียง" : " · ไม่มีเสียง");
        }
        return String.format(Locale.ROOT, "รูป %d×%d (%s) · %s%s", orientedW(), orientedH(), Fmt.mp(orientedW(), orientedH()),
                Fmt.bytes(sizeBytes), wideGamut ? " · Wide-gamut" : "");
    }

    /** "Technical Info" block. */
    public String technical() {
        StringBuilder sb = new StringBuilder();
        sb.append("ไฟล์: ").append(name).append('\n');
        sb.append("MIME: ").append(mime).append(" · ขนาด ").append(Fmt.bytes(sizeBytes)).append('\n');
        if (video) {
            sb.append("Video: ").append(videoMime).append(" ").append(videoW).append('×').append(videoH)
                    .append(" rotation ").append(rotation).append("°").append('\n');
            sb.append("Profile/level: ").append(profile).append('/').append(level).append('\n');
            sb.append(String.format(Locale.ROOT, "Frame rate: avg %.2f (min %.2f / max %.2f)%s, frames sampled %d%n",
                    fps, fpsMin, fpsMax, vfr ? " VFR" : "", frames));
            sb.append("Duration: ").append(Fmt.durationUs(durationUs)).append(" · bitrate ").append(Fmt.bitrate(bitrate)).append('\n');
            sb.append("Colour: ").append(standardName(colorStandard)).append(" · ").append(transferName(colorTransfer))
                    .append(" · range ").append(rangeName(colorRange)).append(hdr ? " · HDR" : " · SDR").append('\n');
            if (hasAudio) {
                sb.append("Audio: ").append(audioMime).append(' ').append(sampleRate).append(" Hz · ").append(channels)
                        .append(" ch · ").append(Fmt.bitrate(audioBitrate)).append('\n');
            } else {
                sb.append("Audio: ไม่มี\n");
            }
        } else {
            sb.append("Stored: ").append(rawW).append('×').append(rawH).append(" · EXIF orientation ").append(exifOrientation).append('\n');
            sb.append("Colour space: ").append(colorSpace).append(wideGamut ? " (กว้างกว่า sRGB)" : "").append('\n');
            sb.append("ICC profile: ").append(hasIcc ? "มี" : "ไม่มี/ไม่ทราบ").append(" · Ultra HDR gain map: ").append(hasGainmap ? "มี" : "ไม่มี").append('\n');
            sb.append("GPS ในไฟล์: ").append(hasGps ? "มี (ไฟล์ส่งออกจะไม่มี)" : "ไม่มี").append('\n');
            if (camera != null) sb.append("Camera: ").append(camera).append('\n');
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("mime", mime);
        m.put("size_bytes", sizeBytes);
        m.put("width", orientedW());
        m.put("height", orientedH());
        if (video) {
            m.put("codec", videoMime);
            m.put("profile", profile);
            m.put("level", level);
            m.put("fps_avg", fps);
            m.put("vfr", vfr);
            m.put("duration_s", durationUs / 1e6);
            m.put("bitrate_bps", bitrate);
            m.put("color_standard", standardName(colorStandard));
            m.put("color_transfer", transferName(colorTransfer));
            m.put("color_range", rangeName(colorRange));
            m.put("hdr", hdr);
            m.put("frames", frames);
            m.put("audio", hasAudio ? audioMime + " " + sampleRate + "Hz " + channels + "ch" : "none");
        } else {
            m.put("color_space", colorSpace);
            m.put("icc", hasIcc);
            m.put("gps", hasGps);
            m.put("exif_orientation", exifOrientation);
        }
        return m;
    }
}
