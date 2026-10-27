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
        settings(w, h);
        w.key("stations").arr().obj().put("id", "auto").put("name", "Westgate").end();
        Model.Bikes b = h.bikes;
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
        boolean alexa = "on".equals(h.prefs.get(Prefs.ALEXA, "off"));
        String topic = h.prefs.get(Prefs.ALEXA_TOPIC, "");
        w.key("alexa").obj().put("on", alexa).put("url", alexa && Ntfy.validTopic(topic) ? Ntfy.publishUrl(topic, "find-ebike") : "")
                .put("stopUrl", alexa && Ntfy.validTopic(topic) ? Ntfy.publishUrl(topic, "stop-ebike") : "").end();
        return w.end().toString();
    }

    private static void weather(Jw w, Model.Weather x) {
        if (x == null || !x.ok) return;
        w.key("weather").obj().put("ok", true).put("city", x.city).put("tempF", Model.Weather.f(x.tempC)).put("tempC", x.tempC)
                .put("feelsF", Model.Weather.f(x.feelsC)).put("hiF", Model.Weather.f(x.hiC)).put("loF", Model.Weather.f(x.loC))
                .put("condition", x.condition).put("code", x.code).put("day", x.day).put("humidity", x.humidity)
                .put("windMph", x.windKph < 0 ? -1 : Math.round(x.windKph / 1.609344)).put("windDir", Weather.compass(x.windDir))
                .put("updated", x.updated).key("hours").arr();
        for (int i = 0; i < x.hourC.size(); i++) {
            w.obj().put("label", x.hourLabel.get(i)).put("f", Model.Weather.f(x.hourC.get(i))).put("c", Model.Weather.c(x.hourC.get(i)))
                    .put("code", x.hourCode.get(i)).put("day", x.hourDay.get(i)).put("rain", i < x.hourRain.size() ? x.hourRain.get(i) : -1)
                    .end();
        }
        w.endArr().end();
    }

    private static void bikes(Jw w, Model.Bikes b) {
        if (b == null) return;
        w.key("bikes").obj().put("ok", b.ok).put("label", b.label).put("fetched", b.fetched).put("published", b.published)
                .key("stations").arr();
        for (Model.Station s : b.stations) {
            w.obj().put("id", s.id).put("name", s.name).put("short", Gbfs.shortName(s, false)).put("ebikes", s.rentableEbikes())
                    .put("classic", s.rentableClassic()).put("docks", s.docks).put("capacity", s.capacity)
                    .put("renting", s.installed && s.renting).put("miles", s.metres / 1609.344).end();
        }
        w.endArr().end();
    }

    private static void finder(Jw w) {
        Model.Finder f = Finder.get().snapshot();
        String[] names = {"idle", "searching", "found", "timedout", "stopped"};
        w.key("finder").obj().put("state", names[Math.max(0, Math.min(4, f.state))]).put("started", f.started)
                .put("deadline", f.deadline).put("ended", f.ended).put("ebikes", f.ebikes).put("checks", f.checks)
                .put("station", f.station).put("alarm", Alarm.ringing()).put("offline", f.offline).end();
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

    private static void settings(Jw w, Hub h) {
        Prefs p = h.prefs;
        w.key("settings").obj();
        for (String[] c : Prefs.CHOICES) w.put(Prefs.api(c[0]), p.choice(c[0]));
        w.put("awakeFrom", p.get(Prefs.AWAKE_FROM, "07:00")).put("awakeTo", p.get(Prefs.AWAKE_TO, "00:30"))
                .put("bedtime", p.get(Prefs.BEDTIME, "off")).put("station", p.station())
                .put("mbtaKey", p.get(Prefs.MBTA_KEY, "").isEmpty() ? "" : "set").put("wifiSsid", p.get(Prefs.WIFI_SSID, ""))
                .put("wifiSet", !p.get(Prefs.WIFI_PASS, "").isEmpty() || !p.get(Prefs.WIFI_SSID, "").isEmpty())
                .put("theme", p.get(Prefs.MODE, "auto")).put("alexa", p.get(Prefs.ALEXA, "off"));
        w.end();
    }
}
