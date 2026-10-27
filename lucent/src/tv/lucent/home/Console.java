package tv.lucent.home;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The control center's web server, inside Lucent on the TV. A phone or laptop opens
 * http://&lt;tv address&gt;:8686 and, once paired ({@link Keys}), can send notes, add photos, start
 * bike finder and change settings.
 *
 * Who can reach it:
 * - It listens only on the TV's home Wi-Fi or Ethernet address (a private 10.x, 172.16-31.x or
 *   192.168.x address) and, if Tailscale runs on the TV, its 100.64.0.0/10 tailnet address. Never
 *   on every interface, never on IPv6, and it opens nothing on the router.
 * - A connection is dropped before a byte is read unless it comes from the same home subnet (or
 *   the tailnet, on the tailnet listener).
 * - The Host header must be the TV's own address and port. A web page that tricks a browser at
 *   home into talking to the TV under another name (DNS rebinding) gets 421 and nothing else.
 * - Everything but the page itself and the pairing steps needs a paired device's key.
 * - Changes come only from Lucent's own page: a POST must carry Origin exactly equal to the TV's
 *   address, a same-origin Fetch-Metadata header when the browser sends one, the custom
 *   X-Lucent header and the right Content-Type. No CORS answers are ever given, so another site's
 *   script cannot even send its request.
 *
 * How much it will take: 16 connections at once (4 per address), 6 worker threads, one request
 * per connection, 5 seconds to send the headers, size caps per route, and a per-device pace.
 *
 * Platform-free: the actions behind each route live in {@link Host}, so the desktop tests attack
 * this class directly.
 */
final class Console {
    interface Host {
        /** A file of the web app from the APK's assets, or null. */
        byte[] asset(String name);

        /** The whole state the page shows, as JSON. */
        String state(Keys.Device d, String peer);

        /** A POST to /api/{@code action} with a parsed JSON body. Returns JSON. */
        String act(Keys.Device d, String action, Map<String, Object> body) throws Http.Refused;

        /** A photo upload: the raw JPEG or PNG bytes. Returns JSON. */
        String photo(Keys.Device d, byte[] body, String caption, long taken) throws Http.Refused;

        /** A stored photo (or its thumbnail) by id, JPEG bytes, or null. */
        byte[] photoFile(String id, boolean thumb);

        /** A device asks to pair: show the OK prompt on the TV. */
        void pairRequest(Keys.Pending p);

        /** The TV's name for the page header. */
        String name();
    }

    static final int PORT = 8686;
    static final int MAX_CONNECTIONS = 16, MAX_PER_PEER = 4;
    static final int MAX_JSON = 64 * 1024, MAX_NOTE = 384 * 1024, MAX_PHOTO = 14 * 1024 * 1024;
    static final String COOKIE = "lucent";
    static final int LAN_AND_TAILNET = 0, TAILNET_ONLY = 1, OFF = 2;

