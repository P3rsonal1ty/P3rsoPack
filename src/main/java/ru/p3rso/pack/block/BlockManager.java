package ru.p3rso.pack.block;

import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.BlockDef;
import ru.p3rso.pack.model.NoteBlockStates;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Кастомные блоки: состояние нотного блока ↔ определение, дропы, скорость копания. */
public final class BlockManager {
    private static final Instrument[] INSTRUMENTS = Instrument.values();

    private final P3rsoPackPlugin plugin;
    private final NamespacedKey miningKey;

    public BlockManager(P3rsoPackPlugin plugin) {
        this.plugin = plugin;
        this.miningKey = new NamespacedKey(plugin, "mining_speed");
        if (INSTRUMENTS.length < NoteBlockStates.INSTRUMENTS.size()) {
            plugin.getLogger().warning("org.bukkit.Instrument содержит " + INSTRUMENTS.length + " значений, ожидалось "
                    + NoteBlockStates.INSTRUMENTS.size() + " — часть вариаций блоков недоступна");
        }
    }

    /** Определение кастомного блока по состоянию или null (ванильный нотный блок/другой блок). */
    public BlockDef defOf(BlockData data) {
        if (!(data instanceof NoteBlock nb)) return null;
        int inst = nb.getInstrument().ordinal();
        int v = NoteBlockStates.variation(inst, nb.getNote().getId());
        if (v == 0) return null;
        return plugin.state().blockByVariation().get(v);
    }

    public BlockDef defOf(Block block) {
        return block.getType() == Material.NOTE_BLOCK ? defOf(block.getBlockData()) : null;
    }

    public NoteBlock dataFor(BlockDef def) {
        Integer v = plugin.state().variations().get(def.id());
        if (v == null) return null;
        return stateData(v);
    }

    public static NoteBlock stateData(int variation) {
        NoteBlock nb = (NoteBlock) Material.NOTE_BLOCK.createBlockData();
        nb.setInstrument(INSTRUMENTS[NoteBlockStates.instrument(variation)]);
        nb.setNote(new Note(NoteBlockStates.note(variation)));
        nb.setPowered(false);
        return nb;
    }

    /** Ванильный нотный блок — всегда в состоянии вариации 0, чтобы не совпасть с кастомным. */
    public static NoteBlock vanillaData() { return stateData(0); }

    // ------------------------------------------------------------------ drops

    public List<ItemStack> drops(BlockDef def, ItemStack tool, boolean explosion) {
        List<ItemStack> out = new ArrayList<>();
        if (!explosion && def.silkTouchSelf() && tool != null && tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            ItemStack self = plugin.items().create(def.id(), 1);
            if (self != null) out.add(self);
            return out;
        }
        if (!explosion && def.requiresTool() && !toolMatches(def.tool(), tool)) return out;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (BlockDef.Drop d : def.drops()) {
            if (d.chance() < 1 && rnd.nextDouble() >= d.chance()) continue;
            int amount = d.min() == d.max() ? d.min() : rnd.nextInt(d.min(), d.max() + 1);
            if (amount <= 0) continue;
            ItemStack stack = d.item() != null ? plugin.items().create(d.item(), amount) : vanilla(d.material(), amount);
            if (stack != null) out.add(stack);
        }
        return out;
    }

    private static ItemStack vanilla(String material, int amount) {
        Material m = Material.matchMaterial(material);
        return m == null ? null : ItemStack.of(m, amount);
    }

    public int exp(BlockDef def) {
        if (def.expMax() <= 0) return 0;
        return def.expMin() == def.expMax() ? def.expMin() : ThreadLocalRandom.current().nextInt(def.expMin(), def.expMax() + 1);
    }

    public void playSound(Location at, String key, BlockDef.Sounds s) {
        if (key == null) return;
        at.getWorld().playSound(Sound.sound(Key.key(key), Sound.Source.BLOCK, s.volume(), s.pitch()), at.getX(), at.getY(), at.getZ());
    }

    // ------------------------------------------------------------------ mining speed

    /**
     * Скорость копания задаётся атрибутом block_break_speed на время копания: клиент сам рисует трещины
     * и ломает блок с нужной скоростью. Множитель = (наша скорость) / (скорость ванильного нотного блока).
     */
    public void startMining(Player player, BlockDef def, ItemStack tool) {
        double vanilla = toolSpeed(tool, "axe") / 0.8 / 30.0;
        boolean canHarvest = !def.requiresTool() || toolMatches(def.tool(), tool);
        double custom;
        if (def.hardness() <= 0) custom = 1000;
        else custom = toolSpeed(tool, def.tool()) / def.hardness() / (canHarvest ? 30.0 : 100.0);
        double mult = Math.clamp(custom / vanilla, 0.0, 1024.0);
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) return;
        attr.removeModifier(miningKey);
        if (Math.abs(mult - 1.0) > 1e-6) {
            attr.addTransientModifier(new AttributeModifier(miningKey, mult - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    public void stopMining(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr != null && attr.getModifier(miningKey) != null) attr.removeModifier(miningKey);
    }

    public static boolean toolMatches(String tool, ItemStack item) {
        if (tool.equals("none")) return true;
        if (item == null || item.isEmpty()) return false;
        return toolType(item.getType()).equals(tool);
    }

    static String toolType(Material m) {
        String n = m.name();
        if (n.endsWith("_PICKAXE")) return "pickaxe";
        if (n.endsWith("_AXE")) return "axe";
        if (n.endsWith("_SHOVEL")) return "shovel";
        if (n.endsWith("_HOE")) return "hoe";
        if (n.endsWith("_SWORD")) return "sword";
        if (m == Material.SHEARS) return "shears";
        return "none";
    }

    /** Скорость инструмента для блока, который «копается» этим типом инструмента. */
    static double toolSpeed(ItemStack item, String forTool) {
        if (item == null || item.isEmpty() || forTool.equals("none")) return 1.0;
        Material m = item.getType();
        if (!toolType(m).equals(forTool)) return 1.0;
        if (m == Material.SHEARS) return 5.0;
        String n = m.name().toLowerCase(Locale.ROOT);
        if (forTool.equals("sword")) return 1.5;
        if (n.startsWith("wooden_")) return 2;
        if (n.startsWith("stone_")) return 4;
        if (n.startsWith("copper_")) return 5;
        if (n.startsWith("iron_")) return 6;
        if (n.startsWith("diamond_")) return 8;
        if (n.startsWith("netherite_")) return 9;
        if (n.startsWith("golden_")) return 12;
        return 1.0;
    }
}
