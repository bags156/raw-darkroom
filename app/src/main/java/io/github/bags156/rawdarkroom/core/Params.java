package io.github.bags156.rawdarkroom.core;

/**
 * Everything that describes one edit. All sliders are neutral at 0 except {@link #look}, which is
 * the strength of the film-style look (0 = off, 1 = full).
 */
public final class Params {
    /** Exposure in stops, -3..+3. */
    public float exposure;
    /** -1..+1. */
    public float contrast;
    /** -1..+1. Negative pulls bright tones down; white itself stays white. */
    public float highlights;
    /** -1..+1. Positive lifts dark tones; black itself stays black. */
    public float shadows;
    /** -1..+1. Positive is warmer (more red, less blue). */
    public float warmth;
    /** -1..+1. Positive is more magenta, negative more green. */
    public float tint;
    /** -1..+1. */
    public float saturation;
    /** 0..1. */
    public float look = DEFAULT_LOOK;

    public static final float DEFAULT_LOOK = 0.7f;

    public Params copy() {
        Params p = new Params();
        p.exposure = exposure;
        p.contrast = contrast;
        p.highlights = highlights;
        p.shadows = shadows;
        p.warmth = warmth;
        p.tint = tint;
        p.saturation = saturation;
        p.look = look;
        return p;
    }

    /** Returns a copy with every value forced into its legal range. */
    public Params clamped() {
        Params p = copy();
        p.exposure = clamp(exposure, -3f, 3f);
        p.contrast = clamp(contrast, -1f, 1f);
        p.highlights = clamp(highlights, -1f, 1f);
        p.shadows = clamp(shadows, -1f, 1f);
        p.warmth = clamp(warmth, -1f, 1f);
        p.tint = clamp(tint, -1f, 1f);
        p.saturation = clamp(saturation, -1f, 1f);
        p.look = clamp(look, 0f, 1f);
        return p;
    }

    static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) {
            return lo > 0f ? lo : Math.min(hi, 0f);
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
