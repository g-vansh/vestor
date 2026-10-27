"""
ratelimit.py — a small, thread-safe, per-host request governor.

Every outbound request the flight tracker makes goes through one of these so the
wall can run 24/7 without ever tripping a provider's rate limit. Per host it
enforces, *locally and before anything is sent*:

  * a minimum spacing between requests (``min_interval``),
  * a token-bucket per-minute budget (``per_minute`` refill, ``burst`` capacity),
  * an exact sliding 24 h cap (``per_day``) for providers with daily quotas,
  * a cooldown after the server pushes back: HTTP 429 / 503 honour
    ``Retry-After`` (delta-seconds or HTTP-date), ``RateLimit-Reset`` /
    ``X-RateLimit-Reset`` (delta or epoch), and a "rate limited for N seconds"
    body hint; ``X-RateLimit-Remaining: 0`` on a *successful* response also
    pauses the host until the advertised reset,
  * exponential backoff with full jitter on errors/timeouts (capped, default
    5 min) or an explicit escalation ladder (used for FR24), reset on success.

When a request would break a budget the governor refuses it locally:
``allow()`` returns False, and the ``get()`` wrapper raises ``RateLimited``.
Callers then fail over or keep their last good data. Nothing ever sleeps for
a cooldown; only a short spacing wait (``max_wait``) is ever slept.

Counters per host (sent, ok, not_modified, throttled, errors, skipped) are
available via ``stats()`` / ``summary()`` for the journal.

Optional persistence (``state_path``) keeps the daily logs and long cooldowns
across service restarts, so a crash/restart loop cannot reset a daily quota
or forget a server's Retry-After.

---------------------------------------------------------------------------
DOCUMENTED / OBSERVED LIMITS (researched 2026-10-27; budgets in overhead.py
are set comfortably below these):

  adsb.fi open data  https://github.com/adsbfi/opendata (README "Limits")
      "The public endpoints are rate limited to 1 request per second."
      Requests returning 400/401/403/404/429 count toward the limit, and
      "excessive invalid HTTP requests results in a temporary IP address
      restriction". /api/v2/lat/../lon/../dist/.. is deprecated (returns an
      "aircraft" array); /api/v3/lat/../lon/../dist/.. (<=250 nm) returns the
      ADSBExchange-v2 "ac" array. Personal, non-commercial use, cite adsb.fi.
      Live probe: 200, no rate-limit headers.

  adsb.lol           https://github.com/adsblol/api (README "Rate limits")
      "Rate limits are dynamic based on the environment load" and an API key
      (obtained by feeding) will be required "in the future". Live response
      headers 2026-10-27:
        x-ratelimit-limit: 4, 4;w=1, 60;w=60, 500;w=86400
        x-ratelimit-remaining: 3      x-ratelimit-reset: 1
      i.e. 4 req/s, 60 req/min and only 500 req per DAY per client.

  airplanes.live     https://airplanes.live/api-guide/
      Guide documents 1 request per second, non-commercial use. Live probe
      2026-10-27: HTTP 403 {"error": "Please contact us at
      contact@airplanes.live. Your email MUST include any links, a description
      of the project, ..."}. Treated as unusable without registration (last
      resort, long cooldown on 401/403). The owner can email them for access.

  adsbdb.com         https://github.com/mrjackwills/adsbdb
      src/db/redis/ratelimit.rs: LOWER_LIMIT 512 requests per 60 s window per
      IP, then 429 with body {"response": "rate limited for N seconds"} (no
      Retry-After header); >= 1024 in a window extends the block to 5 min.
      CHANGELOG: "rate limits increased, lower: 120 -> 512, upper: 240 -> 1024".
      Unknown callsign is a normal 404 {"response": "unknown callsign"}.

  FlightRadar24 via FlightRadarAPI 1.5.1
                     https://github.com/JeanExtreme002/FlightRadarAPI
      Unofficial scrape of data-cloud.flightradar24.com/zones/fcgi/feed.js. No
      published limit ("educational purposes" only, commercial use via
      business@fr24.com). The package does NOT retry by default, uses a 30 s
      timeout, and raises CloudflareError on 403+cf-mitigated or 520 ("Perhaps
      you are making too many calls") and HTTPError (raise_for_status) on other
      4xx/5xx. This is the most throttle-prone source: governed by a ladder.

  hexdb.io (not used by the tracker; tools/clients only)
      Responses carry cache-control: max-age=14400 (4 h).
---------------------------------------------------------------------------
"""

