package io.github.philaeone.artci;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * Panelin tamami tek bir cizim yuzeyidir: ust serit, hiz kadrani, navigasyon
 * ve muzik. Dikey ve yatay icin bolgeler ayri hesaplanir, icerik ayni cizim
 * kodunu kullanir.
 *
 * Gorunus: siyah zemin, kesik koseli cerceveler, tek vurgu rengi. Vurgu rengi
 * varsayilan olarak kirmizidir; ayara gore calan sarkinin kapagindan secilir.
 * Animasyonlar yalnizca bir sey degistiginde oynar; bos dururken ekran
 * saniyede bir kez cizilir.
 */
final class PanelView extends View {

    interface Actions {
        void onAction(int id);
    }

    static final int A_NONE = 0;
    static final int A_OPEN_NAV = 3;
    static final int A_OPEN_MUSIC = 4;
    static final int A_MENU = 5;
    static final int A_PREV = 6;
    static final int A_PLAY = 7;
    static final int A_NEXT = 8;
    static final int A_GRANT_NOTIF = 11;
    static final int A_GRANT_LOC = 12;
    static final int A_VOL_DOWN = 13;
    static final int A_VOL_UP = 14;
    /** Navigasyon kutusunun kendisine dokunma; A_OPEN_NAV ile ayni isi yapar. */
    private static final int A_NAV_BOX = 103;

    static final int DEF_ACCENT = 0xFFFF2A2A;
    static final int DEF_DIM = 0xFF5A0000;

    private static final int BG = 0xFF050505;
    private static final int PANEL = 0xFF111111;
    private static final int BUTTON = 0xFF0B0B0B;
    private static final int TRACK = 0xFF242424;
    private static final int ROAD = 0xFF272727;
    private static final int ROAD_EDGE = 0xFF505050;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFF8C8C8C;
    private static final int AMBER = 0xFFFFC400;

    private static final float MAX_SPEED = 160f;
    /** Perspektif sabiti (metre): kucuk deger yakindaki mesafeleri daha cok acar. */
    private static final float PERSP_K = 60f;
    /** Motor isaretinin kameranin onundeki mesafesi (metre). */
    private static final float BIKE_AHEAD = 5f;
    private static final float FLOW_STEP = 14f;
    private static final long INTRO_MS = 1100L;
    private static final long FLASH_MS = 220L;
    private static final long COVER_FADE_MS = 350L;

    // --- MainActivity'nin doldurdugu veriler ---
    float speed = -1f;
    boolean locPermission = true;
    boolean listenerEnabled = true;
    String clock = "";
    int battery = -1;
    boolean charging;
    float battTemp = Float.NaN;
    /** Medya sesi, 0..1; bilinmiyorsa -1. */
    float volume = -1f;
    /** Hava sicakligi (°C); bilinmiyorsa NaN. */
    float weatherTemp = Float.NaN;
    /** Hava verisi eskiyse soluk gosterilir. */
    boolean weatherStale;
    String version = "";
    String bikeName = "";
    /** true: vurgu rengi kapaktan; false: hep varsayilan kirmizi. */
    boolean coverColors = true;

    private final Actions actions;

    // --- Tema: gecerli deger hedefe dogru yumusakca kayar ---
    private int accent = DEF_ACCENT;
    private int accentDim = DEF_DIM;
    private int accentTarget = DEF_ACCENT;
    private int dimTarget = DEF_DIM;
    private Bitmap curArt;
    private Bitmap prevArt;
    private long artChangedAt;
    private boolean themedWithCover = true;

    // --- Animasyon durumu ---
    private long introStart;
    private long lastFrame;
    private float shownSpeed;
    private float flow;
    private int flashId = A_NONE;
    private long flashStart;
    private boolean animating;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path framePath = new Path();
    private final RectF tmp = new RectF();
    private final RectF tmp2 = new RectF();
    private final RectF dst = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private ColorFilter accentTint = new PorterDuffColorFilter(DEF_ACCENT, PorterDuff.Mode.SRC_IN);
    private final ColorFilter mutedTint = new PorterDuffColorFilter(MUTED, PorterDuff.Mode.SRC_IN);
    private final ColorFilter whiteTint = new PorterDuffColorFilter(WHITE, PorterDuff.Mode.SRC_IN);

    private final RectF rStatus = new RectF();
    private final RectF rMenu = new RectF();
    private final RectF rSpeed = new RectF();
    private final RectF rNav = new RectF();
    private final RectF rMusic = new RectF();
    private final RectF rMusicInfo = new RectF();
    private final RectF rPrev = new RectF();
    private final RectF rPlay = new RectF();
    private final RectF rNext = new RectF();
    private final RectF rVolDown = new RectF();
    private final RectF rVolUp = new RectF();

    /** 720 piksel genisliginde bir ekranda 1 piksele denk gelen birim. */
    private float u = 1f;
    private float pad = 16f;
    private float cut = 20f;
    private int pressed = A_NONE;

