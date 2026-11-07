package ru.p3rso.pack.model;

/**
 * Символы-сдвиги для шрифта {@code <namespace>:shift} (провайдер space). Любой сдвиг собирается
 * из степеней двойки: −1…−1024 и +1…+1024.
 */
public final class Shifts {
    public static final int BITS = 11; // 1..1024
    public static final char NEG_BASE = '';
    public static final char POS_BASE = '';

    private Shifts() {}

    /** Строка символов, сдвигающая курсор на {@code px} пикселей (может быть отрицательным). */
    public static String of(int px) {
        if (px == 0) return "";
        StringBuilder sb = new StringBuilder();
        boolean neg = px < 0;
        int left = Math.abs(px);
        for (int bit = BITS - 1; bit >= 0 && left > 0; bit--) {
            int v = 1 << bit;
            while (left >= v) {
                sb.append((char) ((neg ? NEG_BASE : POS_BASE) + bit));
                left -= v;
            }
        }
        return sb.toString();
    }

    public static int advanceOf(char c) {
        if (c >= NEG_BASE && c < NEG_BASE + BITS) return -(1 << (c - NEG_BASE));
        if (c >= POS_BASE && c < POS_BASE + BITS) return 1 << (c - POS_BASE);
        return 0;
    }
}
