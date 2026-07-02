#!/usr/bin/env python3
"""
model.py — parametric geometry of the Vestor wall + panels + mount, as plain
axis-aligned box specs (no CAD kernel needed). Dimensions (mm) from
docs/design/WALL_PROFILE.md + MOUNT_DESIGN.md. Consumed by render_mpl.py
(matplotlib) and render_pv.py (PyVista); STEP export can be layered on later.

Axes:  X = along wall (0 = left corner .. 5120 = right end)
       Y = depth into room (0 = main wall surface, +Y toward viewer)
       Z = vertical (0 = TOP of the wooden piece; +up / -down)
"""

# ---- WALL FEATURE (WALL_PROFILE.md) ----------------------------------------
PIECE_THICK, PIECE_HEIGHT, PIECE_PROUD = 20, 140, 34   # 2cm / 14cm / 3.4cm proud
CEIL_TO_TOP = 225                                       # 22.5 cm ceiling->top
TOP_GRV_H, TOP_GRV_GAP = 50, 14                         # 5 cm tall, 1.4 cm (open TOP)
BOT_GRV_H, BOT_GRV_GAP = 57, 18                         # 5.7 cm tall, 1.8 cm (open BOT)
WALL_STEP = 4                                           # wall 0.4 cm deeper below attach
MID_H = PIECE_HEIGHT - TOP_GRV_H - BOT_GRV_H            # 33 mm attach band

PIECE_BACK, PIECE_FRONT = TOP_GRV_GAP, PIECE_PROUD      # 14 .. 34
Z_TOP, Z_BOT = 0.0, -PIECE_HEIGHT                       # 0 .. -140
Z_MID_TOP, Z_MID_BOT = -TOP_GRV_H, -(TOP_GRV_H + MID_H)  # -50 .. -83

# ---- MOUNT STACKUP (from cad/parts/top_cleat.py) ---------------------------
# The panel plane is set by: cleat front face -> aluminium flat bar -> steel
# magnet tape -> the 11 mm magnet-screw standoff -> the panel. Coplanarity is
# taken out at each panel's 6 adjustable magnet screws, NOT by a precision rail.
CLEAT_FACE_Y = 40       # cleat front upright, 6 mm proud of the piece (34)
RAIL_THICK   = 5        # aluminium flat bar bolted to the cleat face
STEEL_THICK  = 1        # adhesive ferrous strip on the bar (magnet target)
MAGNET_STAND = 11       # magnet-screw protrusion off the panel back (0.8 shaft + 0.3 base)

# ---- PANELS ----------------------------------------------------------------
PANEL_W, PANEL_H, PANEL_D = 320.0, 160.0, 15.0
N_PANELS = 16
ROW_W = PANEL_W * N_PANELS                              # 5120
WALL_USABLE = 5118.0                                    # 201.5"
PANEL_BACK_Y = CLEAT_FACE_Y + RAIL_THICK + STEEL_THICK + MAGNET_STAND  # 57
PANEL_FACE_Y = PANEL_BACK_Y + PANEL_D                   # 72 (~38 mm proud of the piece)
M3_V_PITCH = 144.0

# ---- MOUNT (MOUNT_DESIGN.md) -----------------------------------------------
TONGUE_THICK = 12       # ½" birch, fits 14 mm top groove
TAB_THICK = 16          # fits 18 mm bottom groove
ENGAGE = 40             # engage ~40 of the groove depth

# palette (r,g,b 0..1)
C_WALL   = (0.66, 0.52, 0.35)
C_PIECE  = (0.55, 0.41, 0.26)
C_PANEL  = (0.11, 0.11, 0.14)
C_SCREEN = (0.10, 0.55, 0.85)
C_BIRCH  = (0.90, 0.80, 0.55)
C_RAIL   = (0.75, 0.78, 0.82)
C_PI     = (0.20, 0.66, 0.36)
C_PSU    = (0.55, 0.55, 0.58)


