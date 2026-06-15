"""Vestor live-data clients — one module per wall data source.

All clients are stdlib-only over `_http` (urllib) except `mit_shuttle`, which also
needs `gtfs-realtime-bindings`. Each exposes a `fetch()` returning a dataclass
shaped to mirror the simulator's DataModel, plus a `__main__` CLI for spot checks.

    from tools.clients import weather, bluebikes, mit_shuttle, flights
    w = weather.fetch(); b = bluebikes.fetch()
    s = mit_shuttle.fetch(); sky = flights.fetch()
"""
from __future__ import annotations

from . import _http, weather, bluebikes, mit_shuttle, flights  # noqa: F401

__all__ = ["_http", "weather", "bluebikes", "mit_shuttle", "flights"]
