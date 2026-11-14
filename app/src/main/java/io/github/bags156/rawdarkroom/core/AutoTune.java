package io.github.bags156.rawdarkroom.core;

/**
 * On-device auto-tuning. Looks at one photo's brightness and colour statistics and proposes
 * slider settings for it. This is statistical image analysis, not a neural network: it runs in
 * milliseconds, needs no model file and no network, and its decisions are explainable.
 *
 * <p>It is deliberately conservative. A camera's own white balance is usually close, and many
 * scenes are meant to be dark, bright or strongly coloured, so every correction is partial and
 * capped.
 */
public final class AutoTune {

    private static final int BINS = 256;
    /** Analyse at most about this many pixels; more adds time without changing the answer. */
    private static final int TARGET_SAMPLES = 200_000;
    private static final float TARGET_MEDIAN = 0.16f;

    private AutoTune() {
    }

    /**
     * @param pixels packed sRGB ARGB pixels, at least {@code width * height} long
     * @param look   the look strength to carry into the result
     */
    public static Params analyze(int[] pixels, int width, int height, float look) {
        Params p = new Params();
        p.look = look;
        int total = width * height;
        if (total <= 0 || pixels.length < total) {
            return p;
        }
        int step = Math.max(1, (int) Math.sqrt(total / (double) TARGET_SAMPLES));

        // Luminance histogram over sqrt(luminance): fine resolution in the darks.
        int[] hist = new int[BINS];
        int n = 0;
        double sumPurity = 0;
        double nr = 0, ng = 0, nb = 0;
        int neutral = 0;

        for (int yy = 0; yy < height; yy += step) {
            int row = yy * width;
            for (int xx = 0; xx < width; xx += step) {
                int c = pixels[row + xx];
                float r = Pipeline.linear(c >> 16);
                float g = Pipeline.linear(c >> 8);
                float b = Pipeline.linear(c);
                float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                hist[Math.min(BINS - 1, (int) (Math.sqrt(y) * BINS))]++;
                n++;

                float max = Math.max(r, Math.max(g, b));
                float min = Math.min(r, Math.min(g, b));
                float purity = max > 1e-5f ? (max - min) / max : 0f;
                sumPurity += purity;

                // Mid-tone, nearly colourless pixels are the best evidence of a colour cast.
                if (y > 0.05f && y < 0.7f && purity < 0.25f) {
                    nr += r;
                    ng += g;
                    nb += b;
                    neutral++;
                }
            }
        }
        if (n == 0) {
            return p;
        }

        float p5 = percentile(hist, n, 0.05);
        float p50 = percentile(hist, n, 0.50);
        float p95 = percentile(hist, n, 0.95);

        // Exposure: move the median part of the way towards a mid-grey target. Darkening is held
        // back further, because bright scenes (snow, beach, high-key) are often meant to be bright.
        float ev = (float) (Math.log(TARGET_MEDIAN / Math.max(p50, 0.004f)) / Math.log(2.0));
        ev *= ev > 0 ? 0.7f : 0.35f;
        // A frame whose brightest parts are already bright is not underexposed, it is a dark
        // scene (dusk, night, a stage). Brighten it only as far as its highlights have room.
        float p99 = percentile(hist, n, 0.998);
        float headroom = (float) (Math.log(1.0 / Math.max(p99, 0.004f)) / Math.log(2.0));
        ev = Math.min(ev, headroom + 0.5f);
        ev = Params.clamp(ev, -1f, 2f);
        if (Math.abs(ev) < 0.15f) {
            ev = 0f;
        }
        p.exposure = round2(ev);

        // Shadows and highlights, judged after the exposure change.
        float gain = (float) Math.pow(2.0, ev);
        float dark = fractionBelow(hist, n, 0.02f / gain);
        // When the photo is being darkened, bright areas are still judged as they were shot:
        // an overexposed frame needs its highlights pulled down as well.
        float bright = 1f - fractionBelow(hist, n, 0.70f / Math.max(gain, 1f));
        // These act by region (see LocalMap), so they can be firmer than a global curve without
        // flattening the photo. Shadows stay the gentler of the two: large dark areas are often
        // meant to be dark, and lifting them also lifts noise.
        p.shadows = round2(Params.clamp(dark * 2.0f, 0f, 0.5f));
        p.highlights = round2(-Params.clamp(bright * 2.0f, 0f, 0.6f));

        // Contrast from the spread between dark and bright tones, in stops.
        float spread = (float) (Math.log(Math.max(p95, 1e-4f) / Math.max(p5, 1e-4f)) / Math.log(2.0));
        if (spread < 4f) {
            p.contrast = round2(Params.clamp(0.15f * (4f - spread), 0f, 0.4f));
        }

        // White balance: correct about a third of the cast of the near-neutral pixels, if there are
        // enough of them, and ignore small casts. The camera's own white balance is usually right,
        // and warm or cool light is often the point of the photo.
        if (neutral > n / 20 && nr > 0 && ng > 0 && nb > 0) {
            // Pipeline: red/blue gain ratio is 2^(0.5 * warmth); green relative to the red-blue
            // mean is 2^(-0.225 * tint).
            double warmth = Math.log(nb / nr) / Math.log(2.0) / 0.5;
            double tint = Math.log(ng / Math.sqrt(nr * nb)) / Math.log(2.0) / 0.225;
            p.warmth = round2(Params.clamp((float) (0.35 * warmth), -0.3f, 0.3f));
            p.tint = round2(Params.clamp((float) (0.35 * tint), -0.3f, 0.3f));
            if (Math.abs(p.warmth) < 0.08f) {
                p.warmth = 0f;
            }
            if (Math.abs(p.tint) < 0.08f) {
                p.tint = 0f;
            }
        }

        // Saturation: a small lift for washed-out photos only. Vivid photos are left to the look,
        // which already tames the most intense colours.
        float meanPurity = (float) (sumPurity / n);
        if (meanPurity < 0.15f) {
            p.saturation = 0.15f;
        }
        return p;
    }

    /** Luminance (linear) below which {@code fraction} of the samples fall. */
    private static float percentile(int[] hist, int n, double fraction) {
        long target = (long) Math.ceil(n * fraction);
        long seen = 0;
        for (int i = 0; i < BINS; i++) {
            seen += hist[i];
            if (seen >= target) {
                float s = (i + 0.5f) / BINS;
                return s * s;
            }
        }
        return 1f;
    }

    private static float fractionBelow(int[] hist, int n, float luminance) {
        if (luminance >= 1f) {
            return 1f;
        }
        int limit = (int) (Math.sqrt(Math.max(0f, luminance)) * BINS);
        long seen = 0;
        for (int i = 0; i < limit && i < BINS; i++) {
            seen += hist[i];
        }
        return seen / (float) n;
    }

    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }
}
