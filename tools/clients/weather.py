"""Open-Meteo weather client — returns temperatures in BOTH °C and °F.

Free, no API key, CORS-friendly. The Vestor weather scene shows Celsius and
Fahrenheit simultaneously (owner requirement), so this client always provides
both; °F is derived as C*9/5+32.

Anchor: 540 Memorial Drive, Cambridge MA.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict

from ._http import get_json

LAT, LON = 42.354, -71.107

# WMO weather-code → short label (mirrors sim/scenes.js WMO_TEXT)
WMO_TEXT = {
    0: "CLEAR", 1: "MOSTLY FAIR", 2: "PARTLY CLOUDY", 3: "CLOUDY",
    45: "FOG", 48: "RIME FOG",
    51: "DRIZZLE", 53: "DRIZZLE", 55: "DRIZZLE",
    61: "RAIN", 63: "RAIN", 65: "HEAVY RAIN",
    71: "SNOW", 73: "SNOW", 75: "HEAVY SNOW", 77: "SNOW GRAINS",
    80: "SHOWERS", 81: "SHOWERS", 82: "VIOLENT SHOWERS",
    85: "SNOW SHOWERS", 86: "SNOW SHOWERS",
    95: "THUNDERSTORM", 96: "THUNDERSTORM", 99: "THUNDERSTORM",
}


def c_to_f(c: float) -> float:
    return c * 9.0 / 5.0 + 32.0


@dataclass
class Weather:
    temp_c: float
    temp_f: float
    feels_c: float
    feels_f: float
    code: int
    condition: str
    hi_c: float
    hi_f: float
    lo_c: float
    lo_f: float
    humidity: int
    wind_kph: float
    wind_mph: float
    wind_dir: int
    is_day: bool

    def as_dict(self) -> dict:
        return asdict(self)


def fetch(lat: float = LAT, lon: float = LON, timeout: float = 8.0) -> Weather:
    url = (
        "https://api.open-meteo.com/v1/forecast"
        f"?latitude={lat}&longitude={lon}"
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"
        "precipitation,weather_code,wind_speed_10m,wind_direction_10m"
        "&daily=temperature_2m_max,temperature_2m_min"
        "&timezone=America/New_York"
    )
    j = get_json(url, timeout=timeout)
    c = j["current"]
    d = j["daily"]
    tc = float(c["temperature_2m"])
    fc = float(c["apparent_temperature"])
    hi = float(d["temperature_2m_max"][0])
    lo = float(d["temperature_2m_min"][0])
    code = int(c["weather_code"])
    return Weather(
        temp_c=tc, temp_f=c_to_f(tc),
        feels_c=fc, feels_f=c_to_f(fc),
        code=code, condition=WMO_TEXT.get(code, "WX"),
        hi_c=hi, hi_f=c_to_f(hi), lo_c=lo, lo_f=c_to_f(lo),
        humidity=int(c["relative_humidity_2m"]),
        wind_kph=float(c["wind_speed_10m"]), wind_mph=float(c["wind_speed_10m"]) * 0.621371,
        wind_dir=int(c["wind_direction_10m"]),
        is_day=bool(c["is_day"]),
    )


if __name__ == "__main__":
    w = fetch()
    print(f"{w.condition}: {w.temp_c:.1f}°C / {w.temp_f:.1f}°F  "
          f"(feels {w.feels_c:.1f}°C/{w.feels_f:.1f}°F)  "
          f"hi {w.hi_c:.0f}/{w.hi_f:.0f}  lo {w.lo_c:.0f}/{w.lo_f:.0f}  "
          f"RH {w.humidity}%  wind {w.wind_kph:.0f} kph/{w.wind_mph:.0f} mph @ {w.wind_dir}°  "
          f"{'day' if w.is_day else 'night'}")
