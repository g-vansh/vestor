package tv.lucent.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Wallpapers: twelve bundled CC0 photographs (Wikimedia Commons, credits in NOTICE) and your own
 * album ({@link Album}), as chosen in Settings: nature, ours, or both, taking turns.
 *
 * Album photos are JPEGs Lucent's sandbox wrote, at most 3840 pixels on the long side, so this
 * class only ever decodes pictures that came out of the sandbox or out of the APK. A photo whose
 * shape is far from the screen's (a portrait from a phone, a square) is shown whole, over a soft,
 * enlarged copy of itself, the way a good photo frame does, rather than cropped to a strip.
 */
final class Walls {
    static final class Item {
        /** "n:" and the asset for nature, "p:" and the photo id for the album. */
        final String id, title, place, asset;
        final File file;
        final long taken;

        Item(String id, String title, String place, String asset, File file, long taken) {
            this.id = id;
            this.title = title;
            this.place = place;
            this.asset = asset;
            this.file = file;
            this.taken = taken;
        }

        boolean ours() {
            return file != null;
        }
    }

    private static final String[][] BUNDLED = {
        {"nature-01.jpg", "Moraine Lake", "Banff National Park, Canada"},
        {"nature-11.jpg", "The Matterhorn", "Valais, Switzerland"},
        {"nature-04.jpg", "Aurora over Flakstad", "Lofoten, Norway"},
        {"nature-06.jpg", "Cradle Mountain", "Dove Lake, Tasmania"},
        {"nature-03.jpg", "The Twelve Apostles", "Great Ocean Road, Australia"},
        {"nature-08.jpg", "Jigoku-numa", "Hakkoda Mountains, Japan"},
        {"nature-10.jpg", "Valle de la Luna", "Atacama Desert, Chile"},
        {"nature-05.jpg", "The Palouse", "Steptoe Butte, Washington"},
        {"nature-12.jpg", "Blue Mountains", "New South Wales, Australia"},
        {"nature-07.jpg", "Western Alps", "Near Grenoble, France"},
        {"nature-09.jpg", "Sankenbach Falls", "Black Forest, Germany"},
        {"nature-02.jpg", "Spruce Forest", "Holma, Sweden"},
    };

    /** Album photos are Lucent's own files, but a bound still keeps a damaged one harmless. */
    private static final int MAX_SIDE = 8192;
    private static final long MAX_PIXELS = 40_000_000L;

    private Walls() {}

    /**
     * The wallpapers for {@code album} ("nature", "ours" or "both"). With both, your photos and
     * the bundled ones take turns. With "ours" and an empty album, the bundled ones stand in.
     */
    static List<Item> list(String album, Album photos) {
        List<Item> nature = new ArrayList<>();
        for (String[] b : BUNDLED) nature.add(new Item("n:" + b[0], b[1], b[2], "wallpapers/" + b[0], null, 0));
        List<Item> ours = new ArrayList<>();
        if (photos != null && !"nature".equals(album)) {
            for (Album.Photo p : photos.list()) {
                String when = Album.when(p);
                boolean named = !p.caption.isEmpty();
                ours.add(new Item("p:" + p.id, named ? p.caption : when.isEmpty() ? "Our photos" : when, named ? when : "", null,
                        photos.file(p), p.taken));
            }
        }
        if ("nature".equals(album) || ours.isEmpty()) return nature;
        if ("ours".equals(album)) return ours;
        List<Item> both = new ArrayList<>();
        for (int i = 0; i < Math.max(ours.size(), nature.size()); i++) {
            if (i < ours.size()) both.add(ours.get(i));
            if (i < nature.size()) both.add(nature.get(i));
        }
        return both;
    }

    static int indexOf(List<Item> items, String id) {
        for (int i = 0; id != null && i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
        return -1;
    }

    /**
     * One of your photos taken on this day in an earlier year, or -1. Several match: the one
     * that changes with the date, so a run of anniversaries is not always the same photo.
     */
    static int onThisDay(List<Item> items, long now, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        int month = c.get(Calendar.MONTH), day = c.get(Calendar.DAY_OF_MONTH), year = c.get(Calendar.YEAR);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (!it.ours() || it.taken <= 0) continue;
            c.setTimeInMillis(it.taken);
            if (c.get(Calendar.MONTH) == month && c.get(Calendar.DAY_OF_MONTH) == day && c.get(Calendar.YEAR) < year) hits.add(i);
        }
        return hits.isEmpty() ? -1 : hits.get(year % hits.size());
    }

    /** "On this day in 2023" for a photo from an earlier year. */
    static String anniversary(Item it, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(it.taken);
        return "On this day in " + c.get(Calendar.YEAR);
    }

