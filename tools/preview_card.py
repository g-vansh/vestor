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

# screen.py only imports config (no rgbmatrix), so it's safe to read the real
# canvas geometry (192x32 for the 3-panel board) before the mock is installed.
from setup import screen  # noqa: E402
WIDTH, HEIGHT = screen.WIDTH, screen.HEIGHT


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
from scenes.idle import IdleScene                       # noqa: E402
from setup import weather as _weather                   # noqa: E402
from rgbmatrix import graphics                          # noqa: E402

# Mock weather so the idle preview shows conditions (no network / thread).
_weather._state = {"temp_c": 22, "temp_f": 72, "code": 2, "condition": "PARTLY",
                   "category": "partly", "wind_mph": 8, "is_day": True}


class Card(AirlineLogoScene, JourneyScene, FlightDetailsScene, IdleScene):
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

    def render_frame(self, frame):
        self.airline_logo(frame)
        self.journey(frame)
        self.flight_details(frame)
        self.idle_screen(frame)
        return self.canvas.img.copy()


SAMPLE = [
    {"callsign": "JBU353", "origin": "JFK", "destination": "BOS", "aircraft_code": "A21N",
     "altitude": 4200, "vertical_speed": -900, "ground_speed": 280, "squawk": "3412"},
    {"callsign": "DAL1180", "origin": "BOS", "destination": "ATL", "aircraft_code": "B739",
     "altitude": 8000, "vertical_speed": 1400, "ground_speed": 340, "squawk": "1200"},
    {"callsign": "RPA5689", "origin": "ORD", "destination": "BOS", "aircraft_code": "E75L",
     "altitude": 525, "vertical_speed": -600, "ground_speed": 140, "squawk": "6521"},
    {"callsign": "N247NE", "origin": "", "destination": "", "aircraft_code": "EC45",
     "altitude": 850, "vertical_speed": -200, "ground_speed": 90, "squawk": "1200"},
    {"callsign": "SWA88", "origin": "MDW", "destination": "BOS", "aircraft_code": "B738",
     "altitude": 3200, "vertical_speed": -1100, "ground_speed": 210, "squawk": "7700"},
    {"callsign": "N512SR", "origin": "", "destination": "", "aircraft_code": "SR22",
     "altitude": 6500, "vertical_speed": 700, "ground_speed": 150, "squawk": "1200"},
]


def _add_panel_dividers(img, scale):
    """Faint vertical lines at the 64px panel seams, for orientation."""
    px = img.load()
    for seam in range(64, WIDTH, 64):
        x = seam * scale
        for y in range(img.size[1]):
            if x < img.size[0]:
                px[x, y] = (70, 70, 80)
    return img


def main():
    scale = 6
    pad = 8
    # --- idle screen (no aircraft overhead) ---
    idle = Card([])
    idle.reset_scene()
    isheet = Image.new("RGB", (3 * (WIDTH * scale + pad) + pad, HEIGHT * scale + 2 * pad),
                       (25, 25, 30))
    f = 0
    for ci, target in enumerate([4, 14, 44]):
        while f <= target:
            iimg = idle.render_frame(f)
            f += 1
        big = iimg.resize((WIDTH * scale, HEIGHT * scale), Image.NEAREST)
        _add_panel_dividers(big, scale)
        isheet.paste(big, (pad + ci * (WIDTH * scale + pad), pad))
    isheet.save(os.path.join(HERE, "preview_idle.png"))
    print("wrote idle preview")

    frames_to_grab = [3, 14, 70]            # split-flap progressing -> settled
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
            _add_panel_dividers(big, scale)
            x = pad + ci * (WIDTH * scale + pad)
            y = pad + ri * (HEIGHT * scale + pad)
            sheet.paste(big, (x, y))
    out = os.path.join(HERE, "preview_card.png")
    sheet.save(out)
    print("wrote", out, sheet.size)


if __name__ == "__main__":
    main()
