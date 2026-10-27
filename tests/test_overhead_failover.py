"""Failover / rate-limit behaviour of utilities/overhead.py (no network).

A fake clock drives both the governor and the Overhead poller, and a fake
session answers per hostname, so hours of 24/7 polling run in milliseconds.
"""

import os
import sys
import unittest
from types import SimpleNamespace
from unittest import mock
from urllib.parse import urlsplit

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

import requests  # noqa: E402

import utilities.overhead as oh  # noqa: E402
from utilities.ratelimit import DAY, HostPolicy, RateGovernor  # noqa: E402

try:
    from tests.test_ratelimit import FakeClock, FakeResp, MaxRng, MidRng  # noqa: E402
except ImportError:                                      # run from inside tests/
    from test_ratelimit import FakeClock, FakeResp, MaxRng, MidRng  # noqa: E402

FI, LOL, LIVE, ADSBDB = "opendata.adsb.fi", "api.adsb.lol", "api.airplanes.live", "api.adsbdb.com"

EXPECTED_KEYS = {"plane", "aircraft_code", "origin", "destination", "vertical_speed",
                 "altitude", "callsign", "ground_speed", "heading", "squawk",
                 "registration", "distance_mi", "bearing"}


def aircraft(callsign="JBU251", lat=42.36, lon=-71.03, alt=3000, vs=-800):
    """One ADSBx-v2 aircraft inside ZONE_HOME and the altitude band."""
    return {"hex": "a1b2c3", "flight": f"{callsign:<8}", "r": "N123JB", "t": "A320",
            "alt_baro": alt, "gs": 180.4, "track": 220.0, "baro_rate": vs,
            "squawk": "1234", "lat": lat, "lon": lon}


def ok(*acs, key="ac"):
    return FakeResp(200, payload={key: list(acs), "now": 0})


FORBIDDEN_LIVE = FakeResp(403, payload={"error": "Please contact us at contact@airplanes.live."},
                          text='{"error": "Please contact us at contact@airplanes.live."}')
UNKNOWN_CS = FakeResp(404, payload={"response": "unknown callsign"})


class FakeSession:
    def __init__(self, clock, handlers):
        self.clock = clock
        self.handlers = handlers
        self.calls = []                                  # (t, host, url)

    def get(self, url, timeout=None, **kw):
        host = urlsplit(url).hostname
        self.calls.append((self.clock.t, host, url))
        h = self.handlers.get(host)
        if h is None:
            raise requests.ConnectionError(f"no handler for {host}")
        r = h(url) if callable(h) else h
        if isinstance(r, BaseException):
            raise r
        return r

    def times(self, host):
        return [t for t, h, _ in self.calls if h == host]

    def count(self, host):
        return len(self.times(host))


class FakeFR24:
    def __init__(self, clock, behaviour):
        self.clock = clock
        self.behaviour = behaviour                       # callable() -> list or raises
        self.calls = []

    def get_bounds(self, zone):
        return "b"

    def get_bounds_by_point(self, lat, lon, r):
        return "b"

    def get_flights(self, bounds=None):
        self.calls.append(self.clock.t)
        r = self.behaviour()
        if isinstance(r, BaseException):
            raise r
        return r


def fr24_flight(cs, o, d):
    return SimpleNamespace(callsign=cs, origin_airport_iata=o, destination_airport_iata=d)


class CloudflareError(Exception):
    """Same class name FlightRadarAPI raises when FR24 blocks us."""
    response = None


class Base(unittest.TestCase):
    def setUp(self):
        patches = [mock.patch.object(oh, "DEMO_MODE", False),
                   mock.patch.object(oh.Overhead, "_log", lambda *a: None),
                   mock.patch.object(oh, "STALE_CLEAR_SECONDS", 0),
                   mock.patch.object(oh, "STALE_AFTER_SECONDS", 120)]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    def make(self, handlers, fr24=None, overrides=None, rng=None, providers=None):
        self.clock = FakeClock()
        handlers.setdefault(ADSBDB, UNKNOWN_CS)
        policies = oh.build_policies(oh._BUDGET_OVERRIDES)
        policies.update(overrides or {})
        self.gov = RateGovernor(policies=policies, clock=self.clock, wall=self.clock.wall,
                                sleep=self.clock.sleep, rng=rng or MaxRng(),
                                log=lambda m: None)
        self.session = FakeSession(self.clock, handlers)
        self.ov = oh.Overhead(session=self.session, governor=self.gov, fr24=fr24,
                              clock=self.clock, start=False,
                              providers=providers or ["adsb.fi", "adsb.lol", "airplanes.live"])
        return self.ov

    def poll(self, n=1, step=5.0):
        for _ in range(n):
            self.ov._fetch_once()
            self.assertFalse(self.ov.processing)
            self.clock.advance(step)


