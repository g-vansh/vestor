"""
overhead.py — live "what's overhead" data from free, no-key community ADS-B.

SOURCE (replaces the throttled FlightRadar24 unofficial feed):
  * Positions + aircraft type: airplanes.live REST API (ADSBExchange v2 format),
    a point+radius query around LOCATION_HOME. No key, ~1 req/s limit.
  * Route (origin -> destination): adsbdb.com maps a callsign to its airports.
    No key, ~120 req/min, cached per callsign (routes don't change) — the same
    enricher the upstream its-a-plane project uses.
Raw ADS-B carries no route, hence the two-step (positions from one, route from
the other). Aircraft type ("t") comes free in the positions payload.

The class self-polls on a background daemon thread every POLL_SECONDS and exposes
the same interface the display expects (grab_data / new_data / processing / data
/ data_is_empty), emitting the same per-flight dict schema as before so every
scene works unchanged:
    {plane, aircraft_code, origin, destination, vertical_speed, altitude, callsign}

The previous FlightRadar24 implementation is preserved as
utilities/overhead_fr24.py.
"""

import math
import os
from threading import Thread, Lock
from time import sleep, monotonic
from urllib.parse import quote

import requests

try:                                   # FR24 gives the ACTUAL current route (the
    from FlightRadarAPI import FlightRadar24API   # filed flight plan), unlike the
except ImportError:                    # stale scheduled-route DBs. Used low-volume
    try:                               # (once per new callsign, then cached).
        from FlightRadar24 import FlightRadar24API
    except ImportError:
        FlightRadar24API = None


def _cfg(name, default):
    try:
        return getattr(__import__("config"), name)
    except (ModuleNotFoundError, NameError, ImportError, AttributeError):
        return default


LOCATION_HOME = _cfg("LOCATION_HOME", [42.354, -71.107, 0.005])
MIN_ALTITUDE = _cfg("MIN_ALTITUDE", 0)
MAX_ALTITUDE = _cfg("MAX_ALTITUDE", 40000)
SEARCH_RADIUS_NM = _cfg("SEARCH_RADIUS_NM", 10)
POLL_SECONDS = _cfg("POLL_SECONDS", 10)
MAX_FLIGHTS = _cfg("MAX_FLIGHTS", 5)
DEMO_MODE = _cfg("DEMO_MODE", False)
ZONE_HOME = _cfg("ZONE_HOME", None)
USE_ZONE_BOX = _cfg("USE_ZONE_BOX", False)
HOME_AIRPORT = _cfg("JOURNEY_CODE_SELECTED", "BOS")

HOME_LAT, HOME_LON = LOCATION_HOME[0], LOCATION_HOME[1]

# Bounding box (same one FR24 used) — kept only if enabled + defined.
_BOX = ZONE_HOME if (USE_ZONE_BOX and ZONE_HOME) else None

VS_DIR = 200        # fpm; above = climbing/departing, below -VS_DIR = descending


def _in_box(lat, lon):
    if _BOX is None:
        return True
    return (_BOX["br_y"] <= lat <= _BOX["tl_y"]) and (_BOX["tl_x"] <= lon <= _BOX["br_x"])


def _plausible_route(origin, destination, vertical_speed):
    """Sanity-check a callsign->route lookup for a flight near HOME.

    We only track low-altitude traffic near HOME_AIRPORT, so such a flight almost
    always departs or arrives HOME. Free route databases (adsbdb/hexdb) key on
    flight number and often carry a STALE scheduled route (e.g. AAL909 -> ORD-MSP
    when it's really BOS-DFW today). If the looked-up route omits HOME, it's
    almost certainly stale — anchor HOME by climb/descent and blank the unreliable
    endpoint rather than show a wrong route.
    """
    if not origin and not destination:
        return origin, destination
    if HOME_AIRPORT in (origin, destination):
        return origin, destination                 # HOME present -> trust it
    vs = vertical_speed or 0
    if vs > VS_DIR:
        return HOME_AIRPORT, ""                     # climbing out -> departing HOME
    if vs < -VS_DIR:
        return "", HOME_AIRPORT                     # descending -> arriving HOME
    return "", ""                                  # unsure -> don't show a wrong route

AIRPLANES_URL = "https://api.airplanes.live/v2/point/{lat}/{lon}/{radius}"
ADSBDB_URL = "https://api.adsbdb.com/v0/callsign/{callsign}"

# The venv's certifi CA bundle lives under /home/pi (0700), which the
# dropped-privilege `daemon` user the matrix runs as CANNOT read — so verifying
# with it would fail. Point requests at the OS trust store (world-readable).
_SYS_CA = "/etc/ssl/certs/ca-certificates.crt"
VERIFY = _SYS_CA if os.path.exists(_SYS_CA) else True

