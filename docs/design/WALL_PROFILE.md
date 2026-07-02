# Wall top profile — the mounting rail for the 16-panel wall

Source: hand sketch `IMG_3822` (2026-07-02), reference image
[`wall_top_profile.jpg`](wall_top_profile.jpg). Cross-section (side view) of the
top of the wall where it meets the ceiling. Geometry **owner-confirmed** after
three passes. All units **cm** (inches noted where useful).

## TL;DR — what this is

Running the **full width of the wall (~201 in / ~512 cm)** there is a
**structural WOODEN rail** at the top: a **2 cm-thick lip that stands 3.4 cm
proud of the wall**, with a **continuous open-top pocket (1.4 cm wide × 5 cm
deep) behind it**. It is load-bearing ("can hold a lot of weight"). This is,
in effect, a **built-in full-width cleat rail** — the natural anchor to **hang
the entire LED panel row from**, dropping a top cleat/tongue into the pocket from
above, with the lip's 3.4 cm projection giving standoff room behind the panels.

## Measured dimensions

| Label     | Value | Role |
|-----------|-------|------|
| `22.5 cm` | 22.5  | ceiling straight down to the **TOP of the piece** |
| `2 cm`    | 2.0   | **thickness** of the jutting wooden piece/lip |
| `14 cm`   | 14.0  | **height** of the piece (extends down from its top) |
| `~5 cm`   | ~4.8  | **depth (height) of the open-top pocket** behind the piece |
| `1.4 cm`  | 1.4   | **width of the pocket/groove** between the piece and the wall |
| `3.4 cm`  | 3.4   | **total projection** of the piece front off the wall (= 1.4 + 2) |

Full width (into the page): **~512 cm (~201 in)** — the pocket runs the whole way.

## Geometry (confirmed)

A **2 cm-thick wooden piece juts out from the wall and is anchored to the wall at
its BOTTOM**. Between the piece and the wall is a **groove/pocket that is 1.4 cm
wide, ~5 cm deep, OPEN AT THE TOP and closed at the bottom** — a slot you can
drop something into from above. There is **no recess cut into the wall itself**;
the groove *is* the space between the piece and the wall.

- Ceiling → top of piece: **22.5 cm**.
- The piece is **2 cm thick, 14 cm tall**, front face **3.4 cm proud** of the wall.
- Behind its **top ~5 cm** is the **open pocket** (1.4 cm wide, open from the
  top); the piece is **backed/anchored to the wall over its lower portion**, so
  the pocket bottoms out ~5 cm down and the piece joins the wall at its bottom.
- Sanity check that the read is right: **1.4 (pocket) + 2 (piece) = 3.4**.

```
   ceiling ─────────────────────────────
              │ 22.5 cm (to top of piece)
              ▼
        ┌──┐  ▲  ← pocket OPEN at top (1.4 wide × ~5 deep)
        │  │▒▒│     (▒ = the 1.4 cm slot; you drop a cleat in here)
        │  │██│  ← below ~5 cm: solid backing anchors piece to wall
        │  │██│
   piece│  │██│  14 cm tall, 2 cm thick, stands 3.4 cm proud
        └──┘██│
   ────────wall────────
```

## What it must carry — the LED panel row

Wall topology is **LOCKED**: one continuous **16-wide row**, center-fed as 2
chains of 8 (see `docs/HARDWARE.md`). So the row is:

- **16 × 320 mm = 5120 mm = 512 cm (~201.6 in) wide** — matches the rail width.
- **160 mm = 16 cm tall** (single panel high).
- Logical render **1024 × 32 px**.
- Weight: est. **~0.5 kg/panel → ~8 kg of panels**, plus the mounting
  frame/cleat (confirm panel weight against `INVENTORY.md`).

The row (16 cm tall) is ~2 cm taller than the lip (14 cm), so panels hung at the
lip roughly **cover and hide the wooden rail** — a clean look.

## Mounting concept (leading candidate — pending on-site checks)

The pocket is a full-width, upward-open mortise → treat it as a **cleat slot**:

1. Build a rigid **top hanging rail** for the panel assembly (e.g. an aluminium
   extrusion / wood bar spanning the width) with a **downward tongue ≤ 1.4 cm
   thick** that **drops into the pocket from the top**. Aim ~1.0–1.2 cm tongue
   for clearance; engage ~4 cm of the 5 cm depth.
2. Mount the panel frame to/below that rail. **Load path:** panels → hanging rail
   → tongue → pocket → structural wooden rail → wall. The wood is load-bearing,
   so this carries the ~10–13 kg assembly.
3. The lip holds everything **3.4 cm off the wall** → a natural **air gap behind
   the panels** for HUB75 ribbons + cabling.
4. Add a **bottom restraint** (spacer/standoff to the wall, or a lower fixing) so
   the row can't swing/tip out at the bottom.

**Mount the two LRS-350-5 PSUs SEPARATELY** (heavy, hot, need airflow) — on the
wall/shelf below or behind, NOT hung from the pocket.

## Constraints to respect

- **Tongue/cleat thickness ≤ 1.4 cm** (pocket width). Straight & flat over 512 cm
  (or segment it).
- **Pocket depth 5 cm** — usable engagement; don't rely on the full 5 cm.
- **Standoff = 3.4 cm** only. The **center Pi + Triple Bonnet stack** (est.
  ~4–5 cm deep) likely needs MORE than 3.4 cm → plan a recess, a deeper local
  standoff, or mount the Pi/bonnet just **below** the row.
- **Level/flatness** of the rail over 512 cm — shim the tongue as needed.
- Panels shouldn't **sag/bow** across 5 m → the hanging rail must be rigid.

## Still to confirm on-site

1. **Depth behind the lip** for the Pi + bonnet stack (measure the stack; 3.4 cm
   may be tight).
2. **Panel weight** (confirm ~0.5 kg each) → total hung load.
3. Pocket is **uniform/continuous** across the full 512 cm.
4. Wall material behind (drywall/studs) for the **bottom restraint** fixing.
5. Exact **ceiling-to-floor** context (how far below the ceiling we want the row;
   the pocket puts the top ~22.5 cm down — is that the desired height?).
