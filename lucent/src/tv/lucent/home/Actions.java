package tv.lucent.home;

import java.util.List;
import java.util.Map;

/**
 * What each control-center POST does, on the main thread. A closed list: an action not named
 * here is refused, and nothing here reaches the accessibility service's powers, the pairing
 * window, or who may connect. Those only change from the TV itself.
 */
final class Actions {
    private Actions() {}

    private static final String OK = "{\"ok\":true}";

    static String run(Hub h, Keys.Device d, String action, Map<String, Object> body) throws Http.Refused {
        switch (action) {
            case "finder": {
                String what = Json.str(body, "do", "");
                if ("start".equals(what)) {
                    if (!h.findBike()) return "{\"ok\":true,\"note\":\"Waking Bluebikes, starting in a moment\"}";
                } else if ("stop".equals(what)) {
                    h.stopFinding();
                } else if ("silence".equals(what)) {
                    Alarm.silence();
                } else {
                    throw new Http.Refused(400, "finder");
                }
                return OK;
            }
            case "note": {
                Notes.Note n;
                try {
                    n = Notes.parse(body, d.name, System.currentTimeMillis());
                } catch (IllegalArgumentException e) {
                    throw new Http.Refused(400, "note");
                }
                Notes.Note added = h.addNote(n);
                return new Jw().obj().put("ok", true).put("id", added.id).end().toString();
            }
            case "notedel":
                if (!h.notes.remove(Json.str(body, "id", ""))) throw new Http.Refused(404, "note");
                h.post(Hub.NOTE, null);
                return OK;
            case "notepin":
                if (!h.notes.pin(Json.str(body, "id", ""), Json.bool(body, "pin", false))) throw new Http.Refused(404, "note");
                h.post(Hub.NOTE, null);
                return OK;
            case "heart": {
                String kind = Json.str(body, "kind", "hearts");
                if (!"hearts".equals(kind) && !"sparkles".equals(kind) && !"wave".equals(kind)) throw new Http.Refused(400, "kind");
                if (!h.hearts(kind, d.name)) throw new Http.Refused(429, "one moment");
                return OK;
            }
            case "photodel":
                if (!h.album.remove(Json.str(body, "id", ""))) throw new Http.Refused(404, "photo");
                h.post(Hub.PHOTOS, null);
                return OK;
            case "photocap":
                if (!h.album.caption(Json.str(body, "id", ""), Json.str(body, "caption", ""))) throw new Http.Refused(404, "photo");
                h.post(Hub.PHOTOS, null);
                return OK;
            case "photoshow": {
                String id = Json.str(body, "id", "");
                if (h.album.find(id) == null) throw new Http.Refused(404, "photo");
                h.post(Hub.WALL, id);
                return OK;
            }
            case "mode": {
                String m = Json.str(body, "mode", "");
                if (!"auto".equals(m) && !"day".equals(m) && !"night".equals(m)) throw new Http.Refused(400, "mode");
                h.setMode(m);
                return OK;
            }
            case "set":
                set(h, Json.str(body, "key", ""), body.get("value"));
                return OK;
            case "media": {
                String what = Json.str(body, "do", "");
                if ("toggle".equals(what)) h.media.toggle();
                else if ("next".equals(what)) h.media.next();
                else if ("prev".equals(what)) h.media.previous();
                else throw new Http.Refused(400, "media");
                return OK;
            }
            case "tv": {
                String what = Json.str(body, "do", "");
                if ("lucent".equals(what)) Guard.wake(h.app, true);
                else if ("firetv".equals(what)) Guard.amazon(h.app);
                else if ("sleep".equals(what)) {
                    if (!Guard.sleep()) throw new Http.Refused(409, "sleep needs the HOME service");
                } else throw new Http.Refused(400, "tv");
                return OK;
            }
            case "list": {
                String op = Json.str(body, "op", "");
                try {
                    if ("add".equals(op)) h.plans.add(Json.str(body, "text", ""), d.name, System.currentTimeMillis());
                    else if ("toggle".equals(op)) h.plans.toggle(Json.str(body, "id", ""));
                    else if ("remove".equals(op)) h.plans.remove(Json.str(body, "id", ""));
                    else if ("clear".equals(op)) h.plans.clearDone();
                    else throw new Http.Refused(400, "list");
                } catch (IllegalArgumentException e) {
                    throw new Http.Refused(400, "list");
                }
                h.post(Hub.PLANS, null);
                return OK;
            }
            case "wifi": {
                String what = Json.str(body, "do", "");
                if ("show".equals(what)) {
                    if (h.prefs.get(Prefs.WIFI_SSID, "").isEmpty()) throw new Http.Refused(409, "no wifi set");
                    h.wifiUntil = System.currentTimeMillis() + 120000L;
                } else {
                    h.wifiUntil = 0;
                }
                h.post(Hub.WIFI, null);
                return OK;
            }
            case "revoke": {
                String prefix = Json.str(body, "id", "");
                if (prefix.length() != 4) throw new Http.Refused(400, "id");
                for (Keys.Device x : h.keys.list()) {
                    if (x.selector.startsWith(prefix)) {
                        h.keys.revoke(x.selector, System.currentTimeMillis());
                        return OK;
                    }
                }
                throw new Http.Refused(404, "device");
            }
            case "refresh":
                h.live.wake(120000);
                h.live.refreshBikes();
                return OK;
            case "samsung":
                return samsung(h, Json.str(body, "do", ""), body);
            case "remote":
                return remote(h, Json.str(body, "do", ""), body);
            case "volume": {
                // The Fire TV's own volume: HDMI-CEC carries it to the TV or the soundbar.
                String what = Json.str(body, "do", "");
                if (!"up".equals(what) && !"down".equals(what) && !"mute".equals(what)) throw new Http.Refused(400, "volume");
                h.systemVolume(what);
                return OK;
            }
            case "ask": {
                // Queued: the answer comes in the state a few seconds later, and on the TV.
                try {
                    Jw w = new Jw();
                    Claude.answerJson(w, h.assistant.ask(Json.str(body, "text", ""), d.name));
                    return w.toString();
                } catch (Claude.Failed f) {
                    throw new Http.Refused(409, f.getMessage());
                }
            }
            case "assistant": {
                Claude c = h.assistant.claude;
                try {
                    switch (Json.str(body, "do", "")) {
                        case "key":
                            c.setKey(Json.str(body, "key", "").trim());
                            break;
                        case "forget":
                            c.forgetKey();
                            break;
                        case "cap":
                            c.setCap((int) Json.num(body, "dollars", -1));
                            break;
                        case "mode":
                            c.setMode(Json.str(body, "mode", ""));
                            break;
                        case "clear":
                            c.clearHistory();
                            break;
                        case "ruledel":
                            c.deleteRule(Json.str(body, "id", ""));
                            break;
                        case "ruleon":
                            c.setRuleOn(Json.str(body, "id", ""), Json.bool(body, "on", true));
                            break;
                        default:
                            throw new Http.Refused(400, "assistant");
                    }
                } catch (Claude.Failed f) {
                    throw new Http.Refused(400, f.getMessage());
                }
                return OK;
            }
            case "push": {
                if (!"test".equals(Json.str(body, "do", ""))) throw new Http.Refused(400, "push");
                if (!h.pushOn()) throw new Http.Refused(409, "phone alerts are off");
                if (!h.push("Lucent", "Phone alerts work. Bike finder will buzz here when an e-bike docks.", 3, "tv", "")) {
                    throw new Http.Refused(503, "ntfy did not answer");
                }
                return OK;
            }
            default:
                throw new Http.Refused(404, "no such action");
        }
    }

