package tv.vestor.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings, kept in private preferences. A plain {@code vestor.conf} pushed into the app's media
 * folder (adb can write there on Fire OS 8) sets the weather place and, if wanted, the bike dock:
 *
 * <pre>
 * city=Cambridge
 * lat=42.3556
 * lon=-71.1040
 * bike_station=f835141c-0de8-11e7-991c-3863bb43a7d0
 * </pre>
 *
 * Every line is optional. The media folder is shared storage, so the file is read once, checked
 * line by line, copied into the app's private settings and then deleted: nothing about where you
 * live stays where other apps could read it, and a file another app drops there can do no more
 * than a typed setting could.
 */
final class Prefs {
    static final String WALL = "wall", ROTATE = "rotate", MOTION = "motion", CLOCK = "clock", UNITS = "units",
            AMBIENT = "ambient", ORDER = "order", HIDDEN = "hidden", SHOWN = "shown", WALL_AT = "wall_at",
            WEATHER_JSON = "weather_json", WEATHER_AT = "weather_at", STATION = "bike_station",
            FIND_FOR = "find_for", SKY = "sky";
    /** Added in 2.0. Values and defaults are listed in {@link #DEFAULTS}. */
    static final String ALBUM = "album", DIM = "dim", AWAKE_FROM = "awake_from", AWAKE_TO = "awake_to", BEDTIME = "bedtime",
            ALARM = "alarm", ALARM_FOR = "alarm_for", WAKE_NOTES = "wake_notes", MODE = "mode", MODE_UNTIL = "mode_until",
            MBTA_KEY = "mbta_key", CALENDARS = "calendars", WIFI_SSID = "wifi_ssid", WIFI_PASS = "wifi_pass",
            ALEXA = "alexa", ALEXA_TOPIC = "alexa_topic", ACCESS = "access", TAKEOVER = "takeover", WALLPAPER = "wallpaper",
            QUALITY = "wall_quality", SEEN_DAY = "seen_day", NOTE_SEEN = "note_seen";
    /** Phone alerts through ntfy, and the Samsung's picture modes (see {@link Samsung}). */
    static final String PUSH = "push", PUSH_TOPIC = "push_topic", PICTURE_FOLLOW = "picture_follow", DAY_MODE = "day_mode",
            NIGHT_MODE = "night_mode", GLARE_MODE = "glare_mode", GLARE = "glare", GLARE_BACK = "glare_back";
    /** The second dock bike finder watches: "auto" for Erie St (outside Flour), "none", or a station id. */
    static final String STATION2 = "bike_station2";
    /** Video Calls: your voice through the headphones' microphone when Fire OS offers it, or the webcam's. */
    static final String CALL_VOICE = "call_voice";
    /** When this TV last handed an update to Fire OS's installer, so the new version comes back to the front. */
    static final String UPDATE_AT = "update_at";
    /** The Bluetooth beacon that shows the Samsung's AirPlay to Apple devices near the TV (see {@link AirPlay}). */
    static final String AIRPLAY = "airplay";

    /** Every setting the control center may change, with its allowed values (null: free text checked elsewhere). */
    static final String[][] CHOICES = {
        {UNITS, "f", "c", "off"},
        {CLOCK, "auto", "12", "24"},
        {ROTATE, "10m", "1m", "5m", "hour", "day", "off"},
        {ALBUM, "both", "nature", "ours"},
        {MOTION, "full", "reduced"},
        {AMBIENT, "2", "5", "15", "off"},
        {DIM, "10", "5", "30", "off"},
        {FIND_FOR, "30", "15", "60", "120"},
        {SKY, "on", "off"},
        {ALARM, "ring", "chime"},
        {ALARM_FOR, "10", "2", "5", "30"},
        {WAKE_NOTES, "never", "day", "always"},
        {QUALITY, "motion", "still"},
        {PICTURE_FOLLOW, "off", "on"},
        {CALL_VOICE, CallAudio.HEADSET, CallAudio.WEBCAM},
        {AIRPLAY, "on", "off"},
    };

    /** Names the control center uses where they differ from the stored key. */
    private static final String[][] API = {
        {"findFor", FIND_FOR}, {"wakeForNotes", WAKE_NOTES}, {"alarmFor", ALARM_FOR}, {"quality", QUALITY},
        {"pictureFollow", PICTURE_FOLLOW}, {"dayMode", DAY_MODE}, {"nightMode", NIGHT_MODE}, {"glareMode", GLARE_MODE},
        {"callVoice", CALL_VOICE},
    };

    static String api(String key) {
        for (String[] a : API) if (a[1].equals(key)) return a[0];
        return key;
    }

    static String key(String api) {
        for (String[] a : API) if (a[0].equals(api)) return a[1];
        return api;
    }

    /** The default of a choice setting: its first allowed value. */
    static String fallback(String key) {
        for (String[] c : CHOICES) if (c[0].equals(key)) return c[1];
        return "";
    }

    /** Whether {@code value} is allowed for {@code key}. */
    static boolean allowed(String key, String value) {
        for (String[] c : CHOICES) {
            if (!c[0].equals(key)) continue;
            for (int i = 1; i < c.length; i++) if (c[i].equals(value)) return true;
            return false;
        }
        return false;
    }

    /** "HH:MM" from 00:00 to 23:59, or one of the words given. */
    static boolean clockOr(String v, String... words) {
        for (String w : words) if (w.equals(v)) return true;
        return v != null && v.matches("([01][0-9]|2[0-3]):[0-5][0-9]");
    }

    /** Minutes after midnight of "HH:MM", or -1. */
    static int minutes(String v) {
        if (v == null || !v.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) return -1;
        return Integer.parseInt(v.substring(0, 2)) * 60 + Integer.parseInt(v.substring(3));
    }

