package io.github.philaeone.artci;

import android.graphics.Bitmap;
import android.media.session.MediaController;

/** O an calan muzigin son bilinen durumu. */
final class MediaState {
    static volatile MediaController controller;
    static volatile boolean hasSession;
    static volatile boolean playing;
    static volatile String title = "";
    static volatile String artist = "";
    static volatile Bitmap art;

    private MediaState() {}

    static void clear() {
        controller = null;
        hasSession = false;
        playing = false;
        title = "";
        artist = "";
        art = null;
    }
}
