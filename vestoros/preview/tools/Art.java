import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Vestor's own mark: a lens of glass over a night aurora, catching one spark of light on its
 * rim. Drawn at four times the size and scaled down, so the edges are clean at every size.
 *
 *   java Art.java res          writes res/drawable-xhdpi/{icon,banner}.png and xxxhdpi/icon.png
 */
public class Art {
    private static final int SS = 4;

    public static void main(String[] a) throws Exception {
        File res = new File(a.length > 0 ? a[0] : "res");
        Font display = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/InterDisplay-SemiBold.ttf"));
        new File(res, "drawable-xhdpi").mkdirs();
        new File(res, "drawable-xxxhdpi").mkdirs();
        BufferedImage icon = icon(512);
        ImageIO.write(down(icon, 96), "png", new File(res, "drawable-xhdpi/icon.png"));
        ImageIO.write(down(icon, 192), "png", new File(res, "drawable-xxxhdpi/icon.png"));
        ImageIO.write(banner(640, 360, display), "png", new File(res, "drawable-xhdpi/banner.png"));
        if (a.length > 1) {
            ImageIO.write(icon, "png", new File(a[1], "vestoros-icon-512.png"));
            ImageIO.write(banner(1280, 720, display), "png", new File(a[1], "vestoros-banner-1280.png"));
        }
    }

