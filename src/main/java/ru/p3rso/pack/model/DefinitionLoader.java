package ru.p3rso.pack.model;

import ru.p3rso.pack.config.ConfigException;
import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.config.Source;
import ru.p3rso.pack.config.Suggest;
import ru.p3rso.pack.config.YNode;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * Читает папки items/, glyphs/, guis/, blocks/, furniture/ и превращает YAML в {@link Definitions}.
 * Не зависит от Bukkit: всё серверное проверяется через {@link Environment}. Ошибки не бросаются наружу,
 * а копятся в {@link Problems} — битое определение пропускается, остальные загружаются.
 */
public final class DefinitionLoader {
    public static final List<String> FOLDERS = List.of("items", "glyphs", "guis", "blocks", "furniture");

    static final Set<String> LOOK_KEYS = Set.of("texture", "textures", "parent", "handheld", "model", "item-model");
    static final Set<String> ITEM_KEYS = union(LOOK_KEYS, Set.of("material", "name", "lore", "max-stack-size",
            "max-damage", "glint", "unbreakable", "hide-tooltip", "attributes", "enchantments", "equippable"));
    static final Set<String> BLOCK_KEYS = union(LOOK_KEYS, Set.of("material", "name", "lore", "variation", "hardness",
            "tool", "requires-tool", "drops", "silk-touch", "exp", "sounds"));
    static final Set<String> FURNITURE_KEYS = union(LOOK_KEYS, Set.of("material", "name", "lore", "display", "hitbox",
            "seats", "seat", "rotation-step", "drop", "sounds"));
    static final Set<String> GLYPH_KEYS = Set.of("texture", "height", "ascent", "char");
    static final Set<String> GUI_KEYS = Set.of("title", "rows", "background", "background-offset", "title-offset", "items", "open-actions");
    static final Set<String> GUI_ITEM_KEYS = Set.of("slot", "slots", "item", "material", "amount", "name", "lore", "item-model",
            "actions", "left-actions", "right-actions", "shift-left-actions", "shift-right-actions", "middle-actions", "drop-actions");

    static final List<String> SLOTS = List.of("mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor", "body", "saddle", "any");
    static final List<String> EQUIP_SLOTS = List.of("head", "chest", "legs", "feet", "body", "saddle", "mainhand", "offhand");
    static final Map<String, String> OPERATIONS = Map.of(
            "add_number", "add_number", "add_value", "add_number",
            "add_scalar", "add_scalar", "add_multiplied_base", "add_scalar",
            "multiply_scalar_1", "multiply_scalar_1", "add_multiplied_total", "multiply_scalar_1");
    public static final List<String> TOOLS = List.of("none", "pickaxe", "axe", "shovel", "hoe", "sword", "shears");
    static final List<String> DISPLAY_TRANSFORMS = List.of("none", "thirdperson_lefthand", "thirdperson_righthand",
            "firstperson_lefthand", "firstperson_righthand", "head", "gui", "ground", "fixed");

    private final Path root;
    private final String ns;
    private final Environment env;
    private final Problems problems;

    private final Map<String, ItemDef> items = new LinkedHashMap<>();
    private final Map<String, GlyphDef> glyphs = new LinkedHashMap<>();
    private final Map<String, GuiDef> guis = new LinkedHashMap<>();
    private final Map<String, BlockDef> blocks = new LinkedHashMap<>();
    private final Map<String, FurnitureDef> furniture = new LinkedHashMap<>();
    private final Map<String, Source> glyphCharsExplicit = new HashMap<>();

    public DefinitionLoader(Path root, String namespace, Environment env, Problems problems) {
        this.root = root;
        this.ns = namespace;
        this.env = env;
        this.problems = problems;
    }

    public Definitions load() {
        eachEntry("items", this::parseItem, items);
        eachEntry("blocks", this::parseBlock, blocks);
        eachEntry("furniture", this::parseFurniture, furniture);
        eachEntry("glyphs", this::parseGlyph, glyphs);
        eachEntry("guis", this::parseGui, guis);
        assignGlyphChars();
        crossCheck();
        return new Definitions(items, glyphs, guis, blocks, furniture);
    }

