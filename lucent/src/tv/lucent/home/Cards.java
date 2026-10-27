package tv.lucent.home;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the live glass shows, at three levels of detail (visionOS's pattern for widgets seen from
 * a distance): a compact segment in the glance bar that reads from the couch, the card it grows
 * into when focused, and the full panel OK opens. The scene owns the glass and the motion; this
 * draws the content into the rectangles it is given.
 *
 * Numbers that change are right-aligned and set in their own run with the unit smaller beside
 * them, so countdowns do not jitter sideways. Colour is spent only on meaning.
 */
final class Cards {
    static final int WEATHER = 0, BIKES = 1, TRANSIT = 2, SKY = 3, PLANS = 4, MUSIC = 5, COUNT = 6;

    /** Found e-bikes are announced on the bikes card for this long. */
    static final long FOUND_SHOWN_MS = 15 * 60000L;

    Model.Weather weather;
    /** Air quality and UV (Open-Meteo), and the most serious weather alert (NWS), or null. */
    Model.Air air;
    Model.Alert alert;
    Model.Bikes bikes;
    Model.Transit transit;
    Model.Sky sky;
    Model.Finder finder = new Model.Finder();
    /** The shared calendars' next two weeks, sorted, and the feed names by index. */
    List<Ical.Event> events = new ArrayList<>();
    List<String> feeds = new ArrayList<>();
    List<Plans.Countdown> countdowns = new ArrayList<>();
    List<Plans.Item> items = new ArrayList<>();
    Model.Media media = new Model.Media();
    /** The device's elapsed-realtime clock, for the music position. */
    long uptime;
    java.util.TimeZone zone = java.util.TimeZone.getDefault();
    boolean h24, still;
    long now;
    float time;
    private float d = 2;

    void density(float density) {
        d = density;
    }

    private float dp(float v) {
        return v * d;
    }

    static String name(int kind) {
        switch (kind) {
            case WEATHER: return "Weather";
            case BIKES: return "Bluebikes";
            case TRANSIT: return "Shuttles";
            case PLANS: return "Plans";
            case MUSIC: return "Now Playing";
            default: return "Planes Overhead";
        }
    }

    boolean finding() {
        return finder != null && finder.active();
    }

    boolean foundRecently() {
        return finder != null && finder.state == Model.Finder.FOUND && now - finder.ended < FOUND_SHOWN_MS;
    }

    boolean transitStale() {
        return transit != null && now - transit.fetched > 90000L;
    }

    boolean bikesStale() {
        return bikes != null && now - bikes.fetched > 5 * 60000L;
    }

    String stationLabel() {
        return bikes == null ? "Westgate" : bikes.label;
    }

    // ---- Shared bits ---------------------------------------------------------------------------

