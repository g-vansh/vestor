package tv.vestor.home;

import java.util.Locale;

/**
 * Bike finder's alerts. Over another app they are drawn as thick dark glass (another app's
 * picture cannot be sampled for a backdrop); on the home screen the scene puts the same content
 * on real glass. Placed top right inside the safe area, where tvOS shows incoming calls and
 * where subtitles and player controls never are.
 */
final class Notice {
    private Notice() {}

    static final int FOUND = 1, SEARCHING = 2, TIMED_OUT = 3, OFFLINE = 4;

    /** The overlay window, in dp: the card plus room for its shadow. */
    static final float OVERLAY_W = 452, OVERLAY_H = 172, OVERLAY_X = 20, OVERLAY_Y = 12;
    /** The card inside it. */
    static final float CARD_W = 396, CARD_H = 112, PAD = 28;
    /** How long the found alert's rings keep spreading. */
    static final float PULSE_SECONDS = 2.6f;

    static final class Spec {
        final int kind;
        final String meta, title, detail;
        final int accent;

        Spec(int kind, String meta, String title, String detail, int accent) {
            this.kind = kind;
            this.meta = meta;
            this.title = title;
            this.detail = detail;
            this.accent = accent;
        }
    }

    static String place(Model.Finder f) {
        return f.station == null || f.station.isEmpty() ? "the station" : f.station;
    }

    static Spec found(Model.Finder f, Model.Station s) {
        String count = f.ebikes == 1 ? "1 e-bike" : f.ebikes + " e-bikes";
        String detail = place(f) + " · " + count;
        if (s != null && s.live) detail += " · " + s.docks + (s.docks == 1 ? " dock" : " docks");
        boolean already = f.checks <= 1;
        return new Spec(FOUND, "BIKE FINDER", already ? "E-bike there now" : "E-bike docked", detail, Tone.VOLT);
    }

    static Spec searching(Model.Finder f, Model.Station s, long now) {
        long left = Math.max(0, f.deadline - now);
        return new Spec(SEARCHING, "BIKE FINDER", "Watching " + place(f),
                "Checking every 5 s · " + minutes(left) + " left", Tone.LIVE);
    }

    static Spec timedOut(Model.Finder f, Model.Station s) {
        return new Spec(TIMED_OUT, "BIKE FINDER", "No e-bike yet",
                "Stopped watching " + place(f) + " after " + minutes(f.ended - f.started), Tone.SLATE);
    }

    static Spec offline(Model.Finder f) {
        return new Spec(OFFLINE, "BIKE FINDER", "Reconnecting…", "Bluebikes is not answering. Still trying.",
                Tone.AMBER);
    }

    static String minutes(long ms) {
        long m = Math.max(1, Math.round(ms / 60000.0));
        if (m < 60) return m + " min";
        return String.format(Locale.US, "%d h %d min", m / 60, m % 60);
    }

    // ---- Drawing -----------------------------------------------------------------------------

    private static final int[] DARK = {0xF2262A36, 0xF21A1D27, 0xF2121419};
    private static final float[] THREE = {0f, 0.5f, 1f};
    private static final int[] RIM = {0x8CFFFFFF, 0x1AFFFFFF, 0x0DFFFFFF, 0x40FFFFFF};
    private static final float[] RIM_STOPS = {0f, 0.35f, 0.7f, 1f};
    private static final int[] VOLT_WELL = {0xFFFFE066, 0xFFFFC400};
    private static final int[] LIVE_WELL = {0xFF2C3A33, 0xFF1C2421};
    private static final int[] GREY_WELL = {0xFF3A3D45, 0xFF2A2C33};
    private static final float[] TWO = {0f, 1f};

    private static Object shadow;
    private static int shadowKey;

