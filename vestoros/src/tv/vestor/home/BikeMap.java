package tv.vestor.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The bike map: Cambridge and BU in 3D around the Charles, with every Bluebikes dock as a live
 * column (e-bikes in Volt at the bottom, classic bikes in blue, free docks as clear glass above),
 * the rides from Westgate to BU Law and to Pardee, and those three places named.
 *
 * The camera is a real perspective camera over a flat world in metres (see {@link MapGeo}).
 * Buildings are drawn far to near and lit from the west. While the camera flies, the map draws
 * only the bigger buildings each frame; where it lands, every building is drawn once into an
 * offscreen image on a worker thread and that image is shown, so a Fire TV keeps 60 frames a
 * second. The columns, labels and the rides moving along the routes are drawn live on top.
 *
 * Keys: OK flies to the next place (all three, BU Law, Pardee, Westgate), Left and Right turn the
 * map, Up and Down zoom. Back closes it (handled by the scene).
 */
final class BikeMap {
    /** Where the camera looks from: the point it looks at, compass bearing, tilt from straight down, distance. */
    static final class Pose {
        final float x, y, bearing, pitch, dist;

        Pose(float x, float y, float bearing, float pitch, float dist) {
            this.x = x;
            this.y = y;
            this.bearing = bearing;
            this.pitch = pitch;
            this.dist = dist;
        }

        boolean same(Pose o) {
            return o != null && Math.abs(o.x - x) < 0.01f && Math.abs(o.y - y) < 0.01f && Math.abs(turn(o.bearing - bearing)) < 0.01f
                    && Math.abs(o.pitch - pitch) < 0.01f && Math.abs(o.dist - dist) < 0.05f;
        }
    }

    static final int OVERVIEW = 0, LAW = 1, PARDEE = 2, HOME = 3, STOPS = 4;
    static final String[] STOP_NAMES = {"All three", "BU Law", "Pardee", "Westgate"};
    /** The phone's names for the stops. */
    static final String[] STOP_KEYS = {"all", "law", "pardee", "home"};

    /** The stop the phone named, or -1. */
    static int stopNamed(String key) {
        for (int i = 0; i < STOP_KEYS.length; i++) if (STOP_KEYS[i].equals(key)) return i;
        return -1;
    }

    /** Buildings drawn while flying: these tall or this big (square metres) or a named place. */
    static final float LITE_HEIGHT = 22, LITE_AREA = 2500;
    static final float MIN_DIST = 380, MAX_DIST = 4200;
    static final long FLY_MS = 1500, OPEN_MS = 2300;
    /** Average Bluebikes speed for the ride times, metres a minute (12 km/h). */
    static final float RIDE_M_PER_MIN = 200;

    // The map's own night palette: it is always dark, whatever the home screen's theme.
    static final int BG_TOP = 0xFF070A10, BG_BOTTOM = 0xFF111725, FOG = 0xFF0B0F18;
    static final int GROUND = 0xFF141922, WATER = 0xFF0E2740, WATER_EDGE = 0xFF1D4B73, PARK = 0xFF13261C;
    static final int ROOF = 0xFF2A303B, WALL = 0xFF222832, WALL_DARK = 0xFF13161C;
    static final int GREEN_LINE = 0xFF1E9E5A, RAIL = 0xFF5B4B3B, CYCLE = 0xFF1B5A52;
    static final int[] ROAD_COLOR = {0xFF414957, 0xFF3A414D, 0xFF323842, 0xFF2D333C, 0xFF262B33, 0xFF1F242B, CYCLE, 0xFF262B33};
    /** Road widths in metres, by kind. */
    static final float[] ROAD_W = {16, 14, 11, 10, 7, 4.5f, 3.2f, 4};
    static final int LAW_C = 0xFFFF6F7D, PARDEE_C = 0xFFFFA040, HOME_C = 0xFFB9A7FF;
    static final int VOLT = 0xFFFFD426, PEDAL = 0xFF64C8FF, TEXT = 0xFFF5F1EA, TEXT2 = 0xB3F5F1EA, TEXT3 = 0x73F5F1EA;
    static final int FULL_C = 0xFFFF7A7A;
    static final int[] STOP_COLORS = {TEXT, LAW_C, PARDEE_C, HOME_C};
    static final int[] BG = {BG_TOP, BG_BOTTOM};
    static final int[] SCRIM_TOP = {0xF0070A10, 0x00070A10}, SCRIM_BOTTOM = {0x00070A10, 0xE6070A10};
    static final float[] SCRIM_STOPS = {0, 1};
    /** The panel's width, and its overview blocks, dock rows and legend, in dp. */
    static final float PANEL_W = 300, BLOCK = 92, ROW = 58, LEGEND = 44;
    static final float[] BG_STOPS = {0, 1};

    private final MapGeo geo;
    private final Pose[] stops;
    private int stop = OVERVIEW;
    private Pose from, to;
    private long flyStart, flyMs, openedAt;
    private boolean opened;
    /** Turn and zoom on top of the current stop. */
    private float turn, zoom = 1;

    // The worker draws the whole city for the pose the camera is flying to.
    private final Object lock = new Object();
    private Pose wanted;
    private Gfx wantedGfx;
    private int wantedW, wantedH;
    private volatile Object baseImage;
    private volatile Pose basePose;
    private volatile int baseW, baseH;
    private Thread worker;
    private volatile boolean running;
    /** Preview and tests draw the city in the calling thread. */
    boolean sync;
    /** How much of the finished image shows, 0..1. */
    private float baseA;
    private long lastStep;

    private final Renderer live = new Renderer();

    /** The renderer that draws the screen, for the tests. */
    Renderer renderer() {
        return live;
    }

    // The frame around the map: what the panel shows while it changes, and the sliding pill.
    private int prevStop = OVERVIEW;
    private long stopAt;
    private float panelH, pillL, pillR, frameDt, bearingNow;
    private long lastDraw;

    BikeMap() {
        geo = MapGeo.get();
        stops = new Pose[STOPS];
        float hx = MapGeo.x(Gbfs.WESTGATE_LON), hy = MapGeo.y(Gbfs.WESTGATE_LAT);
        float lx = MapGeo.x(Gbfs.BU_LAW_LON), ly = MapGeo.y(Gbfs.BU_LAW_LAT);
        float px = MapGeo.x(Gbfs.PARDEE_LON), py = MapGeo.y(Gbfs.PARDEE_LAT);
        // From over Cambridge looking south-east: home in front, the river, BU, and the Back Bay beyond.
        stops[OVERVIEW] = new Pose((hx + lx + px) / 3 + 40, (hy + ly + py) / 3 - 60, 162, 54, 2350);
        stops[LAW] = new Pose(lx, ly + 20, 150, 57, 900);
        stops[PARDEE] = new Pose(px - 60, py + 10, 176, 57, 950);
        stops[HOME] = new Pose(hx + 30, hy - 10, 196, 56, 850);
        Pose o = stops[OVERVIEW];
        from = new Pose(o.x, o.y + 300, o.bearing - 50, 4, o.dist * 1.9f);
        to = o;
    }

    // ---- Opening, keys and motion -------------------------------------------------------------

    void open(long now) {
        opened = true;
        openedAt = now;
        stop = OVERVIEW;
        prevStop = OVERVIEW;
        stopAt = 0;
        panelH = 0;
        pillL = pillR = 0;
        lastDraw = now;
        turn = 0;
        zoom = 1;
        Pose o = stops[OVERVIEW];
        from = new Pose(o.x, o.y + 300, o.bearing - 50, 4, o.dist * 1.9f);
        to = o;
        flyStart = now;
        flyMs = OPEN_MS;
        baseA = 0;
        lastStep = now;
        running = true;
    }

    /** Lets the worker go and drops the city image; the map is shown again with {@link #open}. */
    void close() {
        opened = false;
        running = false;
        synchronized (lock) {
            wanted = null;
            lock.notifyAll();
        }
        worker = null;
        baseImage = null;
        basePose = null;
    }

    boolean isOpen() {
        return opened;
    }

    int stop() {
        return stop;
    }

    /** Where the camera lands for stop {@code i}, before any turn or zoom. */
    Pose stopPose(int i) {
        return stops[i];
    }

    /** OK, Left, Right, Up and Down (the scene's key codes). Returns whether the camera moved. */
    boolean key(int k, long now) {
        switch (k) {
            case Scene.OK:
                moveTo((stop + 1) % STOPS, now);
                turn = 0;
                zoom = 1;
                break;
            case Scene.LEFT:
                turn -= 45;
                break;
            case Scene.RIGHT:
                turn += 45;
                break;
            case Scene.UP:
                zoom = Math.max(MIN_DIST / stops[stop].dist, zoom * 0.72f);
                break;
            case Scene.DOWN:
                zoom = Math.min(MAX_DIST / stops[stop].dist, zoom / 0.72f);
                break;
            default:
                return false;
        }
        flyTo(target(), now, FLY_MS);
        return true;
    }

    /** Flies straight to a stop (the phone's buttons). */
    void show(int which, long now) {
        moveTo(Math.max(0, Math.min(STOPS - 1, which)), now);
        turn = 0;
        zoom = 1;
        flyTo(target(), now, FLY_MS);
    }