    /** One setting, checked against its allowed values. Anything else is refused. */
    private static void set(Hub h, String apiKey, Object value) throws Http.Refused {
        Prefs p = h.prefs;
        String key = Prefs.key(apiKey);
        String v = value instanceof String ? ((String) value).trim() : null;
        if ("theme".equals(apiKey)) {
            if (v == null || !("auto".equals(v) || "day".equals(v) || "night".equals(v))) throw new Http.Refused(400, "theme");
            h.setMode(v);
            return;
        }
        if (Prefs.allowed(key, v)) {
            p.put(key, v);
        } else if ("awakeFrom".equals(apiKey) || "awakeTo".equals(apiKey)) {
            if (!Prefs.clockOr(v, "always")) throw new Http.Refused(400, "time");
            p.put("awakeFrom".equals(apiKey) ? Prefs.AWAKE_FROM : Prefs.AWAKE_TO, v);
        } else if ("bedtime".equals(apiKey)) {
            if (!Prefs.clockOr(v, "off")) throw new Http.Refused(400, "time");
            p.put(Prefs.BEDTIME, v);
        } else if ("station".equals(apiKey)) {
            if (v == null || !v.matches("[A-Za-z0-9_-]{1,64}")) throw new Http.Refused(400, "station");
            p.put(Prefs.STATION, v);
            h.live.setStation(p.station());
            h.live.refreshBikes();
        } else if ("station2".equals(apiKey)) {
            if (v == null || !v.matches("[A-Za-z0-9_-]{1,64}")) throw new Http.Refused(400, "station");
            p.put(Prefs.STATION2, v);
            h.live.setStation2(p.station2());
            h.live.refreshBikes();
        } else if ("mbtaKey".equals(apiKey)) {
            if (v == null || !(v.isEmpty() || v.matches("[0-9a-fA-F]{32}"))) throw new Http.Refused(400, "key");
            p.put(Prefs.MBTA_KEY, v.toLowerCase(java.util.Locale.US));
        } else if ("calendars".equals(apiKey)) {
            calendars(h, value);
        } else if ("countdowns".equals(apiKey)) {
            if (!(value instanceof List)) throw new Http.Refused(400, "countdowns");
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            try {
                h.plans.setCountdowns(list);
            } catch (IllegalArgumentException e) {
                throw new Http.Refused(400, "countdowns");
            }
            h.post(Hub.PLANS, null);
            return;
        } else if ("wifiSsid".equals(apiKey) || "wifiPass".equals(apiKey)) {
            // Printable text only: these go into a QR code, never into anything that runs.
            if (v == null || v.length() > 63 || !v.matches("[\\x20-\\x7E]*")) throw new Http.Refused(400, "wifi");
            p.put("wifiSsid".equals(apiKey) ? Prefs.WIFI_SSID : Prefs.WIFI_PASS, v);
        } else if ("dayMode".equals(apiKey) || "nightMode".equals(apiKey) || "glareMode".equals(apiKey)) {
            if (v == null || !h.samsung.modes().contains(v)) throw new Http.Refused(400, "picture mode");
            p.put(key, v);
            h.picture(h.night, true);
            h.post(Hub.SETTINGS, key);
            return;
        } else if ("push".equals(apiKey)) {
            if (!"on".equals(v) && !"off".equals(v) && !"new".equals(v)) throw new Http.Refused(400, "push");
            h.setPush(v);
            h.post(Hub.SETTINGS, Prefs.PUSH);
            return;
        } else if ("alexa".equals(apiKey)) {
            if (!"on".equals(v) && !"off".equals(v) && !"new".equals(v)) throw new Http.Refused(400, "alexa");
            if ("new".equals(v)) {
                p.remove(Prefs.ALEXA_TOPIC);
                v = "on";
            }
            p.put(Prefs.ALEXA, v);
            h.startAlexa();
        } else {
            throw new Http.Refused(400, "setting");
        }
        h.applySettings();
        if (Prefs.UNITS.equals(key) || Prefs.CLOCK.equals(key)) h.reweather();
        if (Prefs.PICTURE_FOLLOW.equals(key)) h.picture(h.night, true);
        h.post(Hub.SETTINGS, key);
    }

