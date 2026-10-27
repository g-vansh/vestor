package tv.vestor.home;

import java.io.File;
import java.io.IOException;

/** Updates over the air: what update.json may say, when to look, and what the TV shows. */
public final class UpdateTest {
    private static int failures, checks;

    private static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    public static void main(String[] args) throws IOException {
        // A good offer
        Update.Offer o = Update.parse(json(5, "vestoros-5.apk", 5892956, SHA, Update.PACKAGE), 4);
        ok("a newer version is offered", o != null && o.code == 5 && o.size == 5892956 && SHA.equals(o.sha256));
        eq("from the ota branch, by a name made from the version", Update.BASE + "vestoros-5.apk", o == null ? null : o.url());
        eq("the manifest's home", "https://raw.githubusercontent.com/g-vansh/vestor/ota/vestoros/update.json", Update.MANIFEST);
        ok("upper-case hash taken as the same hash", Update.parse(json(5, "vestoros-5.apk", 10, SHA.toUpperCase(), Update.PACKAGE), 4) != null);

        // Never an older or the same version
        ok("same version: nothing", Update.parse(json(4, "vestoros-4.apk", 10, SHA, Update.PACKAGE), 4) == null);
        ok("older: nothing", Update.parse(json(3, "vestoros-3.apk", 10, SHA, Update.PACKAGE), 4) == null);

        // Only this app, a real hash, a sane size
        ok("another package", Update.parse(json(5, "vestoros-5.apk", 10, SHA, "com.evil.app"), 4) == null);
        ok("short hash", Update.parse(json(5, "vestoros-5.apk", 10, SHA.substring(1), Update.PACKAGE), 4) == null);
        ok("not hex", Update.parse(json(5, "vestoros-5.apk", 10, SHA.replace('a', 'g'), Update.PACKAGE), 4) == null);
        ok("no size", Update.parse(json(5, "vestoros-5.apk", 0, SHA, Update.PACKAGE), 4) == null);
        ok("negative size", Update.parse(json(5, "vestoros-5.apk", -1, SHA, Update.PACKAGE), 4) == null);
        ok("too large", Update.parse(json(5, "vestoros-5.apk", Update.MAX_APK + 1, SHA, Update.PACKAGE), 4) == null);
        ok("fractional size", Update.parse("{\"package\":\"tv.vestor.home\",\"versionCode\":5,\"apk\":\"vestoros-5.apk\",\"size\":10.5,"
                + "\"sha256\":\"" + SHA + "\"}", 4) == null);
        ok("fractional version", Update.parse("{\"package\":\"tv.vestor.home\",\"versionCode\":5.5,\"apk\":\"vestoros-5.apk\",\"size\":10,"
                + "\"sha256\":\"" + SHA + "\"}", 4) == null);
        ok("absurd version", Update.parse(json(3000000000L, "vestoros-3000000000.apk", 10, SHA, Update.PACKAGE), 4) == null);

        // The file name can only be the one made from the version: no paths, no links
        ok("another version's file", Update.parse(json(5, "vestoros-6.apk", 10, SHA, Update.PACKAGE), 4) == null);
        ok("a path", Update.parse(json(5, "../vestoros-5.apk", 10, SHA, Update.PACKAGE), 4) == null);
        ok("a link", Update.parse(json(5, "https://evil.example/vestoros-5.apk", 10, SHA, Update.PACKAGE), 4) == null);
        ok("no file", Update.parse("{\"package\":\"tv.vestor.home\",\"versionCode\":5,\"size\":10,\"sha256\":\"" + SHA + "\"}", 4) == null);

        // Anything that is not an object of the right shape
        ok("not JSON", Update.parse("<html>rate limited</html>", 4) == null);
        ok("an array", Update.parse("[1,2]", 4) == null);
        ok("empty", Update.parse("", 4) == null);
        ok("null", Update.parse(null, 4) == null);
        ok("numbers as text", Update.parse("{\"package\":\"tv.vestor.home\",\"versionCode\":\"5\",\"apk\":\"vestoros-5.apk\",\"size\":\"10\","
                + "\"sha256\":\"" + SHA + "\"}", 4) == null);

        // When to look
        long h = 3600000L;
        ok("never looked: due", Update.due(10 * h, 0));
        ok("five hours ago: not due", !Update.due(10 * h, 5 * h));
        ok("six hours ago: due", Update.due(12 * h, 6 * h));
        ok("clock went back: due", Update.due(5 * h, 6 * h));
        ok("nudge waits out GitHub's five-minute cache", Update.NUDGE_MS > 5 * 60000L && Update.NUDGE_MS <= 10 * 60000L);

        // What the TV shows comes from the verified APK, and is kept plain
        eq("a version", "2.1", Update.version("2.1"));
        eq("a longer version", "2.10.3", Update.version("2.10.3"));
        eq("not a version", "", Update.version("2.1 <b>now</b>"));
        eq("no version", "", Update.version(null));
        eq("notes kept plain", "Video Calls play in your AirPods. Updates come over the air.",
                Update.notes("  Video Calls play in your AirPods.\n\n   Updates come\tover the air.  "));
        eq("control and direction characters dropped", "abc", Update.notes("a\u0000b‮c\u0007"));
        eq("at most 300", 300, Update.notes(repeat('x', 1000)).length());
        eq("no notes", "", Update.notes(null));
        eq("hex", "00ff10", Update.hex(new byte[] {0, (byte) 255, 16}));

        // The Settings row and the installer's answers
        eq("ready", "VestorOS 2.2 Is Ready, Install", Update.row(Update.READY, "2.2", ""));
        eq("current", "Up to Date, Check Now", Update.row(Update.CURRENT, "", ""));
        eq("never checked", "Check Now", Update.row(Update.IDLE, "", ""));
        eq("failed with a reason", "Download Failed, Try Again", Update.row(Update.FAILED, "", "Download Failed"));
        eq("installing", "Confirm Install on the Screen", Update.row(Update.INSTALLING, "2.2", ""));
        eq("cancelled", "Install Cancelled", Update.refused(3));
        eq("blocked", "Installs Are Blocked", Update.refused(2));
        eq("unknown", "Could Not Update", Update.refused(99));
        eq("every state named", 7, Update.STATES.length);
        for (int s = 0; s < Update.STATES.length; s++) {
            String r = Update.row(s, "2.2", "Download Failed");
            ok("house style: " + r, !r.isEmpty() && r.indexOf(';') < 0 && r.indexOf('—') < 0);
        }

        // The download goes nowhere but the allowed hosts, over HTTPS
        ok("GitHub's raw files allowed", Net.allowed(Update.MANIFEST));
        File f = File.createTempFile("vestor-update", ".apk");
        ok("no plain HTTP", !Net.download("http://raw.githubusercontent.com/g-vansh/vestor/ota/vestoros/vestoros-5.apk", f, 10, SHA, 1000, 2000));
        ok("no other host", !Net.download("https://evil.example/vestoros-5.apk", f, 10, SHA, 1000, 2000));
        ok("no look-alike host", !Net.download("https://raw.githubusercontent.com.evil.example/x.apk", f, 10, SHA, 1000, 2000));
        ok("no empty file", !Net.download(Update.BASE + "vestoros-5.apk", f, 0, SHA, 1000, 2000));
        ok("a refused download leaves no file", !f.exists() || f.length() == 0);
        f.delete();

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    private static String json(long code, String apk, long size, String sha, String pkg) {
        return "{\"package\":\"" + pkg + "\",\"versionCode\":" + code + ",\"versionName\":\"x\",\"apk\":\"" + apk + "\",\"size\":" + size
                + ",\"sha256\":\"" + sha + "\"}";
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    private static void ok(String what, boolean cond) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }
}
