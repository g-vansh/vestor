package tv.lucent.home;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Notes from the control center, drawn or typed, kept on the TV: the last 40, newest first.
 *
 * A drawing arrives as strokes, never as a picture, so no image decoder ever sees what a phone
 * sends. Each stroke is a colour from a fixed palette of eight, a width, and points as whole
 * numbers from 0 to 10000 across a 4:3 box. Everything is checked on the way in: at most 200
 * strokes, 20,000 points, 280 characters of text (cleaned like any outside text), and anything
 * out of range is refused, not clamped. Platform-free: storage is handed in.
 */
final class Notes {
    interface Store {
        String load();

        void save(String json);
    }

    static final int MAX_NOTES = 40, MAX_STROKES = 200, MAX_POINTS = 20000, MAX_TEXT = 280;
    static final int COLORS = 8, PAPERS = 4;

    /** The palette, shared with the web page. Colour 0 is ink: light on dark paper, dark on light. */
    static final int[] INK = {0xFFF5F1EA, 0xFFFF6B8B, 0xFFEC944C, 0xFFF4D06F, 0xFF7FD49B, 0xFF6EC1FF, 0xFFB79CFF, 0xFFFF5E5E};
    static final int DARK_INK = 0xFF161514;
    static final int[] PAPER = {0xFF15130F, 0xFFF6F3EE, 0xFFF9E3E3, 0xFF151A2D};

    static boolean lightPaper(int paper) {
        return paper == 1 || paper == 2;
    }

    static int ink(int color, int paper) {
        return color == 0 && lightPaper(paper) ? DARK_INK : INK[Math.max(0, Math.min(COLORS - 1, color))];
    }

    static final class Note {
        String id = "", from = "", text = "";
        long at;
        int paper;
        boolean pinned;
        /** The card's header, made once for drawing ("NOTE FROM ALEX · 9:41 PM"), and its clock style. */
        String head;
        boolean headH24;
        /** Each stroke: {colour, width in thousandths of the box width, x0, y0, x1, y1, ...}. */
        final List<int[]> strokes = new ArrayList<>();

        int points() {
            int n = 0;
            for (int[] s : strokes) n += (s.length - 2) / 2;
            return n;
        }

        /** Total drawn length in box units, which paces the replay on the TV. */
        double length() {
            double len = 0;
            for (int[] s : strokes) {
                for (int i = 4; i + 1 < s.length; i += 2) len += Math.hypot(s[i] - s[i - 2], (s[i + 1] - s[i - 1]) * 0.75);
            }
            return len;
        }
    }

    private final Store store;
    private final List<Note> notes = new ArrayList<>();
    private int seq;

    Notes(Store store) {
        this.store = store;
        load();
    }

    /**
     * A note from a control-center body. Throws IllegalArgumentException with a reason when
     * anything is out of bounds.
     */
    static Note parse(Map<String, Object> body, String from, long now) {
        Note n = new Note();
        n.from = Keys.cleanName(from);
        n.at = now;
        String text = Text.clean(Json.str(body, "text", ""));
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("text too long");
        n.text = text;
        int paper = Json.integer(body, "paper", 0);
        if (paper < 0 || paper >= PAPERS) throw new IllegalArgumentException("paper");
        n.paper = paper;
        n.pinned = Json.bool(body, "pin", false);
        List<Object> strokes = Json.arr(body, "strokes");
        int points = 0;
        if (strokes != null) {
            if (strokes.size() > MAX_STROKES) throw new IllegalArgumentException("too many strokes");
            for (Object o : strokes) {
                Map<String, Object> s = Json.obj(o);
                if (s == null) throw new IllegalArgumentException("stroke");
                int c = Json.integer(s, "c", -1), w = Json.integer(s, "w", -1);
                if (c < 0 || c >= COLORS || w < 2 || w > 80) throw new IllegalArgumentException("stroke style");
                List<Object> p = Json.arr(s, "p");
                if (p == null || p.size() < 2 || p.size() % 2 != 0) throw new IllegalArgumentException("points");
                points += p.size() / 2;
                if (points > MAX_POINTS) throw new IllegalArgumentException("too many points");
                int[] out = new int[2 + p.size()];
                out[0] = c;
                out[1] = w;
                for (int i = 0; i < p.size(); i++) {
                    Object v = p.get(i);
                    if (!(v instanceof Double)) throw new IllegalArgumentException("point");
                    double d = (Double) v;
                    if (d < 0 || d > 10000 || d != Math.floor(d)) throw new IllegalArgumentException("point range");
                    out[2 + i] = (int) d;
                }
                n.strokes.add(out);
            }
        }
        if (n.text.isEmpty() && n.strokes.isEmpty()) throw new IllegalArgumentException("empty note");
        return n;
    }

