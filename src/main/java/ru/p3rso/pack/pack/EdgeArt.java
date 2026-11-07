package ru.p3rso.pack.pack;

import ru.p3rso.pack.model.EdgeLayout;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/**
 * Рисует картинки элементов меню у края экрана: кнопки, панели, шапку, фон. Стиль — тёмно-фиолетовый
 * с золотом, надписи пиксельным шрифтом {@link PixelFont}. Результат детерминирован (шум с фиксированным сидом).
 */
public final class EdgeArt {
    static final int[] GOLD = {232, 188, 98}, GOLD_L = {255, 232, 160}, GOLD_D = {130, 88, 36};

    private final BufferedImage img;
    private final int w, h;
    private final Random rnd;
    private java.util.function.Function<String, BufferedImage> iconLoader = k -> null;

    private EdgeArt(int w, int h, long seed) {
        this.w = w;
        this.h = h;
        this.img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        this.rnd = new Random(seed);
    }

    // ------------------------------------------------------------------ public

    public static BufferedImage background(int w, int h) {
        EdgeArt a = new EdgeArt(w, h, 1);
        a.rrect(0, 0, w, h, new int[]{28, 21, 48}, new int[]{12, 9, 22}, 238, 8, 2);
        a.glow(w * 0.6, h * 0.25, w * 0.55, h * 0.45, new int[]{110, 70, 210}, 36);
        for (int y = 6; y < h - 6; y++) {
            for (int x = 6; x < w - 6; x++) if (a.rnd.nextDouble() < 0.003) a.blend(x, y, 255, 240, 220, 40 + a.rnd.nextInt(70));
        }
        a.border(0, 0, w, h, GOLD_L, GOLD_D, 255, 8);
        a.border(2, 2, w - 4, h - 4, new int[]{255, 220, 150}, new int[]{120, 80, 40}, 45, 6);
        int[][] corners = {{6, 6}, {w - 7, 6}, {6, h - 7}, {w - 7, h - 7}};
        for (int[] c : corners) a.diamond(c[0], c[1], 2, GOLD_L, 255);
        return a.img;
    }

    /**
     * Значок ⚠ диалога (признак окна от сервера) в стиле меню: тёмный фон, золотая рамка, жёлтый треугольник с «!».
     * Значок остаётся на месте и хорошо заметен — меняется только оформление. state: 0 обычный, 1 наведение, 2 неактивный.
     */
    public static BufferedImage warningButton(int state) {
        EdgeArt a = new EdgeArt(20, 20, 7);
        boolean hi = state == 1, off = state == 2;
        int[] top = off ? new int[]{60, 58, 66} : hi ? new int[]{92, 70, 150} : new int[]{58, 44, 98};
        int[] bot = off ? new int[]{34, 33, 38} : hi ? new int[]{48, 34, 86} : new int[]{26, 19, 46};
        a.rrect(0, 0, 20, 20, top, bot, 255, 4, 0);
        a.glow(10, 2, 12, 9, new int[]{255, 255, 255}, hi ? 60 : 30);
        int[] b1 = off ? new int[]{150, 150, 150} : hi ? new int[]{255, 240, 180} : GOLD_L;
        int[] b2 = off ? new int[]{80, 80, 80} : GOLD_D;
        a.border(0, 0, 20, 20, b1, b2, 255, 4);
        // треугольник
        int[] y1 = off ? new int[]{170, 170, 170} : new int[]{255, 214, 70}, y2 = off ? new int[]{120, 120, 120} : new int[]{220, 150, 30};
        for (int y = 4; y <= 15; y++) {
            double t = (y - 4) / 11.0;
            int halfW = (int) Math.round(t * 6.0);
            int[] c = mix(y1, y2, t);
            for (int x = 10 - halfW - 1; x <= 9 + halfW + 1; x++) a.blend(x, y, 30, 18, 6, 255);     // тёмная обводка
            for (int x = 10 - halfW; x <= 9 + halfW; x++) a.blend(x, y, c[0], c[1], c[2], 255);
        }
        for (int x = 3; x <= 16; x++) a.blend(x, 16, 30, 18, 6, 255);
        // «!»
        int[] ex = {40, 24, 8};
        for (int y = 7; y <= 11; y++) { a.blend(9, y, ex[0], ex[1], ex[2], 255); a.blend(10, y, ex[0], ex[1], ex[2], 255); }
        a.blend(9, 13, ex[0], ex[1], ex[2], 255);
        a.blend(10, 13, ex[0], ex[1], ex[2], 255);
        return a.img;
    }

