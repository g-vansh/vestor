#!/usr/bin/env python3
"""
bars.py — PART 4: the two STEEL rails (stock, not printed).  REVISED per
docs/design/MANUFACTURING_PLAN.md.

Steel, not aluminium+glued-strip, because (validation): bare low-carbon steel is
the magnet target neodymium actually grips; steel is 3× stiffer (holds the flat
datum the seams depend on); it expands half as much; and there is no separate strip
to shear-peel.

  • TOP rail    = steel FLAT BAR (~30×5 mm), the upper magnet target.
  • BOTTOM rail = steel ANGLE: vertical leg = lower magnet target, horizontal leg =
    a CONTINUOUS ledge every panel bottom RESTS on (weight off the magnets; also
    deletes the 16 printed rest-shoes).

Both bolt to the station cleats' single front plane. Splice each rail in 2 at the
centre station so a cleat bridges the joint (no splice plate). Anchor to ONE central
cleat; slot the other cleats' holes so the 5 m rail can breathe (thermal).
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mount_params as P
from util import box

RAIL_LEN = P.WALL_USABLE          # 5118
TOP_H    = P.BAR_H                 # 26 mm flat-bar face height
V_LEG_Z0, V_LEG_Z1 = P.LEDGE_Z, P.LEDGE_Z + 30   # bottom angle vertical leg -160..-130


def build_top_rail(x1=RAIL_LEN):
    z0, z1 = P.bar_z(P.TOP_BAR_CZ)
    return box(0, x1, P.FACE_Y, P.RAIL_FRONT_Y, z0, z1)


def build_bottom_rail(x1=RAIL_LEN):
    vleg = box(0, x1, P.FACE_Y, P.RAIL_FRONT_Y, V_LEG_Z0, V_LEG_Z1)          # magnet target
    hleg = box(0, x1, P.FACE_Y, P.LEDGE_FRONT_Y, P.LEDGE_Z - P.LEDGE_THK, P.LEDGE_Z)  # rest ledge
    return vleg.union(hleg)


def build(x1=RAIL_LEN):
    return build_top_rail(x1).union(build_bottom_rail(x1))


def cut_list():
    seg = RAIL_LEN / 2
    print("PART 4 · STEEL rails — CUT LIST")
    print(f"  TOP rail: low-carbon steel FLAT BAR ~{TOP_H:.0f} × {P.RAIL_THK:.0f} mm")
    print(f"    2 pieces × {seg:.0f} mm (splice at centre station {P.ANCHOR_STATION})")
    print(f"  BOTTOM rail: low-carbon steel ANGLE ~30 × {P.LEDGE_FRONT_Y-P.FACE_Y:.0f} × {P.LEDGE_THK:.0f} mm")
    print(f"    2 pieces × {seg:.0f} mm (vertical leg = magnet target, horizontal leg = panel rest ledge)")
    print(f"  Bare steel (NO paint/zinc on the magnet face); A36/1018/1008 or 430 stainless if corrosion matters.")
    print(f"  Drill M4 at {P.N_STATIONS} stations × 2/rail; SLOT all but station {P.ANCHOR_STATION} in X (thermal).")


if __name__ == "__main__":
    cut_list()
