package io.github.philaeone.artci;

import android.graphics.Bitmap;

/**
 * Yandex yonu yaziyla vermedigi icin manevra simgesinin seklinden cikarilir.
 *
 * Yontem: simge 48x48'e kucultulur, dolu pikseller bulunur. Okun govdesi altta
 * baslar; ust kismin agirlik merkezi govdenin soluna kayiyorsa sola, sagina
 * kayiyorsa saga donustur. Kaba bir tahmindir; gercek simgeler goruldukce
 * ayarlanmasi gerekir. Emin olunamayan durumda DIR_NONE doner ve panel
 * uygulamanin kendi simgesini gosterir.
 */
final class IconShape {
    private static final int N = 48;

    static final class Result {
        int direction = NavState.DIR_NONE;
        boolean glyph;
        String signature = "";
        String ascii = "";
        float offset;
        /** Eslesen sablonun adi; eslesme yoksa bos. */
        String template = "";
        float score;
    }

    private IconShape() {}

    static Result analyze(Bitmap src) {
        Result r = new Result();
        if (src == null) return r;
        try {
            Bitmap soft = src;
            if (src.getConfig() != Bitmap.Config.ARGB_8888) {
                soft = src.copy(Bitmap.Config.ARGB_8888, false);
                if (soft == null) return r;
            }
            Bitmap small = Bitmap.createScaledBitmap(soft, N, N, true);
            int[] px = new int[N * N];
            small.getPixels(px, 0, N, 0, 0, N, N);

            boolean[] mask = new boolean[N * N];
            int opaque = 0;
            for (int i = 0; i < px.length; i++) {
                if ((px[i] >>> 24) >= 100) opaque++;
            }
            r.glyph = opaque < px.length * 0.85f;
            int count = 0;
            if (r.glyph) {
                for (int i = 0; i < px.length; i++) {
                    mask[i] = (px[i] >>> 24) >= 100;
                    if (mask[i]) count++;
                }
            } else {
                // Zemin doluysa kose rengine benzemeyen pikseller sekildir.
                int ref = px[0];
                for (int i = 0; i < px.length; i++) {
                    mask[i] = colorDistance(px[i], ref) > 90;
                    if (mask[i]) count++;
                }
            }

            StringBuilder art = new StringBuilder();
            for (int y = 0; y < N; y += 2) {
                for (int x = 0; x < N; x += 2) {
                    int n = 0;
                    if (mask[y * N + x]) n++;
                    if (mask[y * N + x + 1]) n++;
                    if (mask[(y + 1) * N + x]) n++;
                    if (mask[(y + 1) * N + x + 1]) n++;
                    art.append(n >= 2 ? '#' : '.');
                }
                art.append('\n');
            }
            r.ascii = art.toString();
            r.signature = Integer.toHexString(r.ascii.hashCode());

            if (count < 20 || count > px.length * 0.8f) return r;

            // Once bilinen gercek simgelerle karsilastir; tutarsa kural tabanli tahmine gerek yok.
            if (matchTemplate(r)) return r;

            int minX = N, maxX = -1, minY = N, maxY = -1;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    if (!mask[y * N + x]) continue;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            float bw = maxX - minX + 1;
            float bh = maxY - minY + 1;
            if (bw < 6 || bh < 6) return r;
            float cx = (minX + maxX) / 2f;

            // Govdenin tabani: en alttaki satirlar.
            int baseRows = Math.max(2, Math.round(bh * 0.12f));
            float baseSum = 0f;
            int baseN = 0;
            for (int y = maxY - baseRows + 1; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (mask[y * N + x]) {
                        baseSum += x;
                        baseN++;
                    }
                }
            }
            if (baseN == 0) return r;
            float xBase = baseSum / baseN;

            // Ust kisim: ok ucunun ve donusun bulundugu bolge.
            int topEnd = minY + Math.round(bh * 0.55f);
            float topSum = 0f;
            int topN = 0;
            for (int y = minY; y <= topEnd && y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (mask[y * N + x]) {
                        topSum += x;
                        topN++;
                    }
                }
            }
            if (topN == 0) return r;
            float offset = (topSum / topN - xBase) / bw;
            r.offset = offset;

