package tv.lucent.home;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * A circuit breaker for the HOME button. Lucent's process also carries the service that brings
 * it back over Amazon's home, so a build that crashed on start could otherwise be relaunched
 * again and again. Each crash is noted, and after three within three minutes the guard stands
 * aside and Amazon's home stays until things calm down. Lucent itself still opens from Your Apps.
 */
final class Crash {
    private static final String FILE = "lucent_crashes", KEY = "at", START = "start";
    private static final long WINDOW = 3 * 60 * 1000L;
    private static final int LIMIT = 3;

    private Crash() {}

    private static final class Noter implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler next;

        Noter(Context app, Thread.UncaughtExceptionHandler next) {
            this.app = app;
            this.next = next;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                note(app, System.currentTimeMillis());
            } catch (Throwable ignored) {
                // never stand in the way of the crash report
            }
            if (next != null) next.uncaughtException(t, e);
        }
    }

    /**
     * Once per process, from whichever of the home screen and the guard starts first. A start
     * marker the home screen left without ever drawing a frame means the last start died in
     * native code or hung (no Java exception to see), and it counts as a crash too.
     */
    static synchronized void watch(Context c) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof Noter) return;
        Thread.setDefaultUncaughtExceptionHandler(new Noter(c.getApplicationContext(), current));
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        long started = p.getLong(START, 0), now = System.currentTimeMillis();
        if (started > 0) {
            p.edit().remove(START).commit();
            if (now - started >= 0 && now - started < WINDOW) note(c, now);
        }
    }

    /** The home screen is starting; {@link #drawn} clears this once its first frame is on screen. */
    static void starting(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(START, System.currentTimeMillis()).commit();
    }

    static void drawn(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(START).apply();
    }

    private static void note(Context c, long now) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        StringBuilder kept = new StringBuilder(Long.toString(now));
        int n = 1;
        for (String s : p.getString(KEY, "").split(",")) {
            if (n >= LIMIT + 2) break;
            try {
                long t = Long.parseLong(s);
                if (now - t < WINDOW) {
                    kept.append(',').append(t);
                    n++;
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        // commit, not apply: the process is about to end.
        p.edit().putString(KEY, kept.toString()).commit();
    }

    /** Whether Lucent crashed three times in the last three minutes. */
    static boolean tooMany(Context c) {
        String v = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "");
        if (v.isEmpty()) return false;
        long now = System.currentTimeMillis();
        int recent = 0;
        for (String s : v.split(",")) {
            try {
                long t = Long.parseLong(s);
                if (now - t >= 0 && now - t < WINDOW) recent++;
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return recent >= LIMIT;
    }
}
