package ru.p3rso.pack;

import ru.p3rso.pack.api.P3rsoPackReloadEvent;
import ru.p3rso.pack.block.BlockListener;
import ru.p3rso.pack.block.BlockManager;
import ru.p3rso.pack.command.PackCommand;
import ru.p3rso.pack.config.ConfigException;
import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.config.YNode;
import ru.p3rso.pack.furniture.FurnitureListener;
import ru.p3rso.pack.furniture.FurnitureManager;
import ru.p3rso.pack.gui.GuiListener;
import ru.p3rso.pack.gui.GuiManager;
import ru.p3rso.pack.host.PackService;
import ru.p3rso.pack.item.ItemFactory;
import ru.p3rso.pack.model.BlockVariations;
import ru.p3rso.pack.model.DefinitionLoader;
import ru.p3rso.pack.model.Definitions;
import ru.p3rso.pack.model.Settings;
import ru.p3rso.pack.pack.PackGenerator;
import ru.p3rso.pack.text.TextService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Точка входа. Работает на Paper и Folia: только региональные/entity/async-планировщики. */
public final class P3rsoPackPlugin extends JavaPlugin implements Listener {
    private static P3rsoPackPlugin instance;

    private volatile State state = State.empty(Settings.defaults());
    private final AtomicBoolean reloading = new AtomicBoolean();

    private TextService text;
    private ItemFactory items;
    private GuiManager guis;
    private BlockManager blocks;
    private FurnitureManager furniture;
    private PackService packs;

    public static P3rsoPackPlugin instance() { return instance; }

    public State state() { return state; }

    public TextService text() { return text; }

    public ItemFactory items() { return items; }

    public GuiManager guis() { return guis; }

    public BlockManager blocks() { return blocks; }

    public FurnitureManager furniture() { return furniture; }

    public PackService packs() { return packs; }

