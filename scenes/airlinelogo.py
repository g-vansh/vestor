"""
airlinelogo.py — the hero band of the single-panel flight card.

Top 13 rows of the 64x32 panel:
    rows 0..11  full-colour airline wordmark (from the baked logo pack)
    row  12     a 1px brand-colour rule (the airline's livery colour)

Behaviour:
  * Logo that fits in 64px  -> drawn static, horizontally centred.
  * Wider wordmark          -> scrolled as a slow marquee so the whole name reads.
  * No logo for this carrier -> the curated short name (or ICAO) in brand colour.

Pixels are blitted with self.canvas.SetPixel, which the Pi's Port-3 shim already
offsets by +64 rows — so this scene carries no panel-lane maths and works
unchanged on the eventual centre-fed wall.
"""

from utilities.animator import Animator
from setup import colours, fonts, screen
from setup import airlines, logos

from rgbmatrix import graphics

LOGO_BAND_H = 12          # rows 0..11
RULE_ROW = 12             # brand hairline
LOGO_GAP = 14             # blank gap between marquee repeats
LOGO_SPEED = 0.55         # px/frame for the marquee (stately, readable)

FALLBACK_FONT = fonts.small


class AirlineLogoScene(object):
    def __init__(self):
        self._logo_iata = None
        self._logo_rec = None
        self._logo_brand = colours.BLACK
        self._logo_scroll = 0.0
        self._logo_static_x = 0
        super().__init__()

    def _resolve_logo(self):
        """Look up the current flight's logo record + brand colour."""
        callsign = self._data[self._data_index].get("callsign", "")
        iata = airlines.iata_for_callsign(callsign)
        rec = logos.get_logo(iata)
        self._logo_iata = iata
        self._logo_rec = rec

        sampled = rec["brand"] if rec else None
        br, bg, bb = airlines.brand_for_callsign(callsign, sampled)
        self._logo_brand = graphics.Color(br, bg, bb)

        # Pre-compute static centring for logos that fit.
        if rec and rec["w"] <= screen.WIDTH:
            self._logo_static_x = (screen.WIDTH - rec["w"]) // 2
        self._logo_scroll = 0.0

    @Animator.KeyFrame.add(0)
    def airline_logo_setup(self):
        # Re-arm whenever the scene resets (new flight / new index / new data).
        if len(self._data) == 0:
            self._logo_rec = None
            return
        self._resolve_logo()

    def _blit_logo(self, x0):
        """Blit the rendered logo pixels translated by x0, clipped to the panel."""
        for (x, y, r, g, b) in self._logo_rec["px"]:
            sx = x0 + x
            if 0 <= sx < screen.WIDTH:
                self.canvas.SetPixel(sx, y, r, g, b)

    def _draw_fallback(self, callsign):
        """No logo: draw the curated short name (or ICAO) in brand colour."""
        text = airlines.name_for_callsign(callsign) or "FLIGHT"
        # Measure by drawing off-screen, then draw centred.
        width = graphics.DrawText(
            self.canvas, FALLBACK_FONT, screen.WIDTH + 40, 9, self._logo_brand, text
        )
        x = max(1, (screen.WIDTH - width) // 2)
        graphics.DrawText(self.canvas, FALLBACK_FONT, x, 9, self._logo_brand, text)

    @Animator.KeyFrame.add(1)
    def airline_logo(self, count):
        # Guard against no data — leave the band clear for the idle scenes.
        if len(self._data) == 0:
            return

        # Clear the hero band (logo rows + rule row).
        self.draw_square(0, 0, screen.WIDTH, RULE_ROW, colours.BLACK)

        rec = self._logo_rec
        if rec:
            if rec["w"] <= screen.WIDTH:
                self._blit_logo(self._logo_static_x)
            else:
                total = rec["w"] + LOGO_GAP
                self._logo_scroll += LOGO_SPEED
                sx = int(self._logo_scroll) % total
                self._blit_logo(-sx)
                self._blit_logo(-sx + total)   # seamless wrap
        else:
            callsign = self._data[self._data_index].get("callsign", "")
            self._draw_fallback(callsign)

        # Brand-colour rule under the logo — a dim livery hairline.
        graphics.DrawLine(
            self.canvas, 0, RULE_ROW, screen.WIDTH - 1, RULE_ROW, self._logo_brand
        )
