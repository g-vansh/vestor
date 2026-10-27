package tv.lucent.home;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The assistant against a stand-in for the Claude API: the request it sends (model, tools, the
 * key only in its header), tool rounds, the safety rule after a web search, every refusal path
 * (no key, the monthly cap, the daily and per-minute limits, bad keys, API errors), what play
 * addresses open, automations and when they fire, and what the phone may see.
 */
public final class ClaudeTest {
    private static int failures, checks;
    private static final String KEY = "sk-ant-api03-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxAbCd";

    static final class FakeWeb implements Claude.Web {
        final List<String> bodies = new ArrayList<>();
        final List<String[]> headers = new ArrayList<>();
        final List<Net.Reply> replies = new ArrayList<>();

        @Override
        public Net.Reply call(String method, String url, String[] h, String contentType, byte[] body) {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            headers.add(h);
            return replies.isEmpty() ? null : replies.remove(0);
        }

        void reply(int code, String json) {
            replies.add(new Net.Reply(code, json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    static final class FakeHands implements Claude.Hands {
        final List<String> ran = new ArrayList<>();

        @Override
        public String context(long now) {
            return "Now: test. E-bikes now: Westgate 0, Erie St 1.";
        }

        @Override
        public String run(String tool, Map<String, Object> in) throws Claude.Failed {
            ran.add(tool + " " + in);
            if ("tv".equals(tool) && "picture_mode".equals(in.get("action")) && !"Movie".equals(in.get("value"))) {
                throw new Claude.Failed("The Samsung has no picture mode called " + in.get("value") + ".");
            }
            return "Done " + tool;
        }
    }

    static Map<String, String> store;

    /** Each assistant gets its own store; {@link #store} is the latest one, for the tests to poke. */
    static Claude make(FakeWeb web, FakeHands hands) {
        final Map<String, String> mine = new HashMap<>();
        store = mine;
        return new Claude(new Claude.Store() {
            @Override
            public String get(String k) {
                return mine.get(k);
            }

            @Override
            public void put(String k, String v) {
                mine.put(k, v);
            }
        }, web, hands);
    }

    static String usage(int in, int out, int searches) {
        return "\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out + ",\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0,\"server_tool_use\":{\"web_search_requests\":" + searches + "}}";
    }

    public static void main(String[] args) throws Exception {
        long now = System.currentTimeMillis();
        // Keys
        check("valid key", Claude.validKey(KEY));
        check("not a key", !Claude.validKey("sk-proj-abc") && !Claude.validKey("sk-ant-short") && !Claude.validKey(KEY + " rm -rf"));
        FakeWeb web = new FakeWeb();
        FakeHands hands = new FakeHands();
        Claude c = make(web, hands);
        Claude.Answer a = c.ask("hello", "phone", now);
        eq("no key yet", "Add a Claude API key on the phone page first (Settings, Claude).", a.error);
        eq("nothing sent without a key", 0, web.bodies.size());
        try {
            c.setKey("nope");
            check("bad key refused", false);
        } catch (Claude.Failed f) {
            check("bad key refused", true);
        }
        c.setKey(KEY);
        eq("hint shows four", "…AbCd", c.keyHint());

        // A plain answer: request shape, cost, markdown out
        web.reply(200, "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":"
                + "\"**Moradabad** in Uttar Pradesh.\\n- Called Peetal Nagri\"}],\"stop_reason\":\"end_turn\"," + usage(3000, 200, 0) + "}");
        a = c.ask("What's the famous brass town in India?", "Sam's iPhone", now);
        eq("answer", "Moradabad in Uttar Pradesh. Called Peetal Nagri", a.text);
        eq("cost", 3000L * 4 + 200 * 20, a.cost);
        eq("month spent", a.cost, c.spent(now));
        String body = web.bodies.get(0);
        Map<String, Object> req = Json.obj(Json.parse(body));
        eq("model", "claude-opus-5-5", Json.str(req, "model", ""));
        eq("fallbacks", "default", Json.str(req, "fallbacks", ""));
        eq("effort", "low", Json.str(Json.obj(req, "output_config"), "effort", ""));
        check("no sampling settings", !req.containsKey("temperature") && !req.containsKey("tool_choice"));
        check("key not in the body", !body.contains(KEY));
        String[] h = web.headers.get(0);
        check("key in its header", "x-api-key".equals(h[0]) && KEY.equals(h[1]) && "2026-10-21".equals(h[3]));
        List<Object> tools = Json.arr(req, "tools");
        eq("tool count", 10, tools.size());
        eq("web search first", "web_search_20260209", Json.str(Json.obj(tools.get(0)), "type", ""));
        boolean strict = true;
        for (int i = 1; i < tools.size(); i++) strict &= Json.bool(Json.obj(tools.get(i)), "strict", false);
        check("client tools strict", strict);
        List<Object> msgs = Json.arr(req, "messages");
        check("context then question", Json.str(Json.obj(msgs.get(0)), "content", "").startsWith("<tv>\nNow: test.")
                && Json.str(Json.obj(msgs.get(0)), "content", "").endsWith("What's the famous brass town in India?"));

        // A follow-up carries the first turn
        web.reply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Yes.\"}],\"stop_reason\":\"end_turn\"," + usage(100, 5, 0) + "}");
        c.ask("Is it in the north?", "Sam's iPhone", now + 1000);
        List<Object> m2 = Json.arr(Json.obj(Json.parse(web.bodies.get(1))), "messages");
        eq("follow-up has three messages", 3, m2.size());
        eq("earlier answer replayed as text", "Moradabad in Uttar Pradesh. Called Peetal Nagri", Json.str(Json.obj(m2.get(1)), "content", ""));

        // Tool round: play, then the answer; Claude's turn goes back unchanged
        web.bodies.clear();
        web.reply(200, "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"abc\"},{\"type\":\"text\",\"text\":\"Opening it.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"play\",\"input\":{\"url\":\"https://www.netflix.com/title/80021955\"}}],"
                + "\"stop_reason\":\"tool_use\"," + usage(2000, 50, 0) + "}");
        web.reply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Better Call Saul is on.\"}],\"stop_reason\":\"end_turn\"," + usage(2100, 10, 0) + "}");
        a = c.ask("play better call saul", "the TV", now + 200000000L);
        eq("final round's words only", "Better Call Saul is on.", a.text);
        eq("did", "[Done play]", a.did.toString());
        Map<String, Object> second = Json.obj(Json.parse(web.bodies.get(1)));
        List<Object> sm = Json.arr(second, "messages");
        Map<String, Object> back = Json.obj(sm.get(1));
        eq("assistant turn returned", "assistant", Json.str(back, "role", ""));
        eq("signature kept", "abc", Json.str(Json.obj(Json.arr(back, "content").get(0)), "signature", ""));
        Map<String, Object> result = Json.obj(Json.arr(Json.obj(sm.get(2)), "content").get(0));
        eq("tool result", "tu_1", Json.str(result, "tool_use_id", ""));

        // An address on another site is refused before Lucent sees it
        hands.ran.clear();
        web.reply(200, "{\"content\":[{\"type\":\"tool_use\",\"id\":\"tu_2\",\"name\":\"play\",\"input\":{\"url\":\"https://evil.example/title/1\"}}],"
                + "\"stop_reason\":\"tool_use\"," + usage(10, 10, 0) + "}");
        web.reply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"I couldn't.\"}],\"stop_reason\":\"end_turn\"," + usage(10, 10, 0) + "}");
        c.ask("play this", "phone", now + 400000000L);
        eq("foreign address never reaches the TV", 0, hands.ran.size());
        check("refusal reported to Claude", web.bodies.get(web.bodies.size() - 1).contains("\"is_error\":true"));

        // After a web search, only play and open_app run
        hands.ran.clear();
        web.reply(200, "{\"content\":[{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\",\"input\":{\"query\":\"x\"}},"
                + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"s1\",\"content\":[]},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_3\",\"name\":\"add_automation\",\"input\":{\"name\":\"x\",\"at\":\"07:00\",\"offset_minutes\":0,"
                + "\"days\":[],\"action\":\"tv_power_off\",\"value\":\"\"}},{\"type\":\"tool_use\",\"id\":\"tu_4\",\"name\":\"open_app\",\"input\":{\"name\":\"Netflix\"}}],"
                + "\"stop_reason\":\"tool_use\"," + usage(10, 10, 1) + "}");
        web.reply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Ok.\"}],\"stop_reason\":\"end_turn\"," + usage(10, 10, 0) + "}");
        a = c.ask("search and set", "phone", now + 600000000L);
        eq("only the app opened", "[open_app {name=Netflix}]", hands.ran.toString());
        eq("no rule kept", 0, c.rules().size());
        eq("search priced", 10L * 4 + 10 * 20 + 10000 + 10 * 4 + 10 * 20, a.cost);

