# FABRICATION — 3D-printed parts for the Vestor wall

Notes on how to fabricate the wall's mounting/enclosure parts now that the owner
has **MIT Hobby Shop** access. Companion to `docs/INVENTORY.md` §8 (the 80/20
metal backbone plan) and `docs/design/WALL_PROFILE.md` (the wooden mounting rail).
Written 2026-07-02.

## Machines available (Hobby Shop — confirmed with Charlotte Reiter, 2026-07-02)

Three printers (the Stratasys uPrint is **gone**):

| Printer | Tech | Build volume | Notes |
|---|---|---|---|
| **Bambu H2S** | FDM CoreXY | **340 × 320 × 340 mm** | 65 °C heated chamber, **350 °C** hotend, ~1000 mm/s. Runs PLA/**PETG/ABS/ASA/PC**/PA/PET/TPU + CF/GF. → our **large + structural + future-engineering-filament** workhorse. |
| **Bambu P2S** | FDM CoreXY | 256 × 256 × 256 mm | 300 °C nozzle, 110 °C bed, enclosed (no *active* chamber). → **small parts, parallel printing, everyday**. |
| **Formlabs Form 3L** | Resin SLA | 335 × 200 × 300 mm | 25 µm XY, 25–300 µm layers. → **small precision / cosmetic parts only**. |

**Materials:** **PLA is FREE** for students — but it's the **only filament stocked
right now** (more "coming soon"). **Resin is $0.25/mL** (assorted). Everything else
(machine time, PLA, tools, metal cutting/welding/waterjet) is free.

## Material strategy — the one real gotcha: **PLA creep**

Research is unambiguous: **PLA is the worst common filament for CREEP** — it slowly
deforms under *sustained* load, worse when warm (the textbook failure is "a printed
shelf bracket that sags over weeks"). PETG/ASA/ABS/PC resist creep far better; **PETG
is the standard choice for load-bearing printed mounts.** Our shop only has PLA today.

So the plan is deliberately **hybrid** — don't let creep-prone PLA solely carry the
permanent ~12–15 kg hang:

1. **Metal carries the sustained load.** The wall's own **structural wooden pocket**
   + a **metal spine (80/20 extrusion)** take the weight. Printed parts **locate and
   interface**; they are not the sole load path.
2. **PLA is fine NOW for:** panel alignment brackets, the Pi/bonnet enclosure, cable
   clips, jigs, spacers, and any low- or short-term-load part. PLA is free →
   **over-build** (thick walls, high infill, load oriented along the layers).
3. **Any part under real sustained load** (cleat hooks, PSU cradles): print in PLA
   now as a first install, then **reprint in PETG/ASA on the H2S** once the shop
   stocks it. Design so PLA loads in **compression/shear** (strong), never long-term
   bending/tension across layer lines.
4. **Anti-creep design rules:** short unsupported spans; ribs + fillets; spread load
   over a **large contact area**; orient layer lines with the principal load; keep
   parts **away from PSU heat** (PLA softens ~55–60 °C).

## Mounting architecture (updated by the wooden pocket)

> **The authoritative mount design is now `docs/design/MOUNT_DESIGN.md`** (clean-slate,
> 2026-07-02: wood tongue-cleat in the pocket + aluminum rails + printed adjustable
> clips). The summary below is consistent with it; the printed-parts BOM here still
> applies (clips, Pi/bonnet enclosure, PSU cradles, cable clips).

The full-width **structural wooden pocket** (1.4 cm × 5 cm, open-top, ~512 cm long —
see `WALL_PROFILE.md`) is a **built-in continuous cleat rail**. It **replaces the
lag-screws-into-studs / toggle-bolt wall anchoring** from `INVENTORY.md` §8, and being
*continuous* it lets us hang the spine at close intervals → the metal spine can be
**smaller/shallower** than the originally-specced 80/20 1530 (good, because the lip
only stands **34 mm** proud — depth is tight).

**Load path:** panel (M3 holes) → printed alignment bracket → **metal spine** →
printed **cleat hook (tongue in the wooden pocket)** → **wooden rail** → wall.
A printed **bottom spacer** holds the assembly's lower edge 34 mm off the wall so it
can't swing/tip (classic French-cleat behavior: top cleat bears load, bottom spacer
keeps it flush).

```
   ceiling
   ┌─pocket─┐   ← printed CLEAT HOOK tongue drops in here (≤1.4 cm, ~4 cm deep)
   │██wood██│
   │   ┌────┴──── metal spine (80/20 or lighter) ── printed panel brackets ── PANELS
   │   │  Pi+bonnet enclosure (center, behind/below)
   wall│  ...  bottom spacer (34 mm) keeps the row flush
```

## The printed BOM

Qtys are for the full 16-panel wall. "Later" = reprint in PETG/ASA when stocked.

