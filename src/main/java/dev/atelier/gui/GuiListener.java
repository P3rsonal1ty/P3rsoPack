package dev.atelier.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Окна Atelier — только для чтения: любые перемещения предметов отменяются. */
public final class GuiListener implements Listener {
    private final GuiManager guis;

    public GuiListener(GuiManager guis) { this.guis = guis; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof GuiHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() != top) return;
        guis.click(player, holder, e.getSlot(), e.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof GuiHolder)) return;
        int size = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < size) { e.setCancelled(true); return; }
        }
    }
}