    public static BufferedImage render(EdgeLayout.Element e, BufferedImage icon) { return render(e, icon, k -> null); }

    /** iconLoader: ключ текстуры → картинка (для иконок плашек). */
    public static BufferedImage render(EdgeLayout.Element e, BufferedImage icon, java.util.function.Function<String, BufferedImage> iconLoader) {
        EdgeArt a = new EdgeArt(e.width(), e.height(), e.key().hashCode());
        a.iconLoader = iconLoader;
        EdgeLayout.Art art = e.art();
        switch (e.kind()) {
            case BUTTON -> a.button(art, icon);
            case PANEL -> a.panel(art, icon);
            case HEADER -> a.header(art, icon);
            default -> {}
        }
        return a.img;
    }

    // ------------------------------------------------------------------ kinds

    private void button(EdgeLayout.Art art, BufferedImage icon) {
        int[] top = art.active() ? lighten(art.top(), .15) : art.top(), bot = art.bottom();
        rrect(1, 2, w - 1, h - 2, new int[]{0, 0, 0}, new int[]{0, 0, 0}, 110, 4, 0);
        rrect(0, 0, w - 1, h - 1, top, bot, 236, 4, 2);
        glow(w * 0.35, 1, w * 0.5, h * 0.6, new int[]{255, 255, 255}, art.active() ? 60 : 38);
        border(0, 0, w - 1, h - 1, lighten(top, .45), darken(bot, .5), 255, 4);
        if (art.active()) rrect(0, 3, 2, h - 7, GOLD_L, GOLD, 255, 1, 0);
        int tx = 6;
        if (icon != null) {
            int target = Math.min(h - 6, 8) * art.iconScale();
            blitIcon(icon, 5, (h - 1 - target) / 2, target);
            tx = 5 + target + 4;
        }
        int[] tc = textColor(top);
        text(art.label(), tx, (h - 1 - PixelFont.HEIGHT) / 2, tc, mix(tc, top, .25), 1, true);
    }

    private void panel(EdgeLayout.Art art, BufferedImage icon) {
        if (h >= 80) {
            largePanel(art, icon);
            return;
        }
        int[] top = art.top(), bot = art.bottom();
        rrect(2, 3, w - 2, h - 2, new int[]{0, 0, 0}, new int[]{0, 0, 0}, 80, 6, 0);
        rrect(1, 1, w - 2, h - 2, top, bot, 240, 6, 2.5);
        glow(w * 0.5, 4, w * 0.55, h * 0.45, new int[]{255, 255, 255}, 46);
        border(1, 1, w - 2, h - 2, lighten(top, .55), darken(bot, .55), 255, 6);
        border(3, 3, w - 6, h - 6, lighten(top, .25), darken(bot, .2), 60, 4);
        int[] tc = textColor(top);
        int iconSize = icon == null ? 0 : 16 * art.iconScale();
        boolean iconRight = icon != null && iconSize * 1.6 < w;
        if (icon != null) {
            int ix = iconRight ? w - iconSize - 7 : (w - iconSize) / 2, iy = Math.max(4, (h - iconSize) / 2);
            glow(ix + iconSize / 2.0, iy + iconSize / 2.0, iconSize * 0.8, iconSize * 0.7, lighten(top, .6), 70);
            blitIcon(icon, ix, iy, iconSize);
        }
        int ty = 6;
        if (!art.label().isEmpty()) {
            text(art.label(), 7, ty, textColor(top), mix(tc, lighten(top, .6), .3), 1, true);
            ty += 11;
        }
        if (art.subtitle() != null) {
            boolean light = tc[0] < 128;
            int[] sc = light ? mix(tc, top, .2) : mix(tc, lighten(top, .4), .5);
            text(art.subtitle(), 7, ty, sc, light ? sc : mix(tc, top, .5), 1, !light);
            ty += 10;
        }
        for (String line : art.text()) {
            if (ty + PixelFont.HEIGHT > h - 4) break;
            text(line, 7, ty, mix(tc, new int[]{225, 220, 255}, .5), mix(tc, new int[]{180, 170, 230}, .5), 1, true);
            ty += 10;
        }
        if (art.pill() != null) {
            int pw = PixelFont.get().width(art.pill(), 1) + 12, ph = 13;
            int px = 6, py = h - ph - 6;
            rrect(px + 1, py + 2, pw, ph, new int[]{0, 0, 0}, new int[]{0, 0, 0}, 90, 6, 0);
            rrect(px, py, pw, ph, new int[]{255, 214, 110}, new int[]{214, 140, 40}, 255, 6, 0);
            border(px, py, pw, ph, new int[]{255, 240, 190}, new int[]{120, 70, 10}, 255, 6);
            text(art.pill(), px + 6, py + 3, new int[]{70, 36, 6}, new int[]{90, 46, 8}, 1, false);
        }
    }

