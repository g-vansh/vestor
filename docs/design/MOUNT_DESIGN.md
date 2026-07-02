# MOUNT DESIGN — hanging the 16-panel wall (clean-slate, 2026-07-02)

**Supersedes `docs/INVENTORY.md` §8** (the old 80/20-into-studs plan, drawn before we
knew the wall has a built-in structural wooden pocket and before we had the MIT Hobby
Shop's full wood + metal + print capability). Reads with
`docs/design/WALL_PROFILE.md` (the wall) and `docs/FABRICATION.md` (the shop).

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
| **Anti-swing** | Continuous **bottom spacer/ledger** setting the standoff + holding the row flush | Wood strip (or printed feet) | Woodshop | The tongue is captured front-back by the pocket, so the bottom only needs *contact* — ideally **no new wall holes**. |
| **Brain** | **Pi + Triple Bonnet enclosure** | 3D-print | Printers | Mount **below** the row center (depth — see below). |
| **Power** | **PSU cradles** ×2 (LRS-350-5, ~215×115×30 mm, ~0.6 kg ea) | Printed or wood/metal, **vented** | any | Separate from the hung row; warm → keep airflow, keep off PLA later. |

**Load path:** panel → printed clip → aluminum rail → bracket → **wood tongue-cleat →
wooden pocket → wall**. Every sustained-load link is **metal or structural wood**;
prints only locate. No PLA in the permanent load path → no creep.

```
 ceiling
 ┌─pocket─┐  ½" birch tongue-cleat drops in (continuous, rests on 5cm floor)
 │██wood██│┐
 │  lip   ││ bracket
 │        │┴─ TOP aluminum rail ─────────────  ← panels clip here (top M3 row)
 │  (hidden│      [ open air: HUB75 + power behind panels ]
 │  behind ├─ BOTTOM aluminum rail ──────────  ← panels clip here (bottom M3 row, 144mm)
 wall  panels)  bottom spacer → wall (anti-swing, no holes)
            └─ Pi+bonnet enclosure hangs just below center
```

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

## The one thing that gates the geometry — MEASURE THIS

Everything above is robust regardless, but the **bracket/tongue geometry and whether
the Pi+bonnet fits behind vs below** hinge on one measurement:

> **How much clear depth is there from the wall face to the front of the pocket lip,
> and how does the pocket's height line up with where the panel row's top edge will
> sit?**

- The lip is **34 mm** proud; the Pi+Triple-Bonnet stack is **~40–50 mm** deep.
  - **If we keep panels ~flush with the lip (~34 mm standoff):** cleanest look, but the
    Pi/bonnet **mounts below the row** (good anyway — serviceable, cooler, ribbons drop
    down).
  - **If we stand panels ~55–60 mm off the wall:** the Pi/bonnet fits *behind* center,
    but the wall sticks out more.
  - **Recommended: panels ~flush, Pi/bonnet below.** Minimal protrusion, best service
    access, best thermals.

## Build sequence at the Hobby Shop

1. **Measure** the depth + pocket-to-panel-height relationship (above) + confirm panel
   weight + pocket uniformity across 512 cm.
2. **Woodshop:** rip the ½″ Baltic-birch **tongue-cleat** to fit the pocket (test-fit a
   short offcut first); make the bottom spacer/ledger.
3. **Metal shop:** cold-saw the **aluminum rails** to length (2× ~2.56 m per rail,
   top+bottom); prep T-slot splice plates for the center joints.
4. **Printers:** run the **adjustable panel clips** (batch), the **Pi/bonnet
   enclosure**, **PSU cradles**, and **cable clips** (parametric CAD — see FABRICATION).
5. **Assemble on the wall:** drop the tongue-cleat in → hang the top rail from it →
   **laser-level** the top rail → hang the bottom rail → mount panels **one at a time,
   left of center outward**, refining each seam and **re-checking flatness every 2–3
   panels** (research best practice) → set the bottom spacer → hang the Pi/bonnet →
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
