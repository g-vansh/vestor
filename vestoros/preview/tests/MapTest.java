package tv.vestor.home;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Checks the bike map: the map data reads back whole, every dock and named place stands on land,
 * the camera looks where it should and survives the near plane, the three tags never cover each
 * other or the panel, the panel fits above the keys, and the screen opens and closes it the way
 * the remote expects. BU Law's and Pardee's docks are checked against real station places.
 *
 * With a folder as its argument it also saves each stop as a picture there.
 */
public final class MapTest {
    private static int checks, failures;
    private static final File ROOT = new File(System.getProperty("vestor.root", "."));
    private static final int W = 1920, H = 1080;
    private static final float D = 2;

    public static void main(String[] args) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        data();
        places();
        camera();
        screens(out);
        scene();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static void ok(String what, boolean cond) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    /** The generated map reads back with every named thing in it. */
    private static void data() {
        long t = System.nanoTime();
        MapGeo g = MapGeo.decode(MapData.PARTS);
        System.out.printf("map data read in %.0f ms (desktop)%n", (System.nanoTime() - t) / 1e6);
        ok("thousands of buildings", g.buildings.size() > 4000);
        ok("the river", !g.water.isEmpty());
        ok("streets", g.roads.size() > 500);
        ok("parks", !g.parks.isEmpty());
        ok("the two rides", g.routes.size() == 2);
        for (int tag : new int[] {MapGeo.BU_LAW, MapGeo.PARDEE, MapGeo.HOME}) ok("building tagged " + tag, g.building(tag) != null);
        MapGeo.Shape law = g.route(MapGeo.TO_LAW), pardee = g.route(MapGeo.TO_PARDEE);
        ok("ride to BU Law about 1.4 km", law != null && law.size > 1200 && law.size < 1700);
        ok("ride to Pardee about 2.1 km", pardee != null && pardee.size > 1800 && pardee.size < 2400);
        // A route's stored length matches its points, and it starts at home and ends at its place.
        for (MapGeo.Shape r : g.routes) {
            float len = 0;
            for (int i = 0; i + 3 < r.xy.length; i += 2) len += (float) Math.hypot(r.xy[i + 2] - r.xy[i], r.xy[i + 3] - r.xy[i + 1]);
            ok("route " + r.tag + " length agrees", Math.abs(len - r.size) < r.size * 0.03f);
            float hx = MapGeo.x(Gbfs.WESTGATE_LON), hy = MapGeo.y(Gbfs.WESTGATE_LAT);
            ok("route " + r.tag + " starts at Westgate", Math.hypot(r.xy[0] - hx, r.xy[1] - hy) < 250);
            double plon = r.tag == MapGeo.TO_LAW ? Gbfs.BU_LAW_LON : Gbfs.PARDEE_LON;
            double plat = r.tag == MapGeo.TO_LAW ? Gbfs.BU_LAW_LAT : Gbfs.PARDEE_LAT;
            int n = r.xy.length;
            ok("route " + r.tag + " ends at its place", Math.hypot(r.xy[n - 2] - MapGeo.x(plon), r.xy[n - 1] - MapGeo.y(plat)) < 250);
        }
        // A building's height is sane and every shape is inside the map's box.
        boolean heights = true, inside = true;
        for (MapGeo.Shape b : g.buildings) {
            if (!(b.size > 0 && b.size < 300)) heights = false;
            if (b.cx < g.minX || b.cx > g.maxX || b.cy < g.minY || b.cy > g.maxY) inside = false;
        }
        ok("building heights", heights);
        ok("shapes in the box", inside);
        // The river is wet, and the three places are dry.
        ok("mid-river is water", g.wet(MapGeo.x(-71.1000), MapGeo.y(42.3540)));
        ok("Westgate is on land", !g.wet(MapGeo.x(Gbfs.WESTGATE_LON), MapGeo.y(Gbfs.WESTGATE_LAT)));
        ok("BU Law is on land", !g.wet(MapGeo.x(Gbfs.BU_LAW_LON), MapGeo.y(Gbfs.BU_LAW_LAT)));
        ok("Pardee is on land", !g.wet(MapGeo.x(Gbfs.PARDEE_LON), MapGeo.y(Gbfs.PARDEE_LAT)));
        // Damaged data is refused, not misread.
        boolean refused = false;
        try {
            MapGeo.decode(new String[] {"AAEC/////w=="});
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        ok("damaged map data refused", refused);
    }

    /** BU Law's and Pardee's docks, from real station places with made-up counts. */
    private static void places() throws Exception {
        Model.Bikes b = Preview.mapBikes();
        ok("two places", b.places.size() == 2);
        Model.Place law = b.places.get(0), pardee = b.places.get(1);
        ok("BU Law's nearest is BU Central", "BU Central".equals(Gbfs.placeName(law.nearest())));
        ok("BU Central is close to BU Law", law.nearest().metres < 150);
        ok("Pardee's nearest is Silber Way", "Silber Way".equals(Gbfs.placeName(pardee.nearest())));
        for (Model.Place p : b.places) {
            ok(p.label + " lists at most three docks", p.docks.size() <= Gbfs.PLACE_DOCKS && !p.docks.isEmpty());
            for (int i = 1; i < p.docks.size(); i++) {
                ok(p.label + " docks within a short walk", p.docks.get(i).metres <= Gbfs.PLACE_METRES);
                ok(p.label + " nearest first", p.docks.get(i).metres >= p.docks.get(i - 1).metres);
            }
            for (Model.Station s : p.docks) ok(p.label + " never across the Pike: " + s.name, !s.name.contains("Buswell"));
        }
        ok("BU Law free docks", law.freeDocks() == 10);
        ok("Pardee's nearest is full", pardee.nearest().returnableDocks() == 0 && pardee.firstFree() != null);
        ok("Pardee's first room is Deerfield St", "Deerfield St".equals(Gbfs.placeName(pardee.firstFree())));
        ok("card figure", "10 free".equals(Cards.placeFigure(law)));
        ok("card detail when full", Cards.placeDetail(pardee).startsWith("Silber Way full, 6 at Deerfield St"));
        ok("card detail with room", Cards.placeDetail(law).startsWith("7 at BU Central, "));
        Model.Place none = new Model.Place();
        ok("no counts yet", "-".equals(Cards.placeFigure(none)) && Cards.placeDetail(none).isEmpty());
        // Every dock on the map stands on land.
        MapGeo g = MapGeo.get();
        ok("the map's docks", b.area.size() > 30);
        for (Model.Station s : b.area) ok("dock on land: " + s.name, !g.wet(MapGeo.x(s.lon), MapGeo.y(s.lat)));
        ok("short names", "Granby St".equals(name("Commonwealth Ave at Granby St"))
                && "BU Central".equals(name("B.U. Central T Stop - 725 Commonwealth Ave")) && "Silber Way".equals(name("Silber Way")));
        ok("phone names its stops", BikeMap.stopNamed("law") == BikeMap.LAW && BikeMap.stopNamed("home") == BikeMap.HOME
                && BikeMap.stopNamed("all") == BikeMap.OVERVIEW && BikeMap.stopNamed("mars") == -1);
    }

    private static String name(String full) {
        Model.Station s = new Model.Station();
        s.name = full;
        return Gbfs.placeName(s);
    }

    /** The camera looks at each stop, turns through north and survives the near plane. */
    private static void camera() {
        BikeMap m = new BikeMap();
        BikeMap.Renderer r = m.new Renderer();
        for (int i = 0; i < BikeMap.STOPS; i++) {
            BikeMap.Pose p = m.stopPose(i);
            r.setup(p, W, H);
            float[] o = r.project(p.x, p.y, 0);
            ok("stop " + i + " looks at its point", Math.abs(o[0] - W * 0.6f) < 0.5f && Math.abs(o[1] - H * 0.54f) < 0.5f
                    && Math.abs(o[2] - p.dist) < 0.5f);
        }
        ok("same pose across north", new BikeMap.Pose(0, 0, 359.995f, 50, 900).same(new BikeMap.Pose(0, 0, -0.004f, 50, 900)));
        ok("turn wraps", Math.abs(BikeMap.turn(350 - 10) + 20) < 1e-3 && Math.abs(BikeMap.turn(10 - 350) - 20) < 1e-3);
        // A square around the camera, half of it behind: clipped to finite points in front.
        BikeMap.Pose p = m.stopPose(BikeMap.LAW);
        r.setup(p, W, H);
        float cx = r.camX, cy = r.camY;
        float[] sq = {cx - 300, cy - 300, cx + 300, cy - 300, cx + 300, cy + 300, cx - 300, cy + 300};
        float[] s = r.flat(sq, 0, true);
        boolean finite = s != null && s.length >= 6;
        if (s != null) for (float v : s) finite &= !Float.isNaN(v) && !Float.isInfinite(v) && Math.abs(v) < 1e6f;
        ok("near plane clipping", finite);
        // Straight under and behind the camera: nothing to draw, or something finite.
        float[] behind = {cx - 10, cy - 10, cx + 10, cy - 10, cx, cy - 5};
        float[] b = r.flat(behind, 0, true);
        boolean fine = true;
        if (b != null) for (float v : b) fine &= !Float.isNaN(v) && !Float.isInfinite(v);
        ok("behind the camera", fine);
        // Keys: OK goes round the four stops; zoom stays inside its limits.
        long now = Preview.NOW;
        m.open(now);
        int[] seen = new int[BikeMap.STOPS + 1];
        for (int i = 0; i <= BikeMap.STOPS; i++) {
            seen[i] = m.stop();
            m.key(Scene.OK, now += 2000);
        }
        ok("OK goes round", seen[0] == 0 && seen[1] == 1 && seen[2] == 2 && seen[3] == 3 && seen[4] == 0);
        for (int i = 0; i < 12; i++) m.key(Scene.UP, now += 2000);
        BikeMap.Pose in = m.pose(now += 2000);
        for (int i = 0; i < 24; i++) m.key(Scene.DOWN, now += 2000);
        BikeMap.Pose outPose = m.pose(now += 2000);
        ok("zoom in stops", in.dist >= BikeMap.MIN_DIST - 1);
        ok("zoom out stops", outPose.dist <= BikeMap.MAX_DIST + 1);
        ok("other keys do nothing", !m.key(Scene.PLAY, now));
        m.close();
        ok("closed", !m.isOpen());
    }

    /** Every stop, turned all the way round, with and without counts: nothing throws, tags stay apart. */
    private static void screens(File out) throws Exception {
        Model.Bikes bikes = Preview.mapBikes();
        BikeMap m = new BikeMap();
        m.sync = true;
        long now = Preview.NOW;
        m.open(now);
        String[] names = {"all-three", "bu-law", "pardee", "westgate"};
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int stop = 0; stop < BikeMap.STOPS; stop++) {
            m.show(stop, now);
            for (int turn = 0; turn < 8; turn++) {
                if (turn > 0) m.key(Scene.RIGHT, now);
                now = settle(m, now);
                Graphics2D g2 = img.createGraphics();
                AwtGfx g = new AwtGfx(g2, new File(ROOT, "assets/fonts"));
                boolean threw = false;
                try {
                    m.draw(g, W, H, D, now, turn == 3 ? null : bikes, 1);
                } catch (RuntimeException e) {
                    threw = true;
                    e.printStackTrace();
                }
                g.done();
                g2.dispose();
                String at = names[stop] + " turned " + turn * 45;
                ok(at + ": draws", !threw);
                ok(at + ": a city, not a blank", variety(img) > 40);
                if (turn != 3) layout(m.renderer(), at);
                if (turn == 0) {
                    float ph = m.renderer().panelHeight(stop, bikes, D);
                    ok(names[stop] + ": panel clears the keys", D * 112 + ph < H - D * 56);
                    if (out != null) ImageIO.write(img, "png", new File(out, (stop + 1) + "-" + names[stop] + ".png"));
                }
            }
            // Back to the next stop as it lands.
            m.show((stop + 1) % BikeMap.STOPS, now);
            now = settle(m, now);
        }
        // Zoomed all the way in and out at BU Law.
        m.show(BikeMap.LAW, now);
        for (int k = 0; k < 10; k++) m.key(Scene.UP, now);
        now = settle(m, now);
        Graphics2D g2 = img.createGraphics();
        AwtGfx g = new AwtGfx(g2, new File(ROOT, "assets/fonts"));
        long t = System.nanoTime();
        m.draw(g, W, H, D, now, bikes, 1);
        ok("zoomed in draws", variety(img) > 40);
        for (int k = 0; k < 20; k++) m.key(Scene.DOWN, now);
        now = settle(m, now);
        m.draw(g, W, H, D, now, bikes, 1);
        ok("zoomed out draws", variety(img) > 40);
        g.done();
        g2.dispose();
        System.out.printf("whole city drawn in %.0f ms a frame (desktop, software)%n", (System.nanoTime() - t) / 2e6);
        m.close();
    }

