package tv.lucent.home;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The TV's e-bike forecast against the Python reference that was backtested (preview/fixtures/
 * forecast_vectors.json, made by the analysis from the same assets/bikes_model.json): run-out and
 * next-e-bike minutes must agree within a minute, the morning outlook within half a percent. Also
 * refuses broken models and times the work the TV does.
 */
public final class ForecastTest {
    private static int failures, checks;

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        String model = new String(Files.readAllBytes(Paths.get(root, "assets/bikes_model.json")), StandardCharsets.UTF_8);
        String vectors = new String(Files.readAllBytes(Paths.get(root, "preview/fixtures/forecast_vectors.json")), StandardCharsets.UTF_8);
        Forecast f = Forecast.parse(model);
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        in.setTimeZone(zone);
        for (Object o : Json.arr(Json.parse(vectors))) {
            Map<String, Object> v = Json.obj(o);
            String name = Json.str(v, "name", "");
            long now = in.parse(Json.str(v, "at", "")).getTime();
            double[] rain = doubles(Json.arr(v, "rain")), temp = doubles(Json.arr(v, "temp"));
            String morning = Json.str(v, "morning", "");
            long morningAt = morning.isEmpty() ? 0 : in.parse(morning).getTime();
            long t0 = System.nanoTime();
            Forecast.Result r = f.predict(now, (int) Json.num(v, "nV", 0), (int) Json.num(v, "nE", 0), zone, rain, temp, morningAt);
            long ms = (System.nanoTime() - t0) / 1000000;
            System.out.println(name + ": " + ms + " ms on this computer");
            minutes(name + " pool", Json.arr(v, "pool"), now, r.poolLo, r.poolMed, r.poolHi);
            minutes(name + " first", Json.arr(v, "first"), now, r.firstLo, r.firstMed, r.firstHi);
            minutes(name + " next", Json.arr(v, "next"), now, r.nextLo, r.nextMed, r.nextHi);
            if (v.get("morningP") instanceof Double) {
                checks++;
                double want = (Double) v.get("morningP");
                if (Math.abs(want - r.morning) > 0.005) {
                    failures++;
                    System.out.println("FAIL " + name + " morning: want " + want + ", got " + r.morning);
                }
            }
        }
        refuses("not json", "{");
        refuses("wrong version", model.replace("\"version\":1", "\"version\":2"));
        refuses("a rate out of range", model.replaceFirst("\"wet\":(-?[0-9.]+)", "\"wet\":99"));
        refuses("a short day profile", model.replaceFirst("\"class\":\\[(-?[0-9.]+),", "\"class\":["));
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static double[] doubles(List<Object> a) {
        double[] d = new double[a == null ? 0 : a.size()];
        for (int i = 0; i < d.length; i++) d[i] = a.get(i) instanceof Double ? (Double) a.get(i) : Double.NaN;
        return d;
    }

    /** The reference gives minute indexes (or null past the horizon); the TV gives times a minute after them. */
    private static void minutes(String what, List<Object> want, long now, long lo, long med, long hi) {
        if (want == null) return;
        long[] got = {lo, med, hi};
        for (int k = 0; k < 3; k++) {
            checks++;
            Object w = want.get(k);
            long g = got[k] == 0 ? -1 : (got[k] - now) / 60000L - 1;
            long expect = w instanceof Double ? Math.round((Double) w) : -1;
            if (expect < 0 ? g >= 0 : Math.abs(g - expect) > 1) {
                failures++;
                System.out.println("FAIL " + what + " q" + k + ": want " + expect + ", got " + g);
            }
        }
    }

    private static void refuses(String what, String json) {
        checks++;
        try {
            Forecast.parse(json);
            failures++;
            System.out.println("FAIL accepted " + what);
        } catch (IllegalArgumentException | ClassCastException e) {
            // refused, as it should be
        }
    }
}
