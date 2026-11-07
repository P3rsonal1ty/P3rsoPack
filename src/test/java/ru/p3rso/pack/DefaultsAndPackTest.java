package ru.p3rso.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.config.YNode;
import ru.p3rso.pack.model.BlockVariations;
import ru.p3rso.pack.model.DefinitionLoader;
import ru.p3rso.pack.model.Definitions;
import ru.p3rso.pack.model.Environment;
import ru.p3rso.pack.model.GlyphDef;
import ru.p3rso.pack.model.ItemDef;
import ru.p3rso.pack.model.NoteBlockStates;
import ru.p3rso.pack.model.Settings;
import ru.p3rso.pack.pack.PackGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class DefaultsAndPackTest {
    @TempDir
    Path dir;
    Settings settings;
    Problems problems;

    @BeforeEach
    void setUp() throws Exception {
        TestFiles.copyDefaults(dir);
        problems = new Problems();
        try (Reader r = Files.newBufferedReader(dir.resolve("config.yml"))) {
            settings = Settings.parse(YNode.parse("config.yml", r), problems);
        }
    }

    private PackGenerator.Result build(Definitions defs) throws Exception {
        Map<String, Integer> vars = BlockVariations.assign(defs.blocks(), Map.of(), problems);
        return new PackGenerator(settings, dir.resolve("pack"), problems).generate(defs, vars);
    }

    private Definitions load() {
        return new DefinitionLoader(dir, settings.namespace(), Environment.permissive(), problems).load();
    }

    private static JsonObject json(PackGenerator.Result r, String path) {
        byte[] b = r.files().get(path);
        assertNotNull(b, "в паке нет " + path);
        return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void defaultsLoadAndBuildWithoutProblems() throws Exception {
        Definitions defs = load();
        PackGenerator.Result r = build(defs);
        assertEquals(java.util.List.of(), problems.all(), "примеры должны грузиться без ошибок и предупреждений");
        assertTrue(defs.items().containsKey("p3rso:ruby"));
        assertTrue(defs.items().containsKey("p3rso:ruby_ore"), "у блока есть авто-предмет");
        assertTrue(defs.items().containsKey("p3rso:chair"), "у мебели есть авто-предмет");
        assertEquals(ItemDef.Placement.Type.BLOCK, defs.items().get("p3rso:ruby_ore").placement().type());
        assertEquals(6, defs.guis().size());
        assertEquals("cmenu", defs.guis().get("p3rso:main_menu").command());
        assertEquals("fmenu", defs.guis().get("p3rso:hub_menu").command());
        assertEquals("menu", defs.guis().get("p3rso:edge_menu").command());
        assertEquals("p3rsopack.admin", defs.guis().get("p3rso:collection").permission());
        // якоря YAML: вся карточка кликается как иконка
        var main = defs.guis().get("p3rso:main_menu");
        var icon = main.items().stream().filter(i -> i.key().equals("shop")).findFirst().orElseThrow();
        var area = main.items().stream().filter(i -> i.key().equals("shop_area")).findFirst().orElseThrow();
        assertEquals(icon.actions(), area.actions());
        assertEquals(java.util.List.of(12, 13, 14, 21, 23, 30, 31, 32), area.slots());
        assertEquals(40, r.sha1().length());
    }

    @Test
    void fullScreenMenuButtonsMapToCells() throws Exception {
        Definitions defs = load();
        var hub = defs.guis().get("p3rso:hub_menu");
        assertTrue(hub.hidePlayerInventory());
        java.util.function.Function<String, java.util.List<Integer>> slots = k -> hub.items().stream()
                .filter(i -> i.key().equals(k)).findFirst().orElseThrow().slots();
        assertEquals(java.util.List.of(9, 10, 11, 12), slots.apply("rpg"), "боковая панель: 4 ячейки ряда 1");
        assertEquals(java.util.List.of(31, 32, 33, 34, 35, 40, 41, 42, 43, 44, 49, 50, 51, 52, 53), slots.apply("news"));
        assertEquals(java.util.List.of(58, 59, 60, 61, 62, 67, 68, 69, 70, 71, 76, 77, 78, 79, 80, 85, 86, 87, 88, 89), slots.apply("reward"),
                "награда занимает инвентарь игрока и хотбар справа");
        assertEquals(java.util.List.of(81, 82, 83, 84), slots.apply("close"), "хотбар: виртуальные слоты 81..89");
        var close = hub.items().stream().filter(i -> i.key().equals("close")).findFirst().orElseThrow();
        assertEquals("p3rso:p3rsopack_invisible", close.itemModel());
        assertEquals("p3rso:menu", close.tooltipStyle());

        PackGenerator.Result r = build(defs);
        assertEquals(java.util.List.of(), problems.all());
        assertEquals("minecraft:empty", json(r, "assets/p3rso/items/p3rsopack_invisible.json").getAsJsonObject("model").get("type").getAsString());
        assertTrue(r.files().containsKey("assets/minecraft/textures/gui/sprites/container/slot_highlight_back.png"), "slot-highlight: hidden");
        assertEquals("", json(r, "assets/minecraft/lang/ru_ru.json").get("container.inventory").getAsString());
        assertEquals("", json(r, "assets/minecraft/lang/en_us.json").get("container.inventory").getAsString());
        assertEquals(241, r.resolved().glyphs().get("p3rso:hub_bg").advance());
    }

    private int defsGlyphCount() { return load().glyphs().size(); }

    @Test
    void edgeMenuTabsLineUp() throws Exception {
        Definitions defs = load();
        PackGenerator.Result r = build(defs);
        assertEquals(java.util.List.of(), problems.all());
        var edge = r.resolved().guis().get("p3rso:edge_menu").edge();
        assertTrue(edge.hasTabs());
        assertTrue(edge.centered(), "окно со вкладками — по центру экрана");
        assertEquals("home", edge.defaultTab().key());
        String dir = "assets/p3rso/textures/gui/edge/edge_menu/";
        assertTrue(r.files().containsKey(dir + "tab_home.png"));
        assertTrue(r.files().containsKey(dir + "tab_home_active.png"));
        assertTrue(r.files().containsKey(dir + "content_home_t0.png"), "панель 324 px режется на куски ≤ 240");
        assertTrue(r.files().containsKey(dir + "content_home_t1.png"));
        assertTrue(r.files().containsKey(dir + "content_home_b0.png"), "кнопка «Играть» на панели");
        assertTrue(r.files().containsKey(dir + "background_t0.png"));
        GlyphDef bg = r.resolved().glyphs().get("p3rso:edge/edge_menu/background_t0");
        assertEquals(13, bg.ascent(), "фон на 6 px выше первой строки — перекрывает рамку фокуса");
        var visible = edge.visible("home");
        var rows = edge.rows(visible, id -> r.resolved().glyphs().get(id).advance(),
                java.util.Map.of("tab.home", edge.tab("home").activeGlyph()));
        for (var row : rows) {
            assertEquals(460, row.stream().mapToInt(ru.p3rso.pack.model.EdgeLayout.Segment::advance).sum(),
                    "все строки шириной окна — клиент центрирует текст уже экрана");
        }
        // вкладка «Главная» y=9: строка 1 — подсвеченная картинка, строка 2 — кликабельный пробел
        var g = rows.get(1).stream().filter(s -> s.element() != null && s.element().key().equals("tab.home")).findFirst().orElseThrow();
        assertEquals("p3rso:edge/edge_menu/tab/home_active", g.glyph());
        var c = rows.get(2).stream().filter(s -> s.element() != null && s.element().key().equals("tab.home")).findFirst().orElseThrow();
        assertEquals(ru.p3rso.pack.model.EdgeLayout.SegmentType.CLICK_SHIFT, c.type());
        // кнопка на панели кликабельна и стоит на строках ниже первой строки панели
        var btn = edge.tab("home").content().get(1);
        assertTrue(btn.clickable());
        var btnRow = rows.get(btn.firstLine()).stream().filter(s -> s.element() == btn).findFirst().orElseThrow();
        assertEquals(ru.p3rso.pack.model.EdgeLayout.SegmentType.GLYPH, btnRow.type());
        // строка кнопки: панель (некликабельная) на ней не занимает места — клик попадает в кнопку
        assertTrue(rows.get(btn.firstLine()).stream().noneMatch(s -> s.element() != null && s.element().key().equals("content.home")));
        assertTrue(edge.tab("close").actions().stream().anyMatch(a -> a.type() == ru.p3rso.pack.model.GuiDef.Action.Type.CLOSE));
    }

    @Test
    void geometryMatchesVanillaContainer() {
        // 6 рядов: окно 222 px, инвентарь игрока с y=139, хотбар с y=197
        var cells = ru.p3rso.pack.model.GuiGeometry.cells(6, true);
        assertEquals(90, cells.size());
        assertEquals(139, cells.get(54).y());
        assertEquals(197, cells.get(81).y());
        assertEquals(0, ru.p3rso.pack.model.GuiGeometry.playerSlot(6, 81));
        assertEquals(9, ru.p3rso.pack.model.GuiGeometry.playerSlot(6, 54));
        assertEquals(81, ru.p3rso.pack.model.GuiGeometry.virtualSlot(6, 0));
        // 3 ряда: окно 168 px → инвентарь с y=85
        assertEquals(85, ru.p3rso.pack.model.GuiGeometry.cells(3, true).get(27).y());
    }

    @Test
    void packMetaUsesFormatOf26_3() throws Exception {
        PackGenerator.Result r = build(load());
        JsonObject pack = json(r, "pack.mcmeta").getAsJsonObject("pack");
        assertEquals(97, pack.get("min_format").getAsInt());
        assertEquals(97, pack.get("max_format").getAsInt());
        assertTrue(r.files().containsKey("pack.png"));
    }

    @Test
    void itemDefinitionsAndModels() throws Exception {
        PackGenerator.Result r = build(load());
        JsonObject def = json(r, "assets/p3rso/items/ruby.json").getAsJsonObject("model");
        assertEquals("minecraft:model", def.get("type").getAsString());
        assertEquals("p3rso:item/ruby", def.get("model").getAsString());
        JsonObject model = json(r, "assets/p3rso/models/item/ruby.json");
        assertEquals("minecraft:item/generated", model.get("parent").getAsString());
        assertEquals("p3rso:item/ruby", model.getAsJsonObject("textures").get("layer0").getAsString());
        assertEquals("minecraft:item/handheld", json(r, "assets/p3rso/models/item/ruby_sword.json").get("parent").getAsString());
        // предмет с готовой моделью — ничего не генерируется
        assertFalse(r.files().containsKey("assets/p3rso/items/shiny_coin.json"));
        // мебель использует модель из pack/
        assertEquals("p3rso:furniture/chair", json(r, "assets/p3rso/items/chair.json").getAsJsonObject("model").get("model").getAsString());
    }

    @Test
    void equipmentAssetGenerated() throws Exception {
        PackGenerator.Result r = build(load());
        JsonObject eq = json(r, "assets/p3rso/equipment/ruby.json").getAsJsonObject("layers");
        assertEquals("p3rso:ruby", eq.getAsJsonArray("humanoid").get(0).getAsJsonObject().get("texture").getAsString());
    }

    @Test
    void noteBlockStatesCoverEveryState() throws Exception {
        Definitions defs = load();
        Map<String, Integer> vars = BlockVariations.assign(defs.blocks(), Map.of(), problems);
        PackGenerator.Result r = new PackGenerator(settings, dir.resolve("pack"), problems).generate(defs, vars);
        JsonObject variants = json(r, "assets/minecraft/blockstates/note_block.json").getAsJsonObject("variants");
        assertEquals(NoteBlockStates.INSTRUMENTS.size() * NoteBlockStates.NOTES * 2, variants.size());
        int v = vars.get("p3rso:ruby_ore");
        String key = NoteBlockStates.variantKey(NoteBlockStates.instrument(v), NoteBlockStates.note(v), false);
        assertEquals("p3rso:block/ruby_ore", variants.getAsJsonObject(key).get("model").getAsString());
        assertEquals("minecraft:block/note_block", variants.getAsJsonObject("instrument=harp,note=0,powered=false").get("model").getAsString());
        JsonObject blockModel = json(r, "assets/p3rso/models/block/ruby_ore.json");
        assertEquals("minecraft:block/cube_all", blockModel.get("parent").getAsString());
        assertEquals("p3rso:block/ruby_ore", blockModel.getAsJsonObject("textures").get("particle").getAsString());
    }

    @Test
    void fontsAndGlyphMetrics() throws Exception {
        PackGenerator.Result r = build(load());
        GlyphDef menu = r.resolved().glyphs().get("p3rso:menu");
        assertEquals(177, menu.advance(), "176 px непрозрачной ширины + 1");
        GlyphDef icon = r.resolved().glyphs().get("p3rso:ruby_icon");
        assertEquals(9, icon.advance(), "16 px при масштабе 8/16 = 8, +1");
        JsonObject font = json(r, "assets/p3rso/font/glyphs.json");
        assertEquals(defsGlyphCount(), font.getAsJsonArray("providers").size());
        JsonObject first = font.getAsJsonArray("providers").get(0).getAsJsonObject();
        assertEquals("bitmap", first.get("type").getAsString());
        JsonObject shift = json(r, "assets/p3rso/font/shift.json").getAsJsonArray("providers").get(0).getAsJsonObject();
        assertEquals("space", shift.get("type").getAsString());
        assertEquals(22, shift.getAsJsonObject("advances").size());
    }

    @Test
    void nonStandardTextureFolderAddedToAtlas() throws Exception {
        PackGenerator.Result r = build(load());
        String atlas = new String(r.files().get("assets/minecraft/ppases/blocks.json"), StandardCharsets.UTF_8);
        assertTrue(atlas.contains("p3rso:furniture/wood"), atlas);
        assertFalse(atlas.contains("p3rso:item/ruby"), "item/ уже в атласе по умолчанию");
    }

    @Test
    void zipIsDeterministic() throws Exception {
        PackGenerator.Result a = build(load());
        problems = new Problems();
        PackGenerator.Result b = build(load());
        assertEquals(a.sha1(), b.sha1(), "одинаковые входные файлы → одинаковый хеш на всех серверах");
        Map<String, Long> times = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(a.zip()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) times.put(e.getName(), e.getTime());
        }
        assertEquals(a.files().size(), times.size());
        assertEquals(1, times.values().stream().distinct().count());
    }

    @Test
    void changingTextureChangesHash() throws Exception {
        PackGenerator.Result a = build(load());
        Path png = dir.resolve("pack/assets/p3rso/textures/item/ruby.png");
        byte[] data = Files.readAllBytes(png);
        Files.write(dir.resolve("pack/assets/p3rso/textures/item/extra.png"), data);
        problems = new Problems();
        PackGenerator.Result b = build(load());
        assertNotEquals(a.sha1(), b.sha1());
    }
}
