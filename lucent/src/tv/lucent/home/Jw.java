package tv.lucent.home;

/**
 * A tiny JSON writer for the control center's answers and Lucent's own small files. Strings are
 * escaped completely (quotes, backslashes, every control character, and the line and paragraph
 * separators that would end a JavaScript string), so text from feeds or notes can never break
 * out of its value.
 */
final class Jw {
    private final StringBuilder b = new StringBuilder(256);
    private boolean comma;

    Jw obj() {
        sep();
        b.append('{');
        comma = false;
        return this;
    }

    Jw end() {
        b.append('}');
        comma = true;
        return this;
    }

    Jw arr() {
        sep();
        b.append('[');
        comma = false;
        return this;
    }

    Jw endArr() {
        b.append(']');
        comma = true;
        return this;
    }

    /** A key inside an object; the next call writes its value. */
    Jw key(String k) {
        sep();
        quote(b, k);
        b.append(':');
        comma = false;
        return this;
    }

    Jw val(String s) {
        sep();
        if (s == null) b.append("null");
        else quote(b, s);
        comma = true;
        return this;
    }

    Jw val(long v) {
        sep();
        b.append(v);
        comma = true;
        return this;
    }

    Jw val(double v) {
        sep();
        if (Double.isNaN(v) || Double.isInfinite(v)) b.append("null");
        else b.append(Math.round(v * 1000) / 1000.0);
        comma = true;
        return this;
    }

    Jw val(boolean v) {
        sep();
        b.append(v);
        comma = true;
        return this;
    }

    Jw put(String k, String v) {
        return key(k).val(v);
    }

    Jw put(String k, long v) {
        return key(k).val(v);
    }

    Jw put(String k, double v) {
        return key(k).val(v);
    }

    Jw put(String k, boolean v) {
        return key(k).val(v);
    }

    /** Inserts already-written JSON (from another Jw). */
    Jw raw(String json) {
        sep();
        b.append(json);
        comma = true;
        return this;
    }

    private void sep() {
        if (comma) b.append(',');
    }

    @Override
    public String toString() {
        return b.toString();
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == '<' || c == '>' || c == '&') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
