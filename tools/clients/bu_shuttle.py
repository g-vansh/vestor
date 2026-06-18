"""BU shuttle client — the BU "Hyatt" route, live-tracked via TransLoc.

Boston University runs the campus shuttle fleet ("the BUS") on TransLoc, with a
public live map at https://bu.transloc.com. The relevant line for the wall is the
**Hyatt** route, which departs from next to 540 Memorial Dr and crosses the Charles
to BU:

    Amesbury St. @ Vassar St   (StopID 21)  -- the Cambridge stop, ~170 m from the
                                                wall, beside the Hyatt Regency
    Comm Ave @ St Mary's St     (StopID 3)
    George Sherman Union (GSU)  (StopID 22)  -- BU central campus

The TransLoc "3" platform exposes a JSONP relay service (the same backend the
public map calls). We hit it as plain JSON. The `Seconds` field on each arrival is
a live countdown; a non-null `EstimateTime` means the estimate is from a tracked
vehicle (vs. schedule-only), which is what we keep for a "live tracking" display.

Verified live (2026-06-18): route list + stops resolved, and `GetStopArrivalTimes`
returns real `Seconds`/`VehicleId` estimates for active routes (1, 3). The Hyatt
route runs ~7am-7pm on weekdays (reduced in summer); when no vehicle is assigned
the arrivals list is empty and `fetch()` correctly returns an empty list.

No API key of our own and no scraping: `8882812681` is the public relay key the
bu.transloc.com map itself ships with. Stdlib-only (no extra Pi deps).
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field, asdict

from ._http import get_json

# Public TransLoc relay used by the bu.transloc.com live map.
BASE = "https://bu.transloc.com/Services/JSONPRelay.svc"
APIKEY = "8882812681"

ROUTE_HYATT = 5                 # "Hyatt"  (Amesbury/Vassar <-> GSU)
STOP_AMESBURY_VASSAR = 21       # the Cambridge boarding stop, beside the wall


def _get(method: str, **params) -> object:
    qs = "&".join(f"{k}={v}" for k, v in {"APIKey": APIKEY, **params}.items())
    return get_json(f"{BASE}/{method}?{qs}",
                    headers={"User-Agent": "Mozilla/5.0 (vestor-led-wall)"})


@dataclass
class BuShuttleArrivals:
    route_id: int = ROUTE_HYATT
    route_name: str = "Hyatt"
    stop_id: int = STOP_AMESBURY_VASSAR
    stop_name: str = "Amesbury St @ Vassar St"
    hyatt: list[int] = field(default_factory=list)   # minutes to arrival, ascending
    live_vehicles: int = 0                           # tracked vehicles on the route

    def as_dict(self) -> dict:
        return asdict(self)


def fetch(route_id: int = ROUTE_HYATT, stop_id: int = STOP_AMESBURY_VASSAR,
          timeout: float = 8.0, realtime_only: bool = True) -> BuShuttleArrivals:
    """Live arrivals of the BU Hyatt shuttle at the Amesbury/Vassar stop."""
    out = BuShuttleArrivals(route_id=route_id, stop_id=stop_id)
    # active tracked vehicles on the route
    try:
        veh = _get("GetMapVehiclePoints", routeIDs=route_id, timeout=timeout)
        out.live_vehicles = sum(1 for v in (veh or []) if v.get("RouteID") == route_id)
    except Exception:
        pass
    # per-stop arrival estimates
    rows = _get("GetStopArrivalTimes", routeIDs=route_id, timeout=timeout) or []
    now = time.time()
    mins: list[int] = []
    for row in rows:
        if row.get("StopId") != stop_id:
            continue
        if row.get("RouteDescription"):
            out.route_name = row["RouteDescription"]
        if row.get("StopDescription"):
            out.stop_name = row["StopDescription"]
        for tdat in (row.get("Times") or []):
            if realtime_only and not tdat.get("EstimateTime"):
                continue            # schedule-only entry, no tracked vehicle
            if tdat.get("IsDeparted"):
                continue
            secs = tdat.get("Seconds")
            if secs is None:
                est = _dotnet_ms(tdat.get("EstimateTime"))
                if est is None:
                    continue
                secs = est / 1000.0 - now
            m = int(round(secs / 60.0))
            if m < 0:
                continue
            mins.append(m)
    out.hyatt = sorted(set(mins))
    return out


def _dotnet_ms(s):
    """Parse a .NET '/Date(1781813597118)/' string to epoch milliseconds."""
    if not s or "(" not in s:
        return None
    try:
        return int(s[s.index("(") + 1:s.index(")")].split("+")[0].split("-")[0])
    except Exception:
        return None


def discover(timeout: float = 8.0) -> dict:
    """Dump the BU route list + Hyatt stops so route/stop IDs can be re-verified."""
    routes = _get("GetRoutesForMapWithScheduleWithEncodedLine", timeout=timeout) or []
    out = {"routes": [], "hyatt_stops": []}
    for r in routes:
        out["routes"].append({"id": r.get("RouteID"), "name": r.get("Description"),
                              "visible": r.get("IsVisibleOnMap"),
                              "n_stops": len(r.get("Stops") or [])})
        if r.get("RouteID") == ROUTE_HYATT:
            for s in (r.get("Stops") or []):
                out["hyatt_stops"].append({"stop_id": s.get("AddressID") or s.get("StopID"),
                                           "name": s.get("Description")})
    return out


if __name__ == "__main__":
    import sys
    if "--discover" in sys.argv:
        import json
        print(json.dumps(discover(), indent=2))
    else:
        a = fetch()
        print(f"BU {a.route_name} @ {a.stop_name} (stop {a.stop_id}):  "
              f"{a.hyatt or '--'}  (minutes; {a.live_vehicles} live vehicle(s))")
