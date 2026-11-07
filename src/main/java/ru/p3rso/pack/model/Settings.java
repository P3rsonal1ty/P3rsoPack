package ru.p3rso.pack.model;

import ru.p3rso.pack.config.Problems;
import ru.p3rso.pack.config.YNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Настройки из config.yml. */
public record Settings(
        String namespace,
        String packDescription,
        HostMode hostMode,
        String bindAddress,
        int port,
        String publicAddress,
        String externalUrl,
        String externalSha1,
        SendPhase sendPhase,
        boolean required,
        String prompt,
        int sendTimeoutSeconds,
        boolean resendOnReload,
        int defaultBackgroundOffset,
        boolean hudEnabled,
        String hudText,
        int hudIntervalTicks,
        boolean resetVanillaNoteBlocks) {

    public enum HostMode { SELF, EXTERNAL, NONE }

    public enum SendPhase { CONFIGURATION, JOIN, NONE }

    public static Settings defaults() {
        return new Settings("p3rso", "P3rso resource pack", HostMode.SELF, "0.0.0.0", 8164, "", "", "",
                SendPhase.CONFIGURATION, false, "", 60, true, -8, false, "", 20, true);
    }

    public static Settings parse(YNode root, Problems problems) {
        Settings d = defaults();
        root.checkKeys(Set.of("namespace", "pack", "gui", "hud", "blocks", "config-version"), problems);
        String ns = root.string("namespace", d.namespace()).trim();
        if (!Ids.validNamespace(ns)) throw root.require("namespace").error("namespace может содержать только a-z, 0-9, _ . -");

        YNode pack = root.get("pack");
        String desc = d.packDescription();
        HostMode mode = d.hostMode();
        String bind = d.bindAddress(), pub = d.publicAddress(), extUrl = d.externalUrl(), extSha = d.externalSha1();
        int port = d.port(), timeout = d.sendTimeoutSeconds();
        SendPhase phase = d.sendPhase();
        boolean required = d.required(), resend = d.resendOnReload();
        String prompt = d.prompt();
        if (pack != null) {
            pack.checkKeys(Set.of("description", "hosting", "send"), problems);
            desc = pack.string("description", desc);
            YNode h = pack.get("hosting");
            if (h != null) {
                h.checkKeys(Set.of("mode", "bind-address", "port", "public-address", "external-url", "external-sha1"), problems);
                mode = enumOf(h, "mode", HostMode.class, mode, List.of("self", "external", "none"));
                bind = h.string("bind-address", bind);
                port = h.integer("port", port, 1, 65535);
                pub = h.string("public-address", pub).trim();
                extUrl = h.string("external-url", extUrl).trim();
                extSha = h.string("external-sha1", extSha).trim().toLowerCase(Locale.ROOT);
                if (mode == HostMode.EXTERNAL && extUrl.isEmpty()) {
                    throw h.error("mode: external, но не указан external-url");
                }
                if (!extSha.isEmpty() && !extSha.matches("[0-9a-f]{40}")) {
                    throw h.require("external-sha1").error("external-sha1 должен быть 40 hex-символов (или пусто — тогда берётся SHA-1 собранного пака)");
                }
            }
            YNode s = pack.get("send");
            if (s != null) {
                s.checkKeys(Set.of("phase", "required", "prompt", "timeout-seconds", "resend-on-reload"), problems);
                phase = enumOf(s, "phase", SendPhase.class, phase, List.of("configuration", "join", "none"));
                required = s.bool("required", required);
                prompt = s.string("prompt", prompt);
                timeout = s.integer("timeout-seconds", timeout, 5, 600);
                resend = s.bool("resend-on-reload", resend);
            }
        }
        int bgOffset = d.defaultBackgroundOffset();
        YNode gui = root.get("gui");
        if (gui != null) {
            gui.checkKeys(Set.of("default-background-offset"), problems);
            bgOffset = gui.integer("default-background-offset", bgOffset);
        }
        boolean hud = d.hudEnabled();
        String hudText = d.hudText();
        int hudInterval = d.hudIntervalTicks();
        YNode h = root.get("hud");
        if (h != null) {
            h.checkKeys(Set.of("enabled", "text", "interval-ticks"), problems);
            hud = h.bool("enabled", hud);
            hudText = h.string("text", hudText);
            hudInterval = h.integer("interval-ticks", hudInterval, 1, 20 * 60);
        }
        boolean resetNb = d.resetVanillaNoteBlocks();
        YNode b = root.get("blocks");
        if (b != null) {
            b.checkKeys(Set.of("reset-vanilla-note-blocks"), problems);
            resetNb = b.bool("reset-vanilla-note-blocks", resetNb);
        }
        return new Settings(ns, desc, mode, bind, port, pub, extUrl, extSha, phase, required, prompt, timeout, resend,
                bgOffset, hud, hudText, hudInterval, resetNb);
    }

    private static <E extends Enum<E>> E enumOf(YNode parent, String key, Class<E> type, E def, List<String> names) {
        YNode n = parent.get(key);
        if (n == null) return def;
        String v = n.asString().trim().toLowerCase(Locale.ROOT);
        int idx = names.indexOf(v);
        if (idx < 0) throw n.error("неизвестное значение '" + v + "', допустимо: " + String.join(", ", names));
        return type.getEnumConstants()[idx];
    }
}
