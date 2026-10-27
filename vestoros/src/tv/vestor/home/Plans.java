package tv.vestor.home;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The shared list (groceries, things to do) and the countdowns ("Wedding in 228 days"), both
 * edited from the control center and shown on the TV. Small and bounded: 100 list items of up to
 * 80 characters, 6 countdowns. Platform-free: storage is handed in.
 */
final class Plans {
    interface Store {
        String load();

        void save(String json);
    }

    static final int MAX_ITEMS = 100, MAX_TEXT = 80, MAX_COUNTDOWNS = 6;

    static final class Item {
        String id = "", text = "", by = "";
        boolean done;
        long at;
    }

    static final class Countdown {
        String title = "", date = "";
    }

    private final Store store;
    private final List<Item> items = new ArrayList<>();
    private final List<Countdown> countdowns = new ArrayList<>();
    private int seq;

    Plans(Store store) {
        this.store = store;
        load();
    }

    synchronized Item add(String text, String by, long now) {
        String t = Text.clean(text);
        if (t.isEmpty() || t.length() > MAX_TEXT) throw new IllegalArgumentException("text");
        if (items.size() >= MAX_ITEMS) throw new IllegalArgumentException("list full");
        Item i = new Item();
        i.id = "l" + Long.toString(now, 36) + Integer.toString(++seq % 1296, 36);
        i.text = t;
        i.by = Keys.cleanName(by);
        i.at = now;
        items.add(i);
        save();
        return i;
    }

    synchronized boolean toggle(String id) {
        for (Item i : items) {
            if (i.id.equals(id)) {
                i.done = !i.done;
                save();
                return true;
            }
        }
        return false;
    }

    synchronized boolean remove(String id) {
        for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(id)) {
                it.remove();
                save();
                return true;
            }
        }
        return false;
    }

    /** Clears the done items. */
    synchronized void clearDone() {
        for (Iterator<Item> it = items.iterator(); it.hasNext(); ) if (it.next().done) it.remove();
        save();
    }

    synchronized List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            Item c = new Item();
            c.id = i.id;
            c.text = i.text;
            c.by = i.by;
            c.done = i.done;
            c.at = i.at;
            out.add(c);
        }
        return out;
    }

    /** Replaces the countdowns from a control-center array of {title, date}. */
    synchronized void setCountdowns(List<Object> list) {
        List<Countdown> fresh = new ArrayList<>();
        if (list != null) {
            for (Object o : list) {
                Map<String, Object> m = Json.obj(o);
                String title = Text.clean(Json.str(m, "title", ""));
                String date = Json.str(m, "date", "");
                if (title.isEmpty() || title.length() > 40 || parseDate(date) < 0) throw new IllegalArgumentException("countdown");
                Countdown c = new Countdown();
                c.title = title;
                c.date = date;
                fresh.add(c);
                if (fresh.size() > MAX_COUNTDOWNS) throw new IllegalArgumentException("too many countdowns");
            }
        }
        countdowns.clear();
        countdowns.addAll(fresh);
        save();
    }

    synchronized List<Countdown> countdowns() {
        return new ArrayList<>(countdowns);
    }

    /** Whole days from today to {@code date} in {@code zone}: 0 on the day, negative after. */
    static int daysTo(String date, long now, TimeZone zone) {
        long t = parseDate(date);
        if (t < 0) return Integer.MIN_VALUE;
        Calendar a = Calendar.getInstance(zone, Locale.US), b = Calendar.getInstance(zone, Locale.US);
        a.setTimeInMillis(now);
        Calendar target = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        target.setTimeInMillis(t);
        b.clear();
        b.set(target.get(Calendar.YEAR), target.get(Calendar.MONTH), target.get(Calendar.DAY_OF_MONTH), 12, 0, 0);
        a.set(Calendar.HOUR_OF_DAY, 12);
        a.set(Calendar.MINUTE, 0);
        a.set(Calendar.SECOND, 0);
        a.set(Calendar.MILLISECOND, 0);
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
    }

    /** A yyyy-MM-dd date between 2000 and 2200 as UTC midnight, or -1. */
    static long parseDate(String s) {
        if (s == null || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return -1;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            long t = f.parse(s).getTime();
            int y = Integer.parseInt(s.substring(0, 4));
            return y >= 2000 && y <= 2200 ? t : -1;
        } catch (ParseException | RuntimeException e) {
            return -1;
        }
    }

    synchronized String json(long now, TimeZone zone) {
        Jw w = new Jw().obj().key("list").arr();
        for (Item i : items) w.obj().put("id", i.id).put("text", i.text).put("done", i.done).put("by", i.by).put("at", i.at).end();
        w.endArr().key("countdowns").arr();
        for (Countdown c : countdowns) w.obj().put("title", c.title).put("date", c.date).put("days", daysTo(c.date, now, zone)).end();
        return w.endArr().end().toString();
    }

    private void save() {
        Jw w = new Jw().obj().key("list").arr();
        for (Item i : items) w.obj().put("id", i.id).put("text", i.text).put("done", i.done).put("by", i.by).put("at", i.at).end();
        w.endArr().key("countdowns").arr();
        for (Countdown c : countdowns) w.obj().put("title", c.title).put("date", c.date).end();
        store.save(w.endArr().end().toString());
    }

    private void load() {
        String json = store.load();
        if (json == null || json.isEmpty()) return;
        try {
            Map<String, Object> root = Json.obj(Json.parse(json));
            List<Object> list = Json.arr(root, "list");
            if (list != null) {
                for (Object o : list) {
                    Map<String, Object> m = Json.obj(o);
                    Item i = new Item();
                    i.id = Json.str(m, "id", "");
                    i.text = Text.clean(Json.str(m, "text", ""));
                    i.by = Keys.cleanName(Json.str(m, "by", ""));
                    i.done = Json.bool(m, "done", false);
                    i.at = (long) Json.num(m, "at", 0);
                    if (i.id.matches("[a-z0-9]{2,24}") && !i.text.isEmpty() && i.text.length() <= MAX_TEXT && items.size() < MAX_ITEMS) items.add(i);
                }
            }
            List<Object> cds = Json.arr(root, "countdowns");
            if (cds != null) {
                for (Object o : cds) {
                    Map<String, Object> m = Json.obj(o);
                    Countdown c = new Countdown();
                    c.title = Text.clean(Json.str(m, "title", ""));
                    c.date = Json.str(m, "date", "");
                    if (!c.title.isEmpty() && parseDate(c.date) >= 0 && countdowns.size() < MAX_COUNTDOWNS) countdowns.add(c);
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            items.clear();
            countdowns.clear();
        }
    }
}