class TestProviders(Base):
    def test_primary_used_when_healthy_and_schema_unchanged(self):
        ov = self.make({FI: ok(aircraft()), LOL: AssertionError("fallback must not be used"),
                        LIVE: AssertionError("must not be used")})
        self.poll()
        self.assertTrue(ov.new_data)
        data = ov.data
        self.assertFalse(ov.new_data)
        self.assertEqual(len(data), 1)
        self.assertEqual(set(data[0]), EXPECTED_KEYS)
        f = data[0]
        self.assertEqual((f["callsign"], f["aircraft_code"], f["altitude"]), ("JBU251", "A320", 3000))
        self.assertEqual((f["ground_speed"], f["registration"], f["squawk"]), (180, "N123JB", "1234"))
        self.assertEqual(ov.source, "adsb.fi")
        self.assertFalse(ov.stale)
        self.assertEqual(self.session.count(LOL) + self.session.count(LIVE), 0)

    def test_adsbfi_v2_aircraft_key_and_empty_sky(self):
        ov = self.make({FI: ok(aircraft(), key="aircraft")})
        self.poll()
        self.assertEqual([f["callsign"] for f in ov.data], ["JBU251"])
        self.assertEqual(oh._aircraft_list({"ac": None}), [])
        self.assertEqual(oh._aircraft_list({"aircraft": []}), [])
        with self.assertRaises(ValueError):
            oh._aircraft_list({"error": "Please contact us"})

    def test_bad_payload_fails_over(self):
        ov = self.make({FI: FakeResp(200, payload={"error": "maintenance"}), LOL: ok(aircraft())})
        self.poll()
        self.assertEqual(ov.source, "adsb.lol")
        self.assertEqual(self.gov.stats("adsb.fi")["errors"], 1)

    def test_dead_primary_fails_over_same_poll_and_is_reprobed(self):
        state = {"fi_up": False}

        def fi(url):
            return ok(aircraft()) if state["fi_up"] else requests.ConnectionError("refused")

        ov = self.make({FI: fi, LOL: ok(aircraft("DAL100")), LIVE: FORBIDDEN_LIVE})
        self.poll()
        self.assertEqual(ov.source, "adsb.lol")                    # same poll, no gap
        self.assertEqual([f["callsign"] for f in ov.data], ["DAL100"])

        self.poll(11)                                              # first minute down
        fi_calls = self.session.times(FI)
        self.assertLessEqual(len(fi_calls), 5)                     # backoff, not 12 tries
        self.assertEqual(fi_calls[:4], [1000.0, 1005.0, 1015.0, 1035.0])
        self.assertLessEqual(self.session.count(LOL), 4)           # paced at 15 s
        self.assertEqual(self.session.count(LIVE), 1)              # 403 -> parked 1 h
        self.assertFalse(ov.data_is_empty)                         # never blanked

        state["fi_up"] = True                                      # primary recovers
        t_up = self.clock.t
        for _ in range(80):
            self.poll()
            if ov.source == "adsb.fi":
                break
        self.assertEqual(ov.source, "adsb.fi")
        self.assertLessEqual(self.clock.t - t_up, 300 + 5)         # within the 5 min cap
        self.assertEqual([f["callsign"] for f in ov.data], ["JBU251"])

    def test_paced_failover_does_not_spam_the_journal(self):
        ov = self.make({FI: requests.ConnectionError("refused"), LOL: ok(aircraft()),
                        LIVE: FORBIDDEN_LIVE})
        with mock.patch.object(oh.Overhead, "_log") as log:
            self.poll(int(600 / 5))                                # 10 min on the fallback
        self.assertFalse(ov.stale)                                 # fresh every 15 s
        self.assertLessEqual(log.call_count, 3)                    # transitions only
        self.assertEqual(ov.source, "adsb.lol")

    def test_429_retry_after_on_primary(self):
        replies = [FakeResp(429, {"Retry-After": "90"})]         # longer than the 60 s floor

        def fi(url):
            return replies.pop(0) if replies else ok(aircraft())

        ov = self.make({FI: fi, LOL: ok(aircraft("DAL100")), LIVE: FORBIDDEN_LIVE})
        self.poll(18)                                              # t = 0 .. 85
        self.assertEqual(self.session.count(FI), 1)                # silent for 90 s
        self.assertEqual(self.gov.stats("adsb.fi")["throttled"], 1)
        self.assertEqual(ov.source, "adsb.lol")
        self.poll()                                                # t = 90
        self.assertEqual(self.session.count(FI), 2)
        self.assertEqual(ov.source, "adsb.fi")

    def test_budget_refusal_fails_over(self):
        tight = HostPolicy(min_interval=0.0, per_minute=1, burst=1)
        ov = self.make({FI: ok(aircraft()), LOL: ok(aircraft("DAL100"))},
                       overrides={"adsb.fi": tight})
        self.poll()
        self.assertEqual(ov.source, "adsb.fi")
        self.poll()                                                # adsb.fi out of budget
        self.assertEqual(ov.source, "adsb.lol")
        self.assertEqual(self.session.count(FI), 1)                # refused locally
        self.assertGreaterEqual(self.gov.stats("adsb.fi")["skipped"], 1)

    def test_all_providers_down_keeps_last_data_then_marks_stale(self):
        state = {"down": False}

        def handler(good):
            def h(url):
                return requests.Timeout("read timed out") if state["down"] else good
            return h

        ov = self.make({FI: handler(ok(aircraft())), LOL: handler(ok(aircraft())),
                        LIVE: handler(ok(aircraft()))})
        self.poll()
        before = ov.data
        self.assertEqual(len(before), 1)
        state["down"] = True
        start = self.clock.t
        sent_before = len(self.session.calls)
        while self.clock.t - start < 150:
            self.poll()
            self.assertFalse(ov.new_data)                          # nothing new published
            self.assertFalse(ov.data_is_empty)                     # board not blanked
            self.assertEqual(ov.stale, ov.data_age > 120)
        self.assertTrue(ov.stale)
        self.assertEqual(ov.data, before)
        self.assertLessEqual(len(self.session.calls) - sent_before, 20)   # 30 polls, backoff
        state["down"] = False
        for _ in range(80):
            self.poll()
            if ov.new_data:
                break
        self.assertTrue(ov.new_data)
        self.assertFalse(ov.stale)

    def test_all_down_from_boot_goes_stale_without_crashing(self):
        ov = self.make({FI: FakeResp(503), LOL: FakeResp(502), LIVE: FORBIDDEN_LIVE})
        self.poll(30)
        self.assertTrue(ov.data_is_empty)
        self.assertTrue(ov.stale)
        self.assertIsNone(ov.source)

    def test_optional_stale_clear(self):
        state = {"down": False}
        ov = self.make({FI: lambda u: requests.Timeout() if state["down"] else ok(aircraft()),
                        LOL: FakeResp(503), LIVE: FORBIDDEN_LIVE})
        with mock.patch.object(oh, "STALE_CLEAR_SECONDS", 300):
            self.poll()
            ov.data
            state["down"] = True
            self.poll(int(250 / 5))
            self.assertFalse(ov.data_is_empty)
            self.poll(int(100 / 5))
            self.assertTrue(ov.data_is_empty)
            self.assertTrue(ov.new_data)                           # display returns to idle

    def test_adsb_lol_daily_budget_in_long_primary_outage(self):
        ov = self.make({FI: FakeResp(503), LOL: ok(aircraft()), LIVE: FORBIDDEN_LIVE})
        t0 = self.clock.t
        self.poll(int((DAY + 2 * 3600) / 5))                       # 26 h of 5 s polls
        lol = self.session.times(LOL)
        first_day = [t for t in lol if t < t0 + DAY]
        self.assertEqual(len(first_day), 400)                      # budget, not the 500 limit
        j = 0
        for i, t in enumerate(lol):                                # any 24 h window
            while lol[j] <= t - DAY:
                j += 1
            self.assertLessEqual(i - j + 1, 400)
        self.assertGreater(len(lol), 400)                          # quota returns next day
        self.assertLessEqual(self.session.count(FI), 26 * 3600 / 150)   # backoff ~5 min
        self.assertLessEqual(self.session.count(LIVE), 27)         # ~1 probe per hour
        self.assertGreater(self.gov.stats("adsb.lol")["skipped"], 0)


