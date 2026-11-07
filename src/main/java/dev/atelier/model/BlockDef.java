package dev.atelier.model;

import dev.atelier.config.Source;

import java.util.List;

/**
 * Кастомный блок на состоянии нотного блока.
 * {@code model} — ключ модели блока (сгенерированной или из pack/).
 */
public record BlockDef(
        String id,
        Source source,
        ModelSpec model,
        Integer variation,
        double hardness,
        String tool,
        boolean requiresTool,
        List<Drop> drops,
        boolean silkTouchSelf,
        int expMin,
        int expMax,
        Sounds sounds) {

    public record Drop(String item, String material, int min, int max, double chance) {}

    public record Sounds(String place, String breakSound, String hit, float volume, float pitch) {}
}
