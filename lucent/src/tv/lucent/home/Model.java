package tv.lucent.home;

import java.util.ArrayList;
import java.util.List;

/** The data the scene shows. Filled by the platform, read by {@link Scene}. */
final class Model {
    private Model() {}

    /** One app on the dock. */
    static final class App {
        final String id;
        final String label;
        /** Banner art (16:9, already a bitmap) or null while loading. */
        volatile Object banner;
        /** Square icon for apps without a banner. */
        volatile Object icon;
        /** A dark colour sampled from the icon, used behind icons and as a placeholder. */
        volatile int color = 0xFF23262E;
        volatile boolean loaded;

        App(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** A wallpaper, decoded and prepared for glass. */
    static final class Wall {
        Object full;
        /** The blurred, vibrancy-boosted copy every glass panel samples. */
        Object blur;
        int[] blurPixels;
        int blurW, blurH;
        String title = "", place = "";
    }

    /**
     * Temperatures are kept in Celsius as fetched and shown in both units; Fahrenheit is derived
     * from the unrounded value so 21.6 °C reads 22 °C and 71 °F, never a double-rounded 72.
     */
    static final class Weather {
        boolean ok;
        double tempC, feelsC, hiC, loC;
        int code, humidity, windDir;
        double windKph = -1;
        boolean day = true;
        /** Which unit leads: Celsius above, Fahrenheit beneath, or the reverse. */
        boolean celsiusFirst = true;
        String city = "";
        String condition = "";
        String updated = "";
        final List<String> hourLabel = new ArrayList<>();
        final List<Double> hourC = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
        /** Chance of rain per hour in percent, -1 when unknown. */
        final List<Integer> hourRain = new ArrayList<>();
        /** The next two hours ("Rain from 3:45 PM"), empty when unknown; see {@link Weather#nowcast}. */
        String rain = "";
        /** When rain starts (or started) and stops, 0 when not in the next two hours. */
        long rainAt, rainUntil;
        /** Rain or snow now or in the next two hours. */
        boolean wetSoon;

        static int c(double c) {
            return (int) Math.round(c);
        }

        static int f(double c) {
            return (int) Math.round(c * 9 / 5 + 32);
        }

        int lead(double c) {
            return celsiusFirst ? c(c) : f(c);
        }

        int other(double c) {
            return celsiusFirst ? f(c) : c(c);
        }

        String leadUnit() {
            return celsiusFirst ? "C" : "F";
        }

        String otherUnit() {
            return celsiusFirst ? "F" : "C";
        }
    }

    /** One Bluebikes dock, its fixed facts from station_information and its live counts. */
    static final class Station {
        String id = "", name = "", shortName = "";
        /** Where a phone opens Bluebikes for this dock (the feed's rental_uris), checked by {@link Gbfs#rentLink}. */
        String rent = "";
        double lat, lon;
        int capacity;
        float metres;
        int classic, ebikes, docks, disabled;
        boolean installed = true, renting = true, returning = true, live;
        long reported;

        /** E-bikes you can actually take: none while the station is not renting. */
        int rentableEbikes() {
            return installed && renting ? ebikes : 0;
        }

        int rentableClassic() {
            return installed && renting ? classic : 0;
        }
    }

    /** The stations the home screen follows; the first is the one bike finder watches. */
    static final class Bikes {
        boolean ok;
        long fetched;
        /** When Bluebikes published these counts (the feed's last_updated), 0 if unknown. */
        long published;
        /** What the followed station is called on screen: "Westgate" unless another was chosen. */
        String label = "Westgate";
        final List<Station> stations = new ArrayList<>();
        /** The docks nearest Westgate, for choosing which one to follow. */
        final List<Station> nearby = new ArrayList<>();

        Station main() {
            return stations.isEmpty() ? null : stations.get(0);
        }
    }

    /** Bike finder: polls one station every few seconds until an e-bike can be rented. */
    static final class Finder {
        static final int IDLE = 0, SEARCHING = 1, FOUND = 2, TIMED_OUT = 3, STOPPED = 4;
        int state = IDLE;
        long started, deadline, ended, lastCheck;
        int checks, failures, ebikes, classic;
        String station = "";
        boolean offline;

        boolean active() {
            return state == SEARCHING;
        }

        Finder copy() {
            Finder f = new Finder();
            f.state = state;
            f.started = started;
            f.deadline = deadline;
            f.ended = ended;
            f.lastCheck = lastCheck;
            f.checks = checks;
            f.failures = failures;
            f.ebikes = ebikes;
            f.classic = classic;
            f.station = station;
            f.offline = offline;
            return f;
        }
    }

    /** A transit line at the stop that matters, with live minutes to the next arrivals. */
    static final class Line {
        final String id, chip, stop;
        String label;
        final int color;
        final List<Integer> minutes = new ArrayList<>();
        /** Shown when nothing is coming, such as "From 6 PM" or "Next 7:25 AM". */
        String idle = "";
        /** Where it is heading, to tell apart two directions of one route ("Assembly"). */
        String toward = "";

        Line(String id, String label, String chip, String stop, int color) {
            this.id = id;
            this.label = label;
            this.chip = chip;
            this.stop = stop;
            this.color = color;
        }
    }

    static final class Transit {
        boolean ok;
        long fetched;
        /** The 85 is on the MBTA's live stream (a key is set) rather than polled. */
        boolean live;
        final List<Line> lines = new ArrayList<>();

        boolean anyRunning() {
            for (Line l : lines) if (!l.minutes.isEmpty()) return true;
            return false;
        }
    }

    /** An aircraft near home, enriched with its route when the route is known. */
    static final class Flight {
        String hex = "", callsign = "", airline = "", type = "", reg = "";
        String from = "", to = "", fromCity = "", toCity = "";
        int altFt, knots, fpm;
        float track, miles;
        /** Position, and how many seconds old it was when fetched, for carrying it forward between polls. */
        double lat, lon;
        float seen;
        boolean ground;
    }

    static final class Sky {
        boolean ok;
        long fetched;
        int inRange;
        /** Which service answered, for the credit line. */
        String source = "";
        final List<Flight> flights = new ArrayList<>();

        Flight hero() {
            return flights.isEmpty() ? null : flights.get(0);
        }
    }

    /** A National Weather Service alert for home: a warning, watch or advisory. */
    static final class Alert {
        String event = "", headline = "", severity = "", area = "";
        long onset, expires;

        /** Extreme and Severe alerts are shown in amber on the home screen. Others wait in the panel. */
        boolean serious() {
            return "Extreme".equals(severity) || "Severe".equals(severity);
        }
    }

    /** Air quality and UV from Open-Meteo (CAMS data). -1 when unknown. */
    static final class Air {
        boolean ok;
        int aqi = -1;
        double pm25 = -1, uv = -1;
        long fetched;
    }

    /** What is playing on the TV, from the playing app's media session. */
    static final class Media {
        boolean active, playing, canNext, canPrev;
        String pkg = "", app = "", title = "", artist = "", album = "";
        long duration, position, positionAt;
        float speed = 1;
        /** Album art, a bitmap at most 640 across, or null. */
        volatile Object art;
        volatile long artSource;

        /** Where playback is now, carried forward from the last report. */
        long positionNow(long elapsedRealtime) {
            if (!playing || positionAt <= 0) return position;
            long p = position + (long) ((elapsedRealtime - positionAt) * speed);
            return duration > 0 ? Math.min(duration, Math.max(0, p)) : Math.max(0, p);
        }
    }

    /** What the HDMI chain says it accepts. */
    static final class Audio {
        /** "HDMI" or "Bluetooth", and the Bluetooth device's name. */
        String route = "HDMI", device = "";
        boolean known;
        boolean atmos;
        String headline = "HDMI audio";
        int channels;
        final List<String> formats = new ArrayList<>();
    }

    /**
     * What the stick sends the TV: the HDMI mode now, the best mode the TV offers, and the HDR
     * kinds it accepts. {@link #describe} turns it into one line and, when 4K or HDR is not
     * getting through, the one setting that fixes it.
     */
    static final class Picture {
        int width, height, hz, maxWidth, maxHeight, max4kHz, uiWidth, uiHeight;
        final List<String> hdr = new ArrayList<>();
        /** "4K, 60 Hz, HDR10 and HLG", empty when the display could not be read. */
        String line = "";
        /** When 4K or HDR is not reaching the TV: what is wrong and the one setting that fixes it, else empty. */
        String problem = "", fix = "", advice = "";
        /** How apps and films draw, when all is well. */
        String note = "";

        static String size(int w, int h) {
            if (w >= 7680) return "8K";
            if (w >= 3840) return "4K";
            if (w >= 2560) return "1440p";
            if (w >= 1920 || h >= 1080) return "1080p";
            if (w >= 1280) return "720p";
            return w + " by " + h;
        }

        void describe() {
            line = problem = fix = advice = note = "";
            if (width <= 0 || height <= 0) return;
            StringBuilder b = new StringBuilder(size(width, height));
            if (hz > 0) b.append(", ").append(hz).append(" Hz");
            for (int i = 0; i < hdr.size(); i++) b.append(i == 0 ? ", " : i == hdr.size() - 1 ? " and " : ", ").append(hdr.get(i));
            line = b.toString();
            String plus = "Turn on Input Signal Plus for HDMI 1.", where = " On the Samsung: Settings, General, External Device Manager.";
            if (maxWidth > 0 && maxWidth < 3840) {
                problem = "The Samsung offers no 4K on this input.";
                fix = plus;
            } else if (max4kHz > 0 && max4kHz < 50) {
                problem = "4K stops at " + max4kHz + " Hz on this input.";
                fix = plus;
            } else if (width < 3840) {
                problem = "Fire TV is sending " + size(width, height) + ".";
                fix = "Fire TV Settings: Video Resolution to Auto.";
                where = " It is under Display and Sounds, Display.";
            } else if (hdr.isEmpty()) {
                problem = "No HDR reaches the TV.";
                fix = plus;
            }
            if (!problem.isEmpty()) advice = problem + " " + fix + where;
            else note = uiWidth > 0 && uiWidth < width ? "Apps draw at " + size(uiWidth, uiHeight) + ", films play at the full " + size(width, height) + "."
                    : "Apps and films both draw at " + size(width, height) + ".";
        }
    }

    /** A row in the settings sheet. */
    static final class Option {
        final String key, title;
        final String[] values, names;
        int index;
        final boolean action;

        Option(String key, String title, String[] values, String[] names) {
            this.key = key;
            this.title = title;
            this.values = values;
            this.names = names;
            this.action = values == null;
        }

        String value() {
            return values == null ? null : values[Math.max(0, Math.min(values.length - 1, index))];
        }

        String name() {
            return names == null || names.length == 0 ? "" : names[Math.max(0, Math.min(names.length - 1, index))];
        }
    }
}
