# ROADMAP — future scenes

## 🛡️ Always-on hardening (free software wins — see `docs/GEAR.md` §5)
For a wall-mounted, hard-to-reach Pi appliance. All zero-cost, work with our
Tailscale + systemd setup:
- **systemd hardware watchdog** — `RuntimeWatchdogSec=14s` in `/etc/systemd/system.conf`
  + `WatchdogSec=` on `vestor.service`. Auto-reboots a hung Pi / restarts a wedged loop.
  **Highest-leverage "dark wall" fix.**
- **Read-only root filesystem (overlayfs)** — `raspi-config` → Performance → Overlay FS.
  Brownout/power-loss tolerant SD (writes → RAM). Makes a Pi UPS HAT redundant (and the
  Triple Bonnet blocks GPIO HATs anyway).
- **Remote logging** (systemd-journal-remote over Tailscale) — debug a crash without
  SSHing into a wedged Pi once rootfs is read-only.
- **Remote power-cycle** — a Shelly/Kasa smart plug on the PSUs' AC, scripted over
  Tailscale, to reboot the whole wall remotely.

## ✨ Finishing — front face (see `docs/GEAR.md` §4)
Smoke-Gray #2064 tinted acrylic front (NOT a white diffuser): deepens blacks, cuts
daylight wash, hides panel shade variation. Prototype with one 2'×4' test sheet first.

## ⭐ Phase-1 prerequisite — the WALL COMPOSITION LAYER (biggest software task)
The on-Pi Python app is still the **upstream single-panel (64×32) flight tracker**.
The full multi-zone 1024×32 design (weather + planes + Bluebikes + shuttle, dual
°C/°F) currently lives **only in `sim/`** (the web simulator) — none of it is in the
Python render path yet. Porting it is the real Phase-1 software build, and the
**LOCKED 2×8 center-feed topology (INVENTORY §5)** dictates the shape:
- Render the logical scene at **1024×32**, then compose into a **512×64 hardware
  canvas**: chain 0 = left half, chain 1 = right half, with the **LEFT half rotated
  180°** (the snake). Do this in our own code in `display/__init__.py` — **NOT** an
  hzeller `pixel_mapper_config`.
- First-light bring-up can use the trivial `parallel=1 chain=16` (no rotation) to
  validate panels before switching to the 2×8 center-feed for the real install.
- Carry over from the sim: **dual °C/°F weather** (config is currently
  `TEMPERATURE_UNITS="imperial"` = single-unit; the wall renderer must show both),
  the zone layout, real airline logos, and the Bluebikes/shuttle scenes below.
- **Color uniformity is a design constraint, not a tunable:** hzeller is global-only
  (no per-panel LUT). Keep zone content dark/sparse (it already is) so panel-to-panel
  shade variation stays invisible. Per-panel correction = a C++ `MapColors()` fork —
  last resort only.

## Data sources (sim-proven, to wire into the wall renderer)
- **Weather:** NWS `api.weather.gov/points/42.354,-71.107` (User-Agent header, no key) or OpenWeatherMap (free key, upstream-native).
- **MBTA:** `api-v3.mbta.com` (free key; 20→1000 req/min). `/predictions?filter[stop]=place-cntsq&filter[route]=Red` (also `place-knncl`). Refs: dufus2506/MBTA-bus-train-stop-prediction-sign, TrevorSayre/led-matrix-mbta-signage.
- **Bluebikes:** GBFS (no key) `gbfs.bluebikes.com/gbfs/gbfs.json` → station_information + station_status; nearest One Memorial Drive + MIT-area Memorial Dr docks.
- **Local ADS-B:** RTL-SDR + dump1090 `aircraft.json`; consume via exxamalte/python-flightradar-client `Dump1090AircraftsFeed`. Kills cloud rate-limit/breakage risk.
- **Dashboard alt:** ChuckBuilds/LEDMatrix (native Triple Bonnet: regular/parallel=3/slowdown=4; weather/clock/calendar/sports/stocks). Never run two matrix processes at once.
