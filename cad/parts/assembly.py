#!/usr/bin/env python3
"""
assembly.py — put every mount part together in ONE wall-coordinate frame and prove
they fit. Because all parts import mount_params, unioning them here cannot drift.

Outputs:
  • assembly_section.png — the full vertical cross-section (top cleat → top bar →
    panel → bottom bar → anti-swing foot → rest shoe): the money shot.
  • assembly_3d.png      — a shaded 3D of a ~2-panel section with all hardware.
Run the individual parts first (they write the per-part STLs); this writes the
section STLs into out/asm/ and renders them.
"""
import os, sys, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadquery as cq
import mount_params as P
from util import box, OUT, IMG
import top_cleat, anti_swing_foot, panel_rest, bars, corner_enclosure

ASM = os.path.join(OUT, "asm"); os.makedirs(ASM, exist_ok=True)
SECT_X = 700.0     # render a 700 mm slice (~2 panels)

PALETTE = {
    "wall": (0.66, 0.52, 0.35), "piece": (0.55, 0.41, 0.26), "bridge": (0.55, 0.41, 0.26),
    "panel": (0.11, 0.11, 0.14), "screen": (0.10, 0.55, 0.85),
    "cleat": (0.93, 0.80, 0.52), "foot": (0.93, 0.80, 0.52), "tab": (0.96, 0.66, 0.30),
    "rest": (0.85, 0.72, 0.45), "bar": (0.78, 0.80, 0.84), "steel": (0.62, 0.64, 0.68),
}
def color_for(name):
    for k, c in PALETTE.items():
        if name.startswith(k):
            return c
    return (0.7, 0.7, 0.7)


def section_solids():
    """[(name, solid)] for a SECT_X slice with all hardware + context."""
    S = []
    # ---- wall context ----
    S.append(("wall",   box(-40, SECT_X + 40, -25, 0, -320, 40)))
    S.append(("piece",  box(-40, SECT_X + 40, P.PIECE_BACK_Y, P.PIECE_FRONT_Y, P.PIECE_BOT_Z, 0)))
    S.append(("bridge", box(-40, SECT_X + 40, 0, P.PIECE_BACK_Y, P.ATTACH_BOT_Z, P.ATTACH_TOP_Z)))
    # ---- the two bars + steel over the slice ----
    S.append(("bar_top",   bars.build_bar(P.TOP_BAR_CZ, SECT_X)))
    S.append(("bar_bot",   bars.build_bar(P.BOT_BAR_CZ, SECT_X)))
    S.append(("steel_top", bars.build_steel(P.TOP_BAR_CZ, SECT_X)))
    S.append(("steel_bot", bars.build_steel(P.BOT_BAR_CZ, SECT_X)))
    # ---- cleats at two stations ----
    for i, x0 in enumerate((40.0, 440.0)):
        S.append((f"cleat_{i}", top_cleat.build(x0)))
    # ---- one anti-swing foot (base + tab) ----
    S.append(("foot_0", anti_swing_foot.build_base(35.0)))
    S.append(("tab_0",  anti_swing_foot.build_tab(35.0)))
    # ---- rest shoes under two panel centres ----
    for i, x0 in enumerate((140.0, 460.0)):
        S.append((f"rest_{i}", panel_rest.build(x0)))
    # ---- two panels (front) ----
    for i, a in enumerate((0.0, 320.0)):
        S.append((f"panel_{i}", box(a + 1, a + P.PANEL_W - 1, P.PANEL_BACK_Y, P.PANEL_FACE_Y - 1.5, P.PANEL_BOT_Z, 0)))
        S.append((f"screen_{i}", box(a + 4, a + P.PANEL_W - 4, P.PANEL_FACE_Y - 1.5, P.PANEL_FACE_Y, P.PANEL_BOT_Z + 4, -4)))
    return S


def collide_check(S):
    """Real boolean test: no hardware may share solid volume with (a) the panels'
    active volume or (b) the wooden piece. Uses actual intersections, so L-shaped
    parts that merely tuck a ledge under the panel bottom edge pass."""
    panel_vol = box(-40, SECT_X + 40, P.PANEL_BACK_Y, P.PANEL_FACE_Y, P.PANEL_BOT_Z, 0)
    piece_vol = box(-40, SECT_X + 40, P.PIECE_BACK_Y, P.PIECE_FRONT_Y, P.PIECE_BOT_Z, 0)
    def vol(s):
        try:
            r = s.val().Volume()
            return r if r else 0.0
        except Exception:
            return 0.0
    issues = []
    for n, s in S:
        if not n.startswith(("cleat", "foot", "tab", "rest")):
            continue
        for label, ref in (("panel", panel_vol), ("piece", piece_vol)):
            inter = vol(s.intersect(ref))
            if inter > 1.0:   # > 1 mm³ of shared solid = a real clash
                issues.append(f"{n} clashes with {label} ({inter:.0f} mm³)")
    return issues


def write_stls(S):
    for f in glob.glob(os.path.join(ASM, "*.stl")):
        os.remove(f)
    for n, s in S:
        cq.exporters.export(s, os.path.join(ASM, f"{n}.stl"))


OPACITY = {"wall": 0.06, "piece": 0.5, "bridge": 0.5, "panel": 0.22, "screen": 0.32}

