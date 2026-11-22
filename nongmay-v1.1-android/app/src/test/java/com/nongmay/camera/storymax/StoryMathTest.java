package com.nongmay.camera.storymax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nongmay.camera.storymax.quality.AbWorksheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class StoryMathTest {

    @Test
    public void coverRectFromLandscapeIsCentredAndExact916() {
        double[] r = CropMath.coverRect(4000, 3000, CropMath.STORY_ASPECT, 1.0, 0.5, 0.5);
        assertEquals(3000, r[3] - r[1], 1e-9);
        assertEquals(1687.5, r[2] - r[0], 1e-9);
        assertEquals(1156.25, r[0], 1e-9);
        assertEquals(9.0 / 16.0, (r[2] - r[0]) / (r[3] - r[1]), 1e-12);
    }

    @Test
    public void coverRectClampsAndZooms() {
        double[] r = CropMath.coverRect(4000, 3000, CropMath.STORY_ASPECT, 2.0, 0.0, 1.0);
        assertEquals(0, r[0], 1e-9);
        assertEquals(3000, r[3], 1e-9);
        assertEquals(1500, r[3] - r[1], 1e-9);
        double[] portrait = CropMath.coverRect(3000, 4000, CropMath.STORY_ASPECT, 1.0, 0.5, 0.5);
        // 3:4 portrait is wider than 9:16, so the sides are cropped and full height is kept
        assertEquals(4000, portrait[3] - portrait[1], 1e-9);
        assertEquals(2250, portrait[2] - portrait[0], 1e-9);
        double[] tall = CropMath.coverRect(1080, 1920, CropMath.STORY_ASPECT, 1.0, 0.5, 0.5);
        assertEquals(1.0, CropMath.keptFraction(tall, 1080, 1920), 1e-12);
    }

    @Test
    public void fitRectLetterboxes() {
        double[] f = CropMath.fitRect(4000, 3000, 1080, 1920);
        assertEquals(0, f[0], 1e-9);
        assertEquals(1080, f[2], 1e-9);
        assertEquals(810, f[3] - f[1], 1e-9);
        assertEquals((1920 - 810) / 2.0, f[1], 1e-9);
    }

    @Test
    public void orientationRoundTripsForAllExifValues() {
        int rawW = 400, rawH = 300;
        for (int o = 1; o <= 8; o++) {
            for (double[] p : new double[][]{{0, 0}, {399, 0}, {10, 290}, {123.5, 77.25}}) {
                double[] d = CropMath.rawToOriented(p[0], p[1], o, rawW, rawH);
                double[] back = CropMath.orientedToRaw(d[0], d[1], o, rawW, rawH);
                assertEquals("o=" + o, p[0], back[0], 1e-9);
                assertEquals("o=" + o, p[1], back[1], 1e-9);
            }
        }
        int[] sz = CropMath.orientedSize(400, 300, 6);
        assertEquals(300, sz[0]);
        // rotate-90 photo: top strip of what the viewer sees is the left strip of the stored pixels
        double[] raw = CropMath.rectToRaw(new double[]{0, 0, 300, 100}, 6, 400, 300);
        assertEquals(0, raw[0], 1e-9);
        assertEquals(100, raw[2], 1e-9);
        assertEquals(0, raw[1], 1e-9);
        assertEquals(300, raw[3], 1e-9);
        assertEquals(6, CropMath.orientationForRotation(90));
        assertEquals(8, CropMath.orientationForRotation(-90));
        assertEquals(2, CropMath.sampleSize(4000, 7111, 1080, 1920));
        assertEquals(4, CropMath.sampleSize(4400, 7800, 1080, 1920));
        assertEquals(1, CropMath.sampleSize(1100, 1950, 1080, 1920));
    }

    @Test
    public void frameRatePolicyDropsWithoutJudder() {
        FrameRatePolicy p60 = new FrameRatePolicy(30);
        for (int i = 0; i < 120; i++) p60.accept(Math.round(i * 1e6 / 60));
        assertEquals(60, p60.accepted());
        FrameRatePolicy p24 = new FrameRatePolicy(30);
        for (int i = 0; i < 48; i++) p24.accept(Math.round(i * 1e6 / 24));
        assertEquals(48, p24.accepted());
        FrameRatePolicy jitter = new FrameRatePolicy(30);
        long t = 0;
        for (int i = 0; i < 90; i++) { jitter.accept(t); t += (i % 2 == 0) ? 30_000 : 36_666; }
        assertEquals(90, jitter.accepted());
        FrameRatePolicy p50 = new FrameRatePolicy(30);
        for (int i = 0; i < 100; i++) p50.accept(i * 20_000L);
        assertEquals(50, p50.accepted());
    }

    @Test
    public void toneMapReferencePoints() {
        assertEquals(100.0, ToneMap.pqEotfNits(ToneMap.pqOetf(100.0)), 1e-6);
        assertEquals(0.58, ToneMap.pqOetf(203.0), 0.01);
        assertEquals(1.0 / 12.0, ToneMap.hlgInvOetf(0.5), 1e-12);
        assertEquals(1.0, ToneMap.hlgInvOetf(1.0), 1e-6);
        double[] black = ToneMap.toSdr(0, 0, 0, ToneMap.TRANSFER_PQ, 1000);
        assertEquals(0, black[0], 1e-6);
        double c203 = ToneMap.pqOetf(203.0);
        double[] white = ToneMap.toSdr(c203, c203, c203, ToneMap.TRANSFER_PQ, 1000);
        assertTrue(white[0] > 0.9 && white[0] < 1.0);
        assertEquals(white[0], white[1], 1e-3); // BT.2087 matrix rows sum to 1 ± 1e-4
        double prev = -1;
        for (int nits = 1; nits <= 4000; nits *= 2) {
            double c = ToneMap.pqOetf(nits);
            double v = ToneMap.toSdr(c, c, c, ToneMap.TRANSFER_PQ, 1000)[1];
            assertTrue(v >= prev - 1e-9 && v <= 1.0);
            prev = v;
        }
        double[] hlgWhite = ToneMap.toSdr(0.75, 0.75, 0.75, ToneMap.TRANSFER_HLG, 1000);
        assertTrue(hlgWhite[0] > 0.85 && hlgWhite[0] <= 1.0);
        assertTrue(ToneMap.FRAGMENT_YUV_HDR.contains("__samplerExternal2DY2YEXT"));
    }

    @Test
    public void profilesAreConsistent() {
        Set<String> codes = new HashSet<>();
        for (StoryProfiles.Profile p : StoryProfiles.video()) assertTrue(codes.add("v" + p.code));
        for (StoryProfiles.Profile p : StoryProfiles.photo()) assertTrue(codes.add("p" + p.code));
        assertEquals(1080, StoryProfiles.V1080_SDR.width);
        assertEquals(1920, StoryProfiles.P1080_SDR.height);
        assertEquals(StoryProfiles.STABLE, StoryProfiles.V1080_SDR.status);
        assertEquals(StoryProfiles.NOT_IMPLEMENTED, StoryProfiles.V_HDR_TEST.status);
        assertFalse(StoryProfiles.abSet(true).contains(StoryProfiles.V_HDR_TEST));
        assertEquals(StoryProfiles.V1080_BR12, StoryProfiles.byCode("V1080_BR12"));
    }

    @Test
    public void worksheetHidesProfilesUntilKey() {
        List<AbWorksheet.Row> rows = new ArrayList<>();
        Set<String> blind = new HashSet<>();
        int i = 0;
        for (StoryProfiles.Profile p : StoryProfiles.abSet(true)) {
            AbWorksheet.Row r = new AbWorksheet.Row();
            r.experimentId = "E1";
            r.blindCode = AbWorksheet.blindCode(42, i++);
            r.profileCode = p.code;
            r.sourceName = "clip.mp4";
            r.actual = "1080x1920 H.264";
            blind.add(r.blindCode);
            rows.add(r);
        }
        String md = AbWorksheet.build("1.1.0", "SM-S938B", "2026-11-22", rows);
        int key = md.indexOf("## Key");
        assertTrue(key > 0);
        assertTrue(md.substring(0, key).indexOf("V1080_BR12") < 0);
        assertTrue(md.substring(key).contains("V1080_BR12"));
        for (String b : blind) assertTrue(md.contains("| " + b + " | Galaxy |"));
        assertEquals(rows.size(), blind.size());
    }

    static Set<String> corners(float[] v) {
        Set<String> s = new HashSet<>();
        for (int i = 0; i < 4; i++) s.add(Math.round(v[2 * i]) + "," + Math.round(v[2 * i + 1]));
        return s;
    }

    @Test
    public void quadFullFrameMapsCornersToCorners() {
        float[][] q = QuadMath.quad(1080, 1920, new double[]{0, 0, 1080, 1920}, new double[]{0, 0, 1, 1}, 1, false);
        float[] ndc = q[0], st = q[1];
        // triangle strip BL, BR, TL, TR
        assertEquals(-1f, ndc[0], 1e-6f); assertEquals(-1f, ndc[1], 1e-6f);
        assertEquals(1f, ndc[2], 1e-6f); assertEquals(-1f, ndc[3], 1e-6f);
        assertEquals(-1f, ndc[4], 1e-6f); assertEquals(1f, ndc[5], 1e-6f);
        assertEquals(1f, ndc[6], 1e-6f); assertEquals(1f, ndc[7], 1e-6f);
        assertEquals(0f, st[0], 1e-6f); assertEquals(0f, st[1], 1e-6f);
        assertEquals(1f, st[6], 1e-6f); assertEquals(1f, st[7], 1e-6f);
        // fit mode: a centred 1080x810 band keeps its aspect inside the 9:16 frame
        float[][] fit = QuadMath.quad(1080, 1920, new double[]{0, 555, 1080, 1365}, new double[]{0, 0, 1, 1}, 1, false);
        assertEquals((float) (1.0 - 2.0 * 1365 / 1920), fit[0][1], 1e-6f);
        assertEquals((float) (1.0 - 2.0 * 555 / 1920), fit[0][5], 1e-6f);
    }

    @Test
    public void quadLandscapeEncoderAndRotationStayInsideTheFrame() {
        float[][] land = QuadMath.quad(1080, 1920, new double[]{0, 0, 1080, 1920}, new double[]{0, 0, 1, 1}, 1, true);
        Set<String> want = new HashSet<>();
        for (String c : new String[]{"-1,-1", "1,-1", "-1,1", "1,1"}) want.add(c);
        assertEquals(want, corners(land[0]));
        assertEquals(1f, land[0][0], 1e-6f);   // portrait bottom-left lands at the landscape encoder's right edge
        assertEquals(-1f, land[0][1], 1e-6f);
        float[][] rot = QuadMath.quad(1080, 1920, new double[]{0, 0, 1080, 1920}, new double[]{0, 0, 1, 1}, 6, false);
        Set<String> unit = new HashSet<>();
        for (String c : new String[]{"0,0", "1,0", "0,1", "1,1"}) unit.add(c);
        assertEquals(unit, corners(rot[1]));
        float[][] up = QuadMath.quad(1080, 1920, new double[]{0, 0, 1080, 1920}, new double[]{0, 0, 1, 1}, 1, false);
        assertFalse(up[1][0] == rot[1][0] && up[1][1] == rot[1][1]);
    }

    @Test
    public void avcLevelCoversStoryAndUhdModes() {
        assertEquals(0x100, QuadMath.avcLevelFor(1080, 1920, 30));   // 4.1
        assertEquals(0x200, QuadMath.avcLevelFor(1080, 1920, 60));   // 4.2
        assertEquals(0x800, QuadMath.avcLevelFor(2160, 3840, 30));   // 5.1
        assertEquals(0x1000, QuadMath.avcLevelFor(2160, 3840, 60));  // 5.2
    }
}