    /** A choice setting's value, never one outside its allowed list. */
    String choice(String key) {
        String v = get(key, fallback(key));
        return allowed(key, v) ? v : fallback(key);
    }

    private static final String CITY = "conf_city", LAT = "conf_lat", LON = "conf_lon";
    private static final String CITY_RULE = "[\\p{L}\\p{N} ,.'-]{1,40}", STATION_RULE = "[A-Za-z0-9_-]{1,64}";

    private final SharedPreferences p;
    private final File media;
    String city = "Cambridge";
    /** Westgate, for the weather, sunrise and sunset (sent rounded to about 2 km, see {@link Weather#url}). */
    double lat = Gbfs.WESTGATE_LAT, lon = Gbfs.WESTGATE_LON;

    /** The media dir is deprecated on Android 11 but it is the one folder adb can still write. */
    @SuppressWarnings("deprecation")
    Prefs(Context c) {
        p = c.getSharedPreferences("vestor", Context.MODE_PRIVATE);
        File[] dirs = c.getExternalMediaDirs();
        media = dirs != null && dirs.length > 0 ? dirs[0] : null;
        if (media != null) {
            if (!media.exists()) media.mkdirs();
            // Keeps your wallpapers out of photo apps' galleries.
            try {
                new File(media, ".nomedia").createNewFile();
            } catch (IOException | RuntimeException ignored) {
                // only a nicety
            }
        }
        String c0 = p.getString(CITY, null);
        if (c0 != null && c0.matches(CITY_RULE)) city = c0;
        lat = coordinate(p.getString(LAT, null), 90, lat);
        lon = coordinate(p.getString(LON, null), 180, lon);
        readConf();
    }

    private static double coordinate(String v, double limit, double fallback) {
        if (v == null) return fallback;
        try {
            double d = Double.parseDouble(v);
            return d >= -limit && d <= limit ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    File media() {
        return media;
    }

    String get(String key, String fallback) {
        return p.getString(key, fallback);
    }

    void put(String key, String value) {
        p.edit().putString(key, value).apply();
    }

    /** Written to disk before returning, for values that cannot be lost to a crash (a refresh token works once). */
    void putNow(String key, String value) {
        p.edit().putString(key, value).commit();
    }

    void remove(String key) {
        p.edit().remove(key).apply();
    }

    long getLong(String key) {
        return p.getLong(key, 0);
    }

    void putLong(String key, long v) {
        p.edit().putLong(key, v).apply();
    }

    List<String> list(String key) {
        String v = p.getString(key, "");
        if (TextUtils.isEmpty(v)) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(v.split(",")));
    }

    Set<String> set(String key) {
        return new HashSet<>(list(key));
    }

    void putList(String key, List<String> items) {
        p.edit().putString(key, TextUtils.join(",", items)).apply();
    }

    void hide(String pkg) {
        Set<String> s = set(HIDDEN);
        s.add(pkg);
        putList(HIDDEN, new ArrayList<>(s));
    }

    void unhideAll() {
        p.edit().remove(HIDDEN).apply();
    }

    boolean reduced() {
        return "reduced".equals(get(MOTION, "full"));
    }

    /** Which unit leads; both are always shown. Fahrenheit leads unless Celsius is chosen. */
    boolean celsiusFirst() {
        return "c".equals(get(UNITS, "f"));
    }

    boolean weatherOn() {
        return !"off".equals(get(UNITS, "f"));
    }

    String station() {
        String v = get(STATION, "auto");
        return v.matches(STATION_RULE) ? v : "auto";
    }

    String station2() {
        String v = get(STATION2, "auto");
        return v.matches(STATION_RULE) ? v : "auto";
    }

    long findForMs() {
        try {
            return Math.max(5, Long.parseLong(get(FIND_FOR, "30"))) * 60000L;
        } catch (NumberFormatException e) {
            return 30 * 60000L;
        }
    }

    /**
     * Takes in vestor.conf if one was pushed; called on every return home. True when something
     * changed, so the caller can fetch the weather for the new place.
     */
    boolean readConf() {
        if (media == null) return false;
        File f = new File(media, "vestor.conf");
        // The file is small and plain. Anything odd in it is ignored rather than trusted.
        if (!f.isFile() || f.length() > 4096) return false;
        String newCity = null, newStation = null;
        double newLat = Double.NaN, newLon = Double.NaN;
        // At most 4 KB is read, however the file changes while it is read.
        byte[] buf = new byte[4097];
        int n = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        if (n > 4096) return false;
        String[] all = new String(buf, 0, n, StandardCharsets.UTF_8).split("\r?\n|\r", 51);
        for (int i = 0; i < all.length && i < 50; i++) {
            String line = all[i].trim();
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq < 1) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            if ("city".equals(k) && v.matches(CITY_RULE)) newCity = v;
            else if ("lat".equals(k)) newLat = coordinate(v, 90, Double.NaN);
            else if ("lon".equals(k)) newLon = coordinate(v, 180, Double.NaN);
            else if ("bike_station".equals(k) && v.matches(STATION_RULE)) newStation = v;
        }
        // A place needs both halves: half a position is no position.
        boolean place = !Double.isNaN(newLat) && !Double.isNaN(newLon);
        if (newCity == null && !place && newStation == null) return false;
        SharedPreferences.Editor e = p.edit();
        if (newCity != null) {
            city = newCity;
            e.putString(CITY, newCity);
        }
        if (place) {
            lat = newLat;
            lon = newLon;
            e.putString(LAT, Double.toString(newLat)).putString(LON, Double.toString(newLon));
            e.remove(WEATHER_JSON).remove(WEATHER_AT);
        }
        if (newStation != null) e.putString(STATION, newStation);
        e.commit();
        if (!f.delete()) f.deleteOnExit();
        return true;
    }
}
