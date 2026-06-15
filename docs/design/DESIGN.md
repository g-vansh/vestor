# VESTOR — Visual Design Document
### A 201-inch departure board for the sky over Cambridge

> Aesthetic codename: **"Departure Board Noir"** — sodium-amber phosphor on
> aviation-control black, the visual language of Solari split-flap boards,
> airport radar scopes, and 1970s flight-information displays, rebuilt pixel by
> pixel on real HUB75 LED hardware.

This document is the single source of truth for *what the wall shows and how it
looks*. The hardware/build truth lives in `docs/HARDWARE.md`, `docs/RUNBOOK.md`
and `AGENTS.md`; the API specifics live in `docs/design/API_REFERENCE.md`; the
projects/patterns survey lives in `docs/design/RESEARCH.md`.

A faithful, interactive **simulator** of everything described here lives in
[`/sim`](../../sim/) — open `sim/index.html` (or run the bundled static server)
to see both displays render exactly as the phosphor will glow.

---

## 1. The two displays

Vestor is **one panel geometry** driven in two configurations:

| | **Phase 0 — Single panel** | **Phase 1 — The Wall** |
|---|---|---|
| Pixels | **64 × 32** | **1024 × 32** |
| Panels | 1 | 16 (side by side) |
| Physical | 320 × 160 mm | **5120 mm = 201.6 in** wide |
| Wiring | data → Port 1 | 3 chains (6+5+5) on the Triple Bonnet |
| Content | **flights only** | full city dashboard |
| Purpose | hardware bring-up / tuning ladder | the finished installation |

The wall is deliberately an **ultra-wide 32:1 ribbon**, not a square. That aspect
ratio *is* the concept: it reads like the airport solari board / transit ticker
it's emulating, and it turns the awkward "16 panels of 64×32" into a single
narrative strip that the eye scans left-to-right.

### Electrical vs. logical geometry
hzeller's driver requires a uniform `chain_length`, so 3 chains of 6 give a
**384 × 96** physical canvas (3 rows of 6 panels). A **pixel-mapper** folds those
three stacked physical rows into the single **1024 × 32** logical ribbon the
scenes draw onto. The 6+5+5 split (16 panels, not 18) means the mapper leaves the
last two cells of chains 2 and 3 unpopulated — a Phase-1 engineering task tracked
in `docs/OPEN_QUESTIONS.md`. The simulator and all scene code already target the
final 1024×32 logical surface.

---

## 2. Aesthetic direction

A bold, committed point of view — not a generic dashboard.

### Palette (`PAL` in `sim/led.js`)
Authored in 0–255, stored as **linear light** so additive glow is physical.

| Token | Hex | Role |
|---|---|---|
| `amber` | `#FFB000` | **primary** — clocks, callsigns, headers (sodium-vapor) |
| `green` | `#39FF14` | phosphor — altitudes, "good", on-time |
| `cyan` | `#00E5FF` | data/labels, radar, routes, transit |
| `magenta`| `#FF2D95` | e-bikes, accents, alerts |
| `warm` | `#FFD678` | speeds, sun, secondary amber |
| `red` | `#FF3C32` | descending, delays, low-stock, LIVE dot |
| `blue` | `#5A96FF` | night, tide, cold |
| `purple` | `#AA78FF` | aircraft type chips, extras |
| `white` | `#EBF0FF` | emphasis only (used sparingly) |
| `ink` | `#08080C` | near-black substrate |

Dominant amber + sharp phosphor accents on black. Never a flat even palette;
never the AI-slop purple-on-white gradient.

### Typography
The simulator's *chrome* (the web page around the canvases) uses:
- **Major Mono Display** — the `vestor` wordmark (lowercase, mechanical)
- **Saira Condensed** — display headers (tall, transit-signage)
- **IBM Plex Mono** — all body/data text
- **Silkscreen** — pixel-perfect captions and labels

The *LED content itself* uses two **authored bitmap fonts** baked into
`led.js` — there are no system fonts on the matrix:
- **5×7** — the workhorse (uppercase, digits, punctuation, arrows, `°`, symbols)
- **3×5** — captions/labels where 5×7 is too tall (the ribbon is only 32px)

Both render at integer `scale` (1×, 2× …) so glyphs stay crisp on real pixels.

### Motion language
Motion is *diegetic* — it imitates real electromechanical/radar hardware:
- **Split-flap (Solari) flips** — `SplitFlap` advances each character cell
  mechanically through a charset toward its target, drawing a flap-seam line
  mid-glyph while turning. Used for callsigns and routes.
