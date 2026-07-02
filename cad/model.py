#!/usr/bin/env python3
"""
model.py — parametric CAD of the Vestor wall + panels + mount, for a virtual
fit-check. All dimensions (mm) come from docs/design/WALL_PROFILE.md +
MOUNT_DESIGN.md. Builds the geometry, exports a combined STEP (open in Fusion 360)
and per-part STL (for the PyVista renders in render.py).

Coordinate system:
  X = along the wall  (0 = left corner .. 5120 = right end)
  Y = depth into the room (0 = main wall surface, +Y toward viewer)
  Z = vertical (0 = TOP of the wooden piece; +Z up, -Z down)
"""
import os
import cadquery as cq

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

# ---- WALL FEATURE (from WALL_PROFILE.md) -----------------------------------
PIECE_THICK   = 20     # 2.0 cm (uniform, straight)
PIECE_HEIGHT  = 140    # 14 cm
PIECE_PROUD   = 34     # 3.4 cm front off the main wall
CEIL_TO_TOP   = 225    # 22.5 cm ceiling -> top of piece
TOP_GRV_H     = 50     # 5 cm  (open at TOP)
TOP_GRV_GAP   = 14     # 1.4 cm (piece back -> wall)
BOT_GRV_H     = 57     # 5.7 cm (open at BOTTOM)
BOT_GRV_GAP   = 18     # 1.8 cm
WALL_STEP     = 4      # wall cut 0.4 cm deeper below the attachment
MID_H         = PIECE_HEIGHT - TOP_GRV_H - BOT_GRV_H   # 33 mm attach band

# derived depths (Y): main wall surface at Y=0; piece back at Y=TOP_GRV_GAP=14;
# piece front at Y=34 (= 14+20 = proud). bottom-groove wall recedes to Y=-4.
PIECE_BACK  = TOP_GRV_GAP          # 14
PIECE_FRONT = PIECE_PROUD          # 34
Z_TOP       = 0.0
Z_BOT       = -PIECE_HEIGHT        # -140
Z_MID_TOP   = -TOP_GRV_H           # -50
Z_MID_BOT   = -(TOP_GRV_H + MID_H) # -83

# ---- PANELS ----------------------------------------------------------------
PANEL_W, PANEL_H, PANEL_D = 320.0, 160.0, 15.0
N_PANELS = 16
ROW_W = PANEL_W * N_PANELS          # 5120
WALL_USABLE = 5118.0                # 201.5"
PANEL_BACK_Y = PIECE_FRONT          # panels ~flush to the piece front (34)
PANEL_FACE_Y = PANEL_BACK_Y + PANEL_D
M3_V_PITCH = 144.0                  # top row -> bottom row
M3_TOP_Z = -8.0                     # top hole row (est. ~8 mm below panel top)
M3_BOT_Z = M3_TOP_Z - M3_V_PITCH    # -152
M3_COLS_X = (28.0, PANEL_W/2, PANEL_W - 28.0)   # est. L / C / R columns

# ---- MOUNT (MOUNT_DESIGN.md) -----------------------------------------------
TONGUE_THICK = 12      # 1/2" birch cleat, fits the 14 mm top groove
TONGUE_ENGAGE = 40     # of the 50 mm top-groove depth
TAB_THICK = 16         # fits the 18 mm bottom groove
TAB_ENGAGE = 40
RAIL = 20              # 20x20 aluminum rail


def box(x0, x1, y0, y1, z0, z1):
    """Axis-aligned box by min/max corners."""
    return (cq.Workplane("XY")
            .box(x1 - x0, y1 - y0, z1 - z0, centered=(False, False, False))
            .translate((x0, y0, z0)))


def wall_feature(length=ROW_W):
    """The wooden wall + straight piece + the two grooves (as air gaps)."""
    wall = box(0, length, -120, 0, Z_TOP - 260, CEIL_TO_TOP + 20)
    # the wall is cut 0.4 cm deeper behind the bottom groove
    wall = wall.cut(box(0, length, -WALL_STEP, 0, Z_BOT, Z_MID_BOT))
    piece = box(0, length, PIECE_BACK, PIECE_FRONT, Z_BOT, Z_TOP)
    bridge = box(0, length, 0, PIECE_BACK, Z_MID_BOT, Z_MID_TOP)   # middle attach
    return wall.union(piece).union(bridge)


