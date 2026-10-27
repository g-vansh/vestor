package tv.lucent.home;

import android.os.SystemClock;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The control center's view of the TV, as one JSON document (web/API.md describes every field).
 * Secrets the owner typed (the MBTA key, calendar addresses, the Wi-Fi password) are never sent
 * back in full: the page only learns that they are set.
 */
final class State {
    private State() {}

    /**
     * The assistant: whether a key is set (with only its last four characters), the month's
     * spending against the cap, the question being answered, the last answers and the automations.
     */
    private static void assistant(Jw w, Hub h, long now) {
        Claude c = h.assistant.claude;
        w.key("assistant").obj().put("on", c.hasKey()).put("hint", c.keyHint()).put("model", Claude.MODEL).put("cap", c.cap())
                .put("spent", c.spent(now)).put("perDay", Claude.PER_DAY);
        Claude.Answer latest = h.assistant.latest;
        if (latest != null && latest.busy) {
            w.key("asking");
            Claude.answerJson(w, latest);
        }
        w.key("history").arr();
        for (Claude.Answer a : c.history()) Claude.answerJson(w, a);
        w.endArr().key("rules").arr();
        for (Claude.Rule r : c.rules()) Claude.ruleJson(w, r);
        w.endArr().end();
    }

    static String json(Hub h, Keys.Device me, long now) {
        Jw w = new Jw().obj();
        TimeZone zone = TimeZone.getDefault();
        long[] sun = Sun.times(now, h.prefs.lat, h.prefs.lon, zone);
        double moon = Sun.moonPhase(now);
        w.key("tv").obj().put("name", "Lucent").put("version", BuildInfo.VERSION).put("front", h.front)
                .put("mode", h.night ? "night" : "day").put("modeSetting", h.prefs.get(Prefs.MODE, "auto"))
                .put("nextChange", h.nextChange).put("sunrise", sun[0]).put("sunset", sun[1])
                .put("moon", Sun.moonName(moon)).put("moonLit", Sun.moonLit(moon)).put("now", now)
                .put("access", h.prefs.get(Prefs.ACCESS, "lan")).put("ringing", Alarm.ringing()).key("urls").arr();
        for (String u : h.console.urls()) w.val(u);
        w.endArr().end();
        w.key("me").obj().put("name", me.name).put("id", me.selector.substring(0, 4)).end();
        weather(w, h.weather);
        Model.Air a = h.air;
        if (a != null && a.ok) {
            w.key("air").obj().put("aqi", a.aqi).put("aqiWord", Extras.aqiWord(a.aqi)).put("uv", a.uv)
                    .put("uvWord", Extras.uvWord(a.uv)).end();
        }
        w.key("alerts").arr();
        for (Model.Alert x : h.alerts) {
            w.obj().put("event", x.event).put("headline", x.headline).put("severity", x.severity).put("expires", x.expires).end();
        }
        w.endArr();
        bikes(w, h.bikes);
        forecast(w, h.bikeForecast);
        finder(w);
        transit(w, h.transit, now);
        sky(w, h.sky);
        calendar(w, h, now);
        w.key("notes").raw(h.notes.json(20));
        w.key("photos").obj().put("count", h.album.count()).put("bytes", h.album.bytes()).put("freeBytes", h.album.free())
                .put("limit", Album.MAX_PHOTOS).put("album", h.prefs.choice(Prefs.ALBUM)).key("items").raw(h.album.json(120)).end();
        String plans = h.plans.json(now, zone);
        w.key("list").raw(plans.substring(plans.indexOf('['), plans.indexOf("],\"countdowns\"") + 1));
        media(w, h.media.snapshot());
        audio(w, h.audio);
        picture(w, h.picture);
        settings(w, h);
        w.key("stations").arr().obj().put("id", "auto").put("name", "Westgate").end();
        Model.Bikes b = h.bikes;
        if (b != null) {
            for (Model.Station s : b.nearby) w.obj().put("id", s.id).put("name", Gbfs.shortName(s, false)).end();
        }
        w.endArr();
        // The second dock bike finder watches: Erie St by default, or none, or another nearby.
        w.key("stations2").arr().obj().put("id", "auto").put("name", "Erie St").end().obj().put("id", "none").put("name", "None").end();
        if (b != null) {
            for (Model.Station s : b.nearby) w.obj().put("id", s.id).put("name", Gbfs.shortName(s, false)).end();
        }
        w.endArr();
        w.key("devices").arr();
        for (Keys.Device d : h.keys.list()) {
            w.obj().put("id", d.selector.substring(0, 4)).put("name", d.name).put("seen", d.seen).put("ip", d.ip)
                    .put("me", d.selector.equals(me.selector)).end();
        }
        w.endArr();
        w.key("health").arr();
        for (String[] r : Pace.report(now)) {
            w.obj().put("host", r[0]).put("state", r[1]).put("sent", Long.parseLong(r[2])).put("ok", Long.parseLong(r[3]))
                    .put("unchanged", Long.parseLong(r[4])).put("limited", Long.parseLong(r[5])).put("failed", Long.parseLong(r[6]))
                    .put("perMinute", Long.parseLong(r[7])).end();
        }
        w.endArr();
        alexa(w, h);
        push(w, h);
        samsung(w, h);
        return w.end().toString();
    }

