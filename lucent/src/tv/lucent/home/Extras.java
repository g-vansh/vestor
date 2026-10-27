package tv.lucent.home;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The smaller feeds around the weather: National Weather Service alerts (warnings, watches,
 * advisories for home) and Open-Meteo's air quality and UV. Both are public, keyless, and asked
 * for a position rounded to about 2 km, like the forecast. The NWS asks every client to name
 * itself in its User-Agent, so Lucent does, without anything that identifies the home or its
 * owner (a contact is optional; the name alone is enough).
 */
final class Extras {
    private Extras() {}

    static final String NWS_AGENT = "Lucent/2.0 (home screen for Fire TV)";

    private static double round2(double v) {
        return Math.round(v * 50) / 50.0;
    }

    static String alertsUrl(double lat, double lon) {
        return String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.2f,%.2f", round2(lat), round2(lon));
    }

    static String airUrl(double lat, double lon) {
        return String.format(Locale.US, "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.2f&longitude=%.2f"
                + "&current=us_aqi,pm2_5,uv_index&timezone=auto", round2(lat), round2(lon));
    }

    /** Active, actual (not test) alerts that have not expired, most severe first, at most 6. */
    static List<Model.Alert> alerts(String json, long now) {
        List<Model.Alert> out = new ArrayList<>();
        List<Object> features = Json.arr(Json.obj(Json.parse(json)), "features");
        if (features == null) return out;
        for (Object f : features) {
            Map<String, Object> p = Json.obj(Json.obj(f), "properties");
            if (p == null || !"Actual".equals(Json.str(p, "status", "Actual"))) continue;
            if ("Cancel".equals(Json.str(p, "messageType", ""))) continue;
            Model.Alert a = new Model.Alert();
            a.event = Text.clean(Json.str(p, "event", ""));
            a.headline = Text.clean(Json.str(p, "headline", ""));
            a.severity = Text.clean(Json.str(p, "severity", ""));
            a.area = Text.clean(Json.str(p, "areaDesc", ""));
            a.onset = Transit.iso(Json.str(p, "onset", Json.str(p, "effective", null)));
            long ends = Transit.iso(Json.str(p, "ends", null));
            a.expires = ends > 0 ? ends : Transit.iso(Json.str(p, "expires", null));
            if (a.event.isEmpty() || a.expires > 0 && a.expires < now) continue;
            out.add(a);
            if (out.size() >= 12) break;
        }
        java.util.Collections.sort(out, new java.util.Comparator<Model.Alert>() {
            @Override
            public int compare(Model.Alert x, Model.Alert y) {
                return Integer.compare(rank(x.severity), rank(y.severity));
            }
        });
        return out.size() > 6 ? new ArrayList<>(out.subList(0, 6)) : out;
    }

    private static int rank(String severity) {
        switch (severity) {
            case "Extreme": return 0;
            case "Severe": return 1;
            case "Moderate": return 2;
            case "Minor": return 3;
            default: return 4;
        }
    }

    static Model.Air air(String json, long now) {
        Model.Air a = new Model.Air();
        Map<String, Object> cur = Json.obj(Json.obj(Json.parse(json)), "current");
        if (cur == null) return a;
        double aqi = Json.num(cur, "us_aqi", -1), pm = Json.num(cur, "pm2_5", -1), uv = Json.num(cur, "uv_index", -1);
        a.aqi = aqi >= 0 && aqi <= 999 ? (int) Math.round(aqi) : -1;
        a.pm25 = pm >= 0 && pm <= 2000 ? pm : -1;
        a.uv = uv >= 0 && uv <= 25 ? uv : -1;
        a.fetched = now;
        a.ok = a.aqi >= 0 || a.uv >= 0;
        return a;
    }

    /** The EPA's words for a US AQI value. */
    static String aqiWord(int aqi) {
        if (aqi < 0) return "";
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for some";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very unhealthy";
        return "Hazardous";
    }

    /** The WHO's words for a UV index. */
    static String uvWord(double uv) {
        if (uv < 0) return "";
        if (uv < 3) return "Low";
        if (uv < 6) return "Moderate";
        if (uv < 8) return "High";
        if (uv < 11) return "Very high";
        return "Extreme";
    }
}
