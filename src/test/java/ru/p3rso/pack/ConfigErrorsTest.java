package ru.p3rso.pack;

import ru.p3rso.pack.config.ConfigException;
import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.config.YNode;
import ru.p3rso.pack.model.BlockVariations;
import ru.p3rso.pack.model.DefinitionLoader;
import ru.p3rso.pack.model.Definitions;
import ru.p3rso.pack.model.Environment;
import ru.p3rso.pack.model.GuiDef;
import ru.p3rso.pack.model.Settings;
import ru.p3rso.pack.pack.PackGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigErrorsTest {
    @TempDir
    Path dir;

    /** Окружение, знающее несколько материалов — для проверки подсказок. */
    static final Environment ENV = new Environment() {
        final List<String> mats = List.of("PAPER", "DIAMOND_SWORD", "DIAMOND", "STONE");
        public boolean isItemMaterial(String n) { return mats.contains(n); }
        public Collection<String> itemMaterials() { return mats; }
        public boolean isAttribute(String k) { return k.equals("minecraft:attack_damage"); }
        public Collection<String> attributes() { return List.of("minecraft:attack_damage"); }
        public boolean isEnchantment(String k) { return true; }
        public Collection<String> enchantments() { return List.of(); }
    };

    private Problems load(String folder, String file, String yaml) throws Exception {
        TestFiles.write(dir, folder + "/" + file, yaml);
        Problems p = new Problems();
        new DefinitionLoader(dir, "p3rso", ENV, p).load();
        return p;
    }

    private static Problems.Problem only(Problems p) {
        assertEquals(1, p.all().size(), () -> p.all().toString());
        return p.all().getFirst();
    }

    @Test
    void unknownMaterialPointsToLineAndSuggests() throws Exception {
        Problems p = load("items", "a.yml", """
                ok:
                  texture: item/x
                bad:
                  texture: item/x
                  material: DIAMOND_SWORDD
                """);
        Problems.Problem pr = only(p);
        assertEquals(Problems.Level.ERROR, pr.level());
        assertEquals("items/a.yml", pr.file());
        assertEquals(5, pr.line());
        assertTrue(pr.message().contains("DIAMOND_SWORD'?"), pr.message());
    }

    @Test
    void yamlSyntaxErrorHasLine() throws Exception {
        Problems p = load("items", "b.yml", "a:\n  texture: item/x\n   name: oops\n");
        Problems.Problem pr = only(p);
        assertEquals(3, pr.line());
        assertTrue(pr.message().contains("синтаксиса"), pr.message());
    }

    @Test
    void typoInKeyIsWarned() throws Exception {
        Problems p = load("items", "c.yml", "a:\n  texture: item/x\n  lroe: [x]\n");
        Problems.Problem pr = only(p);
        assertEquals(Problems.Level.WARNING, pr.level());
        assertEquals(3, pr.line());
        assertTrue(pr.message().contains("'lore'"), pr.message());
    }

    @Test
    void unknownAttribute() throws Exception {
        Problems p = load("items", "d.yml", """
                a:
                  texture: item/x
                  attributes:
                    - attribute: attack_damag
                      amount: 3
                """);
        Problems.Problem pr = only(p);
        assertEquals(4, pr.line());
        assertTrue(pr.message().contains("minecraft:attack_damage"), pr.message());
    }

    @Test
    void duplicateIdAcrossFolders() throws Exception {
        TestFiles.write(dir, "items/a.yml", "ruby:\n  texture: item/x\n");
        Problems p = load("blocks", "b.yml", "ruby:\n  texture: block/x\n");
        Problems.Problem pr = only(p);
        assertTrue(pr.message().contains("уже определён в items/a.yml:1"), pr.message());
    }

    @Test
    void guiReferencesAreChecked() throws Exception {
        TestFiles.write(dir, "items/a.yml", "ruby:\n  texture: item/x\n");
        Problems p = load("guis", "g.yml", """
                main:
                  rows: 3
                  background: missing_bg
                  items:
                    a:
                      slot: 30
                      item: ruby
                    b:
                      slot: 1
                      item: rubyy
                      actions:
                        - "open: nowhere"
                        - "teleport: spawn"
                """);
        List<String> msgs = p.all().stream().map(Problems.Problem::describe).toList();
        assertEquals(3, msgs.size(), msgs::toString);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("слот 30 вне окна")), msgs::toString);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("неизвестное действие 'teleport'")), msgs::toString);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("missing_bg")), msgs::toString);
        // item rubyy: ошибка в действии уже выбросила элемент, поэтому про 'rubyy' отдельной ошибки нет
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("guis/g.yml:13")), msgs::toString);
    }

    @Test
    void guiSlotsAndActionsParse() throws Exception {
        TestFiles.write(dir, "guis/g.yml", """
                main:
                  items:
                    a:
                      slots: [1, 3-5]
                      material: STONE
                      actions:
                        - "command: /spawn"
                        - close
                        - {message: "<red>hi"}
                """);
        Problems p = new Problems();
        Definitions d = new DefinitionLoader(dir, "p3rso", ENV, p).load();
        assertFalse(p.hasErrors(), p.all()::toString);
        GuiDef.GuiItem it = d.guis().get("p3rso:main").items().getFirst();
        assertEquals(List.of(1, 3, 4, 5), it.slots());
        List<GuiDef.Action> acts = it.actions().get("any");
        assertEquals(GuiDef.Action.Type.COMMAND, acts.get(0).type());
        assertEquals("spawn", acts.get(0).value());
        assertEquals(GuiDef.Action.Type.CLOSE, acts.get(1).type());
        assertEquals("<red>hi", acts.get(2).value());
    }

    @Test
    void missingTextureReportedByGenerator() throws Exception {
        TestFiles.write(dir, "items/a.yml", "gem:\n  texture: item/nope\n");
        Problems p = new Problems();
        Definitions d = new DefinitionLoader(dir, "p3rso", ENV, p).load();
        new PackGenerator(Settings.defaults(), dir.resolve("pack"), p).generate(d, Map.of());
        Problems.Problem pr = only(p);
        assertEquals("items/a.yml", pr.file());
        assertTrue(pr.message().contains("pack/assets/p3rso/textures/item/nope.png"), pr.message());
    }

    @Test
    void glyphAscentAboveHeightRejected() throws Exception {
        Problems p = load("glyphs", "g.yml", "x:\n  texture: gui/x\n  height: 8\n  ascent: 10\n");
        assertTrue(only(p).message().contains("ascent"));
    }

    @Test
    void uppercaseIdRejected() {
        ConfigException e = assertThrows(ConfigException.class, () ->
                ru.p3rso.pack.model.Ids.key("p3rso", "Ruby", YNode.parse("t.yml", new StringReader("a: 1"))));
        assertTrue(e.getMessage().contains("строчные"));
    }

    @Test
    void settingsValidation() {
        Problems p = new Problems();
        ConfigException e = assertThrows(ConfigException.class, () -> Settings.parse(YNode.parse("config.yml",
                new StringReader("pack:\n  hosting:\n    mode: cdn\n")), p));
        assertEquals(3, e.line());
        assertTrue(e.getMessage().contains("self, external, none"));
    }

    @Test
    void variationsArePersistentAndConflictsReported() throws Exception {
        TestFiles.write(dir, "blocks/b.yml", """
                a:
                  texture: block/a
                b:
                  texture: block/b
                c:
                  texture: block/c
                  variation: 7
                d:
                  texture: block/d
                  variation: 7
                """);
        Problems p = new Problems();
        Definitions d = new DefinitionLoader(dir, "p3rso", ENV, p).load();
        Map<String, Integer> v = BlockVariations.assign(d.blocks(), Map.of("p3rso:b", 1, "p3rso:old", 2), p);
        assertEquals(1, v.get("p3rso:b"), "сохранённая вариация не меняется");
        assertEquals(3, v.get("p3rso:a"), "новый блок не занимает вариацию удалённого блока");
        assertEquals(7, v.get("p3rso:c"));
        assertFalse(v.containsKey("p3rso:d"));
        assertTrue(p.all().stream().anyMatch(x -> x.message().contains("variation 7 уже занята")));
    }
}