    /** The Alexa link: one URL switch per command, each with the phrase that turns it on. */
    private static void alexa(Jw w, Hub h) {
        String topic = h.prefs.get(Prefs.ALEXA_TOPIC, "");
        boolean on = "on".equals(h.prefs.get(Prefs.ALEXA, "off")) && Ntfy.validTopic(topic);
        w.key("alexa").obj().put("on", on).put("url", on ? Ntfy.publishUrl(topic, "find-ebike") : "")
                .put("stopUrl", on ? Ntfy.publishUrl(topic, "stop-ebike") : "");
        if (on) {
            w.key("commands").arr();
            for (String[] c : Ntfy.SWITCHES) {
                w.obj().put("name", c[0]).put("say", c[1]).put("on", Ntfy.publishUrl(topic, c[2]))
                        .put("off", c[3].isEmpty() ? "" : Ntfy.publishUrl(topic, c[3])).end();
            }
            w.endArr();
        }
        w.end();
    }

    private static void push(Jw w, Hub h) {
        String topic = h.prefs.get(Prefs.PUSH_TOPIC, "");
        boolean on = h.pushOn();
        w.key("push").obj().put("on", on).put("topic", on ? topic : "").put("url", on ? "https://ntfy.sh/" + topic : "").end();
        assistant(w, h, System.currentTimeMillis());
    }

    /** Only what the page shows: never the app's secret or the tokens. */
    private static void samsung(Jw w, Hub h) {
        Samsung s = h.samsung;
        Prefs p = h.prefs;
        boolean connected = s.connected();
        w.key("samsung").obj().put("connected", connected).put("device", connected ? s.device() : "").key("modes").arr();
        if (connected) for (String m : s.modes()) w.val(m);
        w.endArr().put("mode", connected ? s.mode() : "").put("glare", connected && h.glare())
                .put("follow", p.choice(Prefs.PICTURE_FOLLOW)).put("dayMode", connected ? h.picked(Prefs.DAY_MODE) : "")
                .put("nightMode", connected ? h.picked(Prefs.NIGHT_MODE) : "").put("glareMode", connected ? h.picked(Prefs.GLARE_MODE) : "")
                .put("error", s.error()).put("setup", s.begun() ? "begun" : "none");
        if (connected) {
            w.put("power", s.power()).put("volume", s.volume()).put("muted", s.muted()).put("input", s.input()).key("inputs").arr();
            for (String[] in : s.inputs()) w.obj().put("id", in[0]).put("name", in[1]).end();
            w.endArr().put("sound", s.sound()).key("sounds").arr();
            for (String m : s.sounds()) w.val(m);
            w.endArr().key("can").obj().put("power", s.reports("switch")).put("volume", s.reports("audioVolume"))
                    .put("mute", s.reports("audioMute"))
                    .put("input", s.reports("samsungvd.mediaInputSource") || s.reports("mediaInputSource"))
                    .put("sound", s.reports("custom.soundmode")).put("keys", s.reports("samsungvd.remoteControl"))
                    .put("apps", s.reports("custom.launchapp")).end();
        }
        // The remote over the home network: never its token, pin or MAC.
        SamsungRemote r = h.remote;
        w.key("remote").obj().put("state", r.state()).put("address", r.address()).put("paired", r.paired()).put("label", r.label())
                .put("model", r.model()).put("canWake", r.canWake()).put("error", r.error()).end();
        w.end();
    }

