# HARDWARE

- **Pi 4 Model B, 4GB** (not Pi 5 — hzeller driver maturity).
- **Adafruit Triple RGB Matrix Bonnet 6358** — active3, 3 chains, no onboard power, no soldering. Ports 1/2/3; Port 2 = center of 3 adjacent panels.
- **16× P5 HUB75** 64×32, ~320×160mm, 1/16 scan, **FM6124HJ** chip (a STANDARD driver — needs NO init string, unlike FM6126A/FM6127; A–D address, no E). **Brand CnGear, board rev JHT2.0, SMD2121** — verified off the physical boards 2026-06-29. Each panel has an **integrated plastic rear frame** (X-braced); **no magnets included** — mounting method TBD (see `INVENTORY.md` §6/§8).
- **Wall topology (LOCKED 2026-06-21): one continuous 16-wide row, fed from the CENTER as 2 chains of 8** (`--led-parallel=2 --led-chain=8`). Pi+bonnet center-mounted; chain 0 → left (panels 8→1, mounted 180°/SW-flipped), chain 1 → right (panels 9→16). Keeps every HUB75 ribbon <50cm. (Superseded the earlier 6+5+5 plan, which needed 1.9–3.5 m jump cables in a single row. See `docs/INVENTORY.md` §5.)
- **⚠️ Topology RECONSIDERED 2026-07-02 (corner electronics):** the Pi/bonnet now go in the room's **left corner** (the wall's grooved feature turns the corner). The corner is the row's left END, so the center-fed 2×8 would need a ~2.5 m bonnet ribbon (bad — HUB75 wants < ~50 cm). **CONFIRMED 2026-07-02: single chain of 16 from the corner** (`--led-chain=16 --led-parallel=1`), keeps every ribbon short + **removes the "snake"/180° left-half flip** in `display/__init__.py`. **Refresh MEASURED on the Pi** (slowdown=4): old 2×8 = 150 Hz @11-bit; 1×16 = 86 Hz @11-bit, **111 Hz @9-bit**, 123 Hz @8-bit, 139 Hz @7-bit. **Recommended: `pwm_bits=9` → 111 Hz** (flicker-free + 512 levels/ch, ample for text/logos/fades). Could raise further by dropping `gpio_slowdown` to 3/2 if signal stays clean (wall-time tune). Full analysis: `docs/design/MOUNT_DESIGN.md`.
- **Mount anchor (owner-confirmed 2026-07-02): a full-width STRUCTURAL WOODEN rail** at the top of the wall — a 2 cm lip standing **3.4 cm proud**, with a continuous **open-top pocket (1.4 cm wide × 5 cm deep)** running the full ~512 cm. Plan: hang the whole row from a **top cleat/tongue (≤1.4 cm) dropped into the pocket**; the 3.4 cm projection gives standoff behind the panels; PSUs mount separately. Full geometry, panel-row sizing, load path + constraints in **`docs/design/WALL_PROFILE.md`**. (Resolves the panel "mounting method TBD" above — the cleat replaces the need for magnets on the plastic rear frames.)
- **Power:** Mean Well **LRS-350-5** (5V/60A) ×2 for the wall (1 for Phase 0), fork-lug AC cord. Panels powered from PSU; **bonnet logic from Pi 5V GPIO rail**.
- **SD:** 64GB Verbatim microSDXC (V10/U1; image a backup once set up).
- **Flash host:** Mac + built-in SDXC + Raspberry Pi Imager.

## The 3 settings that matter
1. `--led-gpio-mapping=regular` (NOT adafruit-hat).
2. `--led-parallel=3` wall / `1` test; `--led-chain=6` wall / `1` test.
3. `--led-panel-type` **unset** — FM6124D is standard. Only if the panel stays BLACK, try `=FM6126A` then `=FM6127` as a fallback, then remove again.
