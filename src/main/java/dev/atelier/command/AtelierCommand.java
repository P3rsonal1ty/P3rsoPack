package dev.atelier.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.atelier.AtelierPlugin;
import dev.atelier.State;
import dev.atelier.config.Problems;
import dev.atelier.model.Definitions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.key.Key;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * /atelier (алиас /atl):
 * reload | give &lt;игроки&gt; &lt;предмет&gt; [кол-во] | gui &lt;окно&gt; [игроки] | list [тип] | errors | pack [info|send]
 */
public final class AtelierCommand {
    private AtelierCommand() {}

    public static LiteralCommandNode<CommandSourceStack> build(AtelierPlugin plugin) {
        SuggestionProvider<CommandSourceStack> itemIds = ids(plugin, d -> d.items().keySet());
        SuggestionProvider<CommandSourceStack> guiIds = ids(plugin, d -> d.guis().keySet());

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("atelier")
                .requires(s -> s.getSender().hasPermission("atelier.admin"))
                .executes(ctx -> help(plugin, ctx.getSource().getSender()));

        root.then(Commands.literal("reload").executes(ctx -> {
            CommandSender s = ctx.getSource().getSender();
            if (!plugin.reload(s)) plugin.msg(s, "<yellow>Перезагрузка уже идёт");
            else plugin.msg(s, "<gray>Перезагрузка Atelier…");
            return Command.SINGLE_SUCCESS;
        }));

        root.then(Commands.literal("give")
                .then(Commands.argument("targets", ArgumentTypes.players())
                        .then(Commands.argument("item", ArgumentTypes.key()).suggests(itemIds)
                                .executes(ctx -> give(plugin, ctx, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                                        .executes(ctx -> give(plugin, ctx, IntegerArgumentType.getInteger(ctx, "amount")))))));

        root.then(Commands.literal("gui")
                .then(Commands.argument("gui", ArgumentTypes.key()).suggests(guiIds)
                        .executes(ctx -> {
                            if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                                plugin.msg(ctx.getSource().getSender(), "<red>Укажите игрока: /atelier gui <окно> <игрок>");
                                return 0;
                            }
                            return openGui(plugin, ctx, List.of(p));
                        })
                        .then(Commands.argument("targets", ArgumentTypes.players())
                                .executes(ctx -> openGui(plugin, ctx, players(ctx))))));

        root.then(Commands.literal("list")
                .executes(ctx -> list(plugin, ctx.getSource().getSender(), "items"))
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> {
                            for (String t : List.of("items", "blocks", "furniture", "guis", "glyphs")) b.suggest(t);
                            return b.buildFuture();
                        })
                        .executes(ctx -> list(plugin, ctx.getSource().getSender(), StringArgumentType.getString(ctx, "type")))));

        root.then(Commands.literal("errors").executes(ctx -> {
            CommandSender s = ctx.getSource().getSender();
            List<Problems.Problem> all = plugin.state().problems().all();
            if (all.isEmpty()) plugin.msg(s, "<green>Ошибок и предупреждений нет");
            for (Problems.Problem p : all) {
                plugin.msg(s, (p.level() == Problems.Level.ERROR ? "<red>✖ " : "<yellow>⚠ ") + "<gray>"
                        + plugin.esc(p.file() + (p.line() > 0 ? ":" + p.line() : "")) + "</gray> " + plugin.esc(p.message()));
            }
            return Command.SINGLE_SUCCESS;
        }));

        root.then(Commands.literal("pack")
                .executes(ctx -> packInfo(plugin, ctx.getSource().getSender()))
                .then(Commands.literal("info").executes(ctx -> packInfo(plugin, ctx.getSource().getSender())))
                .then(Commands.literal("send")
                        .executes(ctx -> {
                            if (ctx.getSource().getExecutor() instanceof Player p) {
                                p.getScheduler().run(plugin, t -> plugin.packs().send(p), null);
                                return Command.SINGLE_SUCCESS;
                            }
                            plugin.msg(ctx.getSource().getSender(), "<red>Укажите игроков: /atelier pack send <игроки>");
                            return 0;
                        })
                        .then(Commands.argument("targets", ArgumentTypes.players()).executes(ctx -> {
                            List<Player> ps = players(ctx);
                            for (Player p : ps) p.getScheduler().run(plugin, t -> plugin.packs().send(p), null);
                            plugin.msg(ctx.getSource().getSender(), "<green>Пак отправлен: " + ps.size() + " игрокам");
                            return Command.SINGLE_SUCCESS;
                        }))));
        return root.build();
    }

    private static SuggestionProvider<CommandSourceStack> ids(AtelierPlugin plugin, Function<Definitions, Collection<String>> f) {
        return (ctx, b) -> {
            String rem = b.getRemaining().toLowerCase(Locale.ROOT);
            String ns = plugin.state().settings().namespace() + ":";
            for (String id : f.apply(plugin.state().defs())) {
                if (id.startsWith(rem) || (id.startsWith(ns) && id.substring(ns.length()).startsWith(rem))) b.suggest(id);
            }
            return b.buildFuture();
        };
    }

    /** Key-аргумент без namespace превращается в minecraft:… — подставляем namespace Atelier. */
    private static String resolve(AtelierPlugin plugin, Key key, Set<String> known) {
        String full = key.asString();
        if (known.contains(full)) return full;
        String alt = plugin.state().settings().namespace() + ":" + key.value();
        return known.contains(alt) ? alt : null;
    }

    private static List<Player> players(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getArgument("targets", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
    }

    private static int give(AtelierPlugin plugin, CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        State st = plugin.state();
        String id = resolve(plugin, ctx.getArgument("item", Key.class), st.defs().items().keySet());
        if (id == null) {
            plugin.msg(s, "<red>Нет такого предмета. Список: /atelier list items");
            return 0;
        }
        List<Player> targets = players(ctx);
        for (Player p : targets) {
            int left = amount;
            while (left > 0) {
                ItemStack stack = plugin.items().create(id, 1);
                int n = Math.min(left, stack.getMaxStackSize());
                stack.setAmount(n);
                plugin.give(p, stack);
                left -= n;
            }
        }
        plugin.msg(s, "<green>Выдано <white>" + id + " ×" + amount + "</white> игрокам: " + targets.size());
        return Command.SINGLE_SUCCESS;
    }

    private static int openGui(AtelierPlugin plugin, CommandContext<CommandSourceStack> ctx, List<Player> targets) {
        String id = resolve(plugin, ctx.getArgument("gui", Key.class), plugin.state().defs().guis().keySet());
        if (id == null) {
            plugin.msg(ctx.getSource().getSender(), "<red>Нет такого окна. Список: /atelier list guis");
            return 0;
        }
        for (Player p : targets) plugin.guis().open(p, id);
        return Command.SINGLE_SUCCESS;
    }

    private static int list(AtelierPlugin plugin, CommandSender s, String type) {
        Definitions d = plugin.state().defs();
        Collection<String> ids = switch (type.toLowerCase(Locale.ROOT)) {
            case "items" -> d.items().keySet();
            case "blocks" -> d.blocks().keySet();
            case "furniture" -> d.furniture().keySet();
            case "guis" -> d.guis().keySet();
            case "glyphs" -> d.glyphs().keySet();
            default -> null;
        };
        if (ids == null) {
            plugin.msg(s, "<red>Типы: items, blocks, furniture, guis, glyphs");
            return 0;
        }
        plugin.msg(s, "<gold>" + type + " (" + ids.size() + "): <white>" + plugin.esc(String.join(", ", ids)));
        return Command.SINGLE_SUCCESS;
    }

    private static int packInfo(AtelierPlugin plugin, CommandSender s) {
        State st = plugin.state();
        if (st.pack() == null) {
            plugin.msg(s, "<red>Пак ещё не собран");
            return 0;
        }
        String url = plugin.packs().url(st, s instanceof Player p ? p.getVirtualHost() : null);
        plugin.msg(s, "<gold>Ресурспак: <white>" + (st.pack().length / 1024) + " КБ</white>\n<gray>sha1:</gray> " + st.packSha1()
                + "\n<gray>url:</gray> <click:copy_to_clipboard:'" + url + "'><u>" + plugin.esc(url) + "</u></click>"
                + "\n<gray>режим:</gray> " + st.settings().hostMode().name().toLowerCase(Locale.ROOT)
                + ", отправка: " + st.settings().sendPhase().name().toLowerCase(Locale.ROOT)
                + "\n<gray>файл:</gray> plugins/Atelier/output/pack.zip");
        return Command.SINGLE_SUCCESS;
    }

    private static int help(AtelierPlugin plugin, CommandSender s) {
        plugin.msg(s, String.join("\n",
                "<gold>Atelier</gold> <gray>— команды:",
                "<yellow>/atelier reload</yellow> <gray>— перечитать конфиги и пересобрать пак",
                "<yellow>/atelier give <игроки> <предмет> [кол-во]",
                "<yellow>/atelier gui <окно> [игроки]",
                "<yellow>/atelier list [items|blocks|furniture|guis|glyphs]",
                "<yellow>/atelier errors</yellow> <gray>— ошибки последней загрузки",
                "<yellow>/atelier pack [info|send [игроки]]"));
        return Command.SINGLE_SUCCESS;
    }
}
