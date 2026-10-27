package tv.vestor.home;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;

/**
 * The single view the home screen is drawn in. It maps remote keys to {@link Scene} keys (the
 * D-pad, OK, Back, Menu and the three media keys: Home, the microphone, volume and the app
 * buttons never reach an app on Fire TV), runs the frame loop (60 fps while something springs, 30 fps for the slow wallpaper drift,
 * nothing when the screen is hidden).
 */
final class HomeView extends View {
    private static final long LONG_PRESS_MS = 550;

    private final Scene scene;
    private final AndroidGfx gfx;
    private boolean running;
    private long lastFrame;
    private long okDown;
    private boolean okHeld, longFired, drawn;

    HomeView(Context c, Scene scene) {
        super(c);
        this.scene = scene;
        this.gfx = new AndroidGfx(c.getAssets());
        setFocusable(true);
        setFocusableInTouchMode(true);
        setSoundEffectsEnabled(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void start() {
        running = true;
        lastFrame = 0;
        requestFocus();
        invalidate();
    }

    void stop() {
        running = false;
        removeCallbacks(frame);
    }

    /** The drawing surface's Gfx, for images the scene makes once (QR codes). */
    Gfx gfx() {
        return gfx;
    }

    void kick() {
        lastFrame = lastFrame == 0 ? 0 : SystemClock.uptimeMillis() - 16;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scene.setSize(w, h, getResources().getDisplayMetrics().density);
    }

    /** The one pending redraw. A single runnable means kicks never stack into extra timers. */
    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        scene.setUptime(SystemClock.elapsedRealtime());
        boolean moving = scene.step(dt, System.currentTimeMillis());
        gfx.begin(canvas);
        scene.draw(gfx, System.currentTimeMillis());
        if (!drawn) {
            drawn = true;
            Crash.drawn(getContext());
        }
        removeCallbacks(frame);
        if (!running) return;
        if (moving) {
            postInvalidateOnAnimation();
        } else {
            // 25 ms lands on the second vsync, a true 30 fps for the drift; else once a second.
            postDelayed(frame, scene.drifting() ? 25 : scene.ticking() ? 250 : 1000);
        }
    }

    private static int map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return Scene.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return Scene.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP:
                return Scene.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return Scene.DOWN;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return Scene.OK;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return Scene.BACK;
            case KeyEvent.KEYCODE_MENU:
                return Scene.MENU;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return Scene.PLAY;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return Scene.REWIND;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return Scene.FORWARD;
            default:
                return 0;
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        int k = map(code);
        if (k == 0) return super.onKeyDown(code, e);
        if (k == Scene.OK) {
            if (e.getRepeatCount() == 0) {
                e.startTracking();
                okDown = e.getEventTime();
                okHeld = true;
                longFired = false;
            } else if (okHeld && !longFired && e.getEventTime() - okDown >= LONG_PRESS_MS) {
                longFired = true;
                send(Scene.LONG_OK);
            }
            return true;
        }
        // Held media keys repeat; one press is one track or one photo.
        if ((k == Scene.PLAY || k == Scene.REWIND || k == Scene.FORWARD) && e.getRepeatCount() > 0) return true;
        send(k);
        return true;
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (map(code) == Scene.OK && okHeld && !longFired) {
            longFired = true;
            send(Scene.LONG_OK);
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        int k = map(code);
        if (k == 0) return super.onKeyUp(code, e);
        if (k == Scene.OK) {
            if (okHeld && !longFired) send(Scene.OK);
            okHeld = false;
            longFired = false;
        }
        return true;
    }

    private void send(int k) {
        scene.key(k, System.currentTimeMillis());
        setContentDescription(scene.focusedLabel());
        kick();
    }

    /** Navigation feedback through the system, which honours Fire TV's Navigation Sounds setting. */
    void feedback(boolean click) {
        playSoundEffect(click ? SoundEffectConstants.CLICK : SoundEffectConstants.NAVIGATION_RIGHT);
    }
}
