package tv.vestor.home;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * The Samsung TV's own remote over the home network ({@link SamsungRemote} and {@link Ws}) and
 * the rest of SmartThings ({@link Samsung}), against stand-ins: a fake TV, plain and TLS with two
 * self-signed certificates made by keytool, that pairs, refuses, stays silent, changes its
 * certificate and sends every kind of broken head and frame, and a fake SmartThings for power,
 * volume, mute, inputs, sound modes, apps and cloud keys. About 15 seconds.
 *
 *   java -cp <classes> tv.vestor.home.SamsungRemoteTest
 */
public final class SamsungRemoteTest {
    private SamsungRemoteTest() {}

    private static int failures, checks;
    static final String TV = "192.168.1.20";
    static final String KEY_UP = "{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"Click\",\"DataOfCmd\":\"KEY_UP\",\"Option\":\"false\","
            + "\"TypeOfRemote\":\"SendRemoteKey\"}}";
    static final String TU7000 = "{\"device\":{\"FrameTVSupport\":\"false\",\"GamePadSupport\":\"true\",\"ImeSyncedSupport\":\"true\","
            + "\"Language\":\"en_US\",\"OS\":\"Tizen\",\"PowerState\":\"on\",\"TokenAuthSupport\":\"true\",\"VoiceSupport\":\"false\","
            + "\"countryCode\":\"US\",\"description\":\"Samsung DTV RCR\",\"developerIP\":\"0.0.0.0\",\"developerMode\":\"0\","
            + "\"duid\":\"uuid:0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0\",\"firmwareVersion\":\"Unknown\",\"id\":\"uuid:0f1e2d3c\",\"ip\":\""
            + TV + "\",\"model\":\"20_NIKEM_UHD\",\"modelName\":\"UN43TU7000FXZA\",\"name\":\"[TV] Samsung 7 Series (43)\","
            + "\"networkType\":\"wireless\",\"resolution\":\"3840x2160\",\"smartHubAgreement\":\"true\",\"type\":\"Samsung SmartTV\","
            + "\"udn\":\"uuid:0f1e2d3c\",\"wifiMac\":\"8c:c8:cd:12:34:56\"},\"id\":\"uuid:0f1e2d3c\",\"isSupport\":\"{\\\"remote_available"
            + "\\\":\\\"true\\\"}\",\"name\":\"[TV] Samsung 7 Series (43)\",\"remote\":\"1.0\",\"type\":\"Samsung SmartTV\","
            + "\"uri\":\"http://" + TV + ":8001/api/v2/\",\"version\":\"2.0.25\"}";

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vestor-tv");
        try {
            Certs a = certs(dir, "a"), b = certs(dir, "b");
            addresses();
            FakeTv tv = new FakeTv(a, b);
            try {
                probe(tv);
                pairing(tv, a, b);
                refusals(tv);
                channel(tv);
                hostileFrames(tv);
                hostileHeads(tv);
                plain(tv);
            } finally {
                tv.stop();
            }
            wol();
            cloud();
            cloudHostile();
        } finally {
            for (File f : dir.toFile().listFiles()) f.delete();
            dir.toFile().delete();
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void yes(String what, boolean ok) {
        eq(what, true, ok);
    }

    /** Waits up to {@code ms} for {@code c}. */
    private static boolean await(BooleanSupplier c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            sleep(10);
        }
        return c.getAsBoolean();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    interface Step {
        void run() throws Samsung.Failed;
    }

    private static int status(Step s) {
        try {
            s.run();
            return 0;
        } catch (Samsung.Failed f) {
            return f.status;
        }
    }

    static final class Mem implements SamsungRemote.Store, Samsung.Store {
        final Map<String, String> m = Collections.synchronizedMap(new HashMap<String, String>());

        @Override
        public String get(String key) {
            return m.get(key);
        }

        @Override
        public void put(String key, String value) {
            m.put(key, value);
        }
    }

    // ---- Certificates ----------------------------------------------------------------------

    static final class Certs {
        final SSLContext server;
        final String sha;

        Certs(SSLContext server, String sha) {
            this.server = server;
            this.sha = sha;
        }
    }

    /** A self-signed certificate like the TV's, made by keytool, and a server context for it. */
    static Certs certs(Path dir, String name) throws Exception {
        File store = dir.resolve(name + ".p12").toFile();
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "tv", "-keyalg", "RSA", "-keysize", "2048", "-sigalg",
                "SHA256withRSA", "-dname", "CN=SmartViewSDK", "-validity", "3650", "-keystore", store.getPath(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit", "-noprompt").redirectErrorStream(true).start();
        byte[] out = readAll(p.getInputStream());
        if (p.waitFor() != 0) throw new IOException("keytool: " + new String(out, StandardCharsets.UTF_8));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        X509Certificate cert = (X509Certificate) ks.getCertificate("tv");
        return new Certs(ctx, SamsungRemote.sha256(cert.getEncoded()));
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ---- A fake TV ---------------------------------------------------------------------------

    /** What the fake TV answers to an upgrade request, given the client's key. */
    interface Head {
        byte[] answer(String key);
    }

    static final Head GOOD = key -> ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + Ws.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

    /**
     * Port 8001 (REST and plain WebSocket) and port 8002 with certificate A or B. Pairs like a TV:
     * a known token is let in, an unknown one waits for "Allow" and gets a new token.
     */
    static final class FakeTv {
        final ServerSocket plain;
        final SSLServerSocket secureA, secureB;
        volatile boolean useB, stopped;
        // Behaviour
        volatile String rest = TU7000, mode = "allow";
        volatile byte[] restRaw;
        volatile long promptMs = 300, trickleMs;
        volatile Head head = GOOD;
        volatile byte[] after;
        volatile boolean refuseKeys, pingAfterConnect, closeAfterConnect, numberToken, rotate, pingForever, deaf;
        volatile String issueToken;
        volatile byte[] afterConnect;
        final Set<String> known = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger issued = new AtomicInteger();
        // What it saw
        final List<String> targets = Collections.synchronizedList(new ArrayList<String>());
        final List<String> hosts = Collections.synchronizedList(new ArrayList<String>());
        final List<String> restLines = Collections.synchronizedList(new ArrayList<String>());
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        final List<String> pongs = Collections.synchronizedList(new ArrayList<String>());
        final List<Integer> closes = Collections.synchronizedList(new ArrayList<Integer>());
        final Set<String> masks = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger pings = new AtomicInteger();
        final List<String> dialed = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean unmasked;

        FakeTv(Certs a, Certs b) throws IOException {
            InetAddress lo = InetAddress.getByName("127.0.0.1");
            plain = new ServerSocket(0, 50, lo);
            secureA = (SSLServerSocket) a.server.getServerSocketFactory().createServerSocket(0, 50, lo);
            secureB = (SSLServerSocket) b.server.getServerSocketFactory().createServerSocket(0, 50, lo);
            accept(plain, false);
            accept(secureA, true);
            accept(secureB, true);
        }

        /** Like a TV just switched on: no tokens known, default behaviour. */
        void reset() {
            useB = false;
            rest = TU7000;
            restRaw = null;
            mode = "allow";
            promptMs = 300;
            trickleMs = 0;
            head = GOOD;
            after = null;
            afterConnect = null;
            refuseKeys = pingAfterConnect = closeAfterConnect = numberToken = rotate = pingForever = deaf = false;
            issueToken = null;
            issued.set(0);
            known.clear();
            targets.clear();
            hosts.clear();
            restLines.clear();
            got.clear();
            pongs.clear();
            closes.clear();
            masks.clear();
            pings.set(0);
            dialed.clear();
            unmasked = false;
        }

        /** Dials the fake for 192.168.1.20, and counts every attempt. */
        final SamsungRemote.Dial dial = new SamsungRemote.Dial() {
            @Override
            public Socket open(String ip, int port, int timeoutMs) throws IOException {
                dialed.add(ip + ":" + port);
                if (!TV.equals(ip)) throw new IOException("no such TV");
                int local = port == 8001 ? plain.getLocalPort() : port == 8002 ? (useB ? secureB : secureA).getLocalPort() : -1;
                if (local < 0) throw new IOException("closed port");
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", local), timeoutMs);
                return s;
            }
        };

        SamsungRemote remote(Mem mem) {
            SamsungRemote r = new SamsungRemote(mem, dial);
            r.allowMs = 3000;
            r.handshakeMs = 2000;
            r.restMs = 1500;
            return r;
        }

        void stop() throws IOException {
            stopped = true;
            plain.close();
            secureA.close();
            secureB.close();
        }

        private void accept(final ServerSocket ss, final boolean secure) {
            Thread t = new Thread(() -> {
                while (!stopped) {
                    try {
                        final Socket c = ss.accept();
                        Thread h = new Thread(() -> {
                            try {
                                serve(c, secure);
                            } catch (Exception ignored) {
                                // the client went away or the TLS handshake failed
                            } finally {
                                try {
                                    c.close();
                                } catch (IOException ignored) {
                                    // closed
                                }
                            }
                        });
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        if (stopped) return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        String issue() {
            String t = issueToken != null ? issueToken : String.valueOf(48151623 + 1000 * issued.incrementAndGet());
            known.add(t);
            return t;
        }

        private void serve(Socket c, boolean secure) throws Exception {
            c.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            String request = line(in);
            Map<String, String> h = new HashMap<>();
            for (String l = line(in); !l.isEmpty(); l = line(in)) {
                int colon = l.indexOf(':');
                h.put(l.substring(0, colon).trim().toLowerCase(Locale.US), l.substring(colon + 1).trim());
            }
            String target = request.split(" ")[1];
            if (!secure && "/api/v2/".equals(target)) {
                restLines.add(request + "|" + h.get("host"));
                if (trickleMs > 0) {
                    out.write("HTTP/1.0 200 OK\r\n".getBytes(StandardCharsets.US_ASCII));
                    while (true) {
                        out.write('X');
                        out.flush();
                        sleep(trickleMs);
                    }
                }
                byte[] body = rest.getBytes(StandardCharsets.UTF_8);
                out.write(restRaw != null ? restRaw : ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                        + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if (restRaw == null) out.write(body);
                out.flush();
                return;
            }
            targets.add(target);
            hosts.add(h.get("host"));
            if (trickleMs > 0) {
                out.write("HTTP/1.1 101 Switching Protocols\r\n".getBytes(StandardCharsets.US_ASCII));
                while (true) {
                    out.write("X-Slow: a\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    sleep(trickleMs);
                }
            }
            out.write(head.answer(h.get("sec-websocket-key")));
            out.flush();
            if (after != null) {
                out.write(after);
                out.flush();
            }
            String token = query(target, "token");
            if (pingForever) {
                while (true) {
                    frame(out, 0x89, new byte[0]);
                    sleep(200);
                }
            }
            if (!secure) {
                send(out, "{\"data\":{\"clients\":[],\"id\":\"x\"},\"event\":\"ms.channel.connect\"}");
            } else if (token != null && known.contains(token)) {
                send(out, connect(rotate ? issue() : token));
            } else if ("allow".equals(mode)) {
                sleep(promptMs);
                send(out, connect(issue()));
            } else if ("deny".equals(mode)) {
                send(out, "{\"event\":\"ms.channel.unauthorized\",\"from\":\"host\"}");
            } else if ("timeout".equals(mode)) {
                send(out, "{\"event\":\"ms.channel.timeOut\",\"from\":\"host\"}");
            }
            if (afterConnect != null) {
                out.write(afterConnect);
                out.flush();
            }
            if (pingAfterConnect) frame(out, 0x89, "hi".getBytes(StandardCharsets.UTF_8));
            if (closeAfterConnect) frame(out, 0x88, new byte[] {0x03, (byte) 0xE8});
            while (true) {
                int b0 = in.read();
                if (b0 < 0) return;
                int b1 = in.read();
                if ((b1 & 0x80) == 0) unmasked = true;
                long len = b1 & 0x7F;
                if (len == 126) len = in.read() << 8 | in.read();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = len << 8 | in.read();
                }
                byte[] mask = new byte[4];
                readFully(in, mask);
                masks.add(Arrays.toString(mask));
                byte[] p = new byte[(int) len];
                readFully(in, p);
                for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                int op = b0 & 0x0F;
                if (op == 0x1) {
                    got.add(new String(p, StandardCharsets.UTF_8));
                    if (refuseKeys) send(out, "{\"data\":{\"message\":\"No Authorized\"},\"event\":\"ms.error\"}");
                } else if (op == 0xA) {
                    pongs.add(new String(p, StandardCharsets.UTF_8));
                } else if (op == 0x9) {
                    pings.incrementAndGet();
                    if (!deaf) frame(out, 0x8A, p);
                } else if (op == 0x8) {
                    closes.add(p.length >= 2 ? (p[0] & 0xFF) << 8 | (p[1] & 0xFF) : 1005);
                    try {
                        frame(out, 0x88, p);
                    } catch (IOException ignored) {
                        // the client already closed
                    }
                    return;
                }
            }
        }

        String connect(String token) {
            return numberToken ? "{\"data\":{\"clients\":[],\"id\":\"x\",\"token\":" + token + "},\"event\":\"ms.channel.connect\"}"
                    : "{\"data\":{\"clients\":[{\"attributes\":{\"name\":\"VmVzdG9y\"},\"isHost\":false}],\"id\":\"x\",\"token\":\""
                    + token + "\"},\"event\":\"ms.channel.connect\"}";
        }
    }

    static String query(String target, String name) {
        int q = target.indexOf('?');
        if (q < 0) return null;
        for (String kv : target.substring(q + 1).split("&")) if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        return null;
    }

    static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new EOFException();
            if (c != '\r') b.append((char) c);
        }
        return b.toString();
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        for (int off = 0, n; off < b.length; off += n) {
            n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException();
        }
    }

    /** An unmasked server frame with first byte {@code b0}. */
    static byte[] frameBytes(int b0, byte[] p) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(b0);
        if (p.length < 126) {
            f.write(p.length);
        } else if (p.length < 65536) {
            f.write(126);
            f.write(p.length >> 8);
            f.write(p.length);
        } else {
            f.write(127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) p.length >> (8 * i)));
        }
        f.write(p, 0, p.length);
        return f.toByteArray();
    }

    static void frame(OutputStream out, int b0, byte[] p) throws IOException {
        out.write(frameBytes(b0, p));
        out.flush();
    }

    static void send(OutputStream out, String text) throws IOException {
        frame(out, 0x81, text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] text(String s) {
        return frameBytes(0x81, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    // ---- Addresses ---------------------------------------------------------------------------

    private static void addresses() {
        for (String ok : new String[] {"192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.0.1"}) {
            yes("home address " + ok, SamsungRemote.address(ok) != null);
        }
        for (String bad : new String[] {"172.32.0.1", "172.15.0.1", "8.8.8.8", "127.0.0.1", "169.254.1.1", "0.0.0.0", "255.255.255.255",
            "192.168.1.0", "192.168.1.255", "100.64.0.1", "localhost", "tv.local", "192.168.1.20:8002", " 192.168.1.20",
            "192.168.01.20", "192.168.1", "192.168.1.20.5", "192.168.1.256", "::1", "fd00::1", "", "1e2.168.1.1", "192.168.1.20\n",
            "0x0a.0.0.1", "192.168.1.-1", "+192.168.1.2", "192.168.1.2/24", "%31%39%32.168.1.2"}) {
            eq("refused " + bad.replace("\n", "\\n"), null, SamsungRemote.address(bad));
        }
        eq("null refused", null, SamsungRemote.address(null));
        Mem mem = new Mem();
        final List<String> dialed = new ArrayList<>();
        final SamsungRemote r = new SamsungRemote(mem, (ip, port, ms) -> {
            dialed.add(ip);
            throw new IOException("no");
        });
        eq("no TV yet", SamsungRemote.NONE, r.state());
        eq("connect without an address", 409, status(r::connect));
        eq("public address refused", 400, status(() -> r.setAddress("8.8.8.8")));
        eq("loopback refused", 400, status(() -> r.setAddress("127.0.0.1")));
        eq("name refused", 400, status(() -> r.setAddress("samsung.local")));
        eq("nothing stored", null, mem.get("tv_ip"));
        eq("nothing dialled", 0, dialed.size());
        mem.put("tv_ip", "8.8.8.8");
        eq("a bad stored address is still refused", 400, status(r::connect));
        eq("still nothing dialled", 0, dialed.size());
        eq("set a home address", 0, status(() -> r.setAddress(" 192.168.1.20 ")));
        eq("trimmed", TV, r.address());
        eq("unreachable", 503, status(r::connect));
        eq("says unreachable", SamsungRemote.UNREACHABLE, r.state());
        yes("names the address", r.error().contains(TV));
        eq("dialled the TV only", Arrays.asList(TV), dialed);
        eq("wake without a MAC", 409, status(r::wake));
    }

    // ---- The TV's description ----------------------------------------------------------------

    private static void probe(FakeTv tv) {
        tv.reset();
        Mem mem = new Mem();
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        SamsungRemote.Info[] info = new SamsungRemote.Info[1];
        eq("probe", 0, status(() -> info[0] = r.probe()));
        eq("asked HTTP/1.0 with the Host", "GET /api/v2/ HTTP/1.0|" + TV + ":8001", tv.restLines.get(0));
        eq("name", "[TV] Samsung 7 Series (43)", info[0].name);
        eq("model", "20_NIKEM_UHD", info[0].model);
        eq("model name", "UN43TU7000FXZA", info[0].modelName);
        eq("power", "on", info[0].power);
        eq("network", "wireless", info[0].network);
        eq("mac", "8C:C8:CD:12:34:56", info[0].mac);
        eq("token auth", true, info[0].tokenAuth);
        eq("secure port chosen", 8002, r.port());
        eq("label kept", "[TV] Samsung 7 Series (43)", r.label());
        eq("model kept", "UN43TU7000FXZA", r.model());
        yes("can wake", r.canWake());

        tv.rest = TU7000.replace("\"TokenAuthSupport\":\"true\",", "").replace("{\\\"remote_available", "{\\\"TokenAuthSupport\\\":"
                + "\\\"true\\\",\\\"remote_available").replace("\"PowerState\":\"on\"", "\"PowerState\":\"standby\"");
        eq("probe, token auth in isSupport", 0, status(() -> info[0] = r.probe()));
        eq("isSupport read", true, info[0].tokenAuth);
        eq("standby", "standby", info[0].power);

        tv.rest = TU7000.replace("\"TokenAuthSupport\":\"true\",", "").replace("\"PowerState\":\"on\",", "");
        eq("older TV", 0, status(() -> info[0] = r.probe()));
        eq("no power state", "", info[0].power);
        eq("plain port chosen", 8001, r.port());

        String odd = "{\"device\":{\"type\":\"Samsung SmartTV\",\"name\":\"" + new String(new char[300]).replace('\0', 'n')
                + "\",\"model\":\"<b>\",\"modelName\":\"UN43\\nTU\",\"wifiMac\":\"01:00:5e:00:00:01\",\"PowerState\":\"<on>\","
                + "\"networkType\":\"radio\"}}";
        tv.rest = odd;
        eq("odd fields", 0, status(() -> info[0] = r.probe()));
        eq("name capped", 60, info[0].name.length());
        eq("odd model dropped", "", info[0].model);
        eq("odd model name dropped", "", info[0].modelName);
        eq("multicast MAC dropped", "", info[0].mac);
        eq("odd power dropped", "", info[0].power);
        eq("odd network dropped", "", info[0].network);

        for (String bad : new String[] {"{\"device\":{\"type\":\"Router\"}}", "not json", "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
            + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[", "{}", ""}) {
            tv.rest = bad;
            eq("not a Samsung TV: " + bad.substring(0, Math.min(12, bad.length())), 502, status(r::probe));
        }
        tv.rest = TU7000;
        String[][] raws = {
            {"too long declared", "HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n{}"},
            {"chunked", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n"},
            {"two lengths", "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}"},
            {"not 200", "HTTP/1.1 302 Found\r\nLocation: http://8.8.8.8/\r\nContent-Length: 0\r\n\r\n"},
            {"garbage status", "SSH-2.0-OpenSSH\r\n\r\n"},
            {"bare CR", "HTTP/1.1 200 OK\rX: y\r\n\r\n{}"},
            {"short body", "HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\n{}"},
            {"binary head", "HTTP/1.1 200 OK\r\nX: \u0001\r\n\r\n{}"}};
        for (String[] raw : raws) {
            tv.restRaw = raw[1].getBytes(StandardCharsets.ISO_8859_1);
            yes("refused: " + raw[0], status(r::probe) >= 500);
        }
        StringBuilder big = new StringBuilder("HTTP/1.0 200 OK\r\n\r\n{\"device\":{\"type\":\"Samsung SmartTV\",\"x\":\"");
        for (int i = 0; i < 20000; i++) big.append('a');
        tv.restRaw = big.append("\"}}").toString().getBytes(StandardCharsets.US_ASCII);
        eq("body over 16 KB without a length", 502, status(r::probe));
        tv.restRaw = null;
        tv.trickleMs = 50;
        long t0 = System.currentTimeMillis();
        eq("dripping answer cut", 504, status(r::probe));
        yes("cut at the deadline", System.currentTimeMillis() - t0 < 3000);
        tv.trickleMs = 0;
    }

    // ---- Pairing, keys and the certificate -------------------------------------------------

    private static void pairing(FakeTv tv, Certs a, Certs b) throws Exception {
        tv.reset();
        tv.numberToken = true;
        tv.promptMs = 600;
        Mem mem = new Mem();
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        final int[] result = {-1};
        Thread t = new Thread(() -> result[0] = status(r::connect));
        t.start();
        yes("asks for Allow", await(() -> SamsungRemote.ALLOW.equals(r.state()), 3000));
        t.join(5000);
        eq("paired", 0, result[0]);
        eq("connected", SamsungRemote.CONNECTED, r.state());
        eq("first request: name, no token", "/api/v2/channels/samsung.remote.control?name=VmVzdG9y", tv.targets.get(0));
        eq("host header", TV + ":8002", tv.hosts.get(0));
        eq("number token kept as text", "48152623", mem.get("tv_token"));
        yes("paired()", r.paired());
        eq("certificate pinned", a.sha, mem.get("tv_pin"));

        eq("key", 0, status(() -> r.key("KEY_UP")));
        yes("key arrives", await(() -> tv.got.size() == 1, 2000));
        eq("key JSON", KEY_UP, tv.got.get(0));
        for (String k : new String[] {"KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_ENTER", "KEY_RETURN", "KEY_MENU", "KEY_HOME"}) {
            eq("key " + k, 0, status(() -> r.key(k)));
        }
        yes("all keys arrive", await(() -> tv.got.size() == 8, 2000));
        eq("one connection for all", 1, tv.targets.size());
        eq("every frame masked", false, tv.unmasked);
        yes("fresh mask per frame", tv.masks.size() >= 7);
        eq("hold", 0, status(() -> r.hold("KEY_POWER", 150)));
        yes("press and release arrive", await(() -> tv.got.size() == 10, 2000));
        yes("press", tv.got.get(8).contains("\"Cmd\":\"Press\",\"DataOfCmd\":\"KEY_POWER\""));
        yes("release", tv.got.get(9).contains("\"Cmd\":\"Release\",\"DataOfCmd\":\"KEY_POWER\""));
        eq("launch", 0, status(() -> r.launch("111299001912", true)));
        yes("launch arrives", await(() -> tv.got.size() == 11, 2000));
        eq("launch JSON", "{\"method\":\"ms.channel.emit\",\"params\":{\"event\":\"ed.apps.launch\",\"to\":\"host\",\"data\":{\"appId\":"
                + "\"111299001912\",\"action_type\":\"DEEP_LINK\",\"metaTag\":\"\"}}}", tv.got.get(10));
        eq("native launch", 0, status(() -> r.launch("org.tizen.browser", false)));
        yes("native launch arrives", await(() -> tv.got.size() == 12 && tv.got.get(11).contains("NATIVE_LAUNCH"), 2000));
        for (String bad : new String[] {"KEY_FACTORY", "KEY_UP\"}", "key_up", "KEY_AUTO_ARC_RESET", "", "KEY_UP "}) {
            eq("key refused: " + bad, 400, status(() -> r.key(bad)));
        }
        eq("null key", 400, status(() -> r.key(null)));
        eq("hold refused", 400, status(() -> r.hold("KEY_FACTORY", 100)));
        for (String bad : new String[] {"../x", "a", "app\"}", "x y", ""}) eq("app refused: " + bad, 400, status(() -> r.launch(bad, true)));
        sleep(100);
        eq("nothing refused was sent", 12, tv.got.size());

        // Again later: the token goes along and the TV lets Vestor straight in, with a new token.
        r.close();
        eq("closed", SamsungRemote.OFF, r.state());
        yes("goodbye 1000", await(() -> tv.closes.contains(1000), 2000));
        tv.rotate = true;
        long t0 = System.currentTimeMillis();
        eq("reconnect", 0, status(r::connect));
        yes("no wait for Allow", System.currentTimeMillis() - t0 < 1500);
        eq("token sent", "/api/v2/channels/samsung.remote.control?name=VmVzdG9y&token=48152623", tv.targets.get(1));
        eq("new token kept", "48153623", mem.get("tv_token"));
        eq("same pin", a.sha, mem.get("tv_pin"));

        // A hostile token is not kept.
        r.close();
        tv.numberToken = false;
        tv.known.clear();
        tv.issueToken = "abc\\r\\nX-Evil: 1";
        eq("pairs with an odd token", 0, status(r::connect));
        eq("odd token not kept", "48153623", mem.get("tv_token"));
        tv.issueToken = null;

        // The certificate changes: refused in the handshake, the token never leaves.
        r.close();
        tv.useB = true;
        int requests = tv.targets.size();
        eq("certificate changed", 409, status(() -> r.key("KEY_UP")));
        eq("says changed", SamsungRemote.CHANGED, r.state());
        eq("explains", SamsungRemote.CERT, r.error());
        eq("no request reached the impostor", requests, tv.targets.size());
        int dials = tv.dialed.size();
        eq("still refused", 409, status(r::connect));
        eq("without dialling again", dials, tv.dialed.size());
        eq("pin unchanged", a.sha, mem.get("tv_pin"));
        r.trustAgain();
        eq("trust again forgets the token", "", mem.get("tv_token"));
        eq("and the pin", "", mem.get("tv_pin"));
        eq("pairs with the new certificate", 0, status(r::connect));
        eq("asked without a token", "/api/v2/channels/samsung.remote.control?name=VmVzdG9y", tv.targets.get(tv.targets.size() - 1));
        eq("new pin", b.sha, mem.get("tv_pin"));
        yes("token again", r.paired());

        // A new address for the same TV keeps the pairing.
        eq("same address is a no-op", 0, status(() -> r.setAddress(TV)));
        eq("still connected", SamsungRemote.CONNECTED, r.state());
        r.forget();
        for (String k : new String[] {"tv_ip", "tv_port", "tv_token", "tv_pin", "tv_mac", "tv_model", "tv_label"}) eq("forgot " + k, "", mem.get(k));
        eq("no TV", SamsungRemote.NONE, r.state());
    }

    private static SamsungRemote paired(FakeTv tv, Mem mem) {
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        int s = status(r::connect);
        if (s != 0) throw new IllegalStateException("pairing failed " + s + " " + r.error());
        return r;
    }

    private static void refusals(FakeTv tv) {
        tv.reset();
        tv.mode = "deny";
        Mem mem = new Mem();
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        eq("denied", 403, status(r::connect));
        eq("says refused", SamsungRemote.REFUSED, r.state());
        yes("tells where", r.error().contains("Device Connection Manager"));
        yes("no token", !r.paired());

        tv.mode = "timeout";
        eq("nobody answered", 408, status(r::connect));
        eq("timeout says so", SamsungRemote.NO_ANSWER, r.error());
        eq("off after timeout", SamsungRemote.OFF, r.state());

        tv.mode = "silent";
        long t0 = System.currentTimeMillis();
        eq("silent TV", 408, status(r::connect));
        long took = System.currentTimeMillis() - t0;
        yes("gave up at the deadline", took >= 2900 && took < 6000);

        tv.mode = "silent";
        tv.pingForever = true;
        t0 = System.currentTimeMillis();
        eq("TV that pings instead of answering", 408, status(r::connect));
        took = System.currentTimeMillis() - t0;
        yes("still cut at the deadline", took >= 2900 && took < 6000);
        eq("says nobody answered", SamsungRemote.NO_ANSWER, r.error());
        tv.pingForever = false;

        // The TV refuses the token later, without closing: Vestor forgets it.
        tv.reset();
        Mem m2 = new Mem();
        final SamsungRemote p = paired(tv, m2);
        yes("paired before", p.paired());
        tv.refuseKeys = true;
        eq("key sent", 0, status(() -> p.key("KEY_UP")));
        yes("refused later", await(() -> SamsungRemote.REFUSED.equals(p.state()), 2000));
        yes("token forgotten", !p.paired());
        yes("said goodbye", await(() -> tv.closes.contains(1000), 2000));
    }

    // ---- The open channel ------------------------------------------------------------------

    private static void channel(FakeTv tv) {
        tv.reset();
        tv.pingAfterConnect = true;
        tv.afterConnect = cat(frameBytes(0x82, new byte[] {1, 2, 3}), frameBytes(0x01, "{\"event\":\"ms.er".getBytes(StandardCharsets.UTF_8)),
                frameBytes(0x89, "x".getBytes(StandardCharsets.UTF_8)), frameBytes(0x00, "ror\",\"data\":{\"message\":\"bo".getBytes(
                StandardCharsets.UTF_8)), frameBytes(0x80, "om\"}}".getBytes(StandardCharsets.UTF_8)));
        Mem mem = new Mem();
        final SamsungRemote r = paired(tv, mem);
        yes("fragments put together, ping inside", await(() -> "The TV said: boom".equals(r.error()), 2000));
        yes("pongs", await(() -> tv.pongs.contains("x") && tv.pongs.contains("hi"), 2000));
        eq("still connected", SamsungRemote.CONNECTED, r.state());
        eq("pong masked", false, tv.unmasked);

        // A quiet channel is pinged before a key goes on it. A TV that answers keeps the channel,
        // one that does not is dialled again, and the key arrives either way.
        tv.reset();
        Mem m3 = new Mem();
        final SamsungRemote q = paired(tv, m3);
        q.staleMs = 200;
        sleep(300);
        eq("key after a quiet spell", 0, status(() -> q.key("KEY_UP")));
        yes("pinged first", tv.pings.get() == 1);
        eq("same channel", 1, tv.targets.size());
        tv.deaf = true;
        sleep(300);
        long t1 = System.currentTimeMillis();
        eq("key to a TV that stopped answering", 0, status(() -> q.key("KEY_DOWN")));
        yes("waited for the pong, briefly", System.currentTimeMillis() - t1 < 3000);
        eq("dialled again", 2, tv.targets.size());
        yes("both keys arrived", await(() -> tv.got.size() == 2 && tv.got.get(1).contains("KEY_DOWN"), 2000));
        q.close();

        tv.reset();
        tv.closeAfterConnect = true;
        Mem m2 = new Mem();
        final SamsungRemote c = paired(tv, m2);
        yes("TV closed", await(() -> SamsungRemote.OFF.equals(c.state()), 2000));
        yes("close echoed", await(() -> tv.closes.contains(1000), 2000));
        tv.closeAfterConnect = false;
        eq("next key opens again", 0, status(() -> c.key("KEY_DOWN")));
        yes("arrives", await(() -> tv.got.size() == 1, 2000));
        eq("with the token", "48152623", query(tv.targets.get(tv.targets.size() - 1), "token"));
    }

    // ---- Broken frames and heads ------------------------------------------------------------

    private static void broken(FakeTv tv, String what, byte[] after, int closeCode) {
        broken(tv, what, after, closeCode, 2500);
    }

    private static void broken(FakeTv tv, String what, byte[] after, int closeCode, long within) {
        tv.reset();
        tv.mode = "silent";
        tv.after = after;
        Mem mem = new Mem();
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        long t0 = System.currentTimeMillis();
        int s = status(r::connect);
        eq(what + ": refused", 502, s);
        yes(what + ": quick", System.currentTimeMillis() - t0 < within);
        eq(what + ": off", SamsungRemote.OFF, r.state());
        yes(what + ": not paired", !r.paired());
        if (closeCode > 0) yes(what + ": closed with " + closeCode, await(() -> tv.closes.contains(closeCode), 2000));
    }

    private static void hostileFrames(FakeTv tv) {
        byte[] p = "{}".getBytes(StandardCharsets.UTF_8);
        broken(tv, "masked frame", new byte[] {(byte) 0x81, (byte) 0x82, 1, 2, 3, 4, 'a', 'b'}, 1002);
        broken(tv, "reserved bit", frameBytes(0xC1, p), 1002);
        broken(tv, "unknown opcode", frameBytes(0x83, p), 1002);
        broken(tv, "10 MB frame", new byte[] {(byte) 0x81, 127, 0, 0, 0, 0, 0, (byte) 0xA0, 0, 0}, 1009);
        broken(tv, "length sign bit", new byte[] {(byte) 0x81, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 1}, 1002);
        broken(tv, "long form for 5", new byte[] {(byte) 0x81, 126, 0, 5, 'h', 'e', 'l', 'l', 'o'}, 1002);
        broken(tv, "bad UTF-8", frameBytes(0x81, new byte[] {(byte) 0xC3, (byte) 0x28}), 1007);
        broken(tv, "lone surrogate", frameBytes(0x81, new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}), 1007);
        broken(tv, "continuation first", frameBytes(0x80, p), 1002);
        broken(tv, "message inside a message", cat(frameBytes(0x01, p), frameBytes(0x81, p)), 1002);
        broken(tv, "fragmented ping", frameBytes(0x09, p), 1002);
        byte[] longPing = new byte[126];
        broken(tv, "long ping", frameBytes(0x89, longPing), 1002);
        broken(tv, "one-byte close", frameBytes(0x88, new byte[] {3}), 1002);
        byte[] half = new byte[35000];
        Arrays.fill(half, (byte) 'a');
        broken(tv, "70 KB in two parts", cat(frameBytes(0x01, half), frameBytes(0x80, half)), 1009);
        byte[] whole = new byte[66000];
        Arrays.fill(whole, (byte) 'a');
        broken(tv, "66 KB frame", frameBytes(0x81, whole), 1009);
        byte[] many = new byte[0];
        for (int i = 0; i < 51; i++) many = cat(many, text("{}"));
        broken(tv, "51 messages before letting in", many, 1000);
        // Bounded by the time allowed for pairing (3 s here), not by the frame's own 10 s.
        broken(tv, "stalls inside a frame", new byte[] {(byte) 0x81, 10, 'a'}, 1002, 4500);
        Random rnd = new Random(42);
        for (int i = 0; i < 20; i++) {
            byte[] junk = new byte[64];
            rnd.nextBytes(junk);
            junk[0] = (byte) (junk[0] | 0x70);
            broken(tv, "garbage " + i, junk, 0);
        }

        // Deep JSON and odd events are ignored, then the TV lets Vestor in.
        tv.reset();
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 3000; i++) deep.append('[');
        tv.after = cat(text(deep.toString()), text("{\"event\":\"ed.edenTV.update\",\"data\":{\"update_type\":\"ed.edenApp.update\"}}"),
                text("{\"event\":\"ms.voiceApp.hide\"}"), text("\"just a string\""), frameBytes(0x82, new byte[] {0}));
        Mem mem = new Mem();
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        eq("odd messages ignored", 0, status(r::connect));
    }

    private static void badHead(FakeTv tv, String what, Head head) {
        tv.reset();
        tv.head = head;
        Mem mem = new Mem();
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        eq(what, 502, status(r::connect));
        eq(what + ": off", SamsungRemote.OFF, r.state());
    }

    private static void hostileHeads(FakeTv tv) {
        badHead(tv, "200 instead of 101", key -> "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "wrong accept", key -> ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + Ws.accept("AAAAAAAAAAAAAAAAAAAAAA==") + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "no Upgrade", key -> ("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + Ws.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "unasked extension", key -> ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + Ws.accept(key) + "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "two accepts", key -> ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + Ws.accept(key) + "\r\nSec-WebSocket-Accept: " + Ws.accept(key) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        StringBuilder longLine = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\nX-Long: ");
        for (int i = 0; i < 3000; i++) longLine.append('a');
        final String ll = longLine.append("\r\n").toString();
        badHead(tv, "line over 2 KB", key -> (ll + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + Ws.accept(key)
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        StringBuilder many = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\n");
        for (int i = 0; i < 50; i++) many.append("X-").append(i).append(": a\r\n");
        final String mh = many.toString();
        badHead(tv, "50 headers", key -> (mh + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + Ws.accept(key)
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "non-ASCII header", key -> ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "X-A: é\r\nSec-WebSocket-Accept: " + Ws.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        badHead(tv, "bare CR", key -> ("HTTP/1.1 101 Switching Protocols\rUpgrade: websocket\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "folded header", key -> ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n  more\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + Ws.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "HTTP/1.0 101", key -> ("HTTP/1.0 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + Ws.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "1010", key -> ("HTTP/1.1 1010 Odd\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        badHead(tv, "a frame instead of a head", key -> new byte[0]);

        tv.reset();
        Mem mem = new Mem();
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        tv.trickleMs = 100;
        long t0 = System.currentTimeMillis();
        eq("dripping head", 502, status(r::connect));
        long took = System.currentTimeMillis() - t0;
        yes("cut at the deadline", took >= 1900 && took < 4500);
        eq("says slow", SamsungRemote.SLOW, r.error());
        tv.trickleMs = 0;
    }

    // ---- Older TVs: plain WebSocket on 8001 ------------------------------------------------

    private static void plain(FakeTv tv) {
        tv.reset();
        tv.rest = TU7000.replace("\"TokenAuthSupport\":\"true\",", "");
        Mem mem = new Mem();
        mem.put("tv_token", "11111111");
        final SamsungRemote r = tv.remote(mem);
        status(() -> r.setAddress(TV));
        status(r::probe);
        eq("plain port", 8001, r.port());
        eq("plain connect", 0, status(() -> r.key("KEY_VOLUP")));
        eq("never a token on plain", "/api/v2/channels/samsung.remote.control?name=VmVzdG9y", tv.targets.get(0));
        eq("dialled 8001", TV + ":8001", tv.dialed.get(tv.dialed.size() - 1));
        yes("key arrives", await(() -> tv.got.size() == 1 && tv.got.get(0).contains("KEY_VOLUP"), 2000));
        r.close();
    }

    // ---- Wake-on-LAN ---------------------------------------------------------------------------

    private static void wol() throws IOException {
        byte[] m = SamsungRemote.magic("8c:c8:cd:12:34:56");
        eq("magic length", 102, m.length);
        boolean ok = true;
        for (int i = 0; i < 6; i++) ok &= m[i] == (byte) 0xFF;
        for (int i = 0; i < 16; i++) {
            ok &= m[6 + i * 6] == (byte) 0x8C && m[6 + i * 6 + 5] == (byte) 0x56;
        }
        yes("magic layout", ok);
        yes("dashes", SamsungRemote.magic("8C-C8-CD-12-34-56") != null);
        for (String bad : new String[] {"00:00:00:00:00:00", "01:00:5e:00:00:01", "zz:00:00:00:00:00", "8cc8cd123456", "8c:c8:cd:12:34",
            "8c:c8:cd:12:34:56:78", "", "8c:c8:cd:12:34:5\n"}) {
            eq("bad MAC " + bad.replace("\n", "\\n"), null, SamsungRemote.magic(bad));
        }
        eq("null MAC", null, SamsungRemote.magic(null));
        List<InetAddress> to = SamsungRemote.targets(TV);
        eq("limited broadcast first", "255.255.255.255", to.get(0).getHostAddress());
        yes("the TV too", to.contains(InetAddress.getByName(TV)));
        for (InetAddress a : to) yes("only home or broadcast: " + a, a.getHostAddress().equals("255.255.255.255") || Console.isPrivate(a));
        eq("no TV for a public address", false, SamsungRemote.targets("8.8.8.8").contains(InetAddress.getByName("8.8.8.8")));
        DatagramSocket rx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        rx.setSoTimeout(2000);
        int sent = SamsungRemote.send(m, Collections.singletonList(InetAddress.getByName("127.0.0.1")), rx.getLocalPort());
        eq("sent three", 3, sent);
        int received = 0;
        for (int i = 0; i < 3; i++) {
            DatagramPacket pk = new DatagramPacket(new byte[200], 200);
            rx.receive(pk);
            if (Arrays.equals(m, Arrays.copyOf(pk.getData(), pk.getLength()))) received++;
        }
        rx.close();
        eq("received three magic packets", 3, received);
    }

    // ---- SmartThings: the rest of the TV ------------------------------------------------------

    static final String DEVICE = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    /** The main component's status of a 2020 TV, in the shape SmartThings answers (VD-STV profiles). */
    static String status2020(String power, String inputs) {
        return "{\"switch\":{\"switch\":{\"value\":\"" + power + "\",\"timestamp\":\"2026-10-27T15:00:00Z\"}},"
                + "\"audioVolume\":{\"volume\":{\"value\":12,\"unit\":\"%\"}},\"audioMute\":{\"mute\":{\"value\":\"unmuted\"}},"
                + "\"custom.picturemode\":{\"pictureMode\":{\"value\":\"Standard\"},\"supportedPictureModes\":{\"value\":[\"Dynamic\","
                + "\"Standard\",\"Natural\",\"Movie\"]},\"supportedPictureModesMap\":{\"value\":[{\"id\":\"modeDynamic\",\"name\":\"Dynamic\"}]}},"
                + "\"custom.soundmode\":{\"soundMode\":{\"value\":\"Standard\"},\"supportedSoundModes\":{\"value\":[\"Standard\",\"Optimized\","
                + "\"Amplify\"]},\"supportedSoundModesMap\":{\"value\":[]}},"
                + "\"samsungvd.mediaInputSource\":{\"inputSource\":{\"value\":\"HDMI1\"},\"supportedInputSourcesMap\":{\"value\":" + inputs + "}},"
                + "\"mediaInputSource\":{\"inputSource\":{\"value\":\"HDMI1\"},\"supportedInputSources\":{\"value\":[\"digitalTv\",\"HDMI1\","
                + "\"HDMI2\"]}},\"samsungvd.remoteControl\":{},\"custom.launchapp\":{},\"samsungvd.supportsPowerOnByOcf\":"
                + "{\"supportsPowerOnByOcf\":{\"value\":\"true\"}},\"tvChannel\":{\"tvChannel\":{\"value\":\"\"},\"tvChannelName\":{\"value\":\"\"}},"
                + "\"ocf\":{\"mnmo\":{\"value\":\"UN43TU7000FXZA\"},\"vid\":{\"value\":\"VD-STV-2020\"},\"mnpv\":{\"value\":\"Tizen 5.5\"}},"
                + "\"refresh\":{},\"execute\":{\"data\":{\"value\":null}}}";
    }

    static final String INPUTS = "[{\"id\":\"dtv\",\"name\":\"TV\"},{\"id\":\"HDMI1\",\"name\":\"Fire TV\"},{\"id\":\"HDMI2\",\"name\":\"HDMI 2\"}]";

    static final class Call {
        final String method, url, body;

        Call(String method, String url, byte[] body) {
            this.method = method;
            this.url = url;
            this.body = body == null ? null : new String(body, StandardCharsets.UTF_8);
        }
    }

    /** SmartThings for one TV: tokens, the device list, statuses and commands. */
    static final class Cloud implements Samsung.Web {
        final List<Call> calls = new ArrayList<>();
        volatile String component = status2020("on", INPUTS);
        volatile int commandStatus = 200;
        int issued;

        @Override
        public Net.Reply call(String method, String url, String[] headers, String contentType, byte[] body) {
            Call c = new Call(method, url, body);
            calls.add(c);
            if (url.equals(Samsung.API + "/oauth/token")) {
                issued++;
                return reply(200, "{\"access_token\":\"A" + issued + "\",\"refresh_token\":\"R" + issued + "\",\"expires_in\":86399}");
            }
            if (url.startsWith(Samsung.API + "/devices?capability=")) {
                return reply(200, "{\"items\":[{\"deviceId\":\"" + DEVICE + "\",\"label\":\"Living Room TV\"}]}");
            }
            if (url.endsWith("/components/main/capabilities/custom.picturemode/status")) {
                return reply(200, "{\"pictureMode\":{\"value\":\"Standard\"},\"supportedPictureModes\":{\"value\":[\"Dynamic\",\"Standard\","
                        + "\"Natural\",\"Movie\"]}}");
            }
            if (url.equals(Samsung.API + "/devices/" + DEVICE + "/components/main/status")) return reply(200, component);
            if (url.equals(Samsung.API + "/devices/" + DEVICE + "/commands") && "POST".equals(method)) {
                return reply(commandStatus, commandStatus == 200 ? "{\"results\":[{\"id\":\"1\",\"status\":\"ACCEPTED\"}]}" : "{}");
            }
            return reply(404, "{}");
        }

        String last() {
            return calls.get(calls.size() - 1).body;
        }
    }

    static Net.Reply reply(int code, String body) {
        return new Net.Reply(code, body.getBytes(StandardCharsets.UTF_8));
    }

    static Samsung connected(Mem mem, Cloud web) {
        final Samsung s = new Samsung(mem, web);
        String[] url = new String[1];
        status(() -> url[0] = s.begin("0f1e2d3c-4b5a-6978", "secret-secret"));
        String state = url[0].substring(url[0].indexOf("&state=") + 7);
        status(() -> s.finish("?code=AbCdEf&state=" + state));
        if (!s.connected()) throw new IllegalStateException("not connected");
        return s;
    }

    static String cmd(String capability, String command, String args) {
        return "{\"commands\":[{\"component\":\"main\",\"capability\":\"" + capability + "\",\"command\":\"" + command + "\""
                + (args == null ? "" : ",\"arguments\":" + args) + "}]}";
    }

    private static void cloud() {
        Mem mem = new Mem();
        Cloud web = new Cloud();
        final Samsung s = connected(mem, web);
        eq("nothing known before reading", 0, s.capabilities().size());
        eq("read all", 0, status(s::readAll));
        eq("one request", Samsung.API + "/devices/" + DEVICE + "/components/main/status", web.calls.get(web.calls.size() - 1).url);
        yes("remote keys reported", s.reports("samsungvd.remoteControl"));
        yes("launch reported", s.reports("custom.launchapp"));
        eq("power", "on", s.power());
        eq("volume", 12, s.volume());
        eq("muted", "unmuted", s.muted());
        eq("input", "HDMI1", s.input());
        List<String[]> in = s.inputs();
        eq("inputs", 3, in.size());
        eq("input id", "HDMI1", in.get(1)[0]);
        eq("input name", "Fire TV", in.get(1)[1]);
        eq("sound", "Standard", s.sound());
        eq("sounds", Arrays.asList("Standard", "Optimized", "Amplify"), s.sounds());
        eq("picture modes too", Arrays.asList("Dynamic", "Standard", "Natural", "Movie"), s.modes());

        eq("power off", 0, status(() -> s.power(false)));
        eq("power off JSON", cmd("switch", "off", null), web.last());
        eq("power now", "off", s.power());
        eq("power on", 0, status(() -> s.power(true)));
        eq("power on JSON", cmd("switch", "on", null), web.last());
        eq("volume 30", 0, status(() -> s.setVolume(30)));
        eq("volume JSON, a number", cmd("audioVolume", "setVolume", "[30]"), web.last());
        eq("volume now", 30, s.volume());
        int before = web.calls.size();
        eq("volume 101", 400, status(() -> s.setVolume(101)));
        eq("volume -1", 400, status(() -> s.setVolume(-1)));
        eq("nothing sent for bad volume", before, web.calls.size());
        eq("volume up", 0, status(() -> s.volumeStep(true)));
        eq("volume up JSON", cmd("audioVolume", "volumeUp", null), web.last());
        eq("volume after step", 31, s.volume());
        eq("volume down", 0, status(() -> s.volumeStep(false)));
        eq("volume down JSON", cmd("audioVolume", "volumeDown", null), web.last());
        eq("mute", 0, status(() -> s.mute(true)));
        eq("mute JSON", cmd("audioMute", "mute", null), web.last());
        eq("muted now", "muted", s.muted());
        eq("unmute", 0, status(() -> s.mute(false)));
        eq("unmute JSON", cmd("audioMute", "unmute", null), web.last());
        eq("input HDMI2", 0, status(() -> s.setInput("HDMI2")));
        eq("input JSON", cmd("samsungvd.mediaInputSource", "setInputSource", "[\"HDMI2\"]"), web.last());
        eq("input now", "HDMI2", s.input());
        before = web.calls.size();
        for (String bad : new String[] {"HDMI9", "HDMI1\"]", "", "HDMI 1", "../x"}) eq("input refused: " + bad, 400, status(() -> s.setInput(bad)));
        eq("null input", 400, status(() -> s.setInput(null)));
        eq("sound Amplify", 0, status(() -> s.setSound("Amplify")));
        eq("sound JSON", cmd("custom.soundmode", "setSoundMode", "[\"Amplify\"]"), web.last());
        for (String bad : new String[] {"Loud", "Amplify\"]", "", "<b>"}) eq("sound refused: " + bad, 400, status(() -> s.setSound(bad)));
        eq("nothing sent for refusals", before + 1, web.calls.size());
        eq("cloud key OK", 0, status(() -> s.key("OK")));
        eq("cloud key JSON", cmd("samsungvd.remoteControl", "send", "[\"OK\",\"PRESS_AND_RELEASED\"]"), web.last());
        for (String bad : new String[] {"KEY_UP", "FACTORY", "ok", "OK\"]", ""}) eq("cloud key refused: " + bad, 400, status(() -> s.key(bad)));
        eq("launch", 0, status(() -> s.launch("111299001912")));
        eq("launch JSON", cmd("custom.launchapp", "launchApp", "[\"111299001912\"]"), web.last());
        eq("launch refused", 400, status(() -> s.launch("x\"]")));
        eq("picture still works", 0, status(() -> s.setMode("Movie")));
        eq("picture JSON unchanged", cmd("custom.picturemode", "setPictureMode", "[\"Movie\"]"), web.last());

        web.commandStatus = 409;
        eq("TV busy", 502, status(() -> s.mute(true)));
        eq("busy says so", "The TV is off or busy", s.error());
        web.commandStatus = 429;
        eq("too many", 502, status(() -> s.setVolume(10)));
        eq("too many says so", "Too many TV commands, wait a minute", s.error());
        web.commandStatus = 200;
        eq("back", 0, status(() -> s.mute(false)));
        eq("error clears", "", s.error());

        // Off: the short list a TV in standby reports does not replace the real one.
        web.component = status2020("off", "[{\"id\":\"dtv\",\"name\":\"TV\"}]");
        eq("read while off", 0, status(s::readAll));
        eq("power off read", "off", s.power());
        eq("inputs kept while off", 3, s.inputs().size());

        // A TV without remote keys, launch or the Samsung input list: an older profile.
        web.component = "{\"switch\":{\"switch\":{\"value\":\"on\"}},\"audioVolume\":{\"volume\":{\"value\":5}},\"mediaInputSource\":"
                + "{\"inputSource\":{\"value\":\"HDMI1\"},\"supportedInputSources\":{\"value\":[\"digitalTv\",\"HDMI1\",\"HDMI2\"]}},"
                + "\"samsungvd.soundMode\":{\"soundMode\":{\"value\":\"Förstärk\"},\"supportedSoundModes\":{\"value\":[\"Standard\","
                + "\"Förstärk\"]}},\"custom.picturemode\":{}}";
        eq("older TV read", 0, status(s::readAll));
        eq("no remote keys", 409, status(() -> s.key("OK")));
        eq("no launch", 409, status(() -> s.launch("111299001912")));
        eq("no mute", 409, status(() -> s.mute(true)));
        eq("standard inputs", "digitalTv", s.inputs().get(0)[0]);
        eq("standard input set", 0, status(() -> s.setInput("digitalTv")));
        eq("standard input JSON", cmd("mediaInputSource", "setInputSource", "[\"digitalTv\"]"), web.last());
        eq("localized sound", "Förstärk", s.sound());
        eq("localized sound set", 0, status(() -> s.setSound("Förstärk")));
        eq("samsungvd sound JSON", cmd("samsungvd.soundMode", "setSoundMode", "[\"Förstärk\"]"), web.last());
        eq("unknown mute", "", s.muted());

        s.disconnect();
        for (String k : new String[] {"st_caps", "st_inputs", "st_sounds"}) eq("forgotten " + k, "", mem.get(k));
        eq("power forgotten", "", s.power());
        eq("volume forgotten", -1, s.volume());
        eq("not connected", 409, status(() -> s.power(true)));
        eq("read needs a TV", 409, status(s::readAll));
    }

    private static void cloudHostile() {
        Mem mem = new Mem();
        Cloud web = new Cloud();
        final Samsung s = connected(mem, web);
        StringBuilder inputs = new StringBuilder("[");
        for (int i = 0; i < 40; i++) inputs.append(i == 0 ? "" : ",").append("{\"id\":\"HDMI").append(i).append("\",\"name\":\"In ").append(i).append("\"}");
        inputs.append(",{\"id\":\"<script>\",\"name\":\"x\"},{\"id\":7},null,\"HDMI1\",{\"id\":\"HDMI2\",\"name\":\"<img src=x>\"}]");
        StringBuilder caps = new StringBuilder("{");
        for (int i = 0; i < 100; i++) caps.append("\"cap").append(i).append("\":{},");
        caps.append("\"../../x\":{},\"switch\":{\"switch\":{\"value\":\"exploded\"}},\"audioVolume\":{\"volume\":{\"value\":1e9}},"
                + "\"audioMute\":{\"mute\":{\"value\":[1]}},\"samsungvd.mediaInputSource\":{\"inputSource\":{\"value\":\"HDMI1\\\"]\"},"
                + "\"supportedInputSourcesMap\":{\"value\":" + inputs + "}},\"custom.soundmode\":{\"soundMode\":{\"value\":{\"x\":1}},"
                + "\"supportedSoundModes\":{\"value\":\"Standard\"}}}");
        web.component = caps.toString();
        eq("hostile read", 0, status(s::readAll));
        eq("capabilities capped", 64, s.capabilities().size());
        yes("odd capability names dropped", !s.capabilities().contains("../../x"));
        eq("odd power", "", s.power());
        eq("odd volume", -1, s.volume());
        eq("odd mute", "", s.muted());
        eq("odd input", "", s.input());
        eq("inputs capped", 16, s.inputs().size());
        for (String[] i : s.inputs()) yes("valid input id " + i[0], Samsung.validInput(i[0]));
        eq("sound list not a list", 0, s.sounds().size());
        eq("odd sound", "", s.sound());

        web.component = "not json";
        eq("garbage status", 502, status(s::readAll));
        web.component = "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[";
        eq("deep status", 502, status(s::readAll));
        web.component = "{\"samsungvd.mediaInputSource\":{\"supportedInputSourcesMap\":{\"value\":{\"id\":\"HDMI1\"}}},"
                + "\"switch\":{\"switch\":{\"value\":\"on\"}}}";
        eq("map not a list", 0, status(s::readAll));
        yes("names validated", !Samsung.validName("<b>") && !Samsung.validName("") && Samsung.validName("Amplify")
                && Samsung.validName("Förstärk") && !Samsung.validName("a‮b"));
    }
}
