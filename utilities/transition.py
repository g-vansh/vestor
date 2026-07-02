"""
transition.py — the "plane swoop" scene transition + the drawing machinery it
needs, shared by the live display and the offline preview.

THE EFFECT
----------
Whenever the board changes what it shows (flight -> flight, flight -> clock,
clock -> flight) a big jet swoops across from left to right. Everything to the
LEFT of the plane is the NEW screen; everything to the RIGHT is the OLD screen —
the plane is the leading edge of a wipe. A bright sweep line marks the seam and
a contrail trails behind.

HOW THE WIPE IS POSSIBLE WITHOUT A SECOND FRAMEBUFFER
-----------------------------------------------------
The display draws into ONE persistent canvas; each scene clears+redraws only its
own zone every frame. So if we install a CLIP WINDOW ``[0, edge)`` that every
draw primitive obeys, then during a transition the scenes only repaint the
revealed (left) region and the right region is never touched — the OLD frame
simply stays frozen there. As ``edge`` sweeps right, NEW is revealed column by
column under the plane. When no transition is running the clip is ``None`` and
the primitives behave EXACTLY as before (byte-identical hot path).

The only subtlety is text: the C ``DrawText`` can't be pixel-clipped, so while a
clip is active we draw the longest whole-glyph PREFIX that fits left of the edge
(a straddling glyph is dropped — it sits under the wide plane body anyway).

Pixels are pushed through a ``put(x, y, r, g, b)`` callback so the SAME plane
art renders live (``self._raw_pixel``) and in the Mac preview (a PIL canvas).
"""

import math

from rgbmatrix import graphics

from setup import screen
from utilities.animator import Animator

try:
    from setup.frames import PER_SECOND
except (ModuleNotFoundError, ImportError):
    PER_SECOND = 20

try:
    from config import TRANSITIONS_ENABLED
except (ModuleNotFoundError, NameError, ImportError):
    TRANSITIONS_ENABLED = True

WIDTH, HEIGHT = screen.WIDTH, screen.HEIGHT

# ---------------------------------------------------------------------------
# graphics interception: apply a lane y-offset (Port-3 shim) + the clip window.
# install() replaces the module-level DrawText/DrawLine ONCE; the wrappers fall
# straight through to the originals when no clip is active.
# ---------------------------------------------------------------------------
_LANE_Y = 0
_CLIP = None                       # None, or (x0, x1): only draw within [x0, x1)
_real_DrawText = None
_real_DrawLine = None
_BLACK = graphics.Color(0, 0, 0)
_wcache = {}                       # (id(font), ch) -> advance width


def set_clip(clip):
    global _CLIP
    _CLIP = clip


def clear_clip():
    global _CLIP
    _CLIP = None


def _char_width(canvas, font, ch):
    key = (id(font), ch)
    w = _wcache.get(key)
    if w is not None:
        return w
    try:
        w = int(font.CharacterWidth(ord(ch)))
        if w <= 0:
            raise ValueError
    except Exception:              # measure: real DrawText returns the advance
        w = int(_real_DrawText(canvas, font, WIDTH + 80, 0, _BLACK, ch))
        if w <= 0:
            w = 6
    _wcache[key] = w
    return w


def _wrapped_drawtext(canvas, font, x, y, colour, text):
    yy = y + _LANE_Y
    if _CLIP is None:
        return _real_DrawText(canvas, font, x, yy, colour, text)
    x0, x1 = _CLIP
    cx = x
    k = 0
    for ch in text:                # draw the whole-glyph prefix that fits left of x1
        w = _char_width(canvas, font, ch)
        if x0 <= cx and cx + w <= x1:
            cx += w
            k += 1
        else:
            break
    if k:
        _real_DrawText(canvas, font, x, yy, colour, text[:k])
    full = cx                      # still return the TRUE advance (callers measure)
    for ch in text[k:]:
        full += _char_width(canvas, font, ch)
    return full - x


