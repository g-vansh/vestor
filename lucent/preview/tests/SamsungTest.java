package tv.lucent.home;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The newer outside links, against stand-ins: the SmartThings sign-in and picture modes (with a
 * fake Samsung that also misbehaves), the 15-minute rain line, the 4K and HDR advice, the
 * Bluebikes link a phone alert opens, and the ntfy and Alexa words.
 */
public final class SamsungTest {
    private static int failures, checks;

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        code();
        flow();
        hostile();
        nowcast();
        picture();
        rentLink();
        ntfy();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
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

    // ---- The pasted code ---------------------------------------------------------------------

    private static void code() {
        String st = "Zm9vYmFyYmF6cXV4";
        eq("bare code", "AbC123", Samsung.code("  AbC123 ", st));
        eq("httpbin address", "XYZ789", Samsung.code("https://httpbin.org/get?code=XYZ789&state=" + st, st));
        eq("state first", "XYZ789", Samsung.code("https://httpbin.org/get?state=" + st + "&code=XYZ789", st));
        eq("wrong state", null, Samsung.code("https://httpbin.org/get?code=XYZ789&state=AAAAAAAAAAAAAAAA", st));
        String json = "{\n  \"args\": {\n    \"code\": \"Q1w2E3\", \n    \"state\": \"" + st + "\"\n  }, \"headers\": {}\n}";
        eq("httpbin json", "Q1w2E3", Samsung.code(json, st));
        eq("json wrong state", null, Samsung.code(json.replace(st, "BBBBBBBBBBBBBBBB"), st));
        eq("no code", null, Samsung.code("https://httpbin.org/get?error=access_denied&state=" + st, st));
        eq("odd characters", null, Samsung.code("abc<script>", st));
        eq("null", null, Samsung.code(null, st));
        char[] big = new char[4100];
        Arrays.fill(big, 'a');
        eq("too long", null, Samsung.code(new String(big), st));
        eq("code only too short", null, Samsung.code("abc", st));
    }

    // ---- A fake SmartThings ------------------------------------------------------------------

    static final class Mem implements Samsung.Store {
        final Map<String, String> m = new HashMap<>();

        @Override
        public String get(String key) {
            return m.get(key);
        }

        @Override
        public void put(String key, String value) {
            m.put(key, value);
        }
    }

    static final class Call {
        final String method, url, contentType, body;
        final String[] headers;

        Call(String method, String url, String[] headers, String contentType, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.contentType = contentType;
            this.body = body == null ? null : new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            for (int i = 0; headers != null && i + 1 < headers.length; i += 2) if (headers[i].equalsIgnoreCase(name)) return headers[i + 1];
            return null;
        }
    }

    /** Answers like SmartThings: tokens that rotate, one TV, its modes, and commands. */
    static final class Fake implements Samsung.Web {
        final List<Call> calls = new ArrayList<>();
        int issued;
        String access = "", refresh = "";
        String mode = "Standard";
        int commandStatus = 200, tokenStatus = 200;
        boolean revokeOnce;
        String devices = null, status = null, token = null;

