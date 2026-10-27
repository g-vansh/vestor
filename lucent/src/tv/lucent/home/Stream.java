package tv.lucent.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A long-lived HTTPS stream that the server pushes into: the MBTA's live predictions as
 * server-sent events, or an ntfy topic as one JSON object per line. One request then carries
 * every update the moment it happens, which is both the fastest way to hear about a bus and the
 * gentlest on the service's limits.
 *
 * The same rules as {@link Net}: HTTPS to allowed hosts only, no redirects, every connect asks
 * {@link Pace} first, and reconnects back off with jitter. A connection is a fault, ended at
 * once, when a line reaches 1 MB (without waiting for its end), an event 2 MB, the bytes of one
 * connection their cap (16 MB for the MBTA's events, 1 MB for an ntfy topic, unless the caller
 * names another), or the stream stops talking for {@code idleMs}. A fault reconnects with the
 * backoff, which only resets after a connection that ended cleanly or lasted five minutes, so a
 * server that floods cannot make it reconnect in a tight loop. A connection is also recycled
 * every 30 minutes, so a stream that silently stalled cannot go stale for long.
 */
final class Stream {
    interface Handler {
        /** One event ("message" when the server named none) or one JSON line ("line"). Stream thread. */
        void event(String type, String data);

        /** The stream opened (true) or dropped (false). Stream thread. */
        void state(boolean open);
    }

    static final int SSE = 0, LINES = 1;
    static final int MAX_LINE = 1024 * 1024, MAX_EVENT = 2 * 1024 * 1024;
    /** Bytes one connection may carry: the MBTA's server-sent events, and an ntfy topic's lines. */
    static final long SSE_MAX_BYTES = 16L * 1024 * 1024, LINES_MAX_BYTES = 1024L * 1024;
    private static final long LIFETIME_MS = 30 * 60000L, STEADY_MS = 5 * 60000L;

    private final String url;
    private final String[] headers;
    private final int mode, idleMs;
    private final long maxBytes;
    private final Handler handler;
    private volatile boolean running, stopped;
    private volatile HttpURLConnection conn;
    private Thread thread;

    /** A stream with the byte cap of its kind: 16 MB a connection for SSE, 1 MB for lines. */
    Stream(String url, String[] headers, int mode, int idleMs, Handler handler) {
        this(url, headers, mode, idleMs, mode == SSE ? SSE_MAX_BYTES : LINES_MAX_BYTES, handler);
    }

    /** {@code maxBytes}: what one connection may carry before it is dropped as a fault. */
    Stream(String url, String[] headers, int mode, int idleMs, long maxBytes, Handler handler) {
        this.url = url;
        this.headers = headers;
        this.mode = mode;
        this.idleMs = idleMs;
        this.maxBytes = Math.max(1, maxBytes);
        this.handler = handler;
    }

    synchronized void start() {
        if (running) return;
        if (!Net.allowed(url)) return;
        running = true;
        stopped = false;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "stream");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        stopped = true;
        HttpURLConnection c = conn;
        if (c != null) c.disconnect();
        if (thread != null) thread.interrupt();
        thread = null;
    }

    boolean running() {
        return running;
    }

    private void loop() {
        String host;
        try {
            host = new URL(url).getHost();
        } catch (IOException e) {
            running = false;
            return;
        }
        int strikes = 0;
        while (running) {
            long now = System.currentTimeMillis();
            long wait = Pace.wait(host, now);
            if (wait > 0) {
                if (!sleep(Math.min(wait, 5000))) return;
                continue;
            }
            if (!Pace.take(host, now)) {
                if (!sleep(1000)) return;
                continue;
            }
            boolean opened = false, clean = false;
            long openedAt = 0;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                conn = c;
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(idleMs);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", Net.AGENT);
                c.setRequestProperty("Accept", mode == SSE ? "text/event-stream" : "application/x-ndjson, application/json");
                // Compressed streams are buffered by the decoder: events would arrive late.
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Cache-Control", "no-cache");
                if (headers != null) {
                    for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
                }
                int code = c.getResponseCode();
                long t = System.currentTimeMillis();
                Pace.answered(host, code, c.getHeaderField("Retry-After"), t);
                Pace.quota(host, c.getHeaderField("X-RateLimit-Remaining"), c.getHeaderField("X-RateLimit-Reset"), t);
                if (code == HttpURLConnection.HTTP_OK && running) {
                    opened = true;
                    openedAt = System.currentTimeMillis();
                    handler.state(true);
                    clean = read(c.getInputStream(), System.currentTimeMillis() + LIFETIME_MS);
                }
            } catch (IOException | RuntimeException e) {
                if (running) Pace.unreachable(host, System.currentTimeMillis());
            } finally {
                conn = null;
                if (c != null) c.disconnect();
                if (opened) handler.state(false);
            }
            if (!running) return;
            // A stream the server ended or that reached its lifetime reconnects at once. Trouble backs off,
            // and keeps backing off while each connection faults soon after it opened.
            if (clean || opened && System.currentTimeMillis() - openedAt >= STEADY_MS) strikes = 0;
            long pause = clean ? 1000 : Pace.backoff(++strikes, 2000, 120000);
            if (!sleep(pause)) return;
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * Reads until the end, the lifetime, or a fault. True when it ended without a fault: false as
     * soon as a line reaches {@link #MAX_LINE} or the connection {@code maxBytes}, without reading
     * on to a line end. Package-private for the tests.
     */
    boolean read(InputStream in, long until) throws IOException {
        try {
            byte[] buf = new byte[8192];
            ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
            StringBuilder data = new StringBuilder();
            String type = null;
            long total = 0;
            int n;
            while (!stopped && (n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) return false;
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b != '\n') {
                        if (line.size() >= MAX_LINE) return false;
                        line.write(b);
                        continue;
                    }
                    String s = new String(line.toByteArray(), StandardCharsets.UTF_8);
                    line.reset();
                    if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
                    if (mode == LINES) {
                        if (!s.isEmpty()) deliver("line", s);
                        continue;
                    }
                    if (s.isEmpty()) {
                        if (data.length() > 0) deliver(type == null ? "message" : type, data.toString());
                        data.setLength(0);
                        type = null;
                    } else if (s.startsWith(":")) {
                        // a comment, sent as a keep-alive
                    } else if (s.startsWith("event:")) {
                        type = s.substring(6).trim();
                        if (type.length() > 32) type = type.substring(0, 32);
                    } else if (s.startsWith("data:")) {
                        String d = s.substring(5);
                        if (d.startsWith(" ")) d = d.substring(1);
                        if (data.length() > 0) data.append('\n');
                        if (data.length() + d.length() > MAX_EVENT) return false;
                        data.append(d);
                    }
                }
                if (System.currentTimeMillis() > until) return true;
            }
            return true;
        } finally {
            in.close();
        }
    }

    private void deliver(String type, String data) {
        try {
            handler.event(type, data);
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
            // One event no parser expected is dropped, the stream carries on.
        }
    }
}
