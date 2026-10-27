package tv.lucent.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The main process's side of {@link Decoder}: hands an untrusted picture to the sandbox and
 * collects what it writes, a JPEG to store or pixels to show. Nothing it gets back is decoded
 * here. One call at a time, each with a 30 second limit, after which the sandbox is let go and
 * its process ends. Never on the main thread.
 *
 * Pixels are only ever pixels, so calls for them share one sandbox, kept for 5 seconds after the
 * last: a dozen tile pictures or a quick flick through wallpapers start one process, not twelve.
 * A JPEG is kept and served to phones, so each comes from a fresh sandbox that no other picture
 * has touched.
 */
final class Decode {
    private Decode() {}

    static final class Result {
        int status = Decoder.FAILED, width, height;
        long taken;
        byte[] jpeg;
    }

    private static final long KEEP = 5000, LIMIT = 30000;
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // The kept sandbox. STATE is only ever held for a moment, never across a bind or a call.
    private static final Object STATE = new Object();
    private static Context app;
    private static ServiceConnection conn;
    private static IBinder sandbox;
    private static boolean busy;

    private static final Runnable IDLE = new Runnable() {
        @Override
        public void run() {
            release();
        }
    };

    /**
     * The sandbox's JPEG of {@code input}, laid out in w x h ({@link Layout}). A result whose
     * jpeg is non-null starts like a JPEG and is at most {@code maxBytes}: ready to store.
     */
    static Result jpeg(Context c, File input, int mode, int w, int h, int quality, int maxBytes) {
        Result r = new Result();
        if (!Layout.jpegRequest(mode, w, h, quality)) {
            r.status = Decoder.BAD_INPUT;
            return r;
        }
        File out = scratch(c);
        if (out == null) return r;
        try {
            if (call(c, input, out, mode, Decoder.JPEG, w, h, quality, r) && r.status == Decoder.OK) {
                byte[] b = read(out, maxBytes);
                if (Layout.jpeg(b, maxBytes)) r.jpeg = b;
                else r.status = Decoder.FAILED;
            }
            return r;
        } finally {
            if (!out.delete()) out.deleteOnExit();
        }
    }

    /**
     * {@code input} laid out by the sandbox in a w x h box (COVER or WHOLE) as a bitmap, or null.
     * Exactly w x h x 4 bytes of pixels are accepted, and copied in: nothing is decoded here.
     */
    static Bitmap pixels(Context c, File input, int layout, int w, int h) {
        if (!Layout.pixelRequest(layout, w, h)) return null;
        File out = scratch(c);
        if (out == null) return null;
        try {
            Result r = new Result();
            if (!call(c, input, out, layout, Decoder.PIXELS, w, h, 0, r) || r.status != Decoder.OK || r.width != w
                    || r.height != h) {
                return null;
            }
            byte[] px = read(out, Layout.pixelBytes(w, h));
            if (px == null || !Layout.pixelsFit(px.length, w, h)) return null;
            Layout.clampToAlpha(px);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(ByteBuffer.wrap(px));
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (!out.delete()) out.deleteOnExit();
        }
    }

    /** A new, unguessable name in the cache for the sandbox to write to, or null. */
    private static File scratch(Context c) {
        File dir = new File(c.getCacheDir(), "decode");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return new File(dir, Long.toHexString(RANDOM.nextLong()) + ".out");
    }

