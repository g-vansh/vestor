package tv.vestor.home;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Samsung TV's picture mode, through SmartThings: a Glare button that switches to the bright
 * picture for a sunny room, and, if wanted, a picture that follows day and night. Also the rest
 * of what a Tizen TV offers there: power, volume, mute, input, sound mode, app launches and the
 * keys of its remote, each checked against what the TV last reported
 * ({@link #readAll}). SmartThings takes 12 commands a minute per device, so the phone's arrow keys
 * go to the TV over the home network instead ({@link SamsungRemote}).
 *
 * Setup is once, from the control center: the owner makes an OAuth-In app with the SmartThings
 * command line (scopes r:devices:* and x:devices:*, redirect https://httpbin.org/get), types its
 * id and secret, signs in to Samsung on the phone, and pastes back the code httpbin shows. From
 * then on the TV keeps its own tokens: an access token lasts a day, and each refresh token works
 * once and is replaced, so both are saved after every refresh. A refresh is made at least every
 * 20 days so the sign-in never lapses.
 *
 * What it can reach: SmartThings grants these scopes for every device on the account, not only
 * the TV, so README says so. The secret and tokens stay in Vestor's private storage and are never
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
    /** What samsungvd.remoteControl's send command takes (its enum), less the accessibility shortcuts. */
    static final String[] CLOUD_KEYS = {"UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "EXIT", "MENU", "HOME", "MUTE", "VOLUP",
        "VOLDOWN", "POWER", "SOURCE", "GUIDE", "MORE", "PLAY", "PAUSE", "STOP", "REWIND", "FF", "PLAY_BACK", "CHUP", "CHDOWN",
        "PRECH", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "RED", "GREEN", "YELLOW", "BLUE", "MULTI_VIEW", "TV_PLUS",
        "CC_ONOFF", "PICTURE_OFF"};
    private static final long REFRESH_EVERY = 20L * 24 * 3600000L;

    private static final String CLIENT = "st_client", SECRET = "st_secret", ACCESS = "st_access", REFRESH = "st_refresh",
            EXPIRES = "st_expires", REFRESHED = "st_refreshed", DEVICE = "st_device", LABEL = "st_label", CAP = "st_cap",
            STATE = "st_state", MODES = "st_modes", MODE = "st_mode", REPORTED = "st_caps", INPUTS = "st_inputs", SOUNDS = "st_sounds";

    private final Store store;
    private final Web web;
    private final SecureRandom random = new SecureRandom();
    private volatile String error = "";
    /** As the TV last reported them: "on", "off" or "", 0 to 100 or -1, "muted", "unmuted" or "". */
    private volatile String power = "", muted = "", input = "", sound = "";
    private volatile int volume = -1;

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
        for (String k : new String[] {CLIENT, SECRET, ACCESS, REFRESH, EXPIRES, REFRESHED, DEVICE, LABEL, CAP, STATE, MODES, MODE,
            REPORTED, INPUTS, SOUNDS}) {
            store.put(k, "");
        }
        error = "";
        power = muted = input = sound = "";
        volume = -1;
    }

    private void tokens(String form) throws Failed {
        String basic = Keys.b64((s(CLIENT) + ":" + s(SECRET)).getBytes(StandardCharsets.UTF_8));
        // The standard alphabet for Basic, not Vestor's URL-safe one.
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
        picture(obj(r.text()));
    }

    /** The current picture mode and the modes on offer, from the picture capability's status. */
    private void picture(Map<String, Object> m) {
        String mode = value(m, "pictureMode");
        String modes = names(m, "supportedPictureModes", true);
        if (validMode(mode)) keep(MODE, mode);
        if (!modes.isEmpty()) keep(MODES, modes);
    }

    /** Stores only a change: the TV's store writes through to disk. */
    private void keep(String key, String value) {
        if (!value.equals(s(key))) store.put(key, value);
    }

    /** Up to 16 valid names from an attribute's list value, one per line. */
    private static String names(Map<String, Object> m, String attr, boolean picture) {
        StringBuilder out = new StringBuilder();
        Object sup = Json.obj(m.get(attr)) == null ? null : Json.obj(m.get(attr)).get("value");
        if (sup instanceof List) {
            int n = 0;
            for (Object o : (List<?>) sup) {
                String v = o instanceof String ? Text.clean((String) o) : "";
                if (!(picture ? validMode(v) : validName(v)) || n++ >= 16) continue;
                out.append(v).append('\n');
            }
        }
        return out.toString();
    }

    private static String value(Map<String, Object> m, String attr) {
        Map<String, Object> a = Json.obj(m, attr);
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
        command(s(CAP), "setPictureMode", mode);
        store.put(MODE, mode);
    }

    /** One command to the TV's main component. Strings and whole numbers as arguments. */
    private void command(String capability, String command, Object... args) throws Failed {
        Jw w = new Jw().obj().key("commands").arr().obj().put("component", "main").put("capability", capability).put("command", command);
        if (args.length > 0) {
            w.key("arguments").arr();
            for (Object a : args) {
                if (a instanceof Integer) w.val(((Integer) a).longValue());
                else w.val((String) a);
            }
            w.endArr();
        }
        Net.Reply r = api("POST", "/devices/" + s(DEVICE) + "/commands", w.end().endArr().end().toString());
        if (r.code != 200) {
            error = r.code == 409 || r.code == 422 ? "The TV is off or busy" : r.code == 429 ? "Too many TV commands, wait a minute"
                    : "SmartThings answered " + r.code;
            throw new Failed(502, error);
        }
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

    // ---- The rest of the TV: power, sound, inputs, apps and remote keys --------------------------

    /**
     * Reads everything the TV reports in one request (SmartThings allows 12 a minute): which
     * capabilities it has, power, volume, mute, input, sound mode and the picture. The lists of
     * inputs and sound modes are kept only while the TV is on, since a TV in standby reports
     * fewer. Off the main thread.
     */
    synchronized void readAll() throws Failed {
        if (s(DEVICE).isEmpty()) throw new Failed(409, "not connected");
        Net.Reply r = api("GET", "/devices/" + s(DEVICE) + "/components/main/status", null);
        if (r.code != 200) throw new Failed(502, "SmartThings answered " + r.code);
        Map<String, Object> m = obj(r.text());
        StringBuilder caps = new StringBuilder();
        int n = 0;
        for (String k : m.keySet()) {
            if (k.matches("[A-Za-z][A-Za-z0-9.]{0,63}") && n++ < 64) caps.append(k).append('\n');
        }
        if (caps.length() > 0) keep(REPORTED, caps.toString());
        String p = value(cap(m, "switch"), "switch");
        power = "on".equals(p) || "off".equals(p) ? p : "";
        Map<String, Object> vol = Json.obj(cap(m, "audioVolume"), "volume");
        double v = Json.num(vol, "value", -1);
        volume = v >= 0 && v <= 100 ? (int) Math.round(v) : -1;
        String mute = value(cap(m, "audioMute"), "mute");
        muted = "muted".equals(mute) || "unmuted".equals(mute) ? mute : "";
        Map<String, Object> pic = cap(m, s(CAP));
        if (pic != null) picture(pic);
        boolean on = !"off".equals(power);
        Map<String, Object> sm = cap(m, "custom.soundmode") != null ? cap(m, "custom.soundmode") : cap(m, "samsungvd.soundMode");
        if (sm != null) {
            String now = value(sm, "soundMode");
            sound = validName(now) ? now : "";
            String list = names(sm, "supportedSoundModes", false);
            if (on && !list.isEmpty()) keep(SOUNDS, list);
        }
        Map<String, Object> vd = cap(m, "samsungvd.mediaInputSource"), std = cap(m, "mediaInputSource");
        StringBuilder list = new StringBuilder();
        int count = 0;
        List<Object> map = Json.arr(Json.obj(vd, "supportedInputSourcesMap"), "value");
        if (map != null) {
            for (Object o : map) {
                Map<String, Object> e = Json.obj(o);
                String id = Text.clean(Json.str(e, "id", "")), name = Text.clean(Json.str(e, "name", ""));
                if (!validInput(id) || count++ >= 16) continue;
                list.append(id).append('\t').append(validName(name) ? name : id).append('\n');
            }
        }
        List<Object> ids = Json.arr(Json.obj(std, "supportedInputSources"), "value");
        if (list.length() == 0 && ids != null) {
            for (Object o : ids) {
                String id = o instanceof String ? (String) o : "";
                if (!validInput(id) || count++ >= 16) continue;
                list.append(id).append('\t').append(id).append('\n');
            }
        }
        if (on && list.length() > 0) keep(INPUTS, list.toString());
        String in = value(map != null ? vd : std, "inputSource");
        input = validInput(in) ? in : "";
        error = "";
    }

    private static Map<String, Object> cap(Map<String, Object> status, String capability) {
        return Json.obj(status, capability);
    }

    /** Sound modes and input names may be in the TV's own language. */
    static boolean validName(String s) {
        return s != null && s.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} +&'()./_-]{0,31}");
    }

    static boolean validInput(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    }

    /** The capabilities the TV reported, or none before the first {@link #readAll}. */
    List<String> capabilities() {
        return lines(REPORTED);
    }

    /** Whether the TV reported {@code capability}. */
    boolean reports(String capability) {
        return capabilities().contains(capability);
    }

    /** Whether the TV may have {@code capability}: it reported it, or nothing is known yet. */
    private boolean may(String capability) {
        List<String> c = capabilities();
        return c.isEmpty() || c.contains(capability);
    }

    private List<String> lines(String key) {
        List<String> out = new ArrayList<>();
        for (String m : s(key).split("\n")) if (!m.isEmpty()) out.add(m);
        return out;
    }

    String power() {
        return power;
    }

    /** 0 to 100, or -1 when unknown. */
    int volume() {
        return volume;
    }

    /** "muted", "unmuted" or "". */
    String muted() {
        return muted;
    }

    String input() {
        return input;
    }

    /** The TV's inputs as {id, name}: {"HDMI1", "Fire TV"}. */
    List<String[]> inputs() {
        List<String[]> out = new ArrayList<>();
        for (String line : lines(INPUTS)) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.add(new String[] {line.substring(0, tab), line.substring(tab + 1)});
        }
        return out;
    }

    String sound() {
        return sound;
    }

    List<String> sounds() {
        return lines(SOUNDS);
    }

    private void need(String capability, String why) throws Failed {
        if (!connected()) throw new Failed(409, "not connected");
        if (!may(capability)) throw new Failed(409, why);
    }

    /** Turns the TV on or off. On needs a 2019 or later TV with Power On with Mobile turned on. */
    synchronized void power(boolean on) throws Failed {
        need("switch", "This TV cannot be switched through SmartThings");
        command("switch", on ? "on" : "off");
        power = on ? "on" : "off";
    }

    synchronized void setVolume(int level) throws Failed {
        if (level < 0 || level > 100) throw new Failed(400, "volume");
        need("audioVolume", "This TV has no volume through SmartThings");
        command("audioVolume", "setVolume", level);
        volume = level;
    }

    /** One step up or down, like the remote's volume key. */
    synchronized void volumeStep(boolean up) throws Failed {
        need("audioVolume", "This TV has no volume through SmartThings");
        command("audioVolume", up ? "volumeUp" : "volumeDown");
        int v = volume;
        if (v >= 0) volume = Math.max(0, Math.min(100, v + (up ? 1 : -1)));
    }

    synchronized void mute(boolean on) throws Failed {
        need("audioMute", "This TV has no mute through SmartThings");
        command("audioMute", on ? "mute" : "unmute");
        muted = on ? "muted" : "unmuted";
    }

    /** Switches to an input by the id the TV reported (HDMI1, dtv). */
    synchronized void setInput(String id) throws Failed {
        if (!validInput(id)) throw new Failed(400, "input");
        boolean vd = reports("samsungvd.mediaInputSource") || !reports("mediaInputSource");
        need(vd ? "samsungvd.mediaInputSource" : "mediaInputSource", "This TV has no inputs through SmartThings");
        List<String[]> known = inputs();
        boolean found = known.isEmpty();
        for (String[] k : known) found |= k[0].equals(id);
        if (!found) throw new Failed(400, "this TV has no input " + id);
        command(vd ? "samsungvd.mediaInputSource" : "mediaInputSource", "setInputSource", id);
        input = id;
    }

    synchronized void setSound(String mode) throws Failed {
        if (!validName(mode)) throw new Failed(400, "sound mode");
        String capability = reports("custom.soundmode") || !reports("samsungvd.soundMode") ? "custom.soundmode" : "samsungvd.soundMode";
        need(capability, "This TV has no sound modes through SmartThings");
        List<String> known = sounds();
        if (!known.isEmpty() && !known.contains(mode)) throw new Failed(400, "this TV has no " + mode + " sound");
        command(capability, "setSoundMode", mode);
        sound = mode;
    }

    /** Opens an app by its Tizen id (YouTube is 111299001912). */
    synchronized void launch(String appId) throws Failed {
        if (appId == null || !appId.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) throw new Failed(400, "app");
        need("custom.launchapp", "This TV opens no apps through SmartThings");
        command("custom.launchapp", "launchApp", appId);
    }

    /**
     * One key of the TV's remote through the cloud (UP, OK, BACK, HOME, MENU). Slow and counted
     * against the 12 a minute: for the occasional key, such as Allow on the TV's question while
     * {@link SamsungRemote} pairs.
     */
    synchronized void key(String key) throws Failed {
        if (key == null || !Arrays.asList(CLOUD_KEYS).contains(key)) throw new Failed(400, "key");
        need("samsungvd.remoteControl", "This TV takes no remote keys through SmartThings");
        command("samsungvd.remoteControl", "send", key, "PRESS_AND_RELEASED");
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