def _wrapped_drawline(canvas, x0, y0, x1, y1, colour):
    yy0, yy1 = y0 + _LANE_Y, y1 + _LANE_Y
    if _CLIP is None:
        return _real_DrawLine(canvas, x0, yy0, x1, yy1, colour)
    cx0, cx1 = _CLIP
    if x0 == x1:                   # vertical (draw_square's columns)
        if cx0 <= x0 < cx1:
            return _real_DrawLine(canvas, x0, yy0, x1, yy1, colour)
        return None
    lo, hi = min(x0, x1), max(x0, x1)   # horizontal rule -> clip the span
    lo, hi = max(lo, cx0), min(hi, cx1 - 1)
    if lo <= hi:
        return _real_DrawLine(canvas, lo, yy0, hi, yy1, colour)
    return None


def install(lane_y=0):
    """Patch graphics.DrawText/DrawLine (offset + clip). Idempotent."""
    global _LANE_Y, _real_DrawText, _real_DrawLine
    _LANE_Y = lane_y
    if _real_DrawText is None:
        _real_DrawText = graphics.DrawText
        _real_DrawLine = graphics.DrawLine
        graphics.DrawText = _wrapped_drawtext
        graphics.DrawLine = _wrapped_drawline


# ---------------------------------------------------------------------------
# the swoop art — a big side-view airliner + sweep line + contrail.
# Pure geometry; pushes pixels through `put(x, y, r, g, b)`.
# ---------------------------------------------------------------------------
TRANS_FRAMES = max(12, int(PER_SECOND * 0.9))   # ~0.9 s sweep
PLANE_LEN = 42                                   # fuselage length (px)
_CY = HEIGHT // 2                                # vertical centre (16)
_HH = 4                                          # fuselage half-height
_FIN_H = 12                                      # tail-fin height above fuselage
_WING_DROP = 11                                  # wing reach below fuselage

# palette
_BODY_TOP = (236, 242, 252)      # silver-white (lit top)
_BODY_BOT = (150, 160, 182)      # shaded belly
_FIN = (255, 176, 0)             # departure-board amber tail
_WING = (196, 206, 224)          # cool grey wing
_COCKPIT = (90, 200, 235)        # cyan glass
_NOSE = (255, 255, 255)          # nose glint
_LINE_CORE = (255, 236, 200)     # warm sweep-line core
_LINE_SOFT = (150, 96, 20)       # sweep-line falloff
_CONTRAIL = (120, 150, 190)


def _blend(a, b, t):
    return (int(a[0] + (b[0] - a[0]) * t),
            int(a[1] + (b[1] - a[1]) * t),
            int(a[2] + (b[2] - a[2]) * t))


def _fuselage_hh(xi):
    """Half-height of the fuselage at distance xi behind the nose."""
    head, tail = 7, 9
    if xi < head:
        return 1 + (_HH - 1) * xi / head
    if xi > PLANE_LEN - tail:
        return 1 + (_HH - 1) * (PLANE_LEN - xi) / tail
    return _HH


def _put_clamped(put, x, y, colour):
    if 0 <= x < WIDTH and 0 <= y < HEIGHT:
        put(x, y, colour[0], colour[1], colour[2])


