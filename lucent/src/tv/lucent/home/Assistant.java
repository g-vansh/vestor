package tv.lucent.home;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The Android side of {@link Claude}: what the TV shows, told in a few lines, and the hands that
 * carry out Claude's tool calls (titles open in their own apps, Lucent's own actions, the Samsung
 * and the soundbar's volume). Questions wait on their own thread, two at most, so the screen and
 * the other feeds never wait on Claude. Automations are checked every 30 s from Hub's tick.
 */
final class Assistant implements Claude.Hands {
    final Claude claude;
    private final Hub hub;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor q = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "assistant");
                    t.setDaemon(true);
                    return t;
                }
            });
    /** The question on its way or the last answer, for the TV's card and the phone. */
    volatile Claude.Answer latest;

    Assistant(Hub hub) {
        this.hub = hub;
        final Prefs prefs = hub.prefs;
        claude = new Claude(new Claude.Store() {
            @Override
            public String get(String key) {
                return prefs.get(key, "");
            }

            @Override
            public void put(String key, String value) {
                prefs.putNow(key, value);
            }
        }, new Claude.Web() {
            @Override
            public Net.Reply call(String method, String url, String[] headers, String contentType, byte[] body) {
                // A search-backed answer can take most of a minute; the answer is a few KB, search results more.
                return Net.call(method, url, 60000, 4 * 1024 * 1024, headers, contentType, body);
            }
        }, this);
    }

    /**
     * Queues a question from {@code from} ("Sam's iPhone", "the TV"). The answer arrives to the
     * listeners as {@link Hub#ANSWER}, first as a card that says Lucent is thinking.
     */
    Claude.Answer ask(final String question, final String from) throws Claude.Failed {
        if (!claude.hasKey()) throw new Claude.Failed("Add a Claude API key first: on the phone, Settings, Claude.");
        final Claude.Answer pending = new Claude.Answer();
        pending.question = Text.clean(question, Claude.MAX_QUESTION);
        pending.from = from;
        pending.at = System.currentTimeMillis();
        pending.busy = true;
        if (pending.question.isEmpty()) throw new Claude.Failed("Ask a question first.");
        try {
            q.execute(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    Claude.Answer a;
                    try {
                        a = claude.ask(question, from, System.currentTimeMillis());
                    } catch (Throwable t) {
                        // Whatever went wrong stays on this thread: the home screen keeps running.
                        a = new Claude.Answer();
                        a.question = pending.question;
                        a.from = from;
                        a.at = pending.at;
                        a.error = "Something went wrong answering that. Try again.";
                    }
                    latest = a;
                    hub.post(Hub.ANSWER, a);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new Claude.Failed("Lucent is still answering the last questions. Try again in a moment.");
        }
        latest = pending;
        hub.post(Hub.ANSWER, pending);
        return pending;
    }

    /** Runs the automations that are due, off the main thread. Called every 30 s. */
    void tick(final long now) {
        if (claude.rules().isEmpty()) return;
        long[] sun = Sun.times(now, hub.prefs.lat, hub.prefs.lon, TimeZone.getDefault());
        int rise = minutes(sun[0], 6 * 60 + 30), set = minutes(sun[1], 18 * 60);
        final List<Claude.Rule> due = claude.due(now, rise, set);
        if (due.isEmpty()) return;
        hub.runInBackground(new Runnable() {
            @Override
            public void run() {
                for (Claude.Rule r : due) {
                    Object[] call = Claude.call(r);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> in = (Map<String, Object>) call[1];
                        Assistant.this.run((String) call[0], in);
                    } catch (Claude.Failed | RuntimeException ignored) {
                        // a step that cannot run now (the Samsung offline) waits for tomorrow
                    }
                }
            }
        });
    }

    private static int minutes(long at, int fallback) {
        if (at <= 0) return fallback;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    // ---- What the TV shows --------------------------------------------------------------------

    @Override
    public String context(long now) {
        StringBuilder b = new StringBuilder();
        String clock = hub.is24h() ? "H:mm" : "h:mm a";
        SimpleDateFormat when = new SimpleDateFormat("EEEE d MMMM yyyy, " + clock, Locale.US);
        Forecast.Result fc = hub.bikeForecast;
        b.append("Now: ").append(when.format(new Date(now)));
        if (fc != null && !fc.dayType.isEmpty()) b.append(" (").append(dayType(fc.dayType)).append(")");
        b.append(". Look: ").append(hub.night ? "night" : "day").append('\n');
        Model.Bikes bikes = hub.bikes;
        if (bikes != null && bikes.main() != null) {
            Model.Station v = bikes.main(), e = bikes.alt();
            b.append("E-bikes now: Westgate (MIT Vassar St) ").append(v.rentableEbikes());
            if (e != null) b.append(", Erie St (by Flour, Cambridgeport) ").append(e.rentableEbikes());
            b.append(". Classic bikes: ").append(v.rentableClassic()).append(e != null ? " and " + e.rentableClassic() : "").append('\n');
        }
        SimpleDateFormat hm = new SimpleDateFormat(clock, Locale.US);
        if (fc != null) {
            if (!fc.empty && fc.poolMed > 0) {
                b.append("E-bike forecast: both docks likely out by ").append(hm.format(new Date(fc.poolMed)));
                if (fc.poolLo > 0 && fc.poolHi > 0) {
                    b.append(" (80% between ").append(hm.format(new Date(fc.poolLo))).append(" and ").append(hm.format(new Date(fc.poolHi))).append(")");
                }
                if (fc.firstMed > 0) b.append(", Westgate alone by ").append(hm.format(new Date(fc.firstMed)));
                b.append('\n');
            } else if (fc.empty && fc.nextMed > 0) {
                b.append("E-bike forecast: next e-bike at either dock likely around ").append(hm.format(new Date(fc.nextMed))).append('\n');
            }
            if (fc.morning >= 0) {
                b.append("Morning outlook: chance of an e-bike ").append(Math.round(fc.morning * 100)).append("% at ")
                        .append(hm.format(new Date(fc.morningAt)));
                if (fc.morning1h >= 0) b.append(", ").append(Math.round(fc.morning1h * 100)).append("% an hour later");
                if (fc.morning2h >= 0) b.append(", ").append(Math.round(fc.morning2h * 100)).append("% two hours later");
                b.append('\n');
            }
        }
        Model.Finder f = Finder.get().snapshot();
        if (f.state == Model.Finder.SEARCHING) b.append("The bike finder is on, watching both docks.\n");
        Model.Weather w = hub.weather;
        if (w != null && w.ok) {
            b.append("Weather in Cambridge: ").append(w.condition).append(", ").append(Model.Weather.f(w.tempC)).append("°F (")
                    .append(Model.Weather.c(w.tempC)).append("°C), high ").append(Model.Weather.f(w.hiC)).append("°F, low ")
                    .append(Model.Weather.f(w.loC)).append("°F");
            if (!w.rain.isEmpty()) b.append(". ").append(w.rain);
            b.append('\n');
        }
        Model.Transit t = hub.transit;
        if (t != null && t.ok) {
            b.append("Shuttles and buses near Westgate:");
            for (Model.Line l : t.lines) {
                b.append(' ').append(l.label).append(": ");
                if (l.minutes.isEmpty()) b.append(l.idle.isEmpty() ? "none tracked" : l.idle);
                else b.append("in ").append(l.minutes.get(0)).append(" min");
                b.append(';');
            }
            b.setLength(b.length() - 1);
            b.append('\n');
        }
        List<Ical.Event> ev = hub.upcoming(2);
        int n = 0;
        for (Ical.Event e : ev) {
            if (e.end < now) continue;
            if (n == 0) b.append("Plans:");
            b.append(' ').append(e.title).append(" (").append(e.allDay ? "all day" : new SimpleDateFormat("EEE " + clock, Locale.US).format(new Date(e.start)))
                    .append(");");
            if (++n == 4) break;
        }
        if (n > 0) b.setLength(b.length() - 1);
        if (n > 0) b.append('\n');
        Model.Media m = hub.media.snapshot();
        if (m != null && m.active) {
            b.append(m.playing ? "Playing: " : "Paused: ").append(m.title.isEmpty() ? "something" : m.title)
                    .append(m.artist.isEmpty() ? "" : " by " + m.artist).append(m.app.isEmpty() ? "" : " in " + m.app).append('\n');
        }
        try {
            StringBuilder apps = new StringBuilder();
            int k = 0;
            for (Catalog.Entry a : Catalog.all(hub.app)) {
                if (a.pkg.equals(hub.app.getPackageName())) continue;
                if (k++ > 0) apps.append(", ");
                apps.append(a.label);
                if (k == 40) break;
            }
            if (k > 0) b.append("Apps on the TV: ").append(apps).append('\n');
        } catch (RuntimeException ignored) {
            // the list is a hint
        }
        if (hub.samsung.connected()) {
            b.append("Samsung picture modes: ").append(join(hub.samsung.modes())).append(" (now ").append(hub.samsung.mode()).append(")");
            List<String> sounds = hub.samsung.sounds();
            if (!sounds.isEmpty()) b.append(". Sound modes: ").append(join(sounds));
            StringBuilder ins = new StringBuilder();
            for (String[] in : hub.samsung.inputs()) ins.append(ins.length() > 0 ? ", " : "").append(in[1]);
            if (ins.length() > 0) b.append(". Inputs: ").append(ins);
            b.append('\n');
        } else {
            b.append("The Samsung is not connected to Lucent's SmartThings link, so its modes can't be changed; volume still works.\n");
        }
        List<Claude.Rule> rules = claude.rules();
        if (!rules.isEmpty()) {
            b.append("Automations:");
            for (Claude.Rule r : rules) b.append(' ').append(r.name).append(" (").append(Claude.describe(r)).append(r.on ? "" : ", paused").append(");");
            b.setLength(b.length() - 1);
            b.append('\n');
        }
        return b.toString().trim();
    }

    private static String dayType(String t) {
        switch (t) {
            case "class":
                return "an MIT class day";
            case "finals":
                return "MIT finals";
            case "offweek":
                return "a weekday with no MIT classes";
            default:
                return t.equals("sat") ? "Saturday" : "Sunday";
        }
    }

    private static String join(List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String s : list) b.append(b.length() > 0 ? ", " : "").append(s);
        return b.toString();
    }

    // ---- Hands ---------------------------------------------------------------------------------

    @Override
    public String run(String tool, Map<String, Object> in) throws Claude.Failed {
        switch (tool) {
            case "play":
                return play(Json.str(in, "url", ""));
            case "open_app":
                return openApp(Json.str(in, "name", ""));
            case "lucent":
                return lucent(Json.str(in, "action", ""));
            case "media":
                return media(Json.str(in, "action", ""));
            case "tv":
                return tv(Json.str(in, "action", ""), Json.str(in, "value", "").trim());
            case "note":
                return note(Json.str(in, "text", ""));
            default:
                throw new Claude.Failed("No such tool.");
        }
    }

    private String play(String url) throws Claude.Failed {
        String[] svc = Claude.Play.app(url);
        String link = Claude.Play.link(url);
        if (svc == null || link == null) throw new Claude.Failed("That address isn't a title Lucent can open.");
        PackageManager pm = hub.app.getPackageManager();
        String pkg = null;
        for (String p : svc[1].split(",")) {
            if (pm.getLaunchIntentForPackage(p) != null || pm.getLeanbackLaunchIntentForPackage(p) != null) {
                pkg = p;
                break;
            }
        }
        if (pkg == null) throw new Claude.Failed(svc[0] + " isn't installed on the TV.");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if ("Netflix".equals(svc[0])) i.putExtra("source", "30");
        if (pm.resolveActivity(i, 0) == null) {
            // The app takes no links of this kind: open it, and say so.
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            start(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "Opened " + svc[0] + ", but it couldn't open the title directly";
        }
        start(i);
        return "Opened it in " + svc[0];
    }

    private String openApp(String name) throws Claude.Failed {
        String want = name.trim().toLowerCase(Locale.US);
        Catalog.Entry best = null;
        for (Catalog.Entry a : Catalog.all(hub.app)) {
            String label = a.label.toString().toLowerCase(Locale.US);
            if (label.equals(want)) {
                best = a;
                break;
            }
            if (best == null && (label.contains(want) || want.contains(label))) best = a;
        }
        if (best == null || best.pkg.equals(hub.app.getPackageName())) throw new Claude.Failed("There's no app called " + name + " on the TV.");
        start(new Intent(best.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "Opened " + best.label;
    }

    /** Starts an activity from the main thread. Lucent's HOME service lets it start one from behind another app. */
    private void start(final Intent i) throws Claude.Failed {
        onMain(new Callable<Void>() {
            @Override
            public Void call() {
                hub.app.startActivity(i);
                return null;
            }
        });
    }

    private String lucent(String action) throws Claude.Failed {
        switch (action) {
            case "find_ebike":
                hub.command("find-ebike");
                return "Started the bike finder";
            case "stop_ebike":
                hub.command("stop-ebike");
                return "Stopped the bike finder";
            case "glare_on":
            case "glare_off":
                if (!hub.samsung.connected()) throw new Claude.Failed("Glare needs the Samsung connected: on the phone, Settings, Samsung Picture.");
                hub.command(action.equals("glare_on") ? "glare-on" : "glare-off");
                return action.equals("glare_on") ? "Switched the Samsung to its bright picture" : "Put the usual picture back";
            case "night_look":
                hub.command("night");
                return "Switched to the night look";
            case "day_look":
                hub.command("day");
                return "Switched to the day look";
            case "auto_look":
                hub.command("auto");
                return "The look follows the sun again";
            case "show_guest_wifi":
                if (hub.prefs.get(Prefs.WIFI_SSID, "").isEmpty()) throw new Claude.Failed("No guest Wi-Fi is set up on the phone page.");
                hub.command("wifi");
                return "Showing the guest Wi-Fi code";
            case "hide_guest_wifi":
                hub.command("wifi-hide");
                return "Hid the guest Wi-Fi code";
            case "send_hearts":
                hub.command("hearts");
                return "Sent hearts";
            case "lucent_home":
                hub.command("lucent");
                return "Brought Lucent to the front";
            case "fire_tv_home":
                hub.command("firetv");
                return "Went to Fire TV's home";
            default:
                throw new Claude.Failed("Not something Lucent can do.");
        }
    }

    private String media(String action) throws Claude.Failed {
        final Media media = hub.media;
        Model.Media m = media.snapshot();
        if (m == null || !m.active) throw new Claude.Failed("Nothing is playing on the TV.");
        switch (action) {
            case "play":
                if (!m.playing) media.toggle();
                return "Playing";
            case "pause":
                if (m.playing) media.toggle();
                return "Paused";
            case "next":
                media.next();
                return "Skipped ahead";
            default:
                media.previous();
                return "Went back";
        }
    }

    private String tv(String action, String value) throws Claude.Failed {
        try {
            switch (action) {
                case "volume_up":
                case "volume_down":
                case "mute":
                    hub.systemVolume(action.equals("volume_up") ? "up" : action.equals("volume_down") ? "down" : "mute");
                    return action.equals("mute") ? "Muted (or unmuted) the sound" : action.equals("volume_up") ? "Turned it up" : "Turned it down";
                case "power_off":
                    if (hub.samsung.connected()) {
                        hub.samsung.power(false);
                    } else if (hub.remote.paired()) {
                        hub.remote.key("KEY_POWER");
                    } else {
                        throw new Claude.Failed("Turning the TV off needs the Samsung connected on the phone page.");
                    }
                    return "Turned the TV off";
                case "picture_mode":
                    need();
                    hub.samsung.setMode(match(value, hub.samsung.modes(), "picture mode"));
                    return "Picture mode is now " + hub.samsung.mode();
                case "sound_mode":
                    need();
                    hub.samsung.setSound(match(value, hub.samsung.sounds(), "sound mode"));
                    return "Sound mode is now " + value;
                case "input": {
                    need();
                    for (String[] in : hub.samsung.inputs()) {
                        if (in[1].equalsIgnoreCase(value) || in[0].equalsIgnoreCase(value)) {
                            hub.samsung.setInput(in[0]);
                            return "Switched the TV to " + in[1];
                        }
                    }
                    throw new Claude.Failed("The Samsung has no input called " + value + ".");
                }
                default:
                    throw new Claude.Failed("Not something Lucent can do.");
            }
        } catch (Samsung.Failed e) {
            throw new Claude.Failed(e.getMessage());
        }
    }

    private void need() throws Claude.Failed {
        if (!hub.samsung.connected()) throw new Claude.Failed("That needs the Samsung connected: on the phone, Settings, Samsung Picture.");
    }

    private static String match(String value, List<String> known, String what) throws Claude.Failed {
        for (String k : known) if (k.equalsIgnoreCase(value)) return k;
        throw new Claude.Failed("The Samsung has no " + what + " called " + value + ". It has: " + join(known) + ".");
    }

    private String note(final String text) throws Claude.Failed {
        onMain(new Callable<Void>() {
            @Override
            public Void call() {
                Notes.Note n = new Notes.Note();
                n.text = Text.clean(text, Notes.MAX_TEXT);
                n.from = "Lucent";
                n.at = System.currentTimeMillis();
                n.paper = 1;
                n.pinned = true;
                hub.addNote(n);
                return null;
            }
        });
        return "Put a note on the home screen";
    }

    private <T> T onMain(Callable<T> c) throws Claude.Failed {
        FutureTask<T> task = new FutureTask<>(c);
        main.post(task);
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Claude.Failed("The TV couldn't do that just now.");
        }
    }
}
