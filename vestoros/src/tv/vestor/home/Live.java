package tv.vestor.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the live data current, as fast as each service publishes it and never faster than its
 * limits allow ({@link Pace} has the budgets, README the numbers):
 *
 * - Bluebikes every 10 s. The feed changes once a minute and an unchanged answer is a free 304,
 *   so a new count shows within about 10 s of Bluebikes publishing it.
 * - MIT shuttles every 10 s (Passio's trip updates move every 12 to 16 s).
 * - The 85 every 10 s without a key. With a free MBTA key it is one live stream instead, and a
 *   change shows the moment the MBTA has it.
 * - Planes every 5 s, from whichever ADS-B service answers first.
 * - Weather every 15 minutes (the model's own step), air quality hourly, National Weather Service
 *   alerts every 2 minutes, shared calendars every 10 minutes.
 *
 * It runs while the home screen is in front, and for a while after the control center on a
 * phone asks for fresh data. Bike finder has its own loop. Static data (the station list, MIT's
 * GTFS zip) is kept on disk and refreshed daily or weekly.
 */
final class Live {
    interface Sink {
        void bikes(Model.Bikes b);

        void transit(Model.Transit t);

        void sky(Model.Sky s);

        /** A fresh Open-Meteo answer, parsed by the sink. */
        void weather(String json, long at);

        void alerts(List<Model.Alert> alerts);

        void air(Model.Air air);

        void calendar(List<Ical.Event> events, long at);
    }

    static final long BIKES_MS = 10000, TRANSIT_MS = 10000, SKY_MS = 5000;
    static final long WEATHER_MS = 15 * 60000L, AIR_MS = 60 * 60000L, ALERTS_MS = 2 * 60000L, CAL_MS = 10 * 60000L;
    private static final long INFO_MAX_AGE = 24 * 3600000L, GTFS_MAX_AGE = 7 * 24 * 3600000L;
    /** After a copy that would not parse, the next download waits this long. */
    private static final long INFO_RETRY = 30 * 60000L, GTFS_RETRY = 6 * 3600000L;
    /** The stops {@link Transit#mit} reads, so the realtime feed keeps no other stop's predictions. */
    private static final Set<String> MIT_STOPS =
            new HashSet<>(java.util.Arrays.asList(Transit.STOP_GRAD_JUNCTION_W, Transit.STOP_W98_VASSAR, Transit.STOP_TANG_WESTGATE));
    private static final long ROUTE_HIT_MS = 12 * 3600000L, ROUTE_MISS_MS = 2 * 3600000L;
    private static final int TIMEOUT = 8000;

    private final Context context;
    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /** Aircraft lookups (several hosts, slower) never hold up the bikes or the shuttles. */
    private final ExecutorService air = Executors.newSingleThreadExecutor();
    /** Weather, alerts and calendars: slow, rare, and never in the way of the fast loops. */
    private final ExecutorService slow = Executors.newSingleThreadExecutor();
    private boolean on;
    private long remoteUntil;
    private volatile String stationChoice = "auto", station2Choice = "auto";
    private volatile double skyLat = Gbfs.WESTGATE_LAT, skyLon = Gbfs.WESTGATE_LON;
    private volatile double queryLat = coarse(Gbfs.WESTGATE_LAT), queryLon = coarse(Gbfs.WESTGATE_LON);
    private volatile double placeLat = Gbfs.WESTGATE_LAT, placeLon = Gbfs.WESTGATE_LON;
    private volatile boolean skyOn = true;
    private volatile String mbtaKey = "";
    private volatile List<String> calendars = new ArrayList<>();
    /** One fetch per loop at a time: on a dead network, ticks are skipped instead of queued. */
    private final AtomicBoolean bikesBusy = new AtomicBoolean(), transitBusy = new AtomicBoolean(),
            skyBusy = new AtomicBoolean(), slowBusy = new AtomicBoolean();

    // Touched only on the network thread.
    private List<Model.Station> allStations;
    private long allStationsAt, allStationsFailed;
    private Gtfs.Static mitStatic;
    private long mitStaticAt, mitStaticFailed;
    private List<Gtfs.Update> mitUpdates;
    private long mitAt;
    private List<List<Long>> timetable;
    private String timetableDay = "";
    private volatile boolean clock24;
    private final Mbta mbta = new Mbta();
    private volatile long mbtaPolled;
    private volatile boolean mbtaStreaming;
    private Stream mbtaStream;
    // Touched only on the aircraft thread.
    private final Map<String, String[]> routes = new HashMap<>();
    private final Map<String, Long> routeAt = new HashMap<>();
    private final Map<String, String[]> airports = new HashMap<>();
    // Touched only on the slow thread.
    private long weatherAt, airAt, alertsAt, calAt, calWindow;
    private final Map<String, String> calKeys = new HashMap<>();
    private final Map<String, List<Ical.Event>> calEvents = new HashMap<>();

    private final Runnable bikesLoop = loop(BIKES_MS, new Runnable() {
        @Override
        public void run() {
            if (!bikesBusy.compareAndSet(false, true)) return;
            submit(net, bikesBusy, new Runnable() {
                @Override
                public void run() {
                    try {
                        final Model.Bikes b = safely(BIKES);
                        if (b != null) deliver(new Runnable() {
                            @Override
                            public void run() {
                                sink.bikes(b);
                            }
                        });
                    } finally {
                        bikesBusy.set(false);
                    }
                }
            });
        }
    });

    private final Runnable transitLoop = loop(TRANSIT_MS, new Runnable() {
        @Override
        public void run() {
            if (!transitBusy.compareAndSet(false, true)) return;
            submit(net, transitBusy, new Runnable() {
                @Override
                public void run() {
                    try {
                        final Model.Transit t = safely(TRANSIT);
                        if (t != null) deliver(new Runnable() {
                            @Override
                            public void run() {
                                sink.transit(t);
                            }
                        });
                    } finally {
                        transitBusy.set(false);
                    }
                }
            });
        }
    });

    private final Runnable skyLoop = loop(SKY_MS, new Runnable() {
        @Override
        public void run() {
            if (!skyOn || !skyBusy.compareAndSet(false, true)) return;
            submit(air, skyBusy, new Runnable() {
                @Override
                public void run() {
                    try {
                        final Model.Sky s = safely(SKY);
                        if (s != null) deliver(new Runnable() {
                            @Override
                            public void run() {
                                sink.sky(s);
                            }
                        });
                    } finally {
                        skyBusy.set(false);
                    }
                }
            });
        }
    });

    /** Weather, air, alerts and calendars share one loop that wakes each minute and does what is due. */
    private final Runnable slowLoop = loop(60000L, new Runnable() {
        @Override
        public void run() {
            if (!slowBusy.compareAndSet(false, true)) return;
            submit(slow, slowBusy, new Runnable() {
                @Override
                public void run() {
                    try {
                        safely(SLOW);
                    } finally {
                        slowBusy.set(false);
                    }
                }
            });
        }
    });

    /** A loop that runs {@code body} now and again every {@code every} ms while Live is wanted. */
    private Runnable loop(final long every, final Runnable body) {
        return new Runnable() {
            @Override
            public void run() {
                if (!wanted()) return;
                body.run();
                ui.removeCallbacks(this);
                ui.postDelayed(this, every);
            }
        };
    }

    Live(Context c, Sink sink) {
        this.context = c.getApplicationContext();
        this.sink = sink;
    }

    void setStation(String choice) {
        stationChoice = choice == null || choice.isEmpty() ? "auto" : choice;
    }

    /** The second watched dock: "auto" (Erie St), "none", or a station id. */
    void setStation2(String choice) {
        station2Choice = choice == null || choice.isEmpty() ? "auto" : choice;
    }

    void setClock24(boolean on) {
        clock24 = on;
    }

    /** Where the weather is for. Sent rounded (see {@link Weather#url}). */
    void setPlace(double lat, double lon) {
        if (lat != placeLat || lon != placeLon) weatherAt = airAt = alertsAt = 0;
        placeLat = lat;
        placeLon = lon;
    }

    /** Shared calendar feeds (https URLs). A changed list is fetched on the next minute. */
    void setCalendars(List<String> urls) {
        List<String> clean = new ArrayList<>();
        for (String u : urls) if (Net.allowed(u) && clean.size() < 6) clean.add(u);
        if (!clean.equals(calendars)) calAt = 0;
        for (String old : calendars) if (!clean.contains(old)) Net.forget(old);
        calendars = clean;
    }

    /**
     * The MBTA key raises its limit from 20 to 1000 requests a minute and unlocks the live stream.
     * Empty means none.
     */
    void setMbtaKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.equals(mbtaKey)) return;
        mbtaKey = k;
        Pace.useMbtaKey(!k.isEmpty());
        stopStream();
        if (wanted()) startStream();
    }

    /**
     * The aircraft query names a point, and the ADS-B services are third parties, so the point
     * sent is snapped to a 0.05 degree grid (about 5 km): it says "this part of Greater Boston",
     * never which street. The 25 nautical mile circle around it still covers home many times over,
     * and which plane is nearest, and how far, is worked out on the TV from the exact point.
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

    private boolean wanted() {
        return on || System.currentTimeMillis() < remoteUntil;
    }

    /** The home screen came to the front. */
    void resume() {
        if (on) return;
        boolean was = wanted();
        on = true;
        if (!was) kickAll();
    }

    /** The home screen went behind an app. Loops stop at their next tick unless a phone wants data. */
    void pause() {
        on = false;
        if (!wanted()) stopStream();
    }

    /**
     * The control center asked for fresh data: keep the loops going for {@code forMs} more, even
     * while another app is in front.
     */
    void wake(long forMs) {
        boolean was = wanted();
        remoteUntil = Math.max(remoteUntil, System.currentTimeMillis() + forMs);
        if (!was) kickAll();
    }

    private void kickAll() {
        ui.removeCallbacks(bikesLoop);
        ui.removeCallbacks(transitLoop);
        ui.removeCallbacks(skyLoop);
        ui.removeCallbacks(slowLoop);
        ui.post(bikesLoop);
        ui.postDelayed(transitLoop, 300);
        ui.postDelayed(skyLoop, 600);
        ui.postDelayed(slowLoop, 900);
        startStream();
    }

    /** A refresh now, for example after the station choice changed. */
    void refreshBikes() {
        ui.removeCallbacks(bikesLoop);
        ui.post(bikesLoop);
    }

    /** Weather and calendars again now (a new place or a new calendar). */
    void refreshSlow() {
        weatherAt = airAt = alertsAt = calAt = 0;
        ui.removeCallbacks(slowLoop);
        ui.post(slowLoop);
    }

    void shutdown() {
        on = false;
        remoteUntil = 0;
        stopStream();
        ui.removeCallbacks(bikesLoop);
        ui.removeCallbacks(transitLoop);
        ui.removeCallbacks(skyLoop);
        ui.removeCallbacks(slowLoop);
        net.shutdownNow();
        air.shutdownNow();
        slow.shutdownNow();
    }

    private static final int BIKES = 0, TRANSIT = 1, SKY = 2, SLOW = 3;

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
                case SKY:
                    return (T) fetchSky();
                default:
                    fetchSlow();
                    return null;
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
        if ((allStations == null || now - allStationsAt > INFO_MAX_AGE) && waited(now, allStationsFailed, INFO_RETRY)) {
            String info = cachedText("gbfs_info.json", Gbfs.INFO, INFO_MAX_AGE);
            if (info != null) {
                List<Model.Station> got;
                try {
                    got = Gbfs.stations(info, Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                    got = null;
                }
                if (got != null && !got.isEmpty()) {
                    allStations = got;
                    allStationsAt = now;
                } else {
                    // A copy that cannot be read, or lists no station, is dropped and the next
                    // download waits: the last good list, if any, stays in use meanwhile.
                    new File(context.getFilesDir(), "gbfs_info.json").delete();
                    allStationsFailed = now;
                }
            }
        }
        if (allStations == null || allStations.isEmpty()) return null;
        String choice = stationChoice;
        String main = "auto".equals(choice) ? Gbfs.westgate(allStations) : choice;
        if (Gbfs.find(allStations, main) == null) main = Gbfs.westgate(allStations);
        String choice2 = station2Choice;
        String alt = "none".equals(choice2) ? null : "auto".equals(choice2) ? Gbfs.erie(allStations) : choice2;
        if (alt != null && (Gbfs.find(allStations, alt) == null || alt.equals(main))) alt = null;
        List<Model.Station> follow = Gbfs.follow(allStations, main, alt, 3);
        // Bike finder reads the same file every 5 s: within 2 s, its copy is used instead.
        String status = Net.text(Gbfs.STATUS, TIMEOUT, true, Net.LIMIT_FEED, null, 2000);
        if (status == null || !Gbfs.fresh(status)) return null;
        Model.Bikes b = new Model.Bikes();
        try {
            if (Gbfs.status(status, follow) == 0) return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
        b.stations.addAll(follow);
        b.label = "auto".equals(choice) ? "Westgate" : Gbfs.shortName(follow.get(0), false);
        if (alt != null && follow.size() > 1 && follow.get(1).id.equals(alt)) {
            b.altId = alt;
            b.altLabel = "auto".equals(choice2) ? "Erie St" : Gbfs.shortName(follow.get(1), false);
        }
        for (int i = 0; i < allStations.size() && i < 8; i++) b.nearby.add(Gbfs.copy(allStations.get(i)));
        b.published = Gbfs.lastUpdated(status) * 1000L;
        b.fetched = now;
        b.ok = true;
        return b;
    }

    // ---- Shuttles and the 85 -----------------------------------------------------------------

    private Model.Transit fetchTransit() {
        long now = System.currentTimeMillis();
        if ((mitStatic == null || now - mitStaticAt > GTFS_MAX_AGE) && waited(now, mitStaticFailed, GTFS_RETRY)) {
            byte[] zip = cachedBytes("mit_gtfs.zip", Transit.MIT_STATIC, GTFS_MAX_AGE, Net.LIMIT_ZIP);
            if (zip != null) {
                try {
                    mitStatic = Gtfs.readStatic(zip);
                    mitStaticAt = now;
                } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
                    // A copy that cannot be read is dropped, never retried from disk, and the next
                    // download waits hours: the last good timetable, if any, stays in use meanwhile.
                    new File(context.getFilesDir(), "mit_gtfs.zip").delete();
                    mitStaticFailed = now;
                }
            }
        }
        byte[] feed = Net.get(Transit.MIT_TRIPS, 10000, false, Net.LIMIT_FEED);
        if (feed != null) {
            try {
                mitUpdates = Gtfs.tripUpdates(feed, MIT_STOPS);
                mitAt = now;
            } catch (RuntimeException ignored) {
                // a bad feed keeps the last good one
            }
        }
        boolean polled = false;
        if (!mbtaStreaming) {
            // Polled only without the live stream. Unchanged answers are 304s, which the MBTA does
            // not count against its limit.
            String predictions = Net.text(Transit.MBTA_PREDICTIONS, TIMEOUT, true, Net.LIMIT_SMALL, keyHeader(), 0);
            if (predictions != null) {
                try {
                    mbta.document(predictions, now);
                    mbtaPolled = now;
                    polled = true;
                } catch (RuntimeException ignored) {
                    // a bad answer keeps the last good set
                }
            }
        }
        String day = Transit.serviceDate(now);
        if (!day.equals(timetableDay)) {
            String sched = Net.text(Transit.mbtaSchedule(day), TIMEOUT, false, Net.LIMIT_FEED, keyHeader(), 0);
            if (sched != null) {
                try {
                    timetable = Transit.mbtaTimetable(sched);
                    timetableDay = day;
                } catch (RuntimeException ignored) {
                    // try again next time
                }
            }
        }
        if (feed == null && !polled && !mbtaStreaming && mitUpdates == null) return null;
        return transitNow(now, feed != null, mbtaStreaming || polled);
    }

    /** The five lines as of {@code now}, from the latest MIT update and MBTA set. */
    private Model.Transit transitNow(long now, boolean mitOk, boolean mbtaOk) {
        Model.Transit t = new Model.Transit();
        Model.Line tech = Transit.tech(), tech2 = Transit.tech2(), safe = Transit.saferide(), grocery = Transit.grocery();
        Model.Line assembly = Transit.toAssembly(), ruggles = Transit.toRuggles();
        // Updates older than two minutes are not shown as live minutes: the bus has moved on.
        if (mitUpdates != null && now - mitAt < 120000L) Transit.mit(mitUpdates, mitStatic, now, tech, tech2, safe, grocery);
        long mbtaAge = now - Math.max(mbtaPolled, mbta.changed());
        if (mbtaStreaming || mbtaAge < 120000L) mbta.fill(now, assembly, ruggles);
        t.lines.add(tech);
        t.lines.add(tech2);
        t.lines.add(safe);
        t.lines.add(assembly);
        t.lines.add(ruggles);
        // The grocery shuttle only on its days (or whenever one is tracked), so it never clutters a weekday.
        if (Transit.groceryShown(grocery, now)) t.lines.add(grocery);
        Transit.idleNotes(t, now);
        boolean h24 = clock24;
        List<List<Long>> tt = timetable;
        if (assembly.minutes.isEmpty()) assembly.idle = tt == null ? "No bus tracked" : Transit.nextNote(tt.get(0), now, h24);
        if (ruggles.minutes.isEmpty()) ruggles.idle = tt == null ? "No bus tracked" : Transit.nextNote(tt.get(1), now, h24);
        // A feed that did not answer is said so, rather than shown as a quiet line.
        if (!mitOk && (mitUpdates == null || now - mitAt > 120000L)) {
            tech.idle = "MIT feed not answering";
            tech2.idle = tech.idle;
            safe.idle = tech.idle;
            grocery.idle = tech.idle;
        }
        if (!mbtaOk && mbtaAge > 120000L) {
            assembly.idle = "MBTA not answering";
            ruggles.idle = assembly.idle;
        }
        t.live = mbtaStreaming;
        t.fetched = now;
        t.ok = true;
        return t;
    }

    private String[] keyHeader() {
        String k = mbtaKey;
        return k.isEmpty() ? null : new String[] {"x-api-key", k};
    }

    private void startStream() {
        String k = mbtaKey;
        if (k.isEmpty() || mbtaStream != null) return;
        mbtaStream = new Stream(Transit.MBTA_PREDICTIONS, new String[] {"x-api-key", k}, Stream.SSE, 90000,
                new Stream.Handler() {
                    private long lastPush;

                    @Override
                    public void event(String type, String data) {
                        long now = System.currentTimeMillis();
                        if (!mbta.event(type, data, now)) return;
                        // Several events often arrive together: the screen hears at most 4 a second.
                        if (now - lastPush < 250) return;
                        lastPush = now;
                        pushTransit();
                    }

                    @Override
                    public void state(boolean open) {
                        mbtaStreaming = open;
                    }
                });
        mbtaStream.start();
    }

    private void stopStream() {
        Stream s = mbtaStream;
        mbtaStream = null;
        mbtaStreaming = false;
        if (s != null) s.stop();
    }

    /** Rebuilds the lines on the network thread after a live MBTA change. */
    private void pushTransit() {
        submit(net, new AtomicBoolean(), new Runnable() {
            @Override
            public void run() {
                try {
                    final Model.Transit t = transitNow(System.currentTimeMillis(), mitUpdates != null, true);
                    deliver(new Runnable() {
                        @Override
                        public void run() {
                            sink.transit(t);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // the next tick tries again
                }
            }
        });
    }

    // ---- Aircraft ----------------------------------------------------------------------------

    private Model.Sky fetchSky() {
        long now = System.currentTimeMillis();
        if (routes.size() > 3000 || airports.size() > 500) {
            routes.clear();
            routeAt.clear();
            airports.clear();
        }
        String json = null, source = "";
        for (int i = 0; i < Sky.POINTS.length && json == null; i++) {
            json = Net.text(String.format(Locale.US, Sky.POINTS[i], queryLat, queryLon, 25), TIMEOUT, false, Net.LIMIT_SMALL);
            source = Sky.POINT_NAMES[i];
        }
        if (json == null) return null;
        Model.Sky s;
        try {
            s = Sky.aircraft(json, skyLat, skyLon);
        } catch (IllegalArgumentException e) {
            return null;
        }
        s.source = source;
        int lookups = 0;
        for (Model.Flight f : s.flights) {
            if (f.callsign.isEmpty() || !Sky.safeCallsign(f.callsign)) continue;
            String[] r = routes.get(f.callsign);
            Long at = routeAt.get(f.callsign);
            boolean stale = at == null || now - at > (r != null && r.length > 0 ? ROUTE_HIT_MS : ROUTE_MISS_MS);
            if (stale && lookups < 3) {
                lookups++;
                String[] found = lookupRoute(f.callsign);
                // A miss is remembered too, so an unknown flight is not asked about every 5 s. A
                // failed request is not (null), and is tried again on a later tick.
                if (found != null) {
                    routes.put(f.callsign, found);
                    routeAt.put(f.callsign, now);
                    r = found;
                }
            }
            String[] shown = Sky.plausible(r == null || r.length < 4 ? null : r, f);
            if (shown == null) continue;
            f.from = shown[0];
            f.fromCity = shown[1];
            f.to = shown[2];
            f.toCity = shown[3];
        }
        s.fetched = System.currentTimeMillis();
        return s;
    }

    /** {from, fromCity, to, toCity, ...}, an empty array when no service knows the flight, null on failure. */
    private String[] lookupRoute(String callsign) {
        String j = Net.text(Sky.ADSBDB + callsign, 5000, false, Net.LIMIT_TINY);
        if (j != null) {
            try {
                String[] r = Sky.adsbdb(j);
                if (r != null) return r;
            } catch (IllegalArgumentException ignored) {
                // fall through to hexdb
            }
        }
        String rj = Net.text(Sky.ROUTE + callsign, 5000, false, Net.LIMIT_TINY);
        if (rj == null) return j == null ? null : new String[0];
        String[] codes;
        try {
            codes = Sky.route(rj);
        } catch (IllegalArgumentException e) {
            codes = null;
        }
        if (codes == null) return new String[0];
        String[] a = airport(codes[0]), b = airport(codes[1]);
        return new String[] {a[0], a[1], b[0], b[1], codes[0], codes[1]};
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

    // ---- Weather, air, alerts, calendars -------------------------------------------------------

    private void fetchSlow() {
        long now = System.currentTimeMillis();
        double lat = placeLat, lon = placeLon;
        if (now - weatherAt >= WEATHER_MS) {
            final String json = Net.text(Weather.url(lat, lon), TIMEOUT, false, Net.LIMIT_TINY);
            if (json != null) {
                weatherAt = now;
                final long at = now;
                deliver(new Runnable() {
                    @Override
                    public void run() {
                        sink.weather(json, at);
                    }
                });
            }
        }
        if (now - airAt >= AIR_MS) {
            String json = Net.text(Extras.airUrl(lat, lon), TIMEOUT, false, Net.LIMIT_TINY);
            if (json != null) {
                airAt = now;
                try {
                    final Model.Air a = Extras.air(json, now);
                    deliver(new Runnable() {
                        @Override
                        public void run() {
                            sink.air(a);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // next hour
                }
            }
        }
        if (now - alertsAt >= ALERTS_MS) {
            String json = Net.text(Extras.alertsUrl(lat, lon), TIMEOUT, false, Net.LIMIT_SMALL,
                    new String[] {"User-Agent", Extras.NWS_AGENT, "Accept", "application/geo+json"}, 0);
            if (json != null) {
                alertsAt = now;
                try {
                    final List<Model.Alert> list = Extras.alerts(json, now);
                    deliver(new Runnable() {
                        @Override
                        public void run() {
                            sink.alerts(list);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // next time
                }
            }
        }
        if (now - calAt >= CAL_MS) {
            calAt = now;
            fetchCalendars(now);
        }
    }

    private void fetchCalendars(long now) {
        List<String> urls = calendars;
        TimeZone zone = TimeZone.getDefault();
        long from = Sun.startOfDay(now, zone), to = from + 15 * 86400000L;
        // A new day moves the window, so every feed is expanded again.
        boolean changed = from != calWindow;
        calWindow = from;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            byte[] body = Net.get(url, 15000, false, Net.LIMIT_ICS, null, 0);
            if (body == null) continue;
            String key = Ical.changeKey(body);
            List<Ical.Event> old = calEvents.get(url);
            // Google sends no ETag and stamps every event with the time of the request, so a
            // hash of the feed without those stamps tells whether anything really changed. A new
            // day still needs the window moved.
            if (key.equals(calKeys.get(url)) && old != null && !changed) continue;
            try {
                List<Ical.Event> ev = Ical.occurrences(body, i, from, to, zone, 300);
                calEvents.put(url, ev);
                calKeys.put(url, key);
                changed = true;
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                // keep the last good copy
            }
        }
        for (Iterator<String> it = calEvents.keySet().iterator(); it.hasNext(); ) {
            String u = it.next();
            if (!urls.contains(u)) {
                it.remove();
                calKeys.remove(u);
                changed = true;
            }
        }
        if (!changed && !calEvents.isEmpty()) return;
        final List<Ical.Event> all = new ArrayList<>();
        for (List<Ical.Event> l : calEvents.values()) all.addAll(l);
        Collections.sort(all, Ical.ORDER);
        final long at = now;
        deliver(new Runnable() {
            @Override
            public void run() {
                sink.calendar(all, at);
            }
        });
    }

    // ---- Disk cache --------------------------------------------------------------------------

    /** Whether {@code wait} has passed since a failure at {@code failed}, or the clock went back past it. */
    private static boolean waited(long now, long failed, long wait) {
        return now - failed > wait || now < failed;
    }

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

    static void write(File f, byte[] b) {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(b);
            out.getFD().sync();
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

    static byte[] readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            long len = f.length();
            if (len > 64L * 1024 * 1024) return null;
            byte[] b = new byte[(int) len];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return off == b.length ? b : java.util.Arrays.copyOf(b, off);
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
