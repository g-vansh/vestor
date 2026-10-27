package tv.vestor.home;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Feeds every parser broken, truncated, mutated and malicious input, the way a hostile or
 * broken server could, and checks that nothing escapes but the exceptions the callers expect
 * (IllegalArgumentException, and IOException from the archive reader). Anything else would be a
 * home screen crash. Also checks the network allowlist and the protobuf length guards.
 */
public final class Hostile {
    private static int checks, failures;
    /** The stops Live asks the realtime feed for. */
    private static final java.util.Set<String> MIT_STOPS =
            new java.util.HashSet<>(java.util.Arrays.asList(Transit.STOP_GRAD_JUNCTION_W, Transit.STOP_W98_VASSAR));

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "preview/fixtures");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261027L;
        Random rnd = new Random(seed);
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 1500;

        String[] jsonFixtures = {"gbfs_info.json", "gbfs_status.json", "mbta_predictions.json", "mbta_schedules.json",
            "adsb.json", "hexdb_route.json", "hexdb_kbos.json", "weather.json"};
        for (String name : jsonFixtures) {
            byte[] good = Files.readAllBytes(dir.resolve(name));
            for (int i = 0; i < rounds; i++) jsonParsers(new String(mutate(good, rnd), StandardCharsets.UTF_8));
        }
        // Pathological shapes.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200000; i++) deep.append('[');
        jsonParsers(deep.toString());
        StringBuilder deepObj = new StringBuilder();
        for (int i = 0; i < 100000; i++) deepObj.append("{\"a\":");
        jsonParsers(deepObj.toString());
        jsonParsers("{\"data\":[{\"attributes\":{\"departure_time\":\"9999-99-99T99:99:99+99:99\"}}]}");
        jsonParsers("{\"current\":{\"temperature_2m\":1e308},\"current_units\":{\"temperature_2m\":\"°F\"}}");
        jsonParsers("{\"current\":{\"temperature_2m\":\"NaN\"}}");
        jsonParsers("{\"ac\":[{\"lat\":1e999,\"lon\":-1e999,\"alt_baro\":[],\"gs\":{}}]}");
        jsonParsers("\"\\u");
        jsonParsers("");
        for (int i = 0; i < rounds * 4; i++) jsonParsers(randomText(rnd));

        byte[] pb = Files.readAllBytes(dir.resolve("mit_trips.pb"));
        for (int i = 0; i < rounds * 5; i++) protobuf(mutate(pb, rnd));
        for (int i = 0; i < rounds; i++) {
            byte[] b = new byte[rnd.nextInt(200)];
            rnd.nextBytes(b);
            protobuf(b);
        }
        // A length that would overflow pos + len: field 2, wire type 2, length 2^31 - 2.
        protobufRejects("huge length", new byte[] {0x12, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 1, 2, 3});
        // Skip of a length-delimited field whose length overflows.
        protobufRejects("huge skip", new byte[] {0x0A, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07});
        // A varint eleven bytes long.
        protobufRejects("long varint", new byte[] {0x08, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01});
        // A deprecated group (wire type 3).
        protobufRejects("group", new byte[] {0x0B, 0x0C});
        // Field number zero.
        protobufRejects("field zero", new byte[] {0x00, 0x01});

        byte[] zip = Files.readAllBytes(dir.resolve("mit_gtfs.zip"));
        for (int i = 0; i < rounds; i++) archive(mutate(zip, rnd));
        archiveRejects("bomb", bomb());
        archiveRejects("many entries", manyEntries());
        archiveRejects("traversal names read as data only", traversal(), false);

        budgets();
        amplifiers(dir);
        allowlist();
        streams(rnd, rounds);
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static void fail(String what, Throwable t) {
        failures++;
        if (failures < 20) {
            System.out.println("FAIL " + what + ": " + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) System.out.println("    at " + st[i]);
        }
    }

    private static void jsonParsers(String s) {
        long now = 1793124300000L;
        run("Gbfs.stations", () -> Gbfs.stations(s, Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON));
        run("Gbfs.status", () -> {
            List<Model.Station> one = new java.util.ArrayList<>();
            Model.Station st = new Model.Station();
            st.id = "f8350cc2-0de8-11e7-991c-3863bb43a7d0";
            one.add(st);
            Gbfs.status(s, one);
        });
        run("Json.objectWith", () -> Json.objectWith(s, "station_id", "x"));
        run("Transit.mbta", () -> Transit.mbta(s, now, Transit.toAssembly(), Transit.toRuggles()));
        run("Transit.mbtaTimetable", () -> {
            List<List<Long>> t = Transit.mbtaTimetable(s);
            Transit.nextNote(t.get(0), now, false);
        });
        run("Sky.aircraft", () -> Sky.aircraft(s, 42.35, -71.1));
        run("Sky.route", () -> Sky.route(s));
        run("Sky.airport", () -> Sky.airport(s, "KBOS"));
        // The weather parser runs on the main thread and must never throw at all.
        checks++;
        try {
            Model.Weather w = Weather.parse(s, "Cambridge", false, now, false);
            if (w.ok && !(Math.abs(w.tempC) < 100)) fail("Weather.parse kept an impossible temperature " + w.tempC,
                    new IllegalStateException());
        } catch (Throwable t) {
            fail("Weather.parse threw", t);
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    /** Only IllegalArgumentException may come out: the callers catch exactly that. */
    private static void run(String what, Body b) {
        checks++;
        try {
            b.run();
        } catch (IllegalArgumentException expected) {
            // the documented failure
        } catch (Throwable t) {
            fail(what, t);
        }
    }

    /**
     * Server-sent events and ntfy lines, mutated and random, through the stream reader with a
     * handler that parses what it gets: nothing may escape, and every hostile shape must end the
     * connection as a fault (false), never hang or read on without bound.
     */
    private static void streams(Random rnd, int rounds) {
        final long far = System.currentTimeMillis() + 3600000L;
        final Stream.Handler parse = new Stream.Handler() {
            @Override
            public void event(String type, String data) {
                Json.parse(data);
            }

            @Override
            public void state(boolean open) {}
        };
        byte[] sse = ("event: reset\ndata: [{\"id\":\"p1\",\"attributes\":{\"arrival_time\":\"2026-10-27T09:00:00-04:00\"}}]\n\n"
                + ": keep-alive\n\nevent: update\r\ndata: {\"id\":\"p1\",\n\ndata: \"x\"}\n\nevent: remove\ndata: {\"id\":\"p1\"}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] ntfy = "{\"id\":\"a\",\"event\":\"open\"}\n{\"id\":\"b\",\"event\":\"message\",\"message\":\"find\"}\n"
                .getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < rounds; i++) {
            final byte[] a = mutate(sse, rnd), b = mutate(ntfy, rnd);
            run("Stream SSE", () -> new Stream("https://api-v3.mbta.com/x", null, Stream.SSE, 1000, 64 * 1024, parse)
                    .read(new java.io.ByteArrayInputStream(a), far));
            run("Stream lines", () -> new Stream("https://ntfy.sh/x", null, Stream.LINES, 1000, parse).read(new java.io.ByteArrayInputStream(b), far));
            final byte[] r = new byte[rnd.nextInt(4096)];
            rnd.nextBytes(r);
            run("Stream random", () -> new Stream("https://ntfy.sh/x", null, Stream.SSE, 1000, parse).read(new java.io.ByteArrayInputStream(r), far));
        }
        // An event built from many short data lines past 2 MB, and a line with no end past 1 MB.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < Stream.MAX_EVENT / 1000 + 10; i++) many.append("data: ").append(rep('x', 1000)).append('\n');
        streamFault("an event past 2 MB", many.toString().getBytes(StandardCharsets.UTF_8), parse, far);
        streamFault("a line past 1 MB", rep('y', Stream.MAX_LINE + 10).getBytes(StandardCharsets.UTF_8), parse, far);
    }

    private static void streamFault(String what, byte[] b, Stream.Handler h, long far) {
        checks++;
        try {
            if (new Stream("https://api-v3.mbta.com/x", null, Stream.SSE, 1000, h).read(new java.io.ByteArrayInputStream(b), far)) {
                failures++;
                System.out.println("FAIL stream: " + what + " read as a clean end");
            }
        } catch (Throwable t) {
            fail("stream: " + what, t);
        }
    }

    private static String rep(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }

    private static void protobuf(byte[] b) {
        run("Gtfs.tripUpdates", () -> Gtfs.tripUpdates(b));
        run("Gtfs.tripUpdates at MIT's stops", () -> Gtfs.tripUpdates(b, MIT_STOPS));
    }

    private static void protobufRejects(String what, byte[] b) {
        checks++;
        try {
            Gtfs.tripUpdates(b);
            failures++;
            System.out.println("FAIL protobuf " + what + ": accepted");
        } catch (IllegalArgumentException expected) {
            // refused, as it should be
        } catch (Throwable t) {
            fail("protobuf " + what, t);
        }
    }

    private static void archive(byte[] b) {
        checks++;
        try {
            Gtfs.readStatic(b);
        } catch (IOException | IllegalArgumentException expected) {
            // refused
        } catch (Throwable t) {
            fail("Gtfs.readStatic", t);
        }
    }

    private static void archiveRejects(String what, byte[] b) {
        archiveRejects(what, b, true);
    }

    private static void archiveRejects(String what, byte[] b, boolean mustRefuse) {
        checks++;
        try {
            Gtfs.Static st = Gtfs.readStatic(b);
            if (mustRefuse) {
                failures++;
                System.out.println("FAIL archive " + what + ": accepted");
            } else if (st.stopName.size() != 1) {
                failures++;
                System.out.println("FAIL archive " + what + ": " + st.stopName);
            }
        } catch (IOException expected) {
            if (!mustRefuse) {
                failures++;
                System.out.println("FAIL archive " + what + ": " + expected);
            }
        } catch (Throwable t) {
            fail("archive " + what, t);
        }
    }

    /** stops.txt that inflates to 200 MB from about 200 KB. */
    private static byte[] bomb() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(out);
        z.putNextEntry(new ZipEntry("stops.txt"));
        byte[] zeros = new byte[1 << 20];
        for (int i = 0; i < 200; i++) z.write(zeros);
        z.closeEntry();
        z.close();
        return out.toByteArray();
    }

    private static byte[] manyEntries() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(out);
        for (int i = 0; i < 5000; i++) {
            z.putNextEntry(new ZipEntry("junk" + i + ".txt"));
            z.closeEntry();
        }
        z.close();
        return out.toByteArray();
    }

    /** Entry names that try to climb out of a folder. Vestor never writes entries to disk. */
    private static byte[] traversal() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(out);
        z.putNextEntry(new ZipEntry("../../../../data/data/tv.vestor.home/shared_prefs/stops.txt"));
        z.write("stop_id,stop_name\n1,Grad Junction\n".getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
        z.close();
        return out.toByteArray();
    }

    /**
     * Answers built to exhaust memory or time, the way a compromised feed operator could. Run
     * with a TV-sized heap (-Xmx192m): each must be refused or handled quickly.
     */
    private static void budgets() throws IOException {
        final AwtGfx[] gfx = new AwtGfx[1];
        StringBuilder many = new StringBuilder("[");
        while (many.length() < 6 * 1024 * 1024) many.append("{},");
        many.append("{}]");
        timed("6 MB of empty objects", 3000, () -> Json.parse(many.toString()));
        StringBuilder hidden = new StringBuilder("{\"data\":{\"stations\":[{\"station_id\":\"x\",\"pad\":[");
        while (hidden.length() < 6 * 1024 * 1024) hidden.append("{},");
        hidden.append("{}]}]}}");
        timed("finder lookup of a padded station", 3000, () -> Json.objectWith(hidden.toString(), "station_id", "x"));
        StringBuilder repeats = new StringBuilder();
        while (repeats.length() < 6 * 1024 * 1024) repeats.append("{\"station_id\":\"x\"");
        timed("finder lookup with the id everywhere", 3000, () -> Json.objectWith(repeats.toString(), "station_id", "x"));
        StringBuilder longString = new StringBuilder("{\"name\":\"");
        while (longString.length() < 4 * 1024 * 1024) longString.append('a');
        longString.append("\"}");
        timed("a 4 MB string", 3000, () -> Json.parse(longString.toString()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(out);
        z.putNextEntry(new ZipEntry("stops.txt"));
        byte[] commas = new byte[1 << 20];
        java.util.Arrays.fill(commas, (byte) ',');
        for (int i = 0; i < 15; i++) z.write(commas);
        z.closeEntry();
        z.close();
        final byte[] commaZip = out.toByteArray();
        timed("15 MB of commas in the timetable", 5000, () -> Gtfs.readStatic(commaZip));

        StringBuilder label = new StringBuilder();
        for (int i = 0; i < 100000; i++) label.append("Wide app name ");
        try {
            gfx[0] = new AwtGfx(new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics(),
                    new java.io.File("assets/fonts"));
        } catch (Exception e) {
            throw new IOException(e);
        }
        timed("fitting a 1.4 MB app name", 500, () -> Text.fit(gfx[0], label.toString(), Gfx.TEXT_MEDIUM, 26, 300));
        checks++;
        String cleaned = Text.clean("Net\u202Eflix\u0000\n\u200Bmore" + label);
        if (cleaned.length() > Text.MAX || cleaned.indexOf('\u202E') >= 0 || cleaned.indexOf('\u0000') >= 0
                || cleaned.indexOf('\u200B') >= 0 || !cleaned.startsWith("Netflix more")) {
            failures++;
            System.out.println("FAIL Text.clean: " + cleaned.substring(0, Math.min(40, cleaned.length())));
        }
    }

    /**
     * Answers that cost far more than their size (from the October 2026 audit): a timetable whose
     * every row became a map of all its columns, a station lookup that parsed the whole document
     * once for each place the id appears, and a realtime feed kept whole whatever stops it names.
     */
    private static void amplifiers(Path dir) throws Exception {
        timetableHeap();
        timetableBudget();
        lookupBounded();
        lookupAsParsed(dir);
        realtimeKept(dir);
    }

    /**
     * Hostile timetables read in a JVM with the TV's heap ({@link Heap}): each must be kept or
     * refused there, never run it out of memory.
     */
    private static void timetableHeap() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx192m", "-cp", System.getProperty("java.class.path"),
                Heap.class.getName());
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int seen = 0;
        StringBuilder other = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("ok ") || line.startsWith("FAIL ")) {
                    checks++;
                    seen++;
                    if (line.startsWith("FAIL ")) {
                        failures++;
                        System.out.println(line);
                    }
                } else {
                    other.append("    ").append(line).append('\n');
                }
            }
        }
        int code = p.waitFor();
        checks++;
        if (code != 0 || seen != Heap.CASES) {
            failures++;
            System.out.print("FAIL timetables at -Xmx192m: exit " + code + ", " + seen + " of " + Heap.CASES + " cases\n" + other);
        }
    }

    /** Reads each hostile timetable with the heap its JVM was given, one line per case. */
    public static final class Heap {
        static final int CASES = 4;

        public static void main(String[] args) throws IOException {
            StringBuilder named = new StringBuilder("stop_id,stop_name");
            for (int c = 2; c < 48; c++) named.append(",c").append(c);
            String commas = repeat(",", 47) + "\n";
            read("the audit's 7 KB zip, 48 named columns over 50,000 rows of 47 commas",
                    zip("stops.txt", named + "\n" + repeat(commas, 50000)));
            StringBuilder wide = new StringBuilder(named).append('\n');
            for (int i = 0; i < 50000; i++) wide.append(i).append(",Stop ").append(i).append(repeat(",", 46)).append('\n');
            read("50,000 distinct stops, 48 columns each", zip("stops.txt", wide.toString()));
            read("three tables of 190,000 distinct rows", distinct(190000));
            byte[] fifteen = new byte[15 << 20];
            java.util.Arrays.fill(fifteen, (byte) ',');
            read("15 MB of commas", zip("stops.txt", new String(fifteen, StandardCharsets.US_ASCII)));
        }

        private static void read(String what, byte[] zip) {
            long t0 = System.nanoTime();
            try {
                Gtfs.Static st = Gtfs.readStatic(zip);
                int kept = st.tripRoute.size() + st.routeLong.size() + st.routeShort.size() + st.stopName.size();
                System.out.println("ok " + what + ": " + zip.length + " B zip, kept " + kept + ", "
                        + (System.nanoTime() - t0) / 1000000 + " ms");
            } catch (IOException | IllegalArgumentException refused) {
                System.out.println("ok " + what + ": " + zip.length + " B zip, refused (" + refused.getMessage() + "), "
                        + (System.nanoTime() - t0) / 1000000 + " ms");
            } catch (Throwable t) {
                System.out.println("FAIL " + what + ": " + t);
            }
        }

        /** All three tables, {@code n} rows each, every key different. */
        static byte[] distinct(int n) throws IOException {
            StringBuilder trips = new StringBuilder("trip_id,route_id\n");
            StringBuilder routes = new StringBuilder("route_id,route_long_name,route_short_name\n");
            StringBuilder stops = new StringBuilder("stop_id,stop_name\n");
            for (int i = 0; i < n; i++) {
                trips.append('t').append(i).append(",r").append(i).append('\n');
                routes.append('r').append(i).append(",Long ").append(i).append(",S").append(i).append('\n');
                stops.append(i).append(",Stop ").append(i).append('\n');
            }
            return zip("trips.txt", trips.toString(), "routes.txt", routes.toString(), "stops.txt", stops.toString());
        }

        static byte[] zip(String... nameThenText) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ZipOutputStream z = new ZipOutputStream(out);
            for (int i = 0; i + 1 < nameThenText.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameThenText[i]));
                z.write(nameThenText[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            z.close();
            return out.toByteArray();
        }
    }

    static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    /**
     * Far more rows than MIT's few hundred are refused rather than kept, and a timetable many
     * times MIT's size is still kept whole.
     */
    private static void timetableBudget() throws IOException {
        StringBuilder trips = new StringBuilder("trip_id,route_id\n");
        for (int i = 0; i < 150000; i++) trips.append("trip-").append(i).append(",63220\n");
        archiveRejects("150,000 distinct trips", Heap.zip("trips.txt", trips.toString()));
        checks++;
        try {
            Gtfs.Static st = Gtfs.readStatic(Heap.distinct(5000));
            if (st.tripRoute.size() != 5000 || st.routeLong.size() != 5000 || st.stopName.size() != 5000
                    || !"Long 4999".equals(st.name("r4999"))) {
                failures++;
                System.out.println("FAIL 5,000-row timetable: " + st.tripRoute.size() + " trips, " + st.stopName.size() + " stops");
            }
        } catch (Throwable t) {
            fail("5,000-row timetable", t);
        }
    }

    /**
     * The id at 64 places at the top level, 6 MB of stations, then an error: the lookup must cost
     * a small multiple of one parse of the whole document, not one parse per place.
     */
    private static void lookupBounded() {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < 64; i++) b.append("\"station_id\":\"x\",");
        b.append("\"stations\":[");
        String station = "{\"name\":\"" + repeat("Westgate ", 12) + "\"},";
        while (b.length() < 6 * 1024 * 1024) b.append(station);
        b.append("{}]!");
        String doc = b.toString();
        long parse = Long.MAX_VALUE, lookup = Long.MAX_VALUE;
        Map<String, Object> found = null;
        for (int i = 0; i < 4; i++) {
            long t0 = System.nanoTime();
            try {
                Json.parse(doc);
            } catch (IllegalArgumentException expected) {
                // the trailing error
            }
            long t1 = System.nanoTime();
            found = Json.objectWith(doc, "station_id", "x");
            long t2 = System.nanoTime();
            parse = Math.min(parse, t1 - t0);
            lookup = Math.min(lookup, t2 - t1);
        }
        checks++;
        if (lookup > 4 * parse + 20000000L || found != null) {
            failures++;
            System.out.printf("FAIL lookup with 64 top-level ids: %.1f ms against %.1f ms for one parse, found %s%n",
                    lookup / 1e6, parse / 1e6, found);
        }
    }

    /** Every station a real status document holds is found exactly as a full parse reads it. */
    private static void lookupAsParsed(Path dir) throws IOException {
        for (String name : new String[] {"gbfs_status.json", "gbfs_status_found.json"}) {
            String doc = new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
            List<Object> stations = Json.arr(Json.obj(Json.obj(Json.parse(doc)), "data"), "stations");
            int differ = 0;
            for (Object o : stations) {
                Map<String, Object> want = Json.obj(o);
                Map<String, Object> got = Json.objectWith(doc, "station_id", Json.str(want, "station_id", ""));
                checks++;
                if (!want.equals(got)) {
                    failures++;
                    if (differ++ < 3) System.out.println("FAIL lookup in " + name + ": want " + want + ", got " + got);
                }
            }
            checks++;
            if (stations.size() < 500 || Json.objectWith(doc, "station_id", "no-such-station") != null) {
                failures++;
                System.out.println("FAIL lookup in " + name + ": " + stations.size() + " stations, or an unknown id found");
            }
        }
    }

    /**
     * A 6 MB realtime feed keeps at most the cap, however many predictions it holds, and asking
     * for MIT's two stops changes none of the lines shown.
     */
    private static void realtimeKept(Path dir) throws IOException {
        byte[] ours = bigFeed(Transit.STOP_W98_VASSAR, 6 * 1024 * 1024);
        byte[] theirs = bigFeed("1", 6 * 1024 * 1024);
        int cap = Gtfs.MAX_UPDATES;
        kept("6 MB feed at a stop Vestor shows, every stop", Gtfs.tripUpdates(ours), cap, cap);
        kept("6 MB feed at another stop, every stop", Gtfs.tripUpdates(theirs), cap, cap);
        kept("6 MB feed at a stop Vestor shows, MIT's stops", Gtfs.tripUpdates(ours, MIT_STOPS), cap, cap);
        kept("6 MB feed at another stop, MIT's stops", Gtfs.tripUpdates(theirs, MIT_STOPS), 0, 0);
        timed("700,000 predictions at another stop", 1000, () -> Gtfs.tripUpdates(theirs, MIT_STOPS));

        byte[] pb = Files.readAllBytes(dir.resolve("mit_trips.pb"));
        Gtfs.Static st = Gtfs.readStatic(Files.readAllBytes(dir.resolve("mit_gtfs.zip")));
        long now = 1793124300000L;
        Model.Line[] all = {Transit.tech(), Transit.tech2(), Transit.saferide()};
        Model.Line[] some = {Transit.tech(), Transit.tech2(), Transit.saferide()};
        Transit.mit(Gtfs.tripUpdates(pb), st, now, all[0], all[1], all[2]);
        List<Gtfs.Update> mine = Gtfs.tripUpdates(pb, MIT_STOPS);
        Transit.mit(mine, st, now, some[0], some[1], some[2]);
        for (int i = 0; i < 3; i++) {
            checks++;
            if (all[i].minutes.isEmpty() || !all[i].minutes.equals(some[i].minutes)) {
                failures++;
                System.out.println("FAIL " + all[i].id + " from MIT's stops: " + some[i].minutes + ", every stop " + all[i].minutes);
            }
        }
        checks++;
        for (Gtfs.Update u : mine) {
            if (!MIT_STOPS.contains(u.stopId)) {
                failures++;
                System.out.println("FAIL kept a prediction at stop " + u.stopId);
                break;
            }
        }
    }

    private static void kept(String what, List<Gtfs.Update> got, int min, int max) {
        checks++;
        if (got.size() < min || got.size() > max) {
            failures++;
            System.out.println("FAIL " + what + ": kept " + got.size() + ", want " + min + " to " + max);
        }
    }

    /**
     * A TripUpdates feed of about {@code size} bytes: one trip whose every stop-time update is an
     * arrival at {@code stop} (9 bytes each for a one-character stop, so about 700,000 in 6 MB).
     */
    static byte[] bigFeed(String stop, int size) {
        byte[] id = stop.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream update = new ByteArrayOutputStream();
        // TripUpdate.trip { trip_id: "T-1" }, then StopTimeUpdate { arrival { time: 1 }, stop_id }.
        field(update, 1, new byte[] {0x0A, 3, 'T', '-', '1'});
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        field(one, 2, new byte[] {0x10, 0x01});
        field(one, 4, id);
        byte[] stu = one.toByteArray();
        while (update.size() < size) field(update, 2, stu);
        ByteArrayOutputStream entity = new ByteArrayOutputStream();
        field(entity, 1, new byte[] {'e'});
        field(entity, 3, update.toByteArray());
        ByteArrayOutputStream feed = new ByteArrayOutputStream();
        field(feed, 2, entity.toByteArray());
        return feed.toByteArray();
    }

    /** A length-delimited protobuf field. */
    private static void field(ByteArrayOutputStream out, int number, byte[] body) {
        varint(out, number << 3 | 2);
        varint(out, body.length);
        out.write(body, 0, body.length);
    }

    private static void varint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write((int) v);
    }

    /** Must end, by any of the expected exceptions or none, within {@code ms}. */
    private static void timed(String what, long ms, Body b) {
        checks++;
        long t0 = System.nanoTime();
        try {
            b.run();
        } catch (IllegalArgumentException | IOException expected) {
            // refused
        } catch (Throwable t) {
            fail(what, t);
            return;
        }
        long took = (System.nanoTime() - t0) / 1000000;
        if (took > ms) {
            failures++;
            System.out.println("FAIL " + what + ": took " + took + " ms");
        }
    }

    private static void allowlist() {
        String[][] cases = {
            {"https://gbfs.lyft.com/gbfs/2.3/bos/en/station_status.json", "true"},
            {"https://api-v3.mbta.com/predictions?filter%5Bstop%5D=21772", "true"},
            {"https://hexdb.io/api/v1/route/icao/JBU1387", "true"},
            {"https://API.ADSB.LOL/v2/point/42.35/-71.10/25", "true"},
            {"http://gbfs.lyft.com/gbfs/2.3/bos/en/station_status.json", "false"},
            {"https://gbfs.lyft.com:8443/x", "false"},
            {"https://evil.example.com/gbfs.lyft.com", "false"},
            {"https://gbfs.lyft.com.evil.example.com/x", "false"},
            {"https://user@gbfs.lyft.com/x", "false"},
            {"https://evil.example.com#@gbfs.lyft.com", "false"},
            {"https://evil.example.com\\@gbfs.lyft.com/", "false"},
            {"ftp://gbfs.lyft.com/x", "false"},
            {"file:///data/data/tv.vestor.home/shared_prefs/vestor.xml", "false"},
            {"javascript:alert(1)", "false"},
            {"", "false"},
            {null, "false"},
        };
        for (String[] c : cases) {
            checks++;
            boolean got = Net.allowed(c[0]);
            if (got != Boolean.parseBoolean(c[1])) {
                failures++;
                System.out.println("FAIL allowlist " + c[0] + ": got " + got);
            }
        }
        checks++;
        if (Net.get("https://example.com/", 1000, false) != null) {
            failures++;
            System.out.println("FAIL a host outside the allowlist was fetched");
        }
    }

    /** Byte flips, truncations, duplications and splices, a few at a time. */
    private static byte[] mutate(byte[] in, Random rnd) {
        byte[] b = in.clone();
        int edits = 1 + rnd.nextInt(6);
        for (int e = 0; e < edits && b.length > 1; e++) {
            switch (rnd.nextInt(5)) {
                case 0:
                    b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
                    break;
                case 1:
                    b = java.util.Arrays.copyOf(b, rnd.nextInt(b.length));
                    break;
                case 2: {
                    int at = rnd.nextInt(b.length), len = Math.min(b.length - at, 1 + rnd.nextInt(64));
                    byte[] out = new byte[b.length + len];
                    System.arraycopy(b, 0, out, 0, at + len);
                    System.arraycopy(b, at, out, at + len, b.length - at);
                    b = out;
                    break;
                }
                case 3: {
                    String[] tokens = {"null", "[]", "{}", "\"", "-1e308", "1e400", "\"\\uD800\"", "NaN", ",", ":", "]]]]"};
                    byte[] t = tokens[rnd.nextInt(tokens.length)].getBytes(StandardCharsets.UTF_8);
                    int at = rnd.nextInt(b.length);
                    byte[] out = new byte[b.length + t.length];
                    System.arraycopy(b, 0, out, 0, at);
                    System.arraycopy(t, 0, out, at, t.length);
                    System.arraycopy(b, at, out, at + t.length, b.length - at);
                    b = out;
                    break;
                }
                default: {
                    int at = rnd.nextInt(b.length), len = Math.min(b.length - at, 1 + rnd.nextInt(256));
                    byte[] out = new byte[b.length - len];
                    System.arraycopy(b, 0, out, 0, at);
                    System.arraycopy(b, at + len, out, at, b.length - at - len);
                    b = out;
                }
            }
        }
        return b;
    }

    private static String randomText(Random rnd) {
        String alphabet = "{}[]\",:0123456789.eE+-truefalsnul \\u";
        int n = rnd.nextInt(300);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
