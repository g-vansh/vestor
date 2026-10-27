package tv.lucent.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * - Only HTTPS, and only to the six public hosts Lucent reads. Anything else is refused before a
 *   socket opens, so no feed, file or setting can point Lucent somewhere new.
 * - Redirects are never followed blindly: Android's own handling would already have sent the
 *   request to the new host before Lucent could object. A redirect is followed by hand, at most
 *   three times, and only to HTTPS on the same host.
 * - Every answer has a size cap fitting what it should be, counted after decompression, and the
 *   whole request has a deadline, so a server that drips bytes cannot hold a loop.
 * - Nothing identifying is sent: no cookies, no account, no device id, a generic user agent in
 *   place of Android's default one, which names the device model and build.
 */
final class Net {
    private Net() {}

    /** Size caps: a route lookup, an API answer, a whole feed, the MIT timetable archive. */
    static final int LIMIT_TINY = 64 * 1024, LIMIT_SMALL = 1024 * 1024, LIMIT_FEED = 6 * 1024 * 1024,
            LIMIT_ZIP = 4 * 1024 * 1024;
    private static final String AGENT = "Lucent/1.2";
    private static final int MAX_HOPS = 3;

    /** The only hosts Lucent talks to. README lists what each is for. */
    static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "api.open-meteo.com", "gbfs.lyft.com", "passio3.com", "api-v3.mbta.com", "api.adsb.lol", "hexdb.io"));

    private static final class Cached {
        String etag, modified;
        byte[] body;
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

        @Override
        public void run() {
            HttpURLConnection c = conn;
            if (c != null) c.disconnect();
        }
    }

    /** Whether {@code url} is HTTPS to one of {@link #HOSTS}, on the default port. */
    static boolean allowed(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            return allowed(new URL(url));
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private static boolean allowed(URL u) {
        return "https".equals(u.getProtocol()) && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null
                && HOSTS.contains(u.getHost().toLowerCase(Locale.US));
    }

    static byte[] get(String url, int timeoutMs, boolean conditional) {
        return get(url, timeoutMs, conditional, LIMIT_FEED);
    }

    /**
     * The body, or null on any failure or when it would exceed {@code maxBytes}.
     * {@code conditional} reuses the cached body on 304.
     */
    static byte[] get(String url, int timeoutMs, boolean conditional, int maxBytes) {
        if (!allowed(url)) return null;
        Cached prior;
        synchronized (CACHE) {
            prior = conditional ? CACHE.get(url) : null;
        }
        long deadline = System.nanoTime() + 3L * timeoutMs * 1000000L;
        HttpURLConnection conn = null;
        Cut cut = new Cut();
        ScheduledFuture<?> alarm;
        try {
            alarm = WATCHDOG.schedule(cut, 3L * timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            return null;
        }
        try {
            URL target = new URL(url);
            URL at = target;
            for (int hop = 0; ; hop++) {
                conn = (HttpURLConnection) at.openConnection();
                cut.conn = conn;
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", AGENT);
                conn.setRequestProperty("Accept", "application/json, application/x-protobuf, */*");
                if (prior != null && prior.body != null) {
                    if (prior.etag != null) conn.setRequestProperty("If-None-Match", prior.etag);
                    if (prior.modified != null) conn.setRequestProperty("If-Modified-Since", prior.modified);
                }
                int code = conn.getResponseCode();
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
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && prior != null && prior.body != null) return prior.body;
            if (code != HttpURLConnection.HTTP_OK) return null;
            long declared = conn.getContentLength();
            if (declared > maxBytes) return null;
            byte[] body = read(conn.getInputStream(), maxBytes, deadline);
            if (conditional && body != null) {
                Cached c = new Cached();
                c.etag = conn.getHeaderField("ETag");
                c.modified = conn.getHeaderField("Last-Modified");
                c.body = body;
                synchronized (CACHE) {
                    if (c.etag != null || c.modified != null) CACHE.put(url, c);
                    else CACHE.remove(url);
                }
            }
            return body;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            alarm.cancel(false);
            cut.conn = null;
            if (conn != null) conn.disconnect();
        }
    }

    static String text(String url, int timeoutMs, boolean conditional) {
        return text(url, timeoutMs, conditional, LIMIT_FEED);
    }

    static String text(String url, int timeoutMs, boolean conditional, int maxBytes) {
        byte[] b = get(url, timeoutMs, conditional, maxBytes);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
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
