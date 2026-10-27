package tv.lucent.home;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Decodes pictures Lucent did not make (photos from the control center, files pushed into the
 * shared media folder) in a sandbox, and hands back only a fresh JPEG that Android's own encoder
 * wrote.
 *
 * This service runs in its own isolated process (android:isolatedProcess in the manifest): no
 * permissions, no files, no network, nothing of Lucent's. If a crafted picture ever exploited a
 * flaw in an image decoder, it would land there, with nothing to take and nothing to reach. The
 * main process passes in two file descriptors, one to read and one to write, and gets back a
 * status. It never decodes the original itself.
 *
 * Checks before any decoder runs: at most 25 MB, a JPEG or PNG signature, the decoder agreeing it
 * is JPEG or PNG, at most 12000 pixels a side and 50 megapixels. EXIF rotation is applied, and
 * nothing of the EXIF (GPS included) survives the re-encode.
 */
public final class Decoder extends Service {
    static final int FIT = 0, COVER = 1;
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
                status = in == null || out == null ? BAD_INPUT : work(in, out, mode, w, h, quality, size, taken);
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
     * FIT scales so the long side is at most {@code w} (a photo). COVER fills a w x h box and
     * crops the overflow (an app tile).
     */
    static int work(ParcelFileDescriptor inFd, ParcelFileDescriptor outFd, int mode, int w, int h, int quality, int[] size,
            long[] taken) {
        if (w < 16 || w > 4096 || h < 16 || h > 4096 || quality < 50 || quality > 95) return BAD_INPUT;
        byte[] b = read(inFd);
        if (b == null) return TOO_LARGE;
        if (b.length < 8 || !Images.jpegOrPng(b)) return NOT_IMAGE;
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
        int outW, outH;
        if (mode == COVER) {
            outW = w;
            outH = h;
        } else {
            float s = Math.min(1f, w / (float) Math.max(srcW, srcH));
            outW = Math.max(1, Math.round(srcW * s));
            outH = Math.max(1, Math.round(srcH * s));
        }
        // Sampled while still at least twice the target, then filtered down: sharp, no aliasing.
        o.inSampleSize = 1;
        int needW = mode == COVER ? (int) Math.ceil(Math.max(outW, outH * srcW / (float) srcH)) : outW;
        int needH = mode == COVER ? (int) Math.ceil(Math.max(outH, outW * srcH / (float) srcW)) : outH;
        if (sideways) {
            int t = needW;
            needW = needH;
            needH = t;
        }
        while (o.outWidth / (o.inSampleSize * 2) >= needW * 2 && o.outHeight / (o.inSampleSize * 2) >= needH * 2) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = BitmapFactory.decodeByteArray(b, 0, b.length, o);
        b = null;
        if (src == null) return NOT_IMAGE;
        Bitmap dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(dst);
        c.drawColor(0xFF000000);
        Matrix m = new Matrix();
        float sw = sideways ? src.getHeight() : src.getWidth(), sh = sideways ? src.getWidth() : src.getHeight();
        float scale = mode == COVER ? Math.max(outW / sw, outH / sh) : Math.min(outW / sw, outH / sh);
        m.postTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f);
        m.postRotate(rotate);
        m.postScale(scale, scale);
        m.postTranslate(outW / 2f, outH / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG));
        src.recycle();
        FileOutputStream fo = new FileOutputStream(outFd.getFileDescriptor());
        try {
            if (!dst.compress(Bitmap.CompressFormat.JPEG, quality, fo)) return FAILED;
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
