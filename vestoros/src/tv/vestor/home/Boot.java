package tv.vestor.home;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Keeps Vestor in charge of HOME across restarts and Fire OS updates. Android forgets an
 * accessibility service when an update or a settings reset switches it off, and on Fire OS there
 * is often no screen to switch it back on. So at every boot, and after Vestor itself updates,
 * this puts Vestor's HOME service back in the enabled list, keeping every other service as it
 * was.
 *
 * It can only do that with WRITE_SECURE_SETTINGS, which install.sh grants once over ADB (and
 * --undo takes back). Without it, nothing happens, and README says to run install.sh again.
 * With "Vestor off" chosen in Settings it does nothing at all. It never removes anything and
 * never touches a setting other than the two that enable accessibility services, plus the alarm
 * timing install.sh set before (only if install.sh's own record of it is still there).
 */
public final class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        heal(c);
        // Starts the hub so the control center answers from boot, not only once HOME is pressed.
        try {
            Hub.get(c);
        } catch (RuntimeException ignored) {
            // the next HOME press starts it
        }
        // Updated from the TV a moment ago: Android closed Vestor to replace it, so it comes back.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Prefs p = new Prefs(c);
            long at;
            try {
                at = Long.parseLong(p.get(Prefs.UPDATE_AT, "0"));
            } catch (NumberFormatException e) {
                at = 0;
            }
            long ago = System.currentTimeMillis() - at;
            if (at > 0 && ago >= 0 && ago < 20 * 60000L) {
                p.put(Prefs.UPDATE_AT, "0");
                Guard.wake(c, true);
            }
        }
    }

    /** Whether Vestor may write secure settings (granted by install.sh). */
    static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Re-enables the HOME service if it was switched off. True when it is (now) enabled. */
    static boolean heal(Context c) {
        if (!"on".equals(new Prefs(c).get(Prefs.TAKEOVER, "on"))) return false;
        String me = new ComponentName(c, Guard.class).flattenToString();
        String list;
        try {
            list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (RuntimeException e) {
            return false;
        }
        if (list == null) list = "";
        boolean on = false;
        for (String s : list.split(":")) on |= me.equals(s) || ("tv.vestor.home/.Guard").equals(s);
        int enabled = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        if (on && enabled == 1) return true;
        if (!granted(c)) return false;
        // The list is only ever extended, and only if it looks like a list of components.
        if (!list.matches("[A-Za-z0-9._$/:]*")) return false;
        try {
            if (!on) Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    list.isEmpty() ? me : list + ":" + me);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            futurity(c);
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * install.sh sets min_futurity=0 so the HOME switch is instant, and records the old value in
     * vestor_min_futurity. If an update wiped the setting but the record is still there, the
     * setting goes back.
     */
    private static void futurity(Context c) {
        try {
            String record = Settings.Global.getString(c.getContentResolver(), "vestor_min_futurity");
            if (record == null) return;
            String cur = Settings.Global.getString(c.getContentResolver(), "alarm_manager_constants");
            if (cur != null && cur.contains("min_futurity=0")) return;
            if (cur != null && !cur.matches("[A-Za-z0-9_=,.]*")) return;
            String rest = cur == null ? "" : cur.replaceAll("(^|,)min_futurity=[0-9]*", "").replaceAll("^,", "");
            Settings.Global.putString(c.getContentResolver(), "alarm_manager_constants",
                    (rest.isEmpty() ? "" : rest + ",") + "min_futurity=0");
        } catch (RuntimeException ignored) {
            // the switch is then up to 5 s slower, nothing worse
        }
    }

    /**
     * "Vestor off" in Settings: HOME goes back to Amazon until Vestor is opened from Your Apps and
     * switched on again. With the grant the HOME service itself is switched off too.
     */
    static void takeover(Context c, boolean on) {
        new Prefs(c).put(Prefs.TAKEOVER, on ? "on" : "off");
        if (on) {
            heal(c);
            return;
        }
        if (!granted(c)) return;
        try {
            String me = new ComponentName(c, Guard.class).flattenToString();
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || !list.matches("[A-Za-z0-9._$/:]*")) return;
            StringBuilder keep = new StringBuilder();
            for (String s : list.split(":")) {
                if (s.isEmpty() || s.equals(me) || s.equals("tv.vestor.home/.Guard")) continue;
                if (keep.length() > 0) keep.append(':');
                keep.append(s);
            }
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, keep.toString());
        } catch (RuntimeException ignored) {
            // the guard checks the setting itself and stands aside
        }
    }
}
