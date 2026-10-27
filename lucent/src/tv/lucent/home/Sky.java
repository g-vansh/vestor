package tv.lucent.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aircraft over Cambridge, as on Vestor's wall: live ADS-B positions and the flight's route.
 *
 * Positions come from the first of three community ADS-B services that answers: adsb.fi (one
 * request a second allowed, so it leads), then adsb.lol (60 a minute but only 500 a day, so it is
 * the fallback), then airplanes.live (which now asks projects to register, and is tried only in
 * case it lets Lucent in). All three speak the same readsb JSON. {@link Pace} keeps each inside
 * its limits and skips one that is failing, so a dead service costs nothing.
 *
 * Routes come from adsbdb (fresh, and it names both airports in one answer), then hexdb.io. Free
 * route databases are keyed by flight number and can be years stale, so a low flight near Boston
 * whose route does not touch Logan is corrected the way Vestor does it: climbing means it left
 * Logan, descending means it is landing there, level means the route is not shown at all.
 * Airlines are named in text from the callsign's ICAO prefix. No airline logos are drawn.
 */
final class Sky {
    private Sky() {}

    /** Position services in order of preference, each with lat, lon and a radius in nautical miles. */
    static final String[] POINTS = {
        "https://opendata.adsb.fi/api/v3/lat/%.4f/lon/%.4f/dist/%d",
        "https://api.adsb.lol/v2/point/%.4f/%.4f/%d",
        "https://api.airplanes.live/v2/point/%.4f/%.4f/%d",
    };
    static final String[] POINT_NAMES = {"adsb.fi", "adsb.lol", "airplanes.live"};
    /** Kept for the first build's fixtures and tests. */
    static final String POINT = POINTS[1];
    static final String ROUTE = "https://hexdb.io/api/v1/route/icao/";
    static final String AIRPORT = "https://hexdb.io/api/v1/airport/icao/";
    static final String ADSBDB = "https://api.adsbdb.com/v0/callsign/";
    static final String HOME_ICAO = "KBOS", HOME_IATA = "BOS";

    /** Aircraft near (lat, lon): airborne ones nearest first, then any on the ground. */
    static Model.Sky aircraft(String json, double lat, double lon) {
        Model.Sky sky = new Model.Sky();
        Map<String, Object> root = Json.obj(Json.parse(json));
        List<Object> ac = Json.arr(root, "ac");
        if (ac == null) ac = Json.arr(root, "aircraft");
        if (ac == null) return sky;
        List<Model.Flight> all = new ArrayList<>();
        for (Object o : ac) {
            Map<String, Object> a = Json.obj(o);
            if (a == null || !Json.has(a, "lat") || !Json.has(a, "lon")) continue;
            Model.Flight f = new Model.Flight();
            f.hex = Text.clean(Json.str(a, "hex", ""));
            f.callsign = Text.clean(Json.str(a, "flight", ""));
            f.reg = Text.clean(Json.str(a, "r", ""));
            f.type = Text.clean(Json.str(a, "t", ""));
            Object alt = a.get("alt_baro");
            f.ground = "ground".equals(alt);
            f.altFt = alt instanceof Double ? (int) Math.round((Double) alt) : Json.integer(a, "alt_geom", 0);
            f.knots = Json.integer(a, "gs", 0);
            f.fpm = Json.has(a, "baro_rate") ? Json.integer(a, "baro_rate", 0) : Json.integer(a, "geom_rate", 0);
            f.track = (float) Json.num(a, "track", Json.num(a, "true_heading", Json.num(a, "mag_heading", 0)));
            f.lat = Json.num(a, "lat", lat);
            f.lon = Json.num(a, "lon", lon);
            // How old the position was when the service answered, so it can be carried forward.
            f.seen = (float) Math.max(0, Math.min(60, Json.num(a, "seen_pos", Json.num(a, "seen", 0))));
            f.miles = (float) (Gbfs.metres(lat, lon, f.lat, f.lon) / 1609.344);
            f.airline = airline(f.callsign);
            all.add(f);
        }
        Collections.sort(all, new Comparator<Model.Flight>() {
            @Override
            public int compare(Model.Flight a, Model.Flight b) {
                if (a.ground != b.ground) return a.ground ? 1 : -1;
                boolean ac = !a.callsign.isEmpty(), bc = !b.callsign.isEmpty();
                if (ac != bc) return ac ? -1 : 1;
                return Float.compare(a.miles, b.miles);
            }
        });
        sky.inRange = all.size();
        for (Model.Flight f : all) {
            if (f.ground) continue;
            sky.flights.add(f);
            if (sky.flights.size() >= 6) break;
        }
        sky.ok = true;
        return sky;
    }

