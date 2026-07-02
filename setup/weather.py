"""
weather.py — current conditions from Open-Meteo (free, no API key, HTTPS).

Refreshes on a background thread every ~10 min and exposes current() -> dict:
    {temp_c, temp_f, code, condition, category, wind_mph, is_day}
Temperatures in BOTH °C and °F (a hard requirement for the display).

Uses the OS trust store for TLS so it works after the matrix drops privileges
to `daemon` (the venv's certifi bundle under /home/pi 0700 is unreadable there).
"""

import os
from threading import Thread, Lock
from time import sleep

import requests

try:
    from config import LOCATION_HOME
except (ModuleNotFoundError, NameError, ImportError):
    LOCATION_HOME = [42.354, -71.107, 0.005]

LAT, LON = LOCATION_HOME[0], LOCATION_HOME[1]
URL = (
    "https://api.open-meteo.com/v1/forecast"
    "?latitude={lat}&longitude={lon}"
    "&current=temperature_2m,weather_code,wind_speed_10m,is_day"
    "&temperature_unit=celsius&wind_speed_unit=mph"
)

_SYS_CA = "/etc/ssl/certs/ca-certificates.crt"
VERIFY = _SYS_CA if os.path.exists(_SYS_CA) else True
REFRESH = 600         # seconds
USER_AGENT = "Vestor-LED/1.0 (+https://github.com/g-vansh/vestor)"

# WMO weather code -> (short condition, icon category)
_WMO = {
    0: ("CLEAR", "sun"),
    1: ("CLEAR", "sun"), 2: ("PARTLY", "partly"), 3: ("CLOUDY", "cloud"),
    45: ("FOG", "fog"), 48: ("FOG", "fog"),
    51: ("DRIZZLE", "rain"), 53: ("DRIZZLE", "rain"), 55: ("DRIZZLE", "rain"),
    56: ("FRZ DRZL", "rain"), 57: ("FRZ DRZL", "rain"),
    61: ("RAIN", "rain"), 63: ("RAIN", "rain"), 65: ("HVY RAIN", "rain"),
    66: ("FRZ RAIN", "rain"), 67: ("FRZ RAIN", "rain"),
    71: ("SNOW", "snow"), 73: ("SNOW", "snow"), 75: ("HVY SNOW", "snow"),
    77: ("SNOW", "snow"),
    80: ("SHOWERS", "rain"), 81: ("SHOWERS", "rain"), 82: ("SHOWERS", "rain"),
    85: ("SNOW", "snow"), 86: ("SNOW", "snow"),
    95: ("STORM", "storm"), 96: ("STORM", "storm"), 99: ("STORM", "storm"),
}

_lock = Lock()
_state = None
_started = False


def _fetch():
    s = requests.Session()
    s.headers.update({"User-Agent": USER_AGENT})
    s.verify = VERIFY
    c = s.get(URL.format(lat=LAT, lon=LON), timeout=10).json()["current"]
    tc = c["temperature_2m"]
    code = int(c.get("weather_code", 0))
    cond, cat = _WMO.get(code, ("--", "cloud"))
    return {
        "temp_c": int(round(tc)),
        "temp_f": int(round(tc * 9 / 5 + 32)),
        "code": code,
        "condition": cond,
        "category": cat,
        "wind_mph": int(round(c.get("wind_speed_10m", 0))),
        "is_day": bool(c.get("is_day", 1)),
    }


def _loop():
    global _state
    while True:
        try:
            data = _fetch()
            with _lock:
                _state = data
        except Exception as e:
            print(f"[weather] fetch error: {e}", flush=True)
        sleep(REFRESH)


def start():
    global _started
    if _started:
        return
    _started = True
    Thread(target=_loop, daemon=True).start()


def current():
    with _lock:
        return _state
