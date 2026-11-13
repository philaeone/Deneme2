package io.github.philaeone.dominarpanel;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Navigasyon uygulamalarinin (oncelik Yandex Navigasyon) bildirimlerinden
 * "kac metre sonra, hangi yone" bilgisini cikarir.
 *
 * Yandex bu veriyi resmi bir yolla vermedigi icin tek kaynak bildirimdir.
 * Bildirimin bicimi bilinmedigi icin eldeki her seyi toplar: standart metin
 * alanlari, buyuk simge ve ozel bildirim gorunumunun icindeki yazilar/resimler.
 */
final class NavParser {
    static final String TAG = "DominarPanel";

    private static final Set<String> NAV_PACKAGES = new HashSet<>(Arrays.asList(
            "ru.yandex.yandexnavi",
            "ru.yandex.yandexmaps",
            "com.google.android.apps.maps",
            "net.osmand",
            "net.osmand.plus",
            "app.comaps",
            "app.organicmaps"));

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String B0 = "(?<![\\p{L}])(?:";
    private static final String B1 = ")(?![\\p{L}])";

    private static final Pattern DIST = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m|км|м)(?![\\p{L}])", FLAGS);
    private static final Pattern P_RIGHT = Pattern.compile(
            B0 + "sağa|sağdan|sağda|sağdaki|sağ|right|направо|вправо|правее" + B1, FLAGS);
    private static final Pattern P_LEFT = Pattern.compile(
            B0 + "sola|soldan|solda|soldaki|sol|left|налево|влево|левее" + B1, FLAGS);
    private static final Pattern P_UTURN = Pattern.compile(
            B0 + "u dönüşü|u-dönüşü|u dönüş|geri dönün|geri dön|u-turn|u turn|разворот|развернитесь" + B1, FLAGS);
    private static final Pattern P_STRAIGHT = Pattern.compile(
            B0 + "düz|dümdüz|straight|прямо" + B1, FLAGS);
    private static final Pattern P_SLIGHT = Pattern.compile(
            B0 + "hafif|hafifçe|slight|slightly|keep|плавно|держитесь" + B1, FLAGS);
    private static final Pattern P_ROUNDABOUT = Pattern.compile(
            "döner kavşak|dönel kavşak|roundabout|кольц", FLAGS);
    private static final Pattern P_ARRIVE = Pattern.compile(
            B0 + "vardınız|ulaştınız|arrived|прибыли" + B1, FLAGS);

    private static final Pattern P_DIST_ONLY = Pattern.compile(
            "^\\s*(\\d+(?:[.,]\\d+)?)\\s*(km|m|км|м)\\.?\\s*$", FLAGS);
    private static final Pattern P_CLOCK = Pattern.compile("^\\s*\\d{1,2}[:.]\\d{2}\\s*$");
    private static final Pattern P_DURATION = Pattern.compile(
            "^\\s*(?:\\d+\\s*(?:saat|sa|dk|dak|sn|hr|h|min|мин|ч)\\.?\\s*)+$", FLAGS);
    private static final Set<String> BUTTON_TEXTS = new HashSet<>(Arrays.asList(
            "rotayı sonlandır", "rotayi sonlandir", "end route", "finish route", "exit navigation",
            "завершить маршрут", "navigasyonu kapat", "çıkış", "kapat"));

    /** Bu calismada gorulen farkli simgeler: imza -> dokum satirlari. */
    private static final Map<String, String> SEEN_ICONS = new LinkedHashMap<>();

    private static final class Entry {
        String pkg;
        boolean ongoing;
        long when;
        final List<String> texts = new ArrayList<>();
        final List<String> buttons = new ArrayList<>();
        final List<Bitmap> icons = new ArrayList<>();
        String distance = "";
        float meters = -1f;
        String instruction = "";
        String extra = "";
        int direction = NavState.DIR_NONE;
        String directionSource = "";
        Bitmap icon;
        boolean iconGlyph;
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private NavParser() {}

    static boolean isNavPackage(String pkg) {
        return pkg != null && NAV_PACKAGES.contains(pkg);
    }

    static synchronized void posted(Context ctx, StatusBarNotification sbn) {
        if (sbn == null || !isNavPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;

        Entry e = new Entry();
        e.pkg = sbn.getPackageName();
        e.ongoing = sbn.isOngoing();
        e.when = System.currentTimeMillis();

        try {
            Bundle x = n.extras;
            if (x != null) {
                add(e.texts, x.getCharSequence(Notification.EXTRA_TITLE));
                add(e.texts, x.getCharSequence(Notification.EXTRA_TITLE_BIG));
                add(e.texts, x.getCharSequence(Notification.EXTRA_TEXT));
                add(e.texts, x.getCharSequence(Notification.EXTRA_BIG_TEXT));
                add(e.texts, x.getCharSequence(Notification.EXTRA_SUB_TEXT));
                add(e.texts, x.getCharSequence(Notification.EXTRA_INFO_TEXT));
                add(e.texts, x.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
                CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) {
                    for (CharSequence line : lines) add(e.texts, line);
                }
            }
            Icon large = n.getLargeIcon();
            if (large != null) {
                Bitmap b = toBitmap(large.loadDrawable(ctx));
                if (b != null) e.icons.add(b);
            }
        } catch (Throwable t) {
            e.texts.add("[bildirim okunamadi: " + t + "]");
        }

        collect(ctx, n.bigContentView, e);
        collect(ctx, n.contentView, e);
        collect(ctx, n.headsUpContentView, e);

        parse(e);
        ENTRIES.put(sbn.getKey(), e);
        publish();
    }

    static synchronized void removed(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (ENTRIES.remove(sbn.getKey()) != null) publish();
    }

    static synchronized void clear() {
        ENTRIES.clear();
        publish();
    }

    // ------------------------------------------------------------------

    private static void add(List<String> out, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().replace('\n', ' ').trim();
        if (s.isEmpty() || out.contains(s)) return;
        out.add(s);
    }

    /** Ozel bildirim gorunumunu sisirip icindeki yazi ve resimleri toplar. */
    private static void collect(Context ctx, RemoteViews rv, Entry e) {
        if (rv == null) return;
        try {
            Context app = ctx.getApplicationContext();
            View root = rv.apply(app, new FrameLayout(app));
            walk(root, e);
        } catch (Throwable t) {
            add(e.texts, "[ozel gorunum acilamadi: " + t.getClass().getSimpleName() + "]");
        }
    }

    private static void walk(View v, Entry e) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            String low = cs == null ? "" : cs.toString().trim().toLowerCase(new Locale("tr", "TR"));
            if (v instanceof Button || BUTTON_TEXTS.contains(low)) {
                add(e.buttons, cs);
            } else {
                add(e.texts, cs);
            }
        } else if (v instanceof ImageView) {
            Bitmap b = toBitmap(((ImageView) v).getDrawable());
            if (b != null && e.icons.size() < 6) e.icons.add(b);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), e);
        }
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d == null) return null;
        try {
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null) return b;
            }
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = 128;
                h = 128;
            }
            float scale = Math.min(1f, 256f / Math.max(w, h));
            int bw = Math.max(1, Math.round(w * scale));
            int bh = Math.max(1, Math.round(h * scale));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, bw, bh);
            d.draw(c);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void parse(Entry e) {
        String remainDist = "";
        String remainTime = "";
        String eta = "";
        List<String> others = new ArrayList<>();

        for (String s : e.texts) {
            if (s.startsWith("[")) continue;
            if (BUTTON_TEXTS.contains(s.toLowerCase(new Locale("tr", "TR")))) continue;
            Matcher only = P_DIST_ONLY.matcher(s);
            if (only.matches()) {
                String d = only.group(1) + " " + only.group(2).toLowerCase(Locale.ROOT);
                if (e.distance.isEmpty()) {
                    e.distance = d;
                    e.meters = toMeters(only.group(1), only.group(2));
                } else if (remainDist.isEmpty()) {
                    remainDist = d;
                }
                continue;
            }
            if (P_CLOCK.matcher(s).matches()) {
                if (eta.isEmpty()) eta = s.trim();
                continue;
            }
            if (P_DURATION.matcher(s).matches()) {
                if (remainTime.isEmpty()) remainTime = s.trim().replaceAll("\\.$", "");
                continue;
            }
            Matcher m = DIST.matcher(s);
            if (e.distance.isEmpty() && m.find()) {
                // "200 m · Ornek Cad." gibi tek satirda gelen bicim.
                e.distance = m.group(1) + " " + m.group(2).toLowerCase(Locale.ROOT);
                e.meters = toMeters(m.group(1), m.group(2));
                String rest = (s.substring(0, m.start()) + " " + s.substring(m.end()))
                        .replaceAll("^[\\s,.;:·\\-–]+|[\\s,.;:·\\-–]+$", "")
                        .replaceAll("\\s{2,}", " ");
                if (rest.length() >= 2) others.add(0, rest);
                continue;
            }
            others.add(s);
        }

        if (!others.isEmpty()) e.instruction = others.remove(0);

        StringBuilder extra = new StringBuilder();
        if (!remainDist.isEmpty()) extra.append("Kalan ").append(remainDist);
        if (!remainTime.isEmpty()) {
            extra.append(extra.length() > 0 ? " · " : "Kalan ").append(remainTime);
        }
        if (!eta.isEmpty()) {
            if (extra.length() > 0) extra.append(" · ");
            extra.append("Varış ").append(eta);
        }
        if (extra.length() == 0 && !others.isEmpty()) extra.append(others.get(0));
        e.extra = extra.toString();

        // Manevra simgesi: kareye yakin ilk resim (ilerleme cubugu gibi enli resimler degil).
        IconShape.Result shape = null;
        for (Bitmap b : e.icons) {
            float ratio = b.getWidth() / (float) Math.max(1, b.getHeight());
            if (ratio < 0.8f || ratio > 1.25f) continue;
            IconShape.Result r = IconShape.analyze(b);
            remember(r);
            if (e.icon == null) {
                e.icon = b;
                e.iconGlyph = r.glyph;
                shape = r;
            }
        }

        StringBuilder all = new StringBuilder();
        for (String s : e.texts) all.append(s).append(" \n ");
        e.direction = detectDirection(all.toString());
        if (e.direction != NavState.DIR_NONE) {
            e.directionSource = "yazi";
        } else if (shape != null && shape.direction != NavState.DIR_NONE) {
            e.direction = shape.direction;
            e.directionSource = "simge " + shape.signature;
        }
    }

    private static float toMeters(String number, String unit) {
        try {
            float v = Float.parseFloat(number.replace(',', '.'));
            String u = unit.toLowerCase(Locale.ROOT);
            return (u.equals("km") || u.equals("км")) ? v * 1000f : v;
        } catch (Exception ex) {
            return -1f;
        }
    }

    private static void remember(IconShape.Result r) {
        if (r == null || r.signature.isEmpty() || SEEN_ICONS.containsKey(r.signature)) return;
        if (SEEN_ICONS.size() >= 24) return;
        SEEN_ICONS.put(r.signature, "simge " + r.signature + "  tahmin=" + IconShape.name(r.direction)
                + "  kayma=" + String.format(Locale.ROOT, "%.2f", r.offset) + "\n" + r.ascii);
    }

    private static int detectDirection(String s) {
        // Turkce buyuk harfler (I, İ) icin iki farkli kucultme birlikte aranir.
        String l = s.toLowerCase(Locale.ROOT) + " \n " + s.toLowerCase(new Locale("tr", "TR"));
        if (P_UTURN.matcher(l).find()) return NavState.DIR_UTURN;
        if (P_ROUNDABOUT.matcher(l).find()) return NavState.DIR_ROUNDABOUT;
        boolean right = P_RIGHT.matcher(l).find();
        boolean left = P_LEFT.matcher(l).find();
        boolean slight = P_SLIGHT.matcher(l).find();
        if (right && !left) return slight ? NavState.DIR_SLIGHT_RIGHT : NavState.DIR_RIGHT;
        if (left && !right) return slight ? NavState.DIR_SLIGHT_LEFT : NavState.DIR_LEFT;
        if (right && left) {
            // Ikisi de geciyorsa metinde once gecen yon ilk manevradir.
            Matcher mr = P_RIGHT.matcher(l);
            Matcher ml = P_LEFT.matcher(l);
            int ir = mr.find() ? mr.start() : Integer.MAX_VALUE;
            int il = ml.find() ? ml.start() : Integer.MAX_VALUE;
            return ir < il ? NavState.DIR_RIGHT : NavState.DIR_LEFT;
        }
        if (P_STRAIGHT.matcher(l).find()) return NavState.DIR_STRAIGHT;
        if (P_ARRIVE.matcher(l).find()) return NavState.DIR_ARRIVE;
        return NavState.DIR_NONE;
    }

    private static void publish() {
        Entry best = null;
        boolean present = false;
        for (Entry e : ENTRIES.values()) {
            if (e.ongoing) present = true;
            boolean useful = !e.distance.isEmpty() || e.direction != NavState.DIR_NONE;
            if (!useful) continue;
            if (best == null
                    || (best.distance.isEmpty() && !e.distance.isEmpty())
                    || (best.distance.isEmpty() == e.distance.isEmpty() && e.when >= best.when)) {
                best = e;
            }
        }

        if (best != null) {
            if (!best.distance.equals(NavState.distance)) {
                NavState.distanceAt = SystemClock.elapsedRealtime();
            }
            NavState.distance = best.distance;
            NavState.distanceMeters = best.meters;
            NavState.instruction = best.instruction;
            NavState.extra = best.extra;
            NavState.direction = best.direction;
            NavState.icon = best.icon;
            NavState.iconGlyph = best.iconGlyph;
            NavState.active = true;
        } else {
            NavState.active = false;
            NavState.distance = "";
            NavState.distanceMeters = -1f;
            NavState.instruction = "";
            NavState.extra = "";
            NavState.direction = NavState.DIR_NONE;
            NavState.icon = null;
            NavState.iconGlyph = false;
        }
        NavState.appPresent = present || !ENTRIES.isEmpty();

        StringBuilder d = new StringBuilder();
        if (ENTRIES.isEmpty()) {
            d.append("Navigasyon uygulamasindan bildirim yok.\n\n")
             .append("Yandex Navigasyon'da bir rota baslatip bu ekrana geri don.");
        }
        int n = 0;
        for (Map.Entry<String, Entry> me : ENTRIES.entrySet()) {
            Entry e = me.getValue();
            n++;
            d.append("#").append(n).append("  ").append(e.pkg)
             .append(e.ongoing ? "  (kalici)" : "  (gecici)").append('\n');
            for (int i = 0; i < e.texts.size(); i++) {
                d.append("  yazi ").append(i + 1).append(": ").append(e.texts.get(i)).append('\n');
            }
            if (e.texts.isEmpty()) d.append("  (yazi yok)\n");
            for (int i = 0; i < e.icons.size(); i++) {
                Bitmap b = e.icons.get(i);
                d.append("  resim ").append(i + 1).append(": ")
                 .append(b.getWidth()).append("x").append(b.getHeight()).append('\n');
            }
            for (String b : e.buttons) d.append("  tus: ").append(b).append('\n');
            d.append("  => mesafe=[").append(e.distance).append("] yon=").append(IconShape.name(e.direction))
             .append(e.directionSource.isEmpty() ? "" : " (" + e.directionSource + ")")
             .append("\n  => talimat=[").append(e.instruction).append("]")
             .append("\n  => ozet=[").append(e.extra).append("]\n\n");
        }
        if (!SEEN_ICONS.isEmpty()) {
            d.append("GORULEN SIMGELER (").append(SEEN_ICONS.size()).append(")\n\n");
            for (String v : SEEN_ICONS.values()) d.append(v).append('\n');
        }
        String dump = d.toString();
        if (!dump.equals(NavState.rawDump)) {
            NavState.rawDump = dump;
            Log.i(TAG, dump);
        }
        Bus.changed();
    }
}
