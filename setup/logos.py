"""
logos.py — render airline wordmark logos for the flight card, at runtime, with
Pillow, straight from the canonical sim assets (`sim/logos/<IATA>.png`).

We reuse the SAME logo set the web sim uses (64 full-colour wordmarks, RGBA,
64px tall) — a single source of truth — and process each one on demand:
    load PNG -> resize to the band height (LANCZOS) -> flatten alpha on black
    -> LED legibility lift (raise value, keep hue) -> list of (x,y,r,g,b).
Results are cached per (iata, height), so each carrier is rasterised once.

The lift matters: many liveries are dark navy/black wordmarks (e.g. American),
which are the worst case on a black HUB75 panel — blue is the dimmest subpixel
and the driver's CIE1931 curve dims mids further. Lifting value (not hue) makes
them read while staying on-brand; already-bright logos barely move.

Pillow does the image work (no hand-rolled resampling); pixels are pushed with
the shimmed SetPixel so the code stays panel-lane-agnostic.
"""

import colorsys
import glob
import os

try:
    from PIL import Image
    _PIL_OK = True
except ImportError:                      # graceful: card falls back to text
    _PIL_OK = False

DIR_PATH = os.path.dirname(os.path.realpath(__file__))
LOGO_DIR = os.path.join(DIR_PATH, "..", "sim", "logos")

LOGO_H = 12          # logo band height (rows 0..11 of the 64x32 card)
ALPHA_KEEP = 96      # alpha below this is treated as transparent
MIN_LUM = 6          # drop resulting near-black pixels (faint AA fringe)
MAX_W = 250          # clamp width (packed x fits a byte; also caps scroll len)

# LED legibility lift
LIFT_GAMMA = 0.62    # <1 lifts shadows more than highlights
LIFT_FLOOR = 0.50    # every kept pixel is at least this bright (0..1 value)
SAT_BOOST = 1.12     # keep lifted colours vivid, not washed out

_cache = {}          # (iata, height) -> record | None
_index = None        # upper(IATA) -> png path


def _build_index():
    global _index
    _index = {}
    if not _PIL_OK:
        return
    for path in glob.glob(os.path.join(LOGO_DIR, "*.png")):
        iata = os.path.splitext(os.path.basename(path))[0].upper()
        _index[iata] = path


def _led_lift(r, g, b):
    """Raise luminance for LED legibility, preserving hue."""
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    v = max(v ** LIFT_GAMMA, LIFT_FLOOR)
    s = min(1.0, s * SAT_BOOST)
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return int(r * 255), int(g * 255), int(b * 255)


def _sample_brand(pixels):
    """Most-representative saturated colour of a logo (accents/fallback)."""
    from collections import Counter
    buckets = Counter()
    for (x, y, r, g, b) in pixels:
        mx, mn = max(r, g, b), min(r, g, b)
        if mx < 60 or mn > 225:
            continue
        if mx - mn < 24 and mx > 110:
            continue
        buckets[(r // 24 * 24, g // 24 * 24, b // 24 * 24)] += 1
    if not buckets:
        return (255, 176, 0)
    return buckets.most_common(1)[0][0]


def _render(path, height):
    im = Image.open(path).convert("RGBA")
    w0, h0 = im.size
    new_w = max(1, min(MAX_W, round(w0 * height / h0)))
    im = im.resize((new_w, height), Image.LANCZOS)
    data = im.getdata()

    pixels = []
    for i, (r, g, b, a) in enumerate(data):
        if a < ALPHA_KEEP:
            continue
        f = a / 255.0
        rr, gg, bb = int(r * f), int(g * f), int(b * f)
        if max(rr, gg, bb) < MIN_LUM:
            continue
        rr, gg, bb = _led_lift(rr, gg, bb)
        pixels.append((i % new_w, i // new_w, rr, gg, bb))

    return {"w": new_w, "h": height, "px": pixels, "brand": _sample_brand(pixels)}


def get_logo(iata, height=LOGO_H):
    """Return a rendered logo record {w,h,px:[(x,y,r,g,b)],brand}, or None."""
    if not _PIL_OK or not iata:
        return None
    if _index is None:
        _build_index()
    key = (iata.upper(), height)
    if key in _cache:
        return _cache[key]
    path = _index.get(iata.upper())
    rec = None
    if path:
        try:
            rec = _render(path, height)
        except (OSError, ValueError):
            rec = None
    _cache[key] = rec
    return rec


def preload_all(height=LOGO_H):
    """Render + cache every logo NOW.

    Must be called BEFORE RGBMatrix(drop_privileges=True) constructs the matrix:
    the driver needs root to map the hardware and then drops to an unprivileged
    user (`daemon`) that cannot read /home/pi (0700). Loading the PNGs lazily at
    render time would therefore fail and the card would fall back to text. Doing
    it here, while still root, warms the cache so rendering never touches disk.
    """
    if _index is None:
        _build_index()
    total = len(_index or {})
    loaded = sum(1 for iata in list(_index or {}) if get_logo(iata, height))
    print(f"[logos] preloaded {loaded}/{total} airline logos", flush=True)
    return loaded


def has_logos():
    if _index is None:
        _build_index()
    return bool(_index)
