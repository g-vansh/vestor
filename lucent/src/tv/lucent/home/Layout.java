package tv.lucent.home;

/**
 * Where a picture lands in a box, and the checks on what the sandbox hands back. Free of Android,
 * so the desktop tests run it. {@link Decoder} lays pictures out with it in its own process, and
 * {@link Decode} accepts an answer only if it is exactly the pixels asked for, or a JPEG within
 * its byte cap.
 */
final class Layout {
    private Layout() {}

    /**
     * FIT: the whole picture, its long side at most the box's width. COVER: fills the box, the
     * overflow cropped. WHOLE: the whole picture centred over a soft, darkened, enlarged copy of
     * itself, the way a good photo frame shows a portrait.
     */
    static final int FIT = 0, COVER = 1, WHOLE = 2;

    /** A box's sides, and the most pixels the sandbox hands back raw: DCI 4K, about 35 MB. */
    static final int MIN_SIDE = 16, MAX_SIDE = 4096;
    static final long MAX_PIXELS = 4096L * 2160;

    /** A request for a JPEG: any layout, a sane box and a sane quality. */
    static boolean jpegRequest(int mode, int w, int h, int quality) {
        return (mode == FIT || mode == COVER || mode == WHOLE) && side(w) && side(h) && quality >= 50 && quality <= 95;
    }

    /** A request for raw pixels: exactly w x h, so COVER or WHOLE, and at most MAX_PIXELS. */
    static boolean pixelRequest(int mode, int w, int h) {
        return (mode == COVER || mode == WHOLE) && side(w) && side(h) && (long) w * h <= MAX_PIXELS;
    }

    private static boolean side(int n) {
        return n >= MIN_SIDE && n <= MAX_SIDE;
    }

    /** Bytes in w x h ARGB_8888 pixels. */
    static long pixelBytes(int w, int h) {
        return 4L * w * h;
    }

    /** Whether {@code length} bytes are exactly w x h pixels, the only raw answer accepted. */
    static boolean pixelsFit(long length, int w, int h) {
        return side(w) && side(h) && length == pixelBytes(w, h);
    }

    /**
     * Makes any bytes a valid premultiplied picture, with no colour above its alpha, so whatever
     * arrives is only ever colours. Android keeps ARGB_8888 as R, G, B, A bytes in memory.
     */
    static void clampToAlpha(byte[] px) {
        for (int i = 0; i + 3 < px.length; i += 4) {
            int a = px[i + 3] & 0xFF;
            if (a == 0xFF) continue;
            for (int k = i; k < i + 3; k++) if ((px[k] & 0xFF) > a) px[k] = (byte) a;
        }
    }

    /** A JPEG the sandbox wrote: more than a header, at most {@code max} bytes, starting FF D8 FF. */
    static boolean jpeg(byte[] b, int max) {
        return b != null && b.length > 3 && b.length <= max && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF;
    }

    /** JPEG starts FF D8 FF, PNG starts 89 'P' 'N' 'G' CR LF 1A LF. */
    static boolean jpegOrPng(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        int[] png = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length < png.length) return false;
        for (int i = 0; i < png.length; i++) if ((b[i] & 0xFF) != png[i]) return false;
        return true;
    }

    /** The size FIT gives an sw x sh picture: its long side at most {@code max}, never enlarged. */
    static int[] fitSize(int sw, int sh, int max) {
        float s = Math.min(1f, max / (float) Math.max(sw, sh));
        return new int[] {Math.max(1, Math.round(sw * s)), Math.max(1, Math.round(sh * s))};
    }

    /**
     * Where an upright sw x sh picture is drawn in a w x h box, as {left, top, width, height},
     * centred. WHOLE keeps the photo within 92% of the width and 90% of the height.
     */
    static float[] box(int mode, float sw, float sh, int w, int h) {
        float s = mode == COVER ? Math.max(w / sw, h / sh)
                : mode == WHOLE ? Math.min(w * 0.92f / sw, h * 0.9f / sh) : Math.min(w / sw, h / sh);
        return centred(sw * s, sh * s, w, h);
    }

    /** WHOLE: the tiny copy the backdrop is made from, 24 pixels tall in the photo's shape. */
    static int[] tiny(float sw, float sh) {
        return new int[] {Math.max(1, Math.round(sw / sh * 24)), 24};
    }

    /** WHOLE: where the tiny copy goes, enlarged to cover the box with 15% to spare, centred. */
    static float[] backdrop(int tw, int th, int w, int h) {
        float s = Math.max(w / (float) tw, h / (float) th) * 1.15f;
        return centred(tw * s, th * s, w, h);
    }

    /**
     * WHOLE: ring {@code i} (6, the widest, down to 1) of the soft shadow under the photo at
     * {@code photo}, as {left, top, right, bottom, corner radius}. Each is 5% black, stacked.
     */
    static float[] shadow(float[] photo, int i) {
        float grow = i * 4f;
        return new float[] {photo[0] - grow, photo[1] - grow + i * 2f, photo[0] + photo[2] + grow,
            photo[1] + photo[3] + grow + i * 2f, grow + 6};
    }

    /**
     * Whether an sw x sh photo is far from a w x h screen's shape, and so shown whole: portraits,
     * squares and panoramas are, 4:3 and 3:2 photos fill the screen. An unknown size fills it.
     */
    static boolean whole(int sw, int sh, int w, int h) {
        if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return false;
        float shape = (sw / (float) sh) / (w / (float) h);
        return shape < 0.7f || shape > 1.6f;
    }

    /**
     * The decoder's power-of-two sample size for an ow x oh picture as stored (turned on its
     * side when {@code sideways}) that is drawn needW x needH upright: halved while it stays at
     * least {@code factor} times that size, so the last step is a filtered draw.
     */
    static int sample(int ow, int oh, boolean sideways, float needW, float needH, int factor) {
        long nw = need(sideways ? needH : needW) * factor, nh = need(sideways ? needW : needH) * factor;
        int s = 1;
        while (s < 4096 && ow / (s * 2) >= nw && oh / (s * 2) >= nh) s *= 2;
        return s;
    }

    /** A drawn size in whole pixels, at least 1 and at most 2^30, so nothing overflows. */
    private static long need(float v) {
        return Math.max(1, Math.min(1L << 30, Math.round((double) v)));
    }

    private static float[] centred(float dw, float dh, int w, int h) {
        return new float[] {(w - dw) / 2f, (h - dh) / 2f, dw, dh};
    }
}
