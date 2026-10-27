package tv.vestor.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Bike finder's alarm: when an e-bike can be rented, the ringtone ({@link Bell#ring}) loops
 * until someone stops it, from the remote (any button on the alert), the control center, or
 * Alexa. It rings at full volume from the first second (the TV's or soundbar's own volume sets
 * how loud that is), and gives up by itself after the chosen limit (10 minutes unless changed)
 * so a house left empty is not rung at all evening. It is mixed over whatever plays and never
 * pauses it.
 */
final class Alarm {
    interface Listener {
        void ringing(boolean on);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AudioTrack track;
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
            t.setVolume(1f);
            t.play();
            track = t;
            MAIN.postDelayed(limit, limitMs);
            notifyListener(true);
        } catch (RuntimeException e) {
            // No alarm sound is better than a crash. The visual alert still shows.
            track = null;
            Chime.play();
        }
    }

    /** Stops ringing (main thread). */
    static void silence() {
        MAIN.removeCallbacks(limit);
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

    /** The house is empty: the limit passed and nobody stopped it. */
    private static final Runnable limit = new Runnable() {
        @Override
        public void run() {
            silence();
        }
    };

    private static void notifyListener(boolean on) {
        Listener l = listener;
        if (l != null) l.ringing(on);
    }
}
