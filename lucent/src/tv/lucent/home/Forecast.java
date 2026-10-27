package tv.lucent.home;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * When the watched docks will run out of e-bikes, and when the next one will turn up, from the
 * model in assets/bikes_model.json (fitted on every Bluebikes trip at these docks since March
 * 2024, the community status log of autumn 2026, MIT's academic calendar and Logan's weather; the
 * analysis is in analysis/bikes/README.md).
 *
 * The stock at Vassar St and at Erie St moves minute by minute as a pair: riders take e-bikes at
 * each dock's demand rate (for this day type, time, month, rain and temperature), riders bring
 * them back at each dock's return rate, and Lyft's van adds a batch at a small hourly hazard. The
 * chance that both docks are empty at once, for the first time, gives the run-out time; with both
 * empty, the first return or restock at either gives the next e-bike. A backtest on 116 October
 * mornings put 78% of real run-outs inside the 80% band.
 *
 * Platform-free and allocation-light: the TV calls it on its worker thread when the counts change.
 */
final class Forecast {
    static final String[] DAY_TYPES = {"class", "finals", "offweek", "sat", "sun"};
    static final int CAP_V = 53, CAP_E = 19;
    /** Minutes looked ahead for a run-out or the next e-bike. */
    static final int HORIZON = 480;
    /** The morning outlook follows the chance of an e-bike for two hours from its time, minute by minute. */
    static final int MORNING_SPAN = 121;

    /** One dock's model for departures or returns: log-rates per minute. */
    static final class Rate {
        final double[][] base = new double[DAY_TYPES.length][96];
        final double[] month = new double[12], temp = new double[5];
        double wet, heavy, snow, holiday;
    }

    static final class Result {
        /** Run-out of both docks together, and of the first dock alone: 10%, 50%, 90% times, or 0 past the horizon. */
        long poolLo, poolMed, poolHi, firstLo, firstMed, firstHi;
        /** With both empty: when the next e-bike turns up at either, 10%, 50%, 90%. */
        long nextLo, nextMed, nextHi;
        /** The chance that at least one e-bike waits at either dock at {@link #morningAt}, or -1. */
        double morning = -1;
        long morningAt;
        /** The same chance an hour and two hours later (8:30 and 9:30 for a 7:30 outlook), or -1. */
        double morning1h = -1, morning2h = -1;
        String dayType = "";
        /** True when both docks were empty: then the next-e-bike times hold, else the run-out ones. */
        boolean empty;
    }

    final Rate depV = new Rate(), arrV = new Rate(), depE = new Rate(), arrE = new Rate();
    /** Restock hazards per minute by hour, on weekdays and at weekends (Lyft's van mostly comes at weekends). */
    final double[] restockV = new double[24], restockE = new double[24], restockWeV = new double[24], restockWeE = new double[24];
    double sizeV = 8, sizeE = 4;
    private final double[] tempEdges = {0, 8, 16, 24};
    private long calFrom;
    private String calDays = "";
    /** Calibration from the backtest: demand on weekdays and weekends, returns while waiting, restocks (stocked, empty). */
    double calDepWeekday = 1, calDepWeekend = 1, calArrNext = 1, calRestock = 1, calRestockNext = 1;
    /** Corrections learned on this TV from what it sees at the two docks: multipliers on demand and returns. */
    double fixDepV = 1, fixArrV = 1, fixDepE = 1, fixArrE = 1;

