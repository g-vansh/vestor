package tv.lucent.home;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Current conditions and the next hours from Open-Meteo (no account, no key; data CC BY 4.0,
 * credited in the weather card). Only a neighbourhood-level coordinate is sent. Fetched in
 * Celsius and shown in both units.
 *
 * Open-Meteo's default ("best match") is the right one for Westgate: in the United States it
 * takes NOAA's HRRR (3 km, a new run every hour) for the next day and a half, NOAA's National
 * Blend of Models for the chance of rain, and GFS beyond. The same request asks for HRRR's
 * 15-minute precipitation over the next two hours, which becomes "Rain from 3:45 PM" or
 * "Dry for the next 2 hours".
 */
final class Weather {
    private Weather() {}

    /** Two hours of 15-minute steps. */
    static final int NOWCAST_SLOTS = 8;
    /** Rain worth a word: 0.1 mm in 15 minutes, a light rain of about 0.4 mm an hour. */
    private static final double WET_MM = 0.1;
    static final String SOURCE = "NOAA HRRR and NBM";

    /**
     * The forecast for a point snapped to a 0.02 degree grid (about 2 km), finer than the
     * forecast itself, so even a position typed to the doorstep leaves the TV as a neighbourhood.
     */
    static String url(double lat, double lon) {
        return String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,apparent_temperature,is_day,weather_code,relative_humidity_2m,"
                        + "wind_speed_10m,wind_direction_10m"
                        + "&hourly=temperature_2m,weather_code,is_day,precipitation_probability"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&minutely_15=precipitation,weather_code&forecast_minutely_15=" + NOWCAST_SLOTS
                        + "&timezone=auto&forecast_days=2&forecast_hours=7",
                Math.round(lat * 50) / 50.0, Math.round(lon * 50) / 50.0);
    }

    /** Parses a saved or fresh response into the card's model. */
    static Model.Weather parse(String json, String city, boolean h24, long fetchedAt, boolean celsiusFirst) {
        Model.Weather w = new Model.Weather();
        w.celsiusFirst = celsiusFirst;
        if (json == null) return w;
        try {
            Map<String, Object> root = Json.obj(Json.parse(json));
            Map<String, Object> cur = Json.obj(root, "current");
            if (cur == null || !Json.has(cur, "temperature_2m")) return w;
            // Everything is requested in Celsius; a forecast saved by an older build may not be.
            boolean f = Json.str(Json.obj(root, "current_units"), "temperature_2m", "").contains("F");
            if (f) toCelsius(root);
            w.tempC = Json.num(cur, "temperature_2m", 0);
            // Earth's records are about -89 and 57 °C. Anything outside is a broken answer.
            if (!(Math.abs(w.tempC) < 100)) return w;
            w.feelsC = Json.num(cur, "apparent_temperature", w.tempC);
            w.code = Json.integer(cur, "weather_code", 3);
            w.day = Json.integer(cur, "is_day", 1) == 1;
            w.humidity = Json.integer(cur, "relative_humidity_2m", -1);
            w.windKph = Json.num(cur, "wind_speed_10m", -1);
            w.windDir = Json.integer(cur, "wind_direction_10m", 0);
            Map<String, Object> daily = Json.obj(root, "daily");
            List<Object> max = Json.arr(daily, "temperature_2m_max"), min = Json.arr(daily, "temperature_2m_min");
            w.hiC = max != null && !max.isEmpty() && max.get(0) instanceof Double ? (Double) max.get(0) : w.tempC;
            w.loC = min != null && !min.isEmpty() && min.get(0) instanceof Double ? (Double) min.get(0) : w.tempC;
            Map<String, Object> hourly = Json.obj(root, "hourly");
            List<Object> times = Json.arr(hourly, "time"), temps = Json.arr(hourly, "temperature_2m");
            List<Object> codes = Json.arr(hourly, "weather_code"), days = Json.arr(hourly, "is_day");
            List<Object> rain = Json.arr(hourly, "precipitation_probability");
            if (times != null && temps != null && codes != null) {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                SimpleDateFormat out = new SimpleDateFormat(h24 ? "HH:mm" : "h a", Locale.US);
                int n = Math.min(7, Math.min(times.size(), Math.min(temps.size(), codes.size())));
                for (int i = 0; i < n; i++) {
                    if (!(temps.get(i) instanceof Double) || !(codes.get(i) instanceof Double)) continue;
                    String label = "";
                    if (i == 0) {
                        label = "Now";
                    } else if (times.get(i) instanceof String) {
                        try {
                            Date d = in.parse((String) times.get(i));
                            label = d == null ? "" : out.format(d);
                        } catch (ParseException e) {
                            label = "";
                        }
                    }
                    w.hourLabel.add(label);
                    w.hourC.add((Double) temps.get(i));
                    w.hourCode.add((int) Math.round((Double) codes.get(i)));
                    w.hourDay.add(days == null || i >= days.size() || !(days.get(i) instanceof Double)
                            || (Double) days.get(i) != 0);
                    w.hourRain.add(rain != null && i < rain.size() && rain.get(i) instanceof Double
                            ? (int) Math.round((Double) rain.get(i)) : -1);
                }
            }
            nowcast(root, w, h24);
            w.condition = Icons.describe(w.code);
            w.city = city;
            w.updated = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(new Date(fetchedAt));
            w.ok = true;
        } catch (RuntimeException | StackOverflowError e) {
            // Parsed on the main thread, from the network or from the copy saved last time:
            // nothing in a forecast may take the home screen down.
            w.ok = false;
        }
        return w;
    }

    /**
     * The next two hours of rain, from HRRR's 15-minute steps: "Rain until 4:15 PM" while it
     * rains, "Rain from 3:45 PM" when it is coming, "Dry for the next 2 hours" when it is not.
     * Snow says snow. Left empty when the forecast has no 15-minute steps.
     */
    static void nowcast(Map<String, Object> root, Model.Weather w, boolean h24) {
        Map<String, Object> m = Json.obj(root, "minutely_15");
        List<Object> times = Json.arr(m, "time"), mm = Json.arr(m, "precipitation"), codes = Json.arr(m, "weather_code");
        if (times == null || mm == null || times.isEmpty()) return;
        long offset = (long) Json.num(root, "utc_offset_seconds", 0) * 1000L;
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        int n = Math.min(NOWCAST_SLOTS, Math.min(times.size(), mm.size()));
        long[] at = new long[n];
        boolean[] wet = new boolean[n];
        boolean snow = false;
        for (int i = 0; i < n; i++) {
            if (!(times.get(i) instanceof String) || !(mm.get(i) instanceof Double)) return;
            try {
                Date d = in.parse((String) times.get(i));
                if (d == null) return;
                at[i] = d.getTime() - offset;
            } catch (ParseException e) {
                return;
            }
            wet[i] = (Double) mm.get(i) >= WET_MM;
            if (wet[i] && codes != null && i < codes.size() && codes.get(i) instanceof Double) {
                int c = (int) Math.round((Double) codes.get(i));
                if (c >= 71 && c <= 77 || c == 85 || c == 86) snow = true;
            }
        }
        if (n == 0) return;
        SimpleDateFormat out = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US);
        String what = snow ? "Snow" : "Rain";
        int first = -1, dry = -1;
        for (int i = 0; i < n; i++) {
            if (wet[i] && first < 0) first = i;
            if (!wet[i] && first >= 0 && dry < 0) dry = i;
        }
        String hours = n >= 8 ? "2 hours" : n * 15 + " minutes";
        if (first < 0) {
            w.rain = "Dry for the next " + hours;
        } else if (first == 0) {
            w.rain = dry < 0 ? what + " for the next " + hours : what + " until " + out.format(new Date(at[dry]));
            w.rainAt = at[0];
            w.rainUntil = dry < 0 ? 0 : at[dry];
        } else {
            w.rain = what + " from " + out.format(new Date(at[first]));
            w.rainAt = at[first];
            w.rainUntil = dry < 0 ? 0 : at[dry];
        }
        w.wetSoon = first >= 0;
    }

    private static void toCelsius(Map<String, Object> root) {
        Map<String, Object> cur = Json.obj(root, "current");
        for (String k : new String[] {"temperature_2m", "apparent_temperature"}) {
            if (cur.get(k) instanceof Double) cur.put(k, ((Double) cur.get(k) - 32) * 5 / 9);
        }
        for (String block : new String[] {"hourly", "daily"}) {
            Map<String, Object> m = Json.obj(root, block);
            if (m == null) continue;
            for (String k : new String[] {"temperature_2m", "temperature_2m_max", "temperature_2m_min"}) {
                List<Object> a = Json.arr(m, k);
                if (a == null) continue;
                for (int i = 0; i < a.size(); i++) {
                    if (a.get(i) instanceof Double) a.set(i, ((Double) a.get(i) - 32) * 5 / 9);
                }
            }
        }
    }

    /** Compass point for a wind direction in degrees. */
    static String compass(int deg) {
        String[] p = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return p[((Math.round(deg / 45f) % 8) + 8) % 8];
    }
}
