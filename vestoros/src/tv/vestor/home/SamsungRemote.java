package tv.vestor.home;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The Samsung TV's own remote, over the home network: the keys of its remote (power, volume, the
 * arrows, enter, back, home, menu, sources) and app launches, through the remote control channel
 * Tizen TVs since 2016 open to phones, a WebSocket on port 8002 (TLS) or 8001 (plain, older
 * sets). It is quick and has no quota, unlike SmartThings with its 12 commands a minute, so it is
 * what walks through the TV's menus from the phone.
 *
 * Pairing is once. The first connection makes the TV ask on screen whether to allow "Vestor".
 * Once allowed, the TV hands back a token that Vestor keeps and sends from then on, and the TV
 * lets it straight in while Access Notification is First Time Only. A token the TV later refuses
 * is forgotten, so the next connection asks again.
 *
 * Defensive:
 * - The TV is given by its address: four numbers in a private home range (10.x, 172.16-31.x,
 *   192.168.x). Names, public addresses, loopback and anything else are refused before a socket
 *   opens. Only ports 8001 and 8002 are dialled, and UDP 9 for Wake-on-LAN, which goes only to
 *   the TV and to the home network's broadcast addresses.
 * - The TV's certificate is self-signed, so no authority can vouch for it. The first one seen is
 *   remembered by its SHA-256, and any other is refused in the TLS handshake, before the request
 *   that carries the token is written. A TV software update can bring a new certificate: the
 *   owner then trusts the TV again, which also forgets the token, so an impostor never gets it.
 * - What the TV sends passes through {@link Ws} and {@link Json} with their caps. Dialling,
 *   the TLS handshake, the upgrade and the wait for Allow each have a deadline, and an idle
 *   channel is closed after 10 minutes. A channel quiet for 30 seconds is pinged before a key
 *   goes on it, so a TV that went to standby meanwhile costs a reconnection, not a lost key.
 * - Only the keys in {@link #KEYS} are sent, and app ids of a fixed shape.
 *
 * Every call but the getters blocks on the network, for up to 35 seconds while the TV asks to
 * allow Vestor, so callers use a thread of their own. Platform-free: storage and sockets are
 * handed in, for the desktop tests.
 */
final class SamsungRemote {
    interface Store {
        String get(String key);

        void put(String key, String value);
    }

    /** Opens the TCP connection, so tests can stand in for the TV. */
    interface Dial {
        Socket open(String ip, int port, int timeoutMs) throws IOException;
    }

    /** No TV set, closed, opening, waiting for Allow on the TV, ready, refused, certificate changed, not answering. */
    static final String NONE = "none", OFF = "off", CONNECTING = "connecting", ALLOW = "allow", CONNECTED = "connected",
            REFUSED = "refused", CHANGED = "changed", UNREACHABLE = "unreachable";
    static final int PLAIN = 8001, SECURE = 8002, WOL_PORT = 9;
    /** The name the TV shows in its question and its Device List. */
    static final String NAME = "Vestor";

    /** The keys Vestor sends: enough to reach every setting. Service and factory keys are left out. */
    static final String[] KEYS = {
        "KEY_POWER", "KEY_POWEROFF", "KEY_HOME", "KEY_MENU", "KEY_TOOLS", "KEY_INFO", "KEY_GUIDE", "KEY_SOURCE",
        "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_ENTER", "KEY_RETURN", "KEY_EXIT",
        "KEY_VOLUP", "KEY_VOLDOWN", "KEY_MUTE",
        "KEY_HDMI", "KEY_HDMI1", "KEY_HDMI2", "KEY_HDMI3", "KEY_HDMI4", "KEY_TV",
        "KEY_CHUP", "KEY_CHDOWN", "KEY_PRECH", "KEY_CH_LIST",
        "KEY_0", "KEY_1", "KEY_2", "KEY_3", "KEY_4", "KEY_5", "KEY_6", "KEY_7", "KEY_8", "KEY_9",
        "KEY_PLAY", "KEY_PAUSE", "KEY_STOP", "KEY_FF", "KEY_REWIND",
        "KEY_RED", "KEY_GREEN", "KEY_YELLOW", "KEY_BLUE", "KEY_MORE", "KEY_CAPTION", "KEY_AD", "KEY_PICTURE_SIZE",
        "KEY_SLEEP", "KEY_MULTI_VIEW"};
    private static final Set<String> KEY_SET = new HashSet<>(Arrays.asList(KEYS));

    static final String ADDRESS = "Vestor only talks to a TV at a home address, like 192.168.1.20.",
            NO_ADDRESS = "Type the TV's address first.",
            SAID_NO = "The TV said no to Vestor. On the TV: Settings, General, External Device Manager, Device Connection Manager, "
                    + "Device List. Set Vestor to Allow, then try again.",
            NO_ANSWER = "Nobody chose Allow on the TV in time. Try again, then choose Allow on the TV.",
            CERT = "The TV's security certificate changed. After a TV software update that is expected: choose Trust the TV "
                    + "again. If the TV was not updated, another device may be pretending to be it.",
            ODD = "The TV's answer made no sense.",
            GONE = "The TV stopped answering.",
            SLOW = "The TV took too long to answer.";

    private static final String IP = "tv_ip", PORT = "tv_port", TOKEN = "tv_token", PIN = "tv_pin", MAC = "tv_mac",
            MODEL = "tv_model", LABEL = "tv_label";
    private static final int CONNECT_MS = 4000, HANDSHAKE_MS = 8000, ALLOW_MS = 35000, REST_MS = 5000, REST_MAX = 16 * 1024,
            IDLE_MS = 10 * 60000, HOLD_MAX_MS = 5000, PONG_MS = 1500;

    static final Dial DIRECT = new Dial() {
        @Override
        public Socket open(String ip, int port, int timeoutMs) throws IOException {
            InetAddress a = address(ip);
            if (a == null) throw new IOException("not a home address");
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(a, port), timeoutMs);
                s.setTcpNoDelay(true);
            } catch (IOException | RuntimeException e) {
                close(s);
                throw e;
            }
            return s;
        }
    };

    /** Closes a connection still opening at its deadline, however slowly the bytes come. */
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tv-remote-deadline");
            t.setDaemon(true);
            return t;
        }
    });

    static {
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    private final Store store;
    private final Dial dial;
    private final SecureRandom random = new SecureRandom();
    /** The open channel, or null. Guarded by this. */
    private Ws ws;
    private volatile String state, error = "";
    private volatile long used;
    /** While pairing, when (System.nanoTime) the TV counts as asking for Allow, else 0. */
    private volatile long askingAt;
    /** Tests shorten them. */
    int allowMs = ALLOW_MS, handshakeMs = HANDSHAKE_MS, restMs = REST_MS, staleMs = 30000;

    SamsungRemote(Store store) {
        this(store, DIRECT);
    }

    SamsungRemote(Store store, Dial dial) {
        this.store = store;
        this.dial = dial;
        state = address(s(IP)) == null ? NONE : OFF;
    }

    /** What the TV says about itself at /api/v2/. */
    static final class Info {
        final String name, model, modelName, power, network, mac;
        final boolean tokenAuth;

        private Info(String name, String model, String modelName, String power, String network, String mac, boolean tokenAuth) {
            this.name = name;
            this.model = model;
            this.modelName = modelName;
            this.power = power;
            this.network = network;
            this.mac = mac;
            this.tokenAuth = tokenAuth;
        }

        /**
         * The TV's answer at /api/v2/: its name, model, "on" or "standby" (or "" when the firmware
         * does not say), wired or wireless, the MAC of its network card and whether its remote
         * channel wants a token (2018 and later).
         */
        static Info parse(String json) throws Samsung.Failed {
            Map<String, Object> top = parseObj(json);
            Map<String, Object> d = Json.obj(top, "device");
            String type = Json.str(d, "type", Json.str(top, "type", ""));
            boolean samsung = "Samsung SmartTV".equals(type) || "Tizen".equals(Json.str(d, "OS", ""));
            if (d == null || !samsung) throw new Samsung.Failed(502, "That address is not a Samsung TV.");
            String auth = Json.str(d, "TokenAuthSupport", "");
            String support = Json.str(top, "isSupport", "");
            if (auth.isEmpty() && support.length() < 4096) auth = Json.str(parseObj(support.trim()), "TokenAuthSupport", "");
            String name = Text.clean(Json.str(d, "name", Json.str(top, "name", "Samsung TV")), 60);
            String model = Json.str(d, "model", ""), modelName = Json.str(d, "modelName", "");
            String power = Json.str(d, "PowerState", ""), network = Json.str(d, "networkType", "");
            String mac = Json.str(d, "wifiMac", "").toUpperCase(Locale.US);
            return new Info(name.isEmpty() ? "Samsung TV" : name, model.matches("[A-Za-z0-9_ -]{1,40}") ? model : "",
                    modelName.matches("[A-Za-z0-9-]{1,32}") ? modelName : "", "on".equals(power) || "standby".equals(power) ? power : "",
                    "wired".equals(network) || "wireless".equals(network) ? network : "", magic(mac) != null ? mac : "",
                    "true".equals(auth));
        }
    }

    // ---- What the page shows (any thread, never waits on the TV) --------------------------------

    String state() {
        String s = state;
        long at = askingAt;
        return CONNECTING.equals(s) && at != 0 && System.nanoTime() - at >= 0 ? ALLOW : s;
    }

    String error() {
        return error;
    }

    String address() {
        return s(IP);
    }

    /** Whether the TV has let Vestor in and given it a token. */
    boolean paired() {
        return !s(TOKEN).isEmpty();
    }

    String label() {
        return s(LABEL);
    }

    String model() {
        return s(MODEL);
    }

    /** Whether Wake-on-LAN can be tried: the TV's MAC is known. */
    boolean canWake() {
        return magic(s(MAC)) != null;
    }

    int port() {
        return String.valueOf(PLAIN).equals(s(PORT)) ? PLAIN : SECURE;
    }

    private String s(String key) {
        String v = store.get(key);
        return v == null ? "" : v;
    }

    // ---- Addresses -----------------------------------------------------------------------------

    /**
     * {@code ip} as an address when it is four plain numbers in 10/8, 172.16/12 or 192.168/16,
     * and not the network or broadcast address of a /24. Else null, and no name is looked up.
     */
    static InetAddress address(String ip) {
        if (ip == null || ip.length() > 15) return null;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return null;
            int v = Integer.parseInt(parts[i]);
            if (v > 255) return null;
            b[i] = (byte) v;
        }
        if (b[3] == 0 || b[3] == (byte) 255) return null;
        try {
            InetAddress a = InetAddress.getByAddress(b);
            return Console.isPrivate(a) ? a : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Sets the TV's address. A new one closes the channel but keeps the token and the pinned
     * certificate: the same TV after its router gave it a new address is let straight in, and a
     * different device there is refused by the pin.
     */
    synchronized void setAddress(String ip) throws Samsung.Failed {
        String a = ip == null ? "" : ip.trim();
        if (address(a) == null) throw new Samsung.Failed(400, ADDRESS);
        if (a.equals(s(IP))) return;
        drop();
        store.put(IP, a);
        state = OFF;
        error = "";
    }

    private String target() throws Samsung.Failed {
        String ip = s(IP);
        if (ip.isEmpty()) throw new Samsung.Failed(409, NO_ADDRESS);
        if (address(ip) == null) throw new Samsung.Failed(400, ADDRESS);
        return ip;
    }

    // ---- The TV's description ------------------------------------------------------------------

    /**
     * Asks the TV about itself at http://&lt;tv&gt;:8001/api/v2/, which needs no pairing and also
     * answers in network standby. Keeps its name, model, MAC and which port its remote uses.
     */
    synchronized Info probe() throws Samsung.Failed {
        String ip = target();
        Info info = Info.parse(new String(rest(ip), StandardCharsets.UTF_8));
        store.put(LABEL, info.name);
        store.put(MODEL, info.modelName);
        if (!info.mac.isEmpty()) store.put(MAC, info.mac);
        String port = String.valueOf(info.tokenAuth ? SECURE : PLAIN);
        if (!port.equals(s(PORT))) {
            drop();
            store.put(PORT, port);
        }
        return info;
    }

    /** One HTTP/1.0 GET (so never chunked), at most 16 KB within 5 seconds. */
    private byte[] rest(String ip) throws Samsung.Failed {
        Socket s;
        try {
            s = dial.open(ip, PLAIN, CONNECT_MS);
        } catch (IOException | RuntimeException e) {
            throw unreachable(ip);
        }
        Cut cut = new Cut(s);
        ScheduledFuture<?> alarm = WATCHDOG.schedule(cut, restMs, TimeUnit.MILLISECONDS);
        try {
            long deadline = System.nanoTime() + restMs * 1000000L;
            s.setSoTimeout(restMs);
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/v2/ HTTP/1.0\r\nHost: " + ip + ":" + PLAIN + "\r\nAccept: application/json\r\nUser-Agent: " + Net.AGENT
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 4096);
            String status = Ws.line(in, deadline);
            if (!status.matches("HTTP/1\\.[01] 200( .*)?")) throw new Samsung.Failed(502, ODD);
            long length = -1;
            int lines = 0, bytes = 0;
            for (String h = Ws.line(in, deadline); !h.isEmpty(); h = Ws.line(in, deadline)) {
                bytes += h.length() + 2;
                int colon = h.indexOf(':');
                if (++lines > Ws.MAX_HEADERS || bytes > Ws.MAX_HEAD || colon <= 0) throw new Samsung.Failed(502, ODD);
                String name = h.substring(0, colon).trim().toLowerCase(Locale.US), value = h.substring(colon + 1).trim();
                if ("content-length".equals(name)) {
                    if (length >= 0 || !value.matches("[0-9]{1,9}")) throw new Samsung.Failed(502, ODD);
                    length = Long.parseLong(value);
                } else if ("transfer-encoding".equals(name)) {
                    throw new Samsung.Failed(502, ODD);
                }
            }
            if (length > REST_MAX) throw new Samsung.Failed(502, ODD);
            ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[4096];
            while (length < 0 || body.size() < length) {
                int want = length < 0 ? buf.length : (int) Math.min(buf.length, length - body.size());
                int n = in.read(buf, 0, want);
                if (n < 0) break;
                body.write(buf, 0, n);
                if (body.size() > REST_MAX || System.nanoTime() - deadline > 0) throw new Samsung.Failed(502, ODD);
            }
            if (length >= 0 && body.size() != length) throw new Samsung.Failed(502, ODD);
            return body.toByteArray();
        } catch (IOException | RuntimeException e) {
            // Dialling worked: what went wrong is the answer.
            throw new Samsung.Failed(cut.fired || e instanceof SocketTimeoutException ? 504 : 502, cut.fired
                    || e instanceof SocketTimeoutException ? SLOW : ODD);
        } finally {
            alarm.cancel(false);
            close(s);
        }
    }

    private Samsung.Failed unreachable(String ip) {
        state = UNREACHABLE;
        error = "The TV did not answer at " + ip + ". Is it on, and on the same Wi-Fi as Vestor?";
        return new Samsung.Failed(503, error);
    }

    // ---- The remote channel ----------------------------------------------------------------------

    private static final class Cut implements Runnable {
        private final Socket s;
        volatile boolean fired;

        Cut(Socket s) {
            this.s = s;
        }

        @Override
        public void run() {
            fired = true;
            close(s);
        }
    }

    /**
     * Opens the remote channel unless it is open. Without a token the TV asks on screen whether
     * to allow Vestor, and this waits for the answer, up to 35 seconds: {@link #state} is
     * {@link #ALLOW} meanwhile.
     */
    synchronized void connect() throws Samsung.Failed {
        if (ws != null && ws.open()) return;
        ws = null;
        String ip = target();
        if (CHANGED.equals(state)) throw new Samsung.Failed(409, CERT);
        int port = port();
        boolean secure = port == SECURE;
        String token = secure ? s(TOKEN) : "";
        state = CONNECTING;
        Socket raw;
        try {
            raw = dial.open(ip, port, CONNECT_MS);
        } catch (IOException | RuntimeException e) {
            throw unreachable(ip);
        }
        Cut cut = new Cut(raw);
        ScheduledFuture<?> alarm = WATCHDOG.schedule(cut, handshakeMs, TimeUnit.MILLISECONDS);
        Tofu trust = secure ? new Tofu(s(PIN)) : null;
        Ws w = null;
        boolean pairing = false;
        try {
            raw.setSoTimeout(handshakeMs);
            Socket sock = secure ? tls(raw, ip, trust) : raw;
            String path = "/api/v2/channels/samsung.remote.control?name=" + Base64.getEncoder().encodeToString(
                    NAME.getBytes(StandardCharsets.UTF_8)) + (validToken(token) ? "&token=" + token : "");
            w = Ws.open(sock, ip + ":" + port, path, handshakeMs, random);
            // The wait for Allow has its own deadline, however the TV fills it (pings, odd frames).
            alarm.cancel(false);
            pairing = true;
            alarm = WATCHDOG.schedule(cut, allowMs + 1000L, TimeUnit.MILLISECONDS);
            String got = pair(w, token.isEmpty());
            if (secure && validToken(got) && !got.equals(token)) store.put(TOKEN, got);
            if (secure && s(PIN).isEmpty() && trust.seen != null) store.put(PIN, trust.seen);
            ws = w;
            used = System.currentTimeMillis();
            state = CONNECTED;
            error = "";
            Thread t = new Thread(new Reader(w), "tv-remote");
            t.setDaemon(true);
            t.start();
        } catch (Samsung.Failed f) {
            close(w, raw);
            throw f;
        } catch (IOException | RuntimeException e) {
            close(w, raw);
            if (trust != null && trust.changed) {
                state = CHANGED;
                error = CERT;
                throw new Samsung.Failed(409, CERT);
            }
            state = OFF;
            if (pairing && cut.fired) {
                error = NO_ANSWER;
                throw new Samsung.Failed(408, NO_ANSWER);
            }
            error = cut.fired || e instanceof SocketTimeoutException ? SLOW : ODD;
            throw new Samsung.Failed(502, error);
        } finally {
            alarm.cancel(false);
        }
    }

    private static void close(Ws w, Socket raw) {
        if (w != null) w.close();
        close(raw);
    }

    private SSLSocket tls(Socket raw, String ip, Tofu trust) throws IOException {
        SSLContext ctx;
        try {
            // A new context each time: no session is resumed, so every handshake shows the certificate.
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {trust}, random);
        } catch (GeneralSecurityException e) {
            throw new IOException("no TLS", e);
        }
        SSLSocket t = (SSLSocket) ctx.getSocketFactory().createSocket(raw, ip, SECURE, true);
        t.setUseClientMode(true);
        t.startHandshake();
        return t;
    }

    /**
     * Waits for the TV to let Vestor in: ms.channel.connect, with a token on port 8002. Answers
     * the token, or "" when none came. Each read waits for the rest of the time allowed, so a
     * read never resumes after a timeout on the TLS socket.
     */
    private String pair(Ws w, boolean fresh) throws IOException, Samsung.Failed {
        long start = System.nanoTime(), deadline = start + allowMs * 1000000L;
        // A known token is let in at once: still waiting after 2 s means the TV is asking.
        askingAt = fresh ? start : start + 2000000000L;
        try {
            return pairing(w, deadline);
        } finally {
            askingAt = 0;
        }
    }

    private String pairing(Ws w, long deadline) throws IOException, Samsung.Failed {
        for (int messages = 0; ; ) {
            long left = (deadline - System.nanoTime()) / 1000000L;
            String text = null;
            if (left > 0) {
                w.timeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, left)));
                try {
                    text = w.read();
                } catch (SocketTimeoutException quiet) {
                    left = 0;
                }
            }
            if (left <= 0) {
                state = OFF;
                error = NO_ANSWER;
                throw new Samsung.Failed(408, NO_ANSWER);
            }
            if (text == null) {
                state = OFF;
                error = "The TV ended the connection before letting Vestor in.";
                throw new Samsung.Failed(502, error);
            }
            if (++messages > 50) throw new IOException("too much while pairing");
            Map<String, Object> m = parseObj(text);
            String event = Json.str(m, "event", "");
            if ("ms.channel.connect".equals(event)) return Json.str(Json.obj(m, "data"), "token", "");
            if ("ms.channel.unauthorized".equals(event) || "ms.error".equals(event) && authError(m)) throw refused();
            if ("ms.channel.timeOut".equals(event)) {
                state = OFF;
                error = NO_ANSWER;
                throw new Samsung.Failed(408, NO_ANSWER);
            }
            // Anything else (ed.edenTV.update, ms.voiceApp.hide) is news Vestor does not need.
        }
    }

    /** "No Authorized" and its spellings: the TV refused the token, without closing. */
    private static boolean authError(Map<String, Object> m) {
        String msg = Json.str(Json.obj(m, "data"), "message", "").toLowerCase(Locale.US);
        return msg.contains("authorized") && !msg.contains("ms.remote.control");
    }

    private Samsung.Failed refused() {
        store.put(TOKEN, "");
        state = REFUSED;
        error = SAID_NO;
        return new Samsung.Failed(403, SAID_NO);
    }

    private static boolean validToken(String t) {
        return t != null && t.matches("[A-Za-z0-9_-]{1,64}");
    }

    /** Reads what the TV sends while the channel is open: pings, refusals and the end. */
    private final class Reader implements Runnable {
        private final Ws w;

        Reader(Ws w) {
            this.w = w;
        }

        @Override
        public void run() {
            try {
                // One long read: a TLS socket is not asked to read on after a timeout unless the
                // channel is in use, and then a failure only costs a reconnection.
                w.timeout(IDLE_MS);
                while (true) {
                    String text;
                    try {
                        text = w.read();
                    } catch (SocketTimeoutException quiet) {
                        if (System.currentTimeMillis() - used >= IDLE_MS) break;
                        continue;
                    }
                    if (text == null) break;
                    heard(w, text);
                }
            } catch (IOException | RuntimeException e) {
                // closed below
            } finally {
                lost(w);
            }
        }
    }

    private void heard(Ws w, String text) {
        Map<String, Object> m = parseObj(text);
        String event = Json.str(m, "event", "");
        if ("ms.channel.unauthorized".equals(event) || "ms.error".equals(event) && authError(m)) {
            synchronized (this) {
                refused();
                w.close();
            }
        } else if ("ms.error".equals(event)) {
            error = "The TV said: " + Text.clean(Json.str(Json.obj(m, "data"), "message", "an error"), 80);
        } else if ("ed.apps.launch".equals(event) && !"200".equals(Json.str(m, "data", "200"))) {
            error = "The TV could not open that app.";
        }
    }

    private synchronized void lost(Ws w) {
        w.close();
        if (ws == w) {
            ws = null;
            if (CONNECTED.equals(state)) state = OFF;
        }
    }

    private void drop() {
        if (ws != null) {
            ws.close();
            ws = null;
        }
    }

    // ---- Keys and apps -----------------------------------------------------------------------

    /** Presses and lets go of one key of the TV's remote, opening the channel if needed. */
    synchronized void key(String key) throws Samsung.Failed {
        if (key == null || !KEY_SET.contains(key)) throw new Samsung.Failed(400, "key");
        send(keyMessage("Click", key));
    }

    /** Holds a key down for {@code ms} (at most 5 s), as a long press on the remote. */
    synchronized void hold(String key, int ms) throws Samsung.Failed {
        if (key == null || !KEY_SET.contains(key)) throw new Samsung.Failed(400, "key");
        send(keyMessage("Press", key));
        try {
            Thread.sleep(Math.max(100, Math.min(HOLD_MAX_MS, ms)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            send(keyMessage("Release", key));
        }
    }

    /** The Samsung's built-in browser, which takes the address to open as its launch data. */
    static final String BROWSER = "org.tizen.browser";

    /** Opens an app by its Tizen id (YouTube is 111299001912), as a deep link or a native launch. */
    synchronized void launch(String appId, boolean deepLink) throws Samsung.Failed {
        launch(appId, deepLink, "");
    }

    /**
     * The same, with launch data: for the browser, the address to open. Only Vestor's own page on
     * the home network is ever sent this way.
     */
    synchronized void launch(String appId, boolean deepLink, String meta) throws Samsung.Failed {
        if (appId == null || !appId.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) throw new Samsung.Failed(400, "app");
        if (meta == null || meta.length() > 200 || !(meta.isEmpty() || meta.matches("http://[0-9.]{7,15}:[0-9]{2,5}/[a-z]{1,16}")))
            throw new Samsung.Failed(400, "meta");
        send(new Jw().obj().put("method", "ms.channel.emit").key("params").obj().put("event", "ed.apps.launch").put("to", "host")
                .key("data").obj().put("appId", appId).put("action_type", deepLink ? "DEEP_LINK" : "NATIVE_LAUNCH").put("metaTag", meta)
                .end().end().end().toString());
    }

    static String keyMessage(String cmd, String key) {
        return new Jw().obj().put("method", "ms.remote.control").key("params").obj().put("Cmd", cmd).put("DataOfCmd", key)
                .put("Option", "false").put("TypeOfRemote", "SendRemoteKey").end().end().toString();
    }

    /**
     * Sends on the open channel, or on a fresh one when the TV dropped the last or no longer
     * answers a ping.
     */
    private void send(String json) throws Samsung.Failed {
        for (int attempt = 0; ; attempt++) {
            connect();
            Ws w = ws;
            try {
                if (w.quietMs() >= staleMs && !alive(w)) throw new IOException("no pong");
                w.send(json);
                used = System.currentTimeMillis();
                return;
            } catch (IOException e) {
                drop();
                state = OFF;
                if (attempt > 0) {
                    error = GONE;
                    throw new Samsung.Failed(503, GONE);
                }
            }
        }
    }

    /** Pings and waits up to 1.5 s for anything back, which the reader thread takes in. */
    private static boolean alive(Ws w) throws IOException {
        long sent = System.nanoTime();
        w.ping();
        while (System.nanoTime() - sent < PONG_MS * 1000000L) {
            if (w.heardAt() - sent >= 0) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Closes the channel. The TV, the token and the pin are kept. */
    synchronized void close() {
        drop();
        state = address(s(IP)) == null ? NONE : OFF;
    }

    /** Forgets the TV: its address, token, pinned certificate and what it said about itself. */
    synchronized void forget() {
        drop();
        for (String k : new String[] {IP, PORT, TOKEN, PIN, MAC, MODEL, LABEL}) store.put(k, "");
        state = NONE;
        error = "";
    }

    /**
     * The owner says the TV's new certificate is fine: the next connection pins whichever it
     * shows. The token goes too, so the TV asks to allow Vestor once more and an impostor that
     * slipped in never sees it.
     */
    synchronized void trustAgain() {
        drop();
        store.put(PIN, "");
        store.put(TOKEN, "");
        state = address(s(IP)) == null ? NONE : OFF;
        error = "";
    }

    // ---- Waking the TV ---------------------------------------------------------------------------

    /**
     * Wake-on-LAN to the MAC the TV reported, three times to the TV and to each home network's
     * broadcast address. Works when Power On with Mobile is on (Settings, General, Network,
     * Expert Settings), more surely on a wired TV than on Wi-Fi.
     */
    synchronized void wake() throws Samsung.Failed {
        byte[] packet = magic(s(MAC));
        if (packet == null) throw new Samsung.Failed(409, "Vestor has not read the TV's network card yet. Ask the TV while it is on.");
        if (send(packet, targets(s(IP)), WOL_PORT) == 0) throw new Samsung.Failed(503, "Vestor could not send the wake-up call.");
    }

    /** The magic packet for {@code mac} (six bytes of FF, then the MAC 16 times), or null for no usable MAC. */
    static byte[] magic(String mac) {
        if (mac == null || !mac.matches("[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}")) return null;
        byte[] m = new byte[6];
        int or = 0;
        for (int i = 0; i < 6; i++) {
            m[i] = (byte) Integer.parseInt(mac.substring(i * 3, i * 3 + 2), 16);
            or |= m[i] & 0xFF;
        }
        if (or == 0 || (m[0] & 1) != 0) return null;
        byte[] p = new byte[6 + 16 * 6];
        Arrays.fill(p, 0, 6, (byte) 0xFF);
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, p, 6 + i * 6, 6);
        return p;
    }

    /** Where a wake-up goes: the local broadcast, each private network's broadcast, and the TV. */
    static List<InetAddress> targets(String ip) {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByAddress(new byte[] {-1, -1, -1, -1}));
        } catch (UnknownHostException ignored) {
            // four bytes are always an address
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    boolean home = b instanceof Inet4Address && Console.isPrivate(ia.getAddress()) && Console.isPrivate(b);
                    if (home && !out.contains(b)) out.add(b);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // the local broadcast alone, then
        }
        InetAddress tv = address(ip);
        if (tv != null && !out.contains(tv)) out.add(tv);
        return out;
    }

    /** Sends {@code packet} three times to each address. Answers how many went out. */
    static int send(byte[] packet, List<InetAddress> to, int port) {
        int sent = 0;
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            for (int round = 0; round < 3; round++) {
                for (InetAddress a : to) {
                    try {
                        s.send(new DatagramPacket(packet, packet.length, a, port));
                        sent++;
                    } catch (IOException | RuntimeException ignored) {
                        // that network refused it: the others may not
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // no socket: nothing went out
        } finally {
            if (s != null) s.close();
        }
        return sent;
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private static Map<String, Object> parseObj(String json) {
        try {
            return Json.obj(Json.parse(json));
        } catch (IllegalArgumentException | StackOverflowError e) {
            return null;
        }
    }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder h = new StringBuilder(64);
            for (byte x : d) h.append(Character.forDigit(x >> 4 & 15, 16)).append(Character.forDigit(x & 15, 16));
            return h.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void close(Socket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /**
     * Trusts one certificate: the pinned one, or with no pin whichever the TV shows, which the
     * caller pins once the TV has let Vestor in. Names and dates are not checked: the TV's
     * certificate is self-signed for "SmartViewSDK", so the pin is the whole of the trust.
     */
    private static final class Tofu extends X509ExtendedTrustManager {
        private final String pinned;
        volatile String seen;
        volatile boolean changed;

        Tofu(String pinned) {
            this.pinned = pinned;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || chain[0] == null) throw new CertificateException("no certificate");
            String sha = sha256(chain[0].getEncoded());
            seen = sha;
            byte[] got = sha.getBytes(StandardCharsets.US_ASCII), want = pinned.getBytes(StandardCharsets.US_ASCII);
            if (!pinned.isEmpty() && !MessageDigest.isEqual(got, want)) {
                changed = true;
                throw new CertificateException("certificate changed");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
