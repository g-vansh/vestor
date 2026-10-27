package tv.lucent.home;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Checks every feed parser against the fixtures in preview/fixtures (see tools/fixtures.py),
 * including a GTFS-realtime feed written by Google's protobuf encoder.
 */
public final class Feeds {
    private static final long NOW = 1793124300000L;
    private static int failures, checks;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "preview/fixtures");
        bikes(dir);
        transit(dir);
        sky(dir);
        weather(dir);
        json();
        bell();
        tiles();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static String text(Path dir, String name) throws Exception {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void bikes(Path dir) throws Exception {
        List<Model.Station> all = Gbfs.stations(text(dir, "gbfs_info.json"), Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
        eq("station count", 564, all.size());
        String west = Gbfs.westgate(all);
        eq("westgate id", "f8350cc2-0de8-11e7-991c-3863bb43a7d0", west);
        eq("nearest first", west, all.get(0).id);
        List<Model.Station> follow = Gbfs.follow(all, west, 3);
        eq("follow size", 4, follow.size());
        eq("follow main", west, follow.get(0).id);
        String status = text(dir, "gbfs_status.json");
        long t0 = System.nanoTime();
        int found = 0;
        for (int i = 0; i < 50; i++) found = Gbfs.status(status, follow);
        System.out.printf("status for %d stations: %.2f ms per poll (desktop)%n", follow.size(), (System.nanoTime() - t0) / 50 / 1e6);
        eq("status found", 4, found);
        Model.Station w = follow.get(0);
        eq("westgate classic", 6, w.classic);
        eq("westgate ebikes", 0, w.ebikes);
        eq("westgate docks", 18, w.docks);
        eq("westgate capacity", 25, w.capacity);
        eq("westgate short", "Westgate", Gbfs.shortName(w, true));
        Model.Station pac = Gbfs.copy(Gbfs.find(all, Gbfs.PACIFIC));
        Gbfs.status(status, Arrays.asList(pac));
        eq("pacific ebikes from vehicle types", 1, pac.ebikes);
        eq("pacific classic", 15, pac.classic);
        eq("pacific short", "Pacific St", Gbfs.shortName(pac, false));
        Model.Station amh = Gbfs.copy(Gbfs.find(all, "f83493c0-0de8-11e7-991c-3863bb43a7d0"));
        Gbfs.status(status, Arrays.asList(amh));
        eq("not renting means none rentable", 0, amh.rentableEbikes());
        eq("amherst short", "Mass Ave", Gbfs.shortName(amh, false));
        Model.Station stata = Gbfs.copy(Gbfs.find(all, "f8349de4-0de8-11e7-991c-3863bb43a7d0"));
        eq("stata short", "Stata Center", Gbfs.shortName(stata, false));
        Gbfs.status(text(dir, "gbfs_status_found.json"), Arrays.asList(w));
        eq("westgate e-bike docked", 1, w.rentableEbikes());
        eq("reported", NOW / 1000 - 40 + 30, w.reported / 1000 + 30);
    }

    private static void transit(Path dir) throws Exception {
        Gtfs.Static st = Gtfs.readStatic(Files.readAllBytes(dir.resolve("mit_gtfs.zip")));
        eq("trip route", "63220", st.tripRoute.get("T-2"));
        eq("quoted name", "Saferide Cambridge West", st.name("55555"));
        eq("bom header", "56642", st.tripRoute.get("T2-1"));
        eq("stop name", "W98 @ Vassar St", st.stopName.get("3813"));
        List<Gtfs.Update> ups = Gtfs.tripUpdates(Files.readAllBytes(dir.resolve("mit_trips.pb")));
        eq("updates", 11, ups.size());
        Model.Line tech = Transit.tech(), tech2 = Transit.tech2(), safe = Transit.saferide();
        Transit.mit(ups, st, NOW, tech, tech2, safe);
        eq("tech", Arrays.asList(4, 16), tech.minutes);
        eq("tech 2", Arrays.asList(9, 24), tech2.minutes);
        eq("saferide", Arrays.asList(7, 22), safe.minutes);
        Model.Line asm = Transit.toAssembly(), rug = Transit.toRuggles();
        Transit.mbta(text(dir, "mbta_predictions.json"), NOW, asm, rug);
        eq("85 to Assembly", Arrays.asList(7, 23), asm.minutes);
        eq("85 to Ruggles", Arrays.asList(2, 13), rug.minutes);
        List<List<Long>> tt = Transit.mbtaTimetable(text(dir, "mbta_schedules.json"));
        eq("timetable sizes", "5/5", tt.get(0).size() + "/" + tt.get(1).size());
        eq("next to Assembly", "Next 2:20 PM", Transit.nextNote(tt.get(0), NOW, false));
        eq("next to Ruggles 24h", "Next 14:25", Transit.nextNote(tt.get(1), NOW, true));
        eq("none left", "No more today", Transit.nextNote(tt.get(0), NOW + 6 * 3600000L, false));
        eq("iso", 1793124300000L + 130000L, Transit.iso("2026-10-27T14:07:10-04:00"));
        eq("iso junk", 0L, Transit.iso("2026-13-45T99:99:99Z!"));
        eq("service day past midnight", "2026-10-27", Transit.serviceDate(1793124300000L + 11 * 3600000L + 25 * 60000L));
        Model.Transit t = new Model.Transit();
        t.lines.add(tech);
        t.lines.add(Transit.saferide());
        Transit.idleNotes(t, NOW);
        eq("saferide idle by day", "From 6 PM", t.lines.get(1).idle);
        eq("now", "Now", Transit.lead(0));
        eq("hour", "1 h 12 m", Transit.lead(72));
        eq("saferide late", "On demand", Transit.note("saferide", 23 * 60 + 30, false));
        // Without the static zip, ids still classify; SafeRide by name needs it.
        Model.Line a = Transit.tech(), b = Transit.tech2(), c = Transit.saferide();
        Transit.mit(ups, null, NOW, a, b, c);
        eq("tech without static", Arrays.asList(4), a.minutes);
        grocery();
    }

    /** The Trader Joe's and Whole Foods shuttle: its days, its line at Tang/Westgate, its idle words. */
    private static void grocery() {
        java.util.TimeZone ny = java.util.TimeZone.getTimeZone("America/New_York");
        java.util.Calendar c = java.util.Calendar.getInstance(ny);
        String[] days = {"2026-10-30", "2026-11-01", "2026-11-08", "2026-11-15", "2026-11-22", "2026-11-29", "2026-12-25",
            "2026-10-31", "2026-10-27"};
        String want = "yes yes no yes no yes no no no", got = "";
        for (String d : days) {
            c.clear();
            c.set(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(5, 7)) - 1, Integer.parseInt(d.substring(8)), 12, 0);
            got += (got.isEmpty() ? "" : " ") + (Transit.groceryDay(c.getTimeInMillis()) ? "yes" : "no");
        }
        eq("grocery days (Fri, Sun 1st 2nd 3rd 4th 5th, Christmas, Sat, Tue)", want, got);
        eq("grocery by id", 4, Transit.kind("64288", ""));
        eq("grocery by name", 4, Transit.kind("1", "Trader Joe-Whole Foods"));
        eq("costco is not ours", 0, Transit.kind("64291", "Costco - Aldi's"));
        Gtfs.Update u = new Gtfs.Update(), elsewhere = new Gtfs.Update();
        u.routeId = "64288";
        u.stopId = Transit.STOP_TANG_WESTGATE;
        u.time = NOW / 1000 + 6 * 60;
        elsewhere.routeId = "64288";
        elsewhere.stopId = "27098";
        elsewhere.time = NOW / 1000 + 3 * 60;
        Model.Line g = Transit.grocery();
        Transit.mit(Arrays.asList(u, elsewhere), null, NOW, Transit.tech(), Transit.tech2(), Transit.saferide(), g);
        eq("grocery at Westgate only", Arrays.asList(6), g.minutes);
        eq("grocery shown when tracked", true, Transit.groceryShown(g, NOW));
        eq("grocery hidden on a Tuesday", false, Transit.groceryShown(Transit.grocery(), NOW));
        eq("grocery before the first run", "Leaves Kendall 11:00 AM", Transit.note("grocery", 10 * 60 + 30, false));
        eq("grocery midday", "Leaves Kendall 1:03 PM", Transit.note("grocery", 12 * 60 + 40, false));
        eq("grocery after the last run", "Last run left 3:09", Transit.note("grocery", 15 * 60 + 30, false));
    }

    private static void sky(Path dir) throws Exception {
        Model.Sky s = Sky.aircraft(text(dir, "adsb.json"), Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
        eq("in range", 5, s.inRange);
        eq("airborne", 4, s.flights.size());
        Model.Flight h = s.hero();
        eq("hero", "JBU1387", h.callsign);
        eq("hero name", "JetBlue 1387", Sky.flightName(h));
        eq("hero alt", 12400, h.altFt);
        eq("hero fpm", -1088, h.fpm);
        eq("second", "N12345", s.flights.get(1).callsign);
        eq("n-number airline", "", s.flights.get(1).airline);
        eq("geom rate", 320, s.flights.get(1).fpm);
        eq("no callsign last", "", s.flights.get(3).callsign);
        String[] r = Sky.route(text(dir, "hexdb_route.json"));
        eq("route", "KBOS>KSFO", r[0] + ">" + r[1]);
        eq("bos", "BOS Logan", String.join(" ", Sky.airport(text(dir, "hexdb_kbos.json"), "KBOS")));
        eq("sfo", "SFO San Francisco", String.join(" ", Sky.airport(text(dir, "hexdb_ksfo.json"), "KSFO")));
        System.out.printf("hero %.2f mi%n", h.miles);
    }

    private static void weather(Path dir) throws Exception {
        Model.Weather w = Weather.parse(text(dir, "weather.json"), "Cambridge", false, NOW, true);
        eq("ok", true, w.ok);
        String url = Weather.url(42.36123, -71.09087);
        eq("weather position rounded to 0.02 degrees", true, url.contains("latitude=42.36&longitude=-71.10&"));
        eq("c", 11, Model.Weather.c(w.tempC));
        eq("f", 53, Model.Weather.f(w.tempC));
        eq("hi", "13/56", Model.Weather.c(w.hiC) + "/" + Model.Weather.f(w.hiC));
        eq("lo", "5/41", Model.Weather.c(w.loC) + "/" + Model.Weather.f(w.loC));
        eq("hours", 7, w.hourC.size());
        eq("hour label", "3 PM", w.hourLabel.get(1));
        eq("night hour", false, w.hourDay.get(4));
        eq("wind", "W", Weather.compass(w.windDir));
        eq("humidity", 58, w.humidity);
        eq("rain chance", 70, (int) w.hourRain.get(4));
        Model.Weather f = new Model.Weather();
        f.celsiusFirst = false;
        eq("lead f", 53, f.lead(11.4));
        eq("other c", 11, f.other(11.4));
    }

    private static void json() {
        Map<String, Object> m = Json.obj(Json.parse("{\"a\":\"x\\u00e9\\n\",\"b\":[1,2.5,-3e2,true,null],\"c\":{}}"));
        eq("escape", "xé\n", m.get("a"));
        eq("array", 5, Json.arr(m, "b").size());
        eq("exp", -300.0, Json.arr(m, "b").get(2));
        eq("objectWith spaced", "2", Json.str(Json.objectWith("[{\"id\" : \"1\", \"v\":1},{\"v\":{\"w\":2}, \"id\": \"2\"}]", "id", "2"), "id", null));
        eq("objectWith value as other key", null, Json.objectWith("[{\"x\":\"2\",\"id\":\"3\"}]", "id", "2"));
        boolean threw = false;
        try {
            Json.parse("{\"a\":1,}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        eq("rejects trailing comma", true, threw);
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) deep.append('[');
        boolean refused = false;
        try {
            Json.parse(deep.toString());
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        eq("refuses absurd nesting without overflowing the stack", true, refused);
        eq("normal nesting fine", 1, Json.arr(Json.parse("[[[[[[[[[[1]]]]]]]]]]")).size());
    }

    private static void tiles() {
        for (int n : new int[] {1, 7, 48, 192}) {
            int[] opaque = new int[n * n], round = new int[n * n];
            for (int i = 0; i < n * n; i++) {
                opaque[i] = 0xFFCC2233;
                int x = i % n, y = i / n;
                double dx = x - n / 2.0, dy = y - n / 2.0;
                round[i] = dx * dx + dy * dy < n * n / 5.0 ? 0xFF2255EE : 0;
            }
            int[] a = Tiles.compose(opaque, n, null, null, 0);
            int[] b = Tiles.compose(round, n, null, null, 0);
            int[] c = Tiles.compose(opaque, n, opaque, round, n);
            eq("tile size " + n, Tiles.W * Tiles.H, a.length + b.length + c.length - 2 * Tiles.W * Tiles.H);
            int mid = a[(Tiles.H / 2) * Tiles.W + Tiles.W / 2];
            if (n >= 48) eq("icon in the middle " + n, 0xFFCC2233, mid);
            boolean opaqueOut = true;
            for (int p : b) opaqueOut &= (p >>> 24) == 255;
            eq("tile opaque " + n, true, opaqueOut);
        }
    }

    private static void bell() {
        short[] s = Bell.samples();
        int peak = 0;
        for (short v : s) peak = Math.max(peak, Math.abs(v));
        eq("bell length", Bell.RATE * 16 / 10, s.length);
        checks++;
        if (peak > 0.45 * 32767 || peak < 0.3 * 32767) {
            failures++;
            System.out.println("FAIL bell peak " + peak);
        }
        eq("bell ends silent", 0, (int) s[s.length - 1]);
        // The alarm rings loud from the first second: its peak sits just under full scale.
        short[] r = Bell.ring();
        int rp = 0;
        for (short v : r) rp = Math.max(rp, Math.abs(v));
        checks++;
        if (rp < 0.85 * 32767 || rp > 0.9 * 32767) {
            failures++;
            System.out.println("FAIL ring peak " + rp);
        }
        eq("ring ends silent", 0, (int) r[r.length - 1]);
    }
}
