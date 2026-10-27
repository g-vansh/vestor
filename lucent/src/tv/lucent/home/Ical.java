package tv.lucent.home;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TimeZone;

/**
 * Reads an iCalendar feed (RFC 5545), such as a Google Calendar secret iCal address or an iCloud
 * public calendar, into the concrete occurrences that touch a window of time. No libraries.
 *
 * It follows folded lines (unfolded as bytes, so a fold inside a UTF-8 character is mended),
 * quoted and caret-escaped parameters, DTSTART with DTEND or DURATION, all-day dates, UTC times,
 * IANA and common Windows zone names (else X-WR-TIMEZONE, else the device zone), RRULE by day,
 * week, month and year with INTERVAL, COUNT, UNTIL, BYDAY with ordinals, BYMONTHDAY, BYMONTH,
 * BYSETPOS and WKST, then RDATE, EXDATE, RECURRENCE-ID overrides and cancellations. Repeats keep
 * their wall-clock time across daylight saving changes. A time that a spring change skips reads
 * an hour later, and a time that an autumn change repeats means the first of the two.
 *
 * The feed comes from the internet, so every cost is bounded: 4,000,000 bytes, 5,000 events,
 * 2,000 characters a line, 3,000 steps of expansion an event and 300,000 for the whole feed,
 * years 1900 to 2200, INTERVAL 1 to 1000 and COUNT 1 to 10,000. Nothing recurses, and an event
 * that fails in any way is skipped alone. Rules it cannot follow exactly (BYWEEKNO, BYYEARDAY,
 * BYHOUR and the like, or another calendar scale) keep just their first occurrence rather than
 * show wrong ones.
 *
 * Invitations the calendar's owner has not accepted are left out unless the caller asks for
 * them: in a Google secret address for a primary calendar, X-WR-CALNAME is the owner's email
 * address, and an invitation not yet answered (or declined) carries an ATTENDEE line for that
 * address with PARTSTAT=NEEDS-ACTION (or DECLINED). A calendar whose name is not an address
 * keeps every event, since there is no telling who its owner is.
 */
final class Ical {
    private Ical() {}

    /** One occurrence of an event. */
    static final class Event {
        String uid = "", title = "", location = "";
        /**
         * Epoch ms. An all-day event runs from local midnight of its first day to local midnight
         * of the day after its last.
         */
        long start, end;
        boolean allDay;
        /** Which feed it came from, as the caller numbered them. */
        int calendar;
    }

