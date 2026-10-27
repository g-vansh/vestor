package tv.lucent.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The main process's side of {@link Decoder}: hands an untrusted picture to the sandbox and
 * collects the JPEG it writes. One at a time, each with a 30 second limit, after which the
 * sandbox is let go and its process ends. Call off the main thread.
 */
final class Decode {
    private Decode() {}

    static final class Result {
        int status = Decoder.FAILED, width, height;
        long taken;
        byte[] jpeg;
    }

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Decodes {@code input} with the sandbox. A result whose jpeg is non-null is ready to store. */
    static Result run(Context c, File input, int mode, int w, int h, int quality) {
        synchronized (LOCK) {
            return once(c.getApplicationContext(), input, mode, w, h, quality);
        }
    }

    private static Result once(Context c, File input, int mode, int w, int h, int quality) {
        final Result r = new Result();
        final CountDownLatch bound = new CountDownLatch(1);
        final IBinder[] binder = new IBinder[1];
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder[0] = service;
                bound.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // the sandbox ended: the waiting call sees a dead binder
            }
        };
        File dir = new File(c.getCacheDir(), "decode");
        if (!dir.isDirectory() && !dir.mkdirs()) return r;
        final File out = new File(dir, Long.toHexString(RANDOM.nextLong()) + ".jpg");
        boolean isBound = false;
        ParcelFileDescriptor in = null, sink = null;
        try {
            isBound = c.bindService(new Intent(c, Decoder.class), conn, Context.BIND_AUTO_CREATE);
            if (!isBound || !bound.await(10, TimeUnit.SECONDS) || binder[0] == null) return r;
            in = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY);
            sink = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE);
            final Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            data.writeFileDescriptor(in.getFileDescriptor());
            data.writeFileDescriptor(sink.getFileDescriptor());
            data.writeInt(mode);
            data.writeInt(w);
            data.writeInt(h);
            data.writeInt(quality);
            final IBinder b = binder[0];
            final boolean[] done = new boolean[1];
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (b.transact(Decoder.TRANSACT, data, reply, 0)) {
                            synchronized (r) {
                                r.status = reply.readInt();
                                r.width = reply.readInt();
                                r.height = reply.readInt();
                                r.taken = reply.readLong();
                                done[0] = true;
                            }
                        }
                    } catch (RemoteException | RuntimeException ignored) {
                        // the sandbox died or refused: a failed decode
                    }
                }
            }, "decode-call");
            t.start();
            t.join(30000);
            boolean finished;
            synchronized (r) {
                finished = done[0];
            }
            if (!finished) {
                r.status = Decoder.FAILED;
                return r;
            }
            data.recycle();
            reply.recycle();
            if (r.status == Decoder.OK) {
                byte[] jpeg = Live.readFile(out);
                // Only a JPEG of sane size comes back: the sandbox's own output, checked anyway.
                if (jpeg != null && jpeg.length > 3 && jpeg.length < 16 * 1024 * 1024 && (jpeg[0] & 0xFF) == 0xFF
                        && (jpeg[1] & 0xFF) == 0xD8) {
                    r.jpeg = jpeg;
                } else {
                    r.status = Decoder.FAILED;
                }
            }
            return r;
        } catch (IOException | InterruptedException | RuntimeException | OutOfMemoryError e) {
            r.status = Decoder.FAILED;
            return r;
        } finally {
            close(in);
            close(sink);
            if (isBound) {
                try {
                    c.unbindService(conn);
                } catch (RuntimeException ignored) {
                    // already gone
                }
            }
            if (!out.delete()) out.deleteOnExit();
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
