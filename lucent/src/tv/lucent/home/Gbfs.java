package tv.lucent.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bluebikes through its public GBFS 2.3 feed (Lyft, no key). Station facts come from
 * station_information once a day; live counts from station_status, whose time to live is 60 s.
 *
 * Classic and e-bikes are counted the way Vestor counts them: num_ebikes_available when the feed
 * has it, otherwise every vehicle type other than "1" (human-powered) in vehicle_types_available.
 */
final class Gbfs {
    private Gbfs() {}

    static final String BASE = "https://gbfs.lyft.com/gbfs/2.3/bos/en/";
    static final String INFO = BASE + "station_information.json";
    static final String STATUS = BASE + "station_status.json";

    /**
     * The Westgate dock: 25 docks on Vassar Street in front of Westgate's low-rise buildings, near
     * Audrey Street (MIT announced it in January 2016 as "Vassar Street West"). It is found in the
     * live feed by name or, failing that, as the dock nearest this point, so a rename by Bluebikes
     * does not break it.
     */
    static final double WESTGATE_LAT = 42.35560, WESTGATE_LON = -71.10395;
    /**
     * The second dock bike finder watches: "Sidney Research Campus/Erie Street at Waverly", outside
     * Flour's Cambridgeport bakery, 240 m from Westgate. Found by name, or as the dock nearest this
     * point within 150 m.
     */
    static final double ERIE_LAT = 42.35775, ERIE_LON = -71.10393;
    /** MIT Pacific St at Purrington St, the station Vestor's wall shows. */
    static final String PACIFIC = "f835141c-0de8-11e7-991c-3863bb43a7d0";

