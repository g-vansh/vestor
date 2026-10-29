#!/usr/bin/env python3
"""
Builds src/tv/vestor/home/MapData.java, the geometry the 3D bike map draws: the Charles River,
parks, streets, the Green Line and every building with its height, between BU and MIT.

The source is Overture Maps (release named below), whose water, roads and most buildings come
from OpenStreetMap. Run it on a desk, not on the TV:

    python3 -m pip install pyarrow shapely requests
    python3 preview/tools/mapdata.py /path/to/scratch

It reads only the row groups that touch the map's box (a few MB over HTTP range requests),
simplifies every shape for a 1080p screen, and writes coordinates as half-metre steps east and
north of MapData.LAT0/LON0, delta and varint coded, then Base64, in strings of at most 60000
characters (a Java string constant holds 65535 bytes).
"""
import base64, io, json, math, os, re, sys, time
import requests, pyarrow.parquet as pq
import shapely.wkb as wkb
from shapely.geometry import box, Point, Polygon, MultiPolygon, LineString, MultiLineString
from shapely.ops import unary_union
import heapq

RELEASE = "2026-10-21.0"
BUCKET = "https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com/"
# The map's box: BU West to the Prudential, the Fenway to Kendall's edge.
W, S, E, N = -71.1215, 42.3420, -71.0810, 42.3640
LAT0, LON0 = 42.3532, -71.1015
KX = math.cos(math.radians(LAT0)) * 111320.0
KY = 110574.0

session = requests.Session()


class Ranged(io.RawIOBase):
    """A remote file read in byte ranges, so pyarrow fetches only footers and wanted row groups."""

    def __init__(self, url, size):
        self.url, self.size, self.pos = url, size, 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, o, whence=0):
        self.pos = o if whence == 0 else self.pos + o if whence == 1 else self.size + o
        return self.pos

    def readinto(self, b):
        if self.pos >= self.size:
            return 0
        end = min(self.size, self.pos + len(b)) - 1
        for attempt in range(6):
            try:
                d = session.get(self.url, headers={"Range": "bytes=%d-%d" % (self.pos, end)}, timeout=120).content
                if len(d) == end - self.pos + 1:
                    break
            except requests.RequestException:
                pass
            time.sleep(1 + attempt)
        b[:len(d)] = d
        self.pos += len(d)
        return len(d)


def keys(theme):
    out, token = [], None
    while True:
        url = BUCKET + "?list-type=2&prefix=release/" + RELEASE + "/" + theme + "/"
        if token:
            url += "&continuation-token=" + requests.utils.quote(token)
        x = session.get(url, timeout=60).text
        out += list(zip(re.findall("<Key>([^<]*)</Key>", x), map(int, re.findall("<Size>([^<]*)</Size>", x))))
        m = re.search("<NextContinuationToken>([^<]*)<", x)
        if not m:
            return out
        token = m.group(1)


def fetch(theme, cols, cache):
    if os.path.exists(cache):
        return json.load(open(cache))
    rows = []
    for key, size in keys(theme):
        f = pq.ParquetFile(io.BufferedReader(Ranged(BUCKET + key, size), 1 << 20))
        md = f.metadata
        for i in range(md.num_row_groups):
            rg, st = md.row_group(i), {}
            for j in range(rg.num_columns):
                c = rg.column(j)
                if c.path_in_schema.startswith("bbox.") and c.statistics is not None and c.statistics.has_min_max:
                    st[c.path_in_schema] = (c.statistics.min, c.statistics.max)
            if st and (st["bbox.xmax"][1] < W or st["bbox.xmin"][0] > E or st["bbox.ymax"][1] < S or st["bbox.ymin"][0] > N):
                continue
            for r in f.read_row_group(i, columns=cols + ["bbox"]).to_pylist():
                b = r.pop("bbox")
                if b["xmax"] < W or b["xmin"] > E or b["ymax"] < S or b["ymin"] > N:
                    continue
                r["geometry"] = r["geometry"].hex()
                rows.append(r)
    json.dump(rows, open(cache, "w"), default=str)
    return rows


def local(g):
    """Lon/lat to metres east and north of the origin."""
    import shapely
    return shapely.transform(g, lambda c: (c - [LON0, LAT0]) * [KX, KY])


def polys(g):
    if g.is_empty:
        return []
    if isinstance(g, Polygon):
        return [g]
    if isinstance(g, MultiPolygon):
        return list(g.geoms)
    return [p for p in getattr(g, "geoms", []) if isinstance(p, Polygon)]


def lines(g):
    if g.is_empty:
        return []
    if isinstance(g, LineString):
        return [g]
    if isinstance(g, MultiLineString):
        return list(g.geoms)
    return [p for p in getattr(g, "geoms", []) if isinstance(p, LineString)]


