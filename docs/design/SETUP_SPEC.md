# VESTOR — FULL SETUP SPEC (authoritative)

The single source of truth for the physical build, owner-confirmed and validated in
CAD (`cad/model.py`; STEP: `cad/out/vestor_wall.step`). Build to these numbers.
Companions: `WALL_PROFILE.md`, `MOUNT_DESIGN.md`, `FABRICATION.md`, `../HARDWARE.md`.
All dims in **mm** (inches noted). Established 2026-07-02.

---

## 1. The room

Three walls: **FRONT** (the panels go here), **LEFT**, **RIGHT**. You walk in and
face the front wall. The **LEFT wall is a room corner on your left that comes toward
you**, and it is **built IDENTICALLY to the front wall** — same grooved wooden piece,
same top/bottom grooves. (The right wall is not used by the build.)

---

## 2. The grooved wooden piece (cross-section) — IDENTICAL on BOTH walls

A structural **wooden** piece runs the full length near the top of each wall. Profile
(side view; the piece is perfectly STRAIGHT / uniform):

```
   ceiling ───────────────────────────────
              │ 22.5 cm (225 mm) ceiling → TOP of the piece
              ▼   ↓ drop a cleat in
        ┌──┐▒▒│  ← TOP groove   OPEN at top    (1.4 cm wide × ~5 cm tall)
        │  │▒▒│
        │  │██│  ← MIDDLE ~3.3 cm: piece ATTACHED to the wall
        │  │▒▒│  ← BOTTOM groove OPEN at bottom (1.8 cm wide × 5.7 cm tall)
   piece│  │▒▒│
        └──┘▒▒│  ↑ push a tab up
   ───────wall────────  piece: 2 cm thick, 14 cm tall, 3.4 cm proud
```

| Feature | Value | Notes |
|---|---|---|
| Piece thickness | **2.0 cm** (20 mm) | uniform, straight |
| Piece height | **14 cm** (140 mm) | = 5 + 3.3 + 5.7 |
| Piece projection (proud of wall) | **3.4 cm** (34 mm) | = groove 1.4 + piece 2.0 |
| Ceiling → top of piece | **22.5 cm** (225 mm) | |
| **TOP groove** | **1.4 cm wide × ~5 cm tall** | behind the piece top; **OPEN AT THE TOP**, closed at its bottom |
| Middle attach band | **~3.3 cm** (33 mm) | piece joined to the wall here |
| **BOTTOM groove** | **1.8 cm wide × 5.7 cm tall** | behind the piece bottom; **OPEN AT THE BOTTOM**, closed at its top |
| Wall step | **0.4 cm** (4 mm) | the WALL is cut 0.4 cm deeper BELOW the attach band → that's why the bottom gap (1.8) > top gap (1.4). The piece does NOT tilt. |

The grooves are the **gap between the piece and the wall** (not cut into the wall).
Both grooves run the **full length** of each wall.

**Why the grooves matter (the whole mounting idea):** the TOP groove is a continuous,
structural, open-top slot → a **built-in French cleat**: drop a wood tongue in, it rests
on the slot floor and carries load in compression into solid wood (wood cleats hold
50–100+ kg; the whole assembly is ~10 kg → ~10× margin, zero new wall holes). The BOTTOM
groove (open at bottom) takes an anti-swing tab pushed up into it → the assembly is
**captured at both ends**. Both walls have this, so the corner electronics hang the same way.

---

## 3. The corner + the usable face (CRITICAL)

- **Usable panel FACE = 201.5 in (5118 mm)** — the flat mountable length, measured from
  the far end to the **INSIDE CORNER**.
- The **inside corner is where the LEFT wall's proud piece BEGINS**, NOT the left wall's
  back surface. The panels **stop there**.
- Because the left wall's piece juts **34 mm** into the room (identical build), the left
  wall's **SURFACE sits 34 mm BEHIND the inside corner** (`left-wall surface = corner + 34`).
- So the panels butt the corner cleanly; the left piece front lands exactly where the last
  panel ends — **no overlap**.

---

## 4. The panels

- **16× CnGear P5 HUB75**, 64×32 px, **~320 × 158 × 15 mm**, **~0.45 kg** each, FM6124HJ
  (standard driver, no init string).
