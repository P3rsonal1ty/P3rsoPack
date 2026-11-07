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
        Map<String, GlyphDef> glyphs = plugin.state().defs().glyphs();
        var text = plugin.text();
        Map<String, ClickEvent> clicks = new HashMap<>();
        Map<String, HoverEvent<Component>> hovers = new HashMap<>();
        for (EdgeLayout.Element e : def.edge().elements()) {
            if (e.clickable()) {
                List<GuiDef.Action> actions = e.actions();
                clicks.put(e.key(), ClickEvent.callback(audience -> {
                    if (audience instanceof Player p) p.getScheduler().run(plugin, t -> guis.run(p, null, actions), null);
                }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build()));
            }
            if (e.name() != null || !e.lore().isEmpty()) {
                List<Component> tip = new ArrayList<>();
                if (e.name() != null) tip.add(text.parse(e.name(), player));
                tip.addAll(text.lore(e.lore(), player));
                hovers.put(e.key(), HoverEvent.showText(Component.join(JoinConfiguration.newlines(), tip)));
            }
        }
        List<Component> lines = new ArrayList<>();
        for (List<EdgeLayout.Segment> row : def.edge().rows(id -> {
            GlyphDef g = glyphs.get(id);
            return g == null ? 0 : g.advance();
        })) {
            Component line = Component.empty();
            for (EdgeLayout.Segment s : row) {
                Component c = switch (s.type()) {
                    case SHIFT -> text.shift(s.advance());
                    case CLICK_SHIFT -> text.shift(s.advance());
                    case GLYPH -> {
                        GlyphDef g = glyphs.get(s.glyph());
                        yield g == null ? Component.empty() : Component.text(String.valueOf(g.character()))
                                .font(text.glyphFont()).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
                    }
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
        // у диалога обязана быть кнопка: либо «Закрыть» внизу, либо незаметная шириной 1 px
        ActionButton footer = def.edge().closeButton()
                ? ActionButton.builder(Component.translatable("gui.done")).width(100).build()
                : ActionButton.builder(Component.empty()).width(1).build();
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(" ")).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(body, 1024))).build())
                .type(DialogType.notice(footer)));
        player.showDialog(dialog);
    }
}
