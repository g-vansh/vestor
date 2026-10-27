package tv.lucent.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A deliberately small HTTP/1.1 reader and writer for the control center. It accepts only what
 * a phone's browser sends to it and refuses the rest before it can cost anything:
 *
 * - GET and POST only, one request per connection (every answer closes it), so there is no
 *   pipelining or keep-alive state to confuse.
 * - A request line of at most 2048 bytes, at most 40 headers and 8 KB of them, plain ASCII,
 *   CRLF or LF line ends, no folded headers, no NUL.
 * - A body only with a single, plain Content-Length, capped per route by the caller. Any
 *   Transfer-Encoding is refused (501), as is Expect (417), so there is no chunked parser to get
 *   wrong and no smuggling between two ideas of where a body ends.
 * - Host, Origin, Cookie, Content-Length and Content-Type may each appear once.
 *
 * Platform-free so the desktop tests can throw hostile bytes at it.
 */
final class Http {
    private Http() {}

    static final int MAX_LINE = 2048, MAX_HEADER_BYTES = 8192, MAX_HEADERS = 40;

    static final class Request {
        String method = "", target = "", path = "", query = "";
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];
        String peer = "";

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    /** A request that cannot be served, with the status to answer it with. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Refused(int status, String why) {
            super(why);
            this.status = status;
        }
    }

    private static final String[] SINGLE = {"host", "origin", "cookie", "content-length", "content-type", "sec-fetch-site",
        "x-lucent"};

    /** Reads the request line and headers. The body is read separately, once its cap is known. */
    static Request head(InputStream in) throws IOException, Refused {
        Request r = new Request();
        String line = line(in, MAX_LINE);
        if (line == null) throw new Refused(400, "empty");
        if (line.startsWith("PRI * HTTP/2")) throw new Refused(505, "http2");
        int a = line.indexOf(' '), b = line.lastIndexOf(' ');
        if (a <= 0 || b <= a) throw new Refused(400, "request line");
        r.method = line.substring(0, a);
        r.target = line.substring(a + 1, b);
        String version = line.substring(b + 1);
        if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) throw new Refused(505, "version");
        if (!"GET".equals(r.method) && !"POST".equals(r.method)) throw new Refused(405, "method");
        // Origin-form only: a path, never an absolute URL that names another host.
        if (r.target.isEmpty() || r.target.charAt(0) != '/' || r.target.length() > 512) throw new Refused(400, "target");
        for (int i = 0; i < r.target.length(); i++) {
            char c = r.target.charAt(i);
            if (c <= 0x20 || c >= 0x7F) throw new Refused(400, "target");
        }
        int q = r.target.indexOf('?');
        r.path = q < 0 ? r.target : r.target.substring(0, q);
        r.query = q < 0 ? "" : r.target.substring(q + 1);
        if (r.query.length() > 256 || r.path.contains("..") || r.path.contains("//") || r.path.indexOf('\\') >= 0
                || r.path.indexOf('%') >= 0) {
            throw new Refused(400, "path");
        }
        int total = 0;
        while (true) {
            String h = line(in, MAX_LINE * 2);
            if (h == null) throw new Refused(400, "headers ended early");
            if (h.isEmpty()) break;
            total += h.length() + 2;
            if (total > MAX_HEADER_BYTES || r.headers.size() >= MAX_HEADERS) throw new Refused(431, "headers too large");
            if (h.charAt(0) == ' ' || h.charAt(0) == '\t') throw new Refused(400, "folded header");
            int colon = h.indexOf(':');
            if (colon <= 0) throw new Refused(400, "header");
            String name = h.substring(0, colon).toLowerCase(Locale.US);
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_')) throw new Refused(400, "header name");
            }
            String value = h.substring(colon + 1).trim();
            if (r.headers.containsKey(name)) {
                for (String s : SINGLE) if (s.equals(name)) throw new Refused(400, "duplicate " + name);
                // Other repeated headers are joined, as HTTP allows. Lucent reads none of them.
                value = r.headers.get(name) + ", " + value;
            }
            r.headers.put(name, value);
        }
        if (r.header("transfer-encoding") != null) throw new Refused(501, "transfer-encoding");
        if (r.header("expect") != null) throw new Refused(417, "expect");
        if (r.header("host") == null) throw new Refused(400, "no host");
        return r;
    }

    /** The declared body length: -1 when there is none, else 0..cap. */
    static int length(Request r, int cap) throws Refused {
        String v = r.header("content-length");
        if (v == null) {
            if ("POST".equals(r.method)) throw new Refused(411, "length required");
            return -1;
        }
        if (v.isEmpty() || v.length() > 9) throw new Refused(400, "content-length");
        for (int i = 0; i < v.length(); i++) if (!Character.isDigit(v.charAt(i))) throw new Refused(400, "content-length");
        int n = Integer.parseInt(v);
        if (n > cap) throw new Refused(413, "too large");
        return n;
    }

    /**
     * Reads exactly {@code n} body bytes, giving up at {@code deadline} (System.nanoTime) or when a
     * slow sender falls under about 16 KB a second after the first 5 seconds.
     */
    static byte[] body(InputStream in, int n, long deadline) throws IOException, Refused {
        if (n <= 0) return new byte[0];
        byte[] out = new byte[n];
        int off = 0;
        long started = System.nanoTime();
        while (off < n) {
            int got = in.read(out, off, Math.min(64 * 1024, n - off));
            if (got < 0) throw new Refused(400, "body ended early");
            off += got;
            long now = System.nanoTime();
            if (now > deadline) throw new Refused(408, "too slow");
            long secs = (now - started) / 1000000000L;
            if (secs >= 5 && off < secs * 16 * 1024) throw new Refused(408, "too slow");
        }
        return out;
    }

    /** One line of ASCII, without its CR LF, or null at the end of the stream. */
    static String line(InputStream in, int max) throws IOException, Refused {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] raw = b.toByteArray();
                int len = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
                return new String(raw, 0, len, StandardCharsets.US_ASCII);
            }
            if (c == 0 || c >= 0x80 || c < 0x20 && c != '\r' && c != '\t') throw new Refused(400, "bad byte");
            if (b.size() >= max) throw new Refused(431, "line too long");
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString("US-ASCII");
    }

    /** Headers every answer carries. The page loads only its own files and is never framed. */
    static final String[] SECURITY = {
        "Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; "
                + "font-src 'self'; connect-src 'self'; manifest-src 'self'; form-action 'self'; frame-ancestors 'none'; "
                + "base-uri 'none'",
        "X-Content-Type-Options", "nosniff",
        "X-Frame-Options", "DENY",
        "Referrer-Policy", "no-referrer",
        "Cross-Origin-Opener-Policy", "same-origin",
        "Cross-Origin-Resource-Policy", "same-origin",
        "Cross-Origin-Embedder-Policy", "require-corp",
        "Permissions-Policy", "camera=(), microphone=(), geolocation=(), usb=(), payment=(), interest-cohort=()",
    };

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 417: return "Expectation Failed";
            case 421: return "Misdirected Request";
            case 423: return "Locked";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            case 505: return "HTTP Version Not Supported";
            default: return "Error";
        }
    }

    /**
     * Writes a complete answer and nothing after it. {@code cache} true lets the browser keep a
     * static file (revalidating each time), false forbids keeping anything.
     */
    static void respond(OutputStream out, int code, String type, byte[] body, boolean cache, String... extra)
            throws IOException {
        StringBuilder h = new StringBuilder(512);
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        h.append("Cache-Control: ").append(cache ? "no-cache" : "no-store").append("\r\n");
        h.append("Connection: close\r\n");
        for (int i = 0; i + 1 < SECURITY.length; i += 2) h.append(SECURITY[i]).append(": ").append(SECURITY[i + 1]).append("\r\n");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            // Header values Lucent builds itself, checked anyway: no line breaks can split a header.
            String v = extra[i + 1];
            if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) continue;
            h.append(extra[i]).append(": ").append(v).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (body != null) out.write(body);
        out.flush();
    }

    /** A cookie's value from a Cookie header, or null. */
    static String cookie(Request r, String name) {
        String c = r.header("cookie");
        if (c == null) return null;
        for (String part : c.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1);
        }
        return null;
    }
}
