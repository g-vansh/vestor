package tv.vestor.home;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * The bike map's shapes, read once from {@link MapData}. Every coordinate is in metres east (x)
 * and north (y) of MapData.LAT0/LON0, so the map can be turned and tilted with plain arithmetic.
 *
 * Read on first use and kept: about 6,800 shapes, 4,700 of them buildings, in under 2 MB.
 */
final class MapGeo {
    static final int WATER = 0, ISLAND = 1, PARK = 2, ROAD = 3, RAIL = 4, BUILDING = 5, ROUTE = 6;
    /** Road tags: the low three bits are the kind, 8 marks a bridge. */
    static final int MOTORWAY = 0, PRIMARY = 1, SECONDARY = 2, TERTIARY = 3, RESIDENTIAL = 4, SERVICE = 5, CYCLEWAY = 6,
            PEDESTRIAN = 7, BRIDGE = 8;
    /** Building tags: the places the map names. */
    static final int PLAIN = 0, BU_LAW = 1, PARDEE = 2, HOME = 3, DOME = 4, PRUDENTIAL = 5;
    /** Route tags. */
    static final int TO_LAW = 1, TO_PARDEE = 2;

    static final class Shape {
        int kind, tag;
        /** A building's height or a route's length, in metres; 0 otherwise. */
        float size;
        /** x0, y0, x1, y1, ... in metres. */
        float[] xy;
        /** The middle of the bounding box, and its half-diagonal. */
        float cx, cy, reach;
        /** Footprint area in square metres (polygons only). */
        float area;

        int points() {
            return xy.length / 2;
        }
    }

    final List<Shape> water = new ArrayList<>(), islands = new ArrayList<>(), parks = new ArrayList<>(),
            roads = new ArrayList<>(), rails = new ArrayList<>(), buildings = new ArrayList<>(), routes = new ArrayList<>();
    /** The extent of all shapes, in metres. */
    float minX, minY, maxX, maxY;

    private static MapGeo instance;

    /** The decoded map. Thread-safe; the first call does the work (a few tens of milliseconds). */
    static synchronized MapGeo get() {
        if (instance == null) instance = decode(MapData.PARTS);
        return instance;
    }

    static float x(double lon) {
        return (float) ((lon - MapData.LON0) * MapData.KX);
    }

    static float y(double lat) {
        return (float) ((lat - MapData.LAT0) * MapData.KY);
    }

    static MapGeo decode(String[] parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) b.append(p);
        byte[] d = Base64.getDecoder().decode(b.toString());
        MapGeo m = new MapGeo();
        m.minX = m.minY = Float.MAX_VALUE;
        m.maxX = m.maxY = -Float.MAX_VALUE;
        int[] at = {0};
        while (at[0] + 2 <= d.length) {
            Shape s = new Shape();
            s.kind = d[at[0]] & 0xFF;
            s.tag = d[at[0] + 1] & 0xFF;
            at[0] += 2;
            int extra = varint(d, at);
            int n = varint(d, at);
            if (n < 0 || n > 100000) throw new IllegalArgumentException("map data is damaged");
            s.xy = new float[n * 2];
            int x = 0, y = 0;
            float lx = Float.MAX_VALUE, ly = Float.MAX_VALUE, hx = -Float.MAX_VALUE, hy = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                x += varint(d, at);
                y += varint(d, at);
                float fx = x / 2f, fy = y / 2f;
                s.xy[i * 2] = fx;
                s.xy[i * 2 + 1] = fy;
                lx = Math.min(lx, fx);
                ly = Math.min(ly, fy);
                hx = Math.max(hx, fx);
                hy = Math.max(hy, fy);
            }
            s.cx = (lx + hx) / 2;
            s.cy = (ly + hy) / 2;
            s.reach = (float) Math.hypot(hx - lx, hy - ly) / 2;
            m.minX = Math.min(m.minX, lx);
            m.minY = Math.min(m.minY, ly);
            m.maxX = Math.max(m.maxX, hx);
            m.maxY = Math.max(m.maxY, hy);
            switch (s.kind) {
                case WATER:
                    s.area = area(s.xy);
                    m.water.add(s);
                    break;
                case ISLAND:
                    s.area = area(s.xy);
                    m.islands.add(s);
                    break;
                case PARK:
                    s.area = area(s.xy);
                    m.parks.add(s);
                    break;
                case ROAD:
                    m.roads.add(s);
                    break;
                case RAIL:
                    m.rails.add(s);
                    break;
                case BUILDING:
                    s.size = extra / 10f;
                    s.area = area(s.xy);
                    m.buildings.add(s);
                    break;
                case ROUTE:
                    s.size = extra;
                    m.routes.add(s);
                    break;
                default:
                    // A kind a newer generator added: skipped.
            }
        }
        // Small streets first, so the big ones are drawn over them.
        Collections.sort(m.roads, new java.util.Comparator<Shape>() {
            @Override
            public int compare(Shape a, Shape b2) {
                return Integer.compare(b2.tag & 7, a.tag & 7);
            }
        });
        return m;
    }

    private static int varint(byte[] d, int[] at) {
        int v = 0, shift = 0;
        while (true) {
            if (at[0] >= d.length || shift > 28) throw new IllegalArgumentException("map data is damaged");
            int b = d[at[0]++] & 0xFF;
            v |= (b & 0x7F) << shift;
            shift += 7;
            if (b < 0x80) break;
        }
        return (v >>> 1) ^ -(v & 1);
    }

    /** The unsigned area of a ring, in square metres. */
    static float area(float[] xy) {
        double a = 0;
        int n = xy.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) a += (double) xy[j * 2] * xy[i * 2 + 1] - (double) xy[i * 2] * xy[j * 2 + 1];
        return (float) Math.abs(a / 2);
    }

    /** Whether (x, y) is inside the ring, by the even-odd rule. */
    static boolean inside(float[] xy, float x, float y) {
        boolean in = false;
        int n = xy.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = xy[i * 2], yi = xy[i * 2 + 1], xj = xy[j * 2], yj = xy[j * 2 + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    /** Whether a point is on the water (inside the river and not on an island). */
    boolean wet(float x, float y) {
        for (Shape s : islands) if (inside(s.xy, x, y)) return false;
        for (Shape s : water) if (inside(s.xy, x, y)) return true;
        return false;
    }

    /** The first building with {@code tag}, or null. */
    Shape building(int tag) {
        for (Shape s : buildings) if (s.tag == tag) return s;
        return null;
    }

    Shape route(int tag) {
        for (Shape s : routes) if (s.tag == tag) return s;
        return null;
    }
}
