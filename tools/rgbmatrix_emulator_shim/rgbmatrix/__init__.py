"""TEST-ONLY shim: make ``import rgbmatrix`` resolve to RGBMatrixEmulator.

Put this package's PARENT directory (``tools/rgbmatrix_emulator_shim``) on
``sys.path[0]`` BEFORE importing the app, and the app's unmodified
``from rgbmatrix import RGBMatrix, RGBMatrixOptions, graphics`` calls
transparently target the pure-Python emulator instead of the compiled hzeller
C-extension binding.

This is used by ``tools/emulator_capture.py`` for headless render tests on a
machine with no LED hardware (and no X server, via the emulator's ``raw``
adapter). It is NEVER imported in production: on the Pi the real compiled
``rgbmatrix`` binding lives in site-packages and is found normally because this
shim dir is not on the path.
"""
import sys as _sys

from RGBMatrixEmulator import RGBMatrix, RGBMatrixOptions, graphics  # noqa: F401

# The app only ever does ``from rgbmatrix import graphics`` (a name lookup on
# this package, satisfied by the import above). Register the submodule alias too
# so any ``rgbmatrix.graphics`` / ``from rgbmatrix.graphics import X`` form keeps
# working if the app grows one.
_sys.modules.setdefault("rgbmatrix.graphics", graphics)
