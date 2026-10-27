package tv.vestor.home;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets Vestor see which app is playing music or video (its media
 * session), for the Now Playing card and the remote's play and pause on the home screen. It
 * never reads, keeps or shows a notification: it overrides nothing. Fire OS has no screen to
 * grant it, so install.sh does it once with "cmd notification allow_listener", and --undo takes
 * it back.
 */
public final class Listener extends NotificationListenerService {
}
