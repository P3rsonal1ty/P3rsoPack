package ru.p3rso.pack.gui;

import ru.p3rso.pack.model.GuiDef;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/** Метка открытого окна P3rsoPack: какое определение и какой элемент в каком слоте. */
public final class GuiHolder implements InventoryHolder {
    private final GuiDef def;
    private final Map<Integer, GuiDef.GuiItem> bySlot = new HashMap<>();
    private Inventory inventory;

    GuiHolder(GuiDef def) {
        this.def = def;
        for (GuiDef.GuiItem it : def.items()) for (int s : it.slots()) bySlot.put(s, it);
    }

    void inventory(Inventory inv) { this.inventory = inv; }

    public GuiDef def() { return def; }

    public GuiDef.GuiItem itemAt(int slot) { return bySlot.get(slot); }

    @Override
    public Inventory getInventory() { return inventory; }
}
