package dev.atelier.model;

import dev.atelier.config.Source;

import java.util.List;
import java.util.Map;

/** Окно (GUI) из guis/*.yml. */
public record GuiDef(
        String id,
        Source source,
        String title,
        int rows,
        String background,
        int backgroundOffset,
        int titleOffset,
        List<GuiItem> items,
        List<Action> openActions) {

    /**
     * Элемент окна. {@code item} — id кастомного предмета или null; {@code material} — ванильный материал.
     * {@code actions}: ключ — тип клика (any, left, right, shift_left, shift_right, middle, drop), значение — действия.
     */
    public record GuiItem(String key, Source source, List<Integer> slots, String item, String material, int amount,
                          String name, List<String> lore, String itemModel, Map<String, List<Action>> actions) {}

    /** Действие по клику. */
    public record Action(Type type, String value, Source source) {
        public enum Type { COMMAND, CONSOLE, MESSAGE, ACTIONBAR, OPEN, CLOSE, SOUND, GIVE, REFRESH }
    }
}
