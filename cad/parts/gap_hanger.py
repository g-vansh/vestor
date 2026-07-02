#!/usr/bin/env python3
"""
gap_hanger.py — PART 7: CEILING-GAP HANGER (carries the electronics backer strip + valance).

Hangs the back-of-house from the SAME top groove (zero new wall holes) up into the
~22.5 cm ceiling gap. Profile (YZ, extruded WIDTH in X):
  • TONGUE drops into the top groove (same 11 mm blade) + SADDLE on the piece top.
  • POST rises up the gap; its FRONT face (Y=STRIP_FRONT_Y) carries the continuous
    aluminium/​wood backer strip that the PSUs, fuse blocks and Pi bolt to.
  • VALANCE ARM reaches forward at the top to Y=PANEL_FACE_Y so the removable, vented
    valance sits flush with the panel plane (one clean trim band; vent reveal above it).

Light duty (~0.5 kg/hanger — electronics total ~3 kg over ~6 hangers) → PLA is ample.
Print on its SIDE (profile flat, WIDTH up Z) → support-free. ~6 along the wall,
biased to the ends where the PSUs sit.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box, cut_cyl, export, IMG

WIDTH = 40.0
TONGUE_Y0, TONGUE_Y1, TONGUE_DEEP = 1.5, 12.5, 46.0
SADDLE_TOP = 10.0
ARM_Z0 = P.HANGER_TOP_Z - 12          # valance arm sits at the top of the post


def build(x0=0.0):
    xc = x0 + WIDTH / 2
    tongue = box(x0, x0 + WIDTH, TONGUE_Y0, TONGUE_Y1, -TONGUE_DEEP, 0)
    saddle = box(x0, x0 + WIDTH, TONGUE_Y0, P.FACE_Y, 0, SADDLE_TOP)
    post   = box(x0, x0 + WIDTH, P.STRIP_BACK_Y, P.STRIP_FRONT_Y, SADDLE_TOP, P.HANGER_TOP_Z)
    arm    = box(x0, x0 + WIDTH, P.STRIP_FRONT_Y, P.VALANCE_FRONT_Y, ARM_Z0, P.HANGER_TOP_Z)
    b = tongue.union(saddle).union(post).union(arm)
    # strip-mount holes through the post front (2), + valance bolt down through the arm
    for z in (60, 140):
        b = cut_cyl(b, xc, P.STRIP_FRONT_Y, z, P.M4_CLR, axis="Y")
    b = cut_cyl(b, xc, P.VALANCE_FRONT_Y - 5, P.HANGER_TOP_Z, P.M4_CLR, axis="Z", length=30)
    return b


def fit_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Polygon, Rectangle
    fig, ax = plt.subplots(figsize=(7, 10))
    ax.plot([-25, 90], [P.CEIL_Z, P.CEIL_Z], color="#888", lw=3); ax.text(30, P.CEIL_Z + 5, "CEILING", fontsize=9, color="#777")
    ax.add_patch(Rectangle((-25, -60), 25, P.CEIL_Z + 60, facecolor=(.66, .52, .35), ec="k", lw=.4))   # wall
    ax.add_patch(Rectangle((P.PIECE_BACK_Y, -140), P.PIECE_THICK, 140, facecolor=(.55, .41, .26), ec="k", lw=.6))  # piece
    prof = [(TONGUE_Y0, -TONGUE_DEEP), (TONGUE_Y0, SADDLE_TOP), (P.FACE_Y, SADDLE_TOP), (P.FACE_Y, 0),
            (P.STRIP_FRONT_Y, 0), (P.STRIP_FRONT_Y, ARM_Z0), (P.VALANCE_FRONT_Y, ARM_Z0),
            (P.VALANCE_FRONT_Y, P.HANGER_TOP_Z), (P.STRIP_BACK_Y, P.HANGER_TOP_Z), (P.STRIP_BACK_Y, SADDLE_TOP),
            (TONGUE_Y1, SADDLE_TOP), (TONGUE_Y1, -TONGUE_DEEP)]
    ax.add_patch(Polygon(prof, closed=True, facecolor=(.90, .80, .55), ec="k", lw=1.2, zorder=5))
    ax.add_patch(Rectangle((P.STRIP_FRONT_Y, 40), 4, 120, facecolor=(.75, .78, .82), ec="k", lw=.6))    # backer strip
    ax.add_patch(Rectangle((P.STRIP_FRONT_Y + 4, 55), 30, 90, facecolor=(.9, .7, .35), ec="k", lw=.6))  # a PSU (edge)
    ax.add_patch(Rectangle((P.VALANCE_BACK_Y, 0), 10, P.VALANCE_TOP_Z, facecolor=(.85, .85, .87), ec="k", lw=.7, alpha=.85))  # valance
    ax.annotate("tongue → top groove", (7, -30), (78, -30), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("backer strip (electronics bolt on)", (P.STRIP_FRONT_Y, 100), (80, 120), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("PSU / fuse block / Pi", (50, 100), (80, 90), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("valance (flush, vented top)", (P.VALANCE_BACK_Y, 180), (80, 180), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-30, 130); ax.set_ylim(-70, P.CEIL_Z + 20); ax.set_aspect("equal"); ax.grid(alpha=.3)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm, 0 = piece top)")
    ax.set_title("PART 7 · GAP HANGER — carries the electronics strip + valance")
    fig.savefig(os.path.join(IMG, "gap_hanger_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote gap_hanger_fit.png")


if __name__ == "__main__":
    print("PART 7 · gap_hanger")
    export(build(), "gap_hanger")
    fit_section()
