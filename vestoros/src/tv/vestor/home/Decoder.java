package tv.vestor.home;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Decodes pictures Vestor did not make (photos from the control center, files pushed into the
 * shared media folder, album photos and tile pictures on their way to the screen) in a sandbox,
 * and hands back only a fresh JPEG that Android's own encoder wrote, or raw pixels.
 *
 * This service runs in its own isolated process (android:isolatedProcess in the manifest): no
 * permissions, no files, no network, nothing of Vestor's. If a crafted picture ever exploited a
 * flaw in an image decoder, it would land there, with nothing to take and nothing to reach. The
 * main process passes in two file descriptors, one to read and one to write, and gets back a
 * status. It never decodes the original itself, and never decodes what this writes: a JPEG is
 * only stored and served to phones, and pixels are only copied into a bitmap.
 *
 * Checks before any decoder runs: at most 25 MB, a JPEG or PNG signature, the decoder agreeing it
 * is JPEG or PNG, at most 12000 pixels a side and 50 megapixels. EXIF rotation is applied, and
 * nothing of the EXIF (GPS included) survives the re-encode.
 */
public final class Decoder extends Service {
    /** What comes back: a JPEG, or w x h ARGB_8888 pixels in Android's own memory order. */
    static final int JPEG = 0, PIXELS = 1;
    static final int OK = 0, BAD_INPUT = 1, TOO_LARGE = 2, NOT_IMAGE = 3, FAILED = 4;
    static final int MAX_INPUT = 25 * 1024 * 1024, MAX_SIDE = 12000;
    static final long MAX_PIXELS = 50000000L;
    static final int TRANSACT = IBinder.FIRST_CALL_TRANSACTION;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != TRANSACT) return false;
            int status;
            int[] size = new int[2];
            long[] taken = new long[1];
            ParcelFileDescriptor in = null, out = null;
            try {
                in = data.readFileDescriptor();
                out = data.readFileDescriptor();
                int mode = data.readInt(), w = data.readInt(), h = data.readInt(), quality = data.readInt();
                int output = data.readInt();
                status = in == null || out == null ? BAD_INPUT : work(in, out, mode, output, w, h, quality, size, taken);
            } catch (RuntimeException | OutOfMemoryError e) {
                status = FAILED;
            } finally {
                close(in);
                close(out);
            }
            if (reply != null) {
                reply.writeInt(status);
                reply.writeInt(size[0]);
                reply.writeInt(size[1]);
                reply.writeLong(taken[0]);
            }
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private static void close(ParcelFileDescriptor p) {
        if (p == null) return;
        try {
            p.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /**
     * Lays the picture out in a w x h box ({@link Layout}: FIT, COVER or WHOLE) and writes it as a
     * JPEG, or as raw pixels, exactly w x h of them.
     */
    static int work(ParcelFileDescriptor inFd, ParcelFileDescriptor outFd, int mode, int output, int w, int h, int quality,
            int[] size, long[] taken) {
        boolean raw = output == PIXELS;
        if (raw ? !Layout.pixelRequest(mode, w, h) : output != JPEG || !Layout.jpegRequest(mode, w, h, quality)) return BAD_INPUT;
        byte[] b = read(inFd);
        if (b == null) return TOO_LARGE;
        if (b.length < 8 || !Layout.jpegOrPng(b)) return NOT_IMAGE;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (!"image/jpeg".equals(o.outMimeType) && !"image/png".equals(o.outMimeType)) return NOT_IMAGE;
        if (o.outWidth < 16 || o.outHeight < 16 || o.outWidth > MAX_SIDE || o.outHeight > MAX_SIDE
                || (long) o.outWidth * o.outHeight > MAX_PIXELS) {
            return TOO_LARGE;
        }
        int rotate = 0;
        if ("image/jpeg".equals(o.outMimeType)) {
            try {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(b));
                int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rotate = orient == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orient == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : orient == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                taken[0] = exifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
            } catch (IOException | RuntimeException ignored) {
                // no usable EXIF
            }
        }
        boolean sideways = rotate == 90 || rotate == 270;
        int srcW = sideways ? o.outHeight : o.outWidth, srcH = sideways ? o.outWidth : o.outHeight;
        int outW = w, outH = h;
        if (mode == Layout.FIT) {
            int[] fit = Layout.fitSize(srcW, srcH, w);
            outW = fit[0];
            outH = fit[1];
        }
        // Sampled while still at least the size drawn (twice it for a JPEG), then filtered down:
        // sharp, no aliasing.
        float[] at = Layout.box(mode, srcW, srcH, outW, outH);
        o.inSampleSize = Layout.sample(o.outWidth, o.outHeight, sideways, at[2], at[3], raw ? 1 : 2);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = BitmapFactory.decodeByteArray(b, 0, b.length, o);
        b = null;
        if (src == null) return NOT_IMAGE;
        Bitmap dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(dst);
        // A JPEG has no transparency. Raw pixels keep a PNG's, so a tile picture can show its
        // tile's colour through.
        if (!raw) c.drawColor(0xFF000000);
        Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
        float uw = sideways ? src.getHeight() : src.getWidth(), uh = sideways ? src.getWidth() : src.getHeight();
        at = Layout.box(mode, uw, uh, outW, outH);
        if (mode == Layout.WHOLE) backdrop(c, src, rotate, uw, uh, at, outW, outH, smooth);
        draw(c, src, rotate, at, smooth);
        src.recycle();
        FileOutputStream fo = new FileOutputStream(outFd.getFileDescriptor());
        try {
            if (raw) {
                ByteBuffer px = ByteBuffer.allocate(dst.getByteCount());
                dst.copyPixelsToBuffer(px);
                if (!Layout.pixelsFit(px.position(), outW, outH)) return FAILED;
                fo.write(px.array(), 0, px.position());
            } else if (!dst.compress(Bitmap.CompressFormat.JPEG, quality, fo)) {
                return FAILED;
            }
            fo.flush();
        } catch (IOException e) {
            return FAILED;
        } finally {
            dst.recycle();
        }
        size[0] = outW;
        size[1] = outH;
        return OK;
    }

    /** Draws {@code src}, turned upright by {@code rotate} degrees, into at = {left, top, width, height}. */
    private static void draw(Canvas c, Bitmap src, int rotate, float[] at, Paint paint) {
        boolean sideways = rotate == 90 || rotate == 270;
        float uw = sideways ? src.getHeight() : src.getWidth(), uh = sideways ? src.getWidth() : src.getHeight();
        Matrix m = new Matrix();
        m.postTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f);
        m.postRotate(rotate);
        m.postScale(at[2] / uw, at[3] / uh);
        m.postTranslate(at[0] + at[2] / 2f, at[1] + at[3] / 2f);
        c.drawBitmap(src, m, paint);
    }

    /**
     * WHOLE: behind the photo (uw x uh upright) going to {@code photo}, a 24 pixel tall copy of it
     * enlarged past the box's edges, which blurs it, and darkened, then a soft shadow where the
     * photo will sit.
     */
    private static void backdrop(Canvas c, Bitmap src, int rotate, float uw, float uh, float[] photo, int w, int h, Paint smooth) {
        int[] t = Layout.tiny(uw, uh);
        Bitmap tiny = Bitmap.createBitmap(t[0], t[1], Bitmap.Config.ARGB_8888);
        draw(new Canvas(tiny), src, rotate, new float[] {0, 0, t[0], t[1]}, smooth);
        draw(c, tiny, 0, Layout.backdrop(t[0], t[1], w, h), smooth);
        tiny.recycle();
        c.drawColor(0x66000000);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(Pixels.alpha(0xFF000000, 0.05f));
        for (int i = 6; i >= 1; i--) {
            float[] r = Layout.shadow(photo, i);
            c.drawRoundRect(r[0], r[1], r[2], r[3], r[4], r[4], shade);
        }
    }

    private static byte[] read(ParcelFileDescriptor fd) {
        InputStream in = new FileInputStream(fd.getFileDescriptor());
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_INPUT) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    /** EXIF "2024:06:12 18:30:05" (camera local time) to epoch ms in the TV's zone, or 0. */
    static long exifDate(String v) {
        if (v == null || v.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            f.setLenient(false);
            f.setTimeZone(TimeZone.getDefault());
            Date d = f.parse(v.substring(0, 19));
            long t = d == null ? 0 : d.getTime();
            return t > 631152000000L && t < System.currentTimeMillis() + 86400000L ? t : 0;
        } catch (java.text.ParseException | RuntimeException e) {
            return 0;
        }
    }
}
