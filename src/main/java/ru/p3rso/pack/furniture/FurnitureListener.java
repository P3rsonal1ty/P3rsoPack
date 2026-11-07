package ru.p3rso.pack.furniture;

import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.FurnitureDef;
import ru.p3rso.pack.model.ItemDef;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Установка, ломание и сидения мебели. */
public final class FurnitureListener implements Listener {
    private final P3rsoPackPlugin plugin;
    private final FurnitureManager furniture;

    public FurnitureListener(P3rsoPackPlugin plugin, FurnitureManager furniture) {
        this.plugin = plugin;
        this.furniture = furniture;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        Player p = e.getPlayer();
        // барьерный хитбокс: ЛКМ — сломать, ПКМ — сесть
        if (furniture.isFurnitureBarrier(clicked)) {
            ItemDisplay base = furniture.baseOf(clicked);
            if (base == null) return;
            if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                e.setCancelled(true);
                if (furniture.canBreak(p, base)) furniture.remove(base, p);
                return;
            }
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND && !p.isSneaking()) {
                if (furniture.sit(p, base)) { e.setCancelled(true); return; }
            }
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        ItemDef item = plugin.items().defOf(hand);
        if (item == null || item.placement() == null || item.placement().type() != ItemDef.Placement.Type.FURNITURE) return;
        e.setUseItemInHand(Event.Result.DENY);
        if (ru.p3rso.pack.block.BlockListener.interactable(clicked) && !p.isSneaking() && e.useInteractedBlock() != Event.Result.DENY
                && !furniture.isFurnitureBarrier(clicked)) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        FurnitureDef def = plugin.state().defs().furniture().get(item.placement().target());
        if (def == null) return;
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(e.getBlockFace());
        float yaw = FurnitureManager.snapYaw(p.getYaw(), def.rotationStep());
        if (furniture.place(def, target, yaw, p, hand) != null) {
            if (p.getGameMode() != GameMode.CREATIVE) hand.subtract(1);
            p.swingHand(e.getHand());
        }
    }

    /** ЛКМ по interaction-хитбоксу — сломать. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Entity target = e.getAttacked();
        if (!(target instanceof Interaction) && !furniture.isBase(target)) return;
        ItemDisplay base = furniture.baseOf(target);
        if (base == null) return;
        e.setCancelled(true);
        if (furniture.canBreak(e.getPlayer(), base)) furniture.remove(base, e.getPlayer());
    }

    /** ПКМ по interaction-хитбоксу — сесть. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Interaction i)) return;
        ItemDisplay base = furniture.baseOf(i);
        if (base == null) return;
        e.setCancelled(true);
        furniture.sit(e.getPlayer(), base);
    }

    /** В креативе барьер ломается мгновенно — ломаем всю мебель. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e instanceof BreakCheckEvent || !furniture.isFurnitureBarrier(e.getBlock())) return;
        e.setCancelled(true);
        ItemDisplay base = furniture.baseOf(e.getBlock());
        if (base != null) furniture.remove(base, e.getPlayer());
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        Entity seat = e.getDismounted();
        if (!furniture.isSeat(seat)) return;
        seat.getScheduler().run(plugin, t -> seat.remove(), null);
        if (e.getEntity() instanceof Player p) {
            Location up = p.getLocation().add(0, 0.6, 0);
            p.getScheduler().run(plugin, t -> p.teleportAsync(up), null);
        }
    }
}
