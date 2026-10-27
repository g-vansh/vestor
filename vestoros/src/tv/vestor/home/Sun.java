package tv.vestor.home;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise, sunset and the moon, worked out on the TV with no network: NOAA's solar position
 * equations (the ones behind its sunrise calculator, accurate to about a minute at Boston's
 * latitude) and a mean lunation for the moon's phase.
 *
 * Day and night follow the owner's rule: night begins at sunset, day begins an hour after
 * sunrise, so early mornings stay soft.
 */
final class Sun {
    private Sun() {}

    /** Day starts this long after sunrise. */
    static final long DAY_AFTER_SUNRISE_MS = 60 * 60000L;

    /**
     * {sunrise, sunset} in epoch ms for the local calendar day containing {@code at}, or
     * {-1, -1} in polar day or night (never at Boston's latitude).
     */
    static long[] times(long at, double lat, double lon, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(at);
        int y = c.get(Calendar.YEAR), m = c.get(Calendar.MONTH) + 1, d = c.get(Calendar.DAY_OF_MONTH);
        // Midnight UTC of that calendar date, then each event in UTC minutes after it.
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.clear();
        u.set(y, m - 1, d, 0, 0, 0);
        long midnightUtc = u.getTimeInMillis();
        double jd = julianDay(y, m, d);
        double rise = event(jd, lat, lon, true), set = event(jd, lat, lon, false);
        if (Double.isNaN(rise) || Double.isNaN(set)) return new long[] {-1, -1};
        long r = midnightUtc + Math.round(rise * 60000), s = midnightUtc + Math.round(set * 60000);
        // West of Greenwich, the evening's sunset can land after UTC midnight: keep both on the
        // local day that was asked for.
        long localMidnight = startOfDay(at, zone);
        while (r < localMidnight) r += 86400000L;
        while (r >= localMidnight + 86400000L) r -= 86400000L;
        while (s < localMidnight) s += 86400000L;
        while (s >= localMidnight + 86400000L) s -= 86400000L;
        return new long[] {r, s};
    }

    /** Local midnight of the day containing {@code now}. */
    static long startOfDay(long now, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Whether it is night by the owner's rule: from sunset until an hour after sunrise. */
    static boolean night(long now, double lat, double lon, TimeZone zone) {
        long[] t = times(now, lat, lon, zone);
        if (t[0] < 0) return false;
        return now >= t[1] || now < t[0] + DAY_AFTER_SUNRISE_MS;
    }

    /** The next moment day or night begins after {@code now}. */
    static long nextChange(long now, double lat, double lon, TimeZone zone) {
        long best = Long.MAX_VALUE;
        for (int k = 0; k <= 2; k++) {
            long[] t = times(now + k * 86400000L, lat, lon, zone);
            if (t[0] < 0) continue;
            long dayAt = t[0] + DAY_AFTER_SUNRISE_MS, nightAt = t[1];
            if (dayAt > now) best = Math.min(best, dayAt);
            if (nightAt > now) best = Math.min(best, nightAt);
        }
        return best == Long.MAX_VALUE ? now + 3600000L : best;
    }

    private static double julianDay(int y, int m, int d) {
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        double a = Math.floor(y / 100.0), b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** Minutes after 00:00 UTC of sunrise or sunset, iterated twice for accuracy. */
    private static double event(double jd, double lat, double lon, boolean rise) {
        double t = (jd - 2451545.0) / 36525.0;
        double guess = utc(t, lat, lon, rise);
        if (Double.isNaN(guess)) return Double.NaN;
        double t2 = (jd + guess / 1440.0 - 2451545.0) / 36525.0;
        return utc(t2, lat, lon, rise);
    }

    private static double utc(double t, double lat, double lon, boolean rise) {
        double eq = equationOfTime(t), decl = declination(t);
        double latR = Math.toRadians(lat), declR = Math.toRadians(decl);
        double cosH = (Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(declR))) - Math.tan(latR) * Math.tan(declR);
        if (cosH > 1 || cosH < -1) return Double.NaN;
        double ha = Math.toDegrees(Math.acos(cosH));
        if (!rise) ha = -ha;
        return 720 - 4 * (lon + ha) - eq;
    }

    private static double meanLongitude(double t) {
        double l = 280.46646 + t * (36000.76983 + t * 0.0003032);
        l %= 360;
        return l < 0 ? l + 360 : l;
    }

    private static double meanAnomaly(double t) {
        return 357.52911 + t * (35999.05029 - 0.0001537 * t);
    }

    private static double eccentricity(double t) {
        return 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
    }

    private static double center(double t) {
        double m = Math.toRadians(meanAnomaly(t));
        return Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * m) * 0.000289;
    }

    private static double apparentLongitude(double t) {
        double o = 125.04 - 1934.136 * t;
        return meanLongitude(t) + center(t) - 0.00569 - 0.00478 * Math.sin(Math.toRadians(o));
    }

    private static double obliquity(double t) {
        double seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813));
        double e0 = 23.0 + (26.0 + seconds / 60.0) / 60.0;
        double o = 125.04 - 1934.136 * t;
        return e0 + 0.00256 * Math.cos(Math.toRadians(o));
    }

    private static double declination(double t) {
        double e = Math.toRadians(obliquity(t)), l = Math.toRadians(apparentLongitude(t));
        return Math.toDegrees(Math.asin(Math.sin(e) * Math.sin(l)));
    }

    private static double equationOfTime(double t) {
        double e = Math.toRadians(obliquity(t)), l0 = Math.toRadians(meanLongitude(t));
        double ecc = eccentricity(t), m = Math.toRadians(meanAnomaly(t));
        double y = Math.tan(e / 2) * Math.tan(e / 2);
        double eq = y * Math.sin(2 * l0) - 2 * ecc * Math.sin(m) + 4 * ecc * y * Math.sin(m) * Math.cos(2 * l0)
                - 0.5 * y * y * Math.sin(4 * l0) - 1.25 * ecc * ecc * Math.sin(2 * m);
        return Math.toDegrees(eq) * 4;
    }

    // ---- Moon ----------------------------------------------------------------------------------

    /** Mean synodic month in days. */
    static final double LUNATION = 29.530588853;
    /** A new moon (2000-01-06 18:14 UTC) to count from. */
    private static final long NEW_MOON_MS = 947182440000L;

    /** Phase from 0 (new) through 0.5 (full) to 1 (new again). */
    static double moonPhase(long at) {
        double days = (at - NEW_MOON_MS) / 86400000.0;
        double p = (days % LUNATION) / LUNATION;
        return p < 0 ? p + 1 : p;
    }

    /** The phase's name, as almanacs give them. */
    static String moonName(double p) {
        if (p < 0.0339 || p > 0.9661) return "New moon";
        if (p < 0.2161) return "Waxing crescent";
        if (p < 0.2839) return "First quarter";
        if (p < 0.4661) return "Waxing gibbous";
        if (p < 0.5339) return "Full moon";
        if (p < 0.7161) return "Waning gibbous";
        if (p < 0.7839) return "Last quarter";
        return "Waning crescent";
    }

    /** How much of the moon is lit, 0..1. */
    static double moonLit(double p) {
        return (1 - Math.cos(2 * Math.PI * p)) / 2;
    }
}
