package ru.p3rso.pack.api;

import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.BlockDef;
import ru.p3rso.pack.model.FurnitureDef;
import ru.p3rso.pack.model.ItemDef;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.Set;

/**
 * Публичный API для других плагинов. Добавьте {@code depend: [P3rsoPack]} (или softdepend) в plugin.yml.
 * <pre>{@code
 * ItemStack ruby = P3rsoPack.item("p3rso:ruby").orElseThrow();
 * Optional<String> id = P3rsoPack.idOf(player.getInventory().getItemInMainHand());
 * }</pre>
 * Id можно указывать без namespace — тогда подставится namespace из config.yml.
 */
public final class P3rsoPack {
    private P3rsoPack() {}

    private static P3rsoPackPlugin plugin() {
        P3rsoPackPlugin p = P3rsoPackPlugin.instance();
        if (p == null) throw new IllegalStateException("P3rsoPack не загружен");
        return p;
    }

    public static String fullId(String id) {
        return id.contains(":") ? id : plugin().state().settings().namespace() + ":" + id;
    }

    /** Новый ItemStack кастомного предмета. */
    public static Optional<ItemStack> item(String id) { return item(id, 1); }

    public static Optional<ItemStack> item(String id, int amount) {
        return Optional.ofNullable(plugin().items().create(fullId(id), amount));
    }

    /** Id кастомного предмета (или пусто, если это обычный предмет). */
    public static Optional<String> idOf(ItemStack stack) { return Optional.ofNullable(plugin().items().idOf(stack)); }

    public static boolean isCustom(ItemStack stack) { return idOf(stack).isPresent(); }

    public static boolean is(ItemStack stack, String id) { return idOf(stack).map(fullId(id)::equals).orElse(false); }

    public static Optional<ItemDef> definition(String id) { return Optional.ofNullable(plugin().state().defs().items().get(fullId(id))); }

    public static Set<String> itemIds() { return plugin().state().defs().items().keySet(); }

    public static Set<String> guiIds() { return plugin().state().defs().guis().keySet(); }

    /** Открыть окно из guis/. Можно вызывать с любого потока. */
    public static boolean openGui(Player player, String id) { return plugin().guis().open(player, fullId(id)); }

    /** Компонент-глиф для своих сообщений, action bar, scoreboard. */
    public static Component glyph(String id) { return plugin().text().glyph(id); }

    /** MiniMessage с тегами P3rsoPack ({@code <glyph:id>}, {@code <shift:N>}). */
    public static Component text(String miniMessage, Player player) { return plugin().text().parse(miniMessage, player); }

    /** Id кастомного блока в этом месте. Вызывать на потоке региона блока. */
    public static Optional<String> blockAt(Block block) {
        return Optional.ofNullable(plugin().blocks().defOf(block)).map(BlockDef::id);
    }

    /** Поставить кастомный блок (без событий и проверки защиты). Вызывать на потоке региона. */
    public static boolean placeBlock(Location at, String id) {
        BlockDef def = plugin().state().defs().blocks().get(fullId(id));
        if (def == null) return false;
        NoteBlock data = plugin().blocks().dataFor(def);
        if (data == null) return false;
        at.getBlock().setBlockData(data, false);
        return true;
    }

    /** Поставить мебель (без проверки защиты). Вызывать на потоке региона. */
    public static Optional<Entity> placeFurniture(Location at, String id, float yaw) {
        FurnitureDef def = plugin().state().defs().furniture().get(fullId(id));
        if (def == null) return Optional.empty();
        return Optional.ofNullable(plugin().furniture().place(def, at.getBlock(), yaw, null, null));
    }

    /** Id мебели по её сущности (основа или хитбокс). */
    public static Optional<String> furnitureOf(Entity entity) {
        ItemDisplay base = plugin().furniture().baseOf(entity);
        return base == null ? Optional.empty() : Optional.ofNullable(plugin().furniture().defOf(base)).map(FurnitureDef::id);
    }

    /** SHA-1 текущего ресурспака (для отправки пака своим плагином/прокси). */
    public static Optional<String> packSha1() { return Optional.ofNullable(plugin().state().packSha1()); }
}
