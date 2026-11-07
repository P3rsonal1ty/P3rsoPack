package ru.p3rso.pack.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.entity.Player;
import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.EdgeLayout;
import ru.p3rso.pack.model.GlyphDef;
import ru.p3rso.pack.model.GuiDef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Показ меню у края экрана ({@code type: edge}) как диалога. Раскладка по строкам — {@link EdgeLayout#rows}.
 */
@SuppressWarnings("UnstableApiUsage")
final class EdgeMenu {
    private final P3rsoPackPlugin plugin;
    private final GuiManager guis;

    EdgeMenu(P3rsoPackPlugin plugin, GuiManager guis) {
        this.plugin = plugin;
        this.guis = guis;
    }

    void show(Player player, GuiDef def) {
        EdgeLayout.Tab tab = def.edge().defaultTab();
        show(player, def, tab == null ? null : tab.key());
    }

    private ClickEvent callback(Player owner, java.util.function.Consumer<Player> action) {
        return ClickEvent.callback(audience -> {
            if (audience instanceof Player p) p.getScheduler().run(plugin, t -> action.accept(p), null);
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build());
    }

    void show(Player player, GuiDef def, String tabKey) {
        EdgeLayout edge = def.edge();
        Map<String, GlyphDef> glyphs = plugin.state().defs().glyphs();
        var text = plugin.text();
        EdgeLayout.Tab current = tabKey == null ? null : edge.tab(tabKey);
        List<EdgeLayout.Element> visible = edge.visible(current == null ? null : current.key());
        Map<String, ClickEvent> clicks = new HashMap<>();
        Map<String, HoverEvent<Component>> hovers = new HashMap<>();
        for (EdgeLayout.Element e : visible) {
            if (e.tab() != null) {
                String target = e.tab();
                clicks.put(e.key(), callback(player, p -> {
                    p.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("ui.button.click"),
                            net.kyori.adventure.sound.Sound.Source.MASTER, 0.4f, 1.2f));
                    show(p, def, target);
                }));
            } else if (e.clickable()) {
                List<GuiDef.Action> actions = e.actions();
                boolean stays = actions.stream().noneMatch(a -> a.type() == GuiDef.Action.Type.CLOSE || a.type() == GuiDef.Action.Type.OPEN
                        || a.type() == GuiDef.Action.Type.COMMAND || a.type() == GuiDef.Action.Type.CONSOLE);
                String keep = current == null ? null : current.key();
                clicks.put(e.key(), callback(player, p -> {
                    guis.run(p, null, actions);
                    // меню остаётся открытым: перерисуем его, чтобы снять фокус с нажатого текста
                    if (stays && edge.hasTabs()) show(p, def, keep);
                }));
            }
            if (e.name() != null || !e.lore().isEmpty()) {
                List<Component> tip = new ArrayList<>();
                if (e.name() != null) tip.add(text.parse(e.name(), player));
                tip.addAll(text.lore(e.lore(), player));
                hovers.put(e.key(), HoverEvent.showText(Component.join(JoinConfiguration.newlines(), tip)));
            }
        }
        Map<String, String> override = new HashMap<>();
        if (current != null && current.activeGlyph() != null) override.put("tab." + current.key(), current.activeGlyph());

        List<Component> lines = new ArrayList<>();
        for (List<EdgeLayout.Segment> row : edge.rows(visible, id -> {
            GlyphDef g = glyphs.get(id);
            return g == null ? 0 : g.advance();
        }, override)) {
            Component line = Component.empty();
            for (EdgeLayout.Segment s : row) {
                Component c = switch (s.type()) {
                    case SHIFT, CLICK_SHIFT -> text.shift(s.advance());
                    case GLYPH -> glyph(glyphs.get(s.glyph()));
                };
                if (s.element() != null) {
                    ClickEvent click = clicks.get(s.element().key());
                    HoverEvent<Component> hover = hovers.get(s.element().key());
                    if (click != null) c = c.clickEvent(click);
                    if (hover != null) c = c.hoverEvent(hover);
                }
                line = line.append(c);
            }
            lines.add(line);
        }
        Component body = Component.join(JoinConfiguration.newlines(), lines);
        ActionButton footer = edge.closeButton()
                ? ActionButton.builder(Component.translatable("gui.done")).width(100)
                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                            (view, audience) -> { if (audience instanceof Player p) p.getScheduler().run(plugin, t -> p.closeDialog(), null); },
                            ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build()))
                    .build()
                : ActionButton.builder(Component.empty()).width(1).build();
        // NONE: при переключении вкладки сервер сразу присылает новый диалог, без мигания
        DialogBase.DialogAfterAction after = edge.hasTabs() ? DialogBase.DialogAfterAction.NONE : DialogBase.DialogAfterAction.CLOSE;
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(" ")).canCloseWithEscape(true).pause(false).afterAction(after)
                        .body(List.of(DialogBody.plainMessage(body, 1024))).build())
                .type(DialogType.notice(footer)));
        player.showDialog(dialog);
    }

    private Component glyph(GlyphDef g) {
        if (g == null) return Component.empty();
        return Component.text(String.valueOf(g.character())).font(plugin.text().glyphFont())
                .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
    }
}