| # | Part | Qty | Printer | Material (now → later) | Load | Notes |
|---|---|---|---|---|---|---|
| 1 | **Cleat hook** (spine-top → wooden pocket) | ~8–10 | H2S | PLA over-built → **PETG/ASA** | ~1.5 kg ea (compression on tongue) | Tongue ≤ 14 mm wide (target ~11), engage ~40 of the 50 mm pocket. Rest tongue on pocket floor so load is compression. Bolts to spine T-slot. |
| 2 | **Panel alignment bracket** (M3 holes ↔ spine) | ~32–48 | P2S (+H2S) | PLA | locating only | Screws into the panel's 6 M3 brass holes (4 corners + 2 mid-edge, ~144 mm pitch), bolts to spine. **Sets coplanarity + even gaps** across 16 panels. Adapt Printables 1294572 / 578204 to our pitch. |
| 3 | **Bottom spacer / anti-swing foot** | ~6–8 | P2S | PLA | low | 34 mm deep standoff to the wall along the bottom edge. |
| 4 | **Pi + Triple Bonnet enclosure/mount** | 1 | H2S | PLA → PETG | low | Center-back. **Depth-critical:** stack is ~40–50 mm; lip only gives 34 mm → recess it or **hang it just BELOW the row**. Vented. Parametric (CadQuery). |
| 5 | **PSU cradle** (LRS-350-5, ~215×115×30 mm, ~0.6 kg ea) | 2 | H2S | PLA → **PETG/ASA** | moderate + warm | Open/vented (PSUs run warm — PLA risk). Mount **separately** from the hung row (wall/shelf), PSU1 left half, PSU2 right half. |
| 6 | **Cable management** (HUB75 ribbon clips, power-wire routing, strain reliefs) | many | P2S | PLA | none | Print a batch; also a mount for the 8-way ATC fuse block. |
| 7 | **Spine splice alignment jig** (if spine is 2–3 sections) | 2 | P2S | PLA | none | Metal splice plates do the joint; print alignment aids so sections stay coplanar. |
| 8 | **Assembly jigs**: gap combs, cleat-position drill template | few | P2S / **Form 3L** | PLA / resin | none | Resin worth it here for precision (small volume, ~$5–10). |
| 9 | **(Optional) front bezel / seam trim, Vestor logo badge** | — | P2S / Form 3L | PLA / resin | none | Bezel over 5 m = FDM segments (resin too costly). Logo badge = resin (nice finish, ~$2). |

## Resin — when it's worth $0.25/mL

Reserve the Form 3L for **small precision or cosmetic** parts; FDM (free) does all
structure. Rough costs: logo badge ~5–10 mL → **~$1.25–2.50**; a gap-comb jig set
~20–40 mL → **~$5–10**; a detail bezel corner ~15–30 mL → **~$4–8 ea**. A structural
bracket in resin (~30–60 mL → $7.50–15) is **not** worth it — brittle *and* paid.
Budget **~$10–30** total for nice-to-haves; everything load-bearing stays free PLA/PETG.

## Parametric CAD workflow (Claude/Codex, per the owner's stack)

The owner drives CAD with Claude/Codex → generate **parametric code**, not one-shot
STLs. Proposed repo layout (new `cad/` dir), one folder per part:

```
cad/
  cleat_hook/      model.py (CadQuery/build123d)  exports/{step,stl,3mf}  checks/validate.py  README.md
  panel_bracket/   ...
  pi_enclosure/    ...
  psu_cradle/      ...
```

Rules for every part script: **all dims as top variables**; export **STEP (CAD edit)
+ STL/3MF (slice)**; print bounding box; assert min wall thickness; comment each
design choice; design for **FDM constraints** (overhangs ≤45°, no unsupported
bridges > ~10 mm, holes sized for M3). Validate mesh (trimesh/MeshLab) before
printing. Slice in **Bambu Studio** (H2S/P2S profiles); OrcaSlicer for tuning.

## Print-setting defaults (structural PLA, H2S/P2S)

- Walls: **4–6 perimeters**; infill **40–60 %** (gyroid); top/bottom 5 layers.
- Layer height 0.2 mm (0.28 for big non-cosmetic parts to save time).
- **Orientation = load along layers**, weak Z-axis never across the load.
- Brim on tall/small-footprint parts; PLA needs no chamber but the enclosed P2S/H2S
  reduce warp.
- For eventual PETG/ASA: H2S only (chamber); expect to re-tune supports/gaps.

## Measure on-site BEFORE printing finals

1. **Depth behind the 34 mm lip** — does the Pi+bonnet stack fit, or recess/relocate it?
2. **Panel weight** (confirm ~0.45 kg ea) → total hung load → cleat-hook count.
3. **Pocket uniformity** across the full 512 cm (width, depth, straightness).
4. **Ceiling-to-desired-row-height** (pocket puts the row top ~22.5 cm below ceiling).
5. Wall material at the bottom edge (for the anti-swing spacer fixing, if any).

## Next steps

- Confirm the 5 measurements above.
- Decide the metal spine (lighter than 1530 now that the pocket supports continuously).
- Then I can generate the parametric CAD (`cad/`) for parts #1–#6, starting with the
  **cleat hook** and **panel alignment bracket** (the two that gate everything).
