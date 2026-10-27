"""Unit tests for utilities/ratelimit.py (stdlib unittest + mock, no network)."""

import email.utils
import os
import random
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from utilities.ratelimit import (  # noqa: E402
    DAY, FORBIDDEN, OK, THROTTLED, ERROR, HostPolicy, RateGovernor, RateLimited,
    parse_reset, parse_retry_after,
)

WALL0 = 1_800_000_000.0


class FakeClock:
    """Monotonic + wall clock + sleep, all driven by advance()."""

    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t

    def wall(self):
        return WALL0 + self.t

    def advance(self, s):
        self.t += s

    sleep = advance


class MaxRng:
    """uniform(a, b) -> b: makes full jitter deterministic (the ceiling)."""

    def uniform(self, a, b):
        return b


class MidRng:
    def uniform(self, a, b):
        return (a + b) / 2.0


class FakeResp:
    def __init__(self, status=200, headers=None, text="", payload=None):
        self.status_code = status
        self.headers = headers or {}
        self.text = text
        self._payload = payload

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


def gov_with(policy, clock=None, rng=None, **kw):
    clock = clock or FakeClock()
    g = RateGovernor(policies={"h": policy}, clock=clock, wall=clock.wall,
                     sleep=clock.sleep, rng=rng or MaxRng(), log=lambda m: None, **kw)
    return g, clock


FAST = dict(min_interval=0.0, per_minute=6000, burst=1000)


class TestBudgets(unittest.TestCase):
    def test_min_interval_spacing(self):
        g, c = gov_with(HostPolicy(min_interval=2.0, per_minute=600, burst=10))
        self.assertTrue(g.allow("h"))
        self.assertFalse(g.allow("h"))
        wait, reason = g.wait_time("h")
        self.assertEqual(reason, "spacing")
        self.assertAlmostEqual(wait, 2.0)
        c.advance(2.0)
        self.assertTrue(g.allow("h"))
        s = g.stats("h")
        self.assertEqual((s["sent"], s["skipped"]), (2, 1))

    def test_max_wait_absorbs_short_spacing_only(self):
        g, c = gov_with(HostPolicy(min_interval=1.0, per_minute=600, burst=10))
        self.assertTrue(g.allow("h"))
        t0 = c.t
        self.assertTrue(g.allow("h", max_wait=2.0))      # slept 1 s instead of refusing
        self.assertAlmostEqual(c.t - t0, 1.0)
        g.record("h", FakeResp(429, {"Retry-After": "30"}))
        t1 = c.t
        self.assertFalse(g.allow("h", max_wait=60.0))    # cooldowns are never slept
        self.assertEqual(c.t, t1)

    def test_minute_token_bucket(self):
        g, c = gov_with(HostPolicy(min_interval=0.0, per_minute=6, burst=3))
        self.assertEqual([g.allow("h") for _ in range(4)], [True, True, True, False])
        self.assertEqual(g.wait_time("h")[1], "minute-budget")
        c.advance(10.0)                                  # 6/min = one token per 10 s
        self.assertTrue(g.allow("h"))
        self.assertFalse(g.allow("h"))

    def test_minute_bucket_never_exceeds_rate_over_long_run(self):
        g, c = gov_with(HostPolicy(min_interval=0.0, per_minute=20, burst=2))
        granted = 0
        for _ in range(6000):                            # 10 minutes in 0.1 s steps
            granted += g.allow("h")
            c.advance(0.1)
        self.assertLessEqual(granted, 20 * 10 + 2)

    def test_daily_budget_sliding_window(self):
        g, c = gov_with(HostPolicy(per_day=3, **FAST))
        t0 = c.t
        self.assertTrue(g.allow("h"))
        c.advance(3600)
        self.assertTrue(g.allow("h"))
        c.advance(3600)
        self.assertTrue(g.allow("h"))
        self.assertFalse(g.allow("h"))
        self.assertEqual(g.wait_time("h")[1], "day-budget")
        self.assertEqual(g.stats("h")["day_used"], 3)
        c.t = t0 + DAY - 1                               # first send not yet 24 h old
        self.assertFalse(g.allow("h"))
        c.t = t0 + DAY                                   # first send ages out -> one slot
        self.assertTrue(g.allow("h"))
        self.assertFalse(g.allow("h"))
        c.t = t0 + DAY + 3600                            # second send ages out
        self.assertTrue(g.allow("h"))

    def test_daily_budget_holds_in_every_24h_window(self):
        g, c = gov_with(HostPolicy(per_day=400, min_interval=15.0, per_minute=4, burst=1))
        sends = []
        for _ in range(int(2 * DAY / 5)):                # 48 h of 5 s polls
            if g.allow("h"):
                sends.append(c.t)
            c.advance(5)
        j = 0
        for i, t in enumerate(sends):                    # max sends in any 24 h window
            while sends[j] <= t - DAY:
                j += 1
            self.assertLessEqual(i - j + 1, 400)
        self.assertEqual(len(sends), 800)

    def test_daily_budget_persists_across_restart(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "state.json")
            c = FakeClock()
            g1, _ = gov_with(HostPolicy(per_day=3, **FAST), clock=c, state_path=path)
            for _ in range(3):
                self.assertTrue(g1.allow("h"))
            # Restart: a new process has a new monotonic origin but the same wall clock.
            c2 = FakeClock(t=5.0)
            c2.wall = lambda: WALL0 + c.t + 10                 # 10 s later in real time
            g2 = RateGovernor(policies={"h": HostPolicy(per_day=3, **FAST)}, clock=c2,
                              wall=c2.wall, sleep=c2.sleep, rng=MaxRng(),
                              state_path=path, log=lambda m: None)
            self.assertFalse(g2.allow("h"))
            self.assertEqual(g2.wait_time("h")[1], "day-budget")
            self.assertEqual(g2.stats("h")["day_used"], 3)

    def test_cooldown_persists_across_restart(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "state.json")
            c = FakeClock()
            g1, _ = gov_with(HostPolicy(**FAST), clock=c, state_path=path)
            g1.allow("h")
            g1.record("h", FakeResp(429, {"Retry-After": "600"}))
            c2 = FakeClock(t=1.0)
            c2.wall = lambda: WALL0 + c.t + 100
            g2 = RateGovernor(policies={"h": HostPolicy(**FAST)}, clock=c2, wall=c2.wall,
                              sleep=c2.sleep, rng=MaxRng(), state_path=path,
                              log=lambda m: None)
            wait, _ = g2.wait_time("h")
            self.assertAlmostEqual(wait, 500, delta=1)

    def test_unwritable_state_path_is_harmless(self):
        g, _ = gov_with(HostPolicy(per_day=3, **FAST),
                        state_path="/nonexistent-dir/for/sure/state.json")
        self.assertTrue(g.allow("h"))

    def test_thread_safety_never_over_grants(self):
        g, _ = gov_with(HostPolicy(min_interval=0.0, per_minute=60, burst=10))
        results = []
        lock = threading.Lock()

        def worker():
            for _ in range(50):
                ok = g.allow("h")
                with lock:
                    results.append(ok)

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(sum(results), 10)               # frozen clock: exactly the burst
        self.assertEqual(g.stats("h")["skipped"], 400 - 10)


