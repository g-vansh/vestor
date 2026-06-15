"""Live smoke-test for every Vestor data client.

Hits the real public APIs and prints a compact PASS/skip line per source plus the
combined JSON the wall would consume. Run from the repo root:

    python3 -m tools.clients.test_live          # human-readable
    python3 -m tools.clients.test_live --json   # combined live snapshot as JSON

A source that returns no rows (e.g. shuttle overnight) is reported as EMPTY, not
FAIL — empty is valid data. Only an exception counts as a failure.
"""
from __future__ import annotations

import json
import sys
import time

from . import weather, bluebikes, mit_shuttle, flights


def _run(name, fn):
    t0 = time.time()
    try:
        val = fn()
        return name, "OK", round(time.time() - t0, 2), val, None
    except Exception as e:  # noqa: BLE001
        return name, "FAIL", round(time.time() - t0, 2), None, f"{type(e).__name__}: {e}"


def snapshot() -> dict:
    results = [
        _run("weather", weather.fetch),
        _run("bluebikes", bluebikes.fetch),
        _run("shuttle", mit_shuttle.fetch),
        _run("flights", flights.fetch),
    ]
    out = {"ts": int(time.time()), "sources": {}}
    for name, status, dt, val, err in results:
        out["sources"][name] = {
            "status": status, "seconds": dt, "error": err,
            "data": val.as_dict() if val is not None else None,
        }
    return out


def main() -> int:
    snap = snapshot()
    if "--json" in sys.argv:
        print(json.dumps(snap, indent=2))
        return 0
    fails = 0
    for name, s in snap["sources"].items():
        data, status = s["data"], s["status"]
        empty = ""
        if status == "OK":
            if name == "shuttle" and not (data["tech"] or data["tech_nw"]):
                empty = "  (EMPTY — no service now)"
            if name == "flights" and not data["hero"]:
                empty = "  (EMPTY — no aircraft)"
        flag = "PASS" if status == "OK" else "FAIL"
        if status == "FAIL":
            fails += 1
        line = f"[{flag}] {name:10s} {s['seconds']:>5}s{empty}"
        print(line)
        if status == "FAIL":
            print("        ", s["error"])
    # one-line human summaries
    src = snap["sources"]
    if src["weather"]["status"] == "OK":
        w = src["weather"]["data"]
        print(f"   weather  : {w['condition']} {w['temp_c']:.0f}°C/{w['temp_f']:.0f}°F  RH{w['humidity']}%")
    if src["bluebikes"]["status"] == "OK":
        b = src["bluebikes"]["data"]
        print(f"   bikes    : {b['classic']} classic + {b['ebikes']} e-bike, {b['docks']} docks")
    if src["flights"]["status"] == "OK" and src["flights"]["data"]["hero"]:
        h = src["flights"]["data"]["hero"]
        print(f"   flight   : {h['callsign']} {h['origin'] or '?'}->{h['dest'] or '?'} "
              f"FL{h['alt']//100:03d} {h['distance']}mi")
    print(f"\n{'ALL PASS' if not fails else str(fails)+' FAILED'} "
          f"({len(snap['sources'])} sources)")
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
