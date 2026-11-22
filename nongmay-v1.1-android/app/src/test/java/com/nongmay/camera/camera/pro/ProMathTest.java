package com.nongmay.camera.camera.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProMathTest {

    @Test
    public void shutterLabelsAndAngles() {
        assertEquals("1/120", ExposureMath.shutter(8_333_333));
        assertEquals("1/8000", ExposureMath.shutter(125_000));
        assertEquals("1/60", ExposureMath.shutter(16_666_667));
        assertEquals("0.5s", ExposureMath.shutter(500_000_000));
        assertEquals("2s", ExposureMath.shutter(2_000_000_000L));
        assertEquals(180.0, ExposureMath.shutterAngle(16_666_667, 30), 0.01);
        assertEquals(172.8, ExposureMath.shutterAngle(20_000_000, 24), 0.01);
        assertEquals(16_666_666, ExposureMath.clampToFrame(30_000_000, 60), 1);
    }

    @Test
    public void stopsStayInsideHardwareRange() {
        long[] s = ExposureMath.shutterStops(100_000, 250_000_000);
        assertEquals(100_000, s[0]);
        assertEquals(250_000_000, s[s.length - 1]);
        for (int i = 1; i < s.length; i++) assertTrue(s[i] > s[i - 1]);
        int[] iso = ExposureMath.isoStops(50, 3200);
        assertEquals(50, iso[0]);
        assertEquals(3200, iso[iso.length - 1]);
        assertEquals(100, iso[ExposureMath.nearestIndex(iso, 110)]);
        assertEquals(0, ExposureMath.isoStops(0, 100).length);
    }

    @Test
    public void exposureEquivalenceAndLensUnits() {
        assertEquals(4_000_000, ExposureMath.equivalentExposure(8_000_000, 100, 200));
        assertEquals("∞", ExposureMath.focusDistance(0));
        assertEquals("50 cm", ExposureMath.focusDistance(2));
        assertEquals("2.0 m", ExposureMath.focusDistance(0.5));
        assertEquals(22.7, ExposureMath.equivalentFocal(6.3, 9.6, 7.2), 0.2);
        assertEquals("0.6×", ExposureMath.zoomLabel(0.6));
        assertEquals("3×", ExposureMath.zoomLabel(3.0));
        assertEquals("+0.7", ExposureMath.evLabel(2, 1 / 3.0));
        assertEquals(10.0, ExposureMath.ev100(1_000_000_000L / 125, 100, 2.8), 0.1);
    }

    /** CIE XYZ (D65) -> linear sRGB, i.e. a "camera" whose native space is sRGB. */
    static final double[] XYZ_TO_SRGB_D65 = {
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252};

    @Test
    public void planckianLocus() {
        double[] xy = WhiteBalanceMath.planckianXy(6504);
        assertEquals(0.3135, xy[0], 0.002);
        assertEquals(0.3237, xy[1], 0.002);
        double[] a = WhiteBalanceMath.planckianXy(2856);
        assertEquals(0.4476, a[0], 0.003);
        assertEquals(0.4074, a[1], 0.003);
        double[] magenta = WhiteBalanceMath.whiteXy(5000, 20);
        double[] plain = WhiteBalanceMath.whiteXy(5000, 0);
        assertTrue(magenta[1] < plain[1]);
        assertEquals(5000, WhiteBalanceMath.cctFromXy(plain[0], plain[1]), 60);
    }

    @Test
    public void kelvinGainsBehaveLikeACamera() {
        WhiteBalanceMath.Result d65 = WhiteBalanceMath.compute(6504, 0, XYZ_TO_SRGB_D65, null, null, null, null, null, 6504, 0);
        assertTrue(d65.valid);
        assertEquals(1.0, d65.gains[0], 0.06);
        assertEquals(1.0, d65.gains[3], 0.06);
        WhiteBalanceMath.Result warm = WhiteBalanceMath.compute(3000, 0, XYZ_TO_SRGB_D65, null, null, null, null, null, 6504, 0);
        assertTrue("tungsten light needs less red gain than blue gain", warm.gains[0] < warm.gains[3]);
        for (int r = 0; r < 3; r++) {
            assertEquals(1.0, warm.ccm[r * 3] + warm.ccm[r * 3 + 1] + warm.ccm[r * 3 + 2], 1e-9);
        }
        double[] fm = WhiteBalanceMath.inv(WhiteBalanceMath.XYZD50_TO_SRGB);
        assertNotNull(fm);
        WhiteBalanceMath.Result withFm = WhiteBalanceMath.compute(5000, 0, XYZ_TO_SRGB_D65, XYZ_TO_SRGB_D65, null, null, fm, fm, 2856, 6504);
        assertTrue(withFm.forwardMatrix);
        double[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        for (int i = 0; i < 9; i++) assertEquals(id[i], withFm.ccm[i], 1e-6);
        assertNull(WhiteBalanceMath.compute(5000, 0, null, null, null, null, null, null, 0, 0).gains);
    }

    @Test
    public void illuminantInterpolationWeight() {
        assertEquals(1.0, WhiteBalanceMath.weight(2856, 2856, 6504), 1e-9);
        assertEquals(0.0, WhiteBalanceMath.weight(6504, 2856, 6504), 1e-9);
        double mid = WhiteBalanceMath.weight(4000, 2856, 6504);
        assertTrue(mid > 0 && mid < 1);
        assertEquals(6504, WhiteBalanceMath.illuminantKelvin(21), 0);
    }

    @Test
    public void lensChipsUseTheRatioWhereTheS25UltraSwitchesLens() {
        assertEquals(3.0, ExposureMath.lensChipRatio(61.0 / 22.3), 1e-9);      // 2.74x tele: HAL switches at 3.0x
        assertEquals(112.7 / 22.3, ExposureMath.lensChipRatio(112.7 / 22.3), 1e-9); // 5.06x tele: native works
        assertEquals(1.0, ExposureMath.lensChipRatio(1.0), 1e-9);
        assertEquals(0.6, ExposureMath.lensChipRatio(0.6), 1e-9);
        assertEquals(2.0, ExposureMath.lensChipRatio(1.85), 1e-9);
    }
}