    // ------------------------------------------------------------------ files

    private <T> void eachEntry(String folder, BiFunction<String, YNode, T> parser, Map<String, T> out) {
        Path dir = root.resolve(folder);
        if (!Files.isDirectory(dir)) return;
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> { String n = p.getFileName().toString(); return n.endsWith(".yml") || n.endsWith(".yaml"); })
                    .sorted().toList();
        } catch (IOException e) {
            problems.error(folder, 0, "не удалось прочитать папку: " + e.getMessage());
            return;
        }
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            YNode doc;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                doc = YNode.parse(rel, r);
            } catch (ConfigException e) {
                problems.error(e);
                continue;
            } catch (IOException e) {
                problems.error(rel, 0, "не удалось прочитать файл: " + e.getMessage());
                continue;
            }
            if (!doc.isMap()) {
                problems.error(rel, doc.line(), "файл должен содержать разделы вида 'id:' с полями внутри");
                continue;
            }
            for (Map.Entry<String, YNode> e : doc.entries().entrySet()) {
                String rawId = e.getKey();
                YNode node = e.getValue();
                try {
                    if (!node.isMap()) throw node.error("ожидался раздел с полями для '" + rawId + "'");
                    String id = Ids.key(ns, rawId, node);
                    if (id.contains("/") && !folder.equals("glyphs") && !folder.equals("guis")) {
                        // допустимо, но предупредим: id с '/' неудобно набирать в командах
                        problems.warn(node.source(), "id '" + id + "' содержит '/', его неудобно использовать в командах");
                    }
                    Source dup = duplicateSource(folder, id);
                    if (dup != null) throw node.error("id '" + id + "' уже определён в " + dup);
                    T def = parser.apply(id, node);
                    out.put(id, def);
                } catch (ConfigException ex) {
                    problems.error(ex);
                }
            }
        }
    }

    /** Предметы, блоки и мебель делят одно пространство id (у блоков и мебели есть свои предметы). */
    private Source duplicateSource(String folder, String id) {
        if (folder.equals("items") || folder.equals("blocks") || folder.equals("furniture")) {
            if (items.containsKey(id)) return items.get(id).source();
            if (blocks.containsKey(id)) return blocks.get(id).source();
            if (furniture.containsKey(id)) return furniture.get(id).source();
        }
        if (folder.equals("glyphs") && glyphs.containsKey(id)) return glyphs.get(id).source();
        if (folder.equals("guis") && guis.containsKey(id)) return guis.get(id).source();
        return null;
    }

    // ------------------------------------------------------------------ look (модель)

    /**
     * Общая логика внешнего вида. {@code defaultParent} — родитель для сгенерированной модели,
     * {@code modelFolder} — где в паке лежит сгенерированная модель (item/ или block/).
     */
    private ModelSpec parseLook(String id, YNode n, String defaultParent, String modelFolder, boolean block, boolean required) {
        int ways = (n.has("texture") || n.has("textures") ? 1 : 0) + (n.has("model") ? 1 : 0) + (n.has("item-model") ? 1 : 0);
        if (ways > 1) throw n.error("укажите что-то одно: texture/textures, model или item-model");
        if (n.has("item-model")) {
            if (block) throw n.require("item-model").error("у блоков нельзя использовать item-model — нужна модель блока (texture/textures/model)");
            return ModelSpec.existing(Ids.key("minecraft", n.reqString("item-model"), n.require("item-model")));
        }
        if (n.has("model")) {
            return ModelSpec.model(Ids.key(ns, n.reqString("model"), n.require("model")));
        }
        String generatedModel = Ids.namespace(id) + ":" + modelFolder + "/" + Ids.path(id);
        Map<String, String> tex = new LinkedHashMap<>();
        String parent;
        if (n.has("texture")) {
            YNode t = n.require("texture");
            List<YNode> list = t.asList();
            if (block) {
                if (list.size() != 1) throw t.error("для блока texture — одна текстура (cube_all); для разных сторон используйте textures: {top, bottom, side}");
                tex.put("all", Ids.key(ns, list.getFirst().asString(), list.getFirst()));
                parent = "minecraft:block/cube_all";
            } else {
                for (int i = 0; i < list.size(); i++) tex.put("layer" + i, Ids.key(ns, list.get(i).asString(), list.get(i)));
                parent = n.bool("handheld", false) ? "minecraft:item/handheld" : defaultParent;
            }
        } else if (n.has("textures")) {
            YNode t = n.require("textures");
            if (!t.isMap()) throw t.error("textures — это раздел вида {top: ..., side: ...}; для одной текстуры используйте texture:");
            for (Map.Entry<String, YNode> e : t.entries().entrySet()) tex.put(e.getKey(), Ids.key(ns, e.getValue().asString(), e.getValue()));
            parent = block ? guessBlockParent(tex.keySet(), t) : defaultParent;
        } else {
            if (!required) return null;
            throw n.error("не задан внешний вид: укажите texture, textures, model или item-model");
        }
        if (n.has("parent")) parent = Ids.key("minecraft", n.reqString("parent"), n.require("parent"));
        return ModelSpec.generated(generatedModel, parent, tex);
    }

    private static String guessBlockParent(Set<String> keys, YNode where) {
        if (keys.equals(Set.of("all"))) return "minecraft:block/cube_all";
        if (keys.equals(Set.of("top", "bottom", "side"))) return "minecraft:block/cube_bottom_top";
        if (keys.equals(Set.of("end", "side"))) return "minecraft:block/cube_column";
        if (keys.equals(Set.of("top", "side"))) return "minecraft:block/cube_top";
        if (keys.containsAll(Set.of("north", "south", "east", "west", "up", "down"))) return "minecraft:block/cube";
        throw where.error("не удалось подобрать модель для текстур " + keys
                + ": используйте {all}, {top, bottom, side}, {end, side}, {top, side} или {north, south, east, west, up, down}, либо укажите parent");
    }

    // ------------------------------------------------------------------ items

    private ItemDef parseItem(String id, YNode n) {
        n.checkKeys(ITEM_KEYS, problems);
        ModelSpec look = parseLook(id, n, "minecraft:item/generated", "item", false, true);
        return baseItem(id, n, look, null);
    }

    private ItemDef baseItem(String id, YNode n, ModelSpec look, ItemDef.Placement placement) {
        String material = material(n, "PAPER");
        List<ItemDef.AttributeSpec> attrs = new ArrayList<>();
        YNode an = n.get("attributes");
        if (an != null) {
            for (YNode a : an.asList()) {
                if (!a.isMap()) throw a.error("каждый атрибут — раздел {attribute, amount, operation, slot}");
                a.checkKeys(Set.of("attribute", "amount", "operation", "slot"), problems);
                String key = Ids.key("minecraft", a.reqString("attribute"), a.require("attribute"));
                if (!env.isAttribute(key)) throw a.require("attribute").error("неизвестный атрибут '" + key + "'" + Suggest.hint(key, env.attributes()));
                double amount = a.require("amount").asDouble();
                String op = a.string("operation", "add_number").toLowerCase(Locale.ROOT);
                if (!OPERATIONS.containsKey(op)) throw a.require("operation").error("неизвестная операция '" + op + "', допустимо: add_number, add_scalar, multiply_scalar_1");
                String slot = a.string("slot", "mainhand").toLowerCase(Locale.ROOT);
                if (!SLOTS.contains(slot)) throw a.require("slot").error("неизвестный слот '" + slot + "', допустимо: " + String.join(", ", SLOTS));
                attrs.add(new ItemDef.AttributeSpec(key, amount, OPERATIONS.get(op), slot));
            }
        }
        Map<String, Integer> ench = new TreeMap<>();
        YNode en = n.get("enchantments");
        if (en != null) {
            if (!en.isMap()) throw en.error("enchantments — раздел вида {sharpness: 3}");
            for (Map.Entry<String, YNode> e : en.entries().entrySet()) {
                String key = Ids.key("minecraft", e.getKey(), e.getValue());
                if (!env.isEnchantment(key)) throw e.getValue().error("неизвестное зачарование '" + key + "'" + Suggest.hint(key, env.enchantments()));
                ench.put(key, e.getValue().asInt());
            }
        }
        ItemDef.EquippableSpec eq = null;
        YNode eqn = n.get("equippable");
        if (eqn != null) {
            eqn.checkKeys(Set.of("slot", "asset", "equip-sound", "camera-overlay", "swappable", "damage-on-hurt"), problems);
            String slot = eqn.reqString("slot").toLowerCase(Locale.ROOT);
            if (!EQUIP_SLOTS.contains(slot)) throw eqn.require("slot").error("неизвестный слот '" + slot + "', допустимо: " + String.join(", ", EQUIP_SLOTS));
            String asset = eqn.has("asset") ? Ids.key(ns, eqn.reqString("asset"), eqn.require("asset")) : id;
            String sound = eqn.has("equip-sound") ? Ids.key("minecraft", eqn.reqString("equip-sound"), eqn.require("equip-sound")) : null;
            String overlay = eqn.has("camera-overlay") ? Ids.key(ns, eqn.reqString("camera-overlay"), eqn.require("camera-overlay")) : null;
            eq = new ItemDef.EquippableSpec(slot, asset, true, sound, overlay, eqn.bool("swappable", true), eqn.bool("damage-on-hurt", true));
        }
        Integer maxStack = n.has("max-stack-size") ? n.integer("max-stack-size", 64, 1, 99) : null;
        Integer maxDamage = n.has("max-damage") ? n.integer("max-damage", 1, 1, Integer.MAX_VALUE) : null;
        if (maxStack != null && maxStack > 1 && maxDamage != null) {
            throw n.error("предмет с max-damage не может складываться: уберите max-stack-size или поставьте 1");
        }
        Boolean glint = n.has("glint") ? n.bool("glint", false) : null;
        return new ItemDef(id, n.source(), material, n.string("name", null), n.stringList("lore"), look,
                maxStack, maxDamage, glint, n.bool("unbreakable", false), n.bool("hide-tooltip", false),
                List.copyOf(attrs), ench, eq, placement);
    }

    private String material(YNode n, String def) {
        YNode m = n.get("material");
        String name = m == null ? def : m.asString().trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        if (!env.isItemMaterial(name)) {
            throw (m == null ? n : m).error("неизвестный материал предмета '" + name + "'" + Suggest.hint(name, env.itemMaterials()));
        }
        return name;
    }

    // ------------------------------------------------------------------ blocks

    private BlockDef parseBlock(String id, YNode n) {
        n.checkKeys(BLOCK_KEYS, problems);
        ModelSpec look = parseLook(id, n, "minecraft:block/cube_all", "block", true, true);
        Integer variation = n.has("variation") ? n.integer("variation", 1, 1, NoteBlockStates.MAX_VARIATION) : null;
        double hardness = n.dbl("hardness", 1.0);
        if (hardness < 0) throw n.require("hardness").error("hardness не может быть отрицательной");
        String tool = n.string("tool", "none").toLowerCase(Locale.ROOT);
        if (!TOOLS.contains(tool)) throw n.require("tool").error("неизвестный инструмент '" + tool + "', допустимо: " + String.join(", ", TOOLS));
        List<BlockDef.Drop> drops = new ArrayList<>();
        YNode dn = n.get("drops");
        if (dn != null) {
            for (YNode d : dn.asList()) {
                if (!d.isMap()) throw d.error("каждый дроп — раздел {item: ..., amount: 1-3, chance: 1.0}");
                d.checkKeys(Set.of("item", "material", "amount", "chance"), problems);
                String item = d.has("item") ? Ids.key(ns, d.reqString("item"), d.require("item")) : null;
                String mat = null;
                if (item == null) {
                    if (!d.has("material")) throw d.error("укажите item (кастомный предмет) или material (ванильный)");
                    mat = material(d, "STONE");
                }
                int[] amount = range(d.get("amount"), 1);
                double chance = d.dbl("chance", 1.0);
                if (chance < 0 || chance > 1) throw d.require("chance").error("chance — вероятность от 0 до 1");
                drops.add(new BlockDef.Drop(item, mat, amount[0], amount[1], chance));
            }
        } else {
            drops.add(new BlockDef.Drop(id, null, 1, 1, 1.0));
        }
        int[] exp = range(n.get("exp"), 0);
        BlockDef.Sounds sounds = parseSounds(n.get("sounds"), "block.stone.place", "block.stone.break", "block.stone.hit");
        ItemDef item = baseItem(id, n, ModelSpec.model(look.model()), new ItemDef.Placement(ItemDef.Placement.Type.BLOCK, id));
        items.put(id, item);
        return new BlockDef(id, n.source(), look, variation, hardness, tool, n.bool("requires-tool", false), List.copyOf(drops),
                n.bool("silk-touch", false), exp[0], exp[1], sounds);
    }

    private BlockDef.Sounds parseSounds(YNode s, String place, String brk, String hit) {
        if (s == null) return new BlockDef.Sounds(key(place), key(brk), key(hit), 1f, 1f);
        s.checkKeys(Set.of("place", "break", "hit", "volume", "pitch"), problems);
        return new BlockDef.Sounds(
                s.has("place") ? Ids.key("minecraft", s.reqString("place"), s.require("place")) : key(place),
                s.has("break") ? Ids.key("minecraft", s.reqString("break"), s.require("break")) : key(brk),
                s.has("hit") ? Ids.key("minecraft", s.reqString("hit"), s.require("hit")) : key(hit),
                (float) s.dbl("volume", 1), (float) s.dbl("pitch", 1));
    }

    private static String key(String s) { return s == null ? null : (s.contains(":") ? s : "minecraft:" + s); }

    /** "2", 2 или "1-3". */
    private static int[] range(YNode n, int def) {
        if (n == null) return new int[]{def, def};
        String s = n.asString().trim();
        try {
            int dash = s.indexOf('-', 1);
            if (dash < 0) { int v = Integer.parseInt(s); return new int[]{v, v}; }
            int a = Integer.parseInt(s.substring(0, dash).trim()), b = Integer.parseInt(s.substring(dash + 1).trim());
            if (a > b || a < 0) throw n.error("неверный диапазон '" + s + "': нужно мин-макс, например 1-3");
            return new int[]{a, b};
        } catch (NumberFormatException e) {
            throw n.error("ожидалось число или диапазон вида 1-3, а найдено '" + s + "'");
        }
    }

    // ------------------------------------------------------------------ furniture

    private FurnitureDef parseFurniture(String id, YNode n) {
        n.checkKeys(FURNITURE_KEYS, problems);
        ModelSpec look = parseLook(id, n, "minecraft:item/generated", "item", false, true);
        String transform = "none";
        float scale = 1f;
        float[] translation = {0f, 0f, 0f};
        Integer brightness = null;
        YNode d = n.get("display");
        if (d != null) {
            d.checkKeys(Set.of("transform", "scale", "translation", "brightness"), problems);
            transform = d.string("transform", transform).toLowerCase(Locale.ROOT);
            if (!DISPLAY_TRANSFORMS.contains(transform)) throw d.require("transform").error("неизвестный transform '" + transform + "', допустимо: " + String.join(", ", DISPLAY_TRANSFORMS));
            scale = (float) d.dbl("scale", 1);
            if (d.has("translation")) translation = vec3(d.require("translation"));
            if (d.has("brightness")) brightness = d.integer("brightness", 15, 0, 15);
        }
        FurnitureDef.Hitbox hitbox = new FurnitureDef.Hitbox(FurnitureDef.Hitbox.Type.INTERACTION, 1f, 1f, List.of());
        YNode h = n.get("hitbox");
        if (h != null) {
            h.checkKeys(Set.of("type", "width", "height", "blocks"), problems);
            String type = h.string("type", "interaction").toLowerCase(Locale.ROOT);
            switch (type) {
                case "interaction" -> hitbox = new FurnitureDef.Hitbox(FurnitureDef.Hitbox.Type.INTERACTION,
                        (float) h.dbl("width", 1), (float) h.dbl("height", 1), List.of());
                case "barrier" -> {
                    List<int[]> list = new ArrayList<>();
                    YNode bl = h.get("blocks");
                    if (bl == null) list.add(new int[]{0, 0, 0});
                    else for (YNode b : bl.asList()) { float[] v = vec3(b); list.add(new int[]{(int) v[0], (int) v[1], (int) v[2]}); }
                    hitbox = new FurnitureDef.Hitbox(FurnitureDef.Hitbox.Type.BARRIER, 1f, 1f, List.copyOf(list));
                }
                case "none" -> hitbox = new FurnitureDef.Hitbox(FurnitureDef.Hitbox.Type.NONE, 0f, 0f, List.of());
                default -> throw h.require("type").error("неизвестный тип хитбокса '" + type + "', допустимо: interaction, barrier, none");
            }
        }
        if (hitbox.type() == FurnitureDef.Hitbox.Type.NONE) {
            problems.warn(n.source(), "у мебели '" + id + "' нет хитбокса — её нельзя будет сломать или на неё сесть, только командой");
        }
        List<FurnitureDef.Seat> seats = new ArrayList<>();
        if (n.has("seat")) {
            YNode s = n.require("seat");
            s.checkKeys(Set.of("x", "y", "z", "height"), problems);
            seats.add(new FurnitureDef.Seat((float) s.dbl("x", 0), (float) s.dbl("height", s.dbl("y", 0.5)), (float) s.dbl("z", 0)));
        }
        if (n.has("seats")) {
            for (YNode s : n.require("seats").asList()) {
                float[] v = vec3(s);
                seats.add(new FurnitureDef.Seat(v[0], v[1], v[2]));
            }
        }
        int step = n.integer("rotation-step", 90, 1, 360);
        if (360 % step != 0) throw n.require("rotation-step").error("rotation-step должен делить 360 (например 90, 45, 22.5 не подходит)");
        BlockDef.Sounds snd = parseSounds(n.get("sounds"), "block.wood.place", "block.wood.break", null);
        ItemDef item = baseItem(id, n, look, new ItemDef.Placement(ItemDef.Placement.Type.FURNITURE, id));
        items.put(id, item);
        return new FurnitureDef(id, n.source(), look, transform, scale, translation, hitbox, List.copyOf(seats), step,
                n.bool("drop", true), snd.place(), snd.breakSound(), brightness);
    }

    private static float[] vec3(YNode n) {
        if (n.isMap()) {
            return new float[]{(float) n.dbl("x", 0), (float) n.dbl("y", 0), (float) n.dbl("z", 0)};
        }
        List<YNode> l = n.asList();
        if (l.size() != 3) throw n.error("ожидалось три числа [x, y, z]");
        return new float[]{(float) l.get(0).asDouble(), (float) l.get(1).asDouble(), (float) l.get(2).asDouble()};
    }

    // ------------------------------------------------------------------ glyphs

    private GlyphDef parseGlyph(String id, YNode n) {
        n.checkKeys(GLYPH_KEYS, problems);
        String tex = Ids.key(ns, n.reqString("texture"), n.require("texture"));
        int height = n.integer("height", 0, 0, 4096);
        int ascent = n.integer("ascent", Integer.MIN_VALUE);
        if (ascent != Integer.MIN_VALUE && height > 0 && ascent > height) {
            throw n.require("ascent").error("ascent (" + ascent + ") не может быть больше height (" + height + ") — клиент не загрузит шрифт");
        }
        char c = 0;
        if (n.has("char")) {
            String s = n.reqString("char");
            if (s.codePointCount(0, s.length()) != 1 || s.length() != 1) throw n.require("char").error("char — ровно один символ, например \"\\uE001\"");
            c = s.charAt(0);
            Source prev = glyphCharsExplicit.putIfAbsent(String.valueOf(c), n.source());
            if (prev != null) throw n.require("char").error("символ уже занят глифом из " + prev);
        }
        // height=0 / ascent=MIN_VALUE → вычисляются из PNG при сборке пака
        return new GlyphDef(id, n.source(), tex, height, ascent, c, 0);
    }

    /** Глифам без явного char раздаются символы из Private Use Area по порядку id — детерминированно. */
    private void assignGlyphChars() {
        Set<Character> used = new HashSet<>();
        for (GlyphDef g : glyphs.values()) if (g.character() != 0) used.add(g.character());
        char next = 0xE000;
        for (String id : new TreeMap<>(glyphs).keySet()) {
            GlyphDef g = glyphs.get(id);
            if (g.character() != 0) continue;
            while (used.contains(next)) next++;
            if (next > 0xF7FF) { problems.error(g.source(), "закончились символы для глифов"); continue; }
            glyphs.put(id, g.withChar(next));
            used.add(next++);
        }
    }

    // ------------------------------------------------------------------ guis

    private GuiDef parseGui(String id, YNode n) {
        n.checkKeys(GUI_KEYS, problems);
        int rows = n.integer("rows", 6, 1, 6);
        String bg = n.has("background") ? Ids.key(ns, n.reqString("background"), n.require("background")) : null;
        List<GuiDef.GuiItem> list = new ArrayList<>();
        YNode its = n.get("items");
        Map<Integer, String> taken = new HashMap<>();
        if (its != null) {
            if (!its.isMap()) throw its.error("items — раздел, где каждый элемент имеет своё имя: items: { кнопка: {slot: 13, ...} }");
            for (Map.Entry<String, YNode> e : its.entries().entrySet()) {
                YNode it = e.getValue();
                try {
                    list.add(parseGuiItem(e.getKey(), it, rows, taken));
                } catch (ConfigException ex) {
                    problems.error(ex);
                }
            }
        }
        List<GuiDef.Action> open = n.has("open-actions") ? actions(n.require("open-actions")) : List.of();
        return new GuiDef(id, n.source(), n.string("title", ""), rows, bg, n.integer("background-offset", Integer.MIN_VALUE),
                n.integer("title-offset", 0), List.copyOf(list), open);
    }

    private GuiDef.GuiItem parseGuiItem(String key, YNode it, int rows, Map<Integer, String> taken) {
        if (!it.isMap()) throw it.error("элемент окна — раздел с полями slot, item/material, actions...");
        it.checkKeys(GUI_ITEM_KEYS, problems);
        List<Integer> slots = new ArrayList<>();
        YNode sn = it.has("slots") ? it.require("slots") : it.get("slot");
        if (sn == null) throw it.error("не указан slot (номер ячейки 0.." + (rows * 9 - 1) + ")");
        for (YNode s : sn.asList()) {
            String v = s.asString().trim();
            int dash = v.indexOf('-', 1);
            try {
                if (dash > 0) {
                    int a = Integer.parseInt(v.substring(0, dash).trim()), b = Integer.parseInt(v.substring(dash + 1).trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) slots.add(i);
                } else slots.add(Integer.parseInt(v));
            } catch (NumberFormatException e) {
                throw s.error("слот должен быть числом или диапазоном вида 10-16, а найдено '" + v + "'");
            }
            for (int slot : slots) {
                if (slot < 0 || slot >= rows * 9) throw s.error("слот " + slot + " вне окна: при rows: " + rows + " доступны 0.." + (rows * 9 - 1));
            }
        }
        for (int slot : slots) {
            String prev = taken.put(slot, key);
            if (prev != null && !prev.equals(key)) problems.warn(it.source(), "слот " + slot + " уже занят элементом '" + prev + "' — будет перезаписан");
        }
        String item = it.has("item") ? Ids.key(ns, it.reqString("item"), it.require("item")) : null;
        String mat = null;
        if (item == null) mat = it.has("material") ? material(it, "STONE") : null;
        String im = it.has("item-model") ? Ids.key(ns, it.reqString("item-model"), it.require("item-model")) : null;
        if (item == null && mat == null && im == null) mat = "PAPER";
        if (mat == null && im != null) mat = "PAPER";
        Map<String, List<GuiDef.Action>> acts = new LinkedHashMap<>();
        for (String k : List.of("actions", "left-actions", "right-actions", "shift-left-actions", "shift-right-actions", "middle-actions", "drop-actions")) {
            if (it.has(k)) acts.put(k.equals("actions") ? "any" : k.replace("-actions", "").replace('-', '_'), actions(it.require(k)));
        }
        return new GuiDef.GuiItem(key, it.source(), List.copyOf(slots), item, mat, it.integer("amount", 1, 1, 99),
                it.string("name", null), it.stringList("lore"), im, acts);
    }

    private static final Map<String, GuiDef.Action.Type> ACTIONS = Map.of(
            "command", GuiDef.Action.Type.COMMAND, "console", GuiDef.Action.Type.CONSOLE,
            "message", GuiDef.Action.Type.MESSAGE, "actionbar", GuiDef.Action.Type.ACTIONBAR,
            "open", GuiDef.Action.Type.OPEN, "close", GuiDef.Action.Type.CLOSE,
            "sound", GuiDef.Action.Type.SOUND, "give", GuiDef.Action.Type.GIVE, "refresh", GuiDef.Action.Type.REFRESH);

    /** Действия: строки "тип: значение" или разделы {тип: значение}. */
    private List<GuiDef.Action> actions(YNode n) {
        List<GuiDef.Action> out = new ArrayList<>();
        for (YNode a : n.asList()) {
            String type, value;
            if (a.isMap()) {
                if (a.keys().size() != 1) throw a.error("действие — одна пара 'тип: значение'");
                type = a.keys().iterator().next();
                YNode v = a.get(type);
                value = v == null ? "" : v.asString();
            } else {
                String s = a.asString().trim();
                int c = s.indexOf(':');
                type = c < 0 ? s : s.substring(0, c);
                value = c < 0 ? "" : s.substring(c + 1).trim();
            }
            type = type.trim().toLowerCase(Locale.ROOT);
            GuiDef.Action.Type t = ACTIONS.get(type);
            if (t == null) throw a.error("неизвестное действие '" + type + "'" + Suggest.hint(type, ACTIONS.keySet())
                    + "; доступны: " + String.join(", ", new TreeMap<>(ACTIONS).keySet()));
            if (value.isEmpty() && t != GuiDef.Action.Type.CLOSE && t != GuiDef.Action.Type.REFRESH) {
                throw a.error("у действия '" + type + "' не указано значение, пример: \"" + type + ": ...\"");
            }
            if (t == GuiDef.Action.Type.OPEN || t == GuiDef.Action.Type.GIVE) {
                String first = value.split("\\s+")[0];
                String rest = value.substring(first.length());
                value = Ids.key(ns, first, a) + rest;
            }
            if (t == GuiDef.Action.Type.COMMAND || t == GuiDef.Action.Type.CONSOLE) {
                if (value.startsWith("/")) value = value.substring(1);
            }
            out.add(new GuiDef.Action(t, value, a.source()));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ cross references

    private void crossCheck() {
        for (GuiDef g : guis.values()) {
            if (g.background() != null && !glyphs.containsKey(g.background())) {
                problems.error(g.source(), "окно '" + g.id() + "': глиф фона '" + g.background() + "' не найден в glyphs/" + Suggest.hint(g.background(), glyphs.keySet()));
            }
            for (GuiDef.GuiItem it : g.items()) {
                if (it.item() != null && !items.containsKey(it.item())) {
                    problems.error(it.source(), "окно '" + g.id() + "', элемент '" + it.key() + "': предмет '" + it.item() + "' не найден" + Suggest.hint(it.item(), items.keySet()));
                }
                it.actions().values().forEach(this::checkActions);
            }
            checkActions(g.openActions());
        }
        for (BlockDef b : blocks.values()) {
            for (BlockDef.Drop d : b.drops()) {
                if (d.item() != null && !items.containsKey(d.item())) {
                    problems.error(b.source(), "блок '" + b.id() + "': дроп '" + d.item() + "' не найден среди предметов" + Suggest.hint(d.item(), items.keySet()));
                }
            }
        }
    }

    private void checkActions(List<GuiDef.Action> actions) {
        for (GuiDef.Action a : actions) {
            String target = a.value().split("\\s+")[0];
            if (a.type() == GuiDef.Action.Type.OPEN && !guis.containsKey(target)) {
                problems.error(a.source(), "open: окно '" + target + "' не найдено" + Suggest.hint(target, guis.keySet()));
            }
            if (a.type() == GuiDef.Action.Type.GIVE && !items.containsKey(target)) {
                problems.error(a.source(), "give: предмет '" + target + "' не найден" + Suggest.hint(target, items.keySet()));
            }
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new HashSet<>(a);
        s.addAll(b);
        return Set.copyOf(s);
    }
}