    private void moveTo(int next, long now) {
        if (next == stop) return;
        prevStop = stop;
        stop = next;
        stopAt = now;
    }

    private Pose target() {
        Pose p = stops[stop];
        return new Pose(p.x, p.y, (p.bearing + turn + 720) % 360, p.pitch, p.dist * zoom);
    }

    private void flyTo(Pose p, long now, long ms) {
        from = pose(now);
        to = p;
        flyStart = now;
        flyMs = ms;
    }

    /** Where the camera is at {@code now}: eased between the last pose and the next. */
    Pose pose(long now) {
        float t = flyMs <= 0 ? 1 : Math.max(0, Math.min(1, (now - flyStart) / (float) flyMs));
        float e = t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
        float db = turn(to.bearing - from.bearing);
        // Distance moves on a log scale and lifts a little mid-flight, the way map apps fly.
        double ld = Math.log(from.dist) + (Math.log(to.dist) - Math.log(from.dist)) * e;
        float hop = flyMs == OPEN_MS ? 0 : (float) Math.sin(Math.PI * e) * 0.18f * Math.min(1, travel() / 600f);
        return new Pose(from.x + (to.x - from.x) * e, from.y + (to.y - from.y) * e, from.bearing + db * e,
                from.pitch + (to.pitch - from.pitch) * e, (float) Math.exp(ld) * (1 + hop));
    }

    private float travel() {
        return (float) Math.hypot(to.x - from.x, to.y - from.y);
    }

    boolean flying(long now) {
        return now - flyStart < flyMs;
    }

    /** Advances the fades; returns whether anything still moves (the columns breathe, so while open, always). */
    boolean step(long now) {
        float dt = Math.max(0, Math.min(0.1f, (now - lastStep) / 1000f));
        lastStep = now;
        boolean ready = !flying(now) && baseImage != null && to.same(basePose);
        baseA = ready ? Math.min(1, baseA + dt / 0.35f) : flying(now) ? Math.max(0, baseA - dt / 0.12f) : baseA;
        return opened;
    }

    // ---- Drawing ------------------------------------------------------------------------------

    /**
     * Draws the map over the whole screen. {@code d} is pixels per dp. {@code bikes} may be null
     * (the map then shows the city and waits for Bluebikes).
     */
    void draw(Gfx g, float w, float h, float d, long now, Model.Bikes bikes, float alpha) {
        if (alpha <= 0.003f) return;
        Pose p = pose(now);
        boolean fly = flying(now);
        if (!fly) want(g, (int) w, (int) h);
        live.setup(p, w, h);
        g.fillRectLinear(0, 0, w, h, 0, 0, 0, h, BG, BG_STOPS, alpha);
        float rise = Math.max(0, Math.min(1, (now - openedAt - 250) / 1300f));
        Object img = baseImage;
        boolean imageOk = img != null && p.same(basePose) && baseW == (int) w && baseH == (int) h;
        if (imageOk && baseA > 0.003f) {
            if (baseA < 0.999f) live.city(g, true, ease(rise), alpha);
            g.drawImage(img, 0, 0, w, h, Math.round(255 * alpha * baseA));
        } else {
            live.city(g, true, ease(rise), alpha);
        }
        float oa = alpha * Math.max(0, Math.min(1, (now - openedAt - 900) / 700f));
        frameDt = Math.max(0, Math.min(0.1f, (now - lastDraw) / 1000f));
        lastDraw = now;
        bearingNow = p.bearing;
        live.overlay(g, d, now, bikes, oa);
        // The panel swaps what it shows while faded out, and its box eases to the new height.
        float k = Math.max(0, Math.min(1, (now - stopAt) / 520f));
        int shown = k < 0.4f ? prevStop : stop;
        float ca = k < 0.4f ? 1 - k / 0.4f : Math.min(1, (k - 0.4f) / 0.6f);
        float target = live.panelHeight(shown, bikes, d);
        panelH = panelH <= 0 ? target : panelH + (target - panelH) * (1 - (float) Math.exp(-frameDt * 14));
        live.chrome(g, d, now, bikes, oa);
        live.panel(g, d, bikes, shown, panelH, ca, oa);
    }