    private static void weather(Jw w, Model.Weather x) {
        if (x == null || !x.ok) return;
        w.key("weather").obj().put("ok", true).put("city", x.city).put("tempF", Model.Weather.f(x.tempC)).put("tempC", x.tempC)
                .put("feelsF", Model.Weather.f(x.feelsC)).put("hiF", Model.Weather.f(x.hiC)).put("loF", Model.Weather.f(x.loC))
                .put("condition", x.condition).put("code", x.code).put("day", x.day).put("humidity", x.humidity)
                .put("windMph", x.windKph < 0 ? -1 : Math.round(x.windKph / 1.609344)).put("windDir", Weather.compass(x.windDir))
                .put("updated", x.updated).put("model", Weather.SOURCE).key("hours").arr();
        for (int i = 0; i < x.hourC.size(); i++) {
            w.obj().put("label", x.hourLabel.get(i)).put("f", Model.Weather.f(x.hourC.get(i))).put("c", Model.Weather.c(x.hourC.get(i)))
                    .put("code", x.hourCode.get(i)).put("day", x.hourDay.get(i)).put("rain", i < x.hourRain.size() ? x.hourRain.get(i) : -1)
                    .end();
        }
        w.endArr();
        if (!x.rain.isEmpty()) w.key("rain").obj().put("summary", x.rain).put("at", x.rainAt).put("until", x.rainUntil).end();
        w.end();
    }

    private static void bikes(Jw w, Model.Bikes b) {
        if (b == null) return;
        w.key("bikes").obj().put("ok", b.ok).put("label", b.label).put("alt", b.altId).put("altLabel", b.altLabel)
                .put("fetched", b.fetched).put("published", b.published)
                .key("stations").arr();
        for (Model.Station s : b.stations) {
            w.obj().put("id", s.id).put("name", s.name).put("short", Gbfs.shortName(s, false)).put("ebikes", s.rentableEbikes())
                    .put("classic", s.rentableClassic()).put("docks", s.docks).put("capacity", s.capacity)
                    .put("renting", s.installed && s.renting).put("miles", s.metres / 1609.344).end();
        }
        w.endArr().end();
    }

    /** The e-bike forecast (see Forecast): times in ms, 0 when beyond eight hours. */
    private static void forecast(Jw w, Forecast.Result r) {
        if (r == null) return;
        w.key("forecast").obj().put("empty", r.empty).put("dayType", r.dayType)
                .key("pool").obj().put("lo", r.poolLo).put("med", r.poolMed).put("hi", r.poolHi).end()
                .key("first").obj().put("lo", r.firstLo).put("med", r.firstMed).put("hi", r.firstHi).end()
                .key("next").obj().put("lo", r.nextLo).put("med", r.nextMed).put("hi", r.nextHi).end()
                .put("morning", r.morning).put("morningAt", r.morningAt).put("morning1h", r.morning1h).put("morning2h", r.morning2h).end();
    }

    private static void finder(Jw w) {
        Model.Finder f = Finder.get().snapshot();
        String[] names = {"idle", "searching", "found", "timedout", "stopped"};
        w.key("finder").obj().put("state", names[Math.max(0, Math.min(4, f.state))]).put("started", f.started)
                .put("deadline", f.deadline).put("ended", f.ended).put("ebikes", f.ebikes).put("checks", f.checks)
                .put("station", f.station).put("ebikes2", f.ebikes2).put("alarm", Alarm.ringing()).put("offline", f.offline).end();
    }

    private static void transit(Jw w, Model.Transit t, long now) {
        if (t == null) return;
        w.key("transit").obj().put("fetched", t.fetched).put("live", t.live).key("lines").arr();
        for (Model.Line l : t.lines) {
            w.obj().put("id", l.id).put("chip", l.chip).put("label", l.label).put("stop", l.stop)
                    .put("color", String.format(Locale.US, "#%06X", l.color & 0xFFFFFF)).put("idle", l.idle).key("minutes").arr();
            // Minutes were worked out when fetched: older than 90 s they are not shown.
            if (now - t.fetched < 90000L) for (Integer m : l.minutes) w.val(m);
            w.endArr().end();
        }
        w.endArr().end();
    }

