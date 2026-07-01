#!/usr/bin/env python3
"""
bake_logos.py — pre-render the sim's airline wordmark PNGs into a compact,
Pi-friendly pixel pack for the single-panel (64x32) "legendary" flight card.

WHY THIS EXISTS
    The web sim (sim/logos/*.png) ships 64 full-colour airline wordmarks sized
    for the 1024x32 wall: RGBA, 64 px tall, up to ~750 px wide. On the tiny
    64x32 panel we want the SAME brand identity, but:
      * we render with the hzeller binding's per-pixel SetPixel (already wrapped
        by the Pi's Port-3 +64-row shim), so we need raw (x,y,r,g,b) pixels, and
      * the on-Pi service must NOT depend on Pillow at runtime.
    So we do all the heavy image work HERE, on the Mac (Pillow present), once,
    and emit assets/airline_logos.pkl that the Pi loads with stdlib pickle.

DESIGN NOTES
    * Downscale to LOGO_H px tall with a high-quality box/Lanczos filter — do NOT
      pre-gamma-correct: the hzeller driver already applies a CIE1931 luminance
      curve at 11 PWM bits, so we feed it linear sRGB and let it correct.
    * Alpha is flattened against black (the panel background), so anti-aliased
      edges become naturally dimmer pixels — smoother than a hard 1-bit key.
    * Each logo also carries a sampled "brand" colour used for accents/rule/
      fallback text; the runtime prefers the curated LED-boosted colour from
      setup/airlines.py when the operator is known, and falls back to this.

OUTPUT (assets/airline_logos.pkl) — pickled dict:
    { IATA: {"w": int, "h": int, "brand": (r,g,b), "px": bytes} }
  where px is packed 5 bytes/pixel: x, y, r, g, b  (x<256, y<32, colours 0-255).
"""

import glob
import os
import pickle
from collections import Counter

from PIL import Image

HERE = os.path.dirname(os.path.realpath(__file__))
LOGO_SRC = os.path.join(HERE, "..", "sim", "logos")
OUT_PKL = os.path.join(HERE, "..", "assets", "airline_logos.pkl")

LOGO_H = 12          # target logo band height (rows 0..11 of the 64x32 card)
ALPHA_KEEP = 110     # pixels with alpha below this are treated as transparent
MIN_LUM = 8          # drop resulting near-black pixels (faint AA fringe)
MAX_W = 250          # safety clamp so packed x always fits one byte


def sample_brand(rgba_px, w, h):
    """Most-representative saturated colour of a logo (for accents/fallback).

    Ignores transparent, near-black and near-white pixels, quantises the rest
    to a coarse grid and takes the most common bucket, then nudges it brighter
    so it reads as a saturated accent on the LED panel.
    """
    buckets = Counter()
    for (r, g, b, a) in rgba_px:
        if a < ALPHA_KEEP:
            continue
        mx, mn = max(r, g, b), min(r, g, b)
        if mx < 45:                     # near-black
            continue
        if mn > 225:                    # near-white
            continue
        if mx - mn < 24 and mx > 90:    # low-saturation grey (keep dark/vivid)
            continue
        buckets[(r // 24 * 24, g // 24 * 24, b // 24 * 24)] += 1
    if not buckets:
        return (255, 176, 0)            # sodium-amber generic
    r, g, b = buckets.most_common(1)[0][0]
    # LED boost: lift toward full range so it doesn't look muddy dimmed.
    boost = 255 / max(r, g, b, 1)
    boost = min(boost, 1.6)
    return (min(255, int(r * boost)), min(255, int(g * boost)), min(255, int(b * boost)))


def bake_one(path):
    im = Image.open(path).convert("RGBA")
    w0, h0 = im.size
    new_w = max(1, round(w0 * LOGO_H / h0))
    if new_w > MAX_W:
        new_w = MAX_W
    im = im.resize((new_w, LOGO_H), Image.LANCZOS)
    px = list(im.getdata())

    brand = sample_brand(px, new_w, LOGO_H)

    packed = bytearray()
    kept = 0
    for i, (r, g, b, a) in enumerate(px):
        if a < ALPHA_KEEP:
            continue
        # flatten alpha against black background
        f = a / 255.0
        rr, gg, bb = int(r * f), int(g * f), int(b * f)
        if max(rr, gg, bb) < MIN_LUM:
            continue
        x = i % new_w
        y = i // new_w
        packed += bytes((x, y, rr, gg, bb))
        kept += 1

    return {"w": new_w, "h": LOGO_H, "brand": brand, "px": bytes(packed)}, kept


def main():
    os.makedirs(os.path.dirname(OUT_PKL), exist_ok=True)
    out = {}
    total_bytes = 0
    for path in sorted(glob.glob(os.path.join(LOGO_SRC, "*.png"))):
        iata = os.path.splitext(os.path.basename(path))[0].upper()
        rec, kept = bake_one(path)
        out[iata] = rec
        total_bytes += len(rec["px"])
        print(f"  {iata:>3}  {rec['w']:>3}x{rec['h']}  px={kept:>4}  brand={rec['brand']}")

    with open(OUT_PKL, "wb") as f:
        pickle.dump(out, f, protocol=4)   # proto 4: py3.4+ (safe for the Pi)

    size_kb = os.path.getsize(OUT_PKL) / 1024
    print(f"\nBaked {len(out)} logos -> {OUT_PKL}  ({size_kb:.1f} KB, "
          f"{total_bytes/1024:.1f} KB pixels)")


if __name__ == "__main__":
    main()
