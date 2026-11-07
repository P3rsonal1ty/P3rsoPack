package ru.p3rso.pack.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.config.Source;
import ru.p3rso.pack.model.BlockDef;
import ru.p3rso.pack.model.Definitions;
import ru.p3rso.pack.model.FurnitureDef;
import ru.p3rso.pack.model.GlyphDef;
import ru.p3rso.pack.model.Ids;
import ru.p3rso.pack.model.ItemDef;
import ru.p3rso.pack.model.ModelSpec;
import ru.p3rso.pack.model.NoteBlockStates;
import ru.p3rso.pack.model.Settings;
import ru.p3rso.pack.model.Shifts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Собирает ресурспак: содержимое папки pack/ + сгенерированные определения предметов, модели,
 * blockstate нотного блока, шрифты глифов/сдвигов, equipment-ассеты и атлас.
 * <p>Результат детерминирован: одинаковые входные файлы → побайтно одинаковый zip → одинаковый SHA-1
 * на всех серверах сети.</p>
 */
public final class PackGenerator {
    /** Формат ресурспаков клиента 26.3 (resource_major 97, minor 1). */
    public static final int PACK_FORMAT = 97;
    private static final long ZIP_TIME = 315532800000L + 86400000L * 2; // 1980-01-03, фиксированное время записей
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> SKIP_FILES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");

    public record Result(byte[] zip, String sha1, Map<String, byte[]> files, Definitions resolved) {}

    private final Settings settings;
    private final Path packDir;
    private final Problems problems;
    private final Map<String, byte[]> files = new TreeMap<>();
    private final Set<String> userFiles = new LinkedHashSet<>();
    private final Set<String> atlasSingles = new LinkedHashSet<>();

    public PackGenerator(Settings settings, Path packDir, Problems problems) {
        this.settings = settings;
        this.packDir = packDir;
        this.problems = problems;
    }

    public Result generate(Definitions defs, Map<String, Integer> variations) throws IOException {
        copyUserFiles();
        writeMcmeta();
        generateBlocks(defs, variations);
        for (ItemDef item : defs.items().values()) generateItem(item);
        generateEdgeArt(defs);
        if (defs.guis().values().stream().anyMatch(g -> g.isEdge() && !g.edge().centered())) writeFocusOutlineShader();
        writeTitlePanelShader(defs);
        Map<String, GlyphDef> glyphs = generateFonts(defs);
        writeInvisibleItem();
        checkTooltipStyles(defs);
        if (settings.slotHighlight() != Settings.SlotHighlight.DEFAULT) slotHighlight(settings.slotHighlight());
        if (settings.hideInventoryLabel()) hideInventoryLabel();
        writeAtlas();
        Definitions resolved = new Definitions(defs.items(), glyphs, defs.guis(), defs.blocks(), defs.furniture());
        byte[] zip = zip(files);
        return new Result(zip, sha1(zip), Map.copyOf(files), resolved);
    }

    // ------------------------------------------------------------------ user files

    private void copyUserFiles() throws IOException {
        if (!Files.isDirectory(packDir)) return;
        List<Path> list;
        try (Stream<Path> s = Files.walk(packDir)) {
            list = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path p : list) {
            String rel = packDir.relativize(p).toString().replace('\\', '/');
            String name = p.getFileName().toString();
            if (SKIP_FILES.contains(name) || name.startsWith(".")) continue;
            if (rel.equals("pack.mcmeta")) {
                problems.warn("pack/pack.mcmeta", 0, "pack.mcmeta генерируется автоматически, ваш файл игнорируется (описание задаётся в config.yml → pack.description)");
                continue;
            }
            if (!rel.startsWith("assets/") && !rel.equals("pack.png")) {
                problems.warn("pack/" + rel, 0, "файл вне assets/ не попадёт в пак (ожидается структура pack/assets/<namespace>/...)");
                continue;
            }
            if (!rel.equals(rel.toLowerCase(java.util.Locale.ROOT)) && rel.startsWith("assets/")) {
                problems.error("pack/" + rel, 0, "в путях ресурспака допустимы только строчные буквы — клиент не загрузит этот файл");
                continue;
            }
            if (rel.endsWith(".json") || rel.endsWith(".mcmeta")) {
                try {
                    JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    problems.error("pack/" + rel, 0, "некорректный JSON: " + rootMessage(e));
                    continue;
                }
            }
            files.put(rel, Files.readAllBytes(p));
            userFiles.add(rel);
        }
    }