    /** Decodes, fits to the screen and prepares the blurred copy. Slow: call off the UI thread. */
    static Model.Wall load(Context c, Item item, int w, int h) {
        Bitmap src = decode(c, item, w, h);
        if (src == null) return null;
        Bitmap full;
        try {
            full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            src.recycle();
            return null;
        }
        Canvas canvas = new Canvas(full);
        Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
        float sw = src.getWidth(), sh = src.getHeight();
        if (whole(item, sw, sh, w, h)) {
            // A portrait or a panorama: the whole photo, over a soft, enlarged copy of itself.
            Bitmap tiny = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(sw / sh * 24)), 24, true);
            Matrix back = new Matrix();
            float bs = Math.max(w / (float) tiny.getWidth(), h / (float) tiny.getHeight()) * 1.15f;
            back.setScale(bs, bs);
            back.postTranslate((w - tiny.getWidth() * bs) / 2f, (h - tiny.getHeight() * bs) / 2f);
            canvas.drawBitmap(tiny, back, smooth);
            tiny.recycle();
            canvas.drawColor(0x66000000);
            float fs = Math.min(w * 0.92f / sw, h * 0.9f / sh);
            float fw = sw * fs, fh = sh * fs, fl = (w - fw) / 2f, ft = (h - fh) / 2f;
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 6; i >= 1; i--) {
                shade.setColor(Pixels.alpha(0xFF000000, 0.05f));
                float grow = i * 4f;
                canvas.drawRoundRect(fl - grow, ft - grow + i * 2f, fl + fw + grow, ft + fh + grow + i * 2f, grow + 6, grow + 6, shade);
            }
            Matrix m = new Matrix();
            m.setScale(fs, fs);
            m.postTranslate(fl, ft);
            canvas.drawBitmap(src, m, smooth);
        } else {
            float scale = Math.max(w / sw, h / sh);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - sw * scale) / 2f, (h - sh * scale) / 2f);
            canvas.drawBitmap(src, m, smooth);
        }
        src.recycle();

        int[] px = new int[w * h];
        full.getPixels(px, 0, w, 0, 0, w, h);
        Pixels.prepareWall(px, w, h);
        full.setPixels(px, 0, w, 0, 0, w, h);

        // Two halving steps average the pixels before the final size; one big step would alias.
        Bitmap half = Bitmap.createScaledBitmap(full, Pixels.BLUR_W * 2, Pixels.BLUR_H * 2, true);
        Bitmap small = Bitmap.createScaledBitmap(half, Pixels.BLUR_W, Pixels.BLUR_H, true);
        if (half != small) half.recycle();
        Bitmap blur = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (blur != small) small.recycle();
        int bw = blur.getWidth(), bh = blur.getHeight();
        int[] sp = new int[bw * bh];
        blur.getPixels(sp, 0, bw, 0, 0, bw, bh);
        Pixels.prepareBlur(sp, bw, bh);
        blur.setPixels(sp, 0, bw, 0, 0, bw, bh);

        Model.Wall wall = new Model.Wall();
        wall.full = full;
        wall.blur = blur;
        wall.blurPixels = sp;
        wall.blurW = bw;
        wall.blurH = bh;
        wall.title = item.title;
        wall.place = item.place;
        return wall;
    }

    /** Whether a photo is shown whole rather than cropped: one of ours, far from the screen's shape. */
    private static boolean whole(Item item, float sw, float sh, int w, int h) {
        float shape = (sw / sh) / (w / (float) h);
        // 4:3 and 3:2 photos fill the screen; portraits, squares and panoramas are shown whole.
        return item.ours() && (shape < 0.7f || shape > 1.6f);
    }

    /**
     * Decodes at the smallest power-of-two step that still covers the screen, then the final
     * scale is a filtered draw: sharp on a 1080p panel without holding a 4K bitmap.
     */
    private static Bitmap decode(Context c, Item item, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            read(c, item, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > MAX_SIDE || o.outHeight > MAX_SIDE
                    || (long) o.outWidth * o.outHeight > MAX_PIXELS) {
                return null;
            }
            o.inSampleSize = 1;
            if (whole(item, o.outWidth, o.outHeight, w, h)) {
                float fit = Math.min(w * 0.92f / o.outWidth, h * 0.9f / o.outHeight);
                while (o.inSampleSize * 2 * fit <= 1f) o.inSampleSize *= 2;
            } else {
                while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return read(c, item, o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap read(Context c, Item item, BitmapFactory.Options o) throws IOException {
        InputStream in = item.file != null ? new java.io.FileInputStream(item.file) : c.getAssets().open(item.asset);
        try {
            return BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
    }
}