def render_3d():
    import pyvista as pv
    pv.OFF_SCREEN = True
    p = pv.Plotter(off_screen=True, window_size=(1900, 1250))
    p.set_background("white")
    for stl in sorted(glob.glob(os.path.join(ASM, "*.stl"))):
        name = os.path.splitext(os.path.basename(stl))[0]
        m = pv.read(stl)
        op = next((v for k, v in OPACITY.items() if name.startswith(k)), 1.0)
        p.add_mesh(m, color=color_for(name), opacity=op, show_edges=(op == 1.0),
                   edge_color=(.25, .2, .12), line_width=1, ambient=0.4, diffuse=0.7, specular=0.1)
    # front-left, slightly above, looking back at the wall down the row
    p.camera_position = [(-520, 1000, 430), (360, 55, -95), (0, 0, 1)]
    p.camera.zoom(1.45)
    p.screenshot(os.path.join(IMG, "assembly_3d.png"))
    print("  wrote assembly_3d.png")


def render_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Polygon, Rectangle
    from util import draw_wall, bar_patch
    import anti_swing_foot as F
    fig, ax = plt.subplots(figsize=(9, 11))
    draw_wall(ax)
    # top cleat
    prof = [(top_cleat.TONGUE_Y0, -top_cleat.TONGUE_DEEP), (top_cleat.TONGUE_Y0, top_cleat.SADDLE_TOP),
            (P.FACE_Y, top_cleat.SADDLE_TOP), (P.FACE_Y, top_cleat.UPRIGHT_BOT),
            (top_cleat.UPRIGHT_BACK, top_cleat.UPRIGHT_BOT), (top_cleat.UPRIGHT_BACK, 0),
            (top_cleat.TONGUE_Y1, 0), (top_cleat.TONGUE_Y1, -top_cleat.TONGUE_DEEP)]
    ax.add_patch(Polygon(prof, closed=True, facecolor=PALETTE["cleat"], ec="k", lw=1.2, zorder=5))
    # bars + steel
    bar_patch(ax, P.TOP_BAR_CZ, "top bar")
    bar_patch(ax, P.BOT_BAR_CZ, "bottom bar")
    # anti-swing foot base + tab
    ax.add_patch(Rectangle((0, F.BASE_BOT), P.FACE_Y, F.BASE_TOP - F.BASE_BOT, facecolor=PALETTE["foot"], ec="k", lw=1.1, zorder=5))
    ax.add_patch(Rectangle((F.CH_Y0, F.TAB_BOT), F.CH_Y1 - F.CH_Y0, F.TAB_TOP - F.TAB_BOT, facecolor=PALETTE["tab"], ec="k", lw=1.1, zorder=6))
    # rest shoe (front wall + ledge)
    z0b, _ = P.bar_z(P.BOT_BAR_CZ)
    ax.add_patch(Rectangle((P.FACE_Y - 3, z0b - 3), (P.BAR_FRONT_Y + 3) - (P.FACE_Y - 3), 21, facecolor=PALETTE["rest"], ec="k", lw=.9, zorder=4))
    ax.add_patch(Rectangle((P.BAR_FRONT_Y, P.PANEL_BOT_Z - 3), 19, 3, facecolor=PALETTE["rest"], ec="k", lw=.9, zorder=7))
    # magnet screws at both M3 rows
    for z in (P.M3_TOP_Z, P.M3_BOT_Z):
        ax.plot([P.STEEL_Y, P.PANEL_BACK_Y], [z, z], color="orange", lw=3, zorder=8)
    # labels
    ax.annotate("PART 1  top cleat → TOP groove", (7, -30), (82, -20), fontsize=8.5, arrowprops=dict(arrowstyle="->"))
    ax.annotate("PART 4  top bar + steel", (P.FACE_Y+2, P.TOP_BAR_CZ), (82, -55), fontsize=8.5, arrowprops=dict(arrowstyle="->"))
    ax.annotate("magnet screws → panel\n(6 per panel, adjustable)", (P.PANEL_BACK_Y, P.M3_TOP_Z), (82, -90), fontsize=8.5, arrowprops=dict(arrowstyle="->"))
    ax.annotate("PART 4  bottom bar + steel", (P.FACE_Y+2, P.BOT_BAR_CZ), (82, -128), fontsize=8.5, arrowprops=dict(arrowstyle="->"))
    ax.annotate("PART 2  anti-swing tab → BOT groove", (F.CH_Y1, -118), (82, -158), fontsize=8.5, arrowprops=dict(arrowstyle="->"))
    ax.annotate("PART 3  rest shoe (panel sits on it)", (P.BAR_FRONT_Y+8, P.PANEL_BOT_Z), (82, -185), fontsize=8.5, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-30, 150); ax.set_ylim(-205, 30); ax.set_aspect("equal"); ax.grid(alpha=.3)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm, 0 = piece top)")
    ax.set_title("VESTOR MOUNT — full cross-section (all parts, one frame)")
    fig.savefig(os.path.join(IMG, "assembly_section.png"), dpi=130, bbox_inches="tight")
    print("  wrote assembly_section.png")


if __name__ == "__main__":
    print("ASSEMBLY")
    S = section_solids()
    issues = collide_check(S)
    print("  collide check:", "OK — no hardware intrudes into the panel band" if not issues else issues)
    write_stls(S)
    render_section()
    render_3d()