            // U donusu: alt bolgede govdenin karsi tarafinda da belirgin kutle olur.
            int lowStart = minY + Math.round(bh * 0.6f);
            int lowLeft = 0;
            int lowRight = 0;
            int lowSpan = 0;
            for (int y = lowStart; y <= maxY; y++) {
                int first = -1;
                int last = -1;
                for (int x = minX; x <= maxX; x++) {
                    if (!mask[y * N + x]) continue;
                    if (first < 0) first = x;
                    last = x;
                    if (x < cx) lowLeft++; else lowRight++;
                }
                if (first >= 0) lowSpan = Math.max(lowSpan, last - first + 1);
            }
            boolean baseOnRight = xBase > cx;
            float same = baseOnRight ? lowRight : lowLeft;
            float opposite = baseOnRight ? lowLeft : lowRight;
            boolean offCentre = Math.abs(xBase - cx) > bw * 0.12f;

            if (offCentre && same > 0f && opposite / same > 0.35f) {
                r.direction = NavState.DIR_UTURN;
            } else if (offset <= -0.2f) {
                r.direction = NavState.DIR_LEFT;
            } else if (offset >= 0.2f) {
                r.direction = NavState.DIR_RIGHT;
            } else if (offset <= -0.1f) {
                r.direction = NavState.DIR_SLIGHT_LEFT;
            } else if (offset >= 0.1f) {
                r.direction = NavState.DIR_SLIGHT_RIGHT;
            } else if (bh > bw * 1.25f && lowSpan < bw * 0.5f) {
                // Duz ok: altta yalnizca ince govde vardir. Halka gibi genis
                // sekiller (doner kavsak) buraya girmez, taninmadi sayilir.
                r.direction = NavState.DIR_STRAIGHT;
            }
        } catch (Throwable t) {
            r.direction = NavState.DIR_NONE;
        }
        return r;
    }

    /** Maskeyi sablonlarla karsilastirir; kesisim/birlesim orani yeterliyse yonu yazar. */
    private static boolean matchTemplate(Result r) {
        String[] rows = r.ascii.split("\n");
        if (rows.length < 24) return false;
        int best = -1;
        float bestScore = 0f;
        for (int t = 0; t < IconTemplates.ROWS.length; t++) {
            String[] tpl = IconTemplates.ROWS[t];
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int inter = 0;
                    int union = 0;
                    for (int y = 0; y < 24; y++) {
                        int ty = y + dy;
                        for (int x = 0; x < 24; x++) {
                            int tx = x + dx;
                            boolean a = rows[y].charAt(x) == '#';
                            boolean b = ty >= 0 && ty < 24 && tx >= 0 && tx < 24 && tpl[ty].charAt(tx) == '#';
                            if (a && b) inter++;
                            if (a || b) union++;
                        }
                    }
                    float score = union == 0 ? 0f : inter / (float) union;
                    if (score > bestScore) {
                        bestScore = score;
                        best = t;
                    }
                }
            }
        }
        r.score = bestScore;
        if (best >= 0 && bestScore >= 0.72f) {
            r.direction = IconTemplates.DIRECTIONS[best];
            r.template = IconTemplates.NAMES[best];
            return true;
        }
        return false;
    }

    private static int colorDistance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.abs(dr) + Math.abs(dg) + Math.abs(db);
    }

    static String name(int direction) {
        switch (direction) {
            case NavState.DIR_LEFT: return "SOL";
            case NavState.DIR_RIGHT: return "SAĞ";
            case NavState.DIR_STRAIGHT: return "DÜZ";
            case NavState.DIR_UTURN: return "U DÖNÜŞÜ";
            case NavState.DIR_SLIGHT_LEFT: return "HAFİF SOL";
            case NavState.DIR_SLIGHT_RIGHT: return "HAFİF SAĞ";
            case NavState.DIR_ROUNDABOUT: return "DÖNER KAVŞAK";
            case NavState.DIR_ARRIVE: return "VARIŞ";
            default: return "TANINMADI";
        }
    }
}
