package tv.lucent.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * What is playing on the TV (Spotify, YouTube, any app with a media session), and control of it:
 * play, pause, next, previous. Read through {@link Listener}'s grant. Without the grant this
 * simply reports nothing is playing. Artwork arrives as pixels the playing app already decoded,
 * and a copy at most 640 pixels across is kept.
 */
final class Media {
    interface Sink {
        void media(Model.Media m);
    }

    private final Context context;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager manager;
    private MediaController current;
    private volatile Model.Media now = new Model.Media();
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    pick(controllers);
                }
            };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    Media(Context c, Sink sink) {
        context = c.getApplicationContext();
        this.sink = sink;
    }

    private ComponentName component() {
        return new ComponentName(context, Listener.class);
    }

    void start() {
        if (started) return;
        try {
            manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            manager.addOnActiveSessionsChangedListener(changed, component(), main);
            started = true;
            pick(manager.getActiveSessions(component()));
        } catch (RuntimeException e) {
            // Not granted: there is simply no Now Playing.
            started = false;
        }
    }

    /** Asks again (after install.sh granted access, for example). */
    void refresh() {
        if (!started) {
            start();
            return;
        }
        try {
            pick(manager.getActiveSessions(component()));
        } catch (RuntimeException e) {
            pick(null);
        }
    }

    boolean available() {
        return started;
    }

    Model.Media snapshot() {
        return now;
    }

    /** Prefers a session that is playing, else the most recent one. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                if (c.getPackageName().equals(context.getPackageName())) continue;
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null) best = c;
            }
        }
        if (current != null && (best == null || !best.getSessionToken().equals(current.getSessionToken()))) {
            try {
                current.unregisterCallback(callback);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        if (best != null && (current == null || !best.getSessionToken().equals(current.getSessionToken()))) {
            try {
                best.registerCallback(callback, main);
            } catch (RuntimeException ignored) {
                // its updates are missed, polling the snapshot still works
            }
        }
        current = best;
        publish();
    }

    private void publish() {
        Model.Media m = new Model.Media();
        MediaController c = current;
        if (c != null) {
            try {
                m.pkg = c.getPackageName();
                m.app = label(m.pkg);
                MediaMetadata md = c.getMetadata();
                if (md != null) {
                    m.title = Text.clean(text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                    m.artist = Text.clean(text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                    m.album = Text.clean(text(md, MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
                    m.duration = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
                    m.art = art(md);
                }
                PlaybackState s = c.getPlaybackState();
                if (s != null) {
                    m.playing = s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING;
                    m.position = Math.max(0, s.getPosition());
                    m.positionAt = s.getLastPositionUpdateTime();
                    m.speed = s.getPlaybackSpeed();
                    long actions = s.getActions();
                    m.canNext = (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
                    m.canPrev = (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0;
                }
                m.active = !m.title.isEmpty() || m.playing;
            } catch (RuntimeException e) {
                m = new Model.Media();
            }
        }
        // Keep the same artwork object while it has not changed, so the screen does not reload it.
        Model.Media old = now;
        if (m.art == null && old.art != null && m.title.equals(old.title)) m.art = old.art;
        now = m;
        final Model.Media out = m;
        main.post(new Runnable() {
            @Override
            public void run() {
                sink.media(out);
            }
        });
    }

    private static String text(MediaMetadata md, String key, String fallback) {
        CharSequence v = md.getText(key);
        if (v == null || v.length() == 0) v = md.getText(fallback);
        return v == null ? "" : v.toString();
    }

    private Object art(MediaMetadata md) {
        Bitmap b = null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            try {
                b = md.getBitmap(k);
            } catch (RuntimeException ignored) {
                b = null;
            }
            if (b != null) break;
        }
        if (b == null || b.getWidth() < 16 || b.getHeight() < 16) return null;
        Model.Media old = now;
        if (old.artSource == b.getGenerationId() + b.getWidth() * 31L && old.art != null) return old.art;
        try {
            float s = Math.min(1f, 640f / Math.max(b.getWidth(), b.getHeight()));
            Bitmap copy = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                    Math.max(1, Math.round(b.getHeight() * s)), true);
            if (copy == b) copy = b.copy(Bitmap.Config.ARGB_8888, false);
            now.artSource = b.getGenerationId() + b.getWidth() * 31L;
            return copy;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return Text.clean(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    void toggle() {
        MediaController c = current;
        if (c == null) return;
        try {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
            else c.getTransportControls().play();
        } catch (RuntimeException ignored) {
            // the app went away
        }
    }

    void next() {
        try {
            if (current != null) current.getTransportControls().skipToNext();
        } catch (RuntimeException ignored) {
            // the app went away
        }
    }

    void previous() {
        try {
            if (current != null) current.getTransportControls().skipToPrevious();
        } catch (RuntimeException ignored) {
            // the app went away
        }
    }
}