class TestFR24(Base):
    def _unique_callsigns(self):
        n = {"i": 0}

        def fi(url):
            n["i"] += 1
            return ok(aircraft(f"JBU{n['i']}"))
        return fi

    def test_exceptions_escalate_ladder_then_probe_recovers(self):
        state = {"up": False}

        def behaviour():
            if state["up"]:
                return [fr24_flight("X1", "BOS", "MCO")]
            return CloudflareError("Blocked by Cloudflare")

        fr = FakeFR24(None, behaviour)
        ov = self.make({FI: self._unique_callsigns()}, fr24=fr, rng=MidRng())
        fr.clock = self.clock
        self.poll(int(40 * 60 / 5))                              # 40 min of polling
        gaps = [b - a for a, b in zip(fr.calls, fr.calls[1:])]
        self.assertEqual(len(fr.calls), 5)                         # 0, +60, +300, +900, +900
        for gap, step in zip(gaps, (60, 300, 900, 900)):
            self.assertGreaterEqual(gap, step)
            self.assertLess(gap, step + 5.01)
        self.assertFalse(ov._fr24_healthy)
        state["up"] = True
        self.poll(int(20 * 60 / 5))
        after = [t for t in fr.calls if t > 1000 + 40 * 60]
        self.assertTrue(ov._fr24_healthy)
        recovered_gaps = [b - a for a, b in zip(after, after[1:])]
        self.assertTrue(recovered_gaps)
        self.assertTrue(all(20 <= g < 25.01 for g in recovered_gaps))   # back to the floor

    def test_empty_answers_escalate_after_tolerance(self):
        fr = FakeFR24(None, lambda: [])
        self.make({FI: self._unique_callsigns()}, fr24=fr, rng=MidRng())
        fr.clock = self.clock
        self.poll(int(30 * 60 / 5))
        gaps = [round(b - a) for a, b in zip(fr.calls, fr.calls[1:])]
        self.assertEqual(gaps[:4], [20, 60, 300, 900])            # 1 tolerated, then ladder

    def test_failure_falls_back_to_adsbdb_and_reuses_last_map(self):
        calls = {"n": 0}

        def behaviour():
            calls["n"] += 1
            if calls["n"] == 1:
                return [fr24_flight("JBU251", "BOS", "MCO"), fr24_flight("DAL100", "ATL", "BOS")]
            return CloudflareError("Blocked by Cloudflare")

        overhead_now = {"acs": [aircraft("JBU251")]}
        adsbdb = FakeResp(200, payload={"response": {"flightroute": {
            "origin": {"iata_code": "BOS"}, "destination": {"iata_code": "SEA"}}}})
        fr = FakeFR24(None, behaviour)
        ov = self.make({FI: lambda u: ok(*overhead_now["acs"]), ADSBDB: adsbdb}, fr24=fr)
        fr.clock = self.clock

        def routes():
            return {f["callsign"]: (f["origin"], f["destination"]) for f in ov.data}

        self.poll()                                                # FR24 call 1 succeeds
        self.assertEqual(routes(), {"JBU251": ("BOS", "MCO")})
        self.clock.advance(20)                                     # past the 20 s floor
        overhead_now["acs"] = [aircraft("JBU251"), aircraft("UAL9", lat=42.37)]
        self.poll()                                                # FR24 call 2 throws
        self.assertFalse(ov._fr24_healthy)
        self.assertEqual(routes()["UAL9"], ("BOS", "SEA"))         # adsbdb fallback
        self.clock.advance(20)
        overhead_now["acs"].append(aircraft("DAL100", lat=42.38))
        self.poll()                                                # FR24 backing off
        self.assertEqual(routes()["DAL100"], ("ATL", "BOS"))       # last good FR24 map
        self.assertEqual(len(fr.calls), 2)                         # no hammering

    def test_no_fr24_package(self):
        ov = self.make({FI: ok(aircraft())}, fr24=None)
        self.poll()
        self.assertEqual(len(ov.data), 1)


