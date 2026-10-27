package tv.lucent.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Takes the Claude API key from install.sh over ADB, so it never has to be typed or pasted. Only
 * a sender holding WRITE_SECURE_SETTINGS can reach it (the manifest says so): that is ADB's shell
 * and nothing an app can get without ADB. The key goes straight into Lucent's private storage
 * and is never logged. The result is "ok" or "bad" for the installer to report.
 */
public final class KeyDrop extends BroadcastReceiver {
    static final String SET = "tv.lucent.home.CLAUDE_KEY", FORGET = "tv.lucent.home.CLAUDE_KEY_FORGET";

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        Claude claude = Hub.get(c.getApplicationContext()).assistant.claude;
        if (FORGET.equals(i.getAction())) {
            claude.forgetKey();
            setResultData("ok");
            return;
        }
        if (!SET.equals(i.getAction())) return;
        try {
            claude.setKey(i.getStringExtra("key"));
            setResultData("ok");
        } catch (Claude.Failed e) {
            setResultData("bad");
        }
    }
}
