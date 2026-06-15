# Vestor LED Wall Simulator

A faithful, zero-dependency browser simulator for the Vestor LED matrix wall.
It renders both the **64×32 single panel** (Phase-0 flights-only bring-up) and
the full **1024×32 wall** (16 panels, 201.6") exactly as the real HUB75 phosphor
will glow — round emitters with dark inter-pixel gaps, gamma 2.2, additive bloom,
64-px panel seams, scanlines, and a vignette.

> Full design rationale: [`../docs/design/DESIGN.md`](../docs/design/DESIGN.md).
> Data sources: [`../docs/design/API_REFERENCE.md`](../docs/design/API_REFERENCE.md).

## Run it
```bash
# from this folder
python3 -m http.server 8765
# open http://localhost:8765
```
Or just double-click `index.html` — it runs from `file://` (classic `<script>`
tags, no build step, no npm).

## Controls
- **Scene mode** — `DASHBOARD` (zoned city dashboard) · `FLIGHT TAKEOVER` (whole
  ribbon = one giant flight strip + departures board) · `MARQUEE` (scrolling
  ticker).
- **Data source** — `SIMULATED` (synthetic, offline, default) · `LIVE APIS`
  (pulls **real** Open-Meteo weather and Bluebikes availability; falls back to
  simulated on any error).
- **Brightness**, **View** (1:1 pan / fit-width), **Auto-pan**.

## Architecture
| File | Role |
|---|---|
| `led.js` | `LEDMatrix` (linear-light framebuffer, authored 5×7 + 3×5 pixel fonts, icon sprites, drawing primitives) and `LEDRenderer` (the faithful optical pipeline) |
| `scenes.js` | Every scene + animated helpers: `SplitFlap` (Solari flips), `Marquee`, `drawRadar`, `gauge`, and `FlightScene` / `WeatherScene` (°C **and** °F) / `BluebikesScene` / `ShuttleScene` / `ClockScene` / `ExtrasScene` / `StatusEndcap` |
| `data.js` | `DataModel` — believable synthetic animation, plus an optional real-API overlay shaped identically to the live endpoints |
| `app.js` | Builds both matrices/renderers, lays out the three wall modes, runs the render loop, binds the console |
| `index.html` + `styles.css` | The "Departure Board Noir" showcase page |

## Fidelity notes
The renderer models emission, not rectangles:
`linear framebuffer → brightness → gamma 2.2 LUT → nearest upscale →
destination-in round-dot mask → additive bloom (2 passes) → scanline/seam/vignette
overlay`. The single panel renders at a chunky 14-px pitch; the wall at a 5-px
pitch so the full 5120-px-wide ribbon pans like a transit ticker.
