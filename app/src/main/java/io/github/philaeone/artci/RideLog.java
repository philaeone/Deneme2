package io.github.philaeone.artci;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Kalici surus kaydi. Navigasyon bildiriminden okunan her degisiklik ve
 * periyodik durum satirlari bir metin dosyasina eklenir; rota bitse de
 * uygulama kapansa da silinmez. Simge tanimayi gercek veriye gore duzeltmek
 * ve isi/sarj davranisini gormek icin kullanilir.
 *
 * Dosya: Android/data/io.github.philaeone.artci/files/surus-kaydi.txt
 */
final class RideLog {
    private static final String FILE_NAME = "surus-kaydi.txt";
    private static final String OLD_NAME = "surus-kaydi-eski.txt";
    private static final long MAX_BYTES = 600L * 1024L;
    private static final int RECENT_LINES = 500;

    /** MainActivity her saniye gunceller; kayit satirlarina eklenir. */
    static volatile float speed = -1f;
    static volatile float battTemp = Float.NaN;
    static volatile int battery = -1;

    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
    private static File file;
    private static Handler io;

    private RideLog() {}

    static synchronized void init(Context ctx) {
        if (file != null) return;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        file = new File(dir, FILE_NAME);
        HandlerThread thread = new HandlerThread("ridelog");
        thread.start();
        io = new Handler(thread.getLooper());
        final File f = file;
        io.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (f.length() > MAX_BYTES) {
                        File old = new File(f.getParentFile(), OLD_NAME);
                        if (old.exists()) old.delete();
                        f.renameTo(old);
                    }
                    if (f.exists()) {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                        String line;
                        synchronized (RECENT) {
                            while ((line = r.readLine()) != null) {
                                RECENT.addLast(line);
                                if (RECENT.size() > RECENT_LINES) RECENT.removeFirst();
                            }
                        }
                        r.close();
                    }
                } catch (Throwable t) {
                    Log.w(NavParser.TAG, "surus kaydi okunamadi", t);
                }
            }
        });
    }

    /** Hiz ve pil bilgisini satir sonuna ekler. */
    static String status() {
        StringBuilder b = new StringBuilder();
        b.append("hiz ").append(speed < 0f ? "-" : String.valueOf(Math.round(speed)));
        b.append(" | pil ");
        b.append(Float.isNaN(battTemp) ? "-" : String.format(Locale.ROOT, "%.0f", battTemp)).append("C");
        if (battery >= 0) b.append(" %").append(battery);
        return b.toString();
    }

    static void line(String text) {
        final String stamped;
        synchronized (STAMP) {
            stamped = STAMP.format(new Date()) + "  " + text;
        }
        synchronized (RECENT) {
            for (String part : stamped.split("\n")) {
                RECENT.addLast(part);
                if (RECENT.size() > RECENT_LINES) RECENT.removeFirst();
            }
        }
        final File f = file;
        final Handler h = io;
        if (f == null || h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
                    w.write(stamped);
                    w.write('\n');
                    w.close();
                } catch (Throwable t) {
                    Log.w(NavParser.TAG, "surus kaydi yazilamadi", t);
                }
            }
        });
    }

    /** Son satirlar, en yenisi sonda. */
    static String tail() {
        StringBuilder b = new StringBuilder();
        synchronized (RECENT) {
            for (String s : RECENT) b.append(s).append('\n');
        }
        return b.toString();
    }

    static String path() {
        return file == null ? "" : file.getAbsolutePath();
    }

    static void clear() {
        synchronized (RECENT) {
            RECENT.clear();
        }
        final File f = file;
        final Handler h = io;
        if (f == null || h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
