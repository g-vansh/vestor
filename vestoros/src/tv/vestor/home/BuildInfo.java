package tv.vestor.home;

/**
 * The version shown in Settings and the control center, and the one build.sh writes into the APK.
 * Raise CODE for every release: an update installs only over a lower one.
 */
final class BuildInfo {
    private BuildInfo() {}

    static final String VERSION = "2.1.1";
    static final int CODE = 6;
}
