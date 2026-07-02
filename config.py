"""Vestor configuration (committed — NO secrets).

Location: 540 Memorial Drive, Cambridge MA (~42.354 N, -71.107 W), facing
south/southeast toward Logan/BOS traffic over Dorchester and the harbor.

Secrets (API keys) are NOT stored here. They are read from the environment,
populated by the gitignored `.env` (loaded via the systemd EnvironmentFile, or
`source .env` for manual runs). See `.env.example` / `config_secrets.example.py`.
"""

import os

# ---- Flight tracking zone (bounding box: top-left + bottom-right lat/lon) ----
ZONE_HOME = {
    "tl_y": 42.400,   # north edge (past Logan)
    "tl_x": -71.120,  # west edge (near home)
    "br_y": 42.280,   # south edge (Dorchester/Quincy)
    "br_x": -70.980,  # east edge (over the harbor)
}

# ---- Home location [lat, lon, altitude_km] — used to sort flights by distance ----
LOCATION_HOME = [42.354, -71.107, 0.005]

# ---- Weather ----
WEATHER_LOCATION = "Cambridge,MA,US"
TEMPERATURE_UNITS = "imperial"

# ---- Flight filtering / display ----
MIN_ALTITUDE = 100              # feet — filters out planes on the tarmac
MAX_ALTITUDE = 10000            # feet — only low "visible overhead" traffic (planes
                                # you can actually see from the window). Raise toward
                                # 40000 to also catch cruise overflights.

# ---- Panel geometry ----
# Number of 64x32 panels chained on the data line (left->right, calibrated
# 2026-07-01: canvas col 0..63 = physical LEFT panel, 64..127 = middle, 128..191
# = right; no mirror/flip). Drives the multi-panel departure-board layout.
CHAIN_LENGTH = 3

# ---- Live data source: airplanes.live (positions+type) + adsbdb.com (routes) ----
# Free, no-key community ADS-B (replaces the throttled FR24 unofficial feed).
# We query airplanes.live by point+radius, then (if USE_ZONE_BOX) keep only the
# aircraft inside the ZONE_HOME bounding box above — the same area FR24 used.
SEARCH_RADIUS_NM = 10           # nm around LOCATION_HOME to query (must cover ZONE_HOME)
POLL_SECONDS = 10               # how often to poll airplanes.live for what's overhead
MAX_FLIGHTS = 5                 # show the nearest N aircraft (cycled on the board)
USE_ZONE_BOX = True             # True = filter to ZONE_HOME box; False = full radius circle
JOURNEY_CODE_SELECTED = "BOS"   # bolds Logan as the local airport
JOURNEY_BLANK_FILLER = " ? "

# Demo mode: skip FlightRadar24 and cycle a fixed set of sample flights. Useful
# when the (free, rate-limited) FR24 feed is throttled or during a real lull, so
# the board still shows the flight card. Set False for live data.
DEMO_MODE = False

# ---- Display / hardware (Triple Bonnet, Pi 4) ----
BRIGHTNESS = 50                 # 0-100
GPIO_SLOWDOWN = 4               # Pi 4 starting value (try 5 on hardware if garbage)
HAT_PWM_ENABLED = False         # ignored now that hardware_mapping is hardcoded "regular"

# ---- Secrets from environment (never hardcode keys in this committed file) ----
OPENWEATHER_API_KEY = os.environ.get("OPENWEATHER_API_KEY", "")
MBTA_API_KEY = os.environ.get("MBTA_API_KEY", "")
