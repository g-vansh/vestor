# NOTICE

Vestor is derived from **ColinWaddell/its-a-plane-python**
(https://github.com/ColinWaddell/its-a-plane-python), licensed under **GPL-3.0**.
This repository remains licensed under GPL-3.0; see `LICENSE`. Original copyright
notices are retained.

## Major modifications by g-vansh
- Adafruit **Triple** RGB Matrix Bonnet (product 6358, "active3") support:
  `hardware_mapping="regular"`, `parallel=3`, `disable_hardware_pulsing=False`.
- **FM6124/FM6126A** panel init (`panel_type="FM6126A"`), 64x32 1/16-scan panels.
- Pi 4 tuning (`gpio_slowdown=4`), flicker tweaks, location config for
  540 Memorial Drive, Cambridge MA (Logan/BOS corridor).
- Modular `scenes/` + `data/` architecture for future MBTA, Bluebikes, weather,
  and animation scenes.

## Live flight data sources
Aircraft positions come from community ADS-B networks, used for personal,
non-commercial display only and within each service's published rate limits
(see `utilities/ratelimit.py`).
- **adsb.fi** open data API, https://adsb.fi (primary position source)
- **adsb.lol**, https://adsb.lol (fallback position source)
- **airplanes.live**, https://airplanes.live (last resort, needs registration)
- **adsbdb.com**, https://www.adsbdb.com (callsign to route lookups)
- **Flightradar24** via the unofficial FlightRadarAPI package (current routes,
  low volume, personal use)