- **Radar sweep** — a rotating beam with a trailing afterglow; contacts brighten
  as the beam passes, then decay (pure additive deposition + the renderer's
  bloom = a real phosphor trail).
- **Seconds bar / sweeping fills** — smooth sub-pixel progress bars.
- **Marquee** — reserved for the low-priority ticker mode only (scrolling is
  costly to read; the dashboard prefers static zones + flips).
- **Pulses** — gentle sine-driven brightness on live/alert indicators.

---

## 3. The faithful LED optical model

The simulator is not "draw squares." `LEDRenderer` (in `sim/led.js`) reproduces
how a HUB75 panel actually emits, so the design is judged under real conditions:

1. **Linear framebuffer** — `Float32Array` of `cols×rows×3`, additive-capable.
2. **Brightness → gamma 2.2 LUT** → 8-bit per channel into a `cols×rows` texel
   buffer (one texel per LED).
3. **Nearest-neighbor upscale** to the display canvas → hard square pixels.
4. **Round-emitter mask** — a tiled radial-dot pattern composited `destination-in`
   carves every square into a round die with a dark inter-pixel gap, in **one**
   GPU pass for the whole 1024px ribbon (not 32k per-pixel draws).
5. **Additive bloom** — the dot image is downsampled, blurred and re-added
   `lighter` in two passes for the diffuser glow.
6. **Overlay** — faint horizontal scanlines, **64-px panel seams**, vignette.

Knobs: `pitch` (screen px/LED), `dot` (emitter diameter), `bloom`, `scan`,
`seam`, `vignette`, `brightness`. The wall renders at `pitch:5` (pannable like a
ticker); the single panel at `pitch:14` (chunky and tactile).

---

## 4. Wall zone map (1024 px)

All zone boundaries land on **64-px panel seams** so no element straddles a gap.

```
 px:  0      128     256                 576     704     832     960  1024
      |CLOCK |WEATHER|  FLIGHTS · RADAR  |BIKES  |SHUTTLE|EXTRAS |STAT|
      | DATE | °C/°F |  (hero, 5 panels) |       | MIT   |rotate |cap |
      |2 pnl |2 pnl  |                   |2 pnl  |2 pnl  |2 pnl  |1pnl|
```

| Zone | px | Source | Content |
|---|---|---|---|
| **Clock·Date** | 0–127 | system | Big `HH:MM` (2× flap-free), day, month/date, AM/PM, sweeping seconds bar |
| **Weather** | 128–255 | Open-Meteo | Condition icon + text, **°C and °F both**, hi/lo, humidity, wind vector, feels-like (both units) |
| **Flights** *(hero)* | 256–575 | ADS-B | Radar disc (live sweep + contacts), split-flap callsign, type chip, route codes + arc, origin/dest cities, FL/speed/vertical-rate, alt/spd gauges |
| **Bluebikes** | 576–703 | GBFS | Pacific St: **classic vs e-bike** counts (bike/bolt icons), free-dock bar |
| **MIT Shuttle** | 704–831 | Passio GTFS-rt | **Tech** + **Tech NW** arrivals at Grad Junction; "DUE" flash ≤1 min |
| **Extras** | 832–959 | mixed | Rotates every 6 s: ISS pass · MBTA Red Line · AQI · Moon phase · Charles tide |
| **Status** | 960–1023 | system | LIVE heartbeat, vertical `VESTOR` wordmark, data-freshness sparkline |

### Three wall modes
- **DASHBOARD** (default) — the zoned layout above; all domains at a glance.
- **FLIGHT TAKEOVER** — the whole ribbon becomes one giant flight strip: large
  radar, 2×-scale split-flap callsign, live stats, **plus a right-hand
  "departures board"** of the other contacts (airport-board aesthetic).
- **MARQUEE** — a static LIVE/VESTOR end-block + one 2×-scale scrolling ticker
  combining every domain; for glanceable, low-attention moments.

---

## 5. Scene specifications

Each scene is a stateful class in `sim/scenes.js` with
`draw(matrix, x, y, w, h, data, t, dt)`. The Python app mirrors these as scenes
under `scenes/` driven by the same data shapes.

### 5.1 Flight (hero) — `FlightScene`
The showpiece. Two layouts auto-selected by zone width:
- **Compact (64px / Phase 0):** full-width split-flap callsign banner, centered
  route, bottom-left mini-radar, bottom-right stacked `FL###` / `###KT` /
  climb-descent. This is exactly what the single-panel bring-up should show.
