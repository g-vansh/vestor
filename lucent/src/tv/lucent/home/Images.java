package tv.lucent.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes pictures from the shared media folder, which other software can write. Android picks
 * an image decoder from a file's contents, whatever its name, so a file named .jpg could reach
 * any decoder on the system, including ones with a history of flaws (the WebP decoder's
 * CVE-2023-4863). Only JPEG and PNG get through. The file is read once into memory, within a
 * size cap, and every check and both decoding passes use those same bytes, so nothing can
 * change the file between the check and the decode. The first bytes must be a JPEG or PNG
 * signature before any decoder runs, the decoder must agree, and dimensions and pixel count
 * are capped.
 */
final class Images {
    private Images() {}

    /**
     * The picture in {@code f}, sampled down while both sides stay at least w x h, or null if it
     * is not a JPEG or PNG within the limits.
     */
    static Bitmap decode(File f, int maxBytes, int maxSide, long maxPixels, int w, int h) {
        byte[] b = read(f, maxBytes);
        if (b == null || b.length < 8 || !jpegOrPng(b)) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (!jpegOrPngType(o.outMimeType)) return null;
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > maxSide || o.outHeight > maxSide
                    || (long) o.outWidth * o.outHeight > maxPixels) {
                return null;
            }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap out = BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (out != null && !jpegOrPngType(o.outMimeType)) {
                out.recycle();
                return null;
            }
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static boolean jpegOrPngType(String mime) {
        return "image/jpeg".equals(mime) || "image/png".equals(mime);
    }

    /** The whole file, or null if it is missing, unreadable or larger than {@code maxBytes}. */
    private static byte[] read(File f, int maxBytes) {
        if (f == null || !f.isFile() || f.length() > maxBytes) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.max(0, Math.min(maxBytes, f.length()))];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            // A file that grew past what was measured is refused: something is writing to it.
            if (n < buf.length || in.read() >= 0) return null;
            return buf;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** JPEG starts FF D8 FF; PNG starts 89 'P' 'N' 'G' CR LF 1A LF. */
    static boolean jpegOrPng(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        int[] png = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length < png.length) return false;
        for (int i = 0; i < png.length; i++) if ((b[i] & 0xFF) != png[i]) return false;
        return true;
    }
}
