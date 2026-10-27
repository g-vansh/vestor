package tv.lucent.home;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Shuttles near Westgate, with the same stops and routes as Vestor's clients:
 *
 * - MIT Tech Shuttle (route 63220) and Tech Shuttle 2 (56642, 71674) at Grad Junction West
 *   (stop 180113), and SafeRide (any route named "Saferide...") at W98 @ Vassar St (stop 3813),
 *   all from Passio's GTFS-realtime feed for agency "mit".
 * - MBTA route 85 (Assembly Row - Ruggles) at the Westgate dock, from the MBTA's V3 API, shown as
 *   two lines: 85A towards Assembly Row, boarding at Vassar St @ Audrey St (stop 21772, beside the
 *   Bluebikes dock), and 85B towards BU, boarding at Amesbury St @ Vassar St (stop 22173). After
 *   that stop the bus crosses the BU Bridge to Mountfort St, Kenmore and on to Ruggles. Live
 *   predictions only. When none is up, today's timetable says when the next one is due.
 *
 * BU's Hyatt shuttle, which Vestor shows, stopped with BU's Hyatt housing in fall 2026 (its
 * route is gone from BU's TransLoc feed), so it is not here. MIT routes are also recognised by
 * name, so a renumbered route keeps working.
 */
final class Transit {
    private Transit() {}

    static final String MIT_TRIPS = "https://passio3.com/mit/passioTransit/gtfs/realtime/tripUpdates";
    static final String MIT_STATIC = "https://passio3.com/mit/passioTransit/gtfs/google_transit.zip";
    static final String STOP_GRAD_JUNCTION_W = "180113";
    static final String STOP_W98_VASSAR = "3813";
    /** Tang/Westgate (W84/W85), where the grocery shuttles stop for Westgate. */
    static final String STOP_TANG_WESTGATE = "1013";
    /** Trader Joe-Whole Foods in MIT's Passio feed. */
    static final Set<String> GROCERY = set("64288");
    /**
     * MIT's published Trader Joe's and Whole Foods run (TJ-WF.pdf): it leaves Kendall Square at
     * these minutes past midnight, passes Tang/Westgate on the way, and is at Trader Joe's about
     * 45 minutes later. Service 11 AM to 5 PM on Fridays and the 1st, 3rd and 5th Sundays.
     */
    static final int[] GROCERY_FROM_KENDALL = {660, 720, 783, 846, 909};
    static final Set<String> TECH = set("63220");
    static final Set<String> TECH2 = set("56642", "71674");

    static final String MBTA_ROUTE = "85";
    static final String STOP_TO_ASSEMBLY = "21772", STOP_TO_RUGGLES = "22173";
    private static final String MBTA = "https://api-v3.mbta.com/";
    /** Polled keyless (the MBTA allows 20 a minute, Pace keeps Lucent at 10), or streamed with a key. */
    static final String MBTA_PREDICTIONS = MBTA + "predictions?filter%5Bstop%5D=" + STOP_TO_ASSEMBLY + "," + STOP_TO_RUGGLES
            + "&filter%5Broute%5D=" + MBTA_ROUTE + "&sort=departure_time";

    /** Today's timetable at both stops, fetched once a service day. */
    static String mbtaSchedule(String serviceDate) {
        return MBTA + "schedules?filter%5Bstop%5D=" + STOP_TO_ASSEMBLY + "," + STOP_TO_RUGGLES + "&filter%5Broute%5D="
                + MBTA_ROUTE + "&filter%5Bdate%5D=" + serviceDate + "&sort=departure_time";
    }

    /**
     * Line colours keep Vestor's order (Tech green, NW blue, SafeRide violet), lifted to read at
     * 4.5:1 on dimmed glass; MBTA buses wear the MBTA's own bus yellow. The chip's text carries
     * the meaning, not the colour.
     */
    static final int GREEN = 0xFF4CD97B, AZURE = 0xFF5AB0FF, VIOLET = 0xFFC08CFF, MBTA_YELLOW = 0xFFFFC72C, CORAL = 0xFFFF8A65;

    private static Set<String> set(String... ids) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, ids);
        return s;
    }

    static Model.Line tech() {
        return new Model.Line("tech", "Tech Shuttle", "TECH", "Grad Junction West", GREEN);
    }

    static Model.Line tech2() {
        return new Model.Line("tech2", "Tech Shuttle +NW", "NW", "Grad Junction West", AZURE);
    }

    static Model.Line saferide() {
        return new Model.Line("saferide", "SafeRide", "SAFE", "W98 @ Vassar St", VIOLET);
    }

    static Model.Line grocery() {
        return new Model.Line("grocery", "Trader Joe's and Whole Foods", "TJ", "Tang/Westgate", CORAL);
    }

    /**
     * Whether the grocery shuttle runs on this day: Fridays (not Christmas Day) and the first,
     * third and fifth Sundays of the month.
     */
    static boolean groceryDay(long nowMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"), Locale.US);
        c.setTimeInMillis(nowMs);
        int dow = c.get(Calendar.DAY_OF_WEEK), dom = c.get(Calendar.DAY_OF_MONTH);
        if (dow == Calendar.FRIDAY) return !(c.get(Calendar.MONTH) == Calendar.DECEMBER && dom == 25);
        if (dow != Calendar.SUNDAY) return false;
        int week = (dom - 1) / 7 + 1;
        return week == 1 || week == 3 || week == 5;
    }

    /** Whether to list the grocery shuttle now: on its days from 10 AM until 5 PM, or whenever a bus is tracked. */
    static boolean groceryShown(Model.Line grocery, long nowMs) {
        if (!grocery.minutes.isEmpty()) return true;
        if (!groceryDay(nowMs)) return false;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"), Locale.US);
        c.setTimeInMillis(nowMs);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h >= 10 && h < 17;
    }

    /** 85A: the 85 heading to Assembly Row, from beside the Westgate dock. */
    static Model.Line toAssembly() {
        Model.Line l = new Model.Line("85a", "85A to Assembly Row", "85A", "Vassar St @ Audrey St", MBTA_YELLOW);
        l.toward = "Assembly";
        return l;
    }

    /** 85B: the 85 heading over the BU Bridge to BU, Kenmore and Ruggles. */
    static Model.Line toRuggles() {
        Model.Line l = new Model.Line("85b", "85B to BU and Ruggles", "85B", "Amesbury St @ Vassar St", MBTA_YELLOW);
        l.toward = "BU";
        return l;
    }

    /** Which MIT line a route is: 1 Tech, 2 Tech 2, 3 SafeRide, 4 Trader Joe's and Whole Foods, 0 none of ours. */
    static int kind(String routeId, String routeName) {
        if (TECH.contains(routeId)) return 1;
        if (TECH2.contains(routeId)) return 2;
        if (GROCERY.contains(routeId)) return 4;
        String n = routeName == null ? "" : routeName.toLowerCase(Locale.US).replace(" ", "");
        if (n.startsWith("saferide")) return 3;
        if (n.startsWith("traderjoe")) return 4;
        if (n.startsWith("techshuttle2")) return 2;
        if (n.startsWith("techshuttle")) return 1;
        return 0;
    }

    /** Fills Tech, Tech 2 and SafeRide minutes from realtime updates. */
    static void mit(List<Gtfs.Update> updates, Gtfs.Static st, long nowMs, Model.Line tech, Model.Line tech2,
            Model.Line safe) {
        mit(updates, st, nowMs, tech, tech2, safe, grocery());
    }

    /** The same, and the grocery shuttle's minutes at Tang/Westgate. */
    static void mit(List<Gtfs.Update> updates, Gtfs.Static st, long nowMs, Model.Line tech, Model.Line tech2,
            Model.Line safe, Model.Line grocery) {
        for (Gtfs.Update u : updates) {
            String route = st == null ? u.routeId : st.routeOf(u);
            int k = kind(route, st == null ? "" : st.name(route));
            if (k == 0) continue;
            String want = k == 3 ? STOP_W98_VASSAR : k == 4 ? STOP_TANG_WESTGATE : STOP_GRAD_JUNCTION_W;
            if (!want.equals(u.stopId)) continue;
            int mins = minutes(u.time * 1000L - nowMs);
            if (mins < 0) continue;
            (k == 1 ? tech : k == 2 ? tech2 : k == 4 ? grocery : safe).minutes.add(mins);
        }
        tidy(tech);
        tidy(tech2);
        tidy(safe);
        tidy(grocery);
    }

    /**
     * Fills the two route 85 lines from MBTA predictions (JSON:API). A prediction counts for the
     * Assembly line at stop 21772 heading out (direction 0) and for the Ruggles line at stop 22173
     * heading in (direction 1); skipped and cancelled trips are left out.
     */
    static void mbta(String json, long nowMs, Model.Line assembly, Model.Line ruggles) {
        mbta(Json.arr(Json.obj(Json.parse(json)), "data"), nowMs, assembly, ruggles);
    }

    /** The same from a list of prediction resources, as the live stream keeps them. */
    static void mbta(java.util.Collection<Object> data, long nowMs, Model.Line assembly, Model.Line ruggles) {
        if (data == null) return;
        for (Object o : data) {
            Map<String, Object> p = Json.obj(o);
            Map<String, Object> at = Json.obj(p, "attributes");
            if (at == null) continue;
            String rel = Json.str(at, "schedule_relationship", "");
            if ("SKIPPED".equals(rel) || "CANCELLED".equals(rel) || "NO_DATA".equals(rel)) continue;
            String when = Json.str(at, "departure_time", null);
            if (when == null) when = Json.str(at, "arrival_time", null);
            long t = iso(when);
            if (t <= 0) continue;
            String stop = Json.str(Json.obj(Json.obj(Json.obj(p, "relationships"), "stop"), "data"), "id", "");
            int dir = Json.integer(at, "direction_id", -1);
            Model.Line line = STOP_TO_ASSEMBLY.equals(stop) && dir == 0 ? assembly
                    : STOP_TO_RUGGLES.equals(stop) && dir == 1 ? ruggles : null;
            if (line == null) continue;
            int mins = minutes(t - nowMs);
            if (mins >= 0) line.minutes.add(mins);
        }
        tidy(assembly);
        tidy(ruggles);
    }

    /** Today's departures from a schedules answer: [0] towards Assembly, [1] towards Ruggles. */
    static List<List<Long>> mbtaTimetable(String json) {
        List<List<Long>> out = new ArrayList<>();
        out.add(new ArrayList<Long>());
        out.add(new ArrayList<Long>());
        List<Object> data = Json.arr(Json.obj(Json.parse(json)), "data");
        if (data == null) return out;
        for (Object o : data) {
            Map<String, Object> p = Json.obj(o);
            Map<String, Object> at = Json.obj(p, "attributes");
            String when = Json.str(at, "departure_time", null);
            if (when == null) when = Json.str(at, "arrival_time", null);
            long t = iso(when);
            if (t <= 0) continue;
            String stop = Json.str(Json.obj(Json.obj(Json.obj(p, "relationships"), "stop"), "data"), "id", "");
            int dir = Json.integer(at, "direction_id", -1);
            if (STOP_TO_ASSEMBLY.equals(stop) && dir == 0) out.get(0).add(t);
            else if (STOP_TO_RUGGLES.equals(stop) && dir == 1) out.get(1).add(t);
        }
        Collections.sort(out.get(0));
        Collections.sort(out.get(1));
        return out;
    }

    /** "Next 7:25 AM" from a timetable, or "No more today". */
    static String nextNote(List<Long> times, long nowMs, boolean h24) {
        if (times == null) return "";
        for (Long t : times) {
            if (t > nowMs + 30000) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("America/New_York"));
                return "Next " + f.format(new java.util.Date(t));
            }
        }
        return "No more today";
    }

    /** The MBTA service day in Boston (it runs past midnight until about 3 AM), as yyyy-MM-dd. */
    static String serviceDate(long nowMs) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        return f.format(new java.util.Date(nowMs - 3 * 3600000L));
    }

    /** ISO 8601 with offset, as the MBTA sends it ("2026-10-27T08:08:56-04:00"), to epoch ms. */
    static long iso(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            f.setLenient(false);
            java.util.Date d = f.parse(s);
            return d == null ? 0 : d.getTime();
        } catch (java.text.ParseException | RuntimeException e) {
            return 0;
        }
    }

    /** Minutes to an arrival: -1 once it is past, 0 ("Now") within 30 s, else rounded, half up. */
    static int minutes(long ms) {
        if (ms < 0) return -1;
        if (ms <= 30000) return 0;
        return (int) Math.floor(ms / 60000.0 + 0.5);
    }

    private static void tidy(Model.Line l) {
        Collections.sort(l.minutes);
        List<Integer> unique = new ArrayList<>();
        for (Integer m : l.minutes) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals(m)) unique.add(m);
        }
        l.minutes.clear();
        l.minutes.addAll(unique.subList(0, Math.min(4, unique.size())));
    }

    /**
     * What an MIT line with no tracked bus says, from MIT's published hours (2026): Tech Shuttle
     * weekdays 6:15 AM to 11 PM, +NW weekdays 7 AM to 4 PM, SafeRide fixed routes 6 to 11 PM and
     * on demand after. Hours change in summer and on holidays, so these are hints beside live
     * data, never instead of it. MBTA lines say when the timetable's next bus is due instead.
     */
    static void idleNotes(Model.Transit t, long nowMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"), Locale.US);
        c.setTimeInMillis(nowMs);
        int mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
        for (Model.Line l : t.lines) {
            if (l.id.startsWith("85")) continue;
            l.idle = l.minutes.isEmpty() ? note(l.id, mins, weekend) : "";
        }
    }

    static String note(String id, int mins, boolean weekend) {
        if ("tech".equals(id)) {
            if (weekend) return "Weekdays";
            return mins >= 375 && mins < 1380 ? "No bus tracked" : "From 6:15 AM";
        }
        if ("tech2".equals(id)) {
            if (weekend) return "Weekdays";
            return mins >= 420 && mins < 960 ? "No bus tracked" : "Weekdays 7 to 4";
        }
        if ("grocery".equals(id)) {
            for (int k : GROCERY_FROM_KENDALL) {
                if (k >= mins) return "Leaves Kendall " + clock(k);
            }
            return "Last run left 3:09";
        }
        if (mins >= 1080 && mins < 1380) return "No bus tracked";
        return mins >= 1380 || mins < 240 ? "On demand" : "From 6 PM";
    }

    /** "11:00 AM" or "1:03 PM" for minutes past midnight. */
    static String clock(int mins) {
        int h = mins / 60 % 24, m = mins % 60;
        return (h % 12 == 0 ? 12 : h % 12) + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " AM" : " PM");
    }

    /** MBTA's display rules: "Now" at 30 s or less, minutes below an hour, "1 h 12 m" above. */
    static String lead(int minutes) {
        if (minutes <= 0) return "Now";
        if (minutes < 60) return String.valueOf(minutes);
        return (minutes / 60) + " h " + (minutes % 60) + " m";
    }

    /** Whether {@link #lead} needs " min" after it. */
    static boolean unit(int minutes) {
        return minutes > 0 && minutes < 60;
    }
}
