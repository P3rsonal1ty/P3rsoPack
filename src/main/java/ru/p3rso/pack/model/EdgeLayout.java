package ru.p3rso.pack.model;

import ru.p3rso.pack.config.Source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Меню у левого края экрана (окно {@code type: edge}). Показывается как диалог 1.21.6+, тело которого —
 * один кликабельный текст шириной {@link #LINE_WIDTH} px. Элементы шире экрана клиент прижимает к левому
 * краю, поэтому холст меню оказывается у края экрана. Каждый элемент — глиф-картинка с click-событием;
 * на следующих строках под ним — кликабельные пробелы той же ширины, чтобы элемент нажимался по всей высоте.
 *
 * <p>Координаты элементов — пиксели холста (0,0 — левый верхний угол меню). По вертикали текст идёт строками
 * по 9 px; строка 0 отдана под фон, холст начинается со строки 1.</p>
 */
public record EdgeLayout(int width, int height, String backgroundGlyph, List<Element> elements, boolean closeButton,
                         List<Tab> tabs, List<Element> extraArt) {
    public static final int LINE_HEIGHT = 9;
    /** Ширина каждой строки — больше ширины экрана в пикселях интерфейса при масштабе ≥ 2. */
    public static final int LINE_WIDTH = 980;

    public enum Kind { BUTTON, PANEL, HEADER, IMAGE }

    /** Параметры автоматически рисуемой картинки; null, если картинка своя (texture). */
    public record Art(String label, String subtitle, List<String> text, String icon, int[] top, int[] bottom,
                      boolean active, String pill, int iconScale) {}

    public record Element(String key, Source source, Kind kind, int x, int y, int width, int height,
                          String glyph, String texture, Art art, String name, List<String> lore,
                          List<GuiDef.Action> actions, String tab) {
        public boolean clickable() { return !actions.isEmpty() || tab != null; }

        /** Первая строка текста (с учётом строки фона). */
        public int firstLine() { return 1 + y / LINE_HEIGHT; }

        public int lastLine() { return 1 + (y + height - 1) / LINE_HEIGHT; }
    }

    /**
     * Вкладка: кнопка слева ({@code tab}-элемент), её подсвеченный вариант, панель по центру экрана
     * (глиф в заголовке диалога) и кнопки диалога внизу. Вкладка без панели просто выполняет actions.
     */
    public record Tab(String key, Source source, String activeGlyph, String contentGlyph,
                      List<FooterButton> buttons, List<GuiDef.Action> actions) {}

    /** Кнопка диалога внизу по центру. */
    public record FooterButton(String label, String tooltip, int width, List<GuiDef.Action> actions) {}

    public boolean hasTabs() { return !tabs.isEmpty(); }

    public Tab tab(String key) {
        for (Tab t : tabs) if (t.key().equals(key)) return t;
        return null;
    }

    /** Первая вкладка с панелью — открывается по умолчанию. */
    public Tab defaultTab() {
        for (Tab t : tabs) if (t.contentGlyph() != null) return t;
        return tabs.isEmpty() ? null : tabs.getFirst();
    }

    public int canvasLines() { return (height + LINE_HEIGHT - 1) / LINE_HEIGHT; }

    public int totalLines() {
        int max = canvasLines();
        for (Element e : elements) max = Math.max(max, e.lastLine());
        return max + 1;
    }

    // ------------------------------------------------------------------ строки

    public enum SegmentType { SHIFT, GLYPH, CLICK_SHIFT }

    /** Кусок строки: сдвиг, глиф или кликабельный сдвиг (продолжение элемента на нижних строках). */
    public record Segment(SegmentType type, int advance, String glyph, Element element) {}

    /**
     * Раскладывает меню по строкам. {@code advance} — ширина глифа в пикселях (из сборки пака).
     * Каждая строка добивается сдвигом ровно до {@link #LINE_WIDTH}, поэтому все строки начинаются с одного x.
     */
    public List<List<Segment>> rows(ToIntFunction<String> advance) { return rows(advance, Map.of()); }

    /** {@code glyphOverride}: ключ элемента → другой глиф (подсвеченная активная вкладка). */
    public List<List<Segment>> rows(ToIntFunction<String> advance, Map<String, String> glyphOverride) {
        List<List<Segment>> rows = new ArrayList<>();
        int total = totalLines();
        List<Element> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingInt(Element::x));
        for (int line = 0; line < total; line++) {
            List<Segment> row = new ArrayList<>();
            int cursor = 0;
            if (line == 0 && backgroundGlyph != null) {
                int a = advance.applyAsInt(backgroundGlyph);
                row.add(new Segment(SegmentType.GLYPH, a, backgroundGlyph, null));
                row.add(new Segment(SegmentType.SHIFT, -a, null, null));
            }
            for (Element e : sorted) {
                if (line < e.firstLine() || line > e.lastLine()) continue;
                String glyph = glyphOverride.getOrDefault(e.key(), e.glyph());
                int a = advance.applyAsInt(glyph);
                if (e.x() > cursor) row.add(new Segment(SegmentType.SHIFT, e.x() - cursor, null, null));
                if (line == e.firstLine()) row.add(new Segment(SegmentType.GLYPH, a, glyph, e));
                else if (e.clickable()) row.add(new Segment(SegmentType.CLICK_SHIFT, a, null, e));
                else row.add(new Segment(SegmentType.SHIFT, a, null, null));
                cursor = Math.max(cursor, e.x()) + a;
            }
            row.add(new Segment(SegmentType.SHIFT, LINE_WIDTH - cursor, null, null));
            rows.add(row);
        }
        return rows;
    }

    /** Ascent глифа элемента: верх картинки ровно на y внутри своей строки. */
    public static int ascentFor(int y) { return 7 - (y % LINE_HEIGHT); }

    public static Map<String, int[][]> STYLES = Map.of(
            "default", new int[][]{{70, 56, 128}, {32, 24, 62}},
            "primary", new int[][]{{120, 92, 214}, {64, 44, 140}},
            "danger", new int[][]{{150, 40, 52}, {78, 18, 28}},
            "success", new int[][]{{56, 180, 132}, {14, 68, 56}},
            "gold", new int[][]{{236, 186, 74}, {132, 76, 16}},
            "blue", new int[][]{{60, 130, 220}, {20, 40, 104}},
            "dark", new int[][]{{48, 38, 86}, {24, 18, 46}});
}
