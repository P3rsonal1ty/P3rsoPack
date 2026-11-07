package ru.p3rso.pack.furniture;

import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.FurnitureDef;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Мебель = ItemDisplay («основа», хранит id и список барьеров) + Interaction или барьеры как хитбокс.
 * Все связи — через PersistentDataContainer, поэтому мебель переживает рестарт и не требует своей базы данных.
 * Методы вызываются на потоке региона, где стоит мебель (на Folia — из событий игрока рядом).
 */
public final class FurnitureManager {
    private final P3rsoPackPlugin plugin;
    final NamespacedKey idKey, baseKey, barriersKey, seatKey, yawKey;

    public FurnitureManager(P3rsoPackPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "furniture");
        this.baseKey = new NamespacedKey(plugin, "furniture_base");
        this.barriersKey = new NamespacedKey(plugin, "furniture_barriers");
        this.seatKey = new NamespacedKey(plugin, "seat");
        this.yawKey = new NamespacedKey(plugin, "furniture_yaw");
    }

    // ------------------------------------------------------------------ place

    public static float snapYaw(float playerYaw, int step) {
        float yaw = playerYaw + 180f;
        yaw = Math.round(yaw / step) * (float) step;
        yaw %= 360f;
        if (yaw < 0) yaw += 360f;
        return yaw;
    }

    /** Поворот смещения (x, z) на yaw (градусы, как у сущностей Minecraft). */
    static double[] rotate(double x, double z, float yaw) {
        double r = Math.toRadians(yaw);
        double cos = Math.cos(r), sin = Math.sin(r);
        return new double[]{x * cos - z * sin, x * sin + z * cos};
    }

    /**
     * Ставит мебель в блок {@code target}. {@code player} может быть null (API) — тогда без проверки защиты.
     * @return основа или null, если место занято/запрещено
     */
    public ItemDisplay place(FurnitureDef def, Block target, float yaw, Player player, ItemStack sourceItem) {
        World world = target.getWorld();
        List<Block> barriers = new ArrayList<>();
        if (def.hitbox().type() == FurnitureDef.Hitbox.Type.BARRIER) {
            for (int[] off : def.hitbox().barriers()) {
                double[] r = rotate(off[0], off[2], yaw);
                barriers.add(target.getRelative((int) Math.round(r[0]), off[1], (int) Math.round(r[1])));
            }
        }
        if (!target.isReplaceable()) return null;
        for (Block b : barriers) {
            if (!b.isReplaceable() || b.getY() >= world.getMaxHeight() || b.getY() < world.getMinHeight()) return null;
            for (Entity en : world.getNearbyEntities(BoundingBox.of(b))) if (en instanceof LivingEntity) return null;
        }
        if (player != null) {
            var ev = new org.bukkit.event.block.BlockPlaceEvent(target, target.getState(), target.getRelative(0, -1, 0),
                    sourceItem == null ? ItemStack.empty() : sourceItem, player, true, org.bukkit.inventory.EquipmentSlot.HAND);
            if (!ev.callEvent() || !ev.canBuild()) return null;
        }
        ItemStack display = plugin.items().create(def.id(), 1);
        if (display == null) return null;
        Location base = target.getLocation().add(0.5, 0.5, 0.5);
        base.setYaw(yaw);
        base.setPitch(0);
        StringBuilder barrierList = new StringBuilder();
        for (Block b : barriers) {
            if (!barrierList.isEmpty()) barrierList.append(';');
            barrierList.append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ());
        }
        ItemDisplay entity = world.spawn(base, ItemDisplay.class, d -> {
            d.setItemStack(display);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.valueOf(def.displayTransform().toUpperCase(Locale.ROOT)));
            float[] t = def.translation();
            d.setTransformation(new Transformation(new Vector3f(t[0], t[1], t[2]), new AxisAngle4f(),
                    new Vector3f(def.scale(), def.scale(), def.scale()), new AxisAngle4f()));
            if (def.brightness() != null) d.setBrightness(new Display.Brightness(def.brightness(), def.brightness()));
            d.setPersistent(true);
            PersistentDataContainer pdc = d.getPersistentDataContainer();
            pdc.set(idKey, PersistentDataType.STRING, def.id());
            pdc.set(yawKey, PersistentDataType.FLOAT, yaw);
            if (!barrierList.isEmpty()) pdc.set(barriersKey, PersistentDataType.STRING, barrierList.toString());
        });
        String baseId = entity.getUniqueId().toString();
        if (def.hitbox().type() == FurnitureDef.Hitbox.Type.INTERACTION) {
            Location hl = target.getLocation().add(0.5, 0, 0.5);
            world.spawn(hl, Interaction.class, i -> {
                i.setInteractionWidth(def.hitbox().width());
                i.setInteractionHeight(def.hitbox().height());
                i.setResponsive(true);
                i.setPersistent(true);
                i.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, baseId);
            });
        }
        for (Block b : barriers) {
            b.setType(Material.BARRIER, false);
            b.getChunk().getPersistentDataContainer().set(barrierKey(b), PersistentDataType.STRING, baseId);
        }
        playSound(base, def.placeSound());
        return entity;
    }

    private NamespacedKey barrierKey(Block b) {
        return new NamespacedKey(plugin, "fb." + b.getX() + "." + b.getY() + "." + b.getZ());
    }

    // ------------------------------------------------------------------ lookup

    public FurnitureDef defOf(ItemDisplay base) {
        String id = base.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : plugin.state().defs().furniture().get(id);
    }

    public boolean isBase(Entity e) {
        return e instanceof ItemDisplay && e.getPersistentDataContainer().has(idKey, PersistentDataType.STRING);
    }

    /** Основа по сущности-хитбоксу (Interaction) или сама основа. */
    public ItemDisplay baseOf(Entity e) {
        if (isBase(e)) return (ItemDisplay) e;
        String id = e.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING);
        return id == null ? null : findBase(e.getLocation(), id, 4);
    }

    /** Основа по блоку-барьеру. */
    public ItemDisplay baseOf(Block barrier) {
        if (barrier.getType() != Material.BARRIER) return null;
        String id = barrier.getChunk().getPersistentDataContainer().get(barrierKey(barrier), PersistentDataType.STRING);
        return id == null ? null : findBase(barrier.getLocation().toCenterLocation(), id, 8);
    }

    public boolean isFurnitureBarrier(Block b) {
        return b.getType() == Material.BARRIER
                && b.getChunk().getPersistentDataContainer().has(barrierKey(b), PersistentDataType.STRING);
    }

    private ItemDisplay findBase(Location near, String uuid, double radius) {
        UUID id;
        try { id = UUID.fromString(uuid); } catch (IllegalArgumentException ex) { return null; }
        for (ItemDisplay d : near.getWorld().getNearbyEntitiesByType(ItemDisplay.class, near, radius)) {
            if (d.getUniqueId().equals(id)) return d;
        }
        return null;
    }

    // ------------------------------------------------------------------ remove

    /** Проверка защиты (WorldGuard и т.п. слушают BlockBreakEvent). */
    public boolean canBreak(Player player, ItemDisplay base) {
        Block b = base.getLocation().getBlock();
        BlockBreakEvent ev = new BreakCheckEvent(b, player);
        ev.setDropItems(false);
        return ev.callEvent();
    }

    public void remove(ItemDisplay base, Player breaker) {
        FurnitureDef def = defOf(base);
        String uuid = base.getUniqueId().toString();
        Location loc = base.getLocation();
        String barriers = base.getPersistentDataContainer().get(barriersKey, PersistentDataType.STRING);
        if (barriers != null) {
            for (String part : barriers.split(";")) {
                String[] c = part.split(",");
                if (c.length != 3) continue;
                Block b = loc.getWorld().getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                Chunk chunk = b.getChunk();
                String owner = chunk.getPersistentDataContainer().get(barrierKey(b), PersistentDataType.STRING);
                if (uuid.equals(owner)) {
                    chunk.getPersistentDataContainer().remove(barrierKey(b));
                    if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
                }
            }
        }
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 4, 4, 4)) {
            String link = e.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING);
            if (uuid.equals(link)) {
                e.eject();
                e.remove();
            }
        }
        if (def != null) {
            if (def.dropItem() && (breaker == null || breaker.getGameMode() != GameMode.CREATIVE)) {
                ItemStack drop = plugin.items().create(def.id(), 1);
                if (drop != null) loc.getWorld().dropItemNaturally(loc, drop);
            }
            playSound(loc, def.breakSound());
        }
        base.remove();
    }

    // ------------------------------------------------------------------ seats

    /** Посадить игрока на ближайшее свободное сидение. */
    public boolean sit(Player player, ItemDisplay base) {
        FurnitureDef def = defOf(base);
        if (def == null || def.seats().isEmpty() || player.isInsideVehicle()) return false;
        Float yawObj = base.getPersistentDataContainer().get(yawKey, PersistentDataType.FLOAT);
        float yaw = yawObj == null ? base.getYaw() : yawObj;
        Location floor = base.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        for (FurnitureDef.Seat s : def.seats()) {
            double[] r = rotate(s.x(), s.z(), yaw);
            Location pos = floor.clone().add(r[0], s.y(), r[1]);
            if (occupied(pos)) continue;
            double d = pos.distanceSquared(player.getLocation());
            if (d < bestDist) { bestDist = d; best = pos; }
        }
        if (best == null) return false;
        best.setYaw(yaw);
        String baseId = base.getUniqueId().toString();
        ItemDisplay seat = best.getWorld().spawn(best, ItemDisplay.class, s -> {
            s.setPersistent(false);
            s.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, (byte) 1);
            s.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, baseId);
        });
        if (!seat.addPassenger(player)) {
            seat.remove();
            return false;
        }
        // страховка: пустое сидение удаляется само
        seat.getScheduler().runAtFixedRate(plugin, t -> {
            if (seat.getPassengers().isEmpty()) { seat.remove(); t.cancel(); }
        }, null, 40L, 40L);
        return true;
    }

    private boolean occupied(Location pos) {
        for (ItemDisplay d : pos.getWorld().getNearbyEntitiesByType(ItemDisplay.class, pos, 0.3)) {
            if (isSeat(d) && !d.getPassengers().isEmpty()) return true;
        }
        return false;
    }

    public boolean isSeat(Entity e) {
        return e.getPersistentDataContainer().has(seatKey, PersistentDataType.BYTE);
    }

    private void playSound(Location at, String key) {
        if (key == null) return;
        at.getWorld().playSound(Sound.sound(Key.key(key), Sound.Source.BLOCK, 1f, 1f), at.getX(), at.getY(), at.getZ());
    }
}
