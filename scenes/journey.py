"""
journey.py — the MIDDLE panel of the 3-panel departure board: the ROUTE.

Full 64x32 panel (canvas cols 64..127):
    rows 0..12    ORIGIN airport code (big, amber), split-flap flip-in
    rows 13..18   a plane sprite flying left->right along a dotted track
    rows 19..31   DESTINATION code (big, amber; home airport brighter + bold)

The stacked layout reads top-to-bottom as "from -> to" and lets the codes be
large. Coordinates are canvas-absolute; the panel draw-offset is applied by the
Display (set_pixel / shimmed DrawText).
"""

from utilities.animator import Animator
from setup import colours, fonts, screen, airlines

from rgbmatrix import graphics

try:
    from config import JOURNEY_CODE_SELECTED
except (ModuleNotFoundError, NameError, ImportError):
    JOURNEY_CODE_SELECTED = "BOS"

ZONE_X = screen.ZONE_ROUTE        # 64 — middle panel
PANEL_W = screen.PANEL_W          # 64

CHAR_W = 8                        # 8x13 advance
CODE_X = ZONE_X + (PANEL_W - 3 * CHAR_W) // 2   # 3-char code centred in the panel
ORIGIN_BASELINE = 12
DEST_BASELINE = 31

PLANE_TOP = 13                    # plane sprite occupies rows 13..18
TRACK_Y = 16
TRACK_DOT = graphics.Color(120, 78, 0)
CODE_COLOUR = graphics.Color(255, 176, 0)          # sodium amber
CODE_HOME_COLOUR = graphics.Color(255, 214, 120)   # warm pop for home airport

# A right-flying jet in profile (15 wide x 6 tall), pixels as (dx, dy). A TWO-row
# fuselage (not a 1px stick) + a raised tail fin + a swept wing read as a real
# airliner. Fuselage sits on dy=2..3 so its belly rides the dotted track.
#    . # # . . . . . . . . . . . .     tail fin
#    . # # . . . . . . . . . . . .     tail fin base
#    # # # # # # # # # # # # # # #     fuselage top (nose at right)
#    # # # # # # # # # # # # # # .     fuselage belly (on the track)
#    . . . . . # # # . . . . . . .     wing
#    . . . . # # # . . . . . . . .     wing (swept down-aft)
PLANE = [
    (1, 0), (2, 0),
    (1, 1), (2, 1),
    (0, 2), (1, 2), (2, 2), (3, 2), (4, 2), (5, 2), (6, 2), (7, 2),
    (8, 2), (9, 2), (10, 2), (11, 2), (12, 2), (13, 2), (14, 2),
    (0, 3), (1, 3), (2, 3), (3, 3), (4, 3), (5, 3), (6, 3), (7, 3),
    (8, 3), (9, 3), (10, 3), (11, 3), (12, 3), (13, 3),
    (5, 4), (6, 4), (7, 4),
    (4, 5), (5, 5), (6, 5),
]
NOSE = (14, 2)                    # brightened nose tip
PLANE_W = 15
PLANE_SPEED = 0.7                 # px/frame

# Split-flap
FLAP_GLYPHS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
FLAP_STAGGER = 2
FLAP_SETTLE = 9


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
        self._plane_x = float(ZONE_X)
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
        self._flap_frame = 0
        self._plane_x = float(ZONE_X)

    def _cell_glyph(self, target, cell_index):
        local = self._flap_frame - cell_index * FLAP_STAGGER
        if local < 0:
            return None
        if local >= FLAP_SETTLE or target == " ":
            return target
        return FLAP_GLYPHS[(local * 2 + cell_index) % len(FLAP_GLYPHS)]

    def _draw_code(self, code, baseline, cell_start):
        home = code.strip() == JOURNEY_CODE_SELECTED
        font = fonts.large_bold if home else fonts.large
        colour = CODE_HOME_COLOUR if home else CODE_COLOUR
        for j, target in enumerate(code):
            glyph = self._cell_glyph(target, cell_start + j)
            if glyph and glyph != " ":
                graphics.DrawText(self.canvas, font, CODE_X + j * CHAR_W, baseline,
                                  colour, glyph)

    def _draw_plane(self):
        # dotted track
        for x in range(ZONE_X + 2, ZONE_X + PANEL_W - 2, 4):
            self.set_pixel(x, TRACK_Y, TRACK_DOT.red, TRACK_DOT.green, TRACK_DOT.blue)
        # the plane (brand colour) sweeping origin -> destination, looping
        span = PANEL_W - PLANE_W - 2
        px = ZONE_X + 1 + int((self._plane_x - ZONE_X) % span)
        for (dx, dy) in PLANE:
            self.set_pixel(px + dx, PLANE_TOP + dy,
                           self._route_brand.red, self._route_brand.green, self._route_brand.blue)
        # brighter nose tip — a bit of forward "glint" so direction reads clearly
        self.set_pixel(px + NOSE[0], PLANE_TOP + NOSE[1],
                       min(255, self._route_brand.red + 90),
                       min(255, self._route_brand.green + 90),
                       min(255, self._route_brand.blue + 90))
        self._plane_x += PLANE_SPEED

    @Animator.KeyFrame.add(1)
    def journey(self, count):
        if len(self._data) == 0:
            return

        self.draw_square(ZONE_X, 0, ZONE_X + PANEL_W, screen.HEIGHT, colours.BLACK)

        self._draw_code(self._origin_cells, ORIGIN_BASELINE, 0)
        self._draw_plane()
        self._draw_code(self._dest_cells, DEST_BASELINE, 3)

        self._flap_frame += 1
