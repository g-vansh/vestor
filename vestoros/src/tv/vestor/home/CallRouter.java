package tv.vestor.home;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;

import java.util.List;

/**
 * Carries out {@link CallAudio} for the Calls screen: where a call's sound and your voice go while
 * WhatsApp has the microphone open, and everything put back when it closes it.
 *
 * <ul>
 * <li>AirPods with their microphone (BOTH): the Bluetooth call link (SCO) is opened before WhatsApp
 * gets the microphone, so the engine's microphone opens on the AirPods, and call mode sends the
 * call's sound there too. If the link does not come up in a few seconds, the call goes on with the
 * AirPods for sound only.</li>
 * <li>AirPods for sound only (EARS): Android is kept out of call mode and off the speakerphone
 * setting, so the call plays in the AirPods the way music does.</li>
 * <li>Otherwise (TV): nothing is changed. The engine's own choices stand.</li>
 * </ul>
 *
 * Every change is one the system also undoes when this app's process ends: the call link belongs
 * to this app's audio client, and call mode to its process.
 */
final class CallRouter {
    interface Out {
        void say(String text);
    }

    /** How long the AirPods' call link may take to come up before the call goes on without it. */
    private static final long LINK_MS = 4000;
    /** How long after the microphone is granted the call may take to start recording. */
    private static final long START_MS = 30000;
    /** A microphone closed and opened again this quickly is the same call (the engine restarting it). */
    private static final long GAP_MS = 1500;

    private final Activity activity;
    private final AudioManager am;
    private final Handler main;
    private final Out out;
    private final Prefs prefs;

    private CallAudio.Facts facts = new CallAudio.Facts();
    private int route = CallAudio.TV;
    private boolean live, linkAsked, linkUp, modeSet, failed, receiver;
    /**
     * A microphone grant came from this screen and its recording has not ended. Android tells every
     * app about every recording (unnamed), so only one after a grant here counts as the call.
     */
    private boolean armed;
    /** The link failed before the call began: the first line of the call says so. */
    private boolean failedEarly;
    /** The engine had turned call mode on, so its call sound is on the voice stream. */
    private boolean engineCall;
    /** The grant waiting for the call link. */
    private Runnable waiting;