    /** Большая панель (содержимое вкладки): шапка с заголовком, золотой разделитель, текст, иконка в углу. */
    private void largePanel(EdgeLayout.Art art, BufferedImage icon) {
        int[] top = art.top(), bot = art.bottom();
        int[] deep = darken(bot, .35);
        rrect(3, 4, w - 3, h - 3, new int[]{0, 0, 0}, new int[]{0, 0, 0}, 90, 8, 0);
        rrect(1, 1, w - 2, h - 2, mix(deep, bot, .6), deep, 244, 8, 2);
        glow(w * 0.5, h * 0.45, w * 0.6, h * 0.5, lighten(top, .2), 26);
        // шапка
        rrect(4, 4, w - 8, 20, lighten(top, .1), top, 250, 5, 2);
        glow(w * 0.5, 4, w * 0.6, 14, new int[]{255, 255, 255}, 55);
        border(4, 4, w - 8, 20, lighten(top, .55), darken(top, .4), 255, 5);
        int[] tc = textColor(top);
        text(art.label(), 10, 10, tc, mix(tc, lighten(top, .6), .25), 1, tc[0] > 128);
        // рамка и разделитель
        border(1, 1, w - 2, h - 2, GOLD_L, GOLD_D, 255, 8);
        border(3, 3, w - 6, h - 6, new int[]{255, 220, 150}, new int[]{120, 80, 40}, 40, 6);
        int ty = 30;
        if (art.subtitle() != null) {
            text(art.subtitle(), 10, ty, GOLD_L, GOLD, 1, true);
            ty += 11;
        }
        for (int i = 10; i < w - 10; i++) {
            double t = 1 - Math.abs(i - w / 2.0) / (w / 2.0 - 10);
            blend(i, ty, GOLD[0], GOLD[1], GOLD[2], (int) (40 + 170 * t));
        }
        diamond(w / 2, ty, 2, GOLD_L, 255);
        ty += 7;
        boolean hasCards = !art.cards().isEmpty();
        int iconSize = icon == null ? 0 : 16 * art.iconScale();
        if (icon != null && !hasCards) {
            int ix = w - iconSize - 10, iy = h - iconSize - 10;
            glow(ix + iconSize / 2.0, iy + iconSize / 2.0, iconSize * 0.85, iconSize * 0.75, lighten(top, .5), 80);
            blitIcon(icon, ix, iy, iconSize);
        }
        for (String line : art.text()) {
            if (ty + PixelFont.HEIGHT > h - 8) break;
            text(line, 10, ty, new int[]{232, 228, 255}, new int[]{190, 182, 235}, 1, true);
            ty += 11;
        }
        if (hasCards) cards(art, 10, ty + (art.text().isEmpty() ? 0 : 3), w - 20, h - 10 - ty - (art.text().isEmpty() ? 0 : 3));
        int[][] corners = {{6, 6}, {w - 7, 6}, {6, h - 7}, {w - 7, h - 7}};
        for (int[] c : corners) diamond(c[0], c[1], 1, GOLD_L, 255);
    }

