package tv.lucent.home;

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
 * Settings, kept in private preferences. A plain {@code lucent.conf} pushed into the app's media
 * folder (adb can write there on Fire OS 8) sets the weather place and, if wanted, the bike dock:
 *
 * <pre>
 * city=Cambridge
 * lat=42.3736
 * lon=-71.1097
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

    private static final String CITY = "conf_city", LAT = "conf_lat", LON = "conf_lon";
    private static final String CITY_RULE = "[\\p{L}\\p{N} ,.'-]{1,40}", STATION_RULE = "[A-Za-z0-9_-]{1,64}";

    private final SharedPreferences p;
    private final File media;
    String city = "Cambridge";
    double lat = 42.3736, lon = -71.1097;

    /** The media dir is deprecated on Android 11 but it is the one folder adb can still write. */
    @SuppressWarnings("deprecation")
    Prefs(Context c) {
        p = c.getSharedPreferences("lucent", Context.MODE_PRIVATE);
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

    long findForMs() {
        try {
            return Math.max(5, Long.parseLong(get(FIND_FOR, "30"))) * 60000L;
        } catch (NumberFormatException e) {
            return 30 * 60000L;
        }
    }

    /**
     * Takes in lucent.conf if one was pushed; called on every return home. True when something
     * changed, so the caller can fetch the weather for the new place.
     */
    boolean readConf() {
        if (media == null) return false;
        File f = new File(media, "lucent.conf");
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
