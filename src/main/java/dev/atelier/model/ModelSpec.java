package dev.atelier.model;

import java.util.Map;

/**
 * Как получить внешний вид предмета.
 * <ul>
 *   <li>{@code GENERATED} — сгенерировать модель из текстур с родителем {@link #parent}</li>
 *   <li>{@code MODEL} — использовать модель из папки pack/ ({@link #model})</li>
 *   <li>{@code EXISTING} — использовать готовое определение предмета (например {@code minecraft:diamond}),
 *       ничего не генерируется</li>
 * </ul>
 */
public record ModelSpec(Kind kind, String model, String parent, Map<String, String> textures) {
    public enum Kind { GENERATED, MODEL, EXISTING }

    public static ModelSpec existing(String itemModel) { return new ModelSpec(Kind.EXISTING, itemModel, null, Map.of()); }

    public static ModelSpec model(String model) { return new ModelSpec(Kind.MODEL, model, null, Map.of()); }

    public static ModelSpec generated(String model, String parent, Map<String, String> textures) {
        return new ModelSpec(Kind.GENERATED, model, parent, Map.copyOf(textures));
    }
}
