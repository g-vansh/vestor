#!/usr/bin/env python3
"""
preview_transition.py — offline filmstrip of the plane-swoop transition.

Renders a real OLD frame and NEW frame with the same software renderer used by
preview_card, then composites the swoop across them (NEW left of the plane, OLD
right) exactly as the live TransitionMixin does. Output: a vertical filmstrip
per scenario so we can judge the motion + plane art with no hardware.

    python3 tools/preview_transition.py
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))

# importing preview_card installs the mock rgbmatrix + gives us Card/Canvas
import tools.preview_card as pc                                  # noqa: E402
from utilities.transition import draw_swoop, TRANS_FRAMES, PLANE_LEN, _ease  # noqa: E402

W, H = pc.WIDTH, pc.HEIGHT


def render(flight, settle=70):
    card = pc.Card([flight] if flight else [])
    card.reset_scene()
    img = None
    for f in range(settle + 1):
        img = card.render_frame(f)
    return img.copy()


def composite(old_img, new_img, nose, edge):
    comp = Image.new("RGB", (W, H))
    op, np_, cp = old_img.load(), new_img.load(), comp.load()
    for y in range(H):
        for x in range(W):
            cp[x, y] = np_[x, y] if x < edge else op[x, y]
    draw_swoop(lambda x, y, r, g, b: cp.__setitem__((x, y), (r, g, b)),
               nose, edge, 0, TRANS_FRAMES)
    return comp


def filmstrip(old_img, new_img, samples=8, scale=5, pad=6):
    idxs = [round(1 + i * (TRANS_FRAMES - 1) / (samples - 1)) for i in range(samples)]
    sheet = Image.new("RGB", (W * scale + 2 * pad,
                              samples * (H * scale + pad) + pad), (22, 22, 28))
    for row, f in enumerate(idxs):
        p = f / TRANS_FRAMES
        nose = _ease(p) * (W + PLANE_LEN)
        edge = max(0, min(W, int(round(nose))))
        frame = composite(old_img, new_img, nose, edge)
        big = frame.resize((W * scale, H * scale), Image.NEAREST)
        sheet.paste(big, (pad, pad + row * (H * scale + pad)))
    return sheet


FA = {"callsign": "AAL909", "origin": "BOS", "destination": "DFW", "aircraft_code": "B738",
      "altitude": 4200, "vertical_speed": 1400, "ground_speed": 280, "squawk": "1200"}
FB = {"callsign": "JBU671", "origin": "JFK", "destination": "BOS", "aircraft_code": "A320",
      "altitude": 2600, "vertical_speed": -900, "ground_speed": 240, "squawk": "1200"}


def main():
    idle = render(None)
    fa = render(FA)
    fb = render(FB)
    out = "/private/tmp/claude-502/-Users-vansh-Documents-LED-Screen-Project/712beab6-2b0d-44da-a2d4-af92976ca4ec/scratchpad"
    filmstrip(idle, fa).save(f"{out}/swoop_idle_to_flight.png")
    filmstrip(fa, fb).save(f"{out}/swoop_flight_to_flight.png")
    print("wrote swoop_idle_to_flight.png + swoop_flight_to_flight.png")


if __name__ == "__main__":
    main()
