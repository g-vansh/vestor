package tv.lucent.home;

/**
 * Colour and type roles. Text is off-white at three vibrancy levels; colour is spent only on
 * meaning (e-bikes, lines, weather) and checked at 4.5:1 or better against dimmed glass.
 */
final class Tone {
    private Tone() {}

    /** Off-white: pure white blooms on a TV. */
    static final int TEXT = 0xFFF5F5F7;
    static final float PRIMARY = 1f, SECONDARY = 0.70f, TERTIARY = 0.45f;
    /** Dark text on the white focus fill. */
    static final int INK = 0xFF15161A;

    /** E-bikes, the scarce thing: the bolt and the e-bike count. */
    static final int VOLT = 0xFFFFD426;
    /** Classic bikes. */
    static final int PEDAL = 0xFF64C8FF;
    /** Open docks. */
    static final int SLATE = 0xFFC9CED6;
    /** Fresh, live data. */
    static final int LIVE = 0xFF30D158;
    /** Stale data, trouble. */
    static final int AMBER = 0xFFFFA040;
    /** Rain chance. */
    static final int RAIN = 0xFF3CD3FE;

    static int text(float level, float alpha) {
        return Pixels.alpha(TEXT, level * alpha);
    }
}
