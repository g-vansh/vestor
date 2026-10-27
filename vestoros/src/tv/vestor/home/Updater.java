package tv.vestor.home;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates over the air (rules and the reasons for them in {@link Update}). Checks every six hours
 * and when asked, downloads and verifies in the background, and installs when you choose Install,
 * through Fire OS's own confirmation screen.
 *
 * The verified APK sits in this app's private cache until it is installed or a newer one replaces
 * it. Installing needs "Install unknown apps" allowed for Vestor, which install.sh sets over ADB
 * (and its undo takes back); without it the TV says where to allow it.
 */
final class Updater {
    private final Hub hub;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();

    volatile int state = Update.IDLE;
    /** The ready (or downloading) version's name and what changed, read from the verified APK. */
    volatile String version = "", notes = "";
    /** Why the last attempt failed, for the screen ("" when it did not). */
    volatile String problem = "";
    volatile long checkedAt;
    private volatile long manualAt;
    private volatile File ready;
    private volatile String readySha = "";
    private volatile int readyCode;

    Updater(Hub hub) {
        this.hub = hub;
        this.app = hub.app;
    }

    private final Runnable timed = new Runnable() {
        @Override
        public void run() {
            if (Update.due(System.currentTimeMillis(), checkedAt)) check(false);
            main.postDelayed(this, 30 * 60000L);
        }
    };

    void start() {
        main.postDelayed(timed, Update.FIRST_MS);
        // A file left from before a restart is checked again before it is offered.
        work.execute(new Runnable() {
            @Override
            public void run() {
                File old = new File(dir(), "ready.apk");
                if (old.exists()) old.delete();
            }
        });
    }

    /** The installed version code. */
    int installed() {
        try {
            return (int) app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return BuildInfo.CODE;
        }
    }

