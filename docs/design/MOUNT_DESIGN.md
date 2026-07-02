# MOUNT DESIGN — hanging the 16-panel wall (clean-slate, 2026-07-02)

**Supersedes `docs/INVENTORY.md` §8** (the old 80/20-into-studs plan, drawn before we
knew the wall has a built-in structural wooden pocket and before we had the MIT Hobby
Shop's full wood + metal + print capability). Reads with
`docs/design/WALL_PROFILE.md` (the wall) and `docs/FABRICATION.md` (the shop).

## ✅ LOCKED DECISIONS (2026-07-02)

1. **Hang from the wall's built-in grooves — zero new wall holes.** Wood **½″
   Baltic-birch top tongue-cleat** into the **top groove** (open-up, carries the load,
   ~10× margin) + a **bottom capture tab** up into the **bottom groove** (open-down,
   anti-swing). Fully captured top + bottom.
2. **Frame = two continuous horizontal aluminum rails** at the panel's 144 mm M3 rows
   (dead-flat reference). *(Can machine our own from bar/plate on the Hobby Shop mills/
   waterjet — no need to buy 80/20.)*
3. **Panels = MAGNETIC** (owner-tested OK): thin **steel strips** on the rails + the
   on-hand **magnet screws** (96/100) → snap-on, self-aligning, tool-free; **alignment
   pins** for lateral seams.
4. **Electronics in the LEFT CORNER** (the grooved piece turns the corner) — Pi + Triple
   Bonnet on the 3.4 cm ledge / hung. Resolves depth-behind-panels.
5. **Feed = SINGLE CHAIN OF 16 from the corner** (`--led-chain=16 --led-parallel=1`,
   **`pwm_bits=9` → measured 111 Hz**). All ribbons short; **removes the snake/180° flip**
   in `display/__init__.py`. (Supersedes the old 2×8 center-feed.)
6. **Power:** keep **PSU2 out near the right half** (short 5 V runs) — or a heavy 5 V bus
   if all-in-corner (keep drop < 3 %).
7. **Fit:** 16 × 320 = 5120 mm into **201.5″ (~5118 mm) usable → butt tight, no side
   frames.** Panels ~flush to the wall (connectors barely protrude).
8. **3D prints locate/interface only** (panel clips, Pi/PSU enclosures, cable clips) —
   PLA now, PETG/ASA later; nothing structural rides on PLA.

## ✅ DESIGN VALIDATED + REFINED (deep research, 2026-07-02)

Checked the locked plan against how commercial LED walls + architectural panels are
actually hung. It matches best practice on every axis; three refinements make it optimal.

**What the research says:**
- Coplanar seams come from a **rigid, precisely-LEVELED rail grid** + per-module
  **micro-adjustment in 3 axes** (esp. depth/Z). [LumenMatrix, ledlightsworld]
- Modules are **magnet-snapped** for quick positioning, then **alignment pins / micro-
  adjusters** refine seams; magnetic connectors cut install errors ~50%. [Radiant, MOUNT-IT]
- **Adjustable magnet HEIGHT is the standard coplanarity control** — exactly what our
  **thread-in magnet screws** provide (screw in/out = per-panel Z-adjust). [ledscreenparts]
- **6063-T5 aluminium** frames are the industry standard (dimensionally stable).
- **Continuous aluminium cleat / Z-clip rails** (6–12 ft lengths) are a proven concealed
  hanging system with easy **lateral adjustment** — validates a continuous cleat. [Eagle/Monarch/Orange Aluminum]
- Magnets are great for **indoor / light / low-vibration** + service; weaker than bolts in
  shear/vibration (irrelevant for 0.45 kg indoor panels) — but **rest the weight on the
  rail** so magnets only hold coplanarity, not shear.

**Three refinements to the locked plan:**
1. **Continuous cleat-tongue** in the top groove (not discrete cleats) → better load spread
   + easier leveling & lateral adjustment.
2. **Panel weight rests on the bottom rail / a lip** (magnets only hold it flat) → removes
   any long-term magnet-shear / creep worry.
3. **Alignment pins** between panels for lateral seam registration (magnets don't locate sideways).

**FINAL ARCHITECTURE (optimal, research-validated):**
1. Continuous **½″ Baltic-birch (or aluminium) tongue** in the TOP groove — the hang.
2. Two continuous **6063 aluminium rails** at the panels' 144 mm M3 rows → the flat reference
   grid, hung from the top groove + laser-leveled.
3. **Steel strips** on the rail faces → the ferrous plane for the magnets.
4. **Panels magnet-mount** via the on-hand magnet screws; **thread depth = per-panel Z
   coplanarity micro-adjust**; **alignment pins** set lateral seams; panel weight **rests on
   the bottom rail** (a lip), magnets hold it flat.
5. **Anti-swing tab** up into the BOTTOM groove (mid-height capture) → fully constrained,
   zero new wall holes.
6. **Corner electronics** hung from the LEFT-wall grooves (same tongue system).
7. **3D prints** = adjustable panel clips, alignment-pin holders, Pi/PSU enclosures, cable
   clips (PLA now → PETG/ASA later; nothing structural on PLA).

**Build sequence:** hang + **laser-level the rails on the wall** (using the top groove) →
populate panels **one at a time** (tool-free magnetic, self-coplanarizing via magnet-screw
depth + alignment pins), re-checking flatness every 2–3 → engage the bottom anti-swing tab →
hang the Pi/PSU in the corner → dress cabling. **Never handle a floppy 5 m assembly** (rails
first, then populate — the pro method).

## Design goals, in priority order

1. **Won't fall** (safety first).
2. **Dead flat / coplanar** across 5.12 m with tight, even seams — *this is the hard
   part; weight is trivial.*
3. **Reliable for years** — no creep, no sag, no humidity warp, no loosening.
4. **Serviceable** — pull one panel without dismantling the row.
5. **Minimal/zero new damage to the wall** — use what's already there.

## The key realization: the wall already gives us the hard part

The wall's **full-width structural wooden pocket** (1.4 cm × 5 cm, open-top —
`WALL_PROFILE.md`) is a **pre-built, continuous French cleat that's part of the
building**. Research: a wood French cleat holds **50–100+ kg**, and its reliability is
normally limited by *how well the cleat is anchored to studs* — here that's moot,
because the receiver is **solid structural wood spanning the whole width**. Our entire
assembly is **~10 kg**, so the hang has a **~5–10× safety margin** before we do
anything clever. **We hang from the pocket. Full stop.** No stud-hunting, no toggle
bolts, no lag screws — and the pocket supports *continuously*, which (per beam theory)
makes the frame go dead flat with a much lighter/shallower section than a stud-anchored
rail would need.

## Recommended architecture — "wood hangs it, metal keeps it flat, prints fine-tune it"

Each shop does what it's best at:

| Layer | Part | Material | Shop | Why |
|---|---|---|---|---|
| **Hang** | Continuous **tongue-cleat** dropped into the pocket (full width, ~2–3 sections) | **½″ (12 mm) Baltic-birch ply** | Woodshop (table saw / CNC) | Fits the 14 mm pocket; wood-on-wood; continuous compression into structural wood; the proven never-delaminate cleat material. |
| **Flatness** | Two continuous **horizontal rails** (top + bottom, ~144 mm apart = the panel's M3 hole rows), each in 2 halves (~2.56 m) spliced at center | **Aluminum T-slot (2020/2040)** | Metal shop (cold saw to length) | Dead straight + dimensionally stable (no humidity warp — the one knock on ply); T-slot = infinite micro-adjust, the pro requirement for coplanar seams. |
| **Registration** | **Adjustable panel clips** — bolt to the rail T-slot, screw into the panel's 6 M3 brass holes, with a screw for **depth (Z) micro-adjust** | **3D-print** (PLA now → PETG later) | Printers | Per-panel 3-axis fine-tune (research: micro-adjusters refine each seam); these *locate*, they don't bear sustained load → PLA-safe. |
| **Bottom capture** | **Tab that slides UP into the wall's BOTTOM groove** (1.8 cm × 5.7 cm, open at bottom) | ½″ birch or printed | Woodshop / printers | **UPDATE 2026-07-02:** the wall has a *second* groove open at the bottom → capture the frame's bottom in it (front-back + anti-swing) instead of a spacer-to-wall. Now the assembly is **fully captured at BOTH ends by built-in wall grooves → zero new holes.** |
| **Brain** | **Pi + Triple Bonnet enclosure** | 3D-print | Printers | Mount **below** the row center (depth — see below). |
| **Power** | **PSU cradles** ×2 (LRS-350-5, ~215×115×30 mm, ~0.6 kg ea) | Printed or wood/metal, **vented** | any | Separate from the hung row; warm → keep airflow, keep off PLA later. |

**Load path:** panel → printed clip → aluminum rail → bracket → **wood tongue-cleat →
wooden pocket → wall**. Every sustained-load link is **metal or structural wood**;
prints only locate. No PLA in the permanent load path → no creep.

```
 ceiling
 ▒▒│ TOP groove (open↓): ½" birch tongue-cleat drops in, rests on floor → HANGS load
 ██│ piece attached to wall (middle)
 ▒▒│ BOTTOM groove (open↑): tab slides up → CAPTURES bottom (anti-swing), no holes
 wall
        ┌─ TOP aluminum rail ────────────  ← panels clip/snap (top M3 row)
        │     [ open air: HUB75 + power behind panels ]
        └─ BOTTOM aluminum rail ─────────  ← panels (bottom M3 row, 144 mm)
              Pi+bonnet enclosure hangs just below center
```

## What the bottom groove changes (2026-07-02)

The wall has a **second groove, open at the bottom** (1.8 cm × 5.7 cm — see
`WALL_PROFILE.md`). This is a real upgrade:

- **Fully captured, zero new holes.** Before, only the top pocket carried load and
  the bottom needed a spacer/ledger (possibly a wall fixing). Now the frame is
  **captured at both ends by built-in wall grooves** — **top groove hangs the
  weight** (drop-in tongue rests on its floor), **bottom groove captures the bottom**
  (a tab slides up into it, locking it front-to-back and anti-swing). The wall does
  everything; we add **no holes at all.**
- **Rigidity.** Two engaged grooves 14 cm apart resist rocking/tip far better than a
  top hang + a contact spacer.
- **Assembly kinematics** (the one wrinkle): the **top groove opens up (drop in)** and
  the **bottom groove opens down (push up)** — opposite directions, so you can't
  translate a rigid frame into both at once. Plan: **hang the top tongue first** (bears
  the load), then engage the bottom with a **separate insertable/spring tab** pushed up
  into the bottom groove and locked. That tab is a small CAD part; the top hang alone
  is already safe (~10× margin), so the bottom tab is capture, not load.

## Magnet screws — the 100 on-hand feet (optional, good for service)

We have **100 magnet-screw feet** (they screw into the panels' M3 brass holes). Geometry
(owner-measured): they **jut 1.1 cm** off the panel back — **~0.8 cm of cylindrical
shaft**, then a **magnet base** (~0.3 cm) at the end. Magnets need **ferrous** metal
(they won't stick to wood/aluminum), so they only help if we **face the frame rails
with thin steel strips**.

- **If used (magnetic panel mount):** panels **snap on / pull off from the front**
  (tool-free service) and **self-align to the flat steel plane** (helps coplanarity in
  the depth axis). 6 magnets/panel × 16 = **96 (we have 100)**. Put the steel strips on
  the **flat aluminum rails** (aluminum sets flatness, steel gives the magnetic face),
  and add **alignment pins** for lateral (seam) registration — magnets don't locate
  sideways. **Watch the 1.1 cm standoff vs the panels' rear connectors** (~8–10 mm) —
  fine if the strips sit at the top/bottom **edge** rows (where there are no connectors;
  ribbons live in the open center).
- **If not used (magnet-free):** panels **screw** (M3) to printed brackets on the rails
  — positively located, no steel needed, but service = unscrew from behind (harder once
  wall-mounted).
- **CONFIRMED 2026-07-02 — GO MAGNETIC.** The owner tested it: the 1.1 cm standoff
  **clears the (barely-protruding) connectors + ribbons**, and the magnet hold is
  strong. So the plan is **magnetic panel mount**: thin **steel strips** on the flat
  aluminum rails + the on-hand magnet screws (6/panel × 16 = 96 of the 100) → panels
  **snap on/off from the front** (tool-free) and **self-align** to the flat steel plane.
  Add **alignment pins** for lateral (seam) registration. Magnet-free (M3 screws) stays
  a clean fallback but is no longer needed.
- **Note:** the magnets do **not** hang the panels from the wall (the grooves are wood,
  non-ferrous). They hold **panel → frame**; the **frame → wall** hang is the wood
  tongue in the grooves.

## The LEFT CORNER changes two electrical things (2026-07-02)

The Pi + Triple Bonnet now live **in the left corner** (see `WALL_PROFILE.md`) — great
for mounting/depth, but the corner is the **left END** of the row, which forces two
decisions (both researched):

### 1. Feed topology — recommend a SINGLE CHAIN OF 16 (supersedes the LOCKED 2×8)
- HUB75 is a high-speed parallel bus: **keep the bonnet→first-panel ribbon < ~50 cm**;
  long runs cause pixel corruption/noise. The old **center-fed 2×8** kept both feed
  ribbons short *because the bonnet was in the middle*. From the **corner**, a 2×8 split
  would need a **~2.5 m ribbon** to reach the middle panel (chain 1's start) — **not OK**.
- **A single chain of 16 from the corner keeps EVERY ribbon short** (bonnet→panel 1 at
  the corner, then short panel-to-panel daisy-chains 1→16). It's also **simpler in
  software** (direct 1024×32 map — **no "snake"/180° left-half flip**) and uses **1 of
  the bonnet's 3 ports** (2 spare).
- **Cost — MEASURED on the Pi (slowdown=4):** old 2×8 = **150 Hz** @11-bit color; 1×16 =
  **86 Hz** @11-bit, **111 Hz** @9-bit, 123 Hz @8-bit, 139 Hz @7-bit. All are flicker-free
  (eye fusion ~60–90 Hz; a monitor is 60–120 Hz). "Color depth" = brightness steps per R/G/B
  channel: 11-bit=2048, 8-bit=256 (= "true color", what a laptop screen shows). On flat
  text/logos the difference is **imperceptible** (only smooth photo gradients would band).
- **CONFIRMED: `--led-chain=16 --led-parallel=1`, `pwm_bits=9` → 111 Hz**, and **remove the
  snake/flip** in `display/__init__.py` (at wall-build). Could push higher by lowering
  `gpio_slowdown` (4→3/2) if the signal stays clean. Keep 2×8 only if max refresh mattered
  (it doesn't for a static board) — and it can't cleanly corner-mount anyway.

### 2. Power distribution — mind 5 V voltage drop over 5 m
- 5 V is drop-sensitive (**keep < ~3 % = 0.15 V**). The old plan put **PSU1 behind the
  left half, PSU2 behind the right half** so no 5 V run exceeds ~2.5 m.
- If **both** PSUs go in the corner, the right end is ~5 m away → big drop unless a
  **heavy 5 V bus** (thick gauge / copper bar, inject every ~1 panel).
- **Recommended:** Pi/bonnet in the corner; **keep PSU2 out near the right half** (short
  runs, less copper) — a PSU is just a box, it doesn't need to sit with the Pi. If you'd
  rather keep *everything* in the corner, run a **heavy 5 V bus + per-panel injection**
  and size it for < 3 % drop.

## Why this over the alternatives

- **vs the old 80/20-into-studs plan:** we no longer drill ~10 stud anchors; the
  pocket is a better (continuous, structural, zero-new-holes) anchor, and continuous
  support lets the rail be lighter/shallower — which matters because the lip only
  stands **34 mm** proud.
- **vs all-aluminum welded frame:** overkill for 10 kg; heavier; no aesthetic gain.
- **vs a solid plywood backer:** a solid sheet fights the panels' rear connectors and
  the depth budget; the open two-rail frame leaves the center free for cabling + the
  Pi/bonnet.

### Honest alternative — **all-Baltic-birch modular cassettes**

If you'd rather keep it **all-wood, all-in-house, and cheaper**: split the row into
**4 CNC-cut Baltic-birch cassettes of 4 panels** (~1.28 m each), each a ladder frame
(top+bottom rails + verticals) with the exact M3 hole grid + connector windows + a
tongue cut in one Laguna CNC job; hang each from the pocket; register cassette-to-
cassette with dowel pins. **Pros:** ~free (1 sheet or `reuse@mit`), matches the wooden
wall, modular = easy to carry/hang/service, short 1.28 m pieces stay flat. **Con:**
slightly less dimensionally stable than aluminum over years (humidity) — mitigated by
Baltic-birch quality, short spans, sealing, and the wall's continuous support. This is
a very strong option; I lead with the aluminum-rail version **only** because T-slot's
micro-adjust makes hitting perfect coplanarity easier. Either is reliable.

**My pick:** aluminum rails + wood cleat + printed clips for max flatness/adjustability;
switch the rails to Baltic-birch cassettes if you want zero-cost + all-in-house and
accept marginally less long-term stability.

## Measurements, checks & tests before CAD/build

The design is robust regardless, but these resolve the exact part geometry. Grouped:

### MEASURE (tape / calipers; do the wall ones at ~5 points across the 512 cm)
1. **Re-confirm the wall numbers + that they're UNIFORM full-width:** ceiling→top-of-piece
   (22.5), piece thickness (2), height (14), **top groove** width (1.4) + depth (5),
   **bottom groove** width (1.8) + height (5.7), middle attach (~3.3), wall step (0.4).
   Note the **usable** groove widths (a hair under, after paint/finish).
2. ~~Depth for the Pi behind the panels~~ — **RESOLVED 2026-07-02: the Pi/bonnet go in
   the LEFT CORNER, not behind the panels**, so no depth-behind constraint. (This was
   the only thing #2 needed; the flat cross-section already gives the wall geometry.)
   Still a *preference* to pick: the row's vertical position (top groove ≈ 22.5 cm below
   ceiling).
3. **Panel M3 hole grid:** vertical pitch (~144 mm) + horizontal column spacing + positions
   vs the panel edges. → the bracket/clip + steel-strip CAD.
4. **(nice-to-have)** confirm panel weight (~0.45 kg) — owner says light, so non-blocking.

### CHECK (eyes/hands)
6. **Continuity:** is the piece + both grooves **unbroken across all 512 cm** — any outlets,
   corners, nails, blobs, or spots where a groove is blocked/narrower?
7. **Groove interior:** clean wood a tongue slides freely in? Any paint/caulk narrowing it?
8. **Piece is solid + firmly attached** along its whole length (push/pull — does it flex?).

### TEST (5-minute, before committing parts)
9. **Tongue fit:** cut a short (~10 cm) scrap to the measured groove widths and **slide one
   into the top groove** (seats on the floor ~5 cm down, snug but free) and one into the
   **bottom groove** (1.8). Validates the cleat/tab thickness before making full-length ones.
10. ✅ **Magnet connector-clearance — PASSED (owner, 2026-07-02).** The 1.1 cm standoff
    clears the connectors/ribbons → **go magnetic.**
11. ✅ **Magnet hold — PASSED.** Firm hold (6/panel
    holds 0.45 kg with big margin, but confirm the magnets aren't weak).

## Build sequence at the Hobby Shop

1. **Measure** the depth + pocket-to-panel-height relationship (above) + confirm panel
   weight + pocket uniformity across 512 cm.
2. **Woodshop:** rip the ½″ Baltic-birch **top tongue-cleat** to fit the top groove
   (test-fit a short offcut first); make the **bottom capture tab** to fit the 1.8 cm
   bottom groove.
3. **Metal shop:** cold-saw the **aluminum rails** to length (2× ~2.56 m per rail,
   top+bottom); prep T-slot splice plates for the center joints.
4. **Printers:** run the **adjustable panel clips** (batch), the **Pi/bonnet
   enclosure**, **PSU cradles**, and **cable clips** (parametric CAD — see FABRICATION).
5. **Assemble on the wall:** drop the top tongue-cleat into the top groove → hang the
   top rail from it → **laser-level** → hang the bottom rail → **engage the bottom
   capture tab up into the bottom groove** and lock it → mount panels **one at a time,
   left of center outward** (snap-on if magnetic), refining each seam and **re-checking
   flatness every 2–3 panels** (research best practice) → hang the Pi/bonnet below →
   dress cabling → power up per chain.
6. **Maintenance:** re-check seam flatness + re-torque clips annually.

## Safety + longevity notes

- **~5–10× load margin** on the hang; verify nothing bears on PLA long-term (it
  doesn't, by design).
- **Heat:** panels run warm at `BRIGHTNESS ≤ 50`; keep PSUs **vented and separate**;
  don't tightly enclose electronics in wood/PLA.
- **Wall:** primary support is the built-in pocket → **no new structural holes**; the
  bottom spacer is contact-only if possible (a couple of discreet anchors optional as a
  belt-and-suspenders safety, TBD by wall material).

## Still open (small, non-blocking)
- Final rail material call (aluminum vs Baltic-birch cassettes) — after the depth
  measurement.
- Exact tongue-cleat ↔ rail bracket (a short CAD part) once depth is known.
- Whether to add a bottom safety fixing (depends on wall material below the lip).
