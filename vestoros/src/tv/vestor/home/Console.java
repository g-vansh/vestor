package tv.vestor.home;

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
import java.util.ArrayDeque;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The control center's web server, inside Vestor on the TV. A phone or laptop opens
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
 * - Everything but the page itself and the pairing steps needs a paired device's key, and a key
 *   in use is replaced every minute ({@link Keys#verify}): one copied off the Wi-Fi stops working
 *   and signs its device out the moment both copies are used.
 * - Changes come only from Vestor's own page: a POST must carry Origin exactly equal to the TV's
 *   address, a same-origin Fetch-Metadata header when the browser sends one, the custom
 *   X-Vestor header and the right Content-Type. No CORS answers are ever given, so another site's
 *   script cannot even send its request.
 *
 * How much it will take: 16 connections at once, 6 per address, one request per connection, 6
 * worker threads. Of one address's connections, at most 2 without a paired device's key are
 * being served at a time; the others wait their turn without a thread. Each request has 5 seconds
 * in all to send its head and 20 seconds in all (40 for the pairing long-poll, 130 for a photo
 * upload, plus the TV's own time to act), and a watchdog closes the socket at that deadline, so
 * a client that sends or reads a byte at a time cannot keep a thread. Size caps per route, and
 * a per-device pace.
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

        /** A device asks to pair: show the OK prompt on the TV, with {@link Keys.Pending#match}. */
        void pairRequest(Keys.Pending p);

        /** The TV's name for the page header. */
        String name();
    }

    static final int PORT = 8686;
    static final int MAX_CONNECTIONS = 16, MAX_PER_PEER = 6, MAX_UNPAIRED_PER_PEER = 2, THREADS = 6;
    static final int MAX_JSON = 64 * 1024, MAX_NOTE = 384 * 1024, MAX_PHOTO = 14 * 1024 * 1024;
    /** The longest device name a pairing request may send, before it is cleaned and cut to 40. */
    static final int MAX_PAIR_NAME = 100;
    /**
     * Deadlines in ms from when a worker takes a connection: the request head, a whole request,
     * the pairing long-poll (which holds up to {@link #HOLD_MS}) and a photo upload (14 MB).
     */
    static final long HEAD_MS = 5000, REQUEST_MS = 20000, WAIT_MS = 40000, PHOTO_MS = 130000, HOLD_MS = 25000;
    /**
     * How long a connection may wait for its turn; the TV's own time to act on a request (the
     * host waits up to 20 s for the main thread), added to the deadline when it starts.
     */
    static final long QUEUED_MS = 10000, ACT_MS = 30000, SLOW_ACT_MS = 60000;
    static final String COOKIE = "vestor";
    static final int LAN_AND_TAILNET = 0, TAILNET_ONLY = 1, OFF = 2;
    private static final long MS = 1000000L;
    private static final String CLEAR_COOKIE = COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";

    private final Host host;
    private final Keys keys;
    /** Core size equal to the maximum: a connection never waits in the queue while a thread could start. */
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(MAX_CONNECTIONS), new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "console-worker");
                    t.setDaemon(true);
                    return t;
                }
            });
    private final Watchdog watchdog = new Watchdog();
    private final Map<String, Listener> listeners = new HashMap<>();
    private final Map<String, Peer> peers = new HashMap<>();
    private final Map<String, double[]> pace = new Lru<>(256);
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
                if (!allowed || !admit(new Conn(c, peer.getHostAddress(), hostHeader()))) closeQuietly(c);
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

    /** One accepted connection, from the accept to the close. */
    private final class Conn implements Runnable {
        final Socket socket;
        final String peer, expectHost;
        /** System.nanoTime when a worker took it. */
        long started;
        /** System.nanoTime at which the watchdog closes the socket. Set through {@link Watchdog}. */
        volatile long deadline;
        /** Showed a paired device's key; ended. Guarded by Console.this. */
        boolean paired, finished;

        Conn(Socket socket, String peer, String expectHost) {
            this.socket = socket;
            this.peer = peer;
            this.expectHost = expectHost;
        }

        @Override
        public void run() {
            try {
                serve(this);
            } finally {
                finish(this);
            }
        }
    }

    /** One address's connections: how many, how many being served without a key, and the rest in line. */
    private static final class Peer {
        int open, unpaired;
        final ArrayDeque<Conn> waiting = new ArrayDeque<>();
    }

    /**
     * Closes each connection's socket at its deadline, which ends any read or write blocked on it,
     * so no client can hold a worker thread past its time. One thread that sleeps until the next
     * deadline, and not at all while nothing is open.
     */
    private static final class Watchdog implements Runnable {
        private final Set<Conn> live = new HashSet<>();
        private Thread thread;

        synchronized void set(Conn c, long deadline) {
            c.deadline = deadline;
            live.add(c);
            if (thread == null) {
                thread = new Thread(this, "console-watchdog");
                thread.setDaemon(true);
                thread.start();
            }
            notifyAll();
        }

        /** Moves the deadline to at least {@code ms} from now. */
        synchronized void extend(Conn c, long ms) {
            long d = System.nanoTime() + ms * MS;
            set(c, d - c.deadline > 0 ? d : c.deadline);
        }

        synchronized void forget(Conn c) {
            live.remove(c);
        }

        @Override
        public void run() {
            List<Conn> due = new ArrayList<>();
            while (true) {
                synchronized (this) {
                    while (true) {
                        long now = System.nanoTime(), next = Long.MAX_VALUE;
                        for (Iterator<Conn> it = live.iterator(); it.hasNext(); ) {
                            Conn c = it.next();
                            long left = c.deadline - now;
                            if (left <= 0) {
                                due.add(c);
                                it.remove();
                            } else if (left < next) {
                                next = left;
                            }
                        }
                        if (!due.isEmpty()) break;
                        try {
                            wait(next == Long.MAX_VALUE ? 0 : next / MS + 1);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                for (Conn c : due) closeQuietly(c.socket);
                due.clear();
            }
        }
    }

    /** A map that forgets the entry used longest ago once it holds more than {@code max}. */
    private static final class Lru<V> extends LinkedHashMap<String, V> {
        private static final long serialVersionUID = 1L;
        private final int max;

        Lru(int max) {
            super(16, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
            return size() > max;
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

    /**
     * Takes a new connection, or refuses it (the caller closes it) when the server or its address
     * already has as many as it may. Served at once while its address has fewer than
     * {@link #MAX_UNPAIRED_PER_PEER} served without a key, else in line behind them.
     */
    private synchronized boolean admit(Conn c) {
        Peer p = peers.get(c.peer);
        if (open >= MAX_CONNECTIONS || p != null && p.open >= MAX_PER_PEER) return false;
        if (p == null) {
            p = new Peer();
            peers.put(c.peer, p);
        }
        open++;
        p.open++;
        watchdog.set(c, System.nanoTime() + QUEUED_MS * MS);
        if (p.unpaired < MAX_UNPAIRED_PER_PEER) start(p, c);
        else p.waiting.add(c);
        return true;
    }

    /** Hands a connection to a worker, counted as served without a key until it shows one. Holds the lock. */
    private void start(Peer p, Conn c) {
        p.unpaired++;
        try {
            pool.execute(c);
        } catch (RejectedExecutionException e) {
            finish(c);
        }
    }

    /** The connection showed a paired device's key: it no longer holds up its address's line. */
    private synchronized void paired(Conn c) {
        if (c.paired || c.finished) return;
        c.paired = true;
        Peer p = peers.get(c.peer);
        if (p == null) return;
        p.unpaired--;
        next(p);
    }

    private synchronized void finish(Conn c) {
        if (c.finished) return;
        c.finished = true;
        closeQuietly(c.socket);
        watchdog.forget(c);
        open = Math.max(0, open - 1);
        Peer p = peers.get(c.peer);
        if (p == null) return;
        p.open--;
        if (!c.paired) p.unpaired--;
        next(p);
        if (p.open <= 0) peers.remove(c.peer);
    }

    /** Starts the connections in line while their address has room. Holds the lock. */
    private void next(Peer p) {
        while (p.unpaired < MAX_UNPAIRED_PER_PEER && !p.waiting.isEmpty()) start(p, p.waiting.poll());
    }

    /** A token bucket per device (or address): 10 a second, bursts of 30. */
    private synchronized boolean allow(String who, double cost, long now) {
        double[] b = pace.get(who);
        if (b == null) {
            b = new double[] {30, now};
            pace.put(who, b);
        }
        b[0] = Math.min(30, b[0] + (now - b[1]) / 100.0);
        b[1] = now;
        if (b[0] < cost) return false;
        b[0] -= cost;
        return true;
    }

    /** For the tests: the addresses and devices the pace table still remembers. */
    synchronized boolean paced(String who) {
        return pace.containsKey(who);
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

    private void serve(Conn c) {
        long start = System.nanoTime();
        c.started = start;
        watchdog.set(c, start + HEAD_MS * MS);
        OutputStream out;
        InputStream in;
        try {
            c.socket.setSoTimeout((int) HEAD_MS);
            c.socket.setTcpNoDelay(true);
            in = new BufferedInputStream(c.socket.getInputStream(), 8192);
            out = c.socket.getOutputStream();
        } catch (IOException e) {
            return;
        }
        Http.Request r = null;
        try {
            r = Http.head(in, start + HEAD_MS * MS);
            r.peer = c.peer;
            handle(c, r, in, out);
        } catch (Http.Refused e) {
            error(out, r, e.status, e.getMessage());
        } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
            error(out, r, 400, "bad request");
        }
    }

    /** The deadline of a whole request, ms from when a worker took it. */
    private static long budget(Http.Request r) {
        if ("POST".equals(r.method) && "/api/pair/wait".equals(r.path)) return WAIT_MS;
        if ("POST".equals(r.method) && "/api/photo".equals(r.path)) return PHOTO_MS;
        return REQUEST_MS;
    }

    /** Routes one request whose head has been read. */
    private void handle(Conn c, Http.Request r, InputStream in, OutputStream out) throws IOException, Http.Refused {
        long now = System.currentTimeMillis();
        // DNS rebinding: only the TV's own address is a valid name for it.
        if (!c.expectHost.equals(r.header("host"))) throw new Http.Refused(421, "host");
        watchdog.set(c, c.started + budget(r) * MS);
        String[] file = FILES.get(r.path);
        if ("GET".equals(r.method) && file != null) {
            // The page's files need no key, but with one they do not wait behind strangers.
            if (keys.known(Http.cookie(r, COOKIE), now)) paired(c);
            if (!allow(r.peer, 0.5, now)) throw new Http.Refused(429, "slow down");
            byte[] b = host.asset(file[0]);
            if (b == null) throw new Http.Refused(404, "missing");
            Http.respond(out, 200, file[1], b, true);
            return;
        }
        if (!r.path.startsWith("/api/")) throw new Http.Refused(404, "no such page");
        sameOrigin(r, c.expectHost);
        if ("GET".equals(r.method) && "/api/hello".equals(r.path)) {
            if (!allow(r.peer, 1, now)) throw new Http.Refused(429, "slow down");
            Keys.Check k = keys.verify(Http.cookie(r, COOKIE), r.peer, now);
            if (k.reused) {
                reused(out);
                return;
            }
            if (k.device != null) {
                paired(c);
                r.setCookie = k.cookie;
            }
            Jw w = new Jw().obj().put("paired", k.device != null).put("name", host.name()).put("pairing", keys.windowOpen(now));
            String nonce = keys.nonce(now);
            if (nonce != null) w.put("nonce", nonce);
            json(out, r, 200, w.end().toString());
            return;
        }
        if ("POST".equals(r.method) && OPEN_POSTS.contains(r.path)) {
            if (!allow(r.peer, 5, now)) throw new Http.Refused(429, "slow down");
            Map<String, Object> body = jsonBody(c, r, in, 4096);
            pair(c, r, body, out, now);
            return;
        }
        Keys.Check k = keys.verify(Http.cookie(r, COOKIE), r.peer, now);
        if (k.reused) {
            reused(out);
            return;
        }
        Keys.Device d = k.device;
        if (d == null) throw new Http.Refused(401, "not paired");
        paired(c);
        // From here on every answer, errors too, carries the new key when there is one.
        r.setCookie = k.cookie;
        if (!allow("d:" + d.selector, 1, now)) throw new Http.Refused(429, "slow down");
        if ("GET".equals(r.method)) {
            if ("/api/state".equals(r.path)) {
                json(out, r, 200, host.state(d, r.peer));
                return;
            }
            if (r.path.startsWith("/api/photo/")) {
                String name = r.path.substring("/api/photo/".length());
                boolean thumb = name.endsWith(".thumb.jpg");
                String id = name.replace(".thumb.jpg", "").replace(".jpg", "");
                if (!id.matches("[0-9a-f]{32}")) throw new Http.Refused(404, "photo");
                byte[] b = host.photoFile(id, thumb);
                if (b == null) throw new Http.Refused(404, "photo");
                // A full photo can be several MB: time to send it at 256 KB a second or better.
                watchdog.extend(c, 10000 + b.length / 256);
                send(out, r, 200, "image/jpeg", b);
                return;
            }
            throw new Http.Refused(404, "no such api");
        }
        if ("/api/photo".equals(r.path)) {
            String type = r.header("content-type");
            if (!"image/jpeg".equals(type) && !"image/png".equals(type)) throw new Http.Refused(415, "photo type");
            if (!allow("d:" + d.selector, 10, now)) throw new Http.Refused(429, "slow down");
            int n = Http.length(r, MAX_PHOTO);
            byte[] body = Http.body(in, n, c.deadline);
            String caption = decodeHeader(r.header("x-caption"));
            long taken = 0;
            String t = r.header("x-taken");
            if (t != null && t.matches("[0-9]{1,15}")) taken = Long.parseLong(t);
            watchdog.extend(c, ACT_MS);
            json(out, r, 200, host.photo(d, body, caption, taken));
            return;
        }
        String action = r.path.substring("/api/".length());
        if (!action.matches("[a-z]{2,16}(/[a-z]{2,16})?")) throw new Http.Refused(404, "no such api");
        int cap = "note".equals(action) ? MAX_NOTE : MAX_JSON;
        Map<String, Object> body = jsonBody(c, r, in, cap);
        if ("logout".equals(action)) {
            keys.revoke(d.selector, now);
            r.setCookie = null;
            Http.respond(out, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), false, "Set-Cookie", CLEAR_COOKIE);
            return;
        }
        // Pairing with the Samsung waits up to 45 s for Allow on the TV, and SmartThings steps up to 50 s.
        watchdog.extend(c, "remote".equals(action) || "samsung".equals(action) ? SLOW_ACT_MS : ACT_MS);
        json(out, r, 200, host.act(d, action, body));
    }

    /**
     * Cross-site defences for everything under /api: an exact Origin on POSTs, Fetch-Metadata
     * saying same-origin when present, and the X-Vestor header no other site can add without a
     * CORS preflight that this server never answers.
     */
    private static void sameOrigin(Http.Request r, String expectHost) throws Http.Refused {
        String site = r.header("sec-fetch-site");
        if (site != null && !"same-origin".equals(site)) throw new Http.Refused(403, "cross-site");
        if (!"1".equals(r.header("x-vestor"))) throw new Http.Refused(403, "missing header");
        if ("POST".equals(r.method)) {
            String origin = r.header("origin");
            if (origin == null || !origin.equals("http://" + expectHost)) throw new Http.Refused(403, "origin");
        }
    }

    private static Map<String, Object> jsonBody(Conn c, Http.Request r, InputStream in, int cap) throws IOException, Http.Refused {
        String type = r.header("content-type");
        if (type == null || !(type.equals("application/json") || type.startsWith("application/json;"))) {
            throw new Http.Refused(415, "json only");
        }
        int n = Http.length(r, cap);
        long deadline = System.nanoTime() + 15000 * MS;
        if (c.deadline - deadline < 0) deadline = c.deadline;
        byte[] b = Http.body(in, n, deadline);
        if (b.length == 0) return new HashMap<>();
        try {
            Map<String, Object> m = Json.obj(Json.parse(new String(b, StandardCharsets.UTF_8)));
            if (m == null) throw new Http.Refused(400, "json object");
            return m;
        } catch (IllegalArgumentException | StackOverflowError e) {
            throw new Http.Refused(400, "json");
        }
    }

    /**
     * /api/pair takes {"proof", "c", "name"} (the QR secret, proven without sending it; see
     * {@link Keys}) or {"pin", "name"}, and answers 202 {"wait": id, "match": "123"} or 403 with
     * why. /api/pair/wait then holds for the OK on the remote.
     */
    private void pair(Conn c, Http.Request r, Map<String, Object> body, OutputStream out, long now) throws IOException, Http.Refused {
        if ("/api/pair".equals(r.path)) {
            String proof = Json.str(body, "proof", null), nonceC = Json.str(body, "c", null), pin = Json.str(body, "pin", null);
            String name = Json.str(body, "name", "");
            // The secret itself ({"k": ...}) is no longer taken: it must never cross the network.
            if (body.containsKey("k") || name.length() > MAX_PAIR_NAME) throw new Http.Refused(400, "pair");
            Keys.Claim claim;
            if (proof != null && pin == null) {
                if (nonceC == null || !proof.matches("[0-9a-f]{64}") || !nonceC.matches("[A-Za-z0-9_-]{22}")) {
                    throw new Http.Refused(400, "pair");
                }
                claim = keys.claimProof(proof, nonceC, name, r.peer, now);
            } else if (pin != null && proof == null) {
                if (!pin.matches("[0-9]{6}")) throw new Http.Refused(400, "pair");
                claim = keys.claimPin(pin, name, r.peer, now);
            } else {
                throw new Http.Refused(400, "pair");
            }
            Keys.Pending p = claim.pending;
            if (p == null) {
                json(out, r, 403, new Jw().obj().put("error", claim.error).put("lockedFor", claim.lockedFor).end().toString());
                return;
            }
            host.pairRequest(p);
            json(out, r, 202, new Jw().obj().put("wait", p.id).put("match", p.match).end().toString());
            return;
        }
        // /api/pair/wait: hold the request up to 25 s for the OK on the remote, ending in time to answer.
        String id = Json.str(body, "id", "");
        long until = System.nanoTime() + HOLD_MS * MS, last = c.deadline - 5000 * MS;
        if (last - until < 0) until = last;
        while (true) {
            String[] got = keys.collect(id, r.peer, System.currentTimeMillis());
            if ("2".equals(got[0])) {
                json(out, r, 403, "{\"error\":\"refused\"}");
                return;
            }
            if ("1".equals(got[0])) {
                Http.respond(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), false,
                        "Set-Cookie", keyCookie(got[1]));
                return;
            }
            if (System.nanoTime() - until > 0) {
                json(out, r, 202, new Jw().obj().put("wait", id).end().toString());
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

    /** The Set-Cookie value for a device key: the same flags whenever it is handed out or replaced. */
    static String keyCookie(String value) {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=34560000";
    }

    /** A copied key was used: its device is signed out, and this browser drops the key. */
    private static void reused(OutputStream out) throws IOException {
        Http.respond(out, 401, "application/json; charset=utf-8",
                "{\"error\":\"reused\",\"status\":401}".getBytes(StandardCharsets.UTF_8), false, "Set-Cookie", CLEAR_COOKIE);
    }

    /** Any answer: with the device's new key when {@link Keys#verify} just made one. */
    private static void send(OutputStream out, Http.Request r, int code, String type, byte[] body) throws IOException {
        if (r != null && r.setCookie != null) {
            Http.respond(out, code, type, body, false, "Set-Cookie", keyCookie(r.setCookie));
        } else {
            Http.respond(out, code, type, body, false);
        }
    }

    private static void json(OutputStream out, Http.Request r, int code, String json) throws IOException {
        send(out, r, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /** Reasons are Vestor's own fixed words, never text from the request. */
    private static void error(OutputStream out, Http.Request r, int code, String why) {
        try {
            json(out, r, code, new Jw().obj().put("error", why == null ? "error" : why).put("status", code).end().toString());
        } catch (IOException ignored) {
            // the peer is gone
        }
    }
}
