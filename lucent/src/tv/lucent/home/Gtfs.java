package tv.lucent.home;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        /** What the maps hold, in bytes as MAX_HELD counts them. */
        private long held;

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
        private final int start, end;

        Pb(byte[] b) {
            this(b, 0, b.length);
        }

        Pb(byte[] b, int off, int len) {
            this.b = b;
            this.pos = off;
            this.start = off;
            this.end = off + len;
        }

        boolean more() {
            return pos < end;
        }

        /** Back to the first field, for a second pass. */
        void rewind() {
            pos = start;
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

        /** Which of {@code want} the next length-delimited field holds, compared as bytes, or -1. */
        int which(byte[][] want) {
            int len = length();
            int at = pos;
            pos += len;
            for (int w = 0; w < want.length; w++) {
                byte[] x = want[w];
                if (x.length != len) continue;
                int i = 0;
                while (i < len && b[at + i] == x[i]) i++;
                if (i == len) return w;
            }
            return -1;
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

    /**
     * The most predictions kept from one feed. MIT's whole feed holds a few dozen, a handful of
     * them at the stops Lucent shows.
     */
    static final int MAX_UPDATES = 2000;

    /** Every stop-time prediction in a TripUpdates FeedMessage, up to MAX_UPDATES. */
    static List<Update> tripUpdates(byte[] feed) {
        return tripUpdates(feed, null);
    }

    /**
     * The stop-time predictions in a TripUpdates FeedMessage at {@code stops} (null for every
     * stop), up to MAX_UPDATES. Other stops are passed over without building anything, so a feed
     * naming hundreds of thousands of them costs no more to keep than one naming a few.
     */
    static List<Update> tripUpdates(byte[] feed, Set<String> stops) {
        Stops want = stops == null ? null : new Stops(stops);
        List<Update> out = new ArrayList<>();
        Pb msg = new Pb(feed);
        while (msg.more() && out.size() < MAX_UPDATES) {
            int k = msg.key();
            if (k >>> 3 == 2 && (k & 7) == 2) entity(msg.message(), want, out);
            else msg.skip(k & 7);
        }
        return out;
    }

    /** The stops asked for, also as UTF-8, so a stop id is matched without making a String. */
    private static final class Stops {
        final String[] ids;
        final byte[][] utf8;

        Stops(Set<String> stops) {
            ids = stops.toArray(new String[0]);
            utf8 = new byte[ids.length][];
            for (int i = 0; i < ids.length; i++) utf8[i] = ids[i].getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void entity(Pb e, Stops want, List<Update> out) {
        boolean deleted = false;
        Pb trip = null;
        while (e.more()) {
            int k = e.key();
            int field = k >>> 3, wire = k & 7;
            if (field == 2 && wire == 0) deleted = e.varint() != 0;
            else if (field == 3 && wire == 2) trip = e.message();
            else e.skip(wire);
        }
        if (!deleted && trip != null) tripUpdate(trip, want, out);
    }

    private static void tripUpdate(Pb t, Stops want, List<Update> out) {
        // The trip may come after its stops, so a first pass finds it and a second reads the
        // stops, rather than holding every stop until the trip is known.
        String tripId = "", routeId = "";
        while (t.more()) {
            int k = t.key();
            if (k >>> 3 == 1 && (k & 7) == 2) {
                Pb d = t.message();
                while (d.more()) {
                    int dk = d.key();
                    if (dk >>> 3 == 1 && (dk & 7) == 2) tripId = d.string();
                    else if (dk >>> 3 == 5 && (dk & 7) == 2) routeId = d.string();
                    else d.skip(dk & 7);
                }
            } else {
                t.skip(k & 7);
            }
        }
        t.rewind();
        while (t.more() && out.size() < MAX_UPDATES) {
            int k = t.key();
            if (k >>> 3 == 2 && (k & 7) == 2) stopTime(t.message(), tripId, routeId, want, out);
            else t.skip(k & 7);
        }
    }

    /** One StopTimeUpdate, kept when it is at a stop asked for and has a time. */
    private static void stopTime(Pb s, String tripId, String routeId, Stops want, List<Update> out) {
        // The stop first, so another stop's times are never read.
        String stopId = "";
        while (s.more()) {
            int k = s.key();
            if (k >>> 3 != 4 || (k & 7) != 2) {
                s.skip(k & 7);
            } else if (want == null) {
                stopId = s.string();
            } else {
                int w = s.which(want.utf8);
                stopId = w < 0 ? "" : want.ids[w];
            }
        }
        if (stopId.isEmpty()) return;
        s.rewind();
        long arrival = 0, departure = 0;
        while (s.more()) {
            int k = s.key();
            int field = k >>> 3, wire = k & 7;
            if ((field == 2 || field == 3) && wire == 2) {
                long time = eventTime(s.message());
                if (field == 2) arrival = time;
                else departure = time;
            } else {
                s.skip(wire);
            }
        }
        long time = arrival != 0 ? arrival : departure;
        if (time == 0) return;
        Update u = new Update();
        u.tripId = tripId;
        u.routeId = routeId;
        u.stopId = stopId;
        u.time = time;
        out.add(u);
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
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip));
        Inflating in = new Inflating(zin);
        // Nothing from the archive is ever written to a file: three known tables are read by
        // fixed name, and every other entry, whatever it is called, is passed over. Each table is
        // read as it inflates and only its wanted columns are kept, so its width and length cost
        // time but no memory.
        int entries = 0, wanted = 0;
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("too many archive entries");
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (!name.equals("trips.txt") && !name.equals("routes.txt") && !name.equals("stops.txt")) continue;
                if (++wanted > 3) throw new IOException("repeated archive entry");
                in.entry = 0;
                Reader text = new InputStreamReader(in, StandardCharsets.UTF_8);
                if (name.equals("trips.txt")) {
                    Csv t = new Csv(text, "trip_id", "route_id");
                    while (t.next()) put(st, st.tripRoute, t.cell(0), t.cell(1));
                } else if (name.equals("routes.txt")) {
                    Csv t = new Csv(text, "route_id", "route_long_name", "route_short_name");
                    while (t.next()) {
                        put(st, st.routeLong, t.cell(0), t.cell(1));
                        put(st, st.routeShort, t.cell(0), t.cell(2));
                    }
                } else {
                    Csv t = new Csv(text, "stop_id", "stop_name");
                    while (t.next()) put(st, st.stopName, t.cell(0), t.cell(1));
                }
            }
        } finally {
            zin.close();
        }
        return st;
    }

    /** Keeps {@code k -> v}, counted against MAX_HELD. */
    private static void put(Static st, Map<String, String> m, String k, String v) throws IOException {
        if (k == null || k.isEmpty() || v == null) return;
        v = v.trim();
        st.held += 128 + 2L * (k.length() + v.length());
        if (st.held > MAX_HELD) throw new IOException("timetable too large");
        m.put(k, v);
    }

    /**
     * A timetable file inflating past these is refused, counted as it inflates (the sizes an
     * archive declares are the sender's word), so a zip bomb cannot fill the memory.
     */
    private static final int MAX_ENTRY = 16 * 1024 * 1024, MAX_TOTAL = 24 * 1024 * 1024, MAX_ENTRIES = 64;

    /**
     * What the kept tables may come to, estimated as 128 bytes an entry for the map node and its
     * two strings, plus two bytes a character. MIT's tables come to about 60 KB.
     */
    private static final int MAX_HELD = 8 * 1024 * 1024;

    /** The archive as it inflates, refused past the entry and archive limits. */
    private static final class Inflating extends FilterInputStream {
        long entry, total;

        Inflating(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) count(1);
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            entry += n;
            total += n;
            if (entry > MAX_ENTRY) throw new IOException("archive entry too large");
            if (total > MAX_TOTAL) throw new IOException("archive too large");
        }
    }

    /** More than any real timetable table has: past these, cells are dropped or the file refused. */
    private static final int MAX_ROWS = 200000, MAX_CELLS = 48, MAX_CELL = 1024;

    /**
     * RFC 4180 CSV with a header row, as GTFS uses it, read one row at a time. Only the columns
     * named when it is opened are kept, and a row's cells are replaced by the next row's.
     */
    static final class Csv {
        private final Reader in;
        private final char[] buf = new char[8192];
        private int pos, len, rows;
        private boolean end;
        /** Which wanted column each of the first MAX_CELLS columns is, or -1. */
        private final int[] slot = new int[MAX_CELLS];
        private final String[] cells;
        private final StringBuilder cell = new StringBuilder();

        /** Reads the header, after which {@link #next} reads each row's {@code columns}. */
        Csv(Reader in, String... columns) throws IOException {
            this.in = in;
            cells = new String[columns.length];
            if (peek() == '\uFEFF') read();
            int[] every = new int[MAX_CELLS];
            for (int c = 0; c < MAX_CELLS; c++) every[c] = c;
            String[] head = new String[MAX_CELLS];
            record(every, head);
            Arrays.fill(slot, -1);
            for (int c = 0; c < MAX_CELLS; c++) {
                if (head[c] == null) continue;
                String name = head[c].trim();
                for (int w = 0; w < columns.length; w++) if (columns[w].equals(name)) slot[c] = w;
            }
        }

        /** The next row; false at the end of the table. */
        boolean next() throws IOException {
            if (!record(slot, cells)) return false;
            if (++rows > MAX_ROWS) throw new IllegalArgumentException("too many rows");
            return true;
        }

        /** This row's cell in the {@code i}th column asked for, or null when the row stops short. */
        String cell(int i) {
            return cells[i];
        }

        /** One record: column c goes to {@code into[to[c]]} unless that is -1. False at the end. */
        private boolean record(int[] to, String[] into) throws IOException {
            int ch = read();
            if (ch < 0) return false;
            Arrays.fill(into, null);
            cell.setLength(0);
            int col = 0;
            boolean quoted = false;
            for (; ch >= 0; ch = read()) {
                char c = (char) ch;
                if (quoted) {
                    if (c != '"') {
                        add(to, col, c);
                    } else if (peek() == '"') {
                        read();
                        add(to, col, '"');
                    } else {
                        quoted = false;
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    keep(to, into, col++);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && peek() == '\n') read();
                    break;
                } else {
                    add(to, col, c);
                }
            }
            keep(to, into, col);
            return true;
        }

        private void add(int[] to, int col, char c) {
            if (col < MAX_CELLS && to[col] >= 0 && cell.length() < MAX_CELL) cell.append(c);
        }

        private void keep(int[] to, String[] into, int col) {
            if (col < MAX_CELLS && to[col] >= 0) into[to[col]] = cell.toString();
            cell.setLength(0);
        }

        private int read() throws IOException {
            if (pos == len && !fill()) return -1;
            return buf[pos++];
        }

        private int peek() throws IOException {
            if (pos == len && !fill()) return -1;
            return buf[pos];
        }

        private boolean fill() throws IOException {
            if (end) return false;
            int n = in.read(buf, 0, buf.length);
            if (n <= 0) {
                end = true;
                return false;
            }
            pos = 0;
            len = n;
            return true;
        }
    }
}
