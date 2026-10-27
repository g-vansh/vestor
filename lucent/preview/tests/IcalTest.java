package tv.lucent.home;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

/**
 * Checks the iCalendar reader against Google and Apple style feeds, the worked examples of
 * RFC 5545 (expected values from python-dateutil), and hostile feeds that must finish quickly.
 */
public final class IcalTest {
    private static int checks, failures;
    private static final TimeZone NY = TimeZone.getTimeZone("America/New_York");
    private static final ZoneId NYZ = ZoneId.of("America/New_York");
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(NYZ);

    public static void main(String[] args) {
        google();
        apple();
        rfc();
        skipping();
        zones();
        dst();
        edges();
        overrides();
        dates();
        text();
        key();
        hostile();
        fuzz(args.length > 0 ? Long.parseLong(args[0]) : 20261027L);
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static long t(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    /** Midnight in New York at the start of a yyyymmdd date. */
    private static long day(String ymd) {
        return LocalDate.parse(ymd, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(NYZ).toInstant().toEpochMilli();
    }

    private static String ny(long ms) {
        return F.format(Instant.ofEpochMilli(ms));
    }

    private static String cal(String... lines) {
        StringBuilder b = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Lucent//Test//EN\r\n");
        for (String l : lines) b.append(l).append("\r\n");
        return b.append("END:VCALENDAR\r\n").toString();
    }

    private static List<Ical.Event> read(String ics, long from, long to) {
        return Ical.occurrences(ics, 0, from, to, NY, 10000);
    }

    private static List<Ical.Event> titled(List<Ical.Event> all, String title) {
        List<Ical.Event> out = new ArrayList<>();
        for (Ical.Event e : all) {
            if (e.title.equals(title)) out.add(e);
        }
        return out;
    }

    private static Ical.Event one(String what, List<Ical.Event> all, String title) {
        List<Ical.Event> l = titled(all, title);
        eq(what + " count", 1, l.size());
        return l.isEmpty() ? new Ical.Event() : l.get(0);
    }

    private static String starts(List<Ical.Event> l) {
        StringBuilder b = new StringBuilder();
        for (Ical.Event e : l) b.append(b.length() > 0 ? ", " : "").append(ny(e.start));
        return b.toString();
    }

    /** All starts when a dozen or fewer, else the count, the first three and the last. */
    private static String summary(List<Ical.Event> l) {
        if (l.size() <= 12) return starts(l);
        return l.size() + ": " + starts(l.subList(0, 3)) + " ... " + ny(l.get(l.size() - 1).start);
    }

    private static String event(String uid, String summary, String... props) {
        StringBuilder b = new StringBuilder("BEGIN:VEVENT\r\nUID:" + uid + "\r\nSUMMARY:" + summary);
        for (String p : props) b.append("\r\n").append(p);
        return b.append("\r\nEND:VEVENT").toString();
    }

    // ---- Google ----------------------------------------------------------------------------------

    /** Shaped like a Google Calendar secret iCal address: every event since forever, UTC singles. */
    static final String GOOGLE = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "PRODID:-//Google Inc//Google Calendar 70.9054//EN",
            "VERSION:2.0",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:Family",
            "X-WR-TIMEZONE:America/New_York",
            "BEGIN:VTIMEZONE",
            "TZID:America/New_York",
            "X-LIC-LOCATION:America/New_York",
            "BEGIN:DAYLIGHT",
            "TZOFFSETFROM:-0500",
            "TZOFFSETTO:-0400",
            "TZNAME:EDT",
            "DTSTART:19700308T020000",
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
            "END:DAYLIGHT",
            "BEGIN:STANDARD",
            "TZOFFSETFROM:-0400",
            "TZOFFSETTO:-0500",
            "TZNAME:EST",
            "DTSTART:19701101T020000",
            "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU",
            "END:STANDARD",
            "END:VTIMEZONE",
            // The moved Wednesday comes before its series, with RECURRENCE-ID in UTC.
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261028T110000",
            "DTEND;TZID=America/New_York:20261028T113000",
            "DTSTAMP:20261027T120000Z",
            "UID:standup-1@google.com",
            "RECURRENCE-ID:20261028T133000Z",
            "CREATED:20260901T150000Z",
            "LAST-MODIFIED:20261020T150000Z",
            "SEQUENCE:3",
            "STATUS:CONFIRMED",
            "SUMMARY:Standup (moved)",
            "TRANSP:OPAQUE",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261026T093000",
            "DTEND;TZID=America/New_York:20261026T094500",
            "RRULE:FREQ=WEEKLY;WKST=SU;UNTIL=20261120T045959Z;BYDAY=MO,WE,FR",
            "EXDATE;TZID=America/New_York:20261106T093000",
            "DTSTAMP:20261027T120000Z",
            "UID:standup-1@google.com",
            "CREATED:20260901T150000Z",
            "DESCRIPTION:Daily sync.\\nJoin: https://meet.google.com/abc-defg-hij\\nOr dial: (US) +1 6",
            " 17-555-0100 PIN: 123456#",
            "LAST-MODIFIED:20261020T150000Z",
            "LOCATION:",
            "SEQUENCE:2",
            "STATUS:CONFIRMED",
            "SUMMARY:Standup",
            "TRANSP:OPAQUE",
            "BEGIN:VALARM",
            "ACTION:EMAIL",
            "DESCRIPTION:This is an event reminder",
            "SUMMARY:Alarm notification",
            "ATTENDEE:mailto:someone@example.com",
            "TRIGGER:-P0DT0H10M0S",
            "END:VALARM",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "DESCRIPTION:This is an event reminder",
            "TRIGGER:-P0DT0H30M0S",
            "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261109T093000",
            "DTEND;TZID=America/New_York:20261109T094500",
            "DTSTAMP:20261027T120000Z",
            "UID:standup-1@google.com",
            "RECURRENCE-ID;TZID=America/New_York:20261109T093000",
            "STATUS:CANCELLED",
            "SUMMARY:Standup",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART:20261029T223000Z",
            "DTEND:20261030T003000Z",
            "DTSTAMP:20261027T120000Z",
            "UID:dinner@google.com",
            "SUMMARY:Dinner\\, drinks\\; and a long title that Google folds because it runs p",
            " ast seventy-five octets",
            "LOCATION:Giulia\\, 1682 Massachusetts Ave\\, Cambridge\\, MA 02138\\, USA",
            "STATUS:CONFIRMED",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:20261031",
            "DTEND;VALUE=DATE:20261101",
            "DTSTAMP:20261027T120000Z",
            "UID:halloween@google.com",
            "SUMMARY:Halloween",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:20261101",
            "DTEND;VALUE=DATE:20261104",
            "DTSTAMP:20261027T120000Z",
            "UID:trip@google.com",
            "SUMMARY:Trip to Montreal",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART:20261030T160000Z",
            "DTEND:20261030T170000Z",
            "DTSTAMP:20261027T120000Z",
            "UID:cancelled@google.com",
            "STATUS:CANCELLED",
            "SUMMARY:Cancelled lunch",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART:20140305T150000Z",
            "DTEND:20140305T160000Z",
            "DTSTAMP:20261027T120000Z",
            "UID:old@google.com",
            "SUMMARY:Old meeting",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20150105T080000",
            "DTEND;TZID=America/New_York:20150105T090000",
            "RRULE:FREQ=DAILY;UNTIL=20160101T000000Z",
            "DTSTAMP:20261027T120000Z",
            "UID:old-series@google.com",
            "SUMMARY:Old daily",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:19901102",
            "DTEND;VALUE=DATE:19901103",
            "RRULE:FREQ=YEARLY",
            "DTSTAMP:20261027T120000Z",
            "UID:birthday@google.com",
            "SUMMARY:Mom's birthday",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20260130T180000",
            "DTEND;TZID=America/New_York:20260130T200000",
            "RRULE:FREQ=MONTHLY;BYDAY=-1FR",
            "DTSTAMP:20261027T120000Z",
            "UID:gamenight@google.com",
            "SUMMARY:Game night",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20260113T190000",
            "DTEND;TZID=America/New_York:20260113T203000",
            "RRULE:FREQ=MONTHLY;BYDAY=2TU",
            "DTSTAMP:20261027T120000Z",
            "UID:council@google.com",
            "SUMMARY:Council meeting",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261029T120000",
            "DURATION:PT1H30M",
            "DTSTAMP:20261027T120000Z",
            "UID:lunch@google.com",
            "SUMMARY:Lunch",
            "END:VEVENT",
            "BEGIN:VTODO",
            "DTSTART:20261028T120000Z",
            "SUMMARY:A task, not an event",
            "END:VTODO",
            "BEGIN:VJOURNAL",
            "DTSTART:20261028T120000Z",
            "SUMMARY:A journal entry",
            "END:VJOURNAL",
            "END:VCALENDAR",
            "");

    private static void google() {
        long from = day("20261026"), to = day("20261116");
        List<Ical.Event> all = Ical.occurrences(GOOGLE, 3, from, to, NY, 100);
        eq("google total", 14, all.size());
        List<Ical.Event> standup = titled(all, "Standup");
        eq("standup across the November change, less EXDATE, override and cancellation",
                "2026-10-26 09:30, 2026-10-30 09:30, 2026-11-02 09:30, 2026-11-04 09:30, 2026-11-11 09:30, 2026-11-13 09:30",
                starts(standup));
        if (standup.size() == 6) {
            eq("standup EDT instant", t("2026-10-30T13:30:00Z"), standup.get(1).start);
            eq("standup EST instant", t("2026-11-02T14:30:00Z"), standup.get(2).start);
            eq("standup length", 15 * 60000L, standup.get(2).end - standup.get(2).start);
            eq("standup uid", "standup-1@google.com", standup.get(0).uid);
            eq("calendar index", 3, standup.get(0).calendar);
            eq("timed is not all day", false, standup.get(0).allDay);
        }
        eq("moved standup", "2026-10-28 11:00", starts(titled(all, "Standup (moved)")));
        Ical.Event dinner = one("dinner", all, "Dinner, drinks; and a long title that Google folds because it runs past seventy-five octets");
        eq("dinner start (UTC)", t("2026-10-29T22:30:00Z"), dinner.start);
        eq("dinner end", t("2026-10-30T00:30:00Z"), dinner.end);
        eq("dinner location", "Giulia, 1682 Massachusetts Ave, Cambridge, MA 02138, USA", dinner.location);
        Ical.Event hall = one("halloween", all, "Halloween");
        eq("all-day flag", true, hall.allDay);
        eq("all-day start is local midnight", t("2026-10-31T04:00:00Z"), hall.start);
        eq("all-day end is next local midnight", t("2026-11-01T04:00:00Z"), hall.end);
        Ical.Event trip = one("trip", all, "Trip to Montreal");
        eq("multi-day start", t("2026-11-01T04:00:00Z"), trip.start);
        eq("multi-day end after the DST change", t("2026-11-04T05:00:00Z"), trip.end);
        eq("cancelled event", 0, titled(all, "Cancelled lunch").size());
        eq("old single", 0, titled(all, "Old meeting").size());
        eq("old series", 0, titled(all, "Old daily").size());
        Ical.Event mom = one("birthday", all, "Mom's birthday");
        eq("yearly all-day birthday", t("2026-11-02T05:00:00Z"), mom.start);
        eq("birthday is all day", true, mom.allDay);
        eq("last friday", "2026-10-30 18:00", starts(titled(all, "Game night")));
        eq("second tuesday", "2026-11-10 19:00", starts(titled(all, "Council meeting")));
        Ical.Event lunch = one("lunch", all, "Lunch");
        eq("duration end", "2026-10-29 13:30", ny(lunch.end));
        eq("alarm summary never names an event", 0, titled(all, "Alarm notification").size());
        eq("todo and journal ignored", 0, titled(all, "A task, not an event").size() + titled(all, "A journal entry").size());
        boolean sorted = true;
        for (int i = 1; i < all.size(); i++) {
            if (all.get(i - 1).start > all.get(i).start) sorted = false;
        }
        eq("sorted by start", true, sorted);
        List<Ical.Event> bytes = Ical.occurrences(GOOGLE.getBytes(StandardCharsets.UTF_8), 3, from, to, NY, 100);
        eq("byte and string overloads agree", starts(all), starts(bytes));
        // Later in the series the same rules give the second Tuesday and last Friday of December.
        List<Ical.Event> dec = read(GOOGLE, day("20261201"), day("20270101"));
        eq("december game night", "2026-12-25 18:00", starts(titled(dec, "Game night")));
        eq("december council", "2026-12-08 19:00", starts(titled(dec, "Council meeting")));
        eq("standup ended by UNTIL", 0, titled(dec, "Standup").size());
        List<Ical.Event> nov = read(GOOGLE, day("20261116"), day("20261201"));
        eq("standup last before UNTIL", "2026-11-16 09:30, 2026-11-18 09:30", starts(titled(nov, "Standup")));
    }

    // ---- Apple -----------------------------------------------------------------------------------

    /** Shaped like an iCloud public calendar, with LF line ends, tab folds and odd lines. */
    static final String APPLE = String.join("\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Apple Inc.//iCloud 1.0//EN",
            "CALSCALE:GREGORIAN",
            "X-WR-CALNAME:Home",
            "X-APPLE-CALENDAR-COLOR:#1BADF8",
            "X-WR-TIMEZONE:America/New_York",
            "BEGIN:VTIMEZONE",
            "TZID:America/New_York",
            "BEGIN:DAYLIGHT",
            "DTSTART:20070311T020000",
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
            "TZNAME:EDT",
            "TZOFFSETFROM:-0500",
            "TZOFFSETTO:-0400",
            "END:DAYLIGHT",
            "BEGIN:STANDARD",
            "DTSTART:20071104T020000",
            "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU",
            "TZNAME:EST",
            "TZOFFSETFROM:-0400",
            "TZOFFSETTO:-0500",
            "END:STANDARD",
            "END:VTIMEZONE",
            "BEGIN:VEVENT",
            "CREATED:20260110T153000Z",
            "DTEND;TZID=America/New_York:20260130T173000",
            "DTSTAMP:20261027T120001Z",
            "DTSTART;TZID=America/New_York:20260130T170000",
            "LAST-MODIFIED:20260110T153000Z",
            "RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
            "SEQUENCE:0",
            "SUMMARY:Pay rent",
            "UID:4E5A0F1C-8C2B-4B6E-9C1A-0D9A3B2F6E11",
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"1 Main St\\, Cambridge\";X-APPLE-RADIUS=70;X-TITLE=\"Home\":geo:42.36,-71.09",
            "LOCATION:Home\\n1 Main St\\, Cambridge",
            "ATTENDEE;CN=\"Doe, John: Jr\";CUTYPE=INDIVIDUAL;EMAIL=\"john@example.com\";PARTSTAT=ACCEPTED:mailto:john@example.com",
            "THIS LINE IS NOT VALID",
            "ATTENDEE;CN=\"Unclosed:mailto:x@example.com",
            ":no name",
            "BEGIN:VALARM",
            "X-WR-ALARMUID:5B2E7B9E-3F55-4F7C-9E44-6B0B6E2C1A11",
            "UID:5B2E7B9E-3F55-4F7C-9E44-6B0B6E2C1A11",
            "TRIGGER:-PT15M",
            "ATTACH;VALUE=URI:Chord",
            "ACTION:AUDIO",
            "LOCATION:Somewhere else",
            "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:19851014",
            "DTEND;VALUE=DATE:19851015",
            "RRULE:FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=14",
            "SUMMARY:Sam's birthday",
            "UID:B1C2D3E4-0000-4000-8000-000000000001",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:20000229",
            "DTEND;VALUE=DATE:20000301",
            "RRULE:FREQ=YEARLY",
            "SUMMARY:Leap day",
            "UID:B1C2D3E4-0000-4000-8000-000000000002",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:20201126",
            "DTEND;VALUE=DATE:20201127",
            "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=4TH",
            "SUMMARY:Thanksgiving",
            "UID:B1C2D3E4-0000-4000-8000-000000000003",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20260131T120000",
            "DTEND;TZID=America/New_York:20260131T130000",
            "RRULE:FREQ=MONTHLY",
            "SUMMARY:Month end review",
            "UID:B1C2D3E4-0000-4000-8000-000000000004",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20260131T080000",
            "DTEND;TZID=America/New_York:20260131T083000",
            "RRULE:FREQ=MONTHLY;BYMONTHDAY=-1",
            "SUMMARY:Last day backup",
            "UID:B1C2D3E4-0000-4000-8000-000000000005",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261002T100000",
            "DTEND;TZID=America/New_York",
            "DURATION:PT45M",
            "SUMMARY:Dentist with a broken DTEND line and a ti",
            "\ttle folded with a tab",
            "UID:B1C2D3E4-0000-4000-8000-000000000006",
            "END:VEVENT",
            "END:VCALENDAR",
            "");

    private static void apple() {
        List<Ical.Event> all = read(APPLE, day("20260901"), day("20270101"));
        List<Ical.Event> rent = titled(all, "Pay rent");
        eq("last weekday of the month", "2026-09-30 17:00, 2026-10-30 17:00, 2026-11-30 17:00, 2026-12-31 17:00", starts(rent));
        if (!rent.isEmpty()) eq("apple location, alarm and structured location ignored", "Home 1 Main St, Cambridge", rent.get(0).location);
        eq("yearly by month and day", "2026-10-14 00:00", starts(titled(all, "Sam's birthday")));
        eq("thanksgiving", "2026-11-26 00:00", starts(titled(all, "Thanksgiving")));
        eq("monthly on the 31st skips short months", "2026-10-31 12:00, 2026-12-31 12:00", starts(titled(all, "Month end review")));
        eq("last day of each month", "2026-09-30 08:00, 2026-10-31 08:00, 2026-11-30 08:00, 2026-12-31 08:00",
                starts(titled(all, "Last day backup")));
        Ical.Event dentist = one("dentist", all, "Dentist with a broken DTEND line and a title folded with a tab");
        eq("broken DTEND falls back to DURATION", 45 * 60000L, dentist.end - dentist.start);
        List<Ical.Event> leap = read(APPLE, day("20260101"), day("20290101"));
        eq("feb 29 only in leap years", "2028-02-29 00:00", starts(titled(leap, "Leap day")));
        List<Ical.Event> jun = read(APPLE, day("20260101"), day("20260301"));
        eq("monthly 31st in january only", "2026-01-31 12:00", starts(titled(jun, "Month end review")));
        eq("last day in february", "2026-01-31 08:00, 2026-02-28 08:00", starts(titled(jun, "Last day backup")));
    }

    // ---- RFC 5545 examples -------------------------------------------------------------------------

    /** DTSTART, RRULE, EXDATE, window end, expected (all in New York, from python-dateutil). */
    private static final String[][] RFC = {
        {"19970902T090000", "FREQ=DAILY;COUNT=10", "", "19980101",
            "1997-09-02 09:00, 1997-09-03 09:00, 1997-09-04 09:00, 1997-09-05 09:00, 1997-09-06 09:00, 1997-09-07 09:00, 1997-09-08 09:00, 1997-09-09 09:00, 1997-09-10 09:00, 1997-09-11 09:00"},
        {"19970902T090000", "FREQ=DAILY;UNTIL=19971224T000000Z", "", "19980101",
            "113: 1997-09-02 09:00, 1997-09-03 09:00, 1997-09-04 09:00 ... 1997-12-23 09:00"},
        {"19970902T090000", "FREQ=DAILY;INTERVAL=2", "", "19971201",
            "45: 1997-09-02 09:00, 1997-09-04 09:00, 1997-09-06 09:00 ... 1997-11-29 09:00"},
        {"19970902T090000", "FREQ=DAILY;INTERVAL=10;COUNT=5", "", "19980101",
            "1997-09-02 09:00, 1997-09-12 09:00, 1997-09-22 09:00, 1997-10-02 09:00, 1997-10-12 09:00"},
        {"19980101T090000", "FREQ=YEARLY;UNTIL=20000131T140000Z;BYMONTH=1;BYDAY=SU,MO,TU,WE,TH,FR,SA", "", "20010101",
            "93: 1998-01-01 09:00, 1998-01-02 09:00, 1998-01-03 09:00 ... 2000-01-31 09:00"},
        {"19980101T090000", "FREQ=DAILY;UNTIL=20000131T140000Z;BYMONTH=1", "", "20010101",
            "93: 1998-01-01 09:00, 1998-01-02 09:00, 1998-01-03 09:00 ... 2000-01-31 09:00"},
        {"19970902T090000", "FREQ=WEEKLY;COUNT=10", "", "19990101",
            "1997-09-02 09:00, 1997-09-09 09:00, 1997-09-16 09:00, 1997-09-23 09:00, 1997-09-30 09:00, 1997-10-07 09:00, 1997-10-14 09:00, 1997-10-21 09:00, 1997-10-28 09:00, 1997-11-04 09:00"},
        {"19970902T090000", "FREQ=WEEKLY;UNTIL=19971224T000000Z", "", "19990101",
            "17: 1997-09-02 09:00, 1997-09-09 09:00, 1997-09-16 09:00 ... 1997-12-23 09:00"},
        {"19970902T090000", "FREQ=WEEKLY;INTERVAL=2;WKST=SU", "", "19980201",
            "1997-09-02 09:00, 1997-09-16 09:00, 1997-09-30 09:00, 1997-10-14 09:00, 1997-10-28 09:00, 1997-11-11 09:00, 1997-11-25 09:00, 1997-12-09 09:00, 1997-12-23 09:00, 1998-01-06 09:00, 1998-01-20 09:00"},
        {"19970902T090000", "FREQ=WEEKLY;UNTIL=19971007T000000Z;WKST=SU;BYDAY=TU,TH", "", "19990101",
            "1997-09-02 09:00, 1997-09-04 09:00, 1997-09-09 09:00, 1997-09-11 09:00, 1997-09-16 09:00, 1997-09-18 09:00, 1997-09-23 09:00, 1997-09-25 09:00, 1997-09-30 09:00, 1997-10-02 09:00"},
        {"19970902T090000", "FREQ=WEEKLY;COUNT=10;WKST=SU;BYDAY=TU,TH", "", "19990101",
            "1997-09-02 09:00, 1997-09-04 09:00, 1997-09-09 09:00, 1997-09-11 09:00, 1997-09-16 09:00, 1997-09-18 09:00, 1997-09-23 09:00, 1997-09-25 09:00, 1997-09-30 09:00, 1997-10-02 09:00"},
        {"19970901T090000", "FREQ=WEEKLY;INTERVAL=2;UNTIL=19971224T000000Z;WKST=SU;BYDAY=MO,WE,FR", "", "19990101",
            "25: 1997-09-01 09:00, 1997-09-03 09:00, 1997-09-05 09:00 ... 1997-12-22 09:00"},
        {"19970902T090000", "FREQ=WEEKLY;INTERVAL=2;COUNT=8;WKST=SU;BYDAY=TU,TH", "", "19990101",
            "1997-09-02 09:00, 1997-09-04 09:00, 1997-09-16 09:00, 1997-09-18 09:00, 1997-09-30 09:00, 1997-10-02 09:00, 1997-10-14 09:00, 1997-10-16 09:00"},
        {"19970905T090000", "FREQ=MONTHLY;COUNT=10;BYDAY=1FR", "", "19990101",
            "1997-09-05 09:00, 1997-10-03 09:00, 1997-11-07 09:00, 1997-12-05 09:00, 1998-01-02 09:00, 1998-02-06 09:00, 1998-03-06 09:00, 1998-04-03 09:00, 1998-05-01 09:00, 1998-06-05 09:00"},
        {"19970905T090000", "FREQ=MONTHLY;UNTIL=19971224T000000Z;BYDAY=1FR", "", "19990101",
            "1997-09-05 09:00, 1997-10-03 09:00, 1997-11-07 09:00, 1997-12-05 09:00"},
        {"19970907T090000", "FREQ=MONTHLY;INTERVAL=2;COUNT=10;BYDAY=1SU,-1SU", "", "19990101",
            "1997-09-07 09:00, 1997-09-28 09:00, 1997-11-02 09:00, 1997-11-30 09:00, 1998-01-04 09:00, 1998-01-25 09:00, 1998-03-01 09:00, 1998-03-29 09:00, 1998-05-03 09:00, 1998-05-31 09:00"},
        {"19970922T090000", "FREQ=MONTHLY;COUNT=6;BYDAY=-2MO", "", "19990101",
            "1997-09-22 09:00, 1997-10-20 09:00, 1997-11-17 09:00, 1997-12-22 09:00, 1998-01-19 09:00, 1998-02-16 09:00"},
        {"19970928T090000", "FREQ=MONTHLY;BYMONTHDAY=-3", "", "19980301",
            "1997-09-28 09:00, 1997-10-29 09:00, 1997-11-28 09:00, 1997-12-29 09:00, 1998-01-29 09:00, 1998-02-26 09:00"},
        {"19970902T090000", "FREQ=MONTHLY;COUNT=10;BYMONTHDAY=2,15", "", "19990101",
            "1997-09-02 09:00, 1997-09-15 09:00, 1997-10-02 09:00, 1997-10-15 09:00, 1997-11-02 09:00, 1997-11-15 09:00, 1997-12-02 09:00, 1997-12-15 09:00, 1998-01-02 09:00, 1998-01-15 09:00"},
        {"19970930T090000", "FREQ=MONTHLY;COUNT=10;BYMONTHDAY=1,-1", "", "19990101",
            "1997-09-30 09:00, 1997-10-01 09:00, 1997-10-31 09:00, 1997-11-01 09:00, 1997-11-30 09:00, 1997-12-01 09:00, 1997-12-31 09:00, 1998-01-01 09:00, 1998-01-31 09:00, 1998-02-01 09:00"},
        {"19970910T090000", "FREQ=MONTHLY;INTERVAL=18;COUNT=10;BYMONTHDAY=10,11,12,13,14,15", "", "20010101",
            "1997-09-10 09:00, 1997-09-11 09:00, 1997-09-12 09:00, 1997-09-13 09:00, 1997-09-14 09:00, 1997-09-15 09:00, 1999-03-10 09:00, 1999-03-11 09:00, 1999-03-12 09:00, 1999-03-13 09:00"},
        {"19970902T090000", "FREQ=MONTHLY;INTERVAL=2;BYDAY=TU", "", "19980401",
            "18: 1997-09-02 09:00, 1997-09-09 09:00, 1997-09-16 09:00 ... 1998-03-31 09:00"},
        {"19970610T090000", "FREQ=YEARLY;COUNT=10;BYMONTH=6,7", "", "20030101",
            "1997-06-10 09:00, 1997-07-10 09:00, 1998-06-10 09:00, 1998-07-10 09:00, 1999-06-10 09:00, 1999-07-10 09:00, 2000-06-10 09:00, 2000-07-10 09:00, 2001-06-10 09:00, 2001-07-10 09:00"},
        {"19970310T090000", "FREQ=YEARLY;INTERVAL=2;COUNT=10;BYMONTH=1,2,3", "", "20050101",
            "1997-03-10 09:00, 1999-01-10 09:00, 1999-02-10 09:00, 1999-03-10 09:00, 2001-01-10 09:00, 2001-02-10 09:00, 2001-03-10 09:00, 2003-01-10 09:00, 2003-02-10 09:00, 2003-03-10 09:00"},
        {"19970101T090000", "FREQ=YEARLY;INTERVAL=3;COUNT=10;BYYEARDAY=1,100,200", "", "20050101",
            "1997-01-01 09:00"},
        {"19970519T090000", "FREQ=YEARLY;BYDAY=20MO", "", "20000101",
            "1997-05-19 09:00, 1998-05-18 09:00, 1999-05-17 09:00"},
        {"19970313T090000", "FREQ=YEARLY;BYMONTH=3;BYDAY=TH", "", "20000101",
            "1997-03-13 09:00, 1997-03-20 09:00, 1997-03-27 09:00, 1998-03-05 09:00, 1998-03-12 09:00, 1998-03-19 09:00, 1998-03-26 09:00, 1999-03-04 09:00, 1999-03-11 09:00, 1999-03-18 09:00, 1999-03-25 09:00"},
        {"19970605T090000", "FREQ=YEARLY;BYDAY=TH;BYMONTH=6,7,8", "", "19990101",
            "26: 1997-06-05 09:00, 1997-06-12 09:00, 1997-06-19 09:00 ... 1998-08-27 09:00"},
        {"19970902T090000", "FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13", "19970902T090000", "20010101",
            "1998-02-13 09:00, 1998-03-13 09:00, 1998-11-13 09:00, 1999-08-13 09:00, 2000-10-13 09:00"},
        {"19970913T090000", "FREQ=MONTHLY;BYDAY=SA;BYMONTHDAY=7,8,9,10,11,12,13", "", "19980701",
            "1997-09-13 09:00, 1997-10-11 09:00, 1997-11-08 09:00, 1997-12-13 09:00, 1998-01-10 09:00, 1998-02-07 09:00, 1998-03-07 09:00, 1998-04-11 09:00, 1998-05-09 09:00, 1998-06-13 09:00"},
        {"19961105T090000", "FREQ=YEARLY;INTERVAL=4;BYMONTH=11;BYDAY=TU;BYMONTHDAY=2,3,4,5,6,7,8", "", "20050101",
            "1996-11-05 09:00, 2000-11-07 09:00, 2004-11-02 09:00"},
        {"19970904T090000", "FREQ=MONTHLY;COUNT=3;BYDAY=TU,WE,TH;BYSETPOS=3", "", "19990101",
            "1997-09-04 09:00, 1997-10-07 09:00, 1997-11-06 09:00"},
        {"19970929T090000", "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-2", "", "19980401",
            "1997-09-29 09:00, 1997-10-30 09:00, 1997-11-27 09:00, 1997-12-30 09:00, 1998-01-29 09:00, 1998-02-26 09:00, 1998-03-30 09:00"},
        {"19970805T090000", "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=MO", "", "19990101",
            "1997-08-05 09:00, 1997-08-10 09:00, 1997-08-19 09:00, 1997-08-24 09:00"},
        {"19970805T090000", "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=SU", "", "19990101",
            "1997-08-05 09:00, 1997-08-17 09:00, 1997-08-19 09:00, 1997-08-31 09:00"},
        {"20070115T090000", "FREQ=MONTHLY;BYMONTHDAY=15,30;COUNT=5", "", "20080101",
            "2007-01-15 09:00, 2007-01-30 09:00, 2007-02-15 09:00, 2007-03-15 09:00, 2007-03-30 09:00"},
    };

    private static void rfc() {
        for (int i = 0; i < RFC.length; i++) {
            String[] c = RFC[i];
            List<String> props = new ArrayList<>(Arrays.asList(
                    "DTSTART;TZID=America/New_York:" + c[0], "DURATION:PT1H", "RRULE:" + c[1]));
            if (!c[2].isEmpty()) props.add("EXDATE;TZID=America/New_York:" + c[2]);
            String ics = cal(event("rfc-" + i, "RFC " + i, props.toArray(new String[0])));
            List<Ical.Event> got = read(ics, day(c[0].substring(0, 8)), day(c[3]));
            eq("rfc " + c[1], c[4], summary(got));
        }
    }

    /** Series that began years before the window, where expansion jumps ahead. */
    private static void skipping() {
        long from = day("20261026"), to = day("20261116");
        String weekly = cal(event("w", "Weekly", "DTSTART;TZID=America/New_York:20150105T090000",
                "DTEND;TZID=America/New_York:20150105T100000", "RRULE:FREQ=WEEKLY;BYDAY=MO,TH;COUNT=1234"));
        eq("weekly COUNT counted from 2015", "2026-10-26 09:00, 2026-10-29 09:00", starts(read(weekly, from, to)));
        String monthly = cal(event("m", "Monthly", "DTSTART;TZID=America/New_York:20150113T190000",
                "RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=143"));
        eq("monthly COUNT walked from 2015", "2026-11-10 19:00", starts(read(monthly, from, to)));
        eq("monthly COUNT one short", "", starts(read(monthly.replace("COUNT=143", "COUNT=142"), from, to)));
        String daily = cal(event("d", "Daily", "DTSTART;TZID=America/New_York:20160101T070000",
                "RRULE:FREQ=DAILY;COUNT=4000"));
        List<Ical.Event> dec = read(daily, day("20261201"), day("20270101"));
        eq("daily COUNT=4000 ends dec 13", "2026-12-13 07:00", dec.isEmpty() ? "" : ny(dec.get(dec.size() - 1).start));
        eq("daily COUNT=4000 in december", 13, dec.size());
        String every3 = cal(event("e", "Every third day", "DTSTART;TZID=America/New_York:20100101T070000",
                "RRULE:FREQ=DAILY;INTERVAL=3"));
        eq("every third day since 2010", "2026-10-28 07:00, 2026-10-31 07:00", starts(read(every3, day("20261026"), day("20261102"))));
        String biweekly = cal(event("b", "Biweekly", "DTSTART;TZID=America/New_York:20180102T160000",
                "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,FR;WKST=SU"));
        eq("every other week since 2018", "2026-10-27 16:00, 2026-10-30 16:00, 2026-11-10 16:00, 2026-11-13 16:00",
                starts(read(biweekly, from, to)));
        // A two-day event every Monday since 2020 still shows the one that began before the window.
        String long2 = cal(event("l", "Two days", "DTSTART;TZID=America/New_York:20200106T120000",
                "DTEND;TZID=America/New_York:20200108T120000", "RRULE:FREQ=WEEKLY"));
        eq("long occurrence over the window start", "2026-10-26 12:00", starts(read(long2, day("20261027"), day("20261102"))));
    }

    // ---- Zones -----------------------------------------------------------------------------------

    private static void zones() {
        String[][] cases = {
            {"\"Pacific Standard Time\"", "20261105T100000", "2026-11-05T18:00:00Z"},
            {"Eastern Standard Time", "20261027T100000", "2026-10-27T14:00:00Z"},
            {"Central Standard Time", "20261027T100000", "2026-10-27T15:00:00Z"},
            {"Mountain Standard Time", "20261027T100000", "2026-10-27T16:00:00Z"},
            {"GMT Standard Time", "20261027T100000", "2026-10-27T10:00:00Z"},
            {"GMT Standard Time", "20260715T100000", "2026-07-15T09:00:00Z"},
            {"W. Europe Standard Time", "20261027T100000", "2026-10-27T09:00:00Z"},
            {"India Standard Time", "20261027T100000", "2026-10-27T04:30:00Z"},
            {"China Standard Time", "20261027T100000", "2026-10-27T02:00:00Z"},
            {"Tokyo Standard Time", "20261027T100000", "2026-10-27T01:00:00Z"},
            {"\"(UTC-05:00) Eastern Time (US & Canada)\"", "20261027T100000", "2026-10-27T14:00:00Z"},
            {"/mozilla.org/20050126_1/America/New_York", "20261027T100000", "2026-10-27T14:00:00Z"},
            {"/freeassociation.sourceforge.net/Tzfile/Europe/London", "20261027T100000", "2026-10-27T10:00:00Z"},
            {"/softwarestudio.org/Olson_20011030_5/Asia/Tokyo", "20261027T100000", "2026-10-27T01:00:00Z"},
            {"america/los_angeles", "20261027T100000", "2026-10-27T17:00:00Z"},
            {"Europe/Berlin", "20261027T100000", "2026-10-27T09:00:00Z"},
            {"Eastern Standard Time^n", "20261027T100000", "2026-10-27T14:00:00Z"},
            {"Mars/Olympus_Mons", "20261027T100000", "2026-10-27T17:00:00Z"},
        };
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < cases.length; i++) {
            if (i > 0) events.append("\r\n");
            events.append(event("z" + i, "Z" + i, "DTSTART;TZID=" + cases[i][0] + ":" + cases[i][1]));
        }
        // An unknown zone falls back to X-WR-TIMEZONE, here Los Angeles.
        String ics = cal("X-WR-TIMEZONE:America/Los_Angeles", events.toString());
        List<Ical.Event> all = read(ics, t("2026-01-01T00:00:00Z"), t("2027-01-01T00:00:00Z"));
        for (int i = 0; i < cases.length; i++) {
            eq("zone " + cases[i][0] + " " + cases[i][1], t(cases[i][2]), one("zone " + i, all, "Z" + i).start);
        }
        String floating = event("f", "Floating", "DTSTART:20261027T100000");
        eq("floating uses X-WR-TIMEZONE", t("2026-10-27T17:00:00Z"),
                one("floating", read(cal("X-WR-TIMEZONE:America/Los_Angeles", floating), day("20261027"), day("20261028")), "Floating").start);
        eq("floating without X-WR-TIMEZONE uses the device zone", t("2026-10-27T14:00:00Z"),
                one("floating local", read(cal(floating), day("20261027"), day("20261028")), "Floating").start);
        eq("X-WR-TIMEZONE in a Windows name", t("2026-10-27T17:00:00Z"),
                one("windows x-wr", read(cal("X-WR-TIMEZONE:Pacific Standard Time", floating), day("20261027"), day("20261028")), "Floating").start);
        eq("unknown X-WR-TIMEZONE uses the device zone", t("2026-10-27T14:00:00Z"),
                one("unknown x-wr", read(cal("X-WR-TIMEZONE:Nowhere/Special", floating), day("20261027"), day("20261028")), "Floating").start);
        String[] windows = {"Eastern Standard Time", "Pacific Standard Time", "Central Standard Time", "Mountain Standard Time",
            "GMT Standard Time", "India Standard Time", "W. Europe Standard Time", "China Standard Time", "Tokyo Standard Time",
            "AUS Eastern Standard Time", "Romance Standard Time", "FLE Standard Time", "Nepal Standard Time",
            "Argentina Standard Time", "US Eastern Standard Time", "UTC"};
        for (String w : windows) eq("windows name resolves " + w, true, Ical.resolve(w) != null);
        eq("unknown zone", null, Ical.resolve("Not A Zone"));
        // A device zone other than New York moves all-day events to its own midnight.
        TimeZone tokyo = TimeZone.getTimeZone("Asia/Tokyo");
        List<Ical.Event> td = Ical.occurrences(GOOGLE, 0, t("2026-10-30T00:00:00Z"), t("2026-11-01T00:00:00Z"), tokyo, 50);
        Ical.Event hall = one("tokyo halloween", td, "Halloween");
        eq("all-day in the device zone", t("2026-10-30T15:00:00Z"), hall.start);
    }

    private static void dst() {
        String gap = cal(event("g", "Gap", "DTSTART;TZID=America/New_York:20260307T023000", "RRULE:FREQ=DAILY;COUNT=3"));
        List<Ical.Event> g = read(gap, day("20260301"), day("20260401"));
        eq("2:30 on the spring change reads as 3:30 EDT", t("2026-03-08T07:30:00Z"), g.size() == 3 ? g.get(1).start : 0L);
        eq("wall time kept either side", t("2026-03-07T07:30:00Z") + "," + t("2026-03-09T06:30:00Z"),
                g.size() == 3 ? g.get(0).start + "," + g.get(2).start : "");
        String overlap = cal(event("o", "Overlap", "DTSTART;TZID=America/New_York:20261101T013000"));
        eq("1:30 on the autumn change is the first one (EDT)", t("2026-11-01T05:30:00Z"),
                one("overlap", read(overlap, day("20261101"), day("20261102")), "Overlap").start);
        String march = cal(event("dm", "Daily", "DTSTART;TZID=America/New_York:20260306T080000",
                "DTEND;TZID=America/New_York:20260306T083000", "RRULE:FREQ=DAILY;COUNT=5",
                "EXDATE;TZID=America/New_York:20260308T080000"));
        List<Ical.Event> m = read(march, day("20260301"), day("20260401"));
        eq("daily COUNT with EXDATE across the March change", "2026-03-06 08:00, 2026-03-07 08:00, 2026-03-09 08:00, 2026-03-10 08:00", starts(m));
        eq("before the change", t("2026-03-07T13:00:00Z"), m.size() == 4 ? m.get(1).start : 0L);
        eq("after the change", t("2026-03-09T12:00:00Z"), m.size() == 4 ? m.get(2).start : 0L);
        // DTEND keeps the exact length, DURATION in days keeps the wall clock.
        String nominal = cal(event("n", "Nominal", "DTSTART;TZID=America/New_York:20261031T090000", "DURATION:P1D"),
                event("x", "Exact", "DTSTART;TZID=America/New_York:20261031T090000", "DTEND:20261101T140000Z"));
        List<Ical.Event> n = read(nominal, day("20261031"), day("20261102"));
        eq("P1D across the change ends at 9:00 again", "2026-11-01 09:00", ny(one("nominal", n, "Nominal").end));
        eq("exact length from DTEND", 25 * 3600000L, one("exact", n, "Exact").end - one("exact", n, "Exact").start);
        String allDayDur = cal(event("ad", "Long weekend", "DTSTART;VALUE=DATE:20261031", "DURATION:P3D"));
        Ical.Event lw = one("all-day duration", read(allDayDur, day("20261031"), day("20261105")), "Long weekend");
        eq("all-day DURATION P3D", t("2026-11-03T05:00:00Z"), lw.end);
        String utc = cal(event("u", "UTC daily", "DTSTART:20261030T150000Z", "RRULE:FREQ=DAILY;COUNT=3"));
        eq("UTC series keeps UTC time", "2026-10-30 11:00, 2026-10-31 11:00, 2026-11-01 10:00", starts(read(utc, day("20261030"), day("20261105"))));
    }

    // ---- Window edges and limits -----------------------------------------------------------------

    private static void edges() {
        long from = day("20261027"), to = day("20261028");
        String ics = cal(
                event("a", "Over the start", "DTSTART;TZID=America/New_York:20261026T230000", "DTEND;TZID=America/New_York:20261027T010000"),
                event("b", "Ends at the start", "DTSTART;TZID=America/New_York:20261026T220000", "DTEND;TZID=America/New_York:20261027T000000"),
                event("c", "Starts at the end", "DTSTART;TZID=America/New_York:20261028T000000", "DTEND;TZID=America/New_York:20261028T010000"),
                event("d", "Instant at the start", "DTSTART;TZID=America/New_York:20261027T000000"),
                event("e", "Yesterday all day", "DTSTART;VALUE=DATE:20261026", "DTEND;VALUE=DATE:20261027"),
                event("f", "Today all day", "DTSTART;VALUE=DATE:20261027"),
                event("g", "Long trip", "DTSTART;VALUE=DATE:20261020", "DTEND;VALUE=DATE:20261105"),
                event("h", "Over the end", "DTSTART;TZID=America/New_York:20261027T233000", "DTEND;TZID=America/New_York:20261028T003000"),
                event("i", "B tie", "DTSTART:20261027T150000Z"),
                event("j", "A tie", "DTSTART:20261027T150000Z"),
                event("k", "End before start", "DTSTART:20261027T160000Z", "DTEND:20261027T150000Z"));
        List<Ical.Event> all = read(ics, from, to);
        eq("window edges, all-day first on a tie, then title", "Long trip|Over the start|Today all day|Instant at the start|A tie|B tie|End before start|Over the end",
                titles(all));
        Ical.Event today = one("today", all, "Today all day");
        eq("all-day without DTEND lasts a day", day("20261028"), today.end);
        eq("end before start is clamped", 0L, one("clamped", all, "End before start").end - one("clamped", all, "End before start").start);
        List<Ical.Event> few = Ical.occurrences(ics, 0, from, to, NY, 3);
        eq("maxEvents keeps the earliest", "Long trip|Over the start|Today all day", titles(few));
        eq("no events wanted", 0, Ical.occurrences(ics, 0, from, to, NY, 0).size());
        eq("negative max", 0, Ical.occurrences(ics, 0, from, to, NY, -5).size());
        eq("window backwards", 0, Ical.occurrences(ics, 0, to, from, NY, 10).size());
        eq("null string", 0, Ical.occurrences((String) null, 0, from, to, NY, 10).size());
        eq("null bytes", 0, Ical.occurrences((byte[]) null, 0, from, to, NY, 10).size());
        TimeZone was = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        eq("null zone uses the default", Ical.occurrences(ics, 0, from, to, TimeZone.getDefault(), 10).size(),
                Ical.occurrences(ics, 0, from, to, null, 10).size());
        TimeZone.setDefault(was);
        String daily = cal(event("dd", "Every day", "DTSTART;TZID=America/New_York:20200101T090000", "RRULE:FREQ=DAILY"));
        List<Ical.Event> huge = Ical.occurrences(daily, 0, Long.MIN_VALUE, Long.MAX_VALUE, NY, Integer.MAX_VALUE);
        eq("whole time range is bounded by the step cap", true, huge.size() > 0 && huge.size() <= Ical.MAX_STEPS + 1);
    }

    private static String titles(List<Ical.Event> l) {
        StringBuilder b = new StringBuilder();
        for (Ical.Event e : l) b.append(b.length() > 0 ? "|" : "").append(e.title);
        return b.toString();
    }

    private static void overrides() {
        String weekly = "RRULE:FREQ=WEEKLY;BYDAY=MO";
        String ics = cal(
                event("s1", "Weekly", "DTSTART;TZID=America/New_York:20261005T100000", "DTEND;TZID=America/New_York:20261005T110000", weekly),
                // Monday Nov 2 moves into a window with no Monday, and Monday Oct 26 moves out of it.
                event("s1", "Moved in", "RECURRENCE-ID;TZID=America/New_York:20261102T100000",
                        "DTSTART;TZID=America/New_York:20261029T100000", "DTEND;TZID=America/New_York:20261029T110000"),
                event("s1", "Moved out", "RECURRENCE-ID;TZID=America/Los_Angeles:20261026T070000",
                        "DTSTART;TZID=America/New_York:20261104T100000", "DTEND;TZID=America/New_York:20261104T110000"),
                event("orphan", "Orphan", "RECURRENCE-ID:20261028T150000Z", "DTSTART:20261028T160000Z"),
                event("gone", "Gone series", "DTSTART;TZID=America/New_York:20261005T090000", weekly, "STATUS:CANCELLED"),
                event("gone", "Gone override", "RECURRENCE-ID;TZID=America/New_York:20261026T090000", "DTSTART;TZID=America/New_York:20261027T090000"));
        List<Ical.Event> a = read(ics, day("20261026"), day("20261031"));
        eq("override moved in, original moved out", "Moved in@2026-10-29 10:00|Orphan@2026-10-28 12:00",
                tagged(titled(a, "Moved in"), titled(a, "Orphan")));
        eq("RECURRENCE-ID in another zone removes the Monday", 0, titled(a, "Weekly").size());
        eq("cancelled series hides everything", 0, titled(a, "Gone series").size() + titled(a, "Gone override").size());
        List<Ical.Event> b = read(ics, day("20261102"), day("20261110"));
        eq("moved-in original gone, moved-out shown", "2026-11-04 10:00|2026-11-09 10:00",
                starts(titled(b, "Moved out")) + "|" + starts(titled(b, "Weekly")));
        // An all-day series with a date RECURRENCE-ID.
        String allDay = cal(
                event("ad", "Bins out", "DTSTART;VALUE=DATE:20261005", "RRULE:FREQ=WEEKLY"),
                event("ad", "Bins out late", "RECURRENCE-ID;VALUE=DATE:20261026", "DTSTART;VALUE=DATE:20261027"));
        eq("all-day override", "Bins out late@2026-10-27 00:00|Bins out@2026-11-02 00:00",
                tagged(read(allDay, day("20261026"), day("20261103"))));
    }

    private static String tagged(List<Ical.Event> a) {
        return tagged(a, new ArrayList<Ical.Event>());
    }

    private static String tagged(List<Ical.Event> a, List<Ical.Event> b) {
        StringBuilder s = new StringBuilder();
        List<Ical.Event> all = new ArrayList<>(a);
        all.addAll(b);
        for (Ical.Event e : all) s.append(s.length() > 0 ? "|" : "").append(e.title).append('@').append(ny(e.start));
        return s.toString();
    }

    private static void dates() {
        String ics = cal(event("ex", "Daily", "DTSTART;TZID=America/New_York:20261026T080000", "RRULE:FREQ=DAILY;COUNT=6",
                "EXDATE;TZID=America/New_York:20261027T080000,20261029T080000",
                "EXDATE:20261030T120000Z"));
        eq("EXDATE lists, lines and UTC instants", "2026-10-26 08:00, 2026-10-28 08:00, 2026-10-31 08:00",
                starts(read(ics, day("20261026"), day("20261110"))));
        String allDay = cal(event("ad", "Gym", "DTSTART;VALUE=DATE:20261026", "RRULE:FREQ=DAILY;COUNT=4", "EXDATE;VALUE=DATE:20261027,20261028"));
        eq("all-day EXDATE dates", "2026-10-26 00:00, 2026-10-29 00:00", starts(read(allDay, day("20261026"), day("20261110"))));
        String dateOnTimed = cal(event("dt", "Timed", "DTSTART;TZID=America/New_York:20261026T080000", "RRULE:FREQ=DAILY;COUNT=3",
                "EXDATE;VALUE=DATE:20261027"));
        eq("EXDATE date on a timed series", "2026-10-26 08:00, 2026-10-28 08:00", starts(read(dateOnTimed, day("20261026"), day("20261110"))));
        String rdate = cal(event("rd", "Class", "DTSTART;TZID=America/New_York:20261027T100000", "DTEND;TZID=America/New_York:20261027T110000",
                "RDATE;TZID=America/New_York:20261029T150000,20261027T100000",
                "RDATE;VALUE=DATE:20261031",
                "RDATE;VALUE=PERIOD:20261102T150000Z/PT1H",
                "RDATE:20261103T150000Z",
                "EXDATE:20261103T150000Z"));
        List<Ical.Event> r = read(rdate, day("20261026"), day("20261110"));
        eq("RDATE times, dates and periods, deduplicated, EXDATE applied",
                "2026-10-27 10:00, 2026-10-29 15:00, 2026-10-31 10:00, 2026-11-02 10:00", starts(r));
        eq("RDATE length", 3600000L, r.isEmpty() ? 0L : r.get(1).end - r.get(1).start);
        String invalid = cal(event("iv", "Feb 30", "DTSTART;TZID=America/New_York:20260130T090000", "RRULE:FREQ=MONTHLY;COUNT=3"));
        eq("invalid dates skipped and not counted", "2026-01-30 09:00, 2026-03-30 09:00, 2026-04-30 09:00",
                starts(read(invalid, day("20260101"), day("20270101"))));
        String yearlyAlone = cal(event("ya", "Anniversary", "DTSTART;TZID=America/New_York:20180615T190000", "RRULE:FREQ=YEARLY"));
        eq("YEARLY alone uses DTSTART's month and day", "2026-06-15 19:00", starts(read(yearlyAlone, day("20260101"), day("20270101"))));
        String unsupported = cal(
                event("u1", "Hourly", "DTSTART:20261027T150000Z", "RRULE:FREQ=HOURLY;COUNT=5"),
                event("u2", "Week number", "DTSTART:20261027T150000Z", "RRULE:FREQ=YEARLY;BYWEEKNO=44;BYDAY=TU"),
                event("u3", "By hour", "DTSTART:20261027T150000Z", "RRULE:FREQ=DAILY;BYHOUR=9,17"),
                event("u4", "Chinese", "DTSTART:20261027T150000Z", "RRULE:FREQ=YEARLY;RSCALE=CHINESE"),
                event("u5", "Gregorian", "DTSTART:20261027T150000Z", "RRULE:RSCALE=GREGORIAN;FREQ=DAILY;COUNT=2"));
        eq("rules it cannot follow keep only DTSTART, RSCALE=GREGORIAN is fine", "By hour|Chinese|Gregorian|Hourly|Week number|Gregorian",
                titles(read(unsupported, day("20261026"), day("20261110"))));
    }

    // ---- Text ------------------------------------------------------------------------------------

    private static void text() {
        String ics = cal(event("t1", "Line\\none\\Ntwo \\\\ back\\:slash\\x",
                "LOCATION;LANGUAGE=en:Room \\;5\\, floor 2",
                "SUMMARY:Second summary loses",
                "DTSTART:20261027T150000Z"),
                "BEGIN:VEVENT", "UID:t2", "DTSTART:20261027T160000Z",
                "SUMMARY:‮evil‬ \u0007bell" + rep("x", 300), "END:VEVENT");
        List<Ical.Event> all = read(ics, day("20261027"), day("20261028"));
        eq("unescaped and cleaned", "Line one two \\ back:slashx", all.isEmpty() ? "" : all.get(0).title);
        eq("location unescaped", "Room ;5, floor 2", all.isEmpty() ? "" : all.get(0).location);
        Ical.Event evil = all.size() > 1 ? all.get(1) : new Ical.Event();
        eq("bidi overrides removed", true, evil.title.startsWith("evil  bell") || evil.title.startsWith("evil bell"));
        eq("title capped", Text.MAX, evil.title.length());
        // A fold that splits the two bytes of the é in Café, as Google's 75-byte folding can.
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        write(b, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u\r\nDTSTART:20261027T150000Z\r\nSUMMARY:Caf");
        b.write(0xC3);
        write(b, "\r\n ");
        b.write(0xA9);
        write(b, " ☕ meetup\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
        List<Ical.Event> cafe = Ical.occurrences(b.toByteArray(), 0, day("20261027"), day("20261028"), NY, 10);
        eq("fold inside a UTF-8 character", "Café ☕ meetup", cafe.isEmpty() ? "" : cafe.get(0).title);
        // A colon and an escaped quote inside quoted parameters, before the real value.
        String quoted = cal(event("q", "Quoted",
                "DTSTART;X-NOTE=\"a:b;c ^'q^' ^^\";TZID=\"America/New_York\":20261027T100000"));
        eq("quoted parameters with colons", t("2026-10-27T14:00:00Z"),
                one("quoted", read(quoted, day("20261027"), day("20261028")), "Quoted").start);
        String bom = "﻿" + cal(event("bom", "Bom", "DTSTART:20261027T150000Z"));
        eq("byte order mark", 1, read(bom, day("20261027"), day("20261028")).size());
        String lower = "begin:vcalendar\r\nbegin:vevent\r\nuid:l\r\ndtstart:20261027T150000Z\r\nsummary:Lower\r\nend:vevent\r\nend:vcalendar\r\n";
        eq("lower-case names", "Lower", titles(read(lower, day("20261027"), day("20261028"))));
    }

    private static void write(ByteArrayOutputStream b, String s) {
        byte[] x = s.getBytes(StandardCharsets.UTF_8);
        b.write(x, 0, x.length);
    }

    private static void key() {
        byte[] a = GOOGLE.getBytes(StandardCharsets.UTF_8);
        byte[] b = GOOGLE.replace("DTSTAMP:20261027T120000Z", "DTSTAMP:20261028T090512Z").getBytes(StandardCharsets.UTF_8);
        byte[] c = GOOGLE.replace("SUMMARY:Lunch", "SUMMARY:Brunch").getBytes(StandardCharsets.UTF_8);
        byte[] d = GOOGLE.replace("DTSTAMP:20261027T120000Z", "DTSTAMP;X-A=1:20261028T0\r\n 90512Z").getBytes(StandardCharsets.UTF_8);
        String ka = Ical.changeKey(a);
        eq("key is hex SHA-256", 64, ka.length());
        eq("new DTSTAMP is no change", ka, Ical.changeKey(b));
        eq("folded DTSTAMP is no change", ka, Ical.changeKey(d));
        eq("a new title is a change", false, ka.equals(Ical.changeKey(c)));
        eq("empty feed", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Ical.changeKey(null));
        eq("stamp-like names are kept", false, Ical.changeKey("DTSTAMPX:1\r\n".getBytes(StandardCharsets.UTF_8))
                .equals(Ical.changeKey(new byte[0])));
    }

    // ---- Hostile ---------------------------------------------------------------------------------

    private static void fast(String what, long limitMs, Runnable r) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            try {
                r.run();
            } catch (Throwable e) {
                checks++;
                failures++;
                System.out.println("FAIL " + what + " threw " + e);
                return;
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1000000);
        }
        checks++;
        if (best > limitMs) {
            failures++;
            System.out.println("FAIL " + what + ": " + best + " ms");
        }
        System.out.printf("  %-50s %4d ms%n", what, best);
    }

    private static void hostile() {
        long from = day("20261026"), to = day("20261102");
        System.out.println("hostile feeds (best of 3, desktop):");
        String count = cal(event("c", "Forever", "DTSTART:19000101T090000Z", "RRULE:FREQ=DAILY;COUNT=10000000"));
        fast("DAILY COUNT=10000000 from 1900", 200, () -> eq("1900 COUNT series ended long ago", 0, read(count, from, to).size()));
        String count2 = cal(event("c", "Forever", "DTSTART:19000101T090000Z", "RRULE:FREQ=DAILY;COUNT=10000000;BYMONTH=1,2,3,4,5,6,7,8,9,10,11,12"));
        fast("same with a filter, walked to the step cap", 200, () -> eq("capped walk", 0, read(count2, from, to).size()));
        String forever = cal(event("f", "Forever", "DTSTART:19000101T090000Z", "RRULE:FREQ=DAILY"));
        fast("DAILY forever from 1900", 200, () -> eq("jumps to the window", 7, read(forever, from, to).size()));
        StringBuilder tiny = new StringBuilder("BEGIN:VCALENDAR\r\n");
        for (int i = 0; i < 100000; i++) {
            String hm = String.format("%02d%02d", i / 60 % 24, i % 60);
            tiny.append("BEGIN:VEVENT\r\nUID:e").append(i).append("\r\nDTSTART:20261027T").append(hm).append("00Z\r\nEND:VEVENT\r\n");
        }
        String tinyFeed = tiny.toString();
        fast("100k tiny VEVENTs", 200, () -> {
            List<Ical.Event> l = Ical.occurrences(tinyFeed, 0, from, to, NY, 100000);
            eq("only 5,000 events read", Ical.MAX_EVENTS, l.size());
        });
        fast("100k tiny VEVENTs, 50 wanted", 200, () -> {
            List<Ical.Event> l = Ical.occurrences(tinyFeed, 0, from, to, NY, 50);
            eq("earliest 50 kept", "50 " + t("2026-10-27T00:00:00Z"), l.size() + " " + (l.isEmpty() ? 0 : l.get(0).start));
        });
        String head = cal(event("ok", "Before the long line", "DTSTART:20261027T150000Z")).replace("END:VCALENDAR\r\n", "");
        String longLine = head + "BEGIN:VEVENT\r\nDTSTART:20261027T150000Z\r\nSUMMARY:" + rep("x", 50000000) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        fast("a 50 MB line (string)", 200, () -> eq("event before it kept, cut one dropped", "Before the long line",
                titles(read(longLine, from, to))));
        byte[] longBytes = longLine.getBytes(StandardCharsets.ISO_8859_1);
        fast("a 50 MB line (bytes)", 200, () -> eq("bytes too", 1, Ical.occurrences(longBytes, 0, from, to, NY, 10).size()));
        StringBuilder nest = new StringBuilder("BEGIN:VCALENDAR\r\n");
        for (int i = 0; i < 200000; i++) nest.append(i % 2 == 0 ? "BEGIN:VEVENT\r\n" : "BEGIN:VALARM\r\nDTSTART:20261027T150000Z\r\n");
        String nested = nest.toString();
        fast("200k nested BEGIN without END", 200, () -> eq("nothing closed, nothing read", 0, read(nested, from, to).size()));
        StringBuilder nest2 = new StringBuilder("BEGIN:VCALENDAR\r\n");
        for (int i = 0; i < 100000; i++) nest2.append("BEGIN:X").append(i % 7).append("\r\n");
        for (int i = 0; i < 100000; i++) nest2.append("END:VCALENDAR\r\n");
        nest2.append(event("after", "After", "DTSTART:20261027T150000Z")).append("\r\n");
        String nested2 = nest2.toString();
        fast("100k nested components then stray ENDs", 200, () -> eq("event after them", 1, read(nested2, from, to).size()));
        Random rnd = new Random(7);
        byte[] garbage = new byte[3000000];
        rnd.nextBytes(garbage);
        fast("3 MB of random bytes", 200, () -> eq("garbage", 0, Ical.occurrences(garbage, 0, from, to, NY, 10).size()));
        byte[] headed = garbage.clone();
        System.arraycopy("BEGIN:VCALENDAR\r\n".getBytes(StandardCharsets.US_ASCII), 0, headed, 0, 17);
        fast("3 MB of random bytes after BEGIN:VCALENDAR", 200, () -> eq("garbage read", 0, Ical.occurrences(headed, 0, from, to, NY, 10).size()));
        eq("an HTML error page is not a calendar", 0,
                read("<!DOCTYPE html>\r\n" + cal(event("x", "X", "DTSTART:20261027T150000Z")), from, to).size());
        String broken = cal(
                event("m", "Missing DTSTART", "DTEND:20261027T150000Z"),
                event("bd", "BYDAY=99MO", "DTSTART;TZID=America/New_York:20261026T090000", "RRULE:FREQ=MONTHLY;BYDAY=99MO"),
                event("iz", "INTERVAL=0", "DTSTART;TZID=America/New_York:20261026T090000", "RRULE:FREQ=DAILY;INTERVAL=0;COUNT=3"),
                event("ub", "UNTIL before DTSTART", "DTSTART;TZID=America/New_York:20261026T090000", "RRULE:FREQ=DAILY;UNTIL=20200101T000000Z"),
                event("u0", "UNTIL in 1800", "DTSTART;TZID=America/New_York:20261026T100000", "RRULE:FREQ=DAILY;UNTIL=18000101"),
                event("u9", "UNTIL in 9999", "DTSTART;TZID=America/New_York:20261026T110000", "RRULE:FREQ=WEEKLY;UNTIL=99991231T000000Z"),
                event("big", "Huge numbers", "DTSTART;TZID=America/New_York:20261026T120000",
                        "RRULE:FREQ=DAILY;INTERVAL=99999999999999999999;COUNT=-99999999999999999999"),
                event("y1", "Year 1899", "DTSTART:18991231T000000Z"),
                event("y2", "Year 2201", "DTSTART;VALUE=DATE:22010101"),
                event("y3", "Year 9999", "DTSTART:99991231T000000Z"),
                event("bad", "Bad dates", "DTSTART:20261332T250000Z"),
                event("q", "Unterminated quote", "DTSTART;TZID=\"America/New_York:20261026T090000"),
                event("sp", "BYSETPOS out of range", "DTSTART;TZID=America/New_York:20261026T130000", "RRULE:FREQ=MONTHLY;BYSETPOS=999"),
                event("bm", "BYMONTH=13", "DTSTART;TZID=America/New_York:20261026T140000", "RRULE:FREQ=YEARLY;BYMONTH=13"),
                event("dur", "Bad DURATION", "DTSTART;TZID=America/New_York:20261026T150000", "DURATION:PXYZ"),
                event("neg", "Negative DURATION", "DTSTART;TZID=America/New_York:20261026T160000", "DURATION:-PT1H"),
                event("ok", "Fine", "DTSTART;TZID=America/New_York:20261026T170000"));
        List<Ical.Event> bl = read(broken, from, to);
        eq("hostile values", "BYDAY=99MO|INTERVAL=0|UNTIL before DTSTART|UNTIL in 1800|UNTIL in 9999|Huge numbers"
                + "|BYSETPOS out of range|BYMONTH=13|Bad DURATION|Negative DURATION|Fine|INTERVAL=0|INTERVAL=0", titles(bl));
        String huge = cal(event("h", "Huge", "DTSTART;TZID=America/New_York:20200101T090000", "RRULE:FREQ=DAILY;INTERVAL=99999999999999999999"));
        eq("huge INTERVAL is clamped to 1000 days", "2022-09-27 09:00", starts(read(huge, day("20220101"), day("20230101"))));
        // Many hostile rules at once are held by the budget for the whole feed.
        StringBuilder many = new StringBuilder("BEGIN:VCALENDAR\r\n");
        for (int i = 0; i < 5000; i++) {
            many.append("BEGIN:VEVENT\r\nUID:h").append(i).append("\r\nDTSTART:19000101T000000Z\r\nRRULE:FREQ=")
                    .append(i % 2 == 0 ? "MONTHLY;BYMONTHDAY=31,30,29;COUNT=10000" : "YEARLY;BYDAY=MO,TU,WE;COUNT=10000")
                    .append("\r\nEXDATE:");
            for (int k = 0; k < 20; k++) many.append(k > 0 ? "," : "").append("2026102").append(k % 10).append("T000000Z");
            many.append("\r\nEND:VEVENT\r\n");
        }
        String manyFeed = many.toString();
        fast("5,000 events with walking COUNT rules", 200, () -> read(manyFeed, from, to));
        StringBuilder ov = new StringBuilder("BEGIN:VCALENDAR\r\n");
        for (int i = 0; i < 2500; i++) {
            ov.append("BEGIN:VEVENT\r\nUID:same\r\nDTSTART:20261026T150000Z\r\nRRULE:FREQ=DAILY\r\nEND:VEVENT\r\n");
            ov.append("BEGIN:VEVENT\r\nUID:same\r\nRECURRENCE-ID:2026").append(String.format("%04d", 1100 + i % 28)).append("T150000Z\r\n")
                    .append("DTSTART:20261027T160000Z\r\nEND:VEVENT\r\n");
        }
        String overrideFeed = ov.toString();
        fast("2,500 series and 2,500 overrides sharing a UID", 200, () -> read(overrideFeed, from, to));
    }

    // ---- Fuzz ------------------------------------------------------------------------------------

    /**
     * Random feeds built from real and broken lines, and random mutations of the fixtures. The
     * reader must never throw, and what it returns must be sorted, in the window and within
     * maxEvents.
     */
    private static void fuzz(long seed) {
        Random rnd = new Random(seed);
        long from = day("20261020"), to = day("20261110");
        String[] names = {"BEGIN:VEVENT", "END:VEVENT", "BEGIN:VALARM", "END:VALARM", "BEGIN:VCALENDAR", "END:VCALENDAR",
            "BEGIN:VTIMEZONE", "END:VTIMEZONE", "SUMMARY:x", "UID:a", "UID:b", "STATUS:CANCELLED", "X-WR-TIMEZONE:Europe/Paris",
            "DURATION:", "DTEND:", "DTSTART:", "DTSTART;VALUE=DATE:", "DTSTART;TZID=America/New_York:", "DTSTART;TZID=Nowhere:",
            "EXDATE:", "RDATE:", "RECURRENCE-ID:", "RRULE:", " folded", "\tfolded", "", ":", ";;;", "\"", "X;A=\"b:c\":d"};
        String[] parts = {"FREQ=DAILY", "FREQ=WEEKLY", "FREQ=MONTHLY", "FREQ=YEARLY", "FREQ=HOURLY", "FREQ=", "INTERVAL=", "COUNT=",
            "UNTIL=", "BYDAY=", "BYMONTHDAY=", "BYMONTH=", "BYSETPOS=", "WKST=SU", "WKST=", "BYYEARDAY=1"};
        String[] days = {"MO", "TU", "WE", "TH", "FR", "SA", "SU", "XX", ""};
        int runs = 3000;
        long t0 = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            StringBuilder b = new StringBuilder(rnd.nextInt(20) == 0 ? "" : "BEGIN:VCALENDAR\r\n");
            int lines = rnd.nextInt(60);
            for (int k = 0; k < lines; k++) {
                String n = names[rnd.nextInt(names.length)];
                b.append(n);
                if (n.endsWith(":") && n.length() > 1) {
                    if (n.startsWith("RRULE")) {
                        int np = 1 + rnd.nextInt(5);
                        for (int p = 0; p < np; p++) {
                            String part = parts[rnd.nextInt(parts.length)];
                            b.append(p > 0 ? ";" : "").append(part);
                            if (part.endsWith("=")) {
                                int v = rnd.nextInt(3);
                                if (part.startsWith("BYDAY")) {
                                    b.append(v == 0 ? "" : (rnd.nextInt(130) - 65)).append(days[rnd.nextInt(days.length)]);
                                    if (rnd.nextBoolean()) b.append(",").append(days[rnd.nextInt(days.length)]);
                                } else if (part.startsWith("UNTIL")) {
                                    b.append(stamp(rnd));
                                } else {
                                    b.append(v == 0 ? "x" : String.valueOf(rnd.nextInt(800) - 400));
                                }
                            }
                        }
                    } else if (n.startsWith("DURATION")) {
                        b.append(rnd.nextBoolean() ? "PT" + rnd.nextInt(100) + "H" : "P" + rnd.nextInt(10) + "D");
                    } else {
                        b.append(stamp(rnd));
                        if (rnd.nextInt(4) == 0) b.append(",").append(stamp(rnd));
                    }
                }
                b.append(rnd.nextInt(10) == 0 ? "\n" : "\r\n");
            }
            check("fuzz " + i, Ical.occurrences(b.toString(), 0, from, to, NY, 40), from, to, 40);
        }
        byte[][] fixtures = {GOOGLE.getBytes(StandardCharsets.UTF_8), APPLE.getBytes(StandardCharsets.UTF_8)};
        for (int i = 0; i < runs; i++) {
            byte[] m = fixtures[i % 2].clone();
            int edits = 1 + rnd.nextInt(8);
            for (int k = 0; k < edits; k++) {
                int at = rnd.nextInt(m.length);
                int kind = rnd.nextInt(4);
                if (kind == 0) m[at] = (byte) rnd.nextInt(256);
                else if (kind == 1) m[at] = (byte) PUNCT.charAt(rnd.nextInt(PUNCT.length()));
                else if (kind == 2) m = Arrays.copyOf(m, at);
                else {
                    byte[] n = new byte[m.length + 1];
                    System.arraycopy(m, 0, n, 0, at);
                    n[at] = (byte) rnd.nextInt(256);
                    System.arraycopy(m, at, n, at + 1, m.length - at);
                    m = n;
                }
                if (m.length == 0) break;
            }
            check("mutation " + i, Ical.occurrences(m, 0, from, to, NY, 40), from, to, 40);
        }
        System.out.printf("fuzz: %d feeds in %d ms%n", runs * 2, (System.nanoTime() - t0) / 1000000);
    }

