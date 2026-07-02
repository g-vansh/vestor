"""
flightdetails.py — the RIGHT panel of the 3-panel departure board: TELEMETRY.

Full 64x32 panel (canvas cols 128..191):
    rows 0..7    callsign + a status tag: down-green ARR / up-amber DEP
    rows 9..21   altitude, big, coloured by FR24's altitude ramp
    rows 23..30  ground speed + aircraft type
    right edge   a vertical altitude gauge (bar height ∝ altitude)

If the transponder squawks an emergency code (7500/7600/7700) the panel turns
into a flashing red alert. Status/arriving-departing is derived from BOS being
the origin/destination and the climb/descent.
"""

from utilities.animator import Animator
from setup import colours, fonts, screen, airlines

from rgbmatrix import graphics

try:
    from config import JOURNEY_CODE_SELECTED as HOME
except (ModuleNotFoundError, NameError, ImportError):
    HOME = "BOS"

try:
    from setup.frames import PER_SECOND
except (ModuleNotFoundError, ImportError):
    PER_SECOND = 20

ZONE_X = screen.ZONE_TELEM        # 128 — right panel
PANEL_W = screen.PANEL_W          # 64

CALLSIGN_BASELINE = 7
ALT_BASELINE = 21
INFO_BASELINE = 30

CALLSIGN_COLOUR = graphics.Color(250, 240, 215)   # warm white
TYPE_COLOUR = graphics.Color(120, 200, 235)        # soft cyan
SPEED_COLOUR = graphics.Color(150, 150, 150)       # dim grey
ARRIVE_COLOUR = graphics.Color(60, 220, 90)        # green
DEPART_COLOUR = graphics.Color(255, 176, 0)        # amber
ALERT_COLOUR = graphics.Color(255, 40, 40)         # red

VS_THRESHOLD = 200
GAUGE_X0 = ZONE_X + PANEL_W - 4
GAUGE_X1 = ZONE_X + PANEL_W - 3
GAUGE_TOP = 3
GAUGE_BOT = 30
GAUGE_MAX = 40000.0

EMERGENCY = {"7500": "HIJACK", "7600": "NORDO", "7700": "EMERG"}


class FlightDetailsScene(object):
    def __init__(self):
        self._tele_frame = 0
        super().__init__()

    def _measure(self, font, text):
        return graphics.DrawText(self.canvas, font, screen.WIDTH + 60,
                                 CALLSIGN_BASELINE, colours.BLACK, text)

    def _status(self, flight):
        """(-> label, colour, arrow_up) for arriving/departing HOME, or None."""
        o, d = flight.get("origin"), flight.get("destination")
        vs = flight.get("vertical_speed") or 0
        if d == HOME or vs < -VS_THRESHOLD:
            return ("ARR", ARRIVE_COLOUR, False)
        if o == HOME or vs > VS_THRESHOLD:
            return ("DEP", DEPART_COLOUR, True)
        return None

    def _arrow(self, x, y, up, colour):
        if up:
            pts = [(x + 1, y), (x, y + 1), (x + 1, y + 1), (x + 2, y + 1),
                   (x, y + 2), (x + 2, y + 2)]
        else:
            pts = [(x, y), (x + 2, y), (x, y + 1), (x + 1, y + 1),
                   (x + 2, y + 1), (x + 1, y + 2)]
        for (px, py) in pts:
            self.set_pixel(px, py, colour.red, colour.green, colour.blue)

    def _draw_gauge(self, altitude):
        alt = max(0, min(GAUGE_MAX, altitude or 0))
        fill = int((GAUGE_BOT - GAUGE_TOP) * (alt / GAUGE_MAX))
        r, g, b = airlines.altitude_colour(alt)
        for y in range(GAUGE_TOP, GAUGE_BOT + 1):
            lit = y >= (GAUGE_BOT - fill)
            for gx in (GAUGE_X0, GAUGE_X1):
                if lit:
                    self.set_pixel(gx, y, r, g, b)
                else:
                    self.set_pixel(gx, y, 26, 26, 26)

    def _draw_emergency(self, flight):
        code = flight.get("squawk", "")
        flash = (self._tele_frame // (PER_SECOND // 3)) % 2 == 0
        c = ALERT_COLOUR if flash else graphics.Color(90, 0, 0)
        graphics.DrawText(self.canvas, fonts.small, ZONE_X + 2, 10, c, "! ALERT")
        graphics.DrawText(self.canvas, fonts.large_bold, ZONE_X + 2, 24, c, code)
        graphics.DrawText(self.canvas, fonts.small, ZONE_X + 2, 31, c,
                          EMERGENCY.get(code, ""))

    @Animator.KeyFrame.add(1)
    def flight_details(self, count):
        if len(self._data) == 0:
            return

        flight = self._data[self._data_index]
        self.draw_square(ZONE_X, 0, ZONE_X + PANEL_W, screen.HEIGHT, colours.BLACK)
        self._tele_frame += 1

        # Emergency squawk -> flashing alert takes over the panel.
        if flight.get("squawk") in EMERGENCY:
            self._draw_emergency(flight)
            return

        # Callsign (top-left).
        callsign = flight.get("callsign") or ""
        if callsign:
            graphics.DrawText(self.canvas, fonts.small, ZONE_X + 2, CALLSIGN_BASELINE,
                              CALLSIGN_COLOUR, callsign)

        # Status tag (top-right): down-green ARR / up-amber DEP.
        st = self._status(flight)
        if st:
            label, colour, up = st
            w = self._measure(fonts.small, label)
            tx = GAUGE_X0 - 2 - w
            self._arrow(tx - 5, 1, up, colour)
            graphics.DrawText(self.canvas, fonts.small, tx, CALLSIGN_BASELINE, colour, label)

        # Altitude (big) + gauge.
        alt = flight.get("altitude") or 0
        alt_text = f"FL{round(alt / 100):03d}" if alt >= 18000 else f"{int(alt)}'"
        graphics.DrawText(self.canvas, fonts.large, ZONE_X + 2, ALT_BASELINE,
                          graphics.Color(*airlines.altitude_colour(alt)), alt_text)
        self._draw_gauge(alt)

        # Ground speed + aircraft type.
        gs = flight.get("ground_speed")
        if gs:
            graphics.DrawText(self.canvas, fonts.small, ZONE_X + 2, INFO_BASELINE,
                              SPEED_COLOUR, f"{gs}KT")
        type_text = flight.get("aircraft_code") or ""
        if type_text:
            w = self._measure(fonts.small, type_text)
            graphics.DrawText(self.canvas, fonts.small, GAUGE_X0 - 2 - w, INFO_BASELINE,
                              TYPE_COLOUR, type_text.upper())

        # Pulsing LIVE heartbeat (bottom-right corner) — data is live.
        b = 1.0 - abs((self._tele_frame % PER_SECOND) - PER_SECOND / 2) / (PER_SECOND / 2)
        self.set_pixel(ZONE_X + PANEL_W - 1, screen.HEIGHT - 1,
                       0, int(70 + 170 * b), int(25 * b))