class Out:
    def __init__(self):
        self.b = bytearray()
        self.count = 0

    def varint(self, v):
        v = (v << 1) ^ (v >> 31)  # zigzag
        v &= 0xFFFFFFFF
        while v >= 0x80:
            self.b.append((v & 0x7F) | 0x80)
            v >>= 7
        self.b.append(v)

    def feature(self, kind, tag, extra, coords):
        pts = []
        for x, y in coords:
            p = (int(round(x * 2)), int(round(y * 2)))
            if not pts or p != pts[-1]:
                pts.append(p)
        if len(pts) > 1 and pts[0] == pts[-1]:
            pts.pop()
        if len(pts) < (3 if kind in (0, 1, 2, 5) else 2):
            return
        self.b.append(kind)
        self.b.append(tag)
        self.varint(extra)
        self.varint(len(pts))
        px = py = 0
        for x, y in pts:
            self.varint(x - px)
            self.varint(y - py)
            px, py = x, y
        self.count += 1


ROAD = {"motorway": 0, "trunk": 0, "primary": 1, "secondary": 2, "tertiary": 3, "residential": 4, "unclassified": 4,
        "living_street": 4, "service": 5, "cycleway": 6, "pedestrian": 7}


# Where the bike routes start and end: the Westgate dock, and the docks nearest BU Law and the
# Pardee School (positions as Bluebikes publishes them).
WESTGATE = (-71.103945, 42.355601)
BU_CENTRAL = (-71.108279, 42.350406)
SILBER_WAY = (-71.100576, 42.349496)
# How much longer a metre feels on each kind of way to someone on a bike.
COST = {"cycleway": 0.8, "primary": 1.0, "secondary": 1.0, "tertiary": 1.0, "residential": 1.0, "unclassified": 1.0,
        "living_street": 1.0, "service": 1.15, "pedestrian": 1.4, "footway": 1.5, "path": 1.2, "unknown": 1.3}


def route(roads, a, b):
    """The cheapest way between two points over the street graph, as a list of metres east and north."""
    graph, near = {}, {}
    for r in roads:
        if r["subtype"] != "road" or r["class"] not in COST:
            continue
        g = local(wkb.loads(bytes.fromhex(r["geometry"])))
        for ln in lines(g):
            c = [(round(x, 1), round(y, 1)) for x, y in ln.coords]
            for u, v in zip(c, c[1:]):
                d = math.hypot(u[0] - v[0], u[1] - v[1]) * COST[r["class"]]
                graph.setdefault(u, []).append((v, d))
                graph.setdefault(v, []).append((u, d))
    nodes = list(graph)

    def snap(p):
        q = local(Point(*p))
        return min(nodes, key=lambda n: (n[0] - q.x) ** 2 + (n[1] - q.y) ** 2)

    s, t = snap(a), snap(b)
    dist, prev, heap = {s: 0.0}, {}, [(0.0, s)]
    while heap:
        d, u = heapq.heappop(heap)
        if u == t:
            break
        if d > dist.get(u, 1e18):
            continue
        for v, w in graph[u]:
            nd = d + w
            if nd < dist.get(v, 1e18):
                dist[v], prev[v] = nd, u
                heapq.heappush(heap, (nd, v))
    path, u = [t], t
    while u != s:
        u = prev[u]
        path.append(u)
    path.reverse()
    return LineString(path)


