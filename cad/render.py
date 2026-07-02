#!/usr/bin/env python3
"""
render.py — offscreen PyVista renders of the CAD (run model.py first).
Produces fit-check PNGs: full-row overview, the left corner, and a cross-section.
"""
import glob
import os
import pyvista as pv

from model import OUT, color_for, ROW_W, PANEL_FACE_Y

pv.OFF_SCREEN = True
IMGDIR = os.path.join(OUT, "img")
os.makedirs(IMGDIR, exist_ok=True)


def _meshes():
    out = []
    for stl in sorted(glob.glob(os.path.join(OUT, "*.stl"))):
        name = os.path.splitext(os.path.basename(stl))[0]
        out.append((name, pv.read(stl)))
    return out


def _plotter(size=(1800, 1000)):
    p = pv.Plotter(off_screen=True, window_size=size)
    p.set_background("white")
    p.enable_parallel_projection()
    return p


def add_all(p, clip=None):
    for name, mesh in _meshes():
        m = mesh
        if clip is not None:
            m = m.clip(normal=clip[0], origin=clip[1], invert=clip[2])
            if m.n_points == 0:
                continue
        p.add_mesh(m, color=color_for(name), show_edges=False,
                   smooth_shading=False, ambient=0.3, diffuse=0.7)


def overview():
    p = _plotter((2200, 700))
    add_all(p)
    # look from front-upper-left down the 5 m row
    p.camera_position = [(ROW_W * 0.35, -3800, 2600),
                         (ROW_W * 0.5, PANEL_FACE_Y, -80),
                         (0, 0, 1)]
    p.camera.zoom(1.3)
    p.screenshot(os.path.join(IMGDIR, "overview.png"))
    print("wrote overview.png")


def corner():
    p = _plotter((1500, 1200))
    add_all(p)
    p.camera_position = [(-700, -900, 500),
                         (250, 120, -120),
                         (0, 0, 1)]
    p.screenshot(os.path.join(IMGDIR, "corner.png"))
    print("wrote corner.png")


def section():
    # clip everything to X < 200 mm (through the first panel) and view the profile
    p = _plotter((1300, 1300))
    add_all(p, clip=("x", (200, 0, 0), False))
    p.camera_position = [(1600, PANEL_FACE_Y - 40, -70),  # look along -X
                         (0, PANEL_FACE_Y - 40, -70),
                         (0, 0, 1)]
    p.camera.zoom(2.6)
    p.screenshot(os.path.join(IMGDIR, "section.png"))
    print("wrote section.png")


if __name__ == "__main__":
    overview()
    corner()
    section()
