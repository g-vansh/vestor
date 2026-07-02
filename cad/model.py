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

# ---- PANELS ----------------------------------------------------------------
PANEL_W, PANEL_H, PANEL_D = 320.0, 160.0, 15.0
N_PANELS = 16
ROW_W = PANEL_W * N_PANELS                              # 5120
WALL_USABLE = 5118.0                                    # 201.5"
PANEL_BACK_Y = PIECE_FRONT + 2                          # 36 (just off the piece)
PANEL_FACE_Y = PANEL_BACK_Y + PANEL_D                   # 51
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
    P.append(("wall",   (0, ROW_W, -120, 0,          Z_BOT - 120, CEIL_TO_TOP + 20), C_WALL))
    P.append(("piece",  (0, ROW_W, PIECE_BACK, PIECE_FRONT, Z_BOT, Z_TOP),           C_PIECE))
    P.append(("bridge", (0, ROW_W, 0, PIECE_BACK,    Z_MID_BOT, Z_MID_TOP),          C_PIECE))  # attach

    # --- panels (dark body + a thin front "screen") ---
    for i in range(N_PANELS):
        x0 = i * PANEL_W + 0.5
        x1 = (i + 1) * PANEL_W - 0.5
        P.append((f"panel_{i:02d}", (x0, x1, PANEL_BACK_Y, PANEL_FACE_Y - 1.5, -PANEL_H, 0), C_PANEL))
        P.append((f"screen_{i:02d}", (x0 + 3, x1 - 3, PANEL_FACE_Y - 1.5, PANEL_FACE_Y, -PANEL_H + 3, -3), C_SCREEN))

    # --- mount: tongue in TOP groove, tab in BOTTOM groove ---
    P.append(("tongue", (0, ROW_W, PIECE_BACK - TONGUE_THICK, PIECE_BACK, Z_MID_TOP + 2, Z_TOP), C_BIRCH))
    P.append(("tab",    (0, ROW_W, -WALL_STEP + 1, -WALL_STEP + 1 + TAB_THICK, Z_BOT, Z_BOT + ENGAGE), C_BIRCH))

    # --- LEFT WALL: a room corner FACING the panel wall, extending INTO the room
    # (+Y). SAME structure as the front wall (2 cm piece + top/bottom grooves),
    # rotated 90°: its surface is at X=0, the piece juts +X, grooves face +X.
    LWY = 520                                    # modeled length along the left wall
    P.append(("corner_solid", (-120, 0, -120, 0, Z_BOT - 120, CEIL_TO_TOP + 20), C_WALL))
    P.append(("left_wall",    (-120, 0, 0, LWY, Z_BOT - 120, CEIL_TO_TOP + 20),  C_WALL))
    P.append(("left_piece",   (PIECE_BACK, PIECE_FRONT, 0, LWY, Z_BOT, Z_TOP),   C_PIECE))  # juts +X
    P.append(("left_bridge",  (0, PIECE_BACK, 0, LWY, Z_MID_BOT, Z_MID_TOP),     C_PIECE))
    # electronics HUNG from the left wall's grooves, tucked into the corner
    P.append(("pi_bonnet", (PIECE_FRONT, PIECE_FRONT + 80, 150, 215, -110, -50),   C_PI))
    P.append(("psu",       (PIECE_FRONT, PIECE_FRONT + 115, 280, 315, -220, -110), C_PSU))
    return P


def summary():
    over = ROW_W - WALL_USABLE
    return (f"row {ROW_W:.0f} mm into {WALL_USABLE:.0f} mm usable ({over:+.0f} mm) | "
            f"piece {PIECE_PROUD} mm proud | panel face @ Y={PANEL_FACE_Y:.0f} mm | "
            f"tongue {TONGUE_THICK} mm in {TOP_GRV_GAP} mm top groove | "
            f"tab {TAB_THICK} mm in {BOT_GRV_GAP} mm bottom groove")


if __name__ == "__main__":
    print(summary())
    for n, b, _ in parts():
        print(f"  {n:14s} x[{b[0]:.0f},{b[1]:.0f}] y[{b[2]:.0f},{b[3]:.0f}] z[{b[4]:.0f},{b[5]:.0f}]")
