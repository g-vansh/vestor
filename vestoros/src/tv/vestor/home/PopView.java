package tv.vestor.home;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

/**
 * A moment drawn over another app by the HOME guard: a note writing itself, hearts rising, or
 * the bike alarm. Notes and hearts never take the remote or touch the film. The alarm does take
 * the remote, on purpose: any button (OK, Back, the D-pad) stops it, and the app underneath gets
 * the remote back as the card leaves.
 */
final class PopView extends View {
    interface Done {
        void done(PopView v);
    }

    static final int NOTE = 1, HEARTS = 2, ALARM = 3;

    final int kind;
    private final AndroidGfx gfx;
    private final Spring appear = new Spring(0, 0.45f, 0.1f);
    private final long shownAt = SystemClock.uptimeMillis();
    private long lastFrame, until;
    private final Done done;
    private Notes.Note note;
    private Ink ink;
    private String heartKind = "hearts";
    private Model.Finder finder;
    private Model.Station station;
    private final boolean h24;
    private boolean leaving;

    PopView(Context c, int kind, long showMs, boolean h24, Done done) {
        super(c);
        this.kind = kind;
        this.h24 = h24;
        this.done = done;
        gfx = new AndroidGfx(c.getAssets());
        until = shownAt + showMs;
        appear.to(1);
        setWillNotDraw(false);
        if (kind == ALARM) {
            setFocusable(true);
            setFocusableInTouchMode(true);
        }
    }

    void note(Notes.Note n) {
        note = n;
        float d = getResources().getDisplayMetrics().density;
        ink = new Ink(n, Math.round(NOTE_DRAW * d), Math.round(NOTE_DRAW * 0.75f * d));
    }

    void hearts(String k) {
        heartKind = k;
    }

    void alarm(Model.Finder f, Model.Station s) {
        finder = f;
        station = s;
    }

    /** Starts the leaving animation; the window goes once it has slid away. */
    void leave() {
        leaving = true;
        appear.to(0);
        invalidate();
    }

    static final float NOTE_DRAW = 360;

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (!leaving && kind != ALARM && now > until) leave();
        boolean moving = appear.step(dt, false);
        float d = getResources().getDisplayMetrics().density;
        float t = (now - shownAt) / 1000f;
        gfx.begin(canvas);
        float a = Math.max(0, Math.min(1, appear.value));
        int w = getWidth();
        boolean animating = moving;
        if (kind == NOTE && note != null) {
            float[] size = Pop.noteSize(note, NOTE_DRAW);
            float cw = size[0] * d, ch = size[1] * d;
            float l = w - cw - 28 * d + (1 - a) * 40 * d, top = 28 * d;
            int save = gfx.save();
            gfx.scale(0.94f + 0.06f * a, l + cw, top);
            float progress = Math.min(1, Math.max(0, (t - 0.35f) / Ink.seconds(note)));
            Pop.note(gfx, note, ink, l, top, l + cw, top + ch, d, progress, a, t, h24);
            gfx.restore(save);
            animating |= progress < 1 || t < 10;
        } else if (kind == HEARTS) {
            Pop.hearts(gfx, heartKind, w - 360 * d, 0, w - 20 * d, getHeight(), d, t, shownAt);
            animating = t < Pop.HEARTS_SECONDS;
            if (!animating && !leaving) leave();
        } else if (kind == ALARM && finder != null) {
            float l = (w - Pop.ALARM_W * d) / 2, top = 40 * d - (1 - a) * 30 * d;
            Pop.alarm(gfx, finder, station, l, top, d, a, t, false, false);
            animating = true;
        }
        if (leaving && appear.value < 0.01f && (kind != HEARTS || t >= Pop.HEARTS_SECONDS)) {
            Done dn = done;
            if (dn != null) dn.done(this);
            return;
        }
        if (animating || leaving) postInvalidateOnAnimation();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (kind != ALARM) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        // Volume and power stay with the system.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
                || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Alarm.silence();
            leave();
        }
        return true;
    }
}