def main(scratch):
    os.makedirs(scratch, exist_ok=True)
    water = fetch("theme=base/type=water", ["id", "geometry", "subtype", "class", "names"], os.path.join(scratch, "water.json"))
    land = fetch("theme=base/type=land_use", ["id", "geometry", "subtype", "class", "names"], os.path.join(scratch, "landuse.json"))
    roads = fetch("theme=transportation/type=segment", ["id", "geometry", "subtype", "class", "names"], os.path.join(scratch, "roads.json"))
    blds = fetch("theme=buildings/type=building", ["id", "geometry", "height", "num_floors", "names", "class"], os.path.join(scratch, "bld.json"))
    frame = local(box(W, S, E, N))
    out = Out()

    # Water: the river and its lagoons as one shape, islands drawn back as land.
    wet = [local(wkb.loads(bytes.fromhex(r["geometry"]))) for r in water
           if r["class"] in ("river", "lagoon", "lake", "water", "reflecting_pool", "canal", "moat")]
    wet = unary_union([p for g in wet for p in polys(g) if p.area > 150]).intersection(frame)
    wet = wet.simplify(2.5)
    for p in polys(wet):
        if p.area < 150:
            continue
        out.feature(0, 0, 0, p.exterior.coords)
        for hole in p.interiors:
            if Polygon(hole).area > 40:
                out.feature(1, 0, 0, hole.coords)

    # Parks and lawns, merged so a park is one shape rather than its every lawn.
    green = [local(wkb.loads(bytes.fromhex(r["geometry"]))) for r in land
             if r["subtype"] in ("park", "recreation", "managed", "horticulture", "protected") and r["class"] not in ("track", "marina")]
    green = unary_union([p.buffer(1.5) for g in green for p in polys(g)]).buffer(-1.5).intersection(frame).simplify(2.5)
    for p in polys(green):
        if p.area > 600:
            out.feature(2, 0, 0, p.exterior.coords)

    # Streets, rails and bridges. A segment mostly over water is a bridge, drawn as a raised deck.
    for r in roads:
        g = local(wkb.loads(bytes.fromhex(r["geometry"]))).intersection(frame)
        if r["subtype"] == "rail":
            tag = 0 if r["class"] in ("light_rail", "tram") else 1 if r["class"] in ("standard_gauge", "subway") else -1
            if tag < 0:
                continue
            for ln in lines(g):
                out.feature(4, tag, 0, ln.simplify(2).coords)
            continue
        tag = ROAD.get(r["class"])
        if tag is None:
            continue
        name = ((r.get("names") or {}).get("primary") or "")
        for ln in lines(g):
            if ln.length < 4:
                continue
            bridge = ln.length > 30 and ln.intersection(wet).length > 0.6 * ln.length
            out.feature(3, tag | (8 if bridge else 0), 0, ln.simplify(1.5).coords)

    # Buildings: footprint and height in decimetres. Tags mark the places the map names.
    marks = {
        1: [Point(-71.10717, 42.35108), Point(-71.1077, 42.3509)],  # BU Law: the tower and the Redstone building
        2: [Point(-71.09889, 42.35028)],  # 121 Bay State Road, the Pardee School
        4: [Point(-71.09201, 42.35968)],  # MIT's Great Dome, Building 10
        5: [Point(-71.08251, 42.34717)],  # the Prudential Tower
    }
    marks = {k: [local(p) for p in v] for k, v in marks.items()}
    kept = 0
    for r in blds:
        g = local(wkb.loads(bytes.fromhex(r["geometry"])))
        for p in polys(g):
            if p.area < 25 or not frame.contains(p.centroid):
                continue
            h = r["height"]
            if h is None:
                fl = r["num_floors"]
                h = fl * 3.4 if fl else 9.0
            h = max(3.0, min(260.0, float(h)))
            name = ((r.get("names") or {}).get("primary") or "")
            tag = 3 if name.startswith("W85") else 0
            for k, pts in marks.items():
                if any(p.distance(q) < 6.0 for q in pts):
                    tag = k
            q = p.simplify(0.8, preserve_topology=True)
            if not isinstance(q, Polygon) or q.is_empty:
                continue
            out.feature(5, tag, int(round(h * 10)), q.exterior.coords)
            kept += 1

    # The two rides the map draws, Westgate to BU Law and Westgate to Pardee; the length in metres rides along.
    for tag, dest in ((1, BU_CENTRAL), (2, SILBER_WAY)):
        ln = route(roads, WESTGATE, dest)
        out.feature(6, tag, int(round(ln.length)), ln.simplify(1.5).coords)
        print("route", tag, round(ln.length), "m", file=sys.stderr)

    data = base64.b64encode(bytes(out.b)).decode("ascii")
    chunks = [data[i:i + 60000] for i in range(0, len(data), 60000)]
    java = ["package tv.vestor.home;", "",
            "/**",
            " * The bike map's geometry, written by preview/tools/mapdata.py from Overture Maps release " + RELEASE + ".",
            " * Water, streets and most buildings come from OpenStreetMap: © OpenStreetMap contributors, ODbL.",
            " * Do not edit by hand. " + str(out.count) + " shapes, " + str(kept) + " of them buildings.",
            " */",
            "final class MapData {",
            "    private MapData() {}", "",
            "    static final double LAT0 = %r, LON0 = %r;" % (LAT0, LON0),
            "    /** Metres per degree east and north at LAT0. */",
            "    static final double KX = %r, KY = %r;" % (KX, KY),
            "    static final String RELEASE = \"%s\";" % RELEASE, "",
            "    static final String[] PARTS = {"]
    for c in chunks:
        java.append("        \"" + c + "\",")
    java += ["    };", "}", ""]
    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "tv", "vestor", "home", "MapData.java")
    open(dest, "w").write("\n".join(java))
    print(out.count, "shapes,", kept, "buildings,", len(out.b), "bytes,", len(chunks), "strings ->", os.path.normpath(dest))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/vestor-map")
