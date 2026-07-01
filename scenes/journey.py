"""
journey.py — the ROUTE band of the flight card (rows 13..25).

    BOS  · · ✈ · ·  JFK

  * Origin (left) and destination (right) IATA codes in Solari amber, the
    "home" airport (config JOURNEY_CODE_SELECTED) brighter + bold.
  * On every scene reset the codes SPLIT-FLAP in: each character cell rolls
    through a scramble of glyphs, staggered left-to-right, then locks on target
    — the departure-board flourish.
  * Between the codes, a brand-colour marker chases along a dotted track from
    origin toward destination on a loop — "en route".

Coordinates are panel-relative; the Pi's Port-3 shim adds the +64 lane offset.
"""

from utilities.animator import Animator
from setup import colours, fonts, screen, airlines

from rgbmatrix import graphics

try:
    from config import JOURNEY_CODE_SELECTED
except (ModuleNotFoundError, NameError, ImportError):
    JOURNEY_CODE_SELECTED = "BOS"

# Band geometry
ROUTE_BASELINE = 24        # 8x13 baseline; glyphs sit ~rows 13..24
ROUTE_CLEAR_TOP = 13
ROUTE_CLEAR_BOT = 26
CHAR_W = 8                 # 8x13 advance
ORIGIN_X = 1               # cells at 1, 9, 17
DEST_X = screen.WIDTH - 3 * CHAR_W   # cells at 40, 48, 56

# Chase track (the gap between the two codes)
TRACK_Y = 20
TRACK_X0 = ORIGIN_X + 3 * CHAR_W + 1   # ~26
TRACK_X1 = DEST_X - 2                   # ~38
TRACK_DOT_COLOUR = graphics.Color(120, 78, 0)     # dim amber ties
CHASE_PERIOD = 26          # frames per origin->dest sweep

# Colours
CODE_COLOUR = graphics.Color(255, 176, 0)         # sodium amber
CODE_HOME_COLOUR = graphics.Color(255, 214, 120)  # warm pop for home airport

# Split-flap
FLAP_GLYPHS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
FLAP_STAGGER = 2           # frames between adjacent cells starting to flip
FLAP_SETTLE = 9            # flips before a cell locks on its target


def _pad3(code):
    code = (code or "").upper()
    if not code:
        return "???"
    return (code[:3] + "   ")[:3]


class JourneyScene(object):
    def __init__(self):
        self._origin_cells = "???"
        self._dest_cells = "???"
        self._flap_frame = 0
        self._chase = 0
        self._route_brand = colours.WHITE
        super().__init__()

    @Animator.KeyFrame.add(0)
    def journey_setup(self):
        if len(self._data) == 0:
            return
        flight = self._data[self._data_index]
        self._origin_cells = _pad3(flight.get("origin"))
        self._dest_cells = _pad3(flight.get("destination"))
        br, bg, bb = airlines.brand_for_callsign(flight.get("callsign", ""))
        self._route_brand = graphics.Color(br, bg, bb)
        # Re-arm the split-flap.
        self._flap_frame = 0

    def _cell_glyph(self, target, cell_index):
        """Which glyph to show for a flapping cell this frame."""
        local = self._flap_frame - cell_index * FLAP_STAGGER
        if local < 0:
            return None                       # not started — blank
        if local >= FLAP_SETTLE or target == " ":
            return target                     # locked on target
        return FLAP_GLYPHS[(local * 2 + cell_index) % len(FLAP_GLYPHS)]

    def _draw_code(self, code, base_x, cell_start):
        home = code.strip() == JOURNEY_CODE_SELECTED
        font = fonts.large_bold if home else fonts.large
        colour = CODE_HOME_COLOUR if home else CODE_COLOUR
        for j, target in enumerate(code):
            glyph = self._cell_glyph(target, cell_start + j)
            if glyph and glyph != " ":
                graphics.DrawText(
                    self.canvas, font, base_x + j * CHAR_W, ROUTE_BASELINE, colour, glyph
                )

    def _draw_chase(self):
        # Dotted journey track.
        for x in range(TRACK_X0, TRACK_X1 + 1, 3):
            self.canvas.SetPixel(
                x, TRACK_Y, TRACK_DOT_COLOUR.red, TRACK_DOT_COLOUR.green, TRACK_DOT_COLOUR.blue
            )
        # Brand-colour marker sweeping origin -> destination.
        span = max(1, TRACK_X1 - TRACK_X0)
        pos = (self._chase % CHASE_PERIOD) / CHASE_PERIOD
        mx = TRACK_X0 + int(pos * span)
        self.canvas.SetPixel(
            mx, TRACK_Y, self._route_brand.red, self._route_brand.green, self._route_brand.blue
        )
        if mx - 1 >= TRACK_X0:                # short comet trail
            self.canvas.SetPixel(mx - 1, TRACK_Y, self._route_brand.red // 3,
                                 self._route_brand.green // 3, self._route_brand.blue // 3)
        self._chase += 1

    @Animator.KeyFrame.add(1)
    def journey(self, count):
        if len(self._data) == 0:
            return

        # Clear the route band.
        self.draw_square(0, ROUTE_CLEAR_TOP, screen.WIDTH, ROUTE_CLEAR_BOT, colours.BLACK)

        self._draw_code(self._origin_cells, ORIGIN_X, 0)
        self._draw_code(self._dest_cells, DEST_X, 3)
        self._draw_chase()

        self._flap_frame += 1
