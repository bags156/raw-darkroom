package io.github.bags156.rawdarkroom.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalMapTest {

    private static final int W = 400;
    private static final int H = 300;

    private static int grey(int v) {
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    /** Left half dark, right half bright, with one small dark patch inside the bright half. */
    private static int[] scene() {
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean patch = x >= 296 && x < 304 && y >= 146 && y < 154;
                px[y * W + x] = grey(x < W / 2 || patch ? 40 : 200);
            }
        }
        return px;
    }

    private static int[] develop(Params p, int[] px, LocalMap map) {
        int[] out = new int[px.length];
        new Pipeline(p).processRows(px, out, 0, W, H, 0, H, map);
        return out;
    }

    private static Params only(float shadows, float highlights) {
        Params p = new Params();
        p.look = 0f;
        p.shadows = shadows;
        p.highlights = highlights;
        return p;
    }

    @Test
    public void uniformImageGivesUniformMap() {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, grey(128));
        LocalMap map = LocalMap.build(px, W, H);
        assertEquals(128 / 255f, map.sample(0.1f, 0.1f), 0.01f);
        assertEquals(128 / 255f, map.sample(0.9f, 0.9f), 0.01f);
        assertEquals(128 / 255f, map.sample(0f, 1f), 0.01f);
    }

    @Test
    public void mapFollowsRegionsNotSmallDetails() {
        LocalMap map = LocalMap.build(scene(), W, H);
        assertTrue("dark side is dark", map.sample(0.1f, 0.5f) < 0.3f);
        assertTrue("bright side is bright", map.sample(0.9f, 0.5f) > 0.6f);
        // The small dark patch barely dents the bright region around it.
        assertTrue("patch area still reads bright", map.sample(0.75f, 0.5f) > 0.6f);
    }

    @Test
    public void shadowLiftIsRegionalSoDetailInBrightAreasKeepsContrast() {
        int[] px = scene();
        int[] out = develop(only(1f, 0f), px, LocalMap.build(px, W, H));
        int bigDarkArea = (out[150 * W + 50] >> 8) & 0xFF;
        int smallDarkPatch = (out[150 * W + 300] >> 8) & 0xFF;
        assertTrue("large shadow is lifted", bigDarkArea > 55);
        assertTrue("small dark detail is lifted less", smallDarkPatch < bigDarkArea - 5);
    }

    @Test
    public void highlightsAreHeldBackOnlyInBrightRegions() {
        int[] px = scene();
        int[] out = develop(only(0f, -1f), px, LocalMap.build(px, W, H));
        assertTrue("bright side darker", ((out[150 * W + 380] >> 8) & 0xFF) < 190);
        assertEquals("dark side untouched", 40, (out[150 * W + 50] >> 8) & 0xFF, 2);
    }

    @Test
    public void blackAndWhiteArePinnedWithAMap() {
        int[] px = scene();
        px[10 * W + 10] = grey(0);
        px[10 * W + 390] = grey(255);
        int[] out = develop(only(1f, -1f), px, LocalMap.build(px, W, H));
        assertEquals(0, out[10 * W + 10] & 0xFF);
        assertEquals(255, out[10 * W + 390] & 0xFF);
    }

    @Test
    public void bandsGiveTheSameResultAsOnePass() {
        int[] px = scene();
        LocalMap map = LocalMap.build(px, W, H);
        Params p = only(0.6f, -0.5f);
        int[] whole = develop(p, px, map);
        int[] banded = new int[px.length];
        Pipeline pipeline = new Pipeline(p);
        for (int y = 0; y < H; y += 37) {
            int rows = Math.min(37, H - y);
            pipeline.processRows(px, banded, y * W, W, rows, y, H, map);
        }
        for (int i = 0; i < px.length; i++) {
            assertEquals(whole[i], banded[i]);
        }
    }

    @Test
    public void mapFromASmallCopyMatchesFullSizeClosely() {
        int[] px = scene();
        int[] half = new int[(W / 2) * (H / 2)];
        for (int y = 0; y < H / 2; y++) {
            for (int x = 0; x < W / 2; x++) {
                half[y * (W / 2) + x] = px[(y * 2) * W + x * 2];
            }
        }
        LocalMap big = LocalMap.build(px, W, H);
        LocalMap small = LocalMap.build(half, W / 2, H / 2);
        for (float u = 0.05f; u < 1f; u += 0.1f) {
            assertEquals(big.sample(u, 0.5f), small.sample(u, 0.5f), 0.06f);
        }
    }

    @Test
    public void emptyImageGivesNoMapAndNoMapStillProcesses() {
        assertTrue(LocalMap.build(new int[0], 0, 0) == null);
        int[] px = {grey(40), grey(200)};
        int[] out = new int[2];
        new Pipeline(only(1f, -1f)).processRows(px, out, 0, 2, 1, 0, 1, null);
        assertTrue((out[0] & 0xFF) > 40);
    }
}
