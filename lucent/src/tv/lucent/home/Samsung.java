package tv.lucent.home;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Samsung TV's picture mode, through SmartThings: a Glare button that switches to the bright
 * picture for a sunny room, and, if wanted, a picture that follows day and night.
 *
 * Setup is once, from the control center: the owner makes an OAuth-In app with the SmartThings
 * command line (scopes r:devices:* and x:devices:*, redirect https://httpbin.org/get), types its
 * id and secret, signs in to Samsung on the phone, and pastes back the code httpbin shows. From
 * then on the TV keeps its own tokens: an access token lasts a day, and each refresh token works
 * once and is replaced, so both are saved after every refresh. A refresh is made at least every
 * 20 days so the sign-in never lapses.
 *
 * What it can reach: SmartThings grants these scopes for every device on the account, not only
 * the TV, so README says so. The secret and tokens stay in Lucent's private storage and are never
 * sent to a phone. Platform-free: storage and HTTP are handed in, for the desktop tests.
 */
final class Samsung {
    interface Store {
        String get(String key);

        void put(String key, String value);
    }

    /** HTTP, so tests can stand in for SmartThings: null when nothing came back. */
    interface Web {
        Net.Reply call(String method, String url, String[] headers, String contentType, byte[] body);
    }

    static final String API = "https://api.smartthings.com/v1";
    static final String REDIRECT = "https://httpbin.org/get";
    static final String SCOPES = "r:devices:* x:devices:*";
    private static final String[] CAPS = {"custom.picturemode", "samsungvd.pictureMode"};
    private static final long REFRESH_EVERY = 20L * 24 * 3600000L;

    private static final String CLIENT = "st_client", SECRET = "st_secret", ACCESS = "st_access", REFRESH = "st_refresh",
            EXPIRES = "st_expires", REFRESHED = "st_refreshed", DEVICE = "st_device", LABEL = "st_label", CAP = "st_cap",
            STATE = "st_state", MODES = "st_modes", MODE = "st_mode";

    private final Store store;
    private final Web web;
    private final SecureRandom random = new SecureRandom();
    private volatile String error = "";

    Samsung(Store store, Web web) {
        this.store = store;
        this.web = web;
    }

    /** Refused setup or command, with a reason for the control center. */
    static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Failed(int status, String why) {
            super(why);
            this.status = status;
        }
    }

    boolean connected() {
        return !s(DEVICE).isEmpty() && !s(REFRESH).isEmpty();
    }

    boolean begun() {
        return !s(CLIENT).isEmpty() && !connected();
    }

    String device() {
        return s(LABEL);
    }

    String error() {
        return error;
    }

    /** The picture modes this TV offers, as it last reported them. */
    List<String> modes() {
        List<String> out = new ArrayList<>();
        for (String m : s(MODES).split("\n")) if (!m.isEmpty()) out.add(m);
        return out;
    }

    String mode() {
        return s(MODE);
    }

    private String s(String key) {
        String v = store.get(key);
        return v == null ? "" : v;
    }

    // ---- Setup -----------------------------------------------------------------------------------

    /** Step one: keeps the app's id and secret and answers the Samsung sign-in address. */
    synchronized String begin(String clientId, String secret) throws Failed {
        String id = clientId == null ? "" : clientId.trim(), sec = secret == null ? "" : secret.trim();
        if (!id.matches("[A-Za-z0-9-]{8,64}") || !sec.matches("[A-Za-z0-9._~-]{8,128}")) throw new Failed(400, "client id or secret");
        byte[] b = new byte[12];
        random.nextBytes(b);
        String state = Keys.b64(b);
        store.put(CLIENT, id);
        store.put(SECRET, sec);
        store.put(STATE, state);
        error = "";
        return API + "/oauth/authorize?client_id=" + enc(id) + "&scope=" + enc(SCOPES) + "&response_type=code&redirect_uri="
                + enc(REDIRECT) + "&state=" + enc(state);
    }

    /**
     * The code from what the owner pasted: the bare code, httpbin's whole address, or its JSON.
     * When a state is present it must be the one this TV made. Null when there is no code.
     */
    static String code(String pasted, String state) {
        if (pasted == null) return null;
        String p = pasted.trim();
        if (p.length() > 4000) return null;
        if (p.matches("[A-Za-z0-9_-]{4,64}")) return p;
        Matcher c = Pattern.compile("(?:[?&]code=|\"code\"\\s*:\\s*\")([A-Za-z0-9_-]{4,64})").matcher(p);
        if (!c.find()) return null;
        Matcher s = Pattern.compile("(?:[?&]state=|\"state\"\\s*:\\s*\")([A-Za-z0-9_-]{4,64})").matcher(p);
        if (s.find() && state != null && !state.isEmpty() && !Keys.same(s.group(1).getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return c.group(1);
    }

    /** Step two: trades the code for tokens and finds the TV. Slow: call off the main thread. */
    synchronized String finish(String pasted) throws Failed {
        if (s(CLIENT).isEmpty()) throw new Failed(409, "start again");
        String code = code(pasted, s(STATE));
        if (code == null) throw new Failed(400, "no code in that");
        tokens("grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(s(CLIENT)) + "&redirect_uri=" + enc(REDIRECT));
        store.put(STATE, "");
        find();
        status();
        error = "";
        return s(LABEL);
    }

    /** Forgets the app, the tokens and the TV. */
    synchronized void disconnect() {
        for (String k : new String[] {CLIENT, SECRET, ACCESS, REFRESH, EXPIRES, REFRESHED, DEVICE, LABEL, CAP, STATE, MODES, MODE}) {
            store.put(k, "");
        }
        error = "";
    }

    private void tokens(String form) throws Failed {
        String basic = Keys.b64((s(CLIENT) + ":" + s(SECRET)).getBytes(StandardCharsets.UTF_8));
        // The standard alphabet for Basic, not Lucent's URL-safe one.
        basic = basic.replace('-', '+').replace('_', '/');
        while (basic.length() % 4 != 0) basic += "=";
        Net.Reply r = web.call("POST", API + "/oauth/token", new String[] {"Authorization", "Basic " + basic},
                "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8));
        if (r == null) throw new Failed(503, "SmartThings did not answer");
        if (r.code == 400 || r.code == 401) throw new Failed(409, "Samsung refused the sign-in, connect again");
        if (r.code != 200) throw new Failed(502, "SmartThings answered " + r.code);
        Map<String, Object> m = obj(r.text());
        String access = Json.str(m, "access_token", ""), refresh = Json.str(m, "refresh_token", "");
        long expires = (long) Json.num(m, "expires_in", 0);
        if (access.isEmpty() || refresh.isEmpty() || access.length() > 4096 || refresh.length() > 4096) {
            throw new Failed(502, "SmartThings sent no tokens");
        }
        long now = System.currentTimeMillis();
        // Both are replaced at once: the old refresh token no longer works.
        store.put(ACCESS, access);
        store.put(REFRESH, refresh);
        store.put(EXPIRES, Long.toString(now + Math.max(600, Math.min(86400, expires)) * 1000L - 300000L));
        store.put(REFRESHED, Long.toString(now));
    }

    private void refresh() throws Failed {
        if (s(REFRESH).isEmpty()) throw new Failed(409, "not connected");
        try {
            tokens("grant_type=refresh_token&refresh_token=" + enc(s(REFRESH)) + "&client_id=" + enc(s(CLIENT)));
        } catch (Failed f) {
            if (f.status == 409) {
                error = "Samsung sign-in expired. Connect again in Settings.";
                store.put(REFRESH, "");
            }
            throw f;
        }
    }

    /** A token that works now, refreshed when it is about to lapse or has not been for 20 days. */
    private String token() throws Failed {
        long now = System.currentTimeMillis();
        if (now >= num(EXPIRES) || now - num(REFRESHED) > REFRESH_EVERY || s(ACCESS).isEmpty()) refresh();
        return s(ACCESS);
    }

    private long num(String key) {
        try {
            return Long.parseLong(s(key));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Keeps the sign-in alive when nothing has used it for a while. Off the main thread. */
    synchronized void keepAlive() {
        if (!connected()) return;
        try {
            if (System.currentTimeMillis() - num(REFRESHED) > REFRESH_EVERY) refresh();
        } catch (Failed ignored) {
            // error() says so
        }
    }

    private Net.Reply api(String method, String path, String json) throws Failed {
        String[] auth = {"Authorization", "Bearer " + token()};
        byte[] body = json == null ? null : json.getBytes(StandardCharsets.UTF_8);
        Net.Reply r = web.call(method, API + path, auth, "application/json", body);
        if (r != null && r.code == 401) {
            // The access token was revoked early: one fresh try.
            refresh();
            auth[1] = "Bearer " + s(ACCESS);
            r = web.call(method, API + path, auth, "application/json", body);
        }
        if (r == null) throw new Failed(503, "SmartThings did not answer");
        return r;
    }

    /** The account's TV: the first device with a picture-mode capability. */
    private void find() throws Failed {
        for (String cap : CAPS) {
            Net.Reply r = api("GET", "/devices?capability=" + enc(cap), null);
            if (r.code != 200) continue;
            List<Object> items = Json.arr(obj(r.text()), "items");
            if (items == null) continue;
            for (Object o : items) {
                Map<String, Object> d = Json.obj(o);
                String id = Json.str(d, "deviceId", "");
                if (!id.matches("[0-9a-fA-F-]{8,64}")) continue;
                String label = Text.clean(Json.str(d, "label", Json.str(d, "name", "Samsung TV")));
                store.put(DEVICE, id);
                store.put(LABEL, label.isEmpty() ? "Samsung TV" : label);
                store.put(CAP, cap);
                return;
            }
        }
        throw new Failed(404, "no Samsung TV with picture modes on this account");
    }

    /** Reads the current mode and the modes on offer. Off the main thread. */
    synchronized void status() throws Failed {
        if (s(DEVICE).isEmpty()) throw new Failed(409, "not connected");
        Net.Reply r = api("GET", "/devices/" + s(DEVICE) + "/components/main/capabilities/" + s(CAP) + "/status", null);
        if (r.code != 200) throw new Failed(502, "SmartThings answered " + r.code);
        Map<String, Object> m = obj(r.text());
        String mode = value(m, "pictureMode");
        StringBuilder modes = new StringBuilder();
        Object sup = Json.obj(m.get("supportedPictureModes")) == null ? null : Json.obj(m.get("supportedPictureModes")).get("value");
        if (sup instanceof List) {
            int n = 0;
            for (Object o : (List<?>) sup) {
                String v = o instanceof String ? Text.clean((String) o) : "";
                if (!validMode(v) || n++ >= 16) continue;
                modes.append(v).append('\n');
            }
        }
        if (validMode(mode)) store.put(MODE, mode);
        if (modes.length() > 0) store.put(MODES, modes.toString());
    }

    private static String value(Map<String, Object> m, String attr) {
        Map<String, Object> a = Json.obj(m.get(attr));
        return a == null ? "" : Text.clean(Json.str(a, "value", ""));
    }

    static boolean validMode(String m) {
        return m != null && m.matches("[A-Za-z][A-Za-z0-9 +_-]{1,31}");
    }

    /** Switches the picture mode. Off the main thread. */
    synchronized void setMode(String mode) throws Failed {
        if (!connected()) throw new Failed(409, "not connected");
        if (!validMode(mode)) throw new Failed(400, "mode");
        List<String> known = modes();
        if (!known.isEmpty() && !known.contains(mode)) throw new Failed(400, "this TV has no " + mode + " mode");
        String json = new Jw().obj().key("commands").arr().obj().put("component", "main").put("capability", s(CAP))
                .put("command", "setPictureMode").key("arguments").arr().val(mode).endArr().end().endArr().end().toString();
        Net.Reply r = api("POST", "/devices/" + s(DEVICE) + "/commands", json);
        if (r.code != 200) {
            error = r.code == 409 || r.code == 422 ? "The TV is off or busy" : "SmartThings answered " + r.code;
            throw new Failed(502, error);
        }
        store.put(MODE, mode);
        error = "";
    }

    /** The first of {@code wanted} that this TV has, or the first mode it has, or "". */
    String pick(String... wanted) {
        List<String> known = modes();
        for (String w : wanted) {
            for (String k : known) if (k.equalsIgnoreCase(w)) return k;
        }
        return known.isEmpty() ? "" : known.get(0);
    }

    private static Map<String, Object> obj(String json) throws Failed {
        try {
            Map<String, Object> m = Json.obj(Json.parse(json));
            if (m == null) throw new Failed(502, "SmartThings sent something odd");
            return m;
        } catch (IllegalArgumentException | StackOverflowError e) {
            throw new Failed(502, "SmartThings sent something odd");
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.US);
    }
}
