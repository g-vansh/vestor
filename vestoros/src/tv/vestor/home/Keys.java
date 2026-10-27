package tv.vestor.home;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Which phones and laptops may use the control center, and how a new one is let in.
 *
 * Pairing needs someone at the TV. Only the TV opens a pairing window (Settings, or the phone
 * card), for two minutes. It shows a QR code whose link carries a one-time secret k in the part
 * of the address browsers never send over the network, and a six-digit code for a laptop without
 * a camera.
 *
 * The secret itself never crosses the network either: the page proves it knows k with
 * HMAC-SHA256(k, "vestor-pair:" + nonce + ":" + c + ":" + name), where the nonce is fresh for
 * each window (from /api/hello) and c is the phone's own random value, good for one try. Someone
 * on the Wi-Fi who sees the proof learns nothing that works again. A wrong proof is just refused:
 * k is 128 random bits, so wrong proofs cost the window nothing.
 *
 * The six-digit code can be guessed, so it is rationed: five wrong codes from one address, or
 * twenty from all of them, turn the code off for the rest of the window (scanning still works),
 * and three windows that ended that way within an hour turn the code off for fifteen minutes.
 * The TV can read how many wrong codes were typed ({@link #wrongCodes}).
 *
 * A right code or proof still only asks: the TV shows the device's name with a three-digit match
 * number the phone shows too, and nothing happens until someone presses OK on the remote.
 *
 * An approved device gets a key in an HttpOnly cookie: a 16-byte selector and a 32-byte
 * verifier. Vestor keeps only SHA-256 hashes of verifiers, compares in constant time, and
 * forgets a device on Revoke. At most 20 devices. A key in use is replaced every minute
 * ({@link #verify}); the one before stays good for 30 seconds, for requests already on their way,
 * and an older one coming back means the key was copied: the device is signed out. Platform-free:
 * storage is handed in.
 */
final class Keys {
    interface Store {
        String load();

        void save(String json);
    }

    static final class Device {
        String selector = "", hash = "", name = "", ip = "";
        long created, seen;
        /** When the current verifier was handed out, and whether a request has carried it since. */
        long issued;
        boolean confirmed;
        /** The hash of the verifier before the current one, good until {@code prevUntil}. */
        String prev = "";
        long prevUntil;
        /** Hashes of verifiers that must never come back, newest last. */
        final List<String> retired = new ArrayList<>();
    }

    /** A device waiting for the OK on the remote. */
    static final class Pending {
        String id = "", name = "", ip = "", cookie = "";
        /** Three digits the phone shows and the TV shows next to the request, to tell them apart. */
        String match = "";
        long at;
        /** 0 waiting, 1 allowed, 2 refused or expired. */
        int state;
    }

    /** What a cookie came to. */
    static final class Check {
        static final Check NONE = new Check(null, null, false);

        /** The paired device, or null. */
        final Device device;
        /** A new cookie value ("selector.verifier") the answer must carry, or null. */
        final String cookie;
        /** An old key came back: the device has just been signed out. */
        final boolean reused;

        Check(Device device, String cookie, boolean reused) {
            this.device = device;
            this.cookie = cookie;
            this.reused = reused;
        }
    }

    /** What a pairing attempt came to: a request for the TV, or why not. */
    static final class Claim {
        /** The request now waiting for the remote, or null. */
        final Pending pending;
        /** Null, or "closed" (no window), "wrong", or "locked" (the six-digit code is off). */
        final String error;
        /** With "locked": how long until the code could work again, ms. */
        final long lockedFor;

        Claim(Pending pending, String error, long lockedFor) {
            this.pending = pending;
            this.error = error;
            this.lockedFor = lockedFor;
        }
    }

    static final int MAX_DEVICES = 20;
    static final long WINDOW_MS = 120000L, DECIDE_MS = 90000L, COOLDOWN_MS = 15 * 60000L;
    /** Wrong six-digit codes one address may type in a window, and all addresses together. */
    static final int MAX_TRIES = 5, MAX_WINDOW_TRIES = 20;
    /** A key is replaced once it is this old, and the one before stays good this long. */
    static final long ROTATE_MS = 60000L, GRACE_MS = 30000L;
    /** Old verifier hashes kept per device to recognise a copied key, and events kept for the TV. */
    static final int RETIRED = 4, MAX_EVENTS = 20;
    /** What the proof's message starts with. */
    static final String PROOF_PREFIX = "vestor-pair:";

    private final Store store;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private String secret = "", pin = "", nonce = "";
    private long windowUntil, pinLockedUntil, savedAt;
    /** This window: wrong codes in all, by address, whether the code is off, whether it counted as failed. */
    private int wrong;
    private final Map<String, Integer> wrongBy = new HashMap<>();
    private boolean pinOff, windowFailed;
    /** The c values seen in this window: each is good for one try. */
    private final Set<String> usedC = new LinkedHashSet<>();
    private final List<Long> failedWindows = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    Keys(Store store) {
        this.store = store;
        load();
    }

    // ---- Pairing window ----------------------------------------------------------------------

    /**
     * Opens a window: {secret, six-digit code}. The code is "" while it is turned off after too
     * many wrong ones (scanning still works then). Never null: pairing as a whole is never locked.
     */
    synchronized String[] open(long now) {
        secret = token(16);
        nonce = token(16);
        boolean locked = now < pinLockedUntil;
        pin = locked ? "" : String.format(Locale.US, "%06d", random.nextInt(1000000));
        windowUntil = now + WINDOW_MS;
        wrong = 0;
        wrongBy.clear();
        usedC.clear();
        pinOff = locked;
        windowFailed = false;
        return new String[] {secret, pin};
    }

    synchronized void close() {
        secret = "";
        pin = "";
        nonce = "";
        windowUntil = 0;
        usedC.clear();
    }

    synchronized boolean windowOpen(long now) {
        return now < windowUntil && !secret.isEmpty();
    }

    synchronized long windowLeft(long now) {
        return windowOpen(now) ? windowUntil - now : 0;
    }

    /** The open window's nonce for /api/hello (22 URL-safe base64 characters), or null. */
    synchronized String nonce(long now) {
        return windowOpen(now) ? nonce : null;
    }

    /**
     * How long pairing as a whole stays locked: always 0 now. Wrong codes only ever turn off the
     * six-digit code ({@link #pinLockedFor}, {@link #pinOpen}); scanning the QR code always works.
     */
    synchronized long lockedFor(long now) {
        return 0;
    }

    /** How long the six-digit code stays off after three windows of wrong codes, ms. */
    synchronized long pinLockedFor(long now) {
        return Math.max(0, pinLockedUntil - now);
    }

    /** Whether the six-digit code of the open window can still be used by someone new. */
    synchronized boolean pinOpen(long now) {
        return windowOpen(now) && !pinOff && !pin.isEmpty() && now >= pinLockedUntil;
    }

    /**
     * Wrong six-digit codes typed in the open (or last) window, from every address. The TV can
     * show it ("2 wrong codes were typed") so the owner knows someone is trying.
     */
    synchronized int wrongCodes() {
        return wrong;
    }

    /**
     * A phone presents the proof that it scanned the QR code (see the class comment). Right: a
     * request for the TV to approve, and the window closes. Wrong or a c used before: refused,
     * at no cost to the window.
     */
    synchronized Claim claimProof(String proof, String c, String name, String ip, long now) {
        if (!windowOpen(now)) return new Claim(null, "closed", 0);
        if (proof == null || c == null || name == null || !c.matches("[A-Za-z0-9_-]{22}") || !proof.matches("[0-9a-f]{64}")) {
            return new Claim(null, "wrong", 0);
        }
        if (!usedC.add(c)) return new Claim(null, "wrong", 0);
        if (usedC.size() > 1024) {
            Iterator<String> it = usedC.iterator();
            it.next();
            it.remove();
        }
        String want = proof(secret, nonce, c, name);
        if (!same(proof.getBytes(StandardCharsets.US_ASCII), want.getBytes(StandardCharsets.US_ASCII))) {
            return new Claim(null, "wrong", 0);
        }
        return new Claim(grant(name, ip, now), null, 0);
    }

    /** A phone or laptop presents the six-digit code typed from the TV. */
    synchronized Claim claimPin(String given, String name, String ip, long now) {
        if (!windowOpen(now)) return new Claim(null, "closed", 0);
        String who = ip == null ? "" : ip;
        if (now < pinLockedUntil) return new Claim(null, "locked", pinLockedUntil - now);
        Integer mine = wrongBy.get(who);
        if (pinOff || pin.isEmpty() || mine != null && mine >= MAX_TRIES) return new Claim(null, "locked", windowUntil - now);
        boolean ok = given != null && same(given.getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            wrong++;
            int n = mine == null ? 1 : mine + 1;
            wrongBy.put(who, n);
            if (wrong >= MAX_WINDOW_TRIES) pinOff = true;
            if (n >= MAX_TRIES || pinOff) failWindow(now);
            return new Claim(null, "wrong", 0);
        }
        return new Claim(grant(name, ip, now), null, 0);
    }

    private Pending grant(String name, String ip, long now) {
        close();
        Pending p = new Pending();
        p.id = token(16);
        p.name = cleanName(name);
        p.ip = ip == null ? "" : ip;
        p.at = now;
        p.match = String.format(Locale.US, "%03d", random.nextInt(1000));
        prunePending(now);
        if (pending.size() >= 4) pending.remove(pending.keySet().iterator().next());
        pending.put(p.id, p);
        return p;
    }

    /** A window where the code ran out of tries. Three within an hour turn the code off for 15 minutes. */
    private void failWindow(long now) {
        if (windowFailed) return;
        windowFailed = true;
        failedWindows.add(now);
        for (Iterator<Long> it = failedWindows.iterator(); it.hasNext(); ) if (now - it.next() > 3600000L) it.remove();
        if (failedWindows.size() >= 3) {
            pinLockedUntil = now + COOLDOWN_MS;
            pinOff = true;
            failedWindows.clear();
            event("Too many wrong codes were typed: the six-digit code is off for 15 minutes. Scanning the QR code still works.");
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
        d.issued = now;
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

    /**
     * The device a cookie belongs to, and what to do about the key:
     * - the current verifier: good. Once it is {@link #ROTATE_MS} old a new one is made, and the
     *   answer must carry it ({@link Check#cookie}); the current one becomes the previous.
     * - the previous verifier, within {@link #GRACE_MS} of being replaced: good (the page sends
     *   up to three requests at once, so some still carry it).
     * - the previous one later, while no request has yet carried the current one: the answer that
     *   carried it never arrived (a phone locked mid-request), so another is made. The unseen one
     *   is retired: if someone else does hold it, it will show up as reuse.
     * - the previous one later still, or any older one: the key was copied. The device is signed
     *   out, an event is kept for the TV, and {@link Check#reused} is set.
     * Updates when the device was last seen and from where (a new address is an event too).
     */
    synchronized Check verify(String cookie, String ip, long now) {
        Device d = device(cookie);
        if (d == null) return Check.NONE;
        byte[] h = sha(cookie.substring(cookie.indexOf('.') + 1)).getBytes(StandardCharsets.US_ASCII);
        String fresh = null;
        if (same(h, d.hash.getBytes(StandardCharsets.US_ASCII))) {
            d.confirmed = true;
            if (now - d.issued >= ROTATE_MS || now < d.issued) fresh = rotate(d, now);
        } else if (!d.prev.isEmpty() && same(h, d.prev.getBytes(StandardCharsets.US_ASCII))) {
            if (now >= d.prevUntil) {
                if (d.confirmed) return reused(d, now);
                fresh = reissue(d, now);
            }
        } else {
            boolean old = false;
            for (String r : d.retired) old |= same(h, r.getBytes(StandardCharsets.US_ASCII));
            return old ? reused(d, now) : Check.NONE;
        }
        seen(d, ip, now);
        if (fresh != null) save(now);
        // Last-seen times are written at most every ten minutes, not on every request.
        else if (now - savedAt > 600000L) save(now);
        return new Check(d, fresh == null ? null : d.selector + "." + fresh, false);
    }

    /**
     * Whether a cookie is a key that {@link #verify} would accept now, changing nothing: for
     * requests that need no key but are let through faster with one.
     */
    synchronized boolean known(String cookie, long now) {
        Device d = device(cookie);
        if (d == null) return false;
        byte[] h = sha(cookie.substring(cookie.indexOf('.') + 1)).getBytes(StandardCharsets.US_ASCII);
        if (same(h, d.hash.getBytes(StandardCharsets.US_ASCII))) return true;
        return !d.prev.isEmpty() && same(h, d.prev.getBytes(StandardCharsets.US_ASCII)) && (now < d.prevUntil || !d.confirmed);
    }

    private Device device(String cookie) {
        if (cookie == null || cookie.length() > 128) return null;
        int dot = cookie.indexOf('.');
        if (dot <= 0) return null;
        return devices.get(cookie.substring(0, dot));
    }

    private String rotate(Device d, long now) {
        String v = token(32);
        if (!d.prev.isEmpty()) retire(d, d.prev);
        d.prev = d.hash;
        d.prevUntil = now + GRACE_MS;
        d.hash = sha(v);
        d.issued = now;
        d.confirmed = false;
        return v;
    }

    private String reissue(Device d, long now) {
        String v = token(32);
        retire(d, d.hash);
        d.prevUntil = now + GRACE_MS;
        d.hash = sha(v);
        d.issued = now;
        d.confirmed = false;
        return v;
    }

    private static void retire(Device d, String hash) {
        d.retired.add(hash);
        while (d.retired.size() > RETIRED) d.retired.remove(0);
    }

    private Check reused(Device d, long now) {
        devices.remove(d.selector);
        save(now);
        event(d.name + " was signed out: its key was used from two places");
        return new Check(null, null, true);
    }

    private void seen(Device d, String ip, long now) {
        d.seen = now;
        if (ip == null || ip.isEmpty() || ip.equals(d.ip)) return;
        if (!d.ip.isEmpty()) event(d.name + " is now at " + ip);
        d.ip = ip;
    }

    private void event(String text) {
        events.add(text);
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    /**
     * What happened to paired devices since the last call, oldest first, as sentences for the TV
     * ("Alex's iPhone was signed out: its key was used from two places", "Alex's iPhone is now at
     * 192.168.1.40"). Returns them and forgets them. Any thread.
     */
    synchronized List<String> events() {
        List<String> out = new ArrayList<>(events);
        events.clear();
        return out;
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

    private static final String HASH = "[0-9a-f]{64}";

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
                d.issued = (long) Json.num(m, "i", 0);
                d.confirmed = Json.bool(m, "f", false);
                String prev = Json.str(m, "p", "");
                if (prev.matches(HASH)) {
                    d.prev = prev;
                    d.prevUntil = (long) Json.num(m, "pu", 0);
                }
                List<Object> retired = Json.arr(m, "r");
                if (retired != null) {
                    for (Object r : retired) if (r instanceof String && ((String) r).matches(HASH)) retire(d, (String) r);
                }
                if (d.selector.matches("[A-Za-z0-9_-]{16,32}") && d.hash.matches(HASH) && devices.size() < MAX_DEVICES) {
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
            w.obj().put("s", d.selector).put("h", d.hash).put("n", d.name).put("ip", d.ip).put("c", d.created).put("l", d.seen)
                    .put("i", d.issued).put("f", d.confirmed);
            if (!d.prev.isEmpty()) w.put("p", d.prev).put("pu", d.prevUntil);
            w.key("r").arr();
            for (String r : d.retired) w.val(r);
            w.endArr().end();
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
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The pairing proof: lowercase hex of HMAC-SHA256 keyed with the UTF-8 bytes of the QR secret
     * {@code k}, over the UTF-8 bytes of "vestor-pair:" + nonce + ":" + c + ":" + name.
     */
    static String proof(String k, String nonce, String c, String name) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal((PROOF_PREFIX + nonce + ":" + c + ":" + name).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder h = new StringBuilder(d.length * 2);
        for (byte x : d) h.append(Character.forDigit(x >> 4 & 15, 16)).append(Character.forDigit(x & 15, 16));
        return h.toString();
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
