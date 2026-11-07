package ru.p3rso.pack.gui;

import ru.p3rso.pack.P3rsoPackPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Временное скрытие инвентаря игрока для полноэкранных меню. Вещи сериализуются в PersistentDataContainer
 * игрока ДО очистки, поэтому переживают падение сервера: копия сохранится вместе с данными игрока
 * и вернётся при следующем входе. Предметы меню помечены и вычищаются при любом восстановлении.
 */
public final class InventoryBackup {
    private final P3rsoPackPlugin plugin;
    private final NamespacedKey backupKey;
    private final NamespacedKey menuItemKey;

    public InventoryBackup(P3rsoPackPlugin plugin) {
        this.plugin = plugin;
        this.backupKey = new NamespacedKey(plugin, "inventory_backup");
        this.menuItemKey = new NamespacedKey(plugin, "menu_item");
    }

    public NamespacedKey menuItemKey() { return menuItemKey; }

    public boolean has(Player p) {
        return p.getPersistentDataContainer().has(backupKey, PersistentDataType.BYTE_ARRAY);
    }

    public boolean isMenuItem(ItemStack s) {
        return s != null && !s.isEmpty() && s.getPersistentDataContainer().has(menuItemKey, PersistentDataType.BYTE);
    }

    /** Сохранить и очистить 36 слотов инвентаря (броня и вторая рука не трогаются). Повторный вызов ничего не делает. */
    public void hide(Player p) {
        if (has(p)) {
            purge(p);
            return;
        }
        PlayerInventory inv = p.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        p.getPersistentDataContainer().set(backupKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(storage));
        inv.setStorageContents(new ItemStack[storage.length]);
    }

    /** Вернуть вещи. То, что попало в инвентарь, пока меню было открыто (выдал другой плагин), не теряется. */
    public void restore(Player p) {
        byte[] data = p.getPersistentDataContainer().get(backupKey, PersistentDataType.BYTE_ARRAY);
        if (data == null) {
            purge(p);
            return;
        }
        PlayerInventory inv = p.getInventory();
        List<ItemStack> extra = new ArrayList<>();
        for (ItemStack s : inv.getStorageContents()) if (s != null && !s.isEmpty() && !isMenuItem(s)) extra.add(s);
        ItemStack[] saved;
        try {
            saved = ItemStack.deserializeItemsFromBytes(data);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Не удалось восстановить инвентарь {} — копия оставлена в данных игрока", p.getName(), e);
            return;
        }
        ItemStack[] storage = new ItemStack[inv.getStorageContents().length];
        System.arraycopy(saved, 0, storage, 0, Math.min(saved.length, storage.length));
        inv.setStorageContents(storage);
        p.getPersistentDataContainer().remove(backupKey);
        for (ItemStack s : extra) {
            for (ItemStack left : inv.addItem(s).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
    }

    /** Достать сохранённые вещи (для смерти без keepInventory) и удалить копию. */
    public List<ItemStack> take(Player p) {
        byte[] data = p.getPersistentDataContainer().get(backupKey, PersistentDataType.BYTE_ARRAY);
        if (data == null) return List.of();
        p.getPersistentDataContainer().remove(backupKey);
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : ItemStack.deserializeItemsFromBytes(data)) if (s != null && !s.isEmpty()) out.add(s);
        return out;
    }

    /** Убрать предметы меню из инвентаря. */
    public void purge(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < storage.length; i++) {
            if (isMenuItem(storage[i])) { storage[i] = null; changed = true; }
        }
        if (changed) inv.setStorageContents(storage);
        if (isMenuItem(p.getItemOnCursor())) p.setItemOnCursor(null);
    }
}
