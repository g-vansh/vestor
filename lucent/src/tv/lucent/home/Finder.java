package tv.lucent.home;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bike finder. Once started it reads the live status of the Westgate dock (Vassar St) and of the
 * second watched dock (Erie St, outside Flour) every five seconds, on its own thread, whatever is
 * on screen, and the moment an e-bike can be rented at either it rings and tells the listeners:
 * the home screen if it is in front, otherwise the HOME guard, which shows the alert over the app
 * that is playing. When both have one, Westgate wins, being closer. It then stops by itself, as it
 * does at its deadline.
 *
 * The finder lives in the app's process, which the always-on accessibility service keeps alive,
 * so it keeps running after you open Netflix.
 */
final class Finder {
    interface Listener {
        /** On the main thread. {@code f} is a copy the listener may keep. */
        void onFinder(Model.Finder f, Model.Station station);
    }

    static final long PERIOD_MS = 5000;
    private static final int TIMEOUT_MS = 6000;
    private static Finder instance;

    static synchronized Finder get() {
        if (instance == null) instance = new Finder();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Model.Finder state = new Model.Finder();
    private Model.Station station, alt, found;
    private String label = "", altLabel = "";
    private HandlerThread thread;
    private Handler worker;
    private int generation;

    private Finder() {}

    void listen(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    synchronized Model.Finder snapshot() {
        return state.copy();
    }

    /** The dock the e-bike was found at, else the first watched one. */
    synchronized Model.Station station() {
        Model.Station s = found != null ? found : station;
        return s == null ? null : Gbfs.copy(s);
    }

    synchronized void start(Model.Station s, String label, long forMs) {
        start(s, label, null, "", forMs);
    }

    /**
     * Starts (or restarts) watching {@code s}, called {@code label} on screen, and {@code second}
     * (or null) called {@code secondLabel}, for up to {@code forMs}.
     */
    synchronized void start(Model.Station s, String label, Model.Station second, String secondLabel, long forMs) {
        if (s == null) return;
        station = Gbfs.copy(s);
        alt = second == null || second.id.equals(s.id) ? null : Gbfs.copy(second);
        found = null;
        this.label = label;
        altLabel = alt == null ? "" : secondLabel;
        long now = System.currentTimeMillis();
        state.state = Model.Finder.SEARCHING;
        state.started = now;
        state.deadline = now + forMs;
        state.ended = 0;
        state.checks = 0;
        state.failures = 0;
        state.offline = false;
        state.station = alt == null ? label : label + " and " + altLabel;
        state.ebikes = s.rentableEbikes();
        state.classic = s.rentableClassic();
        state.ebikes2 = alt == null ? -1 : alt.rentableEbikes();
        final int gen = ++generation;
        if (thread == null) {
            thread = new HandlerThread("bike-finder", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        worker.removeCallbacksAndMessages(null);
        worker.post(new Runnable() {
            @Override
            public void run() {
                poll(gen);
            }
        });
        publish();
    }

    synchronized void stop() {
        if (state.state != Model.Finder.SEARCHING) return;
        generation++;
        state.state = Model.Finder.STOPPED;
        state.ended = System.currentTimeMillis();
        if (worker != null) worker.removeCallbacksAndMessages(null);
        publish();
    }

    /** Clears a finished search so the card returns to normal. */
    synchronized void dismiss() {
        if (state.state == Model.Finder.SEARCHING || state.state == Model.Finder.IDLE) return;
        state.state = Model.Finder.IDLE;
        publish();
    }

    private void poll(final int gen) {
        long began = SystemClock.uptimeMillis();
        String id, id2;
        synchronized (this) {
            if (gen != generation || state.state != Model.Finder.SEARCHING) return;
            id = station.id;
            id2 = alt == null ? null : alt.id;
        }
        // Shares one request with the bikes card when both ask within 2 s.
        String json = Net.text(Gbfs.STATUS, TIMEOUT_MS, true, Net.LIMIT_FEED, null, 2000);
        // A CDN edge handing out the previous minute's file is not news, and not a failure.
        boolean older = json != null && !Gbfs.fresh(json);
        Map<String, Object> m = null, m2 = null;
        if (json != null && !older) {
            try {
                m = Json.objectWith(json, "station_id", id);
                if (id2 != null) m2 = Json.objectWith(json, "station_id", id2);
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                // An answer no parser expected counts as a missed check, never a crash: this
                // runs while a film plays, and the process also carries the HOME button.
                m = null;
                m2 = null;
            }
        }
        boolean found = false;
        synchronized (this) {
            if (gen != generation || state.state != Model.Finder.SEARCHING) return;
            long now = System.currentTimeMillis();
            state.checks++;
            state.lastCheck = now;
            if (m != null && !apply(m, station)) m = null;
            if (m2 != null && !apply(m2, alt)) m2 = null;
            if (older) {
                state.failures = 0;
            } else if (m != null) {
                state.failures = 0;
                state.offline = false;
                int here = station.rentableEbikes(), there = alt != null && m2 != null ? alt.rentableEbikes() : 0;
                state.ebikes2 = alt == null ? -1 : there;
                if (here > 0 || there <= 0) {
                    state.ebikes = here;
                    state.classic = station.rentableClassic();
                }
                found = here > 0 || there > 0;
                if (found) {
                    // Westgate first: it is the closer of the two.
                    this.found = here > 0 ? station : alt;
                    state.station = here > 0 ? label : altLabel;
                    state.ebikes = here > 0 ? here : there;
                    state.classic = this.found.rentableClassic();
                }
            } else {
                state.failures++;
                state.offline = state.failures >= 3;
            }
            if (found) {
                state.state = Model.Finder.FOUND;
                state.ended = now;
            } else if (now >= state.deadline) {
                state.state = Model.Finder.TIMED_OUT;
                state.ended = now;
            }
            publish();
            if (state.state == Model.Finder.SEARCHING) {
                // Every 5 s; after three misses in a row back off to 10 and then 20 s until
                // Bluebikes answers again.
                long period = state.failures < 3 ? PERIOD_MS : state.failures < 6 ? 2 * PERIOD_MS : 4 * PERIOD_MS;
                long wait = Math.max(500, period - (SystemClock.uptimeMillis() - began));
                worker.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        poll(gen);
                    }
                }, wait);
            }
        }
        // The sound (a chime, or the alarm that rings until stopped) is the hub's to play.
    }

    private static boolean apply(Map<String, Object> m, Model.Station into) {
        try {
            Gbfs.apply(m, into);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Hands a copy to the listeners and the guard on the main thread. Call holding the lock. */
    private void publish() {
        final Model.Finder copy = state.copy();
        final Model.Station st = found != null ? Gbfs.copy(found) : station == null ? null : Gbfs.copy(station);
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onFinder(copy, st);
                Guard.finder(copy, st);
            }
        });
    }
}
