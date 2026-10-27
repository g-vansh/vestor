package tv.lucent.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader with no platform dependency, so the feeds parse the same way on the TV and
 * in the desktop tests. Objects become maps, arrays lists, numbers doubles.
 */
final class Json {
    private final String s;
    private int i, depth, values;
    /** Deeper input than any real feed is refused rather than allowed to overflow the stack. */
    private static final int MAX_DEPTH = 64;
    /**
     * More values, or longer strings, than any real feed holds (the biggest, Bluebikes' station
     * list, has about 15,000 values) are refused, so a hostile answer cannot fill the memory.
     */
    private static final int MAX_VALUES = 250000, MAX_STRING = 16 * 1024;
    /** The most {@link #objectWith} parses after an entry's opening brace. */
    private static final int MAX_ENTRY = 16 * 1024;

    private Json(String s, int start) {
        this.s = s;
        this.i = start;
    }

    /** Parses a whole document. Throws IllegalArgumentException on malformed input. */
    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("no input");
        Json p = new Json(text, 0);
        p.space();
        Object v = p.value();
        p.space();
        if (p.i != text.length()) throw p.error("trailing data");
        return v;
    }

    /**
     * Parses just the object that contains {@code "key":"value"}, which is how a 5-second poll
     * reads one station out of a feed of hundreds without building all of them. Works for feeds
     * whose strings hold no braces (GBFS status does not); returns null when not found.
     *
     * The work is bounded whatever the input: a station's entry is a few hundred bytes, so the
     * search back for its opening brace stops after 8 KB, the parse reads at most 16 KB past that
     * brace, and only the first 64 places the value appears are tried. The first parse that
     * fails ends the search: in a well-formed feed none does.
     */
    static Map<String, Object> objectWith(String text, String key, String value) {
        if (text == null) return null;
        String quoted = "\"" + value + "\"";
        int from = 0;
        for (int tries = 0; tries < 64; tries++) {
            int at = text.indexOf(quoted, from);
            if (at < 0) return null;
            from = at + quoted.length();
            int k = before(text, at);
            if (k < 0 || text.charAt(k) != ':') continue;
            int keyEnd = before(text, k);
            String keyQuoted = "\"" + key + "\"";
            if (keyEnd < keyQuoted.length() - 1 || !text.startsWith(keyQuoted, keyEnd - keyQuoted.length() + 1)) continue;
            int depth = 0, open = -1;
            for (int j = at, stop = Math.max(0, at - 8192); j >= stop; j--) {
                char c = text.charAt(j);
                if (c == '}') depth++;
                else if (c == '{') {
                    if (depth == 0) {
                        open = j;
                        break;
                    }
                    depth--;
                }
            }
            if (open < 0) return null;
            try {
                Json p = new Json(text.substring(open, Math.min(text.length(), open + 1 + MAX_ENTRY)), 0);
                Object v = p.value();
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) v;
                    if (value.equals(m.get(key))) return m;
                }
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return null;
    }

    private static int before(String text, int at) {
        int j = at - 1, stop = Math.max(-1, at - 256);
        while (j > stop && Character.isWhitespace(text.charAt(j))) j--;
        return j;
    }

    // ---- Typed access, forgiving about missing or odd fields --------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static Map<String, Object> obj(Map<String, Object> m, String key) {
        return m == null ? null : obj(m.get(key));
    }

    static List<Object> arr(Map<String, Object> m, String key) {
        return m == null ? null : arr(m.get(key));
    }

    static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof String) return (String) v;
        if (v instanceof Double) {
            double d = (Double) v;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        return fallback;
    }

    static double num(Map<String, Object> m, String key, double fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Double) return (Double) v;
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    static int integer(Map<String, Object> m, String key, int fallback) {
        double d = num(m, key, Double.NaN);
        return Double.isNaN(d) ? fallback : (int) Math.round(d);
    }

    static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) return (Double) v != 0;
        return fallback;
    }

    static boolean has(Map<String, Object> m, String key) {
        return m != null && m.get(key) != null;
    }

    // ---- Parser ------------------------------------------------------------------------------

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + i);
    }

    private void space() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        if (++values > MAX_VALUES) throw error("too many values");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default:
                if (c == '-' || c >= '0' && c <= '9') return number();
                throw error("unexpected '" + c + "'");
        }
    }

    private void word(String w) {
        if (!s.startsWith(w, i)) throw error("expected " + w);
        i += w.length();
    }

    private Map<String, Object> object() {
        if (++depth > MAX_DEPTH) throw error("too deep");
        try {
            return objectBody();
        } finally {
            depth--;
        }
    }

    private Map<String, Object> objectBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            space();
            if (peek() != '"') throw error("expected key");
            String k = string();
            space();
            if (peek() != ':') throw error("expected ':'");
            i++;
            space();
            m.put(k, value());
            space();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        if (++depth > MAX_DEPTH) throw error("too deep");
        try {
            return arrayBody();
        } finally {
            depth--;
        }
    }

    private List<Object> arrayBody() {
        List<Object> a = new ArrayList<>();
        i++;
        space();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            space();
            a.add(value());
            space();
            char c = peek();
            i++;
            if (c == ']') return a;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private char peek() {
        if (i >= s.length()) throw error("unexpected end");
        return s.charAt(i);
    }

    private String string() {
        i++;
        int start = i;
        // Fast path: no escapes.
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                if (i - start > MAX_STRING) throw error("string too long");
                String out = s.substring(start, i);
                i++;
                return out;
            }
            if (c == '\\') break;
            i++;
        }
        StringBuilder b = new StringBuilder(s.substring(start, i));
        while (i < s.length()) {
            if (b.length() > MAX_STRING) throw error("string too long");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n':
                    b.append('\n');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'u':
                    if (i + 4 > s.length()) throw error("bad escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad escape");
                    }
                    i += 4;
                    break;
                default:
                    b.append(e);
                    break;
            }
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
            else break;
        }
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }
}
