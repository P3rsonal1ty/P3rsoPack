package dev.atelier.item;

import dev.atelier.AtelierPlugin;
import dev.atelier.model.ItemDef;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Собирает ItemStack по определению. Прототипы кэшируются до следующего /reload. */
@SuppressWarnings("UnstableApiUsage")
public final class ItemFactory {
    private final AtelierPlugin plugin;
    private final NamespacedKey idKey;
    private volatile Map<String, ItemStack> cache = new ConcurrentHashMap<>();

    public ItemFactory(AtelierPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "id");
    }

    public NamespacedKey idKey() { return idKey; }

    public void invalidate() { cache = new ConcurrentHashMap<>(); }

    /** Новый экземпляр предмета или null, если id неизвестен. */
    public ItemStack create(String id, int amount) {
        ItemDef def = plugin.state().defs().items().get(id);
        if (def == null) return null;
        ItemStack proto = cache.computeIfAbsent(id, k -> build(def));
        ItemStack copy = proto.clone();
        copy.setAmount(Math.max(1, amount));
        return copy;
    }

    /** id кастомного предмета или null. Работает и для предметов, удалённых из конфига. */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return stack.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public ItemDef defOf(ItemStack stack) {
        String id = idOf(stack);
        return id == null ? null : plugin.state().defs().items().get(id);
    }

    private ItemStack build(ItemDef def) {
        Material mat = Objects.requireNonNull(Material.matchMaterial(def.material()), def.material());
        ItemStack stack = ItemStack.of(mat);
        var text = plugin.text();
        stack.editMeta(meta -> {
            if (def.name() != null) meta.itemName(text.itemName(def.name()));
            if (!def.lore().isEmpty()) meta.lore(text.lore(def.lore(), null));
            meta.setItemModel(NamespacedKey.fromString(def.itemModelKey()));
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
            if (def.maxStackSize() != null) meta.setMaxStackSize(def.maxStackSize());
            if (def.glint() != null) meta.setEnchantmentGlintOverride(def.glint());
            if (def.unbreakable()) meta.setUnbreakable(true);
            if (def.hideTooltip()) meta.setHideTooltip(true);
            int i = 0;
            for (ItemDef.AttributeSpec a : def.attributes()) {
                Attribute attr = Registry.ATTRIBUTE.get(Objects.requireNonNull(NamespacedKey.fromString(a.attribute())));
                if (attr == null) continue;
                NamespacedKey key = new NamespacedKey(plugin, def.id().replace(':', '.') + "." + i++);
                meta.addAttributeModifier(attr, new AttributeModifier(key, a.amount(), operation(a.operation()), slotGroup(a.slot())));
            }
            for (Map.Entry<String, Integer> e : def.enchantments().entrySet()) {
                Enchantment ench = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                        .get(Objects.requireNonNull(NamespacedKey.fromString(e.getKey())));
                if (ench != null) meta.addEnchant(ench, e.getValue(), true);
            }
        });
        if (def.maxDamage() != null) {
            stack.setData(DataComponentTypes.MAX_DAMAGE, def.maxDamage());
            stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        }
        if (def.equippable() != null) {
            ItemDef.EquippableSpec eq = def.equippable();
            Equippable.Builder b = Equippable.equippable(equipSlot(eq.slot()))
                    .swappable(eq.swappable()).damageOnHurt(eq.damageOnHurt());
            if (isArmorSlot(eq.slot())) b.assetId(Key.key(eq.asset()));
            if (eq.equipSound() != null) b.equipSound(Key.key(eq.equipSound()));
            if (eq.cameraOverlay() != null) b.cameraOverlay(Key.key(eq.cameraOverlay()));
            stack.setData(DataComponentTypes.EQUIPPABLE, b.build());
        }
        return stack;
    }

    private static boolean isArmorSlot(String s) {
        return s.equals("head") || s.equals("chest") || s.equals("legs") || s.equals("feet") || s.equals("body") || s.equals("saddle");
    }

    static AttributeModifier.Operation operation(String op) {
        return switch (op) {
            case "add_scalar" -> AttributeModifier.Operation.ADD_SCALAR;
            case "multiply_scalar_1" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default -> AttributeModifier.Operation.ADD_NUMBER;
        };
    }

    static EquipmentSlotGroup slotGroup(String s) {
        return switch (s) {
            case "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "hand" -> EquipmentSlotGroup.HAND;
            case "head" -> EquipmentSlotGroup.HEAD;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "feet" -> EquipmentSlotGroup.FEET;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "body" -> EquipmentSlotGroup.BODY;
            case "saddle" -> EquipmentSlotGroup.SADDLE;
            case "any" -> EquipmentSlotGroup.ANY;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    static EquipmentSlot equipSlot(String s) {
        return switch (s) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            case "body" -> EquipmentSlot.BODY;
            case "saddle" -> EquipmentSlot.SADDLE;
            case "offhand" -> EquipmentSlot.OFF_HAND;
            default -> EquipmentSlot.HAND;
        };
    }
}