    private void writeMcmeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", settings.packDescription());
        pack.addProperty("min_format", PACK_FORMAT);
        pack.addProperty("max_format", PACK_FORMAT);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        put("pack.mcmeta", root);
    }

    // ------------------------------------------------------------------ items

    private void generateItem(ItemDef item) {
        ModelSpec m = item.model();
        String id = item.id();
        if (m.kind() == ModelSpec.Kind.EXISTING) {
            String def = Ids.asset(m.model(), "items", ".json");
            if (!Ids.namespace(m.model()).equals("minecraft") && !files.containsKey(def)) {
                problems.error(item.source(), "item-model '" + m.model() + "': нет файла pack/" + def);
            }
        } else {
            String modelKey = m.model();
            if (m.kind() == ModelSpec.Kind.GENERATED) {
                writeGeneratedModel(item.source(), m, false);
            } else {
                checkModel(item.source(), modelKey);
            }
            String defPath = Ids.asset(id, "items", ".json");
            if (userFiles.contains(defPath)) {
                // пользователь положил своё определение (select/condition и т.п.) — уважаем его
            } else {
                JsonObject model = new JsonObject();
                model.addProperty("type", "minecraft:model");
                model.addProperty("model", modelKey);
                JsonObject root = new JsonObject();
                root.add("model", model);
                put(defPath, root);
            }
        }
        if (item.equippable() != null) generateEquipment(item);
    }

    private void writeGeneratedModel(Source src, ModelSpec m, boolean block) {
        String path = Ids.asset(m.model(), "models", ".json");
        if (userFiles.contains(path)) {
            problems.warn(src, "модель " + m.model() + " уже есть в pack/ — используется ваш файл, texture/textures из конфига игнорируются");
            checkModel(src, m.model());
            return;
        }
        JsonObject tex = new JsonObject();
        for (Map.Entry<String, String> e : m.textures().entrySet()) {
            checkTexture(src, e.getValue());
            tex.addProperty(e.getKey(), e.getValue());
        }
        if (block && !tex.has("particle")) {
            String particle = m.textures().getOrDefault("all", m.textures().getOrDefault("side",
                    m.textures().getOrDefault("north", m.textures().values().iterator().next())));
            tex.addProperty("particle", particle);
        }
        JsonObject root = new JsonObject();
        root.addProperty("parent", m.parent());
        root.add("textures", tex);
        put(path, root);
    }

    /** Модель из pack/: проверяем, что файл есть и что её текстуры существуют. */
    private void checkModel(Source src, String modelKey) {
        if (Ids.namespace(modelKey).equals("minecraft") && !files.containsKey(Ids.asset(modelKey, "models", ".json"))) return;
        String path = Ids.asset(modelKey, "models", ".json");
        byte[] data = files.get(path);
        if (data == null) {
            problems.error(src, "модель '" + modelKey + "' не найдена: ожидается файл pack/" + path);
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (obj.has("textures") && obj.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("textures").entrySet()) {
                    if (!e.getValue().isJsonPrimitive()) continue;
                    String t = e.getValue().getAsString();
                    if (t.startsWith("#")) continue;
                    String key = t.contains(":") ? t : "minecraft:" + t;
                    checkTexture(new Source("pack/" + path, 0), key);
                }
            }
        } catch (Exception e) {
            problems.error("pack/" + path, 0, "не удалось разобрать модель: " + rootMessage(e));
        }
    }

    private void checkTexture(Source src, String key) {
        if (Ids.namespace(key).equals("minecraft") && !files.containsKey(Ids.asset(key, "textures", ".png"))) return;
        String file = Ids.asset(key, "textures", ".png");
        if (!files.containsKey(file)) {
            problems.error(src, "текстура '" + key + "' не найдена: ожидается файл pack/" + file);
            return;
        }
        String p = Ids.path(key);
        if (!p.startsWith("item/") && !p.startsWith("block/")) atlasSingles.add(key);
    }

    private void generateEquipment(ItemDef item) {
        ItemDef.EquippableSpec eq = item.equippable();
        String asset = eq.asset();
        String path = Ids.asset(asset, "equipment", ".json");
        if (userFiles.contains(path)) return;
        String ns = Ids.namespace(asset), name = Ids.path(asset);
        boolean legs = eq.slot().equals("legs");
        String layer = legs ? "humanoid_leggings" : "humanoid";
        String tex = "assets/" + ns + "/textures/entity/equipment/" + layer + "/" + name + ".png";
        if (!eq.slot().equals("head") && !eq.slot().equals("chest") && !eq.slot().equals("legs") && !eq.slot().equals("feet")) {
            return; // body/saddle/руки — ассет не обязателен
        }
        if (ns.equals("minecraft")) return; // ванильный ассет
        JsonObject layers = new JsonObject();
        for (String l : List.of("humanoid", "humanoid_leggings")) {
            String f = "assets/" + ns + "/textures/entity/equipment/" + l + "/" + name + ".png";
            if (files.containsKey(f)) {
                JsonArray arr = new JsonArray();
                JsonObject t = new JsonObject();
                t.addProperty("texture", asset);
                arr.add(t);
                layers.add(l, arr);
            }
        }
        if (!files.containsKey(tex)) {
            problems.error(item.source(), "броня '" + item.id() + "' (слот " + eq.slot() + "): нет текстуры pack/" + tex
                    + " (или своего файла pack/" + path + ")");
            return;
        }
        if (files.containsKey(path)) return; // уже сгенерирован для другого предмета этого комплекта
        JsonObject root = new JsonObject();
        root.add("layers", layers);
        put(path, root);
    }

    // ------------------------------------------------------------------ blocks

    private void generateBlocks(Definitions defs, Map<String, Integer> variations) {
        if (defs.blocks().isEmpty()) return;
        Map<Integer, String> byVariation = new TreeMap<>();
        for (BlockDef b : defs.blocks().values()) {
            Integer v = variations.get(b.id());
            if (v == null) continue;
            if (b.model().kind() == ModelSpec.Kind.GENERATED) writeGeneratedModel(b.source(), b.model(), true);
            else checkModel(b.source(), b.model().model());
            byVariation.put(v, b.model().model());
        }
        String path = "assets/minecraft/blockstates/note_block.json";
        if (userFiles.contains(path)) {
            problems.warn("pack/" + path, 0, "ваш note_block.json заменён сгенерированным — нотный блок используется для кастомных блоков");
        }
        JsonObject variants = new JsonObject();
        for (int inst = 0; inst < NoteBlockStates.INSTRUMENTS.size(); inst++) {
            for (int note = 0; note < NoteBlockStates.NOTES; note++) {
                String model = byVariation.getOrDefault(NoteBlockStates.variation(inst, note), "minecraft:block/note_block");
                for (boolean powered : new boolean[]{false, true}) {
                    JsonObject v = new JsonObject();
                    v.addProperty("model", model);
                    variants.add(NoteBlockStates.variantKey(inst, note, powered), v);
                }
            }
        }
        JsonObject root = new JsonObject();
        root.add("variants", variants);
        put(path, root);
    }

    // ------------------------------------------------------------------ fonts

    private Map<String, GlyphDef> generateFonts(Definitions defs) {
        String ns = settings.namespace();
        Map<String, GlyphDef> out = new LinkedHashMap<>();
        JsonArray providers = new JsonArray();
        for (GlyphDef g : defs.glyphs().values()) {
            String file = Ids.asset(g.texture(), "textures", ".png");
            byte[] data = files.get(file);
            if (data == null) {
                problems.error(g.source(), "глиф '" + g.id() + "': текстура не найдена, ожидается файл pack/" + file);
                continue;
            }
            Png.Info info;
            try {
                info = Png.read(data);
            } catch (IOException e) {
                problems.error(g.source(), "глиф '" + g.id() + "': не удалось прочитать pack/" + file + ": " + e.getMessage());
                continue;
            }
            int height = g.height() > 0 ? g.height() : info.height();
            int ascent = g.ascent() != Integer.MIN_VALUE ? g.ascent() : Math.min(height, 7);
            if (ascent > height) {
                problems.error(g.source(), "глиф '" + g.id() + "': ascent (" + ascent + ") больше height (" + height + ") — клиент отклонит шрифт");
                continue;
            }
            int advance = Png.advance(info, height);
            JsonObject p = new JsonObject();
            p.addProperty("type", "bitmap");
            p.addProperty("file", g.texture() + ".png");
            p.addProperty("ascent", ascent);
            p.addProperty("height", height);
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf(g.character()));
            p.add("chars", chars);
            providers.add(p);
            out.put(g.id(), new GlyphDef(g.id(), g.source(), g.texture(), height, ascent, g.character(), advance));
        }
        JsonObject glyphFont = new JsonObject();
        glyphFont.add("providers", providers);
        put("assets/" + ns + "/font/glyphs.json", glyphFont);

        JsonObject advances = new JsonObject();
        for (int bit = 0; bit < Shifts.BITS; bit++) {
            advances.addProperty(String.valueOf((char) (Shifts.NEG_BASE + bit)), -(1 << bit));
            advances.addProperty(String.valueOf((char) (Shifts.POS_BASE + bit)), 1 << bit);
        }
        JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        space.add("advances", advances);
        JsonArray sp = new JsonArray();
        sp.add(space);
        JsonObject shiftFont = new JsonObject();
        shiftFont.add("providers", sp);
        put("assets/" + ns + "/font/shift.json", shiftFont);
        return out;
    }

    // ------------------------------------------------------------------ edge menus

    /** Картинки меню на диалоге: фон, вкладки, панели, кнопки (свои PNG в pack/ не трогаем). Широкие режутся на куски. */
    private void generateEdgeArt(Definitions defs) {
        for (var gui : defs.guis().values()) {
            if (!gui.isEdge()) continue;
            var edge = gui.edge();
            List<ru.p3rso.pack.model.EdgeLayout.Element> all = new java.util.ArrayList<>();
            if (edge.background() != null) all.add(edge.background());
            all.addAll(edge.elements());
            all.addAll(edge.extraArt());
            for (var t : edge.tabs()) all.addAll(t.content());
            for (var e : all) {
                boolean own = true;
                for (int i = 0; i < e.tiles(); i++) own &= userFiles.contains(Ids.asset(e.tileTexture(i), "textures", ".png"));
                if (own) continue;                                   // свои картинки
                if (e.art() == null) {
                    for (int i = 0; i < e.tiles(); i++) {
                        String path = Ids.asset(e.tileTexture(i), "textures", ".png");
                        if (!files.containsKey(path)) problems.error(e.source(), "'" + e.key() + "': нет файла pack/" + path);
                    }
                    continue;
                }
                java.awt.image.BufferedImage icon = null;
                if (e.art().icon() != null) {
                    String ip = Ids.asset(e.art().icon(), "textures", ".png");
                    byte[] data = files.get(ip);
                    if (data == null) {
                        problems.error(e.source(), "'" + e.key() + "': иконка '" + e.art().icon() + "' не найдена (pack/" + ip + ")");
                    } else {
                        try {
                            icon = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
                        } catch (IOException ex) {
                            problems.error(e.source(), "'" + e.key() + "': не удалось прочитать иконку: " + ex.getMessage());
                        }
                    }
                }
                StringBuilder chars = new StringBuilder(e.art().label());
                if (e.art().subtitle() != null) chars.append(e.art().subtitle());
                if (e.art().pill() != null) chars.append(e.art().pill());
                e.art().text().forEach(chars::append);
                for (char c : chars.toString().toCharArray()) {
                    if (!PixelFont.get().supports(c) && c != ' ') {
                        problems.warn(e.source(), "'" + e.key() + "': символа '" + c + "' нет в пиксельном шрифте — будет нарисован «?»");
                        break;
                    }
                }
                if (e.kind() == ru.p3rso.pack.model.EdgeLayout.Kind.BUTTON && EdgeArt.buttonLabelRight(e) > e.width()) {
                    problems.warn(e.source(), "'" + e.key() + "': надпись «" + e.art().label() + "» не влезает в кнопку шириной "
                            + e.width() + " px — нужно ~" + EdgeArt.buttonLabelRight(e) + " px (увеличьте width или сократите надпись)");
                }
                java.awt.image.BufferedImage img = e.kind() == ru.p3rso.pack.model.EdgeLayout.Kind.BACKGROUND
                        ? EdgeArt.background(e.width(), e.height()) : EdgeArt.render(e, icon);
                for (int i = 0; i < e.tiles(); i++) {
                    java.awt.image.BufferedImage tile = e.tiles() == 1 ? img : img.getSubimage(e.tileX(i), 0, e.tileWidth(i), e.height());
                    files.put(Ids.asset(e.tileTexture(i), "textures", ".png"), png(tile));
                }
            }
        }
    }

    /**
     * Меню у левого края экрана — текст шире экрана, и после клика клиент обводит его белой рамкой фокуса
     * через весь экран. Шейдер core/gui (копия ванильного 26.3) сжимает белые линии, вылезающие за правый край
     * экрана, в x = 8 (там начинается такой текст); левую линию закрывает фон меню. Обычные окна игры
     * в экран помещаются и не затрагиваются.
     */
    private void writeFocusOutlineShader() {
        String path = "assets/minecraft/shaders/core/gui.vsh";
        if (userFiles.contains(path)) return;
        String src = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                // Can't moj_import in things used during startup, when resource packs don't exist.
                // This is a copy of dynamicimports.glsl and projection.glsl
                layout(std140) uniform DynamicTransforms {
                    mat4 ModelViewMat;
                    mat4 TextureMat;
                    vec4 ColorModulator;
                    vec3 ModelOffset;
                };
                layout(std140) uniform Projection {
                    mat4 ProjMat;
                };

                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;

                layout(location = 0) out vec4 vertexColor;

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                    vertexColor = Color;

                    // P3rsoPack: рамка фокуса вокруг текста меню шире экрана — сжать белые линии за правым краем в x = 8
                    bool orthographic = ProjMat[3][3] == 1.0 && ProjMat[2][3] == 0.0;
                    if (orthographic && Color == vec4(1.0) && gl_Position.x > 1.0) {
                        gl_Position.x = 8.0 * ProjMat[0][0] - 1.0;
                    }
                }
                """;
        files.put(path, src.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Панели вкладок (в заголовке диалога) клиент рисует там, где их поставил заголовок, а сервер не знает размер
     * экрана. Шейдер core/text (копия ванильного 26.3 + блок для GUI) находит куски панели по цвету-маркеру
     * (254, 10 + номер меню, номер куска), ставит панель по центру экрана и увеличивает в целое число раз,
     * насколько позволяет экран. Панель некликабельна, поэтому перенос безопасен.
     */
    private void writeTitlePanelShader(Definitions defs) {
        List<ru.p3rso.pack.model.EdgeLayout> menus = new java.util.ArrayList<>();
        for (var g : defs.guis().values()) if (g.isEdge() && g.edge().hasTitlePanels()) menus.add(g.edge());
        if (menus.isEmpty()) return;
        String path = "assets/minecraft/shaders/core/text.vsh";
        if (userFiles.contains(path)) {
            problems.warn("pack/" + path, 0, "свой text.vsh: панели меню со вкладками не будут центрироваться и увеличиваться");
            return;
        }
        StringBuilder pw = new StringBuilder(), ph = new StringBuilder(), tw = new StringBuilder(), sh = new StringBuilder(),
                asc = new StringBuilder(), tabs = new StringBuilder(), max = new StringBuilder();
        for (int i = 0; i < menus.size(); i++) {
            var e = menus.get(i);
            var panel = e.tabs().stream().filter(t -> t.titlePanel() != null).findFirst().orElseThrow().titlePanel();
            String sep = i == 0 ? "" : ", ";
            pw.append(sep).append(panel.width()).append(".0");
            ph.append(sep).append(panel.height()).append(".0");
            tw.append(sep).append((panel.width() + panel.tiles() - 1) / panel.tiles()).append(".0");
            sh.append(sep).append(e.titleShift()).append(".0");
            GlyphDef g = defs.glyphs().get(panel.tileGlyph(0));
            asc.append(sep).append(g == null ? -24 : g.ascent()).append(".0");
            tabs.append(sep).append(e.width() + 18).append(".0");  // правый край столбца вкладок с фоном: 12 + ширина + 6
            max.append(sep).append(e.contentScale()).append(".0");
        }
        int n = menus.size();
        String src = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                #include <minecraft:fog.glsl>
                #include <minecraft:sample_lightmap.glsl>
                #endif

                #include <minecraft:dynamictransforms.glsl>
                #include <minecraft:projection.glsl>

                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;
                layout(location = 2) in vec2 UV0;
                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                layout(location = 3) in ivec2 UV2;
                #endif

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                uniform sampler2D Sampler2;
                layout(location = 0) out float sphericalVertexDistance;
                layout(location = 1) out float cylindricalVertexDistance;
                #endif

                layout(location = 2) out vec4 vertexColor;
                layout(location = 3) out vec2 texCoord0;

                #ifdef IS_GUI
                // P3rsoPack: размеры панелей меню со вкладками (по номеру меню)
                const int MENUS = %N%;
                const float PANEL_W[MENUS] = float[](%PW%);
                const float PANEL_H[MENUS] = float[](%PH%);
                const float TILE_W[MENUS] = float[](%TW%);
                const float SHIFT[MENUS] = float[](%SH%);
                const float ASCENT[MENUS] = float[](%ASC%);
                const float TABS_RIGHT[MENUS] = float[](%TABS%);
                const float MAX_SCALE[MENUS] = float[](%MAX%);
                #endif

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    sphericalVertexDistance = fog_spherical_distance(Position);
                    cylindricalVertexDistance = fog_cylindrical_distance(Position);
                    vertexColor = Color * sample_lightmap(Sampler2, UV2);
                #else
                    vertexColor = Color;
                #endif

                #ifdef IS_GUI
                    ivec3 marker = ivec3(round(Color.rgb * 255.0));
                    int menu = marker.g - 10;
                    if (marker.r == 254 && menu >= 0 && menu < MENUS) {
                        float sw = 2.0 / ProjMat[0][0];          // ширина экрана в пикселях интерфейса
                        float shh = -2.0 / ProjMat[1][1];        // высота
                        vec4 pos = ModelViewMat * vec4(Position, 1.0);
                        float pw = PANEL_W[menu], ph = PANEL_H[menu], tw = TILE_W[menu];
                        float tile = float(marker.b);
                        float tileX = tile * tw;
                        float tileW = min(tw, pw - tileX);
                        // где панель стоит без шейдера: заголовок по центру экрана, сверху шапка 33 px
                        float titleW = SHIFT[menu] + pw + 1.0;
                        float left0 = floor((sw - titleW) * 0.5) + SHIFT[menu];
                        float top0 = 12.0 + 7.0 - ASCENT[menu];
                        bool right = pos.x - left0 > tileX + tileW * 0.5;
                        bool bottom = pos.y - top0 > ph * 0.5;
                        // увеличение: целое, чтобы пиксели оставались ровными; панель не выше экрана без шапки и кнопок
                        float scale = floor(min((shh - 80.0) / ph, (sw - TABS_RIGHT[menu] - 24.0) / pw));
                        scale = clamp(scale, 1.0, MAX_SCALE[menu]);
                        float halfW = pw * scale * 0.5;
                        float cx = max(sw * 0.5, TABS_RIGHT[menu] + 16.0 + halfW);  // по центру, но не на вкладках
                        cx = min(cx, sw - 4.0 - halfW);                             // и не за правым краем
                        float cy = shh * 0.5;
                        pos.x = cx - pw * scale * 0.5 + (tileX + (right ? tileW : 0.0)) * scale;
                        pos.y = cy - ph * scale * 0.5 + (bottom ? ph * scale : 0.0);
                        gl_Position = ProjMat * pos;
                        vertexColor = vec4(1.0, 1.0, 1.0, Color.a);
                    }
                #endif

                    texCoord0 = UV0;
                }
                """
                .replace("%N%", String.valueOf(n)).replace("%PW%", pw).replace("%PH%", ph).replace("%TW%", tw)
                .replace("%SH%", sh).replace("%ASC%", asc).replace("%TABS%", tabs).replace("%MAX%", max);
        files.put(path, src.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] png(java.awt.image.BufferedImage img) {
        try {
            var out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ GUI helpers

    /** Невидимый предмет для кнопок-областей: модель типа minecraft:empty ничего не рисует. */
    private void writeInvisibleItem() {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:empty");
        JsonObject root = new JsonObject();
        root.add("model", model);
        put(Ids.asset(ru.p3rso.pack.model.DefinitionLoader.invisibleModel(settings.namespace()), "items", ".json"), root);
    }

    /** tooltip-style ns:name → спрайты textures/gui/sprites/tooltip/name_background.png и name_frame.png. */
    private void checkTooltipStyles(Definitions defs) {
        Map<String, Source> styles = new TreeMap<>();
        for (ItemDef i : defs.items().values()) if (i.tooltipStyle() != null) styles.putIfAbsent(i.tooltipStyle(), i.source());
        for (var g : defs.guis().values()) {
            for (var it : g.items()) if (it.tooltipStyle() != null) styles.putIfAbsent(it.tooltipStyle(), it.source());
        }
        for (Map.Entry<String, Source> e : styles.entrySet()) {
            String ns = Ids.namespace(e.getKey()), path = Ids.path(e.getKey());
            if (ns.equals("minecraft")) continue;
            for (String part : List.of("_background", "_frame")) {
                String f = "assets/" + ns + "/textures/gui/sprites/tooltip/" + path + part + ".png";
                if (!files.containsKey(f)) {
                    problems.error(e.getValue(), "tooltip-style '" + e.getKey() + "': нет файла pack/" + f
                            + " (нужны " + path + "_background.png и " + path + "_frame.png, обычно 100×100 с .mcmeta nine_slice)");
                }
            }
        }
    }

    /**
     * Подсветка слотов: subtle — еле заметная (сетка меню не видна, но в сундуках понятно, куда наведён курсор),
     * hidden — нет совсем. Действует на ВСЕ окна у игрока.
     */
    private void slotHighlight(Settings.SlotHighlight mode) {
        String mcmeta = "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":24,\"height\":24,\"border\":4}}}";
        for (String n : List.of("slot_highlight_back", "slot_highlight_front")) {
            boolean faint = mode == Settings.SlotHighlight.SUBTLE && n.endsWith("back");
            String base = "assets/minecraft/textures/gui/sprites/container/" + n + ".png";
            files.put(base, highlightPng(faint));
            files.put(base + ".mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Все языки клиента 26.3 (индекс ассетов + встроенный en_us). */
    static final List<String> LANGUAGES = List.of(("en_us af_za ar_sa ast_es az_az ba_ru bar be_by be_latn bg_bg br_fr brb bs_ba ca_es "
            + "cs_cz cv_cu cy_gb da_dk de_at de_ch de_de el_gr en_au en_ca en_gb en_nz en_pt en_ud enp enws eo_uy es_ar es_cl es_ec "
            + "es_es es_mx es_uy es_ve esan et_ee eu_es fa_ir fi_fi fil_ph fo_fo fr_ca fr_ch fr_fr fra_de fur_it fy_nl ga_ie gd_gb gl_es "
            + "go_fr got_de hal_ua haw_us he_il hi_in hn_no hr_hr hu_hu hy_am id_id ig_ng io_en is_is isv it_it ja_jp jbo_en ka_ge kk_kz "
            + "kn_in ko_kr ksh kw_gb ky_kg la_la lb_lu li_li lmo lo_la lol_us lt_lt lv_lv lzh mk_mk mn_mn ms_my mt_mt nah nds_de nl_be "
            + "nl_nl nn_no no_no oc_fr ovd pl_pl pls pt_br pt_pt qcb_es qid qya_aa ro_ro rpr ru_ru ry_ua sah_sah se_no sk_sk sl_si so_so "
            + "sq_al sr_cs sr_sp sv_se sxu szl ta_in th_th tl_ph tlh_aa tok tr_tr tt_ru tzo_mx uk_ua uz_uz val_es vec_it vi_vn vp_vl vro "
            + "yi_de yo_ng zh_cn zh_hk zh_tw zlm_arab").split(" "));

    /**
     * Надпись «Инвентарь» над инвентарём игрока клиент рисует сам (ключ container.inventory). Пустая строка
     * во всех языках убирает её — во всех окнах-контейнерах. Свои lang-файлы из pack/ сохраняются (ключ дописывается).
     */
    private void hideInventoryLabel() {
        for (String lang : LANGUAGES) {
            String path = "assets/minecraft/lang/" + lang + ".json";
            JsonObject obj = files.containsKey(path)
                    ? JsonParser.parseString(new String(files.get(path), StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            obj.addProperty("container.inventory", "");
            put(path, obj);
        }
    }

    private static byte[] highlightPng(boolean faint) {
        try {
            var img = new java.awt.image.BufferedImage(24, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            if (faint) for (int y = 4; y < 20; y++) for (int x = 4; x < 20; x++) img.setRGB(x, y, 0x22FFFFFF);
            var out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ atlas

    /**
     * Текстуры из textures/item/ и textures/block/ попадают в атласы автоматически. Для остальных путей
     * (например textures/furniture/) добавляем single-источники в атлас blocks — клиент ищет текстуры
     * моделей и в blocks, и в items.
     */
    private void writeAtlas() {
        if (atlasSingles.isEmpty()) return;
        String path = "assets/minecraft/ppases/blocks.json";
        JsonObject root;
        JsonArray sources;
        if (files.containsKey(path)) {
            root = JsonParser.parseString(new String(files.get(path), StandardCharsets.UTF_8)).getAsJsonObject();
            sources = root.has("sources") ? root.getAsJsonArray("sources") : new JsonArray();
        } else {
            root = new JsonObject();
            sources = new JsonArray();
        }
        for (String key : atlasSingles) {
            JsonObject s = new JsonObject();
            s.addProperty("type", "minecraft:single");
            s.addProperty("resource", key);
            sources.add(s);
        }
        root.add("sources", sources);
        put(path, root);
    }

    // ------------------------------------------------------------------ zip

    private void put(String path, JsonElement json) {
        files.put(path, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> e : new TreeMap<>(files).entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(ZIP_TIME);
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
