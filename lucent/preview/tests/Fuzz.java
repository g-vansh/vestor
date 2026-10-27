package tv.lucent.home;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Presses random remote keys at the real Scene, in many content states, and draws frames. */
public final class Fuzz {
    public static void main(String[] a) throws Exception {
        File root = new File(System.getProperty("lucent.root", "."));
        Model.Wall wall = Preview.wall(new File(root, "assets/wallpapers/nature-04.jpg"), "Aurora", "Lofoten");
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        AwtGfx g = new AwtGfx(g2, new File(root, "assets/fonts"));
        int[] keys = {Scene.LEFT, Scene.RIGHT, Scene.UP, Scene.DOWN, Scene.OK, Scene.LONG_OK, Scene.BACK, Scene.MENU, Scene.PLAY,
            Scene.REWIND, Scene.FORWARD};
        final int[] calls = new int[19];
        final AwtGfx qrGfx = g;
        final List<String> hidden = new ArrayList<>();
        for (int variant = 0; variant < 6; variant++) {
            Random rnd = new Random(variant);
            final Scene[] self = new Scene[1];
            final long[] clock = {0};
            final Model.Finder finder = new Model.Finder();
            Scene.Host host = new Scene.Host() {
                public void launch(Model.App app) { calls[0]++; }
                public void appInfo(Model.App app) { calls[1]++; }
                public void fireSettings() { calls[2]++; }
                public void amazonHome() { calls[3]++; }
                public void option(String key, String value) { calls[4]++; }
                public void order(List<String> ids) { calls[5]++; }
                public void hide(String id) { calls[6]++; hidden.add(id); }
                public void unhideAll() { calls[7]++; }
                public void sound(boolean click) { calls[8]++; }
                public void findBike() {
                    calls[9]++;
                    finder.state = Model.Finder.SEARCHING;
                    finder.started = clock[0];
                    finder.deadline = clock[0] + 60000;
                    finder.station = "Westgate";
                    self[0].setFinder(finder.copy(), null, clock[0]);
                }
                public void stopFinding() {
                    calls[10]++;
                    finder.state = Model.Finder.STOPPED;
                    self[0].setFinder(finder.copy(), null, clock[0]);
                }
                public void dismissFinder() {
                    calls[11]++;
                    finder.state = Model.Finder.IDLE;
                    self[0].setFinder(finder.copy(), null, clock[0]);
                }
                public void silenceAlarm() {
                    calls[12]++;
                    self[0].setAlarm(false, null, null, clock[0]);
                }
                public void media(int what) { calls[13]++; }
                public void wallpaper(int dir) { calls[14]++; }
                public void mirror(boolean apple) { calls[15]++; }
                public void pairing(boolean open) {
                    calls[16]++;
                    if (open) {
                        String[] w = {"http://192.168.1.9:8686/#k=abc", "123456", "http://192.168.1.9:8686"};
                        self[0].setPairing(w, qrGfx, Qr.encode(w[0]), clock[0] + Keys.WINDOW_MS);
                    }
                }
                public void decide(String id, boolean allow) { calls[17]++; }
                public void action(String key) { calls[18]++; }
            };
            Scene s = new Scene(host);
            self[0] = s;
            s.setSize(1920, 1080, 2f);
            int n = new int[] {0, 1, 2, 5, 12, 30}[variant];
            List<Model.App> apps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Model.App app = new Model.App("p" + i, "App number " + i + (i % 3 == 0 ? " with a very long name indeed" : ""));
                if (i % 2 == 0) app.banner = new BufferedImage(400, 225, BufferedImage.TYPE_INT_ARGB);
                else if (i % 3 == 0) app.icon = new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
                apps.add(app);
            }
            s.setApps(apps);
            if (variant != 1) s.setWall(wall);
            if (variant % 2 == 0) {
                Model.Weather w = new Model.Weather();
                w.ok = true; w.tempC = -3.4; w.hiC = 2; w.loC = -9.6; w.feelsC = -11; w.code = 75; w.day = true; w.city = "Cambridge";
                w.condition = "Heavy Snow"; w.humidity = 91; w.windKph = 40; w.windDir = 10; w.celsiusFirst = variant != 2;
                for (int h = 0; h < 7; h++) { w.hourLabel.add(h == 0 ? "Now" : (h + 9) + " AM"); w.hourC.add(-3.0 - h); w.hourCode.add(h * 13 % 100); w.hourDay.add(h < 4); }
                s.setWeather(w);
            }
            Model.Audio au = new Model.Audio();
            if (variant % 3 == 0) { au.known = true; au.formats.add("Dolby Digital"); au.headline = "Dolby Digital"; }
            s.setAudio(au);
            s.setWeatherOn(variant != 3);
            s.setClock("9:05", variant % 2 == 0 ? "AM" : "", "Tuesday, January 6");
            List<Model.Option> opts = new ArrayList<>();
            opts.add(new Model.Option("wall", "Wallpaper", new String[] {"0", "1", "2"}, new String[] {"A", "B", "C"}));
            opts.add(new Model.Option("motion", "Motion", new String[] {"full", "reduced"}, new String[] {"Full", "Reduced"}));
            for (int i = 0; i < 8; i++) opts.add(new Model.Option("x" + i, "Row " + i, new String[] {"a", "b"}, new String[] {"A", "B"}));
            opts.add(new Model.Option("fire", "Fire TV Settings", null, new String[] {""}));
            opts.add(new Model.Option("unhide", "Hidden Apps", null, new String[] {"None"}));
            s.setOptions(opts);
            s.setReduced(variant == 4);
            long now = 1793124300000L;
            s.enter(now);
            for (int i = 0; i < 4000; i++) {
                clock[0] = now;
                if (i % 97 == 0) live(s, rnd, now, variant);
                if (rnd.nextInt(150) == 0) {
                    Model.Finder f = finder.copy();
                    int[] states = {Model.Finder.FOUND, Model.Finder.TIMED_OUT, Model.Finder.SEARCHING, Model.Finder.IDLE};
                    f.state = states[rnd.nextInt(states.length)];
                    f.ended = now;
                    f.ebikes = rnd.nextInt(3);
                    f.offline = rnd.nextBoolean();
                    finder.state = f.state;
                    s.setFinder(f, null, now);
                }
                if (rnd.nextInt(60) == 0) moment(s, rnd, now, qrGfx);
                s.key(keys[rnd.nextInt(keys.length)], now);
                for (int k = 0; k < 2; k++) {
                    now += 16;
                    s.step(1 / 60f, now);
                }
                if (rnd.nextInt(200) == 0) {
                    List<Model.App> fewer = new ArrayList<>(apps);
                    if (!fewer.isEmpty()) fewer.remove(rnd.nextInt(fewer.size()));
                    s.setApps(fewer);
                }
                if (rnd.nextInt(300) == 0) s.setApps(apps);
                if (rnd.nextInt(500) == 0) s.setWall(Preview.wall(new File(root, "assets/wallpapers/nature-0" + (1 + rnd.nextInt(9)) + ".jpg"), "X", ""));
                if (i % 40 == 0) s.draw(g, now);
                if (rnd.nextInt(700) == 0) now += 400_000;
            }
            System.out.println("variant " + variant + " (" + n + " apps) ok");
        }
        System.out.println("host calls: launch " + calls[0] + ", info " + calls[1] + ", fire " + calls[2] + ", amazon " + calls[3]
                + ", option " + calls[4] + ", order " + calls[5] + ", hide " + calls[6] + ", unhide " + calls[7] + ", sound " + calls[8]
                + ", find " + calls[9] + ", stop " + calls[10] + ", dismiss " + calls[11] + ", silence " + calls[12] + ", media "
                + calls[13] + ", wallpaper " + calls[14] + ", mirror " + calls[15] + ", pairing " + calls[16] + ", decide " + calls[17]
                + ", action " + calls[18]);
    }

    /** One of the moments the hub sends, at random: notes, hearts, the alarm, a phone asking, Wi-Fi. */
    private static void moment(Scene s, Random rnd, long now, AwtGfx g) {
        switch (rnd.nextInt(12)) {
            case 0:
                s.showNote(Preview.note(rnd.nextBoolean()), now);
                break;
            case 1:
                s.hearts(rnd.nextBoolean() ? "hearts" : rnd.nextBoolean() ? "sparkles" : "wave", "Someone", now);
                break;
            case 2: {
                Model.Finder f = new Model.Finder();
                f.state = Model.Finder.FOUND;
                f.ebikes = 1;
                f.ended = now;
                s.setAlarm(rnd.nextBoolean(), f, null, now);
                break;
            }
            case 3:
                s.ask(rnd.nextBoolean() ? "p" + rnd.nextInt(9) : null, "A phone with a very long name that goes on and on");
                break;
            case 4:
                s.setWifi(rnd.nextBoolean() ? "Guest" : null, g, Qr.encode("WIFI:T:WPA;S:Guest;P:x;;"), now + rnd.nextInt(130000));
                break;
            case 5:
                s.setNight(rnd.nextBoolean(), rnd.nextBoolean());
                break;
            case 6:
                s.setPinned(rnd.nextBoolean() ? Preview.note(rnd.nextBoolean()) : null);
                break;
            case 7:
                s.toast("Headphones connected", rnd.nextBoolean() ? "" : "Sound plays here now", 1 + rnd.nextInt(5), now);
                break;
            case 8: {
                Model.Media m = new Model.Media();
                m.active = rnd.nextBoolean();
                m.playing = rnd.nextBoolean();
                m.title = rnd.nextBoolean() ? "" : "A song title long enough to need cutting short on the card";
                m.artist = "Artist";
                m.duration = rnd.nextInt(400000);
                m.position = rnd.nextInt(500000);
                m.positionAt = rnd.nextInt(1000);
                s.setMedia(m);
                break;
            }
            case 9: {
                List<Ical.Event> ev = new ArrayList<>();
                for (int i = 0; i < rnd.nextInt(12); i++) {
                    Ical.Event e = new Ical.Event();
                    e.title = "Event " + i;
                    e.start = now + rnd.nextInt(7) * 86400000L - 3600000L;
                    e.end = e.start + rnd.nextInt(5) * 3600000L;
                    e.allDay = rnd.nextInt(4) == 0;
                    e.calendar = rnd.nextInt(3);
                    ev.add(e);
                }
                List<Plans.Item> items = new ArrayList<>();
                for (int i = 0; i < rnd.nextInt(6); i++) {
                    Plans.Item it = new Plans.Item();
                    it.text = "Item " + i;
                    it.done = rnd.nextBoolean();
                    items.add(it);
                }
                s.setPlans(ev, java.util.Arrays.asList("Us", "Work"), new ArrayList<Plans.Countdown>(), items);
                break;
            }
            case 10: {
                List<Model.Alert> alerts = new ArrayList<>();
                if (rnd.nextBoolean()) {
                    Model.Alert a = new Model.Alert();
                    a.event = "Winter Storm Warning";
                    a.headline = "Heavy snow expected, with a very long headline that runs past the edge";
                    a.severity = "Severe";
                    alerts.add(a);
                }
                s.setAlerts(alerts);
                break;
            }
            default:
                s.setSun(now - 3600000L * rnd.nextInt(12), now + 3600000L * rnd.nextInt(12), rnd.nextDouble());
        }
    }

    /** Random live data, including empty, odd and extreme cases. */
    private static void live(Scene s, Random rnd, long now, int variant) {
        if (variant == 5 || rnd.nextInt(5) == 0) {
            s.setBikes(null);
            s.setTransit(null);
            s.setSky(null);
            return;
        }
        Model.Bikes b = new Model.Bikes();
        b.ok = true;
        b.fetched = now - rnd.nextInt(600000);
        b.label = rnd.nextBoolean() ? "Westgate" : "A station with a long name somewhere";
        for (int i = 0; i < rnd.nextInt(5); i++) {
            Model.Station st = new Model.Station();
            st.id = "s" + i;
            st.name = i == 0 ? "MIT Vassar St" : "Some St at Other Ave " + i;
            st.capacity = new int[] {0, 11, 25, 80}[rnd.nextInt(4)];
            st.ebikes = rnd.nextInt(4);
            st.classic = rnd.nextInt(20);
            st.docks = rnd.nextInt(30);
            st.disabled = rnd.nextInt(3);
            st.renting = rnd.nextInt(6) != 0;
            st.installed = rnd.nextInt(10) != 0;
            st.returning = rnd.nextInt(6) != 0;
            st.live = rnd.nextInt(8) != 0;
            st.metres = rnd.nextInt(2000);
            st.reported = now - rnd.nextInt(100000);
            b.stations.add(st);
        }
        s.setBikes(b);
        Model.Transit t = new Model.Transit();
        t.ok = true;
        t.fetched = now - rnd.nextInt(400000);
        Model.Line[] lines = {Transit.tech(), Transit.tech2(), Transit.saferide(), Transit.toAssembly(), Transit.toRuggles()};
        for (Model.Line l : lines) {
            int k = rnd.nextInt(4);
            for (int j = 0; j < k; j++) l.minutes.add(rnd.nextInt(3) == 0 ? rnd.nextInt(2) : 2 + rnd.nextInt(90));
            java.util.Collections.sort(l.minutes);
            l.idle = l.minutes.isEmpty() ? "From 6:15 AM" : "";
            t.lines.add(l);
        }
        s.setTransit(t);
        Model.Sky sky = new Model.Sky();
        sky.ok = rnd.nextBoolean();
        for (int i = 0; i < rnd.nextInt(5); i++) {
            Model.Flight f = new Model.Flight();
            f.callsign = rnd.nextBoolean() ? "JBU" + rnd.nextInt(9999) : "";
            f.airline = f.callsign.isEmpty() ? "" : "JetBlue";
            f.from = rnd.nextBoolean() ? "BOS" : "";
            f.to = f.from.isEmpty() ? "" : "SFO";
            f.fromCity = "Logan";
            f.toCity = "A very long city name for an airport far away";
            f.altFt = rnd.nextInt(41000);
            f.track = rnd.nextInt(360);
            f.miles = rnd.nextFloat() * 20;
            f.fpm = rnd.nextInt(4000) - 2000;
            sky.flights.add(f);
        }
        s.setSky(sky);
        s.setSkyOn(rnd.nextInt(5) != 0);
    }
}
