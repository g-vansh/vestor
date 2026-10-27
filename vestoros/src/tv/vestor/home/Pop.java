package tv.vestor.home;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The moments that pop up: a note writing itself on its paper, hearts floating up, and the bike
 * alarm's card. Drawn the same on the home screen (on real glass) and over another app (in the
 * HOME guard's overlay window, on dark glass), so they live here, platform-free.
 */
final class Pop {
    private Pop() {}

    // ---- Notes ---------------------------------------------------------------------------------

    /** A note card's size in dp for a drawing width {@code drawW}: header, 4:3 drawing, text. */
    static float[] noteSize(Notes.Note n, float drawW) {
        return noteSize(n, drawW, new float[2]);
    }

    /** The same, into {@code out}, for drawing every frame without garbage. */
    static float[] noteSize(Notes.Note n, float drawW, float[] out) {
        boolean drawing = !n.strokes.isEmpty();
        float h = 64 + (drawing ? drawW * 0.75f + 18 : 0);
        if (!n.text.isEmpty()) {
            // The words' height by an estimate of their lines (serif italic averages under half its size).
            float size = drawing ? 24 : 34;
            int perLine = Math.max(8, (int) (drawW / (size * 0.47f)));
            int lines = Math.min(drawing ? 2 : 3, Math.max(1, (n.text.length() + perLine - 1) / perLine));
            h += size * 0.4f + lines * size * 1.18f;
        }
        out[0] = drawW + 44;
        out[1] = h + 14;
        return out;
    }

    /**
     * A note on its paper inside l..r, t..b: a mono header ("NOTE FROM ALEX · 9:41 PM"), the
     * drawing writing itself ({@code progress} 0..1), and the typed words in serif italic.
     */
    static void note(Gfx g, Notes.Note n, Ink ink, float l, float t, float r, float b, float d, float progress, float a, float time,
            boolean h24) {
        if (a <= 0.003f) return;
        int paper = Notes.PAPER[Math.max(0, Math.min(Notes.PAPERS - 1, n.paper))];
        boolean light = Notes.lightPaper(n.paper);
        float rad = 26 * d;
        shadow(g, l, t, r, b, rad, d, a);
        g.fillRoundRect(l, t, r, b, rad, Pixels.alpha(paper, a));
        // Paper has a faint top light and a hairline rim, like a card under a lamp.
        g.fillRoundRectLinear(l, t, r, b, rad, 0, t, 0, b, light ? PAPER_LIGHT : PAPER_DARK, TWO, a);
        g.strokeRoundRect(l + d / 2, t + d / 2, r - d / 2, b - d / 2, rad, d, light ? Pixels.alpha(0xFF000000, 0.08f * a) : Pixels.white(0.14f * a));
        int pen = light ? 0xFF161514 : 0xFFF5F1EA;
        String head = n.head;
        if (head == null || n.headH24 != h24) {
            String when = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(new Date(n.at));
            head = ("Note from " + firstName(n.from) + "  ·  " + when).toUpperCase(Locale.US);
            n.head = head;
            n.headH24 = h24;
        }
        float x = l + 22 * d;
        g.text(Text.fit(g, head, Gfx.MONO, 11.5f * d, r - l - 80 * d), Gfx.MONO, 11.5f * d, 0.08f, x, t + 34 * d,
                Pixels.alpha(pen, 0.55f * a), Gfx.LEFT, 0);
        heart(g, r - 30 * d, t + 30 * d, 9 * d, Pixels.alpha(0xFFFF6B8B, a * (0.75f + 0.25f * (float) Math.sin(time * 3))));
        float y = t + 52 * d;
        if (!n.strokes.isEmpty() && ink != null) {
            float dw = r - l - 44 * d, dh = dw * 0.75f;
            Object img = ink.at(g, progress);
            g.drawImage(img, x, y, x + dw, y + dh, Math.round(255 * a));
            float[] nib = progress < 1 ? ink.nib() : null;
            if (nib != null) {
                float nx = x + nib[0] * dw, ny = y + nib[1] * dh;
                g.fillCircle(nx, ny, 7 * d, Pixels.alpha(0xFFFFFFFF, 0.18f * a));
                g.fillCircle(nx, ny, 3 * d, Pixels.alpha(0xFFFFFFFF, 0.6f * a));
            }
            y += dh + 18 * d;
        }
        if (!n.text.isEmpty()) {
            boolean big = n.strokes.isEmpty();
            float size = (big ? 34 : 24) * d;
            float textA = Math.max(0, Math.min(1, (progress - 0.15f) / 0.5f)) * a;
            lines(g, n.text, Gfx.SERIF_ITALIC, size, x, y + size, r - l - 44 * d, size * 1.18f, big ? 3 : 2, Pixels.alpha(pen, textA));
        }
    }

