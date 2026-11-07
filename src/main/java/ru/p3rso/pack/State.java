package ru.p3rso.pack;

import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.model.BlockDef;
import ru.p3rso.pack.model.Definitions;
import ru.p3rso.pack.model.Settings;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Неизменяемый снимок всего, что загружено при последнем /reload. Обработчики событий на разных
 * потоках (Folia) читают одну volatile-ссылку — никаких блокировок.
 */
public record State(
        Settings settings,
        Definitions defs,
        Map<String, Integer> variations,
        Map<Integer, BlockDef> blockByVariation,
        Problems problems,
        byte[] pack,
        String packSha1,
        UUID packId) {

    public static State empty(Settings s) {
        return new State(s, Definitions.EMPTY, Map.of(), Map.of(), new Problems(), null, null, null);
    }

    public static Map<Integer, BlockDef> index(Definitions defs, Map<String, Integer> variations) {
        Map<Integer, BlockDef> m = new HashMap<>();
        variations.forEach((id, v) -> { BlockDef b = defs.blocks().get(id); if (b != null) m.put(v, b); });
        return Map.copyOf(m);
    }
}