def parts():
    """Return [(name, (x0,x1,y0,y1,z0,z1), rgb), ...]."""
    P = []
    # --- wooden wall feature (grooves are the un-filled gaps) ---
    # The usable panel FACE = the full available space = 201.5" (WALL_USABLE), measured
    # to the INSIDE CORNER (X=WL) where the left wall's proud piece begins — the panels
    # STOP there. The left wall's SURFACE sits PIECE_FRONT (34 mm) BEHIND that corner,
    # since its own piece juts 34 mm into the room to meet the front face.
    WL = WALL_USABLE                              # 5118 mm — usable face, ends at the corner
    xc = WL + PIECE_FRONT                          # left-wall surface (its piece front = WL = corner)
    P.append(("wall",   (0, xc, -120, 0,          Z_BOT - 120, CEIL_TO_TOP + 20), C_WALL))
    P.append(("piece",  (0, WL, PIECE_BACK, PIECE_FRONT, Z_BOT, Z_TOP),           C_PIECE))
    P.append(("bridge", (0, WL, 0, PIECE_BACK,    Z_MID_BOT, Z_MID_TOP),          C_PIECE))  # attach
    P.append(("tongue", (0, WL, PIECE_BACK - TONGUE_THICK, PIECE_BACK, Z_MID_TOP + 2, Z_TOP), C_BIRCH))
    P.append(("tab",    (0, WL, -WALL_STEP + 1, -WALL_STEP + 1 + TAB_THICK, Z_BOT, Z_BOT + ENGAGE), C_BIRCH))

    # --- panels: 16×320 = 5120 mm, BUTT the corner (X=WL); the +2 mm overshoots the
    # FAR end (panel 0 sticks ~2 mm past X=0), never the corner ---
    x0p = WL - ROW_W                              # -2 mm
    for i in range(N_PANELS):
        a = x0p + i * PANEL_W
        P.append((f"panel_{i:02d}", (a + 0.5, a + PANEL_W - 0.5, PANEL_BACK_Y, PANEL_FACE_Y - 1.5, -PANEL_H, 0), C_PANEL))
        P.append((f"screen_{i:02d}", (a + 3, a + PANEL_W - 3, PANEL_FACE_Y - 1.5, PANEL_FACE_Y, -PANEL_H + 3, -3), C_SCREEN))

    # --- LEFT WALL (viewer's LEFT): a room corner coming TOWARD them (+Y), built
    # identically. Its proud piece FRONT sits exactly at the corner (X=WL) where the
    # front face ends, so NOTHING juts into the panel row — clean L. ---
    LWY = 520
    P.append(("corner_solid", (xc, xc + 120, -120, 0, Z_BOT - 120, CEIL_TO_TOP + 20), C_WALL))
    P.append(("left_wall",    (xc, xc + 120, 0, LWY, Z_BOT - 120, CEIL_TO_TOP + 20),  C_WALL))
    P.append(("left_piece",   (xc - PIECE_FRONT, xc - PIECE_BACK, PIECE_FRONT, LWY, Z_BOT, Z_TOP), C_PIECE))  # front @ WL
    P.append(("left_bridge",  (xc - PIECE_BACK, xc, PIECE_FRONT, LWY, Z_MID_BOT, Z_MID_TOP),      C_PIECE))
    # electronics HUNG from the LEFT-wall grooves, deeper along the side wall
    P.append(("pi_bonnet", (xc - PIECE_FRONT - 80, xc - PIECE_FRONT, 250, 320, -110, -50),   C_PI))
    P.append(("psu",       (xc - PIECE_FRONT - 115, xc - PIECE_FRONT, 400, 470, -220, -110), C_PSU))
    return P


def summary():
    over = ROW_W - WALL_USABLE
    return (f"panel wall face = {WALL_USABLE:.0f} mm (201.5\") from the corner | "
            f"16 panels = {ROW_W:.0f} mm ({over:+.0f} mm at the far end) | "
            f"piece {PIECE_PROUD} mm proud | tongue {TONGUE_THICK} in {TOP_GRV_GAP} mm groove | "
            f"tab {TAB_THICK} in {BOT_GRV_GAP} mm groove")


if __name__ == "__main__":
    print(summary())
    for n, b, _ in parts():
        print(f"  {n:14s} x[{b[0]:.0f},{b[1]:.0f}] y[{b[2]:.0f},{b[3]:.0f}] z[{b[4]:.0f},{b[5]:.0f}]")
