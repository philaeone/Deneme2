package io.github.philaeone.dominarpanel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * Panelin tamami tek bir cizim yuzeyidir. Dikey ve yatay icin bolgeler
 * (hiz, sekmeler, orta panel, alt serit, tuslar) ayri hesaplanir; icerik ayni
 * cizim kodunu kullanir.
 */
final class PanelView extends View {

    interface Actions {
        void onAction(int id);
    }

    static final int A_NONE = 0;
    static final int A_TAB_NAV = 1;
    static final int A_TAB_MUSIC = 2;
    static final int A_OPEN_NAV = 3;
    static final int A_OPEN_MUSIC = 4;
    static final int A_MENU = 5;
    static final int A_PREV = 6;
    static final int A_PLAY = 7;
    static final int A_NEXT = 8;
    static final int A_MINI = 9;
    static final int A_MINI_NEXT = 10;
    static final int A_GRANT_NOTIF = 11;
    static final int A_GRANT_LOC = 12;

    static final int MODE_NAV = 0;
    static final int MODE_MUSIC = 1;

    private static final int BG = 0xFF0B0D0C;
    private static final int PANEL = 0xFF161A18;
    private static final int PANEL_HI = 0xFF26302B;
    private static final int ACCENT = 0xFF39D353;
    private static final int ACCENT_DIM = 0xFF1C4A27;
    private static final int WHITE = 0xFFF2F5F3;
    private static final int GREY = 0xFF98A39D;
    private static final int AMBER = 0xFFFFB300;
    private static final int RED = 0xFFFF5252;

    private static final float MAX_SPEED = 160f;

    // --- MainActivity'nin doldurdugu veriler ---
    float speed = -1f;
    boolean locPermission = true;
    boolean listenerEnabled = true;
    String clock = "";
    int battery = -1;
    boolean charging;
    float battTemp = Float.NaN;
    String version = "";
    int mode = MODE_NAV;

    private final Actions actions;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    private final RectF rStatus = new RectF();
    private final RectF rSpeed = new RectF();
    private final RectF rTabNav = new RectF();
    private final RectF rTabMusic = new RectF();
    private final RectF rPanel = new RectF();
    private final RectF rMini = new RectF();
    private final RectF rMiniBtn = new RectF();
    private final RectF rBtnNav = new RectF();
    private final RectF rBtnMusic = new RectF();
    private final RectF rBtnMenu = new RectF();
    private final RectF rPrev = new RectF();
    private final RectF rPlay = new RectF();
    private final RectF rNext = new RectF();

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
        if (h >= w) {
            float y = pad;
            float statusH = h * 0.055f;
            rStatus.set(pad, y, w - pad, y + statusH);
            y += statusH + pad;

            float speedH = h * 0.26f;
            rSpeed.set(pad, y, w - pad, y + speedH);
            y += speedH + pad;

            float tabH = h * 0.06f;
            float mid = w / 2f;
            rTabNav.set(pad, y, mid - pad / 2f, y + tabH);
            rTabMusic.set(mid + pad / 2f, y, w - pad, y + tabH);
            y += tabH + pad;

            float bottom = h - pad;
            float btnH = h * 0.11f;
            float bw = (w - 4f * pad) / 3f;
            rBtnNav.set(pad, bottom - btnH, pad + bw, bottom);
            rBtnMusic.set(2f * pad + bw, bottom - btnH, 2f * pad + 2f * bw, bottom);
            rBtnMenu.set(3f * pad + 2f * bw, bottom - btnH, w - pad, bottom);

            float miniH = h * 0.07f;
            float miniBottom = bottom - btnH - pad;
            rMini.set(pad, miniBottom - miniH, w - pad, miniBottom);
            rPanel.set(pad, y, w - pad, miniBottom - miniH - pad);
        } else {
            float statusH = h * 0.11f;
            rStatus.set(pad, pad, w - pad, pad + statusH);
            float top = 2f * pad + statusH;
            float bottom = h - pad;

            float leftW = w * 0.28f;
            rSpeed.set(pad, top, pad + leftW, bottom);

            float rightW = w * 0.17f;
            float rx = w - pad - rightW;
            float bh = (bottom - top - 2f * pad) / 3f;
            rBtnNav.set(rx, top, w - pad, top + bh);
            rBtnMusic.set(rx, top + bh + pad, w - pad, top + 2f * bh + pad);
            rBtnMenu.set(rx, top + 2f * bh + 2f * pad, w - pad, bottom);

            float mx0 = 2f * pad + leftW;
            float mx1 = rx - pad;
            float mid = (mx0 + mx1) / 2f;
            float tabH = h * 0.12f;
            rTabNav.set(mx0, top, mid - pad / 2f, top + tabH);
            rTabMusic.set(mid + pad / 2f, top, mx1, top + tabH);

            float miniH = h * 0.13f;
            rMini.set(mx0, bottom - miniH, mx1, bottom);
            rPanel.set(mx0, top + tabH + pad, mx1, bottom - miniH - pad);
        }
        rMiniBtn.set(rMini.right - rMini.height() * 1.6f, rMini.top, rMini.right, rMini.bottom);

