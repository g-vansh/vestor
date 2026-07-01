#!/usr/bin/env python3
"""
preview_card.py — pixel-accurate offline preview of the single-panel flight card.

Runs the REAL scene code (scenes/airlinelogo, journey, flightdetails) against a
mock `rgbmatrix` whose graphics layer renders the actual BDF fonts + SetPixel to
a Pillow image. Lets us verify the 64x32 composition — and show it — on the Mac,
with no panel and no rgbmatrix binding.

Usage:  python3 tools/preview_card.py            # default sample flights
Output: tools/preview_card.png  (frames stacked, scaled up)
"""

import os
import sys
import types

from PIL import Image

HERE = os.path.dirname(os.path.realpath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path.insert(0, ROOT)

WIDTH, HEIGHT = 64, 32


# ----- minimal BDF font -----------------------------------------------------
class BDFFont:
    def __init__(self):
        self.glyphs = {}     # codepoint -> (dwidth, w, h, xoff, yoff, rows[list of int])

    def LoadFont(self, path):
        cp = dwidth = None
        bbx = None
        rows = []
        reading = False
        with open(path, "r", errors="ignore") as f:
            for line in f:
                p = line.split()
                if not p:
                    continue
                if p[0] == "ENCODING":
                    cp = int(p[1])
                elif p[0] == "DWIDTH":
                    dwidth = int(p[1])
                elif p[0] == "BBX":
                    bbx = (int(p[1]), int(p[2]), int(p[3]), int(p[4]))
                elif p[0] == "BITMAP":
                    reading = True
                    rows = []
                elif p[0] == "ENDCHAR":
                    reading = False
                    if cp is not None and bbx is not None:
                        w, h, xoff, yoff = bbx
                        self.glyphs[cp] = (dwidth, w, h, xoff, yoff, rows)
                    cp = dwidth = bbx = None
                elif reading:
                    rows.append(int(p[0], 16))


# ----- mock rgbmatrix.graphics ----------------------------------------------
class Color:
    def __init__(self, r=0, g=0, b=0):
        self.red, self.green, self.blue = int(r), int(g), int(b)


def Font():
    return BDFFont()


def DrawText(canvas, font, x, y, colour, text):
    pen = x
    for ch in text:
        g = font.glyphs.get(ord(ch))
        if g is None:
            pen += 4
            continue
        dwidth, w, h, xoff, yoff, rows = g
        for r, bits in enumerate(rows):
            # bits are left-justified in ceil(w/8)*8; MSB is leftmost column
            nbytes = (w + 7) // 8
            for c in range(w):
                byte_index = c // 8
                bit = (bits >> (nbytes * 8 - 1 - c)) & 1
                if bit:
                    px = pen + xoff + c
                    py = y - yoff - h + r
                    canvas.SetPixel(px, py, colour.red, colour.green, colour.blue)
        pen += dwidth if dwidth is not None else w
    return pen - x


def DrawLine(canvas, x0, y0, x1, y1, colour):
    # Only horizontal/vertical used by the card.
    if x0 == x1:
        for y in range(min(y0, y1), max(y0, y1) + 1):
            canvas.SetPixel(x0, y, colour.red, colour.green, colour.blue)
    elif y0 == y1:
        for x in range(min(x0, x1), max(x0, x1) + 1):
            canvas.SetPixel(x, y0, colour.red, colour.green, colour.blue)


graphics_mod = types.ModuleType("rgbmatrix.graphics")
graphics_mod.Color = Color
graphics_mod.Font = Font
graphics_mod.DrawText = DrawText
graphics_mod.DrawLine = DrawLine

rgbmatrix_mod = types.ModuleType("rgbmatrix")
rgbmatrix_mod.graphics = graphics_mod
sys.modules["rgbmatrix"] = rgbmatrix_mod
sys.modules["rgbmatrix.graphics"] = graphics_mod


# ----- fake canvas ----------------------------------------------------------
class Canvas:
    def __init__(self):
        self.img = Image.new("RGB", (WIDTH, HEIGHT), (0, 0, 0))
        self.px = self.img.load()

    def SetPixel(self, x, y, r, g, b):
        if 0 <= x < WIDTH and 0 <= y < HEIGHT:
            self.px[x, y] = (int(r), int(g), int(b))

    def Clear(self):
        self.img = Image.new("RGB", (WIDTH, HEIGHT), (0, 0, 0))
        self.px = self.img.load()


# ----- import scenes AFTER the mock is installed ----------------------------
from scenes.airlinelogo import AirlineLogoScene       # noqa: E402
from scenes.journey import JourneyScene               # noqa: E402
from scenes.flightdetails import FlightDetailsScene   # noqa: E402
from rgbmatrix import graphics                          # noqa: E402


class Card(AirlineLogoScene, JourneyScene, FlightDetailsScene):
    def __init__(self, data):
        self._data = data
        self._data_index = 0
        self.canvas = Canvas()
        super().__init__()

    def draw_square(self, x0, y0, x1, y1, colour):
        for x in range(x0, x1):
            graphics.DrawLine(self.canvas, x, y0, x, y1, colour)

    def set_pixel(self, x, y, r, g, b):
        self.canvas.SetPixel(x, y, r, g, b)

    def reset_scene(self):
        self.airline_logo_setup()
        self.journey_setup()
        self.flight_details_setup()

    def render_frame(self, frame):
        self.airline_logo(frame)
        self.journey(frame)
        self.flight_details(frame)
        return self.canvas.img.copy()


SAMPLE = [
    {"callsign": "AAL191", "origin": "BOS", "destination": "LAX", "aircraft_code": "A321",
     "plane": "Airbus A321", "altitude": 31000, "vertical_speed": 500},
    {"callsign": "UAL2456", "origin": "SFO", "destination": "BOS", "aircraft_code": "B752",
     "plane": "Boeing 757-200", "altitude": 36000, "vertical_speed": 0},
    {"callsign": "DAL1180", "origin": "BOS", "destination": "ATL", "aircraft_code": "B739",
     "plane": "Boeing 737-900", "altitude": 8000, "vertical_speed": 1400},
    {"callsign": "BAW238", "origin": "LHR", "destination": "BOS", "aircraft_code": "B772",
     "plane": "Boeing 777-200", "altitude": 4200, "vertical_speed": -800},
]


def main():
    frames_to_grab = [0, 4, 10, 40, 90]     # split-flap progressing -> settled + marquee
    scale = 8
    pad = 6
    cols = len(frames_to_grab)
    rows = len(SAMPLE)
    sheet = Image.new(
        "RGB",
        (cols * (WIDTH * scale + pad) + pad, rows * (HEIGHT * scale + pad) + pad),
        (25, 25, 30),
    )
    for ri, flight in enumerate(SAMPLE):
        card = Card([flight])
        card.reset_scene()
        f = 0
        for ci, target in enumerate(frames_to_grab):
            while f <= target:
                img = card.render_frame(f)
                f += 1
            big = img.resize((WIDTH * scale, HEIGHT * scale), Image.NEAREST)
            x = pad + ci * (WIDTH * scale + pad)
            y = pad + ri * (HEIGHT * scale + pad)
            sheet.paste(big, (x, y))
    out = os.path.join(HERE, "preview_card.png")
    sheet.save(out)
    print("wrote", out, sheet.size)


if __name__ == "__main__":
    main()
