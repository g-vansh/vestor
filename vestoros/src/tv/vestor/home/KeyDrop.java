package tv.vestor.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Takes the Claude API key from install.sh over ADB, so it never has to be typed or pasted:
 * {@code content write} streams it in from the computer's standard input, so it is on no command
 * line and in no broadcast record. Only ADB's shell (and root) may call this: the caller's user
 * id is checked on every call, so no app on the TV can set, swap or erase the key. The key goes
 * straight into Vestor's private storage and is never logged.
 */
public final class KeyDrop extends ContentProvider {
    private static final int SHELL = 2000, ROOT = 0, MAX = 600;

    private static void onlyShell() {
        int uid = Binder.getCallingUid();
        if (uid != SHELL && uid != ROOT) throw new SecurityException("only ADB may hand over the key");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** "w": the key arrives on a pipe, a few hundred bytes at most. */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        onlyShell();
        if (mode == null || !mode.contains("w") || mode.contains("r")) throw new FileNotFoundException("write only");
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new FileNotFoundException("no pipe");
        }
        final Context c = getContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[MAX];
                int n = 0;
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                    int r;
                    while (n < MAX && (r = in.read(buf, n, MAX - n)) > 0) n += r;
                } catch (IOException ignored) {
                    // whatever arrived is checked below
                }
                final String key = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                onMain(c, new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        Hub.get(c.getApplicationContext()).assistant.claude.setKey(key);
                        return "ok";
                    }
                });
            }
        }, "key-drop");
        t.setDaemon(true);
        t.start();
        return pipe[1];
    }

    /** "status" says whether a key is set (with its last four characters); "forget" erases it. */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        onlyShell();
        final Context c = getContext();
        final boolean forget = "forget".equals(method);
        String result = onMain(c, new Callable<String>() {
            @Override
            public String call() {
                Claude claude = Hub.get(c.getApplicationContext()).assistant.claude;
                if (forget) {
                    claude.forgetKey();
                    return "forgotten";
                }
                return claude.hasKey() ? "set " + claude.keyHint() : "none";
            }
        });
        Bundle b = new Bundle();
        b.putString("result", result == null ? "failed" : result);
        return b;
    }

    /** Vestor's state lives on the main thread. Null when the step failed. */
    private static String onMain(Context c, Callable<String> work) {
        FutureTask<String> task = new FutureTask<>(work);
        new Handler(Looper.getMainLooper()).post(task);
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
