"""
flightdetails.py — the TELEMETRY band of the flight card (rows 24..31).

    UAL123               FL350

  * Callsign on the left in warm white (deliberately NOT blue — blue is the
    least-legible LED text colour: lowest photoreceptor sensitivity + dimmest
    subpixel). Clipped if needed so it always keeps a gap before the field.
  * A right-aligned field that ROTATES every few seconds through:
        - altitude, coloured by FR24's altitude ramp,
        - the aircraft type (e.g. B738),
        - the "N of M" index when several aircraft are overhead.
    Rotating keeps the panel information-dense without crowding 64px.

Drawn in the 5x8 font (readable — the old 4x6 was too small at the panel's
bottom edge) via DrawText, on rows disjoint from the route band above.
"""

from utilities.animator import Animator
from setup import colours, fonts, screen, airlines

from rgbmatrix import graphics

# Band geometry — telemetry occupies rows 24..31 (disjoint from the route band
# above), drawn in the larger 5x8 font for legibility (the old 4x6 was unreadable).
TELE_BASELINE = 31
TELE_CLEAR_TOP = 24
TELE_CLEAR_BOT = 32
CALLSIGN_X = 1
TELE_FONT = fonts.small

# Colours
CALLSIGN_COLOUR = graphics.Color(250, 240, 215)   # warm white
TYPE_COLOUR = graphics.Color(120, 200, 235)        # soft cyan
INDEX_COLOUR = graphics.Color(150, 150, 150)       # dim grey

try:
    from setup.frames import PER_SECOND
except (ModuleNotFoundError, ImportError):
    PER_SECOND = 10
ROTATE_FRAMES = int(PER_SECOND * 3.5)


class FlightDetailsScene(object):
    def __init__(self):
        self._tele_frame = 0
        super().__init__()

    @Animator.KeyFrame.add(0)
    def flight_details_setup(self):
        self._tele_frame = 0

    def _measure(self, font, text):
        # DrawText off-screen returns the advance width without drawing.
        return graphics.DrawText(self.canvas, font, screen.WIDTH + 60, TELE_BASELINE,
                                 colours.BLACK, text)

    def _altitude_field(self, flight):
        alt = flight.get("altitude") or 0
        if alt >= 18000:
            text = f"FL{round(alt / 100):03d}"
        else:
            text = f"{int(alt)}'"
        colour = graphics.Color(*airlines.altitude_colour(alt))
        return ("alt", text, colour)

    def _build_fields(self, flight):
        fields = [self._altitude_field(flight)]
        # Prefer the short ICAO type code (B738); fall back to the model text.
        type_text = flight.get("aircraft_code") or ""
        if not type_text:
            type_text = (flight.get("plane") or "")[:8]
        if type_text:
            fields.append(("type", type_text.upper(), TYPE_COLOUR))
        if len(self._data) > 1:
            fields.append(("index", f"{self._data_index + 1}/{len(self._data)}", INDEX_COLOUR))
        return fields

    @Animator.KeyFrame.add(1)
    def flight_details(self, count):
        if len(self._data) == 0:
            return

        flight = self._data[self._data_index]

        # Clear the telemetry band.
        self.draw_square(0, TELE_CLEAR_TOP, screen.WIDTH, TELE_CLEAR_BOT, colours.BLACK)

        # Rotating right-aligned field (altitude / type / index).
        fields = self._build_fields(flight)
        kind, text, colour = fields[(self._tele_frame // ROTATE_FRAMES) % len(fields)]
        field_x = screen.WIDTH - self._measure(TELE_FONT, text)

        # Callsign (left), clipped so it always keeps a gap before the field.
        callsign = flight.get("callsign") or ""
        max_w = field_x - 2 - CALLSIGN_X
        while callsign and self._measure(TELE_FONT, callsign) > max_w:
            callsign = callsign[:-1]
        if callsign:
            graphics.DrawText(self.canvas, TELE_FONT, CALLSIGN_X, TELE_BASELINE,
                              CALLSIGN_COLOUR, callsign)

        graphics.DrawText(self.canvas, TELE_FONT, field_x, TELE_BASELINE, colour, text)
        self._tele_frame += 1
