package ru.p3rso.pack.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.PlayerInventory;
import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.GuiGeometry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Окна P3rsoPack — только для чтения: любые перемещения предметов отменяются. */
public final class GuiListener implements Listener {
    private final P3rsoPackPlugin plugin;
    private final GuiManager guis;

    public GuiListener(P3rsoPackPlugin plugin, GuiManager guis) {
        this.plugin = plugin;
        this.guis = guis;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof GuiHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == top) {
            guis.click(player, holder, e.getSlot(), e.getClick());
        } else if (e.getClickedInventory() instanceof PlayerInventory && holder.def().hidePlayerInventory()) {
            int v = GuiGeometry.virtualSlot(holder.def().rows(), e.getSlot());
            if (v >= 0) guis.click(player, holder, v, e.getClick());
        }
    }

    /** Закрыли полноэкранное меню — вернуть вещи (если только не открылось следующее такое же меню). */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof GuiHolder h) || !h.def().hidePlayerInventory()) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        p.getScheduler().run(plugin, t -> {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof GuiHolder now && now.def().hidePlayerInventory()) return;
            guis.backup().restore(p);
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        if (guis.backup().has(e.getPlayer())) guis.backup().restore(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        InventoryBackup b = guis.backup();
        if (b.has(e.getPlayer())) {
            b.restore(e.getPlayer());
            plugin.getLogger().info("Инвентарь " + e.getPlayer().getName() + " восстановлен из резервной копии меню");
        } else {
            b.purge(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        InventoryBackup b = guis.backup();
        e.getDrops().removeIf(b::isMenuItem);
        if (!b.has(e.getPlayer())) return;
        if (!e.getKeepInventory()) e.getDrops().addAll(b.take(e.getPlayer()));
        // при keepInventory вещи вернутся при возрождении
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (guis.backup().has(e.getPlayer())) guis.backup().restore(e.getPlayer());
        else guis.backup().purge(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (guis.backup().isMenuItem(e.getItemDrop().getItemStack())) e.getItemDrop().remove();
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