    private static final String PUNCT = "\r\n :;\",=\\0123456789TZ";

    private static String stamp(Random rnd) {
        int y = 1890 + rnd.nextInt(330), m = rnd.nextInt(14), d = rnd.nextInt(33);
        String date = String.format("%04d%02d%02d", y, m, d);
        if (rnd.nextInt(5) == 0) date = String.format("2026%02d%02d", 10 + rnd.nextInt(2), 1 + rnd.nextInt(30));
        switch (rnd.nextInt(4)) {
            case 0:
                return date;
            case 1:
                return date + "T" + String.format("%02d%02d%02d", rnd.nextInt(26), rnd.nextInt(61), rnd.nextInt(62));
            case 2:
                return date + "T" + String.format("%02d%02d00", rnd.nextInt(24), rnd.nextInt(60)) + "Z";
            default:
                return date.substring(0, rnd.nextInt(date.length()));
        }
    }

    private static void check(String what, List<Ical.Event> l, long from, long to, int max) {
        checks++;
        String bad = null;
        if (l.size() > max) bad = "too many: " + l.size();
        for (int i = 0; i < l.size() && bad == null; i++) {
            Ical.Event e = l.get(i);
            if (e.start >= to || e.end < e.start || (e.end <= from && e.end != e.start) || e.start < from && e.end == e.start) {
                bad = "outside the window: " + ny(e.start) + " to " + ny(e.end);
            } else if (i > 0 && l.get(i - 1).start > e.start) {
                bad = "not sorted";
            } else if (e.title == null || e.location == null || e.uid == null) {
                bad = "null text";
            }
        }
        if (bad != null) {
            failures++;
            if (failures < 20) System.out.println("FAIL " + what + ": " + bad);
        }
    }

    /** String.repeat, which Java 8 lacks. */
    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
