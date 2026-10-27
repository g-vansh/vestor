package tv.vestor.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Plain HTTPS GETs with short timeouts. Responses that carry an ETag or Last-Modified are kept,
 * and the next request for the same URL asks "only if changed", so polling a large feed every few
 * seconds costs a few hundred bytes whenever nothing moved. Thread-safe.
 *
 * Defensive by default:
 * - Only HTTPS, and only to the public hosts Vestor reads (plus the calendar servers of Google,
 *   iCloud and Outlook, for feeds you add). Anything else is refused before a socket opens, so no
 *   feed, file or setting can point Vestor somewhere new.
 * - Every request first asks {@link Pace}, which keeps each service far inside its published
 *   limit, obeys Retry-After and backs off from trouble. A request over budget is never sent.
 * - Redirects are never followed blindly: Android's own handling would already have sent the
 *   request to the new host before Vestor could object. A redirect is followed by hand, at most
 *   three times, and only to HTTPS on the same host.
 * - Every answer has a size cap fitting what it should be, counted after decompression, and the
 *   whole request has a deadline, so a server that drips bytes cannot hold a loop.
 * - Nothing identifying is sent: no cookies, no account, no device id, a generic user agent in
 *   place of Android's default one, which names the device model and build.
 * - Connections that ended cleanly stay open for the next poll, so a 5 second loop does not pay
 *   for a new TLS handshake every time.
 */
final class Net {
    private Net() {}

    /** Size caps: a route lookup, an API answer, a whole feed, the MIT timetable archive, a calendar. */
    static final int LIMIT_TINY = 64 * 1024, LIMIT_SMALL = 1024 * 1024, LIMIT_FEED = 6 * 1024 * 1024,
            LIMIT_ZIP = 4 * 1024 * 1024, LIMIT_ICS = 4 * 1024 * 1024;
    static final String AGENT = "Vestor/2.0";
    private static final int MAX_HOPS = 3;