class TestServerPushback(unittest.TestCase):
    def setUp(self):
        self.g, self.c = gov_with(HostPolicy(min_interval=0.0, per_minute=600, burst=10,
                                             backoff_base=5, backoff_cap=300,
                                             throttle_floor=60, forbidden_cooldown=600))

    def test_429_retry_after_seconds(self):
        self.assertTrue(self.g.allow("h"))
        kind = self.g.record("h", FakeResp(429, {"Retry-After": "120"}))
        self.assertEqual(kind, THROTTLED)
        wait, reason = self.g.wait_time("h")
        self.assertAlmostEqual(wait, 120)
        self.assertEqual(reason, THROTTLED)
        self.c.advance(119)
        self.assertFalse(self.g.allow("h"))
        self.c.advance(1.5)
        self.assertTrue(self.g.allow("h"))
        self.assertEqual(self.g.stats("h")["throttled"], 1)

    def test_429_retry_after_http_date(self):
        when = email.utils.formatdate(self.c.wall() + 300, usegmt=True)
        self.g.record("h", FakeResp(429, {"retry-after": when}))   # lower-case header
        self.assertAlmostEqual(self.g.wait_time("h")[0], 300, delta=1)

    def test_503_retry_after(self):
        kind = self.g.record("h", FakeResp(503, {"Retry-After": "30"}))
        self.assertEqual(kind, ERROR)
        self.assertAlmostEqual(self.g.wait_time("h")[0], 30)
        self.assertEqual(self.g.stats("h")["errors"], 1)

    def test_429_without_hint_uses_throttle_floor(self):
        self.g.record("h", FakeResp(429))
        self.assertAlmostEqual(self.g.wait_time("h")[0], 60)

    def test_429_body_hint_adsbdb_style(self):
        self.g.record("h", FakeResp(429, text='{"response":"rate limited for 45 seconds"}'))
        self.assertAlmostEqual(self.g.wait_time("h")[0], 45)

    def test_x_ratelimit_reset_epoch_and_delta(self):
        self.g.record("h", FakeResp(429, {"X-RateLimit-Reset": str(int(self.c.wall() + 90))}))
        self.assertAlmostEqual(self.g.wait_time("h")[0], 90, delta=1)
        g2, _ = gov_with(HostPolicy(**FAST, throttle_floor=0, backoff_base=0.001))
        g2.record("h", FakeResp(429, {"RateLimit-Reset": "12"}))
        self.assertAlmostEqual(g2.wait_time("h")[0], 12, delta=0.01)

    def test_retry_after_is_clamped(self):
        g, _ = gov_with(HostPolicy(**FAST, max_retry_after=3600))
        g.record("h", FakeResp(429, {"Retry-After": "999999"}))
        self.assertLessEqual(g.wait_time("h")[0], 3600)

    def test_remaining_zero_on_success_pauses_until_reset(self):
        kind = self.g.record("h", FakeResp(200, {"x-ratelimit-limit": "4, 4;w=1, 60;w=60, 500;w=86400",
                                                 "x-ratelimit-remaining": "0",
                                                 "x-ratelimit-reset": "7"}))
        self.assertEqual(kind, OK)
        wait, reason = self.g.wait_time("h")
        self.assertAlmostEqual(wait, 7)
        self.assertEqual(reason, "quota-exhausted")

    def test_remaining_positive_does_not_pause(self):
        self.g.record("h", FakeResp(200, {"x-ratelimit-remaining": "3", "x-ratelimit-reset": "1"}))
        self.assertEqual(self.g.wait_time("h")[0], 0)

    def test_forbidden_long_cooldown(self):
        kind = self.g.record("h", FakeResp(403, text='{"error": "Please contact us"}'))
        self.assertEqual(kind, FORBIDDEN)
        self.assertGreaterEqual(self.g.wait_time("h")[0], 600)

    def test_benign_404_is_success(self):
        g, _ = gov_with(HostPolicy(**FAST, benign_statuses=(404,)))
        self.assertEqual(g.record("h", FakeResp(404)), OK)
        self.assertEqual(g.wait_time("h")[0], 0)
        self.assertEqual(g.stats("h")["ok"], 1)

    def test_304_counted(self):
        self.g.record("h", FakeResp(304))
        self.assertEqual(self.g.stats("h")["not_modified"], 1)