    private final Host host;
    private final Keys keys;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(10));
    private final Map<String, Listener> listeners = new HashMap<>();
    private final Map<String, Integer> perPeer = new HashMap<>();
    private final Map<String, double[]> pace = new HashMap<>();
    private int open;
    private volatile int access = LAN_AND_TAILNET;
    private volatile int port = PORT;

    /** One bound address. {@code net} and {@code prefix} say which peers it accepts. */
    private final class Listener implements Runnable {
        final InetAddress addr;
        final int prefix;
        final boolean tailnet;
        /** Desktop tests only ({@link #listenOn}): the loopback peer is let through. */
        final boolean loopback;
        final ServerSocket socket;

        Listener(InetAddress addr, int prefix, boolean tailnet) throws IOException {
            this(addr, prefix, tailnet, false);
        }

        Listener(InetAddress addr, int prefix, boolean tailnet, boolean loopback) throws IOException {
            this.addr = addr;
            this.prefix = prefix;
            this.tailnet = tailnet;
            this.loopback = loopback && addr.isLoopbackAddress();
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(addr, port), 16);
            socket = s;
        }

        String hostHeader() {
            return addr.getHostAddress() + ":" + port;
        }

        @Override
        public void run() {
            while (!socket.isClosed()) {
                final Socket c;
                try {
                    c = socket.accept();
                } catch (IOException e) {
                    if (socket.isClosed()) return;
                    continue;
                }
                InetAddress peer = c.getInetAddress();
                // Never the TV itself: another app on the TV connecting to its own address is not a phone.
                boolean allowed = loopback ? peer.isLoopbackAddress()
                        : tailnet ? inRange(peer, TAILNET_NET, 10) && !peer.equals(addr) : prefix > 0 && sameSubnet(peer, addr, prefix);
                if (!allowed || !admit(peer.getHostAddress())) {
                    closeQuietly(c);
                    continue;
                }
                final String key = peer.getHostAddress();
                final String expectHost = hostHeader();
                try {
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                serve(c, key, expectHost);
                            } finally {
                                closeQuietly(c);
                                release(key);
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    closeQuietly(c);
                    release(key);
                }
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static final byte[] TAILNET_NET = {100, 64, 0, 0};

    Console(Host host, Keys keys) {
        this.host = host;
        this.keys = keys;
        pool.allowCoreThreadTimeOut(true);
    }

    /** Which networks may reach the control center. */
    void setAccess(int mode) {
        access = mode;
        refresh();
    }

    int access() {
        return access;
    }

    /** For tests: listen on another port. */
    void setPort(int p) {
        port = p;
    }

    /**
     * Binds to the addresses the TV has now and lets go of ones it lost. Called every 30 seconds
     * and when the network changes.
     */
    synchronized void refresh() {
        Map<String, Object[]> want = new LinkedHashMap<>();
        if (access != OFF) {
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress a = ia.getAddress();
                        if (!(a instanceof Inet4Address)) continue;
                        if (inRange(a, TAILNET_NET, 10)) {
                            want.put(a.getHostAddress(), new Object[] {a, 0, true});
                        } else if (access == LAN_AND_TAILNET && isPrivate(a) && !ni.isVirtual()) {
                            want.put(a.getHostAddress(), new Object[] {a, (int) ia.getNetworkPrefixLength(), false});
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // keep whatever is bound
                return;
            }
        }
        for (Iterator<Map.Entry<String, Listener>> it = listeners.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Listener> e = it.next();
            if (!want.containsKey(e.getKey()) || e.getValue().socket.getLocalPort() != port) {
                e.getValue().close();
                it.remove();
            }
        }
        for (Map.Entry<String, Object[]> e : want.entrySet()) {
            if (listeners.containsKey(e.getKey())) continue;
            Object[] v = e.getValue();
            int prefix = (Integer) v[1];
            // A home network wider than /16 is unusual: its listener only accepts a /24 around the TV.
            if (!(Boolean) v[2] && (prefix < 16 || prefix > 30)) prefix = 24;
            try {
                Listener l = new Listener((InetAddress) v[0], prefix, (Boolean) v[2]);
                Thread t = new Thread(l, "console");
                t.setDaemon(true);
                t.start();
                listeners.put(e.getKey(), l);
            } catch (IOException | RuntimeException ignored) {
                // that address cannot be bound right now: the next refresh tries again
            }
        }
    }

    /** For the desktop tests: listen on one given address (loopback there). Never called on the TV. */
    synchronized void listenOn(InetAddress a, int prefix) throws IOException {
        Listener l = new Listener(a, prefix, false, true);
        Thread t = new Thread(l, "console");
        t.setDaemon(true);
        t.start();
        listeners.put(a.getHostAddress(), l);
    }

    /** "http://192.168.1.23:8686" for each address listened on, home network first. */
    synchronized List<String> urls() {
        List<String> lan = new ArrayList<>(), tail = new ArrayList<>();
        for (Listener l : listeners.values()) (l.tailnet ? tail : lan).add("http://" + l.hostHeader());
        lan.addAll(tail);
        return lan;
    }

    synchronized void stop() {
        for (Listener l : listeners.values()) l.close();
        listeners.clear();
    }

    // ---- Admission -------------------------------------------------------------------------------

    private synchronized boolean admit(String peer) {
        Integer n = perPeer.get(peer);
        if (open >= MAX_CONNECTIONS || n != null && n >= MAX_PER_PEER) return false;
        open++;
        perPeer.put(peer, n == null ? 1 : n + 1);
        return true;
    }

    private synchronized void release(String peer) {
        open = Math.max(0, open - 1);
        Integer n = perPeer.get(peer);
        if (n == null || n <= 1) perPeer.remove(peer);
        else perPeer.put(peer, n - 1);
    }

    /** A token bucket per device (or address): 10 a second, bursts of 30. */
    private synchronized boolean allow(String who, double cost, long now) {
        double[] b = pace.get(who);
        if (b == null) {
            if (pace.size() > 256) pace.clear();
            b = new double[] {30, now};
            pace.put(who, b);
        }
        b[0] = Math.min(30, b[0] + (now - b[1]) / 100.0);
        b[1] = now;
        if (b[0] < cost) return false;
        b[0] -= cost;
        return true;
    }

    static boolean isPrivate(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 4) return false;
        int x = b[0] & 0xFF, y = b[1] & 0xFF;
        return x == 10 || x == 172 && y >= 16 && y <= 31 || x == 192 && y == 168;
    }

    static boolean inRange(InetAddress a, byte[] net, int prefix) {
        byte[] b = a.getAddress();
        if (b.length != 4) return false;
        int ai = toInt(b), ni = toInt(net);
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        return (ai & mask) == (ni & mask);
    }

    static boolean sameSubnet(InetAddress peer, InetAddress self, int prefix) {
        if (peer.equals(self)) return false;
        return inRange(peer, self.getAddress(), prefix) && isPrivate(peer);
    }

    private static int toInt(byte[] b) {
        return (b[0] & 0xFF) << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    // ---- One request -----------------------------------------------------------------------------

    private static final Map<String, String[]> FILES = new HashMap<>();

    static {
        FILES.put("/", new String[] {"web/index.html", "text/html; charset=utf-8"});
        FILES.put("/app.js", new String[] {"web/app.js", "text/javascript; charset=utf-8"});
        FILES.put("/app.css", new String[] {"web/app.css", "text/css; charset=utf-8"});
        FILES.put("/icon.svg", new String[] {"web/icon.svg", "image/svg+xml"});
        FILES.put("/manifest.webmanifest", new String[] {"web/manifest.webmanifest", "application/manifest+json"});
        FILES.put("/fonts/inter-medium.ttf", new String[] {"fonts/Inter-Medium.ttf", "font/ttf"});
        FILES.put("/fonts/inter-semibold.ttf", new String[] {"fonts/Inter-SemiBold.ttf", "font/ttf"});
        FILES.put("/fonts/newsreader-display.ttf", new String[] {"fonts/NewsreaderDisplay-Regular.ttf", "font/ttf"});
        FILES.put("/fonts/newsreader-italic.ttf", new String[] {"fonts/NewsreaderDisplay-Italic.ttf", "font/ttf"});
        FILES.put("/fonts/geist-mono.ttf", new String[] {"fonts/GeistMono-Medium.ttf", "font/ttf"});
    }

    private static final Set<String> OPEN_POSTS = new HashSet<>();

    static {
        OPEN_POSTS.add("/api/pair");
        OPEN_POSTS.add("/api/pair/wait");
    }

    void serve(Socket c, String peer, String expectHost) {
        OutputStream out;
        InputStream in;
        try {
            c.setSoTimeout(5000);
            c.setTcpNoDelay(true);
            in = new BufferedInputStream(c.getInputStream(), 8192);
            out = c.getOutputStream();
        } catch (IOException e) {
            return;
        }
        try {
            Http.Request r = Http.head(in);
            r.peer = peer;
            handle(r, in, out, expectHost);
        } catch (Http.Refused e) {
            error(out, e.status, e.getMessage());
        } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
            error(out, 400, "bad request");
        }
    }

    /** Routes one request whose head has been read. Package-private for the tests. */
    void handle(Http.Request r, InputStream in, OutputStream out, String expectHost) throws IOException, Http.Refused {
        long now = System.currentTimeMillis();
        // DNS rebinding: only the TV's own address is a valid name for it.
        if (!expectHost.equals(r.header("host"))) throw new Http.Refused(421, "host");
        String[] file = FILES.get(r.path);
        if ("GET".equals(r.method) && file != null) {
            if (!allow(r.peer, 0.5, now)) throw new Http.Refused(429, "slow down");
            byte[] b = host.asset(file[0]);
            if (b == null) throw new Http.Refused(404, "missing");
            Http.respond(out, 200, file[1], b, true);
            return;
        }
        if (!r.path.startsWith("/api/")) throw new Http.Refused(404, "no such page");
        sameOrigin(r, expectHost);
        if ("GET".equals(r.method) && "/api/hello".equals(r.path)) {
            if (!allow(r.peer, 1, now)) throw new Http.Refused(429, "slow down");
            Keys.Device d = keys.check(Http.cookie(r, COOKIE), r.peer, now);
            json(out, 200, new Jw().obj().put("paired", d != null).put("name", host.name())
                    .put("pairing", keys.windowOpen(now)).end().toString());
            return;
        }
        if ("POST".equals(r.method) && OPEN_POSTS.contains(r.path)) {
            if (!allow(r.peer, 5, now)) throw new Http.Refused(429, "slow down");
            Map<String, Object> body = jsonBody(r, in, 4096);
            pair(r, body, out, now);
            return;
        }
        Keys.Device d = keys.check(Http.cookie(r, COOKIE), r.peer, now);
        if (d == null) throw new Http.Refused(401, "not paired");
        if (!allow("d:" + d.selector, 1, now)) throw new Http.Refused(429, "slow down");
        if ("GET".equals(r.method)) {
            if ("/api/state".equals(r.path)) {
                json(out, 200, host.state(d, r.peer));
                return;
            }
            if (r.path.startsWith("/api/photo/")) {
                String name = r.path.substring("/api/photo/".length());
                boolean thumb = name.endsWith(".thumb.jpg");
                String id = name.replace(".thumb.jpg", "").replace(".jpg", "");
                if (!id.matches("[0-9a-f]{32}")) throw new Http.Refused(404, "photo");
                byte[] b = host.photoFile(id, thumb);
                if (b == null) throw new Http.Refused(404, "photo");
                Http.respond(out, 200, "image/jpeg", b, false);
                return;
            }
            throw new Http.Refused(404, "no such api");
        }
        if ("/api/photo".equals(r.path)) {
            String type = r.header("content-type");
            if (!"image/jpeg".equals(type) && !"image/png".equals(type)) throw new Http.Refused(415, "photo type");
            if (!allow("d:" + d.selector, 10, now)) throw new Http.Refused(429, "slow down");
            int n = Http.length(r, MAX_PHOTO);
            byte[] body = Http.body(in, n, System.nanoTime() + 120000000000L);
            String caption = decodeHeader(r.header("x-caption"));
            long taken = 0;
            String t = r.header("x-taken");
            if (t != null && t.matches("[0-9]{1,15}")) taken = Long.parseLong(t);
            json(out, 200, host.photo(d, body, caption, taken));
            return;
        }
        String action = r.path.substring("/api/".length());
        if (!action.matches("[a-z]{2,16}(/[a-z]{2,16})?")) throw new Http.Refused(404, "no such api");
        int cap = "note".equals(action) ? MAX_NOTE : MAX_JSON;
        Map<String, Object> body = jsonBody(r, in, cap);
        if ("logout".equals(action)) {
            keys.revoke(d.selector, now);
            Http.respond(out, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), false,
                    "Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return;
        }
        json(out, 200, host.act(d, action, body));
    }

    /**
     * Cross-site defences for everything under /api: an exact Origin on POSTs, Fetch-Metadata
     * saying same-origin when present, and the X-Lucent header no other site can add without a
     * CORS preflight that this server never answers.
     */
    private static void sameOrigin(Http.Request r, String expectHost) throws Http.Refused {
        String site = r.header("sec-fetch-site");
        if (site != null && !"same-origin".equals(site)) throw new Http.Refused(403, "cross-site");
        if (!"1".equals(r.header("x-lucent"))) throw new Http.Refused(403, "missing header");
        if ("POST".equals(r.method)) {
            String origin = r.header("origin");
            if (origin == null || !origin.equals("http://" + expectHost)) throw new Http.Refused(403, "origin");
        }
    }

    private static Map<String, Object> jsonBody(Http.Request r, InputStream in, int cap) throws IOException, Http.Refused {
        String type = r.header("content-type");
        if (type == null || !(type.equals("application/json") || type.startsWith("application/json;"))) {
            throw new Http.Refused(415, "json only");
        }
        int n = Http.length(r, cap);
        byte[] b = Http.body(in, n, System.nanoTime() + 15000000000L);
        if (b.length == 0) return new HashMap<>();
        try {
            Map<String, Object> m = Json.obj(Json.parse(new String(b, StandardCharsets.UTF_8)));
            if (m == null) throw new Http.Refused(400, "json object");
            return m;
        } catch (IllegalArgumentException | StackOverflowError e) {
            throw new Http.Refused(400, "json");
        }
    }

    private void pair(Http.Request r, Map<String, Object> body, OutputStream out, long now) throws IOException, Http.Refused {
        if ("/api/pair".equals(r.path)) {
            String k = Json.str(body, "k", null), pin = Json.str(body, "pin", null);
            String name = Json.str(body, "name", "");
            if (k != null && k.length() > 64 || pin != null && !pin.matches("[0-9]{6}")) throw new Http.Refused(400, "pair");
            Keys.Pending p = k != null ? keys.claim(k, false, name, r.peer, now) : pin != null ? keys.claim(pin, true, name, r.peer, now) : null;
            if (p == null) {
                long locked = keys.lockedFor(now);
                json(out, 403, new Jw().obj().put("error", locked > 0 ? "locked" : keys.windowOpen(now) ? "wrong" : "closed")
                        .put("lockedFor", locked).end().toString());
                return;
            }
            host.pairRequest(p);
            json(out, 202, new Jw().obj().put("wait", p.id).end().toString());
            return;
        }
        // /api/pair/wait: hold the request up to 25 s for the OK on the remote.
        String id = Json.str(body, "id", "");
        long until = now + 25000;
        while (true) {
            String[] got = keys.collect(id, r.peer, System.currentTimeMillis());
            if ("2".equals(got[0])) {
                json(out, 403, "{\"error\":\"refused\"}");
                return;
            }
            if ("1".equals(got[0])) {
                Http.respond(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), false,
                        "Set-Cookie", COOKIE + "=" + got[1] + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=34560000");
                return;
            }
            if (System.currentTimeMillis() > until) {
                json(out, 202, new Jw().obj().put("wait", id).end().toString());
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** X-Caption arrives percent-encoded UTF-8 (headers are ASCII). Decoded, cleaned, capped. */
    static String decodeHeader(String v) {
        if (v == null || v.length() > 600) return "";
        try {
            return Text.clean(java.net.URLDecoder.decode(v, "UTF-8"));
        } catch (IOException | IllegalArgumentException e) {
            return "";
        }
    }

    private static void json(OutputStream out, int code, String json) throws IOException {
        Http.respond(out, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), false);
    }

    /** Reasons are Lucent's own fixed words, never text from the request. */
    private static void error(OutputStream out, int code, String why) {
        try {
            Http.respond(out, code, "application/json; charset=utf-8",
                    new Jw().obj().put("error", why == null ? "error" : why).put("status", code).end().toString()
                            .getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException ignored) {
            // the peer is gone
        }
    }
}
