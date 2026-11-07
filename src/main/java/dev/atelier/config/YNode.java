package dev.atelier.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Узел YAML, который помнит файл и строку. Bukkit-овский YamlConfiguration теряет номера строк,
 * а нам нужны сообщения вида «items/ruby.yml:12: неизвестный материал».
 */
public final class YNode {
    private final String file;
    private final Node node;
    private final String path;
    private final int keyLine;
    private Map<String, YNode> mapCache;

    private YNode(String file, Node node, String path) {
        this(file, node, path, 0);
    }

    private YNode(String file, Node node, String path, int keyLine) {
        this.file = file;
        this.node = node;
        this.path = path;
        this.keyLine = keyLine;
    }

    /** Разбирает YAML. Пустой файл даёт пустой map. */
    public static YNode parse(String file, Reader reader) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(64);
        try {
            Node n = new Yaml(opts).compose(reader);
            if (n == null) n = new MappingNode(Tag.MAP, List.of(), null);
            return new YNode(file, n, "");
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() != null ? e.getProblemMark().getLine() + 1 : 0;
            throw new ConfigException(file, line, "ошибка синтаксиса YAML: " + e.getProblem()
                    + " — проверьте отступы (только пробелы, без табов), двоеточия и кавычки");
        } catch (YAMLException e) {
            throw new ConfigException(file, 0, "ошибка YAML: " + e.getMessage());
        }
    }

    public String file() { return file; }

    /** Строка ключа (для значений в map) или самого узла (для элементов списка). */
    public int line() {
        if (keyLine > 0) return keyLine;
        return node.getStartMark() == null ? 0 : node.getStartMark().getLine() + 1;
    }

    public Source source() { return new Source(file, line()); }

    /** Путь внутри файла, например {@code ruby.attributes[0]}. */
    public String path() { return path; }

    public boolean isMap() { return node instanceof MappingNode; }

    public boolean isList() { return node instanceof SequenceNode; }

    public boolean isScalar() { return node instanceof ScalarNode; }

    public boolean isNull() { return node instanceof ScalarNode s && s.getTag().equals(Tag.NULL); }

    public ConfigException error(String message) {
        return new ConfigException(file, line(), (path.isEmpty() ? "" : "'" + path + "': ") + message);
    }

    // ---------- map ----------

    private Map<String, YNode> map() {
        if (mapCache != null) return mapCache;
        if (!(node instanceof MappingNode m)) throw error("ожидался раздел с полями (key: value), а найдено " + kind());
        Map<String, YNode> out = new LinkedHashMap<>();
        flatten(m, out);
        mapCache = out;
        return out;
    }

    private void flatten(MappingNode m, Map<String, YNode> out) {
        // сначала merge-ключи (<<: *anchor), потом собственные поля их перекрывают
        for (NodeTuple t : m.getValue()) {
            if (t.getKeyNode().getTag().equals(Tag.MERGE)) {
                Node v = t.getValueNode();
                if (v instanceof MappingNode mm) flatten(mm, out);
                else if (v instanceof SequenceNode seq) for (Node x : seq.getValue()) if (x instanceof MappingNode mm) flatten(mm, out);
            }
        }
        for (NodeTuple t : m.getValue()) {
            if (t.getKeyNode().getTag().equals(Tag.MERGE)) continue;
            if (!(t.getKeyNode() instanceof ScalarNode k)) throw new ConfigException(file, line(), "ключ должен быть строкой");
            String key = k.getValue();
            int kl = k.getStartMark() == null ? 0 : k.getStartMark().getLine() + 1;
            out.put(key, new YNode(file, t.getValueNode(), path.isEmpty() ? key : path + "." + key, kl));
        }
    }

    public Set<String> keys() { return map().keySet(); }

    public Collection<YNode> values() { return map().values(); }

    public Map<String, YNode> entries() { return map(); }

    public boolean has(String key) { return isMap() && map().containsKey(key) && !map().get(key).isNull(); }

    /** Значение по ключу или null. */
    public YNode get(String key) {
        YNode n = map().get(key);
        return n == null || n.isNull() ? null : n;
    }

    public YNode require(String key) {
        YNode n = get(key);
        if (n == null) throw error("не указано обязательное поле '" + key + "'");
        return n;
    }

    /** Предупреждает о неизвестных полях (частая причина «почему не работает» — опечатка в ключе). */
    public void checkKeys(Collection<String> allowed, Problems problems) {
        if (!isMap()) return;
        for (Map.Entry<String, YNode> e : map().entrySet()) {
            if (!allowed.contains(e.getKey())) {
                problems.warn(file, e.getValue().line(), "неизвестное поле '" + e.getKey() + "'"
                        + (path.isEmpty() ? "" : " в '" + path + "'") + Suggest.hint(e.getKey(), allowed) + " — оно будет проигнорировано");
            }
        }
    }

    // ---------- scalars ----------

    public String asString() {
        if (!(node instanceof ScalarNode s)) throw error("ожидалось значение (строка/число), а найдено " + kind());
        return s.getValue();
    }

    public int asInt() {
        String s = asString().trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw error("ожидалось целое число, а найдено '" + s + "'");
        }
    }

    public double asDouble() {
        String s = asString().trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw error("ожидалось число, а найдено '" + s + "'");
        }
    }

    public boolean asBool() {
        String s = asString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "yes", "on", "да" -> true;
            case "false", "no", "off", "нет" -> false;
            default -> throw error("ожидалось true или false, а найдено '" + s + "'");
        };
    }

    /** Список; одиночное значение считается списком из одного элемента. */
    public List<YNode> asList() {
        if (node instanceof SequenceNode seq) {
            List<YNode> out = new ArrayList<>();
            int i = 0;
            for (Node n : seq.getValue()) out.add(new YNode(file, n, path + "[" + i++ + "]"));
            return out;
        }
        if (isNull()) return List.of();
        return List.of(this);
    }

    public List<String> asStringList() {
        List<String> out = new ArrayList<>();
        for (YNode n : asList()) out.add(n.asString());
        return out;
    }

    // ---------- helpers ----------

    public String string(String key, String def) {
        YNode n = get(key);
        return n == null ? def : n.asString();
    }

    public String reqString(String key) { return require(key).asString(); }

    public int integer(String key, int def) {
        YNode n = get(key);
        return n == null ? def : n.asInt();
    }

    public int integer(String key, int def, int min, int max) {
        YNode n = get(key);
        if (n == null) return def;
        int v = n.asInt();
        if (v < min || v > max) throw n.error("значение " + v + " вне допустимого диапазона " + min + ".." + max);
        return v;
    }

    public double dbl(String key, double def) {
        YNode n = get(key);
        return n == null ? def : n.asDouble();
    }

    public boolean bool(String key, boolean def) {
        YNode n = get(key);
        return n == null ? def : n.asBool();
    }

    public List<String> stringList(String key) {
        YNode n = get(key);
        return n == null ? List.of() : n.asStringList();
    }

    private String kind() {
        if (node instanceof MappingNode) return "раздел (map)";
        if (node instanceof SequenceNode) return "список";
        return "значение '" + ((ScalarNode) node).getValue() + "'";
    }
}
