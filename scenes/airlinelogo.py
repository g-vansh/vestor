"""
airlinelogo.py — the LEFT panel of the 3-panel departure board: AIRLINE identity.

Full 64x32 panel (canvas cols 0..63):
    rows 1..22   the airline logo, fit WHOLE + static, centred
    row  23      a brand-colour livery rule across the panel
    rows 25..31  the airline name (curated) in the brand colour, centred

Pixel writes go through self.set_pixel (Display) which applies the panel
draw-offset; text via the shimmed DrawText/DrawLine. Coordinates are canvas-
absolute (col 0..63 = the physical left panel, per the 2026-07-01 calibration).
"""

from utilities.animator import Animator
from setup import colours, fonts, screen
from setup import airlines, logos

from rgbmatrix import graphics

ZONE_X = screen.ZONE_AIRLINE      # 0 — left panel
PANEL_W = screen.PANEL_W          # 64

LOGO_TOP = 1
LOGO_REGION_H = 22                # rows 1..22
RULE_ROW = 23
NAME_BASELINE = 31                # name sits rows ~25..31


class AirlineLogoScene(object):
    def __init__(self):
        self._logo_rec = None
        self._logo_brand = colours.BLACK
        self._logo_x = ZONE_X
        self._logo_y = LOGO_TOP
        self._name = ""
        super().__init__()

    def _resolve_logo(self):
        flight = self._data[self._data_index]
        callsign = flight.get("callsign", "")
        iata = airlines.iata_for_callsign(callsign)
        rec = logos.get_logo(iata)
        self._logo_rec = rec

        sampled = rec["brand"] if rec else None
        br, bg, bb = airlines.brand_for_callsign(callsign, sampled)
        self._logo_brand = graphics.Color(br, bg, bb)
        self._name = airlines.name_for_callsign(callsign) or callsign[:3]

        if rec:
            self._logo_x = ZONE_X + (PANEL_W - rec["w"]) // 2
            self._logo_y = LOGO_TOP + (LOGO_REGION_H - rec["h"]) // 2

    @Animator.KeyFrame.add(0)
    def airline_logo_setup(self):
        if len(self._data) == 0:
            self._logo_rec = None
            return
        self._resolve_logo()

    def _draw_name(self):
        if not self._name:
            return
        # Pick the biggest font that fits the panel width.
        font = fonts.large_bold if len(self._name) <= 7 else fonts.small
        width = graphics.DrawText(self.canvas, font, screen.WIDTH + 40, NAME_BASELINE,
                                  self._logo_brand, self._name)
        x = ZONE_X + max(1, (PANEL_W - width) // 2)
        graphics.DrawText(self.canvas, font, x, NAME_BASELINE, self._logo_brand, self._name)

    @Animator.KeyFrame.add(1)
    def airline_logo(self, count):
        if len(self._data) == 0:
            return

        # Clear this panel only.
        self.draw_square(ZONE_X, 0, ZONE_X + PANEL_W, screen.HEIGHT, colours.BLACK)

        if self._logo_rec:
            for (x, y, r, g, b) in self._logo_rec["px"]:
                self.set_pixel(self._logo_x + x, self._logo_y + y, r, g, b)
        else:
            # No logo: draw the name big in the logo region instead.
            self._draw_name()

        # Brand livery rule across the panel.
        graphics.DrawLine(self.canvas, ZONE_X, RULE_ROW, ZONE_X + PANEL_W - 1,
                          RULE_ROW, self._logo_brand)

        # Airline name beneath the logo (skip if we already used it as fallback).
        if self._logo_rec:
            self._draw_name()