    /** Сетка плашек с информацией: иконка, заголовок, строки текста. */
    private void cards(EdgeLayout.Art art, int x0, int y0, int aw, int ah) {
        List<EdgeLayout.Card> cards = art.cards();
        int cols = Math.max(1, Math.min(art.columns(), cards.size()));
        int rows = (cards.size() + cols - 1) / cols;
        int gap = 6;
        int cw = (aw - gap * (cols - 1)) / cols, ch = Math.max(16, (ah - gap * (rows - 1)) / rows);
        for (int i = 0; i < cards.size(); i++) {
            EdgeLayout.Card c = cards.get(i);
            int cx = x0 + (i % cols) * (cw + gap), cy = y0 + (i / cols) * (ch + gap);
            int[] top = c.top(), bot = c.bottom();
            rrect(cx + 1, cy + 2, cw, ch, new int[]{0, 0, 0}, new int[]{0, 0, 0}, 80, 4, 0);
            rrect(cx, cy, cw, ch, mix(top, bot, .35), darken(bot, .25), 235, 4, 2);
            rrect(cx, cy, cw, 12, lighten(top, .1), top, 245, 4, 0);                          // цветная шапка
            glow(cx + cw * 0.5, cy, cw * 0.55, 10, new int[]{255, 255, 255}, 45);
            border(cx, cy, cw, ch, lighten(top, .5), darken(bot, .45), 255, 4);
            int tx = cx + 5;
            BufferedImage ic = c.icon() == null ? null : iconLoader.apply(c.icon());
            if (ic != null) {
                blitIcon(ic, cx + 4, cy + 2, 8);
                tx = cx + 15;
            }
            int[] tc = textColor(top);
            text(c.title(), tx, cy + 3, tc, mix(tc, lighten(top, .6), .3), 1, tc[0] > 128);
            int ly = cy + 17;
            int[] body = textColor(mix(top, bot, .35));
            boolean dark = body[0] < 128;
            for (String line : c.text()) {
                if (ly + PixelFont.HEIGHT > cy + ch - 3) break;
                if (dark) text(line, cx + 5, ly, body, mix(body, bot, .2), 1, false);
                else text(line, cx + 5, ly, new int[]{232, 228, 255}, new int[]{190, 182, 235}, 1, true);
                ly += 10;
            }
        }
    }

    private void header(EdgeLayout.Art art, BufferedImage icon) {
        int x = 0;
        if (icon != null) {
            blitIcon(icon, 0, 0, 16);
            x = 20;
        }
        int lw = PixelFont.get().width(art.label(), 2);
        glow(x + lw / 2.0, 8, lw * 0.7 + 6, 10, new int[]{255, 190, 90}, 45);
        text(art.label(), x + 1, 1, new int[]{255, 240, 190}, new int[]{230, 150, 50}, 2, true);
        int lineX = x + lw + 6;
        for (int i = 0; i < Math.min(60, w - lineX - 1); i++) blend(lineX + i, 8, GOLD[0], GOLD[1], GOLD[2], (int) (200 * (1 - i / 60.0)));
        if (lineX < w - 3) diamond(lineX, 8, 2, GOLD_L, 255);
        if (art.subtitle() != null) text(art.subtitle(), x + 1, 18, new int[]{200, 185, 255}, new int[]{150, 130, 230}, 1, true);
    }

    // ------------------------------------------------------------------ primitives

    static int[] lighten(int[] c, double t) { return mix(c, new int[]{255, 255, 255}, t); }

    static int[] darken(int[] c, double t) { return mix(c, new int[]{0, 0, 0}, t); }

    static int[] mix(int[] a, int[] b, double t) {
        return new int[]{(int) (a[0] + (b[0] - a[0]) * t), (int) (a[1] + (b[1] - a[1]) * t), (int) (a[2] + (b[2] - a[2]) * t)};
    }

    static int[] textColor(int[] bg) {
        double lum = 0.299 * bg[0] + 0.587 * bg[1] + 0.114 * bg[2];
        return lum > 150 ? new int[]{70, 36, 6} : new int[]{255, 255, 255};
    }

