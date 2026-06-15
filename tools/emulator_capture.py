#!/usr/bin/env python3
"""[TEST] Headless end-to-end render of the Vestor display via RGBMatrixEmulator.

Runs the REAL app (``display.Display``) with ``import rgbmatrix`` transparently
redirected to RGBMatrixEmulator's ``raw`` adapter -- no X server, no LED panel,
no compiled binding -- captures a handful of rendered frames as PNG, then exits.
This exercises the full scene/animation/data pipeline so we can validate it
renders correctly *before* the panels arrive for the Phase 0 hardware test.

Run from the repo root inside the venv:

    ./env/bin/python tools/emulator_capture.py [out_dir] [max_frames]

Requires: pip install RGBMatrixEmulator  (Pillow comes in as its dependency;
it is intentionally NOT a production dependency of this app).
"""
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHIM = os.path.join(REPO, "tools", "rgbmatrix_emulator_shim")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "emu_frames")
MAX_SAVE = int(sys.argv[2]) if len(sys.argv) > 2 else 12
SAVE_EVERY = 6  # dump roughly every Nth rendered frame so captures are spread out

# 1) Redirect `import rgbmatrix` -> emulator shim. MUST precede any app import.
#    Also put the repo root on the path so `display`/`scenes`/... import when this
#    script is run by path (which otherwise only adds tools/ to sys.path).
sys.path.insert(0, SHIM)
sys.path.insert(1, REPO)

# 2) Force the headless `raw` adapter (frames -> numpy -> PNG, no display server).
#    RGBMatrixEmulator reads emulator_config.json from the CWD.
with open("emulator_config.json", "w") as fh:
    json.dump(
        {"display_adapter": "raw", "pixel_size": 8, "suppress_font_warnings": True},
        fh,
    )

os.makedirs(OUT, exist_ok=True)

# 3) Hook the raw adapter so every Nth rendered frame is written to PNG, and the
#    process exits cleanly once we have enough frames (the app loops forever).
from RGBMatrixEmulator.adapters.raw_adapter import RawAdapter  # noqa: E402

_orig_draw = RawAdapter.draw_to_screen
_state = {"saved": 0}


def _hooked_draw(self, pixels):
    _orig_draw(self, pixels)
    if self.frame % SAVE_EVERY == 0 and _state["saved"] < MAX_SAVE:
        path = os.path.join(OUT, "frame_%03d.png" % _state["saved"])
        try:
            self._dump_screenshot(path)
            _state["saved"] += 1
            print("[capture] %s (emulator frame %d)" % (path, self.frame), flush=True)
        except Exception as exc:  # pragma: no cover - diagnostic
            print("[capture] dump error:", exc, flush=True)
    if _state["saved"] >= MAX_SAVE:
        print("[capture] done: %d frames -> %s" % (_state["saved"], OUT), flush=True)
        os._exit(0)


RawAdapter.draw_to_screen = _hooked_draw

# 4) Prove the shim shadowed the compiled binding before we touch the app.
import rgbmatrix  # noqa: E402

print("[capture] rgbmatrix resolved to:", rgbmatrix.__file__, flush=True)

# 5) Run the real application end-to-end.
from display import Display  # noqa: E402

print("[capture] constructing Display() and running ...", flush=True)
Display().run()
