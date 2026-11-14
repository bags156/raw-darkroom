package io.github.bags156.rawdarkroom.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PipelineTest {

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int r(int c) {
        return (c >> 16) & 0xFF;
    }

    private static int g(int c) {
        return (c >> 8) & 0xFF;
    }

    private static int b(int c) {
        return c & 0xFF;
    }

    private static int[] run(Params p, int... pixels) {
        int[] out = new int[pixels.length];
        new Pipeline(p).process(pixels, out, 0, pixels.length);
        return out;
    }

    private static Params neutral() {
        Params p = new Params();
        p.look = 0f;
        return p;
    }

    @Test
    public void neutralParamsLeaveMidtonesAndShadowsUnchanged() {
        // Below the highlight fade (which starts near sRGB 220) the neutral pipeline is an identity.
        for (int v = 0; v <= 200; v += 5) {
            int in = rgb(v, Math.max(0, v - 20), Math.min(255, v / 2 + 10));
            int out = run(neutral(), in)[0];
            assertEquals("r at " + v, r(in), r(out), 1);
            assertEquals("g at " + v, g(in), g(out), 1);
            assertEquals("b at " + v, b(in), b(out), 1);
        }
    }

    @Test
    public void greyStaysGreyWithTheLookOn() {
        Params p = new Params();
        p.look = 1f;
        p.contrast = 0.5f;
        p.shadows = 0.4f;
        p.highlights = -0.4f;
        p.exposure = 0.7f;
        p.saturation = 0.8f;
        for (int v = 0; v <= 255; v += 3) {
            int out = run(p, rgb(v, v, v))[0];
            assertEquals("grey " + v, r(out), g(out), 1);
            assertEquals("grey " + v, g(out), b(out), 1);
        }
    }

    @Test
    public void blackStaysBlackAndWhiteStaysWhite() {
        Params p = new Params();
        p.look = 1f;
        p.contrast = 1f;
        p.shadows = 1f;
        p.highlights = -1f;
        int[] out = run(p, rgb(0, 0, 0), rgb(255, 255, 255));
        assertEquals(0, r(out[0]));
        assertEquals(255, r(out[1]));
    }

    @Test
    public void toneCurveIsMonotonicAtEverySliderExtreme() {
        float[] ends = {-1f, 0f, 1f};
        float[] exposures = {-3f, 0f, 1.3f, 3f};
        for (float contrast : ends) {
            for (float shadows : ends) {
                for (float highlights : ends) {
                    for (float exposure : exposures) {
                        for (float look : new float[] {0f, 1f}) {
                            Params p = new Params();
                            p.contrast = contrast;
                            p.shadows = shadows;
                            p.highlights = highlights;
                            p.exposure = exposure;
                            p.look = look;
                            double last = -1;
                            for (int i = 0; i <= 2000; i++) {
                                double y = i / 1000.0;
                                double t = Pipeline.tone(y, p);
                                assertTrue("in range", t >= 0.0 && t <= 1.0);
                                assertTrue("monotonic at y=" + y + " c=" + contrast + " s=" + shadows
                                        + " h=" + highlights + " e=" + exposure, t >= last - 1e-9);
                                last = t;
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void exposureBrightensAndRollsHighlightsOffInsteadOfClipping() {
        Params p = neutral();
        p.exposure = 1f;
        int[] out = run(p, rgb(118, 118, 118), rgb(200, 200, 200), rgb(240, 240, 240), rgb(255, 255, 255));
        assertTrue("mid grey brighter", r(out[0]) > 140);
        // Bright tones that a plain +1 stop would clip together stay distinct and in order.
        assertTrue(r(out[1]) < r(out[2]));
        assertTrue(r(out[2]) < r(out[3]));
        assertEquals(255, r(out[3]));
    }

    @Test
    public void shadowsLiftDarksAndHighlightsPullBrightsDown() {
        Params lift = neutral();
        lift.shadows = 1f;
        assertTrue(r(run(lift, rgb(40, 40, 40))[0]) > 50);

        Params pull = neutral();
        pull.highlights = -1f;
        assertTrue(r(run(pull, rgb(190, 190, 190))[0]) < 180);
    }

    @Test
    public void warmthShiftsRedUpAndBlueDown() {
        Params p = neutral();
        p.warmth = 0.5f;
        int out = run(p, rgb(128, 128, 128))[0];
        assertTrue(r(out) > 128);
        assertTrue(b(out) < 128);
    }

    @Test
    public void saturationSliderChangesColourfulness() {
        int in = rgb(150, 110, 90);
        Params more = neutral();
        more.saturation = 1f;
        Params less = neutral();
        less.saturation = -1f;
        int spreadIn = r(in) - b(in);
        int outMore = run(more, in)[0];
        int outLess = run(less, in)[0];
        assertTrue(r(outMore) - b(outMore) > spreadIn);
        assertTrue(r(outLess) - b(outLess) < spreadIn);
    }

    @Test
    public void lookTamesNeonColoursMoreThanGentleOnes() {
        Params off = neutral();
        Params on = neutral();
        on.look = 1f;
        on.contrast = -2f / 3f; // cancels the look's S-curve, isolating its colour behaviour
        int neon = rgb(20, 230, 40);
        int a = run(off, neon)[0];
        int b2 = run(on, neon)[0];
        assertTrue("neon green is less extreme", g(b2) - r(b2) < g(a) - r(a));
    }

    @Test
    public void alphaIsPreservedAndInPlaceProcessingWorks() {
        int[] px = {0x80FF8040, 0x00102030};
        new Pipeline(new Params()).process(px, px, 0, px.length);
        assertEquals(0x80, px[0] >>> 24);
        assertEquals(0x00, px[1] >>> 24);
    }

    @Test
    public void outOfRangeParamsAreClampedNotFatal() {
        Params p = new Params();
        p.exposure = 99f;
        p.contrast = -99f;
        p.look = Float.NaN;
        int out = run(p, rgb(100, 120, 140))[0];
        assertEquals(0xFF, out >>> 24);
    }
}
