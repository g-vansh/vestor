import sys

from setup import frames
from setup import logos
from setup import weather
from utilities.animator import Animator
from utilities.overhead import Overhead

from scenes.airlinelogo import AirlineLogoScene
from scenes.flightdetails import FlightDetailsScene
from scenes.journey import JourneyScene
from scenes.loadingpulse import LoadingPulseScene
from scenes.loadingled import LoadingLEDScene
from scenes.idle import IdleScene
from scenes.planedetails import PlaneDetailsScene

from rgbmatrix import graphics
from rgbmatrix import RGBMatrix, RGBMatrixOptions


def callsigns_match(flights_a, flights_b):
    get_callsigns = lambda flights: [f["callsign"] for f in flights]
    callsigns_a = set(get_callsigns(flights_a))
    callsigns_b = set(get_callsigns(flights_b))

    return callsigns_a == callsigns_b


try:
    # Attempt to load config data
    from config import (
        BRIGHTNESS,
        GPIO_SLOWDOWN,
        HAT_PWM_ENABLED
    )

except (ModuleNotFoundError, NameError):
    # If there's no config data. NOTE: BRIGHTNESS fallback is 50 (NOT 100) on
    # purpose — the full wall's power budget (2x LRS-350-5 = 120 A) is only safe
    # with brightness capped; an all-white panel at 100 would push ~128 A. Keep
    # this conservative so a missing config can't over-draw the supplies.
    BRIGHTNESS = 50
    GPIO_SLOWDOWN = 4
    HAT_PWM_ENABLED = False

try:
    from config import CHAIN_LENGTH
except (ModuleNotFoundError, NameError, ImportError):
    CHAIN_LENGTH = 1

try:
    # Attempt to load experimental config data
    from config import LOADING_LED_ENABLED

except (ModuleNotFoundError, NameError, ImportError):
    # If there's no experimental config data
    LOADING_LED_ENABLED = False


