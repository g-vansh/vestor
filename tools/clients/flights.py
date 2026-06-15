"""Flights client — ADS-B (adsb.lol) + hexdb enrichment, anchored at 540 Mem Dr.

The flight scene is the hero of the wall, so this client returns a fully-fleshed
picture of the sky overhead: the closest interesting aircraft (the "hero") plus a
ranked board of the rest, each enriched with callsign route (origin -> dest),
airport IATA + city, aircraft type and operator.

Sources (all free, no key, CORS-friendly):
  * adsb.lol  v2 point search ......  live aircraft within N nm of a lat/lon
  * hexdb.io  route / airport / aircraft  ...  callsign->route, ICAO->city, hex->type

Enrichment is best-effort and cached with a TTL so polling every few seconds does
not hammer hexdb (and survives its frequent per-callsign 404s).

Anchor: 540 Memorial Drive, Cambridge MA (42.354, -71.107).
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass, field, asdict

from ._http import get_json

LAT, LON = 42.354, -71.107
ADSB_POINT = "https://api.adsb.lol/v2/point/{lat}/{lon}/{nm}"
HEXDB_ROUTE = "https://hexdb.io/api/v1/route/icao/{callsign}"
HEXDB_AIRPORT = "https://hexdb.io/api/v1/airport/icao/{icao}"
HEXDB_AIRCRAFT = "https://hexdb.io/api/v1/aircraft/{hex}"

# simple TTL caches {key: (expires, value)}; value None == "looked up, nothing"
_CACHE: dict[str, tuple[float, object]] = {}
_TTL = 3600.0


def _cached(key: str, fn, ttl: float = _TTL):
    now = time.time()
    hit = _CACHE.get(key)
    if hit and hit[0] > now:
        return hit[1]
    try:
        val = fn()
    except Exception:
        val = None
    _CACHE[key] = (now + ttl, val)
    return val


def haversine_mi(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 3958.7613  # mean Earth radius, miles
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def _num(v, default=0.0):
    """adsb.lol alt_baro is 'ground' or an int; coerce anything else to default."""
    return float(v) if isinstance(v, (int, float)) else default


def _short_city(name: str | None, region: str | None, iata: str | None) -> str:
    """Trim 'Logan International Airport' -> 'Logan'; fall back to region/IATA."""
    if name:
        for suffix in (" International Airport", " Regional Airport",
                       " Municipal Airport", " Airport", " Intl"):
            if name.endswith(suffix):
                name = name[: -len(suffix)]
        return name.strip() or (region or iata or "")
    return region or iata or ""


@dataclass
class Flight:
    callsign: str
    reg: str = ""
    type: str = ""            # ICAO type code, e.g. B738
    type_name: str = ""       # human, e.g. "737 800"
    owner: str = ""           # operator / registered owner
    origin: str = ""          # IATA, e.g. BOS
    dest: str = ""            # IATA
    origin_city: str = ""
    dest_city: str = ""
    alt: int = 0              # ft
    gs: int = 0               # knots
    vspeed: int = 0           # ft/min (+climb / -descend)
    track: float = 0.0        # degrees
    lat: float = 0.0
    lon: float = 0.0
    distance: float = 0.0     # miles from anchor
    squawk: str = ""
    is_ground: bool = False

    def as_dict(self) -> dict:
        return asdict(self)


def _airport(icao: str) -> dict:
    j = _cached("ap:" + icao, lambda: get_json(HEXDB_AIRPORT.format(icao=icao), timeout=6)) or {}
    return j if isinstance(j, dict) else {}


def _route(callsign: str) -> tuple[str, str]:
    """callsign -> (origin_icao, dest_icao) or ('','')."""
    j = _cached("rt:" + callsign, lambda: get_json(HEXDB_ROUTE.format(callsign=callsign), timeout=6))
    if isinstance(j, dict) and "-" in (j.get("route") or ""):
        o, d = j["route"].split("-", 1)
        return o.strip(), d.strip()
    return "", ""


def _aircraft(hex_: str) -> dict:
    j = _cached("ac:" + hex_, lambda: get_json(HEXDB_AIRCRAFT.format(hex=hex_), timeout=6)) or {}
    return j if isinstance(j, dict) else {}


def _enrich(f: Flight, hex_: str) -> Flight:
    if hex_:
        ac = _aircraft(hex_)
        f.type_name = ac.get("Type", "") or f.type_name
        f.owner = ac.get("RegisteredOwners", "") or f.owner
    if f.callsign:
        o_icao, d_icao = _route(f.callsign)
        if o_icao:
            ao = _airport(o_icao)
            f.origin = ao.get("iata", "") or o_icao
            f.origin_city = _short_city(ao.get("airport"), ao.get("region_name"), f.origin)
        if d_icao:
            ad = _airport(d_icao)
            f.dest = ad.get("iata", "") or d_icao
            f.dest_city = _short_city(ad.get("airport"), ad.get("region_name"), f.dest)
    return f


def _to_flight(a: dict, lat: float, lon: float) -> Flight:
    alt_raw = a.get("alt_baro")
    is_ground = alt_raw == "ground"
    flat, flon = a.get("lat", lat), a.get("lon", lon)
    return Flight(
        callsign=(a.get("flight") or "").strip(),
        reg=a.get("r", "") or "",
        type=a.get("t", "") or "",
        alt=int(_num(alt_raw)),
        gs=int(_num(a.get("gs"))),
        vspeed=int(_num(a.get("baro_rate", a.get("geom_rate")))),
        track=_num(a.get("track", a.get("dir"))),
        lat=flat, lon=flon,
        distance=round(haversine_mi(lat, lon, flat, flon), 1),
        squawk=a.get("squawk", "") or "",
        is_ground=is_ground,
    )


@dataclass
class SkyState:
    hero: Flight | None = None
    board: list[Flight] = field(default_factory=list)   # other aircraft, by distance
    count: int = 0

    def as_dict(self) -> dict:
        return {"hero": self.hero.as_dict() if self.hero else None,
                "board": [f.as_dict() for f in self.board], "count": self.count}


def fetch_raw(lat: float = LAT, lon: float = LON, nm: int = 60, timeout: float = 12.0) -> list[dict]:
    j = get_json(ADSB_POINT.format(lat=lat, lon=lon, nm=nm), timeout=timeout)
    return j.get("ac") or []


def fetch(lat: float = LAT, lon: float = LON, nm: int = 60,
          board: int = 6, enrich_board: bool = True, timeout: float = 12.0) -> SkyState:
    """Closest airborne aircraft (enriched) as the hero, plus a ranked board."""
    raw = fetch_raw(lat, lon, nm, timeout=timeout)
    flights = [_to_flight(a, lat, lon) for a in raw]
    flights = [(f, a.get("hex", "")) for f, a in zip(flights, raw)]
    # Prefer airborne aircraft, then sort by distance from the anchor.
    airborne = [(f, h) for f, h in flights if not f.is_ground]
    pool = airborne or flights
    pool.sort(key=lambda fh: fh[0].distance)
    st = SkyState(count=len(flights))
    if not pool:
        return st
    st.hero = _enrich(pool[0][0], pool[0][1])
    for f, h in pool[1: 1 + board]:
        st.board.append(_enrich(f, h) if enrich_board else f)
    return st


if __name__ == "__main__":
    s = fetch()
    if not s.hero:
        print("No aircraft overhead.")
    else:
        h = s.hero
        route = f"{h.origin or '???'}->{h.dest or '???'}"
        print(f"HERO {h.callsign or h.reg}  {route}  {h.type} ({h.owner or '—'})")
        print(f"     FL{h.alt//100:03d}  {h.gs}kt  vs{h.vspeed:+d}  trk{h.track:.0f}°  "
              f"{h.distance}mi  sq{h.squawk}")
        print(f"--- {s.count} aircraft in range; board: ---")
        for f in s.board:
            print(f"  {f.callsign or f.reg:8s} {(f.origin or '?')+'>'+(f.dest or '?'):9s} "
                  f"FL{f.alt//100:03d} {f.gs}kt {f.distance}mi")
