"""
overhead.py — live "what's overhead" data from free, no-key community ADS-B.

SOURCES (all governed by utilities/ratelimit.py so the wall can run 24/7
without ever tripping a provider's rate limit; limits + sources are listed in
that module's docstring):
  * Positions + aircraft type: a provider list tried in order, all speaking
    ADSBExchange-v2-shaped JSON ("ac" array; adsb.fi's deprecated v2 uses
    "aircraft", both are accepted). Default order (config FLIGHT_PROVIDERS):
        adsb.fi        primary   (documented 1 req/s, no daily cap)
        adsb.lol       fallback  (4/s, 60/min, only 500/DAY -> paced, 400/day)
        airplanes.live last      (answers 403 to unregistered clients today)
    The primary is used while healthy. On an error, timeout, 429/403, or a
    local budget refusal the next provider answers the same poll. A failing
    provider sits in jittered exponential backoff (<= 5 min), and is retried
    automatically when that expires, so the primary is re-probed periodically.
  * Route (origin -> destination): FlightRadar24 first (the filed, CURRENT
    route) with a 20 s floor and an escalating ladder after failures or empty
    answers (60 s -> 5 min -> 15 min, one recovery probe per step). Then
    adsbdb.com (stale scheduled routes, fixed up by the BOS plausibility check),
    cached per callsign for hits AND misses.
If every provider fails, the last good data stays on screen (the board is not
blanked) and `stale` turns True after STALE_AFTER_SECONDS.

The class self-polls on a background daemon thread every POLL_SECONDS and exposes
the same interface the display expects (grab_data / new_data / processing / data
/ data_is_empty), emitting the same per-flight dict schema as before so every
scene works unchanged:
    {plane, aircraft_code, origin, destination, vertical_speed, altitude, callsign,
     ground_speed, heading, squawk, registration, distance_mi, bearing}
Extra read-only properties: stale, data_age, source, rate_stats().

The previous FlightRadar24 implementation is preserved as
utilities/overhead_fr24.py.
"""

import math
import os
import random
from threading import Thread, Lock
from time import sleep, monotonic
from urllib.parse import quote

import requests

from utilities.ratelimit import (
    ERROR, HostPolicy, RateGovernor, RateLimited,
)

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
POLL_SECONDS = _cfg("POLL_SECONDS", 5)
MAX_FLIGHTS = _cfg("MAX_FLIGHTS", 5)
DEMO_MODE = _cfg("DEMO_MODE", False)
ZONE_HOME = _cfg("ZONE_HOME", None)
USE_ZONE_BOX = _cfg("USE_ZONE_BOX", False)
HOME_AIRPORT = _cfg("JOURNEY_CODE_SELECTED", "BOS")

FLIGHT_PROVIDERS = list(_cfg("FLIGHT_PROVIDERS", ["adsb.fi", "adsb.lol", "airplanes.live"]))
STALE_AFTER_SECONDS = _cfg("STALE_AFTER_SECONDS", 120)
STALE_CLEAR_SECONDS = _cfg("STALE_CLEAR_SECONDS", 0)     # 0 = never blank on outage
FR24_BACKOFF_STEPS = tuple(_cfg("FR24_BACKOFF_STEPS", (60, 300, 900)))
FR24_EMPTY_TOLERANCE = _cfg("FR24_EMPTY_TOLERANCE", 2)
RATE_STATE_FILE = _cfg("RATE_STATE_FILE", "/tmp/vestor-ratelimit.json")
RATE_STATS_LOG_SECONDS = _cfg("RATE_STATS_LOG_SECONDS", 900)
_BUDGET_OVERRIDES = _cfg("RATE_BUDGETS", {}) or {}

HOME_LAT, HOME_LON = LOCATION_HOME[0], LOCATION_HOME[1]

# Bounding box (same one FR24 used) — kept only if enabled + defined.
_BOX = ZONE_HOME if (USE_ZONE_BOX and ZONE_HOME) else None

