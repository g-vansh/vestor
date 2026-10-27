package tv.lucent.home;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GTFS-realtime TripUpdates (protobuf) and the static GTFS zip, read with no libraries. MIT's
 * shuttles all run on one Passio feed; its per-stop REST endpoint is off, so arrivals come from
 * the realtime feed exactly as Vestor's mit_shuttle.py reads them.
 *
 * Field numbers are from gtfs-realtime.proto: FeedMessage.entity = 2, FeedEntity.trip_update = 3,
 * TripUpdate.trip = 1 and .stop_time_update = 2, TripDescriptor.trip_id = 1 and .route_id = 5,
 * StopTimeUpdate.arrival = 2, .departure = 3, .stop_id = 4, StopTimeEvent.time = 2.
 */
final class Gtfs {
    private Gtfs() {}

    /** One predicted arrival of a trip at a stop. */
    static final class Update {
        String tripId = "", routeId = "", stopId = "";
        long time;
    }

    /** What the static GTFS adds: each trip's route, each route's names, each stop's name. */
    static final class Static {
        final Map<String, String> tripRoute = new HashMap<>();
        final Map<String, String> routeLong = new HashMap<>();
        final Map<String, String> routeShort = new HashMap<>();
        final Map<String, String> stopName = new HashMap<>();

        String routeOf(Update u) {
            if (!u.routeId.isEmpty()) return u.routeId;
            String r = tripRoute.get(u.tripId);
            return r == null ? "" : r;
        }

        String name(String routeId) {
            String n = routeLong.get(routeId);
            if (n == null || n.isEmpty()) n = routeShort.get(routeId);
            return n == null ? "" : n;
        }
    }

    // ---- Protobuf wire format ---------------------------------------------------------------

    static final class Pb {
        private final byte[] b;
        private int pos;
        private final int end;

        Pb(byte[] b) {
            this(b, 0, b.length);
        }

        Pb(byte[] b, int off, int len) {
            this.b = b;
            this.pos = off;
            this.end = off + len;
        }

        boolean more() {
            return pos < end;
        }

        private void advance(int n) {
            if (n > end - pos) throw new IllegalArgumentException("truncated");
            pos += n;
        }

        long varint() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IllegalArgumentException("truncated varint");
                int x = b[pos++];
                v |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("varint too long");
        }

        /** The next field's key: number << 3 | wire type. */
        int key() {
            long k = varint();
            if (k <= 0 || k > Integer.MAX_VALUE) throw new IllegalArgumentException("bad key");
            return (int) k;
        }

        /**
         * A length-delimited field's length, checked against what is left. Written as
         * {@code len > end - pos} so a huge length cannot overflow past the check.
         */
        private int length() {
            long len = varint();
            if (len < 0 || len > end - pos) throw new IllegalArgumentException("bad length");
            return (int) len;
        }

        Pb message() {
            int len = length();
            Pb sub = new Pb(b, pos, len);
            pos += len;
            return sub;
        }

