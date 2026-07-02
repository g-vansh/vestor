#!/usr/bin/env python3
"""
top_cleat.py — PART 1: TOP CLEAT BRACKET.  (rebuilt on mount_params)

Hangs the whole assembly from the wall's TOP groove and carries the TOP bar.
An upside-down hook straddling the wooden piece:
  • TONGUE  drops into the top groove (Y 1.5..12.5 in the 14 mm slot, 46 mm deep) →
    rests near the 50 mm floor → all load in COMPRESSION into solid wood.
  • SADDLE  bridges forward, sits on the piece top (Z 0..8) → resists the panels'
    forward-tip moment.
  • UPRIGHT drops down the piece front (Y 32..40) → flat FACE_Y face to bolt the
    top aluminium bar (2× M4 at the bar centre) + a leveling set-screw through the saddle.

Print: PLA now (over-built), PETG/ASA later. ~N_STATIONS of them along the top groove.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box, cut_cyl, export, draw_wall, bar_patch, IMG

WIDTH = 50.0          # bracket width along the wall (X)
TONGUE_Y0, TONGUE_Y1 = 1.5, 12.5      # 11 mm blade in the 14 mm groove
TONGUE_DEEP = 46.0    # rests near the 50 mm floor
SADDLE_TOP = 8.0
UPRIGHT_BOT = -40.0
UPRIGHT_BACK = P.PIECE_FRONT_Y        # 34 — upright bears FLUSH on the piece front
#                                       (so the piece face shares the forward load).
#   Bar bolts: M4 short heat-set inserts from the FRONT face (6 mm upright), bolt
#   head on the bar side — the upright back is against the piece, so no rear nut.


def build(x0=0.0):
    xc = x0 + WIDTH / 2
    tongue  = box(x0, x0 + WIDTH, TONGUE_Y0, TONGUE_Y1, -TONGUE_DEEP, 0)
    saddle  = box(x0, x0 + WIDTH, TONGUE_Y0, P.FACE_Y, 0, SADDLE_TOP)
    upright = box(x0, x0 + WIDTH, UPRIGHT_BACK, P.FACE_Y, UPRIGHT_BOT, 0)
    b = tongue.union(saddle).union(upright)
    # 2× M4 through the upright → bolt the TOP bar (at its centre Z)
    for dx in (-P.BOLT_DX, P.BOLT_DX):
        b = cut_cyl(b, xc + dx, P.FACE_Y, P.TOP_BAR_CZ, P.M4_CLR, axis="Y")
    # leveling set-screw down through the saddle onto the piece top
    b = cut_cyl(b, xc, 24, 4.0, 4.2, axis="Z", length=40)
    return b


def fit_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Polygon
    fig, ax = plt.subplots(figsize=(8, 9))
    draw_wall(ax)
    # cleat cross-section (YZ) as a polygon
    prof = [(TONGUE_Y0, -TONGUE_DEEP), (TONGUE_Y0, SADDLE_TOP), (P.FACE_Y, SADDLE_TOP),
            (P.FACE_Y, UPRIGHT_BOT), (UPRIGHT_BACK, UPRIGHT_BOT), (UPRIGHT_BACK, 0),
            (TONGUE_Y1, 0), (TONGUE_Y1, -TONGUE_DEEP)]
    ax.add_patch(Polygon(prof, closed=True, facecolor=(.90, .80, .55), ec="k", lw=1.2, zorder=5))
    bar_patch(ax, P.TOP_BAR_CZ, "top bar + steel")
    ax.plot([P.STEEL_Y, P.PANEL_BACK_Y], [P.M3_TOP_Z, P.M3_TOP_Z], color="orange", lw=3, zorder=6)
    ax.annotate("tongue in TOP groove", (7, -35), (80, -35), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("saddle on piece top", (25, 6), (80, 18), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("magnet screw → panel", (P.PANEL_BACK_Y, P.M3_TOP_Z), (80, -70), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-30, 130); ax.set_ylim(-190, 30); ax.set_aspect("equal"); ax.grid(alpha=.3)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm, 0 = piece top)")
    ax.set_title("PART 1 · TOP CLEAT — drops into top groove, carries the top bar")
    fig.savefig(os.path.join(IMG, "top_cleat_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote top_cleat_fit.png")


if __name__ == "__main__":
    b = build()
    print("PART 1 · top_cleat")
    export(b, "top_cleat")
    fit_section()
