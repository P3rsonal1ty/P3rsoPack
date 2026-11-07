package dev.atelier.host;

import dev.atelier.AtelierPlugin;
import dev.atelier.State;
import dev.atelier.model.Settings;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Отправка ресурспака игрокам. Режимы:
 * <ul>
 *   <li>фаза configuration (по умолчанию) — пак загружается ещё до входа в мир, игрок не видит «фиолетовых кубов»;</li>
 *   <li>join — после входа;</li>
 *   <li>none — не отправлять (например, пак отправляет прокси).</li>
 * </ul>
 * UUID пака зависит только от namespace, поэтому на всех серверах сети он одинаков: при переходе между
 * серверами с тем же хешем клиент не перекачивает пак.
 */
public final class PackService implements Listener {
    private final AtelierPlugin plugin;
    private final PackHttpServer http;

    public PackService(AtelierPlugin plugin) {
        this.plugin = plugin;
        this.http = new PackHttpServer(plugin.getLogger());
    }

    public static UUID packId(String namespace) {
        return UUID.nameUUIDFromBytes(("atelier-pack:" + namespace).getBytes(StandardCharsets.UTF_8));
    }

    /** Вызывается после успешной сборки пака. */
    public void update(State state) {
        Settings s = state.settings();
        try {
            if (s.hostMode() == Settings.HostMode.SELF) {
                http.setPack(state.pack(), state.packSha1());
                if (!http.running() || changedAddress(s)) {
                    http.start(s.bindAddress(), s.port());
                    boundBind = s.bindAddress();
                    boundPort = s.port();
                }
            } else {
                http.stop();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось запустить HTTP-сервер пака на " + s.bindAddress() + ":" + s.port()
                    + " — " + e.getMessage() + ". Порт занят? Поменяйте pack.hosting.port в config.yml");
        }
    }

    private String boundBind;
    private int boundPort;

    private boolean changedAddress(Settings s) {
        return !s.bindAddress().equals(boundBind) || s.port() != boundPort;
    }

    public void shutdown() { http.stop(); }

    /** SHA-1, который сообщаем клиенту. */
    public String announcedSha1(State st) {
        Settings s = st.settings();
        if (s.hostMode() == Settings.HostMode.EXTERNAL && !s.externalSha1().isEmpty()) return s.externalSha1();
        return st.packSha1();
    }

    /** URL пака для конкретного подключения (адрес, по которому зашёл игрок, если public-address пуст). */
    public String url(State st, InetSocketAddress virtualHost) {
        Settings s = st.settings();
        if (s.hostMode() == Settings.HostMode.EXTERNAL) return s.externalUrl();
        String base;
        String pub = s.publicAddress();
        if (!pub.isEmpty()) {
            base = pub.contains("://") ? stripSlash(pub) : "http://" + (pub.contains(":") ? pub : pub + ":" + s.port());
        } else {
            String host = virtualHost != null ? virtualHost.getHostString() : null;
            if (host == null || host.isEmpty()) host = Bukkit.getIp();
            if (host == null || host.isEmpty() || host.equals("0.0.0.0")) host = "127.0.0.1";
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            base = "http://" + host + ":" + s.port();
        }
        return base + "/" + st.packSha1() + ".zip";
    }

    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    public ResourcePackRequest request(State st, InetSocketAddress virtualHost, ResourcePackCallback callback) {
        Settings s = st.settings();
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(st.packId(), URI.create(url(st, virtualHost)), announcedSha1(st));
        ResourcePackRequest.Builder b = ResourcePackRequest.resourcePackRequest().packs(info).required(s.required()).replace(false);
        if (!s.prompt().isEmpty()) b.prompt(plugin.text().parse(s.prompt(), null));
        if (callback != null) b.callback(callback);
        return b.build();
    }

    private boolean ready(State st) {
        return st.pack() != null && st.settings().hostMode() != Settings.HostMode.NONE;
    }

    /** Отправить пак игроку (например после /reload). */
    public void send(Player player) {
        State st = plugin.state();
        if (!ready(st)) return;
        player.sendResourcePacks(request(st, player.getVirtualHost(), null));
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent e) {
        State st = plugin.state();
        if (st.settings().sendPhase() != Settings.SendPhase.CONFIGURATION || !ready(st)) return;
        Audience audience = e.getConnection().getAudience();
        InetSocketAddress vh = e.getConnection() instanceof PlayerConnection pc ? pc.getVirtualHost() : null;
        CountDownLatch done = new CountDownLatch(1);
        ResourcePackCallback cb = ResourcePackCallback.onTerminal((id, a) -> done.countDown(), (id, a) -> done.countDown());
        audience.sendResourcePacks(request(st, vh, cb));
        try {
            if (!done.await(st.settings().sendTimeoutSeconds(), TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Игрок " + e.getConnection().getProfile().getName()
                        + " не загрузил ресурспак за " + st.settings().sendTimeoutSeconds() + " с — пускаем дальше");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        State st = plugin.state();
        if (st.settings().sendPhase() == Settings.SendPhase.JOIN && ready(st)) send(e.getPlayer());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        State st = plugin.state();
        if (st.packId() == null || !st.packId().equals(e.getID())) return;
        switch (e.getStatus()) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> plugin.getLogger().warning("Ресурспак у " + e.getPlayer().getName()
                    + ": " + e.getStatus() + " (URL " + url(st, e.getPlayer().getVirtualHost())
                    + "). Проверьте, что порт " + st.settings().port() + " открыт снаружи, или задайте pack.hosting.public-address");
            case DECLINED -> {
                if (st.settings().required()) {
                    e.getPlayer().kick(plugin.text().parse("<red>Для игры на сервере нужен ресурспак", null));
                }
            }
            default -> {}
        }
    }
}
