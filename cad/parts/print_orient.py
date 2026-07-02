#!/usr/bin/env python3
"""
print_orient.py — export every part PRE-ORIENTED for FDM printing + the fit-test
coupons, into cad/out/print/. Drop these straight into Bambu Studio; they're already
sitting on the bed in the right orientation (the slicer's auto-orient can undo good
choices, so these are placed deliberately). See docs/design/PRINTING.md.

Orientation logic (from the manufacturing research):
  • TALL CLEAT — printed ON ITS SIDE: the YZ cross-section lies flat on the bed and the
    50 mm width runs up Z. The body is a constant-section prism → NO supports, and the
    ~1.5 kg shear runs ALONG the layer lines (a PLA hook this way holds 100+ kg vs ~15 kg
    the weak way). This is the one orientation that matters — do not let the slicer re-lay it.
  • Coupons print fast/cheap to check the real groove fit BEFORE batch-printing.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadquery as cq
import mount_params as P
from util import box, OUT
import top_cleat, anti_swing_foot, corner_enclosure, psu_bracket, gap_hanger

PRINT = os.path.join(OUT, "print"); os.makedirs(PRINT, exist_ok=True)
PLA = 1.24  # g/cm³


def _bed(s):
    """Drop a solid so its min corner sits at the origin, on the bed."""
    bb = s.val().BoundingBox()
    return s.translate((-bb.xmin, -bb.ymin, -bb.zmin))


def out(s, name, qty, supports, note):
    s = _bed(s)
    cq.exporters.export(s, os.path.join(PRINT, f"{name}.stl"))
    bb = s.val().BoundingBox()
    vol_cm3 = s.val().Volume() / 1000.0
    grams = vol_cm3 * PLA * 0.45           # ~45 % (walls + gyroid infill), rough
    fits = "P2S+H2S" if max(bb.xlen, bb.ylen, bb.zlen) <= 250 else "H2S"
    print(f"  {name:26s} {bb.xlen:5.0f}×{bb.ylen:5.0f}×{bb.zlen:5.0f}  "
          f"~{grams*qty:5.0f} g ({qty}×)  {fits:8s} sup:{supports:3s}  {note}")


def render_bed():
    """Iso view of the key parts sitting on a 256 mm (P2S) bed, as printed."""
    import glob, pyvista as pv
    pv.OFF_SCREEN = True
    p = pv.Plotter(off_screen=True, window_size=(1800, 1050)); p.set_background("white")
    bed = pv.Plane(center=(128, 128, -0.6), direction=(0, 0, 1), i_size=256, j_size=256)
    p.add_mesh(bed, color=(0.88, 0.89, 0.92), opacity=0.6, show_edges=True, edge_color=(0.7, 0.7, 0.75))
    layout = [("01_top_cleat", 12, 30), ("00_coupon_tongue", 12, 150),
              ("02_foot_base", 120, 150), ("02_foot_tab", 180, 150), ("06_psu_cradle", 200, 30)]
    for name, x, y in layout:
        f = os.path.join(PRINT, f"{name}.stl")
        if not os.path.exists(f):
            continue
        m = pv.read(f); b = m.bounds
        m = m.translate((x - b[0], y - b[2], -b[4]))
        p.add_mesh(m, color=(0.93, 0.80, 0.52), show_edges=False, ambient=0.4, diffuse=0.75, specular=0.1)
    p.camera_position = [(-140, -220, 300), (128, 120, 15), (0, 0, 1)]
    p.camera.zoom(1.3)
    p.screenshot(os.path.join(OUT, "img", "print_layout.png"))
    print("  wrote print_layout.png")


if __name__ == "__main__":
    print("PRINT-ORIENTED EXPORTS  → cad/out/print/   (bbox mm · est filament · printer · supports)")

    # ── PART 1 · tall cleat — ON ITS SIDE (width up Z) ──────────────────────
    cleat = top_cleat.build().rotate((0, 0, 0), (0, 1, 0), 90)
    out(cleat, "01_top_cleat", 13, "no", "profile flat, 50mm width up → support-free, shear along layers")

    # ── COUPON A · cleat tongue+saddle, 20 mm slice, top 60 mm — GROOVE FIT TEST ──
    coup = top_cleat.build().intersect(box(0, 20, -30, P.FACE_Y, -58, 12)).rotate((0, 0, 0), (0, 1, 0), 90)
    out(coup, "00_coupon_tongue", 1, "no", "TEST FIRST: drops the tongue into the TOP groove")

    # ── PART 2 · anti-swing foot (base + tab). Tab = the BOTTOM-groove coupon ──
    out(anti_swing_foot.build_base(), "02_foot_base", 7, "no", "channel + bolt holes vertical")
    tab = anti_swing_foot.build_tab().rotate((0, 0, 0), (1, 0, 0), 90)   # lay the 50 mm length flat
    out(tab, "02_foot_tab", 7, "no", "COUPON B: slides up into the BOTTOM groove")

    # ── PART 5 · center enclosure (Pi + Bonnet) — backplate flat on the bed ──
    enc = corner_enclosure.build().rotate((0, 0, 0), (1, 0, 0), -90)
    out(enc, "05_center_enclosure", 1, "yes", "Pi+Bonnet tray, hung at wall CENTER; light supports under tongue")

    # ── PART 7 · gap hanger — ON ITS SIDE (profile flat, width up) ──────────
    gh = gap_hanger.build().rotate((0, 0, 0), (0, 1, 0), 90)
    out(gh, "07_gap_hanger", 6, "no", "carries the electronics strip + valance; support-free on-side")

    # ── PART 6 · PSU cradle — as modelled, base on the bed ──────────────────
    out(psu_bracket.build(), "06_psu_cradle", 4, "no", "base down; retaining nubs bridge fine")

    render_bed()
    print("\n  FIRST PRINT = the two coupons (00_ + 02_foot_tab). Check the groove fits,")
    print("  tweak TONGUE_THK / TAB_W clearance in mount_params.py if needed, then batch the rest.")
