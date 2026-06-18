"""MIT shuttle client — Passio GTFS-realtime arrivals at Grad Junction West.

MIT shuttles run on Passio GO (agency `mit`). The per-stop ETA REST endpoint is
disabled, so we read GTFS-realtime **TripUpdates** (protobuf) and compute minutes
to arrival at the Grad Junction West stop.

Verified against the live static GTFS (`google_transit.zip`) on 2026-06-15:

    Grad Junction West  stop_id = 180113   (Grad Junction East = 179825)
    Routes serving that stop:
        63220  "Tech Shuttle"        -> line A  (ROUTE_TECH)
        56642  "Tech Shuttle2"       -> line B  (ROUTE_TECH2)
        71674  "Tech Shuttle2 copy"  -> line B  (same line, 2nd vehicle)

There is no separately-named "Tech Shuttle NW" in the current feed; the second
Tech line ("Tech Shuttle 2") is the closest match and is what `tech_nw` carries.
The GTFS-realtime feed shares the static stop_id namespace, so `180113` matches
directly. `trip_update.trip` does not always include a `route_id`, so we fall
back to a cached trip_id->route_id map built from the static GTFS.

Note: the Tech lines are daytime services — overnight the TripUpdates feed is
empty (only Saferide runs), and `fetch()` correctly returns empty arrival lists.

Requires: gtfs-realtime-bindings + protobuf  (pip install gtfs-realtime-bindings).
"""
from __future__ import annotations

import io
import time
import zipfile
import csv
from dataclasses import dataclass, field, asdict

from ._http import get_bytes

TRIP_UPDATES = "https://passio3.com/mit/passioTransit/gtfs/realtime/tripUpdates"
VEHICLE_POSITIONS = "https://passio3.com/mit/passioTransit/gtfs/realtime/vehiclePositions"
GTFS_STATIC = "https://passio3.com/mit/passioTransit/gtfs/google_transit.zip"

# Tech Shuttle (line A) and Tech Shuttle 2 (line B; two vehicles share the line).
ROUTE_TECH = {"63220"}
ROUTE_TECH2 = {"56642", "71674"}
STOP_GRAD_JUNCTION_W = "180113"

# SafeRide — MIT's evening/overnight safety shuttle. Runs on the SAME Passio feed.
# Verified against the static GTFS (2026-06-18): the SafeRide line that actually
# stops next to the wall is "Saferide Campus" (route_id 56140) at stop
#   3813  "W98 @ Vassar St"   (43 m from 540 Memorial Dr — the closest stop of all)
# which the Tech Shuttle also serves. Rather than hard-code a single SafeRide
# route, we classify by name (route_long_name starts with "Saferide") so whichever
# SafeRide line shows up at that stop in the evening realtime feed is captured.
# SafeRide is an evening service (~6pm-3am); daytime the feed has no SafeRide trips
# and `fetch()` correctly returns an empty `saferide` list.
STOP_SAFERIDE = "3813"           # W98 @ Vassar St (Saferide Campus, at the wall)
ROUTE_SAFERIDE_CAMPUS = {"56140"}

# Lazily-populated caches from the static GTFS.
_TRIP_ROUTE: dict[str, str] | None = None
_ROUTE_NAME: dict[str, str] | None = None


def _feed(url: str, timeout: float = 8.0):
    from google.transit import gtfs_realtime_pb2  # imported lazily
    msg = gtfs_realtime_pb2.FeedMessage()
    msg.ParseFromString(get_bytes(url, timeout=timeout))
    return msg


def _load_static(timeout: float = 10.0) -> None:
    """Download google_transit.zip once and build trip->route and route->name."""
    global _TRIP_ROUTE, _ROUTE_NAME
    if _TRIP_ROUTE is not None:
        return
    z = zipfile.ZipFile(io.BytesIO(get_bytes(GTFS_STATIC, timeout=timeout)))
    trip_route, route_name = {}, {}
    with z.open("trips.txt") as f:
        for t in csv.DictReader(io.TextIOWrapper(f, "utf-8")):
            trip_route[t["trip_id"]] = t["route_id"]
    with z.open("routes.txt") as f:
        for r in csv.DictReader(io.TextIOWrapper(f, "utf-8")):
            route_name[r["route_id"]] = (r.get("route_short_name")
                                         or r.get("route_long_name") or r["route_id"])
    _TRIP_ROUTE, _ROUTE_NAME = trip_route, route_name


