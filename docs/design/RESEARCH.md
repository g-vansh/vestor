# VESTOR — Research Findings
### Cool projects, design patterns, and what works on a 32-px-tall LED ribbon

Background research that shaped the design in `DESIGN.md`. Compiled 2026-06-15.

---

## 1. Projects surveyed (HUB75 / RGB-matrix dashboards)

What the hobbyist + maker community has built on exactly this hardware
(rpi-rgb-led-matrix + HUB75 panels), and the one idea worth stealing from each:

| Project / pattern | Steal-worthy idea |
|---|---|
| **its-a-plane-python** (our upstream, ColinWaddell) | Per-scene keyframe animation; FlightRadar24 nearest-plane focus; clean clock/date/weather rotation on a 64×32. Our flight scene extends its "one hero plane" idea with a **radar** + **split-flap**. |
| **TheFlightWall** (AxisNimble OSS + theflightwall.com) | The *cleanest* flight card we found: one flight, **framed**, three centered lines — **airline name in the brand colour + logo**, `From: <City>`, aircraft type. The wow is *legibility + branding*, not density. Drove our **carrier-led** rewrite: lead with the **real full-colour airline logo** (sourced from `pics.avs.io`) and show **city names** before codes. (OSS note "airline logo lookup added soon" — not in their OSS yet, and their product logos live in proprietary firmware; we found a free CORS-enabled CDN and built it.) |
| **Flightradar / "planes overhead" matrix builds** | A live **radar/compass sweep** with the home location at center reads instantly as "aircraft." We made it the flight zone's left anchor. |
| **MTA/transit countdown clocks & subway-sign clones** | Right-aligned **minutes-to-arrival** rows with a route **bullet**; the real signage flips between "now/2 min/4 min." Drives our shuttle + MBTA rows and the **split-flap** motion. |
| **Solari / split-flap board recreations** | The flip *is* the brand. Even a faked flip (cycling glyphs to target) sells "departure board." Implemented as `SplitFlap`. |
| **Weather-station matrix dashboards** | Icon + big number + a couple of micro-stats is the legible maximum on 32px; animated **wind arrow** and day/night icon swap add life cheaply. |
| **GBFS bike-share displays** | Show **availability split** (classic vs e-bike vs docks) as the headline number, not a map; a dock-fill bar communicates "can I return here." |
| **"Departures board for your city" art installs (ultra-wide)** | Ultra-wide ribbons want **zoned, anchored** content scanned L→R, with a single scrolling ticker reserved for overflow — not everything scrolling. |
| **ISS / satellite trackers** | A simple "overhead in N min / LOOK UP" beat is delightful and needs only one endpoint. |
| **Matrix "now playing" / sparkline status bars** | A thin **sparkline / heartbeat** end-cap signals "this thing is alive and fresh" — became the status zone. |

**Net takeaway:** the most-loved builds are *legible, anchored, and
motion-with-meaning*. The wow factor on a 201" ribbon comes from **one
unbroken narrative strip** + **diegetic motion** (flips, sweep), not from
cramming effects.

### Airline logos on a 32-px LED wall (resolved approach — REAL logos)

The reference product (theflightwall.com) shows **crisp, full-colour, real**
airline logos at panel height — not stylised marks. We chased the same look and
**found the source**, after an initial wrong turn into procedural marks (which
looked muddy and were rightly rejected).

- **Where the crisp logos come from → `pics.avs.io`.** The TravelPayouts /
  Aviasales CDN serves free, **CORS-enabled**, transparent-background PNG wordmark
  logos keyed by **IATA** code at any requested size
  (`https://pics.avs.io/<w>/<h>/<IATA>.png`). It returns the horizontal wordmark
  fitted to the requested width on a transparent canvas — ideal for a 1024-px-wide
  ribbon. (AirHex 403s without a paid key/hash; TheFlightWall's own
  `cdn.theflightwall.com` only serves *display names*, not logos — their product
  logos ship inside proprietary firmware.)
- **Real logos survive the optics — they don't become mush.** Downscaled with
  LANCZOS to LED-native height (≈20–26 px) and pushed through gamma 2.2 + round
  emitters + additive bloom, vector-derived wordmarks stay sharp and instantly
  legible: Southwest's gradient heart, United's globe, Delta's widget, the BA
  speedwing all read perfectly. (The thing that *did* turn to mush was hand-rolled
  procedural geometry — the opposite of what we first assumed.)