class TestBackoff(unittest.TestCase):
    def test_exponential_full_jitter_capped_and_reset_on_success(self):
        g, c = gov_with(HostPolicy(**FAST, backoff_base=5, backoff_cap=300))
        waits = []
        for _ in range(9):
            g.record("h", TimeoutError("timed out"))
            waits.append(round(g.wait_time("h")[0], 3))
            c.advance(waits[-1])
        self.assertEqual(waits, [5, 10, 20, 40, 80, 160, 300, 300, 300])
        g.record("h", FakeResp(200))
        self.assertEqual(g.stats("h")["failures"], 0)
        g.record("h", TimeoutError("timed out"))
        self.assertAlmostEqual(g.wait_time("h")[0], 5)

    def test_full_jitter_stays_within_ceiling(self):
        c = FakeClock()
        g = RateGovernor(policies={"h": HostPolicy(**FAST, backoff_base=5, backoff_cap=300)},
                         clock=c, wall=c.wall, sleep=c.sleep, rng=random.Random(7),
                         log=lambda m: None)
        seen = []
        for _ in range(200):
            g.record("h", ConnectionError("down"))
            w = g.wait_time("h")[0]
            self.assertGreaterEqual(w, 0)
            self.assertLessEqual(w, 300)
            seen.append(w)
            c.advance(w)
        self.assertGreater(len(set(round(s, 3) for s in seen)), 50)   # really jittered

    def test_ladder_steps(self):
        g, c = gov_with(HostPolicy(**FAST, backoff_steps=(60, 300, 900)), rng=MidRng())
        waits = []
        for _ in range(5):
            g.record_failure("h")
            waits.append(round(g.wait_time("h")[0]))
            c.advance(waits[-1])
        self.assertEqual(waits, [60, 300, 900, 900, 900])
        g.record_success("h")
        g.record_failure("h")
        self.assertEqual(round(g.wait_time("h")[0]), 60)

    def test_record_success_without_reset_keeps_escalation(self):
        g, c = gov_with(HostPolicy(**FAST, backoff_steps=(60, 300, 900)), rng=MidRng())
        g.record_failure("h")
        c.advance(60)
        g.record_success("h", reset=False)
        g.record_failure("h")
        self.assertEqual(round(g.wait_time("h")[0]), 300)

    def test_cloudflare_exception_is_throttle(self):
        class CloudflareError(Exception):
            response = None

        g, _ = gov_with(HostPolicy(**FAST, throttle_floor=60))
        self.assertEqual(g.record("h", CloudflareError("blocked")), THROTTLED)
        self.assertGreaterEqual(g.wait_time("h")[0], 60)

    def test_http_error_exception_uses_its_response(self):
        exc = Exception("HTTP 429")
        exc.response = FakeResp(429, {"Retry-After": "77"})
        g, _ = gov_with(HostPolicy(**FAST))
        self.assertEqual(g.record("h", exc), THROTTLED)
        self.assertAlmostEqual(g.wait_time("h")[0], 77)


