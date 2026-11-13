package io.github.philaeone.dominarpanel;

import android.os.Handler;
import android.os.Looper;

/** Servisten ekrana "bir sey degisti, yeniden ciz" haberi tasir. */
final class Bus {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile Runnable listener;

    private Bus() {}

    static void changed() {
        final Runnable r = listener;
        if (r != null) MAIN.post(r);
    }
}
