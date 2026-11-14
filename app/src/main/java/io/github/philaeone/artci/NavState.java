package io.github.philaeone.artci;

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
    /** Sonraki manevraya mesafe, bildirimde yazdigi gibi ("30 m"). */
    static volatile String distance = "";
    /** Ayni mesafenin metre karsiligi; bilinmiyorsa -1. */
    static volatile float distanceMeters = -1f;
    /** distance metninin en son degistigi an (SystemClock.elapsedRealtime). */
    static volatile long distanceAt;
    /** Donulecek sokak ya da talimat metni. */
    static volatile String instruction = "";
    /** Hedefe kalan yol ve sure: "7 km / 7 dk". */
    static volatile String remain = "";
    /** Varis saati: "17:56". */
    static volatile String eta = "";
    static volatile int direction = DIR_NONE;
    /** Navigasyon uygulamasinin kendi manevra simgesi. */
    static volatile Bitmap icon;
    /** Simge saydam zemin ustunde tek renk bir sekilse true; o zaman boyanabilir. */
    static volatile boolean iconGlyph;
    /** Menu > Ham navigasyon verisi ekraninda gosterilen dokum. */
    static volatile String rawDump = "";

    private NavState() {}
}
