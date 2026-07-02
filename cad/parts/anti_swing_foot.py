#!/usr/bin/env python3
"""
anti_swing_foot.py — PART 2: BOTTOM ANTI-SWING FOOT (base + slide-up tab).

PURE anti-swing insurance now (the tall station cleats carry the bottom rail, so
this no longer bears weight). It bolts to the continuous bottom rail BETWEEN cleat
stations (so its base clears the tall spine) and wedges a tab up into the wall's
BOTTOM groove so the panel bottoms can't kick out. Because the bottom groove opens
UPWARD, the tab
CANNOT drop in with the rest of the assembly (it would hit the solid attach band).
So the tab is a SEPARATE slide-up piece engaged AFTER the assembly is hung:

  1. Hang the assembly from the top groove (Part 1).           → frame hangs
  2. Bolt the bottom bar onto the foot BASE (Y=FACE_Y face).   → lower steel target
  3. Slide the TAB UP by hand into the bottom groove (~32 mm).
  4. Pinch it with the side M4 set-screw.                      → bottom captured

The foot base sits entirely BELOW the piece bottom (Z ≤ −140) so nothing collides
with the piece; only the thin tab reaches up into the groove (Y 0..13). Anti-swing
load path on a forward kick: base → channel front wall → tab front (Y=13) → piece
back (Y=14). Light duty (no vertical load) → PLA is plenty. Foot every FOOT_EVERY
stations (~850 mm).
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box, cut_cyl, export, draw_wall, bar_patch, IMG

WIDTH = 40.0
BASE_TOP = P.PIECE_BOT_Z            # -140  (base top flush with piece bottom)
BASE_BOT = BASE_TOP - 25           # -165
CH_Y0, CH_Y1 = 0.0, 13.0           # tab channel (in the 18 mm groove, ~1 mm front clearance)
CH_W = 22.0                        # channel width (X)
TAB_W = 20.0
TAB_TOP = -108.0                   # engaged: 32 mm up into the 57 mm groove
TAB_BOT = -158.0                   # stays captured in the channel


def build_base(x0=0.0):
    xc = x0 + WIDTH / 2
    b = box(x0, x0 + WIDTH, 0, P.FACE_Y, BASE_BOT, BASE_TOP)
    # tab channel (through Z) at the rear
    ch = box(xc - CH_W / 2, xc + CH_W / 2, CH_Y0, CH_Y1, BASE_BOT - 1, BASE_TOP + 1)
    b = b.cut(ch)
    # 2× M4 → bolt the BOTTOM bar onto the front face
    for dx in (-12, 12):
        b = cut_cyl(b, xc + dx, P.FACE_Y, P.BOT_BAR_CZ, P.M4_CLR, axis="Y")
    # side M4 set-screw that pinches the tab (locks its height by friction)
    b = cut_cyl(b, xc, CH_Y0 + 5, (BASE_TOP + BASE_BOT) / 2, 4.2, axis="X", length=WIDTH + 4)
    return b


def build_tab(x0=0.0):
    xc = x0 + WIDTH / 2
    return box(xc - TAB_W / 2, xc + TAB_W / 2, CH_Y0 + 0.5, CH_Y1 - 0.5, TAB_BOT, TAB_TOP)


def build(x0=0.0):
    """Base + tab (engaged) — for the assembly view."""
    return build_base(x0).union(build_tab(x0))


def fit_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle
    fig, ax = plt.subplots(figsize=(8, 9))
    draw_wall(ax)
    # base (YZ)
    ax.add_patch(Rectangle((0, BASE_BOT), P.FACE_Y, BASE_TOP - BASE_BOT, facecolor=(.90, .80, .55), ec="k", lw=1.1, zorder=5))
    # tab (engaged, reaching up into the bottom groove)
    ax.add_patch(Rectangle((CH_Y0, TAB_BOT), CH_Y1 - CH_Y0, TAB_TOP - TAB_BOT, facecolor=(.95, .70, .35), ec="k", lw=1.1, zorder=6))
    bar_patch(ax, P.BOT_BAR_CZ, "bottom bar + steel")
    ax.plot([P.STEEL_Y, P.PANEL_BACK_Y], [P.M3_BOT_Z, P.M3_BOT_Z], color="orange", lw=3, zorder=7)
    ax.annotate("tab slides UP\ninto bottom groove", (CH_Y1, -120), (75, -100), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("base (bolts the bottom bar)", (30, BASE_BOT + 8), (78, -150), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("magnet screw → panel", (P.PANEL_BACK_Y, P.M3_BOT_Z), (78, -185), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-30, 130); ax.set_ylim(-200, 20); ax.set_aspect("equal"); ax.grid(alpha=.3)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm, 0 = piece top)")
    ax.set_title("PART 2 · ANTI-SWING FOOT — tab wedges UP into the bottom groove")
    fig.savefig(os.path.join(IMG, "anti_swing_foot_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote anti_swing_foot_fit.png")


if __name__ == "__main__":
    print("PART 2 · anti_swing_foot")
    export(build_base(), "anti_swing_foot_base")
    export(build_tab(), "anti_swing_foot_tab")
    fit_section()
