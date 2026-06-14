# HARDWARE

- **Pi 4 Model B, 4GB** (not Pi 5 — hzeller driver maturity).
- **Adafruit Triple RGB Matrix Bonnet 6358** — active3, 3 chains, no onboard power, no soldering. Ports 1/2/3; Port 2 = center of 3 adjacent panels.
- **16× P5 HUB75** 64×32, 320×160mm, 1/16 scan, **FM6124D** chip (FM6126A family). Wall = 3 chains of **6+5+5**.
- **Power:** Mean Well **LRS-350-5** (5V/60A) ×2 for the wall (1 for Phase 0), fork-lug AC cord. Panels powered from PSU; **bonnet logic from Pi 5V GPIO rail**.
- **SD:** 64GB Verbatim microSDXC (V10/U1; image a backup once set up).
- **Flash host:** Mac + built-in SDXC + Raspberry Pi Imager.

## The 3 settings that matter
1. `--led-gpio-mapping=regular` (NOT adafruit-hat).
2. `--led-parallel=3` wall / `1` test; `--led-chain=6` wall / `1` test.
3. `--led-panel-type=FM6126A` (FM6124 needs this init).