USER_AGENT = "Vestor-LED-FlightTracker/1.0 (+https://github.com/g-vansh/vestor)"
HTTP_TIMEOUT = 8
FR24_MIN_INTERVAL = 20        # s — cap FR24 feed queries to stay well under throttle
ROUTE_TTL = 1800             # s — a route is cached only ~30 min: long enough to
                             # survive one overhead pass, short enough that a reused
                             # callsign (a different leg later) re-fetches. Never
                             # persisted to disk; pruned each grab.
BLANK_FIELDS = {"", "N/A", "NONE"}

# Sample flights for DEMO_MODE — realistic KBOS routes across airlines with logos
# and a spread of altitudes (so the FR24 colour ramp + climb/descent arrows all
# show). Cycled by the display like real overhead traffic.
DEMO_FLIGHTS = [
    {"callsign": "AAL191", "origin": "BOS", "destination": "LAX", "plane": "Airbus A321",
     "aircraft_code": "A321", "altitude": 31000, "vertical_speed": 640},
    {"callsign": "JBU671", "origin": "BOS", "destination": "MCO", "plane": "Airbus A320",
     "aircraft_code": "A320", "altitude": 12500, "vertical_speed": 1408},
    {"callsign": "DAL1180", "origin": "BOS", "destination": "ATL", "plane": "Boeing 737-900",
     "aircraft_code": "B739", "altitude": 8000, "vertical_speed": 1216},
    {"callsign": "BAW238", "origin": "LHR", "destination": "BOS", "plane": "Boeing 777-200",
     "aircraft_code": "B772", "altitude": 4200, "vertical_speed": -768},
    {"callsign": "UAL2456", "origin": "SFO", "destination": "BOS", "plane": "Boeing 757-200",
     "aircraft_code": "B752", "altitude": 36000, "vertical_speed": 0},
    {"callsign": "SWA1234", "origin": "BOS", "destination": "BWI", "plane": "Boeing 737-800",
     "aircraft_code": "B738", "altitude": 21000, "vertical_speed": -640},
]


