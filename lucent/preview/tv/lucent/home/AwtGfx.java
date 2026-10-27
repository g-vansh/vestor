package tv.lucent.home;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Java2D implementation of {@link Gfx} for the desktop preview. Not shipped. */
final class AwtGfx implements Gfx {
    private final Graphics2D g;
    private final List<AffineTransform> transforms = new ArrayList<>();
    private final List<Shape> clips = new ArrayList<>();
    private final Font[] faces = new Font[5];
    private final Map<String, Font> sized = new HashMap<>();

    AwtGfx(Graphics2D g, File fonts) throws Exception {
        this.g = g;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        String[] files = {"InterDisplay-Light.ttf", "InterDisplay-SemiBold.ttf", "Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf"};
        for (int i = 0; i < files.length; i++) faces[i] = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, files[i]));
    }

    private static Color color(int argb) {
        return new Color(argb, true);
    }

    private static Color[] colors(int[] c, float alpha) {
        Color[] out = new Color[c.length];
        for (int i = 0; i < c.length; i++) out[i] = color(Pixels.alpha(c[i], alpha));
        return out;
    }

    private void alpha(int a) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(255, a)) / 255f));
    }

    private void opaque() {
        g.setComposite(AlphaComposite.SrcOver);
    }

    @Override
    public int save() {
        transforms.add(g.getTransform());
        clips.add(g.getClip());
        return transforms.size() - 1;
    }

    @Override
    public void restore(int count) {
        while (transforms.size() > count) {
            g.setTransform(transforms.remove(transforms.size() - 1));
            g.setClip(clips.remove(clips.size() - 1));
        }
    }

    @Override
    public void translate(float dx, float dy) {
        g.translate(dx, dy);
    }

    @Override
    public void scale(float s, float px, float py) {
        g.translate(px, py);
        g.scale(s, s);
        g.translate(-px, -py);
    }

    @Override
    public void tilt(float degX, float degY, float px, float py) {
        // A shear stands in for perspective in the preview.
        g.translate(px, py);
        g.shear(degY * 0.003, degX * 0.003);
        g.translate(-px, -py);
    }

    @Override
    public void clipRect(float l, float t, float r, float b) {
        g.clip(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    @Override
    public void fillRect(float l, float t, float r, float b, int argb) {
        g.setPaint(color(argb));
        g.fill(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    private static RoundRectangle2D rr(float l, float t, float r, float b, float rad) {
        return new RoundRectangle2D.Float(l, t, r - l, b - t, rad * 2, rad * 2);
    }

    @Override
    public void fillRoundRect(float l, float t, float r, float b, float rad, int argb) {
        g.setPaint(color(argb));
        g.fill(rr(l, t, r, b, rad));
    }

    private LinearGradientPaint gradient(float x0, float y0, float x1, float y1, int[] c, float[] stops, float alpha) {
        if (Math.abs(x0 - x1) < 0.01f && Math.abs(y0 - y1) < 0.01f) y1 = y0 + 1;
        return new LinearGradientPaint(new Point2D.Float(x0, y0), new Point2D.Float(x1, y1), stops, colors(c, alpha));
    }

    @Override
    public void fillRoundRectLinear(float l, float t, float r, float b, float rad, float x0, float y0, float x1, float y1,
            int[] c, float[] stops, float alpha) {
        g.setPaint(gradient(x0, y0, x1, y1, c, stops, alpha));
        g.fill(rr(l, t, r, b, rad));
    }

    @Override
    public void strokeRoundRectLinear(float l, float t, float r, float b, float rad, float width, float x0, float y0,
            float x1, float y1, int[] c, float[] stops, float alpha) {
        g.setPaint(gradient(x0, y0, x1, y1, c, stops, alpha));
        g.setStroke(new BasicStroke(width));
        g.draw(rr(l, t, r, b, rad));
    }

    @Override
    public void fillRectLinear(float l, float t, float r, float b, float x0, float y0, float x1, float y1, int[] c,
            float[] stops, float alpha) {
        g.setPaint(gradient(x0, y0, x1, y1, c, stops, alpha));
        g.fill(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    @Override
    public void fillRoundRectImage(Object img, float il, float it, float ir, float ib, float l, float t, float r,
            float b, float rad, int alpha) {
        BufferedImage bi = (BufferedImage) img;
        Composite old = g.getComposite();
        alpha(alpha);
        g.setPaint(new TexturePaint(bi, new Rectangle2D.Float(il, it, ir - il, ib - it)));
        g.fill(rr(l, t, r, b, rad));
        g.setComposite(old);
    }

    @Override
    public void fillRingImage(Object img, float il, float it, float ir, float ib, float l, float t, float r, float b,
            float rad, float inset, int alpha) {
        BufferedImage bi = (BufferedImage) img;
        Area ring = new Area(rr(l, t, r, b, rad));
        ring.subtract(new Area(rr(l + inset, t + inset, r - inset, b - inset, Math.max(0, rad - inset))));
        Composite old = g.getComposite();
        alpha(alpha);
        g.setPaint(new TexturePaint(bi, new Rectangle2D.Float(il, it, ir - il, ib - it)));
        g.fill(ring);
        g.setComposite(old);
    }

    @Override
    public void drawImagePart(Object img, int sl, int st, int sr, int sb, float l, float t, float r, float b, int alpha) {
        if (sr <= sl || sb <= st || r <= l || b <= t) return;
        BufferedImage bi = ((BufferedImage) img).getSubimage(sl, st, sr - sl, sb - st);
        drawImage(bi, l, t, r, b, alpha);
    }

    @Override
    public void drawImage(Object img, float l, float t, float r, float b, int alpha) {
        BufferedImage bi = (BufferedImage) img;
        Composite old = g.getComposite();
        alpha(alpha);
        AffineTransform at = new AffineTransform();
        at.translate(l, t);
        at.scale((r - l) / bi.getWidth(), (b - t) / bi.getHeight());
        g.drawImage(bi, at, null);
        g.setComposite(old);
    }

    @Override
    public void fillCircle(float cx, float cy, float rad, int argb) {
        g.setPaint(color(argb));
        g.fill(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
    }

    @Override
    public void strokeCircle(float cx, float cy, float rad, float width, int argb) {
        g.setPaint(color(argb));
        g.setStroke(new BasicStroke(width));
        g.draw(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float width, int argb) {
        g.setPaint(color(argb));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(x0, y0, x1, y1));
    }

    private static Path2D poly(float[] xy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        return p;
    }

    @Override
    public void fillPolygon(float[] xy, int argb) {
        g.setPaint(color(argb));
        g.fill(poly(xy));
    }

    @Override
    public void strokePolygon(float[] xy, float width, int argb) {
        g.setPaint(color(argb));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(poly(xy));
    }

    @Override
    public void polyline(float[] xy, float width, int argb) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        g.setPaint(color(argb));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    @Override
    public void arc(float cx, float cy, float rad, float startDeg, float sweepDeg, float width, int argb) {
        g.setPaint(color(argb));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // Java2D measures angles counter-clockwise; Gfx (like Android) clockwise.
        g.draw(new java.awt.geom.Arc2D.Float(cx - rad, cy - rad, rad * 2, rad * 2, -startDeg, -sweepDeg, java.awt.geom.Arc2D.OPEN));
    }

    @Override
    public void strokeRoundRect(float l, float t, float r, float b, float rad, float width, int argb) {
        g.setPaint(color(argb));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(l, t, r - l, b - t, rad * 2, rad * 2));
    }

    @Override
    public void fillCircleLinear(float cx, float cy, float rad, float x0, float y0, float x1, float y1, int[] colors,
            float[] stops, float alpha) {
        if (Math.abs(x1 - x0) < 0.01f && Math.abs(y1 - y0) < 0.01f) y1 += 0.01f;
        g.setPaint(new LinearGradientPaint(new Point2D.Float(x0, y0), new Point2D.Float(x1, y1), stops, colors(colors, alpha)));
        g.fill(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
    }

    @Override
    public Object image(int[] argb, int w, int h) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, w, h, argb, 0, w);
        return bi;
    }

    @Override
    public int imageWidth(Object img) {
        return ((BufferedImage) img).getWidth();
    }

    @Override
    public int imageHeight(Object img) {
        return ((BufferedImage) img).getHeight();
    }

    private Font font(int face, float size, float tracking) {
        String key = face + ":" + size + ":" + tracking;
        Font f = sized.get(key);
        if (f == null) {
            Map<TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(TextAttribute.SIZE, size);
            attrs.put(TextAttribute.TRACKING, tracking);
            attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
            f = faces[face].deriveFont(attrs);
            sized.put(key, f);
        }
        return f;
    }

    @Override
    public void text(String s, int face, float size, float tracking, float x, float baseline, int argb, int align,
            float shadowAlpha) {
        if (s == null || s.isEmpty()) return;
        Font f = font(face, size, tracking);
        TextLayout layout = new TextLayout(s, f, g.getFontRenderContext());
        float w = layout.getAdvance();
        float x0 = align == CENTER ? x - w / 2 : align == RIGHT ? x - w : x;
        if (shadowAlpha > 0 && (argb >>> 24) > 0) {
            float a = shadowAlpha * ((argb >>> 24) / 255f);
            for (int i = 1; i <= 3; i++) {
                g.setPaint(new Color(0, 0, 0, Math.min(1f, a * 0.22f)));
                layout.draw(g, x0, baseline + i * size * 0.012f);
            }
        }
        g.setPaint(color(argb));
        layout.draw(g, x0, baseline);
    }

    @Override
    public float measure(String s, int face, float size, float tracking) {
        if (s == null || s.isEmpty()) return 0;
        return new TextLayout(s, font(face, size, tracking), g.getFontRenderContext()).getAdvance();
    }

    @Override
    public float capHeight(int face, float size) {
        TextLayout l = new TextLayout("H", font(face, size, 0), g.getFontRenderContext());
        return (float) -l.getBounds().getY();
    }

    void done() {
        opaque();
    }
}
