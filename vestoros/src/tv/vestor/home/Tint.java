package tv.vestor.home;

import android.graphics.Bitmap;

/**
 * A dark, representative colour for an app's art, used behind transparent banners and icons.
 * Hue-binned so a large neutral background does not win over the brand colour. Adapted from
 * dumbtv's FocusColor (MIT).
 */
final class Tint {
    static final int FALLBACK = 0xFF23262E;

    private Tint() {}

    static int dark(Bitmap art) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(art, 48, 48, true);
            int[] px = new int[48 * 48];
            small.getPixels(px, 0, 48, 0, 0, 48, 48);
            if (small != art) small.recycle();
            return representative(px);
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }

    static int representative(int[] pixels) {
        float[] weights = new float[12], red = new float[12], green = new float[12], blue = new float[12];
        int visible = 0;
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            if (alpha < 96) continue;
            int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            float saturation = max == 0 ? 0 : (max - min) / (float) max;
            float hue;
            if (max == min) hue = 0;
            else if (max == r) hue = (g - b) / (float) (max - min);
            else if (max == g) hue = 2f + (b - r) / (float) (max - min);
            else hue = 4f + (r - g) / (float) (max - min);
            int bin = saturation < 0.12f ? 0 : 1 + ((int) ((hue + 6f) % 6f * 11f / 6f));
            float weight = (alpha / 255f) * (saturation < 0.12f ? 0.12f : 0.5f + saturation * 1.5f);
            weights[bin] += weight;
            red[bin] += r * weight;
            green[bin] += g * weight;
            blue[bin] += b * weight;
            visible++;
        }
        if (visible == 0) return FALLBACK;
        int best = 0;
        for (int i = 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
        if (weights[best] == 0) return FALLBACK;
        float r = red[best] / weights[best], g = green[best] / weights[best], b = blue[best] / weights[best];
        float scale = 1f;
        while (luminance(r * scale, g * scale, b * scale) > 0.1f && scale > 0.05f) scale *= 0.9f;
        return 0xFF000000 | Math.round(r * scale) << 16 | Math.round(g * scale) << 8 | Math.round(b * scale);
    }

    private static float luminance(float r, float g, float b) {
        return 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b);
    }

    private static float lin(float c) {
        float v = c / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }
}