class Display(
    AirlineLogoScene,
    FlightDetailsScene,
    JourneyScene,
    LoadingLEDScene if LOADING_LED_ENABLED else LoadingPulseScene ,
    PlaneDetailsScene,
    IdleScene,
    Animator,
):
    def __init__(self):
        # Setup Display
        # Vestor: Adafruit Triple RGB Matrix Bonnet (6358), "active3" pinout.
        # Configured below for the Phase 0 SINGLE-PANEL test. For the full 16-panel
        # wall (LOCKED 2026-06-21: one continuous row, fed from the CENTER as
        # 2 chains of 8) switch chain_length->8, parallel->2, drop pwm_bits to 7-9.
        # The 1024x32 logical render is composed into a 512x64 hardware canvas with
        # the LEFT half rotated 180deg (the "snake") IN OUR OWN CODE — NOT via
        # pixel_mapper_config. See HARDWARE.md + INVENTORY.md §5. (Superseded the
        # earlier 6+5+5 plan, which needed >50cm jump cables.)
        options = RGBMatrixOptions()
        options.hardware_mapping = "regular"      # Triple Bonnet active3 — NOT adafruit-hat/-pwm
        options.rows = 32
        options.cols = 64
        options.chain_length = CHAIN_LENGTH        # chained panels on the data line (config)
        options.parallel = 1                      # single-panel test (wall: 2 chains, center-fed)
        options.row_address_type = 0              # 1/16 scan ABCD; try 3 or 5 if rows scramble on hw
        options.panel_type = ""                   # FM6124D is a STANDARD driver (no init). Fallback #1 if
                                                  # panel stays BLACK: set "FM6126A" (then "FM6127"), then
                                                  # REMOVE again before tuning rgb-sequence/row-addr.
        options.multiplexing = 0
        options.pwm_bits = 11                     # one panel (wall: drop to 7-9 if refresh < ~100 Hz)
        options.brightness = BRIGHTNESS
        options.pwm_lsb_nanoseconds = 130
        options.led_rgb_sequence = "RGB"          # try RBG/BGR/GRB if colors come out swapped on hw
        options.pixel_mapper_config = ""          # wall: leave EMPTY — the 2x8 snake is handled by our
                                                  # 512x64 canvas composition (left half rotated 180), NOT a mapper
        options.show_refresh_rate = 0
        options.gpio_slowdown = GPIO_SLOWDOWN     # config.py = 4 for Pi 4 (try 5 if garbage on hw)
        options.disable_hardware_pulsing = False  # active3/regular supports hardware PWM natively
        options.drop_privileges = True

        # Load logo assets while we still have root. RGBMatrix() below maps the
        # PWM hardware as root and then drops to the unprivileged `daemon` user,
        # which cannot read /home/pi (0700) — so lazy logo loads during
        # rendering would fail and the card would fall back to text. Warm the
        # cache here, pre-drop (fonts are likewise loaded at import, pre-drop).
        logos.preload_all()

        self.matrix = RGBMatrix(options=options)

        # Setup canvas
        self.canvas = self.matrix.CreateFrameCanvas()
        # Per-panel vertical draw offset. 0 for a normally-wired panel; the
        # Port-3 shim overrides this. Pixel writes go through self.set_pixel so
        # the offset lives in ONE place (SetPixel can't be monkeypatched — it's
        # an immutable method on the C-extension FrameCanvas type).
        self._lane_offset_y = 0
        self.canvas.Clear()

        # Data to render
        self._data_index = 0
        self._data = []

        # Start Looking for planes
        self.overhead = Overhead()
        self.overhead.grab_data()

        # Start the weather feed (for the idle screen)
        weather.start()

        # Initalise animator and scenes
        super().__init__()

        # Overwrite any default settings from
        # Animator or Scenes
        self.delay = frames.PERIOD

    def draw_square(self, x0, y0, x1, y1, colour):
        for x in range(x0, x1):
            _ = graphics.DrawLine(self.canvas, x, y0, x, y1, colour)

    def set_pixel(self, x, y, r, g, b):
        # Single entry point for pixel writes so the panel draw-offset is applied
        # in one place (see self._lane_offset_y). Scenes call this, never
        # canvas.SetPixel directly.
        self.canvas.SetPixel(x, y + self._lane_offset_y, r, g, b)

    @Animator.KeyFrame.add(0)
    def clear_screen(self):
        # First operation after
        # a screen reset
        self.canvas.Clear()

    @Animator.KeyFrame.add(frames.PER_SECOND * 5)
    def check_for_loaded_data(self, count):
        if self.overhead.new_data:
            # Check if there's data
            there_is_data = len(self._data) > 0 or not self.overhead.data_is_empty

            # this marks self.overhead.data as no longer new
            new_data = self.overhead.data

            # See if this matches the data already on the screen
            # This test only checks if it's 2 lists with the same
            # callsigns, regardless or order
            data_is_different = not callsigns_match(self._data, new_data)

            if data_is_different:
                self._data_index = 0
                self._data_all_looped = False
                self._data = new_data

            # Only reset if there's flight data already
            # on the screen, of if there's some new
            # data available to draw which is different
            # from the current data
            reset_required = there_is_data and data_is_different

            if reset_required:
                self.reset_scene()

    @Animator.KeyFrame.add(1)
    def sync(self, count):
        # Redraw screen every frame
        _ = self.matrix.SwapOnVSync(self.canvas)

    @Animator.KeyFrame.add(frames.PER_SECOND * 30)
    def grab_new_data(self, count):
        # Only grab data if we're not already searching
        # for planes, or if there's new data available
        # which hasn't been displayed.
        #
        # We also need wait until all previously grabbed
        # data has been looped through the display.
        #
        # Last, if our internal store of the data
        # is empty, try and grab data
        if not (self.overhead.processing and self.overhead.new_data) and (
            self._data_all_looped or len(self._data) <= 1
        ):
            self.overhead.grab_data()

    def run(self):
        try:
            # Start loop
            print("Press CTRL-C to stop")
            self.play()

        except KeyboardInterrupt:
            print("Exiting\n")
            sys.exit(0)
