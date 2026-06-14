# ROADMAP — future scenes
- **Weather:** NWS `api.weather.gov/points/42.354,-71.107` (User-Agent header, no key) or OpenWeatherMap (free key, upstream-native).
- **MBTA:** `api-v3.mbta.com` (free key; 20→1000 req/min). `/predictions?filter[stop]=place-cntsq&filter[route]=Red` (also `place-knncl`). Refs: dufus2506/MBTA-bus-train-stop-prediction-sign, TrevorSayre/led-matrix-mbta-signage.
- **Bluebikes:** GBFS (no key) `gbfs.bluebikes.com/gbfs/gbfs.json` → station_information + station_status; nearest One Memorial Drive + MIT-area Memorial Dr docks.
- **Local ADS-B:** RTL-SDR + dump1090 `aircraft.json`; consume via exxamalte/python-flightradar-client `Dump1090AircraftsFeed`. Kills cloud rate-limit/breakage risk.
- **Dashboard alt:** ChuckBuilds/LEDMatrix (native Triple Bonnet: regular/parallel=3/slowdown=4; weather/clock/calendar/sports/stocks). Never run two matrix processes at once.
