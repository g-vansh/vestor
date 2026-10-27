package tv.vestor.home;

import java.util.Locale;
import java.util.Map;

/**
 * Updates over the air: the rules that need no Android, so the desk tests check them.
 * {@link Updater} does the fetching and installing.
 *
 * A new VestorOS is published by publish.sh to the "ota" branch of the public g-vansh/vestor
 * repository: the APK and a small update.json saying its version, size and SHA-256. The TV reads
 * update.json every six hours (or when asked), downloads the APK into its private storage, and
 * makes ready only an APK that passes every check below. Installing still takes one press of
 * Install on the Fire TV's own confirmation screen: Android 11 lets no ordinary app update itself
 * without it.
 *
 * What makes it safe is Android's rule that an update must be signed with the same key as the app
 * it replaces. The key never leaves your computer, so whoever controls the branch, the network or
 * GitHub can at most hold an update back. They cannot install anything. Vestor checks the same
 * things first, so a bad file never even reaches the confirmation screen:
 * <ul>
 * <li>update.json names this package, a higher version, a SHA-256 and a size, and an APK file
 * name made from the version (never a path or a link of its own);</li>
 * <li>the download has exactly that size and SHA-256;</li>
 * <li>the APK is this package at that version, signed by the same key as the running app.</li>
 * </ul>
 * Everything the TV shows about an update (its version and what changed) is read from the APK
 * after those checks, so the branch cannot put words of its own on the screen.
 */
final class Update {
    private Update() {}

    static final String BASE = "https://raw.githubusercontent.com/g-vansh/vestor/ota/vestoros/";
    static final String MANIFEST = BASE + "update.json";
    static final String PACKAGE = "tv.vestor.home";
    /** Far above the 6 MB APK, far below the Fire TV's free space. */
    static final long MAX_APK = 64L * 1024 * 1024;
    /** The first check a few minutes after start, then every six hours. A manual check at most once a minute. */
    static final long FIRST_MS = 3 * 60000L, EVERY_MS = 6 * 3600000L, MANUAL_GAP_MS = 60000L;
    /**
     * After publish.sh's nudge the check waits this long: GitHub serves raw files from a cache
     * that holds them for up to five minutes, so an earlier look could still see the old one.
     */
    static final long NUDGE_MS = 6 * 60000L;

    static final int IDLE = 0, CHECKING = 1, CURRENT = 2, DOWNLOADING = 3, READY = 4, INSTALLING = 5, FAILED = 6;
    static final String[] STATES = {"idle", "checking", "current", "downloading", "ready", "installing", "failed"};

    /** What update.json offers, checked. */
    static final class Offer {
        final int code;
        final long size;
        final String sha256, file;

        Offer(int code, long size, String sha256, String file) {
            this.code = code;
            this.size = size;
            this.sha256 = sha256;
            this.file = file;
        }

        String url() {
            return BASE + file;
        }
    }

    /**
     * The offer in update.json, or null when it is malformed, for another app, or not newer than
     * {@code installed}.
     */
    static Offer parse(String json, int installed) {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(json));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (m == null || !PACKAGE.equals(Json.str(m, "package", ""))) return null;
        // Numbers as numbers only: a file that says "5" in quotes was not written by publish.sh.
        if (!(m.get("versionCode") instanceof Number) || !(m.get("size") instanceof Number)) return null;
        double c = ((Number) m.get("versionCode")).doubleValue(), s = ((Number) m.get("size")).doubleValue();
        if (c != Math.floor(c) || c <= installed || c > 2000000000d) return null;
        if (s != Math.floor(s) || s < 1 || s > MAX_APK) return null;
        String sha = Json.str(m, "sha256", "").toLowerCase(Locale.US);
        if (!sha.matches("[0-9a-f]{64}")) return null;
        int code = (int) c;
        String file = "vestoros-" + code + ".apk";
        if (!file.equals(Json.str(m, "apk", ""))) return null;
        return new Offer(code, (long) s, sha, file);
    }

    /** Whether the timed check is due. */
    static boolean due(long now, long last) {
        return last <= 0 || now - last >= EVERY_MS || now < last;
    }

    /** The version name from a verified APK, kept to what a version looks like ("2.1", "2.10.3"). */
    static String version(String raw) {
        return raw != null && raw.matches("[0-9][0-9A-Za-z.]{0,15}") ? raw : "";
    }

    /** What changed, from a verified APK: one paragraph of plain text, at most 300 characters. */
    static String notes(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length() && b.length() < 300; i++) {
            char ch = raw.charAt(i);
            if (Character.isWhitespace(ch)) {
                space = b.length() > 0;
                continue;
            }
            if (Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT) continue;
            if (space) b.append(' ');
            space = false;
            b.append(ch);
        }
        return b.toString();
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }

    /** The Settings row on the TV for a state. */
    static String row(int state, String version, String problem) {
        switch (state) {
            case CHECKING:
                return "Checking";
            case DOWNLOADING:
                return "Downloading" + (version.isEmpty() ? "" : " " + version);
            case READY:
                return "VestorOS " + version + " Is Ready, Install";
            case INSTALLING:
                return "Confirm Install on the Screen";
            case FAILED:
                return (problem.isEmpty() ? "Could Not Update" : problem) + ", Try Again";
            case CURRENT:
                return "Up to Date, Check Now";
            default:
                return "Check Now";
        }
    }

    /** Why a session ended without installing, from PackageInstaller's status, for the screen. */
    static String refused(int status) {
        switch (status) {
            case 3: // STATUS_FAILURE_ABORTED: Cancel on the confirmation screen
                return "Install Cancelled";
            case 4: // STATUS_FAILURE_INVALID
                return "Fire OS Found the File Damaged";
            case 5: // STATUS_FAILURE_CONFLICT
                return "Fire OS Refused the Update";
            case 6: // STATUS_FAILURE_STORAGE
                return "Not Enough Space";
            case 7: // STATUS_FAILURE_INCOMPATIBLE
                return "Fire OS Says It Does Not Fit";
            case 2: // STATUS_FAILURE_BLOCKED
                return "Installs Are Blocked";
            default:
                return "Could Not Update";
        }
    }
}
