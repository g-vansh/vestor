package tv.lucent.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Your own photos for the wallpaper: uploaded from the control center or pushed into the media
 * folder's wallpapers/ by adb. Each is decoded in the sandbox ({@link Decoder}), scaled so its long
 * side is at most 3840 (4K), re-encoded as JPEG and kept in Lucent's private storage, where no
 * other app can read it. EXIF is gone, GPS included. A 480 pixel thumbnail is kept beside it for
 * the control center's gallery.
 *
 * The stick has little storage (about 1 GB free on this one), so the album stops at 300 photos or
 * 900 MB, and refuses new ones whenever less than 400 MB would be left free on the device.
 */
final class Album {
    static final class Photo {
        String id = "", caption = "", from = "", source = "";
        long taken, added;
        int w, h, bytes;
    }

    static final int MAX_PHOTOS = 300, LONG_SIDE = 3840, THUMB = 480, QUALITY = 88;
    static final long MAX_BYTES = 900L * 1024 * 1024, KEEP_FREE = 400L * 1024 * 1024;

    /** Why a photo was not added. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        final String why;

        Refused(String why) {
            super(why);
            this.why = why;
        }
    }

    private final Context context;
    private final File dir, index;
    private final List<Photo> photos = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();

    Album(Context c) {
        context = c.getApplicationContext();
        dir = new File(context.getFilesDir(), "photos");
        if (!dir.isDirectory()) dir.mkdirs();
        index = new File(dir, "index.json");
        load();
    }

    synchronized List<Photo> list() {
        return new ArrayList<>(photos);
    }

    synchronized int count() {
        return photos.size();
    }

    synchronized long bytes() {
        long n = 0;
        for (Photo p : photos) n += p.bytes;
        return n;
    }

    long free() {
        try {
            return new StatFs(context.getFilesDir().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    File file(Photo p) {
        return new File(dir, p.id + ".jpg");
    }

    /** A photo's JPEG (or its thumbnail), or null. */
    byte[] bytes(String id, boolean thumb) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return null;
        synchronized (this) {
            if (find(id) == null) return null;
        }
        return Live.readFile(new File(dir, id + (thumb ? ".t.jpg" : ".jpg")));
    }

    /** Adds an uploaded picture. Slow (the sandbox decodes it): call off the main thread. */
    Photo add(byte[] raw, String caption, long taken, String from) throws Refused {
        roomFor(raw.length);
        File tmp = new File(context.getCacheDir(), "incoming-" + Long.toHexString(random.nextLong()));
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(raw);
            } finally {
                out.close();
            }
            return addFile(tmp, caption, taken, from, "");
        } catch (IOException e) {
            throw new Refused("storage");
        } finally {
            if (!tmp.delete()) tmp.deleteOnExit();
        }
    }

    private Photo addFile(File src, String caption, long taken, String from, String source) throws Refused {
        Decode.Result r = Decode.run(context, src, Decoder.FIT, LONG_SIDE, LONG_SIDE, QUALITY);
        if (r.status == Decoder.NOT_IMAGE) throw new Refused("not a photo");
        if (r.status == Decoder.TOO_LARGE) throw new Refused("too large");
        if (r.jpeg == null) throw new Refused("could not read");
        roomFor(r.jpeg.length);
        Photo p = new Photo();
        byte[] id = new byte[16];
        random.nextBytes(id);
        StringBuilder h = new StringBuilder(32);
        for (byte b : id) h.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
        p.id = h.toString();
        p.caption = clean(caption);
        p.from = Keys.cleanName(from);
        p.source = source;
        p.taken = taken > 0 ? taken : r.taken;
        p.added = System.currentTimeMillis();
        p.w = r.width;
        p.h = r.height;
        p.bytes = r.jpeg.length;
        Live.write(new File(dir, p.id + ".jpg"), r.jpeg);
        byte[] thumb = thumbnail(r.jpeg);
        if (thumb != null) Live.write(new File(dir, p.id + ".t.jpg"), thumb);
        synchronized (this) {
            photos.add(0, p);
            save();
        }
        return p;
    }

    /** Refuses a photo that would break the album's limits or leave the stick short of space. */
    private void roomFor(long size) throws Refused {
        synchronized (this) {
            if (photos.size() >= MAX_PHOTOS) throw new Refused("album full");
            if (bytes() + size > MAX_BYTES) throw new Refused("album full");
        }
        if (free() - size < KEEP_FREE) throw new Refused("tv storage low");
    }

    /**
     * The thumbnail is made from Lucent's own re-encoded JPEG, never from the original, so the
     * main process only ever decodes pictures its sandbox wrote.
     */
    private static byte[] thumbnail(byte[] jpeg) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= THUMB) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            if (b == null) return null;
            float s = THUMB / (float) Math.max(b.getWidth(), b.getHeight());
            Bitmap t = s < 1 ? Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                    Math.max(1, Math.round(b.getHeight() * s)), true) : b;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            t.compress(Bitmap.CompressFormat.JPEG, 80, out);
            if (t != b) t.recycle();
            b.recycle();
            return out.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Brings in JPEG and PNG files pushed to the media folder's wallpapers/ (by adb), through the
     * sandbox like an upload. Each file is taken once, remembered by name, size and date.
     * Returns how many were added.
     */
    int importShared(File media) {
        File d = media == null ? null : new File(media, "wallpapers");
        if (d == null) return 0;
        if (!d.isDirectory()) {
            d.mkdirs();
            return 0;
        }
        // Read lazily and capped: a folder flooded with a million files costs 2,000 names, not a
        // million-entry array, and never a decode each.
        List<String> found = new ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> dir = java.nio.file.Files.newDirectoryStream(d.toPath())) {
            for (java.nio.file.Path f : dir) {
                if (found.size() >= 2000) break;
                found.add(f.getFileName().toString());
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        String[] names = found.toArray(new String[0]);
        Arrays.sort(names);
        int added = 0;
        for (String n : names) {
            if (added >= 20) break;
            String lower = n.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) continue;
            File f = new File(d, n);
            if (!f.isFile() || f.length() > Decoder.MAX_INPUT) continue;
            String key = Keys.sha(n + "|" + f.length() + "|" + f.lastModified()).substring(0, 32);
            synchronized (this) {
                boolean seen = false;
                for (Photo p : photos) seen |= key.equals(p.source);
                if (seen) continue;
            }
            String title = n.replaceAll("\\.[^.]+$", "").replace('_', ' ').replace('-', ' ');
            try {
                addFile(f, title, 0, "Shared folder", key);
                added++;
            } catch (Refused e) {
                if ("album full".equals(e.why) || "tv storage low".equals(e.why)) break;
            }
        }
        return added;
    }

    synchronized boolean remove(String id) {
        for (Iterator<Photo> it = photos.iterator(); it.hasNext(); ) {
            Photo p = it.next();
            if (p.id.equals(id)) {
                it.remove();
                new File(dir, p.id + ".jpg").delete();
                new File(dir, p.id + ".t.jpg").delete();
                save();
                return true;
            }
        }
        return false;
    }

    synchronized boolean caption(String id, String text) {
        Photo p = find(id);
        if (p == null) return false;
        p.caption = clean(text);
        save();
        return true;
    }

    synchronized Photo find(String id) {
        for (Photo p : photos) if (p.id.equals(id)) return p;
        return null;
    }

    /** "June 2024", from when it was taken, or when it was added. */
    static String when(Photo p) {
        long t = p.taken > 0 ? p.taken : p.added;
        return t <= 0 ? "" : new SimpleDateFormat("MMMM yyyy", Locale.US).format(new Date(t));
    }

    private static String clean(String s) {
        String c = Text.clean(s);
        return c.length() > 60 ? c.substring(0, 60).trim() : c;
    }

    synchronized String json(int max) {
        Jw w = new Jw().arr();
        for (int i = 0; i < photos.size() && i < max; i++) {
            Photo p = photos.get(i);
            w.obj().put("id", p.id).put("caption", p.caption).put("taken", p.taken).put("added", p.added).put("from", p.from)
                    .put("w", p.w).put("h", p.h).end();
        }
        return w.endArr().toString();
    }

    private void save() {
        Jw w = new Jw().obj().key("photos").arr();
        for (Photo p : photos) {
            w.obj().put("id", p.id).put("caption", p.caption).put("from", p.from).put("source", p.source).put("taken", p.taken)
                    .put("added", p.added).put("w", p.w).put("h", p.h).put("bytes", p.bytes).end();
        }
        Live.write(index, w.endArr().end().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void load() {
        byte[] b = Live.readFile(index);
        if (b == null) return;
        try {
            List<Object> list = Json.arr(Json.obj(Json.parse(new String(b, java.nio.charset.StandardCharsets.UTF_8))), "photos");
            if (list == null) return;
            for (Object o : list) {
                Map<String, Object> m = Json.obj(o);
                Photo p = new Photo();
                p.id = Json.str(m, "id", "");
                if (!p.id.matches("[0-9a-f]{32}") || !new File(dir, p.id + ".jpg").isFile()) continue;
                p.caption = clean(Json.str(m, "caption", ""));
                p.from = Keys.cleanName(Json.str(m, "from", ""));
                p.source = Json.str(m, "source", "");
                if (!p.source.matches("[0-9a-f]{0,32}")) p.source = "";
                p.taken = (long) Json.num(m, "taken", 0);
                p.added = (long) Json.num(m, "added", 0);
                p.w = Json.integer(m, "w", 0);
                p.h = Json.integer(m, "h", 0);
                p.bytes = Json.integer(m, "bytes", 0);
                photos.add(p);
                if (photos.size() >= MAX_PHOTOS) break;
            }
        } catch (RuntimeException | StackOverflowError e) {
            photos.clear();
        }
    }
}