import email.utils
import json
import math
import os
import random
import re
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Optional, Tuple

DAY = 86400.0
_EPS = 1e-6         # float slack so incremental refills never strand a request

# Outcome classifications returned by record().
OK = "ok"
NOT_MODIFIED = "not_modified"
THROTTLED = "throttled"
FORBIDDEN = "forbidden"
ERROR = "error"

# Refusal reasons. "spacing" and "minute-budget" are short and may be waited
# out (max_wait); the others mean "go elsewhere / keep last data".
SOFT_REASONS = ("spacing", "minute-budget")

COUNTER_NAMES = ("sent", "ok", "not_modified", "throttled", "errors", "skipped")

_BODY_HINT = re.compile(r"(\d+(?:\.\d+)?)\s*(?:seconds?|secs?|s)\b", re.IGNORECASE)


class RateLimited(Exception):
    """A request was refused locally because it would break a host's budget."""

    def __init__(self, host, wait, reason):
        super().__init__(f"{host}: refused locally ({reason}, retry in {wait:.0f}s)")
        self.host = host
        self.wait = wait
        self.reason = reason


@dataclass(frozen=True)
class HostPolicy:
    min_interval: float = 1.0          # s between requests to this host
    per_minute: float = 30.0           # token-bucket refill (requests per minute)
    burst: int = 2                     # token-bucket capacity
    per_day: Optional[int] = None      # exact sliding-24 h cap (None = no cap)
    backoff_base: float = 5.0          # s, first exponential ceiling
    backoff_cap: float = 300.0         # s, max exponential ceiling (5 min)
    backoff_steps: Optional[Tuple[float, ...]] = None  # explicit ladder instead
    throttle_floor: float = 60.0       # s, min pause after a 429 with no hint
    forbidden_cooldown: float = 600.0  # s, pause after 401/403 (blocked / needs key)
    max_retry_after: float = 3600.0    # s, clamp any server-provided wait
    benign_statuses: Tuple[int, ...] = ()  # e.g. (404,) = valid "not found" answer


@dataclass
class _HostState:
    policy: HostPolicy
    tokens: float
    last_refill: float
    last_sent: float = -math.inf
    blocked_until: float = 0.0
    block_reason: str = ""
    failures: int = 0
    day_log: deque = field(default_factory=deque)
    counters: dict = field(default_factory=lambda: {k: 0 for k in COUNTER_NAMES})


def _header(headers, name):
    """Case-insensitive header lookup that works for requests' dict and plain dicts."""
    if not headers:
        return None
    try:
        v = headers.get(name)
        if v is not None:
            return v
    except AttributeError:
        return None
    low = name.lower()
    try:
        for k, v in headers.items():
            if str(k).lower() == low:
                return v
    except AttributeError:
        pass
    return None


def parse_retry_after(value, now_wall=None):
    """Retry-After value -> seconds (float >= 0), or None if unparseable.

    Accepts delta-seconds ("120") or an HTTP-date ("Wed, 21 Oct 2026 07:28:00 GMT").
    """
    if value is None:
        return None
    value = str(value).strip()
    if not value:
        return None
    try:
        return max(0.0, float(value))
    except ValueError:
        pass
    try:
        dt = email.utils.parsedate_to_datetime(value)
    except (TypeError, ValueError, IndexError):
        return None
    if dt is None:
        return None
    if dt.tzinfo is None:              # HTTP-dates are GMT by definition
        import datetime as _dt
        dt = dt.replace(tzinfo=_dt.timezone.utc)
    now_wall = time.time() if now_wall is None else now_wall
    return max(0.0, dt.timestamp() - now_wall)


def parse_reset(value, now_wall=None):
    """X-RateLimit-Reset / RateLimit-Reset -> seconds from now, or None.

    Values above 1e9 are treated as a Unix epoch timestamp, smaller ones as a
    delta in seconds. Comma-separated lists (adsb.lol style) use the first number.
    """
    if value is None:
        return None
    m = re.search(r"\d+(?:\.\d+)?", str(value))
    if not m:
        return None
    v = float(m.group(0))
    if v > 1e9:
        now_wall = time.time() if now_wall is None else now_wall
        return max(0.0, v - now_wall)
    return max(0.0, v)


