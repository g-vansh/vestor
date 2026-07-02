#!/usr/bin/env python3
"""
bars.py — PART 4: the two aluminium BARS + steel magnet strip (stock, not printed).

Two continuous 6063-T5 aluminium flat bars run the full 5118 mm at the panels' two
M3 rows (Z = −8 top, −152 bottom). Each carries an adhesive ferrous steel strip on
its front face — that's what the magnet screws grab. The bars are the flat reference
plane; the top bar bolts to the top cleats (Part 1), the bottom bar bolts to the
anti-swing feet (Part 2), and the panels magnet across both.

Coplanarity trick: the bars need only be ROUGHLY straight — each panel's six magnet
screws are individually adjustable, so residual waviness is dialed out at the panel.
Splice trick: put each bar joint AT a station so the cleat/foot there bridges both
bar ends → no separate splice plate needed.

This module just models the bars for the assembly and prints the cut list.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box

BAR_LEN = P.WALL_USABLE               # 5118 (stop at the corner)
SECTION = 2                            # 2 sections per bar (joint at the middle station)


def build_bar(cz, x1=BAR_LEN):
    z0, z1 = P.bar_z(cz)
    return box(0, x1, P.FACE_Y, P.BAR_FRONT_Y, z0, z1)


def build_steel(cz, x1=BAR_LEN):
    z0, z1 = P.bar_z(cz)
    return box(0, x1, P.BAR_FRONT_Y, P.STEEL_Y, z0 + 3, z1 - 3)


def build(x1=BAR_LEN):
    return (build_bar(P.TOP_BAR_CZ, x1).union(build_bar(P.BOT_BAR_CZ, x1))
            .union(build_steel(P.TOP_BAR_CZ, x1)).union(build_steel(P.BOT_BAR_CZ, x1)))


def cut_list():
    seg = BAR_LEN / SECTION
    joint_station = P.N_STATIONS // 2
    print("PART 4 · bars — CUT LIST")
    print(f"  aluminium flat bar 6063-T5, {P.BAR_H:.0f} × {P.BAR_THK:.0f} mm:")
    print(f"    4 pieces × {seg:.0f} mm  (2 per rail × 2 rails = top + bottom)")
    print(f"    joints land at station {joint_station} (x≈{P.station_x(joint_station):.0f}) → cleat/foot splices them")
    print(f"  steel magnet strip (adhesive ferrous, ~{P.BAR_H-6:.0f} mm wide, ~1 mm):")
    print(f"    2 runs × {BAR_LEN:.0f} mm (top & bottom bar front faces)")
    print(f"  bolts: M4 at each of {P.N_STATIONS} stations (2 per bar) → drill bars to match")


if __name__ == "__main__":
    cut_list()
