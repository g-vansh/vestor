#!/usr/bin/env python3
"""
top_cleat.py — PART 1: the TOP CLEAT BRACKET.

It hangs the top rail from the wall's TOP groove. Profile (side view, Y=depth,
Z=vertical, 0 = piece top):
  - a TONGUE drops into the top groove (Y 1.5..12.5, i.e. 11 mm in the 14 mm slot),
    ~46 mm deep so it rests near the 50 mm groove floor → carries load in COMPRESSION
    into the solid wood;
  - a SADDLE bridges forward and rests on the piece top (Z=0) → shares the load +
    resists the front-down moment;
  - a FRONT UPRIGHT sits just proud of the piece (front face Y=40, clear of the
    piece front Y=34) → a flat face to bolt the steel strip / rail. Panels magnet
    on 11 mm in front of it (panel back Y=51, face Y=66).

Printed PLA now → PETG/ASA later. Placed every ~400–600 mm along the top rail.
Exports STL (print) + STEP (Fusion) + a cross-section fit-check PNG.
"""
import os, sys
import cadquery as cq

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
OUT = os.path.join(HERE, "..", "out"); os.makedirs(OUT, exist_ok=True)
IMG = os.path.join(OUT, "img"); os.makedirs(IMG, exist_ok=True)

# --- interface dims (mm) from the wall (docs/design/SETUP_SPEC.md) ---
GRV_GAP = 14           # top groove width (wall→piece back)
GRV_H = 50             # top groove depth (open top)
PIECE_FRONT = 34
WIDTH = 50             # bracket width along the wall (X)
TONGUE_THK = 11        # in the 14 mm groove (1.5 mm clearance each side)
TONGUE_DEEP = 46       # rests near the 50 mm floor
FRONT_Y = 40           # front face (steel/rail) — 6 mm proud of the piece front
SADDLE_TOP = 8         # saddle thickness above the piece top
UPRIGHT_BOT = -40      # upright reaches down past the top M3 row (Z=-8)

# cross-section profile (Y, Z), closed
PROFILE = [(1.5, -TONGUE_DEEP), (1.5, SADDLE_TOP), (FRONT_Y, SADDLE_TOP),
           (FRONT_Y, UPRIGHT_BOT), (FRONT_Y - 8, UPRIGHT_BOT),
           (FRONT_Y - 8, 0), (12.5, 0), (12.5, -TONGUE_DEEP)]


def build():
    b = (cq.Workplane("YZ").polyline(PROFILE).close().extrude(WIDTH))
    # two M4 clearance holes through the front upright (bolt the steel strip / rail)
    for z in (-10, -30):
        b = (b.faces(">Y").workplane(centerOption="CenterOfMass")
             .pushPoints([(-(WIDTH/2 - 12), z + 20), (WIDTH/2 - 12, z + 20)])
             .hole(4.5)) if False else b
    # simpler: drill along -Y at the two rail-bolt spots
    b = b.faces(">Y").workplane().pushPoints([(-14, 15), (14, 15)]).hole(4.5)
    # a leveling set-screw hole down through the saddle onto the piece top
    b = b.faces(">Z").workplane().pushPoints([(0, -20)]).hole(4.2)
    b = b.edges("|X").fillet(1.5)
    return b


def export(b):
    cq.exporters.export(b, os.path.join(OUT, "top_cleat.stl"))
    cq.exporters.export(b, os.path.join(OUT, "top_cleat.step"))
    print("wrote top_cleat.stl + .step")


def fit_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle, Polygon
    fig, ax = plt.subplots(figsize=(8.5, 9))
    # wall + piece + grooves (from the wall model)
    ax.add_patch(Rectangle((-25, -190), 25, 250, facecolor=(.66, .52, .35), ec="k", lw=.5))  # wall
    ax.add_patch(Rectangle((14, -140), 20, 140, facecolor=(.55, .41, .26), ec="k", lw=.7))    # piece
    ax.add_patch(Rectangle((0, -83), 14, 33, facecolor=(.55, .41, .26), ec="k", lw=.5))       # bridge
    ax.add_patch(Polygon(PROFILE, closed=True, facecolor=(.90, .80, .55), ec="k", lw=1.1))    # CLEAT
    ax.add_patch(Rectangle((40, -25), 5, 29, facecolor=(.75, .78, .82), ec="k", lw=.6))        # steel strip
    ax.add_patch(Rectangle((51, -160), 15, 160, facecolor=(.11, .11, .14), ec="k", lw=.7))     # panel
    ax.plot([40, 51], [-8, -8], color="orange", lw=3)                                          # magnet screw (top row)
    ax.annotate("tongue in TOP groove", (7, -35), (75, -35), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("saddle rests on piece top", (25, 4), (75, 15), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("steel strip + magnet screw", (45, -8), (78, -70), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("panel (face @ Y=66)", (58, -90), (78, -110), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-30, 120); ax.set_ylim(-190, 30); ax.set_aspect("equal"); ax.grid(alpha=.3)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm, 0 = piece top)")
    ax.set_title("TOP CLEAT — fit check (drops into top groove, carries rail out front)")
    fig.savefig(os.path.join(IMG, "top_cleat_fit.png"), dpi=130, bbox_inches="tight")
    print("wrote top_cleat_fit.png")


if __name__ == "__main__":
    b = build(); export(b); fit_section()
    bb = b.val().BoundingBox()
    print(f"cleat bbox: X{bb.xlen:.0f} × Y{bb.ylen:.0f} × Z{bb.zlen:.0f} mm")
