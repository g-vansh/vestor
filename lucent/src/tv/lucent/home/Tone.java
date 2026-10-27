package tv.lucent.home;

/**
 * Colour and type roles, for two looks.
 *
 * Night (from sunset): warm dark glass over a dimmed photograph, warm white text (#F5F1EA rather
 * than pure white, which blooms on a TV), an ember accent. Day (from an hour after sunrise):
 * frosted light glass, near-black ink (#161514), a deep blue accent. Colour is spent only on
 * meaning (e-bikes, lines, weather) and every accent has a day and a night shade, each checked at
 * 4.5:1 or better against its glass.
 *
 * The look changes with a half-minute crossfade, so every role is a blend of its two shades by
 * {@link #mix} (0 night, 1 day), recomputed once a frame. Drawing is single-threaded (the main
 * thread), and overlays over other apps always draw at night ({@link #night()} before, {@link
 * #restore} after), whatever the home screen shows.
 */
final class Tone {
    private Tone() {}

    static final float PRIMARY = 1f, SECONDARY = 0.68f, TERTIARY = 0.46f;

    // Night and day shades, as pairs.
    private static final int[] TEXT_ = {0xFFF5F1EA, 0xFF161514};
    private static final int[] INK_ = {0xFF15161A, 0xFFF7F4EF};
    private static final int[] FOCUS_ = {0xFFF5F1EA, 0xFF1C1B19};
    private static final int[] VOLT_ = {0xFFFFD426, 0xFF9A6400};
    private static final int[] PEDAL_ = {0xFF64C8FF, 0xFF0A68B0};
    private static final int[] SLATE_ = {0xFFC9CED6, 0xFF555B66};
    private static final int[] LIVE_ = {0xFF30D158, 0xFF1A7F37};
    private static final int[] AMBER_ = {0xFFFFA040, 0xFFB45300};
    private static final int[] RAIN_ = {0xFF3CD3FE, 0xFF0077A3};
    private static final int[] ACCENT_ = {0xFFEC944C, 0xFF0C3981};
    private static final int[] ROSE_ = {0xFFFF8FA8, 0xFFC2185B};

    /** Text, at {@link #text} levels. */
    static int TEXT = TEXT_[0];
    /** Text on a focused (filled) control. */
    static int INK = INK_[0];
    /** The fill of a focused control. */
    static int FOCUS = FOCUS_[0];
    /** E-bikes, the scarce thing: the bolt and the e-bike count. */
    static int VOLT = VOLT_[0];
    /** Classic bikes. */
    static int PEDAL = PEDAL_[0];
    /** Open docks. */
    static int SLATE = SLATE_[0];
    /** Fresh, live data. */
    static int LIVE = LIVE_[0];
    /** Stale data, trouble, weather alerts. */
    static int AMBER = AMBER_[0];
    /** Rain chance. */
    static int RAIN = RAIN_[0];
    /** The one decorative accent: ember at night, blueprint blue by day. */
    static int ACCENT = ACCENT_[0];
    /** Notes and hearts. */
    static int ROSE = ROSE_[0];

    /** 0 night, 1 day. */
    static float mix;
    private static float saved = -1;

    /** Sets the look for this frame: 0 night, 1 day, between while the look changes. */
    static void use(float dayMix) {
        mix = Math.max(0, Math.min(1, dayMix));
        TEXT = pick(TEXT_);
        INK = pick(INK_);
        FOCUS = pick(FOCUS_);
        VOLT = pick(VOLT_);
        PEDAL = pick(PEDAL_);
        SLATE = pick(SLATE_);
        LIVE = pick(LIVE_);
        AMBER = pick(AMBER_);
        RAIN = pick(RAIN_);
        ACCENT = pick(ACCENT_);
        ROSE = pick(ROSE_);
    }

    /** Night colours for a moment (an overlay over another app), remembering the home screen's. */
    static void night() {
        saved = mix;
        use(0);
    }

    static void restore() {
        if (saved >= 0) use(saved);
        saved = -1;
    }

    static boolean day() {
        return mix > 0.5f;
    }

    private static int pick(int[] pair) {
        return mix <= 0 ? pair[0] : mix >= 1 ? pair[1] : Pixels.lerp(pair[0], pair[1], mix);
    }

    static int text(float level, float alpha) {
        // Dark ink needs a touch more weight than light text to read the same.
        float l = mix > 0 ? Math.min(1, level + 0.08f * mix * (1 - level)) : level;
        return Pixels.alpha(TEXT, l * alpha);
    }

    /** Text on a fill that blends from glass ({@code on} 0) to the focus fill (1). */
    static int onFocus(float on, float alpha) {
        return Pixels.alpha(Pixels.lerp(TEXT, INK, on), alpha);
    }

    static int focus(float alpha) {
        return Pixels.alpha(FOCUS, alpha);
    }

    /** Thin rules between things: light at night, dark by day. */
    static int hairline(float alpha) {
        return Pixels.alpha(TEXT, (0.14f + 0.02f * mix) * alpha);
    }
}