    private static Graphics2D pen(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    /** The app icon: the scene in a squircle-cornered square. */
    static BufferedImage icon(int size) {
        int s = size * SS;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = pen(img);
        Shape tile = new RoundRectangle2D.Float(0, 0, s, s, s * 0.45f, s * 0.45f);
        g.setClip(tile);
        sky(g, s, s);
        lens(g, s * 0.5f, s * 0.52f, s * 0.29f);
        g.setClip(null);
        g.setStroke(new BasicStroke(s * 0.006f));
        g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 90), s, s, new Color(255, 255, 255, 10)));
        g.draw(new RoundRectangle2D.Float(s * 0.003f, s * 0.003f, s * 0.994f, s * 0.994f, s * 0.45f, s * 0.45f));
        g.dispose();
        return down(img, size);
    }

    /** The TV banner: the lens at left, the name beside it. */
    static BufferedImage banner(int w, int h, Font font) {
        int sw = w * SS, sh = h * SS;
        BufferedImage img = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = pen(img);
        sky(g, sw, sh);
        float r = sh * 0.27f, gap = sw * 0.07f;
        Font f = font.deriveFont(sh * 0.2f);
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout t = new TextLayout("VestorOS", f, frc);
        // The lens and the name are centred as one group, the name set smaller if it would crowd the edges.
        float room = sw * 0.86f - 2 * r - gap;
        if (t.getAdvance() > room) t = new TextLayout("VestorOS", f.deriveFont(f.getSize2D() * room / t.getAdvance()), frc);
        float lx = (sw - 2 * r - gap - t.getAdvance()) / 2f + r, ly = sh * 0.5f;
        lens(g, lx, ly, r);
        float tx = lx + r + gap;
        float ty = ly + (float) t.getBounds().getHeight() / 2f;
        g.setColor(new Color(0, 0, 0, 70));
        t.draw(g, tx, ty + sh * 0.006f);
        g.setPaint(new GradientPaint(0, ty - sh * 0.14f, new Color(255, 255, 255), 0, ty, new Color(226, 232, 255)));
        g.fill(t.getOutline(AffineTransform.getTranslateInstance(tx, ty)));
        g.dispose();
        return down(img, w, h);
    }

    /** Night sky with an aurora: deep blue-violet, three soft blooms of light. */
    private static void sky(Graphics2D g, float w, float h) {
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(w * 0.4f, h),
                new float[] {0f, 0.55f, 1f}, new Color[] {new Color(0x101A3F), new Color(0x0B0F26), new Color(0x05060D)}));
        g.fill(new Rectangle2D.Float(0, 0, w, h));
        float m = Math.max(w, h);
        bloom(g, w * 0.16f, h * 0.18f, m * 0.62f, new Color(0x3A6BFF), 0.62f);
        bloom(g, w * 0.88f, h * 0.24f, m * 0.58f, new Color(0xA64DFF), 0.5f);
        bloom(g, w * 0.55f, h * 1.02f, m * 0.7f, new Color(0x19D3B5), 0.44f);
        bloom(g, w * 0.5f, h * 0.5f, m * 0.3f, new Color(0x6E8BFF), 0.18f);
    }

    private static void bloom(Graphics2D g, float cx, float cy, float rad, Color c, float a) {
        Color in = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * a));
        Color mid = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * a * 0.35f));
        Color out = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), rad, new float[] {0f, 0.45f, 1f}, new Color[] {in, mid, out}));
        g.fill(new Rectangle2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
    }

    /**
     * The lens: a disc of glass that brightens what is behind it, a refracting band inside its
     * edge, a rim lit from the upper left, a crescent of reflected light and one four-point spark.
     */
    private static void lens(Graphics2D g, float cx, float cy, float r) {
        Ellipse2D disc = new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
        // Soft shadow under the glass.
        for (int i = 6; i >= 1; i--) {
            float sr = r * (1 + i * 0.035f);
            g.setColor(new Color(0, 0, 0, 10));
            g.fill(new Ellipse2D.Float(cx - sr, cy - sr + r * 0.08f, sr * 2, sr * 2));
        }
        // Body: frosted, brighter at the top.
        g.setPaint(new LinearGradientPaint(new Point2D.Float(cx, cy - r), new Point2D.Float(cx, cy + r), new float[] {0f, 0.5f, 1f},
                new Color[] {new Color(255, 255, 255, 92), new Color(214, 226, 255, 38), new Color(170, 200, 255, 30)}));
        g.fill(disc);
        // Light caught inside the glass: a soft glow towards the lower right.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx + r * 0.25f, cy + r * 0.35f), r * 0.9f, new float[] {0f, 1f},
                new Color[] {new Color(120, 235, 220, 70), new Color(120, 235, 220, 0)}));
        g.fill(disc);
        // Refracting band just inside the edge.
        Area band = new Area(disc);
        float ir = r * 0.86f;
        band.subtract(new Area(new Ellipse2D.Float(cx - ir, cy - ir, ir * 2, ir * 2)));
        g.setPaint(new LinearGradientPaint(new Point2D.Float(cx - r, cy - r), new Point2D.Float(cx + r, cy + r), new float[] {0f, 0.5f, 1f},
                new Color[] {new Color(255, 255, 255, 70), new Color(255, 255, 255, 8), new Color(255, 255, 255, 46)}));
        g.fill(band);
        // A crescent of reflected light along the upper left.
        Area crescent = new Area(new Ellipse2D.Float(cx - r * 0.8f, cy - r * 0.8f, r * 1.6f, r * 1.6f));
        crescent.subtract(new Area(new Ellipse2D.Float(cx - r * 0.66f, cy - r * 0.62f, r * 1.6f, r * 1.6f)));
        g.setPaint(new LinearGradientPaint(new Point2D.Float(cx - r, cy - r), new Point2D.Float(cx, cy), new float[] {0f, 1f},
                new Color[] {new Color(255, 255, 255, 200), new Color(255, 255, 255, 0)}));
        g.fill(crescent);
        // Specular rim: bright where the light comes from, fading round the disc.
        g.setStroke(new BasicStroke(r * 0.035f));
        g.setPaint(new LinearGradientPaint(new Point2D.Float(cx - r, cy - r), new Point2D.Float(cx + r, cy + r),
                new float[] {0f, 0.35f, 0.65f, 1f}, new Color[] {new Color(255, 255, 255, 240), new Color(255, 255, 255, 60),
                    new Color(255, 255, 255, 20), new Color(255, 255, 255, 120)}));
        float rr = r * 0.985f;
        g.draw(new Ellipse2D.Float(cx - rr, cy - rr, rr * 2, rr * 2));
        // The spark, where the rim meets the light.
        double ang = Math.toRadians(-48);
        float sx = cx + (float) Math.cos(ang) * r * 0.98f, sy = cy + (float) Math.sin(ang) * r * 0.98f;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(sx, sy), r * 0.42f, new float[] {0f, 1f},
                new Color[] {new Color(255, 255, 255, 150), new Color(255, 255, 255, 0)}));
        g.fill(new Ellipse2D.Float(sx - r * 0.42f, sy - r * 0.42f, r * 0.84f, r * 0.84f));
        g.setColor(Color.WHITE);
        g.fill(spark(sx, sy, r * 0.2f));
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static Shape spark(float cx, float cy, float s) {
        Path2D.Float p = new Path2D.Float();
        float k = s * 0.16f;
        p.moveTo(cx, cy - s);
        p.quadTo(cx + k, cy - k, cx + s, cy);
        p.quadTo(cx + k, cy + k, cx, cy + s);
        p.quadTo(cx - k, cy + k, cx - s, cy);
        p.quadTo(cx - k, cy - k, cx, cy - s);
        p.closePath();
        return p;
    }

    static BufferedImage down(BufferedImage src, int size) {
        return down(src, size, size);
    }

    /** Area-averaged downscale in halving steps, for clean anti-aliasing. */
    static BufferedImage down(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            BufferedImage next = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, next.getWidth(), next.getHeight(), null);
            g.dispose();
            cur = next;
        }
        if (cur.getWidth() == w && cur.getHeight() == h) return cur;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(cur, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
