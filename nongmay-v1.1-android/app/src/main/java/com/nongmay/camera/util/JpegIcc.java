package com.nongmay.camera.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts an ICC profile (APP2 "ICC_PROFILE") into a baseline/progressive JPEG without
 * re-encoding pixels. Used so StoryMax JPEGs explicitly declare sRGB. Pure Java.
 */
public final class JpegIcc {
    private static final byte[] ICC_ID = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_CHUNK = 65533 - 2 - ICC_ID.length - 2; // seg len field counts itself

    private JpegIcc() {}

    /** Marker list (e.g. "FFD8","FFE0","FFDB",...) up to SOS, for tests and Technical Info. */
    public static List<String> markers(byte[] jpeg) {
        List<String> out = new ArrayList<>();
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return out;
        out.add("FFD8");
        int p = 2;
        while (p + 4 <= jpeg.length) {
            if ((jpeg[p] & 0xFF) != 0xFF) break;
            int m = jpeg[p + 1] & 0xFF;
            if (m == 0xFF) { p++; continue; }
            out.add(String.format(java.util.Locale.ROOT, "FF%02X", m));
            if (m == 0xDA || m == 0xD9) break;
            int len = ((jpeg[p + 2] & 0xFF) << 8) | (jpeg[p + 3] & 0xFF);
            p += 2 + len;
        }
        return out;
    }

    public static boolean hasIcc(byte[] jpeg) {
        return findIcc(jpeg) >= 0;
    }

    private static int findIcc(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return -1;
        int p = 2;
        while (p + 4 <= jpeg.length) {
            if ((jpeg[p] & 0xFF) != 0xFF) return -1;
            int m = jpeg[p + 1] & 0xFF;
            if (m == 0xDA || m == 0xD9) return -1;
            int len = ((jpeg[p + 2] & 0xFF) << 8) | (jpeg[p + 3] & 0xFF);
            if (m == 0xE2 && len >= 2 + ICC_ID.length && startsWith(jpeg, p + 4, ICC_ID)) return p;
            p += 2 + len;
        }
        return -1;
    }

    private static boolean startsWith(byte[] a, int off, byte[] prefix) {
        if (off + prefix.length > a.length) return false;
        for (int i = 0; i < prefix.length; i++) if (a[off + i] != prefix[i]) return false;
        return true;
    }

    /**
     * Returns a JPEG with the given ICC profile embedded right after SOI and any APP0/APP1
     * segments (JFIF/Exif stay first). If the JPEG already has an ICC profile it is returned as is.
     */
    public static byte[] insertIcc(byte[] jpeg, byte[] icc) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("not a JPEG");
        }
        if (hasIcc(jpeg)) return jpeg;
        int insertAt = 2;
        int p = 2;
        while (p + 4 <= jpeg.length && (jpeg[p] & 0xFF) == 0xFF) {
            int m = jpeg[p + 1] & 0xFF;
            if (m != 0xE0 && m != 0xE1) break;
            int len = ((jpeg[p + 2] & 0xFF) << 8) | (jpeg[p + 3] & 0xFF);
            p += 2 + len;
            insertAt = p;
        }
        int chunks = (icc.length + MAX_CHUNK - 1) / MAX_CHUNK;
        if (chunks > 255) throw new IllegalArgumentException("ICC too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + icc.length + 32 * chunks);
        out.write(jpeg, 0, insertAt);
        for (int i = 0; i < chunks; i++) {
            int from = i * MAX_CHUNK;
            int n = Math.min(MAX_CHUNK, icc.length - from);
            int segLen = 2 + ICC_ID.length + 2 + n;
            out.write(0xFF);
            out.write(0xE2);
            out.write((segLen >> 8) & 0xFF);
            out.write(segLen & 0xFF);
            out.write(ICC_ID, 0, ICC_ID.length);
            out.write(i + 1);
            out.write(chunks);
            out.write(icc, from, n);
        }
        out.write(jpeg, insertAt, jpeg.length - insertAt);
        return out.toByteArray();
    }
}
