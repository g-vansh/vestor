package tv.lucent.home;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Lucent's centre, one per process: it owns the live data, the settings, notes, photos, the
 * shared list, pairing, Now Playing, the Alexa link and the control center's server, so the TV
 * screen ({@link Home}), the HOME guard and a phone all see and change the same things.
 *
 * The process lives as long as the accessibility service is bound, which is always while Lucent
 * has the HOME button, so the control center answers even while a film plays.
 *
 * Everything the screen draws is handed out on the main thread through {@link Listener}. The
 * server's worker threads read immutable snapshots and send changes through the main thread.
 */
final class Hub implements Live.Sink, Finder.Listener, Media.Sink, Console.Host, Alarm.Listener, Ntfy.Sink {
    interface Listener {
        /** Something changed. {@code what} is one of the constants below. Main thread. */
        void hub(int what, Object detail);
    }

    static final int BIKES = 1, TRANSIT = 2, SKY = 3, WEATHER = 4, ALERTS = 5, AIR = 6, CALENDAR = 7, NOTE = 8,
            HEARTS = 9, MODE = 10, PHOTOS = 11, SETTINGS = 12, FINDER = 13, MEDIA = 14, PAIR = 15, PLANS = 16, WIFI = 17,
            ALARM = 18, FRONT = 19, WALL = 20;

    private static Hub instance;

    static synchronized Hub get(Context c) {
        if (instance == null) instance = new Hub(c.getApplicationContext());
        return instance;
    }

    final Context app;
    final Prefs prefs;
    final Live live;
    final Keys keys;
    final Notes notes;
    final Plans plans;
    final Album album;
    final Media media;
    final Console console;
    private Ntfy ntfy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, byte[]> assets = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    volatile Model.Bikes bikes;
    volatile Model.Transit transit;
    volatile Model.Sky sky;
    volatile Model.Weather weather;
    volatile List<Model.Alert> alerts = new ArrayList<>();
    volatile Model.Air air;
    volatile List<Ical.Event> events = new ArrayList<>();
    volatile long eventsAt;
    volatile Model.Audio audio;
    volatile boolean night, front;
    volatile long nextChange, wifiUntil, heartsAt;
    private volatile String[] window;

    private Hub(Context c) {
        app = c;
        prefs = new Prefs(c);
        final File files = c.getFilesDir();
        keys = new Keys(store(new File(files, "devices.json")));
        notes = new Notes(new Notes.Store() {
            @Override
            public String load() {
                return text(new File(files, "notes.json"));
            }

            @Override
            public void save(String json) {
                Live.write(new File(files, "notes.json"), json.getBytes(StandardCharsets.UTF_8));
            }
        });
        plans = new Plans(new Plans.Store() {
            @Override
            public String load() {
                return text(new File(files, "plans.json"));
            }

            @Override
            public void save(String json) {
                Live.write(new File(files, "plans.json"), json.getBytes(StandardCharsets.UTF_8));
            }
        });
        album = new Album(c);
        live = new Live(c, this);
        media = new Media(c, this);
        console = new Console(this, keys);
        Finder.get().listen(this);
        Alarm.listen(this);
        applySettings();
        String saved = prefs.get(Prefs.WEATHER_JSON, null);
        if (saved != null) weather(saved, prefs.getLong(Prefs.WEATHER_AT), false);
        console.setAccess(accessMode());
        media.start();
        startAlexa();
        mode();
        importShared();
        main.postDelayed(tick, 30000);
    }

    /** Pictures pushed into the shared folder become album photos, through the sandbox. */
    void importShared() {
        runInBackground(new Runnable() {
            @Override
            public void run() {
                if (album.importShared(prefs.media()) > 0) post(PHOTOS, null);
            }
        });
    }

    private static Keys.Store store(final File f) {
        return new Keys.Store() {
            @Override
            public String load() {
                return text(f);
            }

            @Override
            public void save(String json) {
                Live.write(f, json.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static String text(File f) {
        byte[] b = Live.readFile(f);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Every 30 s: the server's addresses, day and night, the alarm's limit, the Wi-Fi card. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            console.refresh();
            mode();
            if (wifiUntil > 0 && System.currentTimeMillis() > wifiUntil) {
                wifiUntil = 0;
                post(WIFI, null);
            }
            main.postDelayed(this, 30000);
        }
    };

    // ---- Listeners ---------------------------------------------------------------------------

    void listen(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    /** Tells every listener, on the main thread. */
    void post(final int what, final Object detail) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fire(what, detail);
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                fire(what, detail);
            }
        });
    }