        @Override
        public Net.Reply call(String method, String url, String[] headers, String contentType, byte[] body) {
            Call c = new Call(method, url, headers, contentType, body);
            calls.add(c);
            if (url.equals(Samsung.API + "/oauth/token")) {
                if (tokenStatus != 200) return reply(tokenStatus, "{\"error\":\"invalid_grant\"}");
                if (token != null) return reply(200, token);
                if (c.body.contains("grant_type=refresh_token") && !c.body.contains("refresh_token=" + refresh)) {
                    return reply(400, "{\"error\":\"invalid_grant\"}");
                }
                issued++;
                access = "A" + issued;
                refresh = "R" + issued;
                return reply(200, "{\"access_token\":\"" + access + "\",\"token_type\":\"bearer\",\"refresh_token\":\"" + refresh
                        + "\",\"expires_in\":86399,\"scope\":\"r:devices:* x:devices:*\"}");
            }
            if (!("Bearer " + access).equals(c.header("Authorization"))) return reply(401, "{}");
            if (revokeOnce) {
                revokeOnce = false;
                return reply(401, "{}");
            }
            if (url.startsWith(Samsung.API + "/devices?capability=")) {
                if (devices != null) return reply(200, devices);
                if (!url.endsWith("custom.picturemode")) return reply(200, "{\"items\":[]}");
                return reply(200, "{\"items\":[{\"deviceId\":\"0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0\",\"label\":\"Living Room TV\","
                        + "\"name\":\"[TV] Samsung 7 Series (43)\"}]}");
            }
            if (url.endsWith("/components/main/capabilities/custom.picturemode/status")) {
                if (status != null) return reply(200, status);
                return reply(200, "{\"pictureMode\":{\"value\":\"" + mode + "\"},\"supportedPictureModes\":{\"value\":"
                        + "[\"Dynamic\",\"Standard\",\"Natural\",\"Movie\"]}}");
            }
            if (url.endsWith("/commands") && "POST".equals(method)) {
                if (commandStatus != 200) return reply(commandStatus, "{}");
                Map<String, Object> m = Json.obj(Json.parse(c.body));
                List<Object> cmds = Json.arr(m, "commands");
                Map<String, Object> cmd = Json.obj(cmds.get(0));
                mode = (String) Json.arr(cmd, "arguments").get(0);
                return reply(200, "{\"results\":[{\"status\":\"ACCEPTED\"}]}");
            }
            return reply(404, "{}");
        }

        Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    static Net.Reply reply(int code, String body) {
        return new Net.Reply(code, body.getBytes(StandardCharsets.UTF_8));
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

    private static void flow() {
        Mem mem = new Mem();
        Fake web = new Fake();
        final Samsung s = new Samsung(mem, web);
        eq("not connected at first", false, s.connected());
        eq("bad client id", 400, status(() -> s.begin("short", "secret-secret")));
        eq("bad secret", 400, status(() -> s.begin("0f1e2d3c-4b5a-6978", "has space in it")));
        eq("finish before begin", 409, status(() -> s.finish("AbC123")));
        String id = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", secret = "s3cr3t-Value_9.x~";
        String[] url = new String[1];
        eq("begin", 0, status(() -> url[0] = s.begin(id, secret)));
        yes("authorize address", url[0].startsWith("https://api.smartthings.com/v1/oauth/authorize?client_id=" + id + "&"));
        yes("scopes encoded", url[0].contains("&scope=r%3Adevices%3A*%20x%3Adevices%3A*&"));
        yes("redirect encoded", url[0].contains("&redirect_uri=https%3A%2F%2Fhttpbin.org%2Fget&"));
        yes("response type", url[0].contains("&response_type=code&"));
        String state = url[0].substring(url[0].indexOf("&state=") + 7);
        yes("state is random and long", state.matches("[A-Za-z0-9_-]{16}"));
        eq("begun", true, s.begun());
        eq("wrong state refused", 400, status(() -> s.finish("https://httpbin.org/get?code=AbC123&state=AAAAAAAAAAAAAAAA")));
        eq("nothing sent for a wrong state", 0, web.calls.size());
        String[] device = new String[1];
        eq("finish", 0, status(() -> device[0] = s.finish("https://httpbin.org/get?code=AbC123&state=" + state)));
        eq("device label", "Living Room TV", device[0]);
        eq("connected", true, s.connected());
        eq("modes", Arrays.asList("Dynamic", "Standard", "Natural", "Movie"), s.modes());
        eq("mode", "Standard", s.mode());
        Call tok = web.calls.get(0);
        eq("token method", "POST", tok.method);
        eq("token content type", "application/x-www-form-urlencoded", tok.contentType);
        eq("basic auth, standard base64", "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)),
                tok.header("Authorization"));
        eq("token form", "grant_type=authorization_code&code=AbC123&client_id=" + id + "&redirect_uri=https%3A%2F%2Fhttpbin.org%2Fget",
                tok.body);
        eq("state used once", "", mem.get("st_state"));
        eq("refresh token kept", "R1", mem.get("st_refresh"));

        eq("set Movie", 0, status(() -> s.setMode("Movie")));
        Call cmd = web.last();
        eq("command address", Samsung.API + "/devices/" + id + "/commands", cmd.url);
        eq("command body", "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.picturemode\",\"command\":\"setPictureMode\","
                + "\"arguments\":[\"Movie\"]}]}", cmd.body);
        eq("bearer", "Bearer A1", cmd.header("Authorization"));
        eq("mode now", "Movie", s.mode());
        eq("unknown mode", 400, status(() -> s.setMode("Vivid")));
        eq("hostile mode", 400, status(() -> s.setMode("Movie\"],\"x\":[\"")));
        eq("empty mode", 400, status(() -> s.setMode("")));
        eq("pick, any case", "Dynamic", s.pick("Vivid", "dynamic"));
        eq("pick falls back", "Dynamic", s.pick("Vivid"));

        // The access token lapses: one refresh, and both tokens are replaced.
        mem.put("st_expires", "0");
        int before = web.calls.size();
        eq("set after expiry", 0, status(() -> s.setMode("Dynamic")));
        Call refresh = web.calls.get(before);
        eq("refresh form", "grant_type=refresh_token&refresh_token=R1&client_id=" + id, refresh.body);
        eq("rotated refresh", "R2", mem.get("st_refresh"));
        eq("rotated access", "A2", mem.get("st_access"));
        eq("mode after refresh", "Dynamic", web.mode);

        // Twenty days without a refresh: keepAlive refreshes once, and not again the same day.
        mem.put("st_refreshed", Long.toString(System.currentTimeMillis() - 21L * 86400000L));
        s.keepAlive();
        eq("kept alive", "R3", mem.get("st_refresh"));
        s.keepAlive();
        eq("not twice", "R3", mem.get("st_refresh"));

        // Revoked early: one fresh token and the command again.
        web.revokeOnce = true;
        eq("set after 401", 0, status(() -> s.setMode("Natural")));
        eq("refreshed after 401", "R4", mem.get("st_refresh"));
        eq("mode after 401", "Natural", web.mode);

        // The TV is off.
        web.commandStatus = 409;
        eq("tv off", 502, status(() -> s.setMode("Movie")));
        eq("tv off says so", "The TV is off or busy", s.error());
        web.commandStatus = 200;
        eq("tv back", 0, status(() -> s.setMode("Movie")));
        eq("error clears", "", s.error());

        // Samsung withdraws the sign-in: the TV says connect again.
        web.tokenStatus = 400;
        mem.put("st_expires", "0");
        eq("sign-in lapsed", 409, status(() -> s.setMode("Standard")));
        yes("lapsed message", s.error().startsWith("Samsung sign-in expired"));
        eq("not connected after lapse", false, s.connected());
        web.tokenStatus = 200;

        s.disconnect();
        for (String k : mem.m.keySet()) eq("forgotten " + k, "", mem.m.get(k));
        eq("disconnected", false, s.connected());
        eq("no command when disconnected", 409, status(() -> s.setMode("Movie")));
    }

    private static Samsung connected(Fake web) {
        Mem mem = new Mem();
        Samsung s = new Samsung(mem, web);
        String[] url = new String[1];
        status(() -> url[0] = s.begin("0f1e2d3c-4b5a-6978", "secret-secret"));
        String state = url[0].substring(url[0].indexOf("&state=") + 7);
        status(() -> s.finish("?code=AbCdEf&state=" + state));
        return s;
    }

    /** A misbehaving SmartThings: nothing it sends gets further than a refusal. */
    private static void hostile() {
        Fake web = new Fake();
        web.token = "{\"access_token\":\"" + new String(new char[5000]).replace('\0', 'a') + "\",\"refresh_token\":\"r\",\"expires_in\":1}";
        Samsung s = connected(web);
        eq("huge token refused", false, s.connected());

        web = new Fake();
        web.token = "not json at all";
        eq("garbage token", false, connected(web).connected());

        web = new Fake();
        web.token = "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[";
        eq("deep token", false, connected(web).connected());

        web = new Fake();
        web.devices = "{\"items\":[{\"deviceId\":\"../../../users/me\",\"label\":\"x\"},{\"deviceId\":\"abc\",\"label\":\"y\"}]}";
        eq("odd device ids skipped", false, connected(web).connected());

        web = new Fake();
        web.status = "{\"pictureMode\":{\"value\":\"<img src=x>\"},\"supportedPictureModes\":{\"value\":[\"Movie\",\"a<b\","
                + "\"" + new String(new char[80]).replace('\0', 'x') + "\",7,null,{\"x\":1},\"Standard\"]}}";
        Samsung h = connected(web);
        eq("hostile modes filtered", Arrays.asList("Movie", "Standard"), h.modes());
        eq("hostile current mode ignored", "", h.mode());

        web = new Fake();
        StringBuilder many = new StringBuilder("{\"supportedPictureModes\":{\"value\":[");
        for (int i = 0; i < 40; i++) many.append(i == 0 ? "" : ",").append("\"Mode").append((char) ('a' + i % 26)).append(i).append('"');
        web.status = many.append("]}}").toString();
        eq("modes capped", 16, connected(web).modes().size());
    }

    // ---- Rain in the next two hours ----------------------------------------------------------

    private static Model.Weather rain(double[] mm, int[] codes, boolean h24) {
        StringBuilder b = new StringBuilder("{\"utc_offset_seconds\":-14400,\"minutely_15\":{\"time\":[");
        for (int i = 0; i < mm.length; i++) {
            int minutes = 15 * 60 + i * 15;
            b.append(i == 0 ? "" : ",").append(String.format("\"2026-10-27T%02d:%02d\"", minutes / 60, minutes % 60));
        }
        b.append("],\"precipitation\":[");
        for (int i = 0; i < mm.length; i++) b.append(i == 0 ? "" : ",").append(mm[i]);
        b.append("],\"weather_code\":[");
        for (int i = 0; i < mm.length; i++) b.append(i == 0 ? "" : ",").append(codes == null ? (mm[i] > 0 ? 61 : 3) : codes[i]);
        b.append("]}}");
        Model.Weather w = new Model.Weather();
        Weather.nowcast(Json.obj(Json.parse(b.toString())), w, h24);
        return w;
    }

    private static void nowcast() {
        yes("url asks for 15-minute rain", Weather.url(42.3556, -71.104).contains("&minutely_15=precipitation,weather_code&forecast_minutely_15=8&"));
        yes("url rounds the place", Weather.url(42.3556, -71.104).contains("latitude=42.36&longitude=-71.10&"));
        Model.Weather w = rain(new double[8], null, false);
        eq("dry", "Dry for the next 2 hours", w.rain);
        eq("dry is not wet", false, w.wetSoon);
        w = rain(new double[] {0, 0, 0, 0.3, 0.6, 0.2, 0, 0}, null, false);
        eq("rain coming", "Rain from 3:45 PM", w.rain);
        eq("rain at", 1793130300000L, w.rainAt);
        eq("rain until", 1793133000000L, w.rainUntil);
        eq("wet soon", true, w.wetSoon);
        w = rain(new double[] {0.5, 0.4, 0.05, 0, 0, 0, 0, 0}, null, false);
        eq("raining now", "Rain until 3:30 PM", w.rain);
        w = rain(new double[] {0.5, 0.4, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5}, null, false);
        eq("raining throughout", "Rain for the next 2 hours", w.rain);
        eq("no end", 0L, w.rainUntil);
        w = rain(new double[] {0, 0.2, 0.2, 0, 0, 0, 0, 0}, new int[] {3, 73, 73, 3, 3, 3, 3, 3}, true);
        eq("snow, 24-hour", "Snow from 15:15", w.rain);
        w = rain(new double[] {0.09, 0.05, 0, 0, 0, 0, 0, 0}, null, false);
        eq("a trace is dry", "Dry for the next 2 hours", w.rain);
        w = rain(new double[] {0, 0, 0, 0}, null, false);
        eq("one hour of steps", "Dry for the next 60 minutes", w.rain);
        Model.Weather none = new Model.Weather();
        Weather.nowcast(Json.obj(Json.parse("{\"current\":{}}")), none, false);
        eq("no steps, no line", "", none.rain);
        Model.Weather odd = new Model.Weather();
        Weather.nowcast(Json.obj(Json.parse("{\"minutely_15\":{\"time\":[\"yesterday\",7],\"precipitation\":[\"x\",{}]}}")), odd, false);
        eq("odd steps, no line", "", odd.rain);
    }

    // ---- 4K and HDR --------------------------------------------------------------------------

    private static Model.Picture pic(int w, int h, int hz, int maxW, int maxH, int max4kHz, String... hdr) {
        Model.Picture p = new Model.Picture();
        p.width = w;
        p.height = h;
        p.hz = hz;
        p.maxWidth = maxW;
        p.maxHeight = maxH;
        p.max4kHz = max4kHz;
        p.uiWidth = 1920;
        p.uiHeight = 1080;
        p.hdr.addAll(Arrays.asList(hdr));
        p.describe();
        return p;
    }

    private static void picture() {
        Model.Picture p = pic(3840, 2160, 60, 3840, 2160, 60, "HDR10", "HDR10+", "HLG");
        eq("all well", "4K, 60 Hz, HDR10, HDR10+ and HLG", p.line);
        eq("no advice", "", p.advice);
        eq("note", "Apps draw at 1080p, films play at the full 4K.", p.note);
        p = pic(3840, 2160, 60, 3840, 2160, 60, "HDR10");
        eq("one hdr", "4K, 60 Hz, HDR10", p.line);
        p = pic(1920, 1080, 60, 3840, 2160, 60, "HDR10");
        eq("stick sends 1080p", "Fire TV is sending 1080p.", p.problem);
        eq("resolution fix", "Fire TV Settings: Video Resolution to Auto.", p.fix);
        p = pic(1920, 1080, 60, 1920, 1080, 0);
        eq("tv offers no 4K", "The Samsung offers no 4K on this input.", p.problem);
        eq("input signal plus", "Turn on Input Signal Plus for HDMI 1.", p.fix);
        yes("where to find it", p.advice.endsWith("Settings, General, External Device Manager."));
        p = pic(3840, 2160, 30, 3840, 2160, 30, "HDR10");
        eq("4K at 30", "4K stops at 30 Hz on this input.", p.problem);
        p = pic(3840, 2160, 60, 3840, 2160, 60);
        eq("no hdr", "No HDR reaches the TV.", p.problem);
        eq("no hdr fix", "Turn on Input Signal Plus for HDMI 1.", p.fix);
        p = pic(0, 0, 0, 0, 0, 0);
        eq("unknown says nothing", "", p.line + p.advice + p.note);
        p = pic(1280, 720, 60, 1280, 720, 0);
        eq("720p", "720p, 60 Hz", p.line);
    }

    // ---- The link a phone alert opens --------------------------------------------------------

    private static String link(String rent) {
        Model.Station s = new Model.Station();
        s.rent = rent;
        return Gbfs.rentLink(s);
    }

    private static void rentLink() {
        eq("feed link", "https://bos.lft.to/lastmile_qr_scan", link("https://bos.lft.to/lastmile_qr_scan"));
        eq("bluebikes host", "https://www.bluebikes.com/app", link("https://www.bluebikes.com/app"));
        eq("lyft host", "https://ride.lyft.com/x?y=1", link("https://ride.lyft.com/x?y=1"));
        for (String bad : new String[] {"", "http://bos.lft.to/x", "https://evil.example/x", "https://bos.lft.to.evil.example/x",
            "https://evillft.to/x", "javascript:alert(1)", "https://bos.lft.to/x\nX-Header: 1", "https://bos.lft.to/x y",
            "https://user@bos.lft.to/x", "https://bos.lft.to:8443/x", "intent://scan#Intent;end"}) {
            eq("refused " + bad, Gbfs.RENT, link(bad));
        }
        eq("no station", Gbfs.RENT, Gbfs.rentLink(null));
        String info = "{\"data\":{\"stations\":[{\"station_id\":\"a\",\"name\":\"One Memorial Drive\",\"lat\":42.36,\"lon\":-71.08,"
                + "\"rental_uris\":{\"android\":\"https://bos.lft.to/a\",\"ios\":\"https://bos.lft.to/i\"}},"
                + "{\"station_id\":\"b\",\"name\":\"No link\",\"lat\":42.35,\"lon\":-71.1}]}}";
        List<Model.Station> list = Gbfs.stations(info, 42.3556, -71.104);
        Model.Station a = Gbfs.find(list, "a"), b = Gbfs.find(list, "b");
        eq("ios link parsed", "https://bos.lft.to/i", a.rent);
        eq("copied with the station", "https://bos.lft.to/i", Gbfs.copy(a).rent);
        eq("no link, fallback", Gbfs.RENT, Gbfs.rentLink(b));
    }

    // ---- ntfy and Alexa ----------------------------------------------------------------------

    private static void ntfy() {
        String topic = "lc-abcdefghijklmnopqrstuvwxyz";
        eq("push url", "https://ntfy.sh/" + topic + "/publish?title=E-bike%20ready%20at%20Westgate&message=2%20e-bikes%20%26%20more%3F"
                + "&priority=5&tags=bike&click=https%3A%2F%2Fbos.lft.to%2Flastmile_qr_scan",
                Ntfy.pushUrl(topic, "E-bike ready at Westgate", "2 e-bikes & more?", 5, "bike", Gbfs.RENT));
        yes("priority capped", Ntfy.pushUrl(topic, "t", "m", 9, "", "").endsWith("&priority=5"));
        yes("priority floor", Ntfy.pushUrl(topic, "t", "m", -3, "", "").endsWith("&priority=1"));
        yes("no click when empty", !Ntfy.pushUrl(topic, "t", "m", 3, "", "").contains("click"));
        List<String> words = Arrays.asList(Ntfy.WORDS);
        for (String[] sw : Ntfy.SWITCHES) {
            yes("switch on word known: " + sw[2], words.contains(sw[2]));
            yes("switch off word known: " + sw[3], sw[3].isEmpty() || words.contains(sw[3]));
            yes("phrase starts with Alexa: " + sw[1], sw[1].startsWith("Alexa, turn on "));
        }
        final List<String> heard = new ArrayList<>();
        Ntfy n = new Ntfy(topic, new Ntfy.Sink() {
            @Override
            public void command(String word) {
                heard.add(word);
            }
        });
        long now = 1793130300000L;
        int id = 0;
        for (String w : Ntfy.WORDS) n.line("{\"id\":\"m" + id++ + "\",\"time\":" + now / 1000 + ",\"event\":\"message\",\"message\":\" " + w.toUpperCase() + " \"}", now);
        eq("every word heard", words, heard);
        heard.clear();
        n.line("{\"id\":\"m0\",\"time\":" + now / 1000 + ",\"event\":\"message\",\"message\":\"find-ebike\"}", now);
        eq("repeat ignored", 0, heard.size());
        n.line("{\"id\":\"old\",\"time\":" + (now / 1000 - 600) + ",\"event\":\"message\",\"message\":\"glare-on\"}", now);
        eq("old ignored", 0, heard.size());
        n.line("{\"id\":\"x1\",\"time\":" + now / 1000 + ",\"event\":\"message\",\"message\":\"rm -rf /\"}", now);
        eq("unknown word ignored", 0, heard.size());
        n.line("{\"id\":\"x2\",\"time\":" + now / 1000 + ",\"event\":\"open\",\"message\":\"glare-on\"}", now);
        eq("not a message ignored", 0, heard.size());
        String alexa = Ntfy.publishUrl(topic, "glare-on");
        eq("alexa url", "https://ntfy.sh/" + topic + "/publish?message=glare-on", alexa);
    }
}
