package tv.lucent.home;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which phones and laptops may use the control center, and how a new one is let in.
 *
 * Pairing needs someone at the TV. Only the TV opens a pairing window (Settings, or the phone
 * card), for two minutes. It shows a QR code whose link carries a one-time secret in the part of
 * the address browsers never send over the network, and a six-digit code for a laptop without a
 * camera. A wrong code counts against the window, five wrong codes close it, and three closed
 * windows in an hour lock pairing for fifteen minutes. A right code or a scanned secret still
 * only asks: the TV shows the device's name and nothing happens until someone presses OK on the
 * remote.
 *
 * An approved device gets a key in an HttpOnly cookie: a 16-byte selector and a 32-byte secret.
 * Lucent keeps only a SHA-256 of the secret, compares in constant time, and forgets a device on
 * Revoke. At most 20 devices. Platform-free: storage is handed in.
 */
final class Keys {
    interface Store {
        String load();

        void save(String json);
    }

    static final class Device {
        String selector = "", hash = "", name = "", ip = "";
        long created, seen;
    }

    /** A device waiting for the OK on the remote. */
    static final class Pending {
        String id = "", name = "", ip = "", cookie = "";
        long at;
        /** 0 waiting, 1 allowed, 2 refused or expired. */
        int state;
    }

    static final int MAX_DEVICES = 20;
    static final long WINDOW_MS = 120000L, DECIDE_MS = 90000L, COOLDOWN_MS = 15 * 60000L;
    static final int MAX_TRIES = 5;

    private final Store store;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private String secret = "", pin = "";
    private long windowUntil, cooldownUntil, savedAt;
    private int tries;
    private final List<Long> failedWindows = new ArrayList<>();

    Keys(Store store) {
        this.store = store;
        load();
    }

    // ---- Pairing window ----------------------------------------------------------------------

    /** Opens a window: {secret, six-digit code}, or null while pairing is locked. */
    synchronized String[] open(long now) {
        if (now < cooldownUntil) return null;
        secret = token(16);
        pin = String.format(java.util.Locale.US, "%06d", random.nextInt(1000000));
        windowUntil = now + WINDOW_MS;
        tries = 0;
        return new String[] {secret, pin};
    }

    synchronized void close() {
        secret = "";
        pin = "";
        windowUntil = 0;
    }

    synchronized boolean windowOpen(long now) {
        return now < windowUntil && !secret.isEmpty();
    }

    synchronized long windowLeft(long now) {
        return windowOpen(now) ? windowUntil - now : 0;
    }

    synchronized long lockedFor(long now) {
        return Math.max(0, cooldownUntil - now);
    }

    /**
     * A phone presents the scanned secret ({@code pin} false) or the typed code. Right: a pending
     * request for the TV to approve, and the window closes. Wrong: null, and a try is used.
     */
    synchronized Pending claim(String given, boolean isPin, String name, String ip, long now) {
        if (!windowOpen(now) || given == null) return null;
        String want = isPin ? pin : secret;
        boolean ok = same(given.getBytes(StandardCharsets.UTF_8), want.getBytes(StandardCharsets.UTF_8));
        if (!isPin && !ok) {
            // A scanned secret is single-use: a wrong one burns the window.
            failWindow(now);
            return null;
        }
        if (!ok) {
            if (++tries >= MAX_TRIES) failWindow(now);
            return null;
        }
        close();
        Pending p = new Pending();
        p.id = token(16);
        p.name = cleanName(name);
        p.ip = ip == null ? "" : ip;
        p.at = now;
        prunePending(now);
        if (pending.size() >= 4) pending.remove(pending.keySet().iterator().next());
        pending.put(p.id, p);
        return p;
    }

    private void failWindow(long now) {
        close();
        failedWindows.add(now);
        for (Iterator<Long> it = failedWindows.iterator(); it.hasNext(); ) if (now - it.next() > 3600000L) it.remove();
        if (failedWindows.size() >= 3) {
            cooldownUntil = now + COOLDOWN_MS;
            failedWindows.clear();
        }
    }

    /** The oldest request still waiting for the remote, or null. */
    synchronized Pending waiting(long now) {
        prunePending(now);
        for (Pending p : pending.values()) if (p.state == 0) return p;
        return null;
    }

    synchronized Pending pending(String id, long now) {
        prunePending(now);
        return id == null ? null : pending.get(id);
    }

    /**
     * What the asking device gets: {"1", cookie} once allowed (handed over a single time), {"0"}
     * while the TV waits for the remote, {"2"} if refused, expired, unknown or asked from another
     * address than the one that claimed it.
     */
    synchronized String[] collect(String id, String ip, long now) {
        Pending p = pending(id, now);
        if (p == null || p.state == 2 || ip == null || !ip.equals(p.ip)) return new String[] {"2"};
        if (p.state == 0) return new String[] {"0"};
        String cookie = p.cookie;
        p.cookie = "";
        p.state = 2;
        return cookie.isEmpty() ? new String[] {"2"} : new String[] {"1", cookie};
    }