- **6× M3 brass threaded holes** per panel: a **3-col × 2-row grid**, top+bottom rows
  ~**144 mm** apart (vertical). These are the mount points.
- Rear HUB75 connectors + ribbons **barely protrude**.
- Row = **16 × 320 = 5120 mm** wide × **160 mm** tall (one panel high) → logical **1024×32 px**.
- Fit: 5120 mm into 5118 mm usable → **+2 mm, which overshoots the FAR end** (never the
  corner). Butt panels tight; no width-adding side frames.

---

## 5. Mount — LOCKED (rationale in `MOUNT_DESIGN.md`; **buildable parts in `MOUNT_PARTS.md`**)

1. **Hang from the wall's built-in grooves — zero new holes.** A **½″ (12 mm) Baltic-birch
   tongue-cleat** drops into the **TOP groove** (carries the load) + a **tab** up into the
   **BOTTOM groove** (anti-swing). Captured top + bottom.
2. **Frame = two continuous aluminium rails** at the panels' 144 mm M3 rows (dead-flat
   reference). Can be machined in-house (Hobby Shop mills/waterjet) — no need to buy 80/20.
3. **Panels = MAGNETIC** (owner-tested OK): thin **steel strips** on the rails + the on-hand
   **magnet screws** → panels snap on/off from the front, self-align; add alignment pins for
   lateral seams. Magnet-free (M3 screws) is the fallback.
4. **Electronics in the LEFT CORNER**, hung from the LEFT wall's grooves (same tongue system).
5. **3D prints locate/interface only** (panel clips, Pi/PSU enclosures, cable clips) — PLA
   now (over-built), reprint load-bearing parts in **PETG/ASA** on the H2S when stocked.
   Nothing structural rides on PLA (it creeps).

---

## 6. Electrical — LOCKED

- **Feed: single chain of 16 from the corner** — `--led-chain=16 --led-parallel=1`,
  **`pwm_bits=9` → measured 111 Hz** on the Pi (flicker-free for this static-ish board).
  All HUB75 ribbons stay short; **removes the "snake"/180° left-half flip** in
  `display/__init__.py`. (Supersedes the old 2×8 center-feed.)
- **Power: 2× Mean Well LRS-350-5** (5 V/60 A). Keep 5 V drop < 3 %: put **PSU2 out near
  the right half** (short runs), OR a heavy 5 V bus if all-in-corner. `BRIGHTNESS ≤ 50`.
- **Brain: Raspberry Pi 4 + Adafruit Triple RGB Matrix Bonnet 6358** (regular mapping),
  in the corner.

---

## 7. Materials on hand / hardware

- **100 magnet-screw feet** — screw into the panels' M3 holes; jut **1.1 cm** off the panel
  back (**~0.8 cm cylindrical shaft** + a **magnet base** ~0.3 cm). Need a **ferrous** face
  to stick (→ steel strips on the rails). 6/panel × 16 = 96 used.
- 2× LRS-350-5 PSUs; AC cords; 8-way ATC fuse blocks; fork terminals (see `INVENTORY.md`).
- Wood/metal/print stock free at the MIT Hobby Shop (`MIT_RESOURCES.md`).

---

## 8. CAD

`cad/model.py` — parametric box model (dims match this spec). Coords: **X = along the wall
(0 = far end .. 5118 = inside corner)**, **Y = depth into room** (0 = wall surface, +Y toward
viewer), **Z = vertical** (0 = piece top, −down). `render_pv.py` (shaded 3D), `render_mpl.py`
(ortho + cross-section), `export_step.py` → `cad/out/vestor_wall.step` for **Fusion 360**.
Toolchain: Python 3.12 venv (CadQuery/PyVista) — see `cad/README.md`.

---

## 9. Still to confirm on-site (non-blocking)

- Groove dims **uniform across the full 512 cm** (measure at ~5 points); **usable** groove
  widths after paint/finish; grooves unbroken (no outlets/nails).
- Panel **M3 hole positions** (column spacing + edge offsets) from a real panel — the one
  estimate in the CAD.
- Depth of room behind the corner for the electronics; wall material for any bottom fixing.
- Exact usable face length (if a hair under 5118, the shortfall shows at the FAR end).
