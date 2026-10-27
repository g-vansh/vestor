package tv.lucent.home;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Renders the real {@link Scene} to PNGs on a desktop JVM, driving it with the same key codes
 * the remote sends, fed by the same parsers the TV uses reading the fixtures in
 * preview/fixtures. App art here is stand-in; on the TV it is each app's own banner.
 */
public final class Preview {
    private static final File ROOT = new File(System.getProperty("lucent.root", "."));

    private static final long NOW = 1793124300000L;
    private static final File FIX = new File(ROOT, "preview/fixtures");

    public static void main(String[] args) throws Exception {
        // The fixtures were recorded at 2:05 PM in Cambridge (18:05 UTC): times read in Boston time.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
        File out = new File(args.length > 0 ? args[0] : "preview-out");
        out.mkdirs();
        String wallFile = args.length > 1 ? args[1] : "nature-01.jpg";
        int U = Scene.UP, D = Scene.DOWN, L = Scene.LEFT, R = Scene.RIGHT, OK = Scene.OK, B = Scene.BACK;
        // Home rests on the dashboard: the first Left or Right wakes the bar, Down brings up the apps.
        shot(out, "01-home", wallFile, new int[0], 0);
        shot(out, "02-glance-bikes", wallFile, new int[] {R}, 0);
        shot(out, "03-glance-weather", wallFile, new int[] {L, L}, 0);
        shot(out, "04-glance-shuttles", wallFile, new int[] {R, R}, 0);
        shot(out, "05-glance-sky", wallFile, new int[] {R, R, R}, 0);
        shot(out, "06-bikes-panel-finding", wallFile, new int[] {OK}, 3);
        shot(out, "07-weather-panel", wallFile, new int[] {L, L, OK}, 0);
        shot(out, "08-shuttles-panel", wallFile, new int[] {R, R, OK}, 0);
        shot(out, "09-sky-panel", wallFile, new int[] {R, R, R, OK}, 0);
        shot(out, "32-bikes-panel", wallFile, new int[] {R, OK, OK}, 0);
        shot(out, "33-shuttles-grocery-friday", wallFile, new int[] {R, R, OK}, 15);
        shot(out, "10-finding-home", wallFile, new int[] {OK, B, B}, 3);
        shot(out, "11-found-home", wallFile, new int[0], 4);
        shot(out, "12-apps", wallFile, new int[] {D}, 0);
        shot(out, "13-settings", wallFile, new int[] {U, R, R, R, R, R, OK, D}, 0);
        shot(out, "14-dock-scrolled", wallFile, new int[] {D, R, R, R, R}, 0);
        shot(out, "15-sound", wallFile, new int[] {U, L, L, L, L, R, R, R, OK}, 0);
        shot(out, "18-home-forest", "nature-02.jpg", new int[] {R}, 0);
        shot(out, "19-home-night", "nature-04.jpg", new int[0], 0);
        shot(out, "20-day", "nature-11.jpg", new int[0], 5);
        shot(out, "21-note", "nature-06.jpg", new int[0], 6);
        shot(out, "22-bike-alarm", "nature-04.jpg", new int[0], 7);
        shot(out, "23-pair-phone", "nature-01.jpg", new int[0], 8);
        shot(out, "24-plans-and-music", "nature-03.jpg", new int[] {L, L, L}, 9);
        shot(out, "25-plans-panel", "nature-03.jpg", new int[] {R, R, R, OK}, 5);
        shot(out, "26-mirror", "nature-10.jpg", new int[] {U, L, L, L, L, R, OK}, 0);
        shot(out, "27-allow-phone", "nature-04.jpg", new int[0], 11);
        shot(out, "28-headphones", "nature-05.jpg", new int[] {U, L, L, L, L, R, R, R, OK}, 12);
        shot(out, "29-hearts", "nature-04.jpg", new int[0], 13);
        shot(out, "30-guest-wifi", "nature-12.jpg", new int[0], 14);
        shot(out, "31-day-apps", "nature-11.jpg", new int[] {D}, 5);
        overlay(out, "16-alert-over-video", false);
        overlay(out, "17-reminder-over-video", true);
    }

