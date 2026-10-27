package tv.lucent.home;

import java.nio.charset.StandardCharsets;

/**
 * QR Code encoder (ISO/IEC 18004) for the pairing link the TV shows to a phone. Text goes in
 * as UTF-8 in byte mode at error correction level M, in the smallest of versions 1 to 20 that
 * holds it, under whichever of the eight masks scores the lowest penalty. The matrix has no
 * quiet zone, so whoever draws it leaves a light margin four modules wide around it.
 */
final class Qr {
    private Qr() {}

    /** Largest version made. Version 20 is 97 modules wide and holds 666 bytes at level M. */
    static final int MAX_VERSION = 20;

    /** Codewords in the whole symbol, data and error correction together, by version. */
    private static final int[] TOTAL = {
        0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815, 901, 991, 1085,
    };

    /** Error correction codewords in each block at level M, by version. */
    private static final int[] ECC = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26};

    /** Number of error correction blocks at level M, by version. */
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16};

    /** Centre rows and columns of the alignment patterns, by version. Version 1 has none. */
    private static final int[][] ALIGN = {
        {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
        {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}, {6, 30, 54}, {6, 32, 58}, {6, 34, 62},
        {6, 26, 46, 66}, {6, 26, 48, 70}, {6, 26, 50, 74}, {6, 30, 54, 78}, {6, 30, 56, 82}, {6, 30, 58, 86},
        {6, 34, 62, 90},
    };

    /** XORed into the format information so it can never be all light. */
    private static final int FORMAT_XOR = 0x5412;
    /** BCH(15,5) generator for format information, x^10 + x^8 + x^5 + x^4 + x^2 + x + 1. */
    private static final int FORMAT_POLY = 0x537;
    /** BCH(18,6) generator for version information, x^12 + x^11 + x^10 + x^9 + x^8 + x^5 + x^2 + 1. */
    private static final int VERSION_POLY = 0x1F25;

    /** Powers of the generator 2 in GF(256) modulo x^8 + x^4 + x^3 + x^2 + 1, doubled to skip a modulo. */
    private static final int[] EXP = new int[510];
    /** Discrete logarithms in GF(256). LOG[0] is never read. */
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            EXP[i + 255] = x;
            LOG[x] = i;
            x <<= 1;
            if (x > 0xFF) x ^= 0x11D;
        }
    }

    /**
     * The modules of a QR code for {@code text}, true for dark, indexed [row][column]. UTF-8 in
     * byte mode at level M, in the smallest version from 1 to 20 that fits.
     *
     * @throws IllegalArgumentException if the text is null or longer than version 20 holds
     */
    static boolean[][] encode(String text) {
        if (text == null) throw new IllegalArgumentException("no text");
        return encode(text.getBytes(StandardCharsets.UTF_8), -1);
    }

    /** As {@link #encode(String)} for raw bytes, with mask 0 to 7 forced, or -1 to pick the best. */
    static boolean[][] encode(byte[] data, int mask) {
        if (mask < -1 || mask > 7) throw new IllegalArgumentException("mask " + mask);
        int version = version(data.length);
        int size = size(version);
        boolean[][] dark = new boolean[size][size];
        boolean[][] fixed = new boolean[size][size];
        functionPatterns(dark, fixed, version);
        place(dark, fixed, interleave(dataCodewords(data, version), version));

        boolean[][] best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            if (mask >= 0 && m != mask) continue;
            boolean[][] grid = new boolean[size][];
            for (int y = 0; y < size; y++) grid[y] = dark[y].clone();
            applyMask(grid, fixed, m);
            drawFormat(grid, fixed, m);
            int score = mask >= 0 ? 0 : penalty(grid);
            if (score < bestScore) {
                best = grid;
                bestScore = score;
            }
        }
        return best;
    }

    /** Modules along one side for a version. */
    static int size(int version) {
        return 17 + 4 * version;
    }

    /** Smallest version that holds {@code bytes} bytes of byte mode data at level M. */
    static int version(int bytes) {
        for (int v = 1; v <= MAX_VERSION; v++) {
            if (bytes <= capacity(v)) return v;
        }
        throw new IllegalArgumentException(bytes + " bytes is more than a QR code here holds (" + capacity(MAX_VERSION) + ")");
    }

    /** Bytes of byte mode data a version holds at level M, after the mode and length header. */
    static int capacity(int version) {
        return (dataCodewords(version) * 8 - 4 - countBits(version)) / 8;
    }

    /** Data codewords a version holds at level M, before error correction is added. */
    static int dataCodewords(int version) {
        return TOTAL[version] - BLOCKS[version] * ECC[version];
    }

    /** Width of the byte mode character count. */
    private static int countBits(int version) {
        return version < 10 ? 8 : 16;
    }

    // Data codewords and error correction.

    /** Mode, length, the bytes, a terminator, then alternating pad codewords to fill the version. */
    private static int[] dataCodewords(byte[] data, int version) {
        int n = dataCodewords(version);
        Bits bits = new Bits(n);
        bits.put(0x4, 4);
        bits.put(data.length, countBits(version));
        for (byte b : data) bits.put(b & 0xFF, 8);
        int room = n * 8;
        bits.put(0, Math.min(4, room - bits.length));
        bits.length = (bits.length + 7) & ~7;
        for (int pad = 0xEC; bits.length < room; pad ^= 0xEC ^ 0x11) bits.put(pad, 8);
        return bits.out;
    }

    /** A bit writer into a codeword array, most significant bit first. */
    private static final class Bits {
        final int[] out;
        int length;

        Bits(int codewords) {
            out = new int[codewords];
        }

        void put(int value, int count) {
            for (int i = count - 1; i >= 0; i--, length++) {
                if (((value >>> i) & 1) != 0) out[length >>> 3] |= 0x80 >>> (length & 7);
            }
        }
    }

    /**
     * Splits the data into blocks, adds error correction to each, and interleaves them: the first
     * data codeword of every block, then the second, and so on, then the error correction the same
     * way. The longer blocks come last and carry one more data codeword than the shorter ones.
     */
    private static int[] interleave(int[] data, int version) {
        int blocks = BLOCKS[version], ecc = ECC[version], total = TOTAL[version];
        int shortBlocks = blocks - total % blocks;
        int shortData = total / blocks - ecc;
        int[] gen = generator(ecc);
        int[][] parts = new int[blocks][];
        int[][] checks = new int[blocks][];
        for (int b = 0, off = 0; b < blocks; b++) {
            int len = shortData + (b < shortBlocks ? 0 : 1);
            parts[b] = new int[len];
            System.arraycopy(data, off, parts[b], 0, len);
            checks[b] = remainder(parts[b], gen);
            off += len;
        }
        int[] out = new int[total];
        int k = 0;
        for (int i = 0; i <= shortData; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < parts[b].length) out[k++] = parts[b][i];
            }
        }
        for (int i = 0; i < ecc; i++) {
            for (int b = 0; b < blocks; b++) out[k++] = checks[b][i];
        }
        return out;
    }

    private static int mul(int a, int b) {
        return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
    }

    /**
     * The Reed-Solomon generator of a degree, the product of (x - 2^i) for i from 0 below the
     * degree. Coefficients highest power first, the leading one included.
     */
    static int[] generator(int degree) {
        int[] g = new int[degree + 1];
        g[0] = 1;
        for (int i = 0; i < degree; i++) {
            int root = EXP[i];
            // Multiply by (x + root), working down so each step reads the old lower coefficient.
            for (int j = i + 1; j > 0; j--) g[j] ^= mul(g[j - 1], root);
        }
        return g;
    }

    /** Error correction codewords for one block, the data times x^n modulo the generator. */
    static int[] remainder(int[] data, int[] gen) {
        int n = gen.length - 1;
        int[] rem = new int[n];
        for (int d : data) {
            int factor = d ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, n - 1);
            rem[n - 1] = 0;
            if (factor == 0) continue;
            for (int j = 0; j < n; j++) rem[j] ^= mul(gen[j + 1], factor);
        }
        return rem;
    }

    // Format and version information.

    /** The 15 format bits for level M and a mask, with BCH(15,5) check bits, after the XOR. */
    static int formatBits(int mask) {
        int data = mask; // Level M is the indicator 00 in the top two of these five bits.
        return (data << 10 | bch(data, 10, FORMAT_POLY)) ^ FORMAT_XOR;
    }

    /** The 18 version bits, the version number and its BCH(18,6) check bits. */
    static int versionBits(int version) {
        return version << 12 | bch(version, 12, VERSION_POLY);
    }

    /** The remainder of data times x^degree divided by the generator polynomial, over GF(2). */
    private static int bch(int data, int degree, int poly) {
        int rem = data;
        for (int i = 0; i < degree; i++) {
            rem <<= 1;
            if ((rem >>> degree) != 0) rem ^= poly;
        }
        return rem;
    }

    // Layout.

    private static void set(boolean[][] dark, boolean[][] fixed, int x, int y, boolean on) {
        dark[y][x] = on;
        fixed[y][x] = true;
    }

    /** Finders, separators, timing, alignment, the dark module, and space for format and version. */
    private static void functionPatterns(boolean[][] dark, boolean[][] fixed, int version) {
        int size = dark.length;
        // Timing first. The finders and alignment patterns that cross it agree with it anyway.
        for (int i = 0; i < size; i++) {
            set(dark, fixed, i, 6, i % 2 == 0);
            set(dark, fixed, 6, i, i % 2 == 0);
        }
        finder(dark, fixed, 3, 3);
        finder(dark, fixed, size - 4, 3);
        finder(dark, fixed, 3, size - 4);
        int[] at = ALIGN[version];
        int last = at.length - 1;
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j <= last; j++) {
                // Three corners would land on the finders.
                if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue;
                alignment(dark, fixed, at[i], at[j]);
            }
        }
        drawFormat(dark, fixed, 0);
        set(dark, fixed, 8, size - 8, true);
        if (version >= 7) {
            int bits = versionBits(version);
            for (int i = 0; i < 18; i++) {
                boolean on = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(dark, fixed, a, b, on);
                set(dark, fixed, b, a, on);
            }
        }
    }

    /** A 7 by 7 finder centred on (cx, cy) and the light separator around it, clipped to the symbol. */
    private static void finder(boolean[][] dark, boolean[][] fixed, int cx, int cy) {
        int size = dark.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(dark, fixed, x, y, d != 2 && d != 4);
            }
        }
    }

    /** A 5 by 5 alignment pattern centred on (cx, cy). */
    private static void alignment(boolean[][] dark, boolean[][] fixed, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                set(dark, fixed, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    /**
     * Both copies of the format bits. One wraps the top left finder, skipping the timing row and
     * column. The other is split between the bottom left and top right finders.
     */
    private static void drawFormat(boolean[][] dark, boolean[][] fixed, int mask) {
        int bits = formatBits(mask), size = dark.length;
        for (int i = 0; i < 15; i++) {
            boolean on = ((bits >>> i) & 1) != 0;
            if (i < 6) set(dark, fixed, 8, i, on);
            else if (i < 8) set(dark, fixed, 8, i + 1, on);
            else if (i == 8) set(dark, fixed, 7, 8, on);
            else set(dark, fixed, 14 - i, 8, on);
            if (i < 8) set(dark, fixed, size - 1 - i, 8, on);
            else set(dark, fixed, 8, size - 15 + i, on);
        }
    }

    /**
     * Places the codewords most significant bit first, in two module wide columns from the right
     * edge, snaking up then down and skipping the vertical timing column. Modules left over at
     * the end are the remainder bits and stay light.
     */
    private static void place(boolean[][] dark, boolean[][] fixed, int[] codewords) {
        int size = dark.length, bit = 0, bits = codewords.length * 8;
        boolean up = true;
        for (int right = size - 1; right > 0; right -= 2) {
            if (right == 6) right = 5;
            for (int k = 0; k < size; k++) {
                int y = up ? size - 1 - k : k;
                for (int x = right; x >= right - 1; x--) {
                    if (fixed[y][x]) continue;
                    if (bit < bits) dark[y][x] = ((codewords[bit >>> 3] >>> (7 - (bit & 7))) & 1) != 0;
                    bit++;
                }
            }
            up = !up;
        }
        if (bit < bits) throw new IllegalStateException("layout holds " + bit + " bits, not " + bits);
    }

    /** Modules left for data and remainder bits once the function patterns are drawn. */
    static int dataModules(int version) {
        int size = size(version), n = 0;
        boolean[][] dark = new boolean[size][size];
        boolean[][] fixed = new boolean[size][size];
        functionPatterns(dark, fixed, version);
        for (boolean[] row : fixed) {
            for (boolean f : row) if (!f) n++;
        }
        return n;
    }

    // Masks and penalties.

    /** Whether a mask flips the module at row y, column x. */
    static boolean flips(int mask, int y, int x) {
        switch (mask) {
            case 0: return (y + x) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (y + x) % 3 == 0;
            case 4: return (y / 2 + x / 3) % 2 == 0;
            case 5: return y * x % 2 + y * x % 3 == 0;
            case 6: return (y * x % 2 + y * x % 3) % 2 == 0;
            default: return ((y + x) % 2 + y * x % 3) % 2 == 0;
        }
    }

    private static void applyMask(boolean[][] dark, boolean[][] fixed, int mask) {
        for (int y = 0; y < dark.length; y++) {
            for (int x = 0; x < dark.length; x++) {
                if (!fixed[y][x] && flips(mask, y, x)) dark[y][x] = !dark[y][x];
            }
        }
    }

    /**
     * The standard penalty for a finished symbol, lower is better. Runs of five or more alike in
     * a line, 2 by 2 blocks of one colour, finder-like 1:1:3:1:1 runs with four light modules on
     * one side, and dark modules far from half.
     */
    static int penalty(boolean[][] m) {
        int size = m.length, score = 0, darkCount = 0;
        for (int i = 0; i < size; i++) score += line(m, i, true) + line(m, i, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean c = m[y][x];
                if (c) darkCount++;
                if (x + 1 < size && y + 1 < size && c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3;
            }
        }
        int total = size * size;
        // Ten points for each full 5% step away from half dark.
        score += 10 * (Math.abs(darkCount * 20 - total * 10) / total);
        return score;
    }

    /** Penalty rules 1 and 3 along one row, or one column when {@code row} is false. */
    private static int line(boolean[][] m, int index, boolean row) {
        int size = m.length, score = 0, run = 0;
        boolean prev = false;
        for (int i = 0; i < size; i++) {
            boolean c = at(m, index, i, row);
            if (i > 0 && c == prev) {
                run++;
            } else {
                if (run >= 5) score += run - 2;
                run = 1;
                prev = c;
            }
        }
        if (run >= 5) score += run - 2;
        for (int i = 0; i + 7 <= size; i++) {
            if (finderLike(m, index, i, row) && (light(m, index, i - 4, i, row) || light(m, index, i + 7, i + 11, row))) score += 40;
        }
        return score;
    }

    private static boolean at(boolean[][] m, int index, int i, boolean row) {
        return row ? m[index][i] : m[i][index];
    }

    /** Dark, light, three dark, light, dark, starting at {@code i}. */
    private static boolean finderLike(boolean[][] m, int index, int i, boolean row) {
        return at(m, index, i, row) && !at(m, index, i + 1, row) && at(m, index, i + 2, row) && at(m, index, i + 3, row)
                && at(m, index, i + 4, row) && !at(m, index, i + 5, row) && at(m, index, i + 6, row);
    }

    /** Whether modules from {@code from} up to {@code to} are light. The quiet zone outside counts as light. */
    private static boolean light(boolean[][] m, int index, int from, int to, boolean row) {
        for (int i = Math.max(0, from); i < Math.min(to, m.length); i++) {
            if (at(m, index, i, row)) return false;
        }
        return true;
    }
}
