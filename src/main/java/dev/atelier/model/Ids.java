package dev.atelier.model;

import dev.atelier.config.YNode;

import java.util.Locale;
import java.util.regex.Pattern;

/** Работа с ключами вида {@code namespace:path}. */
public final class Ids {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private Ids() {}

    public static boolean validNamespace(String ns) { return NAMESPACE.matcher(ns).matches(); }

    public static boolean validPath(String p) { return PATH.matcher(p).matches() && !p.startsWith("/") && !p.endsWith("/") && !p.contains("//"); }

    /** Добавляет пространство имён, если его нет; проверяет символы. */
    public static String key(String defaultNamespace, String raw, YNode where) {
        String s = raw.trim();
        if (!s.equals(s.toLowerCase(Locale.ROOT))) {
            throw where.error("'" + raw + "': в ключах и путях допустимы только строчные буквы (a-z, 0-9, _ . - /)");
        }
        int colon = s.indexOf(':');
        String ns = colon < 0 ? defaultNamespace : s.substring(0, colon);
        String path = colon < 0 ? s : s.substring(colon + 1);
        if (path.endsWith(".png") || path.endsWith(".json")) {
            throw where.error("'" + raw + "': укажите путь без расширения (.png/.json добавится автоматически)");
        }
        if (!validNamespace(ns) || !validPath(path)) {
            throw where.error("'" + raw + "': недопустимый ключ — разрешены a-z, 0-9, _ . - и / в пути");
        }
        return ns + ":" + path;
    }

    public static String namespace(String key) { return key.substring(0, key.indexOf(':')); }

    public static String path(String key) { return key.substring(key.indexOf(':') + 1); }

    /** Путь файла в паке: {@code assets/<ns>/<folder>/<path><ext>}. */
    public static String asset(String key, String folder, String ext) {
        return "assets/" + namespace(key) + "/" + folder + "/" + path(key) + ext;
    }
}