    /**
     * One call: the sandbox reads {@code input} and writes {@code out}. True with its answer in
     * {@code r}, false if it could not be reached or did not answer in time.
     */
    private static boolean call(Context c, File input, File out, int mode, int output, int w, int h, int quality, Result r) {
        // Binding answers on the main thread, so waiting for it there would hang.
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        boolean fresh = output == Decoder.JPEG;
        synchronized (LOCK) {
            boolean answered = false;
            ParcelFileDescriptor in = null, sink = null;
            try {
                IBinder b = connect(c.getApplicationContext(), fresh);
                if (b == null) return false;
                in = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY);
                sink = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
                Parcel data = Parcel.obtain();
                data.writeFileDescriptor(in.getFileDescriptor());
                data.writeFileDescriptor(sink.getFileDescriptor());
                data.writeInt(mode);
                data.writeInt(w);
                data.writeInt(h);
                data.writeInt(quality);
                data.writeInt(output);
                answered = ask(b, data, r);
                return answered;
            } catch (IOException | InterruptedException | RuntimeException | OutOfMemoryError e) {
                return false;
            } finally {
                close(in);
                close(sink);
                // A sandbox that did not answer goes at once, and so does one that made a JPEG.
                done(!answered || fresh);
            }
        }
    }

    /**
     * Sends the call from a thread of its own and waits up to 30 seconds. The thread owns the
     * parcels from then on, and an answer that comes later is dropped.
     */
    private static boolean ask(final IBinder b, final Parcel data, Result r) throws InterruptedException {
        final Parcel reply = Parcel.obtain();
        final long[] answer = new long[4];
        // Answered, then given up on: both only under the lock.
        final boolean[] state = new boolean[2];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!b.transact(Decoder.TRANSACT, data, reply, 0)) return;
                    int status = reply.readInt(), width = reply.readInt(), height = reply.readInt();
                    long taken = reply.readLong();
                    synchronized (state) {
                        if (state[1]) return;
                        answer[0] = status;
                        answer[1] = width;
                        answer[2] = height;
                        answer[3] = taken;
                        state[0] = true;
                    }
                } catch (RemoteException | RuntimeException ignored) {
                    // the sandbox died or refused: a failed decode
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }, "decode-call");
        t.start();
        t.join(LIMIT);
        synchronized (state) {
            state[1] = true;
            if (!state[0]) return false;
        }
        r.status = (int) answer[0];
        r.width = (int) answer[1];
        r.height = (int) answer[2];
        r.taken = answer[3];
        return true;
    }

    /** The kept sandbox if it is alive and {@code fresh} is not asked for, or a newly bound one, or null. */
    private static IBinder connect(Context c, boolean fresh) throws InterruptedException {
        ServiceConnection old;
        Context oldApp;
        synchronized (STATE) {
            busy = true;
            MAIN.removeCallbacks(IDLE);
            if (!fresh && sandbox != null && sandbox.isBinderAlive()) return sandbox;
            old = conn;
            oldApp = app;
            conn = null;
            sandbox = null;
        }
        unbind(oldApp, old);
        final CountDownLatch bound = new CountDownLatch(1);
        final IBinder[] got = new IBinder[1];
        ServiceConnection sc = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                got[0] = service;
                bound.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // the sandbox ended: its binder is dead, and the next call binds a new one
            }
        };
        boolean ok = c.bindService(new Intent(c, Decoder.class), sc, Context.BIND_AUTO_CREATE);
        synchronized (STATE) {
            // Kept even if refused: letting it go is harmless.
            app = c;
            conn = sc;
        }
        if (!ok || !bound.await(10, TimeUnit.SECONDS) || got[0] == null) return null;
        synchronized (STATE) {
            sandbox = got[0];
            return sandbox;
        }
    }

    /** After a call: the sandbox is let go now, or kept for KEEP in case another call follows. */
    private static void done(boolean drop) {
        synchronized (STATE) {
            busy = false;
        }
        if (drop) release();
        else MAIN.postDelayed(IDLE, KEEP);
    }

    /** Lets the kept sandbox go, unless a call is using it. Its isolated process ends with it. */
    private static void release() {
        ServiceConnection old;
        Context oldApp;
        synchronized (STATE) {
            if (busy) return;
            old = conn;
            oldApp = app;
            conn = null;
            sandbox = null;
        }
        unbind(oldApp, old);
    }

    private static void unbind(Context c, ServiceConnection sc) {
        if (c == null || sc == null) return;
        try {
            c.unbindService(sc);
        } catch (RuntimeException ignored) {
            // already gone, or never bound
        }
    }

    /** The whole of {@code f}, or null if it is missing, empty or longer than {@code max} bytes. */
    private static byte[] read(File f, long max) {
        long len = f.length();
        if (len <= 0 || len > max) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) len];
            int n = 0;
            while (n < b.length) {
                int k = in.read(b, n, b.length - n);
                if (k < 0) return null;
                n += k;
            }
            // Longer than measured means it is still being written: refused.
            return in.read() < 0 ? b : null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static void close(ParcelFileDescriptor p) {
        if (p == null) return;
        try {
            p.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