    /** hexdb route: {"route":"KBOS-KSFO"} to origin and destination ICAO codes, or null. */
    static String[] route(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        String r = Text.clean(Json.str(m, "route", ""));
        int dash = r.indexOf('-');
        if (dash <= 0 || dash >= r.length() - 1) return null;
        String to = r.substring(dash + 1);
        int more = to.indexOf('-');
        if (more > 0) to = to.substring(more + 1);
        String from = r.substring(0, dash).trim();
        to = to.trim();
        // Airport codes go into the next request's URL, so only real ICAO codes pass.
        if (!safeCode(from) || !safeCode(to)) return null;
        return new String[] {from, to};
    }

    /**
     * adsbdb's answer for a callsign: {from IATA, from city, to IATA, to city, from ICAO, to ICAO},
     * or null when it does not know the flight. Codes that are not plain letters and digits are
     * dropped, and place names are cleaned like any outside text.
     */
    static String[] adsbdb(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        Map<String, Object> r = Json.obj(Json.obj(root, "response"), "flightroute");
        if (r == null) return null;
        Map<String, Object> o = Json.obj(r, "origin"), d = Json.obj(r, "destination");
        if (o == null || d == null) return null;
        String fi = code(Json.str(o, "iata_code", ""), 3), ti = code(Json.str(d, "iata_code", ""), 3);
        String fc = code(Json.str(o, "icao_code", ""), 4), tc = code(Json.str(d, "icao_code", ""), 4);
        if (fi.isEmpty() && fc.isEmpty() || ti.isEmpty() && tc.isEmpty()) return null;
        return new String[] {fi.isEmpty() ? fc : fi, place(o), ti.isEmpty() ? tc : ti, place(d), fc, tc};
    }

    private static String code(String s, int len) {
        String c = Text.clean(s).toUpperCase(Locale.US);
        return c.length() == len && c.matches("[A-Z0-9]+") ? c : "";
    }

    /** "Boston" from the municipality, else the airport's name shortened as {@link #airport} does. */
    private static String place(Map<String, Object> a) {
        String m = Text.clean(Json.str(a, "municipality", ""));
        if (!m.isEmpty() && m.length() <= 40) return m;
        return shortAirport(Text.clean(Json.str(a, "name", "")));
    }

    /**
     * Whether a looked-up route can be believed for a flight seen near Boston. Low traffic here is
     * almost always arriving at or leaving Logan, so a low flight whose route skips Logan has a
     * stale route: it is replaced by what its climb or descent says ({@code null} when level).
     * Cruising overflights keep their route.
     */
    static String[] plausible(String[] route, Model.Flight f) {
        if (route == null || route.length < 4) return route;
        boolean home = HOME_IATA.equals(route[0]) || HOME_IATA.equals(route[2]) || HOME_ICAO.equals(route[0])
                || HOME_ICAO.equals(route[2]) || route.length > 5 && (HOME_ICAO.equals(route[4]) || HOME_ICAO.equals(route[5]));
        if (home || f.altFt >= 12000 || f.miles > 20) return route;
        if (f.fpm > 250) return new String[] {HOME_IATA, "Boston", "", ""};
        if (f.fpm < -250) return new String[] {"", "", HOME_IATA, "Boston"};
        return null;
    }

