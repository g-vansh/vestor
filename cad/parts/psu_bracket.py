#!/usr/bin/env python3
"""
psu_bracket.py — PART 6: PSU CRADLE for a Mean Well LRS-350-5.

The two LRS-350-5 supplies are heavy and hot, so they do NOT hang from the panel
mount — they sit in their own cradles, raised for airflow, screwed to a surface
(exact spot is an on-site call: wall below the panels or a shelf in the corner).
Two cradles per PSU (near each end). The PSU (215 × 63 × 30 mm) drops in; corner
nubs retain it; a zip-tie slot cinches it; the floor is raised 8 mm and vented.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from util import box, cut_cyl, export, IMG

PSU_W, PSU_H = 63.0, 30.0      # PSU cross-section (Y width × Z height)
CRADLE_X = 34.0                # cradle length along the PSU
WALL = 4.0
FOOT = 8.0                     # airflow gap under the PSU
CLEAR = 0.6


def build(x0=0.0):
    iw = PSU_W + 2 * CLEAR
    # base plate (raised on feet)
    base = box(x0, x0 + CRADLE_X, 0, iw + 2 * WALL, FOOT, FOOT + WALL)
    # two feet strips
    f1 = box(x0, x0 + CRADLE_X, 0, 10, 0, FOOT)
    f2 = box(x0, x0 + CRADLE_X, iw + 2 * WALL - 10, iw + 2 * WALL, 0, FOOT)
    # two side walls that cradle the PSU
    w1 = box(x0, x0 + CRADLE_X, 0, WALL, FOOT + WALL, FOOT + WALL + 22)
    w2 = box(x0, x0 + CRADLE_X, iw + WALL, iw + 2 * WALL, FOOT + WALL, FOOT + WALL + 22)
    b = base.union(f1).union(f2).union(w1).union(w2)
    # corner retaining nubs (lip over the PSU top edge)
    for yy in (WALL, iw + WALL):
        nub = box(x0, x0 + CRADLE_X, yy - 2 if yy > WALL else WALL, yy + 2 if yy == WALL else iw + WALL,
                  FOOT + WALL + 22, FOOT + WALL + 25)
        b = b.union(nub)
    # vent holes in the floor
    for dy in (18, iw + 2 * WALL - 18):
        b = cut_cyl(b, x0 + CRADLE_X / 2, dy, FOOT + WALL / 2, 10, axis="Z", length=WALL * 3)
    # mounting screw holes through the feet (fix to a surface)
    for dy in (5, iw + 2 * WALL - 5):
        b = cut_cyl(b, x0 + CRADLE_X / 2, dy, FOOT / 2, 5.0, axis="Z", length=FOOT * 3)
    # zip-tie slot through the base
    b = b.cut(box(x0 + CRADLE_X / 2 - 3, x0 + CRADLE_X / 2 + 3, WALL + 4, iw + WALL - 4, FOOT, FOOT + WALL))
    return b


def preview():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle
    fig, ax = plt.subplots(figsize=(7, 5))
    iw = PSU_W + 2 * CLEAR
    ax.add_patch(Rectangle((0, FOOT), iw + 2 * WALL, WALL, facecolor=(.90, .80, .55), ec="k"))
    ax.add_patch(Rectangle((0, 0), 10, FOOT, facecolor=(.90, .80, .55), ec="k"))
    ax.add_patch(Rectangle((iw + 2*WALL - 10, 0), 10, FOOT, facecolor=(.90, .80, .55), ec="k"))
    ax.add_patch(Rectangle((0, FOOT+WALL), WALL, 25, facecolor=(.90, .80, .55), ec="k"))
    ax.add_patch(Rectangle((iw+WALL, FOOT+WALL), WALL, 25, facecolor=(.90, .80, .55), ec="k"))
    ax.add_patch(Rectangle((WALL+CLEAR, FOOT+WALL), PSU_W, PSU_H, fill=False, ec="r", lw=1.5, ls="--"))
    ax.text((iw+2*WALL)/2, FOOT+WALL+PSU_H+6, "LRS-350-5\n(63 × 30)", ha="center", fontsize=8, color="r")
    ax.text((iw+2*WALL)/2, 3.5, "raised 8 mm for airflow · screws to surface", ha="center", fontsize=7)
    ax.set_xlim(-6, iw+2*WALL+6); ax.set_ylim(-4, FOOT+WALL+PSU_H+16); ax.set_aspect("equal")
    ax.set_xlabel("Y — PSU width (mm)"); ax.set_ylabel("Z — height (mm)")
    ax.set_title("PART 6 · PSU CRADLE — end view (2 per supply)")
    fig.savefig(os.path.join(IMG, "psu_bracket_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote psu_bracket_fit.png")


if __name__ == "__main__":
    print("PART 6 · psu_bracket")
    export(build(), "psu_bracket")
    preview()
