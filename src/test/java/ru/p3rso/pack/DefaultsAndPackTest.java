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
        assertEquals(2, defs.guis().size());
        assertEquals(40, r.sha1().length());
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
        assertEquals(3, font.getAsJsonArray("providers").size());
        JsonObject first = font.getAsJsonArray("providers").get(1).getAsJsonObject();
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
