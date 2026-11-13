package io.github.philaeone.dominarpanel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private static final int REQ_HOME = 2;
    private static final long FIX_TIMEOUT_MS = 4000L;

    private static final int BG = 0xFF0B0D0C;
    private static final int ROW = 0xFF161A18;
    private static final int LINE = 0xFF26302B;
    private static final int WHITE = 0xFFF2F5F3;
    private static final int GREY = 0xFF98A39D;
    private static final int GREEN = 0xFF39D353;
    private static final int AMBER = 0xFFFFB300;

    private static final String[] ORIENT_NAMES = {"Otomatik", "Dikey", "Yatay"};
    private static final String[] KEEP_ON_NAMES = {"Her zaman", "Sadece şarjdayken", "Kapalı"};
    private static final String[] BRIGHT_NAMES = {"Sistem ayarı", "En yüksek"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private PanelView view;
    private SharedPreferences prefs;
    private LocationManager locationManager;
    private boolean locating;
    private long lastFixAt;
    private boolean askedLocation;
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;
    private long lastLocSavedAt;
    private boolean charging;
    private float battTemp = Float.NaN;

    private Dialog settingsDialog;
    private LinearLayout settingsList;

    private static final class AppItem {
        String label;
        Drawable icon;
        String pkg;
        String cls;
    }

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
        prefs = getSharedPreferences("panel", MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Weather.restore(prefs);
        if (prefs.contains("lat")) {
            lastLat = prefs.getFloat("lat", 0f);
            lastLon = prefs.getFloat("lon", 0f);
        }

        view = new PanelView(this, this);
        try {
            view.version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        setContentView(view);
        applyOrientation();
        applyBrightness();
        applyKeepScreenOn();
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
        if (settingsDialog != null && settingsDialog.isShowing()) fillSettings();
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
    // Her saniye: saat, pil, GPS tazeligi, hava, bildirim erisimi
    // ------------------------------------------------------------------

    private void refresh() {
        view.clock = clockFormat.format(new Date());

        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                view.battery = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
                boolean nowCharging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                if (nowCharging != charging) {
                    charging = nowCharging;
                    applyKeepScreenOn();
                }
                view.charging = charging;
                int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                battTemp = t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
                view.battTemp = battTemp;
            }
        } catch (Exception ignored) {
        }

        view.volume = readVolume();
        view.locPermission = hasLocationPermission();
        if (SystemClock.elapsedRealtime() - lastFixAt > FIX_TIMEOUT_MS) view.speed = -1f;
        if (view.locPermission && !locating) startLocation();

        updateWeather(false);

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

    /** Hava verisi 3 saatten eskiyse gosterilmez, 1 saatten eskiyse soluk gosterilir. */
    private void updateWeather(boolean force) {
        long age = Weather.ageMs();
        boolean usable = !Float.isNaN(Weather.temp) && age < 3L * 60L * 60L * 1000L;
        view.weatherTemp = usable ? Weather.temp : Float.NaN;
        view.weatherStale = age > 60L * 60L * 1000L;

        if (Double.isNaN(lastLat)) findLastKnownLocation();
        if (!Double.isNaN(lastLat)) Weather.maybeFetch(prefs, lastLat, lastLon, force);
    }

    /** GPS henuz konum bulamadiysa sistemin bildigi son konumu dener (ag konumu dahil). */
    @SuppressWarnings("MissingPermission")
    private void findLastKnownLocation() {
        if (locationManager == null || !hasLocationPermission()) return;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER};
        for (String p : providers) {
            try {
                Location l = locationManager.getLastKnownLocation(p);
                if (l != null) {
                    lastLat = l.getLatitude();
                    lastLon = l.getLongitude();
                    return;
                }
            } catch (Exception ignored) {
            }
        }
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
        if (requestCode == REQ_LOCATION) {
            startLocation();
            if (settingsDialog != null && settingsDialog.isShowing()) fillSettings();
        }
    }

    @SuppressWarnings("MissingPermission")
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
        long now = SystemClock.elapsedRealtime();
        lastFixAt = now;
        lastLat = location.getLatitude();
        lastLon = location.getLongitude();
        if (now - lastLocSavedAt > 10L * 60L * 1000L || lastLocSavedAt == 0L) {
            // Hava sicakligi icin son konum saklanir; GPS'siz acilista da calissin.
            lastLocSavedAt = now;
            prefs.edit().putFloat("lat", (float) lastLat).putFloat("lon", (float) lastLon).apply();
        }
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
    // Tam ekran pencereler icin ortak parcalar
    // ------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Ustunde buyuk bir "geri" satiri olan tam ekran pencere. */
    private Dialog fullScreen(String title, View content) {
        final Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        TextView back = new TextView(this);
        back.setText("‹   " + title);
        back.setTextColor(WHITE);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(20), 0, dp(20), 0);
        back.setBackgroundColor(ROW);
        back.setOnClickListener(v -> d.dismiss());
        root.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        return d;
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private void showMenu() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(12));
        final Dialog d = fullScreen("Menü", list);

        addTile(list, "Yandex Navigasyon", () -> {
            d.dismiss();
            launch(NAV_PKG, "Yandex Navigasyon");
        });
        addTile(list, "Spotify", () -> {
            d.dismiss();
            launch(MUSIC_PKG, "Spotify");
        });
        addTile(list, "Uygulamalar", () -> {
            d.dismiss();
            showApps();
        });
        addTile(list, "Ayarlar", () -> {
            d.dismiss();
            showSettings();
        });
        d.show();
    }

    /** Ekrani esit paylasan buyuk tus; eldivenle basmak icin. */
    private void addTile(LinearLayout parent, String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(LINE);
        t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(0, dp(6), 0, dp(6));
        parent.addView(t, lp);
    }

    // ------------------------------------------------------------------
    // Uygulamalar
    // ------------------------------------------------------------------

    /** Panel ana ekran oldugu icin telefondaki diger uygulamalara buradan ulasilir. */
    private void showApps() {
        final PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<AppItem> items = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            AppItem a = new AppItem();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.pkg = ri.activityInfo.packageName;
            a.cls = ri.activityInfo.name;
            try {
                a.icon = ri.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            items.add(a);
        }
        Collections.sort(items, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(104));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(8));
        grid.setPadding(dp(10), dp(12), dp(10), dp(12));
        grid.setClipToPadding(false);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) convertView;
                if (cell == null) {
                    cell = new LinearLayout(MainActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(dp(6), dp(12), dp(6), dp(10));
                    cell.setBackgroundColor(ROW);
                    ImageView iv = new ImageView(MainActivity.this);
                    cell.addView(iv, new LinearLayout.LayoutParams(dp(60), dp(60)));
                    TextView tv = new TextView(MainActivity.this);
                    tv.setTextColor(WHITE);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                    tv.setGravity(Gravity.CENTER);
                    tv.setLines(2);
                    tv.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dp(8);
                    cell.addView(tv, lp);
                }
                AppItem a = items.get(position);
                ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
                ((TextView) cell.getChildAt(1)).setText(a.label);
                return cell;
            }
        });

        final Dialog d = fullScreen("Uygulamalar (" + items.size() + ")", grid);
        grid.setOnItemClickListener((parent, v, position, id) -> {
            AppItem a = items.get(position);
            try {
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(a.pkg, a.cls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                d.dismiss();
            } catch (Exception e) {
                toast("Açılamadı");
            }
        });
        d.show();
    }

    // ------------------------------------------------------------------
    // Ayarlar
    // ------------------------------------------------------------------

    private void showSettings() {
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(settingsList);
        settingsDialog = fullScreen("Ayarlar", scroll);
        fillSettings();
        settingsDialog.show();
    }

    private void fillSettings() {
        if (settingsList == null) return;
        settingsList.removeAllViews();

        addHeader("PANEL");
        final boolean isHome = isDefaultHome();
        addRow("Ana ekran yap",
                isHome ? "Dominar Panel şu an ana ekran" : "Şu an: " + currentHomeLabel() + ". Değiştirmek için dokun.",
                isHome, this::requestHomeRole);

        final int keep = prefs.getInt("keepOn", 0);
        addRow("Ekranı açık tut", KEEP_ON_NAMES[keep] + "  (panel ekrandayken)", true, () -> {
            prefs.edit().putInt("keepOn", (keep + 1) % 3).apply();
            applyKeepScreenOn();
            fillSettings();
        });

        final int bright = prefs.getInt("bright", 0);
        addRow("Parlaklık", BRIGHT_NAMES[bright] + "  (panel ekrandayken)", true, () -> {
            prefs.edit().putInt("bright", (bright + 1) % 2).apply();
            applyBrightness();
            fillSettings();
        });

        final int orient = prefs.getInt("orient", 0);
        addRow("Ekran yönü", ORIENT_NAMES[orient], true, () -> {
            prefs.edit().putInt("orient", (orient + 1) % 3).apply();
            applyOrientation();
            fillSettings();
        });

        addHeader("İZİNLER");
        boolean notif = isListenerEnabled();
        addRow("Bildirim erişimi", notif ? "Açık" : "KAPALI. Navigasyon ve müzik bilgisi gelmez, dokun.", notif,
                () -> openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));

        boolean loc = hasLocationPermission();
        addRow("Konum izni", loc ? "Verildi" : "YOK. Hız ve hava sıcaklığı çalışmaz, dokun.", loc, () -> {
            if (hasLocationPermission()) {
                openAppDetails();
            } else {
                requestLocationPermission();
            }
        });

        boolean battOk = isIgnoringBatteryOptimizations();
        addRow("Pil optimizasyonu",
                battOk ? "Muaf (arka planda kapatılmaz)" : "KISITLI. Android paneli kapatabilir, dokun.",
                battOk, () -> openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));

        addHeader("BİLGİ");
        addRow("Pil sıcaklığı",
                Float.isNaN(battTemp) ? "Bilinmiyor"
                        : String.format(Locale.ROOT, "%.1f °C", battTemp) + "  (40 °C üstünde üst şeritte uyarı çıkar)",
                Float.isNaN(battTemp) || battTemp < 40f, null);

        String weather;
        if (Float.isNaN(Weather.temp)) {
            if (Double.isNaN(lastLat)) {
                weather = "Konum henüz bilinmiyor";
            } else {
                weather = "Alınamadı" + (Weather.lastError.isEmpty() ? "" : " (" + Weather.lastError + ")");
            }
        } else {
            long min = Weather.ageMs() / 60000L;
            weather = String.format(Locale.ROOT, "%.1f °C", Weather.temp) + ", " + min + " dk önce"
                    + (Weather.lastError.isEmpty() ? "" : "  (son deneme başarısız)");
        }
        addRow("Hava sıcaklığı", weather + ". Yenilemek için dokun.", !Float.isNaN(Weather.temp), () -> {
            updateWeather(true);
            toast("Hava sıcaklığı isteniyor");
            handler.postDelayed(this::refreshSettingsIfOpen, 4000L);
        });

        addRow("Ham navigasyon verisi", "Yandex'in bildiriminden okunan her şey", true, this::showRawNav);
        addRow("Android ayarları", "Telefonun kendi ayar ekranı", true, () -> openSettings(Settings.ACTION_SETTINGS));
        addRow("Sürüm", view.version, true, null);
    }

    private void refreshSettingsIfOpen() {
        if (settingsDialog != null && settingsDialog.isShowing()) fillSettings();
    }

    private void addHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(GREEN);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(20), dp(22), dp(20), dp(8));
        settingsList.addView(t);
    }

    /** ok=false ise alt yazi turuncu gosterilir (dikkat isteyen durum). */
    private void addRow(String title, String value, boolean ok, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setBackgroundColor(ROW);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        row.addView(t);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(ok ? GREY : AMBER);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        v.setPadding(0, dp(4), 0, 0);
        row.addView(v);

        if (action != null) row.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(2));
        settingsList.addView(row, lp);
    }

    // --- Ana ekran ---

    private ResolveInfo currentHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        return getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
    }

    private boolean isDefaultHome() {
        ResolveInfo ri = currentHome();
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private String currentHomeLabel() {
        ResolveInfo ri = currentHome();
        if (ri == null || ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) {
            return "seçilmemiş";
        }
        return String.valueOf(ri.loadLabel(getPackageManager()));
    }

    /** Android'in onay penceresini acar; uygulama kendini sessizce ana ekran yapamaz. */
    private void requestHomeRole() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(NavParser.TAG, "ana ekran rolu istenemedi", t);
        }
        openSettings(Settings.ACTION_HOME_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME) refreshSettingsIfOpen();
    }

    // --- Ekran ---

    private void applyKeepScreenOn() {
        int mode = prefs.getInt("keepOn", 0);
        boolean on = mode == 0 || (mode == 1 && charging);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void applyBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = prefs.getInt("bright", 0) == 1
                ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private void applyOrientation() {
        int orient = prefs.getInt("orient", 0);
        if (orient == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if (orient == 2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Ayar ekranı açılamadı");
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Ayar ekranı açılamadı");
        }
    }

    /** Navigasyon bildiriminden okunan her seyi gosterir; ayiklama icin. */
    private void showRawNav() {
        final String dump = NavState.rawDump.isEmpty()
                ? (isListenerEnabled()
                        ? "Henüz navigasyon bildirimi görülmedi.\n\nYandex Navigasyon'da rota başlatıp buraya dön."
                        : "Bildirim erişimi kapalı. Ayarlar > Bildirim erişimi'nden aç.")
                : NavState.rawDump;
        TextView tv = new TextView(this);
        tv.setText(dump);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        int p = dp(20);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Ham navigasyon verisi")
                .setView(sv)
                .setPositiveButton("Kapat", null)
                .setNeutralButton("Kopyala", (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("nav", dump));
                    toast("Kopyalandı");
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
