package tv.vestor.home;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Gfx} on an Android Canvas. Built for a small GPU: every shader is created once and only
 * re-positioned with a local matrix, paths and rects are reused, and nothing allocates per frame
 * except the odd measurement cache entry.
 */
final class AndroidGfx implements Gfx {
    private Canvas c;
    private final Typeface[] faces;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flat = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Matrix matrix = new Matrix();
    private final Camera camera = new Camera();
    private final Map<int[], LinearGradient> gradients = new IdentityHashMap<>();
    /**
     * Shaders by bitmap, least recently used dropped first. A BitmapShader holds its bitmap, so a
     * weak map would never release either; this keeps old wallpapers and banners collectable.
     */
    private final Map<Bitmap, BitmapShader> shaders = new LinkedHashMap<Bitmap, BitmapShader>(32, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Bitmap, BitmapShader> e) {
            return size() > 48;
        }
    };
    private final Map<Integer, Float> caps = new HashMap<>();
    private final Rect bounds = new Rect();

    AndroidGfx(AssetManager assets) {
        faces = new Typeface[FACES];
        for (int i = 0; i < FACES; i++) {
            try {
                faces[i] = Typeface.createFromAsset(assets, "fonts/" + FONT_FILES[i]);
            } catch (RuntimeException e) {
                faces[i] = Typeface.create(i >= SERIF_LIGHT && i <= SERIF_ITALIC ? "serif" : i == MONO ? "monospace" : "sans-serif",
                        i == 0 || i == SERIF_LIGHT ? Typeface.NORMAL : Typeface.BOLD);
            }
        }
        setup();
    }

    /** A Gfx for an offscreen bitmap, sharing the typefaces already loaded. */
    private AndroidGfx(Typeface[] shared, Canvas canvas) {
        faces = shared;
        c = canvas;
        setup();
    }

    private void setup() {
        ring.setStyle(Paint.Style.STROKE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setDither(true);
        flat.setDither(true);
        image.setDither(true);
        camera.setLocation(0, 0, -9.72f);
        text.setFontFeatureSettings("tnum, lnum");
    }

    @Override
    public Object offscreen(int w, int h) {
        return Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
    }

    @Override
    public Gfx into(Object offscreen) {
        return new AndroidGfx(faces, new Canvas((Bitmap) offscreen));
    }

    @Override
    public void clear(Object offscreen) {
        ((Bitmap) offscreen).eraseColor(0);
    }

    void begin(Canvas canvas) {
        c = canvas;
    }

    @Override
    public int save() {
        return c.save();
    }

    @Override
    public void restore(int count) {
        c.restoreToCount(count);
    }

    @Override
    public void translate(float dx, float dy) {
        c.translate(dx, dy);
    }

    @Override
    public void scale(float s, float px, float py) {
        c.scale(s, s, px, py);
    }

    @Override
    public void tilt(float degX, float degY, float px, float py) {
        camera.save();
        camera.rotateX(degX);
        camera.rotateY(degY);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-px, -py);
        matrix.postTranslate(px, py);
        c.concat(matrix);
    }

    @Override
    public void clipRect(float l, float t, float r, float b) {
        c.clipRect(l, t, r, b);
    }

    @Override
    public void fillRect(float l, float t, float r, float b, int argb) {
        flat.setShader(null);
        flat.setColor(argb);
        c.drawRect(l, t, r, b, flat);
    }

    @Override
    public void fillRoundRect(float l, float t, float r, float b, float rad, int argb) {
        fill.setShader(null);
        fill.setColor(argb);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, rad, rad, fill);
    }

    /** One shader per colour array, laid along the unit x axis and moved with its local matrix. */
    private LinearGradient gradient(int[] colors, float[] stops, float x0, float y0, float x1, float y1) {
        LinearGradient g = gradients.get(colors);
        if (g == null) {
            g = new LinearGradient(0, 0, 1, 0, colors, stops, Shader.TileMode.CLAMP);
            // Colour arrays are constants, so this stays small. A bound keeps a slip from leaking.
            if (gradients.size() > 256) gradients.clear();
            gradients.put(colors, g);
        }
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) len = 0.001f;
        matrix.reset();
        matrix.setScale(len, len);
        matrix.postRotate((float) Math.toDegrees(Math.atan2(dy, dx)));
        matrix.postTranslate(x0, y0);
        g.setLocalMatrix(matrix);
        return g;
    }

    private static int alpha255(float a) {
        return Math.max(0, Math.min(255, Math.round(a * 255)));
    }

    @Override
    public void fillRoundRectLinear(float l, float t, float r, float b, float rad, float x0, float y0, float x1, float y1,
            int[] colors, float[] stops, float alpha) {
        fill.setColor(0xFF000000);
        fill.setShader(gradient(colors, stops, x0, y0, x1, y1));
        fill.setAlpha(alpha255(alpha));
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, rad, rad, fill);
        fill.setShader(null);
    }

    @Override
    public void strokeRoundRectLinear(float l, float t, float r, float b, float rad, float width, float x0, float y0,
            float x1, float y1, int[] colors, float[] stops, float alpha) {
        stroke.setColor(0xFF000000);
        stroke.setStrokeWidth(width);
        stroke.setShader(gradient(colors, stops, x0, y0, x1, y1));
        stroke.setAlpha(alpha255(alpha));
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, rad, rad, stroke);
        stroke.setShader(null);
    }

    @Override
    public void fillRectLinear(float l, float t, float r, float b, float x0, float y0, float x1, float y1, int[] colors,
            float[] stops, float alpha) {
        flat.setColor(0xFF000000);
        flat.setShader(gradient(colors, stops, x0, y0, x1, y1));
        flat.setAlpha(alpha255(alpha));
        c.drawRect(l, t, r, b, flat);
        flat.setShader(null);
    }

    private BitmapShader shader(Bitmap bmp, float il, float it, float ir, float ib) {
        BitmapShader s = shaders.get(bmp);
        if (s == null) {
            s = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(bmp, s);
        }
        matrix.reset();
        matrix.setScale((ir - il) / bmp.getWidth(), (ib - it) / bmp.getHeight());
        matrix.postTranslate(il, it);
        s.setLocalMatrix(matrix);
        return s;
    }

    @Override
    public void fillRoundRectImage(Object img, float il, float it, float ir, float ib, float l, float t, float r, float b,
            float rad, int alpha) {
        if (!(img instanceof Bitmap) || ((Bitmap) img).isRecycled()) return;
        image.setShader(shader((Bitmap) img, il, it, ir, ib));
        image.setAlpha(Math.max(0, Math.min(255, alpha)));
        rect.set(l, t, r, b);
        if (rad <= 0) c.drawRect(rect, image);
        else c.drawRoundRect(rect, rad, rad, image);
        image.setShader(null);
    }

    /**
     * The edge band is drawn as a stroked rounded rectangle, a primitive the GPU draws with
     * analytic anti-aliasing, rather than an even-odd path that would be re-tessellated per frame.
     */
    @Override
    public void fillRingImage(Object img, float il, float it, float ir, float ib, float l, float t, float r, float b,
            float rad, float inset, int alpha) {
        if (!(img instanceof Bitmap) || ((Bitmap) img).isRecycled()) return;
        ring.setShader(shader((Bitmap) img, il, it, ir, ib));
        ring.setAlpha(Math.max(0, Math.min(255, alpha)));
        ring.setStrokeWidth(inset);
        float h = inset / 2;
        rect.set(l + h, t + h, r - h, b - h);
        float rr = Math.max(0, rad - h);
        c.drawRoundRect(rect, rr, rr, ring);
        ring.setShader(null);
    }

    @Override
    public void drawImage(Object img, float l, float t, float r, float b, int alpha) {
        if (!(img instanceof Bitmap) || ((Bitmap) img).isRecycled()) return;
        plain.setAlpha(Math.max(0, Math.min(255, alpha)));
        rect.set(l, t, r, b);
        c.drawBitmap((Bitmap) img, null, rect, plain);
    }

    private final Rect part = new Rect();

    @Override
    public void drawImagePart(Object img, int sl, int st, int sr, int sb, float l, float t, float r, float b, int alpha) {
        if (!(img instanceof Bitmap) || ((Bitmap) img).isRecycled()) return;
        plain.setAlpha(Math.max(0, Math.min(255, alpha)));
        part.set(sl, st, sr, sb);
        rect.set(l, t, r, b);
        c.drawBitmap((Bitmap) img, part, rect, plain);
    }

    @Override
    public void fillCircle(float cx, float cy, float rad, int argb) {
        fill.setShader(null);
        fill.setColor(argb);
        c.drawCircle(cx, cy, rad, fill);
    }

    @Override
    public void strokeCircle(float cx, float cy, float rad, float width, int argb) {
        stroke.setShader(null);
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        c.drawCircle(cx, cy, rad, stroke);
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float width, int argb) {
        stroke.setShader(null);
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        c.drawLine(x0, y0, x1, y1, stroke);
    }

    private void poly(float[] xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
    }

    @Override
    public void fillPolygon(float[] xy, int argb) {
        poly(xy);
        fill.setShader(null);
        fill.setColor(argb);
        c.drawPath(path, fill);
    }

    @Override
    public void strokePolygon(float[] xy, float width, int argb) {
        poly(xy);
        stroke.setShader(null);
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        c.drawPath(path, stroke);
    }

    @Override
    public void polyline(float[] xy, float width, int argb) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        stroke.setShader(null);
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        c.drawPath(path, stroke);
    }

    @Override
    public void arc(float cx, float cy, float rad, float startDeg, float sweepDeg, float width, int argb) {
        stroke.setShader(null);
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        rect.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(rect, startDeg, sweepDeg, false, stroke);
    }

    @Override
    public void strokeRoundRect(float l, float t, float r, float b, float rad, float width, int argb) {
        stroke.setShader(null);
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, rad, rad, stroke);
    }

    @Override
    public void fillCircleLinear(float cx, float cy, float rad, float x0, float y0, float x1, float y1, int[] colors,
            float[] stops, float alpha) {
        fill.setColor(0xFF000000);
        fill.setShader(gradient(colors, stops, x0, y0, x1, y1));
        fill.setAlpha(alpha255(alpha));
        c.drawCircle(cx, cy, rad, fill);
        fill.setShader(null);
    }

    @Override
    public Object image(int[] argb, int w, int h) {
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
    }

    @Override
    public int imageWidth(Object img) {
        return ((Bitmap) img).getWidth();
    }

    @Override
    public int imageHeight(Object img) {
        return ((Bitmap) img).getHeight();
    }

    private void font(int face, float size, float tracking) {
        text.setTypeface(faces[face]);
        text.setTextSize(size);
        text.setLetterSpacing(tracking);
    }

    @Override
    public void text(String s, int face, float size, float tracking, float x, float baseline, int argb, int align,
            float shadowAlpha) {
        if (s == null || s.isEmpty() || (argb >>> 24) == 0) return;
        font(face, size, tracking);
        float x0 = x;
        if (align != LEFT) {
            float w = text.measureText(s);
            x0 = align == CENTER ? x - w / 2 : x - w;
        }
        if (shadowAlpha > 0) {
            int a = Math.round(shadowAlpha * (argb >>> 24) * 0.55f);
            text.setShadowLayer(size * 0.06f, 0, size * 0.02f, Math.max(0, Math.min(255, a)) << 24);
        } else {
            text.clearShadowLayer();
        }
        text.setColor(argb);
        c.drawText(s, x0, baseline, text);
        if (shadowAlpha > 0) text.clearShadowLayer();
    }

    @Override
    public float measure(String s, int face, float size, float tracking) {
        if (s == null || s.isEmpty()) return 0;
        font(face, size, tracking);
        return text.measureText(s);
    }

    @Override
    public float capHeight(int face, float size) {
        int key = face * 100000 + Math.round(size * 10);
        Float v = caps.get(key);
        if (v == null) {
            font(face, size, 0);
            text.getTextBounds("H", 0, 1, bounds);
            v = (float) -bounds.top;
            caps.put(key, v);
        }
        return v;
    }
}
