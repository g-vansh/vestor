package tv.lucent.home;

/**
 * Pixel work shared by the TV and the preview: the blur behind every glass panel, the
 * vibrancy boost that keeps glass from looking grey, and pre-rendered soft shadows.
 * Everything runs once per wallpaper or per panel size, never per frame.
 */
final class Pixels {
    private Pixels() {}

    /** Three box-blur passes in each direction, which approximates a Gaussian. ARGB in place. */
    static void blur(int[] px, int w, int h, int radius) {
        if (radius < 1) return;
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, radius);
            boxV(tmp, px, w, h, radius);
        }
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, 0, w - 1)];
                sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = (int) (sa / div) << 24 | (int) (sr / div) << 16 | (int) (sg / div) << 8 | (int) (sb / div);
                int add = in[row + clamp(x + r + 1, 0, w - 1)];
                int sub = in[row + clamp(x - r, 0, w - 1)];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, 0, h - 1) * w + x];
                sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = (int) (sa / div) << 24 | (int) (sr / div) << 16 | (int) (sg / div) << 8 | (int) (sb / div);
                int add = in[clamp(y + r + 1, 0, h - 1) * w + x];
                int sub = in[clamp(y - r, 0, h - 1) * w + x];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /**
     * Raises saturation and slightly lowers brightness. Real glass concentrates the colour
     * behind it; a plain blur reads as frosted plastic. Opaque output.
     */
    static void vibrancy(int[] px, float saturation, float brightness) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = (l + (r - l) * saturation) * brightness;
            g = (l + (g - l) * saturation) * brightness;
            b = (l + (b - l) * saturation) * brightness;
            px[i] = 0xFF000000 | clamp((int) r, 0, 255) << 16 | clamp((int) g, 0, 255) << 8 | clamp((int) b, 0, 255);
        }
    }

    /**
     * Darkens the top and bottom of a wallpaper so white text and glass stay legible on a bright
     * sky or snow. Baked into the bitmap so the TV never draws a full-screen scrim per frame.
     */
    static void scrim(int[] px, int w, int h, float top, float topReach, float bottom, float bottomReach) {
        for (int y = 0; y < h; y++) {
            float fy = y / (float) (h - 1);
            float a = 0;
            if (fy < topReach) a = Math.max(a, top * smooth(1f - fy / topReach));
            if (fy > 1f - bottomReach) a = Math.max(a, bottom * smooth((fy - (1f - bottomReach)) / bottomReach));
            if (a <= 0) continue;
            float k = 1f - a;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = px[row + x];
                int r = (int) (((p >> 16) & 255) * k), g = (int) (((p >> 8) & 255) * k), b = (int) ((p & 255) * k);
                px[row + x] = (p & 0xFF000000) | r << 16 | g << 8 | b;
            }
        }
    }

    private static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Width of the blurred copy glass samples; at 1/8 of 1920 the blur reads as ~36 px. */
    static final int BLUR_W = 240, BLUR_H = 135;

    /** The wallpaper itself: a gentle scrim at the top (clock) and bottom (dock). */
    static void prepareWall(int[] px, int w, int h) {
        scrim(px, w, h, 0.30f, 0.38f, 0.34f, 0.42f);
    }

    /** The copy behind glass: blurred, then saturated, as light through real glass is. */
    static void prepareBlur(int[] px, int w, int h) {
        blur(px, w, h, 4);
        vibrancy(px, 1.3f, 0.93f);
    }

    /** Mean relative luminance (0..1) of the fractional rectangle of an image. */
    static float luminance(int[] px, int w, int h, float fl, float ft, float fr, float fb) {
        int x0 = clamp((int) (fl * w), 0, w - 1), x1 = clamp((int) (fr * w), x0 + 1, w);
        int y0 = clamp((int) (ft * h), 0, h - 1), y1 = clamp((int) (fb * h), y0 + 1, h);
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = px[y * w + x];
                sum += 0.2126 * lin((p >> 16) & 255) + 0.7152 * lin((p >> 8) & 255) + 0.0722 * lin(p & 255);
                n++;
            }
        }
        return n == 0 ? 0.2f : (float) (sum / n);
    }

    /** Relative luminance (0..1) of one colour, ignoring its alpha. */
    static float luminance(int c) {
        return (float) (0.2126 * lin((c >> 16) & 255) + 0.7152 * lin((c >> 8) & 255) + 0.0722 * lin(c & 255));
    }

    private static final double[] LINEAR = new double[256];

    static {
        for (int c = 0; c < 256; c++) {
            double v = c / 255.0;
            LINEAR[c] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
    }

    private static double lin(int c) {
        return LINEAR[c & 255];
    }

    /**
     * A soft shadow for a rounded rectangle of size w x h, drawn at {@code scale} to stay small.
     * The returned image is (w + 2*blur) x (h + 2*blur) in unscaled units; out[0] and out[1]
     * hold its scaled width and height, and the pixels follow from out[2].
     */
    static int[] shadow(float w, float h, float rad, float blur, float scale) {
        int pad = Math.max(1, Math.round(blur * scale));
        int sw = Math.max(1, Math.round(w * scale)), sh = Math.max(1, Math.round(h * scale));
        int iw = sw + pad * 2, ih = sh + pad * 2;
        float r = rad * scale;
        int[] px = new int[iw * ih];
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                float cov = coverage(x + 0.5f - pad, y + 0.5f - pad, sw, sh, r);
                px[y * iw + x] = Math.round(cov * 255) << 24;
            }
        }
        blur(px, iw, ih, Math.max(1, pad / 2));
        int[] out = new int[px.length + 2];
        out[0] = iw;
        out[1] = ih;
        System.arraycopy(px, 0, out, 2, px.length);
        return out;
    }

    /** Anti-aliased coverage (0..1) of point (x, y) by a w x h rounded rectangle at the origin. */
    static float roundRectCoverage(float x, float y, float w, float h, float r) {
        return coverage(x, y, w, h, r);
    }

    /** Anti-aliased coverage of a pixel centre by a rounded rectangle at the origin. */
    private static float coverage(float x, float y, float w, float h, float r) {
        float hx = w / 2f, hy = h / 2f;
        r = Math.min(r, Math.min(hx, hy));
        float qx = Math.abs(x - hx) - (hx - r), qy = Math.abs(y - hy) - (hy - r);
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        float sd = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
        return Math.max(0, Math.min(1, 0.5f - sd));
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** {@code color} with its alpha multiplied by {@code a} (0..1). */
    static int alpha(int color, float a) {
        int al = Math.round(((color >>> 24) & 255) * Math.max(0, Math.min(1, a)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    static int white(float a) {
        return alpha(0xFFFFFFFF, a);
    }

    static int lerp(int c0, int c1, float t) {
        t = Math.max(0, Math.min(1, t));
        int a = Math.round(((c0 >>> 24) & 255) + (((c1 >>> 24) & 255) - ((c0 >>> 24) & 255)) * t);
        int r = Math.round(((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t);
        int g = Math.round(((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t);
        int b = Math.round((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
