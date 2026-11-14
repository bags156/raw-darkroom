package io.github.bags156.rawdarkroom.core;

/**
 * The colour and tone pipeline. Build one from a {@link Params}, then call {@link #process} from
 * as many threads as you like: after construction a Pipeline is read-only.
 *
 * <p>Pixels are packed ARGB ints in sRGB, as Android bitmaps provide them. Internally everything
 * runs in linear light:
 *
 * <ol>
 *   <li>white balance (warmth, tint), luminance-neutral on grey;
 *   <li>a tone curve applied to luminance only, so hue is preserved: shadows, highlights,
 *       contrast, the look's film S-curve, exposure, and a soft shoulder that rolls bright tones
 *       off towards white instead of clipping them;
 *   <li>saturation, with less boost for already-saturated colours, plus the look's restrained
 *       colour (tamed neon tones, highlights fading to white);
 *   <li>the look's small colour matrix, which leaves greys untouched.
 * </ol>
 *
 * <p>The look is a hand-tuned interpretation of "natural colour with film-like roll-off". It is
 * not measured from, or affiliated with, any camera maker's processing.
 */
public final class Pipeline {

    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final int ENCODE_N = 16384;
    private static final int[] LINEAR_TO_SRGB = new int[ENCODE_N + 1];

    /** The tone table covers input luminance 0..Y_MAX, indexed by sqrt for precision in the darks. */
    private static final int TONE_N = 4096;
    private static final float Y_MAX = 2f;

    /** Linear value above which exposure boosts are rolled off instead of clipped. */
    private static final double KNEE = 0.45;

    static {
        for (int i = 0; i < 256; i++) {
            SRGB_TO_LINEAR[i] = (float) srgbToLinear(i / 255.0);
        }
        for (int i = 0; i <= ENCODE_N; i++) {
            LINEAR_TO_SRGB[i] = (int) Math.round(linearToSrgb(i / (double) ENCODE_N) * 255.0);
        }
    }

    private final float gainR;
    private final float gainG;
    private final float gainB;
    /** toneRatio[i] = tone(y) / y for the luminance at index i. */
    private final float[] toneRatio = new float[TONE_N + 1];
    private final float satBoost;
    private final float look;
    // Look matrix (rows sum to 1, so neutrals are preserved).
    private final float m00, m01, m02, m10, m11, m12, m20, m21, m22;

    public Pipeline(Params raw) {
        Params p = raw.clamped();

        // White balance. Normalised so a grey pixel keeps its luminance.
        double r = Math.pow(2.0, 0.25 * p.warmth + 0.075 * p.tint);
        double g = Math.pow(2.0, -0.15 * p.tint);
        double b = Math.pow(2.0, -0.25 * p.warmth + 0.075 * p.tint);
        double norm = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        gainR = (float) (r / norm);
        gainG = (float) (g / norm);
        gainB = (float) (b / norm);

        for (int i = 1; i <= TONE_N; i++) {
            double t = i / (double) TONE_N;
            double y = t * t * Y_MAX;
            toneRatio[i] = (float) (tone(y, p) / y);
        }
        toneRatio[0] = toneRatio[1];

        satBoost = 1f + 0.6f * p.saturation;
        look = p.look;

        // Identity blended towards the look matrix by the look strength.
        float l = p.look;
        m00 = 1f + 0.03f * l;
        m01 = -0.02f * l;
        m02 = -0.01f * l;
        m10 = 0.02f * l;
        m11 = 1f - 0.04f * l;
        m12 = 0.02f * l;
        m20 = -0.01f * l;
        m21 = 0.03f * l;
        m22 = 1f - 0.02f * l;
    }

