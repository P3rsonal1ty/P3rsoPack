package dev.atelier.model;

import dev.atelier.config.Problems;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Назначает блокам вариации нотного блока. Уже назначенные вариации сохраняются (файл data/block-variations.yml),
 * иначе при добавлении нового блока поставленные в мире блоки поменяли бы вид. Явная {@code variation:} в конфиге
 * имеет приоритет.
 */
public final class BlockVariations {
    private BlockVariations() {}

    public static Map<String, Integer> assign(Map<String, BlockDef> blocks, Map<String, Integer> persisted, Problems problems) {
        Map<String, Integer> out = new TreeMap<>();
        Map<Integer, String> used = new HashMap<>();
        // 1. явные
        for (BlockDef b : blocks.values()) {
            if (b.variation() == null) continue;
            String prev = used.putIfAbsent(b.variation(), b.id());
            if (prev != null) {
                problems.error(b.source(), "variation " + b.variation() + " уже занята блоком '" + prev + "'");
                continue;
            }
            out.put(b.id(), b.variation());
        }
        // 2. сохранённые ранее
        for (BlockDef b : blocks.values()) {
            if (out.containsKey(b.id()) || b.variation() != null) continue;
            Integer v = persisted.get(b.id());
            if (v == null || v < 1 || v > NoteBlockStates.MAX_VARIATION) continue;
            if (used.containsKey(v)) {
                problems.warn(b.source(), "сохранённая variation " + v + " блока '" + b.id() + "' занята блоком '" + used.get(v)
                        + "' — блоку назначена новая, уже поставленные в мире блоки '" + b.id() + "' сменят вид");
                continue;
            }
            used.put(v, b.id());
            out.put(b.id(), v);
        }
        // 3. новые — первая свободная, сохранённые за удалёнными блоками не переиспользуем, пока есть другие
        int next = 1;
        for (BlockDef b : new TreeMap<>(blocks).values()) {
            if (out.containsKey(b.id()) || b.variation() != null) continue;
            while (next <= NoteBlockStates.MAX_VARIATION && (used.containsKey(next) || persisted.containsValue(next))) next++;
            int v = next;
            if (v > NoteBlockStates.MAX_VARIATION) {
                v = 1;
                while (v <= NoteBlockStates.MAX_VARIATION && used.containsKey(v)) v++;
                if (v > NoteBlockStates.MAX_VARIATION) {
                    problems.error(b.source(), "закончились состояния нотного блока (максимум " + NoteBlockStates.MAX_VARIATION + " блоков)");
                    continue;
                }
            }
            used.put(v, b.id());
            out.put(b.id(), v);
        }
        return out;
    }
}
