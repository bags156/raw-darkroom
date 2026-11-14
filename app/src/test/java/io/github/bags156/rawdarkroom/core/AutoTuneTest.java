package io.github.bags156.rawdarkroom.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class AutoTuneTest {

    private static final int W = 320;
    private static final int H = 240;

    /** A plausible scene: a broad spread of tones, mostly near-neutral, some colour. */
    private static int[] scene(double brightness, double redGain, double blueGain) {
        Random rnd = new Random(7);
        int[] px = new int[W * H];
        for (int i = 0; i < px.length; i++) {
            double base = Math.pow(rnd.nextDouble(), 2.0) * 0.9 + 0.02; // linear, median about 0.25
            double tintR = 1 + (rnd.nextDouble() - 0.5) * 0.2;
            double tintB = 1 + (rnd.nextDouble() - 0.5) * 0.2;
            px[i] = 0xFF000000
                    | (enc(base * brightness * redGain * tintR) << 16)
                    | (enc(base * brightness) << 8)
                    | enc(base * brightness * blueGain * tintB);
        }
        return px;
    }

    private static int enc(double linear) {
        double l = Math.min(1.0, Math.max(0.0, linear));
        return (int) Math.round(Pipeline.linearToSrgb(l) * 255.0);
    }

    @Test
    public void wellExposedNeutralSceneIsLeftNearlyAlone() {
        Params p = AutoTune.analyze(scene(0.7, 1, 1), W, H, 0.7f);
        assertEquals(0f, p.exposure, 0.35f);
        assertEquals(0f, p.warmth, 0.05f);
        assertEquals(0f, p.tint, 0.05f);
        assertEquals(0.7f, p.look, 0f);
    }

    @Test
    public void underexposedSceneGetsBrightened() {
        Params p = AutoTune.analyze(scene(0.12, 1, 1), W, H, 0.7f);
        assertTrue("exposure " + p.exposure, p.exposure > 0.8f);
        assertTrue(p.exposure <= 2f);
    }

    @Test
    public void darkSceneWithBrightLightsIsNotTreatedAsUnderexposed() {
        // Mostly dark, but with lamps at full brightness: dusk, not a mistake.
        int[] px = scene(0.12, 1, 1);
        for (int i = 0; i < px.length; i += 40) {
            px[i] = 0xFFFFFFFF;
        }
        Params p = AutoTune.analyze(px, W, H, 0.7f);
        assertTrue("exposure " + p.exposure, p.exposure <= 0.6f);
    }

    @Test
    public void overexposedSceneIsDarkenedOnlyGently() {
        Params p = AutoTune.analyze(scene(2.5, 1, 1), W, H, 0.7f);
        assertTrue("exposure " + p.exposure, p.exposure < 0f);
        assertTrue(p.exposure >= -1f);
        assertTrue("highlights pulled down", p.highlights < 0f);
    }

    @Test
    public void blueCastIsWarmedAndOrangeCastIsCooledButOnlyPartly() {
        Params blue = AutoTune.analyze(scene(0.7, 0.85, 1.15), W, H, 0.7f);
        assertTrue("warmth " + blue.warmth, blue.warmth > 0.1f && blue.warmth <= 0.3f);

        Params orange = AutoTune.analyze(scene(0.7, 1.15, 0.85), W, H, 0.7f);
        assertTrue("warmth " + orange.warmth, orange.warmth < -0.1f && orange.warmth >= -0.3f);
    }

    @Test
    public void autoTunedBlueCastEndsUpCloserToNeutral() {
        int[] px = scene(0.7, 0.85, 1.15);
        Params p = AutoTune.analyze(px, W, H, 0f);
        p.exposure = 0f;
        p.shadows = 0f;
        p.highlights = 0f;
        p.contrast = 0f;
        p.saturation = 0f;
        int[] out = new int[px.length];
        new Pipeline(p).process(px, out, 0, px.length);
        assertTrue(cast(out) < cast(px));
    }

    /** Mean absolute red-blue difference: a simple measure of a warm/cool colour cast. */
    private static double cast(int[] px) {
        long r = 0, b = 0;
        for (int c : px) {
            r += (c >> 16) & 0xFF;
            b += c & 0xFF;
        }
        return Math.abs(r - b) / (double) px.length;
    }

    @Test
    public void flatLowContrastSceneGetsContrast() {
        int[] px = new int[W * H];
        Random rnd = new Random(3);
        for (int i = 0; i < px.length; i++) {
            int v = 110 + rnd.nextInt(40);
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Params p = AutoTune.analyze(px, W, H, 0.7f);
        assertTrue("contrast " + p.contrast, p.contrast > 0.1f);
    }

    @Test
    public void everyResultIsWithinLegalRangesForExtremeInputs() {
        int[][] inputs = {new int[W * H], filled(0xFFFFFFFF), filled(0xFFFF0000), filled(0xFF0000FF)};
        for (int[] px : inputs) {
            Params p = AutoTune.analyze(px, W, H, 0.7f);
            Params c = p.clamped();
            assertEquals(c.exposure, p.exposure, 0f);
            assertEquals(c.contrast, p.contrast, 0f);
            assertEquals(c.highlights, p.highlights, 0f);
            assertEquals(c.shadows, p.shadows, 0f);
            assertEquals(c.warmth, p.warmth, 0f);
            assertEquals(c.tint, p.tint, 0f);
            assertEquals(c.saturation, p.saturation, 0f);
        }
    }

    @Test
    public void emptyOrMismatchedInputReturnsNeutral() {
        Params p = AutoTune.analyze(new int[4], 100, 100, 0.5f);
        assertEquals(0f, p.exposure, 0f);
        assertEquals(0.5f, p.look, 0f);
    }

    private static int[] filled(int colour) {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, colour);
        return px;
    }
}