    /**
     * The luminance tone curve: linear in, linear out. Monotonic for every legal Params, maps 0 to
     * 0, and never exceeds 1.
     */
    static double tone(double y, Params p) {
        if (y <= 0.0) {
            return 0.0;
        }
        // Tone shaping happens on the 0..1 display range, in a perceptual (gamma) domain.
        double over = y > 1.0 ? y : 1.0;
        double v = Math.pow(Math.min(y, 1.0), 1.0 / 2.2);

        // Shadows and highlights: bumps pinned at black and white. The coefficients keep the
        // curve monotonic at the extremes of each slider.
        double cs = p.shadows >= 0 ? 0.25 * p.shadows : 0.13 * p.shadows;
        double ch = p.highlights <= 0 ? 0.25 * p.highlights : 0.13 * p.highlights;
        double v2 = v + cs * 6.75 * v * (1 - v) * (1 - v) + ch * 6.75 * v * v * (1 - v);
        v2 = Math.min(1.0, Math.max(0.0, v2));

        // Contrast and the look's film curve share one S-curve around mid grey. Its slope is
        // (1 + a) in the middle and (1 - a) at both ends: more punch, softer toe and shoulder.
        double a = 0.3 * p.contrast + 0.2 * p.look;
        a = Math.min(0.9, Math.max(-0.9, a));
        double v3 = v2 - a * Math.sin(2.0 * Math.PI * v2) / (2.0 * Math.PI);

        double x = Math.pow(v3, 2.2) * over * Math.pow(2.0, p.exposure);

        // Soft shoulder: when exposure pushes white past 1, compress everything above the knee so
        // the old white lands exactly on 1 with a smooth roll-off instead of a hard clip.
        double white = Math.pow(2.0, p.exposure);
        if (white > 1.0 && x > KNEE) {
            double slope = (white - KNEE) / (1.0 - KNEE);
            double t = (x - KNEE) / (white - KNEE);
            x = KNEE + (1.0 - KNEE) * (slope * t) / (1.0 + (slope - 1.0) * t);
        }
        return Math.min(1.0, x);
    }

    /**
     * Processes {@code count} pixels starting at {@code offset}. {@code in} and {@code out} may be
     * the same array. Alpha is preserved.
     */
    public void process(int[] in, int[] out, int offset, int count) {
        final float[] toLin = SRGB_TO_LINEAR;
        final int[] toSrgb = LINEAR_TO_SRGB;
        final float[] ratio = toneRatio;
        final float lookF = look;
        final float sat = satBoost;
        final float indexScale = (float) (TONE_N / Math.sqrt(Y_MAX));

        for (int i = offset, end = offset + count; i < end; i++) {
            int c = in[i];
            float r = toLin[(c >> 16) & 0xFF] * gainR;
            float g = toLin[(c >> 8) & 0xFF] * gainG;
            float b = toLin[c & 0xFF] * gainB;

            float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            int idx = (int) (Math.sqrt(y) * indexScale);
            if (idx > TONE_N) {
                idx = TONE_N;
            }
            float k = ratio[idx];
            float yOut = y * k;
            if (yOut > 1f) {
                yOut = 1f;
            }

            // Chroma relative to the new luminance.
            float cr = r * k - yOut;
            float cg = g * k - yOut;
            float cb = b * k - yOut;

            float max = r > g ? (r > b ? r : b) : (g > b ? g : b);
            float min = r < g ? (r < b ? r : b) : (g < b ? g : b);
            float purity = max > 1e-5f ? (max - min) / max : 0f;

            // Saturation: boosts act less on colours that are already vivid.
            float s = sat > 1f ? 1f + (sat - 1f) * (1f - purity) : sat;
            // Look: slightly restrained overall, and firmer on the most vivid colours.
            s *= 1f - lookF * (0.04f + 0.10f * purity * purity);
            // Bright tones fade towards white, as film does, instead of clipping to a flat colour.
            if (yOut > 0.7f) {
                float t = (yOut - 0.7f) / 0.3f;
                t = t * t * (3f - 2f * t);
                s *= 1f - t * (0.25f + 0.35f * lookF);
            }

            float r2 = yOut + cr * s;
            float g2 = yOut + cg * s;
            float b2 = yOut + cb * s;

            float r3 = m00 * r2 + m01 * g2 + m02 * b2;
            float g3 = m10 * r2 + m11 * g2 + m12 * b2;
            float b3 = m20 * r2 + m21 * g2 + m22 * b2;

            out[i] = (c & 0xFF000000)
                    | (toSrgb[encodeIndex(r3)] << 16)
                    | (toSrgb[encodeIndex(g3)] << 8)
                    | toSrgb[encodeIndex(b3)];
        }
    }

    private static int encodeIndex(float v) {
        if (!(v > 0f)) {
            return 0;
        }
        if (v >= 1f) {
            return ENCODE_N;
        }
        return (int) (v * ENCODE_N + 0.5f);
    }

    static double srgbToLinear(double s) {
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    static double linearToSrgb(double l) {
        return l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055;
    }

    /** Linear luminance of a packed sRGB pixel, 0..1. */
    public static float luminance(int argb) {
        return 0.2126f * SRGB_TO_LINEAR[(argb >> 16) & 0xFF]
                + 0.7152f * SRGB_TO_LINEAR[(argb >> 8) & 0xFF]
                + 0.0722f * SRGB_TO_LINEAR[argb & 0xFF];
    }

    static float linear(int channel8) {
        return SRGB_TO_LINEAR[channel8 & 0xFF];
    }
}
