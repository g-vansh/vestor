# VESTOR MOUNT — MANUFACTURING PLAN (validated, least-labor)

The airtight answer to "what is the easiest way to actually build this mount, given
the MIT Hobby Shop." Backed by four parallel research passes (LED-wall best practice,
off-the-shelf hardware, makerspace process-labor, adversarial engineering validation),
each web-sourced and cross-checked. Companion: `MOUNT_PARTS.md` (the parts),
`SETUP_SPEC.md` (the wall). Established 2026-07-02.

## Bottom line

**Print the interface parts, saw-and-drill two steel rails, hang it in the grooves.**
No CNC, no waterjet, no laser for any part. Your hands-on time is roughly **one
slicing session + a few plate swaps + ~1 hr of sawing/drilling + a half-day install**
— the printers do the work unattended. The research changed the *design* (steel rails,
one-piece tall cleats, weight on a ledge) more than the *method* (printing was right).

---

## 1. Manufacturing method — why printing wins (and CNC/waterjet lose)

FDM 3D-printing the custom brackets is the least-*human-labor* route by a wide margin —
not because it's fast (it's slow in wall-clock) but because it's the only process where
**quantity is nearly free**: nest more parts on the plate, hit go, walk away. Zero
fixturing, no material sourcing (free PLA), near-zero post-processing.

| Process | Why it loses here |
|---|---|
| **Waterjet** | The cleat needs its width from **50 mm-thick plate** → cuts at ~2–4 ipm with taper, tabs you hand-grind, and small parts fall through the bed slats. Per-part manual labor printing doesn't have. |
| **CNC mill** | A 3-D hook needs **~3 re-clamped setups per part × ~13** + the steepest CAM learning curve in the shop. Worst fit. |
| **Laser** | Can't cut aluminum or 50 mm plastic; Hobby Shop isn't even confirmed to have one. |
| **Custom extrusion** | Die is **$750+ / 2–3 wk lead** — absurd for ~13 parts. |

The cleat's constant cross-section is a **printing** advantage, not a machining one:
lay it on its side (profile in the build plane, the 50 mm width up Z) → overhangs stay
support-free *and* the shear load runs **along** the layer lines (the orientation that
takes a PLA hook from failing at ~15 kg to holding 100+ kg).

*Sources:* [Hubs 3DP-vs-CNC](https://www.hubs.com/knowledge-base/3d-printing-vs-cnc-machining/) · [MakerStage](https://www.makerstage.com/resources/3d-printing-vs-cnc) · [TechniWaterjet speeds](https://www.techniwaterjet.com/waterjet-cutting-speed/) · [WARDJet tabbing](https://my.wardjet.com/news/tabbing-waterjet-parts) · [SUNLU PLA strength/orientation](https://store.sunlu.com/blogs/products-knowledge/is-pla-strong-enough-real-life-strength-tests-explained) · [Sovol 45° rule](https://sovol.eu/blogs/new/45-degree-rule-3d-printing-overhangs-support-quality-guide) · [MIT Hobby Shop equipment](https://mitadmissions.org/blogs/entry/machine_shops_part_1/) (waterjet/mills/saws confirmed; laser + CNC router NOT).

## 2. What the adversarial validation changed (the "airtight" fixes)

The load-bearing skeleton was already sound (the wood groove has **20–100× margin**;
PLA cleats run **4–5× under** the creep threshold). Every real risk was in the *panel
interface* — and each has a low-labor fix now baked into the CAD:

1. **🔴 Coplanarity (was a showstopper).** A "roughly straight" bar + dialing 96 magnet
   screws will *not* make P5 seams vanish (need ≤0.2–0.3 mm panel-to-panel); it just
   telegraphs the bar's waviness. The nearest real build to ours (Caudell's 48-panel
   jumbotron, magnet-screws-on-bars) ended up **cable-tying panels on**.
   **FIX:** make the rails a single flat **datum** you level once. The cleat is now a
   **tall C-bracket carrying BOTH rails on one rigid spine** → both rails coplanar by
   construction; you laser-level ~13 cleats (jackscrew + slotted holes), screws are trim.
2. **🟠 Magnet shear is marginal.** Friction is only ~15 % of pull force → 0.5–1.8×
   margin, decaying with heat/vibration/dust — the slow "walk down over months" failure.
   **FIX:** the **bottom rail is a steel angle whose horizontal leg is a continuous ledge
   every panel RESTS on.** Magnets then only hold the panel *flat*, never *up*.
3. **🟠 Thermal expansion.** A 5 m metal rail grows ~1–2.4 mm per 20 °C into a butt-tight
   row → bowed seams. **FIX:** anchor each rail to **one central cleat**, slot the other
   cleats' rail-holes, leave a sub-mm end gap (hidden at the corner). Steel (below) also
   halves the movement vs aluminium.
4. **🟢 Steel spec.** Magnets need **bare low-carbon steel ≥2–3 mm** — thin/coated/alloy
   silently loses 40–60 %. **FIX:** the rails ARE steel (see below), bare on the magnet face.
