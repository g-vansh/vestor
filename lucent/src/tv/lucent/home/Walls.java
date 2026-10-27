package tv.lucent.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

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
 * Album photos are laid out for the screen by Lucent's sandbox ({@link Decode}) and come back as
 * plain pixels, so the only pictures this process decodes are the twelve in the APK. A photo
 * whose shape is far from the screen's (a portrait from a phone, a square) is shown whole, over a
 * soft, enlarged copy of itself, the way a good photo frame does, rather than cropped to a strip.
 */
final class Walls {
    static final class Item {
        /** "n:" and the asset for nature, "p:" and the photo id for the album. */
        final String id, title, place, asset;
        final File file;
        final long taken;
        /** An album photo's size as the album recorded it, or 0. */
        final int w, h;

        Item(String id, String title, String place, String asset, File file, long taken, int w, int h) {
            this.id = id;
            this.title = title;
            this.place = place;
            this.asset = asset;
            this.file = file;
            this.taken = taken;
            this.w = w;
            this.h = h;
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

    /** The bundled pictures are Lucent's own, but a bound still keeps a damaged one harmless. */
    private static final int MAX_SIDE = 8192;
    private static final long MAX_PIXELS = 40_000_000L;

    private Walls() {}

    /**
     * The wallpapers for {@code album} ("nature", "ours" or "both"). With both, your photos and
     * the bundled ones take turns. With "ours" and an empty album, the bundled ones stand in.
     */
    static List<Item> list(String album, Album photos) {
        List<Item> nature = new ArrayList<>();
        for (String[] b : BUNDLED) nature.add(new Item("n:" + b[0], b[1], b[2], "wallpapers/" + b[0], null, 0, 0, 0));
        List<Item> ours = new ArrayList<>();
        if (photos != null && !"nature".equals(album)) {
            for (Album.Photo p : photos.list()) {
                String when = Album.when(p);
                boolean named = !p.caption.isEmpty();
                ours.add(new Item("p:" + p.id, named ? p.caption : when.isEmpty() ? "Our photos" : when, named ? when : "", null,
                        photos.file(p), p.taken, p.w, p.h));
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

    /**
     * Fits the picture to the screen and prepares the blurred copy. Album photos come from the
     * sandbox as pixels already laid out, whole or cropped. Slow: call off the UI thread.
     */
    static Model.Wall load(Context c, Item item, int w, int h) {
        Bitmap full = item.file != null ? Decode.pixels(c, item.file, whole(item, w, h) ? Layout.WHOLE : Layout.COVER, w, h)
                : bundled(c, item, w, h);
        if (full == null) return null;

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
    private static boolean whole(Item item, int w, int h) {
        return item.ours() && Layout.whole(item.w, item.h, w, h);
    }

    /** A bundled wallpaper, from the APK, decoded here and drawn to fill the screen. */
    private static Bitmap bundled(Context c, Item item, int w, int h) {
        Bitmap src = decode(c, item.asset, w, h);
        if (src == null) return null;
        try {
            Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            float[] at = Layout.box(Layout.COVER, src.getWidth(), src.getHeight(), w, h);
            Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
            new Canvas(full).drawBitmap(src, null, new RectF(at[0], at[1], at[0] + at[2], at[1] + at[3]), smooth);
            return full;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            src.recycle();
        }
    }

    /**
     * Decodes at the smallest power-of-two step that still covers the screen, then the final
     * scale is a filtered draw: sharp on a 1080p panel without holding a 4K bitmap.
     */
    private static Bitmap decode(Context c, String asset, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            read(c, asset, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > MAX_SIDE || o.outHeight > MAX_SIDE
                    || (long) o.outWidth * o.outHeight > MAX_PIXELS) {
                return null;
            }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return read(c, asset, o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap read(Context c, String asset, BitmapFactory.Options o) throws IOException {
        InputStream in = c.getAssets().open(asset);
        try {
            return BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
    }
}
