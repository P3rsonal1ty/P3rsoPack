package ru.p3rso.pack.gui;

import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.GlyphDef;
import ru.p3rso.pack.model.GuiDef;
import ru.p3rso.pack.model.GuiGeometry;
import ru.p3rso.pack.model.Settings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Открытие окон из guis/ и выполнение действий. Все вызовы — на потоке игрока (Folia). */
public final class GuiManager {
    private final P3rsoPackPlugin plugin;
    private final InventoryBackup backup;

    public GuiManager(P3rsoPackPlugin plugin) {
        this.plugin = plugin;
        this.backup = new InventoryBackup(plugin);
    }

    public InventoryBackup backup() { return backup; }

    /** Открывает окно; можно вызывать с любого потока — переключится на планировщик игрока. */
    public boolean open(Player player, String id) {
        GuiDef def = plugin.state().defs().guis().get(id);
        if (def == null) return false;
        if (def.permission() != null && !player.hasPermission(def.permission())) {
            player.sendActionBar(plugin.text().parse("<red>Нет доступа к этому разделу", player));
            player.playSound(Sound.sound(Key.key("entity.villager.no"), Sound.Source.MASTER, 1f, 1f));
            return false;
        }
        player.getScheduler().run(plugin, t -> openNow(player, def), null);
        return true;
    }

    /** Окно, привязанное к команде (guis/*.yml → command:). */
    public GuiDef byCommand(String command) {
        for (GuiDef g : plugin.state().defs().guis().values()) if (command.equals(g.command())) return g;
        return null;
    }

    private void openNow(Player player, GuiDef def) {
        GuiHolder holder = new GuiHolder(def);
        Inventory inv = Bukkit.createInventory(holder, def.rows() * 9, title(def, player));
        holder.inventory(inv);
        if (def.hidePlayerInventory()) backup.hide(player);
        fill(holder, player);
        player.openInventory(inv);
        run(player, holder, def.openActions());
    }

    /**
     * Заголовок: [сдвиг влево] [глиф фона] [сдвиг обратно] текст.
     * Текст заголовка в окне начинается с x=8 от края, поэтому по умолчанию фон сдвигается на −8.
     */
    public Component title(GuiDef def, Player player) {
        var text = plugin.text();
        Component title = text.parse(def.title(), player);
        if (def.background() == null) return text.shift(def.titleOffset()).append(title);
        GlyphDef g = plugin.state().defs().glyphs().get(def.background());
        if (g == null) return title;
        Settings s = plugin.state().settings();
        int bgOffset = def.backgroundOffset() == Integer.MIN_VALUE ? s.defaultBackgroundOffset() : def.backgroundOffset();
        int back = def.titleOffset() - bgOffset - g.advance();
        return Component.empty()
                .append(text.shift(bgOffset))
                .append(text.glyph(g.id()))
                .append(text.shift(back))
                .append(title);
    }

    void fill(GuiHolder holder, Player player) {
        Inventory inv = holder.getInventory();
        GuiDef def = holder.def();
        inv.clear();
        if (def.hidePlayerInventory()) backup.purge(player);
        for (GuiDef.GuiItem it : def.items()) {
            ItemStack stack = render(it, player);
            if (stack == null) continue;
            for (int slot : it.slots()) {
                if (slot < def.topSize()) inv.setItem(slot, stack.clone());
                else if (def.hidePlayerInventory()) player.getInventory().setItem(GuiGeometry.playerSlot(def.rows(), slot), stack.clone());
            }
        }
    }

    private ItemStack render(GuiDef.GuiItem it, Player player) {
        ItemStack stack;
        if (it.item() != null) {
            stack = plugin.items().create(it.item(), it.amount());
            if (stack == null) return null;
        } else {
            Material m = Material.matchMaterial(it.material());
            if (m == null || !m.isItem() || m.isAir()) return null;
            stack = ItemStack.of(m, it.amount());
        }
        var text = plugin.text();
        stack.editMeta(meta -> {
            if (it.name() != null) meta.customName(text.parse(it.name(), player)
                    .decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC, net.kyori.adventure.text.format.TextDecoration.State.FALSE));
            if (!it.lore().isEmpty()) meta.lore(text.lore(it.lore(), player));
            if (it.itemModel() != null) meta.setItemModel(NamespacedKey.fromString(it.itemModel()));
            if (it.tooltipStyle() != null) meta.setTooltipStyle(NamespacedKey.fromString(it.tooltipStyle()));
            meta.getPersistentDataContainer().set(backup.menuItemKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /** Действия для клика: сначала общие (actions), потом для конкретного типа клика. */
    void click(Player player, GuiHolder holder, int slot, ClickType click) {
        GuiDef.GuiItem it = holder.itemAt(slot);
        if (it == null) return;
        List<GuiDef.Action> list = new ArrayList<>(it.actions().getOrDefault("any", List.of()));
        String type = switch (click) {
            case LEFT -> "left";
            case RIGHT -> "right";
            case SHIFT_LEFT -> "shift_left";
            case SHIFT_RIGHT -> "shift_right";
            case MIDDLE -> "middle";
            case DROP, CONTROL_DROP -> "drop";
            default -> null;
        };
        if (type != null) list.addAll(it.actions().getOrDefault(type, List.of()));
        run(player, holder, list);
    }

    private void run(Player player, GuiHolder holder, List<GuiDef.Action> actions) {
        var text = plugin.text();
        for (GuiDef.Action a : actions) {
            String v = a.value().replace("%player%", player.getName());
            switch (a.type()) {
                case COMMAND -> player.performCommand(v);
                case CONSOLE -> Bukkit.getGlobalRegionScheduler().execute(plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), v));
                case MESSAGE -> player.sendMessage(text.parse(a.value(), player));
                case ACTIONBAR -> player.sendActionBar(text.parse(a.value(), player));
                case OPEN -> open(player, v.split("\\s+")[0]);
                case CLOSE -> player.getScheduler().run(plugin, t -> player.closeInventory(), null);
                case REFRESH -> player.getScheduler().run(plugin, t -> {
                    if (player.getOpenInventory().getTopInventory().getHolder(false) == holder) fill(holder, player);
                }, null);
                case SOUND -> {
                    String[] p = v.split("\\s+");
                    float vol = p.length > 1 ? parseFloat(p[1], 1f) : 1f, pitch = p.length > 2 ? parseFloat(p[2], 1f) : 1f;
                    player.playSound(Sound.sound(Key.key(p[0]), Sound.Source.MASTER, vol, pitch));
                }
                case GIVE -> {
                    String[] p = v.split("\\s+");
                    int amount = p.length > 1 ? (int) parseFloat(p[1], 1) : 1;
                    ItemStack stack = plugin.items().create(p[0], amount);
                    if (stack != null) plugin.give(player, stack);
                }
            }
        }
    }

    private static float parseFloat(String s, float def) {
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return def; }
    }

    /** Закрыть все окна P3rsoPack (перед /reload), чтобы не остались окна со старыми действиями. */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof GuiHolder) p.closeInventory();
            }, null);
        }
    }
}
