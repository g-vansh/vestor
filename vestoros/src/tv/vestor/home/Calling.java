package tv.vestor.home;

import java.util.Locale;
import java.util.Map;

/**
 * WhatsApp on the TV: the rules of the Calls screen that need no Android, so the desk tests can
 * check them. The screen itself is {@link Calls}.
 *
 * WhatsApp Web makes voice and video calls in a browser since July 2026. It serves desktop
 * browsers only and turns away anything older than Chrome 100, so the screen shows it in the
 * Fire TV's own web engine (Chromium, kept current by Amazon) under a desktop Chrome name at the
 * engine's real version, and lets it reach nothing but WhatsApp.
 */
final class Calling {
    private Calling() {}

    static final String HOME = "https://web.whatsapp.com/";
    static final String ORIGIN = "https://web.whatsapp.com";
    /** WhatsApp Web's own floor: "WhatsApp works with Google Chrome 100+". */
    static final int MIN_CHROME = 100;

    /** The engine's Chrome major version in a WebView user agent ("... Chrome/126.0.6478.71 ..."), 0 when absent. */
    static int chromeMajor(String agent) {
        if (agent == null) return 0;
        int i = agent.indexOf("Chrome/");
        if (i < 0) return 0;
        int j = i + "Chrome/".length(), n = 0, digits = 0;
        while (j < agent.length() && Character.isDigit(agent.charAt(j)) && digits < 4) {
            n = n * 10 + (agent.charAt(j++) - '0');
            digits++;
        }
        return n;
    }

    /** A desktop Chrome on Linux (which the Fire TV is) at the engine's own version. */
    static String desktopAgent(int major) {
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + ".0.0.0 Safari/537.36";
    }

    /**
     * Where the screen may go. The page itself only on web.whatsapp.com; frames inside it on
     * WhatsApp's own domains. Never plain HTTP, never another site: a link in a chat opens on the
     * phone, not here.
     */
    static boolean allowed(String url, boolean mainFrame) {
        if (url == null || !url.startsWith("https://")) return false;
        String rest = url.substring("https://".length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end).toLowerCase(Locale.US);
        // No user info or port tricks ("web.whatsapp.com@evil.example", ":8443").
        if (host.indexOf('@') >= 0 || host.indexOf(':') >= 0 || host.isEmpty()) return false;
        if (mainFrame) return "web.whatsapp.com".equals(host);
        return host.equals("whatsapp.com") || host.endsWith(".whatsapp.com") || host.equals("whatsapp.net")
                || host.endsWith(".whatsapp.net");
    }

    /** Whose camera and microphone requests are granted: WhatsApp Web's, and only over HTTPS. */
    static boolean mayCapture(String origin) {
        if (origin == null) return false;
        String o = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        return ORIGIN.equals(o);
    }

    /**
     * How far one press of an arrow moves the pointer, in screen pixels: fine for a single press,
     * faster while the key is held, so the pointer crosses the screen in about two seconds.
     */
    static float step(int repeat, float screenWidth) {
        float fine = screenWidth / 120f, fast = screenWidth / 24f;
        if (repeat <= 0) return fine;
        return Math.min(fast, fine * (1 + repeat * 0.35f));
    }

    /**
     * The Samsung's camera check, as its browser reported it, in one sentence for the screen.
     * Every number is clamped and nothing the page sent is shown as text, so a stranger on the
     * network cannot put words on the TV.
     */
    static String camReport(Map<String, Object> r) {
        if (r == null) return "";
        int engine = (int) Math.max(0, Math.min(999, Json.num(r, "engine", 0)));
        boolean api = Json.bool(r, "devices", false);
        int cams = (int) Math.max(0, Math.min(9, Json.num(r, "cameras", 0)));
        int mics = (int) Math.max(0, Math.min(9, Json.num(r, "mics", 0)));
        String who = engine > 0 ? "The Samsung's browser (Chromium " + engine + ")" : "The Samsung's browser";
        if (!api) return who + " cannot list cameras at all, so no app on it can use a webcam.";
        if (cams == 0) {
            return who + " sees no camera" + (mics > 0 ? " but " + count(mics, "microphone") : " and no microphone")
                    + ". A webcam on the TV's USB port cannot be used on this TV.";
        }
        return who + " sees " + count(cams, "camera") + " and " + count(mics, "microphone")
                + ". The TV can read the webcam, so a Samsung camera app for WhatsApp can work.";
    }

    private static String count(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
