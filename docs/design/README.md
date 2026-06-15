# VESTOR — Design & Simulation

This folder is the **design home** for the Vestor LED wall: what it shows, how it
looks, the data behind it, and the research that informed it. A fully interactive
**simulator** of everything here lives in [`/sim`](../../sim/).

## Contents
- **[DESIGN.md](DESIGN.md)** — the master visual design document: the two
  displays (64×32 Phase-0 and the 1024×32 wall), the "Departure Board Noir"
  aesthetic, palette, fonts, the faithful LED optical model, the wall zone map,
  per-scene specs, the three wall modes, and the design rules.
- **[API_REFERENCE.md](API_REFERENCE.md)** — every live data source with exact
  endpoints, station/route/stop IDs, field maps, and CORS/key notes.
- **[RESEARCH.md](RESEARCH.md)** — survey of cool HUB75/RGB-matrix projects and
  the 32-px-tall design patterns + power/color/motion findings that shaped the
  design.

## The simulator (`/sim`)
A faithful, dependency-free browser simulator that renders both displays exactly
as the phosphor will glow — round emitters, gamma 2.2, additive bloom, 64-px
panel seams, scanlines — driven by either synthetic or **real live** data.

```bash
cd sim && python3 -m http.server 8765   # then open http://localhost:8765
```
or just open `sim/index.html` directly (it runs from `file://`).

| File | Role |
|---|---|
| `sim/led.js` | `LEDMatrix` (framebuffer + 5×7/3×5 fonts + icons + primitives) and `LEDRenderer` (the optical model) |
| `sim/scenes.js` | All scenes + animated helpers (`SplitFlap`, `Marquee`, radar, gauges) |
| `sim/data.js` | `DataModel` — synthetic animation + optional live API overlay |
| `sim/app.js` | Wiring: builds both matrices, the 3 wall modes, the console |
| `sim/index.html` / `styles.css` | The showcase page |

**Controls:** scene mode (dashboard / flight-takeover / marquee), data source
(simulated / live), brightness, view (1:1 pan / fit-width), auto-pan.

## How this maps to the hardware
The simulator targets the final **1024×32 logical** surface. On the Pi, a
pixel-mapper folds the 3 physical chains (6+5+5 panels = 384×96) into that ribbon,
and Python scenes under `scenes/` render the same data shapes the simulator uses.
See `docs/HARDWARE.md` and `docs/RUNBOOK.md` for the build, and `tools/` for the
live data clients.