    private File dir() {
        File d = new File(app.getCacheDir(), "update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void set(int s) {
        state = s;
        hub.post(Hub.UPDATE, null);
    }

    private final Runnable nudged = new Runnable() {
        @Override
        public void run() {
            check(false);
        }
    };

    /** publish.sh's nudge: one check once GitHub's cache has the new file, however many nudges came. */
    void checkSoon() {
        main.removeCallbacks(nudged);
        main.postDelayed(nudged, Update.NUDGE_MS);
    }

    /** Looks for a new version. {@code manual}: someone asked, so a failure is said and the check is not skipped. */
    String check(final boolean manual) {
        long now = System.currentTimeMillis();
        if (manual) {
            if (now - manualAt < Update.MANUAL_GAP_MS && now >= manualAt) {
                return state == Update.READY ? "VestorOS " + version + " is ready" : "Checked a moment ago";
            }
            manualAt = now;
        }
        if (state == Update.CHECKING || state == Update.DOWNLOADING || state == Update.INSTALLING) return "Already on it";
        final int before = state;
        set(Update.CHECKING);
        work.execute(new Runnable() {
            @Override
            public void run() {
                fetch(manual, before);
            }
        });
        return "Checking for updates";
    }

    private void fetch(boolean manual, int before) {
        checkedAt = System.currentTimeMillis();
        String json = Net.text(Update.MANIFEST, 20000, false, Net.LIMIT_TINY);
        if (json == null) {
            // A timed check that could not connect says nothing: the next one tries again.
            problem = manual ? "No Connection to GitHub" : problem;
            set(manual ? Update.FAILED : before == Update.READY ? Update.READY : Update.IDLE);
            return;
        }
        int installed = installed();
        Update.Offer offer = Update.parse(json, installed);
        if (offer == null) {
            dropReady();
            problem = "";
            set(Update.CURRENT);
            return;
        }
        if (ready != null && readyCode == offer.code && ready.exists()) {
            set(Update.READY);
            return;
        }
        dropReady();
        version = "";
        notes = "";
        set(Update.DOWNLOADING);
        File part = new File(dir(), "download.apk");
        if (!Net.download(offer.url(), part, offer.size, offer.sha256, 30000, 15 * 60000L)) {
            problem = "Download Failed";
            set(Update.FAILED);
            return;
        }
        PackageInfo info = verify(part, offer.code);
        if (info == null) {
            part.delete();
            problem = "The Update Failed Its Checks";
            set(Update.FAILED);
            return;
        }
        File done = new File(dir(), "ready.apk");
        if (!part.renameTo(done)) {
            part.delete();
            problem = "Not Enough Space";
            set(Update.FAILED);
            return;
        }
        version = Update.version(info.versionName);
        if (version.isEmpty()) version = String.valueOf(offer.code);
        notes = Update.notes(notesOf(info, done));
        ready = done;
        readySha = offer.sha256;
        readyCode = offer.code;
        problem = "";
        set(Update.READY);
    }

    private void dropReady() {
        File f = ready;
        ready = null;
        readyCode = 0;
        readySha = "";
        if (f != null) f.delete();
    }

    /**
     * The APK's package info when it is this app, at exactly {@code code}, above the installed
     * version, and signed by the very key that signed the running app. Null otherwise.
     *
     * Both signature flags are asked for: some Android versions fill in an APK file's signers only
     * for the older one.
     */
    @SuppressWarnings("deprecation")
    private PackageInfo verify(File apk, int code) {
        PackageManager pm = app.getPackageManager();
        int flags = PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;
        try {
            PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), flags);
            PackageInfo self = pm.getPackageInfo(app.getPackageName(), flags);
            if (info == null || self == null || !app.getPackageName().equals(info.packageName)) return null;
            if (info.getLongVersionCode() != code || code <= self.getLongVersionCode()) return null;
            String[] theirs = signers(info), ours = signers(self);
            if (theirs == null || ours == null || !Arrays.equals(theirs, ours)) return null;
            return info;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** The signing certificates, sorted, in whichever form Android filled in. Null when there are none. */
    @SuppressWarnings("deprecation")
    private static String[] signers(PackageInfo p) {
        Signature[] s = null;
        SigningInfo si = p.signingInfo;
        if (si != null) s = si.getApkContentsSigners();
        if (s == null || s.length == 0) s = p.signatures;
        if (s == null || s.length == 0) return null;
        String[] out = new String[s.length];
        for (int i = 0; i < s.length; i++) out[i] = s[i].toCharsString();
        Arrays.sort(out);
        return out;
    }

    /** The "update_notes" text inside the verified APK, or "". */
    private String notesOf(PackageInfo info, File apk) {
        try {
            info.applicationInfo.sourceDir = apk.getPath();
            info.applicationInfo.publicSourceDir = apk.getPath();
            Resources r = app.getPackageManager().getResourcesForApplication(info.applicationInfo);
            int id = r.getIdentifier("update_notes", "string", info.packageName);
            return id == 0 ? "" : r.getString(id);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    /** Starts installing the ready update. Answers what the screen or phone should say. */
    String install() {
        final File apk = ready;
        if (state == Update.INSTALLING) return "Confirm on the TV";
        if (apk == null || state != Update.READY) return check(true);
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            problem = "Allow Installs for Vestor";
            hub.post(Hub.UPDATE, null);
            return "First allow it: Fire TV Settings, My Fire TV, Developer Options, Install Unknown Apps, Vestor";
        }
        set(Update.INSTALLING);
        final String sha = readySha;
        work.execute(new Runnable() {
            @Override
            public void run() {
                String why = session(apk, sha);
                if (why != null) {
                    problem = why;
                    set(Update.READY);
                }
            }
        });
        return "Confirm on the TV with Install";
    }

    /** Hands the APK to Fire OS's installer. Null when the confirmation screen is on its way. */
    private String session(File apk, String sha) {
        // Checked again right before: the file has been sitting in storage since it was verified.
        if (!sha.equals(sha256(apk)) || verify(apk, readyCode) == null) {
            dropReady();
            return "The Update Failed Its Checks";
        }
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        PackageInstaller.Session s = null;
        try {
            int id = pi.createSession(params);
            s = pi.openSession(id);
            InputStream in = new FileInputStream(apk);
            try {
                OutputStream out = s.openWrite("vestoros.apk", 0, apk.length());
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            Intent done = new Intent(app, Result.class).setAction("tv.vestor.home.UPDATE_RESULT");
            PendingIntent pending = PendingIntent.getBroadcast(app, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
            hub.prefs.putNow(Prefs.UPDATE_AT, String.valueOf(System.currentTimeMillis()));
            s.commit(pending.getIntentSender());
            return null;
        } catch (IOException | RuntimeException e) {
            if (s != null) s.abandon();
            return "Fire OS Refused the Update";
        } finally {
            if (s != null) s.close();
        }
    }

    private static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            return Update.hex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** What the installer said: show its confirmation screen, or why it stopped. */
    void result(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            // Only the system installer's confirmation screen is opened from here.
            if (!systemScreen(c, confirm)) {
                problem = "Fire OS Refused the Update";
                set(Update.READY);
                return;
            }
            Guard.wake(c, false);
            try {
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                problem = "Open Vestor and Try Again";
                set(Update.READY);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Usually never seen: Android ends this process to replace the app.
            dropReady();
            set(Update.CURRENT);
            return;
        }
        problem = Update.refused(status);
        set(Update.READY);
    }

    /** Whether {@code i} opens a screen of a system app (Fire OS's installer), never another app's. */
    private static boolean systemScreen(Context c, Intent i) {
        if (i == null) return false;
        try {
            PackageManager pm = c.getPackageManager();
            android.content.ComponentName to = i.resolveActivity(pm);
            if (to == null || c.getPackageName().equals(to.getPackageName())) return false;
            return (pm.getApplicationInfo(to.getPackageName(), 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** Where PackageInstaller reports back. Not exported: only this app's own pending intent reaches it. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null || !"tv.vestor.home.UPDATE_RESULT".equals(i.getAction())) return;
            Hub.get(c).updater.result(c, i);
        }
    }
}
