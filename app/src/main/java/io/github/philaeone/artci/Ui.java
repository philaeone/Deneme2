package io.github.philaeone.artci;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Menu ve ayar pencerelerinin panelle ayni dili konusmasi icin cizim parcalari. */
final class Ui {
    private Ui() {}

    /** Kesik koseli cerceve; panel kutulariyla ayni gorunum. */
    static final class Frame extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float cut;
        private final float inset;

        Frame(int fillColor, int strokeColor, int cornerColor, float cut, float strokeWidth) {
            this.cut = cut;
            this.inset = strokeWidth;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(fillColor);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(strokeColor);
            stroke.setStrokeWidth(strokeWidth);
            corner.setStyle(Paint.Style.STROKE);
            corner.setColor(cornerColor);
            corner.setStrokeWidth(strokeWidth * 1.5f);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float l = b.left + inset;
            float t = b.top + inset;
            float r = b.right - inset;
            float bt = b.bottom - inset;
            float k = Math.min(cut, Math.min(r - l, bt - t) * 0.3f);
            path.reset();
            path.moveTo(l + k, t);
            path.lineTo(r - k, t);
            path.lineTo(r, t + k);
            path.lineTo(r, bt - k);
            path.lineTo(r - k, bt);
            path.lineTo(l + k, bt);
            path.lineTo(l, bt - k);
            path.lineTo(l, t + k);
            path.close();
            c.drawPath(path, fill);
            c.drawPath(path, stroke);

            float len = k * 1.6f;
            path.reset();
            path.moveTo(l, t + k + len);
            path.lineTo(l, t + k);
            path.lineTo(l + k, t);
            path.lineTo(l + k + len, t);
            path.moveTo(r, bt - k - len);
            path.lineTo(r, bt - k);
            path.lineTo(r - k, bt);
            path.lineTo(r - k - len, bt);
            c.drawPath(path, corner);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static final int GLYPH_GRID = 0;
    static final int GLYPH_SLIDERS = 1;

    /** Cizilen simge: uygulama izgarasi ya da ayar surguleri. */
    static final class Glyph extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        private final int color;
        private final int knobColor;

        Glyph(int kind, int color, int knobColor) {
            this.kind = kind;
            this.color = color;
            this.knobColor = knobColor;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) * 0.8f;
            float x0 = b.exactCenterX() - s / 2f;
            float y0 = b.exactCenterY() - s / 2f;
            paint.setStyle(Paint.Style.FILL);
            if (kind == GLYPH_GRID) {
                float cell = s / 3f;
                float box = cell * 0.66f;
                paint.setColor(color);
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        float x = x0 + col * cell + (cell - box) / 2f;
                        float y = y0 + row * cell + (cell - box) / 2f;
                        c.drawRect(x, y, x + box, y + box, paint);
                    }
                }
            } else {
                float[] knob = {0.3f, 0.7f, 0.45f};
                float bar = s * 0.08f;
                float rad = s * 0.13f;
                for (int i = 0; i < 3; i++) {
                    float y = y0 + s * (0.2f + 0.3f * i);
                    paint.setColor(color);
                    c.drawRect(x0, y - bar / 2f, x0 + s, y + bar / 2f, paint);
                    paint.setColor(knobColor);
                    c.drawCircle(x0 + s * knob[i], y, rad, paint);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
