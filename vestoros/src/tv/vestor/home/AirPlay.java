package tv.vestor.home;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows the Samsung's own AirPlay to every Apple device near the TV, on a network that hides it.
 *
 * Apple devices find an AirPlay TV by Bonjour, which MIT's Wi-Fi blocks between devices. Apple TVs
 * also announce themselves over Bluetooth LE: an "AirPlay Target" message in Apple's manufacturer
 * data carries the receiver's IPv4 address and port, and an iPhone, iPad or Mac that hears it asks
 * that address for /info over the network and lists the receiver. Cisco's Webex boards and UxPlay
 * use the same message for the same reason. MIT routes ordinary traffic between its subnets, so a
 * beacon from the Fire TV pointing at the Samsung is enough: the Samsung answers and pairs (with
 * the code it shows the first time) exactly as it would at home. Nothing here relays a picture.
 *
 * The Samsung's AirPlay listens on a port it picks itself and announces it only over Bonjour, so
 * this remembers the port and checks it every few minutes. When the TV answers on the network but
 * no longer there, it looks for the port that answers as AirTunes: 7000, then the ports Linux hands
 * out (32768 to 60999), at a gentle pace and at most once an hour. Asked from the control center,
 * it tries every port. Only the address set for the Samsung is ever dialled, a home address
 * checked by {@link SamsungRemote#address}.
 */
final class AirPlay {
    /** Bluetooth's company id for Apple, under which the message goes. */
    static final int APPLE = 0x004C;
    /** What a check found: the port answers as AirPlay, the TV refuses it (so the TV is up), or nothing answers. */
    static final int YES = 1, CLOSED = 2, DOWN = 3;
    static final String OFF = "off", NO_ADDRESS = "noaddress", LOOKING = "looking", ON = "on", AWAY = "away",
            NOT_FOUND = "notfound";
    static final String PORT_KEY = "airplay_port", SCAN_KEY = "airplay_scan_at";

    /** The ports Linux gives a server that asks for any port, where this Samsung's has been. */
    static final int[][] USUAL = {{7000, 7000}, {32768, 60999}};
    static final int[][] EVERY = {{7000, 7000}, {32768, 60999}, {1024, 6999}, {7001, 32767}, {61000, 65535}};

    static final long CHECK_MS = 5 * 60000L, AWAY_MS = 2 * 60000L, SCAN_GAP_MS = 60 * 60000L;
    private static final int PER_SECOND = 400, THREADS = 16, CONNECT_MS = 700, ANSWER_MS = 2500;
    private static final long SCAN_LIMIT_MS = 4 * 60000L;

    /** The Bluetooth side, on the TV through Android's advertiser (see Beacon). */
    interface Radio {
        /** Starts or keeps broadcasting {@code data}. A different message replaces the one on air. */
        void show(byte[] data);

        void hide();

        /** "on", "starting", "off", or why not: "unsupported", "bluetooth", "refused", "failed". */
        String status();
    }

    interface Store {
        String get(String key);

        void put(String key, String value);

        /** Whether the owner left the beacon on. */
        boolean enabled();

        /** The Samsung's address, or null when none is set or it is not a home address. */
        InetAddress samsung();
    }

    private final Store store;
    private final Radio radio;
    private final Object lock = new Object();
    private volatile String state = OFF;
    private volatile long checkedAt, nextAt;
    private volatile int port;
    private volatile String address = "";
    private boolean busy, wide;
    private Runnable changed;
    /** Where and how fast a search looks. Only the desk tests change these. */
    int[][] usual = USUAL, every = EVERY;
    int perSecond = PER_SECOND;

    AirPlay(Store store, Radio radio) {
        this.store = store;
        this.radio = radio;
        port = number(store.get(PORT_KEY));
    }

    /** Runs whenever what the page or the TV shows may have changed. */
    void onChange(Runnable r) {
        changed = r;
    }

    String state() {
        return state;
    }

    String radio() {
        return radio.status();
    }

    int port() {
        return port;
    }

    String address() {
        return address;
    }

    long checkedAt() {
        return checkedAt;
    }

    /** Whether Apple devices near the TV should be finding the Samsung right now. */
    boolean showing() {
        return ON.equals(state) && "on".equals(radio.status());
    }

    /** Called every 30 s. Checks on its own thread when a check is due. */
    void tick(long now) {
        synchronized (lock) {
            if (busy || now < nextAt) return;
            busy = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(System.currentTimeMillis());
                } catch (RuntimeException e) {
                    // the next tick tries again
                } finally {
                    synchronized (lock) {
                        busy = false;
                    }
                    Runnable r = changed;
                    if (r != null) r.run();
                }
            }
        }, "airplay");
        t.setDaemon(true);
        t.start();
    }

    /** Checks again at the next tick: the setting or the Samsung's address changed. */
    void kick() {
        nextAt = 0;
    }

    /**
     * Checks at the next tick, and if the TV is on but its AirPlay is not where it was, looks for
     * it on every port, however recently it last looked.
     */
    void findNow() {
        synchronized (lock) {
            wide = true;
        }
        store.put(SCAN_KEY, "0");
        nextAt = 0;
    }

    /** One check. Runs off the main thread: a search can take a few minutes. */
    void check(long now) {
        boolean all;
        synchronized (lock) {
            all = wide;
            wide = false;
        }
        checkedAt = now;
        if (!store.enabled()) {
            radio.hide();
            state = OFF;
            nextAt = now + CHECK_MS;
            return;
        }
        InetAddress tv = store.samsung();
        if (tv == null) {
            radio.hide();
            address = "";
            state = NO_ADDRESS;
            nextAt = now + CHECK_MS;
            return;
        }
        address = tv.getHostAddress();
        int at = port > 0 ? port : 7000;
        int r = answers(tv, at, ANSWER_MS);
        if (r == YES) {
            found(tv, at, now);
            return;
        }
        if (r == DOWN) {
            // The TV is off or asleep. The beacon stays on its last port, so it is found the moment it wakes.
            if (port > 0) radio.show(beacon(tv.getAddress(), port));
            state = AWAY;
            nextAt = now + AWAY_MS;
            return;
        }
        long last = millis(store.get(SCAN_KEY));
        if (!all && last > 0 && now - last < SCAN_GAP_MS) {
            radio.hide();
            state = NOT_FOUND;
            nextAt = now + CHECK_MS;
            return;
        }
        store.put(SCAN_KEY, String.valueOf(now));
        state = LOOKING;
        Runnable c = changed;
        if (c != null) c.run();
        int p = find(tv, all ? every : usual, perSecond, System.currentTimeMillis() + SCAN_LIMIT_MS * (all ? 2 : 1));
        if (p > 0) {
            found(tv, p, System.currentTimeMillis());
        } else {
            radio.hide();
            state = NOT_FOUND;
            nextAt = System.currentTimeMillis() + CHECK_MS;
        }
    }

    private void found(InetAddress tv, int p, long now) {
        if (p != port) {
            port = p;
            store.put(PORT_KEY, String.valueOf(p));
        }
        radio.show(beacon(tv.getAddress(), p));
        state = ON;
        nextAt = now + CHECK_MS;
    }

    /**
     * The manufacturer data Apple TVs broadcast: AirPlay Target (0x09), eight bytes long, flags
     * 0x13 (0x10 says a port follows), a seed Apple devices do not check here, the IPv4 address and
     * the port, high byte first.
     */
    static byte[] beacon(byte[] ipv4, int port) {
        if (ipv4 == null || ipv4.length != 4 || port < 1 || port > 65535) throw new IllegalArgumentException("address");
        return new byte[] {0x09, 0x08, 0x13, 0x30, ipv4[0], ipv4[1], ipv4[2], ipv4[3], (byte) (port >> 8), (byte) port};
    }

    /**
     * Whether {@code tv:port} answers GET /info with a Server header naming AirTunes (YES), refuses
     * or answers as something else (CLOSED: the TV is up), or does not answer at all (DOWN).
     */
    static int answers(InetAddress tv, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            try {
                s.connect(new InetSocketAddress(tv, port), Math.min(timeoutMs, 1500));
            } catch (SocketTimeoutException e) {
                return DOWN;
            } catch (IOException e) {
                return refused(e) ? CLOSED : DOWN;
            }
            try {
                s.setSoTimeout(timeoutMs);
                OutputStream out = s.getOutputStream();
                out.write(("GET /info HTTP/1.1\r\nHost: " + tv.getHostAddress() + ":" + port
                        + "\r\nUser-Agent: AirPlay/690.7.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return airTunes(head(s.getInputStream())) ? YES : CLOSED;
            } catch (IOException e) {
                return CLOSED;
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    /** A refusal comes back at once from a TV that is on, whatever the system calls it. */
    private static boolean refused(IOException e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
        return m.contains("refused") || m.contains("econnrefused") || m.contains("reset");
    }

    /** The answer's head, up to the blank line, at most 8 KB. */
    private static String head(InputStream in) throws IOException {
        byte[] b = new byte[8192];
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) break;
            n += r;
            String sofar = new String(b, 0, n, StandardCharsets.ISO_8859_1);
            if (sofar.contains("\r\n\r\n") || sofar.contains("\n\n")) break;
        }
        return new String(b, 0, n, StandardCharsets.ISO_8859_1);
    }

    /** A successful answer whose Server header names AirTunes, Apple's name for an AirPlay receiver. */
    static boolean airTunes(String head) {
        String[] lines = head.split("\r?\n");
        if (lines.length == 0 || !lines[0].matches("(HTTP/1\\.[01]|RTSP/1\\.0) 200( .*)?")) return false;
        for (int i = 1; i < lines.length && !lines[i].isEmpty(); i++) {
            String l = lines[i].toLowerCase(Locale.US);
            if (l.startsWith("server:") && l.substring(7).trim().startsWith("airtunes/")) return true;
        }
        return false;
    }

    /**
     * The first port in {@code ranges} (inclusive pairs) that answers as AirTunes, or 0. Ports are
     * tried at about {@code perSecond} a second from a few threads, and only a port that accepts a
     * connection is asked for /info. Gives up at {@code deadline}.
     */
    static int find(final InetAddress tv, int[][] ranges, int perSecond, long deadline) {
        final AtomicInteger hit = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "airplay-find");
                t.setDaemon(true);
                return t;
            }
        });
        long gapNs = 1000000000L / Math.max(1, perSecond);
        long next = System.nanoTime();
        try {
            outer:
            for (int[] range : ranges) {
                for (int p = range[0]; p <= range[1]; p++) {
                    if (hit.get() > 0 || System.currentTimeMillis() > deadline) break outer;
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break outer;
                        }
                    }
                    next += gapNs;
                    final int port = p;
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (hit.get() > 0 || !accepts(tv, port)) return;
                            if (answers(tv, port, ANSWER_MS) == YES) hit.compareAndSet(0, port);
                        }
                    });
                }
            }
        } finally {
            pool.shutdown();
        }
        try {
            pool.awaitTermination(ANSWER_MS + CONNECT_MS * 2L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return hit.get();
    }

    private static boolean accepts(InetAddress tv, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(tv, port), CONNECT_MS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    /** A port saved earlier, or 0. */
    private static int number(String s) {
        long v = millis(s);
        return v > 65535 ? 0 : (int) v;
    }

    private static long millis(String s) {
        return s == null || !s.matches("[0-9]{1,15}") ? 0 : Long.parseLong(s);
    }
}