- **Wide (320px / wall):** left radar disc with "RADAR / N TRK" labels; split-flap
  callsign + type chip; `ORIG → DEST` with a pulsing **route arc**; origin/dest
  city subtitles; bottom data row `FL### · ###KT · ↑climb · ##.#MI` over two
  unlabeled progress **gauges** (altitude, ground speed).

Hero selection: nearest contact, weighted toward closer/arriving/international,
rotating every ~8 s. Radar contacts are mapped from each flight's bearing
(`track`) and range (`distance`).

### 5.2 Weather — `WeatherScene`  *(°C **and** °F — hard requirement)*
Condition icon (sun/moon/cloud/rain/snow by WMO code, day/night aware) + text;
large **`##°C`** (amber) stacked over **`##°F`** (green); right column hi / lo /
humidity / wind speed; an animated **wind-direction arrow**; a `FEEL ##°/##°`
ribbon (both units). °F is derived as `C×9/5+32` at render time.

### 5.3 Bluebikes — `BluebikesScene`
Header + "PACIFIC ST"; a **classic** row (bike icon, white, count) and an
**e-bike** row (lightning-bolt icon, magenta, count) — the two are always shown
separately per the brief; a bottom **free-dock bar** + count. Low-availability
(≤2 bikes) pulses the count red.

### 5.4 MIT Shuttle — `ShuttleScene`
Bus icon + "MIT SHUTTLE / GRAD JUNCTION"; two right-aligned ETA rows —
**TECH** (green) and **TECH NW** (cyan) — each showing the lead arrival in
minutes with the next two as faint ticks; **"DUE"** flashes when ≤1 min.

### 5.5 Clock·Date — `ClockScene`
2×-scale `HH:MM` (amber), sub-minute **seconds bar**, day-of-week (cyan),
month+date (white), AM/PM (green).

### 5.6 Extras — `ExtrasScene` (rotating, 5 cards)
ISS overhead/"LOOK UP!" with an orbiting dot · MBTA Red Line @ Kendall/MIT
(Alewife/Ashmont ETAs, red bullet) · Air Quality (color-graded value + bar) ·
Moon phase (lit-fraction disc) · Charles tide (rising/falling + animated wave).
Rotation dots indicate position; cards cross-fade.

### 5.7 Status end-cap — `StatusEndcap`
Pulsing LIVE dot, vertical `VESTOR` wordmark, an 8-bar data-freshness sparkline.

---

## 6. Data model & live data

`sim/data.js` (`DataModel`) holds state shaped **exactly** like the real API
responses and animates it believably (flights advance along track, ETAs count
down and recycle, bikes check in/out, weather drifts). Two modes:

- **SIM** (default) — fully synthetic, offline, deterministic-ish; ideal for the
  showcase and for design review.
- **LIVE** — opportunistically overlays **real** data from the CORS-friendly
  public endpoints (**Open-Meteo** weather, **Bluebikes GBFS**) and falls back to
  SIM on any failure, so the wall never goes dark. *(Verified live: Open-Meteo
  and GBFS both return 200 with CORS from the browser.)*

On the Pi, the Python clients fetch the same shapes from the same endpoints plus
the ones that need a server (Passio GTFS-realtime protobuf, ADS-B). See
`docs/design/API_REFERENCE.md` and `tools/` clients.

---

## 7. Design principles (the rules)

1. **Zone to the seam.** Every element fits inside a 64-px panel; nothing
   straddles a physical gap.
2. **Anchors + rotation.** Persistent zones for always-needed info (time,
   weather, flights); rotation only inside the Extras zone.
3. **Brightness is the budget.** Worst-case all-white ≈107% of the 120A supply,
   so `BRIGHTNESS≤50` caps it (~64A). The design leans on a *dark* substrate with
   sparse bright glyphs — which is also the noir aesthetic. Win-win.
4. **Color carries meaning.** Amber=identity/time, green=good/altitude,
   red=caution/descending, cyan=transit/data, magenta=e-bike.
5. **Motion must mean something.** Flips on data change, sweep for the radar,
   pulse for live/alert. No decoration-only animation on the matrix.
6. **Legibility first.** 5×7 for primary, 3×5 only where height forces it; never
   smaller. Right-align numerics; left-align labels.

---

## 8. Running the simulator

```bash
# from the repo root
cd sim && python3 -m http.server 8765
# open http://localhost:8765
```
Controls: **scene mode** (dashboard/takeover/marquee), **data source**
(simulated/live), **brightness**, **view** (1:1 pan / fit-width), **auto-pan**.
The page also renders the **64×32 Phase-0 panel** live and lists every data
source. No build step, no dependencies — classic `<script>` tags, runs from
`file://`.
