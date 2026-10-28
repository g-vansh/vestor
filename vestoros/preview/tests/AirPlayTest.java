package tv.vestor.home;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Checks the AirPlay beacon: the bytes Apple devices read, telling an AirTunes answer from any
 * other, a port search that skips other servers, and a beacon that follows the Samsung (on, away,
 * moved, missing, turned off) with small servers on this computer standing in for the TV.
 */
public final class AirPlayTest {
    private static int failures, checks;
    private static final String AIRTUNES = "HTTP/1.1 200 OK\r\nServer: AirTunes/377.25.06\r\nContent-Length: 0\r\n\r\n";
    private static final String OTHER = "HTTP/1.1 200 OK\r\nServer: nginx\r\nContent-Length: 0\r\n\r\n";

    public static void main(String[] args) throws Exception {
        beacon();
        head();
        InetAddress here = InetAddress.getLoopbackAddress();
        ServerSocket tv = serve(AIRTUNES), other = serve(OTHER);
        int closed = closedPort();
        answers(here, tv.getLocalPort(), other.getLocalPort(), closed);
        find(here, tv.getLocalPort(), other.getLocalPort(), closed);
        cycle(here, tv, other.getLocalPort(), closed);
        tv.close();
        other.close();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    private static void beacon() {
        byte[] b = AirPlay.beacon(new byte[] {10, 29, (byte) 147, 80}, 46977);
        eq("beacon bytes", "09081330 0a1d9350 b781", hex(b));
        eq("Apple's company id", 0x004C, AirPlay.APPLE);
        eq("port 7000 high byte first", "1b58", hex(Arrays.copyOfRange(AirPlay.beacon(new byte[] {(byte) 192, (byte) 168, 1, (byte) 253}, 7000), 8, 10)));
        refused("no address", null, 7000);
        refused("IPv6", new byte[16], 7000);
        refused("port 0", new byte[4], 0);
        refused("port too high", new byte[4], 65536);
    }

    private static void head() {
        ok("AirTunes", AirPlay.airTunes(AIRTUNES));
        ok("RTSP answer", AirPlay.airTunes("RTSP/1.0 200 OK\r\nServer: AirTunes/220.68\r\n\r\n"));
        ok("header case", AirPlay.airTunes("HTTP/1.1 200 OK\r\nSERVER:   AirTunes/1.0\r\n\r\n"));
        ok("other server", !AirPlay.airTunes(OTHER));
        ok("not found", !AirPlay.airTunes("HTTP/1.1 404 Not Found\r\nServer: AirTunes/377.25.06\r\n\r\n"));
        ok("AirTunes only in the body", !AirPlay.airTunes("HTTP/1.1 200 OK\r\nServer: x\r\n\r\nServer: AirTunes/1\r\n"));
        ok("look-alike name", !AirPlay.airTunes("HTTP/1.1 200 OK\r\nServer: NotAirTunes/1\r\n\r\n"));
        ok("empty", !AirPlay.airTunes(""));
    }

    private static void answers(InetAddress here, int tv, int other, int closed) throws IOException {
        eq("the TV answers", AirPlay.YES, AirPlay.answers(here, tv, 2000));
        eq("another server", AirPlay.CLOSED, AirPlay.answers(here, other, 2000));
        eq("a refused port means the TV is up", AirPlay.CLOSED, AirPlay.answers(here, closed, 2000));
        // TEST-NET-1 is never anyone's: nothing answers there.
        eq("nothing there", AirPlay.DOWN, AirPlay.answers(InetAddress.getByName("192.0.2.1"), 7000, 600));
    }

    private static void find(InetAddress here, int tv, int other, int closed) {
        long later = System.currentTimeMillis() + 30000;
        eq("skips the other server", tv, AirPlay.find(here, new int[][] {{other, other}, {closed, closed}, {tv, tv}}, 1000, later));
        eq("not there", 0, AirPlay.find(here, new int[][] {{other, other}, {closed, closed}}, 1000, later));
        eq("past the deadline", 0, AirPlay.find(here, new int[][] {{tv, tv}}, 1000, System.currentTimeMillis() - 1));
    }

    /** The beacon following the Samsung as it is found, sleeps, moves and goes missing. */
    private static void cycle(InetAddress here, ServerSocket tv, int other, int closed) throws Exception {
        final Map<String, String> saved = new HashMap<>();
        final boolean[] on = {false};
        final InetAddress[] samsung = {null};
        FakeRadio radio = new FakeRadio();
        // A Mac's own AirPlay Receiver answers on 7000, so the first check starts from a refused port instead.
        saved.put(AirPlay.PORT_KEY, String.valueOf(closed));
        AirPlay a = new AirPlay(new AirPlay.Store() {
            @Override
            public String get(String key) {
                return saved.get(key);
            }

            @Override
            public void put(String key, String value) {
                saved.put(key, value);
            }

            @Override
            public boolean enabled() {
                return on[0];
            }

            @Override
            public InetAddress samsung() {
                return samsung[0];
            }
        }, radio);
        a.perSecond = 1000;
        a.usual = new int[][] {{other, other}, {closed, closed}, {tv.getLocalPort(), tv.getLocalPort()}};
        a.every = a.usual;
        long t = 1000000L;

        a.check(t);
        eq("off by setting", AirPlay.OFF, a.state());
        ok("nothing on air", radio.data == null);

        on[0] = true;
        a.check(t);
        eq("no address", AirPlay.NO_ADDRESS, a.state());

        samsung[0] = here;
        a.check(t);
        eq("found by searching", AirPlay.ON, a.state());
        eq("port kept", String.valueOf(tv.getLocalPort()), saved.get(AirPlay.PORT_KEY));
        eq("on air", hex(AirPlay.beacon(here.getAddress(), tv.getLocalPort())), hex(radio.data));
        ok("showing", a.showing());

        // Checked again: the kept port answers, so no search.
        String searched = saved.get(AirPlay.SCAN_KEY);
        a.check(t + AirPlay.CHECK_MS);
        eq("still on", AirPlay.ON, a.state());
        eq("no new search", searched, saved.get(AirPlay.SCAN_KEY));

        // The TV moved its AirPlay within the hour: no second search yet, and nothing points at a dead port.
        AirPlay b = new AirPlay(storeWith(saved, on, samsung, String.valueOf(closed)), radio);
        b.usual = a.usual;
        b.perSecond = 1000;
        saved.put(AirPlay.SCAN_KEY, String.valueOf(t));
        b.check(t + 10 * 60000L);
        eq("moved, too soon to search", AirPlay.NOT_FOUND, b.state());
        ok("beacon off while missing", radio.data == null);
        b.check(t + AirPlay.SCAN_GAP_MS + 1);
        eq("an hour later it searches", AirPlay.ON, b.state());
        eq("new port kept", String.valueOf(tv.getLocalPort()), saved.get(AirPlay.PORT_KEY));

        // Asked from the control center: searches however recently it last did.
        AirPlay c = new AirPlay(storeWith(saved, on, samsung, String.valueOf(closed)), radio);
        c.every = a.usual;
        c.perSecond = 1000;
        saved.put(AirPlay.SCAN_KEY, String.valueOf(t + AirPlay.SCAN_GAP_MS + 1));
        c.findNow();
        c.check(t + AirPlay.SCAN_GAP_MS + 2);
        eq("find now", AirPlay.ON, c.state());

        // The TV asleep: nothing answers, the beacon stays on the last port.
        samsung[0] = InetAddress.getByName("192.0.2.1");
        AirPlay d = new AirPlay(storeWith(saved, on, samsung, "46977"), radio);
        d.check(t);
        eq("away", AirPlay.AWAY, d.state());
        eq("beacon kept on the last port", hex(AirPlay.beacon(samsung[0].getAddress(), 46977)), hex(radio.data));
        ok("not showing while away", !d.showing());

        on[0] = false;
        d.check(t);
        eq("turned off", AirPlay.OFF, d.state());
        ok("off air", radio.data == null);
    }

    private static AirPlay.Store storeWith(final Map<String, String> saved, final boolean[] on, final InetAddress[] samsung, String port) {
        saved.put(AirPlay.PORT_KEY, port);
        return new AirPlay.Store() {
            @Override
            public String get(String key) {
                return saved.get(key);
            }

            @Override
            public void put(String key, String value) {
                saved.put(key, value);
            }

            @Override
            public boolean enabled() {
                return on[0];
            }

            @Override
            public InetAddress samsung() {
                return samsung[0];
            }
        };
    }

    private static final class FakeRadio implements AirPlay.Radio {
        byte[] data;

        @Override
        public void show(byte[] d) {
            data = d.clone();
        }

        @Override
        public void hide() {
            data = null;
        }

        @Override
        public String status() {
            return data == null ? "off" : "on";
        }
    }

    /** A server on this computer that answers every request with {@code reply}. */
    private static ServerSocket serve(final String reply) throws IOException {
        final ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try (Socket c = s.accept()) {
                        c.setSoTimeout(2000);
                        InputStream in = c.getInputStream();
                        byte[] b = new byte[4096];
                        int n = 0;
                        while (n < b.length) {
                            int r = in.read(b, n, b.length - n);
                            if (r < 0) break;
                            n += r;
                            if (new String(b, 0, n, StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) break;
                        }
                        OutputStream out = c.getOutputStream();
                        if (n > 0) out.write(reply.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (IOException e) {
                        // the next connection
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return s;
    }

    private static int closedPort() throws IOException {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int p = s.getLocalPort();
        s.close();
        return p;
    }

    private static void refused(String what, byte[] ip, int port) {
        checks++;
        try {
            AirPlay.beacon(ip, port);
            failures++;
            System.out.println("FAIL " + what + ": accepted");
        } catch (IllegalArgumentException e) {
            // refused, as it should be
        }
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0 && i % 4 == 0) s.append(' ');
            s.append(String.format("%02x", b[i] & 0xFF));
        }
        return s.toString();
    }

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static void ok(String what, boolean cond) {
        eq(what, true, cond);
    }
}