    @Override
    public void onEnable() {
        instance = this;
        copyDefaults();
        text = new TextService(this);
        items = new ItemFactory(this);
        guis = new GuiManager(this);
        blocks = new BlockManager(this);
        furniture = new FurnitureManager(this);
        packs = new PackService(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(new GuiListener(this, guis), this);
        pm.registerEvents(new BlockListener(this, blocks), this);
        pm.registerEvents(new FurnitureListener(this, furniture), this);
        pm.registerEvents(packs, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ev -> {
            ev.registrar().register(PackCommand.build(this), "P3rsoPack: кастомные предметы, GUI, блоки и мебель", List.of("pp"));
            // команды окон (guis/*.yml → command:). Новые команды появляются после перезапуска сервера.
            for (var gui : state.defs().guis().values()) {
                if (gui.command() == null) continue;
                try {
                    ev.registrar().register(PackCommand.guiCommand(this, gui.command()), "Открыть меню " + gui.id(), List.of());
                } catch (RuntimeException e) {
                    getLogger().warning("Не удалось зарегистрировать /" + gui.command() + ": " + e.getMessage());
                }
            }
        });

        // первая загрузка синхронно: предметы должны существовать до входа игроков
        doReload(Bukkit.getConsoleSender());
        checkPaperConfig();
    }

    @Override
    public void onDisable() {
        if (packs != null) packs.shutdown();
        instance = null;
    }

    // ------------------------------------------------------------------ reload

    /** Асинхронная перезагрузка (вызывается командой). */
    public boolean reload(CommandSender sender) {
        if (reloading.get()) return false;
        Bukkit.getAsyncScheduler().runNow(this, t -> doReload(sender));
        return true;
    }

    private void doReload(CommandSender sender) {
        if (!reloading.compareAndSet(false, true)) return;
        long start = System.nanoTime();
        try {
            Path dir = getDataFolder().toPath();
            Problems problems = new Problems();
            Settings settings;
            try (Reader r = Files.newBufferedReader(dir.resolve("config.yml"), StandardCharsets.UTF_8)) {
                settings = Settings.parse(YNode.parse("config.yml", r), problems);
            } catch (ConfigException e) {
                report(sender, problems);
                msg(sender, "<red>Ошибка в config.yml, перезагрузка отменена: <white>" + esc(e.describe()));
                return;
            } catch (IOException e) {
                msg(sender, "<red>Не удалось прочитать config.yml: " + esc(e.getMessage()));
                return;
            }
            Definitions defs = new DefinitionLoader(dir, settings.namespace(), new BukkitEnvironment(), problems).load();
            Map<String, Integer> persisted = readVariations(dir.resolve("data/block-variations.yml"));
            Map<String, Integer> variations = BlockVariations.assign(defs.blocks(), persisted, problems);
            Map<String, Integer> merged = new TreeMap<>(persisted);
            merged.putAll(variations);
            if (!merged.equals(persisted)) writeVariations(dir.resolve("data/block-variations.yml"), merged);

            PackGenerator.Result pack = new PackGenerator(settings, dir.resolve("pack"), problems).generate(defs, variations);
            Path out = dir.resolve("output/pack.zip");
            Files.createDirectories(out.getParent());
            Files.write(out, pack.zip());

            State old = state;
            if (old.defs() != Definitions.EMPTY) guis.closeAll();
            State next = new State(settings, pack.resolved(), Map.copyOf(variations), State.index(pack.resolved(), variations),
                    problems, pack.zip(), pack.sha1(), PackService.packId(settings.namespace()));
            state = next;
            items.invalidate();
            packs.update(next);
            startHudForOnline();

            Definitions d = next.defs();
            long ms = (System.nanoTime() - start) / 1_000_000;
            report(sender, problems);
            msg(sender, "<green>P3rsoPack загружен за " + ms + " мс: <white>" + d.items().size() + "</white> предметов, <white>"
                    + d.blocks().size() + "</white> блоков, <white>" + d.furniture().size() + "</white> мебели, <white>"
                    + d.guis().size() + "</white> окон, <white>" + d.glyphs().size() + "</white> глифов. Пак "
                    + (pack.zip().length / 1024) + " КБ, sha1 <gray>" + pack.sha1().substring(0, 12) + "…</gray>"
                    + (problems.errorCount() > 0 ? " <red>Ошибок: " + problems.errorCount() : "")
                    + (problems.warningCount() > 0 ? " <yellow>Предупреждений: " + problems.warningCount() : ""));

            if (old.packSha1() != null && !old.packSha1().equals(next.packSha1()) && settings.resendOnReload()
                    && settings.sendPhase() != Settings.SendPhase.NONE) {
                for (Player p : Bukkit.getOnlinePlayers()) p.getScheduler().run(this, t -> packs.send(p), null);
            }
            try {
                new P3rsoPackReloadEvent(!Bukkit.isPrimaryThread()).callEvent();
            } catch (IllegalStateException ignored) {
                // событие с неподходящего потока — не критично
            }
        } catch (Exception e) {
            getSLF4JLogger().error("Ошибка при перезагрузке P3rsoPack", e);
            msg(sender, "<red>Ошибка при перезагрузке: " + esc(String.valueOf(e.getMessage())) + " — подробности в консоли");
        } finally {
            reloading.set(false);
        }
    }

    private void report(CommandSender sender, Problems problems) {
        for (Problems.Problem p : problems.all()) {
            boolean err = p.level() == Problems.Level.ERROR;
            if (err) getLogger().severe(p.describe());
            else getLogger().warning(p.describe());
            if (!(sender instanceof Player)) continue;
            msg(sender, (err ? "<red>✖ " : "<yellow>⚠ ") + "<gray>" + esc(p.file() + (p.line() > 0 ? ":" + p.line() : "")) + "</gray> " + esc(p.message()));
        }
    }

    public void msg(CommandSender sender, String mini) {
        if (sender == null) return;
        Component c = text.mini().deserialize(mini);
        if (sender instanceof Player) sender.sendMessage(c);
        else getComponentLogger().info(c);
    }

    public String esc(String s) { return text.mini().escapeTags(s); }

    private static Map<String, Integer> readVariations(Path file) {
        Map<String, Integer> m = new HashMap<>();
        if (!Files.exists(file)) return m;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        for (String k : y.getKeys(false)) m.put(k, y.getInt(k));
        return m;
    }

    private void writeVariations(Path file, Map<String, Integer> m) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder("# Назначенные кастомным блокам состояния нотного блока. Не меняйте вручную без нужды:\n"
                + "# поставленные в мире блоки определяются именно по этим номерам. Для сети серверов скопируйте файл на все серверы.\n");
        m.forEach((k, v) -> sb.append('"').append(k).append("\": ").append(v).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    /** Выдать предмет (на потоке игрока), излишки — под ноги. */
    public void give(Player player, ItemStack stack) {
        player.getScheduler().run(this, t -> {
            for (ItemStack left : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
        }, null);
    }

    /** Первый запуск: распаковать примеры конфигов и текстур. */
    private void copyDefaults() {
        Path dir = getDataFolder().toPath();
        if (Files.exists(dir.resolve("config.yml"))) return;
        try (ZipFile jar = new ZipFile(getFile())) {
            Enumeration<? extends ZipEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().startsWith("defaults/")) continue;
                Path target = dir.resolve(e.getName().substring("defaults/".length()));
                Files.createDirectories(target.getParent());
                try (InputStream in = jar.getInputStream(e)) {
                    Files.copy(in, target);
                }
            }
            getLogger().info("Созданы примеры конфигов в " + dir);
        } catch (IOException e) {
            getSLF4JLogger().error("Не удалось распаковать примеры", e);
        }
    }

    /** Без этой настройки Paper сам меняет инструмент нотного блока при изменении соседних блоков. */
    private void checkPaperConfig() {
        if (state.defs().blocks().isEmpty()) return;
        Path cfg = Path.of("config", "paper-global.yml");
        if (!Files.exists(cfg)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(cfg.toFile());
        if (!y.getBoolean("block-updates.disable-noteblock-updates", false)) {
            getLogger().warning("============================================================");
            getLogger().warning("Для кастомных блоков включите в config/paper-global.yml:");
            getLogger().warning("  block-updates:");
            getLogger().warning("    disable-noteblock-updates: true");
            getLogger().warning("и перезапустите сервер — иначе блоки могут менять вид при обновлении соседей.");
            getLogger().warning("============================================================");
        }
    }

    // ------------------------------------------------------------------ HUD

    private final Map<java.util.UUID, ScheduledTask> hudTasks = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { startHud(e.getPlayer()); }

    private void startHudForOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) startHud(p);
    }

    /** Постоянный HUD в action bar (config.yml → hud). Одна задача на игрока на его планировщике. */
    private void startHud(Player p) {
        Settings s = state.settings();
        if (!s.hudEnabled() || s.hudText().isBlank() || hudTasks.containsKey(p.getUniqueId())) return;
        ScheduledTask task = p.getScheduler().runAtFixedRate(this, t -> {
            Settings cur = state.settings();
            if (!cur.hudEnabled() || !p.isOnline()) {
                t.cancel();
                hudTasks.remove(p.getUniqueId());
                return;
            }
            p.sendActionBar(text.parse(cur.hudText(), p));
        }, () -> hudTasks.remove(p.getUniqueId()), 1L, s.hudIntervalTicks());
        if (task != null) hudTasks.put(p.getUniqueId(), task);
    }
}