    /** Reads the model; throws IllegalArgumentException when it is not one. */
    static Forecast parse(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        if (root == null || Json.num(root, "version", 0) != 1) throw new IllegalArgumentException("model");
        Forecast f = new Forecast();
        Map<String, Object> st = Json.obj(root, "stations");
        f.read(Json.obj(Json.obj(st, "vassar"), "dep"), f.depV);
        f.read(Json.obj(Json.obj(st, "vassar"), "arr"), f.arrV);
        f.read(Json.obj(Json.obj(st, "erie"), "dep"), f.depE);
        f.read(Json.obj(Json.obj(st, "erie"), "arr"), f.arrE);
        Map<String, Object> rs = Json.obj(root, "restock");
        f.sizeV = restock(Json.obj(rs, "vassar"), f.restockV, f.restockWeV, 8);
        f.sizeE = restock(Json.obj(rs, "erie"), f.restockE, f.restockWeE, 4);
        Map<String, Object> cb = Json.obj(root, "calibration");
        if (cb != null) {
            f.calDepWeekday = bounded(Json.num(cb, "depWeekday", 1), 0.2, 5);
            f.calDepWeekend = bounded(Json.num(cb, "depWeekend", 1), 0.2, 5);
            f.calArrNext = bounded(Json.num(cb, "arrNext", 1), 0.2, 5);
            f.calRestock = bounded(Json.num(cb, "restock", 1), 0, 5);
            f.calRestockNext = bounded(Json.num(cb, "restockNext", f.calRestock), 0, 5);
        }
        Map<String, Object> cal = Json.obj(root, "calendar");
        if (cal != null) {
            String from = Json.str(cal, "from", ""), days = Json.str(cal, "days", "");
            if (from.matches("\\d{4}-\\d{2}-\\d{2}") && days.matches("[cfosuh]{1,2000}")) {
                f.calFrom = dayNumber(Integer.parseInt(from.substring(0, 4)), Integer.parseInt(from.substring(5, 7)),
                        Integer.parseInt(from.substring(8, 10)));
                f.calDays = days;
            }
        }
        return f;
    }

    private void read(Map<String, Object> m, Rate r) {
        if (m == null) throw new IllegalArgumentException("model");
        Map<String, Object> base = Json.obj(m, "base");
        for (int d = 0; d < DAY_TYPES.length; d++) numbers(Json.arr(base, DAY_TYPES[d]), r.base[d], -30, 3);
        numbers(Json.arr(m, "month"), r.month, -5, 5);
        numbers(Json.arr(m, "temp"), r.temp, -5, 5);
        r.wet = bounded(Json.num(m, "wet", 0), -5, 5);
        r.heavy = bounded(Json.num(m, "heavy", 0), -5, 5);
        r.snow = bounded(Json.num(m, "snow", 0), -5, 5);
        r.holiday = bounded(Json.num(m, "holiday", 0), -5, 5);
    }

    private static double restock(Map<String, Object> m, double[] weekday, double[] weekend, double size) {
        if (m == null) return size;
        numbers(Json.arr(m, "perMinute"), weekday, 0, 0.05);
        if (Json.arr(m, "perMinuteWeekend") != null) numbers(Json.arr(m, "perMinuteWeekend"), weekend, 0, 0.05);
        else System.arraycopy(weekday, 0, weekend, 0, 24);
        return bounded(Json.num(m, "meanSize", size), 1, 30);
    }

    private static void numbers(List<Object> from, double[] into, double lo, double hi) {
        if (from == null || from.size() != into.length) throw new IllegalArgumentException("model");
        for (int i = 0; i < into.length; i++) {
            Object o = from.get(i);
            if (!(o instanceof Double)) throw new IllegalArgumentException("model");
            into[i] = bounded((Double) o, lo, hi);
        }
    }

    private static double bounded(double v, double lo, double hi) {
        if (Double.isNaN(v) || v < lo || v > hi) throw new IllegalArgumentException("model");
        return v;
    }