    private static long settle(BikeMap m, long now) {
        for (int i = 0; i < 200; i++) m.step(now += 16);
        return now;
    }

    /** The three tags: none covers another, none reaches into the panel, all on screen. */
    private static void layout(BikeMap.Renderer r, String at) {
        float left = r.panelRight(D);
        for (int i = 0; i < 3; i++) {
            if (!r.tagShown[i]) continue;
            ok(at + ": tag " + i + " clear of the panel", r.tagL[i] >= left);
            ok(at + ": tag " + i + " on screen", r.tagL[i] + r.tagW[i] <= W && r.tagT[i] >= 0 && r.tagT[i] + r.tagH <= H);
            for (int j = i + 1; j < 3; j++) {
                if (!r.tagShown[j]) continue;
                boolean apart = r.tagL[i] >= r.tagL[j] + r.tagW[j] || r.tagL[j] >= r.tagL[i] + r.tagW[i]
                        || r.tagT[i] >= r.tagT[j] + r.tagH || r.tagT[j] >= r.tagT[i] + r.tagH;
                ok(at + ": tags " + i + " and " + j + " apart", apart);
            }
        }
    }

    /** How many distinct colours a coarse sample of the picture holds. */
    private static int variety(BufferedImage img) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y += 23) {
            for (int x = 0; x < img.getWidth(); x += 23) seen.add(img.getRGB(x, y) & 0xF8F8F8);
        }
        return seen.size();
    }

    /** The screen: the top row's button opens it, OK flies on, Back returns, and it closes itself when left. */
    private static void scene() throws Exception {
        final int[] actions = {0};
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
            public void findBike() {}
            public void stopFinding() {}
            public void dismissFinder() {}
            public void silenceAlarm() {}
            public void media(int what) {}
            public void wallpaper(int dir) {}
            public void mirror() {}
            public void pairing(boolean open) {}
            public void decide(String id, boolean allow) {}
            public void action(String key) {
                actions[0]++;
            }
        };
        Scene s = new Scene(host);
        s.mapSync = true;
        s.setSize(W, H, D);
        s.setApps(new java.util.ArrayList<Model.App>());
        s.setBikes(Preview.mapBikes());
        long now = Preview.NOW;
        s.enter(now);
        now = run(s, now, 90);
        // Up to the top row, all the way left, then right to the map's button.
        s.key(Scene.UP, now);
        for (int i = 0; i < 8; i++) s.key(Scene.LEFT, now);
        for (int i = 0; i < 5; i++) s.key(Scene.RIGHT, now);
        ok("the top row names it", "Bike Map".equals(s.focusedLabel()));
        s.key(Scene.OK, now);
        ok("the button opens the map", s.mapOpen());
        now = run(s, now, 200);
        ok("the screen reader names the place", s.focusedLabel().startsWith("Bike map, All three"));
        s.key(Scene.OK, now);
        ok("OK flies on", s.focusedLabel().startsWith("Bike map, BU Law"));
        now = run(s, now, 60);
        s.key(Scene.BACK, now);
        ok("Back closes it", !s.mapOpen());
        now = run(s, now, 60);
        // From the phone, straight to Pardee; then left alone it closes itself.
        s.showMap(BikeMap.PARDEE, now);
        ok("the phone opens it at Pardee", s.mapOpen() && s.focusedLabel().startsWith("Bike map, Pardee"));
        now = run(s, now, 30);
        now += Scene.MAP_IDLE_MS + 1000;
        run(s, now, 2);
        ok("closes itself when left alone", !s.mapOpen());
        ok("no stray actions", actions[0] == 0);
    }

    private static long run(Scene s, long now, int frames) {
        for (int i = 0; i < frames; i++) s.step(1 / 60f, now += 16);
        return now;
    }
}