class TestGetWrapper(unittest.TestCase):
    def test_refuses_locally_after_429(self):
        g, c = gov_with(HostPolicy(**FAST))
        session = mock.Mock()
        session.get.return_value = FakeResp(429, {"Retry-After": "60"})
        resp = g.get(session, "https://api.example.com/x", host="h", timeout=5)
        self.assertEqual(resp.status_code, 429)
        with self.assertRaises(RateLimited) as cm:
            g.get(session, "https://api.example.com/x", host="h", timeout=5)
        self.assertEqual(session.get.call_count, 1)      # nothing sent while cooling down
        self.assertGreater(cm.exception.wait, 0)
        c.advance(61)
        session.get.return_value = FakeResp(200)
        self.assertEqual(g.get(session, "https://api.example.com/x", host="h").status_code, 200)
        self.assertEqual(session.get.call_count, 2)

    def test_timeout_recorded_and_reraised(self):
        g, _ = gov_with(HostPolicy(**FAST))
        session = mock.Mock()
        session.get.side_effect = TimeoutError("read timed out")
        with self.assertRaises(TimeoutError):
            g.get(session, "https://api.example.com/x", host="h")
        s = g.stats("h")
        self.assertEqual((s["sent"], s["errors"], s["failures"]), (1, 1, 1))
        self.assertGreater(g.wait_time("h")[0], 0)

    def test_host_defaults_to_url_hostname(self):
        g, _ = gov_with(HostPolicy(**FAST))
        session = mock.Mock()
        session.get.return_value = FakeResp(200)
        g.get(session, "https://api.adsbdb.com/v0/callsign/JBU251")
        self.assertEqual(g.stats("api.adsbdb.com")["ok"], 1)

    def test_summary_mentions_counters(self):
        g, _ = gov_with(HostPolicy(per_day=10, **FAST))
        g.allow("h")
        g.record("h", FakeResp(200))
        text = g.summary()
        for token in ("h:", "sent=1", "ok=1", "429=0", "err=0", "skip=0", "day=1/10"):
            self.assertIn(token, text)


class TestParsers(unittest.TestCase):
    def test_parse_retry_after(self):
        self.assertEqual(parse_retry_after("120"), 120)
        self.assertEqual(parse_retry_after(" 0 "), 0)
        self.assertIsNone(parse_retry_after("soon"))
        self.assertIsNone(parse_retry_after(None))
        date = email.utils.formatdate(WALL0 + 42, usegmt=True)
        self.assertAlmostEqual(parse_retry_after(date, now_wall=WALL0), 42, delta=1)
        past = email.utils.formatdate(WALL0 - 42, usegmt=True)
        self.assertEqual(parse_retry_after(past, now_wall=WALL0), 0)

    def test_parse_reset(self):
        self.assertEqual(parse_reset("1"), 1)
        self.assertEqual(parse_reset("4, 4;w=1"), 4)
        self.assertAlmostEqual(parse_reset(str(int(WALL0 + 30)), now_wall=WALL0), 30)
        self.assertIsNone(parse_reset("none"))


if __name__ == "__main__":
    unittest.main()