class RateGovernor:
    """Per-host request governor. Thread-safe. See module docstring."""

    def __init__(self, policies=None, default_policy=None, clock=time.monotonic,
                 wall=time.time, sleep=time.sleep, rng=None, state_path=None,
                 log=None):
        self._lock = threading.Lock()
        self._clock = clock
        self._wall = wall
        self._sleep = sleep
        self._rng = rng or random.Random()
        self._default = default_policy or HostPolicy()
        self._hosts = {}
        self._state_path = state_path
        self._log = log or (lambda msg: print(f"[ratelimit] {msg}", flush=True))
        self._save_failed = False
        for host, pol in (policies or {}).items():
            self.configure(host, pol)
        self._load()

    # ---------------------------------------------------------------- setup
    def configure(self, host, policy):
        """Set (or replace) a host's policy, keeping its counters and cooldowns."""
        with self._lock:
            st = self._hosts.get(host)
            if st is None:
                self._hosts[host] = _HostState(policy=policy, tokens=float(policy.burst),
                                               last_refill=self._clock())
            else:
                st.policy = policy
                st.tokens = min(st.tokens, float(policy.burst))

    def _state(self, host):
        st = self._hosts.get(host)
        if st is None:
            st = _HostState(policy=self._default, tokens=float(self._default.burst),
                            last_refill=self._clock())
            self._hosts[host] = st
        return st

    # ------------------------------------------------------------- budgets
    def _refill(self, st, now):
        pol = st.policy
        rate = pol.per_minute / 60.0
        if now > st.last_refill:
            st.tokens = min(float(pol.burst), st.tokens + (now - st.last_refill) * rate)
        st.last_refill = now

    def _wait_locked(self, st, now):
        """(seconds until a request is allowed, reason). 0 means allowed now."""
        pol = st.policy
        if st.blocked_until > now:
            return st.blocked_until - now, st.block_reason or "cooldown"
        waits = []
        spacing = st.last_sent + pol.min_interval - now
        if spacing > _EPS:
            waits.append((spacing, "spacing"))
        self._refill(st, now)
        if st.tokens < 1.0 - _EPS:
            rate = pol.per_minute / 60.0
            waits.append(((1.0 - st.tokens) / rate if rate > 0 else math.inf, "minute-budget"))
        if pol.per_day is not None:
            while st.day_log and now - st.day_log[0] >= DAY:
                st.day_log.popleft()
            if len(st.day_log) >= pol.per_day:
                waits.append((st.day_log[0] + DAY - now, "day-budget"))
        if not waits:
            return 0.0, ""
        return max(waits)

    def wait_time(self, host):
        """(seconds, reason) until ``host`` may be called; (0.0, '') if now."""
        with self._lock:
            return self._wait_locked(self._state(host), self._clock())

    def allow(self, host, max_wait=0.0):
        """Reserve a request slot for ``host``. True = go ahead and send now.

        If the only obstacle is spacing / the per-minute bucket and it clears
        within ``max_wait`` seconds, sleeps that long first. Cooldowns, backoff
        and the daily cap are never waited out: they return False (counted as
        ``skipped``) so the caller can fail over or keep its last good data.
        """
        budget = max(0.0, float(max_wait))
        while True:
            with self._lock:
                st = self._state(host)
                now = self._clock()
                wait, reason = self._wait_locked(st, now)
                if wait <= 0:
                    st.tokens -= 1.0
                    st.last_sent = now
                    st.counters["sent"] += 1
                    persist = st.policy.per_day is not None
                    if persist:
                        st.day_log.append(now)
                    break
                if reason not in SOFT_REASONS or wait > budget:
                    st.counters["skipped"] += 1
                    return False
            self._sleep(wait)
            budget -= wait
        if persist:
            self._save()
        return True

    # ------------------------------------------------------------ outcomes
    def _backoff(self, pol, failures):
        if pol.backoff_steps:
            step = pol.backoff_steps[min(failures, len(pol.backoff_steps)) - 1]
            return step * self._rng.uniform(0.9, 1.1)          # small jitter, ladder kept
        ceiling = min(pol.backoff_cap, pol.backoff_base * (2 ** (failures - 1)))
        return self._rng.uniform(0.0, ceiling)                 # full jitter

    def _server_wait(self, pol, status, headers, body):
        """Server-provided wait in seconds (clamped), or None."""
        now_wall = self._wall()
        wait = parse_retry_after(_header(headers, "Retry-After"), now_wall)
        if wait is None:
            for name in ("RateLimit-Reset", "X-RateLimit-Reset", "X-RateLimit-Reset-After"):
                wait = parse_reset(_header(headers, name), now_wall)
                if wait is not None:
                    break
        if wait is None and status == 429 and isinstance(body, str):
            m = _BODY_HINT.search(body[:300])
            if m:
                wait = float(m.group(1))
        if wait is None:
            return None
        return min(wait, pol.max_retry_after)

    def _block(self, st, now, delay, reason):
        until = now + max(0.0, delay)
        if until > st.blocked_until:
            st.blocked_until = until
            st.block_reason = reason
        return st.blocked_until - now

    def record_success(self, host, not_modified=False, reset=True):
        """Count a good answer. ``reset=False`` counts it without clearing an
        escalation already in progress (e.g. a tolerated-but-suspicious reply)."""
        with self._lock:
            st = self._state(host)
            if reset:
                st.failures = 0
            st.counters["not_modified" if not_modified else "ok"] += 1
        return NOT_MODIFIED if not_modified else OK

    def record_failure(self, host, kind=ERROR, retry_after=None):
        """Count a failure that is not an HTTP response (e.g. bad JSON, empty
        answer, library exception) and start the host's backoff."""
        with self._lock:
            st = self._state(host)
            pol = st.policy
            now = self._clock()
            st.failures += 1
            delay = self._backoff(pol, st.failures)
            if kind == THROTTLED:
                st.counters["throttled"] += 1
                delay = max(delay, retry_after if retry_after is not None else pol.throttle_floor)
            else:
                st.counters["errors"] += 1
                if kind == FORBIDDEN:
                    delay = max(delay, pol.forbidden_cooldown)
                if retry_after is not None:
                    delay = max(delay, retry_after)
            delay = min(delay, max(pol.max_retry_after, pol.forbidden_cooldown))
            blocked = self._block(st, now, delay, "backoff" if kind == ERROR else kind)
        if blocked >= 30:
            self._save()
        return kind

    def record(self, host, outcome):
        """Record a response (anything with ``status_code``) or an exception.

        Returns the classification: 'ok', 'not_modified', 'throttled',
        'forbidden' or 'error'.
        """
        if isinstance(outcome, BaseException):
            resp = getattr(outcome, "response", None)
            if type(outcome).__name__ == "CloudflareError":
                return self.record_failure(host, THROTTLED,
                                           self._exc_wait(host, resp))
            if resp is not None and isinstance(getattr(resp, "status_code", None), int):
                return self.record(host, resp)
            return self.record_failure(host, ERROR)

        status = getattr(outcome, "status_code", None)
        headers = getattr(outcome, "headers", None) or {}
        with self._lock:
            pol = self._state(host).policy
        if not isinstance(status, int):
            return self.record_failure(host, ERROR)

        if status < 400 or status in pol.benign_statuses:
            kind = self.record_success(host, not_modified=(status == 304))
            self._honour_remaining(host, headers)
            return kind

        body = None
        if status == 429:
            try:
                body = outcome.text
            except Exception:
                body = None
        wait = self._server_wait(pol, status, headers, body)
        if status == 429:
            return self.record_failure(host, THROTTLED, wait)
        if status in (401, 403):
            return self.record_failure(host, FORBIDDEN, wait)
        return self.record_failure(host, ERROR, wait)    # 5xx (incl. 503) + other 4xx

    def _exc_wait(self, host, resp):
        if resp is None:
            return None
        with self._lock:
            pol = self._state(host).policy
        return self._server_wait(pol, getattr(resp, "status_code", None),
                                 getattr(resp, "headers", None) or {}, None)

    def _honour_remaining(self, host, headers):
        """On a good response, X-RateLimit-Remaining: 0 pauses until the reset."""
        remaining = _header(headers, "X-RateLimit-Remaining") or _header(headers, "RateLimit-Remaining")
        if remaining is None:
            return
        m = re.search(r"-?\d+", str(remaining))
        if not m or int(m.group(0)) > 0:
            return
        reset = (parse_reset(_header(headers, "X-RateLimit-Reset"), self._wall())
                 or parse_reset(_header(headers, "RateLimit-Reset"), self._wall()))
        with self._lock:
            st = self._state(host)
            delay = min(reset if reset is not None else st.policy.min_interval,
                        st.policy.max_retry_after)
            self._block(st, self._clock(), delay, "quota-exhausted")

    # -------------------------------------------------------------- wrapper
    def get(self, session, url, host=None, max_wait=0.0, **kwargs):
        """``session.get(url, **kwargs)`` behind the governor.

        Raises ``RateLimited`` instead of sending if the host is over budget or
        cooling down. Records the outcome and returns the response for any HTTP
        status (callers check ``status_code``). Transport exceptions are recorded
        and re-raised.
        """
        host = host or _host_of(url)
        if not self.allow(host, max_wait=max_wait):
            wait, reason = self.wait_time(host)
            raise RateLimited(host, wait, reason or "budget")
        try:
            resp = session.get(url, **kwargs)
        except Exception as exc:
            self.record(host, exc)
            raise
        self.record(host, resp)
        return resp

    # ---------------------------------------------------------------- stats
    def stats(self, host=None):
        with self._lock:
            now = self._clock()
            hosts = [host] if host else list(self._hosts)
            out = {}
            for h in hosts:
                st = self._state(h)
                d = dict(st.counters)
                d["failures"] = st.failures
                d["blocked_for"] = round(max(0.0, st.blocked_until - now), 1)
                d["block_reason"] = st.block_reason if st.blocked_until > now else ""
                if st.policy.per_day is not None:
                    d["day_used"] = sum(1 for t in st.day_log if now - t < DAY)
                    d["day_limit"] = st.policy.per_day
                out[h] = d
            return out[host] if host else out

    def summary(self):
        parts = []
        for h, d in self.stats().items():
            s = (f"{h}: sent={d['sent']} ok={d['ok']} 304={d['not_modified']} "
                 f"429={d['throttled']} err={d['errors']} skip={d['skipped']}")
            if "day_used" in d:
                s += f" day={d['day_used']}/{d['day_limit']}"
            if d["blocked_for"]:
                s += f" paused={d['blocked_for']:.0f}s({d['block_reason']})"
            parts.append(s)
        return " | ".join(parts)

    # ---------------------------------------------------------- persistence
    def _save(self):
        if not self._state_path:
            return
        with self._lock:
            now, wall = self._clock(), self._wall()
            data = {}
            for h, st in self._hosts.items():
                entry = {}
                if st.policy.per_day is not None and st.day_log:
                    entry["day_log"] = [round(wall - (now - t), 1) for t in st.day_log]
                if st.blocked_until - now >= 30:
                    entry["blocked_until"] = round(wall + (st.blocked_until - now), 1)
                    entry["block_reason"] = st.block_reason
                    entry["failures"] = st.failures
                if entry:
                    data[h] = entry
        tmp = f"{self._state_path}.{os.getpid()}.{threading.get_ident()}.tmp"
        try:
            with open(tmp, "w") as fh:
                json.dump(data, fh)
            os.replace(tmp, self._state_path)
            self._save_failed = False
        except OSError as exc:
            if not self._save_failed:
                self._log(f"could not persist state to {self._state_path}: {exc}")
            self._save_failed = True
            try:
                os.unlink(tmp)
            except OSError:
                pass

    def _load(self):
        if not self._state_path:
            return
        try:
            with open(self._state_path) as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return
        if not isinstance(data, dict):
            return
        with self._lock:
            now, wall = self._clock(), self._wall()
            for h, entry in data.items():
                if not isinstance(entry, dict):
                    continue
                st = self._state(h)
                for t in entry.get("day_log", []):
                    try:
                        age = wall - float(t)
                    except (TypeError, ValueError):
                        continue
                    if 0 <= age < DAY:
                        st.day_log.append(now - age)
                try:
                    remaining = float(entry.get("blocked_until", 0)) - wall
                except (TypeError, ValueError):
                    remaining = 0
                if remaining > 0:
                    self._block(st, now, min(remaining, DAY), entry.get("block_reason", "cooldown"))
                    st.failures = int(entry.get("failures", 0) or 0)


def _host_of(url):
    from urllib.parse import urlsplit
    return urlsplit(url).hostname or url