class Overhead:
    def __init__(self):
        self._lock = Lock()
        self._data = []
        self._new_data = False
        self._processing = False
        self._route_cache = {}
        self._session = requests.Session()
        self._session.headers.update({"User-Agent": USER_AGENT})
        self._session.verify = VERIFY
        self._fr24 = FlightRadar24API() if FlightRadar24API else None
        self._fr24_last_t = 0.0        # rate-limit FR24 feed queries
        self._fr24_last_map = {}
        # Self-poll on a background thread so the display needs no timing changes.
        Thread(target=self._poll_loop, daemon=True).start()

    def grab_data(self):
        # Kept for interface compatibility; the background poller already runs.
        pass

    def _poll_loop(self):
        while True:
            try:
                self._fetch_once()
            except Exception as e:  # never let the poller die
                print(f"[overhead] fetch error: {e}", flush=True)
                with self._lock:
                    self._processing = False
            sleep(POLL_SECONDS)

    def _fetch_once(self):
        # Demo mode: serve the sample flights and skip the network.
        if DEMO_MODE:
            with self._lock:
                self._data = list(DEMO_FLIGHTS)
                self._new_data = True
                self._processing = False
            return

        with self._lock:
            self._processing = True

        url = AIRPLANES_URL.format(lat=HOME_LAT, lon=HOME_LON, radius=SEARCH_RADIUS_NM)
        resp = self._session.get(url, timeout=HTTP_TIMEOUT)
        aircraft = (resp.json() or {}).get("ac", []) or []

        # Keep airborne aircraft in the altitude band with a known position.
        candidates = []
        for ac in aircraft:
            alt = ac.get("alt_baro")
            if not isinstance(alt, (int, float)):  # skips "ground" and nulls
                continue
            if not (MIN_ALTITUDE < alt < MAX_ALTITUDE):
                continue
            lat, lon = ac.get("lat"), ac.get("lon")
            if lat is None or lon is None:
                continue
            if not _in_box(lat, lon):
                continue
            candidates.append(ac)

        # Nearest first, keep the closest MAX_FLIGHTS.
        candidates.sort(key=lambda a: self._dist2(a["lat"], a["lon"]))
        candidates = candidates[:MAX_FLIGHTS]

        self._prune_routes()

        # If any callsign has no fresh cached route, do ONE FR24 zone query (actual
        # current routes for everything overhead). Cheap: only fires for new
        # flights, then everything is cached until the TTL expires.
        need = [(ac.get("flight") or "").strip() for ac in candidates]
        fr24_map = {}
        if any(cs and self._cached_route(cs) is None for cs in need):
            fr24_map = self._fr24_zone_routes()

        data = []
        for ac in candidates:
            callsign = (ac.get("flight") or "").strip()
            vs = ac.get("baro_rate")
            if vs is None:
                vs = ac.get("geom_rate") or 0
            origin, destination = self._resolve_route(callsign, vs, fr24_map)
            data.append({
                "plane": "",
                "aircraft_code": (ac.get("t") or "").strip(),
                "origin": origin,
                "destination": destination,
                "vertical_speed": vs,
                "altitude": ac.get("alt_baro") or 0,
                "callsign": callsign,
            })

        with self._lock:
            self._data = data
            self._new_data = True
            self._processing = False

    def _dist2(self, lat, lon):
        """Cheap squared planar distance to home (fine for nearest-first sort)."""
        dlat = lat - HOME_LAT
        dlon = (lon - HOME_LON) * math.cos(math.radians(HOME_LAT))
        return dlat * dlat + dlon * dlon

    def _cached_route(self, callsign):
        """Return a still-fresh cached route for a callsign, or None."""
        entry = self._route_cache.get(callsign)
        if entry and (monotonic() - entry[1]) < ROUTE_TTL:
            return entry[0]
        return None

    def _prune_routes(self):
        """Drop expired route-cache entries (keeps the dict small; TTL-bounded)."""
        now = monotonic()
        self._route_cache = {
            k: v for k, v in self._route_cache.items() if now - v[1] < ROUTE_TTL
        }

    def _resolve_route(self, callsign, vs, fr24_map):
        """callsign -> (origin, destination), FR24 first (accurate current route),
        else adsbdb + BOS plausibility. Cached for ROUTE_TTL (not permanent)."""
        if not callsign:
            return ("", "")
        cached = self._cached_route(callsign)
        if cached is not None:
            return cached
        if callsign in fr24_map:                 # FR24 has the real current route
            route = fr24_map[callsign]
        else:                                    # fall back to the stale DB, fixed up
            route = _plausible_route(*self._adsbdb_route(callsign), vs)
        self._route_cache[callsign] = (route, monotonic())
        return route

    def _fr24_zone_routes(self):
        """One FR24 feed query over the zone -> {callsign: (origin, dest)}.

        get_flights returns origin/destination IATA on the basic Flight object, so
        no per-flight detail calls are needed. Returns {} on any error/throttle
        (callers then fall back to adsbdb)."""
        if self._fr24 is None:
            return {}
        now = monotonic()
        if now - self._fr24_last_t < FR24_MIN_INTERVAL:
            return self._fr24_last_map     # reuse recent result — bound FR24 load
        try:
            if _BOX:
                bounds = self._fr24.get_bounds(_BOX)
            else:
                bounds = self._fr24.get_bounds_by_point(HOME_LAT, HOME_LON, 20000)
            out = {}
            for f in self._fr24.get_flights(bounds=bounds):
                cs = (getattr(f, "callsign", "") or "").strip()
                o = (getattr(f, "origin_airport_iata", "") or "")
                d = (getattr(f, "destination_airport_iata", "") or "")
                o = o if o.upper() not in BLANK_FIELDS else ""
                d = d if d.upper() not in BLANK_FIELDS else ""
                if cs and (o or d):
                    out[cs] = (o, d)
            self._fr24_last_t = now
            self._fr24_last_map = out
            return out
        except Exception as e:
            print(f"[overhead] FR24 route lookup failed: {e}", flush=True)
            self._fr24_last_t = now        # back off after a failure/throttle too
            return {}

    def _adsbdb_route(self, callsign):
        """callsign -> (origin_iata, destination_iata) via adsbdb (may be stale)."""
        try:
            resp = self._session.get(
                ADSBDB_URL.format(callsign=quote(callsign)), timeout=HTTP_TIMEOUT
            )
        except Exception:
            return ("", "")
        origin = destination = ""
        if resp.status_code in (200, 404):
            try:
                fr = (resp.json() or {}).get("response", {})
                if isinstance(fr, dict):
                    route = fr.get("flightroute") or {}
                    o = ((route.get("origin") or {}).get("iata_code") or "").strip()
                    d = ((route.get("destination") or {}).get("iata_code") or "").strip()
                    origin = o if o.upper() not in BLANK_FIELDS else ""
                    destination = d if d.upper() not in BLANK_FIELDS else ""
            except (ValueError, KeyError, AttributeError):
                pass
        return (origin, destination)

    @property
    def new_data(self):
        with self._lock:
            return self._new_data

    @property
    def processing(self):
        with self._lock:
            return self._processing

    @property
    def data(self):
        with self._lock:
            self._new_data = False
            return self._data

    @property
    def data_is_empty(self):
        return len(self._data) == 0


if __name__ == "__main__":
    o = Overhead()
    while not o.new_data:
        print("processing...")
        sleep(1)
    for f in o.data:
        print(f)
