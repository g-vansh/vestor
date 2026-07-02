#!/usr/bin/env python3
"""
top_cleat.py — PART 1: STATION CLEAT (tall C-bracket).  REVISED after the
manufacturing-strategy research (docs/design/MANUFACTURING_PLAN.md).

WHY IT CHANGED: the adversarial validation said the #1 risk was COPLANARITY — two
independently-hung rails + dialing 96 magnet screws will not make P5 seams vanish.
The fix is to make the rails a single flat DATUM you level ONCE. So the cleat now
carries BOTH rails on one rigid part:

  • TONGUE  drops into the TOP groove (11 mm blade in the 14 mm slot, 46 mm deep) →
    all load in COMPRESSION into solid wood; zero wall holes.
  • SADDLE  rests on the piece top (Z 0..8) + a Z-level set-screw.
  • SPINE   runs flush down the piece front (Y 34..40) to below the bottom rail,
    presenting ONE front plane (Y=40) that carries the TOP rail (Z=-8) AND the
    BOTTOM rail (Z=-152). Because both rails bolt to the same rigid spine, they are
    coplanar + parallel by construction; you level the ~13 cleats to a laser line
    (jackscrew + slotted rail holes) and BOTH rails follow. Screws are trim only.

Print: on its SIDE (cross-section in the build plane, the 50 mm width up Z) → the
tongue/overhangs stay support-free AND the ~1.5 kg shear runs ALONG the layer lines
(the orientation that takes a PLA hook from failing at 15 kg to holding 100+ kg).
PLA is 4-5× under the creep threshold here; PETG/ASA if you want zero worry near LED heat.
~13 of them (one per station, ≤450 mm apart — do NOT reduce the count: rail sag ∝ span⁴).
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box, cut_cyl, export, draw_wall, bar_patch, IMG

WIDTH = 50.0
TONGUE_Y0, TONGUE_Y1 = 1.5, 12.5
TONGUE_DEEP = 46.0
SADDLE_TOP = 8.0
SPINE_BACK = P.PIECE_FRONT_Y          # 34 — spine bears flush on the piece front
SPINE_BOT  = P.bar_z(P.BOT_BAR_CZ)[0] - 1   # just below the bottom rail (~ -166)


def build(x0=0.0):
    xc = x0 + WIDTH / 2
    tongue = box(x0, x0 + WIDTH, TONGUE_Y0, TONGUE_Y1, -TONGUE_DEEP, 0)
    saddle = box(x0, x0 + WIDTH, TONGUE_Y0, P.FACE_Y, 0, SADDLE_TOP)
    spine  = box(x0, x0 + WIDTH, SPINE_BACK, P.FACE_Y, SPINE_BOT, SADDLE_TOP)
    b = tongue.union(saddle).union(spine)
    # rail-mount M4 (heat-set inserts from the front): 2 per rail, at both rail centres
    for cz in (P.TOP_BAR_CZ, P.BOT_BAR_CZ):
        for dx in (-P.BOLT_DX, P.BOLT_DX):
            b = cut_cyl(b, xc + dx, P.FACE_Y, cz, P.M4_CLR, axis="Y")
        # central jackscrew (levels this cleat's rail-face to the laser line)
        b = cut_cyl(b, xc, P.FACE_Y, cz + 13, 4.2, axis="Y")
    # Z-level set-screw down through the saddle onto the piece top
    b = cut_cyl(b, xc, 24, 4.0, 4.2, axis="Z", length=40)
    return b


def fit_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Polygon
    fig, ax = plt.subplots(figsize=(8, 10))
    draw_wall(ax)
    prof = [(TONGUE_Y0, -TONGUE_DEEP), (TONGUE_Y0, SADDLE_TOP), (P.FACE_Y, SADDLE_TOP),
            (P.FACE_Y, SPINE_BOT), (SPINE_BACK, SPINE_BOT), (SPINE_BACK, 0),
            (TONGUE_Y1, 0), (TONGUE_Y1, -TONGUE_DEEP)]
    ax.add_patch(Polygon(prof, closed=True, facecolor=(.90, .80, .55), ec="k", lw=1.2, zorder=5))
    bar_patch(ax, P.TOP_BAR_CZ, "top rail (steel)")
    bar_patch(ax, P.BOT_BAR_CZ, "bottom rail (steel angle)")
    for z in (P.M3_TOP_Z, P.M3_BOT_Z):
        ax.plot([P.RAIL_FRONT_Y, P.PANEL_BACK_Y], [z, z], color="orange", lw=3, zorder=8)
    ax.annotate("tongue → TOP groove", (7, -35), (82, -30), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("saddle on piece top", (25, 6), (82, 14), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("ONE spine carries BOTH rails\n(coplanar by construction)", (P.FACE_Y, -80), (78, -80), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-30, 140); ax.set_ylim(-190, 30); ax.set_aspect("equal"); ax.grid(alpha=.3)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm, 0 = piece top)")
    ax.set_title("PART 1 · STATION CLEAT — hangs in the top groove, carries BOTH rails")
    fig.savefig(os.path.join(IMG, "top_cleat_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote top_cleat_fit.png")


if __name__ == "__main__":
    print("PART 1 · top_cleat (tall C, carries both rails)")
    export(build(), "top_cleat")
    fit_section()
