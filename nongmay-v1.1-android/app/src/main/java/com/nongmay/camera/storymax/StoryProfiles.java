package com.nongmay.camera.storymax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Story export profiles. These are hypotheses to A/B test, NOT an official Instagram recipe:
 * Instagram re-encodes every Story, and nothing here can force it to keep our bits.
 */
public final class StoryProfiles {
    public static final int STABLE = 0, EXPERIMENTAL = 1, NOT_IMPLEMENTED = 2;

    public static final class Profile {
        public final String code;
        public final String label;
        public final boolean video;
        public final int width, height;
        public final int bitrate;
        public final int maxFps;
        public final int jpegQuality;
        public final int status;
        public final String note;

        Profile(String code, String label, boolean video, int w, int h, int bitrate, int maxFps, int q, int status, String note) {
            this.code = code; this.label = label; this.video = video; this.width = w; this.height = h;
            this.bitrate = bitrate; this.maxFps = maxFps; this.jpegQuality = q; this.status = status; this.note = note;
        }

        public boolean isDirect() {
            return code.equals("DIRECT_ORIGINAL");
        }

        public String statusLabel() {
            return status == STABLE ? "STABLE" : status == EXPERIMENTAL ? "EXPERIMENTAL" : "NOT IMPLEMENTED";
        }

        @Override
        public String toString() {
            return code;
        }
    }

    public static final Profile P1080_SDR = new Profile("P1080_SDR", "Photo Standard 1080×1920", false,
            1080, 1920, 0, 0, 95, STABLE, "JPEG q95, sRGB ICC embedded, no metadata");
    public static final Profile P1440_TEST = new Profile("P1440_TEST", "Photo Detail Test 1440×2560", false,
            1440, 2560, 0, 0, 95, EXPERIMENTAL, "A/B: does Instagram keep more detail from a larger upload?");
    public static final Profile V1080_SDR = new Profile("V1080_SDR", "Story Standard 1080×1920 · 10 Mbps", true,
            1080, 1920, 10_000_000, 30, 0, STABLE, "H.264 High + AAC passthrough, BT.709 SDR, ≤30 fps");
    public static final Profile V1080_BR8 = new Profile("V1080_BR8", "Bitrate Test 8 Mbps", true,
            1080, 1920, 8_000_000, 30, 0, EXPERIMENTAL, "A/B bitrate variant");
    public static final Profile V1080_BR12 = new Profile("V1080_BR12", "Bitrate Test 12 Mbps", true,
            1080, 1920, 12_000_000, 30, 0, EXPERIMENTAL, "A/B bitrate variant");
    public static final Profile V1080_BR16 = new Profile("V1080_BR16", "Bitrate Test 16 Mbps", true,
            1080, 1920, 16_000_000, 30, 0, EXPERIMENTAL, "A/B bitrate variant");
    public static final Profile V1440_TEST = new Profile("V1440_TEST", "Video Detail Test 1440×2560 · 16 Mbps", true,
            1440, 2560, 16_000_000, 30, 0, EXPERIMENTAL, "A/B: larger upload, same SDR pipeline");
    public static final Profile V_HDR_TEST = new Profile("V_HDR_TEST", "HDR Retention Test", true,
            0, 0, 0, 0, 0, NOT_IMPLEMENTED, "needs a 10-bit GL + HEVC Main10 path; not built in v1.1");
    public static final Profile DIRECT_ORIGINAL = new Profile("DIRECT_ORIGINAL", "Original (no re-encode)", true,
            0, 0, 0, 0, 0, EXPERIMENTAL, "video: stream copy into a new MP4 (metadata/GPS dropped); photo: JPEG copy with GPS removed");

    private StoryProfiles() {}

    public static List<Profile> photo() {
        List<Profile> l = new ArrayList<>();
        Collections.addAll(l, P1080_SDR, P1440_TEST, DIRECT_ORIGINAL);
        return l;
    }

    public static List<Profile> video() {
        List<Profile> l = new ArrayList<>();
        Collections.addAll(l, V1080_SDR, V1080_BR8, V1080_BR12, V1080_BR16, V1440_TEST, V_HDR_TEST, DIRECT_ORIGINAL);
        return l;
    }

    /** Variant set for one A/B experiment, in worksheet order. */
    public static List<Profile> abSet(boolean video) {
        List<Profile> l = new ArrayList<>();
        if (video) Collections.addAll(l, DIRECT_ORIGINAL, V1080_SDR, V1080_BR8, V1080_BR12, V1080_BR16, V1440_TEST);
        else Collections.addAll(l, DIRECT_ORIGINAL, P1080_SDR, P1440_TEST);
        return l;
    }

    public static Profile byCode(String code) {
        for (Profile p : video()) if (p.code.equals(code)) return p;
        for (Profile p : photo()) if (p.code.equals(code)) return p;
        return null;
    }
}