5. **🟢 PLA / wood / galvanic** all cleared: PLA fine if chunky + oriented (PETG/ASA a
   free upgrade near LED heat); wood groove 20–100× margin (leave 0.3–0.5 mm tongue
   clearance for humidity); galvanic a non-issue indoors.

*Sources:* [LED flatness](https://ledscreenfactory.com/understanding-led-screen-flatness-a-comprehensive-guide/) · [Caudell jumbotron (magnet failure)](https://medium.com/@phillipcaudell/how-to-build-your-very-own-large-led-jumbotron-video-wall-and-go-insane-in-the-process-8c515a49a07d) · [K&J magnet friction/shear](https://www.kjmagnetics.com/blog/leverage-and-friction-when-using-magnets) · [K&J steel thickness](https://www.kjmagnetics.com/blog/steel-thickness-and-magnetic-fields) · aluminium/steel CTE (material data) · [PLA creep](https://www.ryandynamics.com/insight/materialcreep/).

## 3. The one change that simplifies everything: steel rails, double duty

Replacing "aluminium bar + glued steel strip + 16 printed rest-shoes" with **two steel
rails** collapses several risks into one material choice:
- **Top rail** = steel **flat bar** (~26×5 mm) — the magnet target, 3× stiffer than
  aluminium (holds the flat datum), no adhesive strip to shear-peel.
- **Bottom rail** = steel **angle** (~30 × 24 × 4 mm) — vertical leg is the magnet target,
  **horizontal leg is the continuous rest ledge**. Deletes all 16 rest-shoes at once.

Weight goes from ~4 kg (alu) to ~16 kg (steel) — still **trivial** for the wood groove
(20–100× margin). The only real cost is heavier handling; the payoff is stiffness +
magnet grip + thermal + fewer parts.

## 4. The magnets decision — kept, with the fixes

The best-practice research leaned toward **bolting** panels' M3 straight to the rails
(most reliable, fewest parts). But bolting a wall-hugging panel has **no rear access**,
forcing face-down **section assembly** and losing tool-free service. Since you **own +
bench-tested the 100 magnet screws**, want tool-free front service, and the engineering
pass confirms **magnets are fine once the weight rests on the ledge and the rail is a flat
datum** — magnets stay. *Fallback if you'd trade tool-free service for the most bomb-proof
seams: bolt panels' M3 to the rails in 4-panel sections on a table, then hang the sections.*

## 5. Revised parts (was ~43 printed → now ~25)

| Part | Change | Qty |
|---|---|---|
| **Tall station cleat** | now carries BOTH rails (coplanarity datum) + jackscrew leveling | 13 |
| **Anti-swing foot** | pure anti-swing now; sits between cleats | ~7 |
| ~~Rest shoe~~ | **deleted** — the bottom-rail angle is a continuous ledge | 0 |
| **Rails** | **steel** (flat bar top + angle bottom); no separate strip | 2 (4 cuts) |
| Corner enclosure, PSU cradles | unchanged | 1 + 4 |

![cross-section](mount_assembly_section.png)
![3D](mount_assembly_3d.png)

*Verified by an automated boolean `collide_check()`: no bracket shares solid volume with
a panel, the piece, or another bracket.*

## 6. Your actual labor

**Make:** slice/nest the printed parts once → run unattended on the P2S/H2S (free PLA;
PETG/ASA for the cleats if you want zero creep worry) → saw the 4 rail pieces + drill
the bolt holes (~1 hr) → set M4 heat-set inserts in the cleats (~1 hr, soldering iron).

**Install:** drop the 13 cleats into the top groove (no wall holes) → **laser-line the
cleat faces + jackscrew/shim them flat to ≤0.3 mm** (the one precision hour — do this
before any panels) → bolt on the two rails (center-anchored, ends slotted) → wedge a few
anti-swing tabs into the bottom groove → screw the magnet feet into the panels → hang
panels from the corner, each **resting on the ledge + butting its neighbour**, magnets
snap to the steel. Set the 6 screws to a uniform depth with a simple gauge; the flat rail
does the coplanarity.

## 7. Bill of materials
- **Steel:** low-carbon (A36/1018) flat bar ~26×5 mm ×5.1 m + angle ~30×24×4 mm ×5.1 m
  (each in 2 cuts, spliced at the center cleat). Bare on the magnet faces; 430 stainless
  if corrosion is a worry. ~16 kg total.
- **Printed (free PLA / optional PETG-ASA):** 13 tall cleats, ~7 anti-swing feet + tabs,
  1 corner enclosure, 4 PSU cradles (~1 kg). No rest-shoes.
- **Fasteners:** M4 bolts + **M4 heat-set inserts** (2/rail × 13), M4 jackscrews + set-screws,
  M2.5 for the Pi. **Magnet feet:** 96 (own, have 100).

## 8. Still to confirm on-site (non-blocking)
- Real panel **M3 hole positions** (the −8 / −152 rows + column pitch).
- Groove widths **after finish** (tongue/tab clearances); uniformity over 5.1 m.
- Where the PSUs mount (wall vs corner shelf) for the cradles.
