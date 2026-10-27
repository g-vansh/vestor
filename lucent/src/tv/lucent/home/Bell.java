package tv.lucent.home;

/**
 * The bike finder's chime, synthesised (no audio file): two glassy bell notes a fifth apart. Each
 * note is a sum of slightly inharmonic partials that decay at different rates, which is what
 * makes a struck glass or small bell sound bright and then soft. Platform-free so the tests can
 * check it for clipping.
 */
final class Bell {
    private Bell() {}

    static final int RATE = 48000;
    private static short[] pcm;

    /** Partial ratios and levels: a fundamental, its octave, and two glassy upper partials. */
    private static final double[] RATIO = {1.0, 2.0, 3.01, 4.17, 5.43};
    private static final double[] LEVEL = {1.0, 0.32, 0.14, 0.06, 0.025};

    private static short[] ring;

    /**
     * The alarm's ringtone: one phrase of five glass notes climbing an E major arpeggio and
     * falling back (E6, G#6, B6, E7, B6), then a breath of silence, 2.4 s in all. It loops
     * seamlessly until stopped, the way a phone alarm does, so it reads as "come now" without
     * being harsh.
     */
    static synchronized short[] ring() {
        if (ring != null) return ring;
        double[] notes = {1318.51, 1661.22, 1975.53, 2637.02, 1975.53};
        double[] onsets = {0.0, 0.16, 0.32, 0.48, 0.72};
        double length = 2.4;
        int n = (int) (RATE * length);
        double[] mix = new double[n];
        for (int k = 0; k < notes.length; k++) {
            int start = (int) (onsets[k] * RATE);
            double gain = k == 3 ? 1.0 : 0.8;
            for (int p = 0; p < RATIO.length; p++) {
                double f = notes[k] * RATIO[p];
                if (f > RATE * 0.45) continue;
                double tau = 0.42 / (1 + 0.85 * p);
                double w = 2 * Math.PI * f / RATE;
                for (int i = start; i < n; i++) {
                    double t = (i - start) / (double) RATE;
                    if (t > 1.6) break;
                    double attack = t < 0.004 ? 0.5 - 0.5 * Math.cos(Math.PI * t / 0.004) : 1.0;
                    mix[i] += gain * LEVEL[p] * attack * Math.exp(-t / tau) * Math.sin(w * (i - start));
                }
            }
        }
        double peak = 0;
        for (double v : mix) peak = Math.max(peak, Math.abs(v));
        double scale = peak > 0 ? 0.5 / peak : 0;
        short[] out = new short[n];
        // The tail fades to silence, so the loop point never clicks.
        int fadeFrom = n - RATE / 5;
        for (int i = 0; i < n; i++) {
            double v = mix[i] * scale;
            if (i > fadeFrom) v *= (n - i) / (double) (n - fadeFrom);
            out[i] = (short) Math.round(v * 32767);
        }
        ring = out;
        return ring;
    }

    static synchronized short[] samples() {
        if (pcm != null) return pcm;
        double[] notes = {880.0, 1318.51};
        double[] onsets = {0.0, 0.13};
        double length = 1.6;
        int n = (int) (RATE * length);
        double[] mix = new double[n];
        for (int k = 0; k < notes.length; k++) {
            int start = (int) (onsets[k] * RATE);
            double gain = k == 0 ? 0.85 : 1.0;
            for (int p = 0; p < RATIO.length; p++) {
                double f = notes[k] * RATIO[p];
                if (f > RATE * 0.45) continue;
                double tau = 0.62 / (1 + 0.85 * p);
                double w = 2 * Math.PI * f / RATE;
                for (int i = start; i < n; i++) {
                    double t = (i - start) / (double) RATE;
                    double attack = t < 0.004 ? 0.5 - 0.5 * Math.cos(Math.PI * t / 0.004) : 1.0;
                    mix[i] += gain * LEVEL[p] * attack * Math.exp(-t / tau) * Math.sin(w * (i - start));
                }
            }
        }
        double peak = 0;
        for (double v : mix) peak = Math.max(peak, Math.abs(v));
        double scale = peak > 0 ? 0.42 / peak : 0;
        short[] out = new short[n];
        int fadeFrom = n - RATE / 10;
        for (int i = 0; i < n; i++) {
            double v = mix[i] * scale;
            if (i > fadeFrom) v *= (n - i) / (double) (n - fadeFrom);
            out[i] = (short) Math.round(v * 32767);
        }
        pcm = out;
        return pcm;
    }

}
