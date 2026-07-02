#!/usr/bin/env python3
"""
panel_rest.py — PART 3: PANEL REST SHOE (vertical support + creep stop).

Magnets hold each panel to the steel by friction (owner-tested strong), but a
shear (downward) load on a magnet can creep over months. This little shoe clips
onto the BOTTOM bar and puts a ledge exactly under the panel's bottom edge
(Z = PANEL_BOT_Z = −160) so every panel physically RESTS on metal — the magnets
then only need to hold it flat, not hold it up. It also sets each panel's vertical
height (drop the panel until it sits on the shoe → its two M3 rows line up with the
two bars). One per panel (~16), ~40 mm wide, clipped at the panel centre. Panels
still self-space in X by butting each other from the corner, so the shoe carries
no lateral duty.

Clips over the bottom bar's lower edge; the forward ledge sits below the bottom
M3 row so it never fouls the magnet screws.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box, export, draw_wall, bar_patch, IMG

WIDTH = 40.0
BZ0, BZ1 = P.bar_z(P.BOT_BAR_CZ)      # bottom bar: -165 .. -139
LEDGE_TOP = P.PANEL_BOT_Z             # -160  (panel bottom rests here)
LEDGE_THK = 3.0
LEDGE_FRONT_Y = P.PANEL_BACK_Y + 7    # 64 — under the panel back, mostly hidden
GRIP_UP = 18.0                        # how far the clip grips up the bar


def build(x0=0.0):
    # U-clip hugging the bar's lower edge (front wall, back wall, bottom bridge)
    front = box(x0, x0 + WIDTH, P.BAR_FRONT_Y, P.BAR_FRONT_Y + 3, BZ0, BZ0 + GRIP_UP)
    back  = box(x0, x0 + WIDTH, P.FACE_Y - 3, P.FACE_Y, BZ0, BZ0 + GRIP_UP)
    bridge = box(x0, x0 + WIDTH, P.FACE_Y - 3, P.BAR_FRONT_Y + 3, BZ0 - 3, BZ0)
    b = front.union(back).union(bridge)
    # forward ledge under the panel bottom edge
    ledge = box(x0, x0 + WIDTH, P.BAR_FRONT_Y, LEDGE_FRONT_Y, LEDGE_TOP - LEDGE_THK, LEDGE_TOP)
    riser = box(x0, x0 + WIDTH, P.BAR_FRONT_Y, P.BAR_FRONT_Y + 3, LEDGE_TOP - LEDGE_THK, BZ0 + GRIP_UP)
    return b.union(ledge).union(riser)


def fit_section():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle
    fig, ax = plt.subplots(figsize=(8, 6.5))
    draw_wall(ax)
    bar_patch(ax, P.BOT_BAR_CZ, "bottom bar + steel")
    # shoe cross-section: draw the union outline approximately (front wall + ledge)
    ax.add_patch(Rectangle((P.FACE_Y - 3, BZ0 - 3), (P.BAR_FRONT_Y + 3) - (P.FACE_Y - 3), GRIP_UP + 3,
                           facecolor=(.90, .80, .55), ec="k", lw=1.0, zorder=5))
    ax.add_patch(Rectangle((P.BAR_FRONT_Y, LEDGE_TOP - LEDGE_THK), LEDGE_FRONT_Y - P.BAR_FRONT_Y, LEDGE_THK,
                           facecolor=(.90, .80, .55), ec="k", lw=1.0, zorder=5))
    ax.plot([P.STEEL_Y, P.PANEL_BACK_Y], [P.M3_BOT_Z, P.M3_BOT_Z], color="orange", lw=3, zorder=7)
    ax.annotate("panel bottom RESTS here", (P.PANEL_BACK_Y + 3, LEDGE_TOP), (78, -120), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.annotate("clip grips the bottom bar", (P.FACE_Y, -150), (78, -175), fontsize=8, arrowprops=dict(arrowstyle="->"))
    ax.set_xlim(-30, 130); ax.set_ylim(-180, -110); ax.set_aspect("equal"); ax.grid(alpha=.3)
    ax.set_xlabel("Y — depth into room (mm)"); ax.set_ylabel("Z — vertical (mm)")
    ax.set_title("PART 3 · PANEL REST SHOE — panel bottom rests on metal (creep stop)")
    fig.savefig(os.path.join(IMG, "panel_rest_fit.png"), dpi=130, bbox_inches="tight")
    print("  wrote panel_rest_fit.png")


if __name__ == "__main__":
    print("PART 3 · panel_rest")
    export(build(), "panel_rest")
    fit_section()