    /** Days since 1970-01-01 for a civil date (proleptic Gregorian). */
    static long dayNumber(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /**
     * The day type for a local date: from MIT's calendar while it covers the date, else weekends,
     * and weekdays in term months as class days.
     */
    int dayType(int y, int m, int d, int dayOfWeek) {
        long n = dayNumber(y, m, d) - calFrom;
        if (n >= 0 && n < calDays.length()) {
            char ch = calDays.charAt((int) n);
            return "cfosu".indexOf(ch == 'h' ? 'o' : ch);
        }
        if (dayOfWeek == Calendar.SATURDAY) return 3;
        if (dayOfWeek == Calendar.SUNDAY) return 4;
        return m == 1 || m == 6 || m == 7 || m == 8 ? 2 : 0;
    }

    /**
     * Fills per-minute rates from {@code now} for {@code minutes}: demand and returns at both
     * docks and the restock hazards. {@code rainChance} and {@code tempC} are hourly from the
     * current hour (the forecast card's next hours); later hours use an ordinary mix of weather.
     * {@code restock} scales the restock hazards (the backtest fits one for stocked docks and one for empty ones).
     */
    void rates(long now, int minutes, TimeZone zone, double[] rainChance, double[] tempC, double restock, double[][] out) {
        Calendar c = Calendar.getInstance(zone);
        int i = 0, h = 0;
        while (i < minutes) {
            // One calendar look-up an hour: day type, month, weather and restocks hold for the hour.
            c.setTimeInMillis(now + i * 60000L);
            int minute = c.get(Calendar.MINUTE), hour = c.get(Calendar.HOUR_OF_DAY), month = c.get(Calendar.MONTH);
            int dt = dayType(c.get(Calendar.YEAR), month + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.DAY_OF_WEEK));
            boolean hol = holiday(c.get(Calendar.YEAR), month + 1, c.get(Calendar.DAY_OF_MONTH));
            boolean weekend = dt == 3 || dt == 4;
            double dep = weekend ? calDepWeekend : calDepWeekday;
            double p = rainChance != null && h < rainChance.length && rainChance[h] >= 0 ? rainChance[h] : 0.12;
            double temp = tempC != null && h < tempC.length && !Double.isNaN(tempC[h]) ? tempC[h] : Double.NaN;
            double resV = (weekend ? restockWeV : restockV)[hour] * restock, resE = (weekend ? restockWeE : restockE)[hour] * restock;
            for (int mm = minute; mm < 60 && i < minutes; mm++, i++) {
                if (mm == minute || mm % 15 == 0) {
                    int slot = (hour * 60 + mm) / 15;
                    out[0][i] = rate(depV, dt, slot, month, p, temp, hol) * dep * fixDepV;
                    out[1][i] = rate(depE, dt, slot, month, p, temp, hol) * dep * fixDepE;
                    out[2][i] = rate(arrV, dt, slot, month, p, temp, hol) * fixArrV;
                    out[3][i] = rate(arrE, dt, slot, month, p, temp, hol) * fixArrE;
                } else {
                    for (int k = 0; k < 4; k++) out[k][i] = out[k][i - 1];
                }
                out[4][i] = resV;
                out[5][i] = resE;
            }
            h++;
        }
    }

    private int tempBin(double t) {
        if (Double.isNaN(t)) return 1;
        int b = 0;
        while (b < tempEdges.length && t >= tempEdges[b]) b++;
        return b;
    }

    /** Whether the calendar marks the date as a weekday holiday ('h'). */
    boolean holiday(int y, int m, int d) {
        long n = dayNumber(y, m, d) - calFrom;
        return n >= 0 && n < calDays.length() && calDays.charAt((int) n) == 'h';
    }

    /** Per-minute rate: the dry rate, mixed with the wet one by the chance of rain. */
    private double rate(Rate r, int dt, int slot, int month, double rainChance, double temp, boolean holiday) {
        double lp = r.base[dt][slot] + r.month[month] + r.temp[tempBin(temp)] + (holiday ? r.holiday : 0);
        double dry = Math.exp(lp);
        return dry * (1 - rainChance + rainChance * Math.exp(r.wet));
    }

