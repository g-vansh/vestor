"""
logos.py — load the baked airline logo pack (assets/airline_logos.pkl).

The pack is produced offline by tools/bake_logos.py (needs Pillow); at runtime
we only need stdlib pickle, so the on-Pi service carries no image dependency.

Record shape per IATA:
    {"w": int, "h": int, "brand": (r,g,b), "px": bytes}
  px is packed 5 bytes/pixel (x, y, r, g, b). Iterate in steps of 5.
"""

import os
import pickle

DIR_PATH = os.path.dirname(os.path.realpath(__file__))
PACK_PATH = os.path.join(DIR_PATH, "..", "assets", "airline_logos.pkl")

_pack = None
_loaded = False


def _load():
    global _pack, _loaded
    _loaded = True
    try:
        with open(PACK_PATH, "rb") as f:
            _pack = pickle.load(f)
    except (OSError, pickle.UnpicklingError, EOFError):
        # No pack (or corrupt) — the card degrades gracefully to text-only.
        _pack = {}


def get_logo(iata):
    """Return the baked record for an IATA code, or None if unavailable."""
    if not _loaded:
        _load()
    if not iata:
        return None
    return _pack.get(iata.upper())


def has_pack():
    if not _loaded:
        _load()
    return bool(_pack)