    /** The public hosts Vestor talks to. README lists what each is for. */
    static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "api.open-meteo.com", "air-quality-api.open-meteo.com", "api.weather.gov",
            "gbfs.lyft.com", "passio3.com", "api-v3.mbta.com",
            "api.adsb.lol", "api.airplanes.live", "opendata.adsb.fi", "hexdb.io", "api.adsbdb.com",
            "calendar.google.com", "outlook.office365.com", "outlook.live.com", "ntfy.sh", "api.smartthings.com",
            "api.anthropic.com", "raw.githubusercontent.com"));

    /** Whether {@code host} is one Vestor may contact. iCloud serves published calendars from pNN-caldav or pNN-calendars. */
    static boolean allowedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.US);
        if (HOSTS.contains(h)) return true;
        return icloud(h);
    }

    static boolean icloud(String h) {
        String tail = h.endsWith("-caldav.icloud.com") ? "-caldav.icloud.com" : h.endsWith("-calendars.icloud.com")
                ? "-calendars.icloud.com" : null;
        if (tail == null || h.charAt(0) != 'p') return false;
        String n = h.substring(1, h.length() - tail.length());
        if (n.isEmpty() || n.length() > 3) return false;
        for (int i = 0; i < n.length(); i++) if (!Character.isDigit(n.charAt(i))) return false;
        return true;
    }

    private static final class Cached {
        String etag, modified;
        byte[] body;
        long at;
    }

    private static final Map<String, Cached> CACHE = new HashMap<>();

    /**
     * Cuts any request still open at its deadline. The connect and read timeouts each cover one
     * step: a server dripping its headers a byte at a time, or a slow name lookup, could otherwise
     * hold a request for far longer.
     */
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "net-deadline");
            t.setDaemon(true);
            return t;
        }
    });

    static {
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    private static final class Cut implements Runnable {
        volatile HttpURLConnection conn;
        volatile boolean fired;

        @Override
        public void run() {
            fired = true;
            HttpURLConnection c = conn;
            if (c != null) c.disconnect();
        }
    }

    /** Whether {@code url} is HTTPS to an allowed host, on the default port, with no user info. */
    static boolean allowed(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            return allowed(new URL(url));
        } catch (MalformedURLException e) {
            return false;
        }
    }

    static boolean allowed(URL u) {
        return "https".equals(u.getProtocol()) && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null
                && allowedHost(u.getHost());
    }

    static byte[] get(String url, int timeoutMs, boolean conditional) {
        return get(url, timeoutMs, conditional, LIMIT_FEED);
    }

    static byte[] get(String url, int timeoutMs, boolean conditional, int maxBytes) {
        return get(url, timeoutMs, conditional, maxBytes, null, 0);
    }

    /**
     * The body, or null on any failure, when it would exceed {@code maxBytes}, or when the
     * service's budget says not now. {@code conditional} reuses the cached body on 304.
     * {@code headers} are name, value pairs (an API key). With {@code reuseMs} above 0, a body
     * fetched that recently is returned without asking again, so two loops reading one feed
     * (the bikes card and bike finder) cost one request.
     */
    static byte[] get(String url, int timeoutMs, boolean conditional, int maxBytes, String[] headers, long reuseMs) {
        if (!allowed(url)) return null;
        Cached prior;
        synchronized (CACHE) {
            prior = conditional || reuseMs > 0 ? CACHE.get(url) : null;
        }
        long wall = System.currentTimeMillis();
        if (reuseMs > 0 && prior != null && prior.body != null && wall - prior.at >= 0 && wall - prior.at < reuseMs) {
            return prior.body;
        }
        long deadline = System.nanoTime() + 3L * timeoutMs * 1000000L;
        HttpURLConnection conn = null;
        boolean keep = false;
        Cut cut = new Cut();
        ScheduledFuture<?> alarm;
        try {
            alarm = WATCHDOG.schedule(cut, 3L * timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            return null;
        }
        String host = "";
        try {
            URL target = new URL(url);
            URL at = target;
            host = target.getHost();
            int code;
            for (int hop = 0; ; hop++) {
                if (!Pace.take(host, System.currentTimeMillis())) return null;
                conn = (HttpURLConnection) at.openConnection();
                cut.conn = conn;
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", AGENT);
                conn.setRequestProperty("Accept", "application/json, application/vnd.api+json, application/x-protobuf, text/calendar, */*");
                if (headers != null) {
                    for (int i = 0; i + 1 < headers.length; i += 2) conn.setRequestProperty(headers[i], headers[i + 1]);
                }
                if (prior != null && prior.body != null && conditional) {
                    if (prior.etag != null) conn.setRequestProperty("If-None-Match", prior.etag);
                    if (prior.modified != null) conn.setRequestProperty("If-Modified-Since", prior.modified);
                }
                code = conn.getResponseCode();
                long t = System.currentTimeMillis();
                Pace.answered(host, code, conn.getHeaderField("Retry-After"), t);
                Pace.quota(host, conn.getHeaderField("X-RateLimit-Remaining"), conn.getHeaderField("X-RateLimit-Reset"), t);
                if (code < 300 || code > 399 || code == HttpURLConnection.HTTP_NOT_MODIFIED) break;
                // A redirect: only onwards to HTTPS on the same host, a few times at most.
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                conn = null;
                if (location == null || hop + 1 >= MAX_HOPS) return null;
                URL next = new URL(at, location);
                if (!allowed(next) || !next.getHost().equalsIgnoreCase(target.getHost())) return null;
                at = next;
            }
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && prior != null && prior.body != null) {
                drain(conn);
                keep = true;
                synchronized (CACHE) {
                    prior.at = System.currentTimeMillis();
                }
                return prior.body;
            }
            if (code != HttpURLConnection.HTTP_OK) return null;
            long declared = conn.getContentLength();
            if (declared > maxBytes) return null;
            byte[] body = read(conn.getInputStream(), maxBytes, deadline);
            keep = body != null && !cut.fired;
            if (body != null && (conditional || reuseMs > 0)) {
                Cached c = new Cached();
                c.etag = conditional ? conn.getHeaderField("ETag") : null;
                c.modified = conditional ? conn.getHeaderField("Last-Modified") : null;
                c.body = body;
                c.at = System.currentTimeMillis();
                synchronized (CACHE) {
                    if (c.etag != null || c.modified != null || reuseMs > 0) CACHE.put(url, c);
                    else CACHE.remove(url);
                    // Calendar URLs are secrets the user typed: only their latest answer is kept.
                    if (CACHE.size() > 64) CACHE.clear();
                }
            }
            return body;
        } catch (IOException | RuntimeException e) {
            if (!host.isEmpty()) Pace.unreachable(host, System.currentTimeMillis());
            return null;
        } finally {
            alarm.cancel(false);
            cut.conn = null;
            // A connection read to its end goes back to the pool for the next poll. Anything
            // else is closed.
            if (conn != null && !keep) conn.disconnect();
        }
    }

    /** An answer with its status, for APIs whose errors mean something (401: sign in again). */
    static final class Reply {
        final int code;
        final byte[] body;

        Reply(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }

        String text() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * A GET or a POST that reports the status code, under the same rules as {@link #get}: the
     * allowlist, the governor, the deadline and the size cap. Redirects are refused outright,
     * and nothing is cached. Returns null when nothing came back.
     */
    static Reply call(String method, String url, int timeoutMs, int maxBytes, String[] headers, String contentType, byte[] body) {
        if (!allowed(url) || !("GET".equals(method) || "POST".equals(method))) return null;
        long deadline = System.nanoTime() + 3L * timeoutMs * 1000000L;
        HttpURLConnection conn = null;
        Cut cut = new Cut();
        ScheduledFuture<?> alarm;
        try {
            alarm = WATCHDOG.schedule(cut, 3L * timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            return null;
        }
        String host = "";
        try {
            URL target = new URL(url);
            host = target.getHost();
            if (!Pace.take(host, System.currentTimeMillis())) return null;
            conn = (HttpURLConnection) target.openConnection();
            cut.conn = conn;
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", AGENT);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) conn.setRequestProperty(headers[i], headers[i + 1]);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setFixedLengthStreamingMode(body.length);
                java.io.OutputStream out = conn.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            }
            int code = conn.getResponseCode();
            long t = System.currentTimeMillis();
            Pace.answered(host, code, conn.getHeaderField("Retry-After"), t);
            Pace.quota(host, conn.getHeaderField("X-RateLimit-Remaining"), conn.getHeaderField("X-RateLimit-Reset"), t);
            if (code >= 300 && code < 400) return new Reply(code, null);
            if (conn.getContentLength() > maxBytes) return new Reply(code, null);
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] got = in == null ? new byte[0] : read(in, maxBytes, deadline);
            return new Reply(code, got);
        } catch (IOException | RuntimeException e) {
            if (!host.isEmpty()) Pace.unreachable(host, System.currentTimeMillis());
            return null;
        } finally {
            alarm.cancel(false);
            cut.conn = null;
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Streams a large file (an update) into {@code to}, under the rules of {@link #get}: the
     * allowlist, the governor, a deadline for the whole download ({@code totalMs}), no redirects.
     * True only when exactly {@code size} bytes came and their SHA-256 is {@code sha256}; else
     * {@code to} is deleted.
     */
    static boolean download(String url, File to, long size, String sha256, int timeoutMs, long totalMs) {
        if (!allowed(url) || size <= 0) return false;
        HttpURLConnection conn = null;
        Cut cut = new Cut();
        ScheduledFuture<?> alarm;
        try {
            alarm = WATCHDOG.schedule(cut, totalMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            return false;
        }
        String host = "";
        boolean ok = false;
        FileOutputStream out = null;
        try {
            URL target = new URL(url);
            host = target.getHost();
            if (!Pace.take(host, System.currentTimeMillis())) return false;
            conn = (HttpURLConnection) target.openConnection();
            cut.conn = conn;
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", AGENT);
            conn.setRequestProperty("Accept", "*/*");
            // The bytes as published, so the length and hash are of the file itself.
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            Pace.answered(host, code, conn.getHeaderField("Retry-After"), System.currentTimeMillis());
            if (code != HttpURLConnection.HTTP_OK) return false;
            long declared = conn.getContentLengthLong();
            if (declared >= 0 && declared != size) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = conn.getInputStream();
            out = new FileOutputStream(to);
            byte[] buf = new byte[64 * 1024];
            long n = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                n += r;
                if (n > size) return false;
                md.update(buf, 0, r);
                out.write(buf, 0, r);
            }
            out.getFD().sync();
            ok = n == size && !cut.fired && Update.hex(md.digest()).equals(sha256);
            return ok;
        } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
            if (!host.isEmpty()) Pace.unreachable(host, System.currentTimeMillis());
            return false;
        } finally {
            alarm.cancel(false);
            cut.conn = null;
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // the file is checked or deleted either way
                }
            }
            if (conn != null) conn.disconnect();
            if (!ok) to.delete();
        }
    }

    static String text(String url, int timeoutMs, boolean conditional) {
        return text(url, timeoutMs, conditional, LIMIT_FEED);
    }

    static String text(String url, int timeoutMs, boolean conditional, int maxBytes) {
        byte[] b = get(url, timeoutMs, conditional, maxBytes);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static String text(String url, int timeoutMs, boolean conditional, int maxBytes, String[] headers, long reuseMs) {
        byte[] b = get(url, timeoutMs, conditional, maxBytes, headers, reuseMs);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Forgets cached bodies whose URL starts with {@code prefix} (a calendar that was removed). */
    static void forget(String prefix) {
        synchronized (CACHE) {
            for (java.util.Iterator<String> it = CACHE.keySet().iterator(); it.hasNext(); ) {
                if (it.next().startsWith(prefix)) it.remove();
            }
        }
    }

    /** A 304 has no body, but reading to the end lets the connection be reused. */
    private static void drain(HttpURLConnection conn) {
        try {
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[512];
            int total = 0, n;
            while ((n = in.read(buf)) > 0 && total < 4096) total += n;
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // the connection is simply not reused
        }
    }

    /**
     * Reads at most {@code maxBytes} (after any gzip decoding, which Android does unasked), and
     * gives up when the deadline passes.
     */
    private static byte[] read(InputStream in, int maxBytes, long deadline) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes || System.nanoTime() > deadline) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
