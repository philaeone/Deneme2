package io.github.philaeone.artci;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Konuma gore hava sicakligi. Open-Meteo kullanir: ucretsiz, anahtar istemez.
 * Internet yoksa son bilinen deger kalir; panel onu yasina gore soluk gosterir.
 */
final class Weather {
    private static final long REFRESH_MS = 30L * 60L * 1000L;
    private static final long RETRY_MS = 90L * 1000L;

    /** Santigrat; bilinmiyorsa NaN. */
    static volatile float temp = Float.NaN;
    /** Degerin alindigi an (System.currentTimeMillis). */
    static volatile long at;
    static volatile String lastError = "";

    private static volatile boolean busy;
    private static volatile long lastTry;

    private Weather() {}

    static void restore(SharedPreferences prefs) {
        if (prefs.contains("weatherTemp")) {
            temp = prefs.getFloat("weatherTemp", Float.NaN);
            at = prefs.getLong("weatherAt", 0L);
        }
    }

    static long ageMs() {
        return at <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - at;
    }

    static void maybeFetch(final SharedPreferences prefs, final double lat, final double lon, boolean force) {
        long now = System.currentTimeMillis();
        if (busy) return;
        if (!force) {
            if (now - lastTry < RETRY_MS) return;
            if (!Float.isNaN(temp) && now - at < REFRESH_MS) return;
        }
        busy = true;
        lastTry = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String url = String.format(Locale.ROOT,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m",
                            lat, lon);
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("Accept", "application/json");
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IllegalStateException("HTTP " + code);
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    JSONObject root = new JSONObject(out.toString("UTF-8"));
                    double value;
                    JSONObject current = root.optJSONObject("current");
                    if (current != null && current.has("temperature_2m")) {
                        value = current.getDouble("temperature_2m");
                    } else {
                        value = root.getJSONObject("current_weather").getDouble("temperature");
                    }
                    temp = (float) value;
                    at = System.currentTimeMillis();
                    lastError = "";
                    prefs.edit().putFloat("weatherTemp", temp).putLong("weatherAt", at).apply();
                } catch (Throwable t) {
                    lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
                    Log.w(NavParser.TAG, "hava sicakligi alinamadi", t);
                } finally {
                    if (conn != null) conn.disconnect();
                    busy = false;
                    Bus.changed();
                }
            }
        }, "weather").start();
    }
}
