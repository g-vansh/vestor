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
        File out = new File(args.length > 0 ? args[0] : "preview-out");
        out.mkdirs();
        String wallFile = args.length > 1 ? args[1] : "nature-01.jpg";
        int U = Scene.UP, D = Scene.DOWN, L = Scene.LEFT, R = Scene.RIGHT, OK = Scene.OK, B = Scene.BACK;
        shot(out, "01-home", wallFile, new int[0], 0);
        shot(out, "02-glance-bikes", wallFile, new int[] {U, R}, 0);
        shot(out, "03-glance-weather", wallFile, new int[] {U}, 0);
        shot(out, "04-glance-shuttles", wallFile, new int[] {U, R, R}, 0);
        shot(out, "05-glance-sky", wallFile, new int[] {U, R, R, R}, 0);
        shot(out, "06-bikes-panel-finding", wallFile, new int[] {U, R, OK}, 3);
        shot(out, "07-weather-panel", wallFile, new int[] {U, OK}, 0);
        shot(out, "08-shuttles-panel", wallFile, new int[] {U, R, R, OK}, 0);
        shot(out, "09-sky-panel", wallFile, new int[] {U, R, R, R, OK}, 0);
        shot(out, "10-finding-home", wallFile, new int[] {U, R, OK, B, B}, 3);
        shot(out, "11-found-home", wallFile, new int[0], 4);
        shot(out, "12-ambient", wallFile, new int[0], 1);
        shot(out, "13-settings", wallFile, new int[] {U, U, R, OK, D, D, D, D, D, D}, 0);
        shot(out, "14-dock-scrolled", wallFile, new int[] {R, R, R, R}, 0);
        shot(out, "15-sound", wallFile, new int[] {U, U, L, OK}, 0);
        shot(out, "18-home-forest", "nature-02.jpg", new int[] {U, R}, 0);
        shot(out, "19-home-night", "nature-04.jpg", new int[0], 0);
        overlay(out, "16-alert-over-video", false);
        overlay(out, "17-reminder-over-video", true);
    }

    private static String fixture(String name) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(new File(FIX, name).toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Model.Bikes bikes(boolean docked) throws Exception {
        List<Model.Station> all = Gbfs.stations(fixture("gbfs_info.json"), Gbfs.WESTGATE_LAT, Gbfs.WESTGATE_LON);
        List<Model.Station> follow = Gbfs.follow(all, Gbfs.westgate(all), 3);
        Gbfs.status(fixture(docked ? "gbfs_status_found.json" : "gbfs_status.json"), follow);
        Model.Bikes b = new Model.Bikes();
        b.stations.addAll(follow);
        b.fetched = NOW - 12000;
        b.ok = true;
        return b;
    }

    private static Model.Transit transit() throws Exception {
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
        };
        Scene scene = new Scene(host);
        holder[0] = scene;
        int w = 1920, h = 1080;
        scene.setSize(w, h, 2f);
        scene.setApps(apps());
        scene.setWall(wall(new File(ROOT, "assets/wallpapers/" + wallFile), "Moraine Lake", "Banff National Park, Canada"));
        scene.setWeather(Weather.parse(fixture("weather.json"), "Cambridge", false, NOW - 9 * 60000L, false));
        scene.setAudio(audio());
        scene.setBikes(bikes(extra == 4));
        scene.setTransit(transit());
        scene.setSky(sky());
        scene.setClock("2:05", "PM", "Tuesday, October 27");
        scene.setOptions(options());
        scene.setAmbientAfter(extra == 1 ? 1000 : 300000);
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
        for (int k : keys) {
            scene.key(k, now);
            for (int i = 0; i < 30; i++) {
                now += 16;
                clock[0] = now;
                scene.step(1 / 60f, now);
            }
        }
        int settle = extra == 1 ? 240 : extra == 4 ? 75 : 90;
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

    private static List<Model.Option> options() {
        List<Model.Option> list = new ArrayList<>();
        list.add(new Model.Option("wall", "Wallpaper", new String[] {"0", "1"}, new String[] {"Moraine Lake", "Spruce Forest"}));
        list.add(new Model.Option("rotate", "Change Wallpaper", new String[] {"off", "10m", "hour", "day"},
                new String[] {"Never", "Every 10 Minutes", "Every Hour", "Every Day"}));
        list.add(new Model.Option("motion", "Motion", new String[] {"full", "reduced"}, new String[] {"Full", "Reduced"}));
        list.add(new Model.Option("clock", "Clock", new String[] {"12", "24"}, new String[] {"12-Hour", "24-Hour"}));
        list.add(new Model.Option("units", "Weather", new String[] {"f", "c", "off"}, new String[] {"\u00b0F, with \u00b0C", "\u00b0C, with \u00b0F", "Off"}));
        list.add(new Model.Option("bike_station", "Bike Station", new String[] {"auto", "p"}, new String[] {"Westgate", "Pacific St"}));
        list.add(new Model.Option("find_for", "Bike Finder Gives Up", new String[] {"30", "60"}, new String[] {"After 30 Minutes", "After an Hour"}));
        list.add(new Model.Option("sky", "Planes Overhead", new String[] {"on", "off"}, new String[] {"Show", "Hide"}));
        list.add(new Model.Option("ambient", "Ambient Mode", new String[] {"2", "5", "off"}, new String[] {"After 2 Minutes", "After 5 Minutes", "Off"}));
        list.add(new Model.Option("unhide", "Hidden Apps", null, new String[] {"2 hidden, Show All"}));
        list.add(new Model.Option("fire", "Fire TV Settings", null, new String[] {""}));
        list.add(new Model.Option("amazon", "Amazon Home", null, new String[] {""}));
        return list;
    }
}