    static final int MAX_BYTES = 4000000, MAX_EVENTS = 5000, MAX_LINE = 2000, MAX_STEPS = 3000;
    /** Expansion steps for a whole feed, so thousands of hostile rules cannot add up to seconds. */
    private static final int MAX_FEED_STEPS = 300000;
    /** EXDATE, RDATE and override dates read for one event, and lines of each kept. */
    private static final int MAX_DATES = 1000, MAX_DATE_LINES = 100;
    /** Longest event kept, in days. Ten years is more than any real one. */
    private static final int MAX_SPAN = 3660;
    private static final int MAX_DEPTH = 32, MIN_YEAR = 1900, MAX_YEAR = 2200;
    private static final long DAY = 86400000L;
    private static final long MIN_MS = days(MIN_YEAR, 1, 1) * DAY - 2 * DAY;
    private static final long MAX_MS = days(MAX_YEAR + 1, 1, 1) * DAY + 2 * DAY;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final int DAILY = 1, WEEKLY = 2, MONTHLY = 3, YEARLY = 4;
    private static final String[] WEEKDAYS = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};

    /** Occurrences overlapping [fromMs, toMs) in {@link #ORDER}, accepted ones only. Never throws. */
    static List<Event> occurrences(String ics, int calendarIndex, long fromMs, long toMs, TimeZone local, int maxEvents) {
        return occurrences(ics, calendarIndex, fromMs, toMs, local, maxEvents, true);
    }

    /**
     * Occurrences overlapping [fromMs, toMs) in {@link #ORDER}. {@code acceptedOnly}: leave out
     * invitations the owner has not accepted (see the class comment). Never throws.
     */
    static List<Event> occurrences(String ics, int calendarIndex, long fromMs, long toMs, TimeZone local, int maxEvents,
            boolean acceptedOnly) {
        if (ics == null) return new ArrayList<>();
        // Cut before encoding, so a huge string is never copied whole.
        String s = ics.length() > MAX_BYTES ? ics.substring(0, MAX_BYTES) : ics;
        return occurrences(s.getBytes(StandardCharsets.UTF_8), calendarIndex, fromMs, toMs, local, maxEvents, acceptedOnly);
    }

    /**
     * Occurrences overlapping [fromMs, toMs) from the raw bytes of a feed, in {@link #ORDER}, at
     * most {@code maxEvents} of them (the earliest), leaving out invitations the owner has not
     * accepted. Never throws on bad input: returns what it could read.
     */
    static List<Event> occurrences(byte[] ics, int calendarIndex, long fromMs, long toMs, TimeZone local, int maxEvents) {
        return occurrences(ics, calendarIndex, fromMs, toMs, local, maxEvents, true);
    }

    /**
     * As above, with {@code acceptedOnly} false keeping invitations not accepted (or declined) by
     * the owner too.
     */
    static List<Event> occurrences(byte[] ics, int calendarIndex, long fromMs, long toMs, TimeZone local, int maxEvents,
            boolean acceptedOnly) {
        Run run = new Run(calendarIndex, Math.max(fromMs, MIN_MS), Math.min(toMs, MAX_MS),
                local == null ? TimeZone.getDefault() : local, maxEvents);
        run.acceptedOnly = acceptedOnly;
        if (ics == null || maxEvents <= 0 || run.from >= run.to || !isCalendar(ics)) return run.result();
        Feed feed = new Feed();
        try {
            read(unfold(ics), feed);
        } catch (RuntimeException e) {
            // Keep the events read before the fault.
        }
        try {
            run.all(feed);
        } catch (RuntimeException e) {
            // Keep the occurrences found before the fault.
        }
        return run.result();
    }

    /**
     * A hex SHA-256 of the feed without its DTSTAMP lines. Google stamps every answer with the
     * time it was made and sends no ETag, so this is what tells a real change from a new stamp.
     */
    static String changeKey(byte[] ics) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
        if (ics != null) {
            int n = Math.min(ics.length, MAX_BYTES), i = 0;
            boolean stamp = false;
            while (i < n) {
                int end = i;
                while (end < n && ics[end] != '\n' && ics[end] != '\r') end++;
                if (end < n && ics[end] == '\r') end++;
                if (end < n && ics[end] == '\n') end++;
                // A line starting with a space or tab continues the one before it.
                boolean more = end > i && (ics[i] == ' ' || ics[i] == '\t');
                if (!more) stamp = isStamp(ics, i, end);
                if (!stamp) md.update(ics, i, end - i);
                i = end;
            }
        }
        byte[] h = md.digest();
        char[] out = new char[h.length * 2];
        String hex = "0123456789abcdef";
        for (int i = 0; i < h.length; i++) {
            out[2 * i] = hex.charAt(h[i] >> 4 & 15);
            out[2 * i + 1] = hex.charAt(h[i] & 15);
        }
        return new String(out);
    }

    private static boolean isStamp(byte[] b, int i, int end) {
        String name = "DTSTAMP";
        if (end - i <= name.length() || !startsWith(b, i, name)) return false;
        byte next = b[i + name.length()];
        return next == ':' || next == ';';
    }

    /** Whether the bytes at {@code i} spell {@code word}, ignoring the case of letters. */
    private static boolean startsWith(byte[] b, int i, String word) {
        if (b.length - i < word.length()) return false;
        for (int k = 0; k < word.length(); k++) {
            int c = b[i + k];
            if (c >= 'a' && c <= 'z') c -= 32;
            if (c != word.charAt(k)) return false;
        }
        return true;
    }

    /**
     * Whether the first line is BEGIN:VCALENDAR, after any byte order mark or blank space. An
     * HTML error page or random bytes are passed over at once instead of decoded and read.
     */
    private static boolean isCalendar(byte[] b) {
        int i = 0, n = Math.min(b.length, 4096);
        if (n >= 3 && b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF) i = 3;
        while (i < n && (b[i] == ' ' || b[i] == '\t' || b[i] == '\r' || b[i] == '\n')) i++;
        return startsWith(b, i, "BEGIN:VCALENDAR");
    }

    // ---- Reading -------------------------------------------------------------------------------

    /** A content line's value with the parameters that matter. */
    private static final class Prop {
        String value = "", tzid, type, partstat;
    }

    /** ATTENDEE addresses kept for one event: only those that did not accept. */
    private static final int MAX_UNANSWERED = 64;

    /** One VEVENT as written, before any time is worked out. */
    private static final class Raw {
        String uid = "", summary, location, status = "", rrule, duration;
        Prop start, end, recurrenceId;
        List<Prop> exdates, rdates;
        /** Lowercase addresses of attendees that have not accepted (NEEDS-ACTION or DECLINED). */
        List<String> unanswered;
        /** Set when the owner was already known as such an attendee while reading. */
        boolean ownerUnanswered;

        boolean cancelled() {
            return status.trim().equalsIgnoreCase("CANCELLED");
        }

        /** Whether {@code owner} (a lowercase address, or null) has not accepted this event. */
        boolean notAcceptedBy(String owner) {
            if (owner == null) return false;
            return ownerUnanswered || unanswered != null && unanswered.contains(owner);
        }

        void attendee(String line, int nameEnd, String owner) {
            Prop p = prop(line, nameEnd);
            if (p == null || p.partstat == null) return;
            if (!p.partstat.equals("NEEDS-ACTION") && !p.partstat.equals("DECLINED")) return;
            String who = address(p.value);
            if (who == null) return;
            if (who.equals(owner)) {
                ownerUnanswered = true;
                return;
            }
            if (unanswered == null) unanswered = new ArrayList<>();
            if (unanswered.size() < MAX_UNANSWERED) unanswered.add(who);
        }

        void set(String name, String line, int nameEnd) {
            switch (name) {
                case "UID":
                case "SUMMARY":
                case "LOCATION":
                case "STATUS":
                case "DTSTART":
                case "DTEND":
                case "DURATION":
                case "RRULE":
                case "EXDATE":
                case "RDATE":
                case "RECURRENCE-ID":
                    break;
                default:
                    return;
            }
            Prop p = prop(line, nameEnd);
            if (p == null) return;
            if (name.equals("EXDATE") || name.equals("RDATE")) {
                boolean ex = name.equals("EXDATE");
                List<Prop> list = ex ? exdates : rdates;
                if (list == null) {
                    list = new ArrayList<>();
                    if (ex) exdates = list;
                    else rdates = list;
                }
                if (list.size() < MAX_DATE_LINES) list.add(p);
                return;
            }
            // The first of a repeated property wins.
            if (name.equals("UID")) {
                if (uid.isEmpty()) uid = cap(p.value.trim(), 256);
            } else if (name.equals("SUMMARY")) {
                if (summary == null) summary = p.value;
            } else if (name.equals("LOCATION")) {
                if (location == null) location = p.value;
            } else if (name.equals("STATUS")) {
                if (status.isEmpty()) status = p.value;
            } else if (name.equals("DTSTART")) {
                if (start == null) start = p;
            } else if (name.equals("DTEND")) {
                if (end == null) end = p;
            } else if (name.equals("DURATION")) {
                if (duration == null) duration = p.value;
            } else if (name.equals("RRULE")) {
                if (rrule == null) rrule = p.value;
            } else if (recurrenceId == null) {
                recurrenceId = p;
            }
        }
    }

    private static final class Feed {
        final List<Raw> events = new ArrayList<>();
        String zone;
        /** The owner's address from X-WR-CALNAME, lowercase, or null when the name is not one. */
        String owner;
    }

    /**
     * A lowercase email address from "mailto:someone@example.com" or a bare address, or null when
     * it does not look like one.
     */
    static String address(String v) {
        if (v == null) return null;
        String a = v.trim();
        if (a.regionMatches(true, 0, "mailto:", 0, 7)) a = a.substring(7).trim();
        if (a.length() < 3 || a.length() > 254) return null;
        int at = a.indexOf('@');
        if (at <= 0 || at != a.lastIndexOf('@') || at == a.length() - 1) return null;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c <= ' ' || c == ':' || c == ';' || c == ',' || c == '"' || c == '<' || c == '>') return null;
        }
        return a.toLowerCase(Locale.US);
    }

    /**
     * Joins folded lines (a line break and one space or tab) as bytes, then decodes UTF-8.
     * Google folds at 75 bytes, which can split a character in two.
     */
    static String unfold(byte[] in) {
        int n = Math.min(in.length, MAX_BYTES), i = 0, o = 0;
        byte[] out = new byte[n];
        if (n >= 3 && in[0] == (byte) 0xEF && in[1] == (byte) 0xBB && in[2] == (byte) 0xBF) i = 3;
        while (i < n) {
            byte b = in[i];
            if (b == '\r' && i + 2 < n && in[i + 1] == '\n' && (in[i + 2] == ' ' || in[i + 2] == '\t')) {
                i += 3;
            } else if (b == '\n' && i + 1 < n && (in[i + 1] == ' ' || in[i + 1] == '\t')) {
                i += 2;
            } else {
                out[o++] = b;
                i++;
            }
        }
        return new String(out, 0, o, StandardCharsets.UTF_8);
    }

    /**
     * Walks the unfolded lines, keeping track of which component each is in, so the DTSTART of a
     * time zone rule or the SUMMARY of an alarm never lands on an event.
     */
    private static void read(String text, Feed feed) {
        int n = text.length(), i = 0, depth = 0, seen = 0, at = 0;
        boolean ownerRead = false;
        String[] stack = new String[MAX_DEPTH];
        Raw cur = null;
        while (i < n) {
            int end = i;
            while (end < n) {
                char c = text.charAt(end);
                if (c == '\n' || c == '\r') break;
                end++;
            }
            String line = text.substring(i, Math.min(end, i + MAX_LINE));
            i = end + 1;
            if (end + 1 < n && text.charAt(end) == '\r' && text.charAt(end + 1) == '\n') i++;
            int nameEnd = 0;
            while (nameEnd < line.length() && isNameChar(line.charAt(nameEnd))) nameEnd++;
            if (nameEnd == 0 || nameEnd >= line.length()) continue;
            char sep = line.charAt(nameEnd);
            if (sep != ':' && sep != ';') continue;
            String name = line.substring(0, nameEnd).toUpperCase(Locale.US);
            if (name.equals("BEGIN") || name.equals("END")) {
                Prop p = prop(line, nameEnd);
                if (p == null) continue;
                String what = p.value.trim().toUpperCase(Locale.US);
                if (name.equals("BEGIN")) {
                    if (depth < MAX_DEPTH) stack[depth] = what;
                    depth++;
                    if (what.equals("VEVENT")) {
                        if (++seen > MAX_EVENTS) return;
                        if (cur == null && depth <= MAX_DEPTH) {
                            cur = new Raw();
                            at = depth;
                        }
                    }
                    continue;
                }
                if (depth == 0) continue;
                if (depth > MAX_DEPTH) {
                    // Deeper than names are kept, so close whatever is open.
                    depth--;
                } else {
                    // Close up to the matching BEGIN, which also closes any left open inside it.
                    int k = depth - 1;
                    while (k >= 0 && !stack[k].equals(what)) k--;
                    if (k < 0) continue;
                    depth = k;
                }
                if (cur != null && depth < at) {
                    if (cur.start != null) feed.events.add(cur);
                    cur = null;
                }
            } else if (cur == null) {
                if (name.equals("X-WR-TIMEZONE") && feed.zone == null && depth <= 1) {
                    Prop p = prop(line, nameEnd);
                    if (p != null) feed.zone = p.value.trim();
                } else if (name.equals("X-WR-CALNAME") && !ownerRead && depth <= 1) {
                    // The first calendar name decides, an address or not.
                    ownerRead = true;
                    Prop p = prop(line, nameEnd);
                    if (p != null) feed.owner = address(unescape(p.value));
                }
            } else if (depth == at) {
                if (name.equals("ATTENDEE")) cur.attendee(line, nameEnd, feed.owner);
                else cur.set(name, line, nameEnd);
            }
        }
        // An event still open at the end was cut off, so it is left out.
    }

    private static boolean isNameChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-';
    }

    /**
     * Splits a content line at the first colon outside double quotes, keeping TZID and VALUE from
     * the parameters. Null when there is no such colon.
     */
    private static Prop prop(String line, int nameEnd) {
        int n = line.length(), colon = -1;
        boolean quoted = false;
        for (int i = nameEnd; i < n; i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) {
                colon = i;
                break;
            }
        }
        if (colon < 0) return null;
        Prop p = new Prop();
        p.value = line.substring(colon + 1);
        int i = nameEnd;
        while (i < colon) {
            int start = i + 1, end = start;
            boolean q = false;
            while (end < colon) {
                char c = line.charAt(end);
                if (c == '"') q = !q;
                else if (c == ';' && !q) break;
                end++;
            }
            param(p, line, start, end);
            i = end;
        }
        return p;
    }

    private static void param(Prop p, String line, int start, int end) {
        int eq = line.indexOf('=', start);
        if (eq < 0 || eq >= end) return;
        String key = line.substring(start, eq).trim().toUpperCase(Locale.US);
        boolean tz = key.equals("TZID"), part = key.equals("PARTSTAT");
        if (!tz && !part && !key.equals("VALUE")) return;
        String v = line.substring(eq + 1, end).trim();
        if (v.startsWith("\"")) {
            int close = v.indexOf('"', 1);
            v = close < 0 ? v.substring(1) : v.substring(1, close);
        } else {
            int comma = v.indexOf(',');
            if (comma >= 0) v = v.substring(0, comma);
        }
        v = caret(v).trim();
        if (tz) p.tzid = v;
        else if (part) p.partstat = v.toUpperCase(Locale.US);
        else p.type = v.toUpperCase(Locale.US);
    }

    /** RFC 6868 escapes in a parameter value: ^n a line break, ^^ a caret, ^' a double quote. */
    private static String caret(String v) {
        if (v.indexOf('^') < 0) return v;
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            char next = i + 1 < v.length() ? v.charAt(i + 1) : 0;
            if (c == '^' && (next == 'n' || next == 'N' || next == '^' || next == '\'')) {
                b.append(next == '^' ? '^' : next == '\'' ? '"' : '\n');
                i++;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** TEXT escapes: \n and \N a line break, and a backslash before anything else keeps it. */
    static String unescape(String v) {
        if (v == null) return "";
        if (v.indexOf('\\') < 0) return v;
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char e = v.charAt(++i);
                b.append(e == 'n' || e == 'N' ? '\n' : e);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Splits on one character, keeping at most {@code max} pieces, with no regular expression. */
    private static List<String> split(String s, char sep, int max) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (out.size() < max) {
            int j = s.indexOf(sep, i);
            if (j < 0) {
                out.add(s.substring(i));
                break;
            }
            out.add(s.substring(i, j));
            i = j + 1;
        }
        return out;
    }

    // ---- Dates and times -----------------------------------------------------------------------

    /** A DATE or DATE-TIME value as written. */
    private static final class Stamp {
        int y, m, d, h, mi, s;
        boolean date, utc;
        String tzid;

        int day() {
            return days(y, m, d);
        }

        long tod() {
            return h * 3600000L + mi * 60000L + s * 1000L;
        }

        boolean inYears() {
            return y >= MIN_YEAR && y <= MAX_YEAR;
        }
    }

    private static Stamp stamp(Prop p) {
        return p == null ? null : stamp(p.value, p.tzid, p.type);
    }

    /** Parses 20261027, 20261027T090000 or 20261027T130000Z (seconds optional). Null if bad. */
    private static Stamp stamp(String raw, String tzid, String type) {
        if (raw == null) return null;
        String v = raw.trim();
        int slash = v.indexOf('/');
        // A PERIOD value starts with its start time.
        if (slash >= 0) v = v.substring(0, slash);
        if (v.length() < 8) return null;
        int y = digits(v, 0, 4), m = digits(v, 4, 2), d = digits(v, 6, 2);
        if (y < 1 || m < 1 || m > 12 || d < 1 || d > dim(y, m)) return null;
        Stamp s = new Stamp();
        s.y = y;
        s.m = m;
        s.d = d;
        s.tzid = tzid;
        s.date = "DATE".equals(type) || v.length() == 8;
        if (s.date) return s;
        if (v.length() < 13 || (v.charAt(8) != 'T' && v.charAt(8) != 't')) return null;
        int h = digits(v, 9, 2), mi = digits(v, 11, 2), sec = 0, k = 13;
        if (v.length() >= 15 && v.charAt(13) >= '0' && v.charAt(13) <= '9') {
            sec = digits(v, 13, 2);
            k = 15;
        }
        if (h < 0 || h > 23 || mi < 0 || mi > 59 || sec < 0 || sec > 60) return null;
        s.h = h;
        s.mi = mi;
        s.s = Math.min(sec, 59);
        s.utc = k < v.length() && (v.charAt(k) == 'Z' || v.charAt(k) == 'z');
        if (s.utc) s.tzid = null;
        return s;
    }

    private static int digits(String v, int at, int len) {
        if (at + len > v.length()) return -1;
        int x = 0;
        for (int i = at; i < at + len; i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') return -1;
            x = x * 10 + (c - '0');
        }
        return x;
    }

    /** A signed whole number, saturating far past every limit here. {@code bad} if not one. */
    private static int number(String s, int bad) {
        int n = s.length(), i = 0;
        boolean neg = false;
        if (n > 0 && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            neg = s.charAt(0) == '-';
            i = 1;
        }
        if (i >= n) return bad;
        long v = 0;
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return bad;
            if (v < 100000000L) v = v * 10 + (c - '0');
        }
        return (int) (neg ? -v : v);
    }

    /** DURATION as {nominal days, exact ms}, both clamped, or null if it does not parse. */
    private static long[] duration(String raw) {
        String v = raw.trim().toUpperCase(Locale.US);
        int i = 0, n = v.length();
        boolean neg = false;
        if (i < n && (v.charAt(i) == '+' || v.charAt(i) == '-')) neg = v.charAt(i++) == '-';
        if (i >= n || v.charAt(i++) != 'P') return null;
        long days = 0, ms = 0, x = 0;
        boolean time = false, any = false, digit = false;
        for (; i < n; i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') {
                if (x < 100000000L) x = x * 10 + (c - '0');
                digit = true;
                continue;
            }
            if (c == 'T') {
                time = true;
                continue;
            }
            if (!digit) return null;
            if (c == 'W' && !time) days += x * 7;
            else if (c == 'D' && !time) days += x;
            else if (c == 'H' && time) ms += x * 3600000L;
            else if (c == 'M' && time) ms += x * 60000L;
            else if (c == 'S' && time) ms += x * 1000L;
            else return null;
            x = 0;
            digit = false;
            any = true;
        }
        if (!any || digit) return null;
        if (neg) return new long[] {0, 0};
        return new long[] {Math.min(days, MAX_SPAN), Math.min(ms, MAX_SPAN * DAY)};
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's method). */
    static int days(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** The date of a day number, packed as yyyymmdd. */
    static int civil(int z) {
        z += 719468;
        int era = (z >= 0 ? z : z - 146096) / 146097;
        int doe = z - era * 146097;
        int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        int mp = (5 * doy + 2) / 153;
        int d = doy - (153 * mp + 2) / 5 + 1;
        int m = mp < 10 ? mp + 3 : mp - 9;
        int y = yoe + era * 400 + (m <= 2 ? 1 : 0);
        return y * 10000 + m * 100 + d;
    }

    private static int dim(int y, int m) {
        if (m == 2) return y % 4 == 0 && (y % 100 != 0 || y % 400 == 0) ? 29 : 28;
        return m == 4 || m == 6 || m == 9 || m == 11 ? 30 : 31;
    }

    /** 0 for Monday through 6 for Sunday. */
    private static int weekday(int day) {
        return Math.floorMod(day + 3, 7);
    }

    private static int weekStart(int day, int wkst) {
        return day - Math.floorMod(weekday(day) - wkst, 7);
    }

    /**
     * The instant a wall-clock time names in a zone. A time skipped by a spring change reads with
     * the offset from before it (2:30 becomes 3:30), and a time repeated by an autumn change
     * means the earlier of the two. Uses only the zone's offsets, so it agrees on every platform.
     */
    static long wall(TimeZone z, int day, long tod) {
        long local = day * DAY + tod;
        int early = z.getOffset(local - DAY), late = z.getOffset(local + DAY);
        long a = local - early;
        if (early == late) return a;
        long b = local - late;
        boolean aOk = z.getOffset(a) == early, bOk = z.getOffset(b) == late;
        if (aOk && bOk) return Math.min(a, b);
        if (bOk && !aOk) return b;
        return a;
    }

    /** The day number of an instant in a zone. */
    static int day(TimeZone z, long ms) {
        return (int) Math.floorDiv(ms + z.getOffset(ms), DAY);
    }

    // ---- Time zones ----------------------------------------------------------------------------

    /** Windows names that Outlook and Exchange write as TZID, with their IANA zones. */
    private static final Map<String, String> WINDOWS = new HashMap<>();
    /** First segments of IANA names, to find one inside a legacy path. */
    private static final Set<String> AREAS = new HashSet<>();

    static {
        String[] w = {
            "Eastern Standard Time", "America/New_York",
            "US Eastern Standard Time", "America/Indiana/Indianapolis",
            "Central Standard Time", "America/Chicago",
            "Mountain Standard Time", "America/Denver",
            "US Mountain Standard Time", "America/Phoenix",
            "Pacific Standard Time", "America/Los_Angeles",
            "Alaskan Standard Time", "America/Anchorage",
            "Hawaiian Standard Time", "Pacific/Honolulu",
            "Atlantic Standard Time", "America/Halifax",
            "Newfoundland Standard Time", "America/St_Johns",
            "Canada Central Standard Time", "America/Regina",
            "Central Standard Time (Mexico)", "America/Mexico_City",
            "Mexico Standard Time", "America/Mexico_City",
            "SA Pacific Standard Time", "America/Bogota",
            "E. South America Standard Time", "America/Sao_Paulo",
            "Argentina Standard Time", "America/Argentina/Buenos_Aires",
            "GMT Standard Time", "Europe/London",
            "Greenwich Standard Time", "Atlantic/Reykjavik",
            "W. Europe Standard Time", "Europe/Berlin",
            "Romance Standard Time", "Europe/Paris",
            "Central Europe Standard Time", "Europe/Budapest",
            "Central European Standard Time", "Europe/Warsaw",
            "E. Europe Standard Time", "Europe/Chisinau",
            "FLE Standard Time", "Europe/Kiev",
            "GTB Standard Time", "Europe/Bucharest",
            "Turkey Standard Time", "Europe/Istanbul",
            "Russian Standard Time", "Europe/Moscow",
            "Israel Standard Time", "Asia/Jerusalem",
            "South Africa Standard Time", "Africa/Johannesburg",
            "Egypt Standard Time", "Africa/Cairo",
            "Arabian Standard Time", "Asia/Dubai",
            "Arab Standard Time", "Asia/Riyadh",
            "Iran Standard Time", "Asia/Tehran",
            "Pakistan Standard Time", "Asia/Karachi",
            "India Standard Time", "Asia/Kolkata",
            "Nepal Standard Time", "Asia/Kathmandu",
            "Bangladesh Standard Time", "Asia/Dhaka",
            "SE Asia Standard Time", "Asia/Bangkok",
            "Singapore Standard Time", "Asia/Singapore",
            "China Standard Time", "Asia/Shanghai",
            "Taipei Standard Time", "Asia/Taipei",
            "Tokyo Standard Time", "Asia/Tokyo",
            "Korea Standard Time", "Asia/Seoul",
            "AUS Eastern Standard Time", "Australia/Sydney",
            "E. Australia Standard Time", "Australia/Brisbane",
            "Cen. Australia Standard Time", "Australia/Adelaide",
            "W. Australia Standard Time", "Australia/Perth",
            "New Zealand Standard Time", "Pacific/Auckland",
            "UTC", "UTC",
            "Coordinated Universal Time", "UTC",
            "Eastern Time (US & Canada)", "America/New_York",
            "Central Time (US & Canada)", "America/Chicago",
            "Mountain Time (US & Canada)", "America/Denver",
            "Pacific Time (US & Canada)", "America/Los_Angeles",
            "Arizona", "America/Phoenix",
            "Alaska", "America/Anchorage",
            "Hawaii", "Pacific/Honolulu",
            "Dublin, Edinburgh, Lisbon, London", "Europe/London",
            "Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna", "Europe/Berlin",
            "Brussels, Copenhagen, Madrid, Paris", "Europe/Paris",
            "Chennai, Kolkata, Mumbai, New Delhi", "Asia/Kolkata",
            "Beijing, Chongqing, Hong Kong, Urumqi", "Asia/Shanghai",
            "Osaka, Sapporo, Tokyo", "Asia/Tokyo",
        };
        for (int i = 0; i + 1 < w.length; i += 2) WINDOWS.put(w[i].toLowerCase(Locale.US), w[i + 1]);
        Collections.addAll(AREAS, "Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia",
                "Europe", "Indian", "Pacific", "Etc", "US", "Canada", "Brazil", "Mexico", "Chile");
    }

    private static Map<String, String> lowerIds;

    private static synchronized String caseless(String id) {
        if (lowerIds == null) {
            Map<String, String> m = new HashMap<>();
            for (String s : TimeZone.getAvailableIDs()) m.put(s.toLowerCase(Locale.US), s);
            lowerIds = m;
        }
        return lowerIds.get(id.toLowerCase(Locale.US));
    }

    /** A zone the platform knows by this name, or null (TimeZone answers GMT for any unknown). */
    private static TimeZone known(String id) {
        if (id.isEmpty() || id.length() > 64) return null;
        TimeZone z = TimeZone.getTimeZone(id);
        if (!"GMT".equals(z.getID()) || "GMT".equals(id)) return z;
        String c = caseless(id);
        return c == null ? null : TimeZone.getTimeZone(c);
    }

    /**
     * The zone a TZID names: an IANA name, a Windows name, or an IANA name at the end of a legacy
     * path such as /mozilla.org/20050126_1/America/New_York. Null when none of these.
     */
    static TimeZone resolve(String raw) {
        if (raw == null) return null;
        String id = raw.trim();
        if (id.length() >= 2 && id.charAt(0) == '"' && id.charAt(id.length() - 1) == '"') {
            id = id.substring(1, id.length() - 1).trim();
        }
        if (id.isEmpty() || id.length() > 128) return null;
        TimeZone z = known(id);
        if (z != null) return z;
        String key = id.toLowerCase(Locale.US);
        if (key.startsWith("(utc") || key.startsWith("(gmt")) {
            int close = key.indexOf(')');
            if (close > 0) key = key.substring(close + 1).trim();
        }
        String w = WINDOWS.get(key);
        if (w != null) return known(w);
        if (id.indexOf('/') < 0) return null;
        List<String> parts = new ArrayList<>();
        for (String s : split(id, '/', 16)) {
            if (!s.isEmpty()) parts.add(s);
        }
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            if (!AREAS.contains(parts.get(i))) continue;
            StringBuilder b = new StringBuilder();
            for (int k = i; k < n; k++) b.append(k > i ? "/" : "").append(parts.get(k));
            z = known(b.toString());
            if (z != null) return z;
        }
        if (n >= 2) {
            z = known(parts.get(n - 2) + "/" + parts.get(n - 1));
            if (z != null) return z;
        }
        return null;
    }

    // ---- Recurrence rules ----------------------------------------------------------------------

    private static final class Rule {
        int freq, interval = 1, count, wkst;
        String until;
        boolean byDay;
        int nDays;
        final int[] dayOrd = new int[64], dayWd = new int[64];
        final boolean[] weekdays = new boolean[7];
        int[] monthDays, setPos;
        boolean[] months;

        /** The rule, or null when there is none this reader can follow exactly. */
        static Rule parse(String v) {
            Rule r = new Rule();
            String days = null;
            for (String part : split(v, ';', 32)) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String key = part.substring(0, eq).trim().toUpperCase(Locale.US);
                String val = part.substring(eq + 1).trim().toUpperCase(Locale.US);
                switch (key) {
                    case "FREQ":
                        r.freq = val.equals("DAILY") ? DAILY : val.equals("WEEKLY") ? WEEKLY
                                : val.equals("MONTHLY") ? MONTHLY : val.equals("YEARLY") ? YEARLY : -1;
                        break;
                    case "INTERVAL":
                        r.interval = Math.max(1, Math.min(1000, number(val, 1)));
                        break;
                    case "COUNT": {
                        int c = number(val, Integer.MIN_VALUE);
                        if (c != Integer.MIN_VALUE) r.count = Math.max(1, Math.min(10000, c));
                        break;
                    }
                    case "UNTIL":
                        r.until = val;
                        break;
                    case "BYDAY":
                        days = val;
                        break;
                    case "BYMONTHDAY":
                        r.monthDays = ints(val, 1, 31);
                        if (r.monthDays.length == 0) return null;
                        break;
                    case "BYSETPOS":
                        r.setPos = ints(val, 1, 366);
                        if (r.setPos.length == 0) return null;
                        break;
                    case "BYMONTH": {
                        int[] ms = ints(val, 1, 12);
                        if (ms.length == 0) return null;
                        r.months = new boolean[13];
                        for (int m : ms) {
                            if (m > 0) r.months[m] = true;
                        }
                        break;
                    }
                    case "WKST": {
                        int wd = weekdayCode(val);
                        if (wd >= 0) r.wkst = wd;
                        break;
                    }
                    case "RSCALE":
                        if (!val.equals("GREGORIAN")) return null;
                        break;
                    case "SKIP":
                        if (!val.equals("OMIT")) return null;
                        break;
                    case "BYYEARDAY":
                    case "BYWEEKNO":
                    case "BYHOUR":
                    case "BYMINUTE":
                    case "BYSECOND":
                        return null;
                    default:
                        break;
                }
            }
            if (r.freq <= 0) return null;
            if (days != null && !r.days(days)) return null;
            return r;
        }

        /** Reads BYDAY once FREQ and BYMONTH are known, since they decide what ordinals mean. */
        private boolean days(String list) {
            byDay = true;
            boolean inMonth = freq == MONTHLY || freq == YEARLY && months != null;
            for (String item : split(list, ',', 64)) {
                String t = item.trim();
                if (t.length() < 2 || nDays >= dayOrd.length) continue;
                int wd = weekdayCode(t.substring(t.length() - 2));
                if (wd < 0) continue;
                String pre = t.substring(0, t.length() - 2);
                int ord = 0;
                if (!pre.isEmpty()) {
                    ord = number(pre, 0);
                    if (ord == 0 || Math.abs(ord) > (inMonth ? 5 : 53)) continue;
                }
                // Ordinals mean nothing to a daily or weekly rule.
                if (freq == DAILY || freq == WEEKLY) ord = 0;
                dayOrd[nDays] = ord;
                dayWd[nDays++] = wd;
                weekdays[wd] = true;
            }
            return nDays > 0;
        }

        /** Occurrences in every period when that is the same number, else -1. */
        int perPeriod() {
            if (setPos != null || months != null) return -1;
            if (freq == DAILY) return monthDays == null && !byDay ? 1 : -1;
            if (freq != WEEKLY) return -1;
            if (!byDay) return 1;
            int c = 0;
            for (boolean b : weekdays) {
                if (b) c++;
            }
            return c;
        }

        int applySetPos(int[] buf, int n, boolean[] keep) {
            if (setPos == null || n == 0) return n;
            for (int pos : setPos) {
                int i = pos > 0 ? pos - 1 : n + pos;
                if (i >= 0 && i < n) keep[i] = true;
            }
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    buf[m++] = buf[i];
                    keep[i] = false;
                }
            }
            return m;
        }
    }

    private static int weekdayCode(String s) {
        for (int i = 0; i < 7; i++) {
            if (WEEKDAYS[i].equals(s)) return i;
        }
        return -1;
    }

    /** A list of whole numbers within plus or minus lo..hi, dropping the rest. */
    private static int[] ints(String list, int lo, int hi) {
        List<String> parts = split(list, ',', 64);
        int[] out = new int[parts.size()];
        int n = 0;
        for (String p : parts) {
            int v = number(p.trim(), 0);
            if (Math.abs(v) >= lo && Math.abs(v) <= hi) out[n++] = v;
        }
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    // ---- Expansion -----------------------------------------------------------------------------

    /**
     * Agenda order: by start, all-day events first on the same start, then by title. End, UID
     * and feed break any tie left, so the order never depends on the order of the feed.
     */
    static final Comparator<Event> ORDER = new Comparator<Event>() {
        @Override
        public int compare(Event a, Event b) {
            if (a.start != b.start) return a.start < b.start ? -1 : 1;
            if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
            int c = a.title.compareTo(b.title);
            if (c != 0) return c;
            if (a.end != b.end) return a.end < b.end ? -1 : 1;
            c = a.uid.compareTo(b.uid);
            return c != 0 ? c : Integer.compare(a.calendar, b.calendar);
        }
    };

    /** One call's window, zones, budget and the earliest occurrences found so far. */
    private static final class Run {
        final int calendar, max;
        final long from, to;
        final TimeZone local;
        TimeZone fallback;
        final PriorityQueue<Event> heap = new PriorityQueue<>(16, Collections.reverseOrder(ORDER));
        final Map<String, TimeZone> zones = new HashMap<>();
        /** Original starts of each UID's overrides, worked out once however many series share it. */
        final Map<String, Dates> moved = new HashMap<>();
        final int[] buf = new int[372];
        final boolean[] keep = new boolean[372], mark = new boolean[372];
        int budget = MAX_FEED_STEPS;
        /** Leave out what the feed's owner has not accepted. */
        boolean acceptedOnly = true;

        Run(int calendar, long from, long to, TimeZone local, int max) {
            this.calendar = calendar;
            this.from = from;
            this.to = to;
            this.local = local;
            this.fallback = local;
            this.max = max;
        }

        List<Event> result() {
            List<Event> out = new ArrayList<>(heap);
            Collections.sort(out, ORDER);
            return out;
        }

        /** Keeps only the earliest {@code max}, so memory stays bounded however many match. */
        void add(Event e) {
            heap.add(e);
            if (heap.size() > max) heap.poll();
        }

        boolean overlaps(long s, long e) {
            return s < to && (e > from || e == s && s >= from);
        }

        TimeZone zone(String tzid) {
            if (zones.containsKey(tzid)) return zones.get(tzid);
            TimeZone z = resolve(tzid);
            if (zones.size() < 256) zones.put(tzid, z);
            return z;
        }

        /** The zone a time is in: UTC, its TZID, or for floating and unknown ones {@code floating}. */
        TimeZone zoneOf(Stamp s, TimeZone floating) {
            if (s.utc) return UTC;
            TimeZone z = s.tzid == null ? null : zone(s.tzid);
            return z != null ? z : floating;
        }

        long instant(Stamp s, TimeZone floating) {
            return wall(zoneOf(s, floating), s.day(), s.date ? 0 : s.tod());
        }

        void all(Feed feed) {
            if (feed.zone != null) {
                TimeZone z = zone(feed.zone);
                if (z != null) fallback = z;
            }
            // Overrides are gathered first: they may come before their series in the file.
            Map<String, List<Raw>> overrides = new HashMap<>();
            Set<String> cancelled = new HashSet<>();
            for (Raw r : feed.events) {
                if (r.recurrenceId != null) {
                    if (r.uid.isEmpty()) continue;
                    List<Raw> list = overrides.get(r.uid);
                    if (list == null) {
                        list = new ArrayList<>();
                        overrides.put(r.uid, list);
                    }
                    if (list.size() < MAX_DATES) list.add(r);
                } else if (r.cancelled() && !r.uid.isEmpty()) {
                    cancelled.add(r.uid);
                }
            }
            // Only the owner of a primary calendar is known: its not-accepted invitations are left out
            // here, after the overrides were gathered, so a declined occurrence still takes its date
            // out of the series it moved from.
            String owner = acceptedOnly ? feed.owner : null;
            for (Raw r : feed.events) {
                budget--;
                try {
                    if (r.cancelled() || r.notAcceptedBy(owner)) continue;
                    if (r.recurrenceId == null) {
                        new Series(this, r, overrides.get(r.uid), true).run();
                    } else if (r.uid.isEmpty() || !cancelled.contains(r.uid)) {
                        // An override is an event of its own. Without a series it shows as one.
                        new Series(this, r, null, false).run();
                    }
                } catch (RuntimeException e) {
                    // A broken event is skipped alone.
                }
            }
        }
    }

    /** Dates to leave out: whole days, and instants. */
    private static final class Dates {
        final Set<Integer> days = new HashSet<>();
        final Set<Long> ms = new HashSet<>();

        boolean has(int day, long s) {
            return days.contains(day) || ms.contains(s);
        }
    }

    /** One VEVENT worked out: its zone, its length, and each of its occurrences. */
    private static final class Series {
        final Run run;
        final Raw raw;
        final List<Raw> overrides;
        final boolean recurring;
        boolean allDay;
        TimeZone zone;
        int startDay, sm, sd, startWd, nomDays, spanDays;
        long tod, startMs, exact = -1, nomMs;
        String title, location;
        Dates exdates, moved;
        Set<Long> seen;

        Series(Run run, Raw raw, List<Raw> overrides, boolean recurring) {
            this.run = run;
            this.raw = raw;
            this.overrides = overrides;
            this.recurring = recurring;
        }

        void run() {
            Stamp st = stamp(raw.start);
            if (st == null || !st.inYears()) return;
            allDay = st.date;
            zone = allDay ? run.local : run.zoneOf(st, run.fallback);
            startDay = st.day();
            sm = st.m;
            sd = st.d;
            startWd = weekday(startDay);
            tod = allDay ? 0 : st.tod();
            startMs = wall(zone, startDay, tod);
            length();
            // DTSTART is always the first occurrence, whether or not the rule would make it.
            offer(startDay, startMs);
            if (!recurring) return;
            Rule rule = raw.rrule == null ? null : Rule.parse(raw.rrule);
            if (rule != null) expand(rule);
            if (raw.rdates != null) extra();
        }

        private void length() {
            Stamp en = stamp(raw.end);
            if (en != null && !en.inYears()) en = null;
            long[] du = en == null && raw.duration != null ? duration(raw.duration) : null;
            if (allDay) {
                // DTEND is exclusive: an event on the 31st alone ends on the 1st.
                long n = 1;
                if (en != null) n = en.day() - startDay + (en.date || en.tod() == 0 ? 0 : 1);
                else if (du != null) n = du[0] + (du[1] + DAY - 1) / DAY;
                nomDays = (int) Math.max(1, Math.min(MAX_SPAN, n));
                spanDays = nomDays + 1;
            } else {
                if (en != null) exact = Math.max(0, Math.min(MAX_SPAN * DAY, run.instant(en, zone) - startMs));
                else if (du != null) {
                    nomDays = (int) du[0];
                    nomMs = du[1];
                } else exact = 0;
                spanDays = (int) ((exact >= 0 ? exact : nomDays * DAY + nomMs) / DAY) + 2;
            }
        }

        /**
         * Where an occurrence ends. DTEND gives every occurrence the same exact length, DURATION
         * the same days on the wall clock plus the same exact hours.
         */
        private long end(long s) {
            long e;
            if (allDay) {
                e = wall(run.local, day(run.local, s) + nomDays, 0);
            } else if (exact >= 0) {
                e = s + exact;
            } else if (nomDays == 0) {
                e = s + nomMs;
            } else {
                long wallMs = s + zone.getOffset(s);
                int d = (int) Math.floorDiv(wallMs, DAY);
                e = wall(zone, d + nomDays, wallMs - d * DAY) + nomMs;
            }
            return Math.max(e, s);
        }

        private void offer(int day, long s) {
            long e = end(s);
            if (!run.overlaps(s, e) || excluded(day, s)) return;
            if (seen == null) seen = new HashSet<>();
            if (!seen.add(s)) return;
            if (title == null) {
                title = Text.clean(unescape(raw.summary));
                location = Text.clean(unescape(raw.location));
            }
            Event ev = new Event();
            ev.uid = raw.uid;
            ev.title = title;
            ev.location = location;
            ev.start = s;
            ev.end = e;
            ev.allDay = allDay;
            ev.calendar = run.calendar;
            run.add(ev);
        }

        /**
         * EXDATEs and the original starts of overrides, read only once an occurrence is in the
         * window. Dates match by day, times as instants, so a RECURRENCE-ID written in another
         * zone still finds its occurrence.
         */
        private boolean excluded(int day, long s) {
            if (exdates == null) {
                exdates = new Dates();
                int left = MAX_DATES;
                if (raw.exdates != null) {
                    for (Prop p : raw.exdates) left = dates(p, left, exdates);
                }
                if (overrides != null) {
                    String key = raw.uid + "\n" + zone.getID() + "\n" + allDay;
                    moved = run.moved.get(key);
                    if (moved == null) {
                        moved = new Dates();
                        left = MAX_DATES;
                        for (Raw o : overrides) left = dates(o.recurrenceId, left, moved);
                        run.moved.put(key, moved);
                    }
                }
            }
            return exdates.has(day, s) || moved != null && moved.has(day, s);
        }

        /** Reads a comma list of dates into {@code into}, or as extra occurrences when it is null. */
        private int dates(Prop p, int left, Dates into) {
            for (String v : split(p.value, ',', left)) {
                if (left <= 0 || run.budget <= 0) break;
                left--;
                run.budget--;
                Stamp d = stamp(v, p.tzid, p.type);
                if (d == null || !d.inYears()) continue;
                if (into != null) {
                    if (allDay || d.date) into.days.add(d.day());
                    else into.ms.add(run.instant(d, zone));
                } else if (allDay || d.date) {
                    offer(d.day(), wall(zone, d.day(), tod));
                } else {
                    long s = run.instant(d, zone);
                    offer(day(zone, s), s);
                }
            }
            return left;
        }

        private void extra() {
            int left = MAX_DATES;
            for (Prop p : raw.rdates) {
                if (left <= 0) break;
                left = dates(p, left, null);
            }
        }

        private void expand(Rule r) {
            int lastDay = day(zone, run.to) + 1;
            boolean byInstant = false;
            long untilMs = 0;
            if (r.until != null) {
                Stamp u = stamp(r.until, null, null);
                if (u != null) {
                    if (u.y < MIN_YEAR) return;
                    if (u.y <= MAX_YEAR) {
                        if (allDay || u.date) {
                            lastDay = Math.min(lastDay, u.day());
                        } else {
                            untilMs = wall(u.utc ? UTC : zone, u.day(), u.tod());
                            byInstant = true;
                            lastDay = Math.min(lastDay, day(zone, untilMs) + 1);
                        }
                    }
                }
            }
            int near = day(zone, run.from) - spanDays - 1;
            // A series that ends before the window costs nothing more, as most of an old feed does.
            if (lastDay < near || lastDay <= startDay) return;
            int sy = civil(startDay) / 10000, p, step = r.interval;
            if (r.freq == DAILY) p = startDay;
            else if (r.freq == WEEKLY) {
                p = weekStart(startDay, r.wkst);
                step = 7 * r.interval;
            } else if (r.freq == MONTHLY) p = sy * 12 + sm - 1;
            else p = sy;
            int emitted = 1;
            if (near > startDay) {
                // Jump to the window. With COUNT that needs the number of occurrences skipped,
                // which only a plain daily or weekly rule can say without walking them.
                int ny = civil(near) / 10000, nm = civil(near) / 100 % 100, k;
                if (r.freq == DAILY) k = (near - p) / step;
                else if (r.freq == WEEKLY) k = (weekStart(near, r.wkst) - p) / step;
                else if (r.freq == MONTHLY) k = (ny * 12 + nm - 1 - p) / step;
                else k = (ny - p) / step;
                if (k > 0 && r.count > 0) {
                    int per = r.perPeriod();
                    if (per < 0) {
                        k = 0;
                    } else {
                        long before = 1L + after(r, p) + (long) (k - 1) * per;
                        if (before >= r.count) return;
                        emitted = (int) before;
                    }
                }
                if (k > 0) p += k * step;
            }
            int[] buf = run.buf;
            int steps = 0;
            outer:
            while (steps < MAX_STEPS && run.budget > 0) {
                if (first(r, p) > lastDay) break;
                int n = r.applySetPos(buf, generate(r, p, buf), run.keep);
                steps++;
                run.budget--;
                for (int i = 0; i < n; i++) {
                    if (++steps > MAX_STEPS || --run.budget <= 0) break outer;
                    int day = buf[i];
                    if (day <= startDay) continue;
                    if (day > lastDay) break outer;
                    long s = 0;
                    boolean known = false;
                    if (byInstant && day >= lastDay - 3) {
                        s = wall(zone, day, tod);
                        known = true;
                        if (s > untilMs) break outer;
                    }
                    // Excluded dates still use up COUNT. Dates that do not exist never do.
                    if (r.count > 0 && emitted >= r.count) break outer;
                    emitted++;
                    if (day < near) continue;
                    if (!known) s = wall(zone, day, tod);
                    if (s >= run.to) break outer;
                    offer(day, s);
                }
                p += step;
            }
        }

        /** Occurrences after DTSTART in the first period. */
        private int after(Rule r, int p) {
            int n = generate(r, p, run.buf), c = 0;
            for (int i = 0; i < n; i++) {
                if (run.buf[i] > startDay) c++;
            }
            return c;
        }

        private int first(Rule r, int p) {
            if (r.freq == DAILY || r.freq == WEEKLY) return p;
            if (r.freq == MONTHLY) return days(p / 12, p % 12 + 1, 1);
            return days(p, 1, 1);
        }

        /** The dates a rule makes in one period, ascending. What it leaves out comes from DTSTART. */
        private int generate(Rule r, int p, int[] buf) {
            int n = 0;
            switch (r.freq) {
                case DAILY: {
                    int ymd = civil(p), m = ymd / 100 % 100;
                    if (r.months != null && !r.months[m]) return 0;
                    if (r.monthDays != null && !onMonthDay(r, ymd / 10000, m, ymd % 100)) return 0;
                    if (r.byDay && !r.weekdays[weekday(p)]) return 0;
                    buf[0] = p;
                    return 1;
                }
                case WEEKLY:
                    for (int i = 0; i < 7; i++) {
                        int day = p + i, wd = weekday(day);
                        if (r.byDay ? !r.weekdays[wd] : wd != startWd) continue;
                        if (r.months != null && !r.months[civil(day) / 100 % 100]) continue;
                        buf[n++] = day;
                    }
                    return n;
                case MONTHLY: {
                    int y = p / 12, m = p % 12 + 1;
                    if (r.months != null && !r.months[m]) return 0;
                    return month(r, y, m, true, buf, 0);
                }
                default:
                    if (r.byDay && r.months == null && r.monthDays == null) return yearDays(r, p, buf);
                    for (int m = 1; m <= 12; m++) {
                        boolean want = r.months != null ? r.months[m] : r.monthDays != null || r.byDay || m == sm;
                        if (want) n = month(r, p, m, r.months != null, buf, n);
                    }
                    return n;
            }
        }

        private boolean onMonthDay(Rule r, int y, int m, int d) {
            int len = dim(y, m);
            for (int md : r.monthDays) {
                if ((md > 0 ? md : len + 1 + md) == d) return true;
            }
            return false;
        }

        /** Days of one month, as a bit for each, then written out in order. */
        private int month(Rule r, int y, int m, boolean ordinals, int[] buf, int n) {
            int first = days(y, m, 1), len = dim(y, m), a = 0, b = 0;
            if (r.monthDays != null) {
                for (int md : r.monthDays) {
                    int d = md > 0 ? md : len + 1 + md;
                    if (d >= 1 && d <= len) a |= 1 << (d - 1);
                }
            }
            if (r.byDay) {
                int fw = weekday(first);
                for (int k = 0; k < r.nDays; k++) {
                    int off = Math.floorMod(r.dayWd[k] - fw, 7), o = ordinals ? r.dayOrd[k] : 0;
                    if (o == 0) {
                        for (int i = off; i < len; i += 7) b |= 1 << i;
                    } else if (o > 0) {
                        int i = off + 7 * (o - 1);
                        if (i < len) b |= 1 << i;
                    } else {
                        int i = off + 7 * ((len - 1 - off) / 7) + 7 * (o + 1);
                        if (i >= 0) b |= 1 << i;
                    }
                }
            }
            int mask;
            if (r.monthDays != null && r.byDay) mask = a & b;
            else if (r.monthDays != null) mask = a;
            else if (r.byDay) mask = b;
            else mask = sd <= len ? 1 << (sd - 1) : 0;
            for (int i = 0; i < len; i++) {
                if ((mask >>> i & 1) != 0) buf[n++] = first + i;
            }
            return n;
        }

        /** BYDAY across a whole year, as in every 20th Monday. */
        private int yearDays(Rule r, int y, int[] buf) {
            int jan1 = days(y, 1, 1), len = days(y + 1, 1, 1) - jan1, fw = weekday(jan1), n = 0;
            boolean[] mark = run.mark;
            for (int k = 0; k < r.nDays; k++) {
                int off = Math.floorMod(r.dayWd[k] - fw, 7), o = r.dayOrd[k];
                if (o == 0) {
                    for (int i = off; i < len; i += 7) mark[i] = true;
                } else if (o > 0) {
                    int i = off + 7 * (o - 1);
                    if (i < len) mark[i] = true;
                } else {
                    int i = off + 7 * ((len - 1 - off) / 7) + 7 * (o + 1);
                    if (i >= 0) mark[i] = true;
                }
            }
            for (int i = 0; i < len; i++) {
                if (mark[i]) {
                    buf[n++] = jan1 + i;
                    mark[i] = false;
                }
            }
            return n;
        }
    }
}
