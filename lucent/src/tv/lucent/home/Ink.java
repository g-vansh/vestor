package tv.lucent.home;

/**
 * A drawn note, rendered into an offscreen image as if a pen were writing it: each frame adds
 * only the segments drawn since the last one, so even a 20,000 point drawing costs a few line
 * segments per frame and then nothing at all. The paper is not in the image (the card draws it),
 * so one image serves any card size.
 */
final class Ink {
    /** How long the writing takes on screen, from 1.2 s for a squiggle to 3.2 s for a page. */
    static float seconds(Notes.Note n) {
        return (float) Math.max(1.2, Math.min(3.2, n.length() / 9000.0));
    }

    final Notes.Note note;
    private final int w, h;
    private Object img;
    private Gfx canvas;
    private final double total;
    private double drawn;
    private int stroke, point;
    private boolean done;

    Ink(Notes.Note note, int w, int h) {
        this.note = note;
        this.w = Math.max(16, w);
        this.h = Math.max(12, h);
        this.total = Math.max(1, note.length());
    }

    /** The image, written up to {@code progress} (0..1). A progress of 1 finishes it at once. */
    Object at(Gfx g, float progress) {
        if (img == null) {
            img = g.offscreen(w, h);
            canvas = g.into(img);
            drawn = 0;
            stroke = 0;
            point = 0;
            done = note.strokes.isEmpty();
        }
        if (done) return img;
        double want = progress >= 1 ? Double.MAX_VALUE : total * Math.max(0, progress);
        float sx = w / 10000f, sy = h / 10000f;
        while (stroke < note.strokes.size() && drawn < want) {
            int[] s = note.strokes.get(stroke);
            int color = Notes.ink(s[0], note.paper);
            float width = Math.max(1.5f, s[1] / 1000f * w);
            int n = (s.length - 2) / 2;
            if (n == 1) {
                // A dot.
                canvas.fillCircle(s[2] * sx, s[3] * sy, width / 2, color);
                stroke++;
                point = 0;
                continue;
            }
            if (point == 0) point = 1;
            while (point < n && drawn < want) {
                int i = 2 + point * 2;
                float x0 = s[i - 2] * sx, y0 = s[i - 1] * sy, x1 = s[i] * sx, y1 = s[i + 1] * sy;
                canvas.line(x0, y0, x1, y1, width, color);
                drawn += Math.hypot(s[i] - s[i - 2], (s[i + 1] - s[i - 1]) * 0.75);
                point++;
            }
            if (point >= n) {
                stroke++;
                point = 0;
            }
        }
        if (stroke >= note.strokes.size()) done = true;
        return img;
    }

    boolean done() {
        return done;
    }

    /** Where the pen is now, as a fraction of the box (for a glint at the nib), or null. */
    float[] nib() {
        if (done || stroke >= note.strokes.size()) return null;
        int[] s = note.strokes.get(stroke);
        int i = 2 + Math.max(0, Math.min((s.length - 2) / 2 - 1, point)) * 2;
        return new float[] {s[i] / 10000f, s[i + 1] / 10000f};
    }
}
