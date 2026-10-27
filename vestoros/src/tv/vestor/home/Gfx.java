package tv.vestor.home;

/**
 * The drawing surface the {@link Scene} paints on. Android implements it with Canvas and
 * shaders ({@code AndroidGfx}); the desktop preview implements it with Java2D, so the same
 * scene code renders on both. All coordinates are pixels.
 *
 * Images are opaque handles: a Bitmap on Android, a BufferedImage in the preview.
 */
interface Gfx {
    int LEFT = 0, CENTER = 1, RIGHT = 2;

    /**
     * Font faces bundled in assets/fonts: Inter and Inter Display for the interface, Newsreader
     * Display (an editorial serif) for big numerals, titles and accents, Geist Mono for small
     * labels.
     */
    int DISPLAY_LIGHT = 0, DISPLAY_SEMIBOLD = 1, TEXT_REGULAR = 2, TEXT_MEDIUM = 3, TEXT_SEMIBOLD = 4,
            SERIF_LIGHT = 5, SERIF = 6, SERIF_ITALIC = 7, MONO = 8;
    int FACES = 9;
    String[] FONT_FILES = {"InterDisplay-Light.ttf", "InterDisplay-SemiBold.ttf", "Inter-Regular.ttf", "Inter-Medium.ttf",
        "Inter-SemiBold.ttf", "NewsreaderDisplay-Light.ttf", "NewsreaderDisplay-Regular.ttf", "NewsreaderDisplay-Italic.ttf",
        "GeistMono-Medium.ttf"};

    int save();

    void restore(int count);

    void translate(float dx, float dy);

    /** Uniform scale around a pivot. */
    void scale(float s, float px, float py);

    /** A small perspective tilt around a pivot, in degrees. The preview approximates it. */
    void tilt(float degX, float degY, float px, float py);

    void clipRect(float l, float t, float r, float b);

    void fillRect(float l, float t, float r, float b, int argb);

    void fillRoundRect(float l, float t, float r, float b, float rad, int argb);

    /**
     * A rounded rectangle filled with a linear gradient from (x0,y0) to (x1,y1). Gradient colour
     * arrays are constants and their overall opacity is {@code alpha} (0..1), so an
     * implementation can cache one shader per array and only move it.
     */
    void fillRoundRectLinear(float l, float t, float r, float b, float rad,
            float x0, float y0, float x1, float y1, int[] colors, float[] stops, float alpha);

    void strokeRoundRectLinear(float l, float t, float r, float b, float rad, float width,
            float x0, float y0, float x1, float y1, int[] colors, float[] stops, float alpha);

    /** A full rectangle filled with a linear gradient. */
    void fillRectLinear(float l, float t, float r, float b,
            float x0, float y0, float x1, float y1, int[] colors, float[] stops, float alpha);

    /**
     * Fills a rounded rectangle with {@code img}, where the image as a whole is mapped onto
     * the rectangle (il,it)-(ir,ib). This is how glass samples the blurred wallpaper and how
     * app banners get anti-aliased rounded corners.
     */
    void fillRoundRectImage(Object img, float il, float it, float ir, float ib,
            float l, float t, float r, float b, float rad, int alpha);

    /** Like {@link #fillRoundRectImage} but only the band {@code inset} wide inside the edge. */
    void fillRingImage(Object img, float il, float it, float ir, float ib,
            float l, float t, float r, float b, float rad, float inset, int alpha);

    /** Draws the whole image stretched to the rectangle. */
    void drawImage(Object img, float l, float t, float r, float b, int alpha);

    /** Draws the image's pixel rectangle (sl, st)-(sr, sb) stretched to the rectangle. */
    void drawImagePart(Object img, int sl, int st, int sr, int sb, float l, float t, float r, float b, int alpha);

    void fillCircle(float cx, float cy, float rad, int argb);

    void strokeCircle(float cx, float cy, float rad, float width, int argb);

    void line(float x0, float y0, float x1, float y1, float width, int argb);

    /** A filled polygon; {@code xy} holds x0,y0,x1,y1,... */
    void fillPolygon(float[] xy, int argb);

    void strokePolygon(float[] xy, float width, int argb);

    /** An open polyline with round caps and joins; {@code xy} holds x0,y0,x1,y1,... */
    void polyline(float[] xy, float width, int argb);

    /** A circular arc with round caps. Angles in degrees, 0 at 3 o'clock, clockwise positive. */
    void arc(float cx, float cy, float rad, float startDeg, float sweepDeg, float width, int argb);

    void strokeRoundRect(float l, float t, float r, float b, float rad, float width, int argb);

    /** A circle filled with a linear gradient (constant colour array, as above). */
    void fillCircleLinear(float cx, float cy, float rad, float x0, float y0, float x1, float y1,
            int[] colors, float[] stops, float alpha);

    /** A transparent image of w x h pixels that can be drawn into with {@link #into}. */
    Object offscreen(int w, int h);

    /** A Gfx whose drawing lands in {@code offscreen} (from {@link #offscreen}). */
    Gfx into(Object offscreen);

    /** Makes an offscreen image fully transparent again. */
    void clear(Object offscreen);

    /** Creates an image from ARGB pixels (used for pre-rendered shadows). */
    Object image(int[] argb, int w, int h);

    int imageWidth(Object img);

    int imageHeight(Object img);

    /** Text in one of the bundled faces. {@code tracking} is in ems, as Android letterSpacing. */
    void text(String s, int face, float sizePx, float tracking, float x, float baseline,
            int argb, int align, float shadowAlpha);

    float measure(String s, int face, float sizePx, float tracking);

    /** Distance from the baseline to the top of capital letters, positive. */
    float capHeight(int face, float sizePx);
}
