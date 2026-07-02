#!/usr/bin/env python3
"""
corner_enclosure.py — PART 5: CORNER ELECTRONICS TRAY (Pi 4 + Triple Bonnet).

Hangs in the LEFT-wall corner from that wall's TOP groove (identical construction),
using the same tongue+saddle hang as the top cleat — so the brain lives OFF TO THE
SIDE, not behind the LEDs. Open-frame tray (Pi 4 runs warm → ventilated) with four
M2.5 standoff bosses on the Pi's 58 × 49 mm hole pattern; the Triple Bonnet stacks
on the GPIO above the Pi and its HUB75 ribbon runs to panel 0 at the corner.

Modelled in a LOCAL frame (Z vertical, 0 = piece top; Y = depth off the left wall).
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box, cut_cyl, export, IMG

# hang feature (same as the top cleat)
TONGUE_Y0, TONGUE_Y1, TONGUE_DEEP = 1.5, 12.5, 46.0
SADDLE_TOP, FACE = 8.0, P.FACE_Y            # 40
PLATE_THK = 6.0
PLATE_W, PLATE_H = 76.0, 96.0               # X × Z  (Pi is 85×56)
PI_HX, PI_HZ = 49.0, 58.0                   # Pi 4 mount rectangle
BOSS_H, BOSS_D, BOSS_HOLE = 6.0, 8.0, 2.7   # M2.5 standoff bosses


def build(x0=0.0):
    xc = x0 + PLATE_W / 2
    tongue = box(x0, x0 + PLATE_W, TONGUE_Y0, TONGUE_Y1, -TONGUE_DEEP, 0)
    saddle = box(x0, x0 + PLATE_W, TONGUE_Y0, FACE, 0, SADDLE_TOP)
    plate  = box(x0, x0 + PLATE_W, FACE - PLATE_THK, FACE, -PLATE_H, SADDLE_TOP)
    b = tongue.union(saddle).union(plate)
    # ventilation slots
    for k in range(4):
        z = -18 - k * 18
        slot = box(x0 + 10, x0 + PLATE_W - 10, FACE - PLATE_THK - 1, FACE + 1, z, z + 8)
        b = b.cut(slot)
    # four Pi standoff bosses on the plate front (Y = FACE), with M2.5 holes
    czp = -PLATE_H / 2 + SADDLE_TOP / 2
    for dz in (-PI_HZ / 2, PI_HZ / 2):
        for dx in (-PI_HX / 2, PI_HX / 2):
            cxp, czz = xc + dx, czp + dz
            boss = (cyl(cxp, FACE, czz, BOSS_D / 2, BOSS_H))
            b = b.union(boss)
            b = cut_cyl(b, cxp, FACE + BOSS_H, czz, BOSS_HOLE, axis="Y", length=BOSS_H * 2 + PLATE_THK)
    return b


def cyl(cx, cy, cz, r, h):
    import cadquery as cq
    return cq.Workplane("XZ").circle(r).extrude(h).translate((cx, cy, cz))


def preview():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle, Circle
    fig, ax = plt.subplots(figsize=(6, 8))
    # front view (X across, Z up)
    ax.add_patch(Rectangle((0, -PLATE_H), PLATE_W, PLATE_H + SADDLE_TOP, facecolor=(.90, .80, .55), ec="k", lw=1))
    czp = -PLATE_H / 2 + SADDLE_TOP / 2
    # Pi board footprint
    ax.add_patch(Rectangle((PLATE_W/2 - 28, czp - 42.5), 56, 85, fill=False, ec="g", lw=1.5, ls="--"))
    for dz in (-PI_HZ/2, PI_HZ/2):
        for dx in (-PI_HX/2, PI_HX/2):
            ax.add_patch(Circle((PLATE_W/2 + dx, czp + dz), 3, color="k"))
    ax.text(PLATE_W/2, czp+52, "Pi 4 + Triple Bonnet\n(bonnet stacks on GPIO)", ha="center", fontsize=8, color="g")
    ax.text(PLATE_W/2, 3, "tongue → left-wall top groove", ha="center", fontsize=7)
    ax.set_xlim(-10, PLATE_W + 10); ax.set_ylim(-PLATE_H - 10, 20); ax.set_aspect("equal")
    ax.set_xlabel("X — along left wall (mm)"); ax.set_ylabel("Z — vertical (mm)")
    ax.set_title("PART 5 · CORNER TRAY — Pi 4 + Triple Bonnet (front)")
    fig.savefig(os.path.join(IMG, "corner_enclosure_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote corner_enclosure_fit.png")


if __name__ == "__main__":
    print("PART 5 · corner_enclosure")
    export(build(), "corner_enclosure")
    preview()
