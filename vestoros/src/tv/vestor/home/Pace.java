package tv.vestor.home;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

/**
 * Keeps every service Vestor reads comfortably inside its published limits, so none of them ever
 * answers "too many requests". Each host has a budget: a minimum gap between requests and a number
 * of requests per minute, set well under what the service allows. A request that would break the
 * budget is never sent. The caller simply keeps the data it already has and tries on its next tick.
 *
 * When a service does push back (429 or 503) its Retry-After is obeyed to the second. Network
 * errors and server faults back off exponentially with jitter, so a struggling service is given
 * room instead of a queue of retries, and the first good answer resets everything.
 *
 * Counters per host feed the control center's health page. Thread-safe. Platform-free, so the
 * desktop tests drive it with a fake clock.
 */
final class Pace {
    private Pace() {}

    static final class Host {
        final String name;
        long gapMs;
        int perMinute, base, perDay, today;
        long limitedAt, dayStart;
        double tokens;
        long refilled, lastSent, holdUntil;
        int strikes;
        int sent, ok, unchanged, limited, failed, refused;
        long lastOk, lastProblemAt;
        String lastProblem = "";

        Host(String name, long gapMs, int perMinute) {
            this.name = name;
            this.gapMs = gapMs;
            this.perMinute = perMinute;
            this.base = perMinute;
            this.tokens = perMinute;
        }
    }

    /** Longest any service may ask Vestor to wait. Past this, it tries again anyway. */
    static final long MAX_HOLD_MS = 30 * 60000L;

    private static final Map<String, Host> HOSTS = new LinkedHashMap<>();
    private static final Random JITTER = new Random();

    static {
        // Gaps and budgets sit at half or less of each service's published limit (README lists them).
        // Bluebikes publishes once a minute, and asking "changed?" costs a 0 byte 304.
        set("gbfs.lyft.com", 2000, 40);
        // Passio allows 6000 a minute per address. Trip updates move every 12 to 16 s.
        set("passio3.com", 2000, 40);
        // Keyless the MBTA allows 20 a minute. With a key it is 1000, see useMbtaKey.
        set("api-v3.mbta.com", 1500, 12);
        // adsb.fi allows one a second. adsb.lol says 4 a second, 60 a minute and 500 a day, so it
        // is the fallback. airplanes.live now wants projects to register first (it answers 403).
        set("opendata.adsb.fi", 2500, 24);
        set("api.adsb.lol", 3000, 12);
        daily("api.adsb.lol", 350);
        set("api.airplanes.live", 3000, 12);
        set("hexdb.io", 1000, 30);
        // adsbdb allows 512 a minute. Routes are looked up once per new flight and kept.
        set("api.adsbdb.com", 500, 60);
        // The forecast and the next hour's rain are two calls every 15 minutes, so a short gap.
        set("api.open-meteo.com", 5000, 4);
        set("air-quality-api.open-meteo.com", 20000, 3);
        set("api.weather.gov", 10000, 4);
        set("calendar.google.com", 5000, 6);
        set("icloud", 5000, 6);
        set("outlook.office365.com", 5000, 6);
        set("outlook.live.com", 5000, 6);
        set("ntfy.sh", 5000, 4);
        // SmartThings: a picture-mode change or two, a status read, a token refresh a day.
        set("api.smartthings.com", 1500, 12);
        // Claude: a question is a few requests (searches, an action, the answer). Its own limits are far higher.
        set("api.anthropic.com", 250, 30);
    }

    private static void set(String host, long gapMs, int perMinute) {
        HOSTS.put(host, new Host(host, gapMs, perMinute));
    }

    /** A daily cap on top of the per-minute one (adsb.lol allows 500 a day per address). */
    private static void daily(String host, int perDay) {
        HOSTS.get(host).perDay = perDay;
    }