    private static float ease(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** Asks the worker for the whole city at the pose the camera is landing on. */
    private void want(Gfx g, int w, int h) {
        if (to.same(basePose) && baseW == w && baseH == h) return;
        if (sync) {
            render(g, to, w, h);
            return;
        }
        synchronized (lock) {
            if (to.same(wanted) && wantedW == w && wantedH == h) return;
            wanted = to;
            wantedGfx = g;
            wantedW = w;
            wantedH = h;
            if (worker == null) {
                worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        work();
                    }
                }, "vestor-map");
                worker.setDaemon(true);
                worker.setPriority(Thread.NORM_PRIORITY - 1);
                worker.start();
            }
            lock.notifyAll();
        }
    }

    private void work() {
        while (running) {
            Pose p;
            Gfx g;
            int w, h;
            synchronized (lock) {
                while (running && wanted == null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running) return;
                p = wanted;
                g = wantedGfx;
                w = wantedW;
                h = wantedH;
                wanted = null;
            }
            try {
                render(g, p, w, h);
            } catch (RuntimeException | OutOfMemoryError e) {
                // The live drawing carries on without the finished image.
            }
        }
    }

    private Renderer back;

    private void render(Gfx g, Pose p, int w, int h) {
        Object img = g.offscreen(w, h);
        Gfx into = g.into(img);
        if (back == null) back = new Renderer();
        back.setup(p, w, h);
        back.city(into, false, 1, 1);
        if (!running && !sync) return;
        baseW = w;
        baseH = h;
        baseImage = img;
        basePose = p;
    }

    /**
     * Projection and drawing for one thread: the UI thread and the worker each have one, with
     * their own buffers, so neither waits for the other.
     */
    final class Renderer {
        float cx, cy, f, sinB, cosB, sinP, cosP, dist, tx, ty, camX, camY;
        float w, h, fogNear, fogFar;
        /** Per-shape scratch, one array per point count, reused every frame. */
        private float[][] byLen = new float[64][];
        private final float[] out = new float[3];
        private float[] clip = new float[256];
        private float[] order = new float[0];
        private int[] orderIdx = new int[0];

        void setup(Pose p, float w, float h) {
            this.w = w;
            this.h = h;
            // Right of middle, so what the camera looks at clears the panel on the left.
            cx = w * 0.6f;
            cy = h * 0.54f;
            // A 34 degree tall view, like a phone camera held upright: perspective without distortion.
            f = (float) (h / 2 / Math.tan(Math.toRadians(17)));
            double b = Math.toRadians(p.bearing), ph = Math.toRadians(p.pitch);
            sinB = (float) Math.sin(b);
            cosB = (float) Math.cos(b);
            sinP = (float) Math.sin(ph);
            cosP = (float) Math.cos(ph);
            dist = p.dist;
            tx = p.x;
            ty = p.y;
            camX = tx - sinB * dist * sinP;
            camY = ty - cosB * dist * sinP;
            fogNear = dist * 0.85f;
            fogFar = dist * 3.2f;
        }

        /** Projects (x, y, z) in metres into out[0..2]: screen x, screen y, depth. */
        float[] project(float x, float y, float z) {
            float dx = x - tx, dy = y - ty;
            float u = dx * cosB - dy * sinB, v = dx * sinB + dy * cosB;
            float depth = v * sinP - z * cosP + dist;
            float up = v * cosP + z * sinP;
            float k = depth > 1 ? f / depth : f;
            out[0] = cx + u * k;
            out[1] = cy - up * k;
            out[2] = depth;
            return out;
        }

        float depth(float x, float y, float z) {
            float dx = x - tx, dy = y - ty;
            return (dx * sinB + dy * cosB) * sinP - z * cosP + dist;
        }

        /** Whether a sphere around (x, y, z) could be on screen. */
        boolean visible(float x, float y, float z, float reach) {
            float d = depth(x, y, z);
            if (d < -reach) return false;
            if (d < reach + 2) return true;
            project(x, y, z);
            float r = reach * f / d;
            return out[0] + r > 0 && out[0] - r < w && out[1] + r > 0 && out[1] - r < h;
        }

        int fog(int c, float depth, float a) {
            float t = (depth - fogNear) / (fogFar - fogNear);
            t = Math.max(0, Math.min(0.88f, t));
            return Pixels.alpha(Pixels.lerp(c, FOG, t), a);
        }

        private float[] buf(int len) {
            if (len >= byLen.length) byLen = Arrays.copyOf(byLen, len * 2);
            float[] b = byLen[len];
            if (b == null) byLen[len] = b = new float[len];
            return b;
        }

        /**
         * Projects a flat shape at height z, clipped to the near plane. Returns the screen points
         * in an array of exactly the right length, or null when nothing is in front.
         */
        float[] flat(float[] xy, float z, boolean closed) {
            int n = xy.length / 2;
            float near = Math.max(4, dist * 0.04f);
            if (clip.length < n * 6 + 8) clip = new float[n * 6 + 8];
            int m = 0;
            boolean allIn = true;
            for (int i = 0; i < n; i++) if (depth(xy[i * 2], xy[i * 2 + 1], z) < near) allIn = false;
            if (allIn) {
                float[] o = buf(n * 2);
                for (int i = 0; i < n; i++) {
                    project(xy[i * 2], xy[i * 2 + 1], z);
                    o[i * 2] = out[0];
                    o[i * 2 + 1] = out[1];
                }
                return o;
            }
            // Sutherland-Hodgman against the near plane, in world space, then project. A street
            // that leaves and comes back is joined straight across, below the screen's edge.
            int edges = closed ? n : n - 1;
            for (int i = 0; i < edges; i++) {
                int j = (i + 1) % n;
                float ax = xy[i * 2], ay = xy[i * 2 + 1], bx = xy[j * 2], by = xy[j * 2 + 1];
                float da = depth(ax, ay, z) - near, db = depth(bx, by, z) - near;
                if (da >= 0) {
                    clip[m++] = ax;
                    clip[m++] = ay;
                }
                if ((da >= 0) != (db >= 0)) {
                    float t = da / (da - db);
                    clip[m++] = ax + (bx - ax) * t;
                    clip[m++] = ay + (by - ay) * t;
                }
            }
            if (!closed && depth(xy[(n - 1) * 2], xy[(n - 1) * 2 + 1], z) >= near) {
                clip[m++] = xy[(n - 1) * 2];
                clip[m++] = xy[(n - 1) * 2 + 1];
            }
            if (m < (closed ? 6 : 4)) return null;
            float[] o = buf(m);
            for (int i = 0; i < m / 2; i++) {
                project(clip[i * 2], clip[i * 2 + 1], z);
                o[i * 2] = out[0];
                o[i * 2 + 1] = out[1];
            }
            return o;
        }

        /** The ground, water, parks, streets and buildings. {@code lite} draws only the bigger buildings. */
        void city(Gfx g, boolean lite, float rise, float a) {
            // The ground under the map's box, then everything on it.
            float[] box = {geo.minX, geo.minY, geo.maxX, geo.minY, geo.maxX, geo.maxY, geo.minX, geo.maxY};
            float[] s = flat(box, 0, true);
            if (s != null) g.fillPolygon(s, fog(GROUND, dist, a));
            for (MapGeo.Shape sh : geo.parks) area(g, sh, PARK, a);
            for (MapGeo.Shape sh : geo.water) area(g, sh, WATER, a);
            for (MapGeo.Shape sh : geo.water) edge(g, sh, a);
            for (MapGeo.Shape sh : geo.islands) area(g, sh, GROUND, a);
            for (MapGeo.Shape sh : geo.roads) road(g, sh, lite, a);
            for (MapGeo.Shape sh : geo.rails) rail(g, sh, a);
            // Pardee's ride first: Law's shares most of it and lies on top.
            for (int i = geo.routes.size() - 1; i >= 0; i--) route(g, geo.routes.get(i), a);
            edgeFade(g, a);
            buildings(g, lite, rise, a);
        }

        private void area(Gfx g, MapGeo.Shape sh, int color, float a) {
            if (!visible(sh.cx, sh.cy, 0, sh.reach)) return;
            float[] s = flat(sh.xy, 0, true);
            if (s != null) g.fillPolygon(s, fog(color, depth(sh.cx, sh.cy, 0), a));
        }

        /** A faint lighter line along the river banks. */
        private void edge(Gfx g, MapGeo.Shape sh, float a) {
            if (!visible(sh.cx, sh.cy, 0, sh.reach)) return;
            float[] s = flat(sh.xy, 0, true);
            if (s != null) g.strokePolygon(s, Math.max(1, f * 3 / dist), fog(WATER_EDGE, depth(sh.cx, sh.cy, 0), a * 0.8f));
        }

        private void road(Gfx g, MapGeo.Shape sh, boolean lite, float a) {
            int kind = sh.tag & 7;
            boolean bridge = (sh.tag & MapGeo.BRIDGE) != 0;
            if (lite && kind >= MapGeo.SERVICE && kind != MapGeo.CYCLEWAY) return;
            if (!visible(sh.cx, sh.cy, 0, sh.reach)) return;
            float dep = depth(sh.cx, sh.cy, 0);
            float wpx = ROAD_W[kind] * f / Math.max(1, dep);
            if (wpx < 0.35f && kind >= MapGeo.RESIDENTIAL) return;
            if (bridge) {
                // A deck six metres up with its shadow on the water.
                float[] sh0 = flat(sh.xy, 0, false);
                if (sh0 != null) g.polyline(sh0, Math.max(1, wpx * 1.3f), fog(0xFF071422, dep, a * 0.7f));
                float[] s = flat(sh.xy, 6, false);
                if (s != null) {
                    g.polyline(s, Math.max(1, wpx * 1.15f), fog(0xFF1A1E25, dep, a));
                    g.polyline(s, Math.max(1, wpx * 0.8f), fog(Pixels.lerp(ROAD_COLOR[kind], 0xFF6B7484, 0.35f), dep, a));
                }
                return;
            }
            float[] s = flat(sh.xy, 0, false);
            if (s != null) g.polyline(s, Math.max(0.8f, wpx), fog(ROAD_COLOR[kind], dep, a * Math.min(1, wpx * 1.5f + 0.2f)));
        }

        private void rail(Gfx g, MapGeo.Shape sh, float a) {
            if (!visible(sh.cx, sh.cy, 0, sh.reach)) return;
            float dep = depth(sh.cx, sh.cy, 0);
            float[] s = flat(sh.xy, 0.5f, false);
            if (s == null) return;
            if (sh.tag == 0) g.polyline(s, Math.max(1, 3.2f * f / dep), fog(GREEN_LINE, dep, a * 0.9f));
            else g.polyline(s, Math.max(1, 2.4f * f / dep), fog(RAIL, dep, a * 0.8f));
        }

        /** The rides themselves, a soft band under the moving lights the overlay adds. */
        private void route(Gfx g, MapGeo.Shape sh, float a) {
            float dep = depth(sh.cx, sh.cy, 0);
            float[] s = flat(sh.xy, 1, false);
            if (s == null) return;
            int c = sh.tag == MapGeo.TO_LAW ? LAW_C : PARDEE_C;
            g.polyline(s, Math.max(2, 13 * f / dep), fog(c, dep, a * 0.16f));
            g.polyline(s, Math.max(1.2f, 4 * f / dep), fog(c, dep, a * 0.75f));
        }

        /** Bands of night laid over the box's edges, so the city fades out rather than stops. */
        private void edgeFade(Gfx g, float a) {
            float step = 70;
            for (int i = 0; i < 6; i++) {
                float in0 = i * step, in1 = (i + 1) * step;
                float al = (1 - i / 6f) * 0.85f;
                float x0 = geo.minX + in0, x1 = geo.maxX - in0, y0 = geo.minY + in0, y1 = geo.maxY - in0;
                float ix0 = geo.minX + in1, ix1 = geo.maxX - in1, iy0 = geo.minY + in1, iy1 = geo.maxY - in1;
                band(g, new float[] {x0, y0, x1, y0, ix1, iy0, ix0, iy0}, al * a);
                band(g, new float[] {x1, y0, x1, y1, ix1, iy1, ix1, iy0}, al * a);
                band(g, new float[] {x1, y1, x0, y1, ix0, iy1, ix1, iy1}, al * a);
                band(g, new float[] {x0, y1, x0, y0, ix0, iy0, ix0, iy1}, al * a);
            }
        }

        private void band(Gfx g, float[] q, float a) {
            float[] s = flat(q, 0, true);
            if (s != null) g.fillPolygon(s, Pixels.alpha(FOG, a));
        }

        private void buildings(Gfx g, boolean lite, float rise, float a) {
            List<MapGeo.Shape> all = geo.buildings;
            if (order.length < all.size()) {
                order = new float[all.size()];
                orderIdx = new int[all.size()];
            }
            int n = 0;
            for (int i = 0; i < all.size(); i++) {
                MapGeo.Shape b = all.get(i);
                if (lite && b.tag == MapGeo.PLAIN && b.size < LITE_HEIGHT && b.area < LITE_AREA) continue;
                if (!visible(b.cx, b.cy, b.size / 2, b.reach + b.size / 2)) continue;
                float dep = depth(b.cx, b.cy, b.size / 2);
                if (dep < dist * 0.06f) continue;
                order[n] = dep;
                orderIdx[n] = i;
                n++;
            }
            sortFarFirst(n);
            for (int k = 0; k < n; k++) {
                MapGeo.Shape b = all.get(orderIdx[k]);
                float hgt = b.size * riseAt(b, rise);
                if (hgt < 0.3f) continue;
                drawBuilding(g, b, hgt, order[k], a);
            }
        }

        /** Buildings grow up when the map opens, nearest the camera's target first. */
        private float riseAt(MapGeo.Shape b, float rise) {
            if (rise >= 1) return 1;
            float d = (float) Math.hypot(b.cx - tx, b.cy - ty);
            float t = (rise * 1.6f - d / 3000f) / 0.6f;
            t = Math.max(0, Math.min(1, t));
            return 1 - (1 - t) * (1 - t) * (1 - t);
        }

        private void sortFarFirst(int n) {
            // Insertion sort is quadratic; a shell sort keeps 4,700 buildings well under a millisecond.
            for (int gap = n / 2; gap > 0; gap = gap == 2 ? 1 : (int) (gap / 2.2f)) {
                for (int i = gap; i < n; i++) {
                    float v = order[i];
                    int vi = orderIdx[i];
                    int j = i;
                    while (j >= gap && order[j - gap] < v) {
                        order[j] = order[j - gap];
                        orderIdx[j] = orderIdx[j - gap];
                        j -= gap;
                    }
                    order[j] = v;
                    orderIdx[j] = vi;
                }
            }
        }

        private final float[] quad = new float[8];
        private final float[] p0 = new float[2], p1 = new float[2];

        private void drawBuilding(Gfx g, MapGeo.Shape b, float hgt, float dep, float a) {
            float[] xy = b.xy;
            int n = xy.length / 2;
            int accent = b.tag == MapGeo.BU_LAW ? LAW_C : b.tag == MapGeo.PARDEE ? PARDEE_C : b.tag == MapGeo.HOME ? HOME_C : 0;
            // Taller buildings catch a little more light on their roofs.
            float lift = Math.min(1, b.size / 90f);
            int roof = Pixels.lerp(ROOF, 0xFF4A5363, lift * 0.6f);
            int wallLit = Pixels.lerp(WALL, 0xFF3A4250, lift * 0.5f);
            if (b.tag == MapGeo.DOME || b.tag == MapGeo.PRUDENTIAL) {
                roof = 0xFF6E7685;
                wallLit = 0xFF4E5664;
            }
            if (accent != 0) {
                roof = Pixels.lerp(roof, accent, 0.75f);
                wallLit = Pixels.lerp(wallLit, accent, 0.5f);
            }
            float signed = signedArea(xy);
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float ax = xy[i * 2], ay = xy[i * 2 + 1], bx = xy[j * 2], by = xy[j * 2 + 1];
                // Outward normal of this wall, whichever way the ring runs.
                float nx = by - ay, ny = -(bx - ax);
                if (signed < 0) {
                    nx = -nx;
                    ny = -ny;
                }
                if ((camX - ax) * nx + (camY - ay) * ny <= 0) continue;
                float len = (float) Math.hypot(nx, ny);
                if (len < 0.01f) continue;
                // Light from the west-south-west, low, like a late afternoon on the river.
                float lit = (nx * -0.82f + ny * -0.42f) / len;
                float shade = 0.35f + 0.65f * Math.max(0, lit);
                project(ax, ay, 0);
                quad[0] = out[0];
                quad[1] = out[1];
                project(bx, by, 0);
                quad[2] = out[0];
                quad[3] = out[1];
                project(bx, by, hgt);
                quad[4] = out[0];
                quad[5] = out[1];
                project(ax, ay, hgt);
                quad[6] = out[0];
                quad[7] = out[1];
                g.fillPolygon(quad, fog(Pixels.lerp(WALL_DARK, wallLit, shade), dep, a));
            }
            float[] s = flat(xy, hgt, true);
            if (s != null) {
                g.fillPolygon(s, fog(roof, dep, a));
                if (accent != 0) g.strokePolygon(s, Math.max(1, f * 1.2f / dep), fog(Pixels.lerp(accent, 0xFFFFFFFF, 0.3f), dep, a));
            }
        }

        // ---- The live layer: docks, rides, places ---------------------------------------------

        void overlay(Gfx g, float d, long now, Model.Bikes bikes, float a) {
            if (a <= 0.003f) return;
            names(g, d, a);
            rides(g, d, now, a);
            List<Model.Station> docks = bikes == null ? null : bikes.area;
            String home = bikes == null || bikes.main() == null ? "" : bikes.main().id;
            String alt = bikes == null ? "" : bikes.altId;
            List<String> lawIds = new ArrayList<>(), pardeeIds = new ArrayList<>();
            Model.Place law = place(bikes, 0), pardee = place(bikes, 1);
            if (law != null) for (Model.Station s : law.docks) lawIds.add(s.id);
            if (pardee != null) for (Model.Station s : pardee.docks) pardeeIds.add(s.id);
            if (docks != null) {
                // Far to near, so nearer columns stand in front.
                int n = docks.size();
                float[] dep = new float[n];
                Integer[] idx = new Integer[n];
                for (int i = 0; i < n; i++) {
                    Model.Station s = docks.get(i);
                    dep[i] = depth(MapGeo.x(s.lon), MapGeo.y(s.lat), 0);
                    idx[i] = i;
                }
                final float[] dd = dep;
                Arrays.sort(idx, new java.util.Comparator<Integer>() {
                    @Override
                    public int compare(Integer x, Integer y) {
                        return Float.compare(dd[y], dd[x]);
                    }
                });
                for (Integer i : idx) {
                    Model.Station s = docks.get(i);
                    int accent = s.id.equals(home) || s.id.equals(alt) ? HOME_C : lawIds.contains(s.id) ? LAW_C
                            : pardeeIds.contains(s.id) ? PARDEE_C : 0;
                    column(g, s, accent, d, now, a);
                }
            }
            tags(g, d, bikes, a);
        }

        private Model.Place place(Model.Bikes b, int i) {
            return b == null || b.places.size() <= i ? null : b.places.get(i);
        }

        // ---- The three places' tags -----------------------------------------------------------

        /** Where the three tags went in the last frame (Westgate, BU Law, Pardee), for the tests. */
        final float[] tagL = new float[3], tagT = new float[3], tagW = new float[3];
        final boolean[] tagShown = new boolean[3];
        float tagH;

        /**
         * A small tag over each place on a thin stem: its name and the one number that matters.
         * The place being looked at is placed first; the others step aside so none covers another
         * or the panel.
         */
        private void tags(Gfx g, float d, Model.Bikes bikes, float a) {
            double[] lon = {Gbfs.WESTGATE_LON, Gbfs.BU_LAW_LON, Gbfs.PARDEE_LON};
            double[] lat = {Gbfs.WESTGATE_LAT, Gbfs.BU_LAW_LAT, Gbfs.PARDEE_LAT};
            float[] lift = {120, 140, 110};
            String[] names = {"WESTGATE", "BU LAW", "PARDEE"};
            int[] accents = {HOME_C, LAW_C, PARDEE_C};
            String[][] vals = {homeValue(bikes), placeValue(place(bikes, 0)), placeValue(place(bikes, 1))};
            int focus = stop == HOME ? 0 : stop == LAW ? 1 : stop == PARDEE ? 2 : -1;
            float[] dep = new float[3];
            for (int i = 0; i < 3; i++) dep[i] = depth(MapGeo.x(lon[i]), MapGeo.y(lat[i]), 0);
            tagH = d * 54;
            Integer[] order = {0, 1, 2};
            final float[] dd = dep;
            final int ff = focus;
            Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer x, Integer y) {
                    if (x == ff) return -1;
                    if (y == ff) return 1;
                    return Float.compare(dd[x], dd[y]);
                }
            });
            float ch = d * 54, top = d * 112, bottom = h - d * 64 - ch, left = panelRight(d) + d * 18, right = w - d * 40;
            boolean[] shown = new boolean[3];
            float[] gx = new float[3], gy = new float[3];
            int placed = 0;
            int[] done = new int[3];
            for (Integer i : order) {
                float x = MapGeo.x(lon[i]), y = MapGeo.y(lat[i]);
                project(x, y, 0);
                if (out[2] < dist * 0.05f) continue;
                gx[i] = out[0];
                gy[i] = out[1];
                project(x, y, lift[i]);
                float px = out[0], py = out[1];
                float nw = g.measure(names[i], Gfx.MONO, d * 10.5f, 0.14f) + d * 14;
                float vw = g.measure(vals[i][0], Gfx.TEXT_SEMIBOLD, d * 22, 0) + d * 6 + g.measure(vals[i][1], Gfx.TEXT_MEDIUM, d * 12.5f, 0);
                float cw = Math.max(nw, vw) + d * 26;
                float l = Math.max(left, Math.min(right - cw, px - cw / 2));
                float t = Math.max(top, Math.min(bottom, py - ch - d * 8));
                // Step clear of the tags already placed: up if there is room, else down.
                for (int pass = 0; pass < 4; pass++) {
                    boolean moved = false;
                    for (int k = 0; k < placed; k++) {
                        int j = done[k];
                        if (l < tagL[j] + tagW[j] + d * 8 && l + cw + d * 8 > tagL[j] && t < tagT[j] + ch + d * 8 && t + ch + d * 8 > tagT[j]) {
                            float up = tagT[j] - ch - d * 10;
                            t = up >= top ? up : tagT[j] + ch + d * 10;
                            moved = true;
                        }
                    }
                    if (!moved) break;
                }
                tagL[i] = l;
                tagT[i] = Math.min(t, h - d * 64 - ch);
                tagW[i] = cw;
                shown[i] = true;
                done[placed++] = i;
            }
            System.arraycopy(shown, 0, tagShown, 0, 3);
            // Stems first, so no stem crosses a tag.
            for (int i = 0; i < 3; i++) {
                if (!shown[i]) continue;
                float al = a * (focus < 0 || focus == i ? 1 : 0.8f);
                int c = accents[i];
                float l = tagL[i], t = tagT[i], cw = tagW[i];
                boolean onScreen = gx[i] > -d * 40 && gx[i] < w + d * 40 && gy[i] > -d * 40 && gy[i] < h + d * 40;
                if (!onScreen) {
                    // The place is off screen: a small arrow on the tag points the way.
                    float mx = l + cw / 2, my = t + ch / 2, dx = gx[i] - mx, dy = gy[i] - my;
                    float len = (float) Math.hypot(dx, dy);
                    if (len > 1) {
                        dx /= len;
                        dy /= len;
                        float k = Math.min(Math.abs(dx) < 1e-3f ? 1e9f : (cw / 2) / Math.abs(dx), Math.abs(dy) < 1e-3f ? 1e9f : (ch / 2) / Math.abs(dy));
                        float bx = mx + dx * (k + d * 4), by = my + dy * (k + d * 4);
                        g.fillPolygon(new float[] {bx + dx * d * 9, by + dy * d * 9, bx - dy * d * 6, by + dx * d * 6, bx + dy * d * 6, by - dx * d * 6},
                                Pixels.alpha(c, al));
                    }
                }
                if (onScreen) {
                    float sx = Math.max(l + d * 16, Math.min(l + cw - d * 16, gx[i])), sy = gy[i] < t ? t : t + ch;
                    g.line(gx[i], gy[i], sx, sy, Math.max(1, d * 1.2f), Pixels.alpha(c, al * 0.7f));
                    g.fillCircle(gx[i], gy[i], d * 6.5f, Pixels.alpha(c, al * 0.22f));
                    g.fillCircle(gx[i], gy[i], d * 3.2f, Pixels.alpha(c, al));
                }
            }
            for (int i = 0; i < 3; i++) {
                if (!shown[i]) continue;
                float al = a * (focus < 0 || focus == i ? 1 : 0.8f);
                int c = accents[i];
                float l = tagL[i], t = tagT[i], cw = tagW[i];
                g.fillRoundRect(l, t, l + cw, t + ch, d * 13, Pixels.alpha(0xEB0C1017, al));
                g.strokeRoundRect(l, t, l + cw, t + ch, d * 13, Math.max(1, d * (focus == i ? 1.6f : 1.1f)),
                        Pixels.alpha(c, al * (focus == i ? 1 : 0.6f)));
                g.fillCircle(l + d * 17, t + d * 16.5f, d * 3.5f, Pixels.alpha(c, al));
                g.text(names[i], Gfx.MONO, d * 10.5f, 0.14f, l + d * 27, t + d * 20.5f, Pixels.alpha(c, al), Gfx.LEFT, 0);
                boolean bad = vals[i][2] != null;
                float vw = g.measure(vals[i][0], Gfx.TEXT_SEMIBOLD, d * 22, 0);
                g.text(vals[i][0], Gfx.TEXT_SEMIBOLD, d * 22, 0, l + d * 13, t + d * 44.5f, Pixels.alpha(bad ? FULL_C : TEXT, al), Gfx.LEFT, 0);
                g.text(vals[i][1], Gfx.TEXT_MEDIUM, d * 12.5f, 0, l + d * 19 + vw, t + d * 44, Pixels.alpha(TEXT2, al), Gfx.LEFT, 0);
            }
        }

        /** Westgate's number: e-bikes at the watched docks. The third entry is set when it is bad news. */
        private String[] homeValue(Model.Bikes b) {
            Model.Station m = b == null ? null : b.main();
            if (m == null || !m.live) return new String[] {"", "Waiting", null};
            int e = b.watchedEbikes();
            return new String[] {String.valueOf(e), e == 1 ? "e-bike" : "e-bikes", e == 0 ? "" : null};
        }

        /** A place's number: free docks within a short walk, or Full. */
        private String[] placeValue(Model.Place p) {
            if (p == null || !p.live()) return new String[] {"", "Waiting", null};
            int n = p.freeDocks();
            if (n == 0) return new String[] {"Full", "", ""};
            return new String[] {String.valueOf(n), n == 1 ? "free dock" : "free docks", null};
        }

        /** Westgate's lines: e-bikes at the watched docks, where they are, and the classic bikes. */
        String[] homeLines(Model.Bikes b) {
            Model.Station m = b == null ? null : b.main();
            if (m == null || !m.live) return new String[] {"Waiting for Bluebikes", "", ""};
            int e = b.watchedEbikes();
            String head = e == 0 ? "No e-bikes right now" : e == 1 ? "1 e-bike ready" : e + " e-bikes ready";
            Model.Station o = b.alt();
            String sub = m.rentableEbikes() + " at " + b.label + (o != null ? ", " + o.rentableEbikes() + " at " + b.altLabel : "");
            int c = m.rentableClassic() + (o == null ? 0 : o.rentableClassic());
            return new String[] {head, sub, c == 1 ? "1 classic bike too" : c + " classic bikes too"};
        }

        /**
         * A place's lines: the nearest dock's free docks or, when it is full, the next dock that has
         * room; then the rest; then the ride from Westgate.
         */
        String[] placeLines(Model.Place p, int routeTag) {
            String ride = ride(routeTag);
            if (p == null || !p.live() || p.nearest() == null) return new String[] {"Waiting for Bluebikes", "", ride};
            Model.Station n = p.nearest(), free = p.firstFree();
            String nn = Gbfs.placeName(n);
            int here = n.live ? n.returnableDocks() : 0;
            String head, sub;
            if (here > 0) {
                head = (here == 1 ? "1 free dock" : here + " free docks") + " at " + nn;
                int more = p.freeDocks() - here;
                sub = more > 0 ? more + " more at the other docks nearby" : "None free at the other docks";
            } else if (free != null) {
                head = nn + (n.live && !n.returning ? " is not taking bikes" : " is full");
                int f = free.returnableDocks();
                sub = f + " free at " + Gbfs.placeName(free) + ", " + walk(free.metres) + " walk";
            } else {
                head = "Every dock nearby is full";
                sub = "Nearest is " + nn;
            }
            return new String[] {head, sub, ride};
        }

        /** "7 min ride from Westgate · 1.4 km" for a route, or "". */
        String ride(int routeTag) {
            MapGeo.Shape r = geo.route(routeTag);
            if (r == null) return "";
            return Math.max(1, Math.round(r.size / RIDE_M_PER_MIN)) + " min ride from Westgate  ·  "
                    + String.format(Locale.US, "%.1f km", r.size / 1000f);
        }

        /** A lit point riding each route from Westgate, with a fading trail, over and over. */
        private void rides(Gfx g, float d, long now, float a) {
            for (MapGeo.Shape r : geo.routes) {
                int c = r.tag == MapGeo.TO_LAW ? LAW_C : PARDEE_C;
                float period = 3200 + r.size * 2.2f;
                float t = ((now + (r.tag == MapGeo.TO_LAW ? 0 : 1400)) % (long) period) / period;
                for (int k = 6; k >= 0; k--) {
                    float tt = t - k * 0.012f;
                    if (tt < 0) continue;
                    if (!along(r, tt * r.size)) continue;
                    project(p0[0], p0[1], 2);
                    if (out[2] < 5) continue;
                    float rad = Math.max(d * 1.2f, 6 * f / out[2]) * (1 - k * 0.11f);
                    float al = a * (k == 0 ? 1 : 0.5f * (1 - k / 7f));
                    if (k == 0) g.fillCircle(out[0], out[1], rad * 2.6f, Pixels.alpha(c, al * 0.22f));
                    g.fillCircle(out[0], out[1], rad, Pixels.alpha(k == 0 ? Pixels.lerp(c, 0xFFFFFFFF, 0.55f) : c, al));
                }
            }
        }

        /** The point {@code m} metres along a route, into p0. */
        private boolean along(MapGeo.Shape r, float m) {
            float[] xy = r.xy;
            float left = m;
            for (int i = 0; i + 3 < xy.length; i += 2) {
                float sx = xy[i + 2] - xy[i], sy = xy[i + 3] - xy[i + 1];
                float len = (float) Math.hypot(sx, sy);
                if (left <= len && len > 0) {
                    p0[0] = xy[i] + sx * left / len;
                    p0[1] = xy[i + 1] + sy * left / len;
                    return true;
                }
                left -= len;
            }
            return false;
        }

        /**
         * One dock as a glass column: e-bikes, then classic bikes, then broken ones, filled from
         * the ground; the free docks are the clear glass above. Named docks are wider, ringed
         * and numbered.
         */
        private void column(Gfx g, Model.Station s, int accent, float d, long now, float a) {
            float x = MapGeo.x(s.lon), y = MapGeo.y(s.lat);
            if (!visible(x, y, 20, 60)) return;
            boolean named = accent != 0;
            float r = named ? 11 : 6.5f;
            int cap = Math.max(1, Math.max(s.capacity, s.ebikes + s.classic + s.disabled + s.docks));
            float perDock = named ? 2.6f : 1.9f;
            float top = cap * perDock;
            float dep = depth(x, y, 0);
            if (dep < dist * 0.05f) return;
            float fade = a * (1 - Math.max(0, Math.min(0.7f, (dep - fogNear) / (fogFar - fogNear))));
            // A ring on the ground; the named docks' rings breathe.
            if (named) {
                float br = 0.5f + 0.5f * (float) Math.sin(now / 520.0 + x * 0.01);
                ring(g, x, y, r * (2.2f + 0.8f * br), Pixels.alpha(accent, fade * (0.10f + 0.10f * (1 - br))), true);
                ring(g, x, y, r * 1.6f, Pixels.alpha(accent, fade * 0.65f), false);
            } else {
                ring(g, x, y, r * 1.4f, Pixels.alpha(0xFFFFFFFF, fade * 0.18f), false);
            }
            if (!s.live) {
                cylinder(g, x, y, r, 0, top, 0x40FFFFFF, fade, true);
                return;
            }
            int e = s.rentableEbikes() > 0 ? s.ebikes : 0;
            float z = 0;
            z = segment(g, x, y, r, z, e * perDock, VOLT, fade);
            z = segment(g, x, y, r, z, s.classic * perDock, PEDAL, fade);
            z = segment(g, x, y, r, z, s.disabled * perDock, 0xFF5A606B, fade);
            // The free docks: clear glass with a bright rim, so "room to park" reads at a glance.
            float glassTop = Math.max(z, top);
            if (glassTop > z + 0.1f) cylinder(g, x, y, r, z, glassTop, s.returning ? 0x2EFFFFFF : 0x40FF6060, fade, true);
            cap(g, x, y, r, glassTop, z >= glassTop - 0.1f ? Pixels.lerp(z > 0 ? PEDAL : 0xFF5A606B, 0xFFFFFFFF, 0.35f) : 0x66FFFFFF, fade);
            if (named) number(g, s, accent, x, y, glassTop, d, fade);
        }

        private float segment(Gfx g, float x, float y, float r, float z, float hgt, int color, float a) {
            if (hgt <= 0.01f) return z;
            cylinder(g, x, y, r, z, z + hgt, color, a, false);
            return z + hgt;
        }

        private final float[] cyl = new float[2 * 26];

        /** The side of an upright cylinder from z0 to z1, lit on the left, with a rim when glass. */
        private void cylinder(Gfx g, float x, float y, float r, float z0, float z1, int color, float a, boolean glass) {
            // The silhouette: the near half of the bottom circle, then the far half of the top.
            int k = 0;
            for (int i = 0; i <= 12; i++) {
                double t = Math.PI * i / 12;
                // Points on the circle from the screen's right to its left, nearest the camera.
                float ox = (float) Math.cos(t), oy = (float) -Math.sin(t);
                float wx = x + r * (ox * cosB + oy * sinB), wy = y + r * (-ox * sinB + oy * cosB);
                project(wx, wy, z0);
                cyl[k++] = out[0];
                cyl[k++] = out[1];
            }
            for (int i = 12; i >= 0; i--) {
                double t = Math.PI * i / 12;
                float ox = (float) Math.cos(t), oy = (float) Math.sin(t);
                float wx = x + r * (ox * cosB + oy * sinB), wy = y + r * (-ox * sinB + oy * cosB);
                project(wx, wy, z1);
                cyl[k++] = out[0];
                cyl[k++] = out[1];
            }
            g.fillPolygon(cyl, Pixels.alpha(color, a));
            if (glass) {
                g.strokePolygon(cyl, Math.max(1, f * 0.6f / Math.max(1, depth(x, y, z1))), Pixels.alpha(0x59FFFFFF, a));
            } else {
                // A soft highlight down the lit side.
                project(x - r * 0.55f * cosB, y + r * 0.55f * sinB, z0);
                float hx0 = out[0], hy0 = out[1];
                project(x - r * 0.55f * cosB, y + r * 0.55f * sinB, z1);
                g.line(hx0, hy0, out[0], out[1], Math.max(1, f * r * 0.35f / Math.max(1, depth(x, y, z1))), Pixels.alpha(0x33FFFFFF, a));
            }
        }

        private void cap(Gfx g, float x, float y, float r, float z, int color, float a) {
            float[] c = circle(x, y, r, z);
            if (c != null) g.fillPolygon(c, Pixels.alpha(color, a));
        }

        private final float[] circleWorld = new float[48];

        private float[] circle(float x, float y, float r, float z) {
            for (int i = 0; i < 24; i++) {
                double t = Math.PI * 2 * i / 24;
                circleWorld[i * 2] = x + r * (float) Math.cos(t);
                circleWorld[i * 2 + 1] = y + r * (float) Math.sin(t);
            }
            return flat(circleWorld, z, true);
        }

        private void ring(Gfx g, float x, float y, float r, int color, boolean filled) {
            float[] c = circle(x, y, r, 0.3f);
            if (c == null) return;
            if (filled) g.fillPolygon(c, color);
            else g.strokePolygon(c, Math.max(1, f * 1.4f / Math.max(1, depth(x, y, 0))), color);
        }

        /** The number that matters above a named column: free docks near BU, e-bikes at home. */
        private void number(Gfx g, Model.Station s, int accent, float x, float y, float z, float d, float a) {
            project(x, y, z + 6);
            if (out[2] < 5) return;
            boolean bu = accent != HOME_C;
            int v = bu ? s.returnableDocks() : s.rentableEbikes();
            String t = String.valueOf(v);
            float size = Math.max(d * 13, Math.min(d * 22, 2600 / out[2] * d));
            float tw = g.measure(t, Gfx.TEXT_SEMIBOLD, size, 0);
            float cxp = out[0], by = out[1] - d * 6;
            float pad = size * 0.42f;
            g.fillRoundRect(cxp - tw / 2 - pad, by - size * 1.05f, cxp + tw / 2 + pad, by + size * 0.3f, size * 0.5f,
                    Pixels.alpha(0xE60D1118, a));
            g.strokeRoundRect(cxp - tw / 2 - pad, by - size * 1.05f, cxp + tw / 2 + pad, by + size * 0.3f, size * 0.5f, Math.max(1, d),
                    Pixels.alpha(v == 0 ? 0xFFFF6060 : accent, a * 0.9f));
            g.text(t, Gfx.TEXT_SEMIBOLD, size, 0, cxp, by, Pixels.alpha(v == 0 ? 0xFFFF8080 : bu ? TEXT : VOLT, a), Gfx.CENTER, 0);
        }

        // ---- The panel and the frame around the map --------------------------------------------

        float panelRight(float d) {
            return d * (40 + PANEL_W);
        }

        private List<Model.Station> rowsFor(int s, Model.Bikes b) {
            List<Model.Station> rows = new ArrayList<>();
            if (b == null) return rows;
            if (s == HOME) {
                if (b.main() != null) rows.add(b.main());
                if (b.alt() != null) rows.add(b.alt());
            } else if (s == LAW || s == PARDEE) {
                Model.Place p = place(b, s == LAW ? 0 : 1);
                if (p != null) rows.addAll(p.docks);
            }
            return rows;
        }

        /** How tall the panel is for stop {@code s}, in pixels. */
        float panelHeight(int s, Model.Bikes b, float d) {
            if (s == OVERVIEW) return d * (14 + 3 * BLOCK + LEGEND);
            return d * (126 + Math.max(1, rowsFor(s, b).size()) * ROW + 8 + LEGEND);
        }

        /**
         * The panel on the left: all three places at a glance or, at one place, its number in big
         * type and each dock near it with its bikes and free docks as a bar. {@code ca} fades the
         * content while it changes; the box itself does not.
         */
        void panel(Gfx g, float d, Model.Bikes b, int s, float ph, float ca, float a) {
            float l = d * 40, t = d * 112, r = panelRight(d), bt = t + ph;
            g.fillRoundRect(l, t, r, bt, d * 18, Pixels.alpha(0xF50B0F16, a));
            g.strokeRoundRect(l, t, r, bt, d * 18, Math.max(1, d), Pixels.alpha(0x1FFFFFFF, a));
            legend(g, d, l + d * 20, bt - d * 18, a);
            g.line(l + d * 20, bt - d * LEGEND, r - d * 20, bt - d * LEGEND, Math.max(1, d * 0.8f), Pixels.alpha(0x14FFFFFF, a));
            float x = l + d * 20, xr = r - d * 20, maxW = xr - x;
            a *= ca;
            if (a <= 0.003f) return;
            int save = g.save();
            g.clipRect(l, t, r, bt - d * LEGEND);
            if (s == OVERVIEW) {
                String[][] lines = {homeLines(b), placeLines(place(b, 0), MapGeo.TO_LAW), placeLines(place(b, 1), MapGeo.TO_PARDEE)};
                String[] names = {"WESTGATE", "BU LAW", "PARDEE"};
                int[] accents = {HOME_C, LAW_C, PARDEE_C};
                for (int i = 0; i < 3; i++) {
                    float y = t + d * 14 + i * d * BLOCK;
                    if (i > 0) g.line(x, y, xr, y, Math.max(1, d * 0.8f), Pixels.alpha(0x14FFFFFF, a));
                    g.fillCircle(x + d * 4, y + d * 20, d * 3.5f, Pixels.alpha(accents[i], a));
                    g.text(names[i], Gfx.MONO, d * 11, 0.14f, x + d * 14, y + d * 24, Pixels.alpha(accents[i], a), Gfx.LEFT, 0);
                    g.text(Text.fit(g, lines[i][0], Gfx.TEXT_SEMIBOLD, d * 17, maxW), Gfx.TEXT_SEMIBOLD, d * 17, 0, x, y + d * 49,
                            Pixels.alpha(TEXT, a), Gfx.LEFT, 0);
                    g.text(Text.fit(g, lines[i][1], Gfx.TEXT_REGULAR, d * 13, maxW), Gfx.TEXT_REGULAR, d * 13, 0, x, y + d * 68,
                            Pixels.alpha(TEXT2, a), Gfx.LEFT, 0);
                    g.text(Text.fit(g, lines[i][2], Gfx.TEXT_REGULAR, d * 12.5f, maxW), Gfx.TEXT_REGULAR, d * 12.5f, 0, x, y + d * 86,
                            Pixels.alpha(TEXT3, a), Gfx.LEFT, 0);
                }
                g.restore(save);
                return;
            }
            boolean home = s == HOME;
            int accent = home ? HOME_C : s == LAW ? LAW_C : PARDEE_C;
            String name = home ? "WESTGATE" : s == LAW ? "BU LAW" : "PARDEE";
            String[] val = home ? homeValue(b) : placeValue(place(b, s == LAW ? 0 : 1));
            String unit = home ? (val[1].equals("Waiting") ? "for Bluebikes" : val[1] + " ready")
                    : val[1].equals("Waiting") ? "for Bluebikes" : val[0].equals("Full") ? "every dock nearby" : val[1] + " nearby";
            String under = home ? homeLines(b)[2] : ride(s == LAW ? MapGeo.TO_LAW : MapGeo.TO_PARDEE);
            g.fillCircle(x + d * 4, t + d * 26, d * 3.5f, Pixels.alpha(accent, a));
            g.text(name, Gfx.MONO, d * 11.5f, 0.14f, x + d * 14, t + d * 30, Pixels.alpha(accent, a), Gfx.LEFT, 0);
            float vw = val[0].isEmpty() ? 0 : g.measure(val[0], Gfx.DISPLAY_SEMIBOLD, d * 50, 0) + d * 10;
            g.text(val[0], Gfx.DISPLAY_SEMIBOLD, d * 50, 0, x - d * 2, t + d * 86, Pixels.alpha(val[2] != null ? FULL_C : TEXT, a), Gfx.LEFT, 0);
            g.text(Text.fit(g, unit, Gfx.TEXT_MEDIUM, d * 15, maxW - vw), Gfx.TEXT_MEDIUM, d * 15, 0, x + vw, t + d * 84,
                    Pixels.alpha(TEXT2, a), Gfx.LEFT, 0);
            g.text(Text.fit(g, under, Gfx.TEXT_REGULAR, d * 13, maxW), Gfx.TEXT_REGULAR, d * 13, 0, x, t + d * 110,
                    Pixels.alpha(TEXT3, a), Gfx.LEFT, 0);
            List<Model.Station> rows = rowsFor(s, b);
            for (int i = 0; i < rows.size(); i++) {
                Model.Station st = rows.get(i);
                float y = t + d * 126 + i * d * ROW;
                g.line(x, y, xr, y, Math.max(1, d * 0.8f), Pixels.alpha(0x14FFFFFF, a));
                String label = home ? (i == 0 ? b.label : b.altLabel) : Gbfs.placeName(st);
                String fig, detail;
                int figC;
                if (!st.live) {
                    fig = "";
                    figC = TEXT3;
                    detail = "No live counts";
                } else if (home) {
                    int e = st.rentableEbikes();
                    fig = e == 1 ? "1 e-bike" : e + " e-bikes";
                    figC = e > 0 ? VOLT : TEXT3;
                    detail = st.rentableClassic() + " classic  ·  " + st.returnableDocks() + " free docks";
                } else {
                    int f = st.returnableDocks();
                    fig = !st.returning ? "Closed" : f + " free";
                    figC = f > 0 ? TEXT : FULL_C;
                    int e = st.rentableEbikes();
                    detail = walk(st.metres) + " walk  ·  " + (e == 1 ? "1 e-bike, " : e + " e-bikes, ") + st.rentableClassic() + " classic";
                }
                float fw = g.measure(fig, Gfx.TEXT_SEMIBOLD, d * 15, 0);
                g.text(fig, Gfx.TEXT_SEMIBOLD, d * 15, 0, xr, y + d * 24, Pixels.alpha(figC, a), Gfx.RIGHT, 0);
                g.text(Text.fit(g, label, Gfx.TEXT_SEMIBOLD, d * 15, maxW - fw - d * 10), Gfx.TEXT_SEMIBOLD, d * 15, 0, x, y + d * 24,
                        Pixels.alpha(TEXT, a), Gfx.LEFT, 0);
                g.text(Text.fit(g, detail, Gfx.TEXT_REGULAR, d * 12, maxW), Gfx.TEXT_REGULAR, d * 12, 0, x, y + d * 42,
                        Pixels.alpha(TEXT3, a), Gfx.LEFT, 0);
                bar(g, st, x, y + d * 48, xr, d * 4.5f, a);
            }
            g.restore(save);
        }

        /** A dock as a bar: e-bikes, classic, broken, then the free docks as the empty track. */
        private void bar(Gfx g, Model.Station s, float l, float t, float r, float hgt, float a) {
            float rad = hgt / 2;
            g.fillRoundRect(l, t, r, t + hgt, rad, Pixels.alpha(0x1AFFFFFF, a));
            if (!s.live) return;
            int e = s.rentableEbikes() > 0 ? s.ebikes : 0;
            int total = Math.max(1, Math.max(s.capacity, s.ebikes + s.classic + s.disabled + s.docks));
            float k = (r - l) / total;
            float x3 = l + (e + s.classic + s.disabled) * k, x2 = l + (e + s.classic) * k, x1 = l + e * k;
            if (x3 > l + 0.5f) g.fillRoundRect(l, t, Math.max(l + hgt, x3), t + hgt, rad, Pixels.alpha(0xFF5A606B, a));
            if (x2 > l + 0.5f) g.fillRoundRect(l, t, Math.max(l + hgt, x2), t + hgt, rad, Pixels.alpha(PEDAL, a));
            if (x1 > l + 0.5f) g.fillRoundRect(l, t, Math.max(l + hgt, x1), t + hgt, rad, Pixels.alpha(VOLT, a));
        }

        private void legend(Gfx g, float d, float x, float base, float a) {
            String[] labels = {"E-bikes", "Classic", "Free docks"};
            int[] colors = {VOLT, PEDAL, 0};
            for (int i = 0; i < 3; i++) {
                float sl = x, st = base - d * 11;
                if (colors[i] != 0) {
                    g.fillRoundRect(sl, st, sl + d * 9, st + d * 13, d * 3, Pixels.alpha(colors[i], a));
                } else {
                    g.fillRoundRect(sl, st, sl + d * 9, st + d * 13, d * 3, Pixels.alpha(0x26FFFFFF, a));
                    g.strokeRoundRect(sl, st, sl + d * 9, st + d * 13, d * 3, Math.max(1, d * 0.9f), Pixels.alpha(0x8CFFFFFF, a));
                }
                g.text(labels[i], Gfx.TEXT_MEDIUM, d * 12, 0, sl + d * 15, base, Pixels.alpha(TEXT2, a), Gfx.LEFT, 0);
                x += d * 15 + g.measure(labels[i], Gfx.TEXT_MEDIUM, d * 12, 0) + d * 18;
            }
        }

        /** The title and how fresh the counts are, the stop chips and compass, the keys and the credit. */
        void chrome(Gfx g, float d, long now, Model.Bikes b, float a) {
            float x = d * 40;
            g.fillRectLinear(0, 0, w, d * 165, 0, 0, 0, d * 165, SCRIM_TOP, SCRIM_STOPS, a);
            g.fillRectLinear(0, h - d * 120, w, h, 0, h - d * 120, 0, h, SCRIM_BOTTOM, SCRIM_STOPS, a);
            g.text("Bike map", Gfx.DISPLAY_SEMIBOLD, d * 28, 0, x - d, d * 66, Pixels.alpha(TEXT, a), Gfx.LEFT, 0);
            long at = b == null ? 0 : b.published > 0 ? b.published : b.fetched;
            boolean fresh = b != null && b.ok && at > 0 && now - at < 5 * 60000L;
            String sub = b == null || at <= 0 ? "Waiting for Bluebikes" : (fresh ? "Live from Bluebikes  ·  " : "Bluebikes  ·  ") + Cards.age(now - at);
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 420.0);
            int dot = fresh ? 0xFF3DDC84 : 0xFFFFB020;
            g.fillCircle(x + d * 4, d * 87, d * (5 + 3 * pulse), Pixels.alpha(dot, a * 0.18f * (1 - pulse)));
            g.fillCircle(x + d * 4, d * 87, d * 3.5f, Pixels.alpha(dot, a));
            g.text(sub, Gfx.TEXT_MEDIUM, d * 13, 0, x + d * 14, d * 91.5f, Pixels.alpha(TEXT2, a), Gfx.LEFT, 0);
            chips(g, d, a);
            // The keys, bottom left, and the map's credit, bottom right.
            float base = h - d * 30, kh = d * 21, kcy = base - d * 4.5f;
            int kc = Pixels.alpha(TEXT2, a);
            float kx = x;
            kx += Icons.keycap(g, kx, kcy, kh, "OK", kc) + d * 8;
            kx = hint(g, "Next: " + STOP_NAMES[(stop + 1) % STOPS], kx, base, d, a);
            kx = arrowCap(g, kx, kcy, kh, 3, kc) + d * 4;
            kx = arrowCap(g, kx, kcy, kh, 1, kc) + d * 8;
            kx = hint(g, "Turn", kx, base, d, a);
            kx = arrowCap(g, kx, kcy, kh, 0, kc) + d * 4;
            kx = arrowCap(g, kx, kcy, kh, 2, kc) + d * 8;
            kx = hint(g, "Zoom", kx, base, d, a);
            kx += Icons.keycap(g, kx, kcy, kh, "Back", kc) + d * 8;
            hint(g, "Close", kx, base, d, a);
            g.text("Map data © OpenStreetMap contributors, Overture Maps", Gfx.TEXT_REGULAR, d * 10.5f, 0, w - d * 40, base,
                    Pixels.alpha(TEXT3, a), Gfx.RIGHT, 0);
        }

        private float hint(Gfx g, String s, float x, float base, float d, float a) {
            g.text(s, Gfx.TEXT_MEDIUM, d * 12.5f, 0, x, base, Pixels.alpha(TEXT2, a), Gfx.LEFT, 0);
            return x + g.measure(s, Gfx.TEXT_MEDIUM, d * 12.5f, 0) + d * 22;
        }

        /** A keycap with an arrow: 0 up, 1 right, 2 down, 3 left. Returns its right edge. */
        private float arrowCap(Gfx g, float l, float cy, float hgt, int dir, int color) {
            g.strokeRoundRect(l, cy - hgt / 2, l + hgt, cy + hgt / 2, hgt * 0.3f, Math.max(1f, hgt * 0.07f), color);
            float c = l + hgt / 2, s = hgt * 0.2f;
            float[] tri;
            if (dir == 0) tri = new float[] {c, cy - s, c + s, cy + s * 0.6f, c - s, cy + s * 0.6f};
            else if (dir == 2) tri = new float[] {c, cy + s, c + s, cy - s * 0.6f, c - s, cy - s * 0.6f};
            else if (dir == 1) tri = new float[] {c + s, cy, c - s * 0.6f, cy - s, c - s * 0.6f, cy + s};
            else tri = new float[] {c - s, cy, c + s * 0.6f, cy - s, c + s * 0.6f, cy + s};
            g.fillPolygon(tri, color);
            return l + hgt;
        }

        /**
         * The four stops as chips, top right, with a pill that slides to the one in view, and a
         * compass beside them that turns with the map.
         */
        private void chips(Gfx g, float d, float a) {
            float ch = d * 34, cy = d * 58, pad = d * 16, gap = d * 2, size = d * 13;
            float[] cw = new float[STOPS];
            float total = d * 4;
            for (int i = 0; i < STOPS; i++) {
                cw[i] = g.measure(STOP_NAMES[i], Gfx.TEXT_SEMIBOLD, size, 0) + pad * 2 + (i == 0 ? 0 : d * 12);
                total += cw[i] + (i > 0 ? gap : 0);
            }
            total += d * 4;
            float r = w - d * 40, l = r - total;
            g.fillRoundRect(l, cy - ch / 2 - d * 4, r, cy + ch / 2 + d * 4, ch / 2 + d * 4, Pixels.alpha(0xD90B0F16, a));
            g.strokeRoundRect(l, cy - ch / 2 - d * 4, r, cy + ch / 2 + d * 4, ch / 2 + d * 4, Math.max(1, d), Pixels.alpha(0x1FFFFFFF, a));
            float x = l + d * 4;
            float[] cl = new float[STOPS];
            for (int i = 0; i < STOPS; i++) {
                cl[i] = x;
                x += cw[i] + gap;
            }
            // The pill eases toward the chip in view.
            float tl = cl[stop], tr = cl[stop] + cw[stop];
            if (pillR <= pillL) {
                pillL = tl;
                pillR = tr;
            }
            float k = 1 - (float) Math.exp(-frameDt * 12);
            pillL += (tl - pillL) * k;
            pillR += (tr - pillR) * k;
            int pc = STOP_COLORS[stop];
            g.fillRoundRect(pillL, cy - ch / 2, pillR, cy + ch / 2, ch / 2, Pixels.alpha(pc, a));
            float mid = (pillL + pillR) / 2;
            for (int i = 0; i < STOPS; i++) {
                boolean on = Math.abs(mid - (cl[i] + cw[i] / 2)) < cw[i] / 2;
                float tx0 = cl[i] + pad;
                if (i > 0) {
                    g.fillCircle(tx0 + d * 3.5f, cy, d * 3.5f, Pixels.alpha(on ? 0xFF0B0F16 : STOP_COLORS[i], a));
                    tx0 += d * 12;
                }
                g.text(STOP_NAMES[i], Gfx.TEXT_SEMIBOLD, size, 0, tx0, cy + g.capHeight(Gfx.TEXT_SEMIBOLD, size) / 2,
                        Pixels.alpha(on ? 0xFF0B0F16 : TEXT2, a), Gfx.LEFT, 0);
            }
            // The compass: north is where the red needle points.
            float ccx = l - d * 30, rad = d * 19;
            g.fillCircle(ccx, cy, rad, Pixels.alpha(0xD90B0F16, a));
            g.strokeCircle(ccx, cy, rad, Math.max(1, d), Pixels.alpha(0x1FFFFFFF, a));
            double th = Math.toRadians(-bearingNow);
            float nx = (float) Math.sin(th), ny = (float) -Math.cos(th), ex = -ny, ey = nx;
            float len = rad * 0.62f, half = rad * 0.2f;
            g.fillPolygon(new float[] {ccx + nx * len, cy + ny * len, ccx + ex * half, cy + ey * half, ccx - ex * half, cy - ey * half},
                    Pixels.alpha(0xFFFF5A5A, a));
            g.fillPolygon(new float[] {ccx - nx * len, cy - ny * len, ccx + ex * half, cy + ey * half, ccx - ex * half, cy - ey * half},
                    Pixels.alpha(0x8CF5F1EA, a));
        }

        /** River, campus and bridge names, small and quiet, fading with distance. */
        private void names(Gfx g, float d, float a) {
            for (int i = 0; i < NAMES.length; i++) {
                float x = MapGeo.x(NAME_AT[i * 2 + 1]), y = MapGeo.y(NAME_AT[i * 2]);
                if (!visible(x, y, 0, 10)) continue;
                project(x, y, 0);
                if (out[2] < dist * 0.1f) continue;
                float near = Math.max(0, Math.min(1, 1 - (out[2] - fogNear) / (fogFar - fogNear)));
                boolean water = i < 2;
                float size = d * (water ? 15 : 12);
                int c = water ? 0xFF6FA8D6 : 0xFFAEB6C4;
                g.text(NAMES[i], water ? Gfx.SERIF_ITALIC : Gfx.MONO, size, water ? 0.02f : 0.14f, out[0], out[1], Pixels.alpha(c, a * near * 0.8f),
                        Gfx.CENTER, 0.6f);
            }
        }
    }

    static final String[] NAMES = {"Charles River", "Charles River", "MIT", "BOSTON UNIVERSITY", "KENMORE", "BU BRIDGE", "HARVARD BRIDGE",
        "CAMBRIDGEPORT", "MAGAZINE BEACH", "BACK BAY", "FENWAY"};
    /** lat, lon of each name. */
    static final double[] NAME_AT = {42.3532, -71.0985, 42.3561, -71.1146, 42.3598, -71.0935, 42.3505, -71.1035, 42.3486, -71.0952,
        42.3531, -71.1120, 42.3545, -71.0914, 42.3612, -71.1045, 42.3567, -71.1162, 42.3494, -71.0845, 42.3448, -71.0985};

    /** An angle difference brought into -180..180 degrees. */
    static float turn(float deg) {
        return ((deg % 360) + 540) % 360 - 180;
    }

    /** "2 min", by a walk of 80 m a minute. */
    static String walk(float metres) {
        return Math.max(1, Math.round(metres / 80)) + " min";
    }

    static float signedArea(float[] xy) {
        double s = 0;
        int n = xy.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) s += (double) xy[j * 2] * xy[i * 2 + 1] - (double) xy[i * 2] * xy[j * 2 + 1];
        return (float) (s / 2);
    }
}
