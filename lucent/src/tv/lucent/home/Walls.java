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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Wallpapers: twelve bundled CC0 photographs (Wikimedia Commons, credits in NOTICE) plus any
 * JPEG or PNG pushed to the app's media folder, {@code wallpapers/}.
 */
final class Walls {
    static final class Item {
        final String title, place, asset;
        final File file;

        Item(String title, String place, String asset, File file) {
            this.title = title;
            this.place = place;
            this.asset = asset;
            this.file = file;
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

    /**
     * Pictures in the media folder can be written by adb (and, on Android 11, by an app holding
     * the all-files permission), so they are read with care ({@link Images}): JPEG or PNG only,
     * at most 24 MB, 16384 pixels a side and 120 megapixels, decoded down to the screen's size.
     */
    private static final int MAX_FILE = 24 * 1024 * 1024;
    private static final int MAX_SIDE = 16384;
    private static final long MAX_PIXELS = 120_000_000L;

    private Walls() {}

    static List<Item> list(File media) {
        List<Item> out = new ArrayList<>();
        for (String[] b : BUNDLED) out.add(new Item(b[1], b[2], "wallpapers/" + b[0], null));
        File dir = media == null ? null : new File(media, "wallpapers");
        if (dir != null && !dir.exists()) dir.mkdirs();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            int taken = 0;
            for (File f : files) {
                // Plenty for a slideshow, and a folder flooded with files cannot slow HOME down.
                if (taken >= 200) break;
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) continue;
                if (!f.isFile() || f.length() > MAX_FILE) continue;
                String title = f.getName().replaceAll("\\.[^.]+$", "").replace('_', ' ').replace('-', ' ');
                title = title.replaceAll("[^\\p{L}\\p{N} ,.'()&]", "").trim();
                if (title.length() > 60) title = title.substring(0, 60).trim();
                out.add(new Item(title.isEmpty() ? "Your photo" : title, "", null, f));
                taken++;
            }
        }
        return out;
    }

    /** Decodes, crops to the screen and prepares the blurred copy. Slow: call off the UI thread. */
    static Model.Wall load(Context c, Item item, int w, int h) {
        Bitmap src = decode(c, item, w, h);
        if (src == null) return null;
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(full);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        canvas.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
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

    private static Bitmap decode(Context c, Item item, int w, int h) {
        if (item.file != null) return Images.decode(item.file, MAX_FILE, MAX_SIDE, MAX_PIXELS, w, h);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            read(c, item, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > MAX_SIDE || o.outHeight > MAX_SIDE
                    || (long) o.outWidth * o.outHeight > MAX_PIXELS) {
                return null;
            }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return read(c, item, o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A bundled wallpaper, from inside the APK. */
    private static Bitmap read(Context c, Item item, BitmapFactory.Options o) throws IOException {
        InputStream in = c.getAssets().open(item.asset);
        try {
            return BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
    }
}
