#!/usr/bin/env python3
"""util.py — shared CadQuery primitives + a common wall-context drawer.

Parts are built from axis-aligned boxes in WALL COORDINATES (so unioning them in
the assembly guarantees alignment) using box()/cut_cyl(), which are far more
robust than long face-selection chains. draw_wall() paints the identical wall
cross-section behind every part's fit-check PNG.
"""
import os
import cadquery as cq
import mount_params as P

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(HERE, "..", "out")); os.makedirs(OUT, exist_ok=True)
IMG = os.path.join(OUT, "img"); os.makedirs(IMG, exist_ok=True)


def box(x0, x1, y0, y1, z0, z1):
    """Axis-aligned box from explicit bounds (wall coords)."""
    return (cq.Workplane("XY")
            .box(x1 - x0, y1 - y0, z1 - z0, centered=(False, False, False))
            .translate((x0, y0, z0)))


def cut_cyl(solid, cx, cy, cz, d, axis="Y", length=400.0):
    """Cut a through-hole of diameter d centred at (cx,cy,cz), along an axis."""
    r = d / 2.0
    if axis == "Y":
        c = cq.Workplane("XZ").circle(r).extrude(length).translate((cx, cy - length / 2, cz))
    elif axis == "Z":
        c = cq.Workplane("XY").circle(r).extrude(length).translate((cx, cy, cz - length / 2))
    else:  # X
        c = cq.Workplane("YZ").circle(r).extrude(length).translate((cx - length / 2, cy, cz))
    return solid.cut(c)


def export(solid, name):
    cq.exporters.export(solid, os.path.join(OUT, f"{name}.stl"))
    cq.exporters.export(solid, os.path.join(OUT, f"{name}.step"))
    bb = solid.val().BoundingBox()
    print(f"  {name}: STL+STEP  bbox X{bb.xlen:.0f} × Y{bb.ylen:.0f} × Z{bb.zlen:.0f} mm")
    return bb


def draw_wall(ax, panel=True):
    """Paint the wall / grooves / piece (and optionally a panel) behind a
    YZ cross-section fit-check. Matches mount_params exactly."""
    from matplotlib.patches import Rectangle
    W = (.66, .52, .35); PC = (.55, .41, .26); PN = (.11, .11, .14)
    # main wall (with the 4 mm deeper step below the attach band)
    ax.add_patch(Rectangle((-25, -190), 25, 260, facecolor=W, ec="k", lw=.4))
    ax.add_patch(Rectangle((-P.WALL_STEP, -190), P.WALL_STEP, 260 - (P.PIECE_BOT_Z + 190) + (P.ATTACH_BOT_Z + 190),
                           facecolor=W, ec="none"))  # step filler (approx)
    # the piece (Y 14..34, Z 0..-140)
    ax.add_patch(Rectangle((P.PIECE_BACK_Y, P.PIECE_BOT_Z), P.PIECE_THICK, P.PIECE_TOP_Z - P.PIECE_BOT_Z,
                           facecolor=PC, ec="k", lw=.6))
    # attach band bridging piece→wall (solid, Z -50..-83)
    ax.add_patch(Rectangle((0, P.ATTACH_BOT_Z), P.PIECE_BACK_Y, P.ATTACH_TOP_Z - P.ATTACH_BOT_Z,
                           facecolor=PC, ec="k", lw=.4))
    if panel:
        ax.add_patch(Rectangle((P.PANEL_BACK_Y, P.PANEL_BOT_Z), P.PANEL_D, P.PANEL_H,
                               facecolor=PN, ec="k", lw=.6))
    # labels for the grooves
    ax.text(7, -25, "TOP\ngroove", ha="center", va="center", fontsize=6, color="w")
    ax.text(5, -112, "BOT\ngroove", ha="center", va="center", fontsize=6, color="w")


def bar_patch(ax, cz, label=None):
    """Draw one aluminium bar + its steel tape at bar-centre cz (YZ view)."""
    from matplotlib.patches import Rectangle
    z0, z1 = P.bar_z(cz)
    ax.add_patch(Rectangle((P.FACE_Y, z0), P.BAR_THK, P.BAR_H, facecolor=(.75, .78, .82), ec="k", lw=.5))
    ax.add_patch(Rectangle((P.BAR_FRONT_Y, z0 + 4), P.STEEL_THK, P.BAR_H - 8, facecolor=(.6, .62, .66), ec="k", lw=.4))
    if label:
        ax.annotate(label, (P.FACE_Y + 2, cz), (95, cz), fontsize=7, arrowprops=dict(arrowstyle="->"))
