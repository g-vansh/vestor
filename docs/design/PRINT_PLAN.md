# VESTOR — PRINT PLAN (orientations · nesting · overnight batching)

How to get the whole part set printed in the **fewest overnight runs** using both free
Hobby-Shop printers. Nesting numbers are computed (`cad/parts/plate_plan.py`), not guessed.
Profile settings: see `PRINT_PROFILES.md`. Files: `cad/print_stl/` (already oriented).

**Optimization target:** filament and machine-hours are FREE and unlimited, and jobs can run
overnight. So we spend both freely to buy **strength and spare parts** — never to save time or plastic.

---

## 1. Orientations (already baked into the STLs — do NOT let Studio auto-orient)

| Part | Printed orientation | Why | Supports | Brim |
|---|---|---|---|---|
| `01_top_cleat` | **on its side** — profile flat on the bed, the 50 mm width up Z | constant-section prism → **zero supports**, and the ~1.5 kg hanging load runs **along** the layer lines, not across them (the difference between a PLA hook failing at ~15 kg vs holding 100+) | none | yes |
| `07_gap_hanger` | **on its side**, same logic | support-free + load along layers | none | yes |
| `02_foot_base` | channel + bolt holes vertical | holes print clean, no bridging | none | yes |
| `02_foot_tab` | 50 mm length flat on the bed | layers run along the tab's length | none | yes |
| `06_psu_cradle` | base down | retaining nubs bridge fine | none | no |
| `05_center_enclosure` | backplate flat | only part needing help | **light** | no |
| `08_cable_clip` | flat | trivial | none | no |

> Do not print the cleat or hanger standing upright to save bed space — that puts the layer
> lines **across** the load and is the one orientation that would actually make them fail.

## 2. What fits per plate (computed: 10 mm edge margin, 6 mm spacing, 5 mm brim where used)

| Part | Size (mm) | Need | +spares | **P2S** /plate | **H2S** /plate |
|---|---|---|---|---|---|
| `01_top_cleat` | 174×39×50 | 13 | **15** | 4 | **5** |
| `07_gap_hanger` | 246×70×40 | 6 | **7** | **✗ too big** | **3** |
| `02_foot_base` | 40×40×25 | 7 | 9 | 16 | 25 |
| `02_foot_tab` | 20×50×12 | 7 | 10 | 18 | 36 |
| `06_psu_cradle` | 34×72×37 | 4 | 5 | 18 | 28 |
| `05_center_enclosure` | 76×104×39 | 1 | 1 | 4 | 6 |
| `08_cable_clip` | 22×18×5 | 40 | 50 | 80 | 132 |

**The binding constraint:** the **gap hanger is 246 mm long — it does not fit the P2S at all**
(256 mm bed, no room for brim + edge exclusion). All 7 must run on the H2S, 3 per plate.
That single fact sets the whole schedule.

## 3. The batching plan — 3 overnight rounds, both printers in parallel

| Round | **H2S** (340×320) | **P2S** (256×256) |
|---|---|---|
| **1** | 3 × gap hanger | 4 × top cleat |
| **2** | 3 × gap hanger | 4 × top cleat |
| **3** | 1 × gap hanger **+ 4 × top cleat** | 3 × top cleat **+ all small parts** |

**Totals delivered:** 7 gap hangers ✓ · 15 top cleats ✓ (13 needed + 2 spares) · all feet, tabs,
cradles, enclosure, and 50 cable clips ✓.

**Why not 2 rounds?** 7 hangers ÷ 3 per H2S plate = 3 plates minimum, and the hangers can't be
offloaded to the P2S. Even dropping the spare hanger (6 → 2 plates) leaves ~7 cleats + all the
small parts undone. **3 is the floor.**

**How to compress it anyway:** the small-parts plate is quick (a few hours, not a full night).
If someone can swap a plate mid-day, run **small parts as a daytime job on day 1** and pull the
round-3 work forward — that can collapse this into **~2 nights + a daytime swap**.

## 4. Spares are free — print them
Included above: **+2 top cleats, +1 gap hanger, +2 foot bases, +3 foot tabs, +10 cable clips.**
Zero cost, and it means a bad fit or a cracked part never blocks the build. The cleats are the
load-bearing part — having 2 in reserve is the highest-value spare.

## 5. Practical notes
- **Trust Bambu Studio's time estimate** over any number here — hit *Slice* and read it.
- ⚠ **The "Vestor Structural" profile (`PRINT_PROFILES.md`) roughly DOUBLES print time** (near-solid
  parts, 0.16 mm layers). Expect a big structural plate ≈ **15–25 h**, not 10–15. So think in
  **~24 h plate cycles, not strict overnights** — start a plate in the evening, collect it the next
  afternoon. The 3 rounds above become **~3 days** with both printers running, which is still the floor.
- The small-parts plate uses **Vestor Light** and stays quick (≈ 4–6 h) — run it as a **daytime job**
  so it never consumes a cycle.
- If you want it faster, the **6 walls + 50 % grid** variant in `PRINT_PROFILES.md` gets ~85 % of the
  strength at ~65 % of the time.
- **Save each sliced plate as a `.3mf` to your USB** — the shop computer won't keep your profile
  between sessions, and you'll want to re-run a plate if a part fails.
- **Label parts as they come off** (masking tape) — the foot bases and tabs look similar.
- **Check the first layer** on every plate start; after that they're unattended.
- If a coupon fit came back off, **re-slice only the affected part** before committing a full plate.
