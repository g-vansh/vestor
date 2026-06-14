# FlightWall

An RGB LED matrix that shows aircraft passing over Boston as seen from a window in
Cambridge, MA — built on a Raspberry Pi 4 + Adafruit Triple RGB Matrix Bonnet driving
16× P5 (64×32) HUB75 panels. Grows from a single-panel flight tracker into a
modular dashboard (flights → weather → MBTA → Bluebikes → animations).

**Derived from [its-a-plane-python](https://github.com/ColinWaddell/its-a-plane-python)
(GPL-3.0).** See `NOTICE.md`.

## For the build agent
Read **`AGENTS.md`** — it is the complete, autonomous build brief (hardware, git
workflow, secrets, architecture, runbook, troubleshooting, roadmap). Progress is
logged in `docs/BUILD_LOG.md`.

## Phases
- **0** — software installed + one panel renders live flights.
- **1** — full 16-panel wall (3 chains of 6+5+5).
- **2** — rotating multi-scene dashboard.

## Quick map
- `config.py` — geometry, ZONE_HOME, scene config (no secrets)
- `display/__init__.py` — matrix init (Triple Bonnet: `regular` / `parallel` / `FM6126A`)
- `scenes/`, `data/` — modular scenes and data clients
- `scripts/` — `flash_sd.sh`, `setup_pi.sh`, `install_app.sh`, `install_service.sh`
- `services/flightwall.service` — systemd unit
