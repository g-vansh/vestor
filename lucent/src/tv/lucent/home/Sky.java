package tv.lucent.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aircraft over Cambridge, as on Vestor's wall: live ADS-B positions from adsb.lol (no key) and
 * routes from hexdb.io, cached because most callsigns repeat all day. Airlines are named in text
 * from the callsign's ICAO prefix; no airline logos are drawn.
 */
final class Sky {
    private Sky() {}

    static final String POINT = "https://api.adsb.lol/v2/point/%.4f/%.4f/%d";
    static final String ROUTE = "https://hexdb.io/api/v1/route/icao/";
    static final String AIRPORT = "https://hexdb.io/api/v1/airport/icao/";

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
            f.miles = (float) (Gbfs.metres(lat, lon, Json.num(a, "lat", lat), Json.num(a, "lon", lon)) / 1609.344);
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

    /** hexdb airport to {IATA, short place name}: "Logan International Airport" reads "Logan". */
    static String[] airport(String json, String icao) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        String iata = Text.clean(Json.str(m, "iata", ""));
        if (!iata.matches("[A-Z0-9]{3}")) iata = "";
        String name = Text.clean(Json.str(m, "airport", ""));
        for (String suffix : new String[] {" International Airport", " Intercontinental Airport", " Regional Airport",
                " Municipal Airport", " International", " Airport", " Intl"}) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        name = name.trim();
        if (name.isEmpty()) name = Text.clean(Json.str(m, "region_name", ""));
        return new String[] {iata.isEmpty() ? icao : iata, name};
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