def draw_swoop(put, nose_x, edge_x, frame, total):
    """Draw the sweep line + big plane with its nose at nose_x."""
    nose_x = int(round(nose_x))
    tail_x = nose_x - PLANE_LEN

    # --- sweep line (the OLD|NEW seam), full height, with soft falloff ---
    if 0 <= edge_x < WIDTH:
        for y in range(HEIGHT):
            _put_clamped(put, edge_x, y, _LINE_CORE)
            _put_clamped(put, edge_x - 1, y, _blend(_LINE_SOFT, _LINE_CORE, 0.5))
            _put_clamped(put, edge_x - 2, y, _LINE_SOFT)

    # --- contrail: fading dashes trailing behind the tail ---
    for k in range(1, 9):
        cx = tail_x - k * 3
        fade = max(0.0, 1.0 - k / 9.0)
        col = _blend((0, 0, 0), _CONTRAIL, fade * 0.8)
        _put_clamped(put, cx, _CY, col)
        if k % 2 == 0:
            _put_clamped(put, cx, _CY - 1, _blend((0, 0, 0), _CONTRAIL, fade * 0.4))

    # --- wing (drawn first, behind the fuselage): swept down-and-back ---
    root_x = nose_x - int(PLANE_LEN * 0.46)
    tip_x = tail_x + 5
    for x in range(tip_x, root_x + 1):
        t = (x - tip_x) / max(1, (root_x - tip_x))       # 0 at tip .. 1 at root
        top = _CY + int(_HH * 0.4)
        bot = _CY + int(_WING_DROP * (0.35 + 0.65 * t))
        for y in range(top, bot + 1):
            _put_clamped(put, x, y, _WING)
    # engine nacelle under the wing
    eng_x = nose_x - int(PLANE_LEN * 0.5)
    for x in range(eng_x - 3, eng_x + 3):
        for y in range(_CY + _WING_DROP - 4, _CY + _WING_DROP - 1):
            _put_clamped(put, x, y, _blend(_WING, (60, 66, 78), 0.5))

    # --- tail fin: rises above the fuselage at the back, leaning aft ---
    for i in range(10):
        x = tail_x + 2 + i // 2                            # lean back as it rises
        h = int(_FIN_H * (i / 9.0))
        for y in range(_CY - _HH - h, _CY - _HH + 1):
            _put_clamped(put, x, y, _FIN)

    # --- fuselage: tapered capsule, vertical gradient, nose at nose_x ---
    for xi in range(PLANE_LEN + 1):
        x = nose_x - xi
        hh = _fuselage_hh(xi)
        h = int(round(hh))
        for y in range(_CY - h, _CY + h + 1):
            t = (y - (_CY - h)) / max(1, (2 * h))          # 0 top .. 1 bottom
            _put_clamped(put, x, y, _blend(_BODY_TOP, _BODY_BOT, t))

    # cockpit windows just behind the nose (top of fuselage)
    for dx in range(4, 9):
        _put_clamped(put, nose_x - dx, _CY - 2, _COCKPIT)
    # nose glint
    _put_clamped(put, nose_x, _CY, _NOSE)
    _put_clamped(put, nose_x - 1, _CY, _NOSE)


def _ease(p):                       # smootherstep, gentle in/out
    p = max(0.0, min(1.0, p))
    return p * p * p * (p * (p * 6 - 15) + 10)


# ---------------------------------------------------------------------------
# the mixin the Display composes in
# ---------------------------------------------------------------------------
class TransitionMixin(object):
    def __init__(self):
        self._trans_on = False
        self._trans_f = 0
        self._painted = False
        super().__init__()

    # single pixel entry point — applies the lane offset + honours the clip
    def set_pixel(self, x, y, r, g, b):
        c = _CLIP
        if c is not None and not (c[0] <= x < c[1]):
            return
        self.canvas.SetPixel(x, y + self._lane_offset_y, r, g, b)

    # unclipped write (the swoop plane paints ON TOP of the seam)
    def _raw_pixel(self, x, y, r, g, b):
        self.canvas.SetPixel(x, y + self._lane_offset_y, r, g, b)

    def draw_square(self, x0, y0, x1, y1, colour):
        for x in range(x0, x1):
            graphics.DrawLine(self.canvas, x, y0, x, y1, colour)

    def reset_scene(self):
        # Start a swoop instead of an instant cut — unless disabled, nothing has
        # been painted yet (first frame), or a swoop is already running.
        if not TRANSITIONS_ENABLED or not self._painted or self._trans_on:
            return super().reset_scene()
        # Prepare the NEW scene state (the divisor-0 setups) but DON'T clear the
        # canvas — the OLD frame must survive to be revealed from under the plane.
        for kf in self.keyframes:
            if kf.properties["divisor"] == 0 and kf.__name__ != "clear_screen":
                kf()
        self._trans_on = True
        self._trans_f = 0
        set_clip((0, 0))

    @Animator.KeyFrame.add(1)
    def swoop(self, count):
        # Runs after every scene draw, before sync. Marks the board "painted"
        # (so the first frame never transitions) and drives an active swoop.
        self._painted = True
        if not self._trans_on:
            return
        self._trans_f += 1
        p = self._trans_f / TRANS_FRAMES
        nose = _ease(p) * (WIDTH + PLANE_LEN)
        edge = max(0, min(WIDTH, int(round(nose))))
        set_clip((0, edge))                 # scenes reveal up to here next frame
        draw_swoop(self._raw_pixel, nose, edge, self._trans_f, TRANS_FRAMES)
        if self._trans_f >= TRANS_FRAMES:
            self._trans_on = False
            clear_clip()