    private final AudioManager.AudioRecordingCallback recordings = new AudioManager.AudioRecordingCallback() {
        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            recording(configs != null && !configs.isEmpty());
        }
    };

    private final BroadcastReceiver link = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (isInitialStickyBroadcast()) return;
            int state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) linkUp();
            else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED || state == AudioManager.SCO_AUDIO_STATE_ERROR) linkDown();
        }
    };

    private final Runnable linkLate = new Runnable() {
        @Override
        public void run() {
            if (linkUp) return;
            if (am.isBluetoothScoOn()) linkUp();
            else linkDown();
        }
    };

    private final Runnable neverStarted = new Runnable() {
        @Override
        public void run() {
            if (!live) end();
        }
    };

    private final Runnable ended = new Runnable() {
        @Override
        public void run() {
            end();
        }
    };

    /** Once more after the microphone opened, in case the engine switched call mode on a moment late. */
    private final Runnable settle = new Runnable() {
        @Override
        public void run() {
            if (live) earsOrTv();
        }
    };

    CallRouter(Activity activity, Prefs prefs, Handler main, Out out) {
        this.activity = activity;
        // The activity's own audio client, apart from the one the web engine uses, so neither
        // undoes the other's call link or speakerphone choice.
        this.am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        this.main = main;
        this.out = out;
        this.prefs = prefs;
        if (am != null) am.registerAudioRecordingCallback(recordings, main);
    }

    /** The voice setting, from the shared settings. */
    private String voice() {
        return prefs.choice(Prefs.CALL_VOICE);
    }

    /**
     * WhatsApp is about to get the microphone: with AirPods to talk through, their call link comes
     * up first, then {@code grant} runs (at once otherwise, and within {@link #LINK_MS} at worst).
     */
    void beforeMic(Runnable grant) {
        if (am == null) {
            grant.run();
            return;
        }
        armed = true;
        main.removeCallbacks(neverStarted);
        main.postDelayed(neverStarted, START_MS);
        if (live || linkAsked) {
            grant.run();
            return;
        }
        facts = probe(activity);
        if (failed) facts.headset = false;
        route = CallAudio.route(facts, voice());
        if (route != CallAudio.BOTH) {
            grant.run();
            return;
        }
        if (!receiver) {
            activity.registerReceiver(link, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
            receiver = true;
        }
        waiting = grant;
        linkAsked = true;
        linkUp = false;
        try {
            am.startBluetoothSco();
        } catch (RuntimeException e) {
            linkDown();
            return;
        }
        // Already up (the engine opened it for an earlier stream): Android sends no new notice.
        if (am.isBluetoothScoOn()) linkUp();
        else main.postDelayed(linkLate, LINK_MS);
    }

    private void release() {
        Runnable r = waiting;
        waiting = null;
        if (r != null) r.run();
    }

    private void linkUp() {
        if (!linkAsked || linkUp) return;
        linkUp = true;
        main.removeCallbacks(linkLate);
        // Call mode, so the call's sound (on the voice stream or as media) takes the link too.
        if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            modeSet = true;
        }
        activity.setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        release();
    }

    /** The link did not come up, or dropped (AirPods put away, out of range): sound only, from here on. */
    private void linkDown() {
        if (!linkAsked) return;
        boolean wasUp = linkUp;
        main.removeCallbacks(linkLate);
        stopLink();
        failed = true;
        boolean airpods = facts.airpods;
        facts = probe(activity);
        facts.headset = false;
        facts.airpods |= airpods;
        route = CallAudio.route(facts, voice());
        if (!live) {
            // Said when the call's microphone opens, not over a call that never starts.
            failedEarly = !wasUp;
            release();
            return;
        }
        earsOrTv();
        say(wasUp ? CallAudio.dropped(route, facts) : CallAudio.line(route, facts, true));
    }

    private void stopLink() {
        if (!linkAsked) return;
        linkAsked = false;
        linkUp = false;
        try {
            am.stopBluetoothSco();
        } catch (RuntimeException ignored) {
            // the link is gone either way
        }
        if (modeSet) {
            am.setMode(AudioManager.MODE_NORMAL);
            modeSet = false;
        }
    }

    /** A microphone opened or closed, anywhere on the Fire TV. */
    private void recording(boolean on) {
        if (am == null) return;
        if (!on) {
            if (live) main.postDelayed(ended, GAP_MS);
            return;
        }
        main.removeCallbacks(ended);
        if (live) {
            // Opened again within the call: the engine may have turned call mode back on.
            earsOrTv();
            return;
        }
        if (!armed) return;
        live = true;
        main.removeCallbacks(neverStarted);
        if (!linkAsked) {
            // The route chosen at the grant stands. A call link opened under a running microphone
            // could cut it off, so a route of BOTH found only now is left to the engine.
            if (route == CallAudio.BOTH) route = facts.headphones ? CallAudio.EARS : CallAudio.TV;
            engineCall = am.getMode() == AudioManager.MODE_IN_COMMUNICATION;
            earsOrTv();
            main.postDelayed(settle, GAP_MS);
        }
        say(CallAudio.line(route, facts, failedEarly));
        failedEarly = false;
    }

    /** AirPods for sound only: out of call mode and off the speakerphone, so the call plays in them. */
    private void earsOrTv() {
        if (route != CallAudio.EARS) return;
        if (am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
            engineCall = true;
            am.setMode(AudioManager.MODE_NORMAL);
        }
        if (am.isSpeakerphoneOn()) am.setSpeakerphoneOn(false);
        activity.setVolumeControlStream(engineCall ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
    }

    /** The call is over: everything as it was. */
    private void end() {
        live = false;
        armed = false;
        failedEarly = false;
        main.removeCallbacks(linkLate);
        main.removeCallbacks(neverStarted);
        main.removeCallbacks(ended);
        main.removeCallbacks(settle);
        stopLink();
        release();
        engineCall = false;
        route = CallAudio.TV;
        activity.setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
    }

    /** The screen is closing. */
    void close() {
        end();
        if (am != null) am.unregisterAudioRecordingCallback(recordings);
        if (receiver) {
            try {
                activity.unregisterReceiver(link);
            } catch (RuntimeException ignored) {
                // already gone
            }
            receiver = false;
        }
    }

    private void say(String text) {
        if (text != null && !text.isEmpty()) out.say(text);
    }

    /** The menu's check of the AirPods and microphones. */
    String check() {
        return CallAudio.describe(probe(activity), voice());
    }

    /** What is connected: Bluetooth headphones and their profiles, and wired or USB microphones. */
    static CallAudio.Facts probe(Context c) {
        CallAudio.Facts f = new CallAudio.Facts();
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return f;
        f.offCall = am.isBluetoothScoAvailableOffCall();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                f.headphones = true;
                CharSequence n = d.getProductName();
                if (CallAudio.airpods(n == null ? null : n.toString())) f.airpods = true;
            }
        }
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                f.headset = true;
                CharSequence n = d.getProductName();
                if (CallAudio.airpods(n == null ? null : n.toString())) f.airpods = true;
            } else if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                f.mic = true;
            }
        }
        // Android lists the headset's microphone once the hands-free profile connects; the profile
        // state is asked too, in case a build lists it late.
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED) {
                f.headset = true;
            }
        } catch (RuntimeException ignored) {
            // no Bluetooth permission or no adapter: the device list above stands
        }
        return f;
    }
}
