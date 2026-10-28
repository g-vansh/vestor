package tv.vestor.home;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Garbage per frame. The real Scene draws into a Gfx that does nothing, so every byte counted is
 * the scene's own: on the stick, steady garbage is what brings collector pauses and dropped
 * frames. Run it on its own, single-threaded: java ... tv.vestor.home.Frames
 */
public final class Frames {
    private Frames() {}

    public static void main(String[] a) throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
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
        List<Model.App> apps = new ArrayList<>();
        for (int i = 0; i < 10; i++) apps.add(new Model.App("p" + i, "App " + i));
        s.setApps(apps);
        Model.Wall w = new Model.Wall();
        w.full = new int[1];
        w.blur = new int[1];
        w.blurW = Pixels.BLUR_W;
        w.blurH = Pixels.BLUR_H;
        w.blurPixels = new int[w.blurW * w.blurH];
        w.title = "Moraine Lake";
        w.place = "Banff";
        s.setWall(w);
        s.setClock("2:05", "PM", "Tuesday, October 27");
        s.setHour(14, "Cambridge");
        s.setPinned(Preview.note(false));
        Gfx g = new Null();
        String[] names = {"idle home", "glance focused", "panel open", "settings open", "bike radar", "note writing",
            "hearts rising", "alarm ringing", "music playing"};
        int[][] keys = {{}, {Scene.UP, Scene.RIGHT}, {Scene.OK}, {Scene.BACK, Scene.BACK, Scene.MENU}, {Scene.BACK, Scene.BACK}, {}, {},
            {}, {Scene.BACK}};
        long now = 1793124300000L;
        int worst = 0;
        for (int k = 0; k < names.length; k++) {
            for (int key : keys[k]) s.key(key, now);
            if (k == 4) {
                Model.Finder f = new Model.Finder();
                f.state = Model.Finder.SEARCHING;
                f.started = now;
                f.deadline = now + 1800000L;
                f.station = "Westgate";
                s.setFinder(f, null, now);
                s.key(Scene.UP, now);
            }
            if (k == 5) s.showNote(Preview.note(true), now);
            if (k == 6) {
                s.key(Scene.BACK, now);
                s.hearts("hearts", "Alex", now);
            }
            if (k == 7) {
                Model.Finder f = new Model.Finder();
                f.state = Model.Finder.FOUND;
                f.ebikes = 1;
                f.ended = now;
                f.station = "Westgate";
                s.setAlarm(true, f, null, now);
            }
            if (k == 8) {
                s.setAlarm(false, null, null, now);
                Model.Media m = new Model.Media();
                m.active = true;
                m.playing = true;
                m.title = "Slow Morning";
                m.artist = "The Quiet Rooms";
                m.duration = 303000;
                m.position = 1000;
                m.positionAt = 1;
                s.setMedia(m);
                s.key(Scene.UP, now);
            }
            // Warm up, then measure 600 frames (10 s at 60 fps).
            int warm = k == 5 || k == 6 ? 20 : 300, frames = k == 5 || k == 6 ? 120 : 600;
            for (int i = 0; i < warm; i++) {
                now += 16;
                s.step(1 / 60f, now);
                s.draw(g, now);
            }
            long before = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < frames; i++) {
                now += 16;
                s.step(1 / 60f, now);
                s.draw(g, now);
            }
            long per = (mx.getThreadAllocatedBytes(tid) - before) / frames;
            worst = Math.max(worst, (int) per);
            System.out.printf("%-16s %6d bytes a frame%n", names[k], per);
        }
        System.out.println("worst " + worst + " bytes a frame");
        // A few hundred bytes is strings for the second hand; kilobytes would mean a regression.
        if (worst > 4096) {
            System.out.println("FAIL: more than 4 KB of garbage a frame");
            System.exit(1);
        }
    }

    /** Draws nothing, measures text as half its size a character. */
    static final class Null implements Gfx {
        private int depth;

        @Override
        public int save() { return depth++; }

        @Override
        public void restore(int count) {}

        @Override
        public void translate(float dx, float dy) {}

        @Override
        public void scale(float s, float px, float py) {}

        @Override
        public void tilt(float degX, float degY, float px, float py) {}

        @Override
        public void clipRect(float l, float t, float r, float b) {}

        @Override
        public void fillRect(float l, float t, float r, float b, int argb) {}

        @Override
        public void fillRoundRect(float l, float t, float r, float b, float rad, int argb) {}

        @Override
        public void fillRoundRectLinear(float l, float t, float r, float b, float rad, float x0, float y0, float x1, float y1, int[] colors, float[] stops, float alpha) {}

        @Override
        public void strokeRoundRectLinear(float l, float t, float r, float b, float rad, float width, float x0, float y0, float x1, float y1, int[] colors, float[] stops, float alpha) {}

        @Override
        public void fillRectLinear(float l, float t, float r, float b, float x0, float y0, float x1, float y1, int[] colors, float[] stops, float alpha) {}

        @Override
        public void fillRoundRectImage(Object img, float il, float it, float ir, float ib, float l, float t, float r, float b, float rad, int alpha) {}

        @Override
        public void fillRingImage(Object img, float il, float it, float ir, float ib, float l, float t, float r, float b, float rad, float inset, int alpha) {}

        @Override
        public void drawImage(Object img, float l, float t, float r, float b, int alpha) {}

        @Override
        public void drawImagePart(Object img, int sl, int st, int sr, int sb, float l, float t, float r, float b, int alpha) {}

        @Override
        public void fillCircle(float cx, float cy, float rad, int argb) {}

        @Override
        public void strokeCircle(float cx, float cy, float rad, float width, int argb) {}

        @Override
        public void line(float x0, float y0, float x1, float y1, float width, int argb) {}

        @Override
        public void fillPolygon(float[] xy, int argb) {}

        @Override
        public void strokePolygon(float[] xy, float width, int argb) {}

        @Override
        public void polyline(float[] xy, float width, int argb) {}

        @Override
        public void arc(float cx, float cy, float rad, float startDeg, float sweepDeg, float width, int argb) {}

        @Override
        public void strokeRoundRect(float l, float t, float r, float b, float rad, float width, int argb) {}

        @Override
        public void fillCircleLinear(float cx, float cy, float rad, float x0, float y0, float x1, float y1, int[] colors, float[] stops, float alpha) {}

        @Override
        public Object offscreen(int w, int h) { return new int[1]; }

        @Override
        public Gfx into(Object offscreen) { return this; }

        @Override
        public void clear(Object offscreen) {}

        @Override
        public Object image(int[] argb, int w, int h) { return new int[1]; }

        @Override
        public int imageWidth(Object img) { return 64; }

        @Override
        public int imageHeight(Object img) { return 64; }

        @Override
        public void text(String s, int face, float sizePx, float tracking, float x, float baseline, int argb, int align, float shadowAlpha) {}

        @Override
        public float measure(String s, int face, float sizePx, float tracking) { return s == null ? 0 : s.length() * sizePx * 0.5f; }

        @Override
        public float capHeight(int face, float sizePx) { return sizePx * 0.7f; }
    }
}
