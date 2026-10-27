package tv.vestor.home;

/**
 * Where a WhatsApp call's sound and your voice go, from what is connected to the Fire TV. The rules
 * need no Android, so the desk tests check them; {@link CallRouter} carries them out.
 *
 * Bluetooth headphones play sound over the stereo profile (A2DP) and offer their microphone only
 * over the hands-free profile (HFP), the way a phone call uses them. Two facts of Android 11 decide
 * the rest, both from its source:
 * <ul>
 * <li>In call mode (MODE_IN_COMMUNICATION) Android plays nothing on stereo-only headphones. Call
 * and media sound both go to a wired or USB device, else the TV's own output.</li>
 * <li>The web engine turns call mode on by itself when the device has an echo canceller, and picks
 * the call microphone in this order: wired, USB (a webcam), Bluetooth headset, built in.</li>
 * </ul>
 * So AirPods whose microphone Fire OS offers get a Bluetooth call link both ways, opened before
 * WhatsApp opens the microphone. AirPods for sound only keep Android out of call mode, so the call
 * plays in them the way music does, and your voice comes from the webcam.
 */
final class CallAudio {
    private CallAudio() {}

    /** The TV's own sound and the engine's own pick of microphone. */
    static final int TV = 0;
    /** Call sound in the headphones (stereo), your voice through the webcam. */
    static final int EARS = 1;
    /** Call sound and your voice through the headphones (hands-free link). */
    static final int BOTH = 2;

    /** The voice setting: the headphones' microphone when they offer one, or the webcam's. */
    static final String HEADSET = "headset", WEBCAM = "webcam";

    /** What is connected right now. */
    static final class Facts {
        /** Bluetooth headphones whose microphone Fire OS offers (the hands-free profile is connected). */
        boolean headset;
        /** Fire OS lets apps use a Bluetooth headset's microphone outside a phone call. */
        boolean offCall = true;
        /** Bluetooth headphones playing sound (the stereo profile is connected). */
        boolean headphones;
        /** A USB or wired microphone, such as a webcam's. */
        boolean mic;
        /** The headphones call themselves AirPods. */
        boolean airpods;
    }

    /** Where the call goes. Without another microphone the headphones' one is used whatever the setting. */
    static int route(Facts f, String voice) {
        boolean headsetMic = f.headset && f.offCall;
        if (headsetMic && (!WEBCAM.equals(voice) || !f.mic)) return BOTH;
        if (f.headphones) return EARS;
        return TV;
    }

    /** Whether a name is AirPods ("Vansh's AirPods Pro", "AirPods Max"). */
    static boolean airpods(String name) {
        return name != null && name.toLowerCase(java.util.Locale.US).contains("airpods");
    }

    private static String them(Facts f) {
        return f.airpods ? "your AirPods" : "your Bluetooth headphones";
    }

    /**
     * The line shown when a call starts. {@code failed}: the headphones' microphone was tried and
     * did not start, so the call fell back to them for sound only.
     */
    static String line(int route, Facts f, boolean failed) {
        String them = them(f);
        if (route == BOTH) return "Call sound and your voice go through " + them + ".";
        if (route == EARS) {
            String first = failed ? "The microphone in " + them + " did not start. " : "";
            if (f.mic) return first + "Call sound plays in " + them + " and your voice goes through the webcam.";
            if (failed) return first + "Call sound plays in them. For your voice, plug a USB webcam into the Fire TV.";
            return "Call sound plays in " + them + ". Fire OS offers them for listening only, so for your voice plug a USB webcam "
                    + "into the Fire TV.";
        }
        return f.mic ? "" : "No microphone found. For your voice, plug a USB webcam into the Fire TV.";
    }

    /** The line shown when the headphones' call link drops during a call (put away, out of range). */
    static String dropped(int route, Facts f) {
        if (route == EARS) {
            return "The microphone in " + them(f) + " stopped. Call sound stays in them"
                    + (f.mic ? " and your voice goes through the webcam." : ".");
        }
        return (f.airpods ? "Your AirPods" : "Your Bluetooth headphones") + " disconnected, so call sound plays on the TV.";
    }

    /** The menu's check, in two or three sentences. */
    static String describe(Facts f, String voice) {
        String them = them(f), They = f.airpods ? "Your AirPods" : "Your Bluetooth headphones";
        StringBuilder b = new StringBuilder();
        if (f.headset && f.offCall) {
            b.append(They).append(" are connected and Fire OS offers their microphone. ");
            b.append(WEBCAM.equals(voice) && f.mic ? "Calls take your voice from the webcam and play in " + them + " in full sound."
                    : "Calls use " + them + " both ways, at phone-call sound quality.");
        } else if (f.headset) {
            b.append(They).append(" are connected, but Fire OS keeps their microphone for phone calls. Calls play in ")
                    .append(them).append('.');
        } else if (f.headphones) {
            b.append(They).append(" are connected for listening. Fire OS does not offer their microphone, so calls play in ")
                    .append(them).append(" and your voice needs another microphone.");
        } else {
            b.append("No Bluetooth headphones are connected, so calls play on the TV.");
        }
        b.append(f.mic ? " A webcam or USB microphone is plugged in." : " No USB microphone is plugged in.");
        return b.toString();
    }
}
