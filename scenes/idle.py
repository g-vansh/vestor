"""
idle.py — the 3-panel screen shown when NO aircraft is overhead.

    LEFT   day / date / year
    MIDDLE big clock (HH:MM, blinking colon) + "SCANNING" + a radar sweep
    RIGHT  weather: icon + condition, big °F, °C + wind

A dim radar line sweeps left->right across all three panels behind everything,
so the wall reads as "scanning the sky" while it waits for a plane. Weather is
from setup.weather (Open-Meteo, no key) in BOTH °C and °F.
"""

from datetime import datetime

from utilities.animator import Animator
from setup import colours, fonts, screen, weather

from rgbmatrix import graphics

try:
    from setup.frames import PER_SECOND
except (ModuleNotFoundError, ImportError):
    PER_SECOND = 20

LEFT_X = screen.ZONE_AIRLINE
MID_X = screen.ZONE_ROUTE
RIGHT_X = screen.ZONE_TELEM
PANEL_W = screen.PANEL_W

WHITE = graphics.Color(235, 235, 235)
AMBER = graphics.Color(255, 176, 0)
GREY = graphics.Color(150, 150, 150)
CYAN = graphics.Color(120, 200, 235)
RADAR = graphics.Color(0, 90, 45)
RADAR_EDGE = graphics.Color(40, 200, 110)

# Small weather icons (~11x9). Any non ' '/'.' char = lit.
_ICON_ART = {
    "sun": """
....#....
.#.....#.
...###...
..#####..
.#######.
..#####..
...###...
.#.....#.
....#....""",
    "partly": """
..#........
...#.###...
.#..#####..
...#######.
..###...###
..#########
...#######.""",
    "cloud": """
...#####...
.#########.
###.....###
###########
.#########.""",
    "rain": """
...#####...
.#########.
###########
.#########.
...........
.#..#..#...
#..#..#....
.#..#..#...""",
    "snow": """
...#####...
.#########.
###########
.#########.
...........
.#.#.#.#...
..#.#.#....
.#.#.#.#...""",
    "storm": """
...#####...
.#########.
###########
.#########.
.....#.....
....##.....
...####....
.....#.....""",
    "fog": """
###########
...........
.#########.
...........
###########
...........
.#########.""",
}


def _parse(art):
    rows = [r for r in art.split("\n") if r]
    px = [(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch not in " ."]
    return px, (max(len(r) for r in rows) if rows else 0), len(rows)


_ICONS = {k: _parse(v) for k, v in _ICON_ART.items()}


class IdleScene(object):
    def __init__(self):
        self._idle_frame = 0
        super().__init__()

    def _blit(self, px, x0, y0, colour):
        for (x, y) in px:
            self.set_pixel(x0 + x, y0 + y, colour.red, colour.green, colour.blue)

    def _text_w(self, font, text):
        return graphics.DrawText(self.canvas, font, screen.WIDTH + 60, 10,
                                 colours.BLACK, text)

    def _centered(self, font, text, zone_x, baseline, colour):
        w = self._text_w(font, text)
        graphics.DrawText(self.canvas, font, zone_x + max(0, (PANEL_W - w) // 2),
                          baseline, colour, text)

    def _draw_radar(self):
        span = screen.WIDTH
        pos = int(self._idle_frame * 1.4) % span
        for y in range(screen.HEIGHT):
            self.set_pixel(pos, y, RADAR.red, RADAR.green, RADAR.blue)
        if pos + 1 < span:
            for y in range(screen.HEIGHT):
                self.set_pixel(pos + 1, y, RADAR_EDGE.red, RADAR_EDGE.green, RADAR_EDGE.blue)

    def _draw_date(self):
        now = datetime.now()
        self._centered(fonts.large_bold, now.strftime("%a").upper(), LEFT_X, 13, AMBER)
        self._centered(fonts.small, now.strftime("%b %d").upper(), LEFT_X, 23, WHITE)
        self._centered(fonts.small, now.strftime("%Y"), LEFT_X, 31, GREY)

    def _draw_clock(self):
        now = datetime.now()
        hh, mm = now.strftime("%H"), now.strftime("%M")
        # HH and MM in the big font, with a blinking colon between.
        total = 20 + 4 + 20
        x0 = MID_X + (PANEL_W - total) // 2
        graphics.DrawText(self.canvas, fonts.huge, x0, 22, WHITE, hh)
        graphics.DrawText(self.canvas, fonts.huge, x0 + 24, 22, WHITE, mm)
        if (self._idle_frame // (PER_SECOND // 2)) % 2 == 0:
            cx = x0 + 21
            for cy in (8, 9, 16, 17):
                self.set_pixel(cx, cy, WHITE.red, WHITE.green, WHITE.blue)
                self.set_pixel(cx + 1, cy, WHITE.red, WHITE.green, WHITE.blue)
        # scanning label + a pulsing blip
        blip = (self._idle_frame // 6) % 2
        c = RADAR_EDGE if blip else RADAR
        self.set_pixel(MID_X + 12, 29, c.red, c.green, c.blue)
        graphics.DrawText(self.canvas, fonts.extrasmall, MID_X + 15, 31, GREY, "SCANNING")

    def _draw_weather(self):
        w = weather.current()
        if not w:
            self._centered(fonts.small, "WEATHER", RIGHT_X, 14, GREY)
            self._centered(fonts.extrasmall, "LOADING", RIGHT_X, 24, GREY)
            return
        px, iw, ih = _ICONS.get(w["category"], _ICONS["cloud"])
        self._blit(px, RIGHT_X + 3, 1, AMBER if w["category"] in ("sun", "partly") else CYAN)
        graphics.DrawText(self.canvas, fonts.small, RIGHT_X + 16, 10, WHITE, w["condition"])
        # big Fahrenheit
        f_txt = f"{w['temp_f']}°F"
        graphics.DrawText(self.canvas, fonts.large_bold, RIGHT_X + 2, 24, AMBER, f_txt)
        # celsius + wind
        graphics.DrawText(self.canvas, fonts.small, RIGHT_X + 2, 31, CYAN,
                          f"{w['temp_c']}°C")
        graphics.DrawText(self.canvas, fonts.small, RIGHT_X + 34, 31, GREY,
                          f"{w['wind_mph']}MPH")

    @Animator.KeyFrame.add(1)
    def idle_screen(self, count):
        # Only when no aircraft is overhead.
        if len(self._data) != 0:
            return
        self.draw_square(0, 0, screen.WIDTH, screen.HEIGHT, colours.BLACK)
        self._draw_radar()
        self._draw_date()
        self._draw_clock()
        self._draw_weather()
        self._idle_frame += 1