def panels():
    """16 panels butted in a row, faces toward the room."""
    parts = []
    for i in range(N_PANELS):
        x0 = i * PANEL_W
        parts.append(box(x0 + 0.4, x0 + PANEL_W - 0.4,   # 0.8 mm visual seam
                         PANEL_BACK_Y, PANEL_FACE_Y,
                         -PANEL_H, 0))
    return parts


def tongue_cleat(length=ROW_W):
    """½" birch cleat dropped into the TOP groove (rests on its floor)."""
    y0 = (PIECE_BACK - TONGUE_THICK) - 0   # snug at the back of the groove
    return box(0, length, y0, y0 + TONGUE_THICK, Z_TOP - TONGUE_ENGAGE, Z_TOP)


def bottom_tab(length=ROW_W):
    """Tab pushed UP into the BOTTOM groove (anti-swing)."""
    y0 = -WALL_STEP + 1
    return box(0, length, y0, y0 + TAB_THICK, Z_BOT, Z_BOT + TAB_ENGAGE)


def rails(length=ROW_W):
    """Two aluminum rails at the panel M3 hole rows (behind the panels)."""
    y0, y1 = PANEL_BACK_Y - RAIL, PANEL_BACK_Y   # just behind the panels
    top = box(0, length, y0, y1, M3_TOP_Z - RAIL/2, M3_TOP_Z + RAIL/2)
    bot = box(0, length, y0, y1, M3_BOT_Z - RAIL/2, M3_BOT_Z + RAIL/2)
    return top.union(bot)


def corner_and_electronics():
    """The left return wall + Pi/bonnet + a PSU sitting in the corner."""
    # perpendicular return wall at the left (plane X=0), extending into the room
    ret_wall = box(-120, 0, -120, 360, Z_TOP - 260, CEIL_TO_TOP + 20)
    # its piece/ledge (the groove turns the corner) — a 3.4 cm-proud ledge
    ret_piece = box(-PIECE_FRONT, 0, -120, 360, Z_BOT, Z_TOP)
    # electronics resting on the ledge, in the corner
    pi = box(-PIECE_FRONT - 5, -PIECE_FRONT - 5 + 90, 60, 125, -175, -110)   # Pi+bonnet
    psu = box(-PIECE_FRONT - 5, -PIECE_FRONT - 5 + 115, 170, 200, -250, -140)  # LRS-350-5
    return ret_wall.union(ret_piece), pi, psu


def build():
    parts = {}
    parts["wall"] = wall_feature()
    for i, p in enumerate(panels()):
        parts[f"panel_{i:02d}"] = p
    parts["tongue_cleat"] = tongue_cleat()
    parts["bottom_tab"] = bottom_tab()
    parts["rails"] = rails()
    corner, pi, psu = corner_and_electronics()
    parts["corner"] = corner
    parts["pi_bonnet"] = pi
    parts["psu"] = psu
    return parts


COLORS = {   # name-prefix -> (r,g,b) for STEP + render
    "wall": (0.62, 0.47, 0.30),
    "corner": (0.62, 0.47, 0.30),
    "panel": (0.10, 0.10, 0.12),
    "tongue": (0.86, 0.74, 0.52),
    "bottom": (0.86, 0.74, 0.52),
    "rails": (0.75, 0.78, 0.82),
    "pi": (0.20, 0.65, 0.35),
    "psu": (0.55, 0.55, 0.58),
}


def color_for(name):
    for k, c in COLORS.items():
        if name.startswith(k):
            return c
    return (0.7, 0.7, 0.7)


def export(parts):
    asm = cq.Assembly()
    for name, wp in parts.items():
        r, g, b = color_for(name)
        asm.add(wp, name=name, color=cq.Color(r, g, b))
        cq.exporters.export(wp, os.path.join(OUT, f"{name}.stl"))
    asm.save(os.path.join(OUT, "vestor_wall_assembly.step"))
    print(f"exported {len(parts)} parts -> {OUT}")
    print(f"  row width  = {ROW_W} mm  (usable {WALL_USABLE} mm -> {ROW_W-WALL_USABLE:+.0f} mm)")
    print(f"  piece proud= {PIECE_PROUD} mm  panel face @ Y={PANEL_FACE_Y} mm")


if __name__ == "__main__":
    export(build())
