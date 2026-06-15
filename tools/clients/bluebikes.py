"""Bluebikes GBFS client — MIT Pacific St at Purrington St (nearest 540 Mem Dr).

General Bikeshare Feed Spec 2.3 (Lyft), free, no key, CORS-friendly. Reports the
classic vs e-bike split and free docks — exactly what the Bluebikes wall zone
needs ("both ebikes and normal bikes").
"""
from __future__ import annotations

from dataclasses import dataclass, asdict

from ._http import get_json

STATION_STATUS = "https://gbfs.lyft.com/gbfs/2.3/bos/en/station_status.json"
STATION_INFO = "https://gbfs.lyft.com/gbfs/2.3/bos/en/station_information.json"

# MIT Pacific St at Purrington St
STATION_ID = "f835141c-0de8-11e7-991c-3863bb43a7d0"
STATION_NAME = "PACIFIC ST"


@dataclass
class BikeStation:
    name: str
    classic: int
    ebikes: int
    docks: int
    capacity: int
    renting: bool
    returning: bool

    def as_dict(self) -> dict:
        return asdict(self)


def _ebike_count(s: dict) -> int:
    """num_ebikes_available if present, else sum non-'1' vehicle types."""
    if s.get("num_ebikes_available") is not None:
        return int(s["num_ebikes_available"])
    total = 0
    for v in s.get("vehicle_types_available", []) or []:
        # GBFS convention: vehicle_type_id "1" == classic pedal bike
        if str(v.get("vehicle_type_id")) != "1":
            total += int(v.get("count", 0))
    return total


def fetch(station_id: str = STATION_ID, timeout: float = 8.0) -> BikeStation:
    j = get_json(STATION_STATUS, timeout=timeout)
    stations = j["data"]["stations"]
    s = next((x for x in stations if x.get("station_id") == station_id), None)
    if s is None:
        raise LookupError(f"Bluebikes station {station_id} not found in feed")
    bikes = int(s.get("num_bikes_available", 0))
    ebikes = _ebike_count(s)
    docks = int(s.get("num_docks_available", 0))
    classic = max(0, bikes - ebikes)
    return BikeStation(
        name=STATION_NAME,
        classic=classic, ebikes=ebikes, docks=docks,
        capacity=bikes + docks,
        renting=bool(s.get("is_renting", 1)),
        returning=bool(s.get("is_returning", 1)),
    )


if __name__ == "__main__":
    b = fetch()
    print(f"{b.name}: {b.classic} classic + {b.ebikes} e-bike  |  "
          f"{b.docks} free docks  (cap {b.capacity})  "
          f"{'rent✓' if b.renting else 'rent✗'} {'return✓' if b.returning else 'return✗'}")
