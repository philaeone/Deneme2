package io.github.philaeone.dominarpanel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 * Panelin tamami tek bir cizim yuzeyidir: hiz, navigasyon ve muzik ayni anda
 * gorunur. Dikey ve yatay icin bolgeler ayri hesaplanir, icerik ayni cizim
 * kodunu kullanir.
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

    private static final int BG = 0xFF0B0D0C;
    private static final int PANEL = 0xFF161A18;
    private static final int PANEL_HI = 0xFF26302B;
    private static final int ACCENT = 0xFF39D353;
    private static final int ACCENT_DIM = 0xFF1C4A27;
    private static final int WHITE = 0xFFF2F5F3;
    private static final int GREY = 0xFF98A39D;
    private static final int AMBER = 0xFFFFB300;
    private static final int RED = 0xFFFF5252;
    private static final int ROAD = 0xFF2B3430;
    private static final int ROUTE = 0xFF2F8BFF;

    private static final float MAX_SPEED = 160f;

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
    String version = "";

    private final Actions actions;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final RectF tmp2 = new RectF();
    private final RectF dst = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final ColorFilter accentTint = new PorterDuffColorFilter(ACCENT, PorterDuff.Mode.SRC_IN);
    private final ColorFilter greyTint = new PorterDuffColorFilter(GREY, PorterDuff.Mode.SRC_IN);

    private final RectF rStatus = new RectF();
    private final RectF rSpeed = new RectF();
    private final RectF rNav = new RectF();
    private final RectF rMusic = new RectF();
    private final RectF rMusicInfo = new RectF();
    private final RectF rPrev = new RectF();
    private final RectF rPlay = new RectF();
    private final RectF rNext = new RectF();
    private final RectF rVolDown = new RectF();
    private final RectF rVolUp = new RectF();
    private final RectF rBtnNav = new RectF();
    private final RectF rBtnMusic = new RectF();
    private final RectF rBtnMenu = new RectF();

    private float pad = 16f;
    private float corner = 20f;
    private int pressed = A_NONE;

    PanelView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
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
        pad = Math.min(w, h) * 0.025f;
        corner = Math.min(w, h) * 0.03f;
        float ip = pad * 0.8f;

        if (h >= w) {
            // Dikey: durum, hiz, navigasyon, muzik, uygulama tuslari.
            float y = pad;
            float statusH = h * 0.05f;
            rStatus.set(pad, y, w - pad, y + statusH);
            y += statusH + pad;

            float speedH = h * 0.19f;
            rSpeed.set(pad, y, w - pad, y + speedH);
            y += speedH + pad;

            float bottom = h - pad;
            float btnH = h * 0.075f;
            float bw = (w - 4f * pad) / 3f;
            rBtnNav.set(pad, bottom - btnH, pad + bw, bottom);
            rBtnMusic.set(2f * pad + bw, bottom - btnH, 2f * pad + 2f * bw, bottom);
            rBtnMenu.set(3f * pad + 2f * bw, bottom - btnH, w - pad, bottom);

            float musicH = h * 0.185f;
            float musicBottom = bottom - btnH - pad;
            rMusic.set(pad, musicBottom - musicH, w - pad, musicBottom);
            rNav.set(pad, y, w - pad, rMusic.top - pad);

            float x0 = rMusic.left + ip;
            float x1 = rMusic.right - ip;
            float infoH = (musicH - 3f * ip) * 0.38f;
            rMusicInfo.set(x0, rMusic.top + ip, x1, rMusic.top + ip + infoH);
            float by0 = rMusicInfo.bottom + ip;
            float by1 = rMusic.bottom - ip;
            float w5 = (x1 - x0 - 4f * ip) / 5f;
            rPrev.set(x0, by0, x0 + w5, by1);
            rPlay.set(x0 + (w5 + ip), by0, x0 + (w5 + ip) + w5, by1);
            rNext.set(x0 + 2f * (w5 + ip), by0, x0 + 2f * (w5 + ip) + w5, by1);
            rVolDown.set(x0 + 3f * (w5 + ip), by0, x0 + 3f * (w5 + ip) + w5, by1);
            rVolUp.set(x0 + 4f * (w5 + ip), by0, x1, by1);
        } else {
            // Yatay: solda hiz, ortada navigasyon, sagda muzik ve uygulama tuslari.
            float statusH = h * 0.11f;
            rStatus.set(pad, pad, w - pad, pad + statusH);
            float top = 2f * pad + statusH;
            float bottom = h - pad;

            float leftW = w * 0.25f;
            rSpeed.set(pad, top, pad + leftW, bottom);

            float rightW = w * 0.29f;
            float rx = w - pad - rightW;
            float appH = h * 0.14f;
            float bw = (rightW - 2f * pad) / 3f;
            rBtnNav.set(rx, bottom - appH, rx + bw, bottom);
            rBtnMusic.set(rx + bw + pad, bottom - appH, rx + 2f * bw + pad, bottom);
            rBtnMenu.set(rx + 2f * bw + 2f * pad, bottom - appH, w - pad, bottom);

            rMusic.set(rx, top, w - pad, bottom - appH - pad);
            rNav.set(2f * pad + leftW, top, rx - pad, bottom);

            float x0 = rMusic.left + ip;
            float x1 = rMusic.right - ip;
            float inner = rMusic.height() - 4f * ip;
            float infoH = inner * 0.30f;
            float transH = inner * 0.40f;
            rMusicInfo.set(x0, rMusic.top + ip, x1, rMusic.top + ip + infoH);
            float ty0 = rMusicInfo.bottom + ip;
            float w3 = (x1 - x0 - 2f * ip) / 3f;
            rPrev.set(x0, ty0, x0 + w3, ty0 + transH);
            rPlay.set(x0 + w3 + ip, ty0, x0 + 2f * w3 + ip, ty0 + transH);
            rNext.set(x0 + 2f * w3 + 2f * ip, ty0, x1, ty0 + transH);
            float vy0 = ty0 + transH + ip;
            float w2 = (x1 - x0 - ip) / 2f;
            rVolDown.set(x0, vy0, x0 + w2, rMusic.bottom - ip);
            rVolUp.set(x0 + w2 + ip, vy0, x1, rMusic.bottom - ip);
        }
    }

    // ------------------------------------------------------------------
    // Cizim
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);
        if (getWidth() == 0 || getHeight() == 0) return;
        drawStatus(c);
        drawSpeed(c);
        drawNav(c);
        drawMusic(c);
        drawButtons(c);
    }

    private void drawStatus(Canvas c) {
        float size = rStatus.height() * 0.8f;
        float base = baseline(rStatus.centerY(), size, true);
        text(c, clock, rStatus.left + pad * 0.3f, base, size, WHITE, Paint.Align.LEFT, true);

        float x = rStatus.right - pad * 0.3f;
        float small = size * 0.72f;
        float sbase = baseline(rStatus.centerY(), small, true);
        if (battery >= 0) {
            String b = battery + "%";
            text(c, b, x, sbase, small, charging ? ACCENT : WHITE, Paint.Align.RIGHT, true);
            x -= tp.measureText(b) + pad;
        }
        if (!Float.isNaN(battTemp)) {
            String t = Math.round(battTemp) + "°C";
            int col = battTemp >= 45f ? RED : (battTemp >= 40f ? AMBER : GREY);
            text(c, t, x, sbase, small, col, Paint.Align.RIGHT, true);
            x -= tp.measureText(t) + pad;
        }
        text(c, "GPS", x, sbase, small, speed >= 0f ? ACCENT : RED, Paint.Align.RIGHT, true);
    }

    private void drawSpeed(Canvas c) {
        box(c, rSpeed, PANEL);
        RectF r = rSpeed;
        float rad = Math.min(r.width() * 0.44f, r.height() / 2.05f);
        float cx = r.centerX();
        float cy = r.centerY() + rad * 0.09f;
        float sw = rad * 0.11f;

        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        stroke.setStrokeWidth(sw);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(PANEL_HI);
        c.drawArc(tmp, 150f, 240f, false, stroke);
        if (speed >= 0f) {
            float frac = Math.min(speed, MAX_SPEED) / MAX_SPEED;
            if (frac > 0.005f) {
                stroke.setColor(speed > 120f ? AMBER : ACCENT);
                c.drawArc(tmp, 150f, 240f * frac, false, stroke);
            }
        }

        String digits = speed >= 0f ? String.valueOf(Math.round(speed)) : "--";
        float ds = rad * 0.92f;
        text(c, digits, cx, baseline(cy - rad * 0.05f, ds, true), ds, WHITE, Paint.Align.CENTER, true);
        float us = rad * 0.2f;
        if (!locPermission) {
            text(c, "Konum izni yok, dokun", cx, cy + rad * 0.62f, us, AMBER, Paint.Align.CENTER, false);
        } else if (speed < 0f) {
            text(c, "GPS aranıyor", cx, cy + rad * 0.62f, us, AMBER, Paint.Align.CENTER, false);
        } else {
            text(c, "km/h", cx, cy + rad * 0.62f, us, GREY, Paint.Align.CENTER, false);
        }

        float ls = Math.min(rad * 0.15f, r.height() * 0.08f);
        tp.setLetterSpacing(0.12f);
        text(c, "DOMINAR 400", r.left + pad, r.top + pad + ls, ls, ACCENT, Paint.Align.LEFT, true);
        tp.setLetterSpacing(0f);
    }

    private void drawNotice(Canvas c, RectF p, String title, String detail) {
        tmp.set(p.left + pad, p.top + p.height() * 0.2f, p.right - pad, p.top + p.height() * 0.46f);
        textFit(c, title, tmp, Math.min(p.height() * 0.12f, p.width() * 0.09f), AMBER, Paint.Align.CENTER, true);
        tmp.set(p.left + pad * 2f, p.top + p.height() * 0.48f, p.right - pad * 2f, p.bottom - pad);
        multiline(c, detail, tmp, Math.min(p.height() * 0.07f, p.width() * 0.05f), GREY, 4, true, false);
    }

    // ------------------------------------------------------------------
    // Navigasyon
    // ------------------------------------------------------------------

    private void drawNav(Canvas c) {
        RectF p = rNav;
        box(c, p, pressed == A_NAV_BOX ? PANEL_HI : PANEL);
        if (!listenerEnabled) {
            drawNotice(c, p, "Bildirim erişimi gerekli",
                    "Navigasyon ve müzik bilgisi için buraya dokunup Dominar Panel'e izin ver.");
            return;
        }
        if (!NavState.active) {
            if (NavState.appPresent) {
                drawNotice(c, p, "Yönlendirme okunamadı",
                        "Navigasyon açık ama bildiriminden mesafe ya da yön çıkmadı. Menü > Ham navigasyon verisi ekranına bak.");
            } else {
                drawNotice(c, p, "Rota yok",
                        "Yandex Navigasyon'da rota başlatınca yol burada görünür. Açmak için dokun.");
            }
            return;
        }

        float x0 = p.left + pad;
        float y0 = p.top + pad;
        float x1 = p.right - pad;
        float y1 = p.bottom - pad;
        float roadW = (x1 - x0) * 0.44f;
        RectF road = tmp2;
        road.set(x0, y0, x0 + roadW, y1);
        float tx0 = road.right + pad;
        float th = y1 - y0;

        int dir = NavState.direction;
        Bitmap icon = NavState.icon;
        boolean hasIcon = icon != null && !icon.isRecycled();
        boolean drawable = dir == NavState.DIR_LEFT || dir == NavState.DIR_RIGHT
                || dir == NavState.DIR_STRAIGHT || dir == NavState.DIR_UTURN
                || dir == NavState.DIR_SLIGHT_LEFT || dir == NavState.DIR_SLIGHT_RIGHT;

        boolean smallIcon = false;
        if (drawable) {
            drawRoad(c, road, dir, liveMeters());
            smallIcon = hasIcon;
        } else if (hasIcon) {
            // Yon taninamadi: navigasyonun kendi simgesi buyuk gosterilir.
            float s = Math.min(road.width(), road.height()) * 0.8f;
            tmp.set(road.centerX() - s / 2f, road.centerY() - s / 2f, road.centerX() + s / 2f,
                    road.centerY() + s / 2f);
            drawBitmapFit(c, icon, tmp, 0f, NavState.iconGlyph ? accentTint : null);
        } else {
            float s = Math.min(road.width(), road.height()) * 0.8f;
            tmp.set(road.centerX() - s / 2f, road.centerY() - s / 2f, road.centerX() + s / 2f,
                    road.centerY() + s / 2f);
            drawArrow(c, tmp, dir == NavState.DIR_NONE ? NavState.DIR_STRAIGHT : dir,
                    dir == NavState.DIR_NONE ? ACCENT_DIM : ACCENT);
        }

        // Sag sutun: mesafe, sokak, navigasyonun simgesi, ozet.
        String dist = NavState.distance;
        String ins = NavState.instruction;
        String extra = NavState.extra;
        float tw = x1 - tx0;

        if (!dist.isEmpty()) {
            tmp.set(tx0, y0, x1, y0 + th * 0.28f);
            textFit(c, dist, tmp, Math.min(th * 0.24f, tw * 0.34f), WHITE, Paint.Align.LEFT, true);
        }
        if (!ins.isEmpty()) {
            tmp.set(tx0, y0 + th * 0.29f, x1, y0 + th * 0.62f);
            multiline(c, ins, tmp, Math.min(th * 0.085f, tw * 0.13f), WHITE, 3, false, false);
        }
        if (smallIcon) {
            float s = Math.min(th * 0.16f, tw * 0.3f);
            tmp.set(tx0, y0 + th * 0.64f, tx0 + s, y0 + th * 0.64f + s);
            drawBitmapFit(c, icon, tmp, 0f, NavState.iconGlyph ? greyTint : null);
        }
        if (!extra.isEmpty()) {
            tmp.set(tx0, y0 + th * 0.82f, x1, y1);
            multiline(c, extra, tmp, Math.min(th * 0.06f, tw * 0.1f), GREY, 2, false, false);
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

    /**
     * Sematik yol: altta sen, yukari dogru rota, mesafeye gore yerlesen donus.
     * Gercek yolun sekli degildir; yalnizca "ne kadar sonra, hangi yone" gosterir.
     */
    private void drawRoad(Canvas c, RectF r, int dir, float meters) {
        c.save();
        c.clipRect(r);

        float roadW = Math.min(r.width() * 0.2f, r.height() * 0.1f);
        float routeW = roadW * 0.6f;
        float head = routeW * 1.25f;
        float cx = r.centerX();
        float bikeY = r.bottom - roadW * 1.1f;
        float topY = r.top + roadW * 0.5f;

        // 0 m -> hemen onunde, 1 km ve otesi -> en uzakta. Logaritmik olcek
        // yakin mesafelerde hareketi belirgin kilar.
        float f0 = meters < 0f ? 0.5f : (float) (Math.log10(1.0 + meters / 10.0) / Math.log10(101.0));
        if (f0 > 1f) f0 = 1f;
        float turnY = bikeY - (0.16f + 0.84f * f0) * (bikeY - topY - head);

        boolean left = dir == NavState.DIR_LEFT || dir == NavState.DIR_SLIGHT_LEFT;
        float sign = left ? -1f : 1f;
        boolean side = dir == NavState.DIR_LEFT || dir == NavState.DIR_RIGHT;
        boolean slight = dir == NavState.DIR_SLIGHT_LEFT || dir == NavState.DIR_SLIGHT_RIGHT;
        boolean uturn = dir == NavState.DIR_UTURN;

        float diag = Math.max(head * 2f, Math.min(r.width() * 0.36f, turnY - topY - head));
        float lane = roadW * 0.5f;
        float bikeX = uturn ? cx + lane : cx;

        // Gri zemin: uzerinde oldugun yol ve donecegin yol.
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setColor(ROAD);
        stroke.setStrokeWidth(uturn ? roadW * 2f : roadW);
        c.drawLine(cx, r.bottom, cx, r.top, stroke);
        stroke.setStrokeWidth(roadW);
        if (side) {
            c.drawLine(r.left, turnY, r.right, turnY, stroke);
        } else if (slight) {
            c.drawLine(cx, turnY, cx + sign * r.width(), turnY - r.width(), stroke);
        }

        // Mavi rota.
        stroke.setColor(ROUTE);
        stroke.setStrokeWidth(routeW);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(ROUTE);
        path.reset();
        path.moveTo(bikeX, r.bottom + routeW);
        if (side) {
            float rad = roadW * 0.6f;
            float endX = left ? r.left + head * 1.3f : r.right - head * 1.3f;
            path.lineTo(cx, turnY + rad);
            path.quadTo(cx, turnY, cx + sign * rad, turnY);
            path.lineTo(endX, turnY);
            c.drawPath(path, stroke);
            drawHead(c, endX, turnY, sign * 90f, head);
        } else if (slight) {
            float ex = cx + sign * diag;
            float ey = turnY - diag;
            path.lineTo(cx, turnY);
            path.lineTo(ex, ey);
            c.drawPath(path, stroke);
            drawHead(c, ex, ey, sign * 45f, head);
        } else if (uturn) {
            float xr = cx + lane;
            float xl = cx - lane;
            float endY = Math.min(turnY + roadW * 2.4f, bikeY - head);
            path.lineTo(xr, turnY);
            tmp.set(xl, turnY - lane, xr, turnY + lane);
            path.arcTo(tmp, 0f, -180f, false);
            path.lineTo(xl, endY);
            c.drawPath(path, stroke);
            drawHead(c, xl, endY, 180f, head);
        } else {
            path.lineTo(cx, topY + head);
            c.drawPath(path, stroke);
            drawHead(c, cx, topY + head, 0f, head);
        }

        // Motorun yeri.
        float s = roadW * 0.62f;
        path.reset();
        path.moveTo(bikeX, bikeY - s);
        path.lineTo(bikeX - s * 0.85f, bikeY + s * 0.8f);
        path.lineTo(bikeX, bikeY + s * 0.35f);
        path.lineTo(bikeX + s * 0.85f, bikeY + s * 0.8f);
        path.close();
        fill.setColor(ACCENT);
        c.drawPath(path, fill);
        stroke.setColor(BG);
        stroke.setStrokeWidth(s * 0.16f);
        c.drawPath(path, stroke);

        c.restore();
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

    // ------------------------------------------------------------------
    // Muzik
    // ------------------------------------------------------------------

    private void drawMusic(Canvas c) {
        box(c, rMusic, PANEL);
        RectF info = rMusicInfo;
        float side = info.height();
        RectF cover = tmp2;
        cover.set(info.left, info.top, info.left + side, info.bottom);
        Bitmap art = MediaState.art;
        if (art != null && !art.isRecycled()) {
            drawBitmapFit(c, art, cover, corner * 0.5f, null);
        } else {
            fill.setColor(PANEL_HI);
            c.drawRoundRect(cover, corner * 0.5f, corner * 0.5f, fill);
            text(c, "♪", cover.centerX(), baseline(cover.centerY(), side * 0.5f, true), side * 0.5f, GREY,
                    Paint.Align.CENTER, true);
        }

        float tx0 = cover.right + pad * 0.8f;
        float tx1 = info.right;
        String title = MediaState.title;
        String artist = MediaState.artist;
        if (!listenerEnabled) {
            title = "Bildirim erişimi yok";
            artist = "Menü > Bildirim erişimi";
        } else if (!MediaState.hasSession || (title.isEmpty() && artist.isEmpty())) {
            title = "Müzik çalmıyor";
            artist = "Başlatmak için oynat tuşuna bas";
        }
        float barH = Math.max(3f, side * 0.07f);
        tmp.set(tx0, info.top, tx1, info.top + side * 0.52f);
        textFit(c, title, tmp, side * 0.36f, WHITE, Paint.Align.LEFT, true);
        tmp.set(tx0, info.top + side * 0.5f, tx1, info.bottom - barH * 2f);
        textFit(c, artist, tmp, side * 0.25f, GREY, Paint.Align.LEFT, false);

        // Ses duzeyi cubugu.
        if (volume >= 0f) {
            fill.setColor(PANEL_HI);
            c.drawRoundRect(tx0, info.bottom - barH, tx1, info.bottom, barH / 2f, barH / 2f, fill);
            fill.setColor(ACCENT);
            c.drawRoundRect(tx0, info.bottom - barH, tx0 + (tx1 - tx0) * Math.min(1f, volume), info.bottom,
                    barH / 2f, barH / 2f, fill);
        }

        drawMediaButton(c, rPrev, 0, pressed == A_PREV);
        drawMediaButton(c, rPlay, MediaState.playing ? 2 : 1, pressed == A_PLAY);
        drawMediaButton(c, rNext, 3, pressed == A_NEXT);
        drawVolumeButton(c, rVolDown, false, pressed == A_VOL_DOWN);
        drawVolumeButton(c, rVolUp, true, pressed == A_VOL_UP);
    }

    /** kind: 0 onceki, 1 oynat, 2 duraklat, 3 sonraki */
    private void drawMediaButton(Canvas c, RectF r, int kind, boolean down) {
        box(c, r, down ? ACCENT_DIM : PANEL_HI);
        float s = Math.min(r.width(), r.height()) * 0.46f;
        c.save();
        c.translate(r.centerX() - s / 2f, r.centerY() - s / 2f);
        c.scale(s / 100f, s / 100f);
        if (kind == 0) c.scale(-1f, 1f, 50f, 50f);
        fill.setColor(kind == 1 || kind == 2 ? ACCENT : WHITE);
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

    private void drawVolumeButton(Canvas c, RectF r, boolean up, boolean down) {
        box(c, r, down ? ACCENT_DIM : PANEL_HI);
        float label = Math.min(r.height() * 0.17f, r.width() * 0.2f);
        text(c, "SES", r.centerX(), r.top + label * 1.5f, label, GREY, Paint.Align.CENTER, true);
        float s = Math.min(r.width(), r.height()) * 0.34f;
        float cy = r.centerY() + label * 0.5f;
        float t = s * 0.2f;
        fill.setColor(WHITE);
        c.drawRect(r.centerX() - s / 2f, cy - t / 2f, r.centerX() + s / 2f, cy + t / 2f, fill);
        if (up) {
            c.drawRect(r.centerX() - t / 2f, cy - s / 2f, r.centerX() + t / 2f, cy + s / 2f, fill);
        }
    }

    private void drawButtons(Canvas c) {
        drawButton(c, rBtnNav, "YANDEX", pressed == A_OPEN_NAV);
        drawButton(c, rBtnMusic, "SPOTIFY", pressed == A_OPEN_MUSIC);
        drawButton(c, rBtnMenu, "MENÜ", pressed == A_MENU);
    }

    private void drawButton(Canvas c, RectF r, String label, boolean down) {
        box(c, r, down ? ACCENT_DIM : PANEL_HI);
        tmp.set(r.left + pad * 0.5f, r.top, r.right - pad * 0.5f, r.bottom);
        textFit(c, label, tmp, Math.min(r.height() * 0.38f, r.width() * 0.2f), WHITE, Paint.Align.CENTER, true);
    }

    // ------------------------------------------------------------------
    // Cizim yardimcilari
    // ------------------------------------------------------------------

    private void box(Canvas c, RectF r, int color) {
        fill.setColor(color);
        c.drawRoundRect(r, corner, corner, fill);
    }

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
    private void drawBitmapFit(Canvas c, Bitmap b, RectF box, float radius, ColorFilter tint) {
        float bw = b.getWidth();
        float bh = b.getHeight();
        if (bw <= 0f || bh <= 0f) return;
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
        c.drawBitmap(b, null, dst, bmpPaint);
        bmpPaint.setColorFilter(null);
        c.restore();
    }

    /** Yedek buyuk ok (doner kavsak, varis ve taninmayan durumlar icin). 100x100 birimlik karede tanimli. */
    private void drawArrow(Canvas c, RectF box, int dir, int color) {
        float s = Math.min(box.width(), box.height());
        c.save();
        c.translate(box.centerX() - s / 2f, box.centerY() - s / 2f);
        c.scale(s / 100f, s / 100f);
        fill.setColor(color);
        stroke.setColor(color);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        path.reset();

        if (dir == NavState.DIR_ROUNDABOUT) {
            stroke.setStrokeWidth(12f);
            c.drawCircle(50f, 64f, 22f, stroke);
            c.drawRect(44f, 30f, 56f, 44f, fill);
            triangle(c, 50f, 4f, 28f, 34f, 72f, 34f);
        } else if (dir == NavState.DIR_ARRIVE) {
            c.drawCircle(50f, 38f, 26f, fill);
            triangle(c, 28f, 52f, 72f, 52f, 50f, 94f);
            fill.setColor(PANEL);
            c.drawCircle(50f, 38f, 10f, fill);
        } else {
            path.moveTo(50f, 6f);
            path.lineTo(20f, 46f);
            path.lineTo(40f, 46f);
            path.lineTo(40f, 94f);
            path.lineTo(60f, 94f);
            path.lineTo(60f, 46f);
            path.lineTo(80f, 46f);
            path.close();
            c.drawPath(path, fill);
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
    // Dokunma
    // ------------------------------------------------------------------

    private int hit(float x, float y) {
        if (rBtnNav.contains(x, y)) return A_OPEN_NAV;
        if (rBtnMusic.contains(x, y)) return A_OPEN_MUSIC;
        if (rBtnMenu.contains(x, y)) return A_MENU;
        if (rPrev.contains(x, y)) return A_PREV;
        if (rPlay.contains(x, y)) return A_PLAY;
        if (rNext.contains(x, y)) return A_NEXT;
        if (rVolDown.contains(x, y)) return A_VOL_DOWN;
        if (rVolUp.contains(x, y)) return A_VOL_UP;
        if (rSpeed.contains(x, y)) return locPermission ? A_NONE : A_GRANT_LOC;
        if (rNav.contains(x, y)) return listenerEnabled ? A_NAV_BOX : A_GRANT_NOTIF;
        if (rMusicInfo.contains(x, y)) return listenerEnabled ? A_NONE : A_GRANT_NOTIF;
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
