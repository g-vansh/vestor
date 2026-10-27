package tv.vestor.home;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

/**
 * The bike finder's alert over another app, drawn by {@link Notice} in the guard's accessibility
 * overlay window. It never takes focus or touches, so the film underneath keeps the remote. It
 * animates only while entering, pulsing or leaving; the rest of the time it is a still layer.
 */
final class NoticeView extends View {
    interface Gone {
        void gone();
    }

    private final AndroidGfx gfx;
    private final Spring appear = new Spring(0, 0.42f, 0.12f);
    private Notice.Spec spec;
    private long shownAt, lastFrame;
    private Gone onGone;

    NoticeView(Context c) {
        super(c);
        gfx = new AndroidGfx(c.getAssets());
        setWillNotDraw(false);
    }

    void show(Notice.Spec s, Gone gone) {
        boolean fresh = spec == null || appear.target < 0.5f || s.kind != spec.kind;
        spec = s;
        onGone = gone;
        if (fresh) {
            shownAt = SystemClock.uptimeMillis();
            appear.to(1);
        }
        lastFrame = 0;
        invalidate();
    }

    void hide() {
        appear.to(0);
        lastFrame = 0;
        invalidate();
    }

    boolean showing() {
        return appear.target > 0.5f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (spec == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean moving = appear.step(dt, false);
        float d = getResources().getDisplayMetrics().density;
        float t = (now - shownAt) / 1000f;
        gfx.begin(canvas);
        Notice.drawOverlay(gfx, getWidth(), getHeight(), d, spec, appear.value, t);
        boolean pulsing = t < Notice.PULSE_SECONDS;
        if (moving || pulsing) {
            postInvalidateOnAnimation();
        } else if (appear.value < 0.01f && appear.target < 0.5f && onGone != null) {
            Gone g = onGone;
            onGone = null;
            g.gone();
        }
    }
}
