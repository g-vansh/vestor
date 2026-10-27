package tv.lucent.home;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Takes the HOME button back from Amazon's launcher. Adapted from dumbtv's Guard
 * (github.com/humblemuzzu/dumbtv, MIT licence), which was tested on Fire OS 8.1.8.5.
 *
 * Fire OS keeps HOME bound to Amazon's protected launcher and handles the key before any
 * accessibility filter sees it, so this service watches for Amazon's home window and replaces it.
 * A plain startActivity right after HOME is held back by Android's five-second app-switch delay;
 * an alarm-fired PendingIntent comes from system_server, which is exempt, and fires at once
 * because install.sh sets {@code min_futurity=0}. Android 11 also refuses an activity start from
 * an app with no visible window, so a cover window is shown first and the launch waits for its
 * first draw. The cover shows this home screen's own blurred wallpaper, so the switch reads as
 * the glass settling rather than a black flash.
 *
 * The same overlay permission carries the bike finder's alert over whatever app is playing:
 * an accessibility overlay needs no "draw over other apps" grant and never takes the remote.
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long COVER_MAX_MS = 1500;
    private static final long REPEAT_MS = 1000;
    private static final long LAUNCH_FALLBACK_MS = 150;

    private static volatile boolean paused;
    private static Guard running;
    /** The blurred wallpaper, set by the home screen. Same process, so a static is enough. */
    static volatile Bitmap coverImage;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = new Runnable() {
        @Override
        public void run() {
            uncover();
        }
    };
    private final Runnable launch = new Runnable() {
        @Override
        public void run() {
            home(Guard.this);
        }
    };
    private View cover;
    private Bitmap coverShown;
    private NoticeView notice;
    private long remindedFor;
    private final Runnable noticeDone = new Runnable() {
        @Override
        public void run() {
            if (notice != null) notice.hide();
        }
    };
    private long lastLaunch;
    private boolean onAmazonHome;
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!paused && onAmazonHome && !Crash.tooMany(c)) launchWhenCovered();
        }
    };

    /** Lets Amazon's home stay on screen until the user leaves it. */
    static void pause() {
        paused = true;
    }

    static void home(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    /** Called once the home window is on screen. */
    static void shown() {
        Guard g = running;
        if (g != null) g.handler.post(g.uncover);
    }

    static boolean isRunning() {
        return running != null;
    }

    /** Bike finder news, on the main thread. The home screen shows its own when in front. */
    static void finder(Model.Finder f, Model.Station s) {
        Guard g = running;
        if (g != null) g.onFinder(f, s);
    }

    /** The home screen came to the front: it shows its own alerts, so the overlay leaves. */
    static void homeInFront() {
        Guard g = running;
        if (g != null) g.handler.post(g.noticeDone);
    }

    /** The home screen is going behind an app while the finder runs: say it is still looking. */
    static void remind() {
        Guard g = running;
        if (g == null) return;
        Model.Finder f = Finder.get().snapshot();
        if (!f.active() || g.remindedFor == f.started) return;
        g.remindedFor = f.started;
        g.showNotice(Notice.searching(f, Finder.get().station(), System.currentTimeMillis()), 4500);
    }

    private void onFinder(Model.Finder f, Model.Station s) {
        if (Home.inFront) {
            if (notice != null && notice.showing()) notice.hide();
            return;
        }
        if (f.state == Model.Finder.FOUND) showNotice(Notice.found(f, s), 30000);
        else if (f.state == Model.Finder.TIMED_OUT) showNotice(Notice.timedOut(f, s), 12000);
        else if (f.state == Model.Finder.STOPPED && notice != null) notice.hide();
    }

    private void showNotice(Notice.Spec spec, long forMs) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (notice == null) {
            float d = getResources().getDisplayMetrics().density;
            NoticeView v = new NoticeView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.round(Notice.OVERLAY_W * d), Math.round(Notice.OVERLAY_H * d),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = Math.round(Notice.OVERLAY_X * d);
            lp.y = Math.round(Notice.OVERLAY_Y * d);
            lp.windowAnimations = 0;
            lp.setTitle("Lucent bike finder");
            try {
                wm.addView(v, lp);
            } catch (RuntimeException e) {
                return;
            }
            notice = v;
        }
        final NoticeView shown = notice;
        // Once it has slid away the window is removed, so nothing is composited over the film.
        notice.show(spec, new NoticeView.Gone() {
            @Override
            public void gone() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (notice != shown || shown.showing()) return;
                        try {
                            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(shown);
                        } catch (IllegalArgumentException ignored) {
                            // already gone
                        }
                        notice = null;
                    }
                });
            }
        });
        handler.removeCallbacks(noticeDone);
        handler.postDelayed(noticeDone, forMs);
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Explicit and immutable: no other app can redirect or fill in this intent.
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    /** Opens Amazon's own home screen and leaves it alone until another app is opened. */
    static void amazon(Context c) {
        pause();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            paused = false;
        }
    }

    @Override
    protected void onServiceConnected() {
        Crash.watch(this);
        running = this;
        View view = new View(this);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
        cover = view;
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uncover);
        handler.removeCallbacks(launch);
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
            // never registered
        }
        handler.removeCallbacks(noticeDone);
        for (View v : new View[] {cover, notice}) {
            if (v == null) continue;
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
        }
        notice = null;
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            onAmazonHome = false;
            paused = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            return;
        }
        // Only the package and class the system attaches to the event are read. They name the
        // window's owner, which another app cannot fake; no text or screen content is touched.
        onAmazonHome = cls != null && AMAZON_HOME.contentEquals(cls);
        if (paused || !onAmazonHome) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < REPEAT_MS) return;
        lastLaunch = now;
        // A Lucent that keeps crashing is not brought back: Amazon's home stays usable.
        if (Crash.tooMany(this)) return;
        launchWhenCovered();
    }

    /**
     * Shows the cover, then starts the launch once that window is visible to the window manager.
     * A cover already on screen gets no new draw pass, so the launch goes out at once; otherwise it
     * waits for the first pre-draw. A short fallback covers a window that never draws.
     */
    private void launchWhenCovered() {
        handler.removeCallbacks(launch);
        if (cover == null) {
            home(this);
            return;
        }
        boolean shown = cover.getVisibility() == View.VISIBLE;
        cover();
        if (shown) {
            home(this);
            return;
        }
        final View v = cover;
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                handler.removeCallbacks(launch);
                home(Guard.this);
                return true;
            }
        });
        handler.postDelayed(launch, LAUNCH_FALLBACK_MS);
    }

    @SuppressWarnings("deprecation")
    private void cover() {
        if (cover == null) return;
        Bitmap b = coverImage;
        if (b != null && !b.isRecycled()) {
            if (b != coverShown) {
                cover.setBackground(new BitmapDrawable(getResources(), b));
                coverShown = b;
            }
        } else {
            cover.setBackgroundColor(0xFF0B0D12);
            coverShown = null;
        }
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    /** The system interrupting feedback: the cover never outstays it. */
    @Override
    public void onInterrupt() {
        uncover();
    }
}