    /** Adds a note, giving it an id. Pinning it unpins the others: one note sits on the home screen. */
    synchronized Note add(Note n) {
        n.id = "n" + Long.toString(n.at, 36) + Integer.toString(++seq % 1296, 36);
        if (n.pinned) for (Note o : notes) o.pinned = false;
        notes.add(0, n);
        while (notes.size() > MAX_NOTES) notes.remove(notes.size() - 1);
        save();
        return n;
    }

    synchronized boolean remove(String id) {
        for (Iterator<Note> it = notes.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(id)) {
                it.remove();
                save();
                return true;
            }
        }
        return false;
    }

    synchronized boolean pin(String id, boolean pin) {
        boolean found = false;
        for (Note n : notes) {
            if (n.id.equals(id)) {
                n.pinned = pin;
                found = true;
            } else if (pin) {
                n.pinned = false;
            }
        }
        if (found) save();
        return found;
    }

    synchronized Note pinned() {
        for (Note n : notes) if (n.pinned) return n;
        return null;
    }

    synchronized Note latest() {
        return notes.isEmpty() ? null : notes.get(0);
    }

    synchronized List<Note> all() {
        return new ArrayList<>(notes);
    }

    /** The newest {@code max} notes as JSON for the control center. */
    synchronized String json(int max) {
        Jw w = new Jw().arr();
        for (int i = 0; i < notes.size() && i < max; i++) write(w, notes.get(i));
        return w.endArr().toString();
    }

    private static void write(Jw w, Note n) {
        w.obj().put("id", n.id).put("from", n.from).put("at", n.at).put("text", n.text).put("paper", n.paper)
                .put("pinned", n.pinned).key("strokes").arr();
        for (int[] s : n.strokes) {
            w.obj().put("c", s[0]).put("w", s[1]).key("p");
            StringBuilder b = new StringBuilder(s.length * 5).append('[');
            for (int i = 2; i < s.length; i++) {
                if (i > 2) b.append(',');
                b.append(s[i]);
            }
            w.raw(b.append(']').toString()).end();
        }
        w.endArr().end();
    }

    private void save() {
        Jw w = new Jw().obj().key("notes").arr();
        for (Note n : notes) write(w, n);
        store.save(w.endArr().end().toString());
    }

    private void load() {
        String json = store.load();
        if (json == null || json.isEmpty()) return;
        try {
            List<Object> list = Json.arr(Json.obj(Json.parse(json)), "notes");
            if (list == null) return;
            for (Object o : list) {
                Map<String, Object> m = Json.obj(o);
                try {
                    Note n = parse(m, Json.str(m, "from", ""), (long) Json.num(m, "at", 0));
                    n.id = Json.str(m, "id", "");
                    n.pinned = Json.bool(m, "pinned", false);
                    if (!n.id.matches("[a-z0-9]{2,24}")) continue;
                    notes.add(n);
                    if (notes.size() >= MAX_NOTES) break;
                } catch (IllegalArgumentException skip) {
                    // a damaged entry is dropped, the rest load
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            notes.clear();
        }
    }
}
