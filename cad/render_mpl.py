#!/usr/bin/env python3
"""render_mpl.py — clean orthographic fit-check views (matplotlib, no GPU)."""
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

import model as M

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "img")
os.makedirs(OUT, exist_ok=True)


def _rng(b, axis):        # axis 0=X,1=Y,2=Z -> (min,max)
    return b[2 * axis], b[2 * axis + 1]


def _ortho(ax, boxes, u, v, depth, edge="k"):
    """Draw boxes projected onto (u,v); painter-sort by `depth` (far first)."""
    order = sorted(boxes, key=lambda p: sum(_rng(p[1], depth)))
    for _, b, c in order:
        u0, u1 = _rng(b, u); v0, v1 = _rng(b, v)
        ax.add_patch(Rectangle((u0, v0), u1 - u0, v1 - v0,
                     facecolor=c, edgecolor=edge, linewidth=0.25))


def front():
    """Front elevation (look at the wall face): the 16-panel row + corner."""
    P = M.parts()
    fig, ax = plt.subplots(figsize=(24, 3.4))
    _ortho(ax, P, u=0, v=2, depth=1)                 # X vs Z, depth Y
    ax.set_xlim(-160, M.ROW_W + 40); ax.set_ylim(-190, 40)
    ax.set_aspect("equal"); ax.axis("off")
    ax.annotate("", (0, 22), (M.ROW_W, 22), arrowprops=dict(arrowstyle="<->", color="crimson", lw=1.2))
    ax.text(M.ROW_W / 2, 34, f"16 panels = {M.ROW_W:.0f} mm, measured FROM THE CORNER "
            f"into {M.WALL_USABLE:.0f} mm usable ({M.ROW_W - M.WALL_USABLE:+.0f} mm at the far end)",
            ha="center", color="crimson", fontsize=10)
    ax.set_title("FRONT elevation — the LED face (blue); 201.5\" datum at the LEFT corner", fontsize=11)
    fig.savefig(os.path.join(OUT, "front.png"), dpi=130, bbox_inches="tight"); plt.close(fig)
    print("wrote front.png")


def top():
    """Top-down plan: the row's depth + the left-corner equipment bay."""
    P = M.parts()
    fig, ax = plt.subplots(figsize=(24, 3.6))
    _ortho(ax, P, u=0, v=1, depth=2)                 # X vs Y, depth Z
    ax.set_xlim(-160, M.ROW_W + 40); ax.set_ylim(-130, 380)
    ax.set_aspect("equal"); ax.axis("off")
    ax.text(M.ROW_W * 0.5, -95, "panel row (thin strip, ~5 cm off the wall)", ha="center", fontsize=9, color="0.3")
    ax.text(300, 150, "corner: Pi+bonnet (green) + PSU (grey)", fontsize=9, color="0.2")
    ax.set_title("TOP plan — depth into the room; electronics tucked in the left corner", fontsize=11)
    fig.savefig(os.path.join(OUT, "top.png"), dpi=130, bbox_inches="tight"); plt.close(fig)
    print("wrote top.png")


def top_corner():
    """Zoomed top-down of just the left corner."""
    P = [p for p in M.parts() if p[1][1] > M.ROW_W - 900]
    fig, ax = plt.subplots(figsize=(8, 7))
    _ortho(ax, P, u=0, v=1, depth=2)
    ax.set_xlim(M.ROW_W - 780, M.ROW_W + 140); ax.set_ylim(-130, 380)
    ax.set_aspect("equal"); ax.grid(alpha=0.25)
    ax.set_xlabel("X — along wall (mm)"); ax.set_ylabel("Y — depth (mm)")
    ax.set_title("Left corner (top view) — equipment bay")
    fig.savefig(os.path.join(OUT, "top_corner.png"), dpi=130, bbox_inches="tight"); plt.close(fig)
    print("wrote top_corner.png")


def section(xcut=160.0):
    """True-scale YZ cross-section through a panel: the depth stack + groove fits."""
    fig, ax = plt.subplots(figsize=(8.5, 9))
    for name, b, c in M.parts():
        if b[0] <= xcut <= b[1] and not name.startswith(("corner", "pi_", "psu")):
            ax.add_patch(Rectangle((b[2], b[4]), b[3] - b[2], b[5] - b[4],
                         facecolor=c, edgecolor="k", linewidth=0.7))

    def note(y, z, t, dx=6):
        ax.annotate(t, (y, z), (y + dx, z), fontsize=8, va="center",
                    arrowprops=dict(arrowstyle="->", lw=0.6))
    note(8, -24, "TOP groove 1.4×5 — tongue 12 mm drops in", 30)
    note(6, -112, "BOTTOM groove 1.8×5.7 — tab 16 mm up", 30)
    note(24, -66, "2 cm piece (14 cm tall)", 30)
    note(M.PANEL_FACE_Y, -85, "LED panel", 8)
    ax.axvline(0, color="0.5", lw=0.8, ls="--"); ax.text(-2, 40, "wall", ha="right", fontsize=8, color="0.4")
    ax.set_xlim(-30, 95); ax.set_ylim(-190, 60)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm, 0 = piece top)")
    ax.set_aspect("equal"); ax.grid(alpha=0.3)
    ax.set_title(f"CROSS-SECTION at X={xcut:.0f} mm (through a panel)")
    fig.savefig(os.path.join(OUT, "section.png"), dpi=130, bbox_inches="tight"); plt.close(fig)
    print("wrote section.png")


if __name__ == "__main__":
    print(M.summary())
    front(); top(); top_corner(); section()
