package ru.p3rso.pack.pack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Пиксельный шрифт 5×7 (заглавные кириллица/латиница, цифры, знаки) для надписей на сгенерированных картинках. */
public final class PixelFont {
    public static final int HEIGHT = 7;
    private static final PixelFont INSTANCE = load();
    private final Map<Character, boolean[][]> glyphs;

    private PixelFont(Map<Character, boolean[][]> glyphs) { this.glyphs = glyphs; }

    public static PixelFont get() { return INSTANCE; }

    private static PixelFont load() {
        Map<Character, boolean[][]> map = new HashMap<>();
        try (InputStream in = PixelFont.class.getResourceAsStream("pixelfont.txt")) {
            if (in == null) throw new IllegalStateException("pixelfont.txt не найден");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            Character current = null;
            List<String> rows = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") && current == null) continue;
                if (line.length() == 3 && line.charAt(0) == '[' && line.charAt(2) == ']') {
                    if (current != null) map.put(current, toBits(rows));
                    current = line.charAt(1);
                    rows = new ArrayList<>();
                } else if (current != null && !line.isEmpty()) {
                    rows.add(line);
                }
            }
            if (current != null) map.put(current, toBits(rows));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new PixelFont(map);
    }

    private static boolean[][] toBits(List<String> rows) {
        boolean[][] b = new boolean[rows.size()][];
        for (int y = 0; y < rows.size(); y++) {
            String s = rows.get(y);
            b[y] = new boolean[s.length()];
            for (int x = 0; x < s.length(); x++) b[y][x] = s.charAt(x) == '#';
        }
        return b;
    }

    /** Символ шрифта (строчные → заглавные, неизвестные → «?»). */
    boolean[][] glyph(char c) {
        char u = Character.toUpperCase(c);
        if (u == 'Ё') u = 'Ё';
        boolean[][] g = glyphs.get(u);
        if (g == null) g = glyphs.get(String.valueOf(c).toUpperCase(Locale.ROOT).charAt(0));
        return g != null ? g : glyphs.get('?');
    }

    public boolean supports(char c) { return glyphs.containsKey(Character.toUpperCase(c)); }

    public int width(String s, int scale) {
        int w = 0;
        for (char c : s.toCharArray()) w += glyph(c)[0].length + 1;
        return Math.max(0, w - 1) * scale;
    }
}
