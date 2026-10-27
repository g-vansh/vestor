package tv.lucent.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Plays {@link Bell}'s chime. It is mixed over whatever is playing and never asks for audio
 * focus, so a film keeps playing underneath.
 */
final class Chime {
    private Chime() {}

    /** Plays the chime once. Safe from any thread; failures are silent. */
    static void play() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    short[] s = Bell.samples();
                    track = new AudioTrack.Builder()
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
                    track.write(s, 0, s.length);
                    track.play();
                    Thread.sleep(s.length * 1000L / Bell.RATE + 200);
                } catch (RuntimeException | InterruptedException ignored) {
                    // no sound is better than a crash
                } finally {
                    if (track != null) track.release();
                }
            }
        }, "chime").start();
    }
}