    private static String firstName(String from) {
        String f = from == null ? "" : from.trim();
        int apos = f.indexOf('\'');
        if (apos > 0) f = f.substring(0, apos);
        int sp = f.indexOf(' ');
        if (sp > 0) f = f.substring(0, sp);
        return f.isEmpty() ? "home" : f;
    }

    /** Text wrapped at word breaks into at most {@code maxLines}, the last one ellipsised. */
    static void lines(Gfx g, String s, int face, float size, float x, float base, float maxW, float leading, int maxLines,
            int color) {
        String[] out = wrap(g, s, face, size, maxW, maxLines);
        for (int i = 0; i < out.length; i++) g.text(out[i], face, size, 0, x, base + i * leading, color, Gfx.LEFT, 0);
    }

    /** A wrapped paragraph, remembered: the same words at the same size wrap the same way every frame. */
    private static final class Wrap {
        Gfx g;
        String s;
        int face, maxLines;
        float size, maxW;
        String[] out;
    }

    private static final Wrap[] WRAPS = new Wrap[12];
    private static int wrapNext;

    private static String[] wrap(Gfx g, String s, int face, float size, float maxW, int maxLines) {
        for (Wrap w : WRAPS) {
            if (w != null && w.g == g && w.face == face && w.size == size && w.maxW == maxW && w.maxLines == maxLines
                    && (w.s == s || w.s.equals(s))) {
                return w.out;
            }
        }
        List<String> lines = new ArrayList<>();
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String tryLine = line.length() == 0 ? words[i] : line + " " + words[i];
            if (g.measure(tryLine, face, size, 0) <= maxW || line.length() == 0) {
                line.setLength(0);
                line.append(tryLine);
                continue;
            }
            if (lines.size() == maxLines - 1) {
                StringBuilder rest = new StringBuilder(line);
                for (int j = i; j < words.length; j++) rest.append(' ').append(words[j]);
                line.setLength(0);
                line.append(rest);
                break;
            }
            lines.add(line.toString());
            line.setLength(0);
            line.append(words[i]);
        }
        if (line.length() > 0) lines.add(Text.fit(g, line.toString(), face, size, maxW));
        Wrap w = new Wrap();
        w.g = g;
        w.s = s;
        w.face = face;
        w.size = size;
        w.maxW = maxW;
        w.maxLines = maxLines;
        w.out = lines.toArray(new String[0]);
        WRAPS[wrapNext] = w;
        wrapNext = (wrapNext + 1) % WRAPS.length;
        return w.out;
    }

    private static final int[] PAPER_LIGHT = {0x33FFFFFF, 0x00FFFFFF};
    private static final int[] PAPER_DARK = {0x1FFFFFFF, 0x00FFFFFF};
    private static final int[] DARK_GLASS = {0xF2262A36, 0xF21A1D27, 0xF2121419};
    private static final float[] TWO = {0f, 1f};
    private static final int[] VOLT_WELL = {0xFFFFE066, 0xFFFFC400};
    private static final float[] THREE = {0f, 0.5f, 1f};

    /** Shadows by card size: a popped-up note and the pinned one draw in the same frame. */
    private static final Object[] SHADOW_IMG = new Object[4];
    private static final long[] SHADOW_KEY = new long[4];
    private static int shadowNext;

    /** A soft shadow under a card, rendered once per size. */
    static void shadow(Gfx g, float l, float t, float r, float b, float rad, float d, float a) {
        long key = (long) Math.round(r - l) << 40 | (long) Math.round(b - t) << 20 | Math.round(rad * 8 + d * 1000) & 0xFFFFF;
        Object img = null;
        for (int i = 0; i < SHADOW_KEY.length; i++) if (SHADOW_IMG[i] != null && SHADOW_KEY[i] == key) img = SHADOW_IMG[i];
        if (img == null) {
            int[] px = Pixels.shadow(r - l, b - t, rad, 26 * d, 0.25f);
            int[] pixels = new int[px[0] * px[1]];
            System.arraycopy(px, 2, pixels, 0, pixels.length);
            img = g.image(pixels, px[0], px[1]);
            SHADOW_IMG[shadowNext] = img;
            SHADOW_KEY[shadowNext] = key;
            shadowNext = (shadowNext + 1) % SHADOW_KEY.length;
        }
        float pad = 26 * d;
        g.drawImage(img, l - pad, t - pad + 12 * d, r + pad, b + pad + 12 * d, Math.round(255 * 0.5f * a));
    }

    // ---- Hearts ----------------------------------------------------------------------------------

    /** A heart outline as a unit polygon (the classic parametric curve), centred, 1 wide. */
    private static final float[] HEART = new float[64];

    static {
        for (int i = 0; i < 32; i++) {
            double t = Math.PI * 2 * i / 32;
            double x = 16 * Math.pow(Math.sin(t), 3);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            HEART[i * 2] = (float) (x / 34);
            HEART[i * 2 + 1] = (float) (-y / 34 + 0.05);
        }
    }

    private static final float[] SCRATCH = new float[64], STAR = new float[16];

    static void heart(Gfx g, float cx, float cy, float size, int argb) {
        for (int i = 0; i < 64; i += 2) {
            SCRATCH[i] = cx + HEART[i] * size * 2;
            SCRATCH[i + 1] = cy + HEART[i + 1] * size * 2;
        }
        g.fillPolygon(SCRATCH, argb);
    }

    static final float HEARTS_SECONDS = 4.5f;
    private static final int[] HEART_COLORS = {0xFFFF6B8B, 0xFFFF5E5E, 0xFFFF9EB5, 0xFFEC944C, 0xFFF5F1EA};
    private static final int[] SPARK_COLORS = {0xFFF4D06F, 0xFFF5F1EA, 0xFF6EC1FF, 0xFFB79CFF};

    /**
     * A burst of hearts (or sparkles) rising from the bottom of l..r, t..b over 4.5 s: each one
     * with its own size, speed, sway and colour, fading in and out. Deterministic from
     * {@code seed}, so every frame of one burst agrees.
     */
    static void hearts(Gfx g, String kind, float l, float t, float r, float b, float d, float time, long seed) {
        if (time < 0 || time > HEARTS_SECONDS) return;
        boolean spark = "sparkles".equals(kind);
        int n = "wave".equals(kind) ? 14 : 26;
        java.util.Random rnd = new java.util.Random(seed);
        float w = r - l, h = b - t;
        for (int i = 0; i < n; i++) {
            float delay = rnd.nextFloat() * 1.6f, life = 2.2f + rnd.nextFloat() * 1.4f;
            float x0 = l + w * (0.08f + 0.84f * rnd.nextFloat());
            float size = (12 + rnd.nextFloat() * 22) * d, sway = (8 + rnd.nextFloat() * 22) * d, freq = 1.2f + rnd.nextFloat() * 1.6f;
            int color = (spark ? SPARK_COLORS : HEART_COLORS)[rnd.nextInt(spark ? SPARK_COLORS.length : HEART_COLORS.length)];
            float p = (time - delay) / life;
            if (p <= 0 || p >= 1) continue;
            float ease = 1 - (1 - p) * (1 - p);
            float y = b - ease * h * (0.55f + 0.4f * rnd.nextFloat());
            float x = x0 + sway * (float) Math.sin(p * freq * Math.PI * 2 + i);
            float alpha = Math.min(1, p * 6) * (1 - p * p);
            float s = size * (0.7f + 0.3f * Math.min(1, p * 4));
            if (spark) {
                star(g, x, y, s * 0.6f, Pixels.alpha(color, alpha));
            } else {
                heart(g, x, y + 2 * d, s, Pixels.alpha(0xFF000000, 0.25f * alpha));
                heart(g, x, y, s, Pixels.alpha(color, alpha));
            }
        }
    }

    private static void star(Gfx g, float cx, float cy, float rad, int argb) {
        for (int i = 0; i < 16; i += 2) {
            double a = Math.PI * i / 8 - Math.PI / 2;
            float rr = (i / 2) % 2 == 0 ? rad : rad * 0.28f;
            SCRATCH[i] = cx + (float) Math.cos(a) * rr;
            SCRATCH[i + 1] = cy + (float) Math.sin(a) * rr;
        }
        System.arraycopy(SCRATCH, 0, STAR, 0, 16);
        g.fillPolygon(STAR, argb);
    }

    // ---- Alarm --------------------------------------------------------------------------------------

    /** The alarm card's size in dp. */
    static final float ALARM_W = 560, ALARM_H = 188;

    /**
     * The bike alarm: a pulsing well with the e-bike, "E-bike ready at Westgate", the counts, and
     * how to stop it. {@code glass} false draws its own dark glass (over another app).
     */
    /** The largest size from {@code max} down to {@code min} at which {@code s} fits {@code w}. */
    static float fitSize(Gfx g, String s, int face, float max, float min, float w, float d) {
        float size = max;
        while (size > min && g.measure(s, face, size, 0) > w) size -= d;
        return Math.max(min, size);
    }

    static void alarm(Gfx g, Model.Finder f, Model.Station s, float l, float t, float d, float a, float time, boolean glass,
            boolean still) {
        float r = l + ALARM_W * d, b = t + ALARM_H * d, rad = 40 * d;
        if (!glass) {
            shadow(g, l, t, r, b, rad, d, a);
            g.fillRoundRectLinear(l, t, r, b, rad, 0, t, 0, b, DARK_GLASS, THREE, a);
            g.strokeRoundRect(l + d / 2, t + d / 2, r - d / 2, b - d / 2, rad, d, Pixels.white(0.16f * a));
        }
        float pulse = still ? 0.5f : 0.5f + 0.5f * (float) Math.sin(time * Math.PI * 2 / 1.2);
        g.strokeRoundRect(l + 1.5f * d, t + 1.5f * d, r - 1.5f * d, b - 1.5f * d, rad - d, 3 * d, Pixels.alpha(Tone.VOLT, (0.35f + 0.4f * pulse) * a));
        float wellR = 52 * d, wx = l + 30 * d + wellR, wy = (t + b) / 2;
        if (!still) {
            for (int i = 0; i < 3; i++) {
                float p = ((time + i * 0.45f) % 1.35f) / 1.35f;
                g.strokeCircle(wx, wy, wellR * (1 + 0.6f * p), 2.5f * d, Pixels.alpha(Tone.VOLT, 0.5f * (1 - p) * a));
            }
        }
        float bounce = still ? 1 : 1 + 0.05f * (float) Math.sin(time * Math.PI * 2 / 1.2);
        g.fillCircleLinear(wx, wy, wellR * bounce, wx, wy - wellR, wx, wy + wellR, VOLT_WELL, TWO, a);
        Icons.ebike(g, wx, wy + d, 66 * d * bounce, Pixels.alpha(0xFF16130A, a), Pixels.alpha(0xFF16130A, a));
        float x = wx + wellR + 30 * d, maxW = r - x - 28 * d;
        String place = Notice.place(f);
        g.text("BIKE FINDER  ·  ALARM", Gfx.MONO, 12 * d, 0.1f, x, t + 46 * d, Pixels.alpha(Tone.VOLT, a), Gfx.LEFT, 0);
        // The station's name is the point: the title steps down in size, then shortens, before it is cut.
        String title = "E-bike ready at " + place;
        float ts = fitSize(g, title, Gfx.SERIF, 40 * d, 34 * d, maxW, d);
        if (g.measure(title, Gfx.SERIF, ts, 0) > maxW) {
            title = "E-bike at " + place;
            ts = fitSize(g, title, Gfx.SERIF, 40 * d, 30 * d, maxW, d);
        }
        g.text(Text.fit(g, title, Gfx.SERIF, ts, maxW), Gfx.SERIF, ts, -0.01f, x, t + 94 * d, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        String count = f.ebikes == 1 ? "1 e-bike" : f.ebikes + " e-bikes";
        if (s != null && s.live) count += "  ·  " + s.docks + (s.docks == 1 ? " dock" : " docks");
        g.text(count, Gfx.TEXT_MEDIUM, 17 * d, 0, x, t + 126 * d, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        float kw = Icons.keycap(g, x, t + 152 * d, 24 * d, "OK", Tone.text(Tone.PRIMARY, a));
        g.text("Stop the alarm", Gfx.TEXT_SEMIBOLD, 17 * d, 0, x + kw + 12 * d, t + 158 * d, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
    }
}