def _route_of(trip) -> str:
    """route_id straight from the RT message, else resolved via static trips.txt."""
    if trip.route_id:
        return trip.route_id
    if trip.trip_id:
        _load_static()
        return (_TRIP_ROUTE or {}).get(trip.trip_id, "")
    return ""


def _is_saferide(rid: str) -> bool:
    """True if route_id belongs to a SafeRide line (by static route_long_name)."""
    if not rid:
        return False
    _load_static()
    return (_ROUTE_NAME or {}).get(rid, "").lower().startswith("saferide")


@dataclass
class ShuttleArrivals:
    stop_id: str
    tech: list[int] = field(default_factory=list)       # minutes, ascending
    tech_nw: list[int] = field(default_factory=list)     # "Tech Shuttle 2" line
    saferide: list[int] = field(default_factory=list)    # SafeRide @ W98 (evening)
    saferide_stop: str = STOP_SAFERIDE

    def as_dict(self) -> dict:
        return asdict(self)


def fetch(stop_id: str = STOP_GRAD_JUNCTION_W,
          route_tech: set[str] = ROUTE_TECH, route_tech_nw: set[str] = ROUTE_TECH2,
          saferide_stop: str = STOP_SAFERIDE,
          timeout: float = 8.0) -> ShuttleArrivals:
    msg = _feed(TRIP_UPDATES, timeout=timeout)
    now = time.time()
    out = ShuttleArrivals(stop_id=stop_id, saferide_stop=saferide_stop)
    for ent in msg.entity:
        if not ent.HasField("trip_update"):
            continue
        tu = ent.trip_update
        rid = _route_of(tu.trip)
        is_tech = rid in route_tech
        is_tech_nw = rid in route_tech_nw
        is_safe = _is_saferide(rid)
        if not (is_tech or is_tech_nw or is_safe):
            continue
        for stu in tu.stop_time_update:
            # Tech lines report at Grad Junction West; SafeRide at its own stop.
            want_stop = saferide_stop if is_safe else stop_id
            if stu.stop_id != want_stop:
                continue
            ev = stu.arrival if stu.HasField("arrival") else (stu.departure if stu.HasField("departure") else None)
            if ev is None or not ev.time:
                continue
            mins = int(round((ev.time - now) / 60.0))
            if mins < 0:
                continue
            if is_safe:
                out.saferide.append(mins)
            elif is_tech:
                out.tech.append(mins)
            else:
                out.tech_nw.append(mins)
    out.tech.sort()
    out.tech_nw.sort()
    out.saferide.sort()
    return out


def discover(timeout: float = 8.0) -> dict:
    """Dump what the live TripUpdates feed contains, with static route names
    resolved — used to confirm route_ids/stop_ids against the static GTFS."""
    msg = _feed(TRIP_UPDATES, timeout=timeout)
    routes, stops, n_trip = set(), set(), 0
    for ent in msg.entity:
        if not ent.HasField("trip_update"):
            continue
        n_trip += 1
        routes.add(_route_of(ent.trip_update.trip))
        for stu in ent.trip_update.stop_time_update:
            stops.add(stu.stop_id)
    names = {}
    if routes:
        _load_static()
        names = {r: (_ROUTE_NAME or {}).get(r, "?") for r in routes}
    saferide_live = sorted(r for r in routes if (names.get(r, "").lower().startswith("saferide")))
    return {"entities": len(msg.entity), "trip_updates": n_trip,
            "route_ids": sorted(routes), "route_names": names,
            "grad_jct_west_present": STOP_GRAD_JUNCTION_W in stops,
            "saferide_routes_live": saferide_live,
            "w98_vassar_present": STOP_SAFERIDE in stops,
            "stop_ids_sample": sorted(stops)[:40], "n_stops": len(stops)}


if __name__ == "__main__":
    import sys
    if "--discover" in sys.argv:
        import json
        print(json.dumps(discover(), indent=2))
    else:
        a = fetch()
        print(f"Grad Junction West ({a.stop_id}):  "
              f"TECH {a.tech or '--'}  |  TECH 2 {a.tech_nw or '--'}  (minutes)")
        print(f"W98 @ Vassar St ({a.saferide_stop}):  "
              f"SAFERIDE {a.saferide or '--'}  (minutes; evening service)")
