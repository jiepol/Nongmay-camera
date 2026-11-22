package com.nongmay.camera.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nongmay.camera.perf.Stats;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.Test;

public class UtilTest {

    @Test
    public void jsonEscapesAndNests() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "น้องเม \"quote\"\n");
        m.put("nan", Double.NaN);
        m.put("list", Arrays.asList(1, 2.5, true, null));
        m.put("arr", new int[]{3, 4});
        assertEquals("{\"text\":\"น้องเม \\\"quote\\\"\\n\",\"nan\":null,\"list\":[1,2.5,true,null],\"arr\":[3,4]}", Json.write(m));
        assertTrue(Json.pretty(m).contains("\n  \"text\": "));
    }

    @Test
    public void statsPercentiles() {
        Stats s = Stats.of(new double[]{5, 1, 4, 2, 3});
        assertEquals(5, s.n);
        assertEquals(3.0, s.p50, 1e-9);
        assertEquals(4.8, s.p95, 1e-9);
        assertEquals(1.0, s.min, 1e-9);
        assertEquals(0, Stats.of(new double[0]).n);
    }

    @Test
    public void iccIsInsertedAfterJfifAndJpegStillDecodes() throws Exception {
        byte[] icc = Files.readAllBytes(Paths.get("app/src/main/res/raw/srgb_v2.icc"));
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) img.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | 90);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpg", bos));
        byte[] jpeg = bos.toByteArray();
        assertFalse(JpegIcc.hasIcc(jpeg));
        byte[] out = JpegIcc.insertIcc(jpeg, icc);
        assertTrue(JpegIcc.hasIcc(out));
        List<String> markers = JpegIcc.markers(out);
        assertEquals("FFD8", markers.get(0));
        assertEquals("FFE0", markers.get(1));
        assertEquals("FFE2", markers.get(2));
        assertEquals(jpeg.length + icc.length + 18, out.length);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(out));
        assertNotNull(back);
        assertEquals(64, back.getWidth());
        assertSame(out, JpegIcc.insertIcc(out, icc)); // idempotent
    }

    @Test(expected = IllegalArgumentException.class)
    public void iccRejectsNonJpeg() {
        JpegIcc.insertIcc(new byte[]{1, 2, 3, 4}, new byte[]{0});
    }

    @Test
    public void formatting() {
        assertEquals("1.5 MB", Fmt.bytes(1572864));
        assertEquals("12.0 Mbps", Fmt.bitrate(12_000_000));
        assertEquals("00:05", Fmt.clock(5400));
        assertArrayEquals(new byte[0], new byte[0]);
    }
}
