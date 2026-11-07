package ru.p3rso.pack.model;

import ru.p3rso.pack.config.Source;

import java.util.List;
import java.util.Map;

/** Кастомный предмет из items/*.yml (или авто-предмет блока/мебели). */
public record ItemDef(
        String id,
        Source source,
        String material,
        String name,
        List<String> lore,
        ModelSpec model,
        Integer maxStackSize,
        Integer maxDamage,
        Boolean glint,
        boolean unbreakable,
        boolean hideTooltip,
        List<AttributeSpec> attributes,
        Map<String, Integer> enchantments,
        EquippableSpec equippable,
        Placement placement) {

    /** Что предмет ставит при ПКМ по блоку. */
    public record Placement(Type type, String target) {
        public enum Type { BLOCK, FURNITURE }
    }

    public record AttributeSpec(String attribute, double amount, String operation, String slot) {}

    /**
     * Надеваемый предмет. {@code asset} — ключ equipment-ассета ({@code assets/<ns>/equipment/<path>.json});
     * {@code generateAsset} — сгенерировать ассет из текстур textures/entity/equipment/humanoid[_leggings]/.
     */
    public record EquippableSpec(String slot, String asset, boolean generateAsset, String equipSound,
                                 String cameraOverlay, boolean swappable, boolean damageOnHurt) {}

    /** Ключ для компонента item_model. Для EXISTING — указанный ключ, иначе id предмета. */
    public String itemModelKey() {
        return model.kind() == ModelSpec.Kind.EXISTING ? model.model() : id;
    }
}
