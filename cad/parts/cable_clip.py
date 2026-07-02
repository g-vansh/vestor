#!/usr/bin/env python3
"""
cable_clip.py — PART 8: SCREW-DOWN CABLE CLIP / strain-relief anchor.

The reliability research is emphatic: on a warm ceiling gap, adhesive tie-mounts
soften and peel (gravity + heat), dropping the cable's weight onto the connector —
the exact failure to avoid. So every load-bearing anchor is SCREW-DOWN. This little
clip screws to the backer strip and a zip-tie loops over the cable through its two
slots, giving the "first anchor point" ~50–100 mm from each connector (so the crimp/
IDC/plug never bears load) and supporting each vertical drop's weight.

Print MANY (~40): one near every pigtail plug, every HUB75 connector, every drop top.
Trivial PLA. (Off-the-shelf screw-down tie mounts work too; this is the free version.)
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box, cut_cyl, export, IMG

L, W, H = 22.0, 18.0, 5.0
SLOT_W, SLOT_L = 3.0, 8.0


def build(x0=0.0):
    xc, yc = x0 + L / 2, W / 2
    b = box(x0, x0 + L, 0, W, 0, H)
    # central M4 screw hole (fix to the strip) with a shallow counterbore
    b = cut_cyl(b, xc, yc, H, 4.5, axis="Z", length=H * 3)
    b = cut_cyl(b, xc, yc, H - 1.5, 8.0, axis="Z", length=4)   # counterbore for the head
    # two zip-tie slots flanking the screw (a tie loops over the cable, down through both)
    for dx in (-7, 7):
        b = b.cut(box(xc + dx - SLOT_W / 2, xc + dx + SLOT_W / 2, yc - SLOT_L / 2, yc + SLOT_L / 2, -1, H + 1))
    return b


def fit_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle, FancyArrow, Circle
    fig, ax = plt.subplots(figsize=(6, 4.5))
    ax.add_patch(Rectangle((0, 0), L, H, facecolor=(.90, .80, .55), ec="k", lw=1.1))
    ax.add_patch(Rectangle((L/2-1.5, -1), 3, H+1, facecolor="w", ec="k", lw=.6))       # screw hole
    for dx in (-7, 7): ax.add_patch(Rectangle((L/2+dx-1.5, -1), 3, H+1, facecolor="#eee", ec="k", lw=.5))
    ax.add_patch(Circle((L/2, H+6), 4.5, fill=False, ec="#555", lw=1.5))               # cable bundle
    ax.plot([L/2-6, L/2-6, L/2+6, L/2+6], [H, H+11, H+11, H], color="#2a8a6a", lw=1.6)  # zip tie
    ax.text(L/2, H+6, "cable", ha="center", va="center", fontsize=8, color="#555")
    ax.annotate("M4 screw → strip", (L/2, 0), (L+3, 1), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("zip-tie slots", (L/2+7, H), (L+3, H+4), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-3, L+30); ax.set_ylim(-3, H+16); ax.set_aspect("equal"); ax.axis("off")
    ax.set_title("PART 8 · SCREW-DOWN CABLE CLIP (strain-relief anchor)")
    fig.savefig(os.path.join(IMG, "cable_clip_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote cable_clip_fit.png")


if __name__ == "__main__":
    print("PART 8 · cable_clip")
    export(build(), "cable_clip")
    fit_section()
