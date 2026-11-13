package io.github.philaeone.dominarpanel;

import android.graphics.Bitmap;

/** Navigasyon uygulamasinin bildiriminden okunan son yonlendirme bilgisi. */
final class NavState {
    static final int DIR_NONE = 0;
    static final int DIR_LEFT = 1;
    static final int DIR_RIGHT = 2;
    static final int DIR_STRAIGHT = 3;
    static final int DIR_UTURN = 4;
    static final int DIR_SLIGHT_LEFT = 5;
    static final int DIR_SLIGHT_RIGHT = 6;
    static final int DIR_ROUNDABOUT = 7;
    static final int DIR_ARRIVE = 8;

    /** Mesafe ya da yon okunabildi. */
    static volatile boolean active;
    /** Navigasyon uygulamasinin bildirimi var ama icinden yonlendirme okunamadi. */
    static volatile boolean appPresent;
    static volatile String distance = "";
    static volatile String instruction = "";
    static volatile String extra = "";
    static volatile int direction = DIR_NONE;
    static volatile Bitmap icon;
    /** Menu > Ham navigasyon verisi ekraninda gosterilen dokum. */
    static volatile String rawDump = "";

    private NavState() {}
}