        // Automations: add, describe, list, due once a day, delete
        web.reply(200, "{\"content\":[{\"type\":\"tool_use\",\"id\":\"tu_5\",\"name\":\"add_automation\",\"input\":{\"name\":\"Weekday bikes\","
                + "\"at\":\"7:15\",\"offset_minutes\":0,\"days\":[\"mon\",\"tue\",\"wed\",\"thu\",\"fri\"],\"action\":\"find_ebike\",\"value\":\"\"}}],"
                + "\"stop_reason\":\"tool_use\"," + usage(10, 10, 0) + "}");
        web.reply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Set.\"}],\"stop_reason\":\"end_turn\"," + usage(10, 10, 0) + "}");
        a = c.ask("every weekday at 7:15 start the bike finder", "phone", now + 800000000L);
        List<Claude.Rule> rules = c.rules();
        eq("one rule", 1, rules.size());
        eq("described", "Weekdays at 7:15 AM", Claude.describe(rules.get(0)));
        long mon715 = at(2026, 11, 2, 7, 16), sat715 = at(2026, 10, 31, 7, 16), mon800 = at(2026, 11, 2, 8, 0);
        eq("due Monday", 1, c.due(mon715, 390, 1020).size());
        eq("once a day", 0, c.due(mon715 + 60000, 390, 1020).size());
        eq("not Saturday", 0, c.due(sat715, 390, 1020).size());
        eq("not long after", 0, c.due(mon800 + 7 * 86400000L, 390, 1020).size());
        Object[] call = Claude.call(rules.get(0));
        eq("rule runs as a tool", "lucent {action=find_ebike}", call[0] + " " + call[1]);
        Claude.Rule sunset = new Claude.Rule();
        sunset.id = "ab";
        sunset.name = "Movie at sunset";
        sunset.at = "sunset";
        sunset.offset = -30;
        sunset.action = "picture_mode";
        sunset.value = "Movie";
        check("sunset rule valid", Claude.validRule(sunset));
        eq("sunset described", "Every day 30 min before sunset", Claude.describe(sunset));
        sunset.value = "Movie; rm";
        check("odd mode names refused", !Claude.validRule(sunset));
        c.deleteRule(rules.get(0).id);
        eq("deleted", 0, c.rules().size());