VS_DIR = 200        # fpm; above = climbing/departing, below -VS_DIR = descending


def _in_box(lat, lon):
    if _BOX is None:
        return True
    return (_BOX["br_y"] <= lat <= _BOX["tl_y"]) and (_BOX["tl_x"] <= lon <= _BOX["br_x"])


_COMPASS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]


def _dist_bearing(lat, lon):
    """(distance_miles, compass) from HOME to a point — small-area planar approx."""
    dlat = lat - HOME_LAT
    dlon = (lon - HOME_LON) * math.cos(math.radians(HOME_LAT))
    miles = math.sqrt(dlat * dlat + dlon * dlon) * 69.0     # ~69 mi per degree
    brg = (math.degrees(math.atan2(dlon, dlat)) + 360) % 360
    return round(miles, 1), _COMPASS[int((brg + 22.5) // 45) % 8]


def _interest_tier(ac):
    """Higher = more display-worthy: commercial (has airline) + arriving low."""
    try:
        from setup import airlines
        commercial = 1 if airlines.iata_for_callsign((ac.get("flight") or "").strip()) else 0
    except Exception:
        commercial = 0
    vs = ac.get("baro_rate") or 0
    alt = ac.get("alt_baro") if isinstance(ac.get("alt_baro"), (int, float)) else 99999
    arriving = 1 if (vs < -200 and alt < 6000) else 0
    return commercial + arriving


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


# ---- Position providers (all ADSBExchange-v2 shaped) ------------------------
PROVIDER_URLS = {
    # v3 is adsb.fi's current point query ("ac" array); v2 lat/lon/dist is
    # documented as deprecated (it returns "aircraft"; also accepted below).
    "adsb.fi": "https://opendata.adsb.fi/api/v3/lat/{lat}/lon/{lon}/dist/{radius}",
    "adsb.lol": "https://api.adsb.lol/v2/point/{lat}/{lon}/{radius}",
    "airplanes.live": "https://api.airplanes.live/v2/point/{lat}/{lon}/{radius}",
}
ADSBDB_URL = "https://api.adsbdb.com/v0/callsign/{callsign}"

# Per-host budgets, comfortably below each documented limit (see ratelimit.py).
# config.RATE_BUDGETS may override min_interval / per_minute / per_day per name.
DEFAULT_POLICIES = {
    # documented 1 req/s (60/min). We poll every 5 s = 12/min; budget 20/min.
    # 4xx answers count toward adsb.fi's limit, so 401/403 pause it for 10 min.
    "adsb.fi": HostPolicy(min_interval=2.0, per_minute=20, burst=2,
                          backoff_base=5, backoff_cap=300, forbidden_cooldown=600),
    # 4/s, 60/min but only 500/DAY. Fallback only, paced to one call per 15 s
    # and 400/day so a long primary outage never exhausts the daily quota.
    "adsb.lol": HostPolicy(min_interval=15.0, per_minute=4, burst=1, per_day=400,
                           backoff_base=15, backoff_cap=300, forbidden_cooldown=1800),
    # 1 req/s documented, but 403 "contact us" for unregistered clients:
    # a 401/403 parks it for an hour (one probe per hour, negligible load).
    "airplanes.live": HostPolicy(min_interval=5.0, per_minute=12, burst=1,
                                 backoff_base=10, backoff_cap=300, forbidden_cooldown=3600),
    # 512 per 60 s window per IP. Budget 30/min with a small burst so several
    # new callsigns in one poll resolve quickly. 404 = "unknown callsign" (valid).
    "adsbdb": HostPolicy(min_interval=0.5, per_minute=30, burst=5,
                         backoff_base=10, backoff_cap=300, throttle_floor=60,
                         benign_statuses=(404,)),
    # Undocumented, the most throttle-prone. 20 s floor, then a ladder.
    "fr24": HostPolicy(min_interval=20.0, per_minute=3, burst=1,
                       backoff_steps=FR24_BACKOFF_STEPS, throttle_floor=60,
                       forbidden_cooldown=900),
}


def build_policies(overrides=None):
    """DEFAULT_POLICIES with config.RATE_BUDGETS-style overrides applied."""
    from dataclasses import replace
    out = dict(DEFAULT_POLICIES)
    for name, ov in (overrides or {}).items():
        if not isinstance(ov, dict):
            continue
        allowed = {k: v for k, v in ov.items()
                   if k in ("min_interval", "per_minute", "per_day", "burst")}
        out[name] = replace(out.get(name, HostPolicy()), **allowed)
    return out


def _aircraft_list(payload):
    """ADSBx-v2 payload -> list of aircraft dicts. Raises ValueError if the
    payload is not a recognisable answer (an empty sky still has the key)."""
    if not isinstance(payload, dict):
        raise ValueError("payload is not a JSON object")
    for key in ("ac", "aircraft"):
        if key in payload:
            ac = payload[key] or []
            if not isinstance(ac, list):
                raise ValueError(f"'{key}' is not a list")
            return ac
    raise ValueError("no 'ac'/'aircraft' array in payload")


# The venv's certifi CA bundle lives under /home/pi (0700), which the
# dropped-privilege `daemon` user the matrix runs as CANNOT read — so verifying
# with it would fail. Point requests at the OS trust store (world-readable).
_SYS_CA = "/etc/ssl/certs/ca-certificates.crt"
VERIFY = _SYS_CA if os.path.exists(_SYS_CA) else True

USER_AGENT = "Vestor-LED-FlightTracker/1.0 (+https://github.com/g-vansh/vestor)"
HTTP_TIMEOUT = (4, 8)          # (connect, read) s — a dead host fails over fast
FR24_TIMEOUT = 10              # s — the library default (30 s) would stall a poll
PROVIDER_MAX_WAIT = 1.5        # s — absorb tiny spacing waits instead of failing over
ADSBDB_MAX_WAIT = 1.0          # s — space a burst of new callsigns within one poll
POLL_JITTER = 0.5              # s — random extra sleep so polls never phase-lock
ROUTE_TTL = 1800             # s — a COMPLETE route is cached ~30 min: long enough to
                             # survive one overhead pass, short enough that a reused
                             # callsign (a different leg later) re-fetches. Never
                             # persisted to disk; pruned each grab.
INCOMPLETE_TTL = 25          # s — a PARTIAL route (one endpoint missing, e.g. FR24
                             # gave BOS but the destination lagged) is cached only
                             # briefly so the next FR24 scan (refreshed every 20s) can
                             # complete it, instead of locking BOS->??? in for 30 min.
FR24_DOWN_ROUTE_TTL = 120    # s — a route resolved WITHOUT FR24 because FR24 is in
                             # backoff is re-checked soon, so FR24 can correct it
                             # once it recovers. Costs no network (adsbdb is cached).
FR24_MAP_MAX_AGE = 600       # s — reuse the last FR24 zone map this long while
                             # FR24 is spaced or backing off.
ADSBDB_HIT_TTL = 14400       # s — adsbdb is a static schedule DB; 4 h (matches the
                             # max-age hexdb.io sends for the same kind of data).
ADSBDB_MISS_TTL = 3600       # s — "unknown callsign" (404 / no route) cached 1 h.
ADSBDB_CACHE_MAX = 2000      # entries — bound memory on a 24/7 process.
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

_DEFAULT = object()


def _make_fr24():
    if FlightRadar24API is None:
        return None
    try:
        return FlightRadar24API(timeout=FR24_TIMEOUT)
    except TypeError:                  # older package without the timeout kwarg
        return FlightRadar24API()
    except Exception as e:
        print(f"[overhead] FR24 client unavailable: {e}", flush=True)
        return None


class Overhead:
    def __init__(self, session=None, governor=None, fr24=_DEFAULT, providers=None,
                 clock=monotonic, start=True):
        self._lock = Lock()
        self._clock = clock
        self._data = []
        self._new_data = False
        self._processing = False
        self._route_cache = {}
        self._adsbdb_cache = {}
        if session is None:
            session = requests.Session()
            session.headers.update({"User-Agent": USER_AGENT})
            session.verify = VERIFY
        self._session = session
        self._gov = governor or RateGovernor(
            policies=build_policies(_BUDGET_OVERRIDES), state_path=RATE_STATE_FILE)
        wanted = list(providers or FLIGHT_PROVIDERS)
        unknown = [p for p in wanted if p not in PROVIDER_URLS]
        if unknown:
            self._log(f"ignoring unknown FLIGHT_PROVIDERS {unknown}; known: {list(PROVIDER_URLS)}")
        self._providers = [p for p in wanted if p in PROVIDER_URLS] or list(PROVIDER_URLS)
        self._fr24 = _make_fr24() if fr24 is _DEFAULT else fr24
        self._fr24_last_t = None       # when the last good FR24 map arrived
        self._fr24_last_map = {}
        self._fr24_empty_streak = 0
        self._fr24_healthy = True
        self._started = clock()
        self._last_ok = None           # when positions last arrived from any provider
        self._source = None            # provider that supplied the current data
        self._provider_ok = {p: True for p in self._providers}
        self._outage_logged = False
        self._last_stats_log = self._started
        # Self-poll on a background thread so the display needs no timing changes.
        if start:
            Thread(target=self._poll_loop, daemon=True).start()

    def grab_data(self):
        # Kept for interface compatibility; the background poller already runs.
        pass

    @staticmethod
    def _log(msg):
        print(f"[overhead] {msg}", flush=True)

    def _poll_loop(self):
        while True:
            t0 = self._clock()
            try:
                self._fetch_once()
            except Exception as e:  # never let the poller die
                self._log(f"fetch error: {e}")
                with self._lock:
                    self._processing = False
            self._maybe_log_stats()
            elapsed = self._clock() - t0
            sleep(max(1.0, POLL_SECONDS - elapsed) + random.uniform(0, POLL_JITTER))

    def _maybe_log_stats(self):
        now = self._clock()
        if RATE_STATS_LOG_SECONDS and now - self._last_stats_log >= RATE_STATS_LOG_SECONDS:
            self._last_stats_log = now
            self._log(f"rate stats: {self._gov.summary()}")

    # ------------------------------------------------------------- polling
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
        try:
            aircraft, source = self._fetch_positions()
            if aircraft is None:
                self._hold_last_data()
                return
            data = self._build_flights(aircraft)
            now = self._clock()
            with self._lock:
                self._data = data
                self._new_data = True
                self._last_ok = now
                self._source = source
            if self._outage_logged:
                self._log(f"positions restored via {source}")
                self._outage_logged = False
        finally:
            with self._lock:
                self._processing = False

    def _fetch_positions(self):
        """Try each provider in order -> (aircraft_list, provider) or (None, None).

        A provider that is cooling down, backing off, or out of budget is skipped
        locally (no request is sent). The first good answer wins this poll.
        """
        for name in self._providers:
            if not self._gov.allow(name, max_wait=PROVIDER_MAX_WAIT):
                continue
            url = PROVIDER_URLS[name].format(lat=HOME_LAT, lon=HOME_LON, radius=SEARCH_RADIUS_NM)
            try:
                resp = self._session.get(url, timeout=HTTP_TIMEOUT)
            except Exception as e:
                self._gov.record(name, e)
                self._provider_failed(name, type(e).__name__)
                continue
            if resp.status_code != 200:
                kind = self._gov.record(name, resp)
                self._provider_failed(name, f"HTTP {resp.status_code} ({kind})")
                continue
            try:
                aircraft = _aircraft_list(resp.json())
            except ValueError as e:
                self._gov.record_failure(name, ERROR)
                self._provider_failed(name, f"bad payload: {e}")
                continue
            self._gov.record(name, resp)        # success; honours X-RateLimit-Remaining
            self._provider_succeeded(name)
            return aircraft, name
        return None, None

    def _provider_failed(self, name, why):
        if self._provider_ok.get(name, True):
            wait, _ = self._gov.wait_time(name)
            self._log(f"{name} failed ({why}); backing off {wait:.0f}s, failing over")
        self._provider_ok[name] = False

    def _provider_succeeded(self, name):
        if not self._provider_ok.get(name, True):
            self._log(f"{name} healthy again")
        self._provider_ok[name] = True
        if self._source is not None and self._source != name:
            self._log(f"positions now from {name} (was {self._source})")

    def _hold_last_data(self):
        """Every provider failed or was refused: keep the last good data on screen.

        A single empty poll is normal while a paced fallback waits its turn, so
        the outage is only logged once the data has actually gone stale."""
        age = self.data_age
        if not self._outage_logged and age > STALE_AFTER_SECONDS:
            self._log(f"no position provider has answered for {age:.0f}s; holding last "
                      f"data ({len(self._data)} flights, now stale). {self._gov.summary()}")
            self._outage_logged = True
        if STALE_CLEAR_SECONDS and age > STALE_CLEAR_SECONDS:
            with self._lock:
                if self._data:
                    self._data = []
                    self._new_data = True

    def _build_flights(self, aircraft):
        # Keep airborne aircraft in the altitude band with a known position.
        candidates = []
        for ac in aircraft:
            if not isinstance(ac, dict):
                continue
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

        # Smart order: most interesting first (commercial + arriving), then
        # nearest. So the board leads with — and lingers on — the best flight.
        candidates.sort(key=lambda a: (-_interest_tier(a), self._dist2(a["lat"], a["lon"])))
        candidates = candidates[:MAX_FLIGHTS]

        self._prune_routes()

        # If any callsign has no fresh cached route, do ONE FR24 zone query (actual
        # current routes for everything overhead). Cheap: only fires for new
        # flights, then everything is cached until the TTL expires.
        need = [(ac.get("flight") or "").strip() for ac in candidates]
        need = [cs for cs in need if cs and self._cached_route(cs) is None]
        fr24_map = self._fr24_zone_routes(len(need)) if need else {}

        data = []
        for ac in candidates:
            callsign = (ac.get("flight") or "").strip()
            vs = ac.get("baro_rate")
            if vs is None:
                vs = ac.get("geom_rate") or 0
            origin, destination = self._resolve_route(callsign, vs, fr24_map)
            miles, bearing = _dist_bearing(ac["lat"], ac["lon"])
            gs = ac.get("gs")
            data.append({
                "plane": "",
                "aircraft_code": (ac.get("t") or "").strip(),
                "origin": origin,
                "destination": destination,
                "vertical_speed": vs,
                "altitude": ac.get("alt_baro") or 0,
                "callsign": callsign,
                "ground_speed": int(gs) if isinstance(gs, (int, float)) else None,
                "heading": ac.get("track"),
                "squawk": (ac.get("squawk") or "").strip(),
                "registration": (ac.get("r") or "").strip(),
                "distance_mi": miles,
                "bearing": bearing,
            })
        return data

    def _dist2(self, lat, lon):
        """Cheap squared planar distance to home (fine for nearest-first sort)."""
        dlat = lat - HOME_LAT
        dlon = (lon - HOME_LON) * math.cos(math.radians(HOME_LAT))
        return dlat * dlat + dlon * dlon

    # -------------------------------------------------------------- routes
    def _cached_route(self, callsign):
        """Return a still-fresh cached route for a callsign, or None.

        Cache entries are (route, timestamp, ttl); partial routes carry a short
        ttl so they don't linger, complete ones the full ROUTE_TTL.
        """
        entry = self._route_cache.get(callsign)
        if entry and (self._clock() - entry[1]) < entry[2]:
            return entry[0]
        return None

    def _prune_routes(self):
        """Drop expired route-cache entries (keeps the dict small; TTL-bounded)."""
        now = self._clock()
        self._route_cache = {
            k: v for k, v in self._route_cache.items() if now - v[1] < v[2]
        }
        if len(self._adsbdb_cache) > ADSBDB_CACHE_MAX:
            self._adsbdb_cache = {
                k: v for k, v in self._adsbdb_cache.items() if v[0] > now
            }

    @staticmethod
    def _merge_route(fr24_route, adsbdb_route):
        """Complete a PARTIAL FR24 route from adsbdb — but only where adsbdb agrees
        with the endpoint FR24 already knows. adsbdb often carries a stale, DIFFERENT
        leg (e.g. ENY4047 -> PHX-SLC when it's really BOS-DCA today), so if it doesn't
        corroborate FR24's known endpoint we don't trust its other endpoint either."""
        fo, fd = fr24_route
        ao, ad = adsbdb_route
        o, d = fo, fd
        agrees = (fo and fo == ao) or (fd and fd == ad)
        if agrees:
            o = o or ao
            d = d or ad
        return (o, d)

    def _resolve_route(self, callsign, vs, fr24_map):
        """callsign -> (origin, destination), FR24 first (accurate current route),
        else adsbdb + BOS plausibility. A complete route is cached for ROUTE_TTL; a
        partial one only briefly (INCOMPLETE_TTL) so a later scan can complete it."""
        if not callsign:
            return ("", "")
        cached = self._cached_route(callsign)
        if cached is not None:
            return cached
        fr = fr24_map.get(callsign)
        if fr and fr[0] and fr[1]:               # FR24 has the full current route
            route = fr
        elif fr:                                 # FR24 partial -> complete via adsbdb
            route = _plausible_route(*self._merge_route(fr, self._adsbdb_route(callsign)), vs)
        else:                                    # FR24 absent -> stale DB, fixed up
            route = _plausible_route(*self._adsbdb_route(callsign), vs)
        ttl = ROUTE_TTL if (route[0] and route[1]) else INCOMPLETE_TTL
        if not fr and not self._fr24_healthy:
            ttl = min(ttl, FR24_DOWN_ROUTE_TTL)  # let FR24 correct it after recovery
        self._route_cache[callsign] = (route, self._clock(), ttl)
        return route

    def _fr24_recent_map(self):
        if self._fr24_last_t is not None and self._clock() - self._fr24_last_t < FR24_MAP_MAX_AGE:
            return self._fr24_last_map
        return {}

    def _fr24_zone_routes(self, n_needed=1):
        """One FR24 feed query over the zone -> {callsign: (origin, dest)}.

        get_flights returns origin/destination IATA on the basic Flight object, so
        no per-flight detail calls are needed. Governed by the "fr24" policy: a
        20 s floor, and after a failure (exception, Cloudflare block, or an empty
        feed FR24_EMPTY_TOLERANCE times in a row while ADS-B sees aircraft) an
        escalating ladder (60 s -> 5 min -> 15 min). When a step expires the next
        call is a single recovery probe. While spaced or backing off, the last
        good map (<= FR24_MAP_MAX_AGE old) is reused and callers fall back to
        adsbdb for anything it lacks."""
        if self._fr24 is None:
            return {}
        if not self._gov.allow("fr24"):
            return self._fr24_recent_map()
        try:
            if _BOX:
                bounds = self._fr24.get_bounds(_BOX)
            else:
                bounds = self._fr24.get_bounds_by_point(HOME_LAT, HOME_LON, 20000)
            flights = list(self._fr24.get_flights(bounds=bounds) or [])
        except Exception as e:
            kind = self._gov.record("fr24", e)
            self._fr24_failed(f"{type(e).__name__}: {e} ({kind})")
            return self._fr24_recent_map()

        if not flights and n_needed > 0:
            # ADS-B sees aircraft here but FR24 returned none: typical of a soft
            # throttle. One empty answer is tolerated; repeats escalate.
            self._fr24_empty_streak += 1
            if self._fr24_empty_streak >= FR24_EMPTY_TOLERANCE:
                self._gov.record_failure("fr24", ERROR)
                self._fr24_failed(f"empty feed {self._fr24_empty_streak}x in a row")
            else:
                self._gov.record_success("fr24", reset=False)
            return self._fr24_recent_map()

        out = {}
        for f in flights:
            cs = (getattr(f, "callsign", "") or "").strip()
            o = (getattr(f, "origin_airport_iata", "") or "")
            d = (getattr(f, "destination_airport_iata", "") or "")
            o = o if o.upper() not in BLANK_FIELDS else ""
            d = d if d.upper() not in BLANK_FIELDS else ""
            if cs and (o or d):
                out[cs] = (o, d)
        self._gov.record_success("fr24")
        self._fr24_empty_streak = 0
        if not self._fr24_healthy:
            self._log("FR24 route lookups recovered")
        self._fr24_healthy = True
        self._fr24_last_t = self._clock()
        self._fr24_last_map = out
        return out

    def _fr24_failed(self, why):
        wait, _ = self._gov.wait_time("fr24")
        self._log(f"FR24 route lookup failed: {why}; next try in {wait:.0f}s "
                  "(adsbdb fallback meanwhile)")
        self._fr24_healthy = False

    def _adsbdb_route(self, callsign):
        """callsign -> (origin_iata, destination_iata) via adsbdb (may be stale).

        Hits are cached ADSBDB_HIT_TTL, misses (404 / no route) ADSBDB_MISS_TTL.
        Refusals, 429s, 5xx and timeouts are NOT cached: the governor's cooldown
        stops repeats, and a later poll asks again once it clears."""
        now = self._clock()
        hit = self._adsbdb_cache.get(callsign)
        if hit and hit[0] > now:
            return hit[1]
        try:
            resp = self._gov.get(self._session, ADSBDB_URL.format(callsign=quote(callsign)),
                                 host="adsbdb", max_wait=ADSBDB_MAX_WAIT, timeout=HTTP_TIMEOUT)
        except RateLimited:
            return ("", "")
        except Exception:
            return ("", "")
        if resp.status_code not in (200, 404):
            return ("", "")
        origin = destination = ""
        try:
            fr = (resp.json() or {}).get("response", {})
            if isinstance(fr, dict):
                route = fr.get("flightroute") or {}
                o = ((route.get("origin") or {}).get("iata_code") or "").strip()
                d = ((route.get("destination") or {}).get("iata_code") or "").strip()
                origin = o if o.upper() not in BLANK_FIELDS else ""
                destination = d if d.upper() not in BLANK_FIELDS else ""
        except (ValueError, KeyError, AttributeError):
            return ("", "")             # unparseable 200: don't cache it
        result = (origin, destination)
        ttl = ADSBDB_HIT_TTL if (origin or destination) else ADSBDB_MISS_TTL
        self._adsbdb_cache[callsign] = (self._clock() + ttl, result)
        return result

    # ------------------------------------------------------------ interface
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
        with self._lock:
            return len(self._data) == 0

    @property
    def data_age(self):
        """Seconds since positions last arrived (since start if never)."""
        with self._lock:
            ref = self._last_ok if self._last_ok is not None else self._started
        return max(0.0, self._clock() - ref)

    @property
    def stale(self):
        """True when no provider has answered for STALE_AFTER_SECONDS. The data
        on screen is then the last good snapshot (scenes may show a hint)."""
        if DEMO_MODE:
            return False
        return self.data_age > STALE_AFTER_SECONDS

    @property
    def source(self):
        """Name of the provider that supplied the current data (None until first)."""
        with self._lock:
            return self._source

    def rate_stats(self):
        """Per-host request counters and pauses (for logging / a status scene)."""
        return self._gov.stats()


if __name__ == "__main__":
    o = Overhead()
    while not o.new_data:
        print("processing...")
        sleep(1)
    print(f"source={o.source} stale={o.stale}")
    for f in o.data:
        print(f)
    print(o._gov.summary())
