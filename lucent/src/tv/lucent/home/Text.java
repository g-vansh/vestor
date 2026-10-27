package tv.lucent.home;

/**
 * Text from outside Lucent (app names, dock names, airport names) goes through here before it
 * is drawn: control and direction-override characters are removed and the length is capped, so
 * no name can reorder what is on screen or cost real time to lay out.
 */
final class Text {
    private Text() {}

    /** Longest name kept from any feed or app. Far more than any tile or row can show. */
    static final int MAX = 120;

    static String clean(CharSequence in) {
        return clean(in, MAX);
    }

    /**
     * The same cleaning with another cap, for the few texts that may be longer than a name (a
     * note's text keeps up to {@link Notes#MAX_TEXT}). At most {@code 2 * max} characters of the
     * input are ever looked at.
     */
    static String clean(CharSequence in, int max) {
        if (in == null || max <= 0) return "";
        int n = Math.min(in.length(), max * 2);
        StringBuilder sb = new StringBuilder(Math.min(n, max));
        for (int i = 0; i < n && sb.length() < max; i++) {
            char c = in.charAt(i);
            if (c < 0x20 || (c >= 0x7F && c < 0xA0)) {
                // Control characters, line breaks and tabs read as a space.
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                continue;
            }
            // Bidirectional overrides, isolates and marks, and zero-width characters.
            if ((c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
                    || c == 0x061C || c == 0xFEFF) {
                continue;
            }
            sb.append(c);
        }
        // Never end on half of a surrogate pair.
        int len = sb.length();
        if (len > 0 && Character.isHighSurrogate(sb.charAt(len - 1))) sb.setLength(len - 1);
        return sb.toString().trim();
    }

    /**
     * {@code s} cut to fit {@code max} wide with an ellipsis. A binary search over the cut point,
     * so a long string costs a dozen measurements, not one per character.
     */
    static String fit(Gfx g, String s, int face, float size, float max) {
        if (s == null) return "";
        if (s.length() > MAX * 2) s = s.substring(0, MAX * 2);
        if (g.measure(s, face, size, 0) <= max) return s;
        int lo = 1, hi = s.length() - 1, best = 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (g.measure(cut(s, mid) + "…", face, size, 0) <= max) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return cut(s, best).trim() + "…";
    }

    private static String cut(String s, int n) {
        if (n > 0 && n < s.length() && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, Math.max(0, n));
    }
}
