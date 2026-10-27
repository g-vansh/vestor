package tv.lucent.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The control center's defences, tested against the real classes: Keys with a clock the test
 * holds, and Console on 127.0.0.1 (through {@link Console#listenOn}, as ConsoleDemo runs it)
 * driven with raw sockets from many loopback addresses. Covers the October 2026 audit: slow
 * clients and stuck writes (M1), the pairing window and code budget (M2), the pairing proof and
 * key rotation (H1), stream caps (L5), and the note text length. The slow cases run side by side
 * on their own servers, so the whole run takes about half a minute.
 *
 *   java -cp <classes> tv.lucent.home.SecurityTest
 */
public final class SecurityTest {
    private SecurityTest() {}

    private static int checks, failures;

    /** The pairing proof's test vector, also in web/API.md (computed there with Python's hmac). */
    static final String VECTOR_K = "AAECAwQFBgcICQoLDA0ODw", VECTOR_NONCE = "EBESExQVFhcYGRobHB0eHw",
            VECTOR_C = "ICEiIyQlJicoKSorLC0uLw", VECTOR_NAME = "Alex’s iPhone",
            VECTOR_PROOF = "39fc66d8e364aba818c0c8c07bc8ae54b309468c50cd2ef41df71f057502e48c";

    public static void main(String[] args) throws Exception {
        text();
        stream();
        vector();
        windowAndCode();
        rotation();
        storage();
        pairingOverHttp();
        cookiesOverHttp();
        slowClients();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    // ---- Checks ----------------------------------------------------------------------------------

    static synchronized void ok(String what, boolean cond) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    static synchronized void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static String rep(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    // ---- Info: note text -------------------------------------------------------------------------

    private static void text() {
        eq("names still cut at 120", Text.MAX, Text.clean(rep('a', 300)).length());
        eq("clean with a cap", 280, Text.clean(rep('a', 300), 280).length());
        eq("clean with a cap still cleans", "ab", Text.clean("a‮​b", 10));
        eq("clean of null", "", Text.clean(null, 5));
        Map<String, Object> body = new HashMap<>();
        body.put("text", rep('n', Notes.MAX_TEXT));
        eq("a 280-character note keeps all of it", Notes.MAX_TEXT, Notes.parse(body, "Alex", 0).text.length());
        body.put("text", rep('n', Notes.MAX_TEXT + 1));
        boolean refused = false;
        try {
            Notes.parse(body, "Alex", 0);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        ok("a 281-character note is refused, not cut", refused);
    }

    // ---- L5: stream caps ---------------------------------------------------------------------------

    /** Bytes made on demand, forever or up to a length, counting what was read. */
    static final class Endless extends InputStream {
        final byte[] pattern;
        final long length;
        long read;

        Endless(String pattern, long length) {
            this.pattern = pattern.getBytes(StandardCharsets.UTF_8);
            this.length = length;
        }

        @Override
        public int read() {
            if (read >= length) return -1;
            return pattern[(int) (read++ % pattern.length)];
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (read >= length) return -1;
            int n = (int) Math.min(len, length - read);
            for (int i = 0; i < n; i++) b[off + i] = pattern[(int) ((read + i) % pattern.length)];
            read += n;
            return n;
        }
    }

    static final class Count implements Stream.Handler {
        final List<String> types = new ArrayList<>();
        int longest;

        @Override
        public void event(String type, String data) {
            types.add(type);
            longest = Math.max(longest, data.length());
        }

        @Override
        public void state(boolean open) {}
    }

    private static void stream() throws IOException {
        long far = System.currentTimeMillis() + 3600000L;
        Count h = new Count();
        Endless line = new Endless("a", Long.MAX_VALUE);
        ok("a line with no end is a fault", !new Stream("https://api-v3.mbta.com/x", null, Stream.SSE, 1000, h).read(line, far));
        ok("... found as soon as it reaches the cap (read " + line.read + ")",
                line.read >= Stream.MAX_LINE && line.read <= Stream.MAX_LINE + 8192);

        h = new Count();
        Endless lines = new Endless("{\"id\":\"x\",\"event\":\"message\"}\n", Long.MAX_VALUE);
        ok("an ntfy stream that never stops is a fault", !new Stream("https://ntfy.sh/x", null, Stream.LINES, 1000, h).read(lines, far));
        ok("... after 1 MB (read " + lines.read + ")", lines.read > Stream.LINES_MAX_BYTES - 8192 && lines.read <= Stream.LINES_MAX_BYTES + 8192);
        ok("... having delivered its lines", h.types.size() > 1000);

        h = new Count();
        Endless events = new Endless("event: update\ndata: {}\n\n", Long.MAX_VALUE);
        ok("an SSE stream past its own cap is a fault",
                !new Stream("https://api-v3.mbta.com/x", null, Stream.SSE, 1000, 64 * 1024, h).read(events, far));
        ok("... at the cap given (read " + events.read + ")", events.read <= 64 * 1024 + 8192);

        h = new Count();
        String big = "data: " + rep('d', Stream.MAX_LINE - 6) + "\n\n";
        ok("a line of exactly the longest allowed is read",
                new Stream("https://api-v3.mbta.com/x", null, Stream.SSE, 1000, h).read(new Endless(big, big.length()), far));
        eq("... and delivered whole", Stream.MAX_LINE - 6, h.longest);

        h = new Count();
        String done = "event: reset\ndata: []\n\n";
        ok("a stream the server ends is clean", new Stream("https://api-v3.mbta.com/x", null, Stream.SSE, 1000, h)
                .read(new Endless(done, done.length()), far));
        eq("... with its event", "[reset]", h.types.toString());
        eq("16 MB a connection for the MBTA", 16L * 1024 * 1024, Stream.SSE_MAX_BYTES);
    }

    // ---- H1: the pairing proof -------------------------------------------------------------------

    private static void vector() {
        eq("proof test vector (web/API.md)", VECTOR_PROOF, Keys.proof(VECTOR_K, VECTOR_NONCE, VECTOR_C, VECTOR_NAME));
        eq("the name is part of the proof, as UTF-8", "937c172cd604f7cb7d135d1253a304a03a85313e35362038b0f88c32526d4f29",
                Keys.proof(VECTOR_K, VECTOR_NONCE, VECTOR_C, "Alex's iPhone"));
        eq("the vector's secret is 16 bytes 0x00..0x0f", VECTOR_K, Keys.b64(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}));
    }

    static final class Mem implements Keys.Store {
        String json;

        Mem(String json) {
            this.json = json;
        }

        @Override
        public String load() {
            return json;
        }

        @Override
        public void save(String json) {
            this.json = json;
        }
    }

    private static String randomC() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return Keys.b64(b);
    }

    private static String wrongPin(String pin) {
        return String.format(Locale.US, "%06d", (Integer.parseInt(pin) + 1) % 1000000);
    }

    // ---- M2 and H1: the window, the proof and the code ---------------------------------------------

    private static void windowAndCode() {
        long t = 1793124300000L;
        Keys keys = new Keys(new Mem(null));
        eq("no nonce without a window", null, keys.nonce(t));
        String[] w = keys.open(t);
        String nonce = keys.nonce(t);
        ok("a nonce of 22 URL-safe characters", nonce != null && nonce.matches("[A-Za-z0-9_-]{22}"));
        keys.open(t);
        ok("a new nonce each window", !nonce.equals(keys.nonce(t)));
        w = keys.open(t);
        nonce = keys.nonce(t);

        // Wrong proofs cost the window nothing: the secret is 128 bits.
        for (int i = 0; i < 25; i++) {
            Keys.Claim c = keys.claimProof(Keys.proof("not-the-secret-xxxxxxx", nonce, randomC(), "Mallory"), randomC(), "Mallory", "10.0.0.66", t);
            if (i == 0) eq("a wrong proof", "wrong", c.error);
        }
        ok("25 wrong proofs leave the window open", keys.windowOpen(t + 1000));
        eq("... and nothing locked", 0L, keys.lockedFor(t + 1000));
        eq("... and count as no wrong code", 0, keys.wrongCodes());
        String c1 = randomC();
        eq("a proof for another name", "wrong", keys.claimProof(Keys.proof(w[0], nonce, c1, "Alex"), c1, "Mallory", "10.0.0.66", t).error);
        String c2 = randomC();
        Keys.Claim right = keys.claimProof(Keys.proof(w[0], nonce, c1, "Alex"), c1, "Alex", "10.0.0.10", t);
        eq("a c is good for one try, even a right one", "wrong", right.error);
        right = keys.claimProof(Keys.proof(w[0], nonce, c2, "Alex"), c2, "Alex", "10.0.0.10", t + 2000);
        ok("the right proof asks the TV", right.pending != null);
        ok("... with three digits to match", right.pending != null && right.pending.match.matches("[0-9]{3}"));
        eq("... from its address", "10.0.0.10", right.pending == null ? null : right.pending.ip);
        ok("... and closes the window", !keys.windowOpen(t + 2000) && keys.nonce(t + 2000) == null);

        // An old window's proof is no good in a new one.
        String oldNonce = nonce;
        String[] w2 = keys.open(t + 10000);
        String c3 = randomC();
        eq("a proof for the last window's nonce", "wrong",
                keys.claimProof(Keys.proof(w2[0], oldNonce, c3, "Alex"), c3, "Alex", "10.0.0.10", t + 10000).error);
        eq("no window, no pairing", "closed", keys.claimPin(w2[1], "Alex", "10.0.0.10", t + 10000 + Keys.WINDOW_MS).error);

        // The code: five wrong from one address shut that address out; others keep it.
        long u = t + 3600000L;
        w = keys.open(u);
        for (int i = 0; i < Keys.MAX_TRIES; i++) eq("wrong code " + (i + 1), "wrong", keys.claimPin(wrongPin(w[1]), "x", "10.0.0.66", u).error);
        eq("the sixth try from that address, even right", "locked", keys.claimPin(w[1], "x", "10.0.0.66", u).error);
        eq("the TV can tell wrong codes were typed", Keys.MAX_TRIES, keys.wrongCodes());
        ok("the code still works for others", keys.pinOpen(u));
        ok("another address with the right code is asked about", keys.claimPin(w[1], "Laptop", "10.0.0.11", u).pending != null);

        // Twenty wrong from anywhere turn the code off for the window, never the QR code.
        u += 3600000L;
        w = keys.open(u);
        nonce = keys.nonce(u);
        for (int a = 0; a < 4; a++) {
            for (int i = 0; i < Keys.MAX_TRIES; i++) keys.claimPin(wrongPin(w[1]), "x", "10.0.1." + a, u);
        }
        eq("twenty wrong codes", Keys.MAX_WINDOW_TRIES, keys.wrongCodes());
        ok("... turn the code off", !keys.pinOpen(u));
        eq("... for a new address too", "locked", keys.claimPin(w[1], "x", "10.0.1.9", u).error);
        String c4 = randomC();
        ok("... while the QR code still pairs", keys.claimProof(Keys.proof(w[0], nonce, c4, "Alex"), c4, "Alex", "10.0.0.10", u).pending != null);

        // Three windows that ran out of codes within an hour: the code is off for 15 minutes.
        keys.events();
        u += 10 * 3600000L;
        for (int k = 0; k < 3; k++) {
            w = keys.open(u + k * 60000L);
            for (int i = 0; i < Keys.MAX_TRIES; i++) keys.claimPin(wrongPin(w[1]), "x", "10.0.0.66", u + k * 60000L);
        }
        long v = u + 3 * 60000L;
        eq("three failed windows lock the code for 15 minutes", Keys.COOLDOWN_MS - 60000L, keys.pinLockedFor(v));
        eq("pairing itself is never locked", 0L, keys.lockedFor(v));
        ok("the TV hears about it", keys.events().toString().contains("six-digit code is off for 15 minutes"));
        w = keys.open(v);
        ok("a window still opens", w != null && keys.windowOpen(v));
        eq("... showing no code", "", w[1]);
        eq("... and a code typed is refused", "locked", keys.claimPin("123456", "x", "10.0.0.11", v).error);
        String c5 = randomC();
        ok("... but the QR code pairs", keys.claimProof(Keys.proof(w[0], keys.nonce(v), c5, "Alex"), c5, "Alex", "10.0.0.10", v).pending != null);
        w = keys.open(v + Keys.COOLDOWN_MS);
        ok("after 15 minutes the code is back", w[1].matches("[0-9]{6}") && keys.pinOpen(v + Keys.COOLDOWN_MS));

        // Failed windows more than an hour apart never add up.
        Keys spread = new Keys(new Mem(null));
        for (int k = 0; k < 3; k++) {
            long at = t + k * 61 * 60000L;
            String[] x = spread.open(at);
            for (int i = 0; i < Keys.MAX_TRIES; i++) spread.claimPin(wrongPin(x[1]), "x", "10.0.0.66", at);
        }
        eq("failed windows hours apart lock nothing", 0L, spread.pinLockedFor(t + 2 * 61 * 60000L));
    }

    // ---- H1: rotating keys -------------------------------------------------------------------------

    /** A paired device's first cookie, through the real steps. */
    private static String pair(Keys keys, String name, String ip, long t) {
        String[] w = keys.open(t);
        Keys.Pending p = keys.claimPin(w[1], name, ip, t).pending;
        keys.decide(p.id, true, t);
        return keys.collect(p.id, ip, t)[1];
    }

    private static void rotation() {
        long t = 1793124300000L;
        Mem mem = new Mem(null);
        Keys keys = new Keys(mem);
        String ip = "10.0.0.10";
        String v1 = pair(keys, "Alex's iPhone", ip, t);
        Keys.Check k = keys.verify(v1, ip, t + 10000);
        ok("a new key works", k.device != null && k.cookie == null && !k.reused);
        k = keys.verify(v1, ip, t + Keys.ROTATE_MS + 1000);
        String v2 = k.cookie;
        ok("a minute on, a new key is handed out", k.device != null && v2 != null && !v2.equals(v1));
        ok("... for the same device", v2 != null && v2.startsWith(v1.substring(0, v1.indexOf('.') + 1)));
        k = keys.verify(v1, ip, t + 70000);
        ok("the previous key is good for 30 s (requests on their way)", k.device != null && k.cookie == null);
        k = keys.verify(v2, ip, t + 75000);
        ok("the new key works", k.device != null && k.cookie == null);
        ok("known() agrees, changing nothing", keys.known(v2, t + 75000) && !keys.known(v1, t + 95000) && keys.count() == 1);
        keys.events();
        k = keys.verify(v1, ip, t + 95000);
        ok("the previous key after its grace means a copy", k.reused && k.device == null);
        eq("... the device is signed out", 0, keys.count());
        eq("... and the TV is told", "[Alex's iPhone was signed out: its key was used from two places]", keys.events().toString());
        eq("events are handed over once", "[]", keys.events().toString());
        ok("... the new key is dead too", keys.verify(v2, ip, t + 96000).device == null);

        // A phone that never got its new key (it slept mid-request) is not taken for a thief.
        keys = new Keys(new Mem(null));
        v1 = pair(keys, "Alex's iPhone", ip, t);
        String lost = keys.verify(v1, ip, t + 61000).cookie;
        k = keys.verify(v1, ip, t + 300000);
        String v3 = k.cookie;
        ok("the old key, while the new one was never seen, gets another", k.device != null && v3 != null && !v3.equals(lost));
        ok("... which works", keys.verify(v3, ip, t + 301000).device != null);
        ok("... and the unseen one, if it shows up, is a copy", keys.verify(lost, ip, t + 302000).reused);

        // Older keys than the previous one are copies at any time.
        keys = new Keys(new Mem(null));
        v1 = pair(keys, "Alex's iPhone", ip, t);
        v2 = keys.verify(v1, ip, t + 61000).cookie;
        keys.verify(v2, ip, t + 62000);
        v3 = keys.verify(v2, ip, t + 125000).cookie;
        ok("two keys back is a copy", keys.verify(v1, ip, t + 126000).reused);

        // A new address is news.
        keys = new Keys(new Mem(null));
        v1 = pair(keys, "Alex's iPhone", "10.0.0.10", t);
        keys.verify(v1, "10.0.0.10", t + 1000);
        keys.verify(v1, "100.101.102.103", t + 2000);
        eq("a device at a new address", "[Alex's iPhone is now at 100.101.102.103]", keys.events().toString());
        ok("unknown keys are nothing", keys.verify("nope.nope", ip, t) == Keys.Check.NONE && keys.verify(null, ip, t) == Keys.Check.NONE);
        ok("a wrong verifier for a real selector signs nobody out",
                keys.verify(v1.substring(0, v1.indexOf('.') + 1) + "guess", ip, t + 3000).device == null && keys.count() == 1);
    }

    private static void storage() {
        long t = 1793124300000L;
        Mem mem = new Mem(null);
        Keys keys = new Keys(mem);
        String ip = "10.0.0.10";
        String v1 = pair(keys, "Alex's iPhone", ip, t);
        String v2 = keys.verify(v1, ip, t + 61000).cookie;
        keys.verify(v2, ip, t + 62000);
        String v3 = keys.verify(v2, ip, t + 130000).cookie;
        keys.verify(v3, ip, t + 131000);
        String stored = mem.json;
        for (String v : new String[] {v1, v2, v3}) {
            String verifier = v.substring(v.indexOf('.') + 1);
            ok("only hashes are stored", !stored.contains(verifier) && stored.contains(Keys.sha(verifier)));
        }
        Keys again = new Keys(new Mem(stored));
        ok("after a restart the current key works", again.verify(v3, ip, t + 140000).device != null);
        ok("... and an old one is still known for a copy", again.verify(v1, ip, t + 141000).reused);
    }

    // ---- Over HTTP ---------------------------------------------------------------------------------

    static final class Resp {
        int status;
        final Map<String, List<String>> headers = new HashMap<>();
        String body = "";
        long ms;
        boolean timedOut, reset;

        String header(String name) {
            List<String> l = headers.get(name);
            return l == null ? null : l.get(0);
        }

        Map<String, Object> json() {
            try {
                return Json.obj(Json.parse(body));
            } catch (RuntimeException e) {
                return new HashMap<>();
            }
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    private static Socket connect(int port, String src) throws IOException {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(src, 0));
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        return s;
    }

    static byte[] raw(int port, String method, String path, String[] extra, String body) {
        StringBuilder h = new StringBuilder();
        String host = "127.0.0.1:" + port;
        h.append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ").append(host).append("\r\n");
        if (path.startsWith("/api/")) {
            h.append("X-Lucent: 1\r\n");
            if (method.equals("POST")) h.append("Origin: http://").append(host).append("\r\n");
        }
        boolean typed = false;
        for (int i = 0; extra != null && i + 1 < extra.length; i += 2) {
            h.append(extra[i]).append(": ").append(extra[i + 1]).append("\r\n");
            typed |= extra[i].equalsIgnoreCase("content-type");
        }
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (method.equals("POST")) {
            if (!typed && path.startsWith("/api/")) h.append("Content-Type: application/json\r\n");
            h.append("Content-Length: ").append(b.length).append("\r\n");
        }
        h.append("\r\n");
        byte[] head = h.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + b.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(b, 0, all, head.length, b.length);
        return all;
    }

    static Resp call(int port, String src, String method, String path, String[] extra, String body) throws IOException {
        return call(port, src, method, path, extra, body, 10000);
    }

    static Resp call(int port, String src, String method, String path, String[] extra, String body, int timeoutMs) throws IOException {
        Socket s = connect(port, src);
        try {
            s.setSoTimeout(timeoutMs);
            long t0 = System.nanoTime();
            OutputStream o = s.getOutputStream();
            o.write(raw(port, method, path, extra, body));
            o.flush();
            return read(s, t0);
        } finally {
            s.close();
        }
    }

    static Resp read(Socket s, long t0) {
        Resp r = new Resp();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        try {
            InputStream in = s.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) got.write(buf, 0, n);
        } catch (SocketTimeoutException e) {
            r.timedOut = true;
        } catch (IOException e) {
            r.reset = true;
        }
        r.ms = (System.nanoTime() - t0) / 1000000L;
        String all = new String(got.toByteArray(), StandardCharsets.ISO_8859_1);
        int end = all.indexOf("\r\n\r\n");
        if (end < 0) return r;
        String[] lines = all.substring(0, end).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length > 1) r.status = Integer.parseInt(first[1]);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String k = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
            List<String> l = r.headers.get(k);
            if (l == null) r.headers.put(k, l = new ArrayList<>());
            l.add(lines[i].substring(colon + 1).trim());
        }
        r.body = new String(all.substring(end + 4).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return r;
    }

    static int freePort() throws IOException {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int p = s.getLocalPort();
        s.close();
        return p;
    }

    /** The real Console and Keys under a stub host, on 127.0.0.1. */
    static final class Server {
        final Mem store;
        final Keys keys;
        final Console console;
        final int port;
        final List<Keys.Pending> asked = Collections.synchronizedList(new ArrayList<Keys.Pending>());
        volatile boolean approve = true;

        Server(String storeJson, final byte[] font) throws IOException {
            store = new Mem(storeJson);
            keys = new Keys(store);
            final Keys k = keys;
            Console.Host host = new Console.Host() {
                @Override
                public byte[] asset(String name) {
                    if (name.startsWith("fonts/") && font != null) return font;
                    return "<!doctype html><title>x</title>".getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public String state(Keys.Device d, String peer) {
                    return new Jw().obj().put("me", d.name).end().toString();
                }

                @Override
                public String act(Keys.Device d, String action, Map<String, Object> body) {
                    return "{\"ok\":true}";
                }

                @Override
                public String photo(Keys.Device d, byte[] body, String caption, long taken) {
                    return "{\"ok\":true,\"n\":" + body.length + "}";
                }

                @Override
                public byte[] photoFile(String id, boolean thumb) {
                    return null;
                }

                @Override
                public void pairRequest(final Keys.Pending p) {
                    asked.add(p);
                    if (!approve) return;
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                return;
                            }
                            k.decide(p.id, true, System.currentTimeMillis());
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }

                @Override
                public String name() {
                    return "Lucent";
                }
            };
            console = new Console(host, keys);
            port = freePort();
            console.setPort(port);
            console.listenOn(InetAddress.getByName("127.0.0.1"), 8);
        }

        Resp get(String src, String path, String cookie) throws IOException {
            return call(port, src, "GET", path, cookie == null ? null : new String[] {"Cookie", "lucent=" + cookie}, null);
        }

        Resp post(String src, String path, String body, String cookie) throws IOException {
            return call(port, src, "POST", path, cookie == null ? null : new String[] {"Cookie", "lucent=" + cookie}, body);
        }

        String nonce(String src) throws IOException {
            return Json.str(get(src, "/api/hello", null).json(), "nonce", null);
        }

        void stop() {
            console.stop();
        }
    }

    /** A store holding one device with the given key state, for keys at any age without waiting. */
    static String device(String selector, String verifier, long issued, boolean confirmed, String prev, long prevUntil) {
        Jw w = new Jw().obj().key("devices").arr().obj().put("s", selector).put("h", Keys.sha(verifier)).put("n", "Alex's iPhone")
                .put("ip", "127.0.0.10").put("c", 1).put("l", 1).put("i", issued).put("f", confirmed);
        if (prev != null) w.put("p", Keys.sha(prev)).put("pu", prevUntil);
        return w.key("r").arr().endArr().end().endArr().end().toString();
    }

    private static String setCookieValue(Resp r) {
        String c = r.header("set-cookie");
        if (c == null || !c.startsWith("lucent=")) return null;
        int semi = c.indexOf(';');
        return c.substring("lucent=".length(), semi < 0 ? c.length() : semi);
    }

    private static void pairingOverHttp() throws Exception {
        Server s = new Server(null, null);
        try {
            Resp hello = s.get("127.0.0.10", "/api/hello", null);
            ok("hello without a window has no nonce", hello.status == 200 && !hello.json().containsKey("nonce")
                    && Boolean.FALSE.equals(hello.json().get("pairing")));
            String[] w = s.keys.open(System.currentTimeMillis());
            hello = s.get("127.0.0.10", "/api/hello", null);
            eq("hello carries the window's nonce", s.keys.nonce(System.currentTimeMillis()), Json.str(hello.json(), "nonce", null));
            eq("the old form with the secret itself is refused", 400,
                    s.post("127.0.0.10", "/api/pair", "{\"k\":\"" + w[0] + "\",\"name\":\"Alex\"}", null).status);

            // An attacker who sees the window open sends wrong proofs: the owner still pairs.
            String nonce = s.nonce("127.0.0.66");
            for (int i = 0; i < 3; i++) {
                String c = randomC();
                Resp bad = s.post("127.0.0.66", "/api/pair",
                        new Jw().obj().put("proof", Keys.proof("guess" + i, nonce, c, "Mallory")).put("c", c).put("name", "Mallory").end().toString(), null);
                eq("a wrong proof over HTTP", "403 wrong", bad.status + " " + Json.str(bad.json(), "error", ""));
            }
            String c = randomC();
            Resp claim = s.post("127.0.0.10", "/api/pair",
                    new Jw().obj().put("proof", Keys.proof(w[0], nonce, c, "Alex’s iPhone")).put("c", c).put("name", "Alex’s iPhone").end().toString(), null);
            eq("the owner's proof", 202, claim.status);
            String match = Json.str(claim.json(), "match", "");
            ok("... answers three digits to match", match.matches("[0-9]{3}"));
            ok("... the same the TV shows", !s.asked.isEmpty() && s.asked.get(0).match.equals(match) && s.asked.get(0).name.equals("Alex’s iPhone"));
            Resp got = s.post("127.0.0.10", "/api/pair/wait", "{\"id\":\"" + Json.str(claim.json(), "wait", "") + "\"}", null);
            String cookie = setCookieValue(got);
            eq("the OK on the remote hands over the key", 200, got.status);
            ok("... as an HttpOnly, SameSite=Strict cookie", got.header("set-cookie") != null
                    && got.header("set-cookie").endsWith("; Path=/; HttpOnly; SameSite=Strict; Max-Age=34560000"));
            eq("... which works", 200, s.get("127.0.0.10", "/api/state", cookie).status);

            // The code over HTTP: five wrong from one address, then that address is out, another is not.
            w = s.keys.open(System.currentTimeMillis());
            String wrong = wrongPin(w[1]);
            for (int i = 0; i < Keys.MAX_TRIES; i++) {
                eq("wrong code over HTTP " + (i + 1), "wrong", Json.str(s.post("127.0.0.67", "/api/pair",
                        "{\"pin\":\"" + wrong + "\",\"name\":\"x\"}", null).json(), "error", ""));
                Thread.sleep(150);
            }
            Resp locked = s.post("127.0.0.67", "/api/pair", "{\"pin\":\"" + w[1] + "\",\"name\":\"x\"}", null);
            eq("then that address is locked out", "403 locked", locked.status + " " + Json.str(locked.json(), "error", ""));
            eq("the TV can tell", Keys.MAX_TRIES, s.keys.wrongCodes());
            Resp laptop = s.post("127.0.0.11", "/api/pair", "{\"pin\":\"" + w[1] + "\",\"name\":\"Laptop\"}", null);
            ok("another address still pairs with the code", laptop.status == 202 && Json.str(laptop.json(), "match", "").matches("[0-9]{3}"));
            eq("a code and a proof together are refused", 400, s.post("127.0.0.12", "/api/pair",
                    "{\"pin\":\"123456\",\"proof\":\"" + rep('0', 64) + "\",\"c\":\"" + randomC() + "\"}", null).status);
            eq("a malformed c is refused", 400, s.post("127.0.0.12", "/api/pair",
                    "{\"proof\":\"" + rep('0', 64) + "\",\"c\":\"short\",\"name\":\"x\"}", null).status);
        } finally {
            s.stop();
        }
    }

    private static void cookiesOverHttp() throws Exception {
        String sel = "AbCdEfGhIjKlMnOpQrStUv", v1 = "first-verifier-0123456789", v2 = "second-verifier-0123456789";
        long now = System.currentTimeMillis();

        // A key a minute old is replaced in the answer, with the same flags.
        Server s = new Server(device(sel, v1, now - 120000, true, null, 0), null);
        try {
            Resp r = s.get("127.0.0.10", "/api/state", sel + "." + v1);
            String fresh = setCookieValue(r);
            eq("an old key still works", 200, r.status);
            ok("... and the answer carries a new one", fresh != null && fresh.startsWith(sel + ".") && !fresh.equals(sel + "." + v1));
            ok("... with the same flags", r.header("set-cookie") != null
                    && r.header("set-cookie").endsWith("; Path=/; HttpOnly; SameSite=Strict; Max-Age=34560000"));
            r = s.get("127.0.0.10", "/api/state", sel + "." + v1);
            ok("the old key in the 30 s of grace works, with no second new key", r.status == 200 && r.header("set-cookie") == null);
            eq("the new key works", 200, s.get("127.0.0.10", "/api/state", fresh).status);
        } finally {
            s.stop();
        }

        // Errors carry the new key too.
        s = new Server(device(sel, v1, now - 120000, true, null, 0), null);
        try {
            Resp r = s.get("127.0.0.10", "/api/nothing", sel + "." + v1);
            ok("a 404 still hands over the new key", r.status == 404 && setCookieValue(r) != null);
        } finally {
            s.stop();
        }

        // The previous key, after its grace and once the new one was used, is a copy.
        s = new Server(device(sel, v2, now - 5000, true, v1, now - 1000), null);
        try {
            Resp r = s.get("127.0.0.66", "/api/state", sel + "." + v1);
            eq("a copied key", "401 reused", r.status + " " + Json.str(r.json(), "error", ""));
            ok("... is dropped from the browser", r.header("set-cookie") != null && r.header("set-cookie").contains("Max-Age=0"));
            eq("... and signs the device out everywhere", 401, s.get("127.0.0.10", "/api/state", sel + "." + v2).status);
            eq("... which the TV hears about", "[Alex's iPhone was signed out: its key was used from two places]", s.keys.events().toString());
        } finally {
            s.stop();
        }

        s = new Server(device(sel, v2, now - 5000, true, v1, now - 1000), null);
        try {
            eq("hello with a copied key", 401, s.get("127.0.0.66", "/api/hello", sel + "." + v1).status);
            eq("... signs out too", 0, s.keys.count());
        } finally {
            s.stop();
        }

        s = new Server(device(sel, v1, now, false, null, 0), null);
        try {
            s.get("127.0.0.10", "/api/state", sel + "." + v1);
            s.get("127.0.0.20", "/api/state", sel + "." + v1);
            eq("a device at a new address is an event", "[Alex's iPhone is now at 127.0.0.20]", s.keys.events().toString());
        } finally {
            s.stop();
        }
    }

    // ---- M1: slow clients ---------------------------------------------------------------------------

    private interface Body {
        void run() throws Exception;
    }

    private static Thread side(final String what, final Body b) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    b.run();
                } catch (Exception | AssertionError e) {
                    ok(what + ": " + e, false);
                }
            }
        }, what);
        t.start();
        return t;
    }

    /** Sends {@code bytes} one at a time every {@code everyMs}; returns ms until the server hung up. */
    private static long drip(int port, String src, byte[] bytes, long everyMs) throws IOException {
        Socket s = connect(port, src);
        long t0 = System.nanoTime();
        try {
            s.setSoTimeout((int) everyMs);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            for (int i = 0; i < bytes.length; i++) {
                try {
                    out.write(bytes[i]);
                    out.flush();
                } catch (IOException e) {
                    break;
                }
                try {
                    in.read();
                    // an answer (408) or the end: the server let go
                    break;
                } catch (SocketTimeoutException e) {
                    // still open: next byte
                } catch (IOException e) {
                    break;
                }
            }
            return (System.nanoTime() - t0) / 1000000L;
        } finally {
            s.close();
        }
    }

    /** Whether the server has closed {@code s}, waiting at most {@code ms} for it. */
    private static boolean closed(Socket s, int ms) {
        try {
            s.setSoTimeout(ms);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[65536];
            while (true) {
                if (in.read(buf) < 0) return true;
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static void slowClients() throws Exception {
        List<Thread> all = new ArrayList<>();
        final byte[] head = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        // A head sent a byte a second is cut at 5 s in all, though no single read waits 5 s.
        all.add(side("head deadline", new Body() {
            @Override
            public void run() throws Exception {
                Server s = new Server(null, null);
                try {
                    long ms = drip(s.port, "127.0.0.30", head, 1000);
                    ok("a head dripped a byte a second is cut at 5 s (after " + ms + " ms)", ms >= 4000 && ms <= 7000);
                } finally {
                    s.stop();
                }
            }
        }));

        // The audit's slowloris: two drips and two clients that never read a large file, all from one
        // address. Someone else is answered at once, and the stuck writes are cut.
        all.add(side("starvation", new Body() {
            @Override
            public void run() throws Exception {
                final Server s = new Server(null, new byte[32 * 1024 * 1024]);
                List<Socket> held = new ArrayList<>();
                try {
                    for (int i = 0; i < 2; i++) {
                        Socket d = connect(s.port, "127.0.0.31");
                        d.getOutputStream().write('G');
                        held.add(d);
                        Socket n = new Socket();
                        n.setReceiveBufferSize(1024);
                        n.bind(new InetSocketAddress("127.0.0.31", 0));
                        n.connect(new InetSocketAddress("127.0.0.1", s.port), 3000);
                        n.getOutputStream().write(raw(s.port, "GET", "/fonts/inter-medium.ttf", null, null));
                        held.add(n);
                    }
                    Thread.sleep(1500);
                    for (int i = 0; i < 3; i++) {
                        Resp r = s.get("127.0.0.3" + (2 + i), "/api/hello", null);
                        ok("another address is answered while one stalls (" + r.ms + " ms)", r.status == 200 && r.ms < 1500);
                    }
                    Thread.sleep(21000);
                    Socket stuck = held.get(1);
                    long t0 = System.nanoTime();
                    ByteArrayOutputStream drained = new ByteArrayOutputStream();
                    try {
                        stuck.setSoTimeout(3000);
                        InputStream in = stuck.getInputStream();
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) drained.write(buf, 0, n);
                    } catch (IOException e) {
                        // reset is a cut as well
                    }
                    ok("a write the client never reads is cut by 20 s (" + drained.size() + " of 32 MB got through)",
                            drained.size() < 32 * 1024 * 1024 && (System.nanoTime() - t0) / 1000000L < 3000);
                } finally {
                    for (Socket x : held) x.close();
                    s.stop();
                }
            }
        }));

        // One address: six connections at most, two of them served without a key at a time.
        all.add(side("per address", new Body() {
            @Override
            public void run() throws Exception {
                Server s = new Server(null, null);
                List<Socket> idle = new ArrayList<>();
                try {
                    for (int i = 0; i < Console.MAX_PER_PEER; i++) idle.add(connect(s.port, "127.0.0.40"));
                    Thread.sleep(300);
                    Socket seventh = connect(s.port, "127.0.0.40");
                    ok("a seventh connection from one address is refused", closed(seventh, 1500));
                    seventh.close();
                    Resp r = s.get("127.0.0.41", "/api/hello", null);
                    ok("six idle connections from one address hold only two threads (" + r.ms + " ms)", r.status == 200 && r.ms < 1000);
                    boolean allClosed = true;
                    for (Socket x : idle) allClosed &= closed(x, 12000);
                    ok("those waiting in line are cut too", allClosed);
                } finally {
                    for (Socket x : idle) x.close();
                }
                try {
                    // A third request without a key waits for one of the first two to end.
                    Socket a = connect(s.port, "127.0.0.42"), b = connect(s.port, "127.0.0.42");
                    Resp third = s.get("127.0.0.42", "/api/hello", null);
                    ok("a third request without a key waits its turn (" + third.ms + " ms)", third.status == 200 && third.ms >= 3500);
                    a.close();
                    b.close();
                    // A request that showed a key does not hold up its address.
                    String sel = "AbCdEfGhIjKlMnOpQrStUv", v = "photo-verifier-0123456789";
                    Server p = new Server(device(sel, v, System.currentTimeMillis(), true, null, 0), null);
                    List<Socket> uploads = new ArrayList<>();
                    try {
                        for (int i = 0; i < 2; i++) {
                            Socket u = connect(p.port, "127.0.0.44");
                            String h = "POST /api/photo HTTP/1.1\r\nHost: 127.0.0.1:" + p.port + "\r\nX-Lucent: 1\r\nOrigin: http://127.0.0.1:"
                                    + p.port + "\r\nContent-Type: image/jpeg\r\nContent-Length: 1000000\r\nCookie: lucent=" + sel + "." + v + "\r\n\r\nxx";
                            u.getOutputStream().write(h.getBytes(StandardCharsets.US_ASCII));
                            uploads.add(u);
                        }
                        Thread.sleep(500);
                        Resp q = p.get("127.0.0.44", "/api/hello", null);
                        ok("uploads with a key leave room for more (" + q.ms + " ms)", q.status == 200 && q.ms < 1000);
                        // A page load: six files at once (a browser's most) from an address with no key all arrive.
                        final AtomicInteger good = new AtomicInteger();
                        final int port = p.port;
                        List<Thread> files = new ArrayList<>();
                        for (int i = 0; i < Console.MAX_PER_PEER; i++) {
                            final String path = i % 2 == 0 ? "/app.css" : "/fonts/geist-mono.ttf";
                            Thread t = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (call(port, "127.0.0.45", "GET", path, null, null).status == 200) good.incrementAndGet();
                                    } catch (IOException e) {
                                        // counted as missing
                                    }
                                }
                            });
                            t.start();
                            files.add(t);
                        }
                        for (Thread t : files) t.join();
                        eq("six files at once without a key all load", Console.MAX_PER_PEER, good.get());
                    } finally {
                        for (Socket u : uploads) u.close();
                        p.stop();
                    }
                } finally {
                    s.stop();
                }
            }
        }));

        // The pace table forgets the least recently used, never everyone at once.
        all.add(side("pace table", new Body() {
            @Override
            public void run() throws Exception {
                Server s = new Server(null, null);
                try {
                    for (int i = 1; i <= 300; i++) {
                        if (i % 50 == 1) s.get("127.0.0.77", "/icon.svg", null);
                        s.get("127.0." + (1 + i / 200) + "." + (1 + i % 200), "/icon.svg", null);
                    }
                    ok("an address in use is kept", s.console.paced("127.0.0.77"));
                    ok("recent addresses are kept", s.console.paced("127.0.2.101") && s.console.paced("127.0.1.200"));
                    ok("the least recently used is forgotten", !s.console.paced("127.0.1.2"));
                } finally {
                    s.stop();
                }
            }
        }));

        for (Thread t : all) t.join();
    }
}
