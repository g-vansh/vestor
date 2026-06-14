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
JOURNEY_CODE_SELECTED = "BOS"   # bolds Logan as the local airport
JOURNEY_BLANK_FILLER = " ? "

# ---- Display / hardware (Triple Bonnet, Pi 4) ----
BRIGHTNESS = 50                 # 0-100
GPIO_SLOWDOWN = 4               # Pi 4 starting value (try 5 on hardware if garbage)
HAT_PWM_ENABLED = False         # ignored now that hardware_mapping is hardcoded "regular"

# ---- Secrets from environment (never hardcode keys in this committed file) ----
OPENWEATHER_API_KEY = os.environ.get("OPENWEATHER_API_KEY", "")
MBTA_API_KEY = os.environ.get("MBTA_API_KEY", "")
