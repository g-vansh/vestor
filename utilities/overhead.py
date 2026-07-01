from FlightRadar24.api import FlightRadar24API
from threading import Thread, Lock
from time import sleep
import math

from requests.exceptions import ConnectionError
from urllib3.exceptions import NewConnectionError
from urllib3.exceptions import MaxRetryError

try:
    # Attempt to load config data
    from config import MIN_ALTITUDE

except (ModuleNotFoundError, NameError, ImportError):
    # If there's no config data
    MIN_ALTITUDE = 0  # feet

try:
    from config import MAX_ALTITUDE

except (ModuleNotFoundError, NameError, ImportError):
    MAX_ALTITUDE = 10000  # feet

try:
    from config import DEMO_MODE

except (ModuleNotFoundError, NameError, ImportError):
    DEMO_MODE = False

# Sample flights for DEMO_MODE — realistic KBOS routes across airlines with
# logos and a spread of altitudes (so the FR24 colour ramp + climb/descent
# arrows all show). Cycled by the display like real overhead traffic.
DEMO_FLIGHTS = [
    {"callsign": "AAL191", "origin": "BOS", "destination": "LAX", "plane": "Airbus A321",
     "aircraft_code": "A321", "altitude": 31000, "vertical_speed": 640},
    {"callsign": "JBU671", "origin": "BOS", "destination": "MCO", "plane": "Airbus A320",
     "aircraft_code": "A320", "altitude": 12500, "vertical_speed": 1408},
    {"callsign": "DAL1180", "origin": "BOS", "destination": "ATL", "plane": "Boeing 737-900",
     "aircraft_code": "B739", "altitude": 8000, "vertical_speed": 1216},
    {"callsign": "BAW238", "origin": "LHR", "destination": "BOS", "plane": "Boeing 777-200",
     "aircraft_code": "B772", "altitude": 4200, "vertical_speed": -768},
    {"callsign": "UAL2456", "origin": "SFO", "destination": "BOS", "plane": "Boeing 757-200",
     "aircraft_code": "B752", "altitude": 36000, "vertical_speed": 0},
    {"callsign": "SWA1234", "origin": "BOS", "destination": "BWI", "plane": "Boeing 737-800",
     "aircraft_code": "B738", "altitude": 21000, "vertical_speed": -640},
]

RETRIES = 3
RATE_LIMIT_DELAY = 1
MAX_FLIGHT_LOOKUP = 5
EARTH_RADIUS_KM = 6371
BLANK_FIELDS = ["", "N/A", "NONE"]

try:
    # Attempt to load config data
    from config import ZONE_HOME, LOCATION_HOME

    ZONE_DEFAULT = ZONE_HOME
    LOCATION_DEFAULT = LOCATION_HOME

except (ModuleNotFoundError, NameError, ImportError):
    # If there's no config data
    ZONE_DEFAULT = {"tl_y": 62.61, "tl_x": -13.07, "br_y": 49.71, "br_x": 3.46}
    LOCATION_DEFAULT = [51.509865, -0.118092, EARTH_RADIUS_KM]


def distance_from_flight_to_home(flight, home=LOCATION_DEFAULT):
    def polar_to_cartesian(lat, long, alt):
        DEG2RAD = math.pi / 180
        return [
            alt * math.cos(DEG2RAD * lat) * math.sin(DEG2RAD * long),
            alt * math.sin(DEG2RAD * lat),
            alt * math.cos(DEG2RAD * lat) * math.cos(DEG2RAD * long),
        ]

    def feet_to_meters_plus_earth(altitude_ft):
        altitude_km = 0.0003048 * altitude_ft
        return altitude_km + EARTH_RADIUS_KM

    try:
        (x0, y0, z0) = polar_to_cartesian(
            flight.latitude,
            flight.longitude,
            feet_to_meters_plus_earth(flight.altitude),
        )

        (x1, y1, z1) = polar_to_cartesian(*home)

        dist = math.sqrt((x1 - x0) ** 2 + (y1 - y0) ** 2 + (z1 - z0) ** 2)

        return dist

    except AttributeError:
        # on error say it's far away
        return 1e6


class Overhead:
    def __init__(self):
        self._api = FlightRadar24API()
        self._lock = Lock()
        self._data = []
        self._new_data = False
        self._processing = False

    def grab_data(self):
        Thread(target=self._grab_data).start()

    def _grab_data(self):
        # Mark data as old
        with self._lock:
            self._new_data = False
            self._processing = True

        # Demo mode: serve the sample flights and skip FR24 entirely.
        if DEMO_MODE:
            with self._lock:
                self._data = list(DEMO_FLIGHTS)
                self._new_data = True
                self._processing = False
            return

        data = []

        # Grab flight details
        try:
            bounds = self._api.get_bounds(ZONE_DEFAULT)
            flights = self._api.get_flights(bounds=bounds)

            # Sort flights by closest first
            flights = [
                f
                for f in flights
                if f.altitude < MAX_ALTITUDE and f.altitude > MIN_ALTITUDE
            ]
            flights = sorted(flights, key=lambda f: distance_from_flight_to_home(f))

            for flight in flights[:MAX_FLIGHT_LOOKUP]:
                retries = RETRIES

                while retries:
                    # Rate limit protection
                    sleep(RATE_LIMIT_DELAY)

                    # Grab and store details
                    try:
                        details = self._api.get_flight_details(flight)

                        # Get plane type
                        try:
                            plane = details["aircraft"]["model"]["text"]
                        except (KeyError, TypeError):
                            plane = ""

                        # Tidy up what we pass along
                        plane = plane if not (plane.upper() in BLANK_FIELDS) else ""

                        origin = (
                            flight.origin_airport_iata
                            if not (flight.origin_airport_iata.upper() in BLANK_FIELDS)
                            else ""
                        )

                        destination = (
                            flight.destination_airport_iata
                            if not (flight.destination_airport_iata.upper() in BLANK_FIELDS)
                            else ""
                        )

                        callsign = (
                            flight.callsign
                            if not (flight.callsign.upper() in BLANK_FIELDS)
                            else ""
                        )

                        # ICAO aircraft type designator (e.g. B738, A320) — a
                        # basic Flight attribute, short + clean for the panel's
                        # telemetry line. Guard it in case it's absent/blank.
                        try:
                            aircraft_code = flight.aircraft_code or ""
                        except AttributeError:
                            aircraft_code = ""
                        aircraft_code = (
                            aircraft_code if aircraft_code.upper() not in BLANK_FIELDS else ""
                        )

                        data.append(
                            {
                                "plane": plane,
                                "aircraft_code": aircraft_code,
                                "origin": origin,
                                "destination": destination,
                                "vertical_speed": flight.vertical_speed,
                                "altitude": flight.altitude,
                                "callsign": callsign,
                            }
                        )
                        break

                    except (KeyError, AttributeError):
                        retries -= 1

            with self._lock:
                self._new_data = True
                self._processing = False
                self._data = data

        except (ConnectionError, NewConnectionError, MaxRetryError):
            self._new_data = False
            self._processing = False

    @property
    def new_data(self):
        with self._lock:
            return self._new_data

    @property
    def processing(self):
        with self._lock:
            return self._processing

    @property
    def data(self):
        with self._lock:
            self._new_data = False
            return self._data

    @property
    def data_is_empty(self):
        return len(self._data) == 0


# Main function
if __name__ == "__main__":

    o = Overhead()
    o.grab_data()
    while not o.new_data:
        print("processing...")
        sleep(1)

    print(o.data)
