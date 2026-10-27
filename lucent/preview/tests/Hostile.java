package tv.lucent.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
        allowlist();
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

    private static void protobuf(byte[] b) {
        run("Gtfs.tripUpdates", () -> Gtfs.tripUpdates(b));
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

    /** Entry names that try to climb out of a folder. Lucent never writes entries to disk. */
    private static byte[] traversal() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(out);
        z.putNextEntry(new ZipEntry("../../../../data/data/tv.lucent.home/shared_prefs/stops.txt"));
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
            {"file:///data/data/tv.lucent.home/shared_prefs/lucent.xml", "false"},
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