    /** Every station, nearest to (lat, lon) first, with live fields left empty. */
    static List<Model.Station> stations(String infoJson, double lat, double lon) {
        List<Model.Station> out = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(infoJson));
        List<Object> list = Json.arr(Json.obj(root, "data"), "stations");
        if (list == null) return out;
        for (Object o : list) {
            Map<String, Object> m = Json.obj(o);
            String id = Json.str(m, "station_id", null);
            if (id == null || !Json.has(m, "lat") || !Json.has(m, "lon")) continue;
            Model.Station s = new Model.Station();
            s.id = id;
            s.name = Text.clean(Json.str(m, "name", id));
            s.shortName = Text.clean(Json.str(m, "short_name", ""));
            s.lat = Json.num(m, "lat", 0);
            s.lon = Json.num(m, "lon", 0);
            s.capacity = Json.integer(m, "capacity", 0);
            Map<String, Object> uris = Json.obj(m, "rental_uris");
            if (uris != null) s.rent = Json.str(uris, "ios", Json.str(uris, "android", ""));
            s.metres = (float) metres(lat, lon, s.lat, s.lon);
            out.add(s);
        }
        Collections.sort(out, new Comparator<Model.Station>() {
            @Override
            public int compare(Model.Station a, Model.Station b) {
                return Float.compare(a.metres, b.metres);
            }
        });
        return out;
    }

    /**
     * The Westgate station's id: one whose name says Westgate, else the nearest dock to Westgate
     * within 300 m. Null when neither exists.
     */
    static String westgate(List<Model.Station> all) {
        for (Model.Station s : all) {
            if (s.name.toLowerCase(Locale.US).contains("westgate")) return s.id;
        }
        Model.Station best = null;
        double bestM = 300;
        for (Model.Station s : all) {
            double m = metres(WESTGATE_LAT, WESTGATE_LON, s.lat, s.lon);
            if (m < bestM) {
                bestM = m;
                best = s;
            }
        }
        return best == null ? null : best.id;
    }

    /** The Erie Street dock's id (see {@link #ERIE_LAT}), or null. */
    static String erie(List<Model.Station> all) {
        for (Model.Station s : all) {
            String n = s.name.toLowerCase(Locale.US);
            if (n.contains("erie st")) return s.id;
        }
        Model.Station best = null;
        double bestM = 150;
        for (Model.Station s : all) {
            double m = metres(ERIE_LAT, ERIE_LON, s.lat, s.lon);
            if (m < bestM) {
                bestM = m;
                best = s;
            }
        }
        return best == null ? null : best.id;
    }

    /** The station to watch first, then the {@code extra} nearest others (all sorted by distance). */
    static List<Model.Station> follow(List<Model.Station> sorted, String mainId, int extra) {
        return follow(sorted, mainId, null, extra);
    }

    /**
     * The station to watch first, the second watched one (when there is one) next, then the nearest
     * others, {@code extra} after the first in all.
     */
    static List<Model.Station> follow(List<Model.Station> sorted, String mainId, String altId, int extra) {
        List<Model.Station> out = new ArrayList<>();
        Model.Station main = find(sorted, mainId);
        if (main == null && !sorted.isEmpty()) main = sorted.get(0);
        if (main == null) return out;
        out.add(copy(main));
        Model.Station alt = find(sorted, altId);
        if (alt != null && !alt.id.equals(main.id)) out.add(copy(alt));
        for (Model.Station s : sorted) {
            if (out.size() > extra) break;
            if (!s.id.equals(main.id) && (alt == null || !s.id.equals(alt.id))) out.add(copy(s));
        }
        return out;
    }

    /**
     * The feed's own "last_updated" (epoch seconds), or 0. Bluebikes writes the file once a minute
     * and its CDN edges can briefly hand out the previous one, so a reader keeps the newest it has
     * seen and ignores anything older.
     */
    static long lastUpdated(String json) {
        if (json == null) return 0;
        int at = json.indexOf("\"last_updated\"");
        if (at < 0) return 0;
        int i = json.indexOf(':', at);
        if (i < 0 || i - at > 20) return 0;
        i++;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        long v = 0;
        int digits = 0;
        while (i < json.length() && Character.isDigit(json.charAt(i)) && digits < 12) {
            v = v * 10 + (json.charAt(i) - '0');
            i++;
            digits++;
        }
        return digits >= 9 ? v : 0;
    }

    private static long newest;

    /** Whether a station_status answer is at least as new as any seen before. Thread-safe. */
    static synchronized boolean fresh(String json) {
        long t = lastUpdated(json);
        if (t == 0) return true;
        if (t < newest) return false;
        newest = t;
        return true;
    }

    static Model.Station find(List<Model.Station> list, String id) {
        if (id == null) return null;
        for (Model.Station s : list) if (s.id.equals(id)) return s;
        return null;
    }

    /**
     * Fills live counts for {@code stations} from a station_status document. Each station is
     * pulled out on its own, so a feed of hundreds of docks is not turned into hundreds of maps
     * every few seconds. Returns how many stations were found.
     */
    static int status(String statusJson, List<Model.Station> stations) {
        int found = 0;
        for (Model.Station s : stations) {
            Map<String, Object> m = Json.objectWith(statusJson, "station_id", s.id);
            if (m == null) {
                s.live = false;
                continue;
            }
            apply(m, s);
            found++;
        }
        return found;
    }

    static void apply(Map<String, Object> m, Model.Station s) {
        int bikes = Math.max(0, Json.integer(m, "num_bikes_available", 0));
        int ebikes;
        if (Json.has(m, "num_ebikes_available")) {
            ebikes = Json.integer(m, "num_ebikes_available", 0);
        } else {
            ebikes = 0;
            List<Object> types = Json.arr(m, "vehicle_types_available");
            if (types != null) {
                for (Object o : types) {
                    Map<String, Object> t = Json.obj(o);
                    if (!"1".equals(Json.str(t, "vehicle_type_id", "1"))) ebikes += Json.integer(t, "count", 0);
                }
            }
        }
        s.ebikes = Math.max(0, Math.min(bikes, ebikes));
        s.classic = Math.max(0, bikes - s.ebikes);
        s.docks = Math.max(0, Json.integer(m, "num_docks_available", 0));
        s.disabled = Math.max(0, Json.integer(m, "num_bikes_disabled", 0));
        s.installed = Json.bool(m, "is_installed", true);
        s.renting = Json.bool(m, "is_renting", true);
        s.returning = Json.bool(m, "is_returning", true);
        s.reported = (long) Json.num(m, "last_reported", 0) * 1000L;
        if (s.capacity <= 0) s.capacity = bikes + s.docks + s.disabled + Json.integer(m, "num_docks_disabled", 0);
        s.live = true;
    }

    static Model.Station copy(Model.Station a) {
        Model.Station s = new Model.Station();
        s.id = a.id;
        s.name = a.name;
        s.shortName = a.shortName;
        s.rent = a.rent;
        s.lat = a.lat;
        s.lon = a.lon;
        s.capacity = a.capacity;
        s.metres = a.metres;
        s.classic = a.classic;
        s.ebikes = a.ebikes;
        s.docks = a.docks;
        s.disabled = a.disabled;
        s.installed = a.installed;
        s.renting = a.renting;
        s.returning = a.returning;
        s.live = a.live;
        s.reported = a.reported;
        return s;
    }

    /** The Bluebikes app's own link, which every Boston dock lists today. */
    static final String RENT = "https://bos.lft.to/lastmile_qr_scan";

    /**
     * The link a phone alert opens: the dock's rental link when it is an https address on
     * Lyft's or Bluebikes' own hosts, else {@link #RENT}. The feed is public, so nothing else
     * it says is ever put behind a tap.
     */
    static String rentLink(Model.Station s) {
        String u = s == null ? "" : s.rent;
        if (u.length() < 12 || u.length() > 200 || !u.matches("https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~/?&=%-]*)?")) return RENT;
        int end = u.indexOf('/', 8);
        String host = (end < 0 ? u.substring(8) : u.substring(8, end)).toLowerCase(Locale.US);
        boolean ours = host.equals("lft.to") || host.endsWith(".lft.to") || host.equals("bluebikes.com") || host.endsWith(".bluebikes.com")
                || host.equals("lyft.com") || host.endsWith(".lyft.com");
        return ours ? u : RENT;
    }

    /**
     * A short name for a card: "MIT Pacific St at Purrington St" becomes "Pacific St", and the
     * Westgate dock is called Westgate whatever the feed names it.
     */
    static String shortName(Model.Station s, boolean westgate) {
        if (westgate) return "Westgate";
        String n = s.name;
        if (n.startsWith("MIT ")) n = n.substring(4);
        if (n.startsWith("at ")) n = n.substring(3);
        int at = indexOfAny(n, new String[] {" at ", " @ ", " / ", " - "});
        if (at > 3) n = n.substring(0, at);
        return n.trim();
    }

    private static int indexOfAny(String s, String[] parts) {
        int best = -1;
        for (String p : parts) {
            int i = s.indexOf(p);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** Great-circle distance in metres. */
    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
