package tv.vestor.home;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * A small WebSocket client (RFC 6455) for one job: the Samsung TV's remote control channel on the
 * home network ({@link SamsungRemote}). It speaks text messages and nothing else, and refuses what
 * a TV would never send before it can cost anything:
 *
 * - The upgrade answer is checked to the letter: status 101, Upgrade and Connection, and the
 *   Sec-WebSocket-Accept that proves the server read this very request. No extensions and no
 *   subprotocols, since none were asked for. At most 40 header lines and 8 KB, plain ASCII, all
 *   within one deadline.
 * - Frames from the server must be unmasked, use no reserved bits or opcodes and state their
 *   length in the fewest bytes. A frame holds at most 64 KB, and so does a message put together
 *   from fragments, checked before anything is allocated. Control frames are whole and short,
 *   text is valid UTF-8, and once a frame has begun the rest of it must arrive within 10 seconds.
 * - Anything else closes the channel with the matching status code and an IOException.
 * - Every frame Vestor sends is masked with a fresh random key, as the RFC requires of clients.
 *   Pings are answered as they arrive and binary messages are dropped. {@link #quietMs} says how
 *   long the server has been silent, so a caller can ping before trusting an old channel.
 *
 * Platform-free: it runs over any connected socket, plain or TLS, so the desktop tests can point
 * it at a fake TV.
 */
final class Ws {
    static final int MAX_FRAME = 64 * 1024, MAX_MESSAGE = 64 * 1024, MAX_LINE = 2048, MAX_HEAD = 8192, MAX_HEADERS = 40;
    static final long FRAME_MS = 10000;
    /** Close codes: normal, protocol error, not UTF-8, too big. */
    static final int NORMAL = 1000, PROTOCOL = 1002, BAD_DATA = 1007, TOO_BIG = 1009;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random;
    private final Object writing = new Object();
    private boolean closeSent;
    private volatile boolean closed;
    /** A fragmented message still arriving, kept across read timeouts. */
    private ByteArrayOutputStream partial;
    private int partialOp;
    private volatile int closeCode;
    private volatile long heard = System.nanoTime();

    private Ws(Socket socket, InputStream in, OutputStream out, SecureRandom random) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.random = random;
    }

    /**
     * Opens the channel on a connected socket: sends the upgrade request for {@code target} (a path
     * and query) and checks the answer. {@code host} is the Host header, an address and port.
     */
    static Ws open(Socket socket, String host, String target, int timeoutMs, SecureRandom random) throws IOException {
        if (!printable(host) || !printable(target) || !target.startsWith("/")) throw new IOException("bad request");
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET " + target + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: " + Net.AGENT + "\r\n\r\n";
        socket.setSoTimeout(timeoutMs);
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
        String status = line(in, deadline);
        if (!status.startsWith("HTTP/1.1 101") || status.length() > 12 && status.charAt(12) != ' ') {
            throw new IOException("no upgrade: " + Text.clean(status, 40));
        }
        String expect = accept(key);
        boolean upgrade = false, connection = false, accepted = false;
        int lines = 0, bytes = 0;
        for (String h = line(in, deadline); !h.isEmpty(); h = line(in, deadline)) {
            bytes += h.length() + 2;
            if (++lines > MAX_HEADERS || bytes > MAX_HEAD) throw new IOException("head too large");
            int colon = h.indexOf(':');
            if (colon <= 0 || h.charAt(0) == ' ' || h.charAt(0) == '\t') throw new IOException("bad header");
            String name = h.substring(0, colon).trim().toLowerCase(Locale.US), value = h.substring(colon + 1).trim();
            if ("upgrade".equals(name)) {
                if (upgrade || !"websocket".equalsIgnoreCase(value)) throw new IOException("bad Upgrade");
                upgrade = true;
            } else if ("connection".equals(name)) {
                for (String t : value.split(",")) if ("upgrade".equalsIgnoreCase(t.trim())) connection = true;
            } else if ("sec-websocket-accept".equals(name)) {
                if (accepted || !expect.equals(value)) throw new IOException("wrong Sec-WebSocket-Accept");
                accepted = true;
            } else if ("sec-websocket-extensions".equals(name) || "sec-websocket-protocol".equals(name)) {
                throw new IOException("unasked " + name);
            }
        }
        if (!upgrade || !connection || !accepted) throw new IOException("not a WebSocket answer");
        return new Ws(socket, in, out, random);
    }

    /** base64(SHA-1(key + GUID)): what the server must answer to the key Vestor sent. */
    static String accept(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean printable(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_LINE) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) <= ' ' || s.charAt(i) > '~') return false;
        return true;
    }

    /**
     * One line of an HTTP head: printable ASCII, ended by CRLF or LF, at most 2048 bytes, and
     * read before {@code deadline} (System.nanoTime) however the bytes are spread out.
     */
    static String line(InputStream in, long deadline) throws IOException {
        StringBuilder b = new StringBuilder(64);
        boolean cr = false;
        while (true) {
            if (System.nanoTime() - deadline > 0) throw new SocketTimeoutException("head too slow");
            int c = in.read();
            if (c < 0) throw new EOFException("closed in the head");
            if (c == '\n') return b.toString();
            if (cr) throw new IOException("bare CR");
            if (c == '\r') {
                cr = true;
                continue;
            }
            if ((c < 0x20 && c != '\t') || c > 0x7E) throw new IOException("bad byte in head");
            if (b.length() >= MAX_LINE) throw new IOException("line too long");
            b.append((char) c);
        }
    }

    /** The read timeout between messages. */
    void timeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    boolean open() {
        return !closed && !socket.isClosed();
    }

    /** How long since anything at all came from the server (or since opening). */
    long quietMs() {
        return (System.nanoTime() - heard) / 1000000L;
    }

    /** When (System.nanoTime) the server last sent anything. */
    long heardAt() {
        return heard;
    }

    /** Asks for a pong: {@link #heardAt} moves when it comes. */
    void ping() throws IOException {
        write(0x9, new byte[0]);
    }

    /** The status code the TV closed with, or 0. */
    int closeCode() {
        return closeCode;
    }

    // ---- Reading -----------------------------------------------------------------------------

    /**
     * The next text message, or null once the channel has closed. Pings are answered and binary
     * messages dropped on the way. A SocketTimeoutException means nothing new arrived within the
     * socket's timeout and nothing was lost: call again. Any other IOException has closed the
     * channel. One reader at a time.
     */
    String read() throws IOException {
        while (true) {
            if (closed) return null;
            int b0;
            try {
                b0 = in.read();
            } catch (SocketTimeoutException idle) {
                throw idle;
            } catch (IOException e) {
                shut();
                throw e;
            }
            if (b0 < 0) {
                shut();
                return null;
            }
            heard = System.nanoTime();
            String text;
            try {
                text = frame(b0, System.nanoTime() + FRAME_MS * 1000000L);
            } catch (SocketTimeoutException stalled) {
                throw fail(PROTOCOL, "frame stalled");
            } catch (IOException e) {
                shut();
                throw e;
            }
            if (text != null) return text;
        }
    }

    /** One frame: a whole text message when this completes one, else null. */
    private String frame(int b0, long deadline) throws IOException {
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0F;
        if ((b0 & 0x70) != 0) throw fail(PROTOCOL, "reserved bits");
        int b1 = next(deadline);
        if ((b1 & 0x80) != 0) throw fail(PROTOCOL, "masked frame from the server");
        long len = b1 & 0x7F;
        if (len == 126) {
            len = next(deadline) << 8 | next(deadline);
            if (len < 126) throw fail(PROTOCOL, "long form for a short length");
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = len << 8 | next(deadline);
            if (len < 0) throw fail(PROTOCOL, "length out of range");
            if (len < 65536) throw fail(PROTOCOL, "long form for a short length");
        }
        if (op >= 8) {
            if (op > 10) throw fail(PROTOCOL, "unknown opcode " + op);
            if (!fin) throw fail(PROTOCOL, "fragmented control frame");
            if (len > 125) throw fail(PROTOCOL, "long control frame");
        } else if (op > 2) {
            throw fail(PROTOCOL, "unknown opcode " + op);
        }
        if (len > MAX_FRAME) throw fail(TOO_BIG, "frame too large");
        byte[] payload = new byte[(int) len];
        fill(payload, deadline);
        switch (op) {
            case 0x9:
                write(0xA, payload);
                return null;
            case 0xA:
                return null;
            case 0x8:
                closing(payload);
                return null;
            case 0x0:
                if (partial == null) throw fail(PROTOCOL, "continuation without a start");
                break;
            default:
                if (partial != null) throw fail(PROTOCOL, "a message inside a message");
                partial = new ByteArrayOutputStream(Math.max(64, payload.length));
                partialOp = op;
                break;
        }
        if (partial.size() + payload.length > MAX_MESSAGE) throw fail(TOO_BIG, "message too large");
        partial.write(payload, 0, payload.length);
        if (!fin) return null;
        byte[] all = partial.toByteArray();
        int kind = partialOp;
        partial = null;
        if (kind == 0x2) return null;
        String text = utf8(all);
        if (text == null) throw fail(BAD_DATA, "text is not UTF-8");
        return text;
    }

    private static String utf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** The TV closes: answer with its code (RFC 6455 5.5.1) and let go of the socket. */
    private void closing(byte[] payload) throws IOException {
        if (payload.length == 1) throw fail(PROTOCOL, "one-byte close");
        int code = payload.length >= 2 ? (payload[0] & 0xFF) << 8 | (payload[1] & 0xFF) : 0;
        if (payload.length > 2 && utf8(Arrays.copyOfRange(payload, 2, payload.length)) == null) {
            throw fail(BAD_DATA, "close reason is not UTF-8");
        }
        closeCode = code;
        try {
            write(0x8, code == 0 ? new byte[0] : new byte[] {(byte) (code >> 8), (byte) code});
        } catch (IOException ignored) {
            // the TV may already be gone
        }
        shut();
    }

    private int next(long deadline) throws IOException {
        if (System.nanoTime() - deadline > 0) throw new SocketTimeoutException("frame stalled");
        int b = in.read();
        if (b < 0) throw new EOFException("closed inside a frame");
        return b;
    }

    private void fill(byte[] b, long deadline) throws IOException {
        int off = 0;
        while (off < b.length) {
            if (System.nanoTime() - deadline > 0) throw new SocketTimeoutException("frame stalled");
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("closed inside a frame");
            off += n;
        }
    }

    /** Closes with {@code code} (best effort) and answers the exception to throw. */
    private IOException fail(int code, String why) {
        try {
            write(0x8, new byte[] {(byte) (code >> 8), (byte) code});
            // Closing with unread bytes waiting makes TCP reset the connection, and a reset can
            // throw away the close frame before the TV reads it. So what is left is read and
            // dropped first, for at most 300 ms or 256 KB.
            socket.setSoTimeout(300);
            byte[] sink = new byte[8192];
            long end = System.nanoTime() + 300000000L;
            int total = 0;
            while (System.nanoTime() - end < 0 && total < 262144) {
                int n = in.read(sink);
                if (n < 0) break;
                total += n;
            }
        } catch (IOException ignored) {
            // closing anyway
        }
        shut();
        return new IOException(why);
    }

    // ---- Writing -----------------------------------------------------------------------------

    /** Sends one text message as one masked frame. Safe from any thread. */
    void send(String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_FRAME) throw new IOException("message too long");
        write(0x1, b);
    }

    private void write(int op, byte[] payload) throws IOException {
        int n = payload.length, head = n < 126 ? 2 : n < 65536 ? 4 : 10;
        byte[] f = new byte[head + 4 + n];
        f[0] = (byte) (0x80 | op);
        if (n < 126) {
            f[1] = (byte) (0x80 | n);
        } else if (n < 65536) {
            f[1] = (byte) (0x80 | 126);
            f[2] = (byte) (n >>> 8);
            f[3] = (byte) n;
        } else {
            f[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) f[2 + i] = (byte) ((long) n >>> (56 - 8 * i));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        System.arraycopy(mask, 0, f, head, 4);
        for (int i = 0; i < n; i++) f[head + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        synchronized (writing) {
            if (closeSent) throw new SocketException("closed");
            if (op == 0x8) closeSent = true;
            out.write(f);
            out.flush();
        }
    }

    /** Says goodbye (status 1000) and closes the socket. Safe from any thread, more than once. */
    void close() {
        try {
            write(0x8, new byte[] {(byte) (NORMAL >> 8), (byte) NORMAL});
        } catch (IOException | RuntimeException ignored) {
            // already closed or the TV is gone
        }
        shut();
    }

    private void shut() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
