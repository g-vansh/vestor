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
 * Bike finder. Once started it reads the Westgate station's live status every five seconds, on
 * its own thread, whatever is on screen, and the moment an e-bike can be rented it chimes and
 * tells the listeners: the home screen if it is in front, otherwise the HOME guard, which shows
 * the alert over the app that is playing. It then stops by itself, as it does at its deadline.
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
    private Model.Station station;
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

    synchronized Model.Station station() {
        return station == null ? null : Gbfs.copy(station);
    }

    /** Starts (or restarts) watching {@code s}, called {@code label} on screen, for up to {@code forMs}. */
    synchronized void start(Model.Station s, String label, long forMs) {
        if (s == null) return;
        station = Gbfs.copy(s);
        long now = System.currentTimeMillis();
        state.state = Model.Finder.SEARCHING;
        state.started = now;
        state.deadline = now + forMs;
        state.ended = 0;
        state.checks = 0;
        state.failures = 0;
        state.offline = false;
        state.station = label;
        state.ebikes = s.rentableEbikes();
        state.classic = s.rentableClassic();
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
        String id;
        synchronized (this) {
            if (gen != generation || state.state != Model.Finder.SEARCHING) return;
            id = station.id;
        }
        String json = Net.text(Gbfs.STATUS, TIMEOUT_MS, true, Net.LIMIT_FEED);
        Map<String, Object> m = null;
        if (json != null) {
            try {
                m = Json.objectWith(json, "station_id", id);
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                // An answer no parser expected counts as a missed check, never a crash: this
                // runs while a film plays, and the process also carries the HOME button.
                m = null;
            }
        }
        boolean found = false;
        synchronized (this) {
            if (gen != generation || state.state != Model.Finder.SEARCHING) return;
            long now = System.currentTimeMillis();
            state.checks++;
            state.lastCheck = now;
            if (m != null && !apply(m)) m = null;
            if (m != null) {
                state.failures = 0;
                state.offline = false;
                state.ebikes = station.rentableEbikes();
                state.classic = station.rentableClassic();
                found = state.ebikes > 0;
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
        if (found) Chime.play();
    }

    private boolean apply(Map<String, Object> m) {
        try {
            Gbfs.apply(m, station);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Hands a copy to the listeners and the guard on the main thread. Call holding the lock. */
    private void publish() {
        final Model.Finder copy = state.copy();
        final Model.Station st = station == null ? null : Gbfs.copy(station);
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onFinder(copy, st);
                Guard.finder(copy, st);
            }
        });
    }
}
