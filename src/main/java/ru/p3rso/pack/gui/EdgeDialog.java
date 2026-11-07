package ru.p3rso.pack.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.entity.Player;
import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.model.GlyphDef;
import ru.p3rso.pack.model.Shifts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ЭКСПЕРИМЕНТ: меню у левого края экрана без модов и шейдеров.
 * <p>Тело диалога (1.21.6+) — один кликабельный текст шириной ~1000 px. Элементы шире экрана клиент
 * прижимает к левому краю, поэтому всё, что в начале строки, оказывается у края экрана.
 * Кнопка — глиф-картинка с click-событием; вторая строка под ней — невидимый пробел той же ширины,
 * тоже кликабельный, чтобы кнопка нажималась по всей высоте. Каждая строка добивается сдвигом
 * до одинаковой ширины, поэтому все строки начинаются с одного x.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class EdgeDialog {
    /** Ширина каждой строки: больше любого экрана в пикселях интерфейса (при масштабе ≥ 2). */
    static final int LINE = 980;
    private final P3rsoPackPlugin plugin;

    public EdgeDialog(P3rsoPackPlugin plugin) { this.plugin = plugin; }

    private GlyphDef glyph(String id) {
        return plugin.state().defs().glyphs().get(plugin.state().settings().namespace() + ":" + id);
    }

    private Component shift(int px) { return plugin.text().shift(px); }

    private Component glyphText(GlyphDef g) {
        return Component.text(String.valueOf(g.character())).font(plugin.text().glyphFont())
                .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
    }

    /** Строка: содержимое шириной used px + добивка до LINE. */
    private Component line(Component content, int used) {
        return Component.empty().append(content).append(shift(LINE - used));
    }

    private ClickEvent callback(Consumer<Player> onClick) {
        return ClickEvent.callback(audience -> {
            if (audience instanceof Player p) p.getScheduler().run(plugin, t -> onClick.accept(p), null);
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build());
    }

    private void button(List<Component> lines, String glyphId, String tooltip, Consumer<Player> onClick) {
        GlyphDef g = glyph(glyphId);
        if (g == null) return;
        ClickEvent click = callback(onClick);
        HoverEvent<Component> hover = HoverEvent.showText(plugin.text().parse(tooltip, null));
        int w = g.advance();
        lines.add(line(glyphText(g).clickEvent(click).hoverEvent(hover), w));
        lines.add(line(shift(w).clickEvent(click).hoverEvent(hover), w));
    }

    public void show(Player player) {
        List<Component> lines = new ArrayList<>();
        GlyphDef logo = glyph("dlg_logo");
        if (logo != null) {
            lines.add(line(glyphText(logo), logo.advance()));
            lines.add(line(Component.empty(), 0));
            lines.add(line(Component.empty(), 0));
            lines.add(line(Component.empty(), 0));
        }
        button(lines, "dlg_btn_home", "<gray>Полноэкранное меню /menu", p -> plugin.guis().open(p, ns("hub_menu")));
        button(lines, "dlg_btn_rpg", "<#7dffc4>Основной сервер", p -> p.performCommand("play"));
        button(lines, "dlg_btn_games", "<#c9b6ff>Скоро", p -> p.sendActionBar(plugin.text().parse("<#c9b6ff>Мини-игры скоро!", p)));
        button(lines, "dlg_btn_profile", "<gray>Игрок: <white>" + player.getName(),
                p -> p.sendMessage(plugin.text().parse("<#b9a6ff>Профиль <white>%player%", p)));
        button(lines, "dlg_btn_shop", "<#ffd36e>Рубиновая лавка", p -> plugin.guis().open(p, ns("shop")));
        button(lines, "dlg_btn_close", "<#ff7a7a>Закрыть меню",
                p -> p.playSound(Sound.sound(Key.key("block.chest.close"), Sound.Source.MASTER, 0.5f, 1.2f)));

        Component body = Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
        // Диалогу нужна хотя бы одна кнопка: делаем её шириной 1 px без надписи
        ActionButton invisible = ActionButton.builder(Component.empty()).width(1).build();
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(" ")).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(body, 1024))).build())
                .type(DialogType.notice(invisible)));
        player.showDialog(dialog);
    }

    private String ns(String id) { return plugin.state().settings().namespace() + ":" + id; }
}