    private static String fixture(String name) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(new File(FIX, name).toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Model.Bikes bikes(boolean docked) throws Exception {
        List<Model.Station> all = Gbfs.stations(fixture("gbfs_info.json"), Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
        // The recorded feed predates the Erie Street dock's current place: its nearest neighbour stands in.
        String west = Gbfs.westgate(all);
        String erie = Gbfs.erie(all);
        if (erie == null) erie = Gbfs.follow(all, west, 1).get(1).id;
        List<Model.Station> follow = Gbfs.follow(all, west, erie, 3);
        Gbfs.status(fixture(docked ? "gbfs_status_found.json" : "gbfs_status.json"), follow);
        Model.Bikes b = new Model.Bikes();
        b.stations.addAll(follow);
        if (erie != null) {
            b.altId = erie;
            b.altLabel = "Erie St";
        }
        b.fetched = NOW - 12000;
        b.ok = true;
        return b;
    }

    private static Model.Transit transit(boolean grocery) throws Exception {
        Gtfs.Static st = Gtfs.readStatic(java.nio.file.Files.readAllBytes(new File(FIX, "mit_gtfs.zip").toPath()));
        Model.Line tech = Transit.tech(), tech2 = Transit.tech2(), safe = Transit.saferide();
        Model.Line asm = Transit.toAssembly(), rug = Transit.toRuggles();
        Transit.mit(Gtfs.tripUpdates(java.nio.file.Files.readAllBytes(new File(FIX, "mit_trips.pb").toPath())), st, NOW, tech, tech2, safe);
        Transit.mbta(fixture("mbta_predictions.json"), NOW, asm, rug);
        Model.Transit t = new Model.Transit();
        t.lines.add(tech);
        t.lines.add(tech2);
        t.lines.add(safe);
        t.lines.add(asm);
        t.lines.add(rug);
        if (grocery) {
            // A grocery Friday: the Trader Joe's run due at Tang/Westgate.
            Model.Line g = Transit.grocery();
            g.minutes.add(12);
            g.minutes.add(71);
            t.lines.add(g);
        }
        // Daytime in the fixtures, so SafeRide has no bus: show it the way the TV would.
        safe.minutes.clear();
        Transit.idleNotes(t, NOW);
        t.fetched = NOW - 8000;
        t.ok = true;
        return t;
    }

    private static Model.Sky sky() throws Exception {
        Model.Sky s = Sky.aircraft(fixture("adsb.json"), Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
        String[] r = Sky.route(fixture("hexdb_route.json"));
        Model.Flight f = s.hero();
        String[] a = Sky.airport(fixture("hexdb_kbos.json"), r[0]), b = Sky.airport(fixture("hexdb_ksfo.json"), r[1]);
        f.from = a[0];
        f.fromCity = a[1];
        f.to = b[0];
        f.toCity = b[1];
        s.fetched = NOW - 5000;
        return s;
    }

    /**
     * extra: 1 = idle long enough for ambient, 3 = bike finder searching for 84 s,
     * 4 = bike finder just found an e-bike.
     */
    private static void shot(File out, String name, String wallFile, int[] keys, final int extra) throws Exception {
        final Scene[] holder = new Scene[1];
        final long[] clock = {NOW};
        final Model.Finder finder = new Model.Finder();
        Scene.Host host = new Scene.Host() {
            public void launch(Model.App app) {}
            public void appInfo(Model.App app) {}
            public void fireSettings() {}
            public void amazonHome() {}
            public void option(String key, String value) {}
            public void order(List<String> ids) {}
            public void hide(String id) {}
            public void unhideAll() {}
            public void sound(boolean click) {}
            public void findBike() {
                finder.state = Model.Finder.SEARCHING;
                finder.started = clock[0] - (extra == 3 ? 84000 : 0);
                finder.deadline = finder.started + 30 * 60000L;
                finder.station = "Westgate";
                finder.checks = 17;
                finder.lastCheck = clock[0] - 3000;
                holder[0].setFinder(finder.copy(), null, clock[0]);
            }
            public void stopFinding() {
                finder.state = Model.Finder.STOPPED;
                holder[0].setFinder(finder.copy(), null, clock[0]);
            }
            public void dismissFinder() {
                finder.state = Model.Finder.IDLE;
                holder[0].setFinder(finder.copy(), null, clock[0]);
            }
            public void silenceAlarm() {
                holder[0].setAlarm(false, null, null, clock[0]);
            }
            public void media(int what) {}
            public void wallpaper(int dir) {}
            public void mirror(boolean apple) {}
            public void pairing(boolean open) {}
            public void decide(String id, boolean allow) {}
            public void action(String key) {}
        };
        Scene scene = new Scene(host);
        holder[0] = scene;
        int w = 1920, h = 1080;
        scene.setSize(w, h, 2f);
        scene.setApps(apps());
        scene.setWall(wall(new File(ROOT, "assets/wallpapers/" + wallFile), "Moraine Lake", "Banff National Park, Canada"));
        Model.Weather weather = Weather.parse(fixture("weather.json"), "Cambridge", false, NOW - 9 * 60000L, false);
        // HRRR's two-hour rain line, as a showery afternoon would have it.
        weather.rain = "Rain from 3:45 PM";
        weather.wetSoon = true;
        scene.setWeather(weather);
        scene.setAudio(audio());
        scene.setPicture(picture());
        scene.setBikes(bikes(extra == 4));
        scene.setForecast(forecast(extra), NOW);
        scene.setTransit(transit(extra == 15));
        scene.setSky(sky());
        scene.setClock("2:05", "PM", "Tuesday, October 27");
        scene.setOptions(options());
        scene.setAmbientAfter(extra == 1 ? 1000 : 300000);
        scene.setDimAfter(extra == 1 ? 0 : 600000);
        scene.setHour(14, "Cambridge");
        scene.setSun(NOW - 7 * 3600000L, NOW + 3 * 3600000L + 1200000L, 0.42);
        scene.setUptime(1000000);
        // Day by default; night where asked for.
        scene.setNight(extra == 6 || extra == 7 || extra == 11 || extra == 13 || wallFile.equals("nature-04.jpg"), true);
        if (extra == 5 || extra == 9 || extra == 12) scene.setPlans(events(), java.util.Arrays.asList("Us", "Work"), countdowns(), items());
        if (extra == 9 || extra == 12) scene.setMedia(music());
        if (extra != 1) scene.setPinned(note(false));
        Model.Air air = new Model.Air();
        air.ok = true;
        air.aqi = 32;
        air.uv = 3.1;
        scene.setAir(air);
        long now = NOW;
        scene.enter(now);
        for (int i = 0; i < 90; i++) {
            now += 16;
            clock[0] = now;
            scene.step(1 / 60f, now);
        }
        if (extra == 4) {
            host.findBike();
            Model.Finder f = finder.copy();
            f.state = Model.Finder.FOUND;
            f.ebikes = 1;
            f.ended = now;
            f.checks = 23;
            Model.Station st = bikes(true).main();
            scene.setFinder(f, st, now);
        }
        if (extra == 6) scene.showNote(note(true), now);
        if (extra == 7) {
            Model.Finder f = finder.copy();
            f.state = Model.Finder.FOUND;
            f.ebikes = 2;
            f.ended = now;
            f.station = "Westgate";
            scene.setAlarm(true, f, bikes(true).main(), now);
        }
        if (extra == 8) {
            String[] window = {"http://192.168.1.23:8686/#k=Gk2b7Qv1p9XcR0aYt4LmWn8sHq3Ze6Uo", "482913", "http://192.168.1.23:8686"};
            BufferedImage scratch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            scene.setPairing(window, new AwtGfx(scratch.createGraphics(), new File(ROOT, "assets/fonts")), Qr.encode(window[0]),
                    now + Keys.WINDOW_MS - 37000);
        }
        if (extra == 11) scene.ask("p1", "Sam's iPhone", "441");
        if (extra == 12) {
            Model.Audio a = audio();
            a.route = "Bluetooth";
            a.device = "Sam's AirPods Pro";
            scene.setAudio(a);
            scene.toast("Sam's AirPods Pro connected", "Sound plays on Sam's AirPods Pro now", Scene.TOAST_EARBUDS, now);
        }
        if (extra == 13) scene.hearts("hearts", "Alex", now - 1400);
        if (extra == 14) {
            BufferedImage scratch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            scene.setWifi("Memorial Guest", new AwtGfx(scratch.createGraphics(), new File(ROOT, "assets/fonts")),
                    Qr.encode("WIFI:T:WPA;S:Memorial Guest;P:example-only;;"), now + 98000);
        }
        for (int k : keys) {
            scene.key(k, now);
            for (int i = 0; i < 30; i++) {
                now += 16;
                clock[0] = now;
                scene.step(1 / 60f, now);
            }
        }
        int settle = extra == 1 ? 240 : extra == 4 ? 75 : extra == 6 ? 200 : extra == 13 ? 40 : 90;
        for (int i = 0; i < settle; i++) {
            now += 16;
            clock[0] = now;
            scene.step(1 / 60f, now);
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        AwtGfx gfx = new AwtGfx(g, new File(ROOT, "assets/fonts"));
        long t = System.nanoTime();
        scene.draw(gfx, now);
        gfx.done();
        g.dispose();
        System.out.printf("%s drawn in %.1f ms%n", name, (System.nanoTime() - t) / 1e6);
        ImageIO.write(img, "png", new File(out, name + ".png"));
    }

    /** The guard's alert over a film: a dark frame stands in for the app underneath. */
    private static void overlay(File out, String name, boolean reminder) throws Exception {
        int w = 1920, h = 1080;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        BufferedImage film = ImageIO.read(new File(ROOT, "assets/wallpapers/nature-04.jpg"));
        g.drawImage(film, 0, 0, w, h, null);
        g.setColor(new Color(0, 0, 0, 70));
        g.fillRect(0, 0, w, h);
        AwtGfx gfx = new AwtGfx(g, new File(ROOT, "assets/fonts"));
        Model.Finder f = new Model.Finder();
        f.station = "Westgate";
        f.started = NOW - 11 * 60000L;
        f.deadline = f.started + 30 * 60000L;
        f.ended = NOW;
        f.ebikes = 1;
        f.checks = 140;
        Model.Station st = bikes(true).main();
        Notice.Spec spec = reminder ? Notice.searching(f, st, NOW) : Notice.found(f, st);
        float d = 2f;
        int ow = Math.round(Notice.OVERLAY_W * d), oh = Math.round(Notice.OVERLAY_H * d);
        int ox = w - ow - Math.round(Notice.OVERLAY_X * d), oy = Math.round(Notice.OVERLAY_Y * d);
        int save = gfx.save();
        gfx.translate(ox, oy);
        Notice.drawOverlay(gfx, ow, oh, d, spec, 1f, 0.3f);
        gfx.restore(save);
        gfx.done();
        g.dispose();
        ImageIO.write(img, "png", new File(out, name + ".png"));
        System.out.println(name + " drawn");
    }

    static Model.Wall wall(File f, String title, String place) throws Exception {
        BufferedImage src = ImageIO.read(f);
        int w = 1920, h = 1080;
        BufferedImage full = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = full.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        int[] px = full.getRGB(0, 0, w, h, null, 0, w);
        Pixels.prepareWall(px, w, h);
        full.setRGB(0, 0, w, h, px, 0, w);

        int bw = Pixels.BLUR_W, bh = Pixels.BLUR_H;
        BufferedImage small = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.drawImage(full.getScaledInstance(bw, bh, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        sg.dispose();
        int[] sp = small.getRGB(0, 0, bw, bh, null, 0, bw);
        Pixels.prepareBlur(sp, bw, bh);
        small.setRGB(0, 0, bw, bh, sp, 0, bw);

        Model.Wall wall = new Model.Wall();
        wall.full = full;
        wall.blur = small;
        wall.blurPixels = sp;
        wall.blurW = bw;
        wall.blurH = bh;
        wall.title = title;
        wall.place = place;
        return wall;
    }

    /**
     * Stand-in apps. On the TV each tile is the app's own banner art; these screenshots cannot
     * carry other companies' logos, so every tile here is built by {@link Tiles} (the composer
     * Lucent uses for apps without a banner) from a plain geometric stand-in icon.
     */
    private static List<Model.App> apps() {
        String[] names = {"Netflix", "YouTube", "Prime Video", "Disney+", "Hulu", "Max", "Apple TV", "Peacock", "Spotify", "Plex"};
        int[][] colors = {
            {0xFF1A1A1A, 0xFFB3121B}, {0xFFF2F2F2, 0xFFD9D9D9}, {0xFF0F79AF, 0xFF0A3D62}, {0xFF0B1B5C, 0xFF2541B2},
            {0xFF0B3D2A, 0xFF1CE783}, {0xFF2B1A6B, 0xFF6A4CFF}, {0xFF111111, 0xFF3A3A3C}, {0xFF101010, 0xFF2A2A3A},
            {0xFF0E3B22, 0xFF1ED760}, {0xFF1F1F1F, 0xFFE5A00D},
        };
        int[] style = {0, 1, 0, 2, 0, 1, 0, 2, 1, 0};
        List<Model.App> list = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Model.App a = new Model.App("pkg." + i, names[i]);
            int n = 192;
            int[] icon = standIn(n, colors[i][0], colors[i][1], i, style[i] == 1, false);
            int[] bg = null, fg = null;
            if (style[i] == 2) {
                bg = standIn(216, colors[i][0], colors[i][1], i, false, true);
                fg = mark(216, i, 0.42f);
            }
            int[] px = Tiles.compose(icon, n, bg, fg, 216);
            BufferedImage b = new BufferedImage(Tiles.W, Tiles.H, BufferedImage.TYPE_INT_ARGB);
            b.setRGB(0, 0, Tiles.W, Tiles.H, px, 0, Tiles.W);
            a.banner = b;
            a.color = 0xFF202226;
            a.loaded = true;
            list.add(a);
        }
        return list;
    }

    /** A plain icon: a gradient square (or circle) with a white geometric mark. */
    private static int[] standIn(int n, int c0, int c1, int shape, boolean round, boolean backgroundOnly) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(c1, true), n, n, new Color(c0, true)));
        if (round) g.fill(new java.awt.geom.Ellipse2D.Float(n * 0.06f, n * 0.06f, n * 0.88f, n * 0.88f));
        else g.fillRect(0, 0, n, n);
        g.dispose();
        int[] px = img.getRGB(0, 0, n, n, null, 0, n);
        if (backgroundOnly) return px;
        int[] mk = mark(n, shape, round ? 0.5f : 0.56f);
        for (int i = 0; i < px.length; i++) {
            float a = (mk[i] >>> 24) / 255f;
            if (a > 0) px[i] = Pixels.lerp(px[i], (px[i] & 0xFF000000) | (mk[i] & 0xFFFFFF), a);
        }
        return px;
    }

    /** Original geometric marks, one per stand-in. */
    private static int[] mark(int n, int shape, float size) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int ink = shape == 1 ? 0xFF1C1C1E : 0xFFFFFFFF;
        g.setColor(new Color(ink, true));
        float s = n * size, c = n / 2f;
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        g.setStroke(new java.awt.BasicStroke(s * 0.12f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        switch (shape % 10) {
            case 0: // stacked bars
                for (int k = 0; k < 3; k++) g.fill(new java.awt.geom.RoundRectangle2D.Float(c - s / 2, c - s / 2 + k * s * 0.38f, s, s * 0.22f, s * 0.22f, s * 0.22f));
                break;
            case 1: // ring and dot
                g.draw(new java.awt.geom.Ellipse2D.Float(c - s / 2, c - s / 2, s, s));
                g.fill(new java.awt.geom.Ellipse2D.Float(c - s * 0.13f, c - s * 0.13f, s * 0.26f, s * 0.26f));
                break;
            case 2: // two chevrons
                p.moveTo(c - s * 0.45f, c - s * 0.3f); p.lineTo(c - s * 0.05f, c); p.lineTo(c - s * 0.45f, c + s * 0.3f);
                p.moveTo(c + s * 0.0f, c - s * 0.3f); p.lineTo(c + s * 0.4f, c); p.lineTo(c + s * 0.0f, c + s * 0.3f);
                g.draw(p);
                break;
            case 3: // four-point sparkle
                p.moveTo(c, c - s / 2); p.quadTo(c, c, c + s / 2, c); p.quadTo(c, c, c, c + s / 2); p.quadTo(c, c, c - s / 2, c); p.quadTo(c, c, c, c - s / 2);
                g.fill(p);
                break;
            case 4: // dot grid
                for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) g.fill(new java.awt.geom.Ellipse2D.Float(c - s / 2 + x * s * 0.4f, c - s / 2 + y * s * 0.4f, s * 0.2f, s * 0.2f));
                break;
            case 5: // waves
                for (int k = 0; k < 3; k++) {
                    java.awt.geom.Path2D.Float w = new java.awt.geom.Path2D.Float();
                    float y = c - s * 0.3f + k * s * 0.3f;
                    w.moveTo(c - s / 2, y); w.curveTo(c - s / 6, y - s * 0.16f, c + s / 6, y + s * 0.16f, c + s / 2, y);
                    g.draw(w);
                }
                break;
            case 6: // diamond outline
                p.moveTo(c, c - s / 2); p.lineTo(c + s / 2, c); p.lineTo(c, c + s / 2); p.lineTo(c - s / 2, c); p.closePath();
                g.draw(p);
                break;
            case 7: // arc fan
                for (int k = 0; k < 3; k++) {
                    float r = s * (0.2f + 0.16f * k);
                    g.draw(new java.awt.geom.Arc2D.Float(c - r, c - r * 0.6f, r * 2, r * 2, 20, 140, java.awt.geom.Arc2D.OPEN));
                }
                break;
            case 8: // three rising bars
                for (int k = 0; k < 3; k++) {
                    float h = s * (0.45f + 0.27f * k);
                    g.fill(new java.awt.geom.RoundRectangle2D.Float(c - s / 2 + k * s * 0.38f, c + s / 2 - h, s * 0.24f, h, s * 0.24f, s * 0.24f));
                }
                break;
            default: // square in square
                g.draw(new java.awt.geom.RoundRectangle2D.Float(c - s / 2, c - s / 2, s, s, s * 0.3f, s * 0.3f));
                g.fill(new java.awt.geom.RoundRectangle2D.Float(c - s / 5, c - s / 5, s * 0.4f, s * 0.4f, s * 0.12f, s * 0.12f));
        }
        g.dispose();
        return img.getRGB(0, 0, n, n, null, 0, n);
    }

    /** A drawn note: a heart traced the way a finger would, and a line of text. */
    static Notes.Note note(boolean drawn) {
        Notes.Note n = new Notes.Note();
        n.id = drawn ? "n2" : "n1";
        n.from = drawn ? "Alex" : "Sam";
        n.at = NOW - (drawn ? 0 : 3 * 3600000L);
        n.paper = drawn ? 2 : 1;
        n.text = drawn ? "Dinner at Oleana at 8?" : "Back by 7. Love you";
        if (drawn) {
            int steps = 90;
            int[] heart = new int[2 + steps * 2];
            heart[0] = 1;
            heart[1] = 22;
            for (int i = 0; i < steps; i++) {
                double t = Math.PI * 2 * i / (steps - 1);
                double x = 16 * Math.pow(Math.sin(t), 3), y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
                heart[2 + i * 2] = (int) Math.round(5000 + x * 190);
                heart[3 + i * 2] = (int) Math.round(4300 - y * 190);
            }
            n.strokes.add(heart);
            int[] swash = new int[2 + 40 * 2];
            swash[0] = 2;
            swash[1] = 10;
            for (int i = 0; i < 40; i++) {
                swash[2 + i * 2] = 2200 + i * 140;
                swash[3 + i * 2] = (int) Math.round(8600 + 260 * Math.sin(i / 4.0));
            }
            n.strokes.add(swash);
        }
        return n;
    }

    private static List<Ical.Event> events() {
        List<Ical.Event> list = new ArrayList<>();
        String[][] e = {{"Dinner with friends", "Oleana", "0", "19", "0"}, {"Wedding cake tasting", "Flour, Central Sq", "0", "26", "0"},
            {"Seminar: Platform economics", "E62-450", "1", "38", "1"}, {"Marathon training run", "", "0", "62", "0"}};
        for (String[] x : e) {
            Ical.Event ev = new Ical.Event();
            ev.title = x[0];
            ev.location = x[1];
            ev.calendar = Integer.parseInt(x[2]);
            ev.start = NOW + Long.parseLong(x[3]) * 3600000L / 4;
            ev.end = ev.start + 90 * 60000L;
            list.add(ev);
        }
        Ical.Event all = new Ical.Event();
        all.title = "Sam's birthday";
        all.allDay = true;
        all.start = NOW + 2 * 86400000L;
        all.end = all.start + 86400000L;
        list.add(all);
        return list;
    }

    private static List<Plans.Countdown> countdowns() {
        List<Plans.Countdown> list = new ArrayList<>();
        Plans.Countdown c = new Plans.Countdown();
        c.title = "Wedding";
        c.date = "2027-06-12";
        list.add(c);
        return list;
    }

    private static List<Plans.Item> items() {
        List<Plans.Item> list = new ArrayList<>();
        String[] t = {"Oat milk", "Flowers for Saturday", "Return the library books"};
        for (int i = 0; i < t.length; i++) {
            Plans.Item it = new Plans.Item();
            it.id = "l" + i;
            it.text = t[i];
            it.done = i == 2;
            it.by = i == 0 ? "Sam" : "Alex";
            list.add(it);
        }
        return list;
    }

    private static Model.Media music() {
        Model.Media m = new Model.Media();
        m.active = true;
        m.playing = true;
        m.app = "Music";
        m.title = "Slow Morning";
        m.artist = "The Quiet Rooms";
        m.album = "Live at Home";
        m.duration = 303000;
        m.position = 98000;
        m.positionAt = 1000000 - 4000;
        m.canNext = true;
        m.canPrev = true;
        return m;
    }

    /** A forecast as the TV would make it at 2:05 pm with e-bikes docked, or none left for the night shots. */
    private static Forecast.Result forecast(int extra) {
        Forecast.Result r = new Forecast.Result();
        r.dayType = "class";
        if (extra == 6 || extra == 7 || extra == 11 || extra == 13) {
            r.empty = true;
            r.nextLo = NOW + 9 * 60000L;
            r.nextMed = NOW + 34 * 60000L;
            r.nextHi = NOW + 88 * 60000L;
            r.morning = 0.41;
            r.morningAt = NOW + 17 * 3600000L + 25 * 60000L;
        } else {
            r.poolLo = NOW + 38 * 60000L;
            r.poolMed = NOW + 71 * 60000L;
            r.poolHi = NOW + 132 * 60000L;
            r.firstLo = NOW + 22 * 60000L;
            r.firstMed = NOW + 47 * 60000L;
            r.firstHi = NOW + 95 * 60000L;
        }
        return r;
    }

    /** The Fire TV on the Samsung's HDMI 1 with Input Signal Plus on. */
    private static Model.Picture picture() {
        Model.Picture p = new Model.Picture();
        p.width = 3840;
        p.height = 2160;
        p.hz = 60;
        p.maxWidth = 3840;
        p.maxHeight = 2160;
        p.max4kHz = 60;
        p.uiWidth = 1920;
        p.uiHeight = 1080;
        p.hdr.add("HDR10");
        p.hdr.add("HDR10+");
        p.hdr.add("HLG");
        p.describe();
        return p;
    }

    private static Model.Audio audio() {
        Model.Audio a = new Model.Audio();
        a.known = true;
        a.atmos = true;
        a.headline = "Dolby Atmos";
        a.channels = 6;
        a.formats.add("Dolby Atmos");
        a.formats.add("Dolby Digital Plus");
        a.formats.add("Dolby Digital");
        a.formats.add("Multichannel PCM");
        return a;
    }

    /** The same rows as the TV's settings sheet (Home.options), with sample values. */
    private static List<Model.Option> options() {
        List<Model.Option> list = new ArrayList<>();
        list.add(new Model.Option("look", "Look", new String[] {"auto", "day", "night"}, new String[] {"Day and Night by the Sun", "Day", "Night"}));
        list.add(new Model.Option("glare", "Glare", new String[] {"off", "on"}, new String[] {"Off", "On, Dynamic Picture"}));
        list.add(new Model.Option("picture_follow", "TV Picture Follows the Sun", new String[] {"off", "on"},
                new String[] {"No", "Yes, Standard by Day, Movie at Night"}));
        list.add(new Model.Option("wall", "Wallpaper", new String[] {"0", "1"}, new String[] {"Moraine Lake", "Spruce Forest"}));
        list.add(new Model.Option("album", "Photos", new String[] {"both", "nature", "ours"}, new String[] {"Ours and Nature", "Nature", "Ours Only"}));
        list.add(new Model.Option("rotate", "Change Wallpaper", new String[] {"10m", "hour"}, new String[] {"Every 10 Minutes", "Every Hour"}));
        list.add(new Model.Option("wall_quality", "Wallpaper Motion", new String[] {"motion", "still"}, new String[] {"Slow Drift", "Still and Sharpest"}));
        list.add(new Model.Option("dim", "Dim When Idle", new String[] {"10", "off"}, new String[] {"After 10 Minutes", "Never"}));
        list.add(new Model.Option("ambient", "Apps Go Back Down", new String[] {"2", "off"}, new String[] {"After 2 Idle Minutes", "Never"}));
        list.add(new Model.Option("units", "Weather", new String[] {"f", "c", "off"}, new String[] {"\u00b0F, with \u00b0C", "\u00b0C, with \u00b0F", "Off"}));
        list.add(new Model.Option("bike_station", "Bike Station", new String[] {"auto", "p"}, new String[] {"Westgate", "Pacific St"}));
        list.add(new Model.Option("alarm", "When an E-Bike Is Found", new String[] {"ring", "chime"}, new String[] {"Ring Until Stopped", "Chime Once"}));
        list.add(new Model.Option("alarm_for", "Alarm Rings For", new String[] {"10", "30"}, new String[] {"Up to 10 Minutes", "Up to 30 Minutes"}));
        list.add(new Model.Option("find_for", "Bike Finder Gives Up", new String[] {"30", "60"}, new String[] {"After 30 Minutes", "After an Hour"}));
        list.add(new Model.Option("sky", "Planes Overhead", new String[] {"on", "off"}, new String[] {"Show", "Hide"}));
        list.add(new Model.Option("pair", "Phone Control", null, new String[] {"Pair a Phone  \u00b7  192.168.1.23:8686"}));
        list.add(new Model.Option("note", "Write a Note", null, new String[] {"Type or Speak"}));
        list.add(new Model.Option("bluetooth", "Bluetooth Headphones", null, new String[] {"Pair or Switch"}));
        list.add(new Model.Option("takeover", "Lucent Opens on HOME", new String[] {"on", "off"}, new String[] {"Yes", "No, Use Fire TV's Home"}));
        list.add(new Model.Option("fire", "Fire TV Settings", null, new String[] {""}));
        return list;
    }
}