    /** hexdb airport to {IATA, short place name}: "Logan International Airport" reads "Logan". */
    static String[] airport(String json, String icao) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        String iata = Text.clean(Json.str(m, "iata", ""));
        if (!iata.matches("[A-Z0-9]{3}")) iata = "";
        String name = shortAirport(Text.clean(Json.str(m, "airport", "")));
        if (name.isEmpty()) name = Text.clean(Json.str(m, "region_name", ""));
        return new String[] {iata.isEmpty() ? icao : iata, name};
    }

    private static String shortAirport(String name) {
        for (String suffix : new String[] {" International Airport", " Intercontinental Airport", " Regional Airport",
                " Municipal Airport", " International", " Airport", " Intl"}) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return name.trim();
    }

    private static final Map<String, String> AIRLINES = new HashMap<>();

    static {
        String[] pairs = {
            "AAL", "American", "ACA", "Air Canada", "AFR", "Air France", "AIC", "Air India", "AMX", "Aeroméxico",
            "ASA", "Alaska", "AAY", "Allegiant", "AVA", "Avianca", "BAW", "British Airways", "CMP", "Copa",
            "CPZ", "Delta Connection", "DAL", "Delta", "DLH", "Lufthansa", "EDV", "Delta Connection", "EIN", "Aer Lingus",
            "ELY", "El Al", "ENY", "American Eagle", "ETD", "Etihad", "EJA", "NetJets", "FDX", "FedEx",
            "FFT", "Frontier", "FPY", "PLAY", "GJS", "United Express", "GTI", "Atlas Air", "HAL", "Hawaiian",
            "IBE", "Iberia", "ICE", "Icelandair", "ITY", "ITA Airways", "JBU", "JetBlue", "JIA", "American Eagle",
            "JZA", "Air Canada Express", "KAL", "Korean Air", "KAP", "Cape Air", "KLM", "KLM", "LXJ", "Flexjet",
            "MXY", "Breeze", "NBT", "Norse", "NKS", "Spirit", "PDT", "American Eagle", "POE", "Porter",
            "QTR", "Qatar Airways", "QXE", "Horizon", "RPA", "Republic", "SAS", "SAS", "SCX", "Sun Country",
            "SKW", "SkyWest", "SWA", "Southwest", "SWR", "Swiss", "TAP", "TAP Air Portugal", "THY", "Turkish Airlines",
            "UAE", "Emirates", "UAL", "United", "UPS", "UPS", "VIR", "Virgin Atlantic", "VXP", "Avelo",
            "WJA", "WestJet", "CFG", "Condor", "AUA", "Austrian", "BEL", "Brussels Airlines", "CPA", "Cathay Pacific",
            "JAL", "Japan Airlines", "ANA", "ANA", "SVA", "Saudia", "RAM", "Royal Air Maroc", "ASH", "United Express",
            "CJT", "Cargojet", "ABX", "ABX Air", "CKS", "Kalitta", "MXA", "Mexicana", "VOI", "Volaris", "LAN", "LATAM",
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) AIRLINES.put(pairs[i], pairs[i + 1]);
    }

    /** "JBU1387" is JetBlue. A registration-style callsign such as "N935JB" has no airline. */
    static String airline(String callsign) {
        if (callsign == null || callsign.length() < 4) return "";
        String p = callsign.substring(0, 3).toUpperCase(Locale.US);
        if (!Character.isLetter(p.charAt(0)) || !Character.isLetter(p.charAt(1)) || !Character.isLetter(p.charAt(2))) return "";
        if (!Character.isDigit(callsign.charAt(3))) return "";
        String name = AIRLINES.get(p);
        return name == null ? "" : name;
    }

    /**
     * A callsign as it may go into a URL path: letters and digits only, at most 8 (ICAO allows 7).
     * Anything else from the feed is never sent on.
     */
    static boolean safeCallsign(String s) {
        return s != null && s.matches("[A-Za-z0-9]{2,8}");
    }

    /** An ICAO airport code: four letters or digits. */
    static boolean safeCode(String s) {
        return s != null && s.matches("[A-Za-z0-9]{3,4}");
    }

    /** "JBU1387" to "JetBlue 1387"; anything else is shown as sent. */
    static String flightName(Model.Flight f) {
        if (!f.airline.isEmpty() && f.callsign.length() > 3) return f.airline + " " + f.callsign.substring(3);
        if (!f.callsign.isEmpty()) return f.callsign;
        return f.reg.isEmpty() ? f.hex.toUpperCase(Locale.US) : f.reg;
    }
}
