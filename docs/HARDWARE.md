# HARDWARE

- **Pi 4 Model B, 4GB** (not Pi 5 — hzeller driver maturity).
- **Adafruit Triple RGB Matrix Bonnet 6358** — active3, 3 chains, no onboard power, no soldering. Ports 1/2/3; Port 2 = center of 3 adjacent panels.
- **16× P5 HUB75** 64×32, 320×160mm, 1/16 scan, **FM6124(Z)D** chip (a STANDARD driver — needs NO init string, unlike FM6126A/FM6127; A–D address, no E). MUEN/Epstar LEDs.
- **Wall topology (LOCKED 2026-06-21): one continuous 16-wide row, fed from the CENTER as 2 chains of 8** (`--led-parallel=2 --led-chain=8`). Pi+bonnet center-mounted; chain 0 → left (panels 8→1, mounted 180°/SW-flipped), chain 1 → right (panels 9→16). Keeps every HUB75 ribbon <50cm. (Superseded the earlier 6+5+5 plan, which needed 1.9–3.5 m jump cables in a single row. See `docs/INVENTORY.md` §5.)
- **Power:** Mean Well **LRS-350-5** (5V/60A) ×2 for the wall (1 for Phase 0), fork-lug AC cord. Panels powered from PSU; **bonnet logic from Pi 5V GPIO rail**.
- **SD:** 64GB Verbatim microSDXC (V10/U1; image a backup once set up).
- **Flash host:** Mac + built-in SDXC + Raspberry Pi Imager.

## The 3 settings that matter
1. `--led-gpio-mapping=regular` (NOT adafruit-hat).
2. `--led-parallel=3` wall / `1` test; `--led-chain=6` wall / `1` test.
3. `--led-panel-type` **unset** — FM6124D is standard. Only if the panel stays BLACK, try `=FM6126A` then `=FM6127` as a fallback, then remove again.