    /** OK (allow) or Back (refuse) on the remote. */
    synchronized void decide(String id, boolean allow, long now) {
        Pending p = pending.get(id);
        if (p == null || p.state != 0) return;
        if (!allow) {
            p.state = 2;
            return;
        }
        String selector = token(16), verifier = token(32);
        Device d = new Device();
        d.selector = selector;
        d.hash = sha(verifier);
        d.name = p.name;
        d.ip = p.ip;
        d.created = now;
        d.seen = now;
        while (devices.size() >= MAX_DEVICES) devices.remove(oldest());
        devices.put(selector, d);
        p.cookie = selector + "." + verifier;
        p.state = 1;
        save(now);
    }

    private void prunePending(long now) {
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.state == 0 && now - p.at > DECIDE_MS) p.state = 2;
            if (now - p.at > DECIDE_MS + 60000L) it.remove();
        }
    }

    // ---- Keys ----------------------------------------------------------------------------------

    /** The device a cookie belongs to, or null. Updates when it was last seen. */
    synchronized Device check(String cookie, String ip, long now) {
        if (cookie == null || cookie.length() > 128) return null;
        int dot = cookie.indexOf('.');
        if (dot <= 0) return null;
        Device d = devices.get(cookie.substring(0, dot));
        if (d == null) return null;
        if (!same(sha(cookie.substring(dot + 1)).getBytes(StandardCharsets.US_ASCII), d.hash.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        d.seen = now;
        if (ip != null) d.ip = ip;
        // Last-seen times are written at most every ten minutes, not on every request.
        if (now - savedAt > 600000L) save(now);
        return d;
    }

    synchronized List<Device> list() {
        List<Device> out = new ArrayList<>();
        for (Device d : devices.values()) {
            Device c = new Device();
            c.selector = d.selector;
            c.name = d.name;
            c.ip = d.ip;
            c.created = d.created;
            c.seen = d.seen;
            out.add(c);
        }
        return out;
    }

    synchronized boolean revoke(String selector, long now) {
        if (devices.remove(selector) == null) return false;
        save(now);
        return true;
    }

    synchronized void revokeAll(long now) {
        devices.clear();
        pending.clear();
        close();
        save(now);
    }

    synchronized int count() {
        return devices.size();
    }

    private String oldest() {
        String best = null;
        long seen = Long.MAX_VALUE;
        for (Device d : devices.values()) {
            if (d.seen < seen) {
                seen = d.seen;
                best = d.selector;
            }
        }
        return best;
    }

    // ---- Storage ---------------------------------------------------------------------------------

    private void load() {
        String json = store.load();
        if (json == null || json.isEmpty()) return;
        try {
            List<Object> list = Json.arr(Json.obj(Json.parse(json)), "devices");
            if (list == null) return;
            for (Object o : list) {
                Map<String, Object> m = Json.obj(o);
                Device d = new Device();
                d.selector = Json.str(m, "s", "");
                d.hash = Json.str(m, "h", "");
                d.name = cleanName(Json.str(m, "n", ""));
                d.ip = Json.str(m, "ip", "");
                d.created = (long) Json.num(m, "c", 0);
                d.seen = (long) Json.num(m, "l", 0);
                if (d.selector.matches("[A-Za-z0-9_-]{16,32}") && d.hash.matches("[0-9a-f]{64}") && devices.size() < MAX_DEVICES) {
                    devices.put(d.selector, d);
                }
            }
        } catch (RuntimeException e) {
            // An unreadable file means no paired devices: everyone pairs again. Never a crash.
            devices.clear();
        }
    }

    private void save(long now) {
        savedAt = now;
        Jw w = new Jw().obj().key("devices").arr();
        for (Device d : devices.values()) {
            w.obj().put("s", d.selector).put("h", d.hash).put("n", d.name).put("ip", d.ip).put("c", d.created).put("l", d.seen).end();
        }
        w.endArr().end();
        store.save(w.toString());
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** {@code bytes} of randomness as URL-safe base64 without padding. */
    String token(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return b64(b);
    }

    static String b64(byte[] b) {
        final String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder s = new StringBuilder((b.length * 4 + 2) / 3);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xFF) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xFF) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xFF : 0);
            s.append(abc.charAt(n >> 18 & 63)).append(abc.charAt(n >> 12 & 63));
            if (i + 1 < b.length) s.append(abc.charAt(n >> 6 & 63));
            if (i + 2 < b.length) s.append(abc.charAt(n & 63));
        }
        return s.toString();
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder(64);
            for (byte x : d) h.append(Character.forDigit(x >> 4 & 15, 16)).append(Character.forDigit(x & 15, 16));
            return h.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison: how long it takes says nothing about where two values differ. */
    static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    static String cleanName(String raw) {
        String n = Text.clean(raw);
        if (n.length() > 40) n = n.substring(0, 40).trim();
        return n.isEmpty() ? "A phone" : n;
    }
}
