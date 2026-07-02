"""
flightdetails.py — the RIGHT panel of the 3-panel departure board: TELEMETRY.

Full 64x32 panel (canvas cols 128..191):
    rows 1..8    callsign (warm white) + N/M index when several are overhead
    rows 11..22  altitude, big, coloured by FR24's altitude ramp, with a
                 climb / descent / level marker to its left
    rows 25..31  aircraft type (cyan)
    right edge   a vertical altitude gauge (bar height ∝ altitude, FR24-coloured)

Everything is drawn with DrawText / set_pixel; coordinates are canvas-absolute.
"""

from utilities.animator import Animator
from setup import colours, fonts, screen, airlines

from rgbmatrix import graphics

ZONE_X = screen.ZONE_TELEM        # 128 — right panel
PANEL_W = screen.PANEL_W          # 64

CALLSIGN_BASELINE = 8
ALT_BASELINE = 22
TYPE_BASELINE = 31

CALLSIGN_COLOUR = graphics.Color(250, 240, 215)   # warm white
TYPE_COLOUR = graphics.Color(120, 200, 235)        # soft cyan
INDEX_COLOUR = graphics.Color(150, 150, 150)       # dim grey
CLIMB_COLOUR = graphics.Color(60, 220, 90)
DESCEND_COLOUR = graphics.Color(255, 80, 80)
LEVEL_COLOUR = graphics.Color(150, 150, 150)
VS_THRESHOLD = 64                 # fpm; below this magnitude = "level"

# Vertical altitude gauge (right edge of the panel)
GAUGE_X0 = ZONE_X + PANEL_W - 4
GAUGE_X1 = ZONE_X + PANEL_W - 3
GAUGE_TOP = 3
GAUGE_BOT = 30
GAUGE_MAX = 40000.0               # ft mapped to full-height bar


class FlightDetailsScene(object):
    def __init__(self):
        super().__init__()

    def _measure(self, font, text):
        return graphics.DrawText(self.canvas, font, screen.WIDTH + 60,
                                 CALLSIGN_BASELINE, colours.BLACK, text)

    def _draw_vs(self, x, ytop, vertical_speed):
        """3px climb/descent/level marker at rows ytop..ytop+4."""
        vs = vertical_speed or 0
        if vs > VS_THRESHOLD:
            c = CLIMB_COLOUR
            pts = [(x + 1, ytop), (x, ytop + 1), (x + 1, ytop + 1), (x + 2, ytop + 1),
                   (x, ytop + 2), (x + 2, ytop + 2)]
        elif vs < -VS_THRESHOLD:
            c = DESCEND_COLOUR
            pts = [(x, ytop + 2), (x + 2, ytop + 2), (x, ytop + 3), (x + 1, ytop + 3),
                   (x + 2, ytop + 3), (x + 1, ytop + 4)]
        else:
            c = LEVEL_COLOUR
            pts = [(x, ytop + 2), (x + 1, ytop + 2), (x + 2, ytop + 2)]
        for (px, py) in pts:
            self.set_pixel(px, py, c.red, c.green, c.blue)

    def _draw_gauge(self, altitude):
        alt = max(0, min(GAUGE_MAX, altitude or 0))
        span = GAUGE_BOT - GAUGE_TOP
        fill = int(span * (alt / GAUGE_MAX))
        r, g, b = airlines.altitude_colour(alt)
        for y in range(GAUGE_TOP, GAUGE_BOT + 1):
            filled = y >= (GAUGE_BOT - fill)
            for gx in (GAUGE_X0, GAUGE_X1):
                if filled:
                    self.set_pixel(gx, y, r, g, b)
                else:
                    self.set_pixel(gx, y, 26, 26, 26)   # dim track

    @Animator.KeyFrame.add(1)
    def flight_details(self, count):
        if len(self._data) == 0:
            return

        flight = self._data[self._data_index]
        self.draw_square(ZONE_X, 0, ZONE_X + PANEL_W, screen.HEIGHT, colours.BLACK)

        # Callsign (top-left).
        callsign = flight.get("callsign") or ""
        if callsign:
            graphics.DrawText(self.canvas, fonts.small, ZONE_X + 2, CALLSIGN_BASELINE,
                              CALLSIGN_COLOUR, callsign)

        # N/M index (top-right, before the gauge) when several aircraft overhead.
        if len(self._data) > 1:
            idx = f"{self._data_index + 1}/{len(self._data)}"
            w = self._measure(fonts.extrasmall, idx)
            graphics.DrawText(self.canvas, fonts.extrasmall, GAUGE_X0 - 2 - w,
                              CALLSIGN_BASELINE - 2, INDEX_COLOUR, idx)

        # Altitude (big) + climb/descent marker.
        alt = flight.get("altitude") or 0
        if alt >= 18000:
            alt_text = f"FL{round(alt / 100):03d}"
        else:
            alt_text = f"{int(alt)}'"
        alt_colour = graphics.Color(*airlines.altitude_colour(alt))
        self._draw_vs(ZONE_X + 1, ALT_BASELINE - 15, flight.get("vertical_speed"))
        graphics.DrawText(self.canvas, fonts.large, ZONE_X + 7, ALT_BASELINE,
                          alt_colour, alt_text)

        # Aircraft type (bottom).
        type_text = flight.get("aircraft_code") or (flight.get("plane") or "")[:9]
        if type_text:
            graphics.DrawText(self.canvas, fonts.small, ZONE_X + 2, TYPE_BASELINE,
                              TYPE_COLOUR, type_text.upper())

        # Vertical altitude gauge (right edge).
        self._draw_gauge(alt)
