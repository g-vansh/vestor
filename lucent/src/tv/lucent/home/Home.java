package tv.lucent.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home activity. Owns the {@link Scene}, feeds it what the {@link Hub} knows (live data,
 * notes, photos, day and night, pairing) plus the things only an activity sees (apps, the clock,
 * the sound route), and carries out what the scene asks for. The HOME button reaches it through
 * {@link Guard}; it also answers the HOME intent so Fire OS lists it as a launcher.
 */
public final class Home extends Activity implements Scene.Host, Hub.Listener {
    private static final long MINUTE = 60000L;

    /** AirPlay receivers from the Amazon Appstore, best first; any app whose name says AirPlay also counts. */
    private static final String[] AIRPLAY = {"com.softmedia.receiver", "com.softmedia.receiver.lite", "com.ionitech.airscreen",
        "io.github.jqssun.airplay"};
    /** Fire TV's own screens, opened directly (found exported, no permission needed, on Fire OS 8). */
    private static final ComponentName MIRACAST = new ComponentName("com.amazon.cast.sink",
            "com.amazon.cast.sink.DisplayMirroringSinkActivity");
    private static final ComponentName BLUETOOTH = new ComponentName("com.amazon.tv.settings.v2",
            "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity");

    /** Whether the home screen is the app in front; the guard's alerts stay out of its way. */
    static volatile boolean inFront;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService art = Executors.newSingleThreadExecutor();
    private Hub hub;
    private Prefs prefs;
    private Scene scene;
    private HomeView view;
    private List<Walls.Item> walls = new ArrayList<>();
    private volatile int wallGeneration;
    private String wallId = "";
    private int appsGeneration;
    private final Map<String, Catalog.Entry> entries = new HashMap<>();
    private final Map<String, Model.App> artCache = new HashMap<>();
    private long bannersSeen, slept;
    private AudioManager audio;
    private final Set<Integer> outputs = new HashSet<>();
    private boolean resumed;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            if (data != null) artCache.remove(data.getSchemeSpecificPart());
            loadApps();
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final BroadcastReceiver hdmi = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refreshAudio();
        }
    };

    /** Headphones arriving and leaving: a toast, and the Sound card follows. */
    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            for (AudioDeviceInfo d : added) {
                if (!d.isSink() || !outputs.add(d.getId()) || !bluetooth(d)) continue;
                String name = name(d);
                scene.toast(name + " connected", "Sound plays on " + name + " now", Scene.TOAST_EARBUDS, System.currentTimeMillis());
            }
            refreshAudio();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            for (AudioDeviceInfo d : removed) {
                if (!d.isSink() || !outputs.remove(d.getId()) || !bluetooth(d)) continue;
                scene.toast(name(d) + " disconnected", "Sound is back on the TV", Scene.TOAST_SOUNDBAR, System.currentTimeMillis());
            }
            refreshAudio();
        }
    };

    private final Runnable minutely = new Runnable() {
        @Override
        public void run() {
            maybeRotate(false);
            keepAwake();
            bedtime();
            feedPlans();
            ui.postDelayed(this, MINUTE);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Crash.watch(this);
        Crash.starting(this);
        hub = Hub.get(this);
        prefs = hub.prefs;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        scene = new Scene(this);
        applyLook();
        view = new HomeView(this, scene);
        setContentView(view);
        scene.setNight(hub.night, true);
        feedAll();
        hub.listen(this);
        tick();
        options();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        bannersSeen = bannersStamp();
        loadApps();
        listWalls(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        resumed = true;
        Guard.homeInFront();
        Guard.arrived(this);
        hub.front(true);
        if (prefs.readConf()) {
            hub.applySettings();
            hub.live.refreshSlow();
        }
        hub.importShared();
        // A tile picture pushed into banners/ shows the next time you come home.
        long seen = bannersStamp();
        if (seen != bannersSeen) {
            bannersSeen = seen;
            artCache.clear();
            loadApps();
        }
        long now = System.currentTimeMillis();
        scene.setFinder(Finder.get().snapshot(), Finder.get().station(), now);
        scene.setAlarm(Alarm.ringing(), Finder.get().snapshot(), Finder.get().station(), now);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        registerReceiver(hdmi, new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));
        primeOutputs();
        try {
            audio.registerAudioDeviceCallback(devices, ui);
        } catch (RuntimeException ignored) {
            // the Sound card still reads the route each time it opens
        }
        refreshAudio();
        applyLook();
        tick();
        scene.enter(now);
        unseenNote(now);
        // Asked for while Lucent was not running (Alexa, or a phone): the Wi-Fi card is still due.
        if (hub.wifiUntil > now) showWifi(now);
        maybeRotate(false);
        keepAwake();
        ui.removeCallbacks(minutely);
        ui.postDelayed(minutely, MINUTE);
        view.start();
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inFront = false;
        resumed = false;
        hub.front(false);
        if (Finder.get().snapshot().active()) Guard.remind();
        view.stop();
        ui.removeCallbacks(minutely);
        try {
            audio.unregisterAudioDeviceCallback(devices);
        } catch (RuntimeException ignored) {
            // not registered
        }
        try {
            unregisterReceiver(time);
            unregisterReceiver(hdmi);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
    }

    @Override
    protected void onDestroy() {
        hub.unlisten(this);
        try {
            unregisterReceiver(packages);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        io.shutdownNow();
        art.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        scene.reset(System.currentTimeMillis());
        view.kick();
    }

    @Override
    public void onBackPressed() {
        // HOME screen: Back never leaves it. The view handles Back as a key.
    }

    /**
     * While an app plays, the hidden window's GPU context is released, as dumbtv measured on
     * Fire OS (about 35 MB). The wallpaper bitmaps stay, so coming home is instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    // ---- The hub's news ------------------------------------------------------------------------

    private void feedAll() {
        scene.setBikes(hub.bikes);
        scene.setTransit(hub.transit);
        scene.setSky(hub.sky);
        if (hub.weather != null) scene.setWeather(hub.weather);
        scene.setAlerts(hub.alerts);
        scene.setAir(hub.air);
        scene.setMedia(hub.media.snapshot());
        feedPlans();
        feedPinned();
        long now = System.currentTimeMillis();
        scene.setFinder(Finder.get().snapshot(), Finder.get().station(), now);
        if (Alarm.ringing()) scene.setAlarm(true, Finder.get().snapshot(), Finder.get().station(), now);
    }

    @Override
    public void hub(int what, Object detail) {
        long now = System.currentTimeMillis();
        switch (what) {
            case Hub.BIKES: {
                boolean first = scene.cards.bikes == null;
                scene.setBikes((Model.Bikes) detail);
                if (first) options();
                break;
            }
            case Hub.TRANSIT:
                scene.setTransit((Model.Transit) detail);
                break;
            case Hub.SKY:
                scene.setSky((Model.Sky) detail);
                break;
            case Hub.WEATHER:
                scene.setWeather((Model.Weather) detail);
                break;
            case Hub.ALERTS:
                scene.setAlerts(hub.alerts);
                break;
            case Hub.AIR:
                scene.setAir(hub.air);
                break;
            case Hub.CALENDAR:
            case Hub.PLANS:
                feedPlans();
                break;
            case Hub.NOTE:
                feedPinned();
                if (detail instanceof Notes.Note) {
                    Notes.Note n = (Notes.Note) detail;
                    // Shown here when home is in front; the guard shows it over any other app.
                    if (resumed) scene.showNote(n, now);
                    if (resumed || interactive()) prefs.putLong(Prefs.NOTE_SEEN, n.at);
                }
                break;
            case Hub.HEARTS:
                if (resumed && detail instanceof String[]) {
                    String[] h = (String[]) detail;
                    scene.hearts(h[0], h[1], now);
                }
                break;
            case Hub.MODE:
                scene.setNight(hub.night, !resumed);
                break;
            case Hub.PHOTOS:
                listWalls(false);
                if (detail instanceof Album.Photo) {
                    Album.Photo p = (Album.Photo) detail;
                    scene.toast("A new photo", p.from.isEmpty() ? "Added to the album" : "From " + p.from, Scene.TOAST_PHOTO, now);
                }
                break;
            case Hub.SETTINGS:
                settingChanged(detail instanceof String ? (String) detail : "");
                break;
            case Hub.FINDER:
                scene.setFinder((Model.Finder) detail, Finder.get().station(), now);
                break;
            case Hub.MEDIA:
                scene.setMedia((Model.Media) detail);
                break;
            case Hub.FORECAST:
                scene.setForecast((Forecast.Result) detail, now);
                break;
            case Hub.ANSWER:
                if (detail instanceof Claude.Answer) {
                    Claude.Answer a = (Claude.Answer) detail;
                    // Over Lucent only: an answer never covers a film (the phone shows it there).
                    scene.setAnswer(a, inFront);
                }
                break;
            case Hub.PAIR:
                if (detail instanceof Keys.Pending) {
                    Keys.Pending p = (Keys.Pending) detail;
                    scene.setPairing(null, null, null, 0);
                    scene.ask(p.id, p.name, p.match);
                    if (!resumed) Guard.wake(this, true);
                } else if (Boolean.FALSE.equals(detail)) {
                    scene.pairCodeOff();
                }
                break;
            case Hub.SECURITY:
                scene.toast("Phone control", String.valueOf(detail), Scene.TOAST_PHONE, now);
                break;
            case Hub.WIFI:
                showWifi(now);
                break;
            case Hub.ALARM:
                scene.setAlarm(Boolean.TRUE.equals(detail), Finder.get().snapshot(), Finder.get().station(), now);
                break;
            case Hub.WALL:
                if (detail instanceof String) showPhoto((String) detail);
                break;
            default:
                return;
        }
        view.kick();
    }

    private boolean interactive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    /** A note sent while the TV was off or asleep is waiting when you come home, for a day. */
    private void unseenNote(long now) {
        Notes.Note n = hub.notes.latest();
        if (n == null || n.at <= prefs.getLong(Prefs.NOTE_SEEN) || now - n.at > 24 * 60 * MINUTE) return;
        prefs.putLong(Prefs.NOTE_SEEN, n.at);
        scene.showNote(n, now);
    }

    /** The note kept on the home screen: the pinned one, or else today's newest for 12 hours. */
    private void feedPinned() {
        Notes.Note n = hub.notes.pinned();
        if (n == null) {
            Notes.Note latest = hub.notes.latest();
            if (latest != null && System.currentTimeMillis() - latest.at < 12 * 60 * MINUTE) n = latest;
        }
        scene.setPinned(n);
    }

    private void feedPlans() {
        List<String> feeds = new ArrayList<>();
        for (String[] c : hub.calendars()) feeds.add(c[0]);
        scene.setPlans(hub.upcoming(7), feeds, hub.plans.countdowns(), hub.plans.items());
    }

    private void showWifi(long now) {
        String ssid = prefs.get(Prefs.WIFI_SSID, "");
        if (hub.wifiUntil <= now || ssid.isEmpty()) {
            scene.setWifi(null, null, null, 0);
            return;
        }
        boolean[][] qr = Qr.encode(wifiCode(ssid, prefs.get(Prefs.WIFI_PASS, "")));
        scene.setWifi(ssid, view.gfx(), qr, hub.wifiUntil);
        if (!resumed) Guard.wake(this, true);
    }

    /** The Wi-Fi QR format phones read: WIFI:T:WPA;S:name;P:password;; with \ ; , : " escaped. */
    static String wifiCode(String ssid, String pass) {
        return "WIFI:T:" + (pass.isEmpty() ? "nopass" : "WPA") + ";S:" + wifiEscape(ssid) + ";"
                + (pass.isEmpty() ? "" : "P:" + wifiEscape(pass) + ";") + ";";
    }

    private static String wifiEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    private void settingChanged(String key) {
        long now = System.currentTimeMillis();
        if (Prefs.GLARE.equals(key)) {
            boolean on = hub.glare();
            scene.toast(on ? "Glare on" : "Glare off", on ? "The brightest picture, until sunset" : "The picture is back", Scene.TOAST_INFO,
                    now);
        } else if ("glare!".equals(key)) {
            String why = hub.samsung.error();
            scene.toast("The Samsung did not switch", why.isEmpty() ? "Is the TV on?" : why, Scene.TOAST_INFO, now);
        }
        applyLook();
        if (Prefs.ALBUM.equals(key)) listWalls(false);
        if (Prefs.CLOCK.equals(key)) tick();
        if (Prefs.AWAKE_FROM.equals(key) || Prefs.AWAKE_TO.equals(key)) keepAwake();
        options();
    }

    /** Settings the scene draws with, from the stored choices. */
    private void applyLook() {
        scene.setReduced(prefs.reduced() || animationsOff());
        scene.setAmbientAfter(minutes(prefs.choice(Prefs.AMBIENT)));
        scene.setDimAfter(minutes(prefs.choice(Prefs.DIM)));
        scene.setWeatherOn(prefs.weatherOn());
        scene.setSkyOn(!"off".equals(prefs.choice(Prefs.SKY)));
        scene.setStill("still".equals(prefs.choice(Prefs.QUALITY)));
        scene.setClock24(hub.is24h());
    }

    // ---- Apps ----------------------------------------------------------------------------------

    private void loadApps() {
        final int generation = ++appsGeneration;
        final List<String> order = prefs.list(Prefs.ORDER);
        final Set<String> hidden = prefs.set(Prefs.HIDDEN);
        final Set<String> shown = prefs.set(Prefs.SHOWN);
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<Catalog.Entry> found;
                try {
                    found = Catalog.all(Home.this);
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                    found = new ArrayList<>();
                }
                final List<Catalog.Entry> all = found;
                final List<Catalog.Entry> dock = Catalog.dock(all, order, hidden, shown);
                final String airplay = airplayLabel(all);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != appsGeneration || isDestroyed()) return;
                        entries.clear();
                        for (Catalog.Entry e : all) entries.put(e.pkg, e);
                        scene.setMirrorApp(airplay);
                        List<Model.App> list = new ArrayList<>();
                        final List<Model.App> needArt = new ArrayList<>();
                        for (Catalog.Entry e : dock) {
                            Model.App a = artCache.get(e.pkg);
                            if (a == null || !a.label.equals(e.label.toString())) {
                                a = new Model.App(e.pkg, e.label.toString());
                                artCache.put(e.pkg, a);
                                needArt.add(a);
                            }
                            list.add(a);
                        }
                        scene.setApps(list);
                        options();
                        view.kick();
                        loadArt(needArt, new HashMap<String, Catalog.Entry>(entries));
                    }
                });
            }
        });
    }

    /** The installed AirPlay receiver's name, or null. */
    private static String airplayLabel(List<Catalog.Entry> all) {
        for (String pkg : AIRPLAY) {
            for (Catalog.Entry e : all) if (e.pkg.equals(pkg)) return e.label.toString();
        }
        for (Catalog.Entry e : all) {
            String l = e.label.toString().toLowerCase(Locale.US);
            if (l.contains("airplay") || l.contains("airscreen") || l.contains("airreceiver")) return e.label.toString();
        }
        return null;
    }

    private Catalog.Entry airplayEntry() {
        for (String pkg : AIRPLAY) if (entries.containsKey(pkg)) return entries.get(pkg);
        for (Catalog.Entry e : entries.values()) {
            String l = e.label.toString().toLowerCase(Locale.US);
            if (l.contains("airplay") || l.contains("airscreen") || l.contains("airreceiver")) return e;
        }
        return null;
    }

    /** Changes whenever a file is added to, removed from or renamed in banners/. */
    private long bannersStamp() {
        File media = prefs.media();
        return media == null ? 0 : new File(media, "banners").lastModified();
    }

    private void loadArt(final List<Model.App> apps, final Map<String, Catalog.Entry> snapshot) {
        if (apps.isEmpty()) return;
        art.execute(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                File media = prefs.media();
                File banners = media == null ? null : new File(media, "banners");
                for (final Model.App a : apps) {
                    Catalog.Entry e = snapshot.get(a.id);
                    if (e == null) continue;
                    try {
                        Catalog.art(pm, e, a, banners);
                    } catch (RuntimeException | OutOfMemoryError | StackOverflowError ex) {
                        // Another app's art is outside Lucent's control: one that cannot be
                        // drawn leaves its tile plain, and is not tried again until it updates.
                        a.loaded = true;
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            view.kick();
                        }
                    });
                }
            }
        });
    }

    // ---- Wallpaper -----------------------------------------------------------------------------

    /** Lists the wallpapers off the main thread; {@code first} also shows the saved one. */
    private void listWalls(final boolean first) {
        final String album = prefs.choice(Prefs.ALBUM);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<Walls.Item> list = Walls.list(album, hub.album);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        walls = list;
                        int at = savedWall();
                        if (first || !walls.isEmpty() && !walls.get(at).id.equals(wallId)) showWall(at, null);
                        options();
                    }
                });
            }
        });
    }

    /** The saved wallpaper's place in the list: by id, or by number as 1.x saved it. */
    private int savedWall() {
        if (walls.isEmpty()) return 0;
        String saved = prefs.get(Prefs.WALL, "0");
        int i = Walls.indexOf(walls, saved);
        if (i >= 0) return i;
        try {
            return Math.max(0, Math.min(walls.size() - 1, Integer.parseInt(saved)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void showWall(int index, final String title) {
        if (index < 0 || index >= walls.size()) return;
        final Walls.Item item = walls.get(index);
        wallId = item.id;
        prefs.put(Prefs.WALL, item.id);
        final int generation = ++wallGeneration;
        final int w = Math.max(1280, getResources().getDisplayMetrics().widthPixels);
        final int h = Math.max(720, getResources().getDisplayMetrics().heightPixels);
        io.execute(new Runnable() {
            @Override
            public void run() {
                // Flicking through wallpapers queues decodes; only the last one counts.
                if (generation != wallGeneration) return;
                final Model.Wall wall = Walls.load(Home.this, item, w, h);
                if (wall != null && title != null) {
                    wall.title = title;
                    wall.place = item.title;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (wall == null || isDestroyed() || generation != wallGeneration) return;
                        // The previous wallpaper is left to the garbage collector: the renderer may
                        // still hold it for the crossfade, and recycling under it is not worth 8 MB.
                        scene.setWall(wall);
                        Guard.coverImage = (Bitmap) wall.blur;
                        view.kick();
                    }
                });
            }
        });
    }

    /**
     * Moves to the next wallpaper when the chosen interval has passed. The first change of a day
     * goes to a photo taken on this date in an earlier year, if there is one.
     */
    private void maybeRotate(boolean force) {
        long every = rotateMs(prefs.choice(Prefs.ROTATE));
        long now = System.currentTimeMillis();
        long at = prefs.getLong(Prefs.WALL_AT);
        if (at == 0) {
            prefs.putLong(Prefs.WALL_AT, now);
            return;
        }
        if (walls.isEmpty() || !force && (every <= 0 || now - at < every)) return;
        prefs.putLong(Prefs.WALL_AT, now);
        TimeZone zone = TimeZone.getDefault();
        long today = Sun.startOfDay(now, zone);
        if (prefs.getLong(Prefs.SEEN_DAY) != today) {
            prefs.putLong(Prefs.SEEN_DAY, today);
            int memory = Walls.onThisDay(walls, now, zone);
            if (memory >= 0) {
                showWall(memory, Walls.anniversary(walls.get(memory), zone));
                return;
            }
        }
        showWall((savedWall() + 1) % walls.size(), null);
    }

    /** A photo chosen on the phone, shown now; the rotation carries on from it. */
    private void showPhoto(String id) {
        int i = Walls.indexOf(walls, "p:" + id);
        if (i < 0 && !"nature".equals(prefs.choice(Prefs.ALBUM))) {
            // Just uploaded: list again, then show it.
            final String want = id;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    final List<Walls.Item> list = Walls.list(prefs.choice(Prefs.ALBUM), hub.album);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isDestroyed()) return;
                            walls = list;
                            int j = Walls.indexOf(walls, "p:" + want);
                            if (j >= 0) showWall(j, null);
                            options();
                        }
                    });
                }
            });
            return;
        }
        if (i < 0) {
            // The album is set to nature only: showing one of ours means switching to both.
            prefs.put(Prefs.ALBUM, "both");
            prefs.put(Prefs.WALL, "p:" + id);
            listWalls(true);
            return;
        }
        prefs.putLong(Prefs.WALL_AT, System.currentTimeMillis());
        showWall(i, null);
    }

    // ---- Clock, sun and staying awake ------------------------------------------------------

    private void tick() {
        Date now = new Date();
        boolean h24 = hub.is24h();
        String time = DateFormat.format(h24 ? "HH:mm" : "h:mm", now).toString();
        String ampm = h24 ? "" : DateFormat.format("a", now).toString().toUpperCase(Locale.getDefault());
        String date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now).toString();
        scene.setClock(time, ampm, date);
        scene.setClock24(h24);
        Calendar c = Calendar.getInstance();
        scene.setHour(c.get(Calendar.HOUR_OF_DAY), prefs.city);
        TimeZone zone = TimeZone.getDefault();
        long[] sun = Sun.times(now.getTime(), prefs.lat, prefs.lon, zone);
        scene.setSun(sun[0], sun[1], Sun.moonPhase(now.getTime()));
        if (view != null) view.kick();
    }

    /**
     * Inside the awake hours (7:00 to 0:30 unless changed on the phone) the screen stays on, so
     * Fire TV's screensaver and sleep never take over a screen you are using as a lamp; Lucent
     * dims itself when nobody touches the remote. Outside them Fire TV's own timers apply.
     */
    private void keepAwake() {
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean on = awake(prefs.get(Prefs.AWAKE_FROM, "07:00"), prefs.get(Prefs.AWAKE_TO, "00:30"), m);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Whether minute {@code m} of the day falls in from..to, which may run past midnight. */
    static boolean awake(String from, String to, int m) {
        if ("always".equals(from) || "always".equals(to)) return true;
        int f = Prefs.minutes(from), t = Prefs.minutes(to);
        if (f < 0 || t < 0 || f == t) return true;
        return f < t ? m >= f && m < t : m >= f || m < t;
    }

    /**
     * At bedtime, if nobody has pressed a button for ten minutes, the stick goes to sleep (and
     * with HDMI-CEC the TV goes off). Once a night: wake it again and it stays awake.
     */
    private void bedtime() {
        int b = Prefs.minutes(prefs.get(Prefs.BEDTIME, "off"));
        if (b < 0 || !resumed) return;
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int since = (m - b + 1440) % 1440;
        if (since >= 120 || scene.idleFor(now) < 10 * MINUTE || Alarm.ringing() || Finder.get().snapshot().active()) return;
        long night = Sun.startOfDay(now - since * MINUTE, TimeZone.getDefault());
        if (slept == night) return;
        slept = night;
        Guard.sleep();
    }

    private boolean animationsOff() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    // ---- Sound route ---------------------------------------------------------------------------

    private void primeOutputs() {
        outputs.clear();
        try {
            for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) outputs.add(d.getId());
        } catch (RuntimeException ignored) {
            // the toasts are a courtesy
        }
    }

    private static boolean bluetooth(AudioDeviceInfo d) {
        return d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
    }

    private static String name(AudioDeviceInfo d) {
        String n = Text.clean(String.valueOf(d.getProductName()));
        if (n.length() > 32) n = n.substring(0, 32).trim();
        return n.isEmpty() || "null".equals(n) ? "Headphones" : n;
    }

    /** HDMI's formats, and Bluetooth headphones when they carry the sound. */
    private void refreshAudio() {
        Model.Audio a = Sound.read(this);
        try {
            for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!bluetooth(d)) continue;
                a.route = "Bluetooth";
                a.device = name(d);
            }
        } catch (RuntimeException ignored) {
            // HDMI it is
        }
        hub.audio = a;
        scene.setAudio(a);
        Model.Picture pic = Sound.picture(this);
        hub.picture = pic;
        scene.setPicture(pic);
        view.kick();
    }

    // ---- Settings sheet ----------------------------------------------------------------------

    private void options() {
        List<Model.Option> list = new ArrayList<>();
        list.add(option("look", "Look", new String[] {"auto", "day", "night"},
                new String[] {"Day and Night by the Sun", "Day", "Night"}, prefs.get(Prefs.MODE, "auto")));
        if (hub.samsung.connected()) {
            String bright = hub.picked(Prefs.GLARE_MODE);
            list.add(option("glare", "Glare", new String[] {"off", "on"}, new String[] {"Off", "On, " + bright + " Picture"},
                    hub.glare() ? "on" : "off"));
            list.add(choice(Prefs.PICTURE_FOLLOW, "TV Picture Follows the Sun", new String[] {"off", "on"}, new String[] {"No",
                "Yes, " + hub.picked(Prefs.DAY_MODE) + " by Day, " + hub.picked(Prefs.NIGHT_MODE) + " at Night"}));
        } else {
            list.add(new Model.Option("glarehelp", "Glare", null, new String[] {"Connect the Samsung in Phone Settings"}));
        }
        String[] wallValues = new String[walls.size()], wallNames = new String[walls.size()];
        for (int i = 0; i < walls.size(); i++) {
            wallValues[i] = walls.get(i).id;
            wallNames[i] = walls.get(i).title;
        }
        if (walls.size() > 0) list.add(option(Prefs.WALL, "Wallpaper", wallValues, wallNames, wallId));
        list.add(choice(Prefs.ALBUM, "Photos", new String[] {"both", "nature", "ours"},
                new String[] {"Ours and Nature", "Nature", "Ours Only"}));
        list.add(choice(Prefs.ROTATE, "Change Wallpaper", new String[] {"1m", "5m", "10m", "hour", "day", "off"},
                new String[] {"Every Minute", "Every 5 Minutes", "Every 10 Minutes", "Every Hour", "Every Day", "Never"}));
        list.add(choice(Prefs.QUALITY, "Wallpaper Motion", new String[] {"motion", "still"},
                new String[] {"Slow Drift", "Still and Sharpest"}));
        list.add(choice(Prefs.DIM, "Dim When Idle", new String[] {"5", "10", "30", "off"},
                new String[] {"After 5 Minutes", "After 10 Minutes", "After 30 Minutes", "Never"}));
        list.add(choice(Prefs.AMBIENT, "Apps Go Back Down", new String[] {"2", "5", "15", "off"},
                new String[] {"After 2 Idle Minutes", "After 5 Idle Minutes", "After 15 Idle Minutes", "Never"}));
        list.add(choice(Prefs.MOTION, "Motion", new String[] {"full", "reduced"}, new String[] {"Full", "Reduced"}));
        list.add(choice(Prefs.CLOCK, "Clock", new String[] {"auto", "12", "24"}, new String[] {"System", "12-Hour", "24-Hour"}));
        list.add(choice(Prefs.UNITS, "Weather", new String[] {"f", "c", "off"}, new String[] {"°F, with °C", "°C, with °F", "Off"}));
        stationOption(list);
        list.add(choice(Prefs.ALARM, "When an E-Bike Is Found", new String[] {"ring", "chime"},
                new String[] {"Ring Until Stopped", "Chime Once"}));
        list.add(choice(Prefs.ALARM_FOR, "Alarm Rings For", new String[] {"2", "5", "10", "30"},
                new String[] {"Up to 2 Minutes", "Up to 5 Minutes", "Up to 10 Minutes", "Up to 30 Minutes"}));
        list.add(choice(Prefs.FIND_FOR, "Bike Finder Gives Up", new String[] {"15", "30", "60", "120"},
                new String[] {"After 15 Minutes", "After 30 Minutes", "After an Hour", "After 2 Hours"}));
        list.add(choice(Prefs.SKY, "Planes Overhead", new String[] {"on", "off"}, new String[] {"Show", "Hide"}));
        list.add(choice(Prefs.WAKE_NOTES, "Notes Wake the TV", new String[] {"never", "day", "always"},
                new String[] {"Never", "In the Daytime", "Always"}));
        List<String> urls = hub.console.urls();
        list.add(new Model.Option("pair", "Phone Control", null,
                new String[] {urls.isEmpty() ? "Pair a Phone" : "Pair a Phone  ·  " + urls.get(0).replace("http://", "")}));
        list.add(option("access", "Phone Control Works On", new String[] {"lan", "tailnet", "off"},
                new String[] {"Home Wi-Fi and Tailscale", "Tailscale Only", "Off"}, prefs.get(Prefs.ACCESS, "lan")));
        int paired = hub.keys.count();
        if (paired > 0) {
            list.add(new Model.Option("forget", "Paired Phones", null,
                    new String[] {paired + (paired == 1 ? " Phone, Forget It" : " Phones, Forget All")}));
        }
        list.add(new Model.Option("note", "Write a Note", null, new String[] {"Type or Speak"}));
        if (!prefs.get(Prefs.WIFI_SSID, "").isEmpty()) {
            list.add(new Model.Option("wifi", "Guest Wi-Fi", null, new String[] {"Show the Code"}));
        }
        list.add(new Model.Option("bluetooth", "Bluetooth Headphones", null, new String[] {"Pair or Switch"}));
        int hidden = prefs.set(Prefs.HIDDEN).size();
        list.add(new Model.Option("unhide", "Hidden Apps", null, new String[] {hidden == 0 ? "None" : hidden + " Hidden, Show All"}));
        list.add(option("takeover", "Lucent Opens on HOME", new String[] {"on", "off"}, new String[] {"Yes", "No, Use Fire TV's Home"},
                prefs.get(Prefs.TAKEOVER, "on")));
        list.add(new Model.Option("sleep", "Sleep", null, new String[] {"Turn Off the TV Now"}));
        list.add(new Model.Option("fire", "Fire TV Settings", null, new String[] {""}));
        list.add(new Model.Option("amazon", "Fire TV Home", null, new String[] {"Until You Open an App"}));
        list.add(new Model.Option("about", "Lucent " + BuildInfo.VERSION, null, new String[] {"Remote Help"}));
        scene.setOptions(list);
    }

    /** The dock bike finder watches: Westgate unless chosen otherwise, then the nearest others. */
    private void stationOption(List<Model.Option> list) {
        Model.Bikes bikes = hub.bikes;
        List<String> values = new ArrayList<>(), names = new ArrayList<>();
        values.add("auto");
        names.add("Westgate");
        String current = prefs.station();
        if (bikes != null) {
            String west = bikes.main() != null && "auto".equals(current) ? bikes.main().id : null;
            for (Model.Station s : bikes.nearby) {
                if (s.id.equals(west) || values.contains(s.id)) continue;
                values.add(s.id);
                names.add(Gbfs.shortName(s, false));
            }
        }
        if (!values.contains(current)) {
            values.add(current);
            names.add(bikes != null && bikes.main() != null && bikes.main().id.equals(current)
                    ? Gbfs.shortName(bikes.main(), false) : "Chosen Station");
        }
        list.add(option(Prefs.STATION, "Bike Station", values.toArray(new String[0]), names.toArray(new String[0]), current));
        // The second dock: bike finder rings for whichever of the two gets an e-bike first.
        List<String> v2 = new ArrayList<>(), n2 = new ArrayList<>();
        v2.add("auto");
        n2.add("Erie St, by Flour");
        String second = prefs.station2();
        if (bikes != null) {
            for (Model.Station s : bikes.nearby) {
                if (s.id.equals(bikes.altId) && "auto".equals(second) || v2.contains(s.id)) continue;
                v2.add(s.id);
                n2.add(Gbfs.shortName(s, false));
            }
        }
        if (!v2.contains(second) && !"none".equals(second)) {
            v2.add(second);
            n2.add("Chosen Station");
        }
        v2.add("none");
        n2.add("None, Only the First");
        list.add(option(Prefs.STATION2, "Also Watch", v2.toArray(new String[0]), n2.toArray(new String[0]), second));
    }

    private Model.Option choice(String key, String title, String[] values, String[] names) {
        return option(key, title, values, names, prefs.choice(key));
    }

    private static Model.Option option(String key, String title, String[] values, String[] names, String current) {
        Model.Option o = new Model.Option(key, title, values, names);
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) o.index = i;
        return o;
    }

    private static long minutes(String v) {
        if ("off".equals(v)) return 0;
        try {
            return Long.parseLong(v) * MINUTE;
        } catch (NumberFormatException e) {
            return 10 * MINUTE;
        }
    }

    private static long rotateMs(String v) {
        if ("1m".equals(v)) return MINUTE;
        if ("5m".equals(v)) return 5 * MINUTE;
        if ("10m".equals(v)) return 10 * MINUTE;
        if ("hour".equals(v)) return 60 * MINUTE;
        if ("day".equals(v)) return 24 * 60 * MINUTE;
        return 0;
    }

    // ---- Scene.Host --------------------------------------------------------------------------

    @Override
    public void launch(Model.App app) {
        Catalog.Entry e = entries.get(app.id);
        if (e == null) return;
        start(new Intent(e.intent), app.id);
    }

    @Override
    public void appInfo(Model.App app) {
        open("App Info is in Fire TV Settings, Applications",
                system(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.id, null))));
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home; the TV
     * settings entry point opens the real settings screen (found by dumbtv).
     */
    @Override
    public void fireSettings() {
        open("Could not open Fire TV Settings", system(new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")),
                system(new Intent(Settings.ACTION_SETTINGS)));
    }

    /**
     * The intent pinned to the system app that answers it, or null. Any app can claim a settings
     * action, so an app you installed could otherwise show a lookalike Settings screen.
     */
    private Intent system(Intent i) {
        try {
            PackageManager pm = getPackageManager();
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
                android.content.pm.ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.applicationInfo == null) continue;
                if ((ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                return new Intent(i).setClassName(ai.packageName, ai.name);
            }
        } catch (RuntimeException ignored) {
            // nothing to open
        }
        return null;
    }

    @Override
    public void amazonHome() {
        Guard.amazon(this);
    }

    private boolean start(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** The first of these screens that opens, or a short message. */
    private void open(String failed, Intent... tries) {
        for (Intent i : tries) {
            if (i != null && start(i, null)) return;
        }
        Toast.makeText(this, failed, Toast.LENGTH_LONG).show();
    }

    @Override
    public void option(String key, String value) {
        if ("look".equals(key)) {
            hub.setMode(value);
        } else if ("glare".equals(key)) {
            // The toast comes when the Samsung has switched (see settingChanged).
            hub.glare("on".equals(value));
        } else if (Prefs.PICTURE_FOLLOW.equals(key)) {
            prefs.put(key, value);
            hub.picture(hub.night, true);
            hub.post(Hub.SETTINGS, key);
        } else if ("access".equals(key)) {
            // Who may reach the control center changes only here, on the TV.
            prefs.put(Prefs.ACCESS, value);
            hub.console.setAccess(hub.accessMode());
            hub.console.refresh();
        } else if ("takeover".equals(key)) {
            Boot.takeover(this, "on".equals(value));
            if (!"on".equals(value)) {
                Toast.makeText(this, "Fire TV's home will open on HOME. Open Lucent from Your Apps to switch back.", Toast.LENGTH_LONG)
                        .show();
            }
        } else if (Prefs.WALL.equals(key)) {
            prefs.putLong(Prefs.WALL_AT, System.currentTimeMillis());
            showWall(Walls.indexOf(walls, value), null);
        } else if (Prefs.STATION.equals(key) || Prefs.STATION2.equals(key)) {
            prefs.put(key, value);
            hub.live.setStation(prefs.station());
            hub.live.setStation2(prefs.station2());
            hub.live.refreshBikes();
        } else if (Prefs.allowed(key, value)) {
            prefs.put(key, value);
            hub.applySettings();
            if (Prefs.UNITS.equals(key) || Prefs.CLOCK.equals(key)) hub.reweather();
            // Through the hub, so a phone looking at the control center sees the change too.
            hub.post(Hub.SETTINGS, key);
        }
        view.kick();
    }

    @Override
    public void order(List<String> ids) {
        prefs.putList(Prefs.ORDER, ids);
    }

    @Override
    public void hide(String id) {
        prefs.hide(id);
        Model.App a = artCache.get(id);
        scene.toast((a == null ? "App" : a.label) + " hidden", "Settings, Hidden Apps brings it back", Scene.TOAST_INFO,
                System.currentTimeMillis());
        loadApps();
    }

    @Override
    public void unhideAll() {
        prefs.unhideAll();
        loadApps();
    }

    @Override
    public void sound(boolean click) {
        view.feedback(click);
    }

    @Override
    public void findBike() {
        if (!hub.findBike()) {
            scene.toast("Asking Bluebikes", "Bike finder starts in a moment", Scene.TOAST_INFO, System.currentTimeMillis());
        }
    }

    @Override
    public void stopFinding() {
        hub.stopFinding();
    }

    @Override
    public void dismissFinder() {
        Finder.get().dismiss();
    }

    @Override
    public void silenceAlarm() {
        Alarm.silence();
    }

    @Override
    public void media(int what) {
        if (what == Scene.MEDIA_NEXT) hub.media.next();
        else if (what == Scene.MEDIA_PREV) hub.media.previous();
        else hub.media.toggle();
    }

    @Override
    public void wallpaper(int dir) {
        if (walls.isEmpty()) return;
        prefs.putLong(Prefs.WALL_AT, System.currentTimeMillis());
        int n = walls.size();
        showWall(((savedWall() + dir) % n + n) % n, null);
    }

    /**
     * Apple devices: the AirPlay receiver app if one is installed (AirReceiver or AirScreen from
     * the Appstore). Android and Windows: Fire TV's own Miracast screen.
     */
    @Override
    public void mirror(boolean apple) {
        if (apple) {
            Catalog.Entry e = airplayEntry();
            if (e != null) {
                start(new Intent(e.intent), e.pkg);
            } else {
                scene.toast("No AirPlay app yet", "Install AirReceiver or AirScreen from the Appstore", Scene.TOAST_INFO,
                        System.currentTimeMillis());
            }
            return;
        }
        open("Mirroring is in Fire TV Settings, Display and Sounds", system(new Intent().setComponent(MIRACAST)),
                system(new Intent("android.settings.CAST_SETTINGS")));
    }

    @Override
    public void pairing(boolean open) {
        if (!open) {
            hub.closePairing();
            return;
        }
        long now = System.currentTimeMillis();
        if (hub.accessMode() == Console.OFF) {
            scene.toast("Phone control is off", "Settings, Phone Control Works On", Scene.TOAST_INFO, now);
            return;
        }
        String[] w = hub.openPairing();
        if (w == null) {
            scene.toast("No network address yet", "Check the TV's Wi-Fi and try again", Scene.TOAST_INFO, now);
            return;
        }
        scene.setPairing(w, view.gfx(), Qr.encode(w[0]), now + Keys.WINDOW_MS);
    }

    @Override
    public void decide(String id, boolean allow) {
        hub.decide(id, allow);
        if (allow) scene.toast("Phone connected", "It can reach Lucent from now on", Scene.TOAST_PHONE, System.currentTimeMillis());
        options();
    }

    @Override
    public void action(String key) {
        long now = System.currentTimeMillis();
        switch (key) {
            case "bluetooth":
                open("Bluetooth is in Fire TV Settings, Controllers and Bluetooth Devices", system(new Intent().setComponent(BLUETOOTH)),
                        system(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
                break;
            case "wifi":
                hub.wifiUntil = now + 120000L;
                hub.post(Hub.WIFI, null);
                break;
            case "wifi-hide":
                hub.wifiUntil = 0;
                break;
            case "note":
                writeNote();
                break;
            case "ask":
                ask();
                break;
            case "forget":
                hub.keys.revokeAll(now);
                scene.toast("Phones forgotten", "Pair again from Phone Control", Scene.TOAST_PHONE, now);
                options();
                break;
            case "sleep":
                if (!Guard.sleep()) scene.toast("Sleep needs Lucent's HOME service", "Run install.sh again", Scene.TOAST_INFO, now);
                break;
            case "glarehelp":
                scene.toast("Glare needs the Samsung connected", "On your phone: Settings, Samsung Picture", Scene.TOAST_INFO, now);
                break;
            case "about":
                scene.toast("Play/Pause plays music, Rewind and Fast Forward change the photo",
                        "Hold OK for more  ·  Menu for Settings  ·  Back to dismiss", Scene.TOAST_INFO, now);
                break;
            default:
                break;
        }
        view.kick();
    }

    /**
     * A question for the assistant from the remote. The Fire TV keyboard's microphone (where this
     * model offers it) turns speech into the text; a phone's keyboard microphone always does.
     */
    private void ask() {
        if (!hub.assistant.claude.hasKey()) {
            scene.toast("Ask Lucent needs a Claude API key", "On your phone: Settings, Claude", Scene.TOAST_INFO, System.currentTimeMillis());
            view.kick();
            return;
        }
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        field.setHint("Ask anything, or say what to play");
        field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(Claude.MAX_QUESTION)});
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ask Lucent")
                .setView(field)
                .setPositiveButton("Ask", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        send(field.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        field.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int action, android.view.KeyEvent e) {
                if (action != EditorInfo.IME_ACTION_SEND && action != EditorInfo.IME_ACTION_DONE) return false;
                dialog.dismiss();
                send(field.getText().toString());
                return true;
            }
        });
        dialog.show();
    }

    private void send(String text) {
        if (Text.clean(text).isEmpty()) return;
        try {
            hub.assistant.ask(text, "the TV");
        } catch (Claude.Failed f) {
            scene.toast("Couldn't ask", f.getMessage(), Scene.TOAST_INFO, System.currentTimeMillis());
            view.kick();
        }
    }

    /**
     * A note from the remote. The Fire TV keyboard's microphone (or holding the remote's voice
     * button while the keyboard is open, on remotes that offer it) turns speech into the text.
     */
    private void writeNote() {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setHint("A note for the home screen");
        field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(Notes.MAX_TEXT)});
        new AlertDialog.Builder(this)
                .setTitle("Write a Note")
                .setView(field)
                .setPositiveButton("Put It Up", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String text = Text.clean(field.getText().toString());
                        if (text.isEmpty()) return;
                        Notes.Note n = new Notes.Note();
                        n.text = text.length() > Notes.MAX_TEXT ? text.substring(0, Notes.MAX_TEXT) : text;
                        n.from = "The TV";
                        n.at = System.currentTimeMillis();
                        n.paper = 1;
                        n.pinned = true;
                        hub.addNote(n);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
