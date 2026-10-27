package tv.vestor.home;

/**
 * Builds a 16:9 dock tile for an app that ships no TV banner, from the app's own launcher icon,
 * the way premium launchers do: the icon, blurred and saturated, fills the tile as a backdrop;
 * the icon itself sits in the middle at half the tile's height. An adaptive icon's background
 * layer becomes the backdrop and its foreground is drawn unmasked; a square legacy icon gets a
 * squircle mask; an icon with its own shape gets a frosted plate behind it.
 *
 * Pure pixel work on ARGB arrays, so the TV and the desktop preview share it. Runs once per app.
 */
final class Tiles {
    private Tiles() {}

    static final int W = 400, H = 225;

    /**
     * @param icon the launcher icon, n x n
     * @param bg   an adaptive icon's background layer (m x m), or null
     * @param fg   an adaptive icon's foreground layer (m x m), or null
     */
    static int[] compose(int[] icon, int n, int[] bg, int[] fg, int m) {
        int[] out = new int[W * H];
        backdrop(out, bg != null ? bg : icon, bg != null ? m : n);
        if (fg != null) {
            // Adaptive layers are 108 units with the logo inside the middle 66: drawn at 0.82 of
            // the tile's height the logo lands at about half of it, like a legacy icon.
            int s = Math.round(H * 0.82f);
            drawScaled(out, fg, m, (W - s) / 2, (H - s) / 2, s, 0);
            return out;
        }
        int s = Math.round(H * 0.5f);
        if (opaqueCorners(icon, n)) {
            drawScaled(out, icon, n, (W - s) / 2, (H - s) / 2, s, s * 0.22f);
        } else {
            int p = Math.round(H * 0.64f);
            plate(out, (W - p) / 2, (H - p) / 2, p, p * 0.24f);
            drawScaled(out, icon, n, (W - s) / 2, (H - s) / 2, s, 0);
        }
        return out;
    }

    /** The icon scaled to cover the tile, blurred, saturated and dimmed if bright. */
    private static void backdrop(int[] out, int[] src, int n) {
        int sw = 32, sh = 18;
        int[] small = new int[sw * sh];
        // Cover: the middle 16:9 band of the square source.
        float band = n * 9f / 16f, top = (n - band) / 2;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                float sx = (x + 0.5f) * n / sw - 0.5f, sy = top + (y + 0.5f) * band / sh - 0.5f;
                small[y * sw + x] = opaque(sample(src, n, n, sx, sy));
            }
        }
        Pixels.blur(small, sw, sh, 2);
        Pixels.blur(small, sw, sh, 2);
        Pixels.vibrancy(small, 1.3f, 1f);
        double lum = 0;
        for (int p : small) lum += Pixels.luminance(p);
        lum /= small.length;
        // Bright icons (white backgrounds) would glare on a dark TV: dim them, as Apple dims
        // clear glass over bright imagery, and keep every backdrop dark enough for white text.
        float dim = lum > 0.5 ? 0.35f + (float) (lum - 0.5) * 0.6f : lum > 0.25 ? 0.2f : 0.08f;
        for (int y = 0; y < H; y++) {
            float shade = dim + 0.14f * y / (H - 1f);
            for (int x = 0; x < W; x++) {
                int c = sample(small, sw, sh, (x + 0.5f) * sw / W - 0.5f, (y + 0.5f) * sh / H - 0.5f);
                out[y * W + x] = Pixels.lerp(c | 0xFF000000, 0xFF000000, shade);
            }
        }
    }

    private static int opaque(int c) {
        int a = c >>> 24;
        if (a == 255) return c;
        // Transparent parts of an icon read as a neutral dark.
        return Pixels.lerp(0xFF2C2C2E, c | 0xFF000000, a / 255f);
    }

    /** Whether the icon fills its square (a legacy icon with corners) or has its own shape. */
    private static boolean opaqueCorners(int[] px, int n) {
        if (n < 3) return true;
        int k = Math.max(1, n / 24);
        int[] at = {k * n + k, k * n + n - 1 - k, (n - 1 - k) * n + k, (n - 1 - k) * n + n - 1 - k};
        for (int i : at) if ((px[i] >>> 24) < 200) return false;
        return true;
    }

    /** A frosted plate: white at 16%, with a faint top light. */
    private static void plate(int[] out, int l, int t, int s, float rad) {
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float cov = Pixels.roundRectCoverage(x + 0.5f, y + 0.5f, s, s, rad);
                if (cov <= 0) continue;
                float a = (0.16f + 0.06f * (1 - y / (float) s)) * cov;
                int i = (t + y) * W + l + x;
                out[i] = Pixels.lerp(out[i], 0xFFFFFFFF, a);
            }
        }
    }

    /** Draws {@code src} (n x n) at size s, optionally through a rounded-square mask. */
    private static void drawScaled(int[] out, int[] src, int n, int l, int t, int s, float maskRad) {
        for (int y = 0; y < s; y++) {
            int oy = t + y;
            if (oy < 0 || oy >= H) continue;
            for (int x = 0; x < s; x++) {
                int ox = l + x;
                if (ox < 0 || ox >= W) continue;
                float cov = maskRad > 0 ? Pixels.roundRectCoverage(x + 0.5f, y + 0.5f, s, s, maskRad) : 1;
                if (cov <= 0) continue;
                int c = sample(src, n, n, (x + 0.5f) * n / s - 0.5f, (y + 0.5f) * n / s - 0.5f);
                float a = (c >>> 24) / 255f * cov;
                if (a <= 0) continue;
                int i = oy * W + ox;
                out[i] = Pixels.lerp(out[i], c | 0xFF000000, a);
            }
        }
    }

    /** Bilinear sample with premultiplied alpha, so transparent edges do not fringe dark. */
    static int sample(int[] px, int w, int h, float x, float y) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float fx = x - x0, fy = y - y0;
        float a = 0, r = 0, g = 0, b = 0;
        int[] idx = {y0 * w + x0, y0 * w + x1, y1 * w + x0, y1 * w + x1};
        float[] wt = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
        for (int k = 0; k < 4; k++) {
            int c = px[idx[k]];
            float ca = (c >>> 24) / 255f * wt[k];
            a += ca;
            r += ((c >> 16) & 255) * ca;
            g += ((c >> 8) & 255) * ca;
            b += (c & 255) * ca;
        }
        if (a <= 0.0001f) return 0;
        int ai = Math.round(a * 255);
        return ai << 24 | Math.round(r / a) << 16 | Math.round(g / a) << 8 | Math.round(b / a);
    }
}