        String string() {
            int len = length();
            String s = new String(b, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        void skip(int wire) {
            switch (wire) {
                case 0:
                    varint();
                    break;
                case 1:
                    advance(8);
                    break;
                case 2: {
                    // Read the length first: "pos += length()" would add it to pos as it was
                    // before the length's own bytes.
                    int len = length();
                    pos += len;
                    break;
                }
                case 5:
                    advance(4);
                    break;
                default:
                    // 3 and 4 are the long-deprecated groups, 6 and 7 do not exist.
                    throw new IllegalArgumentException("wire type " + wire);
            }
        }
    }

    /** Every stop-time prediction in a TripUpdates FeedMessage. */
    static List<Update> tripUpdates(byte[] feed) {
        List<Update> out = new ArrayList<>();
        Pb msg = new Pb(feed);
        while (msg.more()) {
            int k = msg.key();
            if (k >>> 3 == 2 && (k & 7) == 2) entity(msg.message(), out);
            else msg.skip(k & 7);
        }
        return out;
    }

    private static void entity(Pb e, List<Update> out) {
        boolean deleted = false;
        Pb trip = null;
        while (e.more()) {
            int k = e.key();
            int field = k >>> 3, wire = k & 7;
            if (field == 2 && wire == 0) deleted = e.varint() != 0;
            else if (field == 3 && wire == 2) trip = e.message();
            else e.skip(wire);
        }
        if (!deleted && trip != null) tripUpdate(trip, out);
    }

    private static void tripUpdate(Pb t, List<Update> out) {
        String tripId = "", routeId = "";
        List<Pb> stops = new ArrayList<>();
        while (t.more()) {
            int k = t.key();
            int field = k >>> 3, wire = k & 7;
            if (field == 1 && wire == 2) {
                Pb d = t.message();
                while (d.more()) {
                    int dk = d.key();
                    if (dk >>> 3 == 1 && (dk & 7) == 2) tripId = d.string();
                    else if (dk >>> 3 == 5 && (dk & 7) == 2) routeId = d.string();
                    else d.skip(dk & 7);
                }
            } else if (field == 2 && wire == 2) {
                stops.add(t.message());
            } else {
                t.skip(wire);
            }
        }
        for (Pb s : stops) {
            String stopId = "";
            long arrival = 0, departure = 0;
            while (s.more()) {
                int k = s.key();
                int field = k >>> 3, wire = k & 7;
                if (field == 4 && wire == 2) stopId = s.string();
                else if ((field == 2 || field == 3) && wire == 2) {
                    long time = eventTime(s.message());
                    if (field == 2) arrival = time;
                    else departure = time;
                } else {
                    s.skip(wire);
                }
            }
            long time = arrival != 0 ? arrival : departure;
            if (stopId.isEmpty() || time == 0) continue;
            Update u = new Update();
            u.tripId = tripId;
            u.routeId = routeId;
            u.stopId = stopId;
            u.time = time;
            out.add(u);
        }
    }

    private static long eventTime(Pb ev) {
        long time = 0;
        while (ev.more()) {
            int k = ev.key();
            if (k >>> 3 == 2 && (k & 7) == 0) time = ev.varint();
            else ev.skip(k & 7);
        }
        return time;
    }

    // ---- Static GTFS ---------------------------------------------------------------------------

    static Static readStatic(byte[] zip) throws IOException {
        Static st = new Static();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
        // Nothing from the archive is ever written to a file: three known tables are read into
        // memory by fixed name, and every other entry, whatever it is called, is passed over.
        int entries = 0, wanted = 0;
        long inflated = 0;
        try {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("too many archive entries");
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (!name.equals("trips.txt") && !name.equals("routes.txt") && !name.equals("stops.txt")) continue;
                if (++wanted > 3) throw new IOException("repeated archive entry");
                String text = read(in);
                inflated += text.length();
                if (inflated > MAX_TOTAL) throw new IOException("archive too large");
                List<Map<String, String>> rows = csv(text);
                for (Map<String, String> r : rows) {
                    if (name.equals("trips.txt")) {
                        put(st.tripRoute, r.get("trip_id"), r.get("route_id"));
                    } else if (name.equals("routes.txt")) {
                        put(st.routeLong, r.get("route_id"), r.get("route_long_name"));
                        put(st.routeShort, r.get("route_id"), r.get("route_short_name"));
                    } else {
                        put(st.stopName, r.get("stop_id"), r.get("stop_name"));
                    }
                }
            }
        } finally {
            in.close();
        }
        return st;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (k != null && !k.isEmpty() && v != null) m.put(k, v.trim());
    }

    /**
     * A timetable file inflating past these is refused, counted as it inflates (the sizes an
     * archive declares are the sender's word), so a zip bomb cannot fill the memory.
     */
    private static final int MAX_ENTRY = 16 * 1024 * 1024, MAX_TOTAL = 24 * 1024 * 1024, MAX_ENTRIES = 64;

    private static String read(ZipInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY) throw new IOException("archive entry too large");
            out.write(buf, 0, n);
        }
        String s = out.toString("UTF-8");
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    /** More than any real timetable table has: past these, cells are dropped or the file refused. */
    private static final int MAX_ROWS = 200000, MAX_CELLS = 48, MAX_CELL = 1024;

    /** RFC 4180 CSV with a header row, as GTFS uses it. */
    static List<Map<String, String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        if (cell.length() < MAX_CELL) cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else if (cell.length() < MAX_CELL) {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                if (row.size() < MAX_CELLS) row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (row.size() < MAX_CELLS) row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("too many rows");
                row = new ArrayList<>();
            } else if (cell.length() < MAX_CELL) {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) return out;
        List<String> head = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            if (cells.size() == 1 && cells.get(0).trim().isEmpty()) continue;
            Map<String, String> m = new HashMap<>();
            for (int c = 0; c < head.size() && c < cells.size(); c++) m.put(head.get(c).trim(), cells.get(c));
            out.add(m);
        }
        return out;
    }
}
