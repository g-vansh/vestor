# Screen / panel geometry
#
# One physical panel is 64x32. Multiple panels chained on the data line form a
# wider logical canvas: WIDTH = PANEL_W * CHAIN. Calibrated 2026-07-01 so canvas
# column 0 = physical far-left; each scene owns one 64-wide ZONE.

try:
    from config import CHAIN_LENGTH
except (ModuleNotFoundError, NameError, ImportError):
    CHAIN_LENGTH = 1

PANEL_W = 64
PANEL_H = 32

CHAIN = CHAIN_LENGTH
WIDTH = PANEL_W * CHAIN     # full canvas width (192 for a 3-panel board)
HEIGHT = PANEL_H

# 3-panel departure-board zones (x-offset of each panel's left edge).
ZONE_AIRLINE = 0                 # left panel  — airline logo + name
ZONE_ROUTE = PANEL_W             # middle panel — origin -> destination
ZONE_TELEM = PANEL_W * 2         # right panel  — telemetry (alt / type / vs)
