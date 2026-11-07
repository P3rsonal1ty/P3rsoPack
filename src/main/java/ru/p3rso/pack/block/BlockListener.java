package ru.p3rso.pack.block;

import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.BlockDef;
import ru.p3rso.pack.model.ItemDef;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.Iterator;
import java.util.List;

/** Установка/ломание кастомных блоков и защита нотных блоков от ванильных изменений состояния. */
public final class BlockListener implements Listener {
    private final P3rsoPackPlugin plugin;
    private final BlockManager blocks;

    public BlockListener(P3rsoPackPlugin plugin, BlockManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block clicked = e.getClickedBlock();
        // нотный блок не должен менять ноту по ПКМ (иначе станет другим кастомным блоком)
        if (clicked.getType() == Material.NOTE_BLOCK && (blocks.defOf(clicked) != null || plugin.state().settings().resetVanillaNoteBlocks())) {
            e.setUseInteractedBlock(Event.Result.DENY);
        }
        ItemStack hand = e.getItem();
        ItemDef item = plugin.items().defOf(hand);
        if (item == null || item.placement() == null || item.placement().type() != ItemDef.Placement.Type.BLOCK) return;
        e.setUseItemInHand(Event.Result.DENY);
        if (e.useInteractedBlock() != Event.Result.DENY && interactable(clicked) && !e.getPlayer().isSneaking()) {
            return; // ПКМ по сундуку/двери — открываем его, а не ставим блок
        }
        BlockDef def = plugin.state().defs().blocks().get(item.placement().target());
        if (def == null) return;
        place(e.getPlayer(), e.getHand(), hand, clicked, e.getBlockFace(), def);
    }

    @SuppressWarnings("deprecation") // замены в API нет
    public static boolean interactable(Block b) {
        var type = b.getType().asBlockType();
        return type != null && type.isInteractable();
    }

    private void place(Player player, EquipmentSlot hand, ItemStack stack, Block clicked, BlockFace face, BlockDef def) {
        NoteBlock data = blocks.dataFor(def);
        if (data == null) return;
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        if (!target.isReplaceable()) return;
        if (target.getY() < target.getWorld().getMinHeight() || target.getY() >= target.getWorld().getMaxHeight()) return;
        BoundingBox box = BoundingBox.of(target);
        for (Entity en : target.getWorld().getNearbyEntities(box)) {
            if (en instanceof LivingEntity le && !(le instanceof Player p && p.getGameMode() == GameMode.SPECTATOR)) return;
        }
        BlockState previous = target.getState();
        target.setBlockData(data, false);
        BlockPlaceEvent event = new BlockPlaceEvent(target, previous, clicked, stack, player, true, hand);
        if (!event.callEvent() || !event.canBuild()) {
            previous.update(true, false);
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) stack.subtract(1);
        player.swingHand(hand);
        blocks.playSound(target.getLocation().toCenterLocation(), def.sounds().place(), def.sounds());
    }

    /** Ванильный нотный блок ставится в «нейтральное» состояние. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanillaPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.NOTE_BLOCK || !plugin.state().settings().resetVanillaNoteBlocks()) return;
        if (plugin.items().idOf(e.getItemInHand()) != null) return; // это наш блок
        e.getBlockPlaced().setBlockData(BlockManager.vanillaData(), false);
    }

    // ------------------------------------------------------------------ breaking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        BlockDef def = blocks.defOf(b);
        blocks.stopMining(e.getPlayer());
        if (def == null) return;
        e.setDropItems(false);
        Player p = e.getPlayer();
        Location center = b.getLocation().toCenterLocation();
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack tool = p.getInventory().getItemInMainHand();
            for (ItemStack drop : blocks.drops(def, tool, false)) b.getWorld().dropItemNaturally(center, drop);
            e.setExpToDrop(blocks.exp(def));
        } else {
            e.setExpToDrop(0);
        }
        blocks.playSound(center, def.sounds().breakSound(), def.sounds());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent e) {
        BlockDef def = blocks.defOf(e.getBlock());
        if (def == null) {
            blocks.stopMining(e.getPlayer());
            return;
        }
        blocks.startMining(e.getPlayer(), def, e.getItemInHand());
        if (def.sounds().hit() != null) blocks.playSound(e.getBlock().getLocation().toCenterLocation(), def.sounds().hit(), def.sounds());
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent e) { blocks.stopMining(e.getPlayer()); }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) { blocks.stopMining(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { blocks.stopMining(e.getPlayer()); }

    // ------------------------------------------------------------------ vanilla behaviour suppression

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onNote(NotePlayEvent e) {
        if (blocks.defOf(e.getBlock()) != null) e.setCancelled(true);
    }

    /** Подстраховка к paper-global.yml → block-updates.disable-noteblock-updates. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.NOTE_BLOCK && blocks.defOf(b) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { explode(e.blockList()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { explode(e.blockList()); }

    private void explode(List<Block> list) {
        Iterator<Block> it = list.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            BlockDef def = blocks.defOf(b);
            if (def == null) continue;
            it.remove();
            Location center = b.getLocation().toCenterLocation();
            b.setType(Material.AIR, false);
            for (ItemStack drop : blocks.drops(def, null, true)) b.getWorld().dropItemNaturally(center, drop);
        }
    }
}
