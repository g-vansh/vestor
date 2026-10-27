package tv.vestor.home;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Checks the QR encoder against values from the standard, then (given an output directory)
 * writes every test code as a PNG with a 4 module margin, plus manifest.tsv listing each file,
 * its expected version, forced mask and payload as hex, so an independent decoder can read
 * every one back and compare.
 */
public final class QrTest {
    private static int failures, checks;
    private static final String PAIR = "http://192.168.1.23:8686/#p=";
    private static final String B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** Data codewords plus error correction codewords, by version, from the standard's table. */
    private static final int[] TOTAL = {
        0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815, 901, 991, 1085,
    };
    /** Byte mode capacity at level M, by version, from the standard's table. */
    private static final int[] BYTES_M = {
        0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213, 251, 287, 331, 362, 412, 450, 504, 560, 624, 666,
    };

    public static void main(String[] args) throws Exception {
        format();
        version();
        reedSolomon();
        layout();
        capacity();
        penalty();
        structure();
        speed();
        if (args.length > 0) images(Paths.get(args[0]));
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

    private static void throwsIae(String what, Runnable r) {
        checks++;
        try {
            r.run();
            failures++;
            System.out.println("FAIL " + what + ": no exception");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    /** Format information for level M and masks 0 to 7, as listed in the standard. */
    private static void format() {
        int[] want = {0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0};
        for (int m = 0; m < 8; m++) eq("format bits M mask " + m, want[m], Qr.formatBits(m));
    }

    /** Version information for versions 7 to 20, as listed in the standard. */
    private static void version() {
        int[] want = {0x07C94, 0x085BC, 0x09A99, 0x0A4D3, 0x0BBF6, 0x0C762, 0x0D847, 0x0E60D, 0x0F928, 0x10B78,
            0x1145D, 0x12A17, 0x13532, 0x149A6};
        for (int v = 7; v <= 20; v++) eq("version bits " + v, want[v - 7], Qr.versionBits(v));
    }

    private static void reedSolomon() {
        eq("generator 2", Arrays.toString(new int[] {1, 3, 2}), Arrays.toString(Qr.generator(2)));
        // Generators as powers of 2, from the standard's annex.
        eq("generator 7 exponents", "[0, 87, 229, 146, 149, 238, 102, 21]", Arrays.toString(logs(Qr.generator(7))));
        eq("generator 10 exponents", "[0, 251, 67, 46, 61, 118, 70, 64, 94, 32, 45]", Arrays.toString(logs(Qr.generator(10))));
        // The worked example "01234567" at 1-M in the standard.
        int[] d1 = {16, 32, 12, 86, 97, 128, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17};
        eq("ecc 01234567", "[165, 36, 212, 193, 237, 54, 199, 135, 44, 85]", Arrays.toString(Qr.remainder(d1, Qr.generator(10))));
        // The widely used HELLO WORLD 1-M example.
        int[] d2 = {32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17};
        eq("ecc HELLO WORLD", "[196, 35, 39, 119, 235, 215, 231, 226, 93, 23]", Arrays.toString(Qr.remainder(d2, Qr.generator(10))));
        eq("ecc of zeros", "[0, 0, 0, 0]", Arrays.toString(Qr.remainder(new int[5], Qr.generator(4))));
    }

    private static int[] logs(int[] poly) {
        int[] out = new int[poly.length];
        for (int i = 0; i < poly.length; i++) {
            int x = 1, e = 0;
            while (x != poly[i]) {
                x <<= 1;
                if (x > 0xFF) x ^= 0x11D;
                e++;
            }
            out[i] = e;
        }
        return out;
    }

    /** Function patterns leave exactly the codewords plus the standard's remainder bits. */
    private static void layout() {
        for (int v = 1; v <= Qr.MAX_VERSION; v++) {
            int rem = v == 1 ? 0 : v <= 6 ? 7 : v <= 13 ? 0 : 3;
            eq("data modules v" + v, TOTAL[v] * 8 + rem, Qr.dataModules(v));
            eq("size v" + v, 17 + 4 * v, Qr.size(v));
        }
    }

    private static void capacity() {
        for (int v = 1; v <= Qr.MAX_VERSION; v++) {
            eq("capacity v" + v, BYTES_M[v], Qr.capacity(v));
            eq("fits v" + v, v, Qr.version(BYTES_M[v]));
            if (v < Qr.MAX_VERSION) eq("spills past v" + v, v + 1, Qr.version(BYTES_M[v] + 1));
            eq("encoded size at capacity v" + v, Qr.size(v), Qr.encode(repeat('a', BYTES_M[v])).length);
        }
        eq("empty", 21, Qr.encode("").length);
        eq("666 bytes of euro signs", 97, Qr.encode(repeat('€', 222)).length);
        throwsIae("667 bytes", new Runnable() {
            @Override public void run() {
                Qr.encode(repeat('a', 667));
            }
        });
        throwsIae("669 bytes of euro signs", new Runnable() {
            @Override public void run() {
                Qr.encode(repeat('€', 223));
            }
        });
        throwsIae("null", new Runnable() {
            @Override public void run() {
                Qr.encode((String) null);
            }
        });
        throwsIae("mask 8", new Runnable() {
            @Override public void run() {
                Qr.encode(new byte[1], 8);
            }
        });
    }

    private static void penalty() {
        boolean[][] blank = new boolean[21][21];
        // 42 lines of 21 alike score 19 each, 400 blocks score 3 each, and 0% dark scores 100.
        eq("penalty all light", 42 * 19 + 400 * 3 + 100, Qr.penalty(blank));
        boolean[][] chess = new boolean[21][21];
        for (int y = 0; y < 21; y++) {
            for (int x = 0; x < 21; x++) chess[y][x] = (x + y) % 2 == 0;
        }
        eq("penalty checkerboard", 0, Qr.penalty(chess));
        boolean[][] finders = new boolean[7][7];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) finders[y][x] = x != 1 && x != 5;
        }
        // Seven finder-like rows at 40, seven uniform columns at 5, 12 blocks at 3, 71% dark at 40.
        eq("penalty finder rows", 7 * 40 + 7 * 5 + 12 * 3 + 40, Qr.penalty(finders));
    }

    /** Finders, timing, dark module, both format copies, version copies, and the mask choice. */
    private static void structure() {
        String[] texts = {"Vestor", PAIR + b64(new Random(1), 43), repeat('x', 150), repeat('y', 400), repeat('z', 666)};
        for (String t : texts) {
            byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
            boolean[][] m = Qr.encode(t);
            int v = Qr.version(bytes.length), n = m.length;
            String tag = " (" + bytes.length + " bytes)";
            eq("size" + tag, Qr.size(v), n);
            ok("finder top left" + tag, finderAt(m, 0, 0));
            ok("finder top right" + tag, finderAt(m, n - 7, 0));
            ok("finder bottom left" + tag, finderAt(m, 0, n - 7));
            boolean timing = true;
            for (int i = 8; i < n - 8; i++) timing &= m[6][i] == (i % 2 == 0) && m[i][6] == (i % 2 == 0);
            ok("timing" + tag, timing);
            ok("dark module" + tag, m[n - 8][8]);
            int a = 0, b = 0;
            for (int i = 0; i < 15; i++) {
                int[] p = i < 6 ? new int[] {i, 8} : i < 8 ? new int[] {i + 1, 8} : i == 8 ? new int[] {8, 7} : new int[] {8, 14 - i};
                int[] q = i < 8 ? new int[] {8, n - 1 - i} : new int[] {n - 15 + i, 8};
                if (m[p[0]][p[1]]) a |= 1 << i;
                if (m[q[0]][q[1]]) b |= 1 << i;
            }
            eq("format copies agree" + tag, a, b);
            int mask = -1;
            for (int k = 0; k < 8; k++) if (Qr.formatBits(k) == a) mask = k;
            ok("format is level M" + tag, mask >= 0);
            if (v >= 7) {
                int c = 0, d = 0;
                for (int i = 0; i < 18; i++) {
                    if (m[i / 3][n - 11 + i % 3]) c |= 1 << i;
                    if (m[n - 11 + i % 3][i / 3]) d |= 1 << i;
                }
                eq("version top right" + tag, Qr.versionBits(v), c);
                eq("version bottom left" + tag, Qr.versionBits(v), d);
            }
            int best = Integer.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                boolean[][] forced = Qr.encode(bytes, k);
                best = Math.min(best, Qr.penalty(forced));
                if (k == mask) ok("auto equals forced mask" + tag, Arrays.deepEquals(forced, m));
            }
            eq("lowest penalty chosen" + tag, best, Qr.penalty(m));
        }
    }

