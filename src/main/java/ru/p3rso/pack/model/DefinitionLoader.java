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
            "max-damage", "glint", "unbreakable", "hide-tooltip", "attributes", "enchantments", "equippable", "tooltip-style"));
    static final Set<String> BLOCK_KEYS = union(LOOK_KEYS, Set.of("material", "name", "lore", "variation", "hardness",
            "tool", "requires-tool", "drops", "silk-touch", "exp", "sounds", "tooltip-style"));
    static final Set<String> FURNITURE_KEYS = union(LOOK_KEYS, Set.of("material", "name", "lore", "display", "hitbox",
            "seats", "seat", "rotation-step", "drop", "sounds", "tooltip-style"));
    static final Set<String> GLYPH_KEYS = Set.of("texture", "height", "ascent", "char");
    static final Set<String> GUI_KEYS = Set.of("title", "rows", "background", "background-offset", "title-offset", "items", "open-actions",
            "command", "permission", "hide-player-inventory", "buttons", "type");
    static final Set<String> EDGE_KEYS = Set.of("type", "command", "permission", "width", "height", "background",
            "close-button", "elements", "tabs", "tab-width", "position", "logo", "content-width", "content-height",
            "content-offset", "content-shift", "content-scale", "warning-offset");
    static final Set<String> EDGE_TAB_KEYS = Set.of("label", "icon", "style", "color", "name", "lore", "content", "buttons", "actions", "sections");
    static final Set<String> EDGE_CONTENT_KEYS = Set.of("label", "subtitle", "text", "icon", "icon-scale", "style", "color", "cards", "columns");
    static final Set<String> EDGE_CARD_KEYS = Set.of("title", "text", "icon", "style", "color");
    static final Set<String> EDGE_BUTTON_KEYS = Set.of("label", "tooltip", "width", "style", "color", "icon", "actions", "name", "lore");
    static final Set<String> EDGE_ELEMENT_KEYS = Set.of("kind", "x", "y", "width", "height", "texture", "label", "subtitle",
            "text", "icon", "icon-scale", "style", "color", "active", "pill", "name", "lore", "actions");
    static final Set<String> GUI_ITEM_KEYS = Set.of("slot", "slots", "item", "material", "amount", "name", "lore", "item-model",
            "tooltip-style", "actions", "left-actions", "right-actions", "shift-left-actions", "shift-right-actions", "middle-actions", "drop-actions");
    static final Set<String> GUI_BUTTON_KEYS = union(Set.of("x", "y", "width", "height", "icon-slot"),
            GUI_ITEM_KEYS.stream().filter(k -> !k.equals("slot") && !k.equals("slots")).collect(java.util.stream.Collectors.toSet()));

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
    private final Map<String, Source> guiCommands = new HashMap<>();
    private final List<GuiDef.GuiItem> pendingIcons = new ArrayList<>();

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
                List.copyOf(attrs), ench, eq,
                n.has("tooltip-style") ? Ids.key(ns, n.reqString("tooltip-style"), n.require("tooltip-style")) : null, placement);
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
        String type = n.string("type", "chest").toLowerCase(Locale.ROOT);
        if (type.equals("edge")) return parseEdgeGui(id, n);
        if (!type.equals("chest")) throw n.require("type").error("неизвестный тип окна '" + type + "', допустимо: chest, edge");
        n.checkKeys(GUI_KEYS, problems);
        int rows = n.integer("rows", 6, 1, 6);
        boolean hideInv = n.bool("hide-player-inventory", false);
        int maxSlot = rows * 9 + (hideInv ? 36 : 0);
        String bg = n.has("background") ? Ids.key(ns, n.reqString("background"), n.require("background")) : null;
        List<GuiDef.GuiItem> list = new ArrayList<>();
        Map<Integer, String> taken = new HashMap<>();
        YNode its = n.get("items");
        if (its != null) {
            if (!its.isMap()) throw its.error("items — раздел, где каждый элемент имеет своё имя: items: { кнопка: {slot: 13, ...} }");
            for (Map.Entry<String, YNode> e : its.entries().entrySet()) {
                try {
                    list.add(parseGuiItem(e.getKey(), e.getValue(), rows, maxSlot, hideInv, taken, false));
                } catch (ConfigException ex) {
                    problems.error(ex);
                }
            }
        }
        YNode btns = n.get("buttons");
        if (btns != null) {
            if (!btns.isMap()) throw btns.error("buttons — раздел вида { play: {x: 10, y: 20, width: 60, height: 40, actions: [...]} }");
            for (Map.Entry<String, YNode> e : btns.entries().entrySet()) {
                try {
                    list.add(parseGuiItem(e.getKey(), e.getValue(), rows, maxSlot, hideInv, taken, true));
                } catch (ConfigException ex) {
                    problems.error(ex);
                }
            }
            list.addAll(pendingIcons);
            pendingIcons.clear();
        }
        List<GuiDef.Action> open = n.has("open-actions") ? actions(n.require("open-actions")) : List.of();
        String command = guiCommand(id, n);
        String permission = n.has("permission") ? n.reqString("permission").trim() : null;
        return new GuiDef(id, n.source(), n.string("title", ""), rows, bg, n.integer("background-offset", Integer.MIN_VALUE),
                n.integer("title-offset", 0), List.copyOf(list), open, command, permission, hideInv, null);
    }

    private String guiCommand(String id, YNode n) {
        if (!n.has("command")) return null;
        String command = n.reqString("command").trim().toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) command = command.substring(1);
        if (!command.matches("[a-z0-9_-]{1,32}")) throw n.require("command").error("команда — одно слово из a-z, 0-9, _ и -, например menu");
        Source prev = guiCommands.putIfAbsent(command, n.source());
        if (prev != null) throw n.require("command").error("команда /" + command + " уже открывает окно из " + prev);
        return command;
    }

    /** Меню на диалоге: холст с элементами по пикселям или окно со вкладками. */
    private GuiDef parseEdgeGui(String id, YNode n) {
        n.checkKeys(EDGE_KEYS, problems);
        String path = Ids.path(id), ns0 = Ids.namespace(id);
        String position = n.string("position", "left").toLowerCase(Locale.ROOT);
        if (!position.equals("center") && !position.equals("left")) throw n.require("position").error("position: center (окно по центру экрана) или left (у левого края)");
        boolean centered = position.equals("center");
        boolean tabsMode = n.has("tabs");
        int width = n.integer("width", tabsMode ? (centered ? 460 : n.integer("tab-width", 112, 40, 200) + 16) : 252, 40, centered ? 470 : 900);
        int height = n.integer("height", tabsMode ? 180 : 196, 18, 400);
        if (centered && width > 470) problems.warn(n.source(), "меню шире 470 px не поместится по центру при масштабе интерфейса 4");
        List<EdgeLayout.Element> els = new ArrayList<>();
        List<EdgeLayout.Element> extra = new ArrayList<>();
        List<EdgeLayout.Tab> tabs = new ArrayList<>();
        int titleShift = 0;
        if (tabsMode && !centered) {
            titleShift = parseEdgeTabs(ns0, path, n, els, extra, tabs);
            int maxSections = 0;
            for (EdgeLayout.Tab t : tabs) maxSections = Math.max(maxSections, t.sections().size());
            height = 9 + 18 * (tabs.size() + maxSections);
        } else if (tabsMode) {
            parseTabs(ns0, path, n, width, height, els, extra, tabs);
        } else {
            YNode en = n.require("elements");
            if (!en.isMap()) throw en.error("elements — раздел вида { play: {kind: button, x: 6, y: 30, width: 76, height: 16, ...} }");
            for (Map.Entry<String, YNode> e : en.entries().entrySet()) {
                try {
                    els.add(parseEdgeElement(ns0, path, e.getKey(), e.getValue(), width));
                } catch (ConfigException ex) {
                    problems.error(ex);
                }
            }
            checkOverlaps(els);
        }
        if (n.has("logo")) {
            YNode l = n.require("logo");
            EdgeLayout.Element logo = parseEdgeElement(ns0, path, "logo", l, width);
            els.add(logo);
        }
        EdgeLayout.Element bg = null;
        String bgName = n.string("background", "auto").trim();
        if (!bgName.equalsIgnoreCase("none")) {
            int textHeight = ((height + 8) / 9) * 9;
            for (EdgeLayout.Element e : els) textHeight = Math.max(textHeight, (e.lastLine() + 1) * 9);
            int m = EdgeLayout.BG_MARGIN;
            boolean auto = bgName.equalsIgnoreCase("auto");
            String tex = auto ? ns0 + ":gui/edge/" + path + "/background" : Ids.key(ns, bgName, n.require("background"));
            bg = new EdgeLayout.Element("background", n.source(), EdgeLayout.Kind.BACKGROUND, -m, 0, width + 2 * m, textHeight + 2 * m,
                    ns0 + ":edge/" + path + "/background", tex, auto ? new EdgeLayout.Art("", null, List.of(), null, new int[3], new int[3], false, null, 1) : null,
                    null, List.of(), List.of(), null);
            // фон начинается на m px выше первой строки: ascent = 7 + m
            registerGlyphs(bg, 7 + m);
        }
        return new GuiDef(id, n.source(), "", 1, null, 0, 0, List.of(), List.of(), guiCommand(id, n),
                n.has("permission") ? n.reqString("permission").trim() : null, false,
                new EdgeLayout(width, height, centered, bg, List.copyOf(els), n.bool("close-button", false), List.copyOf(tabs), List.copyOf(extra), titleShift,
                        n.has("content-scale") && !n.reqString("content-scale").equalsIgnoreCase("auto") ? n.integer("content-scale", 4, 1, 6) : 4,
                        warningOffset(n, tabs, titleShift)));
    }

    /**
     * Вкладки у левого края экрана: столбец вкладок в теле диалога (текст шире экрана прижат к краю), панель
     * выбранной вкладки — в заголовке диалога (клиент центрирует его по экрану; сдвиг content-shift смещает панель
     * в свободную часть экрана справа от вкладок), кнопки — обычные кнопки диалога внизу.
     * @return сдвиг заголовка в px
     */
    private int parseEdgeTabs(String ns0, String path, YNode n, List<EdgeLayout.Element> els,
                              List<EdgeLayout.Element> extra, List<EdgeLayout.Tab> tabs) {
        int tabW = n.integer("tab-width", 112, 40, 200);
        int cw = n.integer("content-width", 320, 40, 480);
        int ch = n.integer("content-height", 190, 20, 256);
        int offset = n.integer("content-offset", -24, -256, 0);
        int shift = n.integer("content-shift", 8 + tabW + 16, 0, 400);
        YNode tn = n.require("tabs");
        if (!tn.isMap()) throw tn.error("tabs — раздел вида { home: {label: \"Главная\", content: {...}} }");
        TabSpec spec = new TabSpec(ns0, path, tabW, cw, ch, offset, extra);
        for (Map.Entry<String, YNode> en : tn.entries().entrySet()) {
            try {
                tabs.add(parseLeftTab(spec, en.getKey(), en.getValue(), null));
            } catch (ConfigException ex) {
                problems.error(ex);
            }
        }
        if (tabs.isEmpty()) throw tn.error("нет ни одной вкладки");
        return shift;
    }

    private record TabSpec(String ns0, String path, int tabW, int cw, int ch, int offset, List<EdgeLayout.Element> extra) {}

    /** Вкладка (или раздел вкладки, если parent != null) для меню с вкладками у края экрана. */
    private EdgeLayout.Tab parseLeftTab(TabSpec sp, String key, YNode t, String parent) {
        if (!t.isMap()) throw t.error("вкладка — раздел с полями label, icon, content, buttons...");
        if (!key.matches("[a-z0-9_.-]+")) throw t.error("имя вкладки '" + key + "': только a-z, 0-9, _ . -");
        t.checkKeys(EDGE_TAB_KEYS, problems);
        if (parent != null && t.has("sections")) throw t.require("sections").error("у раздела не может быть своих разделов");
        String stateKey = parent == null ? key : parent + "/" + key;
        String fileKey = stateKey.replace('/', '_');
        String icon = t.has("icon") ? Ids.key(ns, t.reqString("icon"), t.require("icon")) : null;
        String label = t.string("label", key);
        int[][] normal = colors(t, parent == null ? "default" : "dark");
        boolean hasContent = t.has("content");
        boolean hasSections = t.has("sections");
        List<GuiDef.Action> direct = t.has("actions") ? actions(t.require("actions")) : List.of();
        if (!hasContent && !hasSections && direct.isEmpty()) problems.warn(t.source(), "вкладка '" + key + "' без content, sections и actions — нажатие ничего не сделает");
        int x = parent == null ? 8 : 18, w = parent == null ? sp.tabW() : sp.tabW() - 10;
        String glyph = sp.ns0() + ":edge/" + sp.path() + "/tab/" + fileKey, tex = sp.ns0() + ":gui/edge/" + sp.path() + "/tab_" + fileKey;
        boolean navigates = hasContent || hasSections;
        EdgeLayout.Element button = new EdgeLayout.Element("tab." + stateKey, t.source(), EdgeLayout.Kind.BUTTON, x, 9, w, 16, glyph, tex,
                new EdgeLayout.Art(label, null, List.of(), icon, normal[0], normal[1], false, null, 1),
                t.string("name", null), t.stringList("lore"), navigates ? List.of() : direct, navigates ? stateKey : null);
        sp.extra().add(button);
        registerGlyphs(button, 7);
        String activeGlyph = null;
        if (navigates) {
            int[][] act = EdgeLayout.STYLES.get("primary");
            activeGlyph = glyph + "_active";
            EdgeLayout.Element active = new EdgeLayout.Element("tab." + stateKey + ".active", t.source(), EdgeLayout.Kind.BUTTON, x, 9, w, 16,
                    activeGlyph, tex + "_active", new EdgeLayout.Art(label, null, List.of(), icon, act[0], act[1], true, null, 1),
                    null, List.of(), List.of(), null);
            sp.extra().add(active);
            registerGlyphs(active, 7);
        }
        EdgeLayout.Element panel = null;
        if (hasContent) {
            YNode c = t.require("content");
            if (!c.isMap()) throw c.error("content — раздел {label, subtitle, text, cards, icon, style}");
            c.checkKeys(EDGE_CONTENT_KEYS, problems);
            int[][] cc = colors(c, "dark");
            String cicon = c.has("icon") ? Ids.key(ns, c.reqString("icon"), c.require("icon")) : null;
            List<EdgeLayout.Card> cards = new ArrayList<>();
            if (c.has("cards")) {
                for (YNode cd : c.require("cards").asList()) {
                    if (!cd.isMap()) throw cd.error("плашка — раздел {title, text, icon, style}");
                    cd.checkKeys(EDGE_CARD_KEYS, problems);
                    int[][] col = colors(cd, "default");
                    cards.add(new EdgeLayout.Card(cd.string("title", ""), cd.stringList("text"),
                            cd.has("icon") ? Ids.key(ns, cd.reqString("icon"), cd.require("icon")) : null, col[0], col[1]));
                }
            }
            panel = new EdgeLayout.Element("content." + stateKey, c.source(), EdgeLayout.Kind.PANEL, 0, 0, sp.cw(), sp.ch(),
                    sp.ns0() + ":edge/" + sp.path() + "/content/" + fileKey, sp.ns0() + ":gui/edge/" + sp.path() + "/content_" + fileKey,
                    new EdgeLayout.Art(c.string("label", ""), c.string("subtitle", null), c.stringList("text"), cicon, cc[0], cc[1], false, null,
                            c.integer("icon-scale", 3, 1, 4), List.copyOf(cards), c.integer("columns", 2, 1, 4)), null, List.of(), List.of(), null);
            sp.extra().add(panel);
            // панель в заголовке: отрицательный ascent опускает её из шапки диалога, шейдер ставит по центру
            registerGlyphs(panel, sp.offset());
        }
        List<EdgeLayout.FooterButton> buttons = new ArrayList<>();
        if (t.has("buttons")) {
            for (YNode b : t.require("buttons").asList()) {
                if (!b.isMap()) throw b.error("кнопка — раздел {label, actions}");
                b.checkKeys(EDGE_BUTTON_KEYS, problems);
                buttons.add(new EdgeLayout.FooterButton(b.reqString("label"), b.string("name", b.string("tooltip", null)),
                        b.integer("width", 150, 20, 1024), actions(b.require("actions"))));
            }
            if (buttons.size() > 2) throw t.require("buttons").error("у вкладки не больше двух кнопок внизу (так устроены диалоги)");
        }
        List<EdgeLayout.Tab> sections = new ArrayList<>();
        if (hasSections) {
            YNode sn = t.require("sections");
            if (!sn.isMap()) throw sn.error("sections — раздел вида { store: {label: \"Магазин\", content: {...}} }");
            for (Map.Entry<String, YNode> se : sn.entries().entrySet()) {
                try {
                    sections.add(parseLeftTab(sp, se.getKey(), se.getValue(), key));
                } catch (ConfigException ex) {
                    problems.error(ex);
                }
            }
        }
        return new EdgeLayout.Tab(stateKey, t.source(), activeGlyph, List.of(), List.copyOf(buttons), direct, panel, button, List.copyOf(sections));
    }

    /**
     * Сдвиг значка ⚠ влево (px). Клиент ставит значок справа от заголовка, а ряд «заголовок + значок» центрирует;
     * укорачивая заголовок на 2·N невидимым сдвигом, двигаем значок влево на N. Заголовок не может стать короче нуля.
     */
    private int warningOffset(YNode n, List<EdgeLayout.Tab> tabs, int titleShift) {
        if (!n.has("warning-offset")) return 0;
        int v = n.integer("warning-offset", 0, 0, 1000);
        int titleW = titleShift + 1;
        for (EdgeLayout.Tab t : tabs) if (t.titlePanel() != null) { titleW += t.titlePanel().width(); break; }
        int max = titleW / 2;
        if (v > max) {
            problems.warn(n.require("warning-offset").source(), "warning-offset " + v + " больше возможного (" + max + " px при текущей ширине панели) — будет " + max);
            v = max;
        }
        return v;
    }

    /** Глифы элемента (по одному на кусок шириной ≤ MAX_TILE). */
    private void registerGlyphs(EdgeLayout.Element e, int ascent) {
        for (int i = 0; i < e.tiles(); i++) {
            glyphs.put(e.tileGlyph(i), new GlyphDef(e.tileGlyph(i), e.source(), e.tileTexture(i), e.height(), ascent, (char) 0, 0));
        }
    }

    /** Кликабельные элементы на одних строках не должны пересекаться по x — иначе клик попадёт не туда. */
    private void checkOverlaps(List<EdgeLayout.Element> els) {
        for (int i = 0; i < els.size(); i++) {
            for (int j = i + 1; j < els.size(); j++) {
                EdgeLayout.Element a = els.get(i), b = els.get(j);
                boolean xOverlap = a.x() < b.x() + b.width() && b.x() < a.x() + a.width();
                if (!xOverlap) continue;
                // некликабельный элемент занимает только свою первую строку — поверх него ниже можно ставить кнопки
                int aLast = a.clickable() ? a.lastLine() : a.firstLine();
                int bLast = b.clickable() ? b.lastLine() : b.firstLine();
                boolean lines = a.firstLine() <= bLast && b.firstLine() <= aLast;
                if (lines) {
                    problems.error(b.source(), "элемент '" + b.key() + "' перекрывается с '" + a.key() + "' на одних строках текста (по 9 px)."
                            + " Сдвиньте по x или по y; поверх некликабельной панели можно ставить кнопки ниже её первой строки");
                }
            }
            if (els.get(i).clickable() && els.get(i).y() < 9) {
                problems.error(els.get(i).source(), "кликабельный элемент '" + els.get(i).key() + "' должен начинаться не выше y = 9 (строка 0 занята фоном)");
            }
        }
    }

    /** Цвета из style/color раздела. */
    private int[][] colors(YNode e, String defStyle) {
        if (e.has("color")) {
            String hex = e.reqString("color").trim();
            if (!hex.matches("#?[0-9a-fA-F]{6}")) throw e.require("color").error("color — цвет вида \"#5a4aa0\"");
            int v = Integer.parseInt(hex.replace("#", ""), 16);
            int[] top = {v >> 16 & 255, v >> 8 & 255, v & 255};
            return new int[][]{top, {top[0] * 45 / 100, top[1] * 45 / 100, top[2] * 45 / 100}};
        }
        String style = e.string("style", defStyle).toLowerCase(Locale.ROOT);
        int[][] c = EdgeLayout.STYLES.get(style);
        if (c == null) throw e.require("style").error("неизвестный style '" + style + "', допустимо: "
                + String.join(", ", new java.util.TreeSet<>(EdgeLayout.STYLES.keySet())) + " (или color: \"#rrggbb\")");
        return c;
    }

    private static String plain(String mini) { return mini.replaceAll("<[^>]+>", "").trim(); }

    /**
     * Окно со вкладками: столбец вкладок слева, справа большая панель выбранной вкладки и кнопки на ней.
     * Переключение вкладки перерисовывает диалог.
     */
    private void parseTabs(String ns0, String path, YNode n, int width, int height,
                           List<EdgeLayout.Element> els, List<EdgeLayout.Element> extra, List<EdgeLayout.Tab> tabs) {
        int tabW = n.integer("tab-width", 112, 40, 200);
        int panelX = 8 + tabW + 8, panelY = 9, panelW = width - panelX - 8, panelH = ((height - 9) / 9) * 9 - 9;
        if (panelW < 60) throw n.error("слишком узко для панели: увеличьте width или уменьшите tab-width");
        YNode tn = n.require("tabs");
        if (!tn.isMap()) throw tn.error("tabs — раздел вида { home: {label: \"Главная\", content: {...}} }");
        int i = 0;
        for (Map.Entry<String, YNode> en : tn.entries().entrySet()) {
            String key = en.getKey();
            YNode t = en.getValue();
            try {
                if (!t.isMap()) throw t.error("вкладка — раздел с полями label, icon, content, buttons...");
                if (!key.matches("[a-z0-9_.-]+")) throw t.error("имя вкладки '" + key + "': только a-z, 0-9, _ . -");
                t.checkKeys(EDGE_TAB_KEYS, problems);
                int y = 9 + 18 * i++;
                if (y + 16 > height) problems.warn(t.source(), "вкладка '" + key + "' не помещается по высоте (height: " + height + ") — увеличьте height");
                String icon = t.has("icon") ? Ids.key(ns, t.reqString("icon"), t.require("icon")) : null;
                String label = t.string("label", key);
                int[][] normal = colors(t, "default");
                boolean hasContent = t.has("content");
                List<GuiDef.Action> direct = t.has("actions") ? actions(t.require("actions")) : List.of();
                if (!hasContent && direct.isEmpty()) problems.warn(t.source(), "вкладка '" + key + "' без content и без actions — нажатие ничего не сделает");
                String glyph = ns0 + ":edge/" + path + "/tab/" + key, tex = ns0 + ":gui/edge/" + path + "/tab_" + key;
                EdgeLayout.Element el = new EdgeLayout.Element("tab." + key, t.source(), EdgeLayout.Kind.BUTTON, 8, y, tabW, 16, glyph, tex,
                        new EdgeLayout.Art(label, null, List.of(), icon, normal[0], normal[1], false, null, 1),
                        t.string("name", null), t.stringList("lore"), hasContent ? List.of() : direct, hasContent ? key : null);
                els.add(el);
                registerGlyphs(el, EdgeLayout.ascentFor(y));
                String activeGlyph = null;
                List<EdgeLayout.Element> content = new ArrayList<>();
                if (hasContent) {
                    int[][] act = EdgeLayout.STYLES.get("primary");
                    activeGlyph = glyph + "_active";
                    EdgeLayout.Element active = new EdgeLayout.Element("tab." + key + ".active", t.source(), EdgeLayout.Kind.BUTTON, 8, y, tabW, 16,
                            activeGlyph, tex + "_active", new EdgeLayout.Art(label, null, List.of(), icon, act[0], act[1], true, null, 1),
                            null, List.of(), List.of(), null);
                    extra.add(active);
                    registerGlyphs(active, EdgeLayout.ascentFor(y));
                    YNode c = t.require("content");
                    if (!c.isMap()) throw c.error("content — раздел {label, subtitle, text, icon, style}");
                    c.checkKeys(EDGE_CONTENT_KEYS, problems);
                    int[][] cc = colors(c, "dark");
                    String cicon = c.has("icon") ? Ids.key(ns, c.reqString("icon"), c.require("icon")) : null;
                    EdgeLayout.Element panel = new EdgeLayout.Element("content." + key, c.source(), EdgeLayout.Kind.PANEL, panelX, panelY, panelW, panelH,
                            ns0 + ":edge/" + path + "/content/" + key, ns0 + ":gui/edge/" + path + "/content_" + key,
                            new EdgeLayout.Art(c.string("label", ""), c.string("subtitle", null), c.stringList("text"), cicon, cc[0], cc[1], false, null,
                                    c.integer("icon-scale", 3, 1, 4)), null, List.of(), List.of(), null);
                    content.add(panel);
                    registerGlyphs(panel, EdgeLayout.ascentFor(panelY));
                    // кнопки на панели — в её нижней части
                    if (t.has("buttons")) {
                        int bx = panelX + 10, by = panelY + panelH - 27 - (Math.floorMod(panelY + panelH - 27, 9));
                        int bi = 0;
                        for (YNode b : t.require("buttons").asList()) {
                            if (!b.isMap()) throw b.error("кнопка — раздел {label, actions}");
                            b.checkKeys(EDGE_BUTTON_KEYS, problems);
                            String bl = plain(b.reqString("label"));
                            String bicon = b.has("icon") ? Ids.key(ns, b.reqString("icon"), b.require("icon")) : null;
                            int[][] bc = colors(b, bi == 0 ? "gold" : "default");
                            int auto = ru.p3rso.pack.pack.PixelFont.get().width(bl, 1) + (bicon != null ? 26 : 16);
                            int bw = b.integer("width", Math.max(64, auto), 20, panelW - 20);
                            if (bx + bw > panelX + panelW - 6) throw b.error("кнопки не помещаются на панели по ширине");
                            EdgeLayout.Element btn = new EdgeLayout.Element("content." + key + ".b" + bi, b.source(), EdgeLayout.Kind.BUTTON, bx, by, bw, 18,
                                    ns0 + ":edge/" + path + "/content/" + key + "_b" + bi, ns0 + ":gui/edge/" + path + "/content_" + key + "_b" + bi,
                                    new EdgeLayout.Art(bl, null, List.of(), bicon, bc[0], bc[1], false, null, 1),
                                    b.string("name", b.string("tooltip", null)), b.stringList("lore"), actions(b.require("actions")), null);
                            content.add(btn);
                            registerGlyphs(btn, EdgeLayout.ascentFor(by));
                            bx += bw + 8;
                            bi++;
                        }
                    }
                }
                tabs.add(new EdgeLayout.Tab(key, t.source(), activeGlyph, List.copyOf(content), List.of(), direct, null, null, List.of()));
            } catch (ConfigException ex) {
                problems.error(ex);
            }
        }
        if (tabs.isEmpty()) throw tn.error("нет ни одной вкладки");
    }

    private EdgeLayout.Element parseEdgeElement(String ns0, String guiPath, String key, YNode e, int canvasWidth) {
        if (!e.isMap()) throw e.error("элемент меню — раздел с полями kind, x, y, width, height...");
        if (!key.matches("[a-z0-9_.-]+")) throw e.error("имя элемента '" + key + "': только a-z, 0-9, _ . -");
        e.checkKeys(EDGE_ELEMENT_KEYS, problems);
        String kindName = e.string("kind", e.has("texture") ? "image" : "button").toLowerCase(Locale.ROOT);
        EdgeLayout.Kind kind;
        try {
            kind = EdgeLayout.Kind.valueOf(kindName.toUpperCase(Locale.ROOT));
            if (kind == EdgeLayout.Kind.BACKGROUND) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw e.require("kind").error("неизвестный kind '" + kindName + "', допустимо: button, panel, header, image");
        }
        int x = e.require("x").asInt(), y = e.require("y").asInt();
        int defH = switch (kind) { case BUTTON -> 16; case HEADER -> 26; default -> 36; };
        int w = e.integer("width", kind == EdgeLayout.Kind.BUTTON ? 76 : 120, 4, 900);
        int h = e.integer("height", defH, 8, 256);
        if (x < 0 || y < 0) throw e.error("x и y не могут быть отрицательными (0,0 — левый верхний угол меню)");
        if (x + w > canvasWidth) problems.warn(e.source(), "элемент '" + key + "' выходит за ширину меню (" + canvasWidth + " px)");
        String glyph = ns0 + ":edge/" + guiPath + "/" + key;
        String texture;
        EdgeLayout.Art art = null;
        if (e.has("texture")) {
            texture = Ids.key(ns, e.reqString("texture"), e.require("texture"));
        } else {
            if (kind == EdgeLayout.Kind.IMAGE) throw e.error("для kind: image укажите texture");
            texture = ns0 + ":gui/edge/" + guiPath + "/" + key;
            int[][] colors = colors(e, kind == EdgeLayout.Kind.PANEL ? "dark" : "default");
            String icon = e.has("icon") ? Ids.key(ns, e.reqString("icon"), e.require("icon")) : null;
            art = new EdgeLayout.Art(e.string("label", ""), e.string("subtitle", null), e.stringList("text"), icon,
                    colors[0], colors[1], e.bool("active", false), e.string("pill", null), e.integer("icon-scale", kind == EdgeLayout.Kind.PANEL ? 2 : 1, 1, 4));
        }
        if (e.has("texture") && w > EdgeLayout.MAX_TILE) throw e.error("своя картинка шире " + EdgeLayout.MAX_TILE + " px — разрежьте её на несколько элементов");
        List<GuiDef.Action> acts = e.has("actions") ? actions(e.require("actions")) : List.of();
        EdgeLayout.Element el = new EdgeLayout.Element(key, e.source(), kind, x, y, w, h, glyph, texture, art,
                e.string("name", null), e.stringList("lore"), acts, null);
        registerGlyphs(el, EdgeLayout.ascentFor(y));
        return el;
    }

    /** Невидимая модель для кнопок-областей (генерируется в паке всегда). */
    public static String invisibleModel(String namespace) { return namespace + ":p3rsopack_invisible"; }

    private GuiDef.GuiItem parseGuiItem(String key, YNode it, int rows, int maxSlot, boolean hideInv,
                                        Map<Integer, String> taken, boolean button) {
        if (!it.isMap()) throw it.error(button ? "кнопка — раздел с полями x, y, width, height, actions..."
                : "элемент окна — раздел с полями slot, item/material, actions...");
        it.checkKeys(button ? GUI_BUTTON_KEYS : GUI_ITEM_KEYS, problems);
        List<Integer> slots = new ArrayList<>();
        if (button) {
            int x = it.require("x").asInt(), y = it.require("y").asInt();
            int w = it.require("width").asInt(), h = it.require("height").asInt();
            if (w <= 0 || h <= 0) throw it.error("width и height должны быть больше нуля");
            slots.addAll(GuiGeometry.covered(rows, hideInv, x, y, w, h));
            if (slots.isEmpty()) {
                throw it.error("кнопка " + x + "," + y + " " + w + "×" + h + " не накрывает ни одной ячейки: кликабельны только центры ячеек 18×18"
                        + " (окно x 7..169, y 17.." + (17 + rows * 18) + (hideInv ? ", инвентарь игрока y " + (GuiGeometry.height(rows) - 83)
                        + ".." + (GuiGeometry.height(rows) - 7) : ", для нижней части включите hide-player-inventory") + ")");
            }
        } else {
            YNode sn = it.has("slots") ? it.require("slots") : it.get("slot");
            if (sn == null) throw it.error("не указан slot (номер ячейки 0.." + (maxSlot - 1) + ")");
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
                    if (slot < 0 || slot >= maxSlot) throw s.error("слот " + slot + " вне окна: при rows: " + rows + " доступны 0.." + (maxSlot - 1)
                            + (hideInv ? " (включая инвентарь игрока)" : ""));
                }
            }
        }
        String item = it.has("item") ? Ids.key(ns, it.reqString("item"), it.require("item")) : null;
        String mat = null;
        if (item == null) mat = it.has("material") ? material(it, "STONE") : null;
        String im = it.has("item-model") ? Ids.key(ns, it.reqString("item-model"), it.require("item-model")) : null;
        Integer iconSlot = null;
        if (button) {
            if (it.has("icon-slot")) {
                iconSlot = it.require("icon-slot").asInt();
                if (!slots.contains(iconSlot)) throw it.require("icon-slot").error("icon-slot " + iconSlot + " не входит в область кнопки: " + slots);
            } else if (item != null || it.has("material") || im != null) {
                iconSlot = slots.get(slots.size() / 2);
            }
        }
        for (int slot : slots) {
            String prev = taken.put(slot, key);
            if (prev != null && !prev.equals(key)) problems.warn(it.source(), "ячейка " + slot + " уже занята элементом '" + prev + "' — будет перезаписана");
        }
        if (item == null && mat == null && im == null) mat = button ? "PAPER" : "PAPER";
        if (mat == null && im != null) mat = "PAPER";
        Map<String, List<GuiDef.Action>> acts = new LinkedHashMap<>();
        for (String k : List.of("actions", "left-actions", "right-actions", "shift-left-actions", "shift-right-actions", "middle-actions", "drop-actions")) {
            if (it.has(k)) acts.put(k.equals("actions") ? "any" : k.replace("-actions", "").replace('-', '_'), actions(it.require(k)));
        }
        String tooltip = it.has("tooltip-style") ? Ids.key(ns, it.reqString("tooltip-style"), it.require("tooltip-style")) : null;
        String name = it.string("name", null);
        List<String> lore = it.stringList("lore");
        int amount = it.integer("amount", 1, 1, 99);
        if (!button) {
            return new GuiDef.GuiItem(key, it.source(), List.copyOf(slots), item, mat, amount, name, lore, im, tooltip, acts);
        }
        // кнопка: невидимые предметы на всей области + (необязательно) видимая иконка в одной ячейке
        List<Integer> area = new ArrayList<>(slots);
        if (iconSlot != null) area.remove(iconSlot);
        GuiDef.GuiItem areaItem = new GuiDef.GuiItem(key, it.source(), List.copyOf(area), null, "PAPER", 1, name, lore,
                invisibleModel(ns), tooltip, acts);
        if (iconSlot == null) return areaItem;
        pendingIcons.add(new GuiDef.GuiItem(key + "#icon", it.source(), List.of(iconSlot), item, mat, amount, name, lore, im, tooltip, acts));
        return areaItem;
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
            if (g.isEdge()) {
                for (EdgeLayout.Element e : g.edge().elements()) checkActions(e.actions());
                for (EdgeLayout.Tab t : g.edge().tabs()) {
                    checkActions(t.actions());
                    for (EdgeLayout.Element e : t.content()) checkActions(e.actions());
                    for (EdgeLayout.FooterButton b : t.buttons()) checkActions(b.actions());
                    for (EdgeLayout.Tab sec : t.sections()) {
                        checkActions(sec.actions());
                        for (EdgeLayout.FooterButton b : sec.buttons()) checkActions(b.actions());
                    }
                }
            }
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
