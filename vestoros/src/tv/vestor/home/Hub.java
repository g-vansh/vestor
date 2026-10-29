package tv.vestor.home;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.format.DateFormat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Vestor's centre, one per process: it owns the live data, the settings, notes, photos, the
 * shared list, pairing, Now Playing, the Alexa link and the control center's server, so the TV
 * screen ({@link Home}), the HOME guard and a phone all see and change the same things.
 *
 * The process lives as long as the accessibility service is bound, which is always while Vestor
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
            ALARM = 18, FRONT = 19, WALL = 20, SECURITY = 21, FORECAST = 22, ANSWER = 23, CAMCHECK = 24, UPDATE = 25, ASKING = 26,
            AIRPLAY = 27, MAP = 28;

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
    final Samsung samsung;
    final SamsungRemote remote;
    /** The Bluetooth beacon that shows the Samsung's AirPlay to Apple devices near the TV. */
    final AirPlay airplay;
    final Assistant assistant;
    final Updater updater;
    private Ntfy ntfy;
    /**
     * The Samsung remote's own thread and a short queue: waiting for Allow on the TV can take half
     * a minute, and keys must not wait behind SmartThings or photos. A full queue answers busy.
     */
    private final ThreadPoolExecutor tvq = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tv-remote");
                    t.setDaemon(true);
                    return t;
                }
            });
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
    volatile Model.Picture picture;
    volatile boolean night, front;
    volatile long nextChange, wifiUntil, heartsAt;
    /** When Alexa last asked for the question box, so Home opens it once Vestor is in front. */
    volatile long askAt;
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
        samsung = new Samsung(new Samsung.Store() {
            @Override
            public String get(String key) {
                return prefs.get(key, "");
            }

            @Override
            public void put(String key, String value) {
                prefs.putNow(key, value);
            }
        }, new Samsung.Web() {
            @Override
            public Net.Reply call(String method, String url, String[] headers, String contentType, byte[] body) {
                // The governor spaces requests to SmartThings, and connecting reads the devices right
                // after trading the code (Glare reads the mode right before setting it). Each request
                // waits its turn here instead of being refused. Every caller is off the main thread.
                long wait = Pace.wait("api.smartthings.com", System.currentTimeMillis());
                if (wait > 0 && wait <= 15000L) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Net.call(method, url, 10000, Net.LIMIT_SMALL, headers, contentType, body);
            }
        });
        remote = new SamsungRemote(new SamsungRemote.Store() {
            @Override
            public String get(String key) {
                return prefs.get(key, "");
            }

            @Override
            public void put(String key, String value) {
                prefs.putNow(key, value);
            }
        });
        airplay = new AirPlay(new AirPlay.Store() {
            @Override
            public String get(String key) {
                return prefs.get(key, "");
            }

            @Override
            public void put(String key, String value) {
                prefs.putNow(key, value);
            }

            @Override
            public boolean enabled() {
                return "on".equals(prefs.choice(Prefs.AIRPLAY));
            }

            @Override
            public InetAddress samsung() {
                return SamsungRemote.address(remote.address());
            }
        }, new Beacon(c));
        airplay.onChange(new Runnable() {
            private String last = "";

            @Override
            public void run() {
                String now = airplay.state() + " " + airplay.radio() + " " + airplay.port();
                if (now.equals(last)) return;
                last = now;
                post(AIRPLAY, null);
            }
        });
        assistant = new Assistant(this);
        updater = new Updater(this);
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
        main.postDelayed(watch, 3000);
        updater.start();
    }

    private boolean pinShown = true;

    /**
     * Every 3 s: what happened to paired phones (a key used from two places signs a phone out,
     * too many wrong codes turn the code off) becomes a notice on the TV, and an open pairing
     * card drops its code once the code stops working.
     */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            for (String e : keys.events()) {
                // A phone moving between home Wi-Fi and Tailscale changes address often. The phone
                // page's device list shows the address, and the TV stays quiet about it.
                if (!e.contains(" is now at ")) post(SECURITY, e);
            }
            long now = System.currentTimeMillis();
            // Only while the window is still open: at its end the card simply closes.
            if (window != null && pinShown && keys.windowOpen(now) && !keys.pinOpen(now)) {
                pinShown = false;
                post(PAIR, Boolean.FALSE);
            }
            main.postDelayed(this, 3000);
        }
    };

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

    private long keptAt;

    /** Every 30 s: the server's addresses, day and night, the Wi-Fi card, and once a day the Samsung sign-in. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            console.refresh();
            mode();
            long now = System.currentTimeMillis();
            if (samsung.connected() && now - keptAt > 86400000L) {
                keptAt = now;
                runInBackground(new Runnable() {
                    @Override
                    public void run() {
                        samsung.keepAlive();
                        try {
                            samsung.status();
                        } catch (Samsung.Failed ignored) {
                            // the modes stay as last read
                        }
                    }
                });
            }
            if (wifiUntil > 0 && System.currentTimeMillis() > wifiUntil) {
                wifiUntil = 0;
                post(WIFI, null);
            }
            assistant.tick(now);
            airplay.tick(now);
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
        // A picture change missed while the TV was off is made when it is back.
        if (on && pictureStale) picture(night, false);
        post(FRONT, on);
    }

    // ---- Settings ------------------------------------------------------------------------------

    void applySettings() {
        live.setStation(prefs.station());
        live.setStation2(prefs.station2());
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
        airplayNow();
    }

    /** Checks the Samsung's AirPlay now, on its own thread: the beacon setting or the TV's address changed. */
    void airplayNow() {
        airplay.kick();
        airplay.tick(System.currentTimeMillis());
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
            picture(n, true);
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
        forecast(b, false);
    }

    // ---- E-bike forecast -----------------------------------------------------------------------

    private Forecast model;
    private boolean modelTried;
    private long forecastAt;
    private int lastV = -2, lastE = -2;
    private volatile boolean forecasting;
    volatile Forecast.Result bikeForecast;

    /** The model shipped in assets/bikes_model.json, read once. Null if it cannot be read. */
    private synchronized Forecast model() {
        if (!modelTried) {
            modelTried = true;
            byte[] b = asset("bikes_model.json");
            try {
                model = b == null ? null : Forecast.parse(new String(b, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                model = null;
            }
        }
        return model;
    }

    /**
     * A fresh forecast when the counts at the two docks change, and every two minutes otherwise, on
     * the worker thread (a few hundred milliseconds on the stick).
     */
    void forecast(Model.Bikes b, boolean force) {
        if (b == null || b.main() == null || !b.main().live || forecasting) return;
        final int v = b.main().rentableEbikes();
        Model.Station a = b.alt();
        final int e = a == null ? -1 : a.rentableEbikes();
        long now = System.currentTimeMillis();
        if (!force && v == lastV && e == lastE && now - forecastAt < 120000L) return;
        lastV = v;
        lastE = e;
        forecastAt = now;
        forecasting = true;
        final Model.Weather w = weather;
        runInBackground(new Runnable() {
            @Override
            public void run() {
                // A few hundred milliseconds of arithmetic: at background priority, so the home
                // screen's frames always come first.
                int priority = Process.getThreadPriority(Process.myTid());
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    Forecast f = model();
                    if (f == null) return;
                    double[] rain = null, temp = null;
                    if (w != null && w.ok) {
                        rain = new double[w.hourRain.size()];
                        for (int i = 0; i < rain.length; i++) rain[i] = w.hourRain.get(i) < 0 ? -1 : w.hourRain.get(i) / 100.0;
                        temp = new double[w.hourC.size()];
                        for (int i = 0; i < temp.length; i++) temp[i] = w.hourC.get(i);
                    }
                    long t = System.currentTimeMillis();
                    Forecast.Result r = f.predict(t, v, e, TimeZone.getDefault(), rain, temp, morningAt(t, TimeZone.getDefault()));
                    bikeForecast = r;
                    post(FORECAST, r);
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    // no forecast this time; the counts still show
                } finally {
                    forecasting = false;
                    Process.setThreadPriority(priority);
                }
            }
        });
    }

    /** In the evening and at night, the coming 7:30 am, for "tomorrow at 7:30"; otherwise 0. */
    static long morningAt(long now, TimeZone zone) {
        java.util.Calendar c = java.util.Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY);
        if (h >= 6 && h < 17) return 0;
        if (h >= 17) c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        c.set(java.util.Calendar.HOUR_OF_DAY, 7);
        c.set(java.util.Calendar.MINUTE, 30);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
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
        long age = System.currentTimeMillis() - at;
        if (age > 6 * 3600000L) w.ok = false;
        // Two hours of rain timing is only worth showing while it is fresh.
        if (age > 45 * 60000L) {
            w.rain = "";
            w.wetSoon = false;
        }
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
        Model.Station second = b.alt();
        Finder.get().start(s, label, second, second == null ? "" : b.altLabel, prefs.findForMs());
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
            if (pushOn()) {
                final String where = f.station.isEmpty() ? "Westgate" : f.station;
                final int n = f.ebikes;
                final String link = Gbfs.rentLink(s);
                runInBackground(new Runnable() {
                    @Override
                    public void run() {
                        push("E-bike ready at " + where, (n == 1 ? "1 e-bike is" : n + " e-bikes are")
                                + " docked now. Tap to open Bluebikes and reserve one for up to 10 minutes.", 5, "bike", link);
                    }
                });
            }
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
                } else if ("night".equals(word) || "day".equals(word) || "auto".equals(word)) {
                    setMode(word);
                } else if ("glare-on".equals(word) || "glare-off".equals(word)) {
                    glare("glare-on".equals(word));
                } else if ("vestor".equals(word)) {
                    Guard.wake(app, true);
                } else if ("firetv".equals(word)) {
                    Guard.amazon(app);
                } else if ("calls".equals(word)) {
                    Guard.wake(app, false);
                    try {
                        app.startActivity(new Intent(app, Calls.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) {
                        // Fire OS refused a start from the background: the button on the top row still works
                    }
                } else if ("wifi".equals(word)) {
                    if (prefs.get(Prefs.WIFI_SSID, "").isEmpty()) return;
                    Guard.wake(app, true);
                    wifiUntil = System.currentTimeMillis() + 120000L;
                    post(WIFI, null);
                } else if ("wifi-hide".equals(word)) {
                    wifiUntil = 0;
                    post(WIFI, null);
                } else if ("hearts".equals(word)) {
                    hearts("hearts", "Alexa");
                } else if ("ask".equals(word)) {
                    // "Alexa, turn on Ask Vestor": Vestor comes to the front with its question box open.
                    askAt = System.currentTimeMillis();
                    Guard.wake(app, true);
                    post(ASKING, null);
                } else if ("update".equals(word)) {
                    // publish.sh's nudge: look in a few minutes rather than within six hours. Installing still waits for you.
                    updater.checkSoon();
                }
            }
        });
    }

    // ---- Volume --------------------------------------------------------------------------------

    /**
     * The Fire TV's own volume keys: Fire OS sends them over HDMI-CEC to whatever plays the sound
     * (the soundbar through the Samsung's eARC). No exact level: CEC has none.
     */
    void systemVolume(String what) {
        android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int dir = "up".equals(what) ? android.media.AudioManager.ADJUST_RAISE : "down".equals(what)
                ? android.media.AudioManager.ADJUST_LOWER : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
        try {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI);
        } catch (RuntimeException ignored) {
            // some builds refuse; the TV remote over the network still works
        }
    }

    // ---- Phone alerts (ntfy) ---------------------------------------------------------------------

    boolean pushOn() {
        return "on".equals(prefs.get(Prefs.PUSH, "off")) && Ntfy.validTopic(prefs.get(Prefs.PUSH_TOPIC, ""));
    }

    /** "on", "off", or "new" for a fresh topic (phones then subscribe again). */
    void setPush(String v) {
        if ("new".equals(v) || !Ntfy.validTopic(prefs.get(Prefs.PUSH_TOPIC, ""))) prefs.put(Prefs.PUSH_TOPIC, Ntfy.newTopic(random));
        prefs.put(Prefs.PUSH, "off".equals(v) ? "off" : "on");
    }

    /** One alert to every phone on the topic. False when ntfy did not take it. Slow: off the main thread. */
    boolean push(String title, String message, int priority, String tags, String click) {
        String t = prefs.get(Prefs.PUSH_TOPIC, "");
        if (!Ntfy.validTopic(t)) return false;
        Net.Reply r = Net.call("GET", Ntfy.pushUrl(t, title, message, priority, tags, click), 10000, Net.LIMIT_TINY, null, null, null);
        return r != null && r.code == 200;
    }

    // ---- The Samsung's picture (SmartThings) -----------------------------------------------------

    private volatile boolean pictureStale;

    boolean glare() {
        return "on".equals(prefs.get(Prefs.GLARE, "off"));
    }

    /**
     * Glare on: the bright picture, for a sunny room. Glare off: the day or night picture when
     * the picture follows the sun, else the one from before. Glare ends by itself at sunset.
     */
    void glare(final boolean on) {
        if (!samsung.connected()) return;
        runInBackground(new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                try {
                    glareNow(on);
                } catch (Samsung.Failed f) {
                    // samsung.error() says why, on the TV and in the control center
                    ok = false;
                }
                post(SETTINGS, ok ? Prefs.GLARE : "glare!");
            }
        });
    }

    /** {@link #glare} on the calling thread, which waits for Samsung's servers. */
    void glareNow(boolean on) throws Samsung.Failed {
        if (on) {
            if (!glare()) {
                // The mode on screen now, in case someone changed it with the Samsung's own remote.
                try {
                    samsung.status();
                } catch (Samsung.Failed ignored) {
                    // the last known mode will do
                }
                prefs.put(Prefs.GLARE_BACK, samsung.mode());
            }
            samsung.setMode(picked(Prefs.GLARE_MODE));
            prefs.put(Prefs.GLARE, "on");
        } else {
            prefs.put(Prefs.GLARE, "off");
            samsung.setMode(resting(night));
        }
    }

    /**
     * Day and night changed (or the TV is back after missing a change): glare ends at night, and
     * the picture follows the sun when asked to. {@code changed} is false for a retry.
     */
    void picture(final boolean isNight, boolean changed) {
        if (!samsung.connected()) return;
        final boolean follow = "on".equals(prefs.choice(Prefs.PICTURE_FOLLOW));
        final boolean endGlare = isNight && glare();
        if (!follow && !endGlare || !isNight && glare()) return;
        if (!changed && !pictureStale) return;
        runInBackground(new Runnable() {
            @Override
            public void run() {
                if (endGlare) prefs.put(Prefs.GLARE, "off");
                try {
                    samsung.setMode(resting(isNight));
                    pictureStale = false;
                } catch (Samsung.Failed f) {
                    pictureStale = f.status >= 500;
                }
                post(SETTINGS, "picture");
            }
        });
    }

    /** The picture when glare is off. */
    private String resting(boolean isNight) {
        if ("on".equals(prefs.choice(Prefs.PICTURE_FOLLOW))) return picked(isNight ? Prefs.NIGHT_MODE : Prefs.DAY_MODE);
        String back = prefs.get(Prefs.GLARE_BACK, "");
        return samsung.modes().contains(back) ? back : picked(Prefs.DAY_MODE);
    }

    /** The mode chosen for glare, day or night, or a sensible one this TV has. */
    String picked(String key) {
        String v = prefs.get(key, "");
        if (samsung.modes().contains(v)) return v;
        if (Prefs.GLARE_MODE.equals(key)) return samsung.pick("Dynamic", "Vivid", "Standard");
        if (Prefs.NIGHT_MODE.equals(key)) return samsung.pick("Movie", "Filmmaker Mode", "FILMMAKER MODE", "Natural", "Standard");
        return samsung.pick("Standard", "Natural");
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
        pinShown = !w[1].isEmpty();
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

    // ---- Camera check on the Samsung -----------------------------------------------------------------

    /** Until when a report from the Samsung's browser is taken (0: no check running). */
    private long camCheckUntil;
    /** The last report, one sentence, and when it came. */
    volatile String camReport = "";
    volatile long camReportAt;

    /**
     * Starts the check of whether the Samsung itself can see a webcam on its USB port: opens the
     * Samsung's own browser on a page this server serves, which lists the cameras and microphones
     * the TV offers and reports back. Answers what the screen should say while it runs.
     */
    String startCamCheck(long now) {
        List<String> urls = console.urls();
        if (urls.isEmpty()) console.refresh();
        urls = console.urls();
        if (urls.isEmpty()) return "Vestor has no network address yet. Check the Fire TV's Wi-Fi.";
        final String url = urls.get(0) + "/camcheck";
        synchronized (this) {
            camCheckUntil = now + 180000L;
        }
        if (!remote.paired()) {
            return "On the Samsung remote, open Internet and go to " + url.substring("http://".length())
                    + ". Then press Home on the Fire TV remote.";
        }
        runInBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    remote.launch(SamsungRemote.BROWSER, false, url);
                } catch (Samsung.Failed e) {
                    // the screen already says where to go by hand
                }
            }
        });
        return "Opening the Samsung's browser. If it does not open, go to " + url.substring("http://".length())
                + " in the Samsung's Internet app. Press Home on the Fire TV remote when it says it is done.";
    }

    @Override
    public boolean camCheck(String peer, Map<String, Object> body, long now) {
        synchronized (this) {
            // One report per check, and only while one is running: a stranger cannot post at will.
            if (now > camCheckUntil) return false;
            camCheckUntil = 0;
        }
        camReport = Calling.camReport(body);
        camReportAt = now;
        post(CAMCHECK, camReport);
        return true;
    }

    // ---- Console.Host -----------------------------------------------------------------------------

    @Override
    public String name() {
        return "Vestor";
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
        // Steps that wait on another server run on the worker thread, never the screen's, and the
        // TV remote on its own.
        boolean slow = "samsung".equals(action) || "push".equals(action);
        boolean tv = "remote".equals(action);
        if (tv) {
            try {
                tvq.execute(task);
            } catch (RejectedExecutionException e) {
                throw new Http.Refused(503, "busy");
            }
        } else if (slow || "photodel".equals(action) || "photocap".equals(action)) {
            work.execute(task);
        } else {
            main.post(task);
        }
        try {
            return task.get(tv ? ("pair".equals(Json.str(body, "do", "")) ? 45 : 12) : slow ? 50 : 20, TimeUnit.SECONDS);
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
