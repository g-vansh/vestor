package tv.vestor.home;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Checks what the screen fits: the info bar never runs past the dock's right edge, whatever
 * plays and whichever shuttles are due, and no settings row's title runs into its value. Drawn
 * with the real fonts, from the preview's fixtures.
 */
public final class GlanceTest {
    private static int checks, failures;
    private static final File ROOT = new File(System.getProperty("vestor.root", "."));

    public static void main(String[] args) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
        // Every mix of the wide things: music with a long title, plans, bike finder looking,
        // the 85A due first (its two lines are the widest), planes overhead.
        for (int mix = 0; mix < 32; mix++) bar(mix);
        sheet();
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

    private static Model.Wall wall;

    private static Scene scene() throws Exception {
        if (wall == null) wall = Preview.wall(new File(ROOT, "assets/wallpapers/nature-01.jpg"), "Moraine Lake", "Banff National Park, Canada");
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
            public void action(String key) {}
        };
        Scene s = new Scene(host);
        s.setSize(1920, 1080, 2f);
        s.setApps(Preview.apps());
        s.setWall(wall);
        s.setClock("2:05", "PM", "Tuesday, October 27");
        s.setHour(14, "Cambridge");
        return s;
    }

    private static void bar(int mix) throws Exception {
        boolean music = (mix & 1) != 0, plans = (mix & 2) != 0, finding = (mix & 4) != 0, early85 = (mix & 8) != 0,
                planes = (mix & 16) != 0;
        Scene s = scene();
        long now = Preview.NOW;
        s.setWeather(Weather.parse(Preview.fixture("weather.json"), "Cambridge", false, now - 9 * 60000L, false));
        s.setBikes(Preview.bikes(false));
        Model.Transit t = Preview.transit(false);
        if (early85) for (Model.Line ln : t.lines) if ("85A".equals(ln.chip) && !ln.minutes.isEmpty()) ln.minutes.set(0, 1);
        s.setTransit(t);
        if (planes) s.setSky(Preview.sky());
        if (plans) s.setPlans(Preview.events(), java.util.Arrays.asList("Us", "Work"), Preview.countdowns(), Preview.items());
        if (music) {
            Model.Media m = Preview.music();
            m.title = "A Very Long Song Title From the Live Album, Remastered";
            s.setMedia(m);
        }
        if (finding) {
            Model.Finder f = new Model.Finder();
            f.state = Model.Finder.SEARCHING;
            f.started = now - 84000;
            f.deadline = f.started + 30 * 60000L;
            f.station = "Westgate";
            f.lastCheck = now - 3000;
            s.setFinder(f, null, now);
        }
        s.enter(now);
        for (int i = 0; i < 120; i++) {
            now += 16;
            s.step(1 / 60f, now);
        }
        // Twice: the second frame lays the bar out from the widths the first one measured.
        draw(s, now);
        draw(s, now + 16);
        float[] e = s.barEdges();
        ok("bar inside the screen (music " + music + ", plans " + plans + ", finding " + finding + ", 85A first " + early85
                + ", planes " + planes + "): right " + e[0] + ", edge " + e[1], e[0] > 0 && e[0] <= e[1] + 0.5f);
    }

    private static void draw(Scene s, long now) throws Exception {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        AwtGfx gfx = new AwtGfx(g, new File(ROOT, "assets/fonts"));
        s.draw(gfx, now);
        gfx.done();
        g.dispose();
    }

    private static void sheet() throws Exception {
        Scene s = scene();
        List<Model.Option> list = Preview.options();
        // The longest each row can say on the TV.
        list.add(new Model.Option("access", "Phone Control Works On", new String[] {"lan", "tailnet", "off"},
                new String[] {"Home Wi-Fi and Tailscale", "Tailscale Only", "Off"}));
        list.add(new Model.Option("pair", "Phone Control", null, new String[] {"Pair a Phone  ·  100.101.102.103:8686"}));
        list.add(new Model.Option("station2", "Also Watch", new String[] {"none"}, new String[] {"None, Only the First"}));
        list.add(new Model.Option("update", "Updates", null, new String[] {"Version 2.4 is ready, Install on the Next Screen"}));
        list.add(new Model.Option("x", "A Setting With a Title Far Longer Than Any Row Has Today", new String[] {"a"},
                new String[] {"And a Value Just as Long as the Title Beside It"}));
        Model.Option follow = list.get(2);
        follow.index = 1;
        s.setOptions(list);
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        AwtGfx gfx = new AwtGfx(img.createGraphics(), new File(ROOT, "assets/fonts"));
        for (int i = 0; i < list.size(); i++) {
            float[] f = s.rowFit(gfx, i);
            ok("row " + list.get(i).title + ": title " + f[0] + " and value " + f[1] + " fit in " + f[2], f[0] + f[1] <= f[2] + 0.5f);
            ok("row " + list.get(i).title + " keeps some title", f[0] > 0);
        }
        // Rows that fit stay whole.
        for (int i = 0; i < list.size(); i++) {
            Model.Option o = list.get(i);
            if (o.title.equals("Phone Control Works On") || o.title.equals("Planes Overhead") || o.title.equals("Look")) {
                float[] f = s.rowFit(gfx, i);
                float tw = gfx.measure(o.title, Gfx.TEXT_MEDIUM, 34, 0);
                ok("row " + o.title + " shows its whole title", Math.abs(f[0] - tw) < 0.5f);
            }
        }
    }
}
