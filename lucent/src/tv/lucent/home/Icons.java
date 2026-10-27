package tv.lucent.home;

/**
 * Glyphs drawn from geometry so they stay sharp at any size and need no icon font: weather,
 * settings, sound, bikes, docks, shuttles, aircraft. All are original drawings on a 24-unit grid
 * (an SF Symbols-style keyline: 2-unit strokes, round caps and joins, optical centring), scaled
 * to the box they are given. Clouds and moons are single polygons, so they fade without seams.
 */
final class Icons {
    private Icons() {}

    static final int SUN = 0, MOON = 1, PARTLY = 2, PARTLY_NIGHT = 3, CLOUD = 4, FOG = 5,
            DRIZZLE = 6, RAIN = 7, SNOW = 8, STORM = 9;

    /** Maps a WMO weather code (Open-Meteo) to a glyph. */
    static int forCode(int code, boolean day) {
        if (code == 0) return day ? SUN : MOON;
        if (code == 1 || code == 2) return day ? PARTLY : PARTLY_NIGHT;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return RAIN;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return CLOUD;
    }

    static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: return "Light Rain";
            case 63: return "Rain";
            case 65: return "Heavy Rain";
            case 66: case 67: return "Freezing Rain";
            case 71: return "Light Snow";
            case 73: return "Snow";
            case 75: return "Heavy Snow";
            case 77: return "Snow Grains";
            case 80: case 81: return "Showers";
            case 82: return "Heavy Showers";
            case 85: case 86: return "Snow Showers";
            case 95: return "Thunderstorms";
            case 96: case 97: case 99: return "Thunderstorms";
            default: return "";
        }
    }

    /** Draws weather glyph {@code kind} in one colour, in a box of {@code s} centred on (cx, cy). */
    static void weather(Gfx g, int kind, float cx, float cy, float s, int color) {
        weather(g, kind, cx, cy, s, color, color, color, color, color);
    }

    /**
     * The same glyph in Apple Weather's manner: a yellow sun, white clouds and moon, cyan rain and
     * a yellow bolt. Colours carry no meaning on their own; the shapes do.
     */
    static void weatherColor(Gfx g, int kind, float cx, float cy, float s, float alpha) {
        weather(g, kind, cx, cy, s, Pixels.alpha(0xFFFFD60A, alpha), Pixels.alpha(0xFFF5F6FA, alpha),
                Pixels.alpha(0xFF3CD3FE, alpha), Pixels.alpha(0xFFFFD60A, alpha), Pixels.alpha(0xFFEDEFFF, alpha));
    }

    private static void weather(Gfx g, int kind, float cx, float cy, float s, int sunC, int cloudC, int dropC, int boltC,
            int moonC) {
        switch (kind) {
            case SUN:
                sun(g, cx, cy, s, sunC);
                break;
            case MOON:
                moon(g, cx, cy, s * 0.82f, moonC);
                break;
            case PARTLY:
                sun(g, cx + s * 0.16f, cy - s * 0.14f, s * 0.62f, sunC);
                cloud(g, cx - s * 0.05f, cy + s * 0.12f, s * 0.78f, cloudC);
                break;
            case PARTLY_NIGHT:
                moon(g, cx + s * 0.17f, cy - s * 0.15f, s * 0.5f, moonC);
                cloud(g, cx - s * 0.05f, cy + s * 0.12f, s * 0.78f, cloudC);
                break;
            case FOG: {
                float w = s * 0.07f;
                for (int i = 0; i < 3; i++) {
                    float y = cy - s * 0.18f + i * s * 0.18f;
                    float inset = i == 1 ? s * 0.08f : 0;
                    g.line(cx - s * 0.34f + inset, y, cx + s * 0.34f - inset * 0.3f, y, w, cloudC);
                }
                break;
            }
            case DRIZZLE:
            case RAIN: {
                cloud(g, cx, cy - s * 0.1f, s * 0.86f, cloudC);
                int drops = kind == RAIN ? 3 : 2;
                float w = s * 0.065f;
                for (int i = 0; i < drops; i++) {
                    float x = cx + (i - (drops - 1) / 2f) * s * 0.17f;
                    g.line(x + s * 0.04f, cy + s * 0.2f, x - s * 0.03f, cy + s * 0.36f, w, dropC);
                }
                break;
            }
            case SNOW: {
                cloud(g, cx, cy - s * 0.1f, s * 0.86f, cloudC);
                for (int i = 0; i < 3; i++) {
                    float x = cx - s * 0.17f + i * s * 0.17f;
                    g.fillCircle(x, cy + s * 0.28f + (i == 1 ? s * 0.06f : 0), s * 0.045f, cloudC);
                }
                break;
            }
            case STORM: {
                cloud(g, cx, cy - s * 0.12f, s * 0.86f, cloudC);
                float[] p = scratch(STORM_BOLT.length);
                for (int k = 0; k < p.length; k += 2) {
                    p[k] = cx + STORM_BOLT[k] * s;
                    p[k + 1] = cy + STORM_BOLT[k + 1] * s;
                }
                g.fillPolygon(p, boltC);
                break;
            }
            default:
                cloud(g, cx, cy, s, cloudC);
        }
    }

    private static void sun(Gfx g, float cx, float cy, float s, int color) {
        float r = s * 0.19f;
        g.fillCircle(cx, cy, r, color);
        float w = s * 0.065f;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            g.line(cx + c * r * 1.55f, cy + sn * r * 1.55f, cx + c * r * 2.1f, cy + sn * r * 2.1f, w, color);
        }
    }

    private static float[] moonUnit;

    private static void moon(Gfx g, float cx, float cy, float s, int color) {
        if (moonUnit == null) moonUnit = moonPolygon();
        int save = g.save();
        g.translate(cx, cy);
        g.scale(s, 0, 0);
        g.fillPolygon(moonUnit, color);
        g.restore(save);
    }

    /** A crescent as one polygon: the arc of the disc outside the bite, then the bite's edge. */
    private static float[] moonPolygon() {
        float s = 1f, cx = 0f, cy = 0f;
        float ra = s * 0.36f, rb = s * 0.3f;
        float bx = cx + s * 0.2f, by = cy - s * 0.16f;
        int n = 72;
        float[] outer = new float[n * 2];
        int no = 0;
        int start = -1;
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n;
            float x = cx + (float) Math.cos(a) * ra, y = cy + (float) Math.sin(a) * ra;
            out[i] = dist(x, y, bx, by) > rb;
        }
        for (int i = 0; i < n; i++) {
            if (out[i] && !out[(i + n - 1) % n]) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            float[] circle = new float[n * 2];
            for (int i = 0; i < n; i++) {
                circle[i * 2] = (float) Math.cos(i * 2 * Math.PI / n) * ra;
                circle[i * 2 + 1] = (float) Math.sin(i * 2 * Math.PI / n) * ra;
            }
            return circle;
        }
        float ex = 0, ey = 0;
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            if (!out[i]) break;
            double a = i * 2 * Math.PI / n;
            ex = cx + (float) Math.cos(a) * ra;
            ey = cy + (float) Math.sin(a) * ra;
            outer[no++] = ex;
            outer[no++] = ey;
        }
        float[] inner = new float[n * 2];
        int ni = 0;
        int bstart = -1;
        boolean[] in = new boolean[n];
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n;
            float x = bx + (float) Math.cos(a) * rb, y = by + (float) Math.sin(a) * rb;
            in[i] = dist(x, y, cx, cy) < ra;
        }
        for (int i = 0; i < n; i++) {
            if (in[i] && !in[(i + n - 1) % n]) {
                bstart = i;
                break;
            }
        }
        for (int k = 0; bstart >= 0 && k < n; k++) {
            int i = (bstart + k) % n;
            if (!in[i]) break;
            double a = i * 2 * Math.PI / n;
            inner[ni++] = bx + (float) Math.cos(a) * rb;
            inner[ni++] = by + (float) Math.sin(a) * rb;
        }
        // Join the bite edge in whichever direction continues from the end of the outer arc.
        boolean reverse = ni >= 2 && dist(ex, ey, inner[ni - 2], inner[ni - 1]) < dist(ex, ey, inner[0], inner[1]);
        float[] poly = new float[no + ni];
        System.arraycopy(outer, 0, poly, 0, no);
        for (int k = 0; k < ni / 2; k++) {
            int src = reverse ? ni / 2 - 1 - k : k;
            poly[no + k * 2] = inner[src * 2];
            poly[no + k * 2 + 1] = inner[src * 2 + 1];
        }
        return poly;
    }

    private static float[] cloudUnit;

    /** A cloud as one star-shaped polygon: for each angle, the farthest edge of its parts. */
    static void cloud(Gfx g, float cx, float cy, float s, int color) {
        if (cloudUnit == null) cloudUnit = cloudPolygon();
        int save = g.save();
        g.translate(cx, cy);
        g.scale(s, 0, 0);
        g.fillPolygon(cloudUnit, color);
        g.restore(save);
    }

    private static float[] cloudPolygon() {
        float[][] circles = {
            {-0.2f, 0.04f, 0.15f},
            {0.0f, -0.06f, 0.21f},
            {0.19f, 0.03f, 0.16f},
        };
        float baseL = -0.34f, baseR = 0.34f, baseT = 0.0f, baseB = 0.19f;
        float ox = 0f, oy = 0.06f;
        int n = 96;
        float[] poly = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            float best = 0;
            for (float[] c : circles) {
                float t = ray(ox, oy, dx, dy, c[0], c[1], c[2]);
                if (t > best) best = t;
            }
            float tr = rayRoundRect(ox, oy, dx, dy, baseL, baseT, baseR, baseB, 0.095f);
            if (tr > best) best = tr;
            poly[i * 2] = ox + dx * best;
            poly[i * 2 + 1] = oy + dy * best;
        }
        return poly;
    }

    /** Far intersection of a ray from (ox,oy) with a circle; 0 if it misses. */
    private static float ray(float ox, float oy, float dx, float dy, float cx, float cy, float r) {
        float fx = ox - cx, fy = oy - cy;
        float b = fx * dx + fy * dy;
        float c = fx * fx + fy * fy - r * r;
        float disc = b * b - c;
        if (disc < 0) return 0;
        return Math.max(0, -b + (float) Math.sqrt(disc));
    }

    /** Far intersection with a rounded rectangle, found by marching along the ray. */
    private static float rayRoundRect(float ox, float oy, float dx, float dy,
            float l, float t, float r, float b, float rad) {
        float last = 0;
        for (float d = 0; d < 1f; d += 0.004f) {
            float x = ox + dx * d, y = oy + dy * d;
            if (insideRoundRect(x, y, l, t, r, b, rad)) last = d;
        }
        return last;
    }

    private static boolean insideRoundRect(float x, float y, float l, float t, float r, float b, float rad) {
        if (x < l || x > r || y < t || y > b) return false;
        float cx = Math.max(l + rad, Math.min(r - rad, x)), cy = Math.max(t + rad, Math.min(b - rad, y));
        return dist(x, y, cx, cy) <= rad;
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x0 - x1, dy = y0 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** A gear outline with eight teeth. */
    static void gear(Gfx g, float cx, float cy, float s, int color) {
        float[] p = scratch(GEAR.length);
        for (int k = 0; k < GEAR.length; k += 2) {
            p[k] = cx + GEAR[k] * s;
            p[k + 1] = cy + GEAR[k + 1] * s;
        }
        g.strokePolygon(p, s * 0.075f, color);
        g.strokeCircle(cx, cy, s * 0.12f, s * 0.075f, color);
    }

    /** The gear's outline for a size of 1: eight teeth, four points each. */
    private static final float[] GEAR = gearOutline();

    private static float[] gearOutline() {
        int teeth = 8;
        float ro = 0.4f, ri = 0.31f;
        float[] p = new float[teeth * 4 * 2];
        int k = 0;
        for (int i = 0; i < teeth; i++) {
            double base = i * 2 * Math.PI / teeth;
            double half = Math.PI / teeth;
            double[] angles = {base - half * 0.62, base - half * 0.36, base + half * 0.36, base + half * 0.62};
            float[] radii = {ri, ro, ro, ri};
            for (int j = 0; j < 4; j++) {
                p[k++] = (float) Math.cos(angles[j]) * radii[j];
                p[k++] = (float) Math.sin(angles[j]) * radii[j];
            }
        }
        return p;
    }

    /** A waveform: five rounded bars, the sign for sound. */
    private static final float[] SOUND_BARS = {0.28f, 0.56f, 0.82f, 0.5f, 0.3f};

    static void sound(Gfx g, float cx, float cy, float s, int color) {
        float[] h = SOUND_BARS;
        float w = s * 0.1f, step = s * 0.17f;
        for (int i = 0; i < h.length; i++) {
            float x = cx + (i - 2) * step;
            float half = s * h[i] / 2f;
            g.line(x, cy - half + w / 2, x, cy + half - w / 2, w, color);
        }
    }

    static void chevron(Gfx g, float cx, float cy, float s, boolean right, int color) {
        float dx = (right ? 1 : -1) * s * 0.22f;
        g.line(cx - dx, cy - s * 0.4f, cx + dx, cy, s * 0.16f, color);
        g.line(cx + dx, cy, cx - dx, cy + s * 0.4f, s * 0.16f, color);
    }

    static void check(Gfx g, float cx, float cy, float s, int color) {
        g.line(cx - s * 0.36f, cy + s * 0.02f, cx - s * 0.1f, cy + s * 0.28f, s * 0.14f, color);
        g.line(cx - s * 0.1f, cy + s * 0.28f, cx + s * 0.38f, cy - s * 0.26f, s * 0.14f, color);
    }

    // ---- Getting around ------------------------------------------------------------------------

    /** Maps a point on the 24-unit grid to pixels, for a glyph of size {@code s} at (cx, cy). */
    private static float gx(float cx, float s, float x) {
        return cx + (x - 12f) * s / 24f;
    }

    private static float gy(float cy, float s, float y) {
        return cy + (y - 12f) * s / 24f;
    }

    // Glyph outlines on the 24-unit grid, kept as constants so drawing a glyph allocates nothing.
    private static final float[] BIKE_1 = {5.5f, 15f, 11.5f, 15f, 9.5f, 8.5f, 15.5f, 8.5f, 11.5f, 15f};
    private static final float[] BIKE_2 = {18.5f, 15f, 15.5f, 8.5f, 15f, 6f, 17f, 6f};
    private static final float[] BOLT_1 = {14f, 2f, 5.5f, 13f, 11.5f, 13f, 10f, 22f, 18.5f, 11f, 12.5f, 11f};
    private static final float[] EBIKE_1 = {5f, 1.2f, 1.8f, 5.6f, 3.9f, 5.6f, 3.3f, 9f, 6.2f, 4.4f, 4.2f, 4.4f};
    private static final float[] DOCK_1 = {5f, 6f, 5f, 18.5f, 19f, 18.5f, 19f, 6f};
    private static final float[] DOCK_2 = {10f, 18.5f, 10f, 12.5f, 14f, 12.5f, 14f, 18.5f};
    private static final float[] EXIT_1 = {13f, 4f, 5f, 4f, 5f, 20f, 13f, 20f};
    private static final float[] EXIT_2 = {17f, 8.5f, 20.5f, 12f, 17f, 15.5f};
    private static final float[] MIRROR_1 = {3f, 9f, 3f, 5f, 21f, 5f, 21f, 19f, 13f, 19f};

    /** Scratch arrays by length: the Gfx turns a polygon into a path at once, so one per length is enough. */
    private static final float[][] SCRATCH = new float[129][];

    private static float[] scratch(int n) {
        if (n >= SCRATCH.length) return new float[n];
        float[] a = SCRATCH[n];
        if (a == null) a = SCRATCH[n] = new float[n];
        return a;
    }

    private static void tri(Gfx g, float x0, float y0, float x1, float y1, float x2, float y2, int color) {
        float[] p = scratch(6);
        p[0] = x0;
        p[1] = y0;
        p[2] = x1;
        p[3] = y1;
        p[4] = x2;
        p[5] = y2;
        g.fillPolygon(p, color);
    }

    /** The storm cloud's bolt, for a size of 1, from the cloud's centre. */
    private static final float[] STORM_BOLT = {0.03f, 0.08f, -0.1f, 0.27f, -0.01f, 0.27f, -0.06f, 0.42f, 0.1f, 0.2f, 0.01f, 0.2f,
        0.06f, 0.08f};

    private static float[] grid(float cx, float cy, float s, float[] xy) {
        float[] out = scratch(xy.length);
        for (int i = 0; i + 1 < xy.length; i += 2) {
            out[i] = gx(cx, s, xy[i]);
            out[i + 1] = gy(cy, s, xy[i + 1]);
        }
        return out;
    }

    /**
     * A bicycle in profile, facing right: wheels r3.75 on a 13-unit wheelbase, bottom bracket on
     * the axle line, five tubes and no seat stays (they clog the rear wheel at small sizes).
     */
    static void bike(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s), r = s * 3.75f / 24f;
        g.strokeCircle(gx(cx, s, 5.5f), gy(cy, s, 15f), r, w, color);
        g.strokeCircle(gx(cx, s, 18.5f), gy(cy, s, 15f), r, w, color);
        // rear axle, bottom bracket, seat cluster, head, bottom bracket: chain stay, seat tube,
        // top tube and down tube as one stroke
        g.polyline(grid(cx, cy, s, BIKE_1), w, color);
        // seatpost on the seat tube's line, and the saddle
        g.line(gx(cx, s, 9.5f), gy(cy, s, 8.5f), gx(cx, s, 8.8f), gy(cy, s, 6.1f), w, color);
        g.line(gx(cx, s, 7.5f), gy(cy, s, 6f), gx(cx, s, 10.3f), gy(cy, s, 6f), w, color);
        // fork to the front axle, stem and bar
        g.polyline(grid(cx, cy, s, BIKE_2), w, color);
    }

    /** Stroke weight: 2 units, 1.75 from 48 px up, never thinner than a pixel and a half. */
    private static float stroke(float s) {
        return Math.max(1.5f, s * (s >= 48 ? 1.75f : 2f) / 24f);
    }

    /** A lightning bolt, filled, leaning about 11 degrees: the mark for electric assist. */
    static void bolt(Gfx g, float cx, float cy, float s, int color) {
        g.fillPolygon(grid(cx, cy, s, BOLT_1), color);
    }

    /** The bike with a bolt above its rear wheel: an e-bike, recognisable by shape alone. */
    static void ebike(Gfx g, float cx, float cy, float s, int color, int badge) {
        bike(g, cx, cy, s, color);
        g.fillPolygon(grid(cx, cy, s, EBIKE_1), badge);
    }

    /** An open dock: an empty bay with its stand, for "room to return a bike". */
    static void dock(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s);
        g.polyline(grid(cx, cy, s, DOCK_1), w, color);
        g.polyline(grid(cx, cy, s, DOCK_2), w, color);
    }

    /** A shuttle seen head-on: body, tinted windscreen, lamps and wheels. */
    static void bus(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s), u = s / 24f;
        g.strokeRoundRect(gx(cx, s, 5f), gy(cy, s, 3f), gx(cx, s, 19f), gy(cy, s, 18f), u * 3f, w, color);
        g.fillRoundRect(gx(cx, s, 7.5f), gy(cy, s, 6f), gx(cx, s, 16.5f), gy(cy, s, 11.5f), u * 1.2f, Pixels.alpha(color, 0.5f));
        g.fillCircle(gx(cx, s, 8.5f), gy(cy, s, 15f), u * 1.3f, color);
        g.fillCircle(gx(cx, s, 15.5f), gy(cy, s, 15f), u * 1.3f, color);
        g.fillRoundRect(gx(cx, s, 6.5f), gy(cy, s, 18.5f), gx(cx, s, 9f), gy(cy, s, 21f), u * 0.8f, color);
        g.fillRoundRect(gx(cx, s, 15f), gy(cy, s, 18.5f), gx(cx, s, 17.5f), gy(cy, s, 21f), u * 0.8f, color);
    }

    private static final float[] PLANE = {
        12.0f, 1.6f, 13.15f, 3.2f, 13.35f, 8.6f, 21.8f, 13.2f, 21.8f, 15.0f, 13.35f, 12.6f, 13.1f, 18.0f,
        16.4f, 20.4f, 16.4f, 21.8f, 12.0f, 20.6f,
        7.6f, 21.8f, 7.6f, 20.4f, 10.9f, 18.0f, 10.65f, 12.6f, 2.2f, 15.0f, 2.2f, 13.2f, 10.65f, 8.6f, 10.85f, 3.2f,
    };

    /** An airliner from above, nose along {@code headingDeg} (0 is north, clockwise). */
    static void plane(Gfx g, float cx, float cy, float s, float headingDeg, int color) {
        double a = Math.toRadians(headingDeg);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float[] p = new float[PLANE.length];
        for (int i = 0; i + 1 < PLANE.length; i += 2) {
            float x = (PLANE[i] - 12f) * s / 24f, y = (PLANE[i + 1] - 12f) * s / 24f;
            p[i] = cx + x * cos - y * sin;
            p[i + 1] = cy + x * sin + y * cos;
        }
        g.fillPolygon(p, color);
    }

    /**
     * Bike finder's mark: an accent dot, two fixed rings, a sweep that turns once every 2.4 s
     * and rings that leave the centre every 0.53 s, growing and fading by three quarters out.
     * With {@code still} set (reduced motion) it is drawn at rest.
     */
    static void radar(Gfx g, float cx, float cy, float s, float t, boolean still, int color, int accent) {
        float u = s / 24f, w = Math.max(1.2f, 1.75f * u);
        g.strokeCircle(cx, cy, 5.5f * u, w, Pixels.alpha(color, 0.6f));
        g.strokeCircle(cx, cy, 9.5f * u, w, Pixels.alpha(color, 0.35f));
        if (!still) {
            float sweep = (float) (t / 2.4 * 360 % 360);
            int slices = 8;
            for (int i = 0; i < slices; i++) {
                float a0 = sweep - 60f * (i + 1) / slices, a1 = sweep - 60f * i / slices;
                float fade = 0.45f * (1 - i / (float) slices);
                double r0 = Math.toRadians(a0), r1 = Math.toRadians(a1);
                float rad = 10.2f * u;
                tri(g, cx, cy, cx + (float) Math.cos(r0) * rad, cy + (float) Math.sin(r0) * rad, cx + (float) Math.cos(r1) * rad,
                        cy + (float) Math.sin(r1) * rad, Pixels.alpha(accent, fade));
            }
            for (int i = 0; i < 3; i++) {
                float phase = (float) (((t - i * 0.53) / 1.6) % 1.0);
                if (phase < 0) phase += 1;
                float eased = 1 - (1 - phase) * (1 - phase) * (1 - phase);
                float a = phase < 0.75f ? 0.6f * (1 - phase / 0.75f) : 0;
                if (a > 0.01f) g.strokeCircle(cx, cy, (2 + 8 * eased) * u, w, Pixels.alpha(accent, a));
            }
            float blip = (float) ((t % 2.4) / 1.2);
            float ba = blip < 1 ? 1 - 0.65f * blip : 0.35f;
            double br = Math.toRadians(-45);
            g.fillCircle(cx + (float) Math.cos(br) * 7 * u, cy + (float) Math.sin(br) * 7 * u, 1.4f * u, Pixels.white(ba));
        }
        g.fillCircle(cx, cy, 2f * u, accent);
    }

    /** Two short arcs to the right of a dot: "this time is live". */
    static void live(Gfx g, float cx, float cy, float s, int color) {
        float w = s * 0.12f;
        g.fillCircle(cx - s * 0.22f, cy, s * 0.11f, color);
        g.arc(cx - s * 0.22f, cy, s * 0.3f, -50, 100, w, color);
        g.arc(cx - s * 0.22f, cy, s * 0.52f, -45, 90, w, Pixels.alpha(color, 0.6f));
    }

    /** A line badge: a rounded rectangle in the line's colour with its short code. Returns width. */
    static float chip(Gfx g, float l, float cy, float h, String code, int color, float alpha) {
        float size = h * 0.5f;
        float tw = g.measure(code, Gfx.TEXT_SEMIBOLD, size, 0.06f);
        float w = Math.max(h * 1.5f, tw + h * 0.7f);
        g.fillRoundRect(l, cy - h / 2, l + w, cy + h / 2, h * 0.28f, Pixels.alpha(color, alpha));
        int ink = Pixels.luminance(color) > 0.55f ? 0xFF101114 : 0xFFFFFFFF;
        g.text(code, Gfx.TEXT_SEMIBOLD, size, 0.06f, l + w / 2, cy + g.capHeight(Gfx.TEXT_SEMIBOLD, size) / 2,
                Pixels.alpha(ink, alpha), Gfx.CENTER, 0);
        return w;
    }

    /** The remote's OK key as a small keycap, for hints such as "OK  Find me an e-bike". */
    static float keycap(Gfx g, float l, float cy, float h, String label, int color) {
        float size = h * 0.48f;
        float w = Math.max(h, g.measure(label, Gfx.TEXT_SEMIBOLD, size, 0.04f) + h * 0.6f);
        g.strokeRoundRect(l, cy - h / 2, l + w, cy + h / 2, h * 0.3f, Math.max(1f, h * 0.07f), color);
        g.text(label, Gfx.TEXT_SEMIBOLD, size, 0.04f, l + w / 2, cy + g.capHeight(Gfx.TEXT_SEMIBOLD, size) / 2, color,
                Gfx.CENTER, 0);
        return w;
    }

    // ---- Added in 2.0 ---------------------------------------------------------------------------

    /** A desk calendar: rings, a page and one day marked in {@code accent}. */
    static void calendar(Gfx g, float cx, float cy, float s, int color, int accent) {
        float w = stroke(s), u = s / 24f;
        g.strokeRoundRect(gx(cx, s, 4f), gy(cy, s, 5f), gx(cx, s, 20f), gy(cy, s, 20.5f), u * 3f, w, color);
        g.line(gx(cx, s, 4.5f), gy(cy, s, 9.5f), gx(cx, s, 19.5f), gy(cy, s, 9.5f), w, color);
        g.line(gx(cx, s, 8.5f), gy(cy, s, 3f), gx(cx, s, 8.5f), gy(cy, s, 6.5f), w, color);
        g.line(gx(cx, s, 15.5f), gy(cy, s, 3f), gx(cx, s, 15.5f), gy(cy, s, 6.5f), w, color);
        g.fillRoundRect(gx(cx, s, 13f), gy(cy, s, 13f), gx(cx, s, 17f), gy(cy, s, 17f), u * 1.2f, accent);
    }

    /** Four bars rising and falling while music plays; still when {@code t} is negative. */
    static void bars(Gfx g, float cx, float cy, float s, float t, int color) {
        float w = s * 0.16f, step = s * 0.26f;
        for (int i = 0; i < 4; i++) {
            float h = t < 0 ? 0.35f + 0.15f * (i % 2) : 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.sin(t * (5.1 + i * 1.7) + i * 1.3));
            float x = cx + (i - 1.5f) * step, half = s * h / 2;
            g.line(x, cy + s / 2 - w / 2 - half * 2 + w, x, cy + s / 2 - w / 2, w, color);
        }
    }

    /** A pair of quavers: music. */
    static void note(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s), u = s / 24f;
        g.fillCircle(gx(cx, s, 7f), gy(cy, s, 18f), u * 3.2f, color);
        g.fillCircle(gx(cx, s, 17f), gy(cy, s, 16f), u * 3.2f, color);
        g.line(gx(cx, s, 9.8f), gy(cy, s, 18f), gx(cx, s, 9.8f), gy(cy, s, 5.5f), w, color);
        g.line(gx(cx, s, 19.8f), gy(cy, s, 16f), gx(cx, s, 19.8f), gy(cy, s, 3.5f), w, color);
        g.line(gx(cx, s, 9.8f), gy(cy, s, 5.5f), gx(cx, s, 19.8f), gy(cy, s, 3.5f), w * 1.4f, color);
    }

    /** A checkbox, ticked when done. */
    static void box(Gfx g, float cx, float cy, float s, boolean done, int color) {
        g.strokeRoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, s * 0.25f, Math.max(1.2f, s * 0.1f), color);
        if (done) check(g, cx, cy, s * 0.8f, color);
    }

    /** Play (a triangle) or pause (two bars). */
    static void play(Gfx g, float cx, float cy, float s, boolean playing, int color) {
        if (playing) {
            float w = s * 0.22f, h = s * 0.62f;
            g.fillRoundRect(cx - s * 0.28f, cy - h / 2, cx - s * 0.28f + w, cy + h / 2, w * 0.3f, color);
            g.fillRoundRect(cx + s * 0.06f, cy - h / 2, cx + s * 0.06f + w, cy + h / 2, w * 0.3f, color);
        } else {
            tri(g, cx - s * 0.22f, cy - s * 0.34f, cx + s * 0.36f, cy, cx - s * 0.22f, cy + s * 0.34f, color);
        }
    }

    /** Skip: two triangles and a bar, forward or back. */
    static void skip(Gfx g, float cx, float cy, float s, boolean forward, int color) {
        float d = forward ? 1 : -1, h = s * 0.3f;
        for (int i = 0; i < 2; i++) {
            float x0 = cx + d * (-s * 0.34f + i * s * 0.26f);
            tri(g, x0, cy - h, x0 + d * s * 0.3f, cy, x0, cy + h, color);
        }
        float bx = cx + d * s * 0.26f;
        g.line(bx, cy - h, bx, cy + h, s * 0.08f, color);
    }

    /** Leaving: a door frame with an arrow out of it, for "back to Fire TV". */
    static void exit(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s);
        g.polyline(grid(cx, cy, s, EXIT_1), w, color);
        g.line(gx(cx, s, 10f), gy(cy, s, 12f), gx(cx, s, 20.5f), gy(cy, s, 12f), w, color);
        g.polyline(grid(cx, cy, s, EXIT_2), w, color);
    }

    /** Mirroring: a screen with signal arcs rising from its lower left corner. */
    static void mirror(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s), u = s / 24f;
        g.polyline(grid(cx, cy, s, MIRROR_1), w, color);
        g.arc(gx(cx, s, 3f), gy(cy, s, 19f), u * 4f, -90, 90, w, color);
        g.arc(gx(cx, s, 3f), gy(cy, s, 19f), u * 8f, -90, 90, w, color);
        g.fillCircle(gx(cx, s, 3.6f), gy(cy, s, 18.4f), u * 1.4f, color);
    }

    /** A phone, for the control center. */
    static void phone(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s), u = s / 24f;
        g.strokeRoundRect(gx(cx, s, 7f), gy(cy, s, 2.5f), gx(cx, s, 17f), gy(cy, s, 21.5f), u * 2.6f, w, color);
        g.line(gx(cx, s, 10.5f), gy(cy, s, 18.2f), gx(cx, s, 13.5f), gy(cy, s, 18.2f), w, color);
    }

    /** Two earbuds, for Bluetooth headphones. */
    static void earbuds(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s), u = s / 24f;
        for (int side = -1; side <= 1; side += 2) {
            float ox = side * 4.5f;
            g.fillCircle(gx(cx, s, 12f + ox), gy(cy, s, 8.5f), u * 3.4f, color);
            g.line(gx(cx, s, 12f + ox + side * 1.2f), gy(cy, s, 10f), gx(cx, s, 12f + ox + side * 1.2f), gy(cy, s, 20f), w * 1.2f, color);
        }
    }

    /** Wi-Fi: three arcs over a dot. */
    static void wifi(Gfx g, float cx, float cy, float s, int color) {
        float w = stroke(s), u = s / 24f;
        float ox = gx(cx, s, 12f), oy = gy(cy, s, 19f);
        for (int i = 1; i <= 3; i++) g.arc(ox, oy, u * (4f + 4.5f * i), -135, 90, w, color);
        g.fillCircle(ox, oy, u * 1.8f, color);
    }

    /** The moon at {@code lit} (0 new, 1 full), waxing when {@code waxing}: a disc and its shadow. */
    static void moonPhase(Gfx g, float cx, float cy, float s, double phase, int color, int shade) {
        float r = s * 0.42f;
        g.fillCircle(cx, cy, r, shade);
        // The lit part: a half disc plus an ellipse, as the terminator moves across.
        int n = 24;
        float[] p = new float[(n + 1) * 4];
        double k = Math.cos(phase * 2 * Math.PI);
        boolean waxing = phase < 0.5;
        int i = 0;
        for (int j = 0; j <= n; j++) {
            double a = -Math.PI / 2 + Math.PI * j / n;
            p[i++] = cx + (float) (Math.cos(a) * r * (waxing ? 1 : -1));
            p[i++] = cy + (float) (Math.sin(a) * r);
        }
        for (int j = n; j >= 0; j--) {
            double a = -Math.PI / 2 + Math.PI * j / n;
            p[i++] = cx + (float) (Math.cos(a) * r * k * (waxing ? 1 : -1));
            p[i++] = cy + (float) (Math.sin(a) * r);
        }
        g.fillPolygon(p, color);
    }
}
