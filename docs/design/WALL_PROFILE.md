# Wall top profile — the mounting rail for the 16-panel wall

Source: hand sketch `IMG_3822` (2026-07-02), reference image
[`wall_top_profile.jpg`](wall_top_profile.jpg). Cross-section (side view) of the
top of the wall where it meets the ceiling. Geometry **owner-confirmed** after
three passes. All units **cm** (inches noted where useful).

## TL;DR — what this is

Running the **full width of the wall (~201 in / ~512 cm)** there is a
**structural WOODEN rail** at the top: a **2 cm-thick lip, 14 cm tall, standing
~3.4 cm proud of the wall**, attached to the wall only across its **middle ~3.3 cm**
— so behind it are **TWO grooves** (updated 2026-07-02):
- **TOP groove** — 1.4 cm wide × ~5 cm tall, **open at the TOP** (drop a cleat in
  from above; bottoms out ~5 cm down).
- **BOTTOM groove** — 1.8 cm wide × 5.7 cm tall, **open at the BOTTOM** (slide a
  tab up into it from below; closed at its top).

It's load-bearing ("holds a lot of weight"). In effect it's a **built-in full-width
mounting rail captured at BOTH ends** — **hang** the panel row's weight from the
top groove and **capture** its bottom in the bottom groove, so the whole assembly
is fully constrained **with zero new holes in the wall**.

## Measured dimensions

| Label     | Value | Role |
|-----------|-------|------|
| `22.5 cm` | 22.5  | ceiling straight down to the **TOP of the piece** |
| `2 cm`    | 2.0   | **thickness** of the jutting wooden piece/lip (uniform) |
| `14 cm`   | 14.0  | **height** of the piece |
| **TOP groove** | ~5 cm tall × 1.4 cm | behind the piece **top**, **open at the top** |
| **BOTTOM groove** | 5.7 cm tall × 1.8 cm | behind the piece **bottom**, **open at the bottom** |
| middle | ~3.3 cm | band where the piece **attaches to the wall** (14 − 5 − 5.7) |
| projection | 3.4 → 3.8 cm | piece front off the wall (top ≈ 1.4+2 = 3.4; bottom ≈ 1.8+2 = 3.8) |

Full width (into the page): **~512 cm (~201 in)** — both grooves run the whole way.

## Geometry (confirmed; bottom groove added 2026-07-02)

A **2 cm-thick wooden piece** juts ~3.4 cm out from the wall, **14 cm tall**,
attached to the wall only across its **middle ~3.3 cm**. There is **no recess cut
into the wall itself** — the grooves *are* the space between the piece and the
wall, at the top and bottom:

- **TOP groove** — between the piece top and the wall: **1.4 cm wide × ~5 cm tall,
  OPEN AT THE TOP** (drop a cleat/tongue in from above; it bottoms out ~5 cm down).
- **MIDDLE ~3.3 cm** — the piece **attaches to the wall** here.
- **BOTTOM groove** — between the piece bottom and the wall: **1.8 cm wide × 5.7 cm
  tall, OPEN AT THE BOTTOM** (slide a tab up into it from below; closed at its top).
- Ceiling → top of piece: **22.5 cm**. Heights: 5 + 3.3 + 5.7 = **14**.
- Projection: **≈3.4 cm at top (1.4+2), ≈3.8 cm at bottom (1.8+2)** — the piece
  sits a touch more proud at the bottom (the bottom gap is 0.4 cm deeper).

```
   ceiling ─────────────────────────────
              │ 22.5 cm (to top of piece)
              ▼   ↓ drop cleat in
        ┌──┐▒▒│  ← TOP groove  OPEN at top   (1.4 wide × ~5 tall)
        │  │▒▒│
        │  │██│  ← MIDDLE ~3.3 cm: piece attached to wall
        │  │▒▒│  ← BOTTOM groove OPEN at bottom (1.8 wide × 5.7 tall)
   piece│  │▒▒│
        └──┘▒▒│  ↑ push tab up
   ────────wall────────   (piece 14 cm tall, 2 cm thick, ~3.4 proud)
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