    /** "12 s ago", "4 min ago": how old a reading is. */
    static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s ago";
        long m = s / 60;
        if (m < 60) return m + " min ago";
        return (m / 60) + " h ago";
    }

    private String clock(long at) {
        return new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(new Date(at));
    }

    /** The forecast's words, made when it arrives: for the bar, the card, and the panel's two lines. */
    String fcShort = "", fcCard = "", fcLine = "", fcLine2 = "", fcTag = "";
    boolean fcEmpty;

    private String altShortFor, altShortText = "";

    /** "Erie" for "Erie St", worked out once per label rather than every frame. */
    private String altShort() {
        String l = bikes.altLabel;
        if (l != altShortFor) {
            altShortFor = l;
            altShortText = l.endsWith(" St") ? l.substring(0, l.length() - 3) : l;
        }
        return altShortText;
    }

    /** "8:10", without AM or PM, for the bar and the card's short lines. */
    private String hm(long at) {
        return new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.US).format(new Date(at));
    }

    void setForecast(Forecast.Result r, long at) {
        if (r == null) {
            fcShort = fcCard = fcLine = fcLine2 = fcTag = "";
            return;
        }
        fcEmpty = r.empty;
        boolean two = bikes != null && bikes.alt() != null;
        if (!r.empty) {
            if (r.poolMed > 0) {
                fcShort = "out " + hm(r.poolMed);
                fcCard = "All gone by about " + clock(r.poolMed);
                fcLine = "Likely all gone by " + clock(r.poolMed)
                        + (r.poolLo > 0 && r.poolHi > 0 ? " (" + hm(r.poolLo) + " to " + hm(r.poolHi) + ")" : ", maybe later");
            } else {
                fcShort = "";
                fcCard = "Should last the next 8 hours";
                fcLine = "E-bikes should last for the next 8 hours";
            }
            fcLine2 = bikes != null && bikes.alt() != null && r.firstMed > 0 && bikes.main() != null && bikes.main().rentableEbikes() > 0
                    ? stationLabel() + " likely empty by " + clock(r.firstMed) : "";
        } else {
            if (r.nextMed > 0) {
                fcShort = "next " + hm(r.nextMed);
                fcCard = "Next e-bike around " + clock(r.nextMed);
                fcLine = "Next e-bike likely around " + clock(r.nextMed)
                        + (r.nextLo > 0 && r.nextHi > 0 ? " (" + hm(r.nextLo) + " to " + hm(r.nextHi) + ")" : "");
            } else {
                fcShort = "";
                fcCard = "No e-bike expected for hours";
                fcLine = "No e-bike expected in the next 8 hours";
            }
            fcLine2 = "";
        }
        if (r.morning >= 0) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(at);
            int today = c.get(java.util.Calendar.DAY_OF_YEAR);
            c.setTimeInMillis(r.morningAt);
            boolean tomorrow = c.get(java.util.Calendar.DAY_OF_YEAR) != today;
            String chance = Math.round(r.morning * 100) + "% chance of an e-bike";
            fcLine2 = (tomorrow ? "Tomorrow at " : "At ") + clock(r.morningAt) + ", " + chance;
        }
        String[] tags = {"MIT class day", "MIT finals", "No MIT classes", "Saturday", "Sunday"};
        int k = java.util.Arrays.asList(Forecast.DAY_TYPES).indexOf(r.dayType);
        fcTag = (two ? "Both docks  ·  " : "") + (k >= 0 ? tags[k] : "");
    }

    private long elapsedFor = -1;
    private String elapsedText = "";

    /** "4:07", made once a second rather than once a frame. */
    private String elapsed(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s != elapsedFor) {
            elapsedFor = s;
            elapsedText = s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
        }
        return elapsedText;
    }

    private static String thousands(int v) {
        return String.format(Locale.US, "%,d", v);
    }

    private float cap(Gfx g, int face, float size) {
        return g.capHeight(face, size);
    }

    /** Small caps label, the way every card names its source. */
    private void meta(Gfx g, String s, float x, float base, float a) {
        g.text(s.toUpperCase(Locale.US), Gfx.MONO, dp(11.5f), 0.08f, x, base, Tone.text(Tone.TERTIARY + 0.14f, a), Gfx.LEFT, 0);
    }

    /** Freshness at the right of a header: a live mark while fresh, amber once stale. */
    private void freshness(Gfx g, long fetched, long staleAfter, float r, float base, float a) {
        if (fetched <= 0) return;
        long age = now - fetched;
        boolean stale = age > staleAfter;
        String s = stale ? "Updated " + age(age) : age(age);
        float w = g.measure(s, Gfx.TEXT_MEDIUM, dp(11.5f), 0);
        int c = stale ? Pixels.alpha(Tone.AMBER, a) : Tone.text(Tone.TERTIARY, a);
        g.text(s, Gfx.TEXT_MEDIUM, dp(11.5f), 0, r, base, c, Gfx.RIGHT, 0);
        if (!stale) Icons.live(g, r - w - dp(12), base - dp(4), dp(12), Pixels.alpha(Tone.LIVE, a));
    }

    /** "13°" then "/56°" dimmer: a temperature in both units, lead unit first. */
    private float pair(Gfx g, String label, double c, float x, float base, float size, float a) {
        Model.Weather w = weather;
        String lead = label + w.lead(c) + "°", other = "/" + w.other(c) + "°";
        g.text(lead, Gfx.TEXT_MEDIUM, size, 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        x += g.measure(lead, Gfx.TEXT_MEDIUM, size, 0);
        g.text(other, Gfx.TEXT_MEDIUM, size, 0, x, base, Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        return x + g.measure(other, Gfx.TEXT_MEDIUM, size, 0);
    }

    // ---- Compact segments ------------------------------------------------------------------------

    private static final float SEG_PAD_L = 16, SEG_PAD_R = 20;

    /** Width of a compact segment, from what it will draw. */
    float compactWidth(Gfx g, int kind) {
        return dp(SEG_PAD_L + SEG_PAD_R) + compactBody(g, kind, 0, 0, 0, false);
    }

    void compact(Gfx g, int kind, float l, float t, float r, float b, float a) {
        if (a <= 0.003f) return;
        compactBody(g, kind, l + dp(SEG_PAD_L), (t + b) / 2, a, true);
    }

    /** Draws (or, with draw false, measures) a segment's content from x at centre line cy. */
    private float compactBody(Gfx g, int kind, float x, float cy, float a, boolean draw) {
        float x0 = x;
        switch (kind) {
            case WEATHER: {
                Model.Weather w = weather;
                if (w == null || !w.ok) {
                    if (draw) Icons.weather(g, Icons.CLOUD, x + dp(15), cy, dp(28), Tone.text(Tone.TERTIARY, a));
                    return dp(30);
                }
                if (draw) Icons.weatherColor(g, Icons.forCode(w.code, w.day), x + dp(15), cy, dp(30), a);
                x += dp(40);
                String lead = w.lead(w.tempC) + "°", other = w.other(w.tempC) + "°" + w.otherUnit();
                float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(21)) / 2;
                if (draw) g.text(lead, Gfx.TEXT_SEMIBOLD, dp(21), -0.01f, x, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
                x += g.measure(lead, Gfx.TEXT_SEMIBOLD, dp(21), -0.01f) + dp(7);
                if (draw) g.text(other, Gfx.TEXT_MEDIUM, dp(15), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
                x += g.measure(other, Gfx.TEXT_MEDIUM, dp(15), 0);
                return x - x0;
            }
            case BIKES:
                return compactBikes(g, x, cy, a, draw);
            case TRANSIT:
                return compactTransit(g, x, cy, a, draw);
            case PLANS:
                return compactPlans(g, x, cy, a, draw);
            case MUSIC:
                return compactMusic(g, x, cy, a, draw);
            default:
                return compactSky(g, x, cy, a, draw);
        }
    }

    private float compactBikes(Gfx g, float x, float cy, float a, boolean draw) {
        float x0 = x;
        if (finding()) {
            if (draw) Icons.radar(g, x + dp(14), cy, dp(30), time, still, Tone.text(Tone.SECONDARY, a), Pixels.alpha(Tone.LIVE, a));
            x += dp(38);
            float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(16)) / 2;
            String s = "Finding e-bike";
            if (draw) g.text(s, Gfx.TEXT_SEMIBOLD, dp(16), 0, x, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            x += g.measure(s, Gfx.TEXT_SEMIBOLD, dp(16), 0) + dp(9);
            String e = finder.offline ? "Reconnecting" : elapsed(now - finder.started);
            int c = finder.offline ? Pixels.alpha(Tone.AMBER, a) : Tone.text(Tone.SECONDARY, a);
            if (draw) g.text(e, Gfx.TEXT_MEDIUM, dp(15), 0, x, base, c, Gfx.LEFT, 0);
            // Width reserved for "00:00" so the bar does not breathe every second.
            x += Math.max(g.measure(e, Gfx.TEXT_MEDIUM, dp(15), 0), g.measure("00:00", Gfx.TEXT_MEDIUM, dp(15), 0));
            return x - x0;
        }
        if (foundRecently()) {
            if (draw) Icons.ebike(g, x + dp(15), cy, dp(28), Tone.text(Tone.PRIMARY, a), Pixels.alpha(Tone.VOLT, a));
            x += dp(40);
            String s = finder.ebikes == 1 ? "E-bike ready" : finder.ebikes + " e-bikes ready";
            float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(16)) / 2;
            if (draw) g.text(s, Gfx.TEXT_SEMIBOLD, dp(16), 0, x, base, Pixels.alpha(Tone.VOLT, a), Gfx.LEFT, 0);
            return x + g.measure(s, Gfx.TEXT_SEMIBOLD, dp(16), 0) - x0;
        }
        Model.Station s = bikes == null ? null : bikes.main();
        if (s == null || !s.live) {
            if (draw) Icons.bike(g, x + dp(14), cy, dp(28), Tone.text(Tone.SECONDARY, a));
            x += dp(38);
            if (draw) g.text("Bluebikes", Gfx.TEXT_MEDIUM, dp(15), 0, x, cy + cap(g, Gfx.TEXT_MEDIUM, dp(15)) / 2,
                    Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            return x + g.measure("Bluebikes", Gfx.TEXT_MEDIUM, dp(15), 0) - x0;
        }
        boolean renting = s.installed && s.renting;
        // Counts older than five minutes step back, as the card's amber "Updated" line explains.
        float dim = renting && !bikesStale() ? 1 : 0.5f;
        int e = s.rentableEbikes();
        if (draw) Icons.ebike(g, x + dp(15), cy, dp(28), Tone.text(Tone.PRIMARY, a * dim), Pixels.alpha(Tone.VOLT, a * dim));
        x += dp(38);
        float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(21)) / 2;
        String es = String.valueOf(e);
        int ec = e > 0 ? Pixels.alpha(Tone.VOLT, a * dim) : Tone.text(Tone.TERTIARY, a);
        if (draw) g.text(es, Gfx.TEXT_SEMIBOLD, dp(21), 0, x, base, ec, Gfx.LEFT, 0);
        x += g.measure(es, Gfx.TEXT_SEMIBOLD, dp(21), 0) + dp(14);
        Model.Station o = bikes.alt();
        if (o != null && o.live) {
            // The second watched dock's e-bikes, named, beside the first's.
            String nm = altShort();
            if (draw) g.text(nm, Gfx.TEXT_MEDIUM, dp(14), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            x += g.measure(nm, Gfx.TEXT_MEDIUM, dp(14), 0) + dp(6);
            int oe = o.rentableEbikes();
            String os = String.valueOf(oe);
            if (draw) g.text(os, Gfx.TEXT_SEMIBOLD, dp(19), 0, x, base, oe > 0 ? Pixels.alpha(Tone.VOLT, a) : Tone.text(Tone.TERTIARY, a),
                    Gfx.LEFT, 0);
            x += g.measure(os, Gfx.TEXT_SEMIBOLD, dp(19), 0) + dp(4);
        }
        if (o == null) {
            // With one dock watched, the classic bikes and docks too; with two, e-bikes only.
            if (draw) Icons.bike(g, x + dp(12), cy, dp(24), Pixels.alpha(Tone.PEDAL, 0.9f * a * dim));
            x += dp(30);
            String cs = String.valueOf(s.rentableClassic());
            if (draw) g.text(cs, Gfx.TEXT_MEDIUM, dp(17), 0, x, base, Tone.text(Tone.SECONDARY, a * dim), Gfx.LEFT, 0);
            x += g.measure(cs, Gfx.TEXT_MEDIUM, dp(17), 0);
            x += dp(14);
            if (draw) Icons.dock(g, x + dp(10), cy, dp(20), Pixels.alpha(Tone.SLATE, 0.85f * a));
            x += dp(26);
            String ds = String.valueOf(s.docks);
            if (draw) g.text(ds, Gfx.TEXT_MEDIUM, dp(17), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            x += g.measure(ds, Gfx.TEXT_MEDIUM, dp(17), 0);
        }
        if (!fcShort.isEmpty() && !bikesStale()) {
            // "gone by 8:10" or "next 9:25": the forecast in a few words.
            x += dp(14);
            if (draw) g.text(fcShort, Gfx.TEXT_MEDIUM, dp(14), 0, x, base, Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            x += g.measure(fcShort, Gfx.TEXT_MEDIUM, dp(14), 0);
        }
        return x - x0;
    }

    /** Lines with a bus coming, soonest first. */
    List<Model.Line> running() {
        List<Model.Line> out = new ArrayList<>();
        // Minutes are worked out at fetch time: once they are old they are wrong, so none show.
        if (transit == null || transitStale()) return out;
        for (Model.Line l : transit.lines) if (!l.minutes.isEmpty()) out.add(l);
        Collections.sort(out, new Comparator<Model.Line>() {
            @Override
            public int compare(Model.Line p, Model.Line q) {
                return Integer.compare(p.minutes.get(0), q.minutes.get(0));
            }
        });
        return out;
    }

    private float compactTransit(Gfx g, float x, float cy, float a, boolean draw) {
        float x0 = x;
        List<Model.Line> run = running();
        if (run.isEmpty()) {
            if (draw) Icons.bus(g, x + dp(13), cy, dp(26), Tone.text(Tone.SECONDARY, a));
            x += dp(36);
            String s = transit == null ? "Shuttles" : transitStale() ? "Reconnecting" : "No shuttles now";
            if (draw) g.text(s, Gfx.TEXT_MEDIUM, dp(15), 0, x, cy + cap(g, Gfx.TEXT_MEDIUM, dp(15)) / 2,
                    Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            return x + g.measure(s, Gfx.TEXT_MEDIUM, dp(15), 0) - x0;
        }
        float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(21)) / 2;
        int shown = Math.min(2, run.size());
        for (int i = 0; i < shown; i++) {
            Model.Line l = run.get(i);
            if (i > 0) x += dp(18);
            float cw = draw ? Icons.chip(g, x, cy, dp(21), l.chip, l.color, a) : chipWidth(g, l.chip, dp(21));
            x += cw + dp(8);
            // A city bus runs both ways past the dock: say which way this one is going.
            if (!l.toward.isEmpty()) {
                if (draw) g.text(l.toward, Gfx.TEXT_MEDIUM, dp(14), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
                x += g.measure(l.toward, Gfx.TEXT_MEDIUM, dp(14), 0) + dp(7);
            }
            int m = l.minutes.get(0);
            String lead = Transit.lead(m);
            if (draw) g.text(lead, Gfx.TEXT_SEMIBOLD, dp(21), 0, x, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            x += g.measure(lead, Gfx.TEXT_SEMIBOLD, dp(21), 0);
            if (Transit.unit(m)) {
                x += dp(3);
                if (draw) g.text("min", Gfx.TEXT_MEDIUM, dp(13), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
                x += g.measure("min", Gfx.TEXT_MEDIUM, dp(13), 0);
            }
        }
        return x - x0;
    }

    private float chipWidth(Gfx g, String code, float h) {
        return Math.max(h * 1.5f, g.measure(code, Gfx.TEXT_SEMIBOLD, h * 0.5f, 0.06f) + h * 0.7f);
    }

    private float compactSky(Gfx g, float x, float cy, float a, boolean draw) {
        float x0 = x;
        Model.Flight f = sky == null ? null : sky.hero();
        if (draw) Icons.plane(g, x + dp(13), cy, dp(26), f == null ? 45 : f.track, Tone.text(Tone.PRIMARY, a));
        x += dp(36);
        if (f == null) {
            if (draw) g.text("Clear skies", Gfx.TEXT_MEDIUM, dp(15), 0, x, cy + cap(g, Gfx.TEXT_MEDIUM, dp(15)) / 2,
                    Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            return x + g.measure("Clear skies", Gfx.TEXT_MEDIUM, dp(15), 0) - x0;
        }
        float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(17)) / 2;
        String head = !f.from.isEmpty() && !f.to.isEmpty() ? f.from + " \u2192 " + f.to
                : !f.from.isEmpty() ? "From " + f.from : !f.to.isEmpty() ? "To " + f.to : Sky.flightName(f);
        if (draw) g.text(head, Gfx.TEXT_SEMIBOLD, dp(17), 0.01f, x, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        x += g.measure(head, Gfx.TEXT_SEMIBOLD, dp(17), 0.01f) + dp(9);
        String alt = thousands(f.altFt) + " ft";
        if (draw) g.text(alt, Gfx.TEXT_MEDIUM, dp(14), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        x += g.measure(alt, Gfx.TEXT_MEDIUM, dp(14), 0);
        return x - x0;
    }

    // ---- Focused cards ---------------------------------------------------------------------------

    static final float CARD_H = 188;

    float expandedWidth(int kind) {
        switch (kind) {
            case WEATHER: return dp(304);
            case BIKES: return dp(344);
            case TRANSIT: return dp(364);
            case PLANS: return dp(380);
            case MUSIC: return dp(400);
            default: return dp(352);
        }
    }

    void expanded(Gfx g, int kind, float l, float t, float r, float b, float a) {
        if (a <= 0.003f) return;
        switch (kind) {
            case WEATHER:
                weatherCard(g, l, t, r, a);
                break;
            case BIKES:
                bikesCard(g, l, t, r, b, a);
                break;
            case TRANSIT:
                transitCard(g, l, t, r, a, 4, dp(33));
                break;
            case PLANS:
                plansCard(g, l, t, r, a);
                break;
            case MUSIC:
                musicCard(g, l, t, r, b, a);
                break;
            default:
                skyCard(g, l, t, r, a);
        }
    }

    private void weatherCard(Gfx g, float l, float t, float r, float a) {
        Model.Weather w = weather;
        float x = l + dp(22);
        meta(g, w == null || w.city.isEmpty() ? "Weather" : w.city, x, t + dp(30), a);
        if (w == null || !w.ok) {
            g.text("Weather is on its way", Gfx.TEXT_MEDIUM, dp(16), 0, x, t + dp(80), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            return;
        }
        if (!w.updated.isEmpty()) {
            g.text(w.updated, Gfx.TEXT_MEDIUM, dp(11.5f), 0, r - dp(22), t + dp(30), Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
        }
        Icons.weatherColor(g, Icons.forCode(w.code, w.day), x + dp(26), t + dp(74), dp(54), a);
        float tx = x + dp(66), base = t + dp(96);
        String lead = w.lead(w.tempC) + "°";
        g.text(lead, Gfx.SERIF_LIGHT, dp(60), -0.02f, tx, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0.2f);
        tx += g.measure(lead, Gfx.SERIF_LIGHT, dp(60), -0.02f) + dp(8);
        g.text(w.other(w.tempC) + "°" + w.otherUnit(), Gfx.TEXT_MEDIUM, dp(22), 0, tx, base, Tone.text(Tone.SECONDARY, a),
                Gfx.LEFT, 0);
        g.text(w.condition, Gfx.TEXT_SEMIBOLD, dp(16), 0, x, t + dp(128), Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        float px = pair(g, "H ", w.hiC, x, t + dp(150), dp(14), a);
        px = pair(g, "   L ", w.loC, px, t + dp(150), dp(14), a);
        pair(g, "   Feels ", w.feelsC, px, t + dp(150), dp(14), a);
        if (w.wetSoon) {
            // Rain in the next two hours matters more than the humidity: it takes the line, in cyan.
            g.text(w.rain, Gfx.TEXT_SEMIBOLD, dp(13), 0, x, t + dp(172), Pixels.alpha(Tone.RAIN, a), Gfx.LEFT, 0);
        } else {
            g.text(details(w), Gfx.TEXT_REGULAR, dp(13), 0, x, t + dp(172), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        }
    }

    /** "Humidity 58%  ·  Wind 9 mph W", wind in the lead unit's system. */
    private static String details(Model.Weather w) {
        StringBuilder b = new StringBuilder();
        if (w.humidity >= 0) b.append("Humidity ").append(w.humidity).append('%');
        if (w.windKph >= 0 && !Double.isNaN(w.windKph)) {
            if (b.length() > 0) b.append("  ·  ");
            int speed = (int) Math.round(w.celsiusFirst ? w.windKph : w.windKph / 1.609344);
            b.append("Wind ").append(speed).append(w.celsiusFirst ? " km/h " : " mph ").append(Weather.compass(w.windDir));
        }
        return b.toString();
    }

    private void bikesCard(Gfx g, float l, float t, float r, float b, float a) {
        float x = l + dp(22);
        Model.Station s = bikes == null ? null : bikes.main();
        meta(g, stationLabel() + " · Bluebikes", x, t + dp(30), a);
        if (bikes != null) freshness(g, bikes.fetched, 5 * 60000L, r - dp(22), t + dp(30), a);
        if (s == null || !s.live) {
            g.text("Waiting for Bluebikes", Gfx.TEXT_MEDIUM, dp(16), 0, x, t + dp(84), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            hint(g, x, r, t + dp(166), a);
            return;
        }
        stats(g, s, x, r - dp(22), t + dp(46), 1f, a);
        Model.Station o = bikes.alt();
        if (o != null) {
            // The second watched dock on one line: its name and counts.
            g.text(bikes.altLabel.toUpperCase(Locale.US), Gfx.MONO, dp(11), 0.1f, x, t + dp(136), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            miniCounts(g, o, x + dp(86), t + dp(138), a);
        } else {
            dockBar(g, s, x, t + dp(128), r - dp(22), dp(8), a);
        }
        if (!fcCard.isEmpty() && !bikesStale() && !finding() && !foundRecently()) {
            g.text(fcCard, Gfx.TEXT_SEMIBOLD, dp(13.5f), 0, x, t + dp(155), fcEmpty ? Tone.text(Tone.SECONDARY, a)
                    : Pixels.alpha(Tone.VOLT, a), Gfx.LEFT, 0);
        }
        hint(g, x, r, t + dp(176), a);
    }

    /** The three counts: e-bikes as the hero in Volt, classic and docks beside it. */
    private void stats(Gfx g, Model.Station s, float x, float r, float top, float k, float a) {
        boolean renting = s.installed && s.renting;
        float dim = renting ? 1 : 0.5f;
        int e = s.rentableEbikes();
        float base = top + dp(50 * k);
        String es = String.valueOf(e);
        g.text(es, Gfx.SERIF_LIGHT, dp(60 * k), -0.02f, x - dp(2), base, e > 0 ? Pixels.alpha(Tone.VOLT, a * dim)
                : Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0.2f);
        float ew = g.measure(es, Gfx.SERIF_LIGHT, dp(60 * k), -0.02f);
        Icons.bolt(g, x + dp(6 * k), base + dp(18 * k), dp(13 * k), Pixels.alpha(Tone.VOLT, a * dim));
        g.text(e == 1 ? "e-bike" : "e-bikes", Gfx.TEXT_MEDIUM, dp(13 * k), 0, x + dp(15 * k), base + dp(22 * k),
                Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        float colW = (r - x - Math.max(ew, dp(84 * k))) / 2;
        float c1 = x + Math.max(ew, dp(84 * k)) + dp(10 * k), c2 = c1 + colW;
        String cs = String.valueOf(s.rentableClassic()), ds = String.valueOf(s.docks);
        float sb = top + dp(42 * k);
        g.text(cs, Gfx.TEXT_SEMIBOLD, dp(30 * k), -0.01f, c1, sb, Tone.text(Tone.PRIMARY, a * dim), Gfx.LEFT, 0);
        g.text(ds, Gfx.TEXT_SEMIBOLD, dp(30 * k), -0.01f, c2, sb, Tone.text(Tone.PRIMARY, a * (s.returning ? 1 : 0.5f)), Gfx.LEFT, 0);
        Icons.bike(g, c1 + dp(9 * k), base + dp(17 * k), dp(18 * k), Pixels.alpha(Tone.PEDAL, a * dim));
        g.text("classic", Gfx.TEXT_MEDIUM, dp(13 * k), 0, c1 + dp(21 * k), base + dp(22 * k), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        Icons.dock(g, c2 + dp(7 * k), base + dp(17 * k), dp(15 * k), Pixels.alpha(Tone.SLATE, a));
        g.text(s.returning ? "docks" : "returns closed", Gfx.TEXT_MEDIUM, dp(13 * k), 0, c2 + dp(18 * k), base + dp(22 * k),
                Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
    }

    /**
     * The station as a row of docks: Volt for e-bikes, blue for classic bikes, outlines for empty
     * docks and faint marks for broken ones, the way a transit app fills its station pins.
     */
    private void dockBar(Gfx g, Model.Station s, float l, float t, float r, float h, float a) {
        int e = s.rentableEbikes() > 0 ? s.ebikes : 0, c = s.classic, k = s.disabled, empty = s.docks;
        int n = Math.max(s.capacity, e + c + k + empty);
        if (n <= 0) return;
        if (n > 60) {
            // Big stations: proportional bands instead of single docks.
            float w = r - l;
            float x = l;
            int[] counts = {e, c, k, empty};
            int[] colors = {Tone.VOLT, Tone.PEDAL, Tone.text(0.25f, 1), Tone.text(0.12f, 1)};
            for (int i = 0; i < 4; i++) {
                float sw = w * counts[i] / n;
                if (sw > 0.5f) g.fillRoundRect(x, t, x + sw - dp(1), t + h, h / 2, Pixels.alpha(colors[i], a));
                x += sw;
            }
            return;
        }
        float gap = dp(n > 36 ? 1.5f : 2.5f);
        float sw = (r - l - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            float x = l + i * (sw + gap);
            if (i < e) {
                g.fillRoundRect(x, t, x + sw, t + h, Math.min(sw, h) / 2, Pixels.alpha(Tone.VOLT, a));
            } else if (i < e + c) {
                g.fillRoundRect(x, t, x + sw, t + h, Math.min(sw, h) / 2, Pixels.alpha(Tone.PEDAL, 0.92f * a));
            } else if (i < e + c + k) {
                g.fillRoundRect(x, t, x + sw, t + h, Math.min(sw, h) / 2, Tone.text(0.16f, a));
            } else {
                g.strokeRoundRect(x + dp(0.5f), t + dp(0.5f), x + sw - dp(0.5f), t + h - dp(0.5f), Math.min(sw, h) / 2,
                        dp(1), Tone.text(0.3f, a));
            }
        }
    }

    /** The bottom line of the bikes card: what OK does, or what bike finder is doing. */
    private void hint(Gfx g, float x, float r, float base, float a) {
        float cy = base - dp(5);
        if (finding()) {
            Icons.radar(g, x + dp(10), cy, dp(20), time, still, Tone.text(Tone.SECONDARY, a), Pixels.alpha(Tone.LIVE, a));
            String s = finder.offline ? "Reconnecting to Bluebikes…"
                    : "Watching · checked " + (finder.lastCheck > 0 ? age(now - finder.lastCheck) : "now");
            g.text(s, Gfx.TEXT_MEDIUM, dp(13.5f), 0, x + dp(28), base, finder.offline ? Pixels.alpha(Tone.AMBER, a)
                    : Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            g.text(Notice.minutes(finder.deadline - now) + " left", Gfx.TEXT_MEDIUM, dp(13), 0, r - dp(22), base,
                    Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
            return;
        }
        if (foundRecently()) {
            Icons.bolt(g, x + dp(7), cy, dp(16), Pixels.alpha(Tone.VOLT, a));
            g.text("E-bike docked at " + clock(finder.ended), Gfx.TEXT_SEMIBOLD, dp(14), 0, x + dp(22), base,
                    Pixels.alpha(Tone.VOLT, a), Gfx.LEFT, 0);
            return;
        }
        Model.Station s = bikes == null ? null : bikes.main();
        if (s != null && s.live && (!s.installed || !s.renting)) {
            g.text(s.installed ? "Station not renting right now" : "Station offline", Gfx.TEXT_MEDIUM, dp(14), 0, x, base,
                    Pixels.alpha(Tone.AMBER, a), Gfx.LEFT, 0);
            return;
        }
        float kw = Icons.keycap(g, x, cy, dp(19), "OK", Tone.text(Tone.SECONDARY, a));
        g.text("Find me an e-bike", Gfx.TEXT_MEDIUM, dp(14), 0, x + kw + dp(9), base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        if (s != null && s.live && s.rentableEbikes() > 0) {
            g.text("one is there now", Gfx.TEXT_MEDIUM, dp(13), 0, r - dp(22), base, Pixels.alpha(Tone.VOLT, a), Gfx.RIGHT, 0);
        }
    }

    private void transitCard(Gfx g, float l, float t, float r, float a, int rows, float rowH) {
        float x = l + dp(22), right = r - dp(22);
        meta(g, "Shuttles and buses", x, t + dp(30), a);
        if (transit == null) {
            g.text("Looking for shuttles", Gfx.TEXT_MEDIUM, dp(16), 0, x, t + dp(84), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            return;
        }
        freshness(g, transit.fetched, 90000L, right, t + dp(30), a);
        List<Model.Line> order = new ArrayList<>(running());
        for (Model.Line l2 : transit.lines) if (l2.minutes.isEmpty()) order.add(l2);
        float chipW = 0;
        for (Model.Line ln : order) chipW = Math.max(chipW, chipWidth(g, ln.chip, dp(22)));
        float y = t + dp(46);
        for (int i = 0; i < Math.min(rows, order.size()); i++) {
            Model.Line ln = order.get(i);
            float cy = y + rowH / 2;
            boolean live = !ln.minutes.isEmpty();
            float la = live ? a : a * 0.5f;
            Icons.chip(g, x, cy, dp(22), ln.chip, ln.color, la);
            float base = cy + cap(g, Gfx.TEXT_MEDIUM, dp(15)) / 2;
            g.text(ln.label, Gfx.TEXT_MEDIUM, dp(15), 0, x + chipW + dp(12), base, Tone.text(Tone.PRIMARY, la), Gfx.LEFT, 0);
            if (!live) {
                g.text(ln.idle, Gfx.TEXT_REGULAR, dp(13), 0, right, base, Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
            } else {
                countdown(g, ln, right, cy, dp(22), a);
            }
            y += rowH;
        }
    }


    /**
     * Lead minutes right-aligned with "min" beside them, then the next buses as "then 16 · 28"
     * to the left. Returns the left edge used.
     */
    private float countdown(Gfx g, Model.Line ln, float right, float cy, float size, float a) {
        int m = ln.minutes.get(0);
        String lead = Transit.lead(m);
        float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, size) / 2;
        float x = right;
        if (Transit.unit(m)) {
            g.text("min", Gfx.TEXT_MEDIUM, size * 0.55f, 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.RIGHT, 0);
            x -= g.measure("min", Gfx.TEXT_MEDIUM, size * 0.55f, 0) + dp(3);
        }
        g.text(lead, Gfx.TEXT_SEMIBOLD, size, 0, x, base, Tone.text(Tone.PRIMARY, a), Gfx.RIGHT, 0);
        x -= g.measure(lead, Gfx.TEXT_SEMIBOLD, size, 0);
        if (ln.minutes.size() > 1) {
            StringBuilder b = new StringBuilder("then ");
            for (int i = 1; i < Math.min(3, ln.minutes.size()); i++) {
                if (i > 1) b.append(" · ");
                b.append(Transit.lead(ln.minutes.get(i)));
            }
            float ts = Math.max(dp(12), size * 0.55f);
            x -= dp(12);
            g.text(b.toString(), Gfx.TEXT_REGULAR, ts, 0, x, base, Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
            x -= g.measure(b.toString(), Gfx.TEXT_REGULAR, ts, 0);
        }
        return x;
    }

    private void skyCard(Gfx g, float l, float t, float r, float a) {
        float x = l + dp(22), right = r - dp(22);
        Model.Flight f = sky == null ? null : sky.hero();
        meta(g, "Overhead", x, t + dp(30), a);
        if (f == null) {
            g.text("Nothing in the air nearby", Gfx.TEXT_MEDIUM, dp(16), 0, x, t + dp(84), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            return;
        }
        g.text(String.format(Locale.US, "%.1f mi away", f.miles), Gfx.TEXT_MEDIUM, dp(11.5f), 0, right, t + dp(30),
                Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
        Icons.plane(g, right - dp(16), t + dp(62), dp(30), f.track, Tone.text(Tone.PRIMARY, a));
        g.text(Sky.flightName(f), Gfx.TEXT_SEMIBOLD, dp(17), 0, x, t + dp(62), Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        route(g, f, x, t + dp(112), right - dp(40), dp(40), a);
        g.text(telemetry(f), Gfx.TEXT_MEDIUM, dp(13.5f), 0, x, t + dp(172), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        if (!f.type.isEmpty()) g.text(f.type, Gfx.TEXT_SEMIBOLD, dp(12), 0.08f, right, t + dp(172), Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
    }

    /**
     * "BOS → SFO" in serif with the places beneath. When only one end is believable (a low flight
     * near Logan whose looked-up route was stale), "Departing Boston" or "Landing at Boston".
     */
    private void route(Gfx g, Model.Flight f, float x, float base, float maxR, float size, float a) {
        if (f.from.isEmpty() && f.to.isEmpty()) {
            g.text("Route unknown", Gfx.SERIF_LIGHT, size * 0.7f, -0.01f, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            if (!f.reg.isEmpty()) g.text(f.reg, Gfx.TEXT_MEDIUM, dp(13), 0, x, base + dp(20), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            return;
        }
        if (f.from.isEmpty() || f.to.isEmpty()) {
            boolean leaving = f.to.isEmpty();
            String code = leaving ? f.from : f.to, city = leaving ? f.fromCity : f.toCity;
            g.text(leaving ? "Departing" : "Landing at", Gfx.SERIF_ITALIC, size * 0.62f, 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            float w = g.measure(leaving ? "Departing" : "Landing at", Gfx.SERIF_ITALIC, size * 0.62f, 0) + dp(12);
            g.text(code, Gfx.SERIF_LIGHT, size, 0.01f, x + w, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0.2f);
            if (!city.isEmpty()) g.text(city, Gfx.TEXT_REGULAR, dp(13), 0, x + w, base + dp(20), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            return;
        }
        g.text(f.from, Gfx.SERIF_LIGHT, size, 0.01f, x, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0.2f);
        float fw = g.measure(f.from, Gfx.SERIF_LIGHT, size, 0.01f);
        float ax0 = x + fw + dp(14), ax1 = ax0 + size * 1.05f;
        float ay = base - g.capHeight(Gfx.SERIF_LIGHT, size) / 2;
        g.line(ax0, ay, ax1, ay, dp(1.5f), Tone.text(Tone.SECONDARY, a));
        Icons.plane(g, (ax0 + ax1) / 2, ay, dp(14), 90, Tone.text(Tone.PRIMARY, a));
        float tx = ax1 + dp(14);
        g.text(f.to, Gfx.SERIF_LIGHT, size, 0.01f, tx, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0.2f);
        g.text(Notice.fit(g, f.fromCity, Gfx.TEXT_REGULAR, dp(13), ax0 - x), Gfx.TEXT_REGULAR, dp(13), 0, x, base + dp(20),
                Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        g.text(Notice.fit(g, f.toCity, Gfx.TEXT_REGULAR, dp(13), Math.max(dp(60), maxR - tx + dp(40))), Gfx.TEXT_REGULAR, dp(13), 0,
                tx, base + dp(20), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
    }

    private static String telemetry(Model.Flight f) {
        StringBuilder b = new StringBuilder();
        b.append(thousands(f.altFt)).append(" ft  ·  ").append(f.knots).append(" kt");
        if (f.fpm <= -300) b.append("  ·  Descending");
        else if (f.fpm >= 300) b.append("  ·  Climbing");
        else b.append("  ·  Level");
        return b.toString();
    }

    // ---- Panels ----------------------------------------------------------------------------------

    float[] panelSize(int kind) {
        switch (kind) {
            case WEATHER: return new float[] {dp(640), dp(356)};
            case BIKES: return new float[] {dp(660), dp(436)};
            case TRANSIT: return new float[] {dp(620), dp(436)};
            case PLANS: return new float[] {dp(760), dp(440)};
            case MUSIC: return new float[] {dp(720), dp(360)};
            default: return new float[] {dp(620), dp(372)};
        }
    }

    /** {@code button} is how lit the panel's button is (bikes only), 0..1. */
    void panel(Gfx g, int kind, float l, float t, float r, float b, float a, float button, float rad) {
        if (a <= 0.003f) return;
        switch (kind) {
            case WEATHER:
                weatherPanel(g, l, t, r, b, a);
                break;
            case BIKES:
                bikesPanel(g, l, t, r, b, a, button, rad);
                break;
            case TRANSIT:
                transitPanel(g, l, t, r, b, a);
                break;
            case PLANS:
                plansPanel(g, l, t, r, b, a);
                break;
            case MUSIC:
                musicPanel(g, l, t, r, b, a, button);
                break;
            default:
                skyPanel(g, l, t, r, b, a);
        }
    }

    private void title(Gfx g, String s, String sub, float x, float t, float a) {
        g.text(s, Gfx.SERIF, dp(34), -0.01f, x, t + dp(54), Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        if (sub != null && !sub.isEmpty()) {
            g.text(sub, Gfx.TEXT_REGULAR, dp(14.5f), 0, x + dp(1), t + dp(76), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        }
    }

    private void weatherPanel(Gfx g, float l, float t, float r, float b, float a) {
        Model.Weather w = weather;
        float x = l + dp(32);
        if (w == null || !w.ok) {
            title(g, "Weather", "Waiting for Open-Meteo", x, t, a);
            return;
        }
        Icons.weatherColor(g, Icons.forCode(w.code, w.day), x + dp(36), t + dp(86), dp(76), a);
        float tx = x + dp(92), base = t + dp(114);
        String lead = w.lead(w.tempC) + "°";
        g.text(lead, Gfx.SERIF_LIGHT, dp(84), -0.02f, tx, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0.2f);
        tx += g.measure(lead, Gfx.SERIF_LIGHT, dp(84), -0.02f) + dp(10);
        g.text(w.other(w.tempC) + "°" + w.otherUnit(), Gfx.TEXT_MEDIUM, dp(26), 0, tx, base, Tone.text(Tone.SECONDARY, a),
                Gfx.LEFT, 0);
        float cx = l + dp(372);
        g.text(w.condition, Gfx.TEXT_SEMIBOLD, dp(19), 0, cx, t + dp(64), Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        float px = pair(g, "High ", w.hiC, cx, t + dp(90), dp(15), a);
        pair(g, "    Low ", w.loC, px, t + dp(90), dp(15), a);
        pair(g, "Feels like ", w.feelsC, cx, t + dp(112), dp(15), a);
        if (w.wetSoon) {
            g.text(w.rain, Gfx.TEXT_SEMIBOLD, dp(14), 0, cx, t + dp(134), Pixels.alpha(Tone.RAIN, a), Gfx.LEFT, 0);
        } else {
            g.text(details(w), Gfx.TEXT_REGULAR, dp(14), 0, cx, t + dp(134), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        }
        int n = Math.min(7, w.hourC.size());
        float lineY = t + dp(160);
        g.fillRect(l + dp(28), lineY, r - dp(28), lineY + Math.max(1, dp(1)), Tone.hairline(a));
        if (n > 0) {
            float colW = (r - l - dp(56)) / n;
            for (int i = 0; i < n; i++) {
                float ccx = l + dp(28) + colW * (i + 0.5f);
                g.text(w.hourLabel.get(i), Gfx.TEXT_MEDIUM, dp(13), 0.02f, ccx, lineY + dp(32), Tone.text(Tone.SECONDARY, a),
                        Gfx.CENTER, 0);
                Icons.weatherColor(g, Icons.forCode(w.hourCode.get(i), w.hourDay.get(i)), ccx, lineY + dp(62), dp(34), a);
                int chance = i < w.hourRain.size() ? w.hourRain.get(i) : -1;
                if (chance >= 30) {
                    // Rain chance in cyan, only when it is worth knowing.
                    g.text(chance + "%", Gfx.TEXT_SEMIBOLD, dp(11.5f), 0, ccx, lineY + dp(94), Pixels.alpha(Tone.RAIN, a),
                            Gfx.CENTER, 0);
                }
                double c = w.hourC.get(i);
                g.text(w.lead(c) + "°", Gfx.TEXT_SEMIBOLD, dp(18), 0, ccx, lineY + dp(112), Tone.text(Tone.PRIMARY, a),
                        Gfx.CENTER, 0);
                g.text(w.other(c) + "°" + w.otherUnit(), Gfx.TEXT_MEDIUM, dp(13), 0, ccx, lineY + dp(132),
                        Tone.text(Tone.TERTIARY, a), Gfx.CENTER, 0);
            }
        }
        float fx = l + dp(32);
        Model.Air air = this.air;
        if (air != null && air.ok) {
            // Air and sun, coloured only when they ask for care.
            String aq = "Air " + air.aqi + ", " + Extras.aqiWord(air.aqi).toLowerCase(Locale.US);
            int aqC = air.aqi > 100 ? Pixels.alpha(Tone.AMBER, a) : Tone.text(Tone.SECONDARY, a);
            g.text(aq, Gfx.TEXT_MEDIUM, dp(12.5f), 0, fx, b - dp(20), aqC, Gfx.LEFT, 0);
            fx += g.measure(aq, Gfx.TEXT_MEDIUM, dp(12.5f), 0) + dp(14);
            if (air.uv >= 0) {
                String uv = "UV " + Math.round(air.uv) + ", " + Extras.uvWord(air.uv).toLowerCase(Locale.US);
                int uvC = air.uv >= 6 ? Pixels.alpha(Tone.AMBER, a) : Tone.text(Tone.SECONDARY, a);
                g.text(uv, Gfx.TEXT_MEDIUM, dp(12.5f), 0, fx, b - dp(20), uvC, Gfx.LEFT, 0);
                fx += g.measure(uv, Gfx.TEXT_MEDIUM, dp(12.5f), 0) + dp(14);
            }
        }
        Model.Alert al = alert;
        String foot = (al != null ? al.event + "  ·  " : "") + w.city + (w.updated.isEmpty() ? "" : "  ·  Updated " + w.updated)
                + "  ·  Weather by Open-Meteo" + (al != null ? " and the NWS" : "");
        g.text(Notice.fit(g, foot, Gfx.TEXT_REGULAR, dp(12), r - dp(32) - fx), Gfx.TEXT_REGULAR, dp(12), 0, fx,
                b - dp(20), al != null ? Pixels.alpha(Tone.AMBER, a) : Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
    }

    private void bikesPanel(Gfx g, float l, float t, float r, float b, float a, float button, float rad) {
        float x = l + dp(32);
        title(g, "Bluebikes", stationLabel() + (bikes != null && bikes.main() != null ? " · " + bikes.main().name : ""), x, t, a);
        if (bikes != null) freshness(g, bikes.fetched, 5 * 60000L, r - dp(32), t + dp(46), a);
        Model.Station s = bikes == null ? null : bikes.main();
        float leftR = l + dp(390);
        if (s != null && s.live) {
            stats(g, s, x, leftR - dp(18), t + dp(98), 1.12f, a);
            dockBar(g, s, x, t + dp(196), leftR - dp(18), dp(10), a);
            String cap = s.capacity + " docks  ·  " + (s.disabled > 0 ? s.disabled + " out of service  ·  " : "")
                    + "reported " + age(now - s.reported);
            g.text(Notice.fit(g, cap, Gfx.TEXT_REGULAR, dp(12.5f), leftR - x - dp(18)), Gfx.TEXT_REGULAR, dp(12.5f), 0, x,
                    t + dp(226), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        } else {
            g.text("Waiting for Bluebikes", Gfx.TEXT_MEDIUM, dp(17), 0, x, t + dp(150), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        }
        // The forecast across the panel: when the e-bikes run out, or when the next one comes.
        if (!fcLine.isEmpty() && !bikesStale()) {
            float fy = t + dp(268);
            g.fillRect(x, fy - dp(26), r - dp(32), fy - dp(26) + Math.max(1, dp(1)), Tone.hairline(a));
            String tag = fcTag.isEmpty() ? "FORECAST" : "FORECAST  ·  " + fcTag.toUpperCase(Locale.US);
            g.text(tag, Gfx.MONO, dp(11), 0.1f, x, fy - dp(6), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            g.text(Notice.fit(g, fcLine, Gfx.TEXT_SEMIBOLD, dp(16), r - x - dp(32)), Gfx.TEXT_SEMIBOLD, dp(16), 0, x, fy + dp(18),
                    fcEmpty ? Tone.text(Tone.PRIMARY, a) : Pixels.alpha(Tone.VOLT, a), Gfx.LEFT, 0);
            if (!fcLine2.isEmpty()) {
                g.text(Notice.fit(g, fcLine2, Gfx.TEXT_REGULAR, dp(14), r - x - dp(32)), Gfx.TEXT_REGULAR, dp(14), 0, x, fy + dp(40),
                        Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            }
        }
        // Nearby docks, in a column on the right.
        float nx = leftR + dp(12);
        g.fillRect(leftR - dp(6), t + dp(98), leftR - dp(6) + Math.max(1, dp(1)), t + dp(232), Tone.hairline(a));
        meta(g, "Nearby", nx, t + dp(112), a);
        float y = t + dp(140);
        if (bikes != null) {
            // Two neighbours when the forecast takes the panel's lower band, three otherwise.
            int shown = !fcLine.isEmpty() && !bikesStale() ? 3 : 4;
            for (int i = 1; i < Math.min(shown, bikes.stations.size()); i++) {
                Model.Station o = bikes.stations.get(i);
                String nm = o == bikes.alt() ? bikes.altLabel + ", also watched" : Gbfs.shortName(o, false);
                g.text(Notice.fit(g, nm, Gfx.TEXT_MEDIUM, dp(14.5f), r - nx - dp(80)), Gfx.TEXT_MEDIUM, dp(14.5f), 0, nx, y,
                        Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
                g.text(String.format(Locale.US, "%.1f mi", o.metres / 1609.344), Gfx.TEXT_REGULAR, dp(12.5f), 0, r - dp(32), y,
                        Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
                miniCounts(g, o, nx, y + dp(22), a);
                y += dp(44);
            }
        }
        // The one action: find an e-bike, or stop.
        float bt = b - dp(104), bb = bt + dp(52), inset = dp(18);
        float bl = l + inset, br = r - inset;
        float lit = Math.max(0, Math.min(1, button));
        g.fillRoundRect(bl, bt, br, bb, rad - inset, Tone.focus((0.12f + 0.81f * lit) * a));
        int ink = Tone.onFocus(lit, a);
        String label = finding() ? "Stop Finding" : foundRecently() ? "Find Another E-Bike" : "Find Me an E-Bike";
        float cy = (bt + bb) / 2;
        float lw = g.measure(label, Gfx.TEXT_SEMIBOLD, dp(18), 0);
        float iconW = dp(30);
        float lx = (bl + br) / 2 - (lw + iconW) / 2;
        if (finding()) Icons.radar(g, lx + dp(11), cy, dp(24), time, still, Pixels.alpha(ink, 0.8f), Pixels.alpha(Tone.LIVE, a));
        else Icons.ebike(g, lx + dp(11), cy, dp(24), ink, Pixels.alpha(lit > 0.5f ? 0xFFB88A00 : Tone.VOLT, a));
        g.text(label, Gfx.TEXT_SEMIBOLD, dp(18), 0, lx + iconW, cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(18)) / 2, ink, Gfx.LEFT, 0);
        String status;
        if (finding()) {
            status = finder.offline ? "Bluebikes is not answering; still trying"
                    : "Checking " + (finder.station.isEmpty() ? stationLabel() : finder.station) + " every 5 s. You will hear it, even in another app. "
                            + Notice.minutes(finder.deadline - now) + " left.";
        } else if (foundRecently()) {
            status = "An e-bike docked at " + clock(finder.ended) + ".";
        } else {
            status = bikes != null && bikes.alt() != null ? "Lucent checks " + stationLabel() + " and " + bikes.altLabel
                    + " every 5 s and rings for the first e-bike at either." : "Lucent will check every 5 s and ring the moment an e-bike can be rented.";
        }
        g.text(Notice.fit(g, status, Gfx.TEXT_REGULAR, dp(13), r - l - dp(64)), Gfx.TEXT_REGULAR, dp(13), 0, (l + r) / 2, b - dp(24),
                finder.offline && finding() ? Pixels.alpha(Tone.AMBER, a) : Tone.text(Tone.TERTIARY, a), Gfx.CENTER, 0);
    }

    private void miniCounts(Gfx g, Model.Station o, float x, float base, float a) {
        float cy = base - dp(5);
        boolean ok = o.live && o.installed && o.renting;
        float dim = ok ? 1 : 0.45f;
        Icons.bolt(g, x + dp(5), cy, dp(13), Pixels.alpha(Tone.VOLT, a * dim));
        String e = o.live ? String.valueOf(o.rentableEbikes()) : "-";
        g.text(e, Gfx.TEXT_SEMIBOLD, dp(13.5f), 0, x + dp(13), base, o.rentableEbikes() > 0 ? Pixels.alpha(Tone.VOLT, a)
                : Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        float x2 = x + dp(48);
        Icons.bike(g, x2 + dp(8), cy, dp(15), Pixels.alpha(Tone.PEDAL, a * dim));
        g.text(o.live ? String.valueOf(o.rentableClassic()) : "-", Gfx.TEXT_MEDIUM, dp(13.5f), 0, x2 + dp(19), base,
                Tone.text(Tone.SECONDARY, a * dim), Gfx.LEFT, 0);
        float x3 = x2 + dp(54);
        Icons.dock(g, x3 + dp(6), cy, dp(13), Pixels.alpha(Tone.SLATE, a));
        g.text(o.live ? String.valueOf(o.docks) : "-", Gfx.TEXT_MEDIUM, dp(13.5f), 0, x3 + dp(16), base,
                Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
    }

    private void transitPanel(Gfx g, float l, float t, float r, float b, float a) {
        float x = l + dp(32), right = r - dp(32);
        title(g, "Shuttles and Buses", "MIT shuttles, and the 85 both ways (85A Assembly, 85B BU)", x, t, a);
        if (transit == null) {
            g.text("Looking for shuttles", Gfx.TEXT_MEDIUM, dp(17), 0, x, t + dp(150), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            return;
        }
        freshness(g, transit.fetched, 90000L, right, t + dp(46), a);
        float chipW = 0;
        for (Model.Line ln : transit.lines) chipW = Math.max(chipW, chipWidth(g, ln.chip, dp(26)));
        float y = t + dp(100), rowH = dp(54);
        List<Model.Line> order = new ArrayList<>(running());
        for (Model.Line l2 : transit.lines) if (l2.minutes.isEmpty()) order.add(l2);
        for (Model.Line ln : order) {
            float cy = y + rowH / 2;
            boolean live = !ln.minutes.isEmpty();
            float la = live ? a : a * 0.5f;
            Icons.chip(g, x, cy, dp(26), ln.chip, ln.color, la);
            float tx = x + chipW + dp(14);
            g.text(ln.label, Gfx.TEXT_SEMIBOLD, dp(16.5f), 0, tx, cy - dp(2), Tone.text(Tone.PRIMARY, la), Gfx.LEFT, 0);
            g.text(ln.stop, Gfx.TEXT_REGULAR, dp(12.5f), 0, tx, cy + dp(16), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
            if (live) countdown(g, ln, right, cy, dp(28), a);
            else g.text(ln.idle, Gfx.TEXT_MEDIUM, dp(14), 0, right, cy + dp(5), Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
            y += rowH;
            g.fillRect(x, y - dp(0.5f), right, y - dp(0.5f) + Math.max(1, dp(1)), Tone.hairline(0.6f * a));
        }
        g.text(transit.live ? "Live from MIT's Passio feed and the MBTA's live stream." : "Live from MIT's Passio feed and the MBTA, every 10 s.", Gfx.TEXT_REGULAR, dp(12), 0,
                x, b - dp(20), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
    }

    private void skyPanel(Gfx g, float l, float t, float r, float b, float a) {
        float x = l + dp(32), right = r - dp(32);
        Model.Flight f = sky == null ? null : sky.hero();
        title(g, "Planes Overhead", sky == null ? "" : sky.inRange + " aircraft within 25 nautical miles", x, t, a);
        if (f == null) {
            g.text("Nothing in the air nearby", Gfx.TEXT_MEDIUM, dp(17), 0, x, t + dp(150), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            return;
        }
        Icons.plane(g, right - dp(26), t + dp(56), dp(44), f.track, Tone.text(Tone.PRIMARY, a));
        g.text(Sky.flightName(f), Gfx.TEXT_SEMIBOLD, dp(19), 0, x, t + dp(118), Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        g.text(String.format(Locale.US, "%.1f mi away", f.miles) + (f.type.isEmpty() ? "" : "  ·  " + f.type), Gfx.TEXT_MEDIUM,
                dp(13), 0, right, t + dp(118), Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
        route(g, f, x, t + dp(170), right, dp(46), a);
        g.text(telemetry(f), Gfx.TEXT_MEDIUM, dp(14), 0, x, t + dp(222), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        float y = t + dp(256);
        g.fillRect(x, y - dp(10), right, y - dp(10) + Math.max(1, dp(1)), Tone.hairline(a));
        for (int i = 1; i < Math.min(4, sky.flights.size()); i++) {
            Model.Flight o = sky.flights.get(i);
            Icons.plane(g, x + dp(9), y + dp(1), dp(16), o.track, Tone.text(Tone.SECONDARY, a));
            g.text(Sky.flightName(o), Gfx.TEXT_MEDIUM, dp(14), 0, x + dp(26), y + dp(6), Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            String rt = !o.from.isEmpty() && !o.to.isEmpty() ? o.from + " \u2192 " + o.to : !o.from.isEmpty() ? "From " + o.from
                    : !o.to.isEmpty() ? "To " + o.to : "";
            g.text(rt, Gfx.TEXT_MEDIUM, dp(14), 0.02f, x + dp(250), y + dp(6), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            g.text(thousands(o.altFt) + " ft  ·  " + String.format(Locale.US, "%.1f mi", o.miles), Gfx.TEXT_REGULAR, dp(13), 0,
                    right, y + dp(6), Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
            y += dp(26);
        }
        g.text("Aircraft from " + (sky.source.isEmpty() ? "adsb.fi" : sky.source) + ", routes from adsbdb and hexdb.io", Gfx.TEXT_REGULAR, dp(12), 0, x, b - dp(18),
                Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
    }

    // ---- Plans: shared calendars, countdowns, the list ----------------------------------------

    /** Each calendar's colour, by its place in the list. */
    static final int[] CAL_COLORS = {0xFFEC944C, 0xFF6EC1FF, 0xFFB79CFF, 0xFF7FD49B, 0xFFF4D06F, 0xFFFF8FA8};

    static int calColor(int i) {
        return CAL_COLORS[Math.max(0, i) % CAL_COLORS.length];
    }

    /** Whether the Plans card has anything to show. */
    boolean hasPlans() {
        return !events.isEmpty() || !countdowns.isEmpty() || !feeds.isEmpty();
    }

    /** The next thing on the calendars that has not ended, or null. */
    Ical.Event nextEvent() {
        for (Ical.Event e : events) if (e.end > now) return e;
        return null;
    }

    /** "7:30 PM", "Today", "Tomorrow 9 AM", "Thu 7:30 PM". */
    String when(Ical.Event e, boolean withDay) {
        String day = dayName(e.start);
        if (e.allDay) return withDay ? day : "All day";
        String time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(new Date(e.start));
        if (!withDay || "Today".equals(day)) return time;
        return day + " " + time;
    }

    private String dayName(long t) {
        long today = Sun.startOfDay(now, zone);
        long day = Sun.startOfDay(t, zone);
        long diff = Math.round((day - today) / 86400000.0);
        if (diff <= 0) return "Today";
        if (diff == 1) return "Tomorrow";
        SimpleDateFormat f = new SimpleDateFormat(diff < 7 ? "EEEE" : "EEE, MMM d", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(t));
    }

    /** The nearest countdown still to come, or null. */
    Plans.Countdown nextCountdown() {
        Plans.Countdown best = null;
        int bestDays = Integer.MAX_VALUE;
        for (Plans.Countdown c : countdowns) {
            int d = Plans.daysTo(c.date, now, zone);
            if (d >= 0 && d < bestDays) {
                bestDays = d;
                best = c;
            }
        }
        return best;
    }

    private float compactPlans(Gfx g, float x, float cy, float a, boolean draw) {
        float x0 = x;
        Ical.Event e = nextEvent();
        if (draw) Icons.calendar(g, x + dp(13), cy, dp(26), Tone.text(Tone.PRIMARY, a), e == null ? Tone.ACCENT : calColor(e.calendar));
        x += dp(36);
        float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(17)) / 2;
        if (e != null) {
            String when = when(e, true);
            if (draw) g.text(when, Gfx.TEXT_MEDIUM, dp(15), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            x += g.measure(when, Gfx.TEXT_MEDIUM, dp(15), 0) + dp(8);
            String title = Text.fit(g, e.title.isEmpty() ? "Busy" : e.title, Gfx.TEXT_SEMIBOLD, dp(17), dp(150));
            if (draw) g.text(title, Gfx.TEXT_SEMIBOLD, dp(17), 0, x, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            return x + g.measure(title, Gfx.TEXT_SEMIBOLD, dp(17), 0) - x0;
        }
        Plans.Countdown c = nextCountdown();
        if (c != null) {
            int days = Plans.daysTo(c.date, now, zone);
            String n = days == 0 ? "Today" : String.valueOf(days);
            if (draw) g.text(n, Gfx.SERIF, dp(22), 0, x, base + dp(1), Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            x += g.measure(n, Gfx.SERIF, dp(22), 0) + dp(7);
            String rest = Text.fit(g, (days == 0 ? "" : days == 1 ? "day · " : "days · ") + c.title, Gfx.TEXT_MEDIUM, dp(15), dp(200));
            if (draw) g.text(rest, Gfx.TEXT_MEDIUM, dp(15), 0, x, base, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            return x + g.measure(rest, Gfx.TEXT_MEDIUM, dp(15), 0) - x0;
        }
        if (draw) g.text("Nothing planned", Gfx.TEXT_MEDIUM, dp(15), 0, x, base, Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        return x + g.measure("Nothing planned", Gfx.TEXT_MEDIUM, dp(15), 0) - x0;
    }

    private void plansCard(Gfx g, float l, float t, float r, float a) {
        float x = l + dp(22), right = r - dp(22);
        int today = 0;
        for (Ical.Event e : events) if (e.end > now && "Today".equals(dayName(e.start))) today++;
        meta(g, today == 0 ? "Plans" : "Today  \u00B7  " + today + (today == 1 ? " event" : " events"), x, t + dp(30), a);
        float y = t + dp(64);
        int shown = 0;
        for (Ical.Event e : events) {
            if (e.end <= now || shown >= 3) continue;
            g.fillCircle(x + dp(4), y - dp(5), dp(4), Pixels.alpha(calColor(e.calendar), a));
            String when = when(e, true);
            g.text(when, Gfx.MONO, dp(12.5f), 0.02f, x + dp(16), y, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            float tx = x + dp(16) + Math.max(dp(92), g.measure(when, Gfx.MONO, dp(12.5f), 0.02f) + dp(10));
            g.text(Text.fit(g, e.title, Gfx.TEXT_MEDIUM, dp(15.5f), right - tx), Gfx.TEXT_MEDIUM, dp(15.5f), 0, tx, y,
                    Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            y += dp(30);
            shown++;
        }
        if (shown == 0) {
            g.text(feeds.isEmpty() ? "Add your calendars from your phone" : "Nothing on the calendars", Gfx.TEXT_MEDIUM, dp(15), 0, x,
                    y, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            y += dp(30);
        }
        Plans.Countdown c = nextCountdown();
        if (c != null) {
            int days = Plans.daysTo(c.date, now, zone);
            String line = days == 0 ? c.title + " is today" : days + (days == 1 ? " day to " : " days to ") + c.title;
            g.text(Text.fit(g, line, Gfx.SERIF_ITALIC, dp(19), right - x), Gfx.SERIF_ITALIC, dp(19), 0, x, t + dp(172),
                    Pixels.alpha(Tone.ROSE, a), Gfx.LEFT, 0);
        }
    }

    private void plansPanel(Gfx g, float l, float t, float r, float b, float a) {
        float x = l + dp(32), split = l + dp(440), right = r - dp(32);
        title(g, "Plans", feeds.isEmpty() ? "Shared calendars appear here once added from your phone"
                : "From " + joined(feeds), x, t, a);
        float y = t + dp(108);
        String lastDay = "";
        int rows = 0;
        for (Ical.Event e : events) {
            if (e.end <= now) continue;
            String day = dayName(e.start);
            if (!day.equals(lastDay)) {
                if (rows >= 9) break;
                if (!lastDay.isEmpty()) y += dp(6);
                meta(g, day, x, y, a);
                y += dp(26);
                lastDay = day;
            }
            if (rows >= 9 || y > b - dp(40)) break;
            g.fillRoundRect(x, y - dp(14), x + dp(4), y + dp(4), dp(2), Pixels.alpha(calColor(e.calendar), a));
            String when = e.allDay ? "All day" : new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(new Date(e.start));
            g.text(when, Gfx.MONO, dp(13), 0.02f, x + dp(14), y, Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            float tx = x + dp(110);
            g.text(Text.fit(g, e.title, Gfx.TEXT_MEDIUM, dp(16), split - tx - dp(24)), Gfx.TEXT_MEDIUM, dp(16), 0, tx, y,
                    Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
            y += dp(30);
            rows++;
        }
        if (rows == 0) g.text("Nothing in the next two weeks", Gfx.SERIF_ITALIC, dp(20), 0, x, y + dp(10), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        g.fillRect(split - dp(6), t + dp(100), split - dp(5), b - dp(32), Tone.hairline(a));
        float cx = split + dp(18), cy = t + dp(108);
        meta(g, "Counting down", cx, cy, a);
        cy += dp(14);
        int cds = 0;
        for (Plans.Countdown c : countdowns) {
            int days = Plans.daysTo(c.date, now, zone);
            if (days < 0) continue;
            cy += dp(44);
            String n = days == 0 ? "Today" : String.valueOf(days);
            g.text(n, Gfx.SERIF_LIGHT, dp(40), -0.01f, cx, cy, Pixels.alpha(cds == 0 ? Tone.ROSE : Tone.TEXT, a), Gfx.LEFT, 0);
            float nw = g.measure(n, Gfx.SERIF_LIGHT, dp(40), -0.01f) + dp(10);
            g.text(Text.fit(g, (days == 0 ? "" : days == 1 ? "day  " : "days  ") + c.title, Gfx.TEXT_MEDIUM, dp(15), right - cx - nw),
                    Gfx.TEXT_MEDIUM, dp(15), 0, cx + nw, cy - dp(4), Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
            if (++cds >= 3) break;
        }
        if (cds == 0) {
            cy += dp(34);
            g.text("Add a date from your phone", Gfx.TEXT_MEDIUM, dp(14), 0, cx, cy, Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        }
        cy += dp(36);
        meta(g, "Our list", cx, cy, a);
        cy += dp(8);
        int shown = 0;
        for (Plans.Item i : items) {
            if (cy > b - dp(44)) break;
            cy += dp(28);
            Icons.box(g, cx + dp(8), cy - dp(5), dp(15), i.done, Tone.text(i.done ? Tone.TERTIARY : Tone.SECONDARY, a));
            g.text(Text.fit(g, i.text, Gfx.TEXT_MEDIUM, dp(15), right - cx - dp(28)), Gfx.TEXT_MEDIUM, dp(15), 0, cx + dp(24), cy,
                    Tone.text(i.done ? Tone.TERTIARY : Tone.PRIMARY, a), Gfx.LEFT, 0);
            shown++;
        }
        if (shown == 0) g.text("Empty. Add things from your phone", Gfx.TEXT_MEDIUM, dp(14), 0, cx, cy + dp(28), Tone.text(Tone.TERTIARY, a),
                Gfx.LEFT, 0);
    }

    private static String joined(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size() && i < 4; i++) {
            if (i > 0) b.append(i == names.size() - 1 ? " and " : ", ");
            b.append(names.get(i));
        }
        return b.toString();
    }

    // ---- Now Playing ----------------------------------------------------------------------------

    private float compactMusic(Gfx g, float x, float cy, float a, boolean draw) {
        float x0 = x;
        Model.Media m = media;
        float s = dp(34);
        if (draw) art(g, m, x, cy - s / 2, s, dp(8), a);
        x += s + dp(10);
        float base = cy + cap(g, Gfx.TEXT_SEMIBOLD, dp(16)) / 2;
        String title = Text.fit(g, m.title.isEmpty() ? m.app : m.title, Gfx.TEXT_SEMIBOLD, dp(16), dp(220));
        if (draw) g.text(title, Gfx.TEXT_SEMIBOLD, dp(16), 0, x, base, Tone.text(Tone.PRIMARY, a), Gfx.LEFT, 0);
        x += g.measure(title, Gfx.TEXT_SEMIBOLD, dp(16), 0) + dp(10);
        if (draw) Icons.bars(g, x + dp(8), cy, dp(16), still || !m.playing ? -1 : time, Pixels.alpha(Tone.ACCENT, a));
        return x + dp(16) - x0;
    }

    /** Album art in a rounded square, or a glyph on a tile of the accent colour. */
    private void art(Gfx g, Model.Media m, float l, float t, float s, float rad, float a) {
        if (m.art != null) {
            g.fillRoundRectImage(m.art, l, t, l + s, t + s, l, t, l + s, t + s, rad, Math.round(255 * a));
            g.strokeRoundRect(l + dp(0.5f), t + dp(0.5f), l + s - dp(0.5f), t + s - dp(0.5f), rad, dp(1), Tone.hairline(a));
        } else {
            g.fillRoundRect(l, t, l + s, t + s, rad, Pixels.alpha(Tone.ACCENT, 0.85f * a));
            Icons.note(g, l + s / 2, t + s / 2, s * 0.5f, Pixels.alpha(0xFFFFFFFF, a));
        }
    }

    private void musicCard(Gfx g, float l, float t, float r, float b, float a) {
        Model.Media m = media;
        float x = l + dp(22);
        meta(g, m.app.isEmpty() ? "Now playing" : m.app, x, t + dp(30), a);
        float s = dp(112);
        art(g, m, x, t + dp(48), s, dp(14), a);
        float tx = x + s + dp(18), right = r - dp(22);
        Pop.lines(g, m.title.isEmpty() ? "Nothing playing" : m.title, Gfx.SERIF, dp(22), tx, t + dp(76), right - tx, dp(26), 2,
                Tone.text(Tone.PRIMARY, a));
        g.text(Text.fit(g, m.artist, Gfx.TEXT_MEDIUM, dp(15), right - tx), Gfx.TEXT_MEDIUM, dp(15), 0, tx, t + dp(134),
                Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        progress(g, m, tx, t + dp(152), right, a);
    }

    private void progress(Gfx g, Model.Media m, float l, float y, float r, float a) {
        if (m.duration <= 0) return;
        long pos = m.positionNow(uptime);
        float f = Math.max(0, Math.min(1, pos / (float) m.duration));
        g.fillRoundRect(l, y, r, y + dp(4), dp(2), Tone.text(0.18f, a));
        g.fillRoundRect(l, y, l + (r - l) * f, y + dp(4), dp(2), Tone.text(Tone.PRIMARY, a));
        g.text(mmss(pos), Gfx.MONO, dp(11.5f), 0.02f, l, y + dp(22), Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        g.text("-" + mmss(m.duration - pos), Gfx.MONO, dp(11.5f), 0.02f, r, y + dp(22), Tone.text(Tone.TERTIARY, a), Gfx.RIGHT, 0);
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void musicPanel(Gfx g, float l, float t, float r, float b, float a, float button) {
        Model.Media m = media;
        float s = b - t - dp(64), x = l + dp(32), y = t + dp(32);
        art(g, m, x, y, s, dp(20), a);
        float tx = x + s + dp(32), right = r - dp(32);
        meta(g, (m.playing ? "Playing on " : "Paused on ") + (m.app.isEmpty() ? "the TV" : m.app), tx, y + dp(18), a);
        Pop.lines(g, m.title.isEmpty() ? "Nothing playing" : m.title, Gfx.SERIF, dp(36), tx, y + dp(70), right - tx, dp(40), 2,
                Tone.text(Tone.PRIMARY, a));
        g.text(Text.fit(g, m.artist, Gfx.TEXT_MEDIUM, dp(18), right - tx), Gfx.TEXT_MEDIUM, dp(18), 0, tx, y + dp(150),
                Tone.text(Tone.SECONDARY, a), Gfx.LEFT, 0);
        g.text(Text.fit(g, m.album, Gfx.TEXT_REGULAR, dp(15), right - tx), Gfx.TEXT_REGULAR, dp(15), 0, tx, y + dp(174),
                Tone.text(Tone.TERTIARY, a), Gfx.LEFT, 0);
        progress(g, m, tx, y + dp(196), right, a);
        float cy = b - dp(54), mid = (tx + right) / 2;
        Icons.skip(g, mid - dp(80), cy, dp(26), false, Tone.text(m.canPrev ? Tone.PRIMARY : Tone.TERTIARY, a));
        float lit = Math.max(0, Math.min(1, button));
        g.fillCircle(mid, cy, dp(30), Tone.focus((0.14f + 0.8f * lit) * a));
        Icons.play(g, mid, cy, dp(24), m.playing, Tone.onFocus(lit, a));
        Icons.skip(g, mid + dp(80), cy, dp(26), true, Tone.text(m.canNext ? Tone.PRIMARY : Tone.TERTIARY, a));
        g.text("OK to play or pause  \u00B7  \u23EA \u23E9 to skip", Gfx.TEXT_REGULAR, dp(12.5f), 0, mid, b - dp(14),
                Tone.text(Tone.TERTIARY, a), Gfx.CENTER, 0);
    }
}
