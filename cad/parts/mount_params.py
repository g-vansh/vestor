#!/usr/bin/env python3
"""
mount_params.py — the SINGLE source of truth for every mount part.

All parts (top_cleat, anti_swing_foot, seam_key, bars, corner_enclosure, …)
import these. Change a number here and every part + the assembly move together,
so the pieces cannot drift out of alignment. Dimensions in mm.

Coordinate frame (identical to cad/model.py):
    X = along the wall   (0 = far end .. 5118 = inside corner)
    Y = depth into room  (0 = main wall surface, +Y toward the viewer)
    Z = vertical         (0 = TOP of the wooden piece, −down)

Side view of the wall interface (what the parts clip into):

    Y=0        14   34        <- wall / groove / piece / room
    │  TOPgrv  │piece│
  Z=0 ┌────────┼─────┐ ── piece top ────────────────────────────
      │  (14)  │ 20  │   TOP groove: Y 0..14, Z 0..-50  OPEN TOP
  -50 ├────────┤     │   groove floor
      │ attach │     │   MIDDLE band solid Y 0..34, Z -50..-83
  -83 ├────────┤     │
      │ BOTgrv │     │   BOTTOM groove: Y -4..14, Z -83..-140 OPEN BOTTOM
 -140 └────────┴─────┘ ── piece bottom ─────────────────────────
"""

# ── wall interface (Y, depth) ───────────────────────────────────────────────
WALL_Y        = 0.0     # main wall surface
TOP_GRV_GAP   = 14.0    # top groove width (wall→piece back)
PIECE_BACK_Y  = 14.0    # piece back face
PIECE_FRONT_Y = 34.0    # piece front face (3.4 cm proud)
PIECE_THICK   = 20.0
WALL_STEP     = 4.0     # wall cut 4 mm deeper below the attach band
BOT_GRV_BACK_Y = -WALL_STEP   # -4  (bottom-groove back wall)
BOT_GRV_GAP    = PIECE_BACK_Y - BOT_GRV_BACK_Y   # 18 wide

# ── wall interface (Z, vertical; 0 = piece top) ─────────────────────────────
PIECE_TOP_Z   = 0.0
TOP_GRV_FLOOR = -50.0   # top groove is 50 mm deep, open at the top
ATTACH_TOP_Z  = -50.0
ATTACH_BOT_Z  = -83.0   # solid attach band -50..-83  (nothing may pass here)
BOT_GRV_TOP_Z = -83.0   # bottom groove closed top
PIECE_BOT_Z   = -140.0  # piece bottom == bottom groove open mouth

# ── the mount stackup (Y): cleat face → STEEL rail → magnet → panel ──────────
# CHANGED after the manufacturing-strategy research (docs/design/MANUFACTURING_PLAN.md):
# the rails are now STEEL, not aluminium + a glued steel strip. Steel IS the magnet
# target (bare low-carbon steel is what neodymium actually grips — a thin/coated/
# alloy strip silently loses 40-60% of hold), it is 3× stiffer than aluminium (holds
# the flat datum plane), and it expands half as much — so there is no separate strip
# to shear-peel. The rail front face IS the magnet contact plane.
FACE_Y      = 40.0      # front face of every wall bracket (6 mm proud of the piece)
RAIL_THK    = 5.0       # steel rail depth (Y) — bare low-carbon steel, the magnet target
RAIL_FRONT_Y = FACE_Y + RAIL_THK       # 45  (magnet contact plane)
STEEL_Y     = RAIL_FRONT_Y             # alias: the magnet contacts the rail front directly
MAGNET_STAND = 11.0     # magnet-screw protrusion off the panel back
PANEL_BACK_Y = RAIL_FRONT_Y + MAGNET_STAND  # 56
PANEL_D      = 15.0
PANEL_FACE_Y = PANEL_BACK_Y + PANEL_D  # 71 (~37 mm proud of the piece)
# back-compat alias (older parts referenced BAR_FRONT_Y / BAR_THK)
BAR_THK, BAR_FRONT_Y = RAIL_THK, RAIL_FRONT_Y

# ── panels / rows ───────────────────────────────────────────────────────────
PANEL_W, PANEL_H = 320.0, 160.0
N_PANELS = 16
ROW_W    = PANEL_W * N_PANELS          # 5120
WALL_USABLE = 5118.0
PANEL_TOP_Z, PANEL_BOT_Z = 0.0, -PANEL_H            # 0 .. -160
M3_TOP_Z, M3_BOT_Z = -8.0, -152.0      # the two M3 hole rows (144 mm pitch)
M3_V_PITCH = 144.0

# ── the two STEEL rails (run along X) ───────────────────────────────────────
# TOP rail = steel flat bar (magnet target only). BOTTOM rail = steel ANGLE: its
# vertical leg is the magnet target, its horizontal leg is a CONTINUOUS ledge every
# panel bottom RESTS on — so the magnets never carry the panel weight in shear (the
# validated fix), and it deletes the 16 separate printed rest-shoes.
BAR_H       = 26.0      # rail face height (Z) — tall enough for magnet-screw wiggle room
TOP_BAR_CZ  = M3_TOP_Z  # -8   (centred on the top M3 row)
BOT_BAR_CZ  = M3_BOT_Z  # -152 (centred on the bottom M3 row)
def bar_z(cz):          # (z0, z1) of a rail face centred at cz
    return (cz - BAR_H / 2, cz + BAR_H / 2)
LEDGE_Z     = PANEL_BOT_Z          # -160  bottom-angle ledge top (panel rests here)
LEDGE_THK   = 4.0                  # steel angle leg thickness
LEDGE_FRONT_Y = PANEL_BACK_Y + 8   # 64    ledge reaches under the panel back edge

# THERMAL: a 5 m steel rail grows ~1.2 mm per 20 °C. Anchor each rail to ONE central
# cleat only; every other cleat's rail-bolt hole is SLOTTED in X so the rail breathes.
# Leave a sub-mm gap at the row ends (hidden at the corner).

# ── stations (vertical lines of hardware) ───────────────────────────────────
N_STATIONS   = 13
STATION_PITCH = ROW_W / (N_STATIONS - 1)   # ≈ 427 mm (≤450 keeps rail sag invisible)
def station_x(i):
    return i * STATION_PITCH
ANCHOR_STATION = N_STATIONS // 2   # the one cleat that rigidly anchors the rails (rest slot)
FOOT_EVERY = 2          # an anti-swing tab at every 2nd station (~854 mm)

# ── fasteners ───────────────────────────────────────────────────────────────
M4_CLR = 4.5            # M4 clearance hole
M3_CLR = 3.4
BOLT_DX = 15.0          # bar-to-bracket bolt pair, ±X from station centre

# ── print material note ─────────────────────────────────────────────────────
# PLA now (over-built; every part is in compression or light shear). Reprint the
# load path (top_cleat) in PETG/ASA later — PLA creeps under sustained load.

if __name__ == "__main__":
    print(f"stackup Y: face {FACE_Y} → bar {BAR_FRONT_Y} → steel {STEEL_Y} → "
          f"panel back {PANEL_BACK_Y} → face {PANEL_FACE_Y}")
    print(f"bars: top z{bar_z(TOP_BAR_CZ)}  bottom z{bar_z(BOT_BAR_CZ)}")
    print(f"{N_STATIONS} stations @ {STATION_PITCH:.0f} mm; foot every {FOOT_EVERY}")