    private void blend(int x, int y, int r, int g, int b, int a) {
        if (x < 0 || y < 0 || x >= w || y >= h || a <= 0) return;
        int d = img.getRGB(x, y);
        double sa = Math.min(255, a) / 255.0, da = (d >>> 24) / 255.0;
        double oa = sa + da * (1 - sa);
        if (oa <= 0) return;
        int or = (int) Math.round((r * sa + ((d >> 16) & 255) * da * (1 - sa)) / oa);
        int og = (int) Math.round((g * sa + ((d >> 8) & 255) * da * (1 - sa)) / oa);
        int ob = (int) Math.round((b * sa + (d & 255) * da * (1 - sa)) / oa);
        img.setRGB(x, y, ((int) Math.round(oa * 255) << 24) | (clamp(or) << 16) | (clamp(og) << 8) | clamp(ob));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static boolean cornerOut(int x, int y, int w, int h, int r) {
        if (r <= 0) return false;
        int cx, cy;
        if (x < r) cx = r - 1;
        else if (x >= w - r) cx = w - r;
        else return false;
        if (y < r) cy = r - 1;
        else if (y >= h - r) cy = h - r;
        else return false;
        return Math.pow(x - cx, 2) + Math.pow(y - cy, 2) > Math.pow(r - 0.5, 2) + 0.5;
    }

    private void rrect(int x0, int y0, int rw, int rh, int[] c1, int[] c2, int alpha, int r, double noise) {
        for (int y = 0; y < rh; y++) {
            int[] c = mix(c1, c2, y / (double) Math.max(1, rh - 1));
            for (int x = 0; x < rw; x++) {
                if (cornerOut(x, y, rw, rh, r)) continue;
                int n = noise > 0 ? (int) Math.round((rnd.nextDouble() * 2 - 1) * noise) : 0;
                blend(x0 + x, y0 + y, c[0] + n, c[1] + n, c[2] + n, alpha);
            }
        }
    }

    private void border(int x0, int y0, int bw, int bh, int[] top, int[] bottom, int alpha, int r) {
        for (int y = 0; y < bh; y++) {
            int[] c = mix(top, bottom, y / (double) Math.max(1, bh - 1));
            for (int x = 0; x < bw; x++) {
                if (cornerOut(x, y, bw, bh, r)) continue;
                boolean edge = false;
                int[][] nb = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
                for (int[] p : nb) {
                    if (p[0] < 0 || p[1] < 0 || p[0] >= bw || p[1] >= bh || cornerOut(p[0], p[1], bw, bh, r)) { edge = true; break; }
                }
                if (edge) blend(x0 + x, y0 + y, c[0], c[1], c[2], alpha);
            }
        }
    }

    private void glow(double cx, double cy, double rx, double ry, int[] c, int strength) {
        for (int y = (int) (cy - ry); y <= (int) (cy + ry); y++) {
            for (int x = (int) (cx - rx); x <= (int) (cx + rx); x++) {
                double d = Math.hypot((x - cx) / rx, (y - cy) / ry);
                if (d < 1) blend(x, y, c[0], c[1], c[2], (int) (strength * Math.pow(1 - d, 1.6)));
            }
        }
    }

    private void diamond(int cx, int cy, int r, int[] c, int a) {
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) if (Math.abs(x - cx) + Math.abs(y - cy) <= r) blend(x, y, c[0], c[1], c[2], a);
        }
    }

    /** Иконка любого размера → квадрат size×size (ближайший пиксель), с мягкой тенью. */
    private void blitIcon(BufferedImage icon, int x0, int y0, int size) {
        int iw = icon.getWidth(), ih = icon.getHeight();
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int p = icon.getRGB(Math.min(iw - 1, x * iw / size), Math.min(ih - 1, y * ih / size));
                    int a = p >>> 24;
                    if (a == 0) continue;
                    if (pass == 0) blend(x0 + x + 1, y0 + y + 1, 0, 0, 0, a * 90 / 255);
                    else blend(x0 + x, y0 + y, (p >> 16) & 255, (p >> 8) & 255, p & 255, a);
                }
            }
        }
    }

    private void text(String s, int x, int y, int[] top, int[] bottom, int scale, boolean shadow) {
        PixelFont f = PixelFont.get();
        for (int pass = shadow ? 0 : 1; pass < 2; pass++) {
            int cx = x;
            for (char ch : s.toCharArray()) {
                boolean[][] g = f.glyph(ch);
                for (int ry = 0; ry < g.length; ry++) {
                    int[] col = mix(top, bottom, ry / 6.0);
                    for (int rx = 0; rx < g[ry].length; rx++) {
                        if (!g[ry][rx]) continue;
                        for (int dy = 0; dy < scale; dy++) {
                            for (int dx = 0; dx < scale; dx++) {
                                if (pass == 0) blend(cx + rx * scale + dx + scale, y + ry * scale + dy + scale, 10, 6, 18, 190);
                                else blend(cx + rx * scale + dx, y + ry * scale + dy, col[0], col[1], col[2], 255);
                            }
                        }
                    }
                }
                cx += (g[0].length + 1) * scale;
            }
        }
    }

    /** Ширина, которую займёт надпись кнопки (для предупреждения «не влезает»). */
    public static int buttonLabelRight(EdgeLayout.Element e) {
        int tx = 6;
        if (e.art().icon() != null) tx = 5 + Math.min(e.height() - 6, 8) * e.art().iconScale() + 4;
        return tx + PixelFont.get().width(e.art().label(), 1) + 3;
    }
}
