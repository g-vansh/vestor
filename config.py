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

# ---- Live data source: community ADS-B (positions+type) + FR24/adsbdb (routes) ----
# Free, no-key community ADS-B (replaces the throttled FR24 unofficial feed).
# We query a provider by point+radius, then (if USE_ZONE_BOX) keep only the
# aircraft inside the ZONE_HOME bounding box above — the same area FR24 used.
# Every request goes through utilities/ratelimit.py (per-host budgets, Retry-After,
# jittered backoff). Documented limits and their sources are listed there.
SEARCH_RADIUS_NM = 10           # nm around LOCATION_HOME to query (must cover ZONE_HOME)
# How often to ask for what's overhead. 5 s = 12 requests/min to the primary,
# a fifth of adsb.fi's documented 1 request/second. Going lower gains nothing:
# the display only checks for new data every 5 s.
POLL_SECONDS = 5
# Position providers, tried in order. The first healthy one answers each poll.
# On an error, timeout, 429/403 or a local budget refusal the next one answers
# the same poll, and the failing one is re-probed after its backoff (<= 5 min).
#   adsb.fi        documented 1 req/s, no daily cap -> primary
#   adsb.lol       4/s, 60/min but only 500/DAY -> fallback, paced (15 s, 400/day)
#   airplanes.live answered 403 "please contact us" on 2026-10-27 -> last resort.
#                  Email contact@airplanes.live to request access if wanted.
FLIGHT_PROVIDERS = ["adsb.fi", "adsb.lol", "airplanes.live"]
# Per-provider request budgets (overrides; anything not listed keeps the
# defaults in utilities/overhead.py DEFAULT_POLICIES). Keep them well below the
# documented limits noted beside each one.
RATE_BUDGETS = {
    "adsb.fi":        {"min_interval": 2.0,  "per_minute": 20},                  # limit 60/min
    "adsb.lol":       {"min_interval": 15.0, "per_minute": 4, "per_day": 400},   # limit 500/day
    "airplanes.live": {"min_interval": 5.0,  "per_minute": 12},                  # limit 60/min
    "adsbdb":         {"min_interval": 0.5,  "per_minute": 30},                  # limit 512/min
    "fr24":           {"min_interval": 20.0, "per_minute": 3},                   # undocumented
}
# FR24 route lookups: 20 s floor (above), then after each consecutive failure
# (exception, Cloudflare block, or FR24_EMPTY_TOLERANCE empty answers in a row
# while ADS-B sees aircraft) wait the next step. One probe per step, success resets.
FR24_BACKOFF_STEPS = [60, 300, 900]   # s: 1 min -> 5 min -> 15 min (stays at 15)
FR24_EMPTY_TOLERANCE = 2
# When every provider fails the last good flights stay on screen. After
# STALE_AFTER_SECONDS without fresh positions Overhead.stale turns True so a
# scene can show a hint. STALE_CLEAR_SECONDS > 0 would blank the board after
# that long instead (0 = never blank, the owner's preference).
STALE_AFTER_SECONDS = 120
STALE_CLEAR_SECONDS = 0
# Daily quotas and long cooldowns survive service restarts via this small file
# (written by the unprivileged display user, so it lives in /tmp).
RATE_STATE_FILE = "/tmp/vestor-ratelimit.json"
RATE_STATS_LOG_SECONDS = 900    # journal a per-provider request summary this often
MAX_FLIGHTS = 5                 # show the nearest N aircraft (cycled on the board)
USE_ZONE_BOX = True             # True = filter to ZONE_HOME box; False = full radius circle
JOURNEY_CODE_SELECTED = "BOS"   # bolds Logan as the local airport
JOURNEY_BLANK_FILLER = " ? "

# Demo mode: skip FlightRadar24 and cycle a fixed set of sample flights. Useful
# when the (free, rate-limited) FR24 feed is throttled or during a real lull, so
# the board still shows the flight card. Set False for live data.
DEMO_MODE = False

# Scene transitions: a big jet swoops across (new screen left of it, old to the
# right) on every flight<->flight / flight<->clock change. Set False for hard cuts.
TRANSITIONS_ENABLED = True

# ---- Display / hardware (Triple Bonnet, Pi 4) ----
BRIGHTNESS = 50                 # 0-100
GPIO_SLOWDOWN = 4               # Pi 4 starting value (try 5 on hardware if garbage)
HAT_PWM_ENABLED = False         # ignored now that hardware_mapping is hardcoded "regular"

# ---- Secrets from environment (never hardcode keys in this committed file) ----
OPENWEATHER_API_KEY = os.environ.get("OPENWEATHER_API_KEY", "")
MBTA_API_KEY = os.environ.get("MBTA_API_KEY", "")