    /** The budget a URL host falls under: every iCloud calendar server shares one. */
    static String bucket(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.US);
        return h.endsWith(".icloud.com") ? "icloud" : h;
    }

    /** The MBTA lifts its limit to 1000 a minute for a free key. Vestor then uses a tenth. */
    static synchronized void useMbtaKey(boolean key) {
        Host h = HOSTS.get("api-v3.mbta.com");
        h.base = key ? 100 : 12;
        h.perMinute = h.base;
        h.gapMs = key ? 500 : 1500;
        h.tokens = Math.min(h.tokens, h.perMinute);
    }

    /**
     * Whether a request to {@code host} may go out now. If so it is counted against the budget.
     * Unknown hosts are refused: Net never gets that far with one anyway.
     */
    static synchronized boolean take(String host, long now) {
        Host h = HOSTS.get(bucket(host));
        if (h == null) return false;
        refill(h, now);
        if (h.perDay > 0 && now - h.dayStart >= 86400000L) {
            h.dayStart = now;
            h.today = 0;
        }
        if (now < h.holdUntil || now - h.lastSent < h.gapMs || h.tokens < 1 || h.perDay > 0 && h.today >= h.perDay) {
            h.refused++;
            return false;
        }
        h.today++;
        h.tokens -= 1;
        h.lastSent = now;
        h.sent++;
        return true;
    }

    private static void refill(Host h, long now) {
        if (h.refilled == 0) h.refilled = now;
        long dt = Math.max(0, now - h.refilled);
        h.tokens = Math.min(h.perMinute, h.tokens + dt * h.perMinute / 60000.0);
        h.refilled = now;
    }

    /** Milliseconds until {@code host} would take a request, 0 if now. */
    static synchronized long wait(String host, long now) {
        Host h = HOSTS.get(bucket(host));
        if (h == null) return Long.MAX_VALUE;
        refill(h, now);
        long w = Math.max(h.holdUntil - now, h.gapMs - (now - h.lastSent));
        if (h.perDay > 0 && h.today >= h.perDay && now - h.dayStart < 86400000L) w = Math.max(w, h.dayStart + 86400000L - now);
        if (h.tokens < 1) w = Math.max(w, (long) Math.ceil((1 - h.tokens) * 60000.0 / h.perMinute));
        return Math.max(0, w);
    }

    /**
     * The answer a request got. 2xx and 304 clear any backoff. 429 and 503 obey Retry-After when
     * the service gives one, else back off from 30 s. Other 5xx back off from 3 s.
     */
    static synchronized void answered(String host, int code, String retryAfter, long now) {
        Host h = HOSTS.get(bucket(host));
        if (h == null) return;
        if (code >= 200 && code < 300 || code == 304) {
            if (code == 304) h.unchanged++;
            else h.ok++;
            h.strikes = 0;
            h.holdUntil = 0;
            h.lastOk = now;
            // A budget halved by a 429 grows back an hour after the last one.
            if (h.perMinute < h.base && now - h.limitedAt > 3600000L) {
                h.perMinute = Math.min(h.base, h.perMinute * 2);
                h.limitedAt = now;
            }
            return;
        }
        if (code == 429 || code == 503) {
            h.limited++;
            long after = retryAfter(retryAfter, now);
            h.strikes++;
            long hold = after >= 0 ? after : backoff(h.strikes, 30000L, 15 * 60000L);
            h.holdUntil = Math.max(h.holdUntil, now + Math.min(MAX_HOLD_MS, hold));
            // A 429 also says the budget was too generous: halve it until Vestor restarts.
            if (code == 429) {
                h.perMinute = Math.max(2, h.perMinute / 2);
                h.limitedAt = now;
            }
            problem(h, code == 429 ? "Asked to slow down" : "Service busy", now);
            return;
        }
        if (code >= 500) {
            h.failed++;
            h.strikes++;
            h.holdUntil = now + backoff(h.strikes, 3000L, 2 * 60000L);
            problem(h, "Server error " + code, now);
            return;
        }
        h.failed++;
        if (code == 401 || code == 403) {
            // Not allowed in: asking again soon will not change that, so wait an hour.
            h.holdUntil = Math.max(h.holdUntil, now + 3600000L);
            problem(h, "Not allowed (" + code + ")", now);
            return;
        }
        // Another 4xx: the request is wrong, not the pace. Logged, no backoff.
        problem(h, "Refused (" + code + ")", now);
    }

    /**
     * What a service says is left of its own limit (X-RateLimit-Remaining and -Reset, as the MBTA
     * sends). Other devices at home share the same public address and so the same limit: when
     * only a couple of requests are left, Vestor waits for the reset instead of spending them.
     */
    static synchronized void quota(String host, String remaining, String reset, long now) {
        Host h = HOSTS.get(bucket(host));
        if (h == null || remaining == null) return;
        int left;
        try {
            left = Integer.parseInt(remaining.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (left > 2) return;
        long until = now + 60000L;
        if (reset != null) {
            try {
                long r = Long.parseLong(reset.trim());
                // Epoch seconds (MBTA) or seconds from now (some others).
                long at = r > 1000000000L ? r * 1000L : now + r * 1000L;
                if (at > now) until = Math.min(now + 5 * 60000L, at + 1000L);
            } catch (NumberFormatException ignored) {
                // keep the one minute default
            }
        }
        h.holdUntil = Math.max(h.holdUntil, until);
        problem(h, "Shared limit nearly used", now);
    }

    /** A timeout, a dropped connection or a name that did not resolve. */
    static synchronized void unreachable(String host, long now) {
        Host h = HOSTS.get(bucket(host));
        if (h == null) return;
        h.failed++;
        h.strikes++;
        h.holdUntil = now + backoff(h.strikes, 2000L, 2 * 60000L);
        problem(h, "Not reachable", now);
    }

    private static void problem(Host h, String what, long now) {
        h.lastProblem = what;
        h.lastProblemAt = now;
    }

    /** base * 2^(strikes-1), capped, with jitter over its upper half so clients never sync up. */
    static long backoff(int strikes, long base, long cap) {
        long full = base;
        for (int i = 1; i < strikes && full < cap; i++) full *= 2;
        full = Math.min(cap, full);
        double r;
        synchronized (JITTER) {
            r = JITTER.nextDouble();
        }
        return full / 2 + (long) (r * (full / 2));
    }

    /** Retry-After in ms from now: delta seconds or an HTTP date. -1 when absent or unreadable. */
    static long retryAfter(String v, long now) {
        if (v == null) return -1;
        v = v.trim();
        if (v.isEmpty() || v.length() > 64) return -1;
        boolean digits = true;
        for (int i = 0; i < v.length(); i++) digits &= Character.isDigit(v.charAt(i));
        if (digits) {
            if (v.length() > 7) return MAX_HOLD_MS;
            return Math.min(MAX_HOLD_MS, Long.parseLong(v) * 1000L);
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            f.setLenient(false);
            Date d = f.parse(v);
            if (d == null) return -1;
            return Math.max(0, Math.min(MAX_HOLD_MS, d.getTime() - now));
        } catch (java.text.ParseException | RuntimeException e) {
            return -1;
        }
    }

    /** One line per service for the health page: name, state, counts. */
    static synchronized List<String[]> report(long now) {
        List<String[]> out = new ArrayList<>();
        for (Host h : HOSTS.values()) {
            if (h.sent == 0 && h.refused == 0) continue;
            String state = now < h.holdUntil ? "Waiting " + ((h.holdUntil - now + 999) / 1000) + " s"
                    : h.lastOk > 0 && h.lastOk >= h.lastProblemAt ? "OK" : h.lastProblem.isEmpty() ? "Starting" : h.lastProblem;
            out.add(new String[] {h.name, state, String.valueOf(h.sent), String.valueOf(h.ok), String.valueOf(h.unchanged),
                String.valueOf(h.limited), String.valueOf(h.failed), String.valueOf(h.perMinute),
                h.lastOk == 0 ? "" : String.valueOf(h.lastOk)});
        }
        return out;
    }

    /** For tests: forget every count and hold. */
    static synchronized void reset() {
        for (Host h : HOSTS.values()) {
            h.perMinute = h.base;
            h.limitedAt = 0;
            h.tokens = h.perMinute;
            h.refilled = 0;
            h.lastSent = 0;
            h.holdUntil = 0;
            h.strikes = 0;
            h.today = 0;
            h.dayStart = 0;
            h.sent = h.ok = h.unchanged = h.limited = h.failed = h.refused = 0;
            h.lastOk = h.lastProblemAt = 0;
            h.lastProblem = "";
        }
    }

    static synchronized Host peek(String host) {
        return HOSTS.get(bucket(host));
    }
}