        // Muzik tuslari orta panelin alt kismini kaplar.
        float bx0 = rPanel.left + pad;
        float bx1 = rPanel.right - pad;
        float by1 = rPanel.bottom - pad;
        float by0 = by1 - (rPanel.height() - 2f * pad) * 0.38f;
        float w3 = (bx1 - bx0 - 2f * pad) / 3f;
        rPrev.set(bx0, by0, bx0 + w3, by1);
        rPlay.set(bx0 + w3 + pad, by0, bx0 + 2f * w3 + pad, by1);
        rNext.set(bx0 + 2f * w3 + 2f * pad, by0, bx1, by1);
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
        drawTabs(c);
        box(c, rPanel, PANEL);
        if (!listenerEnabled) {
            drawNotice(c, "Bildirim erişimi gerekli", "Spotify ve navigasyon bilgisi için buraya dokun");
        } else if (mode == MODE_NAV) {
            drawNav(c);
        } else {
            drawMusic(c);
        }
        drawMini(c);
        drawButtons(c);
    }

    private void drawStatus(Canvas c) {
        float size = rStatus.height() * 0.78f;
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

    private void drawTabs(Canvas c) {
        drawTab(c, rTabNav, "NAVİGASYON", mode == MODE_NAV, pressed == A_TAB_NAV);
        drawTab(c, rTabMusic, "MÜZİK", mode == MODE_MUSIC, pressed == A_TAB_MUSIC);
    }

    private void drawTab(Canvas c, RectF r, String label, boolean selected, boolean down) {
        box(c, r, down ? PANEL_HI : (selected ? ACCENT_DIM : PANEL));
        tmp.set(r.left + pad, r.top, r.right - pad, r.bottom);
        textFit(c, label, tmp, r.height() * 0.48f, selected ? ACCENT : GREY, Paint.Align.CENTER, true);
    }

    private void drawNotice(Canvas c, String title, String detail) {
        RectF p = rPanel;
        tmp.set(p.left + pad, p.top + p.height() * 0.22f, p.right - pad, p.top + p.height() * 0.48f);
        textFit(c, title, tmp, Math.min(p.height() * 0.13f, p.width() * 0.09f), AMBER, Paint.Align.CENTER, true);
        tmp.set(p.left + pad * 2f, p.top + p.height() * 0.5f, p.right - pad * 2f, p.bottom - pad);
        multiline(c, detail, tmp, Math.min(p.height() * 0.075f, p.width() * 0.055f), GREY, 3, true, false);
    }

    private void drawNav(Canvas c) {
        RectF p = rPanel;
        if (pressed == A_OPEN_NAV + 100) box(c, p, PANEL_HI);
        if (!NavState.active) {
            if (NavState.appPresent) {
                drawNotice(c, "Yönlendirme okunamadı",
                        "Navigasyon açık ama bildiriminden mesafe ya da yön çıkmadı. Menü > Ham navigasyon verisi ekranının görüntüsünü gönder.");
            } else {
                drawNotice(c, "Rota yok", "Yandex Navigasyon'da rota başlatınca sonraki dönüş burada görünür. Açmak için dokun.");
            }
            return;
        }

        float x0 = p.left + pad;
        float y0 = p.top + pad;
        float x1 = p.right - pad;
        float y1 = p.bottom - pad;
        float iw = x1 - x0;
        float ih = y1 - y0;
        boolean wide = iw > ih * 1.25f;

        RectF arrowBox = new RectF();
        RectF textBox = new RectF();
        if (wide) {
            arrowBox.set(x0, y0, x0 + ih, y1);
            textBox.set(x0 + ih + pad, y0, x1, y1);
        } else {
            float s = Math.min(iw, ih * 0.5f);
            arrowBox.set(p.centerX() - s / 2f, y0, p.centerX() + s / 2f, y0 + s);
            textBox.set(x0, y0 + s + pad * 0.5f, x1, y1);
        }

        int dir = NavState.direction;
        Bitmap icon = NavState.icon;
        float inset = Math.min(arrowBox.width(), arrowBox.height()) * 0.08f;
        arrowBox.inset(inset, inset);
        if (dir != NavState.DIR_NONE) {
            drawArrow(c, arrowBox, dir, ACCENT);
        } else if (icon != null && !icon.isRecycled()) {
            drawBitmapFit(c, icon, arrowBox, 0f);
        } else {
            drawArrow(c, arrowBox, NavState.DIR_STRAIGHT, ACCENT_DIM);
        }

        String dist = NavState.distance;
        String ins = NavState.instruction;
        String extra = NavState.extra;
        float th = textBox.height();
        float distH = dist.isEmpty() ? 0f : th * 0.42f;
        float extraH = extra.isEmpty() ? 0f : th * 0.16f;
        if (!dist.isEmpty()) {
            tmp.set(textBox.left, textBox.top, textBox.right, textBox.top + distH);
            textFit(c, dist, tmp, distH * 0.9f, WHITE, wide ? Paint.Align.LEFT : Paint.Align.CENTER, true);
        }
        if (!ins.isEmpty()) {
            tmp.set(textBox.left, textBox.top + distH, textBox.right, textBox.bottom - extraH);
            float size = Math.min(tmp.height() * 0.36f, textBox.width() * 0.085f);
            multiline(c, ins, tmp, size, WHITE, 2, !wide, false);
        }
        if (!extra.isEmpty()) {
            tmp.set(textBox.left, textBox.bottom - extraH, textBox.right, textBox.bottom);
            textFit(c, extra, tmp, extraH * 0.6f, GREY, wide ? Paint.Align.LEFT : Paint.Align.CENTER, false);
        }
    }

    private void drawMusic(Canvas c) {
        RectF p = rPanel;
        float x0 = p.left + pad;
        float x1 = p.right - pad;
        float y0 = p.top + pad;
        float y1 = rPrev.top - pad;
        float h = y1 - y0;

        float side = Math.min(h, (x1 - x0) * 0.38f);
        RectF cover = new RectF(x0, y0 + (h - side) / 2f, x0 + side, y0 + (h + side) / 2f);
        Bitmap art = MediaState.art;
        if (art != null && !art.isRecycled()) {
            drawBitmapFit(c, art, cover, corner * 0.6f);
        } else {
            fill.setColor(PANEL_HI);
            c.drawRoundRect(cover, corner * 0.6f, corner * 0.6f, fill);
            text(c, "♪", cover.centerX(), baseline(cover.centerY(), side * 0.5f, true), side * 0.5f, GREY,
                    Paint.Align.CENTER, true);
        }

        float tx0 = cover.right + pad;
        String title = MediaState.title;
        String artist = MediaState.artist;
        if (!MediaState.hasSession || (title.isEmpty() && artist.isEmpty())) {
            title = "Müzik çalmıyor";
            artist = "Başlatmak için oynat tuşuna bas";
        }
        float tsize = Math.min(h * 0.2f, (x1 - tx0) * 0.14f);
        tmp.set(tx0, y0, x1, y0 + h * 0.62f);
        multiline(c, title, tmp, tsize, WHITE, 2, false, true);
        tmp.set(tx0, y0 + h * 0.62f, x1, y1);
        textFit(c, artist, tmp, tsize * 0.72f, GREY, Paint.Align.LEFT, false);

        drawMediaButton(c, rPrev, 0, pressed == A_PREV);
        drawMediaButton(c, rPlay, MediaState.playing ? 2 : 1, pressed == A_PLAY);
        drawMediaButton(c, rNext, 3, pressed == A_NEXT);
    }

    /** kind: 0 onceki, 1 oynat, 2 duraklat, 3 sonraki */
    private void drawMediaButton(Canvas c, RectF r, int kind, boolean down) {
        box(c, r, down ? ACCENT_DIM : PANEL_HI);
        drawMediaIcon(c, r, kind, kind == 1 || kind == 2 ? ACCENT : WHITE, 0.5f);
    }

    private void drawMediaIcon(Canvas c, RectF r, int kind, int color, float scale) {
        float s = Math.min(r.width(), r.height()) * scale;
        c.save();
        c.translate(r.centerX() - s / 2f, r.centerY() - s / 2f);
        c.scale(s / 100f, s / 100f);
        if (kind == 0) c.scale(-1f, 1f, 50f, 50f);
        fill.setColor(color);
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

    private void drawMini(Canvas c) {
        box(c, rMini, pressed == A_MINI ? PANEL_HI : PANEL);
        float size = rMini.height() * 0.42f;
        if (mode == MODE_NAV) {
            String s;
            if (!MediaState.hasSession || MediaState.title.isEmpty()) {
                s = "♪  Müzik yok";
            } else {
                s = "♪  " + MediaState.title + (MediaState.artist.isEmpty() ? "" : "  ·  " + MediaState.artist);
            }
            tmp.set(rMini.left + pad, rMini.top, rMiniBtn.left - pad * 0.5f, rMini.bottom);
            textFit(c, s, tmp, size, MediaState.playing ? WHITE : GREY, Paint.Align.LEFT, false);
            box(c, rMiniBtn, pressed == A_MINI_NEXT ? ACCENT_DIM : PANEL_HI);
            drawMediaIcon(c, rMiniBtn, 3, WHITE, 0.42f);
        } else {
            String s;
            if (NavState.active) {
                s = "➤  " + NavState.distance
                        + (NavState.instruction.isEmpty() ? "" : (NavState.distance.isEmpty() ? "" : "  ·  ") + NavState.instruction);
            } else {
                s = "➤  Rota yok";
            }
            tmp.set(rMini.left + pad, rMini.top, rMini.right - pad, rMini.bottom);
            textFit(c, s, tmp, size, NavState.active ? WHITE : GREY, Paint.Align.LEFT, false);
        }
    }

    private void drawButtons(Canvas c) {
        drawButton(c, rBtnNav, "YANDEX", pressed == A_OPEN_NAV);
        drawButton(c, rBtnMusic, "SPOTIFY", pressed == A_OPEN_MUSIC);
        drawButton(c, rBtnMenu, "MENÜ", pressed == A_MENU);
    }

    private void drawButton(Canvas c, RectF r, String label, boolean down) {
        box(c, r, down ? ACCENT_DIM : PANEL_HI);
        tmp.set(r.left + pad * 0.6f, r.top, r.right - pad * 0.6f, r.bottom);
        textFit(c, label, tmp, Math.min(r.height() * 0.34f, r.width() * 0.2f), WHITE, Paint.Align.CENTER, true);
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
        c.translate(r.left, r.top + Math.max(0f, (r.height() - layout.getHeight()) / 2f));
        layout.draw(c);
        c.restore();
    }

    /** Resmi oranini bozmadan kutuya sigdirir; radius > 0 ise koseleri yuvarlar. */
    private void drawBitmapFit(Canvas c, Bitmap b, RectF box, float radius) {
        float bw = b.getWidth();
        float bh = b.getHeight();
        if (bw <= 0f || bh <= 0f) return;
        float scale = Math.min(box.width() / bw, box.height() / bh);
        float dw = bw * scale;
        float dh = bh * scale;
        RectF dst = new RectF(box.centerX() - dw / 2f, box.centerY() - dh / 2f,
                box.centerX() + dw / 2f, box.centerY() + dh / 2f);
        c.save();
        if (radius > 0f) {
            path.reset();
            path.addRoundRect(dst, radius, radius, Path.Direction.CW);
            c.clipPath(path);
        }
        c.drawBitmap(b, null, dst, bmpPaint);
        c.restore();
    }

    /** Yon oklari 100x100 birimlik bir karede tanimlidir, kutuya olceklenir. */
    private void drawArrow(Canvas c, RectF box, int dir, int color) {
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
                stroke.setStrokeWidth(12f);
                c.drawCircle(50f, 64f, 22f, stroke);
                c.drawRect(44f, 30f, 56f, 44f, fill);
                triangle(c, 50f, 4f, 28f, 34f, 72f, 34f);
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
    // Dokunma
    // ------------------------------------------------------------------

    private int hit(float x, float y) {
        if (rTabNav.contains(x, y)) return A_TAB_NAV;
        if (rTabMusic.contains(x, y)) return A_TAB_MUSIC;
        if (rBtnNav.contains(x, y)) return A_OPEN_NAV;
        if (rBtnMusic.contains(x, y)) return A_OPEN_MUSIC;
        if (rBtnMenu.contains(x, y)) return A_MENU;
        if (rSpeed.contains(x, y)) return locPermission ? A_NONE : A_GRANT_LOC;
        if (rMini.contains(x, y)) {
            if (mode == MODE_NAV && rMiniBtn.contains(x, y)) return A_MINI_NEXT;
            return A_MINI;
        }
        if (rPanel.contains(x, y)) {
            if (!listenerEnabled) return A_GRANT_NOTIF;
            if (mode == MODE_MUSIC) {
                if (rPrev.contains(x, y)) return A_PREV;
                if (rPlay.contains(x, y)) return A_PLAY;
                if (rNext.contains(x, y)) return A_NEXT;
                return A_NONE;
            }
            // Navigasyon panelinin kendisine dokunmak da Yandex'i acar.
            return A_OPEN_NAV + 100;
        }
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
                if (h != A_NONE && h == was) perform(h);
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

    private void perform(int id) {
        if (id == A_OPEN_NAV + 100) id = A_OPEN_NAV;
        if (id == A_TAB_NAV) {
            mode = MODE_NAV;
        } else if (id == A_TAB_MUSIC) {
            mode = MODE_MUSIC;
        } else if (id == A_MINI) {
            mode = mode == MODE_NAV ? MODE_MUSIC : MODE_NAV;
        }
        invalidate();
        actions.onAction(id);
    }
}
