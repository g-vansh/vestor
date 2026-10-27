package tv.lucent.home;

import java.util.Random;

/**
 * Checks the sandbox's geometry and the main process's checks on its answers ({@link Layout}):
 * cover crops, the whole-photo frame against the drawing Walls did before it moved into the
 * sandbox, the whole or cropped rule, decoder sample sizes, request limits, the exact byte count
 * for raw pixels, the JPEG cap and signature, and the alpha clamp that keeps any bytes harmless.
 */
public final class LayoutTest {
    private static int checks, failures;
    private static final float EPS = 0.01f;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20261027L;
        requests();
        pixelBytes();
        clamp(new Random(seed));
        jpegs();
        fit();
        cover(new Random(seed + 1));
        whole(new Random(seed + 2));
        rule();
        sample(new Random(seed + 3));
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void ok(String what, boolean cond) {
        eq(what, true, cond);
    }

    private static void near(String what, float want, float got) {
        checks++;
        if (!(Math.abs(want - got) <= EPS * Math.max(1f, Math.abs(want)))) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void rect(String what, float[] want, float[] got) {
        eq(what + " length", want.length, got.length);
        for (int i = 0; i < Math.min(want.length, got.length); i++) near(what + " [" + i + "]", want[i], got[i]);
    }

    /** What the sandbox agrees to make. */
    private static void requests() {
        ok("jpeg FIT photo", Layout.jpegRequest(Layout.FIT, 3840, 3840, 88));
        ok("jpeg FIT thumbnail", Layout.jpegRequest(Layout.FIT, 480, 480, 80));
        ok("jpeg COVER tile", Layout.jpegRequest(Layout.COVER, 400, 225, 90));
        ok("jpeg WHOLE", Layout.jpegRequest(Layout.WHOLE, 1920, 1080, 90));
        ok("jpeg unknown mode", !Layout.jpegRequest(3, 1920, 1080, 90));
        ok("jpeg negative mode", !Layout.jpegRequest(-1, 1920, 1080, 90));
        ok("jpeg side 15", !Layout.jpegRequest(Layout.FIT, 15, 480, 80));
        ok("jpeg side 4097", !Layout.jpegRequest(Layout.FIT, 4097, 480, 80));
        ok("jpeg quality 49", !Layout.jpegRequest(Layout.FIT, 480, 480, 49));
        ok("jpeg quality 96", !Layout.jpegRequest(Layout.FIT, 480, 480, 96));
        ok("jpeg quality 50", Layout.jpegRequest(Layout.FIT, 480, 480, 50));
        ok("jpeg quality 95", Layout.jpegRequest(Layout.FIT, 480, 480, 95));

        ok("pixels COVER 1080p", Layout.pixelRequest(Layout.COVER, 1920, 1080));
        ok("pixels WHOLE 1080p", Layout.pixelRequest(Layout.WHOLE, 1920, 1080));
        ok("pixels COVER tile", Layout.pixelRequest(Layout.COVER, Tiles.W, Tiles.H));
        ok("pixels UHD", Layout.pixelRequest(Layout.WHOLE, 3840, 2160));
        ok("pixels DCI 4K", Layout.pixelRequest(Layout.COVER, 4096, 2160));
        ok("pixels DCI 4K on its side", Layout.pixelRequest(Layout.COVER, 2160, 4096));
        ok("pixels past DCI 4K", !Layout.pixelRequest(Layout.COVER, 4096, 2161));
        ok("pixels 4096 square", !Layout.pixelRequest(Layout.COVER, 4096, 4096));
        ok("pixels FIT has no fixed size", !Layout.pixelRequest(Layout.FIT, 1920, 1080));
        ok("pixels unknown mode", !Layout.pixelRequest(7, 1920, 1080));
        ok("pixels side 15", !Layout.pixelRequest(Layout.COVER, 15, 1080));
        ok("pixels side 0", !Layout.pixelRequest(Layout.COVER, 0, 0));
        ok("pixels negative", !Layout.pixelRequest(Layout.COVER, -1920, -1080));
        ok("pixels overflowing product", !Layout.pixelRequest(Layout.COVER, 65536, 65536));
    }

    /** Raw answers are accepted only at exactly w x h x 4 bytes. */
    private static void pixelBytes() {
        eq("1080p bytes", 8294400L, Layout.pixelBytes(1920, 1080));
        eq("tile bytes", 360000L, Layout.pixelBytes(400, 225));
        eq("4096 square bytes, no overflow", 67108864L, Layout.pixelBytes(4096, 4096));
        eq("65536 square bytes, no overflow", 17179869184L, Layout.pixelBytes(65536, 65536));
        ok("exact", Layout.pixelsFit(8294400L, 1920, 1080));
        for (long d : new long[] {-4, -1, 1, 3, 4, 8294400L}) {
            ok("off by " + d, !Layout.pixelsFit(8294400L + d, 1920, 1080));
        }
        ok("empty", !Layout.pixelsFit(0, 1920, 1080));
        ok("negative length", !Layout.pixelsFit(-8294400L, 1920, 1080));
        ok("rows swapped is the same count", Layout.pixelsFit(8294400L, 1080, 1920));
        ok("zero box", !Layout.pixelsFit(0, 0, 0));
        ok("box past the sides", !Layout.pixelsFit(4L * 40000 * 40000, 40000, 40000));
        ok("int-wrapped length", !Layout.pixelsFit((4 * 1920 * 1080) & 0xFFFFL, 1920, 1080));
    }

    /** Whatever bytes arrive become a valid premultiplied picture. */
    private static void clamp(Random rnd) {
        byte[] opaque = new byte[4 * 1000];
        rnd.nextBytes(opaque);
        for (int i = 3; i < opaque.length; i += 4) opaque[i] = (byte) 0xFF;
        byte[] copy = opaque.clone();
        Layout.clampToAlpha(opaque);
        eq("opaque pixels untouched", java.util.Arrays.toString(copy), java.util.Arrays.toString(opaque));

        byte[] clear = {(byte) 200, 100, 50, 0};
        Layout.clampToAlpha(clear);
        eq("alpha 0 clears colour", "[0, 0, 0, 0]", java.util.Arrays.toString(clear));

        byte[] half = {(byte) 200, 100, 50, (byte) 128, 1, 2, 3, (byte) 255};
        Layout.clampToAlpha(half);
        eq("colour above alpha clamped", "[-128, 100, 50, -128, 1, 2, 3, -1]", java.util.Arrays.toString(half));

        byte[] ragged = {(byte) 255, (byte) 255, (byte) 255, 10, (byte) 255, (byte) 255};
        Layout.clampToAlpha(ragged);
        eq("trailing partial pixel left alone", "[10, 10, 10, 10, -1, -1]", java.util.Arrays.toString(ragged));
        Layout.clampToAlpha(new byte[0]);
        ok("empty is fine", true);

        byte[] noise = new byte[4 * 50000];
        rnd.nextBytes(noise);
        Layout.clampToAlpha(noise);
        boolean valid = true;
        for (int i = 0; i < noise.length; i += 4) {
            int a = noise[i + 3] & 0xFF;
            for (int k = 0; k < 3; k++) valid &= (noise[i + k] & 0xFF) <= a;
        }
        ok("random bytes become valid premultiplied pixels", valid);
        byte[] again = noise.clone();
        Layout.clampToAlpha(again);
        ok("clamping is idempotent", java.util.Arrays.equals(noise, again));
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    /** The JPEG cap and signature, and the input sniffing the sandbox does before any decoder. */
    private static void jpegs() {
        byte[] jfif = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F');
        ok("jpeg accepted", Layout.jpeg(jfif, 1024));
        ok("jpeg at the cap", Layout.jpeg(jfif, jfif.length));
        ok("jpeg past the cap", !Layout.jpeg(jfif, jfif.length - 1));
        ok("null", !Layout.jpeg(null, 1024));
        ok("empty", !Layout.jpeg(new byte[0], 1024));
        ok("three bytes", !Layout.jpeg(bytes(0xFF, 0xD8, 0xFF), 1024));
        ok("no third FF", !Layout.jpeg(bytes(0xFF, 0xD8, 0x00, 0xE0), 1024));
        ok("PNG is not a JPEG", !Layout.jpeg(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), 1024));
        ok("zero cap", !Layout.jpeg(jfif, 0));

        ok("sniff JPEG", Layout.jpegOrPng(jfif));
        ok("sniff PNG", Layout.jpegOrPng(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)));
        ok("sniff PNG prefix only", !Layout.jpegOrPng(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A)));
        ok("sniff GIF", !Layout.jpegOrPng("GIF89a....".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        ok("sniff WebP", !Layout.jpegOrPng("RIFF\0\0\0\0WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        ok("sniff HEIF", !Layout.jpegOrPng(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c')));
        ok("sniff empty", !Layout.jpegOrPng(new byte[0]));
        ok("sniff two bytes", !Layout.jpegOrPng(bytes(0xFF, 0xD8)));
    }

    /** FIT: long side at most the cap, never enlarged, never zero. */
    private static void fit() {
        eq("phone photo to 4K", "[3840, 2880]", java.util.Arrays.toString(Layout.fitSize(4032, 3024, 3840)));
        eq("portrait to 4K", "[2880, 3840]", java.util.Arrays.toString(Layout.fitSize(3024, 4032, 3840)));
        eq("small kept", "[1000, 500]", java.util.Arrays.toString(Layout.fitSize(1000, 500, 3840)));
        eq("thumbnail", "[480, 360]", java.util.Arrays.toString(Layout.fitSize(3840, 2880, 480)));
        eq("thin strip keeps a pixel", "[1, 480]", java.util.Arrays.toString(Layout.fitSize(16, 12000, 480)));
        rect("FIT box fills the fitted size", new float[] {0, 0, 480, 360}, Layout.box(Layout.FIT, 3840, 2880, 480, 360));
        rect("FIT box centres a near miss", new float[] {0, 0.5f, 480, 359}, Layout.box(Layout.FIT, 480, 359, 480, 360));
    }

    /** COVER: fills the box, crops the overflow evenly, keeps the shape, as Walls and Catalog did. */
    private static void cover(Random rnd) {
        rect("4:3 on 16:9", new float[] {0, -180, 1920, 1440}, Layout.box(Layout.COVER, 4000, 3000, 1920, 1080));
        rect("portrait on 16:9", new float[] {0, -1166.6666f, 1920, 3413.3333f}, Layout.box(Layout.COVER, 2160, 3840, 1920, 1080));
        rect("square on a tile", new float[] {0, -87.5f, 400, 400}, Layout.box(Layout.COVER, 1000, 1000, 400, 225));
        rect("panorama on a tile", new float[] {-137.5f, 0, 675, 225}, Layout.box(Layout.COVER, 3000, 1000, 400, 225));
        for (int i = 0; i < 20000; i++) {
            int sw = 16 + rnd.nextInt(12000), sh = 16 + rnd.nextInt(12000);
            int w = 16 + rnd.nextInt(4081), h = 16 + rnd.nextInt(4081);
            float[] r = Layout.box(Layout.COVER, sw, sh, w, h);
            String at = sw + "x" + sh + " in " + w + "x" + h;
            // The old drawing, from Walls and Catalog: scale by the larger ratio, centre.
            float scale = Math.max(w / (float) sw, h / (float) sh);
            float[] old = {(w - sw * scale) / 2f, (h - sh * scale) / 2f, sw * scale, sh * scale};
            boolean same = true;
            for (int k = 0; k < 4; k++) same &= Math.abs(old[k] - r[k]) <= EPS * Math.max(1f, Math.abs(old[k]));
            boolean covers = r[0] <= EPS && r[1] <= EPS && r[0] + r[2] >= w - EPS * w && r[1] + r[3] >= h - EPS * h;
            boolean centred = Math.abs(r[0] - (w - r[0] - r[2])) <= EPS * w && Math.abs(r[1] - (h - r[1] - r[3])) <= EPS * h;
            boolean shape = Math.abs(r[2] / r[3] - sw / (float) sh) <= 1e-3f * (sw / (float) sh);
            boolean tight = Math.abs(r[2] - w) <= EPS * w || Math.abs(r[3] - h) <= EPS * h;
            if (!(same && covers && centred && shape && tight)) {
                ok("cover " + at + " same " + same + " covers " + covers + " centred " + centred + " shape " + shape, false);
                return;
            }
        }
        ok("20000 random covers match the old drawing", true);
    }

    /**
     * WHOLE: the photo within 92% by 90%, centred, over the tiny copy enlarged 15% past covering
     * the box, with six shadow rings. Checked against the drawing Walls.load did itself before.
     */
    private static void whole(Random rnd) {
        float[] photo = Layout.box(Layout.WHOLE, 3000, 4000, 1920, 1080);
        rect("portrait photo", new float[] {595.5f, 54, 729, 972}, photo);
        eq("portrait tiny", "[18, 24]", java.util.Arrays.toString(Layout.tiny(3000, 4000)));
        float bs = 1920 / 18f * 1.15f;
        rect("portrait backdrop", new float[] {(1920 - 18 * bs) / 2, (1080 - 24 * bs) / 2, 18 * bs, 24 * bs},
                Layout.backdrop(18, 24, 1920, 1080));
        rect("widest ring", new float[] {595.5f - 24, 54 - 24 + 12, 595.5f + 729 + 24, 54 + 972 + 24 + 12, 30}, Layout.shadow(photo, 6));
        rect("narrowest ring", new float[] {595.5f - 4, 54 - 4 + 2, 595.5f + 729 + 4, 54 + 972 + 4 + 2, 10}, Layout.shadow(photo, 1));
        rect("square photo", new float[] {474, 54, 972, 972}, Layout.box(Layout.WHOLE, 2000, 2000, 1920, 1080));
        rect("panorama photo", new float[] {76.8f, 300, 1766.4f, 480}, Layout.box(Layout.WHOLE, 11040, 3000, 1920, 1080));
        eq("thin strip tiny keeps a pixel", "[1, 24]", java.util.Arrays.toString(Layout.tiny(16, 12000)));
        eq("long panorama tiny", "[18000, 24]", java.util.Arrays.toString(Layout.tiny(12000, 16)));

        for (int i = 0; i < 20000; i++) {
            float sw = 16 + rnd.nextInt(12000), sh = 16 + rnd.nextInt(12000);
            int w = 16 + rnd.nextInt(4081), h = 16 + rnd.nextInt(4081);
            String at = sw + "x" + sh + " in " + w + "x" + h;
            // The old drawing, from Walls.load.
            float fs = Math.min(w * 0.92f / sw, h * 0.9f / sh);
            float fw = sw * fs, fh = sh * fs, fl = (w - fw) / 2f, ft = (h - fh) / 2f;
            int tw = Math.max(1, Math.round(sw / sh * 24));
            float obs = Math.max(w / (float) tw, h / 24f) * 1.15f;
            float[] oldBack = {(w - tw * obs) / 2f, (h - 24 * obs) / 2f, tw * obs, 24 * obs};

            float[] p = Layout.box(Layout.WHOLE, sw, sh, w, h);
            int[] t = Layout.tiny(sw, sh);
            float[] b = Layout.backdrop(t[0], t[1], w, h);
            boolean same = close(new float[] {fl, ft, fw, fh}, p) && t[0] == tw && t[1] == 24 && close(oldBack, b);
            for (int ring = 6; ring >= 1 && same; ring--) {
                float grow = ring * 4f;
                same = close(new float[] {fl - grow, ft - grow + ring * 2f, fl + fw + grow, ft + fh + grow + ring * 2f, grow + 6},
                        Layout.shadow(p, ring));
            }
            boolean inside = p[0] >= w * 0.04f - EPS * w && p[1] >= h * 0.05f - EPS * h && p[2] <= w * 0.92f + EPS * w
                    && p[3] <= h * 0.9f + EPS * h;
            boolean backCovers = b[0] < 0 && b[1] < 0 && b[0] + b[2] > w && b[1] + b[3] > h;
            if (!(same && inside && backCovers)) {
                ok("whole " + at + " same " + same + " inside " + inside + " backdrop covers " + backCovers, false);
                return;
            }
        }
        ok("20000 random whole layouts match the old drawing", true);
    }

    private static boolean close(float[] want, float[] got) {
        if (want.length != got.length) return false;
        for (int i = 0; i < want.length; i++) if (!(Math.abs(want[i] - got[i]) <= EPS * Math.max(1f, Math.abs(want[i])))) return false;
        return true;
    }

    /** Portraits, squares and panoramas are shown whole, 4:3 and 3:2 cropped, on any 16:9 screen. */
    private static void rule() {
        for (int[] screen : new int[][] {{1920, 1080}, {1280, 720}, {3840, 2160}}) {
            int w = screen[0], h = screen[1];
            String on = " on " + w + "x" + h;
            ok("4:3 cropped" + on, !Layout.whole(4000, 3000, w, h));
            ok("3:2 cropped" + on, !Layout.whole(6000, 4000, w, h));
            ok("16:9 cropped" + on, !Layout.whole(3840, 2160, w, h));
            ok("2:1 cropped" + on, !Layout.whole(4000, 2000, w, h));
            ok("5:4 cropped" + on, !Layout.whole(5000, 4000, w, h));
            ok("square whole" + on, Layout.whole(3000, 3000, w, h));
            ok("3:4 portrait whole" + on, Layout.whole(3000, 4000, w, h));
            ok("9:16 portrait whole" + on, Layout.whole(2160, 3840, w, h));
            ok("3:1 panorama whole" + on, Layout.whole(6000, 2000, w, h));
            ok("unknown size cropped" + on, !Layout.whole(0, 0, w, h));
            ok("negative size cropped" + on, !Layout.whole(-3000, 4000, w, h));
        }
        ok("no screen", !Layout.whole(3000, 4000, 0, 0));
        // The old rule, from Walls: shape against the screen's, below 0.7 or above 1.6.
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            int sw = 1 + rnd.nextInt(12000), sh = 1 + rnd.nextInt(12000), w = 16 + rnd.nextInt(4081), h = 16 + rnd.nextInt(4081);
            float shape = ((float) sw / sh) / (w / (float) h);
            if (Layout.whole(sw, sh, w, h) != (shape < 0.7f || shape > 1.6f)) {
                ok("rule " + sw + "x" + sh + " on " + w + "x" + h, false);
                return;
            }
        }
        ok("20000 random shapes follow the old rule", true);
    }

    /** Decoder sample sizes: powers of two that never take the picture below the size drawn. */
    private static void sample(Random rnd) {
        eq("4K album photo to a 1080p wall", 2, Layout.sample(3840, 2160, false, 1920, 1080, 1));
        eq("4K album photo to a 1080p wall, as a JPEG", 1, Layout.sample(3840, 2160, false, 1920, 1080, 2));
        eq("4:3 album photo to a 1080p wall", 2, Layout.sample(3840, 2880, false, 1920, 1440, 1));
        eq("phone photo to the album", 1, Layout.sample(4032, 3024, false, 3840, 2880, 2));
        eq("album photo to a thumbnail", 4, Layout.sample(3840, 2880, false, 480, 360, 2));
        eq("sideways phone photo", 2, Layout.sample(3024, 4032, true, 1920, 1440, 1));
        eq("sideways the other way round is less", 1, Layout.sample(3024, 4032, false, 1920, 1440, 1));
        eq("tile from a big banner", 8, Layout.sample(4000, 2250, false, 400, 225, 1));
        eq("smaller than drawn", 1, Layout.sample(200, 100, false, 400, 225, 1));
        eq("nothing to draw stops at the cap", 4096, Layout.sample(12000, 12000, false, 0, 0, 1));
        eq("huge source stops at the cap", 4096, Layout.sample(Integer.MAX_VALUE, Integer.MAX_VALUE, false, 1, 1, 1));
        eq("huge need", 1, Layout.sample(12000, 12000, false, 1e30f, 1e30f, 2));
        eq("NaN need", 1, Layout.sample(12000, 12000, false, Float.NaN, Float.NaN, 1) >= 1 ? 1 : 0);

        for (int i = 0; i < 50000; i++) {
            int ow = 16 + rnd.nextInt(12000), oh = 16 + rnd.nextInt(12000), factor = 1 + rnd.nextInt(2);
            boolean side = rnd.nextBoolean();
            float nw = 1 + rnd.nextFloat() * 4096, nh = 1 + rnd.nextFloat() * 4096;
            int s = Layout.sample(ow, oh, side, nw, nh, factor);
            // In stored orientation, what the decoder must still deliver.
            long needW = Math.round(side ? nh : nw) * (long) factor, needH = Math.round(side ? nw : nh) * (long) factor;
            boolean power = s >= 1 && (s & (s - 1)) == 0;
            boolean enough = s == 1 || ow / s >= needW && oh / s >= needH;
            boolean least = s >= 4096 || ow / (s * 2) < needW || oh / (s * 2) < needH;
            if (!(power && enough && least)) {
                ok("sample " + ow + "x" + oh + " need " + nw + "x" + nh + " x" + factor + " sideways " + side + " gave " + s, false);
                return;
            }
        }
        ok("50000 random sample sizes are the largest that still cover", true);
    }
}
