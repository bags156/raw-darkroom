package io.github.bags156.rawdarkroom.core;

/**
 * A small, blurred brightness map of a photo: "how bright is the area around this pixel?".
 *
 * <p>The pipeline uses it to lift shadows and hold back highlights by region rather than by
 * pixel. A dark doorway is lifted as a whole, so the detail inside it keeps its contrast; a
 * bright sky is held back as a whole, so clouds keep their shape. That is the difference between
 * a natural result and the flat, grey look of a purely global shadows/highlights curve.
 *
 * <p>Coordinates are normalised (0..1), so a map built from a small preview can be used when
 * processing the same photo at full resolution.
 */
public final class LocalMap {

    /** Cells along the longer side. */
    private static final int LONG_SIDE = 192;

    final int width;
    final int height;
    /** Perceptual brightness per cell, 0..1. */
    final float[] values;

    private LocalMap(int width, int height, float[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    /** Builds the map from packed sRGB ARGB pixels. Returns null for an empty image. */
    public static LocalMap build(int[] pixels, int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || pixels.length < imageWidth * imageHeight) {
            return null;
        }
        int cell = Math.max(1, (Math.max(imageWidth, imageHeight) + LONG_SIDE - 1) / LONG_SIDE);
        int w = (imageWidth + cell - 1) / cell;
        int h = (imageHeight + cell - 1) / cell;
        float[] sum = new float[w * h];
        int[] count = new int[w * h];
        for (int y = 0; y < imageHeight; y++) {
            int row = y * imageWidth;
            int cellRow = (y / cell) * w;
            for (int x = 0; x < imageWidth; x++) {
                int i = cellRow + x / cell;
                sum[i] += luma(pixels[row + x]);
                count[i]++;
            }
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] = count[i] > 0 ? sum[i] / count[i] : 0f;
        }
        // Three box blurs approximate a Gaussian. The radius is a few percent of the frame, wide
        // enough to describe regions rather than edges.
        int radius = Math.max(1, Math.round(Math.max(w, h) * 0.025f));
        float[] tmp = new float[sum.length];
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(sum, tmp, w, h, radius, true);
            boxBlur(tmp, sum, w, h, radius, false);
        }
        return new LocalMap(w, h, sum);
    }

    /** Perceptual brightness of a packed sRGB pixel, 0..1 (gamma-encoded luma). */
    static float luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (54 * r + 183 * g + 19 * b) * (1f / (256f * 255f));
    }

    private static void boxBlur(float[] in, float[] out, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w;
        int length = horizontal ? w : h;
        int stride = horizontal ? 1 : w;
        float norm = 1f / (2 * radius + 1);
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            float acc = 0f;
            for (int k = -radius; k <= radius; k++) {
                acc += in[base + clamp(k, length) * stride];
            }
            for (int i = 0; i < length; i++) {
                out[base + i * stride] = acc * norm;
                acc += in[base + clamp(i + radius + 1, length) * stride]
                        - in[base + clamp(i - radius, length) * stride];
            }
        }
    }

    private static int clamp(int i, int length) {
        return i < 0 ? 0 : (i >= length ? length - 1 : i);
    }

    /** Bilinear sample at normalised image coordinates. */
    float sample(float u, float v) {
        float fx = u * width - 0.5f;
        float fy = v * height - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float ax = fx - x0;
        float ay = fy - y0;
        int x1 = clamp(x0 + 1, width);
        int y1 = clamp(y0 + 1, height);
        x0 = clamp(x0, width);
        y0 = clamp(y0, height);
        float top = values[y0 * width + x0] * (1 - ax) + values[y0 * width + x1] * ax;
        float bottom = values[y1 * width + x0] * (1 - ax) + values[y1 * width + x1] * ax;
        return top * (1 - ay) + bottom * ay;
    }
}