    /**
     * The Samsung's picture through SmartThings. Runs on Lucent's worker thread, since each step
     * waits for Samsung's servers. Nothing secret comes back: setup answers only the sign-in
     * address, and the page learns no more than "connected" and the TV's name.
     */
    private static String samsung(Hub h, String what, Map<String, Object> body) throws Http.Refused {
        Samsung s = h.samsung;
        try {
            if ("begin".equals(what)) {
                String url = s.begin(Json.str(body, "clientId", ""), Json.str(body, "clientSecret", ""));
                h.post(Hub.SETTINGS, "samsung");
                return new Jw().obj().put("ok", true).put("authorize", url).end().toString();
            }
            if ("finish".equals(what)) {
                String device = s.finish(Json.str(body, "code", ""));
                h.post(Hub.SETTINGS, "samsung");
                return new Jw().obj().put("ok", true).put("device", device).end().toString();
            }
            if ("disconnect".equals(what)) {
                s.disconnect();
                h.prefs.put(Prefs.GLARE, "off");
                h.post(Hub.SETTINGS, "samsung");
                return OK;
            }
            if (!s.connected()) throw new Http.Refused(409, "connect the Samsung first");
            if ("glare".equals(what)) {
                h.glareNow(Json.bool(body, "on", false));
                h.post(Hub.SETTINGS, Prefs.GLARE);
            } else if ("mode".equals(what)) {
                s.setMode(Json.str(body, "mode", ""));
                h.post(Hub.SETTINGS, "picture");
            } else if ("read".equals(what)) {
                s.readAll();
            } else if ("power".equals(what)) {
                boolean on = Json.bool(body, "on", false);
                s.power(on);
                // The stick waking also wakes the TV through HDMI-CEC, if SmartThings cannot.
                if (on) Guard.wake(h.app, false);
            } else if ("volume".equals(what)) {
                s.setVolume((int) Json.num(body, "level", -1));
            } else if ("volumeStep".equals(what)) {
                s.volumeStep(Json.bool(body, "up", true));
            } else if ("mute".equals(what)) {
                s.mute(Json.bool(body, "on", true));
            } else if ("input".equals(what)) {
                s.setInput(Json.str(body, "id", ""));
            } else if ("sound".equals(what)) {
                s.setSound(Json.str(body, "mode", ""));
            } else if ("launch".equals(what)) {
                s.launch(Json.str(body, "app", ""));
            } else if ("cloudKey".equals(what)) {
                s.key(Json.str(body, "key", ""));
            } else {
                throw new Http.Refused(400, "samsung");
            }
            return OK;
        } catch (Samsung.Failed f) {
            throw new Http.Refused(f.status, f.getMessage());
        }
    }

