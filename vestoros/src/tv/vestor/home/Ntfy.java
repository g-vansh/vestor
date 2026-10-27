package tv.vestor.home;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lets an Alexa routine reach the TV without opening anything on the home network: Vestor
 * listens to one private ntfy.sh topic (a public relay), and the routine's URL switch publishes
 * a word to it. "Alexa, find an e-bike" in the bedroom then starts bike finder on the TV and
 * wakes it.
 *
 * The topic name is the only key, so it is long and random ("lc-" and 26 characters, about 130
 * bits), made on the TV, and can be changed from the control center at any time. A leaked
 * topic can do little, by design: only these words do anything, nothing else is read, and each
 * message counts once and only within two minutes of being sent.
 *
 * - find-ebike, stop-ebike: start bike finder (and wake the TV), or stop it and the alarm
 * - night, day, auto: the evening or daytime look, or back to following the sun
 * - glare-on, glare-off: the Samsung's bright picture for a sunny room, or back again
 * - vestor, firetv: bring Vestor to the front, or open Fire TV's own home
 * - wifi, wifi-hide: show the guest Wi-Fi code on the TV, or put it away
 * - hearts: hearts float up the screen
 *
 * The same relay also carries alerts the other way, to phones running the ntfy app, on a
 * second topic of their own ({@link #pushUrl}).
 */
final class Ntfy {
    interface Sink {
        void command(String word);
    }

    static final String[] WORDS = {"find-ebike", "stop-ebike", "night", "day", "auto", "glare-on", "glare-off", "vestor",
        "firetv", "wifi", "wifi-hide", "hearts"};

    /** The commands an Alexa URL switch can send, as {name, what to say, word for on, word for off}. */
    static final String[][] SWITCHES = {
        {"Bike finder", "Alexa, turn on bike finder", "find-ebike", "stop-ebike"},
        {"Glare mode", "Alexa, turn on glare mode", "glare-on", "glare-off"},
        {"Night look", "Alexa, turn on night look", "night", "auto"},
        {"Vestor", "Alexa, turn on Vestor", "vestor", "firetv"},
        {"Guest Wi-Fi", "Alexa, turn on guest Wi-Fi", "wifi", "wifi-hide"},
        {"Hearts", "Alexa, turn on hearts", "hearts", ""},
    };
    private static final long FRESH_MS = 120000L;

    private final String topic;
    private final Sink sink;
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 200;
        }
    };
    private Stream stream;

    Ntfy(String topic, Sink sink) {
        this.topic = topic;
        this.sink = sink;
    }

    static boolean validTopic(String t) {
        return t != null && t.matches("lc-[a-z2-7]{26}");
    }

    /** A new random topic: "lc-" and 26 base32 letters. */
    static String newTopic(java.security.SecureRandom r) {
        final String abc = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder b = new StringBuilder("lc-");
        for (int i = 0; i < 26; i++) b.append(abc.charAt(r.nextInt(32)));
        return b.toString();
    }

    /** The address an Alexa URL switch calls to send {@code word}. */
    static String publishUrl(String topic, String word) {
        return "https://ntfy.sh/" + topic + "/publish?message=" + word;
    }

    /**
     * A notification for phones subscribed to {@code topic} in the ntfy app, published with a
     * GET (ntfy takes every header as a parameter), so it goes through Net and its governor.
     * {@code click} is opened when the notification is tapped, or empty.
     */
    static String pushUrl(String topic, String title, String message, int priority, String tags, String click) {
        StringBuilder b = new StringBuilder("https://ntfy.sh/").append(topic).append("/publish?title=").append(enc(title))
                .append("&message=").append(enc(message)).append("&priority=").append(Math.max(1, Math.min(5, priority)));
        if (!tags.isEmpty()) b.append("&tags=").append(enc(tags));
        if (!click.isEmpty()) b.append("&click=").append(enc(click));
        return b.toString();
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    synchronized void start() {
        if (stream != null || !validTopic(topic)) return;
        // since=2m: a command sent while the stream was reconnecting is still heard.
        stream = new Stream("https://ntfy.sh/" + topic + "/json?since=2m", null, Stream.LINES, 90000, new Stream.Handler() {
            @Override
            public void event(String type, String data) {
                line(data, System.currentTimeMillis());
            }

            @Override
            public void state(boolean open) {
                // nothing to show: the control center says whether the link is on
            }
        });
        stream.start();
    }

    synchronized void stop() {
        if (stream != null) stream.stop();
        stream = null;
    }

    /** One line of the topic's JSON stream. Package-private for the tests. */
    void line(String json, long now) {
        if (json.length() > 8192) return;
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(json));
        } catch (IllegalArgumentException | StackOverflowError e) {
            return;
        }
        if (m == null || !"message".equals(Json.str(m, "event", ""))) return;
        String id = Json.str(m, "id", "");
        long time = (long) Json.num(m, "time", 0) * 1000L;
        if (id.isEmpty() || id.length() > 32 || Math.abs(now - time) > FRESH_MS) return;
        synchronized (seen) {
            if (seen.containsKey(id)) return;
            seen.put(id, Boolean.TRUE);
        }
        String word = Json.str(m, "message", "").trim().toLowerCase(Locale.US);
        if (word.length() > 32) return;
        for (String w : WORDS) {
            if (w.equals(word)) {
                sink.command(w);
                return;
            }
        }
    }
}