    private void fire(int what, Object detail) {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.hub(what, detail);
            } catch (RuntimeException e) {
                // one listener's trouble never stops the others
            }
        }
        Guard.hub(what, detail);
    }

    /** The home screen is (or is not) the app in front. */
    void front(boolean on) {
        front = on;
        if (on) live.resume();
        else live.pause();
        post(FRONT, on);
    }

    // ---- Settings ------------------------------------------------------------------------------

    void applySettings() {
        live.setStation(prefs.station());
        live.setSky(!"off".equals(prefs.choice(Prefs.SKY)), Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
        live.setPlace(prefs.lat, prefs.lon);
        live.setClock24(is24h());
        live.setMbtaKey(prefs.get(Prefs.MBTA_KEY, ""));
        live.setCalendars(calendarUrls());
        try {
            Alarm.setLimit(Long.parseLong(prefs.choice(Prefs.ALARM_FOR)) * 60000L);
        } catch (NumberFormatException ignored) {
            // the default stays
        }
    }

    boolean is24h() {
        String c = prefs.choice(Prefs.CLOCK);
        return "24".equals(c) || "auto".equals(c) && DateFormat.is24HourFormat(app);
    }

    int accessMode() {
        String a = prefs.get(Prefs.ACCESS, "lan");
        return "off".equals(a) ? Console.OFF : "tailnet".equals(a) ? Console.TAILNET_ONLY : Console.LAN_AND_TAILNET;
    }

    /** Calendar feeds as {name, https url} pairs, from the saved JSON. */
    List<String[]> calendars() {
        List<String[]> out = new ArrayList<>();
        String json = prefs.get(Prefs.CALENDARS, "");
        if (json.isEmpty()) return out;
        try {
            List<Object> list = Json.arr(Json.obj(Json.parse(json)), "feeds");
            if (list == null) return out;
            for (Object o : list) {
                Map<String, Object> m = Json.obj(o);
                String name = Text.clean(Json.str(m, "name", "")), url = Json.str(m, "url", "");
                if (Net.allowed(url) && out.size() < 6) out.add(new String[] {name.isEmpty() ? "Calendar" : name, url});
            }
        } catch (RuntimeException e) {
            // unreadable: no calendars
        }
        return out;
    }

    private List<String> calendarUrls() {
        List<String> urls = new ArrayList<>();
        for (String[] c : calendars()) urls.add(c[1]);
        return urls;
    }

    /** webcal:// becomes https://, and only Google, iCloud and Outlook calendar hosts are kept. */
    static String calendarUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.startsWith("webcal://")) u = "https://" + u.substring(9);
        if (u.length() > 600 || !Net.allowed(u)) return null;
        return u;
    }

    // ---- Day and night -------------------------------------------------------------------------

    /**
     * Night from sunset, day from an hour after sunrise. A choice made by hand (remote or phone)
     * holds until the next sunrise or sunset change, then the automatic rule takes over again.
     */
    void mode() {
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        boolean auto = Sun.night(now, prefs.lat, prefs.lon, zone);
        nextChange = Sun.nextChange(now, prefs.lat, prefs.lon, zone);
        String m = prefs.get(Prefs.MODE, "auto");
        long until = prefs.getLong(Prefs.MODE_UNTIL);
        if (!"auto".equals(m) && until > 0 && now >= until) {
            prefs.put(Prefs.MODE, "auto");
            m = "auto";
        }
        boolean n = "night".equals(m) || !"day".equals(m) && auto;
        if (n != night) {
            night = n;
            post(MODE, n);
        }
    }

    /** "auto", "day" or "night". Day and night hold until the next automatic change. */
    void setMode(String m) {
        if (!"auto".equals(m) && !"day".equals(m) && !"night".equals(m)) return;
        prefs.put(Prefs.MODE, m);
        prefs.putLong(Prefs.MODE_UNTIL, "auto".equals(m) ? 0 : Sun.nextChange(System.currentTimeMillis(), prefs.lat, prefs.lon,
                TimeZone.getDefault()));
        boolean was = night;
        mode();
        if (was == night) post(MODE, night);
    }

    // ---- Live data (Live.Sink) ----------------------------------------------------------------

    @Override
    public void bikes(Model.Bikes b) {
        bikes = b;
        post(BIKES, b);
    }

    @Override
    public void transit(Model.Transit t) {
        transit = t;
        post(TRANSIT, t);
    }

    @Override
    public void sky(Model.Sky s) {
        sky = s;
        post(SKY, s);
    }

    @Override
    public void weather(String json, long at) {
        weather(json, at, true);
    }

    private void weather(String json, long at, boolean fresh) {
        Model.Weather w = Weather.parse(json, prefs.city, is24h(), at, prefs.celsiusFirst());
        if (!w.ok) return;
        if (System.currentTimeMillis() - at > 6 * 3600000L) w.ok = false;
        if (fresh) {
            prefs.put(Prefs.WEATHER_JSON, json);
            prefs.putLong(Prefs.WEATHER_AT, at);
        }
        weather = w;
        post(WEATHER, w);
    }

    /** The saved forecast again, after the units or the clock changed. */
    void reweather() {
        String saved = prefs.get(Prefs.WEATHER_JSON, null);
        if (saved != null) weather(saved, prefs.getLong(Prefs.WEATHER_AT), false);
    }

    @Override
    public void alerts(List<Model.Alert> list) {
        alerts = list;
        post(ALERTS, list);
    }

    @Override
    public void air(Model.Air a) {
        air = a;
        post(AIR, a);
    }

    @Override
    public void calendar(List<Ical.Event> list, long at) {
        events = list;
        eventsAt = at;
        post(CALENDAR, list);
    }

    @Override
    public void media(Model.Media m) {
        post(MEDIA, m);
    }

    // ---- Bike finder and the alarm -------------------------------------------------------------

    /** Starts bike finder on the followed station. False when Bluebikes has not answered yet. */
    boolean findBike() {
        Model.Bikes b = bikes;
        Model.Station s = b == null ? null : b.main();
        if (s == null) {
            // Not fetched yet (the TV was asleep): fetch now, and try again in a few seconds.
            live.wake(120000);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Model.Bikes again = bikes;
                    if (again != null && again.main() != null && !Finder.get().snapshot().active()) findBike();
                }
            }, 6000);
            return false;
        }
        Alarm.silence();
        String label = "auto".equals(prefs.station()) ? "Westgate" : Gbfs.shortName(s, false);
        Finder.get().start(s, label, prefs.findForMs());
        return true;
    }

    void stopFinding() {
        Finder.get().stop();
        Alarm.silence();
    }

    private long soundedFor;

    @Override
    public void onFinder(Model.Finder f, Model.Station s) {
        if (f.state == Model.Finder.FOUND && f.ended != soundedFor && System.currentTimeMillis() - f.ended < 60000L) {
            soundedFor = f.ended;
            if ("ring".equals(prefs.choice(Prefs.ALARM))) Alarm.ring();
            else Chime.play();
        }
        post(FINDER, f);
    }

    @Override
    public void ringing(boolean on) {
        post(ALARM, on);
    }

    // ---- Alexa (Ntfy.Sink) -----------------------------------------------------------------------

    void startAlexa() {
        if (ntfy != null) {
            ntfy.stop();
            ntfy = null;
        }
        if (!"on".equals(prefs.get(Prefs.ALEXA, "off"))) return;
        String topic = prefs.get(Prefs.ALEXA_TOPIC, "");
        if (!Ntfy.validTopic(topic)) {
            topic = Ntfy.newTopic(random);
            prefs.put(Prefs.ALEXA_TOPIC, topic);
        }
        ntfy = new Ntfy(topic, this);
        ntfy.start();
    }

    @Override
    public void command(final String word) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if ("find-ebike".equals(word)) {
                    Guard.wake(app, true);
                    live.wake(30 * 60000L);
                    findBike();
                } else if ("stop-ebike".equals(word)) {
                    stopFinding();
                } else if ("night".equals(word) || "day".equals(word)) {
                    setMode(word);
                } else if ("hearts".equals(word)) {
                    hearts("hearts", "Alexa");
                }
            }
        });
    }

    // ---- Notes and hearts ------------------------------------------------------------------------

    Notes.Note addNote(Notes.Note n) {
        Notes.Note added = notes.add(n);
        post(NOTE, added);
        String wake = prefs.choice(Prefs.WAKE_NOTES);
        if ("always".equals(wake) || "day".equals(wake) && !night) Guard.wake(app, false);
        return added;
    }

    boolean hearts(String kind, String from) {
        long now = System.currentTimeMillis();
        if (now - heartsAt < 3000) return false;
        heartsAt = now;
        post(HEARTS, new String[] {kind, Keys.cleanName(from)});
        return true;
    }

    // ---- Pairing (TV side) -------------------------------------------------------------------------

    /**
     * Opens a two-minute pairing window: {QR link, six-digit code}, or null while pairing is
     * locked or the server has no address.
     */
    String[] openPairing() {
        List<String> urls = console.urls();
        if (urls.isEmpty()) console.refresh();
        urls = console.urls();
        if (urls.isEmpty()) return null;
        String[] w = keys.open(System.currentTimeMillis());
        if (w == null) return null;
        window = new String[] {urls.get(0) + "/#k=" + w[0], w[1], urls.get(0)};
        return window;
    }

    void closePairing() {
        keys.close();
        window = null;
    }

    /** OK or Back on the TV's "Allow this phone?" card. */
    void decide(String id, boolean allow) {
        keys.decide(id, allow, System.currentTimeMillis());
        post(PAIR, null);
    }

    // ---- Console.Host -----------------------------------------------------------------------------

    @Override
    public String name() {
        return "Lucent";
    }

    @Override
    public byte[] asset(String name) {
        synchronized (assets) {
            if (assets.containsKey(name)) return assets.get(name);
        }
        byte[] b = null;
        AssetManager am = app.getAssets();
        try {
            InputStream in = am.open(name);
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) out.write(buf, 0, n);
                b = out.toByteArray();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            b = null;
        }
        synchronized (assets) {
            assets.put(name, b);
        }
        return b;
    }

    @Override
    public void pairRequest(Keys.Pending p) {
        window = null;
        post(PAIR, p);
    }

    @Override
    public byte[] photoFile(String id, boolean thumb) {
        return album.bytes(id, thumb);
    }

    @Override
    public String photo(Keys.Device d, byte[] body, String caption, long taken) throws Http.Refused {
        try {
            Album.Photo p = album.add(body, caption, taken, d.name);
            post(PHOTOS, p);
            return new Jw().obj().put("ok", true).put("id", p.id).end().toString();
        } catch (Album.Refused e) {
            throw new Http.Refused("not a photo".equals(e.why) ? 415 : "too large".equals(e.why) ? 413 : 409, e.why);
        }
    }

    @Override
    public String state(Keys.Device d, String peer) {
        // A phone looking at the control center wants fresh data, even while a film plays.
        main.post(new Runnable() {
            @Override
            public void run() {
                live.wake(90000);
            }
        });
        return State.json(this, d, System.currentTimeMillis());
    }

    @Override
    public String act(final Keys.Device d, final String action, final Map<String, Object> body) throws Http.Refused {
        // Changes run on the main thread, in order, where the screen reads them.
        FutureTask<String> task = new FutureTask<>(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return Actions.run(Hub.this, d, action, body);
            }
        });
        if ("photodel".equals(action) || "photocap".equals(action)) {
            work.execute(task);
        } else {
            main.post(task);
        }
        try {
            return task.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Http.Refused) throw (Http.Refused) c;
            throw new Http.Refused(400, "failed");
        } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
            throw new Http.Refused(503, "busy");
        }
    }

    /** The open pairing window for the TV card: {QR link, code, address}, or null. */
    String[] window() {
        String[] w = window;
        if (w != null && !keys.windowOpen(System.currentTimeMillis())) {
            window = null;
            return null;
        }
        return w;
    }

    void runInBackground(Runnable r) {
        try {
            work.execute(r);
        } catch (RuntimeException ignored) {
            // shutting down
        }
    }

    /** Sorted copy of the calendar events in the next {@code days}. */
    List<Ical.Event> upcoming(int days) {
        long now = System.currentTimeMillis();
        long to = Sun.startOfDay(now, TimeZone.getDefault()) + days * 86400000L;
        List<Ical.Event> out = new ArrayList<>();
        for (Ical.Event e : events) if (e.end > now && e.start < to) out.add(e);
        Collections.sort(out, Ical.ORDER);
        return out;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
