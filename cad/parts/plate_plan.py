#!/usr/bin/env python3
"""
plate_plan.py — how many of each part fit per plate on each printer, and the
minimum-overnight batching plan. Pure geometry (grid packing, both rotations).

Beds (usable = nominal minus an edge margin):
  Bambu P2S  256 × 256
  Bambu H2S  340 × 320
"""
EDGE = 10.0    # keep off the bed edge / exclusion zone
GAP = 6.0      # part-to-part spacing
BRIM = 5.0     # per side, when a brim is used

BEDS = {"P2S": (256.0, 256.0), "H2S": (340.0, 320.0)}

# name: (footprint_w, footprint_d, height, qty_needed, qty_with_spares, brim?)
PARTS = [
    ("01_top_cleat",       174, 39,  50, 13, 15, True),
    ("07_gap_hanger",      246, 70,  40,  6,  7, True),
    ("02_foot_base",        40, 40,  25,  7,  9, True),
    ("02_foot_tab",         20, 50,  12,  7, 10, True),
    ("06_psu_cradle",       34, 72,  37,  4,  5, False),
    ("05_center_enclosure", 76,104,  39,  1,  1, False),
    ("08_cable_clip",       22, 18,   5, 40, 50, False),
]


def per_plate(w, d, bed, brim):
    """Max parts on a plate, trying both rotations."""
    BW, BD = bed[0] - 2 * EDGE, bed[1] - 2 * EDGE
    b = BRIM if brim else 0.0
    best = 0
    for pw, pd in ((w, d), (d, w)):
        ew, ed = pw + 2 * b, pd + 2 * b          # effective footprint incl. brim
        if ew > BW or ed > BD:
            continue
        nx = int((BW + GAP) // (ew + GAP))
        ny = int((BD + GAP) // (ed + GAP))
        best = max(best, nx * ny)
    return best


if __name__ == "__main__":
    print(f"{'part':22s} {'size (mm)':16s} {'need':>5s} {'+spare':>6s}   P2S/plate  H2S/plate")
    print("-" * 74)
    fits = {}
    for name, w, d, h, need, spare, brim in PARTS:
        p = per_plate(w, d, BEDS["P2S"], brim)
        hh = per_plate(w, d, BEDS["H2S"], brim)
        fits[name] = (p, hh, spare)
        pf = str(p) if p else "— too big"
        print(f"{name:22s} {f'{w}×{d}×{h}':16s} {need:5d} {spare:6d}   {pf:>9s}  {hh:9d}")

    print("\nPlates needed if a part is run ALONE:")
    for name, w, d, h, need, spare, brim in PARTS:
        p, hh, _ = fits[name]
        pp = f"{-(-spare//p)} plate(s)" if p else "n/a"
        hp = f"{-(-spare//hh)} plate(s)" if hh else "n/a"
        print(f"  {name:22s} P2S: {pp:12s}  H2S: {hp}")
