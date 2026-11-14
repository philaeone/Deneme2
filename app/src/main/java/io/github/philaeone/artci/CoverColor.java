package io.github.philaeone.artci;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Album kapagindan panelin vurgu rengini secer.
 *
 * Kapak 24x24'e kucultulur, yeterince canli pikseller 24 renk tonu kutusuna
 * dagitilir ve en agir kutu secilir. Sonuc koyu zeminde okunacak sekilde
 * parlatilir. Kapak siyah-beyaz ya da cok solgunsa null doner; panel o zaman
 * varsayilan yesili kullanir.
 */
final class CoverColor {
    private static final int N = 24;
    private static final int BINS = 24;

    private CoverColor() {}

    /** @return {vurgu, koyu vurgu, tus zemini} ya da uygun renk yoksa null */
    static int[] theme(Bitmap art) {
        if (art == null || art.isRecycled()) return null;
        try {
            Bitmap soft = art;
            if (art.getConfig() != Bitmap.Config.ARGB_8888) {
                soft = art.copy(Bitmap.Config.ARGB_8888, false);
                if (soft == null) return null;
            }
            Bitmap small = Bitmap.createScaledBitmap(soft, N, N, true);
            int[] px = new int[N * N];
            small.getPixels(px, 0, N, 0, 0, N, N);

            float[] weight = new float[BINS];
            float[] sumS = new float[BINS];
            float[] sumV = new float[BINS];
            double[] sumX = new double[BINS];
            double[] sumY = new double[BINS];
            float[] hsv = new float[3];
            float total = 0f;

            for (int p : px) {
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue;
                float w = hsv[1] * hsv[1] * hsv[2];
                int bin = Math.min(BINS - 1, (int) (hsv[0] / 360f * BINS));
                weight[bin] += w;
                sumS[bin] += hsv[1] * w;
                sumV[bin] += hsv[2] * w;
                double rad = Math.toRadians(hsv[0]);
                sumX[bin] += Math.cos(rad) * w;
                sumY[bin] += Math.sin(rad) * w;
                total += w;
            }
            // Renkli alan cok kucukse (siyah-beyaz kapak) tema uretme.
            if (total < px.length * 0.02f) return null;

            int best = 0;
            for (int i = 1; i < BINS; i++) {
                if (weight[i] > weight[best]) best = i;
            }
            if (weight[best] <= 0f) return null;

            float hue = (float) Math.toDegrees(Math.atan2(sumY[best], sumX[best]));
            if (hue < 0f) hue += 360f;
            float sat = sumS[best] / weight[best];

            // Koyu zeminde okunakli olsun: parlaklik yuksek, doygunluk sinirli.
            float s = Math.max(0.5f, Math.min(0.85f, sat));
            // Mavi-mor tonlar ayni parlaklikta daha koyu gorunur; doygunlugu azalt.
            if (hue > 215f && hue < 290f) s = Math.min(s, 0.6f);

            int accent = Color.HSVToColor(new float[] {hue, s, 1f});
            int dim = Color.HSVToColor(new float[] {hue, Math.min(0.75f, s), 0.34f});
            int button = Color.HSVToColor(new float[] {hue, 0.42f, 0.2f});
            return new int[] {accent, dim, button};
        } catch (Throwable t) {
            return null;
        }
    }
}