        // Errors and limits
        web.reply(401, "{\"type\":\"error\"}");
        eq("bad key", "Anthropic refused the key. Check it on the phone page.", c.ask("q", "p", now + 900000000L).error);
        web.reply(529, "{}");
        web.reply(529, "{}");
        check("overload retried once", c.ask("q", "p", now + 900100000L).error.startsWith("Claude answered with an error (529)"));
        eq("nothing back", "Couldn't reach Claude. Check the TV's internet.", c.ask("q", "p", now + 900200000L).error);
        web.reply(200, "{\"content\":[],\"stop_reason\":\"refusal\"," + usage(10, 0, 0) + "}");
        eq("refusal", "Claude can't help with that one.", c.ask("q", "p", now + 900300000L).error);
        // per minute
        FakeWeb w2 = new FakeWeb();
        Claude c2 = make(w2, hands);
        c2.setKey(KEY);
        for (int i = 0; i < 6; i++) w2.reply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"," + usage(1, 1, 0) + "}");
        for (int i = 0; i < 6; i++) c2.ask("q" + i, "p", now + i);
        eq("seventh in a minute", "Too many questions in a minute. Try again shortly.", c2.ask("q7", "p", now + 10).error);
        // the cap
        c2.setCap(1);
        store.put("ai_spent", "1000000");
        check("cap reached", c2.ask("q", "p", now + 120000).error.startsWith("This month's Claude budget of $1 is used up"));
        try {
            c2.setCap(500);
            check("cap above 100 refused", false);
        } catch (Claude.Failed f) {
            check("cap above 100 refused", true);
        }

        // Play addresses
        eq("netflix", "https://www.netflix.com/title/80021955?source=30", Claude.Play.link("https://www.netflix.com/title/80021955"));
        eq("netflix localised", "https://www.netflix.com/title/70143836?source=30", Claude.Play.link("https://www.netflix.com/gb/title/70143836?s=a"));
        eq("youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", Claude.Play.link("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        eq("youtu.be", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", Claude.Play.link("https://youtu.be/dQw4w9WgXcQ?t=3"));
        eq("spotify", "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", Claude.Play.link("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"));
        eq("spotify intl", "spotify:album:4aawyAB9vmqN3uQ7FjRGTy", Claude.Play.link("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy"));
        check("http refused", Claude.Play.app("http://www.netflix.com/title/1") == null);
        check("userinfo refused", Claude.Play.app("https://www.netflix.com@evil.com/title/1") == null);
        check("port refused", Claude.Play.app("https://www.netflix.com:444/title/1") == null);
        check("lookalike refused", Claude.Play.app("https://netflix.com.evil.com/title/1") == null);
        check("odd characters refused", Claude.Play.app("https://www.youtube.com/watch?v=a\"b") == null);
        check("intent scheme refused", Claude.Play.app("intent://x#Intent;end") == null);
        check("not a netflix title", Claude.Play.link("https://www.netflix.com/browse") == null);

        // What the phone sees
        Jw w = new Jw();
        Claude.answerJson(w, c.history().get(0));
        check("history has no key", !w.toString().contains(KEY));
        eq("every question kept", 11, c.history().size());
        eq("plain", "One. Two", Claude.plain("# One\n\n* Two"));

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        c.clear();
        c.set(y, m - 1, d, h, min);
        return c.getTimeInMillis();
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want <" + want + ">, got <" + got + ">");
        }
    }
}
