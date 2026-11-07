package ru.p3rso.pack.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import ru.p3rso.pack.P3rsoPackPlugin;
import ru.p3rso.pack.State;
import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.model.Definitions;
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
 * /p3rsopack (алиас /pp):
 * reload | give &lt;игроки&gt; &lt;предмет&gt; [кол-во] | gui &lt;окно&gt; [игроки] | list [тип] | errors | pack [info|send]
 */
public final class PackCommand {
    private PackCommand() {}

    public static LiteralCommandNode<CommandSourceStack> build(P3rsoPackPlugin plugin) {
        SuggestionProvider<CommandSourceStack> itemIds = ids(plugin, d -> d.items().keySet());
        SuggestionProvider<CommandSourceStack> guiIds = ids(plugin, d -> d.guis().keySet());

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("p3rsopack")
                .requires(s -> s.getSender().hasPermission("p3rsopack.admin"))
                .executes(ctx -> help(plugin, ctx.getSource().getSender()));

        root.then(Commands.literal("reload").executes(ctx -> {
            CommandSender s = ctx.getSource().getSender();
            if (!plugin.reload(s)) plugin.msg(s, "<yellow>Перезагрузка уже идёт");
            else plugin.msg(s, "<gray>Перезагрузка P3rsoPack…");
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
                                plugin.msg(ctx.getSource().getSender(), "<red>Укажите игрока: /p3rsopack gui <окно> <игрок>");
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
                            plugin.msg(ctx.getSource().getSender(), "<red>Укажите игроков: /p3rsopack pack send <игроки>");
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

    /** /menu и т.п.: открыть окно, у которого в конфиге задан command. Права — permission окна. */
    public static LiteralCommandNode<CommandSourceStack> guiCommand(P3rsoPackPlugin plugin, String command) {
        return Commands.literal(command)
                .executes(ctx -> {
                    if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                        plugin.msg(ctx.getSource().getSender(), "<red>Команда только для игроков");
                        return 0;
                    }
                    var gui = plugin.guis().byCommand(command);
                    if (gui == null) {
                        plugin.msg(p, "<red>Это меню сейчас отключено");
                        return 0;
                    }
                    return plugin.guis().open(p, gui.id()) ? Command.SINGLE_SUCCESS : 0;
                }).build();
    }

    private static SuggestionProvider<CommandSourceStack> ids(P3rsoPackPlugin plugin, Function<Definitions, Collection<String>> f) {
        return (ctx, b) -> {
            String rem = b.getRemaining().toLowerCase(Locale.ROOT);
            String ns = plugin.state().settings().namespace() + ":";
            for (String id : f.apply(plugin.state().defs())) {
                if (id.startsWith(rem) || (id.startsWith(ns) && id.substring(ns.length()).startsWith(rem))) b.suggest(id);
            }
            return b.buildFuture();
        };
    }

    /** Key-аргумент без namespace превращается в minecraft:… — подставляем namespace P3rsoPack. */
    private static String resolve(P3rsoPackPlugin plugin, Key key, Set<String> known) {
        String full = key.asString();
        if (known.contains(full)) return full;
        String alt = plugin.state().settings().namespace() + ":" + key.value();
        return known.contains(alt) ? alt : null;
    }

    private static List<Player> players(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getArgument("targets", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
    }

    private static int give(P3rsoPackPlugin plugin, CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        CommandSender s = ctx.getSource().getSender();
        State st = plugin.state();
        String id = resolve(plugin, ctx.getArgument("item", Key.class), st.defs().items().keySet());
        if (id == null) {
            plugin.msg(s, "<red>Нет такого предмета. Список: /p3rsopack list items");
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

    private static int openGui(P3rsoPackPlugin plugin, CommandContext<CommandSourceStack> ctx, List<Player> targets) {
        String id = resolve(plugin, ctx.getArgument("gui", Key.class), plugin.state().defs().guis().keySet());
        if (id == null) {
            plugin.msg(ctx.getSource().getSender(), "<red>Нет такого окна. Список: /p3rsopack list guis");
            return 0;
        }
        for (Player p : targets) plugin.guis().open(p, id);
        return Command.SINGLE_SUCCESS;
    }

    private static int list(P3rsoPackPlugin plugin, CommandSender s, String type) {
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

    private static int packInfo(P3rsoPackPlugin plugin, CommandSender s) {
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
                + "\n<gray>файл:</gray> plugins/P3rsoPack/output/pack.zip");
        return Command.SINGLE_SUCCESS;
    }

    private static int help(P3rsoPackPlugin plugin, CommandSender s) {
        plugin.msg(s, String.join("\n",
                "<gold>P3rsoPack</gold> <gray>— команды:",
                "<yellow>/p3rsopack reload</yellow> <gray>— перечитать конфиги и пересобрать пак",
                "<yellow>/p3rsopack give <игроки> <предмет> [кол-во]",
                "<yellow>/p3rsopack gui <окно> [игроки]",
                "<yellow>/p3rsopack list [items|blocks|furniture|guis|glyphs]",
                "<yellow>/p3rsopack errors</yellow> <gray>— ошибки последней загрузки",
                "<yellow>/p3rsopack pack [info|send [игроки]]"));
        return Command.SINGLE_SUCCESS;
    }
}
