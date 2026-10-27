package tv.lucent.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Bike finder's alarm: when an e-bike can be rented, the ringtone ({@link Bell#ring}) loops
 * until someone stops it, from the remote (OK or Back on the alert), the control center, or
 * Alexa. It starts at half volume and rises to full over 20 seconds, like a gentle phone alarm,
 * and gives up by itself after the chosen limit (10 minutes unless changed) so a house left
 * empty is not rung at all evening. It is mixed over whatever plays and never pauses it.
 */
final class Alarm {
    interface Listener {
        void ringing(boolean on);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AudioTrack track;
    private static long started;
    private static long limitMs = 10 * 60000L;
    private static Listener listener;

    private Alarm() {}

    static void listen(Listener l) {
        listener = l;
    }

    static void setLimit(long ms) {
        limitMs = Math.max(60000L, Math.min(60 * 60000L, ms));
    }

    static boolean ringing() {
        return track != null;
    }

    /** Starts ringing (main thread). Already ringing: nothing changes. */
    static void ring() {
        if (track != null) return;
        try {
            short[] s = Bell.ring();
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Bell.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(s.length * 2)
                    .build();
            t.write(s, 0, s.length);
            t.setLoopPoints(0, s.length, -1);
            t.setVolume(0.5f);
            t.play();
            track = t;
            started = System.currentTimeMillis();
            MAIN.post(swell);
            notifyListener(true);
        } catch (RuntimeException e) {
            // No alarm sound is better than a crash. The visual alert still shows.
            track = null;
            Chime.play();
        }
    }

    /** Stops ringing (main thread). */
    static void silence() {
        MAIN.removeCallbacks(swell);
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.release();
            } catch (RuntimeException ignored) {
                // already released
            }
            notifyListener(false);
        }
    }

    private static final Runnable swell = new Runnable() {
        @Override
        public void run() {
            AudioTrack t = track;
            if (t == null) return;
            long ms = System.currentTimeMillis() - started;
            if (ms > limitMs) {
                silence();
                return;
            }
            try {
                t.setVolume(Math.min(1f, 0.5f + 0.5f * ms / 20000f));
            } catch (RuntimeException ignored) {
                // volume stays where it is
            }
            MAIN.postDelayed(this, 500);
        }
    };

    private static void notifyListener(boolean on) {
        Listener l = listener;
        if (l != null) l.ringing(on);
    }
}
