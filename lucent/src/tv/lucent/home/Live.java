package tv.lucent.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the live cards current while the home screen is on screen: Bluebikes every 30 s, the
 * shuttles every 20 s, aircraft every 15 s. Nothing is fetched while an app is in front (bike
 * finder has its own loop). Static data (the station list, MIT's GTFS zip) is kept on disk and
 * refreshed daily or weekly.
 */
final class Live {
    interface Sink {
        void bikes(Model.Bikes b);

        void transit(Model.Transit t);

        void sky(Model.Sky s);
    }

    private static final long BIKES_MS = 30000, TRANSIT_MS = 20000, SKY_MS = 15000;
    private static final long INFO_MAX_AGE = 24 * 3600000L, GTFS_MAX_AGE = 7 * 24 * 3600000L;
    private static final int TIMEOUT = 8000;

    private final Context context;
    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /** Aircraft lookups (several hosts, slower) never hold up the bikes or the shuttles. */
    private final ExecutorService air = Executors.newSingleThreadExecutor();
    private boolean on;
    private volatile String stationChoice = "auto";
    private volatile double skyLat = Gbfs.WESTGATE_LAT, skyLon = Gbfs.WESTGATE_LON;
    private volatile double queryLat = coarse(Gbfs.WESTGATE_LAT), queryLon = coarse(Gbfs.WESTGATE_LON);
    private volatile boolean skyOn = true;
    /** One fetch per loop at a time: on a dead network, ticks are skipped instead of queued. */
    private final AtomicBoolean bikesBusy = new AtomicBoolean(), transitBusy = new AtomicBoolean(), skyBusy = new AtomicBoolean();

    // Touched only on the network thread.
    private List<Model.Station> allStations;
    private long allStationsAt;
    private Gtfs.Static mitStatic;
    private long mitStaticAt;
    private List<List<Long>> timetable;
    private String timetableDay = "";
    private volatile boolean clock24;
    private final Map<String, String[]> routes = new HashMap<>();
    private long routesAt;
    private final Map<String, String[]> airports = new HashMap<>();

    private final Runnable bikesLoop = new Runnable() {
        @Override
        public void run() {
            if (bikesBusy.compareAndSet(false, true)) {
                submit(net, bikesBusy, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Model.Bikes b = safely(BIKES);
                            deliver(new Runnable() {
                                @Override
                                public void run() {
                                    if (b != null) sink.bikes(b);
                                }
                            });
                        } finally {
                            bikesBusy.set(false);
                        }
                    }
                });
            }
            if (on) ui.postDelayed(this, BIKES_MS);
        }
    };

    private final Runnable transitLoop = new Runnable() {
        @Override
        public void run() {
            if (transitBusy.compareAndSet(false, true)) {
                submit(net, transitBusy, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Model.Transit t = safely(TRANSIT);
                            deliver(new Runnable() {
                                @Override
                                public void run() {
                                    if (t != null) sink.transit(t);
                                }
                            });
                        } finally {
                            transitBusy.set(false);
                        }
                    }
                });
            }
            if (on) ui.postDelayed(this, TRANSIT_MS);
        }
    };

    private final Runnable skyLoop = new Runnable() {
        @Override
        public void run() {
            if (skyOn && skyBusy.compareAndSet(false, true)) {
                submit(air, skyBusy, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Model.Sky s = safely(SKY);
                            deliver(new Runnable() {
                                @Override
                                public void run() {
                                    if (s != null) sink.sky(s);
                                }
                            });
                        } finally {
                            skyBusy.set(false);
                        }
                    }
                });
            }
            if (on) ui.postDelayed(this, SKY_MS);
        }
    };

    Live(Context c, Sink sink) {
        this.context = c.getApplicationContext();
        this.sink = sink;
    }

    void setStation(String choice) {
        stationChoice = choice == null || choice.isEmpty() ? "auto" : choice;
    }

    void setClock24(boolean on) {
        clock24 = on;
    }

    /**
     * The aircraft query names a point, and adsb.lol is a third party, so the point sent is
     * snapped to a 0.05 degree grid (about 5 km): it says "this part of Greater Boston", never
     * which street. The 25 nautical mile circle around it still covers home many times over, and
     * which plane is nearest, and how far, is worked out on the TV from the exact point.
     */
    void setSky(boolean on, double lat, double lon) {
        skyOn = on;
        skyLat = lat;
        skyLon = lon;
        queryLat = coarse(lat);
        queryLon = coarse(lon);
    }

    static double coarse(double deg) {
        return Math.round(deg * 20) / 20.0;
    }

    void resume() {
        if (on) return;
        on = true;
        ui.post(bikesLoop);
        ui.postDelayed(transitLoop, 300);
        ui.postDelayed(skyLoop, 600);
    }

    void pause() {
        on = false;
        ui.removeCallbacks(bikesLoop);
        ui.removeCallbacks(transitLoop);
        ui.removeCallbacks(skyLoop);
    }

    /** A refresh now, for example after the station choice changed. */
    void refreshBikes() {
        ui.removeCallbacks(bikesLoop);
        ui.post(bikesLoop);
    }

    void shutdown() {
        pause();
        net.shutdownNow();
        air.shutdownNow();
    }

    private static final int BIKES = 0, TRANSIT = 1, SKY = 2;

    /**
     * One fetch, never more than one tick's loss: a feed answering with something no parser
     * expected, deep enough or big enough to exhaust the stack or the heap, skips this tick and
     * leaves the last good data on screen. The home screen must never crash over a feed.
     */
    @SuppressWarnings("unchecked")
    private <T> T safely(int which) {
        try {
            switch (which) {
                case BIKES:
                    return (T) fetchBikes();
                case TRANSIT:
                    return (T) fetchTransit();
                default:
                    return (T) fetchSky();
            }
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            return null;
        }
    }

    private static void submit(ExecutorService e, AtomicBoolean busy, Runnable r) {
        try {
            e.execute(r);
        } catch (RuntimeException rejected) {
            // shut down
            busy.set(false);
        }
    }

    private void deliver(Runnable r) {
        ui.post(r);
    }

    // ---- Bluebikes ---------------------------------------------------------------------------

    private Model.Bikes fetchBikes() {
        long now = System.currentTimeMillis();
        if (allStations == null || now - allStationsAt > INFO_MAX_AGE) {
            String info = cachedText("gbfs_info.json", Gbfs.INFO, INFO_MAX_AGE);
            if (info != null) {
                try {
                    allStations = Gbfs.stations(info, Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
                    allStationsAt = now;
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                    new File(context.getFilesDir(), "gbfs_info.json").delete();
                }
            }
        }
        if (allStations == null || allStations.isEmpty()) return null;
        String choice = stationChoice;
        String main = "auto".equals(choice) ? Gbfs.westgate(allStations) : choice;
        if (Gbfs.find(allStations, main) == null) main = Gbfs.westgate(allStations);
        List<Model.Station> follow = Gbfs.follow(allStations, main, 3);
        String status = Net.text(Gbfs.STATUS, TIMEOUT, true, Net.LIMIT_FEED);
        if (status == null) return null;
        Model.Bikes b = new Model.Bikes();
        try {
            if (Gbfs.status(status, follow) == 0) return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
        b.stations.addAll(follow);
        b.label = "auto".equals(choice) ? "Westgate" : Gbfs.shortName(follow.get(0), false);
        for (int i = 0; i < allStations.size() && i < 8; i++) b.nearby.add(Gbfs.copy(allStations.get(i)));
        b.fetched = now;
        b.ok = true;
        return b;
    }

    // ---- Shuttles ----------------------------------------------------------------------------

    private Model.Transit fetchTransit() {
        long now = System.currentTimeMillis();
        Model.Transit t = new Model.Transit();
        Model.Line tech = Transit.tech(), tech2 = Transit.tech2(), safe = Transit.saferide();
        Model.Line assembly = Transit.toAssembly(), ruggles = Transit.toRuggles();
        boolean any = false;
        if (mitStatic == null || now - mitStaticAt > GTFS_MAX_AGE) {
            byte[] zip = cachedBytes("mit_gtfs.zip", Transit.MIT_STATIC, GTFS_MAX_AGE, Net.LIMIT_ZIP);
            if (zip != null) {
                try {
                    mitStatic = Gtfs.readStatic(zip);
                    mitStaticAt = now;
                } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
                    // A copy that cannot be read is dropped, never retried from disk.
                    new File(context.getFilesDir(), "mit_gtfs.zip").delete();
                }
            }
        }
        byte[] feed = Net.get(Transit.MIT_TRIPS, TIMEOUT, false, Net.LIMIT_FEED);
        if (feed != null) {
            try {
                Transit.mit(Gtfs.tripUpdates(feed), mitStatic, now, tech, tech2, safe);
                any = true;
            } catch (RuntimeException ignored) {
                // a bad feed shows as no buses
            }
        }
        // The MBTA answers "not modified" when nothing moved, which also spares its rate limit.
        String predictions = Net.text(Transit.MBTA_PREDICTIONS, TIMEOUT, true, Net.LIMIT_SMALL);
        if (predictions != null) {
            try {
                Transit.mbta(predictions, now, assembly, ruggles);
                any = true;
            } catch (RuntimeException ignored) {
                // a bad answer shows as no buses
            }
        }
        String day = Transit.serviceDate(now);
        if (!day.equals(timetableDay)) {
            String sched = Net.text(Transit.mbtaSchedule(day), TIMEOUT, false, Net.LIMIT_FEED);
            if (sched != null) {
                try {
                    timetable = Transit.mbtaTimetable(sched);
                    timetableDay = day;
                } catch (RuntimeException ignored) {
                    // try again next time
                }
            }
        }
        if (!any) return null;
        t.lines.add(tech);
        t.lines.add(tech2);
        t.lines.add(safe);
        t.lines.add(assembly);
        t.lines.add(ruggles);
        Transit.idleNotes(t, now);
        boolean h24 = clock24;
        if (assembly.minutes.isEmpty()) assembly.idle = timetable == null ? "No bus tracked" : Transit.nextNote(timetable.get(0), now, h24);
        if (ruggles.minutes.isEmpty()) ruggles.idle = timetable == null ? "No bus tracked" : Transit.nextNote(timetable.get(1), now, h24);
        // A feed that did not answer is said so, rather than shown as a quiet line.
        if (feed == null) {
            tech.idle = "MIT feed not answering";
            tech2.idle = tech.idle;
            safe.idle = tech.idle;
        }
        if (predictions == null) {
            assembly.idle = "MBTA not answering";
            ruggles.idle = assembly.idle;
        }
        t.fetched = now;
        t.ok = true;
        return t;
    }

    // ---- Aircraft ----------------------------------------------------------------------------

    private Model.Sky fetchSky() {
        long now = System.currentTimeMillis();
        if (now - routesAt > 3 * 3600000L || routes.size() > 3000 || airports.size() > 500) {
            routes.clear();
            airports.clear();
            routesAt = now;
        }
        String json = Net.text(String.format(Locale.US, Sky.POINT, queryLat, queryLon, 25), TIMEOUT, false, Net.LIMIT_SMALL);
        if (json == null) return null;
        Model.Sky s;
        try {
            s = Sky.aircraft(json, skyLat, skyLon);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int enriched = 0;
        for (Model.Flight f : s.flights) {
            if (enriched++ >= 4 || f.callsign.isEmpty()) continue;
            String[] r = routes.get(f.callsign);
            if (r == null) {
                // A miss (hexdb knows about half of all callsigns) is remembered too, so an
                // unknown flight is not asked about every 15 seconds. A failed request is not,
                // and the cache resets every few hours.
                String rj = Sky.safeCallsign(f.callsign) ? Net.text(Sky.ROUTE + f.callsign, 5000, false, Net.LIMIT_TINY) : null;
                try {
                    r = rj == null ? null : Sky.route(rj);
                } catch (IllegalArgumentException e) {
                    r = null;
                }
                if (rj != null) routes.put(f.callsign, r == null ? new String[0] : r);
            }
            if (r == null || r.length < 2) continue;
            String[] a = airport(r[0]), b = airport(r[1]);
            f.from = a[0];
            f.fromCity = a[1];
            f.to = b[0];
            f.toCity = b[1];
        }
        s.fetched = System.currentTimeMillis();
        return s;
    }

    private String[] airport(String icao) {
        String[] hit = airports.get(icao);
        if (hit != null) return hit;
        String j = Sky.safeCode(icao) ? Net.text(Sky.AIRPORT + icao, 5000, false, Net.LIMIT_TINY) : null;
        String[] out;
        try {
            out = j == null ? new String[] {icao, ""} : Sky.airport(j, icao);
        } catch (IllegalArgumentException e) {
            out = new String[] {icao, ""};
        }
        if (j != null) airports.put(icao, out);
        return out;
    }

    // ---- Disk cache --------------------------------------------------------------------------

    private String cachedText(String name, String url, long maxAge) {
        byte[] b = cachedBytes(name, url, maxAge, Net.LIMIT_FEED);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** A fresh copy from the network when the saved one is old, else the saved one. */
    private byte[] cachedBytes(String name, String url, long maxAge, int maxBytes) {
        File f = new File(context.getFilesDir(), name);
        boolean fresh = f.isFile() && System.currentTimeMillis() - f.lastModified() < maxAge;
        if (!fresh) {
            byte[] b = Net.get(url, 15000, false, maxBytes);
            if (b != null && b.length > 0) {
                write(f, b);
                return b;
            }
        }
        return f.isFile() ? readFile(f) : null;
    }

    private static void write(File f, byte[] b) {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(b);
            out.close();
            out = null;
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (IOException e) {
            tmp.delete();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static byte[] readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }
}
