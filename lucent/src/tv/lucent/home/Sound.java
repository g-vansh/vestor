package tv.lucent.home;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.HashSet;
import java.util.Set;

/**
 * What the HDMI chain accepts, as the TV reports it to the stick (the TV relays the soundbar's
 * capabilities over eARC). Read-only: the launcher never touches audio output. Playback apps
 * negotiate their own formats; on this stick streaming apps are transcoded to Dolby MAT.
 */
final class Sound {
    /** AudioDeviceInfo.TYPE_HDMI_EARC, added after Android 11; matched by value. */
    private static final int TYPE_HDMI_EARC = 29;

    private Sound() {}

    static Model.Audio read(Context c) {
        Set<Integer> enc = new HashSet<>();
        int channels = 0;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = d.getType();
                    if (t != AudioDeviceInfo.TYPE_HDMI && t != AudioDeviceInfo.TYPE_HDMI_ARC && t != TYPE_HDMI_EARC) continue;
                    for (int e : d.getEncodings()) enc.add(e);
                    for (int n : d.getChannelCounts()) channels = Math.max(channels, n);
                }
            } catch (RuntimeException ignored) {
                // some builds refuse device queries; the sticky broadcast below still answers
            }
        }
        try {
            Intent plug = c.registerReceiver(null, new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));
            if (plug != null) {
                int[] e = plug.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS);
                if (e != null) for (int x : e) enc.add(x);
                channels = Math.max(channels, plug.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 0));
            }
        } catch (RuntimeException ignored) {
            // not available
        }
        return describe(enc, channels);
    }

    /**
     * The HDMI picture: the mode the stick is sending, the best one the TV offers (from its
     * EDID) and the HDR kinds it accepts, the way Amazon's own 4K check reads them. Apps
     * draw at 1080p on Fire TV whatever the mode; films decode straight to the 4K plane.
     */
    static Model.Picture picture(Context c) {
        Model.Picture p = new Model.Picture();
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) {
                Display.Mode now = d.getMode();
                p.width = now.getPhysicalWidth();
                p.height = now.getPhysicalHeight();
                p.hz = Math.round(now.getRefreshRate());
                for (Display.Mode m : d.getSupportedModes()) {
                    int w = m.getPhysicalWidth(), h = m.getPhysicalHeight();
                    if ((long) w * h > (long) p.maxWidth * p.maxHeight) {
                        p.maxWidth = w;
                        p.maxHeight = h;
                    }
                    if (w >= 3840) p.max4kHz = Math.max(p.max4kHz, Math.round(m.getRefreshRate()));
                }
                DisplayMetrics metrics = new DisplayMetrics();
                d.getRealMetrics(metrics);
                p.uiWidth = metrics.widthPixels;
                p.uiHeight = metrics.heightPixels;
                Display.HdrCapabilities hdr = d.getHdrCapabilities();
                int[] types = hdr == null ? new int[0] : hdr.getSupportedHdrTypes();
                // Display.HDR_TYPE_*: 1 Dolby Vision, 2 HDR10, 3 HLG, 4 HDR10+ (named on Android 10).
                String[] names = {"", "Dolby Vision", "HDR10", "HLG", "HDR10+"};
                for (int want : new int[] {2, 4, 3, 1}) {
                    for (int t : types) if (t == want) p.hdr.add(names[want]);
                }
            }
        } catch (RuntimeException ignored) {
            // an unreadable display says nothing rather than something wrong
        }
        p.describe();
        return p;
    }

    static Model.Audio describe(Set<Integer> enc, int channels) {
        Model.Audio a = new Model.Audio();
        a.channels = channels;
        boolean joc = enc.contains(AudioFormat.ENCODING_E_AC3_JOC);
        boolean mat = enc.contains(AudioFormat.ENCODING_DOLBY_MAT);
        boolean truehd = enc.contains(AudioFormat.ENCODING_DOLBY_TRUEHD);
        boolean ddp = enc.contains(AudioFormat.ENCODING_E_AC3);
        boolean dd = enc.contains(AudioFormat.ENCODING_AC3);
        boolean dtshd = enc.contains(AudioFormat.ENCODING_DTS_HD);
        boolean dts = enc.contains(AudioFormat.ENCODING_DTS);
        a.atmos = joc || mat;
        if (a.atmos) a.formats.add("Dolby Atmos");
        if (mat) a.formats.add("Dolby MAT");
        if (truehd) a.formats.add("Dolby TrueHD");
        if (ddp) a.formats.add("Dolby Digital Plus");
        if (dd) a.formats.add("Dolby Digital");
        if (dtshd) a.formats.add("DTS-HD");
        else if (dts) a.formats.add("DTS");
        if (channels >= 6) a.formats.add("Multichannel PCM");
        else if (channels > 0 || a.formats.isEmpty()) a.formats.add("Stereo PCM");
        a.known = !enc.isEmpty() || channels > 0;
        a.headline = a.atmos ? "Dolby Atmos" : ddp ? "Dolby Digital Plus" : dd ? "Dolby Digital" : a.known ? "Stereo" : "Sound";
        return a;
    }
}
