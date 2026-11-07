package dev.atelier.text;

import dev.atelier.AtelierPlugin;
import dev.atelier.model.GlyphDef;
import dev.atelier.model.Shifts;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * MiniMessage с дополнительными тегами:
 * <ul>
 *   <li>{@code <glyph:id>} — вставить глиф (картинку) из glyphs/</li>
 *   <li>{@code <shift:-20>} — сдвинуть текст на N пикселей</li>
 * </ul>
 * Плейсхолдеры: {@code %player%}.
 */
public final class TextService {
    private final AtelierPlugin plugin;
    private final MiniMessage mm;

    public TextService(AtelierPlugin plugin) {
        this.plugin = plugin;
        TagResolver glyph = TagResolver.resolver("glyph", (args, ctx) -> {
            String id = args.popOr("<glyph:id> — укажите id глифа").value();
            return Tag.selfClosingInserting(glyph(id));
        });
        TagResolver shift = TagResolver.resolver("shift", (args, ctx) -> {
            String v = args.popOr("<shift:N> — укажите число пикселей").value();
            int px;
            try {
                px = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw ctx.newException("<shift:" + v + ">: ожидалось целое число", args);
            }
            return Tag.selfClosingInserting(shift(px));
        });
        this.mm = MiniMessage.builder().tags(TagResolver.resolver(TagResolver.standard(), glyph, shift)).build();
    }

    public Key glyphFont() { return Key.key(plugin.state().settings().namespace(), "glyphs"); }

    public Key shiftFont() { return Key.key(plugin.state().settings().namespace(), "shift"); }

    /** Глиф без окраски и тени (иначе картинка тонируется цветом текста). Неизвестный id → пустой компонент. */
    public Component glyph(String id) {
        String full = id.contains(":") ? id : plugin.state().settings().namespace() + ":" + id;
        GlyphDef g = plugin.state().defs().glyphs().get(full);
        if (g == null) return Component.empty();
        return Component.text(String.valueOf(g.character()))
                .font(glyphFont()).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none())
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false);
    }

    public Component shift(int px) {
        if (px == 0) return Component.empty();
        return Component.text(Shifts.of(px)).font(shiftFont());
    }

    public Component parse(String text, Player player) {
        if (text == null) return Component.empty();
        String s = player == null ? text : text.replace("%player%", player.getName());
        return mm.deserialize(s);
    }

    /** Для имён и лора: убираем ванильный курсив, лор по умолчанию серый. */
    public Component itemName(String text) {
        return parse(text, null).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> lore(List<String> lines, Player player) {
        return lines.stream().map(l -> parse(l, player)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                .colorIfAbsent(NamedTextColor.GRAY)).toList();
    }

    public MiniMessage mini() { return mm; }
}