class TestAdsbdb(Base):
    def test_caches_hits_and_misses(self):
        def adsbdb(url):
            if url.endswith("/JBU251"):
                return FakeResp(200, payload={"response": {"flightroute": {
                    "origin": {"iata_code": "BOS"}, "destination": {"iata_code": "MCO"}}}})
            return UNKNOWN_CS

        ov = self.make({FI: ok(), ADSBDB: adsbdb})
        self.assertEqual(ov._adsbdb_route("JBU251"), ("BOS", "MCO"))
        self.assertEqual(ov._adsbdb_route("JBU251"), ("BOS", "MCO"))
        self.assertEqual(ov._adsbdb_route("N123AB"), ("", ""))
        self.assertEqual(ov._adsbdb_route("N123AB"), ("", ""))
        self.assertEqual(self.session.count(ADSBDB), 2)             # one per callsign
        self.clock.advance(oh.ADSBDB_MISS_TTL + 1)
        ov._adsbdb_route("N123AB")
        ov._adsbdb_route("JBU251")                                 # hit TTL is longer
        self.assertEqual(self.session.count(ADSBDB), 3)

    def test_429_respected_and_not_cached(self):
        replies = [FakeResp(429, text='{"response":"rate limited for 60 seconds"}')]
        good = FakeResp(200, payload={"response": {"flightroute": {
            "origin": {"iata_code": "BOS"}, "destination": {"iata_code": "MCO"}}}})
        ov = self.make({FI: ok(), ADSBDB: lambda u: replies.pop(0) if replies else good})
        self.assertEqual(ov._adsbdb_route("JBU251"), ("", ""))
        self.assertEqual(ov._adsbdb_route("JBU251"), ("", ""))     # refused locally
        self.assertEqual(self.session.count(ADSBDB), 1)
        self.clock.advance(61)
        self.assertEqual(ov._adsbdb_route("JBU251"), ("BOS", "MCO"))
        self.assertEqual(self.session.count(ADSBDB), 2)

    def test_timeout_not_cached_but_backed_off(self):
        replies = [requests.Timeout("slow")]
        good = FakeResp(200, payload={"response": {"flightroute": {
            "origin": {"iata_code": "BOS"}, "destination": {"iata_code": "MCO"}}}})
        ov = self.make({FI: ok(), ADSBDB: lambda u: replies.pop(0) if replies else good})
        self.assertEqual(ov._adsbdb_route("JBU251"), ("", ""))
        self.assertEqual(ov._adsbdb_route("JBU251"), ("", ""))
        self.assertEqual(self.session.count(ADSBDB), 1)
        self.clock.advance(15)
        self.assertEqual(ov._adsbdb_route("JBU251"), ("BOS", "MCO"))

    def test_burst_of_new_callsigns_in_one_poll(self):
        acs = [aircraft(f"JBU{i}", lat=42.36 + i * 0.001) for i in range(5)]
        ov = self.make({FI: ok(*acs)})
        self.poll()
        self.assertEqual(len(ov.data), 5)
        self.assertEqual(self.session.count(ADSBDB), 5)            # spaced, none refused


class TestDemoMode(Base):
    def test_demo_mode_skips_network(self):
        ov = self.make({})
        with mock.patch.object(oh, "DEMO_MODE", True):
            self.poll()
            self.assertEqual(ov.data, oh.DEMO_FLIGHTS)
            self.assertFalse(ov.stale)
        self.assertEqual(self.session.calls, [])


if __name__ == "__main__":
    unittest.main()