- **Transparent alpha keys cleanly against black.** Corners are `(0,0,0,0)`, so
  rendering is a straight alpha-over-black composite — no white-box matte to strip.
- **Decision → fetch once, render local.** `tools/fetch_logos.py` downloads ~64
  carriers, crops to content, stores 64-px masters in `sim/logos/`, and writes a
  manifest. The wall runs **fully offline** (no per-frame network, no taint, same
  pipeline usable on the Pi via Pillow). Mapped **ICAO→IATA** so the ADS-B feed
  resolves to a logo; brand colour still tints accents (hand-tuned for majors,
  sampled from the logo otherwise); no-logo carriers fall back to a brand
  wordmark. See `DESIGN.md` §5.1a.

---

## 2. Designing for a 32-pixel-tall surface

Hard constraints that drove concrete choices:

- **Vertical budget is brutal.** 32 px = at most **4 rows** of 5×7 text (with
  1px gaps) or **5 rows** of 3×5. Every scene is laid out on that grid:
  5×7 for primary values, 3×5 only where height forces it.
- **Zone to 64-px seams.** Real panels have a visible dark seam every 64 px.
  Straddling one cuts a glyph in half. All zone widths are multiples of 64 and
  all boundaries fall on seams.
- **Horizontal is the luxury dimension.** 1024 px wide is huge — use it for
  *whole words* (city names, "DEPARTING"), route arcs, and a real departures
  board, rather than squeezing vertically.
- **Right-align numbers, left-align labels.** Standard transit-board legibility;
  lets the eye lock onto the changing digits.
- **Dark substrate, sparse bright glyphs.** Both an aesthetic choice (noir) and
  a power choice (see §3).

---

## 3. Power & brightness reality (informs the visual budget)

From the hardware research (`docs/HARDWARE.md`):
- 16 panels at full white ≈ **107%** of the 2× LRS-350-5 (120 A) budget.
- `BRIGHTNESS = 50` caps worst case at ~64 A; typical dark-substrate content
  draws ~27–53%.
- **Design consequence:** a mostly-black board with bright accents isn't just the
  look — it's what keeps the install within its power envelope. The aesthetic and
  the electrical constraint point the same way.

---

## 4. Color semantics adopted

A consistent legend so the wall is readable at a glance from across a room:

- **Amber** — identity & time (wordmark, clock, callsigns, headers)
- **Green** — good / altitude / on-time / climbing
- **Red** — caution / delay / descending / low stock / LIVE
- **Cyan** — transit, data labels, radar, routes
- **Magenta** — e-bikes & special accents
- **Purple** — aircraft type / extras
- **Blue** — night, cold, tide
- **White** — emphasis only, used sparingly

---

## 5. Motion patterns adopted

| Pattern | Where | Why |
|---|---|---|
| Split-flap flip | callsign, route, big numbers | "departure board" signature; signals a data change |
| Radar sweep + decaying contacts | flight zone | instantly legible as aircraft; phosphor trail via additive bloom |
| Sweeping progress bar | seconds, dock-fill, gauges | smooth sub-pixel life without distraction |
| Pulse | LIVE dot, low-availability, "DUE" | draws the eye to the one urgent thing |
| Cross-fade | extras rotation | clean transitions between rotating cards |
| Marquee scroll | ticker mode only | reserved; scrolling is high-effort to read |

---

## 6. Open creative ideas (future scenes, not yet built)

Parked here so they aren't lost — candidates for the Extras rotation or future
modes:
- **Charles River sculling/regatta** activity or river temp.
- **MIT campus events / Stata "now open"** style status.
- **Sunrise/sunset golden-hour countdown** with a horizon gradient.
- **"On this day in aviation"** micro-trivia between flight foci.
- **Aurora / space-weather (Kp index)** alert when visible at this latitude.
- **A daily "wall woke up" boot animation** — staggered panel-by-panel power-on
  sweep across all 16 panels (great first-light moment for the install).
- **Sound-reactive** spectrum strip if a mic is ever added.

All of these fit the existing scene/zone framework and data-overlay model.