    /** The whole overlay window: shadow, dark glass, rim, accent edge and content. */
    static void drawOverlay(Gfx g, int w, int h, float d, Spec spec, float appear, float t) {
        if (appear <= 0.002f) return;
        float l = PAD * d, top = PAD * d, r = l + CARD_W * d, b = top + CARD_H * d;
        float a = Math.min(1, appear);
        float s = 0.92f + 0.08f * a;
        int save = g.save();
        g.translate((1 - a) * 18 * d, 0);
        g.scale(s, r, top);
        float rad = CARD_H * d / 2 * 0.5f;
        int key = Math.round(CARD_W * d) * 31 + Math.round(CARD_H * d);
        if (shadow == null || shadowKey != key) {
            float pad = 22 * d;
            int[] px = Pixels.shadow(r - l, b - top, rad, pad, 0.25f);
            int[] pixels = new int[px[0] * px[1]];
            System.arraycopy(px, 2, pixels, 0, pixels.length);
            shadow = g.image(pixels, px[0], px[1]);
            shadowKey = key;
        }
        float pad = 22 * d;
        g.drawImage(shadow, l - pad, top - pad + 8 * d, r + pad, b + pad + 8 * d, Math.round(255 * 0.55f * a));
        g.fillRoundRectLinear(l, top, r, b, rad, 0, top, 0, b, DARK, THREE, a);
        g.strokeRoundRectLinear(l + 0.5f * d, top + 0.5f * d, r - 0.5f * d, b - 0.5f * d, rad, d, l, top, r, b, RIM, RIM_STOPS, a);
        if (spec.kind == FOUND) {
            g.strokeRoundRect(l + d, top + d, r - d, b - d, rad - d, 2 * d, Pixels.alpha(spec.accent, 0.55f * a));
        }
        content(g, l, top, r, b, d, spec, a, t, false);
        g.restore(save);
    }

    /**
     * The card's content: a round well with the mark, then the small-caps source, the title and
     * the detail. {@code still} turns off the radar sweep (reduced motion).
     */
    static void content(Gfx g, float l, float t, float r, float b, float d, Spec spec, float a, float time,
            boolean still) {
        float h = b - t;
        float wellR = 30 * d, wx = l + 22 * d + wellR, wy = t + h / 2;
        // A found alert bounces its mark once and sends two rings out from the well.
        float bounce = 1;
        if (spec.kind == FOUND && !still && time < 0.6f) {
            bounce = 1 + 0.16f * (float) Math.sin(Math.PI * Math.min(1, time / 0.45f)) * (1 - time / 0.6f);
        }
        if (spec.kind == FOUND && !still && time < PULSE_SECONDS) {
            for (int i = 0; i < 2; i++) {
                float p = (time - i * 0.55f) / 1.4f;
                if (p <= 0 || p >= 1) continue;
                float eased = 1 - (1 - p) * (1 - p);
                g.strokeCircle(wx, wy, wellR * (1 + 0.55f * eased), 2 * d, Pixels.alpha(spec.accent, 0.6f * (1 - p) * a));
            }
        }
        int[] well = spec.kind == FOUND ? VOLT_WELL : spec.kind == SEARCHING ? LIVE_WELL : GREY_WELL;
        float wr = wellR * bounce;
        g.fillCircleLinear(wx, wy, wr, wx, wy - wr, wx, wy + wr, well, TWO, a);
        g.strokeCircle(wx, wy, wr - 0.5f * d, d, Pixels.white(0.22f * a));
        if (spec.kind == FOUND) {
            Icons.ebike(g, wx, wy + d, 40 * d * bounce, Pixels.alpha(0xFF16130A, a), Pixels.alpha(0xFF16130A, a));
        } else if (spec.kind == SEARCHING) {
            Icons.radar(g, wx, wy, 44 * d, time, still, Pixels.white(0.85f * a), Pixels.alpha(spec.accent, a));
        } else if (spec.kind == OFFLINE) {
            Icons.bike(g, wx, wy + d, 36 * d, Pixels.alpha(Tone.AMBER, a));
        } else {
            Icons.bike(g, wx, wy + d, 36 * d, Tone.text(Tone.SECONDARY, a));
        }
        float x = wx + wellR + 18 * d, maxW = r - x - 20 * d;
        g.text(spec.meta, Gfx.TEXT_SEMIBOLD, 11.5f * d, 0.12f, x, t + 33 * d, Pixels.alpha(spec.accent, 0.95f * a), Gfx.LEFT, 0);
        g.text(fit(g, spec.title, Gfx.TEXT_SEMIBOLD, 22 * d, maxW), Gfx.TEXT_SEMIBOLD, 22 * d, -0.005f, x, t + 64 * d,
                Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        g.text(fit(g, spec.detail, Gfx.TEXT_MEDIUM, 14.5f * d, maxW), Gfx.TEXT_MEDIUM, 14.5f * d, 0, x, t + 88 * d,
                Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
    }

    static String fit(Gfx g, String s, int face, float size, float max) {
        return Text.fit(g, s, face, size, max);
    }
}