    /**
     * The Samsung's own remote over the home network (see {@link SamsungRemote}), on its own
     * thread. Keys come from a fixed list, and only a home address is ever dialled.
     */
    private static String remote(Hub h, String what, Map<String, Object> body) throws Http.Refused {
        SamsungRemote r = h.remote;
        try {
            if ("address".equals(what)) {
                r.setAddress(Json.str(body, "ip", ""));
                SamsungRemote.Info info = r.probe();
                h.post(Hub.SETTINGS, "remote");
                return new Jw().obj().put("ok", true).put("label", info.name).put("model", info.modelName.isEmpty() ? info.model : info.modelName)
                        .put("power", info.power).end().toString();
            }
            if ("pair".equals(what)) {
                r.connect();
            } else if ("key".equals(what)) {
                r.key(Json.str(body, "key", ""));
            } else if ("hold".equals(what)) {
                r.hold(Json.str(body, "key", ""), (int) Json.num(body, "ms", 1000));
            } else if ("launch".equals(what)) {
                r.launch(Json.str(body, "app", ""), Json.bool(body, "deepLink", true));
            } else if ("wake".equals(what)) {
                r.wake();
            } else if ("close".equals(what)) {
                r.close();
            } else if ("trust".equals(what)) {
                r.trustAgain();
            } else if ("forget".equals(what)) {
                r.forget();
            } else {
                throw new Http.Refused(400, "remote");
            }
            h.post(Hub.SETTINGS, "remote");
            return OK;
        } catch (Samsung.Failed f) {
            h.post(Hub.SETTINGS, "remote");
            throw new Http.Refused(f.status, f.getMessage());
        }
    }

    private static void calendars(Hub h, Object value) throws Http.Refused {
        if (!(value instanceof List)) throw new Http.Refused(400, "calendars");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        if (list.size() > 6) throw new Http.Refused(400, "calendars");
        List<String[]> old = h.calendars();
        Jw w = new Jw().obj().key("feeds").arr();
        for (Object o : list) {
            Map<String, Object> m = Json.obj(o);
            String name = Text.clean(Json.str(m, "name", ""));
            if (name.length() > 24) name = name.substring(0, 24);
            String raw = Json.str(m, "url", "");
            String url;
            // The page only ever saw a masked address: one sent back unchanged keeps the saved one.
            if (raw.endsWith("/…")) {
                url = null;
                for (String[] f : old) if (State.mask(f[1]).equals(raw)) url = f[1];
            } else {
                url = Hub.calendarUrl(raw);
            }
            if (url == null) throw new Http.Refused(400, "calendar address");
            w.obj().put("name", name.isEmpty() ? "Calendar" : name).put("url", url).end();
        }
        h.prefs.put(Prefs.CALENDARS, w.endArr().end().toString());
        h.live.refreshSlow();
    }
}