    PanelView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
    }

    int accentColor() {
        return accent;
    }

    int accentDimColor() {
        return accentDim;
    }

    // ------------------------------------------------------------------
    // Yerlesim
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutRegions(w, h);
    }

    private void layoutRegions(int w, int h) {
        u = Math.min(w, h) / 720f;
        pad = 18f * u;
        cut = 22f * u;
        float ip = pad * 0.8f;

        if (h >= w) {
            // Dikey: ust serit, hiz, navigasyon, muzik.
            float y = pad;
            float statusH = h * 0.052f;
            rStatus.set(pad, y, w - pad, y + statusH);
            y += statusH + pad * 1.3f;

            float speedH = h * 0.185f;
            rSpeed.set(pad, y, w - pad, y + speedH);
            y += speedH + pad;

            float bottom = h - pad;
            float musicH = h * 0.27f;
            rMusic.set(pad, bottom - musicH, w - pad, bottom);
            rNav.set(pad, y, w - pad, rMusic.top - pad);

            float x0 = rMusic.left + ip;
            float x1 = rMusic.right - ip;
            float rowH = h * 0.095f;
            float by1 = rMusic.bottom - ip;
            float by0 = by1 - rowH;
            rMusicInfo.set(x0, rMusic.top + ip, x1, by0 - ip);
            // Sira: onceki, oynat (1.5 kat genis), sonraki, ses sutunu.
            float unit = (x1 - x0 - 3f * ip) / 4.5f;
            float x = x0;
            rPrev.set(x, by0, x + unit, by1);
            x += unit + ip;
            rPlay.set(x, by0, x + unit * 1.5f, by1);
            x += unit * 1.5f + ip;
            rNext.set(x, by0, x + unit, by1);
            x += unit + ip;
            float half = (by1 - by0 - ip * 0.6f) / 2f;
            rVolUp.set(x, by0, x1, by0 + half);
            rVolDown.set(x, by1 - half, x1, by1);
        } else {
            // Yatay: solda hiz, ortada navigasyon, sagda muzik.
            float statusH = h * 0.105f;
            rStatus.set(pad, pad, w - pad, pad + statusH);
            float top = pad + statusH + pad * 1.3f;
            float bottom = h - pad;

            float leftW = w * 0.25f;
            rSpeed.set(pad, top, pad + leftW, bottom);

            float rightW = w * 0.29f;
            float rx = w - pad - rightW;
            rMusic.set(rx, top, w - pad, bottom);
            rNav.set(2f * pad + leftW, top, rx - pad, bottom);

            float x0 = rMusic.left + ip;
            float x1 = rMusic.right - ip;
            float inner = rMusic.height() - 4f * ip;
            float infoH = inner * 0.38f;
            float transH = inner * 0.36f;
            rMusicInfo.set(x0, rMusic.top + ip, x1, rMusic.top + ip + infoH);
            float ty0 = rMusicInfo.bottom + ip;
            float unit = (x1 - x0 - 2f * ip) / 3.5f;
            rPrev.set(x0, ty0, x0 + unit, ty0 + transH);
            rPlay.set(x0 + unit + ip, ty0, x0 + unit * 2.5f + ip, ty0 + transH);
            rNext.set(x1 - unit, ty0, x1, ty0 + transH);
            float vy0 = ty0 + transH + ip;
            float w2 = (x1 - x0 - ip) / 2f;
            rVolDown.set(x0, vy0, x0 + w2, rMusic.bottom - ip);
            rVolUp.set(x0 + w2 + ip, vy0, x1, rMusic.bottom - ip);
        }
        rMenu.set(rStatus.left, rStatus.top, rStatus.left + rStatus.height() * 1.5f, rStatus.bottom);
    }

    // ------------------------------------------------------------------
    // Cizim dongusu
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);
        if (getWidth() == 0 || getHeight() == 0) return;

        long now = SystemClock.uptimeMillis();
        if (introStart == 0L) introStart = now;
        float dt = lastFrame == 0L ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        animating = false;

        stepTheme(now);
        float intro = Math.min(1f, (now - introStart) / (float) INTRO_MS);
        if (intro < 1f) animating = true;

        // Hiz ibresi hedefe yumusakca yaklasir.
        float target = speed < 0f ? 0f : Math.min(speed, MAX_SPEED);
        float diff = target - shownSpeed;
        if (Math.abs(diff) > 0.4f) {
            shownSpeed += diff * Math.min(1f, dt * 7f + 0.02f);
            animating = true;
        } else {
            shownSpeed = target;
        }
        // Rota uzerindeki akis isaretleri hizla orantili ilerler.
        if (speed > 1f) flow = (flow + speed / 3.6f * dt) % FLOW_STEP;

        drawStatus(c);
        drawSpeed(c, intro);
        drawNav(c);
        drawMusic(c, now);

        if (flashId != A_NONE) {
            if (now - flashStart >= FLASH_MS) flashId = A_NONE; else animating = true;
        }
        // Acilista paneller karanliktan belirir.
        if (intro < 0.4f) {
            int a = Math.round(255f * (1f - intro / 0.4f));
            c.drawColor(Color.argb(a, 5, 5, 5));
        }
        if (animating) postInvalidateOnAnimation();
    }

    /** Kapak degistiyse hedef renkleri secer, gecerli renkleri hedefe yaklastirir. */
    private void stepTheme(long now) {
        Bitmap art = MediaState.art;
        if (art != curArt || themedWithCover != coverColors) {
            if (art != curArt) {
                prevArt = curArt;
                curArt = art;
                artChangedAt = now;
            }
            themedWithCover = coverColors;
            int[] t = coverColors ? CoverColor.theme(art) : null;
            if (t != null) {
                accentTarget = t[0];
                dimTarget = t[1];
            } else {
                accentTarget = DEF_ACCENT;
                dimTarget = DEF_DIM;
            }
        }
        if (accent != accentTarget || accentDim != dimTarget) {
            accent = approach(accent, accentTarget);
            accentDim = approach(accentDim, dimTarget);
            accentTint = new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN);
            animating = true;
        }
        if (prevArt != null) {
            if (now - artChangedAt >= COVER_FADE_MS) prevArt = null; else animating = true;
        }
    }

    private static int approach(int from, int to) {
        if (from == to) return to;
        int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = channel(from & 0xFF, to & 0xFF);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to) {
        int d = to - from;
        if (Math.abs(d) <= 3) return to;
        return from + Math.round(d * 0.14f);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ------------------------------------------------------------------
    // Cerceve ve tus
    // ------------------------------------------------------------------

    /** Dort kosesi kesik dikdortgen. */
    private void buildFrame(Path p, RectF r, float k) {
        p.reset();
        p.moveTo(r.left + k, r.top);
        p.lineTo(r.right - k, r.top);
        p.lineTo(r.right, r.top + k);
        p.lineTo(r.right, r.bottom - k);
        p.lineTo(r.right - k, r.bottom);
        p.lineTo(r.left + k, r.bottom);
        p.lineTo(r.left, r.bottom - k);
        p.lineTo(r.left, r.top + k);
        p.close();
    }

    /** Panel: koyu zemin, hafif parlayan cerceve, iki capraz kosede parlak vurgu. */
    private void drawPanel(Canvas c, RectF r) {
        buildFrame(framePath, r, cut);
        fill.setColor(PANEL);
        c.drawPath(framePath, fill);

        stroke.setStrokeJoin(Paint.Join.MITER);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setColor(accentDim);
        stroke.setStrokeWidth(2.5f * u);
        stroke.setShadowLayer(9f * u, 0f, 0f, withAlpha(accent, 80));
        c.drawPath(framePath, stroke);
        stroke.clearShadowLayer();

        float len = cut * 1.6f;
        stroke.setColor(accent);
        stroke.setStrokeWidth(3.5f * u);
        path.reset();
        path.moveTo(r.left, r.top + cut + len);
        path.lineTo(r.left, r.top + cut);
        path.lineTo(r.left + cut, r.top);
        path.lineTo(r.left + cut + len, r.top);
        path.moveTo(r.right, r.bottom - cut - len);
        path.lineTo(r.right, r.bottom - cut);
        path.lineTo(r.right - cut, r.bottom);
        path.lineTo(r.right - cut - len, r.bottom);
        c.drawPath(path, stroke);
    }

    /** Yol gorunumunun ustune binen koyu bilgi kutusu. */
    private void drawChip(Canvas c, RectF r) {
        buildFrame(framePath, r, Math.min(cut * 0.45f, r.height() * 0.2f));
        fill.setColor(0xEE0B0B0B);
        c.drawPath(framePath, fill);
        stroke.setStrokeJoin(Paint.Join.MITER);
        stroke.setColor(accentDim);
        stroke.setStrokeWidth(2f * u);
        c.drawPath(framePath, stroke);
    }

    /**
     * Tus cercevesi. Basiliyken vurgu rengiyle dolar, birakinca kisa surede soner.
     * @return simgenin cizilecegi renk
     */
    private int drawKey(Canvas c, RectF r, int id, boolean bright) {
        float k = Math.min(cut * 0.6f, Math.min(r.width(), r.height()) * 0.2f);
        buildFrame(framePath, r, k);
        boolean down = pressed == id;
        fill.setColor(down ? accent : BUTTON);
        c.drawPath(framePath, fill);
        if (!down && flashId == id) {
            float f = 1f - (SystemClock.uptimeMillis() - flashStart) / (float) FLASH_MS;
            if (f > 0f) {
                fill.setColor(withAlpha(accent, Math.round(200f * f)));
                c.drawPath(framePath, fill);
            }
        }
        stroke.setStrokeJoin(Paint.Join.MITER);
        stroke.setColor(bright || down ? accent : accentDim);
        stroke.setStrokeWidth(2.5f * u);
        if (bright) stroke.setShadowLayer(8f * u, 0f, 0f, withAlpha(accent, 110));
        c.drawPath(framePath, stroke);
        stroke.clearShadowLayer();
        return down ? BG : WHITE;
    }

    // ------------------------------------------------------------------
    // Ust serit
    // ------------------------------------------------------------------

    private void drawStatus(Canvas c) {
        int ic = drawKey(c, rMenu, A_MENU, false);
        float bw = rMenu.width() * 0.4f;
        float bh = Math.max(3f, rMenu.height() * 0.08f);
        float gap = rMenu.height() * 0.2f;
        fill.setColor(ic);
        for (int i = -1; i <= 1; i++) {
            float cy = rMenu.centerY() + i * gap;
            c.drawRoundRect(rMenu.centerX() - bw / 2f, cy - bh / 2f, rMenu.centerX() + bw / 2f, cy + bh / 2f,
                    bh / 2f, bh / 2f, fill);
        }

        // Sag: pil yuzdesi, GPS, hava sicakligi, gerekirse pil sicakligi uyarisi.
        float x = rStatus.right - pad * 0.2f;
        float small = rStatus.height() * 0.54f;
        float sbase = baseline(rStatus.centerY(), small, true);
        if (battery >= 0) {
            String b = "%" + battery;
            text(c, b, x, sbase, small, charging ? accent : WHITE, Paint.Align.RIGHT, true);
            x -= tp.measureText(b) + pad;
        }
        text(c, "GPS", x, sbase, small, speed >= 0f ? WHITE : AMBER, Paint.Align.RIGHT, true);
        x -= tp.measureText("GPS") + pad;
        // Dar ekranda pil uyarisi cikinca hava sicakligina yer kalmaz; uyari onceliklidir.
        boolean warn = !Float.isNaN(battTemp) && battTemp >= 40f;
        boolean roomy = rStatus.width() > 1000f * u;
        if (!Float.isNaN(weatherTemp) && (!warn || roomy)) {
            String t = Math.round(weatherTemp) + "°C";
            text(c, t, x, sbase, small, weatherStale ? MUTED : WHITE, Paint.Align.RIGHT, true);
            x -= tp.measureText(t) + pad;
        }
        if (warn) {
            String t = "PİL " + Math.round(battTemp) + "°";
            text(c, t, x, sbase, small, AMBER, Paint.Align.RIGHT, true);
            x -= tp.measureText(t) + pad;
        }

        // Orta: saat. Sagdaki grup genisse sola kayar, menu tusuna binmez.
        float size = rStatus.height() * 0.86f;
        float base = baseline(rStatus.centerY(), size, true);
        float cw = tp.measureText(clock);
        float cx = rStatus.centerX();
        if (cx + cw / 2f > x) cx = x - cw / 2f;
        float minCx = rMenu.right + pad + cw / 2f;
        if (cx < minCx) cx = minCx;
        text(c, clock, cx, base, size, accent, Paint.Align.CENTER, true);

        // Seridin altindaki ince cizgi.
        float ly = rStatus.bottom + pad * 0.6f;
        stroke.setColor(accentDim);
        stroke.setStrokeWidth(2f * u);
        c.drawLine(rStatus.left, ly, rStatus.right, ly, stroke);
    }

    // ------------------------------------------------------------------
    // Hiz kadrani
    // ------------------------------------------------------------------

    private static float speedAngle(float v) {
        return 150f + 240f * Math.max(0f, Math.min(v, MAX_SPEED)) / MAX_SPEED;
    }

    private void drawSpeed(Canvas c, float intro) {
        RectF r = rSpeed;
        drawPanel(c, r);
        float rad = Math.min(r.width() * 0.42f, r.height() / 2.12f);
        float cx = r.centerX();
        float cy = r.centerY() + rad * 0.2f;

        // Centikler ve rakamlar.
        stroke.setStrokeCap(Paint.Cap.BUTT);
        for (int v = 0; v <= 160; v += 10) {
            boolean major = v % 20 == 0;
            double a = Math.toRadians(speedAngle(v));
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            float r0 = rad * (major ? 0.8f : 0.86f);
            float r1 = rad * 0.93f;
            stroke.setColor(major ? 0xFFBEBEBE : 0xFF5A5A5A);
            stroke.setStrokeWidth((major ? 3f : 2f) * u);
            c.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1, stroke);
            if (v % 40 == 0) {
                float ts = rad * 0.13f;
                float rt = rad * 0.66f;
                text(c, String.valueOf(v), cx + cos * rt, baseline(cy + sin * rt, ts, true), ts, MUTED,
                        Paint.Align.CENTER, true);
            }
        }

        // Dis halka: zemin ve dolan kisim.
        float sw = rad * 0.065f;
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        stroke.setStrokeWidth(sw);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(TRACK);
        c.drawArc(tmp, 150f, 240f, false, stroke);

        // Acilista ibre bir tur atip doner.
        float pointer = shownSpeed;
        if (intro < 1f) pointer = Math.max(pointer, MAX_SPEED * (float) Math.sin(Math.PI * intro));
        float sweep = speedAngle(pointer) - 150f;
        if (sweep > 0.8f) {
            stroke.setColor(pointer > 120f && intro >= 1f ? AMBER : accent);
            stroke.setShadowLayer(10f * u, 0f, 0f, withAlpha(accent, 150));
            c.drawArc(tmp, 150f, sweep, false, stroke);
            stroke.clearShadowLayer();
        }

        // Ibre: halkanin icinde kisa, parlak bir cizgi; ortadaki rakami kesmez.
        double pa = Math.toRadians(speedAngle(pointer));
        float pc = (float) Math.cos(pa);
        float ps = (float) Math.sin(pa);
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(rad * 0.04f);
        stroke.setShadowLayer(8f * u, 0f, 0f, withAlpha(accent, 220));
        c.drawLine(cx + pc * rad * 0.72f, cy + ps * rad * 0.72f, cx + pc * rad * 1.05f, cy + ps * rad * 1.05f, stroke);
        stroke.clearShadowLayer();

        // Ortada buyuk rakam.
        String digits = speed >= 0f ? String.valueOf(Math.round(shownSpeed)) : "--";
        float ds = rad * 0.7f;
        text(c, digits, cx, baseline(cy - rad * 0.02f, ds, true), ds, WHITE, Paint.Align.CENTER, true);
        float us = rad * 0.17f;
        float uy = cy + rad * 0.5f;
        if (!locPermission) {
            text(c, "Konum izni yok, dokun", cx, uy, us, AMBER, Paint.Align.CENTER, true);
        } else if (speed < 0f) {
            text(c, "GPS aranıyor", cx, uy, us, AMBER, Paint.Align.CENTER, true);
        } else {
            text(c, "km/h", cx, uy, us, accent, Paint.Align.CENTER, true);
        }

        if (bikeName != null && !bikeName.isEmpty()) {
            float ls = Math.min(rad * 0.14f, r.height() * 0.075f);
            tp.setLetterSpacing(0.12f);
            text(c, bikeName, r.left + cut + pad * 0.4f, r.top + pad * 0.7f + ls, ls, accent, Paint.Align.LEFT, true);
            tp.setLetterSpacing(0f);
        }
    }

    // ------------------------------------------------------------------
    // Navigasyon
    // ------------------------------------------------------------------

    private void drawNotice(Canvas c, RectF p, String title, String detail) {
        tmp.set(p.left + pad, p.top + p.height() * 0.2f, p.right - pad, p.top + p.height() * 0.46f);
        textFit(c, title, tmp, Math.min(p.height() * 0.12f, p.width() * 0.09f), accent, Paint.Align.CENTER, true);
        tmp.set(p.left + pad * 2f, p.top + p.height() * 0.48f, p.right - pad * 2f, p.bottom - pad);
        multiline(c, detail, tmp, Math.min(p.height() * 0.07f, p.width() * 0.05f), MUTED, 4, true, false);
    }

    private void drawNav(Canvas c) {
        RectF p = rNav;
        drawPanel(c, p);
        if (!listenerEnabled) {
            drawNotice(c, p, "Bildirim erişimi gerekli",
                    "Navigasyon ve müzik bilgisi için buraya dokunup Artçı'ya izin ver.");
            return;
        }
        if (!NavState.active) {
            if (NavState.appPresent) {
                drawNotice(c, p, "Yönlendirme okunamadı",
                        "Navigasyon açık ama bildiriminden mesafe ya da yön çıkmadı. Menü > Ayarlar > Ham navigasyon verisi ekranına bak.");
            } else {
                drawNotice(c, p, "Rota yok",
                        "Yandex Navigasyon'da rota başlatınca yol burada görünür. Açmak için dokun.");
            }
            return;
        }

        float in = 4f * u;
        float bottomH = Math.min(p.height() * 0.24f, 150f * u);
        RectF view = tmp2;
        view.set(p.left + in, p.top + in, p.right - in, p.bottom - bottomH);

        int dir = NavState.direction;
        Bitmap icon = NavState.icon;
        boolean hasIcon = icon != null && !icon.isRecycled();

        // Yol gorunumu cercevenin kesik koselerine tasmasin.
        c.save();
        tmp.set(p.left + in, p.top + in, p.right - in, p.bottom - in);
        buildFrame(framePath, tmp, cut);
        c.clipPath(framePath);
        c.clipRect(view);
        drawRoadView(c, view, dir, liveMeters());
        c.restore();
        if (pressed == A_NAV_BOX) {
            buildFrame(framePath, p, cut);
            fill.setColor(withAlpha(accent, 40));
            c.drawPath(framePath, fill);
        }

        // Sol ustte bilgi kutusu: manevra oku ve mesafe. Sag ustte navigasyonun kendi simgesi.
        boolean known = dir != NavState.DIR_NONE;
        String dist = NavState.distance;
        float boxH = Math.min(view.height() * 0.2f, 120f * u);
        float bl = view.left + pad * 0.7f;
        float bt = view.top + pad * 0.7f;
        float gs = boxH * 0.76f;
        float ts = boxH * 0.62f;
        tp.setTypeface(bold);
        tp.setTextSize(ts);
        float tw = dist.isEmpty() ? 0f : tp.measureText(dist);
        boolean glyph = known || hasIcon;
        float boxW = pad * 0.5f + (glyph ? gs : 0f) + (tw > 0f ? pad * 0.5f + tw : 0f) + pad * 0.6f;
        boxW = Math.min(boxW, view.width() * 0.72f);
        if (glyph || tw > 0f) {
            tmp.set(bl, bt, bl + boxW, bt + boxH);
            drawChip(c, tmp);
            float gx = bl + pad * 0.5f;
            float gy = bt + (boxH - gs) / 2f;
            if (known) {
                tmp.set(gx, gy, gx + gs, gy + gs);
                drawGlyph(c, tmp, dir, WHITE);
            } else if (hasIcon) {
                tmp.set(gx, gy, gx + gs, gy + gs);
                drawBitmapFit(c, icon, tmp, 0f, NavState.iconGlyph ? whiteTint : null, 255);
            }
            if (tw > 0f) {
                float tx0 = glyph ? gx + gs + pad * 0.5f : gx;
                tmp.set(tx0, bt, bl + boxW - pad * 0.5f, bt + boxH);
                textFit(c, dist, tmp, ts, WHITE, Paint.Align.LEFT, true);
            }
        }
        if (known && hasIcon) {
            float is = boxH * 0.72f;
            float ir = view.right - pad * 0.7f;
            tmp.set(ir - is, bt, ir, bt + is);
            drawChip(c, tmp);
            tmp.set(ir - is * 0.82f, bt + is * 0.18f, ir - is * 0.18f, bt + is * 0.82f);
            drawBitmapFit(c, icon, tmp, 0f, NavState.iconGlyph ? mutedTint : null, 255);
        }

        // Alt blok: sokak adi, ince cizgi, kalan yol ve varis saati.
        float bx0 = p.left + pad;
        float bx1 = p.right - pad;
        float streetH = bottomH * 0.5f;
        String ins = NavState.instruction;
        if (!ins.isEmpty()) {
            tmp.set(bx0, view.bottom, bx1, view.bottom + streetH);
            textFit(c, ins, tmp, streetH * 0.52f, WHITE, Paint.Align.CENTER, true);
        }
        float ly = view.bottom + streetH;
        stroke.setColor(accentDim);
        stroke.setStrokeWidth(2f * u);
        c.drawLine(bx0, ly, bx1, ly, stroke);
        float statH = p.bottom - in - ly;
        float half = (bx1 - bx0) / 2f;
        String remain = NavState.remain;
        String eta = NavState.eta;
        if (!remain.isEmpty()) {
            tmp.set(bx0 + pad * 0.3f, ly, bx0 + half * 1.25f, ly + statH);
            textFit(c, remain, tmp, statH * 0.46f, WHITE, Paint.Align.LEFT, true);
        }
        if (!eta.isEmpty()) {
            tmp.set(bx0 + half * 1.3f, ly, bx1 - pad * 0.3f, ly + statH);
            textFit(c, "Varış " + eta, tmp, statH * 0.46f, WHITE, Paint.Align.RIGHT, true);
        }
    }

    /**
     * Yandex mesafeyi aralikli gunceller. Aradaki sureyi kendi GPS hizimizla
     * doldurup donusun akici yaklasmasini saglar; ekrandaki sayi degismez.
     */
    private float liveMeters() {
        float m = NavState.distanceMeters;
        if (m < 0f) return -1f;
        if (speed > 1f) {
            float dt = (SystemClock.elapsedRealtime() - NavState.distanceAt) / 1000f;
            if (dt > 0f) {
                float travelled = speed / 3.6f * Math.min(dt, 10f);
                m -= Math.min(travelled, Math.max(8f, m * 0.2f));
            }
        }
        return Math.max(0f, m);
    }

    /** d metre ilerideki noktanin ekrandaki orani: 1 = en alt, 0 = ufuk. */
    private static float persp(float d) {
        return PERSP_K / (PERSP_K + Math.max(0f, d));
    }

    /**
     * Perspektifli sematik yol. Altta sen, ufka dogru daralan yol, mesafeye
     * gore yerlesen donus. Gercek yolun sekli degildir; yalnizca "ne kadar
     * sonra, hangi yone" bilgisini cizer. Cevredeki baska sokaklar gosterilmez.
     */
    private void drawRoadView(Canvas c, RectF r, int dir, float meters) {
        // Ufuk gorunumun ustunde, ekran disinda: yol daralir ama bir noktada
        // bitmez. Gorunen menzil yaklasik 170 metredir; daha uzaktaki donus
        // ustten kayarak girer.
        float hz = r.top - r.height() * 0.35f;
        float by = r.bottom;
        float span = by - hz;
        float cx = r.centerX();
        float w0 = r.width() * 0.24f;
        float rf = 0.4f;
        float fTop = (r.top - hz) / span;

        boolean left = dir == NavState.DIR_LEFT || dir == NavState.DIR_SLIGHT_LEFT;
        float sign = left ? -1f : 1f;
        boolean side = dir == NavState.DIR_LEFT || dir == NavState.DIR_RIGHT;
        boolean slight = dir == NavState.DIR_SLIGHT_LEFT || dir == NavState.DIR_SLIGHT_RIGHT;
        boolean uturn = dir == NavState.DIR_UTURN;
        boolean round = dir == NavState.DIR_ROUNDABOUT;

        float d = (meters < 0f ? 120f : meters) + BIKE_AHEAD;
        float fT = persp(d);
        float yT = hz + span * fT;
        float hwT = w0 * fT;
        float yF = hz + span * persp(d + 10f);
        float minBand = 5f * u;
        if (yT - yF < minBand) yF = yT - minBand;

        stroke.setStrokeCap(Paint.Cap.BUTT);

        // --- Gri zemin: uzerinde oldugun yol ve donecegin yol ---
        fill.setColor(ROAD);
        path.reset();
        path.moveTo(cx - w0, by);
        path.lineTo(cx + w0, by);
        path.lineTo(cx + w0 * fTop, r.top);
        path.lineTo(cx - w0 * fTop, r.top);
        path.close();
        c.drawPath(path, fill);

        float yC = hz + span * persp(d + 12f);
        float rx = Math.min(Math.max(hwT * 2.4f, 16f * u), r.width() * 0.34f);
        float ry = rx * 0.42f;
        if (side) {
            c.drawRect(r.left, yF, r.right, yT, fill);
        } else if (round) {
            c.drawRect(r.left, yC - ry * 0.3f, r.right, yC + ry * 0.3f, fill);
            c.drawOval(cx - rx, yC - ry, cx + rx, yC + ry, fill);
            fill.setColor(PANEL);
            c.drawOval(cx - rx * 0.45f, yC - ry * 0.45f, cx + rx * 0.45f, yC + ry * 0.45f, fill);
            fill.setColor(ROAD);
        }
        float fB = persp(d + 60f);
        float yB = hz + span * fB;
        float xB = cx + sign * r.width() * 0.3f;
        if (slight) {
            float wB = Math.max(w0 * fB, 4f * u);
            path.reset();
            path.moveTo(cx - hwT, yT);
            path.lineTo(cx + hwT, yT);
            path.lineTo(xB + wB, yB);
            path.lineTo(xB - wB, yB);
            path.close();
            c.drawPath(path, fill);
        }
        stroke.setColor(ROAD_EDGE);
        stroke.setStrokeWidth(2f * u);
        c.drawLine(cx - w0, by, cx - w0 * fTop, r.top, stroke);
        c.drawLine(cx + w0, by, cx + w0 * fTop, r.top, stroke);

        // --- Rota (vurgu rengi) ---
        fill.setColor(accent);
        stroke.setColor(accent);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float flowEnd;
        if (side) {
            float yM = (yT + yF) / 2f;
            float t = Math.max((yT - yF) * 0.55f, 4f * u);
            float aT = Math.max(rf * w0 * (yM - hz) / span, 2f * u);
            path.reset();
            path.moveTo(cx - rf * w0, by);
            path.lineTo(cx + rf * w0, by);
            path.lineTo(cx + aT, yM - t / 2f);
            path.lineTo(cx - aT, yM - t / 2f);
            path.close();
            c.drawPath(path, fill);
            float headH = Math.max(t * 1.3f, 9f * u);
            float headL = headH * 1.2f;
            float xe = left ? r.left + headL + 8f * u : r.right - headL - 8f * u;
            float xs = cx - sign * aT;
            c.drawRect(Math.min(xs, xe), yM - t / 2f, Math.max(xs, xe), yM + t / 2f, fill);
            triangle(c, xe + sign * headL, yM, xe, yM - headH, xe, yM + headH);
            flowEnd = d;
        } else if (slight) {
            float aT = Math.max(rf * hwT, 2f * u);
            float aB = Math.max(rf * w0 * fB, 3f * u);
            path.reset();
            path.moveTo(cx - rf * w0, by);
            path.lineTo(cx + rf * w0, by);
            path.lineTo(cx + aT, yT);
            path.lineTo(xB + aB, yB);
            path.lineTo(xB - aB, yB);
            path.lineTo(cx - aT, yT);
            path.close();
            c.drawPath(path, fill);
            float ang = (float) Math.toDegrees(Math.atan2(xB - cx, yT - yB));
            drawHead(c, xB, yB, ang, Math.max(aB * 2.2f, 10f * u));
            flowEnd = d;
        } else if (uturn) {
            float lane = 0.5f;
            float bandHalf = 0.28f;
            path.reset();
            path.moveTo(cx + (lane - bandHalf) * w0, by);
            path.lineTo(cx + (lane + bandHalf) * w0, by);
            path.lineTo(cx + (lane + bandHalf) * hwT, yT);
            path.lineTo(cx + (lane - bandHalf) * hwT, yT);
            path.close();
            c.drawPath(path, fill);
            float fE = persp(Math.max(BIKE_AHEAD + 4f, d * 0.45f));
            float yE = hz + span * fE;
            float hwE = w0 * fE;
            path.reset();
            path.moveTo(cx - (lane + bandHalf) * hwT, yT);
            path.lineTo(cx - (lane - bandHalf) * hwT, yT);
            path.lineTo(cx - (lane - bandHalf) * hwE, yE);
            path.lineTo(cx - (lane + bandHalf) * hwE, yE);
            path.close();
            c.drawPath(path, fill);
            stroke.setStrokeWidth(Math.max(2f * bandHalf * hwT, 3f * u));
            stroke.setStrokeCap(Paint.Cap.BUTT);
            tmp.set(cx - lane * hwT, yT - lane * hwT * 0.9f, cx + lane * hwT, yT + lane * hwT * 0.9f);
            c.drawArc(tmp, 0f, -180f, false, stroke);
            float hh = Math.max(bandHalf * hwE * 2.2f, 10f * u);
            triangle(c, cx - lane * hwE, yE + hh * 1.2f, cx - lane * hwE - hh, yE, cx - lane * hwE + hh, yE);
            flowEnd = d;
        } else if (round) {
            float yEntry = yC + ry * 0.8f;
            float aE = Math.max(rf * w0 * (yEntry - hz) / span, 2f * u);
            path.reset();
            path.moveTo(cx - rf * w0, by);
            path.lineTo(cx + rf * w0, by);
            path.lineTo(cx + aE, yEntry);
            path.lineTo(cx - aE, yEntry);
            path.close();
            c.drawPath(path, fill);
            // Kacinci cikis oldugu bilinmedigi icin yalnizca donus yonu gosterilir.
            float k = 0.72f;
            stroke.setStrokeWidth(Math.max(rx * 0.2f, 4f * u));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            tmp.set(cx - rx * k, yC - ry * k, cx + rx * k, yC + ry * k);
            c.drawArc(tmp, 90f, -150f, false, stroke);
            double end = Math.toRadians(-60.0);
            float ex = cx + rx * k * (float) Math.cos(end);
            float ey = yC + ry * k * (float) Math.sin(end);
            float tdx = rx * (float) Math.sin(end);
            float tdy = -ry * (float) Math.cos(end);
            float ang = (float) Math.toDegrees(Math.atan2(tdx, -tdy));
            drawHead(c, ex, ey, ang, Math.max(rx * 0.3f, 9f * u));
            flowEnd = d;
        } else if (dir == NavState.DIR_ARRIVE) {
            // Varis: rota hedefe kadar gelir, hedefte isaret durur.
            float aT = Math.max(rf * hwT, 2f * u);
            path.reset();
            path.moveTo(cx - rf * w0, by);
            path.lineTo(cx + rf * w0, by);
            path.lineTo(cx + aT, yT);
            path.lineTo(cx - aT, yT);
            path.close();
            c.drawPath(path, fill);
            float ps = Math.max(hwT * 1.6f, 26f * u);
            tmp.set(cx - ps / 2f, yT - ps, cx + ps / 2f, yT);
            drawGlyph(c, tmp, NavState.DIR_ARRIVE, WHITE);
            flowEnd = d;
        } else {
            // Duz devam. Yon taninmadiysa ok ucu cizilmez ve rota soluk kalir;
            // o durumda ustteki navigasyon simgesi esas alinir.
            boolean unknown = dir != NavState.DIR_STRAIGHT;
            float dS = 130f;
            float fS = persp(dS);
            float yS = hz + span * fS;
            float aS = Math.max(rf * w0 * fS, 3f * u);
            if (unknown) fill.setColor(accentDim);
            path.reset();
            path.moveTo(cx - rf * w0, by);
            path.lineTo(cx + rf * w0, by);
            path.lineTo(cx + aS, yS);
            path.lineTo(cx - aS, yS);
            path.close();
            c.drawPath(path, fill);
            if (!unknown) {
                float hh = Math.max(aS * 2.4f, 12f * u);
                triangle(c, cx, yS - hh * 1.3f, cx - hh, yS, cx + hh, yS);
            }
            flowEnd = dS;
        }

        // --- Akis isaretleri: rota uzerinde ileri kayan koyu V'ler ---
        float laneX = uturn ? 0.5f : 0f;
        float laneHalf = uturn ? 0.28f : rf;
        stroke.setColor(0x66000000);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeJoin(Paint.Join.MITER);
        for (float dd = BIKE_AHEAD + 9f - flow; dd < flowEnd - 4f; dd += FLOW_STEP) {
            if (dd < BIKE_AHEAD + 5f) continue;
            float f = persp(dd);
            float hw = laneHalf * w0 * f * 0.7f;
            if (hw < 5f * u) break;
            float y = hz + span * f;
            float x = cx + laneX * w0 * f;
            stroke.setStrokeWidth(Math.max(hw * 0.28f, 2f * u));
            path.reset();
            path.moveTo(x - hw, y + hw * 0.55f);
            path.lineTo(x, y - hw * 0.35f);
            path.lineTo(x + hw, y + hw * 0.55f);
            c.drawPath(path, stroke);
        }

        // --- Motorun yeri ---
        float fBike = persp(BIKE_AHEAD);
        float yBike = hz + span * fBike;
        float bikeX = cx + laneX * w0 * fBike;
        float s = w0 * fBike * 0.3f;
        path.reset();
        path.moveTo(bikeX, yBike - s * 1.2f);
        path.lineTo(bikeX - s, yBike + s * 0.8f);
        path.lineTo(bikeX, yBike + s * 0.3f);
        path.lineTo(bikeX + s, yBike + s * 0.8f);
        path.close();
        fill.setColor(WHITE);
        c.drawPath(path, fill);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(BG);
        stroke.setStrokeWidth(s * 0.16f);
        c.drawPath(path, stroke);
    }

    /** Ok ucu. angle: 0 yukari, 90 saga, -90 sola, 180 asagi. Ucu merkezden size kadar ileridedir. */
    private void drawHead(Canvas c, float x, float y, float angle, float size) {
        c.save();
        c.translate(x, y);
        c.rotate(angle);
        path.reset();
        path.moveTo(0f, -size);
        path.lineTo(-size, size * 0.55f);
        path.lineTo(size, size * 0.55f);
        path.close();
        c.drawPath(path, fill);
        c.restore();
    }

    /** Manevra oku (gokyuzu seridindeki buyuk beyaz simge). 100x100 birimlik karede tanimli. */
    private void drawGlyph(Canvas c, RectF box, int dir, int color) {
        float s = Math.min(box.width(), box.height());
        c.save();
        c.translate(box.centerX() - s / 2f, box.centerY() - s / 2f);
        c.scale(s / 100f, s / 100f);
        if (dir == NavState.DIR_LEFT || dir == NavState.DIR_SLIGHT_LEFT) {
            c.scale(-1f, 1f, 50f, 50f);
        }
        fill.setColor(color);
        stroke.setColor(color);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        path.reset();

        switch (dir) {
            case NavState.DIR_LEFT:
            case NavState.DIR_RIGHT:
                stroke.setStrokeWidth(17f);
                path.moveTo(32f, 88f);
                path.lineTo(32f, 58f);
                path.quadTo(32f, 44f, 46f, 44f);
                path.lineTo(66f, 44f);
                c.drawPath(path, stroke);
                triangle(c, 64f, 18f, 96f, 44f, 64f, 70f);
                break;
            case NavState.DIR_SLIGHT_LEFT:
            case NavState.DIR_SLIGHT_RIGHT:
                stroke.setStrokeWidth(17f);
                path.moveTo(38f, 88f);
                path.lineTo(38f, 60f);
                path.lineTo(58f, 38f);
                c.drawPath(path, stroke);
                triangle(c, 82f, 12f, 76f, 54f, 42f, 20f);
                break;
            case NavState.DIR_UTURN:
                stroke.setStrokeWidth(15f);
                path.moveTo(68f, 88f);
                path.lineTo(68f, 42f);
                tmp.set(30f, 23f, 68f, 61f);
                path.arcTo(tmp, 0f, -180f, false);
                path.lineTo(30f, 56f);
                c.drawPath(path, stroke);
                triangle(c, 8f, 54f, 52f, 54f, 30f, 84f);
                break;
            case NavState.DIR_ROUNDABOUT:
                stroke.setStrokeWidth(13f);
                tmp.set(22f, 30f, 78f, 86f);
                c.drawArc(tmp, 120f, -290f, false, stroke);
                triangle(c, 20f, 44f, 52f, 40f, 30f, 72f);
                break;
            case NavState.DIR_ARRIVE:
                c.drawCircle(50f, 38f, 26f, fill);
                triangle(c, 28f, 52f, 72f, 52f, 50f, 94f);
                fill.setColor(PANEL);
                c.drawCircle(50f, 38f, 10f, fill);
                break;
            case NavState.DIR_STRAIGHT:
            default:
                path.moveTo(50f, 6f);
                path.lineTo(20f, 46f);
                path.lineTo(40f, 46f);
                path.lineTo(40f, 94f);
                path.lineTo(60f, 94f);
                path.lineTo(60f, 46f);
                path.lineTo(80f, 46f);
                path.close();
                c.drawPath(path, fill);
                break;
        }
        c.restore();
    }

    private void triangle(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        c.drawPath(path, fill);
    }

    // ------------------------------------------------------------------
    // Muzik
    // ------------------------------------------------------------------

    private void drawMusic(Canvas c, long now) {
        drawPanel(c, rMusic);
        RectF info = rMusicInfo;
        if (pressed == A_OPEN_MUSIC) {
            fill.setColor(withAlpha(accent, 40));
            c.drawRect(info, fill);
        }
        float side = Math.min(info.height(), info.width() * 0.5f);
        RectF cover = tmp2;
        cover.set(info.left, info.centerY() - side / 2f, info.left + side, info.centerY() + side / 2f);
        float rr = 8f * u;

        boolean drew = false;
        float fade = prevArt == null ? 1f : Math.min(1f, (now - artChangedAt) / (float) COVER_FADE_MS);
        if (prevArt != null && !prevArt.isRecycled() && fade < 1f) {
            drawBitmapFit(c, prevArt, cover, rr, null, Math.round(255f * (1f - fade)));
            drew = true;
        }
        if (curArt != null && !curArt.isRecycled()) {
            drawBitmapFit(c, curArt, cover, rr, null, Math.round(255f * fade));
            drew = true;
        }
        if (!drew) {
            fill.setColor(TRACK);
            c.drawRoundRect(cover, rr, rr, fill);
            text(c, "♪", cover.centerX(), baseline(cover.centerY(), side * 0.5f, true), side * 0.5f, MUTED,
                    Paint.Align.CENTER, true);
        }
        stroke.setColor(accent);
        stroke.setStrokeWidth(2.5f * u);
        c.drawRoundRect(cover, rr, rr, stroke);

        float tx0 = cover.right + pad;
        float tx1 = info.right;
        float tw = tx1 - tx0;
        String title = MediaState.title;
        String artist = MediaState.artist;
        if (!listenerEnabled) {
            title = "Bildirim erişimi yok";
            artist = "Menü > Ayarlar";
        } else if (!MediaState.hasSession || (title.isEmpty() && artist.isEmpty())) {
            title = "Müzik çalmıyor";
            artist = "Oynat tuşuna bas";
        }
        float tsize = Math.min(side * 0.18f, tw * 0.13f);
        float barH = Math.max(4f, side * 0.035f);
        tmp.set(tx0, cover.top, tx1, cover.top + side * 0.52f);
        multiline(c, title, tmp, tsize, WHITE, 2, false, true);
        tmp.set(tx0, cover.top + side * 0.54f, tx1, cover.top + side * 0.8f);
        textFit(c, artist, tmp, tsize * 0.72f, MUTED, Paint.Align.LEFT, false);

        // Ses duzeyi cubugu.
        if (volume >= 0f) {
            fill.setColor(TRACK);
            c.drawRoundRect(tx0, cover.bottom - barH, tx1, cover.bottom, barH / 2f, barH / 2f, fill);
            fill.setColor(accent);
            c.drawRoundRect(tx0, cover.bottom - barH, tx0 + tw * Math.min(1f, volume), cover.bottom,
                    barH / 2f, barH / 2f, fill);
        }

        drawMediaKey(c, rPrev, 0, A_PREV);
        drawMediaKey(c, rPlay, MediaState.playing ? 2 : 1, A_PLAY);
        drawMediaKey(c, rNext, 3, A_NEXT);
        drawVolumeKey(c, rVolDown, false, A_VOL_DOWN);
        drawVolumeKey(c, rVolUp, true, A_VOL_UP);
    }

    /** kind: 0 onceki, 1 oynat, 2 duraklat, 3 sonraki */
    private void drawMediaKey(Canvas c, RectF r, int kind, int id) {
        int col = drawKey(c, r, id, id == A_PLAY);
        float s = Math.min(r.width(), r.height()) * 0.42f;
        c.save();
        c.translate(r.centerX() - s / 2f, r.centerY() - s / 2f);
        c.scale(s / 100f, s / 100f);
        if (kind == 0) c.scale(-1f, 1f, 50f, 50f);
        fill.setColor(col);
        path.reset();
        if (kind == 1) {
            path.moveTo(24f, 10f);
            path.lineTo(24f, 90f);
            path.lineTo(90f, 50f);
            path.close();
            c.drawPath(path, fill);
        } else if (kind == 2) {
            c.drawRect(20f, 12f, 42f, 88f, fill);
            c.drawRect(58f, 12f, 80f, 88f, fill);
        } else {
            path.moveTo(12f, 14f);
            path.lineTo(12f, 86f);
            path.lineTo(68f, 50f);
            path.close();
            c.drawPath(path, fill);
            c.drawRect(72f, 14f, 88f, 86f, fill);
        }
        c.restore();
    }

    private void drawVolumeKey(Canvas c, RectF r, boolean up, int id) {
        int col = drawKey(c, r, id, false);
        float s = Math.min(r.width(), r.height()) * 0.36f;
        float t = s * 0.2f;
        fill.setColor(col);
        c.drawRect(r.centerX() - s / 2f, r.centerY() - t / 2f, r.centerX() + s / 2f, r.centerY() + t / 2f, fill);
        if (up) {
            c.drawRect(r.centerX() - t / 2f, r.centerY() - s / 2f, r.centerX() + t / 2f, r.centerY() + s / 2f, fill);
        }
    }

    // ------------------------------------------------------------------
    // Cizim yardimcilari
    // ------------------------------------------------------------------

    /** Verilen dikey merkeze oturacak yazi taban cizgisini hesaplar. */
    private float baseline(float centerY, float size, boolean isBold) {
        tp.setTypeface(isBold ? bold : regular);
        tp.setTextSize(size);
        return centerY - (tp.ascent() + tp.descent()) / 2f;
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, boolean isBold) {
        if (s == null || s.isEmpty()) return;
        tp.setTypeface(isBold ? bold : regular);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(align);
        c.drawText(s, x, y, tp);
    }

    /** Tek satir: sigmazsa once kucultur, yine sigmazsa sonunu keser. */
    private void textFit(Canvas c, String s, RectF r, float maxSize, int color, Paint.Align align, boolean isBold) {
        if (s == null || s.isEmpty() || r.width() <= 1f) return;
        tp.setTypeface(isBold ? bold : regular);
        float size = maxSize;
        tp.setTextSize(size);
        float w = tp.measureText(s);
        if (w > r.width()) {
            size = Math.max(maxSize * 0.6f, size * r.width() / w);
            tp.setTextSize(size);
            s = TextUtils.ellipsize(s, tp, r.width(), TextUtils.TruncateAt.END).toString();
        }
        tp.setColor(color);
        tp.setTextAlign(align);
        float x = align == Paint.Align.LEFT ? r.left : (align == Paint.Align.RIGHT ? r.right : r.centerX());
        float y = r.centerY() - (tp.ascent() + tp.descent()) / 2f;
        c.drawText(s, x, y, tp);
    }

    private void multiline(Canvas c, String s, RectF r, float size, int color, int maxLines, boolean center,
            boolean isBold) {
        if (s == null || s.isEmpty() || r.width() <= 1f) return;
        tp.setTypeface(isBold ? bold : regular);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.LEFT);
        StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.max(1, (int) r.width()))
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build();
        c.save();
        c.translate(r.left, center ? r.top + Math.max(0f, (r.height() - layout.getHeight()) / 2f) : r.top);
        layout.draw(c);
        c.restore();
    }

    /** Resmi oranini bozmadan kutuya sigdirir; radius > 0 ise koseleri yuvarlar. */
    private void drawBitmapFit(Canvas c, Bitmap b, RectF box, float radius, ColorFilter tint, int alpha) {
        float bw = b.getWidth();
        float bh = b.getHeight();
        if (bw <= 0f || bh <= 0f || alpha <= 0) return;
        float scale = Math.min(box.width() / bw, box.height() / bh);
        float dw = bw * scale;
        float dh = bh * scale;
        dst.set(box.centerX() - dw / 2f, box.centerY() - dh / 2f, box.centerX() + dw / 2f,
                box.centerY() + dh / 2f);
        c.save();
        if (radius > 0f) {
            path.reset();
            path.addRoundRect(dst, radius, radius, Path.Direction.CW);
            c.clipPath(path);
        }
        bmpPaint.setColorFilter(tint);
        bmpPaint.setAlpha(Math.min(255, alpha));
        c.drawBitmap(b, null, dst, bmpPaint);
        bmpPaint.setColorFilter(null);
        bmpPaint.setAlpha(255);
        c.restore();
    }

    // ------------------------------------------------------------------
    // Dokunma
    // ------------------------------------------------------------------

    private int hit(float x, float y) {
        // Menu tusunun dokunma alani ciziminden biraz genis tutulur.
        if (x <= rMenu.right + pad && y <= rMenu.bottom + pad) return A_MENU;
        if (rPrev.contains(x, y)) return A_PREV;
        if (rPlay.contains(x, y)) return A_PLAY;
        if (rNext.contains(x, y)) return A_NEXT;
        if (rVolDown.contains(x, y)) return A_VOL_DOWN;
        if (rVolUp.contains(x, y)) return A_VOL_UP;
        if (rSpeed.contains(x, y)) return locPermission ? A_NONE : A_GRANT_LOC;
        if (rNav.contains(x, y)) return listenerEnabled ? A_NAV_BOX : A_GRANT_NOTIF;
        if (rMusicInfo.contains(x, y)) return listenerEnabled ? A_OPEN_MUSIC : A_GRANT_NOTIF;
        return A_NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int h = hit(e.getX(), e.getY());
                int was = pressed;
                pressed = A_NONE;
                if (was != A_NONE) {
                    flashId = was;
                    flashStart = SystemClock.uptimeMillis();
                }
                invalidate();
                if (h != A_NONE && h == was) {
                    actions.onAction(h == A_NAV_BOX ? A_OPEN_NAV : h);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = A_NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