    /**
     * The forecast from the stock now. {@code nE} is -1 when only the first dock is watched.
     * Slow-ish (a few million steps): call it off the main thread.
     */
    Result predict(long now, int nV, int nE, TimeZone zone, double[] rainChance, double[] tempC, long morningAt) {
        int minutes = HORIZON;
        if (morningAt > now) minutes = (int) Math.max(minutes, Math.min(24 * 60 + MORNING_SPAN, (morningAt - now) / 60000L + MORNING_SPAN));
        double[][] r = new double[6][minutes];
        boolean two = nE >= 0;
        boolean none = Math.max(0, nV) + (two ? Math.max(0, nE) : 0) == 0;
        rates(now, minutes, zone, rainChance, tempC, none ? calRestockNext : calRestock, r);
        if (!two) {
            for (int i = 0; i < minutes; i++) r[1][i] = r[3][i] = r[5][i] = 0;
        }
        Result out = new Result();
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        out.dayType = DAY_TYPES[dayType(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.DAY_OF_WEEK))];
        int v = Math.max(0, Math.min(CAP_V, nV)), e = two ? Math.max(0, Math.min(CAP_E, nE)) : 0;
        double[] cdf = new double[HORIZON];
        out.empty = v + e == 0;
        if (v + e > 0) {
            chain(v, e, r, HORIZON, 0, cdf, -1, null);
            long[] q = quantiles(cdf, now);
            out.poolLo = q[0];
            out.poolMed = q[1];
            out.poolHi = q[2];
            if (two && v > 0) {
                chain(v, e, r, HORIZON, 1, cdf, -1, null);
                q = quantiles(cdf, now);
                out.firstLo = q[0];
                out.firstMed = q[1];
                out.firstHi = q[2];
            } else if (!two) {
                out.firstLo = out.poolLo;
                out.firstMed = out.poolMed;
                out.firstHi = out.poolHi;
            }
        } else {
            double haz = 0;
            for (int i = 0; i < HORIZON; i++) {
                haz += (r[2][i] + r[3][i]) * calArrNext + r[4][i] + r[5][i];
                cdf[i] = 1 - Math.exp(-haz);
            }
            long[] q = quantiles(cdf, now);
            out.nextLo = q[0];
            out.nextMed = q[1];
            out.nextHi = q[2];
        }
        if (morningAt > now) {
            int at = (int) ((morningAt - now) / 60000L);
            if (at + MORNING_SPAN <= minutes) {
                double[] some = new double[MORNING_SPAN];
                chain(v, e, r, at + MORNING_SPAN, 2, null, at, some);
                out.morning = some[0];
                out.morning1h = some[60];
                out.morning2h = some[120];
                out.morningAt = morningAt;
            }
        }
        return out;
    }

    /**
     * Steps the joint stock minute by minute. mode 0: absorb when both docks are empty, mode 1:
     * absorb when the first is, both writing the first-passage CDF; mode 2: no absorption, and from
     * minute {@code at} on, the chance that either dock has an e-bike fills {@code some}.
     *
     * At most one event a minute (rates are a few an hour). A restock adds a geometric batch, which
     * is spread with a running sum instead of a convolution, and only the occupied block of stock
     * levels is visited, so a full forecast is a few hundred thousand steps.
     */
    private void chain(int v0, int e0, double[][] r, int minutes, int mode, double[] cdf, int at, double[] some) {
        int nv = CAP_V + 1, ne = CAP_E + 1;
        double[] p = new double[nv * ne], q = new double[nv * ne], g = new double[Math.max(nv, ne)];
        p[v0 * ne + e0] = 1;
        int iLo = v0, iHi = v0, jLo = e0, jHi = e0;
        double pv = 1 / Math.max(1, sizeV), pe = 1 / Math.max(1, sizeE);
        double zv = 1 - Math.pow(1 - pv, CAP_V), ze = 1 - Math.pow(1 - pe, CAP_E);
        double done = 0;
        double[] scale = new double[4], still = new double[4];
        for (int m = 0; m < minutes; m++) {
            double dV = r[0][m], dE = r[1][m], aV = r[2][m], aE = r[3][m], rV = r[4][m], rE = r[5][m];
            double common = aV + aE + rV + rE;
            for (int s = 0; s < 4; s++) {
                double total = common + ((s & 1) != 0 ? dV : 0) + ((s & 2) != 0 ? dE : 0);
                double pe1 = total > 0 ? 1 - Math.exp(-total) : 0;
                still[s] = 1 - pe1;
                scale[s] = total > 0 ? pe1 / total : 0;
            }
            int lo2 = Math.max(0, iLo - 1), hi2 = rV > 0 ? CAP_V : Math.min(CAP_V, iHi + 1);
            int jl2 = Math.max(0, jLo - 1), jh2 = rE > 0 ? CAP_E : Math.min(CAP_E, jHi + 1);
            for (int i = lo2; i <= hi2; i++) java.util.Arrays.fill(q, i * ne + jl2, i * ne + jh2 + 1, 0);
            for (int i = iLo; i <= iHi; i++) {
                for (int j = jLo; j <= jHi; j++) {
                    double w = p[i * ne + j];
                    if (w == 0) continue;
                    int s = (i > 0 ? 1 : 0) | (j > 0 ? 2 : 0);
                    double f = w * scale[s];
                    q[i * ne + j] += w * still[s];
                    if (i > 0) q[(i - 1) * ne + j] += f * dV;
                    if (j > 0) q[i * ne + j - 1] += f * dE;
                    q[Math.min(i + 1, CAP_V) * ne + j] += f * aV;
                    q[i * ne + Math.min(j + 1, CAP_E)] += f * aE;
                }
            }
            if (rV > 0) {
                // Batches at Vassar St: for each Erie level, f*rV from every row spreads upwards.
                for (int j = jLo; j <= jHi; j++) {
                    double run = 0, total = 0, given = 0;
                    for (int i = 0; i <= CAP_V; i++) {
                        double w = i >= iLo && i <= iHi ? p[i * ne + j] : 0;
                        g[i] = w * scale[(i > 0 ? 1 : 0) | (j > 0 ? 2 : 0)] * rV;
                        total += g[i];
                    }
                    for (int k = 1; k < CAP_V; k++) {
                        run = (1 - pv) * run + g[k - 1];
                        double add = pv / zv * run;
                        q[k * ne + j] += add;
                        given += add;
                    }
                    q[CAP_V * ne + j] += total - given;
                }
            }
            if (rE > 0) {
                for (int i = iLo; i <= iHi; i++) {
                    double run = 0, total = 0, given = 0;
                    for (int j = 0; j <= CAP_E; j++) {
                        double w = j >= jLo && j <= jHi ? p[i * ne + j] : 0;
                        g[j] = w * scale[(i > 0 ? 1 : 0) | (j > 0 ? 2 : 0)] * rE;
                        total += g[j];
                    }
                    for (int k = 1; k < CAP_E; k++) {
                        run = (1 - pe) * run + g[k - 1];
                        double add = pe / ze * run;
                        q[i * ne + k] += add;
                        given += add;
                    }
                    q[i * ne + CAP_E] += total - given;
                }
            }
            if (mode == 0) {
                done += q[0];
                q[0] = 0;
            } else if (mode == 1) {
                for (int j = 0; j < ne; j++) {
                    done += q[j];
                    q[j] = 0;
                }
            }
            if (cdf != null && m < cdf.length) cdf[m] = done;
            if (mode == 2 && m >= at && m - at < some.length) some[m - at] = 1 - q[0];
            // The occupied block: anything below 1e-13 is dropped.
            iLo = nv;
            iHi = -1;
            jLo = ne;
            jHi = -1;
            for (int i = lo2; i <= hi2; i++) {
                for (int j = jl2; j <= jh2; j++) {
                    if (q[i * ne + j] < 1e-13) {
                        q[i * ne + j] = 0;
                        continue;
                    }
                    if (i < iLo) iLo = i;
                    if (i > iHi) iHi = i;
                    if (j < jLo) jLo = j;
                    if (j > jHi) jHi = j;
                }
            }
            double[] t = p;
            p = q;
            q = t;
            if (iHi < 0) {
                // Everything absorbed: the CDF stays where it is.
                for (int k = m + 1; cdf != null && k < Math.min(minutes, cdf.length); k++) cdf[k] = done;
                break;
            }
        }
    }

    /** The 10%, 50% and 90% minutes of a CDF as times, 0 when beyond the horizon. */
    private static long[] quantiles(double[] cdf, long now) {
        long[] out = new long[3];
        double[] qs = {0.1, 0.5, 0.9};
        for (int k = 0; k < 3; k++) {
            int i = 0;
            while (i < cdf.length && cdf[i] < qs[k]) i++;
            out[k] = i < cdf.length ? now + (i + 1) * 60000L : 0;
        }
        return out;
    }
}
