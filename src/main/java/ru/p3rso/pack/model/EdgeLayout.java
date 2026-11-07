package ru.p3rso.pack.model;

import ru.p3rso.pack.config.Source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Меню на диалоге 1.21.6+ (окно {@code type: edge}). Тело диалога — один кликабельный текст; каждый элемент
 * меню — глиф-картинка внутри текста, а на нижних строках под кликабельным элементом — кликабельные пробелы той же
 * ширины, чтобы он нажимался по всей высоте. Текст идёт строками по {@value #LINE_HEIGHT} px.
 *
 * <ul>
 *   <li>{@code centered} — все строки шириной {@link #width} (меньше экрана): клиент ставит такой текст по центру
 *       экрана — получается окно меню по центру;</li>
 *   <li>иначе строки шириной {@link #WIDE_LINE} (шире экрана) — клиент прижимает текст к левому краю экрана.</li>
 * </ul>
 * Координаты — пиксели меню, (0,0) — левый верхний угол первой строки. Строка 0 содержит фон, поэтому
 * кликабельные элементы начинаются не выше y = 9. Фон на {@value #BG_MARGIN} px шире текста со всех сторон:
 * он перекрывает рамку фокуса, которую клиент рисует вокруг текста после клика.
 */
public record EdgeLayout(int width, int height, boolean centered, Element background, List<Element> elements,
                         boolean closeButton, List<Tab> tabs, List<Element> extraArt, int titleShift, int contentScale) {
    public static final int LINE_HEIGHT = 9;
    /**
     * Ширина строки для меню у края экрана: ровно ширина текстовой области виджета (1024 − 2·4). Клиент
     * центрирует каждую строку внутри неё, поэтому строка во всю ширину начинается точно с левого края текста
     * (x = 12), а виджет шире экрана прижат к x = 8.
     */
    public static final int WIDE_LINE = 1016;
    /** Максимальная ширина одного глифа — шире картинки режутся на куски. */
    public static final int MAX_TILE = 240;
    public static final int BG_MARGIN = 6;

    public enum Kind { BUTTON, PANEL, HEADER, IMAGE, BACKGROUND }

    /** Параметры автоматически рисуемой картинки; null, если картинка своя (texture). */
    public record Art(String label, String subtitle, List<String> text, String icon, int[] top, int[] bottom,
                      boolean active, String pill, int iconScale) {}

    /**
     * Элемент. {@code glyph}/{@code texture} — базовые id; если {@code tiles() > 1}, реальные глифы — {@link #tileGlyph(int)}.
     * {@code tab} — вкладка, на которую переключает нажатие (или null).
     */
    public record Element(String key, Source source, Kind kind, int x, int y, int width, int height,
                          String glyph, String texture, Art art, String name, List<String> lore,
                          List<GuiDef.Action> actions, String tab) {
        public boolean clickable() { return !actions.isEmpty() || tab != null; }

        public int firstLine() { return Math.max(0, y) / LINE_HEIGHT; }

        public int lastLine() { return Math.max(0, y + height - 1) / LINE_HEIGHT; }

        public int tiles() { return (width + MAX_TILE - 1) / MAX_TILE; }

        public int tileWidth(int i) {
            int tw = (width + tiles() - 1) / tiles();
            return Math.min(tw, width - i * tw);
        }

        public int tileX(int i) { return i * ((width + tiles() - 1) / tiles()); }

        public String tileGlyph(int i) { return tiles() == 1 ? glyph : glyph + "_t" + i; }

        public String tileTexture(int i) { return tiles() == 1 ? texture : texture + "_t" + i; }
    }

    /**
     * Вкладка: кнопка слева, её подсвеченный вариант, элементы содержимого (панель и кнопки на ней)
     * и кнопки диалога внизу. Вкладка без содержимого просто выполняет actions.
     */
    public record Tab(String key, Source source, String activeGlyph, List<Element> content,
                      List<FooterButton> buttons, List<GuiDef.Action> actions, Element titlePanel) {
        public boolean hasContent() { return !content.isEmpty() || titlePanel != null; }
    }

    /** Кнопка диалога внизу по центру. */
    public record FooterButton(String label, String tooltip, int width, List<GuiDef.Action> actions) {}

    public boolean hasTabs() { return !tabs.isEmpty(); }

    /** Вкладки у края экрана с панелями в заголовке — их панели центрирует и увеличивает шейдер text.vsh. */
    public boolean hasTitlePanels() {
        for (Tab t : tabs) if (t.titlePanel() != null) return true;
        return false;
    }

    /** Порядковый номер такого меню среди всех (по id) — зашит в цвет-маркер панели (G = 10 + номер). */
    public static int markerIndex(java.util.Map<String, GuiDef> guis, String guiId) {
        int i = 0;
        for (GuiDef g : guis.values()) {
            if (!g.isEdge() || !g.edge().hasTitlePanels()) continue;
            if (g.id().equals(guiId)) return i;
            i++;
        }
        return -1;
    }

    public Tab tab(String key) {
        for (Tab t : tabs) if (t.key().equals(key)) return t;
        return null;
    }

    /** Первая вкладка с содержимым — открывается по умолчанию. */
    public Tab defaultTab() {
        for (Tab t : tabs) if (t.hasContent()) return t;
        return tabs.isEmpty() ? null : tabs.getFirst();
    }

    public int lineWidth() { return centered ? width : WIDE_LINE; }

    public int totalLines(List<Element> els) {
        int max = (height + LINE_HEIGHT - 1) / LINE_HEIGHT - 1;
        for (Element e : els) max = Math.max(max, e.lastLine());
        return max + 1;
    }

    /** Все элементы, которые видны при выбранной вкладке (или просто элементы холста). */
    public List<Element> visible(String tabKey) {
        List<Element> out = new ArrayList<>();
        if (background != null) out.add(background);
        out.addAll(elements);
        Tab t = tabKey == null ? null : tab(tabKey);
        if (t != null) out.addAll(t.content());
        return out;
    }

    // ------------------------------------------------------------------ строки

    public enum SegmentType { SHIFT, GLYPH, CLICK_SHIFT }

    /** Кусок строки: сдвиг, глиф или кликабельный сдвиг (продолжение элемента на нижних строках). */
    public record Segment(SegmentType type, int advance, String glyph, Element element) {}

    private record Piece(Element element, int x, int width, String glyph) {}

    /**
     * Раскладывает видимые элементы по строкам. {@code advance} — ширина глифа (из сборки пака);
     * {@code glyphOverride}: ключ элемента → другой глиф (подсвеченная вкладка).
     * Правила: кликабельные куски на одной строке не пересекаются; некликабельный элемент на нижних строках
     * ничего не занимает (поверх него можно ставить кнопки); каждая строка добивается до {@link #lineWidth()}.
     */
    public List<List<Segment>> rows(List<Element> els, ToIntFunction<String> advance, Map<String, String> glyphOverride) {
        List<List<Segment>> rows = new ArrayList<>();
        int total = totalLines(els);
        for (int line = 0; line < total; line++) {
            List<Piece> pieces = new ArrayList<>();
            for (Element e : els) {
                if (line < e.firstLine() || line > e.lastLine()) continue;
                boolean first = line == e.firstLine();
                if (!first && !e.clickable()) continue;
                for (int i = 0; i < e.tiles(); i++) {
                    String g = e.tiles() == 1 ? glyphOverride.getOrDefault(e.key(), e.glyph()) : e.tileGlyph(i);
                    pieces.add(new Piece(e, e.x() + e.tileX(i), e.tileWidth(i), first ? g : null));
                }
            }
            pieces.sort(Comparator.comparingInt(Piece::x));
            List<Segment> row = new ArrayList<>();
            int cursor = 0;
            for (Piece p : pieces) {
                if (p.x() != cursor) row.add(new Segment(SegmentType.SHIFT, p.x() - cursor, null, null));
                int a = p.glyph() != null ? advance.applyAsInt(p.glyph()) : p.width() + 1;
                if (p.glyph() != null) row.add(new Segment(SegmentType.GLYPH, a, p.glyph(), p.element()));
                else row.add(new Segment(SegmentType.CLICK_SHIFT, a, null, p.element()));
                cursor = p.x() + a;
            }
            row.add(new Segment(SegmentType.SHIFT, lineWidth() - cursor, null, null));
            rows.add(row);
        }
        return rows;
    }

    public List<List<Segment>> rows(ToIntFunction<String> advance) {
        return rows(visible(defaultTab() == null ? null : defaultTab().key()), advance, Map.of());
    }

    /** Ascent глифа элемента: верх картинки ровно на y внутри своей строки. */
    public static int ascentFor(int y) { return 7 - Math.floorMod(y, LINE_HEIGHT); }

    public static Map<String, int[][]> STYLES = Map.of(
            "default", new int[][]{{70, 56, 128}, {32, 24, 62}},
            "primary", new int[][]{{120, 92, 214}, {64, 44, 140}},
            "danger", new int[][]{{150, 40, 52}, {78, 18, 28}},
            "success", new int[][]{{56, 180, 132}, {14, 68, 56}},
            "gold", new int[][]{{236, 186, 74}, {132, 76, 16}},
            "blue", new int[][]{{60, 130, 220}, {20, 40, 104}},
            "dark", new int[][]{{48, 38, 86}, {24, 18, 46}});
}