    private static void sky(Jw w, Model.Sky s) {
        if (s == null) return;
        w.key("sky").obj().put("fetched", s.fetched).put("source", s.source).put("inRange", s.inRange).key("flights").arr();
        for (Model.Flight f : s.flights) {
            w.obj().put("name", Sky.flightName(f)).put("from", f.from).put("to", f.to).put("fromCity", f.fromCity)
                    .put("toCity", f.toCity).put("alt", f.altFt).put("knots", f.knots).put("miles", f.miles).put("type", f.type)
                    .put("track", f.track).put("trend", f.fpm <= -300 ? "Descending" : f.fpm >= 300 ? "Climbing" : "Level").end();
        }
        w.endArr().end();
    }

    private static void calendar(Jw w, Hub h, long now) {
        List<String[]> feeds = h.calendars();
        w.key("calendar").obj().key("events").arr();
        for (Ical.Event e : h.upcoming(8)) {
            String cal = e.calendar >= 0 && e.calendar < feeds.size() ? feeds.get(e.calendar)[0] : "";
            w.obj().put("title", e.title).put("start", e.start).put("end", e.end).put("allDay", e.allDay).put("cal", e.calendar)
                    .put("calName", cal).put("location", e.location).end();
        }
        w.endArr().key("feeds").arr();
        for (String[] f : feeds) w.obj().put("name", f[0]).put("url", mask(f[1])).end();
        w.endArr();
        String plans = h.plans.json(now, TimeZone.getDefault());
        w.key("countdowns").raw(plans.substring(plans.indexOf("\"countdowns\":") + "\"countdowns\":".length(), plans.length() - 1));
        w.end();
    }

    /** "https://calendar.google.com/…" with the private part hidden. */
    static String mask(String url) {
        int slash = url.indexOf('/', 8);
        return (slash > 0 ? url.substring(0, slash) : url) + "/…";
    }

    private static void media(Jw w, Model.Media m) {
        w.key("media").obj().put("active", m.active).put("app", m.app).put("title", m.title).put("artist", m.artist)
                .put("album", m.album).put("playing", m.playing).put("position", m.positionNow(SystemClock.elapsedRealtime()))
                .put("duration", m.duration).put("canNext", m.canNext).put("canPrev", m.canPrev).end();
    }

    private static void audio(Jw w, Model.Audio a) {
        w.key("audio").obj();
        if (a != null) {
            w.put("route", a.route).put("device", a.device).put("headline", a.headline).key("formats").arr();
            for (String f : a.formats) w.val(f);
            w.endArr();
        }
        w.end();
    }

    private static void picture(Jw w, Model.Picture p) {
        if (p == null || p.line.isEmpty()) return;
        w.key("display").obj().put("width", p.width).put("height", p.height).put("hz", p.hz).key("hdr").arr();
        for (String x : p.hdr) w.val(x);
        w.endArr().put("uiWidth", p.uiWidth).put("uiHeight", p.uiHeight).put("line", p.line).put("advice", p.advice).end();
    }

    private static void settings(Jw w, Hub h) {
        Prefs p = h.prefs;
        w.key("settings").obj();
        for (String[] c : Prefs.CHOICES) w.put(Prefs.api(c[0]), p.choice(c[0]));
        w.put("awakeFrom", p.get(Prefs.AWAKE_FROM, "07:00")).put("awakeTo", p.get(Prefs.AWAKE_TO, "00:30"))
                .put("bedtime", p.get(Prefs.BEDTIME, "off")).put("station", p.station()).put("station2", p.station2())
                .put("mbtaKey", p.get(Prefs.MBTA_KEY, "").isEmpty() ? "" : "set").put("wifiSsid", p.get(Prefs.WIFI_SSID, ""))
                .put("wifiSet", !p.get(Prefs.WIFI_PASS, "").isEmpty() || !p.get(Prefs.WIFI_SSID, "").isEmpty())
                .put("theme", p.get(Prefs.MODE, "auto")).put("alexa", p.get(Prefs.ALEXA, "off"));
        w.end();
    }
}
