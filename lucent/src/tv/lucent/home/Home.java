package tv.lucent.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home activity. Owns the {@link Scene}, feeds it apps, wallpaper, clock, weather and sound
 * capabilities, and carries out what the scene asks for. The HOME button reaches it through
 * {@link Guard}; it also answers the HOME intent so Fire OS lists it as a launcher.
 */
public final class Home extends Activity implements Scene.Host, Live.Sink, Finder.Listener {
    private static final long WEATHER_MAX_AGE = 15 * 60000L;
    private static final long MINUTE = 60000L;

    /** Whether the home screen is the app in front; the guard's alerts stay out of its way. */
    static volatile boolean inFront;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService art = Executors.newSingleThreadExecutor();
    /** Weather has its own thread so a slow network never holds up wallpapers or the app list. */
    private final ExecutorService web = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private Scene scene;
    private HomeView view;
    private List<Walls.Item> walls = new ArrayList<>();
    private volatile int wallIndex = -1;
    private int appsGeneration;
    private boolean weatherBusy;
    private final Map<String, Catalog.Entry> entries = new HashMap<>();
    private final Map<String, Model.App> artCache = new HashMap<>();
    private long bannersSeen;
    private Live live;
    private Model.Bikes bikes;

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
            scene.setAudio(Sound.read(Home.this));
            view.kick();
        }
    };

    private final Runnable minutely = new Runnable() {
        @Override
        public void run() {
            maybeRotate(false);
            maybeWeather(false);
            ui.postDelayed(this, MINUTE);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Crash.watch(this);
        prefs = new Prefs(this);
        scene = new Scene(this);
        scene.setReduced(prefs.reduced() || animationsOff());
        scene.setAmbientAfter(ambientMs(prefs.get(Prefs.AMBIENT, "5")));
        scene.setWeatherOn(prefs.weatherOn());
        view = new HomeView(this, scene);
        setContentView(view);
        walls = Walls.list(prefs.media());
        options();
        tick();
        String saved = prefs.get(Prefs.WEATHER_JSON, null);
        if (saved != null) showWeather(saved, prefs.getLong(Prefs.WEATHER_AT));
        live = new Live(this, this);
        live.setStation(prefs.station());
        live.setSky(!"off".equals(prefs.get(Prefs.SKY, "on")), Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
        scene.setSkyOn(!"off".equals(prefs.get(Prefs.SKY, "on")));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        bannersSeen = bannersStamp();
        loadApps();
        loadWall(currentWallIndex());
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        Guard.homeInFront();
        Guard.arrived(this);
        boolean moved = prefs.readConf();
        live.setStation(prefs.station());
        // A tile picture pushed into banners/ shows the next time you come home.
        long seen = bannersStamp();
        if (seen != bannersSeen) {
            bannersSeen = seen;
            artCache.clear();
            loadApps();
        }
        live.resume();
        List<Walls.Item> fresh = Walls.list(prefs.media());
        if (fresh.size() != walls.size()) {
            walls = fresh;
            options();
        }
        Finder.get().listen(this);
        scene.setFinder(Finder.get().snapshot(), Finder.get().station(), System.currentTimeMillis());
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        registerReceiver(hdmi, new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));
        scene.setReduced(prefs.reduced() || animationsOff());
        scene.setAudio(Sound.read(this));
        tick();
        scene.enter(System.currentTimeMillis());
        maybeRotate(false);
        maybeWeather(moved);
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
        live.pause();
        Finder.get().unlisten(this);
        if (Finder.get().snapshot().active()) Guard.remind();
        view.stop();
        ui.removeCallbacks(minutely);
        try {
            unregisterReceiver(time);
            unregisterReceiver(hdmi);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packages);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        io.shutdownNow();
        art.shutdownNow();
        web.shutdownNow();
        live.shutdown();
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

    // ---- Content -----------------------------------------------------------------------------

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
                } catch (RuntimeException e) {
                    found = new ArrayList<>();
                }
                final List<Catalog.Entry> all = found;
                final List<Catalog.Entry> dock = Catalog.dock(all, order, hidden, shown);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != appsGeneration || isDestroyed()) return;
                        entries.clear();
                        List<Model.App> list = new ArrayList<>();
                        final List<Model.App> needArt = new ArrayList<>();
                        for (Catalog.Entry e : dock) {
                            entries.put(e.pkg, e);
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

    private int currentWallIndex() {
        int n = walls.size();
        if (n == 0) return -1;
        try {
            return Math.max(0, Math.min(n - 1, Integer.parseInt(prefs.get(Prefs.WALL, "0"))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void loadWall(final int index) {
        if (index < 0 || index >= walls.size()) return;
        wallIndex = index;
        final Walls.Item item = walls.get(index);
        final int w = Math.max(1280, getResources().getDisplayMetrics().widthPixels);
        final int h = Math.max(720, getResources().getDisplayMetrics().heightPixels);
        io.execute(new Runnable() {
            @Override
            public void run() {
                // Flicking through wallpapers in Settings queues decodes; only the last one counts.
                if (index != wallIndex) return;
                final Model.Wall wall = Walls.load(Home.this, item, w, h);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (wall == null || isDestroyed() || index != wallIndex) return;
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

    /** Moves to the next wallpaper when the chosen interval has passed. */
    private void maybeRotate(boolean force) {
        long every = rotateMs(prefs.get(Prefs.ROTATE, "day"));
        long now = System.currentTimeMillis();
        long at = prefs.getLong(Prefs.WALL_AT);
        if (at == 0) {
            prefs.putLong(Prefs.WALL_AT, now);
            return;
        }
        if (!force && (every <= 0 || now - at < every)) return;
        int next = (currentWallIndex() + 1) % Math.max(1, walls.size());
        prefs.put(Prefs.WALL, String.valueOf(next));
        prefs.putLong(Prefs.WALL_AT, now);
        loadWall(next);
        options();
    }

    private void maybeWeather(boolean force) {
        if (!prefs.weatherOn() || weatherBusy) return;
        long at = prefs.getLong(Prefs.WEATHER_AT);
        if (!force && System.currentTimeMillis() - at < WEATHER_MAX_AGE) return;
        weatherBusy = true;
        final String url = Weather.url(prefs.lat, prefs.lon);
        web.execute(new Runnable() {
            @Override
            public void run() {
                final String json = Net.text(url, 8000, false, Net.LIMIT_TINY);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        weatherBusy = false;
                        if (json == null || isDestroyed()) return;
                        long now = System.currentTimeMillis();
                        Model.Weather w = Weather.parse(json, prefs.city, is24h(), now, prefs.celsiusFirst());
                        if (!w.ok) return;
                        prefs.put(Prefs.WEATHER_JSON, json);
                        prefs.putLong(Prefs.WEATHER_AT, now);
                        showWeather(json, now);
                    }
                });
            }
        });
    }

    private void showWeather(String json, long at) {
        Model.Weather w = Weather.parse(json, prefs.city, is24h(), at, prefs.celsiusFirst());
        // A forecast older than six hours is worse than none.
        if (System.currentTimeMillis() - at > 6 * 60 * MINUTE) w.ok = false;
        scene.setWeather(w);
        if (view != null) view.kick();
    }

    private void tick() {
        Date now = new Date();
        boolean h24 = is24h();
        String time = DateFormat.format(h24 ? "HH:mm" : "h:mm", now).toString();
        String ampm = h24 ? "" : DateFormat.format("a", now).toString().toUpperCase(Locale.getDefault());
        String date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now).toString();
        scene.setClock(time, ampm, date);
        scene.setClock24(h24);
        if (live != null) live.setClock24(h24);
        if (view != null) view.kick();
    }

    private boolean is24h() {
        String c = prefs.get(Prefs.CLOCK, "auto");
        return "24".equals(c) || "auto".equals(c) && DateFormat.is24HourFormat(this);
    }

    private boolean animationsOff() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    // ---- Settings sheet ----------------------------------------------------------------------

    private void options() {
        List<Model.Option> list = new ArrayList<>();
        String[] wallValues = new String[walls.size()], wallNames = new String[walls.size()];
        for (int i = 0; i < walls.size(); i++) {
            wallValues[i] = String.valueOf(i);
            wallNames[i] = walls.get(i).title;
        }
        if (walls.size() > 0) list.add(option(Prefs.WALL, "Wallpaper", wallValues, wallNames, String.valueOf(currentWallIndex())));
        list.add(option(Prefs.ROTATE, "Change Wallpaper", new String[] {"off", "10m", "hour", "day"},
                new String[] {"Never", "Every 10 Minutes", "Every Hour", "Every Day"}, prefs.get(Prefs.ROTATE, "day")));
        list.add(option(Prefs.MOTION, "Motion", new String[] {"full", "reduced"}, new String[] {"Full", "Reduced"},
                prefs.get(Prefs.MOTION, "full")));
        list.add(option(Prefs.CLOCK, "Clock", new String[] {"auto", "12", "24"}, new String[] {"System", "12-Hour", "24-Hour"},
                prefs.get(Prefs.CLOCK, "auto")));
        list.add(option(Prefs.UNITS, "Weather", new String[] {"f", "c", "off"}, new String[] {"°F, with °C", "°C, with °F", "Off"},
                prefs.get(Prefs.UNITS, "f")));
        stationOption(list);
        list.add(option(Prefs.FIND_FOR, "Bike Finder Gives Up", new String[] {"15", "30", "60", "120"},
                new String[] {"After 15 Minutes", "After 30 Minutes", "After an Hour", "After 2 Hours"}, prefs.get(Prefs.FIND_FOR, "30")));
        list.add(option(Prefs.SKY, "Planes Overhead", new String[] {"on", "off"}, new String[] {"Show", "Hide"},
                prefs.get(Prefs.SKY, "on")));
        list.add(option(Prefs.AMBIENT, "Ambient Mode", new String[] {"2", "5", "15", "off"},
                new String[] {"After 2 Minutes", "After 5 Minutes", "After 15 Minutes", "Off"}, prefs.get(Prefs.AMBIENT, "5")));
        int hidden = prefs.set(Prefs.HIDDEN).size();
        list.add(new Model.Option("unhide", "Hidden Apps", null,
                new String[] {hidden == 0 ? "None" : hidden + " Hidden, Show All"}));
        list.add(new Model.Option("fire", "Fire TV Settings", null, new String[] {""}));
        list.add(new Model.Option("amazon", "Amazon Home", null, new String[] {"Until You Open an App"}));
        scene.setOptions(list);
    }

    /** The dock bike finder watches: Westgate unless chosen otherwise, then the nearest others. */
    private void stationOption(List<Model.Option> list) {
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
    }

    private static Model.Option option(String key, String title, String[] values, String[] names, String current) {
        Model.Option o = new Model.Option(key, title, values, names);
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) o.index = i;
        return o;
    }

    private static long ambientMs(String v) {
        if ("off".equals(v)) return 0;
        try {
            return Long.parseLong(v) * MINUTE;
        } catch (NumberFormatException e) {
            return 5 * MINUTE;
        }
    }

    private static long rotateMs(String v) {
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
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.id, null)), null);
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home; the TV
     * settings entry point opens the real settings screen (found by dumbtv).
     */
    @Override
    public void fireSettings() {
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS");
        start(tv.resolveActivity(getPackageManager()) != null ? tv : new Intent(Settings.ACTION_SETTINGS), null);
    }

    @Override
    public void amazonHome() {
        Guard.amazon(this);
    }

    private void start(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void option(String key, String value) {
        prefs.put(key, value);
        if (Prefs.WALL.equals(key)) {
            prefs.putLong(Prefs.WALL_AT, System.currentTimeMillis());
            try {
                loadWall(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                // not a wallpaper index
            }
        } else if (Prefs.MOTION.equals(key)) {
            scene.setReduced(prefs.reduced() || animationsOff());
        } else if (Prefs.CLOCK.equals(key)) {
            tick();
            String saved = prefs.get(Prefs.WEATHER_JSON, null);
            if (saved != null) showWeather(saved, prefs.getLong(Prefs.WEATHER_AT));
        } else if (Prefs.UNITS.equals(key)) {
            boolean on = !"off".equals(value);
            scene.setWeatherOn(on);
            String saved = prefs.get(Prefs.WEATHER_JSON, null);
            if (on && saved != null) showWeather(saved, prefs.getLong(Prefs.WEATHER_AT));
            if (on) maybeWeather(false);
        } else if (Prefs.STATION.equals(key)) {
            live.setStation(prefs.station());
            live.refreshBikes();
        } else if (Prefs.SKY.equals(key)) {
            boolean on = !"off".equals(value);
            live.setSky(on, Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
            scene.setSkyOn(on);
        } else if (Prefs.AMBIENT.equals(key)) {
            scene.setAmbientAfter(ambientMs(value));
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
        Toast.makeText(this, (a == null ? "App" : a.label) + " hidden. Settings, Hidden Apps brings it back.",
                Toast.LENGTH_LONG).show();
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
        Model.Station s = bikes == null ? null : bikes.main();
        if (s == null) {
            Toast.makeText(this, "Bluebikes is not answering yet. Try again in a moment.", Toast.LENGTH_SHORT).show();
            return;
        }
        String label = "auto".equals(prefs.station()) ? "Westgate" : Gbfs.shortName(s, false);
        Finder.get().start(s, label, prefs.findForMs());
    }

    @Override
    public void stopFinding() {
        Finder.get().stop();
    }

    @Override
    public void dismissFinder() {
        Finder.get().dismiss();
    }

    // ---- Live data ---------------------------------------------------------------------------

    @Override
    public void bikes(Model.Bikes b) {
        boolean first = bikes == null;
        bikes = b;
        scene.setBikes(b);
        if (first) options();
        view.kick();
    }

    @Override
    public void transit(Model.Transit t) {
        scene.setTransit(t);
        view.kick();
    }

    @Override
    public void sky(Model.Sky s) {
        scene.setSky(s);
        view.kick();
    }

    @Override
    public void onFinder(Model.Finder f, Model.Station s) {
        scene.setFinder(f, s, System.currentTimeMillis());
        view.kick();
    }
}
