package io.github.philaeone.artci;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

/**
 * Bildirim erisimi verildiginde Android bu servisi calistirir. Iki isi var:
 * 1) Calan muzigin (Spotify) oturumunu bulup sarki bilgisi ve kontrol saglamak.
 * 2) Navigasyon bildirimlerini NavParser'a iletmek.
 */
public class NotifService extends NotificationListenerService
        implements MediaSessionManager.OnActiveSessionsChangedListener {

    static final String MUSIC_PKG = "com.spotify.music";
    static volatile boolean connected;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private ComponentName self;
    private MediaController current;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publishMedia();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publishMedia();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };

    @Override
    public void onListenerConnected() {
        connected = true;
        self = new ComponentName(this, NotifService.class);
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessions.addOnActiveSessionsChangedListener(this, self, main);
        } catch (Throwable t) {
            Log.w(NavParser.TAG, "muzik oturumlari dinlenemedi", t);
        }
        refreshSessions();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) NavParser.posted(this, sbn);
            }
        } catch (Throwable t) {
            Log.w(NavParser.TAG, "mevcut bildirimler okunamadi", t);
        }
        Bus.changed();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        try {
            if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
        } catch (Throwable ignored) {
        }
        setController(null);
        NavParser.clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            NavParser.posted(this, sbn);
        } catch (Throwable t) {
            Log.w(NavParser.TAG, "bildirim islenemedi", t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NavParser.removed(sbn);
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        pick(controllers);
    }

    private void refreshSessions() {
        try {
            pick(sessions != null && self != null ? sessions.getActiveSessions(self) : null);
        } catch (Throwable t) {
            setController(null);
        }
    }

    /** Oncelik: calan Spotify > calan baska uygulama > duran Spotify > ilk oturum. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        int bestScore = -1;
        if (list != null) {
            for (MediaController c : list) {
                if (c == null || NavParser.isNavPackage(c.getPackageName())) continue;
                PlaybackState st = c.getPlaybackState();
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                int score = 1 + (playing ? 4 : 0) + (MUSIC_PKG.equals(c.getPackageName()) ? 2 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        setController(best);
    }

    private void setController(MediaController next) {
        boolean same = current != null && next != null
                && current.getSessionToken().equals(next.getSessionToken());
        if (!same) {
            if (current != null) {
                try {
                    current.unregisterCallback(callback);
                } catch (Throwable ignored) {
                }
            }
            current = next;
            if (current != null) {
                try {
                    current.registerCallback(callback, main);
                } catch (Throwable ignored) {
                }
            }
        }
        publishMedia();
    }

    private void publishMedia() {
        MediaController c = current;
        if (c == null) {
            MediaState.clear();
            Bus.changed();
            return;
        }
        String title = "";
        String artist = "";
        Bitmap art = null;
        boolean playing = false;
        try {
            MediaMetadata m = c.getMetadata();
            if (m != null) {
                title = first(m.getString(MediaMetadata.METADATA_KEY_TITLE),
                        m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                artist = first(m.getString(MediaMetadata.METADATA_KEY_ARTIST),
                        m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                        m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            PlaybackState st = c.getPlaybackState();
            playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        } catch (Throwable t) {
            Log.w(NavParser.TAG, "muzik bilgisi okunamadi", t);
        }
        MediaState.controller = c;
        MediaState.hasSession = true;
        MediaState.title = title;
        MediaState.artist = artist;
        MediaState.art = art;
        MediaState.playing = playing;
        Bus.changed();
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }
}
