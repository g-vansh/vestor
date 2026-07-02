"""
airlinelogo.py — the LEFT panel of the 3-panel departure board: AIRLINE identity.

Full 64x32 panel (canvas cols 0..63). The content is composed as a group and
VERTICALLY CENTRED so it never sits low:

    [ logo | aircraft icon ]      top element
    ---- brand rule ----          thin divider
    NAME / CATEGORY               label beneath

  * Airline with a logo -> the logo (fit 62x22, static) + curated name.
  * No logo (GA / heli / private jet / regional) -> a pixel-art aircraft icon
    (helicopter / private jet / turboprop / airliner) + a label (the operator
    name if known, else the category).

Pixels via self.set_pixel; text via the shimmed DrawText/DrawLine. Coordinates
are canvas-absolute (cols 0..63 = physical left panel).
"""

from utilities.animator import Animator
from setup import colours, fonts, screen
from setup import airlines, logos, aircraft

from rgbmatrix import graphics

ZONE_X = screen.ZONE_AIRLINE      # 0 — left panel
PANEL_W = screen.PANEL_W          # 64

ICON_COLOUR = graphics.Color(200, 210, 225)   # silver — reads as "aircraft"
GAP = 2

CATEGORY_LABEL = {
    "heli": "HELICOPTER",
    "bizjet": "PRIVATE JET",
    "prop": "PROP",
    "airliner": "AIRLINER",
}


class AirlineLogoScene(object):
    def __init__(self):
        self._logo_rec = None
        self._icon = None             # (static_px, anim_px, w, h) when no logo
        self._icon_frame = 0
        self._brand = colours.BLACK
        self._label = ""
        super().__init__()

    def _resolve(self):
        flight = self._data[self._data_index]
        callsign = flight.get("callsign", "")
        iata = airlines.iata_for_callsign(callsign)
        icao = airlines.icao_for_callsign(callsign)
        rec = logos.get_logo(iata)
        self._logo_rec = rec

        sampled = rec["brand"] if rec else None
        br, bg, bb = airlines.brand_for_callsign(callsign, sampled)
        self._brand = graphics.Color(br, bg, bb)

        if rec:
            self._icon = None
            self._label = airlines.name_for_callsign(callsign) or icao
        else:
            code = flight.get("aircraft_code", "")
            static, anim, w, h, cat = aircraft.icon_for(code)
            self._icon = (static, anim, w, h)
            if icao in airlines.AIRLINE_DB:      # known operator, just no logo file
                self._label = airlines.AIRLINE_DB[icao]["name"]
            else:
                self._label = CATEGORY_LABEL[cat]

    @Animator.KeyFrame.add(0)
    def airline_logo_setup(self):
        if len(self._data) == 0:
            self._logo_rec = None
            self._icon = None
            return
        self._resolve()

    def _label_metrics(self):
        # Always the 5x8 font so logo(<=20) + rule + gap + name(8) fits 32 rows.
        font = fonts.small
        width = graphics.DrawText(self.canvas, font, screen.WIDTH + 60, 20,
                                  colours.BLACK, self._label) if self._label else 0
        return font, width, 8

    def _draw_icon(self, top_x, top_y):
        """Silver aircraft body + a bright spot sweeping the rotor/prop (spin)."""
        static, anim, w, h = self._icon
        for (x, y) in static:
            self.set_pixel(top_x + x, top_y + y, ICON_COLOUR.red,
                           ICON_COLOUR.green, ICON_COLOUR.blue)
        if anim:
            n = len(anim)
            head = (self._icon_frame * 2) % n
            for i, (x, y) in enumerate(anim):
                d = min((i - head) % n, (head - i) % n)
                if d == 0:
                    r, g, b = 255, 255, 255
                elif d == 1:
                    r, g, b = 150, 160, 180
                else:
                    r, g, b = 55, 62, 72
                self.set_pixel(top_x + x, top_y + y, r, g, b)

    @Animator.KeyFrame.add(1)
    def airline_logo(self, count):
        if len(self._data) == 0:
            return

        self.draw_square(ZONE_X, 0, ZONE_X + PANEL_W, screen.HEIGHT, colours.BLACK)

        # Top element dimensions (logo or icon).
        if self._logo_rec:
            top_w, top_h = self._logo_rec["w"], self._logo_rec["h"]
            rule_colour = self._brand
        elif self._icon:
            _, _, top_w, top_h = self._icon
            rule_colour = ICON_COLOUR
        else:
            return

        font, label_w, label_h = self._label_metrics()

        # Centre the whole group vertically.
        group_h = top_h + 1 + GAP + label_h
        top_y = max(0, (screen.HEIGHT - group_h) // 2)

        # Top element, centred horizontally.
        top_x = ZONE_X + (PANEL_W - top_w) // 2
        if self._logo_rec:
            for (x, y, r, g, b) in self._logo_rec["px"]:
                self.set_pixel(top_x + x, top_y + y, r, g, b)
        else:
            self._draw_icon(top_x, top_y)
        self._icon_frame += 1

        # Brand rule.
        rule_y = top_y + top_h + 1
        graphics.DrawLine(self.canvas, ZONE_X + 2, rule_y, ZONE_X + PANEL_W - 3,
                          rule_y, rule_colour)

        # Label beneath.
        if self._label:
            label_colour = self._brand if self._logo_rec else ICON_COLOUR
            baseline = rule_y + GAP + label_h - 1
            x = ZONE_X + max(1, (PANEL_W - label_w) // 2)
            graphics.DrawText(self.canvas, font, x, baseline, label_colour, self._label)
