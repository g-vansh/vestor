"""
planedetails.py — the flight-cycle controller (no pixels of its own).

The original scene scrolled the aircraft type along the bottom and advanced to
the next overhead aircraft when the text scrolled off. The legendary card folds
the aircraft type into the telemetry rotation (flightdetails.py), so this scene
keeps ONLY the cycling responsibility: dwell on each aircraft for a few seconds,
then advance the index and reset the scene (which re-arms the split-flap, the
logo marquee and the telemetry rotation for the next flight).

It preserves the `_data_all_looped` flag that display/__init__.py's
grab_new_data() relies on to decide when it may fetch fresh data.
"""

from utilities.animator import Animator

try:
    from setup.frames import PER_SECOND
except (ModuleNotFoundError, ImportError):
    PER_SECOND = 10

DWELL_FRAMES = int(PER_SECOND * 7)     # ~7s per aircraft


class PlaneDetailsScene(object):
    def __init__(self):
        self._data_all_looped = False
        self._dwell = 0
        super().__init__()

    @Animator.KeyFrame.add(0)
    def reset_cycle(self):
        self._dwell = 0

    @Animator.KeyFrame.add(1)
    def plane_cycle(self, count):
        # Nothing to cycle through with 0 or 1 aircraft.
        if len(self._data) <= 1:
            self._data_all_looped = False
            return

        self._dwell += 1
        if self._dwell >= DWELL_FRAMES:
            self._dwell = 0
            self._data_index = (self._data_index + 1) % len(self._data)
            # True once we've wrapped back to the first aircraft.
            self._data_all_looped = (not self._data_index) or self._data_all_looped
            self.reset_scene()