    private static void speed() {
        String pair = PAIR + b64(new Random(2), 43), big = repeat('q', 666);
        for (String t : new String[] {pair, big}) {
            for (int i = 0; i < 20; i++) Qr.encode(t);
            long t0 = System.nanoTime();
            for (int i = 0; i < 50; i++) Qr.encode(t);
            System.out.printf("encode %d bytes (version %d): %.2f ms (desktop)%n", t.length(), Qr.version(t.length()),
                    (System.nanoTime() - t0) / 50 / 1e6);
        }
    }

    private static boolean finderAt(boolean[][] m, int x0, int y0) {
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                int d = Math.max(Math.abs(x - 3), Math.abs(y - 3));
                if (m[y0 + y][x0 + x] != (d != 2)) return false;
            }
        }
        return true;
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private static String b64(Random r, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(B64URL.charAt(r.nextInt(64)));
        return sb.toString();
    }

    /** A URL of exactly {@code len} bytes, the pairing link shape when there is room. */
    private static String url(Random r, int len) {
        String head = len >= PAIR.length() + 8 ? PAIR : len >= 13 ? "http://lu.tv/" : "";
        return head + b64(r, len - head.length());
    }

    /**
     * Random text from ASCII, accents, Greek, CJK, emoji and a few controls. At least one
     * character, and no more than {@code max} UTF-8 bytes past the first.
     */
    private static String random(Random r, int max) {
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        while (true) {
            int k = r.nextInt(100), cp;
            if (k < 50) cp = 0x20 + r.nextInt(0x5F);
            else if (k < 62) cp = 0xC0 + r.nextInt(0x40);
            else if (k < 70) cp = 0x391 + r.nextInt(0x38);
            else if (k < 80) cp = 0x4E00 + r.nextInt(0x5000);
            else if (k < 92) cp = 0x1F300 + r.nextInt(0x350);
            else if (k < 96) cp = "\n\t\r".charAt(r.nextInt(3));
            else cp = 0x2000 + r.nextInt(0x100);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > max && bytes > 0) break;
            sb.appendCodePoint(cp);
            bytes += len;
        }
        return sb.toString();
    }

    private static int written;

    private static void images(Path dir) throws IOException {
        Files.createDirectories(dir);
        Random r = new Random(20261027);
        try (Writer w = Files.newBufferedWriter(dir.resolve("manifest.tsv"), StandardCharsets.UTF_8)) {
            write(dir, w, "short-vestor", "Vestor");
            write(dir, w, "short-hello", "HELLO WORLD");
            write(dir, w, "short-a", "a");
            // No decoder reports an empty payload, so the checker compares this one with reference encoders only.
            write(dir, w, "empty", "");
            for (int i = 0; i < 10; i++) write(dir, w, "pair-" + i, PAIR + b64(r, 43));
            for (int v = 1; v <= Qr.MAX_VERSION; v++) {
                int cap = Qr.capacity(v);
                write(dir, w, "full-v" + v, url(r, cap));
                write(dir, w, "near-v" + v, url(r, cap - 1));
                if (v < Qr.MAX_VERSION) write(dir, w, "over-v" + v, url(r, cap + 1));
            }
            write(dir, w, "utf8-mixed", "Café crème brûlée, naïve façade, Über, 東京, "
                    + "🚀👍🏽 🇺🇸 ☕");
            write(dir, w, "utf8-emoji-full", repeat4("😀", 166));
            write(dir, w, "utf8-accents", "àéîõü Ångström Łódź İstanbul");
            String pair = PAIR + b64(r, 43), mid = url(r, 150), big = url(r, 600);
            for (int m = 0; m < 8; m++) {
                write(dir, w, "mask" + m + "-pair", pair.getBytes(StandardCharsets.UTF_8), m);
                write(dir, w, "mask" + m + "-v8", mid.getBytes(StandardCharsets.UTF_8), m);
                write(dir, w, "mask" + m + "-v19", big.getBytes(StandardCharsets.UTF_8), m);
            }
            for (int i = 0; i < 200; i++) {
                int max = r.nextInt(4) == 0 ? r.nextInt(667) : r.nextInt(120);
                write(dir, w, String.format("random-%03d", i), random(r, max));
            }
            for (int i = 0; i < 20; i++) {
                byte[] b = new byte[r.nextInt(667)];
                r.nextBytes(b);
                write(dir, w, String.format("binary-%02d", i), b, -1);
            }
        }
        System.out.println("wrote " + written + " codes to " + dir);
    }

    private static String repeat4(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static void write(Path dir, Writer w, String name, String text) throws IOException {
        boolean[][] m = Qr.encode(text);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        eq("same as byte encode " + name, true, Arrays.deepEquals(m, Qr.encode(bytes, -1)));
        write(dir, w, name, bytes, -1);
    }

    private static void write(Path dir, Writer w, String name, byte[] bytes, int mask) throws IOException {
        boolean[][] m = Qr.encode(bytes, mask);
        int v = Qr.version(bytes.length);
        eq("size " + name, Qr.size(v), m.length);
        png(m, dir.resolve(name + ".png"));
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b & 0xFF));
        w.write(name + ".png\t" + v + "\t" + mask + "\t" + hex + "\n");
        written++;
    }

    private static void png(boolean[][] m, Path file) throws IOException {
        int scale = 8, margin = 4, n = (m.length + 2 * margin) * scale;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster px = img.getRaster();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int my = y / scale - margin, mx = x / scale - margin;
                boolean dark = my >= 0 && mx >= 0 && my < m.length && mx < m.length && m[my][mx];
                px.setSample(x, y, 0, dark ? 0 : 255);
            }
        }
        ImageIO.write(img, "png", file.toFile());
    }
}
