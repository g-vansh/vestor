#!/usr/bin/env python3
"""render_pv.py — shaded 3D fit-check renders via PyVista/VTK (offscreen)."""
import os
import pyvista as pv
import model as M

pv.OFF_SCREEN = True
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "img")
os.makedirs(OUT, exist_ok=True)


def _scene(size=(2000, 1150), subset=None):
    pl = pv.Plotter(off_screen=True, window_size=size)
    pl.set_background("white")
    for name, b, c in M.parts():
        if subset and not subset(name, b):
            continue
        box = pv.Box(bounds=(b[0], b[1], b[2], b[3], b[4], b[5]))
        emissive = name.startswith("screen")
        pl.add_mesh(box, color=c, show_edges=True, edge_color=(0.15, 0.15, 0.18),
                    line_width=0.4, ambient=0.5 if emissive else 0.32,
                    diffuse=0.5 if emissive else 0.7, specular=0.08)
    pl.enable_lightkit()
    return pl


def overview():
    pl = _scene((2400, 720))
    pl.camera_position = [(M.ROW_W * 0.30, 5200, 2600),
                          (M.ROW_W * 0.52, 40, -70), (0, 0, 1)]
    pl.camera.zoom(1.5)
    pl.screenshot(os.path.join(OUT, "pv_overview.png")); print("wrote pv_overview.png")


def corner():
    # look into the corner (now at X=ROW_W) from the room so the left-wall face,
    # its grooves, and the hung electronics are visible
    pl = _scene((1500, 1250), subset=lambda n, b: b[1] > M.ROW_W - 950)
    pl.camera_position = [(M.ROW_W - 1550, 1950, 780), (M.ROW_W - 60, 240, -130), (0, 0, 1)]
    pl.camera.zoom(1.2)
    pl.screenshot(os.path.join(OUT, "pv_corner.png")); print("wrote pv_corner.png")


def room_pov():
    """As YOU stand in the room facing the panels: left wall/corner on your LEFT,
    coming toward you; the panel row runs off to your right."""
    xw = M.ROW_W
    pl = _scene((1800, 1150), subset=lambda n, b: b[1] > xw - 1750)
    pl.camera_position = [(xw - 850, 3300, -650), (xw - 250, -200, -230), (0, 0, 1)]
    pl.add_point_labels([(xw - 2, 520, -120)], ["LEFT CORNER (comes toward you)"],
                        font_size=16, text_color="red", shape=None, show_points=False)
    pl.add_point_labels([(xw - 1100, 0, -60)], ["PANEL WALL (in front) →"],
                        font_size=16, text_color="navy", shape=None, show_points=False)
    pl.screenshot(os.path.join(OUT, "pv_room.png")); print("wrote pv_room.png")


if __name__ == "__main__":
    overview()
    corner()
    room_pov()
