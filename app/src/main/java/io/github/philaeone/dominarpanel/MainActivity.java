package io.github.philaeone.dominarpanel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements PanelView.Actions, LocationListener {

    private static final String NAV_PKG = "ru.yandex.yandexnavi";
    private static final String MUSIC_PKG = NotifService.MUSIC_PKG;
    private static final int REQ_LOCATION = 1;
    private static final long FIX_TIMEOUT_MS = 4000L;

    private static final int ORIENT_AUTO = 0;
    private static final int ORIENT_PORTRAIT = 1;
    private static final int ORIENT_LANDSCAPE = 2;
    private static final String[] ORIENT_NAMES = {"Otomatik", "Dikey", "Yatay"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private PanelView view;
    private SharedPreferences prefs;
    private LocationManager locationManager;
    private boolean locating;
    private long lastFixAt;
    private boolean askedLocation;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };

    /** Rota acik ve hareket halindeyken sematik yolun akici kaymasi icin sik cizim. */
    private final Runnable animate = new Runnable() {
        @Override
        public void run() {
            if (NavState.active && view.speed > 1f) view.invalidate();
            handler.postDelayed(this, 150L);
        }
    };

    private final Runnable redraw = new Runnable() {
        @Override
        public void run() {
            if (view != null) view.invalidate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("panel", MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        view = new PanelView(this, this);
        try {
            view.version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        setContentView(view);
        applyOrientation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        Bus.listener = redraw;
        if (!hasLocationPermission() && !askedLocation) {
            askedLocation = true;
            requestLocationPermission();
        }
        startLocation();
        handler.removeCallbacks(tick);
        handler.post(tick);
        handler.removeCallbacks(animate);
        handler.postDelayed(animate, 150L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(animate);
        Bus.listener = null;
        stopLocation();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Ana ekran olarak calisirken geri tusu bir yere goturmemeli. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------
    // Her saniye: saat, pil, GPS tazeligi, bildirim erisimi
    // ------------------------------------------------------------------

    private void refresh() {
        view.clock = clockFormat.format(new Date());

        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                view.battery = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
                view.charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                view.battTemp = t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
            }
        } catch (Exception ignored) {
        }

        view.volume = readVolume();
        view.locPermission = hasLocationPermission();
        if (SystemClock.elapsedRealtime() - lastFixAt > FIX_TIMEOUT_MS) view.speed = -1f;
        if (view.locPermission && !locating) startLocation();

        boolean enabled = isListenerEnabled();
        view.listenerEnabled = enabled;
        if (enabled && !NotifService.connected) {
            // Uygulama guncellenince Android servisi bazen yeniden baglamaz.
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, NotifService.class));
            } catch (Throwable ignored) {
            }
        }
        view.invalidate();
    }

    private float readVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : -1f;
        } catch (Exception e) {
            return -1f;
        }
    }

    private void changeVolume(int direction) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (Exception e) {
            toast("Ses değiştirilemedi");
        }
        view.volume = readVolume();
        view.invalidate();
    }

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName() + "/");
    }

    // ------------------------------------------------------------------
    // Konum ve hiz
    // ------------------------------------------------------------------

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) startLocation();
    }

    private void startLocation() {
        if (locating || locationManager == null || !hasLocationPermission()) return;
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this);
            locating = true;
        } catch (Exception e) {
            Log.w(NavParser.TAG, "GPS baslatilamadi", e);
        }
    }

    private void stopLocation() {
        if (!locating || locationManager == null) return;
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        locating = false;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        lastFixAt = SystemClock.elapsedRealtime();
        float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        // Dururken GPS 1-2 km/h oynar; onu sifir goster.
        view.speed = kmh < 2.5f ? 0f : kmh;
        view.invalidate();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    // ------------------------------------------------------------------
    // Panelden gelen dokunuslar
    // ------------------------------------------------------------------

    @Override
    public void onAction(int id) {
        switch (id) {
            case PanelView.A_OPEN_NAV:
                launch(NAV_PKG, "Yandex Navigasyon");
                break;
            case PanelView.A_OPEN_MUSIC:
                launch(MUSIC_PKG, "Spotify");
                break;
            case PanelView.A_MENU:
                showMenu();
                break;
            case PanelView.A_PREV:
                media(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case PanelView.A_PLAY:
                media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case PanelView.A_NEXT:
                media(KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case PanelView.A_VOL_DOWN:
                changeVolume(AudioManager.ADJUST_LOWER);
                break;
            case PanelView.A_VOL_UP:
                changeVolume(AudioManager.ADJUST_RAISE);
                break;
            case PanelView.A_GRANT_NOTIF:
                openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                break;
            case PanelView.A_GRANT_LOC:
                requestLocationPermission();
                break;
            default:
                break;
        }
    }

    private void launch(String pkg, String name) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                toast(name + " kurulu değil");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast(name + " açılamadı");
        }
    }

    /** Oturum varsa dogrudan ona, yoksa sisteme medya tusu olarak gonderir. */
    private void media(int keyCode) {
        MediaController c = MediaState.controller;
        try {
            if (c != null) {
                MediaController.TransportControls t = c.getTransportControls();
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    t.skipToNext();
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    t.skipToPrevious();
                } else if (MediaState.playing) {
                    t.pause();
                } else {
                    t.play();
                }
                return;
            }
        } catch (Exception ignored) {
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private void showMenu() {
        final int orient = prefs.getInt("orient", ORIENT_AUTO);
        String[] items = {
                "Uygulamalar",
                "Ekran yönü: " + ORIENT_NAMES[orient],
                "Bildirim erişimi" + (isListenerEnabled() ? " (açık)" : " (KAPALI)"),
                "Ham navigasyon verisi",
                "Varsayılan ana ekranı seç",
                "Android ayarları"};
        new AlertDialog.Builder(this)
                .setTitle("Dominar Panel " + view.version)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                showApps();
                                break;
                            case 1:
                                prefs.edit().putInt("orient", (orient + 1) % 3).apply();
                                applyOrientation();
                                toast("Ekran yönü: " + ORIENT_NAMES[(orient + 1) % 3]);
                                break;
                            case 2:
                                openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                                break;
                            case 3:
                                showRawNav();
                                break;
                            case 4:
                                openSettings(Settings.ACTION_HOME_SETTINGS);
                                break;
                            case 5:
                                openSettings(Settings.ACTION_SETTINGS);
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void applyOrientation() {
        int orient = prefs.getInt("orient", ORIENT_AUTO);
        if (orient == ORIENT_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if (orient == ORIENT_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Ayar ekranı açılamadı");
        }
    }

    /** Panel ana ekran oldugu icin diger uygulamalara buradan ulasilir. */
    private void showApps() {
        final PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            apps.add(ri);
        }
        final List<String> labels = new ArrayList<>();
        final List<ResolveInfo> sorted = new ArrayList<>(apps);
        Collections.sort(sorted, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
            }
        });
        for (ResolveInfo ri : sorted) labels.add(String.valueOf(ri.loadLabel(pm)));

        new AlertDialog.Builder(this)
                .setTitle("Uygulamalar")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ResolveInfo ri = sorted.get(which);
                        try {
                            Intent i = new Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            startActivity(i);
                        } catch (Exception e) {
                            toast("Açılamadı");
                        }
                    }
                })
                .show();
    }

    /** Navigasyon bildiriminden okunan her seyi gosterir; ayiklama icin. */
    private void showRawNav() {
        final String dump = NavState.rawDump.isEmpty()
                ? (isListenerEnabled()
                        ? "Henüz navigasyon bildirimi görülmedi.\n\nYandex Navigasyon'da rota başlatıp buraya dön."
                        : "Bildirim erişimi kapalı. Menü > Bildirim erişimi'nden aç.")
                : NavState.rawDump;
        TextView tv = new TextView(this);
        tv.setText(dump);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        int p = Math.round(20f * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Ham navigasyon verisi")
                .setView(sv)
                .setPositiveButton("Kapat", null)
                .setNeutralButton("Kopyala", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("nav", dump));
                        toast("Kopyalandı");
                    }
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
